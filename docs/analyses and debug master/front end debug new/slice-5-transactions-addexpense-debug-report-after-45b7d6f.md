# Slice 5 Re-Debug Report — Transactions + Manual Add

Commit reviewed: `45b7d6f079144d350bb1079db3def04d00c43bc4`  
Commit title: `fix(ui): Slice 5 remaining - S5-006/008/009/016/017/018/020/022/023`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:
https://github.com/panospao7/Cost-agregator/commit/45b7d6f079144d350bb1079db3def04d00c43bc4

Scope:
- `AddExpenseViewModel.kt`
- `AddExpenseSheet.kt`
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `TransactionFilterSheet.kt`
- `TransactionFilter.kt`
- `ExpenseRepository.kt`
- `TransactionLifecycleCoordinator.kt`
- `strings.xml`

---

# Executive Summary

Slice 5 is **significantly improved**, especially for the original critical Add Expense bugs.

Confirmed fixed or mostly fixed:
- Real EUR users are no longer blocked by the fake `"EUR"` sentinel.
- `reset()` now preserves loaded currency.
- failed manual save now updates `mutation` error state.
- save uses the currency captured at save tap.
- Add Expense transfer metadata clears when leaving `TRANSFER`.
- route filter clear now uses canonical `null`.
- same-currency transaction date headers now use the group currency.
- category edit dialog now waits for a category-success event before closing.
- main transaction flow now catches repository/flow errors.
- refresh spinner is cleared on flow error.
- prefilled manual amount is sanitized.
- mixed-currency label is localized.

Still unresolved / partially fixed:
1. **Amount sort/filter is still not proven currency-safe.** The recent change added comments, not conversion.
2. **`TransactionFilterSheet` still drops route-only amount/correlation filters in a common path.**
3. **type + transfer update is DB-transaction-wrapped, but post-update side effects can still fire before the outer transaction is fully safe.**
4. **most transaction edit dialogs still close before persistence success.**
5. **Add Expense and Transactions ViewModels still emit mostly hardcoded strings despite new resources.**
6. **paging/search still has no true mutex/debounce architecture.**
7. **manual add still has duplicated `isSaving` + `mutation` state.**
8. **the UI still has weak loading/error representation for currency-loading failure.**

Recommended next order:
1. Finish filter-sheet route-only preservation.
2. Replace amount sort/filter comment with real currency-normalized tests or implementation.
3. Replace type+transfer wrapper with one coordinator method.
4. Convert all edit dialogs to operation-targeted mutation state.
5. Add ViewModel tests for every fixed bug before more UI work.

---

# Status of Previous Slice 5 Issues

