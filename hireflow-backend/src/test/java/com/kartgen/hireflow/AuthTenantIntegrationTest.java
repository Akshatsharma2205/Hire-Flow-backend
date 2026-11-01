package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUser;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.User;
import com.kartgen.hireflow.tenant.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTenantIntegrationTest extends IntegrationTestSupport {

    private static final String SEEDED_EXTENSION_TOKEN = "hf_ext_live_org_kartgen_01_demo12345";

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private UserRepository userRepo;

    @Test
    void loginReturnsJwtWithOrganizationAndRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "hr@kartgen.com", "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.organizationId").value(ORG_ID))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.organizationName").value("Kartgen Infotech LLP"));
    }

    @Test
    void loginRejectsWrongPasswordWithGenericError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "hr@kartgen.com", "password", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void protectedEndpointsRequireAuthenticationWithJsonError() throws Exception {
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/v1/candidates"));
    }

    @Test
    void seededExtensionTokenAuthenticatesAgainstItsOrganization() throws Exception {
        JsonNode page = getJson("/api/v1/candidates", SEEDED_EXTENSION_TOKEN);
        assertThat(page.path("content").isArray()).isTrue();
        assertThat(page.path("page").asInt()).isZero();
    }

    @Test
    void jwtFromAnotherOrganizationCannotReadTenantData() throws Exception {
        Organization foreignOrg = orgRepo.save(Organization.builder()
                .name("Foreign Org")
                .slug("foreign-org-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User foreignUser = userRepo.save(User.builder()
                .email("foreign-" + UUID.randomUUID().toString().substring(0, 8) + "@foreign.com")
                .displayName("Foreign User")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(foreignOrg.getId())
                .userId(foreignUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        String foreignOrgToken = jwtService.generateToken(
                foreignUser.getId(), foreignUser.getEmail(), foreignOrg.getId(), "OWNER");

        JsonNode page = getJson("/api/v1/candidates", foreignOrgToken);
        assertThat(page.path("totalElements").asInt()).isZero();

        mockMvc.perform(get("/api/v1/jobs/" + JOB_ID)
                        .header("Authorization", "Bearer " + foreignOrgToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleWithoutPermissionGetsForbidden() throws Exception {
        User viewerUser = userRepo.save(User.builder()
                .email("viewer-" + UUID.randomUUID().toString().substring(0, 8) + "@kartgen.com")
                .displayName("Viewer User")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(viewerUser.getId())
                .role("VIEWER")
                .status("ACTIVE")
                .build());

        String viewerToken = jwtService.generateToken(
                viewerUser.getId(), viewerUser.getEmail(),
                UUID.fromString(ORG_ID), "VIEWER");

        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Unauthorized Job"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void malformedJwtIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer not.a.real.jwt"))
                .andExpect(status().isUnauthorized());
    }
}
