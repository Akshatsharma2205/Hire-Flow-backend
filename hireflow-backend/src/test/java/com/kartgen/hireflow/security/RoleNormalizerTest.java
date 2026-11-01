package com.kartgen.hireflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RoleNormalizerTest {

    @Test
    @DisplayName("Canonical supported roles normalize to themselves")
    void canonicalRolesNormalizeSuccessfully() {
        assertThat(RoleNormalizer.normalizeRole("OWNER")).contains("OWNER");
        assertThat(RoleNormalizer.normalizeRole("ADMIN")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("RECRUITER")).contains("RECRUITER");
        assertThat(RoleNormalizer.normalizeRole("VIEWER")).contains("VIEWER");
    }

    @Test
    @DisplayName("Roles with leading/trailing whitespace are trimmed and normalized")
    void whitespaceIsTrimmed() {
        assertThat(RoleNormalizer.normalizeRole("  ADMIN  ")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("\tOWNER\n")).contains("OWNER");
        assertThat(RoleNormalizer.normalizeRole("   RECRUITER ")).contains("RECRUITER");
        assertThat(RoleNormalizer.normalizeRole(" VIEWER  ")).contains("VIEWER");
    }

    @Test
    @DisplayName("Mixed-case and lower-case roles normalize using Locale.ROOT")
    void caseNormalization() {
        assertThat(RoleNormalizer.normalizeRole("admin")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("Admin")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("owner")).contains("OWNER");
        assertThat(RoleNormalizer.normalizeRole("Recruiter")).contains("RECRUITER");
        assertThat(RoleNormalizer.normalizeRole("viewer")).contains("VIEWER");
    }

    @Test
    @DisplayName("Exactly one ROLE_ prefix is stripped to canonical role")
    void singleRolePrefixSupported() {
        assertThat(RoleNormalizer.normalizeRole("ROLE_ADMIN")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("role_admin")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("Role_Admin")).contains("ADMIN");
        assertThat(RoleNormalizer.normalizeRole("  ROLE_OWNER  ")).contains("OWNER");
        assertThat(RoleNormalizer.normalizeRole("ROLE_RECRUITER")).contains("RECRUITER");
        assertThat(RoleNormalizer.normalizeRole("ROLE_VIEWER")).contains("VIEWER");
    }

    @Test
    @DisplayName("Authorities are constructed with exactly one ROLE_ prefix")
    void authorityConstruction() {
        Optional<SimpleGrantedAuthority> adminAuth = RoleNormalizer.normalizeToAuthority("ADMIN");
        assertThat(adminAuth).isPresent().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        Optional<SimpleGrantedAuthority> roleAdminAuth = RoleNormalizer.normalizeToAuthority("ROLE_ADMIN");
        assertThat(roleAdminAuth).isPresent().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        Optional<SimpleGrantedAuthority> ownerAuth = RoleNormalizer.normalizeToAuthority("OWNER");
        assertThat(ownerAuth).isPresent().contains(new SimpleGrantedAuthority("ROLE_OWNER"));

        Optional<SimpleGrantedAuthority> recruiterAuth = RoleNormalizer.normalizeToAuthority("ROLE_RECRUITER");
        assertThat(recruiterAuth).isPresent().contains(new SimpleGrantedAuthority("ROLE_RECRUITER"));

        Optional<SimpleGrantedAuthority> viewerAuth = RoleNormalizer.normalizeToAuthority("VIEWER");
        assertThat(viewerAuth).isPresent().contains(new SimpleGrantedAuthority("ROLE_VIEWER"));
    }

    @Test
    @DisplayName("Authority string returns exact canonical ROLE_ prefix")
    void authorityStringConstruction() {
        assertThat(RoleNormalizer.normalizeAuthorityString("ADMIN")).contains("ROLE_ADMIN");
        assertThat(RoleNormalizer.normalizeAuthorityString("ROLE_ADMIN")).contains("ROLE_ADMIN");
        assertThat(RoleNormalizer.normalizeAuthorityString("RECRUITER")).contains("ROLE_RECRUITER");
        assertThat(RoleNormalizer.normalizeAuthorityString("VIEWER")).contains("ROLE_VIEWER");
    }

    @Test
    @DisplayName("NULL role is rejected")
    void nullRoleRejected() {
        assertThat(RoleNormalizer.normalizeRole(null)).isEmpty();
        assertThat(RoleNormalizer.normalizeToAuthority(null)).isEmpty();
        assertThat(RoleNormalizer.normalizeAuthorityString(null)).isEmpty();
        assertThat(RoleNormalizer.isValidRole(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t \r\n "})
    @DisplayName("Empty and whitespace-only roles are rejected")
    void emptyOrWhitespaceRolesRejected(String input) {
        assertThat(RoleNormalizer.normalizeRole(input)).isEmpty();
        assertThat(RoleNormalizer.normalizeToAuthority(input)).isEmpty();
        assertThat(RoleNormalizer.isValidRole(input)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GUEST",
            "ROLE_GUEST",
            "USER",
            "ROLE_USER",
            "SUPERADMIN",
            "MANAGER",
            "HIRING_MANAGER",
            "AUDITOR",
            "UNKNOWN",
            "anonymous"
    })
    @DisplayName("Unknown roles are strictly rejected without fallback")
    void unknownRolesRejected(String unknownRole) {
        assertThat(RoleNormalizer.normalizeRole(unknownRole)).isEmpty();
        assertThat(RoleNormalizer.normalizeToAuthority(unknownRole)).isEmpty();
        assertThat(RoleNormalizer.isValidRole(unknownRole)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ROLE_ROLE_ADMIN",
            "role_role_admin",
            "Role_Role_Admin",
            "ROLE_role_admin",
            "ROLE_ROLE_RECRUITER",
            "role_role_recruiter",
            "ROLE_ROLE_OWNER",
            "role_role_owner",
            "ROLE_ROLE_VIEWER",
            "role_role_viewer",
            "ROLE_ROLE_ROLE_ADMIN",
            "ROLE_ROLE_",
            "ROLE_ROLE"
    })
    @DisplayName("Repeated or double prefixes like ROLE_ROLE_ADMIN are rejected")
    void repeatedPrefixesRejected(String doublePrefixRole) {
        assertThat(RoleNormalizer.normalizeRole(doublePrefixRole)).isEmpty();
        assertThat(RoleNormalizer.normalizeToAuthority(doublePrefixRole)).isEmpty();
        assertThat(RoleNormalizer.isValidRole(doublePrefixRole)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ROLE_",
            "ROLE_ ",
            "ROLE_ ADMIN",
            "ROLE__ADMIN",
            "ADMIN_ROLE",
            "ADMIN_ROLE_",
            "ROLE_ADMIN_",
            "ROLE_OWNER_",
            "ROLEX_ADMIN",
            "ROLE-ADMIN",
            "ROLE:ADMIN",
            "_ADMIN"
    })
    @DisplayName("Malformed prefixes are rejected")
    void malformedPrefixesRejected(String malformedRole) {
        assertThat(RoleNormalizer.normalizeRole(malformedRole)).isEmpty();
        assertThat(RoleNormalizer.normalizeToAuthority(malformedRole)).isEmpty();
        assertThat(RoleNormalizer.isValidRole(malformedRole)).isFalse();
    }

    @Test
    @DisplayName("Non-ASCII and Unicode characters do not produce canonical roles")
    void nonAsciiRolesRejected() {
        assertThat(RoleNormalizer.normalizeRole("ADMÎN")).isEmpty();
        assertThat(RoleNormalizer.normalizeRole("ÀDMIN")).isEmpty();
        assertThat(RoleNormalizer.normalizeRole("ADMI\u00D1")).isEmpty();
    }
}
