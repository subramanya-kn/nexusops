# Threat Model

_Stub — expanded in Phases 2–4._

## Trust boundaries

1. **Reasoning plane is untrusted for actions.** It reads (simulated/real) infra state and
   attacker-influenced data (container logs). It can never mutate infrastructure directly.
2. **Control plane is the sole actuator.** Capability-based execution: a closed `ActionType`
   enum, typed docker-java operations, no shell/exec, target allowlist via the registry.

## Key threats & mitigations

| Threat | Mitigation |
|--------|------------|
| Indirect prompt injection via logs | Data-not-instruction wrapping + structural plan validation |
| Malicious/over-broad plan | Policy-as-data gate, blast-radius limits, approval workflow |
| Runaway agent | Hop cap on the ReAct loop; bounded tool output |
| Audit tampering | Hash-chained append-only audit log with chain verification |
| Unauthorized action | OAuth2 (Keycloak JWT) resource server, deny-by-default, role scopes |
| Control-plane compromise blast radius | Kill switch (fail-closed), capability allowlist, dry-run mode |
