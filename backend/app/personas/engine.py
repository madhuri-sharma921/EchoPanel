"""
Persona reasoning engine — calls OpenAI with role-specific system prompts
and the relevant slice of the Context Graph (never the raw transcript) to
generate the next question or challenge.
"""
import json

from openai import AsyncOpenAI

from app.core.config import get_settings
from app.models.schemas import ClaimNode, ContextGraph, PersonaRole
from app.personas.base import PERSONA_DEFINITIONS

_CLAIM_EXTRACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "topic": {"type": "string"},
        "claim": {"type": "string"},
        "confidence": {
            "type": "number",
            "description": (
                "How concrete/specific the candidate's answer was, 0-1. "
                "Low confidence = vague, hand-wavy, or evasive."
            ),
        },
    },
    "required": ["topic", "claim", "confidence"],
}


def _client() -> AsyncOpenAI:
    settings = get_settings()
    return AsyncOpenAI(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url or None,
    )


def _graph_context_for_prompt(graph: ContextGraph, topic_hint: str | None) -> str:
    """Serialize the relevant graph nodes for the persona's prompt context."""
    nodes = graph.nodes_for_topic(topic_hint) if topic_hint else graph.nodes[-10:]
    return json.dumps(
        [
            {
                "topic": n.topic,
                "claim": n.claim,
                "raised_by": n.raised_by.value,
                "confidence": n.confidence,
                "is_vague": n.is_vague,
                "has_contradictions": bool(n.contradicts),
            }
            for n in nodes
        ],
        indent=2,
    )


async def extract_claim(
    candidate_answer: str, topic_hint: str, transcript_timestamp_ms: int, role: PersonaRole
) -> ClaimNode:
    """Extract a structured claim node from the candidate's raw answer."""
    settings = get_settings()
    client = _client()

    response = await client.chat.completions.create(
        model=settings.openai_model,
        messages=[
            {
                "role": "system",
                "content": (
                    "Extract the candidate's core claim from their answer as "
                    "strict JSON matching the given schema. Be concise."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Topic hint: {topic_hint}\nCandidate answer: {candidate_answer}"
                ),
            },
        ],
        response_format={"type": "json_object"},
    )
    data = json.loads(response.choices[0].message.content)
    return ClaimNode(
        topic=data.get("topic", topic_hint),
        claim=data.get("claim", candidate_answer),
        confidence=float(data.get("confidence", 0.5)),
        raised_by=role,
        transcript_timestamp_ms=transcript_timestamp_ms,
    )


async def generate_followup(
    role: PersonaRole,
    graph: ContextGraph,
    topic_hint: str | None,
    question_depth: str,
) -> str:
    """Generate the persona's next question/challenge, grounded in the graph."""
    settings = get_settings()
    client = _client()
    persona = PERSONA_DEFINITIONS[role]

    context = _graph_context_for_prompt(graph, topic_hint)

    response = await client.chat.completions.create(
        model=settings.openai_model,
        messages=[
            {"role": "system", "content": persona.system_prompt},
            {
                "role": "user",
                "content": (
                    f"Rubric: {persona.rubric}\n"
                    f"Target question depth: {question_depth}\n"
                    f"Relevant claims from the shared context graph so far:\n"
                    f"{context}\n\n"
                    "Generate your next spoken question or challenge for the "
                    "candidate. Keep it to 1-2 sentences, natural spoken "
                    "voice register (this will go through TTS)."
                ),
            },
        ],
    )
    return response.choices[0].message.content.strip()
