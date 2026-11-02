package com.kartgen.hireflow.mail.service;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.document.*;
import com.kartgen.hireflow.extraction.ResumeExtractionPort;
import com.kartgen.hireflow.extraction.dto.ExtractedResumeSchema;
import com.kartgen.hireflow.hiring.ApplicationService;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateIdentityService;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.identity.IdentityReviewCase;
import com.kartgen.hireflow.identity.IdentityReviewCaseRepository;
import com.kartgen.hireflow.rules.EmailRuleEngineService;
import com.kartgen.hireflow.jobs.Job;
import com.kartgen.hireflow.jobs.JobRepository;
import com.kartgen.hireflow.jobs.JobVersionRepository;
import com.kartgen.hireflow.mail.model.*;
import com.kartgen.hireflow.matching.MatchService;
import com.kartgen.hireflow.ops.ProcessingJob;
import com.kartgen.hireflow.ops.ProcessingJobRepository;
import com.kartgen.hireflow.profile.CandidateProfileVersionRepository;
import com.kartgen.hireflow.profile.LivingProfileService;
import com.kartgen.hireflow.profile.ResumeVersion;
import com.kartgen.hireflow.profile.ResumeVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageIngestionService {

    private static final List<String> ACTIVE_ATTACHMENT_STATUSES =
            List.of("RECEIVED", "QUEUED", "PROCESSING");

    private final ResumeFileRepository resumeFileRepo;
    private final InboundMessageRepository messageRepo;
    private final InboundAttachmentRepository attachmentRepo;
    private final ProcessingJobRepository processingJobRepo;
    private final StoragePort storagePort;
    private final TikaMimeDetector mimeDetector;
    private final ResumeGatekeeperService gatekeeper;
    private final ResumeExtractionPort extractor;
    private final CandidateIdentityService identityService;
    private final LivingProfileService profileService;
    private final JobRepository jobRepo;
    private final JobVersionRepository jobVersionRepo;
    private final CandidateProfileVersionRepository profileVersionRepo;
    private final CandidateRepository candidateRepo;
    private final ResumeVersionRepository resumeVersionRepo;
    private final MatchService matchService;
    private final ApplicationService applicationService;
    private final EmailRuleEngineService ruleEngine;
    private final IdentityReviewCaseRepository reviewCaseRepo;

    // ── Channel 1: Chrome Extension ──
    @Transactional
    public UUID ingestFromExtension(UUID orgId, byte[] fileData, String originalFilename,
                                    String senderEmail, String subject, String clientSha256,
                                    String sourceType, UUID jobId) {
        String serverSha256 = HashUtil.sha256Hex(fileData);
        if (clientSha256 != null && !clientSha256.equalsIgnoreCase(serverSha256)) {
            throw new IllegalArgumentException("SHA-256 mismatch: file integrity check failed");
        }

        String mime = mimeDetector.detectMimeType(fileData, originalFilename);
        if (!mimeDetector.isResumeMimeType(mime)) {
            throw new IllegalArgumentException("Invalid file type: " + mime);
        }

        ResumeFile resumeFile = getOrStoreResumeFile(orgId, serverSha256, mime, fileData);
        UUID reused = reuseExistingAttachment(orgId, resumeFile, jobId, sourceType);
        if (reused != null) {
            log.debug("Reused existing attachment={} for sha256={}", reused, serverSha256);
            return reused;
        }

        InboundMessage message = InboundMessage.builder()
                .organizationId(orgId)
                .senderEmail(senderEmail)
                .subject(subject)
                .sourceType(sourceType != null ? sourceType : "EXT_GMAIL")
                .jobId(jobId)
                .receivedAt(Instant.now())
                .processingStatus("DISCOVERED")
                .build();
        messageRepo.save(message);

        InboundAttachment attachment = InboundAttachment.builder()
                .organizationId(orgId)
                .messageId(message.getId())
                .attachmentIndex(0)
                .originalFilename(originalFilename)
                .declaredMime(mime)
                .detectedMime(mime)
                .sizeBytes((long) fileData.length)
                .sha256(serverSha256)
                .resumeFileId(resumeFile.getId())
                .status("QUEUED")
                .build();
        attachmentRepo.save(attachment);

        enqueueParseJob(orgId, attachment.getId());
        message.setProcessingStatus("QUEUED");
        messageRepo.save(message);
        log.info("Extension intake queued: attachment={} org={}", attachment.getId(), orgId);
        return attachment.getId();
    }

    // ── Channel 2: IMAPS attachment with provider provenance ──
    @Transactional
    public UUID ingestMailAttachment(UUID orgId, UUID connectionId, String providerMessageId,
                                     String internetMessageId, String senderEmail, String senderName,
                                     String subject, String sourceType, byte[] data, String filename) {
        if (providerMessageId != null) {
            InboundMessage existing = messageRepo
                    .findByOrganizationIdAndConnectionIdAndProviderMessageId(
                            orgId, connectionId, providerMessageId)
                    .orElse(null);
            if (existing != null) {
                List<InboundAttachment> existingAttachments = attachmentRepo.findByMessageId(existing.getId());
                if (!existingAttachments.isEmpty()) {
                    log.debug("Skipping already-ingested provider message={} connection={}",
                            providerMessageId, connectionId);
                    return existingAttachments.get(0).getId();
                }
            }
        }

        String mime = mimeDetector.detectMimeType(data, filename);
        if (!mimeDetector.isResumeMimeType(mime)) {
            throw new IllegalArgumentException("Invalid attachment type: " + mime);
        }
        String sha256 = HashUtil.sha256Hex(data);
        ResumeFile resumeFile = getOrStoreResumeFile(orgId, sha256, mime, data);

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, connectionId, "INBOX", senderEmail, senderName, subject, null,
                List.of(new EmailRuleEngineService.AttachmentEnvelope(filename, mime, data.length))
        );
        EmailRuleEngineService.EvaluationDecision decision = ruleEngine.evaluate(env);

        InboundMessage message = InboundMessage.builder()
                .organizationId(orgId)
                .connectionId(connectionId)
                .providerMessageId(providerMessageId)
                .internetMessageId(internetMessageId)
                .senderEmail(senderEmail)
                .senderName(senderName)
                .subject(subject)
                .sourceType(sourceType != null ? sourceType : "IMAP")
                .jobId(decision.targetJobId())
                .receivedAt(Instant.now())
                .processingStatus("DISCOVERED")
                .matchedRuleId(decision.matchedRuleId())
                .ruleAction(decision.action())
                .ruleExplanation(decision.explanation())
                .build();
        messageRepo.save(message);

        if (decision.status() == EmailRuleEngineService.EvaluationDecision.DecisionStatus.EXCLUDED) {
            message.setProcessingStatus("FILTERED");
            messageRepo.save(message);
            log.info("Inbound mail message {} filtered out by rule '{}'", message.getId(), decision.matchedRuleName());
            return null;
        }

        InboundAttachment attachment = InboundAttachment.builder()
                .organizationId(orgId)
                .messageId(message.getId())
                .attachmentIndex(0)
                .originalFilename(filename)
                .declaredMime(mime)
                .detectedMime(mime)
                .sizeBytes((long) data.length)
                .sha256(sha256)
                .resumeFileId(resumeFile.getId())
                .status("QUEUED")
                .build();
        attachmentRepo.save(attachment);

        if (decision.status() == EmailRuleEngineService.EvaluationDecision.DecisionStatus.REVIEW_REQUIRED) {
            message.setProcessingStatus("REVIEW_REQUIRED");
            messageRepo.save(message);
            reviewCaseRepo.save(IdentityReviewCase.builder()
                    .organizationId(orgId)
                    .messageId(message.getId())
                    .attachmentId(attachment.getId())
                    .suggestedJobId(decision.targetJobId())
                    .reasonCode("AMBIGUOUS_JOB_MAPPING")
                    .details(decision.explanation())
                    .build());
        } else {
            message.setProcessingStatus("QUEUED");
            messageRepo.save(message);
        }

        enqueueParseJob(orgId, attachment.getId());
        return attachment.getId();
    }

    // ── Channel 5: Direct / Bulk Upload ──
    @Transactional
    public List<UUID> ingestDirectUpload(UUID orgId, List<MultipartFile> files, String sourceType) {
        return ingestDirectUpload(orgId, files, sourceType, null);
    }

    @Transactional
    public List<UUID> ingestDirectUpload(UUID orgId, List<MultipartFile> files,
                                         String sourceType, UUID jobId) {
        List<UUID> accepted = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                byte[] data = file.getBytes();
                String fname = file.getOriginalFilename();

                if (fname != null && fname.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    accepted.addAll(processZip(orgId, data, sourceType, jobId));
                } else {
                    UUID id = ingestSingleFile(orgId, data, fname, null, null, sourceType, jobId);
                    if (id != null) accepted.add(id);
                }
            } catch (Exception e) {
                log.warn("Error processing uploaded file '{}': {}",
                        file.getOriginalFilename(), e.getMessage());
            }
        }
        return accepted;
    }

    // ── Channel 4: Forwarding Webhook ──
    public record WebhookIngestResult(UUID messageId, List<UUID> attachmentIds) {}

    /**
     * One forwarded email becomes exactly ONE inbound message with N
     * attachments and N parse jobs. The forwarding mailbox (forwarderEmail)
     * and the claimed original sender (originalSenderEmail, optional) are
     * stored as separate provenance fields; an absent original sender stays
     * unknown (null) and is never invented.
     */
    @Transactional
    public WebhookIngestResult ingestFromWebhook(UUID orgId, String forwarderEmail,
                                                 String originalSenderEmail, String subject,
                                                 String sourceType, List<byte[]> attachmentData,
                                                 List<String> attachmentNames, UUID jobId) {
        boolean isForwarded = isForwardedSource(forwarderEmail, subject, sourceType);
        String effectiveSourceType = isForwarded ? "FORWARDED" : "DIRECT";

        InboundMessage message = InboundMessage.builder()
                .organizationId(orgId)
                .senderEmail(forwarderEmail)
                .originalSenderClaim(originalSenderEmail)
                .subject(subject)
                .sourceType(effectiveSourceType)
                .jobId(jobId)
                .receivedAt(Instant.now())
                .processingStatus("DISCOVERED")
                .build();
        messageRepo.save(message);

        List<UUID> accepted = new ArrayList<>();
        for (int i = 0; i < attachmentData.size(); i++) {
            byte[] data = attachmentData.get(i);
            String fname = i < attachmentNames.size() ? attachmentNames.get(i) : "resume_" + i + ".pdf";

            String sha256 = HashUtil.sha256Hex(data);
            String mime = mimeDetector.detectMimeType(data, fname);
            if (!mimeDetector.isResumeMimeType(mime)) {
                // Intentional, documented skip: the attachment is not a resume
                // type and is never accepted. The response's acceptedCount and
                // attachmentIds enumerate exactly what was accepted, so a
                // missing id unambiguously means "not a resume attachment".
                // Any storage/persistence failure, in contrast, aborts the whole
                // delivery below (atomic semantics: nothing is half-accepted).
                log.info("Skipping non-resume webhook attachment '{}' (type {}) for org {}", fname, mime, orgId);
                continue;
            }

            // Storage or persistence failure here propagates: the whole
            // delivery (message, prior attachments, jobs, ledger row) rolls
            // back as one atomic unit, and newly stored objects are removed by
            // the transaction-rollback compensation registered in
            // getOrStoreResumeFile. The sender retries with the same
            // idempotency key and re-enters cleanly.
            ResumeFile resumeFile = getOrStoreResumeFile(orgId, sha256, mime, data);

            InboundAttachment attachment = InboundAttachment.builder()
                    .organizationId(orgId)
                    .messageId(message.getId())
                    .attachmentIndex(i)
                    .originalFilename(fname)
                    .declaredMime(mime)
                    .detectedMime(mime)
                    .sizeBytes((long) data.length)
                    .sha256(sha256)
                    .resumeFileId(resumeFile.getId())
                    .status("QUEUED")
                    .build();
            attachmentRepo.save(attachment);
            enqueueParseJob(orgId, attachment.getId());
            accepted.add(attachment.getId());
        }

        message.setProcessingStatus(!accepted.isEmpty() ? "QUEUED" : "EMPTY");
        messageRepo.save(message);

        return new WebhookIngestResult(message.getId(), accepted);
    }

    // ── Inline sync processing (used by the worker) ──
    @Transactional
    public void processAttachment(UUID orgId, InboundAttachment attachment) {
        if (!orgId.equals(attachment.getOrganizationId())) {
            throw new IllegalArgumentException("Attachment does not belong to this organization");
        }
        if ("PROCESSED".equals(attachment.getStatus())) {
            log.debug("Attachment {} already processed; skipping", attachment.getId());
            return;
        }

        ResumeFile resumeFile = resumeFileRepo
                .findByIdAndOrganizationId(attachment.getResumeFileId(), orgId)
                .orElseThrow(() -> new IllegalStateException(
                        "ResumeFile not found: " + attachment.getResumeFileId()));

        if (resumeFile.getExtractedRawText() == null || resumeFile.getExtractedRawText().isBlank()) {
            ResumeGatekeeperService.GatekeeperResult gk = gatekeeper.evaluate(
                    storagePort.retrieve(resumeFile.getS3ObjectKey()), attachment.getOriginalFilename());
            if (!gk.accepted()) {
                log.warn("Gatekeeper rejected attachment={}: {}", attachment.getId(), gk.rejectionReason());
                attachment.setStatus("REJECTED");
                attachmentRepo.save(attachment);
                updateMessageStatus(attachment.getMessageId());
                return;
            }
            resumeFile.setExtractedRawText(gk.extractedText());
            resumeFileRepo.save(resumeFile);
        }

        InboundMessage message = messageRepo.findByIdAndOrganizationId(
                        attachment.getMessageId(), orgId)
                .orElseThrow(() -> new IllegalStateException(
                        "Inbound message not found: " + attachment.getMessageId()));
        if (!"REVIEW_REQUIRED".equals(message.getProcessingStatus())) {
            message.setProcessingStatus("PROCESSING");
            messageRepo.save(message);
        }

        String sourceType = message.getSourceType();
        String senderEmail = message.getSenderEmail();

        ExtractedResumeSchema schema = extractor.extract(
                resumeFile.getExtractedRawText(),
                attachment.getOriginalFilename(),
                senderEmail,
                orgId,
                resumeFile.getId());

        boolean missingContact = (schema.getEmails() == null || schema.getEmails().isEmpty())
                && (schema.getPhones() == null || schema.getPhones().isEmpty())
                && (senderEmail == null || "FORWARDED".equalsIgnoreCase(sourceType));
        if (missingContact) {
            log.info("Missing contact info for attachment={} in message={}", attachment.getId(), message.getId());
            reviewCaseRepo.save(IdentityReviewCase.builder()
                    .organizationId(orgId)
                    .messageId(message.getId())
                    .attachmentId(attachment.getId())
                    .reasonCode("MISSING_CONTACT_INFO")
                    .details("Extracted resume contains no contact information (missing email and phone).")
                    .build());
            message.setProcessingStatus("REVIEW_REQUIRED");
            messageRepo.save(message);
        }

        Candidate candidate = identityService.resolveOrCreate(orgId, schema, senderEmail, sourceType);

        LivingProfileService.PromotionResult promotion = profileService.promoteResume(
                orgId, candidate, resumeFile, schema, attachment.getId());
        ResumeVersion rv = promotion.resumeVersion();

        if (promotion.promoted()) {
            autoMatchCandidate(orgId, candidate);
        }

        if (message.getJobId() != null) {
            try {
                applicationService.applyToJob(orgId, candidate.getId(), message.getJobId(),
                        rv.getId(), sourceType);
            } catch (IllegalArgumentException e) {
                log.warn("Could not create application for candidate={} job={}: {}",
                        candidate.getId(), message.getJobId(), e.getMessage());
            }
        }

        attachment.setStatus("PROCESSED");
        attachmentRepo.save(attachment);
        updateMessageStatus(message.getId());

        log.info("Processed attachment={} candidate={} resumeVersion={} promoted={}",
                attachment.getId(), candidate.getId(), rv.getVersionNumber(), promotion.promoted());
    }

    // ── Helpers ──

    private UUID reuseExistingAttachment(UUID orgId, ResumeFile resumeFile,
                                         UUID jobId, String sourceType) {
        InboundAttachment existing = attachmentRepo
                .findFirstByOrganizationIdAndResumeFileIdAndStatusNotOrderByCreatedAtAsc(
                        orgId, resumeFile.getId(), "REJECTED")
                .orElse(null);
        if (existing == null) return null;

        if (jobId != null) {
            resumeVersionRepo
                    .findTopByOrganizationIdAndResumeFileIdOrderByVersionNumberDesc(
                            orgId, resumeFile.getId())
                    .ifPresent(rv -> applicationService.applyToJob(
                            orgId, rv.getCandidateId(), jobId, rv.getId(), sourceType));
        }
        return existing.getId();
    }

    private List<UUID> processZip(UUID orgId, byte[] zipData, String sourceType, UUID jobId) {
        List<UUID> accepted = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            int count = 0;
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++count > 100) {
                    log.warn("ZIP exceeded maximum entry limit (100); skipping remaining entries");
                    break;
                }
                String entryName = entry.getName();
                if (entryName.contains("..") || entryName.startsWith("/") || entryName.contains("\\")) {
                    log.warn("Skipping unsafe ZIP entry: {}", entryName);
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int read;
                long entryBytes = 0;
                while ((read = zis.read(buffer)) != -1) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > 20 * 1024 * 1024 || totalBytes > 100 * 1024 * 1024) {
                        log.warn("ZIP decompression threshold exceeded for {}; skipping", entryName);
                        baos = null;
                        break;
                    }
                    baos.write(buffer, 0, read);
                }
                zis.closeEntry();
                if (baos != null) {
                    byte[] data = baos.toByteArray();
                    UUID id = ingestSingleFile(orgId, data, entryName, null, null, sourceType, jobId);
                    if (id != null) accepted.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("Error processing ZIP: {}", e.getMessage());
        }
        return accepted;
    }

    private UUID ingestSingleFile(UUID orgId, byte[] data, String fname,
                                  String senderEmail, String subject,
                                  String sourceType, UUID jobId) {
        String mime = mimeDetector.detectMimeType(data, fname);
        if (!mimeDetector.isResumeMimeType(mime)) return null;

        String sha256 = HashUtil.sha256Hex(data);
        ResumeFile resumeFile = getOrStoreResumeFile(orgId, sha256, mime, data);

        UUID reused = reuseExistingAttachment(orgId, resumeFile, jobId, sourceType);
        if (reused != null) return reused;

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, null, senderEmail, null, subject, null,
                List.of(new EmailRuleEngineService.AttachmentEnvelope(fname, mime, data.length))
        );
        EmailRuleEngineService.EvaluationDecision decision = ruleEngine.evaluate(env);
        UUID effectiveJobId = jobId != null ? jobId : decision.targetJobId();

        InboundMessage message = InboundMessage.builder()
                .organizationId(orgId)
                .senderEmail(senderEmail)
                .subject(subject)
                .sourceType(sourceType != null ? sourceType : "UPLOAD")
                .jobId(effectiveJobId)
                .receivedAt(Instant.now())
                .processingStatus("DISCOVERED")
                .matchedRuleId(decision.matchedRuleId())
                .ruleAction(decision.action())
                .ruleExplanation(decision.explanation())
                .build();
        messageRepo.save(message);

        InboundAttachment attachment = InboundAttachment.builder()
                .organizationId(orgId)
                .messageId(message.getId())
                .attachmentIndex(0)
                .originalFilename(fname)
                .declaredMime(mime)
                .detectedMime(mime)
                .sizeBytes((long) data.length)
                .sha256(sha256)
                .resumeFileId(resumeFile.getId())
                .status("QUEUED")
                .build();
        attachmentRepo.save(attachment);

        String postStatus = (jobId == null && decision.status() == EmailRuleEngineService.EvaluationDecision.DecisionStatus.REVIEW_REQUIRED)
                ? "REVIEW_REQUIRED" : "QUEUED";
        message.setProcessingStatus(postStatus);
        messageRepo.save(message);

        if ("REVIEW_REQUIRED".equals(postStatus)) {
            reviewCaseRepo.save(IdentityReviewCase.builder()
                    .organizationId(orgId)
                    .messageId(message.getId())
                    .attachmentId(attachment.getId())
                    .suggestedJobId(decision.targetJobId())
                    .reasonCode("AMBIGUOUS_JOB_MAPPING")
                    .details(decision.explanation())
                    .build());
        }

        enqueueParseJob(orgId, attachment.getId());
        return attachment.getId();
    }

    /**
     * Returns the ResumeFile for (org, sha256), creating it if needed.
     *
     * <p>Storage/database consistency: the physical object is written to a
     * fresh unique key (never a shared content-addressed key), then the row is
     * flushed. If this transaction rolls back for any reason, a transaction
     * synchronization callback deletes exactly the object this transaction
     * created. Objects belonging to pre-existing rows are never deleted, and a
     * concurrent identical upload's object is never touched, because each
     * store call owns a key no other transaction can produce.</p>
     *
     * <p><b>Fail-closed:</b> a new physical object is created only when an
     * actual database transaction with active synchronization is available, so
     * every newly stored object is guaranteed to have rollback compensation.
     * Both conditions are checked explicitly — Spring can report active
     * synchronization without an actual transaction, and a compensating
     * rollback callback is only meaningful inside a real transaction. The
     * check is a runtime guard because this method is invoked from within
     * this bean (self-invocation bypasses any {@code @Transactional} proxy),
     * and because reuse of an existing row must stay possible without a
     * transaction. If either condition is absent, the method fails before
     * calling {@code storagePort.store()} — it never stores an object it
     * cannot compensate.</p>
     *
     * <p><b>Unique-key race:</b> when two transactions race to insert the
     * same (org, sha256), each stores its own distinct UUID-based object key,
     * and the database unique constraint lets exactly one ResumeFile row win.
     * The loser encounters the uniqueness error during flush, abandons its own
     * unique object explicitly, and its transaction becomes rollback-only and
     * rolls back; the rollback callback re-deletes the same object
     * (idempotently). The sender retries, and the retry's
     * {@code findByOrganizationIdAndSha256} finds the committed winner's
     * ResumeFile and reuses it. The loser only ever deletes its own unique
     * object.</p>
     */
    public ResumeFile getOrStoreResumeFile(UUID orgId, String sha256, String mime, byte[] data) {
        return resumeFileRepo.findByOrganizationIdAndSha256(orgId, sha256).orElseGet(() -> {
            // Fail closed BEFORE any physical write: an object created without
            // an actual transaction (and its synchronization) could never be
            // compensated on rollback. Reuse of an existing row above is
            // unaffected.
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Cannot create a new resume file without an active "
                        + "transaction (org=" + orgId + ", sha256=" + sha256 + "): refusing to store "
                        + "an object that has no rollback compensation");
            }
            String objectKey = storagePort.store(orgId, sha256, mime, data);
            ResumeFile rf = ResumeFile.builder()
                    .organizationId(orgId)
                    .sha256(sha256)
                    .s3Bucket(storagePort.bucketName())
                    .s3ObjectKey(objectKey)
                    .sizeBytes((long) data.length)
                    .mimeType(mime)
                    .build();
            // Register compensation only after the object was successfully
            // stored, and only for this transaction's own unique object.
            registerRollbackCleanup(orgId, objectKey);
            try {
                return resumeFileRepo.saveAndFlush(rf);
            } catch (DataIntegrityViolationException e) {
                // Lost the (org, sha256) uniqueness race: the loser's flush
                // failed, its transaction is rollback-only and will roll back.
                // Abandon our own unique object explicitly now (the rollback
                // callback re-deletes the same object idempotently). The
                // fallback re-read below is defensive — it may return the
                // winner's row, but this transaction will not commit it; the
                // sender retries and reuses the winner's committed row. Both
                // paths touch only our own unique object.
                abandonNewObject(orgId, objectKey, e);
                return resumeFileRepo.findByOrganizationIdAndSha256(orgId, sha256)
                        .orElseThrow(() -> e);
            }
        });
    }

    /**
     * Registers a transaction-synchronization callback that deletes the given
     * newly stored object if and only if the current transaction rolls back.
     *
     * <p>Safe under concurrency because {@code objectKey} is unique to this
     * transaction's store call: no other transaction can hold a row that
     * references it, so deleting it on rollback can never destroy an object a
     * committed transaction depends on. Objects reused via an existing
     * ResumeFile row are never registered. A failed cleanup is logged with
     * enough context (org id + object key, no candidate data or secrets) to
     * trigger reconciliation, and never masks the original transaction
     * failure.</p>
     */
    private void registerRollbackCleanup(UUID orgId, String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("No active transaction synchronization for newly stored object org={} key={}; "
                    + "rollback compensation not registered — an object written by a failing "
                    + "caller may require manual reconciliation", orgId, objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
                    // COMMITTED keeps the object (the row references it);
                    // UNKNOWN is deliberately conservative: we cannot prove the
                    // row did not commit, so we leave the object for
                    // reconciliation rather than risk deleting a referenced one.
                    return;
                }
                deleteStoredObject(orgId, objectKey, "transaction rolled back");
            }
        });
    }

    /** Best-effort deletion of a newly stored object, with reconciliation logging. */
    private void deleteStoredObject(UUID orgId, String objectKey, String reason) {
        try {
            storagePort.delete(objectKey);
            log.info("Rollback compensation deleted newly stored object: org={} key={} reason={}",
                    orgId, objectKey, reason);
        } catch (Exception e) {
            log.error("Rollback compensation FAILED for org={} key={} reason={}; orphan requires "
                    + "manual reconciliation: {}", orgId, objectKey, reason, e.getMessage());
        }
    }

    /** Explicit abandonment of a unique object created by a lost uniqueness race. */
    private void abandonNewObject(UUID orgId, String objectKey, Exception cause) {
        log.warn("Concurrent duplicate content; abandoning newly stored unique object: org={} "
                + "key={} cause={}", orgId, objectKey, cause.getMessage());
        deleteStoredObject(orgId, objectKey, "unique-violation fallback to existing row");
    }

    private void enqueueParseJob(UUID orgId, UUID attachmentId) {
        processingJobRepo.save(ProcessingJob.builder()
                .organizationId(orgId)
                .jobType("PARSE_RESUME")
                .referenceId(attachmentId)
                .status("PENDING")
                .build());
    }

    public void updateMessageStatus(UUID messageId) {
        messageRepo.findById(messageId).ifPresent(message -> {
            if ("REVIEW_REQUIRED".equals(message.getProcessingStatus())) {
                return;
            }
            long outstanding = attachmentRepo.countByMessageIdAndStatusIn(
                    messageId, ACTIVE_ATTACHMENT_STATUSES);
            List<InboundAttachment> all = attachmentRepo.findByMessageId(messageId);
            long total = all.size();
            long rejected = all.stream().filter(a -> "REJECTED".equals(a.getStatus())).count();
            long processed = all.stream().filter(a -> "PROCESSED".equals(a.getStatus())).count();
            long failed = all.stream().filter(a -> "FAILED".equals(a.getStatus())).count();

            if (outstanding > 0) {
                message.setProcessingStatus("PROCESSING");
            } else if (processed > 0) {
                message.setProcessingStatus("COMPLETED");
            } else if (rejected == total && total > 0) {
                message.setProcessingStatus("REJECTED");
            } else if (failed > 0 && (failed + rejected == total)) {
                message.setProcessingStatus("FAILED");
                if (message.getFailureCode() == null) {
                    message.setFailureCode("ATTACHMENT_PROCESSING_FAILED");
                }
            } else if (total > 0) {
                message.setProcessingStatus("COMPLETED");
            } else {
                message.setProcessingStatus("EMPTY");
            }
            messageRepo.save(message);
        });
    }

    private boolean isForwardedSource(String senderEmail, String subject, String sourceType) {
        if ("FORWARDED".equalsIgnoreCase(sourceType) || "FORWARDING".equalsIgnoreCase(sourceType)) return true;
        if (subject != null) {
            String s = subject.toLowerCase(Locale.ROOT);
            if (s.startsWith("fwd:") || s.startsWith("fw:") || s.startsWith("forward")) return true;
        }
        if (senderEmail != null) {
            String low = senderEmail.toLowerCase(Locale.ROOT);
            for (String kw : List.of("hr@", "recruit@", "careers@", "jobs@", "placement@", "agency@", "hr-", "recruit-", "agency-")) {
                if (low.startsWith(kw) || low.contains("agency") || low.contains("headhunter")) return true;
            }
        }
        return false;
    }

    private void autoMatchCandidate(UUID orgId, Candidate candidate) {
        try {
            Candidate fresh = candidateRepo.findByIdAndOrganizationId(candidate.getId(), orgId)
                    .orElse(candidate);
            if (fresh.getCurrentProfileVersionId() == null) return;

            var profile = profileVersionRepo
                    .findByIdAndOrganizationId(fresh.getCurrentProfileVersionId(), orgId)
                    .orElse(null);
            if (profile == null) return;

            List<Job> activeJobs = jobRepo.findByOrganizationIdAndStatus(orgId, "OPEN");
            for (Job job : activeJobs) {
                if (job.getCurrentVersionId() == null) continue;
                jobVersionRepo.findByIdAndOrganizationId(job.getCurrentVersionId(), orgId)
                        .ifPresent(jv -> matchService.computeMatch(orgId, jv, fresh, profile));
            }
        } catch (Exception e) {
            log.warn("Auto-match failed for candidate={}: {}", candidate.getId(), e.getMessage());
        }
    }
}
