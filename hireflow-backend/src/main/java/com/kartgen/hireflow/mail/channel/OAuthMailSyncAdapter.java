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
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Channel 3: OAuth2 mail sync for Gmail and Microsoft Graph.
 *
 * Stores an encrypted JSON credential blob in mail_connections.oauth_secret_ref:
 * {"accessToken":"...","refreshToken":"...","expiresAt":<epochMillis>}
 * and a historyId (Gmail) or delta link (Graph) in sync_cursor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthMailSyncAdapter {

    private static final int MAX_PAGES = 10;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private final MailConnectionRepository connectionRepo;
    private final MessageIngestionService ingestionService;
    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;

    public SyncResult syncConnection(MailConnection conn) {
        try {
            int processed = switch (conn.getProvider()) {
                case "GMAIL_OAUTH" -> syncGmail(conn);
                case "MS_GRAPH_OAUTH" -> syncMsGraph(conn);
                default -> {
                    log.warn("OAuthMailSyncAdapter called for unsupported provider: {}", conn.getProvider());
                    yield 0;
                }
            };
            String summary = String.format("OAuth sync successful: %d resume attachment(s) ingested.", processed);
            return new SyncResult("ACTIVE", processed, processed, summary, null);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            String err = "OAuth authorization rejected (" + e.getStatusCode() + "); token expired or revoked. Please re-authenticate.";
            log.warn("OAuth authorization rejected for connection={} ({})", conn.getId(), e.getStatusCode());
            conn.setStatus("REAUTH");
            connectionRepo.save(conn);
            return new SyncResult("REAUTH", 0, 0, null, err);
        } catch (Exception e) {
            String err = "OAuth sync error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("OAuth sync failed for connection={}: {}", conn.getId(), err, e);
            conn.setStatus("ERROR");
            connectionRepo.save(conn);
            return new SyncResult("ERROR", 0, 0, null, err);
        }
    }

    // ─────────────────────────── Gmail ───────────────────────────

    private int syncGmail(MailConnection conn) {
        String cursor = conn.getSyncCursor();
        int processed = 0;

        if (cursor == null || cursor.isBlank()) {
            String historyId = gmailProfileHistoryId(conn);
            String listUrl = gmailBase() + "/users/me/messages?maxResults=50&q="
                    + urlEncode("has:attachment newer_than:7d");
            JsonNode page = getJson(conn, "gmail", listUrl);
            processed += processGmailMessageList(conn, page);
            conn.setSyncCursor(historyId);
        } else {
            String nextCursor = cursor;
            String pageToken = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = gmailBase() + "/users/me/history?startHistoryId=" + urlEncode(cursor)
                        + "&historyTypes=messageAdded&maxResults=100"
                        + (pageToken != null ? "&pageToken=" + urlEncode(pageToken) : "");
                JsonNode response;
                try {
                    response = getJson(conn, "gmail", url);
                } catch (HttpClientErrorException.NotFound e) {
                    log.info("Gmail history expired for connection={}; falling back to recent sync",
                            conn.getId());
                    conn.setSyncCursor(gmailProfileHistoryId(conn));
                    String listUrl = gmailBase() + "/users/me/messages?maxResults=50&q="
                            + urlEncode("has:attachment newer_than:7d");
                    processed += processGmailMessageList(conn, getJson(conn, "gmail", listUrl));
                    break;
                }
                processed += processGmailHistory(conn, response);
                if (response.hasNonNull("historyId")) {
                    nextCursor = response.get("historyId").asText();
                }
                pageToken = response.path("nextPageToken").asText(null);
                if (pageToken == null || pageToken.isBlank()) break;
            }
            conn.setSyncCursor(nextCursor);
        }

        conn.setLastSuccessAt(Instant.now());
        conn.setStatus("ACTIVE");
        connectionRepo.save(conn);
        log.info("Gmail sync completed: {} attachments, cursor={}, connection={}",
                processed, conn.getSyncCursor(), conn.getId());
        return processed;
    }

    private String gmailProfileHistoryId(MailConnection conn) {
        JsonNode profile = getJson(conn, "gmail", gmailBase() + "/users/me/profile");
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
        JsonNode message = getJson(conn, "gmail",
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

                JsonNode attachment = getJson(conn, "gmail",
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

    // ─────────────────────────── Microsoft Graph ───────────────────────────

    private int syncMsGraph(MailConnection conn) {
        String deltaLink = conn.getSyncCursor();
        String nextLink = deltaLink != null && deltaLink.startsWith("http")
                ? deltaLink
                : graphBase() + "/me/mailFolders/inbox/messages/delta"
                + "?$select=id,subject,from,receivedDateTime,hasAttachments,internetMessageId"
                + "&$top=50";

        int processed = 0;
        String finalDeltaLink = null;

        for (int page = 0; page < MAX_PAGES && nextLink != null; page++) {
            JsonNode response;
            try {
                response = getJson(conn, "graph", nextLink);
            } catch (HttpClientErrorException.Gone e) {
                log.info("Graph delta token expired for connection={}; restarting delta", conn.getId());
                if (conn.getSyncCursor() != null) {
                    conn.setSyncCursor(null);
                    return syncMsGraph(conn);
                }
                return processed;
            }

            JsonNode messages = response.path("value");
            if (messages.isArray()) {
                for (JsonNode message : messages) {
                    if (message.path("hasAttachments").asBoolean(false)) {
                        processed += processGraphMessage(conn, message);
                    }
                }
            }
            nextLink = response.path("@odata.nextLink").asText(null);
            finalDeltaLink = response.path("@odata.deltaLink").asText(null);
        }

        if (finalDeltaLink != null) {
            conn.setSyncCursor(finalDeltaLink);
        }
        conn.setLastSuccessAt(Instant.now());
        conn.setStatus("ACTIVE");
        connectionRepo.save(conn);
        log.info("MS Graph sync completed: {} attachments, connection={}", processed, conn.getId());
        return processed;
    }

    private int processGraphMessage(MailConnection conn, JsonNode message) {
        String messageId = message.path("id").asText();
        String subject = message.path("subject").asText(null);
        String internetMessageId = message.path("internetMessageId").asText(null);
        JsonNode from = message.path("from").path("emailAddress");
        String sender = from.path("address").asText(null);
        String senderName = from.path("name").asText(null);

        String url = graphBase() + "/me/messages/" + urlEncode(messageId)
                + "/attachments?$select=id,name,contentType,contentBytes";
        JsonNode response = getJson(conn, "graph", url);
        JsonNode attachments = response.path("value");

        int accepted = 0;
        if (attachments.isArray()) {
            for (JsonNode attachment : attachments) {
                try {
                    String name = attachment.path("name").asText(null);
                    String contentBytes = attachment.path("contentBytes").asText(null);
                    if (name == null || contentBytes == null) continue;

                    byte[] bytes = Base64.getDecoder().decode(contentBytes);
                    if (bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES) {
                        log.debug("Skipping oversized/empty Graph attachment '{}' ({} bytes)",
                                name, bytes.length);
                        continue;
                    }

                    ingestionService.ingestMailAttachment(
                            conn.getOrganizationId(), conn.getId(),
                            messageId + ":" + accepted, internetMessageId,
                            sender, senderName, subject, "MS_GRAPH_OAUTH", bytes, name);
                    accepted++;
                } catch (Exception e) {
                    log.warn("Failed to ingest Graph attachment for message={}: {}",
                            messageId, e.getMessage());
                }
            }
        }
        return accepted;
    }

    // ─────────────────────────── Token handling ───────────────────────────

    private JsonNode getJson(MailConnection conn, String provider, String url) {
        String body = exchangeWithToken(conn, provider, url, false);
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON response from " + provider, e);
        }
    }

    private String exchangeWithToken(MailConnection conn, String provider, String url,
                                     boolean forceRefresh) {
        String token = accessToken(conn, provider, forceRefresh);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        try {
            ResponseEntity<String> response = restTemplate().exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getBody() != null ? response.getBody() : "{}";
        } catch (HttpClientErrorException.Unauthorized e) {
            if (!forceRefresh && hasRefreshToken(conn)) {
                return exchangeWithToken(conn, provider, url, true);
            }
            throw e;
        }
    }

    private String accessToken(MailConnection conn, String provider, boolean forceRefresh) {
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

        TokenResponse refreshed = "gmail".equals(provider)
                ? refreshGoogleToken(credentials.refreshToken())
                : refreshMicrosoftToken(credentials.refreshToken());

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
                // Legacy raw access token.
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

    private TokenResponse refreshMicrosoftToken(String refreshToken) {
        HireFlowProperties.OAuthProperties oauth = props.getOauth();
        if (oauth.getMicrosoftClientId().isBlank()) {
            throw new IllegalStateException(
                    "Microsoft OAuth client id is not configured (hireflow.oauth.microsoft-client-id)");
        }
        String uri = oauth.getMicrosoftTokenUriTemplate()
                .replace("{tenant}", oauth.getMicrosoftTenant());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", oauth.getMicrosoftClientId());
        if (!oauth.getMicrosoftClientSecret().isBlank()) {
            form.add("client_secret", oauth.getMicrosoftClientSecret());
        }
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        form.add("scope", "https://graph.microsoft.com/Mail.Read offline_access");
        return postToken(uri, form);
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
            long ttl = expiresIn;
            return new TokenResponse(accessToken, newRefreshToken,
                    Instant.now().plusSeconds(ttl).toEpochMilli());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse OAuth token response", e);
        }
    }

    // ─────────────────────────── Utilities ───────────────────────────

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    private String gmailBase() {
        return trimTrailingSlash(props.getOauth().getGmailBaseUrl());
    }

    private String graphBase() {
        return trimTrailingSlash(props.getOauth().getGraphBaseUrl());
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