| ID | Previous Issue | Current Status | Notes |
|---|---|---:|---|
| S5-001 | Real EUR users cannot save | **Resolved** | `homeCurrency` is now nullable; `EUR` is allowed once loaded. |
| S5-002 | `reset()` restores fake EUR | **Resolved** | `reset()` preserves `_state.value.homeCurrency`. |
| S5-003 | failed save leaves mutation running | **Mostly resolved** | `Result.Error` and `catch` update `mutation.error`; `Result.Loading` still risky. |
| S5-004 | save uses live currency | **Resolved** | save captures `currency` from `currentState`. |
| S5-005 | route-filter clear uses empty filter | **Resolved** | `clearRouteFilter()` now sets `_filter.value = null`. |
| S5-006 | filter sheet drops amount/correlation | **Partially fixed** | Preserved only if category/type/date is selected. Still drops route-only filters otherwise. |
| S5-007 | date headers use home currency | **Resolved** | group currency is passed to `DateHeader`. |
| S5-008 | amount filter/sort raw-compares currencies | **Unresolved / needs proof** | commit added comments only; repository SQL still says effective amount is ownership-adjusted. |
| S5-009 | mixed currency label hardcoded | **Resolved for localization** | Still no normalized aggregate. |
| S5-010 | type + transfer update not atomic | **Partially fixed** | wrapped in Room transaction, but uses two coordinator methods with side effects. |
| S5-011 | Add Expense transfer fields stale | **Resolved** | leaving non-transfer clears transfer fields. |
| S5-012 | ownership edit bypasses validator | **Mostly fixed** | now uses shared validator, but reparses instead of using typed validator output. |
| S5-013 | old sequential ownership methods public | **Partially fixed** | deprecated wrappers route to `updateOwnership`; still public. |
| S5-014 | dialogs close before success | **Partially fixed** | category fixed; rename/type/ownership/location/recurring still close immediately. |
| S5-015 | global row mutation loading | **Unresolved** | still one `_isLoading`. |
| S5-016 | `loadMore()` duplicate race | **Partially fixed** | avoids canceling active job, but no mutex/atomic gate. |
| S5-017 | refresh may stay true on error | **Mostly fixed** | flow catch clears refreshing; initial ALL path also clears in finally. |
| S5-018 | transaction flow lacks catch | **Resolved but crude** | catch emits empty list; better preserve stale data. |
| S5-019 | aggressive reload/no debounce | **Unresolved** | `search()` still reloads immediately. |
| S5-020 | prefill bypasses sanitizer | **Resolved** | prefill amount sanitized; merchant trimmed/capped. |
| S5-021 | prefill can apply later after dirty skip | **Unresolved** | dirty skip still does not set `initialValuesApplied = true`. |
| S5-022 | Add Expense hardcoded VM strings | **Partially fixed at resources only** | VM still emits raw strings. |
| S5-023 | Transactions hardcoded VM strings | **Partially fixed at resources only** | VM still emits raw strings. |
| S5-024 | mixed-currency normalized header aggregate | **Unresolved** | still displays “Mixed currencies.” |
| S5-025 | monolithic Transactions screen | **Unresolved** | still large and stateful. |

---

# Confirmed Fixes

## S5-FIX-001 — Nullable currency state removes fake EUR blocker

**Status:** Resolved  
**Files:**
- `AddExpenseViewModel.kt`

Current state now uses:

```kotlin
val homeCurrency: String? = null
```

Save blocks only when currency is `null`, not when it equals `"EUR"`.

This fixes the critical old bug where real EUR users were treated as “still loading” forever.

## Remaining improvement

This is still not a fully typed state. Prefer:

```kotlin
sealed interface CurrencyLoadState {
    data object Loading : CurrencyLoadState
    data class Ready(val code: String) : CurrencyLoadState
    data class Error(val message: UiText) : CurrencyLoadState
}
```

Reason:
- current nullable state cannot distinguish loading vs repository failure.
- UI renders an empty currency symbol while loading.

---

## S5-FIX-002 — `reset()` preserves loaded currency

**Status:** Resolved  
**File:** `AddExpenseViewModel.kt`

Current behavior:

```kotlin
val loadedCurrency = _state.value.homeCurrency
_state.value = AddExpenseState(date = timeProvider.now(), homeCurrency = loadedCurrency)
```

This prevents the previous fake-currency regression after successful save/reopen.

---

## S5-FIX-003 — Manual save error paths update mutation state

**Status:** Mostly resolved  
**File:** `AddExpenseViewModel.kt`

`Result.Error` and `catch` now set:

```kotlin
mutation = MutationState.error(...)
```

This should re-enable the Save button because `AddExpenseSheet` disables by:

```kotlin
enabled = !state.mutation.isRunning
```

## Remaining risk

The `Result.Loading` branch still does:

```kotlin
_state.update { it.copy(isSaving = true) }
```

If repository ever returns `Result.Loading` as a terminal value, `mutation` remains running.

## Fix

Treat `Result.Loading` as invalid for this one-shot save call:

```kotlin
Result.Loading -> {
    _state.update {
        it.copy(
            isSaving = false,
            saveResult = SaveResult.Error("Unexpected loading result"),
            mutation = MutationState.error("save", UiText.StringResource(R.string.error_unknown))
        )
    }
}
```

Or remove `Result.Loading` from this repository API.

---

## S5-FIX-004 — Add Expense uses captured currency snapshot

**Status:** Resolved  
**File:** `AddExpenseViewModel.kt`

The save path captures:

```kotlin
val currency = currentState.homeCurrency
```

