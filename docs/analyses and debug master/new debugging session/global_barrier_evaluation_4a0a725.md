# Global Write / Read / Restore Barrier Evaluation

Commit reviewed: `4a0a72543a957121bceed2519428758102d53700`  
Parent reviewed previously: `51cdb7d197146a17eb8c1fdda359a54718593ab6`  
Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit:  
https://github.com/panospao7/Cost-agregator/commit/4a0a72543a957121bceed2519428758102d53700

---

## Executive verdict

This commit is a **good targeted patch** over the previous barrier foundation. It fixes several concrete misses from the last review:

```text
ExpenseRepository maintenance writes now check DatabaseWriteBarrier.
BudgetRepository notification timestamp writes now check DatabaseWriteBarrier.
BudgetRepository.restoreDebugSnapshot is DEBUG-gated and barrier-guarded.
Legacy import rollback failure no longer exits to NORMAL if rollback failed.
RestoreMaintenanceMode resets the worker stop flag when exiting to NORMAL.
WorkerExecutionGuard.checkpoint now observes WorkerLeaseRegistry stop requests.
MainActivity now blocks RestoreInProgress.
BankStatementLifecycleProcessor re-checks barrier after OCR/AI/parsing before DB writes.
```

So the commit should be considered a **real improvement**.

However, the global barrier implementation is still **not closed**.

Remaining highest-risk issues:

1. **Worker drain is still not actually wired into backup/restore/reset.**
2. **MaintenanceOperationRunner still appears unused.**
3. **Restore still uses stale injected Room after DB file swap.**
4. **Backup export still copies the live DB without true snapshot/drain guarantees.**
5. **Worker cancellation still leaves `BackgroundJobRun` rows unfinished.**
6. **WorkerExecutionGuard still checks write barrier before run logging, so restore-blocked worker attempts remain invisible.**
7. **Some long write blocks still only check the barrier once; restore can start after the check.**
8. **Dismiss/Snooze reminder receivers still directly write DAOs with `runBlocking`; they only check barrier once before the coroutine body.**
9. **Static DAO guard remains broad and class-level; it cannot prove methods actually call the barrier.**
10. **Maintenance-safe diagnostics remain mostly Timber-only / not durable.**

Current status: **improved partial foundation, not production-closed**.

---

# 1. Fixes confirmed in this commit

## 1.1 ExpenseRepository write gaps fixed

Previously open methods:

```text
conditionallySetLocation
clearExpenseLocation
incrementBackfillAttempts
updateMerchantKey
```

Now each calls:

```kotlin
writeBarrier.checkWritesAllowed(...)
```

before the DAO mutation.

### Status

**Fixed for these four methods.**

### Remaining caveat

These methods still live in `ExpenseRepository` as approved direct maintenance writes. That is acceptable short-term, but long-term they should move to `ExpenseWriteStore` or a maintenance/backfill coordinator.

### Suggested tracker state

```text
ExpenseRepository maintenance write barrier sweep: fixed short-term / migrate later.
```

---

## 1.2 BudgetRepository write gaps fixed

Previously open methods:

```text
updateExceededNotification
updateCriticalNotification
updateWarningNotification
restoreDebugSnapshot
```

Now:

- each notification timestamp method checks `writeBarrier`,
- `restoreDebugSnapshot()` is `BuildConfig.DEBUG` gated,
- `restoreDebugSnapshot()` checks `writeBarrier`.

### Status

**Fixed for the previously identified BudgetRepository gaps.**

### Remaining caveat

`restoreDebugSnapshot()` still directly mutates budget tables. This is okay only as debug-only repair tooling, but it should stay covered by the static guard and tests.

### Required tests

```text
restore_blocks_budget_notification_timestamp_update
restore_blocks_budget_debug_restore
release_build_restoreDebugSnapshot_returns_error
```

---

## 1.3 Legacy import rollback failure no longer exits to NORMAL

Previously:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = false)
```

even if rollback failed.

Now:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = !rollbackResult.isSuccess)
```

### Status

**Mostly fixed.**

If rollback fails, the app exits to `RESTORE_COMPLETE_RESTART_REQUIRED`, so writes remain blocked.

### Remaining caveat

`RESTORE_COMPLETE_RESTART_REQUIRED` is better than `NORMAL`, but a rollback failure means DB state is unknown. This probably deserves a stronger state:

