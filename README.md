# HireFlow AI — Intelligent Recruitment & Candidate Ingestion Platform

[![HireFlow CI](https://github.com/Akshatsharma2205/Hire-Flow-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/Akshatsharma2205/Hire-Flow-backend/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3.5](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Tests Passing](https://img.shields.io/badge/Tests-282%20Passing-success.svg)](docs/TESTING.md)

**HireFlow AI** is an enterprise-grade applicant tracking system (ATS) and automated candidate intake pipeline engineered as a high-performance **Modular Monolith** in **Java 21** with **Spring Boot 3.3.5**. It streamlines candidate ingestion across webmail extensions, direct recruiter uploads, and inbound email forwarding webhooks while guaranteeing strict tenant isolation, cryptographic signature validation, deterministic identity deduplication, and storage-to-database atomicity.

---

## Key Features

- **Multi-Channel Candidate Ingestion**:
  - **Browser Extension (Manifest V3)**: Real-time resume extraction from Gmail and Outlook with built-in invoice trap filters and SHA-256 duplicate pre-checks.
  - **Direct Recruiter Upload**: Drag-and-drop resume ingestion in the embedded web SPA.
  - **Inbound Forwarding Webhook**: Enterprise email webhook receiver with HMAC-SHA256 v2 signature verification and idempotency deduplication.
- **Privacy-Preserving Resume Extraction**:
  - Deterministic parsing via Apache Tika and PDFBox heuristics without external cloud AI dependencies.
  - Automatic skill canonicalization using an alias dictionary covering 55+ technical skills.
- **Deterministic Identity & Deduplication**:
  - Multi-tiered resolution across normalized emails, phone numbers, and LinkedIn URLs.
  - Automated collision detection generating recruiter review cases (`IdentityReviewCase`).
- **High-Performance Job Matching**:
  - Weighted candidate-to-job scoring (0-100) evaluating mandatory skills, bonus capabilities, and seniority.
- **Robust Storage Architecture (J-2B)**:
  - Clean `StoragePort` abstraction with content-addressable local disk adapter and HMAC signed temporary view URLs.
  - Storage-to-database atomic synchronization ensuring automatic cleanup of physical files on transaction rollback.
- **Defense-in-Depth Security**:
  - Role-Based Access Control (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`).
  - Production guards actively rejecting weak secrets and neutralizing default demo credentials on startup.

---

## Frontend-to-Backend Integration Matrix

| Feature | Frontend page/component | API endpoint | Auth requirement | Success handling | Error handling | Test status |
|---|---|---|---|---|---|---|
| **Auto-Login** | Header / `autoLogin()` | `POST /api/v1/auth/login` | Public | Stores JWT in memory, displays user badge, loads initial data | Displays error status badge and toast notification | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **KPI Metrics** | KPI Bar / `loadKPIs()` | `GET /api/v1/operations/health` | Bearer JWT | Updates candidate, file, queue, and processed counters | Silent failure to avoid interrupting UI navigation | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Extension Token** | KPI Bar / `generateExtToken()` | `POST /api/v1/auth/extension-token` | Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`) | Renders new `hf_ext_...` token code, displays success toast | Displays error toast with rejection reason | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Direct Upload** | Tab A / `handleFiles()` | `POST /api/v1/uploads/direct` | Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`) | Shows queued count badge, auto-refreshes candidate list | Displays red failure text and error toast | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Pre-Flight Dedup** | Tab C / `testExtensionPreCheck()` | `POST /api/v1/uploads/check-hashes` | Bearer Extension Token | Displays whether file is duplicate or new | Displays error span with HTTP status | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Extension Intake** | Tab C / `sendExtensionIntakeRequest()` | `POST /api/v1/uploads/extension-intake` | Bearer Extension Token | Updates inspector log with elapsed ms, polls candidate table | Renders rejected HTTP status code and details in log box | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Mailbox Connect** | Tab B / `createMailConnection()` | `POST /api/v1/mail-connections` | Bearer JWT (`OWNER`, `ADMIN`) | Resets form, displays success toast, refreshes connections | Displays connection error toast | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Mailbox Sync** | Tab B / `syncNow()` | `POST /api/v1/mail-connections/{id}/sync-now` | Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`) | Displays scanned and ingested counts in green alert card | Displays red alert card with failure diagnosis | **VERIFIED** (`IntakePipelineIntegrationTest`) |
| **List Jobs** | Requisition dropdown / `loadJobs()` | `GET /api/v1/jobs` | Bearer JWT | Populates `<select>` options for job filter and modal | Retains default "All Candidates" option | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Create Job** | Modal / `createJob()` | `POST /api/v1/jobs` | Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`) | Closes modal, refreshes job selector, displays success toast | Displays error toast with validation details | **VERIFIED** (`JobControllerIntegrationTest`) |
| **List Candidates**| Candidate Table / `loadCandidates()` | `GET /api/v1/candidates` | Bearer JWT | Renders table rows with skills, match %, version badges | Displays error row in table body | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Candidate Drawer**| Drawer / `openDrawer(id)` | `GET /api/v1/candidates/{id}` | Bearer JWT | Populates contact details, skills list, and education | Displays error message inside drawer panel | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Version History** | Drawer / `openDrawer(id)` | `GET /api/v1/candidates/{id}/versions` | Bearer JWT | Renders version rows with status and "View PDF" button | Renders empty state message | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Applications** | Drawer / `openDrawer(id)` | `GET /api/v1/candidates/{id}/applications` | Bearer JWT | Renders application history cards with stage badges | Renders "No applications yet" text | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Resume Preview** | Drawer / `viewResumePdf()` | `GET /api/v1/resume-versions/{id}/view-url` | Bearer JWT | Sets signed URL on `<iframe>`, displays viewer container | Displays error toast | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |
| **Binary Stream** | Preview `<iframe>` | `GET /api/v1/local-s3/{token}` | URL HMAC Signature | Streams PDF bytes with inline disposition | Returns HTTP 403 Forbidden or HTTP 410 Gone | **VERIFIED** (`FrontendSpaEndToEndIntegrationTest`) |

---

## Quick Start (Local Development)

### 1. Prerequisites
- **Java Development Kit (JDK) 21+**
- **Node.js 20+** (for browser extension tests)
- **Google Chrome** (for browser extension)

### 2. Run the Backend Application
The backend includes an embedded H2 database pre-seeded with sample data via Flyway migrations.

```powershell
cd hireflow-backend
# Windows:
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

# Linux / macOS:
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Once started, the backend is available at:
- **Application & Embedded SPA**: `http://localhost:8080/`
- **Health Check Actuator**: `http://localhost:8080/actuator/health`

### 3. Log In to the Web Dashboard
Navigate to `http://localhost:8080/` in your browser. Default local development credentials:
- **Email**: `hr@kartgen.com`
- **Password**: `password123`
- **Role**: `OWNER`

### 4. Run Automated Tests
HireFlow includes a comprehensive verification suite:

```powershell
# Run backend test suite (31 suites, 282 tests)
cd hireflow-backend
.\mvnw.cmd test

# Run Chrome extension test suite (11 tests)
node --test hireflow-extension/test/extension.test.js
```

---

## Documentation Index

Comprehensive technical documentation is maintained in the `docs/` directory:

| Document | Description |
|---|---|
| [**Architecture Guide**](docs/ARCHITECTURE.md) | Modular monolith topology, bounded contexts, transaction atomicity |
| [**REST API Reference**](docs/API_REFERENCE.md) | Complete catalog of all 13 controllers, routes, request/response contracts |
| [**Database & Schema**](docs/DATABASE.md) | 29 JPA entities, table schemas, Flyway migrations V1-V4, relationship matrix |
| [**Java Class Reference**](docs/CLASS_REFERENCE.md) | Comprehensive class listing by package with single responsibilities |
| [**Security Architecture**](docs/SECURITY.md) | HMAC v2 signatures, timing attack defense, tenant isolation, production guards |
| [**Testing Guide**](docs/TESTING.md) | Test layers, failure-injection tests, MockMvc tests, execution commands |
| [**Chrome Extension**](docs/EXTENSION.md) | Manifest V3 architecture, permissions, privacy gatekeeper, setup guide |
| [**Final Verification Report**](docs/FINAL_VERIFICATION_REPORT.md) | Deep test logs, live runtime evidence, unverified aspects ledger |
| [**Repository Structure**](REPOSITORY_STRUCTURE.md) | Directory organization and file layout |

---

## Production Deployment

To package the application for production deployment with PostgreSQL 15+:

```powershell
cd hireflow-backend
.\mvnw.cmd clean package -DskipTests
```
