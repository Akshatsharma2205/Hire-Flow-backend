package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttempt;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Empirical Adversarial Challenger Test Suite:
 * Probing Frontend Security, Message Bridge Validation, XSS Resistance,
 * Mailbox Deduplication, and Zero Plaintext Token Exposure.
 */
@Transactional
class OAuthSecurityChallengerTest extends IntegrationTestSupport {

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

    @AfterEach
    void tearDown() {
        props.getOauth().setGoogleTokenUri("https://oauth2.googleapis.com/token");
        props.getOauth().setGmailBaseUrl("https://gmail.googleapis.com/gmail/v1");
        props.getOauth().setMicrosoftTokenUriTemplate("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token");
        props.getOauth().setGraphBaseUrl("https://graph.microsoft.com/v1.0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. XSS Payload Injection Resistance in HTML Bridge
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Adversarial XSS 1: Script tag breakout in error_description is neutralized")
    void testXss_ScriptTagBreakout_Neutralized() throws Exception {
        String xssPayload = "</script><script>alert('CHALLENGE_XSS_1')</script>";

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "server_error")
                        .param("error_description", xssPayload))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // 1. In HTML markup before <script>, zero unescaped <script tags exist
        String htmlBodyOnly = body.substring(0, body.indexOf("<script>"));
        assertThat(htmlBodyOnly).doesNotContain("<script");
        assertThat(htmlBodyOnly).contains("&lt;/script&gt;&lt;script&gt;alert(&#39;CHALLENGE_XSS_1&#39;)&lt;/script&gt;");

        // 2. Exactly one </script> closing tag exists in the entire document (no premature script block closure)
        int scriptCloseCount = countOccurrences(body, "</script>");
        assertThat(scriptCloseCount).isEqualTo(1)
                .withFailMessage("Injected </script> must NOT close script block prematurely");

        // 3. Inside JS script block, </ is escaped to <\/
        assertThat(body).contains("<\\/script><script>alert(\\'CHALLENGE_XSS_1\\')<\\/script>");
    }

