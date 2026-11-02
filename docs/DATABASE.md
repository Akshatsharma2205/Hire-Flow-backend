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
| **V5** | `V5__oauth_authorization_attempts.sql` | OAuth 2.0 authorization attempt ledger & mailbox state | `oauth_authorization_attempts`, unique constraint on `state_token`, indices on `(expires_at, status)`, `(organization_id, created_at)`, and supporting index `(organization_id, provider, mailbox_address)` on `mail_connections` |

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
| `Organization` | `OAuthAuthorizationAttempt` | One-to-Many | `OAuthAuthorizationAttempt` | `organization_id` | RESTRICT | No | `OAuthMailControllerTest`, `OAuthMailConnectionE2ETest` |
| `User` | `OAuthAuthorizationAttempt` | One-to-Many | `OAuthAuthorizationAttempt` | `user_id` | CASCADE | No | `OAuthMailControllerTest` |

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
5. **OAuth 2.0 Authorization Attempts & Mailbox State**:
   - `oauth_authorization_attempts`:
     - Primary Key: `id` (UUID)
     - Unique constraint: `uq_oauth_att_state` on `state_token`
     - Status check constraint: `ck_oauth_att_status` CHECK `(status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'FAILED'))`
     - Foreign Key: `fk_oauth_att_org` on `organization_id` REFERENCES `organizations(id)`
     - Foreign Key: `fk_oauth_att_user` on `user_id` REFERENCES `users(id)`
     - Lookup index: `idx_oauth_att_state` on `state_token`
     - Column index: `idx_oauth_att_state_col` on `state`
     - Organization audit index: `idx_oauth_att_org_created` on `(organization_id, created_at)`
     - Expiry sweep index: `idx_oauth_att_expires` on `(expires_at, status)`
   - `mail_connections`:
     - Composite lookup index: `idx_mailconn_org_prov_box` on `(organization_id, provider, mailbox_address)`

---

## 4. OAuth Authorization Attempt Lifecycle & State Machine

The `oauth_authorization_attempts` table functions as an immutable, high-security state ledger preventing CSRF, session fixation, and replay attacks during Google and Microsoft OAuth flows:

```
                  ┌───────────────┐
                  │    PENDING    │ (10-minute TTL)
                  └───────┬───────┘
                          │
         ┌────────────────┼────────────────┐
         │ (Successful    │ (Provider error │ (TTL expired
         │  token exch.)  │  or cancelled)  │  before callback)
         ▼                ▼                 ▼
  ┌──────────────┐ ┌──────────────┐  ┌──────────────┐
  │   CONSUMED   │ │    FAILED    │  │   EXPIRED    │
  └──────────────┘ └──────────────┘  └──────────────┘
```

1. **Initiation**: Inserted with `status = 'PENDING'`, `created_at = now()`, `expires_at = now() + 10m`, and cryptographically unpredictable `state_token` (32 bytes `SecureRandom`).
2. **Atomic Consumption**: When provider callback arrives, backend executes atomic conditional SQL:
   `UPDATE oauth_authorization_attempts a SET a.status = 'CONSUMED', a.consumed_at = :now WHERE (a.state_token = :token OR a.state = :token) AND a.status = 'PENDING' AND a.expires_at > :now`
   If 1 row is updated, code exchange proceeds; if 0 rows, callback is rejected as a replay or expired attempt.
3. **Failure Transition**: If provider returns an error (e.g. `access_denied`), attempt is marked `FAILED` with `consumed_at = now()`.
4. **Expiration Sweep**: Periodic or on-demand cleanup transitions unconsumed rows past TTL to `EXPIRED`.
