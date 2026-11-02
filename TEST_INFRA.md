# TEST_INFRA.md — HireFlow Integrated Production Release E2E Test Infrastructure & Specification

## 1. Executive Summary & Testing Philosophy

HireFlow is an intelligent, modular recruitment automation platform delivering mailbox-based candidate ingestion, configurable email rule evaluation, secure resume attachment extraction, identity deduplication, candidate matching, and an independent modern SaaS frontend.

The testing infrastructure follows an **opaque-box, requirement-driven, 4-tier testing methodology**. Tests validate system behavior exclusively through its external interface contracts (HTTP REST APIs, database state validation, mock server boundaries, and synthetic document fixtures), completely independent of private implementation details.

### Core Testing Tenets
1. **Opaque-Box Verification**: Exercise functionality through public API boundaries (`/api/v1/rules/**`, `/api/v1/mail-connections/**`, `/api/v1/operations/**`, `/api/v1/review-cases/**`, `/api/v1/uploads/**`, `/actuator/**`, and SPA frontend routes). Assert on HTTP status codes, headers, response JSON schemas, and database state.
2. **Deterministic Network Isolation**: Mock external OAuth 2.0 authorization servers and provider APIs (Google Workspace Gmail API and Microsoft 365 Graph API) via an embedded in-memory HTTP server (`com.sun.net.httpserver.HttpServer`) bound to `127.0.0.1:0`. Zero unmocked WAN traffic is emitted during testing.
3. **Multi-Tenant Security & Isolation**: Verify that rule creation, evaluation, mail synchronization, candidate records, and review cases strictly enforce tenant boundaries (`organizationId`), preventing cross-tenant leakage or privilege escalation.
4. **Zero Fabrication & Truth In Extraction**: Validate that resume parsing, candidate identity resolution, and job associations reflect authentic extracted text and never fabricate candidate qualifications, contact details, or job history.
5. **Progressive Testability & Graceful Degradation**: Structure tests across progressive milestones (M1 through M5). Tests for features under active implementation utilize dynamic capability detection to verify contracts as features land without false negative failures.
6. **Zero Secret & Token Exposure**: Enforce that client secrets, refresh tokens, access tokens, and candidate PII never appear in log events, Actuator metric tags, or frontend payloads.

---

## 2. Feature Inventory & Test Mapping

The test suite directly covers all requirements R1 through R6 and Features 1–23 specified in `PROJECT.md` and `ORIGINAL_REQUEST.md`:

| Feature Area | Req ID | Feature Inventory & Description | Test Tier | Primary Verification Channel |
|--------------|--------|---------------------------------|-----------|-----------------------------|
| **Rule Engine** | R1 | Email rule CRUD, prioritization, enable/disable, preview dry-run, deterministic evaluation, tenant isolation | T1, T2, T3, T4 | REST `/api/v1/rules/**`, `/api/v1/rules/preview` |
| **Mailbox Sync** | R2 | Google/Microsoft/IMAP sync, cursor checkpointing, token rotation, HTTP 429 backoff, message lifecycle | T1, T2, T3, T4 | REST `/api/v1/mail-connections/**`, Mock OAuth Server |
| **Attachment & AI** | R3 | Content-based MIME, 25MB limit, ZIP-bomb defense, identity deduplication, job link, review queue | T1, T2, T3, T4 | REST `/api/v1/uploads/**`, `/api/v1/review-cases/**` |
| **Frontend & Docs** | R4 | Modern SPA forward routing, static asset serving, zero token exposure, docs viewer, genuine metrics | T1, T2, T3, T4 | HTTP GET `/`, `/assets/**`, `/docs/**`, `/api/v1/operations/**` |
| **Tika & Security** | R5 | Tika >= 3.2.2 upgrade, XFA entity rejection, Billion Laughs expansion defense, external DTD denial, Trivy | T1, T2, T3, T4 | Synthetic PDF/XFA fixtures, CI workflow inspection |
| **Observability** | R6 | `X-Correlation-ID` header, MDC propagation, Actuator health/metrics, PostgreSQL Flyway, HAT guide | T1, T2, T3, T4 | HTTP headers, `/actuator/**`, `docs/HUMAN_ACCEPTANCE_TESTING.md` |

---

## 3. The 4-Tier Test Architecture

The end-to-end integration test suite is structured into four distinct test tiers designed for progressive verification, diagnostic clarity, and complete coverage.

