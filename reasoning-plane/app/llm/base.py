"""Provider interface for the ReAct loop.

A provider makes two kinds of decision:

* :meth:`decide` — given what's been observed, either call another read-only tool or stop.
* :meth:`synthesise` — turn the gathered evidence into a structured RemediationPlan.

Both are deliberately narrow. The provider never gets a write capability; it can only ask
the loop to run read-only tools or emit a (re-validated) plan.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from app.schemas.plan import RemediationPlan


@dataclass
class ReasoningContext:
    incident_id: str
    correlation_id: str
    service_ref: str
    environment: str
    signal: str
    dependencies: list[str] = field(default_factory=list)
    # (tool_name, obs_id, raw_facts) — raw facts only; model-facing content is delimited.
    observations: list[tuple[str, str, str]] = field(default_factory=list)
    hops: int = 0

    def called_tools(self) -> set[str]:
        return {name for name, _, _ in self.observations}

    def facts(self) -> str:
        return "\n".join(f"{name}: {raw}" for name, _, raw in self.observations)


@dataclass
class ToolCall:
    tool: str
    args: dict[str, object] = field(default_factory=dict)


@dataclass
class Stop:
    pass


Decision = ToolCall | Stop


class LLMProvider(Protocol):
    name: str

    def decide(self, ctx: ReasoningContext) -> Decision:
        """Choose the next read-only tool to run, or Stop to synthesise a plan."""
        ...

    def synthesise(self, ctx: ReasoningContext) -> RemediationPlan:
        """Emit a structured plan from the gathered evidence (temperature 0)."""
        ...
