# HireFlow AI — Security Architecture & Hardening Specifications

HireFlow AI enforces defense-in-depth principles across authentication, authorization, multi-tenant isolation, data integrity, cryptographic secret lifecycle, and external integration surfaces.

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
- **Timestamp Drift Protection**: Requests with timestamps diverging more than **300 seconds** (past or future) from server wall clock are immediately rejected with `401 Unauthorized`.
- **Timing Attack Resistance**: Signature validation strictly utilizes `MessageDigest.isEqual(expected, actual)`, preventing byte-by-byte timing discrepancy attacks.
- **Idempotency Guarantee**: If an identical webhook payload is received with a known idempotency key, HireFlow short-circuits re-ingestion and returns an identical `200 OK` response without duplicate record creation.

---

## 3. Generated Secrets & Credentials Lifecycle

| Secret Type | Generator Component | Length / Entropy | Storage Representation | Display Policy | Rotation / Revocation |
|---|---|---|---|---|---|
| **Webhook Secrets** | `WebhookCredentialController` | 32 bytes (256 bits) `SecureRandom` (`hf_sec_...`) | SHA-256 hash in `webhook_credentials.secret_hash` | Displayed once at provisioning time; never logged | Revocable via key deactivation in DB; new keys provisioned via `POST /api/v1/webhook-credentials` |
| **Extension Tokens** | `AuthController.generateExtensionToken` | 24 bytes (192 bits) `SecureRandom` | SHA-256 hash in `organization_users.extension_token_hash` | Returned in API response; cached locally in browser extension `chrome.storage.local` | Re-generated anytime via `POST /api/v1/auth/extension-token`, overwriting previous hash |
| **Document View Tokens** | `LocalDiskStorageAdapter` | 256-bit HMAC-SHA256 over `{objectKey}:{expiry}` | URL parameter: `base64url(payload).hmacHex` | Embedded in short-lived URL (TTL: 3600 seconds) | Automatically expires past TTL (returns HTTP 410 Gone); cannot be forged without server storage secret |
| **Mailbox Secrets** | `MailConnectionController` | User-supplied IMAP App Password or OAuth Refresh Token | AES-256-GCM encrypted in `mail_connections.oauth_secret_ref` | Never displayed; masked as `***` in API responses | User can delete or pause connection anytime |
| **JWT Signing Key** | `HireFlowProperties.jwt.secret` | >= 256 bits HMAC secret key | Environment variable `${JWT_SECRET}` | Never exposed over HTTP or in logs | Rotated via configuration update |

---

## 4. Multi-Tenant Data Isolation

- **Tenant Scoping**: Every database entity (candidates, jobs, resumes, applications, messages) contains a non-nullable `organization_id` foreign key.
- **Context Holder**: `TenantContext` binds tenant identity to the executing request thread via `ThreadLocal`.
- **Repository Filtering**: All data retrieval and mutation operations are parameterized by `organizationId`. A user belonging to Organization A cannot query, view, modify, or link records belonging to Organization B.
- **Cross-Tenant Guarding**: Explicit integration tests (`AuthTenantIntegrationTest`, `IngestionAuthorizationIntegrationTest`, `UserMembershipRoleAuthorizationTest`) verify that cross-tenant access attempts return `403 Forbidden` or `404 Not Found`.

---

## 5. Storage Safety & Rollback (J-2B Guarantees)

Physical file storage operations coordinate atomically with database transaction boundaries:
- Files are staged and stored prior to database transaction commitment.
- A Spring `TransactionSynchronization` listener monitors transaction completion:
  - If a transaction rolls back due to a database exception, constraint violation, or server error, the listener disposes of the physical staged file automatically.
  - No orphaned files remain in storage upon failure.
- In duplicate ingestion races, winning uploads keep the physical object while losing uploads gracefully abandon duplicate physical storage and reference the winning row.

---

## 6. Production Hardening & Startup Guards

HireFlow implements strict fail-fast startup verifications in `StartupProfileGuard`:
1. **Production Secret Entropy**: Rejects default, weak, or placeholder JWT and webhook secrets. In `prod`, secrets must exceed minimum length and entropy thresholds.
2. **Datasource Validation**: In `prod`, the application refuses to start against in-memory H2 databases; an external PostgreSQL 15+ database is required.
3. **Demo Credential Neutralization**: `ProductionDemoCredentialSanitizer` runs automatically on startup in production, clearing hardcoded demo password hashes and deactivating seeded demo organizations.

---

## 7. Zero-Secret Git Hygiene

No production credentials, private keys, database passwords, or secret tokens are committed to source control. Configuration files rely on environment variable substitutions (`${DATABASE_URL}`, `${JWT_SECRET}`, `${WEBHOOK_SECRET}`).
