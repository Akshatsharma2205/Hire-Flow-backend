package com.kartgen.hireflow.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Independent implementation of the documented webhook signing contract
 * (docs/webhook-security.md). Deliberately does NOT reuse the production
 * verifier's code, so the integration tests validate the contract itself.
 */
public final class WebhookTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    private WebhookTestSupport() {}

    public static String sha256Hex(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical v2 signing string - must match docs/webhook-security.md exactly. */
    public static String canonicalString(String organizationId, String keyId, String timestamp, String nonce,
                                         String method, String requestPath, String idempotencyKey,
                                         String canonicalContentType, String bodySha256Hex) {
        return String.join("\n",
                "v2", organizationId, keyId, timestamp, nonce, method, requestPath,
                idempotencyKey, canonicalContentType, bodySha256Hex);
    }

    /** Canonical JSON Content-Type used by the v2 signature. */
    public static String canonicalJsonContentType() {
        return "application/json";
    }

    /** Canonical multipart Content-Type used by the v2 signature. */
    public static String canonicalMultipartContentType(String boundary) {
        return "multipart/form-data; boundary=\"" + quote(boundary) + "\"";
    }

    /** Raw (non-canonical) multipart Content-Type header for the HTTP request. */
    public static String rawMultipartContentType(String boundary) {
        return "multipart/form-data; boundary=" + boundary;
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String randomNonce() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(NONCE_ALPHABET.charAt(RANDOM.nextInt(NONCE_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String randomIdempotencyKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public record FormPart(String name, String filename, String contentType, byte[] content) {}

    /**
     * A part with caller-supplied header block content (without the terminating
     * blank line). Used for malformed/adversarial multipart fixtures.
     */
    public record RawPart(String headers, byte[] content) {}

    /** Encodes a raw multipart/form-data body; signature covers these exact bytes. */
    public static byte[] multipartBody(String boundary, List<FormPart> parts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (FormPart part : parts) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
                StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"")
                        .append(part.name()).append("\"");
                if (part.filename() != null) {
                    disposition.append("; filename=\"").append(part.filename()).append("\"");
                }
                disposition.append("\r\n");
                out.write(disposition.toString().getBytes(StandardCharsets.UTF_8));
                if (part.contentType() != null) {
                    out.write(("Content-Type: " + part.contentType() + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                }
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(part.content());
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encodes a raw multipart body from exact per-part header blocks. */
    public static byte[] multipartBodyFromRawParts(String boundary, List<RawPart> parts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (RawPart part : parts) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write((part.headers() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(part.content());
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
