package com.kartgen.hireflow.ops;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "outbox_events")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "aggregate_type", length = 100) private String aggregateType;
    @Column(name = "aggregate_id") private UUID aggregateId;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(columnDefinition = "TEXT") private String payload;
    @Column(nullable = false, length = 30) @Builder.Default private String status = "PENDING";
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    @Column(name = "published_at") private Instant publishedAt;
}

