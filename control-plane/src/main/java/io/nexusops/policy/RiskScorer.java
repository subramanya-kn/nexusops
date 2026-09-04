package io.nexusops.policy;

import io.nexusops.incident.Environment;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.BlastRadius;
import io.nexusops.remediation.PlanAction;
import io.nexusops.remediation.RemediationPlan;
import org.springframework.stereotype.Component;

/**
 * Produces a 0..1 risk score for a plan. Not the decision-maker (rules decide) — this
 * feeds observability and gives rules a single comparable magnitude. Deterministic.
 */
@Component
public class RiskScorer {

    public double score(RemediationPlan plan, Environment environment) {
        double actionRisk = plan.actions().stream()
                .mapToDouble(RiskScorer::baseRisk)
                .max()
                .orElse(0.0);
        double blastFactor = switch (plan.estimatedBlastRadius()) {
            case LOW -> 0.2;
            case MEDIUM -> 0.5;
            case HIGH -> 0.9;
        };
        double envFactor = environment == Environment.PROD ? 1.0 : 0.5;
        double confidencePenalty = 1.0 - plan.confidence(); // low confidence => higher risk
        double countFactor = Math.min(1.0, plan.actions().size() / 5.0);

        double raw = (0.4 * actionRisk)
                + (0.25 * blastFactor)
                + (0.15 * confidencePenalty)
                + (0.10 * countFactor);
        return clamp(raw * envFactor);
    }

    private static double baseRisk(PlanAction action) {
        ActionType t = action.type();
        return switch (t) {
            case NO_OP -> 0.0;
            case ROTATE_LOG, CLEAR_CACHE -> 0.2;
            case RESTART_CONTAINER -> 0.5;
            case SCALE_SERVICE -> 0.6;
            case ROLLBACK_IMAGE -> 0.9;
        };
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
