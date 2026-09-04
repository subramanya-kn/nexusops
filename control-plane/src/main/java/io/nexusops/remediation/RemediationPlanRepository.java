package io.nexusops.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RemediationPlanRepository extends JpaRepository<RemediationPlanEntity, String> {

    Optional<RemediationPlanEntity> findFirstByIncidentIdOrderByCreatedAtDesc(String incidentId);
}
