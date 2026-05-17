# Global Write / Read / Restore Barrier Implementation Plan

Commit baseline: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Scope:

```text
No DB write unless DatabaseWriteBarrier allows it.
No export/read during restore unless DatabaseReadBarrier allows it.
No direct DAO mutation outside approved lifecycle/coordinator paths.
```

Affected pipelines:

```text
P1 Notification
P2 Transaction lifecycle
P3 Receipt/OCR
P4 Recurring/reminders
P6 Budget/forecast/planned
P7 Backup/restore
P9 Workers
P10 Bank integration
P11 Email receipt ingestion
P12 Import/export/accounting
```

---

## 0. Current state summary

The project already has the basic pieces:

- `RestoreMaintenanceMode`
- `DatabaseWriteBarrier`
- `DatabaseReadBarrier`
- partial write-barrier usage in multiple repositories/coordinators
- worker cancellation on maintenance-mode enter
- restart-required restore mode

But the current implementation is **caller-by-caller**, not globally enforceable.

### Current important facts

`DatabaseWriteBarrier` currently blocks writes when `RestoreMaintenanceMode.isWritesAllowed()` is false.

`RestoreMaintenanceMode.isWritesAllowed()` currently allows writes only in:

```text
NORMAL
```

`DatabaseReadBarrier` currently allows reads in:

```text
NORMAL
BACKUP_EXPORTING
```

`ExpenseDao` itself already has a TODO warning that mutation methods are public and should go through lifecycle coordination.

`RestoreMaintenanceMode.enter()` persists maintenance mode and cancels workers by `WorkerSpec.DEFAULTS`.

`DatabaseBackupRepositoryImpl.restoreCostBackup()` still has comments acknowledging stale injected Room after DB swap, so reads/writes after restore swap need extra caution.

---

# 1. Target architecture

## 1.1 Layers

Use three protection layers:

```text
Layer 1 — Runtime barriers
  DatabaseWriteBarrier
  DatabaseReadBarrier
  RestoreMaintenanceMode

Layer 2 — Approved lifecycle/coordinator write surfaces
  TransactionLifecycleCoordinator
  ReceiptLifecycleCoordinator
  RecurringLifecycleCoordinator
  Budget/Forecast coordinators
  Bank coordinators
  ImportCoordinator
  ExportCoordinator for reads

Layer 3 — Static enforcement
  CI/static test blocks direct DAO mutation outside allowlist
```

Do **not** try to inject barriers into Room DAO interfaces. Room DAOs should stay simple. Enforce boundaries at repository/coordinator/service entrypoints and with static checks.

---

# 2. Desired maintenance-mode policy

## 2.1 Modes

Keep existing modes, but add missing destructive/critical states.

```kotlin
enum class Mode {
    NORMAL,

    BACKUP_EXPORTING,

    RESTORE_PREPARING,
    RESTORE_STAGING,
    RESTORE_SWAPPING,
    RESTORE_VERIFYING,
    RESTORE_ROLLING_BACK,
    ASSETS_RESTORING,

    RESETTING_DATABASE,

    RESTORE_COMPLETE_RESTART_REQUIRED,
    CRITICAL_RECOVERY_REQUIRED
}
```

If adding `ASSETS_RESTORING` conflicts with the current `RestoreJournal` enum only, keep it there but make the policy explicit.

## 2.2 Write policy

```text
NORMAL -> writes allowed
everything else -> writes blocked
```

No exception for diagnostics unless using a special restore-safe sink.

## 2.3 Read policy

```text
NORMAL -> normal app reads allowed
BACKUP_EXPORTING -> controlled snapshot/export reads allowed
RESTORE_* -> normal app reads blocked
RESETTING_DATABASE -> reads blocked
RESTORE_COMPLETE_RESTART_REQUIRED -> reads blocked or app-shell locked
CRITICAL_RECOVERY_REQUIRED -> reads blocked except recovery UI
```

Important distinction:

