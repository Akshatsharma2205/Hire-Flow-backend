package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import com.kartgen.hireflow.mail.service.MessageIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleMailAdapter implements MailSyncPort {

    private static final int MAX_PAGES = 10;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private final MailConnectionRepository connectionRepo;
    private final MessageIngestionService ingestionService;
    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String provider) {
        return "GMAIL_OAUTH".equalsIgnoreCase(provider);
    }

    @Override
    public SyncResult syncConnection(MailConnection conn) {
        conn.setLastAttemptAt(Instant.now());
        try {
            int processed = syncGmail(conn);
            conn.setInitialSyncCompleted(true);
            conn.setConsecutiveFailures(0);
            conn.setSyncStatusDetails("Gmail sync completed successfully");
            conn.setLastSuccessAt(Instant.now());
            conn.setStatus("ACTIVE");
            connectionRepo.save(conn);

            String summary = String.format("Gmail sync successful: %d resume attachment(s) ingested.", processed);
            return new SyncResult("ACTIVE", processed, processed, summary, null);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Gmail API rate limit reached (HTTP 429) for connection={}", conn.getId());
            conn.setSyncStatusDetails("Rate limited (HTTP 429). Backing off.");
            connectionRepo.save(conn);
            return new SyncResult("ACTIVE", 0, 0, "Rate limited (HTTP 429). Retrying on next schedule.", null);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            String err = "Gmail OAuth rejected (" + e.getStatusCode() + "); token expired or revoked. Please re-authenticate.";
            log.warn("Gmail authorization rejected for connection={} ({})", conn.getId(), e.getStatusCode());
            conn.setStatus("REAUTH");
            conn.setConsecutiveFailures(conn.getConsecutiveFailures() != null ? conn.getConsecutiveFailures() + 1 : 1);
            conn.setSyncStatusDetails(err);
            connectionRepo.save(conn);
            return new SyncResult("REAUTH", 0, 0, null, err);
        } catch (Exception e) {
            String err = "Gmail sync error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("Gmail sync failed for connection={}: {}", conn.getId(), err, e);
            conn.setStatus("ERROR");
            conn.setConsecutiveFailures(conn.getConsecutiveFailures() != null ? conn.getConsecutiveFailures() + 1 : 1);
            conn.setSyncStatusDetails(err);
            connectionRepo.save(conn);
            return new SyncResult("ERROR", 0, 0, null, err);
        }
    }

    private int syncGmail(MailConnection conn) {
        String cursor = conn.getSyncCursor();
        int processed = 0;

        if (cursor == null || cursor.isBlank()) {
            String historyId = gmailProfileHistoryId(conn);
            String listUrl = gmailBase() + "/users/me/messages?maxResults=50&q="
                    + urlEncode("has:attachment newer_than:7d");
            JsonNode page = getJson(conn, listUrl);
            processed += processGmailMessageList(conn, page);
            conn.setSyncCursor(historyId);
            connectionRepo.save(conn);
        } else {
            String nextCursor = cursor;
            String pageToken = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = gmailBase() + "/users/me/history?startHistoryId=" + urlEncode(cursor)
                        + "&historyTypes=messageAdded&maxResults=100"
                        + (pageToken != null ? "&pageToken=" + urlEncode(pageToken) : "");
                JsonNode response;
                try {
                    response = getJson(conn, url);
                } catch (HttpClientErrorException.NotFound e) {
                    log.info("Gmail history expired for connection={}; falling back to recent sync", conn.getId());
                    String freshHistoryId = gmailProfileHistoryId(conn);
                    conn.setSyncCursor(freshHistoryId);
                    connectionRepo.save(conn);

                    String listUrl = gmailBase() + "/users/me/messages?maxResults=50&q="
                            + urlEncode("has:attachment newer_than:7d");
                    processed += processGmailMessageList(conn, getJson(conn, listUrl));
                    break;
                }

                processed += processGmailHistory(conn, response);
                if (response.hasNonNull("historyId")) {
                    nextCursor = response.get("historyId").asText();
                    // Checkpoint cursor per page
                    conn.setSyncCursor(nextCursor);
                    connectionRepo.save(conn);
                }
                pageToken = response.path("nextPageToken").asText(null);
                if (pageToken == null || pageToken.isBlank()) break;
            }
            conn.setSyncCursor(nextCursor);
            connectionRepo.save(conn);
        }

        return processed;
    }

    private String gmailProfileHistoryId(MailConnection conn) {
        JsonNode profile = getJson(conn, gmailBase() + "/users/me/profile");
        return profile.path("historyId").asText(null);
    }

    private int processGmailMessageList(MailConnection conn, JsonNode page) {
        int processed = 0;
        JsonNode messages = page.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                processed += processGmailMessage(conn, message.path("id").asText());
            }
        }
        return processed;
    }

    private int processGmailHistory(MailConnection conn, JsonNode response) {
        int processed = 0;
        Set<String> seen = new HashSet<>();
        JsonNode history = response.path("history");
        if (!history.isArray()) return 0;
        for (JsonNode entry : history) {
            JsonNode messages = entry.path("messages");
            if (!messages.isArray()) continue;
            for (JsonNode message : messages) {
                String id = message.path("id").asText();
                if (id.isBlank() || !seen.add(id)) continue;
                processed += processGmailMessage(conn, id);
            }
        }
        return processed;
    }

    private int processGmailMessage(MailConnection conn, String messageId) {
        JsonNode message = getJson(conn,
                gmailBase() + "/users/me/messages/" + urlEncode(messageId) + "?format=full");
        JsonNode payload = message.path("payload");
        String sender = parseAddress(header(payload, "From"));
        String senderName = parseDisplayName(header(payload, "From"));
        String subject = header(payload, "Subject");
        String internetMessageId = header(payload, "Message-ID");

        List<JsonNode> attachmentParts = new ArrayList<>();
        collectGmailAttachmentParts(payload, attachmentParts);

        int accepted = 0;
        for (JsonNode part : attachmentParts) {
            try {
                String filename = part.path("filename").asText(null);
                String attachmentId = part.path("body").path("attachmentId").asText(null);
                if (filename == null || attachmentId == null) continue;

                JsonNode attachment = getJson(conn,
                        gmailBase() + "/users/me/messages/" + urlEncode(messageId)
                                + "/attachments/" + urlEncode(attachmentId));
                String data = attachment.path("data").asText(null);
                if (data == null) continue;

                byte[] bytes;
                try {
                    bytes = Base64.getUrlDecoder().decode(data);
                } catch (IllegalArgumentException e) {
                    bytes = Base64.getDecoder().decode(data);
                }
                if (bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES) {
                    log.debug("Skipping oversized/empty Gmail attachment '{}' ({} bytes)",
                            filename, bytes.length);
                    continue;
                }

                ingestionService.ingestMailAttachment(
                        conn.getOrganizationId(), conn.getId(),
                        messageId + ":" + accepted, internetMessageId,
                        sender, senderName, subject, "GMAIL_OAUTH", bytes, filename);
                accepted++;
            } catch (Exception e) {
                log.warn("Failed to ingest Gmail attachment for message={}: {}",
                        messageId, e.getMessage());
            }
        }
        return accepted;
    }

    private void collectGmailAttachmentParts(JsonNode part, List<JsonNode> out) {
        if (part == null || part.isMissingNode()) return;
        String filename = part.path("filename").asText("");
        if (!filename.isBlank() && part.path("body").hasNonNull("attachmentId")) {
            out.add(part);
            return;
        }
        JsonNode parts = part.path("parts");
        if (parts.isArray()) {
            for (JsonNode child : parts) collectGmailAttachmentParts(child, out);
        }
    }

    private JsonNode getJson(MailConnection conn, String url) {
        String body = exchangeWithToken(conn, url, false);
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON response from Gmail API", e);
        }
    }

    private String exchangeWithToken(MailConnection conn, String url, boolean forceRefresh) {
        String token = accessToken(conn, forceRefresh);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> response = restTemplate().exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getBody() != null ? response.getBody() : "{}";
        } catch (HttpClientErrorException.Unauthorized e) {
            if (!forceRefresh && hasRefreshToken(conn)) {
                return exchangeWithToken(conn, url, true);
            }
            throw e;
        }
    }

    private String accessToken(MailConnection conn, boolean forceRefresh) {
        Credentials credentials = readCredentials(conn);
        long now = Instant.now().toEpochMilli();

        if (!forceRefresh && credentials.accessToken() != null
                && credentials.expiresAt() > now + 60_000) {
            return credentials.accessToken();
        }

        if (credentials.refreshToken() == null || credentials.refreshToken().isBlank()) {
            if (credentials.accessToken() != null && !forceRefresh) {
                return credentials.accessToken();
            }
            throw new IllegalStateException(
                    "No refresh token configured for connection " + conn.getId());
        }

        TokenResponse refreshed = refreshGoogleToken(credentials.refreshToken());

        String effectiveRefreshToken = (refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank())
                ? refreshed.refreshToken()
                : credentials.refreshToken();

        Credentials updated = new Credentials(refreshed.accessToken(),
                effectiveRefreshToken, refreshed.expiresAt());
        storeCredentials(conn, updated);
        return refreshed.accessToken();
    }

    private boolean hasRefreshToken(MailConnection conn) {
        try {
            String token = readCredentials(conn).refreshToken();
            return token != null && !token.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private record Credentials(String accessToken, String refreshToken, long expiresAt) {}
    private record TokenResponse(String accessToken, String refreshToken, long expiresAt) {}

    private Credentials readCredentials(MailConnection conn) {
        if (conn.getOauthSecretRef() == null || conn.getOauthSecretRef().isBlank()) {
            return new Credentials(null, null, 0);
        }
        try {
            String json = CryptoUtil.decrypt(conn.getOauthSecretRef(),
                    props.getCrypto().getSecretKey());
            if (!json.trim().startsWith("{")) {
                return new Credentials(json.trim(), null, 0);
            }
            JsonNode node = objectMapper.readTree(json);
            return new Credentials(
                    node.path("accessToken").asText(null),
                    node.path("refreshToken").asText(null),
                    node.path("expiresAt").asLong(0));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt OAuth credentials for connection "
                    + conn.getId(), e);
        }
    }

    private void storeCredentials(MailConnection conn, Credentials credentials) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("accessToken", credentials.accessToken());
            values.put("refreshToken", credentials.refreshToken());
            values.put("expiresAt", credentials.expiresAt());
            String json = objectMapper.writeValueAsString(values);
            conn.setOauthSecretRef(CryptoUtil.encrypt(json, props.getCrypto().getSecretKey()));
            connectionRepo.save(conn);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store refreshed OAuth credentials", e);
        }
    }

    private TokenResponse refreshGoogleToken(String refreshToken) {
        HireFlowProperties.OAuthProperties oauth = props.getOauth();
        if (oauth.getGoogleClientId().isBlank() || oauth.getGoogleClientSecret().isBlank()) {
            throw new IllegalStateException(
                    "Google OAuth client id/secret are not configured (hireflow.oauth.google-*)");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", oauth.getGoogleClientId());
        form.add("client_secret", oauth.getGoogleClientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        return postToken(oauth.getGoogleTokenUri(), form);
    }

    private TokenResponse postToken(String uri, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate().postForEntity(
                uri, new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode node = objectMapper.readTree(response.getBody() != null
                    ? response.getBody() : "{}");
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IllegalStateException("Token endpoint returned no access_token");
            }
            String newRefreshToken = node.path("refresh_token").asText(null);
            long expiresIn = node.path("expires_in").asLong(3600);
            return new TokenResponse(accessToken, newRefreshToken,
                    Instant.now().plusSeconds(expiresIn).toEpochMilli());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse OAuth token response", e);
        }
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    private String gmailBase() {
        return trimTrailingSlash(props.getOauth().getGmailBaseUrl());
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String header(JsonNode payload, String name) {
        JsonNode headers = payload.path("headers");
        if (!headers.isArray()) return null;
        for (JsonNode header : headers) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText(null);
            }
        }
        return null;
    }

    private String parseAddress(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    private String parseDisplayName(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        if (start > 0) {
            return from.substring(0, start).replace("\"", "").trim();
        }
        return null;
    }
}
