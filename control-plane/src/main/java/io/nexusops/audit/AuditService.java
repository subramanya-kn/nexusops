package io.nexusops.audit;

import io.nexusops.common.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Append-only, hash-chained audit log.
 *
 * <p>Every state transition in the system is recorded here. Each row's hash covers the
 * previous row's hash, so any retroactive edit is detectable by re-walking the chain
 * ({@link #verifyChain()}). Appends are serialized ({@code synchronized}) to keep the
 * chain linear on a single control-plane instance.
 */
@Service
public class AuditService {

    private static final String GENESIS = "0".repeat(64);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /** Append an event. {@code payload} is serialized to JSON and folded into the hash. */
    @Transactional
    public synchronized AuditEntryEntity append(String incidentId, String correlationId,
                                                String eventType, Object payload, String actor) {
        String payloadJson = Json.write(payload);
        String prevHash = repository.findFirstByOrderBySeqDesc()
                .map(AuditEntryEntity::getHash)
                .orElse(GENESIS);
        Instant now = Instant.now();
        String hash = computeHash(prevHash, incidentId, correlationId, eventType,
                payloadJson, actor, now);
        return repository.save(new AuditEntryEntity(incidentId, correlationId, eventType,
                payloadJson, actor, prevHash, hash, now));
    }

    /** Walk the whole chain and confirm each link. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        List<AuditEntryEntity> all = repository.findAllByOrderBySeqAsc();
        String expectedPrev = GENESIS;
        for (AuditEntryEntity e : all) {
            if (!e.getPrevHash().equals(expectedPrev)) {
                return ChainVerification.broken(e.getSeq(), "prev-hash mismatch");
            }
            String recomputed = computeHash(e.getPrevHash(), e.getIncidentId(),
                    e.getCorrelationId(), e.getEventType(), e.getPayloadJson(),
                    e.getActor(), e.getCreatedAt());
            if (!recomputed.equals(e.getHash())) {
                return ChainVerification.broken(e.getSeq(), "row hash mismatch (tampered payload)");
            }
            expectedPrev = e.getHash();
        }
        return ChainVerification.valid(all.size());
    }

    private static String computeHash(String prevHash, String incidentId, String correlationId,
                                      String eventType, String payloadJson, String actor,
                                      Instant createdAt) {
        String material = String.join("",
                prevHash,
                nz(incidentId),
                nz(correlationId),
                eventType,
                payloadJson,
                nz(actor),
                createdAt.toString());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Result of walking the audit chain. */
    public record ChainVerification(boolean valid, long entries, Long brokenAtSeq, String detail) {
        static ChainVerification valid(long entries) {
            return new ChainVerification(true, entries, null, "chain intact");
        }

        static ChainVerification broken(long seq, String detail) {
            return new ChainVerification(false, seq, seq, detail);
        }
    }
}
