# Slice 7 Debug Report — Receipt Scan + Receipt Matching

Commit reviewed: `d914916ee9a46f7ec6a63be0a93c421d8c7c0a97`  
Review type: static GitHub source review, not local Gradle/device execution.

Scope:
- `ui/screens/receiptscan/*`
- `ui/screens/receiptmatching/*`
- `ui/components/ai/ReceiptAssistCard.kt`
- `ui/components/ai/ReceiptItemBreakdownCard.kt`
- receipt lifecycle/linking surfaces:
  - `ReceiptLifecycleCoordinator`
  - `ReceiptLinkService`
  - `ReceiptRepository`
  - `ReceiptTransactionMatcher`

Primary invariants:
1. OCR loading/error/success states are explicit.
2. Receipt scan save does not create duplicate/orphan expenses.
3. Receipt matching never creates duplicate expenses when matching an existing transaction.
4. Receipt-expense links go through `ReceiptLinkService`.
5. Item categorization confidence and rationale are surfaced.
6. Retry/cancel paths clean all in-flight state.
7. AI receipt assist and item categorization degrade visibly.

---

# Executive Summary

Slice 7 is **partially fixed, but not closed**.

Good progress:
- Interactive scan now uses `ReceiptLifecycleCoordinator.processReceiptInput(...)`.
- Interactive scan disables auto-match during scan processing.
- Prior scan job is cancelled when a new image is selected.
- Request sequence guards prevent stale OCR results from overwriting newer scan results.
- Save has an `isSaving` idempotency guard.
- Expense creation uses `TransactionLifecycleCoordinator.createExpenseStandalone(...)`.
- Receipt linking uses `ReceiptLinkService.linkReceiptToExpense(...)`.
- Link failure after expense creation is surfaced as `PartialLinkFailure`.
- Receipt save success includes `expenseId`.
- Debug viewer is `BuildConfig.DEBUG` gated.
- Home currency is nullable instead of defaulting directly to fake `"EUR"`.
- OCR failure creates a manual-review state instead of dead-ending.
- Item categorization now has:
  - loading state,
  - error state,
  - unavailable state,
  - rationale dialog,
  - per-item update IDs.
- `ReceiptMatchingViewModel` uses `ReceiptLinkService` for approve/manual/auto/rerun linking.
- `ReceiptTransactionMatcher` attempts currency-aware comparison.

High-risk unresolved issues:
1. **Duplicate receipt save can create a new orphan/duplicate expense before link failure is detected.**
2. **Receipt direct save is not atomic: expense creation and receipt linking are separate operations.**
3. **Receipt matching screen does not display `state.error`, so failures are invisible.**
4. **Manual match ignores `ReceiptLinkService` failure result and closes the dialog anyway.**
5. **Receipt matching buttons do not consume mutation/loading state, so double-taps remain possible in UI.**
6. **AI assist results can stale-write into a newer receipt because receipt ID is not checked before applying result.**
7. **Reset/dismiss does not cancel the active scan job or increment request sequence.**
8. **Receipt item breakdown hardcodes EUR for item amounts.**
9. **Receipt scan and matching still show missing amounts as `0.00`.**
10. **Receipt matching amount candidate scoring still uses raw/effective amount inconsistently.**
11. **Currency loading exists but UI still passes blank currency strings into formatters.**
12. **Most receipt scan/matching errors are raw strings, not `UiText` resources.**

Recommended fix order:
1. Fix direct-save atomicity / duplicate-receipt preflight.
2. Fix matching mutation/error UI.
3. Fix stale AI assist writes.
4. Fix reset/cancel cleanup.
5. Fix currency and amount rendering.
6. Add focused ViewModel/repository tests.

---

# Status of Slice 7 Invariants

## S7-PREV-001 — OCR loading/error/success states are explicit

**Status:** Mostly resolved.

Evidence:
- `ScanStep.CAPTURE`
- `ScanStep.PROCESSING`
- `ScanStep.REVIEW`
- `ScanStep.DONE`
- `ScanStep.ERROR`
- OCR failure becomes `REVIEW` with manual-entry fields and a visible message.
- Catastrophic failure becomes `ERROR`.

Remaining issues:
- Error messages are raw strings.
- Reset/dismiss does not cancel `scanJob`.
- PDF gallery input is allowed but the preview path is image-centric.

---

## S7-PREV-002 — Receipt matching never creates duplicate expenses

**Status:** Partially unresolved.

Good:
- Matching existing transactions uses `ReceiptLinkService`; it does not create a new expense.

Bad:
- Direct receipt save can create a new expense first and only then fail to link.
- Duplicate receipt detection can return an existing receipt, but UI still allows direct save against that receipt ID.

Impact:
A duplicate receipt or already-linked receipt can produce a new expense that is not linked to the receipt.

---

## S7-PREV-003 — Receipt link uses legal lifecycle/link service

**Status:** Mostly resolved.

Evidence:
- `ReceiptScanViewModel.saveExpenseInternal()` calls `ReceiptLinkService.linkReceiptToExpense(...)`.
- `ReceiptMatchingViewModel.approveSuggestion()`, `manualMatch()`, `runAutoMatching()`, and `rerunForReceipt()` call `ReceiptLinkService`.
- `ReceiptLinkService` owns:
  - join table,
  - legacy `ScannedReceipt.expenseId`,
  - warranty/return propagation,
  - item categorization `expenseId`,
  - audit event.

