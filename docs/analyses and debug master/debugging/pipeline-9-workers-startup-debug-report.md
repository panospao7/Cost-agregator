# Pipeline 9 Debugging Report — Workers / Background Jobs / Startup Scheduling

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 9 is intended to be:

```text
MainApplication
→ AppStartupDelegate
→ AppStartupCoordinator
→ restore journal recovery
→ lifecycle observer registration
→ WorkManager schedules
→ workers execute safely/idempotently
→ BackgroundJobRun observability
→ restore/privacy/write gates
```

The current worker system is partially organized:

- `WorkerSpec.DEFAULTS` exists.
- 7 default workers are defined.
- Hilt `HiltWorkerFactory` is configured in `MainApplication`.
- Startup schedules workers individually with `runCatching`.
- Several workers check `RestoreMaintenanceMode`.
- Some workers check `PrivacyGate`.
- Some workers have idempotency mechanisms.

But there are still important gaps.

Highest-risk findings:

1. **`WorkerSpec.version` claims to force re-enqueue, but no code actually uses it.**
2. **Most workers use `ExistingPeriodicWorkPolicy.KEEP`, so changed constraints/intervals may not apply.**
3. **Restore maintenance protection is incomplete.**
4. **`BackgroundJobRun` table/DAO exists but workers do not write job-run records.**
5. **Startup can reset maintenance mode and schedule workers even after critical restore recovery.**
6. **One-off worker paths can bypass the unique worker names that restore mode cancels.**
7. **Some workers mark work as successful/sent even when side effects may not have happened.**
8. **There are no visible direct worker contract tests in the test inventory.**

Main recommendation:

> Build one shared `WorkerExecutionGuard` / `WorkerRunLogger` wrapper and make every worker go through it. It should enforce restore mode, enabled spec, privacy preconditions, timeout/idempotency, `BackgroundJobRun`, stale-run recovery, and consistent retry/failure semantics.

---

## 2. Intended architecture contract

From the dependency map, startup/background runtime is:

```text
MainApplication
→ AppStartupDelegate
→ AppStartupCoordinator
    → checkRestoreJournal()
    → register AppBackgroundLifecycleObserver
    → schedule startup work
```

Default workers:

```text
DailyBriefingWorker
LocationBackfillWorker
MerchantKeyBackfillWorker
WarrantyExpirationWorker
BillReminderWorker
ReceiptMatchingWorker
DataRetentionWorker
```

Expected cross-cutting rules:

```text
restore mode blocks unsafe writes
privacy gates block external/sensitive work
workers are idempotent
failed/transient work retries
per-run state is observable
startup does not schedule unsafe work during restore recovery
```

This is the correct design. The problem is consistency.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

## 3. Actual worker map

## 3.1 Startup

`MainApplication` uses Hilt and implements WorkManager `Configuration.Provider` with `HiltWorkerFactory`.

Then:

```text
onCreate()
→ AppStartupDelegate.initialize(this)
→ AppStartupCoordinator.initialize(application)
```

Good.

`AppStartupCoordinator.initialize()` does:

```text
configureDebugTools()
if mode == RESTORE_COMPLETE_RESTART_REQUIRED → set restart-required flag
checkRestoreJournal()
registerLifecycleObserver()
scheduleStartupWork(application)
syncProactiveBriefingWork()
```

Risk: `checkRestoreJournal()` can report critical recovery, but startup still proceeds to worker scheduling.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/MainApplication.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

---

## 3.2 WorkerSpec

`WorkerSpec.DEFAULTS` defines:

```text
data_retention: 24h
location_backfill: 12h, unmetered
bill_reminder_periodic: disabled, 6h
receipt_matching: 2h
ai_daily_briefing: 24h, unmetered, battery-not-low, charging
warranty_expiration_check: 24h
merchant_key_backfill: one-shot
```

Good: there is a central config file.

But `version` is not used by scheduling code.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

---

## 3.3 RestoreMaintenanceMode

`RestoreMaintenanceMode.enter(mode)`:

```text
write mode
cancel all unique work names from WorkerSpec.DEFAULTS
```

`isWritesAllowed()` returns true for:

```text
NORMAL
BACKUP_EXPORTING
```

and false for restore modes / restart-required mode.

Good idea.

Risk: cancellation only affects unique work names. Already-running workers must cooperate by checking restore mode. Anonymous one-off work is not cancelled by `cancelUniqueWork`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

---

# 4. Major findings

