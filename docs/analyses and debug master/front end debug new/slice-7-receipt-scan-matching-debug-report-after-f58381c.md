# Slice 7 Re-Debug Report — Receipt Scan + Receipt Matching

Commit reviewed: `f58381cadc3bda573e412662a1d45f447e5a94fa`  
Commit title: `fix(receipt): Slice 7 remaining - S7-011/012/015/019/021/025/026/027/028`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:  
https://github.com/panospao7/Cost-agregator/commit/f58381cadc3bda573e412662a1d45f447e5a94fa

---

# Executive Summary

Slice 7 is **improved**, but it is **still not closed**.

Confirmed improvements:
- Receipt scan route and matching route were added and wired from `MainActivity`.
- Receipt scan amount input now uses the shared `AmountInputSanitizer`.
- Manual save request now captures currency at validation time.
- Quick-save preview is blocked while currency is unavailable.
- AI suggestion application now accumulates draft-applied capabilities instead of marking artifacts applied immediately.
- Receipt assist requests are now receipt-ID scoped and guarded against stale receipt writes.
- Receipt scan reset/retry now cancel `scanJob`, cancel item analysis, and increment request sequence.
- Receipt matching screen now renders `state.error`.
- Manual match now checks `ReceiptLinkService` result and keeps the dialog open on failure.
- Reject/skip now have mutation guards and visible generic error handling.
- Auto-match FAB now shows running state.
- Matching load now has a `loadJob` cancellation point.
- Matching content state now includes both unmatched receipts and suggestions.
- Matcher now compares receipt amount against original transaction amount and excludes not-mine transactions.

Still high-risk:
1. **Direct receipt save is still not atomic. Expense creation can still happen before link failure.**
2. **Duplicate receipt processing still appears to enter normal review/save flow.**
3. **Matching row mutation state exists in ViewModel but is not consumed by the UI.**
4. **Approve-match failure can be immediately cleared by unconditional reload.**
5. **Category assist is not receipt-ID scoped like receipt assist.**
6. **AI draft-applied capabilities may now never be marked applied after save, or at least this is not visibly wired.**
7. **Quick-save confirm can still create a request with blank currency from stale preview/state.**
8. **Receipt matching totals still render without currency.**
9. **Missing totals now use a replacement glyph `�`, which is worse than a user-facing unavailable label.**
10. **Item breakdown still likely hardcodes EUR.**
11. **Route split is superficial; screens still take ViewModels directly.**
12. **Most messages are still raw strings rather than resource-backed `UiText`.**

Recommended next order:
1. Fix receipt save/link atomicity.
2. Fix duplicate-receipt state.
3. Finish matching mutation UI.
4. Fix category-assist stale guards.
5. Fix AI artifact applied-after-save wiring.
6. Fix currency/missing-amount rendering.
7. Finish route/screen separation.
8. Add focused tests.

---

# Updated Status Table

| ID | Status after `f58381c` | Notes |
|---|---|---|
| S7-001 | Unresolved | Direct save still has no visible `checkCanLink`/atomic save coordinator. |
| S7-002 | Unresolved | Duplicate receipts still appear to be treated as normal review state. |
| S7-003 | Mostly fixed | Reset/retry cancel scan and item jobs, but AI assist jobs/in-flight set are not centrally cleaned. |
| S7-004 | Partial | Receipt assist is scoped; category assist is still globally keyed and not consistently guarded. |
| S7-005 | Resolved | Matching screen renders `state.error`. |
| S7-006 | Mostly fixed | Manual match checks `Result` and keeps dialog open on failure. |
| S7-007 | Partial | VM mutation state exists, but screen does not consume `mutatingReceiptIds`. |
| S7-008 | Mostly fixed | Reject/skip now guarded and error-handled. |
| S7-009 | Unresolved | Item breakdown currency issue not visibly fixed. |
| S7-010 | Partial/regressed | No longer `0.00` in matching, but now uses `�` instead of a proper label/resource. |
| S7-011 | Partial | Less blank-currency formatting, but still nullable/stringly currency state. |
| S7-012 | Mostly fixed | Manual save captures currency; quick-save still has stale blank-currency edge. |
| S7-013 | Resolved | Receipt amount input uses shared sanitizer. |
| S7-014 | Mostly fixed | Quick-save preview blocked until currency loaded; confirm path still should re-check. |
| S7-015 | Partial/needs verification | Draft-applied capabilities tracked, but applied-after-success wiring not visible. |
| S7-016 | Partial | Category ID validation added, but stale receipt/category-assist guards remain incomplete. |
| S7-017 | Needs verification | No visible final cleanup hardening for item correction. |
| S7-018 | Needs verification | Item correction stale-write guard not clearly proven. |
| S7-019 | Partial | Auto matcher improved; manual candidate scoring/currency still likely inconsistent. |
| S7-020 | Resolved | Conversion availability detection improved. |
| S7-021 | Partial | Load job cancellation added; failure/reload races remain. |
| S7-022 | Unresolved/needs verification | Auto-match link `Result.failure` handling not proven. |
| S7-023 | Unresolved | Matching totals still lack currency. |
| S7-024 | Mostly fixed | Auto-match FAB shows running state; row actions still not mutation-aware. |
| S7-025 | Mostly fixed | New typed content state added; deprecated `loadableState` remains. |
| S7-026 | Partial | Raw exception leakage reduced in matching; raw strings still widespread. |
| S7-027 | Partial | `ReceiptScanRoute` added, but screen still ViewModel-coupled. |
| S7-028 | Partial | `ReceiptMatchingRoute` added, but screen still ViewModel-coupled. |

