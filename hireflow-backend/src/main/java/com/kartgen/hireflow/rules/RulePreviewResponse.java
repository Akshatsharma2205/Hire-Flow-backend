package com.kartgen.hireflow.rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RulePreviewResponse {
    private boolean matched;
    private UUID matchedRuleId;
    private String matchedRuleName;
    private UUID targetJobId;
    private String action;
    private String duplicatePolicy;
    private String explanation;
    private Map<String, Object> details;
    private List<RuleEvaluationTrace> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleEvaluationTrace {
        private UUID ruleId;
        private String ruleName;
        private Integer priority;
        private boolean matched;
        private String reason;
        private Map<String, String> passedConditions;
        private Map<String, String> failedConditions;
    }
}
