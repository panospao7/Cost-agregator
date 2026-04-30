# Startup & Background Workers Deep Analysis — `master-refactor`

## Scope reviewed

Main files inspected:

- `MainApplication.kt`
- `AppStartupDelegate.kt`
- `AppStartupCoordinator.kt`
- `AppBackgroundLifecycleObserver.kt`
- `LocationBackfillWorker.kt`
- `MerchantKeyBackfillWorker.kt`
- `WarrantyExpirationWorker.kt`
- `AiWorkScheduler.kt`
- `AiWorkSchedulerImpl.kt`
- `DailyBriefingWorker.kt`
- `SyncProactiveBriefingWorkUseCase.kt`
- `DeliverProactiveBriefingNotificationUseCase.kt`

## Executive verdict

This area is generally structured well:

- WorkManager is used.
- Workers are Hilt-injected.
- Most recurring jobs use unique work names.
- Startup is centralized through `AppStartupCoordinator`.
- Backfill jobs are mostly idempotent.
- AI proactive briefing has settings checks before scheduling/delivery.

But the biggest risks are:

1. **workers are scheduled with `KEEP`, so future constraint/interval changes may not reach installed users**
2. **location and warranty workers can repeatedly re-process/notify without per-item sent/attempt state**
3. **Daily AI briefing can silently skip delivery when it reuses a fresh cached artifact**
4. **background workers do not consistently classify permanent vs transient failures**
5. **startup sync work is launched without error containment**
6. **several background side effects are not protected against user edits/settings changes during execution**

---

# Architecture summary

## Startup path

`MainApplication.onCreate()` calls:

```text
AppStartupDelegate.initialize(this)
```

Then:

```text
AppStartupDelegate
→ AppStartupCoordinator.initialize(application)
```

`AppStartupCoordinator` does four things:

1. debug logging / StrictMode setup
2. registers `AppBackgroundLifecycleObserver`
3. schedules startup work:
   - `LocationBackfillWorker`
   - `MerchantKeyBackfillWorker`
   - `WarrantyExpirationWorker`
4. launches proactive AI briefing work sync:
   - `SyncProactiveBriefingWorkUseCase`

This is a good central point, but it currently mixes:

- debug tooling
- lifecycle cleanup
- financial/background workers
- AI scheduling

A future improvement would be a `StartupTaskRegistry` with explicit task names, settings dependencies, idempotency rules, and diagnostics.

---

# Strong parts

## 1. Hilt WorkManager setup is correct

`MainApplication` implements `Configuration.Provider` and injects `HiltWorkerFactory`.

This is good. It means workers can receive repositories/use cases through DI instead of service locators.

## 2. Most workers use unique WorkManager names

Examples:

- `location_backfill`
- `merchant_key_backfill`
- `warranty_expiration_check`
- AI daily briefing work name from `AppConfig.Ai.WORK_NAME_DAILY_BRIEFING`

This prevents obvious duplicate periodic schedules.

## 3. Location backfill uses Wi-Fi-only scheduling

`LocationBackfillWorker.schedule()` uses `NetworkType.UNMETERED`.

Good, because geocoding can involve external network calls.

## 4. Merchant-key backfill is local and idempotent

It only fetches rows where `merchantKey` is null, then writes a deterministic key.

This is a good migration/backfill pattern.

## 5. AI proactive briefing has settings checks in both scheduling and delivery

`SyncProactiveBriefingWorkUseCase` schedules only when:

- AI enabled
- dashboard briefing enabled
- proactive briefings enabled

`DeliverProactiveBriefingNotificationUseCase` re-checks settings before notification delivery.

Good defense-in-depth.

---

# Critical / high-priority findings

## 1. `ExistingPeriodicWorkPolicy.KEEP` can freeze old worker config forever

### Where

- `LocationBackfillWorker.schedule()`
- `WarrantyExpirationWorker.schedule()`
- `AiWorkSchedulerImpl.scheduleDailyBriefing()`

### Problem

Periodic workers are scheduled with `ExistingPeriodicWorkPolicy.KEEP`.

This is safe for avoiding duplicates, but dangerous when you later change:

- repeat interval
- network constraints
- battery constraints
- backoff policy
- input data
- worker class behavior assumptions

Installed users with an existing worker keep the old WorkSpec.

### Impact

Example:

- You change AI daily briefing to require Wi-Fi-only.
- Existing installs still run the old unconstrained worker.
- You change warranty checks from daily to weekly.
- Existing installs still run daily.
- You change location backfill from 6 hours to 24 hours.
- Existing installs keep 6 hours.

### Severity

**High**

### Fix

Introduce versioned worker specs.

Options:

1. Use `ExistingPeriodicWorkPolicy.UPDATE` if available/compatible.
2. Use `CANCEL_AND_REENQUEUE` when a stored worker-spec version changes.
3. Store worker config version in DataStore/Room:

```text
workerName → specVersion
```

If version differs:

```text
cancelUniqueWork(workerName)
enqueue new spec
save version
```

### Tests

- Existing old work spec is replaced when version changes.
- Existing work is not replaced when version is unchanged.
- Settings changes update/cancel affected workers.

---

## 2. Warranty notifications can repeat every day in the same reminder window

### Where

`WarrantyExpirationWorker.doWork()`

### Problem

The worker checks:

- warranties expiring in 7 days
- warranties expiring in 30 days

Then sends notifications.

It uses stable notification IDs, which prevents integer overflow and may replace existing notifications, but there is no persisted state like:

- last sent date
- reminder stage sent
- dismissed/snoozed state
- notification permission/result state

### Impact

A warranty inside the 30-day window can notify repeatedly every daily run.

A warranty inside the 7-day window can also notify repeatedly.

Even if Android replaces the same notification ID, the user can still get repeated alerts.

### Severity

**High**

### Fix

Add reminder state:

```kotlin
WarrantyReminderState(
    warrantyId,
    stageDaysBeforeExpiry,
    expiryDate,
    lastSentAt,
    dismissedAt,
    snoozedUntil,
    notificationId
)
```

Use uniqueness:

```text
warrantyId + expiryDate + stageDaysBeforeExpiry
```

Only notify once per stage unless snoozed/re-enabled.

### Extra fix

The 30-day filter currently depends on object equality:

```kotlin
filter { it !in expiringIn7Days }
```

Use ID-based filtering instead:

```kotlin
val sevenDayIds = expiringIn7Days.map { it.id }.toSet()
filter { it.id !in sevenDayIds }
```

---

## 3. Location backfill can retry the same transient failures indefinitely

### Where

`LocationBackfillWorker.doWork()`

### Problem

The worker only increments per-expense attempts for:

- `NeedsUserSelection`
- `Unresolved`

For resolver exceptions and `Retryable` results, it sets `shouldRetry = true` but does not increment the expense backfill attempts.

The query comment says it fetches only expenses below max attempts, but retryable/exception cases may never move toward that max.

### Impact

If a merchant/provider keeps failing transiently, the worker can repeatedly call geocoding providers for the same expense forever.

This can cause:

- API/rate-limit pressure
- battery/data usage
- repeated WorkManager retries
- noisy logs
- external provider abuse

### Severity

**High**

### Fix

Track two separate counters:

```text
permanentResolutionAttempts
transientResolutionAttempts
lastAttemptAt
nextEligibleAttemptAt
lastErrorType
```

Retryable failures should not be treated like permanent unresolved failures, but they still need backoff state.

### Acceptance rule

A row should not be retried on every worker run unless:

```text
now >= nextEligibleAttemptAt
```

---

## 4. Location backfill can overwrite user/manual location changes made during the worker run

### Where

`LocationBackfillWorker.doWork()`

### Problem

The worker fetches unlocated expenses, then later writes location data.

Race:

1. worker fetches expense with no location
2. user manually adds/corrects location
3. worker resolver finishes
4. worker writes auto-resolved location over user value

### Impact

Manual user correction can be lost.

### Severity

**High**

### Fix

Make location update conditional at DAO level:

```sql
UPDATE expenses
SET latitude = ..., longitude = ...
WHERE id = :expenseId
  AND latitude IS NULL
  AND longitude IS NULL
  AND locationSource IS NOT 'MANUAL'
```

Better:

- add `locationIsUserConfirmed`
- never overwrite confirmed/user-provided location from a backfill worker

---

## 5. Daily AI briefing has no WorkManager network/charging/battery constraints

### Where

`AiWorkSchedulerImpl.scheduleDailyBriefing()`

### Problem

It builds:

```kotlin
PeriodicWorkRequestBuilder<DailyBriefingWorker>(24, TimeUnit.HOURS).build()
```

No constraints are attached.

The AI router/settings may prevent cloud calls later, but the worker can still wake up, assemble dashboard data, and attempt generation.

### Impact

Potential issues:

- cloud AI attempted on mobile data if lower layers have a bug
- daily dashboard/AI processing on low battery
- unnecessary background work when network unavailable
- repeated retries if cloud provider unavailable

### Severity

**High**

### Fix

Add constraints based on current settings:

- if cloud AI can be used and Wi-Fi-only is enabled → `UNMETERED`
- if cloud AI can be used → `CONNECTED`
- if local-only/no-op → maybe no network
- consider battery-not-low for AI work

Since WorkManager constraints are static after enqueue, pair this with worker-spec versioning/update.

---

## 6. Daily AI briefing can skip notification when generation reuses a cached artifact

### Where

- `DailyBriefingWorker.doWork()`
- `DeliverProactiveBriefingNotificationUseCase.invoke()`

### Problem

`DailyBriefingWorker` passes `startedAt` to generation and delivery.

Delivery rejects an artifact if:

```kotlin
artifact.updatedAt < startedAt
```

But the generation use case is documented as handling cache freshness.

If `GenerateDashboardBriefingUseCase` decides today’s existing artifact is fresh enough and does not update it, delivery will skip it because it was updated before this worker run started.

The worker then returns `Result.success()`.

### Impact

A valid fresh daily briefing may never be delivered proactively.

Example:

1. user opens dashboard at 09:00, artifact generated
2. DailyBriefingWorker runs at 10:00
3. generate use case reuses 09:00 artifact
4. delivery sees `updatedAt < startedAt`
5. no notification
6. worker succeeds; no retry

### Severity

**High**

### Fix options

Option A — generation returns a result:

```kotlin
GeneratedNew
ReusedFreshArtifact
SkippedDisabled
FailedTransient
```

Delivery should know whether reused artifacts are eligible.

Option B — compare artifact date key, not `updatedAt >= startedAt`.

Option C — only call delivery if generation produced/confirmed a deliverable artifact.

Recommended:

```kotlin
val result = generateDashboardBriefingUseCase(...)
if (result.isDeliverableFor(dateKey)) deliver(...)
```

---

## 7. Daily AI briefing retries all exceptions, including potentially permanent failures

### Where

`DailyBriefingWorker.doWork()`

### Problem

The worker returns `Result.retry()` for any exception except cancellation.

This is good for transient failures, but bad for permanent failures such as:

- missing API key
- invalid AI settings
- provider disabled
- malformed local model state
- deterministic validation failure
- no notification permission, if modeled as exception

### Impact

WorkManager can keep retrying a failure that will never succeed until settings change.

This can waste:

- battery
- network
- API quota
- logs/diagnostics

### Severity

**High**

### Fix

Classify errors:

```text
Transient → retry
Permanent disabled/config missing → success/no-op
User action required → success/no-op + diagnostics
Unexpected bug → retry with limited attempts, then fail/no-op
```

For AI specifically, missing API key should usually be:

```text
Result.success()
```

with diagnostic state, not retry forever.

---

## 8. Startup proactive briefing sync is launched without error containment

### Where

`AppStartupCoordinator.syncProactiveBriefingWork()`

### Problem

It launches:

```kotlin
ProcessLifecycleOwner.get().lifecycleScope.launch {
    syncProactiveBriefingWorkUseCase()
}
```

No `try/catch`.

### Impact

If settings read, WorkManager access, or scheduler logic throws, the coroutine exception is unhandled.

Depending on coroutine exception handling, this can:

- crash in some contexts
- cancel the lifecycle scope
- fail silently with poor diagnostics

### Severity

**Medium / High**

### Fix

Wrap startup async tasks:

```kotlin
lifecycleScope.launch(SupervisorJob() + dispatcher) {
    runCatching { syncProactiveBriefingWorkUseCase() }
        .onFailure { Timber.e(it, "Failed to sync AI briefing work") }
}
```

Better: central startup task runner with per-task failure isolation.

---

## 9. Startup scheduling is not settings-aware for location/warranty workers

### Where

`AppStartupCoordinator.scheduleStartupWork()`

It always schedules:

- location backfill
- merchant-key backfill
- warranty expiration checks

### Problem

No visible check for user settings such as:

- location enrichment enabled
- warranty notifications enabled
- background processing enabled
- notification permission granted

Maybe lower layers handle some of this, but the scheduler itself does not.

### Impact

Workers can keep running after a user disables a feature.

Specific risks:

- location enrichment may still call geocoding providers
- warranty notifications may still be evaluated
- background work continues even if user expects feature off

### Severity

**High for privacy-sensitive location work**

### Fix

Add feature-aware scheduler sync use cases:

```kotlin
SyncLocationBackfillWorkUseCase
SyncWarrantyExpirationWorkUseCase
SyncAiBriefingWorkUseCase
```

Each should either schedule/update or cancel based on current settings.

Also call them when settings change, not only at startup.

---

## 10. Merchant-key backfill can retry forever on deterministic bad rows

### Where

`MerchantKeyBackfillWorker.doWork()`

### Problem

If a row repeatedly fails `updateMerchantKey()`, the worker adds it to a local failed set for this run.

If every row in the batch fails, it returns `Result.retry()`.

There is no persistent failure marker or max attempt count.

### Impact

One bad row can keep the backfill worker retrying forever.

### Severity

**Medium / High**

### Fix

Add persistent backfill status:

```text
merchantKeyBackfillAttempts
merchantKeyBackfillLastError
merchantKeyBackfillFailedAt
```

Or make the update deterministic and non-throwing by using a fallback key:

```text
unknown:<expenseId>
```

If the row is invalid, mark it as skipped rather than retrying forever.

---

## 11. Merchant-key backfill may run too long on very large datasets

### Where

`MerchantKeyBackfillWorker.doWork()`

### Problem

It loops until no null merchant keys remain.

For normal app data this is fine, but with large imports, it could run for a long time.

### Impact

Risk of hitting WorkManager execution limits or creating long startup-era background churn.

### Severity

**Medium**

### Fix

Add a max work budget per run:

```text
maxBatchesPerRun
maxRowsPerRun
maxDurationMs
```

When exceeded:

```kotlin
return Result.retry()
```

This makes the job resumable and bounded.

---

## 12. Background job outputs lack a central run/audit table

### Where

All workers

### Problem

There is no central `BackgroundJobRun`/diagnostics store visible in this layer.

Logs exist, but app/user/developer cannot easily answer:

- when did this worker last run?
- how many rows did it mutate?
- why did it retry?
- did it send a notification?
- did it call cloud AI?
- what settings were active?

### Impact

Hard to debug invisible background side effects.

### Severity

**Medium**

### Fix

Add:

```kotlin
BackgroundJobRun(
    workerName,
    startedAt,
    finishedAt,
    status,
    rowsScanned,
    rowsUpdated,
    notificationsSent,
    retryReason,
    settingsSnapshotHash
)
```

Do not store sensitive raw payloads.

---

# Medium-priority findings

## 13. Lifecycle observer registration is not explicitly idempotent

### Where

`AppStartupCoordinator.registerLifecycleObserver()`

### Problem

It calls:

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycleObserver)
```

Normally `Application.onCreate()` runs once per process, so this is likely fine.

But if startup delegate is called multiple times in tests, preview harnesses, or a future multiprocess setup, the same observer can be added multiple times.

### Impact

`onStop()` cleanup could execute multiple times.

### Fix

Add an `AtomicBoolean initialized` guard in `AppStartupCoordinator`.

---

## 14. `AppBackgroundLifecycleObserver` swallows cleanup errors in release

### Where

`AppBackgroundLifecycleObserver.onStop()`

### Problem

Errors are logged only in debug:

```kotlin
if (BuildConfig.DEBUG) Timber.e(...)
```

### Impact

Release-only cleanup failures become invisible.

### Fix

Record non-sensitive diagnostics in release too, or report to local diagnostics.

---

## 15. AI daily briefing schedule is not aligned to user preference or calendar day

### Where

`AiWorkSchedulerImpl.scheduleDailyBriefing()`

### Problem

Periodic work repeats every 24 hours from the time it was first scheduled.

That means the briefing time depends on app startup/settings toggle time, not user preference.

### Impact

A user may get briefings at odd times.

### Fix

Use one-time work scheduled for next preferred local time, then reschedule after completion.

This also improves date-key semantics and avoids drift.

---

## 16. Warranty worker does reconciliation and notifications in one job

### Where

`WarrantyExpirationWorker.doWork()`

### Problem

It both:

- reconciles expired items
- sends expiring notifications

These are related, but they are different side-effect classes.

### Impact

If notification logic throws, reconciliation success may be retried unnecessarily.

### Fix

Split or transactionally isolate:

- `WarrantyReconciliationWorker`
- `WarrantyReminderWorker`

Or catch notification failures per-item.

---

# Recommended fix order

## PR 1 — Worker scheduling versioning

Add a small worker-spec registry:

```text
workerName
specVersion
policy
constraints
enabled
```

Then update/cancel workers when version/settings change.

Apply to:

- AI daily briefing
- location backfill
- warranty expiration
- merchant-key backfill if needed

## PR 2 — Feature-aware sync use cases

Create:

- `SyncLocationBackfillWorkUseCase`
- `SyncWarrantyExpirationWorkUseCase`
- keep `SyncProactiveBriefingWorkUseCase`
- optional `SyncMerchantKeyBackfillWorkUseCase`

Startup should call sync use cases, not static schedule methods directly.

Settings changes should also call the relevant sync use case.

## PR 3 — Fix location backfill retry/overwrite safety

Add:

- transient attempt tracking
- per-row next eligible retry time
- conditional update that never overwrites manual/user-confirmed location
- setting gate before external geocoding

## PR 4 — Add warranty reminder state

Persist sent/dismissed/snoozed state per warranty/reminder stage.

Send each reminder stage only once unless snoozed/re-enabled.

## PR 5 — Fix daily briefing artifact delivery contract

Replace the `artifact.updatedAt < startedAt` gate with a generation result contract.

The worker should know whether it:

- generated new artifact
- reused a fresh deliverable artifact
- skipped due to disabled/settings
- failed transiently
- failed permanently

## PR 6 — Error classification for workers

Do not `Result.retry()` every exception.

Create reusable classification:

```kotlin
sealed class WorkerFailure {
    object Transient
    object PermanentNoRetry
    object UserActionRequired
}
```

## PR 7 — Central background job diagnostics

Add background job run records with non-sensitive metadata.

---

# Regression tests to add

1. Re-scheduling with same worker spec does not duplicate work.
2. Worker spec version change replaces old periodic work.
3. Disabling proactive AI cancels daily briefing work.
4. Re-enabling proactive AI schedules daily briefing once.
5. AI daily briefing with missing API key does not retry forever.
6. AI daily briefing reusing today’s fresh artifact can still deliver if intended.
7. AI daily briefing does not send duplicate notification for same date key.
8. Location backfill does not overwrite manual location added after fetch.
9. Location retryable failure increments retry/backoff state.
10. Location worker does not run external geocoding when feature disabled.
11. Merchant-key backfill handles one permanently bad row without infinite retry.
12. Merchant-key backfill stops after max rows/time and resumes later.
13. Warranty 30-day reminder sends once per warranty/stage.
14. Warranty 7-day reminder sends once per warranty/stage.
15. Dismissed/snoozed warranty reminders are respected.
16. Startup async sync failure is logged and does not crash app startup.
17. Lifecycle observer is not registered twice if startup initializes twice.

---

# Top three fixes

If you only fix three things first:

1. **Replace blind `KEEP` scheduling with versioned/updateable worker specs.**
2. **Make warranty and location workers persist per-item notification/attempt state.**
3. **Fix DailyBriefingWorker’s cached-artifact delivery semantics and permanent-failure retry behavior.**

These remove the biggest duplicate-side-effect, privacy, and reliability risks.

---

# Sources reviewed

- `MainApplication.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/MainApplication.kt

- `AppStartupDelegate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/startup/AppStartupDelegate.kt

- `AppStartupCoordinator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `AppBackgroundLifecycleObserver.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/startup/AppBackgroundLifecycleObserver.kt

- `LocationBackfillWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `MerchantKeyBackfillWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt

- `WarrantyExpirationWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- `AiWorkScheduler.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/service/AiWorkScheduler.kt

- `AiWorkSchedulerImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt

- `DailyBriefingWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt

- `SyncProactiveBriefingWorkUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt

- `DeliverProactiveBriefingNotificationUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt