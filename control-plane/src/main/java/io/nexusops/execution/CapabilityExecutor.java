package io.nexusops.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.nexusops.audit.AuditService;
import io.nexusops.common.Json;
import io.nexusops.registry.ServiceRegistry;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.PlanAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Executes typed remediation actions through the {@link InfrastructureGateway}, behind a
 * strict guard chain. This is the only place infrastructure is mutated.
 *
 * <p>Guard order (fail-closed at every step):
 * <ol>
 *   <li>Idempotency — an existing execution key returns the prior record, never re-acts.</li>
 *   <li>Kill switch — if engaged, refuse.</li>
 *   <li>Allowlist — action type is a closed enum; target must exist in the registry;
 *       rollback digests must be known-good.</li>
 *   <li>Rate limit — per-capability sliding window.</li>
 *   <li>Dry-run — return the exact intended op without applying it.</li>
 *   <li>Dispatch — map the enum to exactly one gateway method (no shell, ever).</li>
 * </ol>
 */
@Service
public class CapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(CapabilityExecutor.class);

    private final InfrastructureGateway gateway;
    private final ServiceRegistry registry;
    private final KillSwitch killSwitch;
    private final CapabilityRateLimiter rateLimiter;
    private final ExecutionRecordRepository executions;
    private final AuditService audit;
    private final MeterRegistry metrics;
    private final int maxReplicas;

    public CapabilityExecutor(InfrastructureGateway gateway,
                              ServiceRegistry registry,
                              KillSwitch killSwitch,
                              CapabilityRateLimiter rateLimiter,
                              ExecutionRecordRepository executions,
                              AuditService audit,
                              MeterRegistry metrics,
                              @Value("${nexusops.execution.max-replicas:5}") int maxReplicas) {
        this.gateway = gateway;
        this.registry = registry;
        this.killSwitch = killSwitch;
        this.rateLimiter = rateLimiter;
        this.executions = executions;
        this.audit = audit;
        this.metrics = metrics;
        this.maxReplicas = maxReplicas;
    }

    /**
     * Execute (or dry-run) one action. Idempotent on {@code executionKey}.
     *
     * <p>Bulkheaded: bounds concurrent capability executions so a burst of auto-approved
     * incidents can't pile up unbounded calls against the infrastructure gateway. A
     * rejection degrades to a {@code BLOCKED} record — same fail-closed shape as every
     * other guard in this chain — rather than propagating an exception to the caller.
     */
    @io.github.resilience4j.bulkhead.annotation.Bulkhead(name = "execution",
            fallbackMethod = "bulkheadRejected")
    public ExecutionRecordEntity execute(String incidentId, String correlationId,
                                         PlanAction action, String executionKey, boolean dryRun) {
        // 1. Idempotency.
        var existing = executions.findByExecutionKey(executionKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay for key {} -> returning prior record", executionKey);
            return existing.get();
        }

        String intendedOp = describeIntendedOp(action);

        // 2. Kill switch.
        if (killSwitch.isEngaged()) {
            return record(incidentId, correlationId, executionKey, action, dryRun,
                    ExecutionStatus.BLOCKED, intendedOp, null, null,
                    "kill switch engaged", "EXECUTION_BLOCKED");
        }

        // 3. Allowlist / validation.
        String validationError = validate(action);
        if (validationError != null) {
            return record(incidentId, correlationId, executionKey, action, dryRun,
                    ExecutionStatus.BLOCKED, intendedOp, null, null,
                    "validation failed: " + validationError, "EXECUTION_BLOCKED");
        }

        // NO_OP never touches infrastructure.
        if (action.type() == ActionType.NO_OP) {
            return record(incidentId, correlationId, executionKey, action, dryRun,
                    ExecutionStatus.SUCCEEDED, intendedOp, null, null,
                    "no-op", "EXECUTION_NOOP");
        }

        // 4. Rate limit.
        if (!rateLimiter.tryAcquire(action.type())) {
            return record(incidentId, correlationId, executionKey, action, dryRun,
                    ExecutionStatus.BLOCKED, intendedOp, null, null,
                    "rate limit exceeded for " + action.type(), "EXECUTION_BLOCKED");
        }

        // 5. Dry-run.
        if (dryRun) {
            return record(incidentId, correlationId, executionKey, action, true,
                    ExecutionStatus.DRY_RUN, intendedOp, gateway.captureState(action.targetRef()),
                    null, "dry-run: no change applied", "EXECUTION_DRY_RUN");
        }

        // 6. Dispatch — enum to a single typed gateway method.
        CapabilityOutcome outcome = dispatch(action);
        ExecutionStatus status = outcome.ok() ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        return record(incidentId, correlationId, executionKey, action, false, status,
                intendedOp, outcome.preState(), outcome.postState(), outcome.detail(),
                outcome.ok() ? "EXECUTION_SUCCEEDED" : "EXECUTION_FAILED");
    }

    private CapabilityOutcome dispatch(PlanAction action) {
        String target = action.targetRef();
        Map<String, Object> p = action.parameters();
        return switch (action.type()) {
            case RESTART_CONTAINER -> gateway.restartContainer(target, str(p, "reason", "remediation"));
            case SCALE_SERVICE -> gateway.scaleService(target, boundedReplicas(p));
            case ROLLBACK_IMAGE -> gateway.rollbackImage(target, str(p, "toDigest", ""));
            case CLEAR_CACHE -> gateway.clearCache(target);
            case ROTATE_LOG -> gateway.rotateLog(target);
            case NO_OP -> CapabilityOutcome.ok("no-op", null, null);
        };
    }

    private String validate(PlanAction action) {
        if (action.type() == ActionType.NO_OP) {
            return null;
        }
        if (!registry.exists(action.targetRef())) {
            return "unknown target: " + action.targetRef();
        }
        if (action.type() == ActionType.ROLLBACK_IMAGE) {
            String digest = str(action.parameters(), "toDigest", "");
            if (digest.isBlank()) {
                return "rollback requires 'toDigest'";
            }
            if (!registry.isKnownGoodImage(digest)) {
                return "digest not in known-good list: " + digest;
            }
        }
        if (action.type() == ActionType.SCALE_SERVICE) {
            int replicas = rawReplicas(action.parameters());
            if (replicas < 0 || replicas > maxReplicas) {
                return "replicas out of bounds [0," + maxReplicas + "]";
            }
        }
        return null;
    }

    /** The requested value, unclamped — this is what gets bounds-checked in {@link #validate}. */
    private int rawReplicas(Map<String, Object> p) {
        Object v = p.get("replicas");
        return v instanceof Number n ? n.intValue() : 1;
    }

    /**
     * Defence in depth for {@link #dispatch}: by the time this runs, {@link #validate} has
     * already rejected out-of-bounds requests, so this only guards against a race between
     * validation and dispatch (e.g. a concurrent config reload of {@code maxReplicas}).
     */
    private int boundedReplicas(Map<String, Object> p) {
        return Math.max(0, Math.min(maxReplicas, rawReplicas(p)));
    }

    private String describeIntendedOp(PlanAction action) {
        return Json.write(Map.of(
                "capability", action.type().name(),
                "targetRef", action.targetRef(),
                "parameters", action.parameters()));
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j on bulkhead rejection
    private ExecutionRecordEntity bulkheadRejected(String incidentId, String correlationId,
                                                    PlanAction action, String executionKey,
                                                    boolean dryRun, Throwable t) {
        return record(incidentId, correlationId, executionKey, action, dryRun,
                ExecutionStatus.BLOCKED, describeIntendedOp(action), null, null,
                "bulkhead full: " + t.getClass().getSimpleName(), "EXECUTION_BLOCKED");
    }

    private ExecutionRecordEntity record(String incidentId, String correlationId,
                                         String executionKey, PlanAction action, boolean dryRun,
                                         ExecutionStatus status, String intendedOp,
                                         String preState, String postState, String detail,
                                         String auditEvent) {
        ExecutionRecordEntity entity = new ExecutionRecordEntity(
                UUID.randomUUID().toString(), executionKey, incidentId, action.type(),
                action.targetRef(), dryRun, status, intendedOp, preState, postState, detail);
        ExecutionRecordEntity saved = executions.save(entity);
        metrics.counter("nexusops.execution.outcomes", "status", status.name()).increment();
        audit.append(incidentId, correlationId, auditEvent, Map.of(
                "executionId", saved.getId(),
                "executionKey", executionKey,
                "actionType", action.type().name(),
                "targetRef", action.targetRef(),
                "dryRun", dryRun,
                "status", status.name(),
                "detail", detail == null ? "" : detail), "system:executor");
        return saved;
    }

    private static String str(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : v.toString();
    }
}
