package com.kartgen.hireflow.mail.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "inbound_messages")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InboundMessage {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "connection_id") private UUID connectionId;
    @Column(name = "provider_message_id", length = 500) private String providerMessageId;
    @Column(name = "internet_message_id", length = 500) private String internetMessageId;
    @Column(name = "sender_email", length = 320) private String senderEmail;
    @Column(name = "sender_name", length = 200) private String senderName;
    @Column(name = "original_sender_claim", length = 320) private String originalSenderClaim;
    @Column(name = "source_type", nullable = false, length = 30) private String sourceType;
    @Column(columnDefinition = "TEXT") private String subject;
    @Column(name = "job_id") private UUID jobId;
    @Column(name = "received_at") private Instant receivedAt;
    @Column(name = "processing_status", nullable = false, length = 30) @Builder.Default private String processingStatus = "DISCOVERED";
    @Column(name = "failure_code", length = 80) private String failureCode;
    @Column(name = "matched_rule_id") private UUID matchedRuleId;
    @Column(name = "rule_action", length = 50) private String ruleAction;
    @Column(name = "rule_explanation", columnDefinition = "TEXT") private String ruleExplanation;
    @Column(name = "created_at", nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}