---

# Confirmed Fixes

## S7-F583-001 — Shared amount sanitizer used in receipt scan

**Status:** Resolved  
**File:** `ReceiptScanViewModel.kt`

`updateAmount()` now delegates to the shared amount sanitizer instead of local digit/decimal filtering.

## Acceptance tests still needed

- receipt amount field behaves identically to Add Expense amount field.
- multiple decimal separators are normalized.
- fractional limit is enforced.
- leading-zero behavior is consistent.

---

## S7-F583-002 — Manual save captures currency

**Status:** Mostly resolved  
**File:** `ReceiptScanViewModel.kt`

`ReceiptSaveRequest` now includes `currency`, and manual save builds the request from the current validated state.

This fixes the old issue where save could re-read live ViewModel currency later.

## Remaining gap

Quick-save confirm still builds a request with `currentState.editCurrency ?: ""`. It probably cannot happen after normal preview generation, but stale preview/state transitions can still produce a blank currency request.

## Fix

At confirm time:

```kotlin
val currency = currentState.editCurrency?.takeIf { it.isNotBlank() }
if (currency == null) {
    _state.update { it.copy(errorMessage = "Currency is not loaded yet.") }
    return
}
```

---

## S7-F583-003 — Quick save blocked while currency unavailable

**Status:** Mostly resolved  
**File:** `ReceiptScanViewModel.kt`

`buildQuickSavePreview()` now rejects quick save while currency is missing.

## Remaining gap

`confirmReceiptQuickSave()` should still re-check currency because the preview can be stale.

---

## S7-F583-004 — AI suggestions no longer mark artifacts applied immediately

**Status:** Partial improvement  
**File:** `ReceiptScanViewModel.kt`

Applying receipt/category suggestions now adds capabilities to `pendingAppliedAiCapabilities` instead of immediately calling artifact `markApplied`.

This fixes the old “apply to draft + cancel still marks artifact applied” directionally.

## Remaining high-risk gap

I did not find visible wiring that marks `pendingAppliedAiCapabilities` as applied **after successful save**. The old `markLatestArtifactApplied()` helper still exists, but visible search did not show it being called from the save success path.

## Impact

This can invert the original bug:
- before: artifacts were marked applied too early.
- now: artifacts may never be marked applied.

## Fix

After successful expense creation + receipt link:

```kotlin
request.appliedAiCapabilities.forEach { capability ->
    markLatestArtifactAppliedForReceipt(request.receiptId, capability)
}
```

Make it receipt-targeted:

```kotlin
private suspend fun markLatestArtifactAppliedForReceipt(
    receiptId: Long,
    capability: AiCapability
)
```

Do not use live `_state.value.receiptId`.

---

## S7-F583-005 — Receipt assist is receipt-scoped

**Status:** Mostly fixed  
**File:** `ReceiptScanViewModel.kt`

`requestReceiptAssist()` now uses an in-flight key containing the receipt ID and checks the current receipt ID before writing success/error state.

## Remaining gap

Dismiss/apply helpers still need captured receipt ID or state-version guards. The current dismissal code reads the current receipt and updates state after repository work; if the receipt changes during that work, it can still dismiss the wrong current UI.

