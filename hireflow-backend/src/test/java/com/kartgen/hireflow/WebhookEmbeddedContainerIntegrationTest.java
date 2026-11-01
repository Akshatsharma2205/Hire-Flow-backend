package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.document.ResumeFile;
import com.kartgen.hireflow.document.ResumeFileRepository;
import com.kartgen.hireflow.document.StoragePort;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import com.kartgen.hireflow.webhook.WebhookCredential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real embedded-container tests for the multipart webhook transport.
 *
 * <p>MockMvc short-circuits the servlet container's multipart plumbing. These
 * tests start the application on a random port and send exact raw HTTP bytes,
 * then verify binary preservation through the storage adapter and rejection of
 * truncated multipart bodies. No mocks are used for the request path.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hireflow.worker.poll-ms=3600000")
@ActiveProfiles("test")
class WebhookEmbeddedContainerIntegrationTest extends WebhookIntegrationTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private HireFlowProperties props;

    @Autowired
    private ResumeFileRepository resumeFileRepo;

    @Autowired
    private StoragePort storagePort;

    @Test
    void quotedBoundaryBinaryMultipartIsAcceptedAndStoredByteForByte() throws Exception {
        WebhookOrg org = newWebhookOrg("container");
        Credential credential = createCredential(org);
        String boundary = "j2container" + UUID.randomUUID().toString().replace("-", "");
        String quotedContentType = "multipart/form-data; boundary=\"" + boundary + "\"";

        byte[] originalPdf = smallPdf();
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        source.write(originalPdf);
        source.write(("\r\n--" + boundary + "-not-a-delimiter\r\n").getBytes(StandardCharsets.US_ASCII));
        source.write(new byte[]{0x00, (byte) 0xFF, 0x00, '\r', '\n', 0x01});
        byte[] binaryPayload = source.toByteArray();

        byte[] raw = WebhookTestSupport.multipartBody(boundary, List.of(
                new WebhookTestSupport.FormPart("senderEmail", null, null,
                        "hr@agency.example".getBytes(StandardCharsets.UTF_8)),
                new WebhookTestSupport.FormPart("subject", null, null,
                        "container binary".getBytes(StandardCharsets.UTF_8)),
                new WebhookTestSupport.FormPart("files", "resume.pdf", "application/pdf",
                        binaryPayload)));

        HttpResponse<String> response = send(org, credential, quotedContentType,
                WebhookTestSupport.canonicalMultipartContentType(boundary), raw,
                WebhookTestSupport.randomIdempotencyKey("j2a-container"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        JsonNode result = objectMapper.readTree(response.body());
        assertThat(result.path("status").asText()).isEqualTo("QUEUED");
        assertThat(result.path("acceptedCount").asInt()).isEqualTo(1);

        UUID messageId = UUID.fromString(result.path("inboundMessageId").asText());
        List<InboundAttachment> attachments = attachmentRepo.findByMessageId(messageId);
        assertThat(attachments).hasSize(1);
        InboundAttachment attachment = attachments.get(0);
        assertThat(attachment.getOriginalFilename()).isEqualTo("resume.pdf");
        assertThat(attachment.getSizeBytes()).isEqualTo(binaryPayload.length);
        assertThat(attachment.getSha256()).isEqualTo(WebhookTestSupport.sha256Hex(binaryPayload));

        ResumeFile resumeFile = resumeFileRepo
                .findByIdAndOrganizationId(attachment.getResumeFileId(), org.org().getId())
                .orElseThrow();
        assertThat(storagePort.retrieve(resumeFile.getS3ObjectKey()))
                .as("stored attachment bytes must equal the exact signed multipart part bytes")
                .isEqualTo(binaryPayload);
    }

    @Test
    void truncatedMultipartIsRejectedByTheRealContainerWithNoDatabaseWrites() throws Exception {
        WebhookOrg org = newWebhookOrg("container-truncated");
        Credential credential = createCredential(org);
        String boundary = "j2trunc" + UUID.randomUUID().toString().replace("-", "");
        String contentType = "multipart/form-data; boundary=\"" + boundary + "\"";

        byte[] valid = WebhookTestSupport.multipartBody(boundary,
                multipartDelivery("hr@agency.example", "truncated", "resume.pdf", smallPdf()));
        // Remove the final "--" and CRLF, leaving an unterminated closing boundary.
        byte[] truncated = Arrays.copyOf(valid, valid.length - 4);

        HttpResponse<String> response = send(org, credential, contentType,
                WebhookTestSupport.canonicalMultipartContentType(boundary), truncated,
                WebhookTestSupport.randomIdempotencyKey("j2a-container-truncated"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("Malformed multipart payload");
        assertThat(messageRepo.countByOrganizationId(org.org().getId())).isZero();
        assertThat(attachmentRepo.countByOrganizationId(org.org().getId())).isZero();
        assertThat(deliveryRepo.countByOrganizationId(org.org().getId())).isZero();
    }

    @Test
    void duplicateContentTypeHeadersAreRejectedByTheRealContainer() throws Exception {
        WebhookOrg org = newWebhookOrg("container-dupct");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String head = "POST " + WEBHOOK_PATH + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        String response;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            response = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.ISO_8859_1);
        }

        assertThat(response).contains(" 415 ");
        assertThat(response).contains("Unsupported webhook Content-Type");
        assertThat(messageRepo.countByOrganizationId(org.org().getId())).isZero();
        assertThat(attachmentRepo.countByOrganizationId(org.org().getId())).isZero();
        assertThat(deliveryRepo.countByOrganizationId(org.org().getId())).isZero();
    }

    private HttpResponse<String> send(WebhookOrg org, Credential credential,
                                      String rawContentType, String canonicalContentType,
                                      byte[] body, String idempotencyKey) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String signature = WebhookTestSupport.hmacSha256Hex(credential.secret(),
                WebhookTestSupport.canonicalString(org.org().getId().toString(),
                        credential.keyId(), String.valueOf(timestamp), nonce, "POST",
                        WEBHOOK_PATH, idempotencyKey, canonicalContentType,
                        WebhookTestSupport.sha256Hex(body)));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + WEBHOOK_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", rawContentType)
                .header("X-HireFlow-Org-Id", org.org().getId().toString())
                .header("X-HireFlow-Key-Id", credential.keyId())
                .header("X-HireFlow-Timestamp", String.valueOf(timestamp))
                .header("X-HireFlow-Nonce", nonce)
                .header("X-HireFlow-Signature", signature)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Credential createCredential(WebhookOrg org) {
        byte[] keyBytes = new byte[8];
        RANDOM.nextBytes(keyBytes);
        StringBuilder keyHex = new StringBuilder();
        for (byte b : keyBytes) {
            keyHex.append(Character.forDigit((b >> 4) & 0xF, 16));
            keyHex.append(Character.forDigit(b & 0xF, 16));
        }
        String keyId = "whk_" + keyHex;
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        credentialRepo.save(WebhookCredential.builder()
                .organizationId(org.org().getId())
                .keyId(keyId)
                .encryptedSecret(CryptoUtil.encrypt(secret, props.getCrypto().getSecretKey()))
                .status(WebhookCredential.STATUS_ACTIVE)
                .build());
        return new Credential(keyId, secret);
    }
}
