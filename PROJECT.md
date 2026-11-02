# Project: HireFlow — Integrated Production Engineering Release

## Architecture
- **Backend**: Spring Boot 3.3.5 / Java 21 modular monolith with PostgreSQL (H2 in PostgreSQL mode for local dev, PostgreSQL 16 container for integration tests).
  - Package `com.kartgen.hireflow.rules`: Email rule entity, repository, deterministic evaluation engine, rule preview service, and REST controller.
  - Package `com.kartgen.hireflow.mail`: Mailbox connection management, `MailSyncPort`, provider adapters (`GoogleMailAdapter`, `MicrosoftMailAdapter`, `UniversalImapSyncService`), cursor persistence, token rotation, and rate-limit backoff.
  - Package `com.kartgen.hireflow.ingestion`: Message processing lifecycle state machine (`DISCOVERED` -> `COMPLETED`/`REVIEW_REQUIRED`/`FAILED`), idempotent message ingestion, job mapping, and review case dispatch.
  - Package `com.kartgen.hireflow.document`: Secure attachment inspection (content-based MIME, size limits, ZIP-bomb protection), Apache Tika 3.2.2 text extraction pipeline with XXE protections.
  - Package `com.kartgen.hireflow.observability`: `CorrelationIdFilter`, SLF4J MDC context propagation, Actuator metrics exposure, and Micrometer PII-free recruitment metrics.
  - Package `com.kartgen.hireflow.security`: `SecurityConfig` (public OAuth callbacks, SPA forward routes, `/assets/**`), `SpaForwardController`.
  - Database: Additive Flyway migration `V6__email_rules_and_sync_lifecycle.sql`.
- **Frontend**: Independent modern Single-Page Application in `hireflow-frontend/` built with React 18, TypeScript, Vite, Tailwind CSS, Lucide icons, and Vitest.
  - Static asset build output targets `hireflow-backend/src/main/resources/static/` for unified JAR packaging.
  - Complete recruitment SaaS interface: Authentication, Dashboard, Candidates, Jobs, Applications, Mailbox Integration, Recruitment Rules, Processing Activity, Settings, and Documentation Viewer.
  - Strict zero-token exposure: all OAuth exchanges and credentials remain server-side; client receives only status notifications.
- **Testing & CI**:
  - Test suites in backend (`hireflow-backend/src/test/`): unit, contract, security (XFA/XXE), and PostgreSQL integration tests.
  - Component and UI test suites in frontend (`hireflow-frontend/`).
  - GitHub Actions CI (`.github/workflows/ci.yml`): Java 21 build, PostgreSQL 16 service container tests, Aqua Security Trivy dependency vulnerability scan, frontend build verification, hygiene checks.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Apache Tika 3.2.2 Upgrade | Reconcile Maven dependencies to upgrade `tika-core` and parser modules to >= 3.2.2 to remediate CVE-2025-66516 and CVE-2025-54988 | M1 | R5 Security |
