Created the **Pipeline 9 master prompt pack** using the same directive.

<pipeline9-master-prompts.md>
# Pipeline 9 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P9 — Workers / Background Jobs**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P9 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_9_CONSOLIDATED_ISSUES.md
- P9 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_9_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Worker guard: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
- Worker registry: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt
- Worker specs: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt
- Worker scheduler: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt
- Startup coordinator: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

Important context:
- P9 is **Workers / Background Jobs**.
- Core architecture segment: **Segment 12 — Startup & Background Runtime**.
- Cross-cutting segments:
  - Segment 3 — Notification Capture
  - Segment 4 — Receipt Scanning / OCR
  - Segment 7 — Recurring Expenses
  - Segment 11 — Notifications & Alerts
  - Segment 18 — Export & Backup
  - Segment 19 — Location Enrichment
  - Segment 20 — AI Platform
  - Segment 28 — Security / Privacy
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
  - Segment 34 — Warranty / Subscription / Offers
  - Segment 36 — Bill Reminders
  - Segment 38 — Receipt Matching
- The P9 consolidated issue doc says **23 fixed, 1 partial, 0 open**, with `NEW-P9-008` partial because `NotificationIntakeWorker` uses `executionGuard.checkpoint()` but not full `runGuarded` / `runGuardedWithContext`.
- The older P9 implementation plan says **YELLOW** and lists many issues as open. This is stale relative to the consolidated issue doc and code comments. Therefore: **validate every tracker claim against code at the target SHA.**
- Code is source of truth. Docs are architecture expectations and issue-history context.

---

## Prompt A — P9 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin WorkManager, Room, coroutine, backup/restore, privacy-gating, diagnostics, and background-runtime architecture reviewer.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P9 — Workers / Background Jobs

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 9 end-to-end:

### Worker infrastructure scope
- `WorkerExecutionGuard`,
- `WorkerGuardRequest`,
- `WorkerGuardResult`,
- `toWorkerResult`,
- `WorkerRunContext`,
- `WorkerRunLogger`,
- `BackgroundJobRun`,
- `BackgroundJobRunDao`,
- `WorkerSpec`,
- `WorkerSpecScheduler`,
- `WorkerRegistry`,
- `WorkerLeaseRegistry`,
- `WorkerLeaseRegistryImpl`,
- `WorkerDrainController`,
- `RetryableWorkerException`,
- `NotificationPermissionChecker`,
- WorkManager enqueue/cancel/update policy,
- startup worker scheduling,
- post-restore worker scheduling,
- stale RUNNING job recovery,
- worker versioning and constraint changes.

### Runtime contracts
- restore/write/read barrier enforcement,
- maintenance-mode worker blocking,
- worker drain before backup/restore,
- checkpoint behavior in long-running loops,
- worker lease acquisition/release,
- cancellation propagation,
- timeout classification,
- retry/failure/skip classification,
- run logging terminal state exactly once,
- worker counters,
- privacy capability gating,
- notification permission gating,
- disabled spec handling,
- schedule/reschedule chains.

### Registered P9 workers
Audit all registered workers from `WorkerRegistry.entries` and `WorkerSpec.DEFAULTS`:
- `data_retention` — `DataRetentionWorker`
- `location_backfill` — `LocationBackfillWorker`
- `merchant_key_backfill` — `MerchantKeyBackfillWorker`
- `bill_reminder_periodic` — `BillReminderWorker`
- `receipt_matching` — `ReceiptMatchingWorker`
- `ai_daily_briefing` — `DailyBriefingWorker`
- `warranty_expiration_check` — `WarrantyExpirationWorker`

Also audit bespoke/non-registry worker paths:
- `NotificationIntakeWorker`
- any one-shot/manual worker helpers such as `ReceiptMatchingWorker.runOnce()`
- any companion `schedule()` or `cancel()` methods in workers.

### Cross-pipeline dependencies
- P1/P3 notification capture depends on `NotificationIntakeWorker`.
- P4 recurring/bill reminders depends on `BillReminderWorker`.
- P7 backup/restore depends on worker drain, guard, and registry symmetry.
- P8 privacy depends on `DataRetentionWorker`, privacy-gated workers, and runtime cancellation/rescheduling.
- P19/location depends on `LocationBackfillWorker`.
- P20/AI depends on `DailyBriefingWorker`.
- P34/warranty depends on `WarrantyExpirationWorker`.
- P38 receipt matching depends on `ReceiptMatchingWorker`.
- Every background write must respect restore/write barriers.

