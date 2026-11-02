# Project: HireFlow — Google & Microsoft OAuth Mailbox Integration

## Architecture
- **Backend**: Spring Boot 3.3.5 / Java 21 modular monolith with PostgreSQL (H2 in PostgreSQL mode for tests).
  - Package `com.kartgen.hireflow.mail.channel`: REST controllers, OAuth authorization code flow handlers, synchronization adapters.
  - Package `com.kartgen.hireflow.mail.model`: `MailConnection` entity, repository, and new `OAuthAuthorizationAttempt` entity/repository.
  - Package `com.kartgen.hireflow.security`: `SecurityConfig` (public callback route), `TenantContext`, `JwtAuthenticationFilter`.
  - Database: Flyway additive migration `V5__oauth_authorization_attempts.sql`.
- **Frontend**: Embedded Tailwind CSS / Vanilla JS Single-Page Application (`hireflow-backend/src/main/resources/static/index.html`).
  - One-click OAuth connect buttons for Google Workspace and Microsoft 365.
  - Popup window flow (`window.open` + `postMessage`) with full-page redirect fallback.
  - Reauthorization status alerts (`REAUTH`) and sync status indicators.
  - Complete removal of manual token inputs (`#refreshToken`, `#accessToken`).
- **Browser Extension**: Manifest V3 Chrome Extension (`hireflow-extension/`) — preserved, 11/11 tests pass.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Database Migration V5 | Additive Flyway migration `V5__oauth_authorization_attempts.sql` for state, PKCE verifiers, tenant/user binding, and TTL | M1 | Survey Backend & Infra |
| 2 | OAuth State Entity & Repository | `OAuthAuthorizationAttempt` entity and JPA repository matching V5 DDL identically | M1 | Survey Backend & Infra |
| 3 | OAuth Initiation Endpoint | `GET /api/v1/mail-connections/oauth/{provider}/authorize` generating unpredictable single-use state & PKCE | M1 | Survey Backend |
| 4 | OAuth Callback & Token Exchange | `GET /api/v1/mail-connections/oauth/{provider}/callback` server-side exchange, mailbox identity resolution | M1 | Survey Backend |
| 5 | Refresh Token Preservation | Prevent overwriting existing valid refresh tokens when Google omits refresh token on reauthorization | M1 | Survey Backend |
| 6 | Refresh Token Rotation in Sync | Update `OAuthMailSyncAdapter.postToken` to persist rotated refresh tokens during mailbox sync | M1 | Survey Backend |
| 7 | SecurityConfig Public Callback | Configure `permitAll` for OAuth callback endpoints without breaking JWT authorization | M1 | Survey Infra & Backend |
| 8 | Remove Manual Token Fields | Delete `#oauthFields`, `#refreshToken`, and `#accessToken` from `index.html` | M2 | Survey Frontend |
| 9 | One-Click Connect Buttons | Implement Google Workspace and Microsoft 365 connect buttons in `index.html` with popup & redirect flow | M2 | Survey Frontend |
| 10 | Reauthorization Alert & UX | Display `REAUTH` alert with inline "Reauthorize Now" button, humanized sync timestamps | M2 | Survey Frontend |
| 11 | Engineering Documentation | Update `DEPLOYMENT.md`, `ARCHITECTURE.md`, `API_REFERENCE.md`, `SECURITY.md`, `TESTING.md`, `DATABASE.md` | M3 | Survey Infra |
| 12 | Opaque-Box E2E Test Suite | 4-tier requirement-driven test suite with mocked OAuth providers and runner | E2E-Track | Dual Track Requirement |
| 13 | Final E2E Pass & Hardening | Pass 100% of E2E tests (Tiers 1-4) and execute Tier 5 adversarial hardening | M4 | Final Milestone Requirement |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Backend OAuth Core & Persistence | Flyway V5, `OAuthAuthorizationAttempt`, OAuth initiation & callback endpoints, token exchange, refresh token preservation, rotation, `SecurityConfig`, unit/mock tests | none | DONE |
| M2 | Frontend Mailbox Connection Experience | `index.html` UI overhaul: remove manual token inputs, add one-click buttons, popup/redirect listener, `REAUTH` alerts, status formatting | M1 | DONE |
| M3 | Documentation & Security Audit | Synchronize 6 documentation files with OAuth configs, scopes, redirect URIs, CASA details; verify 0 token leakage in logs/APIs | M1, M2 | DONE |
| M4 | Final E2E Verification & Hardening | Verify 100% pass of E2E test suite (Tiers 1-4) and execute Tier 5 adversarial hardening | M1, M2, M3, E2E-Track | DONE |

## Parallel Tracks
- **E2E Testing Track**: Dispatched in parallel with M1. Designs opaque-box test suite for features 1–10 according to Category-Partition, BVA, Pairwise, and Real-World workflows. Outputs `TEST_INFRA.md` and signals readiness via `TEST_READY.md`.

## Interface Contracts
### Backend OAuth Initiation API
- `GET /api/v1/mail-connections/oauth/{provider}/authorize`
- Headers: `Authorization: Bearer <jwt>`
- Query Params: `provider` (in path: `google` or `microsoft`)
- Response: `200 OK` `{ "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=...&state=...", "state": "<stateToken>" }`
- Error: `400 Bad Request` if provider unsupported or not configured.

### Backend OAuth Callback API
- `GET /api/v1/mail-connections/oauth/{provider}/callback`
- Public endpoint (`permitAll` in `SecurityConfig`).
- Query Params: `code` (auth code), `state` (state token), optional `error`, `error_description`.
- Processing:
  1. Atomically consume `OAuthAuthorizationAttempt` by `state`. Verify status is `PENDING` and `expiresAt > now`.
  2. Exchange code with provider token endpoint using client secret and PKCE `code_verifier`.
  3. Fetch mailbox address from provider profile API (`/users/me/profile` for Google, `/me` for Microsoft).
  4. Upsert `MailConnection` for `(organizationId, provider, mailboxAddress)`. If existing connection has valid `refreshToken` and provider response omits it, PRESERVE existing `refreshToken`.
  5. Mark attempt `CONSUMED`.
- Response: HTML page executing `window.opener.postMessage({ type: 'HIREFLOW_OAUTH_CALLBACK', status: 'SUCCESS', provider: '...', mailboxAddress: '...' }, window.location.origin); window.close();` with fallback redirect to `/index.html?oauth=success`.
- Error: HTML page executing `postMessage` with `{ type: 'HIREFLOW_OAUTH_CALLBACK', status: 'ERROR', error: '...' }` or redirect to `/index.html?oauth=error&msg=...`.

### OAuth Sync Adapter Token Rotation
- `OAuthMailSyncAdapter.postToken`: If response JSON contains `refresh_token`, update encrypted `oauth_secret_ref` in `MailConnection` to store the rotated refresh token.

## Code Layout
- `hireflow-backend/src/main/resources/db/migration/V5__oauth_authorization_attempts.sql` (Flyway migration)
- `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/model/OAuthAuthorizationAttempt.java` (Entity)
- `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/model/OAuthAuthorizationAttemptRepository.java` (Repository)
- `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/OAuthMailController.java` (or methods in `MailConnectionController`)
- `hireflow-backend/src/main/java/com/kartgen/hireflow/mail/channel/OAuthMailSyncAdapter.java` (Sync adapter update)
- `hireflow-backend/src/main/java/com/kartgen/hireflow/security/SecurityConfig.java` (PermitAll callback route)
- `hireflow-backend/src/main/resources/static/index.html` (Frontend SPA)
- `docs/DEPLOYMENT.md`, `docs/ARCHITECTURE.md`, `docs/API_REFERENCE.md`, `docs/SECURITY.md`, `docs/TESTING.md`, `docs/DATABASE.md`

