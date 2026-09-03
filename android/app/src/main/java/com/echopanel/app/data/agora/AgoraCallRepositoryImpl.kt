package com.echopanel.app.data.agora

import android.content.Context
import com.echopanel.app.BuildConfig
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.repository.AgoraCallRepository
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgoraCallRepositoryImpl @Inject constructor(
    private val appContext: Context,
) : AgoraCallRepository {

    private var rtcEngine: RtcEngine? = null

    private val transcriptEvents = MutableSharedFlow<TranscriptTurn>(replay = 0, extraBufferCapacity = 32)
    private val agentStateEvents = MutableSharedFlow<AgentActivityState>(replay = 1, extraBufferCapacity = 8)

    companion object {
        // MUST match CANDIDATE_UID = "1001" in your Python backend (agora_agent_service.py)
        const val CANDIDATE_RTC_UID = 1001
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

        val rtcConfig = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = BuildConfig.AGORA_APP_ID
            mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                    // Successfully joined channel with CANDIDATE_RTC_UID
                    // Your ViewModel / Session flow should now call /sessions/{id}/agent/start
                }

                override fun onError(err: Int) {
                    // Handle Agora errors
                }

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    // Remote entity (Agent with UID 9999) joined
                }
            }
        }

        val engine = RtcEngine.create(rtcConfig).apply {
            enableAudio()
            setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_GAME_STREAMING
            )
            setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            muteLocalAudioStream(false) // Guarantee local mic is unmuted
        }
        rtcEngine = engine

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true // Explicitly publish candidate's mic
            autoSubscribeAudio = true     // Hear the agent's voice
        }

        // Pass CANDIDATE_RTC_UID (1001) instead of 0
        val res = engine.joinChannel(agoraToken, channelName, CANDIDATE_RTC_UID, options)
        if (res != 0) {
            throw RuntimeException("Agora joinChannel failed with error code: $res")
        }
    }

    override suspend fun leaveCall() {
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
    }

    override fun observeTranscript(): Flow<TranscriptTurn> = transcriptEvents

    override fun observeAgentState(): Flow<AgentActivityState> = agentStateEvents

    override suspend fun interruptAgent(): Result<Unit> = runCatching {
        // If needed, you can mute local audio or send an interrupt signal
    }
}