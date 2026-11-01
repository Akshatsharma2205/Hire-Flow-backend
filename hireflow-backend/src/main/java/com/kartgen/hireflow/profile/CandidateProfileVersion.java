package com.kartgen.hireflow.profile;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "candidate_profile_versions")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateProfileVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "resume_version_id", nullable = false) private UUID resumeVersionId;
    @Column(name = "profile_version", nullable = false) private Long profileVersion;
    @Column(name = "full_name", length = 250) private String fullName;
    @Column(length = 250) private String headline;
    @Column(name = "experience_months") private Integer experienceMonths;
    @Column(name = "experience_basis", length = 50) private String experienceBasis;
    @Column(name = "location_text", length = 250) private String locationText;
    @Column(name = "education_json", columnDefinition = "TEXT") private String educationJson;
    @Column(name = "source_evidence_json", columnDefinition = "TEXT") private String sourceEvidenceJson;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