and passes that captured value into `manualExpenseRepository.addManualExpense(...)`.

This fixes the race where a currency emission during save could change persisted currency.

---

## S5-FIX-005 — Add Expense transfer metadata clears when leaving transfer

**Status:** Resolved  
**File:** `AddExpenseViewModel.kt`

`selectTransactionType(type)` now clears:

```kotlin
transferDirection = null
transferAccountName = ""
```

when moving away from `TransactionType.TRANSFER`.

---

## S5-FIX-006 — Route-filter clear now uses canonical null

**Status:** Resolved  
**File:** `TransactionsViewModel.kt`

`clearRouteFilter()` now sets:

```kotlin
_filter.value = null
```

This fixes the old ambiguity between `null` and `TransactionFilter()`.

---

## S5-FIX-007 — Same-currency date headers use group currency

**Status:** Resolved  
**File:** `TransactionsScreen.kt`

The date grouping now determines:

```kotlin
val currencies = items.map { it.expense.currency.uppercase() }.toSet()
val groupCurrency = currencies.singleOrNull()
```

and passes `groupCurrency` to `DateHeader`.

This fixes the old USD-as-EUR display problem for same-currency non-home groups.

## Cleanup

Rename `DateHeader` parameter from `homeCurrency` to `currency` to prevent future misuse.

---

## S5-FIX-008 — Main transaction flow now has catch

**Status:** Resolved with quality caveat  
**File:** `TransactionsViewModel.kt`

The flow now catches errors and clears refresh state:

```kotlin
.catch { e ->
    if (e is CancellationException) throw e
    _isRefreshing.value = false
    _error.emit("Failed to load transactions: ${e.message}")
    emit(emptyList())
}
```

## Remaining issue

Emitting `emptyList()` destroys visible stale data during a transient repository failure.

## Better model

Use a full UI state:

```kotlin
data class TransactionsUiState(
    val items: List<ExpenseWithCategory>,
    val isLoading: Boolean,
    val isRefreshing: Boolean,
    val error: UiText?
)
```

On flow error:
- keep last successful list,
- show snackbar/banner,
- clear refresh spinner.

---

## S5-FIX-009 — Prefilled amount is sanitized

**Status:** Resolved  
**File:** `AddExpenseViewModel.kt`

Current prefill path sanitizes amount and trims/caps merchant:

```kotlin
amount = amount?.let { AmountInputSanitizer.sanitize(it) } ?: current.amount
merchant = merchant?.take(100)?.trim() ?: current.merchant
```

---

# Remaining / New Issues

---

## S5-006R — Filter sheet still drops route-only amount/correlation filters

**Severity:** High  
**Files:**
- `TransactionFilterSheet.kt`
- `TransactionsScreen.kt`

## Problem

The recent commit preserves `minAmount`, `maxAmount`, and `correlationId` only inside this branch:

```kotlin
val newFilter = if (
    selectedCategoryId != null ||
    selectedType != null ||
    dateRangeToUse != null
) {
    TransactionFilter(
        ...
        minAmount = currentFilter?.minAmount,
        maxAmount = currentFilter?.maxAmount,
        correlationId = currentFilter?.correlationId ?: 0L
    )
} else {
    null
}
```

If the active route filter is **only**:

```kotlin
minAmount = 50.0
maxAmount = 100.0
correlationId = 123
```

and the user opens the filter sheet and taps Apply without selecting category/type/date, `newFilter` becomes `null`, so route-only fields are dropped.

## Fix Strategy

Build from `currentFilter`, not from the visible fields only:

```kotlin
val base = currentFilter ?: TransactionFilter()

val newFilter = base.copy(
    categoryId = selectedCategoryId,
    transactionType = selectedType,
    dateRange = dateRangeToUse
).normalizeToNullIfEmpty()
```

Add helper:

```kotlin
fun TransactionFilter.isMeaningful(): Boolean =
    categoryId != null ||
    merchantName != null ||
    transactionType != null ||
    dateRange != null ||
    ownership != null && ownership != OwnershipFilter.ALL ||
    minAmount != null ||
    maxAmount != null ||
    correlationId != 0L

fun TransactionFilter.normalizeToNullIfEmpty(): TransactionFilter? =
    if (isMeaningful()) this else null
```

## Acceptance Tests

- min/max-only route filter survives Apply.
- correlation-only route filter survives Apply or is intentionally consumed.
- empty filter normalizes to null.
- clear explicitly removes route-only fields.

---

## S5-008R — Amount sort/filter is still not proven currency-safe

**Severity:** High  
**Files:**
- `TransactionsViewModel.kt`
- `ExpenseRepository.kt`
- `ExpenseDao`

## Problem

The recent commit added this comment:

```kotlin
// effectiveAmount is home-currency-normalized
```

But `ExpenseRepository.SortOrder` says amount sort uses:

```kotlin
EFFECTIVE_AMOUNT_E_SQL
```

and describes it as **ownership-adjusted**, not currency-normalized.

Dynamic filters also use:

```kotlin
$effectiveAmountExpr >= ?
$effectiveAmountExpr <= ?
```

So the code still appears to compare raw transaction amounts across currencies.

## Impact

Mixed-currency sort/filter can still be wrong.

Example:
- `100 JPY`
- `50 USD`

Raw amount sorting places JPY above USD, which is financially wrong.

## Fix Strategy

Either prove the current expression uses base/home currency or replace it.

Preferred query policy:

```sql
CASE
  WHEN e.baseAmount IS NOT NULL AND e.baseCurrency = :homeCurrency
  THEN ownership_adjusted_baseAmount
  ELSE ownership_adjusted_amount
END
```

Better:
- expose `normalizedComparableAmount`,
- include conversion-quality metadata,
- warn if conversion missing.

## Acceptance Tests

- USD 50 sorts above JPY 100 when home currency is USD/EUR.
- minAmount/maxAmount apply to normalized home-currency value.
- conversion failure produces a warning or deterministic fallback.
- ALL tab and non-ALL tabs sort/filter identically.

---

## S5-010R — Type + transfer update is DB-wrapped, but side effects are not transaction-safe

**Severity:** High  
**Files:**
- `ExpenseRepository.kt`
- `TransactionLifecycleCoordinator.kt`
- `TransactionsViewModel.kt`

## Problem

`ExpenseRepository.updateExpenseTypeAndTransfer(...)` now wraps:

```kotlin
database.withTransaction {
    transactionLifecycleCoordinator.updateType(...)
    transactionLifecycleCoordinator.updateTransferDetails(...)
}
```

This improves DB atomicity, but both coordinator methods independently:
- open their own transactions,
- write their own lifecycle events,
- dispatch post-update side effects after each method.

If `updateType()` dispatches side effects and then `updateTransferDetails()` fails, the outer transaction may roll back DB writes, but side effects may already have run.

## Fix Strategy

Create one coordinator method:

```kotlin
suspend fun updateTypeAndTransferDetails(
    expenseId: Long,
    newType: TransactionType,
    transferDirection: TransferDirection?,
    transferAccountName: String?,
    source: String = "USER_EDIT"
)
```

It should:
1. load existing row once,
2. validate duplicate key once,
3. update type + transfer columns in one DB transaction,
4. write one `UPDATED` event,
5. dispatch side effects once after commit.

## Acceptance Tests

- TRANSFER -> PURCHASE clears metadata in one coordinator call.
- PURCHASE -> TRANSFER persists type/direction/account.
- duplicate collision leaves row unchanged.
- transfer update failure leaves row unchanged.
- side effects fire once and only after commit.

---

## S5-014R — Most dialogs still close before persistence success

