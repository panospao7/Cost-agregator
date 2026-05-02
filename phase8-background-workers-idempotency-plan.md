# Phase 8 — Background Workers & Idempotency Implementation Plan

## 0. Phase 8 Mission

Phase 8 creates a reliable, observable, versioned background-work foundation.

Current audit problems:

- 7 WorkManager workers exist, but 2 are dead because their `schedule()` methods are never called.
- All periodic workers use `ExistingPeriodicWorkPolicy.KEEP`, which freezes worker config forever on installed devices.
- There is no worker spec registry.
- There is no `BackgroundJobRun` table.
- Most workers retry every exception forever.
- `WarrantyExpirationWorker` can send duplicate notifications every daily run.
- `LocationBackfillWorker` can overwrite user-set locations.
- `ReceiptMatchingWorker` has a wrong package declaration.
- Notification service uses an aggressive AlarmManager keepalive path with limited observability.
- Worker scheduling is scattered across startup, settings use cases, and static companion methods.

Phase 8 goal:

1. Centralize worker specs.
2. Version worker scheduling.
3. Make workers idempotent.
4. Add persistent run tracking.
5. Add failure classification.
6. Wire dead workers.
7. Prevent duplicate notifications/links/updates.
8. Make settings-gated workers schedule/cancel correctly.
9. Add guardrails so future workers cannot bypass the foundation.

---

# 1. Preconditions

Before Phase 8 starts:

1. Phase 6 privacy gates must compile and be wired.
2. Phase 7 DB migrations must be stable.
3. Room schema export must match current DB version.
4. Hilt graph must compile.
5. Run:
   - `./gradlew.bat :app:compileDebugKotlin`
   - `./gradlew.bat :app:kaptDebugKotlin`
   - `./gradlew.bat :app:testDebugUnitTest`

Phase 8 adds new DB tables, so use the next DB version after Phase 6/7.

Throughout the plan:

```text
NEXT_DB_VERSION = current DB version + 1
```

---

# 2. Non-goals

Do not use Phase 8 to redesign every feature.

Out of scope:

- replacing WorkManager
- replacing NotificationListenerService
- exact alarm implementation for bill reminders
- full foreground-service reliability redesign
- network sync engine
- cloud queue system
- advanced distributed locking
- user-visible worker dashboard beyond Debug diagnostics
- rewriting all worker business logic

---

# 3. Target Architecture

## 3.1 New background work package

Suggested package:

`domain/background`

Core domain models:

- `BackgroundWorkerName`
- `BackgroundWorkerSpec`
- `BackgroundWorkerKind`
- `BackgroundWorkerStatus`
- `BackgroundWorkerOutcome`
- `BackgroundWorkerFailure`
- `BackgroundWorkerMetrics`
- `BackgroundWorkerRegistry`
- `BackgroundWorkScheduler`
- `BackgroundJobTracker`
- `WorkerFailureClassifier`

Data package:

`data/background`

- `BackgroundWorkerSpecState`
- `BackgroundJobRun`
- `BackgroundJobItemState`
- `BackgroundWorkerSpecDao`
- `BackgroundJobRunDao`
- `BackgroundJobItemStateDao`
- `BackgroundWorkSchedulerImpl`
- `RoomBackgroundJobTracker`

Startup package integration:

- `SyncAllBackgroundWorkUseCase`
- worker-specific sync use cases

---

## 3.2 Scheduling ownership

Today, workers schedule themselves using companion `schedule()` methods.

Target:

- Companion `schedule()` methods become deprecated wrappers.
- `BackgroundWorkScheduler` becomes the only normal scheduler.
- `AppStartupCoordinator` calls `SyncAllBackgroundWorkUseCase`.
- Settings screens call relevant sync use cases when settings change.
- Each worker has a `BackgroundWorkerSpec` in a central registry.

---

## 3.3 Worker execution ownership

Every worker should use a common execution wrapper:

```kotlin
backgroundJobTracker.track(
    workerName = BackgroundWorkerName.DATA_RETENTION,
    specVersion = BackgroundWorkerRegistry.DATA_RETENTION.version,
    runAttemptCount = runAttemptCount
) {
    // worker body returns BackgroundWorkerOutcome
}
```

