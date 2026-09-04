package io.nexusops.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditRepository extends JpaRepository<AuditEntryEntity, Long> {

    Optional<AuditEntryEntity> findFirstByOrderBySeqDesc();

    List<AuditEntryEntity> findAllByOrderBySeqAsc();

    List<AuditEntryEntity> findByIncidentIdOrderBySeqAsc(String incidentId);
}
