# Global Write / Read / Restore Barrier Evaluation

Commit reviewed: `d70d3d1bafddff07c1f2bae09aba7f13b678869b`  
Commit title: `Global barrier completion: PRs 1-12 after c3bda070`  
Mode: static GitHub/code review only. I did **not** run Gradle/tests locally.

Commit: https://github.com/panospao7/Cost-agregator/commit/d70d3d1bafddff07c1f2bae09aba7f13b678869b

---

## Executive verdict

This commit is a **major improvement** and closes most of the previously remaining global barrier gaps.

Strong fixes now present:

```text
WorkerExecutionGuard:
  - read-only BACKUP_EXPORTING path no longer writes BackgroundJobRun
  - workerRunLogger.start() is inside lease finally path
  - cancellation finalizes run as CANCELLED
  - statusReason added for skipped/cancelled runs

Backup/restore:
  - restore rollback uses fresh Room
  - restore/import rollback failure enters CRITICAL_RECOVERY_REQUIRED
  - startup recovery now runs integrity_check + foreign_key_check + fresh Room open
  - createCostBackup verifies snapshot before bundling
  - restore assets validate receipt row before copy and clean up on failure
  - MaintenanceOperationRunner is wired into backup/restore/import/reset

Diagnostics:
  - MaintenanceSafeDiagnosticSink now has DataStore ring buffer implementation

Reminder receivers:
  - dismiss/snooze use coordinator
  - state transition no-ops added for terminal statuses
  - event insert no longer swallowed with runCatching

Static guard:
  - detects local DAO variables
  - supports expression-bodied functions
  - checks writeBarrier before mutation
  - adds DB file-op guard
```

Overall status: **barrier epic is now ~88–92% complete**.

I would not call it fully closed yet because some gaps are still meaningful:

1. **Backup snapshot is still live-file-copy based**, not true SQLite backup / `VACUUM INTO`.
2. **Public `createSafetyBackup()` still appears callable without maintenance/drain**, and copies the live DB.
3. **Restore asset DB updates are still an implicit internal write during `ASSETS_RESTORING`**, without a formal internal-write scope.
4. **Critical recovery reason is logged but not persisted**, so after restart UI/support may not know why.
5. **MaintenanceSafeDiagnosticSink persists asynchronously via fire-and-forget scope**, so records can be lost if process dies immediately.
6. **Maintenance diagnostics are not exposed through the interface**, only concrete implementation has `observeRecent()`.
7. **Worker run logging still records mostly zero counts**, though status taxonomy is improved.
8. **Static guard is much stronger, but still regex-based and broad allowlists remain.**
9. **Legacy raw `exportDatabase()` and standalone `createSafetyBackup()` remain weaker than `.costbackup`.**
10. **Restore/import verification is stronger, but semantic/dashboard equivalence is still not part of this barrier layer.**

No obvious new P0 bug found from static review. Remaining items are mostly **P1/P2 hardening**.

---

# 1. Confirmed fixes

## 1.1 WorkerExecutionGuard read-only backup-export bug fixed

Previous issue:

```text
read-only worker allowed during BACKUP_EXPORTING still inserted BackgroundJobRun
```

Current code:

```kotlin
if (allowedReadOnly) {
    val result = block()
    return WorkerGuardResult.Success(result)
}
```

This happens after acquiring a lease but before starting a Room-backed run log.

### Status

**Fixed.**

### Remaining caveat

Read-only backup-export worker exceptions are not classified/logged into `BackgroundJobRun`, by design, because Room writes are blocked. They are also not currently recorded to the safe diagnostic sink as failed read-only worker outcomes.

### Suggested small improvement

For read-only backup-export path:

```kotlin
try {
    val result = block()
    diagnosticSink.recordWorkerCompletedReadOnly(...)
    return Success(result)
} catch (e: Exception) {
    diagnosticSink.recordWorkerFailedReadOnly(...)
    ...
}
```

Currently the sink only supports `recordBlockedOperation`, so this needs API expansion.

---

## 1.2 Worker lease leak fixed

Previous issue:

```text
lease acquired before workerRunLogger.start()
if start() throws, lease leaks
```

Current structure:

```kotlin
val lease = leaseRegistry.acquire(request.workerName)
try {
    ...
    val run = workerRunLogger.start(request.workerName)
    ...
} finally {
    lease.close()
}
```

