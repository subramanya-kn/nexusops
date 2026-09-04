package io.nexusops.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecordEntity, String> {

    Optional<ExecutionRecordEntity> findByExecutionKey(String executionKey);
}
