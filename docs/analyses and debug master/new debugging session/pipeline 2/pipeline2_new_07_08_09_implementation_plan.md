# Pipeline 2 implementation plan — P2-NEW-07, P2-NEW-08, P2-NEW-09

Target baseline: `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-07 | P2 | Category-to-category bulk reassignment is non-atomic |
| P2-NEW-08 | P2 | Review approval merchant key can diverge from auto-accept path |
| P2-NEW-09 | P2 | Future-date policy is hardcoded |

Relevant current evidence:

- `TransactionLifecycleCoordinator.bulkUpdateCategory(categoryId, newCategoryId)` loads expenses and loops through `updateCategory(...)`, so a crash/cancellation mid-loop can leave partial reassignment.
- Merchant-key bulk category update already uses one DAO update + one `BULK_UPDATED` event. Category-to-category should use the same pattern.
- `ReviewQueueRepository.approveReview()` normalizes `merchant` through `merchantNormalizer.normalize(...)` before generating `merchantKey`, even though review merchants may already be normalized/corrected by the intake path.
- Review approval also computes a local `dedupeKey` with `review.suggestedCurrency`, not the resolved final currency.
- `TransactionLifecycleCoordinator.validate(request)` rejects future dates using `TimePeriodUtils.addDays(now, 1)`, with a TODO to make tolerance configurable.

---

# Recommended PR slicing

Implement as three small PRs:

1. **PR A — Atomic category-to-category bulk reassignment**
   - Fixes P2-NEW-07.

2. **PR B — Review approval merchant/key parity**
   - Fixes P2-NEW-08.

3. **PR C — Injectable future-date validation policy**
   - Fixes P2-NEW-09.

Do not mix dedupe-conflict resolution, group lifecycle, receipt legacy path, or full update validation into these PRs.

---

# PR A — Atomic category-to-category bulk reassignment

## Goal

Replace the current per-expense category reassignment loop with:

```text
one SQL UPDATE
one BULK_UPDATED event
one post-commit bulk side-effect batch
```

No partial category migration should be possible if the process crashes mid-operation.

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorBulkCategoryTest.kt
```

Use the project’s existing test package/style if different.

---

## Step A1 — Add DAO method

In `ExpenseDao.kt`, add:

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

Optional but useful for tests:

```kotlin
@Query("SELECT COUNT(*) FROM expenses WHERE categoryId = :categoryId")
suspend fun countByCategory(categoryId: Long): Int
```

Do not use `getExpensesByCategory(..., 0L, Long.MAX_VALUE)` for mutation count. The SQL update already returns affected rows.

---

## Step A2 — Replace looping implementation

Current method shape:

```kotlin
suspend fun bulkUpdateCategory(
    categoryId: Long,
    newCategoryId: Long,
    source: String = "CATEGORY_CORRECTION"
)
```

Replace the body with an atomic transaction.

Recommended implementation:

```kotlin
suspend fun bulkUpdateCategory(
    categoryId: Long,
    newCategoryId: Long,
    source: String = "CATEGORY_CORRECTION"
) {
    if (!restoreMaintenanceMode.isWritesAllowed()) {
        throw IllegalStateException("Database writes blocked during restore")
    }

    if (categoryId == newCategoryId) {
        Timber.d(
            "Bulk category update skipped: source and target category are identical (%d)",
            categoryId
        )
        return
    }

    val now = timeProvider.now()
    val correlationId =
        com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()

    var affectedCount = 0

    database.withTransaction {
        affectedCount = expenseDao.updateCategoryForCategory(
            oldCategoryId = categoryId,
            newCategoryId = newCategoryId
        )

        if (affectedCount > 0) {
            transactionEventDao.insert(
                TransactionEvent(
                    expenseId = null,
                    eventType = LifecycleEventType.BULK_UPDATED.name,
                    source = source,
                    actor = null,
                    occurredAt = now,
                    dedupeKey = null,
                    duplicateExpenseId = null,
                    beforeSnapshot = null,
                    afterSnapshot = null,
                    metadata = JSONObject().apply {
                        put("operation", "bulkUpdateCategoryByCategory")
                        put("oldCategoryId", categoryId)
                        put("newCategoryId", newCategoryId)
                        put("affectedCount", affectedCount)
                        put("changedFields", "categoryId")
                        put("atomic", true)
                    }.toString(),
                    reason = "Bulk reassigned category $categoryId to $newCategoryId",
                    correlationId = correlationId
                )
            )
        }
    }

    if (affectedCount > 0) {
        dispatchBulkPostCommitSideEffects(source, affectedCount)
    }

    Timber.d(
        "Bulk category update: %d expenses moved from category %d to %d",
        affectedCount,
        categoryId,
        newCategoryId
    )
}
```

If the P2-07 enhanced bulk side-effect PR has already landed, call:

```kotlin
planner.planBulkUpdated(
    source = source,
    affectedCount = affectedCount,
    correlationId = correlationId,
    changedFields = setOf(BulkChangedField.CATEGORY)
)
```

instead of the old `dispatchBulkPostCommitSideEffects(source, affectedCount)` helper.

---

## Step A3 — Remove stale TODO

Remove:

```text
TODO P2-CURRENT-015
```

Replace with a short comment:

```kotlin
// Atomic category reassignment: one SQL UPDATE + one BULK_UPDATED event.
```

---

## Step A4 — Do not write per-expense `UPDATED` events

This PR intentionally changes category-to-category reassignment from N per-row events to one aggregate event.

Reason:

- avoids partial migration,
- avoids event spam,
- avoids N budget/anomaly recalculations,
- matches existing merchant-key bulk update semantics.

If product requires per-expense audit later, add a separate bulk detail table, not N lifecycle rows in this PR.

---

## PR A tests

Required tests:

```text
bulk_category_reassignment_updates_all_matching_rows
bulk_category_reassignment_does_not_update_non_matching_rows
bulk_category_reassignment_is_single_atomic_operation
bulk_category_reassignment_writes_one_BULK_UPDATED_event
bulk_category_reassignment_event_has_expenseId_null
bulk_category_reassignment_event_metadata_has_oldCategory_newCategory_affectedCount
bulk_category_reassignment_dispatches_one_bulk_side_effect_batch
bulk_category_reassignment_same_source_and_target_is_noop
bulk_category_reassignment_zero_affected_rows_writes_no_event_and_no_side_effects
bulk_category_reassignment_blocked_during_restore
```

Atomic rollback test:

```text
bulk_category_reassignment_event_insert_failure_rolls_back_category_update
```

Implementation hint:

- use a fake/throwing `TransactionEventDao` if test setup allows,
- or create a test-only constraint failure on event insert,
- assert old category rows are unchanged after the transaction fails.

---

## PR A acceptance criteria

- No per-expense loop remains in category-to-category reassignment.
- One DAO update changes all matching rows.
- One `BULK_UPDATED` event is written inside the same DB transaction.
- Side effects run once after commit.
- Crash/event failure cannot leave partial category migration.
- `P2-CURRENT-015` is removed.

---

# PR B — Review approval merchant/key parity

## Goal

Review approval should use the same merchant/key semantics as auto-accept/coordinator create:

```text
resolved display merchant -> CreateExpenseRequest.merchant -> coordinator owns merchantKey/dedupeKey
```

Review approval should not pre-normalize an already-normalized/corrected merchant and then compute local keys that can diverge.

---

## Current risky code

In `ReviewQueueRepository.approveReview()`:

```kotlin
val merchant: String = finalMerchant ?: review.suggestedMerchant

val normalizedMerchantForKeys =
    merchantNormalizer.normalize(merchant).canonical.normalizedName

merchantKey = MerchantKeyGenerator.generate(normalizedMerchantForKeys)

dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
    amount,
    normalizedMerchantForKeys,
    transactionDate,
    review.suggestedCurrency,
    type
)
```

But the actual creation is delegated to:

```kotlin
transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)
```

The coordinator regenerates merchant key/dedupe key from `CreateExpenseRequest`.

So review approval should stop computing divergent local key material.

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
```

Optional new pure helper:

```text
app/src/main/java/com/yourname/expensetracker/domain/review/ReviewApprovalFieldResolver.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryMerchantKeyTest.kt
```

or pure helper tests if repository tests are heavy.

---

## Step B1 — Resolve currency before building any dedupe/key material

Add near the start of `approveReview()` after amount/merchant validation:

```kotlin
val resolvedCurrency: String =
    finalCurrency?.takeIf { it.isNotBlank() }
        ?: review.suggestedCurrency?.takeIf { it.isNotBlank() }
        ?: return Result.Error(
            message = "Currency is required. Please edit the review and select a currency."
        )
```

Then remove the inline `run { ... }` currency block inside `Expense(...)`.

---

## Step B2 — Remove double-normalization before create

Delete:

```kotlin
val normalizedMerchantForKeys: String =
    merchantNormalizer.normalize(merchant).canonical.normalizedName
```

For local temporary `Expense`, if keeping it, use:

```kotlin
merchantKey = MerchantKeyGenerator.generate(merchant)
```

and:

```kotlin
dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
    amount = amount,
    merchant = merchant,
    date = transactionDate,
    currency = resolvedCurrency,
    transactionType = type
)
```

Important:

- Do not call `merchantNormalizer.normalize()` before `CreateExpenseRequest`.
- Do not use `review.suggestedCurrency` after `resolvedCurrency` exists.
- The coordinator remains the canonical owner of persisted merchant key and dedupe key.

---

## Step B3 — Prefer building `CreateExpenseRequest` directly

Best implementation: remove the temporary `Expense` object entirely.

Replace:

```kotlin
val expense = Expense(...)
...
val request = CreateExpenseRequest(
    merchant = expense.merchant,
    amount = expense.amount,
    ...
)
```

with direct resolved fields:

```kotlin
val request = CreateExpenseRequest(
    merchant = merchant,
    amount = amount,
    currency = resolvedCurrency,
    date = transactionDate,
    transactionType = type,
    source = ExpenseSource.REVIEW_APPROVAL,
    categoryId = categoryId,
    notes = if (review.scannedReceiptId != null) "Scanned from receipt" else null,
    paymentMethod = PaymentMethod.CARD,
    isManualEntry = review.scannedReceiptId != null,
    transferDirection = transferDirection,
    transferAccountName = transferAccountName,
    latitude = if (locationCleared) null else finalLatitude ?: review.suggestedLatitude,
    longitude = if (locationCleared) null else finalLongitude ?: review.suggestedLongitude,
    locationSource = when {
        locationCleared -> AppConfig.Location.SOURCE_UNKNOWN
        finalLatitude != null && finalLongitude != null -> AppConfig.Location.SOURCE_USER_MANUAL
        review.suggestedLatitude != null && review.suggestedLongitude != null -> AppConfig.Location.SOURCE_DEVICE_GPS
        else -> AppConfig.Location.SOURCE_UNKNOWN
    },
    placeId = if (locationCleared) null else finalPlaceId,
    resolvedAddress = if (locationCleared) null else finalAddress,
    rawNotificationId = review.rawNotificationId,
    pendingReviewId = reviewId,
    scannedReceiptId = review.scannedReceiptId,
    skipDeduplication = false
)
```

This is cleaner because the local `Expense` is not the persisted row and should not own lifecycle keys.

If this refactor is too large for the agent, keep the temporary `Expense` but remove double normalization and use `resolvedCurrency`.

---

## Step B4 — Keep alias learning post-commit only

Do not remove this behavior:

```kotlin
if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
    merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
}
```

That is user-correction learning after successful approval. It is different from pre-create normalization.

---

## Step B5 — Remove stale TODO

Remove:

```text
TODO P2-CURRENT-006
```

Replace with:

```kotlin
// The coordinator owns merchantKey and dedupeKey generation.
// Review approval passes the resolved merchant display value directly.
```

---

## PR B tests

Preferred pure tests if repository setup is heavy:

```text
review_approval_resolved_currency_prefers_finalCurrency
review_approval_resolved_currency_falls_back_to_suggestedCurrency
review_approval_blank_currency_returns_error
```

Repository/integration tests:

```text
approve_review_does_not_call_merchantNormalizer_normalize_before_create
approve_review_passes_resolved_merchant_to_CreateExpenseRequest
approve_review_with_already_normalized_suggestedMerchant_keeps_same_merchant_key_as_auto_accept
approve_review_finalMerchant_override_uses_finalMerchant_for_create
approve_review_dedupe_uses_resolvedCurrency_not_stale_suggestedCurrency
approve_review_still_learns_alias_after_successful_finalMerchant_override
```

Test strategy:

- Use a fake `MerchantNormalizer` whose `normalize("ACME")` returns `"ACME CANON"` and whose `normalize("ACME CANON")` returns `"ACME CANON 2"`.
- Approving a review with `suggestedMerchant = "ACME CANON"` should not call `normalize()` before create.
- Assert the created expense has:
  ```text
  merchant == "ACME CANON"
  merchantKey == MerchantKeyGenerator.generate("ACME CANON")
  ```
- If testing through coordinator is too heavy, inject a fake `TransactionLifecycleCoordinator` and capture the `CreateExpenseRequest`.

---

## PR B acceptance criteria

- Review approval no longer double-normalizes the merchant before create.
- Coordinator remains the only owner of persisted merchant key/dedupe key.
- Review approval uses `resolvedCurrency` consistently.
- Auto-accept and review approval produce the same key for the same persisted merchant string.
- `P2-CURRENT-006` is removed.

---

# PR C — Injectable future-date validation policy

## Goal

Replace hardcoded:

```kotlin
request.date > TimePeriodUtils.addDays(now, 1)
```

with an injectable policy object.

Default behavior must remain the same:

```text
allow up to now + 1 calendar day
```

But tests and future settings should be able to use stricter or looser policies.

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt
```

New files:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionDatePolicy.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/DefaultTransactionDatePolicy.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/validation/TransactionDatePolicyTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorDateValidationTest.kt
```

If the P2-NEW-01 shared `TransactionValidator` already exists, inject this policy into `TransactionValidator` instead of directly into the coordinator.

---

## Step C1 — Add config constant

In `AppConfig.kt`, add:

```kotlin
object Transaction {
    const val DEFAULT_FUTURE_DATE_TOLERANCE_DAYS = 1
}
```

Do not remove existing amount constants.

---

## Step C2 — Add policy interface

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.ExpenseSource

interface TransactionDatePolicy {
    fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long

    fun describeFutureDatePolicy(): String
}
```

Rationale:

- `source` lets future policy allow scheduled recurring/manual entries differently.
- `transactionType` lets future policy allow planned transfers differently.
- For now, default implementation ignores both.

---

## Step C3 — Add default implementation

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTransactionDatePolicy @Inject constructor() : TransactionDatePolicy {
    override fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long {
        return TimePeriodUtils.addDays(
            now,
            AppConfig.Transaction.DEFAULT_FUTURE_DATE_TOLERANCE_DAYS
        )
    }

    override fun describeFutureDatePolicy(): String {
        return "Date cannot be more than ${AppConfig.Transaction.DEFAULT_FUTURE_DATE_TOLERANCE_DAYS} day(s) in the future"
    }
}
```

If Hilt needs explicit binding, add a module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class TransactionValidationPolicyModule {
    @Binds
    abstract fun bindTransactionDatePolicy(
        impl: DefaultTransactionDatePolicy
    ): TransactionDatePolicy
}
```

If constructor injection of concrete class is preferred in this codebase, inject `DefaultTransactionDatePolicy` directly. Interface binding is cleaner.

---

## Step C4 — Inject policy into validation owner

### If `TransactionValidator` exists

Modify:

```kotlin
class TransactionValidator @Inject constructor(
    private val timeProvider: TimeProvider,
    private val transactionDatePolicy: TransactionDatePolicy
)
```

Then replace future-date validation with:

```kotlin
val latestAllowed = transactionDatePolicy.latestAllowedTransactionDate(
    now = now,
    source = request.source,
    transactionType = request.transactionType
)

if (request.date > latestAllowed) {
    errors.add(transactionDatePolicy.describeFutureDatePolicy())
}
```

### If validation is still inside `TransactionLifecycleCoordinator`

Inject:

```kotlin
private val transactionDatePolicy: TransactionDatePolicy
```

Then replace:

```kotlin
if (request.date > TimePeriodUtils.addDays(now, 1)) {
    errors.add("Date cannot be in the future")
}
```

with:

```kotlin
val latestAllowed = transactionDatePolicy.latestAllowedTransactionDate(
    now = now,
    source = request.source,
    transactionType = request.transactionType
)

if (request.date > latestAllowed) {
    errors.add(transactionDatePolicy.describeFutureDatePolicy())
}
```

---

## Step C5 — Keep default behavior unchanged

The default policy must still allow:

```text
date <= now + 1 calendar day
```

This avoids surprising current users/tests.

Only the owner changes from hardcoded inline logic to injectable policy.

---

## Step C6 — Remove stale TODO/import

Remove:

```text
TODO P2-CURRENT-020
```

If `TimePeriodUtils` is no longer used in `TransactionLifecycleCoordinator`, remove its import there.

---

## PR C tests

### Policy tests

```text
default_policy_allows_now
default_policy_allows_now_plus_one_day
default_policy_rejects_more_than_one_day_future
default_policy_uses_calendar_addDays_not_raw_millis
```

### Coordinator/validator tests with fake policy

Create fake strict policy:

```kotlin
class StrictNoFutureDatePolicy : TransactionDatePolicy {
    override fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long = now

    override fun describeFutureDatePolicy(): String =
        "Future dates are not allowed"
}
```

Create fake loose policy:

```kotlin
class LooseFourteenDayFutureDatePolicy : TransactionDatePolicy {
    override fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long = TimePeriodUtils.addDays(now, 14)

    override fun describeFutureDatePolicy(): String =
        "Date cannot be more than 14 days in the future"
}
```

Required tests:

```text
create_validation_uses_injected_strict_future_date_policy
create_validation_uses_injected_loose_future_date_policy
future_date_validation_error_uses_policy_description
future_date_policy_receives_source_and_transactionType
```

If shared update validation exists:

```text
update_validation_uses_same_future_date_policy
```

---

## PR C acceptance criteria

- No hardcoded `addDays(now, 1)` remains in transaction create/update validation.
- Default behavior is unchanged.
- A fake strict policy can reject tomorrow.
- A fake loose policy can allow a future scheduled date.
- `P2-CURRENT-020` is removed.

---

# Combined execution order

Recommended order:

1. **PR C — future-date policy**
   - Low risk and improves validation foundation.

2. **PR A — atomic category reassignment**
   - Fixes actual partial-write risk.

3. **PR B — review merchant/key parity**
   - Removes subtle duplicate/key divergence risk.

Alternative:

- PR A can be done first if you want to prioritize data atomicity.
- PR B is independent except for tests that compare with coordinator behavior.

---

# Combined validation commands

Run after each PR:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*BulkCategory*'
./gradlew testDebugUnitTest --tests '*ReviewQueueRepositoryMerchantKey*'
./gradlew testDebugUnitTest --tests '*TransactionDatePolicy*'
./gradlew testDebugUnitTest --tests '*DateValidation*'
```

Manual grep checks:

```bash
grep -R "P2-CURRENT-015" app/src/main/java
grep -R "P2-CURRENT-006" app/src/main/java
grep -R "P2-CURRENT-020" app/src/main/java
grep -R "normalizedMerchantForKeys" app/src/main/java
grep -R "addDays(now, 1)" app/src/main/java
grep -R "getExpensesByCategory(categoryId, 0L, Long.MAX_VALUE)" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Expected:

- no TODOs remain for these three issues,
- no `normalizedMerchantForKeys`,
- no hardcoded future-date tolerance in transaction validation,
- no category reassignment loop.

---

# Definition of done

## P2-NEW-07 done when

- Category-to-category reassignment uses one DAO `UPDATE`.
- Update and `BULK_UPDATED` event happen in one DB transaction.
- Zero affected rows produce no event/side effects.
- One post-commit bulk side-effect batch runs after commit.
- No per-expense loop remains.
- Rollback test proves event failure rolls back category changes.

## P2-NEW-08 done when

- Review approval does not call `merchantNormalizer.normalize()` before building the create request.
- Review approval passes the resolved merchant string directly to `CreateExpenseRequest`.
- Coordinator owns persisted `merchantKey` and `dedupeKey`.
- Local temporary dedupe/key code is removed or uses the exact same resolved merchant/currency as the coordinator.
- Resolved final currency is used everywhere in review approval.
- Auto-accept and review approval produce the same merchant key for the same merchant string.

## P2-NEW-09 done when

- Future-date tolerance is owned by `TransactionDatePolicy`.
- Default policy preserves `now + 1 day`.
- Tests can inject strict and loose policies.
- Create validation uses the policy.
- Update validation uses the policy too if shared update validation already exists.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `ReviewQueueRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
- `ExpenseDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- `TransactionSideEffectPlanner.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- `MerchantNormalizer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/MerchantNormalizer.kt
- `AppConfig.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/config/AppConfig.kt
- `TimePeriodUtils.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt