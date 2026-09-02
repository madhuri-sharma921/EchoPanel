"""
Contradiction and vagueness detection.

Contradiction = a rule check against existing graph nodes on the same topic.
Vagueness = confidence score below a configured threshold.

This runs BEFORE scoring, not after, so the difficulty controller and
persona prompts see flags on the same turn the claim is added.
"""
from app.core.config import get_settings
from app.models.schemas import ClaimNode, ContextGraph


def flag_vague(claim: ClaimNode) -> bool:
    settings = get_settings()
    return claim.confidence < settings.vagueness_confidence_threshold


def find_contradictions(new_claim: ClaimNode, graph: ContextGraph) -> list[ClaimNode]:
    """
    Naive same-topic negation/conflict check for the prototype.

    Production version would replace this with an NLI (natural language
    inference) model call, but the interface — take a new claim and the
    graph, return conflicting prior nodes — stays the same.
    """
    conflicts: list[ClaimNode] = []
    same_topic = graph.nodes_for_topic(new_claim.topic)

    negation_markers = ("not", "never", "no longer", "isn't", "wasn't", "didn't")
    new_is_negated = any(m in new_claim.claim.lower() for m in negation_markers)

    for existing in same_topic:
        existing_is_negated = any(
            m in existing.claim.lower() for m in negation_markers
        )
        # Flag as a contradiction candidate if polarity differs on the same
        # topic — a lightweight heuristic standing in for real NLI.
        if new_is_negated != existing_is_negated:
            conflicts.append(existing)

    return conflicts


def process_new_claim(claim: ClaimNode, graph: ContextGraph) -> ClaimNode:
    """Mutates and returns the claim with vagueness/contradiction flags set."""
    claim.is_vague = flag_vague(claim)
    conflicts = find_contradictions(claim, graph)
    claim.contradicts = [c.id for c in conflicts]
    return claim
