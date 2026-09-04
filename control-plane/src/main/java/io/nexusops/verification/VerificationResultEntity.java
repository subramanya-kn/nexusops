package io.nexusops.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Outcome of re-checking the original incident signal after execution. */
@Entity
@Table(name = "verification_result")
public class VerificationResultEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private String id;

    @Column(name = "incident_id", nullable = false)
    private String incidentId;

    @Column(name = "execution_id")
    private String executionId;

    @Column(nullable = false)
    private boolean resolved;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VerificationResultEntity() {
    }

    public VerificationResultEntity(String id, String incidentId, String executionId,
                                    boolean resolved, String detail) {
        this.id = id;
        this.incidentId = incidentId;
        this.executionId = executionId;
        this.resolved = resolved;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getDetail() {
        return detail;
    }
}
