"""
Turn Arbiter.

Extends Agora's turn-detection (which decides WHEN the floor is free) with
our own logic for WHO speaks next: each active persona computes a live
"interest score" against the shared Context Graph, and the arbiter picks
the highest bidder. This is what lets, e.g., the Product interviewer jump
in one turn later to challenge a claim the Technical interviewer accepted.
"""
from app.models.schemas import ContextGraph, InterestScore, PersonaRole

# Simple keyword-based interest heuristics per persona for the prototype.
# The LLM-backed persona layer (app/personas) can override/refine these
# scores with a real reasoning call before the arbiter makes its pick.
_PERSONA_TOPIC_AFFINITY: dict[PersonaRole, tuple[str, ...]] = {
    PersonaRole.TECHNICAL: ("architecture", "algorithm", "code", "system design", "bug"),
    PersonaRole.PRODUCT_BUSINESS: ("customer", "revenue", "impact", "roadmap", "cost"),
    PersonaRole.BEHAVIOURAL: ("conflict", "team", "leadership", "failure", "decision"),
    PersonaRole.CUSTOMER: ("user", "experience", "support", "complaint", "usability"),
    PersonaRole.HIRING_MANAGER: ("ownership", "tradeoff", "priority", "ambiguity", "seniority"),
}


def compute_interest_scores(
    active_personas: list[PersonaRole],
    latest_topic: str,
    graph: ContextGraph,
) -> list[InterestScore]:
    scores: list[InterestScore] = []
    topic_lower = latest_topic.lower()

    # Distinct roles that have weighed in anywhere in the session so far —
    # the signal the Hiring Manager synthesizes across, rather than one topic.
    distinct_raisers = {n.raised_by for n in graph.nodes}

    for persona in active_personas:
        if persona == PersonaRole.HIRING_MANAGER:
            scores.append(_hiring_manager_interest(distinct_raisers, graph))
            continue

        affinity_terms = _PERSONA_TOPIC_AFFINITY.get(persona, ())
        base = 0.2
        matched = [t for t in affinity_terms if t in topic_lower]
        if matched:
            base += 0.5

        # Boost interest if this persona has an unresolved contradiction
        # opportunity on the topic — i.e. a claim on this topic was
        # accepted without challenge by another persona.
        topic_nodes = graph.nodes_for_topic(latest_topic)
        unchallenged = [
            n for n in topic_nodes if n.raised_by != persona and not n.contradicts
        ]
        if unchallenged:
            base += 0.25

        base = min(base, 1.0)
        reason = (
            f"affinity match on {matched}" if matched else "baseline interest"
        )
        scores.append(InterestScore(persona=persona, score=base, reason=reason))

    return scores


def _hiring_manager_interest(
    distinct_raisers: set[PersonaRole], graph: ContextGraph
) -> InterestScore:
    """
    The Hiring Manager doesn't chase a single topic's keywords — their
    interest rises once enough of the panel has weighed in that there's
    something to synthesize (contradictions, gaps, or how the candidate
    handled being challenged across roles).
    """
    others_who_spoke = distinct_raisers - {PersonaRole.HIRING_MANAGER}
    contested_nodes = [n for n in graph.nodes if n.contradicts]

    base = 0.15 + 0.15 * len(others_who_spoke)
    if contested_nodes:
        base += 0.3

    base = min(base, 1.0)
    reason = (
        f"{len(others_who_spoke)} other personas have weighed in"
        + (", contested claims to synthesize" if contested_nodes else "")
    )
    return InterestScore(persona=PersonaRole.HIRING_MANAGER, score=base, reason=reason)


def pick_next_persona(scores: list[InterestScore]) -> InterestScore:
    if not scores:
        raise ValueError("No active personas to arbitrate between")
    return max(scores, key=lambda s: s.score)
