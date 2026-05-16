# Slice 6 Re-Debug Report — Review Queue + Notification Review UI

Commit reviewed: `f3cefa7111e7cb75264769cf9dde8c2666ed4976`  
Commit title: `fix(review): Slice 6 review2 - S6-D5-001/002/004/005/007/008/009/011/012 repo exception handling, direct approve gate, bulk transfer, location debounce+privacy, AI guard, quick approve revalidation`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:  
https://github.com/panospao7/Cost-agregator/commit/f3cefa7111e7cb75264769cf9dde8c2666ed4976

---

# Executive Summary

Slice 6 is **significantly improved**, but **still not closed**.

Confirmed improvements:
- Repository approval exceptions inside the approval transaction are now caught outside the transaction and returned as `Result.Error`.
- `CancellationException` is rethrown in repository approval.
- Review approval now uses `SideEffectMode.DEFER` and dispatches lifecycle side effects after the transaction.
- Direct Approve button now routes missing amount / unknown merchant reviews to Edit instead of calling approve.
- Bulk approval from edit now passes transfer direction/account and `locationCleared`.
- Location search is now debounced, privacy-gated, and uses `flatMapLatest`.
- AI explanation in-flight guard now adds the review ID before launching the coroutine.
- `validationError` dead variable was removed.
- Category artifact application was moved from “open edit prefill” to “after successful edit approval.”
- Quick approve confirmation now revalidates eligibility before approving.

Still high-risk:
1. **Synthetic placeholder fake EUR remains unresolved.**
2. **Swipe approve still bypasses the new direct-approve gate and visually dismisses before persistence.**
3. **`processingIds` local swipe state is still never cleared.**
4. **Direct approve gate checks amount/merchant but not currency.**
5. **Edit dialog still cannot edit currency, despite repository accepting `finalCurrency`.**
6. **Receipt-assist artifact is still marked applied immediately when opening edit prefill.**
7. **Bulk apply/approve failures are still only logged.**
8. **Location privacy denial loses typed reason and geocoding failures silently become empty results.**
9. **Transfer metadata still lacks repository-level validation for `TRANSFER`.**
10. **Raw strings and exception messages remain widespread.**
11. **No focused tests are visible for the new fixes.**

Recommended next order:
1. Remove fake EUR placeholder and add currency editing.
2. Fix swipe approve/reject lifecycle.
3. Add repository validation for transfer metadata.
4. Make bulk edit result visible.
5. Convert location/approval errors to typed `UiText`.
6. Add tests for rollback, direct approve gate, swipe behavior, location privacy, and quick approve.

---

# Updated Status Table

| ID | Status after `f3cefa7` | Notes |
|---|---|---|
| S6-D5-001 | Mostly fixed | Approval validation/link exceptions now return `Result.Error` and rollback. Still raw exception message. |
| S6-D5-002 | Mostly fixed | Receipt-link failure now returns `Result.Error`; UX still generic. |
| S6-D5-003 | Unresolved | Synthetic placeholder still stores `suggestedCurrency = "EUR"`. |
| S6-D5-004 | Partial | Button routes invalid reviews to Edit, but swipe bypasses and currency is not checked. |
| S6-D5-005 | Mostly fixed | Bulk approve now passes transfer/location-clear fields. Bulk failure reporting still weak. |
| S6-D5-006 | Unresolved | Bulk edit/apply failures still only logged. |
| S6-D5-007 | Partial | Location search debounced/privacy-gated; error modeling still weak. |
| S6-D5-008 | Mostly fixed | AI explanation guard moved before launch. Settings-read failure edge remains. |
| S6-D5-009 | Resolved | Dead `validationError` removed. |
| S6-D5-010 | Partial | Transfer metadata threaded but parser/policy/validation still inconsistent. |
| S6-D5-011 | Partial | Category artifact delayed until save; receipt artifact still applied before save. |
| S6-D5-012 | Mostly fixed | Quick approve confirm revalidates. Preview data still primitive/stale-prone. |
| S6-D5-013 | Unresolved | Raw strings remain widespread. |
| S6-SWIPE | High unresolved | Swipe approval still dismisses before persistence and bypasses required-field gate. |

---

# Confirmed Fixes

## S6-F3-001 — Repository exceptions now rollback and return `Result.Error`

**Status:** Mostly resolved  
**File:** `ReviewQueueRepository.kt`

The approval transaction is now wrapped:

```kotlin
val txnResult: Long = try {
    database.withTransaction { ... }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.e(e, "Review approval failed for reviewId=$reviewId")
    return Result.Error(message = e.message ?: "Approval failed")
}
```

This fixes the previous problem where validation/link exceptions could rollback but escape the repository coroutine.

## Remaining issue

The user-facing message still uses raw `e.message`.

Better:
- map known exceptions to resource-backed `UiText`;
- log raw exception details only with Timber.

---

## S6-F3-002 — Approval side effects are now deferred until post-commit

**Status:** Improved  
**File:** `ReviewQueueRepository.kt`

Approval now calls:

```kotlin
transactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)
```

and later dispatches:

```kotlin
transactionLifecycleCoordinator.dispatchPostCreationSideEffects(...)
```

after the transaction.

This is the right direction and prevents side effects from firing before receipt link / approval transaction success.

## Remaining issue

The call is still suppressed with:

```kotlin
@Suppress("DEPRECATION_ERROR") // TODO: migrate to createExpenseDbOnly()
```

Recommended:
- replace deprecated path with explicit `createExpenseDbOnly()` or equivalent coordinator API.

---

## S6-F3-003 — Direct approve button routes missing required fields to Edit

**Status:** Partial  
**File:** `ReviewScreen.kt`

The button now computes:

```kotlin
canDirectApprove =
    suggestedAmount != null &&
    suggestedAmount > 0.0 &&
    suggestedMerchant.isNotBlank() &&
    suggestedMerchant != "Unknown"
```

If false, button text becomes `Edit Required` and `onClick` calls `onEdit()`.

## Remaining issues

- Currency is not checked.
- `" unknown "` / lowercase `"unknown"` are not normalized.
- Swipe approve bypasses this gate entirely.
- Repository still needs defense-in-depth, which it has for amount/merchant but not fake EUR.

---

## S6-F3-004 — Bulk approve from edit passes transfer/location fields

**Status:** Mostly resolved  
**File:** `ReviewViewModel.kt`

The `approveAllPending` loop now passes:
- `finalTransferDirection`
- `finalTransferAccountName`
- `locationCleared`
- lat/lon/address/placeId

This fixes the earlier bug where bulk approvals could diverge from the edited primary review.

## Remaining issue

Failures are still caught and logged only. The user does not get a partial-failure summary.

---

## S6-F3-005 — Location search is debounced and privacy-gated

**Status:** Partial  
**File:** `ReviewViewModel.kt`

New pipeline:
- `_locationQueryFlow`
- `debounce(300L)`
- `distinctUntilChanged()`
- `flatMapLatest`
- `PrivacyGate.check(EXTERNAL_GEOCODING)`

This addresses the previous direct geocoding spam and privacy-bypass risk.

## Remaining issues

- Denial reason is replaced by generic raw string.
- `FailClosed` vs `Denied` is not visible.
- `GeocodingBatchResult.Failure` silently becomes empty results.
- Search error state is `String?`, not `UiText?`.
- No visible tests prove debounce/privacy/stale behavior.

---

## S6-F3-006 — AI explanation duplicate guard improved

**Status:** Mostly resolved  
**File:** `ReviewViewModel.kt`

`_inFlightExplanations.add(reviewId)` now happens before coroutine launch, preventing two immediate calls from both launching.

## Remaining issue

The settings check happens before the `try/finally`. If `settings().first()` throws, the in-flight set may not be cleared.

Recommended:

```kotlin
if (!_inFlightExplanations.add(reviewId)) return
viewModelScope.launch {
    try {
        val settings = aiSettingsRepository.settings().first()
        ...
    } finally {
        _inFlightExplanations.remove(reviewId)
    }
}
```

---

## S6-F3-007 — Category artifact now marked after edit save

**Status:** Partial  
**File:** `ReviewViewModel.kt`

`applyCategorySuggestion()` no longer immediately marks the category artifact applied.  
`approveReviewWithEdits()` now calls `markCategoryArtifactApplied(reviewId)` after successful save when `finalCategoryId != null`.

## Remaining issue

This marks the category artifact applied for **any** saved category edit, even if the user did not use the AI category suggestion.

Better:
- carry a pending applied AI capability in the edit prefill event/draft;
- mark only if the saved edit actually came from AI prefill.

---

## S6-F3-008 — Quick approve confirm revalidates

**Status:** Mostly resolved  
**File:** `ReviewViewModel.kt`

`confirmQuickApprove()` now calls:

```kotlin
if (!canOfferQuickApprove(preview.reviewId)) {
    _quickApprovePreview.value = null
    _errorMessage.value = "Review changed since preview. Please review it again."
    return
}
```

This fixes the stale-preview class of bugs.

## Remaining issue

Messages are raw strings. Also preview still carries primitive data rather than a validated immutable snapshot.

---

# Remaining / New Issues

---

## S6-F3-001 — Synthetic placeholder still persists fake EUR

**Severity:** Critical/High  
**File:** `ReviewQueueRepository.kt`

## Problem

Synthetic placeholder reviews still use:

```kotlin
suggestedCurrency = "EUR"
```

Repository approval accepts:

```kotlin
finalCurrency ?: review.suggestedCurrency
```

The edit dialog still does not expose a currency selector and `ReviewScreen` does not pass `finalCurrency`.

Therefore:
1. parser fails,
2. placeholder review is created with amount `null`, merchant `"Unknown"`, currency `"EUR"`,
3. user edits amount + merchant,
4. approval persists `"EUR"` even if true currency is unknown/different.

## Fix Strategy

1. Change placeholder currency to `null` or `"UNKNOWN"` if schema requires non-null.
2. Add currency field to edit dialog.
3. Thread `finalCurrency` through:
   - `EditReviewDialog`
   - `ReviewScreen`
   - `ReviewViewModel.approveReviewWithEdits`
   - `ReviewQueueRepository.approveReview`
4. Require explicit currency for placeholders.

## Acceptance Tests

- synthetic placeholder cannot approve without explicit currency.
- editing amount/merchant but not currency still fails.
- selecting USD saves USD.
- no placeholder path silently persists EUR.

---

## S6-F3-002 — Swipe approve bypasses required-field gate

**Severity:** High  
**File:** `ReviewScreen.kt`

## Problem

The new gate only applies to the button inside `ReviewCard`.

Swipe approve still does:

```kotlin
viewModel.approveReview(item.review.id)
return true
```

for every review.

This means:
- missing amount,
- unknown merchant,
- fake EUR placeholder,
- missing currency,

can still be swiped into approval attempt.

The repository may reject, but the UI already dismissed the card.

## Fix Strategy

Use the same eligibility function for swipe.

```kotlin
if (!canDirectApprove(item.review)) {
    editingReview = item
    return@rememberSwipeToDismissBoxState false
}
```

Better:
- disable swipe approve for invalid reviews;
- show snackbar “Edit required first.”

## Acceptance Tests

- missing amount swipe opens edit or is blocked.
- unknown merchant swipe opens edit or is blocked.
- valid review swipe approves.
- swipe does not dismiss invalid row.

---

## S6-F3-003 — Swipe still visually dismisses before persistence success

**Severity:** High  
**File:** `ReviewScreen.kt`

## Problem

`confirmValueChange` returns `true` immediately after calling `approveReview()` / `rejectReview()`.

So the row visually dismisses before:
- approval result,
- repository validation,
- duplicate result,
- link failure result.

If approval fails, user sees the row vanish or become stuck.

## Fix Strategy

Return `false` from swipe and trigger mutation separately.

```kotlin
confirmValueChange = { value ->
    when (value) {
        StartToEnd -> {
            viewModel.approveReview(id)
            false
        }
        EndToStart -> {
            viewModel.rejectReview(id)
            false
        }
        else -> false
    }
}
```

The row will disappear naturally when DB flow removes it after success.

## Acceptance Tests

- approve failure keeps row visible.
- reject failure keeps row visible.
- success removes row via pending-review flow.
- duplicate result shows visible message.

---

## S6-F3-004 — `processingIds` is still never cleared

**Severity:** Medium/High  
**File:** `ReviewScreen.kt`

## Problem

`processingIds` is a local `mutableStateListOf<Long>()`.

Rows are added on swipe:

```kotlin
processingIds.add(item.review.id)
```

but there is no visible removal on:
- success,
- error,
- duplicate,
- cancellation,
- list refresh.

## Impact

If a mutation fails and the row remains, it may become permanently unswipeable.

## Fix Strategy

Remove local `processingIds` entirely and use ViewModel mutation state:

```kotlin
val isMutating = inFlightMutationKinds[item.review.id] != null
```

Or remove ID after a terminal event.

---

## S6-F3-005 — Direct approve gate does not check currency

**Severity:** High  
**File:** `ReviewScreen.kt`

## Problem

`canDirectApprove` checks amount and merchant only.

It should also require:

```kotlin
!review.suggestedCurrency.isNullOrBlank()
```

But note: fake `"EUR"` placeholder still passes this until S6-F3-001 is fixed.

## Fix Strategy

After removing fake EUR placeholder:

```kotlin
val canDirectApprove =
    amount valid &&
    merchant valid &&
    currency explicit &&
    review.extractionState != SYNTHETIC_PLACEHOLDER
```

## Acceptance Tests

- missing currency shows Edit Required.
- placeholder review shows Edit Required.
- valid normal review can approve.

---

## S6-F3-006 — Transfer metadata still lacks repository validation

**Severity:** High  
**File:** `ReviewQueueRepository.kt`

## Problem

The repository threads transfer direction/account, but does not enforce required metadata when:

```kotlin
type == TransactionType.TRANSFER
```

It allows:

```kotlin
transferDirection = null
```

if neither final nor suggested direction parses.

## Fix Strategy

Define domain policy:

```kotlin
if (type == TransactionType.TRANSFER && transferDirection == null) {
    return Result.Error(message = "Transfer direction is required.")
}
```

If account name is mandatory:

```kotlin
if (type == TRANSFER && transferAccountName.isNullOrBlank()) ...
```

Also normalize parser case:

```kotlin
review.suggestedDirection?.uppercase()?.let(TransferDirection::valueOf)
```

## Acceptance Tests

- edited transfer without direction fails.
- valid transfer direction/account persists.
- changing transfer to purchase clears metadata.
- deposit policy is explicitly tested.

---

## S6-F3-007 — Receipt assist artifact is still marked applied before save

**Severity:** Medium/High  
**File:** `ReviewViewModel.kt`

## Problem

`applyReceiptSuggestion()` still does:

```kotlin
_uiEvents.tryEmit(OpenEditWithPrefill(...))
viewModelScope.launch {
    val receiptId = ...
    if (receiptId != null) markReceiptArtifactApplied(receiptId)
}
```

So user can:
1. apply receipt AI suggestion,
2. open edit,
3. cancel,
4. artifact is still marked applied.

Category suggestion was fixed; receipt suggestion was not.

## Fix Strategy

Carry pending applied capability in the edit draft/event:

```kotlin
OpenEditWithPrefill(
    reviewId = reviewId,
    receiptPrefill = prefill,
    pendingAppliedCapabilities = setOf(AiCapability.RECEIPT_EXTRACTION)
)
```

Mark applied only after successful edit approval.

