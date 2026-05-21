# PR 6 — Group / Nested Transaction Flows

## Assumptions

PR1–PR5 are already merged:

- `PostCommitActionBatch` exists.
- `PostCommitActionRunner` exists.
- `TransactionSideEffectPlanner` exists.
- `createExpenseDbOnlyV2()` and `createExpenseStandaloneV2()` exist.
- receipt/email double-dispatch is fixed.
- production `SideEffectMode` callsites are already mostly removed.

If any of those APIs are missing, add only the minimal missing pieces needed for this PR.

---

# 1. Baseline checked

Current `fc002a583674d9e1734412c9df232e41d621549b` group code has two key side-effect risks:

## 1.1 `createSystemExpenseAndLinkToGroup(...)`

Current behavior:

```text
database.withTransaction {
  create expense with SideEffectMode.DEFER
  insert group_expense link
}.also {
  dispatchPostCreationSideEffects(...)
}
```

This is conceptually correct, but it still uses legacy `SideEffectMode` and manual dispatch.

## 1.2 `addExpenseWithLink(...)`

Current behavior:

```text
database.withTransaction {
  insert group_expense link
  normalizeLinkedSystemExpense(...)
}
```

`normalizeLinkedSystemExpense(...)` calls:

```text
TransactionLifecycleCoordinator.updateOwnership(...)
```

That method writes DB state and then dispatches post-update side effects after its own inner transaction returns. Since the outer group transaction may still roll back afterward, this can run side effects for data that is not committed.

This is the core PR6 bug.

## 1.3 `deleteGroupAtomic(...)`

Current behavior:

```text
database.withTransaction {
  delete group rows
  clear shared-expense flags directly through ExpenseDao
}
write one BULK_UPDATED event after transaction
```

This avoids immediate side-effect dispatch, but:
- it bypasses normal transaction update APIs
- lifecycle event is after commit, not atomic with the mutation
- no post-commit action batch is returned/run for affected expenses

---

# 2. Goal

Make group operations that mutate transaction/expense state follow the global rule:

```text
Outer group DB transaction commits first.
Transaction side-effect actions run after outer commit.
Rollback means no side effects.
Nested transaction coordinators must use DB-only APIs.
```

---

# 3. Non-goals

Do not include:

- full `GroupLifecycleCoordinator`
- group lifecycle event table
- settlements table
- member removal balance settlement
- recurring/reminder side-effect refactor
- worker/batch side-effect integration
- static guard script
- full hard-delete product redesign

This PR only fixes group/nested transaction side-effect boundaries.

---

# 4. Files to modify

```text
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt
```

Optional new helper file:

```text
app/src/main/java/com/yourname/expensetracker/data/database/GroupPostCommitOutcome.kt
```

But a private data class inside `GroupTransactionCoordinator.kt` is enough.

---

# 5. New / required transaction APIs

## 5.1 Ownership DB-only update

Add if not already present:

```kotlin
suspend fun updateOwnershipDbOnlyV2(
    expenseId: Long,
    isNotMine: Boolean,
    ownerName: String?,
    isSharedExpense: Boolean,
    sharedWithName: String?,
    mySharePercentage: Int?,
    myShareAmount: Double?,
    reason: String? = null,
    source: String = "USER_EDIT",
    correlationId: String? = null
): MutationResult<OwnershipUpdateResult>
```

Suggested result:

```kotlin
sealed interface OwnershipUpdateResult {
    data class Updated(val expenseId: Long) : OwnershipUpdateResult
    data object NoOp : OwnershipUpdateResult
    data object NotFound : OwnershipUpdateResult
}
```

Rules:

```text
- Writes ownership fields.
- Writes UPDATED event atomically with ownership field changes.
- Returns post-update action batch.
- Does not run the action batch.
- Does not call TransactionSideEffectDispatcher directly.
```

## 5.2 Compatibility wrapper

Keep existing `updateOwnership(...)`, but rewrite it as:

```kotlin
suspend fun updateOwnership(...) {
    val mutation = updateOwnershipDbOnlyV2(...)
    postCommitActionRunner.run(mutation.postCommitActions)
}
```

This keeps existing callers working while giving group code a safe DB-only method.

## 5.3 Bulk shared-flag cleanup API

For hard-delete cleanup, add either:

```kotlin
suspend fun clearSharedExpenseFlagsDbOnlyV2(
    expenseIds: List<Long>,
    reason: String,
    source: String,
    correlationId: String?
): MutationResult<BulkExpenseMutationResult>
```

or a smaller planner helper:

```kotlin
fun planBulkUpdated(
    source: String,
    affectedCount: Int,
    correlationId: String?
): PostCommitActionBatch
```

Preferred: use a DB-only lifecycle method if feasible, because direct DAO mutation is already a known weak point.

---

# 6. Group coordinator local outcome model

Inside `GroupTransactionCoordinator.kt`, add private helpers:

```kotlin
private data class GroupMutationTxOutcome(
    val result: GroupExpenseCreationResult,
    val postCommitActions: PostCommitActionBatch
)
```

For delete:

```kotlin
private data class GroupDeleteTxOutcome(
    val success: Boolean,
    val postCommitActions: PostCommitActionBatch
)
```

Add helper:

```kotlin
private suspend fun runGroupPostCommitActions(batch: PostCommitActionBatch) {
    if (batch.actions.isEmpty()) return
    postCommitActionRunner.run(batch)
}
```

Important:

```text
Do not swallow CancellationException.
The runner already records cancellation.
```

If catching non-cancellation failures, only log; do not mark the primary group mutation failed after it committed.

---

# 7. Constructor injection changes

In `GroupTransactionCoordinator`, inject:

```kotlin
private val postCommitActionRunner: PostCommitActionRunner
```

Possibly also:

```kotlin
private val transactionSideEffectPlanner: TransactionSideEffectPlanner
```

Only inject the planner directly if the transaction coordinator does not expose bulk DB-only action batches. Prefer going through `TransactionLifecycleCoordinator`.

---

# 8. Refactor `createSystemExpenseAndLinkToGroup(...)`

## Current problem

Uses legacy:

```kotlin
createExpense(..., SideEffectMode.DEFER)
dispatchPostCreationSideEffects(...)
```

## Target flow

```kotlin
override suspend fun createSystemExpenseAndLinkToGroup(...): GroupExpenseCreationResult =
    withContext(ioDispatcher) {
        val correlationId = CorrelationIds.newId()

        val outcome = database.withTransaction {
            // validate group
            // validate payer
            // validate split payload
            // calculate currentUserShare

            val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(
                CreateExpenseRequest(
                    ...,
                    source = ExpenseSource.GROUP_EXPENSE,
                    correlationId = correlationId
                )
            )

            val systemExpenseId = when (val result = mutation.value) {
                is CreateExpenseResult.Created -> result.expenseId
                is CreateExpenseResult.DuplicateSkipped ->
                    return@withTransaction GroupMutationTxOutcome(
                        GroupExpenseCreationResult.Error("Duplicate expense: ${result.reason}"),
                        PostCommitActionBatch.empty(correlationId)
                    )
                is CreateExpenseResult.ValidationFailed -> ...
                is CreateExpenseResult.InsertConflict -> ...
                is CreateExpenseResult.Error -> ...
            }

            // ensure not already linked
            // insert GroupExpense

            GroupMutationTxOutcome(
                result = GroupExpenseCreationResult.Success(groupExpenseId, systemExpenseId),
                postCommitActions = mutation.postCommitActions
            )
        }

        if (outcome.result is GroupExpenseCreationResult.Success) {
            runGroupPostCommitActions(outcome.postCommitActions)
        }

        outcome.result
    }
```

## Rules

```text
- The transaction-created expense actions are collected inside the transaction.
- They are run only after the group link insert commits.
- If group link insert fails, no transaction-created side effects run.
- Duplicate/validation/conflict/error results return empty actions.
```

Remove:

```text
SideEffectMode import
@Suppress("DEPRECATION_ERROR")
dispatchPostCreationSideEffects call
```

---

