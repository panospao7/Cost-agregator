# Pipeline 2 finalization implementation plan

Baseline: current post-`1e24ddd` state from latest review.

Issues to close:

| Issue | Current status |
|---|---:|
| P2-P1-01 / P2-NEW-06 business/tax API | Open |
| P2-P1-02 failed-create diagnostics | Mostly fixed |
| P2-P1-03 strict/conflict dedupe | Mostly fixed |
| P2-P1-04 debug audit | Mostly fixed |
| P2-P1-05 DAO mutation guard | Partial |
| P2-06 group hard-delete lifecycle | Open |
| P2-07 bulk side effects | Partial |
| P2-09 delete/FK/orphan tests | Unknown/test gap |
| P2-10 deferred side-effect contract | Partial |
| P2-11 duplicate visibility | Mostly fixed, tests required |
| P2-12 duplicate budget checks | Mostly fixed, tests required |

Recommended final PR order:

1. PR 1 — Business/tax patch contract.
2. PR 2 — Diagnostics/dedup final regression suite.
3. PR 3 — Group hard-delete lifecycle coordinator.
4. PR 4 — Bulk side-effect completion.
5. PR 5 — Delete/FK/orphan regression suite.
6. PR 6 — Deferred side-effect API hardening.
7. PR 7 — DAO/static guards + debug-audit closeout.
8. PR 8 — Final Pipeline 2 golden test pass + tracker update.

---

# PR 1 — Business/tax patch contract

## Fixes

- P2-P1-01
- P2-NEW-06

## Goal

`updateBusinessFlags()` must stop accepting fields that are silently ignored.

Current risk:

```text
businessUsePercent
taxCategory
vatEligible
```

are accepted but not persisted.

## Files

Add:

```text
domain/transaction/BusinessExpensePatch.kt
domain/transaction/BusinessExpenseUpdateResult.kt
```

Modify:

```text
TransactionLifecycleCoordinator.kt
LifecycleEventType.kt
```

## Required implementation

### 1. Add patch model

```kotlin
data class BusinessExpensePatch(
    val isBusinessExpense: Boolean? = null,
    val requiresReceipt: Boolean? = null,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,

    val businessUsePercent: Double? = null,
    val taxCategory: String? = null,
    val vatEligible: Boolean? = null
) {
    fun unsupportedFields(): List<String> = buildList {
        if (businessUsePercent != null) add("businessUsePercent")
        if (taxCategory != null) add("taxCategory")
        if (vatEligible != null) add("vatEligible")
    }

    fun isEmpty(): Boolean =
        isBusinessExpense == null &&
        requiresReceipt == null &&
        businessPurpose == null &&
        businessCategory == null &&
        businessProject == null &&
        businessUsePercent == null &&
        taxCategory == null &&
        vatEligible == null
}
```

### 2. Add result model

```kotlin
sealed interface BusinessExpenseUpdateResult {
    data class Updated(
        val expenseId: Long,
        val changedFields: Set<String>
    ) : BusinessExpenseUpdateResult

    data object NoChange : BusinessExpenseUpdateResult
    data object NotFound : BusinessExpenseUpdateResult

    data class UnsupportedFields(
        val fields: List<String>
    ) : BusinessExpenseUpdateResult

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : BusinessExpenseUpdateResult
}
```

If Kotlin version does not support `data object`, use `object`.

### 3. Add event type

In `LifecycleEventType.kt`:

```kotlin
UPDATE_VALIDATION_FAILED
```

### 4. Add coordinator method

```kotlin
suspend fun updateBusinessExpensePatch(
    expenseId: Long,
    patch: BusinessExpensePatch,
    source: String = "BUSINESS_TAX_UPDATE",
    reason: String? = null,
    correlationId: String? = null
): BusinessExpenseUpdateResult
```

Rules:

1. Call write barrier first:

```kotlin
checkWritesAllowed("updateBusinessExpensePatch")
```

