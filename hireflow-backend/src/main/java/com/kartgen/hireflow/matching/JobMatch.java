package com.kartgen.hireflow.matching;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "job_matches")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class JobMatch {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "job_version_id", nullable = false) private UUID jobVersionId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "profile_version_id", nullable = false) private UUID profileVersionId;
    @Column(name = "scorer_version", nullable = false, length = 50) @Builder.Default private String scorerVersion = "v1";
    @Column(precision = 6, scale = 3) private BigDecimal score;
    @Column(name = "explanation_json", columnDefinition = "TEXT") private String explanationJson;
    @Column(nullable = false, length = 24) @Builder.Default private String status = "READY";
    @Column(name = "computed_at", nullable = false) @Builder.Default private Instant computedAt = Instant.now();
}

