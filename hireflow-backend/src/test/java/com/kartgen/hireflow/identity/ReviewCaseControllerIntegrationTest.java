package com.kartgen.hireflow.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class ReviewCaseControllerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private IdentityReviewCaseRepository reviewCaseRepo;

    @Autowired
    private CandidateRepository candidateRepo;

    @Test
    void listAndResolveIdentityReviewCasesViaHttp() throws Exception {
        String token = login();
        UUID orgId = UUID.fromString(ORG_ID);

        Candidate cand1 = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Candidate One")
                .primaryEmail("c1." + UUID.randomUUID() + "@example.com")
                .build());

        Candidate cand2 = candidateRepo.save(Candidate.builder()
                .organizationId(orgId)
                .displayName("Candidate Two")
                .primaryEmail("c2." + UUID.randomUUID() + "@example.com")
                .build());

        IdentityReviewCase rc = reviewCaseRepo.save(IdentityReviewCase.builder()
                .organizationId(orgId)
                .candidateId(cand1.getId())
                .conflictingCandidateId(cand2.getId())
                .reasonCode("NAME_PHONE_CONFLICT")
                .details("Similar phone number detected")
                .build());

        // 1. Unauthenticated request rejected
        mockMvc.perform(get("/api/v1/review-cases"))
                .andExpect(status().isUnauthorized());

        // 2. List unresolved review cases
        JsonNode list = getJson("/api/v1/review-cases", token);
        assertThat(list.isArray()).isTrue();
        boolean found = false;
        for (JsonNode item : list) {
            if (rc.getId().toString().equals(item.path("id").asText())) {
                found = true;
                assertThat(item.path("reasonCode").asText()).isEqualTo("NAME_PHONE_CONFLICT");
                break;
            }
        }
        assertThat(found).isTrue();

        // 3. Resolve review case successfully
        mockMvc.perform(post("/api/v1/review-cases/" + rc.getId() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("resolution", "MERGED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("MERGED"));

        // 4. Resolving again is rejected (already resolved)
        mockMvc.perform(post("/api/v1/review-cases/" + rc.getId() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("resolution", "DISMISSED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Review case is already resolved"));

        // 5. Unknown review case returns 404
        mockMvc.perform(post("/api/v1/review-cases/" + UUID.randomUUID() + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("resolution", "DISMISSED"))))
                .andExpect(status().isNotFound());
    }
}
