"""
Shared live "script panel" — both the interviewer and the interviewee poll
GET /script/{session_id} and see the exact same list. It holds two kinds
of entry (see ScriptEntry in models/schemas.py):

  - SUGGESTED — generated from the same Context Graph slice the personas
    already use (via personas.engine.generate_suggested_questions), so
    suggestions are grounded in what's actually been said, not generic.
  - CUSTOM — typed live by the human interviewer via POST .../custom.

This is deliberately visible to both sides: the project's whole ethos is
transparency (the AI-disclosure banner is shown for the entire interview,
not just once), so the "what might get asked next" plan is not a secret
weapon the candidate can't see — it's shared script, same as an open
interview panel would often work from a shared doc.
"""
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.models.schemas import PersonaRole, ScriptEntry, ScriptQuestionSource
from app.personas.engine import generate_suggested_questions
from app.services.context_graph_store import ContextGraphStore, get_context_graph_store

router = APIRouter(prefix="/script", tags=["script"])


class ScriptResponse(BaseModel):
    entries: list[ScriptEntry] = []


@router.get("/{session_id}", response_model=ScriptResponse)
def get_script(
    session_id: UUID,
    since_index: int = 0,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> ScriptResponse:
    """
    Polled by both interviewer and interviewee UIs. since_index slices
    into the list the same way GET /sessions/{id}/turns does, so a client
    can poll incrementally instead of re-fetching the whole script.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    entries = session.script[since_index:] if since_index else session.script
    return ScriptResponse(entries=entries)


class SuggestRequest(BaseModel):
    persona: PersonaRole
    topic_hint: str = ""
    count: int = 3


@router.post("/{session_id}/suggest", response_model=ScriptResponse)
async def suggest_questions(
    session_id: UUID,
    payload: SuggestRequest,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> ScriptResponse:
    """
    Asks one persona for fresh suggested questions grounded in the current
    Context Graph, appends them to the shared script, and returns the full
    updated script. The interviewer's UI calls this (e.g. on a "Suggest
    questions" button, or automatically between turns) — the candidate's
    UI only ever reads via GET, never triggers generation.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    questions = await generate_suggested_questions(
        role=payload.persona,
        graph=session.context_graph,
        topic_hint=payload.topic_hint or None,
        count=payload.count,
    )
    new_entries = [
        ScriptEntry(
            source=ScriptQuestionSource.SUGGESTED,
            text=q,
            persona=payload.persona,
            topic_hint=payload.topic_hint,
        )
        for q in questions
    ]
    session.script.extend(new_entries)

    if hasattr(store, "update_session"):
        store.update_session(session)

    return ScriptResponse(entries=session.script)


class AddCustomQuestionRequest(BaseModel):
    text: str
    persona: PersonaRole | None = None
    topic_hint: str = ""


@router.post("/{session_id}/custom", response_model=ScriptResponse)
def add_custom_question(
    session_id: UUID,
    payload: AddCustomQuestionRequest,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> ScriptResponse:
    """
    The human interviewer's own typed-in question, added to the SAME
    shared list as the AI-suggested ones — appears instantly to the
    candidate's script view too, since both sides poll the same endpoint.
    """
    text = payload.text.strip()
    if not text:
        raise HTTPException(status_code=422, detail="Question text cannot be empty")

    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    entry = ScriptEntry(
        source=ScriptQuestionSource.CUSTOM,
        text=text,
        persona=payload.persona,
        topic_hint=payload.topic_hint,
    )
    session.script.append(entry)

    if hasattr(store, "update_session"):
        store.update_session(session)

    return ScriptResponse(entries=session.script)


@router.post("/{session_id}/entries/{entry_id}/mark_used")
def mark_used(
    session_id: UUID,
    entry_id: UUID,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> dict:
    """
    Marks a script entry as asked, so the UI can grey it out for both
    sides — AND pins its text as session.pending_question_text, so the
    NEXT turn's generate_followup() call asks exactly this question
    instead of freelancing something unrelated. This is what makes the
    script panel an actual input to the interview rather than a
    decorative suggestion list: the interviewer picking a question here
    is a real commitment the backend honors on the next turn.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    for entry in session.script:
        if entry.id == entry_id:
            entry.used = True
            session.pending_question_text = entry.text
            if hasattr(store, "update_session"):
                store.update_session(session)
            return {"status": "marked_used", "pinned_for_next_turn": True}

    raise HTTPException(status_code=404, detail="No such script entry on this session")