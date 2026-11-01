package com.kartgen.hireflow.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Polls processing_jobs and hands claimed jobs to the worker. Claiming uses an
 * atomic conditional update so several application instances can run safely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerScheduler {

    private static final int BATCH_SIZE = 10;
    private static final long STALE_AFTER_MINUTES = 10;

    private final ProcessingJobRepository jobRepo;
    private final ProcessingWorker worker;

    @Scheduled(fixedDelayString = "${hireflow.worker.poll-ms:2000}")
    public void poll() {
        Instant now = Instant.now();
        try {
            Instant staleBefore = now.minus(STALE_AFTER_MINUTES, ChronoUnit.MINUTES);
            int deadLettered = jobRepo.deadLetterExhaustedStaleJobs(staleBefore, now);
            if (deadLettered > 0) {
                log.warn("Dead-lettered {} exhausted stale processing jobs", deadLettered);
            }
            int recovered = jobRepo.recoverStaleRunningJobs(staleBefore, now);
            if (recovered > 0) {
                log.warn("Recovered {} stale RUNNING processing jobs", recovered);
            }
        } catch (Exception e) {
            log.warn("Stale job recovery failed: {}", e.getMessage());
        }

        List<ProcessingJob> pending;
        try {
            pending = jobRepo.findPendingJobs(now, PageRequest.of(0, BATCH_SIZE));
        } catch (Exception e) {
            log.error("Failed to poll processing jobs: {}", e.getMessage(), e);
            return;
        }

        for (ProcessingJob job : pending) {
            try {
                if (jobRepo.claim(job.getId(), Instant.now()) == 1) {
                    worker.execute(job.getId());
                }
            } catch (Exception e) {
                log.error("Unexpected error while running job {}: {}", job.getId(), e.getMessage(), e);
            }
        }
    }
}
