package com.kartgen.hireflow.rules;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RulePreviewRequest {
    private UUID connectionId;

    @JsonAlias({"folder", "folderFilter"})
    private String folderName;

    @JsonAlias({"senderEmail", "from"})
    private String sender;

    private String subject;

    @JsonAlias({"bodyText", "content"})
    private String body;

    private List<Object> attachments;

    public String getEffectiveSender() {
        return sender != null ? sender.trim() : null;
    }

    public String getEffectiveSubject() {
        return subject != null ? subject.trim() : "";
    }

    public String getEffectiveBody() {
        return body != null ? body : "";
    }
}
