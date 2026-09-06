package io.nexusops.remediation;

import io.nexusops.approval.ApprovalRecordEntity;
import io.nexusops.approval.ApprovalRecordRepository;
import io.nexusops.approval.ApprovalService;
import io.nexusops.approval.ApprovalState;
import io.nexusops.audit.AuditService;
import io.nexusops.execution.CapabilityExecutor;
import io.nexusops.execution.ExecutionRecordEntity;
import io.nexusops.execution.ExecutionStatus;
import io.nexusops.incident.Environment;
import io.nexusops.incident.IncidentEntity;
import io.nexusops.incident.IncidentRepository;
import io.nexusops.incident.IncidentService;
import io.nexusops.incident.IncidentStatus;
import io.nexusops.observability.CorrelationIdContext;
import io.nexusops.policy.PolicyContext;
import io.nexusops.policy.PolicyDecision;
import io.nexusops.policy.PolicyEngine;
import io.nexusops.policy.PolicyResult;
import io.nexusops.reasoning.DiagnosisRequest;
import io.nexusops.reasoning.ReasoningPlaneClient;
import io.nexusops.registry.ServiceRegistry;
import io.nexusops.verification.VerificationResultEntity;
import io.nexusops.verification.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the self-healing control loop:
 * DETECT (incident) → DIAGNOSE (reasoning plane) → PLAN → GATE (policy) → approval →
 * EXECUTE (capability executor) → VERIFY → RESOLVED / ESCALATE, every step audited.
 *
 * <p>The reasoning plane only proposes. This class validates the proposal server-side and
 * routes it through policy + approval before any capability can run.
 */
