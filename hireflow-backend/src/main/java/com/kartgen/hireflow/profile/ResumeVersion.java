package com.kartgen.hireflow.profile;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity @Table(name = "resume_versions")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "resume_file_id", nullable = false) private UUID resumeFileId;
    @Column(name = "version_number", nullable = false) private Integer versionNumber;
    @Column(name = "received_at", nullable = false) @Builder.Default private Instant receivedAt = Instant.now();
    @Column(name = "document_date") private LocalDate documentDate;
    @Column(name = "source_attachment_id") private UUID sourceAttachmentId;
    @Column(name = "parse_status", nullable = false, length = 30) @Builder.Default private String parseStatus = "PENDING";
    @Column(name = "validation_status", nullable = false, length = 30) @Builder.Default private String validationStatus = "VALID";
    @Column(name = "parser_version", length = 50) private String parserVersion;
    @Column(name = "processed_at") private Instant processedAt;
    @Column(name = "promoted_at") private Instant promotedAt;
    @Column(name = "superseded_at") private Instant supersededAt;
}

