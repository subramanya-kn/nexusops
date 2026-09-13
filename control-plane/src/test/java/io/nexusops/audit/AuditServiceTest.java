package io.nexusops.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-level guard for a bug that only ever showed up against real Postgres (via
 * {@link AuditTamperIntegrationTest}, Testcontainers): {@code verifyChain()} hashes each
 * row's {@code createdAt} using {@link java.time.Instant#toString()}. Postgres
 * {@code TIMESTAMPTZ} stores microsecond precision, so a nanosecond-precision
 * {@code Instant.now()} computed at append time hashes differently than the same row read
 * back from the database later -- every intact row reported as "tampered". This test
 * doesn't need a real database: it just asserts the invariant that fixes it (append()
 * truncates to microseconds before hashing/persisting) directly, so a regression is caught
 * offline rather than only in the Docker-gated integration test.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditRepository repository;

    @Test
    void appendTruncatesCreatedAtToMicrosecondPrecision() {
        when(repository.findFirstByOrderBySeqDesc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuditService service = new AuditService(repository);

        ArgumentCaptor<AuditEntryEntity> captor = ArgumentCaptor.forClass(AuditEntryEntity.class);
        service.append("inc-1", "corr-1", "TEST_EVENT", Map.of("k", "v"), "tester");
        verifyCaptured(captor);
    }

    private void verifyCaptured(ArgumentCaptor<AuditEntryEntity> captor) {
        org.mockito.Mockito.verify(repository).save(captor.capture());
        long nanos = captor.getValue().getCreatedAt().getNano();
        assertThat(nanos % 1000)
                .as("createdAt must be truncated to microseconds -- Postgres TIMESTAMPTZ "
                        + "can't store finer precision, so anything below it would silently "
                        + "desync the hash on the next read")
                .isZero();
    }
}
