package io.nexusops.remediation;

/**
 * A citation linking a claim in the plan to a specific tool observation.
 * Every claim in a plan must be traceable to a tool call the agent actually made.
 *
 * @param toolName       the read-only tool that produced the observation
 * @param observationRef the id of the observation in the diagnosis trace
 */
public record Evidence(String toolName, String observationRef) {
}
