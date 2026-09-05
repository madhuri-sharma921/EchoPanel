"""
Persona reasoning engine — calls OpenAI with role-specific system prompts
and the relevant slice of the Context Graph (never the raw transcript) to
generate the next question or challenge.
"""
import json
import logging
import re

from openai import AsyncOpenAI

from app.core.config import get_settings
from app.models.schemas import ClaimNode, ContextGraph, PersonaRole
from app.personas.base import PERSONA_DEFINITIONS

logger = logging.getLogger("echopanel.personas.engine")

_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE | re.MULTILINE)


def _safe_json_parse(raw: str | None, *, context: str) -> dict:
    """
    Parse an LLM's JSON-mode response defensively.

    Groq/Llama (and other non-OpenAI backends behind this OpenAI-compatible
    client) don't always honor `response_format={"type": "json_object"}` as
    strictly as OpenAI does — they can wrap the JSON in markdown fences, add
    a stray leading/trailing sentence, or occasionally return empty content.
    Previously a single malformed response here raised an unhandled
    JSONDecodeError, which surfaced as a 500 on /agora/turn and made the AI
    interviewer go completely silent for that turn. We now degrade to an
    empty dict (callers already fall back to sane defaults via .get(...))
    instead of taking the whole turn down.
    """
    if not raw or not raw.strip():
        logger.warning("Empty LLM response while parsing %s; falling back to {}", context)
        return {}

    text = raw.strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Strip ```json ... ``` or ``` ... ``` fences some models add despite
    # json_object mode, then retry.
    stripped = _FENCE_RE.sub("", text).strip()
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass

    # Last resort: grab the outermost {...} block in case there's leading/
    # trailing prose around otherwise-valid JSON.
    match = re.search(r"\{.*\}", stripped, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass

    logger.warning(
        "Could not parse LLM response as JSON while parsing %s; falling back to {}. "
        "Raw response (truncated): %r",
        context,
        text[:500],
    )
    return {}

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
        "reaction_emoji": {
            "type": "string",
            "description": (
                "A single emoji capturing how a human interviewer would "
                "genuinely react to this specific answer — not just "
                "good/bad. Pick whatever fits: confident/solid technical "
                "answer, funny/joking remark, off-topic aside (e.g. "
                "saying they're hungry or want a break), nervous/unsure, "
                "rude or dismissive, evasive, enthusiastic, etc. Choose "
                "freely from the full emoji range rather than a fixed set."
            ),
        },
    },
    "required": ["topic", "claim", "confidence", "reaction_emoji"],
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
                    "strict JSON matching this schema:\n"
                    f"{json.dumps(_CLAIM_EXTRACTION_SCHEMA)}\n"
                    "Be concise. For reaction_emoji, react to what they "
                    "ACTUALLY said — a confident technical answer, a joke, "
                    "an off-topic remark (e.g. asking for a break or saying "
                    "they're hungry), nervousness, rudeness, enthusiasm, "
                    "evasiveness, etc. all deserve different, specific "
                    "emoji, not a generic thumbs up/down."
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
    data = _safe_json_parse(
        response.choices[0].message.content, context="extract_claim"
    )
    try:
        confidence = float(data.get("confidence", 0.5))
    except (TypeError, ValueError):
        confidence = 0.5
    reaction_emoji = str(data.get("reaction_emoji") or "").strip()
    return ClaimNode(
        topic=data.get("topic") or topic_hint,
        claim=data.get("claim") or candidate_answer,
        confidence=confidence,
        raised_by=role,
        transcript_timestamp_ms=transcript_timestamp_ms,
        reaction_emoji=reaction_emoji,
    )


_STUCK_PHRASES = (
    "i don't know",
    "i dont know",
    "no idea",
    "not sure",
    "tell me",
    "you tell me",
    "i can't answer",
    "i cant answer",
    "i don't want to answer",
    "i dont want to answer",
    "skip",
    "pass",
)


def _looks_stuck(candidate_answer: str) -> bool:
    """
    Heuristic for 'the candidate is stuck / refusing / asking us to just
    tell them', as opposed to a normal (even if weak) attempt at an answer.

    This matters because the followup prompt previously had no way to
    distinguish "vague technical answer, push for more specifics" from
    "candidate explicitly said they don't know and asked to move on" — both
    looked identical to the LLM (a low-confidence claim node), so it kept
    firing a brand new elaborate scenario question every turn regardless of
    what the candidate actually said. That reads as the AI not listening.
    """
    text = (candidate_answer or "").strip().lower()
    if not text:
        return True
    return any(phrase in text for phrase in _STUCK_PHRASES)


