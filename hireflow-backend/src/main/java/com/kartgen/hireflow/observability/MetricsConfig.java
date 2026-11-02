package com.kartgen.hireflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Micrometer metrics for mailbox synchronization, queues,
 * and resume extraction.
 *
 * Strict Zero-PII Policy: All metric tags use only low-cardinality, fixed enum values.
 * No candidate names, email addresses, phone numbers, or tokens are ever recorded in tags.
 */
@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry registry) {
        // Pre-register core meters so they appear in /actuator/metrics on boot
        Counter.builder("hireflow.sync.items.count")
                .description("Total email items fetched from mailbox providers")
                .tag("provider", "UNKNOWN")
                .tag("status", "SUCCESS")
                .register(registry);

        Counter.builder("hireflow.sync.failure.count")
                .description("Mailbox sync failure events")
                .tag("provider", "UNKNOWN")
                .tag("error_type", "NONE")
                .register(registry);

        Timer.builder("hireflow.sync.duration")
                .description("Duration of mailbox synchronization cycles")
                .tag("provider", "UNKNOWN")
                .tag("status", "SUCCESS")
                .register(registry);

        Timer.builder("hireflow.queue.job.duration")
                .description("Processing duration per background queue job")
                .tag("job_type", "RESUME_INGESTION")
                .tag("status", "COMPLETED")
                .register(registry);

        Counter.builder("hireflow.queue.deadletter.count")
                .description("Jobs moved to dead letter state after maximum retries")
                .tag("job_type", "RESUME_INGESTION")
                .register(registry);

        Timer.builder("hireflow.resume.extraction.duration")
                .description("Time taken by Tika extraction pipeline")
                .tag("mime_type", "application/pdf")
                .tag("outcome", "ACCEPTED")
                .register(registry);

        Counter.builder("hireflow.resume.gatekeeper.rejected")
                .description("Count of attachments rejected by resume gatekeeper")
                .tag("mime_type", "application/pdf")
                .tag("reason_category", "INVALID_MIME")
                .register(registry);
    }
}
