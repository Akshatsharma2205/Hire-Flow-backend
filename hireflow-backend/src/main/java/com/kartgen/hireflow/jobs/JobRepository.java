package com.kartgen.hireflow.jobs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByOrganizationId(UUID orgId);

    List<Job> findByOrganizationIdAndStatus(UUID orgId, String status);

    List<Job> findByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID orgId, String status);

    Optional<Job> findByIdAndOrganizationId(UUID id, UUID orgId);
}