async def generate_followup(
    role: PersonaRole,
    graph: ContextGraph,
    topic_hint: str | None,
    question_depth: str,
    candidate_answer: str | None = None,
) -> tuple[str, dict | None]:
    """
    Generate the persona's next question/challenge, grounded in the graph.

    Returns (spoken_text, scenario). scenario is None for an ordinary
    follow-up question. When the persona decides a role-play or
    scenario-based question fits better than a plain follow-up (a named
    PS11 requirement), it also returns a short visual "scenario card"
    (title, one-line setting, emoji) that the app renders alongside the
    spoken question, so the candidate has something to see, not just hear.

    `candidate_answer` is the candidate's literal last utterance (not the
    graph's distilled claim). Passing it through — and explicitly telling
    the model when the candidate looks stuck — is what lets the persona
    react to "I don't know, tell me" with a hint or a simpler restatement
    instead of barrelling ahead with an unrelated new scenario every turn.
    """
    settings = get_settings()
    client = _client()
    persona = PERSONA_DEFINITIONS[role]

    context = _graph_context_for_prompt(graph, topic_hint)
    stuck = _looks_stuck(candidate_answer) if candidate_answer is not None else False

    candidate_block = (
        f'Candidate\'s literal last answer: "{candidate_answer}"\n'
        if candidate_answer
        else ""
    )

    if stuck:
        reaction_instruction = (
            "The candidate just indicated they don't know, are stuck, or "
            "explicitly asked you to just tell them / move on — do NOT "
            "launch into a new unrelated scenario. Instead, either (a) "
            "give a short, concrete hint or simplify the current question "
            "so it's easier to attempt, or (b) briefly acknowledge that's "
            "fine and move on to a different, more approachable topic. "
            "Keep it encouraging and natural, not a fresh elaborate "
            "role-play. Leave the scenario fields as empty strings this "
            "turn."
        )
    else:
        reaction_instruction = (
            "First judge whether the candidate's literal last answer above "
            "is actually a substantive attempt to answer the interview "
            "question, or whether it's off-topic small talk / an aside "
            "unrelated to the interview (e.g. mentioning they're hungry, "
            "tired, need a break, asking about logistics, etc.).\n\n"
            "If it is off-topic: do NOT build a scenario or technical "
            "question out of the literal content of what they said. "
            "Briefly and naturally acknowledge it in one short clause, "
            "then steer back to the interview by re-asking or lightly "
            "rephrasing the same question you asked before. Leave the "
            "scenario fields as empty strings this turn.\n\n"
            "If it IS a real attempt to answer: generate your next spoken "
            "question or challenge, responding directly to what they just "
            "said — don't ignore it or change subject arbitrarily. Keep it "
            "to 1-2 sentences, natural spoken voice register (this will go "
            "through TTS). Sometimes — not every turn, only when it would "
            "genuinely sharpen the question — frame it as a short "
            "role-play or scenario instead of a plain question (e.g. "
            "'Picture this: a customer calls furious that...'). When you "
            "do, also fill in the scenario fields below so it can be shown "
            "visually. Leave them empty strings when this turn is just a "
            "plain follow-up question."
        )

    response = await client.chat.completions.create(
        model=settings.openai_model,
        messages=[
            {"role": "system", "content": persona.system_prompt},
            {
                "role": "user",
                "content": (
                    f"Rubric: {persona.rubric}\n"
                    f"Target question depth: {question_depth}\n"
                    f"{candidate_block}"
                    f"Relevant claims from the shared context graph so far:\n"
                    f"{context}\n\n"
                    f"{reaction_instruction}\n\n"
                    "Respond as strict JSON:\n"
                    '{"spoken_text": "...", "scenario_title": "...", '
                    '"scenario_setting": "...", "scenario_emoji": "..."}\n'
                    "scenario_title: 2-4 words. scenario_setting: one vivid "
                    "sentence painting the scene. scenario_emoji: a single "
                    "emoji capturing the scenario's mood."
                ),
            },
        ],
        response_format={"type": "json_object"},
    )
    data = _safe_json_parse(
        response.choices[0].message.content, context="generate_followup"
    )
    spoken_text = (data.get("spoken_text") or "").strip()
    if not spoken_text:
        # The persona must say *something* — never let a parsing/formatting
        # hiccup on the LLM's side mean the interviewer goes silent.
        logger.warning(
            "generate_followup got no usable spoken_text for role=%s; using fallback",
            role.value,
        )
        spoken_text = (
            "Sorry, could you say a bit more about that? I want to make "
            "sure I follow your point."
        )

    scenario = None
    title = (data.get("scenario_title") or "").strip()
    setting = (data.get("scenario_setting") or "").strip()
    if title and setting:
        scenario = {
            "persona": role.value,
            "title": title,
            "setting": setting,
            "emoji": (data.get("scenario_emoji") or "").strip() or "🎭",
        }

    return spoken_text, scenario