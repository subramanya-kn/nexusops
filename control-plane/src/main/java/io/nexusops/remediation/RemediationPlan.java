package io.nexusops.remediation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The inter-plane contract. Produced by the reasoning plane, validated and executed
 * (or gated) by the control plane. A closed, typed structure — never free text.
 *
 * <p>The plan is validated three ways before it can execute: JSON-schema shape,
 * the {@link ActionType} allowlist, and target-reference existence in the registry.
 */
public record RemediationPlan(
        @NotNull String incidentId,
        @NotNull String correlationId,
        String hypothesis,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotEmpty @Valid List<PlanAction> actions,
        @Valid List<Evidence> evidence,
        @NotNull BlastRadius estimatedBlastRadius,
        boolean escalate) {

    public RemediationPlan {
        actions = actions == null ? List.of() : List.copyOf(actions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** True if the plan does nothing but a single NO_OP (healthy system / escalation). */
    public boolean isNoOp() {
        return actions.size() == 1 && actions.get(0).type() == ActionType.NO_OP;
    }
}
