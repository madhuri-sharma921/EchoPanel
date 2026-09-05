import logging
from datetime import datetime, timezone
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from app.models.schemas import FinalReport, InterviewSession, PersonaRole, ScenarioCard, TurnLogEntry
from app.services.agora_agent_service import start_agent, stop_agent
from app.services.context_graph_store import ContextGraphStore, get_context_graph_store
from app.services.report_generator import generate_final_report

logger = logging.getLogger("echopanel.sessions")

router = APIRouter(prefix="/sessions", tags=["sessions"])


class StartAgentResponse(BaseModel):
    agent_id: str
    status: str


class TurnsResponse(BaseModel):
    turns: list[TurnLogEntry] = []


class ScenarioResponse(BaseModel):
    # session.latest_scenario is stored as a ScenarioCard model (see
    # app/models/schemas.py), not a plain dict — this was previously typed
    # Optional[dict], which made Pydantic reject the response with a
    # dict_type validation error (500) as soon as any scenario was set.
    scenario: Optional[ScenarioCard] = None


@router.post("", response_model=InterviewSession, status_code=status.HTTP_201_CREATED)
def create_session(
    candidate_name: str = Query(...),
    active_personas: list[PersonaRole] = Query(...),
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> InterviewSession:
    session = InterviewSession(
        candidate_name=candidate_name,
        active_personas=active_personas,
    )
    return store.create_session(session)


@router.get("/{session_id}", response_model=InterviewSession)
def get_session(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> InterviewSession:
    try:
        return store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.post("/{session_id}/consent", response_model=InterviewSession)
def log_consent(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> InterviewSession:
    """
    Called once the candidate has heard the AI-disclosure banner / spoken
    opening statement and consent is confirmed client-side.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    # Immutable-safe update pattern
    if hasattr(session, "model_copy"):
        session = session.model_copy(update={"consent_logged_at": datetime.now(timezone.utc)})
    elif hasattr(session, "copy"):
        session = session.copy(update={"consent_logged_at": datetime.now(timezone.utc)})
    else:
        session.consent_logged_at = datetime.now(timezone.utc)

    if hasattr(store, "update_session"):
        store.update_session(session)

    return session


@router.post("/{session_id}/agent/start", response_model=StartAgentResponse)
def start_session_agent(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> StartAgentResponse:
    """
    Starts the Agora Conversational AI agent for this session's channel.
    Call this AFTER the Android client has already joined the RTC channel
    with its own token, so the agent doesn't speak into an empty room.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    greeting = (
        "Thanks for joining. Let's get started — tell me a bit about a "
        "recent project you've worked on."
    )
    try:
        result = start_agent(session_id=str(session_id), persona_greeting=greeting)
    except ValueError as exc:
        logger.exception("start_agent misconfiguration for session %s", session_id)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)) from exc
    except Exception as exc:  # Agora API/network errors
        logger.exception("start_agent failed for session %s", session_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Failed to start Agora agent: {exc}",
        ) from exc

    agent_id = result.get("agent_id") if isinstance(result, dict) else getattr(result, "agent_id", None)
    agent_status = result.get("status", "started") if isinstance(result, dict) else getattr(result, "status", "started")

    if not agent_id:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Agent service returned an invalid response missing 'agent_id'.",
        )

    # Line 86 fix: safely update whether the model is frozen, typed, or standard
    # Also seed pending_question_text with the opening greeting so the
    # candidate's first answer gets logged against the question they
    # actually heard, instead of the llm_bridge's generic fallback.
    if hasattr(session, "model_copy"):
        session = session.model_copy(
            update={"agora_agent_id": str(agent_id), "pending_question_text": greeting}
        )
    elif hasattr(session, "copy"):
        session = session.copy(
            update={"agora_agent_id": str(agent_id), "pending_question_text": greeting}
        )
    else:
        session.agora_agent_id = str(agent_id)
        session.pending_question_text = greeting

    if hasattr(store, "update_session"):
        store.update_session(session)

    return StartAgentResponse(agent_id=str(agent_id), status=str(agent_status))


@router.post("/{session_id}/agent/stop")
def stop_session_agent(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> dict:
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    if not getattr(session, "agora_agent_id", None):
        return {"status": "no_agent_running"}

    try:
        stop_agent(session.agora_agent_id)
    except Exception as exc:
        logger.exception("stop_agent failed for session %s", session_id)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Failed to stop Agora agent: {exc}",
        ) from exc

    if hasattr(session, "model_copy"):
        session = session.model_copy(update={"agora_agent_id": None})
    elif hasattr(session, "copy"):
        session = session.copy(update={"agora_agent_id": None})
    else:
        session.agora_agent_id = None

    if hasattr(store, "update_session"):
        store.update_session(session)

    return {"status": "stopped"}


@router.get("/{session_id}/report", response_model=FinalReport)
def get_final_report(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> FinalReport:
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    return generate_final_report(session)


@router.get("/{session_id}/turns", response_model=TurnsResponse)
def get_turns(
    session_id: UUID,
    since_index: int = Query(0, ge=0),
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> TurnsResponse:
    """
    Returns turns logged after `since_index` (exclusive) — each one the
    literal question a persona asked plus the candidate's literal answer,
    with the vagueness/contradiction signals detected on that answer.
    The Android app polls this to build the live on-screen transcript
    (with a reaction emoji per persona turn) alongside the voice call,
    which Agora's engine otherwise handles entirely off-app.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    turns = [t for t in session.turn_log if t.index >= since_index]
    return TurnsResponse(turns=turns)


@router.get("/{session_id}/scenario", response_model=ScenarioResponse)
def get_latest_scenario(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> ScenarioResponse:
    """
    Returns the most recent scenario card a persona set up (if any) — the
    Android app polls this after each transcript update so it can show a
    visual scenario alongside a role-play/scenario-based question. Returns
    {"scenario": null} when the latest question was a plain follow-up.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc

    latest_scenario = getattr(session, "latest_scenario", None)
    return ScenarioResponse(scenario=latest_scenario)