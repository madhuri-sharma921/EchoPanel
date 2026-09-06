package com.echopanel.app.data.agora

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.echopanel.app.BuildConfig
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.repository.AgoraCallRepository
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgoraCallRepositoryImpl @Inject constructor(
    private val appContext: Context,
) : AgoraCallRepository {

    private var rtcEngine: RtcEngine? = null

    private val transcriptEvents = MutableSharedFlow<TranscriptTurn>(replay = 0, extraBufferCapacity = 32)
    private val agentStateEvents = MutableSharedFlow<AgentActivityState>(replay = 1, extraBufferCapacity = 8)

    // Bumped every time the local camera pipeline is (re)started — the
    // candidate camera tile keys its retry loop off this so a mid-call
    // camera failure and recovery produces a fresh SurfaceView bound to
    // the newly-restarted capturer, instead of endlessly rebinding a
    // surface to a capture pipeline that already died. See
    // onLocalVideoStateChanged below for what actually triggers a bump.
    private val _localVideoGeneration = MutableStateFlow(0)
    val localVideoGeneration: StateFlow<Int> = _localVideoGeneration

    // Candidate-controlled camera on/off, independent of permission or
    // hardware availability — defaults to on (true), matching the
    // existing behavior before this toggle existed.
    private val _localVideoEnabled = MutableStateFlow(true)
    val localVideoEnabled: StateFlow<Boolean> = _localVideoEnabled

    companion object {
        // MUST match CANDIDATE_UID = "1001" in your Python backend (agora_agent_service.py)
        const val CANDIDATE_RTC_UID = 1001
    }

    private var lastRestartTime = 0L

    private fun restartLocalVideo() {
        _localVideoGeneration.value += 1
    }

    override suspend fun joinCall(
        sessionId: String,
        agoraToken: String,
        channelName: String,
        rtmToken: String,
        rtmUserAccount: String,
    ): Result<Unit> = runCatching {
        // Destroy existing engine if any before recreating
        if (rtcEngine != null) {
            rtcEngine?.leaveChannel()
            RtcEngine.destroy()
            rtcEngine = null
        }
        agentStateEvents.tryEmit(AgentActivityState.UNKNOWN)

        val rtcConfig = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = BuildConfig.AGORA_APP_ID
            mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                    // Candidate joined Agora channel successfully
                    agentStateEvents.tryEmit(AgentActivityState.LISTENING)
                }

                override fun onError(err: Int) {
                }

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    // Remote agent joined channel
                    agentStateEvents.tryEmit(AgentActivityState.LISTENING)
                }

                override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                    when (state) {
                        Constants.REMOTE_AUDIO_STATE_DECODING, Constants.REMOTE_AUDIO_STATE_STARTING -> {
                            agentStateEvents.tryEmit(AgentActivityState.SPEAKING)
                        }
                        Constants.REMOTE_AUDIO_STATE_STOPPED, Constants.REMOTE_AUDIO_STATE_FROZEN -> {
                            agentStateEvents.tryEmit(AgentActivityState.LISTENING)
                        }
                    }
                }

                override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
                    val remoteSpeaker = speakers?.firstOrNull { it.uid != CANDIDATE_RTC_UID && it.uid != 0 }
                    if (remoteSpeaker != null && remoteSpeaker.volume > 5) {
                        agentStateEvents.tryEmit(AgentActivityState.SPEAKING)
                    } else if (agentStateEvents.replayCache.lastOrNull() == AgentActivityState.SPEAKING) {
                        agentStateEvents.tryEmit(AgentActivityState.LISTENING)
                    }
                }

                override fun onLocalVideoStateChanged(
                    source: Constants.VideoSourceType,
                    state: Int,
                    error: Int,
                ) {
                    val LOCAL_VIDEO_STREAM_STATE_FAILED = 3
                    if (state == LOCAL_VIDEO_STREAM_STATE_FAILED) {
                        restartLocalVideo()
                    }
                }
            }
        }

        val engine = RtcEngine.create(rtcConfig).apply {
            enableAudio()
            enableAudioVolumeIndication(200, 3, true)
            setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_GAME_STREAMING
            )
            // Disable Agora camera capture: the candidate video tile and MLKit
            // face proctoring are managed via CameraX (PreviewView) with zero
            // hardware contention, no black tiles, and no blinking.
            enableLocalVideo(false)
            setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            muteLocalAudioStream(false) // Guarantee local mic is unmuted
        }
        rtcEngine = engine

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true // Explicitly publish candidate's mic
            publishCameraTrack = false    // Camera handled directly by CameraX
            autoSubscribeAudio = true     // Hear the agent's voice
            autoSubscribeVideo = false    // Nothing remote publishes video
        }

        // Pass CANDIDATE_RTC_UID (1001) instead of 0
        val res = engine.joinChannel(agoraToken, channelName, CANDIDATE_RTC_UID, options)
        if (res != 0) {
            throw RuntimeException("Agora joinChannel failed with error code: $res")
        }
    }

    override suspend fun leaveCall() {
        agentStateEvents.tryEmit(AgentActivityState.UNKNOWN)
        rtcEngine?.stopPreview()
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
    }

    override fun observeTranscript(): Flow<TranscriptTurn> = transcriptEvents

    override fun observeAgentState(): Flow<AgentActivityState> = agentStateEvents

    override suspend fun interruptAgent(): Result<Unit> = runCatching {
        // If needed, you can mute local audio or send an interrupt signal
    }

    override fun createLocalVideoView(context: Context): SurfaceView? = null

    override fun rebindLocalVideo(surfaceView: SurfaceView) {
        // No-op: video preview is rendered cleanly via CameraX PreviewView
    }

    override fun observeLocalVideoGeneration(): StateFlow<Int> = localVideoGeneration

    override fun setLocalVideoEnabled(enabled: Boolean) {
        _localVideoEnabled.value = enabled
        _localVideoGeneration.value += 1
    }

    override fun observeLocalVideoEnabled(): StateFlow<Boolean> = localVideoEnabled
}