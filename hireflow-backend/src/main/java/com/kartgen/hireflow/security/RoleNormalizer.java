package com.kartgen.hireflow.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Shared utility for strict role validation and normalization across
 * JWT and extension token authentication filters.
 *
 * Enforces canonical supported roles (OWNER, ADMIN, RECRUITER, VIEWER),
 * strips exactly one optional 'ROLE_' prefix, rejects null, blank, unknown,
 * or malformed/repeated prefixes (e.g. ROLE_ROLE_ADMIN), and constructs
 * Spring Security authorities deterministically without silent fallbacks.
 */
public final class RoleNormalizer {

    public static final String PREFIX = "ROLE_";

    public static final Set<String> SUPPORTED_ROLES = Collections.unmodifiableSet(Set.of(
            "OWNER",
            "ADMIN",
            "RECRUITER",
            "VIEWER"
    ));

    private RoleNormalizer() {
        // Utility class
    }

    /**
     * Normalizes a raw database role string into a canonical role name (e.g. "ADMIN", "OWNER", "RECRUITER", "VIEWER").
     *
     * Rules:
     * 1. Reject NULL roles -> Optional.empty()
     * 2. Trim surrounding whitespace
     * 3. Reject empty or whitespace-only roles -> Optional.empty()
     * 4. Normalize case using Locale.ROOT
     * 5. Support exactly one optional 'ROLE_' prefix (e.g. ROLE_ADMIN -> ADMIN)
     * 6. Reject repeated prefixes (e.g. ROLE_ROLE_ADMIN) -> Optional.empty()
     * 7. Accept only canonical supported roles: OWNER, ADMIN, RECRUITER, VIEWER
     * 8. Reject unknown roles (e.g. GUEST, ROLE_GUEST) -> Optional.empty()
     * 9. Never assign a default fallback role
     *
     * @param rawRole the input role string from the database
     * @return Optional containing the canonical role name, or Optional.empty() if invalid
     */
    public static Optional<String> normalizeRole(String rawRole) {
        if (rawRole == null) {
            return Optional.empty();
        }

        String trimmed = rawRole.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String upper = trimmed.toUpperCase(Locale.ROOT);

        String candidate;
        if (upper.startsWith(PREFIX)) {
            candidate = upper.substring(PREFIX.length());
            // Reject repeated or malformed prefix like ROLE_ROLE_ADMIN
            if (candidate.startsWith(PREFIX)) {
                return Optional.empty();
            }
        } else {
            candidate = upper;
        }

        if (SUPPORTED_ROLES.contains(candidate)) {
            return Optional.of(candidate);
        }

        return Optional.empty();
    }

    /**
     * Normalizes a raw database role string into a Spring Security SimpleGrantedAuthority
     * with the exact "ROLE_" prefix (e.g. "ROLE_ADMIN").
     *
     * The resulting authority is constructed exactly once.
     *
     * @param rawRole the input role string from the database
     * @return Optional containing the SimpleGrantedAuthority if valid, or Optional.empty() if invalid
     */
    public static Optional<SimpleGrantedAuthority> normalizeToAuthority(String rawRole) {
        return normalizeRole(rawRole).map(canonicalRole -> new SimpleGrantedAuthority(PREFIX + canonicalRole));
    }

    /**
     * Normalizes a raw database role string into a Spring Security authority String
     * with the exact "ROLE_" prefix (e.g. "ROLE_ADMIN").
     *
     * @param rawRole the input role string from the database
     * @return Optional containing the authority String if valid, or Optional.empty() if invalid
     */
    public static Optional<String> normalizeAuthorityString(String rawRole) {
        return normalizeRole(rawRole).map(canonicalRole -> PREFIX + canonicalRole);
    }

    /**
     * Validates whether a raw role string is supported and valid.
     */
    public static boolean isValidRole(String rawRole) {
        return normalizeRole(rawRole).isPresent();
    }
}
