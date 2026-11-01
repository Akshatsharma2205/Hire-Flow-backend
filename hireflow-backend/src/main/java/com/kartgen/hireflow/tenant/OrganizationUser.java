package com.kartgen.hireflow.tenant;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_users")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OrganizationUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "extension_token_hash", length = 128)
    private String extensionTokenHash;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    @Version
    private Long version;
}

