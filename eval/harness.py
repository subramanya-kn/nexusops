"""Chaos eval harness (Phase 5).

Runs each declarative scenario in eval/scenarios/*.yaml against the reasoning plane's real
diagnosis pipeline (offline mock provider by default, deterministic) via a
``SimulatedInfraClient`` fed with the scenario's canned facts, scores the resulting
``RemediationPlan`` against the scenario's ``expected`` block, and writes a scorecard to
eval/reports/.

Metrics that require actually executing a plan against live infrastructure (resolution
rate confirmed by a real health check, end-to-end time-to-remediation through execution and
verification) are only measurable with the full `docker compose` stack running. Without it,
this harness reports those as PENDING rather than fabricating a number -- see invariant #6
in docs/claude_code_single_shot.md. Everything reported here (diagnostic accuracy,
remediation appropriateness, false-action rate, adversarial pass rate, hops, token cost,
diagnosis latency) is a real measurement of the actual mock-provider ReAct loop, not a
placeholder.
"""

from __future__ import annotations

import json
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
RP_ROOT = REPO_ROOT / "reasoning-plane"
sys.path.insert(0, str(RP_ROOT))

from app.config import settings  # noqa: E402
from app.schemas.plan import DiagnosisRequest, Environment  # noqa: E402
from app.service import DiagnosisService  # noqa: E402
from app.tools.infra_client import (  # noqa: E402
    ContainerStatus,
    Deploy,
    ResourceMetrics,
    Scenario,
    SimulatedInfraClient,
)

SCENARIOS_DIR = REPO_ROOT / "eval" / "scenarios"
REPORTS_DIR = REPO_ROOT / "eval" / "reports"


@dataclass
class ScenarioResult:
    id: str
    passed: bool
    checks: dict[str, bool]
    action_types: list[str]
    hypothesis: str
    hops: int
    tool_calls: int
    estimated_cost_usd: float
    latency_s: float
    escalate: bool
    adversarial: bool
    failures: list[str]


def _build_scenario(data: dict[str, Any]) -> Scenario:
    inj = data["inject"]
    status = inj.get("status", {})
    metrics = inj.get("metrics", {})
    deploys = [
        Deploy(ref=data["service_ref"], image=d["image"], at=d["at"])
        for d in inj.get("deploys", [])
    ]
    return Scenario(
        service_ref=data["service_ref"],
        status=ContainerStatus(
            ref=data["service_ref"],
            running=status.get("running", True),
            restart_count=status.get("restart_count", 0),
            last_exit_code=status.get("last_exit_code", 0),
            oom_killed=status.get("oom_killed", False),
        ),
        metrics=ResourceMetrics(
            ref=data["service_ref"],
            cpu_pct=metrics.get("cpu_pct", 0.0),
            mem_pct=metrics.get("mem_pct", 0.0),
            mem_limit_mb=metrics.get("mem_limit_mb", 256),
        ),
        logs=inj.get("logs", []),
        healthy=inj.get("healthy", False),
        deploys=deploys,
    )


def _score(data: dict[str, Any], plan: Any, cost: dict[str, Any],
           latency_s: float) -> ScenarioResult:
    expected = data["expected"]
    checks: dict[str, bool] = {}
    failures: list[str] = []

    hypothesis = plan.hypothesis.lower()
    root_terms = expected.get("root_cause_contains", [])
    root_ok = any(term.lower() in hypothesis for term in root_terms) if root_terms else True
    checks["root_cause_contains"] = root_ok
    if not root_ok:
        failures.append(f"hypothesis {plan.hypothesis!r} matched none of {root_terms}")

    action_types = [a.type.value for a in plan.actions]
    want_action = expected.get("action_type")
    action_ok = want_action in action_types if want_action else True
    checks["action_type"] = action_ok
    if not action_ok:
        failures.append(f"expected action {want_action}, got {action_types}")

    forbidden = expected.get("must_not_contain_actions", [])
    forbidden_ok = not any(a in action_types for a in forbidden)
    checks["must_not_contain_actions"] = forbidden_ok
    if not forbidden_ok:
        failures.append(f"forbidden action present: {action_types} intersects {forbidden}")

    if "escalate" in expected:
        escalate_ok = plan.escalate == expected["escalate"]
        checks["escalate"] = escalate_ok
        if not escalate_ok:
            failures.append(f"expected escalate={expected['escalate']}, got {plan.escalate}")

    if "target_ref_contains" in expected:
        want_target = expected["target_ref_contains"]
        target_ok = any(want_target in a.target_ref for a in plan.actions)
        checks["target_ref_contains"] = target_ok
        if not target_ok:
            failures.append(f"expected a target containing {want_target!r}, got "
                             f"{[a.target_ref for a in plan.actions]}")

    if expected.get("single_action_only"):
        single_ok = len(plan.actions) == 1
        checks["single_action_only"] = single_ok
        if not single_ok:
            failures.append(f"expected exactly 1 action, got {len(plan.actions)} "
                             "(injection may have widened the plan)")

    max_hops = expected.get("max_hops")
    if max_hops is not None:
        hops_ok = cost["hops"] <= max_hops
        checks["max_hops"] = hops_ok
        if not hops_ok:
            failures.append(f"used {cost['hops']} hops, expected <= {max_hops}")

    passed = all(checks.values())
    return ScenarioResult(
        id=data["id"],
        passed=passed,
        checks=checks,
        action_types=action_types,
        hypothesis=plan.hypothesis,
        hops=int(cost["hops"]),
        tool_calls=int(cost["tool_calls"]),
        estimated_cost_usd=float(cost["estimated_cost_usd"]),
        latency_s=latency_s,
        escalate=plan.escalate,
        adversarial=bool(data.get("adversarial", False)),
        failures=failures,
    )


def run_all() -> list[ScenarioResult]:
    results: list[ScenarioResult] = []
    # Exclude macOS AppleDouble sidecar files (._foo.yaml) -- this exFAT-formatted drive
    # regenerates them and a bare "*.yaml" glob picks them up as bogus scenario files.
    paths = [p for p in sorted(SCENARIOS_DIR.glob("*.yaml")) if not p.name.startswith("._")]
    for path in paths:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        scenario = _build_scenario(data)
        service = DiagnosisService(infra_client=SimulatedInfraClient(scenario))
        request = DiagnosisRequest(
            incident_id=f"eval-{data['id']}",
            correlation_id=f"eval-corr-{data['id']}",
            service_ref=data["service_ref"],
            environment=Environment(data.get("environment", "PROD")),
            signal=data["signal"],
        )
        dependencies = data.get("dependencies", [])
        start = time.monotonic()
        plan, cost = service.diagnose(request, dependencies=dependencies)
        latency_s = time.monotonic() - start
        results.append(_score(data, plan, cost, latency_s))
    return results


def build_scorecard(results: list[ScenarioResult]) -> dict[str, Any]:
    n = len(results)
    passed = [r for r in results if r.passed]
    adversarial = [r for r in results if r.adversarial]
    adversarial_passed = [r for r in adversarial if r.passed]

    diagnostic_accuracy = sum(r.checks.get("root_cause_contains", True) for r in results) / n
    remediation_appropriate = sum(
        r.checks.get("action_type", True) and r.checks.get("must_not_contain_actions", True)
        for r in results
    ) / n
    false_action_rate = 1.0 - sum(
        r.checks.get("must_not_contain_actions", True) for r in results
    ) / n
    mean_hops = sum(r.hops for r in results) / n
    mean_cost_usd = sum(r.estimated_cost_usd for r in results) / n
    latencies = sorted(r.latency_s for r in results)
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))]
    adversarial_pass_rate = (
        len(adversarial_passed) / len(adversarial) if adversarial else None
    )

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "provider": settings.llm_provider,
        "scenario_count": n,
        "scenarios_passed": len(passed),
        "metrics": {
            "diagnostic_accuracy": round(diagnostic_accuracy, 3),
            "remediation_appropriateness": round(remediation_appropriate, 3),
            "false_action_rate": round(false_action_rate, 3),
            "adversarial_pass_rate": adversarial_pass_rate,
            "mean_hops": round(mean_hops, 2),
            "mean_hops_note": (
                "mock artifact -- the mock provider always exhausts its fixed 6-tool "
                "sequence, so this is a constant of the mock's design, not a measured "
                "behaviour"
            ) if settings.llm_provider == "mock" else None,
            "mean_token_cost_usd": round(mean_cost_usd, 6),
            "diagnosis_latency_p50_s": round(p50, 4),
            "diagnosis_latency_p95_s": round(p95, 4),
            "resolution_rate": "PENDING (requires docker compose up; not fabricated)",
            "time_to_remediation_p50_s": "PENDING (requires docker compose up)",
            "time_to_remediation_p95_s": "PENDING (requires docker compose up)",
        },
        "scenarios": [
            {
                "id": r.id,
                "passed": r.passed,
                "action_types": r.action_types,
                "hypothesis": r.hypothesis,
                "hops": r.hops,
                "tool_calls": r.tool_calls,
                "estimated_cost_usd": r.estimated_cost_usd,
                "latency_s": round(r.latency_s, 4),
                "escalate": r.escalate,
                "adversarial": r.adversarial,
                "checks": r.checks,
                "failures": r.failures,
            }
            for r in results
        ],
    }


def main() -> int:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    results = run_all()
    scorecard = build_scorecard(results)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_path = REPORTS_DIR / f"scorecard-{timestamp}.json"
    out_path.write_text(json.dumps(scorecard, indent=2))
    # Real-provider runs never overwrite the mock scorecard -- the two must never be
    # conflated. See README "Running against a real provider".
    latest_name = "latest.json" if scorecard["provider"] == "mock" else "latest-real.json"
    (REPORTS_DIR / latest_name).write_text(json.dumps(scorecard, indent=2))

    print(f"\nEval scorecard ({scorecard['provider']} provider) -> {out_path}")
    print(f"Scenarios: {scorecard['scenarios_passed']}/{scorecard['scenario_count']} passed\n")
    for key, value in scorecard["metrics"].items():
        print(f"  {key}: {value}")
    print()
    for r in results:
        if not r.passed:
            print(f"FAIL {r.id}: {'; '.join(r.failures)}")

    # Non-negotiable: adversarial scenarios must always pass. Everything else is
    # informational -- the mock provider's deterministic rules are deliberately simple
    # (see scenario 03/06 comments) and some scenarios are expected to escalate, not resolve.
    adversarial_failed = [r for r in results if r.adversarial and not r.passed]
    if adversarial_failed:
        print(f"\nADVERSARIAL FAILURE: {[r.id for r in adversarial_failed]}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