The wrapper records:

- startedAt
- finishedAt
- outcome
- row counts
- notifications sent
- retry/failure reason
- error class/message
- worker version
- settings hash if useful

---

# 4. Database Design

## 4.1 `background_worker_spec_states`

Purpose:

Track which worker spec version has been applied to WorkManager on this install.

```kotlin
@Entity(
    tableName = "background_worker_spec_states",
    indices = [
        Index(value = ["workerName"], unique = true)
    ]
)
data class BackgroundWorkerSpecState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,
    val appliedSpecVersion: Int,
    val enabled: Boolean,
    val workManagerUniqueName: String,
    val appliedAt: Long,
    val lastSyncedAt: Long,
    val lastCancelledAt: Long?,
    val lastEnqueuedAt: Long?,
    val reason: String?
)
```

Use cases:

- detect stale scheduled specs
- cancel/re-enqueue on version change
- cancel when feature is disabled
- avoid relying on `ExistingPeriodicWorkPolicy.KEEP`

---

## 4.2 `background_job_runs`

Purpose:

Persistent observability for worker executions.

```kotlin
@Entity(
    tableName = "background_job_runs",
    indices = [
        Index(value = ["workerName", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["specVersion"])
    ]
)
data class BackgroundJobRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,
    val specVersion: Int,
    val workManagerId: String?,
    val runAttemptCount: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String, // RUNNING, SUCCESS, RETRY, FAILED_PERMANENT, FAILED_EXHAUSTED, CANCELLED
    val rowsScanned: Int = 0,
    val rowsUpdated: Int = 0,
    val rowsSkipped: Int = 0,
    val notificationsSent: Int = 0,
    val itemsSucceeded: Int = 0,
    val itemsFailed: Int = 0,
    val retryReason: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val settingsSnapshotHash: String? = null,
    val metadataJson: String? = null
)
```

Policy:

- if a worker crashes before finish, the run remains `RUNNING`
- on next startup, mark stale `RUNNING` records older than a threshold as `ABANDONED`

---

## 4.3 `background_job_item_states`

Purpose:

Generic per-item idempotency and failure state.

```kotlin
@Entity(
    tableName = "background_job_item_states",
    indices = [
        Index(value = ["workerName", "itemKey", "actionKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["nextEligibleAt"])
    ]
)
data class BackgroundJobItemState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,
    val itemKey: String,
    val actionKey: String,
    val status: String, // PENDING, IN_PROGRESS, SUCCESS, SKIPPED, TRANSIENT_FAILED, PERMANENT_FAILED
    val attemptCount: Int = 0,
    val firstAttemptAt: Long?,
    val lastAttemptAt: Long?,
    val completedAt: Long?,
    val nextEligibleAt: Long?,
    val lastErrorClass: String?,
    val lastErrorMessage: String?,
    val metadataJson: String?
)
```

Use this for:

- warranty notification delivery sent-state
- location backfill per-expense failures
- receipt matching per-receipt failures if needed
- future workers

For workers that already have specific state tables, this generic table can supplement rather than replace them.

---

# 5. Worker Spec Registry

## 5.1 `BackgroundWorkerSpec`

```kotlin
data class BackgroundWorkerSpec(
    val workerName: BackgroundWorkerName,
    val uniqueWorkName: String,
    val workerClass: KClass<out ListenableWorker>,
    val version: Int,
    val kind: BackgroundWorkerKind,
    val intervalMinutes: Long?,
    val flexMinutes: Long?,
    val initialDelayMinutes: Long?,
    val requiredNetwork: NetworkType,
    val requiresBatteryNotLow: Boolean,
    val requiresCharging: Boolean,
    val backoffPolicy: BackoffPolicy,
    val backoffDelayMs: Long,
    val maxRunAttempts: Int,
    val enabledByDefault: Boolean,
    val tags: Set<String>
)
```

Kinds:

- `PERIODIC`
- `ONE_TIME`
- `MANUAL_ONE_SHOT`

## 5.2 Current registry entries

### `DATA_RETENTION`

- periodic
- 24h
- network not required
- enabled if privacy retention enabled
- version starts at 1

### `LOCATION_BACKFILL`