```text
Restore engine internal verification reads must use fresh/staged DB handles,
not the injected app-wide AppDatabase singleton.
```

---

# 3. PR 1 — Harden barrier APIs

## Goal

Make barriers structured, testable, and reusable.

## Files

- `DatabaseWriteBarrier.kt`
- `DatabaseReadBarrier.kt`
- `RestoreMaintenanceMode.kt`
- tests

## Add models

```kotlin
enum class DatabaseAccessType {
    READ,
    WRITE
}

enum class DatabaseReadPolicy {
    NORMAL_APP_READ,
    EXPORT_OR_BACKUP_SNAPSHOT_READ,
    RESTORE_INTERNAL_STAGED_DB_READ
}

data class DatabaseAccessOperation(
    val name: String,
    val pipeline: String? = null,
    val entity: String? = null,
    val reason: String? = null
)

class DatabaseAccessBlockedException(
    val accessType: DatabaseAccessType,
    val operation: DatabaseAccessOperation,
    val mode: RestoreMaintenanceMode.Mode
) : IllegalStateException(
    "$accessType blocked during $mode: ${operation.name}"
)
```

## Write barrier v2

Keep the old method for compatibility, but add structured APIs.

```kotlin
@Singleton
class DatabaseWriteBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    fun checkWritesAllowed(operation: String) {
        checkWritesAllowed(DatabaseAccessOperation(operation))
    }

    fun checkWritesAllowed(operation: DatabaseAccessOperation) {
        val mode = restoreMaintenanceMode.currentMode()
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            throw DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = operation,
                mode = mode
            )
        }
    }

    suspend inline fun <T> runWrite(
        operation: DatabaseAccessOperation,
        crossinline block: suspend () -> T
    ): T {
        checkWritesAllowed(operation)
        return block()
    }
}
```

## Read barrier v2

```kotlin
@Singleton
class DatabaseReadBarrier @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode
) {
    fun checkReadAllowed(
        operation: String,
        policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ
    ) {
        checkReadAllowed(DatabaseAccessOperation(operation), policy)
    }

    fun checkReadAllowed(
        operation: DatabaseAccessOperation,
        policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ
    ) {
        val mode = restoreMaintenanceMode.currentMode()

        val allowed = when (policy) {
            DatabaseReadPolicy.NORMAL_APP_READ ->
                mode == RestoreMaintenanceMode.Mode.NORMAL

            DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ ->
                mode == RestoreMaintenanceMode.Mode.NORMAL ||
                mode == RestoreMaintenanceMode.Mode.BACKUP_EXPORTING

            DatabaseReadPolicy.RESTORE_INTERNAL_STAGED_DB_READ ->
                false
                // Do not allow this through the app singleton.
                // Restore should use fresh one-shot DB handles.
        }

        if (!allowed) {
            throw DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.READ,
                operation = operation,
                mode = mode
            )
        }
    }

    suspend inline fun <T> runRead(
        operation: DatabaseAccessOperation,
        policy: DatabaseReadPolicy = DatabaseReadPolicy.NORMAL_APP_READ,
        crossinline block: suspend () -> T
    ): T {
        checkReadAllowed(operation, policy)
        return block()
    }
}
```

## Acceptance tests

```text
write_allowed_in_NORMAL
write_blocked_in_BACKUP_EXPORTING
write_blocked_in_RESTORE_PREPARING
write_blocked_in_RESTORE_COMPLETE_RESTART_REQUIRED

normal_read_allowed_in_NORMAL
normal_read_blocked_in_BACKUP_EXPORTING
normal_read_blocked_in_RESTORE_VERIFYING

export_read_allowed_in_NORMAL
export_read_allowed_in_BACKUP_EXPORTING
export_read_blocked_in_RESTORE_VERIFYING
```

---

# 4. PR 2 — Add global app operational lock

## Goal

Do not rely only on repository guards. If restore completed and restart is required, the user should not keep using screens backed by stale Room state.

## Files

- `RestoreMaintenanceMode.kt`
- app shell / root navigation
- `AppStartupCoordinator.kt`
- backup/restore UI

## Add state model

```kotlin
sealed interface AppOperationalState {
    data object Normal : AppOperationalState
    data object BackupExporting : AppOperationalState
    data class RestoreInProgress(val mode: RestoreMaintenanceMode.Mode) : AppOperationalState
    data object RestartRequiredAfterRestore : AppOperationalState
    data object CriticalRecoveryRequired : AppOperationalState
}
```

## Behavior

```text
NORMAL:
  app usable

BACKUP_EXPORTING:
  optionally show non-blocking backup banner
  writes blocked

RESTORE_*:
  app shell blocks navigation/actions

RESTORE_COMPLETE_RESTART_REQUIRED:
  global full-screen lock
  only action: Restart app

CRITICAL_RECOVERY_REQUIRED:
  global full-screen lock
  only action: recovery/export logs/support
```

## Required cleanup

Remove or no-op local-only dismiss methods like:

```text
dismissRestartRequired()
```

They must not clear true operational lock state.

## Acceptance tests

```text
restore_success_sets_global_restart_required_lock
restart_required_blocks_navigation
restart_required_blocks_write_actions
dismiss_restart_required_is_noop_or_removed
startup_after_clean_restart_resets_mode_to_NORMAL
critical_recovery_required_blocks_app
```

---

# 5. PR 3 — Lifecycle-safe destructive DB operations

## Goal

No DB file operation can run without maintenance mode and journal.

## Applies to

```text
restoreCostBackup
legacy importDatabase
resetDatabase
raw DB export if kept
.costbackup export
```

## Required policy

### Restore

Already mostly has maintenance mode/journal, but must avoid stale Room after swap.

### Reset database

Must become a real lifecycle operation.

```text
enter RESETTING_DATABASE
pause/drain workers
create safety backup
journal operation
close DB
delete/swap DB files
verify new DB
exit RESTORE_COMPLETE_RESTART_REQUIRED
```

or remove from production entirely.

### Backup export

Current `.costbackup` enters `BACKUP_EXPORTING`, but still copies live DB. The stronger fix is:

```text
enter BACKUP_EXPORTING
wait for workers to drain
use SQLite backup API or VACUUM INTO
verify snapshot
exit NORMAL
```

## New helper

```kotlin
class MaintenanceOperationRunner @Inject constructor(
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val workerDrain: WorkerDrainController
) {
    suspend fun <T> runExclusive(
        mode: RestoreMaintenanceMode.Mode,
        operationName: String,
        requireRestartAfterSuccess: Boolean = false,
        block: suspend () -> T
    ): T {
        restoreMaintenanceMode.enter(mode)
        workerDrain.requestStopAndAwaitDrain(operationName)
        return try {
            block()
        } catch (t: Throwable) {
            throw t
        } finally {
            restoreMaintenanceMode.exit(forceRestartRequired = requireRestartAfterSuccess)
        }
    }
}
```

## Acceptance tests

```text
reset_database_enters_maintenance
reset_database_blocks_writes
reset_database_requires_restart
backup_export_enters_BACKUP_EXPORTING
backup_export_blocks_writes
restore_rollback_failure_keeps_writes_blocked
```

---

# 6. PR 4 — Worker drain contract

## Goal

Maintenance mode should not only cancel WorkManager asynchronously. It must wait for currently running guarded workers to stop before backup/restore/reset proceeds.

## Files

- `WorkerExecutionGuard.kt`
- `RestoreMaintenanceMode.kt`
- backup repository / restore orchestrator
- all workers

## Add worker lease registry

```kotlin
interface WorkerLeaseRegistry {
    suspend fun acquire(workerName: String): WorkerLease
    suspend fun requestStopAll(reason: String)
    suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean
}

interface WorkerLease : AutoCloseable {
    suspend fun checkpoint(operation: String)
}
```

## Guard behavior

