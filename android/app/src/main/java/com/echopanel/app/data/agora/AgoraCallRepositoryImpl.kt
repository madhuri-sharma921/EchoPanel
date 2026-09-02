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

    override suspend fun joinCall(
        sessionId: String,
        agoraToken: String,
        channelName: String,
        rtmToken: String,
        rtmUserAccount: String,
    ): Result<Unit> = runCatching {
        val rtcConfig = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = BuildConfig.AGORA_APP_ID
            mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                    // Conversational AI agent auto-joins server-side once
                    // this client successfully joins the channel — see
                    // /sessions/{id}/agent/start, called right after this.
                }

                override fun onError(err: Int) {
                    // Surfaced to the UI via CallState.Error in the ViewModel layer.
                }
            }
        }
        val engine = RtcEngine.create(rtcConfig).apply {
            enableAudio()
            setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
        }
        rtcEngine = engine

        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }
        engine.joinChannel(agoraToken, channelName, 0, options)
    }

    override suspend fun leaveCall() {
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
    }

    override fun observeTranscript(): Flow<TranscriptTurn> = transcriptEvents

    override fun observeAgentState(): Flow<AgentActivityState> = agentStateEvents

    override suspend fun interruptAgent(): Result<Unit> = runCatching {

    }
}