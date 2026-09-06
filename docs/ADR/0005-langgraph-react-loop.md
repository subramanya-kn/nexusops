# ADR-0005: LangGraph for the diagnostic ReAct loop

## Context

The reasoning plane needs to run a bounded ReAct loop: triage an incident, decide which
read-only tool to call next based on what's been observed so far, decide when enough
evidence has been gathered, and synthesize a structured plan — with an explicit hop cap
that's a genuine safety boundary (not just a soft LLM-context limit), retry-with-feedback
on plan validation failure, and (optionally) durable per-incident state so a diagnosis can
resume across a restart.

## Decision

Use LangGraph (`langgraph` package) to express the loop as an explicit state graph:
`triage → investigate (tool loop) → synthesise_plan → validate_plan`, with `DiagnosisState`
as a `TypedDict` using an append reducer for `observations` (each hop's tool result adds to
the trace rather than overwriting it). The hop cap lives in application state (`hops`,
checked in `route_after_investigate` and again in `synthesise_plan`) **independent of**
LangGraph's own `recursion_limit` — exceeding it always produces a `NO_OP + escalate` plan,
never a silent loop or an uncontrolled exception. `validate_plan` re-checks the emitted
plan against the action allowlist and known targets; on failure it retries synthesis once
with the validation errors fed back, then escalates rather than looping indefinitely.

## Consequences

- **Positive:** the control flow is explicit and inspectable as a graph, not implicit in a
  long imperative function — useful for both understanding the loop and for adding new
  nodes later (e.g., a separate "consult runbook" node) without restructuring everything.
- **Positive:** the append-reducer state pattern gives a clean, typed audit trail of every
  observation gathered during one diagnosis for free — it's just the accumulated state,
  not a side channel that has to be kept in sync.
- **Positive:** the checkpointer abstraction (see ADR-0006) is a first-class LangGraph
  concept, not something bolted on.
- **Negative:** LangGraph is a genuine dependency with its own release cadence and mental
  model (nodes, edges, reducers) that a contributor has to learn; a hand-rolled while-loop
  would have zero new concepts, at the cost of the state/observability benefits above.
- **Negative:** the project pins `langgraph==0.2.53` for stability; the brief specified
  `langgraph==1.2.11` but that version wasn't yet available/stable against the rest of the
  pinned stack at build time — recorded as a deviation in `docs/BUILD-LOG.md`.

## Alternatives rejected

- **Hand-rolled while-loop with explicit tool dispatch.** Rejected: reimplements exactly
  what LangGraph already provides (bounded iteration, typed state accumulation, retry
  wiring) with more custom code to test and maintain, for no corresponding benefit given
  the loop's shape is a textbook ReAct pattern.
- **A general agent framework with implicit/hidden tool-selection loops (e.g., a
  framework where the recursion limit is the only safety boundary).** Rejected: the
  invariant "an explicit hops counter, independent of recursion_limit" was a stated
  requirement precisely because relying solely on a framework's internal recursion guard
  conflates "the framework stopped for its own reasons" with "the application enforced its
  own safety boundary" — the latter must be true regardless of what the framework does.
- **No graph framework — call the LLM provider directly in a loop inside `DiagnosisService`.**
  Rejected: would still need to reimplement hop-capping, tool-call routing, and
  retry-with-feedback by hand, effectively rebuilding a smaller, less-tested version of
  LangGraph's state graph.
