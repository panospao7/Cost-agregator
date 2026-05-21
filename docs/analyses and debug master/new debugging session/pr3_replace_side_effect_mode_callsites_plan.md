# PR 3 — Replace `SideEffectMode` Callsites

## Baseline checked

Current referenced code at `fc002a583674d9e1734412c9df232e41d621549b` still has production callsites using:

```kotlin
createExpense(request, SideEffectMode.DEFER)
createExpense(request) // implicit IMMEDIATE
dispatchPostCreationSideEffects(...)
```

Main affected files:

```text
TransactionLifecycleCoordinator.kt
ReceiptLifecycleCoordinator.kt
ReviewQueueRepository.kt
NotificationProcessingPipeline.kt
GroupTransactionCoordinator.kt
BankApiIntegration.kt
```

PR3 assumes PR1 and PR2 are already merged:

```text
PostCommitAction
PostCommitActionBatch
PostCommitActionRunner
MutationResult<T>
TransactionSideEffectPlanner
createExpenseDbOnlyV2(...)
createExpenseStandaloneV2(...)
```

If PR2 did not add the V2 APIs, add them as the first step in this PR.

---

# 1. Goal

Remove `SideEffectMode` from production callsites and replace manual deferred dispatch with typed post-commit action batches.

Target rule:

```text
Standalone create:
  createExpenseStandaloneV2(request)
  -> coordinator commits DB
  -> coordinator runs planned post-commit actions once

Nested/outer transaction create:
  createExpenseDbOnlyV2(request)
  -> returns MutationResult<CreateExpenseResult>
  -> outer owner collects postCommitActions
  -> outer owner runs actions after its transaction commits
```

---

# 2. Non-goals

Do not include these in PR3:

```text
Receipt side-effect planner
Email full double-dispatch redesign beyond transaction-created actions
Group updateOwnership nested side-effect refactor
Recurring/reminder action migration
Worker/batch integration
Static guard script
Removal of compatibility APIs
```

Those belong to later PRs.

---

# 3. Required end state

After PR3:

```bash
rg "SideEffectMode" app/src/main/java
```

Allowed only in:

```text
SideEffectMode.kt
TransactionLifecycleCoordinator.kt compatibility method/KDoc
```

After PR3:

```bash
rg "createExpense\\(" app/src/main/java
```

Allowed only in:

```text
TransactionLifecycleCoordinator.kt compatibility wrapper
```

All other production callers must use:

```kotlin
createExpenseStandaloneV2(request)
createExpenseDbOnlyV2(request)
```

After PR3:

```bash
rg "dispatchPostCreationSideEffects" app/src/main/java
```

Allowed only in:

```text
TransactionLifecycleCoordinator.kt deprecated compatibility wrapper
```

No production service/coordinator should call it directly.

---

# 4. Coordinator API contract

## 4.1 DB-only API

Expected shape:

```kotlin
suspend fun createExpenseDbOnlyV2(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult>
```

Rules:

```text
Created -> postCommitActions from TransactionSideEffectPlanner
DuplicateSkipped -> empty actions
ValidationFailed -> empty actions
InsertConflict -> empty actions
Error -> empty actions
```

## 4.2 Standalone API

Expected shape:

```kotlin
suspend fun createExpenseStandaloneV2(
    request: CreateExpenseRequest
): CreateExpenseResult
```

Implementation:

```kotlin
val mutation = createExpenseDbOnlyV2(request)
if (mutation.value is CreateExpenseResult.Created) {
    postCommitActionRunner.run(mutation.postCommitActions)
}
return mutation.value
```

Rules:

```text
Actions run only after DB commit.
Runner failure does not rollback create.
Cancellation from runner is rethrown.
```

## 4.3 Legacy APIs

Keep temporarily:

```kotlin
createExpense(request, sideEffectMode)
createExpenseStandalone(request)
createExpenseDbOnly(request)
dispatchPostCreationSideEffects(...)
```

But mark as compatibility/deprecated and remove production usage.

---

# 5. File-by-file implementation

## 5.1 `TransactionLifecycleCoordinator.kt`

### Tasks

1. Ensure `PostCommitActionRunner` is injected.
2. Ensure `TransactionSideEffectPlanner` is injected.
3. Ensure `createExpenseDbOnlyV2()` owns the DB mutation only and returns action batch.
4. Ensure `createExpenseStandaloneV2()` runs returned batch exactly once.
5. Keep old APIs only as wrappers.
6. Remove internal usage of `SideEffectMode.IMMEDIATE/DEFER` from preferred APIs.
7. Deprecate `dispatchPostCreationSideEffects(...)`.

### Important

Do not dispatch inside `database.withTransaction`.

Correct shape:

```kotlin
suspend fun createExpenseDbOnlyV2(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult> {
    val result = createExpenseCoreDbOnly(request)

    val actions = when (result) {
        is CreateExpenseResult.Created ->
            transactionSideEffectPlanner.planCreated(
                expenseId = result.expenseId,
                source = request.source,
                correlationId = request.correlationId
            )
        else -> PostCommitActionBatch.empty(request.correlationId ?: CorrelationIds.newId())
    }

    return MutationResult(result, actions)
}
```

### Compatibility wrapper

```kotlin
@Deprecated(...)
suspend fun createExpense(
    request: CreateExpenseRequest,
    sideEffectMode: SideEffectMode = SideEffectMode.IMMEDIATE
): CreateExpenseResult {
    val mutation = createExpenseDbOnlyV2(request)
    if (sideEffectMode == SideEffectMode.IMMEDIATE) {
        postCommitActionRunner.run(mutation.postCommitActions)
    }
    return mutation.value
}
```

No production caller should use this after PR3.

---

## 5.2 `ReceiptLifecycleCoordinator.kt`

Current risks:

```text
createExpense(..., SideEffectMode.DEFER)
dispatchPostCreationSideEffects(...)
email path dispatches create side effects for duplicate existing expenses
```

### Replace direct receipt-created expense flow

Before:

```kotlin
database.withTransaction {
    val result = transactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)
    linkReceiptToExpense(...)
}
transactionLifecycleCoordinator.dispatchPostCreationSideEffects(expenseId, RECEIPT_SCAN)
```

After:

```kotlin
val txOutcome = database.withTransaction {
    val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)

    when (val result = mutation.value) {
        is CreateExpenseResult.Created -> {
            receiptLinkService.linkReceiptToExpense(...)
            ReceiptCreateOutcome.Success(
                expenseId = result.expenseId,
                actions = mutation.postCommitActions
            )
        }
        is CreateExpenseResult.DuplicateSkipped -> ReceiptCreateOutcome.Duplicate
        ...
    }
}

if (txOutcome is ReceiptCreateOutcome.Success) {
    postCommitActionRunner.run(txOutcome.actions)
}
```

### Email receipt path

Current behavior adds both created and duplicate existing expense IDs to `expenseIds`, then dispatches post-create side effects for all of them.

Fix:

```kotlin
val createdExpenseActions = mutableListOf<PostCommitActionBatch>()
val linkedExistingExpenseIds = mutableListOf<Long>()
val createdExpenseIds = mutableListOf<Long>()
```

Rules:

```text
Created expense:
  add result.expenseId to createdExpenseIds
  add mutation.postCommitActions to batch
  link receipt

Duplicate existing:
  add existingExpenseId to linkedExistingExpenseIds
  link receipt
  DO NOT run EXPENSE_CREATED side effects
```

After transaction:

```kotlin
postCommitActionRunner.run(combinedCreatedExpenseActions)
```

Do not call:

```kotlin
dispatchPostCreationSideEffects(...)
```

---

## 5.3 `ReviewQueueRepository.kt`

Current paths:

```text
approveReview() uses createExpenseDbOnly(request) then dispatchPostCreationSideEffects(...)
markAsRelevant() uses createExpense(request, SideEffectMode.DEFER)
```

