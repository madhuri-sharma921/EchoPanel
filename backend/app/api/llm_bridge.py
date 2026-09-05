"""
OpenAI-compatible /v1/chat/completions bridge.

Agora's Conversational AI Engine is configured (see agora_agent_service.py)
to call THIS endpoint instead of a raw LLM provider. Agora sends the
running conversation in OpenAI's message format; we pull the candidate's
latest utterance out of it, run our existing pipeline (claim extraction,
contradiction/vagueness detection, difficulty control, Turn Arbiter,
persona follow-up generation), and return the result in OpenAI's response
shape so Agora's engine can speak it via TTS.

The session_id has no reliable home in OpenAI's protocol — Agora's actual
requests carry no top-level `user` field — so it's embedded directly in
the URL path instead (see agora_agent_service.start_agent, which builds
that URL when starting the agent for a channel).
"""
import logging
import time
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request

from app.models.schemas import ScenarioCard, TurnLogEntry
from app.personas.engine import extract_claim, generate_followup
from app.services.cheating_detector import detect_text_signals, record_signals
from app.services.context_graph_store import get_context_graph_store
from app.services.contradiction_detector import process_new_claim
from app.services.difficulty_controller import update_competence
from app.services.turn_arbiter import compute_interest_scores, pick_next_persona

logger = logging.getLogger("echopanel.llm_bridge")
router = APIRouter(tags=["llm-bridge"])


def _fallback_emoji(claim) -> str:
    """Old fixed 3-way mapping, used only if the LLM didn't supply a
    content-aware reaction_emoji for this answer."""
    if claim.contradicts:
        return "⚡"
    if claim.is_vague:
        return "🤔"
    return "👍"


def _latest_user_message(messages: list[dict]) -> str | None:
    for message in reversed(messages):
        if message.get("role") == "user":
            content = message.get("content", "")
            return content if content else None
    return None


