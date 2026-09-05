from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",  # Allows keys like DEEPGRAM_API_KEY in .env without throwing validation errors
    )

    app_name: str = "EchoPanel"
    environment: str = "development"

    # OpenAI — persona reasoning layer. base_url can point at any
    # OpenAI-compatible provider (e.g. Groq: https://api.groq.com/openai/v1)
    # if you need a free/alternate provider — no other code changes needed.
    openai_api_key: str = ""
    openai_model: str = "gpt-4o"
    openai_base_url: str = ""

    # Deepgram ASR
    deepgram_api_key: str = ""

    # Agora Conversational AI Engine
    agora_app_id: str = ""
    agora_app_certificate: str = ""
    agora_customer_key: str = ""
    agora_customer_secret: str = ""
    # Shared secret Agora's tool-calling / webhook requests must present
    agora_webhook_secret: str = ""
    # Publicly reachable base URL for THIS backend, so Agora's cloud agent
    # can call back into our /v1/chat/completions bridge. Cannot be a LAN
    # address (e.g. 192.168.x.x) — Agora's servers can't reach your local
    # network. Use a tunnel (ngrok, Cloudflare Tunnel) during development.
    public_backend_url: str = ""

    # Vagueness threshold: confidence below this flags an answer as vague
    vagueness_confidence_threshold: float = 0.4

    # Cheating-detection thresholds (see services/cheating_detector.py).
    # Accumulated signal-strength totals at/above these raise a CheatFlag
    # of the corresponding severity for a session.
    cheat_flag_low_threshold: float = 0.4
    cheat_flag_medium_threshold: float = 0.8
    cheat_flag_high_threshold: float = 1.4
    # An answer below this many characters-per-second-of-silence-preceding
    # is considered implausibly fast for its length (see _flag_answer_speed).
    cheat_min_seconds_per_100_chars: float = 2.0

    cors_allow_origins: list[str] = ["*"]


@lru_cache
def get_settings() -> Settings:
    return Settings()