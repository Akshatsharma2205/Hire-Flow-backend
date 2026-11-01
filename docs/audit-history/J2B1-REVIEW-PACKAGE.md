# J-2B.1 Review Package — Final Storage Edge-Case Corrections

Generated: 2026-11-01 · Workspace: `C:\Users\ragha\Desktop\hireflow` (not a git repository; comparisons against a filesystem snapshot)
Pre-J2B.1 snapshot: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b1-snapshot-20261101-135933` (full `src` + `pom.xml` + `src-manifest-before.sha256`)
Pre-J2B.1 Surefire archive (final J-2B evidence): `C:\Users\ragha\AppData\Local\Temp\opencode\j2b1-presurefire-20261101-135933` (27 suites, 268 tests, 0 failures/errors/skipped)
Final Surefire/log archive: `C:\Users\ragha\AppData\Local\Temp\opencode\j2b1-final-surefire-20261101-140814` (27 suites, 273 tests, 0 failures/errors/skipped + full-suite log)

> **For reviewer:** read-only review of a completed, verified implementation. J-3 was not started. The storage system was not redesigned — J-2B's unique-key strategy, `StoragePort.delete()`, transaction-synchronization rollback compensation, and atomic webhook semantics are preserved and re-verified. J-1 source hashes and Flyway V1–V4 are byte-identical. No `flyway clean`/`repair` was run. `J2B-REVIEW-PACKAGE.md` and all J-2B test reports are preserved unchanged; the one J-2B report statement corrected by F-02 is explicitly superseded in Section D, following the J-2A precedent.

**Status labels used in this report:** only
`FIXED AND VERIFIED` · `IMPLEMENTED BUT NOT FULLY VERIFIED` · `PARTIALLY FIXED` · `UNVERIFIED` · `FAILED`.

### Status summary

| Area | Status |
|---|---|
| F-01 — partial local filesystem writes (staging + atomic move + cleanup) | **FIXED AND VERIFIED** |
| F-02 — corrected concurrent-race documentation | **FIXED AND VERIFIED** |
| F-03 — fail-closed transaction synchronization | **FIXED AND VERIFIED** |
| J-2B behaviour preserved (unique keys, delete, rollback compensation, atomic webhook) | **FIXED AND VERIFIED** (all 18 pre-existing focused tests green) |
| Crash orphans (incl. staging artifacts after a process crash) | **PARTIALLY FIXED** — in-process compensation only; reconciliation not implemented |
| S3 live execution, PostgreSQL 16, memory sizing | **UNVERIFIED** (unchanged from J-2B) |

---

## A. Summary of the three corrections

| Finding | Correction |
|---|---|
| **F-01 (Medium)** — a partial local filesystem write left an orphan: the destination file was created, the write failed partway, `store()` threw before returning a key, and rollback compensation was never registered | `LocalDiskStorageAdapter.store()` now writes the full content to a **uniquely named staging file in the same directory**, then **moves** it to the final unique key (atomic move with a non-overwrite fallback). A partial write can never expose a partial object at the final key, and cleanup only ever targets the staging file this attempt created — never a pre-existing object. Three deterministic failure-injection tests prove it. |
| **F-02 (Low)** — source comments claimed the losing unique-key race transaction "commits referencing the winner's row"; observed behaviour is that it becomes rollback-only and rolls back | Source comments in `MessageIngestionService.getOrStoreResumeFile()` (javadoc + race catch) corrected to document the real behaviour. The concurrency algorithm was **not** changed. The corresponding J-2B report statements are superseded in Section D. |
| **F-03 (Low)** — `getOrStoreResumeFile()` was public and, when transaction synchronization was unavailable, stored the object first and only logged a warning | The creation path now **fails closed before `storagePort.store()`** when `TransactionSynchronizationManager.isSynchronizationActive()` is false. No object is ever stored without rollback compensation. Reuse of existing rows (no store) remains possible without a transaction. Two focused tests prove both sides. |

## B. F-01 — Partial-write mechanism and test evidence

### B.1 Mechanism

`store()` now performs, in order:

1. Validate the final key (unchanged path-traversal guard, `startsWith(base)`).
2. `Files.createDirectories(parent)` of the final key.
3. `writeStagedFile(stagingPath, data)` — writes all bytes to a fresh staging file at `{finalKey}.staging-{uuid}` (same directory ⇒ same filesystem ⇒ atomic move possible). `CREATE_NEW`; the channel close flushes the content before any move.
4. `moveIntoPlace(stagingPath, finalPath)` — `Files.move(..., ATOMIC_MOVE)` with a fallback to plain `Files.move` (no `REPLACE_EXISTING` in either path), so a final-key collision fails the store instead of clobbering an object.
5. On any `IOException`: `cleanupStagingFile()` — best-effort `deleteIfExists` of **only the staging file**. If cleanup itself fails, it is logged (ERROR, staging path + original message, no candidate data) and attached via `addSuppressed`; the original exception is never masked and is rethrown wrapped in the adapter's usual `IllegalStateException`.

Safety properties, each with a test:

- **No partial final object is ever exposed** — the final key only ever appears via a completed move (`partialWriteFailureLeavesNoFinalObjectAndCleansStaging`).
- **A pre-existing object is never deleted** — cleanup targets only the staging file of the current attempt; the move never replaces (`partialWriteFailureNeverDeletesPreExistingObjects`).
- **Cleanup failure is logged and does not mask the original exception** — suppressed-exception chain + captured ERROR log (`cleanupFailureIsLoggedAndDoesNotMaskOriginalException`).
- **Original exception preserved** — `store()` throws `IllegalStateException` whose cause is the original `IOException` (asserted in all three tests).
- **Idempotent delete, path traversal, retrieval compatibility, binary byte equality** — unchanged; all five original adapter tests still green.

The two new `protected` methods (`writeStagedFile`, `removeStagingFile`) are documented failure-injection seams; `moveIntoPlace` is protected so a provider without atomic-move support can be exercised. The production default implementations are the real `Files` operations. No mocks and no disk-full tricks are used.

**Crash behaviour:** a process crash between staging-file creation and the move leaves a clearly identifiable `*.staging-*` artifact; no final object is exposed and no row references it. Staging artifacts are reconcilable by the same age-based scan described in Section H. **No crash recovery is claimed.**

### B.2 Test evidence (3 new tests in `LocalDiskStorageAdapterTest`, all green)

| Test | Injection (seam override) | Assertions (all passed) |
|---|---|---|
| `partialWriteFailureLeavesNoFinalObjectAndCleansStaging` | `writeStagedFile` writes half the bytes then throws `IOException("simulated partial write failure")` | `store()` throws `IllegalStateException` with cause = original IOException; the `{org}/{sha}` directory exists but is **empty** (no partial final object); zero `*.staging-*` files anywhere under the base |
| `partialWriteFailureNeverDeletesPreExistingObjects` | same write failure, after a healthy store created a pre-existing object | pre-existing object still exists, byte-identical, `retrieve()` returns exact bytes; zero staging artifacts |
| `cleanupFailureIsLoggedAndDoesNotMaskOriginalException` | `writeStagedFile` throws **and** `removeStagingFile` throws `IOException("simulated cleanup failure")` | original IOException is the cause; cleanup failure is in `cause.getSuppressed()`; captured ERROR log contains `Failed to remove staging file`; the single staging artifact honestly remains (reconciliation gap); no final object exists |

## C. F-03 — Transaction enforcement and all call sites

### C.1 Mechanism

`getOrStoreResumeFile()` (public, invoked from within the same bean) cannot rely on a `@Transactional(propagation = MANDATORY)` annotation — self-invocation bypasses the Spring proxy entirely. Enforcement is therefore an explicit runtime guard at the top of the creation path (`orElseGet` lambda), **before** `storagePort.store()`:

```java
if (!TransactionSynchronizationManager.isSynchronizationActive()) {
    throw new IllegalStateException("Cannot create a new resume file without an active transaction "
            + "(org=" + orgId + ", sha256=" + sha256 + "): refusing to store an object that has no rollback compensation");
}
```

- The dedup lookup (`findByOrganizationIdAndSha256`) runs first and is **not** gated: an existing row is returned without a transaction, so reuse of already-committed files keeps working.
- The guard covers only the physical-creation path, exactly where an uncompensated object could be orphaned.
- `registerRollbackCleanup()`'s own no-sync branch remains as defence-in-depth (now unreachable through any in-repo call path).

### C.2 All call sites verified

| Call site | Enclosing method | Transaction |
|---|---|---|
| `ingestFromExtension` (Chrome extension channel) | `@Transactional` | active ✓ |
| `ingestMailAttachment` (IMAP/OAuth channel) | `@Transactional` | active ✓ |
| `ingestFromWebhook` (forwarding webhook channel) | `@Transactional` (same tx as `WebhookIngestionService.ingestVerifiedDelivery`) | active ✓ |
| `ingestSingleFile` (direct/bulk upload + ZIP) | private; invoked only from `ingestDirectUpload` (`@Transactional`) and `processZip` (called by `ingestDirectUpload`) | active ✓ |

No in-repo caller invokes the creation path outside a transaction; the full 273-test regression (including all 18 focused storage tests) confirms every channel still operates correctly.

### C.3 Test evidence (2 new tests in `TransactionSafeStorageFailureInjectionTest`, all green)

| Test | Setup | Assertions (all passed) |
|---|---|---|
| `creatingNewFileWithoutActiveTransactionFailsClosedBeforeStoring` | direct call to `getOrStoreResumeFile` from the plain test thread (no transaction, no `@Transactional` test) | `IllegalStateException` containing `active transaction`; **`storage.storeAttempts == 0`** (fail-closed happens before `store()`); zero objects; zero `resume_files`/`inbound_messages` rows |
| `existingResumeFileRemainsReusableWithoutActiveTransaction` | committed `ResumeFile` row + physical object pre-inserted; direct no-transaction call with identical content | returns the existing row (same id); **`storeAttempts == 0`**; object untouched, byte-identical; exactly 1 row |

## D. F-02 — Corrected concurrent race explanation

### D.1 Actual behaviour (observed, not assumed)

From the J-2B Test E run (two threads, identical `(org, sha256)`, `CyclicBarrier` forcing both stores before either insert):

1. Both transactions store their own unique objects.
2. The database unique constraint `uq_resumefiles_org_sha256` lets exactly one INSERT win.
3. The loser's `saveAndFlush` fails with `DataIntegrityViolationException`; the fallback explicitly abandons the loser's own unique object (`abandonNewObject`).
4. The failed flush leaves the losing Hibernate session undefined / the transaction rollback-only: the loser **rolls back rather than committing with the winner's entity** (observed log: `Rollback compensation deleted newly stored object ... reason=transaction rolled back` immediately after the fallback delete).
5. The rollback callback re-deletes the same (already deleted) object — idempotent, safe.
6. The sender retries; the next request's `findByOrganizationIdAndSha256` finds the committed winner's `ResumeFile` and reuses it.

Safety invariants (unchanged, re-verified): the winner's object is never touched (the loser never learns the winner's key); the loser deletes only its own unique object; exactly one row and one object survive.

### D.2 What was corrected

- **Source comments** in `MessageIngestionService.getOrStoreResumeFile()` (method javadoc and the `DataIntegrityViolationException` catch comment) now state the rollback-only behaviour and the retry path, while noting that on runtimes where the persistence context survives the failed flush, the fallback re-read still allows the loser to commit against the winner's row. Both statements are accurate to the code's behaviour.
- **J-2B report statements:** `J2B-REVIEW-PACKAGE.md` §C.3 and §L.6 claimed the loser "commits with the winner's row" as the primary path. Per the preservation rule ("preserve existing J-2B tests and reports") the historical report is left byte-identical; those statements are **superseded by this section**. The J-2B report's safety conclusions (winner object intact, loser object removed, single row) were correct and remain so.
- **No concurrency-algorithm change** was made to fit the old comments (explicitly prohibited by the task).

## E. Regression test evidence

| Step | Command | Result |
|---|---|---|
| 1. New partial-write tests | `LocalDiskStorageAdapterTest` (3 new) | 8/8 pass |
| 2. New transaction-enforcement tests | `TransactionSafeStorageFailureInjectionTest` (2 new) | 10/10 pass |
| 3. Existing LocalDiskStorageAdapter tests | same run | 5/5 pass |
| 4. All eight J-2B failure-injection tests | same run | 8/8 pass |
| 5. Webhook delivery/replay/request-contract/embedded-container | `WebhookAuth(23)`, `WebhookDeliveryIntegrity(5)`, `WebhookReplayDurability(2)`, `WebhookRequestContract(12)`, `WebhookEmbeddedContainer(3)`, `WebhookSizeLimit(11)`, `WebhookContentType(15)`, `WebhookIntakeConcurrency(1)`, `IntakePipeline(2)` | all pass |
| 6. J-1 authorization regression | `IngestionAuthorizationIntegrationTest(14)`, `UserMembershipRoleAuthorizationTest(15)`, `AuthTenantIntegrationTest(7)`, `JwtRevocationAndTenantStatusEnforcementTest(10)`, `RoleValidationAndHardeningIntegrationTest(19)` | all pass |
| 7. Full suite | `./mvnw.cmd test` (JAVA_HOME=`.jdk/jdk-21`) | **27 suites, 273 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS, exit 0** |

Full-suite run: started 14:05 (approx.), completed 14:08:00 (~3 min incl. recompiles). Pre-run J-2B evidence preserved at `j2b1-presurefire-20261101-135933`; final reports + `j2b1-full-suite-<ts>.log` archived at `j2b1-final-surefire-20261101-140814`. PostgresSQL 16 and live S3 were not executed; nothing here claims either is verified.

## F. Preserved J-1 and V1–V4 hashes

| File | SHA-256 (unchanged) |
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

## G. Changed-file list and hashes

Exactly 4 files differ from the pre-J2B.1 snapshot; nothing was added or deleted. (`J2B-REVIEW-PACKAGE.md` is preserved byte-identical per the preservation rule.)

| File | Before (J-2B) | After (J-2B.1) | Change |
|---|---|---|---|
| `src/main/java/com/kartgen/hireflow/document/LocalDiskStorageAdapter.java` | `74bbb5ab254d701e243c30b0b95db955427824c5294c3b92c30c66243dc2fa51` | `69db4eee66526827cc9954fdc48a92039fe57c5e32bf7a18d1c1b273e6ef4e5c` | F-01: staging-file write + atomic move + cleanup (3 protected seams) |
| `src/main/java/com/kartgen/hireflow/mail/service/MessageIngestionService.java` | `4ad872208d5cbf3f1382e9a38108a1c29b2c6d40c852a0570cd83809822c047d` | `4447452f2c0bdbee81cb425f5502fa45942b7eed60920de0c842724d67657e4f` | F-03: fail-closed guard before store; F-02: corrected race comments |
| `src/test/java/com/kartgen/hireflow/document/LocalDiskStorageAdapterTest.java` | `27d619e12f50d886323851f28749fa8981f0ee0540e6b1a5b677ac6cafdcb489` | `df3a48b3729ca48ba536a454cddc44c6213feddb1be06f1d6423f413a6f8394b` | +3 F-01 failure-injection tests, seam subclasses |
| `src/test/java/com/kartgen/hireflow/storage/TransactionSafeStorageFailureInjectionTest.java` | `a5e5daaa48cba0591ed110f72bd606e146865f940f7926f63004b9eafdf5b1bc` | `2e70c4ed4cb677077b9a455496ac9e70152f9b3a7fe59801aa4062231a850820` | +2 F-03 fail-closed tests |

## H. Remaining limitations (unchanged from J-2B unless noted)

1. **Crash orphans — PARTIALLY FIXED.** A process crash between store and commit still leaves an unreferenced final-key object, and (new, F-01) a crash between staging-file creation and move leaves a `*.staging-*` artifact. Both are identifiable and reconcilable by an age-based scan (`resume_files.s3_object_key` cross-reference for final keys; `*.staging-*` marker removal for staging artifacts), but no such job is implemented and **no crash recovery is claimed**.
2. **S3 adapter — IMPLEMENTED BUT NOT FULLY VERIFIED.** F-01 applies to the local filesystem adapter; S3 `putObject` with a unique key does not expose partial objects at the service level (single PUT, key absent until complete). No live S3/MinIO endpoint was executed; code-level consistency only.
3. **PostgreSQL 16 — UNVERIFIED.** H2 in PostgreSQL compatibility mode only; no real isolated PostgreSQL 16 instance executed.
4. **Memory sizing — UNVERIFIED.** No limits changed, no JVM flags added, no safe-heap claim; the J-2B static analysis and deferral rationale stand.
5. **Cleanup-failure orphans** (rollback-compensation delete failure or staging-cleanup failure) remain logged-ERROR reconciliation items; the original transaction/exception is never masked (re-verified for both the transaction layer and the adapter layer in J-2B.1).
6. **Bulk upload channels** retain their documented partial-acceptance contract (explicit accepted-ID list); storage/rollback correctness for those channels is unchanged and covered by the shared `getOrStoreResumeFile` fail-closed path.

## I. Final status labels

| Finding | Status |
|---|---|
| F-01 — partial local filesystem writes | **FIXED AND VERIFIED** |
| F-02 — concurrent-race documentation | **FIXED AND VERIFIED** |
| F-03 — fail-closed transaction synchronization | **FIXED AND VERIFIED** |
| J-2B behaviour preservation | **FIXED AND VERIFIED** (full 273-test suite green) |
| Crash reconciliation (final-key + staging orphans) | **PARTIALLY FIXED** (documented, not implemented) |
| S3 live execution / PostgreSQL 16 / memory sizing | **UNVERIFIED** |

**Final recommendation: APPROVE.** All three audit findings are corrected with minimal, targeted changes; the storage system was not redesigned; J-1/J-2/J-2A/J-2B behaviour and evidence are preserved; and the full regression suite (273 tests, 27 suites, 0 failures/errors/skipped) is green, including the eight J-2B failure-injection tests, five adapter tests, three new partial-write tests, and two new transaction-enforcement tests.