## Finding P0-1 — `WorkerSpec.version` is documented but not enforced

`WorkerSpec` says:

```text
version bumped whenever scheduling parameters change
triggers cancel + re-enqueue
```

But the scheduling code uses mostly:

```kotlin
enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
```

and does not include the version in:

- work name,
- tags,
- input data,
- persisted scheduled version,
- re-enqueue comparison.

Android WorkManager’s `KEEP` means if existing uncompleted work with the same unique name exists, the new request is ignored.

So if you change:

```text
interval
constraints
backoff
enabled
flex
```

existing scheduled work may keep old behavior.

Android docs confirm `KEEP` does nothing if work with the same unique name already exists, while `UPDATE` updates the new spec.

### Recommended fix

Create:

```kotlin
WorkerScheduler
```

It should:

1. read `WorkerSpec`,
2. read last scheduled version from DataStore/SharedPreferences,
3. if version changed:
   - cancel unique work,
   - enqueue new request,
   - persist version,
4. if only constraints changed and WorkManager version supports it:
   - use `ExistingPeriodicWorkPolicy.UPDATE`,
5. add tags:
   - worker name,
   - version,
   - feature area.

For one-shot work:

```text
use ExistingWorkPolicy.REPLACE or KEEP based on actual migration/backfill contract
```

Priority: highest.

Sources:  
https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy  
https://developer.android.com/reference/androidx/work/WorkManager#enqueueUniquePeriodicWork(java.lang.String,androidx.work.ExistingPeriodicWorkPolicy,androidx.work.PeriodicWorkRequest)

---

## Finding P0-2 — Restore maintenance guard is incomplete across workers

Workers with visible restore guard:

```text
LocationBackfillWorker
WarrantyExpirationWorker
BillReminderWorker
ReceiptMatchingWorker
```

Workers missing visible restore guard:

```text
DailyBriefingWorker
MerchantKeyBackfillWorker
DataRetentionWorker
```

This is dangerous because restore mode is supposed to block DB writes and background mutations.

### Why this matters

During restore/backup:

- `DataRetentionWorker` can purge raw notification/OCR text and write privacy audit rows.
- `MerchantKeyBackfillWorker` can mutate expense merchant keys.
- `DailyBriefingWorker` can generate AI artifacts and send notifications.
- Already-running workers may continue after `cancelUniqueWork`.

### Recommended fix

Every worker should start with:

```kotlin
val guard = workerExecutionGuard.beforeRun(WORK_NAME)
when (guard) {
    BlockedRestoreMode -> return Result.success()
    DisabledBySpec -> return Result.success()
    Allowed -> continue
}
```

Long-running workers should also check inside loops:

```kotlin
if (isStopped || !restoreMaintenanceMode.isWritesAllowed()) break
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

---

## Finding P0-3 — `BackgroundJobRun` exists but is not used

The DB has:

```text
BackgroundJobRun
BackgroundJobRunDao
AppDatabase.backgroundJobRunDao()
```

The DAO documentation says every worker execution should:

```text
insert RUNNING at start
update final status on completion
```

But inspected workers do not inject or write `BackgroundJobRunDao`.

Also, `DaoModule` does not visibly provide `BackgroundJobRunDao`.

So the observability table exists, but the runtime pipeline does not use it.

### Why this matters

You cannot reliably answer:

```text
Did the worker run?
Was it skipped by restore/privacy/spec?
How many rows did it scan?
What did it update?
Did it retry?
Was it killed?
Is there stale RUNNING work?
```

### Recommended fix

Create:

```kotlin
WorkerRunLogger
```

API:

```kotlin
suspend fun <T> runLogged(
    workerName: String,
    block: suspend WorkerRunContext.() -> T
): WorkerRunResult<T>
```

It should write:

```text
RUNNING
SUCCESS
RETRY
FAILED
SKIPPED_RESTORE_MODE
SKIPPED_DISABLED
SKIPPED_PRIVACY
```

Add DAO provider:

```kotlin
@Provides fun provideBackgroundJobRunDao(db: AppDatabase): BackgroundJobRunDao
```

Add startup stale-run recovery:

```text
RUNNING older than threshold → mark ABANDONED or RETRY
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

---

## Finding P0-4 — Critical restore recovery does not block worker scheduling

`AppStartupCoordinator.checkRestoreJournal()` logs critical recovery:

```text
CRITICAL — safety backup and live DB are both corrupt
```

