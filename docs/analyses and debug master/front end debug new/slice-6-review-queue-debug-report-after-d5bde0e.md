# Slice 6 Re-Debug Report — Review Queue + Notification Review UI

Commit reviewed: `d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b`  
Commit title: `fix(review): Slice 6 - S6-001/002/003/004/005/006/007/008/009 PROCESSING stuck, stale test, dialog key, transfer metadata, location clear, null amount, fake EUR, quick approve, category validation`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:  
https://github.com/panospao7/Cost-agregator/commit/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b

---

# Executive Summary

Slice 6 is **improved**, but **not closed**.

Confirmed improvements:
- Edit dialog is now keyed by `review.id`, fixing stale Compose remembered form state when switching reviews.
- Edited transfer direction/account are now threaded from `EditReviewDialog` → `ReviewViewModel` → `ReviewQueueRepository`.
- Location clear now has an explicit `locationCleared` sentinel instead of overloading `null`.
- Review card no longer displays missing amount as `0.0`; it shows `Amount required`.
- Quick approve now requires:
  - category suggestion,
  - dedupe verdict `LIKELY_DISTINCT`,
  - positive amount,
  - non-unknown merchant,
  - nonblank currency,
  - existing category.
- Category assist now validates suggested category ID before opening edit prefill.
- `ReviewViewModelStressTest` no longer references removed `isBatchProcessing` / `batchProgress` properties.
- Repository validation failure now throws inside the transaction, so the `PROCESSING` transition should roll back.

Still high-risk:
1. **Validation failure now rolls back `PROCESSING`, but the exception appears to escape `approveReview()` instead of returning `Result.Error`.**
2. **Scanned receipt link failure during review approval also throws and can escape without a user-visible result.**
3. **Fake EUR is not actually fixed for synthetic placeholders.** `markAsRelevant()` still creates placeholder reviews with `suggestedCurrency = "EUR"`, and the edit UI still has no currency selector.
4. **Direct approve for missing amount/unknown merchant may still be clickable.** The repository blocks it, but UI should route to edit instead.
5. **Location search remains ungated by privacy, undebounced, and vulnerable to stale-result writes.**
6. **AI explanation duplicate-call guard is still asynchronous and can allow duplicate calls.**
7. **Bulk edit/approve does not pass the new transfer/location-clear fields and still silently logs partial failures.**
8. **Transfer metadata is threaded but not repository-validated.**
9. **Most user-facing messages are still raw strings / exception details, not resource-backed `UiText`.**
10. **Important fixes are not locked by focused tests.**

Recommended next order:
1. Fix repository exception-to-`Result.Error` behavior while preserving rollback.
2. Remove fake EUR placeholder currency and add review currency editing/validation.
3. Disable/direct missing-data review actions to edit flow.
4. Harden location search with privacy gate, debounce, and request sequencing.
5. Finish transfer/location propagation in bulk edit paths.
6. Add repository + ViewModel tests for the fixed paths.

---

# Updated Status Table

| ID | Current Status | Notes |
|---|---|---|
| S6-001 | Partial | PROCESSING rollback improved, but thrown validation exception likely escapes instead of returning `Result.Error`. |
| S6-002 | Mostly fixed | Test compile references updated; ignored stress test still contains stale behavioral assertion. |
| S6-003 | Resolved | Edit dialog keyed by `review.id`. |
| S6-004 | Mostly fixed | Transfer direction/account threaded end-to-end for single edit approval. Repository still allows missing transfer metadata. |
| S6-005 | Mostly fixed | `locationCleared` sentinel added. Bulk approve path does not pass it. |
| S6-006 | Partial | Card shows “Amount required”; direct approve may still be available. |
| S6-007 | Unresolved | Synthetic placeholder still uses `"EUR"`; no edit currency UI. |
| S6-008 | Mostly fixed | Quick approve eligibility hardened; confirm does not fully revalidate snapshot. |
| S6-009 | Mostly fixed | Category ID validation added; artifact is marked applied when opening edit, not after save. |
| S6-010 | Unresolved | Location search still lacks privacy/debounce/stale-result protection. |
| S6-011 | Partial | Quick approve preview kept open until success, but exception paths still use raw string error. |
| S6-012 | Partial | Assist in-flight guards exist; AI explanation guard still async. |
| S6-013 | Partial | Receipt/category assist prefill uses events, but applied artifact timing is questionable. |
| S6-014 | Partial | UI events used; screen still ViewModel-coupled and hard to test. |
| S6-015 | Partial | Bulk approve summary exists; bulk edit failures still silently logged. |
| S6-016 | Unresolved | Raw strings remain widespread. |
| S6-017 | Test gap | No focused repository rollback/transfer/location/quick-approve tests confirmed. |

