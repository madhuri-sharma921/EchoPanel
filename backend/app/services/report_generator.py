"""
Generates the structured final report: per-competency scores, each verdict
linked back to the exact transcript timestamp and triggering claim.
"""
from app.models.schemas import FinalReport, InterviewSession, VerdictItem


def generate_final_report(session: InterviewSession) -> FinalReport:
    graph = session.context_graph
    verdicts: list[VerdictItem] = []
    contradictions = 0
    vague = 0

    for topic, competence in session.competence_scores.items():
        topic_nodes = graph.nodes_for_topic(topic)
        if not topic_nodes:
            continue
        # Anchor the verdict to the most recent, most-corroborated claim.
        anchor = max(topic_nodes, key=lambda n: n.transcript_timestamp_ms)
        contradictions += sum(1 for n in topic_nodes if n.contradicts)
        vague += sum(1 for n in topic_nodes if n.is_vague)

        verdicts.append(
            VerdictItem(
                competency=topic,
                score=competence.score,
                verdict=_verdict_text(competence.score),
                supporting_claim_id=anchor.id,
                transcript_timestamp_ms=anchor.transcript_timestamp_ms,
            )
        )

    return FinalReport(
        session_id=session.id,
        candidate_name=session.candidate_name,
        per_competency=verdicts,
        contradictions_flagged=contradictions,
        vague_answers_flagged=vague,
    )


def _verdict_text(score: float) -> str:
    if score >= 0.75:
        return "Strong — consistent, well-evidenced answers"
    if score >= 0.5:
        return "Adequate — some gaps or unchallenged assumptions"
    return "Weak — vague, contradictory, or under-evidenced answers"
