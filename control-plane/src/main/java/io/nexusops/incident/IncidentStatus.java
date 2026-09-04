package io.nexusops.incident;

/** High-level lifecycle status of an incident (see approval state machine for detail). */
public enum IncidentStatus {
    DETECTED,
    DIAGNOSING,
    PLANNED,
    GATED,
    EXECUTING,
    VERIFYING,
    RESOLVED,
    ESCALATED,
    CLOSED
}