2. If patch empty:
   - return `NoChange`;
   - write no event;
   - run no side effects.

3. If unsupported fields exist:
   - do not mutate expense;
   - write `UPDATE_VALIDATION_FAILED`;
   - metadata only includes field names, not raw values;
   - return `UnsupportedFields`.

4. Load existing expense.
   - If missing, return `NotFound`.

5. Persist supported fields:

```kotlin
val updated = existing.copy(
    isBusinessExpense = patch.isBusinessExpense ?: existing.isBusinessExpense,
    requiresReceipt = patch.requiresReceipt ?: existing.requiresReceipt,
    businessPurpose = patch.businessPurpose ?: existing.businessPurpose,
    businessCategory = patch.businessCategory ?: existing.businessCategory,
    businessProject = patch.businessProject ?: existing.businessProject
)
```

6. Compute changed fields.
7. If no change, return `NoChange`.
8. In one DB transaction:
   - update expense,
   - write `UPDATED` event with changed field names.
9. After commit:
   - run update side effects.

### 5. Convert legacy method

Change old `updateBusinessFlags(...)` to delegate:

```kotlin
@Deprecated(
    message = "Use updateBusinessExpensePatch(). Unsupported tax fields are rejected.",
    replaceWith = ReplaceWith("updateBusinessExpensePatch(...)")
)
suspend fun updateBusinessFlags(...): BusinessExpenseUpdateResult
```

Remove any log message saying fields are ignored.

## Required tests

```text
business_patch_updates_isBusinessExpense
business_patch_updates_requiresReceipt
business_patch_updates_businessPurpose_businessCategory_businessProject
business_patch_same_values_returns_NoChange
business_patch_same_values_writes_no_UPDATED_event
business_patch_empty_returns_NoChange
business_patch_not_found_returns_NotFound
business_patch_businessUsePercent_returns_UnsupportedFields
business_patch_taxCategory_returns_UnsupportedFields
business_patch_vatEligible_returns_UnsupportedFields
business_patch_unsupported_fields_do_not_mutate_expense
business_patch_unsupported_fields_write_UPDATE_VALIDATION_FAILED
legacy_updateBusinessFlags_delegates_to_patch
legacy_updateBusinessFlags_unsupported_fields_are_rejected_not_ignored
business_patch_restore_mode_blocks
```

## Acceptance

- No business/tax field is silently dropped.
- Unsupported fields are rejected.
- Supported fields persist.
- No-op updates do not write fake `UPDATED`.
- Rejected updates do not run side effects.

---

# PR 2 — Diagnostics/dedup final regression suite

## Fixes

- P2-P1-02
- P2-P1-03
- P2-11
- P2-12

## Goal

Mostly implemented logic must be locked with tests so future changes do not regress.

## Files

Modify only if tests fail:

```text
TransactionLifecycleCoordinator.kt
TransactionValidator.kt
ExpenseDao.kt
CreateExpenseResult.kt
```

Tests:

```text
TransactionLifecycleCoordinatorCreateDiagnosticsTest.kt
TransactionLifecycleCoordinatorDedupTest.kt
TransactionLifecycleCoordinatorDuplicateSideEffectTest.kt
ReviewQueueDuplicateApprovalRegressionTest.kt
```

## Required verification

### Failed-create diagnostics

Ensure these outcomes are durable:

```text
restore blocked
validation failed
duplicate skipped
insert conflict unresolved
```

Each must have:

```text
correlationId
safe metadata
no raw merchant/notes/receipt payloads
```

### Strict external dedupe

Verify:

```text
CREATE_ATTEMPTED.dedupeKey == persisted Expense.dedupeKey
```

For strict external mode:

```text
idem:{source}:{idempotencyKey or externalFingerprint}
```

### Insert conflicts

Resolved conflicts must return duplicate:

```text
CreateExpenseResult.DuplicateSkipped(existingExpenseId)
```

