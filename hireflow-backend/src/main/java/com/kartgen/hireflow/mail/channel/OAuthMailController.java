package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttempt;
import com.kartgen.hireflow.mail.model.OAuthAuthorizationAttemptRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail-connections/oauth")
public class OAuthMailController {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private final OAuthAuthorizationAttemptRepository attemptRepo;
    private final MailConnectionRepository connectionRepo;
    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public OAuthMailController(
            OAuthAuthorizationAttemptRepository attemptRepo,
            MailConnectionRepository connectionRepo,
            HireFlowProperties props,
            ObjectMapper objectMapper,
            RestTemplateBuilder restTemplateBuilder) {
        this.attemptRepo = attemptRepo;
        this.connectionRepo = connectionRepo;
        this.props = props;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }

    // Constructor for testing with mock RestTemplate
    public OAuthMailController(
            OAuthAuthorizationAttemptRepository attemptRepo,
            MailConnectionRepository connectionRepo,
            HireFlowProperties props,
            ObjectMapper objectMapper,
            RestTemplate restTemplate) {
        this.attemptRepo = attemptRepo;
        this.connectionRepo = connectionRepo;
        this.props = props;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public record AuthorizeResponse(String authorizationUrl, String state, String provider) {}

    @GetMapping("/{provider}/authorize")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Transactional
    public ResponseEntity<?> authorize(@PathVariable String provider, HttpServletRequest request) {
        String normalized = normalizeProvider(provider);
        if (normalized == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported provider: " + provider + ". Supported: google, microsoft"));
        }

        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        if (orgId == null || userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error",
                    "User and Organization context required"));
        }

        HireFlowProperties.OAuthProperties oauthProps = props.getOauth();
        String stateToken = generateStateToken();
        String redirectUri = resolveRedirectUri(request, normalized);
        String authUrl;
        String codeVerifier = null;

        if ("google".equals(normalized)) {
            String clientId = oauthProps.getGoogleClientId();
            if (clientId == null || clientId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Google OAuth client ID is not configured (hireflow.oauth.google-client-id)"));
            }

            authUrl = "https://accounts.google.com/o/oauth2/v2/auth?"
                    + "client_id=" + urlEncode(clientId)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + urlEncode("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/userinfo.email")
                    + "&access_type=offline"
                    + "&prompt=consent"
                    + "&state=" + urlEncode(stateToken);

        } else { // microsoft
            String clientId = oauthProps.getMicrosoftClientId();
            if (clientId == null || clientId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Microsoft OAuth client ID is not configured (hireflow.oauth.microsoft-client-id)"));
            }

            PkcePair pkce = generatePkce();
            codeVerifier = pkce.verifier();

            String tenant = (oauthProps.getMicrosoftTenant() != null && !oauthProps.getMicrosoftTenant().isBlank())
                    ? oauthProps.getMicrosoftTenant() : "common";
            String authEndpoint = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/authorize";

            authUrl = authEndpoint + "?"
                    + "client_id=" + urlEncode(clientId)
                    + "&response_type=code"
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&response_mode=query"
                    + "&scope=" + urlEncode("offline_access https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/User.Read")
                    + "&state=" + urlEncode(stateToken)
                    + "&code_challenge=" + urlEncode(pkce.challenge())
                    + "&code_challenge_method=S256"
                    + "&prompt=select_account";
        }

        OAuthAuthorizationAttempt attempt = OAuthAuthorizationAttempt.builder()
                .organizationId(orgId)
                .userId(userId)
                .provider(normalized)
                .stateToken(stateToken)
                .state(stateToken)
                .codeVerifier(codeVerifier)
                .redirectUri(redirectUri)
                .status("PENDING")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                .build();

        attemptRepo.saveAndFlush(attempt);

        log.info("Initiated OAuth flow for provider={} org={} user={} statePrefix={}",
                normalized, orgId, userId, stateToken.substring(0, Math.min(8, stateToken.length())));

