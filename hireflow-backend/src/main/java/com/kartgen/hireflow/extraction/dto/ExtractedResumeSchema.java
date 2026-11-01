package com.kartgen.hireflow.extraction.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ExtractedResumeSchema {
    private String fullName;
    private List<String> emails;
    private List<String> phones;
    private String linkedinUrl;
    private String githubUrl;
    private List<String> extractedSkills;
    private Integer totalExperienceMonths;
    private String experienceBasis;
    private String currentCity;
    private List<EducationEntry> educationList;
    private String documentDate;
    private Map<String, String> sourceEvidenceJson;

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class EducationEntry {
        private String degree;
        private String institution;
        private String year;
    }
}

