package com.kartgen.hireflow.jobs;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "jobs")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Job {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(nullable = false, length = 250) private String title;
    @Column(length = 150) private String department;
    @Column(length = 200) private String location;
    @Column(nullable = false, length = 24) @Builder.Default private String status = "DRAFT";
    @Column(name = "current_version_id") private UUID currentVersionId;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

