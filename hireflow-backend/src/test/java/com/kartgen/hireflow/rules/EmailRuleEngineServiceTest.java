package com.kartgen.hireflow.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailRuleEngineServiceTest {

    @Mock
    private EmailRuleRepository ruleRepo;

    private EmailRuleEngineService engineService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId1 = UUID.randomUUID();
    private final UUID jobId2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engineService = new EmailRuleEngineService(ruleRepo, objectMapper);
    }

    @Test
    @DisplayName("Matches sender wildcard pattern and subject keywords")
    void testMatchRule_SubjectKeywordsAndSenderPattern() {
        EmailRule rule = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Java Backend Rule")
                .priority(10)
                .enabled(true)
                .senderIncludeJson("[\"*@tech-agency.com\"]")
                .subjectKeywordsJson("[\"Java\", \"Spring\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .requireAttachment(true)
                .build();

        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, "INBOX", "recruiter@tech-agency.com", "Recruiter",
                "Application for Senior Java Developer", null,
                List.of(new EmailRuleEngineService.AttachmentEnvelope("resume.pdf", "application/pdf", 2048))
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(env);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.targetJobId()).isEqualTo(jobId1);
        assertThat(decision.matchedRuleId()).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("Excludes sender matching exclude pattern")
    void testExcludeRule_SenderExcluded() {
        EmailRule rule = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Reject Spam Rule")
                .priority(1)
                .enabled(true)
                .senderExcludeJson("[\"*@spam.com\"]")
                .subjectKeywordsJson("[\"Java\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .build();

        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, "INBOX", "bot@spam.com", null,
                "Java job opening", null,
                List.of(new EmailRuleEngineService.AttachmentEnvelope("resume.pdf", "application/pdf", 2048))
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(env);
        // Rule doesn't match because sender is excluded; falls back to default intake
        assertThat(decision.matchedRuleId()).isNull();
    }

    @Test
    @DisplayName("In-memory body keyword evaluation without database persistence")
    void testBodyKeywords_InMemoryMatching() {
        EmailRule rule = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Body Keyword Rule")
                .priority(5)
                .enabled(true)
                .bodyKeywordsJson("[\"Kubernetes\", \"Docker\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, "INBOX", "candidate@test.com", null,
                "Inquiry", "I have extensive experience with Docker and Kubernetes in production.",
                List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(env);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.matchedRuleId()).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("Special regex characters in keywords evaluated safely without crashing")
    void testRegexSpecialCharacters_EvaluatedSafely() {
        EmailRule rule = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("C++ / C# Rule")
                .priority(5)
                .enabled(true)
                .subjectKeywordsJson("[\"C++\", \"C# [Principal]\", \"(Senior+)\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, "INBOX", "cand@test.com", null,
                "Application: C++ Developer (Senior+)", null,
                List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(env);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.INCLUDED);
        assertThat(decision.matchedRuleId()).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("Conflicting rules at the same priority level trigger review case")
    void testConflictingRules_SamePriorityDifferentJobs_RoutesToReview() {
        EmailRule ruleA = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Rule Java")
                .priority(10)
                .enabled(true)
                .subjectKeywordsJson("[\"Developer\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        EmailRule ruleB = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Rule Fullstack")
                .priority(10)
                .enabled(true)
                .subjectKeywordsJson("[\"Developer\"]")
                .targetJobId(jobId2)
                .actionOnMatch("PROCESS")
                .requireAttachment(false)
                .build();

        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(ruleA, ruleB));

        EmailRuleEngineService.MessageEnvelope env = new EmailRuleEngineService.MessageEnvelope(
                orgId, null, "INBOX", "applicant@test.com", null,
                "Developer Application", null,
                List.of()
        );

        EmailRuleEngineService.EvaluationDecision decision = engineService.evaluate(env);
        assertThat(decision.status()).isEqualTo(EmailRuleEngineService.EvaluationDecision.DecisionStatus.REVIEW_REQUIRED);
        assertThat(decision.action()).isEqualTo("ROUTE_TO_REVIEW");
        assertThat(decision.explanation()).contains("conflicting rules");
    }

    @Test
    @DisplayName("Dry run preview evaluation returns detailed traces")
    void testPreview_DryRunProducesTraces() {
        EmailRule rule = EmailRule.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .name("Preview Rule")
                .priority(1)
                .enabled(true)
                .subjectKeywordsJson("[\"Backend\"]")
                .targetJobId(jobId1)
                .actionOnMatch("PROCESS")
                .requireAttachment(true)
                .build();

        when(ruleRepo.findByOrganizationIdOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));
        when(ruleRepo.findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(orgId))
                .thenReturn(List.of(rule));

        RulePreviewRequest req = RulePreviewRequest.builder()
                .sender("jane@test.com")
                .subject("Senior Backend Architect")
                .body("Resume attached")
                .attachments(List.of("cv.pdf"))
                .build();

        RulePreviewResponse response = engineService.preview(orgId, req);
        assertThat(response.isMatched()).isTrue();
        assertThat(response.getMatchedRuleId()).isEqualTo(rule.getId());
        assertThat(response.getTraces()).hasSize(1);
        assertThat(response.getTraces().get(0).isMatched()).isTrue();
    }
}
