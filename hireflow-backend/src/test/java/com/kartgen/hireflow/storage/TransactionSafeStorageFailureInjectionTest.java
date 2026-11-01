package com.kartgen.hireflow.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.document.ResumeFile;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.document.StoragePort;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import com.kartgen.hireflow.ops.ProcessingJobRepository;
import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import com.kartgen.hireflow.webhook.WebhookDeliveryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-2B failure-injection tests: storage/database consistency under forced
 * failures, verified on BOTH the database state (real H2 + Flyway + Hibernate)
 * and the physical storage state (an observable fake StoragePort that records
 * every stored/deleted object key).
 *
 * <p>All ingestion flows are exercised through the real Spring context; Test A
 * and Test C additionally go through the real webhook HTTP endpoint
 * (authentication filter, signature verification, controller, transaction).
 * The worker scheduler is disabled so processing jobs stay untouched.</p>
 *
 * <p>Tests A-G map 1:1 to the J-2B audit's mandatory failure-injection tests.
 * Test H pins the documented skip contract for non-resume attachments.</p>
 */
@SpringBootTest(properties = {
        "hireflow.worker.poll-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:hireflow-j2b-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@ActiveProfiles("test")
class TransactionSafeStorageFailureInjectionTest extends WebhookIntegrationTestSupport {

    @TestConfiguration
    static class FakeStorageConfig {
        static final FakeStoragePort FAKE = new FakeStoragePort();

        @Bean
        @Primary
        StoragePort fakeStoragePort() {
            return FAKE;
        }
    }

    /**
     * Observable in-memory StoragePort. Records every stored and deleted key,
     * exposes failure injection for store/delete, and supports a barrier so
     * concurrent tests can force two stores to overlap.
     */
    static final class FakeStoragePort implements StoragePort {
        final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        final List<String> deleteFailures = Collections.synchronizedList(new ArrayList<>());
        final List<String> storedKeys = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger storeAttempts = new AtomicInteger();
        final AtomicInteger storeCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final AtomicBoolean failAllDeletes = new AtomicBoolean();
        /** When > 0, the Nth store attempt throws instead of storing. */
        volatile int failStoreAtAttempt;
        volatile CyclicBarrier storeBarrier;

        void reset() {
            objects.clear();
            deleted.clear();
            deleteFailures.clear();
            storedKeys.clear();
            storeAttempts.set(0);
            storeCalls.set(0);
            deleteCalls.set(0);
            failAllDeletes.set(false);
            failStoreAtAttempt = 0;
            storeBarrier = null;
        }

        @Override
        public String store(UUID orgId, String sha256, String mimeType, byte[] data) {
            if (storeBarrier != null) {
                try {
                    storeBarrier.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("store barrier interrupted", e);
                }
            }
            int attempt = storeAttempts.incrementAndGet();
            if (failStoreAtAttempt > 0 && attempt == failStoreAtAttempt) {
                throw new IllegalStateException("simulated storage failure (attempt " + attempt + ")");
            }
            String ext = mimeType != null && mimeType.contains("pdf") ? ".pdf" : ".docx";
            String key = orgId + "/" + sha256 + "/" + UUID.randomUUID() + ext;
            objects.put(key, data.clone());
            storedKeys.add(key);
            storeCalls.incrementAndGet();
            return key;
        }

        @Override
        public byte[] retrieve(String objectKey) {
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new IllegalStateException("no such object: " + objectKey);
            }
            return bytes.clone();
        }

        @Override
        public void delete(String objectKey) {
            deleteCalls.incrementAndGet();
            if (failAllDeletes.get()) {
                deleteFailures.add(objectKey);
                throw new IllegalStateException("simulated delete failure for " + objectKey);
            }
            objects.remove(objectKey);
            deleted.add(objectKey);
        }

        @Override
        public String generateViewUrl(String objectKey, String fileName) {
            return "/fake-view/" + objectKey;
        }

        @Override
        public String bucketName() {
            return "fake";
        }
    }

    @Autowired
    private MessageIngestionService ingestionService;

    @Autowired
    private InboundMessageRepository messageRepo;

    @Autowired
    private InboundAttachmentRepository attachmentRepo;

    @Autowired
    private ProcessingJobRepository jobRepo;

    @Autowired
    private WebhookDeliveryRepository deliveryRepo;

    @SpyBean
    private ResumeFileRepository resumeFileRepo;

    @Autowired
    private javax.sql.DataSource dataSource;

    private final FakeStoragePort storage = FakeStorageConfig.FAKE;

    @BeforeEach
    void resetStorage() {
        storage.reset();
    }

    @AfterEach
    void assertNoLeakedState() {
        // Every test must leave the fake storage consistent: any surviving
        // object must be referenced by a committed resume_files row — except
        // objects whose cleanup deliberately failed (Test F), which are
        // documented as requiring reconciliation.
        storage.objects.forEach((key, bytes) -> {
            if (storage.deleteFailures.contains(key)) {
                return; // documented orphan from the cleanup-failure test
            }
            List<ResumeFile> rows = resumeFileRepo.findAll().stream()
                    .filter(r -> key.equals(r.getS3ObjectKey()))
                    .toList();
            assertThat(rows)
                    .as("surviving storage object %s must be referenced by a committed row", key)
                    .isNotEmpty();
        });
    }

    // ── Test A — second attachment storage failure (real HTTP endpoint) ──

    @Test
    void secondAttachmentStorageFailureRejectsWholeDeliveryAtomically() throws Exception {
        WebhookOrg org = newWebhookOrg("j2b-a");
        Credential cred = provisionCredential(org);
        byte[] pdf1 = smallPdf();
        byte[] pdf2 = smallPdf();

        // First store attempt (attachment 1) succeeds; the second (attachment 2) fails.
        storage.failStoreAtAttempt = 2;

        MvcResult result = mockMvc.perform(performSignedMultipart(cred, org.org().getId().toString(),
                        "nonce-" + UUID.randomUUID(), "idem-j2b-a-" + UUID.randomUUID(),
                        List.of(
                                new WebhookTestSupport.FormPart("senderEmail", null, null,
                                        "hr@agency.example".getBytes(StandardCharsets.UTF_8)),
                                new WebhookTestSupport.FormPart("subject", null, null,
                                        "j2b-a".getBytes(StandardCharsets.UTF_8)),
                                new WebhookTestSupport.FormPart("files", "a.pdf", "application/pdf", pdf1),
                                new WebhookTestSupport.FormPart("files", "b.pdf", "application/pdf", pdf2))))
                .andExpect(status().isInternalServerError())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // The controller maps any ingestion failure to 500 with this exact body;
        // the sender can never mistake it for acceptance.
        assertThat(body.path("error").asText()).isEqualTo("Webhook processing failed");

        // Atomic delivery: NOTHING was accepted, no message, no attachments,
        // no jobs, no delivery ledger row.
        UUID orgId = org.org().getId();
        assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
        assertThat(attachmentRepo.countByOrganizationId(orgId)).isZero();
        assertThat(jobRepo.countByOrganizationIdAndStatus(orgId, "PENDING")).isZero();
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        assertThat(deliveryRepo.countByOrganizationId(orgId)).isZero();

        // Storage: both stores were attempted, only the first object existed,
        // and it was removed by rollback compensation. No orphan remains.
        assertThat(storage.storeAttempts.get()).isEqualTo(2);
        assertThat(storage.storeCalls.get()).isEqualTo(1);
        assertThat(storage.storedKeys).hasSize(1);
        assertThat(storage.objects).isEmpty();
        assertThat(storage.deleted).containsExactly(storage.storedKeys.get(0));
        assertThat(storage.storedKeys.get(0)).startsWith(orgId + "/" + HashUtil.sha256Hex(pdf1) + "/");
    }

    // ── Test B — database failure after storage (service level) ──

    @Test
    void databaseFailureAfterStorageRollsBackRowsAndRemovesStoredObject() {
        doThrow(new DataAccessResourceFailureException("forced DB failure after storage"))
                .when(resumeFileRepo).saveAndFlush(any(ResumeFile.class));

        WebhookOrg org = newWebhookOrg("j2b-b");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();

        assertThatThrownBy(() -> ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                "j2b-b", "FORWARDED", List.of(pdf), List.of("resume.pdf"), null))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("forced DB failure after storage");

        // Database: the whole transaction rolled back.
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
        assertThat(attachmentRepo.countByOrganizationId(orgId)).isZero();
        assertThat(jobRepo.countByOrganizationIdAndStatus(orgId, "PENDING")).isZero();

        // Storage: the object written before the DB failure was compensated.
        assertThat(storage.storedKeys).hasSize(1);
        assertThat(storage.objects).isEmpty();
        assertThat(storage.deleted).containsExactly(storage.storedKeys.get(0));
    }

    // ── Test C — commit failure at the latest feasible point (real HTTP endpoint) ──

    @Test
    void finalLedgerWriteFailureBeforeCommitRollsBackAndRemovesStoredObject() throws Exception {
        // markAccepted (the JDBC UPDATE on webhook_deliveries) is the last
        // database write before the transaction commits. A real H2 trigger
        // forces that exact statement to fail — no Spring bean is mocked.
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS j2b_fail_accept "
                + "BEFORE UPDATE ON webhook_deliveries FOR EACH ROW "
                + "CALL 'com.kartgen.hireflow.storage.J2bFailOnWebhookAcceptTrigger'");
        try {
            J2bFailOnWebhookAcceptTrigger.fail = true;

            WebhookOrg org = newWebhookOrg("j2b-c");
            Credential cred = provisionCredential(org);
            byte[] pdf = smallPdf();
            String idempotencyKey = "idem-j2b-c-" + UUID.randomUUID();

            MvcResult result = mockMvc.perform(performSignedMultipart(cred, org.org().getId().toString(),
                            "nonce-" + UUID.randomUUID(), idempotencyKey,
                            List.of(
                                    new WebhookTestSupport.FormPart("senderEmail", null, null,
                                            "hr@agency.example".getBytes(StandardCharsets.UTF_8)),
                                    new WebhookTestSupport.FormPart("subject", null, null,
                                            "j2b-c".getBytes(StandardCharsets.UTF_8)),
                                    new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", pdf))))
                    .andExpect(status().isInternalServerError())
                    .andReturn();
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("error").asText()).isEqualTo("Webhook processing failed");

            UUID orgId = org.org().getId();
            assertThat(deliveryRepo.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey)).isEmpty();
            assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
            assertThat(attachmentRepo.countByOrganizationId(orgId)).isZero();
            assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
            assertThat(jobRepo.countByOrganizationIdAndStatus(orgId, "PENDING")).isZero();

            assertThat(storage.storedKeys).hasSize(1);
            assertThat(storage.objects).isEmpty();
            assertThat(storage.deleted).containsExactly(storage.storedKeys.get(0));
        } finally {
            J2bFailOnWebhookAcceptTrigger.fail = false;
            jdbc.execute("DROP TRIGGER IF EXISTS j2b_fail_accept");
        }
    }

    // ── Test D — retry after rollback ──

    @Test
    void retryAfterRollbackSucceedsWithFreshObjectAndNoOrphan() {
        doThrow(new DataAccessResourceFailureException("forced first-attempt DB failure"))
                .when(resumeFileRepo).saveAndFlush(any(ResumeFile.class));

        WebhookOrg org = newWebhookOrg("j2b-d");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();
        String sha256 = HashUtil.sha256Hex(pdf);

        // Attempt 1: object written, DB insert fails, rollback cleans the object.
        assertThatThrownBy(() -> ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                "j2b-d", "FORWARDED", List.of(pdf), List.of("resume.pdf"), null))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(storage.objects).isEmpty();
        assertThat(storage.deleted).hasSize(1);
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        String firstKey = storage.deleted.get(0);

        // Clear the failure stub; the spy now delegates to the real repository.
        reset(resumeFileRepo);

        // Attempt 2: identical content, now succeeds.
        MessageIngestionService.WebhookIngestResult second =
                ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                        "j2b-d", "FORWARDED", List.of(pdf), List.of("resume.pdf"), null);
        assertThat(second.attachmentIds()).hasSize(1);

        ResumeFile row = resumeFileRepo.findByOrganizationIdAndSha256(orgId, sha256).orElseThrow();
        // The committed row references the NEW unique object, not the deleted one.
        assertThat(row.getS3ObjectKey()).isNotEqualTo(firstKey);
        assertThat(storage.retrieve(row.getS3ObjectKey())).isEqualTo(pdf);
        assertThat(storage.objects).hasSize(1);
        // No further deletions: the committed object is untouched.
        assertThat(storage.deleted).containsExactly(firstKey);

        assertThat(messageRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(attachmentRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(jobRepo.countByOrganizationIdAndStatus(orgId, "PENDING")).isEqualTo(1);
    }

    // ── Test E — concurrent identical uploads ──

    @Test
    void concurrentIdenticalUploadsKeepWinnerObjectAndDeleteOnlyLoserObject() throws Exception {
        WebhookOrg org = newWebhookOrg("j2b-e");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();
        String sha256 = HashUtil.sha256Hex(pdf);

        // Force both store calls to complete before either transaction inserts,
        // guaranteeing the (org, sha256) uniqueness race.
        storage.storeBarrier = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                            "j2b-e", "FORWARDED", List.of(pdf), List.of("resume.pdf"), null);
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // Exactly one committed row wins the uniqueness race.
        ResumeFile row = resumeFileRepo.findByOrganizationIdAndSha256(orgId, sha256).orElseThrow();
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isEqualTo(1);

        // Exactly one physical object survives: the winner's, referenced by the
        // committed row and byte-identical to the upload.
        assertThat(storage.storeCalls.get()).isEqualTo(2);
        assertThat(storage.storedKeys).hasSize(2);
        assertThat(storage.objects).hasSize(1);
        String winnerKey = row.getS3ObjectKey();
        assertThat(storage.objects).containsKey(winnerKey);
        assertThat(storage.retrieve(winnerKey)).isEqualTo(pdf);

        // The failed transaction deleted ONLY its own unique object and can
        // never have deleted the winner's.
        String loserKey = storage.storedKeys.stream()
                .filter(k -> !k.equals(winnerKey))
                .findFirst().orElseThrow();
        assertThat(storage.deleted).contains(loserKey);
        assertThat(storage.deleted).doesNotContain(winnerKey);

        // Every outcome is either a success or a database-layer failure —
        // never a storage-layer (IllegalStateException) failure — and
        // successes created exactly one message each.
        long succeeded = outcomes.stream()
                .filter(o -> o instanceof MessageIngestionService.WebhookIngestResult)
                .count();
        assertThat(succeeded).isGreaterThanOrEqualTo(1);
        for (Object outcome : outcomes) {
            if (outcome instanceof Exception e) {
                // The loser fails at the database uniqueness layer (constraint
                // violation, rollback-only commit, or transaction system
                // exception), not at the storage layer.
                assertThat(e).isNotInstanceOf(IllegalStateException.class);
            }
        }
        assertThat(messageRepo.countByOrganizationId(orgId)).isEqualTo(succeeded);
    }

    // ── Test F — cleanup failure must not hide the original failure ──

    @Test
    void failedCleanupDoesNotMaskOriginalFailureAndIsLoggedForReconciliation() {
        storage.failAllDeletes.set(true);
        doThrow(new DataAccessResourceFailureException("forced DB failure"))
                .when(resumeFileRepo).saveAndFlush(any(ResumeFile.class));

        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(MessageIngestionService.class);
        serviceLogger.addAppender(appender);
        try {
            WebhookOrg org = newWebhookOrg("j2b-f");
            UUID orgId = org.org().getId();
            byte[] pdf = smallPdf();

            // The ORIGINAL transaction error is what the caller sees — the
            // cleanup failure must not replace or mask it.
            assertThatThrownBy(() -> ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                    "j2b-f", "FORWARDED", List.of(pdf), List.of("resume.pdf"), null))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("forced DB failure");

            // The cleanup failure is observable: recorded by the port AND
            // logged with the object key for reconciliation.
            assertThat(storage.storedKeys).hasSize(1);
            String orphanKey = storage.storedKeys.get(0);
            assertThat(storage.deleteFailures).containsExactly(orphanKey);
            assertThat(storage.objects).containsKey(orphanKey);

            boolean logged = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Rollback compensation FAILED")
                            && e.getFormattedMessage().contains(orphanKey));
            assertThat(logged)
                    .as("cleanup failure must be logged with the orphan object key")
                    .isTrue();

            assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        } finally {
            serviceLogger.detachAppender(appender);
        }
    }

    // ── Test G — existing shared ResumeFile must never be deleted ──

    @Test
    void rollbackOfReusingIngestionNeverTouchesExistingSharedObject() {
        WebhookOrg org = newWebhookOrg("j2b-g");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();
        String sha256 = HashUtil.sha256Hex(pdf);

        // Pre-existing committed state: a ResumeFile row + its physical object.
        String sharedKey = orgId + "/" + sha256 + "/" + UUID.randomUUID() + ".pdf";
        storage.objects.put(sharedKey, pdf);
        resumeFileRepo.saveAndFlush(ResumeFile.builder()
                .organizationId(orgId)
                .sha256(sha256)
                .s3Bucket("fake")
                .s3ObjectKey(sharedKey)
                .sizeBytes((long) pdf.length)
                .mimeType("application/pdf")
                .build());

        // New delivery: first attachment reuses the shared row, second fails.
        storage.failStoreAtAttempt = 1;
        byte[] pdf2 = smallPdf();

        assertThatThrownBy(() -> ingestionService.ingestFromWebhook(orgId, "hr@agency.example", null,
                "j2b-g", "FORWARDED", List.of(pdf, pdf2), List.of("a.pdf", "b.pdf"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated storage failure");

        // The pre-existing shared object was never registered for compensation
        // and is untouched; the whole new delivery rolled back. The only store
        // attempt was attachment 2's, which failed before writing anything.
        assertThat(storage.deleted).isEmpty();
        assertThat(storage.deleteCalls.get()).isZero();
        assertThat(storage.objects.get(sharedKey)).isEqualTo(pdf);
        assertThat(storage.storeAttempts.get()).isEqualTo(1);
        assertThat(storage.storeCalls.get()).isZero();

        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
        assertThat(attachmentRepo.countByOrganizationId(orgId)).isZero();
    }

    // ── Test H — documented skip contract for non-resume attachments ──

    @Test
    void nonResumeAttachmentIsSkippedNotFailedAndAcceptedListIsExplicit() throws Exception {
        WebhookOrg org = newWebhookOrg("j2b-h");
        Credential cred = provisionCredential(org);
        byte[] pdf = smallPdf();
        byte[] notAResume = "plain text, definitely not a resume document".getBytes(StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(performSignedMultipart(cred, org.org().getId().toString(),
                        "nonce-" + UUID.randomUUID(), "idem-j2b-h-" + UUID.randomUUID(),
                        List.of(
                                new WebhookTestSupport.FormPart("senderEmail", null, null,
                                        "hr@agency.example".getBytes(StandardCharsets.UTF_8)),
                                new WebhookTestSupport.FormPart("subject", null, null,
                                        "j2b-h".getBytes(StandardCharsets.UTF_8)),
                                new WebhookTestSupport.FormPart("files", "notes.txt", "text/plain", notAResume),
                                new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", pdf))))
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("QUEUED");
        assertThat(body.path("acceptedCount").asInt()).isEqualTo(1);
        assertThat(body.path("attachmentIds")).hasSize(1);

        UUID orgId = org.org().getId();
        assertThat(messageRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(attachmentRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        assertThat(storage.deleted).isEmpty();
    }

    // ── J-2B.1 F-03 — fail-closed transaction synchronization ──

    @Test
    void creatingNewFileWithoutActiveTransactionFailsClosedBeforeStoring() {
        WebhookOrg org = newWebhookOrg("j2b1-f03a");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();

        // Direct call with no transaction: the guard must fail BEFORE any
        // physical write (self-invocation means no @Transactional proxy can
        // intercept an internal call, so the runtime check is the enforcement).
        assertThatThrownBy(() -> ingestionService.getOrStoreResumeFile(
                orgId, HashUtil.sha256Hex(pdf), "application/pdf", pdf))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");

        // Nothing was stored and no row was created.
        assertThat(storage.storeAttempts.get()).isZero();
        assertThat(storage.objects).isEmpty();
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
    }

    @Test
    void existingResumeFileRemainsReusableWithoutActiveTransaction() {
        WebhookOrg org = newWebhookOrg("j2b1-f03b");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();
        String sha256 = HashUtil.sha256Hex(pdf);
        String key = orgId + "/" + sha256 + "/" + UUID.randomUUID() + ".pdf";
        storage.objects.put(key, pdf);
        ResumeFile existing = resumeFileRepo.saveAndFlush(ResumeFile.builder()
                .organizationId(orgId)
                .sha256(sha256)
                .s3Bucket("fake")
                .s3ObjectKey(key)
                .sizeBytes((long) pdf.length)
                .mimeType("application/pdf")
                .build());

        // Reuse of an already-committed ResumeFile must keep working without a
        // transaction: no new object is stored and no compensation is needed.
        ResumeFile reused = ingestionService.getOrStoreResumeFile(
                orgId, sha256, "application/pdf", pdf);

        assertThat(reused.getId()).isEqualTo(existing.getId());
        assertThat(storage.storeAttempts.get()).isZero();
        assertThat(storage.objects.get(key)).isEqualTo(pdf);
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isEqualTo(1);
    }

    // ── J-2B.2 L-01 — synchronization without an actual transaction ──

    @Test
    void synchronizationWithoutActualTransactionRejectsNewStorageBeforeStore() throws Exception {
        // Spring can report active synchronization without an actual database
        // transaction (e.g. a non-transactional caller that registered
        // synchronizations directly). The guard must reject new-object
        // creation in that state too, because a rollback callback registered
        // outside a real transaction can never fire.
        //
        // The scenario runs on a short-lived worker thread: activating
        // synchronization without a transaction confuses the shared
        // EntityManager proxy's per-thread caching on the JPA repository path,
        // so it must never touch the main test thread's persistence context.
        WebhookOrg org = newWebhookOrg("j2b2-l01");
        UUID orgId = org.org().getId();
        byte[] pdf = smallPdf();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = pool.submit(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    ingestionService.getOrStoreResumeFile(
                            orgId, HashUtil.sha256Hex(pdf), "application/pdf", pdf);
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    TransactionSynchronizationManager.clearSynchronization();
                }
            });
            Throwable thrown = outcome.get(30, TimeUnit.SECONDS);
            assertThat(thrown).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active transaction");
        } finally {
            pool.shutdownNow();
        }

        // Rejected BEFORE any physical store attempt; main-thread persistence
        // context is untouched and still healthy for these queries.
        assertThat(storage.storeAttempts.get()).isZero();
        assertThat(storage.objects).isEmpty();
        assertThat(resumeFileRepo.countByOrganizationId(orgId)).isZero();
        assertThat(messageRepo.countByOrganizationId(orgId)).isZero();
    }
}
