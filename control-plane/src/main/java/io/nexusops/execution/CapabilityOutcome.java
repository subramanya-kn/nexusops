package io.nexusops.execution;

/**
 * Result of invoking a single infrastructure capability.
 *
 * @param ok        whether the operation succeeded
 * @param detail    human-readable result / error summary (never contains secrets)
 * @param preState  captured state before the operation (JSON)
 * @param postState captured state after the operation (JSON)
 */
public record CapabilityOutcome(boolean ok, String detail, String preState, String postState) {

    public static CapabilityOutcome ok(String detail, String preState, String postState) {
        return new CapabilityOutcome(true, detail, preState, postState);
    }

    public static CapabilityOutcome failed(String detail, String preState) {
        return new CapabilityOutcome(false, detail, preState, null);
    }
}
