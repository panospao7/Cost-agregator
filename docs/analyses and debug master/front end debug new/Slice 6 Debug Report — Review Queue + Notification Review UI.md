Created the Slice 6 AI-friendly debugging report.

<slice-6-review-queue-debug-report.md>
# Slice 6 Debug Report — Review Queue + Notification Review UI

Commit reviewed: `ea3f716eebba8c513edeeba40db394c10ca829cb`  
Review type: static GitHub source review, not local Gradle/device execution.

Scope:
- `ui/screens/review/ReviewScreen.kt`
- `ui/screens/review/ReviewViewModel.kt`
- `ui/components/ai/CategoryAssistCard.kt`
- `ui/components/ai/DedupeAssistCard.kt`
- review approval/rejection/edit flows
- AI category/receipt/dedupe assist surfaces
- pending-review repository approval path where it directly affects UI safety

---

# Executive Summary

Slice 6 is **partially fixed, with strong progress**, but it still has several critical/high-risk defects.

Good progress:
- Review approval uses `ReviewQueueRepository.approveReview()`.
- Repository approval uses status transition `PENDING -> PROCESSING`.
- `recoverStuckReviews()` exists and is called on `ReviewViewModel` init.
- Per-review mutation state exists through `_inFlightMutationKinds`.
- Edit dialog is no longer closed immediately; it closes on `_editApproveSuccess`.
- AI assist state is per-review.
- AI assist has in-flight guards per `(reviewId, assistType)`.
- Quick approve now requires explicit dedupe result `LIKELY_DISTINCT`.
- Quick approve opens a confirmation preview.
- Batch operation state is typed through `ReviewOperationState`.
- Debug menu is `BuildConfig.DEBUG` gated.
- Raw evidence panel hides purged raw text instead of showing null/blank.
- Transaction type and transfer-direction parser tests exist.

Still problematic:
1. `ReviewViewModelStressTest` appears stale and references removed properties.
2. Edit dialog can reuse stale state when switching reviews.
3. Review-card swipe uses local `processingIds` that is never cleared.
4. Swipe approve/reject visually dismisses before persistence result.
5. Edit approval double-tap guard is asynchronous.
6. AI explanation duplicate-call guard is asynchronous.
7. Edited transfer direction/account is collected in UI but never passed to repository.
8. Clearing location in the edit dialog does not actually clear review GPS.
9. Location search has no privacy gate, no debounce, and stale-result race risk.
10. Quick approve can be offered for synthetic/invalid amount reviews.
11. Category assist can apply invalid/nonexistent category IDs.
12. Review card displays null amount as `0.0`.
13. Review approval can persist fake `"EUR"` for synthetic placeholder reviews because the edit UI cannot change currency.
14. Repository validation failure can leave a review stuck in `PROCESSING`.
15. Bulk edit/approve can silently partially fail.
16. Many ViewModel/UI strings are hardcoded and error events are represented as a nullable string.

Recommended fix order:
1. Fix compile/test drift.
2. Fix repository `PROCESSING` stuck path.
3. Fix edit dialog state keying and transfer/location payload semantics.
4. Fix synthetic/null amount and fake-currency handling.
5. Remove local swipe processing state and make swipe persistence-safe.
6. Harden AI assist/quick-approve validity rules.
7. Add targeted ViewModel/repository tests.

---

# Status of Previously Known Slice 6 Invariants

## S6-PREV-001 — Approve/reject calls legal lifecycle path

**Status:** Mostly resolved, with one critical repository caveat.

Evidence:
- `ReviewViewModel.approveReview()` delegates to `reviewQueueRepository.approveReview(reviewId)`.
- `ReviewQueueRepository.approveReview()` builds `CreateExpenseRequest`.
- It delegates creation to `TransactionLifecycleCoordinator.createExpense(...)`.
- Post-creation side effects are dispatched via `dispatchPostCreationSideEffects(...)`.

Remaining critical issue:
- If coordinator returns `CreateExpenseResult.ValidationFailed`, repository returns `Result.Error`, but the review may remain `PROCESSING`.

See S6-001.

---

## S6-PREV-002 — Transfer direction parser behaves consistently

**Status:** Partially resolved.

Evidence:
- `parseTransferDirectionOrNull(raw)` exists.
- `ReviewScreenTransferDirectionParserTest` covers valid, invalid, blank, and null inputs.

Remaining issues:
- Parser is exact and case-sensitive.
- UI-collected transfer direction is not passed to repository on edited approval.
- Deposit metadata policy is inconsistent: card displays badge for `DEPOSIT`, repository allows metadata for `DEPOSIT`, edit UI only exposes transfer metadata for `TRANSFER`.

---

## S6-PREV-003 — Transaction type parser behaves consistently

**Status:** Partially resolved.

Evidence:
- `parseTransactionTypeOrNull(raw)` exists.
- `ReviewScreenTransactionTypeParserTest` covers valid, invalid, blank, null.

Remaining issues:
- Parser is exact and case-sensitive.
- Invalid historical value silently defaults edit dialog to `PURCHASE`.
- No visible warning when a stored/suggested type is invalid.

---

## S6-PREV-004 — Bulk approve is idempotent

**Status:** Partially resolved.

Evidence:
- `approveAll()` refuses to run when another global `operationState` is active.
- Repository approval has `PENDING -> PROCESSING` status transition.
- Repository duplicate handling exists.

Remaining issues:
- Row-level approve/reject/edit methods do not check `operationState`.
- `approveAllReview()` gives no per-item progress/cancel.
- Validation-failed approvals can leave items stuck in `PROCESSING`.
- Bulk edit/approve can silently partially fail.

---

## S6-PREV-005 — AI explanation/dedupe failures degrade visibly

**Status:** Mostly resolved.

Evidence:
- AI explanation uses `AiLoadState.Error`.
- Category/receipt/dedupe assist use `AiLoadState.Error`.
- Assist cards have retry UI.
- Diagnostics can be surfaced.

Remaining issues:
- Duplicate-call guards are asynchronous.
- Some errors are hardcoded raw strings.
- Failure messages may expose raw exception text.
- `NotNeeded` is sometimes represented as `Error`, which can make harmless “not needed” states look like failures.

---

# Critical / High Issues

---

## S6-001 — Repository validation failure can leave review stuck in `PROCESSING`

**Severity:** Critical  
**Files:**
- `ReviewQueueRepository.kt`
- `ReviewViewModel.kt`

## Problem

`ReviewQueueRepository.approveReview()` transitions the review:

```kotlin
PENDING -> PROCESSING
```

inside a database transaction.

Later, if `TransactionLifecycleCoordinator.createExpense(...)` returns:

```kotlin
CreateExpenseResult.ValidationFailed
```

the code records `validationError` and returns the `txAlreadyProcessed` sentinel. After the transaction, it returns `Result.Error`.

The problem is that the pending review status was already changed to `PROCESSING`, and this path does not visibly revert it to `PENDING` or mark it as failed.

## Impact

A validation failure can remove the review from the pending queue until process restart or until `recoverStuckReviews()` runs.

User-facing result:
- approve/edit shows error,
- row may disappear,
- review may be stuck in `PROCESSING`.

## Fix Strategy

Inside the transaction, if validation fails, either:

### Option A — Roll back by throwing a domain exception

```kotlin
is CreateExpenseResult.ValidationFailed -> {
    throw ReviewApprovalValidationException(result.errors)
}
```

Catch outside and return `Result.Error`.

### Option B — Explicitly revert status

```kotlin
is CreateExpenseResult.ValidationFailed -> {
    pendingReviewDao.updateStatus(reviewId, PendingReviewStatus.PENDING)
    validationError = result.errors.joinToString(", ")
    return@withTransaction txValidationFailed
}
```

