package io.nexusops.remediation;

/**
 * The closed set of remediation actions the system can ever execute.
 *
 * <p><b>This is the single most important design decision in the project.</b>
 * There is deliberately no {@code RUN_COMMAND}, no {@code EXEC}, no free-form variant.
 * An LLM cannot request an action outside this enum, because a plan is validated
 * against these values server-side before it can reach the executor. Adding a member
 * here is a reviewed, deliberate act — the attack surface is exactly this list.
 */
public enum ActionType {
    RESTART_CONTAINER,
    SCALE_SERVICE,
    ROLLBACK_IMAGE,
    CLEAR_CACHE,
    ROTATE_LOG,
    NO_OP
}
