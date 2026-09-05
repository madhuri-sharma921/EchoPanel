package com.echopanel.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
enum class PersonaRoleDto {
    @SerialName("technical") TECHNICAL,
    @SerialName("product_business") PRODUCT_BUSINESS,
    @SerialName("behavioural") BEHAVIOURAL,
    @SerialName("customer") CUSTOMER,
    @SerialName("hiring_manager") HIRING_MANAGER,
}

@Serializable
data class CreateSessionResponseDto(
    val id: String,
    @SerialName("candidate_name") val candidateName: String,
    @SerialName("active_personas") val activePersonas: List<PersonaRoleDto>,
    @SerialName("consent_logged_at") val consentLoggedAt: String? = null,
)

@Serializable
data class AgoraTurnRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("candidate_text") val candidateText: String,
    @SerialName("topic_hint") val topicHint: String,
    @SerialName("transcript_timestamp_ms") val transcriptTimestampMs: Long,
    @SerialName("responding_to") val respondingTo: PersonaRoleDto,
    @SerialName("seconds_since_question") val secondsSinceQuestion: Float? = null,
)

@Serializable
data class AgoraTurnResponseDto(
    @SerialName("next_persona") val nextPersona: PersonaRoleDto,
    @SerialName("spoken_text") val spokenText: String,
    @SerialName("is_vague") val isVague: Boolean,
    @SerialName("contradiction_detected") val contradictionDetected: Boolean,
    @SerialName("new_cheat_flag_severity") val newCheatFlagSeverity: CheatSeverityDto? = null,
    @SerialName("new_cheat_flag_summary") val newCheatFlagSummary: String? = null,
)

@Serializable
data class VerdictItemDto(
    val competency: String,
    val score: Float,
    val verdict: String,
    @SerialName("supporting_claim_id") val supportingClaimId: String,
    @SerialName("transcript_timestamp_ms") val transcriptTimestampMs: Long,
)

@Serializable
data class FinalReportDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("candidate_name") val candidateName: String,
    @SerialName("per_competency") val perCompetency: List<VerdictItemDto>,
    @SerialName("contradictions_flagged") val contradictionsFlagged: Int,
    @SerialName("vague_answers_flagged") val vagueAnswersFlagged: Int,
)

@Serializable
data class AgoraTokenResponseDto(
    val token: String,
    @SerialName("app_id") val appId: String,
    @SerialName("channel_name") val channelName: String,
    val uid: Int,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("rtm_token") val rtmToken: String,
    @SerialName("rtm_user_account") val rtmUserAccount: String,
)

@Serializable
data class StartAgentResponseDto(
    @SerialName("agent_id") val agentId: String,
    val status: String,
)

@Serializable
data class ScenarioCardDto(
    val persona: PersonaRoleDto,
    val title: String,
    val setting: String,
    val emoji: String,
)

@Serializable
data class ScenarioResponseDto(
    val scenario: ScenarioCardDto? = null,
)

@Serializable
data class TurnLogEntryDto(
    val index: Int,
    val persona: PersonaRoleDto,
    @SerialName("question_text") val questionText: String,
    @SerialName("candidate_answer") val candidateAnswer: String,
    @SerialName("is_vague") val isVague: Boolean = false,
    @SerialName("contradiction_detected") val contradictionDetected: Boolean = false,
    @SerialName("reaction_emoji") val reactionEmoji: String = "",
    @SerialName("transcript_timestamp_ms") val transcriptTimestampMs: Long,
)

@Serializable
data class TurnsResponseDto(
    val turns: List<TurnLogEntryDto> = emptyList(),
)

// --- Cheating / integrity (proctoring) --------------------------------

@Serializable
enum class CheatSeverityDto {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
}

@Serializable
enum class CheatSignalTypeDto {
    @SerialName("multiple_faces") MULTIPLE_FACES,
    @SerialName("no_face_detected") NO_FACE_DETECTED,
    @SerialName("gaze_off_screen") GAZE_OFF_SCREEN,
    @SerialName("extra_voice_detected") EXTRA_VOICE_DETECTED,
    @SerialName("background_whispering") BACKGROUND_WHISPERING,
    @SerialName("app_backgrounded") APP_BACKGROUNDED,
    @SerialName("screen_share_or_mirror") SCREEN_SHARE_OR_MIRROR,
}

@Serializable
data class ReportSignalRequestDto(
    @SerialName("signal_type") val signalType: CheatSignalTypeDto,
    val detail: String = "",
    @SerialName("reported_strength") val reportedStrength: Float? = null,
    @SerialName("transcript_timestamp_ms") val transcriptTimestampMs: Long = 0,
)

@Serializable
data class CheatFlagDto(
    val id: String,
    val severity: CheatSeverityDto,
    val summary: String,
    @SerialName("transcript_timestamp_ms") val transcriptTimestampMs: Long,
    val acknowledged: Boolean = false,
)

@Serializable
data class ReportSignalResponseDto(
    val recorded: Boolean,
    @SerialName("new_flag") val newFlag: CheatFlagDto? = null,
)

@Serializable
data class ProctoringStatusResponseDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("total_signals") val totalSignals: Int,
    val flags: List<CheatFlagDto> = emptyList(),
    @SerialName("highest_severity") val highestSeverity: String? = null,
)

// --- Shared live script panel -------------------------------------------

@Serializable
enum class ScriptQuestionSourceDto {
    @SerialName("suggested") SUGGESTED,
    @SerialName("custom") CUSTOM,
}

@Serializable
data class ScriptEntryDto(
    val id: String,
    val source: ScriptQuestionSourceDto,
    val text: String,
    val persona: PersonaRoleDto? = null,
    @SerialName("topic_hint") val topicHint: String = "",
    val used: Boolean = false,
)

@Serializable
data class ScriptResponseDto(
    val entries: List<ScriptEntryDto> = emptyList(),
)

@Serializable
data class SuggestQuestionsRequestDto(
    val persona: PersonaRoleDto,
    @SerialName("topic_hint") val topicHint: String = "",
    val count: Int = 3,
)

@Serializable
data class AddCustomQuestionRequestDto(
    val text: String,
    val persona: PersonaRoleDto? = null,
    @SerialName("topic_hint") val topicHint: String = "",
)