@router.post("/v1/chat/completions/{session_id}")
async def chat_completions_bridge(session_id: UUID, request: Request) -> dict:
    raw_body = await request.body()
    logger.info("Raw incoming bridge request body: %s", raw_body)

    try:
        body = await request.json()
    except Exception as exc:
        logger.error("Failed to parse bridge request as JSON: %s", exc)
        raise HTTPException(
            status_code=400, detail=f"Invalid JSON body: {exc}"
        ) from exc

    logger.info("Parsed incoming bridge request for session %s: %s", session_id, body)
    store = get_context_graph_store()

    try:
        session = store.get_or_404(session_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    candidate_text = _latest_user_message(body.get("messages", []))
    graph = session.context_graph

    if candidate_text is None:
        # No candidate utterance yet — this is the engine's initial
        # greeting-generation call (or a blank interim ASR result) before
        # real speech has arrived. Return a neutral placeholder rather
        # than running the pipeline on nothing.
        greeting = "Hello, let's get started."
        if body.get("stream"):
            return _streaming_response(session_id, greeting)
        return _completion_response(session_id, greeting)

    # No structured topic hint arrives from Agora's raw transcript feed,
    # so fall back to a generic bucket. Good enough for the Context Graph
    # to still group related claims; refine later with topic detection.
    topic_hint = "general"

    # The persona the candidate is answering is whoever the Turn Arbiter
    # picked last turn (that's who actually asked the question they're
    # responding to now) — falls back to the first active persona only
    # for the very first turn, when nobody has spoken yet.
    responding_to = session.last_speaking_persona or session.active_personas[0]

    claim = await extract_claim(
        candidate_answer=candidate_text,
        topic_hint=topic_hint,
        transcript_timestamp_ms=int(time.time() * 1000),
        role=responding_to,
    )
    claim = process_new_claim(claim, graph)
    graph.add_node(claim)

    # Text-derived cheating signals on this live-voice turn too — this is
    # the path Agora actually calls during a real call (see module
    # docstring); /agora/turn runs the same check for the non-live/manual
    # test path. Client-reported video/audio signals arrive independently
    # via POST /proctoring/{session_id}/signal and accumulate into the
    # same session.cheat_signals log either way.
    prior_answers = [t.candidate_answer for t in session.turn_log]
    text_signals = detect_text_signals(
        answer_text=candidate_text,
        prior_answers=prior_answers,
        seconds_since_question=None,
        transcript_timestamp_ms=int(time.time() * 1000),
    )
    record_signals(session, text_signals)

    competence = update_competence(
        session, topic=claim.topic, observed_score=claim.confidence
    )

    scores = compute_interest_scores(
        active_personas=session.active_personas,
        latest_topic=claim.topic,
        graph=graph,
    )
    winner = pick_next_persona(scores)
    session.last_speaking_persona = winner.persona

    spoken_text, scenario = await generate_followup(
        role=winner.persona,
        graph=graph,
        topic_hint=claim.topic,
        question_depth=competence.next_depth.value,
        candidate_answer=candidate_text,
    )
    if scenario is not None:
        session.latest_scenario = ScenarioCard(**scenario)

    # Log this turn: the candidate's answer pairs with whichever question
    # is currently "pending" (the question `responding_to` asked last
    # round — stashed on the session when that follow-up was generated).
    # The freshly generated follow-up becomes the new pending question,
    # to be paired with the candidate's next answer. This is what the
    # Android app polls via GET /sessions/{id}/turns to render the live
    # transcript + reaction emoji — the ClaimNode graph alone doesn't
    # retain the literal question text a persona asked.
    pending_question = session.pending_question_text or (
        "Tell me a bit about a recent project you've worked on."
    )
    session.turn_log.append(
        TurnLogEntry(
            index=len(session.turn_log),
            persona=responding_to,
            question_text=pending_question,
            candidate_answer=candidate_text,
            is_vague=claim.is_vague,
            contradiction_detected=bool(claim.contradicts),
            # Prefer the LLM's content-aware reaction (e.g. 🍔 for "I'm
            # hungry", 😂 for a joke); fall back to the old fixed
            # vague/contradiction mapping only if it didn't return one.
            reaction_emoji=claim.reaction_emoji or _fallback_emoji(claim),
            transcript_timestamp_ms=int(time.time() * 1000),
        )
    )
    session.pending_question_text = spoken_text

    if body.get("stream"):
        return _streaming_response(session_id, spoken_text)
    return _completion_response(session_id, spoken_text)


def _completion_response(session_id: UUID, spoken_text: str) -> dict:
    # OpenAI-compatible non-streaming response shape. Agora's engine reads
    # choices[0].message.content and sends it to TTS.
    return {
        "id": f"echopanel-{session_id}-{int(time.time())}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": "echopanel-panel",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": spoken_text},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        },
    }


def _streaming_response(session_id: UUID, spoken_text: str):
    """
    Agora's engine requests stream=true and expects an OpenAI-compatible
    Server-Sent Events response. We don't stream token-by-token internally,
    but we can still satisfy the SSE *protocol* by sending the full text as
    a single chunk followed by the terminating [DONE] marker — this is
    valid per the SSE chat-completions spec even without real streaming.
    """
    import json as json_module

    from fastapi.responses import StreamingResponse

    async def event_generator():
        chunk = {
            "id": f"echopanel-{session_id}-{int(time.time())}",
            "object": "chat.completion.chunk",
            "created": int(time.time()),
            "model": "echopanel-panel",
            "choices": [
                {
                    "index": 0,
                    "delta": {"role": "assistant", "content": spoken_text},
                    "finish_reason": None,
                }
            ],
        }
        yield f"data: {json_module.dumps(chunk)}\n\n"

        final_chunk = {
            "id": f"echopanel-{session_id}-{int(time.time())}",
            "object": "chat.completion.chunk",
            "created": int(time.time()),
            "model": "echopanel-panel",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
        }
        yield f"data: {json_module.dumps(final_chunk)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")