package com.kartgen.hireflow.profile;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name = "skills")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Skill {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "canonical_name", nullable = false, unique = true, length = 150) private String canonicalName;
    @Column(length = 80) private String category;
    @Column(nullable = false) @Builder.Default private Boolean active = true;
}

