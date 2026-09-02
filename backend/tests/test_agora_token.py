"""
Tests for the Agora RTC token endpoint. Uses dummy App ID/Certificate
values via monkeypatched settings — these don't need to be real Agora
credentials for the token-building logic itself to be verified.
"""
import uuid

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import app

client = TestClient(app)


def _create_session() -> str:
    response = client.post(
        "/sessions",
        params={
            "candidate_name": "Test Candidate",
            "active_personas": ["technical", "product_business"],
        },
    )
    assert response.status_code == 200
    return response.json()["id"]


def test_token_endpoint_returns_token_for_valid_session(monkeypatch):
    monkeypatch.setenv("AGORA_APP_ID", "a" * 32)
    monkeypatch.setenv("AGORA_APP_CERTIFICATE", "b" * 32)
    get_settings.cache_clear()

    session_id = _create_session()
    response = client.get(f"/agora/token/{session_id}")

    assert response.status_code == 200
    body = response.json()
    assert body["channel_name"] == session_id
    assert body["app_id"] == "a" * 32
    assert len(body["token"]) > 0
    assert body["expires_at"] > 0

    get_settings.cache_clear()


def test_token_endpoint_404s_for_unknown_session(monkeypatch):
    monkeypatch.setenv("AGORA_APP_ID", "a" * 32)
    monkeypatch.setenv("AGORA_APP_CERTIFICATE", "b" * 32)
    get_settings.cache_clear()

    response = client.get(f"/agora/token/{uuid.uuid4()}")
    assert response.status_code == 404

    get_settings.cache_clear()


def test_token_endpoint_errors_without_agora_credentials(monkeypatch):
    monkeypatch.setenv("AGORA_APP_ID", "")
    monkeypatch.setenv("AGORA_APP_CERTIFICATE", "")
    get_settings.cache_clear()

    session_id = _create_session()
    response = client.get(f"/agora/token/{session_id}")

    assert response.status_code == 500

    get_settings.cache_clear()
