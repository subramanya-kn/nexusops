# BUILD LOG

## Status
Current phase: 1
Last verified gate passed: 0 (audit complete)

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

## Blocked / deferred
- [2026-09-04] git push deferred to user request (code-first, push later).

## Phase completion
- [x] Phase 0 — AUDIT.md written
- [ ] Phase 1 — gate: `make verify-p1` exit 0
- [ ] Phase 2 — gate: `make verify-p2`
- [ ] Phase 3 — gate: `make verify-p3`
- [ ] Phase 4 — gate: `make verify-p4`
