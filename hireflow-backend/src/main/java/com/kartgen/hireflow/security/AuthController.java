package com.kartgen.hireflow.security;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.tenant.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final OrganizationUserRepository orgUserRepo;
    private final OrganizationRepository organizationRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    public record AuthRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record AuthResponse(String token, String email, String displayName, UUID organizationId,
                               String organizationName, String role) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest req) {
        String email = req.email().trim().toLowerCase();

        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd && ProductionDemoCredentialSanitizer.DEMO_EMAIL.equalsIgnoreCase(email)) {
            log.warn("Production security: Rejected login attempt for seeded demo user '{}'", email);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        var user = userRepo.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Same response for unknown user and wrong password.
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        var orgUser = orgUserRepo.findByUserId(user.getId()).stream()
                .filter(ou -> "ACTIVE".equals(ou.getStatus()))
                .findFirst()
                .orElse(null);
        if (orgUser == null) {
            return ResponseEntity.status(403).body(Map.of("error", "User is not assigned to an active organization"));
        }

        if (isProd && ProductionDemoCredentialSanitizer.DEMO_ORG_ID.equals(orgUser.getOrganizationId())) {
            log.warn("Production security: Rejected login linked to demo organization {}", orgUser.getOrganizationId());
            return ResponseEntity.status(403).body(Map.of("error", "Organization is not active"));
        }

        var org = organizationRepo.findById(orgUser.getOrganizationId())
                .filter(o -> "ACTIVE".equals(o.getStatus()))
                .orElse(null);
        if (org == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Organization is not active"));
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(),
                orgUser.getOrganizationId(), orgUser.getRole());

        return ResponseEntity.ok(new AuthResponse(
                token, user.getEmail(), user.getDisplayName(),
                orgUser.getOrganizationId(), org.getName(), orgUser.getRole()
        ));
    }

    @PostMapping("/extension-token")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
    public ResponseEntity<?> generateExtensionToken() {
        UUID userId = TenantContext.getUserId();
        UUID orgId = TenantContext.getOrganizationId();
        if (userId == null || orgId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }

        var orgUser = orgUserRepo.findByOrganizationIdAndUserId(orgId, userId).orElse(null);
        if (orgUser == null || !"ACTIVE".equals(orgUser.getStatus())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Active organization membership required"));
        }

        boolean organizationActive = organizationRepo.findById(orgId)
                .filter(org -> "ACTIVE".equals(org.getStatus()))
                .isPresent();
        if (!organizationActive) {
            return ResponseEntity.status(403).body(Map.of("error", "Active organization required"));
        }

        byte[] random = new byte[24];
        secureRandom.nextBytes(random);
        String token = "hf_ext_live_org_" + orgId.toString().substring(0, 8) + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash = HashUtil.sha256Hex(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        orgUser.setExtensionTokenHash(hash);
        orgUserRepo.save(orgUser);

        log.info("Generated new extension token for user={} org={}", userId, orgId);
        return ResponseEntity.ok(Map.of("extensionToken", token));
    }
}
