package com.kartgen.hireflow.security;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct, byte-level tests for the webhook multipart parser. These do not go
 * through Spring MVC or a servlet container, so they can assert exact body
 * offsets and binary preservation.
 */
class MultipartBodyParserTest {

    private static final String BOUNDARY = "test-boundary";
    private static final String DISPOSITION =
            "Content-Disposition: form-data; name=\"files\"; filename=\"resume.pdf\"";
    private static final int MAX_PARTS = 32;
    private static final int MAX_HEADER_BYTES = 8_192;

    @Test
    void parsesPartContentImmediatelyAfterExactCrlfCrlf() {
        byte[] content = {0x00, 0x01, 'A', '\r', '\n', (byte) 0x80, (byte) 0xFF, 'Z'};

        WebhookMultipartBody body = parse(rawBody(DISPOSITION, content));

        assertThat(body.parts()).hasSize(1);
        WebhookMultipartBody.Part part = (WebhookMultipartBody.Part) body.firstNamed("files");
        assertThat(part.getSubmittedFileName()).isEqualTo("resume.pdf");
        assertThat(part.content()).isEqualTo(content);
    }

    @Test
    void preservesBoundaryLikeBytesInsideBinaryContent() {
        String content = "prefix\r\n--" + BOUNDARY + "X\r\n--" + BOUNDARY + "--garbage\r\nsuffix";

        WebhookMultipartBody body = parse(rawBody(DISPOSITION, content.getBytes(StandardCharsets.UTF_8)));

        assertThat(body.parts()).hasSize(1);
        assertThat(new String(body.firstNamed("files").content(), StandardCharsets.UTF_8))
                .isEqualTo(content);
    }

    @Test
    void acceptsQuotedBoundaryWithInteriorSpace() {
        String boundary = "a b";
        byte[] raw = rawBody(boundary, DISPOSITION, new byte[]{1, 2, 3});
        WebhookContentType contentType = WebhookContentType.parse(
                "multipart/form-data; boundary=\"" + boundary + "\"");

        WebhookMultipartBody body = MultipartBodyParser.parse(
                raw, contentType, MAX_PARTS, MAX_HEADER_BYTES);

        assertThat(contentType.boundary()).isEqualTo(boundary);
        assertThat(contentType.canonical())
                .isEqualTo("multipart/form-data; boundary=\"a b\"");
        assertThat(body.firstNamed("files").content()).containsExactly(1, 2, 3);
    }

    @Test
    void unescapesQuotedFilename() {
        String disposition = "Content-Disposition: form-data; name=\"files\"; "
                + "filename=\"a\\\"b\\\\c.pdf\"";

        WebhookMultipartBody body = parse(rawBody(disposition, new byte[]{1}));

        assertThat(body.firstNamed("files").getSubmittedFileName()).isEqualTo("a\"b\\c.pdf");
    }

    @Test
    void supportsRfc5987ExtendedFilename() {
        String disposition = "Content-Disposition: form-data; name=\"files\"; "
                + "filename*=UTF-8''r%C3%A9sum%C3%A9%20final.pdf";

        WebhookMultipartBody body = parse(rawBody(disposition, new byte[]{1}));

        assertThat(body.firstNamed("files").getSubmittedFileName())
                .isEqualTo("résumé final.pdf");
    }

    @Test
    void acceptsEmptyPartContent() {
        WebhookMultipartBody body = parse(rawBody(DISPOSITION, new byte[0]));

        assertThat(body.parts()).hasSize(1);
        assertThat(body.firstNamed("files").getSize()).isZero();
    }

