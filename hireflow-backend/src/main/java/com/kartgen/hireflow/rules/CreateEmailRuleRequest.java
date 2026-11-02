package com.kartgen.hireflow.rules;

import jakarta.validation.constraints.NotBlank;
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
public class CreateEmailRuleRequest {
    @NotBlank(message = "Rule name is required")
    private String name;

    private String description;
    private Integer priority;
    private Boolean enabled;

    private UUID connectionId;
    private String folderFilter;

    private List<String> senderInclude;
    private List<String> senderExclude;
    private List<String> subjectKeywords;
    private List<String> subjectExcludeKeywords;
    private List<String> bodyKeywords;
    private List<String> bodyExcludeKeywords;

    private Boolean requireAttachment;
    private List<String> allowedMimes;
    private Long minAttachmentSizeBytes;
    private Long maxAttachmentSizeBytes;

    private UUID targetJobId;
    private String duplicatePolicy;
    private String actionOnMatch;

    private Map<String, Object> conditions;

    public boolean hasAnyCondition() {
        if (conditions != null && !conditions.isEmpty()) {
            boolean hasNested = false;
            for (Object v : conditions.values()) {
                if (v instanceof List<?> list && !list.isEmpty()) hasNested = true;
                if (v instanceof String str && !str.isBlank()) hasNested = true;
                if (v instanceof Number || v instanceof Boolean) hasNested = true;
            }
            if (hasNested) return true;
        }
        if (connectionId != null) return true;
        if (folderFilter != null && !folderFilter.isBlank()) return true;
        if (senderInclude != null && !senderInclude.isEmpty()) return true;
        if (senderExclude != null && !senderExclude.isEmpty()) return true;
        if (subjectKeywords != null && !subjectKeywords.isEmpty()) return true;
        if (subjectExcludeKeywords != null && !subjectExcludeKeywords.isEmpty()) return true;
        if (bodyKeywords != null && !bodyKeywords.isEmpty()) return true;
        if (bodyExcludeKeywords != null && !bodyExcludeKeywords.isEmpty()) return true;
        if (allowedMimes != null && !allowedMimes.isEmpty()) return true;
        if (minAttachmentSizeBytes != null || maxAttachmentSizeBytes != null) return true;
        return false;
    }
}
