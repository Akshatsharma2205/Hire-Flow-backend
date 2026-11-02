# TEST_INFRA.md — HireFlow OAuth E2E Test Infrastructure & Specification

## 1. Executive Summary & Testing Philosophy

HireFlow implements backend-managed OAuth 2.0 authorization code flows for Google Workspace (Gmail) and Microsoft 365 (Microsoft Graph) mailboxes. Connecting a corporate mailbox grants automated ingestion capabilities for candidate resumes while safeguarding organizational secrets and enforcing strict multi-tenant boundaries.

The testing infrastructure for HireFlow OAuth Mailbox Integration follows an **opaque-box, requirement-driven, 4-tier methodology**. Tests interact with the system strictly through its documented external interface contracts (HTTP REST APIs, database state validation, and mocked external provider boundaries), ensuring complete decoupling from implementation details.

### Core Testing Tenets
1. **Opaque-Box Verification**: Exercise functionality through public API boundaries (`/api/v1/mail-connections/oauth/**` and `/api/v1/mail-connections/**`), evaluating HTTP response status codes, headers, response bodies, and encrypted persistence state.
2. **Deterministic Mocking**: Isolate external OAuth 2.0 authorization servers and graph APIs (Google and Microsoft) using Spring's `MockRestServiceServer` or mock HTTP endpoints. Never execute unmocked calls across external networks during automated test runs.
3. **Multi-Tenant Integrity**: Validate that authorization requests, state tokens, PKCE verifiers, and created connections strictly bind to the authenticated tenant and user, preventing cross-tenant leakage or session fixation.
4. **Zero Secret Leakage**: Assert that authorization codes, plaintext access tokens, and refresh tokens are never exposed in log events, API responses, or stored in plaintext database columns.
5. **Robust Replay and Failure Resistance**: Verify that single-use states cannot be replayed, expired tokens reject cleanly, provider errors are mapped to user-actionable diagnostics, and existing refresh tokens are preserved during reauthorization.

---

## 2. Feature Inventory & Test Mapping

The test suite directly covers all inventoried OAuth features specified in `PROJECT.md`:

| # | Feature Name | Description | Test Tier | Test Coverage Target |
|---|--------------|-------------|-----------|----------------------|
| 1 | Database Migration V5 | Flyway migration `V5__oauth_authorization_attempts.sql` table schema, constraints, and indices | Tier 1 & 2 | Attempt persistence, status transitions, TTL enforcement |
| 2 | OAuth State Entity & Repository | `OAuthAuthorizationAttempt` entity, repository methods, and atomic status transitions | Tier 1 & 2 | Single-use consumption, lookup by state token |
| 3 | OAuth Initiation Endpoint | `GET /api/v1/mail-connections/oauth/{provider}/authorize` generating state & PKCE | Tier 1 & 2 | Google & Microsoft auth URLs, PKCE code challenge, authentication check |
| 4 | OAuth Callback & Token Exchange | `GET /api/v1/mail-connections/oauth/{provider}/callback` server-side exchange & profile resolution | Tier 1 & 2 | Token exchange, mailbox profile lookup, connection creation, postMessage response |
| 5 | Refresh Token Preservation | Preserving existing valid refresh token when Google omits `refresh_token` on re-consent | Tier 3 | Reauthorization without `refresh_token` preserves previous secret |
| 6 | Refresh Token Rotation in Sync | Persisting rotated refresh token during `OAuthMailSyncAdapter.syncConnection` | Tier 3 | Rotated refresh token stored encrypted in `oauth_secret_ref` |
| 7 | SecurityConfig Public Callback | Public access (`permitAll`) for callback endpoints without breaking JWT filter | Tier 1 | Anonymous callback access succeeds without 401 |
| 8 | Remove Manual Token Fields | Frontend removal of manual access/refresh token input fields | Tier 4 (Contract) | Backend rejects manual token inputs without OAuth flow |
| 9 | One-Click Connect Buttons | Frontend popup and redirect initiation contracts | Tier 4 | PostMessage and redirect query parameters formatted for frontend consumption |
| 10 | Reauthorization Alert & UX | `REAUTH` connection status propagation when tokens are revoked | Tier 3 & 4 | Sync failure transitions connection to `REAUTH` |
| 11 | Engineering Documentation | Contract synchronization across architecture, security, and deployment guides | Tier 1–4 | Verifies conformance with documented endpoints and configuration keys |
| 12 | Opaque-Box E2E Test Suite | Automated test harness in `OAuthMailConnectionE2ETest.java` | Tiers 1–4 | Automated execution via `mvn test` |
| 13 | Final E2E Pass & Hardening | Regression protection and adversarial security verification | All Tiers | 100% test pass rate with zero flaky tests |

