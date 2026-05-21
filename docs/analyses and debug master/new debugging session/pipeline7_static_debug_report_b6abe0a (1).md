# Pipeline 7 Static Debug Report — Backup / Restore

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 7 is **substantially improved** from the original debugging report, but it is **not closed**.

Important improvements now exist:

```text
.costbackup encrypted bundle
manifest + checksums
ZIP-slip protection
staged DB verification
post-migration staged verification
restore journal
restore maintenance mode
fail-closed startup recovery on safety-copy failure
release-disabled raw DB export
release-disabled legacy .db import
legacy import now has maintenance mode + journal in debug
worker resume uses WorkerRegistry
DatabaseWriteBarrier / DatabaseReadBarrier exist
restart-required restore mode blocks writes
backup creation enters BACKUP_EXPORTING mode
asset restore has ASSETS_RESTORING journal state
BackupVerifier checks more Tier 1 tables and some semantic orphans
```

But several critical guarantees remain incomplete.

Highest remaining user-impact risks:

1. **Restore still uses the stale injected Room instance after DB file swap** for live verification and receipt asset path updates.
2. **Startup crash recovery copies the safety backup but does not verify the restored live DB before allowing normal mode.**
3. **Legacy debug import can still exit to NORMAL if rollback fails after destination DB mutation.**
4. **`resetDatabase()` is a destructive DB file operation with no maintenance mode, no journal, and no restart-required state.**
5. **Backup snapshot consistency still depends on every writer respecting `BACKUP_EXPORTING`; it does not use SQLite backup API / `VACUUM INTO`.**
6. **Receipt asset restore is best-effort, non-resumable, copy-before-validate, and uses stale DAO after restore.**
7. **Restore equivalence is still mostly row-count based, not dashboard/analytics/budget/receipt/recurring semantic equivalence.**
8. **Privacy audit events remain optional in restore verification.**
9. **Restore file URI copy has no size/header precheck and bundle extraction has no zip/entry size limit.**
10. **Restart-required UX is only clearly enforced on the backup screen, not as a global app operational lock.**

Current status: **yellow/orange**. The production `.costbackup` path is much safer than before, but restore cannot be called fully safe until stale-Room use, destructive reset, startup verification, and asset-resume semantics are fixed.

---

# Sources checked

- Commit page:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 7 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-7-backup-restore-debug-report.md

- Current code:
  - `DatabaseBackupRepositoryImpl.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
  - `RestoreMaintenanceMode.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
  - `RestoreJournal.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
  - `BackupVerifier.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt
  - `CostbackupBundle.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt
  - `DatabaseWriteBarrier.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt
  - `DatabaseReadBarrier.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt
  - `AppStartupCoordinator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt
  - `BackupRestoreViewModel.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt
  - `BackupRestoreScreen.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt
  - `WorkerRegistry.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt
  - `WorkerSpec.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

---

# 1. Tracker reconciliation

Master tracker currently says Pipeline 7:

| ID | Tracker status |
|---|---|
| P7-P0-01 | TODO |
| P7-P0-02 | fixed |
| P7-P1-01 | TODO |
| P7-P1-02 | TODO |
| P7-P1-03 | TODO |
| P7-P1-04 | TODO |
| P7-P1-05 | TODO |
| P7-P1-06 | TODO |
| P7-P1-07 | fixed |
| P7-P1-08 | TODO |

My current status after checking current code:

| ID | My status | Reason |
|---|---:|---|
| P7-P0-01 | **Partial** | Legacy `.db` import is release-disabled and debug path now enters maintenance mode + writes a restore journal. But it still uses stale Room after swap, and rollback-failure path exits maintenance to NORMAL. |
| P7-P0-02 | **Mostly fixed / caveat** | Startup crash recovery now fails closed when safety-copy fails. But successful safety-copy is not verified with integrity/FK/manifest checks before normal mode is restored. |
| P7-P1-01 | **Open** | Current code comments explicitly admit injected `database` is stale after swap. Live verification and asset path updates still use it. |
| P7-P1-02 | **Partial** | `DatabaseWriteBarrier` exists, and many callers use it, but enforcement remains caller-by-caller, not global at DAO/DB boundary. |
| P7-P1-03 | **Partial** | `createCostBackup()` enters `BACKUP_EXPORTING` and checkpoints WAL, but still copies the live DB file and does not use SQLite backup API / `VACUUM INTO`; running or unguarded writes can still race. |
| P7-P1-04 | **Partial** | `ASSETS_RESTORING` state avoids rolling back verified DB after asset crash, but no durable asset task ledger/resume exists and orphan files can remain. |
| P7-P1-05 | **Partial** | Verifier is stronger and has some semantic orphan checks, but dashboard/analytics/budget/receipt/recurring output equivalence is not proven. |
| P7-P1-06 | **Open** | `privacy_audit_events` is still Tier 3 optional. |
| P7-P1-07 | **Mostly fixed** | `RestoreMaintenanceMode.scheduleAllWorkers()` and startup use `WorkerRegistry.scheduleAll`. Missing guard test that `WorkerSpec.DEFAULTS == WorkerRegistry.entries`. |
| P7-P1-08 | **Partial** | Backup screen restart banner is non-dismissable and restore exits to restart-required mode. But deprecated `dismissRestartRequired()` still clears local UI state if called, and no global app-wide lock was evident in this slice. |

