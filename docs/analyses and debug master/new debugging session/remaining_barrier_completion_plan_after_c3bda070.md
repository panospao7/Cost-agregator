# Remaining Global Barrier Completion Plan After `c3bda070`

Baseline: `c3bda070931e1c72f08ad1e97fa68c83fe4c34bd`

Goal:

```text
Close the remaining Global Write / Read / Restore Barrier issues so the epic can be considered production-safe.
```

Main remaining classes of issues:

```text
W1/W2/W3 — Worker guard edge cases and weak run logging
B1/B2/B3/B4 — Backup/restore snapshot, rollback, asset restore, critical recovery
S1 — Startup recovery verification incomplete
G1 — Static guard still has detection holes
R1 — Reminder dismiss/snooze lifecycle semantics weak
D1 — Maintenance-safe diagnostics not durable
C1 — Maintenance runner duplication
H1 — __pycache__ committed
```

---

# Priority order

Do in this order:

```text
PR 1  WorkerExecutionGuard edge cases
PR 2  Fresh Room for rollback + no singleton DB after file mutation
PR 3  Critical recovery state for rollback/unrecoverable failures
PR 4  Startup recovery FK/count verification
PR 5  Backup snapshot correctness
PR 6  Restore asset internal-write/ledger cleanup
PR 7  Static guard v3
PR 8  Durable maintenance-safe diagnostics
PR 9  Reminder dismiss/snooze transition semantics
PR 10 Worker run taxonomy/counters
PR 11 Consolidate MaintenanceOperationRunner
PR 12 Hygiene/tests/tracker cleanup
```

---

# PR 1 — WorkerExecutionGuard edge cases

## Issues fixed

```text
W1 — read-only workers allowed during BACKUP_EXPORTING still write BackgroundJobRun rows
W2 — lease leak if workerRunLogger.start() throws
partial W3 — cancellation/skip visibility
```

## Files

```text
WorkerExecutionGuard.kt
WorkerRunLogger.kt
WorkerLeaseRegistry.kt
MaintenanceSafeDiagnosticSink.kt
WorkerExecutionGuardTest.kt
```

## Problem

Current flow can do this during `BACKUP_EXPORTING`:

```text
allowed read-only worker
-> workerRunLogger.start()
-> inserts BackgroundJobRun
```

That is a Room write during backup export.

Also:

```kotlin
val lease = leaseRegistry.acquire(...)
val run = workerRunLogger.start(...)
try { ... } finally { lease.close() }
```

If `start()` throws, lease is leaked.

## Implementation

### 1. Split execution paths

Pseudo-code:

```kotlin
val mode = restoreMaintenanceMode.currentMode()

val allowedReadOnlyDuringBackup =
    mode == BACKUP_EXPORTING &&
    request.allowDuringBackupExport &&
    !request.requiresDatabaseWrite

if (mode != NORMAL && !allowedReadOnlyDuringBackup) {
    maintenanceSafeDiagnosticSink.recordBlockedOperation(...)
    return Result.success()
}

val lease = leaseRegistry.acquire(request.workerName)
try {
    if (allowedReadOnlyDuringBackup) {
        // No Room logging here.
        maintenanceSafeDiagnosticSink.recordWorkerStartedReadOnly(...)
        val result = block()
        maintenanceSafeDiagnosticSink.recordWorkerCompletedReadOnly(...)
        return result
    }

    val run = workerRunLogger.start(request.workerName)
    try {
        val result = block()
        run.success(...)
        return result
    } catch (e: CancellationException) {
        run.cancelled("coroutine_cancelled_or_maintenance_stop")
        throw e
    } catch (t: Throwable) {
        ...
    }
} finally {
    lease.close()
}
```

### 2. Add test fake where `workerRunLogger.start()` throws

Assert:

```text
lease count returns to zero
drain does not hang
```

### 3. Add read-only backup-export test

Assert:

```text
no BackgroundJobRun insert
safe sink receives start/completed records
worker executes only if requiresDatabaseWrite=false
```

## Acceptance tests

```text
read_only_worker_allowed_during_backup_export_does_not_insert_BackgroundJobRun
write_worker_during_backup_export_is_skipped
workerRunLogger_start_failure_releases_worker_lease
cancelled_worker_finalizes_CANCELLED
lease_released_on_block_exception
```

---

# PR 2 — Fresh Room for rollback paths

## Issues fixed

```text
B2 — restoreFromSafetyBackup still uses injected singleton Room
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
RestoreDatabaseOpener.kt
BackupVerifier.kt
DatabaseBackupRepositoryImplTest.kt
```

