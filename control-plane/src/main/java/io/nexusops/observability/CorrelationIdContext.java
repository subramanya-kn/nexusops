package io.nexusops.observability;

import org.slf4j.MDC;

/**
 * Threads {@code correlationId} through the log MDC for the duration of one incident
 * operation, so every log line (structured JSON — see logback-spring.xml) emitted while
 * diagnosing, gating, executing, or verifying carries the same id that's stamped on every
 * audit row. Restores the previous MDC value on close rather than blindly clearing it, so
 * nesting (e.g. {@code executeApproved} called from within {@code diagnoseAndGate}) is safe.
 *
 * <pre>{@code
 * try (var ignored = CorrelationIdContext.open(incident.getCorrelationId())) {
 *     // ... work that logs ...
 * }
 * }</pre>
 */
public final class CorrelationIdContext implements AutoCloseable {

    private static final String KEY = "correlationId";

    private final String previous;

    private CorrelationIdContext(String previous) {
        this.previous = previous;
    }

    public static CorrelationIdContext open(String correlationId) {
        String previous = MDC.get(KEY);
        MDC.put(KEY, correlationId);
        return new CorrelationIdContext(previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(KEY);
        } else {
            MDC.put(KEY, previous);
        }
    }
}
