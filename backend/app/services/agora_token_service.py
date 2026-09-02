"""
Generates Agora RTC and RTM tokens.

RTC tokens are required because the Agora project is configured in Secured
mode (App ID + App Certificate) — clients cannot join a voice channel with
an empty token in that mode. RTM tokens are required separately for the
Signaling connection the Conversational AI Engine client toolkit uses to
deliver live transcript and agent-state events. Both are short-lived and
must be generated server-side, since the App Certificate must never be
shipped in the client app.
"""
import time

from agora_token_builder import RtcTokenBuilder, RtmTokenBuilder

from app.core.config import get_settings

# Agora role constant: 1 = Publisher (can send + receive audio/video).
ROLE_PUBLISHER = 1
# RTM role constant: 1 = Rtm_User (standard user role).
RTM_ROLE_USER = 1

# How long a token stays valid after issuance. Long enough for a full
# interview session; regenerate per-session rather than reusing tokens.
TOKEN_EXPIRY_SECONDS = 3600


def generate_rtc_token(channel_name: str, uid: int = 0) -> dict:
    """
    Build a signed RTC token for the given channel.

    uid=0 lets Agora assign any uid to the joining client — fine for a
    single-candidate-per-channel interview session. Pass a specific uid if
    you need to pin identities (e.g. multiple named participants).
    """
    settings = get_settings()
    if not settings.agora_app_id or not settings.agora_app_certificate:
        raise ValueError(
            "AGORA_APP_ID and AGORA_APP_CERTIFICATE must be set in .env "
            "before tokens can be generated."
        )

    expire_at = int(time.time()) + TOKEN_EXPIRY_SECONDS

    token = RtcTokenBuilder.buildTokenWithUid(
        settings.agora_app_id,
        settings.agora_app_certificate,
        channel_name,
        uid,
        ROLE_PUBLISHER,
        expire_at,
    )

    rtm_user_account = f"candidate-{channel_name}"
    rtm_token = RtmTokenBuilder.buildToken(
        settings.agora_app_id,
        settings.agora_app_certificate,
        rtm_user_account,
        RTM_ROLE_USER,
        expire_at,
    )

    return {
        "token": token,
        "app_id": settings.agora_app_id,
        "channel_name": channel_name,
        "uid": uid,
        "expires_at": expire_at,
        "rtm_token": rtm_token,
        "rtm_user_account": rtm_user_account,
    }
