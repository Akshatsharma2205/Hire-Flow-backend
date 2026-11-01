package com.kartgen.hireflow.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyConfigTest {

    @Test
    void productionRejectsEnabledLocalWebhookSeeding() {
        HireFlowProperties props = productionProperties();
        props.getWebhook().setLocalDevEnabled(true);

        assertThatThrownBy(() -> new ProductionSafetyConfig(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be enabled in production");
    }

    @Test
    void productionRejectsALocalWebhookSecretEvenWhenSeedingIsDisabled() {
        HireFlowProperties props = productionProperties();
        props.getWebhook().setLocalDevSecret("local-only-secret-0123456789abcdef");

        assertThatThrownBy(() -> new ProductionSafetyConfig(props).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be set in production");
    }

    @Test
    void productionAcceptsCredentialsProvisionedWithoutLocalSeeding() {
        HireFlowProperties props = productionProperties();

        assertThatCode(() -> new ProductionSafetyConfig(props).validate())
                .doesNotThrowAnyException();
    }

    private static HireFlowProperties productionProperties() {
        HireFlowProperties props = new HireFlowProperties();
        props.getJwt().setSecret("production-jwt-secret-0123456789abcdef");
        props.getCrypto().setSecretKey("FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210");
        props.getStorage().setProvider("s3");
        props.getStorage().setS3Bucket("hireflow-production-resumes");
        props.getCors().setAllowedOrigins("https://app.hireflow.example");
        props.getWebhook().setEnabled(true);
        props.getWebhook().setReplayWindowSeconds(300);
        return props;
    }
}
