package io.nexusops.verification;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationResultRepository
        extends JpaRepository<VerificationResultEntity, String> {
}
