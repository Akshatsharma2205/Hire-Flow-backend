package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.mail.model.InboundMessage;
import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.webhook.WebhookCredential;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-2 webhook security: HMAC authentication, per-organization credentials,
 * timestamp/nonce validation, durable replay protection and idempotency -
 * exercised through the real filter chain and real endpoints.
 */
class WebhookAuthIntegrationTest extends WebhookIntegrationTestSupport {

    // ── Authentication ──────────────────────────────────────────────────────

    @Test
    void missingSignatureIsRejected() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("X-HireFlow-Org-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing webhook authentication"));
    }

    @Test
    void legacyGlobalSecretIsRetired() throws Exception {
        // The old shared-secret model must not authenticate anything.
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("X-HireFlow-Webhook-Secret", "hireflow-local-webhook-secret")
                        .header("X-HireFlow-Org-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing webhook authentication"));
    }

    @Test
    void incorrectSignatureIsRejected() throws Exception {
        var hook = hook();
        byte[] body = jsonDelivery("hr@agency.example", null, "bad signature", "r.pdf", smallPdf());
        String digest = WebhookTestSupport.sha256Hex(body);
        String forgedSignature = "0".repeat(64);
        long ts = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-badsig");

        long before = messageRepo.countByOrganizationId(hook.orgId());
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-HireFlow-Org-Id", hook.orgId())
                        .header("X-HireFlow-Key-Id", hook.credential().keyId())
                        .header("X-HireFlow-Timestamp", String.valueOf(ts))
                        .header("X-HireFlow-Nonce", nonce)
                        .header("X-HireFlow-Signature", forgedSignature)
                        .header("Idempotency-Key", idem))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(before);
        assertThat(deliveryRepo.findByOrganizationIdAndIdempotencyKey(hook.orgId(), idem)).isEmpty();
    }

    @Test
    void modifiedBodyWithOriginalSignatureIsRejected() throws Exception {
        var hook = hook();
        byte[] original = jsonDelivery("hr@agency.example", null, "original body", "r.pdf", smallPdf());
        byte[] tampered = jsonDelivery("hr@agency.example", null, "TAMPERED body", "r.pdf", smallPdf());
        long ts = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-tamper");
        // Signature computed over the original bytes only.
        var builder = signedJson(hook.credential().secret(), hook.orgId().toString(),
                hook.credential().keyId(), ts, nonce, idem, original)
                .content(tampered); // body swapped after signing

        long before = messageRepo.countByOrganizationId(hook.orgId());
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(before);
    }

    @Test
    void wrongOrganizationKeyCombinationIsRejected() throws Exception {
        var orgA = hook("orga");
        var orgB = hook("orgb"); // independent organization with its own credential
        byte[] body = jsonDelivery("hr@agency.example", null, "cross tenant", "r.pdf", smallPdf());

        long beforeA = messageRepo.countByOrganizationId(orgA.organization().getId());
        long beforeB = messageRepo.countByOrganizationId(orgB.organization().getId());

        mockMvc.perform(signedJson(orgA.credential().secret(), orgB.orgId().toString(),
                        orgA.credential().keyId(), Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-cross"), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertThat(messageRepo.countByOrganizationId(orgA.organization().getId())).isEqualTo(beforeA);
        assertThat(messageRepo.countByOrganizationId(orgB.organization().getId())).isEqualTo(beforeB);
        assertThat(deliveryRepo.countByOrganizationId(orgB.organization().getId())).isZero();
    }

    @Test
    void revokedCredentialIsRejected() throws Exception {
        var hook = hook("revoke");
        byte[] body = jsonDelivery("hr@agency.example", null, "revoked", "r.pdf", smallPdf());

        mockMvc.perform(post("/api/v1/webhook-credentials/" + hook.credential().keyId() + "/revoke")
                        .header("Authorization", "Bearer " + hook.org().jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-revoked"), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook authentication"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void suspendedOrganizationIsRejected() throws Exception {
        var hook = hook("suspend");
        hook.organization().setStatus("SUSPENDED");
        orgRepo.save(hook.organization());
        byte[] body = jsonDelivery("hr@agency.example", null, "suspended", "r.pdf", smallPdf());

        // Valid signature, but the organization is not ACTIVE -> 403, nothing ingested.
        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-suspend"), body))
                .andExpect(status().isForbidden());

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void expiredTimestampIsRejected() throws Exception {
        var hook = hook("expired");
        byte[] body = jsonDelivery("hr@agency.example", null, "expired", "r.pdf", smallPdf());
        long stale = Instant.now().getEpochSecond() - 301;

        mockMvc.perform(signedJson(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), stale, WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-expired"), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Webhook timestamp outside allowed window"));
    }

    @Test
    void futureTimestampBeyondClockSkewIsRejected() throws Exception {
        var hook = hook("future");
        byte[] body = jsonDelivery("hr@agency.example", null, "future", "r.pdf", smallPdf());
        long future = Instant.now().getEpochSecond() + 301;

        mockMvc.perform(signedJson(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), future, WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-future"), body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Webhook timestamp outside allowed window"));
    }

    @Test
    void malformedTimestampIsRejected() throws Exception {
        var hook = hook("badts");
        byte[] body = jsonDelivery("hr@agency.example", null, "bad ts", "r.pdf", smallPdf());

        mockMvc.perform(signedJson(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), 0L, WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-badts"), body)
                        .header("X-HireFlow-Timestamp", "not-a-number"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedNonceIsRejected() throws Exception {
        var hook = hook("badnonce");
        byte[] body = jsonDelivery("hr@agency.example", null, "bad nonce", "r.pdf", smallPdf());

        mockMvc.perform(signedJson(hook.credential().secret(), hook.orgId().toString(),
                        hook.credential().keyId(), Instant.now().getEpochSecond(), "short",
                        WebhookTestSupport.randomIdempotencyKey("j2-badnonce"), body)
                        .header("X-HireFlow-Nonce", "short"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        var hook = hook("noidem");
        byte[] body = jsonDelivery("hr@agency.example", null, "no idem", "r.pdf", smallPdf());
        long ts = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String digest = WebhookTestSupport.sha256Hex(body);
        String signature = WebhookTestSupport.hmacSha256Hex(hook.credential().secret(),
                WebhookTestSupport.canonicalString(hook.orgId().toString(),
                        hook.credential().keyId(), String.valueOf(ts), nonce, "POST", WEBHOOK_PATH,
                        "", WebhookTestSupport.canonicalJsonContentType(), digest));

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-HireFlow-Org-Id", hook.orgId())
                        .header("X-HireFlow-Key-Id", hook.credential().keyId())
                        .header("X-HireFlow-Timestamp", String.valueOf(ts))
                        .header("X-HireFlow-Nonce", nonce)
                        .header("X-HireFlow-Signature", signature))
                .andExpect(status().isBadRequest());

        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    // ── Valid deliveries ────────────────────────────────────────────────────

    @Test
    void validSignedJsonDeliverySucceeds() throws Exception {
        var hook = hook("json-ok");
        byte[] body = jsonDelivery("hr@agency.example", null, "json delivery", "resume.pdf", smallPdf());
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-json");

        long beforeMessages = messageRepo.countByOrganizationId(hook.orgId());
        MvcResult result = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.deliveryId").isNotEmpty())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID messageId = UUID.fromString(response.path("inboundMessageId").asText());
        var delivery = deliveryRepo.findByOrganizationIdAndIdempotencyKey(hook.orgId(), idem).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("ACCEPTED");
        assertThat(delivery.getInboundMessageId()).isEqualTo(messageId);
        assertThat(delivery.getBodySha256()).isEqualTo(WebhookTestSupport.sha256Hex(body));
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(beforeMessages + 1);
        assertThat(attachmentRepo.findByMessageId(messageId)).hasSize(1);
    }

    @Test
    void validSignedMultipartDeliverySucceeds() throws Exception {
        var hook = hook("mp-ok");
        var parts = multipartDelivery("hr@agency.example", "multipart delivery", "resume.pdf", smallPdf());
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-mp");

        long beforeMessages = messageRepo.countByOrganizationId(hook.orgId());
        MvcResult result = mockMvc.perform(performSignedMultipart(hook.credential(),
                        hook.orgId().toString(), WebhookTestSupport.randomNonce(), idem, parts))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID messageId = UUID.fromString(response.path("inboundMessageId").asText());
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(beforeMessages + 1);
        assertThat(attachmentRepo.findByMessageId(messageId)).hasSize(1);
        var delivery = deliveryRepo.findByOrganizationIdAndIdempotencyKey(hook.orgId(), idem).orElseThrow();
        assertThat(delivery.getStatus()).isEqualTo("ACCEPTED");
    }

    // ── Replay and idempotency ──────────────────────────────────────────────

    @Test
    void replayingSameNonceCreatesNoDuplicateProcessing() throws Exception {
        var hook = hook("replay-nonce");
        byte[] body = jsonDelivery("hr@agency.example", null, "exact replay", "resume.pdf", smallPdf());
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-exact");

        MvcResult first = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        nonce, idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        String deliveryId = readJson(first, "deliveryId");
        UUID messageId = UUID.fromString(readJson(first, "inboundMessageId"));

        // Exact same request bytes, timestamp, nonce and idempotency key.
        MvcResult second = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        nonce, idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.deliveryId").value(deliveryId))
                .andExpect(jsonPath("$.inboundMessageId").value(messageId.toString()))
                .andReturn();

        assertThat(readJson(second, "attachmentIds")).isEqualTo(readJson(first, "attachmentIds"));
        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(attachmentRepo.findByMessageId(messageId)).hasSize(1);
    }

    @Test
    void sameIdempotencyKeySamePayloadReturnsOriginalResult() throws Exception {
        var hook = hook("idem-same");
        byte[] body = jsonDelivery("hr@agency.example", null, "idempotent retry", "resume.pdf", smallPdf());
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-idem");

        MvcResult first = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        String deliveryId = readJson(first, "deliveryId");

        // Fresh nonce + fresh timestamp, identical payload and idempotency key.
        MvcResult retry = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.deliveryId").value(deliveryId))
                .andReturn();

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(readJson(retry, "attachmentIds")).isEqualTo(readJson(first, "attachmentIds"));
    }

    @Test
    void sameIdempotencyKeyDifferentPayloadConflict() throws Exception {
        var hook = hook("idem-conflict");
        byte[] firstBody = jsonDelivery("hr@agency.example", null, "payload one", "resume.pdf", smallPdf());
        byte[] secondBody = jsonDelivery("hr@agency.example", null, "payload TWO", "resume.pdf", smallPdf());
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-conflict");

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, firstBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency key already used with a different payload"));

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
    }

    @Test
    void sameNonceDifferentIdempotencyKeyIsRejected() throws Exception {
        var hook = hook("nonce-mismatch");
        byte[] firstBody = jsonDelivery("hr@agency.example", null, "nonce owner", "resume.pdf", smallPdf());
        byte[] secondBody = jsonDelivery("hr@agency.example", null, "nonce thief", "resume.pdf", smallPdf());
        String nonce = WebhookTestSupport.randomNonce();
        String firstIdem = WebhookTestSupport.randomIdempotencyKey("j2-nonce-a");

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        nonce, firstIdem, firstBody))
                .andExpect(status().isAccepted());

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        nonce, WebhookTestSupport.randomIdempotencyKey("j2-nonce-b"), secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Webhook replay detected"));

        assertThat(deliveryRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
    }

    // ── Credential lifecycle ────────────────────────────────────────────────

    @Test
    void viewerAndRecruiterCannotProvisionCredentials() throws Exception {
        var hook = hook("rbac");
        String viewerJwt = membershipJwt(hook.org(), "VIEWER", "viewer");
        String recruiterJwt = membershipJwt(hook.org(), "RECRUITER", "recruiter");

        mockMvc.perform(post("/api/v1/webhook-credentials").header("Authorization", "Bearer " + viewerJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/webhook-credentials").header("Authorization", "Bearer " + recruiterJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/webhook-credentials/"
                        + hook.credential().keyId() + "/rotate").header("Authorization", "Bearer " + viewerJwt))
                .andExpect(status().isForbidden());
        assertThat(credentialRepo.findByOrganizationIdOrderByCreatedAtDesc(hook.orgId())).hasSize(1);
    }

    @Test
    void adminCanProvisionAndListNeverReturnsSecrets() throws Exception {
        var hook = hook("admin");
        String adminJwt = membershipJwt(hook.org(), "ADMIN", "admin");

        MvcResult created = mockMvc.perform(post("/api/v1/webhook-credentials")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn();
        String secret = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("secret").asText();

        MvcResult list = mockMvc.perform(get("/api/v1/webhook-credentials")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andReturn();
        String listBody = list.getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(secret);
        assertThat(listBody).contains("whk_");

        // The stored value must never be the raw secret.
        WebhookCredential stored = credentialRepo.findByOrganizationIdOrderByCreatedAtDesc(hook.orgId())
                .get(0);
        assertThat(stored.getEncryptedSecret()).isNotEqualTo(secret);
        assertThat(stored.getEncryptedSecret()).doesNotContain(secret);
    }

    @Test
    void rotateRevokesOldCredentialAndIssuesNewSecret() throws Exception {
        var hook = hook("rotate");
        byte[] body = jsonDelivery("hr@agency.example", null, "rotation", "resume.pdf", smallPdf());
        String oldKey = hook.credential().keyId();

        MvcResult rotated = mockMvc.perform(post("/api/v1/webhook-credentials/" + oldKey + "/rotate")
                        .header("Authorization", "Bearer " + hook.org().jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn();
        JsonNode newCred = objectMapper.readTree(rotated.getResponse().getContentAsString());

        WebhookCredential oldRow = credentialRepo.findByKeyId(oldKey).orElseThrow();
        assertThat(oldRow.getStatus()).isEqualTo("REVOKED");
        assertThat(oldRow.getRevokedAt()).isNotNull();

        // Old key: rejected. New key: accepted.
        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-rot-old"), body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(performSignedJson(
                        new Credential(newCred.path("keyId").asText(), newCred.path("secret").asText()),
                        hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-rot-new"), body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void corruptStoredSecretFailsClosed() throws Exception {
        var hook = hook("corrupt");
        WebhookCredential credential = credentialRepo.findByKeyId(hook.credential().keyId()).orElseThrow();
        credential.setEncryptedSecret("bm90LXJlYWxseS1lbmNyeXB0ZWQ=");
        credentialRepo.save(credential);

        byte[] body = jsonDelivery("hr@agency.example", null, "corrupt key", "resume.pdf", smallPdf());
        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-corrupt"), body))
                .andExpect(status().isServiceUnavailable());

        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isZero();
    }

    @Test
    void webhookHeadersGrantNoInteractiveAccess() throws Exception {
        var hook = hook("noescalate");
        // Webhook credentials are scoped to /api/v1/mail-webhook/*; the same
        // headers presented to an interactive endpoint must authenticate nothing.
        mockMvc.perform(post("/api/v1/jobs")
                        .header("X-HireFlow-Org-Id", hook.orgId())
                        .header("X-HireFlow-Key-Id", hook.credential().keyId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Escalation attempt\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private record Hook(WebhookOrg org, Credential credential) {
        UUID orgId() { return org.org().getId(); }
        Organization organization() { return org.org(); }
    }

    private Hook hook() throws Exception {
        return hook("base");
    }

    private Hook hook(String prefix) throws Exception {
        WebhookOrg org = newWebhookOrg(prefix);
        Credential credential = provisionCredential(org);
        return new Hook(org, credential);
    }

    private String readJson(MvcResult result, String field) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode value = body.path(field);
        return value.isArray() ? value.toString() : value.asText();
    }
}
