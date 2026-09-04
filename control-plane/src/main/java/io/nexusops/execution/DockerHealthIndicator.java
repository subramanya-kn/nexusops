package io.nexusops.execution;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Actuator indicator: is the Docker daemon reachable for the capability executor? */
@Component("docker")
public class DockerHealthIndicator implements HealthIndicator {

    private final InfrastructureGateway gateway;

    public DockerHealthIndicator(InfrastructureGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        return gateway.isAvailable()
                ? Health.up().withDetail("docker", "reachable").build()
                : Health.down().withDetail("docker", "unreachable").build();
    }
}