Only unresolved conflicts should return:

```text
CreateExpenseResult.InsertConflict
```

### Duplicate side effects

Duplicates must not trigger create side effects:

```text
no budget creation check
no CREATED event
no source-link write for new expense
```

## Required tests

```text
restore_blocked_create_emits_TRANSACTION_BLOCKED_diagnostic
restore_blocked_create_does_not_insert_expense
restore_blocked_create_does_not_write_CREATED
validation_failed_create_writes_CREATE_VALIDATION_FAILED
validation_failed_create_emits_FAILED_FINAL_diagnostic
strict_external_attempt_event_uses_idem_key
strict_external_first_create_persists_same_idem_key
strict_external_retry_returns_existing_id
strict_external_retry_writes_CREATE_DUPLICATE_SKIPPED_not_CREATE_INSERT_CONFLICT
standard_insert_conflict_resolves_existing_id_when_possible
bulk_insert_conflict_resolves_existing_id_when_possible
unresolved_insert_conflict_writes_CREATE_INSERT_CONFLICT
duplicate_create_writes_CREATE_DUPLICATE_SKIPPED
duplicate_create_does_not_write_CREATED
duplicate_create_does_not_run_budget_side_effect
review_duplicate_approval_writes_CREATE_DUPLICATE_SKIPPED
review_duplicate_approval_does_not_run_budget_side_effect
validation_failed_create_does_not_run_budget_side_effect
insert_conflict_unresolved_does_not_run_budget_side_effect
```

## Acceptance

- P2-P1-02 and P2-P1-03 can be marked fixed only after tests pass.
- P2-11 and P2-12 become regression-covered.

---

# PR 3 — Group hard-delete lifecycle coordinator

## Fixes

- P2-06

## Goal

Permanent group delete must be explicit, validated, and audited.

## Files

Add:

```text
domain/groups/lifecycle/GroupLifecycleCoordinator.kt
domain/groups/lifecycle/PermanentGroupDeleteResult.kt
domain/groups/lifecycle/DefaultGroupLifecycleCoordinator.kt
```

Modify:

```text
GroupTransactionCoordinator.kt
domain/groups/GroupTransactionCoordinator.kt
GroupExpenseDao.kt
GroupMemberDao.kt
LifecycleEventType.kt
```

## Required event types

```kotlin
GROUP_ARCHIVED
GROUP_PERMANENT_DELETE_ATTEMPTED
GROUP_PERMANENT_DELETE_BLOCKED
GROUP_PERMANENTLY_DELETED
```

## Required result model

```kotlin
sealed interface PermanentGroupDeleteResult {
    data class Deleted(val groupId: Long, val linkedExpenseCount: Int) : PermanentGroupDeleteResult
    data object ConfirmationRequired : PermanentGroupDeleteResult
    data object GroupNotFound : PermanentGroupDeleteResult
    data object GroupStillActive : PermanentGroupDeleteResult
    data class OutstandingBalancesExist(val groupId: Long, val outstandingCount: Int) : PermanentGroupDeleteResult
    data class CurrentUserMembershipExists(val groupId: Long, val currentUserCount: Int) : PermanentGroupDeleteResult
    data class Error(val message: String, val causeClass: String? = null) : PermanentGroupDeleteResult
}
```

## Required policy

Permanent delete must:

1. check write barrier,
2. require `confirmPermanentDelete = true`,
3. require group exists,
4. require group is inactive/archived,
5. block outstanding/unsettled reimbursable balances,
6. block current-user membership unless product explicitly says otherwise,
7. write attempted/blocked/deleted events,
8. call low-level atomic delete only after validation.

## Low-level delete

`GroupTransactionCoordinator.deleteGroupAtomic(...)` should:

```kotlin
suspend fun deleteGroupAtomic(
    groupId: Long,
    correlationId: String
): Int
```

Inside same transaction:

