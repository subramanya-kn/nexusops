package io.nexusops.approval;

import io.nexusops.policy.PolicyDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, String> {

    List<ApprovalRecordEntity> findByState(ApprovalState state);

    List<ApprovalRecordEntity> findByStateAndExpiresAtBefore(ApprovalState state, Instant before);

    List<ApprovalRecordEntity> findByPolicyDecisionAndCreatedAtAfter(
            PolicyDecision decision, Instant after);
}
