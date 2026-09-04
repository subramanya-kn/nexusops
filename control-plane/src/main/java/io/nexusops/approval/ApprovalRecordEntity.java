package io.nexusops.approval;

import io.nexusops.policy.PolicyDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Durable approval record — survives restart (persisted, not in-memory). Carries the
 * policy decision and fired rules, the JWT subject that approved (non-repudiation), and
 * the base execution key that makes downstream execution idempotent.
 */
@Entity
@Table(name = "approval_record")
public class ApprovalRecordEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "incident_id", nullable = false)
    private String incidentId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_decision", nullable = false)
    private PolicyDecision policyDecision;

    @Column(name = "fired_rules", columnDefinition = "text")
    private String firedRules;

    @Column(name = "risk_score", nullable = false)
    private double riskScore;

    /** JWT subject that approved/rejected; null while pending. Never a request parameter. */
    @Column(name = "approver_subject")
    private String approverSubject;

    @Column(name = "execution_key", nullable = false, updatable = false)
    private String executionKey;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    private long version;

    protected ApprovalRecordEntity() {
    }

    public ApprovalRecordEntity(String id, String incidentId, String planId, String correlationId,
                                PolicyDecision policyDecision, String firedRules, double riskScore,
                                String executionKey, Instant expiresAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.planId = planId;
        this.correlationId = correlationId;
        this.state = ApprovalState.PROPOSED;
        this.policyDecision = policyDecision;
        this.firedRules = firedRules;
        this.riskScore = riskScore;
        this.executionKey = executionKey;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Transition guarded by {@link ApprovalStateMachine}. */
    public void transitionTo(ApprovalState next) {
        ApprovalStateMachine.assertTransition(this.state, next);
        this.state = next;
        this.updatedAt = Instant.now();
    }

    public void recordApprover(String subject) {
        this.approverSubject = subject;
        this.updatedAt = Instant.now();
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getId() {
        return id;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public String getPlanId() {
        return planId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public ApprovalState getState() {
        return state;
    }

    public PolicyDecision getPolicyDecision() {
        return policyDecision;
    }

    public String getFiredRules() {
        return firedRules;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public String getApproverSubject() {
        return approverSubject;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
