"""
Cheating / integrity detection.

Mirrors the shape of contradiction_detector.py on purpose: small, pure,
independently-testable functions that take a claim/session and return
flags, run at a well-defined point in the turn pipeline rather than
bolted on as an afterthought.

Two kinds of signal feed this:

1. TEXT signals — computed here, server-side, from the candidate's answer
   text and timing, on every /agora/turn call. No client cooperation
   needed; this always runs.
     - fluency_jump: the candidate's answers have been short/halting and
       this one is suddenly long, polished, and "written" in register.
     - copy_paste_style: the answer contains formatting/markers that read
       like copied reference material rather than spoken speech (bullet
       markers, "Step 1:", code fences, citation-style brackets).
     - answer_too_fast: a long, detailed answer arrived less time after
       the question than a human could plausibly have spoken it in —
       i.e. it looks read-out or pasted rather than composed live.

2. CLIENT (video/audio) signals — reported by the Android app via
   POST /proctoring/{session_id}/signal from on-device detectors (face
   count from the front camera, gaze direction, a second-voice/whisper
   heuristic on the mic stream, app-backgrounding, screen-mirroring
   detection). This module doesn't do any of that detection itself — it
   only scores and accumulates whatever signals arrive, the same way
   find_contradictions() doesn't do NLI itself, just scores a claim
   against the graph. See api/proctoring.py for the ingestion endpoint
   and ARCHITECTURE.md for why video/audio analysis belongs on-device /
   client-side, not in this backend (this backend never sees raw audio
   or video frames, only text and structured signal reports — same
   "who owns what" boundary as the rest of the system).

Signals accumulate on the session (session.cheat_signals) and are never
deleted — a CheatFlag is raised (and re-evaluated) once the accumulated
strength crosses a threshold, and every flag points back at the exact
signals that caused it (contributing_signals), so nobody — interviewer or
candidate — ever sees a bare, unexplained accusation.
"""
from __future__ import annotations

import re
from statistics import mean

from app.core.config import get_settings
from app.models.schemas import (
    CheatFlag,
    CheatSeverity,
    CheatSignal,
    CheatSignalType,
    InterviewSession,
)

# --- text heuristics --------------------------------------------------

_COPY_PASTE_MARKERS = (
    "step 1", "step 2", "in conclusion", "firstly,", "secondly,",
    "```", "* ", "- ", "1.", "2.", "according to", "[1]", "[2]",
    "furthermore,", "in summary,",
)

_FLUENCY_JUMP_MIN_PRIOR_ANSWERS = 2
_FLUENCY_JUMP_LENGTH_MULTIPLIER = 2.5


def _looks_copy_pasted(answer_text: str) -> bool:
    lowered = answer_text.lower().strip()
    if not lowered:
        return False
    hits = sum(1 for marker in _COPY_PASTE_MARKERS if marker in lowered)
    # Two or more structural/citation markers in one spoken answer is the
    # signal — a single "firstly," is normal speech, a wall of numbered
    # steps and citation brackets together is not how people talk.
    return hits >= 2


def _looks_like_fluency_jump(answer_text: str, prior_answers: list[str]) -> bool:
    if len(prior_answers) < _FLUENCY_JUMP_MIN_PRIOR_ANSWERS:
        return False
    prior_avg_len = mean(len(a) for a in prior_answers) or 1
    if len(answer_text) < prior_avg_len * _FLUENCY_JUMP_LENGTH_MULTIPLIER:
        return False
    # Very low filler/hedge-word rate combined with a sudden length spike —
    # spoken, unrehearsed answers almost always contain at least one of
    # these; a long, hedge-free answer right after several short ones is
    # the actual signal, not length alone.
    hedge_words = ("um", "uh", "i think", "maybe", "kind of", "sort of", "i guess")
    lowered = answer_text.lower()
    has_hedge = any(h in lowered for h in hedge_words)
    return not has_hedge


def _answer_too_fast(answer_text: str, seconds_since_question: float | None) -> bool:
    if seconds_since_question is None:
        return False
    settings = get_settings()
    min_seconds = (len(answer_text) / 100.0) * settings.cheat_min_seconds_per_100_chars
    # Only meaningful for substantive answers — a quick "yes" arriving
    # quickly is normal, not suspicious.
    return len(answer_text) > 120 and seconds_since_question < min_seconds


def detect_text_signals(
    *,
    answer_text: str,
    prior_answers: list[str],
    seconds_since_question: float | None,
    transcript_timestamp_ms: int,
) -> list[CheatSignal]:
    """Pure function: given answer text + context, return new CheatSignals."""
    signals: list[CheatSignal] = []

    if _looks_copy_pasted(answer_text):
        signals.append(
            CheatSignal(
                signal_type=CheatSignalType.COPY_PASTE_STYLE,
                detail="Answer contains structural/citation markers atypical of spoken speech.",
                strength=0.35,
                transcript_timestamp_ms=transcript_timestamp_ms,
                source="server",
            )
        )

    if _looks_like_fluency_jump(answer_text, prior_answers):
        signals.append(
            CheatSignal(
                signal_type=CheatSignalType.FLUENCY_JUMP,
                detail="Sudden jump from short/halting answers to a long, hedge-free, polished one.",
                strength=0.3,
                transcript_timestamp_ms=transcript_timestamp_ms,
                source="server",
            )
        )

    if _answer_too_fast(answer_text, seconds_since_question):
        signals.append(
            CheatSignal(
                signal_type=CheatSignalType.ANSWER_TOO_FAST,
                detail=(
                    f"{len(answer_text)}-character answer arrived "
                    f"{seconds_since_question:.1f}s after the question — "
                    "faster than plausible unspoken composition."
                ),
                strength=0.3,
                transcript_timestamp_ms=transcript_timestamp_ms,
                source="server",
            )
        )

    return signals


