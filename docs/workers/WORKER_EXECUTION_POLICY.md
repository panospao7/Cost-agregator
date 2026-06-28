# Worker Execution Policy

> **PRs 1–5 Complete** — Canonical policy for all background workers in the ExpenseTracker app.
> References the `WorkerExecutionGuard` and `WorkerSpecScheduler` infrastructure.

---

## 1. Which Workers MUST Use `WorkerExecutionGuard`

All `CoroutineWorker` subclasses **MUST** use one of the two guard entry points:

| Entry Point | When to Use |
|---|---|
| `executionGuard.runGuarded(request) { block }` | Workers that do NOT need a `WorkerRunContext` (no checkpoint, no per-run counters). Currently unused by any worker. |
| `executionGuard.runGuardedWithContext(request) { ctx -> ... }` | **Every registered worker**. Provides checkpoint, row-scanned/updated/skipped/notification counters, and durable `BackgroundJobRun` logging. |

### All `CoroutineWorker` Subclasses Are Guarded

As of PR 5, `NotificationIntakeWorker` has been migrated to `runGuardedWithContext`. No `CoroutineWorker` subclass bypasses the full guard.

### Non-WorkManager Workers

`SourceLinkBackfillWorker` (a `@Singleton`, not a WorkManager worker) uses `writeBarrier.checkWritesAllowed()` directly. This is acceptable because it is not scheduled by WorkManager and runs synchronously from a ViewModel.

### BroadcastReceivers

`BootReceiver`, `ServiceRestartReceiver`, `DismissReminderReceiver`, `SnoozeReminderReceiver` are not WorkManager workers and do not use `WorkerExecutionGuard`. They run on the main thread for a short window. `DismissReminderReceiver` and `SnoozeReminderReceiver` use `goAsync()` with a coroutine scope.

---

## 2. Result Mapping

`WorkerGuardResult<T>.toWorkerResult()` maps guard outcomes to WorkManager `ListenableWorker.Result`:

| Guard Result | WorkManager Result | When Produced | Worker Impact |
|---|---|---|---|
| `WorkerGuardResult.Success` | `Result.success()` | Block completed without throwing. | WorkManager considers the worker finished. |
| `WorkerGuardResult.Skipped` | `Result.success()` | Write-barrier denied, privacy denied, notification permission denied, worker disabled, restore in progress, or no work to do. | **Treated as success** — NOT retried. The `BackgroundJobRun` is logged as SKIPPED with a `statusReason` for diagnostics. |
| `WorkerGuardResult.Retry` | `Result.retry()` | Transient error (timeout, IO exception, `SQLITE_BUSY`, deadlock, `RetryableWorkerException`). | WorkManager applies backoff via the spec-defined `BackoffPolicy`. |
| `WorkerGuardResult.Failed` | `Result.failure()` | Permanent error (unclassified exception, permanent mismatch). | WorkManager does NOT retry. The `BackgroundJobRun` is logged as FAILED. |

### Result Mapping Decisions

#### NoOp / No Work
Workers that find nothing to do return normally from the guard block, which produces `WorkerGuardResult.Success` with `rowsScanned=0, rowsUpdated=0, notificationsSent=0`. The run is logged as success with message `"NO_WORK"` (see `WorkerRunContext` + `WorkerRunHandle.success`).

**Do NOT return `Result.success()` early before the guard** — always route through the guard so the run is durably logged.

#### Blocked: Maintenance / Restore in Progress
When `RestoreMaintenanceMode.Mode != NORMAL`:
- If `allowDuringBackupExport=true` and mode is `BACKUP_EXPORTING` and `requiresDatabaseWrite=false` → read-only path with read barrier.
- Otherwise → `WorkerGuardResult.Skipped` with `DiagnosticReasonCode.RESTORE_BLOCKED`.

#### Blocked: Restart Required
When the write barrier throws `DatabaseAccessBlockedException`, the guard maps this to `WorkerGuardResult.Skipped` with `DiagnosticReasonCode.WRITE_BARRIER_DENIED`. The worker is **not retried** — the next periodic schedule or startup re-seed will try again.

#### Blocked: Permission Denied (Notification)
When `requiresNotificationPermission=true` and `NotificationPermissionChecker.areNotificationsEnabled()` returns `false`, the guard maps to `WorkerGuardResult.Skipped` with `DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED`.

**Do NOT permanently lose reminders.** Skipping (not failure) means WorkManager considers the run successful. The worker will run again on its next periodic schedule. This is safe for periodic workers. For one-shot workers, the next trigger (e.g., version bump, app restart, boot receiver) will re-enqueue.

#### Partial Failure
- **Do NOT soft-success a partial failure.** If a subset of items fails, the worker should throw `RetryableWorkerException` to trigger `WorkerGuardResult.Retry`. This ensures the run is durably logged as RETRY with context, not SUCCESS with hidden failures.
- `DataRetentionWorker` reports per-target failures via logs and audit events but does NOT throw for individual target failures (by design — one failing target must not block other targets).

---

## 3. Retry Policy

### Maintenance / Restart-Required Blocks

These produce `WorkerGuardResult.Skipped`, which maps to `Result.success()`. The worker is **not retried** by WorkManager. The next periodic schedule or startup re-seed re-enqueues the worker. This is intentional — retrying a blocked worker would busy-loop until maintenance exits.

### Transient Errors

| Error Type | Classification | Guard Action |
|---|---|---|
| `TimeoutCancellationException` | Always transient | `WorkerGuardResult.Retry` (logged as TIMEOUT) |
| `RetryableWorkerException` | Explicitly transient | `WorkerGuardResult.Retry` (highest precedence after cancellation) |
| `IOException` | Transient | `WorkerGuardResult.Retry` |
| Message contains "timeout" | Heuristic transient | `WorkerGuardResult.Retry` |
| Message contains "interrupted" | Heuristic transient | `WorkerGuardResult.Retry` |
| Message contains "deadlock" | Heuristic transient | `WorkerGuardResult.Retry` |
| Message contains "SQLITE_BUSY" | Heuristic transient | `WorkerGuardResult.Retry` |
| Message contains "database is locked" | Heuristic transient | `WorkerGuardResult.Retry` |
| Other exceptions | Permanent | `WorkerGuardResult.Failed` |

### Backoff Configuration

Backoff is read from `WorkerSpec.DEFAULTS`:

| Worker | Backoff Policy | Initial Delay |
|---|---|---|
| `data_retention` | Exponential | 30s (default) |
| `location_backfill` | Exponential | 15s |
| `bill_reminder_periodic` | Exponential | 30s (default) |
| `receipt_matching` | Exponential | 600s |
| `ai_daily_briefing` | Exponential | 30s (default) |
| `warranty_expiration_check` | Exponential | 600s |
| `merchant_key_backfill` | Exponential | 15s |

---

## 4. Permission-Denied Behavior

When notification permissions are denied:
1. The guard records a durable SKIPPED run with `DiagnosticReasonCode.NOTIFICATION_PERMISSION_DENIED`.
2. The worker block does NOT execute.
3. WorkManager receives `Result.success()` — the worker is **not retried**.
4. The next periodic schedule will re-check permissions.

**Do NOT permanently lose reminders.** Because the result is Success (not Failure), WorkManager will not exhaust retries. The worker remains scheduled and will run again on its next interval.

Only `WarrantyExpirationWorker` currently sets `requiresNotificationPermission = true`. `BillReminderWorker` catches `SecurityException` manually inside the guard block rather than using the declarative `requiresNotificationPermission` flag — this is a pre-existing pattern that could be migrated to declarative in a future PR.

---

## 5. Partial-Failure Behavior

### Do NOT Soft-Success

If a worker processes items in a loop and some items fail:
- **Transient failures** → Log the failure, continue processing remaining items, then throw `RetryableWorkerException` after the loop to trigger a guard-level Retry.
- **Permanent failures** → Log the failure, continue processing remaining items. The permanent failures are skipped on retry if they are durably recorded (e.g., `incrementBackfillAttempts` for location).
- **Do NOT return `Result.success()` if any item failed transiently** — this would mask the failure from diagnostics and deprive the operator/developer of visibility.

### Exception: `DataRetentionWorker`
Per-target purge failures are caught individually and reported via logs + audit events. The worker continues to the next target. This is intentional: one misbehaving retention target must not block the entire retention pipeline. The partial failure is visible via audit events and logs.

---

## 6. Terminal Diagnostics Requirements

Every guard-mediated run produces a durable `BackgroundJobRun` entry in Room:

| Outcome | `BackgroundJobRun.status` | `BackgroundJobRun.statusReason` | Counters |
|---|---|---|---|
| Success | `SUCCESS` | `null` or `"NO_WORK"` | rowsScanned, rowsUpdated, notificationsSent |
| Skipped (restore blocked) | `SKIPPED` | `RESTORE_BLOCKED` | N/A |
| Skipped (write-barrier denied) | `SKIPPED` | `WRITE_BARRIER_DENIED` | N/A |
| Skipped (privacy denied) | `SKIPPED` | `PRIVACY_DENIED` | N/A |
| Skipped (privacy fail-closed) | `SKIPPED` | `PRIVACY_FAIL_CLOSED` | N/A |
| Skipped (provider disabled) | `SKIPPED` | `PROVIDER_DISABLED` | N/A |
| Skipped (notification permission denied) | `SKIPPED` | `NOTIFICATION_PERMISSION_DENIED` | N/A |
| Retry | `RETRY` | Reason message from exception | N/A |
| Failed | `FAILED` | Error message from exception | N/A |
| Cancelled | `CANCELLED` | `CANCELLED_BY_SYSTEM` | N/A |
| Stale abort | `STALE_ABORTED` | `CANCELLED_BY_SYSTEM` | N/A |

### Stale Job Recovery

`WorkerExecutionGuard.recoverStaleRunningJobs()` marks any `BackgroundJobRun` still `RUNNING` after 4 hours as `STALE_ABORTED`. This runs at startup.

---

## 7. Schedule Failure Diagnostics

`WorkerSpecScheduler` logs schedule failures at `Log.ERROR` via `android.util.Log`:

- `WorkerSpecScheduler` uses `android.util.Log` (not `Timber`) for critical scheduling failures because Timber may not be initialized during early startup scheduling.
- Each schedule attempt is wrapped in try-catch. `CancellationException` is re-thrown; all other exceptions are logged and swallowed so one failed schedule does not prevent other workers from being scheduled.
- Version bumps are logged at `Log.INFO` with old/new version numbers.

### What Happens When Scheduling Fails

| Scenario | Behaviour |
|---|---|
| `WorkerSpec` not found for name | `Log.w` warning, return without enqueuing |
| Worker is disabled | Existing work is cancelled, no new work enqueued |
| Version has changed since last enqueue | Policy forced to UPDATE/REPLACE, logged at INFO |
| WorkManager throws during enqueue | Logged at ERROR, exception swallowed (except CancellationException) |
| Crash between enqueue and version-prefs write | On next schedule, version bump is detected again, forcing another UPDATE/REPLACE (safe) |

---

## 8. One-Shot Version Bump Policy

**Policy: When a one-shot worker's spec version bumps, the existing work MUST be replaced (UPDATE), not kept (KEEP).**

This ensures that a configuration change (constraints, backoff, etc.) takes effect even if a previous request is still pending.

### Current Implementation

`WorkerSpecScheduler.scheduleFromSpec()`:
- **Periodic**: Version bump → `ExistingPeriodicWorkPolicy.UPDATE` ✓
- **One-shot**: Version bump → `ExistingWorkPolicy.UPDATE` — **FIXED in PR 1** from the previous incorrect `ExistingWorkPolicy.KEEP`

`WorkerSpecScheduler.scheduleAtMidnight()`:
- **One-shot**: Version bump → `ExistingWorkPolicy.UPDATE` — **FIXED in PR 1** from the previous incorrect `ExistingWorkPolicy.KEEP`

### Why KEEP Was Wrong for Version Bumps

`ExistingWorkPolicy.KEEP` ignores the new enqueue request if any work with the same unique name is already pending. This means:
- A spec version bump (e.g., adding a network constraint) would be silently ignored.
- The worker would run with stale parameters until the pending request completes, then the next schedule would apply the new version.

`ExistingWorkPolicy.UPDATE` (added in WorkManager 2.8.x) atomically cancels the pending work and enqueues the new one — the correct behaviour for a version bump.

### Spec-Level Policy

The `WorkerSpec.oneShotPolicy` field controls the **non-bump** policy (used when the version has NOT changed). Currently:

| Worker | `oneShotPolicy` | Rationale |
|---|---|---|
| `ai_daily_briefing` | `REPLACE` | Midnight chain must always re-arm; KEEP caused silent chain death |
| `merchant_key_backfill` | `REPLACE` | Must be re-schedulable (e.g., after new merchants are imported) |

---

## 9. Cancellation Handling

### Rule: `CancellationException` MUST be re-thrown, never swallowed.

The guard (`runGuarded` / `runGuardedWithContext`) catches `CancellationException` in all catch blocks and:
1. Logs the cancellation to `BackgroundJobRun` via `run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)`.
2. Re-throws the exception so WorkManager sees the worker as cancelled.

```kotlin
// CORRECT — guard handles it:
catch (e: kotlinx.coroutines.CancellationException) {
    withContext(NonCancellable) { run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name) }
    throw e
}
```

Inside worker blocks:
- Worker code must NOT catch `CancellationException` unless it immediately re-throws.
- `NonCancellable` context is used for the logging call before re-throw.
- `isStopped` is checked in worker loops for cooperative cancellation — but `isStopped` is advisory; `CancellationException` is authoritative.

### `checkpoint()` behaviour

`WorkerExecutionGuard.checkpoint()` throws `CancellationException` if:
- A maintenance stop has been requested (via `leaseRegistry.isStopRequested()`)
- The write barrier denies writes

This is the correct, safe behaviour — the worker stops promptly when maintenance or a restore requires it.

---

## 10. Worker Registry Requirements

### 10.1 Every Worker Must Be Registered or Explicitly Allowlisted

| Category | Requirement |
|---|---|
| **Registered workers** (in `WorkerRegistry.entries`) | Must also have an entry in `WorkerSpec.DEFAULTS`. Pause/resume/schedule all derive from the registry. |
| **Allowlisted workers** | Must be documented with a justification for exclusion from the registry. |

### 10.2 Currently Allowlisted Workers

| Worker | Justification for Exclusion |
|---|---|
| `SourceLinkBackfillWorker` | Not a WorkManager worker. It is a `@Singleton` invoked directly by `SourceLinkBackfillViewModel`. Only uses `writeBarrier.checkWritesAllowed()` as a guard. |
| `BootReceiver` | `BroadcastReceiver`, not a WorkManager worker. |
| `ServiceRestartReceiver` | `BroadcastReceiver`, not a WorkManager worker. |
| `DismissReminderReceiver` | `BroadcastReceiver`, not a WorkManager worker. |
| `SnoozeReminderReceiver` | `BroadcastReceiver`, not a WorkManager worker. |

### 10.3 Registry/Spec Parity

Every key in `WorkerSpec.DEFAULTS` must have a matching `Entry` in `WorkerRegistry.entries` with the same `specName`, and vice versa.

**Current parity: 7/7 — PASS** (as of commit `3372b917`)

| `WorkerSpec.DEFAULTS` key | `WorkerRegistry.entries` name | Match |
|---|---|---|
| `data_retention` | `data_retention` | ✓ |
| `location_backfill` | `location_backfill` | ✓ |
| `bill_reminder_periodic` | `bill_reminder_periodic` | ✓ |
| `receipt_matching` | `receipt_matching` | ✓ |
| `ai_daily_briefing` | `ai_daily_briefing` | ✓ |
| `warranty_expiration_check` | `warranty_expiration_check` | ✓ |
| `merchant_key_backfill` | `merchant_key_backfill` | ✓ |

### 10.4 Enforcement

`PrivacyRuntimeWorkerPolicy` validates at `init` that every worker name it references exists in `WorkerSpec.DEFAULTS`. This catches typos and stale names at class-load time.

---

## 11. Foreground Workers

No worker currently uses `setForeground()` / `ForegroundInfo`. All workers run as background WorkManager jobs bound by the 10-minute execution limit.

Workers that exceed the 10-minute limit:
- `LocationBackfillWorker` — processes at most 50 expenses per run.
- `MerchantKeyBackfillWorker` — processes at most 25 batches (5000 rows) per run.
- `DataRetentionWorker` — processes targets incrementally with checkpointing.

---

## 12. WorkerSpec Constraints Summary

| Worker | Network | Battery | Charging | Storage | Idle |
|---|---|---|---|---|---|
| `data_retention` | NOT_REQUIRED | None | None | None | None |
| `location_backfill` | UNMETERED | None | None | None | None |
| `bill_reminder_periodic` | NOT_REQUIRED | None | None | None | None |
| `receipt_matching` | NOT_REQUIRED | None | None | None | None |
| `ai_daily_briefing` | UNMETERED | Battery NOT LOW | Required | None | None |
| `warranty_expiration_check` | NOT_REQUIRED | None | None | None | None |
| `merchant_key_backfill` | None | Battery NOT LOW | None | None | None |