Option A is safer because it rolls back every earlier write.

## Acceptance Tests

Add `ReviewQueueRepositoryApprovalValidationTest`:
- coordinator returns `ValidationFailed`;
- pending review remains `PENDING`;
- no source stats decrement persists;
- result is `Result.Error`;
- review still appears in pending queue.

---

## S6-002 — Stale ignored test likely breaks test compilation

**Severity:** Critical if test compile is currently enabled  
**File:**
- `ReviewViewModelStressTest.kt`

## Problem

`ReviewViewModelStressTest` is annotated `@Ignore`, but ignored tests still compile.

The test references:

```kotlin
viewModel.isBatchProcessing.value
viewModel.batchProgress.value
```

Current `ReviewViewModel` exposes:

```kotlin
operationState: StateFlow<ReviewOperationState?>
```

The old properties appear removed.

## Impact

If this test source is compiled, `:app:compileDebugUnitTestKotlin` can fail even though the class is ignored.

## Fix Strategy

Update tests to the new model:

```kotlin
assertNull(viewModel.operationState.value)
```

Replace:
- `isBatchProcessing`
- `batchProgress`

with:
- `operationState`
- `ReviewOperationState.current`
- `ReviewOperationState.total`
- `ReviewOperationState.canCancel`

Also remove `@Ignore` from targeted tests that are not real stress tests.

## Acceptance

```bash
./gradlew :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest --tests "*Review*"
```

---

## S6-003 — Edit dialog can reuse stale state when switching reviews

**Severity:** High  
**File:**
- `ReviewScreen.kt`

## Problem

The edit dialog is keyed by:

```kotlin
key(editPrefillCategoryId, editPrefillReceipt)
```

but not by `item.review.id`.

Inside `EditReviewDialog`, fields use:

```kotlin
remember { mutableStateOf(...) }
```

If user opens Review A, closes it, then opens Review B with the same null prefill keys, Compose may reuse the remembered field state.

## Impact

Review B can open with Review A’s:
- amount,
- merchant,
- selected date,
- selected category,
- transaction type,
- transfer metadata,
- local UI flags.

This can cause wrong data approval.

## Fix Strategy

Include review ID in the key:

```kotlin
key(item.review.id, editPrefillCategoryId, editPrefillReceipt) {
    EditReviewDialog(...)
}
```

Also use `remember(review.id, initialCategoryIdOverride, initialReceiptPrefill)` for individual fields if needed.

## Acceptance Tests

Compose test:
- open edit for review A;
- change merchant;
- close;
- open review B;
- merchant field equals review B merchant, not A edited value.

---

## S6-004 — Edited transfer direction/account is never passed to repository

**Severity:** High  
**Files:**
- `ReviewScreen.kt`
- `ReviewViewModel.kt`
- `ReviewQueueRepository.kt`

## Problem

`EditReviewDialog` collects:

```kotlin
transferDirection
transferAccount
```

and validates direction for `TRANSFER`.

But `onSave` signature does not include them:

```kotlin
onSave: (
    Double?, String?, Long?, Long?, TransactionType?,
    Boolean, Boolean,
    Double?, Double?, String?, String?
) -> Unit
```

`ReviewViewModel.approveReviewWithEdits()` also has no transfer direction/account parameters.

`ReviewQueueRepository.approveReview()` derives transfer metadata only from the original review:

```kotlin
review.suggestedDirection
review.suggestedAccountName
```

## Impact

If the user edits a review to `TRANSFER` and selects a direction/account:
- UI appears to accept the fields,
- repository ignores them,
- created expense can have missing/stale transfer metadata.

## Fix Strategy

Thread transfer metadata end-to-end:

```kotlin
onSave: (
    amount: Double?,
    merchant: String?,
    categoryId: Long?,
    date: Long?,
    type: TransactionType?,
    transferDirection: TransferDirection?,
    transferAccountName: String?,
    applyToAll: Boolean,
    approveAllPending: Boolean,
    lat: Double?,
    lon: Double?,
    address: String?,
    placeId: String?
) -> Unit
```

ViewModel:

```kotlin
fun approveReviewWithEdits(
    ...,
    finalTransferDirection: TransferDirection?,
    finalTransferAccountName: String?,
    ...
)
```

Repository:

```kotlin
suspend fun approveReview(
    ...,
    finalTransferDirection: TransferDirection? = null,
    finalTransferAccountName: String? = null
)
```

Mapping:

```kotlin
val transferDirection =
    if (transferMetadataAllowed) finalTransferDirection
        ?: review.suggestedDirection?.let(::parse)
    else null
```

## Acceptance Tests

- Editing PURCHASE -> TRANSFER with direction/account persists both.
- Editing TRANSFER -> PURCHASE clears both.
- Editing TRANSFER direction changes persisted direction.
- Invalid/missing direction blocks save with visible error.

---

## S6-005 — Clearing review location does not actually clear persisted location

**Severity:** High  
**Files:**
- `ReviewScreen.kt`
- `ReviewViewModel.kt`
- `ReviewQueueRepository.kt`

## Problem

Edit dialog supports:

```kotlin
onLocationCleared()
```

which sets selected lat/lon/address to null.

But on save:

```kotlin
locLat.takeIf { it != review.suggestedLatitude }
```

If `locLat == null`, final latitude is `null`.

In repository approval:

```kotlin
latitude = finalLatitude ?: review.suggestedLatitude
longitude = finalLongitude ?: review.suggestedLongitude
```

So `null` means “fallback to original”, not “clear”.

## Impact

User taps Clear, saves, and the created expense still receives the old GPS location.

## Fix Strategy

Use explicit tri-state location edit intent:

```kotlin
sealed interface ReviewLocationEdit {
    data object Unchanged : ReviewLocationEdit
    data object Clear : ReviewLocationEdit
    data class Set(
        val lat: Double,
        val lon: Double,
        val address: String?,
        val placeId: String?
    ) : ReviewLocationEdit
}
```

Thread this to repository.

Repository mapping:

```kotlin
when (locationEdit) {
    Unchanged -> use review.suggested*
    Clear -> null out lat/lon/address/placeId/source
    is Set -> use provided values
}
```

## Acceptance Tests

- Existing review GPS + Clear -> expense has no lat/lon.
- Existing review GPS + unchanged -> expense keeps original.
- New selected location -> expense uses selected location.
- Address/placeId are cleared together with lat/lon.

---

## S6-006 — Review card displays null amount as `0.0`

**Severity:** High  
**File:**
- `ReviewScreen.kt`

## Problem

Review card renders:

```kotlin
AmountText(amount = review.suggestedAmount ?: 0.0)
```

For synthetic placeholder reviews, `suggestedAmount == null`.

Repository approval blocks approval without a real amount, but the UI still shows a concrete-looking zero amount.

## Impact

User sees a misleading transaction:
- merchant may be Unknown,
- amount displays as zero,
- Approve button is enabled,
- approval later fails.

## Fix Strategy

Create explicit review amount UI state:

```kotlin
sealed interface ReviewAmountUi {
    data class Known(val amount: Double, val currency: String?) : ReviewAmountUi
    data object Missing : ReviewAmountUi
}
```

Render missing amount as:

```text
Amount required
```

Disable direct approve for missing amount:

```kotlin
val canDirectApprove =
    review.suggestedAmount != null &&
    review.suggestedMerchant != "Unknown"
```

Approve button should route to edit when required fields are missing.

## Acceptance Tests

- Missing amount does not display `0.0`.
- Missing amount disables direct approve.
- Missing amount shows edit-required CTA.
- Editing amount then save succeeds.

---

## S6-007 — Review approval can persist fake `"EUR"` for synthetic placeholder reviews

**Severity:** High  
**Files:**
- `ReviewQueueRepository.kt`
- `ReviewScreen.kt`

## Problem

`markAsRelevant()` creates synthetic placeholder reviews with:

```kotlin
suggestedCurrency = "EUR"
```

`EditReviewDialog` lets the user edit amount and merchant, but not currency.

`approveReview()` uses:

```kotlin
currency = finalCurrency ?: review.suggestedCurrency
```

Since UI never supplies `finalCurrency`, edited synthetic reviews keep `"EUR"` even if the real transaction currency is unknown or different.

## Impact

A synthetic review can become a persisted expense with a fake EUR currency.

## Fix Strategy

Do not use fake currency in placeholder review state.

Options:
1. Make `suggestedCurrency` nullable.
2. Add `currencyRequired` to pending review.
3. Add currency selector to `EditReviewDialog`.
4. Block approval until currency is explicit.

Repository should reject:

```kotlin
if (currency.isNullOrBlank()) {
    return Result.Error(message = "Currency required")
}
```

## Acceptance Tests

- Synthetic placeholder requires explicit currency.
- Editing amount/merchant without currency does not approve.
- Selecting USD saves USD.
- No placeholder path silently saves EUR.

---

## S6-008 — Quick approve can be offered for invalid/synthetic reviews

**Severity:** High  
**Files:**
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

## Problem

`canOfferQuickApprove(reviewId)` checks:
- quick approve enabled,
- category suggestion ready,
- categoryId > 0,
- dedupe verdict is `LIKELY_DISTINCT`.

It does not check:
- review amount exists,
- merchant is not `"Unknown"`,
- currency is valid/explicit,
- category exists in current category list,
- review is still pending.

`showQuickApprovePreview()` also uses:

```kotlin
amount = item.review.suggestedAmount ?: 0.0
```

## Impact

Quick approve preview can show `0.0` for a review that repository will reject.

## Fix Strategy

Make quick-approve eligibility require a valid review snapshot:

```kotlin
private fun PendingReview.isQuickApproveEligible(categories: List<Category>): Boolean =
    suggestedAmount != null &&
    suggestedAmount > 0.0 &&
    suggestedMerchant.isNotBlank() &&
    suggestedMerchant != "Unknown" &&
    !suggestedCurrency.isNullOrBlank() &&
    suggestedCategoryId != null &&
    categories.any { it.id == suggestedCategoryId }
```

Then combine with AI criteria.

## Acceptance Tests

- Null amount blocks quick approve.
- Unknown merchant blocks quick approve.
- Invalid category blocks quick approve.
- Missing dedupe state blocks quick approve.
- Likely duplicate blocks quick approve.
- Eligible review shows preview.

---

## S6-009 — Category assist can apply invalid category IDs

**Severity:** High  
**Files:**
- `ReviewViewModel.kt`
- `ReviewScreen.kt`
- `CategoryAssistCard.kt`

## Problem

`applyCategorySuggestion()` emits:

```kotlin
OpenEditWithPrefill(reviewId, categoryId = suggestion.categoryId)
```

without verifying:
- categoryId > 0,
- category exists in `categories`,
- suggestion is not stale,
- target review still exists.

Edit dialog then initializes:

```kotlin
selectedCategoryId = initialCategoryIdOverride ?: review.suggestedCategoryId
```

and can save `finalCategoryId = 0` or a nonexistent category.

## Fix Strategy

Validate before emitting event:

```kotlin
val categories = categories.value
if (suggestion.categoryId <= 0L ||
    categories.none { it.id == suggestion.categoryId }
) {
    _errorMessage.value = "Suggested category is no longer available."
    return
}
```

Also validate in repository or domain layer.

## Acceptance Tests

- category suggestion ID 0 is rejected.
- deleted category suggestion is rejected.
- stale suggestion for removed review is ignored.
- valid category opens edit prefilled.

---

## S6-010 — Swipe approve/reject dismisses before persistence result

**Severity:**