package com.echopanel.app.domain.repository

import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.ScriptEntry
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
        secondsSinceQuestion: Float? = null,
    ): Result<TurnResult>

    suspend fun fetchFinalReport(sessionId: String): Result<FinalReport>

    /** Fetches a signed Agora RTC token scoped to this session's voice channel. */
    suspend fun fetchAgoraToken(sessionId: String): Result<AgoraToken>


    suspend fun startAgent(sessionId: String): Result<Unit>


    suspend fun fetchLatestScenario(sessionId: String): Result<ScenarioCard?>

    /** Fetches turns logged after [sinceIndex] (exclusive) for the live transcript. */
    suspend fun fetchTurns(sessionId: String, sinceIndex: Int): Result<List<LoggedTurn>>

    // --- Proctoring / cheating detection --------------------------------

    /** Reports one on-device video/audio integrity signal; may return a newly raised alert. */
    suspend fun reportCheatSignal(
        sessionId: String,
        signalType: ClientCheatSignalType,
        detail: String = "",
        reportedStrength: Float? = null,
        transcriptTimestampMs: Long = 0,
    ): Result<CheatAlert?>

    /** Polls the session's cumulative integrity status — same data for interviewer and candidate. */
    suspend fun fetchProctoringStatus(sessionId: String): Result<List<CheatAlert>>

    suspend fun acknowledgeCheatFlag(sessionId: String, flagId: String): Result<Unit>

    // --- Shared live script panel ---------------------------------------

    /** Fetches script entries after [sinceIndex] — polled by BOTH interviewer and candidate UIs. */
    suspend fun fetchScript(sessionId: String, sinceIndex: Int = 0): Result<List<ScriptEntry>>

    /** Asks one persona for fresh suggested questions grounded in the Context Graph. */
    suspend fun suggestScriptQuestions(
        sessionId: String,
        persona: PersonaRole,
        topicHint: String = "",
        count: Int = 3,
    ): Result<List<ScriptEntry>>

    /** The human interviewer's own typed question, added to the same shared script. */
    suspend fun addCustomScriptQuestion(
        sessionId: String,
        text: String,
        persona: PersonaRole? = null,
        topicHint: String = "",
    ): Result<List<ScriptEntry>>

    suspend fun markScriptEntryUsed(sessionId: String, entryId: String): Result<Unit>
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