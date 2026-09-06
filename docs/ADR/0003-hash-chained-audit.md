# ADR-0003: Hash-chained, append-only audit log

## Context

Every state transition in the system — incident detection, diagnosis, policy decision,
approval, execution, verification, escalation — needs a permanent record. For a system
whose whole premise is "we let an agent influence production infrastructure, but safely,"
the audit trail itself has to be trustworthy: if someone with database access could quietly
edit a row after the fact (to hide a bad decision, or to make a compromised action look
authorized), the audit log's evidentiary value is zero.

## Decision

`AuditService` (`control-plane/.../audit/AuditService.java`) implements an append-only,
hash-chained log. Every row (`AuditEntryEntity`) stores its own SHA-256 hash and the
previous row's hash; the hash covers the previous hash plus the row's own content
(incident id, correlation id, event type, payload, actor, timestamp). `verifyChain()`
walks the table from genesis and recomputes every hash — a match end to end proves nothing
was altered after the fact; a mismatch identifies the exact row (`brokenAtSeq`) and reason
(prev-hash mismatch vs. payload tamper). `GET /api/audit/verify` exposes this: 200 if
intact, 409 if broken.

Appends are `synchronized` within one instance to keep the chain strictly linear, and
every append happens inside the same `@Transactional` boundary as the state change it
records — if the audit write fails, the transaction rolls back and nothing else took
effect (fail-closed, see the executor's guard chain).

## Consequences

- **Positive:** tampering is *detectable*, not just discouraged. A retroactive edit to
  any historical row — including one masked by leaving the stored `hash` untouched — is
  caught by `verifyChain()` because the hash is recomputed from the row's actual current
  content, not merely read back. Proven by `AuditTamperIntegrationTest` against a real
  Postgres instance.
- **Positive:** simple to implement and reason about — a single SHA-256 chain, no external
  dependency, no separate ledger service.
- **Negative:** this is tamper-*evidence*, not tamper-*prevention*. Someone with direct DB
  write access can still corrupt the chain; they just can't do it without the corruption
  being detectable on the next `verifyChain()` call. A determined attacker with DB access
  and enough patience could theoretically recompute a new consistent chain from the point
  of tampering onward — mitigated by shipping the chain's tip hash to an external,
  independently-controlled store in a production deployment (noted in "what I'd do next").
- **Negative:** `verifyChain()` is O(n) over the whole table; fine at incident-log scale,
  would need incremental/checkpointed verification at very high volume.

## Alternatives rejected

- **Database-level audit triggers / CDC.** Rejected as the primary mechanism: triggers
  detect *that* a change happened, not *whether the row is internally consistent* the way
  a self-describing hash chain does, and add a layer of DB-specific logic outside
  version control.
- **External ledger (e.g., a separate write-once log service, blockchain-style anchor).**
  Rejected for v1 as disproportionate operational complexity for the actual threat model
  at this stage — the hash chain already gives provable tamper-evidence within a single
  Postgres instance. Anchoring the chain tip externally is the natural next step if the
  threat model expands to include a fully compromised DB administrator.
- **Digital signatures per row (e.g., HMAC with a service key).** Rejected as
  materially equivalent security for materially more key-management complexity in v1: an
  HMAC still needs the key protected, and a compromised control-plane process that can
  forge audit rows can equally read an HMAC key held in the same process.
