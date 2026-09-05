"""
Endpoints that Agora's Conversational AI Engine invokes via its tool-calling
hooks. Agora owns ASR, turn-detection, interruption handling, and TTS
playback; our job is purely the reasoning layer it calls out to:

  1. Agora transcribes the candidate's speech and calls `/agora/turn` with
     the recognized text.
  2. We extract a claim, update the Context Graph, run contradiction /
     vagueness checks, update the difficulty controller, run the Turn
     Arbiter to pick the next persona, and generate that persona's
     follow-up via the persona engine.
  3. We return the text for Agora to speak via the chosen persona's TTS
     voice (Agora supports per-persona provider/voice selection).
"""
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException

from app.models.schemas import CheatSeverity, PersonaRole, ScenarioCard, TranscriptEntry
from app.personas.engine import extract_claim, generate_followup
from app.services.agora_token_service import generate_rtc_token
from app.services.cheating_detector import detect_text_signals, record_signals
from app.services.context_graph_store import ContextGraphStore, get_context_graph_store
from app.services.contradiction_detector import process_new_claim
from app.services.difficulty_controller import update_competence
from app.services.turn_arbiter import compute_interest_scores, pick_next_persona
from pydantic import BaseModel

router = APIRouter(prefix="/agora", tags=["agora"])


class AgoraTokenResponse(BaseModel):
    token: str
    app_id: str
    channel_name: str
    uid: int
    expires_at: int
    rtm_token: str
    rtm_user_account: str


@router.get("/token/{session_id}", response_model=AgoraTokenResponse)
def get_agora_token(
    session_id: UUID,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> AgoraTokenResponse:
    """
    Issues a signed Agora RTC token scoped to this session's voice channel.
    The Android client calls this after session creation/consent, then uses
    the returned token + channel_name to join the Agora RTC channel — the
    App Certificate that signs the token never leaves the backend.
    """
    try:
        store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    try:
        # The channel name matches the session ID, which is exactly what
        # the Android client already passes as channelName when joining.
        token_data = generate_rtc_token(channel_name=str(session_id))
    except ValueError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return AgoraTokenResponse(**token_data)


class AgoraTurnRequest(BaseModel):
    session_id: UUID
    candidate_text: str
    topic_hint: str
    transcript_timestamp_ms: int
    # Which persona's question the candidate was answering.
    responding_to: PersonaRole
    # Optional: seconds between the question being spoken and this answer
    # arriving, if the caller can measure it. Feeds the "answer arrived
    # implausibly fast for its length" cheating signal — safe to omit.
    seconds_since_question: float | None = None


class AgoraTurnResponse(BaseModel):
    next_persona: PersonaRole
    spoken_text: str
    is_vague: bool
    contradiction_detected: bool
    # New cheating flag raised by THIS turn's text analysis, if the
    # accumulated signal strength crossed a new severity threshold — null
    # on most turns. Historical/lower-severity flags are available via
    # GET /proctoring/{session_id}/status, not repeated here every turn.
    new_cheat_flag_severity: Optional[CheatSeverity] = None
    new_cheat_flag_summary: Optional[str] = None


@router.post("/turn", response_model=AgoraTurnResponse)
async def handle_turn(
    payload: AgoraTurnRequest,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> AgoraTurnResponse:
    try:
        session = store.get_or_404(payload.session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    graph = session.context_graph

    # 1. Extract structured claim from the candidate's raw speech.
    claim = await extract_claim(
        candidate_answer=payload.candidate_text,
        topic_hint=payload.topic_hint,
        transcript_timestamp_ms=payload.transcript_timestamp_ms,
        role=payload.responding_to,
    )

    # 2. Run contradiction / vagueness checks against the graph BEFORE scoring.
    claim = process_new_claim(claim, graph)
    graph.add_node(claim)

    # 2b. Text-derived cheating signals — always runs, no client cooperation
    # needed. Client-reported video/audio signals arrive separately via
    # POST /proctoring/{session_id}/signal and accumulate into the same
    # session.cheat_signals log (see api/proctoring.py).
    prior_answers = [t.candidate_answer for t in session.turn_log]
    text_signals = detect_text_signals(
        answer_text=payload.candidate_text,
        prior_answers=prior_answers,
        seconds_since_question=payload.seconds_since_question,
        transcript_timestamp_ms=payload.transcript_timestamp_ms,
    )
    new_flag = record_signals(session, text_signals)

    # 3. Update rolling per-topic competence -> next question depth.
    competence = update_competence(
        session, topic=claim.topic, observed_score=claim.confidence
    )

    # 4. Turn Arbiter: who claims the floor next.
    scores = compute_interest_scores(
        active_personas=session.active_personas,
        latest_topic=claim.topic,
        graph=graph,
    )
    winner = pick_next_persona(scores)

    # 5. Generate that persona's grounded follow-up via the LLM.
    spoken_text, scenario = await generate_followup(
        role=winner.persona,
        graph=graph,
        topic_hint=claim.topic,
        question_depth=competence.next_depth.value,
        candidate_answer=payload.candidate_text,
    )
    if scenario is not None:
        session.latest_scenario = ScenarioCard(**scenario)

    if hasattr(store, "update_session"):
        store.update_session(session)

    return AgoraTurnResponse(
        next_persona=winner.persona,
        spoken_text=spoken_text,
        is_vague=claim.is_vague,
        contradiction_detected=bool(claim.contradicts),
        new_cheat_flag_severity=new_flag.severity if new_flag else None,
        new_cheat_flag_summary=new_flag.summary if new_flag else None,
    )


@router.post("/greeting")
async def automated_greeting(session_id: UUID) -> dict:
    """
    Wired to Agora's automated-greeting hook: spoken AI-disclosure statement
    played before the interview starts, with consent logged separately via
    POST /sessions/{id}/consent once the candidate acknowledges.
    """
    return {
        "spoken_text": (
            "Before we begin: this interview is conducted by an AI panel, "
            "not a human. Your responses will be recorded and evaluated by "
            "multiple AI interviewer personas. Do you consent to proceed?"
        )
    }