# Pipeline 7 Implementation Plan — Backup / Restore

**HEAD:** `10d5ee24`

## PR1 — P0-01: Guard legacy .db import in release builds

**Priority:** P0 / Critical
**Files:** `DatabaseBackupRepositoryImpl.kt`

### Problem
`importDatabase()` already has `RestoreMaintenanceMode` and `RestoreJournal` in the code (despite stale comment claims), but it's callable in release builds with no `BuildConfig.DEBUG` guard.

### Fix
Add `BuildConfig.DEBUG` guard at method entry:
```kotlin
if (!BuildConfig.DEBUG) {
    return Result.failure(UnsupportedOperationException("Legacy .db import disabled in release. Use .costbackup restore."))
}
```
Production restores must go through `restoreCostBackup()`.

---

## PR2 — P1-02/P1-03: Block writes during backup snapshot acquisition

**Priority:** P1 / High
**Files:** `RestoreMaintenanceMode.kt`

### Problem
`isWritesAllowed()` returns `true` in `BACKUP_EXPORTING` mode. `createCostBackup()` enters this mode, WAL-checkpoints, then copies the live DB file. Concurrent writes after checkpoint produce an inconsistent snapshot.

### Fix
Change `isWritesAllowed()` to return `false` for `BACKUP_EXPORTING`:
```kotlin
fun isWritesAllowed(): Boolean {
    val current = readMode()
    return current == Mode.NORMAL
}
```
The snapshot window is brief (WAL checkpoint → file copy → exit) so the freeze is minimal. After the file copy completes, `exit(forceRestartRequired = false)` returns to NORMAL and writes resume.

---

## PR3 — P1-07: Replace hardcoded scheduleAllWorkers with WorkerRegistry

**Priority:** P1 / High
**Files:** `RestoreMaintenanceMode.kt`, `AppStartupCoordinator.kt`

### Problem
`pauseAllWorkers()` iterates `WorkerSpec.DEFAULTS.keys` (dynamic) but `scheduleAllWorkers()` and `AppStartupCoordinator.scheduleStartupWork()` are both hardcoded lists of 7 workers. Adding a new worker requires updating 2-3 places.

### Fix
Create a simple registry:
```kotlin
object WorkerRegistry {
    data class Entry(val specName: String, val schedule: (Context) -> Unit)
    
    val entries: List<Entry> = listOf(
        Entry("location_backfill") { LocationBackfillWorker.schedule(it) },
        Entry("merchant_key_backfill") { MerchantKeyBackfillWorker.schedule(it) },
        Entry("warranty_expiration") { WarrantyExpirationWorker.schedule(it) },
        Entry("data_retention") { DataRetentionWorker.schedule(it) },
        Entry("bill_reminder_periodic") { BillReminderWorker.schedule(it) },
        Entry("receipt_matching") { ReceiptMatchingWorker.schedule(it) },
        Entry("ai_daily_briefing") { WorkerSpecScheduler.scheduleAtMidnight(it, "ai_daily_briefing", DailyBriefingWorker::class.java) }
    )
}
```
Replace hardcoded `scheduleStartupWork()` and `scheduleAllWorkers()` with `WorkerRegistry.entries.forEach { it.schedule(context) }`.

---

## PR4 — P1-04: Add asset restore journal states

**Priority:** P1 / High
**Files:** `RestoreJournal.kt`, `DatabaseBackupRepositoryImpl.kt`

### Problem
Receipt asset restore happens after DB verification under best-effort semantics. If the process crashes mid-asset-restore, the journal is in `VERIFYING` state, which may roll back a valid DB on restart.

### Fix
1. Add `ASSETS_RESTORING` journal state between `VERIFYING` and `COMPLETE`:
   - After DB verification succeeds, transition to `ASSETS_RESTORING`
   - After assets restored, transition to `COMPLETE`
2. On crash recovery with `ASSETS_RESTORING` state: do NOT rollback DB — resume asset restore instead
3. Document the state as "DB verified, assets in progress — recoverable"
