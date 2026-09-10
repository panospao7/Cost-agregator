# GR-13 canonical worker-guard contract — discovery record

Status: recorded from production source at working-tree HEAD
`9d145697ae6d77da9d782ee6e529a876a723920d` (branch `gr-00-local`). This is
the mandatory GR-13 discovery record required by
`docs/guardrails/PR-GR-13_helper_worker_mediation_proof_plan.md`
("Canonical worker contract"). Every fact below was read from the actual
source, not inferred from the string `runGuarded`.

Source of record: `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt`

## Receiver identity

| Fact | Value |
|---|---|
| Receiver FQCN | `com.yourname.expensetracker.domain.workers.WorkerExecutionGuard` |
| Implementation | `@Singleton class WorkerExecutionGuard @Inject constructor(...)` — concrete class, no interface backing |
| Canonical write barrier it delegates to | `com.yourname.expensetracker.data.backup.DatabaseWriteBarrier.checkWritesAllowed(...)` (the GR-12 canonical barrier receiver) |

A `runGuarded`-shaped call on ANY other receiver is not a canonical worker
guard (mirrors the GR-12 rule: bare/spelling-based matching is never
sufficient; same-name methods on other receivers are not barriers).

## Exact scope methods

1. `suspend fun <T> runGuarded(request: WorkerGuardRequest, block: suspend () -> T): WorkerGuardResult<T>` (line ~98)
2. `suspend fun <T> runGuardedWithContext(request: WorkerGuardRequest, block: suspend (WorkerRunContext) -> T): WorkerGuardResult<T>` (line ~276)

No other overload exists. Both take the `WorkerGuardRequest` as the FIRST
parameter and the scope lambda as the trailing lambda.

## Barrier-check ordering (source-verified)

On the DB-write path (`mode == NORMAL && request.requiresDatabaseWrite`):

1. `writeBarrier.checkWritesAllowed(request.workerName)` runs FIRST
   (runGuarded line ~127; runGuardedWithContext identical);
2. then lease acquisition, run-log start (which performs its own
   `checkWritesAllowed("WorkerRunLogger.start:<worker>")` via
   `startRunSafely`), spec/privacy/notification-permission gates;
3. only then `block()` is invoked (runGuarded line ~178;
   runGuardedWithContext line ~360).

So the canonical check provably precedes the lambda body on every path
where the body executes with write permission.

## Lambda execution semantics

- The block is a **synchronous suspend call** inside the caller's
  coroutine — no `launch`/`async`, no storage, no return of the lambda.
- Invoked **exactly once** on the success path; **zero times** when the
  guard returns early (`Skipped` / `BlockedRetry` / `Retry` from
  maintenance-mode blocking, lease denial, provider-disabled spec, privacy
  denial, notification-permission denial, or run-log start failure).
- Exceptions from the block are classified by the guard (timeout/cancel/
  checkpoint/retryable/transient) — the DB work itself remains inside the
  guarded scope for mediation purposes.
- Contract tests proving normal-return-only-after-check and
  exactly-once/zero-times behavior: `WorkerExecutionGuardTest.kt`,
  `WorkerBarrierIntegrationTest.kt` (app/src/test/.../domain/workers/).

## Guarded-mode nuances the proof engine MUST handle

1. **Read-only backup path**: when `mode != NORMAL` but
   `allowDuringBackupExport && !requiresDatabaseWrite`, the block DOES run
   with NO write-barrier check (only `readBarrier.checkReadAllowed`).
   `requiresDatabaseWrite=false` is a declaration that the body performs
   no DB writes. Therefore: a policy mutation proven to sit inside such a
   scope is **not** proven — it is
   `COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE` (the scope's write-guard
   precondition was explicitly waived).
2. **Per-operation checkpoints**: `suspend fun checkpoint(operation:
   String)` performs `writeBarrier.checkWritesAllowed(operation)` per call
   (line ~258) and is reachable inside the lambda via
   `WorkerRunContext.checkpoint(...)` (runGuardedWithContext wires
   `checkpointDelegate = ::checkpoint`). A checkpoint is an ADDITIONAL
   canonical check, not a scope; it cannot prove mutations that run before
   the enclosing guard's lambda.
3. **The guard class itself contains DB mutations**
   (`backgroundJobRunDao` via `startRunSafely` and
   `recoverStaleRunningJobs`) with their own canonical
   `checkWritesAllowed` calls. Being "the guard" grants nothing; the proof
   treats those callables as ordinary mediation subjects.

## Worker context parameters required by the contract

- `WorkerGuardRequest`: `workerName`, `requiredCapabilities`,
  `requiresNotificationPermission`, `requiresDatabaseWrite` (default
  `true`), `allowDuringBackupExport` (default `false`), blocked/privacy/
  notification-permission policies, `workId`, `runAttemptCount`,
  `specVersion`, `timeoutPolicy`.
- `WorkerRunContext` (runGuardedWithContext only): checkpoint delegate
  wired to `WorkerExecutionGuard::checkpoint`, plus rows-scanned/updated
  and notification counters.

## Result bridge behavior relevant to scope

`fun <T> WorkerGuardResult<T>.toWorkerResult(): ListenableWorker.Result`
(file-scope extension) maps Success/Skipped→success, BlockedRetry/Retry→
retry, Failed→failure. The bridge runs in the WORKER (outside the guard
lambda); it never re-enters the guarded scope. Result mapping is outside
GR-13's mediation proof.

## Worker root method identity

The canonical worker root is the `doWork()` override of a concrete
`CoroutineWorker` subclass. Current production workers discovered by
source scan (files importing/declaring CoroutineWorker): `DailyBriefingWorker`,
`LocationBackfillWorker`, `MerchantKeyBackfillWorker`,
`DataRetentionWorker`, `ReceiptMatchingWorker`, `BillReminderWorker`,
`WarrantyExpirationWorker`, `NotificationIntakeWorker`,
`DismissReminderActionWorker` (+ any further subclasses at analysis time —
the engine must enumerate, not assume this list). No intermediate abstract
worker base class exists today; discovery must still handle one exactly
if added.

## Registry / source crosswalk source

`app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt`
— "Single source-of-truth registry for all background workers" (P7-P1-07).
Worker-root discovery must cross-check the source-enumerated `CoroutineWorker`
subclasses against this registry; any mismatch is a
`COUNTEREXAMPLE_NON_WORKER_ROOT` / diagnostic condition, never a silent pass.
The legacy regex script (`scripts/verify_worker_boundaries.py`) is NOT
authority for GR-13 (plan hard stop).

## Contract change policy

Any change to the receiver FQCN, scope-method signatures, or
barrier-before-block ordering requires a dedicated reviewed diff updating
BOTH this record and the GR-13 engine's typed contract — no YAML wildcard
may redefine the worker contract (mirrors GR-12's rule 7).
