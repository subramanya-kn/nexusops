package io.nexusops.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The full rule set loaded from {@code policy-rules.yaml}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRuleSet(List<PolicyRule> rules, PolicyDecision defaultDecision) {

    public PolicyRuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
        defaultDecision = defaultDecision == null ? PolicyDecision.REQUIRE_HUMAN : defaultDecision;
    }
}
