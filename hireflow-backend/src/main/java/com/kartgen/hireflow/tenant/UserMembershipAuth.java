package com.kartgen.hireflow.tenant;

import java.util.UUID;

/**
 * Authoritative security projection record representing the consolidated
 * user membership and organization status for JWT authorization decisions.
 */
public record UserMembershipAuth(
        UUID organizationId,
        UUID userId,
        String userEmail,
        String role,
        String membershipStatus,
        String organizationStatus
) {
}