### `approveReview()`

Inside transaction:

```kotlin
val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
```

On `Created`:

```kotlin
ReviewApprovalTxOutcome.Approved(
    expenseId = id,
    actions = mutation.postCommitActions
)
```

After transaction:

```kotlin
postCommitActionRunner.run(outcome.actions)
```

Then keep existing review-specific post-commit work:

```text
classifier retraining
merchant alias learning
confidence cache invalidation
```

For now, those remain `runPostCommitSafely`; later PRs can convert them into action batches.

### `markAsRelevant()`

Inside transaction:

```kotlin
val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
```

Return:

```kotlin
MarkAsRelevantOutcome(
    createdExpenseId = expenseId,
    transactionActions = mutation.postCommitActions,
    ...
)
```

After commit:

```kotlin
postCommitActionRunner.run(outcome.transactionActions)
```

Remove direct call to:

```kotlin
dispatchPostCreationSideEffects(...)
```

---

## 5.4 `NotificationProcessingPipeline.kt`

Current auto-accept flow:

```kotlin
coordinator.createExpense(request, SideEffectMode.DEFER)
...
coordinator.dispatchPostCreationSideEffects(...)
```

### Required refactor

Change `ParsedDbOutcome.AutoAccepted` to carry actions:

```kotlin
data class AutoAccepted(
    val rawId: Long,
    val expenseId: Long,
    val insertedExpense: Expense,
    val transactionActions: PostCommitActionBatch
) : ParsedDbOutcome
```

Inside DB transaction:

```kotlin
val mutation = coordinator.createExpenseDbOnlyV2(request)
```

On created:

```kotlin
ParsedDbOutcome.AutoAccepted(
    rawId = rawId,
    expenseId = result.expenseId,
    insertedExpense = expense,
    transactionActions = mutation.postCommitActions
)
```

In `runParsedPostCommitActions()`:

```kotlin
postCommitActionRunner.run(dbOutcome.transactionActions)
```

Remove direct call to:

```kotlin
coordinator.dispatchPostCreationSideEffects(...)
```

Keep existing notification-specific post-commit actions for now:

```text
transfer analytics
classifier training
recommendation enrichment
subscription detection
```

Those are later side-effect-model candidates, not PR3 blockers.

---

## 5.5 `GroupTransactionCoordinator.kt`

Current path:

```kotlin
database.withTransaction {
    transactionLifecycleCoordinator.createExpense(..., SideEffectMode.DEFER)
    groupExpenseDao.insert(...)
}.also {
    transactionLifecycleCoordinator.dispatchPostCreationSideEffects(...)
}
```

### Refactor

Create a local outcome:

```kotlin
data class GroupCreateSystemExpenseTxOutcome(
    val result: GroupExpenseCreationResult,
    val actions: PostCommitActionBatch
)
```

Inside transaction:

```kotlin
val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
```

On created:

```kotlin
GroupCreateSystemExpenseTxOutcome(
    result = GroupExpenseCreationResult.Success(...),
    actions = mutation.postCommitActions
)
```

After transaction:

```kotlin
if (outcome.result is GroupExpenseCreationResult.Success) {
    postCommitActionRunner.run(outcome.actions)
}
```

Remove direct call to:

```kotlin
dispatchPostCreationSideEffects(...)
```

### Important deferred issue

Do **not** fix this in PR3 unless it is trivial:

```kotlin
normalizeLinkedSystemExpense(...)
    -> transactionLifecycleCoordinator.updateOwnership(...)
```

That is still a nested side-effect risk, but it is not a `SideEffectMode` callsite. Leave it for the dedicated group/nested-flow PR.

---

## 5.6 `BankApiIntegration.kt`

Current path:

```kotlin
coordinator.createExpense(request)
```

This implicitly uses immediate side effects.

### Replace with standalone V2

```kotlin
when (val result = coordinator.createExpenseStandaloneV2(request)) {
    ...
}
```

Because bank sync is currently item-by-item and not wrapping `createExpense` in an outer Room transaction, standalone is correct.

### Future worker/batch note

When bank sync becomes a true chunked worker, migrate again to:

```kotlin
createExpenseDbOnlyV2(...)
collect actions per item/chunk
runner.run(batch) after item/chunk commit
```

That belongs to worker/batch PR.

---

## 5.7 Import/CSV/JSON paths, if present after PR7

Search:

```bash
rg "createExpense|createExpenseStandalone|createExpenseDbOnly|SideEffectMode" app/src/main/java/com/yourname/expensetracker
```

For import rows:

```text
single row standalone import -> createExpenseStandaloneV2
chunk transaction import -> createExpenseDbOnlyV2 + collect actions + run after chunk commit
```

Do not call `dispatchPostCreationSideEffects`.

---

# 6. New helper patterns

## 6.1 Batch combine helper

If PR1 did not add this, add:

```kotlin
fun Iterable<PostCommitActionBatch>.combine(
    correlationId: String
): PostCommitActionBatch
```

Use it in email/import/batch flows.

## 6.2 Post-commit runner helper

Add small local helper where needed:

```kotlin
private suspend fun runTransactionActionsSafely(
    batch: PostCommitActionBatch
) {
    if (batch.actions.isEmpty()) return
    postCommitActionRunner.run(batch)
}
```

Rules:

```text
CancellationException must propagate.
Non-cancellation failures should already be captured by runner outcome.
```

Do not wrap runner in `runCatching` that swallows cancellation.

---

# 7. DI changes

Files likely needing constructor injection:

```text
ReceiptLifecycleCoordinator.kt
ReviewQueueRepository.kt
NotificationProcessingPipeline.kt
GroupTransactionCoordinator.kt
```

Inject:

```kotlin
private val postCommitActionRunner: PostCommitActionRunner
```

`BankApiIntegration` does not need runner directly if it uses `createExpenseStandaloneV2`.

Update Hilt/test constructors accordingly.

---

# 8. Result model changes

Where functions currently return only IDs, add local internal outcomes carrying action batches.

Examples:

```kotlin
private data class ReviewApprovalTxOutcome(
    val result: Result,
    val expenseId: Long?,
    val transactionActions: PostCommitActionBatch
)
```

```kotlin
private data class MarkAsRelevantOutcome(
    val shouldTrainAsTransaction: Boolean,
    val createdExpenseId: Long? = null,
    val transactionActions: PostCommitActionBatch = PostCommitActionBatch.empty(...)
)
```

Avoid storing actions in database entities or public UI DTOs.

---

# 9. Behavioral rules

## Created expense

Run actions after outer commit:

```text
EXPENSE_CREATED action batch runs exactly once.
```

## Duplicate skipped

Do not run create actions:

```text
Duplicate existing expense must not trigger budget/anomaly/merchant/recurring created side effects.
```

This is especially important in email receipt ingestion.

## Validation/insert conflict/error

No post-commit transaction actions.

## Transaction rollback

If outer transaction throws or rolls back:

```text
do not run actions
```

## Cancellation

If runner is cancelled:

```text
emit CANCELLED from runner
rethrow CancellationException
```

---

# 10. Tests

## Static audit tests / grep assertions

Add a small test or script-like check if available:

```text
no_production_SideEffectMode_callsite_outside_compat
no_production_dispatchPostCreationSideEffects_callsite_outside_compat
no_production_createExpense_legacy_callsite_outside_coordinator
```

If static guard PR is later, keep this as manual acceptance for now.

## Coordinator tests

```text
createExpenseStandaloneV2_runs_actions_once_after_commit
createExpenseDbOnlyV2_returns_actions_without_running
createExpense_legacy_immediate_delegates_to_standalone_behavior
createExpense_legacy_defer_returns_without_running
duplicate_create_returns_empty_action_batch
validation_failed_returns_empty_action_batch
insert_conflict_returns_empty_action_batch
```

