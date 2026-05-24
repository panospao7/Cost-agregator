# Pipeline 2 remaining issues final implementation plan

Baseline: commit `4848d23522fe4ae69210e0e1f382693454ba583f`

Goal:

```text
Close the remaining Pipeline 2 gaps and make the pipeline ready for final clean/closed status.
```

Current remaining blockers:

1. `GroupLifecycleCoordinator` dispatches side effects inside DB transactions.
2. Permanent group delete lifecycle is incomplete.
3. Source provenance requirements are warnings, not validation failures.
4. Bulk changed-field model exists but callsites still use default `UNKNOWN`.
5. Bulk side-effect actions are partly disabled/no-op without explicit contract.
6. `RestrictedExpenseDaoMutation` is still `WARNING`, not `ERROR`.
7. `ExpenseRepository` has broad class-level DAO mutation opt-in.
8. Receipt legacy create path is disabled but still present; static guard needed or method should be deleted.
9. Delete/FK/orphan semantics are still a test gap.
10. Several “mostly fixed” items still need final regression/golden tests.

Recommended PR order:

1. PR 1 — Fix `GroupLifecycleCoordinator` post-commit side-effect regression.
2. PR 2 — Finish permanent group delete lifecycle contract.
3. PR 3 — Enforce source provenance requirements.
4. PR 4 — Finish bulk changed-field side-effect callsites and contracts.
5. PR 5 — Harden `ExpenseDao` static/compile guard.
6. PR 6 — Remove/guard receipt legacy create path.
7. PR 7 — Delete/FK/orphan regression suite.
8. PR 8 — Final Pipeline 2 golden/regression test pass.

---

# PR 1 — Fix GroupLifecycleCoordinator post-commit side-effect regression

## Fixes

- P2-10 residual
- P2-NEW-13 regression risk
- Post-commit side-effect invariant

## Problem

`GroupLifecycleCoordinator.emitLifecycleEvent(...)` currently writes lifecycle events and then dispatches side effects:

```kotlin
budgetMonitor.get().checkBudgets()
sideEffectDispatcher.dispatchOnCreated(...)
```

Some callers invoke this inside:

```kotlin
database.withTransaction {
    emitLifecycleEvent(...)
}
```

This violates the Pipeline 2 invariant:

```text
DB transaction commits first.
Side effects run after commit.
Outer transaction owner dispatches side effects.
```

## Goal

