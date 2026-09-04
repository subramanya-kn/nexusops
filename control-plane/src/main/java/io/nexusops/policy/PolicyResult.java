package io.nexusops.policy;

import java.util.List;

/**
 * A policy decision plus the rules that produced it. "The policy said no" is useless;
 * "rule prod-high-blast-radius denied this" is operable — so we always return the
 * fired rule ids and a numeric risk score.
 */
public record PolicyResult(
        PolicyDecision decision,
        List<String> firedRuleIds,
        double riskScore,
        String reason) {

    public PolicyResult {
        firedRuleIds = firedRuleIds == null ? List.of() : List.copyOf(firedRuleIds);
    }
}
