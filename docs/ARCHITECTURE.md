# HireFlow AI — Architecture & Technical Blueprint

## 1. System Topology & Overview

HireFlow AI is an enterprise-grade applicant tracking and intelligent candidate ingestion platform engineered as a clean **Modular Monolith** in **Java 21** using **Spring Boot 3.3.5**. It processes inbound resumes from multiple channels (webmail Chrome extension, direct candidate upload, programmatic email forwarding webhooks), extracts structured profile data using Apache Tika/PDFBox heuristics, resolves candidate identities with deduplication safeguards, and scores candidate capabilities against active job requisitions.

```
                           +-----------------------------------------------+
                           |          Ingestion Channels                   |
                           +-----------------------------------------------+
                             |                     |                     |
                   [Browser Extension]    [Direct SPA Upload]   [Mail Inbound Webhook]
                     (Bearer Token)          (JWT Bearer)         (HMAC-SHA256 v2)
                             |                     |                     |
                             v                     v                     v
                           +-----------------------------------------------+
                           |           Spring Security Filter Chain        |
                           |  - JwtAuthenticationFilter                    |
                           |  - ExtensionTokenAuthFilter                   |
                           |  - WebhookAuthFilter                          |
                           +-----------------------------------------------+
                                                   |
                                                   v
                           +-----------------------------------------------+
                           |          Core Domain Services                 |
                           |  - MessageIngestionService                    |
                           |  - CandidateIdentityService                   |
                           |  - LivingProfileService                       |
                           |  - MatchService                               |
                           |  - ApplicationService                         |
                           +-----------------------------------------------+
                             |                     |                     |
                             v                     v                     v
                    +----------------+    +----------------+    +----------------+
                    |  StoragePort   |    |  Spring Data   |    |  Asynchronous  |
                    | (Local Disk /  |    |  Repositories  |    |  Worker Queue  |
                    |   S3 Adapter)  |    |  (JPA / H2/PG) |    | (Virtual Th.)  |
                    +----------------+    +----------------+    +----------------+
```

---

## 2. Core Modules & Packaging Structure

The backend application (`hireflow-backend/src/main/java/com/kartgen/hireflow`) is partitioned by domain bounded contexts:

### 2.1 `tenant`
- Manages multi-tenant boundaries (`Organization`, `User`, `OrganizationUser`).
- Enforces strict tenant separation across all database operations. Every domain entity is partitioned by `organization_id`.
- Tenant context is resolved per-request in the security filters (JwtAuthenticationFilter, ExtensionTokenAuthFilter, WebhookAuthFilter) and bound to the thread via TenantContext (ThreadLocal<UUID>), cleared deterministically in filter finally blocks.

### 2.2 `security`
- Stateless authentication and authorization via signed JSON Web Tokens (HMAC-SHA256) and Bearer extension tokens (`hf_ext_...`).
- Role-Based Access Control (RBAC) supporting `OWNER`, `ADMIN`, `RECRUITER`, and `VIEWER`.
- `ProductionDemoCredentialSanitizer` automatically neutralizes demo seed passwords and disables demo API tokens when running under `prod` profile.
- Strict startup guards (`StartupProfileGuard`) verifying secret entropy and rejecting placeholder keys in production.

### 2.3 `mail` & `mail.channel`
- Ingestion endpoints:
  - `ForwardingWebhookController` (`/api/v1/mail-webhook/inbound`): Receives inbound email forwarding payloads with signature validation and deduplication.
  - `DirectUploadController` (`/api/v1/uploads/direct`): Allows recruiters to upload candidate resumes directly.
  - `ExtensionIntakeController` (`/api/v1/uploads/extension-intake`): Handles candidate captures streamed from the Chrome extension with SHA-256 pre-flight hash verification (`/api/v1/uploads/check-hashes`).
- `MessageIngestionService`: Orchestrates atomic persistence of raw messages, extraction of attachments, SHA-256 checksum computation, file storage synchronization, and asynchronous job queuing.

