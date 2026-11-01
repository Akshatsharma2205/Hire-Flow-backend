package com.kartgen.hireflow.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "hireflow")
public class HireFlowProperties {

    private JwtProperties jwt = new JwtProperties();
    private CryptoProperties crypto = new CryptoProperties();
    private StorageProperties storage = new StorageProperties();
    private AiProperties ai = new AiProperties();
    private WebhookProperties webhook = new WebhookProperties();
    private CorsProperties cors = new CorsProperties();
    private OAuthProperties oauth = new OAuthProperties();
    private WorkerProperties worker = new WorkerProperties();

    @Data
    public static class JwtProperties {
        private String secret = "CHANGE-ME-local-dev-jwt-secret-key-at-least-256-bits-long-0123456789";
        private String issuer = "hireflow";
        private long expirationMs = 86_400_000L;
    }

    @Data
    public static class CryptoProperties {
        private String secretKey = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    }

    @Data
    public static class StorageProperties {
        /** local | s3 */
        private String provider = "local";
        private String localBasePath = "./.local-storage/s3-bucket";
        private String localUrlSecret = "hireflow-local-hmac-key";
        private String s3Bucket;
        private String s3Region = "ap-south-1";
        private String s3Endpoint;
        private boolean s3PathStyle;
        private int s3PresignedExpiryMinutes = 15;
    }

    @Data
    public static class AiProperties {
        /** local | external */
        private String provider = "local";
        private String endpointUrl = "http://localhost:5000/api/extract";
        private String apiKey = "";
        private int requestTimeoutMs = 60_000;
    }

    @Data
    public static class WebhookProperties {
        /** Master switch for inbound forwarding webhooks. */
        private boolean enabled = true;
        /** Signed requests older/newer than this many seconds are rejected. */
        private long replayWindowSeconds = 300;
        /** Hard cap on the raw webhook request body (HMAC verification buffer). */
        private long maxBodyBytes = 26_214_400L;
        /** Maximum number of attachments accepted per webhook delivery. */
        private int maxAttachments = 20;
        /** Maximum decoded size of a single attachment (multipart part or base64). */
        private long maxAttachmentBytes = 10_485_760L;
        /** Maximum decoded size of all attachments in one delivery. */
        private long maxTotalAttachmentBytes = 16_777_216L;
        /** Maximum number of multipart parts, including metadata fields. */
        private int maxParts = 32;
        /** Maximum aggregate size of textual metadata (fields and filenames). */
        private int maxMetadataBytes = 16_384;
        /** Maximum size of one multipart part header block. */
        private int maxHeaderBytes = 8_192;
        /** Maximum number of webhook bodies buffered/parsed concurrently. */
        private int maxConcurrentRequests = 4;
        /**
         * LOCAL DEVELOPMENT ONLY. Seeding is opt-in and requires an explicit
         * secret; there is deliberately no default secret.
         */
        private boolean localDevEnabled = false;
        private String localDevSecret;

        @PostConstruct
        void validateSizeLimits() {
            if (maxBodyBytes < 1_024) {
                throw new IllegalStateException("WEBHOOK max-body-bytes must be at least 1024");
            }
            if (maxAttachments < 1) {
                throw new IllegalStateException("WEBHOOK max-attachments must be at least 1");
            }
            if (maxAttachmentBytes < 1 || maxTotalAttachmentBytes < 1) {
                throw new IllegalStateException("WEBHOOK attachment limits must be positive");
            }
            if (maxAttachmentBytes > maxTotalAttachmentBytes) {
                throw new IllegalStateException(
                        "WEBHOOK max-attachment-bytes must not exceed max-total-attachment-bytes");
            }
            if (maxParts < maxAttachments + 5) {
                throw new IllegalStateException(
                        "WEBHOOK max-parts must allow every attachment plus five metadata fields");
            }
            if (maxMetadataBytes < 1 || maxMetadataBytes > maxBodyBytes) {
                throw new IllegalStateException(
                        "WEBHOOK max-metadata-bytes must be positive and no larger than max-body-bytes");
            }
            if (maxHeaderBytes < 1_024) {
                throw new IllegalStateException("WEBHOOK max-header-bytes must be at least 1024");
            }
            if (maxConcurrentRequests < 1) {
                throw new IllegalStateException("WEBHOOK max-concurrent-requests must be at least 1");
            }
            if (maxTotalAttachmentBytes > maxBodyBytes) {
                throw new IllegalStateException(
                        "WEBHOOK max-total-attachment-bytes must fit inside max-body-bytes");
            }
            long maxBase64Bytes = ((maxTotalAttachmentBytes + 2) / 3) * 4;
            if (maxBase64Bytes + maxMetadataBytes + 1_024 > maxBodyBytes) {
                throw new IllegalStateException(
                        "WEBHOOK max-body-bytes must fit the base64-expanded attachment aggregate, "
                                + "metadata and JSON envelope");
            }
        }
    }

    @Data
    public static class CorsProperties {
        /** Comma-separated allowed origin patterns. */
        private String allowedOrigins = "http://localhost:*,http://127.0.0.1:*";
    }

    @Data
    public static class OAuthProperties {
        private String googleClientId = "";
        private String googleClientSecret = "";
        private String googleTokenUri = "https://oauth2.googleapis.com/token";
        private String gmailBaseUrl = "https://gmail.googleapis.com/gmail/v1";
        private String microsoftClientId = "";
        private String microsoftClientSecret = "";
        private String microsoftTenant = "common";
        private String microsoftTokenUriTemplate =
                "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token";
        private String graphBaseUrl = "https://graph.microsoft.com/v1.0";
    }

    @Data
    public static class WorkerProperties {
        private long pollMs = 2000;
        private int batchSize = 10;
        private int staleAfterMinutes = 10;
    }
}
