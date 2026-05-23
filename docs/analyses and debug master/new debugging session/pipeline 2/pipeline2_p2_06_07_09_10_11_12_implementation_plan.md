# Pipeline 2 implementation plan — P2-06, P2-07, P2-09, P2-10, P2-11, P2-12

Target baseline: commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Mode: static code-review-based plan.

Issues covered:

| ID | Main theme |
|---|---|
| P2-06 | Group hard-delete lifecycle incomplete |
| P2-07 | Bulk side effects incomplete |
| P2-09 | Soft/hard delete semantics need FK/orphan tests |
| P2-10 | Deferred side-effect contract still relies on caller discipline |
| P2-11 | Duplicate visibility residual edge cases |
| P2-12 | Duplicate budget-check regression tests |

Relevant current evidence:

- `GroupTransactionCoordinator` has TODOs for `GroupLifecycleCoordinator`, group lifecycle audit, hard-delete confirmation, outstanding-balance validation, and last-current-user/member guards.
- `deleteGroupAtomic()` now clears shared flags inside the DB transaction and writes a `BULK_UPDATED` transaction event, but permanent group delete still has weak lifecycle semantics.
- `TransactionSideEffectPlanner.planBulkUpdated()` only creates a `bulk_budget_check` action.
- `TransactionLifecycleCoordinator.createExpense(request, sideEffectMode)` still exists and `createExpenseDbOnlyV2()` suppresses `DEPRECATION_ERROR` to call it with `SideEffectMode.DEFER`.
- `TransactionEvent` intentionally has no FK to `Expense`, so transaction events should survive expense deletion.
- `GroupExpense.expenseId` uses FK cascade to `Expense`, so deleting an expense removes the group link.
- `ReceiptExpenseLink` has no FK; app-layer integrity must be tested.
- `RecurringOccurrence.linkedExpenseId` has no FK; unlink behavior must be tested.
- `LifecycleEventType` includes `BULK_UPDATED`, `DELETED`, `SIDE_EFFECT_FAILED`, etc.

---

# Recommended PR slicing

Implement as **five PRs**:

1. **PR A — Group hard-delete lifecycle contract**
   - Fixes P2-06.

2. **PR B — Bulk side-effect expansion**
   - Fixes P2-07.

3. **PR C — Delete semantics regression suite**
   - Fixes P2-09.

4. **PR D — Deferred side-effect API hardening**
   - Fixes P2-10.

5. **PR E — Duplicate visibility + budget regression tests**
   - Covers P2-11 and P2-12.
   - If P2-P1-02/P2-P1-03 are not already done, make this PR depend on that dedupe/diagnostics PR.

Do not mix receipt legacy-path cleanup, update validation, source-link callsite completion, or DAO mutation static guard into these PRs unless a test requires a minimal local change.

---

# PR A — Group hard-delete lifecycle contract

## Fixes

- P2-06

## Current problem

`GroupTransactionCoordinator.permanentlyDeleteGroup(groupId)` currently:

- has no explicit confirmation flag,
- returns `Boolean`, hiding the reason for failure,
- calls `deleteGroupAtomic(groupId)`,
- hard-deletes group expenses, members, and group,
- clears linked expenses’ shared flags,
- writes only a generic `BULK_UPDATED` event for expense flag cleanup,
- does not write a clear group lifecycle audit event,
- does not validate unsettled balances,
- does not guard last/current-user semantics before destructive member deletion.

## Goal

Permanent group deletion must become explicit, audited, validated, and hard to call accidentally.

