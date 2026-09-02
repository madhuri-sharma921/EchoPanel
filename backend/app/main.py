import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import agora_hooks, llm_bridge, sessions
from app.core.config import get_settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    description=(
        "EchoPanel backend — coordinated multi-persona AI interview panel "
        "built on Agora's Conversational AI Engine."
    ),
    version="0.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allow_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(sessions.router)
app.include_router(agora_hooks.router)
app.include_router(llm_bridge.router)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": settings.app_name}
