package com.kartgen.hireflow.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateProfileVersionRepository extends JpaRepository<CandidateProfileVersion, UUID> {

    List<CandidateProfileVersion> findByCandidateIdOrderByProfileVersionDesc(UUID candidateId);

    Optional<CandidateProfileVersion> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<CandidateProfileVersion> findTopByCandidateIdOrderByProfileVersionDesc(UUID candidateId);
}
