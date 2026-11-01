package com.kartgen.hireflow.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SkillAliasRepository extends JpaRepository<SkillAlias, UUID> {
    List<SkillAlias> findAll();
}

