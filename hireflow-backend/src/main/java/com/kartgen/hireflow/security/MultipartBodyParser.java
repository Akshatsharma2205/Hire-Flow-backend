package com.kartgen.hireflow.security;

import org.springframework.web.multipart.MultipartException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict, bounded multipart/form-data parser over an already-buffered body.
 *
 * <p>Correctness properties required by the webhook contract:</p>
 * <ul>
 *   <li>the first boundary must be at byte zero and each subsequent boundary
 *       must occur at an RFC 2046 delimiter position ({@code CRLF--boundary}
 *       followed by CRLF or {@code --}); boundary-like bytes inside part
 *       content are preserved verbatim,</li>
 *   <li>headers end at the exact {@code CRLFCRLF} separator and part content
 *       starts immediately after it (no off-by-one corruption),</li>
 *   <li>part bodies are raw bytes, never decoded as text,</li>
 *   <li>quoted boundaries and quoted-string filename escapes are supported,
 *       including RFC 5987 {@code filename*},</li>
 *   <li>missing names, duplicate/ambiguous parameters, bare-LF headers,
 *       unterminated parts and trailing truncation are rejected.</li>
 * </ul>
 *
 * <p>Memory is bounded by the caller's raw-body cap and part-count limit;
 * this parser never writes to disk.</p>
 */
final class MultipartBodyParser {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_SEPARATOR = {'\r', '\n', '\r', '\n'};
    private static final int MAX_HEADER_LINES = 32;

    private MultipartBodyParser() {}

    static WebhookMultipartBody parse(byte[] body, WebhookContentType contentType,
                                       int maxParts, int maxHeaderBytes) {
        if (contentType == null || !contentType.isMultipart()) {
            throw malformed("Not a multipart/form-data request");
        }
        if (body == null || body.length == 0) {
            throw malformed("Empty multipart body");
        }
        if (maxParts < 1) {
            throw new MultipartLimitExceededException("Multipart part limit is misconfigured");
        }

        byte[] dashBoundary = ascii("--" + contentType.boundary());
        int pos = 0;
        if (!startsWith(body, pos, dashBoundary)) {
            throw malformed("Multipart boundary is missing at the start of the body");
        }
        pos += dashBoundary.length;

        if (isClosing(body, pos)) {
            if (!isClosingDelimiter(body, pos)) {
                throw malformed("Closing multipart boundary has trailing data");
            }
            return new WebhookMultipartBody(List.of());
        }
        if (!startsWith(body, pos, CRLF)) {
            throw malformed("Multipart boundary is not followed by CRLF");
        }
        pos += CRLF.length;

        List<WebhookMultipartBody.Part> parts = new ArrayList<>();
        while (true) {
            int headerEnd = indexOf(body, HEADER_SEPARATOR, pos);
            if (headerEnd < 0) {
                throw malformed("Multipart part headers are not terminated");
            }
            if (headerEnd - pos > maxHeaderBytes) {
                throw malformed("Multipart part headers are too large");
            }

            Map<String, List<String>> headers = parseHeaders(body, pos, headerEnd);
            int contentStart = headerEnd + HEADER_SEPARATOR.length;
            Delimiter delimiter = findNextDelimiter(body, contentStart, dashBoundary);
            if (delimiter == null) {
                throw malformed("Multipart body is truncated: closing boundary is missing");
            }

            byte[] content = copyOfRange(body, contentStart, delimiter.contentEnd);
            parts.add(buildPart(headers, content));
            if (parts.size() > maxParts) {
                throw new MultipartLimitExceededException(
                        "Multipart part count exceeds the configured maximum");
            }

            if (delimiter.closing) {
                return new WebhookMultipartBody(parts);
            }
            pos = delimiter.afterBoundary + CRLF.length;
        }
    }

    private static Delimiter findNextDelimiter(byte[] body, int contentStart, byte[] dashBoundary) {
        int search = contentStart;
        int maxStart = body.length - (CRLF.length + dashBoundary.length);
        while (search <= maxStart) {
            if (body[search] == '\r' && body[search + 1] == '\n'
                    && startsWith(body, search + 2, dashBoundary)) {
                int afterBoundary = search + 2 + dashBoundary.length;
                if (isClosing(body, afterBoundary)) {
                    if (isClosingDelimiter(body, afterBoundary)) {
                        return new Delimiter(search, afterBoundary, true);
                    }
                    // A closing-boundary prefix followed by data is part of
                    // the binary content, not a delimiter.
                } else if (startsWith(body, afterBoundary, CRLF)) {
                    return new Delimiter(search, afterBoundary, false);
                }
            }
            search++;
        }
        return null;
    }

    private static WebhookMultipartBody.Part buildPart(Map<String, List<String>> headers,
                                                       byte[] content) {
        List<String> dispositions = headers.get("content-disposition");
        if (dispositions == null || dispositions.size() != 1) {
            throw malformed("Multipart part must have exactly one Content-Disposition header");
        }

        HeaderParameters.Value disposition;
        try {
            disposition = HeaderParameters.parse(dispositions.get(0));
        } catch (IllegalArgumentException e) {
            throw malformed("Malformed Content-Disposition header");
        }
        if (!"form-data".equalsIgnoreCase(disposition.token())) {
            throw malformed("Multipart part is not form-data");
        }

        String name = disposition.parameter("name");
        if (name == null || name.isEmpty()) {
            throw malformed("Multipart part is missing a name");
        }

        String fileName = disposition.parameter("filename");
        String encodedFileName = disposition.parameter("filename*");
        if (encodedFileName != null) {
            fileName = decodeExtendedValue(encodedFileName);
        }

        List<String> contentTypes = headers.get("content-type");
        if (contentTypes != null && contentTypes.size() > 1) {
            throw malformed("Multipart part has multiple Content-Type headers");
        }
        String contentType = contentTypes == null || contentTypes.isEmpty() ? null : contentTypes.get(0);

        return new WebhookMultipartBody.Part(name, fileName, contentType, headers, content);
    }

    private static Map<String, List<String>> parseHeaders(byte[] body, int start, int end) {
        if (end <= start) {
            throw malformed("Multipart part has no headers");
        }
        String block = decodeUtf8(body, start, end - start);
        String[] lines = block.split("\r\n", -1);
        if (lines.length > MAX_HEADER_LINES) {
            throw malformed("Multipart part has too many headers");
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                throw malformed("Multipart part contains an empty header line");
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw malformed("Folded multipart headers are not accepted");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw malformed("Malformed multipart header line");
            }
            String name = line.substring(0, colon);
            if (!HeaderParameters.isToken(name)) {
                throw malformed("Malformed multipart header name");
            }
            String value = line.substring(colon + 1).trim();
            if (containsControlCharacter(value)) {
                throw malformed("Control character in multipart header value");
            }
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    /**
     * Decodes RFC 5987 ext-value: {@code charset'language'percent-encoded}.
     */
    private static String decodeExtendedValue(String value) {
        int firstQuote = value.indexOf('\'');
        int secondQuote = firstQuote < 0 ? -1 : value.indexOf('\'', firstQuote + 1);
        if (firstQuote <= 0 || secondQuote < 0) {
            throw malformed("Malformed filename* parameter");
        }
        String charset = value.substring(0, firstQuote).toLowerCase(Locale.ROOT);
        if (!"utf-8".equals(charset) && !"us-ascii".equals(charset)) {
            throw malformed("Unsupported filename* charset");
        }

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        String encoded = value.substring(secondQuote + 1);
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) {
                    throw malformed("Truncated percent escape in filename*");
                }
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw malformed("Invalid percent escape in filename*");
                }
                decoded.write((high << 4) | low);
                i += 2;
            } else if (c < 0x20 || c == 0x7F || c > 0x7E) {
                throw malformed("Unescaped character in filename*");
            } else {
                decoded.write(c);
            }
        }
        return decodeUtf8(decoded.toByteArray(), 0, decoded.size());
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw malformed("Multipart headers are not valid UTF-8");
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClosing(byte[] body, int offset) {
        return offset + 1 < body.length && body[offset] == '-' && body[offset + 1] == '-';
    }

    private static boolean isClosingDelimiter(byte[] body, int afterBoundary) {
        if (!isClosing(body, afterBoundary)) {
            return false;
        }
        int afterClose = afterBoundary + 2;
        if (afterClose == body.length) {
            return true;
        }
        return startsWith(body, afterClose, CRLF) && afterClose + CRLF.length == body.length;
    }

    private static boolean startsWith(byte[] body, int offset, byte[] needle) {
        if (offset < 0 || offset + needle.length > body.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (body[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) {
            return from;
        }
        int maxStart = haystack.length - needle.length;
        for (int i = Math.max(from, 0); i <= maxStart; i++) {
            if (haystack[i] != needle[0]) {
                continue;
            }
            boolean match = true;
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] copy = new byte[to - from];
        System.arraycopy(source, from, copy, 0, copy.length);
        return copy;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static MalformedMultipartException malformed(String message) {
        return new MalformedMultipartException(message);
    }

    private record Delimiter(int contentEnd, int afterBoundary, boolean closing) {}

    static final class MalformedMultipartException extends MultipartException {
        MalformedMultipartException(String message) {
            super(message);
        }
    }

    static final class MultipartLimitExceededException extends MultipartException {
        MultipartLimitExceededException(String message) {
            super(message);
        }
    }
}
