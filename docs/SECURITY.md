# HireFlow AI — Security Architecture & Hardening Specifications

HireFlow AI enforces defense-in-depth principles across authentication, authorization, multi-tenant isolation, data integrity, cryptographic secret lifecycle, external integration surfaces, and CI pipeline hygiene.

---

## 1. Security Findings & Remediation Inventory

| Severity | Finding | Actual Evidence | Fix | Verification |
|---|---|---|---|---|
| **High** | Extension permission over-privilege | `manifest.json` included wildcard `https://*.google.com/*` which violates least privilege | Restricted `host_permissions` strictly to webmail surfaces (`mail.google.com`, `*.googleusercontent.com`, `outlook.live.com`, `outlook.office.com`, `outlook.office365.com`, `localhost:8080`) | Chrome Extension test suite & manifest validation |
| **High** | Missing Unix build wrapper causing CI test failures | Push to GitHub repository failed CI on `ubuntu-latest` because `hireflow-backend/mvnw` shell script was missing | Generated standard Apache Maven Wrapper script with LF line endings and added to CI | GitHub Actions CI execution |
| **Medium** | Database cross-context thread collisions during full test suite runs | In-memory H2 datasource URL collision in asynchronous background scheduler polling | Isolated database namespace in `TransactionSafeStorageFailureInjectionTest` via `jdbc:h2:mem:hireflow-j2b-test` | Full 282-test Maven build runs without concurrency race failures |
| **Medium** | CRLF line ending conversion risk on Windows Git checkouts | Git warning on Windows converting `mvnw` LF to CRLF, which breaks Linux `/bin/sh` in CI containers | Added root `.gitattributes` enforcing `eol=lf` on shell scripts and `mvnw`, and defensive `sed -i` strip in CI | Verified in CI workflow |
| **Low** | Absence of automated end-to-end frontend integration test | SPA console API contract was verified manually but lacked an automated integration test suite | Implemented `FrontendSpaEndToEndIntegrationTest.java` exercising static delivery, auth, KPIs, candidate drawer, signed URLs, and file streaming | `FrontendSpaEndToEndIntegrationTest` PASSED (1/1) |

---

## 2. Authentication Mechanisms

HireFlow employs three distinct authentication protocols tailored to specific caller types:

### 2.1 Web Application JWT Authentication
- **Mechanism**: Signed JSON Web Tokens (HMAC-SHA256).
- **Claims**: Includes `sub` (User UUID), `org` (Tenant Organization UUID), `email`, `role`, and expiration (`exp`).
- **Enforcement**: Validated per-request by `JwtAuthenticationFilter`. Expired or malformed tokens are immediately rejected with `401 Unauthorized`.
- **Production Guard**: In production profile (`prod`), any attempt to authenticate using hardcoded seed credentials or demo tokens is actively intercepted and rejected by `ProductionDemoCredentialSanitizer`.

### 2.2 Chrome Extension Intake Tokens
- **Mechanism**: High-entropy Bearer tokens prefixed with `hf_ext_live_org_` generated with `SecureRandom` (24 bytes / 192 bits of entropy).
- **Storage**: Extension tokens are never stored in plaintext within the database. The database stores the SHA-256 hash (`extension_token_hash` in `organization_users`).
- **Enforcement**: Handled by `ExtensionTokenAuthFilter`. The filter hashes the incoming bearer token using SHA-256 and matches against active organization memberships.

### 2.3 Webhook HMAC-SHA256 v2 Signatures
- **Mechanism**: Modern HMAC-SHA256 signature scheme preventing tampering, spoofing, and replay attacks.
- **Header Protocol** (exact names, enforced in `WebhookAuthFilter`):
  - `X-HireFlow-Org-Id`: UUID of tenant organization.
  - `X-HireFlow-Key-Id`: Organization credential identifier (`whk_` + 16 hex characters).
  - `X-HireFlow-Timestamp`: Unix epoch seconds at send time.
  - `X-HireFlow-Nonce`: Cryptographically random 16–128 character nonce unique per delivery attempt.
  - `X-HireFlow-Signature`: 64 lowercase hex characters: HMAC-SHA256 over 10-line canonical string (`v2\n{orgId}\n{keyId}\n{timestamp}\n{nonce}\n{method}\n{path}\n{idempotencyKey}\n{contentType}\n{bodySha256}`).
  - `Idempotency-Key`: Stable logical delivery identifier (8–200 characters).
