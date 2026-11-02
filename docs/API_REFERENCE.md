# HireFlow AI — Complete REST API Reference

All REST endpoints in HireFlow are prefixed with `/api/v1` and return JSON payloads unless serving binary document streams. Authentication is enforced via Bearer JWT tokens, Extension intake tokens, or HMAC-SHA256 webhook signatures.

---

## 1. Authentication & Security (`AuthController`)
Base Path: `/api/v1/auth`

### `POST /api/v1/auth/login`
Authenticates a user by email and password, issuing a signed HMAC-SHA256 Bearer JWT.
- **Auth**: Public
- **Request Body**:
  ```json
  {
    "email": "hr@kartgen.com",
    "password": "password123"
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "token": "eyJhbGciOiJIUzUxMiJ9...",
    "email": "hr@kartgen.com",
    "displayName": "HR Admin",
    "organizationId": "11111111-1111-1111-1111-111111111111",
    "organizationName": "Kartgen Infotech LLP",
    "role": "OWNER"
  }
  ```
- **Error Responses**:
  - `400 Bad Request`: Missing or malformed email/password.
  - `401 Unauthorized`: Invalid credentials or sanitized production demo credentials.
  - `403 Forbidden`: User organization is inactive.

### `POST /api/v1/auth/extension-token`
Generates a new secure, high-entropy token (`hf_ext_live_org_...`) for browser extension intake.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`)
- **Response `200 OK`**:
  ```json
  {
    "extensionToken": "hf_ext_live_org_11111111_aBcDeF123456..."
  }
  ```

---

## 2. Candidates Management (`CandidateController`)
Base Path: `/api/v1/candidates`

### `GET /api/v1/candidates`
Lists candidates for the caller's tenant with pagination, keyword search, and skill filtering.
- **Auth**: Bearer JWT
- **Query Params**:
  - `page`: Page index (default: `0`)
  - `size`: Page size (default: `25`, max: `100`)
  - `search`: Name or email substring
  - `jobId`: Optional Job UUID to score candidates against
  - `minExpMonths`: Minimum experience in months
  - `location`: City filter
  - `sourceType`: Ingestion channel filter (`UPLOAD`, `EXT_GMAIL`, `INBOUND_EMAIL`)
  - `skills`: Comma-separated or repeated skill names (e.g. `skills=Java&skills=Spring Boot`)
- **Response `200 OK`**: `PageResponse<CandidateListDto>`
  ```json
  {
    "content": [
      {
        "id": "8430e3b3-3869-4265-a10c-72e798117bf2",
        "displayName": "Jane Doe",
        "primaryEmail": "jane@example.com",
        "currentExperienceMonths": 48,
        "experienceConfidence": "HIGH",
        "currentCity": "San Francisco",
        "skills": ["Java", "Spring Boot", "React"],
        "matchScore": 85.00,
        "resumeVersion": 1,
        "sourceType": "UPLOAD",
        "currentStage": "NEW",
        "lastApplicationAt": "2026-11-01T12:00:00Z"
      }
    ],
    "page": 0,
    "size": 25,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  }
  ```

### `GET /api/v1/candidates/{id}`
Retrieves complete profile details, extracted skills with confidence ratings, and education history.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  {
    "id": "8430e3b3-3869-4265-a10c-72e798117bf2",
    "displayName": "Jane Doe",
    "primaryEmail": "jane@example.com",
    "primaryPhone": "+1-555-0199",
    "linkedinUrl": "https://linkedin.com/in/janedoe",
    "currentExperienceMonths": 48,
    "experienceConfidence": "HIGH",
    "currentCity": "San Francisco",
    "skills": [
      { "name": "Java", "category": "Language", "confidence": 0.95 },
      { "name": "Spring Boot", "category": "Framework", "confidence": 0.90 }
    ],
    "educationJson": "[...]",
    "sourceEvidence": "...",
    "resumeVersionCount": 1,
    "applicationCount": 1,
    "profileUpdatedAt": "2026-11-01T12:00:00Z"
  }
  ```

