# J-2B Review Package — Transaction-Safe Attachment Storage Remediation

Generated: 2026-11-01 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repository; comparisons made against a filesystem snapshot)
Pre-J2B snapshot: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b-snapshot-20261101-122547` (full `src` + `pom.xml` + `docker-compose.yml`, plus `src-manifest-before.sha256`)
Pre-J2B Surefire archive (final J-2A evidence): `C:\Users\ragha\AppData\Local\Temp\opencode\j2b-presurefire-20261101-122547` (25 suites, 255 tests, 0 failures/errors/skipped)
Final Surefire/log archive: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b-final-surefire-20261101-125146` (27 suites, 268 tests, 0 failures/errors/skipped + full-suite log)

> **For reviewer:** this is a read-only review of a completed, verified implementation. J-3 was not started. J-1 and J-2/J-2A files and tests were not weakened or rewritten. No Flyway migration was added; V1–V4 are byte-identical to the pre-J2B snapshot. No `flyway clean`/`repair` was run.

**Status labels used in this report:** only
`FIXED AND VERIFIED` · `IMPLEMENTED BUT NOT FULLY VERIFIED` · `PARTIALLY FIXED` · `UNVERIFIED` · `FAILED`.

### Status summary

| Area | Status |
|---|---|
| Storage/database consistency under rollback (storage failure, DB failure, commit failure) | **FIXED AND VERIFIED** |
| Concurrency safety for identical concurrent uploads (no deletion of a committed object) | **FIXED AND VERIFIED** |
| Attachment exception handling (atomic webhook delivery, no silent partial acceptance) | **FIXED AND VERIFIED** |
| Storage adapter coverage (unique keys, delete, idempotency, path traversal) | Local **FIXED AND VERIFIED**; S3 **IMPLEMENTED BUT NOT FULLY VERIFIED** (no live S3 in this environment) |
| Failure-injection tests (A–G + skip-contract H) | **FIXED AND VERIFIED** |
| Crash/orphan reconciliation (JVM death between store and commit) | **PARTIALLY FIXED** — in-process compensation implemented; crash-orphan reconciliation documented but not implemented |
| Memory sizing | **UNVERIFIED** — static analysis only; no limits changed, no JVM flags added, no safe minimum heap claimed |
| PostgreSQL 16 compatibility | **UNVERIFIED** — H2 (PostgreSQL mode) only; no real PostgreSQL 16 instance executed |

---

## A. Executive summary

The independent audit's findings were verified against the current source and confirmed:

1. `StoragePort` had **no delete operation**.
2. Both adapters wrote to a **deterministic, shared, content-addressed key** (`{orgId}/{sha256}{ext}`) and **overwrote** it (`TRUNCATE_EXISTING` / `putObject`). A physical write happened before the database records were committed.
3. `MessageIngestionService.getOrStoreResumeFile()` wrote the physical object first and only then saved the `resume_files` row; any later failure rolled back the DB rows but left the object on disk/S3.
4. `MessageIngestionService.ingestFromWebhook()` wrapped each attachment in a blanket `catch (Exception)` that **swallowed** storage/persistence failures, producing a committed partial delivery (message + earlier attachments) and an HTTP 202 whose `acceptedCount` silently omitted the failed attachment.

The correction implements the audit's proposed design direction, verified to fit the actual data model:

- **Unique object keys per store call** (`{orgId}/{sha256}/{uuid}{ext}`): a physical object is owned by exactly one transaction's store call and can never be the target of a compensating delete by another transaction.
- **Transaction-synchronization rollback compensation**: a newly stored object is registered for deletion only after a successful store, and is deleted only when the owning transaction rolls back (`afterCompletion(STATUS_ROLLED_BACK)`). Reused existing `ResumeFile` rows are never registered. `STATUS_UNKNOWN` deliberately does not delete.
- **Unique-violation fallback**: when two transactions race to insert the same `(organization_id, sha256)`, the loser explicitly abandons its own unique object (its transaction continues/commits against the winner's row) — with the object still registered so a rollback callback is a safe, idempotent retry.
- **Atomic webhook delivery**: any attachment storage/persistence failure now propagates and rolls back the whole delivery (message, attachments, jobs, ledger row); the sender gets HTTP 500 and retries cleanly with the same idempotency key. Non-resume attachments remain an intentional, documented skip (acceptedCount/attachmentIds enumerate exactly what was accepted).

Verification: **268 tests, 27 suites, 0 failures, 0 errors, 0 skipped** (was 255/25). The 8 new failure-injection tests and 5 new adapter tests assert both database state and physical storage state, including real concurrent uploads (Test E), a real commit-stage database failure via an H2 trigger (Test C), and cleanup-failure observability (Test F).

**Recommendation: APPROVE.**

---

## B. Exact failure mechanism before correction

Verified call chain (webhook channel):

```
WebhookIngestionService.ingestVerifiedDelivery()      @Transactional (REQUIRED)
→ MessageIngestionService.ingestFromWebhook()         same transaction (REQUIRED)
→ getOrStoreResumeFile()                              (public; all 5 channels)
   → resumeFileRepo.findByOrganizationIdAndSha256()   dedup lookup
   → StoragePort.store(orgId, sha256, mime, data)     ← physical write FIRST
       → LocalDiskStorageAdapter:  {orgId}/{sha256}{ext} + TRUNCATE_EXISTING
       → S3StorageAdapter:         {orgId}/{sha256}{ext} + putObject (overwrite)
   → resumeFileRepo.save(rf)                          deferred flush (INSERT at next flush/commit)
→ attachmentRepo.save(...)
→ enqueueParseJob(...)                                 processing job
→ database transaction commit
```

Defects confirmed by reading the code (not merely the audit's line references):

| # | Defect | Evidence |
|---|---|---|
| 1 | No delete capability anywhere in the storage port | `StoragePort` had 4 methods; zero delete |
| 2 | Deterministic shared keys + overwrite | `LocalDiskStorageAdapter.store` line 32 `orgId + "/" + sha256 + ext` with `StandardOpenOption.TRUNCATE_EXISTING`; `S3StorageAdapter.store` same key with `putObject` |
| 3 | Object written before row committed | `getOrStoreResumeFile`: `store()` then `save()` — a DB failure, a later flush failure, or a commit failure orphaned the object with no cleanup path |
| 4 | Blanket swallow in the webhook attachment loop | `ingestFromWebhook` lines 234–258: `catch (Exception e) { log.warn(...) }` around each attachment — storage failure on attachment N left message + attachments 1..N-1 committed, returned HTTP 202 with `acceptedCount = N-1` and no error signal |
| 5 | Race hazard on the shared key | Two concurrent identical uploads: both could pass the `findBy...` dedup check, both `store()` to the SAME key (last write wins), both attempt the INSERT; the DB `uq_resumefiles_org_sha256` constraint arbitrated, but the physical object was shared — any compensation deletion would be ambiguous. (This is the exact hazard the audit forbids implementing.) |

Transaction propagation confirmed: `ingestVerifiedDelivery` and `ingestFromWebhook` both default to `REQUIRED` → one transaction; `WebhookDeliveryStore.tryReserve`/`markAccepted` use `MANDATORY` → same transaction. The delivery ledger row, message, attachments, jobs and `resume_files` rows commit or roll back together. Only the physical objects were outside transaction control.

## C. Storage design and concurrency analysis

### C.1 Chosen design

The audit's proposed direction was adopted because it fits the existing schema with **zero migration** (`resume_files.s3_object_key` already records the actual object key; `uq_resumefiles_org_sha256` unchanged) and both adapters:

1. **Unique keys.** Every `StoragePort.store(orgId, sha256, mimeType, data)` call now returns a fresh `{orgId}/{sha256}/{uuid}{ext}` key. `LocalDiskStorageAdapter` uses `CREATE_NEW` (a collision is an error, never an overwrite); `S3StorageAdapter` `putObject`s the unique key (S3 overwrite semantics are irrelevant because no two calls produce the same key).
2. **Register after store, delete only on rollback.** `getOrStoreResumeFile` registers a `TransactionSynchronization` immediately after a successful `store()`. `afterCompletion` deletes the object **only** for `STATUS_ROLLED_BACK`; `STATUS_COMMITTED` keeps it (the row references it); `STATUS_UNKNOWN` is deliberately conservative — the object is left for reconciliation rather than risking deletion of an object whose row may have committed.
3. **Loser-of-race handling.** The `resume_files` INSERT is now `saveAndFlush` so the unique-constraint violation surfaces deterministically inside `getOrStoreResumeFile`. The loser abandons its own unique object explicitly (`abandonNewObject`) and returns the winner's row. The abandoned object stays registered, so if the transaction later rolls back (observed: the failed flush poisons the Hibernate session), the rollback callback re-deletes it — idempotent, safe.
4. **Delete is idempotent.** `delete()` of a missing object is a no-op on both adapters (S3 delete semantics; `Files.deleteIfExists`), so explicit abandonment + rollback compensation can both target the same key without error.

### C.2 Why this is safe under concurrent identical uploads

The audit's forbidden pattern was: deterministic shared key + register `delete(key)` on rollback. The danger: transaction A commits while transaction B (same content) rolls back; B's blind `delete(key)` destroys the object A's row references.

The chosen design removes the hazard structurally:

- **Key ownership is exclusive.** The UUID segment means only the transaction that called `store()` can possibly hold a row referencing that key. No other transaction ever writes to it.
- **The winner's object is unreachable by the loser.** The loser's compensation (whether explicit abandonment or rollback callback) targets only the key returned by the loser's own `store()` call. The winner's key is a different UUID; the loser never learns it and never touches it. **The failed transaction physically cannot delete the winner's object** — verified by Test E (assertion: `deleted` contains the loser key, `doesNotContain` the winner key) and Test G.
- **Reused rows are never registered.** If `findByOrganizationIdAndSha256` hits, no store happens and no cleanup is registered; a rollback can never touch the pre-existing object (Test G).
- **Database uniqueness is preserved.** `uq_resumefiles_org_sha256` still arbitrates the race; dedup on later uploads still goes through the same `findBy...` lookup (Tests D, E).

### C.3 Empirically observed race behavior (H2, Test E)

From the captured logs of `concurrentIdenticalUploadsKeepWinnerObjectAndDeleteOnlyLoserObject`:

```
WARN  ... Concurrent duplicate content; abandoning newly stored unique object:
      org=464a5752-... key=464a5752-.../21c86d72.../8cf9ba6b-...pdf
      cause=could not execute statement [Unique index or primary key violation:
      "public.uq_resumefiles_org_sha256_INDEX_6 ..."]
INFO  ... Rollback compensation deleted newly stored object: ... reason=unique-violation fallback to existing row
INFO  ... Rollback compensation deleted newly stored object: ... reason=transaction rolled back   (idempotent re-delete)
```

- Both threads stored (2 unique objects). The DB constraint let exactly one INSERT win.
- The loser's flush failed; the fallback abandoned its own object; the failed flush left the transaction rollback-only; the commit failed; the rollback callback re-deleted the already-deleted key (no-op). The loser's thread surfaced a database-layer exception (asserted: not a storage-layer exception), and a retry with identical content succeeds via dedup.
- Exactly one `resume_files` row remained, referencing the winner's object, byte-identical to the upload. The winner's key was never deleted.

This is honest behavior to document: under a genuine race, the loser does **not** commit with the winner's row on H2 (the Hibernate session is left undefined after the failed flush, per JPA spec §3.2.4); it fails at the database layer and the sender retries. The DB's dedup guarantee, and the storage safety guarantee, hold in every observed interleaving. On PostgreSQL the same constraint arbitration applies; the loser's post-failure behavior may differ in detail (session state is provider/runtime dependent) but the safety invariants are structural, not behavioral.

## D. Transaction rollback and compensation behaviour

| Failure point | DB outcome | Storage outcome | Mechanism |
|---|---|---|---|
| `StoragePort.store` throws | Tx rolls back (nothing stored) | nothing to clean | exception propagates (no swallow) |
| Store succeeds, `saveAndFlush` throws (non-constraint) | Tx rolls back: message/attachment/job/resume_files rows all gone | **object deleted** | `afterCompletion(STATUS_ROLLED_BACK)` |
| Store succeeds, later statement fails (e.g. `markAccepted` UPDATE at the latest feasible point) | Tx rolls back incl. ledger row | **object deleted** | same callback |
| Commit itself fails | Tx rolls back | **object deleted** | same callback |
| Unique-constraint race lost | Loser tx rolls back (observed) or commits against winner row | **loser's own object deleted**; winner untouched | explicit abandon + (idempotent) rollback callback |
| Cleanup delete fails | Original exception still propagates to caller | orphan remains, **logged** with org + key | callback catches, logs ERROR, never masks |
| JVM crash after store, before commit | — | orphan remains | **not covered** — see Section E |

Failed cleanup can never mask the original transaction failure: the callback runs in `afterCompletion`, after the original exception has already been chosen for propagation. Test F asserts the caller receives the original `DataAccessResourceFailureException` while the cleanup ERROR is logged with the orphan key.

Cleanup logs are deliberately free of candidate PII/secrets: they carry only org UUID + opaque object key + failure message.

## E. Crash/reconciliation limitations

**No claim of zero orphans is made.** A JVM termination between a successful `store()` and the transaction's `afterCompletion` cannot run the in-process callback, leaving an object that no row references.

An age-based orphan reconciliation is **required for full crash safety but was deliberately not implemented** in this narrowly scoped correction because:

- A robust durable-staging design needs either a schema marker (a Flyway migration, which the audit allows only if genuinely required) or a cross-store listing scan (S3 `ListObjectsV2` over the `{orgId}/{sha256}/` prefix), plus a scheduler. That is production-hardening scope beyond this correction, and the audit's own constraint is "do not claim complete crash safety if it is not implemented".
- The unique-key layout (`{orgId}/{sha256}/{uuid}{ext}`) was chosen to make such a reconciler trivial later: list objects under an org's prefix, cross-reference `resume_files.s3_object_key`, delete objects older than a retention window that are referenced by no row. Local disk can do this with a directory walk; S3 with prefix listing.

Status: **PARTIALLY FIXED** (in-process compensation covers every non-crash failure; crash orphans remain a documented, reconcilable gap).

## F. Exception handling changes

`MessageIngestionService.ingestFromWebhook` (webhook channel):

- **Removed** the blanket `catch (Exception) { log.warn }`. Any storage or persistence failure now propagates out of the `@Transactional` method, rolling back the entire delivery (message, all attachments, all jobs, ledger row) and triggering compensation. The controller maps it to **HTTP 500 `{"error":"Webhook processing failed"}`** — verified end-to-end in Tests A and C. A sender can never mistake a failed delivery for acceptance, and retrying with the same idempotency key re-enters cleanly (no ledger residue).
- **Kept and documented** the intentional skip for non-resume MIME types (existing J-2A contract): the attachment is not accepted; the response's `acceptedCount`/`attachmentIds` enumerate exactly what was accepted. Test H pins this contract: one text file + one PDF → `202`, `acceptedCount=1`, exactly one of each row, one stored object, zero deletions.
- **Not changed** (documented scope decision): `ingestDirectUpload`/`processZip` retain their per-file partial-acceptance contract (bulk channel returns the explicit list of accepted IDs; a rejected file is simply absent from that list). These channels share the corrected `getOrStoreResumeFile`, so their storage/rollback behavior is fixed too; only their bulk partial-acceptance semantics remain, which the audit permits when unambiguous.
- **`getOrStoreResumeFile`** catches only `DataIntegrityViolationException` (the dedup race) and re-throws everything else — no indiscriminate suppression of database exceptions.

## G. Failure-injection test evidence

New suite: `com.kartgen.hireflow.storage.TransactionSafeStorageFailureInjectionTest` (8 tests) using an observable in-memory `FakeStoragePort` that records every stored/deleted key, plus a real H2 trigger (`J2bFailOnWebhookAcceptTrigger`) for the commit-stage failure. Every test asserts **both** database state (real H2 + Flyway + Hibernate) **and** storage state (object map, delete log, key values, attempt counters). `LocalDiskStorageAdapterTest` (5 tests) covers the real adapter on a real temp directory.

| Test | Injection | Database assertions (all passed) | Storage assertions (all passed) | HTTP |
|---|---|---|---|---|
| A — second attachment storage failure | `failStoreAtAttempt=2` via real signed multipart request | message/attachment/job/resume_files/delivery counts all 0 | 2 attempts, 1 stored key, **0 objects left**, deleted == the one stored key, key prefix = `org/{sha(pdf1)}/` | 500, body `Webhook processing failed` |
| B — DB failure after storage | `@SpyBean ResumeFileRepository.saveAndFlush` throws `DataAccessResourceFailureException` | all counts 0 | stored 1, **0 left**, deleted == stored key | — (service level) |
| C — commit failure at latest feasible point | real H2 `BEFORE UPDATE` trigger on `webhook_deliveries` (fails the last write before commit) | delivery row gone, all counts 0 | stored 1, **0 left**, deleted == stored key | 500 |
| D — retry after rollback | first `saveAndFlush` throws; stub cleared; identical content retried | attempt 1: all 0; attempt 2: exactly 1 row each (message/attachment/job/resume_files) | attempt 1: 1 deleted; attempt 2: new unique key ≠ deleted key, retrievable == bytes, **1 object, no orphan** | — |
| E — concurrent identical uploads | 2 threads, same org+content, `CyclicBarrier` forces both stores before either insert | exactly 1 `resume_files` row; message count == success count (≥1) | 2 store attempts, **winner object survives** (key == row key, bytes equal), deleted contains loser key, **deleted does NOT contain winner key** | — |
| F — cleanup failure | `failAllDeletes=true` + `saveAndFlush` throws | all counts 0; caller sees **original** `DataAccessResourceFailureException` | delete attempted (1), failure recorded with key, orphan remains (honest), **ERROR log** `Rollback compensation FAILED ... key=...` captured via ListAppender | — |
| G — existing shared ResumeFile | pre-committed row + object; new ingestion reuses it; 1st store attempt (attachment 2) fails | pre-existing row count stays 1; new delivery rows 0 | **0 deletions, 0 delete calls**, shared object byte-identical and untouched; 1 store attempt (failed before write) | — |
| H — non-resume skip contract | real signed multipart: text file + PDF | 1 message, 1 attachment, 1 resume_files | 1 object, 0 deletions | 202, `acceptedCount=1`, 1 attachment id |

Adapter evidence (`LocalDiskStorageAdapterTest`, real filesystem): two stores with identical inputs produce distinct keys, both exist with correct bytes (`storeAlwaysCreatesUniqueKeysAndNeverOverwrites`); delete removes and repeated delete is a no-op (`deleteRemovesTheObjectAndIsIdempotentForMissingKeys`); path traversal rejected for delete and retrieve; 4096-byte binary round-trip preserved.

## H. Adapter coverage

| Requirement | LocalDiskStorageAdapter | S3StorageAdapter |
|---|---|---|
| Unique key per store | `{orgId}/{sha256}/{uuid}{ext}` + `CREATE_NEW` | `{orgId}/{sha256}/{uuid}{ext}` + `putObject` (unique) |
| Delete semantics | `deleteIfExists` — idempotent, missing = no-op | `deleteObject` — idempotent by S3 contract |
| Repeated delete | verified by test | idempotent (documented; not executed) |
| Missing-object handling | verified by test | S3 returns success for missing keys |
| Path-traversal protection | `startsWith(base)` guard on store/retrieve/delete — verified by tests | n/a for S3 keys (no filesystem paths) |
| Organization isolation | org UUID is the first key segment | same |
| Storage exception handling | `IllegalStateException` wrap (store/retrieve/delete) — compensation logs it | same |
| No deletion of committed/shared objects | guaranteed by unique-key ownership (Tests E/G) | same design |

S3 is compiled and consistent with the strategy, but no live S3/MinIO instance was available in this environment → **IMPLEMENTED BUT NOT FULLY VERIFIED**. The fallback reconciliation mechanism (age-based scan of unique keys cross-referenced against `resume_files`) is documented in Section E for both adapters.

## I. Memory analysis and changes made

**No memory changes were made.** Static inspection of the allocation paths confirms the audit's concern is real:

- `WebhookBodyCachingRequestWrapper`: reads the raw body (up to 26,214,400 B) into a `ByteArrayOutputStream` (initial 16 KB) and retains a `toByteArray()` copy.
- `MultipartBodyParser`: `copyOfRange` per part (whole-body copies), base64 decode via `ByteArrayOutputStream` + `toByteArray()` copy, plus Jackson `TextBuffer` allocations for the JSON path.
- `ForwardingWebhookController`: decoded attachment arrays (aggregate cap 16,777,216 B) retained for the whole ingestion.
- With 4 concurrent webhook permits, transient memory can reach several × (raw body + decoded attachments + parse buffers). **The prior claim that a 512 MiB heap cannot OOM is unsupported**, and no minimum safe heap is invented here.

Per the audit's constraints, the following were **not** applied: no `-XX:G1HeapRegionSize` or other GC flags; no limit changes (all webhook limits remain exactly as configured and enforced by the existing J-2A tests — signature verification, exact bytes, size enforcement and rejection semantics are unchanged and still covered by `WebhookSizeLimitIntegrationTest`, `MultipartBodyParserTest`, `WebhookContentTypeTest`, `WebhookEmbeddedContainerIntegrationTest`); no heap-size claim.

A narrowly scoped optimization (initial capacity from the validated `Content-Length`/part-length, streaming/spooling, or earlier reference release) would require profiling on real workloads to justify and correctness tests for the byte-exact signature contract; that is out of scope for this correction. **Status: UNVERIFIED** (memory sizing), with the recommendation to profile under load before changing anything.

## J. Regression test evidence

Verification sequence (section 9 of the task), all executed:

| Step | Command | Result |
|---|---|---|
| 1. Compile + test-compile | `./mvnw.cmd -q -DskipTests compile`; `./mvnw.cmd -q test-compile` | exit 0, exit 0 |
| 2. New focused tests | `./mvnw.cmd test -Dtest='TransactionSafeStorageFailureInjectionTest,LocalDiskStorageAdapterTest'` | 13/13 pass |
| 3. MessageIngestionService consumers | ProcessingWorkerTest, IntakePipelineIntegrationTest, WebhookEmbeddedContainerIntegrationTest | pass (in full run) |
| 4. Webhook delivery/replay/request-contract/container | WebhookAuth(23), WebhookDeliveryIntegrity(5), WebhookReplayDurability(2), WebhookRequestContract(12), WebhookEmbeddedContainer(3), WebhookSizeLimit(11), WebhookContentType(15), WebhookIntakeConcurrency(1) | all pass |
| 5. J-1 authorization | IngestionAuthorizationIntegrationTest(14), UserMembershipRoleAuthorizationTest(15), AuthTenantIntegrationTest(7), JwtRevocationAndTenantStatusEnforcementTest(10), RoleValidationAndHardeningIntegrationTest(19) | all pass |
| 6. Full suite | `./mvnw.cmd test` | **268 tests, 27 suites, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS, exit 0** |

Full-suite run: started 12:50:14, completed 12:51:34 (~80 s). Evidence archived to `j2b-final-surefire-20261101-125146` (reports + `j2b-full-suite-<ts>.log`). Final J-2A evidence preserved at `j2b-presurefire-20261101-122547` (25 suites / 255 tests / 0/0/0) before any test command overwrote `target/surefire-reports`.

PostgreSQL 16 remains **UNVERIFIED**: all tests run against H2 in PostgreSQL compatibility mode (`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`); no real isolated PostgreSQL 16 instance was executed in this environment, and nothing here claims H2 proves PostgreSQL compatibility.

## K. Preservation hashes and changed-file list

### Preservation (unchanged, byte-identical to pre-J2B snapshot / J-1 package)

| File | SHA-256 |
|---|---|
| `src/main/resources/db/migration/V1__init_schema.sql` | `d4c819b790f106941dbabaaf82414d4f632b886f79a0ad7e462468b5394f49db` |
| `src/main/resources/db/migration/V2__seed_data.sql` | `a58c334d157005da0ef3b01ceba46e38a487d8d6c88bbbb0b2533f36ee339844` |
| `src/main/resources/db/migration/V3__performance_indices.sql` | `5745e9002233af2fd74ba247f98b33e00292da42bf169c215ce436a8844760c2` |
| `src/main/resources/db/migration/V4__webhook_security_and_idempotency.sql` | `7fb5bcd02b168c111d873d1e5adf492a3f963c70f751342a24e14f2d9f0af6fd` |
| J-1: `mail/channel/DirectUploadController.java` | `7b0b5535ef89244205789a0892b5fec5de97e521d27fc1902dd95e4d5a8d6fd8` (matches J-1 package) |
| J-1: `mail/channel/ExtensionIntakeController.java` | `0f976f14d148d0323500f62a0bafea3881f19a3885edbbb3f33cfb00df1f6806` (matches) |
| J-1: `security/AuthController.java` | `dd90c6bbead0358590c885df0226b5942babd9cf2ae8d287b20caf04e43e921e` (matches) |
| J-1: `test/.../IngestionAuthorizationIntegrationTest.java` | `cfb43e07e05bac0de3ab1a3c1bc0c6266e71f801e1e2cb68099d51517444e678` (matches) |
| `pom.xml` | `ca5f64d72d1a38a06e4a618c49ee232fd804aef15b83a95ba534cfb6d4e3b085` |
| `J1-EXACT-DIFF.patch`, `J1-REVIEW-PACKAGE.md`, `J2A-REVIEW-PACKAGE.md`, `docs/webhook-security.md`, `hireflow-extension/**` | untouched (not in change set) |

### Changed files (4 modified + 3 new = 7; nothing else differs from the snapshot)

| File | Before (SHA-256) | After (SHA-256) |
|---|---|---|
| `src/main/java/com/kartgen/hireflow/document/StoragePort.java` | `f67e0a2efc020411c809f69d1760a55dd47efaf865ba6e85a5a4b4b0962396ff` | `a00071ba90861fa6eb56b12e835359fb7baab2b183e3d39a7e083d357fb12740` |
| `src/main/java/com/kartgen/hireflow/document/LocalDiskStorageAdapter.java` | `c2b5c87ceb5d62acf28d0b72a9e5a71295d03e5c5e50fd8ab0cce1b1ef85f5ff` | `74bbb5ab254d701e243c30b0b95db955427824c5294c3b92c30c66243dc2fa51` |
| `src/main/java/com/kartgen/hireflow/document/S3StorageAdapter.java` | `febefa131105de2818a39daacfe6ea36afd9f5cc89ab962e5e31beae126f4921` | `0fb1ad36b932d8d8fea30ceeb2955153180d203a67cc02e073908a99eab9b53a` |
| `src/main/java/com/kartgen/hireflow/mail/service/MessageIngestionService.java` | `71b145f8fab7de86534705098f2d1693dbc5cb5562918f575727495ac1ed8173` | `4ad872208d5cbf3f1382e9a38108a1c29b2c6d40c852a0570cd83809822c047d` |
| `src/test/java/com/kartgen/hireflow/document/LocalDiskStorageAdapterTest.java` | — (new) | `27d619e12f50d886323851f28749fa8981f0ee0540e6b1a5b677ac6cafdcb489` |
| `src/test/java/com/kartgen/hireflow/storage/J2bFailOnWebhookAcceptTrigger.java` | — (new) | `9aca1ed4b232ebfa0cb870cfa00b70050dfb6649ebd4a2218d10e90739d07519` |
| `src/test/java/com/kartgen/hireflow/storage/TransactionSafeStorageFailureInjectionTest.java` | — (new) | `a5e5daaa48cba0591ed110f72bd606e146865f940f7926f63004b9eafdf5b1bc` |

Change-set derivation: `git diff --no-index --stat`-style comparison of the full `src` tree + `pom.xml` against the pre-J2B snapshot (`src-manifest-before.sha256` vs after-manifest) shows exactly these 7 files; no file was deleted; no other file differs.

## L. Remaining limitations

1. **Crash orphans** (JVM death between store and commit) are not reconciled in-process; an age-based reconciliation job (local directory walk / S3 prefix listing cross-referenced against `resume_files.s3_object_key`) is documented but not implemented. No zero-orphan guarantee is claimed. (PARTIALLY FIXED)
2. **Cleanup failure** can leave a documented orphan (logged ERROR with org + key for reconciliation); the original transaction failure is never masked. (FIXED AND VERIFIED for observability; orphan recovery depends on the reconciliation mechanism above)
3. **S3 adapter** is implemented to the same contract but not executed against a live S3/MinIO endpoint in this environment. (IMPLEMENTED BUT NOT FULLY VERIFIED)
4. **Memory sizing** is not verified; no limits or JVM flags were changed; a 512 MiB heap OOM risk is neither asserted nor denied. (UNVERIFIED)
5. **PostgreSQL 16** is unverified (H2 only). The transaction-synchronization and unique-key logic is dialect-neutral, and `uq_resumefiles_org_sha256` behavior is identical, but no real PostgreSQL run was performed. (UNVERIFIED)
6. **Race-loser transaction outcome**: observed on H2 to roll back at the database layer (sender retries; dedup then reuses the winner's row). The safety invariants (winner object intact, loser object removed, single row) hold regardless; the exact loser behavior on PostgreSQL was not observed.
7. **Bulk upload channels** retain their documented partial-acceptance contract (explicit accepted-ID list); only the webhook channel is atomic. Their storage/rollback correctness is fixed via the shared `getOrStoreResumeFile`.

## M. Final approval recommendation

**APPROVE.** The audit's confirmed defects are corrected with a structurally safe design (unique object keys + transaction-synchronization compensation + explicit race-loser abandonment), webhook delivery is now atomic with an unambiguous 500-on-failure contract, and the failure-injection suite proves DB and storage consistency for every mandated scenario (A–G) plus the documented skip contract (H), on top of a fully green 268-test regression run. The three UNVERIFIED/partial areas (S3 live execution, memory profiling, PostgreSQL 16) and the crash-orphan reconciliation gap are explicitly documented and none of them invalidates the storage-safety correction itself.
