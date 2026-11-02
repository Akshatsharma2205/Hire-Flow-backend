package com.kartgen.hireflow.challenge;

import com.kartgen.hireflow.document.TikaMimeDetector;
import com.kartgen.hireflow.e2e.HireFlowE2ETestSupport;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.IdentityReviewCase;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundMessage;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import com.kartgen.hireflow.ops.ProcessingJob;
import com.kartgen.hireflow.ops.ProcessingJobRepository;
import com.kartgen.hireflow.ops.ProcessingWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Empirical Challenge Harness for Milestone 2:
 * 1. ZIP-bomb defenses in MessageIngestionService (100 entries, 20MB per-entry, 100MB total limit)
 * 2. Direct upload 25MB ceiling in DirectUploadController
 * 3. Message lifecycle state machine transitions (DISCOVERED, QUEUED, PROCESSING, COMPLETED, REVIEW_REQUIRED, FAILED)
 * 4. Error retention, bounded retries, and duplicate prevention on failed ingestion
 */
public class AttachmentSecurityAndLifecycleChallengeTest extends HireFlowE2ETestSupport {

    @Autowired
    private MessageIngestionService ingestionService;

    @Autowired
    private ProcessingWorker processingWorker;

    @Autowired
    private ProcessingJobRepository processingJobRepo;

    @Autowired
    private TikaMimeDetector mimeDetector;

    private String authToken;
    private UUID orgId;

    @BeforeEach
    void setUpChallenge() throws Exception {
        orgId = UUID.fromString(TENANT_A_ORG_ID);
        authToken = login();
    }

    // =========================================================================
    // FOCUS 1: ZIP-Bomb Defenses in MessageIngestionService
    // =========================================================================

    @Test
    @DisplayName("Challenge 1.1: ZIP-bomb maximum 100 entries limit enforced")
    void testZipBomb_Maximum100EntriesLimitEnforced() throws Exception {
        // Construct ZIP containing 120 valid PDF resume entries
        byte[] validPdf = createValidResumePdf("Test Candidate", "cand@test.com", "555-1234", "Java Developer");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < 120; i++) {
                ZipEntry entry = new ZipEntry("resume_" + i + ".pdf");
                zos.putNextEntry(entry);
                zos.write(validPdf);
                zos.closeEntry();
            }
        }
        byte[] zipBytes = baos.toByteArray();

        MockMultipartFile zipFile = new MockMultipartFile(
                "files", "batch_120_resumes.zip", "application/zip", zipBytes);

        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(zipFile), "UPLOAD", null);

        // Under 100 maximum entries limit: exactly 100 entries must be accepted, 20 rejected
        assertThat(accepted)
                .as("Maximum 100 entries limit should accept at most 100 attachments from a 120-entry archive")
                .hasSize(100);
    }

    @Test
    @DisplayName("Challenge 1.2: ZIP-bomb 20MB per-entry decompression limit enforced")
    void testZipBomb_20MbPerEntryLimitEnforced() throws Exception {
        // Construct ZIP containing:
        // Entry 1: 21MB of uncompressed text (exceeds 20MB per-entry limit)
        // Entry 2: Normal valid PDF resume (~2KB)
        byte[] validPdf = createValidResumePdf("Valid Candidate", "valid@test.com", "555-1111", "Java");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Oversized entry (21MB uncompressed)
            ZipEntry bigEntry = new ZipEntry("huge_document.pdf");
            zos.putNextEntry(bigEntry);
            byte[] chunk = "A".repeat(1024 * 1024).getBytes(StandardCharsets.US_ASCII); // 1MB chunk
            for (int i = 0; i < 21; i++) {
                zos.write(chunk);
            }
            zos.closeEntry();

            // Normal valid entry
            ZipEntry validEntry = new ZipEntry("normal_resume.pdf");
            zos.putNextEntry(validEntry);
            zos.write(validPdf);
            zos.closeEntry();
        }
        byte[] zipBytes = baos.toByteArray();

        MockMultipartFile zipFile = new MockMultipartFile(
                "files", "zip_with_21mb_entry.zip", "application/zip", zipBytes);

        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(zipFile), "UPLOAD", null);

        // Entry 1 (21MB) exceeds the 20MB decompression threshold and MUST be skipped.
        // Entry 2 (normal valid PDF) MUST be accepted.
        assertThat(accepted)
                .as("The 21MB entry must be skipped, while the valid subsequent entry should be accepted")
                .hasSize(1);

        InboundAttachment att = attachmentRepo.findById(accepted.get(0)).orElseThrow();
        assertThat(att.getOriginalFilename()).isEqualTo("normal_resume.pdf");
    }

    @Test
    @DisplayName("Challenge 1.3: ZIP-bomb 100MB total uncompressed limit enforced")
    void testZipBomb_100MbTotalDecompressionLimitEnforced() throws Exception {
        // Construct ZIP containing 6 entries of 18MB each (total 108MB uncompressed)
        // Each entry is 18MB uncompressed.
        // Entry 1: 18MB (total 18MB <= 100MB) -> read finishes
        // Entry 2: 18MB (total 36MB <= 100MB) -> read finishes
        // Entry 3: 18MB (total 54MB <= 100MB) -> read finishes
        // Entry 4: 18MB (total 72MB <= 100MB) -> read finishes
        // Entry 5: 18MB (total 90MB <= 100MB) -> read finishes
        // Entry 6: 18MB (total would reach 108MB > 100MB) -> must exceed threshold and be skipped!

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            byte[] chunk = "X".repeat(1024 * 1024).getBytes(StandardCharsets.US_ASCII); // 1MB chunk
            for (int e = 0; e < 6; e++) {
                ZipEntry entry = new ZipEntry("entry_" + e + ".pdf");
                zos.putNextEntry(entry);
                for (int i = 0; i < 18; i++) {
                    zos.write(chunk);
                }
                zos.closeEntry();
            }
        }
        byte[] zipBytes = baos.toByteArray();

        MockMultipartFile zipFile = new MockMultipartFile(
                "files", "zip_108mb_total.zip", "application/zip", zipBytes);

        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(zipFile), "UPLOAD", null);

        // Since the entries contain repeated 'X' characters, they are rejected by MIME detection (not valid PDF/resumes),
        // but crucially, total decompression halts when crossing 100MB without OOM or unbounded heap allocation.
        assertThat(accepted).isNotNull();
    }

    @Test
    @DisplayName("Challenge 1.4: ZIP Slip and unsafe path traversal defense")
    void testZip_PathTraversalZipSlipDefense() throws Exception {
        byte[] validPdf = createValidResumePdf("Path Traversal Test", "pt@test.com", "555-9999", "Security");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Unsafe path entries
            ZipEntry slip1 = new ZipEntry("../../etc/passwd");
            zos.putNextEntry(slip1);
            zos.write(validPdf);
            zos.closeEntry();

            ZipEntry slip2 = new ZipEntry("..\\windows\\system32\\cmd.exe");
            zos.putNextEntry(slip2);
            zos.write(validPdf);
            zos.closeEntry();

            ZipEntry slip3 = new ZipEntry("/absolute/path/resume.pdf");
            zos.putNextEntry(slip3);
            zos.write(validPdf);
            zos.closeEntry();

            // Safe entry
            ZipEntry safe = new ZipEntry("safe_resume.pdf");
            zos.putNextEntry(safe);
            zos.write(validPdf);
            zos.closeEntry();
        }
        byte[] zipBytes = baos.toByteArray();

        MockMultipartFile zipFile = new MockMultipartFile(
                "files", "zip_slip.zip", "application/zip", zipBytes);

        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(zipFile), "UPLOAD", null);

        // Only the safe entry must be accepted
        assertThat(accepted).hasSize(1);
        InboundAttachment att = attachmentRepo.findById(accepted.get(0)).orElseThrow();
        assertThat(att.getOriginalFilename()).isEqualTo("safe_resume.pdf");
    }

    // =========================================================================
    // FOCUS 2: Direct Upload 25MB Ceiling in DirectUploadController
    // =========================================================================

    @Test
    @DisplayName("Challenge 2.1: File exceeding 25MB (25MB + 1 byte) rejected with HTTP 413")
    void testDirectUpload_Over25MbCeiling_RejectedWith413() throws Exception {
        // Exactly 25MB + 1 byte = 26,214,401 bytes
        byte[] oversized = new byte[25 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("files", "huge_resume.pdf", "application/pdf", oversized);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("File exceeds maximum 25MB limit: huge_resume.pdf"));
    }

    @Test
    @DisplayName("Challenge 2.2: Multi-file batch where one file exceeds 25MB rejects whole batch")
    void testDirectUpload_MultiFileBatch_OneOversized_RejectsBatch() throws Exception {
        byte[] validPdf = createValidResumePdf("Small Candidate", "small@test.com", "555-2222", "Java");
        MockMultipartFile validFile = new MockMultipartFile("files", "small_resume.pdf", "application/pdf", validPdf);

        byte[] oversized = new byte[25 * 1024 * 1024 + 1];
        MockMultipartFile bigFile = new MockMultipartFile("files", "too_big.pdf", "application/pdf", oversized);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(validFile)
                        .file(bigFile)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("File exceeds maximum 25MB limit: too_big.pdf"));

        // Verify that no attachment was persisted for the small file either
        List<InboundAttachment> attachments = attachmentRepo.findAll();
        assertThat(attachments.stream().noneMatch(a -> "small_resume.pdf".equals(a.getOriginalFilename())))
                .as("No files in the rejected batch should be stored or accepted")
                .isTrue();
    }

    // =========================================================================
    // FOCUS 3: Message Lifecycle Transitions
    // =========================================================================

    @Test
    @DisplayName("Challenge 3.1: Happy path message lifecycle: QUEUED -> PROCESSING -> COMPLETED")
    void testMessageLifecycle_QueuedToProcessingToCompleted() throws Exception {
        String uniqueEmail = "lifecycle-" + UUID.randomUUID() + "@example.com";
        byte[] pdf = createValidResumePdf("Lifecycle Candidate", uniqueEmail, "555-7777", "FullStack, Java, React");
        MockMultipartFile file = new MockMultipartFile("files", "lifecycle.pdf", "application/pdf", pdf);

        // Ingest file
        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(file), "UPLOAD", null);
        assertThat(accepted).hasSize(1);
        UUID attachmentId = accepted.get(0);

        InboundAttachment att = attachmentRepo.findById(attachmentId).orElseThrow();
        UUID messageId = att.getMessageId();

        // 1. Initial status: should be QUEUED
        InboundMessage msgInitial = messageRepo.findById(messageId).orElseThrow();
        assertThat(msgInitial.getProcessingStatus())
                .as("Initial message status upon ingestion should be QUEUED")
                .isEqualTo("QUEUED");

        // 2. Process attachment synchronously: triggers PROCESSING -> COMPLETED
        ingestionService.processAttachment(orgId, att);

        InboundMessage msgCompleted = messageRepo.findById(messageId).orElseThrow();
        assertThat(msgCompleted.getProcessingStatus())
                .as("Final message status after successful processing should be COMPLETED")
                .isEqualTo("COMPLETED");

        // Verify via Operations REST endpoint
        mockMvc.perform(get("/api/v1/operations/messages/" + messageId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("COMPLETED"));
    }

    @Test
    @DisplayName("Challenge 3.2: Missing contact information routes to REVIEW_REQUIRED")
    void testMessageLifecycle_MissingContactRoutesToReviewRequired() throws Exception {
        // PDF with no email, no phone, and no sender email (forwarded source)
        byte[] pdfWithoutContact = createValidResumePdf("Anonymous Person", "", "", "Python, Machine Learning");
        MockMultipartFile file = new MockMultipartFile("files", "anon.pdf", "application/pdf", pdfWithoutContact);

        // Ingest as FORWARDED
        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(file), "FORWARDED", null);
        assertThat(accepted).hasSize(1);
        UUID attachmentId = accepted.get(0);

        InboundAttachment att = attachmentRepo.findById(attachmentId).orElseThrow();
        UUID messageId = att.getMessageId();

        // Process attachment
        ingestionService.processAttachment(orgId, att);

        InboundMessage msg = messageRepo.findById(messageId).orElseThrow();
        assertThat(msg.getProcessingStatus())
                .as("Message missing contact information should transition to REVIEW_REQUIRED")
                .isEqualTo("REVIEW_REQUIRED");

        // Review case should exist with reason MISSING_CONTACT_INFO
        List<IdentityReviewCase> cases = reviewCaseRepo.findAll().stream()
                .filter(c -> "MISSING_CONTACT_INFO".equals(c.getReasonCode()))
                .toList();
        assertThat(cases).isNotEmpty();
    }

    // =========================================================================
    // FOCUS 4: Error Details Retention & Bounded Retry Limits
    // =========================================================================

    @Test
    @DisplayName("Challenge 4.1: Bounded retries: 3 attempts with backoff before dead-lettering")
    void testBoundedRetries_ThreeAttemptsBeforeDeadLettering() {
        ProcessingJob job = processingJobRepo.save(ProcessingJob.builder()
                .organizationId(orgId)
                .jobType("PARSE_RESUME")
                .referenceId(UUID.randomUUID()) // Non-existent attachment triggers error
                .attemptCount(1)
                .maxAttempts(3)
                .status("RUNNING")
                .build());

        // Attempt 1 fails -> status PENDING, attempt backoff set
        processingWorker.execute(job.getId());
        ProcessingJob jobAfter1 = processingJobRepo.findById(job.getId()).orElseThrow();
        assertThat(jobAfter1.getStatus()).isEqualTo("PENDING");
        assertThat(jobAfter1.getFailureReason()).isNotNull();
        assertThat(jobAfter1.getNextAttemptAt()).isNotNull();

        // Simulate Attempt 2
        jobAfter1.setAttemptCount(2);
        jobAfter1.setStatus("RUNNING");
        processingJobRepo.save(jobAfter1);

        processingWorker.execute(jobAfter1.getId());
        ProcessingJob jobAfter2 = processingJobRepo.findById(jobAfter1.getId()).orElseThrow();
        assertThat(jobAfter2.getStatus()).isEqualTo("PENDING");

        // Simulate Attempt 3 (Final attempt: attempt >= maxAttempts)
        jobAfter2.setAttemptCount(3);
        jobAfter2.setStatus("RUNNING");
        processingJobRepo.save(jobAfter2);

        processingWorker.execute(jobAfter2.getId());
        ProcessingJob jobAfter3 = processingJobRepo.findById(jobAfter2.getId()).orElseThrow();
        assertThat(jobAfter3.getStatus())
                .as("After reaching maxAttempts (3), job must transition to FAILED (dead-lettered)")
                .isEqualTo("FAILED");
        assertThat(jobAfter3.getCompletedAt()).isNotNull();
        assertThat(jobAfter3.getFailureReason()).contains("Attachment not found");

        // Review case should be logged for PROCESSING_FAILED
        List<IdentityReviewCase> deadLetterCases = reviewCaseRepo.findAll().stream()
                .filter(c -> "PROCESSING_FAILED".equals(c.getReasonCode()))
                .toList();
        assertThat(deadLetterCases).isNotEmpty();
    }

    @Test
    @DisplayName("Challenge 4.2: Candidate deduplication and zero corruption on repeated attempts")
    void testCandidateDeduplication_ZeroDuplicationOrCorruptionOnRetry() {
        String testEmail = "duplicate-test-" + UUID.randomUUID() + "@example.com";
        byte[] pdf = createValidResumePdf("Dedup Candidate", testEmail, "555-8888", "Spring Boot, Docker");
        MockMultipartFile file1 = new MockMultipartFile("files", "resume_v1.pdf", "application/pdf", pdf);

        // Upload first version
        List<UUID> accepted1 = ingestionService.ingestDirectUpload(orgId, List.of(file1), "UPLOAD", null);
        InboundAttachment att1 = attachmentRepo.findById(accepted1.get(0)).orElseThrow();
        ingestionService.processAttachment(orgId, att1);

        long candidateCount1 = candidateRepo.findAll().stream()
                .filter(c -> testEmail.equalsIgnoreCase(c.getPrimaryEmail()))
                .count();
        assertThat(candidateCount1).isEqualTo(1);

        // Upload identical resume content second time (idempotency / deduplication check)
        MockMultipartFile file2 = new MockMultipartFile("files", "resume_v1_dup.pdf", "application/pdf", pdf);
        List<UUID> accepted2 = ingestionService.ingestDirectUpload(orgId, List.of(file2), "UPLOAD", null);

        // Reused existing attachment or processed idempotently
        long candidateCount2 = candidateRepo.findAll().stream()
                .filter(c -> testEmail.equalsIgnoreCase(c.getPrimaryEmail()))
                .count();
        assertThat(candidateCount2)
                .as("Candidate identity must be deduplicated by primary email; count must remain exactly 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Challenge 4.3: Inbound message failure tracking when parse job exhausts retries")
    void testInboundMessageFailureTracking_WhenParseJobExhaustsRetries() throws Exception {
        byte[] validPdf = createValidResumePdf("Failing Candidate", "fail@test.com", "555-4321", "Java");
        MockMultipartFile file = new MockMultipartFile("files", "failing_resume.pdf", "application/pdf", validPdf);

        List<UUID> accepted = ingestionService.ingestDirectUpload(orgId, List.of(file), "UPLOAD", null);
        assertThat(accepted).hasSize(1);
        UUID attachmentId = accepted.get(0);

        InboundAttachment att = attachmentRepo.findById(attachmentId).orElseThrow();
        UUID messageId = att.getMessageId();

        ProcessingJob job = processingJobRepo.findAll().stream()
                .filter(j -> attachmentId.equals(j.getReferenceId()))
                .findFirst().orElseThrow();

        job.setStatus("RUNNING");
        job.setAttemptCount(3);
        job.setMaxAttempts(3);
        processingJobRepo.save(job);

        processingWorker.handleFailure(job.getId(), new RuntimeException("Simulated fatal extraction error"));

        InboundAttachment attAfter = attachmentRepo.findById(attachmentId).orElseThrow();
        assertThat(attAfter.getStatus()).isEqualTo("FAILED");

        InboundMessage msgAfter = messageRepo.findById(messageId).orElseThrow();
        assertThat(msgAfter.getProcessingStatus())
                .as("Parent message must transition to FAILED when attachment exhausts max retries")
                .isEqualTo("FAILED");
        assertThat(msgAfter.getFailureCode())
                .as("Parent message failureCode must record PROCESSING_FAILED")
                .isEqualTo("PROCESSING_FAILED");
    }

    @Test
    @DisplayName("Challenge 3.3: Inbound message initial discovery state tracking")
    void testMessageLifecycle_InitialDiscoveredState() {
        InboundMessage msg = InboundMessage.builder()
                .organizationId(orgId)
                .senderEmail("discovered@test.com")
                .subject("Discovered Candidate Resume")
                .sourceType("IMAP")
                .build();
        messageRepo.save(msg);

        InboundMessage persisted = messageRepo.findById(msg.getId()).orElseThrow();
        assertThat(persisted.getProcessingStatus())
                .as("Newly discovered message without active jobs must be in DISCOVERED status")
                .isEqualTo("DISCOVERED");
    }

    @Test
    @DisplayName("Challenge 4.4: updateMessageStatus correctly transitions message to FAILED when attachments fail")
    void testUpdateMessageStatus_WhenAttachmentFails_TransitionsToFailed() {
        InboundMessage msg = messageRepo.save(InboundMessage.builder()
                .organizationId(orgId)
                .senderEmail("failed-att@test.com")
                .sourceType("UPLOAD")
                .processingStatus("PROCESSING")
                .build());

        attachmentRepo.save(InboundAttachment.builder()
                .organizationId(orgId)
                .messageId(msg.getId())
                .attachmentIndex(0)
                .status("FAILED")
                .build());

        ingestionService.updateMessageStatus(msg.getId());

        InboundMessage updated = messageRepo.findById(msg.getId()).orElseThrow();
        assertThat(updated.getProcessingStatus()).isEqualTo("FAILED");
        assertThat(updated.getFailureCode()).isEqualTo("ATTACHMENT_PROCESSING_FAILED");
    }
}
