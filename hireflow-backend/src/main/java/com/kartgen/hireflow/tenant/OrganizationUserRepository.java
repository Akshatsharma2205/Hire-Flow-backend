package com.kartgen.hireflow.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationUserRepository extends JpaRepository<OrganizationUser, UUID> {
    Optional<OrganizationUser> findByOrganizationIdAndUserId(UUID orgId, UUID userId);
    Optional<OrganizationUser> findByExtensionTokenHash(String hash);
    List<OrganizationUser> findByUserId(UUID userId);

    @Query("""
        SELECT new com.kartgen.hireflow.tenant.UserMembershipAuth(
            ou.organizationId,
            ou.userId,
            u.email,
            ou.role,
            ou.status,
            o.status
        )
        FROM OrganizationUser ou
        JOIN User u ON ou.userId = u.id
        JOIN Organization o ON ou.organizationId = o.id
        WHERE ou.organizationId = :orgId AND ou.userId = :userId
    """)
    Optional<UserMembershipAuth> findAuthMembership(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId
    );
}


