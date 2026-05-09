# Pipeline 9 Debug Report — Workers / Background Jobs

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 9 is **better structured than before but not clean/stable yet**.

Good foundations exist:

- Hilt WorkManager integration via `MainApplication : Configuration.Provider`.
- Most workers are `@HiltWorker`.
- `WorkerSpec.DEFAULTS` centralizes interval/constraints/enabled/backoff.
- `WorkerSpecScheduler` centralizes scheduling.
- `RestoreMaintenanceMode` cancels unique workers and most workers check restore mode.
- `BackgroundJobRun` and `BackgroundJobRunDao` exist.
- Several workers have partial idempotency:
  - data retention purges only unpurged rows,
  - merchant-key backfill processes null keys,
  - location backfill uses conditional location update,
  - daily briefing skips fresh artifacts,
  - warranty reminders have persistent SharedPreferences cooldown.

But the pipeline is still **yellow/orange**, not production-clean, because the most important worker contract is incomplete:

```text
worker starts
→ guard restore/privacy/settings
→ claim/log run
→ do idempotent work
→ save final run result/retry reason
→ recover stale running jobs
→ avoid duplicate notifications/effects
```

Right now, that contract is duplicated manually in each worker, and the `BackgroundJobRun` table is essentially unused.

Main risks:

1. workers do not persist `BackgroundJobRun` records;
2. no shared `WorkerExecutionGuard` / `WorkerRunLogger`;
3. restore/backup pause is cancellation-only and not a true running-worker barrier;
4. daily briefing one-shot chain can stop after early success paths;
5. bill reminders are disabled by static config and are not exactly-once safe;
6. receipt matching `runOnce()` bypasses unique scheduling;
7. worker pause/resume registry is hardcoded and asymmetric;
8. privacy setting changes do not actively cancel/reschedule workers;
9. stale `RUNNING` jobs are queryable but not recovered;
10. notification delivery state is inconsistent across workers.

Current state: **beta-safe for simple runs, not production-stable for observability/idempotency/recovery**.

---

# Severity scale

- **P0 / Critical:** worker can corrupt DB, leak privacy data, or repeatedly create money-affecting duplicates.
- **P1 / High:** missing run audit, non-idempotent side effects, restore/write-barrier hole, broken schedule chain.
- **P2 / Medium:** weak diagnostics, inconsistent retry, static config drift, UX/debug gap.
- **P3 / Low:** cleanup/maintainability.

---

# Worker checklist status

| Worker | Hilt construction | Scheduled | Idempotent | Logs `BackgroundJobRun` | Restore guard | Privacy/settings guard | Duplicate side-effect safety |
|---|---:|---:|---:|---:|---:|---:|---:|
| `DailyBriefingWorker` | Yes | Via AI scheduler | Partial | No | Yes | Cloud AI privacy gate | Partial; cache-based |
| `LocationBackfillWorker` | Yes | Startup periodic | Partial | No | Yes | Background location gate | Mostly; conditional update |
| `MerchantKeyBackfillWorker` | Yes | Startup one-shot | Mostly | No | Yes | N/A | Mostly |
| `WarrantyExpirationWorker` | Yes | Startup periodic | Partial | No | Yes | Notification permission only | Partial; SharedPreferences cooldown |
| `BillReminderWorker` | Yes | Static spec disabled | Weak | No | Yes | Static spec only | Weak; no atomic claim |
| `ReceiptMatchingWorker` | Yes | Startup periodic + unsafe runOnce | Partial | No | Yes | No explicit privacy gate | Partial; link conflicts logged |
| `DataRetentionWorker` | Yes | Startup periodic | Mostly | No | Yes | Uses retention settings | Mostly |

---

# Positive findings to preserve

## PF-01 — Hilt worker construction is wired

`MainApplication` injects `HiltWorkerFactory` and returns WorkManager configuration from `Configuration.Provider`.

This is the correct base for `@HiltWorker` classes.

## PF-02 — `WorkerSpec.DEFAULTS` centralizes worker config

`WorkerSpec.DEFAULTS` defines names, enabled flags, constraints, intervals, flex windows, and backoff parameters for the 7 core workers:

```text
data_retention
location_backfill
bill_reminder_periodic
receipt_matching
ai_daily_briefing
warranty_expiration_check
merchant_key_backfill
```

This is a good step toward worker consistency.

