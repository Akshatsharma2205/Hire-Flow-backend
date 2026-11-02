package com.kartgen.hireflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.ApiError;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.observability.CorrelationIdFilter;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.UserMembershipAuth;
import io.jsonwebtoken.Claims;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final OrganizationUserRepository orgUserRepo;
    private final OrganizationRepository orgRepo;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    @Autowired
    public JwtAuthenticationFilter(
            JwtService jwtService,
            OrganizationUserRepository orgUserRepo,
            OrganizationRepository orgRepo,
            Environment environment,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.orgUserRepo = orgUserRepo;
        this.orgRepo = orgRepo;
        this.environment = environment;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public JwtAuthenticationFilter(JwtService jwtService, OrganizationRepository orgRepo, Environment environment) {
        this(jwtService, null, orgRepo, environment, new ObjectMapper());
    }

    public JwtAuthenticationFilter(JwtService jwtService, OrganizationUserRepository orgUserRepo, OrganizationRepository orgRepo, Environment environment) {
        this(jwtService, orgUserRepo, orgRepo, environment, new ObjectMapper());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String auth = request.getHeader("Authorization");
            // Step 2: Extract Bearer token (excluding extension tokens handled by ExtensionTokenAuthFilter)
            if (auth != null && auth.startsWith("Bearer ") && !auth.startsWith("Bearer hf_ext_")) {
                // Step 1: Clear any previous request authentication context immediately
                clearContexts();
                String token = auth.substring(7).trim();
                if (!token.isEmpty()) {
                    boolean continueChain = processToken(token, request, response);
                    if (!continueChain) {
                        return; // Stop filter chain on infrastructure failure (HTTP 503 written)
                    }
                }
            }
            // Step 14: Continue the filter chain
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(CorrelationIdFilter.MDC_ORG_ID_KEY);
        }
    }

    private boolean processToken(String token, HttpServletRequest request, HttpServletResponse response) {
        // Step 1 & 2: Validate signature and expiration
        Claims claims;
        try {
            claims = jwtService.validateAndExtract(token);
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            clearContexts();
            return true;
        }

        // Step 3 & 4: Extract and validate required claim formats safely
        String subjectStr;
        String orgIdStr;
        String emailClaim;
        String roleClaim;
        UUID userId;
        UUID orgId;
        try {
            subjectStr = claims.getSubject();
            Object rawOrgId = claims.get("orgId");
            orgIdStr = rawOrgId instanceof String s ? s : (rawOrgId != null ? rawOrgId.toString() : null);
            Object rawEmail = claims.get("email");
            emailClaim = rawEmail instanceof String s ? s : (rawEmail != null ? rawEmail.toString() : null);
            Object rawRole = claims.get("role");
            roleClaim = rawRole instanceof String s ? s : (rawRole != null ? rawRole.toString() : null);

            if (subjectStr == null || subjectStr.isBlank()
                    || orgIdStr == null || orgIdStr.isBlank()) {
                log.warn("JWT rejected: missing required subject or orgId claims");
                clearContexts();
                return true;
            }

            userId = UUID.fromString(subjectStr.trim());
            orgId = UUID.fromString(orgIdStr.trim());
        } catch (Exception e) {
            log.warn("JWT rejected: invalid claims or formatting: {}", e.getMessage());
            clearContexts();
            return true;
        }

        // Step 5: Apply unconditional production demo-tenant restrictions
        boolean isProd = environment != null && (environment.acceptsProfiles(Profiles.of("prod"))
                || Arrays.asList(environment.getActiveProfiles()).contains("prod"));
        if (isProd) {
            if (ProductionDemoCredentialSanitizer.DEMO_ORG_ID.equals(orgId)
                    || ProductionDemoCredentialSanitizer.DEMO_USER_ID.equals(userId)
                    || (emailClaim != null && ProductionDemoCredentialSanitizer.DEMO_EMAIL.equalsIgnoreCase(emailClaim.trim()))) {
                log.warn("Production security: Explicitly rejected seeded demo JWT in prod (org={}, user={}, email={})",
                        orgId, userId, emailClaim);
                clearContexts();
                return true;
            }
        }

        // Step 6: Query authoritative membership information
        Optional<UserMembershipAuth> authOpt;
        try {
            if (orgUserRepo != null) {
                authOpt = orgUserRepo.findAuthMembership(orgId, userId);
            } else {
                log.error("Authoritative organization-user repository is null; failing closed");
                authOpt = Optional.empty();
            }
        } catch (DataAccessException | PersistenceException e) {
            log.error("Database failure during authorization lookup for user {} in org {}: {}",
                    userId, orgId, e.getMessage(), e);
            clearContexts();
            writeServiceUnavailable(request, response);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error during authorization lookup: {}", e.getMessage(), e);
            clearContexts();
            writeServiceUnavailable(request, response);
            return false;
        }

        // Step 7, 8, 9: Reject missing users, missing organizations, or missing memberships
        if (authOpt.isEmpty()) {
            log.warn("JWT rejected: membership, user, or organization does not exist (orgId={}, userId={})",
                    orgId, userId);
            clearContexts();
            return true;
        }

        UserMembershipAuth auth = authOpt.get();

        // Step 10: Reject disabled or suspended memberships
        if (!"ACTIVE".equals(auth.membershipStatus())) {
            log.warn("JWT rejected: organization membership is not ACTIVE (status={}, orgId={}, userId={})",
                    auth.membershipStatus(), orgId, userId);
            clearContexts();
            return true;
        }

        // Step 11: Reject non-active organizations
        if (!"ACTIVE".equals(auth.organizationStatus())) {
            log.warn("JWT rejected: organization {} does not exist or is not ACTIVE (status={})",
                    orgId, auth.organizationStatus());
            clearContexts();
            return true;
        }

        // Step 12: Validate authoritative database role using strict shared normalizer
        Optional<SimpleGrantedAuthority> authorityOpt = RoleNormalizer.normalizeToAuthority(auth.role());
        if (authorityOpt.isEmpty()) {
            log.warn("JWT rejected: invalid or unsupported database role '{}' (orgId={}, userId={})",
                    auth.role(), orgId, userId);
            clearContexts();
            return true;
        }

        // Step 13: Establish TenantContext using verified IDs
        TenantContext.setOrganizationId(auth.organizationId());
        TenantContext.setUserId(auth.userId());
        MDC.put(CorrelationIdFilter.MDC_ORG_ID_KEY, auth.organizationId().toString());

        // Step 14: Establish SecurityContext using authoritative database identity and validated role authority
        String authoritativeEmail = (auth.userEmail() != null && !auth.userEmail().isBlank())
                ? auth.userEmail().trim()
                : (emailClaim != null ? emailClaim.trim() : "");

        var authToken = new UsernamePasswordAuthenticationToken(
                authoritativeEmail,
                null,
                List.of(authorityOpt.get())
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);
        return true;
    }

    private void writeServiceUnavailable(HttpServletRequest request, HttpServletResponse response) {
        clearContexts();
        try {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getOutputStream(), ApiError.builder()
                    .status(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
                    .error("Service Unavailable")
                    .message("Authentication database service temporarily unavailable")
                    .path(request.getRequestURI())
                    .build());
        } catch (IOException e) {
            log.error("Failed to write 503 response: {}", e.getMessage(), e);
        }
    }

    private void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        MDC.remove(CorrelationIdFilter.MDC_ORG_ID_KEY);
    }
}
