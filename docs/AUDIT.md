# AUDIT — Phase 0

## What exists

Repo root contains only Claude Code tooling scaffold, no application code:

- `CLAUDE.md` — project instructions (claude-flow swarm/agent config, behavioral rules)
- `.claude/` — skills, agent definitions, command templates, helper scripts (claude-flow tooling)
- `.claude-flow/` — session/intelligence state (`data/`, `sessions/`)
- `.mcp.json` — MCP server config

No `src/`, `docs/` (until this file), `control-plane/`, `reasoning-plane/`, `eval/`, `.github/`. No `pom.xml`, `build.gradle`, `requirements.txt`, `pyproject.toml`, `package.json`, or `docker-compose.yml` anywhere. Not a git repository (`git status` fails — no `.git`).

## Salvageable

Nothing toward the NexusOps architecture. The `.claude/` tooling scaffold is orthogonal to the product (dev-environment config, not app code) — leave as is, don't touch.

## What must be deleted

Nothing to delete. No dead/incoherent code to strip.

## What's missing (= everything in §1–§10 of the brief)

- Two-plane structure: `control-plane/` (Java 21/Spring Boot 3), `reasoning-plane/` (Python/FastAPI/LangGraph)
- `docker-compose.yml`, `Makefile`
- `docs/ARCHITECTURE.md`, `docs/THREAT-MODEL.md`, `docs/ADR/`, `docs/diagrams/`
- All control-plane packages: security, incident, remediation, policy, approval, execution, verification, audit, registry, reasoning-client
- All reasoning-plane packages: api, graphs, tools, schemas, llm, observability, security
- `eval/` harness, scenarios, chaos injectors, reports
- `.github/workflows/` (ci.yml, eval.yml, security.yml)
- Not a git repo yet — needed for CI, ADRs-as-commits, and any GitHub workflow to function

## Recommended build order

Since there is nothing to refactor, this is a pure build, not an audit-driven salvage. Recommend:

1. `git init` first — everything downstream (CI, ADRs, hash-chain-of-commits style provenance) assumes version control.
2. Phase 1 — scaffold full directory tree, minimal buildable skeletons for both services, `docker-compose.yml` wiring (Postgres, Keycloak, Redis, otel, Grafana), confirm `docker compose up` gives green health checks with no business logic yet.
3. Phase 2 — control plane: security filter chain + Keycloak integration first (everything else depends on auth working), then domain model, policy engine, approval state machine, executor, audit hash chain.
4. Phase 3 — reasoning plane: LangGraph skeleton, read-only tools, structured plan output, prompt-injection defenses.
5. Phase 4 — wire executor to real Docker capabilities + write `THREAT-MODEL.md`.
6. Phase 5 — eval harness + scenarios.
7. Phase 6 — observability (tracing/dashboards).
8. Phase 7 — production-signals checklist pass.
9. Phase 8 — README, last, sourced from real eval numbers.

## Open question before Phase 1

Confirm: OK to `git init` this directory? Repo currently has no VCS.
