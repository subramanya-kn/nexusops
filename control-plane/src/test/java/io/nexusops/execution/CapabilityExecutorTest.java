package io.nexusops.execution;

import io.nexusops.audit.AuditService;
import io.nexusops.registry.ServiceRegistry;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.PlanAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The executor is the only place infrastructure gets mutated, so every guard in its
 * fail-closed chain gets its own test: idempotency, kill switch, allowlist, rate limit,
 * dry-run. {@link InfrastructureGateway} is mocked — these are unit, not integration, tests.
 */
@ExtendWith(MockitoExtension.class)
class CapabilityExecutorTest {

    @Mock private InfrastructureGateway gateway;
    @Mock private ServiceRegistry registry;
    @Mock private ExecutionRecordRepository executions;
    @Mock private AuditService audit;

    private KillSwitch killSwitch;
    private CapabilityRateLimiter rateLimiter;
    private CapabilityExecutor executor;

    private static final PlanAction RESTART = new PlanAction(
            ActionType.RESTART_CONTAINER, "payment-svc", Map.of(), "oom");

    @BeforeEach
    void setUp() {
        killSwitch = new KillSwitch();
        rateLimiter = new CapabilityRateLimiter(10, 3600);
        executor = new CapabilityExecutor(gateway, registry, killSwitch, rateLimiter,
                executions, audit, 5);
        lenient().when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void everyExecutionIsIdempotentOnExecutionKey() {
        ExecutionRecordEntity prior = new ExecutionRecordEntity("exec-1", "key-1", "inc-1",
                ActionType.RESTART_CONTAINER, "payment-svc", false, ExecutionStatus.SUCCEEDED,
                "op", null, null, "already done");
        when(executions.findByExecutionKey("key-1")).thenReturn(Optional.of(prior));

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", RESTART, "key-1", false);

        assertThat(result).isSameAs(prior);
        verify(gateway, never()).restartContainer(any(), any());
        verify(executions, never()).save(any());
    }

    @Test
    void killSwitchBlocksExecutionWithoutTouchingInfrastructure() {
        when(executions.findByExecutionKey("key-2")).thenReturn(Optional.empty());
        killSwitch.engage("test");

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", RESTART, "key-2", false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.BLOCKED);
        verify(gateway, never()).restartContainer(any(), any());
    }

    @Test
    void unknownTargetIsBlockedBeforeDispatch() {
        when(executions.findByExecutionKey("key-3")).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(false);

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", RESTART, "key-3", false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.BLOCKED);
        verify(gateway, never()).restartContainer(any(), any());
    }

    @Test
    void rollbackToUnknownDigestIsBlocked() {
        PlanAction rollback = new PlanAction(ActionType.ROLLBACK_IMAGE, "payment-svc",
                Map.of("toDigest", "sha256:evil"), "attacker-controlled suggestion");
        when(executions.findByExecutionKey("key-4")).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(true);
        when(registry.isKnownGoodImage("sha256:evil")).thenReturn(false);

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", rollback, "key-4", false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.BLOCKED);
        verify(gateway, never()).rollbackImage(any(), any());
    }

    @Test
    void scaleOutsideBoundsIsBlocked() {
        PlanAction scale = new PlanAction(ActionType.SCALE_SERVICE, "payment-svc",
                Map.of("replicas", 999), "runaway scale request");
        when(executions.findByExecutionKey("key-5")).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(true);

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", scale, "key-5", false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.BLOCKED);
        verify(gateway, never()).scaleService(any(), any(Integer.class));
    }

    @Test
    void rateLimitBlocksAfterWindowSaturates() {
        CapabilityRateLimiter tightLimiter = new CapabilityRateLimiter(1, 3600);
        CapabilityExecutor tightExecutor = new CapabilityExecutor(gateway, registry, killSwitch,
                tightLimiter, executions, audit, 5);
        when(executions.findByExecutionKey(any())).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(true);
        when(gateway.restartContainer(any(), any()))
                .thenReturn(CapabilityOutcome.ok("done", null, null));

        ExecutionRecordEntity first = tightExecutor.execute("inc-1", "corr-1", RESTART, "key-a", false);
        ExecutionRecordEntity second = tightExecutor.execute("inc-1", "corr-1", RESTART, "key-b", false);

        assertThat(first.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(second.getStatus()).isEqualTo(ExecutionStatus.BLOCKED);
    }

    @Test
    void dryRunCapturesStateWithoutDispatching() {
        when(executions.findByExecutionKey("key-6")).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(true);
        when(gateway.captureState("payment-svc")).thenReturn("{\"running\":true}");

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", RESTART, "key-6", true);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.DRY_RUN);
        assertThat(result.isDryRun()).isTrue();
        verify(gateway, never()).restartContainer(any(), any());
        verify(gateway, times(1)).captureState("payment-svc");
    }

    @Test
    void noOpNeverReachesTheGateway() {
        PlanAction noop = new PlanAction(ActionType.NO_OP, "payment-svc", Map.of(), "healthy");
        when(executions.findByExecutionKey("key-7")).thenReturn(Optional.empty());

        ExecutionRecordEntity result = executor.execute("inc-1", "corr-1", noop, "key-7", false);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        verify(gateway, never()).isAvailable();
        verify(gateway, never()).captureState(any());
    }

    @Test
    void successfulExecutionIsAudited() {
        when(executions.findByExecutionKey("key-8")).thenReturn(Optional.empty());
        when(registry.exists("payment-svc")).thenReturn(true);
        when(gateway.restartContainer(any(), any()))
                .thenReturn(CapabilityOutcome.ok("restarted", "pre", "post"));

        executor.execute("inc-1", "corr-1", RESTART, "key-8", false);

        verify(audit, times(1)).append(any(), any(), any(), any(), any());
    }
}
