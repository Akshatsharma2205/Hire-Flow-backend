package com.kartgen.hireflow.hiring;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.identity.Candidate;
import com.kartgen.hireflow.identity.CandidateRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class ApplicationControllerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private CandidateRepository candidateRepo;

    @Autowired
    private ApplicationRepository applicationRepo;

    @Test
    void createAndManageApplicationLifecycleViaHttp() throws Exception {
        String token = login();
        UUID orgId = UUID.fromString(ORG_ID);

        Candidate candidate = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("HTTP Candidate")
                .primaryEmail("http.cand." + UUID.randomUUID() + "@example.com")
                .status("ACTIVE")
                .build());

        // 1. Unauthenticated creation is rejected
        mockMvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "candidateId", candidate.getId(),
                                "jobId", UUID.fromString(JOB_ID)))))
                .andExpect(status().isUnauthorized());

        // 2. Missing required fields rejected with 400
        mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceType", "MANUAL"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        // 3. Valid creation succeeds
        String createJson = objectMapper.writeValueAsString(Map.of(
                "candidateId", candidate.getId().toString(),
                "jobId", JOB_ID,
                "sourceType", "MANUAL"
        ));

        String res = mockMvc.perform(post("/api/v1/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateId").value(candidate.getId().toString()))
                .andExpect(jsonPath("$.currentStage").value("NEW"))
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(res);
        UUID appId = UUID.fromString(created.get("id").asText());

        // 4. Get by ID
        mockMvc.perform(get("/api/v1/applications/" + appId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appId.toString()))
                .andExpect(jsonPath("$.candidateName").value("HTTP Candidate"));

        // 5. List applications
        mockMvc.perform(get("/api/v1/applications?jobId=" + JOB_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 6. Update Stage to SCREENING
        mockMvc.perform(patch("/api/v1/applications/" + appId + "/stage")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stage", "SCREENING",
                                "note", "Passed initial screening"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("SCREENING"));

        // 7. Invalid stage rejected
        mockMvc.perform(patch("/api/v1/applications/" + appId + "/stage")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stage", "UNKNOWN_STAGE"))))
                .andExpect(status().isBadRequest());

        // 8. Stage History audit verification
        JsonNode history = getJson("/api/v1/applications/" + appId + "/history", token);
        assertThat(history.isArray()).isTrue();
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
        assertThat(history.get(0).get("toStage").asText()).isEqualTo("SCREENING");
        assertThat(history.get(1).get("toStage").asText()).isEqualTo("NEW");

        // 9. Unknown application returns 404
        mockMvc.perform(get("/api/v1/applications/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
