package com.kartgen.hireflow.mail.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.mail.model.MailConnection;
import com.kartgen.hireflow.mail.model.MailConnectionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail-connections")
@RequiredArgsConstructor
public class MailConnectionController {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
            "IMAP_APP_PASSWORD", "GMAIL_OAUTH", "MS_GRAPH_OAUTH");

    private final MailConnectionRepository connectionRepo;
    private final UniversalImapSyncService imapSync;
    private final OAuthMailSyncAdapter oauthSync;
    private final HireFlowProperties props;
    private final ObjectMapper objectMapper;

    public record CreateMailConnectionRequest(
            @NotBlank String provider,
            @NotBlank String mailboxAddress,
            String imapHost,
            Integer imapPort,
            String folderFilter,
            String keywordFilterJson,
            String appPassword,
            String refreshToken,
            String accessToken,
            Long accessTokenExpiresAt
    ) {}

    public record MailConnectionDto(
            UUID id, String provider, String mailboxAddress, String imapHost,
            String folderFilter, String status, String lastSuccessAt, Instant createdAt
    ) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateMailConnectionRequest req) {
        UUID orgId = TenantContext.getOrganizationId();
        String provider = req.provider().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported provider. Use IMAP_APP_PASSWORD, GMAIL_OAUTH or MS_GRAPH_OAUTH"));
        }
        String mailbox = req.mailboxAddress().trim().toLowerCase(Locale.ROOT);
        if (!mailbox.contains("@")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid mailbox address"));
        }

        String encryptedSecret;
        try {
            if ("IMAP_APP_PASSWORD".equals(provider)) {
                if (req.imapHost() == null || req.imapHost().isBlank()
                        || req.appPassword() == null || req.appPassword().isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "IMAP connections require imapHost and appPassword"));
                }
                encryptedSecret = CryptoUtil.encrypt(req.appPassword(),
                        props.getCrypto().getSecretKey());
            } else {
                if (isBlank(req.refreshToken()) && isBlank(req.accessToken())) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "OAuth connections require refreshToken or accessToken"));
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("accessToken", req.accessToken());
                values.put("refreshToken", req.refreshToken());
                values.put("expiresAt", req.accessTokenExpiresAt() != null
                        ? req.accessTokenExpiresAt() : 0L);
                encryptedSecret = CryptoUtil.encrypt(
                        objectMapper.writeValueAsString(values),
                        props.getCrypto().getSecretKey());
            }
        } catch (Exception e) {
            log.error("Failed to encrypt mail credentials: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error",
                    "Unable to secure mail credentials"));
        }

        MailConnection conn = MailConnection.builder()
                .organizationId(orgId)
                .provider(provider)
                .mailboxAddress(mailbox)
                .imapHost(req.imapHost())
                .imapPort(req.imapPort() != null ? req.imapPort() : 993)
                .folderFilter(req.folderFilter() != null ? req.folderFilter() : "INBOX")
                .keywordFilterJson(req.keywordFilterJson())
                .oauthSecretRef(encryptedSecret)
                .permissionScope("IMAP_APP_PASSWORD".equals(provider)
                        ? "mail.read" : "mail.read offline_access")
                .status("ACTIVE")
                .createdBy(TenantContext.getUserId())
                .build();
        connectionRepo.save(conn);

        log.info("Created mail connection id={} provider={} org={}",
                conn.getId(), provider, orgId);
        return ResponseEntity.ok(toDto(conn));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        UUID orgId = TenantContext.getOrganizationId();
        List<MailConnectionDto> dtos = connectionRepo.findByOrganizationId(orgId)
                .stream().map(this::toDto).toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{id}/sync-now")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER','MANAGER')")
    public ResponseEntity<?> syncNow(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        MailConnection conn = connectionRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Connection not found: " + id));

        if ("PAUSED".equals(conn.getStatus()) || "REAUTH".equals(conn.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Connection is " + conn.getStatus() + " and must be reconnected first"));
        }

        SyncResult result;
        switch (conn.getProvider()) {
            case "IMAP_APP_PASSWORD" -> result = imapSync.syncConnection(conn);
            case "GMAIL_OAUTH", "MS_GRAPH_OAUTH" -> result = oauthSync.syncConnection(conn);
            default -> {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Cannot sync provider: " + conn.getProvider()));
            }
        }

        MailConnection refreshed = connectionRepo.findByIdAndOrganizationId(id, orgId)
                .orElse(conn);
        return ResponseEntity.ok(Map.of(
                "status", result.status(),
                "connectionId", id,
                "messagesScanned", result.messagesScanned(),
                "attachmentsIngested", result.attachmentsIngested(),
                "summary", result.summary() != null ? result.summary() : "",
                "error", result.errorMessage() != null ? result.errorMessage() : "",
                "lastSuccessAt", refreshed.getLastSuccessAt() != null
                        ? refreshed.getLastSuccessAt().toString() : ""));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        UUID orgId = TenantContext.getOrganizationId();
        MailConnection conn = connectionRepo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("Connection not found: " + id));
        conn.setStatus("PAUSED");
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "PAUSED", "connectionId", id));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private MailConnectionDto toDto(MailConnection c) {
        return new MailConnectionDto(c.getId(), c.getProvider(), c.getMailboxAddress(),
                c.getImapHost(), c.getFolderFilter(), c.getStatus(),
                c.getLastSuccessAt() != null ? c.getLastSuccessAt().toString() : null,
                c.getCreatedAt());
    }
}