---

# 2. Original issue evaluation

## P7-P0-01 — Legacy `.db` import lacks journal and maintenance mode

### Current state

Partially fixed.

Good:

- `importDatabase()` is disabled outside `BuildConfig.DEBUG`.
- Debug legacy import enters `RestoreMaintenanceMode.Mode.RESTORE_PREPARING`.
- It creates a `RestoreJournal`.
- It transitions through `STAGED`, `SAFETY_BACKUP_CREATED`, `SWAPPING`, `VERIFYING`.
- It creates a safety backup before swap.
- It exits with `forceRestartRequired = true` on success.

Remaining high-risk problems:

1. Legacy import still uses `liveImportVerifier(database, liveDbFile, ...)`, where `database` is the injected singleton Room instance from before file swap.
2. If import fails after destination files were mutated and rollback also fails, the code calls:

```text
restoreMaintenanceMode.exit(forceRestartRequired = false)
```

before returning failure. That means debug import can resume writes even though manual recovery may be required.

3. The source file comments still describe the old unsafe path, which can confuse future agents.
4. Debug-only is a release safety improvement, but not a true correctness fix for debug/dev builds.

### Classification

- **Release user-impact:** mostly mitigated by disabling in release.
- **Developer/data safety bug:** still real.
- **Architecture:** should share the `.costbackup` restore engine.

### Fix strategy

Use one restore state machine for both `.costbackup` and legacy `.db`.

Minimum patch:

```kotlin
if (destinationFilesMutated && !importSucceeded && rollbackResult.isFailure) {
    restoreJournal.failJournal(journalEntry, "Import rollback failed: ...")
    restoreMaintenanceMode.exit(forceRestartRequired = true)
    return Result.failure(CriticalRestoreFailure(...))
}
```

Preferred:

- Move file-swap/rollback/verify into `RestoreOrchestrator`.
- Legacy import should only produce a staged DB file + expected summary, then call the common restore engine.

---

## P7-P0-02 — Startup crash recovery can resume writes after failed recovery

### Current state

Mostly fixed.

Good:

- `AppStartupCoordinator.checkRestoreJournal()` tracks `recovered = false`.
- If safety backup copy fails, it calls `restoreJournal.failJournal(...)`.
- It enters `RESTORE_COMPLETE_RESTART_REQUIRED`.
- It returns early, so final `reset()` is skipped.
- `initialize()` then skips worker scheduling because writes are blocked.

Remaining caveat:

If safety backup copy succeeds, the code does **not** run:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
BackupVerifier.verifyQuick / verify
Room migration/open validation
```

It assumes copy success means DB recovery success, then cleans staging, deletes journal, and later resets maintenance mode to NORMAL.

### User impact

If the safety backup file is readable but corrupted/incomplete, startup can resume normal operation after copying it.

### Fix strategy

After safety backup copy, verify the live DB before setting `recovered = true`:

```kotlin
copySafetyBackup()
val verification = BackupVerifier.verify(liveDbFile, expectedCountsFromJournalOrManifest)
if (!verification.passed) recovered = false
```

If expected counts are unavailable, at minimum require:

```text
PRAGMA integrity_check = ok
PRAGMA foreign_key_check = 0 rows
Room can open with current schema
```

---

## P7-P1-01 — Restore uses stale injected Room instance after DB file swap

### Current state

Open.

Current `restoreCostBackup()` closes and swaps DB files, then does:

```kotlin
database.openHelper.writableDatabase
refreshInvalidationTrackerSafelyForVerification(database)
val liveSummary = queryRoomCountsForVerification(database)
...
val dao = database.scannedReceiptDao()
dao.update(...)
```

The code comments explicitly say the injected `database` is stale and that restart is relied upon.

Legacy import has the same problem via `liveImportVerifier(database, ...)`.

### User impact

After file swap:

- verification can be false or crash-prone;
- DAO handles can point at a closed/stale connection;
- receipt asset image path updates may not persist correctly;
- restore correctness depends on process restart before any DAO use.

### Fix strategy

Add restore-only fresh DB opener:

```kotlin
interface RestoreDatabaseOpener {
    fun openFreshDatabase(databaseName: String = AppDatabase.DATABASE_NAME): AppDatabase
}
```

After swap:

```kotlin
val freshDb = restoreDatabaseOpener.openFreshDatabase()
try {
    freshDb.openHelper.writableDatabase
    val liveSummary = queryRoomCountsForVerification(freshDb)
    restoreReceiptAssets(tempDir, manifest, freshDb)
} finally {
    freshDb.close()
}
```

Rule:

```text
No injected app-wide Room/DAO may be used after DB file swap.
```

---

## P7-P1-02 — Maintenance mode is not a global DB write barrier

### Current state

Partial.

Good:

- `DatabaseWriteBarrier` exists.
- It blocks writes unless maintenance mode is `NORMAL`.
- `DatabaseReadBarrier` exists and blocks reads except in `NORMAL` and `BACKUP_EXPORTING`.
- Multiple prior pipeline reports found many repositories now use write barriers.

Still not a global guarantee:

1. Barrier is not enforced by Room/DAO layer.
2. Any new direct DAO call can bypass it.
3. Existing direct mutation methods in other pipelines still need static guard verification.
4. `DatabaseBackupRepositoryImpl.resetDatabase()` performs destructive DB file deletion without entering maintenance mode or using journal.
5. Worker cancellation in maintenance mode is async; already-running workers may continue unless their internal write methods check the barrier.

### User impact

During backup/restore/reset, any unguarded foreground write or already-running worker can mutate files/tables unexpectedly.

### Fix strategy

Add static enforcement:

```text
Every production DAO mutation call must be inside:
- lifecycle coordinator using DatabaseWriteBarrier, or
- repository method using DatabaseWriteBarrier, or
- Room migration/debug-only path.
```

Also make destructive DB-file operations use a stronger state machine:

```text
enter maintenance
pause/drain workers
journal
close DB
mutate files
restart required
```

---

## P7-P1-03 — Backup creation does not freeze writes or use SQLite backup API

### Current state

Partial.

Good:

- `createCostBackup()` enters `BACKUP_EXPORTING`.
- It checkpoints WAL with `PRAGMA wal_checkpoint(TRUNCATE)`.
- `isWritesAllowed()` returns `false` in `BACKUP_EXPORTING`, so guarded writes are blocked.
- Worker pause is triggered by maintenance mode.

Remaining issues:

1. Snapshot is still made by copying the live DB file.
2. It does not use SQLite online backup API or `VACUUM INTO`.
3. `cancelUniqueWork()` does not synchronously drain already-running workers.
4. Any unguarded writer can still write after checkpoint and during file copy.
5. Operation events are only Timber logs, not durable `backup_restore_events`.

### User impact

A backup can be internally valid but not a clean expected point-in-time snapshot if unguarded writes race the copy.

### Fix strategy

Preferred:

```text
Use SQLite backup API or VACUUM INTO to create a consistent snapshot file.
```

Minimum:

```text
enter BACKUP_EXPORTING
wait for running WorkerExecutionGuard leases to drain
block foreground writes via global barrier
checkpoint WAL
copy DB + verify copy
exit mode
```

Add durable events:

```text
BACKUP_STARTED
BACKUP_MODE_ENTERED
WAL_CHECKPOINTED
SNAPSHOT_CREATED
MANIFEST_WRITTEN
BACKUP_COMPLETED
BACKUP_FAILED
```

---

## P7-P1-04 — Receipt asset restore not atomic with DB restore

### Current state

Partial.

Good:

- Restore journal has `ASSETS_RESTORING`.
- `RestoreJournal.checkAndRecover()` treats `ASSETS_RESTORING` as non-destructive, so a crash during asset restore should not roll back the already verified DB.

Remaining problems:

1. No durable per-asset task ledger.
2. Asset restore is not resumable.
3. Asset restore uses stale injected `database.scannedReceiptDao()` after swap.
4. It copies destination file before parsing receipt ID and checking row existence.
5. If ID parse fails, row is missing, or DB update fails, copied file remains orphaned.
6. On crash during asset restore, journal is deleted on next startup and asset repair is not resumed.

### User impact

After restore, receipt rows may have missing/wrong image paths, or receipt files may exist on disk with no DB reference.

### Fix strategy

Use a durable asset restore ledger in journal or DB:

```kotlin
data class AssetRestoreTask(
    val receiptId: Long,
    val sourceAssetPath: String,
    val targetPath: String,
    val status: PENDING | COPIED | DB_UPDATED | FAILED,
    val error: String?
)
```

Correct order:

```text
parse receipt ID
verify receipt row exists in fresh restored DB
copy to temp file
update DB path using fresh DB
rename temp to final
mark task complete
```

On startup:

```text
if incomplete asset tasks exist, resume repair or show visible warning
```

---

## P7-P1-05 — Restore success does not prove dashboard/analytics equivalence

### Current state

Partial.

Good:

- `BackupVerifier` checks PRAGMA integrity and FK.
- Tier 1 exact counts cover many important tables.
- It includes semantic orphan checks for:
  - `receipt_expense_links` → expenses,
  - `recurring_occurrences` → manual recurring expenses,
  - `budget_forecasts` → budgets.

Still missing:

- dashboard totals equivalence,
- analytics category/month totals equivalence,
- budget status equivalence,
- recurring planned/open/paid semantics,
- receipt link semantics on both receipt and expense sides,
- exchange rate sufficiency for restored analytics,
- privacy audit policy enforcement,
- asset path existence after restore.

### User impact

Restore can pass row-count verification while app behavior changes.

Example:

```text
same number of expenses
but exchange rates missing/optional
=> dashboard totals differ
```

### Fix strategy

Add pre-backup semantic checkpoints into manifest:

```json
{
  "semanticCheckpoints": {
    "dashboardMonthTotal": "...",
    "categoryTotalsHash": "...",
    "budgetStatusHash": "...",
    "receiptLinkCount": 10,
    "recurringOpenCount": 4,
    "privacyAuditCount": 25
  }
}
```

Then after restore, recompute and compare.

---

## P7-P1-06 — Privacy audit events optional in backup verification

### Current state

Open.

`BackupVerifier` still classifies:

```text
privacy_audit_events -> TIER_3_OPTIONAL
```

### User impact

A restored backup can drop privacy audit rows and still pass verification.

For a privacy-heavy app, audit history is not obviously disposable cache.

### Fix strategy

Choose explicit policy.

Option A — preserve:

```text
privacy_audit_events = TIER_1_EXACT
```

Option B — intentionally exclude/redact:

```text
manifest.privacyAuditIncluded = false
UI warning: privacy audit history excluded
verification requires manifest declaration
```

Do not allow silent optional drop.

---

## P7-P1-07 — Worker pause/resume not fully spec-driven

### Current state

Mostly fixed.

Good:

- `RestoreMaintenanceMode.pauseAllWorkers()` cancels every key in `WorkerSpec.DEFAULTS`.
- `scheduleAllWorkers()` now calls `WorkerRegistry.scheduleAll`.
- `AppStartupCoordinator.scheduleStartupWork()` also calls `WorkerRegistry.scheduleAll`.

Remaining caveat:

There is no visible compile/test guard that every `WorkerSpec.DEFAULTS` key has exactly one `WorkerRegistry.Entry`, and vice versa.

### Fix strategy

Add unit test:

```kotlin
assertEquals(
    WorkerSpec.DEFAULTS.keys,
    WorkerRegistry.entries.map { it.specName }.toSet()
)
```

Also ensure disabled specs are not rescheduled unexpectedly.

---

## P7-P1-08 — Successful restore leaves app blocked; UI can dismiss warning

### Current state

Partial.

Good:

- `restoreCostBackup()` exits with `forceRestartRequired = true`.
- `RestoreMaintenanceMode` keeps writes blocked in `RESTORE_COMPLETE_RESTART_REQUIRED`.
- `BackupRestoreScreen` shows a non-dismissable restart banner with only “Restart Now”.
- Comment says no dismiss button.

Remaining issues:

1. `BackupRestoreViewModel.dismissRestartRequired()` still exists and still clears local `restartRequired`.
2. The restart-required state appears local to the backup UI plus a startup pref flag. I did not see a global app-shell operational lock in this slice.
3. If user navigates away from backup screen, other screens may still be visible. Guarded writes fail, but unguarded writes may still happen.
4. Reads can still occur unless every read path uses `DatabaseReadBarrier`.

### Fix strategy

Introduce global state:

```kotlin
sealed interface AppOperationalState {
    data object Normal : AppOperationalState
    data object BackupExporting : AppOperationalState
    data object RestoreInProgress : AppOperationalState
    data object RestartRequiredAfterRestore : AppOperationalState
    data object CriticalRestoreRecoveryRequired : AppOperationalState
}
```

App shell should block navigation/actions until restart.

Remove `dismissRestartRequired()` or make it no-op.

---

# 3. New/current issues found

## P7-NEW-01 — Legacy import rollback failure exits to NORMAL

### Severity

P0 for debug/dev data, lower for release because legacy import is disabled.

### Evidence

In `importDatabase()`, if destination files were mutated and rollback fails, code still calls:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = false)
```

