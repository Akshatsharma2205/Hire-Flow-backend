package com.kartgen.hireflow.identity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "candidates")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Candidate {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "organization_id", nullable = false) private UUID organizationId;
    @Column(name = "display_name", length = 250) private String displayName;
    @Column(name = "primary_email", length = 320) private String primaryEmail;
    @Column(name = "primary_phone", length = 50) private String primaryPhone;
    @Column(name = "linkedin_url", columnDefinition = "TEXT") private String linkedinUrl;
    @Column(name = "current_resume_version_id") private UUID currentResumeVersionId;
    @Column(name = "current_profile_version_id") private UUID currentProfileVersionId;
    @Column(name = "current_experience_months") private Integer currentExperienceMonths;
    @Column(name = "experience_confidence", length = 24) private String experienceConfidence;
    @Column(name = "current_city", length = 150) private String currentCity;
    @Column(name = "last_application_at") private Instant lastApplicationAt;
    @Column(name = "profile_updated_at") private Instant profileUpdatedAt;
    @Column(nullable = false, length = 24) @Builder.Default private String status = "ACTIVE";
    @Version private Long version;
}

