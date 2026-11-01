package com.kartgen.hireflow.security;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.support.WebhookTestSupport;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.webhook.WebhookCredential;
import com.kartgen.hireflow.webhook.WebhookCredentialRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the intake concurrency cap fails closed with {@code 503} before a
 * second body is buffered or parsed, while the first request remains in flight.
 *
 * <p>This is a filter-level test with a blocking downstream chain; the real
 * filter, signature verification, credential lookup and semaphore are used.</p>
 */
class WebhookIntakeConcurrencyTest {

    private static final String PATH = "/api/v1/mail-webhook/inbound";
    private static final String CRYPTO_KEY = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @Test
    void secondConcurrentRequestIsRejectedWithServiceUnavailable() throws Exception {
        HireFlowProperties props = new HireFlowProperties();
        props.getCrypto().setSecretKey(CRYPTO_KEY);
        props.getWebhook().setMaxConcurrentRequests(1);

        UUID orgId = UUID.randomUUID();
        String keyId = "whk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String secret = "j2a-concurrency-secret-" + UUID.randomUUID();
        Organization org = Organization.builder()
                .id(orgId).name("J2A concurrency").slug("j2a-" + orgId).status("ACTIVE")
                .build();
        WebhookCredential credential = WebhookCredential.builder()
                .organizationId(orgId)
                .keyId(keyId)
                .encryptedSecret(CryptoUtil.encrypt(secret, CRYPTO_KEY))
                .status(WebhookCredential.STATUS_ACTIVE)
                .build();

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        WebhookCredentialRepository credentialRepo = mock(WebhookCredentialRepository.class);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(credentialRepo.findByKeyId(keyId)).thenReturn(Optional.of(credential));

        WebhookAuthFilter filter = new WebhookAuthFilter(
                props, JsonMapper.builder().addModule(new JavaTimeModule()).build(),
                orgRepo, credentialRepo);
        filter.initializeConcurrencyLimit();

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        CountDownLatch firstEnteredChain = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        Thread first = new Thread(() -> {
            try {
                filter.doFilter(signedRequest(orgId, keyId, secret, body, "j2a-concurrency-first"),
                        firstResponse, blockingChain(firstEnteredChain, releaseFirst));
            } catch (Exception e) {
                throw new AssertionError("first request failed", e);
            }
        }, "j2a-concurrency-first");
        first.start();

        assertThat(firstEnteredChain.await(5, TimeUnit.SECONDS))
                .as("first request must reach the downstream chain")
                .isTrue();

        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(signedRequest(orgId, keyId, secret, body, "j2a-concurrency-second"),
                secondResponse, (req, res) -> {
                    throw new AssertionError("second request must not reach the chain");
                });

        assertThat(secondResponse.getStatus()).isEqualTo(503);
        assertThat(secondResponse.getContentAsString())
                .contains("Webhook intake is busy; retry later");

        releaseFirst.countDown();
        first.join(5_000);
        assertThat(first.isAlive()).isFalse();
        assertThat(firstResponse.getStatus()).isEqualTo(200);
    }

    private static FilterChain blockingChain(CountDownLatch entered, CountDownLatch release) {
        return (request, response) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release the blocking chain");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while holding the intake permit", e);
            }
        };
    }

    private static MockHttpServletRequest signedRequest(UUID orgId, String keyId, String secret,
                                                        byte[] body, String idempotencyKey) {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = WebhookTestSupport.randomNonce();
        String signature = WebhookTestSupport.hmacSha256Hex(secret,
                WebhookTestSupport.canonicalString(orgId.toString(), keyId,
                        String.valueOf(timestamp), nonce, "POST", PATH, idempotencyKey,
                        WebhookTestSupport.canonicalJsonContentType(),
                        WebhookTestSupport.sha256Hex(body)));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader(WebhookAuthFilter.ORG_HEADER, orgId.toString());
        request.addHeader(WebhookAuthFilter.KEY_ID_HEADER, keyId);
        request.addHeader(WebhookAuthFilter.TIMESTAMP_HEADER, String.valueOf(timestamp));
        request.addHeader(WebhookAuthFilter.NONCE_HEADER, nonce);
        request.addHeader(WebhookAuthFilter.SIGNATURE_HEADER, signature);
        request.addHeader(WebhookAuthFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        return request;
    }
}