# 9. Refactor `addExpenseWithLink(...)`

## Current problem

`normalizeLinkedSystemExpense(...)` calls `updateOwnership(...)`, which dispatches post-update side effects before the outer group transaction commits.

## Target flow

Inside `database.withTransaction`:

```kotlin
val ownershipMutation =
    transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(
        expenseId = systemExpenseId,
        isNotMine = false,
        ownerName = null,
        isSharedExpense = true,
        sharedWithName = null,
        mySharePercentage = null,
        myShareAmount = currentUserShare,
        reason = "Group expense linking: set shared-expense metadata",
        source = "GROUP_EXPENSE",
        correlationId = correlationId
    )
```

Then return:

```kotlin
GroupMutationTxOutcome(
    result = GroupExpenseCreationResult.Success(groupExpenseId, systemExpenseId),
    postCommitActions = ownershipMutation.postCommitActions
)
```

After transaction commits:

```kotlin
if (outcome.result is Success) {
    runGroupPostCommitActions(outcome.postCommitActions)
}
```

## Rename helper

Replace:

```kotlin
normalizeLinkedSystemExpense(...)
```

with:

```kotlin
normalizeLinkedSystemExpenseDbOnly(...)
```

or inline the DB-only coordinator call.

## Rules

```text
- No update side effects run inside addExpenseWithLink transaction.
- Ownership update lifecycle event remains atomic with the group link.
- If group transaction rolls back after ownership normalization, no side effects run.
- No duplicate recurring/budget/anomaly updates.
```

---

# 10. Refactor `deleteGroupAtomic(...)` / hard delete cleanup

## Current problem

Clears shared flags directly via DAO and writes a best-effort bulk event afterward.

## Target minimum fix

Inside transaction:

```kotlin
val linkedExpenseIds = groupExpenseDao.getExpensesForGroupOnce(groupId).mapNotNull { it.expenseId }

database.withTransaction {
    groupExpenseDao.deleteAllForGroup(groupId)
    memberDao.deleteAllForGroup(groupId)
    groupDao.delete(group)

    linkedExpenseIds.forEach { expenseId ->
        expenseDao.clearSharedExpenseFlags(expenseId)
    }

    transactionLifecycleEventWriter.write(
        TransactionLifecycleEvent(
            expenseId = null,
            eventType = LifecycleEventType.BULK_UPDATED.name,
            source = "GROUP_HARD_DELETE",
            correlationId = correlationId,
            metadata = SafeEventMetadata.builder()
                .put("groupId", groupId)
                .put("count", linkedExpenseIds.size)
                .build(),
            reason = "Group hard-delete cleared shared expense flags"
        )
    )
}
```

Then after commit:

```kotlin
val actions =
    transactionSideEffectPlanner.planBulkUpdated(
        source = "GROUP_HARD_DELETE",
        affectedCount = linkedExpenseIds.size,
        correlationId = correlationId
    )

postCommitActionRunner.run(actions)
```

## Preferred stronger fix

Instead of direct DAO cleanup, use a transaction lifecycle DB-only bulk update API. But do not let this PR become a full group lifecycle redesign.

## Privacy / metadata note

Do not put full `expenseIds` arrays in event metadata unless strictly needed. Prefer:

```text
groupId
count
source
correlationId
```

---

# 11. Correlation ID handling

Every group operation that mutates expenses should create one correlation ID:

```kotlin
val correlationId = CorrelationIds.newId()
```

Use it for:

```text
transaction create/update events
group-related bulk event
post-commit side-effect actions
diagnostic events
```

Do not generate a new ID between inner transaction mutation and outer side-effect execution.

---

# 12. Cancellation and error handling

## Post-commit runner

Correct:

```kotlin
try {
    postCommitActionRunner.run(batch)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(e, "Group post-commit side effects failed")
}
```

Do not allow non-cancellation side-effect failure to change already committed group result.

## DB transaction

If any DB step fails:

```text
rollback all DB changes
return error
do not run side-effect batch
```

---

# 13. Tests

## 13.1 `createSystemExpenseAndLinkToGroup`

Required:

```text
create_system_expense_uses_db_only_transaction_api
create_system_expense_runs_actions_after_group_link_commit
create_system_expense_link_failure_does_not_run_actions
create_system_expense_duplicate_result_does_not_run_actions
create_system_expense_validation_failure_does_not_run_actions
create_system_expense_runner_failure_does_not_rollback_committed_group_link
create_system_expense_runner_cancellation_rethrows
```

## 13.2 `addExpenseWithLink`

Required:

```text
add_expense_with_link_uses_updateOwnershipDbOnlyV2
add_expense_with_link_does_not_run_update_actions_inside_transaction
add_expense_with_link_runs_update_actions_after_outer_commit
add_expense_with_link_rollback_does_not_run_update_actions
add_expense_with_link_noop_ownership_update_runs_no_actions
add_expense_with_link_already_attached_returns_error_no_actions
```

## 13.3 Hard delete / cleanup

Required:

```text
hard_delete_clears_shared_flags_inside_transaction
hard_delete_writes_bulk_updated_event_inside_transaction
hard_delete_runs_bulk_actions_after_commit
hard_delete_with_no_linked_expenses_runs_no_actions
hard_delete_rollback_runs_no_actions
```

## 13.4 Grep/static-style regression

Manual acceptance for PR6:

```bash
rg "SideEffectMode" app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
rg "dispatchPostCreationSideEffects" app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
rg "updateOwnership\\(" app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
```

Expected:

```text
no SideEffectMode
no dispatchPostCreationSideEffects
no direct updateOwnership call from inside group transaction
```

Allowed:

```text
updateOwnershipDbOnlyV2
```

---

# 14. Implementation order

## Step 1 — Add DB-only ownership API

In `TransactionLifecycleCoordinator`:

```text
add updateOwnershipDbOnlyV2
rewrite updateOwnership as standalone wrapper
tests for no side-effect execution in DB-only path
```

## Step 2 — Inject runner into group coordinator

Add:

```kotlin
PostCommitActionRunner
```

Update tests/DI constructors.

## Step 3 — Refactor `createSystemExpenseAndLinkToGroup`

Replace legacy deferred create + manual dispatch with:

```text
createExpenseDbOnlyV2
collect action batch
run after outer commit
```

## Step 4 — Refactor `addExpenseWithLink`

Replace `normalizeLinkedSystemExpense()` with DB-only ownership update and post-commit action collection.

## Step 5 — Fix hard-delete cleanup event/action boundary

Move bulk event into transaction. Plan and run bulk update actions after commit.

## Step 6 — Remove stale comments/imports

Remove or update comments saying:

```text
G02-VERIFIED SideEffectMode.DEFER
dispatchPostCreationSideEffects
```

Replace with:

```text
PostCommitActionBatch collected and run after outer group transaction commit.
```

## Step 7 — Add integration/rollback tests

Focus on proving:

```text
rollback => no actions
commit => one action batch
duplicate/error => no actions
```

---

# 15. Acceptance criteria

PR6 is done when:

```text
1. GroupTransactionCoordinator has no SideEffectMode import/use.

2. GroupTransactionCoordinator has no direct dispatchPostCreationSideEffects call.

3. createSystemExpenseAndLinkToGroup uses createExpenseDbOnlyV2 and runs actions after the group link commits.

4. addExpenseWithLink uses updateOwnershipDbOnlyV2 and runs ownership-update actions after the outer group transaction commits.

5. normalizeLinkedSystemExpense no longer dispatches side effects inside the group transaction.

6. Hard-delete shared-flag cleanup has an atomic bulk lifecycle event and post-commit action batch.

7. Rollback paths do not run action batches.

8. Duplicate/validation/error paths do not run transaction create/update actions.

9. Cancellation from the post-commit runner is not swallowed.

10. Existing public group interface remains source-compatible.
```

---

# 16. Sources checked

- Current commit:  
  https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b

- `GroupTransactionCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- Domain group coordinator interface:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionLifecycleEventWriter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt

- Global side-effect contract doc:  
  `global_side_effect_dispatch_contract_plan.md`