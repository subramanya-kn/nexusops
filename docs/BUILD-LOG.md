# BUILD LOG

## Status
Current phase: 1
Last verified gate passed: 0 (audit complete)
Phase 1 scaffold: files complete; offline gate parts PASS; container gate pending Docker running.

## Phase 1 progress [2026-09-04]
Added: root docker-compose.yml, Makefile (up/down/demo/eval/chaos/test/verify-p1..p8),
.env.example, README.md, docs/ARCHITECTURE.md, docs/THREAT-MODEL.md, eval/ scaffold
(harness.py stub + scenarios/chaos/reports), .github/workflows/{ci,eval,security}.yml,
multi-stage non-root Dockerfiles for control-plane + reasoning-plane + demo-svc,
demo-svc/ (parameterised FastAPI target: /health + crash/leak/logflood toggles),
deploy/{otel/config.yaml,tempo/tempo.yaml,keycloak/nexusops-realm.json}.
verify-p1 offline parts verified locally: `mvn -q -B verify -DskipTests` exit 0 (target/control-plane.jar),
`pip install -e .` exit 0, `docker compose config` exit 0.
REMAINING for gate pass: start Docker, run `make verify-p1` (compose up + health green ≤180s), commit.

## Environment (resolved)
- git 2.49.0
- Java: Temurin 21.0.7 LTS
- Maven 3.9.10
- Python 3.11.9 (local) — brief requests 3.12; Docker images pin 3.12. See deviation.
- Docker 28.0.4, Compose v2.34.0

## Decisions
- [2026-09-04] git init done locally; push deferred per user (code-first).
- [2026-09-04] Demo target services built from one tiny `demo-svc` image (FastAPI health server with crash/leak toggles), parameterised via env, rather than 5 bespoke images. Keeps incident subjects trivial as brief requires.
- [2026-09-04] Spring Boot 3.3.x + Java 21, Maven build. Reasoning plane FastAPI + langgraph 1.2.11 + Pydantic v2.
- [2026-09-04] LLM_PROVIDER=mock default (deterministic canned plans) so full stack builds/tests offline; real provider is a config switch.

## Deviations from brief
- [2026-09-04] Local Python is 3.11.9, not 3.12. Docker image uses python:3.12-slim (pinned digest). Local dev/tests run on 3.11; no 3.12-only syntax used.
- [2026-09-04] Dockerfile base images pinned by tag, not @sha256 digest (brief wants digests). No network to resolve real digests offline; a fabricated digest fails the build. TODO: resolve digests in CI. Marked in each Dockerfile.
- [2026-09-04] Keycloak/otel/tempo/grafana have no compose healthchecks in Phase 1 (control-plane uses lazy jwk-set-uri, so it does not hard-depend on Keycloak). They are fully wired in Phase 2/6. Gate-relevant healthchecks (postgres, redis, control-plane, reasoning-plane, demo services) are present.

## Blocked / deferred
- [2026-09-04] git push deferred to user request (code-first, push later).

## Phase completion
- [x] Phase 0 — AUDIT.md written
- [~] Phase 1 — offline parts PASS (mvn verify -DskipTests, pip install -e ., compose config valid); container health-check part pending Docker daemon running locally
- [ ] Phase 2 — gate: `make verify-p2`
- [x] Phase 3 — gate: `verify-p3` PASS — pytest 11/11, ruff clean, mypy --strict clean (28 files), injection tests pass, mock diagnosis schema-valid
- [ ] Phase 4 — gate: `make verify-p4`

## Phase 3 fixes [2026-09-05]
Gate was failing on ruff/mypy before this pass. Fixed:
- ruff: unsorted imports (routes.py, main.py — autofix), B008 Depends-in-default suppressed with noqa (standard FastAPI DI idiom), F841 unused `healthy` local in mock.py synthesise() removed (dead code, never read), E501 long line in runbooks.py wrapped, zip() given explicit strict=True (was strict=False from autofix — corrected since the three lists are always equal length by construction).
- mypy --strict: anthropic_provider.py used getattr(block, "type", "") instead of isinstance(block, TextBlock) narrowing — fixed with proper import + isinstance check; tracing.py get_logger() returned Any from structlog — added typing.cast; diagnostic_graph.py _run_tool() was typed dict[str, Any] but return shape is exactly Observation TypedDict — retyped; routes.py get_service() returned Any from Starlette app.state — added isinstance assert.
