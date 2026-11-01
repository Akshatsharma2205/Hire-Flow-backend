# AGENTS.md — HireFlow Engineering Rules

## 1. Project Mission

HireFlow is a modular recruitment platform focused on:

- Mailbox-based candidate ingestion.
- Resume extraction and validation.
- Candidate identity and deduplication.
- Job and candidate matching.
- Recruitment pipelines and applications.
- Multi-tenant security.
- Transparent background processing.
- Extensible AI-powered recruitment capabilities.

The goal is a maintainable, secure, cloud-portable backend that can serve multiple independent frontend applications.

Do not optimize for code volume, number of classes, number of features, or architectural complexity.

Optimize for correctness, simplicity, reliability, maintainability and real user value.

## 2. Source of Truth

Before making any changes, inspect the current repository state.

Use this order of trust:

1. Actual source code.
2. Database migrations and entity definitions.
3. Automated test assertions and actual test output.
4. Runtime logs and observed HTTP behavior.
5. CI workflow results.
6. Technical documentation.

Never assume that a previous agent's report accurately describes the current code.

Documentation must be reconciled with the actual implementation.

Never fabricate:
- Test results.
- API responses.
- Runtime behavior.
- Security findings.
- Dependency scan results.
- Git operations.
- Deployment status.

When something cannot be verified, explicitly label it NOT VERIFIED.

## 3. Engineering Roles

**Product owner:** The user defines product priorities, approves architecture-level changes and decides whether a feature belongs in the project.

**Lead engineering agent:** Owns the task plan, repository inspection, technical coordination, implementation integration and final evidence report.

**Implementation agents:** Work on explicitly assigned, bounded tasks. They must not modify the same files simultaneously.

**Independent reviewer:** Reviews the actual implementation and evidence. The implementation agent cannot independently approve its own security-sensitive work.

No agent may expand the approved task scope without justification and approval.

## 4. Architecture Rules

- Preserve the modular monolith architecture.
- Keep domain logic independent of frontend implementation.
- Use stable interfaces for external providers.
- Prefer dependency inversion and clear package boundaries.
- Keep provider-specific logic in adapters.
- Keep configuration externalized.
- Preserve compatibility with documented API contracts.
- Use PostgreSQL as the primary production database.
- Keep H2 restricted to local development and appropriate tests.
- Keep storage behind StoragePort.
- Preserve tenant ownership and authorization boundaries.

Do not introduce microservices, Kafka, Kubernetes, Redis or other infrastructure without a concrete requirement, measured benefit and explicit approval.

Do not create unnecessary interfaces or abstract classes merely to appear extensible.

Do not introduce a new dependency without checking its maintenance status, license, transitive dependencies, performance and vulnerability history.

## 5. Scope Discipline

Every engineering task must define:

- Problem being solved.
- Expected user or system benefit.
- Explicit scope.
- Non-goals.
- Acceptance criteria.
- Files or modules expected to change.
- Security implications.
- Required tests.
- Rollback or recovery considerations.

Do not perform opportunistic refactoring unrelated to the approved objective.

Do not rewrite a working subsystem when a smaller correction is sufficient.

Do not create new modules simply because an existing class is inconvenient to understand.

Avoid large, unrelated diffs. A large change must have a clear technical justification.

## 6. Mandatory Change Workflow

Before implementation:

1. Inspect Git status, current branch and latest commit.
2. Inspect relevant implementation and tests.
3. Run the applicable baseline tests.
4. Identify compatibility and security risks.
5. Prepare the smallest viable implementation plan.

During implementation:

1. Change only the approved scope.
2. Preserve existing public contracts unless explicitly changing them.
3. Add regression tests for every confirmed defect.
4. Validate database, transaction and authorization implications.
5. Update relevant documentation.
6. Keep configuration examples free of real secrets.

After implementation:

1. Run targeted tests.
2. Run the full applicable regression suite.
3. Run security and dependency checks.
4. Inspect the complete diff.
5. Verify that unrelated code was not changed.
6. Obtain one independent focused review.
7. Produce an evidence-based completion report.

Do not start another unrestricted audit cycle after a bounded task has passed its acceptance criteria.

If a confirmed critical or high-severity issue remains, address it. Track lower-priority observations rather than continuously reopening completed work.

## 7. Security and Data Protection

- Preserve organization and tenant isolation.
- Enforce authorization on backend operations.
- Never trust frontend-supplied tenant IDs or role names.
- Never log passwords, tokens, refresh tokens, API keys, private keys or encryption keys.
- Never commit real credentials or generated production secrets.
- Never weaken cryptography or authentication to make a test pass.
- Use secure random generation for credentials.
- Encrypt stored OAuth and mailbox credentials.
- Keep sensitive resume and candidate data out of debug logs.
- Validate uploaded files by content and size, not only filename.
- Keep rate limits appropriate to the resource and tenant.
- Require explicit authorization for sensitive data access.
- Keep all development demo credentials unavailable in production.

Security-sensitive changes require regression tests.

## 8. Database and Transaction Rules

- Use additive Flyway migrations for schema changes.
- Do not casually edit migrations that have already been applied.
- Verify foreign keys, unique constraints, indexes and tenant ownership.
- Verify transaction rollback behavior.
- Verify duplicate and concurrency behavior.
- Verify that database state and physical storage remain consistent.
- Test production-critical database behavior against real PostgreSQL in CI (Note: containerized PostgreSQL testing in CI is tracked as unfinished work; current verification relies on in-memory H2 in PostgreSQL compatibility mode).
- Do not treat H2 compatibility mode as proof of PostgreSQL correctness.

## 9. API and Frontend Independence

- Backend business logic must not depend on a particular frontend.
- Maintain versioned REST API contracts.
- Maintain OpenAPI documentation.
- Use consistent authentication and authorization rules across clients.
- Document request and response models.
- Provide consistent error responses.
- Validate input at API boundaries.
- Configure CORS using explicit deployment origins.
- Do not expose secrets or privileged operations to browser clients.
- Do not put provider refresh tokens in frontend code or extension storage.

A frontend replacement must not require rewriting the recruitment domain services.

## 10. Performance and Resource Discipline

Do not optimize based on assumptions.

Measure before making substantial performance changes.

Pay particular attention to:

- Memory allocation during resume parsing.
- Attachment size and concurrency.
- Database connection pool usage.
- Query counts and N+1 queries.
- Worker concurrency.
- Mailbox provider rate limits.
- Storage upload/download memory use.
- Retry behavior.
- Queue age and backlog.

Use bounded concurrency, streaming where appropriate, pagination and backpressure.

Do not load an entire mailbox or large document collection into memory.

Do not introduce infrastructure merely to handle hypothetical future scale.

## 11. Observability and Operational Reliability

Important processing operations must be traceable.

Maintain useful correlation IDs, processing status and sanitized error information.

Capture meaningful application metrics, including:

- API request latency and failures.
- Mailbox synchronization failures.
- Resume extraction success/failure.
- Processing queue depth and oldest-job age.
- Retry and dead-letter counts.
- Storage failures.
- Authentication and rate-limit events.

Never use high-cardinality candidate identifiers or sensitive personal information as metric labels.

Document health checks, backup strategy, restoration procedures and operational alerts.

## 12. Testing Rules

Test actual behavior.

Use appropriate layers:

- Unit tests for domain rules.
- Integration tests for repositories and services.
- HTTP tests for API contracts and security.
- PostgreSQL-backed tests for database-critical behavior (tracked as unfinished work in CI; current suite runs on in-memory H2).
- Browser E2E tests for real frontend interactions.
- Extension tests for browser integration behavior.
- Failure-injection tests for transactions and recovery.

A mocked frontend request is not a real frontend-to-backend E2E test.

Compilation is not runtime verification.

A skipped test is not a passing test.

Never delete or weaken a meaningful test simply to obtain a passing build.

## 13. Dependency and CI Quality Gates

### Active CI Quality Gates (Enforced in `.github/workflows/ci.yml`)
CI currently executes automated gates for:
- Backend compilation, artifact packaging, and test execution (Java 21, Spring Boot 3.3.5, 31 suites / 282 tests against in-memory H2 in PostgreSQL compatibility mode).
- Browser extension unit and security verification (Node.js 20, 11 tests).
- Repository hygiene and uncommitted build artifact detection (`target/`, `.jdk/`, `.local-storage/`).
- Baseline high-risk secret scanning via regex heuristics (private keys, AWS access key IDs, GitHub tokens).
- Documentation completeness verification (validating required engineering documentation suite).

### Tracked Unfinished CI Gates (Future Enhancements)
The following quality gates are acknowledged architectural gaps and are tracked as unfinished work:
- Containerized PostgreSQL integration testing in CI (testing against real PostgreSQL instances rather than H2 compatibility mode).
- Real automated dependency vulnerability scanning (e.g., OWASP Dependency-Check, Snyk, Trivy).
- CodeQL static application security testing (SAST) and automated vulnerability scanning.
- Comprehensive secret detection across git commit history (e.g., Gitleaks, TruffleHog).
- Container image scanning (pending production containerization pipeline).

Critical and high-risk findings must block release unless an explicitly documented exception is approved.

Keep CI efficient through caching, parallel jobs and appropriate scheduling.

Do not claim comprehensive secret detection or dependency vulnerability scanning until real tools are integrated into the pipeline.

## 14. Documentation Rules

Keep these documents consistent with the actual implementation:

- README.md
- AGENTS.md
- docs/AGENT_GUIDE.md
- docs/ARCHITECTURE.md
- docs/API_REFERENCE.md
- docs/DATABASE.md
- docs/SECURITY.md
- docs/TESTING.md
- docs/DEPLOYMENT.md
- docs/OPERATIONS.md

For meaningful new classes, document responsibility and important behavior.

For new APIs, update the OpenAPI contract.

For new database relationships, update the database reference.

For architectural decisions, add an Architecture Decision Record (ADR).

Prefer generated API references and maintainable architecture documentation over enormous manually duplicated class descriptions.

## 15. Final Reporting Rules

The final response must report:

- Actual files changed.
- Reason for every meaningful architectural change.
- Commands executed.
- Actual exit codes.
- Actual test counts.
- Failed and skipped tests.
- Actual security scan results.
- API compatibility impact.
- Database migration impact.
- Remaining limitations.
- Commit SHA when committed.
- CI URL when executed.

Use PASS, FAIL, NOT RUN and NOT VERIFIED accurately.

Never claim production readiness based solely on unit tests or successful compilation.

## 16. Final Principle

HireFlow must remain understandable to its next developer and its next AI engineering agent.

A new developer or agent should be able to identify where a feature belongs, how it interacts with the system, what contracts it must preserve, and how to test it.

Prefer small, verified and maintainable improvements over large speculative rewrites.

The objective is not to write more code.

The objective is to build a reliable recruitment platform without losing control of its complexity.
