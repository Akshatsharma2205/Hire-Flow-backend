package com.kartgen.hireflow.document;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "resume_files")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeFile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "s3_bucket", length = 200) private String s3Bucket;
    @Column(name = "s3_object_key", columnDefinition = "TEXT") private String s3ObjectKey;
    @Column(name = "size_bytes") private Long sizeBytes;
    @Column(name = "mime_type", length = 120) private String mimeType;
    @Column(name = "malware_status", length = 30) @Builder.Default private String malwareStatus = "CLEAN";
    @Column(name = "extracted_raw_text", columnDefinition = "TEXT") private String extractedRawText;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

