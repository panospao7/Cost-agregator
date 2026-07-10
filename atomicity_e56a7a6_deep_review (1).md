# Cancellation / Atomicity / Event Consistency Review — commit `e56a7a6`

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7

Parent worker baseline reviewed previously:  
https://github.com/panospao7/Cost-agregator/commit/e692d743f64ba728093b07d772282c5cf5823789

Static review only. I did not run Gradle locally.

## Executive verdict

This commit is **directionally good**, but it is not a complete implementation of the attached plan.

Good:
- `CancellationSafe` helper exists.
- `DomainTransactionRunner` / `TransactionContext` were introduced.
- Some broad catches now rethrow cancellation.
- Some receipt and recurring writes were wrapped in Room transactions.
- A direct event DAO insert guard exists.
- Post-commit side-effect evidence service exists.
- Hidden write in `getDueReminders()` was split.
- Receipt save + required `PendingReview` in one path improved.

But major blockers remain:

1. `LegacyDataConsistencyChecker.runConsistencyCheck()` computes findings, emits diagnostics, then returns a zeroed report.
2. Cancellation policy is not really enforced: huge allowlist remains, `runCatching` still exists in a suspend path, and the guard is regex/allowlist-heavy.
3. `reconcilePlannedVsActual()` is still a read-like method with writes.
4. Bank-statement receipt/review/run/status/event writes are still not fully atomic.
5. Recurring lifecycle still has swallowed/best-effort event writes inside mutation paths.
6. `TransactionalEventWriter` is only a marker; it does not enforce transaction context.
7. Direct event DAO guard allowlist is very broad and includes legacy repositories.
8. Post-commit side-effect evidence is not a real outbox/retry system.
9. Tests are shallow relative to the plan; key rollback/fault-injection tests are missing.
10. Docs/tracker marking MIT-031/034/041/043 done is premature.

Recommended status:

```text
MIT-034: partial
MIT-031: partial
MIT-041: partial
MIT-043: partial
Do not close as DONE yet.
```

---

# What was fixed well

## 1. Cancellation helper exists

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/util/CancellationSafe.kt

`CancellationSafe.rethrowIfCancellation(...)` and a cancellation-safe result wrapper were added. This is a useful primitive.

Problem: the helper is not enough unless the codebase is forced to use it and `runCatching` is banned or wrapped.

## 2. Transaction runner exists

Sources:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/transaction/DomainTransactionRunner.kt
- https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/data/database/RoomDomainTransactionRunner.kt
- https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/transaction/TransactionContext.kt

This is a good start.

Problem: most migrated production paths still use direct `database.withTransaction`, not the new runner. So the new transaction context/correlation model is not actually the shared mutation model yet.

## 3. Receipt required-review path improved

In `ReceiptLifecycleCoordinator.processReceiptInput`, the new receipt insert, event, and required `PendingReview` insert are inside one transaction.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

This directly improves MIT-041 for that one path.

## 4. Some recurring state/event writes are transactional

Examples:
- `updateOccurrenceStatus`
- `markReminderSent`
- `markReminderFailed`
- `dismissReminderDelivery`
- `snoozeReminderDelivery`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

Good progress.

## 5. `getDueReminders()` hidden write was split

`getDueReminders()` is now read-only, and stale recovery moved to `recoverStaleClaimedDeliveries()` / `recoverAndGetDueReminders()`.

Good direction.

---

# Blocking issues

## BLOCKER 1 — `LegacyDataConsistencyChecker` always returns a zeroed report

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyChecker.kt

`runConsistencyCheck()` does the actual work inside `measureTimeMillis`, builds a local report and emits diagnostics, but after timing it returns:

```text
ConsistencyReport(receiptsWithoutEvent = 0, pendingReviewsWithoutReceipt = 0, ...)
```

So callers always get zeros even if inconsistencies were found.

Impact:
- PR9 legacy consistency checker is not trustworthy.
- Any UI/worker/report using the returned value will claim clean state.
- MIT-031/MIT-041/MIT-043 repair/surfacing acceptance is not met.

Required fix:
- Store the computed report outside the timing lambda.
- Return the actual report with `elapsedMs`.
- Add tests with fake DAO data proving non-zero findings are returned.

Example shape:

```kotlin
lateinit var report: ConsistencyReport
val elapsed = measureTimeMillis {
    report = ConsistencyReport(...)
    emitSummaryDiagnostic(report)
}
return report.copy(elapsedMs = elapsed)
```

Tests:
- one receipt without event returns `receiptsWithoutEvent = 1`;
- one orphan pending review returns `pendingReviewsWithoutReceipt = 1`;
- one occurrence without event returns `occurrencesWithoutEvent = 1`;
- summary diagnostic matches returned report.

---

## BLOCKER 2 — Cancellation policy is not actually closed

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

Problems:

### 1. Huge allowlist remains

`KNOWN_VIOLATIONS` includes many AI providers, repositories, workers, services, ViewModels, and even core files like:

- `ReceiptLifecycleCoordinator.kt`
- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `WorkerExecutionGuard.kt`
- `TransactionLifecycleCoordinator.kt`
- `NotificationIntakeWorker.kt`

This means MIT-034 cannot be considered done.

### 2. Allowlist has no owner/reason/expiry

The attached plan requires owner/reason/expiry allowlists. This implementation has only filenames and comments.

### 3. `runCatching` is not banned

`RecurringLifecycleCoordinator` still has a `runCatching { ... }.getOrNull()` call in a suspend path.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

If the called repository throws `CancellationException`, `runCatching` can trap it and convert it to null. That violates the non-negotiable invariant.

Required fixes:
- Replace suspend-path `runCatching` with `CancellationSafe.runCatchingCancellable`.
- Add a source guard for `runCatching` in suspend/worker/receiver paths.
- Convert `KNOWN_VIOLATIONS` to structured allowlist with expiry.
- Shrink core files out of the allowlist before closing MIT-034.

---

## BLOCKER 3 — `reconcilePlannedVsActual()` still has hidden writes

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

The method comments explicitly say it has write side effects and calls `generateOccurrences()` internally.

This directly contradicts the plan:

> split pure report and explicit write/apply command.

Current problem:
- method name is query/report-like;
- it generates rows;
- it performs hidden writes during read/report use;
- TODO still says it needs splitting.

Required fix:
- `ensureOccurrencesGeneratedForReconciliation(...)` — explicit write command.
- `calculatePlannedVsActualReport(...)` — pure read.
- Static guard should fail read-like methods calling write commands.
- Existing callers should be migrated.

MIT-043 hidden-write cleanup is not complete until this is fixed.

---

## BLOCKER 4 — Bank statement atomicity is incomplete

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

Good:
- receipt insert + initial receipt event are inside one transaction.
- each review + import item insert is inside a per-item transaction.

But the attached plan requires bank-statement receipt/review/status/event writes to be atomic or have a clearly defined partial-failure policy. Current code still has these problems:

### 1. Import run row is created before the receipt transaction

The `BankStatementImportRun` insert happens before receipt insert/event transaction. If later receipt insertion fails or validation fails after the run starts, a dangling RUNNING/FAILED run can exist without a statement receipt.

Maybe this is intended ledger behavior, but then it is not “receipt/import/review atomically commits.” It needs a documented policy and tests.

### 2. Pending reviews are processed in per-item transactions

This means the statement can partially commit reviews. That may be acceptable row-level import semantics, but it is not “whole statement atomicity.” The plan required defining rollback-whole vs row-level failure policy. The code does row-level partial commit but docs/closure should say so.

### 3. Final receipt status update and event are not in one transaction

Near the end, the code updates the receipt status, then writes the completion event outside an enclosing transaction. If event write fails after status update, state/event diverge.

Required fix:
- wrap final status update + `PROCESSING_COMPLETE` or `PROCESSING_FAILED` event in `database.withTransaction`.

### 4. Cancellation catch writes DB after CE

On cancellation, it counts items and finalizes the import run before rethrowing. That is a DB mutation during cancellation. It may be acceptable as bounded cancellation diagnostic, but the plan says cancellation must not become partial terminal state unless policy says so.

Required fix:
- define this as a cancellation diagnostic policy;
- make it bounded/non-cancellable if intentional;
- ensure write barrier/restore semantics;
- test cancellation after partial row commits.

### 5. `runCatching` in item processing path

The recurring lookup uses `runCatching`. Replace with cancellation-safe helper.

Required tests:
- failure after import run before receipt insert;
- failure after final status update before event;
- cancellation during per-item processing;
- review insert failure rolls back item row;
- partial row policy is explicit.

---

## BLOCKER 5 — Recurring lifecycle still swallows event failures in mutation paths

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

In `regenerateReminderDeliveriesForOccurrence(...)`, there are reminder delivery writes followed by lifecycle event inserts wrapped in `try/catch` that swallows non-cancellation exceptions as “best-effort event”.

This is called inside `unlinkExpenseFromOccurrenceDetailed(...)` transaction.

Impact:
- reminder delivery can be reopened/inserted;
- event insert can fail and be ignored;
- transaction still commits;
- state/event divergence remains.

This violates MIT-031/MIT-043.

Required fix:
- For critical lifecycle events, do not swallow event failures.
- Let event insert failure rollback the transaction.
- If some events are truly diagnostic, separate them from critical state-event pairs and document them as non-critical.

Also:
- `recoverStaleClaimedDeliveries()` writes without write barrier and without event.
- `claimReminderDelivery()` writes without event; if claim lifecycle must be audited, it still diverges.
- `markReminderFailed()` stores free-form `reason` in metadata; consider reason-code sanitization.

---

## BLOCKER 6 — `TransactionalEventWriter` does not enforce transactions

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/event/TransactionalEventWriter.kt

The interface is a marker only and explicitly says it is not runtime enforcement.

The plan expected:
- transaction context;
- rejection outside transaction;
- idempotency/correlation;
- before/after snapshots;
- event writer as legal event path.

Current state:
- direct DAO inserts are still allowed from many approved files;
- event writers do not require `TransactionContext`;
- `DomainTransactionRunner` is not widely integrated;
- static guard is the main protection, and it has a broad allowlist.

Required fix:
- Add writer methods that require `TransactionContext`.
- Event writer should not expose context-free critical writes.
- Direct DAO insert allowlist should shrink to writer implementations only.
- Coordinators should use `DomainTransactionRunner`.

---

## BLOCKER 7 — Direct event DAO guard has an overly broad allowlist

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

Problems:
- allowlist includes many repositories marked “to be migrated”;
- no expiry dates;
- duplicate `RecurringLifecycleEventWriter.kt`;
- includes broad names like `eventDao`, which can false-positive/false-negative;
- includes `pendingReviewDao` in event guard, but approval is filename-based.

This is a useful guardrail, not a final enforcement model.

Required fix:
- structured allowlist: file/rule/owner/reason/issue/expiry;
- shrink direct event insert permissions to approved writer/coordinator files;
- add negative fixtures;
- add guard for `PendingReviewDao.insert` outside approved review coordinator, not mixed with event DAO names.

---

## BLOCKER 8 — Side-effect evidence is not a real outbox

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitSideEffectEvidenceService.kt

Good:
- records diagnostic event after side-effect runner result;
- uses sanitized error class.

Limitations:
- no durable outbox table;
- no retry policy;
- if diagnostic event write fails, evidence can be lost;
- `runWithEvidence()` can fail after side effects completed because evidence write fails;
- `runBestEffortWithEvidence()` catches runner failure, then tries evidence write; if evidence write fails, that can still propagate.

This is evidence logging, not the plan’s full post-commit outbox.

Required fix:
- either rename scope to “evidence logger” and do not close MIT-075-like acceptance;
- or implement an outbox/ledger with durable request, status, retry policy, and failure recording.

---

## BLOCKER 9 — Tests are much weaker than the plan requires

Examples:

### `DomainTransactionRunnerTest`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/e56a7a6cf27c394e6fb6e995ac012391ef4b1bf7/app/src/test/java/com/yourname/expensetracker/data/database/DomainTransactionRunnerTest.kt

The test itself says real Room rollback is not verified there. It mostly tests context construction and a fake runner.

Missing:
- state insert then event failure rolls back;
- event insert then state failure rolls back;
- cancellation inside transaction rolls back;
- real in-memory Room transaction test.

### Cancellation guard

It has huge allowlist and regex scanning. Good start, but not proof of zero unsafe cancellation.

### Missing fault-injection tests

The attached plan requires:
- receipt insert then review failure rollback;
- bank review failure rollback/row failure policy;
- recurring projection failure rollback;
- cancellation during projection rollback;
- post-commit failure evidence;
- direct event DAO negative fixture;
- hidden write guard.

These are not visible in the commit.

---

# MIT-by-MIT status

## MIT-034 — cancellation propagation

Status: **partial**.

Fixed:
- helper added;
- some catch blocks improved.

Not fixed:
- huge allowlist;
- `runCatching` still exists in suspend path;
- no full ban on unsafe patterns;
- many pipeline files still allowlisted.

Do not close.

## MIT-031 — state/event atomicity

Status: **partial**.

Fixed:
- some Room transactions added;
- direct event insert guard added.

Not fixed:
- event writer not transaction-enforced;
- event failures still swallowed in recurring paths;
- broad direct DAO insert allowlist;
- final status/event split in bank statement path.

Do not close.

## MIT-041 — receipt/OCR/bank-statement review atomicity

Status: **partial**.

Fixed:
- one receipt path now inserts required review in same transaction.

Not fixed:
- bank statement is not fully atomic;
- final status/event split;
- import run can outlive failed receipt;
- partial row policy not fully documented/test-proven;
- legacy email receipt paths still direct.

Do not close.

## MIT-043 — recurring/reminder hidden writes and atomicity

Status: **partial**.

Fixed:
- several reminder state+event transactions added;
- `getDueReminders()` read-only split started.

Not fixed:
- `reconcilePlannedVsActual()` still writes;
- stale recovery write lacks barrier/event;
- reminder regeneration swallows event failures;
- projection/generation fault tests missing.

Do not close.

---

# Recommended next PRs

Do not continue with more broad “all PRs done” commits. Split the remaining work.

## Atomicity PR11 — fix obvious regressions

1. Fix `LegacyDataConsistencyChecker` returning zero report.
2. Replace `runCatching` in `BankStatementLifecycleProcessor`/recurring paths.
3. Wrap bank-statement final status update + event in one transaction.
4. Add tests for these three.

## Atomicity PR12 — cancellation guard hardening

1. Ban `runCatching` in suspend/worker/receiver paths.
2. Convert `KNOWN_VIOLATIONS` to structured owner/reason/expiry allowlist.
3. Remove core pipeline files from allowlist.
4. Add negative fixtures.

## Atomicity PR13 — recurring hidden write cleanup

1. Split `reconcilePlannedVsActual()` into explicit generate command + pure report.
2. Add write barrier/event to `recoverStaleClaimedDeliveries()` or document as non-evented diagnostic command.
3. Make reminder regeneration event failures rollback critical state.
4. Add projection/generation rollback tests.

## Atomicity PR14 — bank statement atomicity

1. Define policy: whole-statement rollback vs row-level partial.
2. If row-level partial, document and test.
3. Ensure every row state + item ledger + event pair is transactional.
4. Make cancellation terminal run update bounded and policy-backed.

## Atomicity PR15 — transaction/event infrastructure integration

1. Make critical event writer require `TransactionContext`.
2. Use `DomainTransactionRunner` in receipt/bank/recurring coordinators.
3. Shrink direct DAO insert allowlist.
4. Add runtime/context tests.

## Atomicity PR16 — post-commit side-effect outbox

1. Decide whether evidence logger is enough.
2. If not, add real outbox/ledger.
3. Add retry/no-retry policy.
4. Add failure tests.

---

# Final verdict

This commit is a **good first atomicity foundation**, not a full 10-PR completion.

It should be documented as:

```text
Cancellation/Atomicity foundation landed:
- helper
- transaction runner scaffold
- partial receipt and recurring atomicity
- baseline guards
- side-effect evidence scaffold

Remaining:
- full cancellation closure
- full bank-statement atomicity
- recurring hidden write cleanup
- transaction-scoped event enforcement
- real rollback/fault-injection tests
```

Do **not** mark MIT-031, MIT-034, MIT-041, or MIT-043 fully done yet.