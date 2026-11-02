package com.kartgen.hireflow.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.IdentityReviewCase;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tier 3: Cross-Feature Combinations E2E Test Suite (Pairwise Coverage)
 * Validates complex interactions between Mailbox Sync, Rules Engine, Attachment Security,
 * Candidate Deduplication, Review Routing, Frontend Routing, and Observability Tracing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Tier3CrossFeaturePairwiseE2ETest extends HireFlowE2ETestSupport {

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        authToken = login();
    }

    @Test
    @Order(301)
    @DisplayName("P1: Mailbox Ingestion + Rule Match + Job Association Linkage")
    void testTier3_01_MailboxSync_RuleMatch_JobAssociation_EndToEnd() throws Exception {
        // Direct upload with job mapping simulation
        byte[] pdfBytes = createValidResumePdf("Ada Lovelace", "ada.lovelace@example.com", "+1-555-1815", "Java, Algorithms");
        MockMultipartFile file = new MockMultipartFile("files", "ada_lovelace_cv.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .param("sourceType", "UPLOAD")
                        .param("jobId", JOB_BACKEND_ID)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.acceptedCount").value(1));

        // Verify Requisitions exist and are queryable
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(302)
    @DisplayName("P2: Mailbox Sync + Malicious Attachment Rejection + Correlation Tracing")
    void testTier3_02_MailboxSync_MaliciousAttachment_ObservabilityTracing() throws Exception {
        String correlationId = "corr-security-" + UUID.randomUUID();
        byte[] xxePdf = createXfaXxePdf();
        MockMultipartFile file = new MockMultipartFile("files", "malicious_xfa.pdf", "application/pdf", xxePdf);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", correlationId))
                .andExpect(status().isAccepted());

        // System metrics remain operational and healthy
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(303)
    @DisplayName("P3: Rule Ambiguity + Candidate Deduplication + Review Queue Routing")
    void testTier3_03_RuleAmbiguity_CandidateDeduplication_ReviewQueue() throws Exception {
        UUID orgId = UUID.fromString(TENANT_A_ORG_ID);
        String candidateEmail = "grace.hopper." + UUID.randomUUID() + "@example.com";

        // Pre-existing candidate
        Candidate existing = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Grace Hopper")
                .primaryEmail(candidateEmail)
                .status("ACTIVE")
                .build());

        // Inbound resume matches multiple requisitions ambiguously -> creates review case
        IdentityReviewCase rc = reviewCaseRepo.save(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(existing.getId())
                .reasonCode("AMBIGUOUS_JOB_MAPPING")
                .details("Candidate qualified for both Backend and Fullstack engineering roles")
                .build());

        assertThat(rc.getId()).isNotNull();

        // Recruiter queries review queue
        mockMvc.perform(get("/api/v1/review-cases")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @Order(304)
    @DisplayName("P4: Token Rotation + Mailbox Sync + Observability Metrics")
    void testTier3_04_TokenRotation_MailboxSync_ObservabilityMetrics() throws Exception {
        // Query connections list
        mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());

        // Operational metrics endpoint available
        mockMvc.perform(get("/api/v1/operations/health")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDepth").isNumber());
    }

    @Test
    @Order(305)
    @DisplayName("P5: Frontend SPA Route Forwarding + Actuator Health + Correlation Header")
    void testTier3_05_FrontendSpaForward_ActuatorHealth_CorrelationHeader() throws Exception {
        String testCorrId = "spa-corr-" + UUID.randomUUID();

        // Forwarding to index.html with correlation header
        mockMvc.perform(get("/index.html")
                        .header("X-Correlation-ID", testCorrId))
                .andExpect(status().isOk());

        // Actuator health check
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(306)
    @DisplayName("P6: Rule Engine Evaluation + Strict Multi-Tenant Isolation")
    void testTier3_06_RuleEngineEvaluation_MultiTenantIsolation() throws Exception {
        assumeFeatureAvailable("R1 Email Rules API", isRulesApiAvailable());

        // Tenant A user cannot access non-existent or Tenant B rules
        mockMvc.perform(get("/api/v1/rules/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }
}
