package com.echopanel.app.domain.repository

import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.model.TurnResult
import kotlinx.coroutines.flow.Flow

interface InterviewRepository {

    suspend fun createSession(
        candidateName: String,
        activePersonas: List<PersonaRole>,
    ): Result<InterviewSession>

    suspend fun logConsent(sessionId: String): Result<Unit>

    suspend fun submitTurn(
        sessionId: String,
        candidateText: String,
        topicHint: String,
        transcriptTimestampMs: Long,
        respondingTo: PersonaRole,
    ): Result<TurnResult>

    suspend fun fetchFinalReport(sessionId: String): Result<FinalReport>

    /** Fetches a signed Agora RTC token scoped to this session's voice channel. */
    suspend fun fetchAgoraToken(sessionId: String): Result<AgoraToken>

    /**
     * Starts the Agora Conversational AI agent for this session's channel.
     * Call this AFTER joinCall has succeeded, so the agent doesn't speak
     * into an empty room.
     */
    suspend fun startAgent(sessionId: String): Result<Unit>
}

/**
 * Domain-layer contract for the Agora voice call (RTC + Signaling +
 * Conversational AI Engine client toolkit). Transcript and agent-state
 * updates arrive over Agora's Signaling (RTM) channel, delivered via the
 * toolkit's event handler — see AgoraCallRepositoryImpl for the concrete
 * wiring.
 */
interface AgoraCallRepository {

    suspend fun joinCall(
        sessionId: String,
        agoraToken: String,
        channelName: String,
        rtmToken: String,
        rtmUserAccount: String,
    ): Result<Unit>

    suspend fun leaveCall()

    /** Live transcript entries as they're produced — both candidate and agent speech. */
    fun observeTranscript(): Flow<TranscriptTurn>

    /** Live agent activity state (silent/listening/thinking/speaking), for a status indicator. */
    fun observeAgentState(): Flow<AgentActivityState>

    /** Interrupts the agent mid-speech — supports the "interruptible" requirement. */
    suspend fun interruptAgent(): Result<Unit>
}