### `GET /api/v1/candidates/{id}/versions`
Returns all versioned resumes uploaded for this candidate.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  [
    {
      "id": "99999999-9999-9999-9999-999999999999",
      "versionNumber": 1,
      "receivedAt": "2026-11-01T12:00:00Z",
      "parseStatus": "PROCESSED",
      "validationStatus": "VALID",
      "parserVersion": "heuristic-v2"
    }
  ]
  ```

### `GET /api/v1/candidates/{id}/applications`
Returns all job applications submitted by or attached to this candidate.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  [
    {
      "id": "b85a160a-7ef2-4b37-a880-c17a4b852edb",
      "jobTitle": "Senior Full Stack Engineer",
      "currentStage": "SCREENING",
      "status": "ACTIVE",
      "attemptNumber": 1,
      "createdAt": "2026-11-01T12:00:00Z",
      "updatedAt": "2026-11-01T12:05:00Z"
    }
  ]
  ```

---

## 3. Job Requisitions (`JobController`)
Base Path: `/api/v1/jobs`

### `GET /api/v1/jobs`
Lists all job requisitions in the tenant organization.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  [
    {
      "id": "c0000001-0000-0000-0000-000000000001",
      "title": "Senior Backend Engineer",
      "department": "Engineering",
      "location": "Remote",
      "status": "OPEN",
      "currentVersionId": "d0000001-0000-0000-0000-000000000001",
      "createdAt": "2026-11-01T00:00:00Z"
    }
  ]
  ```

### `POST /api/v1/jobs`
Creates a new job requisition and parses required skills from description text.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)
- **Request Body**:
  ```json
  {
    "title": "Full Stack Developer",
    "department": "Product",
    "location": "Bengaluru",
    "rawJdText": "Seeking Java, Spring Boot and React developer with 3+ years experience.",
    "minExperienceMonths": 36,
    "maxExperienceMonths": 72
  }
  ```
- **Response `200 OK`**: Created `JobDto`.

### `GET /api/v1/jobs/{id}/matches`
Returns candidates ranked by computed match score for the requisition.
- **Auth**: Bearer JWT
- **Response `200 OK`**: `PageResponse<MatchDto>`

---

## 4. Pipeline Applications (`ApplicationController`)
Base Path: `/api/v1/applications`

### `POST /api/v1/applications`
Applies a candidate to an open job requisition.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)
- **Request Body**:
  ```json
  {
    "candidateId": "8430e3b3-3869-4265-a10c-72e798117bf2",
    "jobId": "c0000001-0000-0000-0000-000000000001",
    "resumeVersionId": "99999999-9999-9999-9999-999999999999",
    "sourceType": "MANUAL"
  }
  ```
- **Response `200 OK`**: Application created in `NEW` stage.

### `PATCH /api/v1/applications/{id}/stage`
Advances or updates an application stage.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)
- **Request Body**:
  ```json
  {
    "stage": "SCREENING",
    "note": "Passed initial screening evaluation"
  }
  ```
- **Valid Stages**: `NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`, `WITHDRAWN`.

### `GET /api/v1/applications/{id}/history`
Returns chronological audit history of all stage transitions.
- **Auth**: Bearer JWT

---

## 5. Deduplication Review Cases (`ReviewCaseController`)
Base Path: `/api/v1/review-cases`

### `GET /api/v1/review-cases`
Lists unresolved identity collision cases requiring recruiter review.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `HIRING_MANAGER`, `MANAGER`)
- **Response `200 OK`**: `List<ReviewCaseDto>`
  ```json
  [
    {
      "id": "12345678-1234-1234-1234-123456789abc",
      "candidateId": "8430e3b3-3869-4265-a10c-72e798117bf2",
      "conflictingCandidateId": null,
      "reasonCode": "PROCESSING_FAILED",
      "details": "Job PARSE_RESUME failed after 3 attempts: Connection reset",
      "resolution": null,
      "createdAt": "2026-11-01T12:00:00Z"
    }
  ]
  ```

### `POST /api/v1/review-cases/{id}/resolve`
Resolves an identity review collision.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `HIRING_MANAGER`, `MANAGER`)
- **Request Body**:
  ```json
  {
    "resolution": "MERGED"
  }
  ```
- **Supported Resolutions**: `MERGED` or `DISMISSED`.
- **Response `200 OK`**:
  ```json
  {
    "status": "RESOLVED",
    "resolution": "MERGED"
  }
  ```

---

## 6. Direct Resume Upload (`DirectUploadController`)
Base Path: `/api/v1/uploads`

### `POST /api/v1/uploads/direct`
Accepts multi-file binary uploads (`.pdf`, `.docx`, `.zip`) from the UI console.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`)
- **Form Data**:
  - `files`: One or more binary files (`MultipartFile[]`)
  - `sourceType`: Default `UPLOAD`
  - `jobId`: Optional Job UUID
- **Response `202 Accepted`**:
  ```json
  {
    "status": "QUEUED",
    "acceptedCount": 1,
    "attachmentIds": ["3a36a08f-71ee-4523-ac60-3e2cbf396374"]
  }
  ```

---

## 7. Chrome Extension Ingestion (`ExtensionIntakeController`)
Base Path: `/api/v1/uploads`

### `POST /api/v1/uploads/check-hashes`
Pre-flight duplicate check allowing the extension to skip uploading already known resumes.
- **Auth**: Bearer Extension Token (`hf_ext_...`)
- **Request Body**:
  ```json
  {
    "sha256List": ["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"]
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "existingHashes": ["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"]
  }
  ```

### `POST /api/v1/uploads/extension-intake`
Streams a verified resume attachment from Gmail/webmail with metadata.
- **Auth**: Bearer Extension Token (`hf_ext_...`)
- **Form Data**:
  - `file`: Resume binary file
  - `senderEmail`: Candidate or sender email address
  - `subject`: Email subject
  - `sha256`: Pre-computed SHA-256 hash
  - `sourceType`: Default `EXT_GMAIL`
  - `jobId`: Optional Requisition UUID
- **Response `202 Accepted`**:
  ```json
  {
    "status": "QUEUED",
    "attachmentId": "3a36a08f-71ee-4523-ac60-3e2cbf396374"
  }
  ```

---

## 8. Signed Document Viewer (`ResumeVersionController` & `LocalS3Controller`)

