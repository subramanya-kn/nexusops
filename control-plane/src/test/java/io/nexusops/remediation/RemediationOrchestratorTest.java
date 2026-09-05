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
import io.nexusops.policy.PolicyContext;
import io.nexusops.policy.PolicyDecision;
import io.nexusops.policy.PolicyEngine;
import io.nexusops.policy.PolicyResult;
import io.nexusops.reasoning.DiagnosisRequest;
import io.nexusops.reasoning.ReasoningPlaneClient;
import io.nexusops.registry.ServiceRegistry;
import io.nexusops.verification.Verifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the full self-healing loop (DETECT already happened -> DIAGNOSE -> PLAN -> GATE ->
 * approval -> EXECUTE -> VERIFY -> RESOLVED/ESCALATE) end to end in dry-run mode, satisfying
 * the Phase 4 gate without needing a live Docker stack. Collaborators are mocked so this
 * stays a fast unit test of the orchestration logic itself, not an integration test.
 */
@ExtendWith(MockitoExtension.class)
class RemediationOrchestratorTest {

    @Mock private IncidentService incidentService;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ReasoningPlaneClient reasoningClient;
    @Mock private RemediationPlanRepository planRepository;
    @Mock private PolicyEngine policyEngine;
    @Mock private ApprovalService approvalService;
    @Mock private ApprovalRecordRepository approvalRecordRepository;
    @Mock private CapabilityExecutor executor;
    @Mock private Verifier verifier;
    @Mock private ServiceRegistry registry;
    @Mock private AuditService audit;

    private RemediationOrchestrator orchestrator;
    private IncidentEntity incident;

