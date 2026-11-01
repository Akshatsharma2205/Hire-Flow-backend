package com.kartgen.hireflow.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobVersionRepository extends JpaRepository<JobVersion, UUID> {

    List<JobVersion> findByJobIdOrderByVersionNumberDesc(UUID jobId);

    Optional<JobVersion> findTopByJobIdOrderByVersionNumberDesc(UUID jobId);

    Optional<JobVersion> findByIdAndOrganizationId(UUID id, UUID orgId);

    int countByJobId(UUID jobId);

    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM JobVersion v WHERE v.jobId = :jobId")
    int findMaxVersionNumber(@Param("jobId") UUID jobId);
}
