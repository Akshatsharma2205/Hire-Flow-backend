package com.kartgen.hireflow.webhook;

import com.kartgen.hireflow.common.CryptoUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class LocalWebhookDevCredentialSeederTest {

    private static final String SECRET = "local-only-secret-0123456789abcdef";

    @Test
    void disabledSeederDoesNothingEvenWhenASecretIsConfigured() {
        WebhookCredentialRepository repository = mock(WebhookCredentialRepository.class);
        HireFlowProperties props = properties(false, SECRET);

        new LocalWebhookDevCredentialSeeder(repository, props).run(null);

        verifyNoInteractions(repository);
    }

    @Test
    void refusesToSeedWithoutASecret() {
        WebhookCredentialRepository repository = mock(WebhookCredentialRepository.class);
        HireFlowProperties props = properties(true, null);

        assertThatThrownBy(() -> new LocalWebhookDevCredentialSeeder(repository, props).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK_LOCAL_DEV_SECRET");
        verifyNoInteractions(repository);
    }

    @Test
    void refusesToSeedWithAShortSecret() {
        WebhookCredentialRepository repository = mock(WebhookCredentialRepository.class);
        HireFlowProperties props = properties(true, "too-short");

        assertThatThrownBy(() -> new LocalWebhookDevCredentialSeeder(repository, props).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
        verifyNoInteractions(repository);
    }

    @Test
    void storesTheConfiguredSecretEncryptedAndNeverLogsIt(CapturedOutput output) {
        WebhookCredentialRepository repository = mock(WebhookCredentialRepository.class);
        when(repository.findByKeyId(anyString())).thenReturn(Optional.empty());
        HireFlowProperties props = properties(true, SECRET);

        new LocalWebhookDevCredentialSeeder(repository, props).run(null);

        ArgumentCaptor<WebhookCredential> captor = ArgumentCaptor.forClass(WebhookCredential.class);
        verify(repository).save(captor.capture());
        WebhookCredential saved = captor.getValue();

        assertThat(saved.getKeyId()).isEqualTo(LocalWebhookDevCredentialSeeder.LOCAL_DEMO_KEY_ID);
        assertThat(saved.getOrganizationId().toString())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(saved.getStatus()).isEqualTo(WebhookCredential.STATUS_ACTIVE);
        assertThat(saved.getEncryptedSecret()).isNotEqualTo(SECRET);
        assertThat(saved.getEncryptedSecret()).doesNotContain(SECRET);
        assertThat(CryptoUtil.decrypt(saved.getEncryptedSecret(), props.getCrypto().getSecretKey()))
                .isEqualTo(SECRET);
        assertThat(output.getAll()).doesNotContain(SECRET);
    }

    @Test
    void isIdempotentWhenTheDemoCredentialAlreadyExists() {
        WebhookCredentialRepository repository = mock(WebhookCredentialRepository.class);
        when(repository.findByKeyId(LocalWebhookDevCredentialSeeder.LOCAL_DEMO_KEY_ID))
                .thenReturn(Optional.of(WebhookCredential.builder()
                        .keyId(LocalWebhookDevCredentialSeeder.LOCAL_DEMO_KEY_ID)
                        .encryptedSecret("existing")
                        .build()));

        new LocalWebhookDevCredentialSeeder(repository, properties(true, SECRET)).run(null);

        verify(repository, never()).save(any());
    }

    private static HireFlowProperties properties(boolean enabled, String secret) {
        HireFlowProperties props = new HireFlowProperties();
        props.getWebhook().setLocalDevEnabled(enabled);
        props.getWebhook().setLocalDevSecret(secret);
        return props;
    }
}
