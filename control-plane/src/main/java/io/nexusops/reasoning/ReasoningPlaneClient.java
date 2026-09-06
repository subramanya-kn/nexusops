package io.nexusops.reasoning;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.nexusops.remediation.ActionType;
import io.nexusops.remediation.BlastRadius;
import io.nexusops.remediation.PlanAction;
import io.nexusops.remediation.RemediationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound-only typed client to the Python reasoning plane. The control plane calls the
 * reasoning plane; never the reverse over a mutating path. Wrapped in a Resilience4j
 * circuit breaker + time limiter so a slow/broken reasoning plane degrades to a safe
 * NO_OP+escalate plan rather than hanging the control loop.
 */
@Component
public class ReasoningPlaneClient {

    private static final Logger log = LoggerFactory.getLogger(ReasoningPlaneClient.class);

    private final RestClient client;

    /**
     * {@code builder} MUST be the Spring-managed {@link RestClient.Builder} bean, not a
     * bare {@code RestClient.builder()} — the managed builder is what Spring Boot's
     * observability autoconfiguration instruments with an {@code ObservationRegistry},
     * which is what makes the W3C {@code traceparent} header propagate to the reasoning
     * plane. A manually constructed client silently opts out of that instrumentation, and
     * the promised "one trace spans both planes" property would quietly stop being true.
     */
    public ReasoningPlaneClient(RestClient.Builder builder,
                                @Value("${nexusops.reasoning.base-url:http://localhost:8000}")
                                String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "reasoning", fallbackMethod = "fallback")
    @TimeLimiter(name = "reasoning")
    public CompletableFuture<RemediationPlan> diagnose(DiagnosisRequest request) {
        return CompletableFuture.supplyAsync(() -> client.post()
                .uri("/diagnose")
                .body(request)
                .retrieve()
                .body(RemediationPlan.class));
    }

    @SuppressWarnings("unused")
    private CompletableFuture<RemediationPlan> fallback(DiagnosisRequest request, Throwable t) {
        log.warn("Reasoning plane unavailable for incident {} ({}); escalating with NO_OP",
                request.incidentId(), t.toString());
        RemediationPlan safe = new RemediationPlan(
                request.incidentId(), request.correlationId(),
                "reasoning plane unavailable: " + t.getClass().getSimpleName(),
                0.0,
                List.of(new PlanAction(ActionType.NO_OP, request.serviceRef(),
                        java.util.Map.of(), "escalated: reasoning plane unreachable")),
                List.of(), BlastRadius.LOW, true);
        return CompletableFuture.completedFuture(safe);
    }
}
