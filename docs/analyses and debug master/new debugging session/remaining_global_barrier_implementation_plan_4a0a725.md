# Remaining Global Write / Read / Restore Barrier Implementation Plan

Baseline commit: `4a0a72543a957121bceed2519428758102d53700`

Scope: finish the remaining issues from the global write/read/restore barrier epic.

Current status after latest fixes:

```text
Good progress:
- Known ExpenseRepository write gaps fixed.
- Known BudgetRepository write gaps fixed.
- Budget debug restore guarded.
- RestoreInProgress app-shell lock added.
- Worker stop flag reset on normal exit.
- Worker checkpoint now observes stop request.
- Legacy rollback failure no longer exits directly to NORMAL.
- BankStatementLifecycleProcessor re-checks before final write phase.

Still open:
- Worker drain not wired into backup/restore/reset.
- MaintenanceOperationRunner unused.
- Restore still uses stale singleton Room after DB swap.
- Backup snapshot still copies live DB.
- Worker run logging/cancellation incomplete.
- Static DAO guard weak.
- Reminder receivers still direct-write.
- Maintenance-safe diagnostics not durable.
- Startup recovery does not verify safety-restored DB.
```

---

# 0. Priority overview

## P0 / P1 — must fix before calling barrier epic closed

1. **Wire worker drain into backup/restore/reset.**
2. **Use fresh Room after DB file swap.**
3. **Verify startup safety recovery before returning to NORMAL.**
4. **Make rollback failure enter critical/restart-required blocked state.**
5. **Fix worker cancellation/run logging.**
6. **Stop backup export from copying live DB without proven no-write/snapshot guarantee.**
7. **Upgrade static DAO guard to method-level + barrier-aware.**

## P2 — important hardening

8. **Move reminder receivers to coordinator + goAsync/WorkManager.**
9. **Make maintenance-safe diagnostics durable.**
10. **Add ExportCoordinator / central read-barrier entrypoint.**
11. **Improve app operational state for BACKUP_EXPORTING.**
12. **Add verification tests for all fixed and remaining gaps.**

---

# PR 1 — Wire `MaintenanceOperationRunner` and worker drain into destructive operations

## Goal

Backup, restore, legacy import, and reset must not race running workers.

Current problem:

```text
MaintenanceOperationRunner exists but DatabaseBackupRepositoryImpl does not use it.
RestoreMaintenanceMode.enter() cancels WorkManager asynchronously but does not wait for active workers.
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
MaintenanceOperationRunner.kt
WorkerDrainController.kt
WorkerLeaseRegistry.kt
WorkerLeaseRegistryImpl.kt
RestoreMaintenanceMode.kt
```

## Tasks

### 1. Inject runner into `DatabaseBackupRepositoryImpl`

```kotlin
class DatabaseBackupRepositoryImpl @Inject constructor(
    ...
    private val maintenanceOperationRunner: MaintenanceOperationRunner,
    ...
)
```

### 2. Add flexible maintenance session API

The current `runExclusive()` is too simple for multi-stage restore. Add:

```kotlin
interface MaintenanceSession {
    suspend fun transitionTo(mode: RestoreMaintenanceMode.Mode)
    suspend fun markRestartRequired()
    suspend fun markCritical(reason: String)
}

suspend fun <T> runExclusiveSession(
    initialMode: RestoreMaintenanceMode.Mode,
    operationName: String,
    drainTimeoutPolicy: DrainTimeoutPolicy,
    block: suspend MaintenanceSession.() -> T
): T
```

```kotlin
enum class DrainTimeoutPolicy {
    FAIL_OPERATION,
    PROCEED_WITH_WARNING
}
```

Policy:

```text
restoreCostBackup -> FAIL_OPERATION
importDatabase -> FAIL_OPERATION
resetDatabase -> FAIL_OPERATION
createCostBackup -> FAIL_OPERATION unless true SQLite snapshot is implemented
```

### 3. Use runner in operations

#### `createCostBackup`

