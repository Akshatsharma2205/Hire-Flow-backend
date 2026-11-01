package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.security.JwtAuthenticationFilter;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.security.ProductionDemoCredentialSanitizer;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtRevocationAndTenantStatusEnforcementTest extends IntegrationTestSupport {

    private static final String SEEDED_PASSWORD_HASH = "$2a$10$WlQkXR4DeKknxN2m.8jjguikek.kq0lHe.UTl6PM4G93CLMuwEQIe";

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private HireFlowProperties props;

    @Autowired
    private ConfigurableEnvironment environment;

    @BeforeEach
    void setupInitialProfile() {
        environment.setActiveProfiles("test");
    }

    @AfterEach
    void resetState() {
        environment.setActiveProfiles("test");
        TenantContext.clear();
        SecurityContextHolder.clearContext();

        userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).ifPresent(user -> {
            user.setPasswordHash(SEEDED_PASSWORD_HASH);
            userRepo.save(user);
        });
        orgUserRepo.findByOrganizationIdAndUserId(
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                ProductionDemoCredentialSanitizer.DEMO_USER_ID
        ).ifPresent(ou -> {
            ou.setStatus("ACTIVE");
            ou.setExtensionTokenHash(ProductionDemoCredentialSanitizer.DEMO_TOKEN_HASH);
            orgUserRepo.save(ou);
        });
        orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).ifPresent(org -> {
            org.setStatus("ACTIVE");
            orgRepo.save(org);
        });
    }

    // ── Test 1 — Previously issued demo JWT ──
    @Test
    void test1_previouslyIssuedDemoJwtIsRejectedUnderProductionSecurity() throws Exception {
        // 1. Generate a valid demo JWT before sanitization
        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        // 2. Execute production security setup (activate prod rules and sanitize)
        environment.setActiveProfiles("test", "prod");
        ProductionDemoCredentialSanitizer sanitizer =
                new ProductionDemoCredentialSanitizer(userRepo, orgUserRepo, orgRepo, transactionManager);
        sanitizer.sanitize();

        // 3. Attempt access using the original JWT
        // 4. Assert that access is rejected through the real HTTP security pipeline
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 2 — Demo JWT before sanitizer execution ──
    @Test
    void test2_demoJwtRejectedBeforeSanitizerExecutionWhileDbIsActive() throws Exception {
        // 1. Activate prod security rules in the real HTTP security pipeline
        environment.setActiveProfiles("test", "prod");

        // 2. Keep demo organization ACTIVE in test database
        Organization demoOrg = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();
        assertThat(demoOrg.getStatus()).isEqualTo("ACTIVE");

        // 3. Generate a valid demo JWT
        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        // 4. Attempt access before calling the sanitizer
        // 5. Assert rejection: proves filter does NOT depend solely on database sanitization
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 3 — Demo JWT after sanitizer execution ──
    @Test
    void test3_demoJwtRejectedAfterSanitizerExecution() throws Exception {
        environment.setActiveProfiles("test", "prod");

        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        // 1. Execute sanitizer
        ProductionDemoCredentialSanitizer sanitizer =
                new ProductionDemoCredentialSanitizer(userRepo, orgUserRepo, orgRepo, transactionManager);
        sanitizer.sanitize();

        // 2. Reuse the original JWT
        // 3. Assert rejection
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 4 — Suspended customer organization ──
    @Test
    void test4_suspendedCustomerOrganizationJwtIsRejected() throws Exception {
        // 1. Create a legitimate non-demo organization
        Organization customerOrg = orgRepo.save(Organization.builder()
                .name("Acme Logistics Corp")
                .slug("acme-logistics-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User customerUser = userRepo.save(User.builder()
                .email("recruiter@acmelogistics.com")
                .displayName("Acme Recruiter")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(customerOrg.getId())
                .userId(customerUser.getId())
                .role("RECRUITER")
                .status("ACTIVE")
                .build());

        // 2. Issue a valid JWT
        String customerToken = jwtService.generateToken(
                customerUser.getId(),
                customerUser.getEmail(),
                customerOrg.getId(),
                "RECRUITER"
        );

        // Verify active token works first
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        // 3. Suspend the organization
        customerOrg.setStatus("SUSPENDED");
        orgRepo.save(customerOrg);

        // 4. Reuse its original JWT
        // 5. Assert rejection
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    // ── Test 5 — Active customer preservation ──
    @Test
    void test5_activeCustomerJwtPreservedAndSucceeds() throws Exception {
        // 1. Create an ACTIVE non-demo organization
        Organization customerOrg = orgRepo.save(Organization.builder()
                .name("Apex Innovations")
                .slug("apex-innovations-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User customerUser = userRepo.save(User.builder()
                .email("talent@apexinnovations.io")
                .displayName("Apex Talent Lead")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(customerOrg.getId())
                .userId(customerUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        // 2. Issue a valid JWT
        String customerToken = jwtService.generateToken(
                customerUser.getId(),
                customerUser.getEmail(),
                customerOrg.getId(),
                "OWNER"
        );

        // 3. Access a protected endpoint
        // 4. Assert successful access
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ── Test 6 — Local development compatibility ──
    @Test
    void test6_localAndTestProfilePreservesDemoAuthentication() throws Exception {
        // Under test profile (non-prod), demo authentication succeeds
        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ── Test 7 — Invalid and expired tokens ──
    @Test
    void test7_invalidMalformedAndExpiredTokensAreRejected() throws Exception {
        // A. Malformed token string
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer completely-invalid-garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // B. Token signed with wrong secret key
        SecretKey wrongKey = Keys.hmacShaKeyFor("wrong-secret-key-that-is-at-least-256-bits-long-12345".getBytes(StandardCharsets.UTF_8));
        String forgedToken = Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(UUID.randomUUID().toString())
                .claims(Map.of(
                        "email", "hacker@evil.com",
                        "orgId", UUID.randomUUID().toString(),
                        "role", "OWNER"
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(wrongKey)
                .compact();

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());

        // C. Expired token
        SecretKey correctKey = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(UUID.randomUUID().toString())
                .claims(Map.of(
                        "email", "expired@test.com",
                        "orgId", ProductionDemoCredentialSanitizer.DEMO_ORG_ID.toString(),
                        "role", "OWNER"
                ))
                .issuedAt(new Date(System.currentTimeMillis() - 7200000))
                .expiration(new Date(System.currentTimeMillis() - 3600000))
                .signWith(correctKey)
                .compact();

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        // D. Token with non-existent / deleted organization
        UUID nonExistentOrgId = UUID.randomUUID();
        String deletedOrgToken = jwtService.generateToken(
                UUID.randomUUID(), "ghost@deletedorg.com", nonExistentOrgId, "OWNER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + deletedOrgToken))
                .andExpect(status().isUnauthorized());

        // E. Missing required claims (missing orgId)
        String missingOrgToken = Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(UUID.randomUUID().toString())
                .claims(Map.of("email", "no-org@test.com"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(correctKey)
                .compact();

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + missingOrgToken))
                .andExpect(status().isUnauthorized());

        // F. Invalid claim types (e.g. numeric orgId instead of UUID string)
        String numericOrgToken = Jwts.builder()
                .issuer(props.getJwt().getIssuer())
                .subject(UUID.randomUUID().toString())
                .claims(Map.of(
                        "email", "bad-type@test.com",
                        "orgId", 12345,
                        "role", "OWNER"
                ))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(correctKey)
                .compact();

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + numericOrgToken))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 8 — Context cleanup ──
    @Test
    void test8_contextCleanupPreventsStateLeakBetweenRequests() throws Exception {
        // Step A: Send an unauthorized/rejected request
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        // Assert thread-local contexts are clean
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Step B: Direct filter verification of exceptional/rejected paths
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        JwtAuthenticationFilter prodFilter = new JwtAuthenticationFilter(jwtService, orgUserRepo, orgRepo, prodEnv);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer bad.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        prodFilter.doFilter(request, response, chain);

        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Step C: Verify subsequent requests on the pipeline are unaffected
        // Sub-test 1: Unauthenticated subsequent request remains 401 and clean
        mockMvc.perform(get("/api/v1/candidates"))
                .andExpect(status().isUnauthorized());
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // Sub-test 2: Valid customer subsequent request succeeds normally without interference
        Organization activeOrg = orgRepo.save(Organization.builder()
                .name("Post Reject Org")
                .slug("post-reject-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User activeUser = userRepo.save(User.builder()
                .email("clean@postreject.com")
                .displayName("Clean Recruiter")
                .passwordHash("dummy-hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(activeOrg.getId())
                .userId(activeUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());
        String cleanToken = jwtService.generateToken(
                activeUser.getId(), activeUser.getEmail(), activeOrg.getId(), "OWNER");

        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + cleanToken))
                .andExpect(status().isOk());
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── Test 9 — Startup before sanitization ──
    @Test
    void test9_startupBeforeSanitizationBlocksDemoUnderProductionProfile() throws Exception {
        // Assert demo user and demo organization are present and ACTIVE in the database
        Organization demoOrg = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();
        assertThat(demoOrg.getStatus()).isEqualTo("ACTIVE");

        User demoUser = userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).orElseThrow();
        assertThat(demoUser.getPasswordHash()).isEqualTo(SEEDED_PASSWORD_HASH);

        // Activate prod security rules
        environment.setActiveProfiles("test", "prod");

        // Generate demo JWT
        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        // Verify that in production mode, access is rejected despite active database records
        mockMvc.perform(get("/api/v1/candidates")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized());

        // Direct filter verification confirms no authentication or tenant context is set
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        JwtAuthenticationFilter prodFilter = new JwtAuthenticationFilter(jwtService, orgUserRepo, orgRepo, prodEnv);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + demoToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        prodFilter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    // ── Section 7 — Transactional Sanitizer Atomicity and Rollback Test ──
    @Test
    void testSection7_transactionalSanitizerRollsBackOnMidwayFailure() {
        // Verify initial state
        User initialUser = userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).orElseThrow();
        assertThat(initialUser.getPasswordHash()).isEqualTo(SEEDED_PASSWORD_HASH);

        Organization initialOrg = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();
        assertThat(initialOrg.getStatus()).isEqualTo("ACTIVE");

        // Create a subclass that simulates an unexpected failure midway through sanitization
        ProductionDemoCredentialSanitizer failingSanitizer = new ProductionDemoCredentialSanitizer(
                userRepo, orgUserRepo, orgRepo, transactionManager) {
            @Override
            protected void executeSanitization() {
                // Call super to execute all steps
                super.executeSanitization();
                // Then throw an exception midway before transaction commit
                throw new IllegalStateException("Simulated crash midway through transaction execution");
            }
        };

        // Assert the exception is thrown via direct sanitize() invocation
        assertThatThrownBy(failingSanitizer::sanitize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated crash");

        // Reload user and organization from database
        User rolledBackUser = userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).orElseThrow();
        Organization rolledBackOrg = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();

        // Assert all changes rolled back completely
        assertThat(rolledBackUser.getPasswordHash()).isEqualTo(SEEDED_PASSWORD_HASH);
        assertThat(rolledBackOrg.getStatus()).isEqualTo("ACTIVE");

        // Also verify that self-invocation through ApplicationRunner.run(...)
        // executes within the same transactional boundary and rolls back atomically
        assertThatThrownBy(() -> failingSanitizer.run(new org.springframework.boot.DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated crash");

        User rolledBackUser2 = userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).orElseThrow();
        Organization rolledBackOrg2 = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();

        assertThat(rolledBackUser2.getPasswordHash()).isEqualTo(SEEDED_PASSWORD_HASH);
        assertThat(rolledBackOrg2.getStatus()).isEqualTo("ACTIVE");
    }
}
