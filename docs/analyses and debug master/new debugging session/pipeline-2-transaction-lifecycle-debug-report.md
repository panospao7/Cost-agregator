# Pipeline 2 Debug Report — Transaction Lifecycle

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 2 is now **substantially cleaner than the older `53c915f` report**.

The core lifecycle contract is mostly in place:

```text
create/update/delete
→ TransactionLifecycleCoordinator
→ ExpenseDao
→ TransactionEventDao
→ post-commit side effects
→ budget/anomaly/merchant/recurring/dashboard/analytics
```

Old critical issues that now look mostly fixed:

- Outer transaction side effects: major callers now use `SideEffectMode.DEFER`.
- Many update paths are now routed through `TransactionLifecycleCoordinator`.
- Duplicate ID lookup now mirrors duplicate detection more closely.
- Create/update conversion now uses `CurrencySettingsRepository.homeCurrency()`.
- Update/delete side effects exist via `TransactionSideEffectDispatcher`.

Current state: **green/yellow, not red**.  
I would call this **core-stable but not fully hardened**.

Remaining risk is concentrated in:

1. failed-create observability,
2. idempotent insert conflicts,
3. direct debug/backfill/bulk DAO mutation paths,
4. one restore-barrier hole,
5. bulk-update side effects,
6. hard-delete / no soft-delete contract,
7. stale/legacy APIs still present.

---

# Severity scale

- **P0 / Critical:** data loss, duplicate money rows, privacy/security break, restore corruption.
- **P1 / High:** lifecycle bypass, missing audit for meaningful user action, broken idempotency, restore/write-barrier hole.
- **P2 / Medium:** edge correctness, weak diagnostics, bulk/cache inconsistency, architectural regression risk.
- **P3 / Low:** cleanup, deprecation, maintainability.

---

# Current checklist status

## Creation paths

| Path | Current status |
|---|---|
| Manual expense create | Mostly good. Uses coordinator with `SideEffectMode.DEFER`, then dispatches after outer transaction. |
| Notification auto-accept | Good direction from Pipeline 1. Uses coordinator and deferred side effects in outer transaction paths. |
| Review approval | Mostly good. Uses review status transition, coordinator with `DEFER`, receipt link inside transaction, then post-commit side effects. |
| Receipt-created expense | Mostly migrated by docs/KDoc. Legacy `ReceiptRepository.createExpenseFromReceipt()` still exists and is deprecated. |
| Bank/email/import | Not fully re-inspected in this pass. Repository KDoc says migrated, but AI should verify with grep before closing. |
| Group expense | Improved. Uses coordinator with `DEFER`; group link happens in outer transaction; side effects are intended post-commit. |

## Update paths

Mostly improved:

- `updateExpense()`
- `updateCategory()`
- `updateMerchant()`
- `updateType()`
- `updateTransferDetails()`
- `updateOwnership()`
- `updateLocation()`
- bulk category/merchant wrappers

now route through coordinator or are explicitly documented.

Still concerning:

- backfill/debug paths still write directly,
- group hard-delete cleanup clears shared flags directly,
- business/tax update path has a restore guard gap and no-op parameters.

## Delete path

Coordinator delete exists and writes `DELETED`, but deletion is hard-delete only and some direct delete/debug methods bypass lifecycle.

## Side effects

Creation/update/delete side effects exist.  
Bulk updates and some cleanup paths still intentionally skip/fold side effects.

---

# Positive findings to preserve

## PF-01 — Coordinator is now real central infrastructure

`TransactionLifecycleCoordinator.createExpense()` performs:

```text
restore write guard
→ validate
→ merchantKey/dedupeKey
→ normalize ownership
→ home-currency conversion snapshot
→ duplicate check
→ insert expense + CREATED event in Room transaction
→ optional post-commit side effects
```

This is the right architecture.

## PF-02 — Outer transaction issue mostly fixed

Examples:

- `ManualExpenseRepository.addManualExpense()` uses `createExpense(request, SideEffectMode.DEFER)` inside `database.withTransaction`, then dispatches after commit.
- `ReviewQueueRepository.approveReview()` uses `DEFER` inside the review transaction, then dispatches after commit.
- `GroupTransactionCoordinator` uses `DEFER` when creating the system expense inside its group transaction.

This closes the biggest old P0 from the previous report.

## PF-03 — Update APIs are much stronger

`ExpenseRepository` now documents that most user-facing update paths are routed through the coordinator and write lifecycle events.

The coordinator has specialized APIs:

```text
updateCategory
updateLocation
updateBusinessTaxFields
updateMerchant
updateType
updateTransferDetails
updateOwnership
bulkUpdateCategory
bulkUpdateMerchant
deleteExpense
```

## PF-04 — Side effects exist for update/delete

`TransactionSideEffectDispatcher` now has:

```text
dispatchOnUpdated()
dispatchOnDeleted()
```

Update side effects check budgets, re-run anomaly evaluation, and learn merchant-category patterns. Delete side effects check budgets.

## PF-05 — Duplicate lookup is improved

`ExpenseDao.findDuplicateIdCurrencyAware()` now mirrors `isDuplicateCurrencyAware()` with:

```text
merchantKey exact
→ merchantKey prefix containment
→ raw merchant
→ date/amount/currency/type window
```

This fixes the old “duplicate detected but existing ID unknown” problem.

---

# Issue P1-01 — `updateBusinessTaxFields()` bypasses restore maintenance guard

## Severity

P1 / High

## Evidence

Most coordinator update methods start with:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    throw IllegalStateException("Database writes blocked during restore")
}
```

But `updateBusinessTaxFields()` currently loads the expense and writes an update without that guard.

It also accepts fields that do not exist on `Expense` as no-op parameters:

```text
businessUsePercent
taxCategory
vatEligible
```

The method only maps:

```text
isBusinessExpense
receiptRequired → requiresReceipt
```

## Impact

During unsafe restore mode, this path can still mutate expenses.

Also, callers may think business/tax fields were persisted when some accepted parameters are silently ignored.

## Fixing strategy

Make business/tax update obey the same write-barrier and explicit-field contract as other lifecycle updates.

## Implementation plan

1. Add restore guard at the top:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    throw IllegalStateException("Database writes blocked during restore")
}
```

2. Replace silent no-op parameters with one of these:
   - remove unsupported params from API, or
   - add metadata warning to `TransactionEvent`, or
   - return a sealed result:

```kotlin
sealed interface BusinessTaxUpdateResult {
    data object Updated : BusinessTaxUpdateResult
    data class UnsupportedFields(val fields: List<String>) : BusinessTaxUpdateResult
    data class NotFound(val expenseId: Long) : BusinessTaxUpdateResult
}
```

3. Add tests:

```text
updateBusinessTaxFields_blocks_during_restore
updateBusinessTaxFields_writes_UPDATED_event
updateBusinessTaxFields_does_not_silently_drop_unsupported_fields
```

---

# Issue P1-02 — Failed creates are still invisible in `transaction_events`

## Severity

P1 / High

## Evidence

`TransactionLifecycleCoordinator.createExpense()` returns early for:

```text
ValidationFailed
DuplicateSkipped
InsertConflict
Error
```

Only successful creates definitely write `CREATED`. Duplicates attempt to write `CREATE_DUPLICATE_SKIPPED`, but validation failures and insert conflicts do not appear to write durable lifecycle events.

The old model already defines lifecycle event types for failed/attempted creates, but the coordinator does not consistently use them.

## Impact

When a user asks “why didn’t this expense appear?”, the lifecycle audit cannot always answer:

```text
Was create attempted?
Was validation rejected?
Was it duplicate?
Was it an insert race conflict?
Was restore blocking writes?
```

This overlaps with Pipeline 1 diagnostics.

## Fixing strategy

Make create lifecycle observable even when no expense row is created.

## Implementation plan

1. At start of `createExpense()`, write:

```text
CREATE_ATTEMPTED
```

with `expenseId = null`, source, dedupe key if known, and request metadata.

2. On validation failure, write:

```text
CREATE_VALIDATION_FAILED
```

with validation errors in metadata.

3. On insert conflict, write:

```text
CREATE_INSERT_CONFLICT
```

with dedupe key and source.

4. On restore-blocked write, either:
   - write a diagnostic event to a separate diagnostics table, or
   - return a structured blocked result if transaction events are not safe during restore.

5. Add tests:

```text
validation_failure_writes_transaction_event
insert_conflict_writes_transaction_event
duplicate_skipped_writes_transaction_event
restore_block_returns_structured_result
```

---

