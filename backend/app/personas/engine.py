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
    pinned_question: str | None = None,
    suggested_questions: list[str] | None = None,
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

    `pinned_question`, when set, comes from the shared script panel: the
    human interviewer picked an AI-suggested question or typed their own
    and explicitly marked it as the one to ask next (see
    api/script.py's mark_used and InterviewSession.pending_question_text).
    When set, this function asks EXACTLY that text — no LLM call, no
    rephrasing — because the whole point of the script panel is that the
    interviewer's chosen question actually gets asked, not treated as a
    vague suggestion the model is free to ignore. The scenario slot is
    always None for a pinned question, since role-play framing wasn't
    part of what the interviewer picked.

    `suggested_questions`, when provided, supplies a list of candidate
    questions for this persona/topic so the AI interviewer can ask on a
    suggestion basis (verbatim or adapted to flow naturally).
    """
    if pinned_question and (pinned_question.strip().endswith("?") or len(pinned_question.strip().split()) >= 4):
        return pinned_question.strip(), None

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

    pinned_instruction = ""
    if pinned_question:
        pinned_instruction = (
            f"\nHUMAN INTERVIEWER DIRECTIVE: The human interviewer explicitly wants you to "
            f"ask a question regarding the topic/suggestion: '{pinned_question.strip()}'. "
            f"Formulate a sharp, professional interview question exploring this topic "
            f"(e.g. framing a relevant engineering or behavioral scenario around '{pinned_question.strip()}'). "
            f"You MUST base this turn's question on this suggestion.\n"
        )

    suggestion_block = ""
    if suggested_questions:
        cleaned_suggestions = [q.strip() for q in suggested_questions if q.strip()]
        if cleaned_suggestions:
            bullets = "\n".join(f"- {q}" for q in cleaned_suggestions)
            suggestion_block = (
                f"\nSuggested questions grounded in context/topics:\n{bullets}\n"
                "Prioritize asking one of these suggested questions (verbatim or adapted naturally), "
                "or formulate a follow-up directly on the basis of these suggestions.\n"
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
            "Analyze the candidate's last answer and context. If they bring up everyday topics, metaphors, "
            "or asides (such as mentioning they're hungry, ordering food, need a break, or personal routines), "
            "cleverly and naturally bridge it into an interview question or scenario relevant to your persona's "
            "domain (e.g. if hungry or food is mentioned, you can ask about designing a high-concurrency food "
            "delivery platform, handling peak lunch rush order dispatching, or workload balance). "
            "Never rigidly dismiss their remarks or say 'let us refocus' without addressing it. "
            "Keep the question to 1-2 sentences in a natural spoken voice register. "
            "When suggested questions are listed above, actively ask one on a suggestion basis. "
            "Frame it as a short role-play or scenario when it sharpens the question, and fill in the scenario fields."
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
                    f"{context}\n"
                    f"{suggestion_block}\n"
                    f"{pinned_instruction}\n"
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


async def generate_suggested_questions(
    role: PersonaRole,
    graph: ContextGraph,
    topic_hint: str | None,
    count: int = 3,
) -> list[str]:
    """
    Generates a short list of candidate next-questions for this persona,
    grounded in the same Context Graph slice generate_followup() uses —
    but WITHOUT committing to asking any of them. This backs the shared
    live "script panel" (api/script.py): the human interviewer sees these
    as suggestions and can pick one, edit one, or type their own instead,
    rather than the panel only ever being able to auto-ask on its own.
    """
    settings = get_settings()
    client = _client()
    persona = PERSONA_DEFINITIONS[role]
    context = _graph_context_for_prompt(graph, topic_hint)

    topic_directive = (
        f"You MUST generate suggested questions specifically exploring the topic/theme '{topic_hint}'. "
        if topic_hint
        else ""
    )

    response = await client.chat.completions.create(
        model=settings.openai_model,
        messages=[
            {"role": "system", "content": persona.system_prompt},
            {
                "role": "user",
                "content": (
                    f"Rubric: {persona.rubric}\n"
                    f"Relevant claims from the shared context graph so far:\n"
                    f"{context}\n\n"
                    f"{topic_directive}"
                    f"Suggest {count} distinct candidate next-questions this "
                    "persona could ask, grounded in the graph or topic above. Keep "
                    "each under 2 sentences, natural spoken register. "
                    'Respond as strict JSON: {"questions": ["...", "..."]}'
                ),
            },
        ],
        response_format={"type": "json_object"},
    )
    data = _safe_json_parse(
        response.choices[0].message.content, context="generate_suggested_questions"
    )
    questions = data.get("questions")
    if not isinstance(questions, list) or not questions:
        return []
    return [str(q).strip() for q in questions if str(q).strip()][:count]