package com.echopanel.app.domain.usecase

import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.ScriptEntry
import com.echopanel.app.domain.model.TurnResult
import com.echopanel.app.domain.repository.InterviewRepository
import javax.inject.Inject

class StartInterviewSessionUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(
        candidateName: String,
        activePersonas: List<PersonaRole>,
    ): Result<InterviewSession> = repository.createSession(candidateName, activePersonas)
}

class LogConsentUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<Unit> =
        repository.logConsent(sessionId)
}

class SubmitCandidateTurnUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        candidateText: String,
        topicHint: String,
        transcriptTimestampMs: Long,
        respondingTo: PersonaRole,
        secondsSinceQuestion: Float? = null,
    ): Result<TurnResult> = repository.submitTurn(
        sessionId = sessionId,
        candidateText = candidateText,
        topicHint = topicHint,
        transcriptTimestampMs = transcriptTimestampMs,
        respondingTo = respondingTo,
        secondsSinceQuestion = secondsSinceQuestion,
    )
}

class GetFinalReportUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<FinalReport> =
        repository.fetchFinalReport(sessionId)
}

class GetAgoraTokenUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<AgoraToken> =
        repository.fetchAgoraToken(sessionId)
}

class StartAgentUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<Unit> =
        repository.startAgent(sessionId)
}

class GetLatestScenarioUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<ScenarioCard?> =
        repository.fetchLatestScenario(sessionId)
}

class GetTurnsUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String, sinceIndex: Int): Result<List<LoggedTurn>> =
        repository.fetchTurns(sessionId, sinceIndex)
}

// --- Proctoring / cheating detection ------------------------------------

class ReportCheatSignalUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        signalType: ClientCheatSignalType,
        detail: String = "",
        reportedStrength: Float? = null,
        transcriptTimestampMs: Long = 0,
    ): Result<CheatAlert?> = repository.reportCheatSignal(
        sessionId, signalType, detail, reportedStrength, transcriptTimestampMs,
    )
}

class GetProctoringStatusUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String): Result<List<CheatAlert>> =
        repository.fetchProctoringStatus(sessionId)
}

class AcknowledgeCheatFlagUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String, flagId: String): Result<Unit> =
        repository.acknowledgeCheatFlag(sessionId, flagId)
}

// --- Shared live script panel -------------------------------------------

class GetScriptUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String, sinceIndex: Int = 0): Result<List<ScriptEntry>> =
        repository.fetchScript(sessionId, sinceIndex)
}

class SuggestScriptQuestionsUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        persona: PersonaRole,
        topicHint: String = "",
        count: Int = 3,
    ): Result<List<ScriptEntry>> = repository.suggestScriptQuestions(sessionId, persona, topicHint, count)
}

class AddCustomScriptQuestionUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(
        sessionId: String,
        text: String,
        persona: PersonaRole? = null,
        topicHint: String = "",
    ): Result<List<ScriptEntry>> = repository.addCustomScriptQuestion(sessionId, text, persona, topicHint)
}

class MarkScriptEntryUsedUseCase @Inject constructor(
    private val repository: InterviewRepository,
) {
    suspend operator fun invoke(sessionId: String, entryId: String): Result<Unit> =
        repository.markScriptEntryUsed(sessionId, entryId)
}