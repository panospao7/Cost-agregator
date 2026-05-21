# Pipeline 9 Static Debug Report — Workers / Background Jobs

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 9 is **substantially improved**, but it is **not closed**.

Important improvements now exist:

```text
WorkerRunLogger + BackgroundJobRunDao
WorkerExecutionGuard
all 7 core workers use WorkerExecutionGuard
WorkerRegistry single scheduling registry
WorkerModule Hilt binding for WorkerRunLogger
BillReminderWorker enabled in WorkerSpec
BillReminderWorker atomic claim before notification
ReceiptMatchingWorker.runOnce() now uses enqueueUniqueWork()
PrivacySettingsRepository cancels some affected workers on settings changes
DataRetentionWorker scope expanded to AI artifacts + email receipt sources
```

However, the worker contract is still incomplete:

```text
worker starts
→ durable run row
→ guard restore/spec/privacy/permission
→ checkpoint before every DB mutation/notification side effect
→ durable per-feature outcome
→ final run result with counts/reason
→ cancellation/stale recovery
→ safe reschedule policy
```

Highest remaining user-impact risks:

1. **Restore/backup skips are not logged** because `WorkerExecutionGuard` checks `DatabaseWriteBarrier` before creating `BackgroundJobRun`.
2. **Cancelled workers can leave `RUNNING` rows forever** because `CancellationException` is rethrown before finalizing the run.
3. **Most worker runs are logged as generic `SUCCESS` with zero counts**, including no-work, notification-permission skip, fresh-artifact skip, and receipt-match no-match cases.
4. **Daily briefing one-shot chain can still break**; current code only schedules next run after full generation/delivery success.
5. **Bill reminder exactly-once is still partial** because stale claim recovery uses `scheduledAt`, not `claimedAt`; `markReminderSent()` is not conditional; notification ID is not persisted.
6. **Receipt matching one-shot is unique only against itself**, not against the periodic worker; no per-receipt claim exists.
7. **Warranty reminder sent state is still in SharedPreferences** and delivery result is ignored.
8. **Privacy setting cancellation is hardcoded, incomplete, and sometimes wrong**, especially cancelling `data_retention` when notification capture is disabled.
9. **Stale `RUNNING` recovery is still absent.**
10. **Worker spec/registry symmetry is better but untested; one-shot policy remains implicit.**

Current status: **yellow/orange**. Foundations are present, but observability, idempotency, restore safety, and scheduling correctness are still partial.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 9 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-9-workers-background-jobs-debug-report.md

- Current code:
  - `WorkerExecutionGuard.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
  - `WorkerRunLogger.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt
  - `WorkerRegistry.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt
  - `WorkerSpec.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt
  - `WorkerSpecScheduler.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt
  - `BackgroundJobRun.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt
  - `BackgroundJobRunDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt
  - `DailyBriefingWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt
  - `LocationBackfillWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt
  - `MerchantKeyBackfillWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt
  - `DataRetentionWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
  - `BillReminderWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt
  - `WarrantyExpirationWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt
  - `ReceiptMatchingWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
  - `RestoreMaintenanceMode.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
  - `AppStartupCoordinator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt
  - `PrivacySettingsRepositoryImpl.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt

---

# 1. Tracker reconciliation

Master tracker currently says Pipeline 9:

| ID | Tracker status |
|---|---|
| P9-P1-01 | fixed |
| P9-P1-02 | fixed |
| P9-P1-03 | TODO |
| P9-P1-04 | TODO |
| P9-P1-05 | fixed |
| P9-P1-06 | fixed |
| P9-P1-07 | TODO |
| P9-P1-08 | TODO |
| P9-P1-09 | TODO |
| P9-P1-10 | TODO |
| P9-P1-11 | fixed |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P9-P1-01 | **Partial, not fixed** | `WorkerRunLogger` exists and workers use guard, but restore skips are not logged, cancellations leave `RUNNING`, counts are zero/default, and no-work/permission skips become `SUCCESS`. |
| P9-P1-02 | **Mostly fixed / partial** | `WorkerExecutionGuard` exists and all 7 workers use it. But permission gate is unused, `allowDuringBackupExport` is ineffective, cancellation not finalized, and no static guard proves every future worker uses it. |
| P9-P1-03 | **Partial / still high risk** | Checkpoints exist and some loops call them, but WorkManager cancellation is still async and several DB mutations/notifications happen without per-step durable barrier/drain. |
| P9-P1-04 | **Open** | `DailyBriefingWorker` schedules next only after full generation/delivery success. Fresh-artifact, restore, privacy-denied, fail-closed, or early skip can still break chain. |
| P9-P1-05 | **Fixed for static spec** | `bill_reminder_periodic` is now enabled in `WorkerSpec`. Runtime reminder-setting model remains product work. |
| P9-P1-06 | **Partial, not fixed** | Atomic claim exists, but exactly-once still fails around stale claim recovery, `markSent`, notification ID persistence, and payment-after-claim race. |
| P9-P1-07 | **Partial** | `runOnce()` uses unique work now, but a different name from periodic worker, so manual and periodic runs can still overlap. |
| P9-P1-08 | **Partial** | Worker-level run row exists, but per-receipt match/no-match/link-failed/suggestion outcomes are still not durable events. |
| P9-P1-09 | **Open** | Warranty sent state still in SharedPreferences and delivery result is ignored. |
| P9-P1-10 | **Mostly fixed / test caveat** | `WorkerRegistry` is used by startup/restore resume; pause uses `WorkerSpec.DEFAULTS`. But equality is not enforced by a test and one-shot policy is still implicit. |
| P9-P1-11 | **Partial, not fixed** | Privacy setting changes cancel some workers, but via hardcoded names, using possibly stale transformed settings, no re-enable reschedule, and some cancellations are semantically wrong. |

Older medium issues:

| Old issue | My status |
|---|---:|
| P2-12 stale running-job recovery | **Open** |
| P2-13 retry/permanent failure classification | **Partial** — simple classifier exists in guard, but no structured error taxonomy. |
| P2-14 notification delivery outcomes inconsistent | **Open/partial** |
| P2-15 merchant-key one-shot policy mismatch | **Open/partial** |
| P2-16 data retention scope incomplete | **Partial** — AI artifacts + email receipt sources added; chat/debug diagnostics still missing and no target registry. |

---

# 2. Original issue evaluation

## P9-P1-01 — `BackgroundJobRun` table unused by workers

### Current state

Partially fixed.

Good:

- `WorkerRunLogger` and `WorkerRunLoggerImpl` exist.
- `WorkerModule` binds `WorkerRunLoggerImpl`.
- `WorkerExecutionGuard.runGuarded()` starts a `BackgroundJobRun`.
- All 7 checked core workers call `executionGuard.runGuarded()`:
  - `DailyBriefingWorker`
  - `LocationBackfillWorker`
  - `MerchantKeyBackfillWorker`
  - `DataRetentionWorker`
  - `BillReminderWorker`
  - `WarrantyExpirationWorker`
  - `ReceiptMatchingWorker`

Problems:

1. `WorkerExecutionGuard` checks `DatabaseWriteBarrier` **before** `workerRunLogger.start()`.  
   Therefore restore/backup-blocked skips are not recorded in `background_job_runs`.

2. `CancellationException` is rethrown without finalizing the row.  
   A cancelled WorkManager run can remain:

```text
status = RUNNING
finishedAt = null
```

3. `run.success()` is always called with default counts.  
   Workers currently cannot pass:

```text
rowsScanned
rowsUpdated
notificationsSent
message
```

4. Early no-op branches inside worker lambdas are recorded as `SUCCESS`, not as:

```text
SKIPPED_NO_WORK
SKIPPED_FRESH_ARTIFACT
SKIPPED_NOTIFICATION_PERMISSION
SKIPPED_DOCUMENT_TYPE
```

Examples:

- `LocationBackfillWorker`: no unlocated expenses → `SUCCESS`.
- `DailyBriefingWorker`: fresh artifact → `SUCCESS`.
- `WarrantyExpirationWorker`: notifications disabled → `SUCCESS`.
- `ReceiptMatchingWorker`: no match → only worker-level `SUCCESS`, no per-receipt outcome.

### Classification

- **Observability bug:** high.
- **Restore/debug support bug:** high.
- **Actual user impact:** indirect, but blocks support/debugging and stale-run recovery.

### Fix strategy

Change guard API to provide a `WorkerRunContext`:

```kotlin
class WorkerRunContext {
    suspend fun checkpoint(operation: String)
    fun addRowsScanned(n: Int)
    fun addRowsUpdated(n: Int)
    fun addNotificationsSent(n: Int)
    suspend fun skip(reason: WorkerSkipReason): Nothing
}
```

And finalize on all terminal paths:

```kotlin
try {
    val result = block(context)
    run.success(context.counts)
} catch (e: WorkerSkipped) {
    run.skipped(e.reason)
} catch (e: CancellationException) {
    run.cancelled()
    throw e
}
```

For restore-blocked skip, either:

- write to a restore-safe diagnostics channel outside Room, or
- allow `BackgroundJobRun` writes during maintenance only if proven safe, or
- record into `RestoreJournal` / `backup_restore_events`.

---

## P9-P1-02 — No shared `WorkerExecutionGuard`

### Current state

Mostly fixed.

Good:

- `WorkerExecutionGuard` exists.
- It checks:
  - write barrier,
  - `BACKUP_EXPORTING`,
  - `WorkerSpec.enabled`,
  - privacy capabilities,
  - transient/permanent exception classification,
  - checkpoints.
- All 7 checked workers use it.

Remaining issues:

1. `requiresNotificationPermission` exists in `WorkerGuardRequest` but is not implemented.
2. `allowDuringBackupExport` is effectively useless because `writeBarrier.checkWritesAllowed()` runs before the backup-export check.
3. It checks the write barrier before logging, so blocked runs are invisible.
4. It does not distinguish:
   - no work,
   - privacy denied,
   - permission denied,
   - worker disabled,
   - restore blocked,
   - transient DB lock,
   - permanent malformed data,
   in a typed way.
5. No static/CI guard proves every future `CoroutineWorker` uses the guard.

### Classification

- **Architectural foundation mostly fixed.**
- **Semantics still partial.**

### Fix strategy

Add:

```kotlin
enum class WorkerSkipReason {
    RESTORE_BLOCKED,
    BACKUP_EXPORTING,
    DISABLED_BY_SPEC,
    DISABLED_BY_RUNTIME_SETTING,
    PRIVACY_DENIED,
    NOTIFICATION_PERMISSION_DENIED,
    NO_WORK,
    FRESH_ARTIFACT_EXISTS
}
```

Make request explicit:

```kotlin
data class WorkerGuardRequest(
    val workerName: String,
    val requiredCapabilities: List<PrivacyCapability> = emptyList(),
    val requiresNotificationPermission: Boolean = false,
    val requiresDatabaseWrite: Boolean = true,
    val allowDuringBackupExport: Boolean = false
)
```

Then add CI rule:

```text
Every `class .*Worker : CoroutineWorker` in main source must call WorkerExecutionGuard
or appear in an explicit allowlist.
```

---

## P9-P1-03 — Restore/backup cancellation is not a true running-worker barrier

### Current state

Partial.

Good:

- `RestoreMaintenanceMode.enter()` writes mode first, then calls `pauseAllWorkers()`.
- Workers use `WorkerExecutionGuard`.
- Several long loops call `executionGuard.checkpoint()`:
  - location backfill loop,
  - receipt matching loop,
  - data retention raw-notification/OCR loops,
  - bill reminder loop.

Still incomplete:

1. `WorkManager.cancelUniqueWork()` is async; already-running workers can keep running.
2. There is no drain/wait for active workers to stop before restore/swap/backup copy.
3. Not every mutation is checkpointed:
   - `DataRetentionWorker` deletes AI artifacts and email sources without checkpoint/count/audit.
   - `WarrantyExpirationWorker` loops and writes SharedPreferences without checkpoints.
   - `BillReminderWorker` sends Android notification after claim; payment/status may change between claim and notify.
   - `ReceiptMatchingWorker` saves suggestions through repository methods that lack lifecycle events.
4. If a worker is cancelled, `BackgroundJobRun` may remain `RUNNING`.
5. `BACKUP_EXPORTING` blocks writes via barrier, but there is no proven wait for running workers to stop before the DB snapshot.

### User impact

During backup/restore:

- receipt matching can still link receipts briefly,
- retention can purge data,
- location/merchant backfill can update expenses,
- reminders/warranty notifications can be sent,
- backups can race active mutations.

### Fix strategy

Add a runtime worker lease/drain contract:

```kotlin
interface WorkerLeaseRegistry {
    suspend fun acquire(workerName: String): WorkerLease
    suspend fun requestStopAll(reason: String)
    suspend fun awaitNoActiveWorkers(timeoutMs: Long): Boolean
}
```

`WorkerExecutionGuard` acquires a lease at start and releases in `finally`.

`RestoreMaintenanceMode.enter()` should:

```text
set barrier mode
request worker stop
cancel WorkManager unique work
await active leases drain
only then allow restore/snapshot operation to continue
```

---

## P9-P1-04 — Daily briefing one-shot chain can stop after early exits

### Current state

Still open.

Current `DailyBriefingWorker`:

```text
shouldScheduleNext = false
...
fresh artifact exists -> return@runGuarded
...
generation + delivery success -> shouldScheduleNext = true
after guard -> if shouldScheduleNext scheduleDailyBriefing()
```

This means next run is scheduled only after full generation/delivery success.

Still broken for:

```text
fresh artifact exists
restore/write barrier skip
privacy denied
privacy fail-closed
generation skipped by inner use case
notification delivery skipped/fails without exception
```

### User impact

Daily briefing can stop running permanently until startup/settings sync schedules it again.

Example:

```text
Midnight worker runs
fresh artifact already exists
worker returns success
shouldScheduleNext remains false
no next midnight worker is scheduled
```

### Fix strategy

Use a terminal-outcome policy:

```kotlin
var scheduleNext = true
var retrying = false

try {
   ...
} catch (e: Retryable) {
   retrying = true
   Result.retry()
} finally {
   if (scheduleNext && !retrying) aiWorkScheduler.scheduleDailyBriefing()
}
```

If product wants no loop while AI is disabled, runtime settings sync should own cancellation/reschedule. Do not let incidental fresh-artifact/no-work skip break the chain.

---

## P9-P1-05 — Bill reminder worker disabled by static `WorkerSpec`

### Current state

Fixed for static infrastructure.

`WorkerSpec.DEFAULTS["bill_reminder_periodic"]` now has:

```text
enabled = true
version = 2
repeatIntervalHours = 6
```

Remaining product caveat:

There is still no clearly centralized runtime setting:

```text
billRemindersEnabled
quietHours
notificationPermissionState
reminderTimeOfDay
```

### Classification

- Original static disabled bug: fixed.
- Runtime settings: product/hardening.

---

## P9-P1-06 — Bill reminders are not exactly-once safe

### Current state

Partial.

Good:

- Worker now calls `coordinator.claimReminderDelivery(reminder.id)` before notifying.
- DAO has `claimDelivery()`.
- Worker checks claim result and skips if already claimed.
- `FAILED_PERMISSION` / `FAILED_TRANSIENT` states exist via coordinator.

Still unsafe:

1. Stale claim recovery uses `scheduledAt <= staleThreshold`, not a `claimedAt` timestamp.  
   For overdue reminders, a fresh claim can be immediately treated as stale by another run.

2. `RecurringReminderDelivery` has no:
   - `claimedAt`,
   - `lastAttemptAt`,
   - `attemptCount`,
   - `failureReason`,
   - `updatedAt`.

3. `markReminderSent()` loads row then updates to `SENT` without checking status is still `CLAIMED`.

4. `notificationId` is computed but not persisted.

5. Worker does not re-check occurrence status after claim and before notify.  
   If user pays bill after claim but before notify, a stale notification can still send.

6. Direct suppression paths differ:
   - `suppressByOccurrenceId()` cancels `CLAIMED`,
   - `suppressOpenDeliveriesForOccurrence()` only cancels `SCHEDULED`/`SNOOZED`.

### User impact

Duplicate or stale bill notifications are still possible.

### Fix strategy

Add delivery attempt state:

```kotlin
claimedAt: Long?
lastAttemptAt: Long?
attemptCount: Int
failureReason: String?
updatedAt: Long
```

Claim:

```sql
UPDATE recurring_reminder_deliveries
SET status='CLAIMED',
    claimedAt=:now,
    lastAttemptAt=:now,
    attemptCount=attemptCount+1
WHERE id=:id
  AND (
    (status='SCHEDULED' AND scheduledAt <= :now)
    OR
    (status='SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
  )
```

Recover:

```sql
WHERE status='CLAIMED' AND claimedAt < :staleThreshold
```

Send flow:

```text
claim
re-read occurrence
if occurrence.status != PLANNED -> cancel claim, no notification
notify
mark SENT only WHERE status = CLAIMED
persist notificationId
```

---

## P9-P1-07 — `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling

### Current state

Partially fixed.

Good:

`runOnce()` now uses:

```kotlin
enqueueUniqueWork("${WORK_NAME}_run_once", ExistingWorkPolicy.KEEP, request)
```

and copies constraints/backoff from `WorkerSpec`.

Remaining issue:

The one-shot unique name is:

```text
receipt_matching_run_once
```

while the periodic worker unique name is:

```text
receipt_matching
```

So periodic and manual one-shot can still overlap.

There is also no per-receipt claim state like:

```text
matchStatus = MATCHING
```

### User impact

Two workers can process the same receipt concurrently:

```text
periodic receipt_matching is running
user taps run once
receipt_matching_run_once starts
both try to auto-link / suggest same receipt
```

### Fix strategy

Use one of:

Option A — same unique work name:

```kotlin
enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
```

Option B — global mutex/lease:

```kotlin
receiptMatchingLease.acquireOrSkip()
```

Option C — per-receipt claim:

```sql
UPDATE scanned_receipts
SET matchStatus='MATCHING'
WHERE id=:id
  AND matchStatus IN ('UNMATCHED','SUGGESTED')
```

Best implementation is B + C.

---

## P9-P1-08 — Receipt matching outcomes are not durable enough

### Current state

Partial.

Good:

Worker-level `BackgroundJobRun` now exists via guard.

Still missing:

- no `ReceiptEvent` for document-type skip,
- no `MATCH_NOT_FOUND`,
- no `MATCH_SUGGESTED` event from worker path,
- no `AUTO_MATCH_LINK_FAILED`,
- no durable notification delivery result,
- `ReceiptRepository.saveMatchSuggestion()` directly updates receipt and logs only,
- link failure is only Timber warning,
- no row counts passed to `BackgroundJobRun`.

### User impact

A user/debugger still cannot answer:

```text
Did matching run?
Was this receipt skipped because it was a bank statement?
Was no candidate found?
Was a suggestion created?
Did auto-link fail?
Did notification fail?
```

### Fix strategy

Add receipt matching lifecycle events:

```text
MATCH_WORKER_STARTED
MATCH_WORKER_SKIPPED_DOCUMENT_TYPE
MATCH_ATTEMPTED
MATCH_NOT_FOUND
MATCH_SUGGESTED
AUTO_MATCH_LINKED
AUTO_MATCH_LINK_FAILED
MATCH_NOTIFICATION_FAILED
```

Prefer `ReceiptLifecycleCoordinator` / `ReceiptLinkService` for all changes.

---

## P9-P1-09 — Warranty notification sent-state outside DB

### Current state

Open.

`WarrantyExpirationWorker` still:

- stores sent-state in `SharedPreferences`,
- records timestamp immediately after calling `notificationService.sendBudgetAlert(...)`,
- ignores `NotificationService.DeliveryResult`,
- does not use Room-backed delivery state,
- cleanup is local prefs only, not backup/restore.

### User impact

Two opposite bugs remain:

1. Notification delivery fails but timestamp is recorded → user misses warranty reminder.
2. DB backup/restore does not preserve SharedPreferences → reminder can be resent.

### Fix strategy

Create Room table:

```kotlin
WarrantyReminderDelivery(
    id,
    warrantyId,
    windowDays,
    expiryDate,
    status,
    scheduledAt,
    claimedAt,
    sentAt,
    notificationId,
    failureReason,
    uniqueKey
)
```

Unique key:

```text
warrantyId + windowDays + expiryDate
```

Use same claim/send result model as bill reminders.

---

## P9-P1-10 — Worker pause/resume registry hardcoded and asymmetric

### Current state

Mostly fixed.

Good:

- `WorkerRegistry` exists.
- `AppStartupCoordinator.scheduleStartupWork()` calls `WorkerRegistry.scheduleAll(application)`.
- `RestoreMaintenanceMode.scheduleAllWorkers()` calls `WorkerRegistry.scheduleAll(application)`.
- `RestoreMaintenanceMode.pauseAllWorkers()` cancels `WorkerSpec.DEFAULTS.keys`.

Remaining issues:

1. No unit test proves:

```kotlin
WorkerSpec.DEFAULTS.keys == WorkerRegistry.entries.map { it.specName }.toSet()
```

2. `WorkerSpecScheduler.scheduleAtMidnight()` does not cancel existing one-shot work when a spec is disabled; it only returns.

3. `WorkerSpec` lacks explicit one-shot policy. It maps `ExistingPeriodicWorkPolicy` to one-shot `ExistingWorkPolicy`, which is indirect and fragile.

4. `WorkerSpec` comments are stale in places, e.g. bill reminder comment still says disabled by default while actual spec has `enabled = true`.

### Fix strategy

Add explicit worker registration policy:

```kotlin
data class WorkerSpec(
    ...
    val periodicPolicy: ExistingPeriodicWorkPolicy = KEEP,
    val oneShotPolicy: ExistingWorkPolicy = KEEP
)
```

And test registry/spec equality.

---

## P9-P1-11 — Privacy setting changes do not actively cancel workers

### Current state

Partial.

Good:

`PrivacySettingsRepositoryImpl.applyPrivacyChange()` cancels some workers when settings are disabled:

```text
cloudAiEnabled false -> ai_daily_briefing
backgroundLocationBackfillEnabled false -> location_backfill
notificationCaptureEnabled false -> data_retention, receipt_matching, warranty_expiration_check, bill_reminder_periodic
backgroundLocationBackfillEnabled false -> merchant_key_backfill
```

Problems:

1. Work names are hardcoded strings, not `WorkerRegistry`/policy-driven.
2. It calls `applyPrivacyChange(old, transform(old))` after edit.  
   This can differ from the actual persisted `updated` value computed inside the edit block.
3. It cancels `data_retention` when notification capture is disabled.  
   This is likely backwards: if capture is disabled, retention may be more important to purge old raw data.
4. It cancels `merchant_key_backfill` when background location is disabled even though merchant-key generation is local and not inherently location-based.
5. It only cancels on disable. It does not reschedule on re-enable.
6. It does not cancel every cloud-AI worker if more are added.
7. It does not stop in-flight worker execution; cancellation is async and relies on checkpoints.

### User impact

Privacy changes may not take effect immediately or can disable useful cleanup work.

### Fix strategy

Create:

```kotlin
PrivacyRuntimeWorkerPolicy
```

mapping capabilities/settings to workers:

```kotlin
data class WorkerPrivacyPolicy(
    val workerName: String,
    val requiredSettings: Set<PrivacySettingKey>,
    val actionOnDisable: Cancel | KeepButGuard | RunCleanupOnly,
    val actionOnEnable: Schedule
)
```

Use actual persisted settings from the edit block.

---

# 3. New/current issues found

## P9-NEW-01 — Restore/write-barrier skips are invisible in `BackgroundJobRun`

### Severity

P1.

### Evidence

`WorkerExecutionGuard.runGuarded()` calls:

```kotlin
writeBarrier.checkWritesAllowed(request.workerName)
```

before:

```kotlin
workerRunLogger.start(request.workerName)
```

If maintenance mode blocks writes, it returns `Skipped` without a run row.

### Impact

A support/debug screen cannot see that workers were skipped due restore/restart-required mode.

### Fix

Record blocked-run attempts in a restore-safe diagnostic channel, or restructure run logging to support blocked states safely.

---

## P9-NEW-02 — Worker cancellation leaves `RUNNING` rows

### Severity

P1.

### Evidence

Guard catch block does:

```kotlin
if (e is CancellationException) throw e
```

before any final `run.cancelled()`.

### Impact

Normal WorkManager cancellation during restore/privacy change can create abandoned `RUNNING` rows. Since stale recovery is absent, they remain indefinitely.

### Fix

Add:

```kotlin
run.cancelled(reason)
```

before rethrow, and implement stale recovery.

---

## P9-NEW-03 — `BackgroundJobRun` rows are low-value because counts are not propagated

### Severity

P1/P2.

### Evidence

`WorkerExecutionGuard` calls `run.success()` with default zero counts after the lambda returns.

Workers compute local counts but do not pass them to the logger.

Examples:

- `BillReminderWorker` computes `sentCount`, but run row has `notificationsSent = 0`.
- `LocationBackfillWorker` computes resolved/skipped/failed, but run row has zero counts.
- `ReceiptMatchingWorker` computes `autoMatched`/`suggested`, but run row has zero counts.

### Impact

The run table says workers ran, but not what they did.

### Fix

Use `WorkerRunContext` counters or allow the block to return `WorkerRunOutcome`.

---

## P9-NEW-04 — `WorkerGuardRequest.requiresNotificationPermission` is unused

### Severity

P2/P1 for notification workers.

### Evidence

Field exists but guard does not check notification permission.

Warranty checks permission manually; bill catches `SecurityException`; receipt matching and daily briefing notification paths use separate services and may not persist delivery status.

### Impact

Notification worker behavior remains inconsistent.

### Fix

Implement permission check in guard or remove the field and use a dedicated notification delivery port.

---

## P9-NEW-05 — `allowDuringBackupExport` is ineffective

### Severity

P2/P1 if future read-only workers use it.

### Evidence

Guard checks `DatabaseWriteBarrier` before checking:

```kotlin
allowDuringBackupExport
```

Since `DatabaseWriteBarrier` rejects every non-`NORMAL` mode, `allowDuringBackupExport = true` cannot work.

### Impact

Future read-only/background diagnostic workers may be unexpectedly skipped during backup export, or developers may falsely believe the flag works.

### Fix

Add `requiresDatabaseWrite` and separate read/write barrier behavior.

---

## P9-NEW-06 — Privacy change policy cancels data retention

### Severity

P1 privacy.

### Evidence

When `notificationCaptureEnabled` becomes false, code cancels:

```text
data_retention
```

### Impact

If a user disables notification capture for privacy, the app may also stop the worker that purges old raw notification/OCR/email data.

### Fix

Data retention should probably remain enabled unless retention itself is disabled. Use cleanup-only policy.

---

## P9-NEW-07 — `ReceiptMatchingWorker.runOnce()` can overlap periodic worker

### Severity

P1.

### Evidence

Periodic unique work name:

```text
receipt_matching
```

Manual one-shot unique work name:

```text
receipt_matching_run_once
```

### Impact

Manual and periodic workers can process the same receipts concurrently.

### Fix

Use same unique name, global lease, or per-receipt `MATCHING` claim.

---

## P9-NEW-08 — Data retention expanded but without target registry, counts, or checkpoints for all targets

### Severity

P2/P1 privacy.

### Evidence

Data retention now purges:

```text
raw notifications
raw OCR
expired AI artifacts
old email receipt sources
```

But:

- AI/email deletes have no per-target audit counts,
- no checkpoint immediately before those delete calls,
- no retention target registry,
- chat messages/debug diagnostics still missing per comment.

### Impact

Retention looks stronger but remains hard to audit and incomplete.

### Fix

Create `RetentionTarget` registry and log per-target counts into `BackgroundJobRun.metadata` or a retention event table.

---

## P9-NEW-09 — No stale `RUNNING` recovery despite run table

### Severity

P1/P2.

### Evidence

`BackgroundJobRunDao.getStaleRunningRuns()` exists, but `AppStartupCoordinator` does not call a recovery service.

### Impact

Crashes/cancellations leave stale run rows. Retry/repair cannot be automated.

### Fix

Add `BackgroundJobRecoveryService` at startup:

```text
RUNNING older than threshold -> STALE_ABORTED
optionally requeue recoverable worker
```

---

## P9-NEW-10 — Notification delivery contract differs by worker

### Severity

P1/P2.

### Evidence

- `BillReminderWorker` uses `NotificationManagerCompat` directly and gets Boolean.
- `WarrantyExpirationWorker` uses `NotificationService.sendBudgetAlert()` but ignores `DeliveryResult`.
- `ReceiptMatchingWorker` uses `sendBudgetAlert()` and ignores result.
- `DailyBriefingWorker` uses `DeliverProactiveBriefingNotificationUseCase`.

### Impact

Feature-specific sent-state can be wrong, and permission failure handling differs.

### Fix

Create common port:

```kotlin
sealed interface NotificationDeliveryResult {
    data class Delivered(val notificationId: Int)
    data class BlockedPermission(val reason: String)
    data class Failed(val retryable: Boolean, val reason: String)
}
```

All workers should use it and persist result.

---

## P9-NEW-11 — One-shot policy remains implicit and comment-mismatched

### Severity

P2.

### Evidence

`WorkerSpec` has only `ExistingPeriodicWorkPolicy`. `WorkerSpecScheduler` maps this to one-shot `ExistingWorkPolicy`.

Merchant-key comments say REPLACE is intended, but no explicit one-shot policy is present in `WorkerSpec`.

### Impact

Scheduling semantics are ambiguous and can regress.

### Fix

Add explicit `oneShotPolicy`.

---

## P9-NEW-12 — Worker success/no-work/skip taxonomy is not typed

### Severity

P2.

### Evidence

`WorkerRunLogger.skipped(reason: String)` creates dynamic status strings:

```text
SKIPPED_$reason
```

No enum/status constraint exists.

### Impact

Queries and UI filters become fragile.

### Fix

Use typed status + reason columns:

```kotlin
status = SKIPPED
skipReason = NO_WORK | PRIVACY_DENIED | ...
```

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize:

1. **Daily briefing one-shot chain can stop after fresh-artifact/no-work/restore skip.**
2. **Bill reminders can still duplicate or send after payment due claim-state gaps.**
3. **Warranty reminders can be lost or duplicated because sent-state is SharedPreferences and delivery result is ignored.**
4. **Receipt matching manual run can overlap periodic run and double-link/suggest.**
5. **Privacy setting changes can cancel data retention, undermining cleanup.**
6. **Restore/backup can still race running workers because cancellation is async and no drain exists.**
7. **Cancelled/stale workers leave `RUNNING` rows and no recovery.**
8. **Notification delivery results are inconsistent across workers.**

## Architectural / observability work

Important but lower immediate urgency:

1. Typed worker status/skip/retry taxonomy.
2. Worker run context with counts and metadata.
3. Worker registry/spec equality test.
4. Explicit one-shot scheduling policy.
5. Static guard requiring `WorkerExecutionGuard`.
6. Retention target registry.
7. Unified notification delivery port.
8. Background job recovery service.
9. Operational worker health screen.

---

# 5. Recommended implementation plan

## PR 1 — Worker run logging semantics and cancellation finalization

### Goal

Every worker attempt has a durable, accurate final state.

### Files

- `WorkerExecutionGuard.kt`
- `WorkerRunLogger.kt`
- `BackgroundJobRun.kt`
- `BackgroundJobRunDao.kt`
- all workers

### Tasks

1. Add typed statuses:
   ```text
   RUNNING
   SUCCESS
   SKIPPED
   RETRY
   FAILED
   CANCELLED
   STALE_ABORTED
   ```
2. Add typed reason column:
   ```text
   RESTORE_BLOCKED
   BACKUP_EXPORTING
   PRIVACY_DENIED
   PERMISSION_DENIED
   NO_WORK
   FRESH_ARTIFACT
   DISABLED
   ```
3. Add `WorkerRunContext` counters.
4. Finalize run on cancellation before rethrow.
5. Ensure no-work and permission skip are not logged as generic `SUCCESS`.

### Acceptance tests

```text
cancelled_worker_updates_run_to_CANCELLED
location_no_work_logs_SKIPPED_NO_WORK
warranty_permission_denied_logs_SKIPPED_PERMISSION
bill_worker_success_records_notificationsSent
receipt_matching_success_records_autoMatched_and_suggested
restore_blocked_worker_has_durable_skip_record_or_restore_journal_record
```

---

## PR 2 — Runtime worker barrier and restore/backup drain

### Goal

Restore/backup cannot race running workers.

### Files

- `WorkerExecutionGuard.kt`
- `RestoreMaintenanceMode.kt`
- `DatabaseBackupRepositoryImpl.kt`
- all long-running workers

### Tasks

1. Add worker lease registry.
2. Guard acquires lease at start and releases in `finally`.
3. Restore/backup enter:
   - set blocking mode,
   - request stop,
   - cancel unique work,
   - wait for leases to drain or timeout.
4. Add checkpoints before every DB mutation and before notification side effects.
5. Decide backup-export allowlist.

### Acceptance tests

```text
restore_enter_waits_for_running_location_worker_to_stop
backup_export_waits_for_data_retention_to_stop
running_receipt_matching_stops_before_next_link
cancelled_worker_does_not_continue_after_checkpoint
```

---

## PR 3 — Daily briefing schedule-chain fix

### Goal

One-shot daily briefing cannot silently stop.

### Files

- `DailyBriefingWorker.kt`
- `AiWorkSchedulerImpl.kt`
- `SyncProactiveBriefingWorkUseCase.kt`
- `WorkerSpecScheduler.kt`

### Tasks

1. Schedule next run on terminal success/skip except explicit runtime-disabled policy.
2. Fresh artifact skip should schedule next midnight.
3. Restore skip should either:
   - schedule next after restart, or
   - be reconciled by startup sync.
4. `scheduleAtMidnight()` should cancel existing work when disabled.
5. Add idempotent scheduling test.

### Acceptance tests

```text
daily_briefing_fresh_artifact_schedules_next_midnight
daily_briefing_no_work_schedules_next_midnight
daily_briefing_restore_skip_does_not_break_chain
daily_briefing_privacy_fail_closed_does_not_permanently_break_chain
scheduleAtMidnight_disabled_cancels_existing_work
```

---

## PR 4 — Bill reminder delivery state machine finalization

### Goal

No duplicate/stale bill notifications.

### Files

- `RecurringReminderDelivery.kt`
- `RecurringReminderDeliveryDao.kt`
- `RecurringLifecycleCoordinator.kt`
- `BillReminderWorker.kt`
- migration

### Tasks

1. Add:
   - `claimedAt`
   - `lastAttemptAt`
   - `attemptCount`
   - `failureReason`
   - `updatedAt`
2. Recover stale claims by `claimedAt`, not `scheduledAt`.
3. `markReminderSent()` updates only `CLAIMED`.
4. Persist `notificationId`.
5. Re-read occurrence status after claim and before notify.
6. Suppress `CLAIMED` deliveries on payment.

### Acceptance tests

```text
two_bill_workers_claim_same_delivery_only_one_sends
fresh_claim_for_overdue_reminder_not_recovered
stale_claim_after_threshold_recovered
payment_after_claim_before_notify_does_not_send
mark_sent_only_from_claimed
notification_id_persisted
```

---

## PR 5 — Receipt matching concurrency + events

### Goal

Receipt matching is single-owner and auditable.

### Files

- `ReceiptMatchingWorker.kt`
- `ReceiptRepository.kt`
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptLinkService.kt`
- `ReceiptEvent.kt`
- `ScannedReceiptDao.kt`

### Tasks

1. Prevent manual and periodic overlap:
   - same unique work name, or
   - global receipt-matching lease.
2. Add per-receipt claim state:
   ```text
   UNMATCHED/SUGGESTED -> MATCHING -> final state
   ```
3. Write events:
   - `MATCH_ATTEMPTED`
   - `MATCH_NOT_FOUND`
   - `MATCH_SUGGESTED`
   - `AUTO_MATCH_LINKED`
   - `AUTO_MATCH_LINK_FAILED`
   - `MATCH_SKIPPED_DOCUMENT_TYPE`
4. Use unified notification result.

### Acceptance tests

```text
receipt_matching_runOnce_does_not_overlap_periodic
two_workers_cannot_claim_same_receipt
bank_statement_receipt_skip_writes_event
no_match_writes_MATCH_NOT_FOUND
auto_link_failure_writes_event
suggestion_writes_MATCH_SUGGESTED_event
```

---

## PR 6 — Warranty delivery state in Room

### Goal

Warranty reminders survive backup/restore and only mark sent after real delivery.

### Files

- `WarrantyExpirationWorker.kt`
- new `WarrantyReminderDelivery.kt`
- DAO + migration
- `NotificationService`

### Tasks

1. Add warranty reminder delivery table.
2. Unique key: `warrantyId + windowDays + expiryDate`.
3. Claim before notify.
4. Persist `SENT` only when notification delivery result is delivered.
5. Include state in backup/restore.

### Acceptance tests

```text
warranty_notification_failure_does_not_mark_sent
warranty_sent_state_survives_db_backup_restore
warranty_same_window_not_resent_after_restart
warranty_new_expiry_date_creates_new_delivery
```

---

## PR 7 — Worker registry/spec policy cleanup

### Goal

Worker scheduling is explicit and symmetric.

### Files

- `WorkerSpec.kt`
- `WorkerRegistry.kt`
- `WorkerSpecScheduler.kt`
- tests

### Tasks

1. Add explicit `oneShotPolicy`.
2. Fix merchant-key policy to match intended behavior.
3. Add spec/registry equality test.
4. Make `scheduleAtMidnight()` cancel disabled existing work.
5. Remove stale comments.

### Acceptance tests

```text
worker_registry_keys_equal_WorkerSpec_keys
merchant_key_backfill_uses_explicit_oneShotPolicy
disabled_midnight_worker_cancels_existing_unique_work
bill_reminder_comment_matches_enabled_spec
```

---

## PR 8 — Privacy runtime worker policy

### Goal

Privacy settings cancel/reschedule the right workers, and never disable cleanup accidentally.

### Files

- `PrivacySettingsRepositoryImpl.kt`
- new `PrivacyRuntimeWorkerPolicy.kt`
- `WorkerRegistry.kt`
- maybe `WorkerSpec.kt`

### Tasks

1. Use actual persisted updated settings, not `transform(old)`.
2. Replace hardcoded strings with registry/policy mapping.
3. On enable, reschedule relevant workers.
4. Do not cancel `data_retention` merely because capture is disabled.
5. Cloud AI disable cancels all cloud AI work.
6. Location disable cancels only location-dependent work.

### Acceptance tests

```text
disable_cloud_ai_cancels_all_cloud_ai_workers
enable_cloud_ai_reschedules_daily_briefing_when_ai_settings_enabled
disable_notification_capture_does_not_cancel_data_retention
disable_background_location_does_not_cancel_merchant_key_backfill
privacy_update_uses_actual_persisted_settings
```

---

## PR 9 — Stale job recovery

### Goal

Abandoned `RUNNING` jobs are visible and recoverable.

### Files

- new `BackgroundJobRecoveryService.kt`
- `AppStartupCoordinator.kt`
- `BackgroundJobRunDao.kt`

### Tasks

1. On startup, find stale `RUNNING` rows.
2. Mark as `STALE_ABORTED`.
3. Optionally requeue recoverable workers:
   - receipt matching,
   - data retention,
   - merchant backfill,
   - location backfill.
4. Do not mark recent active runs stale.

### Acceptance tests

```text
startup_marks_old_RUNNING_as_STALE_ABORTED
startup_does_not_mark_recent_RUNNING_stale
stale_receipt_matching_requeues_worker
stale_bill_reminder_does_not_duplicate_claimed_delivery_without_delivery_recovery
```

---

## PR 10 — Data retention target registry

### Goal

Retention is complete, auditable, and checkpointed.

### Files

- `DataRetentionWorker.kt`
- new retention target package
- DAOs for email/AI/chat/diagnostics
- `PrivacyAuditEvent`

### Tasks

1. Add `RetentionTarget`.
2. Register:
   - raw notifications,
   - raw OCR,
   - email source body/subject/sender policy,
   - AI artifacts,
   - AI chat messages,
   - pipeline/service diagnostics,
   - debug exports.
3. Every target returns counts.
4. Worker logs per-target counts.
5. Add checkpoints before each target mutation.

### Acceptance tests

```text
data_retention_purges_email_receipt_body
data_retention_purges_ai_artifact_prompts
data_retention_purges_ai_chat_messages
data_retention_records_per_target_counts
data_retention_checkpoint_before_each_target_write
```

---

# 6. Suggested tracker updates

Update Pipeline 9 tracker:

| ID | Suggested status |
|---|---|
| P9-P1-01 | Partial |
| P9-P1-02 | Mostly fixed / partial |
| P9-P1-03 | Partial / high priority |
| P9-P1-04 | TODO / open |
| P9-P1-05 | Fixed |
| P9-P1-06 | Partial |
| P9-P1-07 | Partial |
| P9-P1-08 | Partial |
| P9-P1-09 | TODO / open |
| P9-P1-10 | Mostly fixed / test caveat |
| P9-P1-11 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P9-NEW-01 | P1 | Restore/write-barrier skips are invisible in `BackgroundJobRun` |
| P9-NEW-02 | P1 | Worker cancellation leaves `RUNNING` rows |
| P9-NEW-03 | P1/P2 | `BackgroundJobRun` rows have zero counts/generic outcomes |
| P9-NEW-04 | P2/P1 | `requiresNotificationPermission` is unused |
| P9-NEW-05 | P2/P1 | `allowDuringBackupExport` is ineffective |
| P9-NEW-06 | P1 | Privacy change policy cancels data retention |
| P9-NEW-07 | P1 | Receipt matching one-shot can overlap periodic worker |
| P9-NEW-08 | P2/P1 | Data retention lacks target registry/counts/checkpoints |
| P9-NEW-09 | P1/P2 | No stale `RUNNING` recovery |
| P9-NEW-10 | P1/P2 | Notification delivery contract differs by worker |
| P9-NEW-11 | P2 | One-shot policy remains implicit and comment-mismatched |
| P9-NEW-12 | P2 | Worker success/skip taxonomy is not typed |

---

# 7. Golden tests for Pipeline 9

Add or verify:

```text
all_workers_use_WorkerExecutionGuard
all_WorkerSpec_defaults_have_WorkerRegistry_entry
all_WorkerRegistry_entries_have_WorkerSpec
startup_schedules_every_enabled_registered_worker
restore_enter_cancels_all_registered_workers
restore_enter_waits_for_running_workers_or_sets_barrier
restore_blocked_worker_records_skip_or_restore_journal_event
cancelled_worker_finalizes_BackgroundJobRun_CANCELLED
stale_RUNNING_jobs_marked_STALE_ABORTED_on_startup
location_backfill_no_work_logs_SKIPPED_NO_WORK
location_backfill_records_rows_scanned_updated_failed
merchant_key_backfill_records_rows_updated
data_retention_records_per_target_counts
data_retention_disable_notification_capture_still_runs
daily_briefing_fresh_artifact_schedules_next_midnight
daily_briefing_restore_skip_does_not_break_chain
daily_briefing_privacy_denied_policy_reschedules_or_cancels_explicitly
scheduleAtMidnight_disabled_cancels_existing_work
bill_reminder_two_workers_claim_same_delivery_only_one_sends
bill_reminder_fresh_claim_not_recovered_as_stale
bill_reminder_payment_after_claim_before_notify_does_not_send
bill_reminder_persists_notificationId
receipt_matching_runOnce_does_not_overlap_periodic
receipt_matching_per_receipt_claim_prevents_double_link
receipt_matching_no_match_writes_event
receipt_matching_auto_link_failure_writes_event
receipt_matching_notification_failure_writes_event
warranty_notification_failure_does_not_mark_sent
warranty_sent_state_survives_backup_restore
warranty_same_window_not_resent_after_restart
privacy_disable_cloud_ai_cancels_all_cloud_ai_work
privacy_enable_cloud_ai_reschedules_when_enabled
privacy_disable_location_does_not_cancel_merchant_key_backfill
worker_guard_requires_notification_permission_when_requested
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "class .*Worker" app/src/main/java
grep -R "@HiltWorker" app/src/main/java
grep -R "runGuarded" app/src/main/java
grep -R "WorkerRunLogger" app/src/main/java
grep -R "BackgroundJobRun" app/src/main/java
grep -R "getStaleRunningRuns" app/src/main/java
grep -R "CancellationException" app/src/main/java/com/yourname/expensetracker/domain/workers
grep -R "requiresNotificationPermission" app/src/main/java
grep -R "allowDuringBackupExport" app/src/main/java
grep -R "scheduleAtMidnight" app/src/main/java
grep -R "enqueueUniqueWork" app/src/main/java
grep -R "receipt_matching_run_once" app/src/main/java
grep -R "sendBudgetAlert" app/src/main/java
grep -R "NotificationManagerCompat" app/src/main/java
grep -R "getSharedPreferences" app/src/main/java/com/yourname/expensetracker/service/warranty
grep -R "cancelUniqueWork" app/src/main/java
grep -R "data_retention" app/src/main/java/com/yourname/expensetracker/data/privacy
grep -R "deleteExpired" app/src/main/java
grep -R "deleteOlderThan" app/src/main/java
```

Allowed worker set should remain explicit:

```text
data_retention
location_backfill
bill_reminder_periodic
receipt_matching
ai_daily_briefing
warranty_expiration_check
merchant_key_backfill
```

Definition of done:

```text
- Every worker attempt has a durable final run state.
- Cancelled workers do not leave RUNNING forever.
- BackgroundJobRun records real counts and typed reasons.
- Restore/backup waits for running workers or they stop at checkpoints before writes.
- Daily briefing one-shot chain cannot silently stop.
- Bill reminders have claimedAt/attempt/notificationId and exactly-once semantics.
- Receipt matching manual and periodic paths cannot overlap unsafely.
- Warranty reminder delivery state is Room-backed and delivery-aware.
- Privacy worker policy does not cancel data retention accidentally.
- Stale RUNNING jobs are recovered on startup.
- Notification delivery result is unified across all workers.
- WorkerSpec and WorkerRegistry are tested for symmetry.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix worker run finalization/cancellation/counts** — makes debugging and later recovery possible.
2. **Add runtime worker drain/barrier for restore/backup** — prevents data mutation during backup/restore.
3. **Fix daily briefing one-shot reschedule chain** — direct user-visible scheduling bug.
4. **Finalize bill reminder delivery state machine** — duplicate/stale notification risk.
5. **Fix receipt matching concurrency and per-receipt events** — prevents double-link/silent no-match.
6. **Move warranty delivery state to Room** — prevents missed/duplicate warranty reminders.
7. **Fix privacy runtime worker policy** — especially do not cancel data retention on capture disable.
8. **Add stale `RUNNING` recovery service.**
9. **Clean WorkerSpec/WorkerRegistry/one-shot policy and add symmetry tests.**
10. **Build retention target registry and unified notification delivery port.**