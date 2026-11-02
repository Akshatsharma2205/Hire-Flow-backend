package com.kartgen.hireflow.e2e;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Master Test Runner Harness for the HireFlow E2E Test Suite.
 *
 * Verifies system preconditions, tier discovery, Flyway migration state,
 * Actuator health, mock server network isolation, and suite integrity.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HireFlowE2EMasterRunnerTest extends HireFlowE2ETestSupport {

    @Test
    @Order(1)
    @DisplayName("Master Runner: Verify Test Environment & System Invariants")
    void testMasterRunner_EnvironmentAndSystemInvariants() throws Exception {
        // 1. Verify Flyway migrations applied
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
        assertThat(migrationCount).isNotNull();
        assertThat(migrationCount).isGreaterThanOrEqualTo(5);

        // 2. Verify Seed Data Present
        Integer orgCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class);
        assertThat(orgCount).isGreaterThanOrEqualTo(1);

        Integer jobCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class);
        assertThat(jobCount).isGreaterThanOrEqualTo(2);

        // 3. Verify Mock Server Network Isolation
        assertThat(mockServer).isNotNull();
        assertThat(mockPort).isGreaterThan(0);
        assertThat(mockBaseUrl).startsWith("http://localhost:");

        // 4. Verify Actuator Health
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // 5. Verify Authentication Flow
        String token = login();
        assertThat(token).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("Master Runner: Verify 4-Tier Test Suite Discoverability & Structure")
    void testMasterRunner_VerifyTierSuiteStructure() {
        List<Class<?>> expectedTierClasses = List.of(
                Tier1FeatureCoverageE2ETest.class,
                Tier2BoundaryCornerCaseE2ETest.class,
                Tier3CrossFeaturePairwiseE2ETest.class,
                Tier4RealWorldScenarioE2ETest.class
        );

        for (Class<?> tierClass : expectedTierClasses) {
            assertThat(tierClass).isNotNull();
            assertThat(tierClass.getMethods().length).isGreaterThan(0);
        }

        // Count test methods across the 4 tiers
        long tier1Count = countTestMethods(Tier1FeatureCoverageE2ETest.class);
        long tier2Count = countTestMethods(Tier2BoundaryCornerCaseE2ETest.class);
        long tier3Count = countTestMethods(Tier3CrossFeaturePairwiseE2ETest.class);
        long tier4Count = countTestMethods(Tier4RealWorldScenarioE2ETest.class);

        assertThat(tier1Count).isGreaterThanOrEqualTo(30);
        assertThat(tier2Count).isGreaterThanOrEqualTo(30);
        assertThat(tier3Count).isGreaterThanOrEqualTo(6);
        assertThat(tier4Count).isGreaterThanOrEqualTo(5);

        long totalTests = tier1Count + tier2Count + tier3Count + tier4Count;
        assertThat(totalTests).isGreaterThanOrEqualTo(71);
    }

    private long countTestMethods(Class<?> clazz) {
        return java.util.Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.junit.jupiter.api.Test.class))
                .count();
    }
}
