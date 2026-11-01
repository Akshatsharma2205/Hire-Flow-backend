# HireFlow AI — Testing Guide & Verification Suite

HireFlow AI enforces rigorous automated testing covering all architectural layers: domain unit testing, MockMvc web tier tests, embedded HTTP container tests, failure-injection rollback tests, browser extension client tests, and continuous integration pipeline hygiene.

---

## 1. Test Suite Summary

- **Backend Test Suites**: 31 suites
- **Backend Total Tests**: 282 tests
- **Backend Test Result**: **0 Failures, 0 Errors, 0 Skipped (100% Pass)**
- **Extension Test Suite**: 1 suite (11 tests)
- **Extension Test Result**: **0 Failures, 0 Errors, 0 Skipped (100% Pass)**

---

## 2. Test Layer Catalog

### 2.1 Storage & Rollback Failure-Injection (`com.kartgen.hireflow.storage`)
- `TransactionSafeStorageFailureInjectionTest` (11 tests):
  Validates storage-to-database atomicity under simulated database crashes, H2 commit triggers, and duplicate races. Verifies that orphaned physical files are cleaned up on rollback.
- `LocalDiskStorageAdapterTest` (12 tests):
  Validates directory structure, SHA-256 integrity checks, path traversal defenses, signed URL generation, and error handling.

### 2.2 Security & Authentication (`com.kartgen.hireflow`)
- `AuthTenantIntegrationTest` (10 tests):
  Tests tenant isolation, invalid credentials, JWT issuance, and role access.
- `JwtRevocationAndTenantStatusEnforcementTest` (10 tests):
  Verifies token revocation, suspended tenant handling, and deactivated user rejection.
- `UserMembershipRoleAuthorizationTest` (9 tests):
  Verifies role permission matrices (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`).
- `ProductionDemoCredentialSanitizerTest` (4 tests):
  Tests startup neutralization of seed credentials in production profiles.
- `StartupProfileGuardTest` (6 tests):
  Verifies fail-fast profile selection guards against missing profiles, unsupported profiles, mixed `prod`+`local` profiles, and verifies `spring.factories` registration.
- `ProductionSafetyConfigTest` (3 tests):
  Verifies fail-fast runtime production property checks against enabled local webhook seeding, local dev secrets, and confirms acceptance of valid production configuration.

### 2.3 Webhook Security & Idempotency (`com.kartgen.hireflow`)
- `WebhookAuthIntegrationTest` (18 tests):
  Tests HMAC-SHA256 signature verification, timing attack resistance, timestamp drift, and payload validation.
- `WebhookSizeLimitIntegrationTest` (11 tests):
  Tests payload size caps and non-resume attachment handling.
- `WebhookEmbeddedContainerIntegrationTest` (6 tests):
  Boots real embedded servlet container and executes raw byte HTTP requests over network socket.
- `LocalWebhookDevCredentialSeederTest` (5 tests):
  Verifies local development credential provisioning.

### 2.4 Parsing, Extraction & Matching
- `LocalSmartHeuristicExtractorTest` (10 tests):
  Validates PDFBox and Apache Tika resume parsing, skill alias resolution, and edge cases (corrupt PDF, empty text).
- `MatchServiceTest` (5 tests):
  Tests deterministic candidate-to-job match scoring algorithms.

### 2.5 Hiring, Identity & Lifecycle Management
- `ApplicationControllerIntegrationTest` (1 test):
  End-to-end HTTP MockMvc validation of application creation, listing, stage transitions, and stage history logs.
- `ReviewCaseControllerIntegrationTest` (1 test):
  End-to-end HTTP MockMvc validation of identity collision retrieval and resolution (`MERGED`).
- `LocalS3ControllerIntegrationTest` (1 test):
  Tests signed view URLs, HMAC signature verification, and expired link status codes (`410 GONE`).
- `ApplicationLifecycleIntegrationTest` (14 tests):
  Lifecycle and status progression across all candidate pipeline states.

### 2.6 Frontend SPA End-to-End Testing
- `FrontendSpaEndToEndIntegrationTest` (1 test):
  Real end-to-end integration test covering the complete Single Page Application user journey: static HTML delivery, auto-login, KPI queries, extension token generation, SHA-256 pre-flight checks, direct upload, candidate table pagination, candidate drawer, application creation, stage update to `SCREENING`, signed HMAC URL generation, and inline document byte streaming.

---

## 3. Running Backend Tests Locally

### Run All Backend Tests:
```powershell
cd hireflow-backend
# Windows
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```
*Expected Execution Time*: ~1m 15s. All 282 tests pass with 0 failures, 0 errors, 0 skipped.

### Run an Individual Test Suite:
```powershell
cd hireflow-backend
.\mvnw.cmd test -Dtest=FrontendSpaEndToEndIntegrationTest
```

### Run Webhook Security Tests:
```powershell
cd hireflow-backend
.\mvnw.cmd test -Dtest='Webhook*IntegrationTest'
```

---

## 4. Running Chrome Extension Tests

The extension test suite validates magic byte identification, invoice trap keyword filtering, Base64 encoding roundtrips, and candidate scoring algorithms using Node.js built-in test runner.

```powershell
node --test hireflow-extension/test/extension.test.js
```

### Output:
```
✔ Magic byte inspection: detects valid PDF with resume content (1.0ms)
✔ Magic byte inspection: detects valid DOCX header with resume content (0.2ms)
✔ Magic byte inspection: rejects non-resume file signature (0.2ms)
✔ Privacy Gatekeeper: blocks invoices and receipts lacking resume signals (1.0ms)
✔ Privacy Gatekeeper: allows resume containing incidental invoice word if resume signals present (0.1ms)
✔ Base64 roundtrip conversion preserves binary bytes exactly (0.8ms)
✔ File extension support checker handles valid and invalid extensions (0.2ms)
✔ CSV parser handles empty strings, whitespace and lowercase conversion (0.2ms)
✔ Smart candidate scorer: high score for matching resume with file and standard keywords (0.5ms)
✔ Smart candidate scorer: immediate exclusion for trap words (0.3ms)
✔ Smart candidate scorer: strict custom match requires user keyword (0.2ms)
ℹ tests 11
ℹ pass 11
ℹ fail 0
```

---

## 5. Continuous Integration (CI) Pipeline & Verification Gates

Automated continuous integration is executed on every push and pull request to `main` via `.github/workflows/ci.yml`.

### 5.1 CI Architecture & Automated Jobs

| Job Name | Runner OS | Target | Command / Execution | Criteria |
|---|---|---|---|---|
| **`backend-test`** | `ubuntu-latest` | Backend (Java 21) | `sed -i 's/\r$//' mvnw \|\| true; chmod +x mvnw; ./mvnw test --batch-mode`<br>`./mvnw package -DskipTests --batch-mode` | 31 test suites, 282 tests must pass with 0 failures. Executable JAR artifact must build cleanly. |
| **`extension-test`** | `ubuntu-latest` | Extension (Node.js 20) | `node --test hireflow-extension/test/extension.test.js` | 1 suite, 11 tests must pass with 0 failures. |
| **`repo-hygiene`** | `ubuntu-latest` | Repository Hygiene | 1. Verify `target/`, `.jdk/`, `.local-storage/` directories are not tracked in Git.<br>2. Run `git grep -I -E` scan for private keys, AWS access key IDs, and GitHub tokens.<br>3. Verify presence of 14 core documentation files. | Zero untracked build artifacts, zero pattern matches for exposed secrets, all 14 docs present. |

### 5.2 Test Database Architecture: In-Memory H2
All backend automated tests in CI currently execute against an in-memory **H2 database** configured in PostgreSQL compatibility mode (`jdbc:h2:mem:...;MODE=PostgreSQL`). While H2 provides fast execution for unit and integration testing, it does not provide 100% behavioral parity with PostgreSQL for table locks, transaction concurrency semantics, or native PostgreSQL JSON operators.

### 5.3 Tracked Unfinished Testing Gates (Unfinished Work Ledger)
In accordance with engineering integrity standards, the following capabilities are explicitly classified as **UNFINISHED WORK**:

1. **PostgreSQL Testing in CI**:
   - Automated tests in CI do not currently run against a real PostgreSQL instance.
   - *Planned Enhancement*: Introduce a `postgres:16-alpine` service container into `.github/workflows/ci.yml` or integrate Testcontainers with `@ServiceConnection` in Maven.
2. **Real Dependency Vulnerability Scanning**:
   - Neither Maven nor GitHub Actions currently runs an automated CVE scanner.
   - *Planned Enhancement*: Integrate `dependency-check-maven` (OWASP Dependency-Check) into `pom.xml` or add Snyk / Trivy scanning to CI.
3. **CodeQL Static Application Security Testing (SAST)**:
   - Automated static code security analysis is unconfigured in CI.
   - *Planned Enhancement*: Create `.github/workflows/codeql.yml` scanning Java and JavaScript/TypeScript.
4. **Comprehensive Secret Scanning**:
   - CI executes a 3-pattern `git grep` regex scan in the working tree, which is a baseline heuristic rather than comprehensive secret scanning.
   - *Planned Enhancement*: Add `gitleaks` or TruffleHog to scan full commit history and entropy-based secrets.
5. **Container Image Vulnerability Scanning**:
   - Production container builds and vulnerability scanning are pending production containerization.
