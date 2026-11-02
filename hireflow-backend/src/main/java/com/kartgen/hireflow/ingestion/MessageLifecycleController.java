package com.kartgen.hireflow.ingestion;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessage;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MessageLifecycleController {

    private final InboundMessageRepository messageRepo;
    private final InboundAttachmentRepository attachmentRepo;

    public record InboundMessageDto(
            UUID id,
            UUID organizationId,
            UUID connectionId,
            String providerMessageId,
            String internetMessageId,
            String senderEmail,
            String senderName,
            String originalSenderClaim,
            String sourceType,
            String subject,
            UUID jobId,
            Instant receivedAt,
            String processingStatus,
            String failureCode,
            UUID matchedRuleId,
            String ruleAction,
            String ruleExplanation,
            List<AttachmentSummaryDto> attachments,
            Instant createdAt
    ) {}

    public record AttachmentSummaryDto(
            UUID id,
            Integer attachmentIndex,
            String originalFilename,
            String declaredMime,
            String detectedMime,
            Long sizeBytes,
            String status
    ) {}

    @GetMapping({"/api/v1/operations/messages", "/api/v1/processing-activity"})
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<List<InboundMessageDto>> listMessages(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "connectionId", required = false) UUID connectionId
    ) {
        UUID orgId = TenantContext.getOrganizationId();
        List<InboundMessage> messages;
        if (status != null && !status.isBlank()) {
            messages = messageRepo.findByOrganizationIdAndProcessingStatusOrderByCreatedAtDesc(orgId, status.trim().toUpperCase());
        } else {
            messages = messageRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        }

        if (connectionId != null) {
            messages = messages.stream().filter(m -> connectionId.equals(m.getConnectionId())).toList();
        }

        List<InboundMessageDto> dtos = messages.stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/api/v1/operations/messages/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','HIRING_MANAGER','MANAGER')")
    public ResponseEntity<InboundMessageDto> getMessage(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        InboundMessage message = messageRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Inbound message not found: " + id));
        return ResponseEntity.ok(toDto(message));
    }

    @PostMapping({"/api/v1/operations/messages/{id}/retry", "/api/v1/processing-activity/{id}/retry"})
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<InboundMessageDto> retryMessage(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        InboundMessage message = messageRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Inbound message not found: " + id));
        message.setProcessingStatus("PENDING");
        message.setFailureCode(null);
        messageRepo.save(message);
        log.info("Reset inbound message id={} to PENDING for retry, orgId={}", id, orgId);
        return ResponseEntity.ok(toDto(message));
    }

    private InboundMessageDto toDto(InboundMessage m) {
        List<AttachmentSummaryDto> atts = attachmentRepo.findByMessageId(m.getId()).stream()
                .map(a -> new AttachmentSummaryDto(
                        a.getId(), a.getAttachmentIndex(), a.getOriginalFilename(),
                        a.getDeclaredMime(), a.getDetectedMime(), a.getSizeBytes(), a.getStatus()))
                .toList();

        return new InboundMessageDto(
                m.getId(), m.getOrganizationId(), m.getConnectionId(),
                m.getProviderMessageId(), m.getInternetMessageId(),
                m.getSenderEmail(), m.getSenderName(), m.getOriginalSenderClaim(),
                m.getSourceType(), m.getSubject(), m.getJobId(),
                m.getReceivedAt(), m.getProcessingStatus(), m.getFailureCode(),
                m.getMatchedRuleId(), m.getRuleAction(), m.getRuleExplanation(),
                atts, m.getCreatedAt()
        );
    }
}