    @Test
    @DisplayName("Adversarial XSS 2: JS string literal quote breakout is neutralized")
    void testXss_QuoteBreakout_Neutralized() throws Exception {
        String xssPayload = "'; window.evil=true; //";

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "server_error")
                        .param("error_description", xssPayload))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Must escape single quotes to prevent breaking out of JS string literal
        assertThat(body).contains("\\'; window.evil=true; //");
        // HTML body portion must have escaped single quote
        assertThat(body).contains("&#39;; window.evil=true; //");

        // Exactly one script close tag
        assertThat(countOccurrences(body, "</script>")).isEqualTo(1);
    }

    @Test
    @DisplayName("Adversarial XSS 3: Img tag onerror injection in error message is neutralized in HTML body")
    void testXss_ImgOnError_Neutralized() throws Exception {
        String xssPayload = "<img src=x onerror=alert('CHALLENGE_IMG_XSS')>";

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "invalid_request")
                        .param("error_description", xssPayload))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Separate HTML body from <script> block
        String htmlBodyOnly = body.substring(0, body.indexOf("<script>"));

        // HTML body must entity-escape < and > and contain zero <img> tags
        assertThat(htmlBodyOnly).doesNotContain("<img");
        assertThat(htmlBodyOnly).contains("&lt;img src=x onerror=alert(&#39;CHALLENGE_IMG_XSS&#39;)&gt;");

        // Script block count remains 1
        assertThat(countOccurrences(body, "</script>")).isEqualTo(1);
    }

    @Test
    @DisplayName("Adversarial XSS 4: SVG onload tag in error description is neutralized in HTML body")
    void testXss_SvgOnLoad_Neutralized() throws Exception {
        String xssPayload = "\"><svg/onload=alert('CHALLENGE_SVG')>";

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "server_error")
                        .param("error_description", xssPayload))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Separate HTML body from <script> block
        String htmlBodyOnly = body.substring(0, body.indexOf("<script>"));

        // HTML body must entity-escape < and > and contain zero <svg> tags
        assertThat(htmlBodyOnly).doesNotContain("<svg");
        assertThat(htmlBodyOnly).contains("&quot;&gt;&lt;svg/onload=alert(&#39;CHALLENGE_SVG&#39;)&gt;");

        // Script block count remains 1
        assertThat(countOccurrences(body, "</script>")).isEqualTo(1);
    }

    @Test
    @DisplayName("Adversarial XSS 5: Unknown provider and path parameter safety")
    void testXss_ProviderPathVariable_Neutralized() throws Exception {
        // Test 1: Unsupported provider segment safely renders error HTML with provider properly escaped
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/unsupported_prov_123/callback")
                        .param("error", "access_denied"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("unsupported_prov_123");
        assertThat(countOccurrences(body, "</script>")).isEqualTo(1);

        // Test 2: Injected script in provider path is rejected by Spring MVC routing before reaching application logic
        mockMvc.perform(get("/api/v1/mail-connections/oauth/attacker<script>alert(1)</script>/callback")
                        .param("error", "access_denied"))
                .andExpect(status().is4xxClientError());
    }

    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. PostMessage Security: Strict Origin Targeting
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PostMessage Security: HTML bridge strictly targets window.location.origin and never wildcard *")
    void testPostMessage_StrictOriginTargeting() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("error", "access_denied"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Strictly verify window.location.origin is passed as 2nd argument
        assertThat(body).contains("window.opener.postMessage(message, window.location.origin);");
        // Verify wildcard target origin is NEVER used
        assertThat(body).doesNotContain("postMessage(message, '*')");
        assertThat(body).doesNotContain("postMessage(message, \"*\")");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Mailbox Deduplication & Reconnect Behavior
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deduplication: Connecting same mailbox updates existing entity, preserving ID and zero duplicates")
    void testMailboxDeduplication_UpdatesExistingConnection() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        UUID userId = UUID.fromString(USER_ID);
        String mailbox = "recruiting.lead@company.com";

        // Step 1: Pre-populate existing MailConnection in REAUTH state
        String originalRefreshToken = "original-permanent-refresh-token-12345";
        Map<String, Object> initialCreds = Map.of(
                "accessToken", "expired-access-token",
                "refreshToken", originalRefreshToken,
                "expiresAt", 0L
        );
        String encryptedInitial = CryptoUtil.encrypt(
                objectMapper.writeValueAsString(initialCreds),
                props.getCrypto().getSecretKey()
        );

        MailConnection initialConn = MailConnection.builder()
                .organizationId(orgId)
                .provider("GMAIL_OAUTH")
                .mailboxAddress(mailbox)
                .oauthSecretRef(encryptedInitial)
                .status("REAUTH")
                .createdBy(userId)
                .createdAt(Instant.now().minus(Duration.ofDays(10)))
                .build();
        initialConn = connectionRepo.saveAndFlush(initialConn);
        UUID originalId = initialConn.getId();

        long initialCount = connectionRepo.count();

        // Step 2: Simulate re-authorization via OAuth callback for the same mailbox
        String state = "dedup-state-" + UUID.randomUUID();
        attemptRepo.saveAndFlush(OAuthAuthorizationAttempt.builder()
                .organizationId(orgId)
                .userId(userId)
                .provider("google")
                .stateToken(state)
                .state(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        // Provider token response (omits refresh_token on re-authorization)
        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "access_token": "new-reauthorized-access-token",
                            "expires_in": 3600,
                            "token_type": "Bearer"
                        }
                        """, MediaType.APPLICATION_JSON));

        // Provider profile response returns exact same mailbox
        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                            "emailAddress": "recruiting.lead@company.com",
                            "historyId": "999888777"
                        }
                        """, MediaType.APPLICATION_JSON));

        // Execute callback
        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "reauth-auth-code")
                        .param("state", state))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mailbox Connected")));

        mockServer.verify();

        // Step 3: Assert deduplication invariants
        long finalCount = connectionRepo.count();
        assertThat(finalCount).isEqualTo(initialCount)
                .withFailMessage("Reauthorizing existing mailbox must NOT create duplicate rows in mail_connections");

        Optional<MailConnection> updatedOpt = connectionRepo.findById(originalId);
        assertThat(updatedOpt).isPresent();
        MailConnection updated = updatedOpt.get();

        // Must update status back to ACTIVE
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getMailboxAddress()).isEqualTo(mailbox);

        // Must preserve the existing valid refresh token
        String decryptedCreds = CryptoUtil.decrypt(updated.getOauthSecretRef(), props.getCrypto().getSecretKey());
        JsonNode credsNode = objectMapper.readTree(decryptedCreds);
        assertThat(credsNode.path("accessToken").asText()).isEqualTo("new-reauthorized-access-token");
        assertThat(credsNode.path("refreshToken").asText()).isEqualTo(originalRefreshToken);
    }

    @Test
    @DisplayName("Deduplication: Case-insensitive mailbox matching prevents duplicates (e.g. USER@COMPANY.COM vs user@company.com)")
    void testMailboxDeduplication_CaseInsensitiveMatching() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        UUID userId = UUID.fromString(USER_ID);

        // Initial entry in lowercase
        MailConnection initialConn = MailConnection.builder()
                .organizationId(orgId)
                .provider("GMAIL_OAUTH")
                .mailboxAddress("case.test@company.com")
                .status("REAUTH")
                .createdBy(userId)
                .build();
        initialConn = connectionRepo.saveAndFlush(initialConn);
        UUID originalId = initialConn.getId();

        long countBefore = connectionRepo.count();

        // Callback returns uppercase address
        String state = "case-state-" + UUID.randomUUID();
        attemptRepo.saveAndFlush(OAuthAuthorizationAttempt.builder()
                .organizationId(orgId)
                .userId(userId)
                .provider("google")
                .stateToken(state)
                .state(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("""
                        {"access_token": "token-xyz", "expires_in": 3600}
                        """, MediaType.APPLICATION_JSON));

        // Provider returns mixed/upper-case
        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withSuccess("""
                        {"emailAddress": "CASE.TEST@COMPANY.COM"}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code-case")
                        .param("state", state))
                .andExpect(status().isOk());

        mockServer.verify();

        long countAfter = connectionRepo.count();
        assertThat(countAfter).isEqualTo(countBefore)
                .withFailMessage("Case-insensitive mailbox matching must not create duplicate row");

        MailConnection updated = connectionRepo.findById(originalId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
        assertThat(updated.getMailboxAddress()).isEqualTo("case.test@company.com");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. UI Security: Zero Plaintext Tokens in API Responses & DB Encryption
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UI Security: GET /api/v1/mail-connections exposes zero tokens or secret refs to frontend")
    void testApiSecurity_ZeroTokensExposedInListResponse() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        String secretJson = """
                {"accessToken":"secret-access-token-999","refreshToken":"secret-refresh-token-888","expiresAt":1893456000000}
                """;
        String encrypted = CryptoUtil.encrypt(secretJson, props.getCrypto().getSecretKey());

        connectionRepo.saveAndFlush(MailConnection.builder()
                .organizationId(orgId)
                .provider("GMAIL_OAUTH")
                .mailboxAddress("security.test@company.com")
                .oauthSecretRef(encrypted)
                .status("ACTIVE")
                .build());

        String jwt = login();

        MvcResult result = mockMvc.perform(get("/api/v1/mail-connections")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();

        String jsonResponse = result.getResponse().getContentAsString();

        // 1. Must NOT contain plaintext tokens
        assertThat(jsonResponse).doesNotContain("secret-access-token-999");
        assertThat(jsonResponse).doesNotContain("secret-refresh-token-888");
        // 2. Must NOT expose oauthSecretRef or encrypted blob
        assertThat(jsonResponse).doesNotContain("oauthSecretRef");
        assertThat(jsonResponse).doesNotContain("refreshToken");
        assertThat(jsonResponse).doesNotContain("accessToken");
        assertThat(jsonResponse).doesNotContain("appPassword");
    }

    @Test
    @DisplayName("UI Security: OAuth credentials persisted in database are strictly AES encrypted")
    void testDatabaseSecurity_CredentialsEncryptedAtRest() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        String state = "enc-test-state-" + UUID.randomUUID();

        attemptRepo.saveAndFlush(OAuthAuthorizationAttempt.builder()
                .organizationId(orgId)
                .userId(UUID.fromString(USER_ID))
                .provider("google")
                .stateToken(state)
                .state(state)
                .redirectUri("http://localhost:8080/api/v1/mail-connections/oauth/google/callback")
                .status("PENDING")
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build());

        String plaintextAccessToken = "sensitive-google-access-token-abc123";
        String plaintextRefreshToken = "sensitive-google-refresh-token-xyz789";

        mockServer.expect(MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(String.format("""
                        {
                            "access_token": "%s",
                            "refresh_token": "%s",
                            "expires_in": 3600
                        }
                        """, plaintextAccessToken, plaintextRefreshToken), MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                .andRespond(withSuccess("""
                        {"emailAddress": "encrypted.check@company.com"}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/mail-connections/oauth/google/callback")
                        .param("code", "code-enc")
                        .param("state", state))
                .andExpect(status().isOk());

        mockServer.verify();

        MailConnection saved = connectionRepo
                .findByOrganizationIdAndProviderAndMailboxAddress(orgId, "GMAIL_OAUTH", "encrypted.check@company.com")
                .orElseThrow();

        String storedRef = saved.getOauthSecretRef();
        assertThat(storedRef).isNotNull().isNotBlank();

        // Plaintext tokens must NOT appear in the raw stored column
        assertThat(storedRef).doesNotContain(plaintextAccessToken);
        assertThat(storedRef).doesNotContain(plaintextRefreshToken);

        // Stored ref must be decryptable with secret key
        String decrypted = CryptoUtil.decrypt(storedRef, props.getCrypto().getSecretKey());
        JsonNode creds = objectMapper.readTree(decrypted);
        assertThat(creds.path("accessToken").asText()).isEqualTo(plaintextAccessToken);
        assertThat(creds.path("refreshToken").asText()).isEqualTo(plaintextRefreshToken);
    }
}

