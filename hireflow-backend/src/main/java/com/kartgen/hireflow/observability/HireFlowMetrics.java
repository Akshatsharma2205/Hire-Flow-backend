package com.kartgen.hireflow.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Service for recording operational recruitment metrics.
 * Strictly guarantees zero PII in metric labels.
 */
@Component
@RequiredArgsConstructor
public class HireFlowMetrics {

    private final MeterRegistry registry;

    public void recordSyncItems(String provider, int count, boolean success) {
        registry.counter("hireflow.sync.items.count",
                "provider", sanitizeTag(provider),
                "status", success ? "SUCCESS" : "FAILED"
        ).increment(count);
    }

    public void recordSyncFailure(String provider, String errorType) {
        registry.counter("hireflow.sync.failure.count",
                "provider", sanitizeTag(provider),
                "error_type", sanitizeTag(errorType)
        ).increment();
    }

    public void recordSyncDuration(String provider, Duration duration, boolean success) {
        Timer.builder("hireflow.sync.duration")
                .tag("provider", sanitizeTag(provider))
                .tag("status", success ? "SUCCESS" : "FAILED")
                .register(registry)
                .record(duration);
    }

    public void recordJobDuration(String jobType, Duration duration, boolean success) {
        Timer.builder("hireflow.queue.job.duration")
                .tag("job_type", sanitizeTag(jobType))
                .tag("status", success ? "COMPLETED" : "FAILED")
                .register(registry)
                .record(duration);
    }

    public void recordDeadLetter(String jobType) {
        registry.counter("hireflow.queue.deadletter.count",
                "job_type", sanitizeTag(jobType)
        ).increment();
    }

    public void recordExtractionDuration(String mimeType, Duration duration, boolean accepted) {
        Timer.builder("hireflow.resume.extraction.duration")
                .tag("mime_type", sanitizeTag(mimeType))
                .tag("outcome", accepted ? "ACCEPTED" : "REJECTED")
                .register(registry)
                .record(duration);
    }

    public void recordGatekeeperRejection(String mimeType, String reasonCategory) {
        registry.counter("hireflow.resume.gatekeeper.rejected",
                "mime_type", sanitizeTag(mimeType),
                "reason_category", sanitizeTag(reasonCategory)
        ).increment();
    }

    private String sanitizeTag(String tag) {
        if (tag == null || tag.isBlank()) return "UNKNOWN";
        return tag.trim().toUpperCase().replaceAll("[^A-Z0-9_\\-.]", "_");
    }
}
