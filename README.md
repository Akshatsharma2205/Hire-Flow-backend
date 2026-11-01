# HireFlow AI — Intelligent Recruitment & Candidate Ingestion Platform

[![HireFlow CI](https://github.com/Akshatsharma2205/Hire-Flow-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/Akshatsharma2205/Hire-Flow-backend/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3.5](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Tests Passing](https://img.shields.io/badge/Tests-281%20Passing-success.svg)](docs/TESTING.md)

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

## Quick Start (Local Development)

### 1. Prerequisites
- **Java Development Kit (JDK) 21+**
- **Node.js 20+** (for browser extension tests)
- **Google Chrome** (for browser extension)

### 2. Run the Backend Application
The backend includes an embedded H2 database pre-seeded with sample data via Flyway migrations.

```powershell
cd hireflow-backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
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
# Run backend test suite (30 suites, 281 tests)
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
| [**Database & Schema**](docs/DATABASE.md) | 29 JPA entities, table schemas, Flyway migrations V1-V4, index inventory |
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

Run with the `prod` profile and external environment variables:

```bash
java -jar target/hireflow-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.datasource.url=jdbc:postgresql://postgres.production.internal:5432/hireflow \
  --spring.datasource.username=hireflow_app \
  --spring.datasource.password=${DB_PASSWORD} \
  --hireflow.security.jwt.secret=${STRONG_JWT_SECRET} \
  --hireflow.storage.local-url-secret=${STRONG_STORAGE_HMAC_KEY}
```

*Note: In `prod` profile, `StartupProfileGuard` will immediately abort startup if secrets are weak or if in-memory H2 is configured.*
