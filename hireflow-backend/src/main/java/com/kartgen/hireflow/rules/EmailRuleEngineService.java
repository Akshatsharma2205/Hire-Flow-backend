package com.kartgen.hireflow.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailRuleEngineService {

    private final EmailRuleRepository ruleRepo;
    private final ObjectMapper objectMapper;

    public record AttachmentEnvelope(
            String filename,
            String detectedMime,
            long sizeBytes
    ) {}

    public record MessageEnvelope(
            UUID organizationId,
            UUID connectionId,
            String folderName,
            String senderEmail,
            String senderName,
            String subject,
            String bodyText, // IN-MEMORY ONLY, NEVER PERSISTED
            List<AttachmentEnvelope> attachments
    ) {}

    public record EvaluationDecision(
            DecisionStatus status,
            UUID matchedRuleId,
            String matchedRuleName,
            UUID targetJobId,
            String duplicatePolicy,
            String action,
            String explanation,
            Map<String, String> matchedConditions,
            Map<String, String> failedConditions
    ) {
        public enum DecisionStatus {
            INCLUDED,
            EXCLUDED,
            REVIEW_REQUIRED,
            NO_MATCH
        }
    }

    /**
     * Deterministically evaluates an inbound message envelope against the active rules
     * of the organization, ordered by priority ASC (lower number = higher precedence).
     */
    public EvaluationDecision evaluate(MessageEnvelope env) {
        List<EmailRule> rules = ruleRepo
                .findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(env.organizationId());

        if (rules.isEmpty()) {
            return new EvaluationDecision(
                    EvaluationDecision.DecisionStatus.INCLUDED,
                    null,
                    null,
                    null,
                    "UPDATE_PROFILE_ONLY",
                    "PROCESS",
                    "No active rules configured for organization. Default intake applied.",
                    Map.of(),
                    Map.of()
            );
        }

        // Collect matching rules
        List<RuleMatchResult> matchingResults = new ArrayList<>();
        for (EmailRule rule : rules) {
            RuleMatchResult res = evaluateRule(rule, env);
            if (res.isMatched()) {
                matchingResults.add(res);
            }
        }

        if (matchingResults.isEmpty()) {
            return new EvaluationDecision(
                    EvaluationDecision.DecisionStatus.INCLUDED,
                    null,
                    null,
                    null,
                    "UPDATE_PROFILE_ONLY",
                    "PROCESS",
                    "No email rules matched this message. Default intake applied.",
                    Map.of(),
                    Map.of()
            );
        }

        // Check for conflicting rules at the same highest priority level
        int topPriority = matchingResults.get(0).getRule().getPriority();
        List<RuleMatchResult> topTierMatches = matchingResults.stream()
                .filter(m -> Objects.equals(m.getRule().getPriority(), topPriority))
                .toList();

        Set<UUID> targetJobs = new HashSet<>();
        Set<String> actions = new HashSet<>();
        for (RuleMatchResult tm : topTierMatches) {
            if (tm.getRule().getTargetJobId() != null) {
                targetJobs.add(tm.getRule().getTargetJobId());
            }
            actions.add(tm.getRule().getActionOnMatch());
        }

        if (targetJobs.size() > 1 || (actions.size() > 1 && actions.contains("EXCLUDE") && actions.contains("PROCESS"))) {
            EmailRule primary = topTierMatches.get(0).getRule();
            String explanation = String.format(
                    "Ambiguous matching: %d conflicting rules matched at priority %d with differing target jobs or actions.",
                    topTierMatches.size(), topPriority);
            log.info("Rule conflict detected for org={}: {}", env.organizationId(), explanation);
            return new EvaluationDecision(
                    EvaluationDecision.DecisionStatus.REVIEW_REQUIRED,
                    primary.getId(),
                    primary.getName(),
                    null,
                    primary.getDuplicatePolicy(),
                    "ROUTE_TO_REVIEW",
                    explanation,
                    topTierMatches.get(0).getPassedConditions(),
                    Map.of("conflict", "Multiple conflicting rules at priority " + topPriority)
            );
        }

        // Winning rule is the first top match
        RuleMatchResult winner = topTierMatches.get(0);
        EmailRule winRule = winner.getRule();

        EvaluationDecision.DecisionStatus status;
        if ("EXCLUDE".equalsIgnoreCase(winRule.getActionOnMatch())) {
            status = EvaluationDecision.DecisionStatus.EXCLUDED;
        } else if ("ROUTE_TO_REVIEW".equalsIgnoreCase(winRule.getActionOnMatch())) {
            status = EvaluationDecision.DecisionStatus.REVIEW_REQUIRED;
        } else {
            status = EvaluationDecision.DecisionStatus.INCLUDED;
        }

        String explanation = String.format(
                "Matched rule '%s' (priority %d). Action: %s.",
                winRule.getName(), winRule.getPriority(), winRule.getActionOnMatch());

        return new EvaluationDecision(
                status,
                winRule.getId(),
                winRule.getName(),
                winRule.getTargetJobId(),
                winRule.getDuplicatePolicy(),
                winRule.getActionOnMatch(),
                explanation,
                winner.getPassedConditions(),
                winner.getFailedConditions()
        );
    }

    /**
     * Dry-run preview evaluation: runs the sample message against all tenant rules
     * and produces a detailed trace without saving any files or database changes.
     */
    public RulePreviewResponse preview(UUID orgId, RulePreviewRequest req) {
        List<AttachmentEnvelope> attachments = new ArrayList<>();
        if (req.getAttachments() != null) {
            for (Object attObj : req.getAttachments()) {
                if (attObj instanceof String s) {
                    String mime = s.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf"
                            : (s.toLowerCase(Locale.ROOT).endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/octet-stream");
                    attachments.add(new AttachmentEnvelope(s, mime, 1024L * 100));
                } else if (attObj instanceof Map<?, ?> m) {
                    String fname = Objects.toString(m.get("filename"), "attachment.pdf");
                    String mime = Objects.toString(m.get("detectedMime"), "application/pdf");
                    long sz = 1024L * 100;
                    if (m.get("sizeBytes") instanceof Number n) sz = n.longValue();
                    attachments.add(new AttachmentEnvelope(fname, mime, sz));
                }
            }
        }

        MessageEnvelope env = new MessageEnvelope(
                orgId,
                req.getConnectionId(),
                req.getFolderName(),
                req.getEffectiveSender(),
                null,
                req.getEffectiveSubject(),
                req.getEffectiveBody(),
                attachments
        );

        List<EmailRule> allRules = ruleRepo.findByOrganizationIdOrderByPriorityAsc(orgId);
        List<RulePreviewResponse.RuleEvaluationTrace> traces = new ArrayList<>();
        RuleMatchResult winningMatch = null;

        for (EmailRule rule : allRules) {
            if (!Boolean.TRUE.equals(rule.getEnabled())) {
                traces.add(RulePreviewResponse.RuleEvaluationTrace.builder()
                        .ruleId(rule.getId())
                        .ruleName(rule.getName())
                        .priority(rule.getPriority())
                        .matched(false)
                        .reason("Rule is disabled")
                        .passedConditions(Map.of())
                        .failedConditions(Map.of("enabled", "false"))
                        .build());
                continue;
            }

            RuleMatchResult res = evaluateRule(rule, env);
            traces.add(RulePreviewResponse.RuleEvaluationTrace.builder()
                    .ruleId(rule.getId())
                    .ruleName(rule.getName())
                    .priority(rule.getPriority())
                    .matched(res.isMatched())
                    .reason(res.isMatched() ? "All conditions passed" : "Failed conditions: " + res.getFailedConditions().keySet())
                    .passedConditions(res.getPassedConditions())
                    .failedConditions(res.getFailedConditions())
                    .build());

            if (res.isMatched() && winningMatch == null) {
                winningMatch = res;
            }
        }

        EvaluationDecision decision = evaluate(env);

        boolean matched = decision.status() != EvaluationDecision.DecisionStatus.NO_MATCH
                && decision.matchedRuleId() != null;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("matchedConditions", decision.matchedConditions());
        details.put("failedConditions", decision.failedConditions());
        details.put("totalRulesEvaluated", allRules.size());

        return RulePreviewResponse.builder()
                .matched(matched)
                .matchedRuleId(decision.matchedRuleId())
                .matchedRuleName(decision.matchedRuleName())
                .targetJobId(decision.targetJobId())
                .action(decision.action())
                .duplicatePolicy(decision.duplicatePolicy())
                .explanation(decision.explanation())
                .details(details)
                .traces(traces)
                .build();
    }

    @Data
    @Builder
    private static class RuleMatchResult {
        private EmailRule rule;
        private boolean matched;
        private Map<String, String> passedConditions;
        private Map<String, String> failedConditions;
    }

    private RuleMatchResult evaluateRule(EmailRule rule, MessageEnvelope env) {
        Map<String, String> passed = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();

        // 1. Mailbox Connection Scope
        if (rule.getConnectionId() != null) {
            if (env.connectionId() != null && rule.getConnectionId().equals(env.connectionId())) {
                passed.put("connectionId", "Matches " + rule.getConnectionId());
            } else {
                failed.put("connectionId", "Expected " + rule.getConnectionId() + " but was " + env.connectionId());
            }
        }

        // 2. Folder Scope
        if (rule.getFolderFilter() != null && !rule.getFolderFilter().isBlank()) {
            if (env.folderName() != null && rule.getFolderFilter().equalsIgnoreCase(env.folderName())) {
                passed.put("folderFilter", "Matches " + rule.getFolderFilter());
            } else {
                failed.put("folderFilter", "Expected " + rule.getFolderFilter() + " but was " + env.folderName());
            }
        }

        // 3. Sender Exclusions
        List<String> senderExcludes = parseJsonList(rule.getSenderExcludeJson());
        if (!senderExcludes.isEmpty() && env.senderEmail() != null) {
            for (String pattern : senderExcludes) {
                if (matchesSenderPattern(pattern, env.senderEmail())) {
                    failed.put("senderExclude", "Sender matches excluded pattern: " + pattern);
                    break;
                }
            }
        }

        // 4. Sender Inclusions
        List<String> senderIncludes = parseJsonList(rule.getSenderIncludeJson());
        if (!senderIncludes.isEmpty()) {
            boolean matchedInclude = false;
            if (env.senderEmail() != null) {
                for (String pattern : senderIncludes) {
                    if (matchesSenderPattern(pattern, env.senderEmail())) {
                        matchedInclude = true;
                        passed.put("senderInclude", "Sender matches pattern: " + pattern);
                        break;
                    }
                }
            }
            if (!matchedInclude) {
                failed.put("senderInclude", "Sender " + env.senderEmail() + " did not match required patterns");
            }
        }

        // 5. Subject Exclusions
        List<String> subjectExcludes = parseJsonList(rule.getSubjectExcludeJson());
        if (!subjectExcludes.isEmpty() && env.subject() != null) {
            String lowerSubject = env.subject().toLowerCase(Locale.ROOT);
            for (String kw : subjectExcludes) {
                if (lowerSubject.contains(kw.toLowerCase(Locale.ROOT))) {
                    failed.put("subjectExclude", "Subject contains excluded keyword: " + kw);
                    break;
                }
            }
        }

        // 6. Subject Keywords (plain substring search, safe from regex syntax injection)
        List<String> subjectKeywords = parseJsonList(rule.getSubjectKeywordsJson());
        if (!subjectKeywords.isEmpty()) {
            boolean matchedSubject = false;
            if (env.subject() != null) {
                String lowerSubject = env.subject().toLowerCase(Locale.ROOT);
                for (String kw : subjectKeywords) {
                    if (lowerSubject.contains(kw.toLowerCase(Locale.ROOT))) {
                        matchedSubject = true;
                        passed.put("subjectKeywords", "Subject contains keyword: " + kw);
                        break;
                    }
                }
            }
            if (!matchedSubject) {
                failed.put("subjectKeywords", "Subject does not contain any of required keywords: " + subjectKeywords);
            }
        }

        // 7. Body Exclusions (In-Memory Only)
        List<String> bodyExcludes = parseJsonList(rule.getBodyExcludeJson());
        if (!bodyExcludes.isEmpty() && env.bodyText() != null) {
            String lowerBody = env.bodyText().toLowerCase(Locale.ROOT);
            for (String kw : bodyExcludes) {
                if (lowerBody.contains(kw.toLowerCase(Locale.ROOT))) {
                    failed.put("bodyExclude", "Body contains excluded keyword: " + kw);
                    break;
                }
            }
        }

        // 8. Body Keywords (In-Memory Only)
        List<String> bodyKeywords = parseJsonList(rule.getBodyKeywordsJson());
        if (!bodyKeywords.isEmpty()) {
            boolean matchedBody = false;
            if (env.bodyText() != null) {
                String lowerBody = env.bodyText().toLowerCase(Locale.ROOT);
                for (String kw : bodyKeywords) {
                    if (lowerBody.contains(kw.toLowerCase(Locale.ROOT))) {
                        matchedBody = true;
                        passed.put("bodyKeywords", "Body contains keyword: " + kw);
                        break;
                    }
                }
            }
            if (!matchedBody) {
                failed.put("bodyKeywords", "Body does not contain any of required keywords: " + bodyKeywords);
            }
        }

        // 9. Attachment Checks
        if (Boolean.TRUE.equals(rule.getRequireAttachment())) {
            if (env.attachments() == null || env.attachments().isEmpty()) {
                failed.put("requireAttachment", "Rule requires an attachment but message has none");
            } else {
                passed.put("requireAttachment", "Message has " + env.attachments().size() + " attachment(s)");
            }
        }

        List<String> allowedMimes = parseJsonList(rule.getAllowedMimesJson());
        if (!allowedMimes.isEmpty() && env.attachments() != null && !env.attachments().isEmpty()) {
            boolean hasAllowedMime = env.attachments().stream().anyMatch(att ->
                    att.detectedMime() != null && allowedMimes.stream().anyMatch(m ->
                            m.equalsIgnoreCase(att.detectedMime())));
            if (hasAllowedMime) {
                passed.put("allowedMimes", "Attachment matches allowed MIME type");
            } else {
                failed.put("allowedMimes", "No attachments matched allowed MIME types: " + allowedMimes);
            }
        }

        if (rule.getMinAttachmentSizeBytes() != null && env.attachments() != null && !env.attachments().isEmpty()) {
            boolean satisfiesMin = env.attachments().stream()
                    .anyMatch(att -> att.sizeBytes() >= rule.getMinAttachmentSizeBytes());
            if (!satisfiesMin) {
                failed.put("minAttachmentSizeBytes", "Attachment smaller than required " + rule.getMinAttachmentSizeBytes());
            }
        }

        if (rule.getMaxAttachmentSizeBytes() != null && env.attachments() != null && !env.attachments().isEmpty()) {
            boolean satisfiesMax = env.attachments().stream()
                    .anyMatch(att -> att.sizeBytes() <= rule.getMaxAttachmentSizeBytes());
            if (!satisfiesMax) {
                failed.put("maxAttachmentSizeBytes", "Attachment larger than allowed " + rule.getMaxAttachmentSizeBytes());
            }
        }

        boolean matched = failed.isEmpty();
        return RuleMatchResult.builder()
                .rule(rule)
                .matched(matched)
                .passedConditions(passed)
                .failedConditions(failed)
                .build();
    }

    private boolean matchesSenderPattern(String pattern, String sender) {
        if (pattern == null || sender == null) return false;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String s = sender.trim().toLowerCase(Locale.ROOT);

        if (p.equals(s) || p.equals("*")) return true;

        if (p.startsWith("*@")) {
            String domain = p.substring(2);
            return s.endsWith("@" + domain);
        }
        if (p.endsWith("@*")) {
            String user = p.substring(0, p.length() - 2);
            return s.startsWith(user + "@");
        }
        if (p.contains("*")) {
            String regex = "^" + Pattern.quote(p).replace("*", "\\E.*\\Q") + "$";
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(s).matches();
        }
        return s.contains(p);
    }

    public List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON string list '{}': {}", json, e.getMessage());
            return List.of();
        }
    }

    public String toJsonList(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("Failed to serialize string list to JSON: {}", e.getMessage());
            return null;
        }
    }
}
