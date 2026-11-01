package com.kartgen.hireflow.webhook;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of one authenticated webhook delivery. Doubles as the
 * replay guard (organization + credential + nonce uniqueness) and the
 * idempotency ledger (organization + idempotency key uniqueness).
 *
 * Rows are inserted in the same transaction as the inbound message,
 * attachments and processing jobs they guard.
 */
@Entity
@Table(name = "webhook_deliveries")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookDelivery {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, length = 128)
    private String nonce;

    /** SHA-256 hex of the exact raw request body that was authenticated. */
    @Column(name = "body_sha256", nullable = false, length = 64)
    private String bodySha256;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "inbound_message_id")
    private UUID inboundMessageId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
