"""
Proctoring endpoints: where the Android app reports video/audio integrity
signals it detected on-device (face count from the front camera, gaze
direction, a second-voice heuristic on the mic stream, app-backgrounding,
screen-mirroring detection) and where either the interviewer or the
candidate's own app can poll the accumulated cheating status.

This backend never receives raw audio or video — only small, structured
signal reports (see CheatSignalType in models/schemas.py) — the same
"who owns what" boundary as the rest of the system: Agora owns real
audio, the Android app owns on-device video/audio analysis, this backend
only ever scores and accumulates structured text/signal reports (see
services/cheating_detector.py for why, and ARCHITECTURE.md section 2).
"""
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.models.schemas import CheatFlag, CheatSignal, CheatSignalType
from app.services.cheating_detector import record_signals, score_client_signal
from app.services.context_graph_store import ContextGraphStore, get_context_graph_store

router = APIRouter(prefix="/proctoring", tags=["proctoring"])


class ReportSignalRequest(BaseModel):
    signal_type: CheatSignalType
    detail: str = ""
    # The client's own confidence in this observation if it has one (e.g.
    # ML Kit's face-detection confidence, or a VAD/diarization score for a
    # second voice) — optional, scored server-side either way.
    reported_strength: float | None = None
    transcript_timestamp_ms: int = 0


class ReportSignalResponse(BaseModel):
    recorded: bool
    new_flag: CheatFlag | None = None


@router.post("/{session_id}/signal", response_model=ReportSignalResponse)
def report_signal(
    session_id: UUID,
    payload: ReportSignalRequest,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> ReportSignalResponse:
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    strength = score_client_signal(payload.signal_type, payload.reported_strength)
    signal = CheatSignal(
        signal_type=payload.signal_type,
        detail=payload.detail,
        strength=strength,
        transcript_timestamp_ms=payload.transcript_timestamp_ms,
        source="client",
    )
    new_flag = record_signals(session, [signal])

    if hasattr(store, "update_session"):
        store.update_session(session)

    return ReportSignalResponse(recorded=True, new_flag=new_flag)


class ProctoringStatusResponse(BaseModel):
    session_id: UUID
    total_signals: int
    flags: list[CheatFlag]
    highest_severity: str | None = None


@router.get("/{session_id}/status", response_model=ProctoringStatusResponse)
def get_status(
    session_id: UUID,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> ProctoringStatusResponse:
    """
    Polled by BOTH the interviewer-side and candidate-side UI (the
    interviewer to see the live integrity read on the panel; the
    candidate's own app to render the same disclosure the interviewer
    sees, in keeping with the project's transparency-by-design stance —
    no hidden monitoring the candidate can't also see). Returns every
    flag raised so far, each pointing back at its contributing_signals for
    full auditability, never a bare "cheating detected" with no evidence.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    severity_rank = {"low": 0, "medium": 1, "high": 2}
    highest = None
    if session.cheat_flags:
        highest = max(session.cheat_flags, key=lambda f: severity_rank[f.severity.value]).severity.value

    return ProctoringStatusResponse(
        session_id=session_id,
        total_signals=len(session.cheat_signals),
        flags=session.cheat_flags,
        highest_severity=highest,
    )


@router.post("/{session_id}/flags/{flag_id}/acknowledge")
def acknowledge_flag(
    session_id: UUID,
    flag_id: UUID,
    store: ContextGraphStore = Depends(get_context_graph_store),
) -> dict:
    """
    Lets the human interviewer mark a flag as reviewed (e.g. "I looked,
    it was a sibling walking past camera, not cheating") so the UI can
    visually de-emphasize it without deleting the evidence — CheatFlag
    keeps its full contributing_signals regardless of acknowledged.
    """
    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    for flag in session.cheat_flags:
        if flag.id == flag_id:
            flag.acknowledged = True
            if hasattr(store, "update_session"):
                store.update_session(session)
            return {"status": "acknowledged"}

    raise HTTPException(status_code=404, detail="No such flag on this session")