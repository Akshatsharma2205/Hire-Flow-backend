# J-2B.2 Review Package — Final Storage Safety Corrections

Generated: 2026-11-01 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repository; comparisons against a filesystem snapshot)
Pre-J2B.2 snapshot: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b2-snapshot-20261101-170938` (full `src` + `pom.xml` + `src-manifest-before.sha256`)
Pre-J2B.2 Surefire archive (final J-2B.1 evidence): `C:\Users\ragha\AppData\Local\Temp\opencode\j2b2-presurefire-20261101-170938` (27 suites, 273 tests, 0 failures/errors/skipped)
Final Surefire/log archive: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b2-final-surefire-20261101-172433` (27 suites, 278 tests, 0 failures/errors/skipped + full-suite log)

> **For reviewer:** read-only review of a completed, verified implementation. J-3 was not started. J-1 authorization files/tests and Flyway V1–V4 are byte-identical. All J-2, J-2A, J-2B and J-2B.1 reports and test archives are preserved unchanged; the J-2B.1 race-documentation nuance corrected by L-03 is explicitly superseded in Section F. No `flyway clean`/`repair` was run. HMAC, replay protection, idempotency and attachment limits are untouched.

**Status labels used in this report:** only
`FIXED AND VERIFIED` · `IMPLEMENTED BUT NOT FULLY VERIFIED` · `PARTIALLY FIXED` · `UNVERIFIED` · `FAILED`.

### Status summary

| Area | Status |
|---|---|
| M-01 — documented no-overwrite finalization (exclusive hard-link + no-option move fallback) | **FIXED AND VERIFIED** |
| L-01 — actual-transaction requirement in the creation guard | **FIXED AND VERIFIED** |
| L-02 — staging cleanup for runtime exceptions (suppressed, never masking) | **FIXED AND VERIFIED** |
| L-03 — race documentation corrected (no residual "loser commits with winner row" claims) | **FIXED AND VERIFIED** |
| J-2B / J-2B.1 behaviour preservation (unique keys, delete, rollback compensation, atomic webhook, fail-closed) | **FIXED AND VERIFIED** (all 18 pre-existing focused tests green) |
| Crash orphans (final-key + staging artifacts) | **PARTIALLY FIXED** — in-process compensation only; reconciliation not implemented |
| S3 live execution, PostgreSQL 16, memory profiling | **UNVERIFIED** (unchanged) |

---

## A. Executive summary

The J-2B.1 independent audit found three technical edge cases and one documentation issue. All were verified against the current source and corrected with minimal, targeted changes:

1. **M-01 (no-overwrite semantics):** the J-2B.1 finalization used `Files.move(staging, final, ATOMIC_MOVE)`, and its "never overwrites" documentation was not justified — per the Java NIO contract, when `ATOMIC_MOVE` is supplied "all other options are ignored" and "if the target file exists then it is implementation specific if the existing file is replaced or this method fails". On POSIX providers atomic move maps to `rename(2)`, which silently replaces the destination; NTFS providers behave similarly. Corrected to an **atomic exclusive hard-link publication** (`Files.createLink`, single `link(2)`/`CreateHardLink` syscall — link creation has no replace semantics anywhere) with a **no-option `Files.move` fallback** whose API contract is explicit: "by default, if the target file exists then it fails with FileAlreadyExistsException". No application-level `exists()` check anywhere — no TOCTOU in this code. A deterministic collision test (fixed-key seam, no UUID lottery) proves the existing object stays byte-identical and the operation fails safely.
2. **L-01 (actual transaction):** the creation guard now requires **both** `isActualTransactionActive()` **and** `isSynchronizationActive()` before `storagePort.store()`. A new test proves rejection when synchronization is active without an actual transaction, before any physical store attempt.
3. **L-02 (runtime-exception cleanup):** `store()` now routes ordinary runtime failures (e.g. `SecurityException`) through the same best-effort staging cleanup as `IOException`, rethrowing the original runtime exception un-wrapped; cleanup failures (I/O or runtime) are logged and attached as suppressed exceptions. `Error` conditions are never caught. Three deterministic tests cover runtime failure after staging creation, runtime failure during cleanup, and a pre-existing final object staying byte-identical under a runtime finalization failure.
4. **L-03 (documentation):** every remaining claim that the failed-flush transaction "can commit with the winner's row" was removed from the source comments and Javadoc; the verified primary behaviour (unique keys → DB constraint arbitrates → winner commits → loser's flush fails → loser deletes only its own object → transaction becomes rollback-only and rolls back → sender retries → retry reuses the winner's row) is now stated without qualification. The concurrency algorithm was not changed. Historical reports are preserved; the J-2B.1 §D.2 hedge is superseded in Section F.

