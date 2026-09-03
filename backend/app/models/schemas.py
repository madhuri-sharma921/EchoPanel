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
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ContextGraph(BaseModel):
    session_id: UUID
    nodes: list[ClaimNode] = Field(default_factory=list)

    def nodes_for_topic(self, topic: str) -> list[ClaimNode]:
        return [n for n in self.nodes if n.topic == topic]

    def add_node(self, node: ClaimNode) -> None:
        self.nodes.append(node)


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