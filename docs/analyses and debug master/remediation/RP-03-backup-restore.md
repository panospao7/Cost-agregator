# RP-03 - Backup/restore integrity (Pipeline 7)

> **Scope:** Segment 18 (Export & Backup), with startup/runtime, receipt lifecycle, privacy, and shared encryption crossover. **Mode:** strict.
>
> **Source baseline:** `DatabaseBackupRepositoryImpl`, `data/backup/*`, `ReceiptAssetStore`, `AppStartupCoordinator`, and the four audit reports reviewed on 2026-09-08. The current code has journaled staging/swap/recovery, worker drain, encrypted `.costbackup` bundles, checksum/extraction limits, fresh-DB verification, and Tier 1 exact table counts. The remaining implementation work below must not be inferred from the stale 2026-05-31 tracker.

## 1. Non-negotiable contracts

1. Production `.costbackup` backup/restore remains the only supported user backup path. Preserve the encryption envelope and `BackupEncryptionService`; do not add plaintext receipt or database output. If the envelope is changed, retain legacy decrypt compatibility and add golden vectors before release.
2. Every backup/restore/reset operation enters through `MaintenanceOperationRunner.enterAndDrain`, persists `RestoreMaintenanceMode`, and exits in `finally`. `BACKUP_EXPORTING` blocks writes while the snapshot is made. Restore modes block reads/writes according to `DatabaseReadBarrier` and `DatabaseWriteBarrier`; no caller may bypass them.
3. The live database is never modified before pre-swap staging, manifest validation, Room migration/open, integrity/FK checks, and safety-backup creation succeed. After file swap, use a fresh `RestoreDatabaseOpener` instance. Do not resume ordinary app writes against a stale injected `AppDatabase`; retain forced restart unless a separately approved reopenable database-provider design lands.
4. After the swap, Room operation-run writes are forbidden by the existing F6 rule. Restore journal/event files are the authoritative post-swap progress channel. Receipt-row repair may use only the existing `RestoreInternalWriteScope` in `ASSETS_RESTORING`.
5. Diagnostics and journals contain controlled stage/reason/error codes, receipt IDs, asset kind, byte counts, and booleans only. Never persist/log raw filenames, source paths, URI strings, exception messages, stack traces, receipt/OCR/notification text, or financial payloads. `CancellationException` always propagates.

## 2. Work packages and order

### RP-03A - Save path, snapshot, and envelope

- Keep `BACKUP_EXPORTING` entry/drain, WAL checkpoint, `SqliteSnapshotCreator`, strict required-table counts, and destination verification ordering.
- Add the SAF destination path to `CostbackupBundle`/repository without opening the destination until bundle encryption and verification have succeeded. `contentResolver.openOutputStream(uri)` is nullable and may throw; handle null/open/write/close failures as a failed export.
- On a SAF failure, close the stream exactly once, best-effort delete the provider document with `DocumentsContract.deleteDocument`, and return the existing generic error. Do not damage the restore journal or claim backup success. The file destination overload remains a test/legacy delegate only; remove public-Documents fallback and permissions.
- Preserve the current encrypted envelope. If the versioned KDF envelope in the approved encryption change is not already present in the branch, land it separately with legacy fallback, wrong-password mapping, truncation handling, and vectors. Do not mix an untested encryption-format rewrite into asset recovery.

### RP-03B - Restore state machine and database recovery

- Keep the journal sequence `PREPARING -> STAGED -> SAFETY_BACKUP_CREATED -> SWAPPING -> VERIFYING -> ASSETS_RESTORING -> COMPLETE`, with `ROLLING_BACK`/`FAILED` recovery paths.
- Fix outer post-swap failure handling: track `swapped`; post-swap failure/cancellation must leave writes blocked and require restart. Pre-swap failure may exit normally only after staging cleanup and a terminal failed journal.
- Cancellation in the asset loop must run a `withContext(NonCancellable)` journal-only checkpoint/finalization, preserve resumable `ASSETS_RESTORING`, clean only temporary asset outputs, exit with `forceRestartRequired = true`, then rethrow. Never call Room operation-run finalization after swap.
- Startup handling of `RestoreJournal.RecoveryResult.AssetsIncomplete` must execute the asset recovery algorithm below, not unconditionally lock forever. If the swapped DB fails fresh verification, use the existing safety-backup rollback/critical-recovery path. If only receipt assets are missing, keep the verified DB and mark those asset tasks `FAILED`; do not roll back a valid DB for best-effort image loss.
- `resetDatabase` must journal `SAFETY_BACKUP_CREATED` before destructive deletion and `SWAPPING` before deleting live files; its existing startup safety recovery remains mandatory.
- Keep `.pre_restore` recovery ordering: safety backup, verified `.pre_restore`, then `CRITICAL_RECOVERY_REQUIRED`; clean it after successful consumption, successful commit, swap failure, and rollback.

