package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttempt;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class OAuthMailControllerTest extends IntegrationTestSupport {

    @Autowired
    private OAuthMailController controller;

    @Autowired
    private OAuthAuthorizationAttemptRepository attemptRepo;

    @Autowired
    private MailConnectionRepository connectionRepo;

    @Autowired
    private HireFlowProperties props;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(controller.getRestTemplate());
        // Configure test OAuth client credentials and reset URIs for test isolation
        props.getOauth().setGoogleClientId("test-google-client-id.apps.googleusercontent.com");
        props.getOauth().setGoogleClientSecret("test-google-client-secret");
        props.getOauth().setGoogleTokenUri("https://oauth2.googleapis.com/token");
        props.getOauth().setGmailBaseUrl("https://gmail.googleapis.com/gmail/v1");

        props.getOauth().setMicrosoftClientId("00000000-0000-0000-0000-000000000001");
        props.getOauth().setMicrosoftClientSecret("test-ms-client-secret");
        props.getOauth().setMicrosoftTenant("common");
        props.getOauth().setMicrosoftTokenUriTemplate("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
        props.getOauth().setGraphBaseUrl("https://graph.microsoft.com/v1.0");
    }

    // ─────────────────────────── Initiation Tests ───────────────────────────

    @Test
    @DisplayName("Initiation: Requires authentication")
    void initiateGoogle_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Initiation: Unsupported provider returns 400 Bad Request")
    void initiateUnsupportedProvider_returnsBadRequest() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/mail-connections/oauth/yahoo/authorize")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unsupported provider")));
    }

    @Test
    @DisplayName("Initiation: Missing Google Client ID returns 400 Bad Request")
    void initiateGoogle_missingClientId_returnsBadRequest() throws Exception {
        props.getOauth().setGoogleClientId("");
        String token = login();

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Google OAuth client ID is not configured")));
    }

    @Test
    @DisplayName("Initiation: Google OAuth returns valid URL with offline access, prompt=consent, and records attempt")
    void initiateGoogle_success_createsPendingAttempt() throws Exception {
        String token = login();

        String res = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("google"))
                .andExpect(jsonPath("$.state").isString())
                .andExpect(jsonPath("$.authorizationUrl").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(res);
        String state = json.get("state").asText();
        String authUrl = json.get("authorizationUrl").asText();

        assertThat(state).hasSizeGreaterThanOrEqualTo(40);
        assertThat(authUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(authUrl).contains("client_id=" + props.getOauth().getGoogleClientId());
        assertThat(authUrl).contains("access_type=offline");
        assertThat(authUrl).contains("prompt=consent");
        assertThat(authUrl).contains("response_type=code");
        assertThat(authUrl).contains("state=" + state);
        assertThat(URLDecoder.decode(authUrl, StandardCharsets.UTF_8))
                .contains("https://www.googleapis.com/auth/gmail.readonly");

        // Verify attempt persisted in database
        Optional<OAuthAuthorizationAttempt> attemptOpt = attemptRepo.findByStateToken(state);
        assertThat(attemptOpt).isPresent();
        OAuthAuthorizationAttempt attempt = attemptOpt.get();
        assertThat(attempt.getOrganizationId()).isEqualTo(UUID.fromString(ORG_ID));
        assertThat(attempt.getProvider()).isEqualTo("google");
        assertThat(attempt.getStatus()).isEqualTo("PENDING");
        assertThat(attempt.getCodeVerifier()).isNull();
        assertThat(attempt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Initiation: Microsoft OAuth generates PKCE S256 code verifier/challenge and records attempt")
    void initiateMicrosoft_success_createsPendingAttemptWithPkce() throws Exception {
        String token = login();

        String res = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/authorize")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("microsoft"))
                .andExpect(jsonPath("$.state").isString())
                .andExpect(jsonPath("$.authorizationUrl").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(res);
        String state = json.get("state").asText();
        String authUrl = json.get("authorizationUrl").asText();

        assertThat(authUrl).startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?");
        assertThat(authUrl).contains("code_challenge=");
        assertThat(authUrl).contains("code_challenge_method=S256");
        assertThat(authUrl).contains("response_mode=query");
        assertThat(URLDecoder.decode(authUrl, StandardCharsets.UTF_8))
                .contains("https://graph.microsoft.com/Mail.Read");

        // Verify PKCE verifier stored in database
        Optional<OAuthAuthorizationAttempt> attemptOpt = attemptRepo.findByStateToken(state);
        assertThat(attemptOpt).isPresent();
        OAuthAuthorizationAttempt attempt = attemptOpt.get();
        assertThat(attempt.getCodeVerifier()).isNotBlank();
        assertThat(attempt.getCodeVerifier()).hasSizeGreaterThanOrEqualTo(43);
    }

    // ─────────────────────────── Callback Tests ───────────────────────────

    @Test
    @DisplayName("Callback: Public endpoint permits unauthenticated access")
    void callback_anonymousAccess_permitted() throws Exception {
        // Without Authorization header, should not return 401
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Missing state token")));
    }

    @Test
    @DisplayName("Callback: Provider error (e.g. user cancellation) marks attempt failed and returns error HTML")
    void callback_providerError_marksAttemptFailed() throws Exception {
        String state = "state-cancel-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "access_denied")
                        .param("error_description", "User denied consent")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Authorization was cancelled by user")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HIREFLOW_OAUTH_CALLBACK")));

        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Callback: Invalid or missing state token returns error HTML")
    void callback_invalidState_returnsErrorHtml() throws Exception {
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "some-auth-code")
                        .param("state", "non-existent-state-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Authorization attempt not found")));
    }

    @Test
    @DisplayName("Callback: Expired state token (>10 min) is rejected")
    void callback_expiredState_isRejected() throws Exception {
        String state = "state-expired-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(1))) // expired
                .build());

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "valid-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    @DisplayName("Callback: Replayed state token (already CONSUMED) is atomically rejected")
    void callback_replayedState_isRejected() throws Exception {
        String state = "state-consumed-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("CONSUMED") // already consumed
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .consumedAt(Instant.now().minusSeconds(30))
                .build());

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "valid-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("replay detected")));
    }

    @Test
    @DisplayName("Callback: Google code exchange and profile resolution creates active MailConnection")
    void callback_googleSuccess_exchangesTokensAndSavesConnection() throws Exception {
        String state = "state-google-success-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Mock token exchange POST
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Content-Type", org.hamcrest.Matchers.startsWith("application/x-www-form-urlencoded")))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("code=google-auth-code-123")))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.test_access_token",
                            "expires_in": 3600,
                            "refresh_token": "1//test_refresh_token",
                            "scope": "https://www.googleapis.com/auth/gmail.readonly",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        // Mock Gmail profile GET
        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer ya29.test_access_token"))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "hiring.team@company.com",
                            "messagesTotal": 500,
                            "threadsTotal": 200,
                            "historyId": "987654"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "google-auth-code-123")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hiring.team@company.com")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HIREFLOW_OAUTH_CALLBACK")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCESS")));

        mockServer.verify();

        // Verify attempt is CONSUMED
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo("CONSUMED");
        assertThat(attempt.getConsumedAt()).isNotNull();

        // Verify MailConnection created and credentials encrypted
        Optional<MailConnection> connOpt = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "GMAIL_OAUTH", "hiring.team@company.com");
        assertThat(connOpt).isPresent();
        MailConnection conn = connOpt.get();
        assertThat(conn.getStatus()).isEqualTo("ACTIVE");
        assertThat(conn.getPermissionScope()).isEqualTo("gmail.readonly");

        String decrypted = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.test_access_token");
        assertThat(creds.get("refreshToken").asText()).isEqualTo("1//test_refresh_token");
        assertThat(creds.get("expiresAt").asLong()).isGreaterThan(Instant.now().toEpochMilli());
    }

    @Test
    @DisplayName("Callback: Google reauthorization preserves existing valid refresh token when Google omits it")
    void callback_googleReauthorization_preservesExistingRefreshToken() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        String mailbox = "existing.hr@company.com";

        // Seed an existing connection with an established refresh token
        Map<String, Object> initialCreds = new LinkedHashMap<>();
        initialCreds.put("accessToken", "ya29.old_access_token");
        initialCreds.put("refreshToken", "1//existing_precious_refresh_token");
        initialCreds.put("expiresAt", Instant.now().minusSeconds(100).toEpochMilli());

        String initialEncrypted = CryptoUtil.encrypt(
                objectMapper.writeValueAsString(initialCreds),
                props.getCrypto().getSecretKey());

        connectionRepo.save(MailConnection.builder()
                .organizationId(orgId)
                .provider("GMAIL_OAUTH")
                .mailboxAddress(mailbox)
                .oauthSecretRef(initialEncrypted)
                .status("REAUTH") // e.g. required reauthorization
                .createdBy(UUID.fromString(USER_ID))
                .build());

        // Initiate new reauth attempt
        String state = "state-google-reauth-" + UUID.randomUUID();
        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(orgId)
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Google token response WITHOUT refresh_token (standard Google behavior on re-auth)
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ya29.new_fresh_access_token",
                            "expires_in": 3600,
                            "scope": "https://www.googleapis.com/auth/gmail.readonly",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "existing.hr@company.com",
                            "historyId": "999999"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "google-reauth-code-456")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCESS")));

        mockServer.verify();

        // Verify connection updated to ACTIVE and preserved old refresh token
        MailConnection updatedConn = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                orgId, "GMAIL_OAUTH", mailbox).orElseThrow();
        assertThat(updatedConn.getStatus()).isEqualTo("ACTIVE");

        String decrypted = CryptoUtil.decrypt(updatedConn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.new_fresh_access_token");
        // Crucial requirement: existing refresh token must NOT be overwritten with null
        assertThat(creds.get("refreshToken").asText()).isEqualTo("1//existing_precious_refresh_token");
    }

    @Test
    @DisplayName("Callback: Microsoft OAuth with PKCE verifier exchanges tokens and resolves Graph profile")
    void callback_microsoftSuccess_withPkceVerifier() throws Exception {
        String state = "state-ms-success-" + UUID.randomUUID();
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-test-pkce-verifier";

        attemptRepo.save(OAuthAuthorizationAttempt.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .userId(UUID.fromString(USER_ID))
                .provider("microsoft")
                .stateToken(state)
                .codeVerifier(verifier)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Mock token exchange verifying PKCE code_verifier is passed
        mockServer.expect(MockRestRequestMatchers.requestTo("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("code_verifier=" + verifier)))
                .andExpect(MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andRespond(withSuccess("""
                        {
                            "access_token": "ms_access_token_jwt",
                            "expires_in": 3599,
                            "refresh_token": "0.ARwA_ms_refresh_token",
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        // Mock Microsoft Graph /v1.0/me profile
        mockServer.expect(MockRestRequestMatchers.requestTo("https://graph.microsoft.com/v1.0/me"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header("Authorization", "Bearer ms_access_token_jwt"))
                .andRespond(withSuccess("""
                        {
                            "displayName": "Talent Acquisition",
                            "mail": "recruitment@contoso.com",
                            "userPrincipalName": "recruitment@contoso.onmicrosoft.com"
                        }
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "ms-auth-code-789")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("recruitment@contoso.com")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SUCCESS")));

        mockServer.verify();

        MailConnection conn = connectionRepo.findByOrganizationIdAndProviderAndMailboxAddress(
                UUID.fromString(ORG_ID), "MS_GRAPH_OAUTH", "recruitment@contoso.com").orElseThrow();
        assertThat(conn.getStatus()).isEqualTo("ACTIVE");

        String decrypted = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ms_access_token_jwt");
        assertThat(creds.get("refreshToken").asText()).isEqualTo("0.ARwA_ms_refresh_token");
    }

    @Test
    @DisplayName("Callback: Provider HTTP 400 Bad Request error returns graceful error HTML without crashing")
    void callback_providerExchangeError_handledGracefully() throws Exception {
        String state = "state-google-error-" + UUID.randomUUID();
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
                .andRespond(withBadRequest().body("""
                        {
                            "error": "invalid_grant",
                            "error_description": "Bad Request: expired authorization code"
                        }
                        """));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "expired-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bad Request: expired authorization code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HIREFLOW_OAUTH_CALLBACK")));

        mockServer.verify();
    }
}
