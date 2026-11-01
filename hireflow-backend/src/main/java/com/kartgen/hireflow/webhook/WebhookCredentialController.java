package com.kartgen.hireflow.webhook;

import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.config.HireFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Lifecycle for per-organization webhook credentials. Requires an
 * authenticated OWNER or ADMIN of the organization resolved from the
 * verified TenantContext (never a caller-supplied tenant id).
 *
 * The raw secret is returned exactly once per create/rotate and is stored
 * only in encrypted form. It is never returned by list endpoints and is
 * never logged.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhook-credentials")
@RequiredArgsConstructor
public class WebhookCredentialController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebhookCredentialRepository credentialRepo;
    private final HireFlowProperties props;

    public record CreatedCredential(String keyId, String secret) {}

    public record CredentialDto(String keyId, String status, String createdAt,
                                String revokedAt, UUID createdBy) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> create() {
        UUID orgId = requireOrg();
        String secret = generateSecret();
        WebhookCredential credential = WebhookCredential.builder()
                .organizationId(orgId)
                .keyId(generateKeyId())
                .encryptedSecret(CryptoUtil.encrypt(secret, cryptoKey()))
                .status(WebhookCredential.STATUS_ACTIVE)
                .createdBy(TenantContext.getUserId())
                .build();
        credentialRepo.save(credential);
        log.info("Created webhook credential keyId={} org={}", credential.getKeyId(), orgId);
        return ResponseEntity.ok(new CreatedCredential(credential.getKeyId(), secret));
    }

    /** Revokes the current credential and issues a replacement in one step. */
    @PostMapping("/{keyId}/rotate")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> rotate(@PathVariable String keyId) {
        UUID orgId = requireOrg();
        WebhookCredential existing = credentialRepo.findByKeyId(keyId).orElse(null);
        if (existing == null || !existing.getOrganizationId().equals(orgId)) {
            return ResponseEntity.notFound().build();
        }
        if (!WebhookCredential.STATUS_REVOKED.equals(existing.getStatus())) {
            existing.setStatus(WebhookCredential.STATUS_REVOKED);
            existing.setRevokedAt(Instant.now());
            existing.setRevokedBy(TenantContext.getUserId());
            credentialRepo.save(existing);
        }

        String secret = generateSecret();
        WebhookCredential replacement = WebhookCredential.builder()
                .organizationId(orgId)
                .keyId(generateKeyId())
                .encryptedSecret(CryptoUtil.encrypt(secret, cryptoKey()))
                .status(WebhookCredential.STATUS_ACTIVE)
                .createdBy(TenantContext.getUserId())
                .build();
        credentialRepo.save(replacement);
        log.info("Rotated webhook credential oldKeyId={} newKeyId={} org={}",
                keyId, replacement.getKeyId(), orgId);
        return ResponseEntity.ok(new CreatedCredential(replacement.getKeyId(), secret));
    }

    @PostMapping("/{keyId}/revoke")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> revoke(@PathVariable String keyId) {
        UUID orgId = requireOrg();
        WebhookCredential credential = credentialRepo.findByKeyId(keyId).orElse(null);
        if (credential == null || !credential.getOrganizationId().equals(orgId)) {
            return ResponseEntity.notFound().build();
        }
        credential.setStatus(WebhookCredential.STATUS_REVOKED);
        credential.setRevokedAt(Instant.now());
        credential.setRevokedBy(TenantContext.getUserId());
        credentialRepo.save(credential);
        log.info("Revoked webhook credential keyId={} org={}", keyId, orgId);
        return ResponseEntity.ok(Map.of("keyId", keyId, "status", WebhookCredential.STATUS_REVOKED));
    }

    /** Lists credential metadata only - never the secret. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public ResponseEntity<?> list() {
        UUID orgId = requireOrg();
        List<CredentialDto> dtos = credentialRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream()
                .map(c -> new CredentialDto(
                        c.getKeyId(), c.getStatus(),
                        c.getCreatedAt().toString(),
                        c.getRevokedAt() == null ? null : c.getRevokedAt().toString(),
                        c.getCreatedBy()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    private UUID requireOrg() {
        UUID orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new IllegalStateException("Organization context missing");
        }
        return orgId;
    }

    private String cryptoKey() {
        return props.getCrypto().getSecretKey();
    }

    private String generateSecret() {
        // 32 bytes = 256 bits of entropy, base64url encoded (43 chars).
        byte[] raw = new byte[32];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private String generateKeyId() {
        byte[] raw = new byte[8];
        SECURE_RANDOM.nextBytes(raw);
        StringBuilder hex = new StringBuilder("whk_");
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
