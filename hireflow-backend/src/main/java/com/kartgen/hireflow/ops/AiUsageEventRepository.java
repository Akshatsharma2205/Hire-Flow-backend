package com.kartgen.hireflow.ops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, UUID> {
    @Query("SELECT COALESCE(SUM(a.inputTokens + a.outputTokens), 0) FROM AiUsageEvent a WHERE a.organizationId = :orgId")
    long totalTokensByOrg(UUID orgId);
}

