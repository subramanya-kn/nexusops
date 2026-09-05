"""Builds and runs the diagnostic ReAct graph.

Nodes:
* ``triage``          — seed the investigation.
* ``investigate``     — run one read-only tool per hop (ReAct); loop until the provider
                        stops or the explicit hop cap is hit.
* ``synthesise_plan`` — provider turns evidence into a structured plan (temperature 0).
* ``validate_plan``   — validate against the allowlist + known targets; on failure retry
                        synthesis once with the errors, then escalate.

The hop cap lives in state (``hops``) and is enforced independently of LangGraph's
``recursion_limit`` — exceeding it yields a NO_OP + escalate plan, never a silent loop.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from langgraph.graph import END, START, StateGraph

from app.graphs.state import DiagnosisState, Observation
from app.llm.base import LLMProvider, ReasoningContext, ToolCall
from app.schemas.plan import RemediationPlan, noop_plan
from app.security.injection import validate_plan
from app.tools.registry import Toolbox


def _context(state: DiagnosisState) -> ReasoningContext:
    return ReasoningContext(
        incident_id=state["incident_id"],
        correlation_id=state["correlation_id"],
        service_ref=state["service_ref"],
        environment=state.get("environment", "PROD"),
        signal=state.get("signal", ""),
        dependencies=list(state.get("dependencies", [])),
        observations=[(o["tool"], o["id"], o["raw"]) for o in state.get("observations", [])],
        hops=state.get("hops", 0),
    )


def build_graph(
    toolbox: Toolbox,
    provider: LLMProvider,
    known_targets: Iterable[str],
    max_hops: int,
    checkpointer: Any | None = None,
) -> Any:
    targets = list(known_targets)

    def triage(state: DiagnosisState) -> DiagnosisState:
        return {"hops": 0, "retried": False, "escalate": False}

    def investigate(state: DiagnosisState) -> DiagnosisState:
        decision = provider.decide(_context(state))
        if isinstance(decision, ToolCall):
            obs = _run_tool(toolbox, decision)
            if obs is not None:
                return {"observations": [obs], "hops": state.get("hops", 0) + 1}
            return {"hops": state.get("hops", 0) + 1}
        # Provider chose to stop investigating.
        return {"stop": True, "hops": state.get("hops", 0) + 1}

    def route_after_investigate(state: DiagnosisState) -> str:
        if state.get("stop") or state.get("hops", 0) >= max_hops:
            return "synthesise_plan"
        return "investigate"

    def synthesise_plan(state: DiagnosisState) -> DiagnosisState:
        # Hop cap breached -> do not act; escalate with a NO_OP.
        if state.get("hops", 0) >= max_hops and not state.get("observations"):
            plan = noop_plan(state["incident_id"], state["correlation_id"],
                             state["service_ref"], "hop cap reached before evidence gathered")
            return {"plan": plan.model_dump(by_alias=True), "escalate": True}
        plan = provider.synthesise(_context(state))
        if state.get("hops", 0) >= max_hops:
            plan = plan.model_copy(update={"escalate": True})
        return {"plan": plan.model_dump(by_alias=True)}

    def validate_plan_node(state: DiagnosisState) -> DiagnosisState:
        plan = RemediationPlan.model_validate(state["plan"])
        errors = validate_plan(plan, targets)
        if not errors:
            return {"validation_errors": []}
        if not state.get("retried", False):
            return {"validation_errors": errors, "retried": True}
        # Second failure -> safe NO_OP + escalate.
        safe = noop_plan(state["incident_id"], state["correlation_id"], state["service_ref"],
                         "plan failed validation twice: " + "; ".join(errors))
        return {"plan": safe.model_dump(by_alias=True), "validation_errors": errors,
                "escalate": True}

    def route_after_validate(state: DiagnosisState) -> str:
        if state.get("validation_errors") and state.get("retried") and not state.get("escalate"):
            return "synthesise_plan"
        return END

    graph = StateGraph(DiagnosisState)
    graph.add_node("triage", triage)
    graph.add_node("investigate", investigate)
    graph.add_node("synthesise_plan", synthesise_plan)
    graph.add_node("validate_plan", validate_plan_node)

    graph.add_edge(START, "triage")
    graph.add_edge("triage", "investigate")
    graph.add_conditional_edges("investigate", route_after_investigate,
                                {"investigate": "investigate",
                                 "synthesise_plan": "synthesise_plan"})
    graph.add_edge("synthesise_plan", "validate_plan")
    graph.add_conditional_edges("validate_plan", route_after_validate,
                                {"synthesise_plan": "synthesise_plan", END: END})

    return graph.compile(checkpointer=checkpointer)


def _run_tool(toolbox: Toolbox, call: ToolCall) -> Observation | None:
    method = getattr(toolbox, call.tool, None)
    if method is None:
        return None
    try:
        obs = method(**call.args)
    except Exception as exc:  # handle_tool_error: failures become observations, not crashes
        return {
            "tool": call.tool,
            "id": f"err-{call.tool}",
            "raw": f"tool error: {exc}",
            "content": f"tool error: {exc}",
            "injection_flags": [],
        }
    return {
        "tool": obs.tool_name,
        "id": obs.id,
        "raw": obs.raw,
        "content": obs.content,
        "injection_flags": obs.injection_flags,
    }
