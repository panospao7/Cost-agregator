# Background Workers — Cross‑Check Review Against Current Codebase

**Source analysis**: `docs/analyses and debug master/background-workers-analysis.md`
**Codebase scanned**: `app/src/main/java/com/yourname/expensetracker/` (Kotlin sources)
**Review date**: 2026-05-02

---

## Overall verdict: PARTIALLY ADDRESSED

Significant new infrastructure has landed (`WorkerSpec` with DEFAULTS, `conditionallySetLocation`, per‑run warranty dedup, etc.) but the majority of the high‑severity findings from the analysis are still present — only one issue (conditional location update) is fully resolved. Several new issues were also discovered.

---

# Per‑Issue Status

## Issue 1 — `ExistingPeriodicWorkPolicy.KEEP` freezes old worker config forever

**Status**: PARTIALLY RESOLVED

**What was done**:
- `domain/workers/WorkerSpec.kt` was created with a `version` field and a `DEFAULTS` map that holds the *target* specs (interval, constraints, policy) for all 7 workers.
- Workers now consult `WorkerSpec.DEFAULTS[WORK_NAME]` at runtime for the `enabled` flag.

**What is still missing**:
- **No code compares `WorkerSpec.version` to a persisted value.** The `version` field (line 27 of `WorkerSpec.kt`) is defined but never read by any scheduling logic.
- **Every `schedule()` method still hardcodes `ExistingPeriodicWorkPolicy.KEEP`** — `LocationBackfillWorker:196`, `WarrantyExpirationWorker:137`, `AiWorkSchedulerImpl:26`, `DataRetentionWorker:168`, `BillReminderWorker:187`, `ReceiptMatchingWorker:143`.
- **Every `schedule()` method still hardcodes its own constraints/repeat interval**, completely ignoring the curated values in `WorkerSpec.DEFAULTS`.
- **There is no central `cancelUniqueWork` + re‑enqueue when spec version changes.** Installed users with old WorkSpecs will keep running the old config forever.

**Fix still needed**: Implement a version‑aware scheduler that compares `WorkerSpec.DEFAULTS[name].version` against a persisted `lastEnqueuedVersion` (DataStore/SP) and uses `CANCEL_AND_REENQUEUE` when they differ.

---

## Issue 2 — Warranty notifications can repeat every day

**Status**: PARTIALLY RESOLVED

**What was done**:
- ID‑based filtering: 30‑day filter now uses `val sevenDayIds = expiringIn7Days.map { it.id }.toSet()` → `filter { it.id !in sevenDayIds }` (lines 91–93). ✔ Fixed.
- Per‑run dedup: `notifiedThisRun` set with keys `"${warranty.id}:7"` and `"${warranty.id}:30"` prevents double notification within a single run. ✔ Fixed.

**What is still missing**:
- **No persisted `WarrantyReminderState`**. There is no `lastSentAt`, `dismissedAt`, or `snoozedUntil` stored per warranty×stage.
- A warranty inside the 30‑day window **will still fire a notification on every daily run** (the in‑memory `notifiedThisRun` set does not survive between runs).

**Fix still needed**: Persist a `WarrantyReminderState` table/entity and gate notification delivery on `lastSentAt` + snooze/dismiss state.

---

## Issue 3 — Location backfill retries transient failures indefinitely

**Status**: PARTIALLY RESOLVED

**What was done**:
- `getUnlocatedExpensesForBackfill` now filters `WHERE backfillAttempts < :maxAttempts` (default 3). ✔ Fixed.
- `incrementBackfillAttempts` is called for `NeedsUserSelection` and `Unresolved` results. ✔ Fixed.

**What is still missing**:
- **`Retryable` results do NOT increment `backfillAttempts`** (line 140–147: `shouldRetry = true; failed++` — no `incrementBackfillAttempts` call).
- **Resolver exceptions do NOT increment `backfillAttempts`** (line 114–119: `shouldRetry = true; failed++` — no `incrementBackfillAttempts` call).
- **No transient‑vs‑permanent counter or `nextEligibleAttemptAt`** exists. Retryable/exceptions will keep being retried on every worker run because `backfillAttempts` never moves toward the max.

