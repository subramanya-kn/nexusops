package io.nexusops.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsAndRemovesOnClose() {
        try (var ignored = CorrelationIdContext.open("corr-1")) {
            assertThat(MDC.get("correlationId")).isEqualTo("corr-1");
        }
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void nestedContextRestoresOuterValueRatherThanClearing() {
        try (var outer = CorrelationIdContext.open("corr-outer")) {
            try (var inner = CorrelationIdContext.open("corr-inner")) {
                assertThat(MDC.get("correlationId")).isEqualTo("corr-inner");
            }
            // Inner closing must restore the outer's value, not wipe it.
            assertThat(MDC.get("correlationId")).isEqualTo("corr-outer");
        }
        assertThat(MDC.get("correlationId")).isNull();
    }
}
