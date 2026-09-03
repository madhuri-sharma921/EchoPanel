"""
Starts and stops Agora Conversational AI agents for a session's voice
channel. The agent acts as the interviewer by joining the RTC
channel, managing ASR + TTS, and querying the backend LLM bridge.
"""
import base64
import logging
import os
import time
from typing import Any, Dict

import httpx
from agora_token_builder import RtcTokenBuilder

logger = logging.getLogger("echopanel.agora_agent")

AGORA_API_BASE = "https://api.agora.io/api/conversational-ai-agent/v2/projects"

# Fixed deterministic UIDs to guarantee audio subscription
CANDIDATE_UID = "1001"
AGENT_UID = "9999"

ROLE_PUBLISHER = 1
AGENT_TOKEN_EXPIRY_SECONDS = 86400  # 24 hours


def _get_env(key: str) -> str:
    """Helper to retrieve and sanitize environment variables."""
    val = os.getenv(key, "")
    return val.strip().strip('"').strip("'")


def _basic_auth_header() -> str:
    customer_key = _get_env("AGORA_CUSTOMER_KEY") or _get_env("AGORA_CUSTOMER_ID")
    customer_secret = _get_env("AGORA_CUSTOMER_SECRET")

    if not customer_key or not customer_secret:
        raise ValueError(
            "AGORA_CUSTOMER_KEY and AGORA_CUSTOMER_SECRET must be set in .env."
        )

    raw_credentials = f"{customer_key}:{customer_secret}"
    encoded = base64.b64encode(raw_credentials.encode("utf-8")).decode("utf-8")
    return f"Basic {encoded}"


def _build_agent_token(channel_name: str, agent_uid: str) -> str:
    """Builds an RTC join token for the agent."""
    app_id = _get_env("AGORA_APP_ID")
    app_cert = _get_env("AGORA_APP_CERTIFICATE")

    if not app_id or not app_cert:
        raise ValueError("AGORA_APP_ID and AGORA_APP_CERTIFICATE must be set in .env")

    expire_at = int(time.time()) + AGENT_TOKEN_EXPIRY_SECONDS
    return RtcTokenBuilder.buildTokenWithUid(
        app_id,
        app_cert,
        channel_name,
        int(agent_uid),
        ROLE_PUBLISHER,
        expire_at,
    )


def start_agent(session_id: str, persona_greeting: str) -> dict:
    """Starts the Agora Conversational AI agent with backoff retries."""
    app_id = _get_env("AGORA_APP_ID")
    public_url = _get_env("PUBLIC_BACKEND_URL")
    webhook_secret = _get_env("AGORA_WEBHOOK_SECRET")
    deepgram_key = _get_env("DEEPGRAM_API_KEY")

    if not app_id:
        raise ValueError("AGORA_APP_ID must be set in .env")
    if not public_url:
        raise ValueError("PUBLIC_BACKEND_URL must be set in .env")
    if not deepgram_key:
        raise ValueError("DEEPGRAM_API_KEY must be set in .env")

    clean_public_url = public_url.rstrip("/")
    channel_name = str(session_id)
    agent_token = _build_agent_token(channel_name, AGENT_UID)

    # Custom Deepgram ASR payload without rejected credential_mode
    asr_config: Dict[str, Any] = {
        "vendor": "deepgram",
        "params": {
            "api_key": deepgram_key,
            "url": "wss://api.deepgram.com/v1/listen",
            "model": "nova-3",
            "language": "en-US",
        },
    }

    payload: Dict[str, Any] = {
        "name": f"echopanel-{session_id[:8]}",
        "properties": {
            "channel": channel_name,
            "token": agent_token,
            "agent_rtc_uid": AGENT_UID,
            "remote_rtc_uids": [CANDIDATE_UID],
            "enable_string_uid": False,
            "idle_timeout": 600,
            "advanced_features": {
                "enable_rtm": True,
            },
            "parameters": {
                "data_channel": "rtm",
            },
            "asr": asr_config,
            "llm": {
                "url": f"{clean_public_url}/v1/chat/completions/{session_id}",
                "api_key": webhook_secret or "unused",
                "system_messages": [
                    {
                        "role": "system",
                        "content": (
                            "You are one interviewer on a coordinated AI "
                            "interview panel. Respond only with what you "
                            "would say next; do not narrate stage "
                            "directions or mention other personas."
                        ),
                    }
                ],
                "greeting_message": persona_greeting,
                "failure_message": (
                    "Sorry, could you repeat that? I didn't quite catch it."
                ),
                "max_history": 20,
                "params": {
                    "model": _get_env("OPENAI_MODEL") or "llama-3.3-70b-versatile"
                },
            },
            "tts": {
                "credential_mode": "managed",
                "vendor": "minimax",
                "params": {
                    "url": "wss://api.minimax.io/ws/v1/t2a_v2",
                    "model": "speech-2.6-turbo",
                    "voice_setting": {"voice_id": "English_captivating_female1"},
                },
            },
        },
    }

    url = f"{AGORA_API_BASE}/{app_id}/join"
    headers = {
        "Authorization": _basic_auth_header(),
        "Content-Type": "application/json",
    }

    max_retries = 3
    backoff = 1.5

    for attempt in range(1, max_retries + 1):
        try:
            logger.info(
                "Starting Agora agent on project %s for channel %s (attempt %d/%d)...",
                app_id,
                channel_name,
                attempt,
                max_retries,
            )
            with httpx.Client(timeout=25.0) as client:
                response = client.post(url, headers=headers, json=payload)

            if response.status_code in (200, 201):
                logger.info("Agora agent joined session %s successfully", session_id)
                return response.json()

            if response.status_code >= 500 and attempt < max_retries:
                logger.warning(
                    "Agora join returned %d: %s. Retrying in %.1fs...",
                    response.status_code,
                    response.text,
                    backoff,
                )
                time.sleep(backoff)
                backoff *= 2
                continue

            logger.error(
                "Agora join API failed: status=%s, url=%s, body=%s",
                response.status_code,
                url,
                response.text,
            )
            raise RuntimeError(
                f"Agora join API returned {response.status_code}: {response.text}"
            )

        except httpx.RequestError as exc:
            logger.warning("Network failure contacting Agora API: %s", exc)
            if attempt == max_retries:
                raise RuntimeError(f"Network error calling Agora: {exc}") from exc
            time.sleep(backoff)
            backoff *= 2

    raise RuntimeError("Agora agent start failed after retries.")


def stop_agent(agent_id: str) -> None:
    """Stops an active Agora agent."""
    app_id = _get_env("AGORA_APP_ID")
    if not app_id:
        raise ValueError("AGORA_APP_ID must be set in .env")

    url = f"{AGORA_API_BASE}/{app_id}/agents/{agent_id}/leave"
    headers = {"Authorization": _basic_auth_header()}

    logger.info("Stopping Agora agent %s on project %s", agent_id, app_id)
    with httpx.Client(timeout=15.0) as client:
        response = client.post(url, headers=headers)
        response.raise_for_status()