### RP-03C - Receipt asset integrity and privacy

Implement one idempotent per-asset state machine. The journal must be written durably (temp file, flush/fsync, atomic rename) before each externally observable step. Extend the journal task record as an additive file-format change:

```text
AssetTask {
  assetId: stable deterministic ID,
  receiptId: database receipt ID,
  sourceEntry: validated bundle-relative entry (no user filename),
  expectedSha256: manifest checksum,
  expectedSizeBytes: manifest size,
  tempName: operation-scoped generated name,
  finalName: operation-scoped generated name,
  status: PENDING | TEMP_WRITTEN | FINAL_DURABLE | DB_UPDATED | COMPLETED | FAILED,
  failureCode: controlled code or null
}
```

#### Stable identity

- During backup, derive `assetId = SHA-256(canonical asset key)` where the canonical key is the stable receipt ID plus a fixed asset-kind token, for example `receipt:<receiptId>:image`. Encode as lowercase hex. Do not use UUIDs, original filenames, absolute paths, timestamps, or `String.hashCode()`.
- The manifest entry must carry the asset ID, relative ZIP entry, SHA-256, and exact byte size. The ZIP entry is accepted only under the existing extraction limits and only after path normalization/zip-slip checks.
- On restore, derive the destination directory from `context.filesDir`, and derive `finalName` from the asset ID plus a validated extension selected from a fixed allowlist. Never trust `targetPath` from an old journal; derive final paths again. Reject duplicate asset IDs, duplicate receipt/kind keys, missing checksum/size, invalid IDs, or mismatched manifest-to-DB receipt references before any DB path update.

#### Required ordering and crash recovery

For each task, process and persist these transitions in order:

1. `PENDING`: journal the complete task ledger before copying any asset.
2. `TEMP_WRITTEN`: stream the bundle entry to `tempName`, count bytes, compute SHA-256, flush/fsync, and require exact size/hash. On mismatch, delete temp and mark `FAILED(INTEGRITY_MISMATCH)`; never touch the DB.
3. `FINAL_DURABLE`: atomically rename temp to `finalName` (or use an exclusive durable replacement primitive), fsync the containing directory where supported, verify final exists/is-file and re-check size/hash. If rename/write fails, delete only this task's temp/final candidates and mark failed; leave the old DB path unchanged.
4. `DB_UPDATED`: only after final validation, update the matching receipt row from its pre-restore snapshot to the deterministic final path inside `RestoreInternalWriteScope`; journal the transition after the write returns. The update must be conditional on the receipt ID and must not overwrite an unrelated newer path.
5. `COMPLETED`: verify the row points to `finalName`, journal completion, and only then allow cleanup of operation temp state.

Recovery must be deterministic and idempotent:

| Crash/relaunch observation | Recovery action |
|---|---|
| `PENDING`, no temp/final | Re-run from bundle entry. |
| `TEMP_WRITTEN`, valid temp only | Validate it, rename to final, continue; invalid/missing temp is re-copied if source exists, otherwise `FAILED(SOURCE_MISSING)`. |
| `TEMP_WRITTEN`, invalid temp | Delete temp, keep DB row untouched, re-copy once; repeated failure becomes `FAILED(INTEGRITY_MISMATCH)`. |
| `FINAL_DURABLE`, final valid, DB has old/null path | Perform conditional DB update, then continue. |
| `FINAL_DURABLE`, final missing/invalid | Delete residue and re-copy; do not update DB. |
| `DB_UPDATED`, row points to valid final | Mark `COMPLETED`; do not copy or update again. |
| `DB_UPDATED`, row points elsewhere or final invalid | Do not overwrite the row; mark `FAILED(DB_PATH_CONFLICT)` and delete only the operation-owned output. |
| `COMPLETED` | Verify identity/hash/size and leave it complete; no duplicate file or DB mutation. |
| any state with missing temp directory/source | Mark pending work `FAILED(SOURCE_MISSING)`, preserve the verified DB, and finish the journal. |

