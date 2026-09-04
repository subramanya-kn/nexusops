package io.nexusops.approval;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure transition table for the approval lifecycle. Kept separate from persistence so the
 * legal-transition rules are trivially unit-testable and cannot be bypassed by a service
 * setting a state directly.
 */
public final class ApprovalStateMachine {

    private static final Map<ApprovalState, Set<ApprovalState>> TRANSITIONS =
            new EnumMap<>(ApprovalState.class);

    static {
        TRANSITIONS.put(ApprovalState.PROPOSED,
                EnumSet.of(ApprovalState.PENDING_APPROVAL, ApprovalState.APPROVED,
                        ApprovalState.REJECTED));
        TRANSITIONS.put(ApprovalState.PENDING_APPROVAL,
                EnumSet.of(ApprovalState.APPROVED, ApprovalState.REJECTED, ApprovalState.EXPIRED));
        TRANSITIONS.put(ApprovalState.APPROVED, EnumSet.of(ApprovalState.EXECUTING));
        TRANSITIONS.put(ApprovalState.EXECUTING,
                EnumSet.of(ApprovalState.VERIFYING, ApprovalState.FAILED));
        TRANSITIONS.put(ApprovalState.VERIFYING,
                EnumSet.of(ApprovalState.RESOLVED, ApprovalState.FAILED));
        TRANSITIONS.put(ApprovalState.FAILED, EnumSet.of(ApprovalState.ESCALATED));
        TRANSITIONS.put(ApprovalState.REJECTED, EnumSet.of(ApprovalState.CLOSED));
        TRANSITIONS.put(ApprovalState.EXPIRED, EnumSet.of(ApprovalState.CLOSED));
        TRANSITIONS.put(ApprovalState.RESOLVED, EnumSet.of(ApprovalState.CLOSED));
        TRANSITIONS.put(ApprovalState.ESCALATED, EnumSet.of(ApprovalState.CLOSED));
        TRANSITIONS.put(ApprovalState.CLOSED, EnumSet.noneOf(ApprovalState.class));
    }

    private ApprovalStateMachine() {
    }

    public static boolean canTransition(ApprovalState from, ApprovalState to) {
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(ApprovalState.class)).contains(to);
    }

    public static void assertTransition(ApprovalState from, ApprovalState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }

    /** Thrown when an illegal state transition is attempted. */
    public static final class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(ApprovalState from, ApprovalState to) {
            super("illegal approval transition: " + from + " -> " + to);
        }
    }
}
