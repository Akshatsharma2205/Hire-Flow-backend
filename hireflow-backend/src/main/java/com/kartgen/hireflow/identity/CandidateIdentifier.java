package com.kartgen.hireflow.identity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "candidate_identifiers")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateIdentifier {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "identifier_type", nullable = false, length = 25) private String identifierType;
    @Column(name = "normalized_value", nullable = false, length = 500) private String normalizedValue;
    @Column(name = "source_type", length = 30) private String sourceType;
    @Column(name = "verification_status", nullable = false, length = 24) @Builder.Default private String verificationStatus = "UNVERIFIED";
    @Column(name = "first_seen_at", nullable = false) @Builder.Default private Instant firstSeenAt = Instant.now();
    @Column(name = "last_seen_at", nullable = false) @Builder.Default private Instant lastSeenAt = Instant.now();
}

