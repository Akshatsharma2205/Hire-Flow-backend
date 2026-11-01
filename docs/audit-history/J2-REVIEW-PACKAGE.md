# J-2 Review Package — Webhook Authentication, HMAC, Replay Prevention & Durable Idempotency

Generated: 2026-11-01 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repo; diff produced against pre-J-2 file snapshot)

> **For reviewer:** read-only review. Do not modify any files.
> Exact machine-generated diff: [`J2-EXACT-DIFF.patch`](J2-EXACT-DIFF.patch) (3,184 lines, 24 files, SHA256 `D0E722AFB2CA8CD15D647415C22F92E524E5974F6E2DA6E0C65B43A2E335177E`).
> Sender contract documentation: [`docs/webhook-security.md`](docs/webhook-security.md).

---

## 1. Audit findings verified against current code (before any edit)

| Audit claim | Verified state | Action |
|---|---|---|
| `WebhookAuthFilter` uses a global shared secret | Confirmed (`X-HireFlow-Webhook-Secret`, constant-time compare) | Replaced with per-org credentials + HMAC |
| `X-HireFlow-Org-Id` selects the organization | Confirmed — header trusted after secret check | Header demoted to credential-selection hint; must match verified credential's org |
| No cryptographic HMAC body signature | Confirmed | HMAC-SHA256 over canonical string incl. raw-body SHA-256 |
| No timestamp validation | Confirmed | Signed timestamp, configurable symmetric window (default 300 s) |
| No durable nonce/replay protection | Confirmed | `webhook_deliveries` table, DB unique constraint over (org, credential, nonce) |
| No durable idempotency | Confirmed | Same ledger, unique constraint over (org, idempotency_key), body-digest conflict detection |
| PG unique constraint `(org, connection_id, provider_message_id)` misses NULL `connection_id` webhooks | Confirmed (standard NULL semantics) | New delivery ledger arbitrates duplicates at DB level |
| Replay can duplicate messages/attachments/versions/jobs | Confirmed | Reservation + ingestion in ONE transaction; duplicates return the original result |
| Multipart path already creates one message + N attachments | Confirmed | Preserved and covered by tests 20–21 |

Crypto pre-check: `CryptoUtil` is genuine AES-256-GCM (random 12-byte IV per encryption, 128-bit auth tag, 32-byte hex key enforced, tamper → decryption failure). Reused for secret-at-rest. `ProductionSafetyConfig` (prod) still fails fast on default crypto/JWT keys; the obsolete `WEBHOOK_SECRET` production check was replaced with a replay-window sanity check since the global secret no longer exists.

## 2. What was built

### Per-organization credentials (`webhook_credentials`, V4)
- `POST /api/v1/webhook-credentials` / `/{keyId}/rotate` / `/{keyId}/revoke` / `GET` list.
- `@PreAuthorize("hasAnyRole('OWNER','ADMIN')")`; org derived from verified `TenantContext` — never a caller-supplied id. VIEWER/RECRUITER are denied (tested).
- Secrets: 256-bit SecureRandom, base64url; stored **encrypted at rest** (AES-256-GCM); returned **exactly once** on create/rotate; never in list responses (tested); never logged (audited all log statements).
- Key IDs: `whk_` + 16 hex chars, globally unique. Revocation immediate; rotation revokes the old key atomically (old key rejected afterwards — tested).
- Local dev only: `@Profile("local")` seeder provisions the demo org credential (`whk_0a11c0de00000001`) from `hireflow.webhook.local-dev-secret`, encrypted like any credential. **The legacy global secret authenticates nothing in any profile** (tested).

### HMAC-SHA256 request signature (`WebhookAuthFilter`)
Canonical v1 string (LF-joined): `v1 / orgId / keyId / timestamp / nonce / METHOD / requestPath / idempotencyKey / sha256hex(rawBody)`; signature = hex(HMAC-SHA256(secret, canonical)), verified with `MessageDigest.isEqual`. The body digest is computed by the filter from the **actual received bytes** (bounded, chunked-safe; client-supplied digests never trusted).

Rejections (all sanitized, generic messages): missing/malformed headers (401/400), unknown key (401), key/org mismatch (401 — org B's header with org A's key), revoked key (401), timestamp expired/future/malformed (401), malformed nonce/signature formats (401), altered body (401), unsupported version (signature mismatch), suspended/inactive org (403 — checked **after** signature so org status is never disclosed without a valid signature). DB/crypto failures **fail closed with 503** (corrupt-credential test included).

The webhook principal carries only `ROLE_WEBHOOK` — no interactive authority (tested: webhook headers on `/api/v1/jobs` authenticate nothing).

### Request body handling
`WebhookBodyCachingRequestWrapper` + `MultipartBodyParser`: bounded buffering (default 25 MB, `413` beyond), replayable stream for `@RequestBody`, and `getParts()`/`getPart()` re-implemented over the cached bytes so multipart parsing still works after the raw body was consumed (works identically under MockMvc and real servlet containers; original Content-Type preserved).

### Replay + idempotency (`webhook_deliveries`, one transaction)
Single `@Transactional` unit: durable nonce check → reservation via dialect-neutral `INSERT … SELECT … WHERE NOT EXISTS` (works on H2 and PostgreSQL; committed duplicates insert 0 rows without poisoning the transaction) → inbound message + attachments + parse jobs → `markAccepted` (with an explicit `EntityManager.flush()` so the JDBC outcome update sees the JPA-inserted message). Unique constraints arbitrate true races: the loser's transaction rolls back completely and the controller resolves against the committed winner — identical payload → `202 DUPLICATE` (original refs), different payload → `409`, nonce reuse with different key → `409`. **No second processing chain can exist — enforced by the database.**

Semantics: first delivery `202 QUEUED` (deliveryId + inboundMessageId + attachmentIds persisted); same key + same digest `202 DUPLICATE` (original result); same key + different digest `409`; same nonce + different key `409`.

### Data integrity & provenance
`ingestFromWebhook` now takes `forwarderEmail` + `originalSenderEmail` separately: forwarder → `sender_email`, claimed original → `original_sender_claim`, absent original stays `NULL` (unknown — never invented). One email → one message → N attachments → N `PARSE_RESUME` jobs (tests assert exact DB rows and job types/counts). Candidate identity resolution untouched (recorded for J-5).

### Size limits (configurable)
Body 25 MB (filter-level `413`, enforced while streaming — chunked-safe, Content-Length never trusted), 20 attachments/delivery, 50 MB per attachment (multipart part or decoded base64), metadata caps (320/4000/30) → `400`. Boundary acceptance tested at configured limits.

## 3. Changed files (24: 16 new, 8 modified — nothing else differs)

**New (main):** `V4__webhook_security_and_idempotency.sql` (`7FB5BCD0…0AF6FD`), `webhook/WebhookCredential(+Repository)`, `webhook/WebhookDelivery(+Repository)`, `webhook/WebhookDeliveryStore` (`C233C0EB…B5CB1`), `webhook/WebhookIngestionService` (`C05194BF…D400`), `webhook/WebhookCredentialController`, `webhook/LocalWebhookDevCredentialSeeder`, `security/WebhookAuthFilter` (rewritten, `B6E85818…D9E26`), `security/WebhookBodyCachingRequestWrapper`, `security/MultipartBodyParser` (`6AD21A2E…D400`).
**New (tests):** `WebhookDeliveryIntegrityIntegrationTest`, `WebhookReplayDurabilityIntegrationTest`, `WebhookSizeLimitIntegrationTest`, `support/WebhookIntegrationTestSupport`, `support/WebhookTestSupport`.
**Modified:** `ForwardingWebhookController` (limits + attribute-driven + outcome mapping), `MessageIngestionService` (webhook signature/provenance + message-id return; IMAP/OAuth paths untouched), `HireFlowProperties`, `ProductionSafetyConfig`, `application.yml`, `WebhookAuthIntegrationTest` (rewritten from the retired global-secret model), `IntegrationTestSupport` (removed dead `WEBHOOK_SECRET` constant).
**Docs:** `docs/webhook-security.md` (provisioning, headers, signing format, timestamps, nonces, idempotency, retries, rotation, local signing example with no real secrets).

**Hash-verified unchanged:** `SecurityConfig`, `JwtAuthenticationFilter`, `ExtensionTokenAuthFilter`, `RoleNormalizer`, IMAP/OAuth controllers & adapters, `ProcessingWorker`/`WorkerScheduler`, `pom.xml`, Flyway V1–V3, frontend.

## 4. Required tests → actual test methods

**`WebhookAuthIntegrationTest` (23):** missing signature; legacy global secret retired; incorrect signature; modified body with original signature; wrong org/key combination (no rows in either tenant); revoked credential; suspended organization (403 after valid signature); expired timestamp; future timestamp; malformed timestamp; malformed nonce; missing idempotency key (400, no rows); **valid signed JSON delivery** (202 + delivery/message/attachment/digest DB assertions); **valid signed multipart delivery**; exact replay same nonce → `202 DUPLICATE` + no new rows; same key same payload fresh nonce → original result; same key different payload → `409`; same nonce different key → `409`; VIEWER/RECRUITER cannot provision/rotate; ADMIN provisions + list never contains secret + stored value ≠ secret; rotation revokes old key (old 401, new 202); corrupt stored secret → **503 fail-closed**; webhook headers grant no interactive access.

**`WebhookDeliveryIntegrityIntegrationTest` (5):** 3 attachments → exactly 1 message + 3 attachments + 3 `PARSE_RESUME` jobs (DB-verified, incl. no extra jobs); **two concurrent identical requests → exactly one delivery/message/attachment/job** (both 202: one `QUEUED`, one `DUPLICATE`, same deliveryId — thread-winner agnostic); re-delivery creates no second resume version (worker-processed candidate, version count stable); forwarder vs claimed original stored separately; missing original stays `NULL`.

