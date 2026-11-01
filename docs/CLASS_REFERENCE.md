# HireFlow AI — Java Class Reference

This reference catalogs all production Java classes within `hireflow-backend/src/main/java/com/kartgen/hireflow`, grouped by package, describing their responsibility and architectural role.

---

## 1. Application Root (`com.kartgen.hireflow`)
- `HireFlowApplication`: Primary Spring Boot entrypoint. Enables scheduling, asynchronous processing, transaction management, and sets virtual thread concurrency parameters.

---

## 2. Common (`com.kartgen.hireflow.common`)
- `HashUtil`: Cryptographic utility class providing constant-time byte hashing (`sha256Hex`, `sha256Bytes`), HMAC-SHA256 calculations, and secure token generation.
- `BusinessException`: Base unchecked domain exception representing business rule violations (e.g. invalid status transitions, duplicate candidates).
- `ResourceNotFoundException`: Thrown when a queried entity does not exist within the caller's organization boundary.
- `SecurityViolationException`: Thrown on authorization, cross-tenant boundary, or cryptographic signature check failures.

---

## 3. Configuration (`com.kartgen.hireflow.config`)
- `HireFlowProperties`: Strongly typed configuration bean bound to `hireflow.*` properties (storage paths, HMAC secrets, worker polling intervals).
- `SecurityConfig`: Spring Security 6 filter chain configuration, setting up stateless session policy, CSRF disablement for APIs, and registering authentication filters.
- `AsyncConfig`: Configures application task executors to leverage Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).
- `StartupProfileGuard`: Fail-fast validator on application startup. Ensures production environments use strong secrets and proper PostgreSQL datasources.

---

## 4. Document & Storage (`com.kartgen.hireflow.document`)
- `StoragePort`: Domain port interface declaring abstract binary storage operations (`store`, `retrieve`, `delete`, `generateViewUrl`).
- `LocalDiskStorageAdapter`: File system adapter implementing `StoragePort` with tenant directory partitioning, SHA-256 subdirectory trees, and atomic staging renames.
- `LocalS3Controller`: REST controller verifying HMAC signed tokens and streaming binary files to authenticated clients.
- `ResumeFile`: JPA entity recording stored file metadata (original filename, size, MIME type, SHA-256 hash, object key).
- `ResumeFileRepository`: Spring Data JPA repository for resume file persistence and deduplication queries.

---

## 5. Resume Extraction (`com.kartgen.hireflow.extraction`)
- `LocalSmartHeuristicExtractor`: Deterministic parsing engine extracting candidate details using Apache Tika metadata and Apache PDFBox text extraction.
- `ParsedResume`: Immutable value object carrying structured candidate data extracted from raw documents.

---

## 6. Hiring & Applications (`com.kartgen.hireflow.hiring`)
- `ApplicationController`: REST controller handling application creation, candidate-job linking, and stage transition requests.
- `ApplicationService`: Domain service enforcing application stage state machine logic (`NEW` -> `SCREENING` -> `INTERVIEW` -> `OFFER` -> `HIRED` / `REJECTED`) and emitting audit entries.
- `Application`: JPA entity representing a candidate's pipeline position for a specific job opening.
- `ApplicationRepository`: Repository with tenant-scoped application queries.
- `ApplicationStageHistory`: Audit entity tracking who changed an application stage, previous stage, and timestamp.
- `ApplicationStageHistoryRepository`: Repository for stage history retrieval.
- `ApplicationSubmission`: Association entity linking an application to a specific resume version.
- `CandidateNote`: Recruiter interview notes and candidate evaluations.
- `CandidateNoteRepository`: Repository for candidate notes.

---

## 7. Candidate Identity (`com.kartgen.hireflow.identity`)
- `CandidateController`: REST API for querying candidate profiles, viewing documents, and updating statuses.
- `CandidateIdentityService`: Core identity deduplication service that matches incoming candidates by email, phone, or external handles.
- `Candidate`: Central candidate entity holding primary contact details and active/archived state.
- `CandidateRepository`: Repository for candidate searches with tenant boundaries.
- `CandidateIdentifier`: Normalization entity storing secondary emails, phone numbers, and social URLs.
- `CandidateIdentifierRepository`: Repository for identifier lookups.
- `IdentityReviewCase`: Entity representing ambiguous matches requiring manual human intervention.
- `IdentityReviewCaseRepository`: Repository for identity review cases.
- `ReviewCaseController`: REST API for recruiters to review and resolve identity collisions (`MERGED` or `SEPARATED`).

---

## 8. Job Management (`com.kartgen.hireflow.jobs`)
- `JobController`: REST API for job requisition CRUD and candidate match queries.
- `JobService`: Manages job lifecycle, requirement versioning, and triggers score recomputations.
- `Job`: JPA entity holding job details (title, department, status, location).
- `JobRepository`: Repository for tenant job requisitions.
- `JobVersion`: Immutable snapshot of a job's requirements and description.
- `JobVersionRepository`: Repository for job versions.
- `JobSkillRequirement`: Skill requirement entity defining mandatory vs bonus skills and experience weights.
- `JobSkillRequirementRepository`: Repository for job skill requirements.