### `GET /api/v1/resume-versions/{id}/view-url`
Generates a temporary, HMAC-signed URL for viewing a candidate's resume PDF/DOCX.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  {
    "url": "http://localhost:8080/api/v1/local-s3/b2JqZWN0LXBhdGg6MTc5MzYyNzAwNw.a1b2c3d4..."
  }
  ```

### `GET /api/v1/local-s3/{token}`
Streams the binary document using the signed HMAC token.
- **Auth**: Token embedded in path (`base64url(objectKey:expiry).hmacHex`)
- **Response `200 OK`**: Binary byte stream with `Content-Disposition: inline` and `Content-Type: application/pdf`.
- **Security**: Expired tokens return `410 Gone`. Forged tokens return `403 Forbidden`.

---

## 9. Inbound Forwarding Webhook (`ForwardingWebhookController`)
Base Path: `/api/v1/mail-webhook`

### `POST /api/v1/mail-webhook/inbound`
Secure webhook receiver for email forwarding services (SendGrid, Mailgun, Postmark, AWS SES).
- **Auth**: HMAC-SHA256 v2 Headers (enforced in `WebhookAuthFilter`):
  - `X-HireFlow-Org-Id`: Organization UUID
  - `X-HireFlow-Key-Id`: Organization webhook key identifier (`whk_...`)
  - `X-HireFlow-Timestamp`: Unix epoch seconds at send time
  - `X-HireFlow-Nonce`: Cryptographically random 16–128 character nonce
  - `X-HireFlow-Signature`: 64 lowercase hex HMAC-SHA256 over 10-line canonical string (`v2\n{orgId}\n{keyId}\n{timestamp}\n{nonce}\n{method}\n{path}\n{idempotencyKey}\n{contentType}\n{bodySha256}`)
  - `Idempotency-Key`: Unique delivery identifier (8–200 characters)
- **Content Types**: `application/json` or `multipart/form-data`
- **Response `200 OK`**:
  ```json
  {
    "status": "ACCEPTED",
    "deliveryId": "...",
    "messageId": "...",
    "attachmentCount": 1
  }
  ```

---

## 10. Webhook Credential Management (`WebhookCredentialController`)
Base Path: `/api/v1/webhook-credentials`

### `POST /api/v1/webhook-credentials`
Provisions a new high-entropy HMAC secret key pair for the organization.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Response `200 OK`**:
  ```json
  {
    "keyId": "whk_7a1b2c3d4e5f6071",
    "secret": "hf_sec_..."
  }
  ```

### `POST /api/v1/webhook-credentials/{keyId}/rotate`
Revokes the current credential and issues a replacement in one atomic step.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Response `200 OK`**:
  ```json
  {
    "keyId": "whk_8b2c3d4e5f607182",
    "secret": "hf_sec_..."
  }
  ```

### `POST /api/v1/webhook-credentials/{keyId}/revoke`
Revokes an active webhook credential immediately.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Response `200 OK`**:
  ```json
  {
    "keyId": "whk_7a1b2c3d4e5f6071",
    "status": "REVOKED"
  }
  ```

### `GET /api/v1/webhook-credentials`
Lists active webhook credentials for the organization (secrets are never returned).
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Response `200 OK`**:
  ```json
  [
    {
      "keyId": "whk_7a1b2c3d4e5f6071",
      "status": "ACTIVE",
      "createdAt": "2026-11-01T12:00:00Z",
      "revokedAt": null,
      "createdBy": "11111111-1111-1111-1111-111111111111"
    }
  ]
  ```

---

## 11. Mailbox Connections (`MailConnectionController` & `OAuthMailController`)
Base Path: `/api/v1/mail-connections`

HireFlow provides enterprise email mailbox connectivity through Google Workspace / Gmail OAuth, Microsoft 365 / Outlook Graph OAuth, and traditional IMAP with App Passwords.

### 11.1 Zero Secret Exposure Guarantee
Across all mailbox APIs:
- Access tokens, refresh tokens, and IMAP app passwords are **never** returned in HTTP responses.
- The response model `MailConnectionDto` contains only metadata (`id`, `provider`, `mailboxAddress`, `imapHost`, `folderFilter`, `status`, `lastSuccessAt`, `createdAt`).
- All stored credentials remain encrypted at rest with AES-256-GCM in the backend database.

---

### `GET /api/v1/mail-connections/oauth/{provider}/authorize`
Initiates an OAuth 2.0 authorization code flow for connecting a Google or Microsoft mailbox.
- **Auth**: Bearer JWT (Roles: `OWNER`, `ADMIN`)
- **Path Parameters**:
  - `provider`: Provider identifier (`google` or `microsoft`)
- **Processing**:
  1. Validates that the provider is supported and configured in `HireFlowProperties.OAuthProperties`.
  2. Generates a cryptographically random single-use state token (32 bytes `SecureRandom`, URL-safe Base64).
  3. If provider is `microsoft`, generates a PKCE code verifier (64 bytes) and S256 code challenge.
  4. Records an `OAuthAuthorizationAttempt` entity with status `PENDING` and a 10-minute TTL.
  5. Dynamically constructs and returns the provider's OAuth 2.0 consent URL.
- **Response `200 OK`**:
  ```json
  {
    "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=12345.apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fmail-connections%2Foauth%2Fgoogle%2Fcallback&response_type=code&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.email&access_type=offline&prompt=consent&state=E2g7..._wQ",
    "state": "E2g7..._wQ",
    "provider": "google"
  }
  ```
- **Error Responses**:
  - `400 Bad Request`: Unsupported provider (e.g., `yahoo`) or missing client ID configuration (`hireflow.oauth.google-client-id`).
  - `401 Unauthorized`: Missing or invalid JWT authentication.
  - `403 Forbidden`: User does not possess `OWNER` or `ADMIN` roles.

---

### `GET /api/v1/mail-connections/oauth/{provider}/callback`
Handles the OAuth 2.0 redirect callback from Google or Microsoft. This endpoint is public so browser popup windows can receive provider redirects without pre-existing session cookies.
- **Auth**: Public (`permitAll` in `SecurityConfig`)
- **Path Parameters**:
  - `provider`: Provider identifier (`google` or `microsoft`)
- **Query Parameters**:
  - `code`: Authorization code from the provider (required on successful consent)
  - `state`: Single-use state token originally generated by the backend (required)
  - `error`: Error code from provider (e.g. `access_denied` when user cancels)
  - `error_description`: Human-readable error description from provider
- **Processing**:
  1. If `error` is present, marks the attempt `FAILED` in the database and renders an error HTML page.
  2. Atomically consumes the state token via `consumePendingAttempt(state, now)`. Rejects replayed, expired, or non-existent state tokens immediately.
  3. Validates that the provider path parameter matches the attempt's registered provider.
  4. Server-side exchanges the authorization code for access and refresh tokens via provider token endpoint.
  5. Auto-discovers the mailbox email address via provider user profile API (`/users/me/profile` for Gmail, `/me` for Microsoft Graph).
  6. **Refresh Token Preservation**: If Google does not return a new refresh token (typical on re-authorization without forced prompt), the backend retrieves and preserves the existing encrypted refresh token.
  7. Encrypts the credential tuple (`accessToken`, `refreshToken`, `expiresAt`) using AES-256-GCM and upserts the `MailConnection` entity for the initiating tenant organization.
  8. Emits a lightweight HTML postMessage bridge.
- **Response `200 OK` (HTML with postMessage Bridge)**:
  - Header: `Content-Type: text/html;charset=UTF-8`
  - Body: HTML card that dispatches a structured postMessage to `window.opener`:
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
        setTimeout(() => {
            window.location.href = '/index.html?oauth=success&provider=google&mailbox=hr%40company.com';
        }, 1200);
    }
    ```
