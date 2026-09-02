"""
In-memory store for sessions and their Context Graphs.

Kept deliberately simple for the hackathon prototype. Swap this class for a
Redis- or Postgres-backed implementation without touching callers, since
everything goes through this interface.
"""
from uuid import UUID

from app.models.schemas import ContextGraph, InterviewSession


class ContextGraphStore:
    def __init__(self) -> None:
        self._sessions: dict[UUID, InterviewSession] = {}

    def create_session(self, session: InterviewSession) -> InterviewSession:
        session.context_graph = ContextGraph(session_id=session.id)
        self._sessions[session.id] = session
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
