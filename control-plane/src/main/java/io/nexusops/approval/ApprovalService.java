package io.nexusops.approval;

import io.micrometer.core.instrument.MeterRegistry;
import io.nexusops.audit.AuditService;
import io.nexusops.common.Json;
import io.nexusops.policy.PolicyDecision;
import io.nexusops.policy.PolicyResult;
import io.nexusops.remediation.RemediationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Owns the approval lifecycle. Every transition is audited (fail-closed: if the audit
 * append fails, the surrounding transaction rolls back and nothing proceeds).
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRecordRepository repository;
    private final AuditService audit;
    private final MeterRegistry metrics;
    private final Duration approvalTtl;

    public ApprovalService(ApprovalRecordRepository repository,
                           AuditService audit,
                           MeterRegistry metrics,
                           @Value("${nexusops.approval.ttl-seconds:900}") long ttlSeconds) {
        this.repository = repository;
        this.audit = audit;
        this.metrics = metrics;
        this.approvalTtl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Turn a gated plan into an approval record. AUTO_APPROVE lands in APPROVED,
     * REQUIRE_HUMAN in PENDING_APPROVAL, DENY in REJECTED->CLOSED.
     */
    @Transactional
    public ApprovalRecordEntity createFromDecision(RemediationPlan plan, String planId,
                                                   PolicyResult policy) {
        String executionKey = "exec:" + planId;
        Instant expiresAt = Instant.now().plus(approvalTtl);
        ApprovalRecordEntity record = new ApprovalRecordEntity(
                UUID.randomUUID().toString(), plan.incidentId(), planId, plan.correlationId(),
                policy.decision(), Json.write(policy.firedRuleIds()), policy.riskScore(),
                executionKey, expiresAt);

        audit.append(plan.incidentId(), plan.correlationId(), "APPROVAL_PROPOSED", Map.of(
                "approvalId", record.getId(),
                "decision", policy.decision().name(),
                "firedRules", policy.firedRuleIds(),
                "riskScore", policy.riskScore()), "system:policy");

        switch (policy.decision()) {
            case AUTO_APPROVE -> {
                record.transitionTo(ApprovalState.APPROVED);
                record.recordApprover("system:auto-approve");
                record.setDetail("auto-approved by policy: " + policy.firedRuleIds());
                auditState(record, "APPROVAL_AUTO_APPROVED", "system:policy");
                recordDecisionMetrics(record, "auto");
            }
            case REQUIRE_HUMAN -> {
                record.transitionTo(ApprovalState.PENDING_APPROVAL);
                record.setDetail("awaiting human approval");
                auditState(record, "APPROVAL_PENDING", "system:policy");
            }
            case DENY -> {
                record.transitionTo(ApprovalState.REJECTED);
                record.recordApprover("system:policy");
                record.setDetail("denied by policy: " + policy.firedRuleIds());
                auditState(record, "APPROVAL_DENIED", "system:policy");
                record.transitionTo(ApprovalState.CLOSED);
                recordDecisionMetrics(record, "auto");
            }
            default -> throw new IllegalStateException("unhandled decision " + policy.decision());
        }
        return repository.save(record);
    }

    /** Human approval — subject comes from the authenticated JWT, never a request param. */
    @Transactional
    public ApprovalRecordEntity approve(String approvalId, String subject, String reason) {
        ApprovalRecordEntity record = get(approvalId);
        record.transitionTo(ApprovalState.APPROVED);
        record.recordApprover(subject);
        record.setDetail("approved by " + subject + reasonSuffix(reason));
        auditState(record, "APPROVAL_APPROVED", subject);
        recordDecisionMetrics(record, "human");
        return repository.save(record);
    }

    @Transactional
    public ApprovalRecordEntity reject(String approvalId, String subject, String reason) {
        ApprovalRecordEntity record = get(approvalId);
        record.transitionTo(ApprovalState.REJECTED);
        record.recordApprover(subject);
        record.setDetail("rejected by " + subject + reasonSuffix(reason));
        auditState(record, "APPROVAL_REJECTED", subject);
        record.transitionTo(ApprovalState.CLOSED);
        recordDecisionMetrics(record, "human");
        return repository.save(record);
    }

    /** The console requires a reason for human approve/reject; older callers may omit it. */
    private static String reasonSuffix(String reason) {
        return (reason == null || reason.isBlank()) ? "" : ": " + reason;
    }

    /**
     * Feeds the "approval latency" and "policy decisions" Grafana panels: how long the
     * record sat between creation and this terminal decision, tagged by whether policy
     * decided alone ({@code auto}) or a human acted ({@code human}).
     */
    private void recordDecisionMetrics(ApprovalRecordEntity record, String mode) {
        Duration latency = Duration.between(record.getCreatedAt(), Instant.now());
        metrics.timer("nexusops.approval.latency",
                        "state", record.getState().name(), "mode", mode)
                .record(latency);
        metrics.counter("nexusops.approval.outcomes",
                        "state", record.getState().name(), "mode", mode)
                .increment();
    }

    @Transactional
    public ApprovalRecordEntity transition(String approvalId, ApprovalState next, String actor) {
        ApprovalRecordEntity record = get(approvalId);
        record.transitionTo(next);
        auditState(record, "APPROVAL_" + next.name(), actor);
        return repository.save(record);
    }

    /** Expire stale pending approvals — fail-closed: unactioned plans never execute later. */
    @Scheduled(fixedDelayString = "${nexusops.approval.sweep-ms:60000}")
    @Transactional
    public void expireStale() {
        var stale = repository.findByStateAndExpiresAtBefore(
                ApprovalState.PENDING_APPROVAL, Instant.now());
        for (ApprovalRecordEntity record : stale) {
            record.transitionTo(ApprovalState.EXPIRED);
            record.setDetail("expired without approval");
            auditState(record, "APPROVAL_EXPIRED", "system:sweeper");
            record.transitionTo(ApprovalState.CLOSED);
            recordDecisionMetrics(record, "expired");
            repository.save(record);
            log.info("Expired stale approval {}", record.getId());
        }
    }

    @Transactional(readOnly = true)
    public ApprovalRecordEntity get(String approvalId) {
        return repository.findById(approvalId)
                .orElseThrow(() -> new NoSuchElementException("approval not found: " + approvalId));
    }

    private void auditState(ApprovalRecordEntity record, String event, String actor) {
        audit.append(record.getIncidentId(), record.getCorrelationId(), event, Map.of(
                "approvalId", record.getId(),
                "state", record.getState().name(),
                "actor", actor), actor);
    }
}
