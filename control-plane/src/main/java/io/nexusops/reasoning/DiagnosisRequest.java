package io.nexusops.reasoning;

/**
 * Request sent to the reasoning plane to diagnose an incident. Carries only descriptive
 * signal data — no credentials, no tokens. The reasoning plane investigates read-only.
 */
public record DiagnosisRequest(
        String incidentId,
        String correlationId,
        String serviceRef,
        String environment,
        String signal) {
}