```text
runExclusiveSession(BACKUP_EXPORTING, "createCostBackup", FAIL_OPERATION) {
    create snapshot
    verify snapshot
    bundle/encrypt
}
```

#### `restoreCostBackup`

```text
runExclusiveSession(RESTORE_PREPARING, "restoreCostBackup", FAIL_OPERATION) {
    transitionTo(RESTORE_STAGING)
    stage DB
    transitionTo(RESTORE_VERIFYING)
    verify staged DB
    transitionTo(RESTORE_SWAPPING)
    swap DB
    transitionTo(RESTORE_VERIFYING)
    verify live DB with fresh Room
    transitionTo(ASSETS_RESTORING)
    restore assets
    markRestartRequired()
}
```

#### `importDatabase`

Same outer session, debug-only.

#### `resetDatabase`

```text
runExclusiveSession(RESETTING_DATABASE, "resetDatabase", FAIL_OPERATION) {
    create safety backup
    close DB
    delete files
    verify empty/new DB if possible
    markRestartRequired()
}
```

### 4. Stop proceeding on drain timeout

Current runner logs and proceeds. Change:

```kotlin
if (!drained && policy == FAIL_OPERATION) {
    throw WorkerDrainTimeoutException(operationName)
}
```

### 5. Reset stop flag via interface

Add to interface:

```kotlin
interface WorkerLeaseRegistry {
    suspend fun acquire(workerName: String): WorkerLease
    suspend fun requestStopAll(reason: String)
    suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean
    fun isStopRequested(): Boolean
    fun resetStopFlag()
}
```

Remove implementation casts from `RestoreMaintenanceMode`.

## Acceptance tests

```text
restore_waits_for_running_worker_to_stop_before_swap
restore_drain_timeout_fails_before_file_swap
reset_waits_for_running_worker_before_delete
reset_drain_timeout_does_not_delete_db
backup_export_waits_for_workers_or_fails
worker_stop_flag_reset_after_successful_normal_exit
```

---

# PR 2 — Fresh Room after DB file swap

## Goal

No injected singleton `AppDatabase` or DAO is used after live DB file replacement.

Current problem:

```text
restoreCostBackup still verifies live DB and updates receipt asset paths using injected database after file swap.
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
AppDatabase.kt
new RestoreDatabaseOpener.kt
Hilt module for opener
BackupVerifier.kt
```

## Add

```kotlin
interface RestoreDatabaseOpener {
    fun openFreshDatabase(): AppDatabase
}
```

Implementation:

```kotlin
@Singleton
class RestoreDatabaseOpenerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : RestoreDatabaseOpener {
    override fun openFreshDatabase(): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()
    }
}
```

Use the exact same migrations/config as production DB.

## Replace after swap

Forbidden after swap:

```kotlin
database.openHelper.writableDatabase
database.scannedReceiptDao()
queryRoomCountsForVerification(database)
```

Required:

```kotlin
val freshDb = restoreDatabaseOpener.openFreshDatabase()
try {
    freshDb.openHelper.writableDatabase
    val liveSummary = queryRoomCountsForVerification(freshDb)
    restoreReceiptAssets(..., freshDb)
} finally {
    freshDb.close()
}
```

## Legacy import

Change `liveImportVerifier(database, ...)` to use fresh DB opener.

## Acceptance tests

```text
restore_live_verification_uses_fresh_room
restore_asset_path_updates_use_fresh_room
legacy_import_live_verifier_uses_fresh_room
injected_app_database_not_used_after_swap
fresh_room_closed_after_verification
```

---

# PR 3 — Startup recovery verification

## Goal

Startup safety-backup recovery must verify the restored DB before returning app to NORMAL.

Current problem:

```text
AppStartupCoordinator copies safety backup and assumes success if copy succeeds.
```

## Files

```text
AppStartupCoordinator.kt
BackupVerifier.kt
RestoreJournal.kt
RestoreMaintenanceMode.kt
```

## Tasks

After safety backup copy:

```kotlin
val verification = backupVerifier.verifyBasicLiveDb(liveDbFile)
if (!verification.passed) {
    restoreJournal.failJournal(...)
    restoreMaintenanceMode.enter(CRITICAL_RECOVERY_REQUIRED)
    return
}
```

Minimum verification:

```text
PRAGMA integrity_check == ok
PRAGMA foreign_key_check returns no rows
Room can open with current schema/migrations
```

Better verification if journal has manifest/counts:

```text
BackupVerifier.verify(liveDbFile, expectedCounts)
```

## Add result model

```kotlin
data class StartupRecoveryVerificationResult(
    val passed: Boolean,
    val integrityOk: Boolean,
    val foreignKeyOk: Boolean,
    val roomOpenOk: Boolean,
    val errorMessage: String?
)
```

## Acceptance tests

```text
startup_safety_copy_success_and_integrity_ok_resets_normal
startup_safety_copy_success_but_integrity_failure_enters_critical
startup_safety_copy_success_but_room_open_failure_enters_critical
startup_safety_copy_failure_enters_restart_required_or_critical
journal_not_deleted_until_recovery_verified
```

---

# PR 4 — Critical recovery state for rollback failure

## Goal

Unknown DB state must not be treated as ordinary restart-required success.

Current state after latest fix:

```text
rollback failure exits with forceRestartRequired = true
```

Better:

```text
rollback failure -> CRITICAL_RECOVERY_REQUIRED
```

## Files

```text
RestoreMaintenanceMode.kt
AppOperationalState.kt
AppStartupCoordinator.kt
DatabaseBackupRepositoryImpl.kt
AppOperationalLockScreen.kt
```

## Tasks

### 1. Add API

```kotlin
suspend fun enterCriticalRecoveryRequired(reason: String)
```

Store reason in DataStore:

```text
criticalRecoveryReason
criticalRecoveryTimestamp
```

### 2. Map mode

```kotlin
Mode.CRITICAL_RECOVERY_REQUIRED -> AppOperationalState.CriticalRecoveryRequired(reason)
```

### 3. Use it

Cases:

```text
restore rollback failure
legacy import rollback failure
startup safety recovery verification failure
reset safety backup failure after destructive mutation
fresh Room verification impossible after swap
```

### 4. UI

Critical recovery screen should show:

```text
Do not continue using app.
Restart app.
If persists, export diagnostic log / contact support.
```

No normal navigation.

## Acceptance tests

```text
legacy_import_rollback_failure_enters_CRITICAL_RECOVERY_REQUIRED
restore_rollback_failure_enters_CRITICAL_RECOVERY_REQUIRED
critical_recovery_blocks_app_shell
critical_recovery_does_not_schedule_workers
critical_recovery_reason_persisted
```

---

# PR 5 — Worker run logging, cancellation, and backup-export semantics

## Goal

Every worker attempt has a terminal status; blocked/cancelled workers are visible.

## Files

```text
WorkerExecutionGuard.kt
WorkerRunLogger.kt
BackgroundJobRun.kt
BackgroundJobRunDao.kt
MaintenanceSafeDiagnosticSink.kt
```

## Tasks

### 1. Add request field

```kotlin
data class WorkerGuardRequest(
    val workerName: String,
    val requiresDatabaseWrite: Boolean = true,
    val allowDuringBackupExport: Boolean = false,
    ...
)
```

### 2. Fix barrier order

Pseudo-flow:

```kotlin
val mode = restoreMaintenanceMode.currentMode()

if (mode != NORMAL) {
    if (mode == BACKUP_EXPORTING && request.allowDuringBackupExport && !request.requiresDatabaseWrite) {
        readBarrier.checkReadAllowed(... EXPORT_OR_BACKUP_SNAPSHOT_READ)
    } else {
        safeDiagnosticSink.recordBlockedOperation(...)
        return Result.success()
    }
}

val lease = leaseRegistry.acquire(workerName)
val run = workerRunLogger.start(workerName)
```

### 3. Finalize cancellation

```kotlin
catch (e: CancellationException) {
    run.cancelled("coroutine_cancelled_or_maintenance_stop")
    throw e
}
```

### 4. Typed skip reasons

Add:

```text
SKIPPED_RESTORE_BLOCKED
SKIPPED_BACKUP_EXPORTING
SKIPPED_SPEC_DISABLED
SKIPPED_PRIVACY_DENIED
SKIPPED_PERMISSION_DENIED
SKIPPED_NO_WORK
```

Prefer:

```text
status = SKIPPED
statusReason = RESTORE_BLOCKED
```

if schema already supports it.

### 5. Stale RUNNING recovery

If not already implemented:

```kotlin
BackgroundJobRecoveryService.markStaleRuns()
```

Called from startup.

## Acceptance tests

```text
cancelled_worker_updates_run_CANCELLED
restore_blocked_worker_records_safe_skip
backup_exporting_write_worker_skipped
backup_exporting_readonly_allowed_worker_runs
worker_stop_requested_checkpoint_marks_cancelled
startup_marks_old_RUNNING_as_STALE_ABORTED
```

---

# PR 6 — Backup export true snapshot

## Goal

`.costbackup` export must capture a consistent DB snapshot.

Current problem:

```text
backup export enters BACKUP_EXPORTING and copies the live DB file after checkpoint.
```

This is not enough if any write races the copy.

## Files

```text
DatabaseBackupRepositoryImpl.kt
BackupVerifier.kt
MaintenanceOperationRunner.kt
```

## Preferred option — `VACUUM INTO`

If Android SQLite version supports it:

```sql
VACUUM INTO '/path/to/snapshot.db'
```

Flow:

```text
enter BACKUP_EXPORTING
drain workers
open DB
VACUUM INTO temp snapshot
verify snapshot
bundle snapshot
exit NORMAL
```

## Alternative — SQLite backup API

Use a safe backup API if available.

## Minimum acceptable fallback

```text
enter BACKUP_EXPORTING
drain workers and fail on timeout
block foreground writes
checkpoint WAL TRUNCATE
copy DB
copy/ignore WAL/SHM only according to verified SQLite mode
verify copied DB
exit NORMAL
```

## Acceptance tests

```text
backup_snapshot_consistent_under_concurrent_write_attempt
backup_export_fails_if_worker_drain_timeout
backup_snapshot_verification_failure_deletes_temp_file
backup_does_not_include_mid_export_insert
backup_row_counts_match_manifest
```

---

# PR 7 — Strengthen static DAO/access guard

## Goal

CI should catch new unguarded DAO writes, not just unallowlisted classes.

## Files

```text
scripts/verify_db_access_boundaries.py
config/db_access_allowlist.yml
tests/test_verify_db_access_boundaries.py
Gradle task
```

## Current weakness

```text
class-level allowlist
ignores daos
ignores methods_only
ignores debug_only
does not require writeBarrier
does not scan raw file DB operations
```

## Tasks

### 1. Parse YAML fully

Use:

```text
class
daos
methods_only
debug_only
requires_write_barrier
reason
allowed_until
```

### 2. Method-level matching

Detect current class and function name.

Violation unless:

```text
class matches
DAO variable matches
method matches allowed method
```

### 3. Require barrier in same method

For repository/worker/debug methods:

```text
method body must contain writeBarrier.checkWritesAllowed
or writeBarrier.runWrite
```

Lifecycle coordinators may have class-level central guard if documented.

### 4. Enforce debug-only

If allowlist entry says:

```yaml
debug_only: true
```

then method must contain:

```text
BuildConfig.DEBUG
```

or be compiled only in debug source set.

### 5. Add file-operation guard

Flag outside backup/restore approved classes:

```text
getDatabasePath
deleteRecursively
delete()
renameTo
copyTo
openDatabase
writableDatabase after swap
execSQL outside migrations
```

## Acceptance tests

