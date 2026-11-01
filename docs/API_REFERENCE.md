# HireFlow AI — Complete REST API Reference

All REST endpoints in HireFlow are prefixed with `/api/v1` and return JSON payloads unless serving binary document streams. Authentication is enforced via Bearer JWT tokens, Extension intake tokens, or HMAC-SHA256 webhook signatures.

---

## 1. Authentication & Session (`AuthController`)
Base Path: `/api/v1/auth`

### `POST /api/v1/auth/login`
Authenticates a user by email and password, returning a scoped JWT token.
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
    "token": "eyJhbGciOi...",
    "userId": "22222222-2222-2222-2222-222222222222",
    "organizationId": "11111111-1111-1111-1111-111111111111",
    "email": "hr@kartgen.com",
    "name": "HR Manager",
    "role": "OWNER"
  }
  ```
- **Error Responses**: `400 Bad Request` (missing fields), `401 Unauthorized` (bad credentials).

### `POST /api/v1/auth/logout`
Terminates user session and blacklists the provided JWT.
- **Auth**: Bearer JWT

---

## 2. Candidates Management (`CandidateController`)
Base Path: `/api/v1/candidates`

### `GET /api/v1/candidates`
Lists candidates for the caller's organization. Supports optional search filter.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`, `VIEWER`)
- **Query Params**: `q` (string, optional)
- **Response `200 OK`**: Array of candidate objects with primary contact details, latest profile version, and current resume version.

### `GET /api/v1/candidates/{id}`
Retrieves complete candidate profile, skills, experience, education, and submission history.
- **Auth**: Bearer JWT
- **Response `200 OK`**: Detailed candidate object.
- **Error Responses**: `404 Not Found` if candidate does not exist or belongs to another tenant.

### `PATCH /api/v1/candidates/{id}`
Updates candidate metadata or status (`ACTIVE`, `ARCHIVED`).
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)

---

## 3. Job Requisitions (`JobController`)
Base Path: `/api/v1/jobs`

### `GET /api/v1/jobs`
Lists all job requisitions in the tenant organization.
- **Auth**: Bearer JWT
- **Response `200 OK`**: Array of job summaries (title, department, status, active candidates count).

### `POST /api/v1/jobs`
Creates a new job requisition and triggers baseline skill indexing.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Request Body**:
  ```json
  {
    "title": "Senior Backend Engineer",
    "department": "Engineering",
    "location": "Remote",
    "description": "Building high scale Java services",
    "requiredSkills": ["Java", "Spring Boot", "PostgreSQL"],
    "bonusSkills": ["Docker", "Kubernetes"]
  }
  ```
- **Response `200 OK`**: Created Job object with unique UUID.

### `GET /api/v1/jobs/{id}/matches`
Computes and returns ranked candidate match scores for the specified job.
- **Auth**: Bearer JWT
- **Response `200 OK`**:
  ```json
  [
    {
      "candidateId": "...",
      "candidateName": "Jane Doe",
      "score": 92,
      "matchedSkills": ["Java", "Spring Boot", "PostgreSQL"],
      "missingSkills": []
    }
  ]
  ```

---

## 4. Job Applications (`ApplicationController`)
Base Path: `/api/v1/applications`

### `POST /api/v1/applications`
Submits or attaches a candidate to a job opening.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Request Body**:
  ```json
  {
    "jobId": "c0000001-0000-0000-0000-000000000001",
    "candidateId": "1e492076-684b-4918-a119-8ac1d826a2f0"
  }
  ```
- **Response `201 Created`**: Application entity in initial `NEW` stage.

### `GET /api/v1/applications?jobId={jobId}`
Lists all applications for a specified job.
- **Auth**: Bearer JWT

### `PATCH /api/v1/applications/{id}/stage`
Transitions an application to another pipeline stage (`SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`).
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Request Body**:
  ```json
  {
    "stage": "SCREENING",
    "notes": "Passed initial resume review"
  }
  ```
- **Response `200 OK`**: Updated application object and audit stage entry.

---

## 5. Identity Review Cases (`ReviewCaseController`)
Base Path: `/api/v1/identity-review-cases`

### `GET /api/v1/identity-review-cases`
Returns unresolved identity collision cases requiring recruiter review.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Response `200 OK`**: Array of review cases with incoming candidate details and matching conflicting profile IDs.

