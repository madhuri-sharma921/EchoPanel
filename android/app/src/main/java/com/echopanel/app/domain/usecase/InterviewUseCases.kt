package com.echopanel.app.domain.usecase

import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.PersonaRole
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
    ): Result<TurnResult> = repository.submitTurn(
        sessionId = sessionId,
        candidateText = candidateText,
        topicHint = topicHint,
        transcriptTimestampMs = transcriptTimestampMs,
        respondingTo = respondingTo,
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