Remaining issues:
- Some repository legacy methods still exist, deprecated with `DeprecationLevel.ERROR`.
- Direct receipt save does create+link in two stages, not one atomic lifecycle operation.
- `ReceiptLinkService` directly updates `expense.categoryId` from item categorizations, bypassing `TransactionLifecycleCoordinator.updateCategory()`. The code documents this as a deferred circular-dependency workaround.

---

## S7-PREV-004 — Item categorization confidence is surfaced

**Status:** Mostly resolved.

Evidence:
- `ReceiptItemBreakdownCard` renders confidence badges.
- Low-confidence rows show warning styling.
- Rationale dialog exists.
- Alternative categories are shown from JSON.
- Per-item update spinner exists.

Remaining issues:
- Amount display hardcodes EUR.
- Per-item update cleanup is not in a robust `finally`.
- Item correction updates are not guarded against stale receipt changes.

---

## S7-PREV-005 — Retry/cancel paths clean state

**Status:** Unresolved / high risk.

Good:
- Starting a new scan cancels prior `scanJob` and `itemAnalysisJob`.
- Item analysis has receipt ID guards.

Bad:
- `reset()` and `retry()` cancel only `itemAnalysisJob`, not `scanJob`.
- They do not increment `scanRequestSeq`.
- Dismiss path calls `viewModel.reset()` and `onDismiss()`, but active OCR can continue and later mutate state.

---

# Critical / High Issues

---

## S7-001 — Direct receipt save can create duplicate/orphan expense when receipt linking fails

**Severity:** Critical  
**Files:**
- `ReceiptScanViewModel.kt`
- `ReceiptLinkService.kt`
- `ReceiptLifecycleCoordinator.kt`

## Problem

`ReceiptScanViewModel.saveExpenseInternal()` does:

1. Create expense via `transactionLifecycleCoordinator.createExpenseStandalone(createRequest)`.
2. Then link receipt via `receiptLinkService.linkReceiptToExpense(...)`.

If link fails, the ViewModel returns:

```kotlin
SaveReceiptResult.PartialLinkFailure(expenseId, message)
```

But the expense already exists.

This is especially dangerous when:
- receipt was detected as duplicate and existing receipt is returned;
- receipt is already linked;
- link service rejects because non-bank-statement receipt already has links;
- restore maintenance mode blocks linking after expense creation;
- link insert conflict happens.

## Impact

User can get:
- expense created,
- receipt not linked,
- duplicate/orphan transaction,
- no clean recovery path.

This violates the Slice 7 invariant: matching/linking flows must not create duplicates.

## Fix Strategy

### Short-term preflight

Before creating expense:

```kotlin
val linkability = receiptLinkService.checkCanLink(
    receiptId = request.receiptId,
    allowRelink = false
)
if (!linkability.canLink) {
    _state.update {
        it.copy(
            isSaving = false,
            saveResult = SaveReceiptResult.DuplicateReceipt(
                existingReceiptId = request.receiptId,
                linkedExpenseId = linkability.existingExpenseId
            )
        )
    }
    return@launch
}
```

### Correct long-term fix

Create one atomic domain operation:

```kotlin
class ReceiptExpenseSaveCoordinator {
    suspend fun createExpenseAndLinkReceipt(
        receiptId: Long,
        request: CreateExpenseRequest
    ): ReceiptSaveResult
}
```

It should:
1. Validate receipt exists and is linkable.
2. Validate expense request.
3. Create expense.
4. Link receipt.
5. Commit or compensate safely.
6. Emit one typed result.

If true DB atomicity is impossible because transaction side effects happen post-commit, implement explicit compensation:
- if link fails after expense creation, delete/rollback the created expense only if no side effects have escaped;
- better: move both operations into one transaction-safe coordinator.

## Acceptance Tests

`ReceiptDirectSaveAtomicityTest`:
- already-linked receipt does not create a new expense.
- duplicate receipt returned by lifecycle does not create a new expense.
- link service failure after validation does not leave orphan expense.
- restore write barrier failure blocks before expense creation.
- success creates exactly one expense and exactly one link.

---

## S7-002 — Duplicate receipt detection returns existing receipt but UI still allows Save

