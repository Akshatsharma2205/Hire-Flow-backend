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
public class MicrosoftMailAdapter implements MailSyncPort {

    private static final int MAX_PAGES = 10;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private final MailConnectionRepository connectionRepo;
    private final MessageIngestionService ingestionService;
    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String provider) {
        return "MS_GRAPH_OAUTH".equalsIgnoreCase(provider);
    }

    @Override
    public SyncResult syncConnection(MailConnection conn) {
        conn.setLastAttemptAt(Instant.now());
        try {
            int processed = syncMsGraph(conn);
            conn.setInitialSyncCompleted(true);
            conn.setConsecutiveFailures(0);
            conn.setSyncStatusDetails("Microsoft Graph sync completed successfully");
            conn.setLastSuccessAt(Instant.now());
            conn.setStatus("ACTIVE");
            connectionRepo.save(conn);

            String summary = String.format("MS Graph sync successful: %d resume attachment(s) ingested.", processed);
            return new SyncResult("ACTIVE", processed, processed, summary, null);
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Microsoft Graph API rate limit reached (HTTP 429) for connection={}", conn.getId());
            conn.setSyncStatusDetails("Rate limited (HTTP 429). Backing off.");
            connectionRepo.save(conn);
            return new SyncResult("ACTIVE", 0, 0, "Rate limited (HTTP 429). Retrying on next schedule.", null);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            String err = "MS Graph OAuth rejected (" + e.getStatusCode() + "); token expired or revoked. Please re-authenticate.";
            log.warn("MS Graph authorization rejected for connection={} ({})", conn.getId(), e.getStatusCode());
            conn.setStatus("REAUTH");
            conn.setConsecutiveFailures(conn.getConsecutiveFailures() != null ? conn.getConsecutiveFailures() + 1 : 1);
            conn.setSyncStatusDetails(err);
            connectionRepo.save(conn);
            return new SyncResult("REAUTH", 0, 0, null, err);
        } catch (Exception e) {
            String err = "MS Graph sync error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.error("MS Graph sync failed for connection={}: {}", conn.getId(), err, e);
            conn.setStatus("ERROR");
            conn.setConsecutiveFailures(conn.getConsecutiveFailures() != null ? conn.getConsecutiveFailures() + 1 : 1);
            conn.setSyncStatusDetails(err);
            connectionRepo.save(conn);
            return new SyncResult("ERROR", 0, 0, null, err);
        }
    }

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
                response = getJson(conn, nextLink);
            } catch (HttpClientErrorException.Gone e) {
                log.info("Graph delta token expired for connection={}; restarting delta", conn.getId());
                if (conn.getSyncCursor() != null) {
                    conn.setSyncCursor(null);
                    connectionRepo.save(conn);
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

            // Per-page checkpointing
            if (nextLink != null) {
                conn.setSyncCursor(nextLink);
                connectionRepo.save(conn);
            }
        }

        if (finalDeltaLink != null) {
            conn.setSyncCursor(finalDeltaLink);
            connectionRepo.save(conn);
        }

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
        JsonNode response = getJson(conn, url);
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

    private JsonNode getJson(MailConnection conn, String url) {
        String body = exchangeWithToken(conn, url, false);
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON response from Microsoft Graph", e);
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

        TokenResponse refreshed = refreshMicrosoftToken(credentials.refreshToken());

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

    private String graphBase() {
        return trimTrailingSlash(props.getOauth().getGraphBaseUrl());
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
