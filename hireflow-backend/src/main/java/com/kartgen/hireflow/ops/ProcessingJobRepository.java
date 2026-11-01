package com.kartgen.hireflow.ops;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    @Query("SELECT p FROM ProcessingJob p WHERE p.status = 'PENDING' AND p.nextAttemptAt <= :now " +
           "ORDER BY p.createdAt ASC")
    List<ProcessingJob> findPendingJobs(@Param("now") Instant now, Pageable pageable);

    /**
     * Atomically claim a pending job. Returns 1 when this worker owns the job and
     * 0 when another worker claimed it first. Works on H2 and PostgreSQL without
     * relying on dialect-specific SKIP LOCKED syntax.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessingJob p SET p.status = 'RUNNING', p.startedAt = :now, " +
           "p.attemptCount = p.attemptCount + 1 " +
           "WHERE p.id = :id AND p.status = 'PENDING'")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Returns jobs stuck in RUNNING (for example after an instance crash) to the
     * PENDING queue so another worker can retry them.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessingJob p SET p.status = 'PENDING', p.nextAttemptAt = :now " +
           "WHERE p.status = 'RUNNING' AND p.startedAt < :staleBefore AND p.attemptCount < p.maxAttempts")
    int recoverStaleRunningJobs(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProcessingJob p SET p.status = 'FAILED', p.completedAt = :now, " +
           "p.failureReason = 'Stale job exceeded maximum retry attempts' " +
           "WHERE p.status = 'RUNNING' AND p.startedAt < :staleBefore AND p.attemptCount >= p.maxAttempts")
    int deadLetterExhaustedStaleJobs(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    long countByOrganizationIdAndStatus(UUID orgId, String status);

    long countByStatus(String status);

    List<ProcessingJob> findByOrganizationIdAndStatus(UUID orgId, String status);
}
