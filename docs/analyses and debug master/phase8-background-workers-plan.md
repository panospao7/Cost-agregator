# Phase 8 — Background Workers & Idempotency Final Implementation Plan

**Date:** 2026-05-02  
**Source:** Evaluation of `phase8-background-workers-idempotency-plan.md` against `background-workers-audit.md` (Option A — endorsed with refinements).  
**DB version:** 104 → **105**.  
**Phase 8 goal:** worker spec versioning, `BackgroundJobRun` observability, idempotency, settings-aware workers, failure classification, guardrails.

---

## 0. Mission

Phase 8 creates a reliable, observable, versioned background-work foundation.

### Audit-confirmed problems

| # | Severity | Problem | Workers affected |
|---|---|---|---|
| C1 | CRITICAL | No worker spec registry — `ExistingPeriodicWorkPolicy.KEEP` freezes config forever | ALL periodic |
| C2 | CRITICAL | No `BackgroundJobRun` table — zero execution observability | ALL |
| C3 | CRITICAL | `BillReminderWorker.schedule()` never called — dead code | BillReminderWorker |
| C4 | CRITICAL | `ReceiptMatchingWorker.schedule()` never called + wrong `package` declaration | ReceiptMatchingWorker |
| C5 | HIGH | `WarrantyExpirationWorker` sends duplicate notifications every run (no per-warranty sent-state) | WarrantyExpirationWorker |
| C6 | HIGH | `LocationBackfillWorker` can overwrite user-set locations (race condition) | LocationBackfillWorker |
| R1 | HIGH | Infinite retry loops — most workers return `Result.retry()` for ALL exceptions | 6 of 7 workers |
| R2 | MEDIUM | `DailyBriefingWorker` has **zero** constraints (no network/battery) | DailyBriefingWorker |
| R3 | MEDIUM | `LocationBackfillWorker` interval (6h) is aggressive for a Wi-Fi-only geocoder | LocationBackfillWorker |
| R4 | MEDIUM | `AlarmManager.setRepeating` is inexact on API 31+; 15-minute keepalive may not fire | NotificationCaptureService |
| R5 | MEDIUM | `ReceiptMatchingWorker` has wrong `package com.yourname.expensetracker.data.repository` | ReceiptMatchingWorker |
| R6 | LOW | `MerchantKeyBackfillWorker` has no max-budget cap — can loop indefinitely on large datasets | MerchantKeyBackfillWorker |

### Phase 8 objectives

1. Centralize worker specs in a versioned registry.
2. Replace `KEEP` policy with version-aware scheduling so config updates propagate.
3. Add `BackgroundJobRun` persistent run tracking for every worker.
4. Add `BackgroundJobItemState` for generic per-item idempotency.
5. Classify failures (transient vs permanent) and stop infinite retries.
6. Wire dead workers (BillReminderWorker + ReceiptMatchingWorker).
7. Prevent duplicate notifications/links/location overwrites.
8. Make all workers react to settings changes immediately.
9. Add guardrails so future workers cannot bypass the foundation.
10. Bring foreground-service/AlarmManager into the same observability discipline.

---

## 1. Preconditions

Before Phase 8 begins:

1. Phase 6 privacy gates compile and are wired.
2. Phase 7 DB migrations stable.
3. Room schema export matches DB version **104** (latest: `104.json`).
4. Hilt graph compiles.
5. Run:
   ```
   ./gradlew.bat :app:compileDebugKotlin
   ./gradlew.bat :app:kaptDebugKotlin
   ./gradlew.bat :app:testDebugUnitTest
   ```

Phase 8 bumps DB from **104 → 105** (adds three new tables).

---

## 2. Non-Goals

- Replacing WorkManager.
- Replacing `NotificationListenerService`.
- Exact alarm implementation for bill reminders.
- Full foreground-service reliability redesign.
- Network sync engine or cloud queue.
- User-visible worker dashboard beyond debug diagnostics.
- Rewriting all worker business logic.
- Redesigning the `NotificationCaptureService` in-memory `LinkedHashMap` dedup (500 entries, 5s window) — noted for future work.

---

## 3. Target Architecture

### 3.1 Package layout

```
domain/background/
  BackgroundWorkerName          (enum: 7 entries)
  BackgroundWorkerSpec          (data class)
  BackgroundWorkerKind          (enum: PERIODIC, ONE_TIME, MANUAL_ONE_SHOT)
  BackgroundWorkerStatus        (enum)
  BackgroundWorkerOutcome       (sealed class)
  BackgroundWorkerRegistry      (interface + object)
  BackgroundWorkScheduler       (interface)
  BackgroundJobTracker          (interface)
  WorkerFailureClassifier       (object/class)

data/background/
  BackgroundWorkerSpecState     (@Entity — table background_worker_spec_states)
  BackgroundJobRun              (@Entity — table background_job_runs)
  BackgroundJobItemState        (@Entity — table background_job_item_states)
  BackgroundWorkerSpecDao       (DAO)
  BackgroundJobRunDao           (DAO)
  BackgroundJobItemStateDao     (DAO)
  BackgroundWorkSchedulerImpl   (implements BackgroundWorkScheduler)
  RoomBackgroundJobTracker      (implements BackgroundJobTracker)

domain/background/usecase/
  SyncAllBackgroundWorkUseCase
  SyncDataRetentionWorkUseCase
  SyncLocationBackfillWorkUseCase
  SyncBillReminderWorkUseCase
  SyncReceiptMatchingWorkUseCase
  SyncWarrantyExpirationWorkUseCase
  SyncMerchantKeyBackfillWorkUseCase
```

NOTE: `SyncProactiveBriefingWorkUseCase` already exists at `domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt`. The daily briefing worker sync routes **through that existing use case**, not a new one. `SyncAllBackgroundWorkUseCase` composes all of the above (including the AI one).

### 3.2 Scheduling ownership

**Current state:**
- Workers schedule themselves via companion `schedule(context)` methods.
- 4 workers scheduled in `scheduleStartupWork()`.
- 1 worker scheduled via `SyncProactiveBriefingWorkUseCase` → `AiWorkSchedulerImpl.scheduleDailyBriefing()`.
- 2 workers never scheduled (dead).

**Target:**
- Companion `schedule()` methods become deprecated wrappers that delegate to `BackgroundWorkScheduler`.
- `BackgroundWorkSchedulerImpl` is the ONLY class that calls `WorkManager.enqueueUniquePeriodicWork` / `enqueueUniqueWork`.
- `AppStartupCoordinator.initialize()` calls `SyncAllBackgroundWorkUseCase`.
- Settings screens call individual sync use cases when settings change.
- `AiWorkSchedulerImpl.scheduleDailyBriefing()` delegates to `BackgroundWorkScheduler`.

### 3.3 Execution ownership

Every worker must use a common execution wrapper:

```kotlin
backgroundJobTracker.track(
    workerName = BackgroundWorkerName.DATA_RETENTION,
    specVersion = BackgroundWorkerRegistry.specFor(BackgroundWorkerName.DATA_RETENTION).version,
    runAttemptCount = runAttemptCount,
    workManagerId = id.toString()
) {
    // worker body → returns BackgroundWorkerOutcome
}
```

The wrapper records: `startedAt`, `finishedAt`, `status`, `rowsScanned`, `rowsUpdated`, `notificationsSent`, `retryReason`, `errorClass`, `errorMessage`, `specVersion`, `settingsSnapshotHash`.

On startup: mark stale `RUNNING` records (older than 12 hours) as `ABANDONED`.

---

## 4. Database Design (MIGRATION 104 → 105)

### 4.1 `background_worker_spec_states`

Tracks which spec version has been applied to WorkManager on this device.

```kotlin
@Entity(
    tableName = "background_worker_spec_states",
    indices = [Index(value = ["workerName"], unique = true)]
)
data class BackgroundWorkerSpecState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,              // e.g. "data_retention"
    val appliedSpecVersion: Int,         // spec version applied
    val enabled: Boolean,                // true when worker should be active
    val workManagerUniqueName: String,   // e.g. "data_retention"
    val appliedAt: Long,                 // epoch ms
    val lastSyncedAt: Long,              // epoch ms — last time scheduler checked
    val lastCancelledAt: Long? = null,
    val lastEnqueuedAt: Long? = null,
    val reason: String? = null           // e.g. "disabled_by_privacy_settings"
)
```

### 4.2 `background_job_runs`

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
    val status: String,                  // RUNNING, SUCCESS, RETRY, FAILED_PERMANENT, FAILED_EXHAUSTED, CANCELLED, ABANDONED
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

### 4.3 `background_job_item_states`

Generic per-item idempotency and failure state. Used for warranty notifications, location backfill per-expense failures, receipt matching per-receipt failures.

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
    val workerName: String,              // e.g. "warranty_expiration"
    val itemKey: String,                 // e.g. "warranty:42"
    val actionKey: String,               // e.g. "expiring_7_days"
    val status: String,                  // PENDING, IN_PROGRESS, SUCCESS, SKIPPED, TRANSIENT_FAILED, PERMANENT_FAILED
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

This table **supplements** existing per-worker state tables (e.g., `privacy_audit_events` for DataRetention, `RecurringReminderDelivery` for bill reminders). It does NOT replace them. For workers that already have strong state gates (DataRetentionWorker's `purgedAt` timestamps, BillReminderWorker's `SCHEDULED/SENT` delivery status), the item state table is optional — use it when needed for workers that lack native sentinels (WarrantyExpirationWorker, LocationBackfillWorker per-expense failures).

### 4.4 Migration SQL (104 → 105)

```sql
CREATE TABLE background_worker_spec_states (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workerName TEXT NOT NULL,
    appliedSpecVersion INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0,
    workManagerUniqueName TEXT NOT NULL,
    appliedAt INTEGER NOT NULL,
    lastSyncedAt INTEGER NOT NULL,
    lastCancelledAt INTEGER DEFAULT NULL,
    lastEnqueuedAt INTEGER DEFAULT NULL,
    reason TEXT DEFAULT NULL
);
CREATE UNIQUE INDEX idx_bg_worker_spec_states_name ON background_worker_spec_states(workerName);

CREATE TABLE background_job_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workerName TEXT NOT NULL,
    specVersion INTEGER NOT NULL,
    workManagerId TEXT DEFAULT NULL,
    runAttemptCount INTEGER NOT NULL DEFAULT 1,
    startedAt INTEGER NOT NULL,
    finishedAt INTEGER DEFAULT NULL,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    rowsScanned INTEGER NOT NULL DEFAULT 0,
    rowsUpdated INTEGER NOT NULL DEFAULT 0,
    rowsSkipped INTEGER NOT NULL DEFAULT 0,
    notificationsSent INTEGER NOT NULL DEFAULT 0,
    itemsSucceeded INTEGER NOT NULL DEFAULT 0,
    itemsFailed INTEGER NOT NULL DEFAULT 0,
    retryReason TEXT DEFAULT NULL,
    errorClass TEXT DEFAULT NULL,
    errorMessage TEXT DEFAULT NULL,
    settingsSnapshotHash TEXT DEFAULT NULL,
    metadataJson TEXT DEFAULT NULL
);
CREATE INDEX idx_bg_job_runs_worker_started ON background_job_runs(workerName, startedAt);
CREATE INDEX idx_bg_job_runs_status ON background_job_runs(status);
CREATE INDEX idx_bg_job_runs_spec_version ON background_job_runs(specVersion);

CREATE TABLE background_job_item_states (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    workerName TEXT NOT NULL,
    itemKey TEXT NOT NULL,
    actionKey TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attemptCount INTEGER NOT NULL DEFAULT 0,
    firstAttemptAt INTEGER DEFAULT NULL,
    lastAttemptAt INTEGER DEFAULT NULL,
    completedAt INTEGER DEFAULT NULL,
    nextEligibleAt INTEGER DEFAULT NULL,
    lastErrorClass TEXT DEFAULT NULL,
    lastErrorMessage TEXT DEFAULT NULL,
    metadataJson TEXT DEFAULT NULL
);
CREATE UNIQUE INDEX idx_bg_job_item_states_unique ON background_job_item_states(workerName, itemKey, actionKey);
CREATE INDEX idx_bg_job_item_states_status ON background_job_item_states(status);
CREATE INDEX idx_bg_job_item_states_next_eligible ON background_job_item_states(nextEligibleAt);
```

Also: bump `APP_DATABASE_SCHEMA_VERSION` to 105, add `MIGRATION_104_105` to `ALL_MIGRATIONS`, export schema `105.json` via `./gradlew.bat :app:kaptDebugKotlin`.

---

## 5. Worker Spec Registry

### 5.1 `BackgroundWorkerSpec`

```kotlin
data class BackgroundWorkerSpec(
    val workerName: BackgroundWorkerName,
    val uniqueWorkName: String,          // WorkManager unique name
    val workerClass: KClass<out ListenableWorker>,
    val version: Int,                    // bumped whenever interval/constraint/backoff changes
    val kind: BackgroundWorkerKind,
    val intervalMinutes: Long?,
    val flexMinutes: Long? = null,
    val initialDelayMinutes: Long? = null,
    val requiredNetwork: NetworkType,
    val requiresBatteryNotLow: Boolean = false,
    val requiresCharging: Boolean = false,
    val backoffPolicy: BackoffPolicy,
    val backoffDelayMs: Long,
    val maxRunAttempts: Int,             // per-run retry budget (not to be confused with periodic repeats)
    val enabledByDefault: Boolean,
    val settingsGateKey: String?,        // key used to look up enabling setting
    val tags: Set<String> = emptySet()
)
```

### 5.2 Registry entries (initial versions)

| Worker | Unique Name | Kind | Interval | Flex | Network | Battery | Charging | Backoff | Max Attempts | Enabled Default | Settings Gate |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `DATA_RETENTION` | `data_retention` | PERIODIC | 24h | – | NOT_REQUIRED | – | – | EXPONENTIAL 30s | 3 | ✅ | `privacy_retention_enabled` |
| `LOCATION_BACKFILL` | `location_backfill` | PERIODIC | **12h** ↑ | – | UNMETERED | – | – | EXPONENTIAL `MIN_BACKOFF_MILLIS` | 3 | ✅ | `privacy_location_backfill_enabled` |
| `BILL_REMINDER` | `bill_reminder_periodic` | PERIODIC | 6h | 15m | NOT_REQUIRED | – | – | EXPONENTIAL 30s | 3 | ❌ † | `bill_reminders_enabled` |
| `RECEIPT_MATCHING` | `receipt_matching` | PERIODIC | 2h | – | NOT_REQUIRED | – | – | EXPONENTIAL 10m | 3 | ✅ | `receipt_auto_matching_enabled` |
| `DAILY_BRIEFING` | `ai_daily_briefing` | PERIODIC | 24h | – | **UNMETERED** ↑ | **true** ↑ | **true** ↑ | EXPONENTIAL 30s | 3 | ✅ | `ai_proactive_briefing_enabled` |
| `WARRANTY_EXPIRATION` | `warranty_expiration_check` | PERIODIC | 24h | – | NOT_REQUIRED | – | – | EXPONENTIAL 10m | 3 | ✅ | `warranty_notifications_enabled` |
| `MERCHANT_KEY_BACKFILL` | `merchant_key_backfill` | ONE_TIME | – | – | – | – | – | EXPONENTIAL `MIN_BACKOFF_MILLIS` | 5 | ✅ (runs once) | – |

**Key changes from current state (↑ marks audit-driven refinement):**

- **LOCATION_BACKFILL**: Interval raised from 6h → 12h (audit R3: 6h is aggressive for Wi-Fi-only Nominatim geocoder). The existing `incrementBackfillAttempts` per-expense counter continues to function; per-expense item state is added as a supplementary sentinel.
- **DAILY_BRIEFING**: Network constraint changed from NONE → **UNMETERED**, battery-not-low + charging constraints added (audit R2). Timeout preserved at 12s via existing `withTimeout(DASHBOARD_BRIEFING_TIMEOUT_SECONDS)`.
- **MERCHANT_KEY_BACKFILL**: Explicit max-loop cap added — worker processes at most **50 batches** (200 items each = 10,000 expenses) per lifetime, then marks itself complete. Prevents infinite loop on very large datasets (audit R6).
- † **BILL_REMINDER**: Defaults to **disabled** (enabled=false). The user must opt in via reminder settings. This is safer because: (a) users may not have recurring reminders set up, (b) notification permission may not be granted, (c) false `SCHEDULED` deliveries would waste battery.

All workers start at **version 1**. A version bump triggers cancel + re-enqueue. **What constitutes a version bump:** any change to interval, flex, network type, battery/charging constraints, backoff policy, backoff delay, or tags.

---

## 6. Scheduling Rules

### 6.1 Replace `KEEP` freeze problem

**Current (broken):**
```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    name, ExistingPeriodicWorkPolicy.KEEP, request
)
```
Installed devices never get updated intervals/constraints.

**Target:**
```kotlin
fun syncWorker(name: BackgroundWorkerName) {
    val spec = registry.specFor(name)
    val shouldBeEnabled = evaluateSettings(spec.settingsGateKey) && spec.enabledByDefault
    val applied = specDao.get(name.name)

    if (applied == null || applied.appliedSpecVersion < spec.version || applied.enabled != shouldBeEnabled) {
        workManager.cancelUniqueWork(spec.uniqueWorkName)
        if (shouldBeEnabled) {
            workManager.enqueueUniquePeriodicWork(spec.uniqueWorkName, ExistingPeriodicWorkPolicy.KEEP, buildRequest(spec))
        }
        specDao.upsert(
            BackgroundWorkerSpecState(
                workerName = name.name,
                appliedSpecVersion = spec.version,
                enabled = shouldBeEnabled,
                workManagerUniqueName = spec.uniqueWorkName,
                appliedAt = System.currentTimeMillis(),
                lastSyncedAt = System.currentTimeMillis(),
                lastEnqueuedAt = if (shouldBeEnabled) System.currentTimeMillis() else null,
                reason = if (!shouldBeEnabled) "disabled_by_settings" else null
            )
        )
    }
}
```

**Rationale for retaining `KEEP` in the enqueue call:** Once we've verified the spec is current via our own registry, `KEEP` prevents unnecessary cancel/re-enqueue churn. The registry — not the WorkManager policy — is the authority on version freshness.

### 6.2 Disable behavior

When a setting gate closes:
1. `workManager.cancelUniqueWork(spec.uniqueWorkName)`
2. Upsert spec state with `enabled = false`, `lastCancelledAt = now`, `reason = "disabled_by_settings"`
3. Do NOT enqueue
4. Worker MUST still re-check the runtime gate inside `doWork()` — in case it was already running when cancelled

### 6.3 Startup behavior

`AppStartupCoordinator.initialize()` replaces:

```kotlin
// REMOVED:
LocationBackfillWorker.schedule(application)
MerchantKeyBackfillWorker.schedule(application)
WarrantyExpirationWorker.schedule(application)
DataRetentionWorker.schedule(application)
```

With:

```kotlin
syncAllBackgroundWorkUseCase()
```

`SyncAllBackgroundWorkUseCase` composes all 7 sync cases (6 new + the existing `SyncProactiveBriefingWorkUseCase` for DailyBriefing):

```kotlin
// Order: one-shot backfill first, then periodic workers
syncMerchantKeyBackfillWorkUseCase()
syncDataRetentionWorkUseCase()
syncLocationBackfillWorkUseCase()
syncBillReminderWorkUseCase()
syncReceiptMatchingWorkUseCase()
syncWarrantyExpirationWorkUseCase()
syncProactiveBriefingWorkUseCase()   // via existing use case
```

### 6.4 Settings-change behavior

When settings change, only sync the relevant worker(s):

| Setting changed | Workers synced |
|---|---|
| Privacy settings | `DATA_RETENTION`, `LOCATION_BACKFILL` |
| Location permission | `LOCATION_BACKFILL` |
| AI / briefing settings | `DAILY_BRIEFING` (via `SyncProactiveBriefingWorkUseCase`) |
| Reminder settings | `BILL_REMINDER` |
| Warranty notification settings | `WARRANTY_EXPIRATION` |
| Receipt auto-matching setting | `RECEIPT_MATCHING` |

Hooks added to: `PrivacySettingsViewModel`, `AiSettingsViewModel`, `ReminderSettingsViewModel` (or equivalent), `WarrantySettingsViewModel`, `ReceiptSettingsViewModel`. If a settings screen does not yet exist for a feature, add the hook to the nearest relevant ViewModel or use-case-level setting change listener.

### 6.5 Runtime settings check inside workers

Every worker must re-validate its settings gate at the **start of `doWork()`**:

```kotlin
override suspend fun doWork(): Result {
    if (!gate.allows(thisWorkerName)) {
        return Result.success()  // skipped, not retried
    }
    // ... proceed
}
```

This is already present for DataRetentionWorker and LocationBackfillWorker. Must be **added** to:
- **BillReminderWorker** — check `billRemindersEnabled` AND notification permission
- **ReceiptMatchingWorker** — check `receiptAutoMatchingEnabled`
- **WarrantyExpirationWorker** — check `warrantyNotificationsEnabled` AND notification permission

---

## 7. Execution Tracking Contract

### 7.1 `BackgroundWorkerOutcome` (avoids naming conflict with WorkManager `Result`)

```kotlin
sealed class BackgroundWorkerOutcome {
    data class Success(
        val rowsScanned: Int = 0,
        val rowsUpdated: Int = 0,
        val rowsSkipped: Int = 0,
        val notificationsSent: Int = 0,
        val itemsSucceeded: Int = 0,
        val itemsFailed: Int = 0,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()

    data class Retry(
        val reason: String,
        val error: Throwable? = null,
        val rowsScanned: Int = 0,
        val notificationsSent: Int = 0,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()

    data class PermanentFailure(
        val reason: String,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) : BackgroundWorkerOutcome()
}
```

### 7.2 Tracker → WorkManager result mapping

| Outcome | Periodic worker | One-shot worker |
|---|---|---|
| `Success` | `Result.success()` | `Result.success()` |
| `Retry` (within max attempts) | `Result.retry()` | `Result.retry()` |
| `Retry` (exhausted) | `Result.success()` + record `FAILED_EXHAUSTED` | `Result.failure()` |
| `PermanentFailure` | `Result.success()` + record `FAILED_PERMANENT` | `Result.failure()` |

**Key rule:** For periodic workers, NEVER return `Result.failure()` — it does not stop retries (WorkManager retries periodic work failures using backoff). Instead, return `Result.success()` and record the failure in `BackgroundJobRun` with appropriate status. This prevents retry storms while preserving diagnostics.

### 7.3 `WorkerFailureClassifier`

Classifies exceptions into transient, permanent, or unknown:

**Transient** (retry up to `maxRunAttempts`):
- `IOException` from network calls (geocoding, cloud AI)
- `SocketTimeoutException`, `ConnectException`
- `android.database.sqlite.SQLiteDatabaseLockedException`
- `SQLiteAbortException` with `SQLITE_BUSY`
- `SecurityException` for notification permission when user might grant later
- `CancellationException` (WorkManager stopped — retry is the right semantic)

**Permanent** (do not retry):
- `IllegalArgumentException` from bad input data / malformed URIs
- `IllegalStateException` with schema/state assumption message patterns
- `MissingRequiredDependencyException` (custom, if Hilt DI fails)
- `SecurityException` for `POST_NOTIFICATIONS` when user **permanently denied** (checked via `shouldShowRequestPermissionRationale` — if false, permanent)
- `SQLiteConstraintException` from invalid FK/data integrity (means code bug, not transient)
- `NullPointerException` from missing Room DAO or dependency — code bug

**Unknown** (retry once, then classify as permanent):
- `RuntimeException` with no clear transient/permanent signal
- Unrecognized exception types

**ReceiptMatchingWorker existing classification preserved:** It already distinguishes `IllegalArgumentException` (permanent → `Result.failure()`) from other exceptions. This existing logic is integrated into the classifier rather than replaced.

---

## 8. Detailed Worker Implementation Steps

Each worker follows the same pattern:
1. Add spec to `BackgroundWorkerRegistry`.
2. Add sync use case wired to startup + settings.
3. Wrap `doWork()` with `backgroundJobTracker.track(...)`.
4. Classify exceptions via `WorkerFailureClassifier`.
5. Verify idempotency.

### 8.1 DataRetentionWorker

**Existing state:** ✅ Idempotent (`purgedAt IS NULL` guard), ✅ Settings-aware (`PrivacySettingsRepository`), ✅ Writes `PrivacyAuditEvent`.

**Phase 8 changes:**
1. Add to registry (spec version 1).
2. Add `SyncDataRetentionWorkUseCase` wired to startup + privacy settings change.
3. Deprecate companion `schedule()` → delegate to scheduler.
4. Wrap `doWork()` with tracker (records rows scanned/purged, notifications sent = 0).
5. Classify: `SQLiteDatabaseLockedException` → transient; `SecurityException` for retention data access → permanent.
6. **Keep** `PrivacyAuditEvent` writes alongside `BackgroundJobRun` — the audit event provides domain-level traceability (GDPR) that the generic run table does not. Do NOT remove.

**Acceptance criteria:**
- Schedule/cancel follows privacy retention setting changes.
- Run tracked with row counts.
- Stale purge dates prevent re-purging (existing guard still works).
- `PrivacyAuditEvent` rows continue to be written.

---

### 8.2 LocationBackfillWorker

**Existing state:** ⚠️ Race condition (can overwrite user-set location), ✅ Settings-aware (`PrivacyGate.check`), ⚠️ Retries `Retryable` failures forever, uses `incrementBackfillAttempts` for some but not all failures.

**Phase 8 changes:**
1. Add to registry (spec version 1, interval **12h**, UNMETERED).
2. Add `SyncLocationBackfillWorkUseCase` wired to startup + privacy/location settings change.
3. Deprecate companion `schedule()`.
4. Wrap with tracker.
5. **ADD conditional UPDATE** at DAO level:

```kotlin
@Query("""
    UPDATE expenses
    SET latitude = :lat, longitude = :lng, locationSource = :source,
        placeId = :placeId, address = :address
    WHERE id = :expenseId AND latitude IS NULL AND longitude IS NULL
""")
suspend fun conditionallySetLocation(
    expenseId: Long, lat: Double, lng: Double,
    source: String, placeId: String?, address: String?
): Int  // returns affected rows — 0 means user already set location
```

If affected rows = 0, count as **skipped** (user manually set location between fetch and write).

6. **ADD per-expense item state** for retryable failures:

```
workerName = location_backfill
itemKey = expense:<expenseId>
actionKey = geocode
```

Store attempt count. If attempts exceed 3, mark `PERMANENT_FAILED` and skip. Do not hammer permanently failing merchants.

7. **Keep** existing `incrementBackfillAttempts` mechanism — it serves a different purpose (batch-level backfill tracking). Item state supplements it for per-expense dedup.
8. Classify: `SocketTimeoutException` → transient; `privacy_gate_denied` → skipped (not failure); malformed merchant name → permanent item failure.

**Acceptance criteria:**
- Conditional update skips when user set location.
- Per-expense retryable failures capped at 3 attempts.
- Privacy gate denied produces skipped run, not retry.
- No HTTP calls when privacy gate closed.
- `incrementBackfillAttempts` continues to track batch-level metadata.

---

### 8.3 BillReminderWorker

**Existing state:** ❌ DEAD CODE — `schedule()` never called. ✅ Strong idempotency (`SCHEDULED` → `SENT` status gate). ❌ No settings check. ✅ Catches `SecurityException` for missing notification permission.

**Phase 8 changes:**
1. Add to registry (spec version 1, **enabledByDefault = false**).
2. Add `SyncBillReminderWorkUseCase` wired to startup + reminder settings change.
3. Deprecate companion `schedule()`.
4. **ADD settings gate**:
   - Setting `billRemindersEnabled` (default `false`, user opt-in).
   - If no `RecurringLifecycleCoordinator` has active rules, skip even if enabled.
5. **ADD runtime check**: at `doWork()` start, verify `billRemindersEnabled` + notification permission. If denied, return success (skipped).
6. Wrap with tracker.
7. Use existing `getPendingDeliveries(now)` → filter by `SCHEDULED` → send → mark `SENT`.
8. Classify: notification `SecurityException` → transient if permission not permanently denied, permanent if `shouldShowRequestPermissionRationale == false`; DB failure → transient.

**Acceptance criteria:**
- Worker is scheduled when user enables `billRemindersEnabled`.
- Disabling the setting cancels the worker.
- On each run, only `SCHEDULED` deliveries become `SENT`.
- Second run sends zero duplicate notifications.
- Missing notification permission does not crash or retry forever.
- Worker defers to `RecurringLifecycleCoordinator` for rule status.

---

### 8.4 ReceiptMatchingWorker

**Existing state:** ❌ DEAD CODE — `schedule()` never called. ❌ Wrong `package com.yourname.expensetracker.data.repository` (file lives in `service/receiptmatching/`). ✅ Has failure classification for `IllegalArgumentException`. ⚠️ No per-receipt matched-flag check.

**Phase 8 changes:**
1. **FIX package declaration**: change from `com.yourname.expensetracker.data.repository` to `com.yourname.expensetracker.service.receiptmatching`.
2. Verify Hilt can still resolve the worker (manifest entry, `@HiltWorker` annotation — these should reference the class, not the package string, so no change needed, but verify).
3. Update any import references if other files import this worker by FQN.
4. Add to registry (spec version 1).
5. Add `SyncReceiptMatchingWorkUseCase` wired to startup + receipt settings change.
6. Deprecate companion `schedule()`.
7. **ADD settings gate**: `receiptAutoMatchingEnabled` (default `true` — local-only, safe).
8. **ADD runtime check**: verify `receiptAutoMatchingEnabled` at `doWork()` start.
9. Wrap with tracker.
10. **Verify `ReceiptLinkService` guards against double-linking**: Worker should query `getUnmatchedReceipts()` (which must exclude already-linked receipts, bank statements, rejected receipts). If the DAO query already filters correctly, per-item state is optional.
11. **Preserve** existing `IllegalArgumentException` → `Result.failure()` classification and integrate with common `WorkerFailureClassifier`.

**Acceptance criteria:**
- Worker is scheduled when receipt auto-matching enabled.
- Package declaration matches file path.
- Hilt can instantiate the worker.
- Matched receipt links created once per receipt (no duplicate links).
- Second run creates no new links for already-matched receipts.
- Suggested match saved once; rejected match skipped.
- Existing permanent/transient classification preserved.

---

### 8.5 DailyBriefingWorker

**Existing state:** ✅ Settings-aware (via `SyncProactiveBriefingWorkUseCase`). ✅ Idempotent (engagement key). ✅ Timeout (12s via `withTimeout`). ❌ ZERO WorkManager constraints. ❌ Retries all exceptions.

**Phase 8 changes:**
1. Add to registry (spec version 1, **network = UNMETERED, battery = true, charging = true**).
2. `AiWorkSchedulerImpl.scheduleDailyBriefing()` → delegates to `BackgroundWorkScheduler.sync(DAILY_BRIEFING)`.
3. Wrap with tracker.
4. Classify: network timeout → transient (retry up to 3); AI disabled → skipped; `CancellationException` → transient; permanent prompt/config → permanent.
5. **Keep** existing engagement-key dedup (`getLastDeliveredDashboardBriefingKey()`).
6. **Keep** existing artifact freshness gate (`artifact.updatedAt < startedAt`) — this is an additional idempotency guard that must remain.

**Acceptance criteria:**
- Worker scheduled via registry with UNMETERED + battery + charging constraints.
- `AiWorkSchedulerImpl` delegates to central scheduler.
- Engagement key prevents duplicate notifications per day.
- Artifact freshness gate preserved.
- Failure classified (transient vs permanent).
- AI settings change schedules/cancels correctly.

---

### 8.6 WarrantyExpirationWorker

**Existing state:** ❌ Sends duplicate notifications every run (no sent-state). ❌ No settings check. ⚠️ 30-day filter uses object equality (`it !in expiringIn7Days`) — fragile.

**Phase 8 changes:**
1. Add to registry (spec version 1).
2. Add `SyncWarrantyExpirationWorkUseCase` wired to startup + warranty notification settings change.
3. Deprecate companion `schedule()`.
4. **ADD setting**: `warrantyNotificationsEnabled` (default `true` since warranties are user-created).
5. **ADD runtime check**: verify `warrantyNotificationsEnabled` + notification permission.
6. Wrap with tracker.
7. **ADD per-warranty notification dedup** using `background_job_item_states`:

   Item keys:
   ```
   workerName = warranty_expiration
   itemKey = warranty:<warrantyId>
   actionKey = expiring_7_days  | expiring_30_days | expired
   ```

   Worker flow:
   1. Query expiring warranties (7-day, 30-day windows, expired).
   2. For each warranty + window:
      - Try insert item state with `IN_PROGRESS` (unique constraint prevents duplicate).
      - If already `SUCCESS` → skip notification.
      - Send notification → mark `SUCCESS`.
   3. **REPLACE** `filter { it !in expiringIn7Days }` with ID-based filtering:

      ```kotlin
      val sevenDayIds = expiringIn7Days.map { it.id }.toSet()
      val expiringIn30Days = allExpiring.filter { it.id !in sevenDayIds }
      ```

   4. If notification permission missing: mark items `SKIPPED`, not failed.

8. Classify: notification permission missing → skipped; malformed warranty date → permanent item failure; DB failure → transient.

**Acceptance criteria:**
- 30-day notification sent once per warranty.
- 7-day notification sent once per warranty.
- Daily rerun sends zero duplicates.
- 30-day and 7-day windows can both fire (if policy allows).
- ID-based window filter correct and testable.
- Notification permission handled gracefully.
- Settings change schedules/cancels correctly.

---

### 8.7 MerchantKeyBackfillWorker

**Existing state:** ✅ Idempotent (merchantKey IS NULL guard). ✅ Respects `isStopped`. ⚠️ No max-budget cap (can loop indefinitely).

**Phase 8 changes:**
1. Add to registry (spec version 1, ONE_TIME).
2. Add `SyncMerchantKeyBackfillWorkUseCase`. On first run, enqueue. If spec state shows `completed`, do NOT re-enqueue.
3. Deprecate companion `schedule()`.
4. Wrap with tracker.
5. **ADD max-loop cap**: maximum **50 batch iterations** (200 items each = 10,000 expenses processed). After 50 batches, mark spec state as `completed` even if some null keys remain (those are edge cases that can be handled by a future targeted migration). This prevents infinite loops on datasets exceeding 10,000 null-key expenses.
6. Keep existing `isStopped` check.
7. Keep existing `failedExpenseIdsThisRun` in-memory set.
8. Classify: DB locked → transient; permanently bad merchant name → skip item and continue (not fail the run).

**Acceptance criteria:**
- One-shot worker enqueued once per device lifetime.
- Completes after processing all null keys or hitting budget cap.
- Detects completion via spec state and does not re-enqueue.
- Max 50 batch loop iterations.
- `isStopped` respected.

---

## 9. Foreground Service & AlarmManager Observability (PR 9)

### 9.1 Current state

- `NotificationCaptureService` (foreground `NotificationListenerService`) captures financial notifications.
- In-memory dedup via `LinkedHashMap` (max 500 entries, 5s window per key) — noted as adequate but not monitored.
- `AlarmManager.setRepeating` keeps service alive every 15 minutes (`RESTART_INTERVAL_MS = 900_000`).
- `BootReceiver` starts service on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`.
- `ServiceRestartReceiver` restarts service on alarm fire.
- Both receivers start the service **unconditionally** — no settings/privacy check.

### 9.2 Phase 8 changes

1. **Add settings/privacy gate to BootReceiver:**
   - Check if notification capture is enabled before starting service.
   - If disabled: do NOT start service, do NOT schedule restart alarm.
   
2. **Add settings/privacy gate to ServiceRestartReceiver:**
   - Check if notification capture is enabled before restarting.
   - If disabled: cancel the restart alarm, stop service if running.

3. **AlarmManager reliability improvement:**
   - Current: `setRepeating` (inexact on API 31+).
   - Target: Use `setExactAndAllowWhileIdle` for API 23+ with a re-schedule on each fire, OR use `WorkManager` for the keepalive (a 15-minute periodic worker that pings the service).
   - Recommendation: **Switch to AlarmManager `setWindow`** (exact within a window, battery-optimized for API 23+) with a 5-minute window. Re-schedule on every fire. This addresses audit R4.

4. **Add service event tracking** (to `ServiceDiagnostics` or new `background_job_runs` with `workerName = "notification_capture_service"`):
   - service started
   - service stopped
   - restart alarm scheduled
   - restart alarm fired
   - privacy denied start (attempt blocked)
   - service killed (via `onTaskRemoved` / `onDestroy`)

5. **Re-evaluate 15-minute interval:**
   - Current 15-minute repeat is aggressive for a keepalive.
   - Consider 30-minute interval if service is stable on most devices.
   - Make interval configurable via `AppConfig` constant.

6. **In-memory dedup observability:**
   - Log dedup hit/miss rates to Timber (not persistent — too high volume).
   - Add `dedupHits` / `dedupMisses` counters to service diagnostics.

### Acceptance criteria
- Boot does NOT start service when notification capture disabled.
- Restart receiver does NOT start service when disabled.
- Enabling capture starts service and schedules alarm.
- Disabling capture cancels alarm and stops service.
- AlarmManager uses `setWindow` for API 23+ reliability.
- Service start/stop/deny events recorded.
- Dedup rate observable via Timber debug logs.

---

## 10. Failure Classification for All Workers

### 10.1 Default rules

| Exception type | Classification | Action |
|---|---|---|
| `IOException`, `SocketTimeoutException`, `ConnectException` | TRANSIENT | Retry up to max attempts |
| `SQLiteDatabaseLockedException` / `SQLiteAbortException` (BUSY) | TRANSIENT | Retry up to max attempts |
| `CancellationException` | TRANSIENT | WorkManager handles naturally |
| `IllegalArgumentException` from bad data | PERMANENT | Record failure, return success |
| `SecurityException` (notification permission, not permanently denied) | TRANSIENT | Retry up to max attempts |
| `SecurityException` (notification permission, permanently denied) | PERMANENT | Record failure, return success |
| `SQLiteConstraintException` | PERMANENT | Record failure, return success |
| `NullPointerException` (missing dependency) | PERMANENT | Record failure, return success |
| Unrecognized `RuntimeException` | UNKNOWN | Retry once, then permanent |

### 10.2 Worker-specific overrides

See individual worker sections above. The `WorkerFailureClassifier` is the single source of truth; worker-specific logic is expressed as **additional checks** before falling through to the classifier.

### 10.3 Max attempt defaults

| Worker type | Max run attempts |
|---|---|
| Periodic | 3 |
| One-shot | 5 |

Configurable per worker in `BackgroundWorkerSpec.maxRunAttempts`.

---

## 11. Guardrails (PR 10)

Prevent future workers from bypassing the foundation:

### 11.1 CI/Lint guardrail

Add a custom **unit test** (not lint rule — easier to implement and maintain) that scans the codebase:

```kotlin
@Test
fun `all HiltWorker classes are registered in BackgroundWorkerRegistry`() {
    val hiltWorkers = scanForClassesAnnotatedWith("androidx.hilt.work.HiltWorker")
    val registered = BackgroundWorkerRegistry.all().map { it.workerClass.qualifiedName }.toSet()
    val unregistered = hiltWorkers - registered
    assertThat(unregistered).isEmpty()
}
```

```kotlin
@Test
fun `no direct WorkManager scheduling outside BackgroundWorkSchedulerImpl`() {
    val forbiddenCalls = listOf("enqueueUniquePeriodicWork", "enqueueUniqueWork", "enqueue")
    // Scan .kt files; allow in tests and in BackgroundWorkSchedulerImpl
    // Flag any unauthorized usage
}
```

```kotlin
@Test
fun `no ExistingPeriodicWorkPolicy_KEEP outside scheduler`() {
    // Same scanning approach
}
```

```kotlin
@Test
fun `no Result_retry on broad Exception without classifier`() {
    // Check for catch(Exception) → retry() patterns in workers
}
```

### 11.2 Deprecation markers

Add `@Deprecated` annotation to all companion `schedule()` methods:

```kotlin
@Deprecated(
    message = "Use BackgroundWorkScheduler via Sync{WorkerName}WorkUseCase",
    replaceWith = ReplaceWith("SyncDataRetentionWorkUseCase()"),
    level = DeprecationLevel.WARNING
)
fun schedule(context: Context) { ... }
```

### 11.3 Debug diagnostics

Add a debug screen section showing:
- Last 20 `BackgroundJobRun` records (worker name, started, finished, status, rows).
- Per-worker spec state (version, enabled, last synced).
- Quick action: "Cancel and re-enqueue all workers" (for testing spec version bumps).

---

## 12. Implementation Batches (PRs)

### Batch 0: Baseline docs and worker constants (NO behavior change)

**Files:**
- Create: `docs/development/BACKGROUND_WORKERS.md` (worker inventory, scheduling policy, idempotency contract)
- Modify: Add `BackgroundWorkerName` enum + worker unique name constants

**Done when:** Worker ownership and scheduling policy are documented; enum exists.

---

### Batch 1: Schema — spec state + job runs + item state

**Files:**
- Create: `BackgroundWorkerSpecState.kt`, `BackgroundJobRun.kt`, `BackgroundJobItemState.kt`
- Create: `BackgroundWorkerSpecDao.kt`, `BackgroundJobRunDao.kt`, `BackgroundJobItemStateDao.kt`
- Modify: `AppDatabase.kt` (add entities, DAOs, `MIGRATION_104_105`, bump to 105)
- Add: `105.json` schema export

**Tests:**
- Migration 104→105 creates all three tables with correct columns and indexes.
- Insert/update/query spec state.
- Insert/finish job run.
- Insert item state with unique constraint enforcement.
- Mark stale RUNNING rows as ABANDONED.

**Done when:** Persistence foundation compiles and migration tests pass.

---

### Batch 2: BackgroundWorkerRegistry + BackgroundWorkScheduler

**Files:**
- Create: `BackgroundWorkerRegistry.kt` (with all 7 spec entries)
- Create: `BackgroundWorkScheduler.kt` (interface)
- Create: `BackgroundWorkSchedulerImpl.kt` (implementation — the ONLY place that calls WorkManager scheduling APIs)
- Create: `SyncAllBackgroundWorkUseCase.kt`
- Create: 6 individual sync use cases
- Modify: `AppStartupCoordinator.kt` (replace direct calls with `syncAllBackgroundWorkUseCase()`)
- Modify: `AiWorkSchedulerImpl.kt` (delegate `scheduleDailyBriefing()` to `BackgroundWorkScheduler`)

**Tests:**
- First startup enqueues all enabled workers.
- Spec version bump cancels and re-enqueues.
- Disabled setting cancels worker (no enqueue).
- No-op when spec is current and enabled.
- One-shot backfill does not endlessly re-enqueue if completed.

**Done when:** `KEEP` no longer freezes worker config; all scheduling routed through one class.

---

### Batch 3: Execution tracker wrapper

**Files:**
- Create: `BackgroundJobTracker.kt`, `RoomBackgroundJobTracker.kt`
- Create: `WorkerFailureClassifier.kt`
- Create: `BackgroundWorkerOutcome.kt`
- Modify: All 7 workers — wrap `doWork()` with `backgroundJobTracker.track(...)`

**Migration order** (least-risk first):
1. `MerchantKeyBackfillWorker`
2. `DataRetentionWorker`
3. `DailyBriefingWorker`
4. `LocationBackfillWorker`
5. `ReceiptMatchingWorker`
6. `WarrantyExpirationWorker`
7. `BillReminderWorker`

**Tests:**
- Successful worker writes SUCCESS run with correct counts.
- Retry worker writes RETRY run.
- Permanent failure writes FAILED_PERMANENT.
- Crash path records error (try/finally to update finishedAt + status).
- Stale RUNNING rows marked ABANDONED on next startup.
- `PrivacyAuditEvent` still written for DataRetentionWorker.

**Done when:** Every worker has persistent run diagnostics visible in debug screen.

---

### Batch 4: Wire dead workers (BillReminderWorker + ReceiptMatchingWorker)

**BillReminderWorker:**
- Verify `billRemindersEnabled` setting exists (create if missing, default `false`).
- Add runtime settings + notification permission check.
- Wire sync use case to startup + settings change.

**ReceiptMatchingWorker:**
- Fix package declaration (`com.yourname.expensetracker.data.repository` → `com.yourname.expensetracker.service.receiptmatching`).
- Verify Hilt/manifest references still resolve.
- Add `receiptAutoMatchingEnabled` setting (default `true`).
- Add runtime settings check.
- Wire sync use case to startup + settings change.

**Tests:**
- BillReminderWorker: schedule when enabled, cancel when disabled, send due delivery once, no duplicate on second run, missing notification permission handled.
- ReceiptMatchingWorker: schedule when enabled, package/Hilt worker instantiates, matched receipt links once, second run no duplicate link, suggested match saved once.

**Done when:** No dead `schedule()` methods remain; both workers are active and tracked.

---

### Batch 5: Warranty notification idempotency

**Files:**
- Modify: `WarrantyExpirationWorker.kt` — add per-warranty item state dedup using `background_job_item_states`.
- Create/modify: warranty notification settings key + sync use case.
- Modify: ID-based window filtering (replace `!in` object equality).

**Tests:**
- 30-day notification sent once per warranty.
- 7-day notification sent once per warranty.
- Daily rerun sends zero duplicates.
- Both windows can fire if policy allows.
- Missing notification permission handled.
- ID-based filter excludes 7-day warranties from 30-day list.

**Done when:** Warranty worker is fully idempotent; no duplicate notifications possible.

---

### Batch 6: Location backfill overwrite guard + item state

**Files:**
- Modify: `ExpenseDao.kt` — add `conditionallySetLocation()` query.
- Modify: `LocationBackfillWorker.kt` — use conditional update; add per-expense item state for retryables.
- Keep: `incrementBackfillAttempts()` for batch-level tracking.

**Tests:**
- Conditional update succeeds when location is still NULL.
- Conditional update skips (0 rows) when user already set location.
- Retryable failure records item state with attempt count.
- Permanently invalid merchant does not retry forever (capped at 3).
- Privacy gate denied returns success/skipped, no HTTP calls.

**Done when:** Location worker cannot overwrite manual user data; per-expense retries bounded.

---

### Batch 7: Settings-change sync for all workers

**Files:**
- Modify: Settings ViewModels or use cases to call relevant sync use cases on change.
- Verify: Every worker has a runtime check at `doWork()` start.

**Mapping:**
| Setting change source | Workers to sync |
|---|---|
| Privacy settings changed | DataRetention, LocationBackfill |
| Location permission changed | LocationBackfill |
| AI settings changed | DailyBriefing (via `SyncProactiveBriefingWorkUseCase`) |
| Reminder settings changed | BillReminder |
| Warranty settings changed | WarrantyExpiration |
| Receipt matching setting changed | ReceiptMatching |

**Tests:**
- Enabling setting enqueues worker.
- Disabling setting cancels worker.
- Version change while disabled does NOT enqueue until re-enabled.
- Runtime-denied worker exits success/skipped (not retried).

**Done when:** All workers react immediately to user settings changes.

---

### Batch 8: Foreground service/AlarmManager privacy-aware observability

**Files:**
- Modify: `BootReceiver.kt` — add notification capture privacy gate.
- Modify: `ServiceRestartReceiver.kt` — add notification capture privacy gate.
- Modify: `NotificationCaptureService.kt` — add service event tracking; switch AlarmManager to `setWindow`.
- Add: `ServiceDiagnostics` entries for start/stop/deny events.

**Tests:**
- Boot does NOT start service if capture disabled.
- Restart receiver does NOT start service if capture disabled.
- Enabling capture starts service path.
- Disabling capture cancels alarm.
- Service events recorded.

**Done when:** Foreground service respects privacy gates and is observable.

---

### Batch 9: Failure classification sweep

**Files:**
- Modify: All 7 workers — integrate `WorkerFailureClassifier`.
- Ensure max retry budget enforced (3 for periodic, 5 for one-shot).

**Tests:**
- Permanent errors do not retry forever (capped at max attempts).
- Transient errors retry up to max.
- Exhausted attempts recorded in `BackgroundJobRun.FAILED_EXHAUSTED`.
- Periodic worker does NOT return `Result.failure()` (returns `Result.success()` with failure status recorded).

**Done when:** Retry behavior is predictable, bounded, and observable.

---

### Batch 10: Guardrails, docs, and rollout preparation

**Files:**
- Create: Guardrail unit tests (scan for unregistered `@HiltWorker`, direct scheduling, `KEEP` outside scheduler).
- Modify: All companion `schedule()` methods — add `@Deprecated` with guidance.
- Modify: Debug screen — add recent job runs list.
- Modify: `BACKGROUND_WORKERS.md` — reflect final state.

**Tests:**
- Registry contains all `@HiltWorker` classes.
- Scan fails for unauthorized `enqueueUniquePeriodicWork`.
- Scan fails for unregistered worker class.
- Deprecation warnings fire for companion `schedule()` calls.

**Done when:** Future workers cannot bypass the registry; existing code paths are deprecated.

---

## 13. Non-Desctructive Migration Policy

1. The migration `MIGRATION_104_105` only adds tables. No column drops, renames, or deletions.
2. Existing `privacy_audit_events` for DataRetentionWorker remain untouched.
3. Existing `RecurringReminderDelivery` table for BillReminderWorker remains untouched.
4. `incrementBackfillAttempts` column on expenses remains untouched.
5. The migration is tested with a copy of the 104 schema to verify zero data loss.

---

## 14. Test Strategy Summary

### 14.1 Migration tests
- 104 → 105 migration succeeds with sample data from all existing tables.
- Fresh install (105) has all three new tables with correct indexes.
- DAOs insert/query correctly.

### 14.2 Scheduler tests
- All enabled workers enqueued on first startup.
- Spec version bump triggers cancel + re-enqueue.
- Unchanged spec is no-op.
- Disabled worker is cancelled, not re-enqueued.
- One-shot completion prevents repeated enqueue.

### 14.3 Tracker tests
- Success/retry/failure runs recorded with correct statuses.
- Stale RUNNING marked ABANDONED.
- Row counts and notification counts correct.

### 14.4 Idempotency tests (per worker)
- DataRetentionWorker: twice → no re-purge.
- BillReminderWorker: twice → one notification per SCHEDULED delivery.
- WarrantyExpirationWorker: twice → one notification per warranty per window.
- ReceiptMatchingWorker: twice → one link per unmatched receipt.
- LocationBackfillWorker: race condition where user sets location between fetch and write → skip.
- MerchantKeyBackfillWorker: twice → only fills null keys.

### 14.5 Failure classification tests
- Transient `SQLiteDatabaseLockedException` → retry.
- Permanent `IllegalArgumentException` → no retry.
- Max attempts exhausted → `FAILED_EXHAUSTED`.
- Notification permission missing → skipped (not failed).
- Privacy denied → skipped (not failed).
- Periodic worker does NOT return `Result.failure()`.

### 14.6 Guardrail tests
- Every `@HiltWorker` is in the registry.
- No `enqueueUniquePeriodicWork` outside `BackgroundWorkSchedulerImpl` (except tests).
- No `ExistingPeriodicWorkPolicy.KEEP` outside the scheduler.
- Deprecation annotations present on companion `schedule()`.

### 14.7 Foreground service tests
- Boot disabled → no service start, no alarm.
- Restart disabled → no service start, alarm cancelled.
- Enable capture → service starts, alarm scheduled.
- Disable capture → alarm cancelled, service stops.

---

## 15. Acceptance Criteria

Phase 8 is complete when:

- [ ] 1. All 7 workers are represented in `BackgroundWorkerRegistry`.
- [ ] 2. Direct worker scheduling calls removed from `AppStartupCoordinator`.
- [ ] 3. Worker config is versioned — spec version bumps propagate to installed devices.
- [ ] 4. `BackgroundJobRun` records every worker execution with row counts.
- [ ] 5. Worker run metrics visible in debug diagnostics.
- [ ] 6. `BillReminderWorker` scheduled when `billRemindersEnabled = true`.
- [ ] 7. `ReceiptMatchingWorker` package corrected and scheduled when enabled.
- [ ] 8. `WarrantyExpirationWorker` cannot send duplicate notifications (item state dedup).
- [ ] 9. `LocationBackfillWorker` cannot overwrite manual locations (conditional UPDATE).
- [ ] 10. `DataRetentionWorker` idempotent and tracked; `PrivacyAuditEvent` preserved.
- [ ] 11. `DailyBriefingWorker` registry-scheduled with UNMETERED + battery + charging constraints.
- [ ] 12. `MerchantKeyBackfillWorker` one-shot tracked with max-loop cap (50 batches).
- [ ] 13. Settings changes immediately schedule/cancel relevant workers.
- [ ] 14. Every worker has a runtime settings check at `doWork()` start.
- [ ] 15. Failure classification prevents infinite retry loops (max 3 periodic, 5 one-shot).
- [ ] 16. Periodic workers NEVER return `Result.failure()` (use success + recorded status).
- [ ] 17. Guardrail tests block unregistered workers and unauthorized direct scheduling.
- [ ] 18. Boot + service restart respect notification capture privacy gate.
- [ ] 19. AlarmManager uses `setWindow` for API 23+; 15-minute interval re-evaluated.
- [ ] 20. Migration 104→105 passes; `105.json` schema exported.
- [ ] 21. All idempotency, classification, scheduler, and guardrail tests pass.
- [ ] 22. `BACKGROUND_WORKERS.md` reflects final worker specs and policies.

---

## 16. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Version bump causes cancel → brief gap where worker is not scheduled | Medium | Low | Cancel + enqueue happen in same sync call; gap is milliseconds |
| `setWindow` AlarmManager behaves differently across OEMs | High | Medium | Keep `setRepeating` as fallback for API < 23; test on Samsung/Xiaomi |
| BillReminderWorker enabled too eagerly floods users | Low | High | Default `false`; require explicit user opt-in |
| Per-warranty item state unique constraint causes insert failure on race | Low | Low | Use `INSERT OR IGNORE` + check status after insert |
| Conditional location update misses edge case (NULL lat but non-NULL lng) | Low | Medium | WHERE clause checks BOTH `latitude IS NULL AND longitude IS NULL` |
| `PrivacyAuditEvent` + `BackgroundJobRun` double-writing is confusing | Low | Low | Document that they serve different purposes (GDPR vs operational) |
| Guardrail test false positives from legitimate test code | Medium | Low | Exclude `*Test.kt` and `*Test.kt` from scans |

---

*Plan endorsed by evaluating the `phase8-background-workers-idempotency-plan.md` template against the `background-workers-audit.md` findings. All audit-critical gaps (C1–C6) are addressed with concrete, versioned solutions. Audit-identified risks (R1–R6) are mitigated with explicit design decisions noted in this document.*

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