| 2 | Targeted XXE Regression Tests | Implement `TikaXxeSecurityTest` verifying rejection/safe handling of crafted XFA, external entities, external DTDs, and Billion Laughs expansion | M1 | R5 Security |
| 3 | Automated Dependency Vulnerability Scanner | Introduce Aqua Security Trivy filesystem scanning into `.github/workflows/ci.yml` producing machine-readable `trivy-results.json` artifact | M1 | R5 Security |
| 4 | Real PostgreSQL CI Verification | Add GitHub Actions PostgreSQL 16 service container, `application-postgres-test.yml`, and `PostgreSqlDatabaseIntegrationTest` verifying Flyway V1-V6, tenant isolation, and transactions | M1 | R6 Reliability |
| 5 | Structured Observability & Metrics | Implement `CorrelationIdFilter` with SLF4J MDC propagation and Micrometer metrics for mailbox sync, queues, and extractions without candidate PII | M1 | R6 Reliability |
| 6 | Flyway V6 Migration | Author additive migration `V6__email_rules_and_sync_lifecycle.sql` for `email_rules`, message lifecycle columns, and review case foreign keys | M2 | R1/R2/R3 Backend |
| 7 | Email Rule Engine Domain & Persistence | Implement `EmailRule` entity, repository, validation, and tenant-isolated queries | M2 | R1 Rule Engine |
| 8 | Deterministic Rule Evaluation & Preview | Implement `EmailRuleEngineService` supporting mailbox, folder, sender, subject/body keywords, attachment attributes, target job mapping, priority, and dry-run preview | M2 | R1 Rule Engine |
| 9 | Email Rules REST API | Implement `EmailRuleController` with full CRUD, prioritization, enable/disable, and preview endpoints | M2 | R1 Rule Engine |
| 10 | Mailbox Sync Decomposition & Checkpointing | Decompose sync behind `MailSyncPort` with `GoogleMailAdapter` and `MicrosoftMailAdapter`, per-page cursor checkpointing, token rotation, and HTTP 429 backoff | M2 | R2 Mailbox Sync |
| 11 | Idempotent Message Processing Lifecycle | Implement end-to-end processing lifecycle state machine (`DISCOVERED` -> `COMPLETED`/`REVIEW_REQUIRED`/`FAILED`) with bounded retries and stale job recovery | M2 | R2 Ingestion |
| 12 | Secure Attachment Inspection & ZIP-Bomb Defense | Validate attachments via content-based MIME inspection, 25MB limits, and archive ZIP-bomb protections (100 entries, 20MB entry / 100MB total) | M2 | R3 Attachment |
| 13 | Candidate Identity Resolution & Zero-Fabrication | Resolve identities, detect duplicate candidates, link job openings, and enforce strict zero-fabrication guarantees | M2 | R3 Intelligence |
| 14 | Ambiguous Mapping & Uncertainty Review Queue | Route ambiguous job mappings, conflicting rules, low extraction confidence, and missing contact information to `IdentityReviewCase` | M2 | R1/R3 Review Queue |
| 15 | Frontend Workspace Scaffolding | Scaffold independent React 18 + TypeScript + Vite project in `hireflow-frontend/` with Tailwind CSS, Lucide icons, Vitest, and package build scripts | M3 | R4 Frontend |
| 16 | Spring Boot Static Serving & SPA Route Forwarding | Configure Vite `outDir`, implement `SpaForwardController.java`, and update `SecurityConfig.java` to permit `/assets/**` and client SPA routes | M3 | R4 Frontend |
| 17 | SaaS Screens (Auth, Dashboard, Candidates, Jobs, Applications) | Implement responsive, accessible views with genuine backend metrics, search/filtering, and zero token exposure | M3 | R4 Frontend |
| 18 | SaaS Screens (Mailboxes, Rules, Activity, Settings) | Implement mailbox OAuth connect, rule builder with live preview, sync activity log inspection, and tenant settings | M3 | R4 Frontend |
| 19 | Visual Documentation Viewer Page | Build dedicated visual documentation page rendering project markdown docs (`docs/*.md`) with navigation, search, and syntax highlighting | M3 | R4 Frontend |
| 20 | Human Acceptance Testing Guide | Author `docs/HUMAN_ACCEPTANCE_TESTING.md` with 35+ property configuration inventory, Google Cloud and Microsoft Entra setup guides, PowerShell secrets, and 9-track test checklist | M4 | R6 Documentation |
| 21 | Engineering Documentation Synchronization | Synchronize `ARCHITECTURE.md`, `API_REFERENCE.md`, `DATABASE.md`, `SECURITY.md`, `DEPLOYMENT.md`, `TESTING.md`, `README.md`, and `AGENTS.md` | M4 | R6 Documentation |
| 22 | Opaque-Box E2E Test Suite | Build comprehensive 4-tier requirement-driven E2E test suite covering all features R1–R6, published via `TEST_INFRA.md` and `TEST_READY.md` | E2E-Track | Dual Track Requirement |
| 23 | Final E2E Pass & Adversarial Hardening | Verify 100% pass of E2E test suite (Tiers 1-4) and execute Tier 5 adversarial coverage hardening | M5 | Final Milestone Requirement |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Security, Dependencies & PostgreSQL Reliability | Features 1, 2, 3, 4, 5 (Tika 3.2.2, XXE tests, Trivy CI scanner, PostgreSQL 16 CI test, observability & metrics) | none | DONE |
| M2 | Backend Ingestion Engine (Rules, Sync & Intelligence) | Features 6, 7, 8, 9, 10, 11, 12, 13, 14 (Flyway V6, rule engine, mailbox sync, attachment security, review queue) | none | DONE |
| M3 | Complete Frontend Redesign & Visual Documentation Page | Features 15, 16, 17, 18, 19 (React/TS/Vite frontend in `hireflow-frontend/`, SPA forwarder, all 10 SaaS screens, docs viewer) | M2 | IN_PROGRESS |
| M4 | Human Acceptance Testing Guide & Documentation Sync | Features 20, 21 (`docs/HUMAN_ACCEPTANCE_TESTING.md` and synchronization of all 8 core engineering docs) | M1, M2, M3 | PLANNED |
| M5 | Final E2E Verification & Adversarial Hardening | Feature 23 (Phase 1: 100% pass of E2E test suite Tiers 1-4; Phase 2: Tier 5 adversarial hardening) | M1, M2, M3, M4, E2E-Track | PLANNED |

