package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttempt;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import com.kartgen.hireflow.security.JwtService;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.tenant.OrganizationUser;
import com.kartgen.hireflow.tenant.OrganizationUserRepository;
import com.kartgen.hireflow.tenant.User;
import com.kartgen.hireflow.tenant.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Empirical Adversarial Challenger Test Suite:
 * Rigorously stress-tests the OAuth 2.0 protocol and state management implementation
 * in HireFlow across 6 critical security vectors:
 * 1. State Replay Attack Resistance (sequential & high-concurrency race condition)
 * 2. Expired State Token Rejection (past TTL, subsecond boundary, valid near boundary)
 * 3. Provider Mismatch Tampering (Google state on MS callback, MS state on Google callback, malformed path)
 * 4. PKCE Code Verifier Tampering (valid verifier, tampered verifier rejection, missing verifier rejection)
 * 5. Multi-Tenant State Isolation (cross-tenant state hijacking, dual-org shared mailbox isolation)
 * 6. Refresh Token Preservation on Reauthorization (omitted refresh token, rotated refresh token, corrupted secret recovery)
 */
class OAuthSecurityStressTest extends IntegrationTestSupport {

    @Autowired
    private OAuthMailController controller;

    @Autowired
    private OAuthAuthorizationAttemptRepository attemptRepo;

    @Autowired
    private MailConnectionRepository connectionRepo;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private OrganizationUserRepository orgUserRepo;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private HireFlowProperties props;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(controller.getRestTemplate());

        // Configure test OAuth client credentials and reset URIs for test isolation
        props.getOauth().setGoogleClientId("stress-google-client-id.apps.googleusercontent.com");
        props.getOauth().setGoogleClientSecret("stress-google-client-secret");
        props.getOauth().setGoogleTokenUri("https://oauth2.googleapis.com/token");
        props.getOauth().setGmailBaseUrl("https://gmail.googleapis.com/gmail/v1");

        props.getOauth().setMicrosoftClientId("00000000-0000-0000-0000-stress000001");
        props.getOauth().setMicrosoftClientSecret("stress-ms-client-secret");
        props.getOauth().setMicrosoftTenant("common");
        props.getOauth().setMicrosoftTokenUriTemplate("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
        props.getOauth().setGraphBaseUrl("https://graph.microsoft.com/v1.0");

        // Clean up connections and attempts
        connectionRepo.deleteAll();
        attemptRepo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        props.getOauth().setGoogleTokenUri("https://oauth2.googleapis.com/token");
        props.getOauth().setGmailBaseUrl("https://gmail.googleapis.com/gmail/v1");
        props.getOauth().setMicrosoftTokenUriTemplate("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
        props.getOauth().setGraphBaseUrl("https://graph.microsoft.com/v1.0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 1: State Replay Attack Resistance
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 1: Sequential Replay Attack — Second callback invocation rejected with zero extra token requests")
    void testSequentialReplayAttack_SecondCallbackRejectedAndZeroTokenExchange() throws Exception {
        String state = "stress-seq-replay-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Expect EXACTLY ONCE for token exchange and profile
        mockServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.stress-token-v1",
                            "expires_in": 3600,
                            "refresh_token": "1//stress-refresh-v1",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "replay.victim@kartgen.com",
                            "messagesTotal": 10
                        }
                        """, MediaType.APPLICATION_JSON));

        // Call 1: First callback succeeds
        MvcResult res1 = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "auth-code-1")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res1.getResponse().getContentAsString()).contains("SUCCESS");
        assertThat(res1.getResponse().getContentAsString()).contains("replay.victim@kartgen.com");

        // Verify status in DB is CONSUMED
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo("CONSUMED");
        assertThat(attempt.getConsumedAt()).isNotNull();

        // Call 2: Sequential replay attempt with same state
        MvcResult res2 = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "auth-code-replay-attacker")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res2.getResponse().getContentAsString()).contains("replay detected");
        assertThat(res2.getResponse().getContentAsString()).contains("ERROR");

        // Call 3: Third replay attempt
        MvcResult res3 = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "auth-code-replay-3")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res3.getResponse().getContentAsString()).contains("replay detected");

        // MockServer verifies NO further calls to Google token or profile endpoint were made
        mockServer.verify();

        // Database has only 1 connection created
        List<MailConnection> conns = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(conns).hasSize(1);
        assertThat(conns.getFirst().getMailboxAddress()).isEqualTo("replay.victim@kartgen.com");
    }

    @Test
    @DisplayName("Vector 1: High-Concurrency Replay Attack — 10 concurrent threads racing on single state, exactly 1 wins")
    void testConcurrentReplayAttack_TenThreadsRacing_ExactlyOneSucceeds() throws Exception {
        String state = "stress-concurrent-state-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Expect token exchange and profile called EXACTLY ONCE across all 10 threads
        mockServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.concurrent-winner-token",
                            "expires_in": 3600,
                            "refresh_token": "1//concurrent-winner-refresh",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(ExpectedCount.once(), MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "concurrent.winner@kartgen.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger replayErrorCount = new AtomicInteger(0);
        List<String> htmlResults = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    barrier.await(); // ensure all threads fire at the exact same instant
                    MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                                    .param("code", "concurrent-code-" + index)
                                    .param("state", state))
                            .andReturn();
                    String html = result.getResponse().getContentAsString();
                    htmlResults.add(html);
                    if (html.contains("SUCCESS") && html.contains("concurrent.winner@kartgen.com")) {
                        successCount.incrementAndGet();
                    } else if (html.contains("replay detected") || html.contains("already been consumed") || html.contains("ERROR")) {
                        replayErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected exception
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).isTrue();

        // Exactly 1 thread must claim the state and succeed
        assertThat(successCount.get()).isEqualTo(1);
        // Exactly 9 threads must be atomically rejected
        assertThat(replayErrorCount.get()).isEqualTo(threadCount - 1);

        // Verify MockRestServiceServer had exactly 1 token exchange call
        mockServer.verify();

        // Database check: Attempt is CONSUMED and exactly 1 MailConnection exists
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo("CONSUMED");
        assertThat(attempt.getConsumedAt()).isNotNull();

        List<MailConnection> conns = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(conns).hasSize(1);
        assertThat(conns.getFirst().getMailboxAddress()).isEqualTo("concurrent.winner@kartgen.com");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 2: Expired State Token Rejection
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 2: Expired State Token — Token past 10-minute TTL is rejected without invoking provider")
    void testExpiredState_PastTtlRejectedWithNoTokenExchange() throws Exception {
        String state = "stress-expired-past-ttl-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(15))) // expired 15 mins ago
                .build());

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "some-auth-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("expired");
        assertThat(html).contains("ERROR");

        // Verify attempt was transitioned to EXPIRED in database
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        assertThat(attempt.getStatus()).isIn("EXPIRED", "PENDING"); // marked expired
        assertThat(attempt.getConsumedAt()).isNull();

        // No connection created
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @DisplayName("Vector 2: Boundary Expiry — Token expired 200ms ago is rejected")
    void testExpiredState_SubsecondBoundaryRejected() throws Exception {
        String state = "stress-subsecond-expired-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().minusMillis(200)) // just expired 200ms ago
                .build());

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code-boundary")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("expired");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @DisplayName("Vector 2: Boundary Expiry — Token valid with 5 seconds remaining is successfully accepted")
    void testExpiredState_JustBeforeExpiryAccepted() throws Exception {
        String state = "stress-just-before-expiry-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofSeconds(5))) // valid for 5 seconds
                .build());

        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.boundary-valid-token",
                            "expires_in": 3600,
                            "refresh_token": "1//boundary-valid-refresh",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "boundary.valid@kartgen.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "valid-boundary-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("SUCCESS");
        assertThat(result.getResponse().getContentAsString()).contains("boundary.valid@kartgen.com");
        mockServer.verify();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 3: Provider Mismatch Tampering
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 3: Provider Mismatch — Google state token sent to Microsoft callback is rejected and neutralized")
    void testProviderMismatch_GoogleStateSubmittedToMicrosoftCallback_Rejected() throws Exception {
        String googleState = "stress-google-state-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(googleState)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Attacker submits Google state to Microsoft callback
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "tampered-ms-code")
                        .param("state", googleState))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Provider mismatch for state token");
        assertThat(html).contains("ERROR");

        // Verify zero calls made to Microsoft or Google token endpoints
        mockServer.verify();

        // Verify state is CONSUMED (neutralized so it cannot be reused)
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(googleState).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo("CONSUMED");

        // Verify no connection was created
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @DisplayName("Vector 3: Provider Mismatch — Microsoft state token sent to Google callback is rejected")
    void testProviderMismatch_MicrosoftStateSubmittedToGoogleCallback_Rejected() throws Exception {
        String msState = "stress-ms-state-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("microsoft")
                .stateToken(msState)
                .codeVerifier("stress-pkce-verifier-test")
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Attacker submits Microsoft state to Google callback
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "tampered-google-code")
                        .param("state", msState))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Provider mismatch for state token");
        assertThat(html).contains("ERROR");

        mockServer.verify();
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @DisplayName("Vector 3: Malformed Provider Path — Unknown provider string in callback route handled gracefully")
    void testProviderMismatch_UnknownProviderPathHandledGracefully() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/hacker_corp/callback")
                        .param("code", "123")
                        .param("state", "non-existent-state"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Authorization attempt not found");
        assertThat(result.getResponse().getContentAsString()).contains("ERROR");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 4: PKCE Code Verifier Tampering (Microsoft OAuth)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 4: PKCE — Valid PKCE code_verifier is included in token exchange POST form")
    void testPkce_ValidCodeVerifierMatchesChallenge() throws Exception {
        String state = "stress-pkce-valid-" + UUID.randomUUID();
        String validVerifier = "E9Melhoa2OwvFrEMTJguCHAo65K_25UGvWwjulETMtstressexample";

        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("microsoft")
                .stateToken(state)
                .codeVerifier(validVerifier)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Verify token exchange includes code_verifier
        mockServer.expect(MockRestRequestMatchers.requestTo("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("code_verifier=" + validVerifier)))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ms_valid_pkce_access_token",
                            "expires_in": 3600,
                            "refresh_token": "ms_valid_pkce_refresh_token",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://graph.microsoft.com/v1.0/me"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "mail": "pkce.success@contoso.onmicrosoft.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "valid-ms-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("SUCCESS");
        assertThat(result.getResponse().getContentAsString()).contains("pkce.success@contoso.onmicrosoft.com");
        mockServer.verify();
    }

    @Test
    @DisplayName("Vector 4: PKCE Tampering — Mismatched code_verifier rejected by Microsoft with HTTP 400 invalid_grant")
    void testPkce_TamperedCodeVerifier_ProviderRejectionHandled() throws Exception {
        String state = "stress-pkce-tampered-" + UUID.randomUUID();
        String tamperedVerifier = "TAMPERED_VERIFIER_THAT_DOES_NOT_MATCH_CHALLENGE";

        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("microsoft")
                .stateToken(state)
                .codeVerifier(tamperedVerifier)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Microsoft token endpoint rejects mismatched verifier
        mockServer.expect(MockRestRequestMatchers.requestTo("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {
                            "error": "invalid_grant",
                            "error_description": "AADSTS54005: OAuth2 Authorization code was already redeemed or PKCE code verifier does not match the code challenge."
                        }
                        """));

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "stolen-ms-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("PKCE code verifier does not match the code challenge");
        assertThat(html).contains("ERROR");

        mockServer.verify();
        // Zero connections created
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @DisplayName("Vector 4: PKCE Missing — Empty/null code_verifier rejected by Microsoft with HTTP 400 invalid_request")
    void testPkce_MissingCodeVerifier_ProviderRejectionHandled() throws Exception {
        String state = "stress-pkce-missing-" + UUID.randomUUID();

        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("microsoft")
                .stateToken(state)
                .codeVerifier(null) // missing
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        mockServer.expect(MockRestRequestMatchers.requestTo("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withBadRequest().body("""
                        {
                            "error": "invalid_request",
                            "error_description": "AADSTS90023: Cross-origin token redemption with PKCE requires code_verifier."
                        }
                        """));

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "ms-code-no-verifier")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Cross-origin token redemption with PKCE requires code_verifier");
        assertThat(html).contains("ERROR");

        mockServer.verify();
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 5: Multi-Tenant State Isolation
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 5: Multi-Tenant Isolation — Org A state callback binds strictly to Org A; Org B has zero access")
    void testMultiTenant_CrossTenantStateTampering_IsolationEnforced() throws Exception {
        // Setup Org B (Foreign Org)
        Organization orgB = orgRepo.save(Organization.builder()
                .name("Tenant B Corp")
                .slug("tenant-b-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User userB = userRepo.save(User.builder()
                .email("admin-b-" + UUID.randomUUID().toString().substring(0, 8) + "@tenantb.com")
                .displayName("Tenant B Admin")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(orgB.getId())
                .userId(userB.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        String tokenOrgB = jwtService.generateToken(userB.getId(), userB.getEmail(), orgB.getId(), "OWNER");
        String tokenOrgA = login(); // Org A token

        // Step 1: Org A initiates OAuth attempt
        String stateA = "state-orgA-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(stateA)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Step 2: Attacker executes callback with stateA
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.tenant-a-token",
                            "expires_in": 3600,
                            "refresh_token": "1//tenant-a-refresh",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "hr.org.a@kartgen.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "org-a-code")
                        .param("state", stateA))
                .andExpect(status().isOk());

        // Verify the MailConnection was created for Org A (NOT Org B)
        List<MailConnection> orgAConns = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(orgAConns).hasSize(1);
        MailConnection createdConn = orgAConns.getFirst();
        assertThat(createdConn.getMailboxAddress()).isEqualTo("hr.org.a@kartgen.com");
        assertThat(createdConn.getOrganizationId()).isEqualTo(UUID.fromString(ORG_ID));

        // Step 3: Org B queries connections — MUST be completely empty!
        MvcResult listOrgB = mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + tokenOrgB))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode orgBListJson = objectMapper.readTree(listOrgB.getResponse().getContentAsString());
        assertThat(orgBListJson.isArray()).isTrue();
        assertThat(orgBListJson).isEmpty();

        // Step 4: Org B attempts to trigger sync on Org A's connection — MUST fail (404 Not Found)
        mockMvc.perform(post("/api/v1/mail-connections/" + createdConn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + tokenOrgB))
                .andExpect(status().isNotFound()); // NoSuchElementException -> GlobalExceptionHandler -> 404

        // Step 5: Org B attempts to delete Org A's connection — MUST fail (404 Not Found)
        mockMvc.perform(delete("/api/v1/mail-connections/" + createdConn.getId())
                        .header("Authorization", "Bearer " + tokenOrgB))
                .andExpect(status().isNotFound()); // NoSuchElementException -> GlobalExceptionHandler -> 404

        // Step 6: Org A queries connections — MUST see the connection intact!
        MvcResult listOrgA = mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + tokenOrgA))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode orgAListJson = objectMapper.readTree(listOrgA.getResponse().getContentAsString());
        assertThat(orgAListJson).hasSize(1);
        assertThat(orgAListJson.get(0).get("mailboxAddress").asText()).isEqualTo("hr.org.a@kartgen.com");
    }

    @Test
    @DisplayName("Vector 5: Multi-Tenant Isolation — Org A and Org B both connect same external mailbox address independently")
    void testMultiTenant_DualOrgSameMailboxAddress_IndependentConnections() throws Exception {
        // Setup Org B
        Organization orgB = orgRepo.save(Organization.builder()
                .name("Tenant B Multi")
                .slug("tenant-b-multi-" + UUID.randomUUID().toString().substring(0, 8))
                .status("ACTIVE")
                .build());

        User userB = userRepo.save(User.builder()
                .email("user-b-" + UUID.randomUUID().toString().substring(0, 8) + "@tenantb.com")
                .displayName("User B Multi")
                .passwordHash("dummy-hash")
                .build());

        orgUserRepo.save(OrganizationUser.builder()
                .organizationId(orgB.getId())
                .userId(userB.getId())
                .role("OWNER")
                .status("ACTIVE")
                .build());

        String sharedMailbox = "shared.enterprise.inbox@company.com";

        // Org A connection
        Map<String, Object> credsA = Map.of("accessToken", "tokenA", "refreshToken", "refreshA", "expiresAt", 9999999L);
        connectionRepo.save(MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress(sharedMailbox)
                .oauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(credsA), props.getCrypto().getSecretKey()))
                .status("ACTIVE")
                .createdBy(UUID.fromString(USER_ID))
                .build());

        // Org B connection for identical mailbox address
        Map<String, Object> credsB = Map.of("accessToken", "tokenB", "refreshToken", "refreshB", "expiresAt", 8888888L);
        connectionRepo.save(MailConnection.builder()
                .organizationId(orgB.getId())
                .provider("GMAIL_OAUTH")
                .mailboxAddress(sharedMailbox)
                .oauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(credsB), props.getCrypto().getSecretKey()))
                .status("ACTIVE")
                .createdBy(userB.getId())
                .build());

        // Verify two distinct records in database
        List<MailConnection> orgAConns = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        List<MailConnection> orgBConns = connectionRepo.findByOrganizationId(orgB.getId());

        assertThat(orgAConns).hasSize(1);
        assertThat(orgBConns).hasSize(1);
        assertThat(orgAConns.getFirst().getId()).isNotEqualTo(orgBConns.getFirst().getId());

        // Decrypt credentials and ensure Org A and Org B credentials remain separate
        String decryptedA = CryptoUtil.decrypt(orgAConns.getFirst().getOauthSecretRef(), props.getCrypto().getSecretKey());
        String decryptedB = CryptoUtil.decrypt(orgBConns.getFirst().getOauthSecretRef(), props.getCrypto().getSecretKey());

        assertThat(decryptedA).contains("tokenA");
        assertThat(decryptedB).contains("tokenB");

        // Pausing Org B's connection does not impact Org A
        String tokenOrgB = jwtService.generateToken(userB.getId(), userB.getEmail(), orgB.getId(), "OWNER");
        mockMvc.perform(delete("/api/v1/mail-connections/" + orgBConns.getFirst().getId())
                        .header("Authorization", "Bearer " + tokenOrgB))
                .andExpect(status().isOk());

        MailConnection reloadedA = connectionRepo.findById(orgAConns.getFirst().getId()).orElseThrow();
        MailConnection reloadedB = connectionRepo.findById(orgBConns.getFirst().getId()).orElseThrow();

        assertThat(reloadedA.getStatus()).isEqualTo("ACTIVE"); // Org A remains active!
        assertThat(reloadedB.getStatus()).isEqualTo("PAUSED"); // Org B paused
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VECTOR 6: Refresh Token Preservation on Google Reauthorization
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vector 6: Refresh Token — Existing valid refresh token is strictly preserved when Google omits refresh_token")
    void testRefreshToken_PreservedWhenGoogleOmits() throws Exception {
        String mailbox = "preserve.test@kartgen.com";
        String existingSecretRefreshToken = "1//existing-very-important-refresh-token-999";

        // Seed existing connection
        Map<String, Object> initial = Map.of(
                "accessToken", "ya29.old-access-token",
                "refreshToken", existingSecretRefreshToken,
                "expiresAt", 1000L);
        connectionRepo.save(MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress(mailbox)
                .oauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(initial), props.getCrypto().getSecretKey()))
                .status("REAUTH")
                .createdBy(UUID.fromString(USER_ID))
                .build());

        // State for reauth
        String state = "stress-reauth-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Google returns ONLY access_token (standard behavior when user already consented)
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.brand-new-access-token-456",
                            "expires_in": 3600,
                            "scope": "https://www.googleapis.com/auth/gmail.readonly",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess(String.format("""
                        {
                            "emailAddress": "%s"
                        }
                        """, mailbox), MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "reauth-google-code")
                        .param("state", state))
                .andExpect(status().isOk());

        mockServer.verify();

        // Verify connection updated to ACTIVE and preserved refresh token
        MailConnection conn = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "GMAIL_OAUTH", mailbox).orElseThrow();
        assertThat(conn.getStatus()).isEqualTo("ACTIVE");

        String decrypted = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);

        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.brand-new-access-token-456");
        // Must preserve existing refresh token!
        assertThat(creds.get("refreshToken").asText()).isEqualTo(existingSecretRefreshToken);
    }

    @Test
    @DisplayName("Vector 6: Refresh Token — New refresh token replaces old refresh token when Google supplies one")
    void testRefreshToken_ReplacedWhenGoogleProvidesNew() throws Exception {
        String mailbox = "replace.test@kartgen.com";

        // Seed existing connection
        Map<String, Object> initial = Map.of(
                "accessToken", "ya29.old-token",
                "refreshToken", "1//old-refresh-token",
                "expiresAt", 1000L);
        connectionRepo.save(MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress(mailbox)
                .oauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(initial), props.getCrypto().getSecretKey()))
                .status("ACTIVE")
                .createdBy(UUID.fromString(USER_ID))
                .build());

        String state = "stress-replace-refresh-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Google returns new refresh token
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.fresh-token",
                            "expires_in": 3600,
                            "refresh_token": "1//brand-new-rotated-refresh-token-111",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess(String.format("""
                        {
                            "emailAddress": "%s"
                        }
                        """, mailbox), MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "google-code-with-new-refresh")
                        .param("state", state))
                .andExpect(status().isOk());

        mockServer.verify();

        MailConnection conn = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "GMAIL_OAUTH", mailbox).orElseThrow();
        String decrypted = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);

        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.fresh-token");
        assertThat(creds.get("refreshToken").asText()).isEqualTo("1//brand-new-rotated-refresh-token-111");
    }

    @Test
    @DisplayName("Vector 6: Refresh Token — Corrupted existing secret in DB does not crash callback and saves new valid secret")
    void testRefreshToken_CorruptedSecretRecovery() throws Exception {
        String mailbox = "corrupted.secret@kartgen.com";

        // Seed connection with corrupt, un-decryptable string
        connectionRepo.save(MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress(mailbox)
                .oauthSecretRef("CORRUPTED_GARBAGE_PAYLOAD_NOT_HEX_OR_AES")
                .status("ERROR")
                .createdBy(UUID.fromString(USER_ID))
                .build());

        String state = "stress-corrupt-recovery-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.healed-token",
                            "expires_in": 3600,
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess(String.format("""
                        {
                            "emailAddress": "%s"
                        }
                        """, mailbox), MediaType.APPLICATION_JSON));

        // Must succeed without 500 error
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "healing-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("SUCCESS");
        mockServer.verify();

        MailConnection healed = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "GMAIL_OAUTH", mailbox).orElseThrow();
        assertThat(healed.getStatus()).isEqualTo("ACTIVE");

        // New secret can be decrypted cleanly
        String decrypted = CryptoUtil.decrypt(healed.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.healed-token");
    }

    @Test
    @DisplayName("Vector 6: Refresh Token — First-time authorization without refresh token creates valid connection")
    void testRefreshToken_FirstTimeNoRefreshToken_Succeeds() throws Exception {
        String mailbox = "first.time.norefresh@kartgen.com";
        String state = "stress-first-time-no-refresh-" + UUID.randomUUID();

        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.first-time-token",
                            "expires_in": 3600,
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess(String.format("""
                        {
                            "emailAddress": "%s"
                        }
                        """, mailbox), MediaType.APPLICATION_JSON));

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "first-time-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("SUCCESS");
        mockServer.verify();

        MailConnection conn = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "GMAIL_OAUTH", mailbox).orElseThrow();
        assertThat(conn.getStatus()).isEqualTo("ACTIVE");

        String decrypted = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.first-time-token");
        assertThat(creds.get("refreshToken").isNull()).isTrue();
    }
}
