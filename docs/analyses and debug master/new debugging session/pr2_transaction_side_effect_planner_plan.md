# PR 2 — Transaction Side-Effect Planner

## Baseline
Current `fc002a583674d9e1734412c9df232e41d621549b` state still has:

- `TransactionLifecycleCoordinator` owning create/update/delete/bulk mutation flow.
- `SideEffectMode.IMMEDIATE/DEFER` still relying on caller discipline.
- `dispatchPostCreationSideEffects(...)` as a legacy post-commit API.
- `TransactionSideEffectDispatcher` still containing the actual transaction-side-effect business logic.
- `updateExpense`, `updateCategory`, `updateBusinessFlags`, `updateMerchant`, `updateType`, `updateTransferDetails`, `bulkUpdateMerchant`, and `deleteExpense` still dispatching side effects directly after commit.
- `updateExpense` and `deleteExpense` still doing recurring reconciliation/unlinking as ad hoc post-commit calls.

This PR should move all transaction side effects into a planner-backed batch model without breaking existing public APIs yet.

---

## Goal
Introduce a **transaction-specific side-effect planner** that:

1. decides which post-commit actions should run for each transaction mutation,
2. packages them as `PostCommitActionBatch`,
3. runs them exactly once after commit,
4. keeps current public coordinator methods working as compatibility wrappers.

---

## Non-goals
Do **not** do these in PR2:

- remove `SideEffectMode`
- migrate receipt/email/bank/import callers yet
- add static guards yet
- change provenance/source-link code
- change database schema
- replace all current public coordinator APIs with new V2 names unless needed internally

---

## Files to add

- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionUpdateKind.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlannerTest.kt`

Optional if you want an internal batch carrier:
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionMutationResult.kt`

---

## Files to modify

- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt`  
  - docs only; keep for compatibility
- `app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcherTest.kt`  
  - or replace with planner tests if that file does not exist
- optionally `ReceiptLifecycleCoordinatorTest.kt` for regression coverage only

---

## New planner contract

### Recommended API
Use the planner as the source of truth for transaction side effects:

```kotlin
interface TransactionSideEffectPlanner {
    fun planCreated(
        expenseId: Long,
        source: ExpenseSource,
        correlationId: String?
    ): PostCommitActionBatch

    fun planUpdated(
        expenseId: Long,
        source: String,
        correlationId: String?,
        kind: TransactionUpdateKind
    ): PostCommitActionBatch

    fun planDeleted(
        expenseId: Long,
        source: String,
        correlationId: String?
    ): PostCommitActionBatch

