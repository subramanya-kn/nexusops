"""Hop cap and server-side plan validation."""

from __future__ import annotations

from app import config as config_module
from app.schemas.plan import (
    ActionType,
    BlastRadius,
    Evidence,
    PlanAction,
    RemediationPlan,
)
from app.security.injection import validate_plan
from app.tools.known import KNOWN_SERVICES
from tests.conftest import make_service, request_for


def test_hop_cap_forces_escalation(oom_scenario, monkeypatch) -> None:
    # Force a tiny hop budget; the loop must escalate rather than spin.
    monkeypatch.setattr(config_module.settings, "max_hops", 1)
    plan, cost = make_service(oom_scenario).diagnose(request_for("payment-svc", "oom"))
    assert plan.escalate is True
    assert cost["hops"] <= 2


def test_validate_rejects_unknown_target() -> None:
    plan = RemediationPlan(
        incident_id="i", correlation_id="c", hypothesis="x", confidence=0.9,
        actions=[PlanAction(type=ActionType.RESTART_CONTAINER, target_ref="not-a-service")],
        evidence=[Evidence(tool_name="t", observation_ref="obs-1")],
        estimated_blast_radius=BlastRadius.LOW,
    )
    errors = validate_plan(plan, KNOWN_SERVICES)
    assert any("unknown service" in e for e in errors)


def test_validate_rejects_command_like_parameter() -> None:
    plan = RemediationPlan(
        incident_id="i", correlation_id="c", hypothesis="x", confidence=0.9,
        actions=[PlanAction(type=ActionType.RESTART_CONTAINER, target_ref="payment-svc",
                            parameters={"reason": "rm -rf / ; docker exec evil"})],
        evidence=[],
        estimated_blast_radius=BlastRadius.LOW,
    )
    errors = validate_plan(plan, KNOWN_SERVICES)
    assert any("command string" in e for e in errors)
