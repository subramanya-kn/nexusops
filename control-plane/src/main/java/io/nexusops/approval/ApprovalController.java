package io.nexusops.approval;

import io.nexusops.remediation.RemediationOrchestrator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approval API. Approving/rejecting requires the APPROVER role, and the acting subject is
 * taken from the authenticated JWT ({@link Authentication#getName()}) — never a request
 * parameter — so approvals are non-repudiable.
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final RemediationOrchestrator orchestrator;

    public ApprovalController(ApprovalService approvalService,
                              RemediationOrchestrator orchestrator) {
        this.approvalService = approvalService;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/{id}")
    public ApprovalView get(@PathVariable String id) {
        return ApprovalView.of(approvalService.get(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    public ApprovalView approve(@PathVariable String id,
                                @RequestParam(defaultValue = "false") boolean dryRun,
                                Authentication authentication) {
        ApprovalRecordEntity record = approvalService.approve(id, authentication.getName());
        orchestrator.executeApproved(id, dryRun);
        return ApprovalView.of(approvalService.get(record.getId()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    public ApprovalView reject(@PathVariable String id, Authentication authentication) {
        return ApprovalView.of(approvalService.reject(id, authentication.getName()));
    }

    public record ApprovalView(String id, String incidentId, String state, String decision,
                               String approverSubject, double riskScore, String detail) {
        static ApprovalView of(ApprovalRecordEntity e) {
            return new ApprovalView(e.getId(), e.getIncidentId(), e.getState().name(),
                    e.getPolicyDecision().name(), e.getApproverSubject(), e.getRiskScore(),
                    e.getDetail());
        }
    }
}
