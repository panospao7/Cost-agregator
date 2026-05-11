# Pipeline 9 implementation plan — Workers / Background Jobs
Basis: `master-refactor` / HEAD reviewed on **May 11, 2026**

## Goal
Move Pipeline 9 from **“structurally improved but partial”** to **“registry-driven, barrier-safe, observable, and crash-recoverable.”**

## Current foundations to preserve
These are real and should be hardened, not reworked blindly:

- `WorkerExecutionGuard` exists and is used by all 7 default workers.
- `WorkerSpecScheduler` now really uses `WorkerSpec.version`.
- `AiWorkSchedulerImpl.scheduleDailyBriefing()` now delegates to `WorkerSpecScheduler.scheduleAtMidnight(...)`.
- privacy changes actively cancel workers.
- bill reminders use atomic `claimReminderDelivery(...)`.

---

## Main remaining gaps

1. **`BackgroundJobRun` is still shallow**
   - success paths persist zero metrics by default
   - cancellation can leave `RUNNING` rows stranded
   - `getStaleRunningRuns(...)` exists but is not operationalized

2. **Running-worker barrier is not universal**
   - `checkpoint()` currently only checks `writeBarrier`
   - it does not reliably finalize cancellation state
   - `MerchantKeyBackfillWorker` and `WarrantyExpirationWorker` still lack checkpointed loops
   - `BACKUP_EXPORTING` still counts as writes-allowed in maintenance mode

3. **Worker control plane is still partially hardcoded**
   - `AppStartupCoordinator.scheduleStartupWork()` is still a hardcoded list
   - `RestoreMaintenanceMode.scheduleAllWorkers()` is still a hardcoded list with a `TODO: WorkerRegistry`
   - `ReceiptMatchingWorker.runOnce()` still uses `"receipt_matching_run_once"` outside canonical worker names

4. **Worker-specific durability gaps remain**
   - bill reminder stale `CLAIMED` recovery still TODO
   - receipt matching outcomes are not durably rich enough
   - warranty sent-state still lives in `SharedPreferences`

5. **Tests are still too structural**
   - `WorkerContractTest` mostly checks names/counts, not runtime behavior

---

## Recommended PR order

## PR0 — Freeze the worker contract + test skeleton
**Priority:** Critical

Create a short doc under `docs/` defining:
- canonical worker registry
- unique work naming rules
- startup scheduling contract
- maintenance-mode pause/resume contract
- what a durable worker run record must contain
- what “checkpoint-safe” means

Add empty tests:
- `WorkerRegistryContractTest`
- `WorkerExecutionGuardMetricsTest`
- `WorkerCancellationFinalizationTest`
- `WorkerCheckpointBarrierTest`
- `BillReminderClaimRecoveryTest`
- `ReceiptMatchingRunOnceContractTest`
- `WarrantyReminderPersistenceTest`

### Done when
There is one explicit Pipeline 9 runtime contract.

---

## PR1 — Introduce a real `WorkerRegistry`
**Priority:** Critical  
**Files:**
- new `WorkerRegistry.kt`
- `AppStartupCoordinator.kt`
- `RestoreMaintenanceMode.kt`
- worker scheduling helpers

### Changes
Create one registry entry per worker:
- `name`
- `workerClass`
- `scheduleStrategy` (`PERIODIC_SPEC`, `MIDNIGHT_SPEC`, `ONE_SHOT_SPEC`)
- `startupEnabled`
- `pauseWithMaintenance`
- optional `manualTriggerName`

Use it for:
- startup scheduling
- restore resume scheduling
- pause/cancel
- docs/tests

### Important cleanup
- remove hardcoded lists from:
  - `AppStartupCoordinator.scheduleStartupWork()`
  - `RestoreMaintenanceMode.scheduleAllWorkers()`
- keep `ai_daily_briefing` spec-driven through the registry
- rework `ReceiptMatchingWorker.runOnce()` so it is no longer an unregistered special-case unique name

### Recommendation for `runOnce`
Either:
1. register a canonical manual-trigger work name, or
2. tag manual runs and make maintenance-mode cancellation cancel both canonical names and tagged manual runs.

### Done when
All worker scheduling/pause/resume flows come from one registry.

---

## PR2 — Upgrade `WorkerExecutionGuard` into a real execution/run context
**Priority:** Critical  
**Files:**
- `WorkerExecutionGuard.kt`
- `WorkerRunLogger.kt`
- `BackgroundJobRun.kt`
- `BackgroundJobRunDao.kt`

### Problems
- `run.success()` is called with no metrics
- cancellation can strand `RUNNING`
- checkpoint is too weak

### Changes
Replace the current bare block with a small scope/context:
- `checkpoint(operation)`
- `incrementRowsScanned(n)`
- `incrementRowsUpdated(n)`
- `incrementNotificationsSent(n)`
- optional `note(message)`

### Guard behavior changes
1. `checkpoint()` must:
   - verify writes allowed
   - verify current maintenance policy
   - check coroutine cancellation (`ensureActive()` / equivalent)
2. `runGuarded()` must finalize cancellation paths:
   - add `CANCELLED` or `ABORTED_MAINTENANCE` final statuses
   - do not leave `RUNNING` rows behind
3. use `BackgroundJobRunDao.getStaleRunningRuns(...)`
   - either during startup
   - or in a small maintenance reconciler
   - mark stale runs as `ABANDONED`

### Schema suggestion
If helpful, add explicit status enum-like values:
- `RUNNING`
- `SUCCESS`
- `SKIPPED_*`
- `RETRY`
- `FAILED`
- `CANCELLED`
- `ABANDONED`

### Done when
Every worker run ends in a durable final state with meaningful metrics.

---

## PR3 — Make checkpoint/barrier usage universal
**Priority:** Critical  
**Files:**
- `MerchantKeyBackfillWorker.kt`
- `WarrantyExpirationWorker.kt`
- audit other looping workers
- Pipeline 7 maintenance mode files if needed

### Changes
Add checkpoints:
- before each DB write
- between batches
- before notification-send loops
- after expensive fetch phases if long-running

### Explicit targets
- `MerchantKeyBackfillWorker`
- `WarrantyExpirationWorker`
- re-audit:
  - `ReceiptMatchingWorker`
  - `BillReminderWorker`
  - `LocationBackfillWorker`
  - `DataRetentionWorker`

### Cross-pipeline dependency
Pipeline 9 closure depends on Pipeline 7/U1 semantics:
- if `BACKUP_EXPORTING` should block active workers, `checkpoint()` must see that
- if Pipeline 7 introduces finer-grained backup modes, adopt them here immediately

### Done when
No long-running worker can continue mutating indefinitely after cancellation/maintenance changes.

---

## PR4 — Harden bill reminder delivery recovery
**Priority:** High  
**Files:**
- recurring reminder entity/DAO
- `RecurringLifecycleCoordinator.kt`
- `BillReminderWorker.kt`

### Problems
- concurrent duplicate send is improved
- crash after claim can still strand a reminder in `CLAIMED`

### Changes
Add durable claim metadata:
- `claimedAt`
- `claimRunId`
- `attemptCount`
- `lastError`

Add recovery:
- stale `CLAIMED` older than threshold -> reset to `SCHEDULED` or `FAILED_TRANSIENT`
- do this at worker start or via coordinator method

Add run metrics:
- reminders considered
- claimed
- sent
- failed
- requeued stale claims

### Done when
A worker/process crash cannot permanently lose a reminder delivery.

---

## PR5 — Make receipt matching durable and registry-safe
**Priority:** High  
**Files:**
- `ReceiptMatchingWorker.kt`
- maybe `PipelineDiagnosticEventDao` / matching diagnostics
- worker registry files

### Problems
- worker execution is durable, match outcomes are not
- manual `runOnce()` still sits outside canonical pause/resume semantics

### Changes
1. Add explicit run metrics:
   - receipts scanned
   - auto-matched
   - suggested
   - skipped by document type
   - link failures
2. Emit durable diagnostics/events for:
   - auto-match
   - suggestion persisted
   - link failure
   - no candidate
3. Fold `runOnce()` into the registry contract:
   - canonical name or cancellable tag
   - maintenance mode must cancel/suppress it too

### Done when
Receipt-matching runs are observable, and manual runs no longer bypass worker governance.

---

## PR6 — Move warranty sent-state into Room-backed state
**Priority:** High  
**Files:**
- `WarrantyExpirationWorker.kt`
- warranty entity/DAO/repo or new reminder-state table

### Problem
Per-window notification sent-state still lives in `SharedPreferences`, outside backup/restore/audit.

### Recommended design
Create durable reminder state:
- `warrantyId`
- `windowDays`
- `lastSentAt`
- optional `lastRunId`

Could be:
- columns on a warranty-related table, or
- a dedicated `warranty_reminder_state` table

### Benefits
- backup/restore safe
- queryable
- can participate in DB transactions
- can emit worker metrics/events

### Done when
Warranty notification idempotency no longer depends on `SharedPreferences`.

---

## PR7 — Final contract suite + docs sync
**Priority:** Required for closure

### Tests to add
- `WorkerRegistryContractTest`
- `WorkerExecutionGuardMetricsTest`
- `WorkerCancellationFinalizationTest`
- `WorkerCheckpointBarrierTest`
- `BillReminderClaimRecoveryTest`
- `ReceiptMatchingRunOnceContractTest`
- `ReceiptMatchingWorkerMetricsTest`
- `WarrantyReminderPersistenceTest`
- `WorkerPrivacyCancellationIntegrationTest`

### Minimum scenarios
1. cancelled worker ends as `CANCELLED`, not `RUNNING`
2. stale `RUNNING` rows get reconciled
3. startup/restore resume both use the same registry
4. maintenance-mode flip mid-run stops checkpointed workers
5. reminder claim crash recovers
6. receipt matching manual run is cancellable via maintenance mode
7. warranty reminder state survives app restart/backup

### Docs cleanup
After tests pass:
- update Pipeline 9 tracker statuses
- explicitly note that `ai_daily_briefing` is now spec-driven
- remove stale comments implying only structural worker coverage is sufficient

---

## Closure criteria
I would only call Pipeline 9 clean/stable when all of these are true:

- one `WorkerRegistry` drives startup, resume, and cancellation
- no worker run can remain indefinitely `RUNNING`
- success runs record meaningful metrics
- all long-running loops use checkpoints
- maintenance/backup mode can stop active workers predictably
- bill reminder stale claims recover
- receipt matching outcomes are durably observable
- warranty reminder state is DB-backed
- runtime tests prove the contract

## Sources
- Tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
- `WorkerRunLogger.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt
- `BackgroundJobRunDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt
- `WorkerSpec.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt
- `WorkerSpecScheduler.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt
- `AppStartupCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt
- `RestoreMaintenanceMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
- `ReceiptMatchingWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
- `BillReminderWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt
- `WarrantyExpirationWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt
- `MerchantKeyBackfillWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt
- `WorkerContractTest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/test/java/com/yourname/expensetracker/workers/WorkerContractTest.kt