"""The read-only toolbox exposed to the ReAct loop.

Each method returns a typed :class:`Observation` with a stable id the plan can cite as
evidence. Log output is truncated (bounded) to protect the context window and cost model.
All tool output is wrapped as data via :func:`~app.security.injection.wrap_observation`.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.observability.cost import CostMeter
from app.security.injection import scan_for_injection, wrap_observation
from app.tools.infra_client import InfraClient
from app.tools.runbooks import RunbookIndex


@dataclass
class Observation:
    id: str
    tool_name: str
    content: str          # delimited, model-facing (data, not instructions)
    raw: str              # underlying facts (structured), used for internal reasoning
    injection_flags: list[str]


class Toolbox:
    """Wraps an :class:`InfraClient` + runbook index; assigns observation ids and meters."""

    def __init__(
        self,
        client: InfraClient,
        runbooks: RunbookIndex,
        cost: CostMeter,
        max_log_lines: int = 100,
    ) -> None:
        self._client = client
        self._runbooks = runbooks
        self._cost = cost
        self._max_log_lines = max_log_lines
        self._counter = 0

    def _next_id(self) -> str:
        self._counter += 1
        return f"obs-{self._counter}"

    def _observe(self, tool_name: str, raw: str) -> Observation:
        obs_id = self._next_id()
        flags = scan_for_injection(raw)
        if flags:
            self._cost.injection_flags += len(flags)
        self._cost.record_tool_call(tool_name)
        return Observation(
            id=obs_id,
            tool_name=tool_name,
            content=wrap_observation(tool_name, obs_id, raw),
            raw=raw,
            injection_flags=flags,
        )

    # --- read-only tools -------------------------------------------------

    def get_container_status(self, ref: str) -> Observation:
        s = self._client.get_container_status(ref)
        raw = (
            f"running={s.running} restartCount={s.restart_count} "
            f"lastExitCode={s.last_exit_code} oomKilled={s.oom_killed} image={s.image}"
        )
        return self._observe("get_container_status", raw)

    def get_container_logs(self, ref: str, tail: int = 50) -> Observation:
        tail = max(1, min(tail, self._max_log_lines))  # bound output
        lines = self._client.get_container_logs(ref, tail)
        raw = "\n".join(lines[-tail:]) if lines else "(no logs)"
        return self._observe("get_container_logs", raw)

    def get_resource_metrics(self, ref: str) -> Observation:
        m = self._client.get_resource_metrics(ref)
        raw = f"cpuPct={m.cpu_pct:.1f} memPct={m.mem_pct:.1f} memLimitMb={m.mem_limit_mb}"
        return self._observe("get_resource_metrics", raw)

    def get_service_health(self, ref: str) -> Observation:
        healthy = self._client.get_service_health(ref)
        return self._observe("get_service_health", f"healthy={healthy}")

    def get_recent_deploys(self, ref: str) -> Observation:
        deploys = self._client.get_recent_deploys(ref)
        raw = "; ".join(f"{d.image}@{d.at}" for d in deploys) or "(no recent deploys)"
        return self._observe("get_recent_deploys", raw)

    def get_dependency_graph(self, ref: str) -> Observation:
        graph = self._client.get_dependency_graph(ref)
        raw = "; ".join(f"{k}->{','.join(v)}" for k, v in graph.items()) or "(no dependencies)"
        return self._observe("get_dependency_graph", raw)

    def search_runbooks(self, query: str, k: int = 2) -> Observation:
        hits = self._runbooks.search(query, k=k)
        raw = "\n---\n".join(f"[{title}] {snippet}" for title, snippet in hits) or "(no match)"
        return self._observe("search_runbooks", raw)
