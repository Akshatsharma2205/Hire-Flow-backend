package com.kartgen.hireflow.ops;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "audit_events")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id") private UUID organizationId;
    @Column(name = "user_id") private UUID userId;
    @Column(nullable = false, length = 100) private String action;
    @Column(name = "entity_type", length = 100) private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(columnDefinition = "TEXT") private String details;
    @Column(name = "ip_address", length = 45) private String ipAddress;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

