package com.kartgen.hireflow.webhook;

import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * LOCAL DEVELOPMENT ONLY.
 *
 * <p>Seeding is opt-in: the {@code local} profile alone is not enough. The
 * bean exists only when {@code hireflow.webhook.local-dev-enabled=true}, and
 * startup fails unless an explicit, sufficiently long
 * {@code hireflow.webhook.local-dev-secret} is supplied. There is no default
 * secret in code or configuration.</p>
 *
 * <p>The secret is stored encrypted at rest like any other credential and is
 * never logged. Production credentials are provisioned exclusively through
 * {@code POST /api/v1/webhook-credentials} by an authenticated OWNER or
 * ADMIN.</p>
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "hireflow.webhook", name = "local-dev-enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class LocalWebhookDevCredentialSeeder implements ApplicationRunner {

    /** Stable key id for the local demo credential (16 hex chars after the prefix). */
    public static final String LOCAL_DEMO_KEY_ID = "whk_0a11c0de00000001";
    private static final String LOCAL_DEMO_ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final WebhookCredentialRepository credentialRepo;
    private final HireFlowProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getWebhook().isLocalDevEnabled()) {
            return;
        }
        String secret = props.getWebhook().getLocalDevSecret();
        if (secret == null || secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "WEBHOOK_LOCAL_DEV_SECRET must be set to at least "
                            + MINIMUM_SECRET_LENGTH + " characters when "
                            + "WEBHOOK_LOCAL_DEV_ENABLED=true");
        }

        String keyId = LOCAL_DEMO_KEY_ID;
        if (credentialRepo.findByKeyId(keyId).isPresent()) {
            return;
        }
        credentialRepo.save(WebhookCredential.builder()
                .organizationId(UUID.fromString(LOCAL_DEMO_ORG_ID))
                .keyId(keyId)
                .encryptedSecret(CryptoUtil.encrypt(
                        secret, props.getCrypto().getSecretKey()))
                .status(WebhookCredential.STATUS_ACTIVE)
                .build());
        log.info("Local development webhook credential ready: keyId={} org={}",
                keyId, LOCAL_DEMO_ORG_ID);
    }
}