- delete group expenses,
- delete members,
- delete group,
- clear linked expense shared flags,
- write `GROUP_PERMANENTLY_DELETED`,
- write `BULK_UPDATED` if linked expense flags changed.

After commit:

- dispatch one bulk side-effect batch.

## Required tests

```text
permanent_delete_without_confirmation_returns_ConfirmationRequired
permanent_delete_without_confirmation_writes_GROUP_PERMANENT_DELETE_BLOCKED
permanent_delete_active_group_returns_GroupStillActive
permanent_delete_outstanding_balances_returns_OutstandingBalancesExist
permanent_delete_current_user_membership_returns_CurrentUserMembershipExists
permanent_delete_missing_group_returns_GroupNotFound
permanent_delete_archived_confirmed_deletes_group_members_and_group_expenses
permanent_delete_archived_confirmed_clears_linked_expense_shared_flags
permanent_delete_archived_confirmed_writes_GROUP_PERMANENTLY_DELETED
permanent_delete_archived_confirmed_writes_BULK_UPDATED
permanent_delete_audit_insert_failure_rolls_back_delete
archive_group_writes_GROUP_ARCHIVED
archive_group_preserves_members_and_expenses
```

## Acceptance

- Hard delete is no longer a casual low-level Boolean method.
- Hard delete is lifecycle-owned, validated, and audited.
- Shared flag cleanup remains atomic.

---

# PR 4 — Bulk side-effect completion

## Fixes

- P2-07

## Goal

Bulk updates should not only check budgets. They need field-aware aggregate invalidation.

## Files

Modify:

```text
TransactionSideEffectPlanner.kt
TransactionSideEffectDispatcher.kt
TransactionLifecycleCoordinator.kt
GroupTransactionCoordinator.kt
```

May add:

```text
BulkChangedField.kt
AnalyticsCacheInvalidator.kt
ExpenseDerivedStateInvalidator.kt
MerchantLearningDirtyMarker.kt
RecurringBulkReconciler.kt
```

## Required semantics

`planBulkUpdated()` must accept:

```kotlin
changedFields: Set<BulkChangedField>
```

Known fields:

```kotlin
AMOUNT
AMOUNT_EFFECTIVE
CATEGORY
MERCHANT
MERCHANT_KEY
TRANSACTION_TYPE
DATE
CURRENCY
OWNERSHIP
TRANSFER
LOCATION
BUSINESS_FLAGS
UNKNOWN
```

## Required callsites

Category bulk:

```kotlin
changedFields = setOf(BulkChangedField.CATEGORY)
```

Merchant bulk:

```kotlin
changedFields = setOf(BulkChangedField.MERCHANT, BulkChangedField.MERCHANT_KEY)
```

Group shared-flag cleanup:

```kotlin
changedFields = setOf(BulkChangedField.OWNERSHIP, BulkChangedField.AMOUNT_EFFECTIVE)
```

Unknown fallback:

```kotlin
changedFields = setOf(BulkChangedField.UNKNOWN)
```

## Required actions

Planner should produce aggregate actions where relevant:

```text
bulk_budget_check
bulk_anomaly_invalidation
bulk_analytics_cache_invalidation
bulk_merchant_category_dirty
bulk_merchant_canonical_stats_dirty
bulk_recurring_reconciliation
```

If a subsystem does not exist, do not leave vague `NOT_APPLICABLE` TODOs. Instead:

- create an explicit no-op implementation, or
- document a capability flag, or
- write a `Skipped(NOT_CONFIGURED)` style reason with tests.

## Required tests