---

## 9. Inbound Mail & Intake (`com.kartgen.hireflow.mail` & `channel`)
- `DirectUploadController`: Handles direct file uploads from recruiters in the UI.
- `ExtensionIntakeController`: Handles resume streaming and hash checks from the browser extension.
- `ForwardingWebhookController`: Receives programmatic email forwarding webhooks with HMAC-SHA256 signatures.
- `MailConnectionController`: Configuration endpoints for email ingestion channels.
- `MessageIngestionService`: Central domain service that receives messages, saves attachments, coordinates with `StoragePort`, and queues parsing jobs.
- `InboundMessage`: JPA entity representing raw email envelope metadata.
- `InboundMessageRepository`: Repository for inbound messages.
- `InboundAttachment`: Entity representing attachments parsed from inbound messages.
- `InboundAttachmentRepository`: Repository for message attachments.
- `MailConnection`: Configuration entity for email forwarding addresses.
- `MailConnectionRepository`: Repository for mail connections.

---

## 10. Candidate Matching (`com.kartgen.hireflow.matching`)
- `MatchService`: High performance matching service scoring candidates against job requirements.
- `JobMatch`: JPA entity caching match scores (0-100) and detailed skill overlap breakdowns.
- `JobMatchRepository`: Repository for match scores.

---

## 11. Operations & Scheduling (`com.kartgen.hireflow.ops`)
- `WorkerScheduler`: Periodic scheduler (`@Scheduled`) scanning for pending async tasks.
- `ProcessingWorker`: Worker service processing parsing jobs and recomputing candidate matches.
- `HealthController`: Health check and operational diagnostics endpoint.
- `ProcessingJob`: JPA entity representing background tasks (`status`: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`).
- `ProcessingJobRepository`: Repository with atomic conditional update queries (`claim()`, `recoverStaleRunningJobs()`).
- `AuditEvent`: System audit log entity.
- `AuditEventRepository`: Repository for audit logs.
- `OutboxEvent`: Transactional outbox event entity.
- `OutboxEventRepository`: Repository for outbox events.
- `AiUsageEvent`: Token/usage tracking entity for document parsing.
- `AiUsageEventRepository`: Repository for usage events.

---

## 12. Candidate Profile & Taxonomy (`com.kartgen.hireflow.profile`)
- `LivingProfileService`: Maintains versioned candidate profile evolutions.
- `ResumeVersionController`: REST controller for retrieving specific resume versions and signed view URLs.
- `CandidateProfileVersion`: Snapshot of candidate parsed profile data.
- `CandidateProfileVersionRepository`: Repository for profile versions.
- `ResumeVersion`: Versioned link between a candidate and a physical resume file.
- `ResumeVersionRepository`: Repository for resume versions.
- `Skill`: Canonical skill dictionary entity.
- `SkillRepository`: Repository for skills.
- `SkillAlias`: Skill synonym and alias mappings.
- `SkillAliasRepository`: Repository for skill aliases.
- `CandidateSkill`: Association between candidate profile versions and skills.
- `CandidateSkillRepository`: Repository for candidate skills.

---

## 13. Security & Multitenancy (`com.kartgen.hireflow.security` & `tenant`)
- `AuthController`: REST controller for user login and logout.
- `JwtService`: Generates and validates HMAC-SHA256 JWT tokens.
- `JwtAuthenticationFilter`: Request filter validating Bearer JWTs and setting Spring Security context.
- `ExtensionTokenAuthFilter`: Request filter validating Bearer extension tokens (`hf_ext_...`).
- `TenantContextHolder`: ThreadLocal container holding the authenticated tenant context.
- `TenantContextFilter`: Populates tenant context from authenticated user details.
- `ProductionDemoCredentialSanitizer`: Startup bean disabling demo credentials when active profile is `prod`.
- `Organization`: Tenant entity.
- `OrganizationRepository`: Repository for organizations.
- `User`: Global user identity entity.
- `UserRepository`: Repository for users.
- `OrganizationUser`: Tenant membership association with role (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`).
- `OrganizationUserRepository`: Repository for organization memberships.

---

## 14. Webhook Security (`com.kartgen.hireflow.webhook`)
- `WebhookAuthFilter`: Request filter enforcing HMAC-SHA256 v2 signature validation and timestamp freshness.
- `WebhookIngestionService`: Handles webhook delivery persistence and idempotency deduplication.
- `WebhookCredentialController`: REST controller for provisioning and rotating organization webhook keys.
- `WebhookCredential`: Entity storing keyId and hashed secret.
- `WebhookCredentialRepository`: Repository for webhook credentials.
- `WebhookDelivery`: Entity tracking incoming webhook idempotency keys and payloads.
- `WebhookDeliveryRepository`: Repository for webhook deliveries.
