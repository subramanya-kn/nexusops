package io.nexusops.execution;

import io.nexusops.remediation.ActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The durable record of one capability execution. The {@code executionKey} is unique —
 * this is what makes execution idempotent: a retried apply with the same key returns the
 * original record instead of acting twice.
 */
@Entity
@Table(name = "execution_record")
public class ExecutionRecordEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "execution_key", nullable = false, unique = true, updatable = false)
    private String executionKey;

    @Column(name = "incident_id", nullable = false)
    private String incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ActionType actionType;

    @Column(name = "target_ref", nullable = false)
    private String targetRef;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(name = "intended_op", columnDefinition = "text")
    private String intendedOp;

    @Column(name = "pre_state", columnDefinition = "text")
    private String preState;

    @Column(name = "post_state", columnDefinition = "text")
    private String postState;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExecutionRecordEntity() {
    }

    public ExecutionRecordEntity(String id, String executionKey, String incidentId,
                                 ActionType actionType, String targetRef, boolean dryRun,
                                 ExecutionStatus status, String intendedOp, String preState,
                                 String postState, String detail) {
        this.id = id;
        this.executionKey = executionKey;
        this.incidentId = incidentId;
        this.actionType = actionType;
        this.targetRef = targetRef;
        this.dryRun = dryRun;
        this.status = status;
        this.intendedOp = intendedOp;
        this.preState = preState;
        this.postState = postState;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public String getIntendedOp() {
        return intendedOp;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