```text
planBulkUpdated_category_includes_budget_anomaly_cache_merchant_learning
planBulkUpdated_merchant_includes_anomaly_cache_merchant_learning_recurring
planBulkUpdated_ownership_includes_budget_anomaly_cache
planBulkUpdated_location_only_skips_budget_and_recurring
planBulkUpdated_unknown_includes_global_invalidations
planBulkUpdated_zero_count_returns_empty_or_skipped_batch
bulk_category_update_passes_CATEGORY
bulk_merchant_update_passes_MERCHANT_and_MERCHANT_KEY
group_cleanup_passes_OWNERSHIP_and_AMOUNT_EFFECTIVE
bulk_action_metadata_contains_changedFields
bulk_action_metadata_is_privacy_safe
```

## Acceptance

- Bulk planner is field-aware.
- Bulk side effects are aggregate, not N per expense.
- No ambiguous placeholder TODO remains.

---

# PR 5 — Delete/FK/orphan regression suite

## Fixes

- P2-09

## Goal

Make delete semantics explicit and tested.

## Files

Tests:

```text
TransactionLifecycleCoordinatorDeleteSemanticsTest.kt
ExpenseDeleteReferentialIntegrityTest.kt
GroupDeleteSemanticsTest.kt
```

Modify production only if tests fail.

## Contract to verify

Expense delete must ensure:

```text
expense row deleted
transaction_events survive
DELETED event written with current beforeSnapshot
DELETED.afterSnapshot == null
group link policy explicit
receipt link policy explicit
recurring occurrence unlink policy explicit
side effects after commit only
```

## Required tests

```text
delete_expense_preserves_transaction_events
delete_expense_writes_DELETED_event
delete_expense_DELETED_event_has_current_beforeSnapshot
delete_expense_DELETED_event_has_null_afterSnapshot
delete_expense_group_link_policy_is_explicit
delete_expense_receipt_link_policy_is_explicit
delete_expense_recurring_occurrence_unlinked_once
delete_expense_rollback_does_not_run_side_effects
delete_by_id_uses_latest_snapshot
entity_delete_overload_not_public_or_not_used
archive_group_preserves_members_and_group_expenses
permanent_group_delete_requires_lifecycle_coordinator
```

## If tests expose orphan receipt links

Preferred fix:

Inside coordinator delete transaction:

```kotlin
receiptExpenseLinkDao.deleteLinksForExpense(expenseId)
```

and include safe metadata:

```json
{ "receiptLinksDeleted": 2 }
```

## If tests expose recurring stale links

Ensure delete side-effect or DB cleanup unlinks:

```text
linkedExpenseId = null
status adjusted if needed
```

## Acceptance

- Delete behavior is fully documented by tests.
- Audit history survives.
- No stale screen-loaded entity delete path remains.

---

# PR 6 — Deferred side-effect API hardening

## Fixes

- P2-10

## Goal

No caller can accidentally dispatch side effects inside an outer transaction.

## Files

Modify:

```text
TransactionLifecycleCoordinator.kt
SideEffectMode.kt
```

Tests:

```text
TransactionLifecycleCoordinatorSideEffectContractTest.kt
SideEffectModeUsageTest.kt
```

## Required implementation

Preferred:

- remove active `createExpense(request, sideEffectMode)` implementation.

Minimum:

```kotlin
@Deprecated(
    message = "Use createExpenseStandaloneV2 or createExpenseDbOnlyV2.",
    level = DeprecationLevel.ERROR
)
```

No production source may call:

```kotlin
SideEffectMode.IMMEDIATE
SideEffectMode.DEFER
createExpense(request, sideEffectMode)
```

## Active APIs

DB-only:

```kotlin
suspend fun createExpenseDbOnlyV2(
    request: CreateExpenseRequest
): MutationResult<CreateExpenseResult>
```

Rules:

- performs DB mutation only,
- returns post-commit action batch,
- never runs runner.

Standalone:

```kotlin
suspend fun createExpenseStandaloneV2(
    request: CreateExpenseRequest
): CreateExpenseResult
```

Rules:

- calls DB-only,
- runs actions only after DB mutation succeeds,
- runs no actions for duplicate/validation/conflict/error.

## Static test

```text
SideEffectModeUsageTest
```