    @Test
    void rejectsPartWithoutName() {
        String disposition = "Content-Disposition: form-data; filename=\"resume.pdf\"";

        assertThatThrownBy(() -> parse(rawBody(disposition, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("missing a name");
    }

    @Test
    void rejectsDuplicateHeaderParameter() {
        String disposition = "Content-Disposition: form-data; name=\"files\"; name=\"other\"";

        assertThatThrownBy(() -> parse(rawBody(disposition, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("Content-Disposition");
    }

    @Test
    void rejectsDuplicateContentDispositionHeaders() {
        String headers = DISPOSITION + "\r\n" + DISPOSITION;

        assertThatThrownBy(() -> parse(rawBody(headers, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("exactly one Content-Disposition");
    }

    @Test
    void rejectsBareLfHeaderLines() {
        String headers = DISPOSITION + "\r\nX-Test: value\ncontinued";

        assertThatThrownBy(() -> parse(rawBody(headers, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class);
    }

    @Test
    void rejectsPreambleBeforeFirstBoundary() {
        byte[] valid = rawBody(DISPOSITION, new byte[]{1});
        byte[] withPreamble = new byte[valid.length + 9];
        System.arraycopy("preamble\n".getBytes(StandardCharsets.US_ASCII), 0, withPreamble, 0, 9);
        System.arraycopy(valid, 0, withPreamble, 9, valid.length);

        assertThatThrownBy(() -> parse(withPreamble))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("missing at the start");
    }

    @Test
    void rejectsDelimiterWithoutPrecedingCrlf() {
        byte[] raw = ("--" + BOUNDARY + "\r\n" + DISPOSITION + "\r\n\r\n"
                + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parse(raw))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void rejectsTruncatedClosingBoundary() {
        byte[] valid = rawBody(DISPOSITION, new byte[]{1});
        byte[] truncated = new byte[valid.length - 4];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> parse(truncated))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void rejectsTrailingDataAfterClosingBoundary() {
        byte[] valid = rawBody(DISPOSITION, new byte[]{1});
        byte[] trailing = new byte[valid.length + 7];
        System.arraycopy(valid, 0, trailing, 0, valid.length);
        System.arraycopy("garbage".getBytes(StandardCharsets.US_ASCII), 0, trailing, valid.length, 7);

        assertThatThrownBy(() -> parse(trailing))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class);
    }

    @Test
    void rejectsPartCountAboveLimit() {
        List<RawPart> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parts.add(new RawPart(
                    "Content-Disposition: form-data; name=\"files\"; filename=\"" + i + ".pdf\"",
                    new byte[]{1}));
        }

        assertThatThrownBy(() -> parse(rawBody(parts), 2))
                .isInstanceOf(MultipartBodyParser.MultipartLimitExceededException.class);
    }

    @Test
    void rejectsOversizedHeaderBlock() {
        assertThatThrownBy(() -> MultipartBodyParser.parse(
                rawBody(DISPOSITION, new byte[]{1}),
                contentType(), MAX_PARTS, 10))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("headers are too large");
    }

    @Test
    void rejectsMalformedFilenameStar() {
        String disposition = "Content-Disposition: form-data; name=\"files\"; "
                + "filename*=UTF-8''bad%ZZname.pdf";

        assertThatThrownBy(() -> parse(rawBody(disposition, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("filename*");
    }

    @Test
    void rejectsUnsupportedFilenameStarCharset() {
        String disposition = "Content-Disposition: form-data; name=\"files\"; "
                + "filename*=ISO-8859-1''name.pdf";

        assertThatThrownBy(() -> parse(rawBody(disposition, new byte[]{1})))
                .isInstanceOf(MultipartBodyParser.MalformedMultipartException.class)
                .hasMessageContaining("charset");
    }

    private static WebhookMultipartBody parse(byte[] body) {
        return MultipartBodyParser.parse(body, contentType(), MAX_PARTS, MAX_HEADER_BYTES);
    }

    private static WebhookMultipartBody parse(byte[] body, int maxParts) {
        return MultipartBodyParser.parse(body, contentType(), maxParts, MAX_HEADER_BYTES);
    }

    private static WebhookContentType contentType() {
        return WebhookContentType.parse("multipart/form-data; boundary=\"" + BOUNDARY + "\"");
    }

    private static byte[] rawBody(String headers, byte[] content) {
        return rawBody(List.of(new RawPart(headers, content)));
    }

    private static byte[] rawBody(String boundary, String headers, byte[] content) {
        return rawBody(boundary, List.of(new RawPart(headers, content)));
    }

    private static byte[] rawBody(List<RawPart> parts) {
        return rawBody(BOUNDARY, parts);
    }

    private static byte[] rawBody(String boundary, List<RawPart> parts) {
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

    private record RawPart(String headers, byte[] content) {}
}
