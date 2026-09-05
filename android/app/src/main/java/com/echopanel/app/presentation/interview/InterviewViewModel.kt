package com.echopanel.app.presentation.interview

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echopanel.app.data.proctoring.FaceProctoringAnalyzer
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.CallState
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.ScriptEntry
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.model.reactionEmojiFor
import com.echopanel.app.domain.repository.AgoraCallRepository
import com.echopanel.app.domain.usecase.AddCustomScriptQuestionUseCase
import com.echopanel.app.domain.usecase.GetAgoraTokenUseCase
import com.echopanel.app.domain.usecase.GetLatestScenarioUseCase
import com.echopanel.app.domain.usecase.GetProctoringStatusUseCase
import com.echopanel.app.domain.usecase.GetScriptUseCase
import com.echopanel.app.domain.usecase.GetTurnsUseCase
import com.echopanel.app.domain.usecase.LogConsentUseCase
import com.echopanel.app.domain.usecase.MarkScriptEntryUsedUseCase
import com.echopanel.app.domain.usecase.ReportCheatSignalUseCase
import com.echopanel.app.domain.usecase.StartAgentUseCase
import com.echopanel.app.domain.usecase.StartInterviewSessionUseCase
import com.echopanel.app.domain.usecase.SuggestScriptQuestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InterviewUiState(
    val sessionId: String? = null,
    val callState: CallState = CallState.Idle,
    val transcript: List<TranscriptTurn> = emptyList(),
    val agentState: AgentActivityState = AgentActivityState.UNKNOWN,
    val showConsentDialog: Boolean = false,
    val consentGiven: Boolean = false,
    val errorMessage: String? = null,
    val scenario: ScenarioCard? = null,
    // Cheating / integrity alerts, most recent last. Shared data — the
    // interviewer's screen and (if enabled) the candidate's own screen
    // poll the exact same backend endpoint, so both ever see the same
    // list; nothing here is interviewer-only surveillance.
    val cheatAlerts: List<CheatAlert> = emptyList(),
    // The shared live script panel: AI-suggested questions plus whatever
    // the human interviewer has typed in — both visible to both sides.
    val script: List<ScriptEntry> = emptyList(),
    val isSuggestingQuestions: Boolean = false,
)

