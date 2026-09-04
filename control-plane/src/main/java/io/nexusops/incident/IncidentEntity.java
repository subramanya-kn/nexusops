package io.nexusops.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An incident: a detected failure signal on a monitored service. Root of the aggregate
 * that a diagnosis, plan, approval, execution and verification all hang off, correlated
 * by {@link #correlationId}.
 */
@Entity
@Table(name = "incident")
public class IncidentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    @Column(name = "service_ref", nullable = false)
    private String serviceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Environment environment;

    @Column(nullable = false)
    private String signal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IncidentEntity() {
    }

    public IncidentEntity(String id, String correlationId, String serviceRef,
                          Environment environment, String signal) {
        this.id = id;
        this.correlationId = correlationId;
        this.serviceRef = serviceRef;
        this.environment = environment;
        this.signal = signal;
        this.status = IncidentStatus.DETECTED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void transitionTo(IncidentStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getServiceRef() {
        return serviceRef;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getSignal() {
        return signal;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
