# HireFlow AI — Database Schema & Entity Relationships

HireFlow utilizes **Flyway** for database versioning and supports both **H2 (in-memory mode for development and automated testing)** and **PostgreSQL 15+ (production profile)**. All tables are strictly multi-tenant isolated via `organization_id` foreign keys and indexed for low-latency queries.

---

## 1. Flyway Migration History

| Version | Migration File | Description | Key Tables / Indices Created |
|---|---|---|---|
| **V1** | `V1__init_schema.sql` | Core schema initialization | `organizations`, `users`, `organization_users`, `candidates`, `candidate_identifiers`, `candidate_profile_versions`, `resume_files`, `resume_versions`, `skills`, `skill_aliases`, `candidate_skills`, `jobs`, `job_versions`, `job_skill_requirements`, `applications`, `application_submissions`, `application_stage_history`, `candidate_notes`, `job_matches`, `identity_review_cases`, `mail_connections`, `inbound_messages`, `inbound_attachments`, `processing_jobs`, `outbox_events`, `audit_events`, `ai_usage_events` |
| **V2** | `V2__seed_data.sql` | Deterministic development seed data | Demo organization (`Kartgen Technology`), sample HR user, pre-configured roles, base skill taxonomy (55 aliases) |
| **V3** | `V3__performance_indices.sql` | Production performance optimizations | Multi-column composite indices on `(organization_id, sha256)`, candidate lookup index on `(organization_id, primary_email)`, job match lookup indices |
| **V4** | `V4__webhook_security_and_idempotency.sql` | Webhook security subsystem | `webhook_credentials`, `webhook_deliveries`, uniqueness constraints on `(organization_id, idempotency_key)` and `(organization_id, payload_sha256)` |

---

## 2. Complete Entity Inventory (29 Entities)

