package com.kartgen.hireflow.mail.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailConnectionRepository extends JpaRepository<MailConnection, UUID> {

    List<MailConnection> findByOrganizationIdAndStatus(UUID orgId, String status);

    List<MailConnection> findByOrganizationId(UUID orgId);

    Optional<MailConnection> findByIdAndOrganizationId(UUID id, UUID orgId);

    long countByOrganizationIdAndStatus(UUID orgId, String status);
}
