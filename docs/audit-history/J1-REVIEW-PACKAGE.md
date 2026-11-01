# J-1 Review Package — Close the Ingestion Authorization Gap

Generated: 2026-10-31 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repo; diff produced against file snapshot)

> **For reviewer (GLM-5.3-Flash):** this is a read-only review. Do not modify any files.
> Exact machine-generated diff: [`J1-EXACT-DIFF.patch`](J1-EXACT-DIFF.patch) (484 lines, SHA256 `10EDF422C5216A223734C9501AF95E2A98912BF8A197AEDA604B35A5C93584D1`).

---

## 1. Audit findings verified against current code (before any edit)

| Endpoint | Audit claim | Verified state | Action |
|---|---|---|---|
| `POST /api/v1/auth/extension-token` | no role restriction | Confirmed — no `@PreAuthorize`; reachable by any authenticated role incl. `VIEWER` | `@PreAuthorize` + membership/org-status hardening |
| `POST /api/v1/uploads/direct` | no role restriction | Confirmed — no `@PreAuthorize` | `@PreAuthorize` |
| `POST /api/v1/uploads/extension-intake` | no role restriction | Confirmed — no `@PreAuthorize` | `@PreAuthorize` |
| `POST /api/v1/uploads/check-hashes` | no role restriction | Confirmed — no `@PreAuthorize` | `@PreAuthorize` |