Read first:
- `docs/analyses and debug master/PIPELINE_9_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_9_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs, especially worker guard / restore barrier / TimeProvider docs.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that method:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

Important tracker caveat:
- `PIPELINE_9_CONSOLIDATED_ISSUES.md` says P9 is complete except one partial item.
- `PIPELINE_9_IMPLEMENTATION_PLAN.md` appears older/stale and still lists issues as open.
- Do not trust either blindly. Validate each claim against code and tests at the target SHA.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI pipeline:
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P9 specifically, pay special attention to:
- Segment 12 — Startup & Background Runtime.
- Segment 18 — Export & Backup.
- Segment 28 — Security / Privacy.
- Segment 29 — Debug & Diagnostics.
- Segment 30 — Dependency Injection.
- `backup-restore-barrier-contract.md`.
- worker registry / guard references in architecture docs.
- DB write ownership for workers and worker run logging.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, WorkManager schedule paths, and tests to build the real inventory.

### Worker infrastructure
Review:
- `app/src/main/java/com/yourname/expensetracker/domain/workers/NotificationPermissionChecker.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/NoOpWorkerDrainController.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/RetryableWorkerException.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerDrainController.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLease.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunContext.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt`

### Registered workers
Review:
- `app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt`

### Bespoke/non-registry worker paths
Review:
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`
- any worker discovered by:
  - `rg -n "class .*Worker|CoroutineWorker|ListenableWorker|OneTimeWorkRequestBuilder|PeriodicWorkRequestBuilder|enqueueUniqueWork|enqueueUniquePeriodicWork"`

If a worker is not in `WorkerRegistry` or `WorkerSpec.DEFAULTS`, classify why:
- intentionally bespoke,
- missing registry/spec,
- one-shot child worker,
- obsolete/dead code,
- bug.

### Startup / app lifecycle / restore resume
Review:
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupDelegate.kt`
- `app/src/main/java/com/yourname/expensetracker/startup/AppBackgroundLifecycleObserver.kt`
- `app/src/main/java/com/yourname/expensetracker/MainApplication.kt`
- restore/maintenance classes that pause/resume workers:
  - `RestoreMaintenanceMode.kt`
  - `MaintenanceOperationRunner.kt`
  - `DatabaseWriteBarrier.kt`
  - `DatabaseReadBarrier.kt`
  - `DatabaseReadBarrierFlowExt.kt`
  - `RestoreInternalWriteScope.kt`

### Privacy / runtime-worker policy
Review:
- privacy settings repository implementation,
- runtime-worker cancellation/reschedule policy,
- `PrivacyGate`,
- `PrivacyCapability`,
- `PrivacyDecision`,
- `PrivacyRuntimeWorkerPolicy` or equivalent if present,
- `AiWorkScheduler` / proactive briefing scheduling,
- any code calling `cancelUniqueWork` or rescheduling after settings changes.

Search:
- `rg -n "PrivacyRuntimeWorkerPolicy|cancelUniqueWork|scheduleDailyBriefing|syncProactiveBriefing|PrivacySettingsRepositoryImpl|WorkerRegistry"`

### Notification permission
Review:
- `AndroidNotificationPermissionChecker`
- `NotificationPermissionChecker`
- `NotificationService`
- workers requiring notification permission:
  - bill reminders if applicable,
  - warranty expiration,
  - daily briefing notification delivery,
  - receipt matching alerts.

Search:
- `rg -n "requiresNotificationPermission|NotificationPermissionChecker|areNotificationsEnabled|sendBudgetAlert|NotificationManagerCompat"`

### DAOs / entities / schema
Review:
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/BackgroundJobRun.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt`
- `WarrantyReminderDelivery.kt`
- `WarrantyReminderDeliveryDao.kt`
- `RecurringReminderDelivery.kt`
- `RecurringReminderDeliveryDao.kt`
- `NotificationIntakeEntity.kt`
- `NotificationIntakeDao.kt`
- `ScannedReceipt.kt`
- `ScannedReceiptDao.kt`
- `RawNotification.kt`
- `RawNotificationDao.kt`
- `PipelineDiagnosticEvent.kt`
- `PipelineDiagnosticEventDao.kt`
- `PrivacyAuditEvent.kt`
- `PrivacyAuditDao.kt`
- `AppDatabase.kt`
- `DatabaseMigrations.kt`
- exported Room schema JSON if present.

### Hilt modules
Review all modules that provide:
- worker lease/drain/guard,
- notification permission checker,
- WorkManager,
- worker dependencies,
- DAOs/database,
- TimeProvider,
- privacy gate/settings,
- diagnostics/event sanitizer,
- dispatchers,
- backup/restore barriers.

Likely seeds:
- `app/src/main/java/com/yourname/expensetracker/di/WorkerModule.kt`
- `DatabaseModule.kt`
- `DaoModule.kt`
- `DiagnosticsModule.kt`
- `DispatchersModule.kt`
- `TimeModule.kt`
- `PrivacyModule.kt`
- `NotificationModule.kt`
- `LocationModule.kt`
- `AiModule.kt`
- `BackupRepositoryModule.kt`

### Tests
Search the whole repo:
- `rg -n "Worker|WorkManager|WorkerExecutionGuard|WorkerRun|WorkerSpec|WorkerRegistry|WorkerLease|BackgroundJobRun|DataRetention|BillReminder|DailyBriefing|LocationBackfill|MerchantKeyBackfill|ReceiptMatching|WarrantyExpiration|NotificationIntake|Scheduler|Guard|Drain|Maintenance" app/src/test app/src/androidTest`

Known likely test themes:
- guard timeout classification,
- cancellation propagation,
- run logger idempotency,
- worker counters,
- scheduler versioning,
- scheduleAtMidnight delay floor,
- registry/spec parity,
- worker drain,
- restore barrier blocking,
- privacy toggle cancellation/rescheduling,
- bill reminder exact-once,
- warranty durable sent-state,
- receipt matching overlap,
- stale RUNNING job recovery,
- notification intake partial/bespoke behavior.

Do not stop at known names. Search the entire repo.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Verify WorkManager unique names, policies, constraints, backoff, version handling.
- Check every worker’s `doWork()` implementation, not only the scheduler.
- Check whether public helper methods like `runOnce()` bypass registry/guard assumptions.
- Check whether tests assert the invariant, not merely instantiate classes.
- If tracker says fixed/open/TODO, validate against code at this SHA.
- Treat background writes during restore as high-risk.
- Treat misleading `SUCCESS` for stopped/incomplete work as a data-integrity / diagnostics risk.
- Treat unlogged worker runs as observability gaps unless the worker is intentionally bespoke and documented.
- Treat swallowed `CancellationException` as a bug unless narrowly impossible and tested.

Use searches like:
- `rg -n "class .*Worker|CoroutineWorker|ListenableWorker"`
- `rg -n "WorkerExecutionGuard|runGuarded|runGuardedWithContext|WorkerGuardRequest|checkpoint"`
- `rg -n "WorkerSpec.DEFAULTS|WorkerRegistry|scheduleFromSpec|scheduleAtMidnight|ExistingPeriodicWorkPolicy|ExistingWorkPolicy"`
- `rg -n "BackgroundJobRun|WorkerRunLogger|rowsScanned|rowsUpdated|notificationsSent|STALE_ABORTED"`
- `rg -n "WorkerLease|WorkerDrain|requestStopAndAwaitDrain|awaitNoActiveWorkers|resetStopFlag"`
- `rg -n "isStopped|Result.success\\(\\)|Result.retry\\(\\)|RetryableWorkerException"`
- `rg -n "TimeoutCancellationException|CancellationException|catch \\(e: Exception\\)|catch \\(t: Throwable\\)"`
- `rg -n "System.currentTimeMillis\\(|TimeProvider|Instant.now|LocalDate.now"`
- `rg -n "cancelUniqueWork|enqueueUniqueWork|enqueueUniquePeriodicWork|WorkManager.getInstance"`
- `rg -n "requiresNotificationPermission|PrivacyCapability|PrivacyGate|writesAllowed|writeBarrier|readBarrier"`
- `rg -n "insert\\(|update\\(|delete\\(" app/src/main/java/com/yourname/expensetracker`

## 6. Universal contracts to verify

Audit these for P9:

1. Restore/write barrier:
   - every worker that writes DB or files is blocked during restore/backup maintenance,
   - guard checks write barrier before run logging and before worker mutations,
   - long-running loops checkpoint between batches/items,
   - bespoke workers either use full guard or equivalent barrier/checkpoint semantics,
   - backup/export read-only allowance is explicit and safe,
   - worker drain timeout aborts destructive operations.

2. Worker guard and worker run logging:
   - every registered worker uses `WorkerExecutionGuard`,
   - every guarded run records `RUNNING` then exactly one terminal status,
   - skip/retry/failure/cancel reasons are typed enough,
   - counters are non-zero where work happens,
   - worker run handle is idempotent,
   - stale `RUNNING` rows are recovered on startup,
   - read-only guard path is exception-safe.

3. Privacy/redaction/raw-storage policy:
   - privacy-gated workers declare required capabilities,
   - privacy denied/fail-closed maps to safe skip,
   - retention worker can still run where needed for cleanup,
   - error messages stored in `BackgroundJobRun` are sanitized,
   - logs/diagnostics do not persist raw PII.

4. Money/currency normalization:
   - P9 does not own money math,
   - but workers touching reminders, dashboard briefings, forecast/cashflow, receipts, or warranties must not create inconsistent financial writes,
   - background-generated expenses or links must go through legal lifecycle paths.

5. Transaction lifecycle ownership:
   - notification-created expenses must go through notification/transaction lifecycle,
   - worker backfills must not mutate core expenses illegally,
   - merchant-key/location backfills should use repository methods with barrier semantics.

6. Receipt lifecycle/link ownership:
   - receipt matching worker must link receipts through `ReceiptLinkService`,
   - auto-match overlap must be atomic,
   - outcomes must be durable.

7. Recurring planned/actual reconciliation:
   - bill reminder worker must use recurring lifecycle coordinator,
   - reminder delivery claim/send/mark transitions must be atomic and idempotent,
   - paid/cancelled occurrences must not still notify.

8. Diagnostics/drop reasons/events:
   - worker skips/failures are durable enough,
   - receipt matching skip/no-match/failure paths write events where required,
   - diagnostic failures do not abort core work except cancellation,
   - correlation IDs are preserved if available.

9. Import/export schema/roundtrip:
   - worker-run ledger, warranty reminder delivery, recurring reminder delivery, notification intake, privacy audit, and diagnostic tables are preserved or intentionally classified,
   - backup/restore resumes workers symmetrically after restore,
   - restore does not leave stale scheduled workers using old DB handles.

10. DAO conflict handling and timestamps:
   - `IGNORE` insert results are checked when necessary,
   - claim-before-notify rows use conditional status transitions,
   - `updatedAt`/`finishedAt` timestamps use `TimeProvider` where appropriate,
   - worker version prefs and enqueue state do not drift after crash.

## 7. P9-specific invariants to audit

### Guard / run lifecycle
Check:
- `WorkerExecutionGuard` acquires and releases lease in `finally`.
- It blocks when `RestoreMaintenanceMode` is not NORMAL unless explicitly safe read-only.
- It checks `DatabaseWriteBarrier` before `WorkerRunLogger.start()`.
- It classifies `TimeoutCancellationException` as retryable timeout, not ordinary cancellation.
- It rethrows `CancellationException`.
- It distinguishes skipped, retry, failed, cancelled.
- It handles privacy-denied and fail-closed.
- It handles notification permission.
- It logs terminal state in `NonCancellable`.
- It never double-completes run handle.
- It sanitizes persisted error messages.

### WorkerRunContext / counters
Check:
- counters are thread-safe,
- every worker increments rows scanned/updated/skipped/errors/notifications as appropriate,
- no-work runs are explicitly distinguishable,
- tests cover concurrent counter increments.

### WorkerSpec / scheduler
Check:
- `WorkerSpec.DEFAULTS` and `WorkerRegistry.entries` are in parity.
- disabled specs cancel existing unique work.
- version bump forces safe re-enqueue/update.
- version is written after successful enqueue.
- periodic policy avoids deprecated `REPLACE` unless intentionally replaced by `UPDATE`.
- one-shot policy is explicit.
- `scheduleAtMidnight` has a near-zero delay floor.
- constraints match worker cost/risk:
  - AI daily briefing: unmetered + battery/charging if intended.
  - location backfill: unmetered.
  - merchant backfill: battery-not-low.
  - data retention: no network.
  - bill reminders/warranty: notification permission where applicable.

### WorkerRegistry / startup / restore
Check:
- startup schedules workers via `WorkerRegistry.scheduleAll`.
- restore exit schedules same set.
- maintenance pause uses same keys.
- no worker is scheduled by hardcoded asymmetric list.
- startup skips worker scheduling during maintenance.
- stale RUNNING rows are marked `STALE_ABORTED`.

### Worker drain / leases
Check:
- active leases are tracked correctly.
- multiple concurrent leases with the same worker name are not lost if possible.
- stop flag is reset after maintenance.
- checkpoints observe stop requests.
- long-running workers checkpoint inside loops.
- drain timeout fails closed.

### DataRetentionWorker
Check:
- uses guard/context,
- deterministic target order,
- per-target checkpoint/resume,
- failures are not permanently skipped,
- partial failures are visible,
- cancellation rethrows,
- pagination/bounds,
- audit events sanitized,
- rows purged counted.

### LocationBackfillWorker
Check:
- privacy capability set,
- location resolver errors classify retry vs permanent,
- user-set location is not overwritten,
- `isStopped` returns retry via `RetryableWorkerException`,
- checkpoint before writes,
- no raw PII in logs; merchant anonymized.

### MerchantKeyBackfillWorker
Check:
- bounded batches,
- no infinite loop,
- `isStopped` retry,
- checkpoint before updates,
- battery constraint,
- repository update is safe,
- failures do not stall all progress.

### BillReminderWorker
Check:
- settings and quiet-hours checks are inside guard,
- due reminder claim-before-notify,
- revalidate after claim,
- notification permission handling,
- sent/failed status transition from CLAIMED only,
- stable notification IDs and PendingIntent codes,
- cancellation rethrows,
- rows/notifications counted.

### ReceiptMatchingWorker
Check:
- periodic worker is guarded,
- manual `runOnce()` one-shot does not create unsafe overlap,
- per-receipt atomic claim prevents duplicate linking,
- outcomes are durable,
- diagnostic failures do not abort matching,
- cancellation rethrows,
- notifications counted.

### DailyBriefingWorker
Check:
- privacy capability set,
- timeout bounded,
- timeout classified retry,
- existing artifact/fresh skip does not break one-shot chain,
- reschedule failure logged,
- no infinite reschedule loop,
- notification count meaningful,
- cloud AI policy is enforced by downstream AI use case.

### WarrantyExpirationWorker
Check:
- uses `runGuardedWithContext`,
- uses `TimeProvider`, not `System.currentTimeMillis()`,
- notification permission gate,
- durable `WarrantyReminderDelivery`,
- seed/claim/send/mark protocol,
- stale CLAIMED recovery,
- exactly-once notification behavior,
- backup/restore-safe sent state.

### NotificationIntakeWorker
Check:
- validate `NEW-P9-008`:
  - it has checkpoint/barrier behavior,
  - but does not use full `runGuarded` / `runGuardedWithContext`,
  - it may not write `BackgroundJobRun`,
  - it may not be in `WorkerRegistry`.
- Decide whether this is acceptable bespoke design or a bug:
  - Does each intake row already provide its own durable status ledger?
  - Does WorkManager unique work scheduling handle one intake row safely?
  - Are write barrier, cancellation, retry, max attempts, payload purge, and diagnostics adequate?
  - Would wrapping in full guard double-log or distort per-intake semantics?
- If accepted, require documentation/tests.
- If not accepted, propose minimal integration.

## 8. Known P9 issue set to validate

Read P9 consolidated issue doc and implementation plan, then validate each against code.

Old issues:
- `P9-P1-01`: `BackgroundJobRun` table unused by workers.
- `P9-P1-02`: no shared `WorkerExecutionGuard`.
- `P9-P1-03`: restore/backup cancellation not a running-worker barrier.
- `P9-P1-04`: daily briefing one-shot chain breaks on early exits.
- `P9-P1-05`: bill reminder worker disabled by static `WorkerSpec`.
- `P9-P1-06`: bill reminders not exactly-once safe.
- `P9-P1-07`: `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling.
- `P9-P1-08`: receipt matching outcomes not durable.
- `P9-P1-09`: warranty notification sent-state outside DB.
- `P9-P1-10`: worker pause/resume registry hardcoded/asymmetric.
- `P9-P1-11`: privacy changes do not actively cancel workers.
- `P9-NEW-03`: `BackgroundJobRun` rows recorded zero counts.

New issues:
- `NEW-P9-001`: `TimeoutCancellationException` misclassified as system cancellation.
- `NEW-P9-002`: `BillReminderWorker` bypasses guard for settings/quiet-hours.
- `NEW-P9-003`: `WorkerRunContext` counters not thread-safe.
- `NEW-P9-004`: `WarrantyExpirationWorker` uses `runGuarded` instead of `runGuardedWithContext`.
- `NEW-P9-005`: `WarrantyExpirationWorker` uses `System.currentTimeMillis`.
- `NEW-P9-006`: `WorkerSpecScheduler` uses deprecated `REPLACE`.
- `NEW-P9-007`: SharedPreferences version write not atomic with enqueue.
- `NEW-P9-008`: `NotificationIntakeWorker` not in full guard/registry.
- `NEW-P9-009`: `LocationBackfillWorker` `isStopped` exits as `SUCCESS`.
- `NEW-P9-010`: `MerchantKeyBackfillWorker` same `isStopped` issue.
- `NEW-P9-011`: `scheduleAtMidnight` near-zero delay edge case.
- `NEW-P9-012`: `DailyBriefingWorker` reschedule failure silently swallowed.
- `NEW-P9-013`: `WorkerExecutionGuard` read-only path lacks exception handling.
- `NEW-P9-014`: no battery constraint for `merchant_key_backfill`.
- `NEW-P9-015`: `WorkerRunLogger.Handle` not idempotent.

Important:
- If code is fixed but tracker says open, report tracker drift.
- If docs say fixed but code does not prove it, report bug/partial.
- If `NEW-P9-008` remains partial by design, state the exact risk and required evidence/tests.

## 9. Review dimensions

Check:
- correctness,
- data integrity,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- restore/export safety,
- privacy fail-closed behavior,
- raw PII storage/logging,
- cancellation handling,
- coroutine races,
- WorkManager retry/idempotency,
- dedupe/conflict behavior,
- state-machine transitions,
- timestamp/currency defaults,
- schema/migration compatibility,
- Hilt binding correctness,
- UI state consistency if relevant,
- diagnostics coverage,
- test coverage,
- performance risks,
- security/privacy risks,
- battery/network constraint appropriateness.

## 10. Required output format

Produce this exact structure:

# Pipeline 9 Review — Workers / Background Jobs

## 1. Pipeline summary
- What P9 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- worker infrastructure,
- registered workers,
- bespoke workers,
- services/coordinators touched by workers,
- repositories,
- DAOs,
- Room entities,
- WorkManager scheduler/spec/registry,
- Hilt modules,
- startup/restore integration,
- privacy runtime policy,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow Segment 12 ownership?
- Does code follow backup/restore worker barrier contracts?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- startup scheduling,
- worker guard lifecycle,
- run logging lifecycle,
- restore/maintenance drain,
- privacy-toggle cancellation/reschedule,
- each registered worker’s doWork path,
- NotificationIntakeWorker bespoke path,
- post-restore scheduling,
- stale RUNNING recovery.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore barrier,
- worker guard/run logging,
- privacy/redaction,
- lifecycle ownership,
- money/currency if relevant,
- diagnostics/events,
- import/export/backup,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P9 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all old and new P9 issues from `PIPELINE_9_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- worker registry/spec parity table,
- legal worker write path table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P9 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

For P9:
- Worker writes during restore/DB swap are P0/P1.
- Duplicate notification/payment/reminder side effects are P0/P1 depending money impact.
- Swallowed cancellation in long-running worker is P1/P2.
- Missing worker run logging is P2 unless it hides critical corruption or privacy exposure.
- Scheduler/docs drift is P3 unless it causes a broken critical flow.

## 12. Completion criteria

The review is not complete until:
- P9 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- every Worker subclass was classified,
- registered workers were compared to `WorkerSpec.DEFAULTS`,
- startup/restore/privacy scheduling paths were traced,
- guard/run-log behavior was checked,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P9 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in WorkManager, coroutine cancellation, Room durability, backup/restore safety, privacy-gated background work, and test-driven fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P9 — Workers / Background Jobs

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P9 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_9_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_9_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- DB/restore docs if touching backup/restore/worker drain.
- Privacy/diagnostics docs if touching worker privacy gates or persisted errors.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually

:warning: The provider stream ended early, so this response may be incomplete.