---

## 3. The 4-Tier Test Architecture

The end-to-end integration test suite is structured into four distinct test tiers designed for progressive verification, diagnostic clarity, and complete coverage.

```
+-------------------------------------------------------------------------+
| Tier 4: Real-World Scenarios                                            |
|   - Complete HR User Google Workspace Connection Lifecycle             |
|   - Complete Enterprise Microsoft 365 Lifecycle (PKCE & Rotation)       |
|   - Failed Provider Consent & User Recovery Workflow                    |
|   - Token Revocation, REAUTH Detection & Self-Healing Reauthorization   |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 3: Cross-Feature Combinations                                      |
|   - Reauthorization with Refresh Token Preservation                     |
|   - Rotated Refresh Token Persistence during Mailbox Synchronization    |
|   - Duplicate Mailbox Upsertion & Conflict Resolution                   |
|   - Expired Access Token Auto-Refresh During Sync                       |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 2: Boundary & Corner Cases                                         |
|   - Expired OAuth State Token Rejection                                 |
|   - Replayed / Already-Consumed State Token Attack                      |
|   - Tampered / Unknown State Token Rejection                            |
|   - Tenant / Provider Mismatch Detection                                |
|   - User Cancellation (`error=access_denied`) Graceful Handling         |
|   - Provider Token Exchange Outage (HTTP 500 / 400)                     |
|   - Provider Profile Fetch Failure (HTTP 401 / 404)                     |
|   - Unsupported Provider Rejection (HTTP 400)                           |
|   - Unauthenticated Initiation Rejection (HTTP 401)                     |
+-------------------------------------------------------------------------+
                                    |
+-------------------------------------------------------------------------+
| Tier 1: Feature Coverage (Happy Path)                                   |
|   - Google Authorization URL Generation & State Binding                 |
|   - Microsoft Authorization URL Generation with RFC 7636 PKCE S256      |
|   - Google OAuth Callback Token Exchange & Mailbox Auto-Discovery       |
|   - Microsoft OAuth Callback Token Exchange & UPN / Mail Fallback       |
|   - Public Callback Endpoint (`permitAll`) Accessibility                |
|   - HTML PostMessage Callback Payload & Redirect Fallback               |
+-------------------------------------------------------------------------+
```

---

### Tier 1: Feature Coverage (Happy Path)

Tier 1 validates the baseline functional capabilities under standard operating conditions.

#### Test Cases:
1. **`testGoogleAuthorizationUrlGeneration_HappyPath`**:
   - **Input**: Authenticated `GET /api/v1/mail-connections/oauth/google/authorize` with HR credentials.
   - **Expected Output**: HTTP 200 OK containing JSON with `authorizationUrl` and `state`.
   - **Assertions**:
     - `authorizationUrl` points to `https://accounts.google.com/o/oauth2/v2/auth`.
     - Parameters include: `client_id`, `redirect_uri`, `response_type=code`, `access_type=offline`, `prompt=consent`.
     - Scope contains `https://www.googleapis.com/auth/gmail.readonly`.
     - `state` parameter is cryptographically secure (>= 32 random characters), matching the returned `state` field.
     - Database record created in `oauth_authorization_attempts` with status `PENDING`, matching `tenant_id` and `user_id`.

2. **`testMicrosoftAuthorizationUrlGeneration_WithPkce_HappyPath`**:
   - **Input**: Authenticated `GET /api/v1/mail-connections/oauth/microsoft/authorize`.
   - **Expected Output**: HTTP 200 OK with `authorizationUrl` and `state`.
   - **Assertions**:
     - `authorizationUrl` points to `https://login.microsoftonline.com/common/oauth2/v2.0/authorize` (or configured tenant).
     - Parameters include: `client_id`, `redirect_uri`, `response_type=code`, `response_mode=query`.
     - Scope includes `offline_access`, `https://graph.microsoft.com/Mail.Read`, `User.Read`.
     - PKCE parameters present: `code_challenge` and `code_challenge_method=S256`.
     - Database record stores `code_verifier` corresponding to the SHA-256 base64url-encoded `code_challenge`.

3. **`testGoogleCallbackTokenExchange_HappyPath`**:
   - **Input**: Public `GET /api/v1/mail-connections/oauth/google/callback?code=mock_google_code&state={validState}`.
   - **Mock Setup**:
     - Token endpoint `POST https://oauth2.googleapis.com/token` returns `{"access_token":"ya29.mock","refresh_token":"1//mock_refresh","expires_in":3599,"token_type":"Bearer"}`.
     - Profile endpoint `GET https://gmail.googleapis.com/gmail/v1/users/me/profile` returns `{"emailAddress":"hr-lead@kartgen.com","historyId":"1001"}`.
   - **Expected Output**: HTTP 200 OK returning HTML payload executing `window.opener.postMessage(...)` with `status: "SUCCESS"` and `mailboxAddress: "hr-lead@kartgen.com"`.
   - **Assertions**:
     - MailConnection created with `provider: "GMAIL_OAUTH"`, `mailbox_address: "hr-lead@kartgen.com"`, `status: "ACTIVE"`.
     - Credentials in `oauth_secret_ref` are encrypted with AES-256-GCM.
     - State attempt marked `CONSUMED`.

4. **`testMicrosoftCallbackTokenExchange_WithMailboxDiscovery_HappyPath`**:
   - **Input**: Public `GET /api/v1/mail-connections/oauth/microsoft/callback?code=mock_ms_code&state={validState}`.
   - **Mock Setup**:
     - Token endpoint `POST https://login.microsoftonline.com/common/oauth2/v2.0/token` validates `code_verifier` in request body and returns tokens.
     - Profile endpoint `GET https://graph.microsoft.com/v1.0/me` returns `{"mail":"recruiting@kartgen.onmicrosoft.com","userPrincipalName":"recruiting@kartgen.com"}`.
   - **Expected Output**: HTTP 200 OK returning HTML postMessage response.
   - **Assertions**:
     - MailConnection created with `provider: "MS_GRAPH_OAUTH"`, `mailbox_address: "recruiting@kartgen.onmicrosoft.com"`.
     - Verified that PKCE `code_verifier` was passed in the POST request to Microsoft.

5. **`testCallbackPublicAccess_NoAuthenticationHeaderRequired`**:
   - **Input**: Callback request executed without `Authorization: Bearer <token>` header.
   - **Expected Output**: Endpoint does not return HTTP 401/403; executes the OAuth validation logic cleanly.

---

### Tier 2: Boundary & Corner Cases

Tier 2 verifies system defense mechanisms against malformed inputs, race conditions, expired tokens, tampered state, and external service degradations.

#### Test Cases:
1. **`testCallbackRejection_ExpiredStateToken`**:
   - **Setup**: `OAuthAuthorizationAttempt` with `expires_at` in the past (e.g., 10 minutes ago).
   - **Input**: `GET /api/v1/mail-connections/oauth/google/callback?code=mock_code&state={expiredState}`.
   - **Expected Output**: HTTP 200 HTML with `status: "ERROR"`, error message describing expired authorization session, or redirect to `/index.html?oauth=error`.
   - **Assertions**: No token exchange attempted, no MailConnection created, attempt remains unconsumed or marked expired.

2. **`testCallbackRejection_ReplayedStateToken`**:
   - **Setup**: `OAuthAuthorizationAttempt` already marked `CONSUMED`.
   - **Input**: Replayed callback request with same `state`.
   - **Expected Output**: Callback rejected with error; token exchange not triggered.

3. **`testCallbackRejection_UnknownOrTamperedState`**:
   - **Input**: `GET /api/v1/mail-connections/oauth/google/callback?code=mock_code&state=tampered-or-unknown-uuid`.
   - **Expected Output**: Rejection with invalid state error. Zero database mutations.

4. **`testCallbackRejection_ProviderMismatch`**:
   - **Setup**: State generated for Google provider (`provider = "google"`).
   - **Input**: Callback sent to `/api/v1/mail-connections/oauth/microsoft/callback?code=mock_code&state={googleState}`.
   - **Expected Output**: Rejection due to provider mismatch.

5. **`testCallbackHandling_UserCancelledConsent`**:
   - **Input**: `GET /api/v1/mail-connections/oauth/google/callback?error=access_denied&error_description=User+denied+access&state={validState}`.
   - **Expected Output**: HTTP 200 HTML executing postMessage with `status: "ERROR"` and `error: "access_denied"`.
   - **Assertions**: Attempt marked consumed or failed; zero exceptions thrown.

6. **`testCallbackHandling_TokenEndpointHttp500Failure`**:
   - **Setup**: Mock token endpoint returns HTTP 500 Internal Server Error.
   - **Input**: Callback request with valid state and code.
   - **Expected Output**: HTTP 200 HTML postMessage with `status: "ERROR"`. No MailConnection created.

7. **`testCallbackHandling_ProfileEndpointFailure`**:
   - **Setup**: Token exchange succeeds, but profile endpoint returns HTTP 404 or invalid JSON.
   - **Input**: Callback request.
   - **Expected Output**: Handled gracefully with error response.

8. **`testInitiationRejection_UnauthenticatedUser`**:
   - **Input**: `GET /api/v1/mail-connections/oauth/google/authorize` without Authorization header.
   - **Expected Output**: HTTP 401 Unauthorized.

9. **`testInitiationRejection_UnsupportedProvider`**:
   - **Input**: `GET /api/v1/mail-connections/oauth/unsupported_provider/authorize`.
   - **Expected Output**: HTTP 400 Bad Request.

---

### Tier 3: Cross-Feature Combinations

Tier 3 exercises interactions across multiple subsystems: OAuth flow, credential encryption, token rotation, and mailbox synchronization.

#### Test Cases:
1. **`testReauthorization_PreservesExistingRefreshToken_WhenGoogleOmitsIt`**:
   - **Scenario**: An organization reconnects an existing Google mailbox. Google omits `refresh_token` because consent was previously granted.
   - **Setup**: Existing `MailConnection` with encrypted credentials `{"accessToken":"old_access","refreshToken":"existing_valid_refresh_token","expiresAt":1000}`.
   - **Action**: Execute callback for same mailbox address where Google token endpoint returns `{"access_token":"new_access","expires_in":3600}` (no `refresh_token`).
   - **Expected Output & Verification**:
     - Connection updated in database.
     - Decrypted credentials contain `accessToken = "new_access"` and PRESERVE `refreshToken = "existing_valid_refresh_token"`.
     - Connection status remains `ACTIVE`.

2. **`testMailboxSync_HandlesTokenRefreshAndRotatedRefreshToken`**:
   - **Scenario**: During scheduled or manual sync (`POST /api/v1/mail-connections/{id}/sync-now`), stored access token is expired.
   - **Setup**: Connection credentials have expired `expiresAt`. Mock token refresh endpoint returns a new `access_token` and a rotated `refresh_token`. Mock mail API returns empty message list.
   - **Action**: Invoke `POST /api/v1/mail-connections/{id}/sync-now`.
   - **Expected Output & Verification**:
     - Sync completes with status `ACTIVE`.
     - Decrypted credentials in database reflect BOTH new access token and rotated refresh token.

3. **`testMailboxSync_TransitionsToReauth_WhenTokenRevoked`**:
   - **Scenario**: Provider returns HTTP 401 Unauthorized during token refresh or API call.
   - **Action**: Trigger sync-now.
   - **Expected Output & Verification**:
     - Response indicates `status: "REAUTH"`.
     - MailConnection in database has `status = "REAUTH"`.
     - Subsequent sync attempts are blocked with clear instructions to re-authenticate.

4. **`testDuplicateMailboxConnection_UpsertsExistingRecord`**:
   - **Scenario**: Connecting an already connected mailbox address for the same organization.
   - **Action**: Complete OAuth callback for the same mailbox address.
   - **Expected Output & Verification**:
     - Existing record is updated; no duplicate connection rows created.
     - Unique constraint `(organization_id, provider, mailbox_address)` is maintained.

---

### Tier 4: Real-World Scenarios

Tier 4 validates complete end-to-end user journeys simulating real-world enterprise operations.

#### Test Scenarios:
1. **Scenario 1: Complete Google Workspace HR Onboarding Lifecycle**:
   - Step 1: HR Administrator initiates Google connection (`GET .../authorize`).
   - Step 2: System yields authorization URL with required scopes (`gmail.readonly`, `offline_access`).
   - Step 3: Provider callback executed (`GET .../callback`).
   - Step 4: Mailbox discovered, connection established in `ACTIVE` state.
   - Step 5: Connection listed in `GET /api/v1/mail-connections`.
   - Step 6: HR triggers manual sync (`POST .../sync-now`). Mock messages and resumes ingested.
   - Step 7: Connection verifies updated `lastSuccessAt`.
   - Step 8: HR pauses connection (`DELETE .../{id}`). Status transitions to `PAUSED`.

2. **Scenario 2: Complete Enterprise Microsoft 365 Lifecycle (PKCE & Rotation)**:
   - Step 1: HR Administrator initiates Microsoft connection with PKCE S256 challenge.
   - Step 2: System verifies `code_verifier` persists in attempt record.
   - Step 3: Provider callback exchanges code and verifies `code_verifier` transmitted.
   - Step 4: Discovers mailbox via `mail` or `userPrincipalName`.
   - Step 5: Multi-page delta sync exercises token refresh with rotation.
   - Step 6: Validates encrypted secret storage at rest.

3. **Scenario 3: User Consent Denial & Self-Recovery**:
   - Step 1: User clicks connect, but clicks "Cancel" on Google consent screen.
   - Step 2: Callback receives `error=access_denied`.
   - Step 3: UI receives actionable cancellation message. No connection created.
   - Step 4: User immediately clicks connect again, grants consent, and successfully establishes connection.

4. **Scenario 4: Token Revocation, REAUTH Detection, & Reauthorization**:
   - Step 1: Active connection has token revoked by IT administrator in Microsoft 365 Admin Center.
   - Step 2: HireFlow sync encounters 401/403 and marks connection `REAUTH`.
   - Step 3: HR user initiates reauthorization flow.
   - Step 4: Successful callback transitions connection back from `REAUTH` to `ACTIVE`.

---

## 4. Mocking Strategy & Network Isolation