```text
CRITICAL_RECOVERY_REQUIRED
```

not merely restart required.

### Suggested patch

```kotlin
if (rollbackResult.isSuccess) {
    restoreMaintenanceMode.exit(forceRestartRequired = false)
} else {
    restoreMaintenanceMode.enter(RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED)
}
```

or add:

```kotlin
restoreMaintenanceMode.failCriticalRecovery(...)
```

### Required tests

```text
legacy_import_rollback_success_exits_NORMAL
legacy_import_rollback_failure_enters_CRITICAL_RECOVERY_REQUIRED_or_restart_required
legacy_import_rollback_failure_does_not_schedule_workers
```

---

## 1.4 Worker stop flag reset partially fixed

`RestoreMaintenanceMode.exit(forceRestartRequired = false)` now resets the `WorkerLeaseRegistryImpl` stop flag when returning to `NORMAL`.

### Status

**Fixed for normal exit path.**

### Remaining caveat

`reset()` still only writes mode to `NORMAL`; it does not reschedule workers or reset the stop flag. If `reset()` is only called after process restart, that is fine because the in-memory flag is new. If it can be called in-process, it can leave the stop flag stale.

### Suggested patch

Either document:

```text
reset() is startup-only after process recreation
```

or make `reset()` also call:

```kotlin
(workerLeaseRegistry.get() as? WorkerLeaseRegistryImpl)?.resetStopFlag()
scheduleAllWorkers()
```

carefully.

---

## 1.5 WorkerExecutionGuard checkpoint now checks stop flag

`checkpoint()` now checks:

```kotlin
(leaseRegistry as? WorkerLeaseRegistryImpl)?.isStopRequested()
```

before the write barrier.

### Status

**Improved.**

This means existing workers that call:

```kotlin
executionGuard.checkpoint(...)
```

can now observe maintenance stop requests.

### Remaining caveats

1. It casts to the implementation instead of using the interface.
2. It does not use the active lease acquired in `runGuarded()`.
3. `runGuarded()` still does not expose a `WorkerRunContext`.
4. `CancellationException` from checkpoint is still rethrown before marking the worker run as cancelled.

### Better design

Add to `WorkerLeaseRegistry`:

```kotlin
fun isStopRequested(): Boolean
fun resetStopFlag()
```

or expose checkpoint through a context:

```kotlin
runGuarded(request) { ctx ->
    ctx.checkpoint("before write")
}
```

---

## 1.6 MainActivity now blocks RestoreInProgress

Previously app continued for:

```text
RestoreInProgress
```

Now it shows `AppOperationalLockScreen`.

### Status

**Fixed for restore-in-progress app-shell lock.**

### Remaining caveat

`BackupExporting` still allows normal app use. That may be acceptable if writes are blocked and reads are controlled, but during backup export it may be safer to show at least a non-blocking banner and disable write actions.

### Required tests

```text
restore_in_progress_blocks_navigation
restart_required_blocks_navigation
critical_recovery_blocks_navigation
backup_exporting_shows_banner_or_blocks_writes
```

---

## 1.7 BankStatementLifecycleProcessor re-checks before final DB writes

The processor now re-checks after OCR/AI/parsing and before persisting the receipt/reviews.

### Status

**Improved but not fully safe.**

### Remaining problem

The check occurs once before a long sequence of DB writes:

```text
insert receipt
insert receipt event
loop pending reviews
update receipt status
insert complete event
```

If restore starts after the re-check but before or during the loop, later DAO writes can still happen.

A barrier check is not a lock.

### Fix strategy

Minimum:

```text
check barrier immediately before each DAO mutation or each small transaction.
```

Better:

```text
wrap all final writes in database.withTransaction {
    writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.writeResults")
    insert receipt
    insert events
    insert reviews
    update status
}
```

Best:

```text
use BankStatementImportCoordinator + operation run/item ledger
and perform per-item guarded transactions.
```

Also, the injected variable is named `restoreMaintenanceMode` but typed as `DatabaseWriteBarrier`. Rename it:

```kotlin
private val writeBarrier: DatabaseWriteBarrier
```

to prevent future confusion.

---

# 2. Major remaining issues

## 2.1 MaintenanceOperationRunner still appears unused

`MaintenanceOperationRunner` exists and is conceptually correct:

```text
enter maintenance
request worker drain
run block
exit
```

But `DatabaseBackupRepositoryImpl` does not inject or use it.

Evidence:

- `DatabaseBackupRepositoryImpl` constructor still injects `RestoreMaintenanceMode` directly.
- Searching the file for `MaintenanceOperationRunner` returns no match.

### Impact

Backup/restore/reset still do not benefit from centralized worker-drain semantics.

### Fix strategy

Inject:

```kotlin
private val maintenanceOperationRunner: MaintenanceOperationRunner
```

Then route:

```text
createCostBackup -> runExclusive(BACKUP_EXPORTING, ...)
restoreCostBackup -> runExclusive(RESTORE_PREPARING or staged modes, ...)
importDatabase -> runExclusive(RESTORE_PREPARING, ...)
resetDatabase -> runExclusive(RESETTING_DATABASE, ..., requireRestartAfterSuccess = true)
```

Because restore has multiple stages, the runner may need a more flexible API:

```kotlin
runExclusiveStart(...)
transitionMode(...)
finishExclusive(...)
```

or keep the runner responsible for the outer lock/drain and let restore transition sub-states inside.

### Priority

**P1.**

---

## 2.2 Worker drain still not used by backup/restore/reset

`WorkerLeaseRegistryImpl` and `WorkerDrainController` exist, but `RestoreMaintenanceMode.enter()` still does only:

```kotlin
pauseAllWorkers()
```

which calls:

```kotlin
WorkManager.cancelUniqueWork(name)
```

That is async and does not wait for already-running workers.

### Impact

A worker can still be in the middle of:

```text
receipt matching
retention purge
location backfill
merchant key backfill
bill reminder send
```

while backup/restore/reset proceeds.

### Fix strategy

At every destructive/snapshot operation start:

```kotlin
restoreMaintenanceMode.enter(mode)
workerDrainController.requestStopAndAwaitDrain(operationName)
```

If drain times out, do **not** silently proceed for restore/reset. For backup export maybe allow proceed only if operation is read-only and snapshot mechanism is safe.

Recommended policy:

```text
restore/reset: drain timeout => fail and remain blocked or retry
backup export: drain timeout => fail backup, exit NORMAL
```

Do not proceed with DB file swap after a drain timeout.

### Priority

**P1/P0 for restore/reset.**

---

## 2.3 WorkerRun logging is still incomplete

`WorkerExecutionGuard.runGuarded()` still checks the write barrier before:

```text
lease acquisition
BackgroundJobRun start
```

So if restore/restart-required mode blocks the worker, no `BackgroundJobRun` row is written.

Also, cancellation still does:

```kotlin
if (e is CancellationException) throw e
```

before finalizing the run.

### Impact

During restore/privacy cancellation, workers can disappear from diagnostics or leave stale `RUNNING` rows.

### Fix strategy

Add typed handling:

```kotlin
catch (e: CancellationException) {
    run.cancelled("cancelled_by_maintenance_or_workmanager")
    throw e
}
```

For restore-blocked attempts, use either:

```text
MaintenanceSafeDiagnosticSink
```

or allow a special durable event outside normal DB if safe.

### Required tests

```text
restore_blocked_worker_records_safe_skip
cancelled_worker_finalizes_run_cancelled
stale_RUNNING_recovery_marks_STALE_ABORTED
```

### Priority

**P1.**

---

## 2.4 `allowDuringBackupExport` remains ineffective

`runGuarded()` checks:

```kotlin
writeBarrier.checkWritesAllowed(request.workerName)
```

before checking:

```kotlin
allowDuringBackupExport
```

Since `DatabaseWriteBarrier` blocks in `BACKUP_EXPORTING`, this flag cannot work.

### Impact

The field suggests a capability that is not real. Future read-only workers may be incorrectly skipped, or developers may rely on a broken option.

### Fix strategy

Change request model:

```kotlin
data class WorkerGuardRequest(
    val workerName: String,
    val requiresDatabaseWrite: Boolean = true,
    val allowDuringBackupExport: Boolean = false,
    ...
)
```

Then:

```kotlin
if (requiresDatabaseWrite) {
    writeBarrier.checkWritesAllowed(...)
} else {
    readBarrier.checkReadAllowed(..., EXPORT_OR_BACKUP_SNAPSHOT_READ)
}
```

