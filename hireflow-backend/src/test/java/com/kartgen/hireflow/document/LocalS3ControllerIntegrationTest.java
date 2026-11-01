package com.kartgen.hireflow.document;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import com.kartgen.hireflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalS3ControllerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private StoragePort storagePort;

    @Autowired
    private HireFlowProperties props;

    @Test
    void servesFileWithValidHmacTokenAndRejectsInvalidOrExpiredTokens() throws Exception {
        UUID orgId = UUID.fromString(ORG_ID);
        byte[] testPdf = "%PDF-1.4 sample resume content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HashUtil.sha256Hex(testPdf);
        String objectKey = storagePort.store(orgId, sha256, "application/pdf", testPdf);

        // 1. Generate valid token through storagePort
        String viewUrl = storagePort.generateViewUrl(objectKey, "resume.pdf");
        assertThat(viewUrl).startsWith("/api/v1/local-s3/");

        mockMvc.perform(get(viewUrl))
                .andExpect(status().isOk());

        // 2. Malformed token without dot is rejected
        mockMvc.perform(get("/api/v1/local-s3/not-a-valid-token"))
                .andExpect(status().isForbidden());

        // 3. Token with forged HMAC is rejected
        String payload = objectKey + ":" + (Instant.now().getEpochSecond() + 3600);
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String forgedToken = b64 + ".00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

        mockMvc.perform(get("/api/v1/local-s3/" + forgedToken))
                .andExpect(status().isForbidden());

        // 4. Expired token is rejected
        String expiredPayload = objectKey + ":" + (Instant.now().getEpochSecond() - 3600);
        String expiredB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expiredPayload.getBytes(StandardCharsets.UTF_8));
        String validHmacForExpired = HashUtil.hmacSha256(expiredPayload, props.getStorage().getLocalUrlSecret());
        String expiredToken = expiredB64 + "." + validHmacForExpired;

        mockMvc.perform(get("/api/v1/local-s3/" + expiredToken))
                .andExpect(status().isGone());
    }
}