No budget checks, side-effect dispatcher calls, anomaly work, recurring work, or cache invalidation may run inside a Room transaction.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinator.kt
```

Possibly:

```text
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionBatch.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinatorSideEffectTest.kt
```

---

## Step 1 — Split lifecycle event write from side-effect dispatch

Replace one method that both writes and dispatches:

```kotlin
private suspend fun emitLifecycleEvent(...)
```

with two methods:

```kotlin
private suspend fun writeGroupLifecycleEvent(...)
private suspend fun dispatchGroupLifecycleSideEffectsAfterCommit(...)
```

Expected shape:

```kotlin
private suspend fun writeGroupLifecycleEvent(
    eventType: LifecycleEventType,
    groupId: Long,
    source: String,
    correlationId: String,
    reason: String,
    metadata: SafeEventMetadata = SafeEventMetadata.empty()
) {
    transactionLifecycleEventWriter.write(
        TransactionLifecycleEvent(
            expenseId = null,
            eventType = eventType.name,
            source = source,
            actor = "system:group_lifecycle_coordinator",
            correlationId = correlationId,
            metadata = metadata,
            reason = reason
        )
    )
}
```

Then:

```kotlin
private suspend fun dispatchGroupLifecycleSideEffectsAfterCommit(
    groupId: Long,
    eventType: LifecycleEventType,
    source: String,
    correlationId: String
) {
    try {
        when (eventType) {
            LifecycleEventType.GROUP_ARCHIVED,
            LifecycleEventType.GROUP_PERMANENTLY_DELETED -> {
                budgetMonitor.get().checkBudgets()
                sideEffectDispatcher.dispatchOnBulkUpdated(
                    source = source,
                    affectedCount = 1,
                    changedFields = setOf(BulkChangedField.OWNERSHIP)
                )
            }
            else -> Unit
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Group lifecycle side effects failed after commit for group=%d", groupId)
    }
}
```

Adjust names to actual APIs.

## Step 2 — Refactor transaction methods

Bad current pattern:

```kotlin
database.withTransaction {
    ...
    emitLifecycleEvent(...)
}
```

Good pattern:

```kotlin
val correlationId = CorrelationIds.newId()
var shouldDispatch = false

database.withTransaction {
    ...
    writeGroupLifecycleEvent(...)
    shouldDispatch = true
}

if (shouldDispatch) {
    dispatchGroupLifecycleSideEffectsAfterCommit(...)
}
```

Rules:

- Side effects run after `withTransaction` returns.
- If transaction throws, side effects do not run.
- `CancellationException` is rethrown.
- Event write remains inside transaction if it is part of the mutation audit.

## Step 3 — Add transaction rollback test

Required tests:

```text
archive_group_writes_event_inside_transaction_but_dispatches_side_effects_after_commit
archive_group_transaction_failure_does_not_run_side_effects
permanent_delete_transaction_failure_does_not_run_side_effects
group_lifecycle_side_effect_failure_does_not_rollback_committed_transaction
group_lifecycle_side_effect_cancellation_is_rethrown
```

Implementation hint:

- Use fake event writer that can throw.
- Use fake side-effect dispatcher/budget monitor with call counters.
- Assert side-effect counter is zero when transaction fails.

## Acceptance

- No `budgetMonitor` or `sideEffectDispatcher` calls occur inside `database.withTransaction`.
- Group lifecycle event writes remain atomic with group mutation.
- Side effects run only after commit.
- Tests prove rollback does not dispatch side effects.

---

# PR 2 — Finish permanent group delete lifecycle contract

## Fixes

- P2-06
- P2-NEW-13

## Problem

`GroupLifecycleCoordinator` exists, but permanent delete is still incomplete:

- returns `Boolean` in some paths,
- does not consistently use `PermanentGroupDeleteResult`,
- does not fully validate outstanding balances,
- does not fully validate current-user membership,
- delegates to low-level `GroupTransactionCoordinator.permanentlyDeleteGroup(groupId)`,
- low-level hard delete still carries TODOs and lifecycle ambiguity.

## Goal

Permanent group delete must be explicit, typed, validated, audited, and post-commit safe.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/PermanentGroupDeleteResult.kt
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinatorPermanentDeleteTest.kt
```

---

## Step 1 — Use typed result everywhere

Public permanent delete API should be:

```kotlin
suspend fun permanentlyDeleteGroup(
    groupId: Long,
    confirmPermanentDelete: Boolean
): PermanentGroupDeleteResult
```

Do not expose final public Boolean hard-delete API.

If old method must remain:

```kotlin
@Deprecated(
    message = "Use GroupLifecycleCoordinator.permanentlyDeleteGroup(groupId, confirmPermanentDelete).",
    level = DeprecationLevel.ERROR
)
suspend fun permanentlyDeleteGroup(groupId: Long): Boolean
```

## Step 2 — Required result model

Ensure `PermanentGroupDeleteResult` contains:

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

Use `object` instead of `data object` if Kotlin version requires.

## Step 3 — Add/verify lifecycle event types

In `LifecycleEventType.kt`:

```kotlin
GROUP_PERMANENT_DELETE_ATTEMPTED
GROUP_PERMANENT_DELETE_BLOCKED
GROUP_PERMANENTLY_DELETED
GROUP_ARCHIVED
```

## Step 4 — Add DAO validation helpers

In `GroupExpenseDao.kt`, adapt to actual schema:

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

If schema differs, inspect `GroupExpense` and implement equivalent “unsettled/outstanding” check.

In `GroupMemberDao.kt`:

```kotlin
@Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isCurrentUser = 1")
suspend fun countCurrentUsers(groupId: Long): Int
```

## Step 5 — Implement permanent delete flow

Inside `GroupLifecycleCoordinator`:

```kotlin
suspend fun permanentlyDeleteGroup(
    groupId: Long,
    confirmPermanentDelete: Boolean
): PermanentGroupDeleteResult {
    val correlationId = CorrelationIds.newId()

    return try {
        writeBarrier.checkWritesAllowed("GroupLifecycleCoordinator.permanentlyDeleteGroup")

        writeGroupLifecycleEventBestEffort(
            eventType = LifecycleEventType.GROUP_PERMANENT_DELETE_ATTEMPTED,
            groupId = groupId,
            correlationId = correlationId,
            reason = "Permanent group delete attempted"
        )

        if (!confirmPermanentDelete) {
            writeBlockedEvent(groupId, correlationId, "confirmation_required")
            return PermanentGroupDeleteResult.ConfirmationRequired
        }

        val group = groupDao.getById(groupId)
            ?: return PermanentGroupDeleteResult.GroupNotFound

        if (group.isActive) {
            writeBlockedEvent(groupId, correlationId, "group_still_active")
            return PermanentGroupDeleteResult.GroupStillActive
        }

        val outstanding = groupExpenseDao.countOutstandingReimbursableExpenses(groupId)
        if (outstanding > 0) {
            writeBlockedEvent(groupId, correlationId, "outstanding_balances", outstanding)
            return PermanentGroupDeleteResult.OutstandingBalancesExist(groupId, outstanding)
        }

        val currentUsers = memberDao.countCurrentUsers(groupId)
        if (currentUsers > 0) {
            writeBlockedEvent(groupId, correlationId, "current_user_membership_exists", currentUsers)
            return PermanentGroupDeleteResult.CurrentUserMembershipExists(groupId, currentUsers)
        }

        val linkedCount = groupCoordinator.deleteGroupAtomic(
            groupId = groupId,
            correlationId = correlationId
        )

        dispatchGroupLifecycleSideEffectsAfterCommit(
            groupId = groupId,
            eventType = LifecycleEventType.GROUP_PERMANENTLY_DELETED,
            source = "GROUP_PERMANENT_DELETE",
            correlationId = correlationId
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

## Step 6 — Refactor low-level atomic delete

In `GroupTransactionCoordinator`:

```kotlin
suspend fun deleteGroupAtomic(
    groupId: Long,
    correlationId: String
): Int
```

Inside one `database.withTransaction`:

```text
1. collect linked expense IDs
2. delete group expenses
3. delete members
4. delete group
5. clear shared expense flags for linked expense IDs
6. write GROUP_PERMANENTLY_DELETED
7. write BULK_UPDATED for shared flag cleanup if linked count > 0
```

Return linked expense count.

Do not dispatch side effects inside this method unless it happens after transaction returns.

## Step 7 — Required tests

```text
permanent_delete_without_confirmation_returns_ConfirmationRequired
permanent_delete_without_confirmation_writes_GROUP_PERMANENT_DELETE_BLOCKED
permanent_delete_missing_group_returns_GroupNotFound
permanent_delete_active_group_returns_GroupStillActive
permanent_delete_outstanding_balances_returns_OutstandingBalancesExist
permanent_delete_current_user_membership_returns_CurrentUserMembershipExists
permanent_delete_archived_confirmed_deletes_group
permanent_delete_archived_confirmed_deletes_members
permanent_delete_archived_confirmed_deletes_group_expenses
permanent_delete_archived_confirmed_clears_linked_expense_shared_flags
permanent_delete_archived_confirmed_writes_GROUP_PERMANENTLY_DELETED
permanent_delete_archived_confirmed_writes_BULK_UPDATED_for_linked_expenses
permanent_delete_audit_insert_failure_rolls_back_delete
permanent_delete_runs_side_effects_after_commit_only
direct_low_level_permanent_delete_boolean_api_is_forbidden
```

## Acceptance

- Permanent delete uses `PermanentGroupDeleteResult`.
- Hard delete requires explicit confirmation.
- Active/outstanding/current-user cases are blocked.
- Successful hard delete is audited atomically.
- Side effects run after commit only.

---

# PR 3 — Enforce source provenance requirements

## Fixes

- P2-NEW-17

## Problem

`SourceLinkFallbackPolicy` and `CreateExpenseSourceLinkRequirements` exist, but missing required provenance fields currently only produce a `Timber.w(...)`.

Runtime source-specific creates can still succeed without concrete source links.

## Goal

For runtime source-specific creates, missing provenance must be a validation failure.

Only explicit legacy/backfill mode may bypass.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt
app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkFallbackPolicy.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirementsTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorSourceProvenanceTest.kt
```

---

## Step 1 — Verify requirements model

`CreateExpenseSourceLinkRequirements.missingRequirements(request)` should return missing fields for:

```text
REVIEW_APPROVAL -> pendingReviewId
RECEIPT_SCAN -> scannedReceiptId
GROUP_EXPENSE -> groupId
CSV_IMPORT -> csvImportBatchId + csvRowNumber
EMAIL_RECEIPT -> emailReceiptSourceId
NOTIFICATION -> rawNotificationId
BANK_SYNC -> bankSyncRunId or equivalent bank source ID
```

Manual user-created expenses should not require external provenance.

## Step 2 — Convert warning to validation failure

In create validation flow, replace:

```kotlin
if (missingSourceFields.isNotEmpty()) {
    Timber.w("Missing source provenance fields...")
}
```

with:

```kotlin
if (
    request.sourceLinkFallbackPolicy != SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY
) {
    val missing = CreateExpenseSourceLinkRequirements.missingRequirements(request)
    if (missing.isNotEmpty()) {
        errors += "Missing source provenance for ${request.source}: ${missing.joinToString(",")}"
    }
}
```

If validation returns structured errors, use code:

```text
SOURCE_PROVENANCE_REQUIRED
```

Metadata must include only field names, not raw source values.

## Step 3 — Preserve legacy/backfill escape hatch

If:

```kotlin
request.sourceLinkFallbackPolicy == SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY
```

then missing source fields are allowed and mapper may emit `LEGACY_SOURCE_ONLY`.

This policy should be allowed only in migration/backfill/debug/test code.

## Step 4 — Audit all create request callsites

Run:

```bash
grep -R "CreateExpenseRequest(" app/src/main/java
```

For each source-specific callsite, ensure concrete fields are passed:

| Source | Required |
|---|---|
| review | `pendingReviewId` |
| receipt | `scannedReceiptId` |
| group | `groupId` |
| CSV/import | batch ID + row number |
| email | email source ID |
| notification | raw notification ID |
| bank | bank sync/source ID |
| manual | no external source required |

## Step 5 — Static guard for legacy fallback

Create/verify `SourceLinkFallbackPolicyGuardTest`.

Rules:

```text
SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY may appear only in:
- migration package
- backfill package
- debug fixtures
- tests
```

Fail if normal repositories/workers use it.

## Required tests

```text
review_approval_missing_pendingReviewId_validation_fails
receipt_scan_missing_scannedReceiptId_validation_fails
group_expense_missing_groupId_validation_fails
csv_import_missing_batch_or_row_validation_fails
email_receipt_missing_sourceId_validation_fails
notification_missing_rawNotificationId_validation_fails
manual_entry_without_source_link_is_allowed
legacy_backfill_policy_allows_missing_source_fields
runtime_create_missing_source_provenance_does_not_insert_expense
runtime_create_missing_source_provenance_writes_CREATE_VALIDATION_FAILED
runtime_create_with_source_fields_writes_concrete_source_link
runtime_create_does_not_write_LEGACY_SOURCE_ONLY
```

## Acceptance

- Source-specific runtime creates cannot silently lack provenance.
- `LEGACY_SOURCE_ONLY` is explicit backfill-only.
- All `CreateExpenseRequest` callsites pass required IDs.

---

# PR 4 — Finish bulk changed-field callsites and side-effect contract

## Fixes

- P2-07
- P2-NEW-19

## Problem

`BulkChangedField` exists and `planBulkUpdated(...)` accepts `changedFields`, but callsites still use wrappers/defaults that pass `UNKNOWN`.

Several actions are disabled/no-op without clear contract.

## Goal

Bulk callsites must pass meaningful changed fields, and disabled/no-op actions must be explicit and tested.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlannerBulkTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/BulkUpdateChangedFieldCallsiteTest.kt
```

---

## Step 1 — Update helper signature

If helper exists:

```kotlin
private suspend fun dispatchBulkPostCommitSideEffects(
    source: String,
    affectedCount: Int
)
```

change to:

```kotlin
private suspend fun dispatchBulkPostCommitSideEffects(
    source: String,
    affectedCount: Int,
    correlationId: String?,
    changedFields: Set<BulkChangedField>
)
```

Implementation:

```kotlin
val batch = planner.planBulkUpdated(
    source = source,
    affectedCount = affectedCount,
    correlationId = correlationId,
    changedFields = changedFields
)

runner.runBestEffortAfterCommit(
    batch = batch,
    logMessage = "Non-critical: bulk side effects failed",
    targetId = null
)
```

## Step 2 — Update category bulk callsite

For category-to-category reassignment:

```kotlin
dispatchBulkPostCommitSideEffects(
    source = source,
    affectedCount = affectedCount,
    correlationId = correlationId,
    changedFields = setOf(BulkChangedField.CATEGORY)
)
```

## Step 3 — Update merchant bulk callsite

For merchant update:

```kotlin
changedFields = setOf(
    BulkChangedField.MERCHANT,
    BulkChangedField.MERCHANT_KEY
)
```

## Step 4 — Update group cleanup callsite

For shared flag cleanup after group delete:

```kotlin
changedFields = setOf(
    BulkChangedField.OWNERSHIP,
    BulkChangedField.AMOUNT_EFFECTIVE
)
```

## Step 5 — Clarify disabled/no-op actions

Current planner may return outcomes like:

```text
DISABLED_BY_SETTINGS
NOT_APPLICABLE
```

This is acceptable only if explicit and tested.

Preferred options:

### Option A — Real dirty marker

Add minimal interfaces:

```kotlin
interface AnalyticsCacheInvalidator {
    suspend fun invalidateForExpenseBulkMutation(
        source: String,
        affectedCount: Int,
        changedFields: Set<String>
    )
}
```

```kotlin
interface MerchantLearningDirtyMarker {
    suspend fun markMerchantCategoryPatternsDirty(reason: String)
    suspend fun markCanonicalMerchantStatsDirty(reason: String)
}
```

```kotlin
interface RecurringBulkReconciler {
    suspend fun markRecurringMatchesDirtyForBulkExpenseChange(reason: String)
}
```

Bind no-op implementations if subsystem does not yet need work:

```kotlin
class NoOpAnalyticsCacheInvalidator @Inject constructor() : AnalyticsCacheInvalidator {
    override suspend fun invalidateForExpenseBulkMutation(...) = Unit
}
```

Then actions complete successfully.

### Option B — Explicit skipped contract

If no-op is intentional, define reason:

```kotlin
SideEffectSkipReason.NOT_CONFIGURED
```

or:

```kotlin
SideEffectSkipReason.NO_CACHE_PRESENT
```

Avoid vague `NOT_APPLICABLE` for planned future work.

## Step 6 — Tests

Required planner tests:

```text
planBulkUpdated_category_includes_budget_anomaly_cache_merchant_learning
planBulkUpdated_merchant_includes_anomaly_cache_merchant_learning_recurring
planBulkUpdated_ownership_includes_budget_anomaly_cache
planBulkUpdated_location_only_skips_budget_and_recurring
planBulkUpdated_unknown_includes_global_invalidations
planBulkUpdated_zero_count_returns_empty_or_explicit_skipped_batch
bulk_action_metadata_contains_changedFields
bulk_action_metadata_is_privacy_safe
no_bulk_action_uses_vague_NOT_APPLICABLE_placeholder
```

Required callsite tests:

```text
bulk_category_reassignment_passes_CATEGORY_changed_field
bulk_merchant_update_passes_MERCHANT_and_MERCHANT_KEY
group_shared_flag_cleanup_passes_OWNERSHIP_and_AMOUNT_EFFECTIVE
```

## Acceptance

- Bulk updates are field-aware at callsites.
- No important callsite silently defaults to `UNKNOWN`.
- Disabled/no-op side effects are explicit and tested.
- Bulk actions remain aggregate, not per-expense loops.

---

# PR 5 — Harden ExpenseDao mutation guard

## Fixes

- P2-P1-05
- P2-NEW-20

## Problem

`RestrictedExpenseDaoMutation` exists but is still warning-level, and `ExpenseRepository` uses broad class-level opt-in.

This is too weak for the “no unapproved direct DAO mutation” invariant.

## Goal

Unapproved direct expense mutations fail compile or architecture tests.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt
```

---

## Step 1 — Change opt-in to ERROR

In `RestrictedExpenseDaoMutation.kt`:

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct ExpenseDao mutation is restricted. Route through TransactionLifecycleCoordinator or add a reviewed write-barrier-protected allowlist exception."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class RestrictedExpenseDaoMutation
```

## Step 2 — Update architecture test

Rename/replace current test:

```text
restricted_annotation_uses_warning_or_error_level
```

with:

```text
restricted_annotation_uses_error_level
```

Test should fail if file contains:

```kotlin
RequiresOptIn.Level.WARNING
```

## Step 3 — Remove broad class-level opt-in from ExpenseRepository

Bad:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
class ExpenseRepository ...
```

Replace with function-level opt-in only on allowlisted methods:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun deleteAllExpenses() { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun restoreDebugSnapshot(...) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun incrementBackfillAttempts(...) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun conditionallySetLocation(...) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun clearExpenseLocation(...) { ... }

@OptIn(RestrictedExpenseDaoMutation::class)
suspend fun updateMerchantKey(...) { ... }
```

Each must have an allowlist comment immediately above:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: maintenance/backfill low-risk column update.
// Guard: DatabaseWriteBarrier.
// Audit: no lifecycle event by design.
```

For debug:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: debug-only aggregate destructive operation.
// Guard: BuildConfig.DEBUG + DatabaseWriteBarrier.
// Audit: aggregate TransactionEvent written in same transaction.
```

## Step 4 — Verify all mutating DAO methods are annotated

Mutating methods include:

```text
insert
insertAtomic
insertAll
update
delete
deleteAll
updateCategory
updateCategoryNullable
updateCategoryForMerchant
updateMerchantForMerchant
updateMerchant
updateMerchantAndKey
updateTransactionType
updateDedupeKey
updateTransferDirection
updateTransferAccountName
updateIsNotMine
updateOwnerName
updateIsSharedExpense
updateSharedWithName
updateMySharePercentage
updateMyShareAmount
clearSharedExpenseFlags
incrementBackfillAttempts
updateLocation
conditionallySetLocation
clearLocation
updateMerchantKey
updateCategoryForCategory
```

Add any missing.

## Step 5 — Strengthen architecture test

`ExpenseDaoMutationAccessTest` should enforce:

```text
no file-level opt-in
no broad class-level opt-in except TransactionLifecycleCoordinator
restricted opt-in only in allowlisted files
raw expenseDao mutation calls outside allowlisted files fail
every mutating ExpenseDao method is annotated
allowlisted bypasses have EXPENSE_DAO_MUTATION_ALLOWLIST comment
annotation level is ERROR
```

Add raw call regex:

```kotlin
val callRegex = Regex("""\bexpenseDao\s*\.\s*($methodRegex)\s*\(""")
```

Allowed files:

```text
ExpenseDao.kt
TransactionLifecycleCoordinator.kt
ExpenseRepository.kt
GroupTransactionCoordinator.kt
ReceiptLinkService.kt only if still unavoidable
migration/backfill files
```

## Step 6 — Compile

Run:

```bash
./gradlew compileDebugKotlin
```

For every new opt-in compile error:

1. Prefer route through `TransactionLifecycleCoordinator`.
2. If legitimate bypass, add function-level opt-in and allowlist comment.
3. Ensure write barrier or debug/migration guard.

## Acceptance

- Annotation is ERROR.
- No broad repository class-level opt-in.
- Static tests block unauthorized direct mutations.
- All approved bypasses are documented and guarded.

---

# PR 6 — Receipt legacy create static guard / deletion

## Fixes

- P2-NEW-16

## Problem

`ReceiptRepository.createExpenseFromReceipt()` is ERROR-deprecated and returns disabled error, but still exists in production source.

This may be acceptable short-term, but closure requires a guard or deletion.

## Goal

No production code can call or reintroduce the legacy receipt create path.

Preferred final state: method deleted.

Fallback: method remains disabled, but static guard ensures no production caller exists.

## Files to modify

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
app/src/test/java/com/yourname/expensetracker/architecture/ReceiptLegacyCreatePathGuardTest.kt
```

---

## Step 1 — Audit callers

Run:

```bash
grep -R "createExpenseFromReceipt" app/src/main/java app/src/test/java app/src/androidTest/java
```

If only declaration exists, delete method.

If callers exist, migrate them to `ReceiptLifecycleCoordinator`.

## Step 2 — Preferred: delete method

Remove:

```kotlin
createExpenseFromReceipt(...)
```

Remove unused imports.

## Step 3 — Fallback: keep disabled method and guard callers

If deletion is risky:

```kotlin
@Deprecated(
    message = "Use ReceiptLifecycleCoordinator. Legacy createExpenseFromReceipt is permanently disabled.",
    level = DeprecationLevel.ERROR
)
suspend fun createExpenseFromReceipt(...): ResultType {
    return ResultType.Error("Legacy receipt expense creation is disabled")
}
```

Do not call transaction lifecycle coordinator inside it.

## Step 4 — Static guard

Create:

```kotlin
class ReceiptLegacyCreatePathGuardTest {
    @Test
    fun no_production_callers_of_createExpenseFromReceipt() {
        val offenders = Files.walk(Path.of("src/main/java"))
            .filter { it.toString().endsWith(".kt") }
            .filter { file ->
                val text = file.readText()
                text.contains("createExpenseFromReceipt") &&
                    file.fileName.toString() != "ReceiptRepository.kt"
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Legacy receipt create path is forbidden:\n${offenders.joinToString("\n")}")
        }
    }
}
```

If method is deleted, remove `ReceiptRepository.kt` exception.

## Required tests

```text
no_production_callers_of_createExpenseFromReceipt
receipt_lifecycle_create_link_is_atomic
receipt_link_failure_rolls_back_expense
receipt_source_link_failure_rolls_back_expense
```

## Acceptance

- Legacy receipt create path is deleted or impossible to call.
- Static guard prevents production callsites.
- Receipt lifecycle coordinator owns receipt expense creation.

---

# PR 7 — Delete/FK/orphan regression suite

## Fixes

- P2-09

## Problem

Delete behavior is still a test gap.

## Goal

Explicitly test what happens to related tables and audit rows when expenses/groups are deleted.

## Files to add

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorDeleteSemanticsTest.kt
app/src/test/java/com/yourname/expensetracker/data/database/ExpenseDeleteReferentialIntegrityTest.kt
app/src/test/java/com/yourname/expensetracker/domain/groups/GroupDeleteSemanticsTest.kt
```

Modify production only if tests reveal broken behavior.

---

## Required expense delete contract

Test and enforce:

```text
expense row is deleted
transaction_events survive
DELETED event is written
DELETED.beforeSnapshot contains current row state
DELETED.afterSnapshot == null
group link behavior is explicit
receipt link behavior is explicit
recurring occurrence behavior is explicit
side effects run after commit only
```

## Required tests

```text
delete_expense_preserves_transaction_events
delete_expense_writes_DELETED_event
delete_expense_DELETED_event_has_current_beforeSnapshot
delete_expense_DELETED_event_has_null_afterSnapshot
delete_by_id_uses_latest_snapshot
entity_delete_overload_not_public_or_not_used
delete_expense_group_link_policy_is_explicit
delete_expense_receipt_link_policy_is_explicit
delete_expense_recurring_occurrence_unlinked_once
delete_expense_rollback_does_not_run_side_effects
delete_side_effect_failure_writes_SIDE_EFFECT_FAILED
```

## If receipt links orphan

Preferred fix:

Inside delete transaction:

```kotlin
receiptExpenseLinkDao.deleteLinksForExpense(expenseId)
```

Record safe metadata:

```json
{ "receiptLinksDeleted": 2 }
```

## If recurring link stale

Ensure post-commit or transactional cleanup unlinks exactly once:

```text
linkedExpenseId = null
```

Do not double-unlink.

## Group delete tests

```text
archive_group_preserves_members_and_group_expenses
permanent_group_delete_requires_lifecycle_coordinator
permanent_group_delete_deletes_members_and_group_expenses
permanent_group_delete_preserves_transaction_events
permanent_group_delete_clears_linked_expense_shared_flags
```

## Acceptance

- Delete/FK/orphan behavior is documented by tests.
- Audit rows survive.
- Stale related links are either cleaned or explicitly filtered/tested.

---

# PR 8 — Final golden/regression pass

## Fixes

- P2-P1-02 tests
- P2-P1-03 tests
- P2-P1-04 tests
- P2-11 tests
- P2-12 tests
- P2-NEW-15 tests
- Full closeout confidence

## Goal

Convert “mostly fixed” into “fixed + regression-covered.”

## Add final golden test class

```text
app/src/test/java/com/yourname/expensetracker/pipeline2/Pipeline2GoldenTest.kt
```

## Required golden tests

```text
golden_manual_create_writes_attempt_created_source_link
golden_manual_create_dispatches_side_effects_after_commit
golden_validation_failed_create_writes_failure_event_and_no_side_effects
golden_restore_blocked_create_writes_diagnostic_and_no_expense
golden_duplicate_create_writes_duplicate_event_and_no_side_effects
golden_strict_external_retry_returns_existing_id
golden_standard_insert_conflict_resolves_existing_id
golden_update_invalid_state_rejected_and_audited
golden_business_patch_unsupported_fields_rejected
golden_bulk_category_reassignment_atomic_one_event_one_side_effect
golden_review_approval_merchant_key_matches_auto_accept
golden_group_system_expense_create_link_atomic
golden_group_existing_expense_link_verifies_ownership
golden_group_permanent_delete_validated_and_audited
golden_receipt_legacy_create_path_absent_or_guarded
golden_runtime_source_specific_create_requires_provenance
golden_debug_snapshot_delete_restore_audited
golden_side_effect_failure_writes_SIDE_EFFECT_FAILED
golden_expenseDao_mutation_static_guard_passes
golden_no_production_SideEffectMode_usage
```

## Add targeted tests for manual persisted hook

```text
manual_recommendation_uses_persisted_expense
manual_recommendation_sees_baseAmount
manual_recommendation_sees_exchangeRateUsed
manual_recommendation_sees_merchantKey
manual_recommendation_sees_dedupeKey
manual_duplicate_does_not_generate_recommendations
manual_validation_failed_does_not_generate_recommendations
```

## Final static grep checklist

Run:

```bash
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "businessUsePercent.*ignored" app/src/main/java
grep -R "taxCategory.*ignored" app/src/main/java
grep -R "vatEligible.*ignored" app/src/main/java
grep -R "normalizedMerchantForKeys" app/src/main/java
grep -R "addDays(now, 1)" app/src/main/java
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "RequiresOptIn.Level.WARNING" app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
grep -R "@file:OptIn(RestrictedExpenseDaoMutation::class)" app/src/main/java
grep -R "SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY" app/src/main/java
grep -R "SideEffectMode" app/src/main/java
grep -R "@Suppress(\"DEPRECATION_ERROR\")" app/src/main/java
grep -R "TODO P2-" app/src/main/java
```

Expected:

```text
no direct restore mode checks in transaction coordinator
no ignored business/tax fields
no review double-normalization
no hardcoded future date tolerance in transaction validation
no production legacy receipt create path, or only disabled method with static guard
RestrictedExpenseDaoMutation uses ERROR
no file-level DAO mutation opt-in
LEGACY_BACKFILL_ONLY only in migration/backfill/debug/tests
no production SideEffectMode usage
no unresolved P2 TODOs
```

## Build/test commands

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*GroupLifecycleCoordinator*'
./gradlew testDebugUnitTest --tests '*SourceProvenance*'
./gradlew testDebugUnitTest --tests '*TransactionSideEffectPlannerBulkTest*'
./gradlew testDebugUnitTest --tests '*ExpenseDaoMutationAccessTest*'
./gradlew testDebugUnitTest --tests '*ReceiptLegacyCreatePathGuardTest*'
./gradlew testDebugUnitTest --tests '*DeleteSemantics*'
./gradlew testDebugUnitTest --tests '*Pipeline2GoldenTest*'
```

## Acceptance

Pipeline 2 can be marked clean only when:

1. All PRs pass compile and tests.
2. Side effects are never dispatched inside DB transactions.
3. Permanent group delete is typed, validated, and audited.
4. Source-specific runtime creates require concrete provenance.
5. Bulk side effects receive meaningful changed fields.
6. DAO mutation guard is ERROR-level and statically enforced.
7. Receipt legacy path is deleted or statically unreachable.
8. Delete/FK/orphan behavior is covered.
9. Golden tests pass.

---

# Final tracker update after completion

| Issue | Final status |
|---|---:|
| P2-P1-01 / P2-NEW-06 | Fixed + tests |
| P2-P1-02 | Fixed + tests |
| P2-P1-03 | Fixed + tests |
| P2-P1-04 | Fixed + tests |
| P2-P1-05 / P2-NEW-20 | Fixed + ERROR guard + architecture tests |
| P2-06 / P2-NEW-13 | Fixed |
| P2-07 / P2-NEW-19 | Fixed |
| P2-09 | Fixed by regression suite |
| P2-10 | Fixed + group lifecycle regression removed |
| P2-11 | Fixed + tests |
| P2-12 | Fixed + tests |
| P2-NEW-14 | Fixed + `SIDE_EFFECT_FAILED` tests |
| P2-NEW-15 | Fixed + regression tests |
| P2-NEW-16 | Fixed + static guard |
| P2-NEW-17 | Fixed + validation enforcement |
| P2-NEW-18 | Fixed + tests |