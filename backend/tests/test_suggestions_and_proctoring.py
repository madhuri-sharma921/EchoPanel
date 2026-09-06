import inspect
from uuid import uuid4

from app.models.schemas import (
    CheatFlag,
    CheatSeverity,
    CheatSignal,
    CheatSignalType,
    ContextGraph,
    InterviewSession,
    PersonaRole,
    ScriptEntry,
    ScriptQuestionSource,
)
from app.personas.engine import generate_followup
from app.services.cheating_detector import record_signals, score_client_signal


def test_generate_followup_accepts_suggested_questions_param():
    """Verify generate_followup signature supports suggested_questions."""
    sig = inspect.signature(generate_followup)
    assert "suggested_questions" in sig.parameters
    assert sig.parameters["suggested_questions"].default is None


def test_proctoring_client_signal_scoring():
    """Verify client signal scoring for video/audio proctoring."""
    score_no_face = score_client_signal(CheatSignalType.NO_FACE_DETECTED, 0.6)
    assert 0.05 <= score_no_face <= 1.0

    score_gaze = score_client_signal(CheatSignalType.GAZE_OFF_SCREEN, 0.4)
    assert 0.05 <= score_gaze <= 1.0

    score_multi = score_client_signal(CheatSignalType.MULTIPLE_FACES, 0.85)
    assert score_multi > score_no_face


def test_record_signals_updates_existing_severity_flag_evidence():
    """Verify record_signals updates flag summary/evidence when remaining in same band."""
    session = InterviewSession(
        candidate_name="Test Candidate",
        active_personas=[PersonaRole.TECHNICAL],
    )
    sig1 = CheatSignal(
        signal_type=CheatSignalType.NO_FACE_DETECTED,
        detail="No face visible in frame for ~1.5s",
        strength=0.45,
        transcript_timestamp_ms=1000,
        source="client",
    )
    flag = record_signals(session, [sig1])
    assert flag is not None
    assert flag.severity == CheatSeverity.LOW
    assert len(flag.contributing_signals) == 1

    # Second signal arrives at timestamp 2000, total = 0.6 (still in LOW band < 0.8)
    sig2 = CheatSignal(
        signal_type=CheatSignalType.GAZE_OFF_SCREEN,
        detail="Sustained head turn away from camera",
        strength=0.15,
        transcript_timestamp_ms=2000,
        source="client",
    )
    flag2 = record_signals(session, [sig2])
    # Remains in LOW band, but existing flag was updated with latest evidence
    assert flag2 is None
    assert len(session.cheat_flags) == 1
    updated_flag = session.cheat_flags[0]
    assert len(updated_flag.contributing_signals) == 2
    assert updated_flag.transcript_timestamp_ms == 2000
    assert "candidate not visible" in updated_flag.summary
    assert "looking away from screen" in updated_flag.summary

    # Third signal pushes total to >= 0.8 (MEDIUM band)
    sig3 = CheatSignal(
        signal_type=CheatSignalType.MULTIPLE_FACES,
        detail="2 faces in frame",
        strength=0.3,
        transcript_timestamp_ms=3000,
        source="client",
    )
    flag3 = record_signals(session, [sig3])
    assert flag3 is not None
    assert flag3.severity == CheatSeverity.MEDIUM
    assert len(session.cheat_flags) == 2
