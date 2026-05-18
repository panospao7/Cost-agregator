# Remaining Global Barrier Completion Plan After `d70d3d1`

Baseline commit: `d70d3d1bafddff07c1f2bae09aba7f13b678869b`

Current status:

```text
Global write/read/restore barrier is mostly implemented.
Remaining work is hardening, closing edge cases, and making guarantees explicit/tested.
```

This plan covers the remaining issues:

```text
BARRIER-REM-01  Public createSafetyBackup lacks maintenance/drain guard
BARRIER-REM-02  .costbackup snapshot still file-copy based
BARRIER-REM-03  Restore asset DB writes need formal internal-write scope
BARRIER-REM-04  Critical recovery reason not persisted
BARRIER-REM-05  Maintenance diagnostics async/interface-incomplete
BARRIER-REM-06  Worker run counts mostly zero
BARRIER-REM-07  Static guard still regex/broad allowlist
BARRIER-REM-08  Legacy raw export weaker than .costbackup
```

---

# Executive priority order

Do this order:

```text
PR 1  Safety backup and legacy raw export consistency
PR 2  True SQLite snapshot / verified drained fallback
PR 3  Formal restore-internal write scope
PR 4  Persist critical recovery reason
PR 5  MaintenanceSafeDiagnosticSink API + sync durability
PR 6  WorkerRunContext counters
PR 7  Static guard v4 / Detekt path
PR 8  Asset restore resumability and ledger
PR 9  Backup/restore docs + final golden tests
PR 10 Tracker cleanup and close criteria
```

Fastest safety wins:

```text
1. Guard public createSafetyBackup()
2. Persist critical recovery reason
3. Make maintenance diagnostics synchronous/observable
4. Formalize restore-internal DB writes
5. Add true snapshot or clearly verified fallback
```

---

# PR 1 — Safety backup and legacy raw export consistency

## Issues fixed

```text
BARRIER-REM-01
BARRIER-REM-08
```

## Goal

No public backup/export method may copy the live DB without maintenance mode, worker drain, and verification.

## Current problem

`createSafetyBackup()` is public and copies the live DB. It is safe when called from restore/reset after maintenance has already entered, but unsafe if called standalone.

Legacy `exportDatabase()` is debug-only but weaker than `.costbackup`.

## Files

```text
DatabaseBackupRepositoryImpl.kt
DatabaseBackupRepository.kt
MaintenanceOperationRunner.kt
BackupVerifier.kt
BackupPrivacyGate.kt
tests for DatabaseBackupRepositoryImpl
```

## Implementation

### 1. Split safety backup API

Create private internal method:

```kotlin
private suspend fun createSafetyBackupInternalAssumingMaintenance(
    operationName: String
): Result<File>
```

Rules:

```text
Only call this after:
- maintenance mode has been entered,
- active workers have drained,
- DB writes are blocked.
```

Public method becomes guarded:

```kotlin
override suspend fun createSafetyBackup(): Result<File> =
    maintenanceOperationRunner.runExclusive(
        mode = RestoreMaintenanceMode.Mode.BACKUP_EXPORTING,
        operationName = "createSafetyBackup",
        drainTimeoutPolicy = DrainTimeoutPolicy.FAIL_OPERATION,
        requireRestartAfterSuccess = false
    ) {
        createSafetyBackupInternalAssumingMaintenance("createSafetyBackup")
    }
```

### 2. Use internal method from restore/reset

In restore/reset paths:

```kotlin
val safety = createSafetyBackupInternalAssumingMaintenance("restoreCostBackup")
```

Do not re-enter maintenance mode inside an already-maintenance-scoped restore.

### 3. Fix legacy `exportDatabase()`

Pick one policy.

Recommended:

```text
Disable raw DB export in release.
In debug, route through the same maintenance/drain/snapshot code.
```

Implementation:

```kotlin
if (!BuildConfig.DEBUG) {
    return Result.failure(SecurityException("Raw DB export disabled in release"))
}

maintenanceOperationRunner.runExclusive(
    mode = BACKUP_EXPORTING,
    operationName = "exportDatabaseDebugRaw",
    drainTimeoutPolicy = FAIL_OPERATION
) {
    create verified snapshot
    optionally sanitize/encrypt
}
```

If keeping old behavior temporarily, rename it:

```kotlin
exportDatabaseUnsafeDebugOnly()
```

and require explicit typed confirmation.

### 4. Verify safety backup snapshot

After copying/creating safety backup:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
fresh Room open
```

If it fails:

```text
delete bad safety backup
return failure
```

## Acceptance tests

```text
standalone_createSafetyBackup_enters_BACKUP_EXPORTING
standalone_createSafetyBackup_drains_workers
standalone_createSafetyBackup_blocks_foreground_writes
standalone_createSafetyBackup_verifies_backup_file
reset_uses_internal_safety_backup_without_reentering_mode
restore_uses_internal_safety_backup_without_reentering_mode
raw_export_release_disabled
raw_export_debug_enters_BACKUP_EXPORTING_or_is_removed
raw_export_debug_verifies_snapshot
```

---

# PR 2 — True SQLite snapshot / verified drained fallback

## Issue fixed

```text
BARRIER-REM-02
```

## Goal

`.costbackup` should use a real SQLite-consistent snapshot where possible.

## Current problem

`.costbackup` still copies the DB file after checkpoint/drain. This is much safer now, but not as strong as SQLite backup API or `VACUUM INTO`.

## Files

```text
DatabaseBackupRepositoryImpl.kt
SqliteSnapshotCreator.kt
BackupVerifier.kt
tests
```

## Add abstraction

```kotlin
interface SqliteSnapshotCreator {
    suspend fun createSnapshot(
        sourceDbFile: File,
        targetSnapshotFile: File
    ): SnapshotCreationResult
}

sealed interface SnapshotCreationResult {
    data class Created(val method: SnapshotMethod) : SnapshotCreationResult
    data class Failed(val reason: String, val error: Throwable? = null) : SnapshotCreationResult
}