But after the `when`, it resets non-normal maintenance modes to normal and `initialize()` continues to:

```text
register lifecycle observer
schedule startup work
sync proactive briefing work
```

This means background jobs may run after a critical restore state.

### Recommended fix

Make `checkRestoreJournal()` return:

```kotlin
StartupSafetyState
```

Examples:

```text
SAFE
RESTART_REQUIRED
CRITICAL_RECOVERY_REQUIRED
```

Then:

```kotlin
if (state == CRITICAL_RECOVERY_REQUIRED) {
    do not reset maintenance mode
    do not schedule workers
    do not sync AI briefing work
    show recovery UI
}
```

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

---

## Finding P1-1 — Anonymous one-off work bypasses restore cancellation

`ReceiptMatchingWorker.runOnce(context)` uses:

```kotlin
WorkManager.getInstance(context).enqueue(request)
```

not:

```kotlin
enqueueUniqueWork(WORK_NAME, ...)
```

Therefore `RestoreMaintenanceMode.pauseAllWorkers()` cannot cancel that one-off request by unique name.

The worker itself checks restore mode at runtime, which is good. But queued anonymous work can still exist and run later after mode changes.

### Recommended fix

Use unique work:

```kotlin
enqueueUniqueWork(
    "receipt_matching_manual",
    ExistingWorkPolicy.REPLACE,
    request
)
```

or tag all one-off worker requests:

```kotlin
.addTag("restore_blockable")
.addTag(WORK_NAME)
```

Then maintenance mode can cancel by tag too.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

---

## Finding P1-2 — BillReminderWorker has false-success delivery semantics

`BillReminderWorker.sendNotification()` catches `SecurityException` when notification permission is missing.

But `doWork()` still calls:

```kotlin
coordinator.markReminderSent(reminder.id)
sentCount++
```

So a reminder can become `SENT` even if no notification was displayed.

Also, notification body uses `occurrenceId` as amount, and notification ID is based on `occurrenceId`, so multiple delivery windows for one occurrence can collide.

These were also Pipeline 4 issues, but they are worker correctness issues too.

### Recommended fix

Make notification sending return:

```kotlin
DELIVERED
PERMISSION_DENIED
FAILED
```

Only mark reminder sent when delivered.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

---

## Finding P1-3 — ReceiptMatchingWorker ignores link failure

For `AutoMatch`, worker calls:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
autoMatched++
send notification
```

It does not inspect the `Result`.

So it can report and notify an auto-match even if the link failed.

This combines badly with Pipeline 3 issue:

```text
ReceiptLinkService ignores INSERT IGNORE result
matchStatus may remain UNMATCHED
worker can repeat
```

### Recommended fix

```kotlin
val result = receiptLinkService.linkReceiptToExpense(...)
if (result.isSuccess) {
    autoMatched++
    send notification
} else {
    failedLinks++
    log BackgroundJobRun retry/failure metadata
}
```

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

---

## Finding P1-4 — Ai daily briefing scheduling is split from WorkerSpec behavior

`AiWorkSchedulerImpl` reads `WorkerSpec.DEFAULTS["ai_daily_briefing"]` for interval/constraints, but:

- it does not check `spec.enabled`,
- it always uses `ExistingPeriodicWorkPolicy.KEEP`,
- it does not use `spec.backoffPolicy`,
- it does not use `WorkerSpec.version`.

`DailyBriefingWorker` itself:

- checks `PrivacyGate.CLOUD_AI_DAILY_BRIEFING`,
- but does not check `RestoreMaintenanceMode`,
- does not check `WorkerSpec.enabled`,
- does not log `BackgroundJobRun`.

### Recommended fix

Schedule daily briefing through the same common `WorkerScheduler`.

Run daily briefing through `WorkerExecutionGuard`.

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt

---

## Finding P1-5 — WarrantyExpirationWorker records “sent” before knowing delivery result

`WarrantyExpirationWorker` uses SharedPreferences cooldown state.

It calls:

```kotlin
notificationService.sendBudgetAlert(...)
prefs.edit().putLong(key, now).apply()
```

There is no visible delivery result check.

If permission is denied or delivery fails, the cooldown still records the reminder as sent.

### Recommended fix

Use notification APIs that return `DeliveryResult`.

Only update cooldown when delivered.

Also replace `System.currentTimeMillis()` with `TimeProvider`.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

---

## Finding P1-6 — MerchantKeyBackfillWorker writes during restore and schedule ignores WorkerSpec

`MerchantKeyBackfillWorker` mutates expense rows:

```text
expenseRepository.updateMerchantKey(...)
```

But it does not check `RestoreMaintenanceMode`.

Its `schedule(context)` also creates a custom one-time request and does not use `WorkerSpec.DEFAULTS` except inside `doWork()`.

### Recommended fix

- add restore guard,
- use `WorkerScheduler.scheduleOneShot(spec)`,
- tag the work,
- record rows scanned/updated in `BackgroundJobRun`.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt

---

## Finding P1-7 — DataRetentionWorker writes privacy changes during restore

`DataRetentionWorker` mutates:

```text
raw_notifications
scanned_receipts
privacy_audit_events
```

It does not check `RestoreMaintenanceMode`.

It is idempotent, which is good, but it can still run at the wrong time.

### Recommended fix

Add restore guard and run logging.

Also expand retention coverage later, as noted in Pipeline 8.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

---

## Finding P2-1 — Startup scheduling is resilient but not observable

`AppStartupCoordinator.scheduleStartupWork()` wraps each `schedule()` call with `runCatching`.

Good: one bad scheduler does not block the others.

But failures only go to Timber logs. There is no persistent startup/scheduler status.

### Recommended fix

Record startup scheduling results:

```text
workerName
scheduled/skipped/failed
reason
spec version
constraints hash
timestamp
```

This can be in `BackgroundJobRun` or a separate startup diagnostics table/DataStore.

Priority: medium.

---

# 5. Worker-by-worker checklist

## DailyBriefingWorker

Check/fix:

- [ ] restore guard,
- [ ] WorkerSpec enabled check,
- [ ] BackgroundJobRun logging,
- [ ] cloud/privacy settings bridge,
- [ ] notification permission result,
- [ ] no cloud call when privacy disabled,
- [ ] timeout outcome logged as RETRY.

## LocationBackfillWorker

Already good:

- restore guard,
- privacy gate,
- WorkerSpec check,
- batch limit,
- conditional location update.

Still check/fix:

- [ ] BackgroundJobRun logging,
- [ ] loop restore re-check,
- [ ] retry policy does not spam providers,
- [ ] provider privacy gate at service/provider level.

## MerchantKeyBackfillWorker

Check/fix:

- [ ] restore guard,
- [ ] WorkerSpec scheduling,
- [ ] BackgroundJobRun logging,
- [ ] lifecycle/update event policy if merchantKey mutation matters,
- [ ] no infinite retry/no-progress loop.

## WarrantyExpirationWorker

Check/fix:

- [ ] delivery result before cooldown write,
- [ ] TimeProvider instead of system time,
- [ ] BackgroundJobRun logging,
- [ ] restore re-check during long loops,
- [ ] persistent reminder state should be Room/DataStore contract-tested.

## BillReminderWorker

Check/fix:

- [ ] notification permission result before `SENT`,
- [ ] body uses occurrence merchant/amount/currency,
- [ ] notification ID uses delivery ID,
- [ ] snooze/dismiss path uses coordinator,
- [ ] BackgroundJobRun logging,
- [ ] bill reminder opt-in scheduling path.

## ReceiptMatchingWorker

Check/fix:

- [ ] check link result,
- [ ] update receipt match status,
- [ ] no repeated auto-match notification,
- [ ] one-off run uses unique work or tag,
- [ ] BackgroundJobRun logging,
- [ ] restore re-check inside loop.

## DataRetentionWorker

Check/fix:

- [ ] restore guard,
- [ ] BackgroundJobRun logging,
- [ ] raw-data inventory coverage,
- [ ] audit events for skipped/failed purge,
- [ ] loop cancellation check.

---

# 6. Recommended fix plan

## PR 1 — Common worker guard and logger

Create:

```kotlin
WorkerExecutionGuard
WorkerRunLogger
WorkerScheduler
```

Every worker should use them.

Guard responsibilities:

```text
restore mode
WorkerSpec enabled
privacy precondition hook
isStopped/cancellation
timeout if needed
BackgroundJobRun insert/update
```

Acceptance:

```text
all 7 workers write BackgroundJobRun rows and skip safely during restore.
```

---

## PR 2 — WorkerSpec version enforcement

Implement:

```text
scheduled worker version registry
```

If version changes:

```text
cancel old unique work
enqueue new work
persist version
```

Or use `ExistingPeriodicWorkPolicy.UPDATE` where appropriate.

Acceptance:

```text
changing interval/constraints/version in WorkerSpec actually changes scheduled WorkManager request.
```

---

## PR 3 — Restore-critical startup block

Make startup return safety state from restore recovery.

If critical:

```text
do not reset maintenance mode
do not schedule workers
do not sync AI briefing work
```

Acceptance:

```text
critical restore state leaves zero workers scheduled.
```

---

## PR 4 — Fix worker false-success side effects

Fix:

```text
BillReminderWorker
WarrantyExpirationWorker
ReceiptMatchingWorker
```

so they only mark sent/matched when side effect actually succeeded.

---

## PR 5 — Unique/tagged one-off work

All manual worker triggers must use:

```text
unique work name
or restore-blockable tag
```

Acceptance:

```text
RestoreMaintenanceMode can cancel all pending background work.
```

---

# 7. Tests to add

## `WorkerSpecSchedulingContractTest`

Assert:

```text
WorkerSpec.DEFAULTS maps to actual WorkRequest interval/constraints/backoff/enabled.
version bump cancels/re-enqueues or updates.
KEEP does not silently preserve stale constraints.
```

## `AllWorkersRestoreGuardTest`

For all 7 workers:

```text
restore mode active
→ doWork()
→ Result.success()
→ no DAO writes
→ BackgroundJobRun SKIPPED_RESTORE_MODE
```

## `BackgroundJobRunContractTest`

For each worker type:

```text
start row RUNNING
success row SUCCESS with counts
transient error row RETRY
permanent error row FAILED
skipped row SKIPPED_*
stale RUNNING recovered at startup
```

## `StartupCriticalRecoveryNoWorkersTest`

Simulate:

```text
RestoreJournal.CriticalRecoveryRequired
```

Assert:

```text
maintenance mode not reset
scheduleStartupWork not called
syncProactiveBriefingWork not called
workers not enqueued
```

## `ReceiptMatchingWorkerIdempotencyTest`

Seed:

```text
receipt + matching expense
```

Run worker twice.

Assert:

```text
one link
one notification
matchStatus AUTO_MATCHED
second run no-op
```

## `BillReminderWorkerDeliveryResultTest`

Cases:

```text
permission denied → not SENT
delivered → SENT
second run → no duplicate
snoozed → due again after snoozedUntil
```

## `WarrantyWorkerDeliveryResultTest`

Cases:

```text
delivery failure → no cooldown written
delivery success → cooldown written
same window rerun → no duplicate
```

## `DataRetentionWorkerRestoreModeTest`

Assert:

```text
restore mode active → no raw text purged, no audit inserted
normal mode → old raw data purged
```

---

# 8. Suggested canonical scenario

## `background_job_run_idempotency_restore_contract`

Seed:

```text
restore mode NORMAL
raw notifications older than retention
unlocated expenses
receipt eligible for matching
recurring reminder due
warranty expiring
AI briefing settings enabled
```

Run:

```text
DataRetentionWorker
LocationBackfillWorker
ReceiptMatchingWorker
BillReminderWorker
WarrantyExpirationWorker
DailyBriefingWorker
MerchantKeyBackfillWorker
```

Expected:

```text
each worker writes BackgroundJobRun
rowsScanned/rowsUpdated/notificationsSent correct
idempotent second run does not duplicate links/notifications/events
privacy-denied workers skip with SKIPPED_PRIVACY
restore mode active skips all workers
critical restore startup schedules no workers
```

This should become the Pipeline 9 fed-DB/worker acceptance test.

---

# 9. Most likely real instability sources

Ranked:

1. **WorkerSpec version/config not applied because scheduling uses KEEP.**
2. **Restore guard missing in some workers.**
3. **BackgroundJobRun observability not implemented.**
4. **Critical restore startup still schedules workers.**
5. **Anonymous one-off workers bypass restore cancellation.**
6. **Bill/warranty workers can mark sent despite failed notification delivery.**
7. **Receipt matching worker ignores link result.**
8. **Daily briefing scheduling path does not use the common worker policy.**
9. **No direct worker contract tests.**

---

# 10. Final recommendation

Stabilize Pipeline 9 in this order:

```text
1. Add WorkerExecutionGuard + WorkerRunLogger.
2. Wire BackgroundJobRun into every worker.
3. Enforce WorkerSpec.version and replace stale KEEP scheduling behavior.
4. Add restore guards to DailyBriefing, MerchantKeyBackfill, DataRetention.
5. Block startup scheduling on critical restore recovery.
6. Fix false-success notification/link semantics.
7. Make all one-off work unique/tagged/cancellable.
8. Add worker contract tests.
```

Guiding rule:

> Every worker run must be observable, idempotent, restore-safe, privacy-aware, and schedulable from a single source of truth.

Second guiding rule:

> “Scheduled” and “ran successfully” are not enough. The app must know what the worker actually changed, what it skipped, and why.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — WorkerSpec.version is documented but not enforced
**STATUS: DENIED — WorkerSpecScheduler already implements version-aware re-enqueue**
- `WorkerSpecScheduler` compares `spec.version` to stored SharedPreferences and forces `REPLACE` when the version increases.
- Default specs all use `version = 1`; bumps take effect when the `WorkerSpec` instance's `version` field is changed.
- This finding from the original report was incorrect.

## Finding P0-2 — Restore maintenance guard is incomplete across workers
**STATUS: CONFIRMED — FIXED**
- Added `RestoreMaintenanceMode` guard to three workers that were missing it:
  - `DailyBriefingWorker`: now checks `restoreMaintenanceMode.isWritesAllowed()` before running.
  - `MerchantKeyBackfillWorker`: now checks `restoreMaintenanceMode.isWritesAllowed()` before running.
  - `DataRetentionWorker`: now checks `restoreMaintenanceMode.isWritesAllowed()` before running.
- All seven default workers now have restore maintenance guards.

## Finding P0-3 — BackgroundJobRun exists but is not used
**STATUS: CONFIRMED — NOT FIXED (requires WorkerRunLogger wrapper)**

## Finding P0-4 — Startup can schedule workers after critical recovery
**STATUS: CONFIRMED — FIXED (in Pipeline 7 + Pipeline 9)**
- `AppStartupCoordinator.checkRestoreJournal()` returns early in `CriticalRecoveryRequired` case (Pipeline 7 fix).
- `AppStartupCoordinator.initialize()` now gates `scheduleStartupWork()` and `syncProactiveBriefingWork()` behind `restoreMaintenanceMode.isWritesAllowed()` (Pipeline 9 fix), so workers are blocked from scheduling during critical recovery.

## Finding P0-5 — One-off worker paths bypass unique names
**STATUS: CONFIRMED — NOT FIXED (requires enqueue coordination)**

## Finding P1-1 — Some workers mark work successful despite side-effect failures
**STATUS: CONFIRMED — PARTIALLY FIXED (BillReminderWorker now checks delivery; other workers still optimistic)**

## Finding P1-2 — No worker contract tests
**STATUS: CONFIRMED — NOT FIXED (testing strategy being refactored separately)**

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Add restore mode guard to DailyBriefingWorker | `DailyBriefingWorker.kt` | P0-2 |
| Add restore mode guard to MerchantKeyBackfillWorker | `MerchantKeyBackfillWorker.kt` | P0-2 |
| Add restore mode guard to DataRetentionWorker | `DataRetentionWorker.kt` | P0-2 |
| Block worker scheduling during critical recovery | `AppStartupCoordinator.kt` | P0-4 |

---

# 14. Remaining work priority

1. **P0-3**: Implement WorkerRunLogger to write BackgroundJobRun records
2. **P0-5**: Coordinate one-off worker enqueue with unique work names
3. **P1-1**: Make all workers verify side-effect success before counting as complete

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `MainApplication.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/MainApplication.kt

- `AppStartupCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `AppStartupDelegate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupDelegate.kt

- `AppBackgroundLifecycleObserver.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppBackgroundLifecycleObserver.kt

- `WorkerSpec.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

- `RestoreMaintenanceMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `BackgroundJobRun.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt

- `BackgroundJobRunDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt

- `DailyBriefingWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt

- `AiWorkSchedulerImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt

- `SyncProactiveBriefingWorkUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt

- `LocationBackfillWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `MerchantKeyBackfillWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt

- `WarrantyExpirationWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- `BillReminderWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `ReceiptMatchingWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

- `DataRetentionWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- Android WorkManager `ExistingPeriodicWorkPolicy`:  
  https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy

- Android WorkManager `enqueueUniquePeriodicWork`:  
  https://developer.android.com/reference/androidx/work/WorkManager#enqueueUniquePeriodicWork(java.lang.String,androidx.work.ExistingPeriodicWorkPolicy,androidx.work.PeriodicWorkRequest)