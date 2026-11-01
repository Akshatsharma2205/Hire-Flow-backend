package com.kartgen.hireflow.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HireFlowPropertiesValidationTest {

    @Test
    void defaultWebhookLimitsAreCoherent() {
        assertThatCode(() -> newProperties().getWebhook().validateSizeLimits())
                .doesNotThrowAnyException();
    }

    @Test
    void defaultLocalSeedingIsDisabledAndHasNoSecret() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();

        assertThat(webhook.isLocalDevEnabled()).isFalse();
        assertThat(webhook.getLocalDevSecret()).isNull();
    }

    @Test
    void rejectsTooSmallBodyLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxBodyBytes(1_023);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-body-bytes");
    }

    @Test
    void rejectsZeroAttachmentLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxAttachments(0);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-attachments");
    }

    @Test
    void rejectsPerAttachmentLimitAboveAggregateLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxTotalAttachmentBytes(1_000);
        webhook.setMaxAttachmentBytes(1_001);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed max-total-attachment-bytes");
    }

    @Test
    void rejectsPartLimitThatCannotHoldEveryFieldAndAttachment() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxAttachments(20);
        webhook.setMaxParts(24);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-parts");
    }

    @Test
    void rejectsMetadataLimitLargerThanBodyLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxBodyBytes(100_000);
        webhook.setMaxMetadataBytes(100_001);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-metadata-bytes");
    }

    @Test
    void rejectsTooSmallHeaderLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxHeaderBytes(1_023);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-header-bytes");
    }

    @Test
    void rejectsZeroConcurrencyLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxConcurrentRequests(0);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-requests");
    }

    @Test
    void rejectsAggregateAttachmentLimitAboveBodyLimit() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxTotalAttachmentBytes(webhook.getMaxBodyBytes() + 1);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fit inside max-body-bytes");
    }

    @Test
    void rejectsBodyLimitThatCannotFitBase64Expansion() {
        HireFlowProperties.WebhookProperties webhook = newProperties().getWebhook();
        webhook.setMaxBodyBytes(2_000);
        webhook.setMaxTotalAttachmentBytes(1_500);
        webhook.setMaxAttachmentBytes(1_500);
        webhook.setMaxMetadataBytes(1_024);

        assertThatThrownBy(webhook::validateSizeLimits)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64-expanded");
    }

    private static HireFlowProperties newProperties() {
        return new HireFlowProperties();
    }
}
