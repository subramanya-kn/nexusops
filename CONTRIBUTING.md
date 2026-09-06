# Contributing

## Setup

```bash
cp .env.example .env
make build   # mvn package (control-plane) + pip install -e '.[dev]' (reasoning-plane)
```

Toolchain: Java 21 (Temurin recommended — a JDK 26 preview locally breaks Mockito's inline
mock maker), Maven 3.9+, Python 3.11+ (3.12 in the shipped Docker images), Docker + Compose.

## Running tests

```bash
make test        # both planes, unit tests only
make verify-p2   # control-plane, including Testcontainers integration tests (needs Docker)
make verify-p3   # reasoning-plane: pytest + ruff + mypy --strict
make eval        # 12 chaos scenarios against the mock provider, writes a scorecard
```

Docker-dependent tests (`@Testcontainers(disabledWithoutDocker = true)`) skip cleanly
rather than fail when no Docker daemon is available — don't try to make them pass without
Docker; that's expected behavior, not a bug.

## Before opening a PR

- `make test` and `make verify-p3` both green.
- `make eval` shows no regression against `eval/reports/baseline.json`
  (`python3 eval/check_regression.py`).
- New capability/action-type additions: read `docs/ADR/0002-closed-action-enum.md` first —
  this is a reviewed, deliberate act, not a routine change.
- Anything touching `execution/`, `security/`, or `injection.py`: re-read
  `docs/THREAT-MODEL.md` and make sure the change doesn't weaken a row in that table.

## Conventions

- Conventional commits (`feat(control-plane): ...`, `fix(reasoning-plane): ...`,
  `test(eval): ...`, `docs(adr): ...`).
- Constructor injection only in Java — no field injection, no `@Autowired` on fields.
- No `Runtime.exec`, `ProcessBuilder`, `subprocess`, `os.system`, or docker-java's
  exec-into-container APIs anywhere in application code — enforced by a blocking CI check
  (`.github/workflows/security.yml`, `no-shell-exec` job). This is not a style preference;
  see invariant #2 in the build brief.
- New policy rules go in `control-plane/src/main/resources/policy-rules.yaml`, not as
  Java conditionals — see `docs/ADR/0004-policy-as-data.md`.
- Architectural decisions worth defending later go in `docs/ADR/` (context, decision,
  consequences, alternatives rejected) — see the existing six for the format.

## Reporting issues

Open a GitHub issue with reproduction steps. For anything security-relevant, see
`docs/THREAT-MODEL.md` first — if you've found a way around a listed mitigation, that's
the most useful kind of issue to file.
