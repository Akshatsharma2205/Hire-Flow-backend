package com.kartgen.hireflow.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fails fast when the prod profile is started with development defaults.
 * Production deployments must supply real secrets through the environment.
 */
@Slf4j
@Configuration
@Profile("prod")
@RequiredArgsConstructor
public class ProductionSafetyConfig {

    private final HireFlowProperties props;

    @PostConstruct
    public void validate() {
        String jwt = props.getJwt().getSecret();
        if (jwt == null || jwt.length() < 32 || jwt.contains("CHANGE-ME")) {
            throw new IllegalStateException("JWT_SECRET must be a strong secret of at least 32 characters");
        }
        String crypto = props.getCrypto().getSecretKey();
        if (crypto == null || !crypto.matches("(?i)[0-9a-f]{64}")
                || "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF".equalsIgnoreCase(crypto)) {
            throw new IllegalStateException("CRYPTO_SECRET_KEY must be a distinct 64 hex character secret (AES-256)");
        }
        if (props.getWebhook().isEnabled() && props.getWebhook().getReplayWindowSeconds() < 30) {
            throw new IllegalStateException("WEBHOOK replay window must be at least 30 seconds in production");
        }
        if (props.getWebhook().isLocalDevEnabled()) {
            throw new IllegalStateException(
                    "Local webhook credential seeding cannot be enabled in production");
        }
        if (props.getWebhook().getLocalDevSecret() != null
                && !props.getWebhook().getLocalDevSecret().isBlank()) {
            throw new IllegalStateException(
                    "WEBHOOK_LOCAL_DEV_SECRET must not be set in production");
        }
        if (!"s3".equalsIgnoreCase(props.getStorage().getProvider())) {
            throw new IllegalStateException("Production storage provider must be s3");
        }
        if (props.getStorage().getS3Bucket() == null || props.getStorage().getS3Bucket().isBlank()) {
            throw new IllegalStateException("S3_BUCKET must be configured in production");
        }
        if (props.getCors().getAllowedOrigins() == null
                || props.getCors().getAllowedOrigins().contains("localhost")
                || props.getCors().getAllowedOrigins().contains("127.0.0.1")) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain only production origins");
        }
        log.info("Production safety checks passed");
    }
}