## PF-03 — Startup worker scheduling is isolated

`AppStartupCoordinator.scheduleStartupWork()` wraps each schedule call in `runCatching`, so one failed schedule does not block the rest.

## PF-04 — Restore guard exists in most worker bodies

The inspected workers check `restoreMaintenanceMode.isWritesAllowed()` near the start of `doWork()`.

This is good defense-in-depth, though not yet a full global barrier.

## PF-05 — Location worker has useful privacy and race protections

`LocationBackfillWorker` checks `PrivacyCapability.BACKGROUND_LOCATION_BACKFILL`, evicts stale location cache, limits batch size, anonymizes merchant logs, and uses conditional location updates to avoid overwriting user-set locations.

## PF-06 — Data retention worker is idempotent

It only purges rows that have not already been purged and writes privacy audit events when purge counts are non-zero.

## PF-07 — Daily briefing has timeout and cache skip

`DailyBriefingWorker` bounds the pipeline with a timeout and skips generation if a fresh artifact exists.

That is good, but the early-return scheduling problem below must be fixed.

---

# Issue P1-01 — `BackgroundJobRun` table exists but workers do not use it

## Severity

P1 / High

## Evidence

`BackgroundJobRun` and `BackgroundJobRunDao` exist and are registered in `AppDatabase`.

But inspected workers do not inject `BackgroundJobRunDao` and do not insert/update run records:

```text
DailyBriefingWorker
LocationBackfillWorker
MerchantKeyBackfillWorker
WarrantyExpirationWorker
BillReminderWorker
ReceiptMatchingWorker
DataRetentionWorker
```

They mostly use `Log`/`Timber`.

## Impact

The app cannot reliably answer:

```text
When did this worker last run?
Did it succeed?
Did it retry?
What was the retry reason?
How many rows did it process?
How many notifications did it send?
Was it skipped due restore/privacy/settings?
```

This fails the Pipeline 9 checklist:

```text
logs BackgroundJobRun
retry reason saved
failure is visible
stale running jobs recovered
```

## Fixing strategy

Create a shared `WorkerRunLogger` and make every worker use it.

## Implementation plan

1. Add:

```kotlin
interface WorkerRunLogger {
    suspend fun start(workerName: String): WorkerRunHandle
}

interface WorkerRunHandle {
    suspend fun success(
        rowsScanned: Int = 0,
        rowsUpdated: Int = 0,
        notificationsSent: Int = 0,
        message: String? = null
    )

    suspend fun skipped(reason: String)
    suspend fun retry(reason: String, error: Throwable? = null)
    suspend fun failure(reason: String, error: Throwable? = null)
}
```

2. Implement with `BackgroundJobRunDao`.

3. Add statuses beyond current table:

```text
RUNNING
SUCCESS
SKIPPED_RESTORE
SKIPPED_PRIVACY
SKIPPED_DISABLED
SKIPPED_NO_WORK
RETRY
FAILED
CANCELLED
```

4. Update every worker:

```kotlin
val run = workerRunLogger.start(WORK_NAME)
try {
    ...
    run.success(...)
} catch (e: Exception) {
    run.retry(...)
}
```

5. Tests:

```text
each_worker_inserts_RUNNING_at_start
each_worker_updates_SUCCESS_on_success
restore_skip_writes_SKIPPED_RESTORE
privacy_skip_writes_SKIPPED_PRIVACY
retry_writes_retryReason
failure_writes_errorMessage
```

---

# Issue P1-02 — No shared `WorkerExecutionGuard`

## Severity

P1 / High

## Evidence

Each worker manually checks some subset of:

```text
restore mode
WorkerSpec enabled
privacy gate
notification permission
settings gate
isStopped
```

There is no shared guard/wrapper in `domain/workers`; that package only contains:

```text
WorkerSpec
WorkerSpecScheduler
```

## Impact

Worker behavior is inconsistent:

- some privacy-sensitive workers check privacy;
- some only check `WorkerSpec`;
- some skip with `Result.success`;
- some retry;
- skipped reasons are not standardized;
- future workers can miss restore/privacy/logging entirely.

## Fixing strategy

Introduce one worker execution wrapper.

## Implementation plan

1. Add:

```kotlin
data class WorkerGuardRequest(
    val workerName: String,
    val requiredCapabilities: List<PrivacyCapability> = emptyList(),
    val requiresNotificationPermission: Boolean = false,
    val allowDuringBackupExport: Boolean = false
)
```

