package com.kartgen.hireflow.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartgen.hireflow.e2e.HireFlowE2ETestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Empirical Challenger Test Suite for Milestone 2:
 * Rule Engine & Multi-Tenant Isolation Stress Verification.
 */
public class Milestone2RuleEngineChallengerTest extends HireFlowE2ETestSupport {

    @Autowired
    private EmailRuleRepository ruleRepo;

    @Autowired
    private EmailRuleEngineService engineService;

    private String authToken;
    private final UUID orgA = UUID.fromString(TENANT_A_ORG_ID);
    private final UUID orgB = UUID.fromString(TENANT_B_ORG_ID);

    private final UUID job1 = UUID.fromString(JOB_BACKEND_ID);
    private final UUID job2 = UUID.fromString(JOB_FULLSTACK_ID);

    @BeforeEach
    void setupChallenger() throws Exception {
        authToken = login();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM organizations WHERE id = ?", Integer.class, orgB);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO organizations (id, name, slug, status, default_timezone) VALUES (?, ?, ?, ?, ?)",
                    orgB, "Tenant B Corp", "tenant-b", "ACTIVE", "UTC");
        }
        ruleRepo.deleteAll();
    }

    // =========================================================================
    // FOCUS 1: Deterministic Rule Matching Order (priority ASC)
    // =========================================================================

    @Test
    @DisplayName("Challenge 1.1: Priority ASC order strictly determines winner among multiple matches")
    void testDeterministicPriorityAscOrder() {
        // Create 3 rules with priorities 10, 5, 20 matching same email
        EmailRule ruleLowPrecedence = EmailRule.builder()
                .organizationId(orgA)
                .name("Priority 20 Rule")
                .priority(20)
                .enabled(true)
                .subjectKeywordsJson("[\"Developer\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleWinner = EmailRule.builder()
                .organizationId(orgA)
                .name("Priority 5 Winner Rule")
                .priority(5)
                .enabled(true)
                .subjectKeywordsJson("[\"Developer\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleMid = EmailRule.builder()
                .organizationId(orgA)
                .name("Priority 10 Rule")
                .priority(10)
                .enabled(true)
                .subjectKeywordsJson("[\"Developer\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        // Save out of priority order to verify query ordering
        ruleRepo.saveAll(List.of(ruleLowPrecedence, ruleWinner, ruleMid));

        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "applicant@test.com", "Applicant",
                "Application: Senior Java Developer", "Hello team", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.matchedRuleId()).isEqualTo(ruleWinner.getId());
        assertThat(decision.matchedRuleName()).isEqualTo("Priority 5 Winner Rule");
        assertThat(decision.targetJobId()).isEqualTo(job1);
    }

    @Test
    @DisplayName("Challenge 1.2: Disabled higher-priority rule is skipped in favor of active rule")
    void testDisabledHigherPriorityRuleSkipped() {
        EmailRule disabledRule = EmailRule.builder()
                .organizationId(orgA)
                .name("Disabled Priority 1 Rule")
                .priority(1)
                .enabled(false)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule activeRule = EmailRule.builder()
                .organizationId(orgA)
                .name("Active Priority 10 Rule")
                .priority(10)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        ruleRepo.saveAll(List.of(disabledRule, activeRule));

        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "cand@test.com", "Candidate",
                "Senior Engineer Application", "Body text", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.matchedRuleId()).isEqualTo(activeRule.getId());
        assertThat(decision.targetJobId()).isEqualTo(job2);
    }

    @Test
    @DisplayName("Challenge 1.3: Negative and zero priority boundary values evaluated deterministically")
    void testNegativeAndZeroPriorityBoundaryValues() {
        EmailRule negPriorityRule = EmailRule.builder()
                .organizationId(orgA)
                .name("Negative Priority Rule")
                .priority(-5)
                .enabled(true)
                .subjectKeywordsJson("[\"Urgent\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule zeroPriorityRule = EmailRule.builder()
                .organizationId(orgA)
                .name("Zero Priority Rule")
                .priority(0)
                .enabled(true)
                .subjectKeywordsJson("[\"Urgent\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        ruleRepo.saveAll(List.of(zeroPriorityRule, negPriorityRule));

        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "cand@test.com", "Candidate",
                "Urgent Application", "Body", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.matchedRuleId()).isEqualTo(negPriorityRule.getId());
        assertThat(decision.targetJobId()).isEqualTo(job1);
    }

    // =========================================================================
    // FOCUS 2: Dry-Run Preview Without Database Mutation
    // =========================================================================

    @Test
    @DisplayName("Challenge 2.1: Dry-run preview yields accurate traces without any database mutation")
    void testDryRunPreviewNonPersistenceAndAccuracy() throws Exception {
        EmailRule rule = ruleRepo.save(EmailRule.builder()
                .organizationId(orgA)
                .name("Fullstack Engineer Rule")
                .priority(3)
                .enabled(true)
                .subjectKeywordsJson("[\"Fullstack\", \"React\"]")
                .targetJobId(job2)
                .duplicatePolicy("ALLOW_NEW_SUBMISSION")
                .actionOnMatch("PROCESS")
                .requireAttachment(true)
                .allowedMimesJson("[\"application/pdf\"]")
                .build());

        // Snapshot database counts before preview
        long rulesBefore = ruleRepo.count();
        long messagesBefore = messageRepo.count();
        long attachmentsBefore = attachmentRepo.count();
        long candidatesBefore = candidateRepo.count();
        long applicationsBefore = applicationRepo.count();
        long reviewCasesBefore = reviewCaseRepo.count();

        String previewPayload = objectMapper.writeValueAsString(Map.of(
                "sender", "applicant@techcorp.com",
                "subject", "Application for Fullstack React Role",
                "body", "Please review my attached resume.",
                "attachments", List.of(Map.of(
                        "filename", "developer_cv.pdf",
                        "detectedMime", "application/pdf",
                        "sizeBytes", 50000
                ))
        ));

        MvcResult res = mockMvc.perform(post("/api/v1/rules/preview")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.matchedRuleId").value(rule.getId().toString()))
                .andExpect(jsonPath("$.targetJobId").value(job2.toString()))
                .andExpect(jsonPath("$.duplicatePolicy").value("ALLOW_NEW_SUBMISSION"))
                .andExpect(jsonPath("$.traces").isArray())
                .andExpect(jsonPath("$.traces[0].matched").value(true))
                .andExpect(jsonPath("$.explanation").isNotEmpty())
                .andReturn();

        // Snapshot database counts after preview
        long rulesAfter = ruleRepo.count();
        long messagesAfter = messageRepo.count();
        long attachmentsAfter = attachmentRepo.count();
        long candidatesAfter = candidateRepo.count();
        long applicationsAfter = applicationRepo.count();
        long reviewCasesAfter = reviewCaseRepo.count();

        // Assert strictly zero DB changes occurred
        assertThat(rulesAfter).isEqualTo(rulesBefore);
        assertThat(messagesAfter).isEqualTo(messagesBefore);
        assertThat(attachmentsAfter).isEqualTo(attachmentsBefore);
        assertThat(candidatesAfter).isEqualTo(candidatesBefore);
        assertThat(applicationsAfter).isEqualTo(applicationsBefore);
        assertThat(reviewCasesAfter).isEqualTo(reviewCasesBefore);
    }

    // =========================================================================
    // FOCUS 3: Strict Multi-Tenant Isolation
    // =========================================================================

    @Test
    @DisplayName("Challenge 3.1: Org A rules never evaluate or match against Org B messages")
    void testStrictMultiTenantEvaluationIsolation() {
        // Org A has a rule for "Cybersecurity"
        EmailRule ruleOrgA = ruleRepo.save(EmailRule.builder()
                .organizationId(orgA)
                .name("Org A Security Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"Cybersecurity\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build());

        // Org B has a rule for "DevOps"
        EmailRule ruleOrgB = ruleRepo.save(EmailRule.builder()
                .organizationId(orgB)
                .name("Org B DevOps Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"DevOps\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build());

        // Test envelope for Org B containing Org A's keyword
        EmailRuleEngineService.MessageEnvelope envelopeForOrgB = new EmailRuleEngineService.MessageEnvelope(
                orgB, null, "INBOX", "cand@test.com", "Candidate",
                "Cybersecurity Specialist", "Body text", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decisionB = engineService.evaluate(envelopeForOrgB);
        // Org B has no rule for Cybersecurity, so it MUST NOT match Org A's rule
        assertThat(decisionB.matchedRuleId()).isNull();
        assertThat(decisionB.explanation()).contains("No email rules matched this message");

        // Test envelope for Org A containing Org B's keyword
        EmailRuleEngineService.MessageEnvelope envelopeForOrgA = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "cand@test.com", "Candidate",
                "DevOps Engineer Application", "Body text", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decisionA = engineService.evaluate(envelopeForOrgA);
        // Org A has no rule for DevOps, so it MUST NOT match Org B's rule
        assertThat(decisionA.matchedRuleId()).isNull();
        assertThat(decisionA.explanation()).contains("No email rules matched this message");
    }

    @Test
    @DisplayName("Challenge 3.2: REST API enforces cross-tenant rule isolation on GET/PUT/PATCH/DELETE")
    void testRestApiCrossTenantIsolation() throws Exception {
        // Create rule belonging to Org B
        EmailRule ruleOrgB = ruleRepo.save(EmailRule.builder()
                .organizationId(orgB)
                .name("Org B Secret Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"Confidential\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build());

        // Org A user authenticated with authToken tries to read Org B rule
        mockMvc.perform(get("/api/v1/rules/" + ruleOrgB.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());

        // Org A user tries to update Org B rule
        mockMvc.perform(put("/api/v1/rules/" + ruleOrgB.getId())
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Tampered Name\"}"))
                .andExpect(status().isNotFound());

        // Org A user tries to toggle status of Org B rule
        mockMvc.perform(patch("/api/v1/rules/" + ruleOrgB.getId() + "/status")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isNotFound());

        // Org A user tries to delete Org B rule
        mockMvc.perform(delete("/api/v1/rules/" + ruleOrgB.getId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());

        // Verify Org B rule still exists unmodified
        EmailRule reloaded = ruleRepo.findById(ruleOrgB.getId()).orElse(null);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Org B Secret Rule");
        assertThat(reloaded.getEnabled()).isTrue();
    }

    // =========================================================================
    // FOCUS 4: Edge Cases (Keywords, Duplicate Policies, Ambiguity)
    // =========================================================================

    @Test
    @DisplayName("Challenge 4.1: Empty conditions rejected with HTTP 400")
    void testEmptyConditionsRejected() throws Exception {
        String emptyPayload = objectMapper.writeValueAsString(Map.of(
                "name", "Empty Condition Rule",
                "priority", 50,
                "conditions", Map.of()
        ));

        mockMvc.perform(post("/api/v1/rules")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Challenge 4.2: Case-insensitive keyword and sender pattern matching")
    void testCaseInsensitiveMatching() {
        EmailRule rule = ruleRepo.save(EmailRule.builder()
                .organizationId(orgA)
                .name("Case Insensitive Rule")
                .priority(1)
                .enabled(true)
                .senderIncludeJson("[\"*@TECH-AGENCY.COM\"]")
                .subjectKeywordsJson("[\"JaVa\", \"SpRiNg\"]")
                .bodyKeywordsJson("[\"DoCkEr\"]")
                .folderFilter("InBoX")
                .allowedMimesJson("[\"APPLICATION/PDF\"]")
                .actionOnMatch("PROCESS")
                .requireAttachment(true)
                .build());

        // Inbound message with mixed/opposite casing
        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "inbox", "candidate@tech-agency.com", "Candidate",
                "Application: java and spring architect", "I know docker and cloud.",
                List.of(new EmailRuleEngineService.AttachmentEnvelope("resume.pdf", "application/pdf", 1024))
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.matchedRuleId()).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("Challenge 4.3: All 4 duplicate policies persist and evaluate correctly")
    void testDuplicatePoliciesSupported() throws Exception {
        List<String> validPolicies = List.of(
                "ALLOW_NEW_SUBMISSION",
                "UPDATE_PROFILE_ONLY",
                "REJECT_DUPLICATE",
                "FLAG_HR_REVIEW"
        );

        for (String policy : validPolicies) {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "name", "Policy Test " + policy,
                    "priority", 10,
                    "conditions", Map.of("subjectKeywords", List.of("Role-" + policy)),
                    "duplicatePolicy", policy
            ));

            MvcResult res = mockMvc.perform(post("/api/v1/rules")
                            .header("Authorization", "Bearer " + authToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.duplicatePolicy").value(policy))
                    .andReturn();

            JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
            UUID ruleId = UUID.fromString(node.get("id").asText());

            EmailRule saved = ruleRepo.findById(ruleId).orElseThrow();
            assertThat(saved.getDuplicatePolicy()).isEqualTo(policy);
        }
    }

    @Test
    @DisplayName("Challenge 4.4: Conflicting rules at same top priority route to HR Review")
    void testAmbiguousMatchingRoutesToHrReview() {
        EmailRule ruleA = EmailRule.builder()
                .organizationId(orgA)
                .name("Job 1 Matcher")
                .priority(5)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleB = EmailRule.builder()
                .organizationId(orgA)
                .name("Job 2 Matcher")
                .priority(5)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleLower = EmailRule.builder()
                .organizationId(orgA)
                .name("Lower Priority Matcher")
                .priority(10)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        ruleRepo.saveAll(List.of(ruleA, ruleB, ruleLower));

        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "applicant@test.com", "Applicant",
                "Engineer Role", "Resume text", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.REVIEW_REQUIRED);
        assertThat(decision.action()).isEqualTo("ROUTE_TO_REVIEW");
        assertThat(decision.targetJobId()).isNull();
        assertThat(decision.explanation()).contains("Ambiguous matching: 2 conflicting rules matched at priority 5");
    }

    @Test
    @DisplayName("Challenge 4.5: Conflicting actions (EXCLUDE vs PROCESS) at same priority route to HR Review")
    void testConflictingActionsAtSamePriorityRoutesToReview() {
        EmailRule ruleProcess = EmailRule.builder()
                .organizationId(orgA)
                .name("Process Engineer Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .targetJobId(job1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleExclude = EmailRule.builder()
                .organizationId(orgA)
                .name("Exclude Agency Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"Engineer\"]")
                .actionOnMatch("EXCLUDE")
                .requireAttachment(false)
                .build();

        ruleRepo.saveAll(List.of(ruleProcess, ruleExclude));

        EmailRuleEngineService.MessageEnvelope envelope = new EmailRuleEngineService.MessageEnvelope(
                orgA, null, "INBOX", "cand@test.com", "Cand",
                "Engineer Application", "Body", List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(envelope);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.REVIEW_REQUIRED);
        assertThat(decision.action()).isEqualTo("ROUTE_TO_REVIEW");
    }
}
