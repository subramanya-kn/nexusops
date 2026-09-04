package io.nexusops.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One declarative policy rule.
 *
 * @param id       stable identifier surfaced in decisions for explainability
 * @param when     condition that must hold for the rule to fire
 * @param decision the decision this rule contributes when it fires
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyRule(String id, PolicyCondition when, PolicyDecision decision) {
}
