package com.kartgen.hireflow.hiring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "application_submissions")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationSubmission {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "application_id", nullable = false) private UUID applicationId;
    @Column(name = "resume_version_id") private UUID resumeVersionId;
    @Column(name = "submission_fingerprint", length = 255) private String submissionFingerprint;
    @Column(name = "source_type", length = 30) private String sourceType;
    @Column(name = "submitted_at", nullable = false) @Builder.Default private Instant submittedAt = Instant.now();
}

