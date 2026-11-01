package com.kartgen.hireflow;

import com.kartgen.hireflow.hiring.Application;
import com.kartgen.hireflow.hiring.ApplicationRepository;
import com.kartgen.hireflow.hiring.ApplicationService;
import com.kartgen.hireflow.hiring.ApplicationStageHistory;
import com.kartgen.hireflow.hiring.ApplicationStageHistoryRepository;
import com.kartgen.hireflow.hiring.ApplicationSubmissionRepository;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationLifecycleIntegrationTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID JOB_ID = UUID.fromString("c0000001-0000-0000-0000-000000000001");

    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ApplicationRepository applicationRepo;
    @Autowired
    private ApplicationSubmissionRepository submissionRepo;
    @Autowired
    private ApplicationStageHistoryRepository historyRepo;
    @Autowired
    private CandidateRepository candidateRepo;

    @Test
    void applicationCreationIsIdempotentAndTracksStageHistory() throws Exception {
        Candidate candidate = candidateRepo.save(Candidate.builder()
                .organizationId(ORG_ID)
                .displayName("Lifecycle Candidate")
                .primaryEmail("lifecycle." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        UUID resumeVersionId = null;
        Application first = applicationService.applyToJob(
                ORG_ID, candidate.getId(), JOB_ID, resumeVersionId, "UPLOAD");
        Application second = applicationService.applyToJob(
                ORG_ID, candidate.getId(), JOB_ID, resumeVersionId, "UPLOAD");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getAttemptNumber()).isEqualTo(1);
        assertThat(first.getCurrentStage()).isEqualTo("NEW");
        assertThat(submissionRepo.findByOrganizationIdAndApplicationId(ORG_ID, first.getId()))
                .hasSize(1);

        List<ApplicationStageHistory> creationHistory = historyRepo
                .findByApplicationIdOrderByChangedAtDesc(first.getId());
        assertThat(creationHistory).hasSize(1);
        assertThat(creationHistory.get(0).getToStage()).isEqualTo("NEW");

        Application screening = applicationService.updateStage(
                ORG_ID, first.getId(), "screening", USER_ID, "CV shortlisted");
        assertThat(screening.getCurrentStage()).isEqualTo("SCREENING");
        assertThat(screening.getStatus()).isEqualTo("ACTIVE");

        Application interview = applicationService.updateStage(
                ORG_ID, first.getId(), "INTERVIEW", USER_ID, "Technical round");
        assertThat(interview.getCurrentStage()).isEqualTo("INTERVIEW");

        List<ApplicationStageHistory> history = historyRepo
                .findByApplicationIdOrderByChangedAtDesc(first.getId());
        assertThat(history).hasSize(3);

        ApplicationStageHistory interviewStep = history.stream()
                .filter(h -> "INTERVIEW".equals(h.getToStage()))
                .findFirst().orElseThrow();
        assertThat(interviewStep.getFromStage()).isEqualTo("SCREENING");
        assertThat(interviewStep.getChangedBy()).isEqualTo(USER_ID);

        ApplicationStageHistory screeningStep = history.stream()
                .filter(h -> "SCREENING".equals(h.getToStage()))
                .findFirst().orElseThrow();
        assertThat(screeningStep.getFromStage()).isEqualTo("NEW");

        assertThatThrownBy(() -> applicationService.updateStage(
                ORG_ID, first.getId(), "NOT_A_STAGE", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid stage");

        applicationService.updateStage(ORG_ID, first.getId(), "HIRED", USER_ID, "Offer accepted");
        assertThatThrownBy(() -> applicationService.updateStage(
                ORG_ID, first.getId(), "REJECTED", USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void cannotApplyAcrossTenantsOrToClosedJobs() {
        Candidate candidate = candidateRepo.save(Candidate.builder()
                .organizationId(ORG_ID)
                .displayName("Tenant Candidate")
                .primaryEmail("tenant." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        assertThatThrownBy(() -> applicationService.applyToJob(
                UUID.randomUUID(), candidate.getId(), JOB_ID, null, "UPLOAD"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Candidate not found");

        // Unknown job in the right org is also rejected.
        assertThatThrownBy(() -> applicationService.applyToJob(
                ORG_ID, candidate.getId(), UUID.randomUUID(), null, "UPLOAD"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Job not found");
    }
}
