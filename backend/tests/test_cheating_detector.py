from uuid import uuid4

from app.models.schemas import (
    CheatSeverity,
    CheatSignal,
    CheatSignalType,
    InterviewSession,
    PersonaRole,
)
from app.services.cheating_detector import (
    detect_text_signals,
    record_signals,
    score_client_signal,
)


def _session() -> InterviewSession:
    return InterviewSession(
        candidate_name="Test Candidate",
        active_personas=[PersonaRole.TECHNICAL],
    )


def test_copy_paste_style_flagged():
    signals = detect_text_signals(
        answer_text=(
            "Step 1: identify the bottleneck. Step 2: profile the service. "
            "Firstly, according to the docs [1] this is the standard approach."
        ),
        prior_answers=[],
        seconds_since_question=None,
        transcript_timestamp_ms=1000,
    )
    types = {s.signal_type for s in signals}
    assert CheatSignalType.COPY_PASTE_STYLE in types


def test_fluency_jump_flagged_after_short_answers():
    prior = ["Um, not sure.", "I think maybe it's fine, sort of."]
    long_polished_answer = (
        "The service uses a stateless architecture with horizontal scaling "
        "behind a load balancer, backed by a distributed cache layer that "
        "keeps read latency low even under bursty traffic patterns across "
        "regions, which is why we chose eventual consistency for that path."
    )
    signals = detect_text_signals(
        answer_text=long_polished_answer,
        prior_answers=prior,
        seconds_since_question=None,
        transcript_timestamp_ms=2000,
    )
    types = {s.signal_type for s in signals}
    assert CheatSignalType.FLUENCY_JUMP in types


def test_no_fluency_jump_when_hedge_words_present():
    prior = ["Um, not sure.", "I think maybe it's fine, sort of."]
    long_but_hedged_answer = (
        "Um, I think the service is, sort of, stateless? Maybe. I guess it "
        "scales because, um, there's a load balancer, kind of, in front."
    )
    signals = detect_text_signals(
        answer_text=long_but_hedged_answer,
        prior_answers=prior,
        seconds_since_question=None,
        transcript_timestamp_ms=2000,
    )
    types = {s.signal_type for s in signals}
    assert CheatSignalType.FLUENCY_JUMP not in types


def test_answer_too_fast_flagged():
    long_answer = "x" * 400
    signals = detect_text_signals(
        answer_text=long_answer,
        prior_answers=[],
        seconds_since_question=1.0,
        transcript_timestamp_ms=3000,
    )
    types = {s.signal_type for s in signals}
    assert CheatSignalType.ANSWER_TOO_FAST in types


def test_short_quick_answer_not_flagged_as_too_fast():
    signals = detect_text_signals(
        answer_text="Yes.",
        prior_answers=[],
        seconds_since_question=0.2,
        transcript_timestamp_ms=3000,
    )
    types = {s.signal_type for s in signals}
    assert CheatSignalType.ANSWER_TOO_FAST not in types


def test_client_signal_scoring_uses_reported_confidence():
    low_conf = score_client_signal(CheatSignalType.MULTIPLE_FACES, 0.1)
    high_conf = score_client_signal(CheatSignalType.MULTIPLE_FACES, 0.9)
    assert high_conf > low_conf


def test_record_signals_raises_flag_once_threshold_crossed():
    session = _session()
    strong_signal = CheatSignal(
        signal_type=CheatSignalType.EXTRA_VOICE_DETECTED,
        strength=0.6,
        transcript_timestamp_ms=1000,
        source="client",
    )
    another = CheatSignal(
        signal_type=CheatSignalType.MULTIPLE_FACES,
        strength=0.5,
        transcript_timestamp_ms=2000,
        source="client",
    )
    flag = record_signals(session, [strong_signal])
    assert flag is not None
    assert flag.severity == CheatSeverity.LOW

    # Crossing into MEDIUM should raise a second, higher-severity flag.
    flag2 = record_signals(session, [another])
    assert flag2 is not None
    assert flag2.severity == CheatSeverity.MEDIUM
    assert len(session.cheat_flags) == 2


def test_record_signals_does_not_reflag_same_band_twice():
    session = _session()
    signal = CheatSignal(
        signal_type=CheatSignalType.GAZE_OFF_SCREEN,
        strength=0.45,
        transcript_timestamp_ms=1000,
        source="client",
    )
    first = record_signals(session, [signal])
    assert first is not None

    # Another tiny signal that doesn't cross into a new band -> no new flag.
    tiny = CheatSignal(
        signal_type=CheatSignalType.GAZE_OFF_SCREEN,
        strength=0.01,
        transcript_timestamp_ms=1500,
        source="client",
    )
    second = record_signals(session, [tiny])
    assert second is None
    assert len(session.cheat_flags) == 1


def test_flag_traces_back_to_contributing_signals():
    session = _session()
    signal = CheatSignal(
        signal_type=CheatSignalType.SCREEN_SHARE_OR_MIRROR,
        strength=0.5,
        transcript_timestamp_ms=1000,
        source="client",
    )
    flag = record_signals(session, [signal])
    assert flag is not None
    assert signal.id in flag.contributing_signals