| # | Entity Class | Table Name | Primary Key | Key Foreign Keys & Relationships |
|---|---|---|---|---|
| 1 | `Organization` | `organizations` | `UUID id` | Root tenant entity. One-to-Many with `OrganizationUser`, `Candidate`, `Job`, etc. |
| 2 | `User` | `users` | `UUID id` | Global user record (`email` unique). One-to-Many with `OrganizationUser`. |
| 3 | `OrganizationUser` | `organization_users` | `UUID id` | `organization_id` -> `organizations`, `user_id` -> `users`. Holds RBAC role & extension token hash. |
| 4 | `Candidate` | `candidates` | `UUID id` | `organization_id` -> `organizations`. Central candidate entity. |
| 5 | `CandidateIdentifier` | `candidate_identifiers` | `UUID id` | `candidate_id` -> `candidates`. Stores normalized secondary emails, phone numbers, LinkedIn URLs. |
| 6 | `CandidateProfileVersion` | `candidate_profile_versions` | `UUID id` | `candidate_id` -> `candidates`. Living profile snapshots (headline, summary, experience, education). |
| 7 | `ResumeFile` | `resume_files` | `UUID id` | `organization_id` -> `organizations`. Immutable record of physical stored file (`sha256`, `s3_object_key`). |
| 8 | `ResumeVersion` | `resume_versions` | `UUID id` | `candidate_id` -> `candidates`, `resume_file_id` -> `resume_files`. Versioned candidate CV. |
| 9 | `Skill` | `skills` | `UUID id` | Canonical skill dictionary (e.g. `Java`, `PostgreSQL`, `React`). |
| 10 | `SkillAlias` | `skill_aliases` | `UUID id` | `skill_id` -> `skills`. Normalization mappings (e.g. `k8s` -> `Kubernetes`). |
| 11 | `CandidateSkill` | `candidate_skills` | `UUID id` | `candidate_profile_version_id` -> `candidate_profile_versions`, `skill_id` -> `skills`. |
| 12 | `Job` | `jobs` | `UUID id` | `organization_id` -> `organizations`. Requisition container. |
| 13 | `JobVersion` | `job_versions` | `UUID id` | `job_id` -> `jobs`. Versioned description, requirements, and status. |
| 14 | `JobSkillRequirement` | `job_skill_requirements` | `UUID id` | `job_version_id` -> `job_versions`, `skill_id` -> `skills`. Mandatory vs optional skill flags. |
| 15 | `Application` | `applications` | `UUID id` | `organization_id` -> `organizations`, `job_id` -> `jobs`, `candidate_id` -> `candidates`. Pipeline tracker. |
| 16 | `ApplicationSubmission` | `application_submissions` | `UUID id` | `application_id` -> `applications`, `resume_version_id` -> `resume_versions`. Submission provenance. |
| 17 | `ApplicationStageHistory` | `application_stage_history` | `UUID id` | `application_id` -> `applications`. Audit trail of stage changes (`from_stage`, `to_stage`, `changed_by`). |
| 18 | `CandidateNote` | `candidate_notes` | `UUID id` | `candidate_id` -> `candidates`, `author_id` -> `users`. Recruiter feedback and interview commentary. |
| 19 | `JobMatch` | `job_matches` | `UUID id` | `job_id` -> `jobs`, `candidate_id` -> `candidates`. Cached match score (0-100) and breakdown JSON. |
| 20 | `IdentityReviewCase` | `identity_review_cases` | `UUID id` | `organization_id` -> `organizations`, `candidate_id` -> `candidates`. Identity deduplication collision cases. |
| 21 | `MailConnection` | `mail_connections` | `UUID id` | `organization_id` -> `organizations`. Configuration for inbound email channels. |
| 22 | `InboundMessage` | `inbound_messages` | `UUID id` | `organization_id` -> `organizations`, `mail_connection_id` -> `mail_connections`. Raw email envelope. |
| 23 | `InboundAttachment` | `inbound_attachments` | `UUID id` | `message_id` -> `inbound_messages`, `resume_file_id` -> `resume_files`. Attachment metadata. |
| 24 | `ProcessingJob` | `processing_jobs` | `UUID id` | `organization_id` -> `organizations`. Async worker queue record (`status`, `job_type`, `attempts`). |
| 25 | `OutboxEvent` | `outbox_events` | `UUID id` | `organization_id` -> `organizations`. Reliable event delivery pattern record. |
| 26 | `AuditEvent` | `audit_events` | `UUID id` | `organization_id` -> `organizations`, `actor_id` -> `users`. System audit event ledger. |
| 27 | `AiUsageEvent` | `ai_usage_events` | `UUID id` | `organization_id` -> `organizations`. Usage tracking for parsing and extraction operations. |
| 28 | `WebhookCredential` | `webhook_credentials` | `UUID id` | `organization_id` -> `organizations`. Secure API keys for inbound webhook HMAC validation. |
| 29 | `WebhookDelivery` | `webhook_deliveries` | `UUID id` | `organization_id` -> `organizations`. Idempotency tracking and raw delivery ledger. |

---

## 3. Database Indexes & Constraints

1. **Tenant Integrity**:
   - `organization_users`: Unique constraint on `(organization_id, user_id)`
   - `organization_users`: Unique index on `extension_token_hash` where not null
   - `candidates`: B-tree index on `(organization_id, primary_email)`
   - `candidates`: B-tree index on `(organization_id, primary_phone)`
2. **Document Deduplication**:
   - `resume_files`: Unique constraint on `(organization_id, sha256)`
   - `resume_files`: Unique constraint on `s3_object_key`
3. **Webhook Security & Idempotency**:
   - `webhook_credentials`: Unique constraint on `key_id`
   - `webhook_deliveries`: Unique constraint on `(organization_id, idempotency_key)`
   - `webhook_deliveries`: Index on `(organization_id, payload_sha256)`
4. **Queue & Job Performance**:
   - `processing_jobs`: Index on `(status, next_attempt_at)`
   - `applications`: Index on `(organization_id, job_id, current_stage)`
