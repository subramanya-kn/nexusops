package io.nexusops.incident;

import io.nexusops.approval.ApprovalRecordEntity;
import io.nexusops.remediation.RemediationOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Incident API. Reads are open to any authenticated principal (including the read-only
 * reasoning-plane client); mutations require OPERATOR and are enforced at the method level.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final RemediationOrchestrator orchestrator;

    public IncidentController(IncidentService incidentService,
                              RemediationOrchestrator orchestrator) {
        this.incidentService = incidentService;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<IncidentView> list() {
        return incidentService.all().stream().map(IncidentView::of).toList();
    }

    @GetMapping("/{id}")
    public IncidentView get(@PathVariable String id) {
        return IncidentView.of(incidentService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView create(@Valid @RequestBody CreateIncidentRequest request) {
        IncidentEntity incident = incidentService.create(
                request.serviceRef(), request.environment(), request.signal());
        return IncidentView.of(incident);
    }

    /** Trigger the diagnose→gate loop. {@code dryRun=true} runs end-to-end without mutating. */
    @PostMapping("/{id}/diagnose")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<DiagnoseResponse> diagnose(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        ApprovalRecordEntity approval = orchestrator.diagnoseAndGate(id, dryRun);
        String approvalId = approval == null ? null : approval.getId();
        String state = approval == null ? "ESCALATED" : approval.getState().name();
        return ResponseEntity.accepted().body(new DiagnoseResponse(id, approvalId, state));
    }

    public record CreateIncidentRequest(
            @NotBlank String serviceRef,
            @NotNull Environment environment,
            @NotBlank String signal) {
    }

    public record DiagnoseResponse(String incidentId, String approvalId, String state) {
    }

    public record IncidentView(String id, String correlationId, String serviceRef,
                               String environment, String signal, String status) {
        static IncidentView of(IncidentEntity e) {
            return new IncidentView(e.getId(), e.getCorrelationId(), e.getServiceRef(),
                    e.getEnvironment().name(), e.getSignal(), e.getStatus().name());
        }
    }
}
