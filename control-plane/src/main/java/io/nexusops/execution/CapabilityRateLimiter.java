package io.nexusops.execution;

import io.nexusops.remediation.ActionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-capability sliding-window rate limiter. Bounds how many times a given
 * {@link ActionType} may execute within the window, independent of the policy engine's
 * per-service auto-approval limit — defence in depth against a runaway remediation loop.
 */
@Component
public class CapabilityRateLimiter {

    private final int maxPerWindow;
    private final Duration window;
    private final Map<ActionType, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public CapabilityRateLimiter(
            @Value("${nexusops.execution.rate-limit.max-per-window:10}") int maxPerWindow,
            @Value("${nexusops.execution.rate-limit.window-seconds:3600}") long windowSeconds) {
        this.maxPerWindow = maxPerWindow;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** Returns true and records the hit if allowed; false if the window is saturated. */
    public synchronized boolean tryAcquire(ActionType type) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> deque = hits.computeIfAbsent(type, k -> new ConcurrentLinkedDeque<>());
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
        if (deque.size() >= maxPerWindow) {
            return false;
        }
        deque.addLast(now);
        return true;
    }
}
