package com.kartgen.hireflow.mail.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "mail_connections")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class MailConnection {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(nullable = false, length = 30) private String provider;
    @Column(name = "mailbox_address", nullable = false, length = 320) private String mailboxAddress;
    @Column(name = "imap_host", length = 200) private String imapHost;
    @Column(name = "imap_port") private Integer imapPort;
    @Column(name = "folder_filter", length = 120) @Builder.Default private String folderFilter = "INBOX";
    @Column(name = "keyword_filter_json", columnDefinition = "TEXT") private String keywordFilterJson;
    @Column(name = "oauth_secret_ref", columnDefinition = "TEXT") private String oauthSecretRef;
    @Column(name = "permission_scope", columnDefinition = "TEXT") private String permissionScope;
    @Column(name = "sync_cursor", columnDefinition = "TEXT") private String syncCursor;
    @Column(name = "last_success_at") private Instant lastSuccessAt;
    @Column(nullable = false, length = 30) @Builder.Default private String status = "ACTIVE";
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

