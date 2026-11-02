package com.kartgen.hireflow.rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailRuleDto {
    private UUID id;
    private UUID organizationId;
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

    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
