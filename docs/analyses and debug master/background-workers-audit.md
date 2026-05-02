# Phase 8 — Background Workers & Idempotency Audit

**Date:** 2026-05-02  
**Scope:** ALL `.kt` files — WorkManager workers, foreground services, AlarmManager, BootReceiver, job run tracking, idempotency patterns  
**Output:** Comprehensive inventory, risk analysis, and foundation for worker spec registry / idempotency rules.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [All Workers Inventory](#2-all-workers-inventory)
3. [Worker Configuration & Scheduling](#3-worker-configuration--scheduling)
4. [Idempotency Patterns](#4-idempotency-patterns)
5. [Job Run Tracking](#5-job-run-tracking)
6. [Detailed Worker Profiles](#6-detailed-worker-profiles)
7. [Gaps & Risks](#7-gaps--risks)
8. [Recommended Foundation](#8-recommended-foundation)

---

## 1. Executive Summary

**7 WorkManager workers** found (5 periodic, 2 one-shot), **1 foreground service** (NotificationListenerService with AlarmManager keepalive), **2 BroadcastReceivers** (Boot + ServiceRestart).

| Metric | Count |
|---|---|
| `@HiltWorker` classes | 7 |
| Periodic workers | 5 |
| One-shot workers | 2 |
| Workers actually scheduled at startup | 5 |
| Workers with dead `schedule()` (never called) | 2 |
| Foreground services | 1 (`NotificationCaptureService`) |
| Broadcast receivers | 2 (`BootReceiver`, `ServiceRestartReceiver`) |
| `BackgroundJobRun` table | **0** (does not exist) |
| Worker spec registry | **0** (does not exist) |
| Settings-gated workers | 3 (LocationBackfill, DataRetention, DailyBriefing) |
| Workers with per-item state tracking | 1 (DataRetentionWorker — uses `purgedAt` timestamps) |

### Critical Gaps

1. **BillReminderWorker** and **ReceiptMatchingWorker** have `schedule()` methods but are **never called** — they are dead code.
2. **No `BackgroundJobRun` table** — zero observability into worker execution history.
3. **No worker spec registry** — `ExistingPeriodicWorkPolicy.KEEP` freezes config forever on installed devices.
4. **WarrantyExpirationWorker** sends duplicate notifications every run (no per-warranty sent-state).
5. **ReceiptMatchingWorker** has wrong `package` declaration (`data.repository` instead of `service.receiptmatching`), may cause DI issues.

---

## 2. All Workers Inventory

### 2.1 WorkManager Workers (`CoroutineWorker`)

| # | Class | File | Type | Priority |
|---|---|---|---|---|
| 1 | `DataRetentionWorker` | `data/privacy/DataRetentionWorker.kt` | Periodic (24h) | HIGH |
| 2 | `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` | Periodic (6h) | HIGH |
| 3 | `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | Periodic (6h) | **Dead code** |
| 4 | `ReceiptMatchingWorker` | `service/receiptmatching/ReceiptMatchingWorker.kt` | Periodic (2h) | **Dead code** |
| 5 | `DailyBriefingWorker` | `data/ai/worker/DailyBriefingWorker.kt` | Periodic (24h) | MEDIUM |
| 6 | `WarrantyExpirationWorker` | `service/warranty/WarrantyExpirationWorker.kt` | Periodic (24h) | HIGH |
| 7 | `MerchantKeyBackfillWorker` | `data/location/MerchantKeyBackfillWorker.kt` | One-shot | LOW (runs once) |

### 2.2 Worker Scheduling

#### Scheduled at startup (via `AppStartupCoordinator.scheduleStartupWork()`):

```kotlin
LocationBackfillWorker.schedule(application)      // every 6h, UNMETERED
MerchantKeyBackfillWorker.schedule(application)   // one-shot
WarrantyExpirationWorker.schedule(application)    // every 24h
DataRetentionWorker.schedule(application)         // every 24h
```

#### Scheduled via settings-aware use case (via `SyncProactiveBriefingWorkUseCase`):

```kotlin
AiWorkSchedulerImpl.scheduleDailyBriefing()        // every 24h, no constraints
```

This is called from:
- `AppStartupCoordinator.syncProactiveBriefingWork()` on startup
- `AiSettingsViewModel` when AI settings change

#### NEVER scheduled (dead `schedule()` methods):

```kotlin
BillReminderWorker.schedule()      // never called anywhere
ReceiptMatchingWorker.schedule()   // never called anywhere
```

### 2.3 Broadcast Receivers

| Receiver | File | Action | Effect |
|---|---|---|---|
| `BootReceiver` | `receiver/BootReceiver.kt` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | Starts `NotificationCaptureService` as foreground |
| `ServiceRestartReceiver` | `receiver/ServiceRestartReceiver.kt` | `ACTION_RESTART_SERVICE` | Restarts `NotificationCaptureService` (triggered by AlarmManager) |

### 2.4 AlarmManager

**Used by:** `NotificationCaptureService`

- **`scheduleRestartAlarm()`** — Sets a repeating `ELAPSED_REALTIME` alarm every **15 minutes** (`RESTART_INTERVAL_MS = 900_000`).
- The alarm fires a `PendingIntent` to `ServiceRestartReceiver`, which calls `startForegroundService()`.
- **Purpose:** Keep-alive mechanism for the notification listener service.
- **Risk:** `setRepeating` is inexact on Android 12+; may not fire reliably. The 15-minute interval is aggressive for a keepalive.

### 2.5 Foreground Service

| Service | Type | Purpose |
|---|---|---|
| `NotificationCaptureService` | `NotificationListenerService` + foreground | Captures financial notifications, processes via privacy gates, persists to `raw_notifications` table |

- Foreground service type: `dataSync|location` (declared in manifest)
- Notification channel: `expense_tracker_service` (IMPORTANCE_LOW)
- Uses `START_STICKY`
- Contains in-memory deduplication via `LinkedHashMap` (max 500 entries, per-key 5s window)

### 2.6 `@HiltWorker` Configuration

`MainApplication` implements `Configuration.Provider`:

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
        .setWorkerFactory(workerFactory)
        .build()
```

The default `WorkManagerInitializer` is **disabled** in AndroidManifest.xml so Hilt can provide the factory. ✅

---

## 3. Worker Configuration & Scheduling

### 3.1 Constraints

| Worker | Network | Battery | Other |
|---|---|---|---|
| `DataRetentionWorker` | `NOT_REQUIRED` | None | None |
| `LocationBackfillWorker` | **`UNMETERED`** (WiFi only) | None | None |
| `BillReminderWorker` | `NOT_REQUIRED` | None | None |
| `ReceiptMatchingWorker` | `NOT_REQUIRED` | None | None |
| `DailyBriefingWorker` | **None** ⚠️ | None | None |
| `WarrantyExpirationWorker` | `NOT_REQUIRED` | None | None |
| `MerchantKeyBackfillWorker` | None (one-shot, local) | None | None |

### 3.2 Backoff Policies

| Worker | Backoff | Initial Delay |
|---|---|---|
| `DataRetentionWorker` | Default (EXPONENTIAL, 30s) | Default |
| `LocationBackfillWorker` | `EXPONENTIAL` | `MIN_BACKOFF_MILLIS` |
| `BillReminderWorker` | Default | Default |
| `ReceiptMatchingWorker` | `EXPONENTIAL` | 10 minutes |
| `DailyBriefingWorker` | Default | Default (relies on `withTimeout`) |
| `WarrantyExpirationWorker` | `EXPONENTIAL` | 10 minutes |
| `MerchantKeyBackfillWorker` | `EXPONENTIAL` | `MIN_BACKOFF_MILLIS` |

### 3.3 Repeat Intervals

| Worker | Interval | Flex Interval |
|---|---|---|
| `DataRetentionWorker` | 1 day | None |
| `LocationBackfillWorker` | 6 hours | None |
| `BillReminderWorker` | 6 hours | **15 minutes** |
| `ReceiptMatchingWorker` | 2 hours | None |
| `DailyBriefingWorker` | 24 hours | None |
| `WarrantyExpirationWorker` | 1 day | None |
| `MerchantKeyBackfillWorker` | N/A (one-shot) | N/A |

### 3.4 Unique Work Policies

All workers use `ExistingPeriodicWorkPolicy.KEEP` or `ExistingWorkPolicy.KEEP`.

**This is a problem:** The KEEP policy means once a worker is scheduled with certain constraints/interval, those settings are frozen on the device forever. An app update that changes a repeat interval or network constraint will NOT affect devices that already have the work enqueued.

### 3.5 Worker Versioning / Spec Registry

**Does NOT exist.** There is no:
- `WorkerSpec` table or class
- Version number per worker
- Migration mechanism for worker configuration changes
- Registry of what workers exist and their current specs

This is the foundation gap that Phase 8 is meant to address.

---

## 4. Idempotency Patterns

### 4.1 Duplicate Prevention

| Worker | Prevention Mechanism | Effective? |
|---|---|---|
| `DataRetentionWorker` | Unique work name + checks `rawContentPurgedAt`/`rawOcrTextPurgedAt IS NULL` before purging | ✅ Strong |
| `LocationBackfillWorker` | Unique work name + fetches only `getUnlocatedExpensesForBackfill(limit=50)` | ⚠️ Partial — no per-expense sentinel |
| `BillReminderWorker` | Unique work name + query `getPendingDeliveries(now)` which filters by status `SCHEDULED` | ✅ Strong (DB status gate) |
| `ReceiptMatchingWorker` | Unique work name | ❌ Weak — no per-receipt matched-flag check in worker (relies on `getUnmatchedReceipts()` which should filter) |
| `DailyBriefingWorker` | Unique work name + checks `AiEngagementRepository.getLastDeliveredDashboardBriefingKey()` in delivery use case | ✅ Strong |
| `WarrantyExpirationWorker` | Unique work name only | ❌ Weak — no per-warranty sent-state, can notify same warranty every daily run |
| `MerchantKeyBackfillWorker` | Unique work name + only fetches rows with `merchantKey IS NULL` | ✅ Strong |

### 4.2 Idempotent Operations (Can they run twice safely?)

| Worker | Idempotent? | Notes |
|---|---|---|
| `DataRetentionWorker` | ✅ Yes | Purges only rows not yet purged (null check) |
| `LocationBackfillWorker` | ⚠️ Partial | Setting lat/lng twice on same expense is idempotent, but notification/push side effects may not be |
| `BillReminderWorker` | ✅ Yes | Only sends for `SCHEDULED` deliveries, marks as `SENT` |
| `ReceiptMatchingWorker` | ⚠️ Partial | Auto-linking twice could create duplicate links; but ReceiptLinkService may guard |
| `DailyBriefingWorker` | ✅ Yes | Engagement key prevents duplicate notifications per date |
| `WarrantyExpirationWorker` | ❌ No | Sends notification every run; no sent-state tracking |
| `MerchantKeyBackfillWorker` | ✅ Yes | Deterministic key, only writes where null |

### 4.3 Retry Handling

| Worker | Transient Failures | Permanent Failures | Notes |
|---|---|---|---|
| `DataRetentionWorker` | `Result.retry()` | None classified | Always retries on any exception |
| `LocationBackfillWorker` | `Result.retry()` | None classified | Mixed retry/success based on `shouldRetry` flag |
| `BillReminderWorker` | `Result.retry()` | None classified | Always retries on any exception |
| `ReceiptMatchingWorker` | `Result.retry()` | `Result.failure()` for `IllegalArgumentException` and certain `IllegalStateException` patterns | ✅ Has classification logic |
| `DailyBriefingWorker` | `Result.retry()` | None classified | Always retries except `CancellationException` |
| `WarrantyExpirationWorker` | `Result.retry()` | None classified | Always retries except `CancellationException` |
| `MerchantKeyBackfillWorker` | `Result.retry()` | None classified | Retries on batch failures |

**Issue:** Most workers treat ALL exceptions as transient (`Result.retry()`), risking infinite retry loops for permanent failures.

### 4.4 Settings-Aware Workers

| Worker | Checks Settings? | Settings Checked |
|---|---|---|
| `DataRetentionWorker` | ✅ Yes (inside `doWork()`) | `PrivacySettingsRepository.getSettings()` — reads retention days |
| `LocationBackfillWorker` | ✅ Yes (inside `doWork()`) | `PrivacyGate.check(BACKGROUND_LOCATION_BACKFILL)` — denies if not allowed |
| `BillReminderWorker` | ❌ No | None |
| `ReceiptMatchingWorker` | ❌ No | None |
| `DailyBriefingWorker` | ✅ Yes (via use case chain) | `SyncProactiveBriefingWorkUseCase` checks AI settings before scheduling; delivery re-checks |
| `WarrantyExpirationWorker` | ❌ No | None |

---

## 5. Job Run Tracking

### 5.1 `BackgroundJobRun` Table

**DOES NOT EXIST.** No Room entity, DAO, or table for tracking background job executions.

### 5.2 Existing Logging Mechanisms

| Mechanism | Where | What's Logged |
|---|---|---|
| `PrivacyAuditEvent` table | `privacy_audit_events` | DataRetentionWorker writes purge counts |
| `Timber.d()` / `Log.d()` | All workers | Row counts, status, errors |
| `AiRuntimeDiagnostics` | AI layer | Briefing delivery interactions |
| `ServiceDiagnostics` | NotificationCaptureService | Service start/kill events |

### 5.3 Missing Run Metrics

No worker tracks:
- `startedAt` / `finishedAt` timestamps
- Rows scanned vs rows updated
- Notifications sent count
- Retry reason
- Settings snapshot at time of run
- Worker version / spec version

### 5.4 Failure Tracking

- **No persistent failure tracking** — no per-worker error table.
- `MerchantKeyBackfillWorker` uses in-memory `failedExpenseIdsThisRun` set (lost if worker stops).
- `LocationBackfillWorker` uses `incrementBackfillAttempts()` for some failure types but not all.
- PrivacyAuditEvent only covers data retention operations, not worker failures.

---

## 6. Detailed Worker Profiles

### 6.1 `DataRetentionWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt` |
| **Purpose** | Purges raw notification content and OCR text after configurable retention period |
| **Interval** | Every 24 hours (PeriodicWorkRequest, 1 day) |
| **Idempotent** | ✅ Yes — null-guarded: only processes rows where `rawContentPurgedAt` / `rawOcrTextPurgedAt` IS NULL |
| **Settings-aware** | ✅ Reads `PrivacySettingsRepository.getSettings()` for retention days |
| **Duplicate protection** | ✅ Unique work name `data_retention` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ❌ No — only nulls out raw content fields, never touches user-edited data |
| **Retry behavior** | `Result.retry()` on any exception |
| **Run tracking** | Writes to `PrivacyAuditEvent` table with purge counts |
| **Risks** | No permanent-failure classification; no max-retry limit |

### 6.2 `LocationBackfillWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt` |
| **Purpose** | Geocodes expenses missing lat/lng using Nominatim (Wi-Fi only) |
| **Interval** | Every 6 hours |
| **Idempotent** | ⚠️ Partial — writing same lat/lng twice is safe, but external API calls are wasted |
| **Settings-aware** | ✅ Checks `PrivacyGate.check(BACKGROUND_LOCATION_BACKFILL)` |
| **Duplicate protection** | ✅ Unique work name `location_backfill` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ⚠️ **Yes** — race condition: user could manually set location between fetch and write |
| **Retry behavior** | `Result.retry()` on resolver exceptions and `Retryable` results |
| **Batch size** | 50 expenses per run |
| **Risks** | Can retry same transient failures indefinitely (no per-expense attempt tracking for exceptions/Retryable); can overwrite user manual location |
| **Notifications** | None |

### 6.3 `BillReminderWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt` |
| **Purpose** | Checks for due reminder deliveries and dispatches Android notifications |
| **Interval** | Every 6 hours, 15-minute flex interval |
| **Idempotent** | ✅ Yes — only processes `SCHEDULED` deliveries, marks as `SENT` (DB status gate) |
| **Settings-aware** | ❌ No — no settings check |
| **Duplicate protection** | ✅ Unique work name `bill_reminder_periodic` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ❌ No — read-only query, writes only delivery status |
| **Retry behavior** | `Result.retry()` on any exception |
| **Scheduled?** | **NO — dead code.** `schedule()` is never called anywhere. |
| **Risks** | Entirely inactive; bills will not get reminder notifications |

### 6.4 `ReceiptMatchingWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt` |
| **Purpose** | Auto-matches unmatched scanned receipts to expenses |
| **Interval** | Every 2 hours |
| **Idempotent** | ⚠️ Partial — auto-links via ReceiptLinkService which may guard against double-link |
| **Settings-aware** | ❌ No |
| **Duplicate protection** | ✅ Unique work name `receipt_matching` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ❌ No — creates links, doesn't overwrite existing data |
| **Retry behavior** | ✅ Classifies permanent vs transient: `Result.failure()` for `IllegalArgumentException` and certain `IllegalStateException` patterns |
| **Scheduled?** | **NO — dead code.** `schedule()` is never called anywhere. |
| **Risks** | Inactive; also has **wrong package declaration** (`package com.yourname.expensetracker.data.repository` instead of `service.receiptmatching`) |

### 6.5 `DailyBriefingWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt` |
| **Purpose** | Generates and delivers AI dashboard briefing notification once per day |
| **Interval** | Every 24 hours |
| **Idempotent** | ✅ Yes — last-delivered-key prevents duplicate notifications; artifact cache freshness logic |
| **Settings-aware** | ✅ Yes (via chain: `SyncProactiveBriefingWorkUseCase` checks AI settings before scheduling; delivery re-checks) |
| **Duplicate protection** | ✅ Unique work name `ai_daily_briefing` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ❌ No — read-only analytics aggregation |
| **Retry behavior** | `Result.retry()` on all exceptions except `CancellationException`; `withTimeout` for pipeline bounding |
| **Timeout** | `DASHBOARD_BRIEFING_TIMEOUT_SECONDS` (12s) via `AppConfig.Ai` |
| **Risks** | No WorkManager constraints (network/battery); retries all exceptions including permanent ones; `artifact.updatedAt < startedAt` gate can skip delivery when artifact is reused from cache |

### 6.6 `WarrantyExpirationWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt` |
| **Purpose** | Checks for expiring warranties (7-day and 30-day windows) and sends notifications |
| **Interval** | Every 24 hours |
| **Idempotent** | ❌ **No** — will send duplicate notifications every run; no per-warranty sent-state |
| **Settings-aware** | ❌ No |
| **Duplicate protection** | ✅ Unique work name `warranty_expiration_check` + `ExistingPeriodicWorkPolicy.KEEP` |
| **Can overwrite user data** | ❌ No |
| **Retry behavior** | `Result.retry()` on all exceptions except `CancellationException` |
| **Risks** | **HIGH:** sends notification each run to same warranties; 30-day filter uses object equality (`it !in expiringIn7Days`) instead of ID-based; reconciliation and notification in same job |
| **30-day filter bug** | `filter { it !in expiringIn7Days }` uses Kotlin's `equals()` on data classes — works but fragile; should use ID-based |

### 6.7 `MerchantKeyBackfillWorker`

| Property | Value |
|---|---|
| **File** | `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt` |
| **Purpose** | One-time backfill of `merchantKey` for expenses created before schema v32 |
| **Interval** | One-shot (runs until all null keys processed) |
| **Idempotent** | ✅ Yes — deterministic key generation from merchant name; only writes where key is null |
| **Settings-aware** | ❌ N/A — migration concern, always needed |
| **Duplicate protection** | ✅ `ExistingWorkPolicy.KEEP` — only enqueued once per device lifetime |
| **Can overwrite user data** | ✅ Yes but safe — overwrites null keys with deterministic values |
| **Retry behavior** | `Result.retry()` on batch fetch failure; `Result.retry()` if batch makes no progress |
| **Batch size** | 200 expenses per loop iteration |
| **Risks** | Can run indefinitely on very large datasets with no max-budget cap; can retry forever on permanently bad rows |
| **Cancellation** | ✅ Respects `isStopped` flag |

---

## 7. Gaps & Risks

### 7.1 Critical Gaps

| # | Gap | Impact | Workers Affected |
|---|---|---|---|
| C1 | **No worker spec registry** | Config changes (interval, constraints) never apply to existing installs | ALL periodic workers |
| C2 | **No BackgroundJobRun table** | Zero observability; cannot debug worker behavior in production | ALL workers |
| C3 | **BillReminderWorker dead code** | Bill reminders never dispatched | BillReminderWorker |
| C4 | **ReceiptMatchingWorker dead code** | Receipt auto-matching never runs | ReceiptMatchingWorker |
| C5 | **Warranty duplicate notifications** | User gets same notification every day | WarrantyExpirationWorker |
| C6 | **Location overwrite race** | User manual location can be overwritten | LocationBackfillWorker |

### 7.2 High-Priority Risks

| # | Risk | Details |
|---|---|---|
| R1 | **Infinite retry loops** | Most workers return `Result.retry()` for ALL exceptions — permanent failures retry forever |
| R2 | **Excessive background work** | No battery/charging constraints on DailyBriefingWorker; location backfill every 6h is aggressive |
| R3 | **Notification permission crash** | `BillReminderWorker` catches `SecurityException` for missing notification permission; others do not |
| R4 | **AlarmManager unreliability** | `setRepeating` is inexact on API 31+; 15-minute keepalive may not fire |
| R5 | **Package declaration mismatch** | `ReceiptMatchingWorker` declares `package com.yourname.expensetracker.data.repository` but lives in `service/receiptmatching/` — may cause Hilt/WorkManager DI issues |

### 7.3 Missing Features

| Feature | Missing? | Needed For |
|---|---|---|
| Per-worker max retry count | ✅ Missing | ALL workers |
| Worker failure classification | ✅ Missing (except ReceiptMatchingWorker) | Transient vs permanent failure handling |
| Worker settings sync on preference change | ✅ Missing | Location + Warranty workers |
| Per-item notification sentinel | ✅ Missing | WarrantyExpirationWorker |
| Worker run diagnostics | ✅ Missing | Debugging background behavior |
| Idempotency key / dedup key per job | ✅ Missing | Location + Receipt workers |

---

## 8. Recommended Foundation

### 8.1 Worker Spec Registry

A `WorkerSpec` table/class is needed to enable versioned, updateable worker configurations:

```kotlin
data class WorkerSpec(
    val workerName: String,           // e.g. "location_backfill"
    val specVersion: Int,             // bumped on any config change
    val isPeriodic: Boolean,
    val intervalMinutes: Long,
    val flexMinutes: Long?,           // null if no flex
    val requiredNetwork: NetworkType,
    val requiresBatteryNotLow: Boolean,
    val requiresCharging: Boolean,
    val backoffPolicy: BackoffPolicy,
    val backoffDelayMs: Long,
    val enabled: Boolean              // feature toggle
)
```

Storage: DataStore or Room table.

Logic at app startup and on settings change:
```
if (storedSpec == null || storedSpec.specVersion < currentSpec.specVersion) {
    cancelUniqueWork(workerName)
    enqueueNewWork(currentSpec)
    saveSpec(currentSpec)
}
```

### 8.2 BackgroundJobRun Table

```kotlin
@Entity(tableName = "background_job_runs")
data class BackgroundJobRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workerName: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,               // SUCCESS, FAILURE, RETRY
    val rowsScanned: Int,
    val rowsUpdated: Int,
    val notificationsSent: Int,
    val retryReason: String?,
    val errorMessage: String?,
    val settingsSnapshotHash: String? // for debugging
)
```

### 8.3 Feature-Aware Sync Use Cases

Replace direct `Worker.schedule()` calls with use cases that check settings:

```kotlin
SyncLocationBackfillWorkUseCase  — schedules/cancels based on location settings
SyncWarrantyExpirationWorkUseCase — schedules/cancels based on warranty settings
SyncProactiveBriefingWorkUseCase  — exists already
SyncDataRetentionWorkUseCase     — schedules/cancels based on privacy settings
```

All should be called at startup AND when relevant settings change.

### 8.4 Worker Failure Classification

```kotlin
sealed class WorkerResult {
    data class Success(val rowsScanned: Int = 0, val rowsUpdated: Int = 0, val notificationsSent: Int = 0) : WorkerResult()
    data class TransientFailure(val reason: String, val retryDelayMs: Long? = null) : WorkerResult()
    data class PermanentFailure(val reason: String) : WorkerResult()  // maps to Result.success() + diagnostics
}
```

### 8.5 Priority Order for Fixes

1. **Worker Spec Registry** — enables all future config changes
2. **BackgroundJobRun table** — enables debugging
3. **Wire up dead workers** — enable BillReminderWorker and ReceiptMatchingWorker scheduling
4. **Warranty sentinel state** — stop duplicate notifications
5. **Location overwrite guard** — conditional UPDATE at DAO level
6. **Failure classification** — stop infinite retries

---

## Appendix A: Files Reviewed

All source files in `app/src/main/java/com/yourname/expensetracker/`:

- `MainApplication.kt`
- `data/privacy/DataRetentionWorker.kt`
- `data/location/LocationBackfillWorker.kt`
- `data/location/MerchantKeyBackfillWorker.kt`
- `data/ai/worker/DailyBriefingWorker.kt`
- `data/ai/worker/AiWorkSchedulerImpl.kt`
- `data/database/entity/PrivacyAuditEvent.kt`
- `domain/ai/service/AiWorkScheduler.kt`
- `domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt`
- `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `domain/config/AppConfig.kt`
- `domain/reminder/BillReminderManager.kt`
- `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`
- `service/NotificationCaptureService.kt`
- `service/reminder/BillReminderWorker.kt`
- `service/receiptmatching/ReceiptMatchingWorker.kt`
- `service/warranty/WarrantyExpirationWorker.kt`
- `receiver/BootReceiver.kt`
- `receiver/ServiceRestartReceiver.kt`
- `startup/AppStartupCoordinator.kt`
- `startup/AppStartupDelegate.kt`
- `startup/AppBackgroundLifecycleObserver.kt`
- `AndroidManifest.xml`

## Appendix B: Existing Analysis Reference

A prior analysis exists at `docs/analyses and debug master/background-workers-analysis.md` covering a subset of workers (missing DataRetentionWorker, BillReminderWorker, ReceiptMatchingWorker, NotificationCaptureService, BootReceiver). This document supersedes it with full coverage.
