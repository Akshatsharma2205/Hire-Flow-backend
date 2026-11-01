# HireFlow AI — Database Schema & Entity Relationship Matrix

HireFlow utilizes **Flyway** for database versioning and supports both **H2 (in-memory mode for development and automated testing)** and **PostgreSQL 15+ (production profile)**. All tables are strictly multi-tenant isolated via `organization_id` foreign keys and indexed for low-latency queries.

---

## 1. Flyway Migration History

| Version | Migration File | Description | Key Tables / Indices Created |
|---|---|---|---|
| **V1** | `V1__init_schema.sql` | Core schema initialization | `organizations`, `users`, `organization_users`, `candidates`, `candidate_identifiers`, `candidate_profile_versions`, `resume_files`, `resume_versions`, `skills`, `skill_aliases`, `candidate_skills`, `jobs`, `job_versions`, `job_skill_requirements`, `applications`, `application_submissions`, `application_stage_history`, `candidate_notes`, `job_matches`, `identity_review_cases`, `mail_connections`, `inbound_messages`, `inbound_attachments`, `processing_jobs`, `outbox_events`, `audit_events`, `ai_usage_events` |
| **V2** | `V2__seed_data.sql` | Deterministic development seed data | Demo organization (`Kartgen Infotech LLP`), sample HR user (`hr@kartgen.com`), pre-configured roles, base skill taxonomy (55 aliases) |
| **V3** | `V3__performance_indices.sql` | Production performance optimizations | Multi-column composite indices on `(organization_id, sha256)`, candidate lookup index on `(organization_id, primary_email)`, job match lookup indices |
| **V4** | `V4__webhook_security_and_idempotency.sql` | Webhook security subsystem | `webhook_credentials`, `webhook_deliveries`, uniqueness constraints on `(organization_id, idempotency_key)` and `(organization_id, payload_sha256)` |

---

## 2. Complete Entity Relationship Matrix

This matrix documents the actual JPA entities, relationship types, ownership, join columns/foreign keys, cascade behavior, and test coverage.

