package com.kartgen.hireflow.document;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Serves locally-stored resume files via HMAC-signed tokens.
 * Token format: base64url(objectKey:expiryEpochSeconds).hmacHex
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/local-s3")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hireflow.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalS3Controller {

    private final StoragePort storagePort;
    private final HireFlowProperties props;

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> serveFile(@PathVariable String token) {
        int dotIndex = token.lastIndexOf('.');
        if (dotIndex < 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String payloadB64 = token.substring(0, dotIndex);
        String receivedHmac = token.substring(dotIndex + 1);

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid base64 in local-s3 token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String expectedHmac = HashUtil.hmacSha256(payload, props.getStorage().getLocalUrlSecret());
        if (!MessageDigest.isEqual(expectedHmac.getBytes(StandardCharsets.US_ASCII),
                receivedHmac.getBytes(StandardCharsets.US_ASCII))) {
            log.warn("HMAC mismatch for local-s3 token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        int colonIndex = payload.lastIndexOf(':');
        if (colonIndex < 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String objectKey = payload.substring(0, colonIndex);
        long expiry;
        try {
            expiry = Long.parseLong(payload.substring(colonIndex + 1));
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (System.currentTimeMillis() / 1000 > expiry) {
            log.debug("Expired local-s3 token for: {}", objectKey);
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        try {
            byte[] data = storagePort.retrieve(objectKey);
            MediaType mediaType = objectKey.endsWith(".pdf")
                    ? MediaType.APPLICATION_PDF
                    : MediaType.APPLICATION_OCTET_STREAM;

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(data.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                    .body(data);
        } catch (RuntimeException e) {
            log.error("Failed to retrieve file for local-s3 token: {}", objectKey, e);
            return ResponseEntity.notFound().build();
        }
    }
}
