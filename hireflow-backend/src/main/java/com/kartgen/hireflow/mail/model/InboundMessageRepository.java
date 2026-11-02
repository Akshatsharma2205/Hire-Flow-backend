package com.kartgen.hireflow.mail.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {

    long countByOrganizationId(UUID orgId);

    Optional<InboundMessage> findByIdAndOrganizationId(UUID id, UUID orgId);

    List<InboundMessage> findByOrganizationIdAndIdIn(UUID orgId, java.util.Collection<UUID> ids);

    Optional<InboundMessage> findByOrganizationIdAndConnectionIdAndProviderMessageId(
            UUID orgId, UUID connectionId, String providerMessageId);

    List<InboundMessage> findByOrganizationIdOrderByCreatedAtDesc(UUID orgId);

    List<InboundMessage> findByOrganizationIdAndProcessingStatusOrderByCreatedAtDesc(UUID orgId, String processingStatus);
}


