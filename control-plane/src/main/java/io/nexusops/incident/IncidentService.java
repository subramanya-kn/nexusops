package io.nexusops.incident;

import io.nexusops.audit.AuditService;
import io.nexusops.observability.CorrelationIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Creates and queries incidents; mints the correlation id that threads the whole trace. */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository repository;
    private final AuditService audit;

    public IncidentService(IncidentRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public IncidentEntity create(String serviceRef, Environment environment, String signal) {
        String id = UUID.randomUUID().toString();
        String correlationId = "corr-" + UUID.randomUUID();
        try (var ignored = CorrelationIdContext.open(correlationId)) {
            return doCreate(id, correlationId, serviceRef, environment, signal);
        }
    }

    private IncidentEntity doCreate(String id, String correlationId, String serviceRef,
                                    Environment environment, String signal) {
        IncidentEntity incident = new IncidentEntity(id, correlationId, serviceRef, environment, signal);
        repository.save(incident);
        audit.append(id, correlationId, "INCIDENT_DETECTED", Map.of(
                "serviceRef", serviceRef,
                "environment", environment.name(),
                "signal", signal), "system:watcher");
        log.info("Incident {} created for {} ({})", id, serviceRef, correlationId);
        return incident;
    }

    @Transactional
    public void transition(String incidentId, IncidentStatus status) {
        IncidentEntity incident = get(incidentId);
        incident.transitionTo(status);
        repository.save(incident);
    }

    @Transactional(readOnly = true)
    public IncidentEntity get(String incidentId) {
        return repository.findById(incidentId)
                .orElseThrow(() -> new NoSuchElementException("incident not found: " + incidentId));
    }

    @Transactional(readOnly = true)
    public List<IncidentEntity> all() {
        return repository.findAll();
    }
}