The algorithm must use a single journal writer (`synchronized(journalLock)`), merge task updates instead of replacing the ledger, and tolerate a repeated startup run. Cleanup is scoped by operation ID/asset ID, never by a broad receipts-directory delete. Cancellation deletes temp outputs in `NonCancellable`, preserves pending tasks for resume, exits restart-required, and rethrows.

### RP-03D - Semantic-equivalence verification (P7-P1-05)

This is an in-scope correctness gate, not deferred-by-design. Do not mark RP-03 complete while it is absent. If implementation cannot be landed in this change set, **block RP-03 release/sign-off pending a separate approved P7-P1-05 plan**; do not ship a plan that claims counts-only verification is sufficient.

- Extend `CostbackupBundle.BackupManifest` with a versioned, canonical `semanticCheckpoint` object. It must contain deterministic, privacy-safe aggregates sufficient to compare: total purchase amount by currency and home currency, category totals, monthly totals, budget spent/remaining inputs, receipt-to-expense link counts and orphan count, investment value totals by currency, exchange-rate coverage/version inputs, and counts for included lifecycle/event tables. Use integer minor units/decimal strings, currency codes, stable sorted keys, and explicit calculation period boundaries; never floating-point JSON or raw rows.
- Collect the checkpoint from the same frozen snapshot used for table counts, before bundle creation. Verify it against both the migrated staged DB (pre-swap) and the fresh live DB (post-swap). The live checkpoint comparison is mandatory before `ASSETS_RESTORING`; mismatch triggers existing rollback and `CRITICAL_RECOVERY_REQUIRED` on rollback failure.
- Reuse canonical production calculators (`MultiCurrencyRepository`/`AnalyticsCurrencyNormalizer`/budget and receipt-link services) or add a backup-specific read-only adapter with exactly documented rounding and home-currency policy. Do not duplicate business rules in UI or mutate data while calculating.
- Define compatibility behavior: a manifest without a checkpoint is rejected before destructive swap for the current format, or is accepted only under an explicitly versioned legacy format with a blocked/unsupported result. Never silently downgrade to counts-only.

## 3. Room, schema, and lifetime implications

- The semantic checkpoint, asset hashes/sizes, and task status are bundle/journal metadata, not Room entities; no Room schema version or migration is required for those fields. Update manifest/journal serializers with additive parsing tests and preserve legacy decrypt/read behavior.
- A database file swap does not invalidate Hilt-held `AppDatabase` references. Keep `RESTORE_COMPLETE_RESTART_REQUIRED` and do not let a dismiss action return to `NORMAL` until process restart is real. If product requires in-process continuation, stop and obtain a separate approved `DatabaseProvider`/singleton invalidation design covering every Room consumer, workers, and DI binding; a repository-local reassignment is insufficient.
- Asset path updates remain the narrowly approved restore-internal mutation and must be guarded by `RestoreInternalWriteScope` plus the restore journal. Do not route restore file replacement through ordinary receipt/expense lifecycle creation, and do not add direct DAO writers.
- Preserve schema v148 and the active migration policy. Any future manifest checkpoint that depends on a schema/entity change must update `DatabaseSchemaPolicy`, version, migration, exported schema snapshot, migration matrix, and migration tests before being accepted.

## 4. Exact validation

Add or update these tests with the names/coverage below:

- `BackupRestoreRoundtripGoldenTest`: encrypted bundle round trip, deterministic semantic checkpoint, category/month/currency/budget/link/investment checkpoint equality, and changed amount with unchanged row count fails before commit and after live verification.
- `BackupRestoreViewModelTest`: SAF URI success; null `openOutputStream`; open exception; write exception; close exception; best-effort document deletion; generic error; no success metric/journal damage.
- `BackupEncryptionServiceTest`: current envelope round trip, legacy envelope decrypt, wrong password maps to existing UX exception, magic-present truncation/unsupported parameters fail cleanly.
- `CostbackupBundleLimitsTest`: manifest asset ID/entry path validation, duplicate identity rejection, exact byte-size/hash validation, zip-slip and decompression limits, and no raw filename in diagnostics.
- `AssetRestoreAtomicityTest`: every state in the crash table above; crash after temp fsync, after rename before journal, after journal before DB update, after DB update before completion; repeated resume creates one final file and one path update; hash/size mismatch leaves old row unchanged; DB path conflict does not overwrite; missing source marks failed; cancellation deletes temp but preserves pending journal; final path is deterministic across retries.
- `RestoreJournalDurabilityTest`: atomic temp/fsync/rename, merged task ledger, concurrent append serialization, additive JSON round trip, and diagnostics JSON contains no internal paths, source names, or target names.
- `AppStartupCoordinatorRecoveryTest`: `ASSETS_RESTORING` resumes pending tasks, completes with failed missing assets without rollback, re-verifies swapped DB, and enters critical recovery on verification failure; stale `AppDatabase` consumers remain blocked until restart.
- `DatabaseBackupRepositoryImplTest`: safety backup fsync/partial-copy cleanup; pre/post-swap cancellation; post-swap exception leaves restart-required; rollback deletes staged DB/WAL/SHM; `.pre_restore` recovery ordering; journal task merge.
- `BackupVerifierManifestTest`: all required counts/checkpoint fields are present, checkpoint comparison uses canonical rounding/sorting, legacy/no-checkpoint policy is enforced, and Tier 1 `privacy_audit_events` remains required.
- `RestoreBlocksAllWritesTest` and `WorkerRestoreRegressionTest`: all maintenance modes block writes, worker drain/checkpoint behavior remains intact, and no notification permission denial blocks unrelated cleanup/recovery.
- `BackupRestoreIntegrityE2ETest`: create encrypted bundle with receipt assets, kill/relaunch at each DB and asset boundary, verify journal/mode/recovery, semantic equivalence, hashes/sizes, no broken receipt paths, and restart-required lifetime.
- Architecture/static tests: `BackupRestoreArchitectureGuardTest` must reject direct restore DAO writes, raw asset/path/filename diagnostics, missing write-barrier checks, non-cancellable swallowing, and use of repository-local Room after swap.

Do not run Gradle as part of this documentation change. Before implementation sign-off, run focused tests first, then the relevant module test/check commands under the repository's single-Gradle-owner rule; record exit code and log path. No test result may be reported as passed from file existence alone.

## 5. Stop conditions

Stop the batch and obtain architecture/privacy review if any of these occur:

- the source differs from the baseline contracts above, a required class/test is absent, or a proposed change bypasses a lifecycle coordinator/barrier;
- any path can write the destination or live DB before encryption/verification, safety backup, or required journal transition;
- any cancellation is caught without rethrow, cleanup is not `NonCancellable`, or cleanup can delete another operation's asset;
- asset identity, hash, size, journal, temp/final/DB ordering, or SAF failure behavior cannot be proven by tests;
- semantic checkpoint collection/comparison cannot use canonical money/currency semantics, or only counts-only verification remains;
- stale Room consumers could become writable without a real restart/reopenable provider;
- any diagnostic contains raw exception text, URI/path, filename, OCR/receipt text, or financial payload;
- a Room/entity/schema change is discovered without an approved migration, exported schema, and migration-test update;
- any focused test fails, any static guard fails, or rollback/critical-recovery behavior is not fail-closed.

## 6. Sequencing and acceptance gate

1. **3a:** SAF destination handling, safety-copy durability, and approved encryption-envelope compatibility. Run stream/encryption/safety tests.
2. **3b:** state machine, stable asset identity, hash/size validation, journal format/merge, cancellation cleanup, startup resume, stale-DB restart contract, and rollback cleanup. Run all journal/asset/repository/startup tests.
3. **3c:** semantic checkpoint (P7-P1-05), verifier wiring, cross-currency/budget/receipt/investment tests, then architecture/privacy/static guards and E2E kill/relaunch tests.
4. Update this remediation document and trackers only after implementation, focused tests, strict review, and required guardian gates pass. Until then status is **pending**, not green/complete.

Acceptance requires: encrypted verified backup output; no SAF failure leak; deterministic idempotent asset restore across every crash ordering; exact hash/size checks; cancellation-safe scoped cleanup; preserved maintenance/drain/read/write barriers; fresh-DB/restart safety; fail-closed rollback; privacy-safe diagnostics; and passing P7-P1-05 semantic-equivalence verification.
