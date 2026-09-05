"""Real LLM provider (Anthropic). Optional dependency; only used when NEXUS_LLM_PROVIDER=
anthropic and a key is present. Investigation follows the same fixed read-only tool order
as the mock (deterministic evidence gathering); the model does the *synthesis* into a
structured, schema-validated plan at temperature 0.
"""

from __future__ import annotations

import json

from anthropic.types import TextBlock

from app.llm.base import Decision, LLMProvider, ReasoningContext, Stop, ToolCall
from app.llm.mock import _INVESTIGATION
from app.observability.cost import CostMeter
from app.schemas.plan import RemediationPlan, noop_plan
from app.security.injection import SYSTEM_GUARD

_SCHEMA_HINT = (
    'Return ONLY JSON: {"hypothesis": str, "confidence": 0..1, '
    '"estimatedBlastRadius": "LOW|MEDIUM|HIGH", "escalate": bool, '
    '"actions": [{"type": "RESTART_CONTAINER|SCALE_SERVICE|ROLLBACK_IMAGE|CLEAR_CACHE|'
    'ROTATE_LOG|NO_OP", "targetRef": str, "parameters": {}, "rationale": str}]}'
)


class AnthropicProvider(LLMProvider):
    name = "anthropic"

    def __init__(self, cost: CostMeter, api_key: str, model: str, temperature: float) -> None:
        import anthropic  # imported lazily; optional dependency

        self._client = anthropic.Anthropic(api_key=api_key)
        self._model = model
        self._temperature = temperature
        self._cost = cost

    def decide(self, ctx: ReasoningContext) -> Decision:
        called = ctx.called_tools()
        for tool in _INVESTIGATION:
            if tool not in called:
                args: dict[str, object] = {"ref": ctx.service_ref}
                if tool == "get_container_logs":
                    args["tail"] = 50
                if tool == "search_runbooks":
                    args = {"query": ctx.signal or ctx.service_ref}
                return ToolCall(tool=tool, args=args)
        return Stop()

    def synthesise(self, ctx: ReasoningContext) -> RemediationPlan:
        prompt = (
            f"Incident on service '{ctx.service_ref}' (env {ctx.environment}). "
            f"Signal: {ctx.signal}\n\nEvidence:\n{ctx.facts()}\n\n{_SCHEMA_HINT}"
        )
        message = self._client.messages.create(
            model=self._model,
            max_tokens=1024,
            temperature=self._temperature,
            system=SYSTEM_GUARD,
            messages=[{"role": "user", "content": prompt}],
        )
        usage = getattr(message, "usage", None)
        if usage is not None:
            self._cost.add_usage(
                input_tokens=getattr(usage, "input_tokens", 0),
                output_tokens=getattr(usage, "output_tokens", 0),
            )
        text = "".join(
            block.text for block in message.content if isinstance(block, TextBlock)
        )
        return self._parse(ctx, text)

    def _parse(self, ctx: ReasoningContext, text: str) -> RemediationPlan:
        try:
            start, end = text.index("{"), text.rindex("}") + 1
            data = json.loads(text[start:end])
            data.setdefault("incidentId", ctx.incident_id)
            data.setdefault("correlationId", ctx.correlation_id)
            return RemediationPlan.model_validate(data)
        except Exception:
            return noop_plan(ctx.incident_id, ctx.correlation_id, ctx.service_ref,
                             "model output failed schema validation", escalate=True)
