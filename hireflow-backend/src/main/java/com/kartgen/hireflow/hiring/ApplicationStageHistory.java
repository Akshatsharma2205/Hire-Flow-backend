package com.kartgen.hireflow.hiring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "application_stage_history")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationStageHistory {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "application_id", nullable = false) private UUID applicationId;
    @Column(name = "from_stage", length = 50) private String fromStage;
    @Column(name = "to_stage", nullable = false, length = 50) private String toStage;
    @Column(name = "changed_by") private UUID changedBy;
    @Column(name = "changed_at", nullable = false) @Builder.Default private Instant changedAt = Instant.now();
    @Column(columnDefinition = "TEXT") private String note;
}

