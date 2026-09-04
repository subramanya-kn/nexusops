package io.nexusops.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One append-only audit row. Each row carries the hash of the previous row
 * ({@link #prevHash}) plus its own {@link #hash}, forming a tamper-evident chain:
 * altering any historical row breaks every subsequent hash.
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    @Column(name = "incident_id")
    private String incidentId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_json", columnDefinition = "text", nullable = false)
    private String payloadJson;

    @Column(name = "actor")
    private String actor;

    @Column(name = "prev_hash", nullable = false, updatable = false)
    private String prevHash;

    @Column(name = "hash", nullable = false, updatable = false)
    private String hash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEntryEntity() {
    }

    public AuditEntryEntity(String incidentId, String correlationId, String eventType,
                            String payloadJson, String actor, String prevHash, String hash,
                            Instant createdAt) {
        this.incidentId = incidentId;
        this.correlationId = correlationId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.actor = actor;
        this.prevHash = prevHash;
        this.hash = hash;
        this.createdAt = createdAt;
    }

    public Long getSeq() {
        return seq;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getActor() {
        return actor;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
