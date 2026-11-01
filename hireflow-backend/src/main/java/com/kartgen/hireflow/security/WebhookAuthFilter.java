package com.kartgen.hireflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.ApiError;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.tenant.Organization;
import com.kartgen.hireflow.tenant.OrganizationRepository;
import com.kartgen.hireflow.webhook.WebhookCredential;
import com.kartgen.hireflow.webhook.WebhookCredentialRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Authenticates inbound forwarding webhooks using per-organization
 * credentials and an HMAC-SHA256 request signature.
 *
 * <p>Required headers:</p>
 * <ul>
 *   <li>X-HireFlow-Org-Id - organization the credential belongs to</li>
 *   <li>X-HireFlow-Key-Id - public credential identifier</li>
 *   <li>X-HireFlow-Timestamp - epoch seconds, within the replay window</li>
 *   <li>X-HireFlow-Nonce - cryptographically random, unique per delivery</li>
 *   <li>X-HireFlow-Signature - hex HMAC-SHA256 over the canonical string</li>
 *   <li>Idempotency-Key - stable logical delivery identifier</li>
 * </ul>
 *
 * <p>The v2 signature covers the protocol version, organization id, key id,
 * timestamp, nonce, HTTP method, request path (query strings are rejected),
 * idempotency key, the validated canonical Content-Type (including the
 * multipart boundary) and the SHA-256 digest of the exact raw body. There is
 * no v1 downgrade path.</p>
 *
 * <p>Body buffering and multipart parsing happen only after credential,
 * timestamp and signature validation. Both are bounded by configured size and
 * concurrency limits. All failures are sanitized; no secret is logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookAuthFilter extends OncePerRequestFilter {

    public static final String ORG_HEADER = "X-HireFlow-Org-Id";
    public static final String KEY_ID_HEADER = "X-HireFlow-Key-Id";
    public static final String TIMESTAMP_HEADER = "X-HireFlow-Timestamp";
    public static final String NONCE_HEADER = "X-HireFlow-Nonce";
    public static final String SIGNATURE_HEADER = "X-HireFlow-Signature";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String SIGNATURE_VERSION = "v2";

    public static final String ATTR_ORG_ID = "webhook.orgId";
    public static final String ATTR_CREDENTIAL_ID = "webhook.credentialId";
    public static final String ATTR_KEY_ID = "webhook.keyId";
    public static final String ATTR_BODY_SHA256 = "webhook.bodySha256";
    public static final String ATTR_NONCE = "webhook.nonce";
    public static final String ATTR_IDEMPOTENCY_KEY = "webhook.idempotencyKey";
    public static final String ATTR_CONTENT_TYPE = "webhook.contentType";
    public static final String ATTR_MULTIPART_BODY = "webhook.multipartBody";

    static final String WEBHOOK_AUTHORITY = "ROLE_WEBHOOK";

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^whk_[0-9a-f]{16}$");
    private static final Pattern NONCE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{8,200}$");

    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;
    private final OrganizationRepository organizationRepo;
    private final WebhookCredentialRepository credentialRepo;

    private Semaphore intakePermits;

    @PostConstruct
    void initializeConcurrencyLimit() {
        intakePermits = new Semaphore(Math.max(1, props.getWebhook().getMaxConcurrentRequests()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/mail-webhook/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean permitAcquired = false;
        try {
            if (!props.getWebhook().isEnabled()) {
                writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE, "Webhook intake is disabled");
                return;
            }

            // Metadata may only come from the authenticated body. A query
            // string is never part of the signature and is rejected outright.
            if (request.getQueryString() != null) {
                writeError(response, request, HttpStatus.BAD_REQUEST,
                        "Query parameters are not allowed for webhook deliveries");
                return;
            }

            WebhookContentType contentType;
            try {
                contentType = WebhookContentType.parse(singleContentType(request));
            } catch (WebhookContentType.InvalidContentTypeException e) {
                writeError(response, request, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Unsupported webhook Content-Type");
                return;
            }

            String orgHeader = request.getHeader(ORG_HEADER);
            String keyId = request.getHeader(KEY_ID_HEADER);
            String timestamp = request.getHeader(TIMESTAMP_HEADER);
            String nonce = request.getHeader(NONCE_HEADER);
            String signature = request.getHeader(SIGNATURE_HEADER);
            String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

            if (isBlank(orgHeader) || isBlank(keyId) || isBlank(timestamp)
                    || isBlank(nonce) || isBlank(signature)) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Missing webhook authentication");
                return;
            }
            if (isBlank(idempotencyKey)) {
                writeError(response, request, HttpStatus.BAD_REQUEST,
                        "Missing " + IDEMPOTENCY_KEY_HEADER + " header");
                return;
            }

            UUID organizationId;
            try {
                organizationId = UUID.fromString(orgHeader.trim());
            } catch (IllegalArgumentException e) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
                return;
            }
            if (!KEY_ID_PATTERN.matcher(keyId).matches()
                    || !NONCE_PATTERN.matcher(nonce).matches()
                    || !SIGNATURE_PATTERN.matcher(signature).matches()
                    || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
                return;
            }

            long requestTimestamp;
            try {
                requestTimestamp = Long.parseLong(timestamp.trim());
            } catch (NumberFormatException e) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
                return;
            }
            long now = System.currentTimeMillis() / 1000L;
            long window = props.getWebhook().getReplayWindowSeconds();
            if (requestTimestamp < now - window || requestTimestamp > now + window) {
                writeError(response, request, HttpStatus.UNAUTHORIZED,
                        "Webhook timestamp outside allowed window");
                return;
            }

            WebhookCredential credential;
            try {
                credential = credentialRepo.findByKeyId(keyId).orElse(null);
            } catch (DataAccessException e) {
                log.error("Webhook credential lookup failed (fail closed): {}", e.getMessage());
                writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE,
                        "Webhook authentication temporarily unavailable");
                return;
            }
            // Generic responses: never disclose whether the key exists, belongs
            // to another organization, was revoked, or failed verification.
            if (credential == null
                    || !credential.getOrganizationId().equals(organizationId)
                    || !WebhookCredential.STATUS_ACTIVE.equals(credential.getStatus())) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
                return;
            }

            if (!intakePermits.tryAcquire()) {
                writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE,
                        "Webhook intake is busy; retry later");
                return;
            }
            permitAcquired = true;

            WebhookBodyCachingRequestWrapper wrappedRequest =
                    new WebhookBodyCachingRequestWrapper(request, props.getWebhook().getMaxBodyBytes());
            String bodySha256 = HashUtil.sha256Hex(wrappedRequest.getContentAsByteArray());

            String expectedSignature;
            try {
                String secret = CryptoUtil.decrypt(
                        credential.getEncryptedSecret(), props.getCrypto().getSecretKey());
                expectedSignature = hmacSha256Hex(secret, canonicalString(
                        organizationId, keyId, timestamp.trim(), nonce,
                        request.getMethod().toUpperCase(Locale.ROOT),
                        request.getRequestURI(), idempotencyKey,
                        contentType.canonical(), bodySha256));
            } catch (IllegalStateException | DataAccessException e) {
                // Decryption or infrastructure failure must never fail open.
                log.error("Webhook signature preparation failed (fail closed): {}", e.getMessage());
                writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE,
                        "Webhook authentication temporarily unavailable");
                return;
            }
            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII))) {
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Invalid webhook authentication");
                return;
            }

            Organization organization;
            try {
                organization = organizationRepo.findById(organizationId).orElse(null);
            } catch (DataAccessException e) {
                log.error("Webhook organization lookup failed (fail closed): {}", e.getMessage());
                writeError(response, request, HttpStatus.SERVICE_UNAVAILABLE,
                        "Webhook authentication temporarily unavailable");
                return;
            }
            if (organization == null || !"ACTIVE".equals(organization.getStatus())) {
                writeError(response, request, HttpStatus.FORBIDDEN,
                        "Organization not found or inactive");
                return;
            }

            // Parse multipart only after the signature covers the exact bytes.
            // The controller receives this pre-validated representation and
            // never binds client-controlled query/form metadata.
            if (contentType.isMultipart()) {
                try {
                    WebhookMultipartBody multipartBody = MultipartBodyParser.parse(
                            wrappedRequest.getContentAsByteArray(), contentType,
                            props.getWebhook().getMaxParts(),
                            props.getWebhook().getMaxHeaderBytes());
                    wrappedRequest.setMultipartBody(multipartBody);
                    wrappedRequest.setAttribute(ATTR_MULTIPART_BODY, multipartBody);
                } catch (MultipartBodyParser.MultipartLimitExceededException e) {
                    writeError(response, request, HttpStatus.PAYLOAD_TOO_LARGE,
                            "Webhook payload too large");
                    return;
                } catch (MultipartException e) {
                    writeError(response, request, HttpStatus.BAD_REQUEST,
                            "Malformed multipart payload");
                    return;
                }
            }

            TenantContext.setOrganizationId(organizationId);
            var auth = new UsernamePasswordAuthenticationToken(
                    "webhook:" + keyId, null, List.of(new SimpleGrantedAuthority(WEBHOOK_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(auth);

            wrappedRequest.setAttribute(ATTR_ORG_ID, organizationId);
            wrappedRequest.setAttribute(ATTR_CREDENTIAL_ID, credential.getId());
            wrappedRequest.setAttribute(ATTR_KEY_ID, keyId);
            wrappedRequest.setAttribute(ATTR_BODY_SHA256, bodySha256);
            wrappedRequest.setAttribute(ATTR_NONCE, nonce);
            wrappedRequest.setAttribute(ATTR_IDEMPOTENCY_KEY, idempotencyKey);
            wrappedRequest.setAttribute(ATTR_CONTENT_TYPE, contentType.canonical());

            chain.doFilter(wrappedRequest, response);
        } catch (WebhookBodyCachingRequestWrapper.RequestBodyTooLargeException e) {
            writeError(response, request, HttpStatus.PAYLOAD_TOO_LARGE, "Webhook payload too large");
        } finally {
            if (permitAcquired) {
                intakePermits.release();
            }
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Canonical v2 signing string. Byte-exact contract with senders - documented
     * in docs/webhook-security.md and covered by integration tests.
     */
    static String canonicalString(UUID organizationId, String keyId, String timestamp, String nonce,
                                  String method, String requestPath, String idempotencyKey,
                                  String canonicalContentType, String bodySha256Hex) {
        return String.join("\n",
                SIGNATURE_VERSION,
                organizationId.toString(),
                keyId,
                timestamp,
                nonce,
                method,
                requestPath,
                idempotencyKey,
                canonicalContentType,
                bodySha256Hex);
    }

    static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String singleContentType(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Content-Type");
        if (values != null && values.hasMoreElements()) {
            String value = values.nextElement();
            if (values.hasMoreElements()) {
                throw new WebhookContentType.InvalidContentTypeException("Duplicate Content-Type");
            }
            return value;
        }
        String value = request.getContentType();
        if (value == null) {
            throw new WebhookContentType.InvalidContentTypeException("Missing Content-Type");
        }
        return value;
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request,
                            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = ApiError.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
