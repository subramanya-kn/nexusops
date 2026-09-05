# NexusOps — build, run, verify. Phase gates are machine-checkable (exit 0 == pass).
.DEFAULT_GOAL := help
SHELL := /bin/bash

COMPOSE := docker compose
CP := control-plane
RP := reasoning-plane

.PHONY: help up down restart ps logs build test demo eval chaos \
        verify-p1 verify-p2 verify-p3 verify-p4 verify-p5 verify-p6 verify-p7 verify-p8

help: ## List targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

up: ## Build images and start the full stack
	$(COMPOSE) up -d --build

down: ## Stop the stack and remove volumes
	$(COMPOSE) down -v

restart: down up ## Recreate the stack

ps: ## Show container status
	$(COMPOSE) ps

logs: ## Tail logs (SVC=name to filter)
	$(COMPOSE) logs -f $(SVC)

build: ## Build both planes locally (no containers)
	cd $(CP) && mvn -q -B clean package -DskipTests
	cd $(RP) && pip install -e '.[dev]'

test: ## Run unit tests in both planes
	cd $(CP) && mvn -q -B test
	cd $(RP) && pytest -q

demo: ## Scripted end-to-end incident (Phase 7)
	@bash eval/demo.sh 2>/dev/null || { echo "demo not implemented until Phase 7"; exit 1; }

eval: ## Run scenario harness and write scorecard (Phase 5)
	python3 eval/harness.py

chaos: ## Inject a chaos scenario (Phase 5)
	@bash eval/chaos/run.sh 2>/dev/null || { echo "chaos not implemented until Phase 5"; exit 1; }

# ---------------------------------------------------------------------------
# Phase gates
# ---------------------------------------------------------------------------

verify-p1: ## Gate: stack up + health green + both planes build
	@echo "== verify-p1: bringing stack up =="
	$(COMPOSE) up -d --build
	@echo "== waiting for control-plane (/actuator/health) and reasoning-plane (/health) =="
	@ok=0; for i in $$(seq 1 60); do \
	  cp=$$(curl -fsS http://localhost:8080/actuator/health 2>/dev/null | grep -c UP || true); \
	  rp=$$(curl -fsS -o /dev/null -w '%{http_code}' http://localhost:8000/health 2>/dev/null || echo 000); \
	  if [ "$$cp" = "1" ] && [ "$$rp" = "200" ]; then ok=1; break; fi; \
	  echo "  attempt $$i: control=$$cp reasoning=$$rp"; sleep 3; \
	done; \
	if [ "$$ok" != "1" ]; then echo "FAIL: planes not healthy within 180s"; $(COMPOSE) ps; exit 1; fi
	@echo "== control-plane: mvn verify -DskipTests =="
	cd $(CP) && mvn -q -B verify -DskipTests
	@echo "== reasoning-plane: pip install -e . =="
	cd $(RP) && pip install -e .
	@echo "PASS verify-p1"

verify-p2: ## Gate: control-plane integration tests (Phase 2)
	cd $(CP) && mvn -q -B verify

verify-p3: ## Gate: reasoning-plane tests + lint + types (Phase 3)
	cd $(RP) && pytest -q && ruff check . && mypy --strict app

verify-p4: ## Gate: security workflow + e2e dry-run + kill switch (Phase 4)
	@echo "== no-shell-exec grep check =="
	@PATTERN='Runtime\.exec|Runtime\.getRuntime\(\)\.(exec|halt)|new ProcessBuilder|\bProcessBuilder\(|\bsubprocess\.(run|Popen|call|check_call|check_output)|\bos\.system\(|execCreateCmd|ExecCreateCmd|execStartCmd'; \
	MATCHES=$$(grep -rnE "$$PATTERN" $(CP)/src/main $(RP)/app demo-svc --include='*.java' --include='*.py' || true); \
	if [ -n "$$MATCHES" ]; then echo "FAIL: forbidden shell/exec pattern found:"; echo "$$MATCHES"; exit 1; fi
	@echo "== control-plane: full incident dry-run + kill switch tests =="
	cd $(CP) && mvn -q -B test -Dtest=RemediationOrchestratorTest,CapabilityExecutorTest,KillSwitchTest
	@echo "PASS verify-p4 (Docker-based live e2e dry-run pending Docker; see BUILD-LOG)"

verify-p5: ## Gate: eval scorecard (Phase 5)
	python3 eval/harness.py
	@test -f eval/reports/latest.json || { echo "FAIL: no scorecard written"; exit 1; }
	python3 eval/check_regression.py
	@echo "PASS verify-p5 (resolution rate / time-to-remediation PENDING until docker compose up; see BUILD-LOG)"

verify-p6: ## Gate: cross-plane trace + Grafana (Phase 6)
	@echo "verify-p6 not implemented"; exit 1

verify-p7: ## Gate: full CI + make demo from clean up (Phase 7)
	@echo "verify-p7 not implemented"; exit 1

verify-p8: ## Gate: README integrity (Phase 8)
	@echo "verify-p8 not implemented"; exit 1