```text
worker start:
  acquire lease
  check mode/write/read/privacy/spec
  start BackgroundJobRun

before every DB mutation:
  checkpoint()
  writeBarrier.checkWritesAllowed()

before notification side effect:
  checkpoint()
  re-check relevant state

finally:
  release lease
```

## Maintenance enter behavior

```text
write blocking mode first
request all workers stop
cancel WorkManager unique work
await active leases drain
then proceed with DB snapshot/swap/reset
```

## Acceptance tests

```text
restore_waits_for_running_worker_to_stop
backup_waits_for_data_retention_worker_to_stop
cancelled_worker_releases_lease
worker_checkpoint_blocks_mutation_after_restore_starts
```

---

# 7. PR 5 — Define approved writer ownership map

## Goal

Every table family has one approved write owner.

## Initial allowlist

### Expenses / transaction events

Approved:

```text
TransactionLifecycleCoordinator
ExpenseRepository only for explicitly approved maintenance/backfill methods, each guarded
Room migrations
debug-only tools with BuildConfig.DEBUG + write barrier
```

Not approved:

```text
UI/ViewModel direct ExpenseDao calls
workers direct ExpenseDao calls
receipt/bank/email services direct ExpenseDao calls
```

### Notifications

Approved:

```text
NotificationRepository / NotificationProcessingPipeline
NotificationIntakeCoordinator once added
DataRetentionCoordinator for purge
```

### Receipts / email / OCR

Approved:

```text
ReceiptLifecycleCoordinator
ReceiptLinkService, but only through guarded methods
BankStatementLifecycleProcessor until replaced by BankStatementImportCoordinator
DataRetentionCoordinator for raw purge
```

### Recurring / reminders

Approved:

```text
RecurringRuleLifecycleCoordinator
RecurringLifecycleCoordinator
RecurringOccurrenceMaterializer
ReminderDeliveryCoordinator
```

Receivers should delegate to coordinator, not DAO.

### Budget / forecast / planned

Approved:

```text
BudgetRepository or BudgetLifecycleCoordinator
BudgetForecastingEngine or ForecastLifecycleCoordinator
PlannedExpenseRepository or PlannedExpenseLifecycleCoordinator
```

All notification timestamp updates must be guarded.

### Backup / restore

Approved:

```text
RestoreOrchestrator / DatabaseBackupRepositoryImpl
only for file-level operations under maintenance mode
```

No app-wide Room DAO use after DB file swap.

### Workers

Approved:

```text
Workers do not write DAOs directly.
Workers call lifecycle/repository/coordinator methods.
Exceptions require explicit allowlist and write barrier.
```

### Bank

Approved:

```text
BankConnectionLifecycleCoordinator
BankSyncCoordinator
BankStatementImportCoordinator
```

### Email

Approved:

```text
ReceiptLifecycleCoordinator / future EmailReceiptLifecycleCoordinator
```

Email service should not mutate DB or dispatch lifecycle side effects.

### Export/import

Approved:

```text
ExportCoordinator for reads
ImportCoordinator for writes
```

---

# 8. PR 6 — Add static direct-DAO mutation guard

## Goal

CI fails when new direct DAO mutation appears outside allowlist.

## Add script

```text
scripts/verify_db_access_boundaries.py
```

## Add allowlist config

```text
config/db_access_allowlist.yml
```

Example:

```yaml
allowed_writers:
  - class: TransactionLifecycleCoordinator
    daos:
      - expenseDao
      - transactionEventDao
    reason: "canonical transaction lifecycle writer"

  - class: ReceiptLifecycleCoordinator
    daos:
      - scannedReceiptDao
      - receiptEventDao
      - emailReceiptDao
      - pendingReviewDao
    reason: "canonical receipt lifecycle writer"

  - class: DatabaseBackupRepositoryImpl
    reason: "file-level backup/restore operations under maintenance mode"

  - class: DataRetentionWorker
    allowed_until: "migrate to RetentionCoordinator"
    reason: "temporary purge owner; must call WorkerExecutionGuard checkpoints"
```

