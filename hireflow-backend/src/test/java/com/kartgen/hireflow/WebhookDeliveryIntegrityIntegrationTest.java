package com.kartgen.hireflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.mail.model.InboundAttachment;
import com.kartgen.hireflow.mail.model.InboundMessage;
import com.kartgen.hireflow.ops.ProcessingJob;
import com.kartgen.hireflow.support.TestResumePdf;
import com.kartgen.hireflow.support.WebhookIntegrationTestSupport;
import com.kartgen.hireflow.support.WebhookTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * J-2 webhook data integrity: one email -> one message -> many attachments
 * -> processing jobs; concurrent-delivery safety; sender provenance.
 */
class WebhookDeliveryIntegrityIntegrationTest extends WebhookIntegrationTestSupport {

    @Test
    void threeAttachmentsCreateOneMessageWithThreeAttachmentsAndThreeJobs() throws Exception {
        var hook = hook("three");
        byte[] pdf1 = smallPdf();
        byte[] pdf2 = smallPdf();
        byte[] pdf3 = smallPdf();
        List<WebhookTestSupport.FormPart> parts = List.of(
                new WebhookTestSupport.FormPart("senderEmail", null, null, "hr@agency.example".getBytes()),
                new WebhookTestSupport.FormPart("subject", null, null, "three resumes".getBytes()),
                new WebhookTestSupport.FormPart("files", "one.pdf", "application/pdf", pdf1),
                new WebhookTestSupport.FormPart("files", "two.pdf", "application/pdf", pdf2),
                new WebhookTestSupport.FormPart("files", "three.pdf", "application/pdf", pdf3));
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-three");

        MvcResult result = mockMvc.perform(performSignedMultipart(hook.credential(),
                        hook.orgId().toString(), WebhookTestSupport.randomNonce(), idem, parts))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(3))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID messageId = UUID.fromString(body.path("inboundMessageId").asText());
        List<UUID> attachmentIds = new ArrayList<>();
        body.path("attachmentIds").forEach(n -> attachmentIds.add(UUID.fromString(n.asText())));

        // ONE inbound message for the whole email.
        assertThat(messageRepo.countByOrganizationId(hook.orgId())).isEqualTo(1);
        InboundMessage message = messageRepo.findById(messageId).orElseThrow();
        assertThat(message.getOrganizationId()).isEqualTo(hook.orgId());

        // Exactly three attachment records attached to that one message.
        List<InboundAttachment> attachments = attachmentRepo.findByMessageId(messageId);
        assertThat(attachments).hasSize(3);
        assertThat(attachments.stream().map(InboundAttachment::getId).collect(Collectors.toSet()))
                .isEqualTo(Set.copyOf(attachmentIds));

        // Only the intended parse jobs: one per attachment, nothing else for them.
        List<ProcessingJob> jobsForAttachments = jobRepo.findAll().stream()
                .filter(j -> attachmentIds.contains(j.getReferenceId()))
                .toList();
        assertThat(jobsForAttachments).hasSize(3);
        assertThat(jobsForAttachments).allMatch(j -> "PARSE_RESUME".equals(j.getJobType()));
        assertThat(jobsForAttachments).allMatch(j -> j.getOrganizationId().equals(hook.orgId()));
    }

    @Test
    void concurrentIdenticalDeliveriesCreateExactlyOneChain() throws Exception {
        var hook = hook("race");
        byte[] body = jsonDelivery("hr@agency.example", null, "race", "resume.pdf", smallPdf());
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-race");
        long timestamp = java.time.Instant.now().getEpochSecond();
        UUID orgId = hook.orgId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> task = () -> {
            start.await();
            // Fresh builder per thread - identical signed request bytes/headers.
            return mockMvc.perform(signedJson(hook.credential().secret(), orgId.toString(),
                    hook.credential().keyId(), timestamp, nonce, idem, body)).andReturn();
        };
        Future<MvcResult> first = pool.submit(task);
        Future<MvcResult> second = pool.submit(task);
        start.countDown();
        MvcResult r1 = first.get(30, TimeUnit.SECONDS);
        MvcResult r2 = second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        int s1 = r1.getResponse().getStatus();
        int s2 = r2.getResponse().getStatus();
        assertThat(s1).isEqualTo(202);
        assertThat(s2).isEqualTo(202);
        // Exactly one thread reserved the delivery; the loser resolves against
        // the committed winner. Which thread wins is nondeterministic.
        String b1 = r1.getResponse().getContentAsString();
        String b2 = r2.getResponse().getContentAsString();
        assertThat(b1 + "\n" + b2).contains("QUEUED");
        assertThat(b1 + "\n" + b2).contains("DUPLICATE");
        JsonNode j1 = objectMapper.readTree(b1);
        JsonNode j2 = objectMapper.readTree(b2);
        assertThat(j1.path("deliveryId").asText())
                .isEqualTo(j2.path("deliveryId").asText());
        assertThat(j1.path("inboundMessageId").asText())
                .isEqualTo(j2.path("inboundMessageId").asText());

        // Exactly one durable delivery, one message, one attachment, one job.
        assertThat(deliveryRepo.findByOrganizationIdAndIdempotencyKey(orgId, idem)).isPresent();
        assertThat(deliveryRepo.countByOrganizationId(orgId)).isEqualTo(1);
        assertThat(messageRepo.countByOrganizationId(orgId)).isEqualTo(1);
        InboundMessage message = messageRepo.findAll().stream()
                .filter(m -> m.getOrganizationId().equals(orgId))
                .findFirst().orElseThrow();
        assertThat(attachmentRepo.findByMessageId(message.getId())).hasSize(1);
        UUID attachmentId = attachmentRepo.findByMessageId(message.getId()).get(0).getId();
        assertThat(jobRepo.findAll().stream()
                .filter(j -> attachmentId.equals(j.getReferenceId()))
                .count()).isEqualTo(1);
    }

    @Test
    void redeliveryDoesNotCreateAnotherResumeVersion() throws Exception {
        var hook = hook("redeliver");
        // Deterministic searchable email so the candidate can be located reliably.
        String email = "redeliver." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        byte[] pdf = TestResumePdf.create(TestResumePdf.resume(
                "J2 Redeliver", email, "Pune", 3, "Jan 2026"));
        byte[] body = jsonDelivery("hr@agency.example", null, "living profile", "resume.pdf", pdf);
        String nonce = WebhookTestSupport.randomNonce();
        String idem = WebhookTestSupport.randomIdempotencyKey("j2-redeliver");

        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(), nonce, idem, body))
                .andExpect(status().isAccepted());

        // Wait for the worker to promote the resume into a candidate.
        com.fasterxml.jackson.databind.JsonNode page = awaitJson(
                "/api/v1/candidates?search=" + email, hook.org().jwt(),
                p -> p.path("totalElements").asInt() >= 1,
                Duration.ofSeconds(30));
        UUID candidateId = UUID.fromString(page.path("content").get(0).path("id").asText());
        int versionsBefore = getJson("/api/v1/candidates/" + candidateId + "/versions",
                hook.org().jwt()).size();

        // Idempotent re-delivery: original result, no new resume version.
        mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(), idem, body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        Thread.sleep(1500);
        int versionsAfter = getJson("/api/v1/candidates/" + candidateId + "/versions",
                hook.org().jwt()).size();
        assertThat(versionsAfter).isEqualTo(versionsBefore);
    }

    @Test
    void forwarderAndClaimedOriginalSenderAreStoredSeparately() throws Exception {
        var hook = hook("provenance");
        byte[] body = jsonDelivery("forwarder-mailbox@agency.example", "claimed-original@candidate.example",
                "fwd: candidate", "resume.pdf", smallPdf());

        MvcResult result = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-prov"), body))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID messageId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("inboundMessageId").asText());
        InboundMessage message = messageRepo.findById(messageId).orElseThrow();
        assertThat(message.getSenderEmail()).isEqualTo("forwarder-mailbox@agency.example");
        assertThat(message.getOriginalSenderClaim()).isEqualTo("claimed-original@candidate.example");
        assertThat(message.getSenderEmail()).isNotEqualTo(message.getOriginalSenderClaim());
    }

    @Test
    void missingOriginalSenderRemainsUnknown() throws Exception {
        var hook = hook("prov-unknown");
        byte[] body = jsonDelivery("forwarder-mailbox@agency.example", null,
                "no original claimed", "resume.pdf", smallPdf());

        MvcResult result = mockMvc.perform(performSignedJson(hook.credential(), hook.orgId().toString(),
                        WebhookTestSupport.randomNonce(),
                        WebhookTestSupport.randomIdempotencyKey("j2-prov-unk"), body))
                .andExpect(status().isAccepted())
                .andReturn();

        UUID messageId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("inboundMessageId").asText());
        InboundMessage message = messageRepo.findById(messageId).orElseThrow();
        assertThat(message.getSenderEmail()).isEqualTo("forwarder-mailbox@agency.example");
        assertThat(message.getOriginalSenderClaim()).isNull();
    }

    private record Hook(WebhookOrg org, Credential credential) {
        UUID orgId() { return org.org().getId(); }
    }

    private Hook hook(String prefix) throws Exception {
        WebhookOrg org = newWebhookOrg(prefix);
        Credential credential = provisionCredential(org);
        return new Hook(org, credential);
    }
}