```text
guard_fails_on_unguarded_budgetDao_update_inside_BudgetRepository
guard_allows_budgetDao_update_with_writeBarrier_in_allowed_method
guard_fails_on_debug_method_without_BuildConfig_DEBUG
guard_fails_on_unlisted_DAO_variable
guard_fails_on_db_file_copy_outside_backup_repo
guard_fails_on_execSQL_outside_migration
```

---

# PR 8 — Reminder receiver cleanup

## Goal

Broadcast receivers must not directly write DAOs and must not block with `runBlocking`.

## Files

```text
DismissReminderReceiver.kt
SnoozeReminderReceiver.kt
RecurringLifecycleCoordinator.kt
RecurringReminderDeliveryDao.kt
RecurringLifecycleEventDao.kt
```

## Add coordinator methods

```kotlin
suspend fun dismissReminderDelivery(
    deliveryId: Long,
    source: String,
    correlationId: String? = null
): ReminderActionResult

suspend fun snoozeReminderDelivery(
    deliveryId: Long,
    snoozeUntil: Long,
    source: String,
    correlationId: String? = null
): ReminderActionResult
```

Inside coordinator:

```text
writeBarrier.checkWritesAllowed immediately before transaction
database.withTransaction {
  re-read delivery
  validate status
  update delivery
  insert lifecycle event
}
```

## Receiver flow

Use `goAsync()`:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()
    appScope.launch {
        try {
            coordinator.dismissReminderDelivery(...)
        } finally {
            pendingResult.finish()
        }
    }
}
```

or enqueue a one-shot worker.

## Acceptance tests

```text
dismiss_receiver_does_not_use_runBlocking
snooze_receiver_does_not_use_runBlocking
dismiss_receiver_delegates_to_coordinator
snooze_receiver_delegates_to_coordinator
restore_started_after_onReceive_blocks_actual_write
dismiss_nonexistent_delivery_writes_no_success_event
```

---

# PR 9 — Durable maintenance-safe diagnostics

## Goal

Blocked operations during restore/restart-required should be visible after restart.

Current sink is Timber-only.

## Files

```text
MaintenanceSafeDiagnosticSink.kt
new MaintenanceDiagnosticStore.kt
DataStore-backed ring buffer or file-backed JSONL
BudgetMonitor.kt
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

## Storage options

Recommended:

```text
DataStore ring buffer, max 200 records
```

Alternative:

```text
JSONL file in no-backup dir
```

## Sink API

```kotlin
interface MaintenanceSafeDiagnosticSink {
    fun recordBlockedOperation(...)
    fun recent(): Flow<List<MaintenanceDiagnosticRecord>>
    suspend fun clearOlderThan(cutoff: Long)
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

# PR 10 — ExportCoordinator / read-barrier centralization

## Goal

Export reads should have one policy boundary.

## Files

```text
new ExportCoordinator.kt
ExportDataRepository.kt
AccountingExportRepository.kt
ExportOptionsViewModel.kt
DatabaseReadBarrier.kt
PrivacyGate.kt
```

## Tasks

### 1. Add coordinator

```kotlin
class ExportCoordinator @Inject constructor(
    private val readBarrier: DatabaseReadBarrier,
    private val privacyGate: PrivacyGate,
    private val exportDataRepository: ExportDataRepository,
    ...
) {
    suspend fun generateExport(request: ExportRequest): ExportResult
}
```

### 2. Move checks from ViewModel

Coordinator owns:

```text
readBarrier.checkReadAllowed
privacyGate.check
snapshot creation
accounting validation
file generation
```

ViewModel only calls coordinator.

### 3. Repository methods become internal-ish

Keep repository guards too for defense in depth, but coordinator is canonical.

## Acceptance tests

```text
restore_blocks_export_at_coordinator
restart_required_blocks_export_at_coordinator
export_privacy_denied_blocks_before_query
viewmodel_does_not_call_repository_directly
```

---

# PR 11 — Long-write-section barrier hardening

## Goal

A barrier check before a long computation is not enough.

## Applies to

```text
BankStatementLifecycleProcessor
Receipt processing
Email receipt processing
Import rows/chunks
Batch receipt processing
Workers with loops
```

## Pattern

Before every DB mutation block:

```kotlin
writeBarrier.checkWritesAllowed(
    DatabaseAccessOperation(
        name = "BankStatementLifecycleProcessor.persistResults",
        pipeline = "P3",
        entity = "ScannedReceipt/PendingReview"
    )
)
database.withTransaction {
    writeBarrier.checkWritesAllowed("...insideTransactionStart")
    ...
}
```

For loops:

```kotlin
for (item in items) {
    executionGuard.checkpoint("before item write")
    writeBarrier.checkWritesAllowed("...")
    database.withTransaction { ... }
}
```

## BankStatement specific patch

Replace one large write block with either:

### Option A — one transaction

```kotlin
writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.persistAll")
database.withTransaction {
    writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.persistAll.tx")
    insert receipt
    insert events
    insert reviews
    update status
}
```

### Option B — per item transaction with import ledger

Better but larger.

## Acceptance tests

```text
restore_starts_after_bank_statement_parse_blocks_persist
restore_starts_during_bank_statement_review_loop_blocks_remaining_writes
restore_starts_after_email_parse_blocks_persist
restore_starts_during_import_loop_blocks_remaining_rows
```

---

# PR 12 — Test suite and tracker update

## Goal

Lock in the barrier behavior.

## Add test groups

### Barrier API tests

```text
write_allowed_only_NORMAL
read_normal_only_NORMAL
export_read_allowed_BACKUP_EXPORTING
```

### Repository write tests

```text
ExpenseRepository maintenance writes blocked
BudgetRepository notification writes blocked
Budget debug restore blocked/release denied
```

### Backup/restore tests

```text
restore uses fresh Room
restore waits worker drain
rollback failure critical
startup recovery verification
backup snapshot consistency
```

### Worker tests

```text
worker cancellation finalizes
stop requested checkpoint cancels
blocked worker safe sink
```

### Receiver tests

```text
dismiss/snooze coordinator + goAsync
```

### Static guard tests

```text
method-level allowlist
barrier required
debug-only enforced
file ops detected
```

---

# Final priority order

Do this order:

```text
1. PR 1 — Wire MaintenanceOperationRunner + worker drain into backup/restore/reset.
2. PR 2 — Fresh Room after DB swap.
3. PR 3 — Startup recovery verification.
4. PR 4 — Critical recovery state.
5. PR 5 — Worker logging/cancellation/backup-export semantics.
6. PR 6 — True backup snapshot or drained no-write snapshot.
7. PR 7 — Strengthen static DAO/access guard.
8. PR 8 — Reminder receiver cleanup.
9. PR 9 — Durable maintenance-safe diagnostics.
10. PR 10 — ExportCoordinator.
11. PR 11 — Long-write-section barrier hardening.
12. PR 12 — Full regression test suite + tracker update.
```

Fastest safety win:

```text
1. Fresh Room after swap.
2. Worker drain before restore/reset.
3. Startup recovery verification.
4. Worker cancellation finalization.
5. Static guard upgrade.
```

---

# Definition of done

The global barrier epic is closed only when:

```text
1. No production DB write can happen outside NORMAL mode.

2. Backup/restore/reset wait for active workers to drain or fail safely.

3. Restore never uses injected singleton Room after DB file swap.

4. Startup recovery verifies copied safety DB before NORMAL.

5. Rollback failure enters critical/restart-required blocked state, never NORMAL.

6. Backup export uses a true snapshot or a proven drained no-write window.

7. Worker cancellation and restore-blocked attempts are visible in diagnostics.

8. Static guard catches new unguarded DAO writes at method level.

9. Broadcast receivers no longer write DAOs directly.

10. Long parse/import/batch operations re-check barrier immediately before DB writes.

11. Export reads go through a coordinator with read/privacy barriers.

12. Tests prove the above.
```