## Parallel Tracks
- **E2E Testing Track**: Dispatched in parallel with M1 and M2. Designs opaque-box, requirement-driven test suite for all features according to Category-Partition, BVA, Pairwise, and Real-World workflows. Outputs `TEST_INFRA.md` and signals completion via `TEST_READY.md`.

## Interface Contracts
### 1. Recruitment Email Rules API
- `GET /api/v1/rules`: Returns list of rules for current organization, ordered by `priority ASC`.
- `POST /api/v1/rules`: Creates a new rule. Validates condition JSON, target job mapping, and priority.
- `PUT /api/v1/rules/{id}`: Updates existing rule.
- `DELETE /api/v1/rules/{id}`: Deletes rule.
- `PATCH /api/v1/rules/{id}/status`: Enables or disables rule `{ "enabled": boolean }`.
- `POST /api/v1/rules/reorder`: Updates priorities `{ "ruleIds": ["uuid-1", "uuid-2", ...] }`.
- `POST /api/v1/rules/preview`: Dry-run rule evaluation on a sample message payload `{ "sender": "...", "subject": "...", "body": "...", "attachments": [...] }` returning match explanation without importing or saving.

### 2. Processing Activity & Operations API
- `GET /api/v1/operations/messages`: Returns paginated list of inbound messages with filtering by status (`DISCOVERED`, `FILTERED`, `QUEUED`, `PROCESSING`, `COMPLETED`, `REVIEW_REQUIRED`, `FAILED`), connection ID, and date range.
- `GET /api/v1/operations/messages/{id}`: Returns message detail including `matchedRuleId`, `ruleAction`, and `ruleExplanation`.
- `GET /api/v1/review-cases`: Returns pending review cases with filters by reason code (`AMBIGUOUS_JOB_MAPPING`, `MISSING_CONTACT_INFO`, `LOW_EXTRACTION_CONFIDENCE`, `CONFLICT`).
- `POST /api/v1/review-cases/{id}/resolve`: Resolves review case `{ "action": "ASSIGN_JOB"|"MERGE"|"REJECT"|"CREATE_NEW", "targetJobId": "...", "notes": "..." }`.

### 3. Frontend SPA Routing & Static Serving
- Client routes: `/`, `/dashboard`, `/candidates`, `/candidates/:id`, `/jobs`, `/jobs/:id`, `/applications`, `/mailboxes`, `/rules`, `/activity`, `/settings`, `/docs`, `/login`.
- Static assets: `/assets/**`, `/favicon.ico`, `/vite.svg`.
- Fallback: Non-API, non-asset GET requests forward to `/index.html` via `SpaForwardController`.

### 4. Correlation & Observability Headers
- Header `X-Correlation-ID`: Incoming header accepted or generated if absent; bound to SLF4J MDC `correlationId` and reflected in response headers.
- Health: `GET /actuator/health` returns system and database status.
- Metrics: `GET /actuator/metrics` exposes recruitment metrics (`hireflow.sync.duration`, `hireflow.sync.items.count`, `hireflow.queue.jobs.pending`, etc.).

## Code Layout
- `hireflow-backend/pom.xml`: Dependency management, Apache Tika 3.2.2 upgrade.
- `hireflow-backend/src/main/resources/db/migration/V6__email_rules_and_sync_lifecycle.sql`: Additive schema changes.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/rules/`: Email rules domain, engine, and controller.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/`: Mail connection, sync adapters, and checkpoints.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/ingestion/`: Lifecycle tracking and review case routing.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/document/`: Attachment validation and Tika extraction.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/observability/`: Correlation filter and metrics.
- `hireflow-backend/src/main/java/com/kartgen/hireflow/security/SpaForwardController.java`: SPA route forwarding.
- `hireflow-backend/src/test/java/com/kartgen/hireflow/security/TikaXxeSecurityTest.java`: XXE security tests.
- `hireflow-backend/src/test/java/com/kartgen/hireflow/integration/PostgreSqlDatabaseIntegrationTest.java`: Real PostgreSQL integration test.
- `hireflow-frontend/`: Independent React + TypeScript + Vite frontend project.
- `.github/workflows/ci.yml`: CI pipeline with PostgreSQL 16 container, Trivy scan, and frontend build.
- `docs/HUMAN_ACCEPTANCE_TESTING.md`: Human acceptance testing guide.
