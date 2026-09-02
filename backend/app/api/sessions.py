from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from app.models.schemas import FinalReport, InterviewSession, PersonaRole
from app.services.agora_agent_service import start_agent, stop_agent
from app.services.context_graph_store import ContextGraphStore, get_context_graph_store
from app.services.report_generator import generate_final_report

router = APIRouter(prefix="/sessions", tags=["sessions"])


class CreateSessionRequest(InterviewSession):
    # Reuse InterviewSession's shape for the request body, but personas are
    # required client-side input rather than server-generated state.
    pass


@router.post("", response_model=InterviewSession)
def create_session(
    candidate_name: str = Query(...),
    active_personas: list[PersonaRole] = Query(...),
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> InterviewSession:
    session = InterviewSession(
        candidate_name=candidate_name, active_personas=active_personas
    )
    return store.create_session(session)


@router.get("/{session_id}", response_model=InterviewSession)
def get_session(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> InterviewSession:
    try:
        return store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


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
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    session.consent_logged_at = datetime.utcnow()
    return session


class StartAgentResponse(BaseModel):
    agent_id: str
    status: str


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
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    greeting = (
        "Thanks for joining. Let's get started — tell me a bit about a "
        "recent project you've worked on."
    )
    try:
        result = start_agent(session_id=str(session_id), persona_greeting=greeting)
    except ValueError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except Exception as exc:  # Agora API/network errors
        raise HTTPException(
            status_code=502, detail=f"Failed to start Agora agent: {exc}"
        ) from exc

    session.agora_agent_id = result["agent_id"]
    return StartAgentResponse(agent_id=result["agent_id"], status=result["status"])


@router.post("/{session_id}/agent/stop")
def stop_session_agent(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> dict:
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    if not session.agora_agent_id:
        return {"status": "no_agent_running"}

    try:
        stop_agent(session.agora_agent_id)
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"Failed to stop Agora agent: {exc}"
        ) from exc

    session.agora_agent_id = None
    return {"status": "stopped"}


@router.get("/{session_id}/report", response_model=FinalReport)
def get_final_report(
    session_id: UUID, store: ContextGraphStore = Depends(get_context_graph_store)
) -> FinalReport:
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    return generate_final_report(session)
