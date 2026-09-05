# Architecture

_Stub — expanded across Phases 2–6._

## Two-plane split

- **Control plane** (Java 21 / Spring Boot 3): the only component that can change infrastructure.
  Owns policy evaluation, the approval workflow, capability-based execution (typed docker-java
  ops, no shell), verification, and the hash-chained audit log.
- **Reasoning plane** (Python 3.12 / FastAPI + LangGraph): a read-only ReAct diagnostic agent.
  Holds no credentials, has no write surface, and returns a structured `RemediationPlan` proposal.

## Flow

```
incident detected → diagnose (reasoning plane) → policy gate → approval → execute → verify → audit
```

The reasoning plane proposes; the control plane disposes. Every action is capability-checked,
policy-gated, optionally human-approved, and recorded in a tamper-evident audit chain.

## Components

See package layout under `control-plane/src/main/java/io/nexusops/` and `reasoning-plane/app/`.