**Fix still needed**: Always increment `backfillAttempts` on every non‑success outcome. Optionally add a separate `transientAttempts` counter with exponential backoff timing.

---

## Issue 4 — Location backfill overwrites user/manual location changes

**Status**: RESOLVED ✔

**What was done**:
- `ExpenseDao.conditionallySetLocation` (line 1588–1607): SQL=`UPDATE … WHERE id = :expenseId AND latitude IS NULL AND longitude IS NULL`. This prevents the backfill worker from overwriting a location that the user set between the `SELECT` and the `UPDATE`.
- `LocationBackfillWorker` uses this conditional method (line 125) and logs a skip when `affected == 0`.

The described race condition is fully mitigated. The additional guard `AND locationSource IS NOT 'MANUAL'` is a nice‑to‑have for the case where a user clears their location (making lat/lng NULL again), but that is a different scenario.

---

## Issue 5 — Daily AI briefing has no WorkManager constraints

**Status**: STILL PRESENT

**What was done**:
- `WorkerSpec.DEFAULTS["ai_daily_briefing"]` defines the desired constraints: `UNMETERED` + `batteryNotLow` + `charging` (lines 79–87).

**What is still missing**:
- `AiWorkSchedulerImpl.scheduleDailyBriefing()` (line 20–24) builds a bare `PeriodicWorkRequestBuilder<DailyBriefingWorker>(24, TimeUnit.HOURS).build()` with **zero constraints** and **does not look up `WorkerSpec.DEFAULTS`**.
- The cached artifact delivery via `GenerateDashboardBriefingUseCase` may work off‑Wi‑Fi even if cloud generation is disabled – the worker still wakes up and does local work.

**Fix still needed**: `AiWorkSchedulerImpl.scheduleDailyBriefing()` must read `WorkerSpec.DEFAULTS["ai_daily_briefing"]` and apply its `constraints` to the `PeriodicWorkRequestBuilder`. This must be paired with the version‑based re‑enqueue fix (Issue 1) so existing installs pick up the new constraints.

---

## Issue 6 — Daily AI briefing can skip notification when reusing a cached artifact

**Status**: STILL PRESENT

**What was done**:
- `GenerateDashboardBriefingUseCase` now has cache‑freshness logic via `isFreshArtifact()` — it returns early without re‑generating if today’s artifact is fresh. ✔ Good.
- `DeliverProactiveBriefingNotificationUseCase` now checks `lastDeliveredKey`/`lastOpenedKey` to avoid duplicate delivery. ✔ Good.

**What is still missing**:
- **`GenerateDashboardBriefingUseCase.invoke()` returns `Unit`**. The worker has no way to know whether a **new** artifact was generated or an **existing cached** artifact was reused.
- **`DeliverProactiveBriefingNotificationUseCase` still gates on `artifact.updatedAt < startedAt`** (line 41). If the generation use case reuses a cached artifact from 09:00 and the worker starts at 10:00, delivery is silently skipped.

**Reproduction scenario** (unchanged):
1. User opens dashboard at 09:00 → artifact generated, `updatedAt = 09:00`
2. Worker runs at 10:00 → `startedAt = 10:00`
3. `GenerateDashboardBriefingUseCase` finds fresh artifact → returns early (no new artifact)
4. `DeliverProactiveBriefingNotificationUseCase` checks `artifact.updatedAt (09:00) < startedAt (10:00)` → returns
5. No notification delivered

**Fix still needed**: Introduce a generation result sealed class (`GeneratedNew`, `ReusedFreshArtifact`, `SkippedDisabled`, …) and let the delivery logic decide based on result type, not `updatedAt < startedAt`.

---

## Issue 7 — Daily AI briefing retries all exceptions (incl. permanent)

**Status**: STILL PRESENT

**Current code** (`DailyBriefingWorker.doWork()`, lines 88–93):
```kotlin
} catch (e: Exception) {
    Timber.e(e, "DailyBriefingWorker: transient failure, scheduling retry.")
    Result.retry()
}
```

All non‑cancellation, non‑timeout exceptions return `Result.retry()`. There is **no classification** of:
- Missing API key → should return `Result.success()` with diagnostics
- Disabled provider → should return `Result.success()`
- Malformed local state → should return `Result.success()` or `Result.failure()`

**Fix still needed**: Classify exceptions into `Transient`, `PermanentNoRetry`, `UserActionRequired` and return appropriate WorkManager results.

---

## Issue 8 — Startup proactive briefing sync has no error containment

**Status**: STILL PRESENT

**Current code** (`AppStartupCoordinator.syncProactiveBriefingWork()`, lines 183–187):
```kotlin
private fun syncProactiveBriefingWork() {
    ProcessLifecycleOwner.get().lifecycleScope.launch {
        syncProactiveBriefingWorkUseCase()
    }
}
```

No `try/catch`, no `SupervisorJob`, no `runCatching`. If the use case throws, the coroutine scope may cancel or the exception propagates unhandled.

**Fix still needed**: Wrap with `runCatching { … }.onFailure { Timber.e(it, …) }` and use a dedicated `SupervisorJob()` scope.

---

## Issue 9 — Startup scheduling is not settings‑aware for location/warranty workers

**Status**: PARTIALLY RESOLVED

**What was done**:
- Workers have **runtime gates**: `WorkerSpec.enabled` check, `PrivacyGate` check, notification permission check. These prevent work execution even if the worker is scheduled.
- `SyncProactiveBriefingWorkUseCase` already exists and is settings‑aware. ✔

**What is still missing**:
- `AppStartupCoordinator.scheduleStartupWork()` (line 174–181) **unconditionally schedules all 6 workers** — no settings check.
- No `SyncLocationBackfillWorkUseCase` or `SyncWarrantyExpirationWorkUseCase` exist.
- Workers keep being re‑scheduled at every startup even if the user has disabled the feature. While runtime gates prevent execution, this is wasteful and confusing in diagnostics.
- No settings‑change listener re‑calls sync logic.

**Fix still needed**: Create feature‑aware sync use cases and call them from startup AND settings‑change listeners.

---

## Issue 10 — Merchant‑key backfill can retry forever on deterministic bad rows

**Status**: PARTIALLY RESOLVED

**What was done**:
- Per‑run failed‑row skip: `failedExpenseIdsThisRun` set (line 52, 66, 87) prevents a bad row from being retried within the same run.

**What is still missing**:
- **No persistent failure marker**. If a row fails `updateMerchantKey()` due to a valid reason (e.g. constraint violation), it will be retried on the next app start / next run. The `failedExpenseIdsThisRun` set is in‑memory only.
- No `backfillAttempts` or `merchantKeyBackfillLastError` column on the expense entity.

**Fix still needed**: Either add persistent backfill attempt tracking, or use a fallback key (`unknown:<expenseId>`) for permanently bad rows.

---

## Issue 11 — Merchant‑key backfill has no per‑run work budget

**Status**: STILL PRESENT

The worker loops until no null merchant keys remain (line 54: `while (!isStopped)`). No `maxBatchesPerRun`, `maxRowsPerRun`, or `maxDurationMs`.

**Fix still needed**: Add a budget and return `Result.retry()` when exceeded to make the job resumable.

---

## Issue 12 — No central background job run/audit table

**Status**: STILL PRESENT

No `BackgroundJobRun` entity or diagnostics store exists. `WorkerSpec` is a config spec, not a run record.

**Fix still needed**: Add a `BackgroundJobRun` table with non‑sensitive metadata (workerName, startedAt, finishedAt, status, rowsScanned, rowsUpdated, notificationsSent, retryReason).

---

## Issue 13 — Lifecycle observer registration is not idempotent

**Status**: STILL PRESENT

`AppStartupCoordinator.registerLifecycleObserver()` (line 170–172) unconditionally calls `addObserver()`. No `AtomicBoolean` guard.

In practice `Application.onCreate()` runs once per process, so this is low‑risk in production but still fragile for testing.

**Fix still needed**: Add an `AtomicBoolean initialized` guard in `AppStartupCoordinator`.

---

## Issue 14 — `AppBackgroundLifecycleObserver` swallows errors in release

**Status**: STILL PRESENT

```kotlin
} catch (e: Exception) {
    if (BuildConfig.DEBUG) {
        Timber.e(e, "Error during cleanup")
    }
}
```

Release failures are invisible.

**Fix still needed**: Log non‑sensitive diagnostics in release too (e.g. via `Timber.w` or local diagnostics).

---

## Issue 15 — AI daily briefing not aligned to calendar day

**Status**: STILL PRESENT

`AiWorkSchedulerImpl.scheduleDailyBriefing()` still uses plain 24‑hour periodic work. The briefing time depends on when the app was first started.

**Fix still needed**: Use one‑time work scheduled for next preferred local time + reschedule after completion.

---

## Issue 16 — Warranty worker mixes reconciliation and notifications

**Status**: STILL PRESENT

`WarrantyExpirationWorker.doWork()` both reconciles expired items AND sends notifications. If notification logic throws, reconciliation is retried unnecessarily.

**Fix still needed**: Split or catch notification failures per‑item.

---

# New Issues Discovered (not in original analysis)

## Issue N‑1 — `DailyBriefingWorker` missing WorkerSpec gate

**Severity**: Medium
**File**: `data/ai/worker/DailyBriefingWorker.kt`

Every other worker in the system checks `WorkerSpec.DEFAULTS[WORK_NAME]`:
- `LocationBackfillWorker:60`
- `MerchantKeyBackfillWorker:45`
- `WarrantyExpirationWorker:50`
- `DataRetentionWorker:39–41` (implicit through settings)
- `BillReminderWorker:46`
- `ReceiptMatchingWorker:43`

`DailyBriefingWorker` does **not** perform this check. It only checks `PrivacyGate`. This is inconsistent and means the worker can't be centrally disabled via `WorkerSpec.enabled = false`.

**Fix**: Add the standard WorkerSpec gate at the top of `doWork()`.

---

## Issue N‑2 — All `schedule()` methods ignore `WorkerSpec.constraints`

**Severity**: Medium
**Files**: All `schedule()` companion methods in worker classes

`WorkerSpec.DEFAULTS` defines curated constraints per worker, but every `schedule()` method:
- Hardcodes its own `Constraints.Builder()`
- Hardcodes its own `repeatInterval`
- Hardcodes its own `BackoffPolicy`

This creates a maintenance burden: changing a constraint requires updating both `WorkerSpec.DEFAULTS` AND the schedule method. The two can (and do) diverge — e.g. `WorkerSpec` says `ai_daily_briefing` should have `UNMETERED + batteryNotLow + charging`, but `AiWorkSchedulerImpl` applies none.

**Fix**: Refactor to a single `WorkerSpecScheduler` that reads `WorkerSpec.DEFAULTS[name]` and builds the `WorkRequest`, then call it from all `schedule()` methods. This also enables Issue 1’s version‑based re‑enqueue.

---

## Issue N‑3 — `BillReminderWorker` notification ID collision risk

**Severity**: Low-Medium
**File**: `service/reminder/BillReminderWorker.kt`, line 135

```kotlin
val notificationId = (delivery.occurrenceId % Int.MAX_VALUE).toInt()
```

Modulo `Int.MAX_VALUE` with `.toInt()` can produce negative IDs when `occurrenceId` is large. Additionally, two different `occurrenceId` values that differ by multiples of `Int.MAX_VALUE` will map to the same notification ID, potentially overwriting each other.

