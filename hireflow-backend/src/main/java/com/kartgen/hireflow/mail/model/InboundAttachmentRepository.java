package com.kartgen.hireflow.mail.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundAttachmentRepository extends JpaRepository<InboundAttachment, UUID> {

    List<InboundAttachment> findByMessageId(UUID messageId);

    List<InboundAttachment> findByOrganizationIdAndStatus(UUID orgId, String status);

    Optional<InboundAttachment> findByIdAndOrganizationId(UUID id, UUID orgId);

    List<InboundAttachment> findByOrganizationIdAndIdIn(UUID orgId, java.util.Collection<UUID> ids);

    Optional<InboundAttachment> findFirstByOrganizationIdAndResumeFileIdAndStatusNotOrderByCreatedAtAsc(
            UUID orgId, UUID resumeFileId, String status);

    long countByOrganizationId(UUID orgId);

    long countByMessageIdAndStatusIn(UUID messageId, List<String> statuses);
}