# Issue P1-03 — `STRICT_EXTERNAL_ID` idempotency still returns weak `InsertConflict`

## Severity

P1 / High

## Evidence

In `STRICT_EXTERNAL_ID`, coordinator sets:

```text
dedupeKey = idem:{source}:{idempotencyKey}
```

and relies on the unique index. If `insertAtomic()` returns `<= 0`, coordinator returns:

```text
CreateExpenseResult.InsertConflict(dedupeKey)
```

It does not retrieve the existing expense ID.

## Impact

For idempotent systems:

```text
bank sync
email receipt retry
notification retry
CSV/import retry
worker retry
```

a repeat of the same external transaction should return:

```text
AlreadyCreated(existingExpenseId)
```

or at least:

```text
DuplicateSkipped(existingExpenseId)
```

A generic conflict loses the idempotency contract.

## Fixing strategy

Treat idempotent unique-key conflicts as successful duplicate resolution.

## Implementation plan

1. Add DAO:

```kotlin
@Query("SELECT id FROM expenses WHERE dedupeKey = :dedupeKey LIMIT 1")
suspend fun findIdByDedupeKey(dedupeKey: String): Long?
```

2. In `createExpense()` after `insertAtomic()` conflict:

```kotlin
if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID) {
    val existingId = expenseDao.findIdByDedupeKey(expense.dedupeKey!!)
    if (existingId != null) {
        writeDuplicateEvent(...)
        return CreateExpenseResult.DuplicateSkipped(
            existingExpenseId = existingId,
            reason = "Idempotent duplicate"
        )
    }
}
```

3. Add tests:

```text
strict_external_id_repeat_returns_existing_id
strict_external_id_repeat_writes_duplicate_event
strict_external_id_missing_key_validation_fails
```

---

# Issue P1-04 — Debug restore/snapshot methods bypass lifecycle completely

## Severity

P1 / High if available outside debug builds, otherwise P2

## Evidence

`ExpenseRepository` still exposes:

```kotlin
deleteAllExpenses() = expenseDao.deleteAll()

restoreDebugSnapshot(snapshot) {
    database.withTransaction {
        expenseDao.deleteAll()
        expenseDao.insertAll(snapshot.expenses)
    }
}
```

These bypass:

```text
restoreMaintenanceMode
TransactionLifecycleCoordinator
TransactionEventDao
dedupe policy
side effects
```

## Impact

If reachable from UI/tools/release builds, these can wipe or restore expenses without audit events or lifecycle side effects.

## Fixing strategy

Make debug-only destructive writes explicit and guarded.

## Implementation plan

1. Move debug snapshot methods to a `DebugExpenseRepository`.
2. Add build/runtime guard:

```kotlin
if (!BuildConfig.DEBUG) error("Debug snapshots unavailable in release")
```

3. Add restore/write barrier check.
4. Either:
   - write a single `BULK_RESTORED` / `BULK_DELETED` transaction event, or
   - write to a separate debug audit table.
5. Add static guard test:

```text
release_code_does_not_reference_deleteAllExpenses
debug_snapshot_methods_are_debug_only
```

---

# Issue P1-05 — Public DAO mutation surface still enables lifecycle bypass

## Severity

P1 / High architectural risk

## Evidence

`ExpenseDao` still exposes many mutation methods:

```text
insert
insertAtomic
insertAll
delete
deleteAll
update
updateCategory
updateMerchantAndKey
updateTransactionType
updateTransferDirection
updateOwnership columns
clearSharedExpenseFlags
conditionallSetLocation
updateMerchantKey
...
```

Some are necessary internally, but nothing prevents new callers from bypassing the coordinator.

## Impact

The codebase can regress back to mixed old/new behavior.

## Fixing strategy

Add static guard tests and narrow DAO visibility where possible.

## Implementation plan

1. Add a script/test:

```text
fail if app/src/main/java references ExpenseDao.insert/update/delete
outside:
- TransactionLifecycleCoordinator
- migrations
- approved backfill/debug allowlist
```

2. Maintain allowlist:

```text
LocationBackfillWorker direct location methods
MerchantKeyBackfillWorker updateMerchantKey
GroupTransactionCoordinator.clearSharedExpenseFlags
DebugExpenseRepository snapshot methods
```

3. Add CI task:

```text
./gradlew lifecycleBypassGuard
```

4. Add docs:

```text
docs/architecture/CONTRACTS.md
```

with:

```text
All user-visible expense CUD writes must route through TransactionLifecycleCoordinator.
```

---

# Issue P2-06 — Group hard-delete cleanup still directly clears shared flags

## Severity

P2 / Medium

## Evidence

`GroupTransactionCoordinator` still calls:

```kotlin
expenseDao.clearSharedExpenseFlags(expenseId)
```

during group cleanup. The comment says this is a bulk data-integrity operation, not a user-originated edit.

## Impact

That may be acceptable, but it creates an audit gap:

```text
shared expense flags changed
but no UPDATED event exists for each affected expense
```

This is especially sensitive because group deletion changes dashboard/budget semantics.

## Fixing strategy

Keep cleanup bulk-safe, but make it auditable.

## Implementation plan

Option A — aggregate event:

```text
TransactionEvent.BULK_UPDATED
metadata = {
  operation: "GROUP_DELETE_CLEAR_SHARED_FLAGS",
  groupId,
  affectedExpenseIds,
  affectedCount
}
```

Option B — lifecycle per row only for small counts, aggregate for large counts.

Tests:

```text
delete_group_clears_shared_flags_and_writes_bulk_lifecycle_event
delete_group_recalculates_budget_once_after_commit
```

---

# Issue P2-07 — Bulk category/merchant updates skip holistic side effects

## Severity

P2 / Medium

## Evidence

Coordinator bulk methods write `BULK_UPDATED`, but comments say side effects are intentionally skipped to avoid flooding.

That avoids per-row storms, but there should still be one holistic invalidation/recalculation.

## Impact

After bulk updates, these can lag or remain stale:

```text
budget monitor state
anomaly state
merchant/category learning cache
analytics cache if any
dashboard derived warnings if cached
```

Flows may refresh DB-backed UI automatically, but non-Flow side-effect systems need a signal.

## Fixing strategy

Add aggregate post-commit side effects for bulk operations.

## Implementation plan

1. Add dispatcher methods:

```kotlin
suspend fun dispatchAfterBulkCategoryUpdate(merchantKey: String, newCategoryId: Long, affectedCount: Int)
suspend fun dispatchAfterBulkMerchantUpdate(oldMerchantKey: String, newMerchantKey: String, affectedCount: Int)
```

2. These should do at most:

```text
budgetMonitor.checkBudgets()
anomaly cache invalidation/recompute marker
merchant/category cache invalidation
dashboard cache invalidation if relevant
```

3. Return affected count from bulk coordinator methods.

4. Tests:

```text
bulk_category_update_writes_bulk_event
bulk_category_update_dispatches_single_budget_recheck
bulk_merchant_update_does_not_dispatch_per_row_side_effects
```

---

# Issue P2-08 — Delete loads snapshot outside final transaction

## Severity

P2 / Medium

## Evidence

`deleteExpense(expenseId)` loads:

```kotlin
val expense = expenseDao.getById(expenseId)
```

then calls:

```kotlin
deleteExpense(expense)
```

The second method writes event + delete inside a transaction using the already-loaded object.

## Impact

A concurrent update between the load and delete can produce a stale `beforeSnapshot`.

The final delete still deletes by entity, but the audit snapshot may not match the row at deletion time.

## Fixing strategy

Load the row inside the same transaction that writes `DELETED` and deletes.

## Implementation plan

1. Replace overloads with:

```kotlin
suspend fun deleteExpenseById(expenseId: Long, source: String, reason: String?, actor: String?): Result<Unit> {
    return database.withTransaction {
        val current = expenseDao.getById(expenseId) ?: return@withTransaction NotFound
        transactionEventDao.insert(DELETED snapshot from current)
        expenseDao.delete(current)
        Deleted(current)
    }.also {
        dispatch post-commit side effects
    }
}
```

2. Keep `deleteExpense(expense)` deprecated or internal only.

3. Tests:

```text
delete_uses_latest_snapshot
delete_writes_event_and_deletes_atomically
delete_not_found_returns_failure
```

---

# Issue P2-09 — No explicit soft-delete/undo contract

## Severity

P2 / Medium

## Evidence

The current coordinator performs hard delete:

```text
write DELETED event
→ expenseDao.delete(expense)
```

The pipeline checklist asks for delete/soft-delete behavior, but the code appears to have only hard-delete.

