package com.echopanel.app.presentation.interview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.CallState
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.repository.AgoraCallRepository
import com.echopanel.app.domain.usecase.GetAgoraTokenUseCase
import com.echopanel.app.domain.usecase.LogConsentUseCase
import com.echopanel.app.domain.usecase.StartAgentUseCase
import com.echopanel.app.domain.usecase.StartInterviewSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

@HiltViewModel
class InterviewViewModel @Inject constructor(
    private val startInterviewSession: StartInterviewSessionUseCase,
    private val logConsent: LogConsentUseCase,
    private val getAgoraToken: GetAgoraTokenUseCase,
    private val startAgentUseCase: StartAgentUseCase,
    private val agoraCallRepository: AgoraCallRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InterviewUiState())
    val uiState: StateFlow<InterviewUiState> = _uiState.asStateFlow()

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

    /** Lets the candidate interrupt the AI mid-speech — the "interruptible" requirement. */
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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { agoraCallRepository.leaveCall() }
    }
}
