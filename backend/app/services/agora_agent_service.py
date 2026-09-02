"""
Starts and stops Agora Conversational AI agents for a session's voice
channel. The agent is the "AI interviewer" that actually joins the RTC
channel, runs ASR + TTS, and calls our own /v1/chat/completions bridge
(see llm_bridge.py) instead of a raw LLM provider — that bridge is what
lets Agora's engine drive our existing persona / Context Graph / Turn
Arbiter logic instead of a single undifferentiated chatbot.
"""
import base64
import time

import httpx

from app.core.config import get_settings
from agora_token_builder import RtcTokenBuilder

AGORA_API_BASE = "https://api.agora.io/api/conversational-ai-agent/v2/projects"

# Same publisher role used for the client-side RTC token in agora_token_service.
ROLE_PUBLISHER = 1
AGENT_TOKEN_EXPIRY_SECONDS = 86400  # Agora's documented max for these tokens.


def _basic_auth_header() -> str:
    settings = get_settings()
    if not settings.agora_customer_key or not settings.agora_customer_secret:
        raise ValueError(
            "AGORA_CUSTOMER_KEY and AGORA_CUSTOMER_SECRET must be set in "
            ".env before starting a Conversational AI agent."
        )
    raw = f"{settings.agora_customer_key}:{settings.agora_customer_secret}"
    encoded = base64.b64encode(raw.encode()).decode()
    return f"Basic {encoded}"


def _build_agent_token(channel_name: str, agent_uid: str) -> str:
    """
    Agent join requires its own RTC token, scoped to the agent's own uid
    (distinct from the candidate's uid) in the same channel.
    """
    settings = get_settings()
    expire_at = int(time.time()) + AGENT_TOKEN_EXPIRY_SECONDS
    return RtcTokenBuilder.buildTokenWithUid(
        settings.agora_app_id,
        settings.agora_app_certificate,
        channel_name,
        int(agent_uid),
        ROLE_PUBLISHER,
        expire_at,
    )


def start_agent(session_id: str, persona_greeting: str) -> dict:
    """
    Starts a Conversational AI agent in the given session's channel. The
    LLM is pointed at our own backend's OpenAI-compatible bridge endpoint
    so Agora's engine drives our persona logic instead of calling OpenAI
    (or any other provider) directly.
    """
    settings = get_settings()
    if not settings.public_backend_url:
        raise ValueError(
            "PUBLIC_BACKEND_URL must be set in .env — Agora's cloud agent "
            "needs a publicly reachable URL to call back into this "
            "backend's /v1/chat/completions bridge. A LAN address like "
            "192.168.x.x will not work; use a tunnel (ngrok, Cloudflare "
            "Tunnel) during development."
        )

    channel_name = session_id
    agent_uid = "9999"  # Fixed uid for the agent, distinct from candidate uid=0.
    agent_token = _build_agent_token(channel_name, agent_uid)

    payload = {
        "name": f"echopanel-{session_id}",
        "properties": {
            "channel": channel_name,
            "token": agent_token,
            "agent_rtc_uid": agent_uid,
            "remote_rtc_uids": ["0"],
            "enable_string_uid": False,
            "idle_timeout": 600,
            "advanced_features": {
                # Required for the client-side toolkit to receive live
                # transcript + agent-state events over Signaling (RTM).
                "enable_rtm": True,
            },
            "parameters": {
                "data_channel": "rtm",
            },
            "asr": {
                "credential_mode": "managed",
                "vendor": "deepgram",
                "params": {
                    "url": "wss://api.deepgram.com/v1/listen",
                    "model": "nova-3",
                    "language": "en-US",
                },
            },
            "llm": {
                # Points at OUR backend, not a third-party LLM vendor — no
                # credential_mode/vendor here; Agora just POSTs to this URL
                # in OpenAI's chat-completions format (see llm_bridge.py).
                "url": f"{settings.public_backend_url.rstrip('/')}/v1/chat/completions/{session_id}",
                "api_key": settings.agora_webhook_secret or "unused",
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
                "params": {"model": "gpt-4o"},
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

    response = httpx.post(
        f"{AGORA_API_BASE}/{settings.agora_app_id}/join",
        headers={
            "Authorization": _basic_auth_header(),
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=15.0,
    )
    if response.status_code >= 400:
        raise RuntimeError(
            f"Agora join API returned {response.status_code}: {response.text}"
        )
    return response.json()


def stop_agent(agent_id: str) -> None:
    settings = get_settings()
    response = httpx.post(
        f"{AGORA_API_BASE}/{settings.agora_app_id}/agents/{agent_id}/leave",
        headers={"Authorization": _basic_auth_header()},
        timeout=15.0,
    )
    response.raise_for_status()