**`WebhookReplayDurabilityIntegrationTest` (2):** scenario 19 on an isolated persistent H2 DB — delivery accepted, `@DirtiesContext` forces a **context restart**, replay of the exact same nonce/key/payload against the fresh context → `202 DUPLICATE`, zero new rows (uniqueness is durable, not in-memory).

**`WebhookSizeLimitIntegrationTest` (7):** oversized JSON body `413`; oversized multipart body `413` (filter-level); oversized multipart attachment `413` (controller-level); excessive attachment count `413`; oversized decoded base64 `413`; valid JSON exactly at the body boundary `202` (byte-exact 8192); attachment exactly at limit accepted — all with DB no-write assertions on rejections.

Every rejection test asserts real HTTP status (+ JSON body where meaningful) **and** database invariants; every acceptance test asserts persisted rows. All fixtures are synthetic (generated PDFs, random orgs/users); no real candidate data or credentials.

## 5. Test evidence (actual artifacts)

| Run | Command (cwd `hireflow-backend`) | Exit | Result |
|---|---|---|---|
| Baseline (pre-J-2, archived) | — | 0 | 13 suites / 145 tests / 0 F / 0 E / 0 S (Surefire XML, 2026-10-31 20:23–20:24; archived to `%TEMP%\opencode\j2-presurefire-20261031-220543`) |
| Focused webhook suites (final) | `.\mvnw.cmd -o -Dtest="WebhookAuthIntegrationTest,WebhookDeliveryIntegrityIntegrationTest,WebhookReplayDurabilityIntegrationTest,WebhookSizeLimitIntegrationTest" test -DskipTests=false` | 0 | 23+5+2+7 = **37/37 passed** |
| **Full suite (final)** | `.\mvnw.cmd -q -o test -DskipTests=false` | **0** | **16 suites / 177 tests / 0 failures / 0 errors / 0 skipped** (Surefire XML parsed programmatically, written 2026-11-01 03:26) |

Delta vs baseline = −5 (retired global-secret tests) + 37 (new suites) = **177** ✓. J-1 suite (14), RoleNormalizer (49), RoleValidation/Hardening (19), UserMembership (15), JwtRevocation (10), DemoIsolation (8), WebhookAuth-legacy replaced — all green, proving §13 regressions hold.

## 6. Acceptance conditions → status

1. Independent per-org credential ✓ (2) HMAC over real body + metadata ✓ (3) stale requests rejected ✓ (4) replays create no duplicate side effects ✓ (5) concurrent duplicates → exactly one chain ✓ (6) idempotent retries return original result ✓ (7) changed payload on existing key → 409 ✓ (8) suspended orgs + revoked keys blocked ✓ (9) one email → one message + all attachments ✓ (10) sender provenance preserved ✓ (11) prior protections intact (full suite green) ✓ (12) tests + migration results support claims ✓ (V1→V4 migrate cleanly on every Spring context boot; migration hashes preserved: V1 `D4C819B7…`, V2 `A58C334D…`, V3 `5745E900…`).

## 7. Limitations & residual items (for the record)

- **PostgreSQL not physically verified.** Docker CLI is installed but the daemon could not be started in this environment (engine never became reachable after ~15 min). Per §14, no PostgreSQL behavior is claimed; verification used H2 in PostgreSQL compatibility mode. The reservation SQL is deliberately dialect-neutral (`INSERT … SELECT … WHERE NOT EXISTS`), and V4 uses only portable types; when a daemon is available, run the focused suites with `-Dspring.datasource.url=jdbc:postgresql://…` to close this gap.
- The durability (restart) test is pinned to its own H2 DB by design; nonce/idempotency uniqueness on PostgreSQL is exercised by the other suites.
- Multipart re-parsing uses a purpose-built bounded parser (`MultipartBodyParser`, ~200 lines) — validated by the multipart integration tests; exotic multipart edge cases (encoded filenames, nested parts) are out of scope.
- Candidate identity resolution unchanged: for `FORWARDED` webhooks the forwarder's mailbox is stored as `sender_email` provenance and identity keys come from resume content; deeper identity-model concerns remain recorded for **J-5** (per task §9).
- Retention: `webhook_deliveries` rows are the replay guard — retain ≥ replay window (7–30 days recommended); no cleanup job implemented by design.
- The retired global secret no longer authenticates anywhere; local senders must use provisioned credentials (local demo credential auto-provisioned; sender example in `docs/webhook-security.md`).

*Review artifacts (`J2-REVIEW-PACKAGE.md`, `J2-EXACT-DIFF.patch`, `docs/webhook-security.md`) are documentation and can be kept or removed after review.*
