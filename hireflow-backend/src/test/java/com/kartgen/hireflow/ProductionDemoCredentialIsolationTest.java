package com.kartgen.hireflow;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.config.ProductionSafetyConfig;
import com.kartgen.hireflow.security.AuthController;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.security.ProductionDemoCredentialSanitizer;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductionDemoCredentialIsolationTest extends IntegrationTestSupport {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Flyway flyway;

    @AfterEach
    void resetDemoState() {
        userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).ifPresent(user -> {
            user.setPasswordHash("$2a$10$WlQkXR4DeKknxN2m.8jjguikek.kq0lHe.UTl6PM4G93CLMuwEQIe");
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

    @Test
    void flywayValidationSucceedsWithoutChecksumMismatch() {
        // Confirms that V1, V2, and V3 match expected migration history
        flyway.validate();
    }

    @Test
    void sanitizerNeutralizesSeededDemoCredentialsIdempotently() {
        ProductionDemoCredentialSanitizer sanitizer =
                new ProductionDemoCredentialSanitizer(userRepo, orgUserRepo, orgRepo);

        // Run sanitizer
        sanitizer.sanitize();

        // 1. Password hash is scrambled
        User user = userRepo.findByEmail(ProductionDemoCredentialSanitizer.DEMO_EMAIL).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("DISABLED_DEMO_HASH_");
        assertThat(passwordEncoder.matches("password123", user.getPasswordHash())).isFalse();

        // 2. Org membership is disabled and token hash is cleared
        OrganizationUser ou = orgUserRepo.findByOrganizationIdAndUserId(
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                ProductionDemoCredentialSanitizer.DEMO_USER_ID
        ).orElseThrow();
        assertThat(ou.getStatus()).isEqualTo("DISABLED");
        assertThat(ou.getExtensionTokenHash()).isNull();

        // 3. Demo organization is suspended
        Organization org = orgRepo.findById(ProductionDemoCredentialSanitizer.DEMO_ORG_ID).orElseThrow();
        assertThat(org.getStatus()).isEqualTo("SUSPENDED");

        // 4. Running a second time (idempotency) succeeds without error
        sanitizer.sanitize();
    }

    @Test
    void sanitizerPreservesNonDemoCustomerAccounts() {
        // Create a legitimate non-demo customer organization and user
        Organization realOrg = orgRepo.save(Organization.builder()
                .name("Real Customer Enterprises")
                .slug("real-cust-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        String realPasswordHash = passwordEncoder.encode("SecureRealPassword123!");
        User realUser = userRepo.save(User.builder()
                .email("admin@realcustomer.com")
                .displayName("Real Admin")
                .passwordHash(realPasswordHash)
                .build());

        String realToken = "hf_ext_real_customer_token_99999";
        String realTokenHash = HashUtil.sha256Hex(realToken.getBytes(StandardCharsets.UTF_8));
        OrganizationUser realOu = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(realOrg.getId())
                .userId(realUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .extensionTokenHash(realTokenHash)
                .build());

        // Run sanitizer
        ProductionDemoCredentialSanitizer sanitizer =
                new ProductionDemoCredentialSanitizer(userRepo, orgUserRepo, orgRepo);
        sanitizer.sanitize();

        // Assert non-demo customer records remain completely intact
        User reloadedUser = userRepo.findById(realUser.getId()).orElseThrow();
        assertThat(reloadedUser.getPasswordHash()).isEqualTo(realPasswordHash);
        assertThat(passwordEncoder.matches("SecureRealPassword123!", reloadedUser.getPasswordHash())).isTrue();

        OrganizationUser reloadedOu = orgUserRepo.findById(realOu.getId()).orElseThrow();
        assertThat(reloadedOu.getStatus()).isEqualTo("ACTIVE");
        assertThat(reloadedOu.getExtensionTokenHash()).isEqualTo(realTokenHash);

        Organization reloadedOrg = orgRepo.findById(realOrg.getId()).orElseThrow();
        assertThat(reloadedOrg.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void authControllerExplicitlyRejectsDemoUserInProductionProfile() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AuthController prodAuthController = new AuthController(
                userRepo, orgUserRepo, orgRepo, passwordEncoder, jwtService, prodEnv);

        var response = prodAuthController.login(new AuthController.AuthRequest(
                ProductionDemoCredentialSanitizer.DEMO_EMAIL, "password123"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void authControllerAllowsNonDemoUserInProductionProfile() {
        // Create active customer account
        Organization realOrg = orgRepo.save(Organization.builder()
                .name("Acme Corp")
                .slug("acme-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User realUser = userRepo.save(User.builder()
                .email("ceo@acmecorp.com")
                .displayName("Acme CEO")
                .passwordHash(passwordEncoder.encode("AcmePassword2026!"))
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(realOrg.getId())
                .userId(realUser.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AuthController prodAuthController = new AuthController(
                userRepo, orgUserRepo, orgRepo, passwordEncoder, jwtService, prodEnv);

        var response = prodAuthController.login(new AuthController.AuthRequest(
                "ceo@acmecorp.com", "AcmePassword2026!"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AuthController.AuthResponse body = (AuthController.AuthResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.email()).isEqualTo("ceo@acmecorp.com");
        assertThat(body.organizationId()).isEqualTo(realOrg.getId());
    }

    @Test
    void productionSafetyConfigBlocksInsecureDefaults() {
        HireFlowProperties insecureProps = new HireFlowProperties();
        // Missing strong JWT secret
        insecureProps.getJwt().setSecret("short");

        ProductionSafetyConfig safetyConfig = new ProductionSafetyConfig(insecureProps);
        assertThatThrownBy(safetyConfig::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void extensionTokenFilterExplicitlyRejectsDemoTokenInProductionProfile() throws Exception {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        com.kartgen.hireflow.security.ExtensionTokenAuthFilter prodFilter =
                new com.kartgen.hireflow.security.ExtensionTokenAuthFilter(
                        orgUserRepo, orgRepo, userRepo, prodEnv);

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + ProductionDemoCredentialSanitizer.DEMO_TOKEN_STRING);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain filterChain =
                new org.springframework.mock.web.MockFilterChain();

        prodFilter.doFilter(request, response, filterChain);

        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void jwtAuthenticationFilterExplicitlyRejectsDemoJwtInProductionProfile() throws Exception {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        com.kartgen.hireflow.security.JwtAuthenticationFilter prodFilter =
                new com.kartgen.hireflow.security.JwtAuthenticationFilter(
                        jwtService, orgRepo, prodEnv);

        String demoToken = jwtService.generateToken(
                ProductionDemoCredentialSanitizer.DEMO_USER_ID,
                ProductionDemoCredentialSanitizer.DEMO_EMAIL,
                ProductionDemoCredentialSanitizer.DEMO_ORG_ID,
                "OWNER"
        );

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/candidates");
        request.addHeader("Authorization", "Bearer " + demoToken);
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain filterChain =
                new org.springframework.mock.web.MockFilterChain();

        prodFilter.doFilter(request, response, filterChain);

        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(com.kartgen.hireflow.common.TenantContext.getOrganizationId()).isNull();
    }
}
