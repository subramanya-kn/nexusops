# NexusOps

> Policy-gated autonomous incident remediation — an LLM agent diagnoses infrastructure
> failures and proposes fixes, executed only through a capability-based control plane.

**Status:** under construction. Full README written in Phase 8.

## Quickstart

```bash
cp .env.example .env
make up          # build + start the full stack
make verify-p1   # health checks + both planes build
make down        # tear down
```

## Architecture

Two planes:
- **control-plane** (Java 21 / Spring Boot 3) — policy, approval, capability execution, audit.
- **reasoning-plane** (Python 3.12 / FastAPI + LangGraph) — read-only ReAct diagnostic agent.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md).