**Fix**: Use a stable notification ID generator (consistent with `NotificationIdGenerator` used elsewhere in the app) rather than modulo arithmetic.

---

## Issue N‑4 — `BillReminderWorker` marks SENT before confirming notification delivery

**Severity**: Low-Medium
**File**: `service/reminder/BillReminderWorker.kt`, lines 68–69

```kotlin
sendNotification(reminder, title, body)
coordinator.markReminderSent(reminder.id)
```

If `sendNotification` catches a `SecurityException` (missing notification permission, line 138–140) and doesn't actually deliver, the reminder is still marked as SENT. This means the user will never see the notification and the reminder won't be retried.

**Fix**: Return a delivery result from `sendNotification()` and only call `markReminderSent()` on success.

---

## Issue N‑5 — One‑shot `MerchantKeyBackfillWorker` with `KEEP` prevents re‑scheduling after permanent failure

**Severity**: Medium
**File**: `data/location/MerchantKeyBackfillWorker.kt`, line 123–128

```kotlin
WorkManager.getInstance(context).enqueueUniqueWork(
    WORK_NAME,
    ExistingWorkPolicy.KEEP,
    request
)
```

This is a one‑shot work request with `KEEP`. If the work fails permanently (returns `Result.failure()`) or if WorkManager cancels it, **future calls to `schedule()` will keep the failed WorkSpec** and never enqueue a fresh one. The backfill will never complete.

**Fix**: Either use `ExistingWorkPolicy.REPLACE` for one‑shot work, or check the current work status before enqueueing and cancel + re‑enqueue if failed.

---

## Issue N‑6 — `WorkerSpec.version` is entirely unused

**Severity**: Medium (blocks Issue 1)
**File**: `domain/workers/WorkerSpec.kt`

The `version: Int` field is defined (line 27) and populated in `DEFAULTS`, but **no code in the entire codebase reads it**. There is no persisted `lastEnqueuedVersion`, no comparison logic, and no re‑enqueue trigger.

**Fix**: Implement the version‑based scheduling logic described in Issue 1’s fix.

---

## Issue N‑7 — `RestoreMaintenanceMode.exit()` does not re‑schedule cancelled workers

**Severity**: Low
**File**: `data/backup/RestoreMaintenanceMode.kt`, lines 77–91

When entering maintenance mode, all 7 workers are cancelled via `cancelUniqueWork()`. When exiting to `NORMAL`, the code says:

```kotlin
// Workers are re-enabled; they'll be rescheduled on next app start
```

If the user exits maintenance mode mid‑session without restarting the app, workers remain cancelled until the next app restart. This is a deliberate design choice but could leave a multi‑hour gap with no background work.

**Fix**: Call the appropriate `schedule()` methods on exit (or design a central `rescheduleAllWorkers()` method).

---

# Summary Matrix

| #  | Issue                                        | Original Severity | Status             | Notes                                 |
|----|----------------------------------------------|-------------------|--------------------|---------------------------------------|
| 1  | KEEP freezes old worker config               | High              | PARTIALLY RESOLVED | WorkerSpec exists; version unused     |
| 2  | Warranty notifications repeat daily          | High              | PARTIALLY RESOLVED | ID filter fixed; no persisted state   |
| 3  | Location backfill transient retries forever  | High              | PARTIALLY RESOLVED | Max attempts added; gaps remain       |
| 4  | Location overwrites user changes             | High              | **RESOLVED**       | conditionallySetLocation added        |
| 5  | AI briefing no constraints                   | High              | STILL PRESENT      | WorkerSpec defines them but unused    |
| 6  | AI briefing skips cached artifact delivery   | High              | STILL PRESENT      | No generation result contract         |
| 7  | AI briefing retries permanent failures       | High              | STILL PRESENT      | No error classification               |
| 8  | Startup sync no error containment            | Medium/High       | STILL PRESENT      | No try/catch                          |
| 9  | Startup not settings‑aware                   | High              | PARTIALLY RESOLVED | Runtime gates added; scheduling not   |
| 10 | Merchant‑key infinite retry                  | Medium/High       | PARTIALLY RESOLVED | Per‑run skip; no persistent marker    |
| 11 | Merchant‑key no work budget                  | Medium            | STILL PRESENT      | Unbounded loop                        |
| 12 | No central audit table                       | Medium            | STILL PRESENT      | —                                     |
| 13 | Lifecycle observer not idempotent            | Medium            | STILL PRESENT      | —                                     |
| 14 | Background observer swallows errors          | Medium            | STILL PRESENT      | —                                     |
| 15 | AI briefing not calendar‑aligned             | Medium            | STILL PRESENT      | —                                     |
| 16 | Warranty mixes reconciliation+notifications  | Medium            | STILL PRESENT      | —                                     |
| N‑1| DailyBriefingWorker missing WorkerSpec gate  | NEW/Medium        | —                  | Inconsistent with all other workers   |
| N‑2| schedule() ignores WorkerSpec constraints    | NEW/Medium        | —                  | Two sources of truth for constraints  |
| N‑3| BillReminderWorker notification ID collision | NEW/Low‑Medium    | —                  | Modulo arithmetic risks collision     |
| N‑4| BillReminderWorker marks SENT before confirm | NEW/Low‑Medium    | —                  | Lost notifications if perm denied     |
| N‑5| Merchant‑key KEEP prevents re‑schedule       | NEW/Medium        | —                  | One‑shot work stuck after failure     |
| N‑6| WorkerSpec.version unused                    | NEW/Medium        | —                  | Blocks Issue 1 fix                    |
| N‑7| RestoreMode exit doesn't re‑schedule workers | NEW/Low           | —                  | Gap until next app restart            |

---

# Recommended Fix Order

The original analysis’s PR order is still sound, with adjustments for what has been partially done:

1. **PR 1 — Complete WorkerSpec versioning** (Issue 1, N‑2, N‑6): Make `schedule()` read from `WorkerSpec.DEFAULTS`, compare `version` against persisted value, use `CANCEL_AND_REENQUEUE` on mismatch.
2. **PR 2 — Add DailyBriefingWorker constraints** (Issue 5, N‑1): Apply WorkerSpec constraints in `AiWorkSchedulerImpl`, add WorkerSpec gate to `DailyBriefingWorker.doWork()`.
3. **PR 3 — Fix location backfill retry tracking** (Issue 3): Increment `backfillAttempts` for ALL non‑success outcomes including retryable and exceptions.
4. **PR 4 — Add warranty reminder persistent state** (Issue 2): Persist `WarrantyReminderState` per warranty×stage.
5. **PR 5 — Fix daily briefing artifact delivery** (Issue 6, Issue 7): Introduce generation result sealed class and error classification.
6. **PR 6 — Add error containment and feature‑aware sync** (Issue 8, Issue 9): `try/catch` in startup sync, create `SyncLocationBackfillWorkUseCase`, `SyncWarrantyExpirationWorkUseCase`.
7. **PR 7 — Merchant‑key backfill hardening** (Issue 10, 11, N‑5): Add work budget, persistent failure tracking, use `REPLACE` policy for one‑shot work.
8. **PR 8 — Housekeeping** (Issues 12–16, N‑3, N‑4, N‑7): Audit table, lifecycle idempotency, release logging, calendar alignment, split warranty jobs, bill reminder fixes.

---

# Sources Reviewed

All files listed in the original analysis plus:
- `domain/workers/WorkerSpec.kt` (new)
- `data/privacy/DataRetentionWorker.kt`
- `service/reminder/BillReminderWorker.kt`
- `service/receiptmatching/ReceiptMatchingWorker.kt`
- `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `domain/ai/usecase/AiArtifactFreshness.kt`
- `data/backup/RestoreMaintenanceMode.kt`
- `data/database/dao/ExpenseDao.kt` (relevant methods)
- `data/repository/ExpenseRepository.kt` (relevant methods)
- `domain/config/AppConfig.kt`
