package com.echopanel.app.domain.model

enum class PersonaRole {
    TECHNICAL,
    PRODUCT_BUSINESS,
    BEHAVIOURAL,
    CUSTOMER,
    HIRING_MANAGER;

    val displayName: String
        get() = when (this) {
            TECHNICAL -> "Technical Interviewer"
            PRODUCT_BUSINESS -> "Product / Business Interviewer"
            BEHAVIOURAL -> "Behavioural Interviewer"
            CUSTOMER -> "Customer Interviewer"
            HIRING_MANAGER -> "Hiring Manager"
        }
}

enum class QuestionDepth {
    RECALL, APPLIED, EDGE_CASE
}

data class InterviewSession(
    val id: String,
    val candidateName: String,
    val activePersonas: List<PersonaRole>,
    val consentLogged: Boolean = false,
)

data class TranscriptTurn(
    val speaker: String,
    val text: String,
    val timestampMs: Long,
    val isCandidate: Boolean,
)

data class TurnResult(
    val nextPersona: PersonaRole,
    val spokenText: String,
    val isVague: Boolean,
    val contradictionDetected: Boolean,
)

data class VerdictItem(
    val competency: String,
    val score: Float,
    val verdict: String,
    val transcriptTimestampMs: Long,
)

data class FinalReport(
    val sessionId: String,
    val candidateName: String,
    val perCompetency: List<VerdictItem>,
    val contradictionsFlagged: Int,
    val vagueAnswersFlagged: Int,
)


sealed interface CallState {
    data object Idle : CallState
    data object Connecting : CallState
    data object Connected : CallState
    data class Error(val message: String) : CallState
    data object Ended : CallState
}


data class AgoraToken(
    val token: String,
    val appId: String,
    val channelName: String,
    val uid: Int,
    val rtmToken: String,
    val rtmUserAccount: String,
)

enum class AgentActivityState {
    SILENT, LISTENING, THINKING, SPEAKING, UNKNOWN
}
