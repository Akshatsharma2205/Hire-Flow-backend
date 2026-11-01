package com.kartgen.hireflow.security;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUser;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExtensionTokenAuthFilter extends OncePerRequestFilter {

    private final OrganizationUserRepository orgUserRepo;
    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final Environment environment;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer hf_ext_")) {
                TenantContext.clear();
                SecurityContextHolder.clearContext();
                String token = auth.substring(7).trim();
                String hash = HashUtil.sha256Hex(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                boolean isProd = environment != null && environment.getActiveProfiles() != null
                        && Arrays.asList(environment.getActiveProfiles()).contains("prod");
                if (isProd && (ProductionDemoCredentialSanitizer.DEMO_TOKEN_HASH.equals(hash)
                        || ProductionDemoCredentialSanitizer.DEMO_TOKEN_STRING.equals(token))) {
                    log.warn("Production security: Explicitly rejected seeded demo extension token");
                } else {
                    OrganizationUser resolvedOrgUser = orgUserRepo.findByExtensionTokenHash(hash)
                            .orElse(null);
                    if (resolvedOrgUser != null && "ACTIVE".equals(resolvedOrgUser.getStatus())) {
                        if (isProd && ProductionDemoCredentialSanitizer.DEMO_ORG_ID.equals(resolvedOrgUser.getOrganizationId())) {
                            log.warn("Production security: Rejected extension token linked to demo organization");
                        } else {
                            Organization org = orgRepo.findById(resolvedOrgUser.getOrganizationId()).orElse(null);
                            if (org != null && "ACTIVE".equals(org.getStatus())) {
                                Optional<SimpleGrantedAuthority> authorityOpt =
                                        RoleNormalizer.normalizeToAuthority(resolvedOrgUser.getRole());
                                if (authorityOpt.isEmpty()) {
                                    log.warn("Rejected extension token: invalid or unsupported role '{}' for user={} org={}",
                                            resolvedOrgUser.getRole(), resolvedOrgUser.getUserId(), resolvedOrgUser.getOrganizationId());
                                } else {
                                    userRepo.findById(resolvedOrgUser.getUserId()).ifPresentOrElse(user -> {
                                        TenantContext.setOrganizationId(resolvedOrgUser.getOrganizationId());
                                        TenantContext.setUserId(resolvedOrgUser.getUserId());
                                        var authToken = new UsernamePasswordAuthenticationToken(
                                                user.getEmail(),
                                                null,
                                                List.of(authorityOpt.get())
                                        );
                                        SecurityContextHolder.getContext().setAuthentication(authToken);
                                        log.debug("Extension token authenticated for org={}", resolvedOrgUser.getOrganizationId());
                                    }, () -> log.warn("Rejected extension token: user {} not found", resolvedOrgUser.getUserId()));
                                }
                            } else {
                                log.debug("Rejected extension token: organization is inactive or not found");
                            }
                        }
                    } else {
                        log.debug("Rejected extension token");
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
