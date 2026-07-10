# Deep Review — Atomicity / Cancellation / Event Consistency

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/c88964efb401733494e66eae8037b68fe921ccfe

Relevant sources:
- Latest commit summary: https://github.com/panospao7/Cost-agregator/commit/c88964efb401733494e66eae8037b68fe921ccfe
- `BankStatementLifecycleProcessor.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- `TransactionContext.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/main/java/com/yourname/expensetracker/domain/transaction/TransactionContext.kt
- `ReceiptLifecycleEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt
- `TransactionLifecycleEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt
- `DirectEventDaoInsertGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt
- `CancellationSafetyArchitectureGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/c88964efb401733494e66eae8037b68fe921ccfe/docs/analyses%20and%20debug%20master/MASTER_ISSUE_TRACKER.md
- Actions page: https://github.com/panospao7/Cost-agregator/actions

Static review only. I did not run Gradle locally.

---

# Executive verdict

`c88964e` is a **good PR20 correction**. It fixes several concrete problems from the prior review:

- Bank-statement cancellation cleanup no longer rethrows cleanup `CancellationException`; it catches `Throwable`, adds it as suppressed, logs, then rethrows the original cancellation.
- `statementReceiptId` is tracked outside the try path.
- Unexpected bank-statement failure after receipt creation now tries to finalize the import run and write `PROCESSING_FAILED` in one transaction.
- Context-free receipt/transaction event writer APIs are now `DeprecationLevel.ERROR`.
- Several callers were migrated to `write(context, event)`.
- Docs no longer close MIT-031/MIT-041 as DONE. They now say `NEAR-COMPLETE`, while MIT-034/043/075 remain partial.

That is the correct direction.

However, I would **not close MIT-031 or MIT-041 yet**.

Recommended current status:

```text
MIT-031: NEAR-COMPLETE, not DONE
MIT-041: NEAR-COMPLETE, not DONE
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
```

---

# What is fixed well

## 1. Bank cancellation cleanup no longer masks original cancellation

Previous issue:

```kotlin
catch (finalizeError: Exception) {
    CancellationSafe.rethrowIfCancellation(finalizeError)
}
```

If cleanup timed out, the cleanup `TimeoutCancellationException` could replace the original cancellation.

Latest diff changes this to:

```kotlin
catch (cleanupError: Throwable) {
    cancellation.addSuppressed(cleanupError)
    Timber.w(cleanupError, ...)
}
throw cancellation
```

This is the correct cancellation behavior.

Status: **fixed**.

---

## 2. Unexpected bank failure after receipt creation now writes failure event atomically

Latest code tracks:

```kotlin
var statementReceiptId: Long? = null
```

and if an unexpected failure happens after receipt creation, it does:

```kotlin
transactionRunner.runInTransaction(...) {
    bankStatementImportRunDao.finalize(...)
    receiptLifecycleEventWriter.write(context, PROCESSING_FAILED)
}
```

This fixes the previous “run failed but no receipt failure event” divergence for the receipt-created path.

Status: **mostly fixed**.

Remaining caveat: if that fallback finalization transaction itself fails, it is only logged to Timber. See blocker below.

---

## 3. Event writer APIs are stricter

`ReceiptLifecycleEventWriter.write(event)` and `TransactionLifecycleEventWriter.write(event)` are now:

```kotlin
@Deprecated(..., level = DeprecationLevel.ERROR)
```

This blocks normal source usage of the context-free API.

Status: **improved**.

Remaining caveat: the deprecated method still exists and internally suppresses the deprecation error in the implementation. Also, callers can still manually construct `TransactionContext`.

---

## 4. Docs/status are more honest

`MASTER_ISSUE_TRACKER.md` now says:

```text
MIT-031/041 NEAR-COMPLETE
MIT-034/043/075 PARTIAL
```

This is better than prematurely closing MITs.

Status: **fixed**.

---

# Remaining blockers / high-impact issues

## BLOCKER 1 — CI for latest `c88964e` is not visibly green

The commit message says “BUILD SUCCESSFUL, guard tests PASS,” but the public Actions page does not show a visible run for `c88964e` yet. The latest visible runs on the Actions page still show failures for prior commits like `b1ad7bc`, `5be39ae`, and `bffc429`.

Source: https://github.com/panospao7/Cost-agregator/actions

Required before closing anything:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

If you ran locally, commit the exact command output or make CI show green.

Status: **release blocker until verified**.

---

## BLOCKER 2 — `TransactionContext` is still forgeable inside the app module

`TransactionContext` now has an `internal constructor` and `TransactionToken`, but Kotlin `internal` is visible to the whole Gradle module. Many app files can still manually create:

```kotlin
TransactionContext(
    correlationId = UUID.randomUUID().toString(),
    occurredAt = System.currentTimeMillis()
)
```

The latest commit actually does this in several places:

- `GroupTransactionCoordinator`
- `NotificationProcessingPipeline`
- `WarrantyTrackerRepository`
- `ReceiptLinkService`

This satisfies the new function signature, but it does **not** prove the event write happened inside `DomainTransactionRunner`.

So this is not true runtime enforcement of transaction-scoped event writing. It is mostly API pressure plus documentation.

Required fix options:

### Option A — stricter architecture guard

Add a guard that fails manual `TransactionContext(` construction outside:

- `RoomDomainTransactionRunner`
- test fakes
- migrations/repair code with expiry

Then all callers must obtain `ctx` from `DomainTransactionRunner`.

### Option B — move token construction to separate module

Harder, but stronger. Put transaction infrastructure in a module where `internal` is not visible to app callers.

### Option C — accept static-only enforcement

Then docs must say:

```text
TransactionContext is statically enforced by guards, not runtime-enforced.
```

Until this is fixed or documented, MIT-031 should stay **NEAR-COMPLETE**.

---

## BLOCKER 3 — some migrated callers create fake contexts instead of using `DomainTransactionRunner`

The latest commit changed context-free writer calls by adding manual `TransactionContext(...)`.

Example from diff:

```kotlin
transactionLifecycleEventWriter.write(
    TransactionContext(
        correlationId = UUID.randomUUID().toString(),
        occurredAt = System.currentTimeMillis()
    ),
    event
)
```

This avoids `DeprecationLevel.ERROR`, but does not guarantee atomicity with surrounding state changes unless the call is actually inside a DB transaction.

This is especially concerning in:

- `NotificationProcessingPipeline`
- `WarrantyTrackerRepository`
- `ReceiptLinkService`
- `GroupTransactionCoordinator`

Required:

- verify each manual context call is inside a transaction;
- better: replace them with `transactionRunner.runInTransaction { ctx -> writer.write(ctx, event) }`;
- add static guard banning `TransactionContext(` outside transaction infrastructure.

---

## BLOCKER 4 — deprecated context-free writer methods still exist

Even though `DeprecationLevel.ERROR` blocks normal source calls, the methods still exist:

```kotlin
suspend fun write(event: ReceiptLifecycleEvent)
```

and implementation suppresses the error:

```kotlin
@Suppress("DEPRECATION_ERROR")
override suspend fun write(event: ReceiptLifecycleEvent) { ... }
```

This is acceptable short-term for binary/source compatibility, but not final-grade enforcement.

Required:

- remove the method completely in a later cleanup; or
- add static guard that fails any production call to `.write(event)` without a `TransactionContext`.

---

## BLOCKER 5 — direct event DAO guard remains broad and weak

`DirectEventDaoInsertGuardTest` still uses a plain `setOf(...)` allowlist with comments, not structured data.

Problems:

- no enforced owner/reason/expiry fields;
- duplicate `RecurringLifecycleEventWriter.kt`;
- many legacy repositories still approved:
  - `ReceiptRepository.kt`
  - `NotificationRepository.kt`
  - `ReviewQueueRepository.kt`
  - `RecurringExpenseRepository.kt`
  - `ManualRecurringExpenseRepository.kt`
  - `ExpenseRepository.kt`
  - `BankApiIntegration.kt`
- `pendingReviewDao` is mixed into an event DAO insert guard, which makes the guard semantically broad and noisy.

Required:

```kotlin
data class DirectEventDaoAllowlistEntry(
    val fileName: String,
    val rule: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Add tests for:

- expired allowlist failure;
- duplicate allowlist failure;
- repository direct event insert failure;
- approved writer pass.

Until this is done, MIT-031 should not close.

---

## BLOCKER 6 — non-cancellation fallback finalization can swallow cancellation during cleanup

In the non-cancellation `catch (e: Exception)` path, the fallback finalization transaction is wrapped in:

```kotlin
try {
    transactionRunner.runInTransaction { ... }
} catch (finalizeError: Exception) {
    Timber.w(finalizeError, ...)
}
```

If app/job cancellation happens during this cleanup transaction, `finalizeError` may be a `CancellationException`; this catch would log it and continue returning `Result.failure(e)` for the original error.

That converts a later cancellation into non-cancellation failure semantics.

Required:

```kotlin
catch (finalizeError: Exception) {
    if (finalizeError is CancellationException) throw finalizeError
    Timber.w(finalizeError, ...)
}
```

or if this cleanup is intentionally non-cancellable, wrap it in `NonCancellable` and document it.

---

## BLOCKER 7 — bank cancellation finalization is run-ledger only

The cancellation cleanup finalizes the import run as `CANCELLED`, but does not appear to write a receipt lifecycle cancellation event even when `statementReceiptId` exists.

This may be okay if the policy is:

```text
Cancellation finalization is import-run-ledger-only.
```

But then docs must not claim that every bank run terminal transition has a receipt lifecycle event.

If you want full run/receipt/event consistency:

```kotlin
transactionRunner.runInTransaction {
    bankStatementImportRunDao.finalize(CANCELLED)
    if (statementReceiptId != null) {
        receiptLifecycleEventWriter.write(ctx, PROCESSING_CANCELLED)
    }
}
```

---

## BLOCKER 8 — skipped/failed bank item policy still needs precise docs/tests

Validation-skipped rows are inserted as `BankStatementImportItem` ledger rows. They do not have receipt lifecycle events because no receipt/review exists for that item.

This is acceptable if documented as:

```text
BankStatementImportItem is the authoritative per-item audit ledger before receipt/review creation.
Receipt lifecycle events begin once receipt/review state exists.
```

Need tests:

- invalid amount creates skipped item ledger;
- skipped item reason is sanitized;
- no receipt lifecycle event is expected for no-receipt skipped items.

---

# MIT status

## MIT-031 — state/event atomicity

Current status: **NEAR-COMPLETE, not DONE**.

Fixed:
- event writer APIs require context in normal calls;
- many critical paths use `DomainTransactionRunner`;
- docs no longer close it.

Still open:
- manual `TransactionContext` construction is allowed;
- deprecated writer methods still exist;
- direct event DAO guard is broad and weak;
- legacy repositories remain approved for direct event inserts.

## MIT-041 — receipt/bank atomicity

Current status: **NEAR-COMPLETE, not DONE**.

Fixed:
- bank `runId` issue fixed;
- cancellation cleanup no longer masks original CE;
- unexpected failure after receipt creation writes run failure + receipt event atomically.

Still open:
- CI not visibly green;
- non-cancellation cleanup can swallow cancellation;
- cancellation finalization lacks receipt event or explicit run-only policy;
- skipped/failed item ledger policy needs tests/docs.

## MIT-034 — cancellation

Current status: **PARTIAL**.

Reason:
- cancellation allowlist still huge;
- many non-core files remain exempt until 2026-12-31;
- core paths are better, but global cancellation closure is not done.

## MIT-043 — recurring

Current status: **PARTIAL**.

Reason:
- regeneration remains best-effort by design;
- DB uniqueness depends on MIT-033;
- stale recovery/event semantics need final policy.

## MIT-075 — side effects

Current status: **PARTIAL by design**.

Reason:
- evidence logger exists;
- no durable outbox/retry system.

---

# Recommended next PR: PR21 small cleanup

Do not do another huge atomicity PR. Do a focused cleanup.

## PR21 scope

1. Add static guard banning manual `TransactionContext(` outside `RoomDomainTransactionRunner` and tests.
2. Migrate the new manual-context callers to `DomainTransactionRunner`.
3. Add CE rethrow in non-cancellation bank failure finalization cleanup.
4. Document or implement receipt cancellation event for bank cancellation.
5. Convert direct event DAO allowlist to structured entries with expiry and remove duplicates.
6. Add tests for skipped bank item ledger policy.
7. Get visible green CI for latest commit.

## Required validation

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest
```

---

# Final verdict

`c88964e` is a good PR20 and fixes real problems. The branch is now much closer.

But the most important remaining problem is conceptual:

> You changed event writer APIs to require `TransactionContext`, but callers can still manually create `TransactionContext`, so the API does not yet prove the write happened inside `DomainTransactionRunner`.

Therefore:

```text
MIT-031: NEAR-COMPLETE
MIT-041: NEAR-COMPLETE
MIT-034/043/075: PARTIAL
```

Do not mark MIT-031 or MIT-041 DONE until PR21-style enforcement and green CI are in place.