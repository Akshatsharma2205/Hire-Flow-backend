package com.kartgen.hireflow.webhook;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-organization webhook credential. The raw secret only exists at
 * creation/rotation time in the API response; the database stores it
 * encrypted at rest (AES-256-GCM via CryptoUtil).
 */
@Entity
@Table(name = "webhook_credentials")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookCredential {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Public identifier of this credential; selects the secret for verification. */
    @Column(name = "key_id", nullable = false, length = 64, unique = true)
    private String keyId;

    /** AES-256-GCM encrypted secret (base64 iv+ciphertext+tag). Never plaintext. */
    @Column(name = "encrypted_secret", nullable = false, length = 512)
    private String encryptedSecret;

    @Column(nullable = false, length = 24)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;
}
