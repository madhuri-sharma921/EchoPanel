"""
Core domain models for EchoPanel.

The Context Graph is the shared memory structure that lets multiple AI
interviewer personas coordinate without re-reading the full transcript.
Every claim a candidate makes is added as a node; personas query the graph
(never the raw transcript) to decide what to challenge or follow up on.
"""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


class PersonaRole(str, Enum):
    TECHNICAL = "technical"
    PRODUCT_BUSINESS = "product_business"
    BEHAVIOURAL = "behavioural"
    CUSTOMER = "customer"
    HIRING_MANAGER = "hiring_manager"


class QuestionDepth(str, Enum):
    RECALL = "recall"
    APPLIED = "applied"
    EDGE_CASE = "edge_case"


class ClaimNode(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    topic: str
    claim: str
    confidence: float = Field(ge=0.0, le=1.0)
    raised_by: PersonaRole
    transcript_timestamp_ms: int
    contradicts: list[UUID] = Field(default_factory=list)
    is_vague: bool = False
    # Single emoji the LLM picked to fit the actual content/tone of the
    # candidate's answer (confident, joking, off-topic like asking for a
    # break, nervous, etc.) — a free-form reaction rather than a fixed
    # vague/contradiction/ok 3-way mapping. Falls back to "" when the LLM
    # didn't return one; the caller (llm_bridge) applies the old
    # vague/contradiction-based default in that case.
    reaction_emoji: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ContextGraph(BaseModel):
    session_id: UUID
    nodes: list[ClaimNode] = Field(default_factory=list)

    def nodes_for_topic(self, topic: str) -> list[ClaimNode]:
        return [n for n in self.nodes if n.topic == topic]

    def add_node(self, node: ClaimNode) -> None:
        self.nodes.append(node)


class TurnLogEntry(BaseModel):
    """
    One full round-trip of the live interview: the persona's question, the
    candidate's answer, and the signals detected on that answer. Populated
    by the /v1/chat/completions bridge as each turn is processed, and
    polled by the Android client (GET /sessions/{id}/turns) so the UI can
    render the running transcript with a reaction emoji per persona turn —
    the ClaimNode graph alone doesn't carry the literal question text.
    """
    index: int
    persona: PersonaRole
    question_text: str
    candidate_answer: str
    is_vague: bool = False
    contradiction_detected: bool = False
    reaction_emoji: str = ""
    transcript_timestamp_ms: int


class InterestScore(BaseModel):
    persona: PersonaRole
    score: float = Field(ge=0.0, le=1.0)
    reason: str


class CompetenceScore(BaseModel):
    topic: str
    score: float = Field(ge=0.0, le=1.0)
    sample_count: int = 0
    next_depth: QuestionDepth = QuestionDepth.RECALL


class TranscriptEntry(BaseModel):
    session_id: UUID
    speaker: str
    text: str
    timestamp_ms: int


class ScenarioCard(BaseModel):
    """
    A short, visual scenario the persona sets up before a role-play or
    scenario-based question (a named PS11 requirement). Rendered as a
    card in the app rather than requiring real image generation — the
    LLM supplies a title, one-line setting, and an emoji/icon that
    together let the candidate "see" the scenario, not just hear it.
    """
    persona: PersonaRole
    title: str
    setting: str
    emoji: str
    created_at: datetime = Field(default_factory=datetime.utcnow)


class CheatSeverity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class CheatSignalType(str, Enum):
    # Text-derived (computed server-side from the candidate's answer text)
    FLUENCY_JUMP = "fluency_jump"          # sudden jump from halting to polished/verbatim-sounding text
    COPY_PASTE_STYLE = "copy_paste_style"  # answer reads like pasted reference material, not spoken speech
    ANSWER_TOO_FAST = "answer_too_fast"    # long, detailed answer returned implausibly quickly
    # Client-reported (from the Android app's on-device proctoring signals)
    MULTIPLE_FACES = "multiple_faces"          # more than one face in the camera frame
    NO_FACE_DETECTED = "no_face_detected"      # candidate not in frame during an active answer
    GAZE_OFF_SCREEN = "gaze_off_screen"        # candidate looking away from the screen for a sustained period
    EXTRA_VOICE_DETECTED = "extra_voice_detected"  # a second speaker's voice detected during the answer
    BACKGROUND_WHISPERING = "background_whispering"  # low-level secondary audio suggesting coaching
    APP_BACKGROUNDED = "app_backgrounded"      # candidate switched away from the app mid-question
    SCREEN_SHARE_OR_MIRROR = "screen_share_or_mirror"  # screen-capture/casting detected on device


class CheatSignal(BaseModel):
    """
    One raw observation feeding into a session's cheating assessment. Kept
    separate from CheatFlag (below) because many low-weight signals over
    time should accumulate into a flag, rather than every signal itself
    being a user-facing alert — this is the append-only evidence log a
    flag's `contributing_signals` points back into, mirroring how ClaimNode
    evidence backs a VerdictItem in the final report.
    """
    id: UUID = Field(default_factory=uuid4)
    signal_type: CheatSignalType
    detail: str = ""
    # 0-1 confidence/strength of this one observation, not a final verdict.
    strength: float = Field(default=0.5, ge=0.0, le=1.0)
    transcript_timestamp_ms: int = 0
    source: str = "client"  # "client" (video/audio telemetry) or "server" (text analysis)
    created_at: datetime = Field(default_factory=datetime.utcnow)


class CheatFlag(BaseModel):
    """
    A user-facing cheating alert raised once accumulated CheatSignals cross
    a severity threshold. Every flag traces back to the signals that caused
    it (`contributing_signals`) so neither interviewer nor candidate ever
    sees a bare, unexplained accusation — same evidence-linked philosophy
    as VerdictItem in the final report.
    """
    id: UUID = Field(default_factory=uuid4)
    severity: CheatSeverity
    summary: str
    contributing_signals: list[UUID] = Field(default_factory=list)
    transcript_timestamp_ms: int = 0
    acknowledged: bool = False
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ScriptQuestionSource(str, Enum):
    SUGGESTED = "suggested"   # generated by a persona / the backend from the Context Graph
    CUSTOM = "custom"         # typed by the human interviewer live


class ScriptEntry(BaseModel):
    """
    One entry in the shared live "script panel" both the interviewer and
    the interviewee can see during the call: either a system-suggested
    next question (grounded in the Context Graph, same data the personas
    already use) or a question the human interviewer typed in live. This
    is deliberately a separate, human-readable list from TurnLogEntry —
    TurnLogEntry is the record of what was actually asked/answered;
    ScriptEntry is the forward-looking "what might get asked" plan.
    """
    id: UUID = Field(default_factory=uuid4)
    source: ScriptQuestionSource
    text: str
    persona: Optional[PersonaRole] = None
    topic_hint: str = ""
    used: bool = False
    created_at: datetime = Field(default_factory=datetime.utcnow)


class InterviewSession(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    candidate_name: str
    active_personas: list[PersonaRole]
    started_at: datetime = Field(default_factory=datetime.utcnow)
    consent_logged_at: Optional[datetime] = None
    context_graph: ContextGraph = None
    competence_scores: dict[str, CompetenceScore] = Field(default_factory=dict)
    agora_agent_id: Optional[str] = None
    last_speaking_persona: Optional[PersonaRole] = None
    latest_scenario: Optional[ScenarioCard] = None
    turn_log: list[TurnLogEntry] = Field(default_factory=list)
    # Set ONLY by api/script.py's mark_used, when the human interviewer
    # picks an AI-suggested (or their own typed) question from the shared
    # script panel and commits to asking it next. generate_followup()
    # consumes and clears this on the very next turn (see agora_hooks.py
    # and llm_bridge.py) so it fires exactly once per pin. Do NOT reuse
    # this field for anything else — see last_asked_question_text below
    # for why that used to happen and broke the whole mechanism.
    pending_question_text: Optional[str] = None
    # The most recent follow-up actually spoken to the candidate but not
    # yet answered — paired with their next answer to form the next
    # TurnLogEntry (see llm_bridge.chat_completions_bridge).
    #
    # FIX (interviewer stuck repeating itself / suggestions ignored):
    # this bookkeeping used to be stored in pending_question_text too,
    # which is ALSO the field mark_used() pins a script-panel choice into.
    # Since llm_bridge overwrote pending_question_text with the
    # just-spoken question at the end of every turn, the very next turn
    # would read that leftover value back as if the interviewer had
    # pinned it, short-circuit generate_followup() past the LLM, and
    # re-ask the exact same question verbatim — forever, regardless of
    # the Context Graph, the turn arbiter, or any real suggestion the
    # interviewer picked from the script panel. A real pin from
    # mark_used() only ever survived one turn before being clobbered by
    # this same reuse. Separating the two fields lets pending_question_text
    # be exclusively "a human's real pin" again.
    last_asked_question_text: Optional[str] = None
    # Proctoring / integrity state (see services/cheating_detector.py).
    cheat_signals: list[CheatSignal] = Field(default_factory=list)
    cheat_flags: list[CheatFlag] = Field(default_factory=list)
    # Shared live script panel (see api/script.py) — both interviewer and
    # interviewee poll this so they see the same running list.
    script: list[ScriptEntry] = Field(default_factory=list)

    class Config:
        arbitrary_types_allowed = True


class VerdictItem(BaseModel):
    competency: str
    score: float
    verdict: str
    supporting_claim_id: UUID
    transcript_timestamp_ms: int


class FinalReport(BaseModel):
    session_id: UUID
    candidate_name: str
    per_competency: list[VerdictItem]
    contradictions_flagged: int
    vague_answers_flagged: int
    generated_at: datetime = Field(default_factory=datetime.utcnow)