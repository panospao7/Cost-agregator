# Pipeline 2 implementation plan — P2-NEW-13, P2-NEW-14, P2-NEW-15

Target baseline: `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-13 | P2 | Permanent group delete still lacks `GroupLifecycleCoordinator` |
| P2-NEW-14 | P2 | Side-effect failures are durable only in generic diagnostics, not `transaction_events` |
| P2-NEW-15 | P2 | Manual recommendation hook uses synthetic expense |

Important: keep these as **three separate PRs** unless the agent is explicitly asked to bundle them.

---

# PR A — GroupLifecycleCoordinator for permanent group delete

## Fixes

- P2-NEW-13

## Current evidence

`GroupTransactionCoordinator.kt` has a file-level TODO to create `GroupLifecycleCoordinator` and route lifecycle methods through it. The hard-delete path still lives directly in `GroupTransactionCoordinator.permanentlyDeleteGroup(groupId): Boolean`, which calls `deleteGroupAtomic(groupId)` and returns `false` on exceptions. The same file comments say hard delete should require explicit confirmation and write a group lifecycle event.

## Goal

Create a high-level `GroupLifecycleCoordinator` that owns destructive group lifecycle semantics.

Low-level `GroupTransactionCoordinator` should remain the atomic DAO transaction helper, not the public lifecycle policy owner.

## Files to add

```text
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/GroupLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/GroupLifecycleResult.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/DefaultGroupLifecycleCoordinator.kt
```

If project layering prefers data-layer implementations, put implementation in:

```text
app/src/main/java/com/yourname/expensetracker/data/groups/DefaultGroupLifecycleCoordinator.kt
```

## Files to modify

```text
domain/transaction/LifecycleEventType.kt
data/database/GroupTransactionCoordinator.kt
domain/groups/GroupTransactionCoordinator.kt
data/database/dao/GroupExpenseDao.kt
data/database/dao/GroupMemberDao.kt
data/database/dao/ExpenseGroupDao.kt
```

## Step A1 — Add result contract

Create:

```kotlin
package com.yourname.expensetracker.domain.groups.lifecycle

sealed interface PermanentGroupDeleteResult {
    data class Deleted(
        val groupId: Long,
        val linkedExpenseCount: Int
    ) : PermanentGroupDeleteResult

    data object ConfirmationRequired : PermanentGroupDeleteResult
    data object GroupNotFound : PermanentGroupDeleteResult
    data object GroupStillActive : PermanentGroupDeleteResult

    data class OutstandingBalancesExist(
        val groupId: Long,
        val outstandingCount: Int
    ) : PermanentGroupDeleteResult

    data class CurrentUserMembershipExists(
        val groupId: Long,
        val currentUserCount: Int
    ) : PermanentGroupDeleteResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : PermanentGroupDeleteResult
}
```

If Kotlin version does not support `data object`, use plain `object`.

## Step A2 — Add lifecycle coordinator interface

```kotlin
package com.yourname.expensetracker.domain.groups.lifecycle

interface GroupLifecycleCoordinator {
    suspend fun archiveGroup(groupId: Long): Boolean

    suspend fun permanentlyDeleteGroup(
        groupId: Long,
        confirmPermanentDelete: Boolean
    ): PermanentGroupDeleteResult
}
```

Keep this PR focused on archive/permanent-delete. Do not implement settlements/member-removal unless already needed for compilation.

## Step A3 — Add lifecycle event types

In `LifecycleEventType.kt`, add:

```kotlin
GROUP_ARCHIVED
GROUP_PERMANENT_DELETE_ATTEMPTED
GROUP_PERMANENT_DELETE_BLOCKED
GROUP_PERMANENTLY_DELETED
```

No DB migration should be required if `eventType` is stored as `String`.

## Step A4 — Add DAO validation helpers

In `GroupExpenseDao.kt`:

```kotlin
@Query("""
    SELECT COUNT(*)
    FROM group_expenses
    WHERE groupId = :groupId
      AND isReimbursable = 1
      AND settledAt IS NULL
      AND reimbursedAmount < totalAmount
""")
suspend fun countOutstandingReimbursableExpenses(groupId: Long): Int
```

If the schema lacks these fields, use the project’s current unsettled-balance fields. Do not invent columns without checking entities.

In `GroupMemberDao.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1")
suspend fun countCurrentUsers(groupId: Long): Int
```

In `ExpenseGroupDao.kt`, ensure a read helper exists:

```kotlin
@Query("SELECT * FROM expense_groups WHERE id = :groupId")
suspend fun getById(groupId: Long): ExpenseGroup?
```

Use existing `getGroupById()` if already present.

## Step A5 — Implement coordinator

`DefaultGroupLifecycleCoordinator` dependencies:

```kotlin
class DefaultGroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val groupExpenseDao: GroupExpenseDao,
    private val memberDao: GroupMemberDao,
    private val lowLevelGroupCoordinator: com.yourname.expensetracker.data.database.GroupTransactionCoordinator,
    private val eventWriter: TransactionLifecycleEventWriter,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GroupLifecycleCoordinator
```

If injecting the concrete data coordinator violates DI style, add a low-level interface method for atomic hard delete and inject that instead.

## Step A6 — Permanent delete policy

Implement:

```kotlin
override suspend fun permanentlyDeleteGroup(
    groupId: Long,
    confirmPermanentDelete: Boolean
): PermanentGroupDeleteResult = withContext(ioDispatcher) {
    val correlationId = CorrelationIds.newId()

    try {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.permanentlyDeleteGroup")

        writeGroupEvent(
            LifecycleEventType.GROUP_PERMANENT_DELETE_ATTEMPTED,
            groupId,
            correlationId,
            reason = "Permanent group delete attempted"
        )

        if (!confirmPermanentDelete) {
            writeBlocked(groupId, correlationId, "confirmation_required")
            return@withContext PermanentGroupDeleteResult.ConfirmationRequired
        }

        val group = groupDao.getById(groupId)
            ?: return@withContext PermanentGroupDeleteResult.GroupNotFound

        if (group.isActive) {
            writeBlocked(groupId, correlationId, "group_still_active")
            return@withContext PermanentGroupDeleteResult.GroupStillActive
        }

        val outstanding = groupExpenseDao.countOutstandingReimbursableExpenses(groupId)
        if (outstanding > 0) {
            writeBlocked(groupId, correlationId, "outstanding_balances", outstanding)
            return@withContext PermanentGroupDeleteResult.OutstandingBalancesExist(groupId, outstanding)
        }

        val currentUsers = memberDao.countCurrentUsers(groupId)
        if (currentUsers > 0) {
            writeBlocked(groupId, correlationId, "current_user_membership_exists", currentUsers)
            return@withContext PermanentGroupDeleteResult.CurrentUserMembershipExists(groupId, currentUsers)
        }

        val linkedCount = lowLevelGroupCoordinator.deleteGroupAtomic(
            groupId = groupId,
            correlationId = correlationId,
            lifecycleEventAlreadyAttempted = true
        )

        PermanentGroupDeleteResult.Deleted(groupId, linkedCount)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PermanentGroupDeleteResult.Error(
            message = e.message ?: "Permanent group delete failed",
            causeClass = e.javaClass.name
        )
    }
}
```

If `deleteGroupAtomic()` cannot accept parameters yet, update it in Step A7.

## Step A7 — Refactor `deleteGroupAtomic`

Change from:

```kotlin
suspend fun deleteGroupAtomic(groupId: Long)
```

to:

```kotlin
suspend fun deleteGroupAtomic(
    groupId: Long,
    correlationId: String = CorrelationIds.newId(),
    lifecycleEventAlreadyAttempted: Boolean = false
): Int
```

Return linked expense count.

Inside the same DB transaction that deletes group rows and clears shared flags, also write:

```kotlin
TransactionLifecycleEvent(
    expenseId = null,
    eventType = LifecycleEventType.GROUP_PERMANENTLY_DELETED.name,
    source = "GROUP_LIFECYCLE",
    actor = "system:group_lifecycle_coordinator",
    correlationId = correlationId,
    metadata = SafeEventMetadata.builder()
        .put("groupId", groupId.toString())
        .put("linkedExpenseCount", linkedExpenseIds.size.toString())
        .build(),
    reason = "Group permanently deleted"
)
```

Keep existing `BULK_UPDATED` event for linked expense shared-flag cleanup.

## Step A8 — Archive lifecycle

Implement `GroupLifecycleCoordinator.archiveGroup(groupId)`:

1. Check write barrier.
2. Load group.
3. If missing, return `false`.
4. In transaction:
   - `groupDao.archiveGroup(groupId)`
   - write `GROUP_ARCHIVED`.
5. Return `true`.

Do not hard-delete in archive.

## Step A9 — Deprecate direct hard-delete entrypoint

In `domain/groups/GroupTransactionCoordinator.kt`:

```kotlin
@Deprecated(
    message = "Use GroupLifecycleCoordinator.permanentlyDeleteGroup(groupId, confirmPermanentDelete).",
    level = DeprecationLevel.ERROR
)
suspend fun permanentlyDeleteGroup(groupId: Long): Boolean
```

If this breaks too many callsites, use `WARNING` first, migrate callsites, then promote to `ERROR`.

Add architecture test:

```text
no_production_calls_to_GroupTransactionCoordinator_permanentlyDeleteGroup
```

Allowed files:

```text
DefaultGroupLifecycleCoordinator.kt
GroupTransactionCoordinator.kt
tests
```

## PR A tests

```text
permanent_delete_without_confirmation_returns_ConfirmationRequired
permanent_delete_without_confirmation_writes_GROUP_PERMANENT_DELETE_BLOCKED
permanent_delete_active_group_returns_GroupStillActive
permanent_delete_outstanding_balances_returns_OutstandingBalancesExist
permanent_delete_current_user_membership_returns_CurrentUserMembershipExists
permanent_delete_archived_confirmed_calls_low_level_atomic_delete
permanent_delete_success_writes_GROUP_PERMANENTLY_DELETED
permanent_delete_success_preserves_existing_BULK_UPDATED_cleanup_event
archive_group_writes_GROUP_ARCHIVED
archive_group_does_not_delete_members_or_expenses
direct_permanentlyDeleteGroup_call_is_forbidden_by_architecture_test
```

## PR A acceptance criteria

- `GroupLifecycleCoordinator` exists and owns permanent-delete policy.
- Permanent delete requires `confirmPermanentDelete = true`.
- Permanent delete blocks active groups.
- Permanent delete blocks outstanding balances.
- Permanent delete blocks current-user membership.
- Successful hard delete writes `GROUP_PERMANENTLY_DELETED`.
- Direct low-level hard-delete use is deprecated/guarded.
- Existing shared-flag cleanup and post-commit bulk side effects remain.

---

# PR B — Side-effect failure transaction-event contract

## Fixes

- P2-NEW-14

## Current evidence

`PostCommitActionRunnerImpl` emits side-effect started/completed/skipped/failed/cancelled through `SideEffectEventWriter`. The current diagnostic writer maps failures to `DiagnosticEvent` outcomes. `LifecycleEventType.SIDE_EFFECT_FAILED` exists but is not written to `transaction_events`.

## Decision

Adopt this contract:

```text
Generic diagnostics are the canonical side-effect observability stream.
Additionally, transaction-related side-effect failures are mirrored into transaction_events as SIDE_EFFECT_FAILED.
```

This keeps cross-pipeline diagnostics intact while making expense/transaction failures visible in transaction lifecycle audit.

## Files to add

```text
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/TransactionSideEffectFailureEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/CompositeSideEffectEventWriter.kt
```

If DI multibinding already exists, use multibinding instead of explicit composite.

## Files to modify

```text
domain/sideeffect/SideEffectEventWriter.kt
domain/sideeffect/PostCommitActionRunnerImpl.kt
domain/transaction/LifecycleEventType.kt
di modules binding SideEffectEventWriter
```

## Step B1 — Keep enum

Ensure `LifecycleEventType.kt` contains:

```kotlin
SIDE_EFFECT_FAILED
```

Do not remove it.

## Step B2 — Add transaction failure writer

Create:

```kotlin
@Singleton
class TransactionSideEffectFailureEventWriter @Inject constructor(
    private val transactionEventWriter: TransactionLifecycleEventWriter
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) = Unit
    override suspend fun completed(action: PostCommitAction) = Unit
    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) = Unit
    override suspend fun cancelled(action: PostCommitAction, reason: String?) = Unit

    override suspend fun failed(
        action: PostCommitAction,
        retryable: Boolean,
        reason: String,
        error: Throwable?
    ) {
        if (!shouldMirrorToTransactionEvents(action)) return

        transactionEventWriter.write(
            TransactionLifecycleEvent(
                expenseId = action.targetEntityId.takeIf {
                    action.targetEntityType.equals("Expense", ignoreCase = true)
                },
                eventType = LifecycleEventType.SIDE_EFFECT_FAILED.name,
                source = action.source,
                actor = "system:post_commit_action_runner",
                correlationId = action.correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("actionName", action.name)
                    .put("category", action.category.name)
                    .put("triggerType", action.triggerType.name)
                    .put("targetEntityType", action.targetEntityType)
                    .put("targetEntityId", action.targetEntityId?.toString())
                    .put("retryable", retryable.toString())
                    .put("reasonClass", error?.javaClass?.name ?: "returned_outcome")
                    .build(),
                reason = reason.take(200)
            )
        )
    }

    private fun shouldMirrorToTransactionEvents(action: PostCommitAction): Boolean {
        return action.pipeline == AppPipeline.TRANSACTION ||
            action.targetEntityType.equals("Expense", ignoreCase = true)
    }
}
```

Privacy rule:

- Do not put raw merchant, notes, receipt text, email payloads, raw exception stack traces, or full action metadata in `transaction_events`.
- `reason.take(200)` is acceptable only if side-effect reasons are controlled. If not, replace with sanitized reason code.

## Step B3 — Add composite writer

```kotlin
@Singleton
class CompositeSideEffectEventWriter @Inject constructor(
    private val diagnosticWriter: DiagnosticSideEffectEventWriter,
    private val transactionFailureWriter: TransactionSideEffectFailureEventWriter
) : SideEffectEventWriter {

    override suspend fun started(action: PostCommitAction) {
        emitAll { diagnosticWriter.started(action) }
    }

    override suspend fun completed(action: PostCommitAction) {
        emitAll { diagnosticWriter.completed(action) }
    }

    override suspend fun skipped(action: PostCommitAction, reason: SideEffectSkipReason) {
        emitAll { diagnosticWriter.skipped(action, reason) }
    }

    override suspend fun failed(action: PostCommitAction, retryable: Boolean, reason: String, error: Throwable?) {
        emitAll(
            { diagnosticWriter.failed(action, retryable, reason, error) },
            { transactionFailureWriter.failed(action, retryable, reason, error) }
        )
    }

    override suspend fun cancelled(action: PostCommitAction, reason: String?) {
        emitAll { diagnosticWriter.cancelled(action, reason) }
    }

    private suspend fun emitAll(vararg blocks: suspend () -> Unit) {
        for (block in blocks) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Side-effect event writer child failed")
            }
        }
    }
}
```

Alternative: use `Set<@JvmSuppressWildcards SideEffectEventWriter>` multibinding and fan out generically. But avoid a large DI refactor unless already available.

## Step B4 — Update DI binding

Find the module binding:

```kotlin
@Binds
abstract fun bindSideEffectEventWriter(
    impl: DiagnosticSideEffectEventWriter
): SideEffectEventWriter
```

Change to:

```kotlin
@Binds
abstract fun bindSideEffectEventWriter(
    impl: CompositeSideEffectEventWriter
): SideEffectEventWriter
```

Keep `DiagnosticSideEffectEventWriter` injectable as concrete.

## Step B5 — Runner behavior remains unchanged

Do not change `PostCommitActionRunnerImpl` unless needed. It already calls:

```text
eventWriter.failed(...)
```

for thrown exceptions, retryable failures, and final failures.

## PR B tests

```text
side_effect_thrown_exception_writes_diagnostic_and_SIDE_EFFECT_FAILED_transaction_event
side_effect_failedRetryable_outcome_writes_SIDE_EFFECT_FAILED
side_effect_failedFinal_outcome_writes_SIDE_EFFECT_FAILED
side_effect_completed_does_not_write_SIDE_EFFECT_FAILED
side_effect_skipped_does_not_write_SIDE_EFFECT_FAILED
side_effect_failed_for_non_transaction_non_expense_action_does_not_write_transaction_event
side_effect_failure_transaction_event_has_expenseId_for_expense_target
side_effect_failure_transaction_event_has_null_expenseId_for_bulk_transaction_target
side_effect_failure_event_metadata_is_privacy_safe
transaction_failure_writer_failure_does_not_prevent_diagnostic_writer
cancellation_from_writer_is_rethrown
```

## PR B acceptance criteria

- `SIDE_EFFECT_FAILED` is intentionally kept and used.
- Side-effect failures still emit generic diagnostics.
- Transaction/expense-related side-effect failures also write `transaction_events`.
- Failure-event writer is best-effort and cannot break committed primary transactions.
- Cancellation is not swallowed.
- Privacy-safe metadata only.

---

# PR C — Manual recommendation hook uses persisted expense

## Fixes

- P2-NEW-15

## Current evidence

`ManualExpenseRepository.addManualExpense()` creates through `transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)`. On success it builds a synthetic `Expense` for AI/recommendation hooks and has a TODO saying the synthetic row can diverge from the persisted row, especially conversion/base fields.

## Goal

After coordinator create succeeds, fetch the real persisted expense and use it for AI/recommendation generation.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/repository/ManualExpenseRepositoryRecommendationHookTest.kt
```

## Step C1 — Remove synthetic expense construction

In `ManualExpenseRepository.addManualExpense()`, replace the synthetic `Expense(...)` block with:

```kotlin
val persistedExpense = expenseDao.getById(id)
    ?: throw IllegalStateException("Created expense $id was not found after coordinator insert")

insertedExpenseForHook = persistedExpense.normalizeOwnership()
```

If `getById()` already returns normalized ownership, `normalizeOwnership()` is harmless but optional.

## Step C2 — Keep this inside transaction

Do this inside the existing `database.withTransaction` block immediately after `CreateExpenseResult.Created`.

Reason:

- If coordinator reports `Created` but row cannot be read, this is an invariant violation.
- Throwing inside the transaction rolls back recurring-rule creation and any other transaction-local work.

## Step C3 — Keep recommendation work post-commit

Do not generate recommendations inside the transaction.

Keep current structure:

```kotlin
if (result is Result.Success) {
    asyncScope.launch {
        val insertedExpense = insertedExpenseForHook ?: return@...
        ...
    }
}
```

But now `insertedExpenseForHook` is the real persisted entity.

## Step C4 — Clean imports

Remove if no longer used in `ManualExpenseRepository.kt`:

```kotlin
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
```

Keep `Expense` import only if still needed elsewhere.

## Step C5 — Optional result-carrier improvement

Do not implement in this PR unless trivial, but leave a follow-up TODO:

```text
Future: CreateExpenseResult.Created may carry persisted snapshot to avoid extra DAO fetch.
```

Do not block this fix on result-model refactor.

## PR C tests

```text
manual_create_fetches_persisted_expense_for_recommendation_hook
manual_recommendation_uses_persisted_baseAmount
manual_recommendation_uses_persisted_exchangeRateUsed
manual_recommendation_uses_persisted_merchantKey
manual_recommendation_uses_persisted_dedupeKey
manual_create_created_but_missing_row_rolls_back_and_returns_error
manual_duplicate_does_not_generate_recommendations
manual_validation_failed_does_not_generate_recommendations
manual_recommendation_generation_still_runs_post_commit_only
```

Testing hints:

- Use fake `GenerateTransactionInsightUseCase` that records the `Expense`.
- Use fake `DashboardFollowThroughEngine` that records the transaction.
- Seed or fake currency conversion so persisted `baseAmount` / `exchangeRateUsed` differs from synthetic defaults.
- Assert recorded transaction ID equals created ID and fields match DAO row.

## PR C acceptance criteria

- `P2-CURRENT-019` TODO is removed.
- No synthetic `Expense` is built for recommendation/AI hook.
- Recommendation hook receives the DAO-persisted row.
- Persisted conversion fields are visible to AI/recommendation generation.
- Duplicate/validation/error outcomes do not generate recommendations.

