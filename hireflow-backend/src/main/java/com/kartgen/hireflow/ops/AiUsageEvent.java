package com.kartgen.hireflow.ops;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "ai_usage_events")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AiUsageEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id") private UUID organizationId;
    @Column(length = 50) private String provider;
    @Column(length = 100) private String model;
    @Column(name = "input_tokens") private Integer inputTokens;
    @Column(name = "output_tokens") private Integer outputTokens;
    @Column(name = "latency_ms") private Long latencyMs;
    @Column(name = "resume_file_id") private UUID resumeFileId;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

