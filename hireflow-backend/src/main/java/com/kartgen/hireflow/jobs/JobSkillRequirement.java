package com.kartgen.hireflow.jobs;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "job_skill_requirements")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class JobSkillRequirement {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "job_version_id", nullable = false) private UUID jobVersionId;
    @Column(name = "skill_id", nullable = false) private UUID skillId;
    @Column(nullable = false, length = 24) private String priority;
    @Column(nullable = false, precision = 6, scale = 3) @Builder.Default private BigDecimal weight = BigDecimal.ONE;
}