| Entity (Source) | Related Entity (Target) | Relationship Type | Ownership Side | Join Column / Foreign Key | On Delete / Cascade | Nullable | Verified Test Coverage |
|---|---|---|---|---|---|---|---|
| `Organization` | `OrganizationUser` | One-to-Many | `OrganizationUser` | `organization_id` | RESTRICT | No | `AuthTenantIntegrationTest`, `UserMembershipRoleAuthorizationTest` |
| `User` | `OrganizationUser` | One-to-Many | `OrganizationUser` | `user_id` | CASCADE | No | `UserMembershipRoleAuthorizationTest` |
| `Organization` | `Candidate` | One-to-Many | `Candidate` | `organization_id` | CASCADE | No | `FrontendSpaEndToEndIntegrationTest`, `CandidateControllerIntegrationTest` |
| `Candidate` | `CandidateIdentifier` | One-to-Many | `CandidateIdentifier` | `candidate_id` | CASCADE | No | `CandidateIdentityServiceTest` |
| `Candidate` | `CandidateProfileVersion` | One-to-Many | `CandidateProfileVersion`| `candidate_id` | CASCADE | No | `IntakePipelineIntegrationTest` |
| `Candidate` | `ResumeVersion` | One-to-Many | `ResumeVersion` | `candidate_id` | CASCADE | No | `FrontendSpaEndToEndIntegrationTest`, `LivingProfileServiceTest` |
| `Organization` | `ResumeFile` | One-to-Many | `ResumeFile` | `organization_id` | RESTRICT | No | `TransactionSafeStorageFailureInjectionTest`, `LocalDiskStorageAdapterTest` |
| `ResumeVersion` | `ResumeFile` | Many-to-One | `ResumeVersion` | `resume_file_id` | RESTRICT | No | `FrontendSpaEndToEndIntegrationTest` |
| `Skill` | `SkillAlias` | One-to-Many | `SkillAlias` | `skill_id` | CASCADE | No | `LocalSmartHeuristicExtractorTest` |
| `CandidateProfileVersion` | `CandidateSkill` | One-to-Many | `CandidateSkill` | `profile_version_id` | CASCADE | No | `FrontendSpaEndToEndIntegrationTest` |
| `Skill` | `CandidateSkill` | One-to-Many | `CandidateSkill` | `skill_id` | RESTRICT | No | `FrontendSpaEndToEndIntegrationTest` |
| `Organization` | `Job` | One-to-Many | `Job` | `organization_id` | CASCADE | No | `JobServiceTest`, `FrontendSpaEndToEndIntegrationTest` |
| `Job` | `JobVersion` | One-to-Many | `JobVersion` | `job_id` | CASCADE | No | `JobServiceTest` |
| `JobVersion` | `JobSkillRequirement` | One-to-Many | `JobSkillRequirement` | `job_version_id` | CASCADE | No | `JobServiceTest` |
| `Job` | `Application` | One-to-Many | `Application` | `job_id` | CASCADE | No | `ApplicationControllerIntegrationTest` |
| `Candidate` | `Application` | One-to-Many | `Application` | `candidate_id` | CASCADE | No | `ApplicationControllerIntegrationTest` |
| `Application` | `ApplicationSubmission` | One-to-Many | `ApplicationSubmission`| `application_id` | CASCADE | No | `ApplicationLifecycleIntegrationTest` |
| `Application` | `ApplicationStageHistory`| One-to-Many | `ApplicationStageHistory`| `application_id` | CASCADE | No | `ApplicationControllerIntegrationTest` |
| `Candidate` | `CandidateNote` | One-to-Many | `CandidateNote` | `candidate_id` | CASCADE | No | `CandidateControllerIntegrationTest` |
| `Job` | `JobMatch` | One-to-Many | `JobMatch` | `job_id` | CASCADE | No | `MatchServiceTest` |
| `Candidate` | `JobMatch` | One-to-Many | `JobMatch` | `candidate_id` | CASCADE | No | `MatchServiceTest` |
| `Candidate` | `IdentityReviewCase` | One-to-Many | `IdentityReviewCase` | `candidate_id` | CASCADE | No | `ReviewCaseControllerIntegrationTest` |
| `Organization` | `MailConnection` | One-to-Many | `MailConnection` | `organization_id` | CASCADE | No | `FrontendSpaEndToEndIntegrationTest` |
| `MailConnection` | `InboundMessage` | One-to-Many | `InboundMessage` | `mail_connection_id` | SET NULL | Yes | `IntakePipelineIntegrationTest` |
| `InboundMessage` | `InboundAttachment` | One-to-Many | `InboundAttachment` | `message_id` | CASCADE | No | `WebhookDeliveryIntegrityIntegrationTest` |
| `InboundAttachment` | `ResumeFile` | Many-to-One | `InboundAttachment` | `resume_file_id` | RESTRICT | No | `WebhookDeliveryIntegrityIntegrationTest` |
| `Organization` | `ProcessingJob` | One-to-Many | `ProcessingJob` | `organization_id` | CASCADE | No | `ProcessingWorkerTest` |
| `Organization` | `WebhookCredential` | One-to-Many | `WebhookCredential` | `organization_id` | CASCADE | No | `WebhookAuthIntegrationTest` |
| `Organization` | `WebhookDelivery` | One-to-Many | `WebhookDelivery` | `organization_id` | CASCADE | No | `WebhookReplayDurabilityIntegrationTest` |

---

## 3. Database Indexes & Constraints

1. **Tenant Isolation**:
   - `organization_users`: Unique constraint on `(organization_id, user_id)`
   - `organization_users`: Unique index on `extension_token_hash` where not null
   - `candidates`: Composite B-tree index on `(organization_id, primary_email)`
   - `candidates`: Composite B-tree index on `(organization_id, primary_phone)`
2. **Document Deduplication**:
   - `resume_files`: Unique constraint on `(organization_id, sha256)`
   - `resume_files`: Unique constraint on `s3_object_key`
3. **Webhook Security & Idempotency**:
   - `webhook_credentials`: Unique constraint on `key_id`
   - `webhook_deliveries`: Unique constraint on `(organization_id, idempotency_key)`
   - `webhook_deliveries`: Index on `(organization_id, payload_sha256)`
4. **Queue & Job Performance**:
   - `processing_jobs`: Composite index on `(status, next_attempt_at)`
   - `applications`: Composite index on `(organization_id, job_id, current_stage)`
   - `job_matches`: Index on `(job_version_id, score DESC)`