---

## S7-F583-006 — Reset/retry cancel active scan work

**Status:** Mostly fixed  
**File:** `ReceiptScanViewModel.kt`

`retry()` and `reset()` now:
- increment request sequence,
- cancel `scanJob`,
- clear `scanJob`,
- cancel `itemAnalysisJob`.

## Remaining gap

There is no centralized cleanup helper and no visible `onCleared()` cleanup. Also `inFlightAssist` is not cleared, so old AI jobs may block new requests or keep running until their own `finally`.

## Fix

Create:

```kotlin
private fun cancelActiveWork() {
    scanRequestSeq++
    scanJob?.cancel()
    scanJob = null
    itemAnalysisJob?.cancel()
    itemAnalysisJob = null
    inFlightAssist.clear()
}
```

Use in `reset`, `retry`, and `onCleared`.

---

## S7-F583-007 — Matching screen displays error state

**Status:** Resolved  
**File:** `ReceiptMatchingScreen.kt`

The screen now renders an error card when `state.error` is non-null and provides a dismiss action.

## Remaining improvement

State error is still `String`; should be `UiText`.

---

## S7-F583-008 — Manual match now respects link result

**Status:** Mostly resolved  
**File:** `ReceiptMatchingViewModel.kt`

Manual match now folds over `ReceiptLinkService.linkReceiptToExpense(...)`. Success closes the dialog and reloads; failure keeps the dialog open and shows an error.

## Acceptance tests needed

- `Result.failure` keeps dialog open.
- thrown exception keeps dialog open.
- success closes dialog.
- double-tap calls link once.

---

## S7-F583-009 — Reject/skip have guards and error handling

**Status:** Mostly resolved  
**File:** `ReceiptMatchingViewModel.kt`

Reject/skip now:
- check `mutatingReceiptIds`,
- add the receipt ID before mutation,
- catch failure,
- remove the ID in `finally`.

## Remaining improvement

Extract a shared mutation helper to avoid inconsistent mutation logic across approve/reject/skip/manual/rerun.

---

## S7-F583-010 — Matching load cancellation added

**Status:** Partial  
**File:** `ReceiptMatchingViewModel.kt`

`loadReceipts()` now cancels the previous load job before starting a new one.

## Remaining risks

1. If cancellation is caught as a normal exception inside the load job, it may still update error/loading state.
2. Some mutation paths call `loadReceipts()` after setting error, which can immediately clear that error.
3. There is no request-generation check for non-cooperative repository calls.

---

## S7-F583-011 — Matcher amount policy improved

**Status:** Partial  
**File:** `ReceiptTransactionMatcher.kt`

The matcher now:
- excludes `isNotMine` transactions.
- checks positive original transaction amount.
- compares converted receipt amount against original transaction amount.
- treats conversion result existence explicitly.

This is a strong improvement over comparing against ownership-adjusted `effectiveAmount`.

## Remaining gap

Manual candidate sorting likely still uses a different repository scoring path. Auto and manual matching need one shared scorer.

---

## S7-F583-012 — Route wrappers added

**Status:** Partial  
**Files:**
- `ReceiptScanScreen.kt`
- `ReceiptMatchingScreen.kt`
- `MainActivity.kt`

`MainActivity` now navigates to:
- `ReceiptScanRoute`
- `ReceiptMatchingRoute`

But both route wrappers simply pass ViewModel into the existing screen, and the screens still default to `hiltViewModel()`.

This is a naming/entry-point improvement, not a completed route/screen split.

---

# Remaining / New Issues

---

## S7-F583-001 — Direct receipt save still is not atomic

**Severity:** Critical  
**Files:**
- `ReceiptScanViewModel.kt`
- `ReceiptLinkService.kt`
- transaction lifecycle/linking layer

## Problem

There is no visible `ReceiptExpenseSaveCoordinator`, `checkCanLink`, or atomic create-and-link operation in this commit.

The presence of `PartialLinkFailure` still suggests the app may create an expense first and only afterwards discover that the receipt cannot be linked.

## Impact

A failed link can leave:
- orphan expense,
- duplicate transaction,
- unlinked receipt,
- misleading user success/failure state.

## Fix strategy

Add a domain operation:

```kotlin
class ReceiptExpenseSaveCoordinator {
    suspend fun createExpenseAndLinkReceipt(
        request: ReceiptExpenseSaveRequest
    ): ReceiptExpenseSaveResult
}
```

