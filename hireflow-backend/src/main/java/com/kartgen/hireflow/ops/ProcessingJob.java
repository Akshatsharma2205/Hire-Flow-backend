package com.kartgen.hireflow.ops;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processing_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingJob {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "job_type", nullable = false, length = 50) private String jobType;
    @Column(name = "reference_id") private UUID referenceId;
    @Column(nullable = false, length = 30) @Builder.Default private String status = "PENDING";
    @Column(name = "attempt_count", nullable = false) @Builder.Default private Integer attemptCount = 0;
    @Column(name = "max_attempts", nullable = false) @Builder.Default private Integer maxAttempts = 3;
    @Column(name = "next_attempt_at") @Builder.Default private Instant nextAttemptAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "failure_reason", columnDefinition = "TEXT") private String failureReason;
    @Column(name = "context_json", columnDefinition = "TEXT") private String contextJson;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}
