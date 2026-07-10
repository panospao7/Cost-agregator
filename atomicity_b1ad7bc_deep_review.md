# Deep Review — Cancellation / Atomicity / Event Consistency

Latest commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6

Important previous commits:
- PR19 main cleanup: https://github.com/panospao7/Cost-agregator/commit/bffc429
- PR19 bank atomicity fix: https://github.com/panospao7/Cost-agregator/commit/ae2fc74
- PR18 docs: https://github.com/panospao7/Cost-agregator/commit/d1abaebd453ddf35e2a3e963d6988e25c32b775f
- PR11 hotfix: https://github.com/panospao7/Cost-agregator/commit/327bfb2b35117d13b9e678fc8470ecf9b89afdb8

Sources checked:
- `BankStatementLifecycleProcessor.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- `RecurringLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- `TransactionContext.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/main/java/com/yourname/expensetracker/domain/transaction/TransactionContext.kt
- `TransactionalEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/main/java/com/yourname/expensetracker/domain/event/TransactionalEventWriter.kt
- `ReceiptLifecycleEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt
- `DirectEventDaoInsertGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt
- `CancellationSafetyArchitectureGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
- GitHub Actions page: https://github.com/panospao7/Cost-agregator/actions

Static review only. I did not run Gradle locally.

---

# Executive verdict

The latest state is **much better** than PR1–10 and PR18.

Your PR19 work did fix several concrete issues:

- `importRunId` is now non-null in `BankStatementLifecycleProcessor`.
- Bank statement final success/failure run finalization is now inside a transaction with receipt lifecycle event.
- `LegacyDataConsistencyChecker` now tracks failed checks and reports `FAILED_RETRYABLE`.
- Retention target failures no longer use raw `Throwable.message`.
- Recurring stale recovery now has a transaction and a lifecycle event attempt.
- `reconcilePlannedVsActual()` is now `DeprecationLevel.ERROR`.
- MIT-034 / MIT-043 / MIT-075 are correctly still shown as partial.

But latest `b1ad7bc` is **docs-only** and closes MIT-031/MIT-041 as DONE while GitHub Actions shows the latest `b1ad7bc` workflow run failing. The Actions page also shows `bffc429` and `5be39ae` failing on the same branch.

That alone blocks “DONE.”

Recommended status:

```text
MIT-031: NEAR-COMPLETE, not DONE
MIT-041: NEAR-COMPLETE, not DONE
MIT-034: PARTIAL, as you wrote
MIT-043: PARTIAL, as you wrote
MIT-075: PARTIAL by design, as you wrote
```

---

# What is genuinely fixed

## 1. Bank statement nullable `runId` compile risk is mostly fixed

PR19 replaced nullable `runId!!` usage with non-null:

```kotlin
val importRunId = bankStatementImportRunDao.insert(...)
require(importRunId > 0)
runId = importRunId
```

Then subsequent item rows use `importRunId`.

This fixes the previous likely compile blocker.

Status: **fixed**.

---

## 2. Bank finalization is more atomic

Success path now does in one `transactionRunner.runInTransaction`:

- import run finalization;
- receipt status update to `REVIEW_CREATED`;
- `PROCESSING_COMPLETE` event.

Failure path now does in one transaction:

- import run finalization;
- `PROCESSING_FAILED` event.

Status: **much improved**.

Remaining caveats below.

---

## 3. Consistency checker summary diagnostic is fixed

`LegacyDataConsistencyChecker` now includes:

- `failedChecks`
- `failedCheckNames`
- `FAILED_RETRYABLE` outcome when subchecks fail.

Status: **fixed**.

---

## 4. Retention error messages are sanitized

`RetentionModule` now emits:

```kotlin
RETENTION_PURGE_FAILED:<ClassName>
```

instead of `Throwable.message`.

Status: **acceptable**.

Not perfect structure, but safe enough.

---

## 5. Recurring hidden write is reduced

`reconcilePlannedVsActual()` is now marked:

```kotlin
@Deprecated(... level = DeprecationLevel.ERROR)
```

and there are explicit methods:

- `ensureOccurrencesGeneratedForReconciliation`
- `calculatePlannedVsActualReport`

Status: **improved**.

---

# Current blockers

## BLOCKER 1 — CI is failing on latest commit

GitHub Actions shows latest run for `b1ad7bc` as **Failure** on branch `atomicity-pr19-final-correctness-cleanup`.

Relevant page:  
https://github.com/panospao7/Cost-agregator/actions

Visible rows show:
- `b1ad7bc` — Failure
- `5be39ae` — Failure
- `bffc429` — Failure

This blocks closing MITs as DONE.

Required:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Then fix the actual failing task/test. Commit `5be39ae` mentions two `generateOccurrences` tests were still failing after PR13b migration, so start there.

---

## BLOCKER 2 — cancellation finalization can still mask original cancellation

In `BankStatementLifecycleProcessor`, cancellation catch does:

```kotlin
catch (finalizeError: Exception) {
    CancellationSafe.rethrowIfCancellation(finalizeError)
    Timber.w(...)
}
```

But the cleanup uses:

```kotlin
withContext(NonCancellable) {
    withTimeout(2000L) { ... }
}
```

If the cleanup timeout fires, `withTimeout` throws `TimeoutCancellationException`, which is a `CancellationException`. Then `CancellationSafe.rethrowIfCancellation(finalizeError)` can throw the cleanup timeout instead of the original cancellation.

That violates the goal: “cancellation finalization never masks original CE.”

Required fix:

```kotlin
catch (finalizeError: Throwable) {
    // Never rethrow from cancellation cleanup.
    Timber.w(finalizeError, "Failed to finalize cancelled bank statement import run $rid")
}
throw cancellation
```

If you want to preserve info:

```kotlin
cancellation.addSuppressed(finalizeError)
throw cancellation
```

Tests needed:

- cleanup timeout still rethrows original cancellation;
- cleanup DAO failure still rethrows original cancellation.

---

## BLOCKER 3 — unexpected bank failure finalization still diverges from receipt lifecycle event

The normal success/failure finalization path is better. But the outer non-cancellation catch still does:

```kotlin
runId?.let { rid ->
    count items...
    bankStatementImportRunDao.finalize(... STATUS_FAILED ...)
}
Result.failure(e)
```

This fallback finalizes the import run but does **not** write a matching receipt lifecycle event, because `receiptId` is scoped inside the try path and not available in the catch.

So an unexpected exception after the statement receipt has been created can still leave:

```text
ImportRun = FAILED
Receipt lifecycle event = missing PROCESSING_FAILED
```

Required fix:

- keep `statementReceiptId: Long?` outside the try;
- in fallback catch, if receipt exists, finalize run + write `PROCESSING_FAILED` event in one `transactionRunner.runInTransaction`;
- if receipt does not exist, finalize run as ledger-only with documented policy.

---

## BLOCKER 4 — MIT-031 still has weak enforcement

`TransactionalEventWriter` is still a marker-only interface and explicitly says it is not runtime enforcement.

`TransactionContext` is a public data class and can be manually constructed.

`ReceiptLifecycleEventWriter` still exposes deprecated context-free:

```kotlin
suspend fun write(event: ReceiptLifecycleEvent)
```

and that method fabricates a `TransactionContext`.

So even if critical call sites are migrated, the architecture does not enforce “event writer cannot be called outside transaction context.”

Required if MIT-031 is truly DONE:

- remove context-free writer method, or make it `DeprecationLevel.ERROR`;
- make `TransactionContext` constructor internal or add internal transaction token;
- static guard should fail context-free `write(event)` calls;
- direct event DAO allowlist should shrink to real writers/coordinators only.

Current status: **near-complete, not done**.

---

## BLOCKER 5 — Direct event DAO guard is still broad

`DirectEventDaoInsertGuardTest` uses a plain `setOf` with comments. It includes many legacy repository-level event writers:

- `ReceiptRepository.kt`
- `NotificationRepository.kt`
- `ReviewQueueRepository.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- `ExpenseRepository.kt`
- `BankApiIntegration.kt`

It also has duplicate entries for `RecurringLifecycleEventWriter.kt`.

This is a guardrail, but not final architecture enforcement.

Required:

- structured allowlist object with `owner/reason/issue/expires`;
- duplicate allowlist test;
- expire repository-level direct inserts;
- migrate repository direct event inserts behind event writers/coordinators.

Until then, MIT-031 should be **near-complete**, not DONE.

---

## BLOCKER 6 — recurring stale recovery still swallows event failure

`recoverStaleClaimedDeliveries()` now runs in a transaction and attempts to write `STALE_DELIVERIES_RECOVERED`.

Good.

But it still catches and swallows event insert failure:

```kotlin
try {
    lifecycleEventDao.insert(...)
} catch (e: Exception) {
    CancellationSafe.rethrowIfCancellation(e)
    Timber.w(e, "Failed to write stale delivery recovery event")
}
```

That means delivery state recovery can commit without event.

If stale recovery is critical lifecycle state, this violates state/event atomicity.

Required options:

Option A — critical event:

```kotlin
// no catch; event failure rolls back recovery
lifecycleEventDao.insert(...)
```

Option B — operational diagnostic:

- do not call it lifecycle event;
- write durable diagnostic outside/inside transaction;
- document recovery as operational non-critical.

Current status: **MIT-043 partial is correct**.

---

## BLOCKER 7 — recurring regeneration is still best-effort by design

You already called this out. That is correct.

If best-effort is intentional, MIT-043 cannot be fully closed under the original atomicity invariants. It depends on:

- MIT-033 DB uniqueness;
- durable diagnostics for skipped regeneration windows;
- explicit product policy that partial regeneration is acceptable.

Current status: **MIT-043 partial is correct**.

---

## BLOCKER 8 — bank validation skipped rows are not transaction-scoped

Validation skip rows, e.g. invalid amount/currency/date, call:

```kotlin
bankStatementImportItemDao.insert(...)
```

directly, outside `transactionRunner`.

A single insert is DB-atomic, but it is not “all bank-statement paths transaction-scoped” as the docs claim.

This is acceptable only if docs say:

```text
Skipped/failed item ledger rows are the audit record and are atomic as single-row inserts.
Receipt lifecycle events exist only after receipt/review state exists.
```

Right now latest docs say MIT-041 is DONE because “all bank-statement + receipt paths transaction-scoped.” That is too broad.

---

# MIT status recommendation

## MIT-031 — State/event atomicity

Do **not** close as DONE yet.

Actual status:

```text
NEAR-COMPLETE
Critical receipt/bank paths mostly transaction-scoped.
But enforcement remains weak:
- marker-only TransactionalEventWriter
- public/forgeable TransactionContext
- deprecated context-free writer
- broad direct DAO allowlist
- CI failing
```

## MIT-041 — Receipt/review atomicity

Do **not** close as DONE yet.

Actual status:

```text
NEAR-COMPLETE
ReceiptLifecycleCoordinator and bank item review path much improved.
But:
- CI failing
- cancellation cleanup can mask CE
- unexpected failure fallback finalizes run without receipt event
- skipped/failed item ledger policy needs clearer docs/tests
```

## MIT-034 — Cancellation

Your partial status is correct.

```text
PARTIAL
99 allowlist entries means global cancellation safety is not closed.
Core worker/coordinator paths may be clean, but MIT remains partial.
```

## MIT-043 — Recurring

Your partial status is correct.

```text
PARTIAL
DB uniqueness depends on MIT-033.
Best-effort regeneration is intentional but not full atomicity.
Stale recovery still swallows lifecycle event failure.
```

## MIT-075 — Side effects

Your partial status is correct.

```text
PARTIAL
No durable outbox by architectural decision.
Evidence logger is not equivalent to retrying outbox.
```

---

# Recommended next PR: PR20

Do a small correction PR, not another broad rewrite.

## PR20 scope

1. Fix cancellation cleanup so original CE is never masked.
2. Add fallback failure finalization transaction with receipt `PROCESSING_FAILED` event when receipt exists.
3. Decide stale recovery event policy:
   - rollback if event fails, or
   - record durable operational diagnostic and document non-critical recovery.
4. Make `ReceiptLifecycleEventWriter.write(event)` `DeprecationLevel.ERROR` or remove it.
5. Convert direct event DAO allowlist to structured entries and remove duplicates.
6. Update docs:
   - MIT-031/MIT-041 = NEAR-COMPLETE until CI green and PR20 fixes land.
7. Fix CI failures, especially the mentioned recurring `generateOccurrences` tests.

## Required validation

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest
```

---

# Final verdict

Your own partial statuses for MIT-034, MIT-043, and MIT-075 are correct.

But latest `b1ad7bc` overcloses MIT-031 and MIT-041.

Final recommendation:

```text
Do not mark MIT-031/MIT-041 DONE yet.
Mark both NEAR-COMPLETE.
Fix PR20 issues and get CI green first.
```

The code is close, but “DONE” should require green CI plus no known atomicity/cancellation escape hatches in the critical paths.