### Status

**Fixed.**

### Test to add

```text
workerRunLogger_start_failure_releases_worker_lease
```

---

## 1.3 Worker cancellation now finalizes run

Current code:

```kotlin
if (e is CancellationException) {
    run.cancelled("coroutine_cancelled_or_maintenance_stop")
    throw e
}
```

`WorkerRunLogger.cancelled()` writes:

```text
status = CANCELLED
statusReason = reason
finishedAt = now
```

### Status

**Fixed for normal logged workers.**

### Remaining caveat

Read-only backup-export workers intentionally do not create `BackgroundJobRun`, so cancellation there is not recorded except by WorkManager/system.

---

## 1.4 Worker status taxonomy improved

`BackgroundJobRun` now has:

```kotlin
statusReason: String?
```

`WorkerRunLogger.skipped()` now writes:

```text
status = SKIPPED
statusReason = reason
```

`cancelled()` writes:

```text
status = CANCELLED
statusReason = reason
```

### Status

**Mostly fixed.**

### Remaining issue

Worker success counts are still usually default zeros because workers do not pass real counts into `run.success(...)`.

Examples likely still weak:

```text
BillReminderWorker sent count
ReceiptMatchingWorker autoMatched/suggested count
DataRetentionWorker per-target counts
LocationBackfillWorker scanned/updated/failed counts
```

### Suggested status

```text
Run taxonomy fixed.
Run metrics still partial.
```

---

## 1.5 Restore rollback now uses fresh Room

Previous issue:

```text
restoreFromSafetyBackup() used injected singleton Room after DB file copy
```

Current code opens:

```kotlin
val freshDb = restoreDatabaseOpener.openFreshDatabase()
try {
    freshDb.openHelper.writableDatabase
    refreshInvalidationTrackerSafelyForVerification(freshDb)
} finally {
    freshDb.close()
}
```

### Status

**Fixed.**

### Remaining caveat

The internal test constructor still uses:

```kotlin
object : RestoreDatabaseOpener { override fun openFreshDatabase() = database }
```

That is probably only for tests, but future agents should be careful: in production, the opener must produce a truly fresh Room instance.

---

## 1.6 Rollback failure enters critical recovery

Current restore path:

```kotlin
restoreMaintenanceMode.enterCriticalRecoveryRequired(
    "Restore verification failed and safety backup rollback also failed"
)
```

Legacy import path:

```kotlin
restoreMaintenanceMode.enterCriticalRecoveryRequired(
    "Import failed after swap and rollback also failed"
)
```

### Status

**Fixed.**

### Remaining issue

`enterCriticalRecoveryRequired(reason)` logs the reason but does not persist it. After process death/restart, only the mode survives, not the reason.

### Fix strategy

Persist:

```text
criticalRecoveryReason
criticalRecoveryTimestamp
```

in `RestoreMaintenanceMode` preferences.

---

## 1.7 Startup recovery verification hardened

Startup recovery now performs:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
fresh Room open
```

If failed:

```kotlin
restoreMaintenanceMode.enterCriticalRecoveryRequired(...)
```

### Status

**Mostly fixed.**

### Remaining caveat

It still does not compare expected counts from the restore journal/manifest. That is acceptable for crash recovery minimum safety, but not semantic equivalence.

### Suggested tests

```text
startup_recovery_integrity_failure_enters_critical
startup_recovery_fk_failure_enters_critical
startup_recovery_room_open_failure_enters_critical
```

---

## 1.8 Backup snapshot verification added

`createCostBackup()` now verifies `tempDb` before bundling:

```kotlin
val snapshotVerification = BackupVerifier.verify(tempDb, tableCounts)
if (!snapshotVerification.passed) fail backup
```

### Status

**Improved.**

### Remaining issue

The snapshot is still created by:

```kotlin
dbFile.inputStream().copyTo(tempDb.outputStream())
```

after checkpoint/drain.

This is better now because workers are drained and writes are blocked, but it is still not a true SQLite backup API / `VACUUM INTO`.

### Why this matters

If any write bypasses the barrier or occurs from a non-worker foreground path during the file copy, the snapshot can still be inconsistent.

### Recommendation

Use one of:

```text
VACUUM INTO snapshot.db
SQLite backup API
```

If unavailable, document the current approach as:

```text
drained no-write file-copy snapshot
```

and keep strong verification.

---

## 1.9 Restore asset cleanup improved

Current asset restore order:

```text
parse receipt ID
verify receipt row exists
copy asset
update DB path
cleanup temp/final on failure
```

Default `db = database` parameter removed; fresh DB is passed explicitly.

### Status

**Much improved.**

### Remaining issue

This still performs DB writes during:

```text
ASSETS_RESTORING
```

which violates the simple global invariant:

```text
writes only in NORMAL
```

It is a legitimate restore-internal write, but it should be formalized.

### Fix strategy

Introduce explicit restore-internal write scope:

```kotlin
RestoreInternalWriteScope.run {
    scannedReceiptDao.update(...)
}
```

or update asset paths in the staged DB before live swap.

---

## 1.10 Maintenance diagnostics now DataStore-backed

`DataStoreMaintenanceSafeDiagnosticSink` added:

```text
DataStore ring buffer
max 200 records
survives process death
```

### Status

**Mostly fixed.**

### Remaining issues

1. `recordBlockedOperation()` is fire-and-forget:

```kotlin
scope.launch { DataStore.edit { ... } }
```

If the process dies immediately, the record can be lost.

2. The interface only exposes:

```kotlin
recordBlockedOperation(...)
```

The concrete class has:

```kotlin
observeRecent()
clearOlderThan()
```

but callers depending on the interface cannot access them.

3. `reason` is always `"BLOCKED"`, not a typed reason like:

```text
RESTORE_BLOCKED
BACKUP_EXPORTING
READ_BARRIER_DENIED
WRITE_BARRIER_DENIED
```

### Suggested fix

Make API:

```kotlin
suspend fun recordBlockedOperation(...)
fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>>
suspend fun clearOlderThan(...)
```

or add a separate repository interface.

---

## 1.11 Reminder dismiss/snooze improved

Current coordinator has:

```kotlin
ReminderActionResult.Updated
ReminderActionResult.NoOp(reason)
ReminderActionResult.NotFound
```

Terminal statuses:

```text
DISMISSED
CANCELLED
FAILED_FINAL
SENT
```

Events are inserted inside the same transaction, no `runCatching` swallow.

### Status

**Barrier + basic lifecycle semantics fixed.**

### Remaining issues

1. `CLAIMED` is not terminal, so dismissing a claimed delivery becomes `DISMISSED`. That may be acceptable, but the notification-send path must re-check state after claim.

2. Snoozing `CLAIMED` becomes `SNOOZED`. This needs an explicit product decision:
   - allowed as “undo claim and snooze”
   - or should be `NoOp`.

3. No notification cancellation is shown when dismissing/snoozing an already-posted notification.

These are more Pipeline 4/9 reminder lifecycle issues than global barrier issues.

---

## 1.12 Static guard upgraded

Current script now claims and appears to implement:

```text
local DAO variable detection
expression-bodied function handling
barrier-before-mutation check
debug_only BuildConfig.DEBUG check
file-op guard
```

### Status

**Strong improvement.**

### Remaining limitations

Still regex-based, so it can miss complex Kotlin patterns:

```text
multi-line DAO assignment
DAO stored in property with non-Dao name
generic repository wrappers
higher-order function writes
alias imports
transactions hidden behind helper methods
```

Also, broad allowlist entries remain:

```yaml
requires_write_barrier: false
```

for important classes, including:

```text
NotificationRepository
ReceiptLifecycleCoordinator
DataRetentionWorker
RecurringOccurrenceMaterializer
DatabaseBackupRepositoryImpl
```

Some are valid, but they reduce guard strength.

### Recommendation

Longer term: replace or supplement with a Detekt custom rule.

---

# 2. New or remaining issues after this commit

## ISSUE A — Public `createSafetyBackup()` is still not maintenance/drain guarded

### Severity

P1/P2.

### Evidence

`createSafetyBackup()`:

```text
checkpoint WAL
copy live DB file
cleanup old backups
```

It does not enter maintenance mode or drain workers itself.

It is safe when called from restore/reset after `enterAndDrain()`, but it is still a public repository method and could be called independently.

### User impact

If called standalone while writes are happening, it can produce an inconsistent safety backup.

### Fix strategy

Split into two methods:

```kotlin
private suspend fun createSafetyBackupInternalAssumingMaintenance(): Result<File>

override suspend fun createSafetyBackup(): Result<File> =
    maintenanceOperationRunner.runExclusive(
        mode = BACKUP_EXPORTING,
        operationName = "createSafetyBackup",
        drainTimeoutPolicy = FAIL_OPERATION
    ) {
        createSafetyBackupInternalAssumingMaintenance()
    }
```

For restore/reset, call the internal method.

### Tests

```text
standalone_createSafetyBackup_enters_BACKUP_EXPORTING
standalone_createSafetyBackup_drains_workers
reset_uses_internal_safety_backup_without_reentering_mode
```

---

## ISSUE B — `exportDatabase()` legacy raw export is still weaker

### Severity

P2/P1 if reachable in debug/testing.

### Evidence

Legacy `exportDatabase()` remains debug-only, but it still:

```text
check privacy
checkpoint WAL
copy DB
optional sanitize/encrypt
```

No maintenance mode/drain.

### Impact

Debug exports can be inconsistent and may not follow the new barrier model.

### Fix strategy

Either:

1. Keep debug-only and explicitly document as unsafe dev tool, or
2. Route through `createCostBackup()` / MaintenanceOperationRunner, or
3. Delete raw DB export entirely.

Recommended:

```text
delete or make internal debug repair-only
```

---

## ISSUE C — Backup snapshot verification uses table counts derived from the copied snapshot

### Severity

P2.

### Evidence

`createCostBackup()` builds `tableCounts` by opening `tempDb`, then verifies `tempDb` against those same counts.

### Impact

This is good for checking integrity/FK/semantic rules, but it cannot prove equivalence to the intended live DB at snapshot start because expected counts come from the snapshot itself.

This is not necessarily wrong, but the name “expected table counts” can be misleading.

### Fix strategy

If you want stronger snapshot equivalence:

```text
capture live counts immediately before snapshot under drained/no-write mode
copy snapshot
verify snapshot counts == live counts
```

Current approach is acceptable if documented as:

```text
verify copied snapshot is internally valid
```

not:

```text
prove source/live equivalence
```

---

## ISSUE D — Restore-internal asset DB writes need formal policy

### Severity

P1/P2.

### Current behavior

During restore:

```text
mode = ASSETS_RESTORING
freshDb.scannedReceiptDao().update(...)
```

This is a DB write outside `NORMAL`, but it is intentional.

### Problem

Static guard allows it because `DatabaseBackupRepositoryImpl` is broadly allowlisted. There is no code-level indication that this is a sanctioned internal restore write.

### Fix strategy

Add:

```kotlin
class RestoreInternalWriteScope
```

or function:

```kotlin
private suspend fun runRestoreInternalWrite(operation: String, block: suspend () -> Unit)
```

and use it for asset path updates.

Acceptance test:

```text
only_restore_repository_can_use_restore_internal_write_scope
restore_asset_update_marked_internal_write
```

---

## ISSUE E — Critical recovery reason not persisted

### Severity

P2.

### Current behavior

`enterCriticalRecoveryRequired(reason)` logs reason but persists only mode.

### Impact

After restart, UI/support can see critical mode but not why.

### Fix

Persist:

```text
criticalRecoveryReason
criticalRecoveryTimestamp
criticalRecoveryOperation
```

Expose via `AppOperationalState.CriticalRecoveryRequired(reason)`.

---

## ISSUE F — Maintenance diagnostic sink is async and interface-incomplete

### Severity

P2.

### Problems

```text
fire-and-forget persistence may lose records
observeRecent/clearOlderThan only on concrete class
reason always BLOCKED
```

### Fix

Change interface:

```kotlin
interface MaintenanceSafeDiagnosticSink {
    suspend fun recordBlockedOperation(...)
    fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>>
    suspend fun clearOlderThan(cutoffMs: Long)
}
```

Add reason parameter:

```kotlin
reason: String = "BLOCKED"
```

---

## ISSUE G — `MaintenanceOperationRunner.runExclusive()` is unsafe for multi-stage destructive operations

### Severity

P2.

### Evidence

`runExclusive()` exits maintenance in `catch` with:

```kotlin
restoreMaintenanceMode.exit(forceRestartRequired = requireRestartAfterSuccess)
```

If someone uses it around a mid-swap restore operation, it could exit incorrectly after partial mutation.

Current backup repo uses `enterAndDrain()` manually, so this is not an immediate bug.

### Fix

Either:

1. Remove `runExclusive()` if not used for destructive restore, or
2. Add explicit failure policy:

```kotlin
onFailureMode: FailureMaintenancePolicy
```

Example:

```text
NORMAL_SAFE
RESTART_REQUIRED
CRITICAL_RECOVERY_REQUIRED
LEAVE_CURRENT_MODE
```

---

## ISSUE H — AppStartupCoordinator resets maintenance mode after recovery success but reason/journal semantics are thin

### Severity

P2.

### Current behavior

After successful safety restore verification:

```text
clean staging
delete journal
then reset NORMAL later
```

This is acceptable.

### Caveat

If asset restore crashed in `ASSETS_RESTORING`, previous design wanted resumable asset repair. Current startup treats some non-destructive states as cleanable. Asset restore resumability is still not implemented.

This is more P7 asset lifecycle than core barrier, but still relevant.

---

## ISSUE I — Worker run counts remain mostly zero

### Severity

P2.

### Current state

`WorkerRunLogger` supports counts, but `WorkerExecutionGuard` always calls:

```kotlin
run.success()
```

with defaults.

### Fix

Add `WorkerRunContext` later:

```kotlin
runGuarded(request) { ctx ->
    ctx.addRowsScanned(...)
    ctx.addRowsUpdated(...)
}
```

This is not required for the barrier invariant, but needed for diagnostics.

---

# 3. Updated status table

| Area | Status after `c3bda070` | Status after `d70d3d1` |
|---|---:|---:|
| Expense/Budget known write gaps | fixed | fixed |
| RestoreInProgress app lock | fixed | fixed |
| Worker cancellation finalization | mostly fixed | fixed for logged workers |
| Read-only backup worker Room logging | open | fixed |
| Worker lease leak on logger start failure | open | fixed |
| Worker status reason | partial | mostly fixed |
| Worker counts | open | still open |
| Worker drain wired to backup/restore/reset | mostly fixed | fixed |
| Maintenance runner duplication | open | mostly fixed |
| Fresh Room after restore swap | mostly fixed | fixed |
| Fresh Room rollback | open | fixed |
| Rollback failure critical mode | open | fixed |
| Startup recovery FK check | open | fixed |
| Backup snapshot true SQLite snapshot | open | still open |
| Snapshot verification | partial | improved |
| Restore asset cleanup | partial | improved |
| Restore-internal write formal scope | open | still open |
| Maintenance diagnostics durability | open | partial fixed |
| Static DAO guard | partial | improved, still regex-limited |
| Reminder receivers direct writes | fixed | fixed |
| Reminder transition semantics | weak | improved |

---

# 4. Recommended next PRs

## PR 1 — Safety backup and raw export consistency

### Tasks

```text
- Make public createSafetyBackup() enter BACKUP_EXPORTING and drain workers.
- Add private createSafetyBackupInternalAssumingMaintenance().
- Either route exportDatabase() through maintenance or mark it debug-unsafe/internal.
```

### Acceptance tests

```text
standalone_createSafetyBackup_drains_workers
standalone_createSafetyBackup_blocks_writes
reset_uses_internal_safety_backup_without_reentering
raw_export_debug_either_drains_or_is_blocked
```

---

## PR 2 — True SQLite snapshot

### Tasks

```text
- Try VACUUM INTO for .costbackup snapshot.
- If unsupported, keep drained file-copy but document fallback.
- Compare live counts captured under drain with snapshot counts.
```

### Acceptance tests

```text
backup_snapshot_uses_vacuum_into_when_supported
snapshot_counts_match_live_counts_under_drain
backup_fails_if_snapshot_count_mismatch
```

---

## PR 3 — Formal restore-internal write scope

### Tasks

```text
- Add RestoreInternalWriteScope.
- Use it for receipt asset path updates.
- Remove broad implicit exception where possible.
```

### Acceptance tests

```text
asset_restore_db_update_runs_in_restore_internal_scope
normal_code_cannot_use_restore_internal_scope
static_guard_allows_only_restore_internal_scope_for_non_NORMAL_writes
```

---

## PR 4 — Persist critical recovery reason

### Tasks

```text
- Store reason/timestamp in RestoreMaintenanceMode prefs.
- Add to AppOperationalState.
- Show in lock UI.
```

### Acceptance tests

```text
critical_recovery_reason_survives_restart
critical_recovery_ui_shows_reason
```

---

## PR 5 — MaintenanceSafeDiagnosticSink API completion

### Tasks

```text
- Make recordBlockedOperation suspend or add flush mechanism.
- Add observeRecent/clearOlderThan to interface.
- Add typed reason.
```

### Acceptance tests

```text
blocked_record_flushes_before_return
observe_recent_available_via_interface
blocked_reason_is_specific
```

---

## PR 6 — Worker run context/counts

### Tasks

```text
- Add WorkerRunContext.
- Migrate BillReminderWorker, ReceiptMatchingWorker, DataRetentionWorker, LocationBackfillWorker.
```

### Acceptance tests

```text
bill_worker_records_notificationsSent
data_retention_records_rowsUpdated
receipt_matching_records_autoMatched_suggested
location_backfill_records_scanned_updated
```

---

## PR 7 — Static guard hardening / Detekt path

### Tasks

```text
- Add tests for multi-line local DAO assignment.
- Add tests for barrier after mutation.
- Reduce broad requires_write_barrier:false allowlist.
- Consider Detekt custom rule.
```

---

# 5. Is the global barrier epic closed?

## My current recommendation

Do **not** mark fully closed yet.

Mark as:

```text
Global Barrier: Mostly implemented / hardening remaining
```

or:

```text
Status: GREEN-YELLOW
```

### Required before “closed”

I would require at least these:

```text
1. Public createSafetyBackup is maintenance/drain guarded or made internal.
2. Backup snapshot uses VACUUM INTO/SQLite backup API or documented verified drained fallback.
3. Restore-internal asset DB write is formalized.
4. Critical recovery reason is persisted.
5. MaintenanceSafeDiagnosticSink can be observed through interface and records synchronously enough for critical paths.
6. Worker run counts are either explicitly deferred or implemented for core workers.
```

### Already good enough for many local pipeline fixes

Yes: after this commit, the barrier foundation is strong enough that agents can start safely implementing other universal themes, as long as they do not rely on backup snapshot being perfect yet.

---

# 6. Suggested tracker update

```text
Write/read/restore barrier:
  Status: Mostly fixed / hardening remaining
```

Remaining tracker items:

| ID | Severity | Title |
|---|---:|---|
| BARRIER-REM-01 | P1/P2 | Public `createSafetyBackup()` lacks maintenance/drain guard |
| BARRIER-REM-02 | P1/P2 | `.costbackup` snapshot still file-copy based, not SQLite backup/VACUUM INTO |
| BARRIER-REM-03 | P1/P2 | Restore asset DB updates need formal restore-internal write scope |
| BARRIER-REM-04 | P2 | Critical recovery reason not persisted |
| BARRIER-REM-05 | P2 | Maintenance diagnostics are async and interface-incomplete |
| BARRIER-REM-06 | P2 | Worker run counts still mostly zero |
| BARRIER-REM-07 | P2 | Static guard remains regex-based with broad allowlists |
| BARRIER-REM-08 | P2 | Legacy raw export path weaker than `.costbackup` |

---

# 7. Sources used

- Commit reviewed:  
  https://github.com/panospao7/Cost-agregator/commit/d70d3d1bafddff07c1f2bae09aba7f13b678869b

- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `WorkerRunLogger.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt

- `BackgroundJobRun.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `RestoreMaintenanceMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `MaintenanceOperationRunner.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt

- `DataStoreMaintenanceSafeDiagnosticSink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/backup/DataStoreMaintenanceSafeDiagnosticSink.kt

- `MaintenanceSafeDiagnosticSink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt

- `AppStartupCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `RecurringLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

- `verify_db_access_boundaries.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/scripts/verify_db_access_boundaries.py

- `db_access_allowlist.yml`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/d70d3d1bafddff07c1f2bae09aba7f13b678869b/config/db_access_allowlist.yml