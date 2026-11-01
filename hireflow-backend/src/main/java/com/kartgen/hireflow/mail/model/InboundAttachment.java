package com.kartgen.hireflow.mail.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "inbound_attachments")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundAttachment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "message_id", nullable = false) private UUID messageId;
    @Column(name = "attachment_index", nullable = false) private Integer attachmentIndex;
    @Column(name = "original_filename", columnDefinition = "TEXT") private String originalFilename;
    @Column(name = "declared_mime", length = 120) private String declaredMime;
    @Column(name = "detected_mime", length = 120) private String detectedMime;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(length = 64) private String sha256;
    @Column(name = "resume_file_id") private UUID resumeFileId;
    @Column(nullable = false, length = 30) @Builder.Default private String status = "RECEIVED";
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