- **Error Response (HTML with postMessage Bridge)**:
  - If state is invalid, replayed, expired, or code exchange fails, returns an HTML error card dispatching:
    ```javascript
    const message = {
        type: 'HIREFLOW_OAUTH_CALLBACK',
        status: 'ERROR',
        provider: 'google',
        error: 'OAuth state has already been consumed (replay detected).'
    };
    window.opener.postMessage(message, window.location.origin);
    ```

---

### `GET /api/v1/mail-connections`
Lists all configured email ingestion channels for the caller's tenant organization.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `VIEWER`)
- **Response `200 OK`**: `List<MailConnectionDto>`
  ```json
  [
    {
      "id": "e2d8471b-36d1-4e2b-a810-72e798117bf2",
      "provider": "GMAIL_OAUTH",
      "mailboxAddress": "recruiting@company.com",
      "imapHost": null,
      "folderFilter": "INBOX",
      "status": "ACTIVE",
      "lastSuccessAt": "2026-11-02T12:00:00Z",
      "createdAt": "2026-11-02T10:15:00Z"
    },
    {
      "id": "f3e9582c-47e2-5f3c-b921-83f809228cf3",
      "provider": "MS_GRAPH_OAUTH",
      "mailboxAddress": "talent@company.onmicrosoft.com",
      "imapHost": null,
      "folderFilter": "INBOX",
      "status": "REAUTH",
      "lastSuccessAt": "2026-11-01T18:00:00Z",
      "createdAt": "2026-11-01T15:00:00Z"
    }
  ]
  ```
- **Status Codes**:
  - `ACTIVE`: Mailbox is connected and syncing normally.
  - `REAUTH`: Provider rejected token refresh (revoked consent or expired refresh token); requires one-click re-authorization.
  - `PAUSED`: Mailbox connection paused or archived.
  - `ERROR`: Synchronization encountered a transient or network error.

---

### `POST /api/v1/mail-connections`
Adds an IMAP or manual mail connection with encrypted credentials.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Request Body (IMAP Example)**:
  ```json
  {
    "provider": "IMAP_APP_PASSWORD",
    "mailboxAddress": "hr@custom-mail.com",
    "imapHost": "mail.custom-mail.com",
    "imapPort": 993,
    "folderFilter": "INBOX",
    "appPassword": "secret-app-password"
  }
  ```
- **Response `200 OK`**: `MailConnectionDto` (secrets are never returned).

---

### `POST /api/v1/mail-connections/{id}/sync-now`
Triggers immediate scan and resume attachment ingestion for the specified mailbox connection.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)
- **Path Parameters**:
  - `id`: UUID of the mail connection
- **Response `200 OK`**:
  ```json
  {
    "status": "ACTIVE",
    "connectionId": "e2d8471b-36d1-4e2b-a810-72e798117bf2",
    "messagesScanned": 24,
    "attachmentsIngested": 5,
    "summary": "OAuth sync successful: 5 resume attachment(s) ingested.",
    "error": "",
    "lastSuccessAt": "2026-11-02T12:30:00Z"
  }
  ```
- **Error Responses**:
  - `400 Bad Request`: Connection is `PAUSED` or `REAUTH` (must be re-authorized before syncing).
  - `404 Not Found`: Connection ID does not exist in caller's organization.

---

### `DELETE /api/v1/mail-connections/{id}`
Pauses and archives a mailbox connection.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)
- **Path Parameters**:
  - `id`: UUID of the mail connection
- **Response `200 OK`**:
  ```json
  {
    "status": "PAUSED",
    "connectionId": "e2d8471b-36d1-4e2b-a810-72e798117bf2"
  }
  ```

---

## 12. Operational Diagnostics (`HealthController`)
Base Path: `/api/v1/operations`

### `GET /api/v1/operations/health`
Returns tenant operational metrics for the test console KPI bar.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  {
    "queueDepth": 0,
    "runningCount": 0,
    "processedCount": 12,
    "failedCount": 0,
    "totalResumeFiles": 12,
    "totalCandidates": 10,
    "activeMailConnections": 1,
    "aiTokenSpend": 0
  }
  ```