---

# Confirmed Fixes

## S6-FIX-D5-001 — Edit dialog stale state fixed

**Status:** Resolved  
**Files:**
- `ReviewScreen.kt`

The edit dialog now uses:

```kotlin
key(item.review.id, editPrefillCategoryId, editPrefillReceipt)
```

This should force Compose to recreate remembered edit fields when switching from Review A to Review B.

## Tests still needed

Compose test:
- open review A edit dialog;
- type edited merchant;
- close;
- open review B;
- assert review B fields are not review A’s remembered state.

---

## S6-FIX-D5-002 — Transfer metadata is passed through single edit approval

**Status:** Mostly fixed  
**Files:**
- `ReviewScreen.kt`
- `ReviewViewModel.kt`
- `ReviewQueueRepository.kt`

The edit save callback now passes:

```kotlin
finalTransferDirection
finalTransferAccountName
```

The repository prefers user-edited transfer metadata and falls back to the review suggestion.

## Remaining issue

The repository does not enforce a valid transfer direction/account when `type == TRANSFER`.

Current behavior can still create:
- `TRANSFER` with null direction,
- `TRANSFER` with blank/missing account,
- `DEPOSIT` with inconsistent transfer metadata depending on UI behavior.

## Fix

Add repository/domain validation:

```kotlin
if (type == TransactionType.TRANSFER && transferDirection == null) {
    return Result.Error(message = "Transfer direction is required.")
}
```

If account is required by product rules, validate that too.

---

## S6-FIX-D5-003 — Location clear now has explicit sentinel

**Status:** Mostly fixed  
**Files:**
- `ReviewViewModel.kt`
- `ReviewScreen.kt`
- `ReviewQueueRepository.kt`

`ReviewLocationEditState` now has:

```kotlin
locationCleared: Boolean
```

Repository maps this to:
- `latitude = null`
- `longitude = null`
- `placeId = null`
- `resolvedAddress = null`
- `locationSource = SOURCE_UNKNOWN`

This fixes the previous “null means fallback to original” bug.

## Remaining issues

- `locationCleared` is pulled from live `viewModel.locationEditState.value` in the screen save callback instead of being part of the dialog’s own save snapshot.
- bulk approval from edit does not pass `locationCleared`.
- location search remains privacy/debounce unsafe.

---

## S6-FIX-D5-004 — Missing amount no longer displays as `0.0`

**Status:** Partial  
**File:**
- `ReviewScreen.kt`

The review card now shows:

```text
Amount required
```

for `review.suggestedAmount == null`.

## Remaining issue

The primary approve action may still be available. The UI should not let a missing-amount review attempt direct approval. It should open edit.

Acceptance:
- missing amount disables direct approve;
- primary CTA is “Edit required” / “Add amount”;
- repository still blocks as defense-in-depth.

---

## S6-FIX-D5-005 — Quick approve eligibility hardened

**Status:** Mostly fixed  
**File:**
- `ReviewViewModel.kt`

`canOfferQuickApprove()` now rejects:
- missing/invalid amount,
- unknown/blank merchant,
- blank currency,
- nonexistent category,
- missing dedupe,
- dedupe verdict other than `LIKELY_DISTINCT`.

## Remaining issue

`confirmQuickApprove()` uses the previously prepared preview. It checks the feature flag, but does not fully revalidate:
- review still pending,
- amount still valid,
- merchant still valid,
- category still exists,
- dedupe result still `LIKELY_DISTINCT`.

Add revalidation at confirm time.

---

## S6-FIX-D5-006 — Category suggestion validates category existence

**Status:** Mostly fixed  
**File:**
- `ReviewViewModel.kt`

`applyCategorySuggestion()` now checks:
- `categoryId > 0`
- category exists in `categories.value`

before opening edit prefill.

## Remaining issue

If `categories` has not loaded yet, a valid suggestion can be rejected. Prefer a typed state:
- `CategoriesLoading`
- `CategoriesReady`
- `CategoriesError`

or fetch category by ID before failing.

Also, the category AI artifact is marked applied immediately after opening the edit prefill, not after the edited review is saved.

---

## S6-FIX-D5-007 — Stale test compile references updated

**Status:** Mostly fixed  
**File:**
- `ReviewViewModelStressTest.kt`

The ignored stress test now asserts:

```kotlin
assertNull(viewModel.operationState.value)
```

instead of old removed properties.

## Remaining issue

The same ignored test still contains a stale behavioral assertion: it expects `getReviewById()` not to be called on duplicate edit approval, but `approveReviewWithEdits()` now fetches the original review before approval.

Because the class is `@Ignore`, it will compile but not run. If unignored, it may fail.

---

# New / Remaining Issues

---

## S6-D5-001 — Validation failure rollback is fixed, but error likely escapes repository

**Severity:** Critical  
**Files:**
- `ReviewQueueRepository.kt`
- `ReviewViewModel.kt`

## Problem

The repository now handles:

```kotlin
CreateExpenseResult.ValidationFailed
```

by throwing inside the DB transaction. This is good for rollback.

But there is no visible outer catch in `approveReview()` converting that thrown validation failure back into:

```kotlin
Result.Error(...)
```

`ReviewViewModel.approveReview()` also has only `try/finally`, not `catch`, around repository approval.

## Impact

A validation failure can:
- rollback `PROCESSING` correctly,
- but fail the coroutine,
- not show a user-facing error,
- leave edit dialog open with no inline error,
- rely on Timber/logcat only.

## Fix Strategy

Use a domain exception internally:

```kotlin
class ReviewApprovalValidationException(
    val errors: List<String>
) : RuntimeException(errors.joinToString(", "))
```

Inside transaction:

```kotlin
is CreateExpenseResult.ValidationFailed -> {
    throw ReviewApprovalValidationException(result.errors)
}
```

Outside transaction:

```kotlin
val txnResult = try {
    database.withTransaction { ... }
} catch (e: CancellationException) {
    throw e
} catch (e: ReviewApprovalValidationException) {
    return Result.Error(message = e.errors.joinToString(", "))
} catch (e: Exception) {
    return Result.Error(exception = e, message = e.message ?: "Approval failed")
}
```

## Acceptance Tests

- coordinator validation failure returns `Result.Error`;
- review remains `PENDING`;
- no source stats decrement persists;
- ViewModel exposes visible error;
- coroutine does not crash.

---

## S6-D5-002 — Scanned-receipt link failure during review approval can escape similarly

**Severity:** High  
**File:**
- `ReviewQueueRepository.kt`

## Problem

During approval, if the review has `scannedReceiptId`, the repository calls:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
```

If link fails, it throws inside the transaction.

That should rollback, but again there is no visible outer conversion to `Result.Error`.

## Impact

A receipt-linked review approval failure may:
- rollback DB writes,
- but not show a proper UI error,
- keep the review pending with unclear UX.

## Fix Strategy

Same as S6-D5-001:
- throw internally for rollback,
- catch outside and return typed `Result.Error`.

Also add specific error message:
- “Expense was not created because the receipt could not be linked.”

---

## S6-D5-003 — Fake EUR for synthetic placeholder is not fixed

**Severity:** High  
**Files:**
- `ReviewQueueRepository.kt`
- `ReviewScreen.kt`

## Problem

`markAsRelevant()` still creates synthetic placeholder reviews with:

```kotlin
suggestedCurrency = "EUR"
```

The repository approval change only rejects blank/null currency:

```kotlin
finalCurrency ?: review.suggestedCurrency
```

So a placeholder can still persist EUR if the user edits amount/merchant but cannot edit currency.

## Impact

Synthetic placeholder reviews can still become real expenses with fake EUR.

## Fix Strategy

1. Make synthetic placeholder currency explicit unknown:
   ```kotlin
   suggestedCurrency = null
   ```
2. Add currency picker to `EditReviewDialog`.
3. Thread `finalCurrency` through:
   - `EditReviewDialog`
   - `ReviewScreen`
   - `ReviewViewModel.approveReviewWithEdits`
   - `ReviewQueueRepository.approveReview`
4. Block approval when currency is unknown.

## Acceptance Tests

- synthetic placeholder cannot approve without explicit currency;
- selecting USD saves USD;
- no path persists default EUR from placeholder;
- quick approve never appears for placeholder currency.

---

## S6-D5-004 — Direct approve still likely available for missing required fields

**Severity:** High  
**Files:**
- `ReviewScreen.kt`
- `ReviewViewModel.kt`

## Problem

The card display now says “Amount required,” but the direct approve action is likely still available.

The repository blocks:
- null amount,
- unknown merchant,

but the UI should not send users into an avoidable error path.

## Fix Strategy

Add UI eligibility:

```kotlin
val canDirectApprove =
    review.suggestedAmount != null &&
    review.suggestedAmount > 0.0 &&
    review.suggestedMerchant.isNotBlank() &&
    review.suggestedMerchant != "Unknown" &&
    !review.suggestedCurrency.isNullOrBlank()
```

If false:
- disable Approve, or
- replace with “Edit required” CTA.

## Acceptance Tests

- null amount review cannot direct approve;
- unknown merchant review cannot direct approve;
- edit CTA opens dialog;
- after editing amount/merchant/currency, approval works.

---

## S6-D5-005 — Bulk approve-from-edit does not pass new transfer/location-clear fields

**Severity:** High  
**File:**
- `ReviewViewModel.kt`

## Problem

`approveReviewWithEdits(... approveAllPending = true)` loops over identical pending reviews and calls `reviewQueueRepository.approveReview(...)`.

That call passes:
- merchant,
- category,
- date,
- type,
- lat/lon/address/placeId,

but does **not** pass:
- `finalTransferDirection`,
- `finalTransferAccountName`,
- `locationCleared`.

## Impact

Bulk approval can produce different data than the edited primary review:
- transfer metadata missing/stale,
- location clear not applied,
- inconsistent transaction rows.

## Fix Strategy

Pass all edited fields:

```kotlin
reviewQueueRepository.approveReview(
    reviewId = pending.id,
    finalMerchant = finalMerchant,
    finalCategoryId = finalCategoryId,
    finalDate = finalDate,
    finalType = finalType,
    finalTransferDirection = finalTransferDirection,
    finalTransferAccountName = finalTransferAccountName,
    locationCleared = locationCleared,
    finalLatitude = finalLatitude,
    finalLongitude = finalLongitude,
    finalAddress = finalAddress,
    finalPlaceId = finalPlaceId
)
```

## Acceptance Tests

- bulk approving transfer edits persists transfer direction/account for each row;
- bulk location clear clears all targeted rows;
- failures are reported per row.

---

## S6-D5-006 — Bulk edit/apply failures are still silently logged

**Severity:** Medium/High  
**File:**
- `ReviewViewModel.kt`

## Problem

After primary edit approval succeeds:
- `applyToAll` bulk category/merchant updates catch and only log errors.
- `approveAllPending` catches and only logs errors.

The edit dialog closes because `_editApproveSuccess` is emitted before or independent of these partial failures.

## Impact

User thinks the bulk action succeeded, but only the primary review may have been applied.

## Fix Strategy

Use a typed bulk result:

```kotlin
data class ReviewBulkEditResult(
    val primaryApproved: Boolean,
    val updatedCount: Int,
    val failedCount: Int,
    val failures: List<ReviewBulkFailure>
)
```

Emit visible snackbar/dialog:
- “Approved primary review, but 3 related reviews failed.”

## Acceptance Tests

- bulk category failure produces visible warning;
- bulk approve failure produces visible warning;
- primary success remains successful;
- user can retry failed rows.

---

## S6-D5-007 — Location search still lacks privacy gate, debounce, and stale-result guard

**Severity:** High  
**File:**
- `ReviewViewModel.kt`

## Problem

`onLocationQueryChanged()` directly calls:

```kotlin
geocodingService.searchMultiple(query)
```

for each query length >= 3.

Missing:
- PrivacyGate check for external geocoding;
- debounce;
- `distinctUntilChanged`;
- cancellation of previous request;
- request sequence check.

## Impact

- External geocoding may run when privacy settings deny it.
- Rapid typing can spam geocoding.
- Slow old query results can overwrite newer query results.
- Errors expose raw exception message.

## Fix Strategy

Use a query flow:

```kotlin
private val locationQuery = MutableStateFlow("")
```

Pipeline:

```kotlin
locationQuery
    .debounce(300)
    .distinctUntilChanged()
    .flatMapLatest { query ->
        flow {
            val decision = privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
            if (decision.blocksExecution()) emit(LocationSearchState.Blocked(...))
            else emit(geocodingService.searchMultiple(query))
        }
    }
```

## Acceptance Tests

- privacy denied does not call geocoding service;
- typing “athens” quickly performs one search;
- slow “ath” result cannot overwrite “athens”;
- cancellation does not show error.

---

## S6-D5-008 — AI explanation duplicate guard is still asynchronous

**Severity:** Medium/High  
**File:**
- `ReviewViewModel.kt`

## Problem

`loadAiExplanation()` checks:

```kotlin
if (_inFlightExplanations.contains(reviewId)) return
```

but only adds the review ID **inside** the launched coroutine, after the settings check.

Two synchronous calls can launch two coroutines before the set is updated.

The stress test comments even describe this behavior.

## Fix Strategy

Move add before launch:

```kotlin
if (!_inFlightExplanations.add(reviewId)) return
viewModelScope.launch {
    try { ... }
    finally { _inFlightExplanations.remove(reviewId) }
}
```

If AI is disabled, remove it in `finally`.

## Acceptance Tests

- two immediate `loadAiExplanation(reviewId)` calls invoke use case once;
- disabled path clears in-flight set;
- exception path clears in-flight set.

---

## S6-D5-009 — `CreateExpenseResult.ValidationFailed` path leaves dead `validationError` variable

**Severity:** Low/Medium  
**File:**
- `ReviewQueueRepository.kt`

## Problem

`validationError` remains:

```kotlin
var validationError: String? = null
...
if (validationError != null) return Result.Error(...)
```

But the code now throws instead of assigning it.

## Fix Strategy

Remove `validationError` and handle validation with a typed exception/catch.

---

## S6-D5-010 — Transfer parser remains case-sensitive and UI/repository policies differ

**Severity:** Medium  
**Files:**
- `ReviewScreen.kt`
- `ReviewQueueRepository.kt`

## Problem

Repository fallback parses:

```kotlin
TransferDirection.valueOf(review.suggestedDirection)
```

This is exact/case-sensitive.

Also:
- repository allows transfer metadata for `TRANSFER` and `DEPOSIT`;
- original review report noted edit UI may only expose transfer metadata for `TRANSFER`;
- UI passes metadata for `DEPOSIT` if local variables exist, but it is not clear the UI actually shows required controls for deposit.

## Fix Strategy

Centralize parser:

```kotlin
fun parseTransferDirectionOrNull(raw: String?): TransferDirection?
```

Use it everywhere, with case-normalization.

Define product policy:
- Should `DEPOSIT` have transfer direction/account?
- If yes, expose/validate in UI.
- If no, repository should clear metadata for `DEPOSIT`.

---

## S6-D5-011 — Category assist marks artifact applied before final edit save

**Severity:** Medium  
**File:**
- `ReviewViewModel.kt`

## Problem

`applyCategorySuggestion()`:
1. validates category,
2. emits edit prefill event,
3. immediately calls `markCategoryArtifactApplied(reviewId)`.

If user opens edit and cancels, artifact is marked applied even though no persisted review/expense used it.

## Fix Strategy

Track pending applied AI capability in edit state and mark applied only after successful approval.

Pattern:

```kotlin
data class PendingReviewEditDraft(
    val appliedAiCapabilities: Set<AiCapability>
)
```

On edit approval success:
```kotlin
markCategoryArtifactApplied(reviewId)
```

## Acceptance Tests

- apply category suggestion + cancel does not mark applied;
- apply + save success marks applied;
- save failure does not mark applied.

---

## S6-D5-012 — Quick approve confirm does not fully revalidate preview

**Severity:** Medium  
**File:**
- `ReviewViewModel.kt`

## Problem

`requestQuickApprovePreview()` validates eligibility, but `confirmQuickApprove()` only checks feature flag and uses cached preview.

Between preview and confirm:
- review can be deleted/approved elsewhere;
- category can be deleted;
- dedupe state can change;
- amount/merchant/currency can become invalid.

## Fix Strategy

At confirm:
```kotlin
if (!canOfferQuickApprove(preview.reviewId)) {
    _quickApprovePreview.value = null
    _errorMessage.value = "Review changed. Please review it again."
    return
}
```

Also validate preview category still exists.

---

## S6-D5-013 — Raw strings and exception messages remain widespread

**Severity:** Medium  
**Files:**
- `ReviewViewModel.kt`
- `ReviewQueueRepository.kt`
- `ReviewScreen.kt`

Examples:
- `"Duplicate transaction detected"`
- `"Failed to approve: ..."`
- `"Suggested category is no longer available."`
- `"Quick approve failed: ${e.message}"`
- `"Imported X transactions from statement"`
- `"Currency is required..."`

## Fix Strategy

Use `UiText`:
```kotlin
val errorMessage: StateFlow<UiText?>
```

Known errors should use `UiText.StringResource`. Log exception details with Timber, do not show raw exception text.

---

# Updated Implementation Plan

## Phase 1 — Repository result safety

Files:
- `ReviewQueueRepository.kt`
- `ReviewViewModel.kt`
- tests

Steps:
1. Add `ReviewApprovalValidationException`.
2. Throw inside transaction only for rollback.
3. Catch outside transaction and return `Result.Error`.
4. Rethrow `CancellationException`.
5. Convert receipt-link failure to `Result.Error`.
6. Remove dead `validationError`.

Acceptance:
- no coroutine crash for validation/link failure;
- review remains pending;
- visible error emitted.

---

## Phase 2 — Remove fake EUR placeholder approval

Files:
- `ReviewQueueRepository.kt`
- `PendingReview` if needed
- `ReviewScreen.kt`
- `ReviewViewModel.kt`

Steps:
1. Stop creating synthetic placeholders with `"EUR"`.
2. Add currency edit UI.
3. Thread `finalCurrency`.
4. Require explicit currency for placeholder reviews.
5. Add tests.

Acceptance:
- no synthetic placeholder can approve with fake EUR.

---

## Phase 3 — Required-field UI gating

Files:
- `ReviewScreen.kt`
- `ReviewViewModel.kt`

Steps:
1. Add `canDirectApprove(review)`.
2. Disable direct approve for null amount/unknown merchant/missing currency.
3. Show “Edit required” CTA.
4. Keep repository validation as defense.

---

## Phase 4 — Bulk edit correctness

Files:
- `ReviewViewModel.kt`

Steps:
1. Pass transfer metadata and `locationCleared` to bulk approvals.
2. Report partial failures.
3. Add typed bulk result/event.
4. Add tests.

---

## Phase 5 — Location search hardening

Files:
- `ReviewViewModel.kt`
- privacy/domain services

Steps:
1. Add privacy preflight for external geocoding.
2. Add debounce/distinct.
3. Use `flatMapLatest` or request sequence.
4. Use `UiText` for errors.

---

## Phase 6 — AI/action hardening

Files:
- `ReviewViewModel.kt`

Steps:
1. Move AI explanation in-flight add before coroutine launch.
2. Mark category artifact applied only after successful save.
3. Revalidate quick approve on confirm.
4. Add tests.

---

# Recommended Tests

## `ReviewQueueRepositoryApprovalRollbackTest`

Cases:
- validation failure returns `Result.Error`;
- pending review remains `PENDING`;
- no accepted/decremented stats persist;
- no side effects dispatch;
- receipt link failure returns `Result.Error` and rolls back.

## `ReviewSyntheticPlaceholderCurrencyTest`

Cases:
- synthetic placeholder has no explicit currency;
- approval without final currency fails;
- edit with USD saves USD;
- no fake EUR persisted.

## `ReviewEditTransferLocationTest`

Cases:
- edited transfer direction/account persist;
- transfer without direction rejected;
- clearing location persists null lat/lon/place/address;
- unchanged location keeps original;
- bulk approve passes transfer/location fields.

## `ReviewRequiredFieldsUiTest`

Cases:
- null amount shows `Amount required`;
- null amount disables direct approve;
- unknown merchant disables direct approve;
- edit CTA opens dialog.

## `ReviewLocationSearchTest`

Cases:
- privacy denied prevents geocoding call;
- rapid query debounces;
- stale result ignored;
- failure emits resource-backed error.

## `ReviewAiGuardTest`

Cases:
- duplicate explanation request calls use case once;
- category suggestion apply+cancel does not mark applied;
- category suggestion apply+save marks applied.

## `ReviewQuickApproveTest`

Cases:
- invalid review blocks preview;
- category deleted before confirm cancels approval;
- review approved elsewhere before confirm shows changed-state error;
- successful confirm marks artifacts applied.

---

# Final Severity Table After `d5bde0e`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S6-D5-001 | Critical | New/Unresolved | Validation failure rolls back but likely escapes instead of returning `Result.Error` |
| S6-D5-002 | High | New/Unresolved | Receipt link failure can escape similarly |
| S6-D5-003 | High | Unresolved | Synthetic placeholder still uses fake `"EUR"` |
| S6-D5-004 | High | Partial | Missing amount shown, but direct approve likely still active |
| S6-D5-005 | High | Partial | Bulk approve does not pass new transfer/location-clear fields |
| S6-D5-006 | Med/High | Unresolved | Bulk edit/apply failures silently logged |
| S6-D5-007 | High | Unresolved | Location search lacks privacy/debounce/stale guard |
| S6-D5-008 | Med/High | Unresolved | AI explanation duplicate guard still async |
| S6-D5-009 | Low/Med | Cleanup | Dead `validationError` variable |
| S6-D5-010 | Medium | Partial | Transfer parser/policy still inconsistent |
| S6-D5-011 | Medium | Unresolved | Category artifact marked applied before edit save |
| S6-D5-012 | Medium | Partial | Quick approve confirm does not fully revalidate |
| S6-D5-013 | Medium | Unresolved | Raw strings and exception messages remain |

---

# Immediate Agent Task List

## Task A — Fix repository exception handling
Keep transaction rollback, but return `Result.Error` to ViewModel.

## Task B — Remove fake EUR from synthetic reviews
Require explicit currency and add edit currency control.

## Task C — Gate direct approve by required fields
Missing amount/merchant/currency should open edit, not attempt approval.

## Task D — Fix bulk edit propagation
Pass transfer metadata and location-clear flag; report partial failures.

## Task E — Harden location search
Privacy gate + debounce + stale request protection.

## Task F — Add tests
Start with repository rollback, synthetic currency, transfer/location edit, and quick approve validation.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b
- Commit patch: https://github.com/panospao7/Cost-agregator/commit/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b.patch
- `ReviewQueueRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
- `ReviewViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
- `ReviewScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt
- `ReviewViewModelStressTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d5bde0e093bce28cd17fce1cb5eec0d1da44ff9b/app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelStressTest.kt