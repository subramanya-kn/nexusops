package io.nexusops.policy;

import io.nexusops.incident.Environment;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.BlastRadius;
import io.nexusops.remediation.PlanAction;
import io.nexusops.remediation.RemediationPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests against the real committed policy-rules.yaml. The engine is a pure function
 * of (plan, environment, recentIncidentCount, autoApprovalsThisHour) — no Spring context
 * needed, which keeps these fast and exact about explainability (fired rule ids).
 */
class PolicyEngineTest {

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine(new DefaultResourceLoader(), new RiskScorer(),
                "classpath:policy-rules.yaml");
        engine.load();
    }

    private static RemediationPlan plan(ActionType type, BlastRadius blast, double confidence,
                                        int actionCount) {
        PlanAction action = new PlanAction(type, "payment-svc", Map.of(), "test");
        List<PlanAction> actions = List.copyOf(java.util.Collections.nCopies(actionCount, action));
        return new RemediationPlan("inc-1", "corr-1", "test hypothesis", confidence, actions,
                List.of(), blast, false);
    }

    @Test
    void stagingAlwaysAutoApproves() {
        RemediationPlan plan = plan(ActionType.RESTART_CONTAINER, BlastRadius.LOW, 0.9, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.STAGING, 0, 0));

        assertThat(result.decision()).isEqualTo(PolicyDecision.AUTO_APPROVE);
        assertThat(result.firedRuleIds()).containsExactly("staging-auto");
    }

    @Test
    void prodRollbackRequiresHuman() {
        RemediationPlan plan = plan(ActionType.ROLLBACK_IMAGE, BlastRadius.MEDIUM, 0.9, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.PROD, 0, 0));

        assertThat(result.decision()).isEqualTo(PolicyDecision.REQUIRE_HUMAN);
        assertThat(result.firedRuleIds()).contains("prod-rollback-requires-human");
    }

    @Test
    void repeatIncidentLoopDeniesEvenWhenAnAutoApproveRuleAlsoFires() {
        // Also matches prod-restart-low-blast-auto (env=PROD, RESTART_CONTAINER, LOW) —
        // DENY must win on precedence (fail-safe), and both fired rules must be reported.
        RemediationPlan plan = plan(ActionType.RESTART_CONTAINER, BlastRadius.LOW, 0.9, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.PROD, 4, 0));

        assertThat(result.decision()).isEqualTo(PolicyDecision.DENY);
        assertThat(result.firedRuleIds())
                .contains("repeat-incident-loop", "prod-restart-low-blast-auto");
    }

    @Test
    void lowConfidenceEscalatesToHuman() {
        RemediationPlan plan = plan(ActionType.CLEAR_CACHE, BlastRadius.LOW, 0.2, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.PROD, 0, 0));

        assertThat(result.decision()).isEqualTo(PolicyDecision.REQUIRE_HUMAN);
        assertThat(result.firedRuleIds()).contains("low-confidence");
    }

    @Test
    void noMatchingRuleFallsBackToConfiguredDefault() {
        RemediationPlan plan = plan(ActionType.CLEAR_CACHE, BlastRadius.LOW, 0.9, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.PROD, 0, 0));

        assertThat(result.decision()).isEqualTo(PolicyDecision.REQUIRE_HUMAN);
        assertThat(result.firedRuleIds()).isEmpty();
        assertThat(result.reason()).contains("default");
    }

    @Test
    void everyDecisionCarriesARiskScoreAndAReason() {
        RemediationPlan plan = plan(ActionType.RESTART_CONTAINER, BlastRadius.LOW, 0.9, 1);
        PolicyResult result = engine.evaluate(
                new PolicyContext(plan, Environment.STAGING, 0, 0));

        assertThat(result.riskScore()).isBetween(0.0, 1.0);
        assertThat(result.reason()).isNotBlank();
    }
}