### Technology Selection: `MockRestServiceServer`
To ensure 100% network isolation, deterministic behavior, and zero dependencies on live external providers during CI/CD:
- Spring Boot's `MockRestServiceServer` binds directly to the application's `RestTemplateBuilder` or `RestTemplate` beans.
- For services creating internal `RestTemplate` instances (like `OAuthMailSyncAdapter`), a dedicated test configuration or reflection/spy mechanism intercepts outbound HTTP requests.
- Requests matching Google (`https://oauth2.googleapis.com/**`, `https://gmail.googleapis.com/**`) and Microsoft (`https://login.microsoftonline.com/**`, `https://graph.microsoft.com/**`) are strictly intercepted.
- Any unexpected external HTTP call causes an immediate test assertion failure.

---

## 5. Execution & Verification Guide

### Maven Test Invocations
To run the full E2E OAuth test suite:
```bash
# Run exclusively the OAuth Mail Connection E2E test suite
./mvnw test -Dtest=OAuthMailConnectionE2ETest

# Run targeted tier (using test method name pattern)
./mvnw test -Dtest=OAuthMailConnectionE2ETest#testGoogle*

# Run full project regression suite
./mvnw test
```

### Continuous Integration (CI) Quality Gates
In `.github/workflows/ci.yml`, the test suite runs as part of the backend quality gate:
- Java 21 LTS with Maven.
- Active profile: `test`.
- In-memory H2 in PostgreSQL compatibility mode.
- Verification of zero secret leakage in test logs.

---

## 6. Traceability Matrix

| Requirement | Specification Section | Implemented Test Method |
|-------------|-----------------------|-------------------------|
| R1: Architecture Integration | PROJECT.md § Architecture | `testGoogleCallbackTokenExchange_HappyPath`, `testMailboxSync_HandlesTokenRefresh` |
| R2: Google & Microsoft OAuth Flows | PROJECT.md § Feature Inventory (3, 4) | `testGoogleAuthorizationUrlGeneration_HappyPath`, `testMicrosoftAuthorizationUrlGeneration_WithPkce_HappyPath` |
| R2: State Unpredictability & Single-Use | PROJECT.md § Acceptance Criteria | `testCallbackRejection_ExpiredStateToken`, `testCallbackRejection_ReplayedStateToken` |
| R2: PKCE Verification (RFC 7636) | PROJECT.md § Feature Inventory (3, 4) | `testMicrosoftAuthorizationUrlGeneration_WithPkce_HappyPath`, `testMicrosoftCallbackTokenExchange_WithMailboxDiscovery_HappyPath` |
| R2: Refresh Token Preservation | PROJECT.md § Feature Inventory (5) | `testReauthorization_PreservesExistingRefreshToken_WhenGoogleOmitsIt` |
| R2: Provider Error Handling | PROJECT.md § Acceptance Criteria | `testCallbackHandling_UserCancelledConsent`, `testCallbackHandling_TokenEndpointHttp500Failure` |
| R3: Frontend postMessage Contract | PROJECT.md § Backend OAuth Callback API | `testGoogleCallbackTokenExchange_HappyPath`, `testCallbackHandling_UserCancelledConsent` |
| R4: Credential Encryption at Rest | PROJECT.md § Acceptance Criteria | `testGoogleCallbackTokenExchange_HappyPath`, `testReauthorization_PreservesExistingRefreshToken_WhenGoogleOmitsIt` |
| R4: Tenant & User Isolation | PROJECT.md § Acceptance Criteria | `testCallbackRejection_TenantMismatch`, `testInitiationRejection_UnauthenticatedUser` |
| R5: Sync Token Rotation | PROJECT.md § Feature Inventory (6) | `testMailboxSync_HandlesTokenRefreshAndRotatedRefreshToken` |

---

## 7. Test Suite Status & Readiness Signal

Once implementation of Milestone 1 (Backend OAuth Core) is active:
1. `OAuthMailConnectionE2ETest.java` compiles cleanly.
2. All test cases in Tiers 1 through 4 pass against the application endpoints.
3. `TEST_READY.md` is authored and published to signal orchestrator and downstream verification agents.
