package com.kartgen.hireflow.mail.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAuthorizationAttemptRepository extends JpaRepository<OAuthAuthorizationAttempt, UUID> {

    @Query("SELECT a FROM OAuthAuthorizationAttempt a WHERE a.stateToken = :token OR a.state = :token")
    Optional<OAuthAuthorizationAttempt> findByStateToken(@Param("token") String token);

    Optional<OAuthAuthorizationAttempt> findByState(String state);

    /**
     * Atomically claims and consumes a PENDING attempt if not expired.
     * Returns 1 if successfully claimed by this execution, 0 otherwise (preventing replay).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OAuthAuthorizationAttempt a
        SET a.status = 'CONSUMED', a.consumedAt = :now
        WHERE (a.stateToken = :stateToken OR a.state = :stateToken)
          AND a.status = 'PENDING'
          AND a.expiresAt > :now
    """)
    int consumePendingAttempt(@Param("stateToken") String stateToken, @Param("now") Instant now);

    /**
     * Marks an unconsumed attempt as FAILED.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OAuthAuthorizationAttempt a
        SET a.status = 'FAILED', a.consumedAt = :now
        WHERE (a.stateToken = :stateToken OR a.state = :stateToken)
          AND a.status = 'PENDING'
    """)
    int markFailedAttempt(@Param("stateToken") String stateToken, @Param("now") Instant now);

    /**
     * Marks all unconsumed attempts that have passed their TTL as EXPIRED.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OAuthAuthorizationAttempt a
        SET a.status = 'EXPIRED'
        WHERE a.status = 'PENDING'
          AND a.expiresAt <= :now
    """)
    int markExpiredAttempts(@Param("now") Instant now);
}