        return ResponseEntity.ok(new AuthorizeResponse(authUrl, stateToken, normalized));
    }

    @GetMapping("/{provider}/callback")
    @Transactional
    public ResponseEntity<String> callback(
            @PathVariable String provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {

        String normalizedProvider = normalizeProvider(provider);
        if (normalizedProvider == null) {
            normalizedProvider = provider != null ? provider : "unknown";
        }

        // 1. Check if provider reported an error
        if (error != null && !error.isBlank()) {
            log.warn("OAuth provider returned error: {} - {}", error, errorDescription);
            if (state != null && !state.isBlank()) {
                attemptRepo.markFailedAttempt(state, Instant.now());
            }
            String userError;
            if ("access_denied".equalsIgnoreCase(error)) {
                userError = "access_denied: Authorization was cancelled by user";
            } else if (errorDescription != null && !errorDescription.isBlank()) {
                userError = error + ": " + errorDescription;
            } else {
                userError = error;
            }
            return renderErrorHtml(normalizedProvider, userError);
        }

        // 2. Validate state token presence
        if (state == null || state.isBlank()) {
            log.warn("OAuth callback received without state token");
            return renderErrorHtml(normalizedProvider, "Missing state token parameter");
        }

        // 3. Atomically consume the state token
        Instant now = Instant.now();
        int updated = attemptRepo.consumePendingAttempt(state, now);
        if (updated == 0) {
            Optional<OAuthAuthorizationAttempt> existing = attemptRepo.findByStateToken(state);
            if (existing.isEmpty()) {
                log.warn("OAuth callback rejected: state token not found");
                return renderErrorHtml(normalizedProvider, "Invalid state token. Authorization attempt not found.");
            }
            OAuthAuthorizationAttempt attempt = existing.get();
            if ("CONSUMED".equals(attempt.getStatus())) {
                log.warn("OAuth callback rejected: state token already consumed (replay attempt)");
                return renderErrorHtml(normalizedProvider, "OAuth state has already been consumed (replay detected).");
            }
            if (attempt.getExpiresAt().isBefore(now)) {
                log.warn("OAuth callback rejected: state token expired at {}", attempt.getExpiresAt());
                attemptRepo.markExpiredAttempts(now);
                return renderErrorHtml(normalizedProvider, "OAuth authorization attempt has expired. Please try connecting again.");
            }
            log.warn("OAuth callback rejected: state token has status {}", attempt.getStatus());
            return renderErrorHtml(normalizedProvider, "Authorization attempt is no longer valid (status: " + attempt.getStatus() + ").");
        }

        // 4. Retrieve consumed attempt
        OAuthAuthorizationAttempt attempt = attemptRepo.findByStateToken(state).orElseThrow();
        if (!normalizedProvider.equalsIgnoreCase(attempt.getProvider())) {
            log.warn("OAuth callback provider mismatch: requested={}, attempt={}",
                    normalizedProvider, attempt.getProvider());
            return renderErrorHtml(normalizedProvider, "Provider mismatch for state token");
        }

        if (code == null || code.isBlank()) {
            log.warn("OAuth callback received without code parameter");
            return renderErrorHtml(normalizedProvider, "Missing authorization code from provider");
        }

        // 5. Server-side token exchange and mailbox resolution
        try {
            boolean isGoogle = "google".equals(normalizedProvider);
            TokenExchangeResult tokens = isGoogle
                    ? exchangeGoogleCode(attempt, code)
                    : exchangeMicrosoftCode(attempt, code);

            String rawMailbox = isGoogle
                    ? resolveGoogleMailbox(tokens.accessToken())
                    : resolveMicrosoftMailbox(tokens.accessToken());

            if (rawMailbox == null || !rawMailbox.contains("@")) {
                throw new IllegalStateException("Failed to resolve valid mailbox address from provider profile");
            }
            final String mailboxAddress = rawMailbox.trim().toLowerCase(Locale.ROOT);

            // 6. Refresh Token Preservation
            String storageProvider = isGoogle ? "GMAIL_OAUTH" : "MS_GRAPH_OAUTH";
            Optional<MailConnection> existingConnOpt = connectionRepo
                    .findByOrganizationIdAndProviderAndMailboxAddress(attempt.getOrganizationId(), storageProvider, mailboxAddress);

            String effectiveRefreshToken = tokens.refreshToken();
            if (effectiveRefreshToken == null || effectiveRefreshToken.isBlank()) {
                if (existingConnOpt.isPresent()) {
                    MailConnection existing = existingConnOpt.get();
                    String existingRef = existing.getOauthSecretRef();
                    if (existingRef != null && !existingRef.isBlank()) {
                        try {
                            String decrypted = CryptoUtil.decrypt(existingRef, props.getCrypto().getSecretKey());
                            JsonNode credsNode = objectMapper.readTree(decrypted);
                            String oldRefreshToken = credsNode.path("refreshToken").asText(null);
                            if (oldRefreshToken != null && !oldRefreshToken.isBlank()) {
                                effectiveRefreshToken = oldRefreshToken;
                                log.info("Preserved existing refresh token for connection id={} mailbox={}",
                                        existing.getId(), mailboxAddress);
                            }
                        } catch (Exception ex) {
                            log.warn("Could not decrypt existing credentials to preserve refresh token: {}", ex.getMessage());
                        }
                    }
                }
            }

            // 7. Encrypt and store credentials
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("accessToken", tokens.accessToken());
            values.put("refreshToken", effectiveRefreshToken);
            values.put("expiresAt", Instant.now().plusSeconds(tokens.expiresIn()).toEpochMilli());

            String encryptedSecret = CryptoUtil.encrypt(
                    objectMapper.writeValueAsString(values),
                    props.getCrypto().getSecretKey());

            MailConnection conn = existingConnOpt.orElseGet(() -> MailConnection.builder()
                    .organizationId(attempt.getOrganizationId())
                    .provider(storageProvider)
                    .mailboxAddress(mailboxAddress)
                    .createdBy(attempt.getUserId())
                    .build());

            conn.setOauthSecretRef(encryptedSecret);
            conn.setStatus("ACTIVE");
            conn.setPermissionScope(isGoogle ? "gmail.readonly" : "Mail.Read offline_access");
            if (tokens.historyId() != null && !tokens.historyId().isBlank()
                    && (conn.getSyncCursor() == null || conn.getSyncCursor().isBlank())) {
                conn.setSyncCursor(tokens.historyId());
            }

            connectionRepo.save(conn);

            log.info("Successfully connected mailbox {} via {} for org={}",
                    mailboxAddress, storageProvider, attempt.getOrganizationId());

            return renderSuccessHtml(normalizedProvider, mailboxAddress);

        } catch (RestClientResponseException e) {
            log.warn("OAuth provider HTTP error during code exchange/profile: status={}", e.getStatusCode());
            String errDetail = "Provider rejected token exchange: " + e.getStatusCode();
            try {
                JsonNode errJson = objectMapper.readTree(e.getResponseBodyAsString());
                if (errJson.has("error_description")) {
                    errDetail = errJson.path("error_description").asText();
                } else if (errJson.has("error")) {
                    errDetail = errJson.path("error").asText();
                }
            } catch (Exception ignored) {}
            return renderErrorHtml(normalizedProvider, errDetail);
        } catch (Exception e) {
            log.error("Failed to complete OAuth connection: {}", e.getMessage(), e);
            return renderErrorHtml(normalizedProvider, "Connection failed: " + e.getMessage());
        }
    }

    // ─────────────────────────── Token Exchange ───────────────────────────

    private record TokenExchangeResult(String accessToken, String refreshToken, long expiresIn, String historyId) {}

    private TokenExchangeResult exchangeGoogleCode(OAuthAuthorizationAttempt attempt, String code) {
        HireFlowProperties.OAuthProperties oauth = props.getOauth();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", oauth.getGoogleClientId());
        form.add("client_secret", oauth.getGoogleClientSecret());
        form.add("redirect_uri", attempt.getRedirectUri());
        form.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.postForEntity(
                oauth.getGoogleTokenUri(),
                new HttpEntity<>(form, headers),
                String.class);

        try {
            JsonNode node = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IllegalStateException("Google token response did not contain access_token");
            }
            String refreshToken = node.path("refresh_token").asText(null);
            long expiresIn = node.path("expires_in").asLong(3600);
            return new TokenExchangeResult(accessToken, refreshToken, expiresIn, null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Google token exchange response", e);
        }
    }

    private TokenExchangeResult exchangeMicrosoftCode(OAuthAuthorizationAttempt attempt, String code) {
        HireFlowProperties.OAuthProperties oauth = props.getOauth();
        String tenant = (oauth.getMicrosoftTenant() != null && !oauth.getMicrosoftTenant().isBlank())
                ? oauth.getMicrosoftTenant() : "common";
        String tokenUri = oauth.getMicrosoftTokenUriTemplate().replace("{tenant}", tenant);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", oauth.getMicrosoftClientId());
        if (oauth.getMicrosoftClientSecret() != null && !oauth.getMicrosoftClientSecret().isBlank()) {
            form.add("client_secret", oauth.getMicrosoftClientSecret());
        }
        form.add("code", code);
        form.add("redirect_uri", attempt.getRedirectUri());
        form.add("grant_type", "authorization_code");
        if (attempt.getCodeVerifier() != null && !attempt.getCodeVerifier().isBlank()) {
            form.add("code_verifier", attempt.getCodeVerifier());
        }
        form.add("scope", "offline_access https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/User.Read");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.postForEntity(
                tokenUri,
                new HttpEntity<>(form, headers),
                String.class);

        try {
            JsonNode node = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IllegalStateException("Microsoft token response did not contain access_token");
            }
            String refreshToken = node.path("refresh_token").asText(null);
            long expiresIn = node.path("expires_in").asLong(3600);
            return new TokenExchangeResult(accessToken, refreshToken, expiresIn, null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Microsoft token exchange response", e);
        }
    }

    // ─────────────────────────── Profile Resolution ───────────────────────────

    private String resolveGoogleMailbox(String accessToken) {
        String url = props.getOauth().getGmailBaseUrl() + "/users/me/profile";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        try {
            JsonNode node = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String email = node.path("emailAddress").asText(null);
            if (email == null || email.isBlank()) {
                throw new IllegalStateException("Google profile response missing emailAddress");
            }
            return email;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Google profile response", e);
        }
    }

    private String resolveMicrosoftMailbox(String accessToken) {
        String url = props.getOauth().getGraphBaseUrl() + "/me";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        try {
            JsonNode node = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String mail = node.path("mail").asText(null);
            if (mail != null && !mail.isBlank() && mail.contains("@")) {
                return mail;
            }
            String upn = node.path("userPrincipalName").asText(null);
            if (upn != null && !upn.isBlank() && upn.contains("@")) {
                return upn;
            }
            throw new IllegalStateException("Microsoft profile missing both mail and userPrincipalName");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Microsoft profile response", e);
        }
    }

    // ─────────────────────────── HTML Responses ───────────────────────────

    private ResponseEntity<String> renderSuccessHtml(String provider, String mailboxAddress) {
        String safeProvider = HtmlUtils.htmlEscape(provider != null ? provider : "");
        String safeMailbox = HtmlUtils.htmlEscape(mailboxAddress != null ? mailboxAddress : "");
        String jsProvider = escapeJs(provider != null ? provider : "");
        String jsMailbox = escapeJs(mailboxAddress != null ? mailboxAddress : "");

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>HireFlow — Mailbox Connected</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc; color: #1e293b; }
                    .card { background: white; padding: 2rem; border-radius: 0.75rem; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); text-align: center; max-width: 420px; }
                    .success { color: #16a34a; font-size: 1.25rem; font-weight: 600; margin-bottom: 0.5rem; }
                    p { color: #64748b; font-size: 0.875rem; line-height: 1.5; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="success">✓ Mailbox Connected</div>
                    <p>Mailbox <strong>%s</strong> connected successfully. This window will close automatically.</p>
                </div>
                <script>
                    const message = {
                        type: 'HIREFLOW_OAUTH_CALLBACK',
                        status: 'SUCCESS',
                        provider: '%s',
                        mailboxAddress: '%s'
                    };
                    if (window.opener) {
                        try {
                            window.opener.postMessage(message, window.location.origin);
                        } catch (e) {
                            console.error('postMessage failed:', e);
                        }
                        setTimeout(() => window.close(), 1000);
                    } else {
                        setTimeout(() => {
                            window.location.href = '/index.html?oauth=success&provider=' + encodeURIComponent('%s') + '&mailbox=' + encodeURIComponent('%s');
                        }, 1200);
                    }
                </script>
            </body>
            </html>
            """.formatted(safeMailbox, jsProvider, jsMailbox, jsProvider, jsMailbox);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html);
    }

    private ResponseEntity<String> renderErrorHtml(String provider, String errorMessage) {
        String safeProvider = HtmlUtils.htmlEscape(provider != null ? provider : "");
        String safeError = HtmlUtils.htmlEscape(errorMessage != null ? errorMessage : "An error occurred");
        String jsProvider = escapeJs(provider != null ? provider : "");
        String jsError = escapeJs(errorMessage != null ? errorMessage : "An error occurred");

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>HireFlow — Connection Failed</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; background: #f8fafc; color: #1e293b; }
                    .card { background: white; padding: 2rem; border-radius: 0.75rem; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); text-align: center; max-width: 420px; }
                    .error { color: #dc2626; font-size: 1.25rem; font-weight: 600; margin-bottom: 0.5rem; }
                    p { color: #64748b; font-size: 0.875rem; line-height: 1.5; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="error">✕ Connection Failed</div>
                    <p>%s</p>
                </div>
                <script>
                    const message = {
                        type: 'HIREFLOW_OAUTH_CALLBACK',
                        status: 'ERROR',
                        provider: '%s',
                        error: '%s'
                    };
                    if (window.opener) {
                        try {
                            window.opener.postMessage(message, window.location.origin);
                        } catch (e) {
                            console.error('postMessage failed:', e);
                        }
                        setTimeout(() => window.close(), 3000);
                    } else {
                        setTimeout(() => {
                            window.location.href = '/index.html?oauth=error&provider=' + encodeURIComponent('%s') + '&msg=' + encodeURIComponent('%s');
                        }, 2500);
                    }
                </script>
            </body>
            </html>
            """.formatted(safeError, jsProvider, jsError, jsProvider, jsError);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html);
    }

    // ─────────────────────────── Helper Methods ───────────────────────────

    private String normalizeProvider(String provider) {
        if (provider == null) return null;
        String p = provider.trim().toLowerCase(Locale.ROOT);
        if (p.contains("google") || p.contains("gmail")) return "google";
        if (p.contains("micro") || p.contains("graph") || p.contains("ms")) return "microsoft";
        return null;
    }

    private String generateStateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private PkcePair generatePkce() {
        byte[] verifierBytes = new byte[64];
        secureRandom.nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return new PkcePair(verifier, challenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record PkcePair(String verifier, String challenge) {}

    private String resolveRedirectUri(HttpServletRequest request, String normalizedProvider) {
        return ServletUriComponentsBuilder.fromRequest(request)
                .replacePath("/api/v1/mail-connections/oauth/" + normalizedProvider + "/callback")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeJs(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\/");
    }

    RestTemplate getRestTemplate() {
        return this.restTemplate;
    }
}