**Severity:** High  
**Files:**
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`

## Current status

Category dialog is improved:

```kotlin
viewModel.categoryUpdateSuccess.collect {
    expenseToCategorize = null
}
```

But these still close immediately after invoking a mutation:
- rename merchant,
- change type,
- edit ownership,
- edit location,
- clear location,
- recurrence picker.

Examples:
```kotlin
viewModel.updateMerchant(...)
expenseToRename = null
```

```kotlin
viewModel.updateExpenseType(...)
expenseToChangeType = null
```

```kotlin
viewModel.updateOwnership(...)
expenseToEditOwnership = null
```

## Fix Strategy

Introduce operation-targeted mutation state:

```kotlin
sealed interface TransactionMutation {
    data object Idle : TransactionMutation
    data class Running(val operation: Operation, val expenseId: Long) : TransactionMutation
    data class Success(val operation: Operation, val expenseId: Long) : TransactionMutation
    data class Error(val operation: Operation, val expenseId: Long, val message: UiText) : TransactionMutation
}
```

Screen closes only when success matches the open dialog.

## Acceptance Tests

- rename failure keeps dialog open and preserves text.
- type failure keeps dialog open.
- ownership failure keeps dialog open.
- location save failure keeps dialog open.
- success closes only the matching dialog.

---

## S5-016R — `loadMore()` is improved but still not race-proof

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

The new code avoids canceling an active load-more job, but it still relies on:
- checking `_isLoadingMoreState.value` before launch,
- setting `_isLoadingMoreState.value = true` inside the launched coroutine.

This is better, but still not a true atomic gate.

## Fix Strategy

Use a `Mutex`:

```kotlin
private val loadMoreMutex = Mutex()

fun loadMore() {
    viewModelScope.launch {
        loadMoreMutex.withLock {
            if (_selectedTab.value != TransactionTab.ALL) return@withLock
            if (_isLoading.value || _hasReachedEnd.value) return@withLock
            _isLoadingMoreState.value = true
            try {
                ...
            } finally {
                _isLoadingMoreState.value = false
            }
        }
    }
}
```

## Acceptance Tests

- 10 rapid `loadMore()` calls perform one page fetch.
- page index increments once.
- failure clears loading state.
- a second call after completion loads next page.

---

## S5-021R — Prefill dirty-skip can still apply later

**Severity:** Low/Medium  
**File:** `AddExpenseViewModel.kt`

## Problem

`setInitialValuesIfBlank()` still does:

```kotlin
if (!amountIsBlank || !merchantIsBlank) {
    return@update current
}
initialValuesApplied = true
```

If a prefill payload arrives while the user already typed something, `initialValuesApplied` remains false. If the user later clears fields and recomposition re-sends the same prefill, it can apply unexpectedly.

## Fix

Set `initialValuesApplied = true` whenever a prefill payload was received, even if skipped because the form was dirty.

---

## S5-022R — Add Expense strings are still hardcoded in ViewModel

**Severity:** Medium  
**File:** `AddExpenseViewModel.kt`

The commit added resources, but the ViewModel still emits raw strings:
- `"Loading currency settings..."`
- `"Merchant name is required"`
- `"Enter a valid amount"`
- `"Amount is too large"`
- `"Date cannot be in the future"`
- transfer validation strings
- `"Duplicate expense"`
- `"Unknown error"`

## Fix

Use `UiText` in state:

```kotlin
val merchantError: UiText?
val amountError: UiText?
val saveError: UiText?
```

Then UI resolves with `asString()` or `stringResource`.

---

## S5-023R — Transactions strings are still hardcoded in ViewModel

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

Still emits raw strings:
- `"Transaction deleted"`
- `"Category updated"`
- `"Merchant renamed to ..."`
- `"Type changed to ..."`
- `"Failed to update type: ..."`
- `"Marked as shared expense"`
- etc.

## Fix

Replace:

```kotlin
MutableSharedFlow<String>
```

with:

```kotlin
MutableSharedFlow<TransactionsEvent>
```

where:

```kotlin
sealed interface TransactionsEvent {
    data class Success(val message: UiText) : TransactionsEvent
    data class Error(val message: UiText) : TransactionsEvent
}
```

---

## S5-026 — Category success event is untyped/global

**Severity:** Medium  
**Files:**
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`

## Problem

Category dialog closes on:

```kotlin
_categoryUpdateSuccess.tryEmit(Unit)
```

This event has no:
- expense ID,
- operation ID,
- category ID,
- apply-to-all flag.

It works for a single open dialog, but it is not robust.

## Fix

Emit:

