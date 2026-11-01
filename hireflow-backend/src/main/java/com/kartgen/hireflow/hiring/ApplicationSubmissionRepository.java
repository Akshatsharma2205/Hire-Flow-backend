package com.kartgen.hireflow.hiring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationSubmissionRepository extends JpaRepository<ApplicationSubmission, UUID> {

    Optional<ApplicationSubmission> findByOrganizationIdAndSubmissionFingerprint(
            UUID organizationId, String submissionFingerprint);

    List<ApplicationSubmission> findByOrganizationIdAndApplicationId(UUID organizationId, UUID applicationId);

    List<ApplicationSubmission> findByApplicationId(UUID applicationId);
}