2. Add:

```kotlin
class WorkerExecutionGuard {
    suspend fun <T> runGuarded(
        request: WorkerGuardRequest,
        block: suspend WorkerRunContext.() -> T
    ): WorkerGuardResult<T>
}
```

3. Responsibilities:
   - check `WorkerSpec`;
   - check restore/write mode;
   - check privacy capabilities;
   - check notification permission if requested;
   - create/update `BackgroundJobRun`;
   - classify skip/retry/failure;
   - handle cancellation.

4. Migrate workers one by one.

5. Add static guard:

```text
Every CoroutineWorker in app/src/main/java must call WorkerExecutionGuard or be allowlisted.
```

---

# Issue P1-03 — Restore/backup cancellation is not a true running-worker barrier

## Severity

P1 / High

## Evidence

`RestoreMaintenanceMode.enter()` calls `pauseAllWorkers()` using `WorkManager.cancelUniqueWork()`.

Cancellation is asynchronous. Running workers may continue for some time.

Also:

```kotlin
isWritesAllowed() == true
```

during `BACKUP_EXPORTING`.

Workers check restore mode only at the start of `doWork()`, not before every write.

## Impact

During backup/restore:

- already-running workers may continue writing after cancellation;
- data retention can purge raw content while backup is being created;
- receipt matching can link receipts while a DB snapshot is being copied;
- location backfill can update expenses during export;
- bill/warranty workers can send notifications while restore is in progress.

This overlaps with Pipeline 7’s backup consistency risks.

## Fixing strategy

Add a runtime execution barrier, not only scheduling cancellation.

## Implementation plan

1. Add `WorkerExecutionGuard.checkpoint()`:

```kotlin
suspend fun checkpoint(operation: String) {
    if (!restoreMaintenanceMode.isWritesAllowed()) throw WorkerStoppedForRestore()
    ensureActive()
}
```

2. For long loops, call checkpoint before each DB mutation.

3. Change backup policy:

```text
BACKUP_EXPORTING either blocks write workers or uses explicit read-only-safe allowlist.
```

4. `RestoreMaintenanceMode.enter()` should:
   - cancel workers,
   - wait/check WorkManager states for running unique work,
   - or set a global barrier first so running workers stop at checkpoints.

5. Tests:

```text
running_location_worker_stops_after_restore_mode_enters
running_data_retention_worker_stops_before_next_purge_write
backup_exporting_blocks_write_workers_unless_allowlisted
restore_enter_waits_for_or_detects_running_workers
```

---

# Issue P1-04 — Daily briefing one-shot chain can stop after early exits

## Severity

P1 / High

## Evidence

`DailyBriefingWorker` reschedules the next midnight run only after the full generation/delivery success path:

```text
generate briefing
→ deliver notification
→ aiWorkScheduler.scheduleDailyBriefing()
```

But it returns early for:

```text
restore mode
privacy denied
fresh artifact exists
```

without calling `scheduleDailyBriefing()`.

## Impact

Because daily briefing is a one-shot midnight-aligned worker, an early `Result.success()` can break the chain.

Examples:

```text
Fresh artifact exists today
→ worker returns success
→ no next midnight scheduled

Cloud AI disabled today
→ worker returns success
→ user enables AI tomorrow
→ worker may not be scheduled
```

## Fixing strategy

Always schedule the next one-shot in `finally` for non-retry terminal outcomes, or move recurring scheduling to a periodic worker.

## Implementation plan

1. Wrap logic:

```kotlin
override suspend fun doWork(): Result {
    var shouldScheduleNext = true
    return try {
        ...
    } finally {
        if (shouldScheduleNext) {
            runCatching { aiWorkScheduler.scheduleDailyBriefing() }
        }
    }
}
```

2. Do not schedule next only if WorkManager is retrying the same work immediately, or if the worker is disabled by effective runtime policy.

3. Add explicit tests:

```text
daily_briefing_fresh_artifact_still_schedules_next_midnight
daily_briefing_privacy_denied_still_schedules_next_or_policy_sync_handles_it
daily_briefing_restore_skip_does_not_permanently_break_schedule
daily_briefing_success_schedules_next_once
```

---

# Issue P1-05 — Bill reminder worker is disabled by static `WorkerSpec`

## Severity

P1 / High

## Evidence

`WorkerSpec.DEFAULTS["bill_reminder_periodic"]` has:

```text
enabled = false
```

`BillReminderWorker.schedule()` delegates to `WorkerSpecScheduler.scheduleFromSpec()`, which cancels disabled workers and returns.

## Impact

Bill reminders do not run by default.

A static source-code flag is not a proper user setting. If the user enables bill reminders in UI, this static `enabled = false` still blocks scheduling unless another override exists.

## Fixing strategy

Move enablement to runtime settings.

## Implementation plan

1. Keep infrastructure spec enabled, or add runtime override:

```kotlin
BillReminderSettingsRepository.remindersEnabled()
```

2. Scheduler should use:

```text
WorkerSpec.enabled && userSettingEnabled
```

3. When setting changes:
   - enabled → schedule worker;
   - disabled → cancel worker and pending notifications if desired.

4. Tests:

```text
bill_reminder_disabled_user_setting_cancels_worker
bill_reminder_enabled_user_setting_schedules_worker
static_WorkerSpec_does_not_permanently_disable_user_enabled_feature
```

---

# Issue P1-06 — Bill reminders are not exactly-once safe

## Severity

P1 / High

## Evidence

`BillReminderWorker` flow is:

```text
getDueReminders()
→ send Android notification
→ markReminderSent()
```

There is no atomic claim step.

If two worker instances overlap, both can read the same due reminder before either marks it sent.

If the app crashes after `notify()` but before `markReminderSent()`, the next run can send the same notification again.

If notification permission is missing, the row remains due and can retry indefinitely.

## Impact

User can receive duplicate bill reminders or repeated failed attempts.

## Fixing strategy

Use a durable delivery state machine with atomic claim.

## Implementation plan

1. Add statuses:

```text
SCHEDULED
CLAIMED
SENT
FAILED_PERMISSION
FAILED_TRANSIENT
DISMISSED
SNOOZED
SUPPRESSED_PAID
```

2. Add DAO method:

```kotlin
@Query("""
UPDATE recurring_reminder_deliveries
SET status = 'CLAIMED', lastAttemptAt = :now, attemptCount = attemptCount + 1
WHERE id = :id
  AND status IN ('SCHEDULED', 'SNOOZED')
""")
suspend fun claimDelivery(id: Long, now: Long): Int
```

3. Worker flow:

```text
load due IDs
claim each ID
if claim failed → skip
send notification
success → SENT
permission failure → FAILED_PERMISSION
transient failure → FAILED_TRANSIENT or retry
```

4. Tests:

```text
two_bill_workers_same_delivery_only_one_sends
crash_after_notify_before_mark_sent_has_defined_recovery_policy
missing_permission_marks_FAILED_PERMISSION
failed_permission_not_retried_forever
```

---

# Issue P1-07 — `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling

## Severity

P1 / High

## Evidence

`ReceiptMatchingWorker.runOnce(context)` creates a plain `OneTimeWorkRequest` and calls:

```kotlin
WorkManager.getInstance(context).enqueue(request)
```

It does not use:

```text
enqueueUniqueWork
WorkerSpecScheduler
unique work name
constraints
dedupe policy
```

## Impact

Manual/debug/UI “run once” can run concurrently with the periodic receipt matching worker.

Two instances can attempt to match/link the same receipt at the same time.

## Fixing strategy

Make one-shot manual runs unique and guarded.

## Implementation plan

1. Replace:

```kotlin
enqueue(request)
```

with:

```kotlin
enqueueUniqueWork(
    "receipt_matching_run_once",
    ExistingWorkPolicy.KEEP,
    request
)
```

2. Add constraints from `WorkerSpec.DEFAULTS["receipt_matching"]`.

3. Add a per-receipt DB claim if needed:

```text
matchStatus = MATCHING
```

4. Tests:

```text
receipt_matching_runOnce_is_unique
periodic_and_runOnce_do_not_match_same_receipt_concurrently
receipt_matching_skips_receipts_claimed_by_another_worker
```

---

# Issue P1-08 — Receipt matching worker outcomes are not durable enough

## Severity

P1 / High

## Evidence

`ReceiptMatchingWorker`:

- logs worker start/end with `Timber`;
- logs link failure but continues;
- uses `receiptRepository.saveMatchSuggestion()` for suggestions;
- sends a notification for auto-match.

There is no `BackgroundJobRun` row, and not every per-receipt outcome necessarily writes a `ReceiptEvent`.

## Impact

If a receipt was not matched, user/debug cannot reliably tell whether:

```text
worker did not run
receipt was skipped due document type
no candidate found
suggestion was created
auto-link failed
link conflict occurred
notification failed
```

## Fixing strategy

Persist per-worker and per-receipt outcomes.

## Implementation plan

1. Worker-level:
   - insert/update `BackgroundJobRun`.

2. Receipt-level:
   - write `ReceiptEvent` for:
     - `MATCH_WORKER_SKIPPED_DOCUMENT_TYPE`
     - `MATCH_NOT_FOUND`
     - `MATCH_SUGGESTED`
     - `AUTO_MATCH_LINKED`
     - `AUTO_MATCH_LINK_FAILED`

3. Use `ReceiptLinkService` for all link mutations and a lifecycle method for suggestions.

4. Tests:

```text
receipt_matching_skipped_bank_statement_writes_event
receipt_matching_no_match_writes_debug_event
receipt_matching_suggestion_writes_event
receipt_matching_link_failure_writes_event
```

---

# Issue P1-09 — Warranty notification sent-state is outside DB and can be wrong

## Severity

P1 / High

## Evidence

`WarrantyExpirationWorker` stores last-notified state in `SharedPreferences`.

It calls `notificationService.sendBudgetAlert(...)` and then immediately records the timestamp.

The code does not inspect a delivery result.

Also, SharedPreferences state is not part of the Room DB backup/restore model.

## Impact

Two opposite bugs are possible:

1. Notification delivery fails, but timestamp is recorded → reminder suppressed.
2. DB is restored/migrated without SharedPreferences state → reminder can be sent again.

## Fixing strategy

Move warranty reminder delivery state into Room and record only after confirmed delivery.

## Implementation plan

1. Add table:

```kotlin
WarrantyReminderDelivery(
    warrantyId: Long,
    windowDays: Int,
    status: String,
    scheduledAt: Long,
    sentAt: Long?,
    failureReason: String?,
    uniqueKey: String
)
```

2. Use unique key:

```text
warrantyId + windowDays + expiryDate
```

3. Only mark `SENT` if `NotificationService` confirms delivery.

4. Include state in backup/restore.

5. Tests:

```text
warranty_notification_failure_does_not_mark_sent
warranty_sent_state_survives_db_backup_restore
warranty_same_window_not_resent_after_restart
warranty_new_expiry_date_creates_new_delivery
```

---

# Issue P1-10 — Worker pause/resume registry is hardcoded and asymmetric

## Severity

P1 / High

## Evidence

`RestoreMaintenanceMode.pauseAllWorkers()` cancels all names from `WorkerSpec.DEFAULTS`.

But `scheduleAllWorkers()` manually hardcodes calls to each worker’s `schedule()` plus special AI scheduling.

`AppStartupCoordinator.scheduleStartupWork()` also hardcodes another list.

## Impact

If a new worker is added to `WorkerSpec.DEFAULTS` but not to both hardcoded schedulers:

```text
it may be cancelled during restore
but not resumed
```

or:

```text
it may be scheduled at startup
but not paused during restore
```

## Fixing strategy

Create a single worker registry.

## Implementation plan

1. Add:

```kotlin
data class WorkerRegistration(
    val name: String,
    val workerClass: Class<out ListenableWorker>,
    val scheduleMode: ScheduleMode
)
```

2. Registry:

```kotlin
object WorkerRegistry {
    val all = listOf(...)
}
```

3. Use the registry for:
   - startup scheduling;
   - restore pause;
   - restore resume;
   - CI coverage.

4. Tests:

```text
every_WorkerSpec_default_has_registration
every_registration_has_WorkerSpec_default
restore_pause_and_resume_cover_same_worker_names
startup_schedules_every_enabled_registered_worker
```

---

# Issue P1-11 — Privacy setting changes do not actively cancel workers

## Severity

P1 / High

## Evidence

Workers check privacy/settings at runtime in some places:

```text
LocationBackfillWorker → BACKGROUND_LOCATION_BACKFILL
DailyBriefingWorker → CLOUD_AI_DAILY_BRIEFING
WarrantyExpirationWorker → notification permission
```

But there is no central runtime policy applier that cancels or reschedules workers when privacy settings change.

Also, `WorkerSpecScheduler.scheduleAtMidnight()` skips disabled workers but does not cancel existing one-shot work.

## Impact

A worker scheduled before a privacy change can still run later and only then skip, or possibly execute a path whose worker forgot a privacy gate.

For one-shot AI briefing, disabling a spec/settings flag may leave existing scheduled work in WorkManager.

## Fixing strategy

Privacy/settings updates must actively update WorkManager state.

## Implementation plan

1. Add `PrivacyRuntimePolicyApplier`:

```kotlin
on cloudAiEnabled false → cancel ai_daily_briefing
on backgroundLocationBackfill false → cancel location_backfill
on notificationCapture false → cancel notification-related work
```

2. Update `WorkerSpecScheduler.scheduleAtMidnight()`:

```kotlin
if (!spec.enabled) {
    cancelUniqueWork(workerName)
    return
}
```

3. Tests:

```text
disable_cloud_ai_cancels_ai_daily_briefing
disable_background_location_cancels_location_backfill
scheduleAtMidnight_disabled_cancels_existing_work
```

---

# Issue P2-12 — Stale running-job recovery is not implemented

## Severity

P2 / Medium

## Evidence

`BackgroundJobRunDao` has:

```text
getStaleRunningRuns(staleThresholdMs)
```

But startup code does not query it, and workers do not create `RUNNING` rows.

## Impact

If the app process dies during a worker run, the app cannot mark the abandoned run as stale or recover from it.

## Fixing strategy

Once `BackgroundJobRun` logging is added, recover stale runs at startup.

## Implementation plan

1. Add:

```kotlin
BackgroundJobRecoveryService
```

2. At startup:

```text
find RUNNING older than threshold
mark STALE_ABORTED
optionally enqueue repair/retry work
```

3. Tests:

```text
startup_marks_old_RUNNING_job_as_STALE_ABORTED
startup_does_not_mark_recent_RUNNING_job_stale
stale_receipt_matching_job_requeues_matching_worker
```

---

# Issue P2-13 — Retry/permanent-failure classification is inconsistent

## Severity

P2 / Medium

## Evidence

Different workers use different rules:

- `DailyBriefingWorker` treats several exception classes/messages as permanent.
- `ReceiptMatchingWorker` classifies some `IllegalStateException` messages as permanent.
- `DataRetentionWorker` retries every exception.
- `LocationBackfillWorker` retries if any retryable item failed.
- `BillReminderWorker` retries outer exceptions but not per-delivery notification failure.

## Impact

Equivalent failures behave differently across workers.

Some permanent failures can retry forever; some transient failures can become permanent.

## Fixing strategy

Add shared worker error classification.

## Implementation plan

1. Add:

```kotlin
sealed interface WorkerErrorKind {
    data object Transient
    data object Permanent
    data object PermissionDenied
    data object PrivacyDenied
    data object RestoreBlocked
}
```

2. Add classifier:

```kotlin
WorkerErrorClassifier.classify(workerName, throwable)
```

3. Workers return standardized result via `WorkerExecutionGuard`.

4. Tests:

```text
sqlite_busy_classified_transient
permission_denied_classified_permission
invalid_config_classified_permanent
privacy_denied_classified_skipped_not_retry
```

---

# Issue P2-14 — Notification delivery outcomes are inconsistent

## Severity

P2 / Medium

## Evidence

Workers send notifications via different paths:

```text
BillReminderWorker → NotificationManagerCompat directly and returns Boolean
WarrantyExpirationWorker → NotificationService.sendBudgetAlert and ignores result
ReceiptMatchingWorker → NotificationService.sendBudgetAlert and ignores result
DailyBriefingWorker → DeliverProactiveBriefingNotificationUseCase
```

## Impact

Some workers know whether notification delivery failed; others do not.

Duplicate/suppression state can be wrong.

## Fixing strategy

Use one notification delivery port that returns a structured result.

## Implementation plan

1. Define:

```kotlin
sealed interface NotificationDeliveryResult {
    data class Delivered(val notificationId: Int) : NotificationDeliveryResult
    data class BlockedPermission(val reason: String) : NotificationDeliveryResult
    data class Failed(val retryable: Boolean, val reason: String) : NotificationDeliveryResult
}
```

2. Make all worker notifications use it.

3. Persist result into:
   - `BackgroundJobRun`;
   - feature-specific delivery tables.

4. Tests:

```text
warranty_worker_does_not_record_sent_when_permission_blocked
receipt_matching_worker_records_notification_failure
bill_reminder_worker_uses_same_notification_delivery_port
```

---

# Issue P2-15 — `MerchantKeyBackfillWorker` scheduling policy/comment mismatch

## Severity

P2 / Medium

## Evidence

`MerchantKeyBackfillWorker` comments say it uses `REPLACE` so it can be rescheduled.

`WorkerSpec.DEFAULTS["merchant_key_backfill"]` does not explicitly set a one-shot replacement policy.

`WorkerSpecScheduler` maps `ExistingPeriodicWorkPolicy.KEEP` to `ExistingWorkPolicy.KEEP` for one-shot work.

## Impact

The actual scheduling behavior may not match the documented intention.

If WorkManager considers existing completed unique work in a way the code did not expect, re-backfill triggers may be skipped.

## Fixing strategy

Represent one-shot work policy explicitly.

## Implementation plan

1. Change `WorkerSpec`:

```kotlin
val periodicPolicy: ExistingPeriodicWorkPolicy = KEEP
val oneShotPolicy: ExistingWorkPolicy = KEEP
```

2. Set merchant key backfill:

```kotlin
oneShotPolicy = ExistingWorkPolicy.REPLACE
```

3. Tests:

```text
merchant_key_backfill_uses_REPLACE_policy
one_shot_policy_not_inferred_from_periodic_policy
worker_spec_comments_match_runtime_policy
```

---

# Issue P2-16 — Data retention worker scope is incomplete

## Severity

P2 / Medium

## Evidence

`DataRetentionWorker` has a TODO to expand retention beyond:

```text
raw notification content
raw OCR text
```

Missing likely sensitive targets:

```text
AI artifacts
chat messages
debug diagnostics
email receipt sources
```

## Impact

The worker is scheduled and appears to enforce privacy retention, but sensitive raw data may remain in other tables.

## Fixing strategy

Create a retention-target registry.

## Implementation plan

1. Add:

```kotlin
interface RetentionTarget {
    suspend fun purge(cutoff: Long, now: Long): RetentionPurgeResult
}
```

2. Register targets:

```text
RawNotificationRetentionTarget
ScannedReceiptOcrRetentionTarget
EmailReceiptRetentionTarget
AiArtifactRetentionTarget
AiChatRetentionTarget
DebugDiagnosticsRetentionTarget
```

3. Worker logs one `BackgroundJobRun` with per-target metadata.

4. Tests:

```text
data_retention_purges_email_receipt_body
data_retention_purges_ai_artifact_prompts
data_retention_purges_ai_chat_messages
data_retention_records_per_target_counts
```

---

# Recommended fixing order

## PR 1 — Worker run logging foundation

Files:

```text
BackgroundJobRun.kt
BackgroundJobRunDao.kt
new WorkerRunLogger.kt
AppDatabase.kt if schema fields need expansion
```

Fix:

```text
- every worker writes RUNNING and final status
- retry/failure/skipped reason saved
```

## PR 2 — WorkerExecutionGuard

Files:

```text
new WorkerExecutionGuard.kt
WorkerSpec.kt
WorkerSpecScheduler.kt
RestoreMaintenanceMode.kt
PrivacyGate integration
```

Fix:

```text
- centralized restore/spec/privacy/settings guard
- standardized Result.success/retry/failure behavior
```

## PR 3 — Daily briefing schedule-chain fix

Files:

```text
DailyBriefingWorker.kt
AiWorkSchedulerImpl.kt
WorkerSpecScheduler.kt
```

Fix:

```text
- always schedule next one-shot on terminal success/skip
- disabled one-shot cancels existing work
```

## PR 4 — Bill reminder exact-once delivery

Files:

```text
BillReminderWorker.kt
RecurringReminderDelivery.kt
RecurringReminderDeliveryDao.kt
RecurringLifecycleCoordinator.kt
```

Fix:

```text
- atomic CLAIMED state before notification
- permission failure durable
- no duplicate sends on overlapping workers
```

## PR 5 — Worker registry symmetry

Files:

```text
new WorkerRegistry.kt
AppStartupCoordinator.kt
RestoreMaintenanceMode.kt
WorkerSpec.kt
```

Fix:

```text
- startup/pause/resume all use same registry
- CI guard for WorkerSpec coverage
```

## PR 6 — Receipt matching worker hardening

Files:

```text
ReceiptMatchingWorker.kt
ReceiptTransactionMatcher.kt
ReceiptLinkService.kt
ReceiptRepository.kt
ReceiptEvent.kt
```

Fix:

```text
- unique runOnce
- per-receipt matching events
- claim/lock receipts if needed
```

## PR 7 — Warranty delivery state in Room

Files:

```text
WarrantyExpirationWorker.kt
WarrantyDao.kt or new WarrantyReminderDelivery.kt/Dao
NotificationService.kt
```

Fix:

```text
- sent-state persisted in DB
- only mark sent after confirmed delivery
- backup/restore preserves state
```

## PR 8 — Stale job recovery

Files:

```text
AppStartupCoordinator.kt
new BackgroundJobRecoveryService.kt
BackgroundJobRunDao.kt
```

Fix:

```text
- mark abandoned RUNNING jobs stale
- optionally requeue recoverable workers
```

## PR 9 — Data retention target expansion

Files:

```text
DataRetentionWorker.kt
EmailReceiptDao
AiArtifactDao
AiChatMessageDao
ServiceDiagnosticsDao
```

Fix:

```text
- retention registry
- sensitive artifact purge
```

---

# Golden tests to add

```text
all_workers_construct_with_hilt_worker_factory
all_WorkerSpec_defaults_have_registry_entry
startup_schedules_every_enabled_worker
restore_enter_cancels_all_registered_workers
restore_exit_reschedules_all_enabled_workers
every_worker_writes_BackgroundJobRun_RUNNING
every_worker_writes_success_or_skip_or_retry_or_failed
stale_RUNNING_jobs_marked_aborted_on_startup
daily_briefing_fresh_artifact_still_schedules_next_midnight
daily_briefing_privacy_denied_does_not_break_schedule_chain
bill_reminder_two_workers_claim_same_delivery_only_one_sends
bill_reminder_permission_denied_marks_failed_permission
receipt_matching_runOnce_is_unique
receipt_matching_periodic_and_runOnce_do_not_double_link
warranty_notification_failure_does_not_mark_sent
warranty_sent_state_survives_backup_restore
location_backfill_privacy_denied_writes_skipped_privacy_run
data_retention_restore_mode_writes_skipped_restore_run
merchant_key_backfill_policy_matches_REPLACE_or_documented_KEEP
privacy_disable_cloud_ai_cancels_ai_daily_briefing
privacy_disable_location_backfill_cancels_location_worker
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "class .*Worker" app/src/main/java
grep -R "@HiltWorker" app/src/main/java
grep -R "BackgroundJobRun" app/src/main/java
grep -R "backgroundJobRunDao" app/src/main/java
grep -R "WorkerSpec.DEFAULTS" app/src/main/java
grep -R "scheduleFromSpec" app/src/main/java
grep -R "scheduleAtMidnight" app/src/main/java
grep -R "cancelUniqueWork" app/src/main/java
grep -R "enqueue(" app/src/main/java/com/yourname/expensetracker/service app/src/main/java/com/yourname/expensetracker/data
grep -R "sendBudgetAlert" app/src/main/java
```

Allowed workers should be registered in one place:

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
- Every worker is registered in WorkerRegistry.
- Every worker execution creates and finalizes BackgroundJobRun.
- Every skip/retry/failure has a durable reason.
- Restore/backup mode stops running workers at safe checkpoints.
- Daily briefing one-shot schedule cannot break on early success/skip.
- Bill reminders use atomic claim before notification.
- Receipt matching manual run is unique and cannot overlap unsafely.
- Warranty reminder sent-state is DB-backed and delivery-aware.
- Privacy setting changes actively cancel/reschedule affected workers.
- Stale RUNNING jobs are recovered at startup.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `MainApplication.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/MainApplication.kt

- `AppStartupCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `RestoreMaintenanceMode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `WorkerSpec.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

- `WorkerSpecScheduler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt

- `BackgroundJobRun.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt

- `BackgroundJobRunDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt

- `DailyBriefingWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt

- `AiWorkSchedulerImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt

- `LocationBackfillWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `MerchantKeyBackfillWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt

- `BillReminderWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `ReceiptMatchingWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

- `WarrantyExpirationWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- `DataRetentionWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt