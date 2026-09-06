# ADR-0004: Policy-as-data with fail-safe precedence

## Context

Whether a proposed remediation plan can auto-execute, needs a human, or is denied outright
depends on several factors — action type, environment, blast radius, model confidence,
action count, repeat-incident history, per-service auto-approval rate. This logic changes
as the system is trusted more (or less) over time and needs to be reviewable and
explainable to a human auditing a decision ("why did this auto-execute?"), not just
correct.

## Decision

Policy rules live in `control-plane/src/main/resources/policy-rules.yaml`, loaded and
evaluated by `PolicyEngine` — not hardcoded as Java conditionals. Rules are evaluated top
to bottom; every rule whose `when` clause matches "fires" and contributes a decision
(`AUTO_APPROVE` / `REQUIRE_HUMAN` / `DENY`); the strongest contribution wins with fixed
precedence `DENY > REQUIRE_HUMAN > AUTO_APPROVE` — fail-safe: if a dangerous rule and a
permissive rule both match, the system leans toward the more conservative outcome, never
the reverse. Every decision reports the full list of fired rule ids alongside the risk
score. "The policy said no" is useless for an audit; "rule `prod-high-blast-radius` denied
this" is operable.

Rules can be reloaded at runtime (`PolicyAdminController` → `PolicyEngine.reload()`,
ADMIN-only) without a redeploy.

## Consequences

- **Positive:** policy changes (tightening auto-approval, adding a new loop-protection
  rule) are a YAML diff reviewable in a PR, not a Java code change requiring a rebuild.
- **Positive:** explainability is structural, not an afterthought — `PolicyResult`
  *always* carries `firedRuleIds`, tested directly (`PolicyEngineTest`).
  the fail-safe precedence is itself a unit-tested property
  (`repeatIncidentLoopDeniesEvenWhenAnAutoApproveRuleAlsoFires`).
- **Negative:** the rule language is intentionally simple (a flat set of AND'd conditions
  per rule); it cannot express arbitrary boolean logic across rules without duplicating
  conditions. Acceptable for the current rule count; would need a real rule engine
  (Drools, or a small DSL) if the ruleset grows substantially.
- **Negative:** YAML has no compile-time type checking — a typo in a condition key is
  silently ignored (Jackson's `@JsonIgnoreProperties(ignoreUnknown = true)`) rather than
  a load-time error. Mitigated by `PolicyEngineTest` pinning expected behavior against
  the real committed file, so a rule that silently stops matching breaks the test suite.

## Alternatives rejected

- **Hardcoded Java `if`/`switch` policy logic.** Rejected: every policy change would be a
  code change requiring a full rebuild and redeploy, and the logic is harder to audit at a
  glance for a non-Java-reading stakeholder (e.g., a security reviewer).
- **A general-purpose rules engine (Drools, OPA/Rego).** Rejected for v1 as more
  machinery than six rule types warrant; the flat YAML + Java matcher is auditable in one
  file and one class. Revisit if the ruleset needs cross-rule composition OPA/Rego
  supports natively (noted in "what I'd do next").
- **Policy decisions computed by the reasoning plane itself** (since it already has the
  plan and context). Rejected outright: this would let the least-trusted component decide
  its own authorization level, which defeats the entire point of the trust boundary in
  ADR-0001.
