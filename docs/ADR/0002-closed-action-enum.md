# ADR-0002: Closed `ActionType` enum, no free-form action

## Context

The reasoning plane proposes remediation actions as part of a `RemediationPlan`. If the
plan's action type were a free-form string (or worse, a command string), the LLM's output
would effectively determine what gets executed on real infrastructure — and an LLM's
output is neither fully predictable (hallucination) nor fully trustworthy (it reasons over
attacker-influenced input like container logs). Any design that lets model output become,
directly or indirectly, an executable instruction reintroduces the exact risk ADR-0001's
process split exists to eliminate.

## Decision

`ActionType` (`control-plane/.../remediation/ActionType.java`, mirrored in
`reasoning-plane/.../schemas/plan.py`) is a closed enum:

```
RESTART_CONTAINER | SCALE_SERVICE | ROLLBACK_IMAGE | CLEAR_CACHE | ROTATE_LOG | NO_OP
```

There is deliberately no `RUN_COMMAND`, no `EXEC`, no free-form variant. Every
`PlanAction` is validated against this enum **server-side**, on both sides of the wire:
the reasoning plane's Pydantic model rejects unknown values before the plan ever leaves
the process (`extra="forbid"` on the wire models), and the control plane's
`CapabilityExecutor.validate()` re-checks the type, the target's existence in the
service registry, and (for `ROLLBACK_IMAGE`) that the target digest is in a known-good
allowlist — regardless of what the reasoning plane sent. Adding a member to this enum is
a reviewed, deliberate act: the attack surface of the whole system is exactly this list.

## Consequences

- **Positive:** there is no path from LLM output to an arbitrary command, by construction,
  not by convention or prompt engineering. This is provable by reading the enum, not by
  trusting a system prompt.
- **Positive:** each action maps to exactly one narrow, parameterized method on
  `InfrastructureGateway` (`restartContainer`, `scaleService`, ...) — no interpreter, no
  shell, no string built into an executable context anywhere in the dispatch path.
- **Negative:** the system can only ever do what's in the enum. A genuinely novel
  remediation (say, a database failover) requires a code change and a new reviewed
  capability, not a model that "figures it out" — this is the point, not a limitation to
  work around.
- **Negative:** some incidents that a human operator could resolve with an ad hoc command
  will only ever get `NO_OP + escalate` from this system. Traded deliberately for safety.

## Alternatives rejected

- **Free-form action string, validated by a regex/allowlist of command patterns.**
  Rejected: pattern-matching a string for safety is fragile (encoding tricks, partial
  matches) and still requires *some* interpreter to turn the string into an operation —
  reintroducing exactly the shell/exec surface the no-shell-exec CI check exists to catch.
- **Open action set with a runtime capability-registration mechanism** (plugins can
  register new action types). Rejected: this moves the closed-set guarantee from
  "provable by reading one file" to "provable by auditing every registered plugin,"
  which is a materially weaker security property for a system with a red-team demo as a
  stated goal.
