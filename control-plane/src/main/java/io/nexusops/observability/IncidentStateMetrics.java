package io.nexusops.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nexusops.incident.IncidentRepository;
import io.nexusops.incident.IncidentStatus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers one gauge per {@link IncidentStatus}, each evaluated lazily at Prometheus
 * scrape time via a live repository count — feeds the "incidents by state" Grafana panel.
 * A gauge (not a cumulative counter) is the right shape here: it reports current state
 * distribution, not a running total of transitions.
 */
@Component
public class IncidentStateMetrics {

    private final MeterRegistry registry;
    private final IncidentRepository repository;

    public IncidentStateMetrics(MeterRegistry registry, IncidentRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostConstruct
    void registerGauges() {
        for (IncidentStatus status : IncidentStatus.values()) {
            Gauge.builder("nexusops.incidents.by_state",
                            repository, r -> r.countByStatus(status))
                    .tag("state", status.name())
                    .description("Current number of incidents in this state")
                    .register(registry);
        }
    }
}
