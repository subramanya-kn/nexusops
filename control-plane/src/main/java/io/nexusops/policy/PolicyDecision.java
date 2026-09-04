package io.nexusops.policy;

/** Outcome of policy evaluation over a remediation plan. */
public enum PolicyDecision {
    /** Low risk — execute without human sign-off. */
    AUTO_APPROVE,
    /** Non-trivial risk — hold for an APPROVER. */
    REQUIRE_HUMAN,
    /** Disallowed outright — never executes. */
    DENY
}