### `POST /api/v1/identity-review-cases/{id}/resolve`
Resolves an identity collision case.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Request Body**:
  ```json
  {
    "resolution": "MERGED"
  }
  ```
- Supported resolutions: `MERGED` (combines candidate profiles) or `SEPARATED` (creates separate candidate profile).

---

## 6. Direct Resume Upload (`DirectUploadController`)
Base Path: `/api/v1/uploads`

### `POST /api/v1/uploads/resume`
Accepts multipart form file upload (`.pdf`, `.docx`, `.txt`) directly from the recruiter SPA.
- **Auth**: Bearer JWT (`RECRUITER`, `ADMIN`, `OWNER`)
- **Form Data**:
  - `file`: Binary file stream
  - `jobId`: Optional target Job UUID
- **Response `200 OK`**:
  ```json
  {
    "attachmentId": "...",
    "status": "QUEUED"
  }
  ```

---

## 7. Chrome Extension Intake (`ExtensionIntakeController`)
Base Path: `/api/v1/extension` and `/api/v1/uploads`

### `POST /api/v1/uploads/check-hashes`
Pre-flight duplicate check allowing extension to skip re-uploading files already present in the tenant.
- **Auth**: Bearer Extension Token (`hf_ext_...`)
- **Request Body**:
  ```json
  {
    "hashes": ["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"]
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "existing": ["9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"]
  }
  ```

### `POST /api/v1/extension/upload`
Uploads candidate resume stream detected on webmail or job portals.
- **Auth**: Bearer Extension Token (`hf_ext_...`)
- **Form Data**: `file` (multipart), `metadata` (JSON string)

---

## 8. Inbound Mail Webhook (`ForwardingWebhookController`)
Base Path: `/api/v1/mail-webhook`

### `POST /api/v1/mail-webhook/inbound`
Secure webhook receiver for email forwarding services (SendGrid, Mailgun, Postmark, AWS SES).
- **Auth**: HMAC-SHA256 v2 Headers:
  - `X-HireFlow-Key-Id`: Organization webhook key identifier (`whk_...`)
  - `X-HireFlow-Timestamp`: Unix epoch seconds of request transmission
  - `X-HireFlow-Signature`: Hex-encoded HMAC-SHA256 signature
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
- **Security Protections**: Constant-time signature comparison, timestamp freshness enforcement (drift <= 300s), idempotency tracking returning identical `200 OK` on retries, automatic storage rollback on persistence failures.

---

## 9. Webhook Credential Management (`WebhookCredentialController`)
Base Path: `/api/v1/webhook-credentials`

### `POST /api/v1/webhook-credentials`
Provisions a new high-entropy HMAC secret key pair for the organization.
- **Auth**: Bearer JWT (`OWNER` only)
- **Response `201 Created`**:
  ```json
  {
    "keyId": "whk_7a1b2c3d4e5f6071",
    "secret": "hf_sec_99999999999999999999999999999999",
    "createdAt": "2026-11-01T12:00:00Z"
  }
  ```

### `GET /api/v1/webhook-credentials`
Lists active webhook credentials for the organization (secrets are masked).
- **Auth**: Bearer JWT (`OWNER`, `ADMIN`)

---

## 10. Document Streaming & View URLs (`LocalS3Controller`)
Base Path: `/api/v1/local-s3`

### `GET /api/v1/local-s3/{token}`
Streams candidate resume binary bytes using a secure signed URL token.
- **Auth**: URL HMAC Token (`{objectKeyBase64}:{expiresAt}:{hmacHex}`)
- **Response `200 OK`**: Binary document stream (`application/pdf`, etc.) with `Content-Disposition: inline`.
- **Error Responses**:
  - `403 Forbidden`: Invalid, forged, or mismatched HMAC signature.
  - `410 Gone`: Expired token past TTL window.
  - `404 Not Found`: File removed or unavailable.

---

## 11. System Health & Operations (`HealthController`)
Base Path: `/api/v1/operations`

### `GET /api/v1/operations/health`
Returns system diagnostics, database connectivity status, and background worker queue depths.
- **Auth**: Public / Internal
- **Response `200 OK`**:
  ```json
  {
    "status": "UP",
    "database": "CONNECTED",
    "pendingJobs": 0,
    "failedJobs": 0
  }
  ```
