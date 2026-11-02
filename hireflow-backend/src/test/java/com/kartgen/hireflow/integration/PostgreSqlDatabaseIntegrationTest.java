package com.kartgen.hireflow.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL Database Integration Test.
 * Verifies Flyway migrations (V1..V5), multi-tenant isolation, check constraints,
 * and ACID transaction rollback against a live PostgreSQL 16 instance.
 *
 * In CI, executed against the postgres:16-alpine service container.
 * In local development, executes if PostgreSQL is running on DB_HOST:DB_PORT (default localhost:5432),
 * or skips cleanly if the service container is not present.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:hireflow_test}",
    "spring.datasource.username=${DB_USERNAME:hireflow_user}",
    "spring.datasource.password=${DB_PASSWORD:hireflow_ci_password}",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.flyway.clean-disabled=true"
})
@ActiveProfiles("test")
@ExtendWith(PostgreSqlDatabaseIntegrationTest.PostgreSqlAvailableCondition.class)
@DisplayName("PostgreSQL 16 Database Reliability & Multi-Tenancy Integration Test")
public class PostgreSqlDatabaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Verify Flyway migrations V1 through V5 successfully applied to PostgreSQL")
    void testFlywayMigrationsApplied() {
        List<String> appliedMigrations = jdbcTemplate.query(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                (rs, rowNum) -> rs.getString("version")
        );

        assertThat(appliedMigrations)
                .as("Flyway must record successful execution of core migrations V1 through V6")
                .contains("1", "2", "3", "4", "5", "6");
    }

    @Test
    @DisplayName("Verify PostgreSQL multi-tenant isolation partitions data by organization_id")
    void testMultiTenantIsolation() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, slug, status, created_at) VALUES (?, ?, ?, 'ACTIVE', NOW())",
                orgA, "Acme Corp " + orgA, "acme-" + orgA
        );
        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, slug, status, created_at) VALUES (?, ?, ?, 'ACTIVE', NOW())",
                orgB, "Beta LLC " + orgB, "beta-" + orgB
        );

        UUID candA = UUID.randomUUID();
        UUID candB = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO candidates (id, organization_id, display_name, primary_email, status) " +
                        "VALUES (?, ?, 'Alice Smith', 'alice@acme.com', 'ACTIVE')",
                candA, orgA
        );
        jdbcTemplate.update(
                "INSERT INTO candidates (id, organization_id, display_name, primary_email, status) " +
                        "VALUES (?, ?, 'Bob Jones', 'bob@beta.com', 'ACTIVE')",
                candB, orgB
        );

        List<String> orgACandidates = jdbcTemplate.query(
                "SELECT display_name FROM candidates WHERE organization_id = ?",
                (rs, rowNum) -> rs.getString("display_name"),
                orgA
        );

        List<String> orgBCandidates = jdbcTemplate.query(
                "SELECT display_name FROM candidates WHERE organization_id = ?",
                (rs, rowNum) -> rs.getString("display_name"),
                orgB
        );

        assertThat(orgACandidates).containsExactly("Alice Smith");
        assertThat(orgACandidates).doesNotContain("Bob Jones");

        assertThat(orgBCandidates).containsExactly("Bob Jones");
        assertThat(orgBCandidates).doesNotContain("Alice Smith");
    }

    @Test
    @DisplayName("Verify PostgreSQL CHECK constraint enforcement on invalid status values")
    void testCheckConstraintEnforcement() {
        UUID orgId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, slug, status, created_at) VALUES (?, ?, ?, 'ACTIVE', NOW())",
                orgId, "Constraint Org " + orgId, "const-" + orgId
        );

        // Webhook credentials table enforces ck_webhook_credentials_status (ACTIVE, REVOKED)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO webhook_credentials (id, organization_id, key_id, encrypted_secret, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'INVALID_STATUS', NOW())",
                UUID.randomUUID(), orgId, "whk_" + UUID.randomUUID(), "enc_secret_abc123"
        )).satisfies(e -> {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            assertThat(msg).containsAnyOf("check constraint", "violates check", "ck_webhook_credentials_status");
        });
    }

    @Test
    @DisplayName("Verify PostgreSQL transaction rollback behavior upon unhandled failure")
    void testTransactionRollback() {
        UUID orgId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, slug, status, created_at) VALUES (?, ?, ?, 'ACTIVE', NOW())",
                orgId, "Tx Org " + orgId, "tx-" + orgId
        );

        UUID candidateId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO candidates (id, organization_id, display_name, primary_email, status) " +
                            "VALUES (?, ?, 'Rollback Candidate', 'rb@test.com', 'ACTIVE')",
                    candidateId, orgId
            );
            throw new IllegalStateException("Simulated processing failure to test transaction rollback");
        })).isInstanceOf(IllegalStateException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM candidates WHERE id = ?",
                Integer.class,
                candidateId
        );

        assertThat(count)
                .as("Transaction rollback must guarantee zero residual records in PostgreSQL")
                .isEqualTo(0);
    }

    /**
     * Skips test execution if PostgreSQL service is not reachable on host:port.
     */
    public static class PostgreSqlAvailableCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String host = System.getenv().getOrDefault("DB_HOST", "localhost");
            int port = 5432;
            try {
                port = Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5432"));
            } catch (NumberFormatException ignored) {}

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 800);
                return ConditionEvaluationResult.enabled("PostgreSQL is reachable at " + host + ":" + port);
            } catch (Exception e) {
                return ConditionEvaluationResult.disabled(
                        "PostgreSQL is not reachable at " + host + ":" + port + " (" + e.getMessage() + "). Skipping PostgreSQL integration tests."
                );
            }
        }
    }
}
