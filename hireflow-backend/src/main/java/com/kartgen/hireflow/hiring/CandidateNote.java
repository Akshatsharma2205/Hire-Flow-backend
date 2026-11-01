package com.kartgen.hireflow.hiring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "candidate_notes")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateNote {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "author_id") private UUID authorId;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