# --- client (video/audio) signal scoring -------------------------------

# Base strength assigned to each client-reported signal type. The client
# only reports *that* something was observed (e.g. "2 faces in frame for
# 4200ms") — this backend still owns turning that observation into a
# score, same separation of duties as the rest of the system.
_CLIENT_SIGNAL_BASE_STRENGTH: dict[CheatSignalType, float] = {
    CheatSignalType.MULTIPLE_FACES: 0.5,
    CheatSignalType.NO_FACE_DETECTED: 0.15,
    CheatSignalType.GAZE_OFF_SCREEN: 0.15,
    CheatSignalType.EXTRA_VOICE_DETECTED: 0.55,
    CheatSignalType.BACKGROUND_WHISPERING: 0.4,
    CheatSignalType.APP_BACKGROUNDED: 0.35,
    CheatSignalType.SCREEN_SHARE_OR_MIRROR: 0.45,
}


def score_client_signal(
    signal_type: CheatSignalType, reported_strength: float | None
) -> float:
    """
    Combines the client's own confidence (if it sent one, e.g. ML Kit's
    face-detection confidence) with our base weight per signal type, so a
    single low-confidence blip doesn't carry the same weight as a sustained,
    high-confidence observation.
    """
    base = _CLIENT_SIGNAL_BASE_STRENGTH.get(signal_type, 0.2)
    if reported_strength is None:
        return base
    return max(0.05, min(1.0, base * (0.5 + reported_strength)))


# --- accumulation & flag raising ----------------------------------------

def _severity_for_total(total: float) -> CheatSeverity | None:
    settings = get_settings()
    if total >= settings.cheat_flag_high_threshold:
        return CheatSeverity.HIGH
    if total >= settings.cheat_flag_medium_threshold:
        return CheatSeverity.MEDIUM
    if total >= settings.cheat_flag_low_threshold:
        return CheatSeverity.LOW
    return None


def _summarize(signals: list[CheatSignal]) -> str:
    by_type: dict[CheatSignalType, int] = {}
    for s in signals:
        by_type[s.signal_type] = by_type.get(s.signal_type, 0) + 1
    parts = [
        f"{count}x {_HUMAN_LABEL.get(t, t.value)}" for t, count in by_type.items()
    ]
    return "Possible integrity concern: " + ", ".join(parts)


_HUMAN_LABEL = {
    CheatSignalType.FLUENCY_JUMP: "sudden fluency jump",
    CheatSignalType.COPY_PASTE_STYLE: "copy-paste-style answer",
    CheatSignalType.ANSWER_TOO_FAST: "implausibly fast answer",
    CheatSignalType.MULTIPLE_FACES: "multiple faces on camera",
    CheatSignalType.NO_FACE_DETECTED: "candidate not visible",
    CheatSignalType.GAZE_OFF_SCREEN: "looking away from screen",
    CheatSignalType.EXTRA_VOICE_DETECTED: "a second voice detected",
    CheatSignalType.BACKGROUND_WHISPERING: "background whispering",
    CheatSignalType.APP_BACKGROUNDED: "app switched to background",
    CheatSignalType.SCREEN_SHARE_OR_MIRROR: "screen mirroring/casting detected",
}


def record_signals(session: InterviewSession, new_signals: list[CheatSignal]) -> CheatFlag | None:
    """
    Appends new_signals to the session's evidence log, recomputes the
    accumulated strength, and raises a new CheatFlag if the total has
    crossed into a severity band that hasn't already been flagged at that
    band. Returns the new flag if one was raised, else None (nothing
    crossed a new threshold this call — the signals are still recorded).

    Signals are windowed to the last 25 to keep the running total from
    being dominated by very old, already-addressed behaviour in a long
    interview — the same "don't re-litigate ancient history" reasoning as
    the difficulty controller's rolling weight.
    """
    if not new_signals:
        return None

    session.cheat_signals.extend(new_signals)
    recent = session.cheat_signals[-25:]
    total_strength = sum(s.strength for s in recent)

    severity = _severity_for_total(total_strength)
    if severity is None:
        return None

    for f in session.cheat_flags:
        if f.severity == severity:
            f.summary = _summarize(recent)
            f.contributing_signals = [s.id for s in recent]
            f.transcript_timestamp_ms = recent[-1].transcript_timestamp_ms
            return None

    flag = CheatFlag(
        severity=severity,
        summary=_summarize(recent),
        contributing_signals=[s.id for s in recent],
        transcript_timestamp_ms=recent[-1].transcript_timestamp_ms,
    )
    session.cheat_flags.append(flag)
    return flag