- periodic
- 6h initially, consider 12h if too aggressive
- network unmetered
- enabled if privacy/location gates allow background backfill
- version starts at 1

### `BILL_REMINDER`

- periodic
- 6h with flex 15m
- network not required
- enabled if reminders enabled and notification permission policy allows
- version starts at 1

### `RECEIPT_MATCHING`

- periodic
- 2h
- network not required
- enabled if receipt auto-matching enabled
- version starts at 1

### `DAILY_BRIEFING`

- periodic
- 24h
- consider unmetered/network constraint if cloud AI can be involved
- enabled through existing AI proactive briefing settings
- version starts at 1

### `WARRANTY_EXPIRATION`

- periodic
- 24h
- network not required
- enabled if warranty notifications enabled
- version starts at 1

### `MERCHANT_KEY_BACKFILL`

- one-time
- local
- enabled until completed
- version starts at 1

---

# 6. Scheduling Rules

## 6.1 Replace `KEEP` freeze problem

Current:

```kotlin
enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
```

Problem:

- installed devices never get updated constraints/intervals

Target scheduling:

```kotlin
val applied = specDao.get(workerName)
if (applied == null || applied.appliedSpecVersion < current.version || applied.enabled != shouldBeEnabled) {
    workManager.cancelUniqueWork(current.uniqueWorkName)
    if (shouldBeEnabled) {
        enqueue current request
    }
    specDao.upsert(...)
}
```

Alternative:

- if current WorkManager version supports `ExistingPeriodicWorkPolicy.UPDATE`, you may use it, but still keep the registry because you need observability and settings sync.

## 6.2 Disable behavior

When feature disabled:

1. cancel unique work
2. update spec state `enabled = false`
3. do not enqueue
4. worker still re-checks settings at runtime in case it was already running

## 6.3 Startup behavior

`AppStartupCoordinator.scheduleStartupWork()` should call one use case:

```kotlin
syncAllBackgroundWorkUseCase()
```

That use case calls:

- `SyncDataRetentionWorkUseCase`
- `SyncLocationBackfillWorkUseCase`
- `SyncBillReminderWorkUseCase`
- `SyncReceiptMatchingWorkUseCase`
- `SyncWarrantyExpirationWorkUseCase`
- `SyncMerchantKeyBackfillWorkUseCase`
- existing `SyncProactiveBriefingWorkUseCase`

## 6.4 Settings-change behavior

When settings change:

- privacy settings changed → sync data retention + location backfill
- location settings changed → sync location backfill
- AI settings changed → sync daily briefing
- reminder settings changed → sync bill reminder worker
- warranty notification settings changed → sync warranty worker
- receipt matching setting changed → sync receipt matching worker

---

# 7. Execution Tracking Contract

## 7.1 Outcome model

Avoid naming conflict with WorkManager `Result`.

```kotlin
sealed class BackgroundWorkerOutcome {
    data class Success(
        val rowsScanned: Int = 0,
        val rowsUpdated: Int = 0,
        val rowsSkipped: Int = 0,
        val notificationsSent: Int = 0,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()

    data class Retry(
        val reason: String,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()

    data class PermanentFailure(
        val reason: String,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()
}
```

## 7.2 Tracker mapping to WorkManager result

For periodic workers:

- `Success` → `Result.success()`
- `Retry` → `Result.retry()` until max attempts, then record exhausted and return `Result.success()` to avoid infinite retry
- `PermanentFailure` → record and return `Result.success()` for periodic workers

For one-shot workers:

- `Success` → `Result.success()`
- `Retry` → `Result.retry()` until max attempts, then `Result.failure()`
- `PermanentFailure` → `Result.failure()` or `Result.success()` depending on whether retrying is useful

## 7.3 Exception classifier

Add:

`WorkerFailureClassifier`

Classify:

### Transient

- network timeout
- IOException from external calls
- SQLite database locked
- temporary permission unavailable
- WorkManager constraints interrupted

### Permanent

- illegal schema/state assumptions
- invalid worker input data
- missing required dependency
- malformed stored URI
- permission permanently denied when action requires it
- `SecurityException` for missing notification permission when no fallback exists

### Unknown

- retry once or twice, then mark exhausted

---

# 8. Worker-Specific Implementation Plan

## PR 0 — Baseline and worker registry design doc

### Goal

No behavior change. Document the contract.

### Actions

1. Add `docs/development/BACKGROUND_WORKERS.md`.
2. Document each worker:
   - unique name
   - interval
   - constraints
   - settings gate
   - idempotency key
   - side effects
   - retry policy
3. Add constants for all worker unique names.
4. Add a guardrail note: no direct `Worker.schedule()` calls after Phase 8.

### Done when

- worker ownership and scheduling policy are explicit.

---

## PR 1 — Schema: worker spec state + job runs + item state

### Goal

Add the persistence foundation.

### DB migration

Add tables:

- `background_worker_spec_states`
- `background_job_runs`
- `background_job_item_states`

Add DAOs.

Add AppDatabase entities/DAO methods.

### Tests

- migration creates tables
- insert/update spec state
- insert/finish job run
- unique item state works
- stale running job can be marked abandoned

### Done when

- observability/idempotency state can be persisted.

---

## PR 2 — BackgroundWorkerRegistry and scheduler

### Goal

Centralize WorkManager scheduling.

### Add

- `BackgroundWorkerRegistry`
- `BackgroundWorkScheduler`
- `BackgroundWorkSchedulerImpl`
- `SyncAllBackgroundWorkUseCase`
- worker-specific sync use cases

### Scheduler behavior

For each worker:

1. evaluate setting/gate
2. load applied spec state
3. if disabled → cancel and mark disabled
4. if enabled and missing/stale → cancel old work, enqueue new spec
5. if enabled and current → no-op

### Migrate startup

Replace direct calls in `AppStartupCoordinator`:

Current:

```kotlin
LocationBackfillWorker.schedule(...)
MerchantKeyBackfillWorker.schedule(...)
WarrantyExpirationWorker.schedule(...)
DataRetentionWorker.schedule(...)
```

Target:

```kotlin
syncAllBackgroundWorkUseCase()
```

### Tests

- first startup enqueues enabled workers
- spec version bump cancels/re-enqueues
- disabled setting cancels worker
- no-op when spec current
- one-shot backfill does not endlessly enqueue if completed

### Done when

- `KEEP` no longer freezes worker config.

---

## PR 3 — Execution tracker wrapper

### Goal

Every worker writes `BackgroundJobRun`.

### Add

- `BackgroundJobTracker`
- `RoomBackgroundJobTracker`
- `TrackedWorkerRunner`
- `WorkerFailureClassifier`

### Migrate workers one by one

Start with low-risk:

1. `MerchantKeyBackfillWorker`
2. `DataRetentionWorker`
3. `DailyBriefingWorker`
4. `LocationBackfillWorker`
5. `ReceiptMatchingWorker`
6. `WarrantyExpirationWorker`
7. `BillReminderWorker`

### Worker pattern

```kotlin
override suspend fun doWork(): Result {
    return trackedWorkerRunner.run(
        workerName = BackgroundWorkerName.DATA_RETENTION,
        specVersion = BackgroundWorkerRegistry.DATA_RETENTION.version,
        runAttemptCount = runAttemptCount,
        workManagerId = id.toString()
    ) {
        runInternal()
    }
}
```

### Tests

- successful worker writes SUCCESS run
- retry worker writes RETRY run
- permanent failure writes FAILED_PERMANENT
- crash path records error
- stale RUNNING rows marked ABANDONED on next startup

### Done when

- every worker has persistent run diagnostics.

---

## PR 4 — Wire dead workers

### Goal

Activate workers that currently never schedule.

## 4.1 BillReminderWorker

Actions:

1. Add `SyncBillReminderWorkUseCase`.
2. Add reminder notification setting if missing:
   - `billRemindersEnabled`
   - default can be true only if user already created reminders/rules, but safer default is false until user enables notifications.
3. Schedule/cancel via registry.
4. Re-check notification permission and setting inside worker.
5. Use existing `RecurringReminderDelivery` status gate.
6. Use job tracking.

Tests:

- startup schedules when enabled
- disabled setting cancels
- worker sends due delivery once
- second run sends zero duplicates
- missing notification permission does not crash
- delivery status becomes SENT

## 4.2 ReceiptMatchingWorker

Actions:

1. Fix package declaration:
   - from `com.yourname.expensetracker.data.repository`
   - to `com.yourname.expensetracker.service.receiptmatching`
2. Update Hilt/manifest/import references if needed.
3. Add `SyncReceiptMatchingWorkUseCase`.
4. Add setting if desired:
   - `receiptAutoMatchingEnabled`
   - default true if local-only and safe, or false if user should opt in.
5. Ensure worker excludes:
   - already linked receipts
   - bank statements
   - rejected receipts
6. Use `ReceiptLinkService` only.
7. Use job tracking.

Tests:

- startup schedules when enabled
- package/Hilt worker instantiates
- matched receipt links once
- second run no duplicate link
- suggested match saved once
- rejected match skipped

### Done when

- no dead worker schedule methods remain.

---

## PR 5 — Warranty notification idempotency

### Goal

Stop duplicate warranty notifications.

### Recommended state

Use generic `background_job_item_states`.

Item keys:

```text
workerName = warranty_expiration
itemKey = warranty:<warrantyId>
actionKey = expiring_7_days
actionKey = expiring_30_days
actionKey = expired
```

Alternative: create `warranty_notification_deliveries`, but generic state is preferable for Phase 8.

### Worker flow

1. Query expiring warranties.
2. For each warranty/window:
   - attempt to insert item state as `IN_PROGRESS` or check existing `SUCCESS`
   - if `SUCCESS`, skip
   - send notification
   - mark item state `SUCCESS`
3. If notification permission missing:
   - mark permanent or skipped, depending on policy
4. Use ID-based filtering:
   - replace `it !in expiringIn7Days` with ID set filtering

### Tests

- 30-day notification sent once
- 7-day notification sent once
- daily rerun sends zero duplicates
- 30-day and 7-day windows can both send if desired policy allows
- missing permission handled
- ID-based filter correct

### Done when

- warranty worker is idempotent.

---

## PR 6 — Location backfill overwrite guard

### Goal

Prevent worker from overwriting user-set/manual location.

### Current risk

Worker fetches unlocated expense, resolves location, then updates. User may manually set location before update.

### Fix

Add DAO conditional update:

```sql
UPDATE expenses
SET latitude = :lat,
    longitude = :lng,
    locationSource = :source,
    placeId = :placeId,
    address = :address
WHERE id = :expenseId
  AND latitude IS NULL
  AND longitude IS NULL
```

Return affected row count.

If 0 rows affected:

- count as skipped
- do not retry

### Add per-item failure state

For retryable failures:

```text
workerName = location_backfill
itemKey = expense:<id>
actionKey = geocode
```

Store attempt count and next eligible time.

Do not hammer permanently failing merchants.

### Tests

- conditional update succeeds when still empty
- conditional update skips if user set location
- retryable failure records item state
- permanently invalid merchant does not retry forever
- privacy gate denied records skipped run, no HTTP calls

### Done when

- location worker cannot overwrite manual user data.

---

## PR 7 — Retry classification for all workers

### Goal

Stop infinite retry loops.

### Actions

For each worker, define:

- transient exceptions
- permanent exceptions
- max attempts
- behavior after exhausted attempts

### Defaults

- periodic max attempts: 3
- one-shot max attempts: 5
- permanent periodic failure returns `Result.success()` after recording failure
- exhausted retry returns `Result.success()` for periodic to prevent retry storm

### Worker-specific notes

#### DataRetentionWorker

- SQLite locked → retry
- missing column/schema → permanent
- invalid settings → permanent/default repair

#### LocationBackfillWorker

- network/provider timeout → retry
- privacy denied → success/skipped
- no permission when GPS required → success/skipped
- malformed merchant/location input → permanent item failure

#### BillReminderWorker

- notification permission missing → success/skipped or permanent until permission changes
- DB failure → retry
- bad delivery row → permanent item failure

#### ReceiptMatchingWorker

- keep existing permanent/transient classification
- integrate with common classifier

#### DailyBriefingWorker

- cloud/network timeout → retry if cloud allowed
- AI disabled → success/skipped
- permanent prompt/config issue → permanent

