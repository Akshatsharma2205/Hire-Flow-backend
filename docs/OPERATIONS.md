# HireFlow AI — Operations & Observability Guide

This guide covers operational maintenance, observability endpoints, background job queue management, backup/restore procedures, and troubleshooting runbooks for HireFlow AI.

---

## 1. Observability & Health Probes

### 1.1 Kubernetes & Load Balancer Health Probes
HireFlow exposes Spring Boot Actuator health endpoints:
- **Liveness Probe**: `GET /actuator/health/liveness` (returns `{"status":"UP"}` when JVM and servlet container are running)
- **Readiness Probe**: `GET /actuator/health/readiness` (returns `{"status":"UP"}` when database connection pool and Flyway validation have completed successfully)
- **Aggregated Health**: `GET /actuator/health`

### 1.2 Operational KPI Endpoint
The platform provides a dedicated, authenticated operational KPI endpoint for dashboard health bars:
- **Endpoint**: `GET /api/v1/operations/health`
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`)
- **Response**:
  ```json
  {
    "queueDepth": 0,
    "runningCount": 0,
    "processedCount": 12,
    "failedCount": 0,
    "totalResumeFiles": 12,
    "totalCandidates": 10,
    "activeMailConnections": 1,
    "aiTokenSpend": 0
  }
  ```

---

## 2. Background Processing Queue Management

HireFlow uses an asynchronous database-backed worker queue (`processing_jobs`) executed via `ProcessingWorker` on Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

### 2.1 Job Lifecycle States
1. `PENDING`: Newly registered attachment awaiting heuristic extraction and identity resolution, or a retrying job waiting until `next_attempt_at`.
2. `RUNNING`: Claimed by a worker with conditional optimistic lock update (`status = 'RUNNING', started_at = NOW()`).
3. `COMPLETED`: Extraction, living profile versioning, and match scoring completed successfully (`status = 'COMPLETED', completed_at = NOW()`).
4. `FAILED`: Terminal failure after reaching maximum attempts (`max_attempts = 3`). Automatically generates an `IdentityReviewCase` with `reason_code = 'PROCESSING_FAILED'` for recruiter review.
- **Retry Backoff**: For non-terminal failures (`attempt < max_attempts`), exponential backoff is computed as `Math.min(3600L, (long) Math.pow(4, attempt))` seconds (4s, 16s, 64s, 256s, ... capped at 1 hour) and status reverts to `PENDING` with updated `next_attempt_at`.

### 2.2 Operational Queries for Queue Health
```sql
-- Count pending jobs by status
SELECT status, COUNT(*) FROM processing_jobs GROUP BY status;

-- Find oldest pending job (lag indicator)
SELECT id, organization_id, created_at, attempt_count, failure_reason
FROM processing_jobs
WHERE status IN ('PENDING', 'RUNNING')
ORDER BY created_at ASC
LIMIT 10;

-- Identify failed jobs requiring recruiter / operator attention
SELECT id, organization_id, reference_id, attempt_count, failure_reason, completed_at
FROM processing_jobs
WHERE status = 'FAILED'
ORDER BY completed_at DESC;
```

---

## 3. Correlation Tracking & Logging Standards

- **Tenant Correlation**: Every HTTP request is tagged with the authenticated `organization_id` in the Logback MDC (Mapped Diagnostic Context).
- **Webhook Ingestion Tracking**: Webhook requests log key ID and idempotency key without logging sensitive payload bodies or authorization tokens.
- **Privacy Gating (Zero PII in Logs)**:
  - Never log raw resume text, candidate phone numbers, or email bodies in INFO/WARN logs.
  - Log only entity UUIDs and SHA-256 hashes for debugging.

---

## 4. Backup & Disaster Recovery Procedures

### 4.1 PostgreSQL Database Backup
Automate daily logical dumps using `pg_dump`:
```bash
pg_dump -U hireflow_app -d hireflow -Fc -f "/backups/hireflow_$(date +%Y%m%d_%H%M%S).dump"
```

### 4.2 Storage Synchronization (StoragePort)
For `LOCAL` storage:
- Perform snapshot backups of `${LOCAL_STORAGE_BASE_PATH}` synchronized with database backups.
For `S3` storage:
- Enable S3 Versioning and Cross-Region Replication on the target bucket.

### 4.3 Database Restoration
```bash
pg_restore -U hireflow_app -d hireflow --clean "/backups/hireflow_YYYYMMDD_HHMMSS.dump"
```
Flyway automatically reconciles migration state against `flyway_schema_history` upon restart.

---

## 5. Operational Incident Runbooks

### 5.1 Stuck Processing Worker
**Symptom**: Jobs remain in `RUNNING` status without completion.
**Resolution**:
1. Check if application instances crashed while holding active jobs:
   ```sql
   UPDATE processing_jobs
   SET status = 'PENDING', attempt_count = attempt_count + 1, started_at = NULL
   WHERE status = 'RUNNING' AND started_at < NOW() - INTERVAL '15 minutes';
   ```
2. Restart worker instances to re-trigger polling.

### 5.2 Secret Rotation Runbook
1. **JWT Secret Rotation**: Update `JWT_SECRET` environment variable and roll out application containers. Active JWTs will expire gracefully based on standard TTL (8 hours).
2. **Webhook Secret Provisioning**: Provision new `whk_...` credentials via `POST /api/v1/webhook-credentials` before deactivating the older credential in the dashboard.