before returning failure.

### Impact

The app can resume normal writes after a failed import and failed rollback.

### Fix

If rollback fails:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = true)
restoreJournal.failJournal(...)
return CriticalRestoreFailure
```

Do not allow NORMAL.

---

## P7-NEW-02 — Startup recovery success is not verified

### Severity

P1 / possible P0 in corruption case.

### Evidence

Startup recovery copies safety backup files, sets `recovered = true`, cleans staging, deletes journal, and later resets maintenance to NORMAL. It does not run DB integrity/FK/Room-open verification.

### Impact

A corrupt safety backup copy can be treated as successful recovery.

### Fix

After copy:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
Room open with current schema
BackupVerifier verify if expected counts are available
```

Only then reset to normal.

---

## P7-NEW-03 — `resetDatabase()` is destructive but not journaled/maintenance guarded

### Severity

P1.

### Evidence

`resetDatabase()`:

```text
createSafetyBackup()
database.close()
delete DB/WAL/SHM files
return success
```

It does not:

- enter maintenance mode,
- pause/drain workers,
- create restore journal,
- force restart,
- use `DatabaseWriteBarrier`,
- verify reset state.

### Impact

Foreground/worker writes can race the delete. The injected Room instance is stale after file deletion. A crash during reset has no recovery journal.

