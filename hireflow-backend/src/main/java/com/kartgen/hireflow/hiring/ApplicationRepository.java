package com.kartgen.hireflow.hiring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    List<Application> findByOrganizationId(UUID orgId);

    List<Application> findByCandidateId(UUID candidateId);

    List<Application> findByOrganizationIdAndCandidateId(UUID orgId, UUID candidateId);

    List<Application> findByOrganizationIdAndCandidateIdIn(UUID orgId, java.util.Collection<UUID> candidateIds);

    List<Application> findByOrganizationIdAndJobId(UUID orgId, UUID jobId);

    List<Application> findByJobId(UUID jobId);

    Optional<Application> findByIdAndOrganizationId(UUID id, UUID orgId);

    Optional<Application> findTopByOrganizationIdAndCandidateIdOrderByCreatedAtDesc(
            UUID orgId, UUID candidateId);

    Optional<Application> findTopByOrganizationIdAndCandidateIdAndJobIdAndStatusOrderByCreatedAtDesc(
            UUID orgId, UUID candidateId, UUID jobId, String status);

    int countByCandidateIdAndJobId(UUID candidateId, UUID jobId);

    boolean existsByOrganizationIdAndCandidateIdAndJobIdAndStatus(
            UUID orgId, UUID candidateId, UUID jobId, String status);
}
