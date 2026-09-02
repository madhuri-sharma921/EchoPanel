from uuid import uuid4

from app.models.schemas import ClaimNode, ContextGraph, InterviewSession, PersonaRole
from app.services.contradiction_detector import process_new_claim
from app.services.difficulty_controller import update_competence
from app.services.turn_arbiter import compute_interest_scores, pick_next_persona


def _graph() -> ContextGraph:
    return ContextGraph(session_id=uuid4())


def test_vague_claim_flagged_below_threshold():
    graph = _graph()
    claim = ClaimNode(
        topic="architecture",
        claim="It scales fine, I guess.",
        confidence=0.2,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )
    result = process_new_claim(claim, graph)
    assert result.is_vague is True


def test_contradiction_detected_on_opposite_polarity_same_topic():
    graph = _graph()
    first = ClaimNode(
        topic="architecture",
        claim="The service is stateless.",
        confidence=0.9,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )
    graph.add_node(process_new_claim(first, graph))

    second = ClaimNode(
        topic="architecture",
        claim="The service is not stateless, it keeps session state.",
        confidence=0.9,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=2000,
    )
    result = process_new_claim(second, graph)
    assert len(result.contradicts) == 1


def test_turn_arbiter_picks_highest_interest_persona():
    graph = _graph()
    active = [PersonaRole.TECHNICAL, PersonaRole.PRODUCT_BUSINESS]
    scores = compute_interest_scores(active, "customer revenue impact", graph)
    winner = pick_next_persona(scores)
    assert winner.persona == PersonaRole.PRODUCT_BUSINESS


def test_competence_promotes_depth_on_high_score():
    session = InterviewSession(
        candidate_name="Test Candidate",
        active_personas=[PersonaRole.TECHNICAL],
    )
    session.context_graph = _graph()
    for _ in range(4):
        update_competence(session, topic="architecture", observed_score=0.95)
    result = session.competence_scores["architecture"]
    assert result.next_depth.value in ("applied", "edge_case")


def test_example_scenario_product_challenges_unchallenged_technical_claim():
    """
    PS11's example scenario: a candidate gives a technically correct
    solution but omits customer impact. The technical interviewer accepts
    the implementation; the product interviewer must independently
    challenge the candidate on business implications one turn later —
    proving the panel coordinates via the shared graph rather than acting
    as a single generic bot.
    """
    graph = _graph()
    active = [PersonaRole.TECHNICAL, PersonaRole.PRODUCT_BUSINESS, PersonaRole.HIRING_MANAGER]

    # Candidate answers a technical question; technical interviewer accepts
    # it (high confidence, no contradiction) without raising customer impact.
    technical_claim = ClaimNode(
        topic="checkout service redesign",
        claim="We moved the checkout service to an event-driven architecture.",
        confidence=0.9,
        raised_by=PersonaRole.TECHNICAL,
        transcript_timestamp_ms=1000,
    )
    graph.add_node(process_new_claim(technical_claim, graph))

    # Turn Arbiter must now favor the Product interviewer to independently
    # challenge business/customer impact on the same topic.
    scores = compute_interest_scores(
        active_personas=active,
        latest_topic="checkout service redesign",
        graph=graph,
    )
    winner = pick_next_persona(scores)

    assert winner.persona == PersonaRole.PRODUCT_BUSINESS
    product_score = next(s for s in scores if s.persona == PersonaRole.PRODUCT_BUSINESS)
    assert product_score.score > 0.4  # unchallenged-claim boost applied


def test_hiring_manager_interest_rises_as_more_personas_weigh_in():
    graph = _graph()

    solo_scores = compute_interest_scores(
        active_personas=[PersonaRole.HIRING_MANAGER],
        latest_topic="checkout service redesign",
        graph=graph,
    )
    solo_score = solo_scores[0].score

    graph.add_node(
        process_new_claim(
            ClaimNode(
                topic="checkout service redesign",
                claim="We moved to an event-driven architecture.",
                confidence=0.9,
                raised_by=PersonaRole.TECHNICAL,
                transcript_timestamp_ms=1000,
            ),
            graph,
        )
    )
    graph.add_node(
        process_new_claim(
            ClaimNode(
                topic="checkout service redesign",
                claim="This did not reduce checkout abandonment.",
                confidence=0.8,
                raised_by=PersonaRole.PRODUCT_BUSINESS,
                transcript_timestamp_ms=2000,
            ),
            graph,
        )
    )

    later_scores = compute_interest_scores(
        active_personas=[PersonaRole.HIRING_MANAGER],
        latest_topic="checkout service redesign",
        graph=graph,
    )
    later_score = later_scores[0].score

    assert later_score > solo_score