## Mutation patterns

Search for calls to DAO methods likely to mutate:

```regex
\.\s*(insert|insertAll|update|delete|deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|bulkRename|approve|reject)\s*\(
```

Limit to files under:

```text
app/src/main/java
```

Ignore:

```text
app/src/test
app/src/androidTest
migrations
generated
```

## Build integration

Add Gradle task:

```kotlin
tasks.register("verifyDbAccessBoundaries") {
    doLast {
        exec {
            commandLine("python3", "scripts/verify_db_access_boundaries.py")
        }
    }
}

tasks.named("check") {
    dependsOn("verifyDbAccessBoundaries")
}
```

## Acceptance tests

```text
guard_fails_on_direct_expenseDao_update_in_viewmodel
guard_allows_transaction_lifecycle_expense_write
guard_fails_on_worker_direct_receipt_update
guard_allows_room_migration
allowlist_requires_reason
```

---

# 9. PR 7 — Write-barrier sweep by pipeline

## Goal

Every approved write entrypoint checks `DatabaseWriteBarrier`.

Do not do this blindly inside every method; guard public write entrypoints and long-running batch chunks.

---

## Pipeline 1 — Notification

### Fix

- Service-level restore gate before extraction.
- Repository/pipeline save paths use write barrier.
- Diagnostics during restore must not use normal Room unless explicitly allowed.

### Tasks

```text
NotificationCaptureService:
  check restore/privacy gate before extraction and dedupe

NotificationRepository:
  all save/delete/process write entrypoints check write barrier

NotificationProcessingPipeline:
  check write barrier before RawNotification/PendingReview/Expense write branches
```

### Tests

```text
restore_blocks_notification_processing_before_raw_insert
restart_required_blocks_notification_processing
restore_blocked_notification_does_not_write_pending_review
```

---

## Pipeline 2 — Transaction lifecycle

### Fix

Expense writes must route through coordinator or guarded maintenance methods.

Current known risk examples:

```text
conditionallySetLocation
clearExpenseLocation
incrementBackfillAttempts
updateMerchantKey
```

### Tasks

```text
Add writeBarrier.checkWritesAllowed to all intentionally direct maintenance methods.

Make all user edits route through TransactionLifecycleCoordinator.

Keep debug delete/restore behind:
  BuildConfig.DEBUG
  writeBarrier
```

### Tests

```text
restore_blocks_location_backfill_update
restore_blocks_merchant_key_backfill
restore_blocks_delete_all_expenses
restore_blocks_debug_snapshot_restore
```

---

## Pipeline 3 — Receipt/OCR

### Fix

Receipt writes through receipt lifecycle only.

### Tasks

```text
ReceiptRepository direct insert/update/delete methods:
  either remove, make internal, or add barrier + lifecycle event

ReceiptLinkService:
  use DatabaseWriteBarrier, not direct RestoreMaintenanceMode

Direct match/suggestion methods:
  route through coordinator or guarded ReceiptLinkService
```

### Tests

```text
restore_blocks_receipt_scan_save
restore_blocks_receipt_link
restore_blocks_receipt_match_suggestion
restore_blocks_receipt_delete
```

---

## Pipeline 4 — Recurring/reminders

### Fix

Rule/occurrence/reminder writes through recurring coordinators.

### Tasks

```text
RecurringExpenseRepository.delete/update:
  delegate to RecurringRuleLifecycleCoordinator

ManualRecurringExpenseRepository:
  all writes delegate to rule coordinator

SnoozeReminderReceiver / DismissReminderReceiver:
  no direct DAO writes
  delegate to RecurringLifecycleCoordinator
  use goAsync/WorkManager

Reminder delivery claim/send/fail:
  write barrier at coordinator entry
```

### Tests

```text
restore_blocks_rule_create
restore_blocks_rule_update
restore_blocks_rule_delete
restore_blocks_reminder_snooze
restore_blocks_reminder_dismiss
restore_blocks_occurrence_generation
```

---

## Pipeline 6 — Budget/forecast/planned

### Fix

All budget/planned/forecast writes guarded.

Known risks:

```text
BudgetRepository.updateExceededNotification
BudgetRepository.updateCriticalNotification
BudgetRepository.updateWarningNotification
BudgetRepository.restoreDebugSnapshot
```

### Tasks

```text
Add write barrier to all notification timestamp updates.

restoreDebugSnapshot:
  BuildConfig.DEBUG
  writeBarrier

BudgetForecastingEngine:
  keep write barrier at generate/update accuracy

PlannedExpenseRepository:
  keep/add write barrier to insert/update/delete/fulfill paths
```

### Tests

```text
restore_blocks_budget_notification_timestamp_update
restore_blocks_budget_debug_restore
restore_blocks_forecast_generation
restore_blocks_forecast_accuracy_update
restore_blocks_planned_expense_insert
```

---

## Pipeline 7 — Backup/restore

### Fix

Backup/restore/reset must own maintenance mode and must not use stale app-wide Room after DB swap.

### Tasks

```text
restoreCostBackup:
  keep maintenance mode from start
  use fresh Room instance after DB swap for verification and asset path updates

importDatabase:
  debug-only
  rollback failure exits restart-required or critical mode, never NORMAL

resetDatabase:
  add maintenance mode + journal + restart-required
  or remove production path

createCostBackup:
  BACKUP_EXPORTING + worker drain + point-in-time snapshot
```

### Tests

```text
restore_uses_fresh_room_after_swap
legacy_import_rollback_failure_keeps_writes_blocked
reset_database_enters_maintenance
backup_export_blocks_writes
startup_recovery_verifies_before_normal
```

---

## Pipeline 9 — Workers

### Fix

Workers must obey barrier before each mutation and stop cleanly during maintenance.

### Tasks

```text
WorkerExecutionGuard:
  acquire lease
  check barrier
  checkpoint before mutations
  finalize cancellation
  log blocked/skipped outcome safely

Workers:
  no direct DAO mutation unless temporary allowlisted
  call coordinator/repository methods
```

### Tests

```text
restore_enter_waits_for_workers
worker_checkpoint_blocks_after_restore_starts
cancelled_worker_finalizes_run
worker_direct_dao_mutation_fails_static_guard
```

---

## Pipeline 10 — Bank

### Fix

Bank writes through bank coordinators.

### Tasks

```text
Create BankConnectionLifecycleCoordinator
Create BankSyncCoordinator

BankApiIntegration:
  no direct durable writes except through coordinator
  keep demo guard

BankConnectionDao:
  direct mutation only from coordinators
```

### Tests

```text
restore_blocks_bank_connect
restore_blocks_bank_disconnect
restore_blocks_bank_sync
direct_bank_dao_write_outside_coordinator_fails_static_guard
```

---

## Pipeline 11 — Email

### Fix

Email service should not write DB directly and should not dispatch transaction side effects.

### Tasks

```text
EmailReceiptIngestionService:
  writeBarrier at entry before parse/save
  delegate to ReceiptLifecycleCoordinator
  no direct DAO mutation
  no service-level transaction side effects

ReceiptLifecycleCoordinator.processEmailReceipt:
  all writes guarded
  restore-blocked diagnostics use safe sink
```

### Tests

```text
restore_blocks_email_ingestion_before_receipt_insert
restore_blocked_email_does_not_write_room_diagnostic_unsafely
email_service_has_no_direct_dao_write
```

---

## Pipeline 12 — Export/import/accounting

### Fix

Export reads must be guarded centrally. Import writes must go through lifecycle.

### Tasks

```text
Create ExportCoordinator:
  readBarrier.checkReadAllowed
  privacy gate
  export snapshot
  all export reads through this

ExportDataRepository:
  inject DatabaseReadBarrier
  guard all public read methods or make internal behind ExportCoordinator

Create ImportCoordinator:
  writeBarrier
  TransactionLifecycleCoordinator for rows
```