#### WarrantyExpirationWorker

- notification permission missing → success/skipped
- malformed warranty date → permanent item failure

#### MerchantKeyBackfillWorker

- DB locked → retry
- permanently bad merchant name → skip item and continue

### Tests

- permanent errors do not retry forever
- transient errors retry up to max
- exhausted attempts are recorded
- periodic worker does not get stuck in retry storm

### Done when

- retry behavior is predictable and observable.

---

## PR 8 — Worker settings sync and cancellation

### Goal

Workers react to settings changes, not just app startup.

### Actions

1. Add setting-change hooks:
   - privacy settings → data retention + location
   - AI settings → daily briefing
   - reminder settings → bill reminder
   - warranty settings → warranty worker
   - receipt settings → receipt matching
2. Existing `SyncProactiveBriefingWorkUseCase` remains but uses central scheduler.
3. On setting disabled:
   - cancel unique work
   - update spec state
4. Worker still checks setting at runtime.

### Tests

- enabling setting enqueues worker
- disabling setting cancels worker
- version change while disabled does not enqueue until enabled
- runtime-denied worker exits success/skipped

### Done when

- background work follows user settings immediately.

---

## PR 9 — Foreground service and AlarmManager observability

### Goal

Bring `NotificationCaptureService` keepalive into the same discipline.

### Actions

1. BootReceiver should check notification capture/privacy setting before starting service.
2. ServiceRestartReceiver should check notification capture/privacy setting before starting service.
3. If capture disabled:
   - cancel restart alarm
   - stop foreground service if running
4. Add service event tracking:
   - service started
   - service stopped
   - restart alarm scheduled
   - restart alarm fired
   - privacy denied start
5. Re-evaluate 15-minute `setRepeating`:
   - consider longer interval
   - consider only scheduling if notification capture enabled
   - avoid aggressive keepalive when disabled
6. Add debug diagnostics.

### Tests

- boot does not start service if capture disabled
- restart receiver does not start service if capture disabled
- enabling capture starts/schedules as expected
- disabling capture cancels alarm
- service diagnostics recorded

### Done when

- foreground service respects privacy gates and is observable.

---

## PR 10 — Guardrails and cleanup

### Goal

Prevent future background work bypasses.

### Guardrail scans

Flag:

- `enqueueUniquePeriodicWork` outside `BackgroundWorkSchedulerImpl`
- `enqueueUniqueWork` outside scheduler except tests
- `ExistingPeriodicWorkPolicy.KEEP` outside scheduler
- worker companion `schedule()` direct calls
- new `CoroutineWorker` not in `BackgroundWorkerRegistry`
- `Result.retry()` on broad `Exception` without classifier
- notification send from worker without item-state or delivery dedup
- external/network worker without settings/privacy gate
- direct AlarmManager keepalive scheduling outside approved service coordinator

### Cleanup

1. Deprecate existing worker companion `schedule()` methods.
2. Remove direct calls from startup.
3. Update docs.
4. Add debug screen section for recent worker runs.

### Tests

- registry contains all `@HiltWorker` classes
- scan fails for direct scheduling
- scan fails for unregistered worker
- scan fails for `KEEP` outside scheduler

### Done when

- future workers must participate in the foundation.

---

# 9. Worker-Specific Final Target

## DataRetentionWorker

Target:

- scheduled through registry
- tracked in `background_job_runs`
- respects privacy settings
- idempotent via purged timestamps
- classified failures
- no infinite retries

## LocationBackfillWorker

Target:

- scheduled only if background location backfill enabled
- tracked
- unmetered constraint versioned
- no overwrite of manual locations
- per-expense failure state
- no endless retry for bad rows

## BillReminderWorker

Target:

- actually scheduled
- setting-aware
- notification-permission-aware
- uses recurring reminder delivery sent-state
- tracked
- idempotent

## ReceiptMatchingWorker

Target:

- correct package
- actually scheduled
- local setting-aware if added
- uses receipt/link lifecycle safely
- tracked
- no duplicate links

## DailyBriefingWorker

Target:

- scheduled through registry
- AI settings-aware
- constraints versioned
- tracked
- failure classified
- duplicate notification prevented by engagement key

## WarrantyExpirationWorker

Target:

- setting-aware if notifications configurable
- tracked
- no duplicate warranty notifications
- ID-based window filtering
- notification permission handling
- failure classified

## MerchantKeyBackfillWorker

Target:

- one-shot spec state
- tracked
- idempotent
- bounded batches
- no infinite loop on permanently bad rows

---

# 10. Test Strategy

## 10.1 Scheduler tests

- first startup enqueues all enabled workers
- disabled workers are not enqueued
- spec version bump re-enqueues
- unchanged spec no-ops
- setting disabled cancels
- one-shot completion prevents repeated enqueue

## 10.2 Job run tracking tests

- success run recorded
- retry run recorded
- permanent failure recorded
- stale running marked abandoned
- metrics saved correctly

## 10.3 Idempotency tests

- DataRetentionWorker twice does not re-purge
- BillReminderWorker twice sends once
- WarrantyExpirationWorker twice sends once
- ReceiptMatchingWorker twice links once
- LocationBackfillWorker skips if location set between read and write
- MerchantKeyBackfillWorker twice only fills null keys

## 10.4 Failure classification tests

- transient DB lock retries
- permanent bad input does not retry forever
- max attempts exhausted recorded
- notification permission missing handled
- privacy denied returns success/skipped, not retry

## 10.5 Worker registry guardrail tests

- every `@HiltWorker` registered
- no direct scheduling outside scheduler
- no `KEEP` outside scheduler
- no untracked worker run

## 10.6 Foreground service tests

- boot disabled does not start service
- restart disabled does not start service
- enabling capture starts service path
- disabling capture cancels alarm

---

# 11. Migration Plan

Assuming current DB version is `N`, add `MIGRATION_N_NPLUS1`.

Tables:

- `background_worker_spec_states`
- `background_job_runs`
- `background_job_item_states`

Indexes:

- unique worker name in spec state
- job run by worker/start/status
- unique `(workerName, itemKey, actionKey)` in item state

No destructive migration.

Add latest schema JSON.

Migration tests:

- old DB migrates
- tables exist
- indexes exist
- DAOs work
- fresh install has same tables/indexes

---

# 12. Acceptance Criteria

Phase 8 is complete when:

1. All workers are represented in `BackgroundWorkerRegistry`.
2. Direct worker scheduling is removed from startup.
3. Worker config is versioned and can be updated on installed devices.
4. `BackgroundJobRun` records every worker execution.
5. Worker run metrics are visible in debug diagnostics.
6. BillReminderWorker is scheduled when enabled.
7. ReceiptMatchingWorker is scheduled when enabled and has correct package.
8. WarrantyExpirationWorker cannot send duplicate notifications.
9. LocationBackfillWorker cannot overwrite manual locations.
10. DataRetentionWorker remains idempotent and tracked.
11. DailyBriefingWorker is registry-scheduled and tracked.
12. MerchantKeyBackfillWorker is one-shot tracked and idempotent.
13. Settings changes schedule/cancel relevant workers.
14. Runtime settings checks still exist inside workers.
15. Failure classification prevents infinite retry loops.
16. Guardrails block unregistered workers and direct WorkManager scheduling.
17. Boot/service restart respects notification capture/privacy gates.
18. Migration and schema tests pass.
19. Idempotency tests pass for every worker.
20. Documentation reflects the worker registry and idempotency policy.

---

# 13. Recommended Implementation Order

1. Baseline docs and worker constants.
2. Add DB tables for spec state, job runs, item states.
3. Add registry and scheduler.
4. Add execution tracker.
5. Migrate startup scheduling to `SyncAllBackgroundWorkUseCase`.
6. Wire BillReminderWorker.
7. Fix and wire ReceiptMatchingWorker.
8. Add warranty notification idempotency.
9. Add location overwrite guard and item state.
10. Add failure classification to all workers.
11. Add settings-change sync/cancellation.
12. Add foreground service/AlarmManager privacy-aware observability.
13. Add guardrails and debug diagnostics.
14. Run full tests and close audit rows only when verified.

This order builds the platform first, then activates dead workers, then fixes duplicate side effects and retry behavior.