I reviewed commit `51cdb7d197146a17eb8c1fdda359a54718593ab6` against the Global Write/Read/Restore Barrier plan. Here is the deep static debug report.

<global_barrier_evaluation_51cdb7d.md>
# Global Write / Read / Restore Barrier Evaluation

Commit reviewed: `51cdb7d197146a17eb8c1fdda359a54718593ab6`  
Commit title: `Implement global write/read/restore barrier (PRs 1-11)`  
Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

---

## Executive verdict

This commit is a **large and valuable foundation commit**, but the barrier work is **not complete** yet.

It adds important infrastructure:

```text
DatabaseAccessModels
DatabaseWriteBarrier v2
DatabaseReadBarrier v2
RestoreMaintenanceMode modes + operational state
AppOperationalState
AppOperationalLockScreen
MaintenanceOperationRunner
WorkerLeaseRegistry / WorkerDrainController
WorkerExecutionGuard lease acquisition
MaintenanceSafeDiagnosticSink
Export read barriers
DAO access static guard
ExpenseReadStore / ExpenseWriteStore
DB ownership docs
```

However, several core guarantees from the implementation plan are **still not true**.

Most important remaining risks:

1. **Known write gaps still exist** despite the PR claiming a write-barrier sweep.
   - `ExpenseRepository.conditionallySetLocation`
   - `clearExpenseLocation`
   - `incrementBackfillAttempts`
   - `updateMerchantKey`
   - `BudgetRepository.updateExceededNotification`
   - `updateCriticalNotification`
   - `updateWarningNotification`
   - `restoreDebugSnapshot`

2. **Worker drain is not actually wired into backup/restore/reset operations.**
   - `MaintenanceOperationRunner` exists but `DatabaseBackupRepositoryImpl` does not use it.
   - `RestoreMaintenanceMode.enter()` still only calls `WorkManager.cancelUniqueWork()`, which is async.

3. **Worker stop flag is never reset after drain.**
   - If `WorkerLeaseRegistryImpl.requestStopAndAwaitDrain()` is used, future worker lease checkpoints can cancel forever unless `resetStopFlag()` is called.

4. **Existing workers’ `executionGuard.checkpoint()` does not use the active lease.**
   - So the new stop-request flag is not seen by existing checkpoint calls.

5. **Restore still uses stale injected Room after DB file swap.**
   - Live verification and receipt asset path updates still use the constructor-injected `database`.

6. **Legacy debug import rollback failure can still exit to NORMAL.**
   - If import fails after destination mutation and rollback also fails, code still calls `restoreMaintenanceMode.exit(forceRestartRequired = false)`.

7. **Global operational lock does not block `RestoreInProgress`.**
   - UI continues for `RestoreInProgress` and `BackupExporting`.

8. **Static DAO guard is too weak.**
   - It is class-level, ignores `methods_only`, ignores specific DAO names, and cannot prove allowlisted classes actually call `DatabaseWriteBarrier`.

9. **Backup export still copies the live DB file.**
   - No SQLite backup API / `VACUUM INTO`.
   - No proven active-worker drain before snapshot.

10. **Several long-running operations check the barrier only once at entry.**
    - If restore starts mid-operation, later writes can still happen.

Current status: **partial foundation, not production-closed**.

---

# 1. PR-by-PR reconciliation

## PR 1 — Harden barrier APIs

### Status

**Mostly implemented, good foundation.**

Added:

```text
DatabaseAccessType
DatabaseReadPolicy
DatabaseAccessOperation
DatabaseAccessBlockedException
DatabaseWriteBarrier structured API
DatabaseReadBarrier structured API
```

### Good

The desired write rule exists:

```text
writes allowed only in RestoreMaintenanceMode.Mode.NORMAL
```

The desired read policy exists:

```text
NORMAL_APP_READ -> only NORMAL
EXPORT_OR_BACKUP_SNAPSHOT_READ -> NORMAL or BACKUP_EXPORTING
RESTORE_INTERNAL_STAGED_DB_READ -> always false for app singleton
```

### Remaining issues

1. Generic helper functions are useful but not widely adopted:

```kotlin
runWrite(...)
runRead(...)
```

2. No operation-level diagnostic emission on blocked access.

3. The old string overloads remain widely used, so structured `pipeline/entity/reason` metadata is often absent.

### Verdict

Infrastructure: **good**.  
Adoption: **early**.

---

## PR 2 — Global app operational lock

### Status

**Partial.**

Added:

```text
AppOperationalState
MainViewModel.operationalState
MainActivity AppOperationalLockScreen
```

### Good

The app now blocks for:

```text
RestartRequiredAfterRestore
CriticalRecoveryRequired
```

This is a real improvement.

### Critical issue

`MainActivity` explicitly allows the app to continue for:

```text
Normal
BackupExporting
RestoreInProgress
```

Code path:

```kotlin
else -> { /* Normal / BackupExporting / RestoreInProgress — app continues */ }
```

This violates the plan:

```text
RESTORE_*:
  app shell blocks navigation/actions
```

### User impact

During active restore stages, screens can remain visible and flows can continue. Runtime barriers may block many writes, but:

- not all writes are guarded,
- many reads are not guarded,
- stale Room state can still be observed,
- UI actions can race restore.

### Fix strategy

Change app shell policy:

```kotlin
when (state) {
    AppOperationalState.Normal -> renderApp()
    AppOperationalState.BackupExporting -> renderAppWithNonBlockingBanner()
    is AppOperationalState.RestoreInProgress -> AppOperationalLockScreen(...)
    AppOperationalState.RestartRequiredAfterRestore -> AppOperationalLockScreen(...)
    AppOperationalState.CriticalRecoveryRequired -> AppOperationalLockScreen(...)
}
```

### Verdict

Restart-required lock: **mostly fixed**.  
Restore-in-progress lock: **not fixed**.

---

## PR 3 — Lifecycle-safe destructive DB operations

### Status

**Partial.**

Added:

```text
MaintenanceOperationRunner
RESETTING_DATABASE mode
resetDatabase() maintenance mode + journal + restart-required
```

### Good

`resetDatabase()` is improved:

```text
enter RESETTING_DATABASE
create safety backup
journal
close DB
delete DB/WAL/SHM
commit journal
exit restart-required
```

This is much safer than before.

### Critical issue 1 — MaintenanceOperationRunner is unused

`DatabaseBackupRepositoryImpl` does **not** inject or use `MaintenanceOperationRunner`.

So backup/restore/reset paths still manually call:

```kotlin
restoreMaintenanceMode.enter(...)
restoreMaintenanceMode.exit(...)
```

and do not get centralized worker-drain semantics.

### Critical issue 2 — reset still does not drain workers

`resetDatabase()` enters maintenance mode, but `RestoreMaintenanceMode.enter()` only cancels WorkManager unique work asynchronously.

No active worker drain is awaited.

### Critical issue 3 — reset does not verify new DB state

After deleting DB/WAL/SHM, reset exits restart-required without verifying:

```text
new DB can be recreated
Room can open cleanly
schema is valid
```

Maybe restart will recreate DB, but the operation itself does not prove success.

### Critical issue 4 — rollback failure path remains unsafe in legacy import

In `importDatabase()`, if destination files were mutated and rollback fails, the code still calls:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = false)
```

before returning failure.

This reopens writes after a failed import + failed rollback.

### Fix strategy

1. Use `MaintenanceOperationRunner` for:

```text
createCostBackup
restoreCostBackup
importDatabase
resetDatabase
```

2. Make destructive operation failure policy explicit:

```text
if destination files were mutated and rollback failed:
  enter/exit to RESTORE_COMPLETE_RESTART_REQUIRED or CRITICAL_RECOVERY_REQUIRED
  never NORMAL
```

3. Add reset verification or document restart-only verification.

### Verdict

Reset is **improved**, but destructive operation contract is **not globally enforced**.

---

## PR 4 — Worker drain contract

### Status

**Partial / currently unsafe if used as intended.**

Added:

```text
WorkerLease
WorkerLeaseRegistry
WorkerDrainController
WorkerLeaseRegistryImpl
WorkerExecutionGuard acquires/releases lease
```

### Good

The design direction is correct.

### Critical issue 1 — stop flag is not reset

`WorkerLeaseRegistryImpl` has:

```kotlin
fun resetStopFlag()
```

but I did not see it called from `RestoreMaintenanceMode.exit()` or `MaintenanceOperationRunner`.

Once `requestStopAll()` is called, future lease checkpoints will keep throwing `CancellationException`.

### User impact

If `MaintenanceOperationRunner` or `WorkerDrainController` is used in runtime, future workers may be permanently cancelled at checkpoints until process restart or manual reset.

### Critical issue 2 — existing worker checkpoints do not use leases

`WorkerExecutionGuard.checkpoint(operation)` currently does:

```kotlin
writeBarrier.checkWritesAllowed(operation)
yield()
```

It does **not** delegate to the active `WorkerLease`.

So workers that call:

```kotlin
executionGuard.checkpoint(...)
```

do not observe:

```text
WorkerLeaseRegistryImpl.stopRequested
```

They only observe write barrier mode.

### User impact

The new drain stop flag does not actually stop existing long-running workers at checkpoints.

### Critical issue 3 — drain is not used by backup/restore/reset

Even if fixed, `DatabaseBackupRepositoryImpl` does not currently use the runner/drain.

### Critical issue 4 — `WorkerExecutionGuard` still checks write barrier before logging

The code checks:

```kotlin
writeBarrier.checkWritesAllowed(request.workerName)
```

before:

```kotlin
leaseRegistry.acquire(...)
workerRunLogger.start(...)
```

So restore-blocked worker attempts still do not create `BackgroundJobRun`.

### Critical issue 5 — cancellation still leaves run row unfinished

`WorkerExecutionGuard` still does:

```kotlin
if (e is CancellationException) throw e
```

before finalizing the run as cancelled.

### Critical issue 6 — `allowDuringBackupExport` remains ineffective

Because write barrier is checked before backup-export policy:

```text
BACKUP_EXPORTING -> writeBarrier denies
```

So `allowDuringBackupExport=true` cannot work for read-only workers.

### Fix strategy

1. Add per-run context:

```kotlin
class WorkerRunContext(
    val lease: WorkerLease,
    val runLogger: WorkerRunHandle
)
```

2. Change worker block signature:

```kotlin
runGuarded(request) { ctx ->
    ctx.checkpoint("...")
}
```

3. Make `WorkerExecutionGuard.checkpoint()` either removed or bound to coroutine-local lease.

4. Reset stop flag on maintenance exit:

```kotlin
workerLeaseRegistry.resetStopFlag()
```

5. Add `requiresDatabaseWrite` to `WorkerGuardRequest`.

6. Log cancelled run before rethrow.

### Verdict

The lease infrastructure is useful, but **worker drain is not functionally complete**.

---

## PR 5 — Approved writer ownership map

### Status

**Documentation added, useful but not enforced enough.**

Added:

```text
docs/DB_WRITE_OWNERSHIP.md
config/db_access_allowlist.yml
```

### Good

The ownership map is valuable and should stay.

### Problem

The allowlist contains many entries that are aspirational or temporary, including classes where the write barrier is **not actually present**.

Examples:

```text
ExpenseRepository maintenance methods
BudgetRepository notification timestamp methods
BudgetRepository restoreDebugSnapshot
```

The docs say every write entrypoint checks the barrier, but current code contradicts this.

### Verdict

Good roadmap, but current allowlist gives a false sense of closure.

---

## PR 6 / PR 10 — Static DAO mutation guard

### Status

**Present but weak.**

Added:

```text
scripts/verify_db_access_boundaries.py
Gradle verifyDbAccessBoundaries task
pytest tests
CI failure mode
```

### Good

Having a guard is a major step forward.

### Critical weaknesses

#### 1. Class-level allowlist only

If a class is allowlisted, **all DAO mutations in that file pass**, regardless of:

```text
method name
DAO name
whether writeBarrier is called
debug-only condition
```

So `methods_only` and `daos` in YAML are not enforced.

#### 2. It cannot verify barrier usage

The script checks only syntax like:

```text
expenseDao.update(...)
```

It does not check that nearby code calls:

```text
writeBarrier.checkWritesAllowed(...)
```

#### 3. It misses many patterns

It only scans lines containing `"Dao"` and mutation-looking method names.

Possible misses:

```text
variable named dao
database.someDao().update(...)
repository methods that mutate internally
raw SQL execSQL
Room @Transaction helper writes
file-level DB operations
```

#### 4. The allowlist is very broad

Many repository/coordinator classes are allowlisted wholesale. This reduces the guard’s value.

#### 5. The production “passes” test mostly proves allowlist coverage

The test:

```text
test_production_codebase_has_no_violations
```

does not prove correct barriers; it proves the scanner found no unallowlisted syntactic calls.

### Fix strategy

Upgrade guard in phases:

#### Phase 1

Enforce YAML fields:

```text
class
dao variable names
method names
reason
allowed_until
debug_only
```

#### Phase 2

Require same method contains:

```text
writeBarrier.checkWritesAllowed
```

for allowlisted write methods unless the class is a lifecycle coordinator that centrally guards entrypoints.

#### Phase 3

Add forbidden raw SQL/file operation patterns:

```text
execSQL
deleteRecursively
renameTo
copyTo DB file
openDatabase
writableDatabase after swap
```

#### Phase 4

Reduce broad allowlist.

### Verdict

The static guard is useful as a **warning scaffold**, not yet a strong CI enforcement tool.

---

## PR 7 — Write-barrier sweep

### Status

**Partial; several explicit gaps remain.**

### Confirmed misses

#### ExpenseRepository maintenance/backfill writes are still unguarded

Current code still has:

```kotlin
suspend fun conditionallySetLocation(...) =
    expenseDao.conditionallySetLocation(...)

suspend fun clearExpenseLocation(expenseId: Long) =
    expenseDao.clearLocation(expenseId)

suspend fun incrementBackfillAttempts(expenseId: Long) =
    expenseDao.incrementBackfillAttempts(expenseId)

suspend fun updateMerchantKey(expenseId: Long, merchantKey: String) =
    expenseDao.updateMerchantKey(expenseId, merchantKey)
```

No `writeBarrier.checkWritesAllowed()` in these methods.

This directly violates the global plan.

#### BudgetRepository notification timestamp writes are still unguarded

Current code:

```kotlin
suspend fun updateExceededNotification(id: Long, timestamp: Long) {
    budgetDao.updateExceededNotification(id, timestamp)
}
suspend fun updateCriticalNotification(id: Long, timestamp: Long) {
    budgetDao.updateCriticalNotification(id, timestamp)
}
suspend fun updateWarningNotification(id: Long, timestamp: Long) {
    budgetDao.updateWarningNotification(id, timestamp)
}
```

No write barrier.

#### BudgetRepository restoreDebugSnapshot still unguarded and not debug-gated

Current code:

```kotlin
suspend fun restoreDebugSnapshot(snapshot: DebugBudgetSnapshot): Result {
    if (snapshot.budgets.isNotEmpty()) {
        budgetDao.replaceAllAndEnforceActiveScopes(snapshot.budgets)
    } else {
        budgetDao.deleteAll()
    }
}
```

Missing:

```text
BuildConfig.DEBUG guard
writeBarrier.checkWritesAllowed()
```

#### Long-running BankStatementLifecycleProcessor only checks once at entry

It now checks `DatabaseWriteBarrier` at method entry, which is good.

But after OCR/AI/parsing, it writes:

```text
scannedReceipt
receipt events
pending reviews
receipt status update
```

without re-checking before the write block.

If restore starts while OCR/AI/parsing is running, later writes can still happen.

#### Reminder receivers check once before runBlocking

`DismissReminderReceiver` and `SnoozeReminderReceiver` check the barrier before `runBlocking`, then write DAOs inside.

If restore begins after the check and before DAO writes, writes still happen.

They also still write DAOs directly instead of using coordinator methods.

### Fix strategy

1. Patch known direct methods immediately.

2. For long-running methods, use:

```text
check barrier immediately before every DB write section
or wrap the final DB write section in writeBarrier.runWrite(...)
```

3. Move receivers to coordinator + `goAsync()` / WorkManager.

### Verdict

The sweep is **not complete**. Some previously identified high-risk gaps remain exactly open.

---

## PR 8 — Read-barrier sweep

### Status

**Partial / good for export repository path.**

### Good

`ExportDataRepository` now checks `DatabaseReadBarrier` for:

```text
getExpensesBetween
getExpensesBetweenForExport
countExpensesBetween
getExpensesPage
getCategoryNameMap
```

`AccountingExportRepository.exportExpenses()` checks read barrier.

`ExportOptionsViewModel` checks read barrier.

### Remaining issues

#### 1. No ExportCoordinator

Read/privacy policy still lives across:

```text
ViewModel
ExportDataRepository
AccountingExportRepository
```

rather than a single `ExportCoordinator`.

#### 2. Normal app reads are mostly unguarded

This may be acceptable if the app shell blocks restore, but the app shell currently does **not** block `RestoreInProgress`.

#### 3. `EXPORT_OR_BACKUP_SNAPSHOT_READ` allows reads during `BACKUP_EXPORTING`

That is intentional for backup/export snapshot reads, but the policy is broad. It allows export reads during backup export, which may be okay but should be explicit.

#### 4. Snapshot consistency is still not true

The repo comments say deterministic pager anchors a fixed set of IDs, but the underlying implementation still appears keyset/paged rather than a durable snapshot table.

### Fix strategy

1. Add `ExportCoordinator`.

2. Block app UI during `RestoreInProgress`.

3. Add export snapshot table if actual row-count/row consistency is required.

### Verdict

Export read path is **materially improved**, but read barrier is not globally solved.

---

## PR 9 — Maintenance-safe diagnostic sink

### Status

**Partial.**

Added:

```text
MaintenanceSafeDiagnosticSink
TimberMaintenanceSafeDiagnosticSink
BudgetMonitor integration
```

### Good

This is the correct abstraction.

### Limitations

1. Current implementation is Timber-only, so it is not durable.

2. Only `BudgetMonitor` appears migrated.

3. Other