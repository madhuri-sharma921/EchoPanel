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
    // Small reaction emoji shown next to this turn in the UI — null means
    // "no reaction" (e.g. the candidate's own lines, or a persona line with
    // nothing notable flagged). Derived from the backend's isVague /
    // contradictionDetected flags via reactionEmojiFor() below, so there is
    // one single place that decides what each combination looks like.
    val reactionEmoji: String? = null,
)

/**
 * Maps the backend's per-turn signals to a single reaction emoji for the UI.
 *
 * - contradictionDetected wins over vagueness when both are true — being
 *   caught in a contradiction is the more significant moment for the
 *   candidate to see flagged.
 * - A confident, non-contradictory, substantive answer gets an encouraging
 *   👍 rather than no reaction at all, so the panel visibly "reacts" on
 *   every real answer, not only on problems.
 */
fun reactionEmojiFor(isVague: Boolean, contradictionDetected: Boolean): String = when {
    contradictionDetected -> "⚡"
    isVague -> "🤔"
    else -> "👍"
}

data class TurnResult(
    val nextPersona: PersonaRole,
    val spokenText: String,
    val isVague: Boolean,
    val contradictionDetected: Boolean,
)

/**
 * One full round-trip logged by the backend: the question a persona asked
 * plus the candidate's answer, and the signals detected on that answer.
 * Polled from GET /sessions/{id}/turns (see GetTurnsUseCase) to drive the
 * live on-screen transcript, since Agora's voice engine handles the actual
 * conversation entirely off-app and never reports it back to the client
 * on its own.
 */
data class LoggedTurn(
    val index: Int,
    val persona: PersonaRole,
    val questionText: String,
    val candidateAnswer: String,
    val isVague: Boolean,
    val contradictionDetected: Boolean,
    // Content-aware reaction picked by the backend LLM for this specific
    // answer (confident, joking, off-topic like asking for a break,
    // nervous, etc.) — empty string means the backend fell back to the
    // old vague/contradiction-based default (still an emoji, just less
    // specific), never an actually-missing reaction.
    val reactionEmoji: String,
    val transcriptTimestampMs: Long,
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


data class ScenarioCard(
    val persona: PersonaRole,
    val title: String,
    val setting: String,
    val emoji: String,
)