## Impact

Hard delete is okay only if the contract is explicit. But it affects:

```text
receipt links
group links
recurring links
analytics history
undo support
audit trails
exports
```

The `DELETED` event preserves a snapshot, but downstream links may become orphaned unless FK/cascade rules are correct.

## Fixing strategy

Define delete semantics explicitly.

## Implementation plan

1. Choose one:

```text
A. Hard delete + event snapshot + FK/cascade guarantees
B. Soft delete with deletedAt/deletedReason/deletedBy
C. Hybrid: soft delete for user actions, hard delete for restore/debug
```

2. If hard delete remains:
   - add orphan diagnostics for receipt/group/recurring links,
   - add FK cascade tests,
   - document that `TransactionEvent.beforeSnapshot` is the audit source.

3. Tests:

```text
delete_expense_preserves_deleted_snapshot
delete_expense_does_not_leave_orphan_receipt_link
delete_expense_unlinks_recurring_occurrence
delete_expense_group_link_policy_is_explicit
```

---

# Issue P2-10 — Deferred side-effect contract is caller-enforced, not type-enforced

## Severity

P2 / Medium

## Evidence

The current fix relies on callers remembering:

```kotlin
createExpense(request, SideEffectMode.DEFER)
...
dispatchPostCreationSideEffects(id, source)
```

Manual, review, and group currently do this correctly.

But the API still allows this dangerous pattern inside outer transactions:

```kotlin
database.withTransaction {
    coordinator.createExpense(request) // default IMMEDIATE
}
```

## Impact

A future caller can reintroduce the old bug.

## Fixing strategy

Make nested transaction side effects harder to misuse.

## Implementation plan

Preferred:

```kotlin
sealed interface LifecycleCreateResult {
    data class Created(
        val expenseId: Long,
        val postCommitAction: suspend () -> Unit
    )
}
```

or:

```kotlin
suspend fun createExpenseDbOnly(...): CreateExpenseTxResult
suspend fun dispatch(result: CreateExpenseTxResult)
```

Minimum:

1. Add KDoc warning.
2. Add lint/static guard:

```text
fail if "database.withTransaction" block contains "createExpense(request)" without "SideEffectMode.DEFER"
```

3. Tests:

```text
manual_create_dispatches_side_effects_after_outer_commit
review_approval_dispatches_side_effects_after_outer_commit
group_expense_dispatches_side_effects_after_outer_commit
```

---

# Issue P2-11 — Duplicate event writing is best-effort and not reflected in result

## Severity

P2 / Medium

## Evidence

`writeDuplicateEvent()` catches errors and logs warning instead of surfacing failure.

## Impact

A duplicate can be skipped with no durable audit event.

## Fixing strategy

Expose event logging status.

## Implementation plan

1. Extend result:

```kotlin
data class DuplicateSkipped(
    val existingExpenseId: Long,
    val reason: String,
    val eventLogged: Boolean
)
```

2. In debug/RC builds, optionally fail if duplicate audit cannot be written.

3. Tests:

```text
duplicate_skipped_reports_event_logged_true
duplicate_event_failure_is_visible_in_result
```

---

# Issue P2-12 — Some post-commit paths may duplicate budget checks

## Severity

P2 / Medium

## Evidence

`ReviewQueueRepository.markAsRelevant()` dispatches lifecycle post-creation side effects and then separately calls `budgetMonitor.checkBudgets()` when `shouldCheckBudgets` is true.

Because lifecycle creation side effects already include budget monitoring, this can double-run budget checks.

## Impact

Usually harmless, but can cause:

```text
duplicate budget alerts
extra worker/load
hard-to-read logs
```

## Fixing strategy

Make the lifecycle dispatcher the single owner of budget side effects.

## Implementation plan

1. Remove manual budget check after successful lifecycle dispatch, or gate it:

```kotlin
if (!lifecycleSideEffectsDispatched) budgetMonitor.checkBudgets()
```

2. Add idempotency to budget alerts if not already present.

3. Tests:

```text
mark_as_relevant_created_expense_runs_budget_check_once
review_approval_created_expense_runs_budget_check_once
```

---

# Recommended fixing order

## PR 1 — Restore barrier + failed-create events

Files:

```text
TransactionLifecycleCoordinator.kt
LifecycleEventType.kt
TransactionEventDao.kt
TransactionLifecycleCoordinatorTest.kt
```

Fix:

```text
- add restore guard to updateBusinessTaxFields
- write CREATE_ATTEMPTED / CREATE_VALIDATION_FAILED / CREATE_INSERT_CONFLICT
- expose structured restore-blocked result if needed
```

## PR 2 — Idempotency conflict hardening

Files:

```text
ExpenseDao.kt
TransactionLifecycleCoordinator.kt
CreateExpenseResult.kt
```

Fix:

```text
STRICT_EXTERNAL_ID conflict → existing ID / DuplicateSkipped
```

## PR 3 — Direct mutation guard

Files:

```text
scripts/lifecycle-bypass-guard.*
build.gradle.kts
docs/architecture/CONTRACTS.md
```

Fix:

```text
CI fails on unapproved direct ExpenseDao mutation usage.
```

## PR 4 — Bulk lifecycle side effects

Files:

```text
TransactionLifecycleCoordinator.kt
TransactionSideEffectDispatcher.kt
ExpenseRepository.kt
```

Fix:

```text
bulk category/merchant update dispatches one aggregate post-commit recalculation.
```

## PR 5 — Delete contract hardening

Files:

```text
TransactionLifecycleCoordinator.kt
ExpenseDao.kt
ReceiptExpenseLinkDao.kt
GroupExpenseDao.kt
RecurringOccurrenceDao.kt
```

Fix:

```text
load delete snapshot inside transaction
define hard-vs-soft delete contract
add orphan regression tests
```

## PR 6 — Remove or isolate legacy receipt creation API

Files:

```text
ReceiptRepository.kt
ReceiptLifecycleCoordinator.kt
ReceiptScanViewModel.kt
EmailReceiptIngestionService.kt
BankStatementLifecycleProcessor.kt
```

Fix:

```text
verify all callers migrated
make createExpenseFromReceipt internal/debug-only or remove in v2.0
```

---

# Golden tests to add

```text
manual_create_writes_CREATED_event_and_dispatches_after_commit
review_approval_writes_CREATED_and_marks_review_APPROVED_atomically
review_approval_receipt_link_failure_rolls_back_expense
group_expense_link_failure_rolls_back_system_expense_and_no_side_effects
duplicate_create_writes_CREATE_DUPLICATE_SKIPPED_with_existing_id
strict_external_id_retry_returns_existing_expense_id
validation_failed_create_writes_CREATE_VALIDATION_FAILED
insert_conflict_writes_CREATE_INSERT_CONFLICT
update_category_writes_UPDATED_event_and_budget_side_effect_once
update_merchant_recomputes_merchantKey_and_dedupeKey
delete_expense_writes_DELETED_with_latest_snapshot
delete_expense_unlinks_recurring_occurrence
restore_mode_blocks_create_update_delete_business_tax
bulk_category_update_writes_BULK_UPDATED_and_dispatches_single_recalc
debug_deleteAll_not_available_in_release
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "expenseDao.insert" app/src/main/java
grep -R "expenseDao.update" app/src/main/java
grep -R "expenseDao.delete" app/src/main/java
grep -R "deleteAllExpenses" app/src/main/java
grep -R "restoreDebugSnapshot" app/src/main/java
grep -R "createExpense(request)" app/src/main/java
grep -R "SideEffectMode.IMMEDIATE" app/src/main/java
grep -R "updateBusinessTaxFields" app/src/main/java
```

Allowed direct DAO mutation list should be explicit:

```text
TransactionLifecycleCoordinator
approved Room migrations
approved debug-only repository
LocationBackfillWorker limited location writes
MerchantKeyBackfillWorker limited merchantKey writes
Group hard-delete cleanup, if aggregate audit event is added
```

Definition of done:

```text
- No user-visible create/update/delete bypasses TransactionLifecycleCoordinator.
- updateBusinessTaxFields obeys restore maintenance mode.
- Failed creates are visible in TransactionEvent or diagnostics.
- STRICT_EXTERNAL_ID retry returns existing expense ID.
- Bulk updates write aggregate audit + run one aggregate recalculation.
- Delete snapshot is loaded inside delete transaction.
- Debug destructive methods are debug-only and audited.
- Static guard prevents new lifecycle bypasses.
```

---

# Source files inspected

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `ManualExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt

- `ReviewQueueRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `GroupTransactionCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt