package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.document.ResumeFile;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.document.StoragePort;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.profile.ResumeVersion;
import com.kartgen.hireflow.profile.ResumeVersionRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real End-to-End integration test covering the complete Single Page Application (SPA)
 * user journey and HTTP contract for HireFlow AI.
 *
 * Exercises every endpoint that `index.html` calls, verifying payload structures,
 * authentication/role enforcement, binary streaming, and error handling.
 */
@Transactional
class FrontendSpaEndToEndIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private CandidateRepository candidateRepo;

    @Autowired
    private ResumeFileRepository resumeFileRepo;

    @Autowired
    private ResumeVersionRepository resumeVersionRepo;

    @Autowired
    private StoragePort storagePort;

    @Test
    @DisplayName("Complete Frontend SPA lifecycle: UI load, auth, KPIs, candidate pipeline, and signed document streaming")
    void executeCompleteFrontendSpaWorkflow() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);

        // 1. Static SPA UI Delivery
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HireFlow AI")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Test Console")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("autoLogin")));

        // 2. Auto-login on SPA bootstrap
        String loginPayload = objectMapper.writeValueAsString(Map.of(
                "email", "hr@kartgen.com",
                "password", "password123"
        ));

        String authResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.email").value("hr@kartgen.com"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.organizationId").value(ORG_ID))
                .andReturn().getResponse().getContentAsString();

        JsonNode authJson = objectMapper.readTree(authResponse);
        String token = authJson.get("token").asText();
        assertThat(token).isNotBlank();

        // 2b. Bad credentials rejection
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "hr@kartgen.com",
                                "password", "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));

        // 3. Operational KPIs query
        mockMvc.perform(get("/api/v1/operations/health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueDepth").isNumber())
                .andExpect(jsonPath("$.totalCandidates").isNumber())
                .andExpect(jsonPath("$.totalResumeFiles").isNumber())
                .andExpect(jsonPath("$.processedCount").isNumber());

        // 4. Extension token generation
        String extTokenRes = mockMvc.perform(post("/api/v1/auth/extension-token")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensionToken").isString())
                .andReturn().getResponse().getContentAsString();

        String extensionToken = objectMapper.readTree(extTokenRes).get("extensionToken").asText();
        assertThat(extensionToken).startsWith("hf_ext_live_org_");

        // 5. Extension pre-flight SHA-256 check
        String preCheckJson = objectMapper.writeValueAsString(Map.of(
                "sha256List", List.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        ));

        mockMvc.perform(post("/api/v1/uploads/check-hashes")
                        .header("Authorization", "Bearer " + extensionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preCheckJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existingHashes").isArray());

        // 6. Direct Resume File Upload
        byte[] dummyPdf = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile resumeFile = new MockMultipartFile(
                "files", "jane_applicant_cv.pdf", "application/pdf", dummyPdf);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(resumeFile)
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.acceptedCount").value(1));

        // 7. Requisition Listings
        mockMvc.perform(get("/api/v1/jobs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].title").isString());

        // 8. Seed Candidate & Resume Version for Drawer Verification
        byte[] pdfData = "%PDF-1.7\nJane Doe - Senior Full Stack Developer\nSkills: Java, Spring Boot, React\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        String sha256 = HashUtil.sha256Hex(pdfData);
        String objectKey = storagePort.store(orgId, sha256, "application/pdf", pdfData);

        ResumeFile rf = resumeFileRepo.save(ResumeFile.builder()
                .organizationId(orgId)
                .sha256(sha256)
                .mimeType("application/pdf")
                .s3Bucket("local")
                .s3ObjectKey(objectKey)
                .sizeBytes((long) pdfData.length)
                .extractedRawText("Jane Doe - Senior Full Stack Developer")
                .build());

        Candidate cand = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Jane Doe")
                .primaryEmail("jane.doe." + UUID.randomUUID() + "@example.com")
                .primaryPhone("+1-555-0199")
                .currentCity("San Francisco")
                .currentExperienceMonths(48)
                .status("ACTIVE")
                .build());

        ResumeVersion rv = resumeVersionRepo.save(ResumeVersion.builder()
                .organizationId(orgId)
                .candidateId(cand.getId())
                .resumeFileId(rf.getId())
                .versionNumber(1)
                .parseStatus("PROCESSED")
                .validationStatus("VALID")
                .build());

        cand.setCurrentResumeVersionId(rv.getId());
        candidateRepo.save(cand);

        // 9. Candidate Table Exploration with Pagination & Search
        mockMvc.perform(get("/api/v1/candidates?page=0&size=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.totalElements").isNumber());

        // 10. Candidate Drawer: Details, Versions, Applications, and Resume PDF Viewing
        // 10a. Detail
        mockMvc.perform(get("/api/v1/candidates/" + cand.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cand.getId().toString()))
                .andExpect(jsonPath("$.displayName").value("Jane Doe"))
                .andExpect(jsonPath("$.currentExperienceMonths").value(48));

        // 10b. Versions
        mockMvc.perform(get("/api/v1/candidates/" + cand.getId() + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].versionNumber").value(1));

        // 10c. Applications before applying
        mockMvc.perform(get("/api/v1/candidates/" + cand.getId() + "/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 10d. Create Application via API
        String createJobApp = objectMapper.writeValueAsString(Map.of(
                "candidateId", cand.getId().toString(),
                "jobId", JOB_ID,
                "resumeVersionId", rv.getId().toString()
        ));
        String appRes = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobApp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("NEW"))
                .andReturn().getResponse().getContentAsString();

        UUID createdAppId = UUID.fromString(objectMapper.readTree(appRes).get("id").asText());

        // Update stage to SCREENING
        mockMvc.perform(patch("/api/v1/applications/" + createdAppId + "/stage")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stage", "SCREENING",
                                "note", "Passed resume filter"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("SCREENING"));

        // Applications list now has 1 application
        mockMvc.perform(get("/api/v1/candidates/" + cand.getId() + "/applications")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentStage").value("SCREENING"));

        // 10e. Signed URL and File Streaming
        String urlResponse = mockMvc.perform(get("/api/v1/resume-versions/" + rv.getId() + "/view-url")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString())
                .andReturn().getResponse().getContentAsString();

        String signedUrl = objectMapper.readTree(urlResponse).get("url").asText();
        assertThat(signedUrl).contains("/api/v1/local-s3/");

        String s3Token = signedUrl.substring(signedUrl.lastIndexOf('/') + 1);
        mockMvc.perform(get("/api/v1/local-s3/" + s3Token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(content().bytes(pdfData));

        // 11. Mail Connection Management
        mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 12. Security boundaries & error conditions
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        mockMvc.perform(get("/api/v1/candidates/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }
}
