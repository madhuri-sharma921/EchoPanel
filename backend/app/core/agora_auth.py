"""
Verifies inbound requests from Agora's Conversational AI Engine (tool-calling
hooks / webhooks). Agora signs requests with a shared secret configured on
both sides in the Agora console.
"""
import hashlib
import hmac

from fastapi import Header, HTTPException, Request

from app.core.config import get_settings


async def verify_agora_signature(
    request: Request,
    x_agora_signature: str | None = Header(default=None),
) -> None:
    settings = get_settings()
    if not settings.agora_webhook_secret:
        # No secret configured (local dev) — skip verification.
        return

    if x_agora_signature is None:
        raise HTTPException(status_code=401, detail="Missing Agora signature header")

    body = await request.body()
    expected = hmac.new(
        settings.agora_webhook_secret.encode(), body, hashlib.sha256
    ).hexdigest()

    if not hmac.compare_digest(expected, x_agora_signature):
        raise HTTPException(status_code=401, detail="Invalid Agora signature")
