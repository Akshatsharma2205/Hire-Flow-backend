package com.kartgen.hireflow.security;

import java.util.Locale;

/**
 * Validated, canonical Content-Type used by the v2 webhook signature.
 *
 * <p>Only the media types accepted by the forwarding endpoint are allowed.
 * For multipart requests the boundary is part of the signed canonical value;
 * for JSON the canonical value is exactly {@code application/json}, so
 * optional charset parameters do not weaken or destabilise the contract.</p>
 *
 * <p>Canonical multipart form (always quoted, with backslash escaping):</p>
 * <pre>multipart/form-data; boundary="&lt;boundary&gt;"</pre>
 */
final class WebhookContentType {

    static final String JSON = "application/json";
    static final String MULTIPART = "multipart/form-data";

    private final String mediaType;
    private final String boundary;
    private final String canonical;

    private WebhookContentType(String mediaType, String boundary, String canonical) {
        this.mediaType = mediaType;
        this.boundary = boundary;
        this.canonical = canonical;
    }

    static WebhookContentType parse(String rawContentType) {
        try {
            HeaderParameters.Value value = HeaderParameters.parse(rawContentType);
            String mediaType = value.token().toLowerCase(Locale.ROOT);
            if (JSON.equals(mediaType)) {
                return new WebhookContentType(JSON, null, JSON);
            }
            if (MULTIPART.equals(mediaType)) {
                String boundary = value.parameter("boundary");
                validateBoundary(boundary);
                return new WebhookContentType(MULTIPART, boundary,
                        MULTIPART + "; boundary=\"" + escapeQuoted(boundary) + "\"");
            }
            throw new InvalidContentTypeException("Unsupported webhook media type");
        } catch (InvalidContentTypeException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            String reason = e.getMessage() == null ? "malformed value" : e.getMessage();
            throw new InvalidContentTypeException("Malformed webhook Content-Type: " + reason, e);
        }
    }

    String mediaType() {
        return mediaType;
    }

    String boundary() {
        return boundary;
    }

    String canonical() {
        return canonical;
    }

    boolean isMultipart() {
        return MULTIPART.equals(mediaType);
    }

    private static void validateBoundary(String boundary) {
        if (boundary == null || boundary.isEmpty()) {
            throw new InvalidContentTypeException("Multipart boundary is required");
        }
        if (boundary.length() > 70) {
            throw new InvalidContentTypeException("Multipart boundary is too long");
        }
        for (int i = 0; i < boundary.length(); i++) {
            char c = boundary.charAt(i);
            if (!isBoundaryChar(c)) {
                throw new InvalidContentTypeException("Multipart boundary contains an invalid character");
            }
        }
        if (boundary.charAt(boundary.length() - 1) == ' ') {
            throw new InvalidContentTypeException("Multipart boundary must not end with a space");
        }
    }

    private static boolean isBoundaryChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= 'a' && c <= 'z') return true;
        return "'()+_,-./:=? ".indexOf(c) >= 0;
    }

    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static final class InvalidContentTypeException extends RuntimeException {
        InvalidContentTypeException(String message) {
            super(message);
        }

        InvalidContentTypeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
