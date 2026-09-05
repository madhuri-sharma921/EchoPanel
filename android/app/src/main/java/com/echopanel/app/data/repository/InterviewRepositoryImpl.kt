package com.echopanel.app.data.repository

import com.echopanel.app.data.remote.api.EchoPanelApi
import com.echopanel.app.data.remote.dto.AddCustomQuestionRequestDto
import com.echopanel.app.data.remote.dto.AgoraTurnRequestDto
import com.echopanel.app.data.remote.dto.CheatFlagDto
import com.echopanel.app.data.remote.dto.CheatSeverityDto
import com.echopanel.app.data.remote.dto.CheatSignalTypeDto
import com.echopanel.app.data.remote.dto.PersonaRoleDto
import com.echopanel.app.data.remote.dto.ReportSignalRequestDto
import com.echopanel.app.data.remote.dto.ScriptEntryDto
import com.echopanel.app.data.remote.dto.ScriptQuestionSourceDto
import com.echopanel.app.data.remote.dto.SuggestQuestionsRequestDto
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.CheatSeverity
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.ScriptEntry
import com.echopanel.app.domain.model.ScriptQuestionSource
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
        secondsSinceQuestion: Float?,
    ): Result<TurnResult> = runCatching {
        val response = api.submitTurn(
            AgoraTurnRequestDto(
                sessionId = sessionId,
                candidateText = candidateText,
                topicHint = topicHint,
                transcriptTimestampMs = transcriptTimestampMs,
                respondingTo = respondingTo.toDto(),
                secondsSinceQuestion = secondsSinceQuestion,
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

    override suspend fun fetchTurns(sessionId: String, sinceIndex: Int): Result<List<LoggedTurn>> =
        runCatching {
            val response = api.getTurns(sessionId, sinceIndex)
            response.turns.map { dto ->
                LoggedTurn(
                    index = dto.index,
                    persona = dto.persona.toDomain(),
                    questionText = dto.questionText,
                    candidateAnswer = dto.candidateAnswer,
                    isVague = dto.isVague,
                    contradictionDetected = dto.contradictionDetected,
                    reactionEmoji = dto.reactionEmoji,
                    transcriptTimestampMs = dto.transcriptTimestampMs,
                )
            }
        }

    // --- Proctoring / cheating detection --------------------------------

    override suspend fun reportCheatSignal(
        sessionId: String,
        signalType: ClientCheatSignalType,
        detail: String,
        reportedStrength: Float?,
        transcriptTimestampMs: Long,
    ): Result<CheatAlert?> = runCatching {
        val response = api.reportCheatSignal(
            sessionId,
            ReportSignalRequestDto(
                signalType = signalType.toDto(),
                detail = detail,
                reportedStrength = reportedStrength,
                transcriptTimestampMs = transcriptTimestampMs,
            ),
        )
        response.newFlag?.toDomain()
    }

    override suspend fun fetchProctoringStatus(sessionId: String): Result<List<CheatAlert>> =
        runCatching {
            val response = api.getProctoringStatus(sessionId)
            response.flags.map { it.toDomain() }
        }

    override suspend fun acknowledgeCheatFlag(sessionId: String, flagId: String): Result<Unit> =
        runCatching {
            api.acknowledgeCheatFlag(sessionId, flagId)
            Unit
        }

    // --- Shared live script panel ---------------------------------------

    override suspend fun fetchScript(sessionId: String, sinceIndex: Int): Result<List<ScriptEntry>> =
        runCatching {
            val response = api.getScript(sessionId, sinceIndex)
            response.entries.map { it.toDomain() }
        }

    override suspend fun suggestScriptQuestions(
        sessionId: String,
        persona: PersonaRole,
        topicHint: String,
        count: Int,
    ): Result<List<ScriptEntry>> = runCatching {
        val response = api.suggestScriptQuestions(
            sessionId,
            SuggestQuestionsRequestDto(
                persona = persona.toDto(),
                topicHint = topicHint,
                count = count,
            ),
        )
        response.entries.map { it.toDomain() }
    }

    override suspend fun addCustomScriptQuestion(
        sessionId: String,
        text: String,
        persona: PersonaRole?,
        topicHint: String,
    ): Result<List<ScriptEntry>> = runCatching {
        val response = api.addCustomScriptQuestion(
            sessionId,
            AddCustomQuestionRequestDto(
                text = text,
                persona = persona?.toDto(),
                topicHint = topicHint,
            ),
        )
        response.entries.map { it.toDomain() }
    }

    override suspend fun markScriptEntryUsed(sessionId: String, entryId: String): Result<Unit> =
        runCatching {
            api.markScriptEntryUsed(sessionId, entryId)
            Unit
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

private fun ClientCheatSignalType.toDto(): CheatSignalTypeDto = when (this) {
    ClientCheatSignalType.MULTIPLE_FACES -> CheatSignalTypeDto.MULTIPLE_FACES
    ClientCheatSignalType.NO_FACE_DETECTED -> CheatSignalTypeDto.NO_FACE_DETECTED
    ClientCheatSignalType.GAZE_OFF_SCREEN -> CheatSignalTypeDto.GAZE_OFF_SCREEN
    ClientCheatSignalType.EXTRA_VOICE_DETECTED -> CheatSignalTypeDto.EXTRA_VOICE_DETECTED
    ClientCheatSignalType.BACKGROUND_WHISPERING -> CheatSignalTypeDto.BACKGROUND_WHISPERING
    ClientCheatSignalType.APP_BACKGROUNDED -> CheatSignalTypeDto.APP_BACKGROUNDED
    ClientCheatSignalType.SCREEN_SHARE_OR_MIRROR -> CheatSignalTypeDto.SCREEN_SHARE_OR_MIRROR
}

private fun CheatSeverityDto.toDomain(): CheatSeverity = when (this) {
    CheatSeverityDto.LOW -> CheatSeverity.LOW
    CheatSeverityDto.MEDIUM -> CheatSeverity.MEDIUM
    CheatSeverityDto.HIGH -> CheatSeverity.HIGH
}

private fun CheatFlagDto.toDomain(): CheatAlert = CheatAlert(
    id = id,
    severity = severity.toDomain(),
    summary = summary,
    transcriptTimestampMs = transcriptTimestampMs,
    acknowledged = acknowledged,
)

private fun ScriptQuestionSourceDto.toDomain(): ScriptQuestionSource = when (this) {
    ScriptQuestionSourceDto.SUGGESTED -> ScriptQuestionSource.SUGGESTED
    ScriptQuestionSourceDto.CUSTOM -> ScriptQuestionSource.CUSTOM
}

private fun ScriptEntryDto.toDomain(): ScriptEntry = ScriptEntry(
    id = id,
    source = source.toDomain(),
    text = text,
    persona = persona?.toDomain(),
    used = used,
)