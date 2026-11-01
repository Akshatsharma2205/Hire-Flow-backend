package com.kartgen.hireflow.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.mail.model.InboundAttachmentRepository;
import com.kartgen.hireflow.mail.model.InboundMessageRepository;
import com.kartgen.hireflow.ops.ProcessingJobRepository;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUser;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.User;
import com.kartgen.hireflow.tenant.UserRepository;
import com.kartgen.hireflow.webhook.WebhookCredentialRepository;
import com.kartgen.hireflow.webhook.WebhookDeliveryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared fixtures for J-2 webhook security tests: synthetic organizations,
 * credential provisioning through the real API, and independently signed
 * JSON/multipart webhook requests.
 */
public abstract class WebhookIntegrationTestSupport extends IntegrationTestSupport {

    protected static final String WEBHOOK_PATH = "/api/v1/mail-webhook/inbound";

    @Autowired protected WebhookCredentialRepository credentialRepo;
    @Autowired protected WebhookDeliveryRepository deliveryRepo;
    @Autowired protected OrganizationRepository orgRepo;
    @Autowired protected OrganizationUserRepository orgUserRepo;
    @Autowired protected UserRepository userRepo;
    @Autowired protected InboundMessageRepository messageRepo;
    @Autowired protected InboundAttachmentRepository attachmentRepo;
    @Autowired protected ProcessingJobRepository jobRepo;
    @Autowired protected JwtService jwtService;

    public record WebhookOrg(Organization org, User owner, OrganizationUser membership, String jwt) {}

    public record Credential(String keyId, String secret) {}

