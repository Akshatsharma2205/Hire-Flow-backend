package com.kartgen.hireflow.matching;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobMatchRepository extends JpaRepository<JobMatch, UUID> {

    Page<JobMatch> findByJobVersionIdOrderByScoreDesc(UUID jobVersionId, Pageable pageable);

    List<JobMatch> findByOrganizationIdAndCandidateId(UUID orgId, UUID candidateId);

    Optional<JobMatch> findByJobVersionIdAndCandidateIdAndScorerVersion(
            UUID jobVersionId, UUID candidateId, String scorerVersion);

    Optional<JobMatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndScoreGreaterThanEqual(UUID orgId, BigDecimal minScore);

    @Modifying
    void deleteByJobVersionId(UUID jobVersionId);

    @Modifying
    @Query("UPDATE JobMatch m SET m.status = 'STALE' WHERE m.candidateId = :candidateId " +
           "AND m.profileVersionId <> :currentProfileVersionId")
    int markStaleForCandidate(@Param("candidateId") UUID candidateId,
                              @Param("currentProfileVersionId") UUID currentProfileVersionId);
}
