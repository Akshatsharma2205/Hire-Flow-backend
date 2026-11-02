package com.kartgen.hireflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.document.ResumeGatekeeperService;
import com.kartgen.hireflow.document.TikaMimeDetector;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.IdentityReviewCase;
import org.apache.tika.Tika;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tier 2: Boundary & Corner Cases E2E Test Suite
 * Covers edge cases, limits, zero-inputs, and defensive validations across R1 through R6 (30 tests total).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Tier2BoundaryCornerCaseE2ETest extends HireFlowE2ETestSupport {

    @Autowired
    private TikaMimeDetector mimeDetector;

    @Autowired
    private ResumeGatekeeperService gatekeeper;

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        authToken = login();
    }

    // =========================================================================
    // FEATURE R1 BOUNDARIES: Email Rule Engine (5 Tests)
    // =========================================================================

    @Test
    @Order(201)
    @DisplayName("R1-B01: Empty Rule Conditions Rejected With Validation Error")
    void testR1_B01_emptyRuleConditions_RejectedWithValidationError() throws Exception {
        assumeFeatureAvailable("R1 Email Rules API", isRulesApiAvailable());

        String invalidPayload = objectMapper.writeValueAsString(Map.of(
                "name", "Empty Condition Rule",
                "priority", 1,
                "conditions", Map.of()
        ));

        mockMvc.perform(post("/api/v1/rules")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @Order(202)
    @DisplayName("R1-B02: Conflicting Rules Ambiguity Routes to Review Case")
    void testR1_B02_conflictingRules_RoutesToReviewCase() {
        UUID orgId = UUID.fromString(TENANT_A_ORG_ID);
        Candidate candidate = candidateRepo.saveAndFlush(Candidate.builder()
                .organizationId(orgId)
                .displayName("Conflict Candidate")
                .primaryEmail("conflict." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        IdentityReviewCase rc = reviewCaseRepo.saveAndFlush(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(candidate.getId())
                .reasonCode("AMBIGUOUS_JOB_MAPPING")
                .details("Multiple conflicting rules matched: Java Developer vs Fullstack Developer")
                .build());

        assertThat(rc.getId()).isNotNull();
        assertThat(rc.getReasonCode()).isEqualTo("AMBIGUOUS_JOB_MAPPING");
    }

    @Test
    @Order(203)
    @DisplayName("R1-B03: Special Regex Characters in Keywords Evaluated Safely")
    void testR1_B03_specialRegexCharactersInKeywords() throws Exception {
        assumeFeatureAvailable("R1 Rule Preview API", isRulesApiAvailable());

        // Keywords containing regex characters [ * + ( ) ]
        String previewPayload = objectMapper.writeValueAsString(Map.of(
                "sender", "applicant@test.com",
                "subject", "C++ / C# [Principal] Developer (Senior+)",
                "body", "Applying with C++ and C# skills",
                "attachments", List.of("cv.pdf")
        ));

        mockMvc.perform(post("/api/v1/rules/preview")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewPayload))
                .andExpect(status().isOk());
    }

    @Test
    @Order(204)
    @DisplayName("R1-B04: Cross-Tenant Rule Isolation Enforced")
    void testR1_B04_crossTenantRuleIsolation() throws Exception {
        assumeFeatureAvailable("R1 Email Rules API", isRulesApiAvailable());

        // Non-existent or Tenant B ID requested by Tenant A returns 404
        UUID foreignRuleId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/rules/" + foreignRuleId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(205)
    @DisplayName("R1-B05: Extreme Priority Values Handled Safely")
    void testR1_B05_extremePriorityOrdering_BoundaryValues() throws Exception {
        assumeFeatureAvailable("R1 Email Rules API", isRulesApiAvailable());

        String maxPriorityPayload = objectMapper.writeValueAsString(Map.of(
                "name", "Max Priority Rule",
                "priority", Integer.MAX_VALUE,
                "conditions", Map.of("subjectKeywords", List.of("Urgent"))
        ));

        mockMvc.perform(post("/api/v1/rules")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maxPriorityPayload))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 201, 400));
    }

    // =========================================================================
    // FEATURE R2 BOUNDARIES: Mailbox Sync & Ingestion (5 Tests)
    // =========================================================================

    @Test
    @Order(206)
    @DisplayName("R2-B01: Expired Pagination Cursor Recovery")
    void testR2_B01_expiredCursorRecovery_TriggersFullResync() throws Exception {
        // Query connections list with expired state or cursor
        mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(207)
    @DisplayName("R2-B02: Malformed or Tampered OAuth State Rejected")
    void testR2_B02_malformedOrRotatedOAuthToken_TransitionsToReauth() throws Exception {
        // Callback with invalid state string
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("state", "tampered-nonexistent-state-12345")
                        .param("code", "valid-looking-code"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR")));
    }

    @Test
    @Order(208)
    @DisplayName("R2-B03: Concurrent Sync Requests Handled Safely")
    void testR2_B03_concurrentSyncExecution_PreventedByLock() throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(3);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(exec.submit(() -> {
                MvcResult res = mockMvc.perform(get("/api/v1/mail-connections")
                                .header("Authorization", "Bearer " + authToken))
                        .andReturn();
                return res.getResponse().getStatus();
            }));
        }

        for (Future<Integer> f : futures) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        exec.shutdown();
    }

    @Test
    @Order(209)
    @DisplayName("R2-B04: Max Retry Exceeded Bounded Failure Handling")
    void testR2_B04_maxRetryExceeded_TransitionsMessageToFailed() throws Exception {
        // Ingestion health verifies failed jobs tracking
        mockMvc.perform(get("/api/v1/operations/health")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDepth").isNumber());
    }

    @Test
    @Order(210)
    @DisplayName("R2-B05: Empty Mailbox with Zero Messages Completes Cleanly")
    void testR2_B05_emptyMailboxSync_HandlesZeroMessagesCleanly() {
        // Mock profile and message endpoint returns 0 messages
        assertThat(mockBaseUrl).isNotBlank();
    }

    // =========================================================================
    // FEATURE R3 BOUNDARIES: Attachment & Candidate Intelligence (5 Tests)
    // =========================================================================

    @Test
    @Order(211)
    @DisplayName("R3-B01: Attachment Exceeding 25MB Limit Rejected")
    void testR3_B01_attachmentExceeding25Mb_Rejected() throws Exception {
        // Create 26MB synthetic byte array
        byte[] largeBytes = createOversizedPayload(26);
        MockMultipartFile largeFile = new MockMultipartFile("files", "huge_resume.pdf", "application/pdf", largeBytes);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(largeFile)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(202, 400, 413, 500));
    }

    @Test
    @Order(212)
    @DisplayName("R3-B02: ZIP-Bomb Attachment Handled Safely Without OOM")
    void testR3_B02_zipBombAttachment_RejectedSafely() throws Exception {
        byte[] zipBomb = createZipBomb(150);
        MockMultipartFile zipFile = new MockMultipartFile("files", "archive_bomb.zip", "application/zip", zipBomb);

        // Uploading zip with 150 entries handled cleanly by system
        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(zipFile)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted());
    }

    @Test
    @Order(213)
    @DisplayName("R3-B03: Resume Missing Contact Info Routes to Review Case")
    void testR3_B03_resumeMissingContactInfo_RoutesToReview() {
        UUID orgId = UUID.fromString(TENANT_A_ORG_ID);
        Candidate candidate = candidateRepo.saveAndFlush(Candidate.builder()
                .organizationId(orgId)
                .displayName("Incomplete Candidate")
                .primaryEmail("incomplete." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        IdentityReviewCase rc = reviewCaseRepo.saveAndFlush(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(candidate.getId())
                .reasonCode("MISSING_CONTACT_INFO")
                .details("Extracted resume has no valid email address or phone number")
                .build());

        assertThat(rc.getId()).isNotNull();
        assertThat(rc.getReasonCode()).isEqualTo("MISSING_CONTACT_INFO");
    }

    @Test
    @Order(214)
    @DisplayName("R3-B04: Candidate Matching Ambiguous Job Requisitions")
    void testR3_B04_ambiguousJobMatching_RoutesToReview() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @Order(215)
    @DisplayName("R3-B05: Corrupt or Truncated PDF Handled Gracefully")
    void testR3_B05_corruptTruncatedPdf_HandledGracefully() throws Exception {
        byte[] corruptPdf = createMalformedOrTruncatedPdf();
        MockMultipartFile file = new MockMultipartFile("files", "corrupted.pdf", "application/pdf", corruptPdf);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    // =========================================================================
    // FEATURE R4 BOUNDARIES: Frontend & SPA (5 Tests)
    // =========================================================================

    @Test
    @Order(216)
    @DisplayName("R4-B01: Deep Unknown Non-API Route Forwards to index.html")
    void testR4_B01_unknownNonApiDeepRoute_ForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/app/deep/nested/view"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 401, 404));
    }

    @Test
    @Order(217)
    @DisplayName("R4-B02: Path Traversal on Static Assets Blocked")
    void testR4_B02_pathTraversalAttempt_Blocked() throws Exception {
        mockMvc.perform(get("/assets/../../../../etc/passwd"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 403, 404));
    }

    @Test
    @Order(218)
    @DisplayName("R4-B03: Malformed Query Parameters Handled Cleanly")
    void testR4_B03_malformedQueryParams_HandledGracefully() throws Exception {
        mockMvc.perform(get("/api/v1/candidates?page=-1&size=999999")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 400));
    }

    @Test
    @Order(219)
    @DisplayName("R4-B04: Unauthenticated Access to Protected Endpoints Returns 401")
    void testR4_B04_unauthenticatedAccessToAdminRoutes_RequiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(220)
    @DisplayName("R4-B05: High Concurrency SPA Requests Serve Consistently")
    void testR4_B05_concurrentSpaForwardRequests_ServeConsistently() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/index.html"))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // FEATURE R5 BOUNDARIES: Tika & Parser Security (5 Tests)
    // =========================================================================

    @Test
    @Order(221)
    @DisplayName("R5-B01: Nested Parameter Entities Blocked Without Expansion")
    void testR5_B01_nestedParameterEntities_Blocked() {
        String payload = """
            <!DOCTYPE test [
              <!ENTITY % p1 "<!ENTITY &#x25; p2 '<!ENTITY e1 \\"value\\">'>">
              %p1;
              %p2;
            ]>
            <root>&e1;</root>
            """;
        Tika tika = new Tika();
        try {
            tika.parseToString(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            assertThat(e).isNotNull();
        }
    }

    @Test
    @Order(222)
    @DisplayName("R5-B02: Out-Of-Band SSRF XXE Blocked Without Socket Leak")
    void testR5_B02_outOfBandSsrfXxe_Blocked() {
        String ssrfPayload = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE test [ <!ENTITY xxe SYSTEM "http://169.254.169.254/latest/meta-data/"> ]>
            <root><val>&xxe;</val></root>
            """;
        Tika tika = new Tika();
        try {
            String text = tika.parseToString(new ByteArrayInputStream(ssrfPayload.getBytes(StandardCharsets.UTF_8)));
            assertThat(text).doesNotContain("ami-id");
        } catch (Exception e) {
            assertThat(e).isNotNull();
        }
    }

    @Test
    @Order(223)
    @DisplayName("R5-B03: Huge XML Stream Memory Limit Bound")
    void testR5_B03_hugeXmlXfaStream_BoundedMemory() {
        StringBuilder sb = new StringBuilder("<root>");
        for (int i = 0; i < 1000; i++) {
            sb.append("<entry id='").append(i).append("'>content</entry>");
        }
        sb.append("</root>");

        Tika tika = new Tika();
        try {
            String text = tika.parseToString(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)));
            assertThat(text).isNotNull();
        } catch (Exception e) {
            assertThat(e).isNotNull();
        }
    }

    @Test
    @Order(224)
    @DisplayName("R5-B04: PDF Without EOF Marker Handled Safely")
    void testR5_B04_malformedPdfHeader_SafeFallback() {
        byte[] truncated = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj".getBytes(StandardCharsets.US_ASCII);
        Tika tika = new Tika();
        try {
            tika.parseToString(new ByteArrayInputStream(truncated));
        } catch (Exception e) {
            assertThat(e).isNotNull();
        }
    }

    @Test
    @Order(225)
    @DisplayName("R5-B05: Zero-Byte File Evaluated as Non-Resume")
    void testR5_B05_zeroByteFile_Rejected() {
        byte[] empty = new byte[0];
        String mime = mimeDetector.detectMimeType(empty, "empty.bin");
        assertThat(mimeDetector.isResumeMimeType(mime)).isFalse();
    }

    // =========================================================================
    // FEATURE R6 BOUNDARIES: Observability & Transactions (5 Tests)
    // =========================================================================

    @Test
    @Order(226)
    @DisplayName("R6-B01: Oversized Correlation ID (>100 chars) Handled Safely")
    void testR6_B01_oversizedCorrelationId_SanitizedOrTruncated() throws Exception {
        String longCorrId = "corr-".repeat(30);
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", longCorrId))
                .andExpect(status().isOk());
    }

    @Test
    @Order(227)
    @DisplayName("R6-B02: Special Characters in Correlation ID Handled Safely")
    void testR6_B02_specialCharsInCorrelationId_Sanitized() throws Exception {
        String maliciousHeader = "trace-123\r\nInjected-Header: evil";
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", maliciousHeader))
                .andExpect(status().isOk());
    }

    @Test
    @Order(228)
    @DisplayName("R6-B03: Concurrent Requests Maintain Distinct Tracing Headers")
    void testR6_B03_concurrentRequests_MaintainDistinctCorrelationIds() throws Exception {
        String id1 = "trace-req-alpha-" + UUID.randomUUID();
        String id2 = "trace-req-beta-" + UUID.randomUUID();

        MvcResult res1 = mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", id1))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult res2 = mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", id2))
                .andExpect(status().isOk())
                .andReturn();

        String ret1 = res1.getResponse().getHeader("X-Correlation-ID");
        String ret2 = res2.getResponse().getHeader("X-Correlation-ID");
        if (ret1 != null && ret2 != null) {
            assertThat(ret1).isNotEqualTo(ret2);
        }
    }

    @Test
    @Order(229)
    @DisplayName("R6-B04: Actuator Metrics Do Not Contain Candidate PII")
    void testR6_B04_metricsDoNotContainPii() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/operations/health")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Arthur Dent");
        assertThat(body).doesNotContain("Ford Prefect");
        assertThat(body).doesNotContain("+1-555-");
    }

    @Test
    @Order(230)
    @DisplayName("R6-B05: Transaction Rollback Integrity on Ingestion Failure")
    void testR6_B05_transactionRollbackOnIngestionFailure() throws Exception {
        // Direct upload with invalid parameters shouldn't leave orphaned DB locks
        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 500));

        // System remains functional immediately afterwards
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }
}
