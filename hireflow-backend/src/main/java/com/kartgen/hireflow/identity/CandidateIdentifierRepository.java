package com.kartgen.hireflow.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateIdentifierRepository extends JpaRepository<CandidateIdentifier, UUID> {
    List<CandidateIdentifier> findByOrganizationIdAndIdentifierTypeAndNormalizedValue(UUID orgId, String type, String value);
    List<CandidateIdentifier> findByCandidateId(UUID candidateId);
}

