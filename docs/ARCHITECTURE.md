# HireFlow AI — Architecture & Technical Blueprint

## 1. System Topology & Overview

HireFlow AI is an enterprise-grade applicant tracking and intelligent candidate ingestion platform engineered as a clean **Modular Monolith** in **Java 21** using **Spring Boot 3.3.5**. It processes inbound resumes from multiple channels (webmail Chrome extension, direct candidate upload, programmatic email forwarding webhooks), extracts structured profile data using Apache Tika/PDFBox heuristics, resolves candidate identities with deduplication safeguards, and scores candidate capabilities against active job requisitions.

```
                           +-----------------------------------------------+
                           |          Ingestion Channels                   |
                           +-----------------------------------------------+
                             |                     |                     |
                   [Browser Extension]    [Direct SPA Upload]   [Mail Inbound Webhook]
                     (Bearer Token)          (JWT Bearer)         (HMAC-SHA256 v2)
                             |                     |                     |
                             v                     v                     v
                           +-----------------------------------------------+
                           |           Spring Security Filter Chain        |
                           |  - JwtAuthenticationFilter                    |
                           |  - ExtensionTokenAuthFilter                   |
                           |  - WebhookAuthFilter                          |
                           +-----------------------------------------------+
                                                   |
                                                   v
                           +-----------------------------------------------+
                           |          Core Domain Services                 |
                           |  - MessageIngestionService                    |
                           |  - CandidateIdentityService                   |
                           |  - LivingProfileService                       |
                           |  - MatchService                               |
                           |  - ApplicationService                         |
                           +-----------------------------------------------+
                             |                     |                     |
                             v                     v                     v
                    +----------------+    +----------------+    +----------------+
                    |  StoragePort   |    |  Spring Data   |    |  Asynchronous  |
                    | (Local Disk /  |    |  Repositories  |    |  Worker Queue  |
                    |   S3 Adapter)  |    |  (JPA / H2/PG) |    | (Virtual Th.)  |
                    +----------------+    +----------------+    +----------------+
```

---

## 2. Core Modules & Packaging Structure

The backend application (`hireflow-backend/src/main/java/com/kartgen/hireflow`) is partitioned by domain bounded contexts:

### 2.1 `tenant`
- Manages multi-tenant boundaries (`Organization`, `User`, `OrganizationUser`).
- Enforces strict tenant separation across all database operations. Every domain entity is partitioned by `organization_id`.
- Tenant context is resolved per-request in the security filters (JwtAuthenticationFilter, ExtensionTokenAuthFilter, WebhookAuthFilter) and bound to the thread via TenantContext (ThreadLocal<UUID>), cleared deterministically in filter finally blocks.

### 2.2 `security`
- Stateless authentication and authorization via signed JSON Web Tokens (HMAC-SHA256) and Bearer extension tokens (`hf_ext_...`).
- Role-Based Access Control (RBAC) supporting `OWNER`, `ADMIN`, `RECRUITER`, and `VIEWER`.
- `ProductionDemoCredentialSanitizer` automatically neutralizes demo seed passwords and disables demo API tokens when running under `prod` profile.
- Strict startup guards (`StartupProfileGuard`) verifying secret entropy and rejecting placeholder keys in production.

### 2.3 `mail` & `mail.channel`
- Ingestion endpoints:
  - `ForwardingWebhookController` (`/api/v1/mail-webhook/inbound`): Receives inbound email forwarding payloads with signature validation and deduplication.
  - `DirectUploadController` (`/api/v1/uploads/direct`): Allows recruiters to upload candidate resumes directly.
  - `ExtensionIntakeController` (`/api/v1/uploads/extension-intake`): Handles candidate captures streamed from the Chrome extension with SHA-256 pre-flight hash verification (`/api/v1/uploads/check-hashes`).
  - `OAuthMailController` (`/api/v1/mail-connections/oauth`): Handles OAuth 2.0 authorization initiation (`/{provider}/authorize`) and provider callbacks (`/{provider}/callback`) for Google Gmail and Microsoft 365.
  - `MailConnectionController` (`/api/v1/mail-connections`): Manages mailbox connection lifecycle (listing, manual sync trigger, pause/deletion) with zero secret leakage.
- Core Sync Adapters:
  - `OAuthMailSyncAdapter`: Manages background and on-demand synchronization for `GMAIL_OAUTH` and `MS_GRAPH_OAUTH` mailboxes, cursor management (Gmail history ID / MS Graph delta tokens), attachment extraction, and automatic token refresh/rotation.
  - `UniversalImapSyncService`: Connects to standard IMAP mailboxes using encrypted app passwords.
- `MessageIngestionService`: Orchestrates atomic persistence of raw messages, extraction of attachments, SHA-256 checksum computation, file storage synchronization, and asynchronous job queuing.

### 2.4 `document`
- Storage abstraction via `StoragePort` interface decoupling the application from the underlying storage mechanism.
- `LocalDiskStorageAdapter`: File system implementation utilizing content-addressable storage paths partitioned by tenant ID, SHA-256 subdirectories, and random UUID keys:
  `${local-base-path}/{orgId}/{sha256[0..63]}/{uuid}.{ext}`
- Signed temporary download URLs (`LocalS3Controller` at `/api/v1/local-s3/{token}`) validated with HMAC-SHA256 tokens and time-to-live expirations.

### 2.5 `extraction`
- `LocalSmartHeuristicExtractor`: Deterministic, privacy-preserving resume parsing engine powered by Apache Tika and Apache PDFBox.
- Extracts candidate name, email, phone number, work experience history, education records, and skills from PDF, DOCX, and TXT files without requiring external cloud AI calls.
- Skill canonicalization utilizing a 55-keyword alias normalization dictionary.

### 2.6 `identity`
- `CandidateIdentityService`: Prevents duplicate records across ingestion channels.
- Employs deterministic matching rules:
  1. Exact normalized email address match.
  2. Exact normalized phone number match.
  3. Linked identity handles (LinkedIn, GitHub).
- Discrepancies or ambiguous identities automatically generate an `IdentityReviewCase` requiring human recruiter adjudication (`/api/v1/review-cases`).

### 2.7 `profile`
- `LivingProfileService`: Maintains versioned snapshots of candidate profiles (`CandidateProfileVersion`, `ResumeVersion`).
- Preserves complete document provenance and skill proficiency tags across multiple resume submissions.

### 2.8 `jobs`
- `JobService` and `JobController`: Comprehensive job requisition management with version history (`JobVersion`) and required/optional skill definitions (`JobSkillRequirement`).

### 2.9 `matching`
- `MatchService`: High-performance deterministic scoring engine computing candidate-to-job fit.
- Scores candidates from 0 to 100 based on weighted skill overlap, requirement tiers (mandatory vs bonus), and experience level alignment.

### 2.10 `hiring`
- `ApplicationService` & `ApplicationController`: Manages candidate pipelines across standard stages (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`).
- Records stage transition audit logs (`ApplicationStageHistory`) and recruiter feedback (`CandidateNote`).

### 2.11 `webhook`
- Comprehensive HMAC-SHA256 v2 webhook security subsystem.
- Constant-time signature verification (`MessageDigest.isEqual`), replay protection via timestamp drift window (default 300s) and delivery idempotency ledger (`WebhookDelivery`).

### 2.12 `ops`
- Background asynchronous processing with `WorkerScheduler` and `ProcessingWorker`.
- Atomic job claiming with conditional SQL updates, exponential backoff retries, and dead-letter queueing for poisoned messages.
- Audit event ledger (`AuditEvent`) and transactional outbox (`OutboxEvent`).

---

## 3. Google & Microsoft OAuth 2.0 Mailbox Integration Architecture

HireFlow implements a secure, server-orchestrated OAuth 2.0 authorization-code flow with PKCE that eliminates manual credential provisioning and prevents secret leakage to frontend browser environments.

```
Recruiter Browser (SPA)               HireFlow Backend                      OAuth Provider (Google/MS)
      │                                      │                                          │
      │ 1. GET /oauth/{provider}/authorize   │                                          │
      ├─────────────────────────────────────>│                                          │
      │                                      │ 2. Generate 32B state token              │
      │                                      │    Generate PKCE S256 (Microsoft)        │
      │                                      │    Persist OAuthAuthorizationAttempt     │
      │ 3. 200 OK (authUrl, stateToken)      │                                          │
      │<─────────────────────────────────────┤                                          │
      │                                                                                 │
      │ 4. Open Popup window with authUrl                                               │
      ├────────────────────────────────────────────────────────────────────────────────>│
      │                                      │ 5. User signs in & grants consent        │
      │ 6. Redirect to /oauth/{prov}/callback│<─────────────────────────────────────────┤
      ├─────────────────────────────────────>│                                          │
      │                                      │ 7. Atomic SQL Claim (consumePending)     │
      │                                      │    - Replay check, TTL check             │
      │                                      │ 8. Server-side token exchange (POST)     ├────────┐
      │                                      │<─────────────────────────────────────────┴────────┘
      │                                      │ 9. Profile discovery (fetch mailbox email)
      │                                      │ 10. Refresh token preservation check     │
      │                                      │ 11. AES-256-GCM credential encryption    │
      │                                      │ 12. Upsert MailConnection entity         │
      │ 13. Render HTML postMessage bridge   │                                          │
      │<─────────────────────────────────────┤                                          │
      │                                                                                 │
      │ 14. postMessage({ type: 'HIREFLOW_OAUTH_CALLBACK', status: 'SUCCESS' }, origin) │
      │ 15. Popup window.close(); SPA reloads mailbox list & displays success toast     │
```

### 3.1 Authorization-Code Flow with PKCE S256
- **Initiation (`GET /api/v1/mail-connections/oauth/{provider}/authorize`)**:
  - Enforces `OWNER` or `ADMIN` RBAC authorization via Bearer JWT.
  - Dynamically constructs the provider authorization URL with required parameters:
    - **Google Workspace**: `access_type=offline`, `prompt=consent`, `response_type=code`, `scope=https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/userinfo.email`.
    - **Microsoft 365**: `response_type=code`, `response_mode=query`, `scope=offline_access https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/User.Read`, `prompt=select_account`.
  - **PKCE S256 (RFC 7636)**: For Microsoft flows, the backend generates a 64-byte `SecureRandom` verifier, computes the SHA-256 digest, URL-safe Base64 encodes it as the `code_challenge`, and passes `code_challenge_method=S256`. The verifier is securely persisted in the backend state attempt.

### 3.2 Single-Use State Token Ledger (`OAuthAuthorizationAttempt`)
- **State Token Entropy**: 32 cryptographically random bytes generated via `SecureRandom` encoded as URL-safe Base64 without padding (256 bits of entropy).
- **Tenant & User Binding**: Each authorization attempt is bound to the initiating `organization_id`, `user_id`, provider, and dynamically resolved `redirect_uri`.
- **10-Minute TTL**: State tokens expire 10 minutes after generation.
- **Atomic Replay Prevention**:
  Upon provider callback, the backend executes an atomic SQL conditional update:
  ```sql
  UPDATE oauth_authorization_attempts a
  SET a.status = 'CONSUMED', a.consumed_at = :now
  WHERE (a.state_token = :token OR a.state = :token)
    AND a.status = 'PENDING'
    AND a.expires_at > :now
  ```
  - Exactly one execution can transition the attempt from `PENDING` to `CONSUMED`.
  - Concurrent requests or replay attempts update 0 rows, fail immediately, and return an explicit replay error without contacting the provider.

### 3.3 Mailbox Identity Auto-Discovery
Recruiters never type or configure mailbox email addresses manually. Once the authorization code is exchanged for an access token, the backend queries the provider identity API:
- **Google**: Calls `GET https://gmail.googleapis.com/gmail/v1/users/me/profile`, extracting `emailAddress`.
- **Microsoft**: Calls `GET https://graph.microsoft.com/v1.0/me`, extracting `mail` (or falling back to `userPrincipalName`).
The discovered email address is normalized to lowercase and used as the authoritative identity for the `MailConnection`.

### 3.4 Refresh Token Preservation on Reauthorization
Google OAuth does not re-issue a `refresh_token` on subsequent authorization flows unless forced with re-consent. If an existing mailbox connection is reauthorized and Google's token response omits `refresh_token`:
1. The backend inspects `mail_connections` for an existing connection matching `(organization_id, provider, mailbox_address)`.
2. If present, it decrypts the existing `oauth_secret_ref` using `CryptoUtil` (AES-256-GCM).
3. If an existing valid `refreshToken` is found, the backend preserves it alongside the newly issued `accessToken` and updated expiry timestamp.
4. Prevents connection invalidation during routine reauthorization.

### 3.5 Token Rotation During Mailbox Synchronization
In `OAuthMailSyncAdapter`:
- Before invoking provider APIs, the adapter checks whether the stored access token is expired or within 60 seconds of expiration.
- If expired, it triggers a refresh using the stored refresh token:
  - Google: `POST https://oauth2.googleapis.com/token` (`grant_type=refresh_token`).
  - Microsoft: `POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` (`grant_type=refresh_token`).
- When the provider issues a rotated refresh token in the response (`newRefreshToken != null`), `OAuthMailSyncAdapter` immediately re-encrypts the new token tuple and commits it to `mail_connections.oauth_secret_ref`, ensuring zero credential desynchronization.

### 3.6 HTML PostMessage Bridge & Fallback Redirect
- **Popup Window Flow**:
  1. The SPA opens a centered popup window pointing to the provider's `authorizationUrl`.
  2. The callback endpoint returns a clean HTML page containing a client bridge script:
     ```javascript
     const message = {
         type: 'HIREFLOW_OAUTH_CALLBACK',
         status: 'SUCCESS',
         provider: 'google',
         mailboxAddress: 'hr@company.com'
     };
     if (window.opener) {
         window.opener.postMessage(message, window.location.origin);
         setTimeout(() => window.close(), 1000);
     } else {
         window.location.href = '/index.html?oauth=success&provider=' + encodeURIComponent('google') + '&mailbox=' + encodeURIComponent('hr@company.com');
     }
     ```
  3. **Bidirectional Origin Validation**:
     - The callback script targets `window.location.origin`, preventing cross-origin token leaks.
     - The SPA listener strictly enforces:
       `if (event.origin !== window.location.origin) return;`
       `if (!event.data || event.data.type !== 'HIREFLOW_OAUTH_CALLBACK') return;`
  4. **Popup Blocker Fallback**:
     - If the browser blocks popups, the SPA falls back to a full-window redirect.
     - Upon callback, the user is redirected to `/index.html?oauth=success...`.
     - The SPA processes the result and immediately invokes `window.history.replaceState({}, document.title, cleanUrl)` to scrub query parameters from browser history.

---

## 4. Storage & Transaction Safety (J-2B Architecture)

A critical architectural guarantee of HireFlow is **Storage-to-Database Atomicity**:
1. During file ingestion, files are persisted to physical storage prior to database transaction commitment.
2. In the event of a database transaction rollback, an active transaction synchronization hook (`TransactionSynchronization.afterCompletion`) triggers automated cleanup of orphaned binary objects.
3. If an existing identical file (matching `organization_id` + `sha256`) already exists in the system, HireFlow reuses the existing storage object and skips redundant disk writes.
4. If a uniqueness race occurs under concurrent identical uploads, the loser transaction catches the database conflict, links to the winning entity, and disposes of the superfluous binary object without data corruption.

---

## 5. Frontend Single Page Application (SPA)

HireFlow features an integrated, responsive Single Page Application embedded directly inside `hireflow-backend/src/main/resources/static/index.html`.
- Styled with modern Tailwind CSS utilities and clean typography.
- Communicates with all backend REST endpoints via standard JSON fetch calls.
- Provides real-time dashboard analytics, candidate profile views, job requisition editors, application stage kanban boards, interactive resume PDF previewers, and one-click Google & Microsoft OAuth mailbox connections.