```kotlin
data class CategoryUpdateSuccess(
    val expenseId: Long,
    val applyToAll: Boolean
)
```

Close only if it matches the currently open `expenseToCategorize`.

---

## S5-027 — Currency loading has no explicit UI state

**Severity:** Medium  
**Files:**
- `AddExpenseViewModel.kt`
- `AddExpenseSheet.kt`

## Problem

`homeCurrency == null` means loading, but the UI renders a currency symbol from an empty string:

```kotlin
val hc = state.homeCurrency ?: ""
CurrencyFormatter.formatMoney(0.0, hc, ...)
```

Save is blocked, but the user does not see a clear currency-loading state until they tap Save.

## Fix

Add explicit UI:
- disable Save while currency loading,
- show loading placeholder beside amount,
- show error if currency repository fails.

---

## S5-028 — Search still has no debounce

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

Current `search(query)` immediately:
1. updates query,
2. resets paging,
3. loads first page.

This can fire a DB query per keystroke.

## Fix

Use debounced query flow:

```kotlin
_searchQuery
    .debounce(250)
    .distinctUntilChanged()
    .flatMapLatest { ... }
```

---

# Updated Implementation Plan

## Phase 1 — Finish filter correctness

Files:
- `TransactionFilter.kt`
- `TransactionFilterSheet.kt`
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`

Steps:
1. Add `TransactionFilter.isMeaningful()`.
2. Add `TransactionFilter.normalizedOrNull()`.
3. Build filter sheet result from `currentFilter.copy(...)`.
4. Preserve min/max/correlation even when no visible field is selected.
5. Add route-only filter tests.

---

## Phase 2 — Prove/fix currency-aware amount sorting/filtering

Files:
- `ExpenseDao`
- `ExpenseRepository.kt`
- `TransactionsViewModel.kt`

Steps:
1. Inspect `EFFECTIVE_AMOUNT_E_SQL`.
2. If it uses raw amount, replace with normalized base/home amount.
3. Use same comparable amount for ALL and non-ALL tabs.
4. Add mixed-currency tests.

Required tests:
- USD vs JPY sort.
- min/max range in home currency.
- conversion failure behavior.

---

## Phase 3 — Replace type+transfer wrapper with real coordinator method

Files:
- `TransactionLifecycleCoordinator.kt`
- `ExpenseRepository.kt`
- `TransactionsViewModel.kt`

Steps:
1. Add `updateTypeAndTransferDetails(...)`.
2. Validate duplicate/dedupe once.
3. Update type + transfer fields in one transaction.
4. Write one event.
5. Dispatch side effects once after commit.
6. Remove repository wrapper that calls two coordinator methods.

---

## Phase 4 — Dialog mutation model

Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`

Steps:
1. Add `TransactionMutationState`.
2. Add operation IDs.
3. Keep dialogs open during running/error.
4. Close only matching dialog on success.
5. Remove immediate `expenseToX = null` after mutation calls.

---

## Phase 5 — Message/resource cleanup

Files:
- `AddExpenseViewModel.kt`
- `TransactionsViewModel.kt`
- `strings.xml`

Steps:
1. Convert raw ViewModel strings to `UiText`.
2. Use resource IDs for stable messages.
3. Keep exception detail in logs, not user-facing strings.
4. Add tests asserting message resource identity.

---

# Recommended Tests

## Add Expense

### `AddExpenseViewModelCurrencyTest`
- real EUR emitted -> save allowed.
- null currency -> save blocked.
- reset preserves EUR/USD.
- currency captured at save tap.

### `AddExpenseViewModelMutationTest`
- `Result.Error` re-enables save.
- thrown exception re-enables save.
- `Result.Duplicate` re-enables save.
- `Result.Loading` cannot leave mutation running.
- double tap calls repository once.

### `AddExpensePrefillTest`
- amount prefill sanitized.
- merchant prefill trimmed/capped.
- dirty skip marks prefill consumed.
- prefill cannot apply later after user clears fields.

---

## Transactions

### `TransactionFilterSheetRouteOnlyTest`
- min-only filter survives Apply.
- max-only filter survives Apply.
- min/max/correlation survive Apply.
- Clear removes all fields.
- empty filter normalizes to null.

