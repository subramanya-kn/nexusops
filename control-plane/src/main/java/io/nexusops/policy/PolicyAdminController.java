package io.nexusops.policy;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ADMIN-only: reload policy rules from the configured YAML at runtime. */
@RestController
@RequestMapping("/api/admin/policy")
public class PolicyAdminController {

    private final PolicyEngine policyEngine;

    public PolicyAdminController(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reload() {
        policyEngine.reload();
        return Map.of("reloaded", true);
    }
}
