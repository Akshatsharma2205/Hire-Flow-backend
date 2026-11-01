package com.kartgen.hireflow.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.jobs.JobSkillRequirement;
import com.kartgen.hireflow.jobs.JobSkillRequirementRepository;
import com.kartgen.hireflow.jobs.JobVersion;
import com.kartgen.hireflow.jobs.JobVersionRepository;
import com.kartgen.hireflow.profile.CandidateProfileVersion;
import com.kartgen.hireflow.profile.CandidateProfileVersionRepository;
import com.kartgen.hireflow.profile.CandidateSkill;
import com.kartgen.hireflow.profile.CandidateSkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchServiceTest {

    private JobMatchRepository matchRepo;
    private JobSkillRequirementRepository skillReqRepo;
    private CandidateSkillRepository candidateSkillRepo;
    private CandidateRepository candidateRepo;
    private CandidateProfileVersionRepository profileVersionRepo;
    private JobVersionRepository jobVersionRepo;
    private MatchService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID jobVersionId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID profileVersionId = UUID.randomUUID();
    private final UUID javaId = UUID.randomUUID();
    private final UUID springId = UUID.randomUUID();
    private final UUID dockerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        matchRepo = mock(JobMatchRepository.class);
        skillReqRepo = mock(JobSkillRequirementRepository.class);
        candidateSkillRepo = mock(CandidateSkillRepository.class);
        candidateRepo = mock(CandidateRepository.class);
        profileVersionRepo = mock(CandidateProfileVersionRepository.class);
        jobVersionRepo = mock(JobVersionRepository.class);

        service = new MatchService(matchRepo, skillReqRepo, candidateSkillRepo, candidateRepo,
                profileVersionRepo, jobVersionRepo, new ObjectMapper());

        when(matchRepo.findByJobVersionIdAndCandidateIdAndScorerVersion(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(matchRepo.save(any(JobMatch.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void scoresFullMatchAtOneHundred() {
        stubRequirements();
        stubCandidateSkills(javaId, springId, dockerId);

        JobVersion jv = jobVersion(orgId);
        Candidate candidate = candidate(72);
        CandidateProfileVersion profile = profile();

        JobMatch match = service.computeMatch(orgId, jv, candidate, profile);

        assertThat(match.getScore()).isEqualByComparingTo("100.000");
        assertThat(match.getScorerVersion()).isEqualTo("v2");
        assertThat(match.getExplanationJson())
                .contains("\"experienceFit\":\"IN_RANGE\"")
                .contains("\"mustSkillsMatched\":2");
    }

    @Test
    void scoresPartialSkillCoverageAndUnderExperience() {
        stubRequirements();
        stubCandidateSkills(javaId);

        Candidate candidate = candidate(18);
        JobMatch match = service.computeMatch(orgId, jobVersion(orgId), candidate, profile());

        // must: 1 of 2 equally weighted = 30; preferred: 0; experience 18/36*20 = 10
        assertThat(match.getScore()).isEqualByComparingTo("40.000");
        assertThat(match.getExplanationJson())
                .contains("\"experienceFit\":\"UNDER\"")
                .contains("\"missingMustSkillIds\"");
    }

    @Test
    void capsOverqualifiedExperienceBelowFullRange() {
        stubRequirements();
        stubCandidateSkills(javaId, springId, dockerId);

        JobMatch match = service.computeMatch(
                orgId, jobVersion(orgId), candidate(180), profile());

        // must 60 + preferred 20 + over-range 15 = 95
        assertThat(match.getScore()).isEqualByComparingTo("95.000");
        assertThat(match.getExplanationJson()).contains("\"experienceFit\":\"OVER\"");
    }

    @Test
    void rejectsJobVersionFromAnotherTenant() {
        assertThatThrownBy(() -> service.computeMatch(
                orgId, jobVersion(otherOrgId), candidate(60), profile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organization");
    }

    @Test
    void reusesExistingMatchRowForSameVersion() {
        stubRequirements();
        stubCandidateSkills(javaId, springId, dockerId);

        JobMatch existing = JobMatch.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .jobVersionId(jobVersionId)
                .candidateId(candidateId)
                .profileVersionId(UUID.randomUUID())
                .scorerVersion("v2")
                .build();
        when(matchRepo.findByJobVersionIdAndCandidateIdAndScorerVersion(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        JobMatch result = service.computeMatch(
                orgId, jobVersion(orgId), candidate(60), profile());

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getScore()).isEqualByComparingTo("100.000");

        ArgumentCaptor<JobMatch> captor = ArgumentCaptor.forClass(JobMatch.class);
        verify(matchRepo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    private void stubRequirements() {
        when(skillReqRepo.findByJobVersionId(jobVersionId)).thenReturn(List.of(
                requirement(javaId, "MUST", "1.0"),
                requirement(springId, "MUST", "1.0"),
                requirement(dockerId, "PREFERRED", "1.0")
        ));
    }

    private void stubCandidateSkills(UUID... skillIds) {
        List<CandidateSkill> skills = java.util.Arrays.stream(skillIds)
                .map(id -> CandidateSkill.builder()
                        .organizationId(orgId)
                        .candidateId(candidateId)
                        .profileVersionId(profileVersionId)
                        .skillId(id)
                        .build())
                .toList();
        when(candidateSkillRepo.findByCandidateIdAndProfileVersionId(candidateId, profileVersionId))
                .thenReturn(skills);
    }

    private JobSkillRequirement requirement(UUID skillId, String priority, String weight) {
        return JobSkillRequirement.builder()
                .jobVersionId(jobVersionId)
                .skillId(skillId)
                .priority(priority)
                .weight(new BigDecimal(weight))
                .build();
    }

    private JobVersion jobVersion(UUID organizationId) {
        return JobVersion.builder()
                .id(jobVersionId)
                .organizationId(organizationId)
                .jobId(UUID.randomUUID())
                .versionNumber(1)
                .minExperienceMonths(36)
                .maxExperienceMonths(96)
                .build();
    }

    private Candidate candidate(int experienceMonths) {
        return Candidate.builder()
                .id(candidateId)
                .organizationId(orgId)
                .displayName("Test Candidate")
                .currentExperienceMonths(experienceMonths)
                .currentProfileVersionId(profileVersionId)
                .build();
    }

    private CandidateProfileVersion profile() {
        return CandidateProfileVersion.builder()
                .id(profileVersionId)
                .organizationId(orgId)
                .candidateId(candidateId)
                .profileVersion(1L)
                .build();
    }
}
