# ADR-0001: Two-plane split (control plane vs. reasoning plane)

## Context

NexusOps lets an LLM agent diagnose infrastructure incidents and propose fixes. The
obvious, fastest way to build this is a single service: one process that both reasons
about the incident and holds the credentials to act on it — an LLM with a Docker socket.
That collapses the trust boundary between "figures out what might be wrong" and "is
allowed to change production infrastructure" into one blast radius. A prompt injection,
a hallucinated action, or a compromised dependency in the reasoning stack becomes
equivalent to a root shell on the host.

## Decision

Split the system into two processes with a hard trust boundary between them:

- **Control plane** (Java 21 / Spring Boot 3): owns identity, policy, approval, the
  capability executor, and the audit log. The only component holding infrastructure
  credentials (Docker API access, database credentials).
- **Reasoning plane** (Python 3.12 / FastAPI / LangGraph): a read-only ReAct diagnostic
  agent. Holds no credentials, has no write path to infrastructure, and can only ever
  return a structured `RemediationPlan` proposal over HTTP.

The reasoning plane calls out to read-only tools and returns a plan; the control plane
independently validates that plan (allowlist, target existence, blast radius) before it
ever reaches a policy or execution decision. The reasoning plane cannot execute anything
even if it wanted to — there is no code path from it to the Docker API.

## Consequences

- **Positive:** a full compromise of the reasoning plane (the component most exposed to
  attacker-influenced input — container logs, prompt injection) yields zero infrastructure
  access. The blast radius of the riskiest component is capped by construction.
- **Positive:** the two services can be developed, tested, and scaled independently; the
  reasoning plane's dependency surface (LangGraph, an LLM SDK) never touches the
  credential-holding process.
- **Negative:** every diagnosis requires a network hop and a serialization boundary
  (`RemediationPlan` must be re-validated on both sides — see ADR-0002). This adds latency
  and duplicated validation logic compared to an in-process call.
- **Negative:** operational complexity — two deployables, two health checks, two log
  streams to correlate (mitigated by a shared `correlationId` and OTel trace propagation,
  see Phase 6 of the build log).

## Alternatives rejected

- **Single process, LLM with Docker socket access.** Rejected outright — this is
  explicitly the anti-pattern the project exists to demonstrate an alternative to (see
  `docs/THREAT-MODEL.md` opening).
- **Single process with an internal permission layer** (no separate service, but a
  privilege-checked module boundary in the same process). Rejected: a single process
  compromise (e.g. a dependency supply-chain attack in the LLM SDK) still has memory-level
  access to everything, including credentials, regardless of internal module boundaries.
  A process/network boundary is a meaningfully stronger isolation guarantee than a
  function-call boundary.
- **Reasoning plane behind a sidecar proxy with credential injection at the edge.**
  Rejected as unnecessary complexity: the reasoning plane simply never needs credentials
  if it's read-only by design, so there's nothing for a sidecar to protect.
