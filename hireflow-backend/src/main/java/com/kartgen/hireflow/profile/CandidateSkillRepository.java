package com.kartgen.hireflow.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CandidateSkillRepository extends JpaRepository<CandidateSkill, UUID> {

    List<CandidateSkill> findByCandidateId(UUID candidateId);

    List<CandidateSkill> findByCandidateIdAndProfileVersionId(UUID candidateId, UUID profileVersionId);

    List<CandidateSkill> findByProfileVersionId(UUID profileVersionId);

    List<CandidateSkill> findByProfileVersionIdIn(Collection<UUID> profileVersionIds);

    List<CandidateSkill> findByOrganizationIdAndSkillIdIn(UUID orgId, Collection<UUID> skillIds);

    void deleteByCandidateIdAndProfileVersionId(UUID candidateId, UUID profileVersionId);
}
