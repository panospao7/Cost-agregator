# Pipeline 2 finalization plan — P2-NEW-01 to P2-NEW-12

Baseline assumption: current post-`1e24ddd` state.

Scope:

| Issue | Current status |
|---|---:|
| P2-NEW-01 update validation | Mostly fixed |
| P2-NEW-02 write barrier | Mostly fixed |
| P2-NEW-03 restore-blocked diagnostic | Mostly fixed |
| P2-NEW-04 strict attempt key | Mostly fixed |
| P2-NEW-05 insert conflict resolver | Mostly fixed |
| P2-NEW-07 category reassignment | Likely fixed; verify coordinator callsite |
| P2-NEW-08 review merchant key parity | Likely fixed; targeted test needed |
| P2-NEW-09 future-date policy | Fixed |
| P2-NEW-10 group create/link orphan | Mostly fixed |
| P2-NEW-11 group provenance | Fixed |
| P2-NEW-12 ownership result check | Partial |

Goal:

```text
Move all listed issues from mostly fixed / partial / likely fixed to fixed + regression-covered.
```

Recommended PRs:

1. PR A — Validation/write-barrier/diagnostic/dedup regression hardening.
2. PR B — Category bulk reassignment + review merchant parity verification.
3. PR C — Group create/link final atomicity and ownership verification.
4. PR D — Final golden/static verification for P2-NEW-01..12.

---

# PR A — Validation, barrier, restore diagnostic, strict dedup finalization

## Fixes / closes

- P2-NEW-01
- P2-NEW-02
- P2-NEW-03
- P2-NEW-04
- P2-NEW-05
- P2-NEW-09 regression coverage

## Goal

Most of these are already implemented. This PR should mainly add missing regression tests and close small contract gaps.

---

## A1 — Finalize P2-NEW-01 update validation

### Verify implementation

Check:

```bash
grep -R "class TransactionValidator" app/src/main/java
grep -R "validateFinalExpenseState" app/src/main/java
grep -R "UPDATE_VALIDATION_FAILED" app/src/main/java
```

Expected:

- `TransactionValidator` exists.
- `createExpense()` and `updateExpense()` use shared validation.
- Invalid update writes `UPDATE_VALIDATION_FAILED`.
- Invalid update does not call `expenseDao.update`.
- Invalid update does not dispatch post-commit actions.

### Required code check

In `TransactionLifecycleCoordinator.updateExpense(...)`, final flow should be:

```text
1. check write barrier
2. load existing row
3. preserve immutable fields like id/createdAt
4. normalize ownership
5. recompute merchantKey/dedupeKey/conversion snapshot if needed
6. validate final Expense state
7. if invalid:
   - write UPDATE_VALIDATION_FAILED best-effort
   - throw TransactionValidationException or return typed failure
   - no expense update
8. if valid:
   - update expense
   - write UPDATED
   - dispatch side effects after commit
```

Also verify these update variants:

```text
updateTypeAndTransferDetails
updateTransferDetails
updateLocation, if it can make final row invalid
updateMerchant, if it can accept blank/placeholder merchant
```

Minimum mandatory:

```text
updateExpense
updateTypeAndTransferDetails
updateTransferDetails
```

### Required tests

Create/update:

```text
TransactionLifecycleCoordinatorUpdateValidationTest.kt
TransactionValidatorTest.kt
```

Tests:

```text
updateExpense_rejects_negative_amount_and_does_not_mutate
updateExpense_rejects_nan_amount_and_does_not_mutate
updateExpense_rejects_blank_merchant_and_does_not_mutate
updateExpense_rejects_placeholder_merchant_and_does_not_mutate
updateExpense_rejects_invalid_currency_and_does_not_mutate
updateExpense_rejects_future_date_and_does_not_mutate
updateExpense_rejects_transfer_without_direction_and_does_not_mutate
updateExpense_rejects_transfer_without_account_and_does_not_mutate
updateExpense_rejects_lat_without_lon_and_does_not_mutate
updateExpense_rejects_lon_without_lat_and_does_not_mutate
updateExpense_invalid_state_writes_UPDATE_VALIDATION_FAILED
updateExpense_invalid_state_does_not_write_UPDATED
updateExpense_invalid_state_does_not_run_side_effects
updateExpense_valid_state_writes_UPDATED_and_runs_side_effects_after_commit
updateTypeAndTransferDetails_rejects_TRANSFER_without_metadata
updateTransferDetails_rejects_clearing_metadata_on_TRANSFER
```

### Acceptance

- P2-NEW-01 can be marked fixed only if invalid updates cannot mutate DB and tests prove it.

---

## A2 — Finalize P2-NEW-02 write-barrier normalization

### Verify implementation

Run:

```bash
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "checkWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Expected:

```text
no restoreMaintenanceMode.isWritesAllowed in TransactionLifecycleCoordinator
```

All mutating coordinator methods must call:

```kotlin
checkWritesAllowed("methodName")
```

or directly:

```kotlin
writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.methodName")
```

### Mutating methods to verify

```text
createExpense / createExpenseMutation
updateExpense
updateCategory
bulkUpdateCategory
bulkUpdateMerchant
updateLocation
updateBusinessFlags / updateBusinessExpensePatch if present
updateTransferDetails
updateTypeAndTransferDetails
updateOwnershipDbOnlyV2
deleteExpense
restore/debug paths if coordinator owns any
```

### Add architecture test

Create:

```text
TransactionLifecycleCoordinatorBarrierUsageTest.kt
```

Test:

```kotlin
@Test
fun coordinator_does_not_directly_query_restoreMaintenanceMode_for_write_permission() {
    val file = Path.of(
        "src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt"
    ).readText()

    assertFalse(file.contains("restoreMaintenanceMode.isWritesAllowed"))
}
```

Optional stronger test:

```text
coordinator_does_not_import_RestoreMaintenanceMode
```

Only add if dependency is fully removed.

### Required behavior tests

```text
createExpense_when_writeBarrier_blocks_returns_Error_or_Blocked
updateExpense_when_writeBarrier_blocks_throws
updateCategory_when_writeBarrier_blocks_throws
bulkUpdateCategory_when_writeBarrier_blocks_throws
deleteExpense_when_writeBarrier_blocks_throws
```

### Acceptance

- P2-NEW-02 fixed when no direct restore-mode write check remains and static test prevents regression.

---

## A3 — Finalize P2-NEW-03 restore-blocked create diagnostic

### Verify implementation

Run:

```bash
grep -R "emitCreateBlockedDiagnosticBestEffort" app/src/main/java
grep -R "EventOutcome.BLOCKED" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "DiagnosticReasonCode.RESTORE_BLOCKED" app/src/main/java
```

Expected:

- blocked create emits diagnostic,
- no expense inserted,
- no `CREATE_ATTEMPTED` transaction event after barrier denial,
- diagnostic is privacy-safe.

### Required tests

```text
restore_blocked_create_emits_TRANSACTION_BLOCKED_diagnostic
restore_blocked_create_diagnostic_has_CREATE_EXPENSE_stage
restore_blocked_create_diagnostic_has_RESTORE_BLOCKED_or_WRITE_BARRIER_DENIED_reason
restore_blocked_create_diagnostic_uses_request_correlationId_when_present
restore_blocked_create_diagnostic_generates_correlationId_when_missing
restore_blocked_create_diagnostic_metadata_is_privacy_safe
restore_blocked_create_does_not_insert_expense
restore_blocked_create_does_not_write_CREATE_ATTEMPTED
restore_blocked_create_does_not_write_CREATED
restore_blocked_create_returns_Error_or_Blocked
restore_blocked_create_still_blocks_when_diagnostic_writer_fails
restore_blocked_create_rethrows_cancellation_from_diagnostic_writer
```

Privacy assertions:

Diagnostic metadata may include:

```text
operation
source
deduplicationMode
transactionType
currency
hasIdempotencyKey
hasExternalFingerprint
exceptionClass
```

Must not include:

```text
merchant
amount
notes
raw idempotencyKey
raw externalFingerprint
receipt text
email payload
```

### Acceptance

- P2-NEW-03 fixed when blocked create is durably observable and privacy-safe.

---

## A4 — Finalize P2-NEW-04 strict attempt key

### Verify implementation

Run:

```bash
grep -R "createAttemptDedupeKey" app/src/main/java
grep -R "strictExternalDedupeKey" app/src/main/java
grep -R "idem:" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
```

Expected:

For `DeduplicationMode.STRICT_EXTERNAL_ID`:

```text
CREATE_ATTEMPTED.dedupeKey == "idem:{source}:{idempotencyKey or externalFingerprint}"
Expense.dedupeKey == same value
```

Strict missing-key validation should have:

```text
dedupeKey = null
```

### Required tests

```text
strict_external_attempt_event_uses_idem_key_from_idempotencyKey
strict_external_attempt_event_uses_externalFingerprint_when_idempotencyKey_missing
strict_external_missing_key_validation_event_has_null_dedupeKey
strict_external_first_create_persists_same_idem_key_as_attempt_event
strict_external_duplicate_event_uses_same_correlationId_as_attempt_event
standard_create_attempt_event_still_uses_standard_dedupe_key
bulk_create_attempt_event_still_uses_standard_dedupe_key
```

### Acceptance

- P2-NEW-04 fixed when attempt/audit key and persisted key are identical for strict external mode.

---

## A5 — Finalize P2-NEW-05 insert conflict resolver

### Verify implementation

Run:

```bash
grep -R "resolveExistingIdAfterInsertConflict" app/src/main/java
grep -R "findDuplicateIdForExpense" app/src/main/java
grep -R "CREATE_INSERT_CONFLICT" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
```

Expected insert conflict flow:

```text
insertAtomic returns <= 0
  -> try exact dedupe-key lookup
  -> for STANDARD/BULK try currency-aware duplicate lookup
  -> if existing ID found:
       write CREATE_DUPLICATE_SKIPPED
       return DuplicateSkipped(existingId)
       do NOT write CREATE_INSERT_CONFLICT
  -> if unresolved:
       write CREATE_INSERT_CONFLICT
       return InsertConflict
```

### Required tests

```text
strict_external_retry_returns_DuplicateSkipped_with_existing_id
strict_external_retry_writes_CREATE_DUPLICATE_SKIPPED
strict_external_retry_does_not_write_CREATE_INSERT_CONFLICT
standard_insert_conflict_resolves_existing_id_by_dedupeKey
standard_insert_conflict_resolves_existing_id_by_currencyAwareLookup
standard_resolved_insert_conflict_returns_DuplicateSkipped
standard_resolved_insert_conflict_does_not_write_CREATE_INSERT_CONFLICT
bulk_insert_conflict_resolves_existing_id_by_dedupeKey
bulk_insert_conflict_resolves_existing_id_by_currencyAwareLookup
bulk_resolved_insert_conflict_returns_DuplicateSkipped
unresolved_insert_conflict_returns_InsertConflict
unresolved_insert_conflict_writes_CREATE_INSERT_CONFLICT
unresolved_insert_conflict_does_not_write_CREATE_DUPLICATE_SKIPPED
```

### Testing strategy

If integration race is hard to force:

- use fake DAO where `insertAtomic()` returns `-1`,
- make lookup methods return known ID,
- or make resolver `internal @VisibleForTesting`.

### Acceptance

- P2-NEW-05 fixed when `InsertConflict` only means unresolved conflict.

---

## A6 — Lock P2-NEW-09 future-date policy

### Verify implementation

Run:

```bash
grep -R "TransactionDatePolicy" app/src/main/java
grep -R "addDays(now, 1)" app/src/main/java
grep -R "DEFAULT_FUTURE_DATE_TOLERANCE_DAYS" app/src/main/java
```

Expected:

- validation uses `TransactionDatePolicy`,
- no hardcoded `addDays(now, 1)` in transaction validation,
- default policy still allows `now + 1 day`.

### Required tests

```text
default_policy_allows_now
default_policy_allows_now_plus_one_day
default_policy_rejects_more_than_one_day_future
create_validation_uses_injected_strict_future_date_policy
create_validation_uses_injected_loose_future_date_policy
update_validation_uses_same_future_date_policy
future_date_validation_error_uses_policy_description
```

### Acceptance

- P2-NEW-09 remains fixed and regression-covered.

---

# PR B — Category reassignment and review merchant parity

## Fixes / closes

- P2-NEW-07
- P2-NEW-08

---

## B1 — Finalize P2-NEW-07 category-to-category reassignment

### Verify implementation

Run:

```bash
grep -R "updateCategoryForCategory" app/src/main/java
grep -R "bulkUpdateCategory" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "getExpensesByCategory(categoryId" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
```

Expected:

- `ExpenseDao.updateCategoryForCategory(oldCategoryId, newCategoryId)` exists.
- `bulkUpdateCategory(categoryId, newCategoryId)` uses one SQL update.
- No loop over expenses calling `updateCategory()` remains.
- One `BULK_UPDATED` event is written.
- One post-commit bulk side-effect batch is dispatched.
- Changed fields include `BulkChangedField.CATEGORY` if bulk changed-field PR exists.

### Required implementation if missing

DAO:

```kotlin
@Query("""
    UPDATE expenses
    SET categoryId = :newCategoryId
    WHERE categoryId = :oldCategoryId
""")
suspend fun updateCategoryForCategory(
    oldCategoryId: Long,
    newCategoryId: Long
): Int
```

Coordinator:

```kotlin
database.withTransaction {
    affectedCount = expenseDao.updateCategoryForCategory(categoryId, newCategoryId)

    if (affectedCount > 0) {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.BULK_UPDATED.name,
                source = source,
                metadata = /* oldCategoryId, newCategoryId, affectedCount, changedFields=CATEGORY */,
                correlationId = correlationId,
                ...
            )
        )
    }
}

if (affectedCount > 0) {
    planner.planBulkUpdated(
        source = source,
        affectedCount = affectedCount,
        correlationId = correlationId,
        changedFields = setOf(BulkChangedField.CATEGORY)
    )
}
```

### Required tests

```text
bulk_category_reassignment_updates_all_matching_rows
bulk_category_reassignment_does_not_update_non_matching_rows
bulk_category_reassignment_same_source_and_target_is_noop
bulk_category_reassignment_zero_affected_writes_no_event
bulk_category_reassignment_zero_affected_runs_no_side_effects
bulk_category_reassignment_writes_one_BULK_UPDATED_event
bulk_category_reassignment_event_has_expenseId_null
bulk_category_reassignment_event_metadata_has_oldCategory_newCategory_affectedCount
bulk_category_reassignment_dispatches_one_bulk_side_effect_batch
bulk_category_reassignment_passes_CATEGORY_changed_field
bulk_category_reassignment_event_insert_failure_rolls_back_category_update
bulk_category_reassignment_blocked_during_restore
```

### Acceptance

- P2-NEW-07 fixed when no per-expense reassignment loop remains and rollback test passes.

---

## B2 — Finalize P2-NEW-08 review merchant key parity

### Verify implementation

Run:

```bash
grep -R "normalizedMerchantForKeys" app/src/main/java
grep -R "merchantNormalizer.normalize" app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
grep -R "review.suggestedCurrency" app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
```

Expected:

- no `normalizedMerchantForKeys`;
- review approval does not pre-normalize merchant before create;
- `CreateExpenseRequest.merchant` receives resolved display merchant;
- coordinator owns persisted `merchantKey` and `dedupeKey`;
- `resolvedCurrency` is used consistently, not stale `review.suggestedCurrency`.

### Required implementation if missing

Inside `ReviewQueueRepository.approveReview()`:

```kotlin
val merchant = finalMerchant ?: review.suggestedMerchant

val resolvedCurrency =
    finalCurrency?.takeIf { it.isNotBlank() }
        ?: review.suggestedCurrency?.takeIf { it.isNotBlank() }
        ?: return Result.Error("Currency is required")
```

Then build `CreateExpenseRequest` directly:

```kotlin
CreateExpenseRequest(
    merchant = merchant,
    amount = amount,
    currency = resolvedCurrency,
    date = transactionDate,
    transactionType = type,
    source = ExpenseSource.REVIEW_APPROVAL,
    pendingReviewId = reviewId,
    scannedReceiptId = review.scannedReceiptId,
    rawNotificationId = review.rawNotificationId,
    ...
)
```

Do not compute local persisted key fields unless temporary object requires them. If temporary object remains, it must use exactly:

```kotlin
MerchantKeyGenerator.generate(merchant)
DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, transactionDate, resolvedCurrency, type)
```

No `merchantNormalizer.normalize()` before create.

Keep alias learning only after successful approval:

```kotlin
if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
    merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
}
```

### Required tests

```text
approve_review_passes_resolved_merchant_to_CreateExpenseRequest
approve_review_does_not_pre_normalize_merchant_before_create
approve_review_finalMerchant_override_uses_finalMerchant
approve_review_uses_resolvedCurrency_not_stale_suggestedCurrency
approve_review_blank_currency_returns_error
approve_review_with_same_merchant_as_auto_accept_produces_same_merchantKey
approve_review_learns_alias_only_after_successful_create
approve_review_duplicate_path_still_routes_through_coordinator
```

### Test strategy

Use fake `MerchantNormalizer`:

```text
normalize("ACME") -> "ACME NORMALIZED"
normalize("ACME NORMALIZED") -> "ACME DOUBLE NORMALIZED"
```

Assert approve path does not double-normalize.

### Acceptance

- P2-NEW-08 fixed when review approval and auto-accept produce same key for same merchant string.

---

# PR C — Group create/link finalization

## Fixes / closes

- P2-NEW-10
- P2-NEW-11
- P2-NEW-12

---

## C1 — Finalize P2-NEW-10 group system-expense create/link orphan prevention

### Verify implementation

Run:

```bash
grep -R "GroupExpenseAtomicRollback" app/src/main/java
grep -R "createSystemExpenseAndLinkToGroup" app/src/main/java
grep -R "createExpenseDbOnlyV2" app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
```

Expected:

- system expense create uses DB-only lifecycle create inside outer `database.withTransaction`;
- any post-create group link failure throws inside the transaction;
- catch outside transaction converts rollback signal to public error;
- post-commit actions run only after transaction success.

### Code contract

Inside `withTransaction`:

Bad:

```kotlin
return Error(...)
```

Good:

```kotlin
throw GroupExpenseAtomicRollback(...)
```

Failures that must throw:

```text
CreateExpenseResult not Created
group link already exists
groupExpenseDao.insert returns <= 0
groupExpenseDao.insert throws
source-link write failure
ownership/link invariant failure
```

### Required tests

```text
create_system_expense_validation_failure_does_not_insert_group_link
create_system_expense_link_already_exists_rolls_back_expense
create_system_expense_link_insert_returns_minus_one_rolls_back_expense
create_system_expense_link_insert_exception_rolls_back_expense
create_system_expense_source_link_failure_rolls_back_expense
create_system_expense_rollback_does_not_run_post_commit_actions
create_system_expense_success_commits_expense_and_group_link
create_system_expense_success_runs_post_commit_actions_after_commit
```

Assertions for rollback cases:

```text
expense row missing
group_expenses row missing
transaction_events for created expense missing if create was inside same tx
source links missing
post-commit runner not called
```

### Acceptance

- P2-NEW-10 fixed when all rollback cases prove no orphan system expense remains.

---

## C2 — Finalize P2-NEW-11 group provenance

### Verify implementation

Run:

```bash
grep -R "source = ExpenseSource.GROUP_EXPENSE" app/src/main/java
grep -R "groupId = groupId" app/src/main/java
grep -R "GROUP_EXPENSE" app/src/main/java/com/yourname/expensetracker/domain/provenance
```

Expected:

- every group-created system expense request has:
  ```kotlin
  source = ExpenseSource.GROUP_EXPENSE
  groupId = groupId
  ```
- source-link mapper creates concrete group source link.

### Required tests

```text
create_system_expense_request_contains_groupId
group_created_system_expense_has_GROUP_EXPENSE_source_link
group_created_system_expense_source_link_entityId_is_groupId
group_created_system_expense_does_not_fallback_to_LEGACY_SOURCE_ONLY
group_source_link_written_in_same_transaction_as_expense_create
group_source_link_failure_rolls_back_expense
```

### Acceptance

- P2-NEW-11 fixed when provenance is concrete and atomic.

---

## C3 — Complete P2-NEW-12 ownership result check

### Current gap

Recent fix checks result type:

```text
Updated / NoOp -> proceed
NotFound -> rollback
```

But it still may trust `NoOp` without verifying row fields.

### Required implementation

Inside `addExpenseWithLink()` transaction, after:

```kotlin
val ownershipMutation = transactionLifecycleCoordinator.updateOwnershipDbOnlyV2(...)
```

do:

```kotlin
when (ownershipMutation.value) {
    is OwnershipUpdateResult.NotFound -> throw GroupExpenseAtomicRollback(...)
    is OwnershipUpdateResult.Updated,
    is OwnershipUpdateResult.NoOp -> Unit
    else -> throw GroupExpenseAtomicRollback(...)
}
```

Then reload the expense inside the same transaction:

```kotlin
val updatedExpense = expenseDao.getById(expenseId)
    ?: throw GroupExpenseAtomicRollback("Expense missing after ownership update")
```

Verify final fields match intended group link semantics.

Minimum checks:

```kotlin
if (!updatedExpense.isSharedExpense) fail
if (updatedExpense.isNotMine) fail
if (!approximatelyEquals(updatedExpense.myShareAmount, expectedMyShareAmount)) fail
if (!approximatelyEquals(updatedExpense.mySharePercentage, expectedMySharePercentage)) fail
```

Also verify fields actually used by app:

```text
sharedWithName
ownerName
myShareAmount
mySharePercentage
```

Use actual expected values from group split logic.

Helper:

```kotlin
private fun Double?.approximatelyEquals(
    other: Double?,
    epsilon: Double = 0.0001
): Boolean {
    if (this == null || other == null) return this == other
    return kotlin.math.abs(this - other) <= epsilon
}
```

If mismatch:

```kotlin
throw GroupExpenseAtomicRollback(
    "Ownership update did not produce expected shared-expense state"
)
```

Important:

- verification must happen before group link insert or before transaction exits;
- any mismatch throws, causing rollback;
- post-commit actions are not run.

### Required tests

```text
add_existing_expense_updated_result_but_row_mismatch_rolls_back_group_link
add_existing_expense_noop_result_but_row_not_matching_rolls_back_group_link
add_existing_expense_noop_result_and_row_matching_allows_group_link
add_existing_expense_not_found_result_rolls_back_group_link
add_existing_expense_link_insert_failure_rolls_back_ownership_update
add_existing_expense_success_commits_ownership_and_group_link
add_existing_expense_success_runs_ownership_side_effects_after_commit
add_existing_expense_rollback_does_not_run_post_commit_actions
```

### Acceptance

- P2-NEW-12 fixed when group link cannot commit unless expense ownership row actually matches expected state.

---

# PR D — Final static/golden verification for P2-NEW-01..12

## Goal

After PRs A–C, run final static and golden tests before marking issues closed.

---

## D1 — Final grep checklist

Run:

```bash
grep -R "P2-CURRENT-001\|P2-CURRENT-002\|P2-CURRENT-003\|P2-CURRENT-004\|P2-CURRENT-005\|P2-CURRENT-006\|P2-CURRENT-007\|P2-CURRENT-008\|P2-CURRENT-009\|P2-CURRENT-010\|P2-CURRENT-011\|P2-CURRENT-012\|P2-CURRENT-015\|P2-CURRENT-020" app/src/main/java
```

Expected:

```text
no unresolved TODOs for P2-NEW-01..12
```

Run:

```bash
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "normalizedMerchantForKeys" app/src/main/java
grep -R "addDays(now, 1)" app/src/main/java
grep -R "getExpensesByCategory(categoryId" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle
grep -R "return .*Error" app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
```

Review `return Error` hits manually. Returning error outside transaction is fine; returning inside `database.withTransaction` for rollback-required paths is not.

---

## D2 — Golden tests for this issue batch

Create a single high-level test class if useful:

```text
Pipeline2New01To12GoldenTest.kt
```

Required golden cases:

```text
golden_update_invalid_state_rejected_and_audited
golden_restore_blocked_create_diagnostic_no_expense
golden_strict_external_retry_duplicate_existing_id
golden_standard_insert_conflict_resolves_existing_id
golden_bulk_category_reassign_atomic_one_event_one_side_effect
golden_review_approval_merchant_key_matches_auto_accept
golden_future_date_policy_is_injectable
golden_group_system_expense_create_link_atomic
golden_group_system_expense_has_group_source_link
golden_add_existing_expense_to_group_verifies_ownership_row
```

---

## D3 — Build commands

Run:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*TransactionValidatorTest*'
./gradlew testDebugUnitTest --tests '*UpdateValidation*'
./gradlew testDebugUnitTest --tests '*WriteBarrier*'
./gradlew testDebugUnitTest --tests '*CreateBlockedDiagnostic*'
./gradlew testDebugUnitTest --tests '*StrictExternalDedupe*'
./gradlew testDebugUnitTest --tests '*InsertConflictResolution*'
./gradlew testDebugUnitTest --tests '*BulkCategory*'
./gradlew testDebugUnitTest --tests '*ReviewQueueRepositoryMerchantKey*'
./gradlew testDebugUnitTest --tests '*TransactionDatePolicy*'
./gradlew testDebugUnitTest --tests '*GroupTransactionCoordinatorAtomicity*'
./gradlew testDebugUnitTest --tests '*GroupSourceLink*'
./gradlew testDebugUnitTest --tests '*Ownership*'
```

---

# Final issue-by-issue definition of done

## P2-NEW-01 done when

- `updateExpense()` validates final row through shared validator.
- Transfer/type update helpers also validate final row.
- Invalid update writes `UPDATE_VALIDATION_FAILED`.
- Invalid update writes no `UPDATED`.
- Invalid update mutates no row.
- Invalid update runs no side effects.

## P2-NEW-02 done when

- No `restoreMaintenanceMode.isWritesAllowed` remains in coordinator.
- All coordinator mutations use `DatabaseWriteBarrier`.
- Static test prevents regression.

## P2-NEW-03 done when

- Restore-blocked create emits durable diagnostic.
- No expense/event row is created after write-barrier denial.
- Diagnostic metadata is privacy-safe.

## P2-NEW-04 done when

- Strict external attempt key equals persisted key.
- Missing strict key uses null dedupe key in validation event.
- Attempt/duplicate events share correlation ID.

## P2-NEW-05 done when

- Resolvable insert conflicts return `DuplicateSkipped(existingId)`.
- Resolved conflicts write duplicate event, not insert-conflict event.
- `InsertConflict` only means unresolved.

## P2-NEW-07 done when

- Category reassignment uses one SQL update.
- One `BULK_UPDATED` event.
- One post-commit bulk batch.
- Rollback test proves atomicity.

## P2-NEW-08 done when

- Review approval does not pre-normalize merchant before create.
- Resolved currency is used everywhere.
- Coordinator owns merchantKey/dedupeKey.
- Test proves parity with auto-accept.

## P2-NEW-09 done when

- Future-date tolerance comes from `TransactionDatePolicy`.
- Create and update validation use it.
- Strict/loose fake policy tests pass.

## P2-NEW-10 done when

- System expense + group link rollback tests pass.
- No post-create group-link failure can commit orphan expense.
- Side effects run after commit only.

## P2-NEW-11 done when

- Group-created expenses pass `groupId`.
- Concrete group source link is written atomically.
- No legacy fallback source link for group creates.

## P2-NEW-12 done when

- `addExpenseWithLink()` verifies final ownership row fields.
- `NoOp` is accepted only if row already matches expected state.
- Link insert failure rolls back ownership update.
- Ownership mismatch rolls back group link.

---

# Recommended tracker update after completion

| Issue | Final status |
|---|---:|
| P2-NEW-01 | Fixed + tests |
| P2-NEW-02 | Fixed + static test |
| P2-NEW-03 | Fixed + tests |
| P2-NEW-04 | Fixed + tests |
| P2-NEW-05 | Fixed + tests |
| P2-NEW-07 | Fixed + atomicity tests |
| P2-NEW-08 | Fixed + parity tests |
| P2-NEW-09 | Fixed + policy tests |
| P2-NEW-10 | Fixed + rollback tests |
| P2-NEW-11 | Fixed + provenance tests |
| P2-NEW-12 | Fixed + ownership verification tests |