### Priority

**P2 now, P1 if read-only workers are added.**

---

## 2.5 Restore still uses stale injected Room after DB swap

This was not changed in this commit.

`DatabaseBackupRepositoryImpl.restoreCostBackup()` still does live verification with the injected `database` after file replacement, and `restoreReceiptAssets()` still uses:

```kotlin
val dao = database.scannedReceiptDao()
```

after restore.

Legacy import also still has a comment acknowledging stale Room after swap.

### Impact

After DB file replacement:

```text
verification may run on stale/closed Room handle
asset path updates may fail or write to wrong/stale connection
restore correctness depends on restart
```

### Fix strategy

Add `RestoreDatabaseOpener`:

```kotlin
interface RestoreDatabaseOpener {
    fun openFreshDatabase(): AppDatabase
}
```

After swap:

```kotlin
val freshDb = restoreDatabaseOpener.openFreshDatabase()
try {
    freshDb.openHelper.writableDatabase
    verify using freshDb
    restore assets using freshDb.scannedReceiptDao()
} finally {
    freshDb.close()
}
```

Rule:

```text
No injected singleton AppDatabase after DB file swap.
```

### Priority

**P1.**

---

## 2.6 Startup crash recovery still does not verify copied safety DB

Not fixed in this commit.

The previous issue remains: if startup recovery copies safety backup successfully, it needs to verify:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
Room open/migration validation
BackupVerifier if counts available
```

before resetting to normal.

### Impact

A corrupted safety backup can be treated as recovered.

### Priority

**P1.**

---

## 2.7 Backup export still lacks true point-in-time snapshot

Not fixed in this commit.

`createCostBackup()` still appears to depend on:

```text
checkpoint WAL
copy live DB file
```

rather than:

```text
SQLite backup API
VACUUM INTO
or locked/drained snapshot mechanism
```

### Impact

If any unguarded or already-running write happens during copy, backup consistency is not guaranteed.

### Fix strategy

Preferred:

```sql
VACUUM INTO 'snapshot.db'
```

or Android-supported SQLite backup approach.

Minimum:

```text
enter BACKUP_EXPORTING
drain workers and fail on timeout
block foreground writes
checkpoint WAL
copy DB
verify copied DB
exit NORMAL
```

### Priority

**P1/P2.**

---

## 2.8 Reminder receivers still write directly and check barrier only once

`DismissReminderReceiver` and `SnoozeReminderReceiver` still:

```text
write DAO directly
use runBlocking(Dispatchers.IO)
check write barrier once before runBlocking
```

### Impact

Race:

```text
barrier check passes
restore starts
runBlocking body writes delivery + event
```

Also this bypasses the intended coordinator lifecycle.

### Fix strategy

Replace with:

```text
goAsync()
delegate to RecurringLifecycleCoordinator.dismissReminder/snoozeReminder
coordinator checks barrier immediately before DB transaction
```

At minimum, re-check inside the coroutine immediately before DAO writes.

### Priority

**P2/P1 for restore race.**

---

## 2.9 Static DAO guard still too broad

The guard is now wired into Gradle `check` with `--fail-on-violation`, which is good.

But the implementation still:

```text
parses only class names from allowlist
ignores daos
ignores methods_only
ignores debug_only
does not verify writeBarrier call
does not check raw SQL/file operations
skips any allowlisted class wholesale
```

### Impact

A broad allowlisted class can add unsafe DAO writes without CI failing.

Example risk:

```text
BudgetRepository is allowlisted,
so any future budgetDao mutation in that file passes,
even if no writeBarrier exists.
```

### Fix strategy

Upgrade the script:

1. Parse YAML properly.
2. Enforce:
   ```text
   class + DAO + method
   ```
3. For non-coordinator allowlisted methods, require a barrier call in same method.
4. Enforce `debug_only` with `BuildConfig.DEBUG` check.
5. Add forbidden file-operation guard:
   ```text
   delete DB file
   copy DB file
   renameTo
   writableDatabase after swap
   execSQL outside migrations
   ```

### Priority

**P1 for preventing regression.**

---

# 3. Updated status table

| Area | Previous status after `51cdb7d` | Status after `4a0a725` |
|---|---:|---:|
| Barrier API v2 | mostly implemented | unchanged / good |
| App lock restart-required | mostly fixed | fixed |
| App lock restore-in-progress | open | fixed |
| Expense maintenance write gaps | open | fixed |
| Budget timestamp write gaps | open | fixed |
| Budget debug restore guard | open | fixed |
| Legacy import rollback failure to NORMAL | open | mostly fixed |
| Worker stop flag reset | open | fixed for `exit(NORMAL)` |
| Worker checkpoint stop flag | open | partial fixed |
| Worker drain used by backup/restore/reset | open | still open |
| Worker cancellation finalization | open | still open |
| Restore stale Room after swap | open | still open |
| Backup snapshot consistency | open | still open |
| Static DAO guard strength | weak | still weak |
| Maintenance-safe diagnostics durability | weak | still weak |
| Receiver direct writes | open | still open |

---

# 4. New concerns introduced by this commit

## 4.1 Variable naming confusion in BankStatementLifecycleProcessor

The field is:

```kotlin
private val restoreMaintenanceMode: DatabaseWriteBarrier
```

This compiles because the type is `DatabaseWriteBarrier`, but the name is misleading.

### Fix

Rename to:

```kotlin
private val writeBarrier: DatabaseWriteBarrier
```

### Severity

P3, but important for future maintainability.

---

## 4.2 Resetting worker stop flag through implementation cast

`RestoreMaintenanceMode` does:

```kotlin
(workerLeaseRegistry.get() as? WorkerLeaseRegistryImpl)?.resetStopFlag()
```

This works with current binding, but it leaks implementation knowledge.

### Fix

Add to interface:

```kotlin
interface WorkerLeaseRegistry {
    ...
    fun resetStopFlag()
    fun isStopRequested(): Boolean
}
```

or create separate:

```kotlin
WorkerStopController
```

### Severity

P2 architectural.

---

## 4.3 MaintenanceOperationRunner proceeds after drain timeout

The runner currently logs timeout and proceeds.

For backup maybe arguable. For restore/reset this is unsafe.

### Fix

Add policy:

```kotlin
enum class DrainTimeoutPolicy {
    PROCEED_WITH_WARNING,
    FAIL_OPERATION,
    ENTER_CRITICAL_RECOVERY
}
```

Use:

```text
restore/reset -> FAIL_OPERATION
backup -> FAIL_OPERATION unless proven safe snapshot API
```

### Severity

P1 if runner is wired into restore/reset.

---

# 5. Recommended next implementation order

## PR A — Wire worker drain into destructive operations

Files:

```text
DatabaseBackupRepositoryImpl.kt
MaintenanceOperationRunner.kt
WorkerDrainController.kt
RestoreMaintenanceMode.kt
```

Tasks:

```text
Use MaintenanceOperationRunner or equivalent in:
  createCostBackup
  restoreCostBackup
  importDatabase
  resetDatabase

Do not proceed on drain timeout for restore/reset.
Reset stop flag through interface.
```

Acceptance:

```text
restore_waits_for_running_worker_to_stop
restore_drain_timeout_fails_before_file_swap
reset_drain_timeout_fails_before_delete
backup_drain_timeout_fails_or_uses_safe_snapshot
```

---

## PR B — Fresh Room after restore swap

Files:

```text
DatabaseBackupRepositoryImpl.kt
new RestoreDatabaseOpener.kt
AppDatabase.kt
```

Tasks:

```text
No injected AppDatabase after live DB swap.
Use fresh DB for live verification.
Use fresh DB for receipt asset path update.
Use fresh DB for rollback verification.
```

Acceptance:

```text
restore_live_verification_uses_fresh_room
restore_asset_path_updates_use_fresh_room
injected_database_not_used_after_swap
legacy_import_live_verifier_uses_fresh_room
```

---

## PR C — Worker logging/cancellation semantics

Files:

```text
WorkerExecutionGuard.kt
WorkerRunLogger.kt
BackgroundJobRun.kt
BackgroundJobRunDao.kt
```

Tasks:

```text
Finalize CANCELLED before rethrow.
Log typed skipped reason.
Make restore-blocked attempts visible through safe sink.
Add requiresDatabaseWrite flag.
Fix allowDuringBackupExport semantics.
```

Acceptance:

```text
cancelled_worker_updates_run_CANCELLED
restore_blocked_worker_records_skip_or_safe_sink
allowDuringBackupExport_readonly_worker_can_run_in_backup_mode
```

---

## PR D — Upgrade DAO guard

Files:

```text
scripts/verify_db_access_boundaries.py
config/db_access_allowlist.yml
tests
```

Tasks:

```text
Parse YAML fully.
Enforce class + DAO + method.
Require barrier in same method for repository/worker exceptions.
Enforce debug_only.
Add file-operation patterns.
Reduce broad allowlist.
```

Acceptance:

```text
guard_fails_on_new_unguarded_budgetDao_update_inside_BudgetRepository
guard_fails_on_debug_method_without_BuildConfig_DEBUG
guard_fails_on_db_file_copy_outside_backup_repo
```

---

## PR E — Receiver cleanup

Files:

```text
DismissReminderReceiver.kt
SnoozeReminderReceiver.kt
RecurringLifecycleCoordinator.kt
```

Tasks:

```text
Use goAsync or WorkManager.
Delegate to coordinator.
Barrier check inside coordinator transaction.
No direct DAO writes in receiver.
```

Acceptance:

```text
dismiss_receiver_uses_goAsync
snooze_receiver_uses_goAsync
restore_started_after_receive_blocks_actual_write
```

---

## PR F — Durable maintenance diagnostics

Files:

```text
MaintenanceSafeDiagnosticSink.kt
DataStore ring buffer or BackupRestoreEvent
BudgetMonitor + Notification + Email + Worker guard
```

Tasks:

```text
Make safe sink durable, not Timber-only.
Use it for blocked operations while Room writes are blocked.
```

Acceptance:

```text
restore_blocked_notification_recorded_in_safe_sink
restore_blocked_worker_recorded_in_safe_sink
safe_sink_survives_process_restart
```

---

# 6. Test plan for current commit

Run:

```bash
./gradlew testDebugUnitTest
./gradlew check
./gradlew verifyDbAccessBoundaries
```

Add/verify these targeted tests:

```text
expenseRepository_incrementBackfillAttempts_blocked_during_restore
expenseRepository_conditionallySetLocation_blocked_during_restore
expenseRepository_clearExpenseLocation_blocked_during_restore
expenseRepository_updateMerchantKey_blocked_during_restore

budget_updateExceededNotification_blocked_during_restore
budget_updateCriticalNotification_blocked_during_restore
budget_updateWarningNotification_blocked_during_restore
budget_restoreDebugSnapshot_release_rejected
budget_restoreDebugSnapshot_restore_blocked

legacy_import_rollback_failure_does_not_exit_NORMAL

restore_in_progress_blocks_main_app_shell

worker_checkpoint_stop_requested_throws_cancellation
worker_stop_flag_reset_after_exit_NORMAL
worker_cancellation_finalizes_run_CANCELLED   // currently expected to fail

bank_statement_rechecks_barrier_after_ocr
bank_statement_restore_starts_after_recheck_blocks_later_writes // currently likely fails
```

---

# 7. Revised definition of done for barrier work

The barrier epic should not be considered closed until:

```text
1. Every known direct write gap has barrier tests.
2. RestoreInProgress, RestartRequired, and CriticalRecovery lock the app shell.
3. Backup/restore/reset use worker drain and do not proceed on unsafe timeout.
4. Restore uses fresh Room after DB file swap.
5. Startup recovery verifies safety-restored DB before NORMAL.
6. Worker cancellation finalizes run records.
7. Restore-blocked worker attempts are visible in safe diagnostics.
8. Static DAO guard enforces method-level allowlist and barrier presence.
9. Receivers no longer write DAOs directly.
10. Backup export uses true snapshot or proven drained no-write window.
```

---

# 8. Sources used

- Commit reviewed:  
  https://github.com/panospao7/Cost-agregator/commit/4a0a72543a957121bceed2519428758102d53700

- `RestoreMaintenanceMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `WorkerLeaseRegistryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt

- `MaintenanceOperationRunner.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `ExpenseRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `BudgetRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `BankStatementLifecycleProcessor.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

- `DismissReminderReceiver.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt

- `SnoozeReminderReceiver.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt

- `verify_db_access_boundaries.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/scripts/verify_db_access_boundaries.py

- `db_access_allowlist.yml`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/4a0a72543a957121bceed2519428758102d53700/config/db_access_allowlist.yml