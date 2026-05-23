# Pipeline 2 implementation plan — P2-NEW-10, P2-NEW-11, P2-NEW-12

Target baseline: `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-10 | P1/P2 | Group create-system-expense-and-link can commit orphan system expense on non-throwing link failure |
| P2-NEW-11 | P1/P2 | Group-created system expense does not pass `groupId` into `CreateExpenseRequest` |
| P2-NEW-12 | P2 | `addExpenseWithLink()` does not visibly check DB-only ownership update result |

Core rule for this plan:

```text
Inside Room database.withTransaction:
- returning an error value commits the transaction;
- throwing an exception rolls back the transaction.
```

So every failure that must rollback expense create/link/update must throw inside the transaction, then be converted to a public result outside the transaction.

---

# Recommended PR slicing

Implement as one focused PR:

```text
PR — Group expense create/link atomicity + provenance + ownership-result enforcement
```

Reason: these three issues touch the same group coordinator flows and should be fixed together to avoid another partial state.

Primary files:

```text
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt
app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
```

Use existing test locations if different.

---

# Part A — Fix P2-NEW-10: group system-expense create/link atomicity

## Problem

`createSystemExpenseAndLinkToGroup()` creates an expense via the transaction lifecycle coordinator, then inserts a `GroupExpense` link.

If group link insertion fails but the method returns an error result from inside `database.withTransaction`, Room commits the already-created expense. This leaves an orphan system expense that should not exist without its group link.

## Desired behavior

System expense creation and group link creation must be atomic:

```text
expense create succeeds + group link succeeds => commit
expense create succeeds + group link fails => rollback expense
expense create fails => no group link attempted
source link failure => rollback expense
post-commit side effect failure => does not rollback DB
```

## Step A1 — Add internal rollback exception

In `GroupTransactionCoordinator.kt`, add private internal exception types:

```kotlin
private sealed class GroupExpenseAtomicFailure(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    data class ExpenseCreateFailed(
        val result: CreateExpenseResult
    ) : GroupExpenseAtomicFailure("System expense creation failed: $result")

    data class GroupLinkInsertFailed(
        val groupId: Long,
        val expenseId: Long,
        val reason: String
    ) : GroupExpenseAtomicFailure(
        "Group link insert failed for group=$groupId expense=$expenseId: $reason"
    )

    data class OwnershipUpdateFailed(
        val groupId: Long,
        val expenseId: Long,
        val result: Any?
    ) : GroupExpenseAtomicFailure(
        "Ownership update failed for group=$groupId expense=$expenseId: $result"
    )
}
```

If Kotlin sealed class/data class syntax conflicts with project style, use simple private `RuntimeException` subclasses.

Important:

- These exceptions are internal rollback signals.
- They should be caught outside `withTransaction` and converted to the existing public result type.
- Do not let them crash UI.

---

## Step A2 — Ensure create uses DB-only coordinator API

Inside `createSystemExpenseAndLinkToGroup()`, use only DB-only lifecycle creation:

```kotlin
val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
```

Do **not** use a create method that dispatches side effects inside the group transaction.

Expected result shape from earlier code:

```kotlin
MutationResult<CreateExpenseResult>(
    value = CreateExpenseResult,
    postCommitActions = PostCommitActionBatch
)
```

If the project names differ, adapt but keep the same rule:

```text
inside transaction: DB mutation only
after transaction commits: run post-commit actions
```

---

## Step A3 — Throw on non-created result inside transaction

Inside the transaction:

```kotlin
val mutation = transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)

val created = mutation.value as? CreateExpenseResult.Created
    ?: throw GroupExpenseAtomicFailure.ExpenseCreateFailed(mutation.value)

val expenseId = created.expenseId
postCommitActions += mutation.postCommitActions
```

If `CreateExpenseResult.Created` uses field name `id` or `data`, use the actual field.

Important:

- Do not return an error result from inside the transaction.
- Throw so Room rolls back any writes done by nested create.
- If `createExpenseDbOnlyV2()` itself returns duplicate/validation/error, rollback and convert after transaction.

---

## Step A4 — Insert group link strictly

After expense creation, create the `GroupExpense` link.

If DAO insert returns `Long`:

```kotlin
val linkId = groupExpenseDao.insert(groupExpense)

if (linkId <= 0L) {
    throw GroupExpenseAtomicFailure.GroupLinkInsertFailed(
        groupId = groupId,
        expenseId = expenseId,
        reason = "insert returned $linkId"
    )
}
```

If DAO insert returns `Unit`, wrap and let exceptions throw naturally:

```kotlin
try {
    groupExpenseDao.insert(groupExpense)
} catch (e: Exception) {
    throw GroupExpenseAtomicFailure.GroupLinkInsertFailed(
        groupId = groupId,
        expenseId = expenseId,
        reason = e.message ?: e.javaClass.simpleName
    )
}
```

If DAO uses `OnConflictStrategy.REPLACE`, reconsider. For this flow, `ABORT` or `IGNORE + check returned id` is safer.

Recommended DAO contract:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(groupExpense: GroupExpense): Long
```

Then treat `<= 0` as failure.

---

## Step A5 — Catch rollback exceptions outside transaction

Shape:

```kotlin
suspend fun createSystemExpenseAndLinkToGroup(...): GroupExpenseCreateResult {
    val postCommitActions = mutableListOf<PostCommitActionBatch>()
    var createdExpenseId: Long? = null

    return try {
        database.withTransaction {
            // create expense DB-only
            // insert group link
            // set createdExpenseId
            // collect postCommitActions
        }

        runGroupPostCommitActions(postCommitActions)

        GroupExpenseCreateResult.Success(
            expenseId = requireNotNull(createdExpenseId),
            groupId = groupId
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: GroupExpenseAtomicFailure.ExpenseCreateFailed) {
        GroupExpenseCreateResult.Error(
            message = "Could not create system expense for group",
            causeClass = e.result::class.java.simpleName
        )
    } catch (e: GroupExpenseAtomicFailure.GroupLinkInsertFailed) {
        GroupExpenseCreateResult.Error(
            message = "Could not link system expense to group; expense creation was rolled back",
            causeClass = e.javaClass.simpleName
        )
    } catch (e: Exception) {
        GroupExpenseCreateResult.Error(
            message = e.message ?: "Group system expense create/link failed",
            causeClass = e.javaClass.name
        )
    }
}
```

Use actual result class names from the project.

Rules:

- `CancellationException` must be rethrown.
- Post-commit actions must run only after `withTransaction` returns successfully.
- If any rollback exception is thrown, do not run post-commit actions.

---

## Step A6 — Add rollback diagnostic/event if available

Optional but recommended:

When catching `GroupLinkInsertFailed`, emit a safe diagnostic:

```text
pipeline = TRANSACTION or GROUP
stage = GROUP_SYSTEM_EXPENSE_LINK
outcome = FAILED_FINAL
reason = GROUP_LINK_FAILED
metadata:
  groupId
  expenseId if known
  operation = createSystemExpenseAndLinkToGroup
```

Do not include raw group names, member names, descriptions, notes, or expense merchant.

If no group diagnostic infrastructure exists, skip this and rely on tests.

---

# Part B — Fix P2-NEW-11: pass `groupId` into `CreateExpenseRequest`

## Problem

The create request sets:

```kotlin
source = ExpenseSource.GROUP_EXPENSE
```

but does not set:

```kotlin
groupId = groupId
```

So the source-link mapper cannot create a concrete group provenance link and may fall back to `LEGACY_SOURCE_ONLY`.

## Desired behavior

Every group-created system expense must persist a source link with:

```text
sourceType = GROUP_EXPENSE
sourceEntityId = groupId
expenseId = createdExpenseId
```

or equivalent project schema.

## Step B1 — Update `CreateExpenseRequest` construction

Inside `createSystemExpenseAndLinkToGroup()`, find request creation:

```kotlin
CreateExpenseRequest(
    ...
    source = ExpenseSource.GROUP_EXPENSE,
    ...
)
```

Add:

```kotlin
groupId = groupId
```

Full expected shape:

```kotlin
val request = CreateExpenseRequest(
    merchant = merchant,
    amount = amount,
    currency = currency,
    date = date,
    categoryId = categoryId,
    transactionType = transactionType,
    source = ExpenseSource.GROUP_EXPENSE,
    groupId = groupId,
    notes = notes,
    isSharedExpense = true,
    sharedWithName = groupNameOrMemberName,
    myShareAmount = myShareAmount,
    mySharePercentage = mySharePercentage,
    ...
)
```

Use the actual available fields.

## Step B2 — Verify mapper supports group ID

Check:

```text
CreateExpenseSourceLinkMapper.kt
```

Expected behavior should include:

```kotlin
if (request.groupId != null) {
    payloads += SourceLinkPayload(
        sourceType = SourceLinkType.GROUP_EXPENSE,
        sourceEntityId = request.groupId.toString(),
        ...
    )
}
```

If it does not exist, add it.

Do not use raw group names in metadata. Use only ID.

## Step B3 — Ensure source link write remains atomic

The source link writer should be called inside the same create transaction as expense insert.

Do not move source-link writing outside the transaction.

Acceptance:

```text
expense insert + transaction event + source link all commit together
source-link failure rolls back expense creation
```

---

# Part C — Fix P2-NEW-12: enforce `updateOwnershipDbOnlyV2()` result in `addExpenseWithLink()`

## Problem

`addExpenseWithLink()` links an existing expense to a group and calls:

```kotlin
transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(...)
```

But current code does not visibly prove that a non-success ownership update aborts the group link.

Risk:

```text
group_expenses link commits
expense ownership/share metadata fails or is skipped
=> group link and expense row disagree
```

## Desired behavior

A group link to an existing expense must be atomic with ownership metadata update.

Either both happen or neither happens.

## Step C1 — Identify result type

Inspect `updateOwnershipDbOnlyV2()` return type.

Likely shape:

```kotlin
MutationResult<LifecycleUpdateResult>
```

or:

```kotlin
MutationResult<Result<Unit>>
```

Agent must inspect locally:

```bash
grep -R "fun updateOwnershipDbOnlyV2" app/src/main/java
grep -R "sealed.*UpdateResult\|LifecycleUpdateResult\|UpdateExpenseResult" app/src/main/java
```

## Step C2 — Add success assertion helper

In `GroupTransactionCoordinator.kt`, add helper adapted to actual result type.

Example:

```kotlin
private fun requireOwnershipUpdateSuccess(
    result: Any?,
    groupId: Long,
    expenseId: Long
) {
    val success = when (result) {
        is LifecycleUpdateResult.Updated -> true
        is LifecycleUpdateResult.NoChange -> true // only if no change is acceptable
        is Result<*> -> result.isSuccess
        Unit -> true
        else -> false
    }

    if (!success) {
        throw GroupExpenseAtomicFailure.OwnershipUpdateFailed(
            groupId = groupId,
            expenseId = expenseId,
            result = result
        )
    }
}
```

Important decision:

- Treat `Updated` as success.
- Treat `NoChange` as success **only if** existing expense already has exactly the intended ownership fields.
- Treat `NotFound`, `ValidationFailed`, `Blocked`, `Error`, `Duplicate`, or unknown result as failure.

Better stricter version:

```kotlin
private fun isAcceptableOwnershipResult(result: LifecycleUpdateResult): Boolean =
    result is LifecycleUpdateResult.Updated ||
    result is LifecycleUpdateResult.NoChange
```

But before accepting `NoChange`, verify by reading the expense row and comparing expected fields.

## Step C3 — Compare final ownership fields

After `updateOwnershipDbOnlyV2()`, read the expense row inside the same transaction:

```kotlin
val updatedExpense = expenseDao.getById(expenseId)
    ?: throw GroupExpenseAtomicFailure.OwnershipUpdateFailed(
        groupId = groupId,
        expenseId = expenseId,
        result = "Expense missing after ownership update"
    )
```

Assert intended group ownership/share fields:

```kotlin
if (!updatedExpense.isSharedExpense) fail
if (updatedExpense.myShareAmount != expectedMyShareAmount) fail
if (updatedExpense.mySharePercentage != expectedMySharePercentage) fail
if (updatedExpense.sharedWithName != expectedSharedWithName) fail
```

Use actual expected fields. Avoid exact floating comparison if values are `Double`; use tolerance:

```kotlin
private fun Double?.approximatelyEquals(other: Double?, epsilon: Double = 0.0001): Boolean
```

This row verification is the strongest fix. It prevents a misleading `NoChange` from hiding a failed mutation.

## Step C4 — Throw inside transaction if ownership update is not correct

Inside `addExpenseWithLink()` transaction:

```kotlin
val ownershipMutation = transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(...)
requireOwnershipUpdateSuccess(
    result = ownershipMutation.value,
    groupId = groupId,
    expenseId = expenseId
)

postCommitActions += ownershipMutation.postCommitActions

verifyExpenseOwnershipForGroupLink(
    expenseId = expenseId,
    expected = expectedOwnership
)
```

If verification fails:

```kotlin
throw GroupExpenseAtomicFailure.OwnershipUpdateFailed(...)
```

Do not return error inside the transaction.

## Step C5 — Insert ordering

Preferred order inside transaction:

```text
1. validate group/member/expense exists
2. update expense ownership DB-only
3. verify expense ownership row
4. insert group_expense link
5. collect post-commit actions
```

This way if link insert fails, ownership update rolls back too.

Alternative order is acceptable if every failure throws, because transaction rollback handles it.

## Step C6 — Post-commit actions

Collect all post-commit action batches:

```kotlin
val postCommitBatches = mutableListOf<PostCommitActionBatch>()
```

After successful transaction:

```kotlin
runGroupPostCommitActions(postCommitBatches)
```

Rules:

- Do not run actions inside transaction.
- Do not run actions if transaction throws/rolls back.
- Rethrow `CancellationException`.

---

# Tests

Create/update:

```text
app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorAtomicityTest.kt
```

Use existing fake DB/test style.

---

## Tests for P2-NEW-10

### 1. Link insert failure rolls back created expense

```text
create_system_expense_link_insert_failure_rolls_back_expense
```

Arrange:

- group exists,
- force `groupExpenseDao.insert()` to fail or return `-1`.

Act:

- call `createSystemExpenseAndLinkToGroup()`.

Assert:

- result is error,
- no expense row exists for attempted system expense,
- no `group_expenses` row exists,
- no `CREATED` transaction event remains if create was in same transaction,
- no source link remains,
- post-commit runner was not called.

### 2. Link insert exception rolls back created expense

```text
create_system_expense_link_insert_exception_rolls_back_expense
```

Same as above but DAO throws.

### 3. Create failure does not attempt group link

```text
create_system_expense_validation_failure_does_not_insert_group_link
```

Arrange invalid request, e.g. amount <= 0.

Assert:

- no group link inserted,
- no post-commit actions.

### 4. Successful create/link commits both

```text
create_system_expense_and_link_success_commits_expense_and_group_link
```

Assert:

- expense exists,
- group link exists,
- source link exists,
- post-commit runner called once after commit.

---

## Tests for P2-NEW-11

### 1. Group ID is passed into create request

If using fake coordinator:

```text
create_system_expense_request_contains_groupId
```

Assert captured request:

```kotlin
request.source == ExpenseSource.GROUP_EXPENSE
request.groupId == groupId
```

### 2. Source link is concrete group link

Integration test:

```text
group_created_system_expense_has_group_source_link
```

Assert source-link table contains:

```text
expenseId = createdExpenseId
sourceType = GROUP_EXPENSE
sourceEntityId = groupId
```

or project equivalent.

### 3. No legacy-only fallback for group expense

```text
group_created_system_expense_does_not_use_legacy_source_only_link
```

Assert no source link for that expense has:

```text
sourceType = LEGACY_SOURCE_ONLY
```

unless the project intentionally writes both. Preferred: only concrete group link.

---

## Tests for P2-NEW-12

### 1. Ownership update failure rolls back group link

```text
add_existing_expense_ownership_update_failure_rolls_back_group_link
```

Arrange:

- existing expense,
- group exists,
- fake `updateOwnershipDbOnlyV2()` returns failure.

Act:

- call `addExpenseWithLink()`.

Assert:

- no `group_expenses` row inserted,
- expense ownership fields unchanged,
- no post-commit actions.

### 2. Ownership update no-op is verified

```text
add_existing_expense_ownership_nochange_requires_row_to_match_expected_fields
```

Case A:

- update returns `NoChange`,
- row already matches expected ownership,
- link succeeds.

Case B:

- update returns `NoChange`,
- row does not match expected ownership,
- method throws rollback signal,
- no link committed.

### 3. Link insert failure rolls back ownership update

```text
add_existing_expense_link_insert_failure_rolls_back_ownership_update
```

Arrange:

- ownership update succeeds,
- link insert fails.

Assert:

- expense ownership fields remain old values,
- no group link exists,
- no post-commit actions.

### 4. Success dispatches post-commit after transaction

```text
add_existing_expense_success_dispatches_ownership_side_effects_after_commit
```

Assert runner called only after DB state is visible.

---

# Implementation details and guardrails

## Do not swallow rollback failures inside transactions

Bad:

```kotlin
database.withTransaction {
    val id = createExpense()
    if (linkFailed) {
        return Error("failed") // commits transaction
    }
}
```

Good:

```kotlin
try {
    database.withTransaction {
        val id = createExpense()
        if (linkFailed) {
            throw GroupExpenseAtomicFailure.GroupLinkInsertFailed(...)
        }
    }
    Success
} catch (e: GroupExpenseAtomicFailure) {
    Error(...)
}
```

## Do not dispatch side effects inside transaction

Bad:

```kotlin
database.withTransaction {
    val mutation = createExpenseDbOnlyV2(request)
    runner.run(mutation.postCommitActions)
}
```

Good:

```kotlin
val actions = mutableListOf<PostCommitActionBatch>()

database.withTransaction {
    val mutation = createExpenseDbOnlyV2(request)
    actions += mutation.postCommitActions
}

runner.run(actions)
```

## Rethrow cancellation

Every catch block around this flow must include:

```kotlin
catch (e: CancellationException) {
    throw e
}
```

## Use safe metadata only

If adding diagnostics/events, metadata may include:

```text
groupId
expenseId
operation
failureType
```

Do not include:

```text
group name
member names
emails
notes
description
raw split JSON
merchant names
receipt/email payloads
```

---

# Manual grep checklist

Run before and after:

```bash
grep -R "createSystemExpenseAndLinkToGroup" app/src/main/java
grep -R "addExpenseWithLink" app/src/main/java
grep -R "source = ExpenseSource.GROUP_EXPENSE" app/src/main/java
grep -R "groupId = groupId" app/src/main/java
grep -R "updateOwnershipDbOnlyV2" app/src/main/java
```

Expected final state:

- every `GROUP_EXPENSE` create request in group coordinator passes `groupId = groupId`;
- `createSystemExpenseAndLinkToGroup()` throws rollback exceptions for link failure inside transaction;
- `addExpenseWithLink()` checks ownership mutation result and verifies final row;
- no post-commit runner is called inside `database.withTransaction`.

---

# Validation commands

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*GroupTransactionCoordinatorAtomicity*'
./gradlew testDebugUnitTest --tests '*GroupTransactionCoordinator*'
./gradlew testDebugUnitTest --tests '*SourceLink*'
```

---

# Definition of done

## P2-NEW-10 done when

- `createSystemExpenseAndLinkToGroup()` uses DB-only expense creation inside outer transaction.
- Any group link failure throws inside `withTransaction`.
- Link failure rolls back expense row, transaction events, and source links.
- Post-commit actions run only after successful transaction commit.
- Tests prove no orphan system expense can be committed.

## P2-NEW-11 done when

- Group-created system expense request sets `groupId = groupId`.
- Source-link mapper writes concrete group provenance.
- Tests prove source link has `sourceType = GROUP_EXPENSE` and `sourceEntityId = groupId`.
- Group-created expenses no longer fall back to legacy/source-only provenance.

## P2-NEW-12 done when

- `addExpenseWithLink()` checks `updateOwnershipDbOnlyV2()` result.
- `addExpenseWithLink()` verifies the final expense ownership/share fields inside the same transaction.
- Any ownership failure throws and rolls back group link.
- Any group link failure rolls back ownership update.
- Tests prove success and both rollback directions.