"""Per-diagnosis token and cost accounting, emitted as a metric on each diagnosis."""

from __future__ import annotations

from dataclasses import dataclass, field

# Rough public per-token prices (USD) for cost estimation only. Not billed on.
_PRICE_PER_1K = {
    "input": 0.003,
    "output": 0.015,
}


@dataclass
class CostMeter:
    """Accumulates token usage across the hops of one diagnosis."""

    input_tokens: int = 0
    output_tokens: int = 0
    hops: int = 0
    tool_calls: int = 0
    injection_flags: int = 0
    per_tool: dict[str, int] = field(default_factory=dict)

    def add_usage(self, input_tokens: int, output_tokens: int) -> None:
        self.input_tokens += input_tokens
        self.output_tokens += output_tokens

    def record_tool_call(self, tool_name: str) -> None:
        self.tool_calls += 1
        self.per_tool[tool_name] = self.per_tool.get(tool_name, 0) + 1

    @property
    def estimated_cost_usd(self) -> float:
        return round(
            (self.input_tokens / 1000.0) * _PRICE_PER_1K["input"]
            + (self.output_tokens / 1000.0) * _PRICE_PER_1K["output"],
            6,
        )

    def as_dict(self) -> dict[str, object]:
        return {
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "hops": self.hops,
            "tool_calls": self.tool_calls,
            "injection_flags": self.injection_flags,
            "estimated_cost_usd": self.estimated_cost_usd,
            "per_tool": dict(self.per_tool),
        }
