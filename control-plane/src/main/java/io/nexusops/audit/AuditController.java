package io.nexusops.audit;

import io.nexusops.audit.AuditService.ChainVerification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit endpoints. Chain verification is public-to-authenticated (GET /api/**). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** Walks and validates the entire hash chain; 200 if intact, 409 if tampering detected. */
    @GetMapping("/verify")
    public ResponseEntity<ChainVerification> verify() {
        ChainVerification result = auditService.verifyChain();
        return result.valid()
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(409).body(result);
    }
}
