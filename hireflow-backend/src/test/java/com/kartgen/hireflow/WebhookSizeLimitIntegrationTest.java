package com.kartgen.hireflow;

import com.kartgen.hireflow.support.TestResumePdf;
import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-2 request-size enforcement, exercised with deliberately small limits
 * (8 KB body, 1.5 KB per attachment, 2 KB aggregate, 2 attachments) so every
 * boundary is hit through the real filter chain without allocating large
 * payloads.
 */
@SpringBootTest(properties = {
        "hireflow.webhook.max-body-bytes=8192",
        "hireflow.webhook.max-attachment-bytes=1500",
        "hireflow.webhook.max-total-attachment-bytes=2000",
        "hireflow.webhook.max-attachments=2",
        "hireflow.webhook.max-parts=12",
        "hireflow.webhook.max-metadata-bytes=2048"
})
@org.springframework.test.context.ActiveProfiles("test")
class WebhookSizeLimitIntegrationTest extends WebhookIntegrationTestSupport {

    private static final long MAX_BODY = 8192;
    private static final long MAX_ATTACHMENT = 1500;
    private static final long MAX_TOTAL_ATTACHMENTS = 2000;
    private static final int MAX_ATTACHMENTS = 2;
    private static final int MAX_PARTS = 12;
    private static final int MAX_METADATA = 2048;

    @Test
    void oversizedJsonBodyIsRejected() throws Exception {
        var hook = hook("size-json");
        byte[] tooLarge = jsonWithFiller(hook, new byte[0], 0, MAX_BODY + 1);

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-1"), tooLarge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Webhook payload too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void oversizedMultipartBodyIsRejected() throws Exception {
        var hook = hook("size-mp");
        byte[] bigPdf = pdfAtLeast(8000); // raw multipart body exceeds the 8192-byte body cap
        List<WebhookTestSupport.FormPart> parts = multipartDelivery(
                "hr@agency.example", "too big", "resume.pdf", bigPdf);

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-2"), parts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Webhook payload too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void oversizedMultipartAttachmentIsRejected() throws Exception {
        var hook = hook("size-part");
        byte[] bigPdf = pdfAtLeast(1600); // part exceeds max-attachment-bytes, body stays under 4096
        List<WebhookTestSupport.FormPart> parts = multipartDelivery(
                "hr@agency.example", "big part", "resume.pdf", bigPdf);

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-3"), parts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Attachment too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void excessiveAttachmentCountIsRejected() throws Exception {
        var hook = hook("size-count");
        // Count is enforced before content/aggregate checks, so tiny placeholder
        // parts isolate the attachment-count limit from the size limits.
        byte[] pdf = new byte[]{1, 2, 3};
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        parts.add(new WebhookTestSupport.FormPart("senderEmail", null, null, "hr@agency.example".getBytes()));
        for (int i = 0; i <= MAX_ATTACHMENTS; i++) { // 3 files > limit of 2
            parts.add(new WebhookTestSupport.FormPart("files", "resume-" + i + ".pdf",
                    "application/pdf", pdf));
        }

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-4"), parts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Too many attachments"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void oversizedDecodedBase64AttachmentIsRejected() throws Exception {
        var hook = hook("size-b64");
        byte[] decoded = new byte[(int) MAX_ATTACHMENT + 1]; // 1501 bytes > limit
        new java.security.SecureRandom().nextBytes(decoded);
        byte[] body = jsonDelivery("hr@agency.example", null, "b64 oversize", "blob.bin", decoded);

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-5"), body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Attachment too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void oversizedBase64EncodedPayloadIsRejectedBeforeDecoding() throws Exception {
        var hook = hook("size-b64raw");
        // 3000 base64 chars expand beyond the 2004-char guard for a 1500-byte
        // decoded maximum. The body itself remains under the raw-body cap.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderEmail", "hr@agency.example");
        body.put("attachments", List.of(Map.of(
                "filename", "huge.b64",
                "data", "A".repeat(3000))));
        byte[] raw = objectMapper.writeValueAsBytes(body);
        assertThat(raw.length).isLessThan((int) MAX_BODY);

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-b64raw"), raw))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Attachment too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void aggregateDecodedAttachmentSizeIsRejectedEvenWhenEachPartIsValid() throws Exception {
        var hook = hook("size-total");
        byte[] first = new byte[1100];
        byte[] second = new byte[1100];
        new java.security.SecureRandom().nextBytes(first);
        new java.security.SecureRandom().nextBytes(second);
        assertThat(first.length).isLessThan((int) MAX_ATTACHMENT);
        assertThat(second.length).isLessThan((int) MAX_ATTACHMENT);
        assertThat((long) first.length + second.length).isGreaterThan(MAX_TOTAL_ATTACHMENTS);

        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        parts.add(new WebhookTestSupport.FormPart("senderEmail", null, null,
                "hr@agency.example".getBytes(StandardCharsets.UTF_8)));
        parts.add(new WebhookTestSupport.FormPart("files", "first.pdf", "application/pdf", first));
        parts.add(new WebhookTestSupport.FormPart("files", "second.pdf", "application/pdf", second));

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-total"), parts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Total attachment size too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void excessiveMultipartPartCountIsRejected() throws Exception {
        var hook = hook("size-parts");
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        for (int i = 0; i < MAX_PARTS + 1; i++) {
            parts.add(new WebhookTestSupport.FormPart("files", "part-" + i + ".pdf",
                    "application/pdf", new byte[]{1}));
        }

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-parts"), parts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Webhook payload too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void oversizedMetadataIsRejectedWithBadRequestAndNoTruncation() throws Exception {
        var hook = hook("size-metadata");
        String subject = "s".repeat(MAX_METADATA + 1);
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        parts.add(new WebhookTestSupport.FormPart("subject", null, null,
                subject.getBytes(StandardCharsets.UTF_8)));
        parts.add(new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", smallPdf()));

        mockMvc.perform(performSignedMultipart(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-metadata"), parts))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metadata too large"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void validRequestAtBodyBoundaryRemainsAccepted() throws Exception {
        var hook = hook("size-boundary");
        byte[] pdf = smallPdf();
        byte[] exact = jsonWithFiller(hook, pdf, 1, MAX_BODY); // body is exactly 4096 bytes

        assertThat(exact.length).isEqualTo(MAX_BODY);
        MvcResult result = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-6"), exact))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andReturn();

        UUID messageId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("inboundMessageId").asText());
        assertThat(attachmentRepo.findByMessageId(messageId)).hasSize(1);
    }

    @Test
    void validRequestAtAttachmentBoundaryRemainsAccepted() throws Exception {
        var hook = hook("size-attboundary");
        byte[] exactlyAtLimit = new byte[(int) MAX_ATTACHMENT]; // 1500 bytes, at the boundary
        new java.security.SecureRandom().nextBytes(exactlyAtLimit);
        byte[] body = jsonDelivery("hr@agency.example", null, "at boundary", "blob.bin", exactlyAtLimit);

        // Non-resume payload is queued through the delivery but skipped by the
        // resume gatekeeper - the delivery itself must be accepted (202).
        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-size-7"), body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(0));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Hook(WebhookOrg org, Credential credential) {
        UUID orgId() { return org.org().getId(); }
    }

    private Hook hook(String prefix) throws Exception {
        WebhookOrg org = newWebhookOrg(prefix);
        Credential credential = provisionCredential(org);
        return new Hook(org, credential);
    }

    /** JSON delivery body padded with an inert filler field to an exact byte size. */
    private byte[] jsonWithFiller(Hook hook, byte[] attachment, int attachmentCount, long targetSize) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("senderEmail", "hr@agency.example");
            body.put("subject", "boundary");
            if (attachmentCount > 0) {
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("filename", "resume.pdf");
                att.put("data", Base64.getEncoder().encodeToString(attachment));
                body.put("attachments", List.of(att));
            } else {
                body.put("attachments", List.of());
            }
            body.put("filler", "");
            byte[] base = objectMapper.writeValueAsBytes(body);
            // The filler value grows from "" to N chars, adding exactly N bytes.
            long padding = targetSize - base.length;
            if (padding < 0) {
                throw new IllegalStateException("Base body already exceeds the configured boundary");
            }
            body.put("filler", "x".repeat((int) padding));
            byte[] exact = objectMapper.writeValueAsBytes(body);
            assertThat(exact.length).as("padded body size").isEqualTo(targetSize);
            return exact;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** PDF padded with fast-growing pseudo-random content to reach at least minBytes. */
    private byte[] pdfAtLeast(int minBytes) {
        StringBuilder extra = new StringBuilder();
        byte[] pdf = TestResumePdf.create(TestResumePdf.resume(
                "J2 Size", "size." + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                "Pune", 3, "Jan 2026"));
        while (pdf.length < minBytes) {
            int before = pdf.length;
            for (int i = 0; i < 12; i++) {
                extra.append(UUID.randomUUID()).append('\n');
            }
            pdf = TestResumePdf.create(TestResumePdf.resume(
                    "J2 Size", "size." + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                    "Pune", 3, "Jan 2026" + extra));
            if (pdf.length <= before) {
                throw new IllegalStateException("Unable to grow test PDF to the requested size");
            }
        }
        return pdf;
    }
}
