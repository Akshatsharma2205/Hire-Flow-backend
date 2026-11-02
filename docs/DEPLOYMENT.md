# HireFlow AI — Production Deployment Guide

This guide details the deployment architecture, configuration parameters, operational prerequisites, and security validations for running HireFlow AI in production environments.

---

## 1. Runtime Environment & System Requirements

- **Operating System**: Linux (Ubuntu 22.04 LTS, Debian 12, Alpine, or RHEL 9 recommended)
- **Java Runtime**: **OpenJDK / Eclipse Temurin JDK 21 LTS** (Virtual Threads enabled via Project Loom)
- **Database**: **PostgreSQL 15+** (PostgreSQL 16 recommended)
- **Memory**: Minimum 2 GB RAM (4 GB recommended for production traffic with concurrent resume parsing)
- **CPU**: 2+ vCPUs recommended
- **Object Storage**: **AWS S3** or S3-compatible object storage (e.g., MinIO, Cloudflare R2, Ceph). *Note*: Persistent local disk document storage (`hireflow.storage.provider=local`) is supported **only** in the `local` development profile and is strictly rejected at startup in `prod`.

---

## 2. Startup Architecture & Safety Validation

HireFlow enforces a multi-stage, fail-fast startup validation pipeline to guarantee that development configurations and weak secrets never reach production:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 1. StartupProfileGuard (ApplicationContextInitializer)                         │
│    Location: com.kartgen.hireflow.config.StartupProfileGuard                    │
│    Registered in: META-INF/spring.factories                                     │
│    Timing: Runs BEFORE Spring ApplicationContext refresh                        │
│    Enforcement:                                                                 │
│      • SPRING_PROFILES_ACTIVE must not be empty or null.                        │
│      • Profile must be explicitly set to "local", "test", or "prod".            │
│      • Refuses startup if "prod" and "local" are combined.                      │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 2. Spring Boot Configuration & Placeholder Resolution                           │
│    Timing: ApplicationContext refresh                                           │
│    Enforcement:                                                                 │
│      • Fails fast if mandatory production environment variables are missing:    │
│        DB_PASSWORD, JWT_SECRET, CRYPTO_SECRET_KEY, S3_BUCKET,                  │
│        AI_ENDPOINT_URL, AI_API_KEY, CORS_ALLOWED_ORIGINS                        │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 3. Webhook Size Limit Validation (@PostConstruct)                               │
│    Location: com.kartgen.hireflow.config.HireFlowProperties$WebhookProperties      │
│    Timing: Bean construction                                                    │
│    Enforcement:                                                                 │
│      • Validates maxAttachmentBytes <= maxTotalAttachmentBytes <= maxBodyBytes │
│      • Enforces maxParts >= maxAttachments + 5                                  │
│      • Ensures maxBodyBytes accommodates Base64 payload expansion               │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 4. ProductionSafetyConfig (@Configuration @Profile("prod") @PostConstruct)     │
│    Location: com.kartgen.hireflow.config.ProductionSafetyConfig                 │
│    Timing: Context initialization under 'prod' profile                          │
│    Enforcement:                                                                 │
│      • JWT_SECRET: length >= 32 chars, cannot contain "CHANGE-ME".              │
│      • CRYPTO_SECRET_KEY: exactly 64 hex characters, cannot equal dev key.      │
│      • WEBHOOK_REPLAY_WINDOW_SECONDS: >= 30 seconds.                             │
│      • WEBHOOK_LOCAL_DEV_ENABLED: strictly forbidden (must not be true).        │
│      • WEBHOOK_LOCAL_DEV_SECRET: strictly forbidden (must not be set or non-blank)
│      • Storage Provider: MUST be "s3" (case-insensitive).                       │
│      • S3_BUCKET: must not be null or blank.                                    │
│      • CORS_ALLOWED_ORIGINS: must not contain "localhost" or "127.0.0.1".       │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 5. ProductionDemoCredentialSanitizer (ApplicationRunner, HIGHEST_PRECEDENCE+10)│
│    Location: com.kartgen.hireflow.security.ProductionDemoCredentialSanitizer    │
│    Timing: Post-startup bean execution under 'prod' profile                     │
│    Enforcement:                                                                 │
│      • Neutralizes seeded V2 demo user passwords (hr@kartgen.com).              │
│      • Suspends demo organization (11111111-1111-1111-1111-111111111111).       │
│      • Invalidates hardcoded demo extension token hashes in organization_users. │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibility Distinction
- **`StartupProfileGuard`**: Validates active profile selection prior to context initialization. It verifies that `SPRING_PROFILES_ACTIVE` is specified, recognizes only `local`, `test`, or `prod`, and prevents dangerous co-activation of `prod` and `local`. It does **not** evaluate datasource properties, secret entropy, or S3 configurations.
- **`ProductionSafetyConfig`**: Executes during bean initialization under the `prod` profile. It inspects resolved properties and actively rejects development placeholders, insufficient key lengths, local disk storage, local webhook mock endpoints, and insecure CORS origins.
- **`ProductionDemoCredentialSanitizer`**: Runs after startup to sanitize seed data from database migrations.

---

## 3. Configuration Reference

HireFlow follows Twelve-Factor App principles. Configuration is externalized through environment variables and mapped via Spring Boot `application.yml` and `application-prod.yml`.

### 3.1 Mandatory Production Environment Variables
In the `prod` profile, startup fails immediately if any of these variables are unset or violate security constraints:

| Property Name | Environment Variable | Required | Default Behavior | Applicable Profile | Constraints / Validations |
|---|---|---|---|---|---|
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | **Yes** | None | All | Must be set to `prod`. Validated by `StartupProfileGuard`. Mixed `prod`+`local` is rejected. |
| `spring.datasource.password` | `DB_PASSWORD` | **Yes** | None | `prod` | PostgreSQL user password. No default; placeholder resolution fails if missing. |
| `hireflow.jwt.secret` | `JWT_SECRET` | **Yes** | None | `prod` | HMAC-SHA256 JWT signing key. Enforced by `ProductionSafetyConfig`: min 32 characters, cannot contain `"CHANGE-ME"`. |
| `hireflow.crypto.secret-key` | `CRYPTO_SECRET_KEY` | **Yes** | None | `prod` | Master AES-256 key for encrypting mailbox OAuth secrets. Enforced by `ProductionSafetyConfig`: must be **exactly 64 hexadecimal characters** matching `(?i)[0-9a-f]{64}`, and cannot match the development key `0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF`. Base64 format is **not** supported. |
| `hireflow.storage.s3-bucket` | `S3_BUCKET` | **Yes** | None | `prod` | AWS S3 bucket name. Enforced by `ProductionSafetyConfig`: must not be blank. |
| `hireflow.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | **Yes** | None | `prod` | Comma-separated allowed CORS origins (e.g. `https://hireflow.example.com`). Enforced by `ProductionSafetyConfig`: cannot contain `localhost` or `127.0.0.1`. |
| `hireflow.ai.endpoint-url` | `AI_ENDPOINT_URL` | **Yes** | None | `prod` | HTTP endpoint URL for external resume parsing microservice (e.g. `https://ai.internal.hireflow.com/api/extract`). |
| `hireflow.ai.api-key` | `AI_API_KEY` | **Yes** | None | `prod` | API bearer token for external AI extraction service. |

### 3.2 Optional & Tunable Environment Variables

| Property Name | Environment Variable | Required | Default Behavior | Applicable Profile | Description |
|---|---|---|---|---|---|
| `server.port` | `SERVER_PORT` | No | `8080` | All | HTTP listening port for the application service. |
| `spring.datasource.url` | `DB_HOST`, `DB_PORT`, `DB_NAME` | No | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:hireflow}` | `prod` | Database JDBC URL components. In `prod`, defaults to `localhost:5432/hireflow`. |
| `spring.datasource.username` | `DB_USERNAME` | No | `hireflow` | `prod` | PostgreSQL database user account. |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_SIZE` | No | `20` | `prod` | Maximum HikariCP connection pool size. |
| `spring.datasource.hikari.minimum-idle` | `DB_POOL_MIN_IDLE` | No | `5` | `prod` | Minimum idle connections retained in pool. |
| `hireflow.storage.provider` | `STORAGE_PROVIDER` | No | `s3` (in prod), `local` (in base) | All | Storage adapter. Hardcoded to `s3` in `application-prod.yml`. `ProductionSafetyConfig` throws exception if not `s3` in `prod`. |
| `hireflow.storage.s3-region` | `S3_REGION` | No | `ap-south-1` | `prod`, `local` | AWS S3 region for bucket operations. |
| `hireflow.storage.s3-endpoint` | `S3_ENDPOINT` | No | `""` (AWS default) | `prod`, `local` | Custom endpoint for S3-compatible object stores (MinIO, Cloudflare R2, LocalStack). |
| `hireflow.storage.s3-path-style` | `S3_PATH_STYLE` | No | `false` | `prod`, `local` | Enable path-style S3 access (required for specific MinIO configurations). |
| `hireflow.storage.s3-presigned-expiry-minutes` | `S3_PRESIGNED_EXPIRY_MINUTES` | No | `15` | `prod`, `local` | Validity duration of presigned candidate resume view URLs (in minutes). |
| `hireflow.jwt.expiration-ms` | `JWT_EXPIRATION_MS` | No | `86400000` (24 hours) | All | JWT access token validity period in milliseconds. |
| `hireflow.ai.provider` | `AI_PROVIDER` | No | `external` (in prod), `local` (in base) | All | AI parsing provider (`external` or `local`). |
| `hireflow.ai.request-timeout-ms` | `AI_REQUEST_TIMEOUT_MS` | No | `60000` (60 seconds) | All | HTTP client timeout for AI resume parsing requests. |
| `hireflow.webhook.enabled` | `WEBHOOK_ENABLED` | No | `true` | All | Master toggle for inbound mail forwarding webhook ingestion. |
| `hireflow.webhook.replay-window-seconds` | `WEBHOOK_REPLAY_WINDOW_SECONDS` | No | `300` (5 minutes) | All | Webhook clock drift tolerance window. Must be >= 30 seconds in `prod`. |
| `hireflow.webhook.max-body-bytes` | `WEBHOOK_MAX_BODY_BYTES` | No | `26214400` (25 MB) | All | Maximum allowed raw webhook payload size in bytes. |
| `hireflow.webhook.max-attachments` | `WEBHOOK_MAX_ATTACHMENTS` | No | `20` | All | Maximum attachments processed per webhook request. |
| `hireflow.webhook.max-attachment-bytes` | `WEBHOOK_MAX_ATTACHMENT_BYTES` | No | `10485760` (10 MB) | All | Maximum decoded byte size of any single resume attachment. |
| `hireflow.webhook.max-total-attachment-bytes` | `WEBHOOK_MAX_TOTAL_ATTACHMENT_BYTES` | No | `16777216` (16 MB) | All | Maximum combined attachment size allowed per delivery. |
| `hireflow.webhook.max-parts` | `WEBHOOK_MAX_PARTS` | No | `32` | All | Maximum allowed MIME multipart parts. |
| `hireflow.webhook.max-metadata-bytes` | `WEBHOOK_MAX_METADATA_BYTES` | No | `16384` (16 KB) | All | Maximum size for metadata and part header blocks. |
| `hireflow.webhook.max-header-bytes` | `WEBHOOK_MAX_HEADER_BYTES` | No | `8192` (8 KB) | All | Maximum size for an individual part header block. |
| `hireflow.webhook.max-concurrent-requests` | `WEBHOOK_MAX_CONCURRENT_REQUESTS` | No | `4` | All | Maximum concurrent webhook payload processing tasks. |
| `hireflow.oauth.google-client-id` | `GOOGLE_OAUTH_CLIENT_ID` | Conditional | `""` | All | Google Cloud OAuth Client ID for Gmail mailbox sync. |
| `hireflow.oauth.google-client-secret` | `GOOGLE_OAUTH_CLIENT_SECRET` | Conditional | `""` | All | Google Cloud OAuth Client Secret for Gmail mailbox sync. |
| `hireflow.oauth.google-token-uri` | `GOOGLE_OAUTH_TOKEN_URI` | No | `https://oauth2.googleapis.com/token` | All | Google OAuth token refresh endpoint. |
| `hireflow.oauth.gmail-base-url` | `GMAIL_BASE_URL` | No | `https://gmail.googleapis.com/gmail/v1` | All | Gmail API base URL. |
| `hireflow.oauth.microsoft-client-id` | `MS_OAUTH_CLIENT_ID` | Conditional | `""` | All | Microsoft Azure App Registration Client ID for Outlook sync. |
| `hireflow.oauth.microsoft-client-secret` | `MS_OAUTH_CLIENT_SECRET` | Conditional | `""` | All | Microsoft Azure App Registration Client Secret. |
| `hireflow.oauth.microsoft-tenant` | `MS_OAUTH_TENANT` | No | `common` | All | Microsoft Azure Active Directory tenant ID or `common`. |
| `hireflow.oauth.microsoft-token-uri-template` | `MS_OAUTH_TOKEN_URI_TEMPLATE` | No | `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` | All | Microsoft OAuth token endpoint template. |
| `hireflow.oauth.graph-base-url` | `MS_GRAPH_BASE_URL` | No | `https://graph.microsoft.com/v1.0` | All | Microsoft Graph API base URL. |
| `hireflow.worker.poll-ms` | `WORKER_POLL_MS` | No | `2000` (2 seconds) | All | Background worker polling interval for queued parsing tasks. |
| `hireflow.worker.batch-size` | `WORKER_BATCH_SIZE` | No | `10` | All | Number of queued resume jobs retrieved per worker batch. |
| `hireflow.worker.stale-after-minutes` | `WORKER_STALE_MINUTES` | No | `10` (10 minutes) | All | Inactivity duration before an in-progress parsing job is marked stale and re-queued. |

---

### 3.3 Google & Microsoft OAuth 2.0 Mailbox Configuration

HireFlow connects recruiter mailboxes via standard OAuth 2.0 authorization code flows with PKCE. Recruiters grant delegated permissions directly through provider consent screens without sharing raw credentials or handling manual tokens.

#### 3.3.1 Required Delegated Scopes
The backend requests the minimum required delegated permissions needed for profile resolution and read-only message/attachment synchronization:

- **Google Workspace / Gmail**:
  - `https://www.googleapis.com/auth/gmail.readonly`: Read-only access to messages, threads, history, and attachments for resume ingestion.
  - `https://www.googleapis.com/auth/userinfo.email`: Resolves the authenticated user's primary email address during connection setup.
  - Parameters requested: `access_type=offline`, `prompt=consent` (ensures initial issuance of refresh token).
- **Microsoft 365 / Outlook Graph**:
  - `offline_access`: Requests refresh token for ongoing background mailbox synchronization.
  - `https://graph.microsoft.com/Mail.Read`: Delegated read access to mail folders and messages.
  - `https://graph.microsoft.com/User.Read`: Delegated profile access to resolve user email address (`mail` or `userPrincipalName`).
  - Security parameters: PKCE `code_challenge_method=S256` with high-entropy cryptographic verifier.

#### 3.3.2 Authorized Redirect URIs
The OAuth authorization callback is hosted at:
`/api/v1/mail-connections/oauth/{provider}/callback`

Configure the authorized redirect URIs in provider developer consoles:

| Provider | Environment | Authorized Redirect URI | Authorized Origin |
|---|---|---|---|
| **Google** | Local Development | `http://localhost:8080/api/v1/mail-connections/oauth/google/callback` | `http://localhost:8080` |
| **Google** | Production | `https://<your-domain>/api/v1/mail-connections/oauth/google/callback` | `https://<your-domain>` |
| **Microsoft** | Local Development | `http://localhost:8080/api/v1/mail-connections/oauth/microsoft/callback` | `http://localhost:8080` |
| **Microsoft** | Production | `https://<your-domain>/api/v1/mail-connections/oauth/microsoft/callback` | `https://<your-domain>` |

#### 3.3.3 Development vs Production Setup

- **Google Cloud Console Setup**:
  1. Navigate to **APIs & Services > OAuth consent screen**.
  2. **Development / Testing**: Set User Type to **External** and Publishing status to **Testing**. Add designated recruiter emails under **Test users** (up to 100 test users allowed without Google verification).
  3. **Production**: Set Publishing status to **In Production**. For internal enterprise use within a single Google Workspace domain, set User Type to **Internal** (exempt from external verification and CASA Tier 2).
  4. Under **Credentials > Create Credentials > OAuth client ID**, select **Web application**. Add the Authorized JavaScript origins and Authorized redirect URIs matching your deployment domain.
  5. Set environment variables: `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET`.

- **Microsoft Azure Portal (Entra ID) Setup**:
  1. Navigate to **Microsoft Entra ID > App registrations > New registration**.
  2. Set **Supported account types**:
     - For multi-tenant recruitment across any Microsoft account: Select **Accounts in any organizational directory (Any Microsoft Entra ID tenant - Multitenant) and personal Microsoft accounts** (corresponds to `MS_OAUTH_TENANT=common`).
     - For single-organization deployments: Select **Accounts in this organizational directory only** and set `MS_OAUTH_TENANT=<your-tenant-uuid>`.
  3. Under **Redirect URI**, select **Web** and enter `https://<your-domain>/api/v1/mail-connections/oauth/microsoft/callback`.
  4. Navigate to **Certificates & secrets > Client secrets > New client secret**. Copy secret value to `MS_OAUTH_CLIENT_SECRET`.
  5. Copy Application (client) ID to `MS_OAUTH_CLIENT_ID`.
  6. Under **API permissions**, add delegated permissions for Microsoft Graph: `Mail.Read`, `User.Read`, `offline_access`.

#### 3.3.4 OAuth Verification Implications: Google CASA Tier 2 Restricted Scope Assessment
Because HireFlow requests `https://www.googleapis.com/auth/gmail.readonly`, it falls into Google's **Restricted Scopes** tier:

1. **Development & Internal Exemption**:
   - Apps with publishing status **Testing** (restricted to test users) or apps configured as **Internal** to a Google Workspace domain do **not** require CASA assessment or Google verification.
2. **Production Multi-Tenant Verification**:
   - If HireFlow is deployed as a public or multi-tenant SaaS application allowing external Google users to connect Gmail mailboxes, Google mandates **OAuth App Verification** and a **Cloud Application Security Assessment (CASA) Tier 2** assessment.
   - **CASA Tier 2 Requirements**:
     - Evaluated against OWASP ASVS (Application Security Verification Standard) Level 2 controls.
     - Third-party security assessment by an authorized CASA testing laboratory.
     - Static and dynamic application security testing (SAST / DAST).
     - Cloud deployment infrastructure review ensuring encrypted token storage (satisfied by HireFlow's AES-256-GCM `CryptoUtil`), tenant isolation, and strict access control.
     - Mandatory annual re-certification and ongoing vulnerability management.

---

### 3.4 Development-Only Environment Variables (Forbidden in Production)

| Property Name | Environment Variable | Permitted Profiles | Behavior in `prod` Profile |
|---|---|---|---|
| `hireflow.storage.local-base-path` | `LOCAL_STORAGE_PATH` | `local`, `test` | Ignored. `ProductionSafetyConfig` throws an exception if storage provider is not `s3`. |
| `hireflow.storage.local-url-secret` | `LOCAL_URL_SECRET` | `local` | Ignored. Local storage HMAC token signing secret is not used in production. |
| `hireflow.webhook.local-dev-enabled` | `WEBHOOK_LOCAL_DEV_ENABLED` | `local` | **Fails startup**: `ProductionSafetyConfig` throws `IllegalStateException("Local webhook credential seeding cannot be enabled in production")`. |
| `hireflow.webhook.local-dev-secret` | `WEBHOOK_LOCAL_DEV_SECRET` | `local` | **Fails startup**: `ProductionSafetyConfig` throws `IllegalStateException("WEBHOOK_LOCAL_DEV_SECRET must not be set in production")`. |

---

## 4. Production Packaging & Containerization

### 4.1 Build Executable JAR
```bash
cd hireflow-backend
./mvnw clean package -DskipTests --batch-mode
```
The resulting executable JAR will be located at:
`hireflow-backend/target/hireflow-0.0.1-SNAPSHOT.jar`

### 4.2 Standard Production Launch Command
```bash
java -server \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:+ExitOnOutOfMemoryError \
  -Dspring.profiles.active=prod \
  -jar target/hireflow-0.0.1-SNAPSHOT.jar
```

---

## 5. Database Setup & Flyway Migration

1. **Create PostgreSQL Database & Role**:
   ```sql
   CREATE USER hireflow WITH ENCRYPTED PASSWORD 'your_secure_password';
   CREATE DATABASE hireflow OWNER hireflow;
   GRANT ALL PRIVILEGES ON DATABASE hireflow TO hireflow;
   ```
   *(Note: If connecting as a custom user such as `hireflow_app`, ensure you set the `DB_USERNAME=hireflow_app` environment variable).*

2. **Schema Migrations**:
   Flyway runs automatically on application startup. It validates and executes:
   - `V1__init_schema.sql`: Core 27 domain tables
   - `V2__seed_data.sql`: Seed data (sanitized automatically in `prod` profile)
   - `V3__performance_indices.sql`: Production composite indices
   - `V4__webhook_security_and_idempotency.sql`: Webhook security & idempotency tables
   - `V5__oauth_authorization_attempts.sql`: OAuth 2.0 authorization attempt ledger, state token indexes, PKCE verifiers, and mailbox index

---

## 6. Security Checklist Before Going Live

1. **Verify Startup Profile Validation**:
   - Inspect startup logs to confirm that `StartupProfileGuard` validated `SPRING_PROFILES_ACTIVE=prod` without conflicting profiles.
2. **Verify Production Safety Configuration**:
   - Confirm log line: `"Production safety checks passed"`, indicating `ProductionSafetyConfig` successfully verified `JWT_SECRET` length, `CRYPTO_SECRET_KEY` format (64-hex), S3 storage provider and bucket configuration, webhook replay window, and production CORS origins.
3. **Verify Demo Credential Neutralization**:
   - Confirm log line: `"Production demo credentials neutralized"`, indicating `ProductionDemoCredentialSanitizer` neutralized demo passwords, disabled demo organizations, and purged demo extension token hashes.
4. **Configure Reverse Proxy / TLS**:
   - Place HireFlow behind an HTTPS reverse proxy (Nginx, AWS ALB, Caddy, Cloudflare).
   - Ensure `X-Forwarded-For` and `X-Forwarded-Proto` headers are correctly forwarded (`server.forward-headers-strategy=framework` is enabled).
5. **CORS Restrictions**:
   - Ensure `CORS_ALLOWED_ORIGINS` contains only verified production domains (e.g. `https://hireflow.example.com`). Localhost and IP addresses (`127.0.0.1`) are rejected at startup.