Confirmed supporting facts: `SecurityConfig` has `@EnableMethodSecurity` and `anyRequest().authenticated()`; `RoleNormalizer.SUPPORTED_ROLES = {OWNER, ADMIN, RECRUITER, VIEWER}` (so `MANAGER`/`HIRING_MANAGER` in other controllers' annotations are unreachable); `TenantContext` is populated only by `JwtAuthenticationFilter` / `ExtensionTokenAuthFilter` after DB-verified membership + active-org checks — no request header feeds it.

## 2. Ingestion route inventory (all candidate-ingestion entry points)

| Route | Creates message/attachment/resume-file/processing-job? | Authorization | Decision |
|---|---|---|---|
| `/api/v1/uploads/direct` | Yes (`ingestDirectUpload`) | **was open** | Guarded (J-1) |
| `/api/v1/uploads/extension-intake` | Yes (`ingestFromExtension`) | **was open** | Guarded (J-1) |
| `/api/v1/uploads/check-hashes` | No writes, but task-mandated deny for VIEWER | **was open** | Guarded (J-1) |
| `/api/v1/auth/extension-token` | Mints extension credentials | **was open** | Guarded + hardened (J-1) |
| `/api/v1/mail-webhook/inbound` (multipart + JSON) | Yes (`ingestFromWebhook`) | `WebhookAuthFilter` shared secret + org header | **Untouched — J-2 scope** |
| `/api/v1/mail-connections` (POST) / `/{id}/sync-now` | Triggers IMAP/OAuth sync → `ingestMailAttachment` | Already `hasAnyRole('OWNER','ADMIN')` / `('OWNER','ADMIN','RECRUITER','MANAGER')` | Untouched (already restricted) |
| Worker (`WorkerScheduler`/`ProcessingWorker`) | Internal `PARSE_RESUME`/`RECOMPUTE_MATCHES` execution | Internal, not HTTP | Untouched |
| `POST /api/v1/jobs`, `/versions`, `/status`; `POST /api/v1/applications`; `PATCH` stage; `POST /{id}/resolve` | Not resume ingestion | Already role-restricted | Untouched |
| `/api/v1/local-s3/**` (permitAll) | **Read-only** `GET /{token}` with HMAC-signed, expiring token; no upload/write endpoint | n/a | Untouched (no ingestion capability) |

Grep-verified: `storagePort.store`, `resumeFileRepo.save`, `ProcessingJob.builder().save()` occur only inside `MessageIngestionService` (plus `JobService` match-recompute jobs behind role-guarded `JobController`). No alternative HTTP route stores a resume file or enqueues a parse job.

## 3. Authorization policy implemented

Enforced server-side via `@PreAuthorize` (method security enabled and proven active by tests). Roles come from the **authoritative DB membership** resolved by the auth filters, never from client headers.

| Operation | OWNER | ADMIN | RECRUITER | VIEWER | Anonymous |
|---|---|---|---|---|---|
| Direct upload | Allow | Allow | Allow | **403** | **401** |
| Extension intake | Allow | Allow | Allow | **403** | **401** |
| Hash pre-check | Allow | Allow | Allow | **403** | **401** |
| Generate personal extension token | Allow | Allow | Allow | **403** | **401** |
| Read candidate records | unchanged | unchanged | unchanged | unchanged (200) | 401 |

### `generateExtensionToken` hardening (AuthController)
- User + org derived **only** from verified `TenantContext`; no request body/headers consulted.
- Membership must exist and be `ACTIVE`, and the organization must be `ACTIVE`, else **403** (previously an `IllegalStateException` → 500 if the membership row was missing).
- Token is bound to the caller's own membership row (`extensionTokenHash` on that row) — a member of org A cannot mint for org B.
- Existing SHA-256 hashing, one-token-per-membership overwrite (revocation of the previous token), and raw-token-in-response-only behavior preserved; storage design unchanged.
- Log line still emits only `user=` / `org=` IDs — raw token is never logged.
- All 403s return the standard `ApiError` JSON via the existing `accessDeniedHandler`.

## 4. Changed files (4; nothing else differs from snapshot)

| File | Change | SHA256 (post-change) |
|---|---|---|
| `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/DirectUploadController.java` | +import, +`@PreAuthorize` | `7B0B5535EF89244205789A0892B5FEC5DE97E521D27FC1902DD95E4D5A8D6FD8` |
| `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/ExtensionIntakeController.java` | +import, +`@PreAuthorize` ×2 | `0F976F14D148D0323500F62A0BAFEA3881F19A3885EDBBB3F33CFB00DF1F6806` |
| `hireflow-backend/src/main/java/com/kartgen/hireflow/security/AuthController.java` | +`@PreAuthorize`, +membership/org-status checks (403 instead of 500) | `DD90C6BBEAD0358590C885DF0226B5942BABD9CF2AE8D287B20CAF04E43E921E` |
| `hireflow-backend/src/test/java/com/kartgen/hireflow/IngestionAuthorizationIntegrationTest.java` | **new** (397 lines) | `CFB43E07E05BAC0DE3AB1A3C1BC0C6266E71F801E1E2CB68099D51517444E678` |

Pre-change snapshot: `C:\Users\ragha\AppData\Local\Temp\opencode\j1-snapshot-20261031-185406` (full `src` tree + `pom.xml`). `git diff --no-index --stat` against it shows exactly these 4 files (`4 files changed, 415 insertions(+), 2 deletions(-)`).

## 5. Exact diff — production hunks

```diff
--- a/src/main/java/com/kartgen/hireflow/mail/channel/DirectUploadController.java
+++ b/src/main/java/com/kartgen/hireflow/mail/channel/DirectUploadController.java
@@ -5,6 +5,7 @@
 import org.springframework.http.ResponseEntity;
+import org.springframework.security.access.prepost.PreAuthorize;
 import org.springframework.web.bind.annotation.*;
@@ -24,6 +25,7 @@
     @PostMapping("/direct")
+    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
     public ResponseEntity<?> directUpload(

--- a/src/main/java/com/kartgen/hireflow/mail/channel/ExtensionIntakeController.java
+++ b/src/main/java/com/kartgen/hireflow/mail/channel/ExtensionIntakeController.java
@@ -6,6 +6,7 @@
 import org.springframework.http.ResponseEntity;
+import org.springframework.security.access.prepost.PreAuthorize;
 import org.springframework.web.bind.annotation.*;
@@ -24,6 +25,7 @@
     @PostMapping("/check-hashes")
+    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
     public ResponseEntity<?> checkHashes(...)
@@ -41,6 +43,7 @@
     @PostMapping("/extension-intake")
+    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
     public ResponseEntity<?> extensionIntake(

--- a/src/main/java/com/kartgen/hireflow/security/AuthController.java
+++ b/src/main/java/com/kartgen/hireflow/security/AuthController.java
@@ -84,6 +84,7 @@
     @PostMapping("/extension-token")
+    @PreAuthorize("hasAnyRole('OWNER','ADMIN','RECRUITER')")
     public ResponseEntity<?> generateExtensionToken() {
@@ -91,8 +92,18 @@
-        var orgUser = orgUserRepo.findByOrganizationIdAndUserId(orgId, userId)
-                .orElseThrow(() -> new IllegalStateException("Organization user not found"));
+        var orgUser = orgUserRepo.findByOrganizationIdAndUserId(orgId, userId).orElse(null);
+        if (orgUser == null || !"ACTIVE".equals(orgUser.getStatus())) {
+            return ResponseEntity.status(403)
+                    .body(Map.of("error", "Active organization membership required"));
+        }
+
+        boolean organizationActive = organizationRepo.findById(orgId)
+                .filter(org -> "ACTIVE".equals(org.getStatus()))
+                .isPresent();
+        if (!organizationActive) {
+            return ResponseEntity.status(403).body(Map.of("error", "Active organization required"));
+        }
```

(Full verbatim patch including the new 397-line test file: `J1-EXACT-DIFF.patch`.)

## 6. New regression tests — `IngestionAuthorizationIntegrationTest`

Boots the real application (`@SpringBootTest` + `@AutoConfigureMockMvc`, profile `test`), drives the real filter chain, and asserts against real endpoints and the H2 database. All fixtures are synthetic per-test users/orgs/memberships; resumes are generated PDFs with unique content (no real candidate data, no real mailbox credentials).

| # (task §5) | Test method | Assertion |
|---|---|---|
| 1 | `anonymousDirectUploadIsRejected` | 401 `Unauthorized` |
| 2 | `viewerDirectUploadIsRejected` | 403 `Forbidden`; inbound-message count unchanged |
| 3 | `viewerExtensionIntakeIsRejected` | 403; message count unchanged |
| 4 | `viewerHashPreCheckIsRejected` | 403 |
| 5 | `viewerExtensionTokenGenerationIsRejected` | 403; membership `extensionTokenHash` stays null |
| 6 | `recruiterDirectUploadIsAllowed` | 202 `QUEUED`, acceptedCount 1; +1 message in **own** org; attachment resolvable only in own org |
| 7 | `recruiterExtensionIntakeIsAllowed` | 202; +1 message in own org |
| 8 | `recruiterGeneratesTokenOnlyForOwnMembership` | 200; token prefix = own org; stored hash = SHA-256(token) on **own** membership; foreign membership hash stays null (spoof headers `X-Tenant-ID` / `X-Organization-Id` / `X-Org-Id` sent) |
| 9 | `adminDirectUploadIsAllowed` + `ownerDirectUploadIsAllowed` | 202; +1 message each (owner via seeded `hr@kartgen.com` login) |
| 10 | `crossTenantOrganizationHeadersCannotRedirectDirectUpload` | 202 but write lands in caller's org; foreign org count unchanged; attachment not found under foreign org |
| 11 | `viewerCandidateReadsRemainUnchanged` | `GET /api/v1/candidates` 200 with `content` array |
| 12 | `existingRecruiterExtensionTokenStillAuthenticatesAndIngests` | Pre-seeded `hf_ext_` token reads candidates (200) **and** ingests via extension-intake (202) |
| extra | `existingViewerExtensionTokenCanReadButCannotIngestOrMint` | Legacy VIEWER token: reads OK, extension-intake/check-hashes/extension-token all 403 |

## 7. Test evidence (actual artifacts)

| Run | Command (cwd `hireflow-backend`) | Exit code | Result |
|---|---|---|---|
| Baseline (pre-change) | `.\mvnw.cmd -q -o test -DskipTests=false` | 0 | 12 suites / 131 tests / 0 failures / 0 errors / 0 skipped |
| Focused (post-change) | `.\mvnw.cmd -o -Dtest=IngestionAuthorizationIntegrationTest test -DskipTests=false` | 0 | 14/14 passed (32.2 s) |
| Full (post-change) | `.\mvnw.cmd -q -o test -DskipTests=false` | 0 | **13 suites / 145 tests / 0 failures / 0 errors / 0 skipped** (Surefire XML, written 2026-10-31 18:56:25–18:57:05) |

Surefire XML: `hireflow-backend/target/surefire-reports/TEST-*.xml` — parsed programmatically; totals above are from the XML attributes, not console output. Delta vs baseline = exactly the new suite (+1 suite, +14 tests). Console warnings seen during the full run (simulated DB failure, invalid-role rejections, demo-JWT prod rejection) are expected outputs of pre-existing security tests.

## 8. Limitations & residual items (for the record)

- **H2 only.** All runs use the `test` profile (H2 `MODE=PostgreSQL`). No PostgreSQL behavior is claimed; the SQL used is JPA-derived and dialect-neutral, but production parity is unverified here.
- **Webhook ingestion untouched** (`/api/v1/mail-webhook/inbound` remains permit-all behind `WebhookAuthFilter`). Its org-header trust model is explicitly J-2.
- **Role-model mismatch recorded, not fixed:** existing annotations elsewhere still list `MANAGER` (`MailConnectionController.sync-now`, `JobController`, `ApplicationController`) and `HIRING_MANAGER` (`ReviewCaseController`), but `RoleNormalizer` only supports `OWNER/ADMIN/RECRUITER/VIEWER`, so those entries are dead letters. Per task instructions they were **not** modified — product-owner decision needed.
- **Legacy VIEWER extension tokens** minted before this change remain valid credentials, but are now blocked from all four ingestion/credential endpoints by `@PreAuthorize` (covered by `existingViewerExtensionTokenCanReadButCannotIngestOrMint`). If product wants them invalidated outright, that is a new decision (token revocation redesign is out of scope).
- **Cross-tenant test semantics:** spoofed org headers are *ignored* (write stays in the caller's verified tenant) rather than the request being rejected with an error — this is the correct behavior since headers are never consulted for authorization.
- **`/api/v1/auth/**` remains permitAll at the URL layer** (login must be public). The extension-token endpoint enforces authentication via `TenantContext` (401 when absent) and authorization via `@PreAuthorize` (403 for VIEWER) — verified by tests. URL-level tightening was intentionally avoided so as not to break login.
- Tests exercise the MockMvc filter chain, not a real servlet container; no frontend changes were made or verified.

## 9. Scope compliance

Not modified: webhook replay protection, worker leases, IMAP SSRF handling, OAuth flows, Flyway migrations, extraction, matching, frontend, extension frontend files, `SecurityConfig`, filters, repositories, `MessageIngestionService`, webhook/IMAP/OAuth controllers, `pom.xml`. No test was disabled, weakened, or fabricated; all results above are reproducible from the recorded commands.

*Review artifacts (`J1-REVIEW-PACKAGE.md`, `J1-EXACT-DIFF.patch`) are documentation only and can be deleted after review.*