Verification: **278 tests, 27 suites, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS, exit 0** (was 273; +5 new tests). **Recommendation: APPROVE.**

## B. M-01 — No-overwrite filesystem solution and exact semantics

### B.1 Why the previous approach was unjustified

The Java NIO contract for `Files.move(source, target, options)`:

> **By default, if the target file exists then it fails with FileAlreadyExistsException**, except if the source and target are the same file...
> **ATOMIC_MOVE** — The move is performed as an atomic file system operation and **all other options are ignored**. If the target file exists then it is **implementation specific** if the existing file is replaced or this method fails by throwing an IOException.

J-2B.1 used `ATOMIC_MOVE` and documented "never overwrites". That documentation was wrong for the common providers: on POSIX the atomic move is `rename(2)`, which silently replaces the destination; on NTFS the Windows provider performs the equivalent replace-capable rename. A UUID collision or any pre-existing file at the final key would have been silently replaced — violating the unique-key ownership invariant.

### B.2 Chosen strategy

**Primary — atomic exclusive hard link (`Files.createLink(finalPath, stagingPath)`).** Hard-link creation is a single `link(2)` (POSIX) / `CreateHardLink` (NTFS) syscall with **no replace semantics on any provider**: if the final directory entry already exists the call fails (`FileAlreadyExistsException`), and the existing object is untouched. Publication of the final name is therefore atomic (the complete content is linked in one step — the final name can never be observed partially) AND exclusive (a collision can never overwrite). The staging name is then deleted; if that deletion fails, the final object is complete and published and the leftover staging name is logged as a reconcilable artifact — never a failure of the store, never a partial object.

**Fallback — no-option move (`Files.move(stagingPath, finalPath)`).** On filesystems without hard-link support (some network/FAT mounts), `createLink` throws `UnsupportedOperationException` or `FileSystemException`; the code falls back to a plain move **with no options**, whose documented API contract is the explicit default: target exists → `FileAlreadyExistsException`. No application-level `exists()`-then-act sequence exists anywhere, so there is no TOCTOU race in this code.

### B.3 Documented platform assumptions and trade-off

| Property | Primary (exclusive link) | Fallback (no-option move) |
|---|---|---|
| No-overwrite guarantee | Absolute: `link(2)`/`CreateHardLink` never replaces | API-contract: `FileAlreadyExistsException` when target exists (JDK default semantics) |
| Atomic publication | Yes — single syscall | Yes — same-directory rename is atomic on both providers |
| No-overwrite check and publication as one syscall | Yes (single `link`) | **No** — on POSIX providers the JDK implements the no-replace decision as an existence pre-check before `rename(2)`; the two are not a single syscall (documented trade-off; keys embed a random UUID so same-key collisions are unreachable in practice, and the fallback still fails safely) |
| Hard-link support required | Yes (NTFS, ext4, APFS, XFS, …) | No |

The fallback is engaged only when the provider reports it cannot link; the primary path (this environment's NTFS) has the strongest guarantee. Both paths preserve same-directory staging, binary byte equality, and the unique-key format.

## C. Deterministic final-destination collision test

`LocalDiskStorageAdapterTest.existingFinalObjectCollisionFailsSafelyAndNeverOverwrites` — a new `buildObjectKey` seam (protected, default = random UUID key) lets the test force the final key deterministically instead of relying on UUID randomness:

1. Pre-create the final object at the exact key the store will target, with known bytes.
2. Call `store()` with different content.
3. Assertions (all passed): `store()` throws `IllegalStateException` whose cause is an `IOException` (the provider-level no-overwrite contract rejected publication); the existing final object's bytes are **unchanged** (`isEqualTo(existingBytes)` and `isNotEqualTo(newBytes)` — the new object was never published over it); **zero** `*.staging-*` artifacts remain (staging cleaned up); the target directory contains **exactly** the pre-existing final object — no unrelated object was created or deleted.

The `buildObjectKey` seam also underpins the L-02 final-object test below; the default implementation is unchanged (random UUID).

## D. L-01 — Actual transaction enforcement and tests

### D.1 Guard behaviour

`getOrStoreResumeFile()`'s creation path (inside `orElseGet`, **before** `storagePort.store()`) now requires:

```java
TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()
```

- **Both** conditions must hold: Spring can report active synchronization without an actual database transaction (synchronizations can be registered directly by non-transactional callers), and a rollback callback registered in that state can never fire — an uncompensable object would be created. The check is an explicit runtime guard because all in-repo callers are self-invocations within the bean (a `@Transactional` proxy annotation would be bypassed).
- The exception is the same `IllegalStateException` as before ("Cannot create a new resume file without an active transaction (org=…, sha256=…): refusing to store an object that has no rollback compensation").
- The dedup lookup runs first and is un-gated: **existing committed ResumeFile reuse continues to work without a transaction** (no store, no compensation needed).
- Rollback-cleanup registration after a successful new storage write is unchanged (still registered only after `store()` returns, only for this transaction's own unique object, only when the transaction is real — which the guard now guarantees).
- All four transactional call sites (`ingestFromExtension`, `ingestMailAttachment`, `ingestFromWebhook`, `ingestSingleFile` via `ingestDirectUpload`) verified unaffected by the full-suite regression.

### D.2 Tests

| Test | Scenario | Assertions (all passed) |
|---|---|---|
| `synchronizationWithoutActualTransactionRejectsNewStorageBeforeStore` (new, L-01) | org pre-provisioned; a short-lived worker thread activates synchronization via `TransactionSynchronizationManager.initSynchronization()` **without any database transaction**, then calls `getOrStoreResumeFile` for new content | `IllegalStateException` containing "active transaction"; **`storage.storeAttempts == 0`** (rejected before any physical store); zero objects; zero `resume_files`/`inbound_messages` rows. The worker thread confines the shared-EntityManager proxy's per-thread caching oddity (see note) so the main thread's persistence context stays healthy for the assertions |
| `creatingNewFileWithoutActiveTransactionFailsClosedBeforeStoring` (retained, J-2B.1 F-03) | no transaction, no synchronization at all | `IllegalStateException`; `storeAttempts == 0`; zero rows |
| `existingResumeFileRemainsReusableWithoutActiveTransaction` (retained, J-2B.1 F-03) | committed row + object; no transaction | returns the existing row (same id); `storeAttempts == 0`; object untouched |

Test note: running the phantom-synchronization scenario on the JPA repository path on the main test thread poisons the shared `EntityManager` proxy's ThreadLocal cache for subsequent tests (observed as "Session/EntityManager is closed" across the suite). Isolating the scenario on a short-lived worker thread confines the effect to that thread, which dies with it; the main thread's persistence context remains healthy, which the passing assertions on real repository queries demonstrate.

## E. L-02 — Runtime-exception cleanup and tests

### E.1 Behaviour

`LocalDiskStorageAdapter.store()` now has a second catch arm:

```java
} catch (IOException e) {
    cleanupStagingFile(stagingPath, e);
    throw new IllegalStateException("Failed to store file locally: " + finalPath, e);
} catch (RuntimeException e) {
    cleanupStagingFile(stagingPath, e);   // best-effort, same as IOException path
    throw e;                              // original runtime exception stays primary, un-wrapped
}
```

`cleanupStagingFile` now accepts `Exception` and its inner catch is `catch (Exception cleanup)` — covering both `IOException` and ordinary runtime failures (e.g. `SecurityException` from a denied delete) — logging the staging path + original message (no candidate data/secrets) and attaching the cleanup failure via `addSuppressed`. **`Error` conditions are never caught** (no `catch (Throwable)`). The original exception is always the primary failure; a cleanup failure never masks it.

Additionally, the post-link staging-name removal in `moveIntoPlace` logs a WARN (never fails the store) if it cannot be removed — the final object is already complete and published at that point.

### E.2 Tests (3 new, all green — `LocalDiskStorageAdapterTest`)

| Test | Injection (seam) | Assertions (all passed) |
|---|---|---|
| `runtimeFailureAfterStagingCreationCleansUpAndStaysPrimary` | `writeStagedFile` creates the staging file then throws `SecurityException("simulated runtime failure after staging creation")` | `store()` throws the **same** `SecurityException` (primary, **not** wrapped in `IllegalStateException`); zero `*.staging-*` artifacts (cleanup ran despite the runtime failure); the `{org}/{sha}` directory is empty — no final object exposed |
| `runtimeCleanupFailureIsSuppressedAndOriginalStaysPrimary` | `writeStagedFile` throws `IOException`; `removeStagingFile` throws `SecurityException("simulated runtime cleanup failure")` | original `IOException` is the cause of the `IllegalStateException`; the runtime cleanup failure is in `cause.getSuppressed()`; captured ERROR log contains `Failed to remove staging file`; honest: the staging artifact remains (cleanup failed) and no final object exists |
| `existingFinalObjectStaysByteIdenticalOnRuntimeFinalizationFailure` | fixed key (seam) + pre-created final object + `moveIntoPlace` throws `SecurityException("simulated runtime failure during finalization")` | `SecurityException` primary; the pre-existing final object is **byte-identical**; the new object was never published; zero staging artifacts; directory contains exactly the pre-existing object |

## F. L-03 — Corrected race documentation

### F.1 Verified primary behaviour (unchanged algorithm, corrected text)

1. Two requests for identical `(organizationId, sha256)` content each store their own distinct UUID-based physical keys.
2. The database unique constraint `uq_resumefiles_org_sha256` arbitrates the `resume_files` row.
3. The winning transaction commits.
4. The losing transaction encounters the uniqueness error during flush.
5. The loser deletes **only its own unique object** (explicit abandonment; the rollback callback re-deletes the same object idempotently).
6. Its transaction becomes rollback-only and **rolls back** — it does not commit with the winner's entity.
7. The sender retries.
8. The retry's `findByOrganizationIdAndSha256` finds the committed winner's `ResumeFile` and reuses it.

### F.2 What changed

- `MessageIngestionService.getOrStoreResumeFile()` Javadoc and the `DataIntegrityViolationException` catch comment: **removed every remaining claim that the failed-flush transaction can commit with the winner's row** (including the J-2B.1 "where the persistence context survives… can still commit against it" hedge). The fallback re-read is now described accurately as defensive — it may return the winner's row, but the transaction is already rollback-only and will not commit it.
- The concurrency algorithm was **not** changed to match old documentation.
- Historical reports are preserved byte-identical (J-2B §C.3/§L.6 and J-2B.1 §D.2 statements that implied the loser could commit with the winner's row are **superseded by this section**, per the J-2A/J-2B.1 precedent — the audit trail is never silently rewritten).

## G. Regression test results and raw evidence paths

| Step | Command (JAVA_HOME=`.jdk/jdk-21`) | Result |
|---|---|---|
| 1. New filesystem collision tests | `LocalDiskStorageAdapterTest` (M-01: 1 new) | 12/12 pass |
| 2. New runtime-exception cleanup tests | `LocalDiskStorageAdapterTest` (L-02: 3 new) | 12/12 pass |
| 3. New transaction-guard tests | `TransactionSafeStorageFailureInjectionTest` (L-01: 1 new) | 11/11 pass |
| 4. Existing local filesystem adapter tests | same focused run | 5 J-2B + 3 J-2B.1 adapter tests pass |
| 5. All eight J-2B storage failure-injection tests | same focused run | 8/8 pass |
| 6. Webhook replay/delivery/embedded-container | `WebhookReplayDurability(2)`, `WebhookDeliveryIntegrity(5)`, `WebhookEmbeddedContainer(3)`, `WebhookAuth(23)`, `WebhookRequestContract(12)`, `WebhookSizeLimit(11)`, `WebhookContentType(15)`, `WebhookIntakeConcurrency(1)`, `IntakePipeline(2)` | all pass |
| 7. J-1 authorization regression | `IngestionAuthorizationIntegrationTest(14)`, `UserMembershipRoleAuthorizationTest(15)`, `AuthTenantIntegrationTest(7)`, `JwtRevocationAndTenantStatusEnforcementTest(10)`, `RoleValidationAndHardeningIntegrationTest(19)` | all pass |
| 8. Full suite | `./mvnw.cmd test` | **27 suites, 278 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS, exit 0** |

Full-suite run: started ~17:10, completed 17:24:22. Pre-run J-2B.1 evidence preserved at `j2b2-presurefire-20261101-170938` (27/273/0/0/0); final reports + `j2b2-full-suite-<ts>.log` archived at `j2b2-final-surefire-20261101-172433`. S3 live execution and PostgreSQL 16 were **not** executed; nothing here claims either is verified.

## H. Before/after file hashes and changed-file list

Exactly 4 files changed; nothing added, nothing deleted, `pom.xml` unchanged.

| File | Before (J-2B.1) | After (J-2B.2) | Change |
|---|---|---|---|
| `src/main/java/com/kartgen/hireflow/document/LocalDiskStorageAdapter.java` | `69db4eee66526827cc9954fdc48a92039fe57c5e32bf7a18d1c1b273e6ef4e5c` | `f4c146a970b2b3d2e1b32027eb48dfdfc6a150fb1f73580fc4ea0b1c4361ccef` | M-01: exclusive-link publication + no-option-move fallback + `buildObjectKey` seam; L-02: runtime-exception catch arm + `Exception` cleanup |
| `src/main/java/com/kartgen/hireflow/mail/service/MessageIngestionService.java` | `4447452f2c0bdbee81cb425f5502fa45942b7eed60920de0c842724d67657e4f` | `7d23cba1781e7ed4aeba133be9490860ec6d7c1d2bed799896597b51bcff3e2a` | L-01: `isActualTransactionActive()` added to the guard; L-03: race comments corrected |
| `src/test/java/com/kartgen/hireflow/document/LocalDiskStorageAdapterTest.java` | `df3a48b3729ca48ba536a454cddc44c6213feddb1be06f1d6423f413a6f8394b` | `6740c6723e16a421723f77df66e92ee0099c278510b725b1b9fdf63c3e9db900` | +4 tests (M-01 collision, 3× L-02), +4 seam subclasses |
| `src/test/java/com/kartgen/hireflow/storage/TransactionSafeStorageFailureInjectionTest.java` | `2e70c4ed4cb677077b9a455496ac9e70152f9b3a7fe59801aa4062231a850820` | `960ce01f152da3baf1ffc5bab31817d18d077bac10a0cfa4c39de37c04882b28` | +1 L-01 test (worker-thread isolated phantom-sync scenario) |

## I. Preservation evidence for J-1 and V1–V4

| File | SHA-256 (unchanged, byte-identical) |
|---|---|
| `src/main/resources/db/migration/V1__init_schema.sql` | `d4c819b790f106941dbabaaf82414d4f632b886f79a0ad7e462468b5394f49db` |
| `src/main/resources/db/migration/V2__seed_data.sql` | `a58c334d157005da0ef3b01ceba46e38a487d8d6c88bbbb0b2533f36ee339844` |
| `src/main/resources/db/migration/V3__performance_indices.sql` | `5745e9002233af2fd74ba247f98b33e00292da42bf169c215ce436a8844760c2` |
| `src/main/resources/db/migration/V4__webhook_security_and_idempotency.sql` | `7fb5bcd02b168c111d873d1e5adf492a3f963c70f751342a24e14f2d9f0af6fd` |
| J-1: `mail/channel/DirectUploadController.java` | `7b0b5535ef89244205789a0892b5fec5de97e521d27fc1902dd95e4d5a8d6fd8` |
| J-1: `mail/channel/ExtensionIntakeController.java` | `0f976f14d148d0323500f62a0bafea3881f19a3885edbbb3f33cfb00df1f6806` |
| J-1: `security/AuthController.java` | `dd90c6bbead0358590c885df0226b5942babd9cf2ae8d287b20caf04e43e921e` |
| J-1: `test/.../IngestionAuthorizationIntegrationTest.java` | `cfb43e07e05bac0de3ab1a3c1bc0c6266e71f801e1e2cb68099d51517444e678` |
| `pom.xml` | `ca5f64d72d1a38a06e4a618c49ee232fd804aef15b83a95ba534cfb6d4e3b085` |

IMAP/OAuth, extension ingestion, frontend, matching, worker leases, candidate processing, HMAC, replay protection, idempotency and attachment limits were not modified. No Flyway clean/repair was run.

## J. Remaining limitations

1. **Crash orphan reconciliation — PARTIALLY FIXED.** A process crash between store and commit still leaves an unreferenced final-key object; a crash between staging creation and publication leaves a `*.staging-*` artifact (and a crash between link and staging removal leaves a staging name hard-linked to a published object). All are identifiable and reconcilable by an age-based scan (`resume_files.s3_object_key` cross-reference; `*.staging-*` marker removal), but no reconciliation job is implemented and **no crash recovery is claimed**.
2. **S3 live execution — UNVERIFIED.** The S3 adapter is unchanged from J-2B (unique-key `putObject`, no partial-object exposure at the service level); no live S3/MinIO endpoint was executed in this environment.
3. **PostgreSQL 16 — UNVERIFIED.** H2 in PostgreSQL compatibility mode only; no isolated PostgreSQL 16 instance executed.
4. **Memory profiling — UNVERIFIED.** No limits changed, no JVM flags added, no safe-heap claim; the J-2B static analysis and deferral rationale stand.
5. **Filesystem provider limitations (M-01 fallback).** On providers without hard-link support the no-option-move fallback relies on the JDK's documented default contract; on POSIX the provider implements the no-replace decision as an existence pre-check before `rename(2)` (not a single syscall). Keys embed a random UUID so same-key collisions are unreachable in practice, and the fallback still fails safely. The primary path on NTFS/ext4/APFS/XFS has single-syscall atomic exclusive publication.
6. **Cleanup-failure orphans** (staging-cleanup or rollback-compensation delete failure) remain logged-ERROR reconciliation items; the original failure is never masked (re-verified for I/O and runtime exceptions in J-2B.2).

## K. Final status table

| Finding | Status |
|---|---|
| M-01 — no-overwrite filesystem semantics | **FIXED AND VERIFIED** |
| L-01 — actual transaction enforcement | **FIXED AND VERIFIED** |
| L-02 — runtime-exception staging cleanup | **FIXED AND VERIFIED** |
| L-03 — race documentation | **FIXED AND VERIFIED** |
| J-2B / J-2B.1 behaviour preservation | **FIXED AND VERIFIED** (full 278-test suite green) |
| Crash orphan reconciliation | **PARTIALLY FIXED** (documented, not implemented) |
| S3 live execution / PostgreSQL 16 / memory profiling | **UNVERIFIED** |

## L. Approval recommendation

**APPROVE.** All three technical findings and the documentation issue are corrected with minimal, targeted changes and deterministic test evidence: the no-overwrite guarantee is now structurally sound (exclusive hard-link publication with a documented no-option-move fallback, no TOCTOU in this code), the creation guard requires an actual transaction, runtime failures trigger the same best-effort cleanup with the original exception always primary, and the race documentation states only the verified behaviour. J-1/J-2/J-2A/J-2B/J-2B.1 behaviour and evidence are preserved, and the full regression suite (278 tests, 27 suites, 0 failures/errors/skipped) is green.
