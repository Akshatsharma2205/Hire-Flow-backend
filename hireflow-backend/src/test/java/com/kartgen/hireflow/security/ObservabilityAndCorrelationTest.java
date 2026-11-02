package com.kartgen.hireflow.security;

import com.kartgen.hireflow.common.TenantContext;
import com.kartgen.hireflow.observability.CorrelationIdFilter;
import com.kartgen.hireflow.observability.HireFlowMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Observability, Correlation ID & Micrometer Metrics Tests")
class ObservabilityAndCorrelationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private HireFlowMetrics hireFlowMetrics;

    @Test
    @DisplayName("CorrelationIdFilter preserves and returns incoming X-Correlation-ID header")
    void testPreservesIncomingCorrelationId() throws Exception {
        String testCorrelationId = "test-corr-" + UUID.randomUUID();

        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-ID", testCorrelationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", testCorrelationId));
    }

    @Test
    @DisplayName("CorrelationIdFilter generates X-Correlation-ID header when omitted")
    void testGeneratesMissingCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn();

        String generatedId = result.getResponse().getHeader("X-Correlation-ID");
        assertThat(generatedId).isNotBlank();
        // MDC must be clean after request completion
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("TenantContext synchronizes organization ID with SLF4J MDC orgId")
    void testTenantContextSynchronizesMdcOrgId() {
        UUID orgId = UUID.randomUUID();
        TenantContext.setOrganizationId(orgId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_ORG_ID_KEY)).isEqualTo(orgId.toString());

        TenantContext.clear();
        assertThat(MDC.get(CorrelationIdFilter.MDC_ORG_ID_KEY)).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("Actuator /actuator/health returns UP")
    void testActuatorHealthUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Actuator /actuator/metrics exposes registered recruitment metrics without PII")
    void testActuatorMetricsExposure() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // Verify key hireflow metrics are present
        assertThat(body).contains("hireflow.sync.items.count");
        assertThat(body).contains("hireflow.sync.duration");
        assertThat(body).contains("hireflow.queue.job.duration");
        assertThat(body).contains("hireflow.queue.deadletter.count");
        assertThat(body).contains("hireflow.resume.extraction.duration");
        assertThat(body).contains("hireflow.resume.gatekeeper.rejected");

        // Zero candidate PII verification
        assertThat(body).doesNotContain("@example.com");
        assertThat(body).doesNotContain("@kartgen.com");
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("token");
    }

    @Test
    @DisplayName("HireFlowMetrics records events with low-cardinality sanitized enum tags")
    void testHireFlowMetricsRecording() {
        hireFlowMetrics.recordSyncItems("GMAIL", 42, true);
        hireFlowMetrics.recordSyncFailure("OUTLOOK", "AUTH_EXPIRED");
        hireFlowMetrics.recordSyncDuration("GMAIL", Duration.ofMillis(250), true);
        hireFlowMetrics.recordJobDuration("RESUME_INGESTION", Duration.ofMillis(120), true);
        hireFlowMetrics.recordDeadLetter("RESUME_INGESTION");
        hireFlowMetrics.recordExtractionDuration("application/pdf", Duration.ofMillis(80), true);
        hireFlowMetrics.recordGatekeeperRejection("application/octet-stream", "INVALID_MIME");

        // Verify meters recorded in registry
        assertThat(meterRegistry.find("hireflow.sync.items.count").counter()).isNotNull();
        assertThat(meterRegistry.find("hireflow.sync.failure.count").counter()).isNotNull();
        assertThat(meterRegistry.find("hireflow.sync.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("hireflow.queue.job.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("hireflow.queue.deadletter.count").counter()).isNotNull();
        assertThat(meterRegistry.find("hireflow.resume.extraction.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("hireflow.resume.gatekeeper.rejected").counter()).isNotNull();
    }
}