    protected WebhookOrg newWebhookOrg(String prefix) {
        Organization org = orgRepo.save(Organization.builder()
                .name("J2 " + prefix)
                .slug("j2-" + prefix.toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());
        User owner = userRepo.save(User.builder()
                .email(prefix.toLowerCase(Locale.ROOT) + "-owner-"
                        + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .displayName("J2 Owner " + prefix)
                .passwordHash("unused-test-hash")
                .build());
        OrganizationUser membership = orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.getId())
                .userId(owner.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());
        String jwt = jwtService.generateToken(owner.getId(), owner.getEmail(), org.getId(), "OWNER");
        return new WebhookOrg(org, owner, membership, jwt);
    }

    /** Provisions a credential through the real OWNER/ADMIN API. */
    protected Credential provisionCredential(WebhookOrg org) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/webhook-credentials")
                        .header("Authorization", "Bearer " + org.jwt()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Credential(body.path("keyId").asText(), body.path("secret").asText());
    }

    protected String membershipJwt(WebhookOrg org, String role, String prefix) {
        User user = userRepo.save(User.builder()
                .email(prefix.toLowerCase(Locale.ROOT) + "-"
                        + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .displayName("J2 " + prefix)
                .passwordHash("unused-test-hash")
                .build());
        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(org.org().getId())
                .userId(user.getId())
                .role(role)
                .status("ACTIVE")
                .build());
        return jwtService.generateToken(user.getId(), user.getEmail(), org.org().getId(), role);
    }

    /** Builds a fresh signed JSON request builder (caller invokes perform per call). */
    protected MockHttpServletRequestBuilder signedJson(String secret, String orgId, String keyId,
                                                       long timestamp, String nonce, String idempotencyKey,
                                                       byte[] body) {
        String digest = WebhookTestSupport.sha256Hex(body);
        String signature = WebhookTestSupport.hmacSha256Hex(secret, WebhookTestSupport.canonicalString(
                orgId, keyId, String.valueOf(timestamp), nonce, "POST", WEBHOOK_PATH,
                idempotencyKey, WebhookTestSupport.canonicalJsonContentType(), digest));
        return post(WEBHOOK_PATH)
                .contentType("application/json")
                .content(body)
                .header("X-HireFlow-Org-Id", orgId)
                .header("X-HireFlow-Key-Id", keyId)
                .header("X-HireFlow-Timestamp", String.valueOf(timestamp))
                .header("X-HireFlow-Nonce", nonce)
                .header("X-HireFlow-Signature", signature)
                .header("Idempotency-Key", idempotencyKey);
    }

    protected MockHttpServletRequestBuilder performSignedJson(Credential cred, String orgId, String nonce,
                                                              String idempotencyKey, byte[] body) {
        return signedJson(cred.secret(), orgId, cred.keyId(),
                Instant.now().getEpochSecond(), nonce, idempotencyKey, body);
    }

    /**
     * Builds a signed request from caller-supplied raw bytes and Content-Type.
     * Used by adversarial tests that must control the exact header/body pair.
     */
    protected MockHttpServletRequestBuilder signedRaw(String secret, String orgId, String keyId,
                                                       long timestamp, String nonce,
                                                       String idempotencyKey,
                                                       String rawContentType,
                                                       String canonicalContentType,
                                                       byte[] body) {
        String digest = WebhookTestSupport.sha256Hex(body);
        String signature = WebhookTestSupport.hmacSha256Hex(secret, WebhookTestSupport.canonicalString(
                orgId, keyId, String.valueOf(timestamp), nonce, "POST", WEBHOOK_PATH,
                idempotencyKey, canonicalContentType, digest));
        return post(WEBHOOK_PATH)
                .contentType(rawContentType)
                .content(body)
                .header("X-HireFlow-Org-Id", orgId)
                .header("X-HireFlow-Key-Id", keyId)
                .header("X-HireFlow-Timestamp", String.valueOf(timestamp))
                .header("X-HireFlow-Nonce", nonce)
                .header("X-HireFlow-Signature", signature)
                .header("Idempotency-Key", idempotencyKey);
    }

    /** Builds a fresh signed multipart request builder; signature covers the raw encoded body. */
    protected MockMultipartHttpServletRequestBuilder signedMultipart(String secret, String orgId, String keyId,
                                                                     long timestamp, String nonce,
                                                                     String idempotencyKey,
                                                                     List<WebhookTestSupport.FormPart> parts) {
        String boundary = "j2test" + UUID.randomUUID().toString().replace("-", "");
        byte[] rawBody = WebhookTestSupport.multipartBody(boundary, parts);
        String digest = WebhookTestSupport.sha256Hex(rawBody);
        String signature = WebhookTestSupport.hmacSha256Hex(secret, WebhookTestSupport.canonicalString(
                orgId, keyId, String.valueOf(timestamp), nonce, "POST", WEBHOOK_PATH,
                idempotencyKey, WebhookTestSupport.canonicalMultipartContentType(boundary), digest));

        MockMultipartHttpServletRequestBuilder builder = multipart(WEBHOOK_PATH);
        for (WebhookTestSupport.FormPart part : parts) {
            if (part.filename() != null) {
                builder.file(new org.springframework.mock.web.MockMultipartFile(
                        part.name(), part.filename(), part.contentType(), part.content()));
            } else {
                // Form fields are exposed as request parameters; the raw body still
                // carries them as multipart parts for the signature digest.
                builder.param(part.name(), new String(part.content(), StandardCharsets.UTF_8));
            }
        }
        builder.contentType(WebhookTestSupport.rawMultipartContentType(boundary));
        builder.header("X-HireFlow-Org-Id", orgId);
        builder.header("X-HireFlow-Key-Id", keyId);
        builder.header("X-HireFlow-Timestamp", String.valueOf(timestamp));
        builder.header("X-HireFlow-Nonce", nonce);
        builder.header("X-HireFlow-Signature", signature);
        builder.header("Idempotency-Key", idempotencyKey);
        // Give the security filter the exact raw multipart bytes for digest verification.
        builder.with(request -> {
            request.setContent(rawBody);
            return request;
        });
        return builder;
    }

    protected MockMultipartHttpServletRequestBuilder performSignedMultipart(Credential cred, String orgId,
                                                                            String nonce, String idempotencyKey,
                                                                            List<WebhookTestSupport.FormPart> parts) {
        return signedMultipart(cred.secret(), orgId, cred.keyId(),
                Instant.now().getEpochSecond(), nonce, idempotencyKey, parts);
    }

    protected byte[] jsonDelivery(String senderEmail, String originalSenderEmail, String subject,
                                  String filename, byte[] content) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (senderEmail != null) body.put("senderEmail", senderEmail);
        if (originalSenderEmail != null) body.put("originalSenderEmail", originalSenderEmail);
        if (subject != null) body.put("subject", subject);
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("filename", filename);
        attachment.put("data", Base64.getEncoder().encodeToString(content));
        body.put("attachments", List.of(attachment));
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected List<WebhookTestSupport.FormPart> multipartDelivery(String senderEmail, String subject,
                                                                  String filename, byte[] content) {
        List<WebhookTestSupport.FormPart> parts = new ArrayList<>();
        if (senderEmail != null) {
            parts.add(new WebhookTestSupport.FormPart("senderEmail", null, null,
                    senderEmail.getBytes(StandardCharsets.UTF_8)));
        }
        if (subject != null) {
            parts.add(new WebhookTestSupport.FormPart("subject", null, null,
                    subject.getBytes(StandardCharsets.UTF_8)));
        }
        parts.add(new WebhookTestSupport.FormPart("files", filename, "application/pdf", content));
        return parts;
    }

    protected byte[] smallPdf() {
        return TestResumePdf.create(TestResumePdf.resume(
                "J2 Candidate", "j2." + UUID.randomUUID().toString().substring(0, 8)
                        + "@example.test", "Pune", 3, "Jan 2026"));
    }
}