enum class SnapshotMethod {
    VACUUM_INTO,
    SQLITE_BACKUP_API,
    DRAINED_FILE_COPY
}
```

## Preferred implementation

Try:

```sql
VACUUM INTO ?
```

If supported:

```text
use VACUUM INTO snapshot.db
verify snapshot
bundle snapshot
```

## Fallback implementation

If unsupported:

```text
enter BACKUP_EXPORTING
drain workers
checkpoint WAL TRUNCATE
capture live table counts
copy DB file
verify copied DB
compare copied counts to live counts captured under drain
```

Important: if using fallback, document in manifest:

```json
"snapshotMethod": "DRAINED_FILE_COPY"
```

## Manifest fields

Add:

```json
{
  "snapshotMethod": "VACUUM_INTO",
  "snapshotCreatedAt": 123456789,
  "snapshotVerified": true,
  "liveCountsCaptured": true
}
```

## Acceptance tests

```text
backup_uses_vacuum_into_when_supported
backup_falls_back_to_drained_file_copy_when_vacuum_unsupported
fallback_snapshot_counts_match_live_counts
backup_fails_if_snapshot_count_mismatch
backup_manifest_records_snapshot_method
backup_snapshot_verification_failure_deletes_temp_file
backup_does_not_include_mid_export_insert
```

---

# PR 3 — Formal restore-internal write scope

## Issue fixed

```text
BARRIER-REM-03
```

## Goal

Restore-internal DB writes must be explicit, auditable, and impossible outside restore code.

## Current problem

Asset restore updates `scanned_receipts.imagePath` while mode is `ASSETS_RESTORING`. This is intentional but violates the simple “writes only in NORMAL” invariant unless formalized.

## Files

```text
RestoreInternalWriteScope.kt
DatabaseBackupRepositoryImpl.kt
ScannedReceiptDao.kt
verify_db_access_boundaries.py
db_access_allowlist.yml
tests
```

## Add scope

```kotlin
@Singleton
class RestoreInternalWriteScope @Inject internal constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    suspend fun <T> run(
        operation: String,
        block: suspend () -> T
    ): T {
        val mode = restoreMaintenanceMode.currentMode()
        require(
            mode == RestoreMaintenanceMode.Mode.ASSETS_RESTORING ||
            mode == RestoreMaintenanceMode.Mode.RESTORE_VERIFYING
        ) {
            "Restore-internal write $operation not allowed in $mode"
        }
        return block()
    }
}
```

Alternative stricter option:

```text
Allow only ASSETS_RESTORING.
```

## Usage

```kotlin
restoreInternalWriteScope.run("restoreReceiptAssets.updateImagePath") {
    restoredDb.scannedReceiptDao().update(...)
}
```

## Static guard integration

Allow non-NORMAL DB writes only if inside:

```text
RestoreInternalWriteScope.run(...)
```

and class is:

```text
DatabaseBackupRepositoryImpl
```

or an explicitly allowlisted restore helper.

## Longer-term better option

Update asset paths in staged DB before live swap, so no post-swap restore-internal writes are needed.

But scope is acceptable short-term.

## Acceptance tests

```text
asset_restore_db_update_runs_in_restore_internal_scope
restore_internal_scope_rejects_NORMAL_mode
restore_internal_scope_rejects_BACKUP_EXPORTING
normal_code_cannot_inject_or_call_restore_internal_scope_without_allowlist
static_guard_allows_asset_update_only_inside_restore_internal_scope
```

---

# PR 4 — Persist critical recovery reason

## Issue fixed

```text
BARRIER-REM-04
```

## Goal

If app enters `CRITICAL_RECOVERY_REQUIRED`, the reason must survive restart and be visible to UI/support.

## Files

```text
RestoreMaintenanceMode.kt
AppOperationalState.kt
MainViewModel.kt
AppOperationalLockScreen.kt
AppStartupCoordinator.kt
tests
```

## Add persisted fields

In restore-maintenance DataStore:

```text
criticalRecoveryReason: String?
criticalRecoveryTimestamp: Long?
criticalRecoveryOperation: String?
```

## API

```kotlin
suspend fun enterCriticalRecoveryRequired(
    reason: String,
    operation: String? = null
)
```

## State mapping

```kotlin
data class CriticalRecoveryRequired(
    val reason: String?,
    val timestamp: Long?,
    val operation: String?
) : AppOperationalState
```

## UI

Show:

```text
Critical recovery required.
Operation: restoreCostBackup
Reason: Restore verification failed and rollback also failed.
Timestamp: ...
```

Do not expose raw paths or sensitive exception strings.

## Acceptance tests

```text
critical_recovery_reason_persisted
critical_recovery_reason_survives_restart
critical_recovery_ui_shows_safe_reason
critical_recovery_reason_cleared_after_successful_manual_reset_or_recovery
```

---

# PR 5 — MaintenanceSafeDiagnosticSink API + sync durability

## Issue fixed

```text
BARRIER-REM-05
```

## Goal

Maintenance-safe diagnostics should be durable and queryable through the interface.

## Current problem

The DataStore implementation is fire-and-forget:

```kotlin
scope.launch { dataStore.edit { ... } }
```

Records can be lost on immediate process death. Also `observeRecent()` exists only on concrete class.

## Files

```text
MaintenanceSafeDiagnosticSink.kt
DataStoreMaintenanceSafeDiagnosticSink.kt
MaintenanceDiagnosticRecord.kt
WorkerExecutionGuard.kt
NotificationCaptureService.kt
EmailReceiptIngestionService.kt
tests
```

## Change interface

```kotlin
interface MaintenanceSafeDiagnosticSink {
    suspend fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String? = null,
        entity: String? = null,
        reason: MaintenanceBlockedReason = MaintenanceBlockedReason.BLOCKED
    )

    fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>>

    suspend fun clearOlderThan(cutoffMs: Long)
}
```

Reason enum:

```kotlin
enum class MaintenanceBlockedReason {
    WRITE_BARRIER_DENIED,
    READ_BARRIER_DENIED,
    RESTORE_IN_PROGRESS,
    RESTART_REQUIRED,
    BACKUP_EXPORTING,
    WORKER_STOP_REQUESTED,
    CRITICAL_RECOVERY,
    UNKNOWN
}
```

## Update callers

Worker guard should call:

```kotlin
maintenanceSafeDiagnosticSink.recordBlockedOperation(...)
```

Since this is now suspend, call from suspend contexts directly.

For non-suspend contexts, launch and accept best-effort only if no alternative.

## Acceptance tests

```text
recordBlockedOperation_returns_after_DataStore_commit
safe_sink_observeRecent_available_via_interface
safe_sink_bounds_to_200_records
safe_sink_records_specific_reason
restore_blocked_worker_visible_after_restart
```

---

# PR 6 — WorkerRunContext counters

## Issue fixed

```text
BARRIER-REM-06
```

## Goal

Worker run rows should tell what the worker actually did.

## Files

```text
WorkerExecutionGuard.kt
WorkerRunLogger.kt
BackgroundJobRun.kt
BillReminderWorker.kt
ReceiptMatchingWorker.kt
DataRetentionWorker.kt
LocationBackfillWorker.kt
MerchantKeyBackfillWorker.kt
WarrantyExpirationWorker.kt
tests
```

## Add context

```kotlin
class WorkerRunContext internal constructor(
    private val checkpointDelegate: suspend (String) -> Unit
) {
    var rowsScanned: Int = 0
        private set
    var rowsUpdated: Int = 0
        private set
    var notificationsSent: Int = 0
        private set
    var rowsSkipped: Int = 0
        private set
    var errors: Int = 0
        private set

    fun addRowsScanned(n: Int = 1) { rowsScanned += n }
    fun addRowsUpdated(n: Int = 1) { rowsUpdated += n }
    fun addRowsSkipped(n: Int = 1) { rowsSkipped += n }
    fun addNotificationsSent(n: Int = 1) { notificationsSent += n }
    fun addErrors(n: Int = 1) { errors += n }

    suspend fun checkpoint(label: String) = checkpointDelegate(label)
}
```

## Change guard signature

Compatibility phase:

```kotlin
suspend fun <T> runGuarded(
    request: WorkerGuardRequest,
    block: suspend () -> T
)
```

Add new overload:

```kotlin
suspend fun <T> runGuardedWithContext(
    request: WorkerGuardRequest,
    block: suspend (WorkerRunContext) -> T
)
```

Later migrate all workers and deprecate old one.

## Success logging

```kotlin
run.success(
    rowsScanned = ctx.rowsScanned,
    rowsUpdated = ctx.rowsUpdated,
    notificationsSent = ctx.notificationsSent,
    message = ...
)
```

## Acceptance tests

```text
bill_worker_records_notificationsSent
receipt_matching_records_autoMatched_and_suggested
data_retention_records_per_target_counts
location_backfill_records_scanned_updated_failed
merchant_key_backfill_records_updated_count
warranty_worker_records_notificationsSent
```

---

# PR 7 — Static guard v4 / Detekt path

## Issue fixed

```text
BARRIER-REM-07
```

## Goal

Reduce regex guard blind spots and broad allowlist risk.

## Files

```text
scripts/verify_db_access_boundaries.py
config/db_access_allowlist.yml
tests/test_verify_db_access_boundaries.py
optional detekt rule module
```

## Short-term script hardening

Add tests and logic for:

```text
multi-line local DAO assignment
DAO property with non-Dao variable name
barrier call after mutation must fail
higher-order wrapper suspicious patterns
database.someDao().update(...) direct chain
```

Examples to detect:

```kotlin
val writer =
    database.scannedReceiptDao()
