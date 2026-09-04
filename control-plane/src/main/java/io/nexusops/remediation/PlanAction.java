package io.nexusops.remediation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * A single typed action within a {@link RemediationPlan}.
 *
 * @param type      one of the closed {@link ActionType} set — never free text
 * @param targetRef the service/container this acts on (must resolve in the registry)
 * @param parameters typed-per-action key/values (e.g. {@code replicas}, {@code toDigest})
 * @param rationale why the agent proposed this action (for humans / audit)
 */
public record PlanAction(
        @NotNull ActionType type,
        @NotBlank String targetRef,
        Map<String, Object> parameters,
        String rationale) {

    public PlanAction {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
