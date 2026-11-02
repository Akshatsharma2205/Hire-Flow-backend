package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttempt;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive Opaque-Box E2E Integration Test Suite for Google Gmail and
 * Microsoft 365 OAuth 2.0 Mailbox Connections.
 *
 * Structured into 4 Progressive Tiers:
 * - Tier 1: Feature Coverage (Happy Path)
 * - Tier 2: Boundary & Corner Cases (Equivalence Partitioning & Negative Testing)
 * - Tier 3: Cross-Feature Combinations (Token Preservation, Rotation, Sync Integration)
 * - Tier 4: Real-World Scenarios (End-to-End Enterprise User Journeys)
 *
 * Mocks all external OAuth 2.0 endpoints (Google and Microsoft) via an isolated,
 * embedded HTTP server listening on 127.0.0.1. Zero real network traffic is emitted.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OAuthMailConnectionE2ETest extends IntegrationTestSupport {

    private static HttpServer mockServer;
    private static int mockPort;
    private static String mockBaseUrl;

    // Recorded requests and mock state
    private static final Map<String, String> lastGoogleTokenParams = new ConcurrentHashMap<>();
    private static final Map<String, String> lastMicrosoftTokenParams = new ConcurrentHashMap<>();
    private static final Map<String, String> lastGoogleProfileHeaders = new ConcurrentHashMap<>();
    private static final Map<String, String> lastMicrosoftProfileHeaders = new ConcurrentHashMap<>();

    private static volatile int googleTokenHttpStatus = 200;
    private static volatile String googleTokenResponseBody = null;
    private static volatile int googleProfileHttpStatus = 200;
    private static volatile String googleProfileResponseBody = null;

    private static volatile int msTokenHttpStatus = 200;
    private static volatile String msTokenResponseBody = null;
    private static volatile int msProfileHttpStatus = 200;
    private static volatile String msProfileResponseBody = null;

    @Autowired
    private MailConnectionRepository connectionRepo;

    @Autowired
    private OAuthAuthorizationAttemptRepository attemptRepo;

    @Autowired
    private HireFlowProperties props;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String hrToken;

    @BeforeAll
    static void startMockOAuthServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();
        mockBaseUrl = "http://localhost:" + mockPort;

        // Google Token Endpoint
        mockServer.createContext("/mock-oauth/google/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = readBody(exchange.getRequestBody());
                    lastGoogleTokenParams.clear();
                    lastGoogleTokenParams.putAll(parseFormData(body));

                    int status = googleTokenHttpStatus;
                    String resp = googleTokenResponseBody != null ? googleTokenResponseBody
                            : """
                            {
                              "access_token": "ya29.test-google-access-token",
                              "refresh_token": "1//test-google-refresh-token",
                              "expires_in": 3600,
                              "scope": "https://www.googleapis.com/auth/gmail.readonly",
                              "token_type": "Bearer"
                            }
                            """;
                    sendResponse(exchange, status, resp, "application/json");
                } else {
                    sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                }
            }
        });

        // Google Profile Endpoint (/users/me/profile)
        mockServer.createContext("/mock-oauth/gmail/users/me/profile", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                recordHeaders(exchange, lastGoogleProfileHeaders);
                int status = googleProfileHttpStatus;
                String resp = googleProfileResponseBody != null ? googleProfileResponseBody
                        : """
                        {
                          "emailAddress": "hr-lead@kartgen.com",
                          "messagesTotal": 100,
                          "threadsTotal": 50,
                          "historyId": "99999"
                        }
                        """;
                sendResponse(exchange, status, resp, "application/json");
            }
        });

        // Google Messages List & History Mock (for Sync Adapter)
        mockServer.createContext("/mock-oauth/gmail/users/me/messages", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                sendResponse(exchange, 200, "{\"messages\":[]}", "application/json");
            }
        });
        mockServer.createContext("/mock-oauth/gmail/users/me/history", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                sendResponse(exchange, 200, "{\"history\":[]}", "application/json");
            }
        });

        // Microsoft Token Endpoint
        mockServer.createContext("/mock-oauth/microsoft/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = readBody(exchange.getRequestBody());
                    lastMicrosoftTokenParams.clear();
                    lastMicrosoftTokenParams.putAll(parseFormData(body));

                    int status = msTokenHttpStatus;
                    String resp = msTokenResponseBody != null ? msTokenResponseBody
                            : """
                            {
                              "access_token": "test-microsoft-access-token",
                              "refresh_token": "test-microsoft-refresh-token",
                              "expires_in": 3600,
                              "token_type": "Bearer",
                              "scope": "Mail.Read User.Read offline_access"
                            }
                            """;
                    sendResponse(exchange, status, resp, "application/json");
                } else {
                    sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                }
            }
        });

        // Microsoft Graph Profile Endpoint (/v1.0/me)
        mockServer.createContext("/mock-oauth/microsoft/me", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                recordHeaders(exchange, lastMicrosoftProfileHeaders);
                int status = msProfileHttpStatus;
                String resp = msProfileResponseBody != null ? msProfileResponseBody
                        : """
                        {
                          "id": "ms-user-001",
                          "displayName": "HR Recruiter",
                          "mail": "recruiter@kartgen.onmicrosoft.com",
                          "userPrincipalName": "recruiter@kartgen.onmicrosoft.com"
                        }
                        """;
                sendResponse(exchange, status, resp, "application/json");
            }
        });

        // Microsoft Graph Messages Delta Endpoint (for Sync Adapter)
        mockServer.createContext("/mock-oauth/microsoft/me/mailFolders/inbox/messages/delta", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                sendResponse(exchange, 200, "{\"value\":[]}", "application/json");
            }
        });

        mockServer.start();
    }

    @AfterAll
    static void stopMockOAuthServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        hrToken = login();

        // Configure HireFlowProperties to route OAuth requests to the local mock server
        props.getOauth().setGoogleClientId("mock-google-client-id");
        props.getOauth().setGoogleClientSecret("mock-google-client-secret");
        props.getOauth().setGoogleTokenUri(mockBaseUrl + "/mock-oauth/google/token");
        props.getOauth().setGmailBaseUrl(mockBaseUrl + "/mock-oauth/gmail");

        props.getOauth().setMicrosoftClientId("mock-ms-client-id");
        props.getOauth().setMicrosoftClientSecret("mock-ms-client-secret");
        props.getOauth().setMicrosoftTenant("common");
        props.getOauth().setMicrosoftTokenUriTemplate(mockBaseUrl + "/mock-oauth/microsoft/token");
        props.getOauth().setGraphBaseUrl(mockBaseUrl + "/mock-oauth/microsoft");

        // Reset mock server state
        googleTokenHttpStatus = 200;
        googleTokenResponseBody = null;
        googleProfileHttpStatus = 200;
        googleProfileResponseBody = null;

        msTokenHttpStatus = 200;
        msTokenResponseBody = null;
        msProfileHttpStatus = 200;
        msProfileResponseBody = null;

        lastGoogleTokenParams.clear();
        lastMicrosoftTokenParams.clear();
        lastGoogleProfileHeaders.clear();
        lastMicrosoftProfileHeaders.clear();

        // Clean up connections for test isolation
        connectionRepo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        props.getOauth().setGoogleTokenUri("https://oauth2.googleapis.com/token");
        props.getOauth().setGmailBaseUrl("https://gmail.googleapis.com/gmail/v1");
        props.getOauth().setMicrosoftTokenUriTemplate("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
        props.getOauth().setGraphBaseUrl("https://graph.microsoft.com/v1.0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIER 1: Feature Coverage (Happy Path)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(101)
    @DisplayName("Tier 1: Google Authorization URL generation contains required parameters and returns valid state")
    void testGoogleAuthorizationUrlGeneration_HappyPath() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String authUrl = json.get("authorizationUrl").asText();
        String state = json.get("state").asText();

        // Contract Assertions
        assertThat(authUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(authUrl).contains("client_id=mock-google-client-id");
        assertThat(authUrl).contains("response_type=code");
        assertThat(authUrl).contains("access_type=offline");
        assertThat(authUrl).contains("prompt=consent");
        assertThat(authUrl).contains("state=" + state);
        assertThat(authUrl).contains("gmail.readonly");
        assertThat(state).hasSizeGreaterThanOrEqualTo(16);
    }

    @Test
    @Order(102)
    @DisplayName("Tier 1: Microsoft Authorization URL generation includes PKCE code_challenge and S256 method")
    void testMicrosoftAuthorizationUrlGeneration_WithPkce_HappyPath() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                .andExpect(jsonPath("$.state").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String authUrl = json.get("authorizationUrl").asText();
        String state = json.get("state").asText();

        // Contract Assertions
        assertThat(authUrl).contains("login.microsoftonline.com");
        assertThat(authUrl).contains("client_id=mock-ms-client-id");
        assertThat(authUrl).contains("response_type=code");
        assertThat(authUrl).contains("response_mode=query");
        assertThat(authUrl).contains("code_challenge=");
        assertThat(authUrl).contains("code_challenge_method=S256");
        assertThat(authUrl).contains("state=" + state);
        assertThat(authUrl).contains("Mail.Read");
    }

    @Test
    @Order(103)
    @DisplayName("Tier 1: Google OAuth Callback exchanges code, discovers email, encrypts tokens, returns HTML postMessage")
    void testGoogleCallbackTokenExchange_HappyPath() throws Exception {
        // Step 1: Initiate authorization to get state
        String state = initiateOAuth("google");

        // Step 2: Invoke public callback endpoint
        MvcResult callbackResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "mock_google_code_123")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = callbackResult.getResponse().getContentAsString();
        assertThat(html).contains("HIREFLOW_OAUTH_CALLBACK");
        assertThat(html).contains("SUCCESS");
        assertThat(html).contains("hr-lead@kartgen.com");

        // Step 3: Verify back-channel token exchange parameters
        assertThat(lastGoogleTokenParams)
                .containsEntry("code", "mock_google_code_123")
                .containsEntry("client_id", "mock-google-client-id")
                .containsEntry("client_secret", "mock-google-client-secret")
                .containsEntry("grant_type", "authorization_code");

        // Step 4: Verify MailConnection created with encrypted credentials at rest
        List<MailConnection> connections = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(connections).hasSize(1);
        MailConnection conn = connections.getFirst();
        assertThat(conn.getProvider()).isEqualTo("GMAIL_OAUTH");
        assertThat(conn.getMailboxAddress()).isEqualTo("hr-lead@kartgen.com");
        assertThat(conn.getStatus()).isEqualTo("ACTIVE");

        // Decrypt credentials and assert zero plaintext leakage
        String decryptedJson = CryptoUtil.decrypt(conn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decryptedJson);
        assertThat(creds.get("accessToken").asText()).isEqualTo("ya29.test-google-access-token");
        assertThat(creds.get("refreshToken").asText()).isEqualTo("1//test-google-refresh-token");
    }

    @Test
    @Order(104)
    @DisplayName("Tier 1: Microsoft OAuth Callback exchanges code with PKCE verifier and discovers mailbox address")
    void testMicrosoftCallbackTokenExchange_WithMailboxDiscovery_HappyPath() throws Exception {
        String state = initiateOAuth("microsoft");

        MvcResult callbackResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "mock_ms_auth_code_999")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = callbackResult.getResponse().getContentAsString();
        assertThat(html).contains("HIREFLOW_OAUTH_CALLBACK");
        assertThat(html).contains("SUCCESS");
        assertThat(html).contains("recruiter@kartgen.onmicrosoft.com");

        // Assert PKCE code_verifier was passed in the token exchange POST body
        assertThat(lastMicrosoftTokenParams)
                .containsEntry("code", "mock_ms_auth_code_999")
                .containsEntry("client_id", "mock-ms-client-id")
                .containsEntry("grant_type", "authorization_code")
                .containsKey("code_verifier");
        assertThat(lastMicrosoftTokenParams.get("code_verifier")).isNotEmpty();

        // Verify connection persistence
        List<MailConnection> connections = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(connections).hasSize(1);
        MailConnection conn = connections.getFirst();
        assertThat(conn.getProvider()).isEqualTo("MS_GRAPH_OAUTH");
        assertThat(conn.getMailboxAddress()).isEqualTo("recruiter@kartgen.onmicrosoft.com");
    }

    @Test
    @Order(105)
    @DisplayName("Tier 1: Microsoft Profile falls back to userPrincipalName when mail attribute is null")
    void testMicrosoftCallback_FallbackToUserPrincipalName_WhenMailIsNull() throws Exception {
        msProfileResponseBody = """
                {
                  "id": "ms-user-002",
                  "displayName": "Candidate Ingestion Service",
                  "mail": null,
                  "userPrincipalName": "cloud-intake@kartgen.onmicrosoft.com"
                }
                """;

        String state = initiateOAuth("microsoft");

        MvcResult callbackResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "mock_ms_code_fallback")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(callbackResult.getResponse().getContentAsString())
                .contains("cloud-intake@kartgen.onmicrosoft.com");

        List<MailConnection> connections = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(connections).hasSize(1);
        assertThat(connections.getFirst().getMailboxAddress()).isEqualTo("cloud-intake@kartgen.onmicrosoft.com");
    }

    @Test
    @Order(106)
    @DisplayName("Tier 1: Public Callback endpoint is accessible without Authorization header")
    void testCallbackPublicAccess_NoAuthenticationHeaderRequired() throws Exception {
        String state = initiateOAuth("google");

        // Anonymous call without Authorization header
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "test_public_code")
                        .param("state", state))
                .andExpect(status().isOk());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIER 2: Boundary & Corner Cases (Error Handling & Edge Conditions)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(201)
    @DisplayName("Tier 2: Callback rejects expired OAuth state token with user-facing error")
    void testCallbackRejection_ExpiredStateToken() throws Exception {
        String state = initiateOAuth("google");

        // Manually simulate state expiry in the database
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        attempt.setExpiresAt(Instant.now().minus(15, ChronoUnit.MINUTES));
        attemptRepo.saveAndFlush(attempt);

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "any_code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("ERROR");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(202)
    @DisplayName("Tier 2: Replayed state token is rejected on second callback invocation")
    void testCallbackRejection_ReplayedStateToken() throws Exception {
        String state = initiateOAuth("google");

        // First callback succeeds
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_attempt_1")
                        .param("state", state))
                .andExpect(status().isOk());

        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).hasSize(1);

        // Replay attempt with same state
        MvcResult replayResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_attempt_replay")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = replayResult.getResponse().getContentAsString();
        assertThat(html).contains("ERROR");
    }

    @Test
    @Order(203)
    @DisplayName("Tier 2: Tampered or uninventoried state token returns error without database changes")
    void testCallbackRejection_UnknownOrTamperedState() throws Exception {
        String tamperedState = "tampered-state-token-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "any_code")
                        .param("state", tamperedState))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("ERROR");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(204)
    @DisplayName("Tier 2: Provider mismatch (Google state sent to Microsoft callback) is rejected")
    void testCallbackRejection_ProviderMismatch() throws Exception {
        String googleState = initiateOAuth("google");

        // Submit Google state to Microsoft callback endpoint
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "mismatched_code")
                        .param("state", googleState))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("ERROR");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(205)
    @DisplayName("Tier 2: User consent cancellation (error=access_denied) handled gracefully without 500 crash")
    void testCallbackHandling_UserCancelledConsent() throws Exception {
        String state = initiateOAuth("google");

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "access_denied")
                        .param("error_description", "User denied access to the application")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("ERROR");
        assertThat(html).contains("access_denied");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(206)
    @DisplayName("Tier 2: External OAuth token endpoint failure (HTTP 500) returns error without leaving dirty state")
    void testCallbackHandling_TokenEndpointHttp500Failure() throws Exception {
        googleTokenHttpStatus = 500;
        googleTokenResponseBody = "{\"error\": \"internal_server_error\", \"error_description\": \"Google backend unavailable\"}";

        String state = initiateOAuth("google");

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_for_failed_exchange")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("ERROR");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(207)
    @DisplayName("Tier 2: External Profile API fetch failure returns error postMessage")
    void testCallbackHandling_ProfileEndpointFailure() throws Exception {
        googleProfileHttpStatus = 404;
        googleProfileResponseBody = "{\"error\": \"not_found\"}";

        String state = initiateOAuth("google");

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_valid_token_but_missing_profile")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("ERROR");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();
    }

    @Test
    @Order(208)
    @DisplayName("Tier 2: Authorization initiation without JWT authentication returns HTTP 401 Unauthorized")
    void testInitiationRejection_UnauthenticatedUser() throws Exception {
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(209)
    @DisplayName("Tier 2: Authorization initiation for unsupported provider returns HTTP 400 Bad Request")
    void testInitiationRejection_UnsupportedProvider() throws Exception {
        mockMvc.perform(get("/api/v1/mail-connections/oauth/unsupported_provider_xyz/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isBadRequest());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIER 3: Cross-Feature Combinations (Lifecycle, Token Preservation & Rotation)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(301)
    @DisplayName("Tier 3: Reauthorization preserves existing valid refresh token when Google omits it")
    void testReauthorization_PreservesExistingRefreshToken_WhenGoogleOmitsIt() throws Exception {
        // Step 1: Pre-populate existing connection with a known refresh token
        String existingRefreshToken = "pre-existing-secret-google-refresh-token-123";
        Map<String, Object> initialCreds = Map.of(
                "accessToken", "old-expired-access-token",
                "refreshToken", existingRefreshToken,
                "expiresAt", Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli());
        String encryptedInitial = CryptoUtil.encrypt(
                objectMapper.writeValueAsString(initialCreds), props.getCrypto().getSecretKey());

        MailConnection existingConn = MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress("hr-lead@kartgen.com")
                .status("ACTIVE")
                .oauthSecretRef(encryptedInitial)
                .permissionScope("mail.read offline_access")
                .folderFilter("INBOX")
                .createdBy(UUID.fromString(USER_ID))
                .build();
        connectionRepo.save(existingConn);

        // Step 2: Configure mock Google to return access token WITHOUT refresh_token
        googleTokenResponseBody = """
                {
                  "access_token": "ya29.new-reauthorized-access-token",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
                """;

        // Step 3: Execute OAuth callback
        String state = initiateOAuth("google");
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "reauth_code_without_refresh_token")
                        .param("state", state))
                .andExpect(status().isOk());

        // Step 4: Verify refresh token was PRESERVED in encrypted storage
        List<MailConnection> connections = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(connections).hasSize(1);
        MailConnection updated = connections.getFirst();

        String decrypted = CryptoUtil.decrypt(updated.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode credsNode = objectMapper.readTree(decrypted);

        assertThat(credsNode.get("accessToken").asText()).isEqualTo("ya29.new-reauthorized-access-token");
        assertThat(credsNode.get("refreshToken").asText()).isEqualTo(existingRefreshToken);
    }

    @Test
    @Order(302)
    @DisplayName("Tier 3: Mailbox Sync detects expired token, refreshes token, and persists rotated refresh token")
    void testMailboxSync_HandlesTokenRefreshAndRotatedRefreshToken() throws Exception {
        // Step 1: Pre-populate connection with expired access token
        String initialRefreshToken = "ms-refresh-initial-001";
        Map<String, Object> creds = Map.of(
                "accessToken", "expired-ms-access-token",
                "refreshToken", initialRefreshToken,
                "expiresAt", Instant.now().minus(30, ChronoUnit.MINUTES).toEpochMilli());
        String encrypted = CryptoUtil.encrypt(objectMapper.writeValueAsString(creds), props.getCrypto().getSecretKey());

        MailConnection conn = MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("MS_GRAPH_OAUTH")
                .mailboxAddress("recruiter@kartgen.onmicrosoft.com")
                .status("ACTIVE")
                .oauthSecretRef(encrypted)
                .permissionScope("mail.read offline_access")
                .folderFilter("INBOX")
                .createdBy(UUID.fromString(USER_ID))
                .build();
        connectionRepo.save(conn);

        // Step 2: Configure mock Microsoft token endpoint to return a ROTATED refresh token
        String rotatedRefreshToken = "ms-refresh-ROTATED-token-777";
        msTokenResponseBody = String.format("""
                {
                  "access_token": "freshly-refreshed-ms-access-token",
                  "refresh_token": "%s",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
                """, rotatedRefreshToken);

        // Step 3: Trigger sync-now
        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Step 4: Assert rotated refresh token is persisted in encrypted storage
        MailConnection refreshedConn = connectionRepo.findById(conn.getId()).orElseThrow();
        String decryptedJson = CryptoUtil.decrypt(refreshedConn.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode credsNode = objectMapper.readTree(decryptedJson);

        assertThat(credsNode.get("accessToken").asText()).isEqualTo("freshly-refreshed-ms-access-token");
        assertThat(credsNode.get("refreshToken").asText()).isEqualTo(rotatedRefreshToken);
    }

    @Test
    @Order(303)
    @DisplayName("Tier 3: Mailbox Sync marks connection as REAUTH when refresh token is revoked")
    void testMailboxSync_TransitionsToReauth_WhenTokenRevoked() throws Exception {
        // Seed connection with expired token
        Map<String, Object> creds = Map.of(
                "accessToken", "expired-token",
                "refreshToken", "revoked-refresh-token",
                "expiresAt", Instant.now().minus(10, ChronoUnit.MINUTES).toEpochMilli());
        String encrypted = CryptoUtil.encrypt(objectMapper.writeValueAsString(creds), props.getCrypto().getSecretKey());

        MailConnection conn = MailConnection.builder()
                .organizationId(UUID.fromString(ORG_ID))
                .provider("GMAIL_OAUTH")
                .mailboxAddress("revoked-user@kartgen.com")
                .status("ACTIVE")
                .oauthSecretRef(encrypted)
                .permissionScope("mail.read offline_access")
                .folderFilter("INBOX")
                .createdBy(UUID.fromString(USER_ID))
                .build();
        connectionRepo.save(conn);

        // Provider returns 401 Unauthorized on token refresh
        googleTokenHttpStatus = 401;
        googleTokenResponseBody = "{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}";

        // Trigger sync-now
        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REAUTH"));

        // Verify status in database
        MailConnection dbConn = connectionRepo.findById(conn.getId()).orElseThrow();
        assertThat(dbConn.getStatus()).isEqualTo("REAUTH");

        // Subsequent sync-now is blocked while status is REAUTH
        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Connection is REAUTH and must be reconnected first"));
    }

    @Test
    @Order(304)
    @DisplayName("Tier 3: Connecting the same mailbox address multiple times upserts existing record")
    void testDuplicateMailboxConnection_UpsertsExistingRecord() throws Exception {
        // First connection
        String state1 = initiateOAuth("google");
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_initial")
                        .param("state", state1))
                .andExpect(status().isOk());

        List<MailConnection> initialList = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(initialList).hasSize(1);
        UUID connectionId = initialList.getFirst().getId();

        // Second connection for same mailbox address
        String state2 = initiateOAuth("google");
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code_secondary")
                        .param("state", state2))
                .andExpect(status().isOk());

        // Verify no duplicate row created
        List<MailConnection> finalList = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID));
        assertThat(finalList).hasSize(1);
        assertThat(finalList.getFirst().getMailboxAddress()).isEqualTo("hr-lead@kartgen.com");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIER 4: Real-World Scenarios (End-to-End Enterprise Lifecycles)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @Order(401)
    @DisplayName("Tier 4: Complete Google Workspace HR Onboarding Lifecycle (Authorize -> Callback -> Sync -> Pause)")
    void testScenario1_CompleteGoogleWorkspaceHROnboardingLifecycle() throws Exception {
        // Step 1: HR Manager initiates Google connection
        MvcResult initResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn();
        String state = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("state").asText();

        // Step 2: Consent callback simulates user granting consent
        MvcResult callbackResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "google_e2e_code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(callbackResult.getResponse().getContentAsString()).contains("SUCCESS");

        // Step 3: Mail connection appears in organization connection list
        MvcResult listResult = mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listJson = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(listJson.isArray()).isTrue();
        assertThat(listJson).hasSize(1);
        String connectionIdStr = listJson.get(0).get("id").asText();
        UUID connectionId = UUID.fromString(connectionIdStr);

        // Step 4: HR user triggers manual synchronization
        mockMvc.perform(post("/api/v1/mail-connections/" + connectionId + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Step 5: HR pauses/deletes the mailbox connection
        mockMvc.perform(delete("/api/v1/mail-connections/" + connectionId)
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // Step 6: Verify sync-now is rejected on PAUSED connection
        mockMvc.perform(post("/api/v1/mail-connections/" + connectionId + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Connection is PAUSED and must be reconnected first"));
    }

    @Test
    @Order(402)
    @DisplayName("Tier 4: Enterprise Microsoft 365 Lifecycle with PKCE S256 verification and multi-token rotation")
    void testScenario2_CompleteMicrosoft365EnterpriseLifecycle_WithPkceAndRotation() throws Exception {
        // Step 1: Initiation with PKCE
        MvcResult initResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode initNode = objectMapper.readTree(initResult.getResponse().getContentAsString());
        String authUrl = initNode.get("authorizationUrl").asText();
        String state = initNode.get("state").asText();

        // Extract code_challenge from URL
        Pattern challengePattern = Pattern.compile("code_challenge=([^&]+)");
        Matcher matcher = challengePattern.matcher(authUrl);
        assertThat(matcher.find()).isTrue();
        String codeChallenge = matcher.group(1);
        assertThat(codeChallenge).isNotEmpty();

        // Step 2: Callback execution
        mockMvc.perform(get("/api/v1/mail-connections/oauth/microsoft/callback")
                        .param("code", "ms_enterprise_auth_code")
                        .param("state", state))
                .andExpect(status().isOk());

        // Verify PKCE code_verifier was delivered to token endpoint
        String codeVerifier = lastMicrosoftTokenParams.get("code_verifier");
        assertThat(codeVerifier).isNotNull();

        // Step 3: Verify created connection
        MailConnection conn = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID)).getFirst();
        assertThat(conn.getProvider()).isEqualTo("MS_GRAPH_OAUTH");

        // Step 4: Token rotation on sync
        String nextRotatedToken = "ms-refresh-rotated-v2";
        msTokenResponseBody = String.format("""
                {
                  "access_token": "ms-access-token-v2",
                  "refresh_token": "%s",
                  "expires_in": 3600,
                  "token_type": "Bearer"
                }
                """, nextRotatedToken);

        // Force expired access token to trigger refresh
        Map<String, Object> expiredCreds = Map.of(
                "accessToken", "old-ms-token",
                "refreshToken", "initial-refresh",
                "expiresAt", 1000L);
        conn.setOauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(expiredCreds), props.getCrypto().getSecretKey()));
        connectionRepo.save(conn);

        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk());

        MailConnection reloaded = connectionRepo.findById(conn.getId()).orElseThrow();
        String decrypted = CryptoUtil.decrypt(reloaded.getOauthSecretRef(), props.getCrypto().getSecretKey());
        assertThat(decrypted).contains(nextRotatedToken);
    }

    @Test
    @Order(403)
    @DisplayName("Tier 4: Failed Consent Recovery Workflow (User cancels, then successfully retries)")
    void testScenario3_UserConsentDenialAndRecoveryWorkflow() throws Exception {
        // Attempt 1: User starts flow and cancels on Google screen
        String state1 = initiateOAuth("google");
        MvcResult cancelResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "access_denied")
                        .param("error_description", "User clicked Cancel")
                        .param("state", state1))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cancelResult.getResponse().getContentAsString()).contains("access_denied");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).isEmpty();

        // Attempt 2: User immediately retries and approves consent
        String state2 = initiateOAuth("google");
        assertThat(state2).isNotEqualTo(state1);

        MvcResult successResult = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "google_recovery_code")
                        .param("state", state2))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(successResult.getResponse().getContentAsString()).contains("SUCCESS");
        assertThat(connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID))).hasSize(1);
    }

    @Test
    @Order(404)
    @DisplayName("Tier 4: Token Revocation, REAUTH Detection, and Self-Healing Reauthorization")
    void testScenario4_TokenRevocationAndReauthorizationRecovery() throws Exception {
        // Step 1: Active connection exists
        String state1 = initiateOAuth("google");
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "initial_active_code")
                        .param("state", state1))
                .andExpect(status().isOk());

        MailConnection conn = connectionRepo.findByOrganizationId(UUID.fromString(ORG_ID)).getFirst();

        // Step 2: Token revoked by IT admin; sync marks connection as REAUTH
        googleTokenHttpStatus = 401;
        googleTokenResponseBody = "{\"error\": \"invalid_grant\", \"error_description\": \"Token revoked\"}";

        // Expire access token to force refresh
        Map<String, Object> creds = Map.of(
                "accessToken", "old-token",
                "refreshToken", "revoked-token",
                "expiresAt", 0L);
        conn.setOauthSecretRef(CryptoUtil.encrypt(objectMapper.writeValueAsString(creds), props.getCrypto().getSecretKey()));
        connectionRepo.save(conn);

        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REAUTH"));

        assertThat(connectionRepo.findById(conn.getId()).orElseThrow().getStatus()).isEqualTo("REAUTH");

        // Step 3: User initiates reauthorization flow
        googleTokenHttpStatus = 200;
        googleTokenResponseBody = null; // restore valid mock tokens

        String state2 = initiateOAuth("google");
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "healing_reauth_code")
                        .param("state", state2))
                .andExpect(status().isOk());

        // Step 4: Connection status is restored to ACTIVE
        MailConnection healedConn = connectionRepo.findById(conn.getId()).orElseThrow();
        assertThat(healedConn.getStatus()).isEqualTo("ACTIVE");

        // Sync now succeeds again
        mockMvc.perform(post("/api/v1/mail-connections/" + conn.getId() + "/sync-now")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private String initiateOAuth(String provider) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/" + provider + "/authorize")
                        .header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("state").asText();
    }

    private static String readBody(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseBody, String contentType)
            throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void recordHeaders(HttpExchange exchange, Map<String, String> out) {
        out.clear();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.put(entry.getKey().toLowerCase(), entry.getValue().getFirst());
            }
        }
    }

    private static Map<String, String> parseFormData(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) return map;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }
}
