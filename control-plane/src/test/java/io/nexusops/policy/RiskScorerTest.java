package io.nexusops.policy;

import io.nexusops.incident.Environment;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.BlastRadius;
import io.nexusops.remediation.PlanAction;
import io.nexusops.remediation.RemediationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic, monotonic risk scoring — feeds policy explainability, never decides alone. */
class RiskScorerTest {

    private final RiskScorer scorer = new RiskScorer();

    private static RemediationPlan planOf(ActionType type, BlastRadius blast, double confidence) {
        return new RemediationPlan("inc-1", "corr-1", "h", confidence,
                List.of(new PlanAction(type, "svc", Map.of(), "r")), List.of(), blast, false);
    }

    @Test
    void scoreIsAlwaysInUnitRange() {
        RemediationPlan plan = planOf(ActionType.ROLLBACK_IMAGE, BlastRadius.HIGH, 0.0);
        assertThat(scorer.score(plan, Environment.PROD)).isBetween(0.0, 1.0);
    }

    @Test
    void prodIsRiskierThanStagingForTheSamePlan() {
        RemediationPlan plan = planOf(ActionType.RESTART_CONTAINER, BlastRadius.MEDIUM, 0.7);
        double prod = scorer.score(plan, Environment.PROD);
        double staging = scorer.score(plan, Environment.STAGING);
        assertThat(prod).isGreaterThan(staging);
    }

    @Test
    void higherBlastRadiusIsRiskier() {
        double low = scorer.score(planOf(ActionType.RESTART_CONTAINER, BlastRadius.LOW, 0.8),
                Environment.PROD);
        double high = scorer.score(planOf(ActionType.RESTART_CONTAINER, BlastRadius.HIGH, 0.8),
                Environment.PROD);
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void lowerConfidenceIsRiskier() {
        double confident = scorer.score(planOf(ActionType.CLEAR_CACHE, BlastRadius.LOW, 0.95),
                Environment.PROD);
        double unsure = scorer.score(planOf(ActionType.CLEAR_CACHE, BlastRadius.LOW, 0.2),
                Environment.PROD);
        assertThat(unsure).isGreaterThan(confident);
    }

    @Test
    void rollbackImageIsRiskierThanRotateLog() {
        double rollback = scorer.score(planOf(ActionType.ROLLBACK_IMAGE, BlastRadius.LOW, 0.8),
                Environment.PROD);
        double rotate = scorer.score(planOf(ActionType.ROTATE_LOG, BlastRadius.LOW, 0.8),
                Environment.PROD);
        assertThat(rollback).isGreaterThan(rotate);
    }

    @Test
    void noOpIsAlwaysLowestRisk() {
        double noop = scorer.score(planOf(ActionType.NO_OP, BlastRadius.HIGH, 0.1),
                Environment.PROD);
        double restart = scorer.score(planOf(ActionType.RESTART_CONTAINER, BlastRadius.HIGH, 0.1),
                Environment.PROD);
        assertThat(noop).isLessThan(restart);
    }
}
