#!/usr/bin/env bash
# Scripted end-to-end incident: inject -> diagnose -> gate -> approve -> execute -> verify.
# Requires `make up` to already be running. Designed to complete in well under two minutes.
#
# Uses the AUTO_APPROVE path deliberately (RESTART_CONTAINER + LOW blast radius on a PROD
# service matches the `prod-restart-low-blast-auto` policy rule) so the whole loop runs
# unattended -- "approve" still happens, just via policy rather than a human click. To
# demo the human-approval path instead, raise the injected memory pressure enough to hit
# `prod-high-blast-radius` and then call POST /api/approvals/{id}/approve yourself with an
# APPROVER token (see docs/api/openapi.yaml).
set -euo pipefail

CONTROL_URL="${CONTROL_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
SERVICE_REF="${SERVICE_REF:-payment-svc}"
TIMEOUT_S="${TIMEOUT_S:-100}"

json_field() {
  # $1 = json string, $2 = python expression relative to the parsed object, e.g. "d['id']"
  python3 -c "import json,sys; d=json.loads(sys.argv[1]); print($2)" "$1"
}

echo "== 1/6: waiting for control-plane and reasoning-plane =="
for i in $(seq 1 30); do
  cp_up=$(curl -fsS "$CONTROL_URL/actuator/health" 2>/dev/null | grep -c '"status":"UP"' || true)
  [ "$cp_up" = "1" ] && break
  sleep 2
done
if [ "$cp_up" != "1" ]; then
  echo "FAIL: control-plane not healthy. Run 'make up' first." >&2
  exit 1
fi

echo "== 2/6: obtaining an OPERATOR token from Keycloak =="
TOKEN_JSON=$(curl -fsS -X POST \
  "$KEYCLOAK_URL/realms/nexusops/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=nexusops-cli" \
  -d "client_secret=nexusops-cli-secret" \
  -d "username=operator" \
  -d "password=operator")
OPERATOR_TOKEN=$(json_field "$TOKEN_JSON" "d['access_token']")

echo "== 3/6: injecting memory pressure on $SERVICE_REF (via the loadgen container, same network) =="
# demo-svc allocates ~50MB per /toggle/leak call against a 128MB internal limit
# (_MEM_LIMIT_MB in demo-svc/app.py; mem_pct prefers real cgroup usage, falling back to
# the leak-buffer size only where no cgroup memory controller is visible). Two calls
# (~100MB, ~78% of the buffer alone) don't reliably clear demo-svc's own oomKilled
# threshold (>=95%) or the mock provider's OOM-detection threshold (>=90%) once baseline
# interpreter overhead is accounted for -- the diagnosis could silently fall through to
# the generic "unclassified failure" branch instead of genuinely detecting OOM, even
# though the resulting action happens to coincide. Three calls (~150MB) gives real margin.
docker compose exec -T loadgen sh -c \
  "for i in 1 2 3; do curl -sf -X POST http://$SERVICE_REF:8080/toggle/leak >/dev/null; done"

echo "== 4/6: creating the incident =="
INCIDENT_JSON=$(curl -fsS -X POST "$CONTROL_URL/api/incidents" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"serviceRef\":\"$SERVICE_REF\",\"environment\":\"PROD\",\"signal\":\"$SERVICE_REF memory pressure detected\"}")
INCIDENT_ID=$(json_field "$INCIDENT_JSON" "d['id']")
echo "   incident: $INCIDENT_ID"

echo "== 5/6: triggering diagnose -> gate -> (auto-)approve -> execute -> verify =="
curl -fsS -X POST "$CONTROL_URL/api/incidents/$INCIDENT_ID/diagnose" \
  -H "Authorization: Bearer $OPERATOR_TOKEN" >/dev/null

echo "== 6/6: polling for resolution (timeout ${TIMEOUT_S}s) =="
START=$(date +%s)
while true; do
  INCIDENT=$(curl -fsS "$CONTROL_URL/api/incidents/$INCIDENT_ID" -H "Authorization: Bearer $OPERATOR_TOKEN")
  STATUS=$(json_field "$INCIDENT" "d['status']")
  echo "   status: $STATUS"
  if [ "$STATUS" = "RESOLVED" ] || [ "$STATUS" = "ESCALATED" ] || [ "$STATUS" = "CLOSED" ]; then
    break
  fi
  if [ $(($(date +%s) - START)) -gt "$TIMEOUT_S" ]; then
    echo "FAIL: incident did not reach a terminal state within ${TIMEOUT_S}s" >&2
    exit 1
  fi
  sleep 3
done

echo ""
echo "== Audit chain verification =="
curl -fsS "$CONTROL_URL/api/audit/verify" -H "Authorization: Bearer $OPERATOR_TOKEN"
echo ""

echo ""
echo "== Resetting $SERVICE_REF for the next run =="
docker compose exec -T loadgen sh -c "curl -sf -X POST http://$SERVICE_REF:8080/toggle/reset" >/dev/null

echo ""
echo "Demo complete: incident $INCIDENT_ID reached $STATUS."
