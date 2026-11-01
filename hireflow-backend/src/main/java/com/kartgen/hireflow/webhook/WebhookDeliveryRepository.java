package com.kartgen.hireflow.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    Optional<WebhookDelivery> findByOrganizationIdAndCredentialIdAndNonce(
            UUID organizationId, UUID credentialId, String nonce);

    long countByOrganizationId(UUID organizationId);
}
