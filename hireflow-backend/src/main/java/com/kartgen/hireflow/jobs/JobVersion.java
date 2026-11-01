package com.kartgen.hireflow.jobs;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "job_versions")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class JobVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "version_number", nullable = false) private Integer versionNumber;
    @Column(name = "jd_text", columnDefinition = "TEXT") private String jdText;
    @Column(name = "jd_hash", length = 64) private String jdHash;
    @Column(name = "min_experience_months") private Integer minExperienceMonths;
    @Column(name = "max_experience_months") private Integer maxExperienceMonths;
    @Column(name = "requirements_json", columnDefinition = "TEXT") private String requirementsJson;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

