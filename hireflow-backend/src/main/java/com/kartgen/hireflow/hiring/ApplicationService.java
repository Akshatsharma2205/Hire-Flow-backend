package com.kartgen.hireflow.hiring;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.jobs.Job;
import com.kartgen.hireflow.jobs.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns application lifecycle rules. Every mutation is tenant-scoped and writes
 * an immutable stage-history row in the same transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    public static final List<String> STAGES = List.of(
            "NEW", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED", "WITHDRAWN");

    private static final List<String> TERMINAL_STAGES = List.of("HIRED", "REJECTED", "WITHDRAWN");

    private final ApplicationRepository applicationRepo;
    private final ApplicationSubmissionRepository submissionRepo;
    private final ApplicationStageHistoryRepository historyRepo;
    private final CandidateRepository candidateRepo;
    private final JobRepository jobRepo;
    private final com.kartgen.hireflow.profile.ResumeVersionRepository resumeVersionRepo;

    /**
     * Creates (or reuses) the active application for a candidate/job pair. Used by
     * all intake channels when the message carries job context, and by the API.
     */
    @Transactional
    public Application applyToJob(UUID orgId, UUID candidateId, UUID jobId,
                                  UUID resumeVersionId, String sourceType) {
        Candidate candidate = candidateRepo.findByIdAndOrganizationId(candidateId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
        Job job = jobRepo.findByIdAndOrganizationId(jobId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));

        if (!"OPEN".equals(job.getStatus())) {
            throw new IllegalArgumentException("Applications are only accepted for OPEN jobs");
        }

        if (resumeVersionId != null) {
            var rv = resumeVersionRepo.findByIdAndOrganizationId(resumeVersionId, orgId)
                    .orElseThrow(() -> new NoSuchElementException("Resume version not found in this organization: " + resumeVersionId));
            if (!candidateId.equals(rv.getCandidateId())) {
                throw new IllegalArgumentException("Resume version " + resumeVersionId + " does not belong to candidate " + candidateId);
            }
        }

        Instant now = Instant.now();
        Application application = applicationRepo
                .findTopByOrganizationIdAndCandidateIdAndJobIdAndStatusOrderByCreatedAtDesc(
                        orgId, candidateId, jobId, "ACTIVE")
                .orElseGet(() -> {
                    int attempt = applicationRepo.countByCandidateIdAndJobId(candidateId, jobId) + 1;
                    Application created = Application.builder()
                            .organizationId(orgId)
                            .candidateId(candidateId)
                            .jobId(jobId)
                            .attemptNumber(attempt)
                            .currentStage("NEW")
                            .status("ACTIVE")
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    applicationRepo.save(created);
                    historyRepo.save(ApplicationStageHistory.builder()
                            .applicationId(created.getId())
                            .fromStage(null)
                            .toStage("NEW")
                            .changedAt(now)
                            .note("Application created from " + (sourceType != null ? sourceType : "API"))
                            .build());
                    return created;
                });

        recordSubmission(orgId, application, resumeVersionId, sourceType);

        candidate.setLastApplicationAt(now);
        candidateRepo.save(candidate);

        log.debug("Applied candidate={} to job={} application={} attempt={}",
                candidateId, jobId, application.getId(), application.getAttemptNumber());
        return application;
    }

    @Transactional
    public ApplicationSubmission recordSubmission(UUID orgId, Application application,
                                                  UUID resumeVersionId, String sourceType) {
        String fingerprint = submissionFingerprint(orgId, application.getCandidateId(),
                application.getJobId(), resumeVersionId, sourceType);
        Optional<ApplicationSubmission> existing = submissionRepo
                .findByOrganizationIdAndSubmissionFingerprint(orgId, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        return submissionRepo.save(ApplicationSubmission.builder()
                .organizationId(orgId)
                .applicationId(application.getId())
                .resumeVersionId(resumeVersionId)
                .submissionFingerprint(fingerprint)
                .sourceType(sourceType)
                .build());
    }

    @Transactional
    public Application updateStage(UUID orgId, UUID applicationId, String targetStage,
                                   UUID changedBy, String note) {
        String stage = targetStage == null ? "" : targetStage.trim().toUpperCase();
        if (!STAGES.contains(stage)) {
            throw new IllegalArgumentException("Invalid stage: " + targetStage);
        }

        Application application = applicationRepo.findByIdAndOrganizationId(applicationId, orgId)
                .orElseThrow(() -> new NoSuchElementException("Application not found: " + applicationId));

        if (TERMINAL_STAGES.contains(application.getCurrentStage())) {
            throw new IllegalArgumentException("Application is already in terminal stage "
                    + application.getCurrentStage());
        }
        if (stage.equals(application.getCurrentStage())) {
            return application;
        }

        String previous = application.getCurrentStage();
        application.setCurrentStage(stage);
        application.setStatus(switch (stage) {
            case "HIRED" -> "HIRED";
            case "REJECTED" -> "REJECTED";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> "ACTIVE";
        });
        application.setUpdatedAt(Instant.now());
        applicationRepo.save(application);

        historyRepo.save(ApplicationStageHistory.builder()
                .applicationId(application.getId())
                .fromStage(previous)
                .toStage(stage)
                .changedBy(changedBy)
                .changedAt(Instant.now())
                .note(note)
                .build());

        log.info("Application {} moved {} -> {} by {}", applicationId, previous, stage, changedBy);
        return application;
    }

    public List<Application> listForOrg(UUID orgId, UUID jobId, UUID candidateId) {
        if (jobId != null) {
            return applicationRepo.findByOrganizationIdAndJobId(orgId, jobId);
        }
        if (candidateId != null) {
            return applicationRepo.findByOrganizationIdAndCandidateId(orgId, candidateId);
        }
        return applicationRepo.findByOrganizationId(orgId);
    }

    private String submissionFingerprint(UUID orgId, UUID candidateId, UUID jobId,
                                         UUID resumeVersionId, String sourceType) {
        String material = orgId + "|" + candidateId + "|" + jobId + "|"
                + (resumeVersionId != null ? resumeVersionId : "none") + "|"
                + (sourceType != null ? sourceType : "unknown");
        return HashUtil.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
    }
}
