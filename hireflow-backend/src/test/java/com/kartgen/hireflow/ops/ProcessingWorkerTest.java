package com.kartgen.hireflow.ops;

import com.kartgen.hireflow.identity.IdentityReviewCase;
import com.kartgen.hireflow.identity.IdentityReviewCaseRepository;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import com.kartgen.hireflow.matching.MatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingWorkerTest {

    private ProcessingJobRepository jobRepo;
    private InboundAttachmentRepository attachmentRepo;
    private MessageIngestionService ingestionService;
    private MatchService matchService;
    private IdentityReviewCaseRepository reviewCaseRepo;
    private ProcessingWorker worker;

    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jobRepo = mock(ProcessingJobRepository.class);
        attachmentRepo = mock(InboundAttachmentRepository.class);
        ingestionService = mock(MessageIngestionService.class);
        matchService = mock(MatchService.class);
        reviewCaseRepo = mock(IdentityReviewCaseRepository.class);
        worker = new ProcessingWorker(jobRepo, attachmentRepo, ingestionService,
                matchService, reviewCaseRepo);

        when(jobRepo.save(any(ProcessingJob.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void completesSuccessfulParseJob() {
        ProcessingJob job = job("PARSE_RESUME", 1, 3);
        when(jobRepo.findById(jobId)).thenReturn(Optional.of(job));
        when(attachmentRepo.findByIdAndOrganizationId(attachmentId, orgId))
                .thenReturn(Optional.of(InboundAttachment.builder()
                        .id(attachmentId).organizationId(orgId).build()));

        worker.execute(jobId);

        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getCompletedAt()).isNotNull();
        verify(ingestionService).processAttachment(eq(orgId), any(InboundAttachment.class));
    }

    @Test
    void retriesWithBackoffBeforeMaxAttempts() {
        ProcessingJob job = job("PARSE_RESUME", 1, 3);
        when(jobRepo.findById(jobId)).thenReturn(Optional.of(job));
        when(attachmentRepo.findByIdAndOrganizationId(attachmentId, orgId))
                .thenReturn(Optional.of(InboundAttachment.builder()
                        .id(attachmentId).organizationId(orgId).build()));
        doThrow(new IllegalStateException("storage temporarily unavailable"))
                .when(ingestionService).processAttachment(eq(orgId), any(InboundAttachment.class));

        Instant before = Instant.now();
        worker.execute(jobId);

        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getFailureReason()).contains("storage temporarily unavailable");
        assertThat(job.getNextAttemptAt()).isAfter(before.plusSeconds(3));
        verify(reviewCaseRepo, never()).save(any(IdentityReviewCase.class));
    }

    @Test
    void deadLettersAfterMaxAttemptsAndCreatesReviewCase() {
        ProcessingJob job = job("PARSE_RESUME", 3, 3);
        when(jobRepo.findById(jobId)).thenReturn(Optional.of(job));
        when(attachmentRepo.findByIdAndOrganizationId(attachmentId, orgId))
                .thenReturn(Optional.of(InboundAttachment.builder()
                        .id(attachmentId).organizationId(orgId).status("QUEUED").build()));
        doThrow(new IllegalStateException("parser crashed"))
                .when(ingestionService).processAttachment(eq(orgId), any(InboundAttachment.class));

        worker.execute(jobId);

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.getCompletedAt()).isNotNull();

        ArgumentCaptor<InboundAttachment> attachmentCaptor =
                ArgumentCaptor.forClass(InboundAttachment.class);
        verify(attachmentRepo).save(attachmentCaptor.capture());
        assertThat(attachmentCaptor.getValue().getStatus()).isEqualTo("FAILED");

        ArgumentCaptor<IdentityReviewCase> caseCaptor =
                ArgumentCaptor.forClass(IdentityReviewCase.class);
        verify(reviewCaseRepo).save(caseCaptor.capture());
        assertThat(caseCaptor.getValue().getReasonCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(caseCaptor.getValue().getOrganizationId()).isEqualTo(orgId);
    }

    @Test
    void ignoresJobThatWasNotClaimedByThisWorker() {
        ProcessingJob job = job("PARSE_RESUME", 1, 3);
        job.setStatus("PENDING");
        when(jobRepo.findById(jobId)).thenReturn(Optional.of(job));

        worker.execute(jobId);

        verify(ingestionService, never()).processAttachment(any(), any());
        verify(jobRepo, never()).save(any());
    }

    private ProcessingJob job(String type, int attempt, int maxAttempts) {
        return ProcessingJob.builder()
                .id(jobId)
                .organizationId(orgId)
                .jobType(type)
                .referenceId(attachmentId)
                .status("RUNNING")
                .attemptCount(attempt)
                .maxAttempts(maxAttempts)
                .startedAt(Instant.now())
                .build();
    }
}