## Acceptance Tests

- apply receipt suggestion + cancel does not mark applied.
- apply + save success marks applied.
- save failure does not mark applied.

---

## S6-F3-008 — Category artifact marking is over-broad

**Severity:** Medium  
**File:** `ReviewViewModel.kt`

## Problem

After successful edit save:

```kotlin
if (finalCategoryId != null) markCategoryArtifactApplied(reviewId)
```

This marks category AI applied even when:
- user manually selected a category,
- category came from existing suggestion,
- no category AI suggestion was applied.

## Fix Strategy

Track whether the edit dialog was opened from category AI suggestion.

Example:

```kotlin
data class PendingEditAiApplication(
    val reviewId: Long,
    val capabilities: Set<AiCapability>
)
```

Only mark those capabilities after success.

---

## S6-F3-009 — Bulk edit/apply failures are still silently logged

**Severity:** Medium/High  
**File:** `ReviewViewModel.kt`

## Problem

Bulk operations inside `approveReviewWithEdits()` still catch and log:

```kotlin
Timber.e(e, "Failed to apply bulk category update")
Timber.e(e, "Failed to apply bulk approval")
```

No visible warning is emitted.

## Impact

User sees the primary edit succeed and dialog close, but related reviews may not update/approve.

## Fix Strategy

Emit a typed event:

```kotlin
ReviewBulkEditResult(
    primaryApproved = true,
    updatedCount = ...,
    failedCount = ...,
    failures = ...
)
```

At minimum:

```kotlin
_errorMessage.value = "Primary review approved, but related reviews failed."
```

## Acceptance Tests

- bulk category failure is visible.
- bulk approve partial failure is visible.
- failed review IDs are retained for retry.

---

## S6-F3-010 — Location search privacy denial loses reason

**Severity:** Medium  
**File:** `ReviewViewModel.kt`

## Problem

Privacy denial currently maps to:

```kotlin
"Location search is disabled by privacy settings."
```

It discards:
- actual denial reason,
- `Denied` vs `FailClosed`,
- capability context.

## Fix Strategy

Store typed privacy state:

```kotlin
data class ReviewLocationEditState(
    val privacyBlocked: PrivacyBlocked? = null,
    val error: UiText? = null
)
```

Use:

```kotlin
decision.reason()
```

or a `PrivacyDecisionUiMapper`.

## Acceptance Tests

- Denied geocoding shows denial reason.
- FailClosed geocoding shows safe failure reason.
- geocoding service is not called.

---

## S6-F3-011 — Geocoding service failure silently becomes empty results

**Severity:** Medium  
**File:** `ReviewViewModel.kt`

## Problem

`GeocodingBatchResult.Failure` maps to `emptyList()`.

Exception also logs and emits `emptyList()`.

User sees no results, not an error.

## Fix Strategy

Emit a typed state:

```kotlin
sealed interface LocationSearchUiState {
    data object Idle
    data object Loading
    data class Results(...)
    data class Error(val message: UiText)
    data class PrivacyBlocked(...)
}
```

## Acceptance Tests

- service failure shows error.
- no-results success shows empty state.
- privacy blocked differs from empty result.

---

## S6-F3-012 — Location clear is still read from live ViewModel state in screen save callback

**Severity:** Medium  
**File:** `ReviewScreen.kt`

## Problem

`onSave` passes:

```kotlin
locationCleared = viewModel.locationEditState.value.locationCleared
```

instead of receiving `locationCleared` from the dialog’s save snapshot.

This works in common cases, but it couples the composable directly to live ViewModel state and makes state ownership less clear.

## Fix Strategy

Include `locationCleared` in the `EditReviewDialog.onSave` signature:

```kotlin
onSave(..., locationEdit: ReviewLocationEdit)
```

Prefer tri-state:

```kotlin
sealed interface ReviewLocationEdit {
    data object Unchanged
    data object Clear
    data class Set(...)
}
```

---

