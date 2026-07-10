# Cancellation / Atomicity / Event Consistency Deep Review — latest `d1abaeb`

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/d1abaebd453ddf35e2a3e963d6988e25c32b775f

Key commits reviewed:
- PR18 docs closure: https://github.com/panospao7/Cost-agregator/commit/d1abaebd453ddf35e2a3e963d6988e25c32b775f
- PR17 side-effect evidence/no-outbox decision: https://github.com/panospao7/Cost-agregator/commit/af223e9
- PR16 consistency checker fixes: https://github.com/panospao7/Cost-agregator/commit/9317f33
- PR15 bank-statement validation/events: https://github.com/panospao7/Cost-agregator/commit/4da9781
- PR14 recurring hidden-write split/event fixes: https://github.com/panospao7/Cost-agregator/commit/4280150
- PR13b transaction-runner migration: https://github.com/panospao7/Cost-agregator/commit/f175814
- PR13a transaction context/event-writer changes: https://github.com/panospao7/Cost-agregator/commit/061df25
- PR12b cancellation/runCatching cleanup: https://github.com/panospao7/Cost-agregator/commit/d9cac7f
- PR11 hotfix: https://github.com/panospao7/Cost-agregator/commit/327bfb2
- PR1–10 foundation: https://github.com/panospao7/Cost-agregator/commit/e56a7a6

Static review only. I did not run Gradle locally.

---

# Executive verdict

The branch is **substantially better than PR1–10** and has real progress:

- `LegacyDataConsistencyChecker` no longer returns a zero report.
- Consistency checker now tracks scanned vs invalid counts and failed check metadata.
- `runCatching` was replaced in several critical paths.
- `DomainTransactionRunner` and `TransactionContext` were added.
- Receipt and bank statement paths now use transaction context in several key places.
- Bank statement item validation improved.
- Bank statement `REVIEW_CREATED` event is written with the item/review transaction.
- Recurring reconciliation was split into explicit write + pure read.
- Several recurring reminder event writes are now transaction-wrapped.
- Side-effect failure messages were sanitized.
- Docs correctly describe no durable outbox for MIT-075.

But I would **not close MIT-031 / MIT-034 / MIT-041 / MIT-043 as fully done yet**.

Main remaining issues:

1. **Probable compile blocker in `BankStatementLifecycleProcessor`: nullable `runId` is passed where `Long` is required.**
2. Bank statement run finalization can diverge from receipt final status/event.
3. Cancellation catch in bank statement can convert cancellation into DB failure if finalization throws.
4. Transaction-scoped event enforcement is still weak because context-free deprecated writers remain and `TransactionalEventWriter` is still marker-only.
5. Cancellation guard still has a huge allowlist.
6. Direct event DAO guard still has a broad, non-structured allowlist.
7. Recurring stale recovery still mutates without write barrier/event/diagnostic.
8. Deprecated `reconcilePlannedVsActual()` still performs hidden writes.
9. Recurring reminder regeneration now avoids state/event divergence per window, but swallows failures and can partially restore reminders without durable diagnostic.
10. Consistency checker reports failed subchecks in returned data but not in the summary diagnostic outcome/metadata.
11. Retention targets still put raw `Throwable.message` into `RetentionPurgeResult`.
12. Post-commit side-effect implementation is evidence-only, not an outbox; docs admit this, so MIT-075 should remain partial.

Recommended status:

```text
PR11–PR18: strong progress / near-complete foundation.
MIT-031: NEAR-COMPLETE but not closed.
MIT-034: PARTIAL.
MIT-041: PARTIAL → NEAR-COMPLETE but not closed.
MIT-043: PARTIAL.
MIT-075: PARTIAL by design.
```

---

# What is fixed well

## 1. PR11 fixed the zero-report consistency bug

`LegacyDataConsistencyChecker.runConsistencyCheck()` now captures the computed report and returns it with elapsed time.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyChecker.kt

Status: **fixed**.

## 2. PR16 improved consistency checker counts

The checker now has:

```kotlin
ConsistencyCheckResult(
    scanned,
    invalid,
    failed,
    failureCode,
    errorClass
)
```

and `ConsistencyReport` includes:

- `failedChecks`
- `failedCheckNames`
- per-check result objects
- `missingMerchants`

This fixes the old “invalid count used as scanned count” problem.

Status: **mostly fixed**.

Remaining issue: failed subchecks are not surfaced strongly enough in the summary diagnostic. More below.

## 3. PR12b replaced many unsafe `runCatching` uses

PR12b replaced `runCatching` in:

- `RetentionModule`
- `OperationRunRecorder`
- `CompositeOperationRunRecorder`

and cleaned several catch patterns in:

- `ReceiptLifecycleCoordinator`
- `WorkerExecutionGuard`
- `RecurringLifecycleCoordinator`

Source:  
https://github.com/panospao7/Cost-agregator/commit/d9cac7f

Status: **good progress**.

Remaining issue: cancellation guard still has a large allowlist.

## 4. PR13 added TransactionContext-aware receipt/transaction event writers

`ReceiptLifecycleEventWriter` now has:

```kotlin
suspend fun write(context: TransactionContext, event: ReceiptLifecycleEvent)
```

and writes transaction metadata into event metadata.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt

Status: **good direction**.

Remaining issue: deprecated context-free write remains.

## 5. Bank statement item review transaction improved

For normal item creation, PR15 now writes in one transaction:

- `PendingReview`
- `BankStatementImportItem`
- receipt `REVIEW_CREATED` lifecycle event

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

Status: **real improvement**.

## 6. Recurring reconciliation split was added

`RecurringLifecycleCoordinator` now has:

- `ensureOccurrencesGeneratedForReconciliation(...)` — explicit write command
- `calculatePlannedVsActualReport(...)` — pure read

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

Status: **good**, but deprecated hidden-write method still remains.

## 7. Side-effect diagnostic messages were sanitized

PR17 replaced raw exception messages in several side-effect paths with bounded reason codes and error classes.

Source:  
https://github.com/panospao7/Cost-agregator/commit/af223e9

Status: **good**.

---

# Blocking / high-impact issues

## BLOCKER 1 — probable compile blocker: nullable `runId` passed to `Long`

In `BankStatementLifecycleProcessor`, `runId` is declared nullable:

```kotlin
var runId: Long? = null
```

But later code passes it to APIs/entities that require non-null `Long`.

Examples in the current raw source:

```kotlin
BankStatementImportItem(
    runId = runId,
    ...
)
```

and:

```kotlin
bankStatementImportItemDao.countByRunAndStatus(runId, ...)
```

But `BankStatementImportItem.runId` is:

```kotlin
val runId: Long
```

and DAO methods take:

```kotlin
runId: Long
```

Sources:
- Processor: https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- Entity: https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankStatementImportItem.kt
- DAO: https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/data/database/dao/BankStatementImportItemDao.kt

Unless the checked-out code differs from GitHub raw, this should fail Kotlin compilation.

### Required fix

After creating the import run, assign a non-null local:

```kotlin
val importRunId = bankStatementImportRunDao.insert(...)
require(importRunId > 0) { "Failed to create bank statement import run" }
runId = importRunId
```

Then use `importRunId` for all subsequent non-null operations:

```kotlin
BankStatementImportItem(runId = importRunId, ...)
bankStatementImportItemDao.countByRunAndStatus(importRunId, ...)
bankStatementImportRunDao.finalize(runId = importRunId, ...)
```

Keep nullable `runId` only for outer catch/finalization.

### Required validation

Run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
```

Do not mark PR18 complete until this compiles.

---

## BLOCKER 2 — bank import run finalization can diverge from receipt final status/event

In current bank-statement flow:

1. counts are computed;
2. `bankStatementImportRunDao.finalize(...)` runs;
3. only after that, `transactionRunner.runInTransaction { receipt status + PROCESSING_COMPLETE event }` runs.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

If final status/event transaction fails after import run finalization, the run says completed but the receipt status/event is not completed.

This still violates state/run/event consistency.

### Required fix

Move import run finalization into the same transaction as final receipt status/event.

For success:

```kotlin
transactionRunner.runInTransaction(
    operationId = "bank_statement.finalize_success",
    ...
) { ctx ->
    bankStatementImportRunDao.finalize(...)
    scannedReceiptDao.update(...)
    receiptLifecycleEventWriter.write(ctx, PROCESSING_COMPLETE)
}
```

For failure:

```kotlin
transactionRunner.runInTransaction(
    operationId = "bank_statement.finalize_failure",
    ...
) { ctx ->
    bankStatementImportRunDao.finalize(...)
    scannedReceiptDao.updateStatus(...FAILED or REVIEW_CREATED_WITH_ERRORS if policy says)
    receiptLifecycleEventWriter.write(ctx, PROCESSING_FAILED)
}
```

If receipt status should not change on partial failure, document that explicitly, but still atomically tie run terminal state and event.

### Required tests

- failure after run finalize before event rolls back run finalize;
- event insert failure leaves run not finalized;
- success path run/status/event commit together;
- failure path run/event commit together.

---

## BLOCKER 3 — cancellation handler can convert cancellation into DB failure

Current cancellation catch:

```kotlin
if (e is CancellationException) {
    runId?.let { rid ->
        ... count rows ...
        bankStatementImportRunDao.finalize(...)
    }
    throw e
}
```

If any count/finalize DAO call throws, the original `CancellationException` is replaced by that DB exception.

That violates MIT-034: cancellation must not be converted into a false failure.

### Required fix

Use bounded best-effort diagnostic/finalization around cancellation, never replacing the original CE.

Example:

```kotlin
if (e is CancellationException) {
    runId?.let { rid ->
        try {
            withContext(NonCancellable) {
                withTimeout(CANCELLATION_FINALIZE_TIMEOUT_MS) {
                    finalizeCancelledRun(rid)
                }
            }
        } catch (finalizeError: Exception) {
            CancellationSafe.rethrowIfCancellation(finalizeError) // optional: but beware replacing CE
            Timber.w(finalizeError, "Failed to finalize cancelled bank statement run")
            // record fallback diagnostic if available
        }
    }
    throw e
}
```

Do **not** let finalize failure replace the original cancellation.

### Required tests

- cancellation with finalize success rethrows CE;
- cancellation with finalize DAO failure still rethrows original CE;
- cancellation with count DAO failure still rethrows original CE;
- fallback diagnostic records finalize failure.

---

## BLOCKER 4 — transaction-scoped event enforcement is still weak

`TransactionalEventWriter` is still a marker:

```kotlin
interface TransactionalEventWriter
```

Its docs still say it is a documentation/static contract, not runtime enforcement.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/event/TransactionalEventWriter.kt

Also, `ReceiptLifecycleEventWriter` still exposes deprecated:

```kotlin
suspend fun write(event: ReceiptLifecycleEvent)
```

which fabricates a `TransactionContext` outside a real `DomainTransactionRunner`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt

This means transaction context can still be forged and event writer can still be used outside an actual transaction.

### Required fix

For critical lifecycle event writers:

1. Remove or restrict deprecated context-free write methods.
2. Make `TransactionContext` carry an internal token created only by `DomainTransactionRunner`.
3. Make event writers validate token or use type visibility to prevent external context creation.
4. Static guard should fail calls to deprecated context-free writer.
5. Direct event DAO allowlist should shrink to writer implementations and repair tools only.

### Required tests

- context-free `write(event)` usage fixture fails;
- `TransactionContext` cannot be manually created outside transaction package/module, or writer rejects it;
- critical event writer called outside runner fails or is impossible.

---

## BLOCKER 5 — direct event DAO guard still has broad and weak allowlist

`DirectEventDaoInsertGuardTest.APPROVED_FILES` is a plain `setOf(...)` with comments. It includes:

- repositories marked “to be migrated”
- `BankApiIntegration.kt`
- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `ExpenseRepository.kt`
- `WorkerExecutionGuard.kt`
- duplicated `RecurringLifecycleEventWriter.kt`
- broad names like `eventDao`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

This is not final-grade enforcement.

### Required fix

Replace with structured entries:

```kotlin
data class DirectEventAllowlistEntry(
    val fileName: String,
    val rule: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Then:

- remove duplicates;
- add expiry validation;
- shrink list to writer/coordinator files only;
- move repository-level direct inserts behind coordinators or writer APIs;
- add negative fixtures.

### Required tests

- expired direct-event allowlist fails;
- repository direct event insert fixture fails;
- approved writer fixture passes;
- duplicate allowlist entries fail.

---

## BLOCKER 6 — cancellation guard still has huge allowlist

`CancellationSafetyArchitectureGuardTest` now uses structured entries, which is better, but the allowlist is still huge. It includes many service, repository, domain, worker, and UI files, all expiring on `2026-12-31`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

MIT-034 cannot be considered closed while dozens of files remain exempt from CE-safety enforcement.

### Required fix

Split status honestly:

```text
MIT-034 core worker/domain paths: mostly fixed.
MIT-034 global app cancellation closure: still partial.
```

Then continue shrinking allowlist by priority:

1. worker/receiver/repository/domain mutation paths;
2. side-effect/diagnostic infrastructure;
3. AI/network providers;
4. UI ViewModels.

Also add a guard for raw `runCatching` in suspend paths if not already fully enforced.

### Required tests

- raw `runCatching` in suspend fixture fails;
- broad catch without CE rethrow fails;
- `launch { catch(Exception) }` in non-UI background path fails;
- allowlist expiry is enforced.

---

## BLOCKER 7 — recurring stale recovery still writes silently

`recoverStaleClaimedDeliveries()` still mutates DB and only logs Timber:

```kotlin
val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(...)
if (recovered > 0) Timber.d(...)
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

Missing:

- write barrier check;
- transaction runner;
- lifecycle event or diagnostic;
- affected-count return;
- cancellation-safe bounded diagnostic policy.

### Required fix

Make it explicit and evented/diagnostic:

```kotlin
suspend fun recoverStaleClaimedDeliveries(...): Int {
    writeBarrier.checkWritesAllowed(...)
    return transactionRunner.runInTransaction(...) { ctx ->
        val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(...)
        if (recovered > 0) recurringEventWriter.writeCritical(ctx, ...)
        recovered
    }
}
```

If you decide it is operational-only, record a durable diagnostic instead of lifecycle event.

### Required tests

- recovery writes event/diagnostic;
- recovery blocked by write barrier;
- event failure rolls back recovery if critical;
- cancellation rethrows.

---

## BLOCKER 8 — deprecated `reconcilePlannedVsActual()` still performs hidden writes

PR14 added the correct split, but the old method still exists and still calls generation.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

Even if there are “zero callers” now, the method remains a public footgun.

### Required fix

Choose one:

1. Delete the deprecated method.
2. Make it call the explicit write method and pure report, but rename to command-like name.
3. Keep it only temporarily with static guard forbidding new call sites and expiry.

Recommended: remove it or make it internal/test-only.

### Required tests

- no production call sites to deprecated method;
- hidden-write guard catches any read/report method that calls write operations.

---

## BLOCKER 9 — recurring reminder regeneration swallows partial failures

PR14 improved state/event atomicity **per window**: reopen/insert and event now occur inside transactions.

But the method catches exceptions per window and continues:

```kotlin
catch (e: Exception) {
    CancellationSafe.rethrowIfCancellation(e)
    // If either reopen or event fails, skip this window entirely
}
```

This prevents state/event divergence for that window, but it can still partially regenerate reminders and silently skip others without durable diagnostic.

If `unlinkExpenseFromOccurrenceDetailed()` is expected to atomically restore all future reminders, this is not enough.

### Required fix

Define policy:

- **All-or-nothing reminder regeneration:** let exception escape and roll back unlink transaction.
- **Best-effort per-window regeneration:** record durable diagnostic for skipped windows and document partial behavior.

Given the original plan’s atomicity goals, I recommend all-or-nothing for critical reminder restoration.

### Required tests

- event failure in one window rolls back entire unlink if all-or-nothing;
- or, if best-effort, skipped window diagnostic is durable.

---

## BLOCKER 10 — consistency checker summary diagnostic ignores failed checks

`ConsistencyReport` has `failedChecks` and `failedCheckNames`, but `emitSummaryDiagnostic(report)` metadata only includes inconsistency counts and `totalItemsChecked`.

It determines outcome by:

```kotlin
hasIssues = counts > 0
```

So a failed check with zero detected inconsistencies can emit `COMPLETED`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyChecker.kt

### Required fix

Add failed-check fields to summary metadata and outcome:

```kotlin
.put("failedChecks", report.failedChecks)
.put("failedCheckNames", report.failedCheckNames.joinToString(","))
```

Outcome:

```kotlin
when {
    report.failedChecks > 0 -> EventOutcome.FAILED_RETRYABLE or NEEDS_REVIEW
    hasIssues -> EventOutcome.NEEDS_REVIEW
    else -> EventOutcome.COMPLETED
}
```

### Required tests

- subcheck failure summary outcome is not completed;
- metadata includes failedChecks and failedCheckNames.

---

## BLOCKER 11 — RetentionModule still stores raw exception messages in RetentionPurgeResult

`RetentionModule` now uses `CancellationSafe.runCatchingCancellable`, but still does:

```kotlin
.getOrElse { RetentionPurgeResult(name, 0, false, it.message) }
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/d1abaebd453ddf35e2a3e963d6988e25c32b775f/app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt

That reintroduces raw `Throwable.message` as diagnostic data. Depending on `DataRetentionWorker` handling, it may be logged/persisted later.

### Required fix

Change `RetentionPurgeResult` to structured fields or use safe code:

```kotlin
.getOrElse {
    RetentionPurgeResult(
        targetName = name,
        rowsPurged = 0,
        success = false,
        errorCode = "RETENTION_TARGET_FAILED",
        errorClass = it::class.simpleName
    )
}
```

If schema/model cannot change, put only:

```kotlin
"RETENTION_TARGET_FAILED:${it::class.simpleName}"
```

No raw message.

### Required tests

- retention target exception message containing path/text is not persisted;
- result includes failure code and error class.

---

## BLOCKER 12 — validation SKIPPED/FAILED bank-statement item rows lack lifecycle events

PR15 added `REVIEW_CREATED` event for successful pending-review rows. But validation skips and item failures appear to insert only `BankStatementImportItem` rows, not lifecycle events.

This may be fine if item ledger is the event source of truth, but docs say “per-item lifecycle events.” Right now that is only true for successful review creation.

### Required fix

Choose one:

- Document item ledger rows as the lifecycle audit for skipped/failed items.
- Or add receipt/item lifecycle events for:
  - `ITEM_SKIPPED_INVALID_AMOUNT`
  - `ITEM_SKIPPED_MISSING_CURRENCY`
  - `ITEM_FAILED_PROCESSING`
  - duplicate item decisions

If events are added, item row + event must be in the same transaction.

---

# Medium issues / polish

## 1. TransactionContext can be manually created

`TransactionContext` is a public data class. Deprecated event writers also manually create one.

This weakens transaction proof. Add an internal token or make construction internal where possible.

## 2. `ReceiptLifecycleEventWriter.buildMetadata()` uses `jo.names()!!`

If metadata JSON object is empty or malformed, `jo.names()` can be null. Probably safe because `event.metadata.isEmpty()` is checked, but safer to avoid `!!`.

## 3. Bank statement import run starts before receipt save

This may be an intended durable ledger, but it should be explicitly documented as row-level import semantics. If whole-statement atomicity is expected, this is still wrong.

## 4. PR18 docs say “NEAR-COMPLETE” while multiple blockers remain

Latest docs are better than “DONE,” but “NEAR-COMPLETE” is optimistic until compile is confirmed and the final bank/recurring/cancellation guard issues are fixed.

---

# MIT status

## MIT-031 — state/event atomicity

Status: **partial / near-complete foundation**.

Fixed:
- transaction context exists;
- receipt/bank event writers accept context;
- several state/event pairs are transactional.

Still open:
- marker-only `TransactionalEventWriter`;
- deprecated context-free writer methods;
- broad direct event DAO allowlist;
- bank run finalization outside final event transaction;
- recurring stale recovery not evented.

Do not close yet.

## MIT-034 — cancellation propagation

Status: **partial**.

Fixed:
- helper exists;
- many critical `runCatching` replacements;
- some catch patterns improved.

Still open:
- huge allowlist;
- many files still exempt until 2026-12-31;
- cancellation finalization can still mask CE in bank statement path;
- global app cancellation safety not proven.

Do not close yet.

## MIT-041 — receipt/OCR/bank-statement atomicity

Status: **partial → near-complete, but with compile/run finalization blockers**.

Fixed:
- receipt required-review path improved;
- bank statement receipt saved with event;
- item review + item row + REVIEW_CREATED event transactional.

Still open:
- probable `runId` compile blocker;
- import run finalize outside final status/event transaction;
- validation skip/failure lifecycle policy incomplete;
- cancellation terminal policy unsafe.

Do not close yet.

## MIT-043 — recurring/reminder atomicity

Status: **partial**.

Fixed:
- reconciliation split added;
- some reminder state/event writes transaction-wrapped.

Still open:
- deprecated hidden-write method remains;
- stale recovery silent;
- regeneration can partially skip failed windows without durable diagnostic;
- DB-level linked actual uniqueness remains MIT-033.

Do not close yet.

## MIT-075 — post-commit side-effect evidence

Status: **partial by design**.

Docs explicitly choose no durable outbox. That is acceptable as a product decision only if you do not claim guaranteed replay.

---

# Recommended next PR

Create a small `PR19 — final atomicity correctness fixes` before any closure.

## PR19 scope

1. Fix nullable `runId` compile issue in `BankStatementLifecycleProcessor`.
2. Move `bankStatementImportRunDao.finalize(...)` into the same transaction as final receipt status/event.
3. Make cancellation finalization best-effort bounded and never mask original CE.
4. Remove or make private/deprecated-with-guard `reconcilePlannedVsActual()`.
5. Add event/diagnostic/write-barrier to `recoverStaleClaimedDeliveries()`.
6. Add failed-check metadata/outcome to consistency summary diagnostic.
7. Remove raw `it.message` from `RetentionModule` purge results.
8. Replace direct event DAO allowlist with structured entries and remove duplicates.
9. Either remove deprecated context-free event writer methods or add static guard banning their use.
10. Run `./gradlew :app:compileDebugKotlin` and targeted tests.

## PR19 tests

At minimum:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
./gradlew :app:testDebugUnitTest --tests "*Consistency*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
```

---

# Final verdict

Your PR11–PR18 work is **meaningful and directionally correct**, but the latest `d1abaeb` is still not safe to treat as final closure.

Most important immediate fixes:

1. **Fix probable `runId: Long?` compile errors.**
2. **Atomically finalize bank import run + final receipt status/event.**
3. **Ensure cancellation finalization never masks cancellation.**
4. **Close recurring stale recovery/event gaps.**
5. **Stop raw retention exception messages from entering results.**
6. **Strengthen event/cancellation guards beyond broad allowlists.**

Recommended project status:

```text
Cancellation/Atomicity/Event Consistency: YELLOW-GREEN.
PR11–PR18: strong foundation.
MIT-031/034/041/043: not fully closeable yet.
Next: PR19 targeted correctness cleanup.
```