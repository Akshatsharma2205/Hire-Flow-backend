package com.kartgen.hireflow.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * JDBC-based reservation of the durable idempotency ledger.
 *
 * The insert is expressed as a dialect-neutral
 * {@code INSERT ... SELECT ... WHERE NOT EXISTS}, which works identically on
 * H2 (PostgreSQL mode) and PostgreSQL. Committed duplicates insert zero rows
 * without raising an exception, so the caller's transaction stays healthy and
 * can respond with the original outcome. True concurrent races are arbitrated
 * by the unique constraints on (organization_id, idempotency_key) and
 * (organization_id, credential_id, nonce): the loser receives a
 * DataIntegrityViolationException after the winner commits, which the
 * controller resolves against the committed row (202 duplicate or 409) -
 * never a second ingestion chain.
 *
 * This component always runs inside the caller's transaction so that the
 * reservation, the inbound message, its attachments and the processing jobs
 * commit (or roll back) as one atomic unit.
 */
@Component
@RequiredArgsConstructor
public class WebhookDeliveryStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Attempts to reserve the delivery row.
     *
     * @return true if this call inserted the row (caller owns the
     *         delivery), false if a delivery with the same
     *         organization + idempotency key already exists.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserve(UUID id, UUID organizationId, UUID credentialId,
                              String idempotencyKey, String nonce,
                              String bodySha256, Instant createdAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO webhook_deliveries
                    (id, organization_id, credential_id, idempotency_key, nonce,
                     body_sha256, status, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM webhook_deliveries
                    WHERE organization_id = ? AND idempotency_key = ?
                )
                """,
                id, organizationId, credentialId, idempotencyKey, nonce,
                bodySha256, WebhookDelivery.STATUS_PROCESSING, Timestamp.from(createdAt),
                organizationId, idempotencyKey);
        return inserted == 1;
    }

    /** Marks a reserved delivery as accepted and links the inbound message (same transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markAccepted(UUID id, UUID inboundMessageId) {
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET status = ?, inbound_message_id = ? WHERE id = ?",
                WebhookDelivery.STATUS_ACCEPTED, inboundMessageId, id);
    }
}