## S6-F3-013 — Quick approve button/dialog does not expose loading state

**Severity:** Medium  
**Files:**
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

## Problem

`confirmQuickApprove()` uses `beginMutation(preview.reviewId)`, but the quick approve dialog confirm button does not appear disabled when mutation is running.

## Fix Strategy

Use `inFlightMutationKinds[preview.reviewId]` in the dialog:

```kotlin
val isQuickApproving = inFlightMutationKinds[preview.reviewId] != null
TextButton(enabled = !isQuickApproving, ...)
```

## Acceptance Tests

- double-tap confirm calls approve once.
- spinner/disabled state visible while approving.
- failure keeps preview open.

---

## S6-F3-014 — Raw strings and exception details remain widespread

**Severity:** Medium  
**Files:**
- `ReviewViewModel.kt`
- `ReviewQueueRepository.kt`
- `ReviewScreen.kt`

Examples:
- `"Duplicate transaction detected"`
- `"Failed to approve: ${result.message}"`
- `"Review changed since preview. Please review it again."`
- `"Location search is disabled by privacy settings."`
- `"Suggested category is no longer available."`
- `"Quick approve failed: ${e.message}"`
- `"Imported ... transactions from statement!"`

## Fix Strategy

Use resource-backed `UiText`:

```kotlin
val errorMessage: StateFlow<UiText?>
```

Do not show raw exception text to users.

---

## S6-F3-015 — Missing focused tests

**Severity:** High / test gap**

This commit did not visibly add tests for the new behavior.

Required tests:
- approval validation rollback returns `Result.Error`;
- receipt link failure returns `Result.Error`;
- direct approve gate redirects to edit;
- swipe invalid review does not approve/dismiss;
- fake EUR placeholder cannot approve;
- location privacy denied does not call geocoding;
- quick approve confirm revalidates;
- AI explanation duplicate call guard;
- bulk transfer/location propagation.

---

# Updated Implementation Plan

## Phase 1 — Fix placeholder currency

Files:
- `ReviewQueueRepository.kt`
- `ReviewViewModel.kt`
- `ReviewScreen.kt`
- `EditReviewDialog`

Steps:
1. Stop using `"EUR"` for synthetic placeholder currency.
2. Add currency picker/input to edit dialog.
3. Pass `finalCurrency`.
4. Block placeholder approval until explicit currency selected.
5. Add tests.

---

## Phase 2 — Fix swipe lifecycle

Files:
- `ReviewScreen.kt`

Steps:
1. Reuse required-field eligibility for swipe approve.
2. Return `false` from `confirmValueChange`.
3. Let DB flow remove row after success.
4. Remove local `processingIds`.
5. Use ViewModel mutation state.

---

## Phase 3 — Harden repository validation

Files:
- `ReviewQueueRepository.kt`

Steps:
1. Add transfer metadata validation.
2. Normalize transfer-direction parser.
3. Clarify `DEPOSIT` metadata policy.
4. Map known errors to stable result messages/codes.

---

## Phase 4 — Finish AI artifact correctness

Files:
- `ReviewViewModel.kt`

Steps:
1. Track pending applied AI capabilities in edit event/draft.
2. Do not mark receipt artifact applied before save.
3. Do not mark category artifact applied for manual category edits.
4. Add tests.

---

## Phase 5 — Improve location search state

Files:
- `ReviewViewModel.kt`
- `ReviewScreen.kt`

Steps:
1. Replace `error: String?` with typed `UiText` / sealed state.
2. Preserve privacy denial reason.
3. Show service failure vs empty result distinctly.
4. Add debounce/privacy/stale tests.

---

## Phase 6 — Test hardening

Add:
- `ReviewQueueRepositoryApprovalRollbackTest`
- `ReviewSyntheticPlaceholderCurrencyTest`
- `ReviewSwipeApprovalGateTest`
- `ReviewEditTransferLocationTest`
- `ReviewLocationSearchPrivacyTest`
- `ReviewAiArtifactApplicationTest`
- `ReviewQuickApproveRevalidationTest`
- `ReviewBulkEditResultTest`

