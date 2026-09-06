"""Minimal, dependency-free Prometheus metrics for the reasoning plane.

No extra package: renders the text exposition format by hand. This is deliberately small
-- it exists to feed the "tokens/cost per incident" and "agent hops per incident" Grafana
panels (the two panels the brief calls out that only this service can produce), not to be
a general metrics framework. Thread-safe via a single lock; call volume here is one
diagnosis at a time per worker thread, so contention is a non-issue.
"""

from __future__ import annotations

import threading

from app.schemas.plan import RemediationPlan


def _as_int(value: object) -> int:
    return value if isinstance(value, int) else 0


def _as_float(value: object) -> float:
    return float(value) if isinstance(value, int | float) else 0.0


class _DiagnosisMetrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._diagnoses_total = 0
        self._escalated_total = 0
        self._hops_sum = 0
        self._input_tokens_sum = 0
        self._output_tokens_sum = 0
        self._cost_usd_sum = 0.0
        self._action_counts: dict[str, int] = {}

    def record(self, plan: RemediationPlan, cost: dict[str, object]) -> None:
        with self._lock:
            self._diagnoses_total += 1
            if plan.escalate:
                self._escalated_total += 1
            self._hops_sum += _as_int(cost.get("hops"))
            self._input_tokens_sum += _as_int(cost.get("input_tokens"))
            self._output_tokens_sum += _as_int(cost.get("output_tokens"))
            self._cost_usd_sum += _as_float(cost.get("estimated_cost_usd"))
            for action in plan.actions:
                key = action.type.value
                self._action_counts[key] = self._action_counts.get(key, 0) + 1

    def render_prometheus(self) -> str:
        with self._lock:
            total = self._diagnoses_total
            lines = [
                "# HELP nexusops_diagnoses_total Total diagnoses completed.",
                "# TYPE nexusops_diagnoses_total counter",
                f"nexusops_diagnoses_total {total}",
                "# HELP nexusops_diagnoses_escalated_total Diagnoses that escalated "
                "rather than proposing an action.",
                "# TYPE nexusops_diagnoses_escalated_total counter",
                f"nexusops_diagnoses_escalated_total {self._escalated_total}",
                "# HELP nexusops_diagnosis_hops_sum Sum of ReAct hops across all diagnoses.",
                "# TYPE nexusops_diagnosis_hops_sum counter",
                f"nexusops_diagnosis_hops_sum {self._hops_sum}",
                "# HELP nexusops_diagnosis_tokens_sum Sum of tokens by kind across all diagnoses.",
                "# TYPE nexusops_diagnosis_tokens_sum counter",
                f'nexusops_diagnosis_tokens_sum{{kind="input"}} {self._input_tokens_sum}',
                f'nexusops_diagnosis_tokens_sum{{kind="output"}} {self._output_tokens_sum}',
                "# HELP nexusops_diagnosis_cost_usd_sum Sum of estimated USD cost across "
                "all diagnoses.",
                "# TYPE nexusops_diagnosis_cost_usd_sum counter",
                f"nexusops_diagnosis_cost_usd_sum {self._cost_usd_sum}",
                "# HELP nexusops_diagnosis_actions_total Proposed actions by type.",
                "# TYPE nexusops_diagnosis_actions_total counter",
            ]
            for action_type, count in sorted(self._action_counts.items()):
                lines.append(
                    f'nexusops_diagnosis_actions_total{{action_type="{action_type}"}} {count}'
                )
            return "\n".join(lines) + "\n"


metrics = _DiagnosisMetrics()