### Tests

```text
restore_blocks_export_generation
restart_required_blocks_export_generation
backup_export_policy_allows_only_snapshot_reads
restore_blocks_import
import_routes_through_transaction_lifecycle
```

---

# 10. PR 8 — Read-barrier sweep

## Goal

No high-risk read/export path touches the app DB during restore/restart-required.

## Priorities

### Tier 1 — Must guard now

```text
ExportCoordinator
ExportDataRepository
Backup/restore verification
Assistant full-data queries
Analytics dashboard refresh if triggered outside app shell
Import preview if it reads DB for duplicate/category resolution
```

### Tier 2 — Guard through app shell first

```text
normal dashboard screens
normal transaction lists
normal budget screens
normal receipt screens
```

For Tier 2, global app lock can be the first protection. Later add repository-level guards if needed.

## Flow handling

For `Flow` reads, use one of:

### Option A — app shell lock only

Simpler. Blocks navigation/collection during restore.

### Option B — guarded Flow helper

```kotlin
fun <T> Flow<T>.guardedDatabaseRead(
    readBarrier: DatabaseReadBarrier,
    operation: String
): Flow<T> = flow {
    readBarrier.checkReadAllowed(operation)
    emitAll(this@guardedDatabaseRead)
}
```

This only checks at collection start.

### Option C — mode-aware cancellation

Requires `RestoreMaintenanceMode.modeFlow`.

```kotlin
fun <T> Flow<T>.blockedDuringRestore(
    modeFlow: StateFlow<RestoreMaintenanceMode.Mode>,
    operation: String
): Flow<T> =
    modeFlow.flatMapLatest { mode ->
        if (mode == RestoreMaintenanceMode.Mode.NORMAL) this
        else emptyFlow()
    }
```

Recommended:

```text
Use app shell lock now.
Use explicit read guards for export/assistant/import/backup now.
Add mode-aware Flow later if still needed.
```

---

# 11. PR 9 — Diagnostics during blocked modes

## Problem

If write barrier blocks DB writes, normal `PipelineDiagnosticEventDao.insert()` is also blocked.

## Rule

```text
When DB writes are blocked, do not try to write normal Room diagnostics.
```

## Add safe diagnostic abstraction

```kotlin
interface MaintenanceSafeDiagnosticSink {
    fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String?,
        entity: String?
    )
}
```

Implementation options:

```text
1. Timber only, short term
2. SharedPreferences/DataStore ring buffer
3. RestoreJournal / BackupRestoreEvent for backup/restore-specific events
```

Do not use normal Room tables while restore is active unless there is an explicit audited exception.

## Acceptance tests

```text
restore_blocked_notification_diagnostic_does_not_insert_room_row
restore_blocked_email_diagnostic_does_not_insert_room_row
blocked_operation_written_to_safe_sink
```

---

# 12. PR 10 — Convert direct DAO surfaces to internal/facades where possible

## Goal

Make misuse harder.

Room DAO interfaces must usually stay public to Room, but code can depend on narrower wrappers.

## Pattern

```kotlin
class ExpenseReadStore @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    suspend fun getById(id: Long) = expenseDao.getById(id)
    fun observeAll(...) = expenseDao.getAllFlow(...)
}

class ExpenseWriteStore @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val expenseDao: ExpenseDao
) {
    suspend fun updateMerchantKey(...) {
        writeBarrier.checkWritesAllowed("ExpenseWriteStore.updateMerchantKey")
        expenseDao.updateMerchantKey(...)
    }
}
```

Coordinators receive write stores. UI/read repositories receive read stores.

This is optional but useful after the static guard.

---

# 13. Agent execution checklist

Run initial inventory:

