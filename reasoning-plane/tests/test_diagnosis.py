"""Mock-provider diagnosis produces schema-valid, appropriate plans."""

from __future__ import annotations

from app.schemas.plan import ActionType, BlastRadius, RemediationPlan
from tests.conftest import make_service, request_for


def test_oom_yields_restart(oom_scenario) -> None:
    plan, cost = make_service(oom_scenario).diagnose(
        request_for("payment-svc", "payment-svc OOM killed"))
    assert isinstance(plan, RemediationPlan)
    assert plan.actions[0].type == ActionType.RESTART_CONTAINER
    assert "mem" in plan.hypothesis.lower() or "oom" in plan.hypothesis.lower()
    assert cost["hops"] >= 1
    assert plan.evidence  # every plan cites the observations it used


def test_healthy_yields_noop(healthy_scenario) -> None:
    plan, _ = make_service(healthy_scenario).diagnose(
        request_for("payment-svc", "healthy system check"))
    assert plan.is_noop()
    assert plan.escalate is False


def test_crashloop_with_deploy_rolls_back(crashloop_with_deploy) -> None:
    plan, _ = make_service(crashloop_with_deploy).diagnose(
        request_for("inventory-svc", "inventory-svc crash loop"))
    assert plan.actions[0].type == ActionType.ROLLBACK_IMAGE
    assert plan.actions[0].parameters.get("toDigest")
    assert plan.estimated_blast_radius == BlastRadius.HIGH


def test_plan_serialises_camelcase(oom_scenario) -> None:
    plan, _ = make_service(oom_scenario).diagnose(request_for("payment-svc", "oom"))
    wire = plan.model_dump(by_alias=True)
    # The Java control plane expects camelCase keys.
    assert "incidentId" in wire
    assert "estimatedBlastRadius" in wire
    assert "targetRef" in wire["actions"][0]
