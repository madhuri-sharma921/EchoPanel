package com.echopanel.app.data.repository

import com.echopanel.app.data.remote.api.EchoPanelApi
import com.echopanel.app.data.remote.dto.AgoraTurnRequestDto
import com.echopanel.app.data.remote.dto.PersonaRoleDto
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.TurnResult
import com.echopanel.app.domain.model.VerdictItem
import com.echopanel.app.domain.repository.InterviewRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InterviewRepositoryImpl @Inject constructor(
    private val api: EchoPanelApi,
) : InterviewRepository {

    override suspend fun createSession(
        candidateName: String,
        activePersonas: List<PersonaRole>,
    ): Result<InterviewSession> = runCatching {
        val response = api.createSession(
            candidateName,
            activePersonas.map { it.toDto().toWireValue() },
        )
        InterviewSession(
            id = response.id,
            candidateName = response.candidateName,
            activePersonas = response.activePersonas.map { it.toDomain() },
            consentLogged = response.consentLoggedAt != null,
        )
    }

    override suspend fun logConsent(sessionId: String): Result<Unit> = runCatching {
        api.logConsent(sessionId)
        Unit
    }

    override suspend fun submitTurn(
        sessionId: String,
        candidateText: String,
        topicHint: String,
        transcriptTimestampMs: Long,
        respondingTo: PersonaRole,
    ): Result<TurnResult> = runCatching {
        val response = api.submitTurn(
            AgoraTurnRequestDto(
                sessionId = sessionId,
                candidateText = candidateText,
                topicHint = topicHint,
                transcriptTimestampMs = transcriptTimestampMs,
                respondingTo = respondingTo.toDto(),
            )
        )
        TurnResult(
            nextPersona = response.nextPersona.toDomain(),
            spokenText = response.spokenText,
            isVague = response.isVague,
            contradictionDetected = response.contradictionDetected,
        )
    }

    override suspend fun fetchFinalReport(sessionId: String): Result<FinalReport> = runCatching {
        val response = api.getFinalReport(sessionId)
        FinalReport(
            sessionId = response.sessionId,
            candidateName = response.candidateName,
            perCompetency = response.perCompetency.map {
                VerdictItem(
                    competency = it.competency,
                    score = it.score,
                    verdict = it.verdict,
                    transcriptTimestampMs = it.transcriptTimestampMs,
                )
            },
            contradictionsFlagged = response.contradictionsFlagged,
            vagueAnswersFlagged = response.vagueAnswersFlagged,
        )
    }

    override suspend fun fetchAgoraToken(sessionId: String): Result<AgoraToken> = runCatching {
        val response = api.getAgoraToken(sessionId)
        AgoraToken(
            token = response.token,
            appId = response.appId,
            channelName = response.channelName,
            uid = response.uid,
            rtmToken = response.rtmToken,
            rtmUserAccount = response.rtmUserAccount,
        )
    }

    override suspend fun startAgent(sessionId: String): Result<Unit> = runCatching {
        api.startAgent(sessionId)
        Unit
    }

    override suspend fun fetchLatestScenario(sessionId: String): Result<ScenarioCard?> = runCatching {
        val response = api.getLatestScenario(sessionId)
        response.scenario?.let { dto ->
            ScenarioCard(
                persona = dto.persona.toDomain(),
                title = dto.title,
                setting = dto.setting,
                emoji = dto.emoji,
            )
        }
    }
}

private fun PersonaRoleDto.toWireValue(): String = when (this) {
    PersonaRoleDto.TECHNICAL -> "technical"
    PersonaRoleDto.PRODUCT_BUSINESS -> "product_business"
    PersonaRoleDto.BEHAVIOURAL -> "behavioural"
    PersonaRoleDto.CUSTOMER -> "customer"
    PersonaRoleDto.HIRING_MANAGER -> "hiring_manager"
}

private fun PersonaRole.toDto(): PersonaRoleDto = when (this) {
    PersonaRole.TECHNICAL -> PersonaRoleDto.TECHNICAL
    PersonaRole.PRODUCT_BUSINESS -> PersonaRoleDto.PRODUCT_BUSINESS
    PersonaRole.BEHAVIOURAL -> PersonaRoleDto.BEHAVIOURAL
    PersonaRole.CUSTOMER -> PersonaRoleDto.CUSTOMER
    PersonaRole.HIRING_MANAGER -> PersonaRoleDto.HIRING_MANAGER
}

private fun PersonaRoleDto.toDomain(): PersonaRole = when (this) {
    PersonaRoleDto.TECHNICAL -> PersonaRole.TECHNICAL
    PersonaRoleDto.PRODUCT_BUSINESS -> PersonaRole.PRODUCT_BUSINESS
    PersonaRoleDto.BEHAVIOURAL -> PersonaRole.BEHAVIOURAL
    PersonaRoleDto.CUSTOMER -> PersonaRole.CUSTOMER
    PersonaRoleDto.HIRING_MANAGER -> PersonaRole.HIRING_MANAGER
}