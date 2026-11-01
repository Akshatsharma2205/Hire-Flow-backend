package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.support.TestResumePdf;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real intake path: multipart upload → gatekeeper → heuristic
 * extraction → identity resolution → living profile → matching → application,
 * with the background worker doing the asynchronous parsing.
 */
class IntakePipelineIntegrationTest extends IntegrationTestSupport {

    @Test
    void directUploadCreatesCandidateProfileMatchAndApplicationIdempotently() throws Exception {
        String token = login();
        String email = "aarav.pipeline@example.com";
        byte[] pdf = TestResumePdf.create(TestResumePdf.resume(
                "Aarav Pipeline", email, "Pune", 5, "Jan 2026"));

        MvcResult first = upload(token, pdf, "aarav_pipeline.pdf");
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.path("acceptedCount").asInt()).isEqualTo(1);
        String attachmentId = firstBody.path("attachmentIds").get(0).asText();

        JsonNode candidatePage = awaitJson(
                "/api/v1/candidates?search=" + email, token,
                page -> page.path("totalElements").asInt() == 1,
                Duration.ofSeconds(30));
        JsonNode candidate = candidatePage.path("content").get(0);
        UUID candidateId = UUID.fromString(candidate.path("id").asText());

        assertThat(candidate.path("displayName").asText()).isEqualTo("Aarav Pipeline");
        assertThat(candidate.path("primaryEmail").asText()).isEqualTo(email);
        assertThat(candidate.path("currentExperienceMonths").asInt()).isEqualTo(60);
        assertThat(candidate.path("resumeVersion").asInt()).isEqualTo(1);
        assertThat(candidate.path("sourceType").asText()).isEqualTo("UPLOAD");
        assertThat(candidate.path("currentStage").asText()).isEqualTo("NEW");
        assertThat(candidate.path("skills").toString())
                .contains("Java", "Spring Boot", "PostgreSQL", "Docker", "Kafka");

        JsonNode versions = getJson("/api/v1/candidates/" + candidateId + "/versions", token);
        assertThat(versions.size()).isEqualTo(1);
        assertThat(versions.get(0).path("parseStatus").asText()).isEqualTo("READY");

        JsonNode applications = getJson(
                "/api/v1/candidates/" + candidateId + "/applications", token);
        assertThat(applications.size()).isEqualTo(1);
        assertThat(applications.get(0).path("jobTitle").asText())
                .isEqualTo("Java Backend Developer");
        assertThat(applications.get(0).path("currentStage").asText()).isEqualTo("NEW");
        assertThat(applications.get(0).path("attemptNumber").asInt()).isEqualTo(1);

        JsonNode matches = getJson("/api/v1/jobs/" + JOB_ID + "/matches?size=100", token);
        JsonNode ownMatch = null;
        for (JsonNode match : matches.path("content")) {
            if (candidateId.toString().equals(match.path("candidateId").asText())) {
                ownMatch = match;
                break;
            }
        }
        assertThat(ownMatch).as("match computed for candidate").isNotNull();
        assertThat(ownMatch.path("score").asDouble()).isBetween(0.0, 100.0);

        // Re-uploading the identical file must reuse the attachment and must not
        // create a second candidate, resume version, or application.
        MvcResult second = upload(token, pdf, "aarav_pipeline.pdf");
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.path("attachmentIds").get(0).asText()).isEqualTo(attachmentId);

        Thread.sleep(1000);
        assertThat(getJson("/api/v1/candidates/" + candidateId + "/versions", token).size())
                .isEqualTo(1);
        assertThat(getJson("/api/v1/candidates/" + candidateId + "/applications", token).size())
                .isEqualTo(1);
    }

    @Test
    void multiVersionUploadPromotesLivingProfileAndLinkedFilters() throws Exception {
        String token = login();
        String email = "rahul.sharma@example.com";
        byte[] v1Pdf = TestResumePdf.create(TestResumePdf.resume(
                "Rahul Sharma", email, "Bengaluru", 5, "Jan 2024"));
        byte[] v2Pdf = TestResumePdf.create(TestResumePdf.resume(
                "Rahul Sharma", email, "Bengaluru", 8, "Jan 2026"));

        upload(token, v1Pdf, "rahul_v1.pdf");
        awaitJson(
                "/api/v1/candidates?search=" + email, token,
                body -> body.path("totalElements").asInt() >= 1,
                Duration.ofSeconds(30));

        upload(token, v2Pdf, "rahul_v2.pdf");
        JsonNode page = awaitJson(
                "/api/v1/candidates?search=" + email, token,
                body -> body.path("totalElements").asInt() >= 1
                        && body.path("content").get(0).path("resumeVersion").asInt() >= 2,
                Duration.ofSeconds(40));

        JsonNode rahul = page.path("content").get(0);
        UUID rahulId = UUID.fromString(rahul.path("id").asText());

        assertThat(rahul.path("displayName").asText()).isEqualTo("Rahul Sharma");
        assertThat(rahul.path("primaryEmail").asText()).isEqualTo(email);
        assertThat(rahul.path("currentStage").asText()).isEqualTo("NEW");
        assertThat(rahul.path("currentExperienceMonths").asInt())
                .as("latest document wins (8 years)")
                .isEqualTo(96);
        assertThat(rahul.path("skills").toString()).contains("Kafka");

        JsonNode versions = getJson("/api/v1/candidates/" + rahulId + "/versions", token);
        assertThat(versions.size()).isGreaterThanOrEqualTo(2);

        // Server-side filters must narrow to the same candidate.
        JsonNode filtered = getJson(
                "/api/v1/candidates?search=Rahul%20Sharma&jobId=" + JOB_ID
                        + "&skills=Java", token);
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
        assertThat(filtered.path("content").get(0).path("id").asText())
                .isEqualTo(rahulId.toString());

        JsonNode applications = getJson(
                "/api/v1/candidates/" + rahulId + "/applications", token);
        assertThat(applications.size()).isGreaterThanOrEqualTo(1);
    }

    private MvcResult upload(String token, byte[] pdf, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", filename, MediaType.APPLICATION_PDF_VALUE, pdf);
        return mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(file)
                        .param("sourceType", "UPLOAD")
                        .param("jobId", JOB_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn();
    }
}
