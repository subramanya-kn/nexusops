package io.nexusops.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.nexusops.incident.Environment;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.BlastRadius;

/**
 * The {@code when} clause of a policy rule. A rule matches when every non-null
 * condition here holds for the plan under evaluation. Loaded from YAML.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyCondition(
        ActionType actionType,
        Environment environment,
        BlastRadius blastRadius,
        Double maxConfidenceBelow,
        Integer actionCountAbove,
        Integer repeatIncidentWithinMinutes,
        Integer repeatCountAbove,
        Integer autoApprovalsThisHourAbove) {

    public boolean matches(PolicyContext ctx) {
        if (actionType != null && ctx.plan().actions().stream().noneMatch(a -> a.type() == actionType)) {
            return false;
        }
        if (environment != null && ctx.environment() != environment) {
            return false;
        }
        if (blastRadius != null && ctx.plan().estimatedBlastRadius() != blastRadius) {
            return false;
        }
        if (maxConfidenceBelow != null && ctx.plan().confidence() >= maxConfidenceBelow) {
            return false;
        }
        if (actionCountAbove != null && ctx.plan().actions().size() <= actionCountAbove) {
            return false;
        }
        if (repeatIncidentWithinMinutes != null || repeatCountAbove != null) {
            int threshold = repeatCountAbove == null ? 0 : repeatCountAbove;
            if (ctx.recentIncidentCount() <= threshold) {
                return false;
            }
        }
        if (autoApprovalsThisHourAbove != null
                && ctx.autoApprovalsThisHour() <= autoApprovalsThisHourAbove) {
            return false;
        }
        return true;
    }
}