Rules:

- `SideEffectMode` may appear only in `SideEffectMode.kt` or migration/test compatibility.
- no `@Suppress("DEPRECATION_ERROR")` in production for this flow.
- no production call to mode-based create.

## Required tests

```text
createExpenseDbOnlyV2_does_not_run_side_effects
createExpenseStandaloneV2_runs_side_effects_after_commit
createExpenseStandaloneV2_does_not_run_side_effects_for_duplicate
createExpenseStandaloneV2_does_not_run_side_effects_for_validation_failed
outer_transaction_rollback_after_dbOnly_create_does_not_run_actions
no_production_SideEffectMode_usage
```

## Acceptance

- Side-effect dispatch is structurally separated from DB mutation.
- Static test prevents regression.

---

# PR 7 — DAO/static guards + debug-audit closeout

## Fixes

- P2-P1-04
- P2-P1-05

## Part A — DAO mutation guard

### Goal

Direct `ExpenseDao` mutations must be compile/static guarded.

### Files

```text
RestrictedExpenseDaoMutation.kt
ExpenseDao.kt
ExpenseDaoMutationAccessTest.kt
```

### Required change

Set annotation to hard error:

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Direct ExpenseDao mutation is restricted..."
)
```

### Annotate all mutating DAO methods

Examples:

```text
insert
insertAtomic
insertAll
update
delete
deleteAll
updateCategory...
clearSharedExpenseFlags
incrementBackfillAttempts
updateLocation
conditionallySetLocation
clearLocation
updateMerchantKey
updateCategoryForCategory
```

### Allowlist

Only approved files may opt in:

```text
TransactionLifecycleCoordinator.kt
ExpenseRepository.kt for debug/maintenance methods only
GroupTransactionCoordinator.kt for group cleanup only
ReceiptLinkService.kt only if unavoidable
migration/backfill classes
```

Repository opt-ins must be function-level, not class-level.

Each bypass needs comment:

```kotlin
// EXPENSE_DAO_MUTATION_ALLOWLIST:
// Reason: ...
// Guard: DatabaseWriteBarrier / BuildConfig.DEBUG / migration-only.
// Audit: ...
```

### Architecture tests

```text
no_file_level_restricted_expense_dao_opt_in
restricted_opt_in_only_in_allowlisted_files
no_raw_expenseDao_mutation_calls_outside_approved_files
every_mutating_expense_dao_method_is_restricted
approved_bypasses_have_ALLOWLIST_comment
```

## Part B — Debug audit closeout

If already implemented, add/verify tests only.

Required debug contract:

```text
deleteAllExpenses -> DEBUG_DELETE_ALL_EXPENSES event inside same transaction
restoreDebugSnapshot -> RESTORED_FROM_DEBUG_SNAPSHOT event inside same transaction
createDebugSnapshot -> diagnostic event, best effort
```

No raw expense rows in metadata.

## Required tests

```text
debug_delete_all_writes_DEBUG_DELETE_ALL_EXPENSES_event
debug_delete_all_event_has_affectedCount
debug_delete_all_audit_failure_rolls_back_delete
debug_restore_snapshot_writes_RESTORED_FROM_DEBUG_SNAPSHOT
debug_restore_snapshot_event_has_beforeCount_restoredCount
debug_restore_audit_failure_rolls_back_restore
debug_snapshot_create_emits_diagnostic
debug_snapshot_create_diagnostic_failure_does_not_fail_snapshot
debug_audit_metadata_contains_no_raw_expense_data
debug_methods_blocked_in_release_or_guarded
```

## Acceptance

- DAO guard is hard, not warning.
- Static guard prevents future bypasses.
- Debug operations are auditable and privacy-safe.

---

# PR 8 — Final Pipeline 2 golden test pass

## Goal

Prove Pipeline 2 is clean end-to-end.

## Required golden tests

```text
manual_create_writes_CREATE_ATTEMPTED_AND_CREATED
manual_create_dispatches_side_effects_after_commit
manual_create_validation_failed_writes_failure_event_and_no_side_effects
manual_create_duplicate_writes_duplicate_event_and_no_side_effects
strict_external_retry_returns_existing_id
standard_insert_race_resolves_existing_id_when_possible
update_rejects_invalid_final_state
update_valid_state_writes_UPDATED
business_patch_unsupported_fields_rejected
delete_preserves_audit_events
review_approval_created_expense_has_review_source_link
review_duplicate_approval_writes_duplicate_event
receipt_legacy_create_path_absent_or_guarded
group_system_expense_create_link_atomic
group_existing_expense_link_ownership_verified
group_hard_delete_lifecycle_validated
bulk_category_reassignment_atomic
bulk_side_effects_field_aware
debug_snapshot_delete_restore_audited
restore_mode_blocks_all_expense_mutations
expenseDao_mutation_static_guard_passes
no_production_SideEffectMode_usage
```

## Final grep checklist

```bash
grep -R "businessUsePercent.*ignored" app/src/main/java
grep -R "taxCategory.*ignored" app/src/main/java
grep -R "vatEligible.*ignored" app/src/main/java
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "SideEffectMode" app/src/main/java
grep -R "@Suppress(\"DEPRECATION_ERROR\")" app/src/main/java
grep -R "LEGACY_SOURCE_ONLY" app/src/main/java
grep -R "@file:OptIn(RestrictedExpenseDaoMutation::class)" app/src/main/java
grep -R "RequiresOptIn.Level.WARNING" app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
grep -R "TODO P2-" app/src/main/java
```

Expected:

- no ignored business/tax warnings,
- no direct restore-mode write checks in coordinator,
- no production legacy receipt create path,
- no production side-effect mode usage,
- no file-level restricted DAO opt-in,
- restricted DAO mutation is `ERROR`,
- no unresolved Pipeline 2 TODOs.

## Build/test commands

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*BusinessPatch*'
./gradlew testDebugUnitTest --tests '*CreateDiagnostics*'
./gradlew testDebugUnitTest --tests '*Dedup*'
./gradlew testDebugUnitTest --tests '*Duplicate*'
./gradlew testDebugUnitTest --tests '*GroupLifecycle*'
./gradlew testDebugUnitTest --tests '*Bulk*'
./gradlew testDebugUnitTest --tests '*DeleteSemantics*'
./gradlew testDebugUnitTest --tests '*SideEffectModeUsageTest*'
./gradlew testDebugUnitTest --tests '*ExpenseDaoMutationAccessTest*'
./gradlew testDebugUnitTest --tests '*DebugExpenseAudit*'
```

---

# Final definition of done

Pipeline 2 can be marked clean only when:

1. All listed PRs are merged.
2. All compile/test commands pass.
3. Static guards pass.
4. No production legacy receipt create path remains.
5. No unsupported business/tax fields are silently ignored.
6. No direct unapproved `ExpenseDao` mutations exist.
7. No mode-based side-effect API is used in production.
8. Group hard delete is lifecycle-owned and validated.
9. Duplicate paths are visible and side-effect free.
10. Delete/FK/orphan behavior is covered by tests.
11. Bulk side effects are field-aware.
12. Tracker statuses are updated from open/partial to fixed with test references.

Recommended tracker update after completion:

| Issue | Final status |
|---|---:|
| P2-P1-01 / P2-NEW-06 | Fixed |
| P2-P1-02 | Fixed + tests |
| P2-P1-03 | Fixed + tests |
| P2-P1-04 | Fixed + tests |
| P2-P1-05 | Fixed + static guard |
| P2-06 | Fixed |
| P2-07 | Fixed |
| P2-09 | Fixed by regression suite |
| P2-10 | Fixed + static guard |
| P2-11 | Fixed + tests |
| P2-12 | Fixed + tests |