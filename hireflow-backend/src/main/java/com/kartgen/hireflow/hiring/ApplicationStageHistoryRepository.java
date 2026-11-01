package com.kartgen.hireflow.hiring;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ApplicationStageHistoryRepository extends JpaRepository<ApplicationStageHistory, UUID> {
    List<ApplicationStageHistory> findByApplicationIdOrderByChangedAtDesc(UUID applicationId);
}