- **Timestamp Drift Protection**: Requests with timestamps diverging more than **300 seconds** (past or future) from server wall clock are immediately rejected with `401 Unauthorized` (in `prod`, replay window must be >= 30 seconds).
- **Timing Attack Resistance**: Signature validation strictly utilizes `MessageDigest.isEqual(expected, actual)`, preventing byte-by-byte timing discrepancy attacks.
- **Idempotency Guarantee**: If an identical webhook payload is received with a known idempotency key, HireFlow short-circuits re-ingestion and returns an identical `200 OK` response without duplicate record creation.

---

## 3. Generated Secrets & Credentials Lifecycle

| Secret Type | Generator Component | Length / Entropy | Storage Representation | Display Policy | Rotation / Revocation |
|---|---|---|---|---|---|
| **Webhook Secrets** | `WebhookCredentialController` | 32 bytes (256 bits) `SecureRandom` (`hf_sec_...`) | SHA-256 hash in `webhook_credentials.secret_hash` | Displayed once at provisioning time; never logged | Revocable via key deactivation in DB; new keys provisioned via `POST /api/v1/webhook-credentials` |
| **Extension Tokens** | `AuthController.generateExtensionToken` | 24 bytes (192 bits) `SecureRandom` | SHA-256 hash in `organization_users.extension_token_hash` | Returned in API response; cached locally in browser extension `chrome.storage.local` | Re-generated anytime via `POST /api/v1/auth/extension-token`, overwriting previous hash |
| **Document View Tokens** | `LocalDiskStorageAdapter` | 256-bit HMAC-SHA256 over `{objectKey}:{expiry}` | URL parameter: `base64url(payload).hmacHex` | Embedded in short-lived URL (TTL: 3600 seconds) | Automatically expires past TTL (returns HTTP 410 Gone); cannot be forged without server storage secret |
| **Mailbox Secrets & OAuth Tokens** | `OAuthMailController` & `MailConnectionController` | User-supplied IMAP App Password or OAuth Refresh/Access Tokens | AES-256-GCM encrypted in `mail_connections.oauth_secret_ref` | Never displayed; omitted entirely from `MailConnectionDto` | Reauthorization, deletion, or token rotation during sync |
| **OAuth State Tokens** | `OAuthMailController` | 32 bytes (256 bits) `SecureRandom` | URL-safe Base64 in `oauth_authorization_attempts.state_token` | Sent in authorize response; logged only as 8-char prefix | Single-use atomic SQL consumption; 10-minute TTL |
| **OAuth PKCE Verifiers** | `OAuthMailController` | 64 bytes (512 bits) `SecureRandom` | Stored in `oauth_authorization_attempts.code_verifier` | Never exposed to client or logged | Single-use; consumed during token exchange |
| **JWT Signing Key** | `HireFlowProperties.jwt.secret` | >= 256 bits HMAC secret key | Environment variable `${JWT_SECRET}` | Never exposed over HTTP or in logs | Rotated via configuration update |
| **Master Crypto Key** | `HireFlowProperties.crypto.secret-key` | 256 bits (64 hex characters) | Environment variable `${CRYPTO_SECRET_KEY}` | Never exposed over HTTP or in logs | Rotated via configuration update |

---

## 4. OAuth 2.0 Mailbox Security Architecture

HireFlow applies strict defense-in-depth security to the Google Gmail and Microsoft 365 OAuth 2.0 integration:

### 4.1 AES-256-GCM Credential Encryption at Rest (`CryptoUtil`)
All OAuth credentials (access tokens, refresh tokens, token expiration epochs) are encrypted before writing to `mail_connections.oauth_secret_ref`:
- **Algorithm**: `AES/GCM/NoPadding` (authenticated symmetric encryption).
- **Key**: 256-bit AES master key supplied via `hireflow.crypto.secret-key` / `CRYPTO_SECRET_KEY` (must be exactly 64 hexadecimal characters).
- **IV & Auth Tag**: A unique 12-byte initialization vector (`IV`) is generated for every encryption using `java.security.SecureRandom`. A 128-bit authentication tag (`TAG_BITS=128`) protects ciphertext integrity and authenticity.
- **Output**: The 12-byte IV is prepended to the ciphertext, Base64-encoded, and stored in the database. Decryption verifies the authentication tag before returning the decrypted plaintext.
- **Plaintext Ban**: Neither access tokens nor refresh tokens are ever stored unencrypted in the database.

### 4.2 Single-Use State Token Entropy & Atomic Consumption
To prevent Cross-Site Request Forgery (CSRF) and OAuth state fixation:
- **Entropy**: 32 cryptographically random bytes (256 bits of entropy) are generated via `SecureRandom` and encoded using URL-safe Base64 without padding.
- **Atomic Replay Prevention**:
  State consumption is executed through an atomic SQL update:
  ```sql
  UPDATE oauth_authorization_attempts a
  SET a.status = 'CONSUMED', a.consumed_at = :now
  WHERE (a.state_token = :token OR a.state = :token)
    AND a.status = 'PENDING'
    AND a.expires_at > :now
  ```
  - Exactly one callback execution can transition the attempt to `CONSUMED`.
  - Concurrent or replayed callbacks receive `updatedRows == 0`, immediately halting token exchange.
- **Time-to-Live (TTL)**: Authorization attempts expire strictly after 10 minutes (`expires_at = created_at + 10m`). Expired attempts cannot be claimed.

### 4.3 Multi-Tenant State & Connection Isolation
- **Tenant Context Binding**: When `/authorize` is called, the attempt record is explicitly stamped with `organization_id` and `user_id` from the caller's authenticated `TenantContext`.
- **Callback Enforcement**: When the provider callback completes token exchange, the mailbox connection is upserted strictly using `attempt.getOrganizationId()`. No provider callback parameter or foreign tenant context can redirect a connected mailbox into another organization.

### 4.4 Bidirectional Origin Validation for PostMessage Bridge
To ensure that authorization codes and connection events cannot be intercepted by malicious origins or malicious parent windows:
- **Server Bridge**: The callback HTML page sends the message strictly to `window.location.origin`:
  `window.opener.postMessage(message, window.location.origin);`
  It never uses the wildcard `*` target origin.
- **Client Listener**: The SPA event listener verifies:
  `if (event.origin !== window.location.origin) return;`
  `if (!event.data || event.data.type !== 'HIREFLOW_OAUTH_CALLBACK') return;`
- Messages from foreign origins, subdomains, or unexpected formats are immediately discarded.

### 4.5 Multi-Context XSS Escaping
Dynamic data rendered in the OAuth callback response is escaped across multiple security contexts:
- **HTML Markup Context**: Values interpolated into HTML elements (such as `mailboxAddress`, `provider`, or `errorMessage`) are escaped with `HtmlUtils.htmlEscape()`, neutralizing `<script>`, `<img>`, `<iframe>`, and tag break-out payloads.
- **JavaScript Context**: Values interpolated into the inline `<script>` block are passed through `escapeJs()`, escaping backslashes, single quotes, double quotes, newlines, carriage returns, and `</` to prevent closing `</script>` tags from breaking out of the script block.
- **Frontend SPA**: The SPA applies strict HTML entity escaping (`esc()`) on all dynamic candidate/mailbox text and uses `safeUrl()` to block `javascript:`, `data:`, and `vbscript:` pseudo-protocol URLs.

### 4.6 Zero Token Exposure Guarantee
- **Log Hygiene**: Authorization codes, client secrets, access tokens, and refresh tokens are strictly omitted from application logs. Log events record only provider, organization UUID, user UUID, and truncated state prefix (first 8 characters).
- **API Hygiene**: `MailConnectionDto` contains zero secret fields (`oauthSecretRef`, `refreshToken`, and `accessToken` are never serialized or returned).
- **DOM & Client Storage Hygiene**: The frontend SPA never stores tokens or secrets in DOM attributes, `localStorage`, or `sessionStorage`.

---

## 5. Multi-Tenant Data Isolation

- **Tenant Scoping**: Every database entity (candidates, jobs, resumes, applications, messages) contains a non-nullable `organization_id` foreign key.
- **Context Holder**: `TenantContext` binds tenant identity to the executing request thread via `ThreadLocal`.
- **Repository Filtering**: All data retrieval and mutation operations are parameterized by `organizationId`. A user belonging to Organization A cannot query, view, modify, or link records belonging to Organization B.
- **Cross-Tenant Guarding**: Explicit integration tests (`AuthTenantIntegrationTest`, `IngestionAuthorizationIntegrationTest`, `UserMembershipRoleAuthorizationTest`) verify that cross-tenant access attempts return `403 Forbidden` or `404 Not Found`.

---

## 6. Storage Safety & Rollback (J-2B Guarantees)

Physical file storage operations coordinate atomically with database transaction boundaries:
- Files are staged and stored prior to database transaction commitment.
- A Spring `TransactionSynchronization` listener monitors transaction completion:
  - If a transaction rolls back due to a database exception, constraint violation, or server error, the listener disposes of the physical staged file automatically.
  - No orphaned files remain in storage upon failure.
- In duplicate ingestion races, winning uploads keep the physical object while losing uploads gracefully abandon duplicate physical storage and reference the winning row.

---

## 7. Production Hardening & Startup Guards

HireFlow enforces strict startup safety across three complementary components:

1. **`StartupProfileGuard` (`ApplicationContextInitializer`)**:
   - Executes before the Spring ApplicationContext refreshes.
   - Requires an explicit active profile (`SPRING_PROFILES_ACTIVE`) matching `local`, `test`, or `prod`.
   - Refuses startup if `prod` and `local` are combined, preventing accidental loading of development seeders.
2. **`ProductionSafetyConfig` (`@Configuration @Profile("prod")`)**:
   - Executes during context initialization under the `prod` profile.
   - Enforces `JWT_SECRET` minimum length (>= 32 chars) and non-default entropy (rejects `"CHANGE-ME"`).
   - Enforces `CRYPTO_SECRET_KEY` format (must be exactly 64 hexadecimal characters, rejecting dev key).
   - Enforces `STORAGE_PROVIDER=s3` and mandates non-blank `S3_BUCKET`.
   - Enforces `CORS_ALLOWED_ORIGINS` non-null, rejecting `localhost` and `127.0.0.1`.
   - Forbids `WEBHOOK_LOCAL_DEV_ENABLED` and `WEBHOOK_LOCAL_DEV_SECRET` in production.
   - Enforces minimum webhook replay window (>= 30 seconds).
3. **`ProductionDemoCredentialSanitizer` (`ApplicationRunner`)**:
   - Runs post-startup under `prod` profile.
   - Neutralizes default seeded demo user passwords (`hr@kartgen.com`).
   - Disables seeded demo organizations (`11111111-1111-1111-1111-111111111111`).
   - Clears demo extension token hashes in the database.

---

## 8. Zero-Secret Git Hygiene

No production credentials, private keys, database passwords, or secret tokens are committed to source control. Configuration files rely on environment variable substitutions (`${DB_HOST}`, `${DB_PORT}`, `${DB_NAME}`, `${DB_PASSWORD}`, `${JWT_SECRET}`, `${CRYPTO_SECRET_KEY}`, `${S3_BUCKET}`, `${AI_API_KEY}`).

---

## 9. Continuous Integration (CI) Security Gates & Automated Pipeline Hygiene

Automated verification is executed on every push and pull request to `main` via `.github/workflows/ci.yml`.

### 9.1 Implemented CI Gates
1. **Backend Compilation & Test Gate (`backend-test`)**:
   - Compiles Java 21 codebase under Temurin JDK 21.
   - Executes all 31 test suites (282 tests) against in-memory H2 (`MODE=PostgreSQL`).
   - Packages executable JAR artifact.
2. **Chrome Extension Security Gate (`extension-test`)**:
   - Executes Node.js 20 test suite (11 tests).
   - Verifies magic byte inspection, resume vs non-resume classification, and privacy gatekeeper blocking of invoices and receipts.
3. **Repository Hygiene & Baseline Secret Scan (`repo-hygiene`)**:
   - **Build Artifact Isolation**: Fails build if untracked build artifacts exist in Git (`hireflow-backend/target`, `hireflow-backend/.jdk`, `hireflow-backend/.local-storage`).
   - **Baseline Regex Secret Scan**: Scans working tree using `git grep -I -E` across three high-risk credential patterns (excluding `.md` and `.txt` documentation):
     - Private keys: `BEGIN (RSA|EC|DSA|OPENSSH|PGP) PRIVATE KEY`
     - AWS Access Key IDs: `AKIA[0-9A-Z]{16}`
     - GitHub Personal Access Tokens: `gh[pousr]_[0-9A-Za-z]{36}`
     *Note*: Any pattern match causes immediate build failure (`exit 1`).
   - **Documentation Completeness**: Enforces presence of 14 required engineering documents.

### 9.2 Tracked Unfinished Work Ledger (Future Security Enhancements)
To maintain strict technical honesty and audit transparency, the following capabilities are explicitly classified as **UNFINISHED WORK**:

1. **PostgreSQL Testing in CI**:
   - *Current State*: CI tests execute exclusively against in-memory H2 in PostgreSQL compatibility mode.
   - *Limitation*: H2 does not replicate PostgreSQL row-locking concurrency, transaction isolation edge cases, or native PostgreSQL JSON operators.
   - *Remediation Plan*: Add a `postgres:16-alpine` service container to `.github/workflows/ci.yml` or implement Testcontainers in the Maven test lifecycle.
2. **Real Dependency Vulnerability Scanning**:
   - *Current State*: Neither Maven (`pom.xml`) nor GitHub Actions CI has an automated dependency vulnerability scanner configured.
   - *Limitation*: Known CVEs in third-party libraries (PDFBox, Tika, Jackson, AWS SDK, JJWT) are not automatically detected in the pipeline.
   - *Remediation Plan*: Integrate `dependency-check-maven` (OWASP Dependency-Check) into `pom.xml` or add a Trivy / Snyk security scan step to CI.
3. **CodeQL Static Application Security Testing (SAST)**:
   - *Current State*: CodeQL and SAST linters (SpotBugs, SonarQube, Checkstyle) are unconfigured in CI.
   - *Limitation*: Static code vulnerabilities and code quality flaws must be identified through manual code reviews.
   - *Remediation Plan*: Add `.github/workflows/codeql.yml` scanning Java and JavaScript/TypeScript codebases.
4. **Comprehensive Secret Detection**:
   - *Current State*: CI performs a basic 3-pattern `git grep` regex scan on the working tree.
   - *Limitation*: Does not scan full git commit history, entropy-based secrets, database connection strings, JWT secret tokens, or generic API keys.
   - *Remediation Plan*: Integrate dedicated secret detection tooling (e.g. `gitleaks/gitleaks-action` or TruffleHog) with historical commit inspection.
5. **Container Image Scanning**:
   - *Current State*: Production container image builds and image vulnerability scans (Trivy / Grype) are pending deployment containerization.
