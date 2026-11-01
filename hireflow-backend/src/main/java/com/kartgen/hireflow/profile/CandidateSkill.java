package com.kartgen.hireflow.profile;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "candidate_skills")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateSkill {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "skill_id", nullable = false) private UUID skillId;
    @Column(name = "profile_version_id", nullable = false) private UUID profileVersionId;
    @Column(name = "evidence_type", length = 30) private String evidenceType;
    @Column(precision = 4, scale = 3) private java.math.BigDecimal confidence;
    @Column(name = "last_confirmed_at", nullable = false) @Builder.Default private Instant lastConfirmedAt = Instant.now();
}

