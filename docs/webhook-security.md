# HireFlow Inbound Webhook — Security & Delivery Contract (J-2A)

Applies to: `POST /api/v1/mail-webhook/inbound` (multipart and JSON/base64).
Backend implementation: `WebhookAuthFilter`, `WebhookContentType`,
`MultipartBodyParser`, `WebhookBodyCachingRequestWrapper`,
`WebhookCredentialController`, `WebhookIngestionService`, migration
`V4__webhook_security_and_idempotency.sql`.

The legacy global shared secret (`X-HireFlow-Webhook-Secret`) and the v1
signature canonicalisation are **retired**. No profile accepts either: there is
no downgrade path. Every organization has its own credentials, and every
request is signed with the v2 contract below.

---

## 1. Provisioning (OWNER or ADMIN only)

```
POST   /api/v1/webhook-credentials                 -> { keyId, secret }   # secret shown ONCE
POST   /api/v1/webhook-credentials/{keyId}/rotate  -> { keyId, secret }   # old key revoked, secret shown ONCE
POST   /api/v1/webhook-credentials/{keyId}/revoke  -> { keyId, status: "REVOKED" }
GET    /api/v1/webhook-credentials                 -> metadata only, never secrets
```

- Requires an authenticated `OWNER` or `ADMIN`; the organization is derived
  from the authenticated session, never from a request parameter.
- Secrets are generated with a secure RNG (256 bits, base64url) and stored
  **encrypted at rest** (AES-256-GCM, `CRYPTO_SECRET_KEY`). Revocation is
  immediate: revoked keys cannot authenticate new deliveries.
- Rotate creates a new active credential and revokes the old one; senders
  must switch to the new key id + secret.
- **Local development is opt-in.** The `local` profile alone does not create a
  credential and there is no default secret in code or configuration. To seed
  the demo credential (`whk_0a11c0de00000001`) for the seeded organization,
  start with `local` and set both `WEBHOOK_LOCAL_DEV_ENABLED=true` and an
  explicit `WEBHOOK_LOCAL_DEV_SECRET` of at least 32 characters. Startup fails
  if the flag is set without a sufficiently long secret. The seeder refuses to
  run in any non-`local` profile.

PowerShell (from `hireflow-backend/`):

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:WEBHOOK_LOCAL_DEV_ENABLED = "true"
$env:WEBHOOK_LOCAL_DEV_SECRET = "<your-own-random-secret-of-32-or-more-characters>"
.\mvnw.cmd spring-boot:run
```

Do not use this demo path in production. Production credentials are
provisioned only through the authenticated endpoint above.

## 2. Required headers (exact names, no aliases)

| Header | Value |
|---|---|
| `X-HireFlow-Org-Id` | UUID of your organization (selection hint; the key must belong to it) |
| `X-HireFlow-Key-Id` | `whk_` + 16 hex chars (from provisioning) |
| `X-HireFlow-Timestamp` | Unix epoch **seconds** at send time |
| `X-HireFlow-Nonce` | 16–128 chars, `[A-Za-z0-9_-]`; cryptographically random, **unique per delivery attempt** |
| `X-HireFlow-Signature` | 64 lowercase hex chars: HMAC-SHA256 over the canonical string |
| `Idempotency-Key` | 8–200 chars, `[A-Za-z0-9._-]`; stable per logical delivery |

Exactly one `Content-Type` header is required. Supported values are
`application/json` (optional parameters such as `charset` are accepted) and
`multipart/form-data` with a valid `boundary` parameter. Missing, duplicated,
unsupported, or malformed Content-Type values are rejected before ingestion.
Query strings are rejected outright with `400`; metadata is accepted only from
the authenticated body, never from URL parameters or headers.

## 3. Signature calculation (v2)

Join with `\n` (UTF-8, exactly these 10 lines, LF endings, no trailing
newline):

```
v2
{organizationId}
{keyId}
{timestamp}               # epoch seconds, exactly as sent in the header
{nonce}
{HTTP_METHOD}             # uppercase, e.g. POST
{requestPath}             # e.g. /api/v1/mail-webhook/inbound (path only; query strings rejected)
{idempotencyKey}
{canonicalContentType}
{sha256(rawBody)}         # lowercase hex SHA-256 of the exact raw request bytes
```

`signature = hex(HMAC-SHA256(secret, canonicalString))` where `secret` is the
**raw secret returned at provisioning time**. The server compares signatures in
constant time.

`canonicalContentType` is derived from the validated request header:

- JSON: exactly `application/json` (any `charset` or other optional parameters
  are not part of the signed value).
- Multipart: exactly `multipart/form-data; boundary="<boundary>"`, always with
  the boundary quoted. The boundary is validated (1–70 characters from the
  RFC 2046 `bchars` set, no trailing space) and is part of the signature, so a
  signature cannot be replayed against a different boundary.

There is no v1 compatibility mode. A request signed with the former 9-line
canonical string is rejected with `401`.

The body digest is recomputed by the server from the actual received bytes —
a client-supplied digest is never trusted. For multipart deliveries the raw
bytes include the multipart boundaries: retries must resend byte-identical
bodies (stable boundaries), otherwise the payload is treated as changed.

## 4. Timestamps

The server rejects requests whose timestamp is older than
`hireflow.webhook.replay-window-seconds` (default **300 s**) or more than the
same window in the future. The window is symmetric — it covers both expiry and
clock skew; keep the sender's clock NTP-synchronized.

## 5. Nonces

One nonce per delivery attempt, generated with a secure RNG. The server stores
`(organization_id, credential_id, nonce)` durably (`webhook_deliveries`) with a
unique constraint; reusing a nonce — even with a different idempotency key —
is rejected with `409`. Protection survives restarts and applies to every
concurrent request. Retain delivery rows for at least the replay window;
operationally 7–30 days is recommended.

## 6. Idempotency keys and retries

| Case | Result |
|---|---|
| First valid delivery | `202 {status: "QUEUED", deliveryId, inboundMessageId, acceptedCount, attachmentIds}` |
| Same key + same body digest | `202 {status: "DUPLICATE", ...original refs...}` — no new message/attachment/job |
| Same key + different body digest | `409` — never silently treated as the same request |
| Same nonce + different key | `409` (replay) |

Retry guidance: resend the **exact same bytes** with the **same idempotency
key** and a **fresh nonce + fresh timestamp**. The original `202` result is
returned unchanged. If a request timed out and you do not know whether it was
processed, retrying with the same idempotency key is always safe.

## 7. Size and shape limits (defaults, configurable)

| Limit | Default | Property | Result |
|---|---|---|---|
| Raw request body | 26,214,400 bytes (25 MiB) | `hireflow.webhook.max-body-bytes` | `413` |
| Single attachment, decoded | 10,485,760 bytes (10 MiB) | `max-attachment-bytes` | `413` |
| All attachments, decoded | 16,777,216 bytes (16 MiB) | `max-total-attachment-bytes` | `413` |
| Attachments per delivery | 20 | `max-attachments` | `413` |
| Multipart parts (all fields) | 32 | `max-parts` | `413` |
| Multipart part header block | 8,192 bytes | `max-header-bytes` | `413` |
| Textual metadata (fields + filenames) | 16,384 bytes | `max-metadata-bytes` | `400` |
| Concurrent body buffers/parses | 4 | `max-concurrent-requests` | `503` when busy |

Text metadata also has per-field caps (sender email ≤ 320 bytes, subject ≤
4000 bytes, source type ≤ 30 bytes, job id ≤ 36 bytes, filename ≤ 1024 bytes).
Oversized metadata is rejected with `400`; it is never silently truncated.
Base64 attachments are checked twice: the encoded length is bounded before
decoding to prevent expansion attacks, then the decoded length is bounded. The
configured limits must leave room for base64 expansion (4/3), metadata, and the
JSON envelope inside `max-body-bytes`; the application refuses to start with an
incoherent combination.

## 8. Multipart wire contract

The parser is strict and bounded; malformed input is rejected with `400`
before any database write:

- The first boundary delimiter must start at byte zero.
- Every subsequent delimiter must be exactly `CRLF--<boundary>` followed by
  either `CRLF` (another part) or `--` (closing boundary). Boundary-like bytes
  inside part content are preserved verbatim.
- The closing boundary is mandatory; truncated bodies are rejected.
- Part headers end at the exact `CRLFCRLF` separator. Header lines must be
  well-formed `Name: value` pairs; the part header block is bounded.
- Each part must have exactly one `Content-Disposition: form-data` header with
  a non-empty `name`. Duplicate parameters and malformed quoted values are
  rejected.
- `filename` values may be quoted with backslash escapes; the decoded filename
  is used for validation and storage.
- `files` is the only attachment field. Metadata fields are exactly
  `senderEmail`, `originalSenderEmail`, `subject`, `sourceType`, and `jobId`.
  Unknown, duplicate, missing-name, or metadata-sent-as-a-file parts are
  rejected.
- Part bodies are preserved as raw bytes, including NUL and non-UTF-8 data.

## 9. Error responses

| Status | Meaning |
|---|---|
| 400 | Missing/invalid `Idempotency-Key`, query string, missing/unsupported/duplicate content type, malformed multipart, unknown/duplicate/malformed metadata, oversized metadata |
| 401 | Missing/malformed auth headers, unknown/revoked key, org/key mismatch, bad signature (including v1), timestamp outside window, malformed nonce — always generic "Invalid webhook authentication" (existence is never disclosed) |
| 403 | Organization suspended/inactive (verified signature required first) |
| 409 | Replay or idempotency conflict |
| 413 | Body/attachment/part size or count exceeded |
| 503 | Webhook disabled, intake concurrency exhausted, or internal lookup/decryption failure (fail closed) |

## 10. Local signing example (no real secrets)

The example assumes the opt-in local seeder above has created
`whk_0a11c0de00000001` with your `WEBHOOK_LOCAL_DEV_SECRET`.

```bash
ORG_ID="11111111-1111-1111-1111-111111111111"
KEY_ID="whk_0a11c0de00000001"
SECRET="<the WEBHOOK_LOCAL_DEV_SECRET you supplied>"
CONTENT_TYPE="application/json"
BODY='{"senderEmail":"hr@agency.example","originalSenderEmail":"claimed@example.com",
       "subject":"Fwd: candidate","attachments":[{"filename":"resume.pdf","data":"<base64>"}]}'
TS=$(date +%s)
NONCE="$(openssl rand -base64 24 | tr '+/' '-_' | tr -d '=')"
IDEM="delivery-2026-10-31-0001"
DIGEST=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | sed 's/^.* //')

CANON=$(printf 'v2\n%s\n%s\n%s\n%s\nPOST\n/api/v1/mail-webhook/inbound\n%s\n%s\n%s' \
    "$ORG_ID" "$KEY_ID" "$TS" "$NONCE" "$IDEM" "$CONTENT_TYPE" "$DIGEST")
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

curl -X POST http://localhost:8080/api/v1/mail-webhook/inbound \
  -H "Content-Type: $CONTENT_TYPE" \
  -H "X-HireFlow-Org-Id: $ORG_ID" \
  -H "X-HireFlow-Key-Id: $KEY_ID" \
  -H "X-HireFlow-Timestamp: $TS" \
  -H "X-HireFlow-Nonce: $NONCE" \
  -H "X-HireFlow-Signature: $SIG" \
  -H "Idempotency-Key: $IDEM" \
  --data-binary "$BODY"
```

For multipart, use the exact header value as the signed canonical value. If the
wire header is:

```
Content-Type: multipart/form-data; boundary=j2example
```

then the ninth signed line is:

```
multipart/form-data; boundary="j2example"
```

Java reference (same contract, from the integration test suite):

```java
String canonical = String.join("\n", "v2", orgId, keyId, timestamp, nonce,
        "POST", "/api/v1/mail-webhook/inbound", idempotencyKey,
        "application/json", bodySha256Hex);
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String signature = hex(mac.doFinal(canonical.getBytes(UTF_8)));
```

## 11. Data provenance

One forwarded email maps to ONE `inbound_messages` row with N
`inbound_attachments` rows and N `PARSE_RESUME` processing jobs. The
forwarding mailbox is stored in `sender_email`; a claimed original sender
(optional `originalSenderEmail` header/field) is stored separately in
`original_sender_claim`. When the original sender is absent it stays
`NULL` (unknown) — it is never invented and never treated as authenticated
identity proof. Candidate identity resolution is unchanged (tracked for J-5).
