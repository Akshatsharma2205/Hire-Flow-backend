package com.kartgen.hireflow.profile;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name = "skill_aliases")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillAlias {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "skill_id", nullable = false) private UUID skillId;
    @Column(name = "alias_normalized", nullable = false, unique = true, length = 150) private String aliasNormalized;
}

