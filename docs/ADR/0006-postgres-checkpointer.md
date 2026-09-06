# ADR-0006: Optional Postgres checkpointer, in-memory by default

## Context

A diagnosis can, in principle, span multiple hops over time, and the reasoning-plane
process can restart (deploy, crash, scale event) mid-diagnosis. LangGraph's checkpointer
abstraction persists graph state keyed by a thread id (here, the incident id) so a
diagnosis can resume rather than silently losing its accumulated observations. The
question is what backs that persistence, and whether it should be required.

## Decision

`build_checkpointer()` (`reasoning-plane/app/checkpointer.py`) is a factory switched by
`NEXUS_CHECKPOINTER` (`memory` | `postgres`), defaulting to **`memory`**
(`langgraph.checkpoint.memory.MemorySaver`). The Postgres-backed option
(`langgraph-checkpoint-postgres`) is an **optional dependency** (`pip install
.[postgres]`) — the base install and the default `memory` mode never require it, so the
whole stack builds, runs, and passes tests fully offline with zero external database
dependency for the reasoning plane specifically (the control plane's own Postgres
requirement is unrelated and unconditional). If `NEXUS_CHECKPOINTER=postgres` is set but
the dependency or DSN is missing, the factory logs a warning and falls back to
`MemorySaver` rather than failing startup.

## Consequences

- **Positive:** local development, CI, and the eval harness (`eval/harness.py`) never need
  a database to exercise the diagnostic loop — `make eval` runs 12 scenarios against the
  real graph with zero infrastructure. This was a direct enabler for Phase 5's fully
  offline scorecard.
- **Positive:** the swap from memory to Postgres is a config flag, not a code change —
  `DiagnosisService` accepts any `checkpointer` object satisfying LangGraph's interface.
- **Negative:** with `MemorySaver` (the default), a reasoning-plane restart mid-diagnosis
  loses all accumulated observations for in-flight incidents; the diagnosis simply starts
  over on the next `/diagnose` call rather than resuming. Acceptable for a bounded ReAct
  loop with a small hop cap (default 8) where "start over" costs a handful of tool calls,
  not minutes of lost work.
- **Negative:** `postgres` mode adds a second consumer of the shared Postgres instance
  (alongside the control plane's own schema) and a second set of migrations/connection
  pooling concerns to operate, deferred until actually needed.

## Alternatives rejected

- **Postgres checkpointer required, no in-memory fallback.** Rejected: would make the
  reasoning plane's tests and the eval harness depend on a live database for no benefit
  proportional to the cost — the bounded, short-lived nature of one diagnosis doesn't
  need durable cross-restart resumption to be correct, only to be *nicer* under a restart.
- **Redis-backed checkpointer.** Rejected: redis is already in the compose stack for other
  future use, but adds an operational dependency for a problem (short-lived diagnosis
  state) that doesn't need a separate cache tier when Postgres is already present and
  LangGraph ships a maintained Postgres checkpointer.
- **No checkpointing at all (stateless graph, full state passed by the caller each hop).**
  Rejected: LangGraph's `invoke()` model runs a full diagnosis to completion in one call
  from `DiagnosisService.diagnose()` already; checkpointing exists for the *process*-level
  restart case, not an inherent need of the per-request flow, so it's cheap to keep as an
  opt-in rather than restructure the call pattern to avoid it entirely.