@Service
public class RemediationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RemediationOrchestrator.class);
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(30);

    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final ReasoningPlaneClient reasoningClient;
    private final RemediationPlanRepository planRepository;
    private final PolicyEngine policyEngine;
    private final ApprovalService approvalService;
    private final ApprovalRecordRepository approvalRepository;
    private final CapabilityExecutor executor;
    private final Verifier verifier;
    private final ServiceRegistry registry;
    private final AuditService audit;

    public RemediationOrchestrator(IncidentService incidentService,
                                   IncidentRepository incidentRepository,
                                   ReasoningPlaneClient reasoningClient,
                                   RemediationPlanRepository planRepository,
                                   PolicyEngine policyEngine,
                                   ApprovalService approvalService,
                                   ApprovalRecordRepository approvalRepository,
                                   CapabilityExecutor executor,
                                   Verifier verifier,
                                   ServiceRegistry registry,
                                   AuditService audit) {
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.reasoningClient = reasoningClient;
        this.planRepository = planRepository;
        this.policyEngine = policyEngine;
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.executor = executor;
        this.verifier = verifier;
        this.registry = registry;
        this.audit = audit;
    }

    /**
     * Diagnose an incident and gate the resulting plan. If the policy auto-approves, this
     * also executes and verifies. If it requires a human, execution waits for
     * {@link #executeApproved(String, boolean)} to be called after approval.
     */
    @Transactional
    public ApprovalRecordEntity diagnoseAndGate(String incidentId, boolean dryRun) {
        IncidentEntity incident = incidentService.get(incidentId);
        try (var ignored = CorrelationIdContext.open(incident.getCorrelationId())) {
            return doDiagnoseAndGate(incident, dryRun);
        }
    }

    private ApprovalRecordEntity doDiagnoseAndGate(IncidentEntity incident, boolean dryRun) {
        String incidentId = incident.getId();
        incident.transitionTo(IncidentStatus.DIAGNOSING);
        incidentRepository.save(incident);

        DiagnosisRequest request = new DiagnosisRequest(
                incident.getId(), incident.getCorrelationId(), incident.getServiceRef(),
                incident.getEnvironment().name(), incident.getSignal());

        RemediationPlan proposed = reasoningClient.diagnose(request).join();
        RemediationPlan plan = sanitize(proposed, incident);

        String planId = UUID.randomUUID().toString();
        planRepository.save(new RemediationPlanEntity(planId, plan));
        audit.append(incidentId, incident.getCorrelationId(), "PLAN_CREATED", Map.of(
                "planId", planId,
                "actionTypes", plan.actions().stream().map(a -> a.type().name()).toList(),
                "confidence", plan.confidence(),
                "blastRadius", plan.estimatedBlastRadius().name(),
                "escalate", plan.escalate()), "system:reasoning");
        incident.transitionTo(IncidentStatus.PLANNED);
        incidentRepository.save(incident);

        // Agent asked to escalate (e.g. hop cap hit) — do not auto-execute.
        if (plan.escalate()) {
            escalate(incident, "plan requested escalation");
            return null;
        }

        PolicyResult policy = policyEngine.evaluate(buildContext(plan, incident));
        audit.append(incidentId, incident.getCorrelationId(), "POLICY_EVALUATED", Map.of(
                "decision", policy.decision().name(),
                "firedRules", policy.firedRuleIds(),
                "riskScore", policy.riskScore()), "system:policy");
        incident.transitionTo(IncidentStatus.GATED);
        incidentRepository.save(incident);

        ApprovalRecordEntity approval = approvalService.createFromDecision(plan, planId, policy);

        if (policy.decision() == PolicyDecision.DENY) {
            incident.transitionTo(IncidentStatus.CLOSED);
            incidentRepository.save(incident);
            return approval;
        }
        if (approval.getState() == ApprovalState.APPROVED) {
            executeApproved(approval.getId(), dryRun);
        }
        return approval;
    }

    /** Execute an approved plan, then verify. Callable after human approval. */
    @Transactional
    public void executeApproved(String approvalId, boolean dryRun) {
        ApprovalRecordEntity approval = approvalService.get(approvalId);
        try (var ignored = CorrelationIdContext.open(approval.getCorrelationId())) {
            doExecuteApproved(approval, dryRun);
        }
    }

    private void doExecuteApproved(ApprovalRecordEntity approval, boolean dryRun) {
        String approvalId = approval.getId();
        if (approval.getState() != ApprovalState.APPROVED) {
            throw new IllegalStateException("approval not in APPROVED state: " + approval.getState());
        }
        RemediationPlan plan = planRepository.findById(approval.getPlanId())
                .orElseThrow().toPlan();
        IncidentEntity incident = incidentService.get(approval.getIncidentId());

        approvalService.transition(approvalId, ApprovalState.EXECUTING, "system:orchestrator");
        incident.transitionTo(IncidentStatus.EXECUTING);
        incidentRepository.save(incident);

        boolean allOk = true;
        String lastExecutionId = null;
        List<PlanAction> actions = plan.actions();
        for (int i = 0; i < actions.size(); i++) {
            String execKey = approval.getExecutionKey() + ":" + i;
            ExecutionRecordEntity rec = executor.execute(
                    incident.getId(), incident.getCorrelationId(), actions.get(i), execKey, dryRun);
            lastExecutionId = rec.getId();
            if (rec.getStatus() == ExecutionStatus.FAILED
                    || rec.getStatus() == ExecutionStatus.BLOCKED) {
                allOk = false;
            }
        }

        approvalService.transition(approvalId, ApprovalState.VERIFYING, "system:orchestrator");
        incident.transitionTo(IncidentStatus.VERIFYING);
        incidentRepository.save(incident);

        boolean resolved;
        if (dryRun) {
            resolved = allOk; // nothing was applied; success == guards passed
        } else {
            VerificationResultEntity v = verifier.verify(incident.getId(),
                    incident.getCorrelationId(), incident.getServiceRef(), lastExecutionId);
            resolved = allOk && v.isResolved();
        }

        if (resolved) {
            approvalService.transition(approvalId, ApprovalState.RESOLVED, "system:orchestrator");
            approvalService.transition(approvalId, ApprovalState.CLOSED, "system:orchestrator");
            incident.transitionTo(IncidentStatus.RESOLVED);
            incidentRepository.save(incident);
            log.info("Incident {} RESOLVED", incident.getId());
        } else {
            approvalService.transition(approvalId, ApprovalState.FAILED, "system:orchestrator");
            escalate(incident, "execution/verification failed");
            approvalService.transition(approvalId, ApprovalState.ESCALATED, "system:orchestrator");
            approvalService.transition(approvalId, ApprovalState.CLOSED, "system:orchestrator");
        }
    }

    private void escalate(IncidentEntity incident, String reason) {
        incident.transitionTo(IncidentStatus.ESCALATED);
        incidentRepository.save(incident);
        audit.append(incident.getId(), incident.getCorrelationId(), "ESCALATED",
                Map.of("reason", reason), "system:orchestrator");
        log.warn("Incident {} ESCALATED: {}", incident.getId(), reason);
    }

    /**
     * Server-side validation of the model's proposal. Anything malformed, targeting an
     * unknown service, or citing a non-allowlisted action collapses to a safe NO_OP with
     * escalate=true. The model can never widen the action set.
     */
    private RemediationPlan sanitize(RemediationPlan plan, IncidentEntity incident) {
        boolean valid = plan != null
                && plan.actions() != null
                && !plan.actions().isEmpty()
                && plan.actions().stream().allMatch(a ->
                        a.type() == ActionType.NO_OP || registry.exists(a.targetRef()));
        if (valid) {
            return plan;
        }
        log.warn("Proposed plan for incident {} failed server-side validation; forcing NO_OP",
                incident.getId());
        return new RemediationPlan(incident.getId(), incident.getCorrelationId(),
                "plan failed server-side validation", 0.0,
                List.of(new PlanAction(ActionType.NO_OP, incident.getServiceRef(),
                        Map.of(), "invalid plan rejected by control plane")),
                List.of(), BlastRadius.LOW, true);
    }

    private PolicyContext buildContext(RemediationPlan plan, IncidentEntity incident) {
        Instant windowStart = Instant.now().minus(REPEAT_WINDOW);
        int recentIncidents = incidentRepository
                .findByServiceRefAndCreatedAtAfter(incident.getServiceRef(), windowStart).size();

        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        var recentIncidentIds = incidentRepository
                .findByServiceRefAndCreatedAtAfter(incident.getServiceRef(), hourAgo)
                .stream().map(IncidentEntity::getId).toList();
        long autoApprovals = approvalRepository
                .findByPolicyDecisionAndCreatedAtAfter(PolicyDecision.AUTO_APPROVE, hourAgo)
                .stream().filter(a -> recentIncidentIds.contains(a.getIncidentId())).count();

        Environment env = registry.find(incident.getServiceRef())
                .map(d -> "PROD".equalsIgnoreCase(d.environment())
                        ? Environment.PROD : Environment.STAGING)
                .orElse(incident.getEnvironment());

        return new PolicyContext(plan, env, recentIncidents, (int) autoApprovals);
    }
}
