package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.security.JwtAuthenticationFilter;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.security.ProductionDemoCredentialSanitizer;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Permanent regression tests for Issue 01C:
 * Centralized User, Membership & Role Authorization Enforcement.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserMembershipRoleAuthorizationTest extends IntegrationTestSupport {

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

    // ── Test 1 — Disabled membership ──
    @Test
    @DisplayName("Test 1 — Disabled membership: Reusing a JWT after membership is disabled is rejected")
    void test1_disabledMembershipRejectsJwt() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 1")
                .slug("test-org-1-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user1@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User One")
                .passwordHash("hash")
                .build());
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Verify active token works initially
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Disable the membership in database
        membership.setStatus("DISABLED");
        orgUserRepo.save(membership);

        // Reuse the exact same original JWT
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 2 — Deleted membership ──
    @Test
    @DisplayName("Test 2 — Deleted membership: Reusing a JWT after membership is deleted is rejected")
    void test2_deletedMembershipRejectsJwt() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 2")
                .slug("test-org-2-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user2@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Two")
                .passwordHash("hash")
                .build());
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Verify active token works initially
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Delete the membership record
        orgUserRepo.delete(membership);

        // Reuse original JWT
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 3 — Cross-organization membership violation ──
    @Test
    @DisplayName("Test 3 — Cross-organization membership violation: JWT for foreign org where user has no membership is rejected")
    void test3_crossOrganizationMembershipViolationRejectsJwt() throws Exception {
        Organization orgA = orgRepo.save(Organization.builder()
                .name("Org A")
                .slug("org-a-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        Organization orgB = orgRepo.save(Organization.builder()
                .name("Org B")
                .slug("org-b-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User userA = userRepo.save(User.builder()
                .email("userA@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User A")
                .passwordHash("hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(orgA.getId())
                .userId(userA.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        // User A has NO membership in Org B. Issue a signed JWT with subject User A and orgId Org B.
        String forgedToken = jwtService.generateToken(userA.getId(), userA.getEmail(), orgB.getId(), "OWNER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Test 4 — Immediate role demotion ──
    @Test
    @DisplayName("Test 4 — Immediate role demotion: Demoted ADMIN immediately loses administrative privileges")
    void test4_immediateRoleDemotionRevokesAdminPrivileges() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 4")
                .slug("test-org-4-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("admin4@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("Admin User")
                .passwordHash("hash")
                .build());
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .build());

        // Issue JWT containing ADMIN role
        String adminToken = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // As ADMIN, endpoint with @PreAuthorize("hasAnyRole('OWNER','ADMIN')") permits access (not 403; returns 404 for missing connection)
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // Demote user in database from ADMIN to RECRUITER
        membership.setRole("RECRUITER");
        orgUserRepo.save(membership);

        // Reuse the exact same original JWT containing "ADMIN"
        // Database role is now RECRUITER, so @PreAuthorize("hasAnyRole('OWNER','ADMIN')") denies access with 403 Forbidden!
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    // ── Test 5 — Current role preservation ──
    @Test
    @DisplayName("Test 5 — Current role preservation: Active ADMIN membership grants ADMIN authority despite stale RECRUITER JWT")
    void test5_currentRolePreservationAppliesDatabaseAdminRole() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 5")
                .slug("test-org-5-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("admin5@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("Promoted Admin")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ADMIN")
                .status("ACTIVE")
                .build());

        // Issue a JWT with an outdated "RECRUITER" role claim
        String staleRecruiterToken = jwtService.generateToken(
                user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Attempt endpoint protected by @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
        // Because database role is ADMIN, user's current ADMIN permissions must be applied (returns 404, NOT 403 Forbidden!)
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + staleRecruiterToken))
                .andExpect(status().isNotFound());
    }

    // ── Test 6 — Missing user ──
    @Test
    @DisplayName("Test 6 — Missing user: Deleted user is rejected even if original JWT was valid")
    void test6_missingUserRejectsJwt() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 6")
                .slug("test-org-6-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user6@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Six")
                .passwordHash("hash")
                .build());
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Verify active token works first
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Delete user and membership
        orgUserRepo.delete(membership);
        userRepo.delete(user);

        // Attempt access with the old JWT
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 7 — Active membership ──
    @Test
    @DisplayName("Test 7 — Active membership: Legitimate user with active membership and org retains access")
    void test7_activeMembershipAllowsAccess() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 7")
                .slug("test-org-7-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user7@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Seven")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── Test 8 — Suspended organization ──
    @Test
    @DisplayName("Test 8 — Suspended organization: Old JWT for suspended organization is rejected")
    void test8_suspendedOrganizationRejectsJwt() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 8")
                .slug("test-org-8-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user8@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Eight")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Verify active token works initially
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Suspend the organization
        org.setStatus("SUSPENDED");
        orgRepo.save(org);

        // Reuse original JWT
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 9 — Production demo JWT ──
    @Test
    @DisplayName("Test 9 — Production demo JWT: Demo JWT unconditionally rejected under prod profile")
    void test9_productionDemoJwtIsRejectedUnderProdProfile() throws Exception {
        environment.setActiveProfiles("test", "prod");

        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 10 — Context isolation ──
    @Test
    @DisplayName("Test 10 — Context isolation: Rejected requests leave no TenantContext or SecurityContext")
    void test10_authenticationContextIsolationPreventsLeak() throws Exception {
        // Send rejected request
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer completely-invalid-token"))
                .andExpect(status().isUnauthorized());

        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Subsequent unauthenticated request remains clean
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized());

        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Subsequent valid customer request succeeds and is isolated
        Organization org = orgRepo.save(Organization.builder()
                .name("Isolated Org")
                .slug("isolated-org-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("isolated@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("Isolated User")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());
        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Test 11 — Database failure ──
    @Test
    @DisplayName("Test 11 — Database failure: Database lookup failure returns HTTP 503 and establishes no context")
    void test11_databaseFailureReturns503AndCleansContexts() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 11")
                .slug("test-org-11-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user11@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Eleven")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Directly invoke JwtAuthenticationFilter configured with a throwing repository
        OrganizationUserRepository throwingRepo = mock(OrganizationUserRepository.class);
        when(throwingRepo.findAuthMembership(any(UUID.class), any(UUID.class)))
                .thenThrow(new DataAccessResourceFailureException("Simulated connection pool exhaustion"));

        JwtAuthenticationFilter failingFilter = new JwtAuthenticationFilter(
                jwtService, throwingRepo, orgRepo, environment, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        failingFilter.doFilter(request, response, chain);

        // Assert HTTP 503 Service Unavailable
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("503");
        assertThat(response.getContentAsString()).contains("Service Unavailable");
        assertThat(response.getContentAsString()).contains("Authentication database service temporarily unavailable");

        // Assert contexts are completely clean
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Test 12 — Database email authoritative usage ──
    @Test
    @DisplayName("Test 12 — Authoritative database email: Database email determines authenticated principal rather than stale JWT email claim")
    void test12_databaseEmailDeterminesAuthenticatedPrincipal() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 12")
                .slug("test-org-12-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("actual-db-email@kartgen.com")
                .displayName("Authoritative Email User")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        // Issue token with a stale email claim
        String tokenWithStaleEmail = jwtService.generateToken(
                user.getId(), "stale-outdated-email@olddomain.com", org.getId(), "RECRUITER");

        // Filter directly to verify authenticated principal
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + tokenWithStaleEmail);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // SecurityContext should reflect the database email, not the stale JWT claim
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("actual-db-email@kartgen.com");
    }

    // ── Test 13 — Dirty thread context clearance ──
    @Test
    @DisplayName("Test 13 — Dirty thread context clearance: Pre-existing thread authentication is cleared and overridden by Bearer token")
    void test13_staleThreadAuthenticationIsClearedAndOverriddenByBearerJwt() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 13")
                .slug("test-org-13-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user13@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Thirteen")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String recruiterToken = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "RECRUITER");

        // Simulate a dirty thread in Tomcat where a previous request left an OWNER authentication
        var dirtyAuthentication = new UsernamePasswordAuthenticationToken(
                "stale-admin@attacker.com",
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
        SecurityContextHolder.getContext().setAuthentication(dirtyAuthentication);
        TenantContext.setOrganizationId(UUID.randomUUID());

        // Invoke filter directly with the recruiter token
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, orgUserRepo, orgRepo, environment, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + recruiterToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // SecurityContext must have been cleared of the dirty OWNER context and re-authenticated as the RECRUITER
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(user.getEmail());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RECRUITER");
        assertThat(TenantContext.getOrganizationId()).isNull(); // Cleared in finally
    }

    // ── Test 14 — Multi-tenant sequential token switching ──
    @Test
    @DisplayName("Test 14 — Multi-tenant token switching: Switching tokens across organizations correctly switches tenant and role without leak")
    void test14_multiTenantSequentialTokenSwitchingOnSameThread() throws Exception {
        Organization orgA = orgRepo.save(Organization.builder()
                .name("Org Alpha")
                .slug("org-alpha-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        Organization orgB = orgRepo.save(Organization.builder()
                .name("Org Beta")
                .slug("org-beta-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User multiOrgUser = userRepo.save(User.builder()
                .email("multiorg@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("Multi-Org User")
                .passwordHash("hash")
                .build());

        // In Org Alpha, user is OWNER
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(orgA.getId())
                .userId(multiOrgUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        // In Org Beta, user is RECRUITER
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(orgB.getId())
                .userId(multiOrgUser.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        String tokenA = jwtService.generateToken(multiOrgUser.getId(), multiOrgUser.getEmail(), orgA.getId(), "OWNER");
        String tokenB = jwtService.generateToken(multiOrgUser.getId(), multiOrgUser.getEmail(), orgB.getId(), "RECRUITER");

        // 1. Request with Token A (OWNER) -> accessing admin endpoint succeeds (404, not 403)
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // 2. Immediate subsequent request with Token B (RECRUITER) -> admin endpoint is forbidden (403)
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));

        // 3. Subsequent request with Token A (OWNER) -> succeeds again (404, not 403)
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // ── Test 15 — Database role normalization ──
    @Test
    @DisplayName("Test 15 — Database role normalization: Roles with 'ROLE_' prefix in database are normalized without double prefixing")
    void test15_databaseRoleWithPrefixNormalizedCorrectly() throws Exception {
        Organization org = orgRepo.save(Organization.builder()
                .name("Test Org 15")
                .slug("test-org-15-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User user = userRepo.save(User.builder()
                .email("user15@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .displayName("User Fifteen")
                .passwordHash("hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(user.getId())
                .role("ROLE_ADMIN") // Prefixed in database
                .status("ACTIVE")
                .build());

        String token = jwtService.generateToken(user.getId(), user.getEmail(), org.getId(), "ADMIN");

        // Endpoint protected by @PreAuthorize("hasAnyRole('OWNER','ADMIN')") must accept it
        mockMvc.perform(delete("/api/v1/mail-connections/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