It should:
1. Validate receipt exists.
2. Validate receipt is linkable.
3. Validate create-expense request.
4. Create expense and link receipt in one transaction-safe path.
5. Emit one typed result.

Short-term:
- preflight receipt linkability before expense creation.

## Acceptance tests

- already-linked receipt creates no new expense.
- link failure creates no orphan expense.
- success creates exactly one expense and one link.
- duplicate receipt cannot direct-save silently.

---

## S7-F583-002 — Duplicate receipt still appears to use normal review/save flow

**Severity:** Critical  
**Files:**
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptScanViewModel.kt`

## Problem

No visible typed duplicate processing state was added.

`SaveReceiptResult.DuplicateReceipt` exists, but the scan processing path still appears to accept the receipt returned by lifecycle processing and move into normal review state.

## Fix strategy

Return typed processing result from lifecycle:

```kotlin
sealed interface ReceiptProcessResult {
    data class NewReceipt(...)
    data class DuplicateReceipt(...)
    data class OcrFailedManual(...)
}
```

UI should show duplicate-specific state:
- already scanned,
- linked transaction CTA,
- match existing transaction CTA,
- no direct save by default.

## Acceptance tests

- exact duplicate enters duplicate state.
- semantic duplicate enters duplicate state.
- linked duplicate cannot create a new expense.
- unlinked duplicate prompts matching, not direct save.

---

## S7-F583-003 — Category assist is not receipt-scoped

**Severity:** High  
**File:** `ReceiptScanViewModel.kt`

## Problem

`requestReceiptAssist()` is receipt-scoped, but category assist still uses a global key like `category_assist`.

The success/error state updates also do not consistently check that the current receipt is still the captured receipt before writing.

## Impact

Receipt A category suggestion can update Receipt B after scan change.

## Fix

Use:

```kotlin
val key = "category_assist:$receiptId"
```

and guard all updates:

```kotlin
_state.update { current ->
    if (current.receiptId != receiptId) current
    else current.copy(...)
}
```

## Acceptance tests

- category result for old receipt does not update new receipt.
- category assist for Receipt B is not blocked by Receipt A in-flight key.
- dismiss category assist targets captured receipt ID.

---

## S7-F583-004 — Dismiss/apply assist helpers are still live-state based

**Severity:** Medium/High  
**File:** `ReceiptScanViewModel.kt`

## Problem

Helpers such as:
- dismiss receipt assist,
- dismiss category assist,
- mark latest artifact applied,
- generic apply suggested value,

read current state and update current state rather than being tied to the receipt that produced the artifact/suggestion.

## Fix

Store receipt ID alongside assist state:

```kotlin
data class ReceiptAssistUiState(
    val receiptId: Long,
    val state: AiLoadState<ReceiptAssistSuggestion>
)
```

Or at minimum pass receipt ID into apply/dismiss methods.

---

## S7-F583-005 — AI applied-after-save wiring is not visible

**Severity:** High  
**File:** `ReceiptScanViewModel.kt`

## Problem

The state now tracks `pendingAppliedAiCapabilities`, and `ReceiptSaveRequest` includes `appliedAiCapabilities`.

But visible search did not show the save success path consuming those capabilities.

## Impact

AI artifacts may never transition to applied.

## Fix

After confirmed save success:

```kotlin
request.appliedAiCapabilities.forEach {
    markLatestArtifactAppliedForReceipt(request.receiptId, it)
}
```

Clear pending set only after marking succeeds or intentionally after save success.

## Acceptance tests

- apply suggestion + cancel does not mark applied.
- apply suggestion + save success marks applied.
- save failure does not mark applied.
- quick-save used capabilities are marked after success.

---

## S7-F583-006 — Quick-save confirm can still build blank-currency request

**Severity:** Medium/High  
**File:** `ReceiptScanViewModel.kt`

## Problem

Manual save validates currency and captures it. Quick-save confirm still uses a blank fallback if current currency is null.

Even if preview creation blocks missing currency, confirm should never create a request with blank currency.

## Fix

Re-check currency in `confirmReceiptQuickSave()` and fail before creating the request.

## Acceptance tests

- if currency becomes null after preview opens, confirm does not save.
- error says currency is loading/unavailable.
- request currency is never blank.

---

## S7-F583-007 — Matching approve failure may be cleared immediately

**Severity:** High  
**File:** `ReceiptMatchingViewModel.kt`

## Problem

Approve match sets error on link failure, but then calls `loadReceipts()` after the mutation block. `loadReceipts()` clears `error` at load start.

## Impact

User may never see the approve error.

## Fix

Only reload after success.

```kotlin
var shouldReload = false
result.fold(
    onSuccess = { shouldReload = true },
    onFailure = { setError(...) }
)
if (shouldReload) loadReceipts()
```

## Acceptance tests

- approve `Result.failure` leaves error visible.
- approve failure does not reload and clear error.
- approve success reloads.

---

## S7-F583-008 — Row mutation state is still not consumed by matching UI

**Severity:** High  
**Files:**
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

## Problem

The ViewModel maintains `mutatingReceiptIds`, but the screen search found no usage of that state.

## Impact

Users can still tap row actions while a receipt mutation is running. VM guards reduce duplicate calls, but UI gives no feedback and may still feel broken.

## Fix

Pass to cards:

```kotlin
val isMutating = suggestion.receipt.id in state.mutatingReceiptIds
```

Disable:
- approve,
- reject,
- manual,
- skip,
- rerun.

Show row-level spinner.

## Acceptance tests

- row actions disabled while receipt ID mutating.
- double tap invokes service once.
- row spinner visible during mutation.

---

## S7-F583-009 — Missing receipt amount renders as `�`

**Severity:** Medium/High  
**File:** `ReceiptMatchingScreen.kt`

## Problem

The matching screen replaced the old dash with the replacement character `�`.

This is not a valid user-facing unavailable state and may indicate encoding drift in the source.

## Fix

Add resource:

```xml
<string name="receipt_amount_unavailable">Amount unavailable</string>
```

Render:

```kotlin
receipt.parsedTotal?.let { ... } ?: stringResource(R.string.receipt_amount_unavailable)
```

## Acceptance tests

- missing amount shows localized unavailable label.
- no replacement glyph appears in UI.
- no missing amount displays `0.00`.

---

## S7-F583-010 — Receipt matching totals still lack currency

**Severity:** Medium/High  
**File:** `ReceiptMatchingScreen.kt`

## Problem

Totals are still formatted with numeric-only `String.format`.

No receipt currency is shown.

## Fix

Use:

```kotlin
CurrencyFormatter.formatMoney(total, receipt.currency)
```

If receipt currency is missing:
- show amount plus “currency unknown”, or
- show unavailable/degraded state.

## Acceptance tests

- USD receipt displays USD.
- EUR receipt displays EUR.
- missing currency is explicit.

---

## S7-F583-011 — Receipt scan preview still formats amount without currency when currency missing

**Severity:** Medium  
**File:** `ReceiptScanScreen.kt`

## Problem

The quick-save/review amount preview now falls back to numeric-only amount if currency is missing.

That is better than blank-currency formatter, but still not explicit that currency is loading/unavailable.

## Fix

Use a typed currency state and render:
- loading indicator,
- currency unavailable message,
- disable save/quick-save.

---

## S7-F583-012 — Item breakdown currency issue still unresolved

**Severity:** High  
**File:**
- `ReceiptItemBreakdownCard.kt`

## Problem

This commit did not visibly touch item breakdown. The previous issue was that item amounts were formatted with EUR.

## Fix

Pass receipt/edit currency into the card and format via `CurrencyFormatter`.

## Acceptance tests

- USD items render USD.
- GBP items render GBP.
- no hardcoded EUR format remains.

---

## S7-F583-013 — Auto and manual receipt matching may still use different scoring

**Severity:** High  
**Files:**
- `ReceiptTransactionMatcher.kt`
- `ReceiptRepository.kt`

## Problem

Auto matcher now compares original transaction amount and excludes not-mine. But manual candidate retrieval/sorting likely still happens elsewhere and may still use raw/effective amount.

## Fix

Create one shared scorer:

```kotlin
interface ReceiptMatchScorer {
    fun score(receipt: ScannedReceipt, expense: Expense): ReceiptMatchScore
}
```

Use it for:
- auto match,
- manual candidates,
- rerun suggestions.

## Acceptance tests

- manual candidate order equals matcher score order.
- cross-currency manual candidates sort correctly.
- shared/not-mine policy matches auto matcher.

---

## S7-F583-014 — Matching load cancellation still lacks generation safety

**Severity:** Medium  
**File:** `ReceiptMatchingViewModel.kt`

## Problem

`loadJob?.cancel()` helps, but stale non-cooperative repository calls can still write after a newer request unless guarded with generation IDs.

Also cancellation should not be treated as a normal failure.

## Fix

```kotlin
private var loadSeq = 0L

private fun loadReceipts() {
    val seq = ++loadSeq
    loadJob?.cancel()
    loadJob = viewModelScope.launch {
        try {
            val data = ...
            if (seq != loadSeq) return@launch
            _state.update { ... }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seq == loadSeq) setError(...)
        }
    }
}
```

---

## S7-F583-015 — Raw strings still dominate receipt scan/matching

**Severity:** Medium  
**Files:**
- `ReceiptScanViewModel.kt`
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

## Problem

This commit reduces raw exception leakage in matching, but the state model still uses `String` errors/messages.

Examples:
- validation errors,
- OCR failure messages,
- AI assist messages,
- matching failure messages,
- quick-save messages.

## Fix

Use `UiText`:

```kotlin
val errorMessage: UiText?
val receiptAssistMessage: UiText?
val categoryAssistMessage: UiText?
```

ViewModels emit resource-backed messages; exceptions go to `Timber`.

---

## S7-F583-016 — Route split remains superficial

**Severity:** Medium  
**Files:**
- `ReceiptScanScreen.kt`
- `ReceiptMatchingScreen.kt`

## Problem

`ReceiptScanRoute` and `ReceiptMatchingRoute` now exist, but both simply pass a ViewModel into ViewModel-coupled screens.

The screens still:
- default to `hiltViewModel`,
- collect state internally,
- call ViewModel directly,
- own callbacks/effects.

## Fix

Target:

```kotlin
@Composable
fun ReceiptMatchingRoute(...) {
    val state by viewModel.state.collectAsState()
    ReceiptMatchingScreen(
        state = state,
        callbacks = ReceiptMatchingCallbacks(...)
    )
}
```

Same for receipt scan.

## Acceptance tests

- screen can render fake state without Hilt.
- route owns ViewModel/event collection.
- cards can be tested with fake callbacks.

---

# Updated Implementation Plan

## Phase 1 — Save/link safety

Files:
- `ReceiptScanViewModel.kt`
- `ReceiptLinkService.kt`
- new `ReceiptExpenseSaveCoordinator.kt`

Steps:
1. Add linkability preflight.
2. Add atomic create-expense-and-link coordinator.
3. Remove or avoid `PartialLinkFailure` orphan path.
4. Add duplicate-receipt UI state.
5. Add atomicity tests.

---

## Phase 2 — Finish async stale-state guards

Files:
- `ReceiptScanViewModel.kt`

Steps:
1. Receipt-scope category assist key.
2. Guard all category assist updates by captured receipt ID.
3. Make dismiss/apply artifact actions receipt-targeted.
4. Add centralized `cancelActiveWork()`.
5. Clear in-flight assist state on reset/onCleared.

---

## Phase 3 — AI artifact applied-after-save

Files:
- `ReceiptScanViewModel.kt`

Steps:
1. After successful save/link, mark request capabilities applied.
2. Use request receipt ID, not live state.
3. Clear `pendingAppliedAiCapabilities` only after save success.
4. Add tests for apply+cancel, apply+success, apply+failure.

---

## Phase 4 — Matching mutation/error UI

Files:
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

Steps:
1. Consume `mutatingReceiptIds` in UI.
2. Disable row actions and show row spinner.
3. Reload only after approve success.
4. Add generation IDs to load jobs.
5. Use shared mutation helper.

---

## Phase 5 — Currency/amount display

Files:
- `ReceiptScanScreen.kt`
- `ReceiptMatchingScreen.kt`
- `ReceiptItemBreakdownCard.kt`

Steps:
1. Replace `�` with localized unavailable label.
2. Show currency for all receipt totals.
3. Pass receipt currency to item breakdown.
4. Add typed currency state for receipt scan.
5. Make quick-save confirm reject blank currency.

---

## Phase 6 — Test hardening

Add/strengthen:
- `ReceiptDirectSaveAtomicityTest`
- `ReceiptDuplicateProcessingTest`
- `ReceiptScanAssistStaleGuardTest`
- `ReceiptAiArtifactAppliedAfterSaveTest`
- `ReceiptQuickSaveCurrencyTest`
- `ReceiptMatchingMutationUiStateTest`
- `ReceiptMatchingApproveFailureTest`
- `ReceiptMatchScorerConsistencyTest`
- `ReceiptAmountCurrencyRenderingTest`

---

# Recommended Tests

## `ReceiptDirectSaveAtomicityTest`

Cases:
- already-linked receipt blocks before expense creation.
- link failure creates no orphan expense.
- save success creates one expense and one link.
- duplicate receipt cannot direct-save by default.

## `ReceiptScanAssistStaleGuardTest`

Cases:
- receipt assist result for old receipt ignored.
- category assist result for old receipt ignored.
- dismiss old assist does not dismiss new receipt assist.
- reset clears in-flight assist state.

## `ReceiptAiArtifactAppliedAfterSaveTest`

Cases:
- apply suggestion + cancel does not mark applied.
- apply suggestion + save success marks applied.
- apply suggestion + save failure does not mark applied.
- quick-save used capabilities marked only after success.

## `ReceiptQuickSaveCurrencyTest`

Cases:
- preview unavailable when currency missing.
- confirm rejects blank currency.
- request captures currency.
- currency change after preview uses explicit safe behavior.

## `ReceiptMatchingMutationUiStateTest`

Cases:
- mutating receipt disables row actions.
- manual match failure keeps dialog open.
- approve failure remains visible.
- reject/skip failure remains visible.
- double tap invokes service once.

## `ReceiptAmountCurrencyRenderingTest`

Cases:
- missing amount shows localized unavailable label.
- no replacement glyph appears.
- receipt total shows currency.
- item amount shows receipt currency, not EUR.

## `ReceiptMatchScorerConsistencyTest`

Cases:
- auto and manual scoring match.
- cross-currency conversion affects score correctly.
- not-mine excluded.
- shared transaction policy is explicit.

---

# Final Severity Table After `f58381c`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S7-F583-001 | Critical | Unresolved | Direct save/link still not atomic |
| S7-F583-002 | Critical | Unresolved | Duplicate receipt still not modeled as duplicate UI state |
| S7-F583-003 | High | Partial | Category assist not receipt-scoped |
| S7-F583-004 | Med/High | Partial | Assist dismiss/apply still live-state based |
| S7-F583-005 | High | Partial | AI applied-after-save wiring not visible |
| S7-F583-006 | Med/High | Partial | Quick-save confirm can still create blank-currency request |
| S7-F583-007 | High | New | Approve failure can be cleared by unconditional reload |
| S7-F583-008 | High | Partial | `mutatingReceiptIds` not consumed by UI |
| S7-F583-009 | Med/High | New | Missing amount renders `�` |
| S7-F583-010 | Med/High | Unresolved | Matching totals lack currency |
| S7-F583-011 | Medium | Partial | Scan preview uses numeric-only fallback for missing currency |
| S7-F583-012 | High | Unresolved | Item breakdown currency issue remains |
| S7-F583-013 | High | Partial | Auto/manual matching scoring may still diverge |
| S7-F583-014 | Medium | Partial | Load cancellation lacks generation safety |
| S7-F583-015 | Medium | Partial | Raw strings still widespread |
| S7-F583-016 | Medium | Partial | Route split still ViewModel-coupled |

---

# Immediate Agent Task List

## Task A — Receipt save atomicity
Add linkability preflight and create/link coordinator. Remove orphan `PartialLinkFailure` path.

## Task B — Duplicate receipt UX
Return/render duplicate receipt state and block direct duplicate save.

## Task C — Category assist stale guard
Scope category assist by receipt ID and guard all updates.

## Task D — Apply AI artifacts only after save success
Wire `pendingAppliedAiCapabilities` into the save success path.

## Task E — Matching mutation UI
Use `mutatingReceiptIds` in row cards and do not reload after approve failure.

## Task F — Currency/missing amount rendering
Replace `�`, show currency everywhere, and fix item breakdown EUR fallback.

## Task G — Finish route split
Make scan/matching screens stateless with callback objects.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/f58381cadc3bda573e412662a1d45f447e5a94fa
- `ReceiptScanViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt
- `ReceiptScanScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt
- `ReceiptMatchingViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt
- `ReceiptMatchingScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingScreen.kt
- `ReceiptTransactionMatcher.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt
- `MainActivity.kt`: https://github.com/panospao7/Cost-agregator/blob/f58381cadc3bda573e412662a1d45f447e5a94fa/app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt