package io.nexusops.remediation;

import io.nexusops.common.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** Persisted form of a {@link RemediationPlan}; actions/evidence stored as JSON text. */
@Entity
@Table(name = "remediation_plan")
public class RemediationPlanEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "incident_id", nullable = false)
    private String incidentId;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(columnDefinition = "text")
    private String hypothesis;

    @Column(nullable = false)
    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "blast_radius", nullable = false)
    private BlastRadius blastRadius;

    @Column(name = "actions_json", columnDefinition = "text", nullable = false)
    private String actionsJson;

    @Column(name = "evidence_json", columnDefinition = "text", nullable = false)
    private String evidenceJson;

    @Column(nullable = false)
    private boolean escalate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RemediationPlanEntity() {
    }

    public RemediationPlanEntity(String id, RemediationPlan plan) {
        this.id = id;
        this.incidentId = plan.incidentId();
        this.correlationId = plan.correlationId();
        this.hypothesis = plan.hypothesis();
        this.confidence = plan.confidence();
        this.blastRadius = plan.estimatedBlastRadius();
        this.actionsJson = Json.write(plan.actions());
        this.evidenceJson = Json.write(plan.evidence());
        this.escalate = plan.escalate();
        this.createdAt = Instant.now();
    }

    public RemediationPlan toPlan() {
        List<PlanAction> actions = Json.read(actionsJson,
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        List<Evidence> evidence = Json.read(evidenceJson,
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        return new RemediationPlan(incidentId, correlationId, hypothesis, confidence,
                actions, evidence, blastRadius, escalate);
    }

    public String getId() {
        return id;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public BlastRadius getBlastRadius() {
        return blastRadius;
    }

    public double getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
