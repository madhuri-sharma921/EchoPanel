package com.echopanel.app.domain.repository

import com.echopanel.app.domain.model.AgentActivityState
import com.echopanel.app.domain.model.AgoraToken
import com.echopanel.app.domain.model.CheatAlert
import com.echopanel.app.domain.model.ClientCheatSignalType
import com.echopanel.app.domain.model.FinalReport
import com.echopanel.app.domain.model.InterviewSession
import com.echopanel.app.domain.model.LoggedTurn
import com.echopanel.app.domain.model.PersonaRole
import com.echopanel.app.domain.model.ScenarioCard
import com.echopanel.app.domain.model.ScriptEntry
import com.echopanel.app.domain.model.TranscriptTurn
import com.echopanel.app.domain.model.TurnResult
import kotlinx.coroutines.flow.Flow

interface InterviewRepository {

    suspend fun createSession(
        candidateName: String,
        activePersonas: List<PersonaRole>,
    ): Result<InterviewSession>

    suspend fun logConsent(sessionId: String): Result<Unit>

    suspend fun submitTurn(
        sessionId: String,
        candidateText: String,
        topicHint: String,
        transcriptTimestampMs: Long,
        respondingTo: PersonaRole,
        secondsSinceQuestion: Float? = null,
    ): Result<TurnResult>

    suspend fun fetchFinalReport(sessionId: String): Result<FinalReport>

    /** Fetches a signed Agora RTC token scoped to this session's voice channel. */
    suspend fun fetchAgoraToken(sessionId: String): Result<AgoraToken>


    suspend fun startAgent(sessionId: String): Result<Unit>


    suspend fun fetchLatestScenario(sessionId: String): Result<ScenarioCard?>

    /** Fetches turns logged after [sinceIndex] (exclusive) for the live transcript. */
    suspend fun fetchTurns(sessionId: String, sinceIndex: Int): Result<List<LoggedTurn>>

    // --- Proctoring / cheating detection --------------------------------

    /** Reports one on-device video/audio integrity signal; may return a newly raised alert. */
    suspend fun reportCheatSignal(
        sessionId: String,
        signalType: ClientCheatSignalType,
        detail: String = "",
        reportedStrength: Float? = null,
        transcriptTimestampMs: Long = 0,
    ): Result<CheatAlert?>

    /** Polls the session's cumulative integrity status — same data for interviewer and candidate. */
    suspend fun fetchProctoringStatus(sessionId: String): Result<List<CheatAlert>>

    suspend fun acknowledgeCheatFlag(sessionId: String, flagId: String): Result<Unit>

    // --- Shared live script panel ---------------------------------------

    /** Fetches script entries after [sinceIndex] — polled by BOTH interviewer and candidate UIs. */
    suspend fun fetchScript(sessionId: String, sinceIndex: Int = 0): Result<List<ScriptEntry>>

    /** Asks one persona for fresh suggested questions grounded in the Context Graph. */
    suspend fun suggestScriptQuestions(
        sessionId: String,
        persona: PersonaRole,
        topicHint: String = "",
        count: Int = 3,
    ): Result<List<ScriptEntry>>

    /** The human interviewer's own typed question, added to the same shared script. */
    suspend fun addCustomScriptQuestion(
        sessionId: String,
        text: String,
        persona: PersonaRole? = null,
        topicHint: String = "",
    ): Result<List<ScriptEntry>>

    suspend fun markScriptEntryUsed(sessionId: String, entryId: String): Result<Unit>
}


interface AgoraCallRepository {

    suspend fun joinCall(
        sessionId: String,
        agoraToken: String,
        channelName: String,
        rtmToken: String,
        rtmUserAccount: String,
    ): Result<Unit>

    suspend fun leaveCall()


    fun observeTranscript(): Flow<TranscriptTurn>


    fun observeAgentState(): Flow<AgentActivityState>


    suspend fun interruptAgent(): Result<Unit>

    /**
     * Creates a SurfaceView bound to the candidate's OWN camera feed (the
     * real interviewee video) via Agora's local video renderer. Returns
     * null if the engine isn't joined yet or video couldn't be enabled
     * (e.g. no camera permission) — callers should treat null as "no
     * video tile to show" rather than an error.
     */
    fun createLocalVideoView(context: android.content.Context): android.view.SurfaceView?

    /**
     * Re-confirms the local video binding on an existing SurfaceView.
     * Call this from an AndroidView's `update` block (not just `factory`)
     * — Agora's renderer can silently stop drawing to a SurfaceView that
     * was detached and reattached (e.g. during recomposition), and this
     * is the fix for that "video looks frozen" failure mode. Safe to call
     * repeatedly; a no-op if the engine isn't up.
     */
    fun rebindLocalVideo(surfaceView: android.view.SurfaceView)

    /**
     * Emits a new value every time the local camera capture pipeline is
     * restarted after detecting a mid-call failure (see
     * AgoraCallRepositoryImpl's onLocalVideoStateChanged /
     * ERR_INVALID_STATE handling). UI observing this should fetch a
     * fresh SurfaceView when it changes — a camera that died and was
     * restarted needs a fresh setupLocalVideo() call, not just the same
     * stale surface rebound to a still-broken capturer.
     */
    fun observeLocalVideoGeneration(): kotlinx.coroutines.flow.StateFlow<Int>

    /**
     * Candidate-controlled camera on/off — separate from permission or
     * hardware availability. When turned off, the local camera stops
     * capturing and publishing (muteLocalVideoStream + stopPreview), and
     * the candidate's own tile should fall back to the initials
     * placeholder, same as if the camera were unavailable. Turning it
     * back on re-enables capture and publishing without needing to
     * rejoin the call. Safe to call before joinCall() has completed —
     * a no-op if the engine isn't up yet, the same way the other video
     * methods degrade.
     */
    fun setLocalVideoEnabled(enabled: Boolean)

    /**
     * Whether the candidate has turned their own camera off via
     * [setLocalVideoEnabled] — distinct from whether video is available
     * at all (permission/hardware). Defaults to true (camera on) unless
     * the candidate has explicitly turned it off.
     */
    fun observeLocalVideoEnabled(): kotlinx.coroutines.flow.StateFlow<Boolean>
}