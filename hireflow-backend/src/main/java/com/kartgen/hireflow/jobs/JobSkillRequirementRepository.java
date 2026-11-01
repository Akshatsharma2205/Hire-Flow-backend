package com.kartgen.hireflow.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface JobSkillRequirementRepository extends JpaRepository<JobSkillRequirement, UUID> {
    List<JobSkillRequirement> findByJobVersionId(UUID jobVersionId);
    void deleteByJobVersionId(UUID jobVersionId);
}

