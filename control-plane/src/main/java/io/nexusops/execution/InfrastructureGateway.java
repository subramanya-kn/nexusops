package io.nexusops.execution;

/**
 * The narrow, capability-based boundary to infrastructure.
 *
 * <p><b>Invariant:</b> every method here is a fixed, parameterised operation. There is no
 * {@code exec(String)}, no shell, no command string — by construction, not by convention.
 * An LLM proposes an {@link io.nexusops.remediation.ActionType}; the executor maps that
 * enum to exactly one of these methods. There is no path from model output to an
 * arbitrary command.
 */
public interface InfrastructureGateway {

    boolean isAvailable();

    /** Capture current state of a target (JSON), for pre/post verification. */
    String captureState(String targetRef);

    CapabilityOutcome restartContainer(String containerRef, String reason);

    /** replicas is validated by the caller to be within [0, maxReplicas]. */
    CapabilityOutcome scaleService(String serviceRef, int replicas);

    /** toDigest must already have been validated against the known-good registry list. */
    CapabilityOutcome rollbackImage(String serviceRef, String toDigest);

    CapabilityOutcome clearCache(String cacheRef);

    CapabilityOutcome rotateLog(String containerRef);
}
