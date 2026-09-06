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

    /**
     * Restarts local video capture from scratch — stop, disable, then
     * re-enable and start preview. This is the actual fix for "video
     * works for a while then freezes mid-call and stays frozen even on
     * reopen": reopening the screen only ever created a new SurfaceView
     * and rebound it to the SAME engine's SAME (already-dead) camera
     * capturer, which does nothing if the capturer itself stopped
     * producing frames. This function restarts the capturer itself.
     */
    private fun restartLocalVideo() {
        val engine = rtcEngine ?: return
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) return
        engine.stopPreview()
        engine.enableLocalVideo(false)
        engine.enableLocalVideo(true)
        engine.startPreview()
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

        val rtcConfig = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = BuildConfig.AGORA_APP_ID
            mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                    // Successfully joined channel with CANDIDATE_RTC_UID
                    // Your ViewModel / Session flow should now call /sessions/{id}/agent/start
                }

                override fun onError(err: Int) {
                    // Handle Agora errors. Camera-capture recovery is
                    // handled via the more specific, documented
                    // onLocalVideoStateChanged callback below (state ==
                    // LOCAL_VIDEO_STREAM_STATE_FAILED) rather than here —
                    // that's Agora's purpose-built signal for exactly
                    // this failure, so this generic error callback
                    // doesn't need its own guess at a matching error code.
                }

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    // Remote entity (Agent with UID 9999) joined
                }

                override fun onLocalVideoStateChanged(
                    source: Constants.VideoSourceType,
                    state: Int,
                    error: Int,
                ) {
                    // LOCAL_VIDEO_STREAM_STATE_FAILED is documented by
                    // Agora as raw int value 3 (see Constants class docs)
                    // — the SDK's own onLocalVideoStateChanged signature
                    // takes plain Int state/reason codes, not enum types.
                    // Using the literal directly (with the named constant
                    // as a fallback reference in the comment) avoids
                    // depending on a specific constant name that can
                    // differ slightly across SDK versions/artifacts.
                    // This is Agora's explicit, documented "the camera
                    // stopped producing frames" signal — exactly the
                    // trigger for the reported symptom (video freezes
                    // mid-call, stays frozen even after leaving and
                    // reopening the screen, because nothing was
                    // restarting the actual capturer before this fix).
                    val LOCAL_VIDEO_STREAM_STATE_FAILED = 3
                    if (state == LOCAL_VIDEO_STREAM_STATE_FAILED) {
                        restartLocalVideo()
                    }
                }
            }
        }

        val engine = RtcEngine.create(rtcConfig).apply {
            enableAudio()
            setAudioProfile(
                Constants.AUDIO_PROFILE_SPEECH_STANDARD,
                Constants.AUDIO_SCENARIO_GAME_STREAMING
            )
            // Video for the candidate's own camera tile. The AI panel has no
            // real camera feed of its own — its "video" is a local placeholder
            // animation rendered client-side (see AiInterviewerAvatarView),
            // not a remote Agora video track — so we only need to publish and
            // preview the LOCAL camera here, never subscribe to remote video.
            //
            // Explicit permission check here, not just at the UI layer: this
            // is the fix for the candidate camera tile rendering as a solid
            // black box. Agora's camera capturer fails SILENTLY without
            // CAMERA permission — enableVideo()/startPreview() still
            // "succeed", a surface gets bound, but zero frames ever arrive,
            // which paints as solid black rather than any visible error.
            // Skipping video setup entirely when permission is absent means
            // the tile correctly falls back to the initials placeholder
            // instead of a black box that looks broken.
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasCameraPermission) {
                enableVideo()
                enableLocalVideo(true)
                startPreview()
            }
            setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            muteLocalAudioStream(false) // Guarantee local mic is unmuted
        }
        rtcEngine = engine

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true // Explicitly publish candidate's mic
            publishCameraTrack = true     // Publish candidate's camera (interviewer-side viewing, or a future two-way build)
            autoSubscribeAudio = true     // Hear the agent's voice
            autoSubscribeVideo = false    // Nothing remote publishes video today — the agent has no camera
        }

        // Pass CANDIDATE_RTC_UID (1001) instead of 0
        val res = engine.joinChannel(agoraToken, channelName, CANDIDATE_RTC_UID, options)
        if (res != 0) {
            throw RuntimeException("Agora joinChannel failed with error code: $res")
        }
    }

    override suspend fun leaveCall() {
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

    /**
     * Creates and binds a SurfaceView showing the candidate's OWN camera
     * feed — this is the real interviewee video tile. Call once the engine
     * exists (after joinCall() succeeds); returns null if the engine isn't
     * up yet (e.g. camera permission was declined, so joinCall itself
     * skipped video setup entirely — see enableVideo() call above, which
     * fails silently and harmlessly if there's no camera hardware/permission).
     */
    override fun createLocalVideoView(context: Context): SurfaceView? {
        val engine = rtcEngine ?: return null
        if (!_localVideoEnabled.value) return null
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) return null
        val surfaceView = SurfaceView(context)
        engine.setupLocalVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)
        )
        return surfaceView
    }

    override fun rebindLocalVideo(surfaceView: SurfaceView) {
        val engine = rtcEngine ?: return
        // Re-issuing setupLocalVideo on the SAME SurfaceView re-establishes
        // the renderer's internal surface handle. This is cheap and safe to
        // call on every AndroidView update — it's what recovers a tile that
        // would otherwise stay frozen after the underlying Surface was
        // recreated by the platform (e.g. after the view left and rejoined
        // the composition, or the window lost/regained focus).
        engine.setupLocalVideo(
            VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)
        )
    }

    override fun observeLocalVideoGeneration(): StateFlow<Int> = localVideoGeneration

    override fun setLocalVideoEnabled(enabled: Boolean) {
        _localVideoEnabled.value = enabled
        val engine = rtcEngine ?: return
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) return
        if (enabled) {
            // Re-enabling: restart capture the same way restartLocalVideo()
            // does, and bump the generation counter so the UI fetches a
            // fresh SurfaceView bound to the freshly-started capturer —
            // reusing an old surface here would hit the same "rebinding a
            // dead pipeline" problem the mid-call-failure fix addressed.
            engine.enableLocalVideo(true)
            engine.startPreview()
            engine.muteLocalVideoStream(false)
            _localVideoGeneration.value += 1
        } else {
            // Turning off: mute the outgoing stream AND stop the capturer
            // (not just muteLocalVideoStream alone) — this is a genuine
            // "off", not a still-recording-but-not-sending state, which
            // matters for a candidate who wants the camera light off.
            engine.muteLocalVideoStream(true)
            engine.stopPreview()
            engine.enableLocalVideo(false)
        }
    }

    override fun observeLocalVideoEnabled(): StateFlow<Boolean> = localVideoEnabled
}