### `TransactionsAmountCurrencySortFilterTest`
- amount sort uses normalized home amount.
- amount filter uses normalized home amount.
- ALL/non-ALL sort order matches.
- conversion failure surfaces warning or deterministic fallback.

### `TransactionTypeTransferAtomicTest`
- purchase -> transfer saves type, direction, account.
- transfer -> purchase clears direction/account.
- duplicate collision leaves row unchanged.
- side effects fire once after commit.

### `TransactionDialogMutationTest`
- rename failure keeps dialog open.
- type failure keeps dialog open.
- ownership failure keeps dialog open.
- location failure keeps dialog open.
- success closes matching dialog only.

### `TransactionsPagingTest`
- rapid loadMore calls fetch one page.
- failure clears loading.
- search debounce prevents per-keystroke DB loads.
- repository flow error preserves stale list if UI state refactor is done.

---

# Updated Severity Table

| ID | Severity | Current Status | Summary |
|---|---:|---|---|
| S5-001 | Critical | Resolved | Real EUR no longer blocked |
| S5-002 | Critical | Resolved | reset preserves loaded currency |
| S5-003 | Critical | Mostly resolved | mutation error paths fixed; `Result.Loading` risk remains |
| S5-004 | Medium | Resolved | save uses captured currency |
| S5-005 | Med/High | Resolved | route clear uses null |
| S5-006R | High | Partially unresolved | filter sheet still drops route-only filters |
| S5-007 | High | Resolved | date header uses group currency |
| S5-008R | High | Unresolved | amount sort/filter still not proven currency-normalized |
| S5-009 | Low/Med | Resolved | mixed-currency label localized |
| S5-010R | High | Partial | DB wrapper exists but side effects not safely combined |
| S5-011 | Medium | Resolved | Add Expense clears transfer draft when leaving transfer |
| S5-012 | High | Mostly fixed | shared validator used; typed result still missing |
| S5-013 | Med/High | Partial | deprecated wrappers remain public |
| S5-014R | High | Partial | only category dialog waits for success |
| S5-015 | Medium | Unresolved | global loading state remains |
| S5-016R | Medium | Partial | loadMore better but no mutex |
| S5-017 | Medium | Mostly fixed | refresh clears on flow/load error |
| S5-018 | Medium | Resolved/rough | catch added; emits empty list |
| S5-019 | Medium | Unresolved | search still no debounce |
| S5-020 | Medium | Resolved | prefill amount sanitized |
| S5-021R | Low/Med | Unresolved | dirty prefill skip not consumed |
| S5-022R | Medium | Partial | resources added but VM strings still raw |
| S5-023R | Medium | Partial | resources added but transaction VM strings still raw |
| S5-024 | Med/High | Unresolved | no normalized mixed-currency date aggregate |
| S5-025 | Medium | Unresolved | screen remains monolithic |
| S5-026 | Medium | New | category success event is untyped/global |
| S5-027 | Medium | New | currency loading has weak UI |
| S5-028 | Medium | New/reconfirmed | search reloads per keystroke |

---

# Immediate Agent Task List

## Task A — Fix `TransactionFilterSheet` route-only preservation
This is the most concrete bug remaining from the recent commit.

## Task B — Implement or prove currency-normalized amount sort/filter
Do not accept comments as a fix. Add tests.

## Task C — Replace type+transfer update with one coordinator method
Avoid two post-update side-effect paths.

## Task D — Convert all transaction dialogs to mutation-targeted success/error handling
Category is only the first one.

## Task E — Add tests for the already-fixed critical Add Expense bugs
Lock in:
- real EUR save,
- reset currency preservation,
- mutation error recovery,
- sanitized prefill.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/45b7d6f079144d350bb1079db3def04d00c43bc4
- `AddExpenseViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt
- `AddExpenseSheet.kt`: https://github.com/panospao7/Cost-agregator/blob/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt
- `TransactionsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt
- `TransactionsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt
- `TransactionFilter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt
- `ExpenseRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/45b7d6f079144d350bb1079db3def04d00c43bc4/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt