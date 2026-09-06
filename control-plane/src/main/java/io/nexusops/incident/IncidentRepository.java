package io.nexusops.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface IncidentRepository extends JpaRepository<IncidentEntity, String> {

    List<IncidentEntity> findByServiceRefAndCreatedAtAfter(String serviceRef, Instant after);

    List<IncidentEntity> findByStatus(IncidentStatus status);

    long countByStatus(IncidentStatus status);
}