@HiltViewModel
class InterviewViewModel @Inject constructor(
    private val startInterviewSession: StartInterviewSessionUseCase,
    private val logConsent: LogConsentUseCase,
    private val getAgoraToken: GetAgoraTokenUseCase,
    private val startAgentUseCase: StartAgentUseCase,
    private val getLatestScenario: GetLatestScenarioUseCase,
    private val getTurns: GetTurnsUseCase,
    private val getProctoringStatus: GetProctoringStatusUseCase,
    private val reportCheatSignal: ReportCheatSignalUseCase,
    private val getScript: GetScriptUseCase,
    private val suggestScriptQuestions: SuggestScriptQuestionsUseCase,
    private val addCustomScriptQuestion: AddCustomScriptQuestionUseCase,
    private val markScriptEntryUsed: MarkScriptEntryUsedUseCase,
    private val agoraCallRepository: AgoraCallRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InterviewUiState())
    val uiState: StateFlow<InterviewUiState> = _uiState.asStateFlow()

    // Tracks which persona most recently asked a question, so "suggest
    // questions" and custom-question submissions default to a sensible
    // persona without the interviewer having to pick one every time.
    private var lastKnownPersona: PersonaRole? = null

    init {
        // These flows stay empty until joinCall() has run, so subscribing
        // up front is safe — no events arrive before a channel is joined.
        viewModelScope.launch {
            agoraCallRepository.observeTranscript().collect { turn ->
                _uiState.update { it.copy(transcript = it.transcript + turn) }
            }
        }
        viewModelScope.launch {
            agoraCallRepository.observeAgentState().collect { state ->
                _uiState.update { it.copy(agentState = state) }
            }
        }
    }

    fun startSession(candidateName: String, personas: List<PersonaRole>) {
        viewModelScope.launch {
            _uiState.update { it.copy(callState = CallState.Connecting) }
            lastKnownPersona = personas.firstOrNull()
            startInterviewSession(candidateName, personas)
                .onSuccess { session ->
                    // Only now is there a real session to consent into —
                    // showing the dialog earlier could let a tap race ahead
                    // of sessionId being set.
                    _uiState.update {
                        it.copy(sessionId = session.id, showConsentDialog = true)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            callState = CallState.Error(error.message ?: "Failed to start session"),
                        )
                    }
                }
        }
    }

    fun onConsentGiven() {
        val sessionId = _uiState.value.sessionId
        if (sessionId == null) {
            _uiState.update { it.copy(errorMessage = "Session not ready yet — please wait a moment and try again.") }
            return
        }
        viewModelScope.launch {
            logConsent(sessionId)
                .onSuccess {
                    _uiState.update {
                        it.copy(consentGiven = true, showConsentDialog = false)
                    }
                    joinVoiceCall(sessionId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message)
                    }
                }
        }
    }

    fun onConsentDeclined() {
        _uiState.update {
            it.copy(showConsentDialog = false, callState = CallState.Ended)
        }
    }


    fun onInterruptAgent() {
        viewModelScope.launch {
            agoraCallRepository.interruptAgent().onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    private fun joinVoiceCall(sessionId: String) {
        viewModelScope.launch {
            getAgoraToken(sessionId)
                .onSuccess { agoraToken ->
                    agoraCallRepository.joinCall(
                        sessionId = sessionId,
                        agoraToken = agoraToken.token,
                        channelName = agoraToken.channelName,
                        rtmToken = agoraToken.rtmToken,
                        rtmUserAccount = agoraToken.rtmUserAccount,
                    ).onSuccess {
                        _uiState.update { it.copy(callState = CallState.Connected) }
                        // Bring the AI panel into the channel now that the
                        // candidate has actually joined — starting it any
                        // earlier risks the agent speaking into an empty room.
                        startAgentUseCase(sessionId).onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    errorMessage = error.message
                                        ?: "Failed to start the AI interview panel",
                                )
                            }
                        }
                        pollForScenario(sessionId)
                        pollForTurns(sessionId)
                        pollForCheatStatus(sessionId)
                        pollForScript(sessionId)
                    }.onFailure { error ->
                        _uiState.update {
                            it.copy(callState = CallState.Error(error.message ?: "Call failed"))
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            callState = CallState.Error(
                                error.message ?: "Failed to fetch Agora token",
                            ),
                        )
                    }
                }
        }
    }

    private fun pollForScenario(sessionId: String) {
        viewModelScope.launch {
            var lastSeenTitle: String? = null
            while (_uiState.value.callState == CallState.Connected) {
                getLatestScenario(sessionId).onSuccess { scenario ->
                    if (scenario?.title != lastSeenTitle) {
                        lastSeenTitle = scenario?.title
                        _uiState.update { it.copy(scenario = scenario) }
                    }
                }
                delay(4000)
            }
        }
    }


    private fun pollForTurns(sessionId: String) {
        viewModelScope.launch {
            var nextIndex = 0
            var pendingReactionEmoji: String? = null
            while (_uiState.value.callState == CallState.Connected) {
                getTurns(sessionId, nextIndex).onSuccess { turns ->
                    if (turns.isNotEmpty()) {
                        val newBubbles = mutableListOf<TranscriptTurn>()
                        for (turn in turns) {
                            lastKnownPersona = turn.persona
                            newBubbles += TranscriptTurn(
                                speaker = turn.persona.displayName,
                                text = turn.questionText,
                                timestampMs = turn.transcriptTimestampMs,
                                isCandidate = false,
                                reactionEmoji = pendingReactionEmoji,
                            )
                            newBubbles += TranscriptTurn(
                                speaker = "You",
                                text = turn.candidateAnswer,
                                timestampMs = turn.transcriptTimestampMs,
                                isCandidate = true,
                            )

                            pendingReactionEmoji = turn.reactionEmoji.ifBlank {
                                reactionEmojiFor(turn.isVague, turn.contradictionDetected)
                            }
                        }
                        _uiState.update { it.copy(transcript = it.transcript + newBubbles) }
                        nextIndex = turns.maxOf { it.index } + 1
                    }
                }
                delay(2000)
            }
        }
    }

    /**
     * Polls the shared cheating/integrity status. Both the interviewer's
     * screen and (if the candidate-side build enables it) the candidate's
     * own screen call this same use case against the same endpoint — see
     * GetProctoringStatusUseCase — so nobody is shown a different picture
     * of what was flagged.
     */
    private fun pollForCheatStatus(sessionId: String) {
        viewModelScope.launch {
            while (_uiState.value.callState == CallState.Connected) {
                getProctoringStatus(sessionId).onSuccess { alerts ->
                    if (alerts.size != _uiState.value.cheatAlerts.size) {
                        _uiState.update { it.copy(cheatAlerts = alerts) }
                    }
                }
                delay(3000)
            }
        }
    }

    /** Polls the shared live script panel — same list for both interviewer and candidate. */
    private fun pollForScript(sessionId: String) {
        viewModelScope.launch {
            while (_uiState.value.callState == CallState.Connected) {
                getScript(sessionId).onSuccess { entries ->
                    if (entries.size != _uiState.value.script.size) {
                        _uiState.update { it.copy(script = entries) }
                    }
                }
                delay(3000)
            }
        }
    }

    /** Asks the backend for fresh suggested questions grounded in the current Context Graph. */
    fun onRequestSuggestedQuestions() {
        val sessionId = _uiState.value.sessionId ?: return
        val persona = lastKnownPersona ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSuggestingQuestions = true) }
            suggestScriptQuestions(sessionId, persona)
                .onSuccess { entries -> _uiState.update { it.copy(script = entries) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            _uiState.update { it.copy(isSuggestingQuestions = false) }
        }
    }

    /** The human interviewer's own typed-in question — added to the same shared script. */
    fun onAddCustomQuestion(text: String) {
        val sessionId = _uiState.value.sessionId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            addCustomScriptQuestion(sessionId, text.trim(), lastKnownPersona)
                .onSuccess { entries -> _uiState.update { it.copy(script = entries) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = error.message) } }
        }
    }

    fun onMarkScriptEntryUsed(entryId: String) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            markScriptEntryUsed(sessionId, entryId)
            _uiState.update { state ->
                state.copy(
                    script = state.script.map {
                        if (it.id == entryId) it.copy(used = true) else it
                    },
                )
            }
        }
    }

    /**
     * Starts on-device face-count/gaze proctoring against the front
     * camera for the duration of the call. Called from InterviewScreen
     * once CallState is Connected and camera permission is granted; safe
     * to call once per screen lifecycle — collection stops automatically
     * when [lifecycleOwner] stops (see FaceProctoringAnalyzer.observe).
     */
    fun startFaceProctoring(analyzer: FaceProctoringAnalyzer, lifecycleOwner: LifecycleOwner) {
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            analyzer.observe(lifecycleOwner).collect { signal ->
                reportCheatSignal(
                    sessionId = sessionId,
                    signalType = signal.type,
                    detail = signal.detail,
                    reportedStrength = signal.strength,
                ).onSuccess { newAlert ->
                    if (newAlert != null) {
                        _uiState.update { it.copy(cheatAlerts = it.cheatAlerts + newAlert) }
                    }
                }
            }
        }
    }

    /** Reports the candidate having switched away from the app mid-interview. */
    fun onAppBackgrounded() {
        val sessionId = _uiState.value.sessionId ?: return
        if (_uiState.value.callState != CallState.Connected) return
        viewModelScope.launch {
            reportCheatSignal(
                sessionId = sessionId,
                signalType = ClientCheatSignalType.APP_BACKGROUNDED,
                detail = "Candidate app was backgrounded during the interview",
            ).onSuccess { newAlert ->
                if (newAlert != null) {
                    _uiState.update { it.copy(cheatAlerts = it.cheatAlerts + newAlert) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { agoraCallRepository.leaveCall() }
    }
}