# HireFlow AI — Security Architecture & Hardening Specifications

HireFlow AI enforces defense-in-depth principles across authentication, authorization, multi-tenant isolation, data integrity, and external integration surfaces.

---

## 1. Authentication Mechanisms

HireFlow employs three distinct authentication protocols tailored to specific caller types:

### 1.1 Web Application JWT Authentication
- **Mechanism**: Signed JSON Web Tokens (HMAC-SHA256).
- **Claims**: Includes `sub` (User UUID), `org` (Tenant Organization UUID), `email`, `role`, and expiration (`exp`).
- **Enforcement**: Validated per-request by `JwtAuthenticationFilter`. Expired or malformed tokens are immediately rejected with `401 Unauthorized`.
- **Production Guard**: In production profile (`prod`), any attempt to authenticate using hardcoded seed credentials or demo tokens is actively intercepted and rejected.

### 1.2 Chrome Extension Intake Tokens
- **Mechanism**: High-entropy Bearer tokens prefixed with `hf_ext_` (e.g. `hf_ext_0123456789abcdef...`).
- **Storage**: Extension tokens are never stored in plaintext within the database. The database stores the SHA-256 hash (`extension_token_hash` in `organization_users`).
- **Enforcement**: Handled by `ExtensionTokenAuthFilter`. The filter hashes the incoming bearer token using SHA-256 and matches against active organization memberships.

### 1.3 Webhook HMAC-SHA256 v2 Signatures
- **Mechanism**: Modern HMAC-SHA256 signature scheme preventing tampering, spoofing, and replay attacks.
- **Header Protocol**:
  - `X-HireFlow-Key-Id`: Unique key identifier (`whk_...`) mapped to the tenant organization.
  - `X-HireFlow-Timestamp`: Unix epoch seconds timestamp.
  - `X-HireFlow-Signature`: Hex-encoded HMAC-SHA256 of canonical payload string:
    `v2:${timestamp}:${requestBody}`
  - `X-HireFlow-Idempotency-Key`: Client-assigned unique delivery UUID.
- **Timestamp Drift Protection**: Requests with timestamps diverging more than **300 seconds** (past or future) from server wall clock are immediately rejected with `401 Unauthorized`.
- **Timing Attack Resistance**: Signature validation strictly utilizes `MessageDigest.isEqual(expected, actual)`, preventing byte-by-byte timing discrepancy attacks.
- **Idempotency Guarantee**: If an identical webhook payload is received with a known idempotency key, HireFlow short-circuits re-ingestion and returns an identical `200 OK` response without duplicate record creation.

---

## 2. Multi-Tenant Data Isolation

- **Tenant Scoping**: Every database entity (candidates, jobs, resumes, applications, messages) contains a non-nullable `organization_id` foreign key.
- **Context Holder**: `TenantContextHolder` binds tenant identity to the executing request thread.
- **Repository Filtering**: All data retrieval and mutation operations are parameterized by `organizationId`. A user belonging to Organization A cannot query, view, modify, or link records belonging to Organization B.
- **Cross-Tenant Guarding**: Explicit integration tests (`AuthTenantIntegrationTest`, `IngestionAuthorizationIntegrationTest`, `UserMembershipRoleAuthorizationTest`) verify that cross-tenant access attempts return `403 Forbidden` or `404 Not Found`.

---

## 3. Storage Safety & Rollback (J-2B Guarantees)

Physical file storage operations coordinate atomically with database transaction boundaries:
- Files are staged and stored prior to database transaction commitment.
- A Spring `TransactionSynchronization` listener monitors transaction completion:
  - If a transaction rolls back due to a database exception, constraint violation, or server error, the listener disposes of the physical staged file automatically.
  - No orphaned files remain in storage upon failure.
- In duplicate ingestion races, winning uploads keep the physical object while losing uploads gracefully abandon duplicate physical storage and reference the winning row.

---

## 4. Production Hardening & Startup Guards

HireFlow implements strict fail-fast startup verifications in `StartupProfileGuard`:
1. **Production Secret Entropy**: Rejects default, weak, or placeholder JWT and webhook secrets. In `prod`, secrets must exceed minimum length and entropy thresholds.
2. **Datasource Validation**: In `prod`, the application refuses to start against in-memory H2 databases; an external PostgreSQL 15+ database is required.
3. **Demo Credential Neutralization**: `ProductionDemoCredentialSanitizer` runs automatically on startup in production, clearing hardcoded demo password hashes and deactivating seeded demo organizations.

---

## 5. Signed Temporary URLs for Documents

Candidate resumes are never exposed via unauthenticated static public endpoints:
- Document downloads use signed, temporary HMAC URLs (`/api/v1/local-s3/{token}`).
- The token payload includes the object storage key, Unix expiration epoch, and cryptographic HMAC signature.
- Tampered tokens return `403 Forbidden`. Expired tokens return `410 Gone`.

---

## 6. Zero-Secret Git Hygiene

No production credentials, private keys, database passwords, or secret tokens are committed to source control. Local configuration files rely on environment variable substitutions (`${DATABASE_URL}`, `${JWT_SECRET}`, `${WEBHOOK_SECRET}`).
