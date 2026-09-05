package io.nexusops.execution;

import io.nexusops.remediation.ActionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Defence-in-depth against a runaway remediation loop, independent of policy limits. */
class CapabilityRateLimiterTest {

    @Test
    void allowsUpToTheConfiguredLimit() {
        CapabilityRateLimiter limiter = new CapabilityRateLimiter(3, 3600);
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isTrue();
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isTrue();
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isTrue();
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isFalse();
    }

    @Test
    void limitsArePerCapabilityIndependently() {
        CapabilityRateLimiter limiter = new CapabilityRateLimiter(1, 3600);
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isTrue();
        assertThat(limiter.tryAcquire(ActionType.RESTART_CONTAINER)).isFalse();
        // A different capability has its own independent window.
        assertThat(limiter.tryAcquire(ActionType.SCALE_SERVICE)).isTrue();
    }
}
