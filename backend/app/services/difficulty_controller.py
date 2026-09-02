"""
Difficulty controller: rolling per-topic competence score sets the next
question depth (recall -> applied -> edge_case).
"""
from app.models.schemas import CompetenceScore, InterviewSession, QuestionDepth

_PROMOTE_THRESHOLD = 0.7
_DEMOTE_THRESHOLD = 0.35
_ROLLING_WEIGHT = 0.3  # weight given to the newest observation


def _next_depth(current: QuestionDepth, score: float) -> QuestionDepth:
    order = [QuestionDepth.RECALL, QuestionDepth.APPLIED, QuestionDepth.EDGE_CASE]
    idx = order.index(current)
    if score >= _PROMOTE_THRESHOLD and idx < len(order) - 1:
        return order[idx + 1]
    if score <= _DEMOTE_THRESHOLD and idx > 0:
        return order[idx - 1]
    return current


def update_competence(
    session: InterviewSession, topic: str, observed_score: float
) -> CompetenceScore:
    existing = session.competence_scores.get(topic)
    if existing is None:
        rolling = observed_score
        sample_count = 1
        current_depth = QuestionDepth.RECALL
    else:
        rolling = (
            existing.score * (1 - _ROLLING_WEIGHT) + observed_score * _ROLLING_WEIGHT
        )
        sample_count = existing.sample_count + 1
        current_depth = existing.next_depth

    updated = CompetenceScore(
        topic=topic,
        score=rolling,
        sample_count=sample_count,
        next_depth=_next_depth(current_depth, rolling),
    )
    session.competence_scores[topic] = updated
    return updated
