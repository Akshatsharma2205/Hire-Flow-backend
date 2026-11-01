# J-2A Review Package — Corrective Webhook Transport, Signature v2, Limits & Startup Safety

Generated: 2026-11-01 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repository)
Pre-J2A snapshot: `C:\Users\ragha\AppData\Local\Temp\opencode\j2a-snapshot-20261101-095940`
Pre-J2A Surefire archive: `C:\Users\ragha\AppData\Local\Temp\opencode\j2a-presurefire-20261101-095940`
Final Surefire/log archive: `C:\Users\ragha\AppData\Local\Temp\opencode\j2a-final-surefire-20261101-104213`

> **Decision context:** J-2 remains **unapproved** pending review of this J-2A corrective package.
> `J2-REVIEW-PACKAGE.md` and `J2-EXACT-DIFF.patch` are preserved unchanged. Their claims about the
> v1 9-line canonical string, the 50 MB per-attachment limit, the default local webhook secret,
> and implicit `local` startup are **superseded** by this package.

---

## A. Scope, baseline and constraints

J-2A addresses only the corrective findings raised against J-2. It does not begin J-3.

- J-1 authorization, IMAP/OAuth, Chrome extension, worker leases, matching, frontend, and
  unrelated candidate processing were not modified.
- Flyway V1–V4 were not edited. No `flyway clean`, `repair`, reset, or schema-history edit was run.
- No production or shared database was contacted. All automated tests use fresh in-memory H2
  databases (`jdbc:h2:mem:...`); the replay-durability test uses its own isolated H2 file.
- Every webhook fixture is synthetic (generated PDFs, random organizations/credentials/nonces).
- Changed source/test surface versus the pre-J2A snapshot: **28 files, 2,869 insertions,
  349 deletions** (`git diff --no-index --stat`, snapshot vs current `src`). Two documentation
  files (`docs/webhook-security.md`, `hireflow-backend/README.md`) and this package are additional.

**Status labels used in this report:** only
`FIXED AND VERIFIED` · `IMPLEMENTED BUT NOT FULLY VERIFIED` · `PARTIALLY FIXED` · `UNVERIFIED` · `FAILED`.

### Status summary

| Area | Status |
|---|---|
| Multipart parsing and real-container transport | **FIXED AND VERIFIED** |
| v2 signature, Content-Type binding, no v1 downgrade | **FIXED AND VERIFIED** |
| Query-string rejection and authenticated-body metadata | **FIXED AND VERIFIED** |
| Raw-body, per-attachment, aggregate, count, part, header, metadata, concurrency limits | **FIXED AND VERIFIED** |
| Oversized metadata → HTTP 400, no truncation | **FIXED AND VERIFIED** |
| Explicit-profile startup guard | **FIXED AND VERIFIED** |
| Opt-in local seeding, no default secret, production impossibility | **FIXED AND VERIFIED** |
| Sender documentation and example updated to v2 | **FIXED AND VERIFIED** |
| J-2 protections preserved (credentials, HMAC, replay, idempotency, provenance) | **FIXED AND VERIFIED** |
| J-1 files and Flyway V1–V4 unchanged | **FIXED AND VERIFIED** |
| PostgreSQL 16 verification | **UNVERIFIED** — NOT EXECUTED (Docker daemon unreachable) |

---

## B. Defects confirmed before any edit

The pre-J2A snapshot was inspected directly. These were real defects, not test-only concerns:

| Confirmed pre-J2A state | Impact | Corrective action |
|---|---|---|
| `MultipartBodyParser` located `--boundary` anywhere, accepted bare-LF separators, used naive `indexOf` filename parsing, and had no part/header limits | Boundary-like bytes in content could terminate a part; malformed/truncated bodies could be accepted; ambiguous filenames | Replaced with a strict, bounded RFC 2046 parser |
| `ForwardingWebhookController` received `senderEmail`, `subject`, etc. via `@RequestParam` | Metadata could come from the URL query string, not only the signed body | Metadata is now read only from `WebhookMultipartBody` or the JSON body |
| `truncate(value, maxLength)` silently shortened sender/subject/source/filenames | Oversized metadata was accepted with lost content | Oversized metadata now returns `400`; no silent truncation |
| Signature canonical v1 was 9 lines and did not include Content-Type | A signed body could be replayed with a different media type or multipart boundary | v2 adds the validated canonical Content-Type; no downgrade path |
| Query strings were not rejected by the filter | Query-string metadata could bypass the signed-body rule | Query strings are rejected with `400` before ingestion |
| `maxAttachmentBytes` default was 52,428,800 while `maxBodyBytes` was 26,214,400; no aggregate, part-count, header-size, or concurrency limit | Incoherent limits; base64 expansion could exceed the body cap; unbounded part/header work; unbounded concurrent buffering | Coherent limits with startup validation, base64-expansion accounting, part/header caps, and a 4-permit semaphore |
| `application.yml` had `spring.profiles.default: local` and a default `local-dev-secret: hireflow-local-webhook-secret`; the local seeder ran automatically | A plain start silently used development defaults and a known secret | No default profile; startup guard; opt-in seeding with an explicit ≥32-character secret |
| No test started the real servlet container for multipart | MockMvc could short-circuit container multipart behavior | Added `RANDOM_PORT` raw-HTTP tests against embedded Tomcat |

---

## C. Multipart parsing and transport — FIXED AND VERIFIED

Implementation: `security/MultipartBodyParser.java`, `security/HeaderParameters.java`,
`security/WebhookContentType.java`, `security/WebhookMultipartBody.java`,
`security/WebhookBodyCachingRequestWrapper.java`.

Verified properties:

- First boundary must start at byte zero; preambles are rejected.
- Subsequent delimiters must be exactly `CRLF--<boundary>` followed by `CRLF` or `--`.
  Boundary-like bytes inside part content are preserved, not treated as delimiters.
- Part headers end at the exact `CRLFCRLF`; content starts immediately after it.
- Bare-LF header lines, duplicate `Content-Disposition` headers, duplicate parameters,
  malformed quoted values, and unsupported `filename*` charsets are rejected.
- Quoted boundaries are parsed correctly; the canonical signed form is always
  `multipart/form-data; boundary="<escaped boundary>"`.
- Filename backslash escapes are decoded (unit-tested), and RFC 5987 `filename*` is supported
  where valid.
- Missing `name`, missing filename on `files`, unknown fields, duplicate metadata fields, and
  metadata sent as a file are rejected.
- Missing closing boundary, truncated closing boundary, and trailing data after the closing
  boundary are rejected.
- Part bodies are raw bytes and are never text-decoded.

Tests:

- `MultipartBodyParserTest` — **18/18 passed** (unit).
- `WebhookRequestContractIntegrationTest` — **12/12 passed** (MockMvc, exact raw bytes).
- `WebhookEmbeddedContainerIntegrationTest` — **3/3 passed** on a real embedded Tomcat with
  `webEnvironment = RANDOM_PORT` and raw HTTP:
  1. quoted-boundary binary multipart is accepted and `StoragePort.retrieve()` returns the
     exact signed part bytes byte-for-byte (including NUL, `0xFF`, CR/LF, and a
     boundary-like byte sequence inside the content);
  2. truncated multipart is rejected with `400` by the real container and creates no
     delivery/message/attachment rows;
  3. duplicate `Content-Type` headers sent over a raw socket are rejected with `415`.

`MockMvc` alone was not used to claim the container contract.

---

## D. v2 signature and Content-Type binding — FIXED AND VERIFIED

Canonical string (LF-joined, exactly 10 lines, no trailing newline):

```
v2
{organizationId}
{keyId}
{timestamp}
{nonce}
{METHOD}
{requestPath}
{idempotencyKey}
{canonicalContentType}
{sha256(rawBody)}
```

`canonicalContentType` is derived from the validated request header:

- JSON → exactly `application/json` (optional charset parameters are normalized out).
- Multipart → exactly `multipart/form-data; boundary="<escaped boundary>"`.

The boundary itself is validated (1–70 RFC 2046 `bchars`, no trailing space) and is signed.
Content-Type parsing rejects missing, duplicate, unsupported, and malformed values; JSON and
multipart are the only accepted media types.

No v1 fallback exists. `WebhookAuthFilter.SIGNATURE_VERSION` is `v2`; a request signed with the
former 9-line v1 canonical string is rejected `401`. The server never trusts a client-supplied
body digest: it recomputes SHA-256 from the exact buffered bytes. Signature comparison uses
`MessageDigest.isEqual`.

Tests:

- `WebhookContentTypeTest` — **15/15 passed** (valid/invalid media types, quoted boundaries,
  escaping, boundary character/length rules, duplicate parameters).
- `WebhookRequestContractIntegrationTest` — v1 rejection, boundary binding, unquoted canonical
  mismatch, missing/unsupported Content-Type, JSON charset normalization.
- `WebhookAuthIntegrationTest` — **23/23 passed** (valid JSON/multipart signatures, altered body,
  wrong org/key, revoked key, timestamp, nonce, no interactive authority).

Sender documentation and the Java/curl example now use the 10-line v2 contract, including the
multipart canonical form (see `docs/webhook-security.md` §3 and §10).

---

## E. Query strings and metadata provenance — FIXED AND VERIFIED

- `WebhookAuthFilter` rejects any request with a non-null query string with `400` before
  credential lookup, signature verification, body buffering, or ingestion.
- The controller no longer uses `@RequestParam`. Multipart metadata comes only from
  `WebhookMultipartBody.Part`; JSON metadata comes only from the signed `@RequestBody`.
- Unknown, duplicate, missing-name, and metadata-as-file parts are rejected.
- `originalSenderEmail` remains separate provenance (`original_sender_claim`); it is never
  treated as authenticated identity.

Tests: `WebhookRequestContractIntegrationTest` query-rejection test (including a spoofed
`senderEmail` query parameter), metadata-as-file, unknown/duplicate/missing-name tests, each with
zero delivery/message/attachment/PENDING-job rows.

---

## F. Size, shape, metadata and concurrency limits — FIXED AND VERIFIED

Defaults (all configurable through `hireflow.webhook.*` / environment variables):

| Limit | Default | Result |
|---|---|---|
| Raw body | 26,214,400 bytes (25 MiB) | `413` |
| Single attachment, decoded | 10,485,760 bytes (10 MiB) | `413` |
| All attachments, decoded | 16,777,216 bytes (16 MiB) | `413` |
| Attachments per delivery | 20 | `413` |
| Multipart parts (metadata + files) | 32 | `413` |
| One multipart part header block | 8,192 bytes | `413` |
| Textual metadata aggregate | 16,384 bytes | `400` |
| Concurrent body buffers/parses | 4 | `503` when busy |

Per-field metadata caps are also enforced: sender email 320 bytes, subject 4,000 bytes, source
type 30 bytes, job id 36 bytes, filename 1,024 bytes. Oversized values are rejected, never
truncated.

Base64 is checked twice: encoded length is bounded before decoding, then decoded length is
bounded. `HireFlowProperties.WebhookProperties` refuses to start with incoherent limits, including
when the base64-expanded aggregate plus metadata/envelope could exceed `max-body-bytes`.

Tests:

- `WebhookSizeLimitIntegrationTest` — **11/11 passed**: oversized JSON body, oversized multipart
  body, oversized multipart part, excessive attachment count, oversized decoded base64,
  oversized encoded base64 before decoding, aggregate decoded size, excessive part count,
  oversized metadata (`400`, no truncation), and valid requests exactly at the body/attachment
  boundaries.
- `WebhookIntakeConcurrencyTest` — **1/1 passed**: with a limit of one permit, the first request
  is held in the downstream chain; the second concurrently signed request is rejected `503`
  ("Webhook intake is busy; retry later") before reaching the chain.
- `HireFlowPropertiesValidationTest` — **11/11 passed** (coherent/incoherent limit combinations,
  including base64 expansion).
- `MultipartBodyParserTest` — header-block and part-count limits.

All rejection tests assert the HTTP status and no database writes.

---

## G. Startup profile and local credential seeding — FIXED AND VERIFIED

`StartupProfileGuard` is registered through `META-INF/spring.factories` and runs before context
refresh. It:

- refuses startup when `SPRING_PROFILES_ACTIVE` is absent/blank;
- accepts only known profiles `local`, `test`, `prod`;
- rejects an active set mixing `prod` and `local`;
- rejects known profiles mixed with unknown profiles.

There is no `spring.profiles.default` in `application.yml`. `application-local.yml` does not
supply a webhook secret. `application-prod.yml` no longer contains the obsolete
`hireflow.webhook.secret`. `ProductionSafetyConfig` rejects enabled local seeding, and also rejects
a local-dev secret being present in production even when seeding is disabled.

`LocalWebhookDevCredentialSeeder` requires all of:

1. `local` profile;
2. `WEBHOOK_LOCAL_DEV_ENABLED=true`;
3. `WEBHOOK_LOCAL_DEV_SECRET` of at least 32 characters.

There is no default secret in code or configuration. The secret is encrypted at rest like any
credential and is never logged. The seeder is idempotent for the demo key id.

Precise local activation command (from `hireflow-backend/`):

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

To opt in to the demo credential:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:WEBHOOK_LOCAL_DEV_ENABLED = "true"
$env:WEBHOOK_LOCAL_DEV_SECRET = "<your-own-random-secret-of-32-or-more-characters>"
.\mvnw.cmd spring-boot:run
```

Tests:

- `StartupProfileGuardTest` — **6/6 passed** (missing, unknown, mixed local+prod, registration).
- `LocalWebhookDevCredentialSeederTest` — **5/5 passed** (encrypted storage, no secret in logs,
  idempotency, disabled, missing/short secret).
- `ProductionSafetyConfigTest` — **3/3 passed**.

---

## H. Documentation — FIXED AND VERIFIED

`docs/webhook-security.md` was rewritten for J-2A:

- v2 10-line canonical string, canonical Content-Type rules, no v1 mode;
- strict multipart wire contract and quoted/escaped boundary form;
- all current limits and status codes;
- oversized metadata → `400`, never truncated;
- local seeding opt-in with the explicit PowerShell activation command and no default secret;
- updated curl and Java examples (no real secrets).

`hireflow-backend/README.md` was updated:

- startup now requires an explicit profile; local command uses `SPRING_PROFILES_ACTIVE=local`;
- removed the retired `X-HireFlow-Webhook-Secret` credential row and `WEBHOOK_SECRET` production
  variable;
- documented the opt-in local webhook credential and linked the security contract.

A residual, intentionally untouched frontend artifact is recorded in §M.

---

## I. Automated tests added or expanded

| Class | Final tests | Focus |
|---|---:|---|
| `security/MultipartBodyParserTest` | 18 | strict parser invariants |
| `security/WebhookContentTypeTest` | 15 | Content-Type parsing/canonicalization |
| `security/WebhookIntakeConcurrencyTest` | 1 | semaphore `503` fail-closed |
| `config/HireFlowPropertiesValidationTest` | 11 | coherent limits/base64 expansion |
| `config/StartupProfileGuardTest` | 6 | explicit/known profile guard |
| `config/ProductionSafetyConfigTest` | 3 | production rejects local seeding/secret |
| `webhook/LocalWebhookDevCredentialSeederTest` | 5 | opt-in, encryption, no logging |
| `WebhookRequestContractIntegrationTest` | 12 | raw-byte adversarial transport |
| `WebhookEmbeddedContainerIntegrationTest` | 3 | real Tomcat `RANDOM_PORT` raw HTTP |
| `WebhookSizeLimitIntegrationTest` | 11 | all size/count/metadata limits |
| `WebhookAuthIntegrationTest` | 23 | preserved J-2 auth/replay/idempotency |
| `WebhookDeliveryIntegrityIntegrationTest` | 5 | exactly-one-chain + provenance |
| `WebhookReplayDurabilityIntegrationTest` | 2 | durable replay across restart |

The contract and embedded-container tests use independent raw multipart/signature builders in
`support/WebhookTestSupport`, not the production parser or signer.

---

## J. Commands, exit codes and exact results

All commands were run from `C:\Users\ragha\Desktop\hireflow\hireflow-backend`.

| When (IST) | Command | Exit | Result |
|---|---|---:|---|
| 2026-11-01T10:17:15 finish | `.\mvnw.cmd -o -Dtest="MultipartBodyParserTest,WebhookContentTypeTest,HireFlowPropertiesValidationTest,StartupProfileGuardTest,LocalWebhookDevCredentialSeederTest,ProductionSafetyConfigTest" test -DskipTests=false` | 0 | **58/58 passed** |
| 2026-11-01T10:20:59 finish | `.\mvnw.cmd -o -Dtest="WebhookRequestContractIntegrationTest" test -DskipTests=false` | 0 | **12/12 passed** |
| 2026-11-01T10:26:38 finish | `.\mvnw.cmd -o -Dtest="WebhookEmbeddedContainerIntegrationTest" test -DskipTests=false` | 0 | **2/2 passed** (initial container suite, before the duplicate-header test) |
| 2026-11-01T10:29:30 finish | `.\mvnw.cmd -o -Dtest="WebhookSizeLimitIntegrationTest" test -DskipTests=false` | 0 | **11/11 passed** |
| 2026-11-01T10:31:05 finish | `.\mvnw.cmd -o -Dtest="WebhookAuthIntegrationTest,WebhookDeliveryIntegrityIntegrationTest,WebhookReplayDurabilityIntegrationTest,WebhookRequestContractIntegrationTest,WebhookSizeLimitIntegrationTest,WebhookEmbeddedContainerIntegrationTest" test -DskipTests=false` | 0 | **55/55 passed** (before the final two tests were added) |
| 2026-11-01T10:39:24 finish | `.\mvnw.cmd -o -Dtest="WebhookIntakeConcurrencyTest,WebhookEmbeddedContainerIntegrationTest" test -DskipTests=false` | 0 | **4/4 passed** (1 concurrency + 3 container) |
| **2026-11-01T10:42:03 finish** | **`.\mvnw.cmd -o test -DskipTests=false`** | **0** | **25 suites / 255 tests / 0 failures / 0 errors / 0 skipped** |

Final full-suite log: `target\j2a-full-suite-final-20261101.log` (created 10:39:27, finished
10:42:03, 501,080 bytes). Surefire XML: first written 10:40:23.386, last written 10:42:02.707.
The counts below were parsed programmatically from the 25 XML files, not copied from console text:

```
ApplicationLifecycleIntegrationTest                         2
AuthTenantIntegrationTest                                   7
HireFlowPropertiesValidationTest                           11
ProductionSafetyConfigTest                                  3
StartupProfileGuardTest                                     6
LocalSmartHeuristicExtractorTest                            5
IngestionAuthorizationIntegrationTest                      14
IntakePipelineIntegrationTest                               2
JwtRevocationAndTenantStatusEnforcementTest                10
MatchServiceTest                                            5
ProcessingWorkerTest                                        4
ProductionDemoCredentialIsolationTest                       8
MultipartBodyParserTest                                    18
RoleNormalizerTest                                         49
RoleValidationAndHardeningIntegrationTest                  19
WebhookContentTypeTest                                     15
WebhookIntakeConcurrencyTest                                1
UserMembershipRoleAuthorizationTest                        15
LocalWebhookDevCredentialSeederTest                         5
WebhookAuthIntegrationTest                                 23
WebhookDeliveryIntegrityIntegrationTest                     5
WebhookEmbeddedContainerIntegrationTest                     3
WebhookReplayDurabilityIntegrationTest                      2
WebhookRequestContractIntegrationTest                      12
WebhookSizeLimitIntegrationTest                            11
TOTAL                                                     255
```

Archived copies of all 25 XML files, the final log, and the Docker evidence file are in
`C:\Users\ragha\AppData\Local\Temp\opencode\j2a-final-surefire-20261101-104213`.

---

## K. J-1 and Flyway preservation — FIXED AND VERIFIED

Re-hashed after the final test run; all values equal the pre-J2A values:

```
7B0B5535EF89244205789A0892B5FEC5DE97E521D27FC1902DD95E4D5A8D6FD8  DirectUploadController.java
0F976F14D148D0323500F62A0BAFEA3881F19A3885EDBBB3F33CFB00DF1F6806  ExtensionIntakeController.java
DD90C6BBEAD0358590C885DF0226B5942BABD9CF2AE8D287B20CAF04E43E921E  AuthController.java
CFB43E07E05BAC0DE3AB1A3C1BC0C6266E71F801E1E2CB68099D51517444E678  IngestionAuthorizationIntegrationTest.java

D4C819B790F106941DBABAAF82414D4F632B886F79A0AD7E462468B5394F49DB  V1__init_schema.sql
A58C334D157005DA0EF3B01CEBA46E38A487D8D6C88BBBB0B2533F36EE339844  V2__seed_data.sql
5745E9002233AF2FD74BA247F98B33E00292DA42BF169C215CE436A8844760C2  V3__performance_indices.sql
7FB5BCD02B168C111D873D1E5ADF492A3F963C70F751342A24E14F2D9F0AF6FD  V4__webhook_security_and_idempotency.sql
```

- J-1 suite (`IngestionAuthorizationIntegrationTest`) remains **14/14 passed**.
- Flyway applied V1→V4 cleanly in every Spring test context; the log shows
  “Successfully applied 4 migrations … now at version v4”. No clean/repair was executed.
- Per-organization credentials, 256-bit secrets, AES-256-GCM at rest, OWNER/ADMIN provisioning,
  one-time secret display, rotation/revocation, HMAC, constant-time compare, timestamp/nonce,
  durable idempotency, DB constraints, atomic persistence, duplicate semantics, and provenance
  remain covered by the preserved J-2 suites (all green in the final suite).

---

## L. PostgreSQL verification — UNVERIFIED (NOT EXECUTED)

One bounded Docker daemon check was performed; it was not retried:

```
Command: docker version --format '{{.Server.Version}}'
Exit code: 1
Output: failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine;
        check if the path is correct and if the daemon is running:
        open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
```

Evidence file: `hireflow-backend/target/j2a-docker-check-20261101.txt` (copied into the final
Surefire archive).

**PostgreSQL 16 verification: NOT EXECUTED.** No PostgreSQL behavior is claimed. All database
testing in this package used H2 in PostgreSQL compatibility mode. No shared or production
database was used. The V4 SQL and reservation logic remain dialect-neutral by construction, but
that is a code-review observation, not a PostgreSQL execution result.

---

## M. Residual items, limitations and final status

1. **PostgreSQL 16** — `UNVERIFIED` (NOT EXECUTED; Docker daemon unreachable). This is the only
   required J-2A area without an execution result.
2. **Frontend demo console** — `src/main/resources/static/index.html` still contains the old
   `DEMO_WEBHOOK_SECRET` / `X-HireFlow-Webhook-Secret` demo path. It was intentionally not modified
   because frontend changes are outside the J-2A scope. That console path will not authenticate
   under v2; sender integrations must use provisioned credentials and the documented v2 signature.
   This does not affect the server-side webhook contract, which is fully covered by the tests
   above.
3. **Retention** — `webhook_deliveries` remains the durable replay guard. Retain rows at least
   the replay window (7–30 days recommended); no cleanup job was added in J-2A.
4. **Concurrency test scope** — the semaphore is tested at filter level with a blocking
   downstream chain. It proves fail-closed `503` behavior; it is not a load/soak test.
5. **Per-organization credential lifecycle, replay, idempotency, and provenance** were not
   redesigned in J-2A. They remain the J-2 implementation and are validated by the preserved
   suites (all green in the final 255-test run).

**Final J-2A status: FIXED AND VERIFIED for all corrective code, transport, signature, limit,
startup, seeding, documentation and preservation requirements. PostgreSQL 16 remains UNVERIFIED
because it was NOT EXECUTED. J-2 remains pending reviewer approval.**
