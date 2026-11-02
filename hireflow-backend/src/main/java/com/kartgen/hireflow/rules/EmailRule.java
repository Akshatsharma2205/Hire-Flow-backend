package com.kartgen.hireflow.rules;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 100;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(name = "folder_filter", length = 120)
    private String folderFilter;

    @Column(name = "sender_include_json", columnDefinition = "TEXT")
    private String senderIncludeJson;

    @Column(name = "sender_exclude_json", columnDefinition = "TEXT")
    private String senderExcludeJson;

    @Column(name = "subject_keywords_json", columnDefinition = "TEXT")
    private String subjectKeywordsJson;

    @Column(name = "subject_exclude_json", columnDefinition = "TEXT")
    private String subjectExcludeJson;

    // Body keywords evaluated in-memory during sync/intake, NEVER stored with email body
    @Column(name = "body_keywords_json", columnDefinition = "TEXT")
    private String bodyKeywordsJson;

    @Column(name = "body_exclude_json", columnDefinition = "TEXT")
    private String bodyExcludeJson;

    @Column(name = "require_attachment", nullable = false)
    @Builder.Default
    private Boolean requireAttachment = true;

    @Column(name = "allowed_mimes_json", columnDefinition = "TEXT")
    private String allowedMimesJson;

    @Column(name = "min_attachment_size_bytes")
    private Long minAttachmentSizeBytes;

    @Column(name = "max_attachment_size_bytes")
    private Long maxAttachmentSizeBytes;

    @Column(name = "target_job_id")
    private UUID targetJobId;

    @Column(name = "duplicate_policy", nullable = false, length = 50)
    @Builder.Default
    private String duplicatePolicy = "UPDATE_PROFILE_ONLY";

    @Column(name = "action_on_match", nullable = false, length = 30)
    @Builder.Default
    private String actionOnMatch = "PROCESS";

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
