package io.nexusops.audit;

import io.nexusops.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the hash chain against a real Postgres instance and proves a retroactive edit is
 * detected. The tamper is applied via raw JDBC — bypassing {@link AuditService} entirely —
 * because that's the realistic threat: someone with direct DB access, not the application.
 *
 * <p>The chain spans the whole table, not per-incident, so each test truncates it first:
 * without that, a tamper committed by one test would poison {@code verifyChain()} for
 * every test that runs after it in the same container.
 */
class AuditTamperIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAuditLog() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_entry RESTART IDENTITY");
    }

    @Test
    void intactChainVerifiesClean() {
        auditService.append("inc-1", "corr-1", "TEST_EVENT_A", Map.of("k", "v1"), "tester");
        auditService.append("inc-1", "corr-1", "TEST_EVENT_B", Map.of("k", "v2"), "tester");

        AuditService.ChainVerification result = auditService.verifyChain();

        assertThat(result.valid()).isTrue();
    }

    @Test
    void retroactivelyEditedPayloadIsDetected() {
        auditService.append("inc-2", "corr-2", "TEST_EVENT_A", Map.of("k", "v1"), "tester");
        AuditEntryEntity target =
                auditService.append("inc-2", "corr-2", "TEST_EVENT_B", Map.of("k", "v2"), "tester");
        auditService.append("inc-2", "corr-2", "TEST_EVENT_C", Map.of("k", "v3"), "tester");

        assertThat(auditService.verifyChain().valid()).isTrue();

        // Tamper: rewrite one historical row's payload directly in the database, leaving
        // its stored hash untouched — exactly what an attacker with DB access would try.
        int updated = jdbcTemplate.update(
                "UPDATE audit_entry SET payload_json = ? WHERE seq = ?",
                "{\"k\":\"tampered\"}", target.getSeq());
        assertThat(updated).isEqualTo(1);

        AuditService.ChainVerification result = auditService.verifyChain();

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(target.getSeq());
    }

    @Test
    void tamperedPrevHashIsDetected() {
        auditService.append("inc-3", "corr-3", "TEST_EVENT_A", Map.of("k", "v1"), "tester");
        AuditEntryEntity second =
                auditService.append("inc-3", "corr-3", "TEST_EVENT_B", Map.of("k", "v2"), "tester");

        jdbcTemplate.update("UPDATE audit_entry SET prev_hash = ? WHERE seq = ?",
                "0".repeat(64), second.getSeq());

        AuditService.ChainVerification result = auditService.verifyChain();

        assertThat(result.valid()).isFalse();
    }
}