---

# Recommended Tests

## `ReviewQueueRepositoryApprovalRollbackTest`

Cases:
- `ValidationFailed` returns `Result.Error`.
- review remains `PENDING`.
- receipt link failure returns `Result.Error`.
- receipt link failure rolls back expense creation.
- side effects dispatch only after successful commit.

## `ReviewSyntheticPlaceholderCurrencyTest`

Cases:
- synthetic placeholder has no fake EUR.
- approving without explicit currency fails.
- edit with USD saves USD.
- direct approve and swipe both route to edit.

## `ReviewSwipeApprovalGateTest`

Cases:
- missing amount swipe does not dismiss.
- unknown merchant swipe does not dismiss.
- missing currency swipe does not dismiss.
- valid swipe triggers approve but row remains until DB success.
- failure keeps row visible.

## `ReviewLocationSearchPrivacyTest`

Cases:
- privacy denied prevents geocoding call.
- fail-closed prevents geocoding call.
- denial reason visible.
- rapid typing debounces.
- old result cannot overwrite new query.
- service failure is visible.

## `ReviewAiArtifactApplicationTest`

Cases:
- category suggestion apply + cancel does not mark applied.
- receipt suggestion apply + cancel does not mark applied.
- apply + save success marks only used capabilities.
- manual category edit does not mark category AI artifact.

## `ReviewQuickApproveRevalidationTest`

Cases:
- preview opens for eligible review.
- category deleted before confirm blocks approval.
- review approved elsewhere before confirm blocks approval.
- confirm double-tap calls approve once.
- failure keeps preview visible.

---

# Final Severity Table After `f3cefa7`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S6-F3-001 | Critical/High | Unresolved | Synthetic placeholder still uses fake EUR |
| S6-F3-002 | High | Unresolved | Swipe approve bypasses required-field gate |
| S6-F3-003 | High | Unresolved | Swipe dismisses before persistence success |
| S6-F3-004 | Med/High | Unresolved | `processingIds` never cleared |
| S6-F3-005 | High | Partial | Direct approve gate does not check currency |
| S6-F3-006 | High | Unresolved | Transfer metadata lacks repository validation |
| S6-F3-007 | Med/High | Unresolved | Receipt assist artifact marked applied before save |
| S6-F3-008 | Medium | Partial | Category artifact marking is over-broad |
| S6-F3-009 | Med/High | Unresolved | Bulk failures still only logged |
| S6-F3-010 | Medium | Partial | Location privacy denial loses reason |
| S6-F3-011 | Medium | Partial | Geocoding failure silently becomes empty result |
| S6-F3-012 | Medium | Design debt | Location clear read from live VM state |
| S6-F3-013 | Medium | Partial | Quick approve dialog lacks loading/disabled state |
| S6-F3-014 | Medium | Unresolved | Raw strings/exception messages remain |
| S6-F3-015 | High | Test gap | Focused tests missing |

---

# Immediate Agent Task List

## Task A — Remove fake EUR placeholder
This is the largest correctness issue still open.

## Task B — Fix swipe approve/reject
Do not dismiss before persistence. Do not bypass required-field checks.

## Task C — Validate transfer metadata
Repository must reject invalid `TRANSFER` state.

## Task D — Fix AI artifact timing
Receipt/category AI artifacts should mark applied only after successful saved usage.

## Task E — Make bulk failures visible
Primary success with partial bulk failure must notify the user.

## Task F — Add tests
Start with repository rollback, placeholder currency, swipe gate, and location privacy tests.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/f3cefa7111e7cb75264769cf9dde8c2666ed4976
- Commit patch: https://github.com/panospao7/Cost-agregator/commit/f3cefa7111e7cb75264769cf9dde8c2666ed4976.patch
- `ReviewQueueRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
- `ReviewViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
- `ReviewScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt
- `PrivacyDecision.kt`: https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt