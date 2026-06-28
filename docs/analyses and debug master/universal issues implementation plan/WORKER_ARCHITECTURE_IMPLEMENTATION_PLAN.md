# Worker Architecture Implementation Plan

Last updated: 2026-06-28  
Scope: MIT-016, MIT-017, MIT-035, MIT-065, MIT-070, MIT-082  
Goal: every DB-writing/background worker has a full guard, unique lease, durable run ledger, safe diagnostics, and restore-aware execution.

> **Status: PRs 1–5 COMPLETE** (commit `3372b917`). PRs 6–11 remain open.

---

## 1. Objective

Make background execution safe during normal operation, restore/reset/import maintenance, app shutdown, cancellation, and retries.

After this plan, no worker should be able to:

- write to DB without a lifecycle guard,
- start during restore/reset/import and touch stale DB,
- be missed by restore drain because of lease-key collision,
- finish without durable terminal run state,
- swallow cancellation as success/failure,
- lose diagnostics during shutdown,
- silently fail schedule/reschedule,
- survive incompatible one-shot version changes,
- bypass notification permission/privacy/write barriers,
- exist outside the worker registry without explicit justification.

---

## 2. Master Issues Covered

| MIT | Issue | Covered Here |
|---|---|---|
| MIT-016 | Fix worker lease registry and enforce full worker guard | Yes |
| MIT-017 | Fix one-shot worker version bump and terminal run logging | Yes |
| MIT-035 | Add durable operation run ledgers/checkpoints | Yes, for workers |
| MIT-065 | Durable terminal diagnostics cannot be cancellable | Yes |
| MIT-070 | Worker scheduling diagnostics | Yes |
| MIT-082 | Worker registry/spec parity and time-provider cleanup | Yes |

Related but not owned:

| MIT | Relationship |
|---|---|
| MIT-013 | Restore/DI lifetime defines restart-required state |
| MIT-014 | Maintenance owner tokens used by worker drain |
| MIT-030 | Read/write barriers must be called by worker guard |
| MIT-034 | Cancellation guard must include workers |
| MIT-042 | Bill reminder notification permission handling depends on worker guard |
| MIT-001/003 | CI/static guards enforce this architecture |

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P1 | Notification intake worker, repair/recovery diagnostics |
| P4 | Bill reminder worker, snooze/dismiss receiver behavior |
| P7 | Restore must drain active workers safely |
| P8 | Retention/privacy workers and cancellation behavior |
| P9 | Core worker/background-job architecture |
| P15 | DI/app-scope/worker lifetime under restore |
| P17 | Worker guard/static CI enforcement |

---

## 4. Current Problem Summary

Known worker risks from the tracker:

- `WorkerLeaseRegistryImpl` tracks active leases by worker name, so concurrent same-name workers can overwrite each other.
- `NotificationIntakeWorker` bypasses full guard: no lease, no `BackgroundJobRun`, and may read before barrier.
- One-shot worker version bump uses `KEEP`, so stale work may survive incompatible changes.
- `WorkerRunLogger.Handle` terminal writes are not atomic under race.
- Data retention partial failures can soft-success.
- Daily briefing reschedule failure can kill the chain until startup.
- Bill reminder permission handling is not enforced through worker guard.
- Worker scheduling failures can be swallowed per entry.
- Terminal/pre-launch diagnostics can be lost if launched in cancellable service/worker scope.
- Worker comments/specs drift from implementation.
- Some worker IDs/timestamps use direct system time.
- Worker subclass/registry inventory is incomplete.

---

## 5. Architecture Decision

### Decision

Introduce a standardized worker execution contract:

> Every DB-writing or externally side-effecting worker must execute through `WorkerExecutionGuard.execute(...)`.

The guard owns:

1. maintenance/restart-required checks,
2. worker lease acquisition,
3. durable run ledger creation,
4. read/write barrier preflight,
5. permission/capability checks,
6. cancellation-safe execution,
7. terminal state compare-and-set,
8. durable sanitized diagnostics,
9. retry/failure/success mapping.

### Rejected alternative: worker-by-worker ad hoc fixes

Rejected because it keeps restore drain, diagnostics, and lifecycle behavior inconsistent.

### Rejected alternative: only rely on WorkManager

Rejected because WorkManager does not know your restore/DB lifetime, privacy, permission, operation ledger, or app-specific maintenance rules.

---

## 6. Non-Negotiable Invariants

After this plan:

- [x] Every DB-writing worker uses `WorkerExecutionGuard`. (PR 5)
- [x] Every guarded worker has a unique lease ID. (PR 2)
- [x] Lease registry supports concurrent same-name workers. (PR 2)
- [x] Restore drain can see every active worker. (PR 2)
- [x] Worker starts check maintenance/restart-required before first DAO read/write. (PR 4 + PR 5)
- [x] Every worker run has a durable `BackgroundJobRun` or equivalent run ledger row. (PR 3 + PR 4 + PR 5)
- [x] Terminal worker state is written exactly once via compare-and-set. (PR 3)
- [x] Cancellation is rethrown and not converted to success/failure incorrectly. (PR 4)
- [ ] Terminal diagnostics are durable and not lost due to cancellable scope. (PR 8)
- [ ] Schedule/reschedule failures are visible and sanitized. (PR 7)
- [x] One-shot incompatible version changes replace stale work. (PR 1)
- [ ] New `CoroutineWorker` subclasses fail CI unless guarded or explicitly allowlisted. (PR 10)
- [x] Worker registry/spec comments match implementation. (PR 1)

---

# 7. Target Architecture

---

## 7.1 Core Components

### `WorkerExecutionGuard`

Single entrypoint for guarded worker execution.

Responsibilities:

- create/attach worker run ledger,
- acquire unique lease,
- check maintenance/restart-required state,
- check read/write barriers,
- check required permissions/capabilities,
- execute worker body,
- map result to `Result.success/retry/failure`,
- write terminal state atomically,
- emit sanitized diagnostics.

Suggested shape:

```kotlin
suspend fun <T> execute(
    workerName: String,
    workId: UUID,
    specVersion: Int,
    requirements: WorkerRequirements,
    block: suspend WorkerExecutionContext.() -> WorkerOutcome
): Result
```

---

### `WorkerRequirements`

Declarative worker requirements.

Fields:

- `requiresDatabaseRead`
- `requiresDatabaseWrite`
- `requiresNetwork`
- `requiresNotificationPermission`
- `requiresCloudAllowed`
- `requiresPrivacyCapability`
- `blocksDuringMaintenance`
- `allowDuringMaintenanceWithOwner`
- `requiresForeground`
- `retryOnMaintenance`
- `retryOnPermissionDenied`
- `maxAttemptsPolicy`

---

### `WorkerExecutionContext`

Context passed into worker body.

Contains:

- `runId`,
- `leaseId`,
- `workerName`,
- `workId`,
- `attempt`,
- `TimeProvider`,
- sanitized diagnostic writer,
- operation/run logger handle,
- cancellation-safe helpers,
- access-gate result.

---

### `WorkerLeaseRegistry`

Tracks active workers by unique lease ID, not only worker name.

Recommended model:

```text
leaseId -> LeaseRecord
workerName -> Set<leaseId>
workId -> leaseId
```

Lease record:

- lease ID,
- worker name,
- WorkManager work ID,
- run ID,
- start timestamp,
- heartbeat timestamp,
- owner/thread info if safe,
- requirements,
- state.

---

### `BackgroundJobRunLedger`

Durable run ledger.

Fields:

- run ID,
- worker name,
- work ID,
- spec version,
- attempt number,
- start time,
- terminal time,
- state,
- result,
- sanitized reason code,
- retry reason,
- maintenance/restart-blocked reason,
- linked operation ID if any.

Terminal states:

```text
STARTED
BLOCKED_MAINTENANCE
BLOCKED_RESTART_REQUIRED
BLOCKED_PERMISSION
RUNNING
SUCCEEDED
RETRY_SCHEDULED
FAILED
CANCELLED
PARTIAL_FAILURE
STALE_RECOVERED
```

---

### `WorkerRunLogger.Handle`

Must be single-terminal-write.

Use:

- compare-and-set,
- transaction,
- unique terminal marker,
- or DB constraint preventing multiple terminal rows.

Invariant:

> Exactly one terminal state per `runId`.

---

### `WorkerRegistry`

Single registry of scheduled background jobs.

Responsibilities:

- define worker specs,
- schedule all,
- reschedule changed specs,
- expose enabled/disabled state,
- emit per-entry scheduling diagnostics,
- verify registry/spec parity.

---

### `WorkerScheduleDiagnostics`

Records scheduling problems.

Must capture:

- worker name,
- schedule phase,
- sanitized failure code,
- spec version,
- unique work name,
- policy used,
- whether retry/startup recovery will occur.

No raw exception messages unless sanitized.

---

## 7.2 Worker Execution Flow

Required guarded flow:

```text
doWork()
  -> WorkerExecutionGuard.execute()
     -> create run ledger STARTED
     -> acquire unique lease
     -> check restart-required
     -> check maintenance/read-write barriers
     -> check permissions/capabilities
     -> mark RUNNING
     -> execute body
     -> write terminal state with CAS
     -> release lease
     -> return WorkManager Result
```

If anything fails before body:

```text
STARTED -> BLOCKED_* or RETRY_SCHEDULED/FAILED
release lease
return retry/failure according to policy
```

If cancellation occurs:

```text
rethrow CancellationException
finally release lease
terminal state CANCELLED only if safe/owned by cancellation path
```

---

# 8. Implementation Phases

---

## Phase 0 — Worker Baseline Inventory

### Goal

Know every worker, receiver, scheduler, and background path.

### Tasks

- [ ] Inventory every `CoroutineWorker`.
- [ ] Inventory every `ListenableWorker` if any.
- [ ] Inventory every WorkManager enqueue call.
- [ ] Inventory unique work names and policies.
- [ ] Inventory periodic vs one-shot workers.
- [ ] Inventory receivers that launch coroutine work, e.g. snooze/dismiss.
- [ ] Inventory workers with DB reads/writes.
- [ ] Inventory workers with network/cloud.
- [ ] Inventory workers requiring notification permission.
- [ ] Inventory workers using direct DAO/repositories.
- [ ] Inventory worker tests and ignored/stale tests.
- [ ] Create `docs/workers/WORKER_BASELINE_INVENTORY.md`.

### Useful searches

```bash
rg "CoroutineWorker|ListenableWorker|Worker" app/src/main/java
rg "enqueue|enqueueUnique|enqueueUniquePeriodicWork|WorkManager" app/src/main/java
rg "ExistingWorkPolicy|ExistingPeriodicWorkPolicy" app/src/main/java
rg "doWork\\(" app/src/main/java
rg "BroadcastReceiver|goAsync|launch" app/src/main/java
rg "NotificationIntakeWorker|BillReminderWorker|DailyBriefing|Retention" app/src/main/java
```

### Deliverable table

```md
| Worker | Type | Unique name | DB read | DB write | Network | Permission | Guarded? | Registered? | Tests |
```

### Acceptance Criteria

- [ ] Every worker is listed.
- [ ] Every worker has declared requirements.
- [ ] Unguarded workers are identified.

---

## Phase 1 — Define Worker Contract and Requirements

### Goal

Create a canonical worker policy before refactoring.

### Tasks

- [ ] Create `docs/workers/WORKER_EXECUTION_POLICY.md`.
- [ ] Define which workers require guard.
- [ ] Define worker result mapping.
- [ ] Define retry policy for maintenance/restart-required.
- [ ] Define permission-denied behavior.
- [ ] Define partial-failure behavior.
- [ ] Define terminal diagnostics requirements.
- [ ] Define schedule failure diagnostics requirements.

### Policy decisions

#### Maintenance active

Recommended:

- DB-writing workers return `Result.retry()` after durable diagnostic.
- Read-only workers may retry or return success if explicitly no-op safe.
- Restore-internal workers require owner token and are rare.

#### Restart required

Recommended:

- normal workers do not access DB,
- write durable diagnostic outside DB if possible,
- return `Result.retry()` or `Result.failure()` depending on spec,
- no rescheduling before clean startup.

#### Permission denied

Recommended:

- if permission is required for user-visible delivery, do not mark business operation permanently failed unless policy says so,
- emit `BLOCKED_PERMISSION`,
- retry or wait for permission recovery.

#### Partial failure

Recommended:

- use `PARTIAL_FAILURE`,
- do not soft-success silently,
- include sanitized target-level failure counts.

### Acceptance Criteria

- [ ] Worker behavior is policy-defined, not ad hoc.
- [ ] Each worker has declared requirements.

---

## Phase 2 — Fix Worker Lease Registry

### Goal

Restore drain must not miss active workers.

### Tasks

- [ ] Change lease tracking from `workerName -> lease` to unique `leaseId`.
- [ ] Add secondary index `workerName -> Set<leaseId>`.
- [ ] Include WorkManager `workId`.
- [ ] Add heartbeat or start timestamp.
- [ ] Add safe stale lease recovery.
- [ ] Add tests for concurrent same-name workers.
- [ ] Add tests for lease release on success/failure/cancellation.
- [ ] Add tests for restore drain waiting on all leases.

### Acceptance Criteria

- [ ] Concurrent same-name workers do not overwrite each other.
- [ ] Restore drain sees all active leases.
- [ ] Lease release is reliable in `finally`.

---

## Phase 3 — Run Ledger and Atomic Terminal State

### Goal

Every worker run is durably diagnosable.

### Tasks

- [ ] Create/update `BackgroundJobRun` entity/table if needed.
- [ ] Create run at guard entry.
- [ ] Record spec version and attempt.
- [ ] Add atomic terminal update.
- [ ] Make terminal write compare-and-set.
- [ ] Prevent double terminal state under race.
- [ ] Add retry/failure reason codes.
- [ ] Add sanitized diagnostic metadata.
- [ ] Add tests for double terminal write race.

### Terminal write rules

- [ ] Only open/running runs can transition to terminal.
- [ ] Terminal state cannot be overwritten.
- [ ] If terminal write fails because already terminal, log sanitized diagnostic but do not crash.
- [ ] Cancellation path must not overwrite failure/success already recorded.

### Acceptance Criteria

- [ ] Each run has exactly one terminal state.
- [ ] Terminal state survives worker failure.
- [ ] Race tests pass.

---

## Phase 4 — Implement `WorkerExecutionGuard`

### Goal

Standardize worker lifecycle.

### Tasks

- [ ] Implement guard wrapper.
- [ ] Accept `WorkerRequirements`.
- [ ] Acquire lease.
- [ ] Create run ledger.
- [ ] Check maintenance/restart-required.
- [ ] Check read/write barrier before first DAO read/write.
- [ ] Check notification permission if required.
- [ ] Check cloud/privacy capability if required.
- [ ] Execute worker body.
- [ ] Map outcomes.
- [ ] Write terminal state.
- [ ] Release lease in `finally`.
- [ ] Rethrow `CancellationException`.

### Worker outcome model

Suggested:

```text
Success
Retry(reason)
Failure(reason)
PartialFailure(reason, failedTargets)
NoOp(reason)
BlockedMaintenance
BlockedRestartRequired
BlockedPermission
Cancelled
```

### Acceptance Criteria

- [ ] Guard handles all lifecycle states.
- [ ] Cancellation is not swallowed.
- [ ] Guard test matrix passes.

---

## Phase 5 — Migrate High-Risk Workers

### Priority order

1. `NotificationIntakeWorker`
2. `BillReminderWorker`
3. Data retention/privacy workers
4. Daily briefing/reschedule workers
5. Bank/email/import/background sync workers
6. Recurring/reminder repair/reconciliation workers
7. Backup/export/import workers, if any

---

### 5.1 NotificationIntakeWorker

Tasks:

- [ ] Wrap in full `WorkerExecutionGuard`.
- [ ] Acquire lease.
- [ ] Create run ledger.
- [ ] Barrier-check before first DAO read.
- [ ] Re-check privacy before decrypt/replay.
- [ ] Add durable diagnostics for payload unavailable, filter rejected, max attempts, final failure.
- [ ] Use `TimeProvider` for diagnostic worker IDs/timestamps where needed.
- [ ] Add restore-blocked retry diagnostic.

Acceptance:

- [ ] Notification intake cannot read/decrypt during restore/restart-required/privacy-denied state.
- [ ] Every terminal/drop/retry path has diagnostic.

---

### 5.2 BillReminderWorker

Tasks:

- [ ] Declare `requiresNotificationPermission = true`.
- [ ] Guard checks permission before claim/delivery.
- [ ] Permission denied does not permanently lose reminder.
- [ ] DB writes guarded.
- [ ] Terminal diagnostics for permission blocked and retry.
- [ ] Align quiet-hours/settings tests inside guard.

Acceptance:

- [ ] Permission denial does not silently lose reminder.
- [ ] Reminder worker cannot write during restore.

---

### 5.3 Data retention/privacy workers

Tasks:

- [ ] Remove soft-success on partial target failure.
- [ ] Return `PARTIAL_FAILURE` or retry according to policy.
- [ ] Rethrow `CancellationException`.
- [ ] Record target-level sanitized diagnostics.

Acceptance:

- [ ] Failed retention targets are visible and actionable.

---

### 5.4 Daily briefing/reschedule workers

Tasks:

- [ ] Reschedule failure should not permanently kill chain.
- [ ] Record scheduling failure diagnostic.
- [ ] Startup recovery should repair missing schedules.
- [ ] Use durable run ledger.

Acceptance:

- [ ] One scheduling failure is recoverable.

---

## Phase 6 — Worker Scheduling and Registry Diagnostics

### Goal

Scheduling problems must not disappear.

### Tasks

- [ ] Centralize worker specs in `WorkerRegistry`.
- [ ] Ensure `scheduleAll()` records per-entry result.
- [ ] Catch per-entry schedule failures and emit sanitized diagnostics.
- [ ] Do not abort whole chain unless policy requires.
- [ ] Add summary result:
  - all scheduled,
  - partial scheduled,
  - failed.
- [ ] Add startup recovery for failed schedules.
- [ ] Sync comments/docs with actual policies.
- [ ] Add tests for schedule failure visibility.

### One-shot version bump policy

Tasks:

- [ ] Identify one-shot workers with spec version.
- [ ] Replace `KEEP` with `REPLACE` or cancel+enqueue when version changes.
- [ ] Preserve idempotency via input/run ledger, not stale WorkManager work.
- [ ] Add tests for incompatible version bump.

Acceptance:

- [ ] Stale one-shot work does not survive incompatible changes.
- [ ] Per-worker schedule failures are diagnostic-backed.

---

## Phase 7 — Durable Non-Cancellable Diagnostics

### Goal

Critical terminal diagnostics are not lost during shutdown/restore.

### Tasks

- [ ] Identify diagnostics currently launched in cancellable service/worker scope.
- [ ] Create durable diagnostic writer path.
- [ ] Use `NonCancellable` only for short bounded terminal writes.
- [ ] Prefer durable operation/run ledger transaction where possible.
- [ ] Ensure diagnostics are sanitized.
- [ ] Add timeout to avoid hanging shutdown.
- [ ] Add tests for service destroy, cancellation, and restore shutdown.

### Rules

- [ ] Do not use unbounded `NonCancellable`.
- [ ] Do not write raw payloads/errors.
- [ ] Do not hide diagnostic write failures if terminal state depends on them.
- [ ] If DB unavailable due restart-required, use outside-DB diagnostic store if needed.

Acceptance:

- [ ] Terminal/drop/retry diagnostics survive cancellation/shutdown paths.

---

## Phase 8 — Receivers and Non-WorkManager Background Paths

### Goal

Background work launched from receivers follows the same safety rules.

### Known risk

Snooze/dismiss receivers may launch unstructured scope and catch broad `Exception`.

### Tasks

- [ ] Inventory receiver-launched coroutine work.
- [ ] Replace unstructured launch with structured app/background dispatcher.
- [ ] Use lifecycle/operation guard or enqueue worker where appropriate.
- [ ] Rethrow `CancellationException`.
- [ ] Add DB write barrier.
- [ ] Emit run/diagnostic event.
- [ ] Add tests for receiver cancellation and failure.

Acceptance:

- [ ] Receivers cannot silently fail or bypass DB barriers.

---

## Phase 9 — Static Guards and CI Enforcement

### Goal

New worker violations cannot merge.

### Required guards

- [ ] Worker subclass inventory guard.
- [ ] Guarded worker enforcement.
- [ ] Worker registry/spec parity guard.
- [ ] Worker DB access guard.
- [ ] Cancellation guard for workers/receivers.
- [ ] Schedule policy/comment parity check where feasible.
- [ ] TimeProvider usage guard for business/diagnostic timestamps.
- [ ] Terminal run ledger guard.

### Guard failure examples

- `CoroutineWorker` not using `WorkerExecutionGuard`.
- DB-writing worker missing lease/run ledger.
- Worker not registered in `WorkerRegistry` and not allowlisted.
- `catch(Exception)` in worker without CE rethrow.
- Worker direct DAO access without approved owner.
- One-shot versioned worker using `KEEP`.
- Receiver launching unstructured coroutine DB write.
- `System.currentTimeMillis()` used where `TimeProvider` required.

### Acceptance Criteria

- [ ] Bad fixtures fail CI.
- [ ] Existing exceptions have owner/reason/expiry/MIT link.
- [ ] Guard is required in PR CI.

---

# 9. Testing Strategy

---

## 9.1 Unit Tests

### Lease registry

- [ ] concurrent same-name workers create distinct leases,
- [ ] drain returns all leases,
- [ ] lease released on success,
- [ ] lease released on failure,
- [ ] lease released on cancellation,
- [ ] stale lease recovery works.

### Execution guard

- [ ] normal success,
- [ ] retry outcome,
- [ ] failure outcome,
- [ ] partial failure outcome,
- [ ] maintenance blocked,
- [ ] restart-required blocked,
- [ ] permission blocked,
- [ ] cancellation rethrow,
- [ ] lease released in every path,
- [ ] terminal run written once.

### Run logger

- [ ] terminal CAS succeeds once,
- [ ] double terminal write rejected,
- [ ] race does not corrupt state.

### Scheduling

- [ ] scheduleAll partial failure records diagnostic,
- [ ] one-shot version bump replaces stale work,
- [ ] startup recovery repairs failed schedule.

---

## 9.2 Integration Tests

- [ ] active worker blocks restore until drained or cancelled,
- [ ] restore drain sees concurrent same-name workers,
- [ ] worker starting during maintenance exits/retries without DB read,
- [ ] worker starting during restart-required does not access DB,
- [ ] `NotificationIntakeWorker` checks barrier before first DAO read,
- [ ] bill reminder permission denied does not lose reminder,
- [ ] retention partial target failure is visible,
- [ ] daily briefing reschedule failure is recoverable.

---

## 9.3 Cancellation Tests

- [ ] cancellation during worker body rethrows,
- [ ] cancellation during diagnostic write does not hang,
- [ ] cancellation during lease acquisition/release is safe,
- [ ] receiver cancellation rethrows CE,
- [ ] no worker returns success after CE.

---

## 9.4 Static Guard Tests

Each guard must include:

- [ ] positive fixture,
- [ ] negative fixture,
- [ ] allowlisted fixture,
- [ ] expired allowlist fixture,
- [ ] owner/reason/expiry validation.

---

# 10. Rollout PR Plan

---

## PR 1 — Worker Baseline and Policy ✅ COMPLETE

Includes:

- `WORKER_BASELINE_INVENTORY.md`
- `WORKER_EXECUTION_POLICY.md`
- `WORKER_REQUIREMENTS_MATRIX.md`
- declared requirements for each worker
- registry/spec inventory
- `ExistingWorkPolicy.KEEP` → `REPLACE` fix in `WorkerSpecScheduler`

Acceptance:

- [x] Every worker is inventoried.
- [x] Every worker has requirements and ownership.

---

## PR 2 — Lease Registry Fix ✅ COMPLETE

Includes:

- unique lease IDs (UUID),
- `workerNameIndex` secondary index (`workerName -> Set<leaseId>`),
- idempotent `close()`,
- `drain_sees_all_concurrent_same_name_workers` test.

Acceptance:

- [x] Concurrent same-name workers are tracked correctly.
- [x] Restore drain can see all active leases.

---

## PR 3 — Run Ledger and Atomic Terminal Logging ✅ COMPLETE

Includes:

- `WorkerRunLoggerImpl` terminal methods use `AtomicBoolean.compareAndSet(false, true)`
- `BackgroundJobRun` DAO update only on CAS success
- `WorkerRunLoggerTest.kt` with 10 tests including concurrent terminal race

Acceptance:

- [x] Each run has exactly one terminal state.

---

## PR 4 — WorkerExecutionGuard ✅ COMPLETE (already implemented)

Includes:

- verified existing guard implementation,
- 12 existing tests pass (`WorkerExecutionGuardTest`),
- no code changes required.

Acceptance:

- [x] Guard handles all core worker states.

---

## PR 5 — NotificationIntakeWorker Migration ✅ COMPLETE

Includes:

- wrapped DB body in `runGuardedWithContext()`
- removed direct `writeBarrier` access
- replaced `System.currentTimeMillis()` with `TimeProvider`
- added `WORKER_NAME` constant
- removed from architecture guard allowlists
- `NotificationIntakeWorkerTimeoutTest.kt` with real guard + 7 tests
- fixed `RuntimeException("TIMEOUT")` → `RuntimeException("MAX_RETRIES_EXHAUSTED")` to avoid guard transient-classifier collision

Acceptance:

- [x] P1 worker replay/read issues are blocked.

---

## PR 6 — Reminder/Retention/Daily Workers Migration

Includes:

- bill reminder permission guard,
- retention partial-failure state,
- daily briefing reschedule recovery,
- tests.

Acceptance:

- [ ] P4/P9 worker blockers are fixed.

---

## PR 7 — Scheduling Diagnostics and One-Shot Version Policy

Includes:

- `scheduleAll()` per-entry diagnostics,
- version bump `REPLACE` or cancel+enqueue,
- startup recovery,
- comment/spec sync.

Acceptance:

- [ ] Schedule failures are visible.
- [ ] Stale one-shot work does not survive version changes.

---

## PR 8 — Durable Terminal Diagnostics

Includes:

- non-cancellable bounded diagnostic path,
- service/restore shutdown tests,
- outside-DB fallback if needed.

Acceptance:

- [ ] Critical terminal diagnostics are not lost.

---

## PR 9 — Receivers and Non-Worker Background Paths

Includes:

- snooze/dismiss receiver structured concurrency,
- CE rethrow,
- barrier/diagnostics,
- tests.

Acceptance:

- [ ] Receiver background DB writes are lifecycle-safe.

---

## PR 10 — Static Guards and CI

Includes:

- worker subclass inventory guard,
- worker guard enforcement,
- registry/spec parity guard,
- cancellation guard coverage,
- time-provider guard,
- fixtures/tests.

Acceptance:

- [ ] New worker violations fail CI.

---

## PR 11 — Final Restore/Worker Regression Suite

Includes:

- active worker restore drain tests,
- restart-required worker tests,
- tracker/docs updates.

Acceptance:

- [ ] MIT-016, MIT-017, MIT-035, MIT-065, MIT-070, MIT-082 can close.

---

# 11. Edge Cases

## Worker starts after DB swap but before restart

Expected:

- guard sees restart-required,
- no DAO read/write,
- terminal diagnostic recorded,
- result retry/failure according to policy.

---

## Restore begins while two same-name workers run

Expected:

- both leases visible,
- restore waits/cancels both,
- no overwrite.

---

## Worker is cancelled after success but before terminal write

Expected:

- terminal handling is deterministic,
- no duplicate terminal state,
- cancellation does not corrupt ledger.

---

## ScheduleAll partially fails

Expected:

- failed entry diagnostic,
- other entries continue if policy allows,
- startup recovery retries failed schedule.

---

## Permission denied for reminder

Expected:

- no lost reminder,
- delivery state remains recoverable,
- diagnostic indicates permission blocked.

---

## Data retention fails one target

Expected:

- result is partial failure or retry,
- target-level diagnostic exists,
- not silent success.

---

# 12. Documentation Requirements

Create/update:

```text
docs/workers/WORKER_BASELINE_INVENTORY.md
docs/workers/WORKER_EXECUTION_POLICY.md
docs/workers/WORKER_REQUIREMENTS_MATRIX.md
docs/workers/WORKER_REGISTRY_POLICY.md
docs/workers/WORKER_RUN_LEDGER_POLICY.md
docs/testing/WORKER_RESTORE_DRAIN_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

Each closed issue should list:

- closing commit SHA,
- workers migrated,
- tests added,
- guards added,
- remaining allowlist entries.

---

# 13. Metrics to Track

| Metric | Target |
|---|---|
| DB-writing workers without guard | 0 |
| Workers without run ledger | 0 |
| Workers without unique lease | 0 |
| Same-name lease overwrite cases | 0 |
| Terminal double-write races | 0 |
| Schedule failures without diagnostics | 0 |
| Versioned one-shot workers using KEEP | 0 |
| Worker `catch(Exception)` without CE rethrow | 0 |
| Worker direct DAO access without legal owner | 0 |
| Workers missing from registry | 0 or justified |
| Expired worker allowlist entries | 0 |

---

# 14. Risks and Mitigations

## Risk: Guard migration breaks existing workers

Mitigation:

- migrate highest-risk workers first,
- use typed `WorkerRequirements`,
- add focused tests per worker.

## Risk: Ledger writes fail while DB is blocked

Mitigation:

- preflight before DB work,
- use outside-DB diagnostics for restart-required if DB unavailable,
- keep terminal diagnostic bounded and sanitized.

## Risk: CI guard false positives

Mitigation:

- fixture tests,
- owner/reason/expiry allowlist,
- start warning-only only for S2 rules; S0 worker DB guard should be blocking.

## Risk: WorkManager retries create duplicates

Mitigation:

- pair with DB idempotency constraints,
- use run ledger/idempotency keys,
- never rely on WorkManager uniqueness alone for business dedupe.

## Risk: Restore drain hangs on stuck worker

Mitigation:

- lease heartbeat/stale timeout,
- cancellation request,
- bounded wait,
- diagnostic + safe restart-required behavior.

---

# 15. Definition of Done by MIT

## MIT-016 can close when

- [ ] Lease registry tracks unique lease IDs.
- [ ] Concurrent same-name workers are visible to restore drain.
- [ ] `NotificationIntakeWorker` uses full guard.
- [ ] Every DB-writing worker has guard/lease/run ledger or approved exception.
- [ ] Static guard blocks new unguarded DB-writing workers.

## MIT-017 can close when

- [ ] One-shot version bump uses `REPLACE` or cancel+enqueue.
- [ ] Terminal run logging is atomic.
- [ ] Retention partial failures are visible.
- [ ] Daily briefing reschedule failures recover.
- [ ] `scheduleAll()` records per-entry failures.

## MIT-035 can close when

- [ ] Worker run ledger exists and is durable.
- [ ] Notification, bank/import/export/retention worker diagnostics are represented as needed.
- [ ] Long-running worker operations are resumable or diagnosable.

## MIT-065 can close when

- [ ] Terminal/pre-launch diagnostics use durable bounded path.
- [ ] Shutdown/restore cancellation cannot erase critical diagnostics.
- [ ] Tests cover service destroy and restore shutdown.

## MIT-070 can close when

- [ ] Worker scheduling failures emit sanitized diagnostics.
- [ ] Worker comments/spec docs match implementation.
- [ ] Schedule failure visibility tests pass.

## MIT-082 can close when

- [ ] Every worker subclass is inventoried.
- [ ] Registry/spec parity guard exists.
- [ ] TimeProvider usage is fixed or documented.
- [ ] Stale `RUNNING` recovery test passes.

---

# 16. Final Completion Checklist

This plan is complete when:

- [x] Worker baseline inventory exists. (PR 1)
- [x] Worker execution policy exists. (PR 1)
- [x] Worker requirements matrix exists. (PR 1)
- [x] Lease registry uses unique leases. (PR 2)
- [x] Restore drain sees all active workers. (PR 2)
- [x] Run ledger exists with atomic terminal writes. (PR 3)
- [x] `WorkerExecutionGuard` is implemented. (PR 4)
- [x] `NotificationIntakeWorker` is guarded. (PR 5)
- [ ] Bill reminder permission is guard-enforced. (PR 6)
- [ ] Retention partial failures are not soft-success. (PR 6)
- [ ] Daily briefing scheduling is recoverable. (PR 6)
- [ ] `scheduleAll()` emits per-entry diagnostics. (PR 7)
- [x] One-shot version changes replace stale work. (PR 1)
- [ ] Terminal diagnostics are durable and bounded. (PR 8)
- [ ] Receiver-launched background DB work is structured and guarded. (PR 9)
- [ ] Worker static guards are blocking in CI. (PR 10)
- [ ] Restore/worker regression tests pass. (PR 11)
- [x] Master tracker is updated with closing SHAs. (PRs 1–5)

---

# 17. Recommended First Action

Start with:

```text
PR 1 — Worker Baseline and Policy
```

Then:

```text
PR 2 — Lease Registry Fix
PR 3 — Run Ledger and Atomic Terminal Logging
PR 4 — WorkerExecutionGuard
PR 5 — NotificationIntakeWorker Migration
```

Do not migrate every worker manually before the guard exists.  
Build the shared guard/lease/ledger foundation first, then migrate workers in risk order.