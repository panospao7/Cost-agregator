# Deep Review — Atomicity / Cancellation / Event Consistency

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/42e53e15be17303d945fe75c5afb7e22b963eab5

Important previous commits:
- PR22-small: https://github.com/panospao7/Cost-agregator/commit/ca76078
- PR22 follow-up: https://github.com/panospao7/Cost-agregator/commit/0b8850c
- PR21: https://github.com/panospao7/Cost-agregator/commit/b00241d7928e16373dbedabb039948b1fee9bcd4
- PR20: https://github.com/panospao7/Cost-agregator/commit/c88964efb401733494e66eae8037b68fe921ccfe

Core files checked:
- `NotificationProcessingPipeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `ReceiptLinkService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `WarrantyTrackerRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
- `TransactionContextProvenanceGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/TransactionContextProvenanceGuardTest.kt
- `CancellationSafetyArchitectureGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
- `DirectEventDaoInsertGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt
- `BankStatementLifecycleProcessor.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- `RecurringLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- GitHub Actions: https://github.com/panospao7/Cost-agregator/actions

Static review only. I did not run Gradle locally.

---

# Executive verdict

Latest `42e53e1` is **docs-only** and closes MIT-031/MIT-041 as DONE. The actual implementation work was mainly in `ca76078` and `0b8850c`.

The code is now significantly better:

- `NotificationProcessingPipeline` no longer has the specific raw `runCatching` audit-event issue.
- `ReceiptLinkService` no longer uses raw `runCatching` for category propagation.
- `WarrantyTrackerRepository` raw `runCatching` sites were converted to CE-safe `try/catch`.
- `TransactionContextProvenanceGuardTest` now uses the broader `\bTransactionContext\s*\(` pattern.
- manual `TransactionContext` construction is guarded.
- recurring stale recovery is transactional and evented.
- bank-statement cancellation/failure cleanup is much safer.
- direct event DAO allowlist is now structured and legacy repo entries have shorter 2026-08-15 expiry.
- docs correctly keep MIT-034, MIT-043, and MIT-075 partial.

But I would still call the latest state:

```text
MIT-031: conditionally closeable / core DONE, residual repository-event debt remains
MIT-041: conditionally closeable / mostly DONE, but one bank skipped-row data issue remains
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
```

The two main reasons I would not call this globally release-green:

1. **No visible green CI for latest `42e53e1` / `0b8850c` / `ca76078`.** The public Actions page still shows the latest visible run as `c88964e` failing.
2. **Some residual correctness debt remains**, especially direct event DAO legacy repositories, cancellation guard not banning raw `runCatching`, and bank skipped rows storing non-finite amounts.

---

# What is genuinely fixed

## 1. NotificationProcessingPipeline raw `runCatching` issue is fixed

PR22-small replaced the specific:

```kotlin
runCatching { transactionRunner.runInTransaction { ... } }
    .onFailure { ... }
```

with explicit:

```kotlin
try {
    transactionRunner.runInTransaction(...)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(...)
}
```

Source: PR22 diff at `ca76078`:  
https://github.com/panospao7/Cost-agregator/commit/ca76078

Status: **fixed for the previously flagged call site**.

Also, `find("runCatching")` on latest `NotificationProcessingPipeline.kt` returned no match in the opened source.

## 2. ReceiptLinkService raw `runCatching` issue is fixed

PR22-small replaced the category-propagation `runCatching` with explicit CE-safe try/catch.

Source:  
https://github.com/panospao7/Cost-agregator/commit/ca76078

Status: **fixed**.

## 3. WarrantyTrackerRepository raw `runCatching` sites were replaced

`0b8850c` says 9 raw `runCatching` sites were converted to CE-safe `try/catch`.

Source:  
https://github.com/panospao7/Cost-agregator/commit/0b8850c

`find("runCatching")` on latest `WarrantyTrackerRepository.kt` returned no match in the opened source.

Status: **cancellation aspect fixed**.

Caveat: warranty lifecycle events are still classified as non-critical / best-effort. That is acceptable only if docs keep that outside strict MIT-031 closure.

## 4. TransactionContext provenance guard is stronger

The guard now uses:

```kotlin
Regex("""\bTransactionContext\s*\(""")
```

instead of only matching `TransactionContext(correlationId`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/TransactionContextProvenanceGuardTest.kt

This catches positional and reordered constructor calls.

Status: **fixed/improved**.

Remaining caveat: it scans raw text, so comments/strings may still false-positive unless the source avoids those patterns. But false positives are safer than false negatives here.

## 5. Recurring stale recovery is now transaction-scoped and evented

Latest `recoverStaleClaimedDeliveries()`:

- checks the write barrier;
- uses `transactionRunner.runInTransaction`;
- updates stale deliveries;
- inserts `STALE_DELIVERIES_RECOVERED` inside the same transaction.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

Status: **fixed for stale recovery**.

## 6. Bank-statement cancellation/failure cleanup is much safer

Latest bank processor:

- tracks `statementReceiptId`;
- cancellation cleanup catches `Throwable`, adds it as suppressed, logs, then rethrows original cancellation;
- non-cancellation fallback rethrows cancellation if cleanup is cancelled;
- unexpected failure after receipt creation finalizes run and writes `PROCESSING_FAILED` in one `transactionRunner.runInTransaction`.

Sources:
- Bank processor raw: https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

Status: **mostly fixed**.

## 7. Direct event DAO guard is now structured

`DirectEventDaoInsertGuardTest` now has structured entries with rule, owner, reason, issue, expiry.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

Legacy repository entries now expire on `2026-08-15`.

Status: **improved**.

---

# Remaining blockers / concerns

## BLOCKER 1 — Latest CI is not visibly green

The public Actions page still shows the latest visible workflow run as `c88964e` failing. It does **not** show a visible green run for:

- `42e53e1`
- `0b8850c`
- `ca76078`

Source:  
https://github.com/panospao7/Cost-agregator/actions

This is the biggest release/closure blocker.

If CI did not run because docs-only or branch mismatch, you still need one visible verification run before marking MITs truly done.

Required:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Until a latest commit has a visible green run, closure should be:

```text
MIT-031: conditionally done
MIT-041: conditionally done
```

not fully release-done.

---

## BLOCKER 2 — Cancellation guard still does not enforce raw `runCatching`

`CancellationSafetyArchitectureGuardTest` still focuses on broad `catch` patterns. In the opened latest guard, the valid rules are:

```text
CATCH_WITHOUT_CE_RETHROW
FALSE_POSITIVE_CE_RETHROW
LAUNCH_CE_NO_RETHROW
```

There is no visible rule like:

```text
RAW_RUN_CATCHING_IN_SUSPEND_PATH
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

So even though you fixed the known raw `runCatching` call sites, the guard does not fully prevent reintroduction.

This is okay because MIT-034 remains PARTIAL, but do not imply cancellation safety is globally closed.

Required for MIT-034:

- add raw `runCatching` rule;
- allow only `CancellationSafe.runCatchingCancellable`;
- add fixture tests;
- burn down the 97 allowlist entries.

---

## BLOCKER 3 — Direct event DAO allowlist still has production repository debt

The direct event guard is structured now, but it still allows several production repositories to insert event rows directly until `2026-08-15`, including:

- `ExpenseRepository.kt`
- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `NotificationRepository.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- `WarrantyTrackerRepository.kt`
- `BankApiIntegration.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

That means MIT-031 is not “perfectly done”; it is **core/coordinator done with accepted legacy repository debt**.

This is acceptable only if docs clearly say:

```text
MIT-031 closed for critical coordinator-owned paths.
Legacy repository direct-event writes are accepted temporary debt until 2026-08-15.
```

Latest docs call MIT-031 DONE and mention accepted residuals. That is probably acceptable for a scoped closure, but not for global event architecture closure.

---

## BLOCKER 4 — Bank skipped invalid amount ledger can store NaN/Infinity

In `BankStatementLifecycleProcessor`, invalid amount branches do this:

```kotlin
if (tx.amount.isNaN() || tx.amount.isInfinite()) {
    bankStatementImportItemDao.insert(
        BankStatementImportItem(
            ...
            amount = tx.amount,
            errorReason = "INVALID_AMOUNT: Amount is NaN or Infinite"
        )
    )
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

`BankStatementImportItem.amount` is nullable:

```kotlin
val amount: Double? = null
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/42e53e15be17303d945fe75c5afb7e22b963eab5/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankStatementImportItem.kt

For invalid non-finite amounts, you should store:

```kotlin
amount = null
```

or a sanitized finite value, not NaN/Infinity.

Why it matters:

- non-finite values can break queries/aggregations/debug displays;
- it contradicts “pre-mutation validation of amount” if the ledger still persists the invalid numeric value;
- tests should explicitly assert skipped invalid amount rows do not store non-finite values.

Required fix:

```kotlin
amount = null
```

for NaN/Infinite amount branch.

Add test:

```kotlin
invalid_nan_amount_creates_skipped_item_with_null_amount()
invalid_infinite_amount_creates_skipped_item_with_null_amount()
```

This is the main remaining MIT-041 data-quality issue I found.

---

## BLOCKER 5 — Bank duplicate skip transactions create context but do not write event

For duplicate expense and duplicate pending review, the code uses `transactionRunner.runInTransaction`, but only inserts a `BankStatementImportItem` ledger row. It does not write a receipt event.

This is acceptable under the documented policy:

```text
BankStatementImportItem is authoritative per-item audit ledger before receipt/review creation.
```

But make sure docs/tests explicitly cover duplicate-skip rows too, not just invalid rows.

Recommended tests:

- duplicate expense creates ledger row only;
- duplicate pending review creates ledger row only;
- no receipt lifecycle event is expected for duplicate skip item;
- reason does not contain raw merchant text.

---

## BLOCKER 6 — Warranty events are accepted non-critical, but descriptions may include product names

`0b8850c` converted warranty `runCatching` to try/catch, but warranty event descriptions still include product names like:

```kotlin
"Warranty created for ${warranty.productName}"
```

From the diff shown in `0b8850c`.

If warranty lifecycle events are diagnostic/non-critical, this may still be sensitive depending on privacy policy.

Recommended:

- use hashed/redacted product name in event description, or
- move product name into privacy-reviewed metadata if allowed, or
- document warranty lifecycle event content policy.

This does not block MIT-031/041, but it is privacy-hardening debt.

---

# MIT status recommendation

## MIT-031 — state/event atomicity

Latest docs mark it DONE.

My recommendation:

```text
MIT-031: CONDITIONALLY DONE for critical coordinator-owned paths.
Residual accepted debt remains:
- legacy repository direct-event writes until 2026-08-15;
- deprecated writer methods still exist at DeprecationLevel.ERROR;
- no visible latest green CI.
```

If you want strict wording:

```text
MIT-031 core scope: DONE.
MIT-031 global repository cleanup: follow-up debt.
```

Do not call it “fully release-green” until CI is visible and the legacy direct-event allowlist is burned down.

## MIT-041 — receipt/review atomicity

Latest docs mark it DONE.

My recommendation:

```text
MIT-041: NEAR-DONE / conditionally DONE pending latest green CI and NaN/Infinity ledger fix.
```

Core bank paths are much safer now. The remaining issue is skipped invalid amount storing non-finite value.

## MIT-034 — cancellation propagation

Status in docs: PARTIAL.

That is correct.

Reasons:

- 97 allowlist entries;
- no visible raw `runCatching` guard rule;
- broad categories still include repositories/domain/UI/AI.

## MIT-043 — recurring/reminder atomicity

Status in docs: PARTIAL by design.

That is correct.

Reasons:

- regeneration remains best-effort by design;
- MIT-033 uniqueness dependency remains;
- duplicate fulfillment full closure not in this branch.

## MIT-075 — side-effect evidence/outbox

Status in docs: PARTIAL by design.

That is correct.

No durable outbox exists.

---

# Recommended next small PR

Create:

```text
PR23 — final verification and residue cleanup
```

Scope:

1. Fix bank invalid amount ledger:
   - NaN/Infinity skipped rows store `amount = null`.
2. Add tests:
   - invalid NaN/Infinity amount ledger row has null amount;
   - duplicate skip rows are audited by item ledger and do not create receipt events.
3. Add cancellation static guard for raw `runCatching` in suspend/domain/worker paths.
4. Run and publish visible CI for latest commit.
5. Update docs:
   - MIT-031 core DONE, legacy repo direct-event debt expires 2026-08-15;
   - MIT-041 DONE only after invalid amount ledger fix + green CI.

---

# Final verdict

Your latest PR22 work did address my previous main concerns:

- raw `runCatching` in the named core files was fixed;
- transaction context provenance guard was broadened;
- warranty cancellation handling improved;
- direct event allowlist expiry shortened;
- MIT-034/043/075 remain honestly partial.

But `42e53e1` closes MIT-031 and MIT-041 a little aggressively.

Final recommended status:

```text
MIT-031: core DONE / global debt remains
MIT-041: almost DONE; fix non-finite skipped amount ledger + verify CI
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
Overall: GREEN-YELLOW, not full GREEN
```