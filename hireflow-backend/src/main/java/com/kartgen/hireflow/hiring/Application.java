package com.kartgen.hireflow.hiring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "applications")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Application {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "attempt_number", nullable = false) @Builder.Default private Integer attemptNumber = 1;
    @Column(name = "current_stage", nullable = false, length = 50) @Builder.Default private String currentStage = "NEW";
    @Column(nullable = false, length = 24) @Builder.Default private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) @Builder.Default private Instant updatedAt = Instant.now();
    @Version private Long version;
}

