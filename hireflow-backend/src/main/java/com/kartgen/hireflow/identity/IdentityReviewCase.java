package com.kartgen.hireflow.identity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "identity_review_cases")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class IdentityReviewCase {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id") private UUID candidateId;
    @Column(name = "conflicting_candidate_id") private UUID conflictingCandidateId;
    @Column(name = "reason_code", nullable = false, length = 50) private String reasonCode;
    @Column(columnDefinition = "TEXT") private String details;
    @Column(length = 50) private String resolution;
    @Column(name = "resolved_by") private UUID resolvedBy;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