```
+---------------------------------------------------------------------------------------------------+
| Tier 4: Real-World Application Scenarios (>= 5 Recruiter Workflows)                              |
|   - Scenario 1: Complete Recruiter Mailbox Ingestion & Rule Pipeline (Gmail)                      |
|   - Scenario 2: Multi-Attachment Forwarded Agency Batch Ingestion (Microsoft 365)                 |
|   - Scenario 3: Adversarial / Malicious Attachment Attack & Quarantine (XXE + 30MB File)         |
|   - Scenario 4: Ambiguous Job Mapping HR Review & Resolution Workflow                            |
|   - Scenario 5: Mailbox Rate Limit Backoff & Self-Healing Reauthorization                         |
+---------------------------------------------------------------------------------------------------+
                                                  |
+---------------------------------------------------------------------------------------------------+
| Tier 3: Cross-Feature Combinations (Pairwise Coverage)                                            |
|   - Pair 1: Mailbox Sync (R2) + Rule Engine Match (R1) + Job Association (R3)                    |
|   - Pair 2: Mailbox Sync (R2) + Malicious Attachment Rejection (R3/R5) + Correlation (R6)        |
|   - Pair 3: Rule Ambiguity (R1) + Candidate Deduplication (R3) + Review Case Routing (R3)        |
|   - Pair 4: Token Rotation (R2) + Mailbox Sync (R2) + Observability Metrics (R6)                 |
|   - Pair 5: Frontend SPA Forward (R4) + Actuator Health (R6) + Correlation ID Header (R6)        |
|   - Pair 6: Rule Engine Evaluation (R1) + Multi-Tenant Isolation (R6)                            |
+---------------------------------------------------------------------------------------------------+
                                                  |
+---------------------------------------------------------------------------------------------------+
| Tier 2: Boundary & Corner Cases (Category-Partition & BVA — >= 5 per feature R1–R6 = 30 tests)   |
|   - R1: Empty conditions, conflicting rules, regex special chars, tenant isolation, priority min/max|
|   - R2: Expired cursor recovery, revoked token in sync, concurrent sync lock, max retries, empty box|
|   - R3: >25MB file rejection, ZIP bomb rejection, missing contact info, ambiguous skills, corrupt PDF|
|   - R4: Deep unknown route forward, path traversal block, malformed query params, 401 unauth, stress|
|   - R5: Nested parameter entities, OOB SSRF block, huge XFA stream memory limit, bad PDF header   |
|   - R6: Oversized correlation ID, CRLF injection in headers, concurrent MDC isolation, PII-free  |
+---------------------------------------------------------------------------------------------------+
                                                  |
+---------------------------------------------------------------------------------------------------+
| Tier 1: Feature Coverage (Happy Path — >= 5 per feature R1–R6 = 30 tests)                        |
|   - R1: Rule CRUD, priority ordering, preview dry-run, enable/disable toggle, match explanation  |
|   - R2: Initial sync cursor, incremental sync delta, idempotent deduplication, HTTP 429 backoff   |
|   - R3: Valid PDF intake, MIME detection, candidate deduplication, job opening association, review|
|   - R4: Client SPA routes forward to index.html, static assets serve, zero token exposure, docs  |
|   - R5: Tika 3.2.2 text extraction, XFA XML defense, Billion Laughs defense, DTD block, Trivy CI |
|   - R6: X-Correlation-ID echo/generation, Actuator health UP, Actuator metrics, HAT guide check |
+---------------------------------------------------------------------------------------------------+
```

---

## 4. Category-Partition & Boundary Value Analysis (BVA) Specification

### Feature R1: Recruitment Email Rule Engine
- **Partition 1: Rule Conditions**:
  - Valid: Sender domain matching (`*@recruiting.com`), subject keyword (`Senior Java Developer`), folder (`INBOX`).
  - Boundary: Empty condition object `{}` (Rejected 400), regex metacharacters (`[Senior]+ (Java|Go)/*`).
- **Partition 2: Priority**:
  - Valid: Ascending integers `1, 2, 3...`
  - Boundary: Priority 0, negative values, duplicate priorities, `Integer.MAX_VALUE`.
- **Partition 3: Job Mapping**:
  - Valid: Specific target `jobId` UUID.
  - Ambiguous / Conflicting: Multiple rules matching with different target jobs -> routes to `IdentityReviewCase`.
- **Partition 4: Multi-Tenant Boundary**:
  - Tenant A accesses Tenant B's rule ID -> HTTP 404/403.

### Feature R2: Mailbox Synchronization & Ingestion
- **Partition 1: Sync Cursors**:
  - Valid: Valid `historyId` (Google) / `deltaLink` (Microsoft).
  - Boundary: Expired cursor -> graceful full re-sync without duplicate records.
- **Partition 2: Rate Limiting & Backoff**:
  - Normal: HTTP 200 OK with messages array.
  - Boundary: HTTP 429 Too Many Requests -> exponential backoff header respected.
- **Partition 3: Message Idempotency**:
  - Unique message ID -> Ingested and lifecycle `DISCOVERED` -> `COMPLETED`.
  - Re-synced identical message ID -> Skipped, 0 duplicates created.
- **Partition 4: Retries**:
  - Transient failure -> Retry count increments.
  - Exceeds max retries (3) -> Lifecycle transitions to `FAILED`.

### Feature R3: Attachment Processing & Candidate Intelligence
- **Partition 1: File Size**:
  - Valid: 50KB to 24MB.
  - Boundary: Exactly 25MB (accepted), 25MB + 1 byte (rejected with HTTP 413 / validation error).
- **Partition 2: Archive / ZIP Bomb**:
  - Valid: ZIP with 1–5 resume PDFs (<20MB total).
  - Boundary: ZIP with >100 entries, high compression ratio (>100:1) -> rejected by Gatekeeper.
- **Partition 3: MIME Inspection**:
  - Valid: `application/pdf`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`.
  - Malicious / Boundary: Executable script (`.sh`, `.exe`) disguised with `.pdf` extension -> rejected by magic bytes.
- **Partition 4: Identity Resolution**:
  - Unique email & phone -> New Candidate created.
  - Identical email -> Merged into existing candidate, new `ResumeVersion` appended.
  - Missing email and phone -> Routed to `IdentityReviewCase` (`MISSING_CONTACT_INFO`).

### Feature R5: Apache Tika XXE & Parser Security
- **Partition 1: Document Formats**:
  - Standard PDF -> Extracted cleanly.
  - PDF with embedded Adobe XFA XML form -> XFA processed safely with external entity resolution disabled.
- **Partition 2: Entity Expansions**:
  - Billion Laughs (`<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;">...`) -> Parser limits entity expansion safely without OOM.
  - External System Entities (`SYSTEM "file:///etc/passwd"`, `SYSTEM "http://169.254.169.254/"`) -> Blocked without local file read or external network socket creation.

### Feature R6: Observability & Reliability
- **Partition 1: Correlation ID**:
  - Header present: `X-Correlation-ID: custom-trace-123` -> Echoed in response header, propagated to SLF4J MDC.
  - Header absent: System generates random UUIDv4 and returns in response.
  - Malformed / Injection: Header with `\r\n` or >100 characters sanitized.

---

## 5. Pairwise Cross-Feature Combination Matrix (Tier 3)

| Test ID | Feature 1 | Feature 2 | Feature 3 | Interaction Under Test | Expected Observable Result |
|---------|-----------|-----------|-----------|------------------------|---------------------------|
| **P1** | R2 Mailbox Sync | R1 Rule Engine | R3 Candidate Link | Inbound email processed via active rule creates candidate & links job | Candidate persisted, Application created for target job, lifecycle `COMPLETED` |
| **P2** | R2 Mailbox Sync | R3 Attachment | R6 Observability | Malicious or oversized attachment in synced email | Rejected by gatekeeper, security event logged with `X-Correlation-ID`, metrics incremented |
| **P3** | R1 Rule Engine | R3 Candidate Deduplication | R3 Review Queue | Candidate matching conflicting rules for different job requisitions | Deduplicated candidate linked, `IdentityReviewCase` created (`AMBIGUOUS_JOB_MAPPING`) |
| **P4** | R2 Mailbox Sync | R2 Token Rotation | R6 Metrics | Expired access token encountered during sync triggers rotation | Rotated refresh token stored encrypted, `hireflow.sync.duration` metric recorded |
| **P5** | R4 Frontend SPA | R6 Observability | R6 Actuator | SPA route requested through `CorrelationIdFilter` | `X-Correlation-ID` header returned, Actuator health status remains UP |
| **P6** | R1 Rule Engine | R6 Multi-Tenancy | R1 Rule Preview | Cross-tenant rule preview evaluation | Tenant A cannot view or evaluate Tenant B's recruitment rules (404/empty) |

---

## 6. Real-World Recruiter Scenario Inventory (Tier 4)

| Scenario ID | Name | Recruiter Workflow | Key Invariants Asserted |
|-------------|------|--------------------|-------------------------|
| **S1** | Recruiter Onboarding & Gmail Sync | 1. Recruiter logs in<br>2. Configures rule for "Senior Java Engineer"<br>3. Connects Gmail mailbox<br>4. Inbound resume email arrives<br>5. Sync triggers rule & extraction | Candidate created, application linked to target job, message lifecycle `COMPLETED`, trace ID in headers |
| **S2** | Multi-Candidate Agency Forwarding | 1. Agency forwards batch email with 3 resumes<br>2. Ingestion parses each attachment independently<br>3. 1 new candidate, 1 duplicate candidate, 1 incomplete resume | Exactly 1 new candidate created, 1 duplicate candidate merged, 1 review case created (`MISSING_CONTACT_INFO`) |
| **S3** | Malicious Attachment Defense | 1. Inbound email arrives with XFA XXE PDF and 30MB file<br>2. Pipeline security layers intercept<br>3. Rejection recorded | No external network calls, no OOM, file rejected, Actuator health remains UP |
| **S4** | Ambiguous Job HR Review & Resolution | 1. Resume matches multiple requisitions equally<br>2. Rule engine creates review case<br>3. Recruiter reviews case via Operations API<br>4. Recruiter resolves and assigns job | Case created with `AMBIGUOUS_JOB_MAPPING`, resolved via `POST /api/v1/review-cases/{id}/resolve`, application finalized |
| **S5** | Rate Limit Backoff & Reauth Healing | 1. Sync encounters HTTP 429 rate limit<br>2. Backoff applied<br>3. Subsequent 401 transitions connection to `REAUTH`<br>4. Reauthorization completes<br>5. Sync resumes from checkpoint | No dropped messages, status recovers to `ACTIVE`, incremental sync continues from persisted cursor |

---

## 7. Mock Infrastructure & Network Isolation

### Embedded Mock HTTP Server (`com.sun.net.httpserver.HttpServer`)
- **Listen Address**: `127.0.0.1:0` (ephemeral port assigned by OS).
- **Mock Contexts**:
  - `/mock-oauth/google/token`: Google OAuth 2.0 token exchange & refresh.
  - `/mock-oauth/gmail/users/me/profile`: Google user profile & history ID.
  - `/mock-oauth/gmail/users/me/messages`: Google Gmail message listing & payload.
  - `/mock-oauth/microsoft/token`: Microsoft Entra ID OAuth 2.0 token exchange with RFC 7636 PKCE.
  - `/mock-oauth/graph/v1.0/me`: Microsoft Graph user profile & mailbox address.
  - `/mock-oauth/graph/v1.0/me/mailFolders/inbox/messages`: Microsoft Graph message listing & delta link.
- **Network Invariant**: Zero external internet calls. All HTTP clients in tests are configured with the local mock base URL.

---

## 8. Quality Gates & Coverage Thresholds

| Quality Gate | Threshold | Verification Method |
|--------------|-----------|---------------------|
| **Compilation** | 0 compiler warnings/errors | `./mvnw.cmd test-compile` |
| **Test Execution** | 100% pass of active milestones | `./mvnw.cmd test -Dtest=com.kartgen.hireflow.e2e.*` |
| **Flakiness** | 0 flaky tests across 3 consecutive runs | Automated test execution |
| **Secret Exposure** | 0 tokens/secrets in logs, responses, metrics | Regex assertion on logs & responses |
| **Tenant Isolation** | 100% cross-tenant access rejection | Automated boundary tests |
| **Tika CVE Remediation** | Tika version >= 3.2.2 | Maven dependency tree & XXE test fixtures |

---

## 9. Test Runner Commands

```bash
# Compile all backend test sources
cd hireflow-backend
./mvnw.cmd test-compile

# Run complete E2E test suite (Tiers 1–4 + Master Runner)
./mvnw.cmd test -Dtest=com.kartgen.hireflow.e2e.*

# Run specific test tier
./mvnw.cmd test -Dtest=Tier1FeatureCoverageE2ETest
./mvnw.cmd test -Dtest=Tier2BoundaryCornerCaseE2ETest
./mvnw.cmd test -Dtest=Tier3CrossFeaturePairwiseE2ETest
./mvnw.cmd test -Dtest=Tier4RealWorldScenarioE2ETest

# Run master runner harness
./mvnw.cmd test -Dtest=HireFlowE2EMasterRunnerTest
```
