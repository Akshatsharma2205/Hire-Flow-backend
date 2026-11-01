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
- **Auth**: Bearer JWT

### `POST /api/v1/review-cases/{id}/resolve`
Resolves an identity review collision.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)
- **Request Body**:
  ```json
  {
    "resolution": "MERGED"
  }
  ```
- **Supported Resolutions**: `MERGED` or `DISMISSED`.

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
- **Auth**: HMAC-SHA256 v2 Headers:
  - `X-HireFlow-Key-Id`: Organization webhook key identifier (`whk_...`)
  - `X-HireFlow-Timestamp`: Unix epoch seconds
  - `X-HireFlow-Signature`: Hex-encoded HMAC-SHA256 (`v2:${timestamp}:${body}`)
  - `X-HireFlow-Idempotency-Key`: Unique delivery identifier
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
- **Auth**: Bearer JWT (`OWNER` only)
- **Response `201 Created`**:
  ```json
  {
    "keyId": "whk_7a1b2c3d4e5f6071",
    "secret": "hf_sec_...",
    "createdAt": "2026-11-01T12:00:00Z"
  }
  ```

### `GET /api/v1/webhook-credentials`
Lists active webhook credentials for the organization (secrets are masked).
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)

---

## 11. Mailbox Connections (`MailConnectionController`)
Base Path: `/api/v1/mail-connections`

### `GET /api/v1/mail-connections`
Lists configured email ingestion channels for the tenant.
- **Auth**: Bearer JWT

### `POST /api/v1/mail-connections`
Adds an IMAP or OAuth mailbox connection with encrypted credentials.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)

### `POST /api/v1/mail-connections/{id}/sync-now`
Triggers immediate mailbox scan and attachment ingestion.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`, `RECRUITER`, `MANAGER`)

### `DELETE /api/v1/mail-connections/{id}`
Pauses and archives a mail connection.
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)

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
