package com.kartgen.hireflow.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, UUID> {

    List<ResumeVersion> findByCandidateIdOrderByVersionNumberDesc(UUID candidateId);

    Optional<ResumeVersion> findTopByCandidateIdOrderByVersionNumberDesc(UUID candidateId);

    Optional<ResumeVersion> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ResumeVersion> findByOrganizationIdAndIdIn(UUID organizationId, java.util.Collection<UUID> ids);

    Optional<ResumeVersion> findTopByOrganizationIdAndResumeFileIdOrderByVersionNumberDesc(
            UUID organizationId, UUID resumeFileId);

    @Query("SELECT COALESCE(MAX(r.versionNumber), 0) FROM ResumeVersion r WHERE r.candidateId = :candidateId")
    int findMaxVersionNumber(@Param("candidateId") UUID candidateId);
}
