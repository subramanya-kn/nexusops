package io.nexusops.verification;

import io.nexusops.audit.AuditService;
import io.nexusops.registry.HealthProbe;
import io.nexusops.registry.RegistryProperties.ServiceDescriptor;
import io.nexusops.registry.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Re-checks the original signal after execution. Resolution rate is the metric that
 * matters, so verification probes the same health endpoint that detection used — a real
 * before/after check, not an assumption that "we ran the action, therefore it worked".
 */
@Service
public class Verifier {

    private static final Logger log = LoggerFactory.getLogger(Verifier.class);

    private final ServiceRegistry registry;
    private final HealthProbe healthProbe;
    private final VerificationResultRepository repository;
    private final AuditService audit;

    public Verifier(ServiceRegistry registry, HealthProbe healthProbe,
                    VerificationResultRepository repository, AuditService audit) {
        this.registry = registry;
        this.healthProbe = healthProbe;
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public VerificationResultEntity verify(String incidentId, String correlationId,
                                           String serviceRef, String executionId) {
        boolean resolved = registry.find(serviceRef)
                .map(ServiceDescriptor::healthUrl)
                .map(healthProbe::isHealthy)
                .orElse(false);
        String detail = resolved
                ? "signal cleared: " + serviceRef + " healthy"
                : "signal persists: " + serviceRef + " unhealthy";
        VerificationResultEntity result = repository.save(new VerificationResultEntity(
                UUID.randomUUID().toString(), incidentId, executionId, resolved, detail));
        audit.append(incidentId, correlationId, "VERIFICATION", Map.of(
                "verificationId", result.getId(),
                "resolved", resolved,
                "detail", detail), "system:verifier");
        log.info("Verification for incident {}: resolved={}", incidentId, resolved);
        return result;
    }
}