Soft archive should remain the default delete behavior.

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorHardDeleteTest.kt
```

If android instrumented Room tests are used in this project, place tests under the existing androidTest Room test area.

---

## Step A1 — Add typed permanent-delete result

Modify domain interface file:

```text
domain/groups/GroupTransactionCoordinator.kt
```

Add:

```kotlin
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
        val unpaidExpenseCount: Int
    ) : PermanentGroupDeleteResult

    data class LastCurrentUserWouldBeRemoved(
        val groupId: Long
    ) : PermanentGroupDeleteResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : PermanentGroupDeleteResult
}
```

Then add a new interface method:

```kotlin
suspend fun permanentlyDeleteGroup(
    groupId: Long,
    confirmPermanentDelete: Boolean
): PermanentGroupDeleteResult
```

Keep the old method temporarily:

```kotlin
@Deprecated(
    message = "Use permanentlyDeleteGroup(groupId, confirmPermanentDelete = true).",
    level = DeprecationLevel.ERROR
)
suspend fun permanentlyDeleteGroup(groupId: Long): Boolean
```

If interface binary compatibility is irrelevant, delete the old method. If UI still calls it, update UI callsites.

---

## Step A2 — Add lifecycle event types

Modify:

```text
LifecycleEventType.kt
```

Add:

```kotlin
GROUP_ARCHIVED,
GROUP_PERMANENT_DELETE_ATTEMPTED,
GROUP_PERMANENT_DELETE_BLOCKED,
GROUP_PERMANENTLY_DELETED
```

No DB migration should be needed because `TransactionEvent.eventType` is stored as `String`.

Use `transaction_events` as a temporary audit channel with:

```kotlin
expenseId = null
source = "GROUP_LIFECYCLE"
metadata.groupId = groupId
```

Do **not** create a separate `group_lifecycle_events` table in this PR unless the project already has that pattern.

---

## Step A3 — Add group DAO validation helpers

Modify `GroupExpenseDao.kt`.

Add:

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

Also add a simpler generic unsettled guard if reimbursable semantics are not reliable enough:

```kotlin
@Query("""
    SELECT COUNT(*)
    FROM group_expenses
    WHERE groupId = :groupId
      AND (
          (isReimbursable = 1 AND settledAt IS NULL AND reimbursedAmount < totalAmount)
          OR totalAmount < 0
      )
""")
suspend fun countPotentiallyUnsettledExpenses(groupId: Long): Int
```

Pick one canonical method and use it consistently.

Modify `GroupMemberDao.kt`.

Add:

```kotlin
@Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1")
suspend fun countCurrentUsers(groupId: Long): Int
```

Rationale:

- Before hard delete, if group still has a current-user member, require archive-first or explicit “delete inactive group” flow.
- This protects against accidentally deleting the user’s active participation history.

---

## Step A4 — Make hard delete require archive-first

Implement rule:

```text
Permanent delete is allowed only for inactive/archived groups.
```

In `permanentlyDeleteGroup(groupId, confirmPermanentDelete)`:

1. Check write barrier.
2. If `confirmPermanentDelete == false`, return `ConfirmationRequired`.
3. Load group.
4. If missing, return `GroupNotFound`.
5. If `group.isActive == true`, return `GroupStillActive`.
6. Check outstanding/unsettled expense count.
7. If count > 0, return `OutstandingBalancesExist`.
8. Check current user count.
9. If count > 0, return `LastCurrentUserWouldBeRemoved`.
10. Proceed to atomic hard delete.

Why archive-first?

- It prevents accidental direct hard deletes from active UI paths.
- It creates a clear two-step destructive lifecycle:
  - archive first,
  - permanently delete only archived/inactive group.

If product requires direct active hard delete, still require `confirmPermanentDelete = true`, but keep tests for explicit confirmation and lifecycle audit.

---

## Step A5 — Write attempted/blocked/deleted group lifecycle events

Use existing `TransactionLifecycleEventWriter`.

Inside `permanentlyDeleteGroup(...)`, before validation failure returns, write best-effort blocked events or strict events depending on validation stage.

Recommended:

- For mutation attempt:
  - best-effort event before hard delete.
- For blocked validations:
  - best-effort `GROUP_PERMANENT_DELETE_BLOCKED`.
- For actual hard delete:
  - strict event inside same transaction as hard delete.

Helper:

```kotlin
private suspend fun writeGroupLifecycleEvent(
    eventType: LifecycleEventType,
    groupId: Long,
    source: String,
    correlationId: String,
    reason: String,
    extra: Map<String, Any?> = emptyMap()
) {
    transactionLifecycleEventWriter.write(
        TransactionLifecycleEvent(
            expenseId = null,
            eventType = eventType.name,
            source = source,
            actor = "system:group_transaction_coordinator",
            correlationId = correlationId,
            metadata = SafeEventMetadata.builder()
                .put("groupId", groupId.toString())
                .put("operation", source)
                .apply {
                    extra.forEach { (key, value) ->
                        if (value != null) put(key, value.toString())
                    }
                }
                .build(),
            reason = reason
        )
    )
}
```

If `SafeEventMetadata.builder().apply {}` does not compile due builder type, write explicitly.

Metadata must not include raw member names, emails, notes, descriptions, or split JSON.

---

## Step A6 — Replace `permanentlyDeleteGroup(groupId): Boolean`

Implementation shape:

```kotlin
override suspend fun permanentlyDeleteGroup(
    groupId: Long,
    confirmPermanentDelete: Boolean
): PermanentGroupDeleteResult = withContext(ioDispatcher) {
    val correlationId = CorrelationIds.newId()

    try {
        writeBarrier.checkWritesAllowed("GroupTransactionCoordinator.permanentlyDeleteGroup")

        if (!confirmPermanentDelete) {
            writeGroupLifecycleEvent(
                eventType = LifecycleEventType.GROUP_PERMANENT_DELETE_BLOCKED,
                groupId = groupId,
                source = "GROUP_HARD_DELETE",
                correlationId = correlationId,
                reason = "Permanent delete blocked: confirmation required",
                extra = mapOf("blockedReason" to "confirmation_required")
            )
            return@withContext PermanentGroupDeleteResult.ConfirmationRequired
        }

        val group = groupDao.getById(groupId)
            ?: return@withContext PermanentGroupDeleteResult.GroupNotFound

        if (group.isActive) {
            writeGroupLifecycleEvent(
                eventType = LifecycleEventType.GROUP_PERMANENT_DELETE_BLOCKED,
                groupId = groupId,
                source = "GROUP_HARD_DELETE",
                correlationId = correlationId,
                reason = "Permanent delete blocked: group is still active",
                extra = mapOf("blockedReason" to "group_still_active")
            )
            return@withContext PermanentGroupDeleteResult.GroupStillActive
        }

        val outstandingCount = groupExpenseDao.countOutstandingReimbursableExpenses(groupId)
        if (outstandingCount > 0) {
            writeGroupLifecycleEvent(
                eventType = LifecycleEventType.GROUP_PERMANENT_DELETE_BLOCKED,
                groupId = groupId,
                source = "GROUP_HARD_DELETE",
                correlationId = correlationId,
                reason = "Permanent delete blocked: outstanding balances exist",
                extra = mapOf(
                    "blockedReason" to "outstanding_balances",
                    "outstandingCount" to outstandingCount
                )
            )
            return@withContext PermanentGroupDeleteResult.OutstandingBalancesExist(
                groupId = groupId,
                unpaidExpenseCount = outstandingCount
            )
        }

        val currentUserCount = memberDao.countCurrentUsers(groupId)
        if (currentUserCount > 0) {
            writeGroupLifecycleEvent(
                eventType = LifecycleEventType.GROUP_PERMANENT_DELETE_BLOCKED,
                groupId = groupId,
                source = "GROUP_HARD_DELETE",
                correlationId = correlationId,
                reason = "Permanent delete blocked: current user membership still exists",
                extra = mapOf(
                    "blockedReason" to "current_user_membership_exists",
                    "currentUserCount" to currentUserCount
                )
            )
            return@withContext PermanentGroupDeleteResult.LastCurrentUserWouldBeRemoved(groupId)
        }

        val linkedExpenseCount = deleteGroupAtomic(
            groupId = groupId,
            correlationId = correlationId
        )

        PermanentGroupDeleteResult.Deleted(groupId, linkedExpenseCount)
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

Update the old Boolean method if retained:

```kotlin
@Deprecated(..., level = DeprecationLevel.ERROR)
override suspend fun permanentlyDeleteGroup(groupId: Long): Boolean {
    return permanentlyDeleteGroup(
        groupId = groupId,
        confirmPermanentDelete = false
    ) is PermanentGroupDeleteResult.Deleted
}
```

Do not default confirmation to true.

---

## Step A7 — Make `deleteGroupAtomic()` return affected linked count

Change:

```kotlin
suspend fun deleteGroupAtomic(groupId: Long)
```

to:

```kotlin
suspend fun deleteGroupAtomic(
    groupId: Long,
    correlationId: String = CorrelationIds.newId()
): Int
```

Inside:

1. Collect `linkedExpenseIds`.
2. In the same `database.withTransaction`:
   - delete group expenses,
   - delete members,
   - delete group,
   - clear shared flags,
   - write `GROUP_PERMANENTLY_DELETED`,
   - write existing `BULK_UPDATED` if linked count > 0.

Pseudo:

```kotlin
val linkedExpenseIds = groupExpenseDao.getExpensesForGroupOnce(groupId)
    .mapNotNull { it.expenseId }

database.withTransaction {
    groupExpenseDao.deleteAllForGroup(groupId)
    memberDao.deleteAllForGroup(groupId)
    groupDao.getById(groupId)?.let { groupDao.delete(it) }

    linkedExpenseIds.forEach { expenseId ->
        expenseDao.clearSharedExpenseFlags(expenseId)
    }

    transactionLifecycleEventWriter.write(
        TransactionLifecycleEvent(
            expenseId = null,
            eventType = LifecycleEventType.GROUP_PERMANENTLY_DELETED.name,
            source = "GROUP_HARD_DELETE",
            actor = "system:group_transaction_coordinator",
            correlationId = correlationId,
            metadata = SafeEventMetadata.builder()
                .put("groupId", groupId.toString())
                .put("linkedExpenseCount", linkedExpenseIds.size.toString())
                .build(),
            reason = "Group permanently deleted"
        )
    )

    if (linkedExpenseIds.isNotEmpty()) {
        transactionLifecycleEventWriter.write(
            TransactionLifecycleEvent(
                expenseId = null,
                eventType = LifecycleEventType.BULK_UPDATED.name,
                source = "GROUP_HARD_DELETE",
                actor = "system:group_transaction_coordinator",
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("groupId", groupId.toString())
                    .put("affectedCount", linkedExpenseIds.size.toString())
                    .put("changedFields", "isSharedExpense,myShareAmount,mySharePercentage,sharedWithName")
                    .build(),
                reason = "Group hard-delete cleared shared expense flags"
            )
        )
    }
}

if (linkedExpenseIds.isNotEmpty()) {
    val actions = transactionSideEffectPlanner.planBulkUpdated(
        source = "GROUP_HARD_DELETE",
        affectedCount = linkedExpenseIds.size,
        correlationId = correlationId,
        changedFields = setOf(
            BulkChangedField.OWNERSHIP,
            BulkChangedField.AMOUNT_EFFECTIVE
        )
    )
    runGroupPostCommitActions(actions)
}

return linkedExpenseIds.size
```

`changedFields` requires PR B. If PR A is done before PR B, keep old `planBulkUpdated(...)` call and add a TODO to switch once PR B lands.

---

## Step A8 — Archive lifecycle audit

Update `archiveGroup(groupId)` and `deleteGroup(groupId)` to write a group lifecycle event.

Inside one DB transaction:

```kotlin
database.withTransaction {
    val group = groupDao.getById(groupId)
    if (group == null) return@withTransaction false
    groupDao.archiveGroup(groupId)
    writeGroupLifecycleEvent(
        eventType = LifecycleEventType.GROUP_ARCHIVED,
        groupId = groupId,
        source = "GROUP_ARCHIVE",
        correlationId = correlationId,
        reason = "Group archived"
    )
    true
}
```

If the method returns from inside `withTransaction`, be careful with Kotlin non-local returns. Prefer explicit local result.

---

## PR A tests

Required tests:

```text
permanent_delete_without_confirmation_returns_ConfirmationRequired
permanent_delete_without_confirmation_does_not_delete_group
permanent_delete_without_confirmation_writes_GROUP_PERMANENT_DELETE_BLOCKED

permanent_delete_active_group_returns_GroupStillActive
permanent_delete_active_group_does_not_delete_group
permanent_delete_active_group_writes_blocked_event

permanent_delete_with_outstanding_reimbursable_expenses_blocks
permanent_delete_with_current_user_member_blocks
permanent_delete_missing_group_returns_GroupNotFound

permanent_delete_archived_confirmed_deletes_group_members_and_group_expenses
permanent_delete_archived_confirmed_clears_linked_expense_shared_flags
permanent_delete_archived_confirmed_writes_GROUP_PERMANENTLY_DELETED_in_same_transaction
permanent_delete_archived_confirmed_writes_BULK_UPDATED_for_linked_expenses
permanent_delete_archived_confirmed_runs_bulk_post_commit_once

archive_group_writes_GROUP_ARCHIVED
archive_group_preserves_group_expenses_and_members
```

Atomic rollback test:

```text
permanent_delete_audit_insert_failure_rolls_back_group_delete
```

Implementation hint:

- Use a fake `TransactionLifecycleEventWriter` that throws inside transaction, or a DAO conflict if easier.
- Assert group/member/group_expense rows still exist.

---

## PR A acceptance criteria

- Hard delete requires explicit confirmation.
- Hard delete requires archived/inactive group.
- Hard delete is blocked if outstanding/unsettled balances exist.
- Hard delete is blocked if current-user membership still exists.
- Successful hard delete writes `GROUP_PERMANENTLY_DELETED` atomically.
- Shared expense flag cleanup still writes `BULK_UPDATED`.
- Side effects still run after commit only.
- No raw member names, emails, notes, descriptions, or split JSON appear in audit metadata.

---

# PR B — Bulk side-effect expansion

## Fixes

- P2-07

## Current problem

`TransactionSideEffectPlanner.planBulkUpdated(source, affectedCount, correlationId)` creates only one action:

```text
bulk_budget_check
```

Missing after bulk updates:

- anomaly invalidation/re-evaluation,
- dashboard/analytics cache invalidation,
- merchant/category model dirty marking,
- recurring reconciliation when merchant/type/date/frequency-relevant fields changed.

## Goal

Bulk updates should emit one normalized post-commit batch with targeted actions based on what changed.

Do **not** loop through every expense unless the side effect explicitly requires per-row data and the affected count is small.

## Files to modify

Primary:

```text
TransactionSideEffectPlanner.kt
TransactionSideEffectDispatcher.kt
```

Possible support files if APIs do not exist yet:

```text
domain/sideeffect/SideEffectCategory.kt
domain/sideeffect/SideEffectTriggerType.kt
data/repository/MerchantCategoryRepository.kt
data/repository/MerchantNormalizationRepository.kt
domain/alerts/AnomalyAlertOrchestrator.kt
```

Tests:

```text
TransactionSideEffectPlannerBulkTest.kt
```

---

## Step B1 — Add changed-field model

Create:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/BulkChangedField.kt
```

Content:

```kotlin
package com.yourname.expensetracker.domain.transaction.lifecycle

enum class BulkChangedField {
    AMOUNT,
    AMOUNT_EFFECTIVE,
    CATEGORY,
    MERCHANT,
    MERCHANT_KEY,
    TRANSACTION_TYPE,
    DATE,
    CURRENCY,
    OWNERSHIP,
    TRANSFER,
    LOCATION,
    BUSINESS_FLAGS,
    UNKNOWN
}
```

Add helpers:

```kotlin
fun Set<BulkChangedField>.affectsBudget(): Boolean =
    isEmpty() ||
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.AMOUNT_EFFECTIVE,
            BulkChangedField.CATEGORY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.OWNERSHIP,
            BulkChangedField.TRANSFER,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsAnomaly(): Boolean =
    isEmpty() ||
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.AMOUNT_EFFECTIVE,
            BulkChangedField.CATEGORY,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.OWNERSHIP,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsMerchantLearning(): Boolean =
    any {
        it in setOf(
            BulkChangedField.CATEGORY,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsRecurring(): Boolean =
    any {
        it in setOf(
            BulkChangedField.AMOUNT,
            BulkChangedField.MERCHANT,
            BulkChangedField.MERCHANT_KEY,
            BulkChangedField.TRANSACTION_TYPE,
            BulkChangedField.DATE,
            BulkChangedField.CURRENCY,
            BulkChangedField.UNKNOWN
        )
    }

fun Set<BulkChangedField>.affectsAnalyticsCache(): Boolean =
    isEmpty() || any { it != BulkChangedField.LOCATION }
```

If top-level helper style is not used in project, place helpers as private functions inside planner.

---

## Step B2 — Update planner signature

Change:

```kotlin
fun planBulkUpdated(
    source: String,
    affectedCount: Int,
    correlationId: String?
): PostCommitActionBatch
```

to:

```kotlin
fun planBulkUpdated(
    source: String,
    affectedCount: Int,
    correlationId: String?,
    changedFields: Set<BulkChangedField> = setOf(BulkChangedField.UNKNOWN)
): PostCommitActionBatch
```

Update `TransactionSideEffectDispatcher.dispatchOnBulkUpdated`:

```kotlin
suspend fun dispatchOnBulkUpdated(
    source: String,
    affectedCount: Int,
    changedFields: Set<BulkChangedField> = setOf(BulkChangedField.UNKNOWN)
) {
    val batch = planner.planBulkUpdated(source, affectedCount, null, changedFields)
    runner.run(batch)
}
```

Update callsites:

- group hard-delete:
  - `OWNERSHIP`, `AMOUNT_EFFECTIVE`
- bulk category update:
  - `CATEGORY`
- bulk merchant update:
  - `MERCHANT`, `MERCHANT_KEY`
- category reassignment by category if present:
  - `CATEGORY`
- fallback unknown:
  - `UNKNOWN`

---

## Step B3 — Keep zero-count skip action

If `affectedCount <= 0`, return a batch with a single skipped action or empty batch.

Recommended:

```kotlin
return PostCommitActionBatch.empty(corrId)
```

If the diagnostics runner expects a visible skipped side effect, keep the current skipped `bulk_budget_check`.

Do not run expensive invalidations for zero affected rows.

---

## Step B4 — Add bulk action factories

Add private factories to `TransactionSideEffectPlanner`.

### Budget

Existing bulk budget action can be extracted:

```kotlin
private fun makeBulkBudgetCheckAction(
    source: String,
    affectedCount: Int,
    changedFields: Set<BulkChangedField>,
    correlationId: String
): PostCommitAction
```

Name:

```text
bulk_budget_check
```

Category:

```kotlin
SideEffectCategory.BUDGET
```

Trigger:

```kotlin
SideEffectTriggerType.EXPENSE_BULK_UPDATED
```

Action:

```kotlin
budgetMonitor.get().checkBudgets()
```

### Anomaly invalidation

If `AnomalyAlertOrchestrator` has no bulk invalidation method, add the smallest safe method:

```kotlin
suspend fun invalidateAfterBulkExpenseMutation(
    source: String,
    affectedCount: Int,
    changedFields: Set<String>
)
```

If adding that is too broad, create a post-commit action that returns `Skipped(NOT_APPLICABLE)` with metadata and TODO. But preferred is a real invalidation/dirty marker.

Action name:

```text
bulk_anomaly_invalidation
```

Category:

```kotlin
SideEffectCategory.ANOMALY
```

### Merchant/category model refresh

Preferred minimal APIs:

In `MerchantCategoryRepository`:

```kotlin
suspend fun markPatternsDirty(reason: String)
```

In `MerchantNormalizationRepository`:

```kotlin
suspend fun markCanonicalStatsDirty(reason: String)
```

If repositories do not support dirty flags, add no-op methods with TODO and diagnostics, but tests should verify the action is planned.

Action names:

```text
bulk_merchant_category_dirty
bulk_merchant_canonical_stats_dirty
```

Category:

```kotlin
SideEffectCategory.MERCHANT_LEARNING
```

### Analytics/dashboard cache invalidation

If no cache invalidator exists, add an interface:

```text
app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCacheInvalidator.kt
```

```kotlin
interface AnalyticsCacheInvalidator {
    suspend fun invalidateForExpenseBulkMutation(
        source: String,
        affectedCount: Int,
        changedFields: Set<String>
    )
}
```

Provide a no-op implementation if the project has no cache yet:

```kotlin
@Singleton
class NoOpAnalyticsCacheInvalidator @Inject constructor() : AnalyticsCacheInvalidator {
    override suspend fun invalidateForExpenseBulkMutation(
        source: String,
        affectedCount: Int,
        changedFields: Set<String>
    ) = Unit
}
```

Bind in DI.

Action name:

```text
bulk_analytics_cache_invalidation
```

Category:

```kotlin
SideEffectCategory.CACHE_INVALIDATION
```

### Recurring reconciliation

Preferred minimal API in `RecurringLifecycleCoordinator`:

```kotlin
suspend fun reconcileAfterBulkExpenseMutation(
    source: String,
    affectedCount: Int,
    changedFields: Set<String>
)
```

If unavailable, create a coarse dirty-marker interface instead.

Action name:

```text
bulk_recurring_reconciliation
```

Category:

```kotlin
SideEffectCategory.RECURRING
```

Only plan this action when `changedFields.affectsRecurring()`.

---

## Step B5 — Build conditional action list

Implementation shape:

```kotlin
fun planBulkUpdated(
    source: String,
    affectedCount: Int,
    correlationId: String?,
    changedFields: Set<BulkChangedField>
): PostCommitActionBatch {
    val corrId = correlationId ?: CorrelationIds.newId()

    if (affectedCount <= 0) {
        return PostCommitActionBatch.empty(corrId)
    }

    val normalizedFields = changedFields.ifEmpty { setOf(BulkChangedField.UNKNOWN) }

    val actions = mutableListOf<PostCommitAction>()

    if (normalizedFields.affectsBudget()) {
        actions += makeBulkBudgetCheckAction(source, affectedCount, normalizedFields, corrId)
    }

    if (normalizedFields.affectsAnomaly()) {
        actions += makeBulkAnomalyInvalidationAction(source, affectedCount, normalizedFields, corrId)
    }

    if (normalizedFields.affectsMerchantLearning()) {
        actions += makeBulkMerchantCategoryDirtyAction(source, affectedCount, normalizedFields, corrId)
        actions += makeBulkMerchantCanonicalStatsDirtyAction(source, affectedCount, normalizedFields, corrId)
    }

    if (normalizedFields.affectsAnalyticsCache()) {
        actions += makeBulkAnalyticsCacheInvalidationAction(source, affectedCount, normalizedFields, corrId)
    }

    if (normalizedFields.affectsRecurring()) {
        actions += makeBulkRecurringReconciliationAction(source, affectedCount, normalizedFields, corrId)
    }

    return PostCommitActionBatch(corrId, actions).normalized()
}
```

Every action metadata should include:

```kotlin
SafeEventMetadata.builder()
    .put("source", source)
    .put("affectedCount", affectedCount.toString())
    .put("changedFields", normalizedFields.joinToString(",") { it.name })
    .build()
```

No raw merchants, notes, descriptions, or payloads.

---

## Step B6 — Update known bulk callsites

### Group hard-delete

In `GroupTransactionCoordinator.deleteGroupAtomic()` post-commit:

```kotlin
transactionSideEffectPlanner.planBulkUpdated(
    source = "GROUP_HARD_DELETE",
    affectedCount = linkedExpenseIds.size,
    correlationId = correlationId,
    changedFields = setOf(
        BulkChangedField.OWNERSHIP,
        BulkChangedField.AMOUNT_EFFECTIVE
    )
)
```

### `bulkUpdateCategory`

In `TransactionLifecycleCoordinator.bulkUpdateCategory()`:

```kotlin
planner.planBulkUpdated(
    source = source,
    affectedCount = affected,
    correlationId = correlationId,
    changedFields = setOf(BulkChangedField.CATEGORY)
)
```

### `bulkUpdateMerchant`

```kotlin
changedFields = setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY)
```

If these methods currently call old `planBulkUpdated`, update them.

---

## PR B tests

Required tests:

```text
planBulkUpdated_zero_count_returns_empty_or_skipped_no_work_batch
planBulkUpdated_category_includes_budget_anomaly_merchant_learning_cache
planBulkUpdated_merchant_includes_budget_anomaly_merchant_learning_cache_recurring
planBulkUpdated_ownership_includes_budget_anomaly_cache_not_merchant_learning
planBulkUpdated_location_only_does_not_include_budget_or_recurring
planBulkUpdated_unknown_includes_all_safe_global_invalidations
planBulkUpdated_actions_have_unique_idempotency_keys
planBulkUpdated_metadata_contains_affectedCount_source_changedFields
planBulkUpdated_metadata_contains_no_raw_merchant_or_payload
```

Callsite tests:

```text
group_hard_delete_uses_ownership_amount_effective_changed_fields
bulk_category_update_uses_category_changed_field
bulk_merchant_update_uses_merchant_changed_fields
```

---

## PR B acceptance criteria

- Bulk updates no longer only check budgets.
- Bulk planner creates targeted actions based on changed fields.
- All bulk actions are aggregate actions, not N per-row side effects.
- Existing post-commit runner diagnostics still record action outcomes.
- Metadata is privacy-safe.

---

# PR C — Delete semantics regression suite

## Fixes

- P2-09

## Current problem

Delete semantics are partially improved, but FK/orphan behavior needs explicit tests.

Known schema behavior:

- `TransactionEvent` has no FK to `Expense`; events must survive expense deletion.
- `GroupExpense.expenseId` has FK cascade to `Expense`; deleting an expense removes group link.
- `ReceiptExpenseLink` has no FK; deleting an expense may leave an app-layer orphan unless lifecycle explicitly cleans it.
- `RecurringOccurrence.linkedExpenseId` has no FK; lifecycle side effect should unlink or preserve according to contract.

## Goal

Document and test hard/soft delete semantics so future migrations do not accidentally cascade audit data or create invisible orphan states.

This PR should be mostly tests. Only fix code if tests expose an actual broken contract.

## Files to inspect/modify

Primary tests:

```text
TransactionLifecycleCoordinatorDeleteSemanticsTest.kt
ExpenseDeleteReferentialIntegrityTest.kt
```

Possible code files if tests fail:

```text
TransactionLifecycleCoordinator.kt
TransactionSideEffectPlanner.kt
ReceiptExpenseLinkDao.kt
RecurringOccurrenceDao.kt
GroupExpenseDao.kt
```

---

## Step C1 — Define delete contract in test names

Contract:

1. Expense delete is hard delete from `expenses`.
2. `transaction_events` rows remain.
3. A `DELETED` event is written with beforeSnapshot.
4. `DELETED.afterSnapshot == null`.
5. Group expense link is removed by FK cascade.
6. Receipt links:
   - either are explicitly deleted by lifecycle, or
   - are intentionally retained and query code filters missing expense.
7. Recurring occurrence:
   - `linkedExpenseId` is cleared by recurring unlink side effect, or
   - occurrence status is preserved but no stale paid link remains.
8. Delete side effects run once after commit.

The agent must confirm actual current behavior and encode it in tests. If behavior is unsafe, fix it.

---

## Step C2 — Add DAO helpers if missing

If missing, add read helpers for tests and production cleanup.

Receipt link DAO:

```kotlin
@Query("SELECT * FROM receipt_expense_links WHERE expenseId = :expenseId")
suspend fun getLinksForExpense(expenseId: Long): List<ReceiptExpenseLink>

@Query("DELETE FROM receipt_expense_links WHERE expenseId = :expenseId")
suspend fun deleteLinksForExpense(expenseId: Long): Int
```

Recurring occurrence DAO:

```kotlin
@Query("SELECT * FROM recurring_occurrences WHERE linkedExpenseId = :expenseId")
suspend fun getOccurrencesLinkedToExpense(expenseId: Long): List<RecurringOccurrence>

@Query("""
    UPDATE recurring_occurrences
    SET linkedExpenseId = NULL,
        status = CASE WHEN status = 'PAID' THEN 'PLANNED' ELSE status END,
        paidAt = NULL,
        paidAmount = NULL,
        paidCurrency = NULL,
        updatedAt = :updatedAt
    WHERE linkedExpenseId = :expenseId
""")
suspend fun unlinkExpense(expenseId: Long, updatedAt: Long): Int
```

Only add production DAO methods if tests need them and they match lifecycle behavior.

---

## Step C3 — Test transaction events survive delete

Test:

```text
delete_expense_preserves_transaction_events
```

Arrange:

1. Create expense through coordinator.
2. Assert `CREATED` event exists.
3. Delete expense through coordinator/repository.
4. Assert expense row missing.
5. Assert old `CREATED` event still exists.
6. Assert new `DELETED` event exists.

Expected:

```kotlin
transactionEventDao.getEventsForExpense(expenseId).map { it.eventType }
```

contains:

```text
CREATED
DELETED
```

If no `getEventsForExpense` DAO helper exists, add test-only DAO or production query:

```kotlin
@Query("SELECT * FROM transaction_events WHERE expenseId = :expenseId ORDER BY occurredAt ASC")
suspend fun getEventsForExpense(expenseId: Long): List<TransactionEvent>
```

---

## Step C4 — Test deleted snapshot shape

Test:

```text
delete_expense_writes_before_snapshot_and_null_after_snapshot
```

Expected:

- `DELETED.beforeSnapshot != null`
- `DELETED.afterSnapshot == null`
- before snapshot contains ID, amount, currency, merchant or sanitized expected data depending snapshot format.

Do not assert too many raw string internals. Assert key fields only.

---

## Step C5 — Test group link cascade behavior

Test:

```text
delete_expense_removes_group_expense_link_by_contract
```

Arrange:

1. Create group with members.
2. Create expense.
3. Link expense to group.
4. Delete expense.
5. Assert:
   - expense row missing,
   - `groupExpenseDao.getGroupExpenseForExpense(expenseId) == null`,
   - group still exists,
   - group members still exist,
   - transaction events still exist.

This matches `GroupExpense.expenseId` FK CASCADE.

---

## Step C6 — Test receipt link behavior

Test:

```text
delete_expense_receipt_link_policy_is_explicit
```

Preferred safe behavior:

- lifecycle delete should remove receipt expense links for the deleted expense.

If current behavior leaves orphan rows because there is no FK, decide now.

Recommended production fix:

Inside `TransactionLifecycleCoordinator.deleteExpense(...)` transaction:

```kotlin
receiptExpenseLinkDao.deleteLinksForExpense(expenseId)
```

and event metadata:

```json
{
  "receiptLinksDeleted": 2
}
```

If injecting `ReceiptExpenseLinkDao` into coordinator creates dependency issues, add a small `ExpenseDeleteReferenceCleaner`:

```kotlin
interface ExpenseDeleteReferenceCleaner {
    suspend fun cleanReferencesForDeletedExpense(expenseId: Long): ExpenseDeleteReferenceCleanupResult
}
```

Injected into coordinator and called inside the delete transaction.

Test expectations if cleanup is implemented:

```text
delete_expense_deletes_receipt_expense_links
delete_expense_deleted_event_metadata_contains_receiptLinksDeleted
```

If product chooses to retain app-layer receipt links, then add tests proving all receipt queries join against existing expenses and do not show orphan links. Cleanup is simpler and safer.

---

## Step C7 — Test recurring unlink behavior

Test:

```text
delete_expense_unlinks_recurring_occurrence_once
```

Arrange:

1. Create recurring occurrence linked to expense.
2. Delete expense through coordinator.
3. Assert:
   - no occurrence has `linkedExpenseId = expenseId`, or
   - lifecycle coordinator `unlinkExpenseFromOccurrence(expenseId)` called exactly once via fake.

Because `TransactionSideEffectPlanner.planDeleted()` already includes `makeRecurringUnlinkAction`, use fake `RecurringLifecycleCoordinator` or inspect DB.

Also test:

```text
delete_expense_rollback_does_not_run_recurring_unlink
```

Use fake runner/action if possible.

---

## Step C8 — Test soft vs hard group delete

Tests:

```text
archive_group_sets_isActive_false_and_preserves_members_and_expenses
permanent_group_delete_deletes_members_and_group_expenses_only_after_confirmation
permanent_group_delete_preserves_system_expenses_but_clears_shared_flags
```

These overlap PR A but belong in delete semantics suite.

---

## PR C acceptance criteria

- Tests document exact delete behavior.
- Transaction events survive expense delete.
- `DELETED` event includes current before snapshot.
- Group link behavior is explicit and tested.
- Receipt link behavior is explicit and tested.
- Recurring unlink behavior is explicit and tested.
- Soft group archive and hard group delete semantics are separated in tests.

---

# PR D — Deferred side-effect API hardening

## Fixes

- P2-10

## Current problem

`TransactionLifecycleCoordinator.createExpense(request, sideEffectMode)` still exists internally. It is deprecated with `DeprecationLevel.ERROR`, but `createExpenseDbOnlyV2()` suppresses the error and calls it with `SideEffectMode.DEFER`.

This means the core create implementation still has a mode flag:

```kotlin
SideEffectMode.IMMEDIATE
SideEffectMode.DEFER
```

The architecture relies on caller discipline and comments to avoid `IMMEDIATE` inside outer transactions.

## Goal

Remove mode-based create from the active implementation.

Create API should be split by construction:

```text
createExpenseMutation / createExpenseDbOnlyV2 -> DB mutation + planned actions, never runs side effects
createExpenseStandaloneV2 -> calls mutation, then runs planned actions after commit
```

No caller should pass a side-effect mode.

## Files to modify

Primary:

```text
TransactionLifecycleCoordinator.kt
SideEffectMode.kt
```

Tests:

```text
TransactionLifecycleCoordinatorSideEffectContractTest.kt
ArchitectureSideEffectModeUsageTest.kt
```

Callsites:

```text
GroupTransactionCoordinator.kt
ManualExpenseRepository.kt
ReviewQueueRepository.kt
ReceiptRepository.kt
any createExpense callsites
```

---

## Step D1 — Introduce private DB-only implementation

Inside `TransactionLifecycleCoordinator`, create a private method:

```kotlin
private suspend fun createExpenseMutation(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult>
```

This method should contain the create logic currently inside deprecated `createExpense(...)`, but:

- never accepts `SideEffectMode`,
- never calls `runner.run(...)`,
- returns `MutationResult(result, postCommitActions)`.

Pseudo ending:

```kotlin
val plannedBatch = if (result is CreateExpenseResult.Created) {
    planner.planCreated(result.expenseId, request.source, correlationId)
} else {
    PostCommitActionBatch.empty(correlationId)
}

return MutationResult(result, plannedBatch)
```

Important:

- Expense insert + `CREATED` event + source links remain inside one DB transaction.
- Planned side effects are not executed here.
- All returns should return `MutationResult`, including validation/duplicate/error.

---

## Step D2 — Refactor public APIs

Make `createExpenseDbOnlyV2`:

```kotlin
suspend fun createExpenseDbOnlyV2(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult> {
    return createExpenseMutation(request)
}
```

Make `createExpenseStandaloneV2`:

```kotlin
suspend fun createExpenseStandaloneV2(request: CreateExpenseRequest): CreateExpenseResult {
    val mutation = createExpenseMutation(request)
    if (mutation.value is CreateExpenseResult.Created) {
        runner.run(mutation.postCommitActions)
    }
    return mutation.value
}
```

Make `createExpenseDbOnly` wrapper:

```kotlin
suspend fun createExpenseDbOnly(request: CreateExpenseRequest): CreateExpenseResult {
    return createExpenseDbOnlyV2(request).value
}
```

Make `createExpenseStandalone` wrapper:

```kotlin
suspend fun createExpenseStandalone(request: CreateExpenseRequest): CreateExpenseResult {
    return createExpenseStandaloneV2(request)
}
```

---

## Step D3 — Remove or hard-disable old `createExpense(request, sideEffectMode)`

Preferred:

Delete:

```kotlin
createExpense(request, sideEffectMode)
```

If too many callsites or external references exist, keep it temporarily but make it a non-mode wrapper:

```kotlin
@Deprecated(
    message = "Use createExpenseStandaloneV2() or createExpenseDbOnlyV2(). SideEffectMode is forbidden.",
    level = DeprecationLevel.ERROR
)
suspend fun createExpense(
    request: CreateExpenseRequest,
    sideEffectMode: SideEffectMode = SideEffectMode.IMMEDIATE
): CreateExpenseResult {
    error("Forbidden: use createExpenseStandaloneV2() or createExpenseDbOnlyV2()")
}
```

Better transitional behavior:

```kotlin
@Suppress("UNUSED_PARAMETER")
suspend fun createExpense(...): CreateExpenseResult {
    return when (sideEffectMode) {
        SideEffectMode.IMMEDIATE -> createExpenseStandaloneV2(request)
        SideEffectMode.DEFER -> createExpenseDbOnlyV2(request).value
    }
}
```

But this keeps the risky mode path. Use only if UI compile break is too large.

---

## Step D4 — Deprecate or delete `SideEffectMode`

If no remaining production code uses `SideEffectMode`, change it to:

```kotlin
@Deprecated(
    message = "SideEffectMode is obsolete. Use createExpenseStandaloneV2 or createExpenseDbOnlyV2.",
    level = DeprecationLevel.ERROR
)
enum class SideEffectMode { IMMEDIATE, DEFER }
```

Do not use `@Suppress("DEPRECATION_ERROR")` anywhere in main source.

---

## Step D5 — Remove suppressions

Search:

```bash
grep -R "DEPRECATION_ERROR" app/src/main/java
grep -R "SideEffectMode" app/src/main/java
grep -R "createExpense(.*SideEffectMode" app/src/main/java
```

Expected after PR:

- no `DEPRECATION_ERROR` suppression related to `SideEffectMode`,
- no production call to `createExpense(..., SideEffectMode...)`,
- only `SideEffectMode.kt` may contain the enum if retained for compatibility.

---

## Step D6 — Add architecture/static test

Create:

```text
app/src/test/java/com/yourname/expensetracker/architecture/SideEffectModeUsageTest.kt
```

Test:

```kotlin
class SideEffectModeUsageTest {

    private val sourceRoot = Path.of("src/main/java")

    @Test
    fun no_production_usage_of_side_effect_mode() {
        val allowedFiles = setOf(
            "SideEffectMode.kt"
        )

        val offenders = kotlinFiles()
            .filter { it.fileName.toString() !in allowedFiles }
            .filter { file ->
                val text = file.readText()
                text.contains("SideEffectMode") ||
                    text.contains("@Suppress(\"DEPRECATION_ERROR\")")
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "SideEffectMode or DEPRECATION_ERROR suppression found in production source. " +
                "Use createExpenseStandaloneV2() or createExpenseDbOnlyV2():\n" +
                offenders.joinToString("\n")
            )
        }
    }

    private fun kotlinFiles(): Sequence<Path> =
        Files.walk(sourceRoot).filter { it.toString().endsWith(".kt") }
}
```

Adjust path root for project test working directory.

---

## Step D7 — Contract tests

Tests:

```text
createExpenseDbOnlyV2_returns_created_and_planned_actions_but_does_not_run_runner
createExpenseStandaloneV2_runs_runner_after_create_commit
createExpenseStandaloneV2_does_not_run_runner_for_validation_failed
createExpenseStandaloneV2_does_not_run_runner_for_duplicate
createExpenseDbOnlyV2_can_be_used_inside_outer_transaction_and_actions_run_after_outer_commit
outer_transaction_rollback_after_dbOnly_create_does_not_run_actions
```

For rollback test:

1. Use `database.withTransaction`.
2. Call `createExpenseDbOnlyV2`.
3. Throw controlled exception before transaction returns.
4. Assert:
   - expense row missing,
   - fake runner not called.

---

## PR D acceptance criteria

- Active create implementation has no `SideEffectMode` parameter.
- DB-only create never runs side effects.
- Standalone create runs side effects only after DB create returns success.
- Static test prevents future `SideEffectMode` usage.
- No `@Suppress("DEPRECATION_ERROR")` remains for this flow.

---

# PR E — Duplicate visibility + duplicate budget regression tests

## Fixes

- P2-11 residual
- P2-12 regression coverage

## Current problem

Duplicate visibility is mostly fixed because review approval now routes through coordinator. However, residual risks remain:

- strict/external audit mismatch and insert-race resolution should be covered by the P2-P1-02/P2-P1-03 plan,
- review duplicate path needs regression coverage,
- duplicate creates must not trigger budget side effects,
- duplicate review approvals must not double-decrement/increment budget stats through old direct paths.

## Goal

Add tests proving duplicate outcomes are visible and do not run creation side effects.

If P2-P1-02/P2-P1-03 are not fixed yet, this PR should depend on them. Do not duplicate dedupe implementation here unless necessary.

## Files to modify

Tests:

```text
TransactionLifecycleCoordinatorDuplicateVisibilityTest.kt
ReviewQueueDuplicateApprovalRegressionTest.kt
TransactionLifecycleCoordinatorDuplicateSideEffectTest.kt
```

Possible production files if tests fail:

```text
TransactionLifecycleCoordinator.kt
ReviewQueueRepository.kt
TransactionSideEffectPlanner.kt
PostCommitActionRunnerImpl.kt
```

---

## Step E1 — Coordinator duplicate event tests

Tests:

```text
standard_duplicate_returns_DuplicateSkipped
standard_duplicate_writes_CREATE_DUPLICATE_SKIPPED
standard_duplicate_event_contains_duplicateExpenseId_when_known
standard_duplicate_event_contains_correlationId
duplicate_create_does_not_write_CREATED_event
duplicate_create_does_not_run_budget_side_effect
```

Implementation:

1. Create an expense with request A.
2. Create same request A again.
3. Assert result is `DuplicateSkipped`.
4. Assert transaction events contain:
   - first attempt: `CREATE_ATTEMPTED`, `CREATED`,
   - second attempt: `CREATE_ATTEMPTED`, `CREATE_DUPLICATE_SKIPPED`,
   - second attempt does **not** contain `CREATED`.
5. Use fake `PostCommitActionRunner` or fake `BudgetMonitor`.
6. Assert budget side effect ran once for first create and zero times for duplicate.

If runner diagnostics make counting hard, inject fake runner in unit test and count batches.

---

## Step E2 — Review duplicate approval test

Test:

```text
review_duplicate_approval_routes_through_coordinator_and_writes_duplicate_event
```

Arrange:

1. Insert existing expense matching review.
2. Insert pending review with same merchant/amount/date/currency/type.
3. Approve review.
4. Assert:
   - no new expense inserted,
   - review marked duplicate or equivalent outcome,
   - `CREATE_DUPLICATE_SKIPPED` event exists,
   - event metadata contains review/source info if source-link PR is done,
   - no budget creation side effect ran.

If current review approval still has precheck code, it must still call coordinator or explicitly write equivalent duplicate lifecycle event. Prefer coordinator route.

---

## Step E3 — Strict/external residual tests

If P2-P1-03 implementation has landed, add regression tests here too:

```text
strict_external_retry_writes_duplicate_not_insert_conflict
strict_external_retry_does_not_run_budget_side_effect
strict_external_retry_duplicate_event_has_existing_id
```

If P2-P1-03 has not landed, mark these tests pending in the plan and implement in the dedupe PR instead.

---

## Step E4 — Insert-race visibility tests

If test infrastructure supports concurrent inserts, add:

```text
concurrent_duplicate_create_one_created_one_duplicate
concurrent_duplicate_create_no_unresolved_conflict_when_existing_id_resolvable
concurrent_duplicate_create_budget_side_effect_runs_once
```

Use `runTest` and two coroutines hitting the same request. If Room in-memory DB serializes too much, directly simulate `insertAtomic` conflict with pre-inserted row and assert resolver behavior.

This likely belongs in P2-P1-03 dedupe PR.

---

## Step E5 — Duplicate budget-check regression tests

Tests:

```text
duplicate_manual_create_does_not_dispatch_budget_check
duplicate_review_approval_does_not_dispatch_budget_check
duplicate_strict_external_retry_does_not_dispatch_budget_check
validation_failed_create_does_not_dispatch_budget_check
insert_conflict_unresolved_does_not_dispatch_budget_check
```

Expected:

- only `CreateExpenseResult.Created` plans/runs `planCreated`.
- `DuplicateSkipped`, `ValidationFailed`, `InsertConflict`, `Error` return empty post-commit batch.

If current `createExpenseDbOnlyV2()` builds batches only for `Created`, this should pass; keep as regression.

---

## Step E6 — Static guard for old duplicate budget code

Add architecture test or grep test:

```text
No ReviewQueueRepository duplicate approval path may call BudgetMonitor directly.
No repository create duplicate path may dispatch budget side effects directly.
```

Skeleton:

```kotlin
@Test
fun review_queue_repository_does_not_directly_call_budget_monitor() {
    val file = Path.of("src/main/java/.../ReviewQueueRepository.kt").readText()
    assertFalse(file.contains("budgetMonitor.checkBudgets"))
    assertFalse(file.contains("BudgetMonitor"))
}
```

Adjust to real code. If `BudgetMonitor` is injected for other reasons, narrow the check to duplicate/approve methods or skip this static test.

---

## PR E acceptance criteria

- Duplicate create is visible in `transaction_events`.
- Duplicate review approval is visible.
- Duplicate creates do not write `CREATED`.
- Duplicate creates do not trigger budget side effects.
- Validation failures and insert conflicts do not trigger budget side effects.
- Strict/external duplicate visibility is tested once P2-P1-03 lands.

---

# Cross-PR dependency notes

## P2-11 overlaps P2-P1-02/P2-P1-03

Do not implement dedupe conflict resolution twice.

If the dedicated P2-P1-02/P2-P1-03 PR has already landed, PR E should only add regression tests.

If not landed, sequence should be:

1. P2-P1-02/P2-P1-03 diagnostics/dedup PR.
2. PR E duplicate visibility/budget regression tests.

## P2-06 and P2-07 interaction

If PR A lands before PR B:

- keep old `planBulkUpdated(source, affectedCount, correlationId)` call,
- add TODO to pass `changedFields`.

If PR B lands before PR A:

- update group hard-delete call immediately with:
  ```kotlin
  changedFields = setOf(BulkChangedField.OWNERSHIP, BulkChangedField.AMOUNT_EFFECTIVE)
  ```

## P2-09 depends on actual delete code

Before writing tests, agent must locate the current delete implementation. At commit `ad91767`, `ExpenseRepository.deleteExpense(expense)` delegates to `transactionLifecycleCoordinator.deleteExpense(expense).getOrThrow()`, but the coordinator delete method was not easy to inspect through static web rendering. The agent must inspect locally.

Required local search:

```bash
grep -R "fun deleteExpense" app/src/main/java
grep -R "LifecycleEventType.DELETED" app/src/main/java
grep -R "expenseDao.delete" app/src/main/java
```

If no coordinator delete method exists locally, compilation would already fail; then fix by implementing coordinator delete before PR C tests.

---

# Final recommended execution order

1. **PR D — Deferred side-effect API hardening**
   - Reduces risk for all later transaction work.
2. **PR B — Bulk side-effect expansion**
   - Gives group hard-delete a richer post-commit action target.
3. **PR A — Group hard-delete lifecycle contract**
   - Uses richer bulk planner if available.
4. **PR C — Delete semantics regression suite**
   - Documents and protects delete behavior.
5. **PR E — Duplicate visibility + duplicate budget regression tests**
   - Best done after P2-P1-02/P2-P1-03 diagnostics/dedup PR.

If you want fewer PRs:

- Merge PR B + PR A.
- Keep PR D separate.
- Merge PR C + PR E as a regression-test PR.

---

# Global validation commands

Run after each PR:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*GroupTransactionCoordinator*'
./gradlew testDebugUnitTest --tests '*TransactionSideEffectPlanner*'
./gradlew testDebugUnitTest --tests '*DeleteSemantics*'
./gradlew testDebugUnitTest --tests '*SideEffectModeUsageTest*'
./gradlew testDebugUnitTest --tests '*Duplicate*'
```

Manual grep checks:

```bash
grep -R "SideEffectMode" app/src/main/java
grep -R "@Suppress(\"DEPRECATION_ERROR\")" app/src/main/java
grep -R "planBulkUpdated(" app/src/main/java
grep -R "permanentlyDeleteGroup(" app/src/main/java
grep -R "LifecycleEventType.GROUP_" app/src/main/java
```

Expected final state:

- no production `SideEffectMode` use except possibly `SideEffectMode.kt`,
- no `DEPRECATION_ERROR` suppression for create side-effect mode,
- all `planBulkUpdated` callsites pass changed fields or rely on default `UNKNOWN`,
- permanent group delete requires confirmation,
- group lifecycle events exist,
- duplicate paths are visible and do not trigger budget side effects.

---

# Definition of done by issue

## P2-06 done when

- Permanent group delete requires explicit confirmation.
- Permanent group delete is blocked for active groups.
- Permanent group delete validates outstanding/unsettled expenses.
- Permanent group delete validates current-user membership policy.
- Successful hard delete writes `GROUP_PERMANENTLY_DELETED`.
- Archive writes `GROUP_ARCHIVED`.
- Shared flag cleanup remains atomic and audited.
- Post-commit bulk side effects run once after successful commit.

## P2-07 done when

- `planBulkUpdated()` accepts changed fields.
- Bulk category/merchant/group cleanup pass correct changed fields.
- Bulk planner includes budget, anomaly, cache invalidation, merchant learning dirty markers, and recurring reconciliation when appropriate.
- Zero affected rows do no expensive work.
- All action metadata is privacy-safe.

## P2-09 done when

- Tests prove transaction events survive expense delete.
- Tests prove `DELETED` snapshot semantics.
- Tests prove group link behavior on expense delete.
- Tests prove receipt link behavior on expense delete.
- Tests prove recurring unlink behavior.
- Tests prove group archive vs hard delete semantics.

## P2-10 done when

- Active create implementation no longer accepts `SideEffectMode`.
- DB-only create never dispatches side effects.
- Standalone create dispatches after commit only.
- Static test prevents `SideEffectMode` and `DEPRECATION_ERROR` suppression from returning.
- Outer transaction rollback does not run side effects.

## P2-11 done when

- Duplicate create writes `CREATE_DUPLICATE_SKIPPED`.
- Duplicate review approval writes duplicate lifecycle event.
- Strict/external duplicate retry writes duplicate event, not noisy unresolved conflict, once P2-P1-03 lands.
- Duplicate events include correlation ID and existing ID where known.

## P2-12 done when

- Duplicate create paths do not trigger budget check.
- Validation failed, duplicate skipped, insert conflict, and error outcomes return empty post-commit batches.
- Review duplicate approval does not call budget monitor directly.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `GroupTransactionCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
- `domain/groups/GroupTransactionCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt
- `TransactionSideEffectPlanner.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- `TransactionLifecycleCoordinator.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `ExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- `GroupExpense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt
- `ReceiptExpenseLink.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt
- `RecurringOccurrence.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt
- `TransactionEvent.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
- `LifecycleEventType.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt