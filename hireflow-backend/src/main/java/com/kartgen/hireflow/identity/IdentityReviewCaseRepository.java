package com.kartgen.hireflow.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityReviewCaseRepository extends JpaRepository<IdentityReviewCase, UUID> {

    List<IdentityReviewCase> findByOrganizationIdAndResolutionIsNull(UUID orgId);

    long countByOrganizationIdAndResolutionIsNull(UUID orgId);

    Optional<IdentityReviewCase> findByIdAndOrganizationId(UUID id, UUID orgId);
}
