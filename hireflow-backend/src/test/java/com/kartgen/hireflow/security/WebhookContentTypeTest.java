package com.kartgen.hireflow.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookContentTypeTest {

    @Test
    void canonicalJsonIgnoresOptionalParameters() {
        WebhookContentType type = WebhookContentType.parse("application/json; charset=UTF-8");

        assertThat(type.mediaType()).isEqualTo("application/json");
        assertThat(type.isMultipart()).isFalse();
        assertThat(type.boundary()).isNull();
        assertThat(type.canonical()).isEqualTo("application/json");
    }

    @Test
    void canonicalJsonIsCaseInsensitiveForMediaType() {
        assertThat(WebhookContentType.parse("Application/JSON").canonical())
                .isEqualTo("application/json");
    }

    @Test
    void canonicalMultipartAlwaysQuotesBoundary() {
        WebhookContentType type = WebhookContentType.parse("multipart/form-data; boundary=abc123");

        assertThat(type.boundary()).isEqualTo("abc123");
        assertThat(type.canonical())
                .isEqualTo("multipart/form-data; boundary=\"abc123\"");
    }

    @Test
    void canonicalMultipartPreservesInteriorSpaceInBoundary() {
        WebhookContentType type = WebhookContentType.parse(
                "multipart/form-data; boundary=\"a b\"");

        assertThat(type.boundary()).isEqualTo("a b");
        assertThat(type.canonical())
                .isEqualTo("multipart/form-data; boundary=\"a b\"");
    }

    @Test
    void canonicalMultipartIgnoresOtherParameters() {
        WebhookContentType type = WebhookContentType.parse(
                "multipart/form-data; charset=utf-8; boundary=abc");

        assertThat(type.canonical())
                .isEqualTo("multipart/form-data; boundary=\"abc\"");
    }

    @Test
    void rejectsMissingContentType() {
        assertInvalid(null, "Missing");
    }

    @Test
    void rejectsEmptyContentType() {
        assertInvalid("", "Empty");
    }

    @Test
    void rejectsUnsupportedMediaType() {
        assertInvalid("text/plain", "Unsupported");
    }

    @Test
    void rejectsMultipartWithoutBoundary() {
        assertInvalid("multipart/form-data", "boundary");
    }

    @Test
    void rejectsEmptyBoundary() {
        assertInvalid("multipart/form-data; boundary=\"\"", "boundary");
    }

    @Test
    void rejectsDuplicateBoundaryParameter() {
        assertInvalid("multipart/form-data; boundary=one; boundary=two", "Duplicate");
    }

    @Test
    void rejectsInvalidBoundaryCharacter() {
        assertInvalid("multipart/form-data; boundary=abc@def", "invalid character");
    }

    @Test
    void rejectsTooLongBoundary() {
        assertInvalid("multipart/form-data; boundary=" + "a".repeat(71), "too long");
    }

    @Test
    void rejectsUnterminatedQuotedBoundary() {
        assertInvalid("multipart/form-data; boundary=\"abc", "Malformed");
    }

    @Test
    void rejectsMalformedParameterWithoutEquals() {
        assertInvalid("application/json; charset", "Malformed");
    }

    private static void assertInvalid(String value, String messagePart) {
        assertThatThrownBy(() -> WebhookContentType.parse(value))
                .isInstanceOf(WebhookContentType.InvalidContentTypeException.class)
                .hasMessageContaining(messagePart);
    }
}
