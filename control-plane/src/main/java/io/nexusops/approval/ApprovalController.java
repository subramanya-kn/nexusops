package io.nexusops.approval;

import io.nexusops.common.Json;
import io.nexusops.remediation.RemediationOrchestrator;
import io.nexusops.remediation.RemediationPlanEntity;
import io.nexusops.remediation.RemediationPlanRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Approval API. Approving/rejecting requires the APPROVER role, and the acting subject is
 * taken from the authenticated JWT ({@link Authentication#getName()}) — never a request
 * parameter — so approvals are non-repudiable. Read routes (including the pending-queue
 * list added for the console) are open to any authenticated principal, matching the
 * incident API's read/write split.
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalRecordRepository approvalRepository;
    private final RemediationPlanRepository planRepository;
    private final RemediationOrchestrator orchestrator;

    public ApprovalController(ApprovalService approvalService,
                              ApprovalRecordRepository approvalRepository,
                              RemediationPlanRepository planRepository,
                              RemediationOrchestrator orchestrator) {
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.planRepository = planRepository;
        this.orchestrator = orchestrator;
    }

    /** The console's Approval Queue: every PENDING_APPROVAL record across all incidents. */
    @GetMapping
    public List<ApprovalView> pending() {
        return approvalRepository.findByState(ApprovalState.PENDING_APPROVAL).stream()
                .map(this::toView)
                .toList();
    }

    @GetMapping("/{id}")
    public ApprovalView get(@PathVariable String id) {
        return toView(approvalService.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    public ApprovalView approve(@PathVariable String id,
                                @RequestParam(defaultValue = "false") boolean dryRun,
                                @RequestParam(required = false) String reason,
                                Authentication authentication) {
        ApprovalRecordEntity record =
                approvalService.approve(id, authentication.getName(), reason);
        orchestrator.executeApproved(id, dryRun);
        return toView(approvalService.get(record.getId()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    public ApprovalView reject(@PathVariable String id,
                              @RequestParam(required = false) String reason,
                              Authentication authentication) {
        return toView(approvalService.reject(id, authentication.getName(), reason));
    }

    private ApprovalView toView(ApprovalRecordEntity e) {
        RemediationPlanEntity plan = planRepository.findById(e.getPlanId()).orElse(null);
        List<String> firedRules = e.getFiredRules() == null ? List.of()
                : Json.read(e.getFiredRules(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        return new ApprovalView(e.getId(), e.getIncidentId(), e.getPlanId(),
                e.getCorrelationId(), e.getState().name(), e.getPolicyDecision().name(),
                firedRules, e.getApproverSubject(), e.getRiskScore(), e.getDetail(),
                e.getCreatedAt(), e.getExpiresAt(),
                plan == null ? null : plan.getBlastRadius().name(),
                plan == null ? null : plan.toPlan().hypothesis(),
                plan == null ? List.of() : plan.toPlan().actions());
    }

    public record ApprovalView(String id, String incidentId, String planId, String correlationId,
                               String state, String decision, List<String> firedRules,
                               String approverSubject, double riskScore, String detail,
                               Instant createdAt, Instant expiresAt, String blastRadius,
                               String hypothesis, List<?> actions) {
    }
}