**Severity:** Critical  
**Files:**
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptScanViewModel.kt`

## Problem

`ReceiptLifecycleCoordinator.processReceiptInput()` returns `Result.success(existing)` for duplicate receipts:
- exact hash duplicate,
- text/semantic duplicate.

`ReceiptScanViewModel` then treats the returned receipt like a new scan and enters `ScanStep.REVIEW`.

The user can edit/save. If the existing receipt is already linked, S7-001 occurs. If it is not linked, the UI still does not explain that this is a duplicate receipt.

## Fix Strategy

Return typed processing result:

```kotlin
sealed interface ReceiptProcessResult {
    data class NewReceipt(val receipt: ScannedReceipt) : ReceiptProcessResult
    data class DuplicateReceipt(
        val existing: ScannedReceipt,
        val matchType: String,
        val linkedExpenseId: Long?
    ) : ReceiptProcessResult
    data class OcrFailedManual(val receipt: ScannedReceipt) : ReceiptProcessResult
}
```

Or add metadata to `ScannedReceipt` state.

ViewModel should show:
- “This receipt was already scanned.”
- “View linked transaction” if linked.
- “Link to existing transaction” if not linked.
- do not offer direct Save by default.

## Acceptance Tests

- duplicate exact hash enters duplicate state, not normal review state.
- linked duplicate shows linked expense CTA.
- duplicate cannot create a new expense without explicit user confirmation.
- duplicate state survives retry/reset correctly.

---

## S7-003 — `reset()` / dismiss does not cancel active scan job

**Severity:** High  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`processImageUri()` cancels previous scan jobs when a new scan starts.  
But `reset()` and `retry()` only cancel `itemAnalysisJob`.

Active `scanJob` can continue after:
- user closes scanner,
- user taps retry,
- route is dismissed but ViewModel remains briefly alive.

## Fix Strategy

Centralize cleanup:

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

Use in:
- `reset()`
- `retry()`
- maybe `onCleared()`

```kotlin
override fun onCleared() {
    cancelActiveWork()
    super.onCleared()
}
```

## Acceptance Tests

- dismiss during processing does not later emit REVIEW/DONE.
- retry during processing cancels old request.
- canceled OCR result cannot update state.
- `scanRequestSeq` changes on reset.

---

## S7-004 — AI receipt/category assist can stale-write into a newer receipt

**Severity:** High  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`requestReceiptAssist()` captures:

```kotlin
val receiptId = _state.value.receiptId ?: return
```

But after the use case returns, it updates `_state` without checking that the current state still has the same `receiptId`.

Same for:
- `requestCategoryAssist()`
- `dismissReceiptAssist()`
- `dismissCategoryAssist()`
- `markLatestArtifactApplied()`

The in-flight key is also global:
- `"receipt_assist"`
- `"category_assist"`

not receipt-specific.

## Impact

Scenario:
1. Receipt A in review.
2. User requests AI assist.
3. User scans Receipt B before AI returns.
4. Receipt A result updates Receipt B UI.

## Fix Strategy

Use receipt-scoped keys and state guards:

```kotlin
val key = "receipt_assist:$receiptId"
if (!inFlightAssist.add(key)) return
...
_state.update { current ->
    if (current.receiptId != receiptId) current
    else current.copy(...)
}
```

Also make artifact operations target explicit receipt ID:

```kotlin
private fun markLatestArtifactApplied(receiptId: Long, capability: AiCapability)
```

## Acceptance Tests

- AI result for old receipt does not update new receipt.
- old dismiss does not dismiss new receipt’s AI card.
- artifact applied/dismissed uses captured receipt ID.
- requesting assist for Receipt B is not blocked by Receipt A in-flight request.

---

## S7-005 — Receipt matching screen never displays `state.error`

**Severity:** High  
**Files:**
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

## Problem

`ReceiptMatchingViewModel` sets `state.error` in many places:
- load failure,
- auto-match failure,
- approve failure,
- rerun failure.

`ReceiptMatchingScreen` does not render `state.error`.

## Impact

User taps approve/manual/rerun/auto-match and nothing visible happens if it fails.

## Fix Strategy

Render an error card/snackbar:

```kotlin
state.error?.let { error ->
    ErrorStateCard(
        message = error,
        onDismiss = viewModel::clearError
    )
}
```

Better:
- use `UiText`, not raw `String`.

## Acceptance Tests

- approve failure shows error.
- manual match failure shows error.
- auto-match failure shows error.
- clear dismisses error.

---

## S7-006 — Manual match ignores link failure and closes dialog anyway

**Severity:** Critical  
**File:**
- `ReceiptMatchingViewModel.kt`

## Problem

`manualMatch()` calls:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
closeManualMatch()
loadReceipts()
```

It ignores the returned `Result`.

If link fails:
- dialog closes,
- no error is shown,
- receipt remains unmatched or stale,
- user thinks match succeeded.

## Fix Strategy

```kotlin
fun manualMatch(receiptId: Long, expenseId: Long) {
    if (isMutating(receiptId)) return
    viewModelScope.launch {
        beginMutation(receiptId)
        try {
            val result = receiptLinkService.linkReceiptToExpense(...)
            result.fold(
                onSuccess = {
                    closeManualMatch()
                    loadReceipts()
                },
                onFailure = { e ->
                    _state.update { it.copy(error = "Failed to match receipt: ${e.message}") }
                }
            )
        } finally {
            endMutation(receiptId)
        }
    }
}
```

Keep dialog open on failure.

## Acceptance Tests

- link failure keeps manual dialog open.
- error is visible.
- success closes dialog.
- double-tap manual match performs one link call.

---

## S7-007 — Matching mutation state is not consumed by UI

**Severity:** High  
**Files:**
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

## Problem

State has:
- `mutatingReceiptIds`
- `isAutoMatching`

But the screen:
- does not disable approve/reject/manual/skip/rerun buttons per receipt,
- does not show spinners,
- does not disable FAB while auto-matching,
- does not pass mutation state to cards.

## Fix Strategy

Pass state into cards:

```kotlin
MatchSuggestionCard(
    isMutating = suggestion.receipt.id in state.mutatingReceiptIds,
    ...
)
```

Disable all row actions while mutating.

FAB:

```kotlin
ExtendedFloatingActionButton(
    onClick = { viewModel.runAutoMatching() },
    expanded = !state.isAutoMatching,
    ...
)
```

## Acceptance Tests

- approve disables row buttons while running.
- manual match disables selected candidate while running.
- auto-match FAB disabled/spinner while running.
- double taps call service once.

---

## S7-008 — Reject/skip actions lack mutation guard and error handling

**Severity:** High  
**File:**
- `ReceiptMatchingViewModel.kt`

## Problem

`rejectSuggestion()` and `skipReceipt()` directly call:

```kotlin
receiptRepository.rejectAllSuggestions(receiptId)
loadReceipts()
```

No:
- idempotency guard,
- try/catch,
- error state,
- mutation state.

## Fix Strategy

Route through a shared mutation helper:

```kotlin
private fun mutateReceipt(
    receiptId: Long,
    operation: ReceiptMatchOperation,
    block: suspend () -> Unit
)
```

Use for:
- approve,
- reject,
- skip,
- manual match,
- rerun.

## Acceptance Tests

- reject failure shows error and keeps suggestion.
- skip failure shows error.
- double-tap reject performs one repo call.
- row mutation clears in finally.

---

## S7-009 — Receipt item breakdown hardcodes EUR

**Severity:** High  
**File:**
- `ReceiptItemBreakdownCard.kt`

## Problem

Item row displays:

```kotlin
stringResource(R.string.currency_eur_format, item.itemAmount)
```

This is wrong for non-EUR receipts.

## Fix Strategy

Add currency to item UI state or pass receipt currency:

```kotlin
ReceiptItemBreakdownCard(
    items = itemCategorizations,
    currency = state.editCurrency ?: parsed?.currency,
    ...
)
```

Then:

```kotlin
CurrencyFormatter.formatMoney(item.itemAmount, currency)
```

## Acceptance Tests

- USD receipt items display USD.
- GBP receipt items display GBP.
- loading/unknown currency does not display EUR fallback.
- mixed/unknown item currency state is handled.

---

## S7-010 — Missing receipt amounts are displayed as `0.00`

**Severity:** High  
**Files:**
- `ReceiptMatchingScreen.kt`
- `ReceiptScanScreen.kt`

## Problem

Matching cards show:

```kotlin
receipt.parsedTotal ?: 0.0
```

This makes unknown amount look like a real zero-value receipt.

Examples:
- unmatched receipt card,
- match suggestion card,
- manual match target.

## Fix Strategy

Use typed amount UI:

```kotlin
sealed interface ReceiptAmountUi {
    data class Known(val amount: Double, val currency: String) : ReceiptAmountUi
    data object Missing : ReceiptAmountUi
}
```

Render:
- “Amount unavailable”
- “Manual review required”
- disable auto-match if amount missing

## Acceptance Tests

- missing parsedTotal does not display `0.00`.
- auto-match does not run amount-based matching on missing amount.
- manual match target shows “Amount unavailable”.

---

## S7-011 — Currency loading still passes blank currency into formatters

**Severity:** Medium/High  
**Files:**
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`

## Problem

The ViewModel uses nullable `editCurrency`, which is good. But UI still does:
- `state.editCurrency ?: ""`
- `CurrencyFormatter.formatMoney(preview.amount, state.editCurrency ?: "")`
- `CurrencyPicker(selectedCurrency = state.editCurrency ?: "")`

## Impact

The UI can render blank or unknown currency while loading.

## Fix Strategy

Use typed currency state:

```kotlin
sealed interface ReceiptCurrencyState {
    data object Loading : ReceiptCurrencyState
    data class Ready(val code: String) : ReceiptCurrencyState
    data class Error(val message: UiText) : ReceiptCurrencyState
}
```

Short-term:
- disable Save and Quick Save until `editCurrency` is nonblank;
- show “Loading currency…” beside currency picker;
- do not call money formatter with `""`.

## Acceptance Tests

- initial scan state does not display blank currency.
- quick save unavailable while currency null.
- manual save disabled while currency null.
- currency repository failure shows error.

---

## S7-012 — `ReceiptSaveRequest` does not capture currency

**Severity:** Medium/High  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`buildManualSaveRequest()` validates `currentState.editCurrency`, but `ReceiptSaveRequest` does not contain currency.

`saveExpenseInternal()` later resolves currency from live `_state.value`.

If currency changes between validation/preview and actual save coroutine, the saved expense can use a different currency than what user confirmed.

## Fix Strategy

Add:

```kotlin
data class ReceiptSaveRequest(
    ...
    val currency: String,
)
```

Use captured value for both manual save and quick save.

## Acceptance Tests

- currency changed after tapping Save does not alter saved request.
- quick save preview confirms and saves same currency.
- manual save validates and captures currency once.

---

## S7-013 — Amount input bypasses shared sanitizer

**Severity:** Medium  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`updateAmount()` uses local filtering:

```kotlin
value.filter { it.isDigit() || it == '.' || it == ',' }
```

This allows:
- multiple decimal separators,
- mixed comma/dot strings,
- unlimited fractional digits,
- inconsistent behavior vs Add Expense.

## Fix Strategy

Use shared sanitizer from Slice 2/5:

```kotlin
AmountInputSanitizer.sanitize(value)
```

If comma decimal support is required, add it to the sanitizer centrally.

## Acceptance Tests

- `12.345` → `12.34`
- `12,,3` deterministic result
- `0012.30` consistent with Add Expense
- receipt amount field and Add Expense field behave identically.

---

## S7-014 — Quick save can be offered before currency is ready

**Severity:** Medium/High  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`buildQuickSavePreview()` does not require `editCurrency` to be loaded.

Then confirm calls `saveExpenseInternal()`, which may fail with “Currency not loaded.”

## Fix Strategy

Add:

```kotlin
if (currentState.editCurrency.isNullOrBlank()) return null
```

And expose unavailable reason:
- “Currency settings are still loading.”

## Acceptance Tests

- quick save hidden/disabled when currency null.
- unavailable reason explains currency loading.
- quick save works once currency ready.

---

## S7-015 — Applying AI suggestions marks artifacts applied before final save

**Severity:** Medium/High  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

Methods like:
- `applyCategoryAssist()`
- `applyReceiptAssistMerchant()`
- `applyReceiptAssistTotal()`
- `applyReceiptAssistDate()`
- `applyAllReceiptAssist()`

call `markLatestArtifactApplied(...)` immediately.

If user applies a suggestion and then cancels/dismisses scanner, artifact is recorded as applied even though no expense was saved.

The code already delays marking quick-save capabilities until successful save, which is better.

## Fix Strategy

Track draft-applied capabilities:

```kotlin
val pendingAppliedAiCapabilities: Set<AiCapability>
```

Mark artifacts applied only after successful save.

If product wants “applied to draft” separately, add a different artifact status:
- `DRAFT_APPLIED`
- `SAVED_APPLIED`

## Acceptance Tests

- apply suggestion + cancel does not mark artifact applied.
- apply suggestion + save success marks applied.
- save failure does not mark applied.
- quick save behavior remains correct.

---

## S7-016 — Category assist can apply invalid/deleted category IDs

**Severity:** Medium/High  
**Files:**
- `ReceiptScanViewModel.kt`
- `CategoryAssistCard.kt`

## Problem

`applyCategoryAssist()` blindly sets:

```kotlin
selectedCategoryId = readyState.value.categoryId
```

No validation that:
- category ID > 0,
- category exists in `categories.value`,
- suggestion is still valid for current receipt.

## Fix Strategy

```kotlin
val categoryExists = categories.value.any { it.id == readyState.value.categoryId }
if (!categoryExists) {
    _state.update { it.copy(categoryAssistMessage = "Suggested category is no longer available.") }
    return
}
```

Also use receipt ID guard.

## Acceptance Tests

- deleted category suggestion is rejected.
- category ID `0` is rejected.
- stale category assist from old receipt is ignored.
- valid category applies.

---

## S7-017 — Item correction update cleanup is not guaranteed in `finally`

**Severity:** Medium  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`updateItemCategory()` adds item ID to `itemCorrectionUpdatingIds`.

It removes it after `runCatching { ... }.onFailure { ... }`.

But if cancellation is rethrown inside `onFailure`, cleanup may not run.

## Fix Strategy

Use `try/finally`:

```kotlin
viewModelScope.launch {
    try {
        ...
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ...
    } finally {
        _state.update { it.copy(itemCorrectionUpdatingIds = it.itemCorrectionUpdatingIds - item.id) }
    }
}
```

## Acceptance Tests

- repository failure clears updating ID.
- cancellation clears updating ID or state reset removes it.
- duplicate correction tap is disabled while updating.

---

## S7-018 — Item correction can stale-write after receipt changes

**Severity:** Medium  
**File:**
- `ReceiptScanViewModel.kt`

## Problem

`updateItemCategory()` updates an item, then reloads items using current `_state.value.receiptId`.

If the user scans another receipt while update is running:
- old item correction may complete,
- it can reload categorizations for the new receipt,
- error/success state can appear on wrong receipt.

## Fix Strategy

Capture receipt ID:

```kotlin
val receiptId = _state.value.receiptId ?: return
```

Before every state update:

```kotlin
if (!_state.value.matchesReceiptForAnalysis(receiptId)) return
```

## Acceptance Tests

- correction result for old receipt does not alter new receipt state.
- correction error for old receipt is ignored after scan change.
- updating IDs clear on reset.

---

## S7-019 — Receipt matching amount scoring is still inconsistent for currency/ownership

**Severity:** High  
**Files:**
- `ReceiptTransactionMatcher.kt`
- `ReceiptRepository.kt`

## Problem

`ReceiptTransactionMatcher` converts receipt total into transaction currency, then compares against:

```kotlin
transaction.effectiveAmount
```

But receipt total usually represents the full receipt amount, while `effectiveAmount` may be ownership-adjusted:
- shared expense share,
- not-mine,
- other effective amount rules.

Manual candidate sorting also uses:

```kotlin
abs(receipt.parsedTotal - expense.effectiveAmount)
```

without currency conversion.

## Fix Strategy

Define matching amount policy explicitly:

Option A:
- Receipt-to-existing transaction should compare to original transaction `amount`, not `effectiveAmount`.

Option B:
- If matching user’s ledger contribution, compare to normalized/effective amount but show warning for shared/not-mine.

Preferred:
- Use original transaction amount and currency for receipt match.
- Exclude `isNotMine`.
- Handle shared expenses only if the receipt is expected to represent full group amount.

Add helper:

```kotlin
data class ReceiptMatchAmount(
    val comparableAmount: Double,
    val currency: String,
    val quality: MatchAmountQuality
)
```

## Acceptance Tests

- USD receipt and EUR transaction match only after conversion.
- missing conversion lowers score and prevents auto-match at high confidence.
- shared expense match policy is deterministic.
- manual candidates use same scoring as auto matcher.

---

## S7-020 — Currency conversion availability detection is fragile

**Severity:** Medium  
**File:**
- `ReceiptTransactionMatcher.kt`

## Problem

The matcher detects conversion success with:

```kotlin
conversionAvailable = currenciesMatch || (comparableReceiptAmount != receiptAmount)
```

If conversion result numerically equals input amount, it is treated as unavailable.

## Fix Strategy

Use explicit conversion result:

```kotlin
val conversion = if (!currenciesMatch) {
    currencyConverter.convert(receiptAmount, receiptCurrency, txCurrency)
} else null

val conversionAvailable = currenciesMatch || conversion != null
val comparableReceiptAmount = conversion?.convertedAmount ?: receiptAmount
```

## Acceptance Tests

- conversion returning same numeric value is considered available.
- failed conversion applies penalty.
- conversion service exception does not crash matcher.

---

## S7-021 — Receipt matching load/auto-match can race and stale-write

**Severity:** Medium  
**File:**
- `ReceiptMatchingViewModel.kt`

## Problem

`loadReceipts()` launches independent jobs.  
`runAutoMatching()` calls `loadReceipts()` at the end.  
`refresh()` also calls `loadReceipts()`.

There is no request sequence or cancellation. A slower older load can overwrite newer state.

## Fix Strategy

Add load job/request ID:

```kotlin
private var loadJob: Job? = null
private var loadSeq = 0L
```

Or model as a single trigger flow with `flatMapLatest`.

## Acceptance Tests

- old refresh result cannot overwrite newer load.
- auto-match reload wins over prior manual refresh.
- loading state clears correctly on cancellation.

---

## S7-022 — Auto-match link failures are swallowed per receipt

**Severity:** Medium  
**File:**
- `ReceiptMatchingViewModel.kt`

## Problem

In `runAutoMatching()`, for `AutoMatch`, link failure is captured but not surfaced:

```kotlin
val linkResult = runCatching { receiptLinkService.linkReceiptToExpense(...) }
if (linkResult.isSuccess) autoMatched++
```

But `linkReceiptToExpense()` returns `Result.failure` without throwing, so `runCatching` can be success even when the link result is failure unless the result itself is checked.

## Fix Strategy

```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
linkResult.fold(
    onSuccess = { autoMatched++ },
    onFailure = { errors += "Receipt ${receipt.id}: ${it.message}" }
)
```

## Acceptance Tests

- link service `Result.failure` does not increment autoMatched.
- per-receipt link failure appears in summary.
- successful links count accurately.

---

## S7-023 — Matching screen displays receipt totals without currency

**Severity:** Medium/High  
**File:**
- `ReceiptMatchingScreen.kt`

## Problem

Receipt totals are rendered with:

```kotlin
String.format("%.2f", receipt.parsedTotal ?: 0.0)
```

No receipt currency is shown.

## Fix Strategy

Use:

```kotlin
receipt.parsedTotal?.let {
    CurrencyFormatter.formatMoney(it, receipt.currency)
} ?: stringResource(R.string.receipt_amount_unavailable)
```

## Acceptance Tests

- USD receipt shows USD.
- EUR receipt shows EUR.
- missing amount shows unavailable.

---

## S7-024 — UI does not consume `isAutoMatching` in FAB

**Severity:** Medium  
**File:**
- `ReceiptMatchingScreen.kt`

## Problem

FAB always appears enabled when unmatched receipts exist.

ViewModel guards duplicate auto-match, but UI gives no feedback.

## Fix Strategy

```kotlin
ExtendedFloatingActionButton(
    onClick = { if (!state.isAutoMatching) viewModel.runAutoMatching() },
    text = {
        if (state.isAutoMatching) Text("Matching…")
        else Text(...)
    },
    icon = {
        if (state.isAutoMatching) CircularProgressIndicator(...)
        else Icon(...)
    }
)
```

## Acceptance Tests

- while auto-match running, FAB disabled/spinner.
- second click does not call matcher.

---

## S7-025 — Receipt matching `loadableState` ignores suggestions in data payload

**Severity:** Low/Medium  
**File:**
- `ReceiptMatchingViewModel.kt`

## Problem

`loadableState` returns `Data(unmatchedReceipts)` even if the main visible content may be suggestions.

This is not currently fatal because screen separately checks `suggestedMatches`, but it makes the state model misleading.

## Fix Strategy

Create explicit UI state:

```kotlin
sealed interface ReceiptMatchingUiState {
    data object Loading
    data class Empty(...)
    data class Data(
        val unmatched: List<ScannedReceipt>,
        val suggestions: List<MatchSuggestion>
    )
    data class Error(...)
}
```

---

## S7-026 — Receipt scan/matching ViewModels expose raw strings

**Severity:** Medium  
**Files:**
- `ReceiptScanViewModel.kt`
- `ReceiptMatchingViewModel.kt`

## Problem

Many user-facing messages are raw strings:
- “Merchant name is required”
- “Currency not loaded”
- “AI assist failed”
- “Failed to approve”
- “Auto-matching failed”
- etc.

## Fix Strategy

Use:

```kotlin
UiText.StringResource(...)
```

State:
```kotlin
val errorMessage: UiText?
val receiptAssistMessage: UiText?
```

Do not show raw exception messages to users. Log them with Timber.

## Acceptance Tests

- known errors emit resource IDs.
- exception detail is logged, not displayed.
- UI resolves `UiText`.

---

## S7-027 — Receipt scan screen is still ViewModel-coupled and hard to test

**Severity:** Medium  
**File:**
- `ReceiptScanScreen.kt`

## Problem

`ReceiptScanScreen` directly:
- owns launchers,
- owns permission state,
- collects ViewModel,
- passes ViewModel into `ReviewStep`,
- calls ViewModel from deep child composables.

This makes isolated Compose tests difficult.

## Fix Strategy

Split:

```text
ReceiptScanRoute.kt
ReceiptScanScreen.kt
ReceiptReviewContent.kt
ReceiptCaptureContent.kt
ReceiptScanCallbacks.kt
ReceiptScanUiState.kt
```

`ReceiptScanRoute` owns:
- Hilt ViewModel,
- activity result launchers,
- permissions,
- event collection.

`ReceiptScanScreen` renders fake state with callbacks.

## Acceptance Tests

- `ReceiptReviewContent` renders fake review state without Hilt.
- permission denial card can be tested without real launcher.
- quick save dialog can be tested with fake callbacks.

---

## S7-028 — Receipt matching screen is ViewModel-coupled

**Severity:** Medium  
**File:**
- `ReceiptMatchingScreen.kt`

## Problem

The composable has default `viewModel = hiltViewModel()` and passes ViewModel actions directly into cards.

## Fix Strategy

Split:

```text
ReceiptMatchingRoute.kt
ReceiptMatchingScreen.kt
ReceiptMatchingCallbacks.kt
MatchSuggestionCard.kt
UnmatchedReceiptCard.kt
ManualMatchDialog.kt
```

## Acceptance Tests

- screen renders fake state without Hilt.
- error card visible with fake error.
- cards can be tested with fake mutation states.

---

# Implementation Plan for Agent

## Phase 1 — Fix direct receipt save atomicity

Files:
- `ReceiptScanViewModel.kt`
- `ReceiptLinkService.kt`
- new `ReceiptExpenseSaveCoordinator.kt`
- tests

Steps:
1. Add linkability preflight to `ReceiptLinkService`.
2. Block direct save for already-linked receipt before creating expense.
3. Add typed duplicate receipt state.
4. Prefer one coordinator that creates expense and link together.
5. Add tests.

Acceptance:
- no orphan expense when link fails.
- duplicate receipt cannot silently create a new expense.

---

## Phase 2 — Fix matching mutation/error UX

Files:
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

Steps:
1. Render `state.error`.
2. Use `UiText`.
3. Add guards and try/catch/finally for reject/skip/manual.
4. Check `Result.failure` from `ReceiptLinkService`, not just thrown exceptions.
5. Keep manual dialog open on failure.
6. Pass mutation state to cards.
7. Disable FAB while auto-matching.

Acceptance:
- every failure is visible.
- double taps are blocked at VM and UI.
- dialogs close only after success.

---

## Phase 3 — Fix stale async receipt state

Files:
- `ReceiptScanViewModel.kt`

Steps:
1. Scope AI in-flight keys by receipt ID.
2. Guard every AI update by captured receipt ID.
3. Make artifact applied/dismissed target captured receipt ID.
4. Cancel scan job on reset/retry/dismiss.
5. Increment request sequence on reset.
6. Guard item correction updates by captured receipt ID.

Acceptance:
- old scan/AI/item results cannot update new receipt.

---

## Phase 4 — Fix currency/amount display

Files:
- `ReceiptScanViewModel.kt`
- `ReceiptScanScreen.kt`
- `ReceiptMatchingScreen.kt`
- `ReceiptItemBreakdownCard.kt`

Steps:
1. Add currency to `ReceiptSaveRequest`.
2. Hide quick save until currency ready.
3. Stop passing blank currency to formatter.
4. Pass receipt currency into item breakdown.
5. Render missing amount as unavailable, not `0.00`.
6. Use shared amount sanitizer.

Acceptance:
- non-EUR receipts render correctly.
- missing values are explicit.
- currency is captured at save.

---

## Phase 5 — Improve matcher amount policy

Files:
- `ReceiptTransactionMatcher.kt`
- `ReceiptRepository.kt`

Steps:
1. Fix conversion availability boolean.
2. Define original-vs-effective amount policy.
3. Use same scoring for auto and manual candidates.
4. Add ownership/shared/not-mine tests.
5. Add currency conversion failure tests.

Acceptance:
- multi-currency matching is deterministic and safe.
- manual candidate ordering matches auto matcher.

---

## Phase 6 — Test hardening

Add these tests:
- `ReceiptScanViewModelSaveAtomicityTest`
- `ReceiptScanDuplicateReceiptTest`
- `ReceiptScanAsyncStaleResultTest`
- `ReceiptScanCurrencyStateTest`
- `ReceiptItemBreakdownCurrencyTest`
- `ReceiptMatchingViewModelMutationTest`
- `ReceiptMatchingErrorRenderingTest`
- `ReceiptTransactionMatcherCurrencyTest`
- `ReceiptMatchingManualCandidateTest`

---

# Recommended Test List

## `ReceiptScanViewModelSaveAtomicityTest`
Cases:
- already-linked receipt blocks before expense creation.
- link failure does not leave orphan expense.
- successful save creates one expense + one link.
- duplicate transaction does not link incorrectly.
- partial link failure path is removed or compensated.

## `ReceiptScanDuplicateReceiptTest`
Cases:
- exact-hash duplicate enters duplicate UI state.
- semantic duplicate enters duplicate UI state.
- linked duplicate shows view-linked-transaction CTA.
- duplicate cannot direct-save without explicit override.

## `ReceiptScanAsyncStaleResultTest`
Cases:
- OCR result for old scan ignored after new scan.
- AI receipt assist result for old receipt ignored.
- category assist result for old receipt ignored.
- item correction result for old receipt ignored.
- reset cancels scan job.

## `ReceiptScanCurrencyStateTest`
Cases:
- initial currency loading disables save.
- quick save unavailable while currency loading.
- save captures currency.
- changing picker before save uses selected currency.
- item breakdown uses selected/receipt currency.

## `ReceiptItemBreakdownCurrencyTest`
Cases:
- USD item displays USD.
- EUR item displays EUR.
- no hardcoded `currency_eur_format`.
- updating item disables category chip.

## `ReceiptMatchingViewModelMutationTest`
Cases:
- approve failure visible and mutation clears.
- manual match failure keeps dialog open.
- reject failure visible.
- skip failure visible.
- auto-match failure summary visible.
- double taps call service once.

## `ReceiptTransactionMatcherCurrencyTest`
Cases:
- same-currency exact amount auto-matches.
- different currency converts before scoring.
- conversion failure applies penalty.
- conversion result equal to input still counts as conversion success.
- shared/not-mine policy is tested.

## `ReceiptMatchingScreenTest`
Cases:
- error card renders.
- auto-match FAB disabled while running.
- row buttons disabled while receipt mutating.
- missing receipt amount displays unavailable.
- manual dialog stays open on failure.

---

# Final Severity Table

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S7-001 | Critical | Unresolved | Direct save can create orphan/duplicate expense if link fails |
| S7-002 | Critical | Unresolved | Duplicate receipt is treated as normal review/save |
| S7-003 | High | Unresolved | Reset/dismiss does not cancel active scan job |
| S7-004 | High | Unresolved | AI assist can stale-write into newer receipt |
| S7-005 | High | Unresolved | Matching errors are not displayed |
| S7-006 | Critical | Unresolved | Manual match ignores link failure and closes dialog |
| S7-007 | High | Unresolved | Matching mutation state not consumed by UI |
| S7-008 | High | Unresolved | Reject/skip lack guards/error handling |
| S7-009 | High | Unresolved | Item breakdown hardcodes EUR |
| S7-010 | High | Unresolved | Missing amounts display as `0.00` |
| S7-011 | Med/High | Unresolved | Blank currency passed to formatters |
| S7-012 | Med/High | Unresolved | Save request does not capture currency |
| S7-013 | Medium | Unresolved | Amount input bypasses shared sanitizer |
| S7-014 | Med/High | Unresolved | Quick save can be offered before currency ready |
| S7-015 | Med/High | Unresolved | AI artifacts marked applied before final save |
| S7-016 | Med/High | Unresolved | Category assist can apply invalid category |
| S7-017 | Medium | Unresolved | Item update cleanup not guaranteed in finally |
| S7-018 | Medium | Unresolved | Item correction can stale-write after receipt change |
| S7-019 | High | Unresolved | Matcher amount policy inconsistent for currency/ownership |
| S7-020 | Medium | Unresolved | Currency conversion availability detection fragile |
| S7-021 | Medium | Unresolved | Matching loads can race/stale-write |
| S7-022 | Medium | Unresolved | Auto-match link failures swallowed per receipt |
| S7-023 | Med/High | Unresolved | Matching receipt totals lack currency |
| S7-024 | Medium | Unresolved | Auto-match UI does not consume running state |
| S7-025 | Low/Med | Design debt | `loadableState` payload ignores suggestions |
| S7-026 | Medium | Unresolved | Raw strings instead of `UiText` |
| S7-027 | Medium | Design debt | Receipt scan screen ViewModel-coupled |
| S7-028 | Medium | Design debt | Receipt matching screen ViewModel-coupled |

---

# Immediate Agent Task List

## Task A — Save/link safety
Fix direct save so it cannot create an expense unless receipt linkability is guaranteed.

## Task B — Matching failure visibility
Render `state.error`; keep manual dialog open on failure; consume mutation state.

## Task C — Stale async guards
Guard AI assist, item correction, and artifact updates by captured receipt ID. Cancel scan on reset.

## Task D — Currency/amount correctness
Remove hardcoded EUR, blank currency, and `0.00` missing amount fallbacks.

## Task E — Matcher policy
Use a single currency-aware amount scorer for auto and manual candidates.

## Task F — Tests
Start with JVM ViewModel/domain tests before Compose UI tests.

---

# Sources Reviewed

- Commit context: https://github.com/panospao7/Cost-agregator/commit/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97
- `ReceiptScanViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt
- `ReceiptScanScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt
- `ReceiptMatchingViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt
- `ReceiptMatchingScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingScreen.kt
- `ReceiptAssistCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/components/ai/ReceiptAssistCard.kt
- `ReceiptItemBreakdownCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/ui/components/ai/ReceiptItemBreakdownCard.kt
- `ReceiptLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptLinkService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `ReceiptTransactionMatcher.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt
- `ReceiptRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/d914916ee9a46f7ec6a63be0a93c421d8c7c0a97/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt