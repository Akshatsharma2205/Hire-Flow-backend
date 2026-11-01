# HireFlow AI — Testing Guide & Verification Suite

HireFlow AI enforces rigorous automated testing covering all layers: unit testing, domain integration, MockMvc web tier tests, embedded HTTP container tests, failure-injection rollback tests, and browser extension client tests.

---

## 1. Test Suite Summary

- **Backend Test Suites**: 30 suites
- **Backend Total Tests**: 281 tests
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
- `StartupProfileGuardTest` (5 tests):
  Verifies fail-fast guards against weak secrets and improper production configuration.

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

### 2.5 Hiring & Lifecycle Management
- `ApplicationControllerIntegrationTest` (1 test):
  End-to-end HTTP MockMvc validation of application creation, listing, stage transitions, and stage history logs.
- `ReviewCaseControllerIntegrationTest` (1 test):
  End-to-end HTTP MockMvc validation of identity collision retrieval and resolution (`MERGED`).
- `LocalS3ControllerIntegrationTest` (1 test):
  Tests signed view URLs, HMAC signature verification, and expired link status codes (`410 GONE`).
- `ApplicationLifecycleIntegrationTest` (14 tests):
  Lifecycle and status progression across all candidate pipeline states.

---

## 3. Running Backend Tests Locally

### Run All Backend Tests:
```powershell
cd hireflow-backend
.\mvnw.cmd test
```
*Expected Execution Time*: ~1m 20s. All 281 tests must pass with 0 failures.

### Run an Individual Test Suite:
```powershell
cd hireflow-backend
.\mvnw.cmd test -Dtest=TransactionSafeStorageFailureInjectionTest
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
✔ Magic byte inspection: detects valid PDF with resume content (0.9794ms)
✔ Magic byte inspection: detects valid DOCX header with resume content (0.1794ms)
✔ Magic byte inspection: rejects non-resume file signature (0.1999ms)
✔ Privacy Gatekeeper: blocks invoices and receipts lacking resume signals (0.9012ms)
✔ Privacy Gatekeeper: allows resume containing incidental invoice word if resume signals present (0.1456ms)
✔ Base64 roundtrip conversion preserves binary bytes exactly (0.9723ms)
✔ File extension support checker handles valid and invalid extensions (0.1988ms)
✔ CSV parser handles empty strings, whitespace and lowercase conversion (0.1794ms)
✔ Smart candidate scorer: high score for matching resume with file and standard keywords (0.5523ms)
✔ Smart candidate scorer: immediate exclusion for trap words (0.2781ms)
✔ Smart candidate scorer: strict custom match requires user keyword (0.1969ms)
ℹ tests 11
ℹ suites 0
ℹ pass 11
ℹ fail 0
```
