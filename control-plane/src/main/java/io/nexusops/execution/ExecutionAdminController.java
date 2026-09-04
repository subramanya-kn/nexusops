package io.nexusops.execution;

import io.nexusops.audit.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ADMIN-only controls: the global execution kill switch. */
@RestController
@RequestMapping("/api/admin/execution")
public class ExecutionAdminController {

    private final KillSwitch killSwitch;
    private final AuditService audit;

    public ExecutionAdminController(KillSwitch killSwitch, AuditService audit) {
        this.killSwitch = killSwitch;
        this.audit = audit;
    }

    @PostMapping("/kill-switch/engage")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> engage(@RequestParam(defaultValue = "manual") String reason,
                                      Authentication auth) {
        killSwitch.engage(reason);
        audit.append(null, null, "KILL_SWITCH_ENGAGED",
                Map.of("reason", reason), auth.getName());
        return Map.of("engaged", true, "reason", reason);
    }

    @PostMapping("/kill-switch/release")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> release(@RequestParam(defaultValue = "manual") String reason,
                                       Authentication auth) {
        killSwitch.release(reason);
        audit.append(null, null, "KILL_SWITCH_RELEASED",
                Map.of("reason", reason), auth.getName());
        return Map.of("engaged", false, "reason", reason);
    }
}