```bash
rg "Dao" app/src/main/java/com/yourname/expensetracker -g '*.kt'
rg "\.(insert|insertAll|update|delete|deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|bulkRename|approve|reject)\s*\(" app/src/main/java -g '*.kt'
rg "checkWritesAllowed" app/src/main/java -g '*.kt'
rg "checkReadAllowed" app/src/main/java -g '*.kt'
rg "restoreMaintenanceMode" app/src/main/java -g '*.kt'
rg "DatabaseWriteBarrier" app/src/main/java -g '*.kt'
rg "DatabaseReadBarrier" app/src/main/java -g '*.kt'
```

Find risky direct file operations:

```bash
rg "getDatabasePath|deleteRecursively|renameTo|copyTo|openDatabase|writableDatabase|closeLiveDatabase" app/src/main/java -g '*.kt'
```

Find export paths:

```bash
rg "ExportDataRepository|generateExport|export|countExpensesBetween|getExpensesBetweenForExport" app/src/main/java -g '*.kt'
```

Find worker writes:

```bash
rg "class .*Worker|runGuarded|checkpoint|Dao" app/src/main/java -g '*.kt'
```

---

# 14. Migration strategy

## Phase 1 — Non-breaking compatibility

- Add new barrier APIs.
- Keep old `checkWritesAllowed(String)` and `checkReadAllowed(String)`.
- Add static guard in warning/report mode first.

## Phase 2 — Block new violations

- Turn static guard into CI failure.
- Add allowlist for current intentional exceptions.
- Require reason for every exception.

## Phase 3 — Remove exceptions

Reduce allowlist PR by PR:

```text
ExpenseRepository direct maintenance writes -> guarded write store
Receipt direct mutations -> lifecycle coordinator
Recurring receiver writes -> coordinator
Budget timestamp writes -> guarded repository
Bank DAO writes -> bank coordinator
Email service writes -> none
Export reads -> ExportCoordinator
```

---

# 15. Global acceptance criteria

Definition of done:

```text
1. DatabaseWriteBarrier blocks every production DB write in all non-NORMAL modes.

2. DatabaseReadBarrier blocks export/high-risk reads during restore and restart-required modes.

3. Backup export may read only through explicit snapshot/export read policy.

4. Restore/reset/import DB file operations always enter maintenance mode first.

5. No app-wide injected Room/DAO is used after live DB file swap.

6. Every direct DAO mutation callsite is either:
   - inside approved lifecycle/coordinator/store,
   - debug-only + write-barrier guarded,
   - migration-only,
   - or explicitly allowlisted with reason and TODO.

7. Static CI guard fails on new unauthorized DAO mutations.

8. Workers are stopped/drained before backup/restore/reset file operations.

9. Blocked operations do not attempt normal Room diagnostics.

10. Representative tests prove restore/restart-required mode blocks:
    - notification processing
    - expense create/update/delete
    - receipt save/link
    - recurring rule/reminder writes
    - budget/planned/forecast writes
    - bank connect/sync
    - email ingestion
    - import writes
    - export reads
```

---

# 16. Recommended PR order

Do this order:

```text
PR 1  Barrier API v2 + tests
PR 2  Global app operational lock
PR 3  Lifecycle-safe reset/restore/backup destructive operations
PR 4  Worker drain/checkpoint contract
PR 5  Approved writer ownership map
PR 6  Static DAO mutation guard in warning mode
PR 7  Write-barrier sweep for known gaps
PR 8  Export/read-barrier sweep
PR 9  Maintenance-safe diagnostic sink
PR 10 Turn static guard into CI failure
PR 11 Optional read/write store facades
```

If you want the fastest user-safety win, do:

```text
1. Barrier API v2
2. Budget/expense/receipt/email/bank known write-gap sweep
3. ExportCoordinator read barrier
4. Static DAO mutation guard
5. Restore/reset destructive operation hardening
```

---

# 17. Sources used

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `DatabaseWriteBarrier.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt

- `DatabaseReadBarrier.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt

- `RestoreMaintenanceMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `ExpenseDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `ExpenseRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt