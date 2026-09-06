"""
Tests for the /v1/chat/completions/{session_id} bridge that Agora's
Conversational AI Engine calls in place of a raw LLM provider. OpenAI
calls inside the persona engine are mocked — these tests verify the
bridge's request parsing, session lookup, and OpenAI-compatible response
shape, not the LLM's actual output.

The session_id is a URL path parameter, not a body field — Agora's real
requests carry no top-level `user` field, so the session must be threaded
through the endpoint URL itself (see agora_agent_service.start_agent).
"""
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.main import app
from app.models.schemas import ClaimNode, PersonaRole

client = TestClient(app)


def _create_session() -> str:
    response = client.post(
        "/sessions",
        params={
            "candidate_name": "Test Candidate",
            "active_personas": ["technical", "product_business"],
        },
    )
    assert response.status_code in (200, 201)
    return response.json()["id"]


def test_bridge_returns_openai_shaped_response():
    session_id = _create_session()
    fake_claim = ClaimNode(
        topic="general",
        claim="Candidate discussed a project",
        confidence=0.7,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )

    with patch(
        "app.api.llm_bridge.extract_claim", new=AsyncMock(return_value=fake_claim)
    ), patch(
        "app.api.llm_bridge.generate_followup",
        new=AsyncMock(return_value=("Tell me more about the impact.", None)),
    ):
        response = client.post(
            f"/v1/chat/completions/{session_id}",
            json={
                "model": "gpt-4o",
                "messages": [
                    {"role": "user", "content": "I built a caching layer."}
                ],
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["object"] == "chat.completion"
    assert body["choices"][0]["message"]["role"] == "assistant"
    assert body["choices"][0]["message"]["content"] == "Tell me more about the impact."


def test_bridge_returns_greeting_when_no_user_message_yet():
    """
    The engine's very first call (before the candidate has spoken) has no
    user-role message with real content — this must return a graceful
    greeting response rather than an error, since it's a normal call shape
    Agora's engine actually sends (confirmed via production logs).
    """
    session_id = _create_session()
    response = client.post(
        f"/v1/chat/completions/{session_id}",
        json={
            "model": "gpt-4o",
            "messages": [
                {"role": "system", "content": "system prompt"},
                {"role": "user", "content": ""},
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["choices"][0]["message"]["role"] == "assistant"
    assert len(body["choices"][0]["message"]["content"]) > 0


def test_bridge_streams_when_requested():
    session_id = _create_session()
    fake_claim = ClaimNode(
        topic="general",
        claim="Candidate discussed a project",
        confidence=0.7,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )

    with patch(
        "app.api.llm_bridge.extract_claim", new=AsyncMock(return_value=fake_claim)
    ), patch(
        "app.api.llm_bridge.generate_followup",
        new=AsyncMock(return_value=("Tell me more.", None)),
    ):
        response = client.post(
            f"/v1/chat/completions/{session_id}",
            json={
                "model": "gpt-4o",
                "stream": True,
                "messages": [{"role": "user", "content": "I built a caching layer."}],
            },
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "[DONE]" in response.text


def test_bridge_404s_for_unknown_session():
    import uuid

    response = client.post(
        f"/v1/chat/completions/{uuid.uuid4()}",
        json={
            "model": "gpt-4o",
            "messages": [{"role": "user", "content": "hi"}],
        },
    )
    assert response.status_code == 404


def test_bridge_400s_on_invalid_json():
    session_id = _create_session()
    response = client.post(
        f"/v1/chat/completions/{session_id}",
        content=b"not valid json",
        headers={"content-type": "application/json"},
    )
    assert response.status_code == 400


def test_bridge_stores_scenario_card_when_persona_provides_one():
    """
    When generate_followup decides a role-play/scenario framing fits (a
    named PS11 requirement), the returned scenario must be persisted on
    the session and exposed via GET /sessions/{id}/scenario so the app
    can render it visually alongside the spoken question.
    """
    session_id = _create_session()
    fake_claim = ClaimNode(
        topic="customer support",
        claim="Candidate handled an escalation",
        confidence=0.6,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )
    fake_scenario = {
        "persona": "customer",
        "title": "Angry Customer Call",
        "setting": "A customer is furious their data disappeared after an update.",
        "emoji": "📞",
    }

    with patch(
        "app.api.llm_bridge.extract_claim", new=AsyncMock(return_value=fake_claim)
    ), patch(
        "app.api.llm_bridge.generate_followup",
        new=AsyncMock(return_value=("Picture this: a customer calls furious.", fake_scenario)),
    ):
        response = client.post(
            f"/v1/chat/completions/{session_id}",
            json={
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "I fixed a data loss bug."}],
            },
        )
    assert response.status_code == 200

    scenario_response = client.get(f"/sessions/{session_id}/scenario")
    assert scenario_response.status_code == 200
    scenario = scenario_response.json()["scenario"]
    assert scenario is not None
    assert scenario["title"] == "Angry Customer Call"
    assert scenario["emoji"] == "📞"


def test_scenario_endpoint_returns_null_for_fresh_session():
    session_id = _create_session()
    response = client.get(f"/sessions/{session_id}/scenario")
    assert response.status_code == 200
    assert response.json()["scenario"] is None