### Fix

Make reset a lifecycle operation:

```text
enter RESETTING_DATABASE
begin journal
create safety backup
close fresh DB
delete/swap to empty DB
verify empty DB
exit restart-required
```

Or remove reset from production.

---

## P7-NEW-04 — Receipt asset restore copies before validating target receipt

### Severity

P1/P2.

### Evidence

`restoreReceiptAssets()` creates `destFile`, copies the asset, then parses receipt ID and checks DB row.

### Impact

Invalid filenames or missing receipt rows leave orphan files.

### Fix

Validate first, then copy to temp, update DB, rename atomically. Delete temp/final on failure.

---

## P7-NEW-05 — Restore live verification and asset repair both use stale Room

### Severity

P1.

### Evidence

Both `queryRoomCountsForVerification(database)` and `database.scannedReceiptDao().update(...)` are used after file swap.

### Impact

Verification and image path repair are not trustworthy.

### Fix

Use fresh restore-only Room instance.

---

## P7-NEW-06 — Restore URI copy has no size/header precheck

### Severity

P2, possibly P1 for storage exhaustion.

### Evidence

`BackupRestoreViewModel.restoreBackup()` opens selected URI and copies the whole stream to a temp file before repository validation.

### Impact

Large/wrong files can fill app cache before being rejected.

### Fix

Before full copy:

```text
check ContentResolver size metadata
enforce MAX_BACKUP_BUNDLE_BYTES
read first 13 bytes and validate COSTBACKUP1 header
delete temp on failure
```

---

## P7-NEW-07 — Bundle extraction has no zip/entry size limits

### Severity

P2/P1.

### Evidence

`CostbackupBundle.extract()` streams decrypted ZIP entries to disk and validates checksums after extraction, but there is no max total extracted bytes or max entry count/size.

### Impact

A validly encrypted but malicious/huge bundle can exhaust storage.

### Fix

Add limits:

```text
MAX_BACKUP_BUNDLE_BYTES
MAX_EXTRACTED_BYTES
MAX_ZIP_ENTRIES
MAX_DATABASE_BYTES
MAX_ASSET_BYTES_PER_FILE
```

Track bytes while streaming.

---

## P7-NEW-08 — `verifyQuick()` can skip missing Tier 1 manifest counts

### Severity

P2.

### Evidence

`BackupVerifier.verifyQuick()` loops Tier 1 tables but does:

```kotlin
val expected = expectedCounts[tableName]
if (expected == null) continue
```

Full `verify()` fails missing Tier 1 counts, but quick pre-swap can pass incomplete manifests.

### Impact

Bad manifests can be caught only after live swap, causing unnecessary rollback risk.

### Fix

Add pre-swap manifest completeness validation for `.costbackup`:

```kotlin
BackupVerifier.validateManifestCompleteness(manifest.tableCounts)
```

---

## P7-NEW-09 — Durable backup/restore operation ledger is still missing

### Severity

P2.

### Evidence

Current operation reporting is mainly:

- restore journal for in-progress restore,
- preserved failure journal,
- Timber logs.

No `backup_restore_events` table/entity was found in the checked backup package.

### Impact

User support/debugging still depends on logcat or files.

### Fix

Add durable ledger:

```kotlin
BackupRestoreEvent(
    operationId,
    operationType,
    stage,
    outcome,
    backupPathHash,
    schemaVersion,
    tableCountsJson,
    warningsJson,
    errorClass,
    errorMessage,
    timestamp
)
```

---

## P7-NEW-10 — Safety backup filename can collide within the same second

### Severity

P2 edge.

### Evidence

Safety backup filename uses timestamp format with seconds:

```text
expense_tracker_backup_SAFETY_yyyy-MM-dd_HH-mm-ss.db
```

No UUID suffix.

### Impact

Two safety backups in the same second can overwrite or collide, weakening rollback diagnostics.

### Fix

Add UUID/random suffix, like `.costbackup` output already does.

---

# 4. Actual bugs vs architectural work

## Actual user/data-affecting bugs

Prioritize:

1. **Stale injected Room after restore swap.**
2. **Startup recovery success not verified.**
3. **Legacy import rollback failure exits to NORMAL.**
4. **Destructive reset is not journaled/maintenance guarded.**
5. **Receipt asset restore can leave orphan files and is not resumable.**
6. **Backup snapshot still relies on caller-by-caller write blocking.**
7. **Privacy audit events can be silently excluded.**
8. **Restore URI / ZIP extraction can exhaust storage.**
9. **Restart-required state is not clearly global.**

## Architectural / hardening work

Important but lower immediate urgency:

1. Backup/restore operation ledger.
2. Golden semantic restore-equivalence tests.
3. Worker registry equality guard.
4. Static DAO write/read barrier guard.
5. Remove/deprecate legacy raw export/import from production interface.
6. Add restore-only DB opener abstraction.
7. Add asset repair worker.
8. Add size/version manifest policy.

---

# 5. Recommended implementation plan

## PR 1 — Fresh Room after DB swap

### Goal

No stale app-wide Room/DAO is used after file replacement.

### Files

- `DatabaseBackupRepositoryImpl.kt`
- new `RestoreDatabaseOpener.kt`
- `AppDatabase.kt`
- tests

### Tasks

1. Add `RestoreDatabaseOpener`.
2. Use fresh DB for:
   - live verification,
   - live summary query,
   - receipt asset path updates,
   - rollback verification.
3. Remove `database.openHelper.writableDatabase` after swap.
4. Keep app-wide singleton invalid until process restart.

### Acceptance tests

```text
restore_live_verification_uses_fresh_room
restore_asset_path_updates_use_fresh_room
injected_database_not_used_after_swap
legacy_import_live_verifier_uses_fresh_room
```

---

## PR 2 — Fail-closed all destructive restore/import paths

### Goal

No failed restore/import/reset can resume writes on unknown DB state.

### Files

- `DatabaseBackupRepositoryImpl.kt`
- `RestoreJournal.kt`
- `RestoreMaintenanceMode.kt`
- `AppStartupCoordinator.kt`

### Tasks

1. Fix legacy import rollback-failure path to exit restart-required.
2. Verify startup safety recovery before NORMAL.
3. Add critical state for recovery-failed:
   - `CRITICAL_RECOVERY_REQUIRED`, or
   - `RESTORE_COMPLETE_RESTART_REQUIRED` with failure reason.
4. Do not delete/preserve journal incorrectly before verification.
5. Add tests for rollback-failure branches.

### Acceptance tests

```text
legacy_import_rollback_failure_keeps_writes_blocked
startup_safety_copy_success_but_integrity_failure_keeps_writes_blocked
startup_safety_copy_success_and_verified_resets_normal
```

---

## PR 3 — Make reset database lifecycle-safe or remove it

### Goal

Reset cannot race writes or leave stale Room active.

### Files

- `DatabaseBackupRepositoryImpl.kt`
- `DatabaseBackupRepository.kt`
- UI reset caller if any
- tests

### Tasks

1. Enter maintenance mode before reset.
2. Write reset journal or backup/restore event.
3. Create safety backup with UUID filename.
4. Close DB.
5. Delete/swap DB.
6. Verify empty/new DB.
7. Exit restart-required.
8. Require typed confirmation in repository API, not only comments.

### Acceptance tests

```text
reset_enters_maintenance_mode
reset_blocks_workers_and_writes
reset_failure_preserves_safety_backup_and_blocks_writes
reset_success_requires_restart
reset_requires_typed_confirmation
```

---

## PR 4 — Point-in-time backup snapshot

### Goal

Backup file is a consistent snapshot independent of unguarded writes.

### Files

- `DatabaseBackupRepositoryImpl.kt`
- `RestoreMaintenanceMode.kt`
- maybe native/SQLite helper

### Tasks

1. Prefer `VACUUM INTO` or SQLite online backup API.
2. If unavailable, implement a strict no-write window:
   - enter `BACKUP_EXPORTING`,
   - wait for running worker leases,
   - checkpoint WAL,
   - copy DB,
   - verify snapshot.
3. Add durable backup events.
4. Ensure failures always exit maintenance mode.

### Acceptance tests

```text
backup_snapshot_consistent_under_concurrent_writes
backup_enters_backup_exporting
backup_failure_exits_maintenance
backup_uses_snapshot_file_not_live_file_copy_when_available
```

---

## PR 5 — Receipt asset restore ledger and idempotency

### Goal

Receipt images restore exactly once or produce durable visible warnings.

### Files

- `DatabaseBackupRepositoryImpl.kt`
- `RestoreJournal.kt`
- `ReceiptAssetStore.kt`
- `ScannedReceiptDao.kt`
- optional new `restore_asset_tasks` table

### Tasks

1. Parse and validate receipt ID before copy.
2. Use fresh restored DB.
3. Copy to temp file then rename.
4. Add per-asset task status.
5. Resume incomplete asset restore on startup.
6. Delete orphan temp/final files on failure.

### Acceptance tests

```text
asset_invalid_filename_leaves_no_orphan_file
asset_missing_receipt_row_leaves_no_orphan_file
crash_mid_asset_restore_resumes
asset_restore_uses_fresh_restored_db
```

---

## PR 6 — Manifest completeness and semantic restore equivalence

### Goal

Restore proves app behavior, not only row counts.

### Files

- `BackupVerifier.kt`
- `CostbackupBundle.kt`
- integration tests

### Tasks

1. Add `validateManifestCompleteness()` before swap.
2. Add semantic checkpoints:
   - dashboard totals,
   - analytics category totals,
   - budget status,
   - receipt links,
   - recurring planned/paid counts,
   - privacy audit count/policy.
3. Fail before swap where possible.
4. Run post-restore semantic verification using fresh DB.

### Acceptance tests

```text
missing_tier1_manifest_count_fails_before_swap
restore_preserves_dashboard_total
restore_preserves_analytics_category_total
restore_preserves_budget_status
restore_preserves_receipt_links
restore_preserves_recurring_state
```

---

## PR 7 — Privacy audit backup contract

### Goal

Privacy audit history is either preserved or explicitly excluded.

### Files

- `BackupVerifier.kt`
- `CostbackupBundle.kt`
- backup UI/options
- tests

### Tasks

1. Decide product policy.
2. If preserved, move `privacy_audit_events` to Tier 1 exact.
3. If excluded, add manifest declaration and UI warning.
4. Verification fails if actual behavior differs from manifest.

### Acceptance tests

```text
privacy_audit_preserved_when_policy_include
privacy_audit_excluded_only_when_manifest_declares
restore_fails_when_required_privacy_audit_missing
```

---

## PR 8 — Restore file and ZIP size limits

### Goal

Wrong/huge files are rejected early and safely.

### Files

- `BackupRestoreViewModel.kt`
- `CostbackupBundle.kt`
- tests

### Tasks

1. Add max bundle size.
2. Check URI metadata before copy.
3. Read and validate `.costbackup` header before full copy.
4. Add extraction byte accounting.
5. Reject oversized DB/assets/entry count.
6. Always delete temp files.

### Acceptance tests

```text
restore_rejects_oversize_uri_before_full_copy
restore_rejects_wrong_magic_before_repository_call
extract_rejects_zip_over_max_size
extract_rejects_too_many_entries
temp_files_deleted_on_failure
```

---

## PR 9 — Global restart-required app lock

### Goal

After restore, user cannot keep using stale app state.

### Files

- app shell / navigation root
- `RestoreMaintenanceMode.kt`
- `AppStartupCoordinator.kt`
- `BackupRestoreViewModel.kt`
- `BackupRestoreScreen.kt`

### Tasks

1. Add `AppOperationalState`.
2. App shell observes restore mode.
3. Show global blocking restart screen/banner.
4. Remove or no-op `dismissRestartRequired()`.
5. Disable all write actions and ideally most reads until restart.

### Acceptance tests

```text
restore_success_global_lock_visible
dismiss_restart_required_noop
navigation_blocked_until_restart
startup_after_restart_resets_mode_to_normal
```

---

## PR 10 — Durable backup/restore event ledger

### Goal

User support can diagnose backup/restore without logcat.

### Files

- new `BackupRestoreEvent.kt`
- DAO + migration
- `DatabaseBackupRepositoryImpl.kt`
- `AppStartupCoordinator.kt`

### Tasks

1. Add event entity.
2. Write operation ID and stage events.
3. Include warnings and error class/message.
4. Surface last restore/backup status in UI.
5. Keep privacy-safe path hashing.

### Acceptance tests

```text
wrong_password_writes_extract_failed_event
restore_success_writes_restart_required_event
rollback_success_writes_rollback_event
asset_warning_writes_warning_event
```

---

# 6. Suggested tracker updates

Update Pipeline 7 tracker:

| ID | Suggested status |
|---|---|
| P7-P0-01 | Partial |
| P7-P0-02 | Mostly fixed / verification caveat |
| P7-P1-01 | TODO / open |
| P7-P1-02 | Partial |
| P7-P1-03 | Partial |
| P7-P1-04 | Partial |
| P7-P1-05 | Partial |
| P7-P1-06 | TODO / open |
| P7-P1-07 | Mostly fixed |
| P7-P1-08 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P7-NEW-01 | P0/P1 | Legacy import rollback failure exits to NORMAL |
| P7-NEW-02 | P1/P0 | Startup recovery success is not verified |
| P7-NEW-03 | P1 | `resetDatabase()` is destructive but not journaled/maintenance guarded |
| P7-NEW-04 | P1/P2 | Receipt asset restore copies before validating target receipt |
| P7-NEW-05 | P1 | Restore live verification and asset repair use stale Room |
| P7-NEW-06 | P2/P1 | Restore URI copy has no size/header precheck |
| P7-NEW-07 | P2/P1 | Bundle extraction has no zip/entry size limits |
| P7-NEW-08 | P2 | `verifyQuick()` can skip missing Tier 1 manifest counts |
| P7-NEW-09 | P2 | Durable backup/restore operation ledger is missing |
| P7-NEW-10 | P2 | Safety backup filename can collide within the same second |

---

# 7. Golden tests for Pipeline 7

Add or verify:

```text
costbackup_create_extract_roundtrip_preserves_tier1_counts
costbackup_wrong_password_does_not_touch_live_db
costbackup_tampered_ciphertext_rejected
costbackup_tampered_asset_checksum_rejected
costbackup_missing_tier1_manifest_count_fails_before_swap
backup_enters_backup_exporting_and_exits_on_success
backup_failure_exits_backup_exporting
backup_snapshot_consistent_under_concurrent_writes
restore_enters_maintenance_and_pauses_workers
restore_blocks_all_repository_writes
restore_blocks_notification_processing
restore_staged_migration_failure_leaves_live_db_unchanged
restore_uses_fresh_room_after_swap
restore_asset_updates_use_fresh_room
restore_success_requires_restart_and_blocks_writes
restore_live_verification_failure_rolls_back
restore_rollback_failure_keeps_writes_blocked
startup_recovery_copy_failure_keeps_writes_blocked
startup_recovery_copy_success_but_integrity_failure_keeps_writes_blocked
startup_recovery_success_verifies_before_normal
legacy_import_disabled_in_release
legacy_import_debug_uses_journal_and_maintenance
legacy_import_rollback_failure_keeps_writes_blocked
reset_database_enters_maintenance_and_requires_restart
receipt_assets_restored_and_image_paths_updated
receipt_asset_invalid_filename_leaves_no_orphan_file
receipt_asset_restore_crash_resumes
restore_rejects_oversize_uri_before_full_copy
restore_rejects_wrong_magic_before_full_copy
zip_extract_rejects_oversized_entry
backup_restore_dashboard_totals_equal
backup_restore_analytics_totals_equal
backup_restore_budget_status_equal
backup_restore_receipt_links_equal
backup_restore_recurring_state_equal
privacy_audit_policy_enforced
worker_registry_matches_worker_spec_defaults
global_restart_required_lock_blocks_navigation
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "restoreCostBackup" app/src/main/java
grep -R "importDatabase(" app/src/main/java
grep -R "resetDatabase" app/src/main/java
grep -R "exportDatabase(" app/src/main/java
grep -R "database.openHelper.writableDatabase" app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
grep -R "scannedReceiptDao().update" app/src/main/java
grep -R "RestoreMaintenanceMode.Mode" app/src/main/java
grep -R "DatabaseWriteBarrier" app/src/main/java
grep -R "checkWritesAllowed" app/src/main/java
grep -R "DatabaseReadBarrier" app/src/main/java
grep -R "checkReadAllowed" app/src/main/java
grep -R "WorkerSpec.DEFAULTS" app/src/main/java
grep -R "WorkerRegistry.entries" app/src/main/java
grep -R "dismissRestartRequired" app/src/main/java
grep -R "privacy_audit_events" app/src/main/java
grep -R "verifyQuick" app/src/main/java
```

Allowed DB file operations should be restricted to:

```text
DatabaseBackupRepositoryImpl
RestoreJournal / AppStartupCoordinator recovery
approved test utilities
```

Definition of done:

```text
- No injected singleton Room/DAO is used after DB swap.
- Startup recovery verifies safety-restored DB before NORMAL.
- Legacy import rollback failure never exits to NORMAL.
- resetDatabase is maintenance-guarded, journaled, and restart-required, or removed.
- Backup snapshot uses a true point-in-time mechanism or a proven drained no-write window.
- Receipt asset restore is idempotent, resumable, and leaves no orphan files.
- Manifest completeness is verified before swap.
- Restore semantic tests prove dashboard/analytics/budget/receipt/recurring equivalence.
- Privacy audit backup policy is explicit and verified.
- Restore file copy/extraction has size and header limits.
- Restart-required state is enforced globally.
- Backup/restore events are durable and user-support friendly.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fresh Room after DB swap** — highest restore correctness risk.
2. **Fail-closed destructive paths** — legacy rollback failure + startup recovery verification.
3. **Make `resetDatabase()` lifecycle-safe or remove it.**
4. **Point-in-time backup snapshot / worker-drain contract.**
5. **Receipt asset restore ledger + orphan cleanup.**
6. **Manifest completeness + semantic equivalence verification.**
7. **Privacy audit backup contract.**
8. **Restore URI/header/size and ZIP extraction limits.**
9. **Global restart-required app lock.**
10. **Durable backup/restore event ledger.**