    @BeforeEach
    void setUp() {
        orchestrator = new RemediationOrchestrator(incidentService, incidentRepository,
                reasoningClient, planRepository, policyEngine, approvalService,
                approvalRecordRepository, executor, verifier, registry, audit);

        incident = new IncidentEntity("inc-1", "corr-1", "payment-svc", Environment.PROD,
                "OOMKilled=true");
        lenient().when(incidentService.get("inc-1")).thenReturn(incident);
        lenient().when(registry.exists("payment-svc")).thenReturn(true);
        lenient().when(incidentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(incidentRepository.findByServiceRefAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of());
        lenient().when(approvalRecordRepository.findByPolicyDecisionAndCreatedAtAfter(any(), any()))
                .thenReturn(List.of());
        lenient().when(planRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static RemediationPlan restartPlan() {
        return new RemediationPlan("inc-1", "corr-1", "OOM detected", 0.9,
                List.of(new PlanAction(ActionType.RESTART_CONTAINER, "payment-svc",
                        Map.of(), "restart to clear OOM")),
                List.of(new Evidence("get_resource_metrics", "obs-1")),
                BlastRadius.LOW, false);
    }

    @Test
    void fullIncidentResolvesEndToEndInDryRunMode() {
        RemediationPlan plan = restartPlan();
        when(reasoningClient.diagnose(any(DiagnosisRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(plan));
        when(planRepository.findById(anyString()))
                .thenReturn(Optional.of(new RemediationPlanEntity("plan-1", plan)));

        PolicyResult autoApprove = new PolicyResult(PolicyDecision.AUTO_APPROVE,
                List.of("staging-auto"), 0.2, "auto-approved");
        when(policyEngine.evaluate(any(PolicyContext.class))).thenReturn(autoApprove);

        ApprovalRecordEntity approval = new ApprovalRecordEntity("appr-1", "inc-1", "plan-1",
                "corr-1", PolicyDecision.AUTO_APPROVE, "[\"staging-auto\"]", 0.2,
                "exec:plan-1", Instant.now().plusSeconds(900));
        approval.transitionTo(ApprovalState.APPROVED);
        when(approvalService.createFromDecision(eq(plan), anyString(), eq(autoApprove)))
                .thenReturn(approval);
        when(approvalService.get("appr-1")).thenReturn(approval);

        ExecutionRecordEntity dryRunRecord = new ExecutionRecordEntity("exec-1",
                "exec:plan-1:0", "inc-1", ActionType.RESTART_CONTAINER, "payment-svc", true,
                ExecutionStatus.DRY_RUN, "restart payment-svc", "{\"running\":true}", null,
                "dry-run: no change applied");
        when(executor.execute(eq("inc-1"), eq("corr-1"), any(PlanAction.class),
                anyString(), eq(true))).thenReturn(dryRunRecord);

        ApprovalRecordEntity result = orchestrator.diagnoseAndGate("inc-1", true);

        assertThat(result.getId()).isEqualTo("appr-1");
        verify(verifier, never()).verify(any(), any(), any(), any());
        verify(approvalService).transition("appr-1", ApprovalState.EXECUTING, "system:orchestrator");
        verify(approvalService).transition("appr-1", ApprovalState.VERIFYING, "system:orchestrator");
        verify(approvalService).transition("appr-1", ApprovalState.RESOLVED, "system:orchestrator");
        verify(approvalService).transition("appr-1", ApprovalState.CLOSED, "system:orchestrator");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void killSwitchEngagedDuringExecutionEscalatesRatherThanFailingSilently() {
        RemediationPlan plan = restartPlan();
        when(reasoningClient.diagnose(any(DiagnosisRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(plan));
        when(planRepository.findById(anyString()))
                .thenReturn(Optional.of(new RemediationPlanEntity("plan-2", plan)));

        PolicyResult autoApprove = new PolicyResult(PolicyDecision.AUTO_APPROVE,
                List.of("staging-auto"), 0.2, "auto-approved");
        when(policyEngine.evaluate(any(PolicyContext.class))).thenReturn(autoApprove);

        ApprovalRecordEntity approval = new ApprovalRecordEntity("appr-2", "inc-1", "plan-2",
                "corr-1", PolicyDecision.AUTO_APPROVE, "[\"staging-auto\"]", 0.2,
                "exec:plan-2", Instant.now().plusSeconds(900));
        approval.transitionTo(ApprovalState.APPROVED);
        when(approvalService.createFromDecision(eq(plan), anyString(), eq(autoApprove)))
                .thenReturn(approval);
        when(approvalService.get("appr-2")).thenReturn(approval);

        // Kill switch engaged: the executor's own guard chain returns BLOCKED (see
        // CapabilityExecutorTest for the executor-level proof of that behaviour).
        ExecutionRecordEntity blocked = new ExecutionRecordEntity("exec-2",
                "exec:plan-2:0", "inc-1", ActionType.RESTART_CONTAINER, "payment-svc", false,
                ExecutionStatus.BLOCKED, "restart payment-svc", null, null,
                "kill switch engaged");
        when(executor.execute(eq("inc-1"), eq("corr-1"), any(PlanAction.class),
                anyString(), eq(false))).thenReturn(blocked);

        orchestrator.diagnoseAndGate("inc-1", false);

        verify(approvalService).transition("appr-2", ApprovalState.FAILED, "system:orchestrator");
        verify(approvalService).transition("appr-2", ApprovalState.ESCALATED, "system:orchestrator");
        verify(approvalService).transition("appr-2", ApprovalState.CLOSED, "system:orchestrator");
        verify(verifier, times(1)).verify(any(), any(), any(), any());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ESCALATED);
    }

    @Test
    void planTargetingUnknownServiceIsForcedToNoOpAndEscalated() {
        RemediationPlan maliciousOrHallucinated = new RemediationPlan("inc-1", "corr-1",
                "attempt to restart an unregistered target", 0.9,
                List.of(new PlanAction(ActionType.RESTART_CONTAINER, "not-a-real-service",
                        Map.of(), "hallucinated target")),
                List.of(), BlastRadius.LOW, false);
        when(reasoningClient.diagnose(any(DiagnosisRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(maliciousOrHallucinated));
        when(registry.exists("not-a-real-service")).thenReturn(false);

        ApprovalRecordEntity result = orchestrator.diagnoseAndGate("inc-1", true);

        assertThat(result).isNull();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ESCALATED);
        verify(policyEngine, never()).evaluate(any());
        verify(executor, never()).execute(any(), any(), any(), any(), anyBoolean());
    }
}
