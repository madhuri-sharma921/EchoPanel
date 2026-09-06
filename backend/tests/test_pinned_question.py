import asyncio

from uuid import uuid4

from app.models.schemas import ContextGraph, PersonaRole
from app.personas.engine import generate_followup


def test_pinned_question_returned_verbatim_without_llm_call():
    """
    A pinned_question (set when the interviewer marks a script entry as
    used — see api/script.py's mark_used) must be returned exactly as
    given, with no LLM call and no scenario card. If this ever touched
    the OpenAI client it would raise here anyway, since no API key or
    mock is configured in this test — so a passing test is itself proof
    the short-circuit happens before any network call.
    """
    graph = ContextGraph(session_id=uuid4())
    pinned_text = "Walk me through what happens if that payment service goes down at peak traffic."

    spoken_text, scenario = asyncio.run(
        generate_followup(
            role=PersonaRole.TECHNICAL,
            graph=graph,
            topic_hint="payments",
            question_depth="applied",
            candidate_answer="We use a retry queue.",
            pinned_question=pinned_text,
        )
    )

    assert spoken_text == pinned_text
    assert scenario is None


def test_no_pin_falls_through_to_normal_path_signature():
    """
    Sanity check that omitting pinned_question doesn't change the
    function's contract — this test doesn't call the LLM (that would
    need a real key), it just confirms the parameter is optional and
    defaults to None without raising a TypeError.
    """
    import inspect

    sig = inspect.signature(generate_followup)
    assert "pinned_question" in sig.parameters
    assert "suggested_questions" in sig.parameters
    assert sig.parameters["pinned_question"].default is None


def test_pinned_full_question_short_circuits():
    graph = ContextGraph(session_id=uuid4())
    pinned_text = "What happens when food orders surge during dinner rush?"
    spoken_text, scenario = asyncio.run(
        generate_followup(
            role=PersonaRole.TECHNICAL,
            graph=graph,
            topic_hint="food",
            question_depth="applied",
            candidate_answer="Everything caches.",
            pinned_question=pinned_text,
        )
    )
    assert spoken_text == pinned_text
    assert scenario is None