## Problem

Rollback path still touches:

```kotlin
database.openHelper.writableDatabase
refreshInvalidationTrackerSafelyForVerification(database)
```

after DB file copy/rollback.

## Implementation

### 1. Change rollback helper

```kotlin
private suspend fun restoreFromSafetyBackup(...): Result<Unit> {
    copy safety files

    val freshDb = restoreDatabaseOpener.openFreshDatabase()
    try {
        freshDb.openHelper.writableDatabase
        refreshInvalidationTrackerSafelyForVerification(freshDb)
    } finally {
        freshDb.close()
    }

    return Result.success(Unit)
}
```

### 2. Remove injected DB usage after any file copy

Add comment and guard:

```text
After DB file mutation, injected AppDatabase is invalid until process restart.
Use RestoreDatabaseOpener only.
```

### 3. Make static guard detect singleton use after swap if possible

Add pattern for:

```text
restoreFromSafetyBackup.*database.openHelper
```

## Acceptance tests

```text
restore_rollback_verification_uses_fresh_room
legacy_import_rollback_verification_uses_fresh_room
restore_rollback_does_not_touch_injected_AppDatabase
fresh_room_closed_after_rollback_verification
```

---

# PR 3 — Critical recovery state

## Issues fixed

```text
B3 — rollback failure enters restart-required, not critical recovery
```

## Files

```text
RestoreMaintenanceMode.kt
AppOperationalState.kt
AppStartupCoordinator.kt
DatabaseBackupRepositoryImpl.kt
AppOperationalLockScreen.kt
```

## Add API

```kotlin
suspend fun enterCriticalRecoveryRequired(reason: String)
```

Persist:

```text
mode = CRITICAL_RECOVERY_REQUIRED
criticalRecoveryReason
criticalRecoveryTimestamp
```

## Use for

```text
restore rollback failure
legacy import rollback failure
startup recovery verification failure
reset failure after destructive mutation
fresh Room verification impossible after swap
```

## UI behavior

`CRITICAL_RECOVERY_REQUIRED` should show full-screen blocking UI:

```text
Critical restore recovery required.
Do not continue using app.
Restart. If persists, contact support / export diagnostics.
```

No dismiss.

## Acceptance tests

```text
restore_rollback_failure_enters_CRITICAL_RECOVERY_REQUIRED
legacy_import_rollback_failure_enters_CRITICAL_RECOVERY_REQUIRED
startup_recovery_verification_failure_enters_CRITICAL_RECOVERY_REQUIRED
critical_recovery_blocks_app_shell
critical_recovery_does_not_schedule_workers
critical_recovery_reason_persisted
```

---

# PR 4 — Startup recovery verification hardening

## Issues fixed

```text
S1 — startup recovery lacks foreign_key_check and expected-count verification
```

## Files

```text
AppStartupCoordinator.kt
BackupVerifier.kt
RestoreJournal.kt
RestoreDatabaseOpener.kt
```

## Implementation

After safety backup copy:

```text
1. PRAGMA integrity_check
2. PRAGMA foreign_key_check
3. fresh Room open
4. if journal/manifest expected counts exist, BackupVerifier.verify(...)
```

## Pseudo-code

```kotlin
val result = verifySafetyRestoredDb(liveDbFile, journalEntry)
if (!result.passed) {
    restoreJournal.failJournal(...)
    restoreMaintenanceMode.enterCriticalRecoveryRequired(result.errorSummary)
    return
}
restoreJournal.deleteJournal(...)
restoreMaintenanceMode.reset()
```

## Ensure resource cleanup

Raw SQLite handles must close in `finally`.

## Acceptance tests

```text
startup_recovery_integrity_failure_enters_critical
startup_recovery_foreign_key_failure_enters_critical
startup_recovery_room_open_failure_enters_critical
startup_recovery_expected_count_mismatch_enters_critical
startup_recovery_success_deletes_journal_and_resets_normal
sqlite_handle_closed_when_verification_throws
```

---

# PR 5 — Backup snapshot correctness

## Issues fixed

```text
B1 — backup export still copies live DB file
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
BackupVerifier.kt
MaintenanceOperationRunner.kt
```

## Preferred implementation

Use SQLite snapshot mechanism:

```sql
VACUUM INTO '/tmp/snapshot.db'
```

Flow:

```text
enter BACKUP_EXPORTING
drain workers
block writes
VACUUM INTO snapshot
verify snapshot
bundle snapshot
exit NORMAL
```

## Fallback implementation

If `VACUUM INTO` unsupported:

```text
enter BACKUP_EXPORTING
drain workers and fail on timeout
checkpoint WAL TRUNCATE
copy db
verify copied db
bundle copied db
exit NORMAL
```

Must clearly document:

```text
drained file-copy snapshot, not online backup API
```

## Acceptance tests

```text
backup_export_fails_if_worker_drain_timeout
backup_snapshot_verification_failure_deletes_temp_file
backup_does_not_include_mid_export_insert
backup_row_counts_match_manifest
backup_exits_maintenance_on_failure
```

---

# PR 6 — Restore asset internal-write and cleanup

## Issues fixed

```text
B4 — asset path DB updates happen during restore without formal policy
asset copy can orphan files
default freshDb parameter risk
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
ScannedReceiptDao.kt
RestoreJournal.kt
ReceiptAssetStore.kt
```

## Implementation

### 1. Remove default DB parameter

Bad:

```kotlin
restoreReceiptAssets(..., db: AppDatabase = database)
```

Good:

```kotlin
restoreReceiptAssets(..., restoredDb: AppDatabase)
```

### 2. Validate before copy

Order:

```text
parse receipt ID
verify receipt row exists in fresh restored DB
copy asset to temp file
update DB imagePath using restore-internal write scope
rename temp to final
record success
```

### 3. Formalize restore-internal write

Add:

```kotlin
class RestoreInternalWriteScope
```

or a private method in backup repository:

```kotlin
private suspend fun runRestoreInternalDbWrite(operation, block)
```

This documents why a DB write is allowed during `ASSETS_RESTORING`.

### 4. Cleanup on failure

```text
delete temp file
delete final file if DB update failed
record asset warning
```

## Acceptance tests

```text
restore_asset_update_requires_explicit_fresh_db
invalid_asset_filename_leaves_no_orphan_file
missing_receipt_row_leaves_no_orphan_file
db_update_failure_deletes_copied_asset
asset_restore_success_updates_imagePath
```

---

# PR 7 — Static DAO/access guard v3

## Issues fixed

```text
G1 — guard misses local dao.update, expression-bodied funcs, barrier order
H1 — __pycache__ committed
```

## Files

```text
scripts/verify_db_access_boundaries.py
config/db_access_allowlist.yml
tests/test_verify_db_access_boundaries.py
.gitignore
```

## Tasks

### 1. Detect local DAO vars

Detect:

```kotlin
val dao = database.scannedReceiptDao()
dao.update(...)
```

Track local assignments ending in `Dao()`.

### 2. Detect expression-bodied functions

Support:

```kotlin
suspend fun foo() = expenseDao.update(...)
```

### 3. Require barrier before mutation

For methods requiring barrier:

```text
writeBarrier.checkWritesAllowed must appear before mutation line
```

Not just somewhere in the method.

### 4. Reduce `requires_write_barrier: false`

Only allow for:

```text
canonical lifecycle coordinators with documented entrypoint guard
Room migrations
backup/restore internal scoped writes
```

### 5. Remove `__pycache__`

```bash
git rm -r scripts/__pycache__
```

`.gitignore`:

```gitignore
__pycache__/
*.pyc
```

## Acceptance tests

```text
guard_fails_on_local_lowercase_dao_update
guard_fails_on_expression_body_unguarded_update
guard_fails_when_barrier_after_mutation
guard_allows_barrier_before_mutation
guard_fails_on_db_file_copy_outside_backup_repo
guard_fails_on_execSQL_outside_migration
```

---

# PR 8 — Durable maintenance-safe diagnostics

## Issues fixed

```text
D1 — MaintenanceSafeDiagnosticSink is Timber-only
```

## Files

```text
MaintenanceSafeDiagnosticSink.kt
MaintenanceDiagnosticStore.kt
DataStore module
WorkerExecutionGuard.kt
NotificationCaptureService.kt
EmailReceiptIngestionService.kt
ExportCoordinator/Repository
```

## Add model

```kotlin
data class MaintenanceDiagnosticRecord(
    val id: String,
    val operation: String,
    val mode: String,
    val pipeline: String?,
    val entity: String?,
    val reason: String,
    val timestamp: Long
)
```

## Storage

Use DataStore ring buffer:

```text
max 200 records
drop oldest
survives process restart
```

## API

```kotlin
interface MaintenanceSafeDiagnosticSink {
    fun recordBlockedOperation(...)
    fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>>
    suspend fun clearOlderThan(cutoffMs: Long)
}
```

## Acceptance tests

```text
blocked_operation_recorded_in_datastore_sink
safe_sink_survives_process_restart
safe_sink_bounds_to_max_records
restore_blocked_worker_visible_in_safe_sink
restore_blocked_notification_visible_in_safe_sink
```

---

# PR 9 — Reminder dismiss/snooze semantics

## Issues fixed

```text
R1 — barrier-safe but lifecycle semantics weak
```

## Files

```text
RecurringLifecycleCoordinator.kt
RecurringReminderDeliveryDao.kt
RecurringLifecycleEventDao.kt
DismissReminderReceiver.kt
SnoozeReminderReceiver.kt
```

## Add result model

```kotlin
sealed interface ReminderActionResult {
    data object Updated : ReminderActionResult
    data class NoOp(val reason: String) : ReminderActionResult
    data object NotFound : ReminderActionResult
    data class Failed(val reason: String) : ReminderActionResult
}
```

## Allowed transitions

Dismiss:

```text
SCHEDULED/SNOOZED/CLAIMED/FAILED_TRANSIENT -> DISMISSED
SENT/CANCELLED/DISMISSED/FAILED_FINAL -> NoOp
```

Snooze:

```text
SCHEDULED/SNOOZED -> SNOOZED
CLAIMED -> NoOp or cancel-claim-then-snooze, explicitly decide
SENT/CANCELLED/DISMISSED/FAILED_FINAL -> NoOp
```

## Atomicity

Event insert should be in same transaction.

Do not silently swallow event failure unless a diagnostic is written.

## Acceptance tests

```text
dismiss_scheduled_delivery_updates_and_events
dismiss_sent_delivery_noops
snooze_scheduled_delivery_updates_and_events
snooze_sent_delivery_noops
missing_delivery_returns_NotFound
event_failure_rolls_back_or_records_diagnostic
```

---

# PR 10 — Worker run taxonomy and counters

## Issues fixed

```text
W3 — statuses weak, counts zero
```

## Files

```text
BackgroundJobRun.kt
BackgroundJobRunDao.kt
WorkerRunLogger.kt
WorkerExecutionGuard.kt
all workers gradually
```

## Schema additions

```kotlin
val statusReason: String?
val metadataJson: String?
```

Optional:

```kotlin
val rowsScanned: Int
val rowsUpdated: Int
val notificationsSent: Int
```

already may exist; ensure used.

## Add context

```kotlin
class WorkerRunContext {
    fun addRowsScanned(n: Int)
    fun addRowsUpdated(n: Int)
    fun addNotificationsSent(n: Int)
    suspend fun skip(reason: WorkerSkipReason): Nothing
}
```

## Acceptance tests

```text
location_no_work_logs_SKIPPED_with_NO_WORK_reason
bill_worker_records_notificationsSent
receipt_matching_records_autoMatched_suggested
data_retention_records_per_target_counts
cancelled_worker_has_status_CANCELLED
```

---

# PR 11 — Consolidate maintenance runner

## Issues fixed

```text
C1 — MaintenanceOperationRunner duplicated/unused
```

## Files

```text
MaintenanceOperationRunner.kt
DatabaseBackupRepositoryImpl.kt
RestoreMaintenanceMode.kt
```

## Decide

Option A:

```text
Use MaintenanceOperationRunner everywhere and delete local enterMaintenanceAndDrain helper.
```

Option B:

```text
Delete MaintenanceOperationRunner and keep one local/orchestrator helper.
```

Recommended: Option A.

## Acceptance tests

```text
all_destructive_db_operations_use_MaintenanceOperationRunner
no_duplicate_enterMaintenanceAndDrain_helper
runner_fails_restore_on_drain_timeout
runner_resets_stop_flag_on_normal_exit
```

---

# PR 12 — Final test/tracker cleanup

## Tasks

1. Run full suite:

```bash
./gradlew check
./gradlew testDebugUnitTest
./gradlew verifyDbAccessBoundaries
```

2. Add/update tracker statuses:

```text
Global barrier: mostly closed
Remaining caveats: backup true snapshot if VACUUM INTO not implemented
```

3. Update docs:

```text
docs/DB_WRITE_OWNERSHIP.md
docs/backup-restore-barrier-contract.md
docs/worker-drain-contract.md
```

4. Remove stale comments that say old unsafe behavior remains if fixed.

## Final acceptance matrix

```text
restore waits/drains workers before swap
reset waits/drains workers before delete
backup snapshot verified
fresh Room used after swap and rollback
startup recovery verifies integrity+FK+Room
rollback failure critical
workers cancel cleanly
read-only backup worker does not write Room
static guard catches local dao/expression/body/order
maintenance diagnostics durable
receivers coordinator-based and transition-safe
```