---

# Recommended execution order

1. **PR C — Manual persisted expense hook**
   - Smallest and safest.
2. **PR B — Side-effect failure transaction-event contract**
   - Medium-risk DI/eventing change.
3. **PR A — GroupLifecycleCoordinator**
   - Larger lifecycle/API change.

If P2-06 group hard-delete validation work has already landed, merge PR A with that work and only add the missing `GroupLifecycleCoordinator` wrapper + static guard.

---

# Validation commands

Run after each PR:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*GroupLifecycleCoordinator*'
./gradlew testDebugUnitTest --tests '*SideEffect*'
./gradlew testDebugUnitTest --tests '*ManualExpenseRepository*'
./gradlew testDebugUnitTest --tests '*RecommendationHook*'
```

Manual grep checks:

```bash
grep -R "TODO (PR-E15)" app/src/main/java
grep -R "P2-CURRENT-019" app/src/main/java
grep -R "SIDE_EFFECT_FAILED" app/src/main/java
grep -R "permanentlyDeleteGroup(groupId: Long): Boolean" app/src/main/java
grep -R "synthetic Expense" app/src/main/java
grep -R "DuplicateDetectionPolicy.generateDedupeKeyWithType" app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
```

Expected final state:

- `GroupLifecycleCoordinator` exists and is used for permanent delete.
- Direct permanent hard-delete calls are deprecated/guarded.
- `SIDE_EFFECT_FAILED` is written to `transaction_events` for transaction/expense side-effect failures.
- Manual recommendation hook uses persisted DAO row.
- Synthetic manual `Expense` construction is gone.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `GroupTransactionCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
- Domain group interface: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt
- `PostCommitActionRunnerImpl.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerImpl.kt
- `DiagnosticSideEffectEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/DiagnosticSideEffectEventWriter.kt
- `PostCommitAction.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitAction.kt
- `TransactionLifecycleEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt
- `ManualExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt