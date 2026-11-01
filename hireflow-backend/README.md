# Kartgen HireFlow AI — Backend Service

> **Autonomous multi-channel candidate intake, deduplication, living profiles, and explainable JD matching.**

Built with **Java 21**, **Spring Boot 3.3.5**, **PostgreSQL / H2**, **Flyway**, **Apache PDFBox 3.0**, **Apache Tika 2.9**, and **Java Virtual Threads**.

---

## 🚀 Quick Start (Local Profile)

The application includes a zero-dependency local setup using embedded H2 in PostgreSQL compatibility mode, a local disk-backed S3 storage adapter, and in-process heuristic AI extraction.

### 1. Build and Run Tests
Run the full test suite (tenant isolation, auth, deduplication, living profile promotion, JD matching, application stages, and the webhook security/transport contract):

```powershell
.\mvnw.cmd test
```

### 2. Run the Application

Startup requires an explicit Spring profile; there is no implicit default.
For the zero-dependency local setup:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

Once started:
- **Test Console SPA:** [http://localhost:8080/](http://localhost:8080/)
- **H2 Web Console:** [http://localhost:8080/h2-console](http://localhost:8080/h2-console) (JDBC URL: `jdbc:h2:mem:hireflow`, User: `sa`, Password: *(empty)*)
- **Actuator Health:** [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

### 3. Optional: Local Webhook Demo Credential

Inbound webhook credentials are per-organization and are normally created
through `POST /api/v1/webhook-credentials` (OWNER/ADMIN only). Local seeding is
opt-in and has **no default secret**. To create the demo credential
`whk_0a11c0de00000001` for the seeded local organization, set all three values
before starting:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:WEBHOOK_LOCAL_DEV_ENABLED = "true"
$env:WEBHOOK_LOCAL_DEV_SECRET = "<your-own-random-secret-of-32-or-more-characters>"
.\mvnw.cmd spring-boot:run
```

The seeder never runs outside the `local` profile, stores the secret encrypted
at rest, and never logs it. See [docs/webhook-security.md](../docs/webhook-security.md)
for the v2 signing contract, limits, and retry semantics.

---

## 🔑 Pre-Seeded Credentials & Tokens

| Resource | Value | Notes |
| :--- | :--- | :--- |
| **Organization ID** | `11111111-1111-1111-1111-111111111111` | Default organization (`kartgen`) |
| **HR User Email** | `hr@kartgen.com` | Role: `OWNER` |
| **HR User Password** | `password123` | Valid BCrypt hash seeded in Flyway V2 |
| **Extension Token** | `hf_ext_live_org_kartgen_01_demo12345` | Pre-configured SHA-256 hash in DB |
| **Webhook Credential** | *none by default* | Per-organization, provisioned via API, or opt-in local seeding (section 3); never a global shared secret |

---

## 🛠 Features & Architecture

### 1. Modular Monolith Layout
- `tenant/`: Multi-tenant organization and user management.
- `security/`: JWT auth, extension token filter (`hf_ext_...`), webhook signature verification, role-based access control (`@PreAuthorize`).
- `mail/`: Ingestion for 5 channels (Direct Upload, Chrome Extension, Inbound Forwarding Webhook, IMAPS, and OAuth Gmail/Microsoft Graph).
- `document/`: Content detection via Tika, text extraction via PDFBox, 3-signal gatekeeper verification, S3/Local storage adapters with HMAC-signed streaming.
- `extraction/`: `LocalSmartHeuristicExtractor` (O(1) skill alias catalog, regex name/email/phone/experience detection) and `ExternalAiRestExtractor`.
- `identity/`: Candidate identity resolution, merge/name conflict review case generation, identifier deduplication.
- `profile/`: Living candidate profile promotion, document-date guarding, version snapshots (`V1 -> V2`).
- `jobs/`: Job description analyzer, requirement prioritization (MUST vs. PREFERRED), experience range extraction.
- `matching/`: Weighted 60/20/20 scoring algorithm with explainable JSON breakdowns.
- `hiring/`: Application lifecycle, submission tracking, optimistic-locking stage transitions (`NEW` → `SCREENING` → `INTERVIEW` → `OFFER` → `HIRED`), audit history.
- `ops/`: DB-backed atomic job claiming queue (`ProcessingWorker`), exponential backoff retries, dead-letter recovery.
- `demo/`: Real PDFBox-rendered sample resume generator (`/api/v1/demo/simulate-forwarded` and `/api/v1/demo/sample-resumes.zip`).

---

## 🧪 Built-In Test Console Features

Open `http://localhost:8080` to access the interactive console:
1. **Top KPI Bar:** Displays Live Candidates, Stored Resumes, Pending Queue Depth, Processed Jobs, and Active Extension Token with 1-click copy.
2. **Direct Upload Tab:** Drag and drop PDFs, DOCX, or ZIP archives with DIRECT or FORWARDED provenance.
3. **Connect Inbox Tab:** Configure IMAP credentials or OAuth credentials for Gmail / MS Graph.
4. **Simulate Forwarded Email:** Generates genuine PDF resumes in-memory (Rahul Sharma V1, Priya Patel, Amit Verma, and Rahul Sharma V2 updated) and pushes them through the ingestion queue to demonstrate deduplication and V1 → V2 promotion.
5. **JD Selector & Skill Filters:** Select active job descriptions to view match scores and filter by skills.
6. **Candidate Table & PDF Drawer:** View candidate details, parsed skills, match breakdown, and stream the genuine PDF in the slide-over viewer via signed HMAC URLs.

---

## 🐳 Docker Production Setup

Start the production infrastructure (PostgreSQL 16 and MinIO S3):

```powershell
docker compose up -d
```

Run HireFlow with the production profile:
```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:DB_PASSWORD="hireflow_local"
$env:JWT_SECRET="YOUR-PRODUCTION-256-BIT-SECRET-KEY-HERE-0123456789"
$env:CRYPTO_SECRET_KEY="0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
$env:S3_BUCKET="hireflow-resumes"
$env:S3_ENDPOINT="http://localhost:9000"
$env:S3_PATH_STYLE="true"
$env:CORS_ALLOWED_ORIGINS="http://localhost:3000,http://localhost:8080"

.\mvnw.cmd spring-boot:run
```

