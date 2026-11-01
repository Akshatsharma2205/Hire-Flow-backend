package com.kartgen.hireflow.mail.channel;

import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.security.WebhookAuthFilter;
import com.kartgen.hireflow.security.WebhookMultipartBody;
import com.kartgen.hireflow.webhook.WebhookIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Channel 4: Inbound Forwarding Webhook.
 *
 * <p>Requests are authenticated by {@link WebhookAuthFilter} (per-organization
 * HMAC v2 signature, timestamp, nonce, idempotency key, Content-Type binding,
 * query-string rejection). The verified organization, credential, body digest
 * and pre-parsed multipart body arrive via request attributes - never from
 * client-controlled query parameters or form fields.</p>
 *
 * <p>Multipart and JSON/base64 deliveries share the same idempotent,
 * tenant-isolated ingestion path: ONE forwarded email -> ONE inbound message
 * -> N attachments -> N processing jobs. Metadata is validated strictly:
 * unknown, duplicate or oversized fields are rejected rather than truncated.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mail-webhook")
@RequiredArgsConstructor
public class ForwardingWebhookController {

    private static final int MAX_SENDER_EMAIL_BYTES = 320;
    private static final int MAX_SUBJECT_BYTES = 4_000;
    private static final int MAX_SOURCE_TYPE_BYTES = 30;
    private static final int MAX_JOB_ID_BYTES = 36;
    private static final int MAX_FILENAME_BYTES = 1_024;

    private static final Set<String> METADATA_FIELDS = Set.of(
            "senderEmail", "originalSenderEmail", "subject", "sourceType", "jobId");

    private final WebhookIngestionService ingestionService;
    private final HireFlowProperties props;

    /**
     * Multipart email delivery. The HMAC signature covers the exact raw
     * multipart bytes (including boundary), which WebhookAuthFilter has
     * already captured, verified and parsed.
     */
    @PostMapping(value = "/inbound", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> inboundWebhook(HttpServletRequest request) {
        WebhookMultipartBody multipart = attribute(request, WebhookAuthFilter.ATTR_MULTIPART_BODY);
        if (multipart == null) {
            return badRequest("Malformed multipart payload");
        }
        try {
            return ingest(request, readMultipart(multipart));
        } catch (PayloadException e) {
            return payloadError(e);
        }
    }

    /**
     * JSON/base64 email delivery. Attachments are base64-encoded; encoded and
     * decoded sizes are bounded before allocation and processing.
     */
    @PostMapping(value = "/inbound", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> inboundWebhookJson(HttpServletRequest request,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        if (payload == null) {
            return badRequest("Missing JSON payload");
        }
        try {
            return ingest(request, readJson(payload));
        } catch (PayloadException e) {
            return payloadError(e);
        }
    }

    private DeliveryPayload readMultipart(WebhookMultipartBody multipart) {
        MetadataBudget budget = new MetadataBudget(props.getWebhook().getMaxMetadataBytes());
        Set<String> seenMetadata = new HashSet<>();
        DeliveryPayload payload = new DeliveryPayload();

        for (WebhookMultipartBody.Part part : multipart.parts()) {
            String name = part.getName();
            boolean file = part.getSubmittedFileName() != null;

            if ("files".equals(name)) {
                if (!file) {
                    throw badRequestException("Attachment filename missing");
                }
                addAttachment(part, part.getSubmittedFileName(), payload, budget);
                continue;
            }

            if (!METADATA_FIELDS.contains(name)) {
                throw badRequestException("Unknown multipart field");
            }
            if (!seenMetadata.add(name)) {
                throw badRequestException("Duplicate multipart field");
            }
            if (file) {
                throw badRequestException("Metadata field must not be a file");
            }

            switch (name) {
                case "senderEmail" -> payload.senderEmail = blankToNull(
                        readTextPart(part, MAX_SENDER_EMAIL_BYTES, "senderEmail", budget));
                case "originalSenderEmail" -> payload.originalSenderEmail = blankToNull(
                        readTextPart(part, MAX_SENDER_EMAIL_BYTES, "originalSenderEmail", budget));
                case "subject" -> payload.subject = blankToNull(
                        readTextPart(part, MAX_SUBJECT_BYTES, "subject", budget));
                case "sourceType" -> payload.sourceType = blankToNull(
                        readTextPart(part, MAX_SOURCE_TYPE_BYTES, "sourceType", budget));
                case "jobId" -> payload.jobId = parseJobId(
                        readTextPart(part, MAX_JOB_ID_BYTES, "jobId", budget));
                default -> throw badRequestException("Unknown multipart field");
            }
        }

        if (payload.attachmentData.isEmpty()) {
            throw badRequestException("No attachments provided");
        }
        if (payload.sourceType == null) {
            payload.sourceType = "FORWARDING";
        }
        return payload;
    }

    private DeliveryPayload readJson(Map<String, Object> body) {
        MetadataBudget budget = new MetadataBudget(props.getWebhook().getMaxMetadataBytes());
        DeliveryPayload payload = new DeliveryPayload();

        payload.senderEmail = blankToNull(readJsonString(body, "senderEmail",
                MAX_SENDER_EMAIL_BYTES, budget));
        payload.originalSenderEmail = blankToNull(readJsonString(body, "originalSenderEmail",
                MAX_SENDER_EMAIL_BYTES, budget));
        payload.subject = blankToNull(readJsonString(body, "subject", MAX_SUBJECT_BYTES, budget));
        payload.sourceType = blankToNull(readJsonString(body, "sourceType",
                MAX_SOURCE_TYPE_BYTES, budget));
        payload.jobId = parseJobId(readJsonString(body, "jobId", MAX_JOB_ID_BYTES, budget));

        Object attachmentsRaw = body.get("attachments");
        if (attachmentsRaw == null) {
            throw badRequestException("No attachments provided");
        }
        if (!(attachmentsRaw instanceof List<?> attachments)) {
            throw badRequestException("Invalid attachments");
        }
        if (attachments.size() > props.getWebhook().getMaxAttachments()) {
            throw tooLarge("Too many attachments");
        }

        long totalAttachmentBytes = 0;
        for (Object item : attachments) {
            if (!(item instanceof Map<?, ?> attachment)) {
                throw badRequestException("Invalid attachment entry");
            }

            Object dataRaw = attachment.get("data");
            if (!(dataRaw instanceof String encoded)) {
                throw badRequestException("Invalid attachment encoding");
            }
            if ((long) encoded.length() > maxBase64Length(props.getWebhook().getMaxAttachmentBytes())) {
                throw tooLarge("Attachment too large");
            }

            byte[] decoded;
            try {
                decoded = java.util.Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw badRequestException("Invalid attachment encoding");
            }
            if (decoded.length > props.getWebhook().getMaxAttachmentBytes()) {
                throw tooLarge("Attachment too large");
            }
            totalAttachmentBytes = addAttachmentBytes(totalAttachmentBytes, decoded.length);

            String filename = readAttachmentFilename(attachment, budget);
            payload.attachmentData.add(decoded);
            payload.attachmentNames.add(filename);
        }

        if (payload.attachmentData.isEmpty()) {
            throw badRequestException("No valid attachments");
        }
        if (payload.sourceType == null) {
            payload.sourceType = "FORWARDING";
        }
        return payload;
    }

    private void addAttachment(WebhookMultipartBody.Part part, String filename,
                               DeliveryPayload payload, MetadataBudget budget) {
        if (filename == null || filename.isBlank()) {
            throw badRequestException("Attachment filename missing");
        }
        budget.add(filename, MAX_FILENAME_BYTES, "Attachment filename");

        if (payload.attachmentData.size() >= props.getWebhook().getMaxAttachments()) {
            throw tooLarge("Too many attachments");
        }
        if (part.getSize() > props.getWebhook().getMaxAttachmentBytes()) {
            throw tooLarge("Attachment too large");
        }
        payload.totalAttachmentBytes = addAttachmentBytes(
                payload.totalAttachmentBytes, part.getSize());
        payload.attachmentData.add(part.content());
        payload.attachmentNames.add(filename);
    }

    private long addAttachmentBytes(long current, long additional) {
        if (additional > props.getWebhook().getMaxTotalAttachmentBytes() - current) {
            throw tooLarge("Total attachment size too large");
        }
        return current + additional;
    }

    private String readAttachmentFilename(Map<?, ?> attachment, MetadataBudget budget) {
        Object raw = attachment.get("filename");
        if (raw == null) {
            return "resume.pdf";
        }
        if (!(raw instanceof String filename)) {
            throw badRequestException("Invalid attachment filename");
        }
        if (filename.isBlank()) {
            return "resume.pdf";
        }
        budget.add(filename, MAX_FILENAME_BYTES, "Attachment filename");
        return filename;
    }

    private String readTextPart(WebhookMultipartBody.Part part, int maxBytes,
                                String field, MetadataBudget budget) {
        if (part.getSize() > maxBytes) {
            throw badRequestException(field + " too long");
        }
        String value = decodeUtf8(part.content());
        budget.add(value, maxBytes, field);
        return value;
    }

    private String readJsonString(Map<String, Object> body, String field,
                                  int maxBytes, MetadataBudget budget) {
        Object raw = body.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw badRequestException("Invalid " + field);
        }
        budget.add(value, maxBytes, field);
        return value;
    }

    private UUID parseJobId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw badRequestException("Invalid jobId");
        }
    }

    private ResponseEntity<?> ingest(HttpServletRequest request, DeliveryPayload payload) {
        UUID organizationId = attribute(request, WebhookAuthFilter.ATTR_ORG_ID);
        UUID credentialId = attribute(request, WebhookAuthFilter.ATTR_CREDENTIAL_ID);
        String idempotencyKey = (String) request.getAttribute(WebhookAuthFilter.ATTR_IDEMPOTENCY_KEY);
        String nonce = (String) request.getAttribute(WebhookAuthFilter.ATTR_NONCE);
        String bodySha256 = (String) request.getAttribute(WebhookAuthFilter.ATTR_BODY_SHA256);
        if (organizationId == null || credentialId == null || idempotencyKey == null
                || nonce == null || bodySha256 == null) {
            // Should be unreachable: the filter always sets these on authenticated requests.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Webhook authentication missing"));
        }

        try {
            WebhookIngestionService.DeliveryOutcome outcome = ingestionService.ingestVerifiedDelivery(
                    organizationId, credentialId, idempotencyKey, nonce, bodySha256,
                    payload.senderEmail, payload.originalSenderEmail, payload.subject,
                    payload.sourceType, payload.jobId,
                    payload.attachmentData, payload.attachmentNames);

            return switch (outcome.type()) {
                case ACCEPTED -> ResponseEntity.accepted().body(Map.of(
                        "status", "QUEUED",
                        "deliveryId", outcome.deliveryId().toString(),
                        "inboundMessageId", outcome.inboundMessageId().toString(),
                        "acceptedCount", outcome.attachmentIds().size(),
                        "attachmentIds", outcome.attachmentIds()));
                case DUPLICATE -> ResponseEntity.accepted().body(Map.of(
                        "status", "DUPLICATE",
                        "deliveryId", outcome.deliveryId().toString(),
                        "inboundMessageId", outcome.inboundMessageId() == null
                                ? "" : outcome.inboundMessageId().toString(),
                        "acceptedCount", outcome.attachmentIds().size(),
                        "attachmentIds", outcome.attachmentIds()));
                case PAYLOAD_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Idempotency key already used with a different payload",
                        "deliveryId", outcome.deliveryId().toString()));
                case NONCE_REPLAY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Webhook replay detected"));
            };
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent race lost at the database level (the unique constraints
            // arbitrated two simultaneous deliveries). The losing transaction
            // has rolled back completely; resolve against the committed winner
            // so an identical in-flight duplicate still gets the original result.
            log.warn("Webhook delivery race resolved by database constraint: org={}, detail={}",
                    organizationId, e.getMessage());
            var existing = ingestionService.findDelivery(organizationId, idempotencyKey);
            if (existing != null && bodySha256.equals(existing.getBodySha256())) {
                return ResponseEntity.accepted().body(Map.of(
                        "status", "DUPLICATE",
                        "deliveryId", existing.getId().toString(),
                        "inboundMessageId", existing.getInboundMessageId() == null
                                ? "" : existing.getInboundMessageId().toString(),
                        "acceptedCount", ingestionService.attachmentIdsOf(existing).size(),
                        "attachmentIds", ingestionService.attachmentIdsOf(existing)));
            }
            if (existing != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "Idempotency key already used with a different payload",
                        "deliveryId", existing.getId().toString()));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Webhook delivery conflict"));
        } catch (Exception e) {
            log.error("Webhook ingest error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Webhook processing failed"));
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw badRequestException("Invalid UTF-8 metadata");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long maxBase64Length(long decodedMax) {
        return ((decodedMax + 2) / 3) * 4;
    }

    @SuppressWarnings("unchecked")
    private <T> T attribute(HttpServletRequest request, String name) {
        return (T) request.getAttribute(name);
    }

    private ResponseEntity<?> payloadError(PayloadException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private PayloadException badRequestException(String message) {
        return new PayloadException(HttpStatus.BAD_REQUEST, message);
    }

    private PayloadException tooLarge(String message) {
        return new PayloadException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    private static final class MetadataBudget {
        private final long maximum;
        private long used;

        private MetadataBudget(long maximum) {
            this.maximum = maximum;
        }

        private void add(String value, int fieldMaximum, String field) {
            if (value == null) {
                return;
            }
            long bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > fieldMaximum) {
                throw new PayloadException(HttpStatus.BAD_REQUEST, field + " too long");
            }
            used += bytes;
            if (used > maximum) {
                throw new PayloadException(HttpStatus.BAD_REQUEST, "Metadata too large");
            }
        }
    }

    private static final class DeliveryPayload {
        private String senderEmail;
        private String originalSenderEmail;
        private String subject;
        private String sourceType;
        private UUID jobId;
        private final List<byte[]> attachmentData = new ArrayList<>();
        private final List<String> attachmentNames = new ArrayList<>();
        private long totalAttachmentBytes;
    }

    private static final class PayloadException extends RuntimeException {
        private final HttpStatus status;

        private PayloadException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
