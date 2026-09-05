package com.echopanel.app.domain.repository

import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
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


    suspend fun startAgent(sessionId: String): Result<Unit>


    suspend fun fetchLatestScenario(sessionId: String): Result<ScenarioCard?>

    /** Fetches turns logged after [sinceIndex] (exclusive) for the live transcript. */
    suspend fun fetchTurns(sessionId: String, sinceIndex: Int): Result<List<LoggedTurn>>
}


interface AgoraCallRepository {

    suspend fun joinCall(
        sessionId: String,
        agoraToken: String,
        channelName: String,
        rtmToken: String,
        rtmUserAccount: String,
    ): Result<Unit>

    suspend fun leaveCall()


    fun observeTranscript(): Flow<TranscriptTurn>


    fun observeAgentState(): Flow<AgentActivityState>


    suspend fun interruptAgent(): Result<Unit>
}