# HireFlow AI — Comprehensive Production Java Class Reference

This document provides an exhaustive, authoritative technical specification of every production Java class in `hireflow-backend/src/main/java/com/kartgen/hireflow`, based on the actual source code.

---

## Table of Contents
1. [Application Entrypoint](#1-application-entrypoint)
2. [Common & Utilities (`common`)](#2-common--utilities-common)
3. [Configuration (`config`)](#3-configuration-config)
4. [Document & Storage (`document`)](#4-document--storage-document)
5. [Resume Extraction (`extraction`)](#5-resume-extraction-extraction)
6. [Hiring & Pipeline (`hiring`)](#6-hiring--pipeline-hiring)
7. [Candidate Identity & Deduplication (`identity`)](#7-candidate-identity--deduplication-identity)
8. [Job Requisitions & Parsing (`jobs`)](#8-job-requisitions--parsing-jobs)
9. [Inbound Channels & Sync (`mail.channel`)](#9-inbound-channels--sync-mailchannel)
10. [Inbound Models & Persistence (`mail.model`)](#10-inbound-models--persistence-mailmodel)
11. [Inbound Ingestion Service (`mail.service`)](#11-inbound-ingestion-service-mailservice)
12. [Candidate Matching & Scoring (`matching`)](#12-candidate-matching--scoring-matching)
13. [Operations & Background Processing (`ops`)](#13-operations--background-processing-ops)
14. [Candidate Profile & Taxonomy (`profile`)](#14-candidate-profile--taxonomy-profile)
15. [Security & Authentication (`security`)](#15-security--authentication-security)
16. [Tenant & Membership (`tenant`)](#16-tenant--membership-tenant)
17. [Webhook Security & Ingestion (`webhook`)](#17-webhook-security--ingestion-webhook)

---

## 1. Application Entrypoint

### `HireFlowApplication`
- **Package**: `com.kartgen.hireflow`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/HireFlowApplication.java`
- **Responsibility**: Boots the Spring Boot application, enables scheduling (`@EnableScheduling`), asynchronous execution (`@EnableAsync`), and transaction management (`@EnableTransactionManagement`).
- **Why It Exists**: Central application entrypoint bootstrapping embedded servlet containers and Spring context.
- **Dependencies**: `SpringApplication`.
- **Public Methods**:
  - `void main(String[] args)`: Starts the Spring application context.
- **Exceptions**: `Exception` on failed context boot.
- **Transactions**: Configures global transaction boundaries.
- **Security**: None directly; delegates to `SecurityConfig`.
- **Related Classes**: `SecurityConfig`, `StartupProfileGuard`.

---

## 2. Common & Utilities (`common`)

### `ApiError`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/ApiError.java`
- **Responsibility**: Standardized REST error response schema.
- **Fields**: `int status`, `String error`, `String message`, `String path`, `Instant timestamp`, `Map<String, String> validationErrors`.
- **Why It Exists**: Guarantees consistent error formats across API boundaries.

### `CryptoUtil`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/CryptoUtil.java`
- **Responsibility**: AES-256-GCM authenticated encryption and decryption for sensitive mailbox credentials and secrets.
- **Public Methods**:
  - `String encrypt(String plainText, String secretKey)`: Encrypts text using AES/GCM/NoPadding with a random 12-byte IV, returning base64 `iv:cipherText`.
  - `String decrypt(String cipherTextWithIv, String secretKey)`: Decrypts AES/GCM cipherText, validating integrity tag.
- **Security**: 128-bit authentication tag, constant-time checks.

### `GlobalExceptionHandler`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/GlobalExceptionHandler.java`
- **Responsibility**: `@RestControllerAdvice` translating exceptions into `ApiError` payloads.
- **Handled Exceptions**:
  - `MethodArgumentNotValidException` -> HTTP 400 Bad Request with field validation errors.
  - `NoSuchElementException` -> HTTP 404 Not Found.
  - `IllegalArgumentException`, `IllegalStateException` -> HTTP 400 Bad Request.
  - `AccessDeniedException` -> HTTP 403 Forbidden.
  - `AuthenticationException` -> HTTP 401 Unauthorized.
  - `Exception` -> HTTP 500 Internal Server Error (without leaking stack traces).

### `HashUtil`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/HashUtil.java`
- **Responsibility**: Constant-time cryptographic hashing and HMAC-SHA256 calculation.
- **Public Methods**:
  - `String sha256Hex(byte[] data)`: Computes hex SHA-256 digest.
  - `String hmacSha256(String data, String secret)`: Computes hex HMAC-SHA256 signature.
  - `boolean constantTimeEquals(String a, String b)`: Compares signatures using `MessageDigest.isEqual` to prevent timing attacks.

### `PageResponse<T>`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/PageResponse.java`
- **Responsibility**: Normalized pagination wrapper for Spring Data `Page<T>` responses (`content`, `page`, `size`, `totalElements`, `totalPages`, `last`).

### `TenantContext`
- **Package**: `com.kartgen.hireflow.common`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/common/TenantContext.java`
- **Responsibility**: `ThreadLocal` storage for current request tenant (`organizationId`) and authenticated user (`userId`).
- **Public Methods**:
  - `void set(UUID orgId, UUID userId)`: Sets context for current thread.
  - `UUID getOrganizationId()`, `UUID getUserId()`: Retrieves context.
  - `void clear()`: Clears thread-local state upon request completion.

---

## 3. Configuration (`config`)

### `AsyncConfig`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/AsyncConfig.java`
- **Responsibility**: Configures task execution using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

### `CorsConfig`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/CorsConfig.java`
- **Responsibility**: Registers `CorsFilter` allowing secure cross-origin requests from configured frontend origins and extension surfaces.

### `HireFlowProperties`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/HireFlowProperties.java`
- **Responsibility**: `@ConfigurationProperties(prefix = "hireflow")` binding storage paths, HMAC secrets, worker polling intervals, and encryption keys.

### `JacksonConfig`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/JacksonConfig.java`
- **Responsibility**: Configures `ObjectMapper` with JavaTimeModule, ISO-8601 timestamps, and deserialization fail-on-unknown disabled.

### `ProductionSafetyConfig`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/ProductionSafetyConfig.java`
- **Responsibility**: Validates that production deployments do not enable H2 web consoles, demo credentials, or unsafe debug profiles.

### `StartupProfileGuard`
- **Package**: `com.kartgen.hireflow.config`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/config/StartupProfileGuard.java`
- **Responsibility**: Fail-fast startup validator. Enforces PostgreSQL datasource in `prod` and rejects weak default HMAC/JWT secrets.

---

## 4. Document & Storage (`document`)

### `StoragePort`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/StoragePort.java`
- **Responsibility**: Abstract SPI port for binary document storage.
- **Methods**:
  - `String store(UUID orgId, String sha256, String mimeType, byte[] data)`: Stores binary object, returns storage key.
  - `byte[] retrieve(String objectKey)`: Fetches binary content.
  - `void delete(String objectKey)`: Deletes stored object.
  - `String generateViewUrl(String objectKey, String fileName)`: Generates signed temporary view URL.

### `LocalDiskStorageAdapter`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/LocalDiskStorageAdapter.java`
- **Responsibility**: Production-grade local filesystem implementation of `StoragePort`. Partitions files by tenant and SHA-256 directories, uses atomic rename from staging files, and generates signed HMAC view URLs.

### `LocalS3Controller`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/LocalS3Controller.java`
- **Responsibility**: REST endpoint `GET /api/v1/local-s3/{token}` verifying HMAC tokens and streaming binary PDF/DOCX bytes.
- **Security**: Rejects forged tokens with HTTP 403 Forbidden, expired tokens with HTTP 410 Gone.

### `PdfBoxTextExtractor`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/PdfBoxTextExtractor.java`
- **Responsibility**: Extracts raw text from PDF streams using Apache PDFBox `PDFTextStripper`.

### `ResumeFile`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/ResumeFile.java`
- **Responsibility**: JPA entity for `resume_files` table (`sha256`, `s3_object_key`, `size_bytes`, `mime_type`, `malware_status`).

### `ResumeFileRepository`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/ResumeFileRepository.java`
- **Responsibility**: Spring Data JPA repository for resume file queries and batch hash existence checks (`findExistingHashes`).

### `ResumeGatekeeperService`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/ResumeGatekeeperService.java`
- **Responsibility**: Validates file magic bytes (`%PDF-`, `PK`) and screens out non-resume invoice/tax documents.

### `S3StorageAdapter`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/S3StorageAdapter.java`
- **Responsibility**: AWS S3 cloud adapter implementing `StoragePort` using AWS SDK v2 client and pre-signed S3 URLs.

### `TikaMimeDetector`
- **Package**: `com.kartgen.hireflow.document`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/document/TikaMimeDetector.java`
- **Responsibility**: Inspects byte streams using Apache Tika to detect true MIME type regardless of file extension.

---

## 5. Resume Extraction (`extraction`)

### `LocalSmartHeuristicExtractor`
- **Package**: `com.kartgen.hireflow.extraction`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/extraction/LocalSmartHeuristicExtractor.java`
- **Responsibility**: Deterministic extraction engine identifying candidate names, emails, phones, skills (using 55 aliases), experience duration, and education.

### `ExternalAiRestExtractor`
- **Package**: `com.kartgen.hireflow.extraction`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/extraction/ExternalAiRestExtractor.java`
- **Responsibility**: Fallback LLM extraction client communicating with OpenAI/Gemini REST APIs for complex resume parsing.

### `ResumeExtractionPort`
- **Package**: `com.kartgen.hireflow.extraction`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/extraction/ResumeExtractionPort.java`
- **Responsibility**: SPI port defining `ExtractedResumeSchema extract(byte[] fileBytes, String mimeType)`.

### `ExtractedResumeSchema`
- **Package**: `com.kartgen.hireflow.extraction.dto`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/extraction/dto/ExtractedResumeSchema.java`
- **Responsibility**: DTO schema holding parsed candidate attributes, skills list, experience months, and education.

---

## 6. Hiring & Pipeline (`hiring`)

### `Application`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/Application.java`
- **Responsibility**: JPA entity for `applications` table tracking pipeline stages (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`).

### `ApplicationController`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/ApplicationController.java`
- **Responsibility**: REST controller handling application creation, stage updates (`PATCH /api/v1/applications/{id}/stage`), and audit history (`GET /api/v1/applications/{id}/history`).

### `ApplicationService`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/ApplicationService.java`
- **Responsibility**: Enforces stage state machine transitions, records `ApplicationStageHistory` audit entries, and manages re-application attempts.

### `ApplicationStageHistory` & `Repository`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/ApplicationStageHistory.java`
- **Responsibility**: Immutable audit log of every stage movement (`fromStage`, `toStage`, `changedBy`, `changedAt`, `note`).

### `ApplicationSubmission` & `Repository`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/ApplicationSubmission.java`
- **Responsibility**: Links applications to specific `resume_versions` submitted.

### `CandidateNote`
- **Package**: `com.kartgen.hireflow.hiring`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/hiring/CandidateNote.java`
- **Responsibility**: JPA entity for recruiter evaluation notes.

---

## 7. Candidate Identity & Deduplication (`identity`)

### `Candidate`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/Candidate.java`
- **Responsibility**: JPA entity representing a candidate (`displayName`, `primaryEmail`, `primaryPhone`, `currentCity`, `currentResumeVersionId`, `currentProfileVersionId`).

### `CandidateController`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/CandidateController.java`
- **Responsibility**: REST endpoints for candidate listing (`GET /api/v1/candidates`), details (`GET /api/v1/candidates/{id}`), versions (`GET /api/v1/candidates/{id}/versions`), and applications.

### `CandidateIdentityService`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/CandidateIdentityService.java`
- **Responsibility**: Deduplication engine. Resolves candidate identity by matching primary email, phone numbers, or secondary identifiers. Raises `IdentityReviewCase` when ambiguous conflicts arise.

### `CandidateIdentifier` & `Repository`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/CandidateIdentifier.java`
- **Responsibility**: Normalizes secondary contact handles (emails, phones, LinkedIn URLs).

### `IdentityReviewCase` & `Repository`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/IdentityReviewCase.java`
- **Responsibility**: Entity tracking deduplication collisions needing manual human recruiter resolution.

### `ReviewCaseController`
- **Package**: `com.kartgen.hireflow.identity`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/identity/ReviewCaseController.java`
- **Responsibility**: Endpoints `GET /api/v1/review-cases` and `POST /api/v1/review-cases/{id}/resolve` (`MERGED` or `DISMISSED`).

---

## 8. Job Requisitions & Parsing (`jobs`)

### `Job` & `JobRepository`
- **Package**: `com.kartgen.hireflow.jobs`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/jobs/Job.java`
- **Responsibility**: Requisition entity (`title`, `department`, `location`, `status`, `currentVersionId`).

### `JobController`
- **Package**: `com.kartgen.hireflow.jobs`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/jobs/JobController.java`
- **Responsibility**: REST API for job CRUD, versions, status updates, and match scores (`GET /api/v1/jobs/{id}/matches`).

### `JobService`
- **Package**: `com.kartgen.hireflow.jobs`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/jobs/JobService.java`
- **Responsibility**: Manages job lifecycle and version creation, triggers background match recalculation.

### `JDAnalyzer`
- **Package**: `com.kartgen.hireflow.jobs`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/jobs/JDAnalyzer.java`
- **Responsibility**: Parses job description text to extract mandatory skills, optional skills, and experience ranges.

### `JobVersion` & `JobSkillRequirement`
- **Package**: `com.kartgen.hireflow.jobs`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/jobs/JobVersion.java`
- **Responsibility**: Versioned JD snapshots and associated skill requirements (`mandatory`, `weight`).

---

## 9. Inbound Channels & Sync (`mail.channel`)

### `DirectUploadController`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/DirectUploadController.java`
- **Responsibility**: `POST /api/v1/uploads/direct` for recruiter drag-and-drop file intake.

### `ExtensionIntakeController`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/ExtensionIntakeController.java`
- **Responsibility**: Endpoints `POST /api/v1/uploads/check-hashes` and `POST /api/v1/uploads/extension-intake`.

### `ForwardingWebhookController`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/ForwardingWebhookController.java`
- **Responsibility**: `POST /api/v1/mail-webhook/inbound` receiver for email forwarding webhooks.

### `MailConnectionController`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/MailConnectionController.java`
- **Responsibility**: Manages mailbox connections (`POST /api/v1/mail-connections`, `GET`, `sync-now`, `DELETE`).

### `UniversalImapSyncService` & `OAuthMailSyncAdapter`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **Responsibility**: Connects to IMAP/OAuth mailboxes, scans unread emails, and ingests attachments.

### `SyncResult`
- **Package**: `com.kartgen.hireflow.mail.channel`
- **Responsibility**: Immutable record of mailbox sync outcome (`messagesScanned`, `attachmentsIngested`, `summary`).

---

## 10. Inbound Models & Persistence (`mail.model`)

### `InboundMessage` & `Repository`
- **Package**: `com.kartgen.hireflow.mail.model`
- **Responsibility**: Records raw email metadata (`senderEmail`, `subject`, `sourceType`, `receivedAt`).

### `InboundAttachment` & `Repository`
- **Package**: `com.kartgen.hireflow.mail.model`
- **Responsibility**: Tracks attachments parsed from messages and links them to physical `resume_files`.

### `MailConnection` & `Repository`
- **Package**: `com.kartgen.hireflow.mail.model`
- **Responsibility**: Stores configuration and encrypted credentials for inbound mailboxes.

---

## 11. Inbound Ingestion Service (`mail.service`)

### `MessageIngestionService`
- **Package**: `com.kartgen.hireflow.mail.service`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/service/MessageIngestionService.java`
- **Responsibility**: Atomic ingestion coordinator:
  1. Stores binary file via `StoragePort`.
  2. Registers `TransactionSynchronization` listener to delete staged file if transaction rolls back.
  3. Creates `ResumeFile`, `InboundMessage`, `InboundAttachment`.
  4. Queues `ProcessingJob` for async parsing.

---

## 12. Candidate Matching & Scoring (`matching`)

### `MatchService`
- **Package**: `com.kartgen.hireflow.matching`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/matching/MatchService.java`
- **Responsibility**: Computes deterministic 60/20/20 matching scores:
  - 60% Mandatory Skills overlap.
  - 20% Optional/Bonus Skills overlap.
  - 20% Experience duration alignment.

### `JobMatch` & `Repository`
- **Package**: `com.kartgen.hireflow.matching`
- **Responsibility**: Caches computed match scores and explanation JSON breakdowns.

---

## 13. Operations & Background Processing (`ops`)

### `WorkerScheduler`
- **Package**: `com.kartgen.hireflow.ops`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/ops/WorkerScheduler.java`
- **Responsibility**: Scheduled background poller scanning for `PENDING` processing jobs.

### `ProcessingWorker`
- **Package**: `com.kartgen.hireflow.ops`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/ops/ProcessingWorker.java`
- **Responsibility**: Executes asynchronous resume text extraction, candidate identity matching, and profile promotion.

### `HealthController`
- **Package**: `com.kartgen.hireflow.ops`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/ops/HealthController.java`
- **Responsibility**: Operational health metrics (`GET /api/v1/operations/health`) for test console KPI bar.

### `ProcessingJob`, `AuditEvent`, `OutboxEvent`, `AiUsageEvent` & Repositories
- **Package**: `com.kartgen.hireflow.ops`
- **Responsibility**: Persistence entities for async worker queues, audit trails, transactional outbox events, and LLM token usage tracking.

---

## 14. Candidate Profile & Taxonomy (`profile`)

### `LivingProfileService`
- **Package**: `com.kartgen.hireflow.profile`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/profile/LivingProfileService.java`
- **Responsibility**: Merges extracted resume data into candidate living profiles (`candidate_profile_versions`, `candidate_skills`), promoting resume versions.

### `ResumeVersionController`
- **Package**: `com.kartgen.hireflow.profile`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/profile/ResumeVersionController.java`
- **Responsibility**: Endpoint `GET /api/v1/resume-versions/{id}/view-url` returning signed document view URLs.

### `ResumeVersion` & `CandidateProfileVersion` & Repositories
- **Package**: `com.kartgen.hireflow.profile`
- **Responsibility**: JPA entities tracking versioned candidate resumes and structured profile snapshots.

### `Skill`, `SkillAlias`, `CandidateSkill` & Repositories
- **Package**: `com.kartgen.hireflow.profile`
- **Responsibility**: Canonical skill dictionary (Java, Spring Boot, etc.), alias mappings (k8s -> Kubernetes), and candidate skill associations with confidence scores.

---

## 15. Security & Authentication (`security`)

### `AuthController`
- **Package**: `com.kartgen.hireflow.security`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/security/AuthController.java`
- **Responsibility**: `POST /api/v1/auth/login` (JWT issuance) and `POST /api/v1/auth/extension-token` (extension intake token generation).

### `SecurityConfig`
- **Package**: `com.kartgen.hireflow.security`
- **File Path**: `hireflow-backend/src/main/java/com/kartgen/hireflow/security/SecurityConfig.java`
- **Responsibility**: Spring Security filter chain: stateless session, CSRF disabled, public route permitAll, and custom filter ordering (`WebhookAuthFilter` -> `ExtensionTokenAuthFilter` -> `JwtAuthenticationFilter`).

### `JwtService` & `JwtAuthenticationFilter`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Generates and validates HMAC-SHA256 JWT tokens, sets `SecurityContextHolder` and `TenantContext`.

### `ExtensionTokenAuthFilter`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Authenticates Bearer tokens starting with `hf_ext_` by comparing SHA-256 hashes against `organization_users.extension_token_hash`.

### `WebhookAuthFilter`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Validates incoming webhook requests:
  - Enforces `X-HireFlow-Key-Id`, `X-HireFlow-Timestamp`, `X-HireFlow-Signature`, `X-HireFlow-Idempotency-Key`.
  - Rejects timestamp drift > 300s.
  - Constant-time HMAC comparison.

### `MultipartBodyParser`, `WebhookMultipartBody`, `WebhookContentType`, `WebhookBodyCachingRequestWrapper`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Safely buffers and parses webhook multipart streams without depleting the servlet input stream, allowing signature verification over exact raw payload bytes.

### `RoleNormalizer`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Normalizes role strings (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`) and enforces role hierarchy.

### `ProductionDemoCredentialSanitizer`
- **Package**: `com.kartgen.hireflow.security`
- **Responsibility**: Startup listener neutralizing demo passwords and deactivating demo organizations when `spring.profiles.active=prod`.

---

## 16. Tenant & Membership (`tenant`)

### `Organization` & `OrganizationRepository`
- **Package**: `com.kartgen.hireflow.tenant`
- **Responsibility**: Root multi-tenant entity (`name`, `slug`, `status`, `defaultTimezone`).

### `User` & `UserRepository`
- **Package**: `com.kartgen.hireflow.tenant`
- **Responsibility**: Global user account (`email`, `passwordHash`, `displayName`).

### `OrganizationUser` & `OrganizationUserRepository`
- **Package**: `com.kartgen.hireflow.tenant`
- **Responsibility**: Tenant membership linking `User` to `Organization` with role and `extensionTokenHash`.

### `UserMembershipAuth`
- **Package**: `com.kartgen.hireflow.tenant`
- **Responsibility**: Security projection for fast membership authentication lookups.

---

## 17. Webhook Security & Ingestion (`webhook`)

### `WebhookCredential` & `Repository`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Stores webhook keys (`keyId`, `secretHash`, `organizationId`, `active`).

### `WebhookCredentialController`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Endpoints `POST /api/v1/webhook-credentials` (provisions key, returns secret once) and `GET /api/v1/webhook-credentials`.

### `WebhookDelivery` & `Repository`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Stores webhook deliveries with unique constraint on `(organization_id, idempotency_key)` to guarantee idempotency.

### `WebhookDeliveryStore`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Data access helper checking idempotency key existence and saving deliveries.

### `WebhookIngestionService`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Coordinates idempotency checks, payload SHA-256 verification, and dispatches valid webhook messages to `MessageIngestionService`.

### `LocalWebhookDevCredentialSeeder`
- **Package**: `com.kartgen.hireflow.webhook`
- **Responsibility**: Seeds default development webhook credentials when running with `local` profile.
