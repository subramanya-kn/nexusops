package io.nexusops.execution;

/** Result state of a single capability execution. */
public enum ExecutionStatus {
    /** Dry-run only — the intended operation was returned, nothing was applied. */
    DRY_RUN,
    SUCCEEDED,
    FAILED,
    /** Blocked before execution (kill switch, rate limit, or duplicate execution key). */
    BLOCKED
}