### 2.4 `document`
- Storage abstraction via `StoragePort` interface decoupling the application from the underlying storage mechanism.
- `LocalDiskStorageAdapter`: File system implementation utilizing content-addressable storage paths partitioned by tenant ID, SHA-256 subdirectories, and random UUID keys:
  `${local-base-path}/{orgId}/{sha256[0..63]}/{uuid}.{ext}`
- Signed temporary download URLs (`LocalS3Controller` at `/api/v1/local-s3/{token}`) validated with HMAC-SHA256 tokens and time-to-live expirations.

### 2.5 `extraction`
- `LocalSmartHeuristicExtractor`: Deterministic, privacy-preserving resume parsing engine powered by Apache Tika and Apache PDFBox.
- Extracts candidate name, email, phone number, work experience history, education records, and skills from PDF, DOCX, and TXT files without requiring external cloud AI calls.
- Skill canonicalization utilizing a 55-keyword alias normalization dictionary.

### 2.6 `identity`
- `CandidateIdentityService`: Prevents duplicate records across ingestion channels.
- Employs deterministic matching rules:
  1. Exact normalized email address match.
  2. Exact normalized phone number match.
  3. Linked identity handles (LinkedIn, GitHub).
- Discrepancies or ambiguous identities automatically generate an `IdentityReviewCase` requiring human recruiter adjudication (`/api/v1/review-cases`).

### 2.7 `profile`
- `LivingProfileService`: Maintains versioned snapshots of candidate profiles (`CandidateProfileVersion`, `ResumeVersion`).
- Preserves complete document provenance and skill proficiency tags across multiple resume submissions.

### 2.8 `jobs`
- `JobService` and `JobController`: Comprehensive job requisition management with version history (`JobVersion`) and required/optional skill definitions (`JobSkillRequirement`).

### 2.9 `matching`
- `MatchService`: High-performance deterministic scoring engine computing candidate-to-job fit.
- Scores candidates from 0 to 100 based on weighted skill overlap, requirement tiers (mandatory vs bonus), and experience level alignment.

### 2.10 `hiring`
- `ApplicationService` & `ApplicationController`: Manages candidate pipelines across standard stages (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`).
- Records stage transition audit logs (`ApplicationStageHistory`) and recruiter feedback (`CandidateNote`).

### 2.11 `webhook`
- Comprehensive HMAC-SHA256 v2 webhook security subsystem.
- Constant-time signature verification (`MessageDigest.isEqual`), replay protection via timestamp drift window (default 300s) and delivery idempotency ledger (`WebhookDelivery`).

### 2.12 `ops`
- Background asynchronous processing with `WorkerScheduler` and `ProcessingWorker`.
- Atomic job claiming with conditional SQL updates, exponential backoff retries, and dead-letter queueing for poisoned messages.
- Audit event ledger (`AuditEvent`) and transactional outbox (`OutboxEvent`).

---

## 3. Storage & Transaction Safety (J-2B Architecture)

A critical architectural guarantee of HireFlow is **Storage-to-Database Atomicity**:
1. During file ingestion, files are persisted to physical storage prior to database transaction commitment.
2. In the event of a database transaction rollback, an active transaction synchronization hook (`TransactionSynchronization.afterCompletion`) triggers automated cleanup of orphaned binary objects.
3. If an existing identical file (matching `organization_id` + `sha256`) already exists in the system, HireFlow reuses the existing storage object and skips redundant disk writes.
4. If a uniqueness race occurs under concurrent identical uploads, the loser transaction catches the database conflict, links to the winning entity, and disposes of the superfluous binary object without data corruption.

---

## 4. Frontend Single Page Application (SPA)

HireFlow features an integrated, responsive Single Page Application embedded directly inside `hireflow-backend/src/main/resources/static/index.html`.
- Styled with modern Tailwind CSS utilities and clean typography.
- Communicates with all backend REST endpoints via standard JSON fetch calls.
- Provides real-time dashboard analytics, candidate profile views, job requisition editors, application stage kanban boards, and interactive resume PDF previewers.
