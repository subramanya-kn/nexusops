package io.nexusops.approval;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One test per legal transition in the approval lifecycle, plus representative illegal
 * ones. The state machine is the sole authority on what's legal — {@link ApprovalService}
 * must never be able to bypass it.
 */
class ApprovalStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @CsvSource({
            "PROPOSED, PENDING_APPROVAL",
            "PROPOSED, APPROVED",
            "PROPOSED, REJECTED",
            "PENDING_APPROVAL, APPROVED",
            "PENDING_APPROVAL, REJECTED",
            "PENDING_APPROVAL, EXPIRED",
            "APPROVED, EXECUTING",
            "EXECUTING, VERIFYING",
            "EXECUTING, FAILED",
            "VERIFYING, RESOLVED",
            "VERIFYING, FAILED",
            "FAILED, ESCALATED",
            "REJECTED, CLOSED",
            "EXPIRED, CLOSED",
            "RESOLVED, CLOSED",
            "ESCALATED, CLOSED",
    })
    void legalTransitionsAreAllowed(ApprovalState from, ApprovalState to) {
        assertThat(ApprovalStateMachine.canTransition(from, to)).isTrue();
        ApprovalStateMachine.assertTransition(from, to); // must not throw
    }

    @ParameterizedTest(name = "{0} -> {1} is illegal")
    @CsvSource({
            "PROPOSED, EXECUTING",          // cannot skip approval
            "PROPOSED, RESOLVED",           // cannot skip execution/verification
            "PENDING_APPROVAL, EXECUTING",  // cannot execute without transitioning through APPROVED
            "APPROVED, RESOLVED",           // cannot skip EXECUTING/VERIFYING
            "EXECUTING, APPROVED",          // no going backward
            "REJECTED, APPROVED",           // terminal-adjacent state cannot reopen
            "CLOSED, PROPOSED",             // CLOSED is terminal — nothing leaves it
            "CLOSED, APPROVED",
            "RESOLVED, EXECUTING",          // cannot re-execute a resolved incident
    })
    void illegalTransitionsAreRejected(ApprovalState from, ApprovalState to) {
        assertThat(ApprovalStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> ApprovalStateMachine.assertTransition(from, to))
                .isInstanceOf(ApprovalStateMachine.IllegalStateTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @Test
    void closedIsTerminal() {
        for (ApprovalState target : ApprovalState.values()) {
            assertThat(ApprovalStateMachine.canTransition(ApprovalState.CLOSED, target)).isFalse();
        }
    }

    @Test
    void everyNonTerminalStateHasAtLeastOneEgress() {
        for (ApprovalState state : ApprovalState.values()) {
            if (state == ApprovalState.CLOSED) {
                continue;
            }
            boolean hasEgress = false;
            for (ApprovalState target : ApprovalState.values()) {
                if (ApprovalStateMachine.canTransition(state, target)) {
                    hasEgress = true;
                    break;
                }
            }
            assertThat(hasEgress).as("state %s must have an egress", state).isTrue();
        }
    }
}
