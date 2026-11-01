package com.kartgen.hireflow.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * Bounded, replayable request wrapper for webhook authentication.
 *
 * <p>The HMAC signature must cover the exact raw request bytes, so the filter
 * reads (and caches, up to a hard maximum) the full body before multipart
 * parsing or JSON binding. This wrapper then:</p>
 * <ul>
 *   <li>replays the cached bytes to downstream consumers via
 *       {@code getInputStream()}/{@code getReader()},</li>
 *   <li>exposes the multipart body that the filter parsed and validated
 *       <em>after</em> signature verification, so the servlet multipart
 *       resolver never needs to read the consumed connector stream,</li>
 *   <li>preserves the original headers and reports the cached content
 *       length.</li>
 * </ul>
 */
public class WebhookBodyCachingRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;
    private WebhookMultipartBody multipartBody;

    public WebhookBodyCachingRequestWrapper(HttpServletRequest request, long maxBodyBytes) throws IOException {
        super(request);
        InputStream in = super.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > maxBodyBytes) {
                throw new RequestBodyTooLargeException(maxBodyBytes);
            }
            buffer.write(chunk, 0, read);
        }
        this.body = buffer.toByteArray();
    }

    public byte[] getContentAsByteArray() {
        return body;
    }

    /** Installed by the filter only after the request signature is verified. */
    public void setMultipartBody(WebhookMultipartBody multipartBody) {
        this.multipartBody = multipartBody;
    }

    public WebhookMultipartBody getMultipartBody() {
        return multipartBody;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reads are not supported for webhook requests");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return source.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (multipartBody != null) {
            return List.copyOf(multipartBody.parts());
        }
        String contentType = getContentType();
        if (contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT).startsWith(WebhookContentType.MULTIPART)) {
            throw new IllegalStateException(
                    "Authenticated multipart body is not available on this request");
        }
        return super.getParts();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        if (multipartBody != null) {
            return multipartBody.firstNamed(name);
        }
        for (Part part : getParts()) {
            if (part.getName().equals(name)) {
                return part;
            }
        }
        return null;
    }

    /** Thrown when the raw body exceeds the configured maximum before buffering completes. */
    public static class RequestBodyTooLargeException extends RuntimeException {
        public RequestBodyTooLargeException(long maxBodyBytes) {
            super("Webhook body exceeds maximum of " + maxBodyBytes + " bytes");
        }
    }
}
