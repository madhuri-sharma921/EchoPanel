"""
File-backed store for sessions and their Context Graphs.

Sessions used to live only in a plain in-memory dict, which meant every
backend restart silently wiped every active interview (candidates polling
old session IDs would start getting 404s). This version persists sessions
to a local JSON file on disk so they survive restarts during development,
while keeping the exact same public interface — create_session, get,
get_or_404, all_sessions — so no caller (agora_hooks.py, llm_bridge.py,
sessions.py) needs to change.

Still not a real database: it's a single JSON file, fine for a hackathon
prototype but not for concurrent multi-process deployment. Swap this class
for a Redis- or Postgres-backed implementation later without touching
callers, since everything goes through this interface.
"""
import atexit
import json
import logging
import threading
from pathlib import Path
from uuid import UUID

from app.models.schemas import ContextGraph, InterviewSession

logger = logging.getLogger("echopanel.context_graph_store")

# backend/app/data/sessions.json — created on first save if missing.
_STORE_PATH = Path(__file__).resolve().parent.parent / "data" / "sessions.json"

# How often the background thread flushes in-memory session state to disk.
# Session objects get mutated in place by callers (e.g. graph.add_node(),
# session.latest_scenario = ...) without calling back into this store, so
# periodic autosave is what actually captures those changes — an explicit
# save() only on create_session() would miss everything after that.
_AUTOSAVE_INTERVAL_SECONDS = 2.0


class ContextGraphStore:
    def __init__(self, path: Path = _STORE_PATH) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._sessions: dict[UUID, InterviewSession] = {}
        self._load()

        self._stop_event = threading.Event()
        self._autosave_thread = threading.Thread(
            target=self._autosave_loop, daemon=True
        )
        self._autosave_thread.start()
        atexit.register(self._save)

    # -- persistence ------------------------------------------------------

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            raw = json.loads(self._path.read_text())
        except (json.JSONDecodeError, OSError) as exc:
            logger.warning("Could not read session store at %s: %s", self._path, exc)
            return

        loaded = 0
        for session_data in raw.values():
            try:
                session = InterviewSession.model_validate(session_data)
            except Exception:
                logger.exception("Skipping corrupt session entry on load")
                continue
            self._sessions[session.id] = session
            loaded += 1
        if loaded:
            logger.info("Restored %d session(s) from %s", loaded, self._path)

    def _save(self) -> None:
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            with self._lock:
                data = {
                    str(session_id): session.model_dump(mode="json")
                    for session_id, session in self._sessions.items()
                }
                self._path.write_text(json.dumps(data, indent=2))
        except OSError as exc:
            logger.warning("Could not write session store at %s: %s", self._path, exc)

    def _autosave_loop(self) -> None:
        while not self._stop_event.wait(_AUTOSAVE_INTERVAL_SECONDS):
            self._save()

    # -- public interface (unchanged from the in-memory version) ----------

    def create_session(self, session: InterviewSession) -> InterviewSession:
        session.context_graph = ContextGraph(session_id=session.id)
        self._sessions[session.id] = session
        self._save()
        return session

    def get(self, session_id: UUID) -> InterviewSession | None:
        return self._sessions.get(session_id)

    def get_or_404(self, session_id: UUID) -> InterviewSession:
        session = self.get(session_id)
        if session is None:
            raise KeyError(f"No session found for id {session_id}")
        return session

    def all_sessions(self) -> list[InterviewSession]:
        return list(self._sessions.values())


# Process-wide singleton. FastAPI's dependency below returns this same
# instance so all requests within one server process share state.
_store = ContextGraphStore()


def get_context_graph_store() -> ContextGraphStore:
    return _store