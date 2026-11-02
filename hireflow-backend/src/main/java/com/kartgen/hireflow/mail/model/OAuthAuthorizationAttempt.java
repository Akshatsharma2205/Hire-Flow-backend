package com.kartgen.hireflow.mail.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_authorization_attempts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OAuthAuthorizationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "state_token", nullable = false, unique = true, length = 128)
    private String stateToken;

    @Column(name = "state", length = 128)
    private String state;

    @Column(name = "code_verifier", length = 128)
    private String codeVerifier;

    @Column(name = "redirect_uri", nullable = false, length = 1000)
    private String redirectUri;

    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
