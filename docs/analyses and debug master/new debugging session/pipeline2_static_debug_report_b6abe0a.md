# Pipeline 2 Static Debug Report — Transaction Lifecycle

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 2 is **much better than the old baseline**, but it is **not fully clean**.

The central lifecycle coordinator is real and useful now:

```text
create/update/delete -> TransactionLifecycleCoordinator -> ExpenseDao -> TransactionEventDao -> side effects
```

However, several tracker items marked fixed are actually **partial** when checked against current code.

Highest remaining user-impact risks:

1. **Some direct expense mutations still bypass restore/write barriers.**
2. **Full-row expense update can persist invalid state because update validation is weaker than create validation.**
3. **Some duplicate/failure paths still do not create durable transaction events.**
4. **Create source-link fields are accepted but not persisted, so review/receipt/group/import traceability is incomplete.**
5. **Update side effects can still run inside caller-owned transactions in group-link flows.**
6. **Receipt-created expense path still creates expense before receipt link if the deprecated path is reachable.**
7. **Bulk/category/group cleanup paths remain partially atomic/observable only.**

Current status: **core-stable, but lifecycle hardening still required before calling Pipeline 2 closed.**

---

# Sources checked

- Latest commit: https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Previous Pipeline 2 report: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-2-transaction-lifecycle-debug-report.md
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `TransactionSideEffectDispatcher.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
- `ExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- `ExpenseDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- `ManualExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
- `ReviewQueueRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
- `ReceiptRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
- `GroupTransactionCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
- `CreateExpenseRequest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
- `CreateExpenseResult.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
- `LifecycleEventType.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
- `TransactionEvent.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
- `Expense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

---

# 1. Tracker reconciliation

Master tracker says Pipeline 2:

| ID | Tracker status |
|---|---|
| P2-P1-01 | fixed |
| P2-P1-02 | fixed |
| P2-P1-03 | fixed |
| P2-P1-04 | fixed |
| P2-P1-05 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P2-P1-01 | **Partial** | `updateBusinessFlags()` has restore guard, but several accepted fields are still no-op / not persisted. |
| P2-P1-02 | **Partial** | `CREATE_ATTEMPTED`, validation, conflict events exist, but some caller rollback/early duplicate paths still leave no durable event. |
| P2-P1-03 | **Mostly fixed** | `STRICT_EXTERNAL_ID` conflict lookup exists, but attempt event uses wrong dedupe key and non-strict conflicts still lack existing ID. |
| P2-P1-04 | **Mostly fixed** | Debug destructive methods are `BuildConfig.DEBUG` guarded and write-barrier guarded, but they still do not write meaningful lifecycle audit events. |
| P2-P1-05 | **Open and higher risk than tracker implies** | Public DAO mutation remains, and some approved direct paths also miss write-barrier checks. |

Old lower-priority issues:

| Old issue | My status |
|---|---:|
| P2-06 group hard-delete cleanup | **Partial** |
| P2-07 bulk side effects | **Partial** |
| P2-08 delete snapshot stale | **Partial** |
| P2-09 soft/hard delete contract | **Mostly documented, tests still needed** |
| P2-10 deferred side effects | **Partial** |
| P2-11 duplicate event visibility | **Partial** |
| P2-12 duplicate budget checks | **Mostly fixed** |

---

# 2. Original issue evaluation

## P2-P1-01 — `updateBusinessTaxFields()` / `updateBusinessFlags()` restore guard

### Current state

The restore guard is now present in `updateBusinessFlags()`.

But the method still accepts fields that are not persisted:

```text
businessUsePercent
taxCategory
vatEligible
```

It only persists:

```text
isBusinessExpense
receiptRequired -> requiresReceipt
```

Also, the `Expense` entity has fields such as:

```text
businessPurpose
businessCategory
businessProject
```

but the current update method does not expose/update those fields.

### Classification