writer.update(...)
```

```kotlin
database.scannedReceiptDao()
    .update(...)
```

```kotlin
expenseDao.update(...)
writeBarrier.checkWritesAllowed(...)
```

## Allowlist cleanup

Reduce broad:

```yaml
requires_write_barrier: false
```

Keep only:

```text
Room migrations
DatabaseBackupRepositoryImpl internal restore scope
canonical lifecycle coordinators with documented top-level guard
```

For each broad allowlist entry require:

```yaml
central_guard_method: "createExpenseDbOnly"
reason: "All mutations occur after central write barrier"
```

## Medium-term Detekt

Create custom Detekt rule:

```text
NoDirectDaoMutationRule
NoDbFileOperationRule
NoUnguardedWriteRule
```

Detekt can use Kotlin AST and avoid regex limitations.

## Acceptance tests

```text
guard_fails_on_multiline_local_dao_update
guard_fails_on_direct_chained_dao_update
guard_fails_when_barrier_after_mutation
guard_requires_central_guard_for_requires_write_barrier_false
guard_allows_restore_internal_scope
detekt_rule_flags_direct_dao_mutation_if_enabled
```

---

# PR 8 — Asset restore resumability and ledger

## Related issue

```text
BARRIER-REM-03 hardening
P7 asset restore resumability
```

## Goal

If app crashes during `ASSETS_RESTORING`, asset repair can resume or report exact incomplete tasks.

## Files

```text
RestoreJournal.kt
DatabaseBackupRepositoryImpl.kt
ReceiptAssetStore.kt
MaintenanceSafeDiagnosticSink.kt
```

## Add journal asset tasks

```kotlin
data class AssetRestoreTask(
    val receiptId: Long,
    val sourceRelativePath: String,
    val targetPathHash: String,
    val status: AssetRestoreStatus,
    val error: String?
)

