# HireFlow AI — Repository Structure & Layout

This document details the final, production-ready directory organization of the **HireFlow AI** repository.

```
hireflow/
├── .github/
│   └── workflows/
│       └── ci.yml                     # Multi-job GitHub Actions CI (Java 21, Node.js, Hygiene)
├── docs/                              # Comprehensive engineering documentation
│   ├── audit-history/                 # Preserved audit packages and exact diff patches (J-1 through J-2B.2)
│   ├── spec/                          # Product specification blueprint & architecture diagrams
│   ├── ARCHITECTURE.md                # System topology, core modules, ports & adapters, pipeline flows
│   ├── API_REFERENCE.md               # Complete REST API catalog (13 controllers, endpoints, schemas, auth)
│   ├── DATABASE.md                    # Entity-Relationship diagram, 29 tables, indexes, constraints, migrations
│   ├── CLASS_REFERENCE.md             # Complete Java class inventory by package and responsibility
│   ├── SECURITY.md                    # Defense-in-depth: HMAC v2, timing attack protection, tenant isolation
│   ├── TESTING.md                     # Test strategy, 30 suites (281 tests), fixtures, failure-injection tests
│   ├── EXTENSION.md                   # Chrome Extension (Manifest V3) architecture, permissions, client tests
│   ├── FINAL_VERIFICATION_REPORT.md   # Final deep verification report & evidence ledger
│   └── webhook-security.md            # In-depth webhook signature specification
├── hireflow-backend/                  # Spring Boot 3.3.5 / Java 21 modular monolith application
│   ├── .mvn/wrapper/                  # Maven Wrapper binary & configuration
│   ├── mvnw.cmd                       # Maven Windows command wrapper
│   ├── pom.xml                        # Maven project descriptor (Java 21, Spring Boot, Flyway, H2/PG, Tika)
│   ├── docker-compose.yml             # Local PostgreSQL 16 container compose configuration
│   └── src/
│       ├── main/
│       │   ├── java/com/kartgen/hireflow/
│       │   │   ├── HireFlowApplication.java   # Spring Boot entry point with Virtual Threads enabled
│       │   │   ├── common/                    # Core utilities (HashUtil, Exception types)
│       │   │   ├── config/                    # Security, async, properties, and startup guards
│       │   │   ├── document/                  # StoragePort, LocalDiskStorageAdapter, ResumeFile
│       │   │   ├── extraction/                # Resume parsing (LocalSmartHeuristicExtractor, Tika, PDFBox)
│       │   │   ├── hiring/                    # Applications, stage progression, candidate notes
│       │   │   ├── identity/                  # Candidate identity resolution, deduplication, review cases
│       │   │   ├── jobs/                      # Job requisitions, versioning, skill requirements
│       │   │   ├── mail/                      # Inbound email processing, direct upload, extension intake
│       │   │   ├── matching/                  # Deterministic candidate-to-job matching & scoring
│       │   │   ├── ops/                       # Background worker scheduler, async jobs, outbox, audit
│       │   │   ├── profile/                   # Living candidate profile versioning & skill taxonomy
│       │   │   ├── security/                  # JWT auth, tenant context filter, extension tokens, roles
│       │   │   ├── tenant/                    # Organizations, users, memberships, multi-tenant isolation
│       │   │   └── webhook/                   # HMAC-SHA256 v2 webhook authentication & delivery tracking
│       │   └── resources/
│       │       ├── application.yml            # Base application configuration
│       │       ├── application-local.yml      # Local dev profile configuration
│       │       ├── application-prod.yml       # Production-enforced configuration
│       │       ├── static/index.html          # Embedded Single Page Application (Tailwind + Vanilla JS)
│       │       └── db/migration/              # Flyway SQL migrations (V1, V2, V3, V4)
│       └── test/
│           ├── java/com/kartgen/hireflow/     # 30 test suites across all layers (281 automated tests)
│           └── resources/
│               └── application-test.yml       # Isolated test configuration profile
├── hireflow-extension/                # Chrome Extension (Manifest V3) for candidate resume intake
│   ├── manifest.json                  # Extension manifest (MV3, minimal least-privilege permissions)
│   ├── background.js                  # Background service worker with deduplication hash check & upload
│   ├── content.js                     # Content script for webmail/ATS resume detection and scoring
│   ├── popup.html                     # Extension UI popup
│   ├── popup.js                       # UI controller for manual upload, token auth, and candidate review
│   └── test/
│       └── extension.test.js          # Node.js automated test suite (magic bytes, trap words, scoring)
├── .gitignore                         # Comprehensive git ignore rules
├── README.md                          # Quick start guide, architecture overview, developer manual
└── REPOSITORY_STRUCTURE.md           # This document
```
