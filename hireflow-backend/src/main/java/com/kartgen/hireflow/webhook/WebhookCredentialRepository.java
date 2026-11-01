package com.kartgen.hireflow.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookCredentialRepository extends JpaRepository<WebhookCredential, UUID> {

    Optional<WebhookCredential> findByKeyId(String keyId);

    List<WebhookCredential> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