enum class AssetRestoreStatus {
    PENDING,
    VALIDATED,
    COPIED_TEMP,
    DB_UPDATED,
    COMPLETED,
    FAILED
}
```

## Flow

```text
create tasks before copying
mark VALIDATED after receipt exists
copy temp
mark COPIED_TEMP
update DB
mark DB_UPDATED
rename final
mark COMPLETED
```

On startup:

```text
if journal has ASSETS_RESTORING with incomplete tasks:
  resume or enter restart-required with visible repair warning
```

## Acceptance tests

```text
crash_mid_asset_restore_resumes_pending_tasks
asset_task_failure_recorded_in_journal
completed_asset_tasks_not_reprocessed
orphan_temp_files_cleaned_on_resume
```

---

# PR 9 — Backup/restore docs and final golden tests

## Goal

Make guarantees explicit and prevent regression.

## Docs

Add/update:

```text
docs/backup-restore-barrier-contract.md
docs/worker-drain-contract.md
docs/db-write-ownership.md
docs/maintenance-diagnostics.md
```

Document:

```text
which modes allow writes
which reads are allowed in BACKUP_EXPORTING
restore-internal write exception
snapshot method policy
critical recovery policy
worker drain policy
```

## Golden tests

```text
backup_restore_barrier_contract_test
worker_drain_contract_test
static_dao_guard_contract_test
maintenance_diagnostics_contract_test
```

Acceptance:

```text
docs_match_current_modes
all_global_barrier_golden_tests_pass
```

---

# PR 10 — Tracker cleanup and close criteria

## Goal

Update master tracker accurately.

## Status recommendation after PRs above

```text
Global write/read/restore barrier: CLOSED
```

Only if all close criteria below pass.

## Close criteria

```text
1. Public createSafetyBackup is guarded or internal.
2. Legacy raw export is removed, debug-only unsafe, or guarded.
3. .costbackup snapshot uses VACUUM INTO/backup API or verified drained fallback with manifest.
4. Restore asset writes use formal internal scope.
5. Critical recovery reason persists.
6. Maintenance diagnostics are durable and observable via interface.
7. Core workers record meaningful counts.
8. Static guard catches local DAO vars, direct chains, expression-bodied functions, and barrier order.
9. Asset restore can resume or reports incomplete tasks.
10. Full barrier test suite passes.
```

---

# Final agent checklist

Before coding, run:

```bash
rg "createSafetyBackup" app/src/main/java
rg "exportDatabase" app/src/main/java
rg "copyTo\\(" app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
rg "VACUUM INTO|backup API|SQLiteDatabase" app/src/main/java
rg "ASSETS_RESTORING" app/src/main/java
rg "enterCriticalRecoveryRequired" app/src/main/java
rg "MaintenanceSafeDiagnosticSink" app/src/main/java
rg "runGuarded" app/src/main/java
rg "rowsScanned|rowsUpdated|notificationsSent" app/src/main/java
rg "requires_write_barrier: false" config/db_access_allowlist.yml
rg "scannedReceiptDao\\(\\)" app/src/main/java
rg "database\\..*Dao\\(\\).*\\." app/src/main/java
```

---

# Final definition of done

The remaining barrier work is done when:

```text
- No public DB backup/export method copies the live DB outside maintenance/drain.
- Backup snapshot is SQLite-consistent or explicitly verified under drained no-write fallback.
- Restore-internal writes are explicit and scoped.
- Critical recovery reason survives restart.
- Maintenance diagnostics are durable and queryable.
- Worker run records include real counts for core workers.
- Static guard catches the known bypass patterns.
- Asset restore is resumable or safely reports incomplete tasks.
- Tests cover every guarantee.
```