"""Infra read clients. A ``Protocol`` with a read-only surface and two implementations:

* :class:`SimulatedInfraClient` — deterministic, offline; state is derived from a scenario
  so unit tests and the mock provider run with no network.
* :class:`HttpInfraClient` — GETs the demo services' own introspection endpoints in compose.

Neither exposes a write method. There is no path from here to mutating anything.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol


@dataclass
class ContainerStatus:
    ref: str
    running: bool
    restart_count: int
    last_exit_code: int
    oom_killed: bool = False
    image: str = "nexusops/demo-svc:v1"


@dataclass
class ResourceMetrics:
    ref: str
    cpu_pct: float
    mem_pct: float
    mem_limit_mb: int


@dataclass
class Deploy:
    ref: str
    image: str
    at: str


@dataclass
class Scenario:
    """A canned world the SimulatedInfraClient answers from (used by tests/eval)."""

    service_ref: str
    status: ContainerStatus
    metrics: ResourceMetrics
    logs: list[str] = field(default_factory=list)
    healthy: bool = False
    deploys: list[Deploy] = field(default_factory=list)
    dependencies: dict[str, list[str]] = field(default_factory=dict)


class InfraClient(Protocol):
    def get_container_status(self, ref: str) -> ContainerStatus: ...

    def get_container_logs(self, ref: str, tail: int) -> list[str]: ...

    def get_resource_metrics(self, ref: str) -> ResourceMetrics: ...

    def get_service_health(self, ref: str) -> bool: ...

    def get_recent_deploys(self, ref: str) -> list[Deploy]: ...

    def get_dependency_graph(self, ref: str) -> dict[str, list[str]]: ...


class SimulatedInfraClient:
    """Answers from an in-memory :class:`Scenario`. Deterministic and offline."""

    def __init__(self, scenario: Scenario) -> None:
        self._s = scenario

    def get_container_status(self, ref: str) -> ContainerStatus:
        return self._s.status

    def get_container_logs(self, ref: str, tail: int) -> list[str]:
        return self._s.logs[-tail:]

    def get_resource_metrics(self, ref: str) -> ResourceMetrics:
        return self._s.metrics

    def get_service_health(self, ref: str) -> bool:
        return self._s.healthy

    def get_recent_deploys(self, ref: str) -> list[Deploy]:
        return self._s.deploys

    def get_dependency_graph(self, ref: str) -> dict[str, list[str]]:
        return self._s.dependencies


class HttpInfraClient:
    """GETs the demo services' introspection endpoints. Read-only, no credentials."""

    def __init__(self, base_url_template: str = "http://{ref}:8080") -> None:
        self._tpl = base_url_template

    def _base(self, ref: str) -> str:
        return self._tpl.format(ref=ref)

    def get_container_status(self, ref: str) -> ContainerStatus:
        import httpx

        data = httpx.get(f"{self._base(ref)}/status", timeout=3.0).json()
        return ContainerStatus(
            ref=ref,
            running=bool(data.get("running", True)),
            restart_count=int(data.get("restartCount", 0)),
            last_exit_code=int(data.get("lastExitCode", 0)),
            oom_killed=bool(data.get("oomKilled", False)),
            image=str(data.get("image", "nexusops/demo-svc:v1")),
        )

    def get_container_logs(self, ref: str, tail: int) -> list[str]:
        import httpx

        resp = httpx.get(f"{self._base(ref)}/logs", params={"tail": tail}, timeout=3.0)
        lines = resp.json().get("lines", [])
        return [str(line) for line in lines][-tail:]

    def get_resource_metrics(self, ref: str) -> ResourceMetrics:
        import httpx

        data = httpx.get(f"{self._base(ref)}/metrics-json", timeout=3.0).json()
        return ResourceMetrics(
            ref=ref,
            cpu_pct=float(data.get("cpuPct", 0.0)),
            mem_pct=float(data.get("memPct", 0.0)),
            mem_limit_mb=int(data.get("memLimitMb", 128)),
        )

    def get_service_health(self, ref: str) -> bool:
        import httpx

        try:
            return httpx.get(f"{self._base(ref)}/health", timeout=3.0).status_code == 200
        except httpx.HTTPError:
            return False

    def get_recent_deploys(self, ref: str) -> list[Deploy]:
        import httpx

        try:
            data = httpx.get(f"{self._base(ref)}/deploys", timeout=3.0).json()
            return [Deploy(ref=ref, image=d["image"], at=d["at"]) for d in data.get("deploys", [])]
        except httpx.HTTPError:
            return []

    def get_dependency_graph(self, ref: str) -> dict[str, list[str]]:
        import httpx

        try:
            return dict(httpx.get(f"{self._base(ref)}/deps", timeout=3.0).json())
        except httpx.HTTPError:
            return {}
