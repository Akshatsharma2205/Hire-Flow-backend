package com.kartgen.hireflow.ops;

import com.kartgen.hireflow.identity.IdentityReviewCase;
import com.kartgen.hireflow.identity.IdentityReviewCaseRepository;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import com.kartgen.hireflow.matching.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Executes one claimed processing job. Business work happens in the target
 * services' own transactions; job bookkeeping is committed separately so a
 * failure is never lost by a rollback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingWorker {

    private final ProcessingJobRepository jobRepo;
    private final InboundAttachmentRepository attachmentRepo;
    private final MessageIngestionService ingestionService;
    private final MatchService matchService;
    private final IdentityReviewCaseRepository reviewCaseRepo;

    public void execute(UUID jobId) {
        ProcessingJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || !"RUNNING".equals(job.getStatus())) {
            log.debug("Skipping job {} — not claimed or already finished", jobId);
            return;
        }

        try {
            switch (job.getJobType()) {
                case "PARSE_RESUME" -> executeParseResume(job);
                case "RECOMPUTE_MATCHES" -> executeRecomputeMatches(job);
                default -> throw new IllegalArgumentException("Unknown job type: " + job.getJobType());
            }
            markCompleted(jobId);
        } catch (Exception e) {
            log.error("Job {} ({}) failed on attempt {}/{}: {}", jobId, job.getJobType(),
                    job.getAttemptCount(), job.getMaxAttempts(), e.getMessage(), e);
            handleFailure(jobId, e);
        }
    }

    private void executeParseResume(ProcessingJob job) {
        InboundAttachment attachment = attachmentRepo
                .findByIdAndOrganizationId(job.getReferenceId(), job.getOrganizationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Attachment not found: " + job.getReferenceId()));
        ingestionService.processAttachment(job.getOrganizationId(), attachment);
    }

    private void executeRecomputeMatches(ProcessingJob job) {
        matchService.recomputeAllMatches(job.getOrganizationId(), job.getReferenceId());
    }

    @Transactional
    public void markCompleted(UUID jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            job.setFailureReason(null);
            jobRepo.save(job);
        });
    }

    @Transactional
    public void handleFailure(UUID jobId, Exception error) {
        ProcessingJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;

        String reason = truncate(error.getMessage() != null ? error.getMessage()
                : error.getClass().getSimpleName(), 3900);
        job.setFailureReason(reason);

        int attempt = job.getAttemptCount() != null ? job.getAttemptCount() : 1;
        int maxAttempts = job.getMaxAttempts() != null ? job.getMaxAttempts() : 3;

        if (attempt >= maxAttempts) {
            job.setStatus("FAILED");
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            if ("PARSE_RESUME".equals(job.getJobType())) {
                markAttachmentFailed(job);
            }

            reviewCaseRepo.save(IdentityReviewCase.builder()
                    .organizationId(job.getOrganizationId())
                    .candidateId(null)
                    .reasonCode("PROCESSING_FAILED")
                    .details("Job " + job.getJobType() + " failed after " + maxAttempts
                            + " attempts: " + reason)
                    .build());

            log.warn("Job {} dead-lettered after {} attempts", job.getId(), maxAttempts);
            return;
        }

        // Exponential backoff capped at one hour: 4s, 16s, 64s, 256s, ...
        long backoffSeconds = Math.min(3600L, (long) Math.pow(4, attempt));
        job.setStatus("PENDING");
        job.setNextAttemptAt(Instant.now().plus(backoffSeconds, ChronoUnit.SECONDS));
        jobRepo.save(job);

        log.info("Job {} will retry in {}s (attempt {}/{})",
                job.getId(), backoffSeconds, attempt, maxAttempts);
    }

    private void markAttachmentFailed(ProcessingJob job) {
        attachmentRepo.findByIdAndOrganizationId(job.getReferenceId(), job.getOrganizationId())
                .ifPresent(attachment -> {
                    attachment.setStatus("FAILED");
                    attachmentRepo.save(attachment);
                });
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
