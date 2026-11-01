package com.kartgen.hireflow.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeFileRepository extends JpaRepository<ResumeFile, UUID> {

    Optional<ResumeFile> findByOrganizationIdAndSha256(UUID orgId, String sha256);

    Optional<ResumeFile> findByIdAndOrganizationId(UUID id, UUID orgId);

    @Query("SELECT r.sha256 FROM ResumeFile r WHERE r.organizationId = :orgId AND r.sha256 IN :hashes")
    List<String> findExistingHashes(UUID orgId, List<String> hashes);

    long countByOrganizationId(UUID orgId);
}
