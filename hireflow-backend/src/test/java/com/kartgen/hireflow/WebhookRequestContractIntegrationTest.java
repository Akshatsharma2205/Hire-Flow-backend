package com.kartgen.hireflow;

import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial request-contract tests for the v2 webhook transport.
 *
 * <p>These exercise the real filter chain through MockMvc, but they always
 * supply exact raw bytes and exact Content-Type headers so the signature,
 * canonicalisation and parser boundaries are tested rather than bypassed.</p>
 */
class WebhookRequestContractIntegrationTest extends WebhookIntegrationTestSupport {

    @Test
    void anyQueryStringIsRejectedBeforeSignatureOrIngestion() throws Exception {
        var hook = hook("query");
        byte[] body = jsonDelivery("hr@agency.example", null, "query", "resume.pdf", smallPdf());

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-query"), body)
                        .queryParam("senderEmail", "attacker@example.test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Query parameters are not allowed for webhook deliveries"));

        assertNoWrites(hook);
    }

    @Test
    void legacyV1CanonicalSignatureIsRejectedWithNoDowngradePath() throws Exception {
        var hook = hook("v1");
        byte[] body = jsonDelivery("hr@agency.example", null, "v1 downgrade", "resume.pdf", smallPdf());
        long timestamp = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2a-v1");
        String digest = WebhookTestSupport.sha256Hex(body);

        String v1Canonical = String.join("\n", "v1", hook.orgId().toString(),
                hook.credential().keyId(), String.valueOf(timestamp), nonce, "POST",
                WEBHOOK_PATH, idem, digest);
        String v1Signature = WebhookTestSupport.hmacSha256Hex(hook.credential().secret(), v1Canonical);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-HireFlow-Org-Id", hook.orgId())
                        .header("X-HireFlow-Key-Id", hook.credential().keyId())
                        .header("X-HireFlow-Timestamp", String.valueOf(timestamp))
                        .header("X-HireFlow-Nonce", nonce)
                        .header("X-HireFlow-Signature", v1Signature)
                        .header("X-HireFlow-Signature-Version", "v1")
                        .header("Idempotency-Key", idem))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertNoWrites(hook);
    }

    @Test
    void signatureIsBoundToTheMultipartBoundary() throws Exception {
        var hook = hook("ct-bind");
        String actualBoundary = "j2bind" + UUID.randomUUID().toString().replace("-", "");
        byte[] raw = WebhookTestSupport.multipartBody(actualBoundary,
                multipartDelivery("hr@agency.example", "boundary binding", "resume.pdf", smallPdf()));

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-bind"),
                        WebhookTestSupport.rawMultipartContentType(actualBoundary),
                        WebhookTestSupport.canonicalMultipartContentType(actualBoundary + "-different"),
                        raw))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertNoWrites(hook);
    }

    @Test
    void rawUnquotedBoundaryIsNotTheCanonicalSignedValue() throws Exception {
        var hook = hook("unquoted");
        String boundary = "j2unquoted" + UUID.randomUUID().toString().replace("-", "");
        byte[] raw = WebhookTestSupport.multipartBody(boundary,
                multipartDelivery("hr@agency.example", "unquoted", "resume.pdf", smallPdf()));

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-unquoted"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        raw))
                .andExpect(status().isUnauthorized());

        assertNoWrites(hook);
    }

    @Test
    void missingContentTypeIsRejected() throws Exception {
        byte[] body = jsonDelivery("hr@agency.example", null, "no ct", "resume.pdf", smallPdf());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .content(body)
                        .header("X-HireFlow-Org-Id", ORG_ID)
                        .header("X-HireFlow-Key-Id", "whk_0000000000000000")
                        .header("X-HireFlow-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .header("X-HireFlow-Nonce", WebhookTestSupport.randomNonce())
                        .header("X-HireFlow-Signature", "0".repeat(64))
                        .header("Idempotency-Key", WebhookTestSupport.randomIdempotencyKey("j2a-noct")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void unsupportedContentTypeIsRejected() throws Exception {
        byte[] body = jsonDelivery("hr@agency.example", null, "text", "resume.pdf", smallPdf());

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(body)
                        .header("X-HireFlow-Org-Id", ORG_ID))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void jsonCharsetParameterIsNormalisedOutOfTheSignedContentType() throws Exception {
        var hook = hook("json-charset");
        byte[] body = jsonDelivery("hr@agency.example", null, "charset", "resume.pdf", smallPdf());

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-charset"),
                        "application/json; charset=UTF-8",
                        WebhookTestSupport.canonicalJsonContentType(),
                        body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void truncatedMultipartBodyIsRejectedAfterSignatureVerification() throws Exception {
        var hook = hook("truncated");
        String boundary = "j2trunc" + UUID.randomUUID().toString().replace("-", "");
        byte[] valid = WebhookTestSupport.multipartBody(boundary,
                multipartDelivery("hr@agency.example", "truncated", "resume.pdf", smallPdf()));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 5);

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-truncated"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.canonicalMultipartContentType(boundary),
                        truncated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed multipart payload"));

        assertNoWrites(hook);
    }

    @Test
    void unknownMetadataFieldIsRejected() throws Exception {
        var hook = hook("unknown");
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        parts.add(new WebhookTestSupport.FormPart("evilField", null, null,
                "ignored?".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        parts.add(new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", smallPdf()));
        String boundary = newBoundary("unknown");

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-unknown"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.canonicalMultipartContentType(boundary),
                        WebhookTestSupport.multipartBody(boundary, parts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown multipart field"));

        assertNoWrites(hook);
    }

    @Test
    void duplicateMetadataFieldIsRejected() throws Exception {
        var hook = hook("duplicate");
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        parts.add(new WebhookTestSupport.FormPart("senderEmail", null, null,
                "first@example.test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        parts.add(new WebhookTestSupport.FormPart("senderEmail", null, null,
                "second@example.test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        parts.add(new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", smallPdf()));
        String boundary = newBoundary("duplicate");

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-duplicate"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.canonicalMultipartContentType(boundary),
                        WebhookTestSupport.multipartBody(boundary, parts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Duplicate multipart field"));

        assertNoWrites(hook);
    }

    @Test
    void multipartPartWithoutANameIsRejected() throws Exception {
        var hook = hook("noname");
        String boundary = newBoundary("noname");
        List<WebhookTestSupport.RawPart> parts = List.of(
                new WebhookTestSupport.RawPart(
                        "Content-Disposition: form-data; filename=\"resume.pdf\"",
                        smallPdf()),
                new WebhookTestSupport.RawPart(
                        "Content-Disposition: form-data; name=\"files\"; filename=\"resume.pdf\"",
                        new byte[]{1, 2, 3}));
        byte[] raw = WebhookTestSupport.multipartBodyFromRawParts(boundary, parts);

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-noname"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.canonicalMultipartContentType(boundary),
                        raw))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed multipart payload"));

        assertNoWrites(hook);
    }

    @Test
    void metadataFieldSentAsAFileIsRejected() throws Exception {
        var hook = hook("metadata-file");
        List<WebhookTestSupport.FormPart> parts = List.of(
                new WebhookTestSupport.FormPart("senderEmail", "sender.txt", "text/plain",
                        "hr@agency.example".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf", smallPdf()));
        String boundary = newBoundary("metadata-file");

        mockMvc.perform(signedRaw(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2a-metafile"),
                        WebhookTestSupport.rawMultipartContentType(boundary),
                        WebhookTestSupport.canonicalMultipartContentType(boundary),
                        WebhookTestSupport.multipartBody(boundary, parts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Metadata field must not be a file"));

        assertNoWrites(hook);
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

    private void assertNoWrites(Hook hook) {
        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isZero();
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
        assertThat(attachmentRepo.countByOrganizationId(hook.orgId())).isZero();
        assertThat(jobRepo.findByOrganizationIdAndStatus(hook.orgId(), "PENDING")).isEmpty();
    }

    private static String newBoundary(String prefix) {
        return "j2" + prefix.replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
