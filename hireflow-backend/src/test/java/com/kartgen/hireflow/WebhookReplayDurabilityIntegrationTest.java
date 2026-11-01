package com.kartgen.hireflow;

import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-2 scenario 19: durable replay protection must survive an application
 * restart (reconstructed Spring context) using the same persistent database.
 *
 * This class uses its own isolated persistent H2 database and forces a
 * context rebuild between the two tests. The second request replays the
 * exact same nonce + idempotency key against the brand-new context; the
 * database uniqueness constraints - not any in-memory state - must reject
 * the duplicate.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hireflow-webhook-durability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@org.springframework.test.context.ActiveProfiles("test")
class WebhookReplayDurabilityIntegrationTest extends WebhookIntegrationTestSupport {

    private static final String FIXED_NONCE = "durability-nonce-0000000001";
    private static final String FIXED_IDEM = "durability-key-00000001";

    private static UUID organizationId;
    private static String keyId;
    private static String secret;
    private static byte[] body;

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void firstDeliveryAcceptedBeforeRestart() throws Exception {
        WebhookOrg org = newWebhookOrg("durability");
        Credential credential = provisionCredential(org);
        organizationId = org.org().getId();
        keyId = credential.keyId();
        secret = credential.secret();
        body = jsonDelivery("hr@agency.example", null, "durability", "resume.pdf", smallPdf());

        mockMvc.perform(signedJson(secret, organizationId.toString(), keyId,
                        java.time.Instant.now().getEpochSecond(), FIXED_NONCE, FIXED_IDEM, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        assertThat(deliveryRepo.countByOrganizationId(organizationId)).isEqualTo(1);
        assertThat(messageRepo.countByOrganizationId(organizationId)).isEqualTo(1);
    }

    @Test
    @Order(2)
    void replayAfterContextRestartIsStillRejectedAsDuplicate() throws Exception {
        // New Spring context (test 1 destroyed it), SAME database. Statics from
        // test 1 describe the original delivery.
        assertThat(organizationId).as("test 1 must run first").isNotNull();

        long messagesBefore = messageRepo.countByOrganizationId(organizationId);
        long deliveriesBefore = deliveryRepo.countByOrganizationId(organizationId);

        MvcResult result = mockMvc.perform(signedJson(secret, organizationId.toString(), keyId,
                        java.time.Instant.now().getEpochSecond(), FIXED_NONCE, FIXED_IDEM, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andReturn();

        // No second processing chain was created by the "restarted" application.
        assertThat(deliveryRepo.countByOrganizationId(organizationId)).isEqualTo(deliveriesBefore);
        assertThat(messageRepo.countByOrganizationId(organizationId)).isEqualTo(messagesBefore);
        assertThat(deliveryRepo.findByOrganizationIdAndIdempotencyKey(organizationId, FIXED_IDEM))
                .isPresent();

        // A fresh nonce with the same key + same payload is still the original delivery.
        mockMvc.perform(signedJson(secret, organizationId.toString(), keyId,
                        java.time.Instant.now().getEpochSecond(),
                        WebhookTestSupport.randomNonce(),
                        FIXED_IDEM, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(deliveryRepo.countByOrganizationId(organizationId)).isEqualTo(deliveriesBefore);
    }
}
