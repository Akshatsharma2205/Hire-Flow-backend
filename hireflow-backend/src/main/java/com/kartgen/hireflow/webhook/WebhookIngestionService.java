package com.kartgen.hireflow.webhook;

import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional orchestration of one authenticated webhook delivery.
 *
 * A single transaction covers: reservation of the durable idempotency
 * ledger row (database unique constraints arbitrate concurrent races),
 * creation of the inbound message, its attachments and their processing
 * jobs, and the final ACCEPTED outcome. If any step fails, the whole
 * delivery rolls back and the sender's retry re-enters cleanly.
 *
 * Semantics:
 *  - first valid delivery          -> ACCEPTED
 *  - same key + same body digest   -> DUPLICATE (original result returned)
 *  - same key + different digest   -> PAYLOAD_CONFLICT (409)
 *  - same nonce + different key    -> NONCE_REPLAY (409)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIngestionService {

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookDeliveryStore deliveryStore;
    private final MessageIngestionService ingestionService;
    private final InboundAttachmentRepository attachmentRepo;

    @PersistenceContext
    private EntityManager entityManager;

    public record DeliveryOutcome(
            Type type,
            UUID deliveryId,
            UUID inboundMessageId,
            List<UUID> attachmentIds) {

        public enum Type { ACCEPTED, DUPLICATE, PAYLOAD_CONFLICT, NONCE_REPLAY }

        static DeliveryOutcome accepted(UUID deliveryId, UUID messageId, List<UUID> attachmentIds) {
            return new DeliveryOutcome(Type.ACCEPTED, deliveryId, messageId, attachmentIds);
        }

        static DeliveryOutcome duplicate(WebhookDelivery delivery, List<UUID> attachmentIds) {
            return new DeliveryOutcome(Type.DUPLICATE, delivery.getId(),
                    delivery.getInboundMessageId(), attachmentIds);
        }

        static DeliveryOutcome payloadConflict(WebhookDelivery delivery) {
            return new DeliveryOutcome(Type.PAYLOAD_CONFLICT, delivery.getId(), null, List.of());
        }

        static DeliveryOutcome nonceReplay() {
            return new DeliveryOutcome(Type.NONCE_REPLAY, null, null, List.of());
        }
    }

    @Transactional
    public DeliveryOutcome ingestVerifiedDelivery(UUID organizationId, UUID credentialId,
                                                  String idempotencyKey, String nonce,
                                                  String bodySha256, String forwarderEmail,
                                                  String originalSenderEmail, String subject,
                                                  String sourceType, UUID jobId,
                                                  List<byte[]> attachmentData,
                                                  List<String> attachmentNames) {
        // 1) Durable replay check: same organization + credential + nonce.
        var byNonce = deliveryRepo
                .findByOrganizationIdAndCredentialIdAndNonce(organizationId, credentialId, nonce)
                .orElse(null);
        if (byNonce != null) {
            if (byNonce.getIdempotencyKey().equals(idempotencyKey)) {
                // Exact replay of the same logical delivery: return the original outcome.
                log.info("Webhook delivery replayed (same idempotency key): delivery={} org={}",
                        byNonce.getId(), organizationId);
                return DeliveryOutcome.duplicate(byNonce, attachmentIds(byNonce.getInboundMessageId()));
            }
            log.warn("Webhook nonce reuse with different idempotency key rejected: org={} credential={}",
                    organizationId, credentialId);
            return DeliveryOutcome.nonceReplay();
        }

        // 2) Atomic idempotency reservation; the database unique constraint on
        //    (organization_id, idempotency_key) arbitrates concurrent duplicates.
        UUID deliveryId = UUID.randomUUID();
        boolean reserved = deliveryStore.tryReserve(deliveryId, organizationId, credentialId,
                idempotencyKey, nonce, bodySha256, Instant.now());
        if (!reserved) {
            WebhookDelivery existing = deliveryRepo
                    .findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency conflict without existing delivery row"));
            if (existing.getBodySha256().equals(bodySha256)) {
                log.info("Webhook delivery duplicate (same payload): delivery={} org={}",
                        existing.getId(), organizationId);
                return DeliveryOutcome.duplicate(existing, attachmentIds(existing.getInboundMessageId()));
            }
            log.warn("Webhook idempotency key reused with different payload rejected: delivery={} org={}",
                    existing.getId(), organizationId);
            return DeliveryOutcome.payloadConflict(existing);
        }

        // 3) Ingest atomically in the same transaction: one message, N
        //    attachments, N parse jobs. Rolls back everything on failure.
        MessageIngestionService.WebhookIngestResult result = ingestionService.ingestFromWebhook(
                organizationId, forwarderEmail, originalSenderEmail, subject, sourceType,
                attachmentData, attachmentNames, jobId);

        // Hibernate defers the JPA inserts until flush; the JDBC outcome update
        // runs on the same connection and must see the persisted message row.
        entityManager.flush();

        // 4) Persist the delivery outcome (same transaction).
        deliveryStore.markAccepted(deliveryId, result.messageId());
        return DeliveryOutcome.accepted(deliveryId, result.messageId(), result.attachmentIds());
    }

    private List<UUID> attachmentIds(UUID messageId) {
        if (messageId == null) {
            return List.of();
        }
        return attachmentRepo.findByMessageId(messageId).stream()
                .map(InboundAttachment::getId)
                .toList();
    }

    /** Committed-row lookup used by the controller when a concurrent race loses. */
    @Transactional(readOnly = true)
    public WebhookDelivery findDelivery(UUID organizationId, String idempotencyKey) {
        return deliveryRepo.findByOrganizationIdAndIdempotencyKey(organizationId, idempotencyKey)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<UUID> attachmentIdsOf(WebhookDelivery delivery) {
        return attachmentIds(delivery.getInboundMessageId());
    }
}
