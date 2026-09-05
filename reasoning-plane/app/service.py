"""Diagnosis service: wires the toolbox, provider, and graph for one diagnosis run."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any

from app.config import settings
from app.graphs.diagnostic_graph import build_graph
from app.llm.factory import build_provider
from app.observability.cost import CostMeter
from app.schemas.plan import DiagnosisRequest, RemediationPlan
from app.tools.infra_client import HttpInfraClient, InfraClient
from app.tools.known import KNOWN_SERVICES
from app.tools.registry import Toolbox
from app.tools.runbooks import RunbookIndex

_RUNBOOK_DIR = Path(__file__).resolve().parent.parent / "runbooks"
_RUNBOOKS = RunbookIndex.from_dir(_RUNBOOK_DIR)


class DiagnosisService:
    """Runs one diagnosis end to end and returns the plan plus cost accounting."""

    def __init__(
        self,
        infra_client: InfraClient | None = None,
        known_targets: Iterable[str] = KNOWN_SERVICES,
        checkpointer: Any | None = None,
    ) -> None:
        self._infra_client = infra_client
        self._known_targets = list(known_targets)
        self._checkpointer = checkpointer

    def _client(self) -> InfraClient:
        return self._infra_client or HttpInfraClient()

    def diagnose(
        self,
        request: DiagnosisRequest,
        dependencies: Sequence[str] = (),
    ) -> tuple[RemediationPlan, dict[str, object]]:
        cost = CostMeter()
        provider = build_provider(cost)
        toolbox = Toolbox(self._client(), _RUNBOOKS, cost, max_log_lines=settings.max_log_lines)
        graph = build_graph(
            toolbox=toolbox,
            provider=provider,
            known_targets=self._known_targets,
            max_hops=settings.max_hops,
            checkpointer=self._checkpointer,
        )

        initial = {
            "incident_id": request.incident_id,
            "correlation_id": request.correlation_id,
            "service_ref": request.service_ref,
            "environment": request.environment.value,
            "signal": request.signal,
            "dependencies": list(dependencies),
            "observations": [],
            "hops": 0,
        }
        config = {"configurable": {"thread_id": request.incident_id}}
        final = graph.invoke(initial, config=config)

        plan = RemediationPlan.model_validate(final["plan"])
        cost.hops = int(final.get("hops", 0))
        return plan, cost.as_dict()