## Receipt tests

```text
receipt_create_expense_uses_db_only_v2
receipt_create_expense_runs_actions_after_receipt_link_commit
receipt_create_expense_rollback_does_not_run_actions
email_created_expense_runs_transaction_actions_once
email_duplicate_existing_does_not_run_expense_created_actions
```

## Review tests

```text
review_approval_collects_transaction_actions_inside_tx
review_approval_runs_actions_after_commit
review_approval_rollback_does_not_run_actions
review_duplicate_does_not_run_transaction_create_actions
mark_relevant_created_expense_runs_actions_after_commit
```

## Notification tests

```text
notification_auto_accept_returns_transaction_actions
notification_auto_accept_runs_actions_after_pipeline_tx_commit
notification_duplicate_does_not_run_actions
notification_auto_accept_rollback_does_not_run_actions
```

## Group tests

```text
group_create_system_expense_uses_db_only_v2
group_create_system_expense_runs_actions_after_group_link_commit
group_create_system_expense_link_failure_does_not_run_actions
```

## Bank tests

```text
bank_sync_uses_createExpenseStandaloneV2
bank_created_expense_runs_actions_once
bank_duplicate_does_not_run_create_actions
```

---

# 11. Implementation order

## Step 1 — Verify V2 APIs

Confirm these exist:

```text
createExpenseDbOnlyV2
createExpenseStandaloneV2
MutationResult
PostCommitActionBatch
PostCommitActionRunner
```

If missing, add minimal versions before callsite migration.

## Step 2 — Migrate standalone callers

Start with safe/simple callers:

```text
BankApiIntegration.kt
manual standalone callers, if any
```

Replace implicit immediate `createExpense(request)` with:

```kotlin
createExpenseStandaloneV2(request)
```

## Step 3 — Migrate notification auto-accept

Change auto-accept outcome to carry action batch.

Run batch in existing post-commit stage.

## Step 4 — Migrate review approval and markAsRelevant

Replace legacy DB-only calls and deferred dispatch with action batch carry-out.

## Step 5 — Migrate receipt flows

Replace all `createExpense(..., SideEffectMode.DEFER)` and remove manual transaction dispatch.

Fix email duplicate existing behavior while there.

## Step 6 — Migrate group create-system-expense link

Replace legacy defer call and manual dispatch.

Leave `updateOwnership` nested-flow risk for later PR.

## Step 7 — Clean imports/TODOs

Remove:

```kotlin
import SideEffectMode
@Suppress("DEPRECATION_ERROR")
TODO: migrate to createExpenseDbOnly()
```

from migrated production files.

## Step 8 — Run grep audit

Required:

```bash
rg "SideEffectMode" app/src/main/java
rg "dispatchPostCreationSideEffects" app/src/main/java
rg "createExpense\\(" app/src/main/java
```

Confirm only compatibility locations remain.

---

# 12. Acceptance criteria

PR3 is done when:

```text
1. No production caller uses SideEffectMode.

2. No production caller calls createExpense(request, SideEffectMode.*).

3. No production caller calls dispatchPostCreationSideEffects directly.

4. Outer transaction owners call createExpenseDbOnlyV2 and run returned actions after commit.

5. Standalone owners call createExpenseStandaloneV2.

6. Created expenses run transaction side effects once.

7. Duplicate/validation/conflict/error results run no EXPENSE_CREATED side effects.

8. Email duplicate-existing path no longer dispatches created-expense actions.

9. Rollbacks do not run collected action batches.

10. Cancellation from post-commit runner is not swallowed.

11. Legacy APIs remain only for compatibility/tests and are deprecated.
```

---

# 13. Sources checked

- Current commit:  
  https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b

- `SideEffectMode.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `ReviewQueueRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `NotificationProcessingPipeline.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `BankApiIntegration.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `GroupTransactionCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- Attached plan: `global_side_effect_dispatch_contract_plan.md`