- **Restore bug:** fixed for this method.
- **Actual UX/data bug:** partial. User/caller may believe business/tax details were saved when they were not.
- **Architectural cleanup:** rename/API contract should reflect persisted fields only.

### Fix strategy

Replace the loose nullable-parameter API with a result-bearing patch:

```kotlin
data class BusinessExpensePatch(
    val isBusinessExpense: Boolean?,
    val requiresReceipt: Boolean?,
    val businessPurpose: String?,
    val businessCategory: String?,
    val businessProject: String?
)

sealed interface BusinessExpenseUpdateResult {
    data object Updated : BusinessExpenseUpdateResult
    data object NoChange : BusinessExpenseUpdateResult
    data class UnsupportedFields(val fields: List<String>) : BusinessExpenseUpdateResult
    data object NotFound : BusinessExpenseUpdateResult
}
```

Acceptance:

- no unsupported input is silently dropped,
- event metadata records changed fields,
- no-op patch does not write fake `UPDATED` event.

---

## P2-P1-02 — Failed creates invisible in `transaction_events`

### Current state

Good:

- `CREATE_ATTEMPTED` is written.
- `CREATE_VALIDATION_FAILED` is written.
- `CREATE_INSERT_CONFLICT` is written.
- duplicate event has `eventLogged` returned in `CreateExpenseResult.DuplicateSkipped`.

Still partial:

1. If `createExpense()` is called inside an outer `database.withTransaction` and the caller throws to roll back, the failed-create event can roll back too.
2. `ReviewQueueRepository.approveReview()` has a pre-coordinator duplicate check. If duplicate, it marks the review `DUPLICATE` and does not call coordinator, so no `CREATE_DUPLICATE_SKIPPED` event is written.
3. Restore-blocked creates return `CreateExpenseResult.Error` and only log with Timber. There is no durable diagnostic row.
4. Attempt events use a standard dedupe key even for `STRICT_EXTERNAL_ID`, so the audit key can differ from the actual persisted key.

### Classification

- **Actual observability bug.**
- Direct user impact when debugging “why did my expense not appear?”

### Fix strategy

Create a separate `TransactionLifecycleDiagnosticDao` or allow transaction attempt events to be written outside rollback-prone caller transactions.

Minimum patch:

- Add `recordCreateAttempt()` outside the caller transaction when possible.
- Add explicit duplicate event in review precheck path.
- Add `CREATE_BLOCKED_RESTORE` diagnostic event to a restore-safe diagnostics channel.
- For strict idempotency, derive attempted dedupe key as:

```kotlin
"idem:${source}:${idempotencyKey ?: externalFingerprint}"
```

---

## P2-P1-03 — `STRICT_EXTERNAL_ID` weak insert conflict

### Current state

Mostly fixed:

- `ExpenseDao.findIdByDedupeKey()` exists.
- `STRICT_EXTERNAL_ID` insert conflict looks up existing ID and returns `DuplicateSkipped(existingExpenseId)`.

Still partial:

- `CREATE_INSERT_CONFLICT` is written before the idempotent duplicate is resolved, creating noisy/confusing audit.
- `CREATE_ATTEMPTED.dedupeKey` does not match the strict external ID key.
- Standard/bulk insert races still return `InsertConflict` without existing ID.

### Classification

- **Strict external ID:** mostly fixed.
- **General idempotency/race correctness:** partial.

### Fix strategy

For all insert conflicts:

```kotlin
val existingId = expenseDao.findDuplicateIdCurrencyAware(...)
    ?: expense.dedupeKey?.let { expenseDao.findIdByDedupeKey(it) }

if (existingId != null) return DuplicateSkipped(existingId, ...)
```

Only return `InsertConflict` if the existing row cannot be resolved.

---

## P2-P1-04 — Debug/restore methods bypass lifecycle

### Current state

Good:

- `deleteAllExpenses()` checks `writeBarrier`.
- `deleteAllExpenses()` is disabled outside `BuildConfig.DEBUG`.
- `restoreDebugSnapshot()` checks `writeBarrier`.
- `restoreDebugSnapshot()` is disabled outside `BuildConfig.DEBUG`.

Still partial:

- `deleteAllExpenses()` writes no lifecycle event.
- `restoreDebugSnapshot()` writes no `RESTORED_FROM_DEBUG_SNAPSHOT` event even though the enum exists.
- `createDebugSnapshot()` reads are fine, but there is no diagnostic/audit around snapshot generation.

### Classification

- **Production user bug:** mostly fixed.
- **Debug observability:** partial.

### Fix strategy

Either:

1. Move these into `DebugExpenseRepository`, or
2. Keep them but write aggregate debug events:

```text
DEBUG_DELETE_ALL
RESTORED_FROM_DEBUG_SNAPSHOT
```

Do not write per-row events for large snapshots.

---

## P2-P1-05 — Public DAO mutation surface

### Current state

Still open.

`ExpenseDao` exposes many mutation methods publicly. This was expected, but more importantly some “approved direct paths” in `ExpenseRepository` do not call `writeBarrier`:

```text
conditionallySetLocation()
clearExpenseLocation()
incrementBackfillAttempts()
updateMerchantKey()
```

These are intentionally not lifecycle-routed, but they still mutate `expenses`.

### Classification

- **Actual restore/write-safety bug.**
- **Architectural regression risk.**

### Fix strategy

Add write barrier to every repository method that mutates expense columns, even if lifecycle audit is intentionally skipped.

Example:

```kotlin
suspend fun updateMerchantKey(expenseId: Long, merchantKey: String) {
    writeBarrier.checkWritesAllowed("ExpenseRepository.updateMerchantKey")
    expenseDao.updateMerchantKey(expenseId, merchantKey)
}
```

Then add static guard:

```text
Fail CI if ExpenseDao mutation is called outside approved classes/methods.
```

---

# 3. New/current issues found

## P2-NEW-01 — Full-row `updateExpense()` lacks create-equivalent validation

### Severity

P1.

### Evidence

`createExpense()` validates amount, merchant, currency, date, transfer fields, ownership conflict, and location pair.

`updateExpense(expense)` mostly:

- loads existing,
- recomputes dedupe key if key fields changed,
- recomputes conversion snapshot,
- updates row,
- writes `UPDATED`.

It does not appear to enforce the same validation set.

### User impact

A user edit or future call path can persist:

- non-positive amount,
- invalid currency,
- future date outside policy,
- `TRANSFER` without transfer metadata,
- conflicting ownership flags,
- invalid location pair.

### Fix strategy

Extract shared validation:

```kotlin
TransactionValidator.validateCreate(request)
TransactionValidator.validateUpdate(existing, patch/finalExpense)
```

Acceptance:

- no update path can create a row state that create would reject,
- full-row update normalizes ownership,
- transfer metadata is required when type is `TRANSFER`.

---

## P2-NEW-02 — Update side effects can run before outer transaction commit

### Severity

P1/P2 depending on caller.

### Evidence

`GroupTransactionCoordinator` calls `normalizeLinkedSystemExpense()` inside its own `database.withTransaction`.

`normalizeLinkedSystemExpense()` calls `TransactionLifecycleCoordinator.updateOwnership()`.

`updateOwnership()` writes update/event and dispatches side effects immediately after its internal transaction. When nested inside the outer group transaction, those side effects can run before the group transaction has truly committed.

### User impact

Budget/anomaly/dashboard side effects can observe state that is later rolled back by the group transaction.

### Fix strategy

Add update DB-only APIs:

```kotlin
suspend fun updateOwnershipDbOnly(...): LifecycleUpdateResult
suspend fun dispatchPostUpdateSideEffects(result)
```

Or introduce a generic deferred side-effect accumulator:

```kotlin
class PostCommitActions {
    fun add(action: suspend () -> Unit)
    suspend fun dispatchAll()
}
```

Group operations should collect side effects and dispatch only after the outer transaction returns success.

---

## P2-NEW-03 — `deleteExpense(expense)` stale-snapshot overload remains

### Severity

P2.

### Evidence

`deleteExpense(expenseId)` now loads inside transaction, which is good.

But `deleteExpense(expense: Expense)` still exists and snapshots the passed object before the final transaction. `ExpenseRepository.deleteExpense(expense)` calls this stale overload.

### User impact

If the expense changed between screen load and delete, the `DELETED.beforeSnapshot` can record stale data.

### Fix strategy

Deprecate the entity overload with `DeprecationLevel.ERROR` or make it private/internal.

Change repository to call:

```kotlin
transactionLifecycleCoordinator.deleteExpense(expense.id)
```

Acceptance:

- all public delete paths load current row inside final transaction.

---

## P2-NEW-04 — Source link fields are accepted but not persisted

### Severity

P1/P2 observability.

### Evidence

`CreateExpenseRequest` accepts:

```text
pendingReviewId
scannedReceiptId
emailReceiptSourceId
groupId
csvImportBatchId
csvRowNumber
externalFingerprint
```

but current TODO says these are not persisted by the coordinator.

`Expense` only has `rawNotificationId` for source linking.

### User impact

For reviews, receipts, email, CSV, groups, and bank imports, the transaction audit cannot reliably answer:

```text
Which review created this expense?
Which receipt/email/import row created this expense?
Which group operation created this system expense?
```

### Fix strategy

Persist source link metadata in `TransactionEvent.metadata` at minimum.

Example metadata:

```json
{
  "pendingReviewId": 123,
  "scannedReceiptId": 456,
  "emailReceiptSourceId": 789,
  "groupId": 10,
  "csvImportBatchId": "batch-abc",
  "csvRowNumber": 42,
  "externalFingerprint": "..."
}
```

Long-term: add source-link table:

```text
transaction_source_links(expenseId, sourceType, sourceId, metadata)
```

---

## P2-NEW-05 — Review approval duplicate precheck bypasses transaction-event duplicate logging

### Severity

P1/P2.

### Evidence

`ReviewQueueRepository.approveReview()` prechecks duplicate with `hasCanonicalApprovalDuplicate(expense)`. If duplicate, it:

- increments duplicate stats,
- decrements pending stats,
- marks review `DUPLICATE`,
- returns duplicate outcome.

It does not write `CREATE_DUPLICATE_SKIPPED`.

### User impact

A duplicate review can disappear from pending review queue with no transaction lifecycle event explaining which existing expense it matched.

### Fix strategy

Route duplicate decision through coordinator, or add:

```kotlin
transactionLifecycleCoordinator.recordDuplicateSkippedAttempt(...)
```

The event should include:

- review ID,
- package name,
- attempted merchant/amount/date/currency/type,
- matched expense ID if available.

---

## P2-NEW-06 — Review approval dedupe key can use stale/wrong currency

### Severity

P2.

### Evidence

In `ReviewQueueRepository.approveReview()`, the `Expense` used for precheck sets currency from resolved final currency, but the dedupe key generation uses `review.suggestedCurrency`.

If the user overrides currency, or suggested currency is blank/stale, the precheck key can diverge from coordinator-generated key.

### User impact

Duplicate detection may be inconsistent between review approval and direct creation.

### Fix strategy

Use the resolved final currency everywhere:

```kotlin
val resolvedCurrency = finalCurrency ?: review.suggestedCurrency
dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
    amount,
    normalizedMerchant,
    transactionDate,
    resolvedCurrency,
    type
)
```

Better: do not build a separate `Expense` for precheck. Ask coordinator to perform dry-run duplicate detection with the same request object.

---

## P2-NEW-07 — `createExpenseFromReceipt()` still has non-atomic expense + receipt link

### Severity

P1/P2 if reachable.

### Evidence

`ReceiptRepository.createExpenseFromReceipt()` is deprecated with `DeprecationLevel.ERROR`, but still present.

It calls coordinator create first. If created, it then links receipt via `ReceiptLinkService.linkReceiptToExpense()` and updates item categorization separately.

### User impact

If link or categorization update fails after the expense is created, the app can leave an expense without its receipt link.

Also, lifecycle side effects may run before source-specific receipt link exists.

### Fix strategy

Remove this method or replace with receipt lifecycle coordinator path:

```text
receipt create/link + expense create + receipt item links in one DB transaction
post-commit side effects after all links commit
```

Add static guard:

```text
No production caller may call ReceiptRepository.createExpenseFromReceipt()
```

---

## P2-NEW-08 — Bulk category reassignment by category is non-atomic

### Severity

P2.

### Evidence

Coordinator TODO notes that moving all expenses from one category to another is non-atomic and loops through per-expense updates.

### User impact

Crash/cancellation mid-loop leaves partially migrated category assignments.

It may also trigger N side-effect passes instead of one aggregate recalculation.

### Fix strategy

Add single DAO update:

```sql
UPDATE expenses SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId
```

Then write one `BULK_UPDATED` event with affected count and dispatch one aggregate side-effect.

---

## P2-NEW-09 — Bulk side effects are only budget recheck

### Severity

P2.

### Evidence

`TransactionSideEffectDispatcher.dispatchOnBulkUpdated()` currently performs one budget check only.

Old fix strategy wanted broader aggregate freshness:

```text
budget
anomaly/cache invalidation
merchant/category cache invalidation
dashboard/analytics cache invalidation if cached
```

### User impact

After bulk merchant/category edits, derived intelligence can stay stale.

### Fix strategy

Expand aggregate dispatcher:

```kotlin
dispatchOnBulkUpdated(
    source,
    affectedCount,
    changedFields = setOf("categoryId", "merchant")
)
```

Run:

- budget recheck,
- anomaly re-evaluation/invalidation,
- merchant-category model refresh,
- canonical merchant stats refresh or dirty marker,
- dashboard cache invalidation if cache exists.

---

## P2-NEW-10 — Group hard-delete flag cleanup not fully lifecycle-safe

### Severity

P2.

### Evidence

`deleteGroupAtomic()`:

- hard-deletes group data,
- clears shared expense flags directly through `expenseDao.clearSharedExpenseFlags()`,
- writes one `BULK_UPDATED` event after the transaction,
- swallows audit insert failure,
- does not dispatch aggregate side effects.

### User impact

Clearing shared flags changes effective amount and therefore budgets/dashboard totals. Without side effects, derived state can lag.

If process dies after the DB transaction but before the audit event, the flag cleanup has no lifecycle event.

### Fix strategy

Inside the same DB transaction:

- write group-delete event,
- write shared-flag cleanup aggregate event,
- perform deletes/cleanup.

After commit:

- dispatch one aggregate bulk side-effect.

Also inject `TimeProvider` instead of `System.currentTimeMillis()`.

---

## P2-NEW-11 — Side-effect failures are not durable

### Severity

P2.

### Evidence

`TransactionSideEffectDispatcher` logs and swallows failures. `LifecycleEventType` includes `SIDE_EFFECT_FAILED`, but dispatcher does not appear to write it.

### User impact

Expense write succeeds, but budgets/anomalies/recurring links may fail silently except logs.

### Fix strategy

Inject a lightweight `LifecycleSideEffectEventWriter` and write:

```text
SIDE_EFFECT_FAILED
metadata = {
  expenseId,
  sideEffectName,
  source,
  errorClass,
  errorMessage
}
```

Do not fail the primary transaction.

---

## P2-NEW-12 — Recurring unlink can run twice on delete

### Severity

P3/P2.

### Evidence

`TransactionSideEffectDispatcher.dispatchOnDeleted()` unlinks recurring occurrence. `TransactionLifecycleCoordinator.deleteExpense()` also calls `recurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expenseId)` after dispatcher.

### User impact

Probably harmless if idempotent, but creates noisy logs/work and can hide real errors.

### Fix strategy

Choose one owner.

Preferred:

- dispatcher owns all standard post-delete side effects,
- coordinator only calls dispatcher.

---

## P2-NEW-13 — Manual create recommendation hook uses synthetic expense

### Severity

P2.

### Evidence

`ManualExpenseRepository.addManualExpense()` creates a synthetic `Expense` after coordinator creation. TODO notes it may diverge from actual persisted row, especially conversion fields.

### User impact

AI recommendations/insights may use incomplete or incorrect data.

### Fix strategy

After commit and side effects:

```kotlin
val insertedExpense = expenseDao.getById(result.data)
```

Use the real persisted entity for recommendation generation.

Better: make `CreateExpenseResult.Created` carry the persisted row or a `CreatedExpenseSnapshot`.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize these:

1. **Direct maintenance/backfill expense writes miss write barrier.**
   - `conditionallySetLocation`
   - `clearExpenseLocation`
   - `incrementBackfillAttempts`
   - `updateMerchantKey`

2. **Full-row update can persist invalid expense state.**
   - Missing create-equivalent validation on update.

3. **Review duplicate approvals can become invisible in transaction events.**

4. **Receipt-created expense can remain unlinked if deprecated path is called.**

5. **Group link update side effects can run before outer group transaction commits.**

6. **Group hard-delete shared flag cleanup lacks atomic audit + side effects.**

7. **Bulk category reassignment by category is non-atomic.**

8. **Source link metadata not persisted, hurting review/receipt/import traceability.**

## Architectural / hardening work

Still important but lower immediate severity:

1. Narrow `ExpenseDao` mutation visibility.
2. Replace `SideEffectMode` with typed DB-only result + post-commit action.
3. Create lifecycle diagnostic table for rollback-safe failed attempts.
4. Replace no-op business/tax params with explicit patch/result.
5. Add durable side-effect failure events.
6. Remove stale entity delete overload.
7. Expand bulk aggregate side effects beyond budget check.
8. Fetch real persisted expense for AI/manual hooks.

---

# 5. Recommended implementation plan

## PR 1 — Restore/write barrier sweep

### Goal

Every mutation of `expenses` obeys the centralized write barrier.

### Files

- `ExpenseRepository.kt`
- maybe workers calling `ExpenseDao` directly
- `ExpenseDao.kt`
- static guard script/test

### Tasks

1. Add `writeBarrier.checkWritesAllowed()` to:
   - `conditionallySetLocation`
   - `clearExpenseLocation`
   - `incrementBackfillAttempts`
   - `updateMerchantKey`
2. Search for direct `ExpenseDao` mutation outside coordinator.
3. Add CI guard allowlist.

### Acceptance

```bash
grep -R "expenseDao\.\(insert\|update\|delete\|clear\|increment\|conditionally\)" app/src/main/java
```

Every mutation is either:

- inside `TransactionLifecycleCoordinator`,
- guarded by write barrier and explicitly allowlisted,
- migration-only.

---

## PR 2 — Shared transaction validation for create and update

### Goal

Updates cannot create invalid rows.

### Files

- new `TransactionValidator.kt`
- `TransactionLifecycleCoordinator.kt`
- tests

### Tasks

1. Extract create validation.
2. Add update validation for final row state.
3. Normalize ownership in full-row update.
4. Validate transfer metadata when type is `TRANSFER`.
5. Validate location pair.
6. Return structured `UpdateExpenseResult`.

### Acceptance tests

```text
update_rejects_negative_amount
update_rejects_invalid_currency
update_rejects_transfer_without_account
update_rejects_conflicting_ownership
update_normalizes_ownership
update_rejects_lat_without_lon
```

---

## PR 3 — Durable failed/duplicate diagnostics

### Goal

Every failed create attempt has a durable explanation.

### Files

- `TransactionLifecycleCoordinator.kt`
- `ReviewQueueRepository.kt`
- new `TransactionLifecycleDiagnosticDao.kt` or event-writer

### Tasks

1. Fix strict external attempt dedupe key.
2. Add duplicate event for review precheck duplicate.
3. Add restore-blocked diagnostic.
4. Ensure validation events do not disappear because caller transaction rolls back.
5. Resolve standard/bulk insert conflict to existing ID where possible.

### Acceptance tests

```text
review_duplicate_approval_writes_CREATE_DUPLICATE_SKIPPED
strict_external_attempt_event_uses_idem_key
restore_blocked_create_writes_diagnostic_or_structured_blocked_result
standard_insert_race_returns_duplicate_with_existing_id_when_resolvable
```

---

## PR 4 — Source-link metadata persistence

### Goal

Audit can trace created expense to source object.

### Files

- `CreateExpenseRequest.kt`
- `TransactionLifecycleCoordinator.kt`
- `TransactionEvent.kt` if needed
- tests for review/receipt/group/import

### Tasks

1. Add `sourceLinkMetadata()` helper.
2. Include source-link JSON in:
   - `CREATE_ATTEMPTED`
   - `CREATED`
   - duplicate/failure events.
3. For created expenses, optionally add `SOURCE_LINKED`.

### Acceptance

For review approval, created event metadata includes:

```json
{
  "pendingReviewId": "...",
  "scannedReceiptId": "..."
}
```

For group expense, metadata includes `groupId`.

---

## PR 5 — Post-commit action contract for updates

### Goal

No lifecycle side effect runs inside caller-owned transaction.

### Files

- `TransactionLifecycleCoordinator.kt`
- `GroupTransactionCoordinator.kt`
- `TransactionSideEffectDispatcher.kt`

### Tasks

1. Add DB-only update methods.
2. Add post-commit action/result model.
3. Refactor group existing-expense link flow.
4. Make cancellation propagate in group post-commit catches.

### Acceptance

```text
group_existing_expense_link_dispatches_update_side_effects_after_outer_commit
rollback_group_link_does_not_run_budget_check
cancellation_during_post_commit_is_not_swallowed
```

---

## PR 6 — Delete hardening

### Goal

Delete audit always uses latest row and delete semantics are tested.

### Files

- `TransactionLifecycleCoordinator.kt`
- `ExpenseRepository.kt`
- receipt/group/recurring DAO tests

### Tasks

1. Remove/deprecate `deleteExpense(expense)` public overload.
2. Make repository delete by ID.
3. Add FK/orphan tests for:
   - receipt links,
   - group links,
   - recurring occurrence links.
4. Remove double recurring unlink.

### Acceptance

```text
delete_by_entity_is_not_public
delete_uses_latest_snapshot
delete_preserves_DELETED_snapshot
delete_does_not_leave_invalid_receipt_or_group_links
recurring_unlink_runs_once
```

---

## PR 7 — Bulk and group cleanup hardening

### Goal

Bulk updates and group hard-delete are atomic, audited, and trigger one aggregate recalculation.

### Files

- `TransactionLifecycleCoordinator.kt`
- `ExpenseDao.kt`
- `GroupTransactionCoordinator.kt`
- `TransactionSideEffectDispatcher.kt`

### Tasks

1. Replace category reassignment loop with single DAO update + `BULK_UPDATED`.
2. Put group hard-delete audit in same DB transaction.
3. Dispatch bulk side effects after group cleanup.
4. Expand bulk dispatcher beyond budget check.

### Acceptance

```text
bulk_category_reassignment_is_atomic
bulk_category_reassignment_writes_one_event
group_hard_delete_cleanup_writes_event_in_same_transaction
group_hard_delete_cleanup_dispatches_bulk_side_effect_once
```

---

## PR 8 — Remove/rewrite deprecated receipt expense path

### Goal

No expense can be created from receipt without atomic source link.

### Files

- `ReceiptRepository.kt`
- `ReceiptLifecycleCoordinator.kt`
- callsites/tests

### Tasks

1. Delete `createExpenseFromReceipt()` if no callers.
2. If callers exist, replace with receipt lifecycle coordinator.
3. Add static guard preventing production call.

### Acceptance

```text
grep -R "createExpenseFromReceipt" app/src/main/java
```

returns only deleted/deprecation test references, no production caller.

---

# 6. Suggested tracker updates

Update Pipeline 2 tracker:

| ID | Suggested status |
|---|---|
| P2-P1-01 | Partial |
| P2-P1-02 | Partial |
| P2-P1-03 | Mostly fixed / partial caveat |
| P2-P1-04 | Mostly fixed / debug-audit caveat |
| P2-P1-05 | TODO / high priority |
| P2-06 | Partial |
| P2-07 | Partial |
| P2-08 | Partial |
| P2-09 | Mostly documented, tests needed |
| P2-10 | Partial |
| P2-11 | Partial |
| P2-12 | Fixed |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P2-NEW-01 | P1 | Full-row update lacks create-equivalent validation |
| P2-NEW-02 | P1/P2 | Update side effects can run before outer transaction commit |
| P2-NEW-03 | P2 | Stale `deleteExpense(expense)` overload remains |
| P2-NEW-04 | P1/P2 | Source link fields accepted but not persisted |
| P2-NEW-05 | P1/P2 | Review duplicate precheck bypasses transaction event |
| P2-NEW-06 | P2 | Review approval dedupe key can use stale currency |
| P2-NEW-07 | P1/P2 | Deprecated receipt expense path is non-atomic |
| P2-NEW-08 | P2 | Category reassignment by category is non-atomic |
| P2-NEW-09 | P2 | Bulk side effects only recheck budget |
| P2-NEW-10 | P2 | Group hard-delete cleanup lacks atomic audit + side effects |
| P2-NEW-11 | P2 | Side-effect failures are not durable |
| P2-NEW-12 | P3/P2 | Delete recurring unlink can run twice |
| P2-NEW-13 | P2 | Manual recommendation hook uses synthetic expense |

---

# 7. Golden tests for Pipeline 2

Add or verify:

```text
manual_create_writes_CREATE_ATTEMPTED_and_CREATED
manual_create_dispatches_side_effects_after_commit
manual_create_with_recurring_failure_rolls_back_expense
validation_failed_create_writes_durable_failure_event
restore_mode_blocks_all_expense_mutations
backfill_location_write_blocks_during_restore
merchant_key_backfill_blocks_during_restore
review_duplicate_approval_writes_duplicate_event
review_approval_currency_override_uses_same_currency_for_dedupe_key
strict_external_retry_returns_existing_id
standard_insert_race_returns_duplicate_when_existing_id_resolvable
update_rejects_invalid_final_state
update_normalizes_ownership
update_transfer_requires_direction_and_account
delete_by_id_uses_latest_snapshot
delete_entity_overload_not_public
group_existing_expense_link_side_effects_after_outer_commit
group_hard_delete_cleanup_writes_bulk_event_atomically
bulk_category_reassign_is_atomic_and_single_side_effect
receipt_create_path_cannot_leave_unlinked_expense
side_effect_failure_writes_SIDE_EFFECT_FAILED
```

---

# 8. Agent-ready priority order

Do this order:

1. **Write-barrier sweep for direct expense mutations.**
2. **Shared create/update validation.**
3. **Duplicate/failure diagnostics hardening.**
4. **Persist source-link metadata in transaction events.**
5. **Fix update post-commit contract for group flows.**
6. **Remove stale delete overload and double recurring unlink.**
7. **Atomic bulk/category/group cleanup fixes.**
8. **Remove or rewrite deprecated receipt expense path.**
9. **Durable side-effect failure events.**
10. **Manual create recommendation hook fetches persisted expense.**