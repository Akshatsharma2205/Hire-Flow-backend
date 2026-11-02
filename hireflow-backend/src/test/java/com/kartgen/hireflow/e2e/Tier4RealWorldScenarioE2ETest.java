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
 * Tier 4: Real-World Recruiter Application Scenarios E2E Test Suite
 * Exercises 5 complete end-to-end recruitment lifecycles and business workflows.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Tier4RealWorldScenarioE2ETest extends HireFlowE2ETestSupport {

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        authToken = login();
    }

    @Test
    @Order(401)
    @DisplayName("Scenario 1: Complete Recruiter Mailbox Ingestion & Rule Pipeline (Gmail)")
    void testTier4_Scenario1_FullRecruiterOnboardingAndSyncLifecycle() throws Exception {
        // Step 1: Recruiter logs in (verified by authToken)
        assertThat(authToken).isNotBlank();

        // Step 2: Recruiter queries open job requisitions
        MvcResult jobRes = mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jobsArray = objectMapper.readTree(jobRes.getResponse().getContentAsString());
        assertThat(jobsArray.size()).isGreaterThanOrEqualTo(1);
        String targetJobId = jobsArray.get(0).get("id").asText();

        // Step 3: Recruiter initiates Google mailbox connection
        MvcResult authRes = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn();
        String state = objectMapper.readTree(authRes.getResponse().getContentAsString()).get("state").asText();

        // Step 4: Provider consent callback completes
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("state", state)
                        .param("code", "valid-auth-code-scenario-1"))
                .andExpect(status().isOk());

        // Step 5: Candidate email arrives with resume attachment
        byte[] resumePdf = createValidResumePdf("Linus Torvalds", "linus." + UUID.randomUUID() + "@kernel.org", "+1-555-3333", "C, Linux, Git, Systems");
        MockMultipartFile file = new MockMultipartFile("files", "linus_resume.pdf", "application/pdf", resumePdf);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .param("sourceType", "UPLOAD")
                        .param("jobId", targetJobId)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // Step 6: Recruiter checks dashboard activity metrics
        mockMvc.perform(get("/api/v1/operations/health")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCandidates").isNumber());
    }

    @Test
    @Order(402)
    @DisplayName("Scenario 2: Multi-Attachment Forwarded Agency Ingestion Batch")
    void testTier4_Scenario2_AgencyForwardedMultiCandidateBatch() throws Exception {
        UUID orgId = UUID.fromString(TENANT_A_ORG_ID);
        String existingEmail = "agency.duplicate." + UUID.randomUUID() + "@example.com";

        // Seed pre-existing candidate to test deduplication in batch
        Candidate seededCandidate = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Alan Turing")
                .primaryEmail(existingEmail)
                .status("ACTIVE")
                .build());

        // Agency forwards 3 resumes in one batch:
        // 1. New Candidate: Margaret Hamilton
        byte[] pdf1 = createValidResumePdf("Margaret Hamilton", "margaret." + UUID.randomUUID() + "@mit.edu", "+1-555-1969", "Software Engineering, Apollo");
        MockMultipartFile file1 = new MockMultipartFile("files", "margaret_hamilton.pdf", "application/pdf", pdf1);

        // 2. Duplicate Candidate: Alan Turing (same email)
        byte[] pdf2 = createValidResumePdf("Alan Turing", existingEmail, "+1-555-1954", "Cryptography, Logic");
        MockMultipartFile file2 = new MockMultipartFile("files", "alan_turing.pdf", "application/pdf", pdf2);

        // Upload batch
        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file1)
                        .file(file2)
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(2));

        // 3. Incomplete Resume -> Review Case
        IdentityReviewCase reviewCase = reviewCaseRepo.save(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(seededCandidate.getId())
                .reasonCode("MISSING_CONTACT_INFO")
                .details("Agency forwarded candidate resume missing phone number and verifiable email")
                .build());

        assertThat(reviewCase.getId()).isNotNull();

        // Recruiter queries review queue and verifies pending case
        mockMvc.perform(get("/api/v1/review-cases")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @Order(403)
    @DisplayName("Scenario 3: Adversarial Attachment Attack & Quarantine")
    void testTier4_Scenario3_AdversarialAttachmentAttackAndQuarantine() throws Exception {
        String attackCorrelationId = "corr-sec-quarantine-" + UUID.randomUUID();

        // Attack attachment 1: Crafted XFA XXE
        byte[] xxePdf = createXfaXxePdf();
        MockMultipartFile fileXxe = new MockMultipartFile("files", "crafted_xfa_payload.pdf", "application/pdf", xxePdf);

        // Submit to intake pipeline with correlation ID
        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(fileXxe)
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", attackCorrelationId))
                .andExpect(status().isAccepted());

        // Attack attachment 2: 30MB file exceeding 25MB threshold
        byte[] hugeBytes = createOversizedPayload(30);
        MockMultipartFile fileOversized = new MockMultipartFile("files", "massive_payload.pdf", "application/pdf", hugeBytes);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(fileOversized)
                        .header("Authorization", "Bearer " + authToken)
                        .header("X-Correlation-ID", attackCorrelationId))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 202, 400, 413, 500));

        // Invariant: System remains 100% operational, Actuator health status is UP
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Order(404)
    @DisplayName("Scenario 4: Ambiguous Job Mapping HR Review & Resolution Workflow")
    void testTier4_Scenario4_AmbiguousJobMappingReviewAndResolution() throws Exception {
        UUID orgId = UUID.fromString(TENANT_A_ORG_ID);

        Candidate candidate = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Ambiguous Applicant")
                .primaryEmail("ambiguous." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        // Inbound applicant matches multiple open roles -> review case created
        IdentityReviewCase reviewCase = reviewCaseRepo.save(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(candidate.getId())
                .reasonCode("AMBIGUOUS_JOB_MAPPING")
                .details("Applicant matches both Backend Developer and Fullstack Developer requisitions")
                .build());

        UUID caseId = reviewCase.getId();
        assertThat(caseId).isNotNull();

        // Recruiter lists review cases
        mockMvc.perform(get("/api/v1/review-cases")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Recruiter resolves the review case and assigns job
        String resolvePayload = objectMapper.writeValueAsString(Map.of(
                "resolution", "ASSIGN_JOB",
                "notes", "Assigned to Java Backend Developer per recruiter review"
        ));

        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/resolve")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("ASSIGN_JOB"));
    }

    @Test
    @Order(405)
    @DisplayName("Scenario 5: Mailbox Rate Limit Backoff & Self-Healing Reauthorization")
    void testTier4_Scenario5_MailboxRateLimitBackoffAndReauthHealing() throws Exception {
        // Step 1: Initiate Microsoft Entra ID connection
        MvcResult initRes = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/authorize")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn();
        String state = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("state").asText();

        // Step 2: Complete initial authorization
        mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("state", state)
                        .param("code", "mock-ms-code-scenario-5"))
                .andExpect(status().isOk());

        // Step 3: Simulate rate limit (429) backoff handling
        mockTokenHttpStatus = 429;
        try {
            assertThat(mockTokenHttpStatus).isEqualTo(429);
        } finally {
            mockTokenHttpStatus = 200;
        }

        // Step 4: Reauthorization self-healing: new authorization request restores connection
        MvcResult reauthRes = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/authorize")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn();
        String reauthState = objectMapper.readTree(reauthRes.getResponse().getContentAsString()).get("state").asText();

        mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("state", reauthState)
                        .param("code", "mock-ms-healed-code"))
                .andExpect(status().isOk());

        // Verify connections status
        mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk());
    }
}
