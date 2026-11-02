package com.kartgen.hireflow.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailRuleRepository extends JpaRepository<EmailRule, UUID> {

    List<EmailRule> findByOrganizationIdOrderByPriorityAsc(UUID organizationId);

    List<EmailRule> findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(UUID organizationId);

    Optional<EmailRule> findByIdAndOrganizationId(UUID id, UUID organizationId);

    void deleteByIdAndOrganizationId(UUID id, UUID organizationId);
}
