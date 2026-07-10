# Cancellation / Atomicity / Event Consistency — Remaining Issues Implementation Plan

Base reviewed commit: `e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7`

Goal: finish the remaining work for MIT-031, MIT-034, MIT-041, and MIT-043 without overclaiming completion.

Current status: foundation landed, but the implementation is still partial. Do not mark the MITs fully done until this plan passes.

---

## Recommended PR sequence

1. **PR11 — Immediate correctness regressions**
2. **PR12 — Cancellation policy closure**
3. **PR13 — Transaction-scoped event writer enforcement**
4. **PR14 — Bank-statement receipt/review atomicity**
5. **PR15 — Recurring/reminder hidden-write and event atomicity**
6. **PR16 — Post-commit side-effect outbox/evidence hardening**
7. **PR17 — Legacy inconsistency repair/surfacing**
8. **PR18 — Final static guards, CI, docs, tracker closure**

---

# PR11 — Immediate Correctness Regressions

## Goal

Fix the obvious correctness bugs introduced or left behind in the foundation commit.

## Issues fixed

- `LegacyDataConsistencyChecker.runConsistencyCheck()` returns a zeroed report.
- Remaining `runCatching` in suspend/domain paths can swallow cancellation.
- Bank-statement final receipt status update and event can diverge.
- Docs currently overstate MIT completion.

## Files

Likely:
- `LegacyDataConsistencyChecker.kt`
- `BankStatementLifecycleProcessor.kt`
- `RecurringLifecycleCoordinator.kt`
- atomicity docs / master tracker

## Tasks

### 1. Fix `LegacyDataConsistencyChecker`

Current bug: the checker computes a report inside `measureTimeMillis`, emits diagnostics, then returns a new zeroed report.

Fix:

```kotlin
var report: ConsistencyReport
val elapsedMs = measureTimeMillis {
    report = ConsistencyReport(
        receiptsWithoutEvent = ...,
        pendingReviewsWithoutReceipt = ...,
        occurrencesWithoutEvent = ...,
        ...
    )
    emitSummaryDiagnostic(report)
}
return report.copy(elapsedMs = elapsedMs)
```

If `report` cannot be `lateinit` because it is a data class, use local nullable:

```kotlin
var computed: ConsistencyReport? = null
...
return requireNotNull(computed).copy(elapsedMs = elapsedMs)
```

### 2. Add tests for consistency checker

Add fixtures/fakes proving non-zero counts are returned:

- `receipt_without_event_is_reported`
- `pending_review_without_receipt_is_reported`
- `occurrence_without_event_is_reported`
- `summary_diagnostic_matches_returned_report`
- `elapsed_time_is_preserved`

### 3. Replace remaining unsafe `runCatching`

Search:

```bash
rg "runCatching" app/src/main/java
```

Replace suspend/domain usages with:

```kotlin
CancellationSafe.runCatchingCancellable { ... }
```

or explicit try/catch:

```kotlin
try {
    ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

Priority files:
- `RecurringLifecycleCoordinator.kt`
- `BankStatementLifecycleProcessor.kt`
- any receipt/OCR/review/import worker path

### 4. Transactionalize bank final status + event

In `BankStatementLifecycleProcessor`, wrap final receipt status update plus final lifecycle event in one transaction:

```kotlin
database.withTransaction {
    receiptDao.updateStatus(...)
    receiptLifecycleEventDao.insert(...)
}
```

Do the same for failure terminal state if applicable.

### 5. Update docs status

Change from:

```text
MIT-031/034/041/043 DONE
```

to:

```text
Foundation landed; remaining PR11–PR18 pending.
```

## Acceptance criteria

- Consistency checker returns real counts.
- No unsafe `runCatching` remains in high-risk suspend paths.
- Bank receipt final status and final event cannot diverge.
- Docs do not claim completion prematurely.

---

# PR12 — Cancellation Policy Closure

## Goal

Make MIT-034 actually enforceable.

## Current problem

`CancellationSafetyArchitectureGuardTest` has a huge filename allowlist and no owner/reason/expiry metadata. Unsafe broad catches and `runCatching` can still survive.

## Files

- `CancellationSafetyArchitectureGuardTest.kt`
- `CancellationSafe.kt`
- test fixtures/resources
- docs: `CANCELLATION_POLICY.md`

## Tasks

### 1. Create structured allowlist

Replace:

```kotlin
setOf("SomeFile.kt", ...)
```

with:

```kotlin
data class CancellationAllowlistEntry(
    val fileName: String,
    val rule: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Validation:
- owner required;
- reason required;
- issue required;
- expiry required;
- expired entries fail.

### 2. Split rules

Separate rules:

- `RUN_CATCHING_IN_SUSPEND_PATH`
- `BROAD_CATCH_WITHOUT_CE_RETHROW`
- `THROWABLE_CATCH_WITHOUT_CE_RETHROW`
- `ON_FAILURE_WITHOUT_CE_PRESERVATION`
- `RETURN_SUCCESS_AFTER_CANCELLATION`

### 3. Ban `runCatching` in suspend/worker/receiver paths

Allow only:

```kotlin
CancellationSafe.runCatchingCancellable { ... }
```

or a documented, non-suspend pure computation exception.

### 4. Shrink allowlist

Remove core files from allowlist first:

- `ReceiptLifecycleCoordinator.kt`
- `RecurringLifecycleCoordinator.kt`
- `BankStatementLifecycleProcessor.kt`
- `WorkerExecutionGuard.kt`
- `NotificationIntakeWorker.kt`
- receipt/review/import coordinators

If a file remains allowlisted, create an owner/expiry and issue.

### 5. Add fixtures

Bad fixtures:
- `RunCatchingInSuspendPath.kt`
- `CatchExceptionNoCancellationRethrow.kt`
- `CatchThrowableNoCancellationRethrow.kt`
- `RunCatchingOnFailureSwallowsCancellation.kt`
- `ReceiverSwallowsCancellation.kt`
- `WorkerReturnsSuccessAfterCancellation.kt`
- `ExpiredCancellationAllowlist.kt`

Good fixtures:
- `CatchExceptionWithCancellationRethrow.kt`
- `RunCatchingCancellableHelper.kt`
- `PureNonSuspendRunCatching.kt`

### 6. Tests

- bad fixtures fail with expected rule;
- good fixtures pass;
- expired allowlist fails;
- all real source violations are either fixed or structured/expiring.

## Acceptance criteria

- No unstructured cancellation allowlist.
- No high-risk `runCatching` in suspend paths.
- `CancellationException` is rethrown in suspend/worker/receiver paths.
- MIT-034 can only close when this guard passes without broad core allowlists.

---

# PR13 — Transaction-Scoped Event Writer Enforcement

## Goal

Make MIT-031 real: critical state mutations and lifecycle events must be written through transaction-aware APIs, not casual DAO inserts.

## Current problem

`TransactionalEventWriter` is mostly a marker. It does not require `TransactionContext` or reject use outside a transaction.

## Files

- `TransactionalEventWriter.kt`
- `DomainTransactionRunner.kt`
- `TransactionContext.kt`
- event writer implementations
- direct event DAO guard
- receipt/recurring/operation event DAOs

## Tasks

### 1. Make event writer require `TransactionContext`

Example:

```kotlin
interface TransactionalEventWriter {
    suspend fun insertReceiptEvent(
        ctx: TransactionContext,
        event: ReceiptLifecycleEvent
    )

    suspend fun insertRecurringEvent(
        ctx: TransactionContext,
        event: RecurringLifecycleEvent
    )
}
```

No critical event method should be context-free.

### 2. Add transaction proof

`TransactionContext` should include:

```kotlin
val transactionId: String
val operationId: String
val correlationId: String
val actor: String
val startedAtMs: Long
internal val transactionToken: TransactionToken
```

Make token internal so only `DomainTransactionRunner` can create it.

### 3. Integrate `DomainTransactionRunner`

Critical coordinators should use:

```kotlin
transactionRunner.runInTransaction(
    operation = "receipt.saveWithReview",
    correlationId = ...
) { ctx ->
    receiptDao.insert(...)
    eventWriter.insertReceiptEvent(ctx, ...)
}
```

Priority:
- receipt lifecycle;
- bank statement lifecycle;
- recurring lifecycle;
- reminder lifecycle;
- operation lifecycle.

### 4. Shrink direct event DAO allowlist

Only allow direct event DAO insert in:
- event writer implementation;
- migrations/tests;
- possibly legacy repair coordinator with expiry.

Everything else must use transaction-aware writer.

### 5. Add static guard

Fail:
- `eventDao.insert(...)`
- `lifecycleEventDao.insert(...)`
- `pendingReviewDao.insert(...)` outside approved coordinator/writer if it represents required review.

Use structured allowlist with expiry.

### 6. Tests

- event writer cannot be called without `TransactionContext`;
- state insert + event failure rolls back state;
- event insert + later exception rolls back event;
- direct event DAO insert fixture fails;
- approved writer fixture passes.

## Acceptance criteria

- Critical event writes require transaction context.
- Direct event DAO insert is blocked outside approved writer/coordinator.
- Rollback tests prove state/event atomicity.

---

# PR14 — Bank-Statement Receipt / Review Atomicity

## Goal

Make MIT-041 true for bank-statement receipt/review paths.

## Current problem

Bank statement processing is partly transactional, but:
- import run can exist before receipt transaction;
- per-item review transactions mean partial commit policy is implicit;
- final receipt status + event can diverge;
- cancellation writes terminal state without clear policy;
- item review/status/event atomicity needs stronger tests.

## Files

- `BankStatementLifecycleProcessor.kt`
- bank statement import/run DAOs
- receipt/review DAOs
- lifecycle event writer
- tests

## Tasks

### 1. Define policy explicitly

Choose one:

#### Option A — whole statement atomic

All receipt/import/review/event rows rollback if any required part fails.

Best for strict consistency, harder for large imports.

#### Option B — row-level atomic

Each item is atomic, statement run is a ledger. Some rows can succeed while others fail, but every row-level state/event/review pair is atomic.

Recommended for imports.

Document:

```text
Bank statement import is row-level atomic. ImportRun may exist without all item rows succeeding. Each item commits its receipt/review/status/event atomically. Failures create sanitized row diagnostics.
```

### 2. Wrap final status + final event

Ensure final receipt status and event are in one transaction.

### 3. Row-level item transaction

For each item:

```kotlin
database.withTransaction {
    validate item preconditions already done
    insert/update import item
    insert receipt or link
    insert pending review if required
    insert item lifecycle event
}
```

### 4. Import run terminal policy

If cancellation occurs:
- rethrow `CancellationException`;
- if writing cancellation diagnostic/terminal run, use bounded non-cancellable diagnostic path;
- do not mark false success/failure.

### 5. Validation before transaction

Before mutating:
- finite amount;
- valid currency;
- valid date;
- sanitized merchant/description;
- no raw sensitive metadata in diagnostics.

### 6. Tests

- invalid amount creates no receipt/review item;
- item review failure rolls back that item;
- final receipt status event failure rolls back status;
- cancellation during item transaction rolls back item;
- cancellation after committed rows leaves committed row-level states consistent;
- import run terminal state uses cancellation code, not false failure;
- partial row policy documented and tested.

## Acceptance criteria

- Bank statement row-level or whole-statement atomicity is explicit.
- No receipt requiring bank review commits without review row.
- Final receipt status and event cannot diverge.
- Cancellation does not become false success/failure.

---

# PR15 — Recurring / Reminder Hidden-Write and Event Atomicity

## Goal

Finish MIT-043.

## Current problems

- `reconcilePlannedVsActual()` still writes.
- Reminder regeneration swallows event failures.
- Stale-claim recovery writes without event/barrier.
- Some claim/recovery paths may update state without lifecycle event.

## Files

- `RecurringLifecycleCoordinator.kt`
- recurring/reminder DAOs
- recurring lifecycle event writer
- reminder workers/action workers
- tests

## Tasks

### 1. Split `reconcilePlannedVsActual()`

Create pure read:

```kotlin
suspend fun calculatePlannedVsActualReport(...): PlannedVsActualReport
```

Create explicit write command:

```kotlin
suspend fun ensureOccurrencesGeneratedForReconciliation(...): GenerationResult
```

Callers that need generation must call the command first.

### 2. Static guard for hidden writes

Fail if methods named:

```text
get*
load*
observe*
find*
query*
calculate*
report*
reconcile*
```

call DAO insert/update/delete or coordinator write commands.

Allowlist only with owner/expiry.

### 3. Make reminder regeneration event-critical

In `regenerateReminderDeliveriesForOccurrence(...)`, remove best-effort swallowed event insert.

Critical state update + event insert should rollback together.

If some event is diagnostic only, rename it and document it as non-critical.

### 4. Stale recovery command

For stale reminder delivery recovery:

```kotlin
suspend fun recoverStaleClaimedDeliveries(...): RecoveryResult
```

Must:
- check write barrier;
- run in transaction;
- update delivery state;
- write recovery event or diagnostic;
- return affected count.

### 5. Claim semantics

Decide if `claimReminderDelivery()` requires lifecycle event.

If yes:
- claim state + event transaction.

If no:
- document claim as transient operational state and ensure stale recovery exists.

### 6. Duplicate fulfillment conflict

If DB uniqueness from MIT-033 rejects duplicate actual link:
- catch constraint exception;
- rollback transaction;
- return typed conflict;
- create review/diagnostic atomically if policy requires.

### 7. Tests

- `calculatePlannedVsActualReport` does not mutate DB;
- explicit generation command mutates and events transactionally;
- projection failure rolls back all rows;
- event failure during reminder regeneration rolls back delivery changes;
- stale recovery writes state + event;
- duplicate actual link maps to typed conflict;
- cancellation during projection rolls back.

## Acceptance criteria

- Read-named recurring methods do not write.
- Recurring/reminder state/event pairs are atomic.
- Projection cannot leave partial rows.
- Duplicate fulfillment is handled safely.

---

# PR16 — Post-Commit Side-Effect Outbox / Evidence Hardening

## Goal

Make post-commit side-effect failures durable without corrupting primary transactions.

## Current problem

`PostCommitSideEffectEvidenceService` records diagnostic events, but it is not a real outbox:
- no request table;
- no status/retry policy;
- evidence write failure can lose evidence;
- naming may overclaim.

## Decision point

Choose one:

### Option A — Evidence logger only

If scope is just failure evidence, rename/docs:

```text
PostCommitSideEffectEvidenceService is not an outbox. It records best-effort durable diagnostics.
```

Then do not close any outbox/retry acceptance under this plan.

### Option B — Real outbox

Recommended if side effects must be retried.

Create table:

```text
post_commit_side_effects
```

Fields:
- id;
- operationId;
- correlationId;
- type;
- payloadJson sanitized;
- status: PENDING/RUNNING/SUCCEEDED/FAILED/DEAD;
- attempts;
- nextAttemptAt;
- lastErrorCode;
- lastErrorClass;
- createdAt;
- updatedAt.

### Tasks for Option B

1. Transactionally enqueue side-effect request inside primary transaction.
2. Worker/dispatcher processes PENDING.
3. Failure updates row with sanitized code/class.
4. Retry policy based on type.
5. Dead-letter after max attempts.
6. Static guard: post-commit side effects must use outbox/evidence service.

### Tests

- primary transaction commits and outbox row exists;
- side effect failure records FAILED and error class;
- retryable side effect schedules next attempt;
- non-retryable side effect dead-letters;
- evidence write failure does not corrupt primary transaction.

## Acceptance criteria

- Side-effect failure is durable and queryable.
- Primary transaction is not rolled back by post-commit failure.
- Scope is honestly documented: evidence logger or real outbox.

---

# PR17 — Legacy Inconsistency Repair / Surfacing

## Goal

Make existing inconsistent data safe.

## Current problem

Consistency checker exists but repair/surfacing is incomplete.

## Files

- `LegacyDataConsistencyChecker.kt`
- repair coordinator
- receipt/recurring/review DAOs
- diagnostics

## Tasks

### 1. Queries

Add queries for:
- receipts requiring review but missing `PendingReview`;
- pending reviews without receipt;
- receipt status/event mismatches;
- recurring occurrence without event;
- reminder delivery without event if critical;
- duplicate linked actuals;
- partial projection rows.

### 2. Repair policy

For each inconsistency define:

| Issue | Repair |
|---|---|
| receipt needs review missing row | create PendingReview or diagnostic |
| orphan pending review | mark orphan diagnostic / hide / delete if safe |
| receipt status no event | create repair event |
| recurring occurrence no event | create repair event |
| duplicate actual link | create review/conflict diagnostic |
| partial projection | regenerate or mark invalid |

### 3. Transactional repair

Each repair must use:
- transaction runner;
- event writer;
- cancellation-safe handling;
- sanitized diagnostics.

### 4. Tests

- inconsistent legacy fixture is detected;
- safe repair commits state + repair event;
- unsafe repair creates diagnostic;
- cancellation during repair rolls back;
- repair is idempotent.

## Acceptance criteria

- Existing inconsistent states are repaired or surfaced.
- Repair itself is transactional and cancellation-safe.

---

# PR18 — Final Guards / CI / Docs / Tracker Closure

## Goal

Close the MITs only when enforcement and tests prove it.

## Static guards required

1. Cancellation guard.
2. Direct event DAO insert guard.
3. Transaction-required mutation guard.
4. Receipt/review direct mutation guard.
5. Recurring hidden-write guard.
6. Post-commit side-effect guard.
7. Allowlist expiry guard.

## CI

Run at minimum:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Preferred:

```bash
./gradlew :app:check
```

Targeted:

```bash
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Atomicity*"
./gradlew :app:testDebugUnitTest --tests "*Transaction*"
./gradlew :app:testDebugUnitTest --tests "*Receipt*"
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
./gradlew :app:testDebugUnitTest --tests "*Consistency*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
```

## Docs

Update:
- `CANCELLATION_POLICY.md`
- `TRANSACTIONAL_EVENT_POLICY.md`
- `POST_COMMIT_SIDE_EFFECT_POLICY.md`
- `RECEIPT_REVIEW_ATOMICITY_POLICY.md`
- `RECURRING_LIFECYCLE_ATOMICITY_POLICY.md`
- master issue tracker

## Final MIT closure criteria

### MIT-034

Close only when:
- no unsafe cancellation pattern remains outside structured/expiring allowlist;
- CE tests pass;
- guard is blocking in CI.

### MIT-031

Close only when:
- critical state/event pairs use transaction context;
- direct event DAO inserts are blocked;
- rollback tests pass.

### MIT-041

Close only when:
- receipt save/status/review atomicity is proven;
- bank statement row/statement policy is explicit and tested;
- no required review can be missing after commit.

### MIT-043

Close only when:
- recurring state/event writes are atomic;
- projection rollback tests pass;
- hidden writes are split;
- duplicate fulfillment conflict is safely handled.

---

# Minimal immediate hotfix set

If you need the shortest useful patch first:

1. Fix `LegacyDataConsistencyChecker` returning zero report.
2. Replace remaining `runCatching` in recurring/bank paths.
3. Wrap bank final status + event in one transaction.
4. Split `reconcilePlannedVsActual()` into write command + pure report.
5. Remove best-effort swallowed event writes from recurring critical mutations.
6. Downgrade docs from DONE to PARTIAL until PR11–PR18 pass.

These six changes address the highest-risk correctness gaps.