    fun planBulkUpdated(
        source: String,
        affectedCount: Int,
        correlationId: String?
    ): PostCommitActionBatch
}
```

### `TransactionUpdateKind`
Use a small enum so the planner can distinguish current update paths:

- `FULL`
- `CATEGORY_ONLY`
- `LOCATION_ONLY`
- `BUSINESS_FLAGS_ONLY`
- `MERCHANT`
- `TYPE`
- `TRANSFER_DETAILS`

This lets the planner preserve current behavior while avoiding special-case code in the coordinator.

---

## Action mapping rules

### 1) Expense created
Planned actions should preserve current order:

1. `budget_check`
2. `anomaly_alert_check`
3. `merchant_category_pattern_learning`
4. `merchant_canonical_stats_update`
5. `recurring_occurrence_matching`

Rules:
- `merchant_category_pattern_learning` only if category exists.
- `merchant_canonical_stats_update` only if a canonical merchant row exists.
- recurring matching should be a single idempotent action, not ad hoc coordinator logic.

### 2) Expense updated
Plan:

- `budget_check`
- `anomaly_alert_check`
- `merchant_category_pattern_learning`
- `merchant_canonical_stats_update`
- optional `recurring_reconcile` when key fields changed

Rules:
- location-only updates should return an empty batch.
- category-only/business-flags updates should not trigger recurring reconciliation.
- merchant/type updates should trigger recurring reconciliation if they affect matching.
- recurring reconciliation should be one action that re-reads current state and decides whether to unlink/relink.

### 3) Expense deleted
Plan:

- `budget_check`
- `recurring_unlink`

Rules:
- recurring unlink must be a single idempotent action.
- the delete path should stop doing separate post-commit unlink code.

### 4) Bulk merchant update
Plan:

- one aggregate `bulk_budget_check` action only

Rules:
- zero affected rows should return an empty batch or a skipped `NO_WORK` action.
- do not emit one action per affected expense.

---

## PR2 coordinator integration

### `TransactionLifecycleCoordinator`
Replace direct side-effect business logic with planner + runner flow.

#### Create path
- Build the expense and lifecycle event as today.
- Compute the created-side-effect batch through the new planner.
- If the call is standalone / immediate, run the batch after commit.
- If the call is deferred, do not run anything yet.

#### Legacy deferred API
Keep `dispatchPostCreationSideEffects(...)`, but make it a wrapper that:
- loads the committed expense state if needed,
- asks the planner for the create batch,
- passes that batch to the post-commit runner.

This preserves current callers like receipt flows.

#### Update paths
For:
- `updateExpense`
- `updateCategory`
- `updateBusinessFlags`
- `updateMerchant`
- `updateType`
- `updateTransferDetails`

replace the current `sideEffectDispatcher.dispatchOnUpdated(...)` plus separate recurring reconciliation code with one planner-backed batch execution.

#### Delete paths
For both delete overloads:
- replace `dispatchOnDeleted(...)`
- replace separate recurring unlink code
- run one delete batch after commit

#### Bulk merchant update
Replace the current bulk budget dispatch with a planner-backed bulk batch.

---

## `TransactionSideEffectDispatcher` refactor
Keep the file as a **compatibility facade** only.

It should stop containing the actual transaction business logic and instead:

- inject `TransactionSideEffectPlanner`
- inject the PR1 post-commit runner
- convert legacy calls into planned batches
- run the batch after commit

So the file becomes a bridge for old callers, not the business-rule owner.

---

## `SideEffectMode` handling
Keep it for now.

But:
- document it as compatibility-only
- do not add new callsites that depend on manual discipline
- keep `createExpenseStandalone` / `createExpenseDbOnly` working through the new planner path

No static guard yet in PR2; that is PR10.

---

## Side-effect outcome rules
Use the PR1 typed outcome model.

Planner actions should classify common cases as:

- `Skipped(NOT_APPLICABLE)` for location-only updates or missing category/canonical merchant
- `Skipped(ALREADY_PROCESSED)` for idempotent recurring link/unlink cases
- `Skipped(NO_WORK)` for bulk update with zero affected rows
- `FailedRetryable` for transient infrastructure problems
- `FailedFinal` only for explicit unrecoverable cases

Do not swallow failures in the planner path.

---

## Idempotency rules
Each planned action must get a deterministic idempotency key, for example:

- `expense:{id}:created:budget_check`
- `expense:{id}:created:anomaly_alert_check`
- `expense:{id}:created:merchant_category_learning`
- `expense:{id}:created:merchant_stats`
- `expense:{id}:created:recurring_matching`
- `expense:{id}:updated:budget_check`
- `expense:{id}:deleted:recurring_unlink`
- `bulk:{source}:{affectedCount}:budget_check`

This is what prevents duplicate dispatch storms.

---

## Metadata rules
Planner-generated metadata must stay safe:
- expense id
- source
- trigger kind
- correlation id
- affected count
- boolean flags like `hasCategory` / `needsRecurringReconcile`

Do **not** include raw merchant text, notes, OCR text, email content, or bank text.

---

## Recommended implementation order

1. Add `TransactionSideEffectPlanner` and `TransactionUpdateKind`.
2. Add planner unit tests for create/update/delete/bulk batches.
3. Refactor `TransactionSideEffectDispatcher` into a compatibility facade.
4. Update `TransactionLifecycleCoordinator` to use planner + runner internally.
5. Keep `dispatchPostCreationSideEffects(...)` as a wrapper for deferred legacy flows.
6. Replace separate recurring reconciliation/unlink logic with planner actions.
7. Update coordinator tests to verify batches run once after commit.
8. Add regression tests for no-op update paths and bulk no-work cases.

---

## Tests

### Planner unit tests
- `created_plans_budget_anomaly_merchant_recurring_actions`
- `category_only_update_skips_recurring_reconcile`
- `location_only_update_returns_empty_batch`
- `business_flags_update_plans_budget_and_anomaly_only`
- `merchant_update_plans_recurring_reconcile`
- `type_update_plans_recurring_reconcile`
- `delete_plans_budget_and_recurring_unlink`
- `bulk_update_plans_single_budget_check`
- `bulk_update_with_zero_rows_returns_no_work`

### Coordinator tests
- `createExpense_immediate_runs_planned_actions_once`
- `createExpense_deferred_does_not_run_actions_inside_transaction`
- `dispatchPostCreationSideEffects_runs_same_batch_after_commit`
- `updateExpense_runs_planned_actions_once`
- `deleteExpense_runs_planned_actions_once`
- `bulkUpdateMerchant_runs_one_aggregate_action`
- `locationOnlyUpdate_runs_no_side_effects`

### Regression tests
- duplicate create does not schedule transaction side effects
- insert conflict does not run post-commit actions
- recurring reconciliation is not duplicated by separate coordinator code paths
- cancellation is propagated, not swallowed

---

## Acceptance criteria

PR2 is done when:

- transaction side effects are described as planner-generated batches
- `TransactionSideEffectDispatcher` no longer owns the business logic
- create/update/delete/bulk mutation paths use the planner
- recurring reconcile/unlink is a single planned action, not ad hoc code
- `SideEffectMode` still works for compatibility, but is no longer the architectural center
- deferred flows like receipt processing still work through the legacy wrapper
- side-effect execution remains exactly once after commit
- no new schema or callsite migration is required yet

---

## Sources checked

- Current commit:  
  https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `SideEffectMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt

- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `SideEffectDiagnosticRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SideEffectDiagnosticRecorder.kt

- `EventOutcome.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventOutcome.kt

- `DiagnosticReasonCode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticReasonCode.kt