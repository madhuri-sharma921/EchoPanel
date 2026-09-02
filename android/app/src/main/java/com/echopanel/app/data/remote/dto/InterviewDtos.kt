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
)

@Serializable
data class AgoraTurnResponseDto(
    @SerialName("next_persona") val nextPersona: PersonaRoleDto,
    @SerialName("spoken_text") val spokenText: String,
    @SerialName("is_vague") val isVague: Boolean,
    @SerialName("contradiction_detected") val contradictionDetected: Boolean,
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
