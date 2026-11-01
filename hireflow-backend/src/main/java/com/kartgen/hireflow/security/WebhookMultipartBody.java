package com.kartgen.hireflow.security;

import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, fully-buffered multipart/form-data body produced by
 * {@link MultipartBodyParser} after the request signature has been verified.
 *
 * <p>The parsed representation implements {@link Part} so the standard
 * servlet multipart resolver can still operate on the wrapped request, while
 * the webhook controller uses {@link #parts()} directly. Part bodies are raw
 * bytes and are never decoded as text.</p>
 */
public final class WebhookMultipartBody {

    private final List<Part> parts;

    WebhookMultipartBody(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Part> partsNamed(String name) {
        List<Part> matches = new ArrayList<>();
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                matches.add(part);
            }
        }
        return List.copyOf(matches);
    }

    public Part firstNamed(String name) {
        for (Part part : parts) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    /**
     * A single multipart part. {@link #content()} exposes the immutable
     * backing array for the controller without an additional copy; callers
     * must not modify it.
     */
    public static final class Part implements jakarta.servlet.http.Part {

        private final String name;
        private final String submittedFileName;
        private final String contentType;
        private final Map<String, List<String>> headers;
        private final byte[] content;

        Part(String name, String submittedFileName, String contentType,
             Map<String, List<String>> headers, byte[] content) {
            this.name = name;
            this.submittedFileName = submittedFileName;
            this.contentType = contentType;
            this.content = content;

            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((key, values) ->
                    copy.put(key.toLowerCase(Locale.ROOT), List.copyOf(values)));
            this.headers = Collections.unmodifiableMap(copy);
        }

        public byte[] content() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return submittedFileName;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public void write(String fileName) throws IOException {
            Files.write(Path.of(fileName), content);
        }

        @Override
        public void delete() {
            // Fully buffered in memory; nothing to delete.
        }

        @Override
        public String getHeader(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public Collection<String> getHeaders(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null ? List.of() : values;
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }
    }
}
