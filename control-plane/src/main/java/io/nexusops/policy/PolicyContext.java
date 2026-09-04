package io.nexusops.policy;

import io.nexusops.incident.Environment;
import io.nexusops.remediation.RemediationPlan;

/**
 * Everything the policy engine needs to decide, gathered by the service layer so the
 * engine itself stays a pure function of its inputs (easy to unit-test).
 *
 * @param recentIncidentCount   incidents for the same service in the loop-protection window
 * @param autoApprovalsThisHour auto-approved remediations for the service in the last hour
 */
public record PolicyContext(
        RemediationPlan plan,
        Environment environment,
        int recentIncidentCount,
        int autoApprovalsThisHour) {
}
