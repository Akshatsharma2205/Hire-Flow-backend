package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.support.TestResumePdf;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUser;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.User;
import com.kartgen.hireflow.tenant.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-1 regression coverage for the ingestion authorization boundary.
 *
 * Every test drives the real Spring Security filter chain and a real controller
 * endpoint. Users, organizations and memberships are synthetic and created per
 * test so the assertions do not depend on seeded candidate data.
 */
class IngestionAuthorizationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private InboundMessageRepository messageRepo;

    @Autowired
    private InboundAttachmentRepository attachmentRepo;

    private record Member(User user, Organization org, OrganizationUser membership) {}

    // ── 1. Anonymous ────────────────────────────────────────────────────────

    @Test
    void anonymousDirectUploadIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("anonymous")))
                        .param("sourceType", "UPLOAD"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── 2-5. VIEWER is denied on every ingestion/credential endpoint ────────

    @Test
    void viewerDirectUploadIsRejected() throws Exception {
        Member viewer = newMember("viewer-direct", "VIEWER");
        long before = messageRepo.countByOrganizationId(viewer.org().getId());

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("viewer-direct")))
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + jwt(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        assertThat(messageRepo.countByOrganizationId(viewer.org().getId())).isEqualTo(before);
    }

    @Test
    void viewerExtensionIntakeIsRejected() throws Exception {
        Member viewer = newMember("viewer-intake", "VIEWER");
        long before = messageRepo.countByOrganizationId(viewer.org().getId());

        mockMvc.perform(multipart("/api/v1/uploads/extension-intake")
                        .file(extensionFile(uniquePdf("viewer-intake")))
                        .header("Authorization", "Bearer " + jwt(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        assertThat(messageRepo.countByOrganizationId(viewer.org().getId())).isEqualTo(before);
    }

    @Test
    void viewerHashPreCheckIsRejected() throws Exception {
        Member viewer = newMember("viewer-hash", "VIEWER");

        mockMvc.perform(post("/api/v1/uploads/check-hashes")
                        .header("Authorization", "Bearer " + jwt(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sha256List", List.of("00000000000000000000000000000000")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void viewerExtensionTokenGenerationIsRejected() throws Exception {
        Member viewer = newMember("viewer-token", "VIEWER");

        mockMvc.perform(post("/api/v1/auth/extension-token")
                        .header("Authorization", "Bearer " + jwt(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        assertThat(reload(viewer).getExtensionTokenHash()).isNull();
    }

    // ── 6-8. RECRUITER keeps ingestion and personal token issuance ──────────

    @Test
    void recruiterDirectUploadIsAllowed() throws Exception {
        Member recruiter = newMember("recruiter-direct", "RECRUITER");
        long before = messageRepo.countByOrganizationId(recruiter.org().getId());

        MvcResult result = mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("recruiter-direct")))
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + jwt(recruiter)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andReturn();

        assertThat(messageRepo.countByOrganizationId(recruiter.org().getId()))
                .isEqualTo(before + 1);

        UUID attachmentId = attachmentId(result);
        assertThat(attachmentRepo.findByIdAndOrganizationId(attachmentId, recruiter.org().getId()))
                .isPresent();
    }

    @Test
    void recruiterExtensionIntakeIsAllowed() throws Exception {
        Member recruiter = newMember("recruiter-intake", "RECRUITER");
        long before = messageRepo.countByOrganizationId(recruiter.org().getId());

        MvcResult result = mockMvc.perform(multipart("/api/v1/uploads/extension-intake")
                        .file(extensionFile(uniquePdf("recruiter-intake")))
                        .header("Authorization", "Bearer " + jwt(recruiter)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attachmentId").isNotEmpty())
                .andReturn();

        assertThat(messageRepo.countByOrganizationId(recruiter.org().getId()))
                .isEqualTo(before + 1);
        assertThat(attachmentRepo.findByIdAndOrganizationId(
                attachmentId(result), recruiter.org().getId())).isPresent();
    }

    @Test
    void recruiterGeneratesTokenOnlyForOwnMembership() throws Exception {
        Member recruiter = newMember("recruiter-token", "RECRUITER");
        Member foreign = newMember("foreign-token", "OWNER");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/extension-token")
                        .header("Authorization", "Bearer " + jwt(recruiter))
                        .header("X-Tenant-ID", foreign.org().getId().toString())
                        .header("X-Organization-Id", foreign.org().getId().toString())
                        .header("X-Org-Id", foreign.org().getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensionToken").isNotEmpty())
                .andReturn();

        String extensionToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("extensionToken").asText();
        String expectedHash = HashUtil.sha256Hex(
                extensionToken.getBytes(StandardCharsets.UTF_8));

        assertThat(extensionToken)
                .startsWith("hf_ext_live_org_"
                        + recruiter.org().getId().toString().substring(0, 8) + "_");
        assertThat(reload(recruiter).getExtensionTokenHash()).isEqualTo(expectedHash);
        assertThat(reload(foreign).getExtensionTokenHash()).isNull();
    }

    // ── 9. ADMIN and OWNER retain ingestion access ──────────────────────────

    @Test
    void adminDirectUploadIsAllowed() throws Exception {
        Member admin = newMember("admin-direct", "ADMIN");
        long before = messageRepo.countByOrganizationId(admin.org().getId());

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("admin-direct")))
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + jwt(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));

        assertThat(messageRepo.countByOrganizationId(admin.org().getId()))
                .isEqualTo(before + 1);
    }

    @Test
    void ownerDirectUploadIsAllowed() throws Exception {
        UUID seededOrg = UUID.fromString(ORG_ID);
        long before = messageRepo.countByOrganizationId(seededOrg);

        mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("owner-direct")))
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + login()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));

        assertThat(messageRepo.countByOrganizationId(seededOrg)).isEqualTo(before + 1);
    }

    // ── 10. Tenant headers cannot redirect ingestion ─────────────────────────

    @Test
    void crossTenantOrganizationHeadersCannotRedirectDirectUpload() throws Exception {
        Member recruiter = newMember("spoof-source", "RECRUITER");
        Member foreign = newMember("spoof-target", "OWNER");
        long ownBefore = messageRepo.countByOrganizationId(recruiter.org().getId());
        long foreignBefore = messageRepo.countByOrganizationId(foreign.org().getId());

        MvcResult result = mockMvc.perform(multipart("/api/v1/uploads/direct")
                        .file(directFile(uniquePdf("spoof-source")))
                        .param("sourceType", "UPLOAD")
                        .header("Authorization", "Bearer " + jwt(recruiter))
                        .header("X-Tenant-ID", foreign.org().getId().toString())
                        .header("X-Organization-Id", foreign.org().getId().toString())
                        .header("X-Org-Id", foreign.org().getId().toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andReturn();

        assertThat(messageRepo.countByOrganizationId(recruiter.org().getId()))
                .isEqualTo(ownBefore + 1);
        assertThat(messageRepo.countByOrganizationId(foreign.org().getId()))
                .isEqualTo(foreignBefore);

        UUID attachmentId = attachmentId(result);
        assertThat(attachmentRepo.findByIdAndOrganizationId(attachmentId, recruiter.org().getId()))
                .isPresent();
        assertThat(attachmentRepo.findByIdAndOrganizationId(attachmentId, foreign.org().getId()))
                .isEmpty();
    }

    // ── 11. VIEWER read access is unchanged ─────────────────────────────────

    @Test
    void viewerCandidateReadsRemainUnchanged() throws Exception {
        Member viewer = newMember("viewer-read", "VIEWER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + jwt(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── 12. Existing extension-token authentication still works ─────────────

    @Test
    void existingRecruiterExtensionTokenStillAuthenticatesAndIngests() throws Exception {
        Member recruiter = newMember("ext-auth", "RECRUITER");
        String extensionToken = "hf_ext_live_j1_" + UUID.randomUUID().toString().replace("-", "");
        setExtensionToken(recruiter, extensionToken);
        long before = messageRepo.countByOrganizationId(recruiter.org().getId());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + extensionToken))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/uploads/extension-intake")
                        .file(extensionFile(uniquePdf("ext-auth")))
                        .header("Authorization", "Bearer " + extensionToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        assertThat(messageRepo.countByOrganizationId(recruiter.org().getId()))
                .isEqualTo(before + 1);
    }

    @Test
    void existingViewerExtensionTokenCanReadButCannotIngestOrMint() throws Exception {
        Member viewer = newMember("ext-viewer", "VIEWER");
        String extensionToken = "hf_ext_live_j1_" + UUID.randomUUID().toString().replace("-", "");
        setExtensionToken(viewer, extensionToken);

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + extensionToken))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/uploads/extension-intake")
                        .file(extensionFile(uniquePdf("ext-viewer")))
                        .header("Authorization", "Bearer " + extensionToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/uploads/check-hashes")
                        .header("Authorization", "Bearer " + extensionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sha256List", List.of("00000000000000000000000000000000")))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/extension-token")
                        .header("Authorization", "Bearer " + extensionToken))
                .andExpect(status().isForbidden());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private Member newMember(String prefix, String role) {
        Organization org = orgRepo.save(Organization.builder()
                .name("J1 " + prefix)
                .slug("j1-" + prefix.toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User user = userRepo.save(User.builder()
                .email(prefix.toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .displayName("J1 " + prefix)
                .passwordHash("unused-test-hash")
                .build());

        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role(role)
                .status("ACTIVE")
                .build());

        return new Member(user, org, membership);
    }

    private String jwt(Member member) {
        return jwtService.generateToken(
                member.user().getId(),
                member.user().getEmail(),
                member.org().getId(),
                member.membership().getRole());
    }

    private OrganizationUser reload(Member member) {
        return orgUserRepo.findById(member.membership().getId()).orElseThrow();
    }

    private void setExtensionToken(Member member, String rawToken) {
        OrganizationUser membership = reload(member);
        membership.setExtensionTokenHash(HashUtil.sha256Hex(
                rawToken.getBytes(StandardCharsets.UTF_8)));
        orgUserRepo.save(membership);
    }

    private byte[] uniquePdf(String tag) {
        String email = tag.toLowerCase(Locale.ROOT) + "."
                + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return TestResumePdf.create(TestResumePdf.resume(
                "J1 " + tag, email, "Pune", 5, "Jan 2026"));
    }

    private MockMultipartFile directFile(byte[] pdf) {
        return new MockMultipartFile(
                "files", "j1-resume.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);
    }

    private MockMultipartFile extensionFile(byte[] pdf) {
        return new MockMultipartFile(
                "file", "j1-resume.pdf", MediaType.APPLICATION_PDF_VALUE, pdf);
    }

    private UUID attachmentId(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = body.has("attachmentId")
                ? body.path("attachmentId").asText()
                : body.path("attachmentIds").get(0).asText();
        return UUID.fromString(id);
    }
}
