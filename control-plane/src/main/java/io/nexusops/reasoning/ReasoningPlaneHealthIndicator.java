package io.nexusops.reasoning;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Actuator indicator: is the Python reasoning plane reachable? Advisory only. */
@Component("reasoningPlane")
public class ReasoningPlaneHealthIndicator implements HealthIndicator {

    private final RestClient client;

    public ReasoningPlaneHealthIndicator(
            @Value("${nexusops.reasoning.base-url:http://localhost:8000}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public Health health() {
        try {
            client.get().uri("/health").retrieve().toBodilessEntity();
            return Health.up().withDetail("reasoning-plane", "reachable").build();
        } catch (Exception e) {
            return Health.down().withDetail("reasoning-plane", "unreachable").build();
        }
    }
}
