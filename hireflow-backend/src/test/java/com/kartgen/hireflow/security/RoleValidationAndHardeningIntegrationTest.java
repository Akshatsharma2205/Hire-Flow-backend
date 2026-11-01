package com.kartgen.hireflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Permanent regression tests for Issue 01D:
 * Strict Role Validation, Normalization & Authorization Hardening.
 *
 * Covers all 18 required scenarios for JWT and Extension Token authentication paths.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoleValidationAndHardeningIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        environment.setActiveProfiles("test");
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        environment.setActiveProfiles("test");
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private User createUser(String prefix) {
        return userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .displayName("Test User " + prefix)
                .passwordHash("hash")
                .build());
    }

    private Organization createOrg(String prefix) {
        return orgRepo.save(Organization.builder()
                .name("Org " + prefix)
                .slug(prefix.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
    }

    // =========================================================================
    // JWT TESTS (1 - 12)
    // =========================================================================

    @Test
    @DisplayName("JWT 1: ADMIN produces ROLE_ADMIN and grants admin endpoint access")
    void test01_jwtAdminProducesRoleAdminAuthorityAndAccessesAdminEndpoint() throws Exception {
        Organization org = createOrg("jwt1");
        User user = createUser("jwt1");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // Verify direct filter sets ROLE_ADMIN authority in SecurityContext
        AtomicReference<GrantedAuthority> capturedAuthority = new AtomicReference<>();
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
            capturedAuthority.set(auth.getAuthorities().iterator().next());
        });
        assertThat(capturedAuthority.get()).isNotNull();
        assertThat(capturedAuthority.get().getAuthority()).isEqualTo("ROLE_ADMIN");

        // Verify protected @PreAuthorize("hasAnyRole('OWNER','ADMIN')") endpoint permits access
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()); // 404 proves authorized; 403 would mean forbidden
    }

    @Test
    @DisplayName("JWT 2: OWNER produces ROLE_OWNER and grants admin endpoint access")
    void test02_jwtOwnerProducesRoleOwnerAuthorityAndAccessesAdminEndpoint() throws Exception {
        Organization org = createOrg("jwt2");
        User user = createUser("jwt2");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "OWNER");

        // Verify direct filter sets ROLE_OWNER authority
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_OWNER");
        });

        // Verify protected endpoint permits access
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("JWT 3: RECRUITER produces ROLE_RECRUITER and is forbidden from admin endpoints")
    void test03_jwtRecruiterProducesRoleRecruiterAndIsForbiddenFromAdminEndpoint() throws Exception {
        Organization org = createOrg("jwt3");
        User user = createUser("jwt3");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Verify direct filter sets ROLE_RECRUITER authority
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_RECRUITER");
        });

        // RECRUITER can read candidates
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // RECRUITER is forbidden from admin endpoint
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("JWT 4: VIEWER produces ROLE_VIEWER and is forbidden from mutating endpoints")
    void test04_jwtViewerProducesRoleViewerAndIsForbiddenFromMutatingEndpoints() throws Exception {
        Organization org = createOrg("jwt4");
        User user = createUser("jwt4");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("VIEWER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "VIEWER");

        // Verify direct filter sets ROLE_VIEWER authority
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_VIEWER");
        });

        // VIEWER can read candidates
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // VIEWER is forbidden from job creation
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Viewer Job"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("JWT 5: NULL role is defensively rejected and clears context without default role")
    void test05_jwtNullDatabaseRoleIsDefensivelyRejected() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "nullrole@test.com", orgId, "RECRUITER");

        OrganizationUserRepository mockOrgUserRepo = mock(OrganizationUserRepository.class);
        when(mockOrgUserRepo.findAuthMembership(orgId, userId)).thenReturn(Optional.of(
                new UserMembershipAuth(orgId, userId, "nullrole@test.com", null, "ACTIVE", "ACTIVE")
        ));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, mockOrgUserRepo, orgRepo, environment, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    @Test
    @DisplayName("JWT 6: Empty role is rejected and fails closed")
    void test06_jwtEmptyDatabaseRoleIsRejected() throws Exception {
        Organization org = createOrg("jwt6");
        User user = createUser("jwt6");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("JWT 7: Whitespace-only role is rejected and fails closed")
    void test07_jwtWhitespaceDatabaseRoleIsRejected() throws Exception {
        Organization org = createOrg("jwt7");
        User user = createUser("jwt7");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("   ")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("JWT 8: Unknown role GUEST is rejected and fails closed")
    void test08_jwtUnknownGuestRoleIsRejected() throws Exception {
        Organization org = createOrg("jwt8");
        User user = createUser("jwt8");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("GUEST")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "GUEST");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("JWT 9: Database role ROLE_ADMIN is normalized correctly without double-prefixing")
    void test09_jwtRoleAdminPrefixIsNormalizedWithoutDoublePrefixing() throws Exception {
        Organization org = createOrg("jwt9");
        User user = createUser("jwt9");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ROLE_ADMIN")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // Verify direct filter sets exactly ROLE_ADMIN authority (not ROLE_ROLE_ADMIN)
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        });

        // Verify protected endpoint permits access
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("JWT 10: Repeated prefix ROLE_ROLE_ADMIN is rejected and fails closed")
    void test10_jwtRoleRoleAdminIsRejected() throws Exception {
        Organization org = createOrg("jwt10");
        User user = createUser("jwt10");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ROLE_ROLE_ADMIN")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("JWT 11: Stale JWT role claim cannot override database role")
    void test11_staleJwtRoleCannotOverrideDatabaseRole() throws Exception {
        Organization org = createOrg("jwt11");
        User user = createUser("jwt11");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        // JWT explicitly claims "ADMIN" role
        String tokenClaimingAdmin = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // Attempt admin endpoint: database is authoritative (RECRUITER), so 403 Forbidden!
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenClaimingAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("JWT 12: A demoted ADMIN immediately loses administrative access with the same token")
    void test12_demotedAdminImmediatelyLosesAdministrativeAccess() throws Exception {
        Organization org = createOrg("jwt12");
        User user = createUser("jwt12");
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // Request 1: As ADMIN, access to admin endpoint succeeds
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // Immediate demotion to RECRUITER in database
        membership.setRole("RECRUITER");
        orgUserRepo.save(membership);

        // Request 2: Reusing exact same JWT is immediately denied with 403 Forbidden
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    // =========================================================================
    // EXTENSION TESTS (13 - 18)
    // =========================================================================

    @Test
    @DisplayName("Extension 13: Extension ADMIN receives ROLE_ADMIN and accesses admin endpoint")
    void test13_extensionAdminReceivesRoleAdminAndAccessesAdminEndpoint() throws Exception {
        Organization org = createOrg("ext13");
        User user = createUser("ext13");
        String extToken = "hf_ext_live_admin_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        // Direct filter verification: authority is ROLE_ADMIN
        ExtensionTokenAuthFilter filter = new ExtensionTokenAuthFilter(
                orgUserRepo, orgRepo, userRepo, environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + extToken);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        });

        // Full Spring Security chain verification: accesses admin endpoint
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Extension 14: Extension ROLE_ADMIN is normalized without double-prefixing")
    void test14_extensionRoleAdminIsNormalizedWithoutDoublePrefixing() throws Exception {
        Organization org = createOrg("ext14");
        User user = createUser("ext14");
        String extToken = "hf_ext_live_prefixed_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ROLE_ADMIN") // Prefixed in database
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        // Direct filter verification: authority is strictly ROLE_ADMIN, not ROLE_ROLE_ADMIN
        ExtensionTokenAuthFilter filter = new ExtensionTokenAuthFilter(
                orgUserRepo, orgRepo, userRepo, environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + extToken);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        });

        // Full MockMvc verification: admin endpoint access succeeds
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Extension 15: Extension OWNER receives ROLE_OWNER and accesses admin endpoint")
    void test15_extensionOwnerReceivesRoleOwnerAndAccessesAdminEndpoint() throws Exception {
        Organization org = createOrg("ext15");
        User user = createUser("ext15");
        String extToken = "hf_ext_live_owner_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("OWNER")
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        ExtensionTokenAuthFilter filter = new ExtensionTokenAuthFilter(
                orgUserRepo, orgRepo, userRepo, environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + extToken);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_OWNER");
        });

        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Extension 16: Extension RECRUITER receives ROLE_RECRUITER and is forbidden from admin endpoints")
    void test16_extensionRecruiterReceivesRoleRecruiterAndIsForbiddenFromAdminEndpoint() throws Exception {
        Organization org = createOrg("ext16");
        User user = createUser("ext16");
        String extToken = "hf_ext_live_recruiter_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        ExtensionTokenAuthFilter filter = new ExtensionTokenAuthFilter(
                orgUserRepo, orgRepo, userRepo, environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + extToken);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_RECRUITER");
        });

        // Allowed on candidates endpoint
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isOk());

        // Forbidden on admin endpoint
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Extension 16b: Extension VIEWER receives ROLE_VIEWER and is forbidden from mutating endpoints")
    void test16b_extensionViewerReceivesRoleViewerAndIsForbiddenFromMutatingEndpoints() throws Exception {
        Organization org = createOrg("ext16b");
        User user = createUser("ext16b");
        String extToken = "hf_ext_live_viewer_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("VIEWER")
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        // Direct filter verification: authority is ROLE_VIEWER
        ExtensionTokenAuthFilter filter = new ExtensionTokenAuthFilter(
                orgUserRepo, orgRepo, userRepo, environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + extToken);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_VIEWER");
        });

        // VIEWER can read candidates
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isOk());

        // VIEWER is forbidden from mutating endpoints (job creation)
        mockMvc.perform(post("/api/v1/jobs")
                        .header("Authorization", "Bearer " + extToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Viewer Job"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Extension 17: Extension with invalid roles (GUEST, ROLE_ROLE_ADMIN, blank, NULL) is rejected")
    void test17_extensionInvalidRolesAreRejected() throws Exception {
        Organization org = createOrg("ext17");

        // 17a: Unknown role GUEST
        User guestUser = createUser("ext17a");
        String guestToken = "hf_ext_guest_" + UUID.randomUUID().toString().replace("-", "");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(guestUser.getId())
                .role("GUEST")
                .status("ACTIVE")
                .extensionTokenHash(HashUtil.sha256Hex(guestToken.getBytes(StandardCharsets.UTF_8)))
                .build());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // 17b: Malformed repeated prefix ROLE_ROLE_ADMIN
        User doublePrefixUser = createUser("ext17b");
        String doubleToken = "hf_ext_double_" + UUID.randomUUID().toString().replace("-", "");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(doublePrefixUser.getId())
                .role("ROLE_ROLE_ADMIN")
                .status("ACTIVE")
                .extensionTokenHash(HashUtil.sha256Hex(doubleToken.getBytes(StandardCharsets.UTF_8)))
                .build());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + doubleToken))
                .andExpect(status().isUnauthorized());

        // 17c: Empty role
        User emptyUser = createUser("ext17c");
        String emptyToken = "hf_ext_empty_" + UUID.randomUUID().toString().replace("-", "");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(emptyUser.getId())
                .role("")
                .status("ACTIVE")
                .extensionTokenHash(HashUtil.sha256Hex(emptyToken.getBytes(StandardCharsets.UTF_8)))
                .build());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + emptyToken))
                .andExpect(status().isUnauthorized());

        // 17d: Whitespace role
        User wsUser = createUser("ext17d");
        String wsToken = "hf_ext_ws_" + UUID.randomUUID().toString().replace("-", "");
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(wsUser.getId())
                .role("   ")
                .status("ACTIVE")
                .extensionTokenHash(HashUtil.sha256Hex(wsToken.getBytes(StandardCharsets.UTF_8)))
                .build());

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + wsToken))
                .andExpect(status().isUnauthorized());

        // 17e: Defensive NULL role test (using mock to simulate NULL role without weakening database schema)
        String nullToken = "hf_ext_null_" + UUID.randomUUID().toString().replace("-", "");
        String nullHash = HashUtil.sha256Hex(nullToken.getBytes(StandardCharsets.UTF_8));
        UUID nullOrgId = UUID.randomUUID();
        UUID nullUserId = UUID.randomUUID();

        OrganizationUser nullRoleOrgUser = OrganizationUser.builder()
                .organizationId(nullOrgId)
                .userId(nullUserId)
                .role(null)
                .status("ACTIVE")
                .extensionTokenHash(nullHash)
                .build();

        OrganizationUserRepository mockRepo = mock(OrganizationUserRepository.class);
        when(mockRepo.findByExtensionTokenHash(nullHash)).thenReturn(Optional.of(nullRoleOrgUser));

        Organization activeOrg = Organization.builder().id(nullOrgId).status("ACTIVE").name("Org").slug("slug").build();
        OrganizationRepository mockOrgRepo = mock(OrganizationRepository.class);
        when(mockOrgRepo.findById(nullOrgId)).thenReturn(Optional.of(activeOrg));

        User activeUser = User.builder().id(nullUserId).email("nulluser@test.com").build();
        UserRepository mockUserRepo = mock(UserRepository.class);
        when(mockUserRepo.findById(nullUserId)).thenReturn(Optional.of(activeUser));

        ExtensionTokenAuthFilter nullRoleFilter = new ExtensionTokenAuthFilter(
                mockRepo, mockOrgRepo, mockUserRepo, environment);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + nullToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        nullRoleFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("Extension 18: Real Spring Security filter chain preserves extension context through JwtAuthenticationFilter")
    void test18_extensionAuthenticationThroughRealFilterChainPreservesContextAndSucceeds() throws Exception {
        Organization org = createOrg("ext18");
        User user = createUser("ext18");
        String extToken = "hf_ext_live_chain_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = HashUtil.sha256Hex(extToken.getBytes(StandardCharsets.UTF_8));

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .extensionTokenHash(tokenHash)
                .build());

        // Execute request through the real configured filter chain via MockMvc
        // JwtAuthenticationFilter follows ExtensionTokenAuthFilter and must NOT clear SecurityContext!
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + extToken))
                .andExpect(status().isOk());

        // Contexts must be completely cleaned up after request finishes
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
