package io.nexusops.approval;

/**
 * States of the approval / execution lifecycle.
 *
 * <pre>
 * PROPOSED -> PENDING_APPROVAL -> APPROVED -> EXECUTING -> VERIFYING -> RESOLVED
 *                              -> REJECTED / EXPIRED -> CLOSED
 *                                                    -> FAILED -> ESCALATED
 * </pre>
 */
public enum ApprovalState {
    PROPOSED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXPIRED,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    FAILED,
    ESCALATED,
    CLOSED
}
