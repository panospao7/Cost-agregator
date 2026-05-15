# Slice 5 Debug Report — Transactions + Manual Add

Commit reviewed: `ea3f716eebba8c513edeeba40db394c10ca829cb`  
Review type: static GitHub source review, not local Gradle/device execution.

Scope:
- `ui/screens/transactions/*`
- `ui/screens/addexpense/*`
- `ui/components/TransferDirectionBadge.kt`
- related utilities: `AmountInputSanitizer`, `OwnershipValidator`, clipboard/prefill paths
- transaction filter, edit dialogs, ownership/transfer state, currency display

---

# Executive Summary

Slice 5 is **partially fixed but still has several high-risk bugs**.

Good progress:
- `AddExpenseViewModel.updateAmount()` uses `AmountInputSanitizer`.
- Manual save has a double-tap guard through `isSaving`.
- `AddExpenseSheet` closes only on `mutation.isSuccess`, not immediately after tapping save.
- `TransactionsScreen` now applies/clears route-provided filters via `LaunchedEffect(initialFilter)`.
- `TransactionsViewModel.clearRouteFilter()` exists.
- Mixed-currency date headers avoid raw summing by showing “Mixed currencies”.
- `TransactionsViewModel.updateOwnership()` exists to avoid sequential stale-object ownership writes.
- `TransactionsViewModel.updateExpenseType()` tries to clear transfer metadata when type changes away from transfer.
- `TransferDirectionBadge` has an unknown/null state.

Critical/high unresolved issues:
1. **EUR users can be permanently blocked from saving manual expenses.**
2. **`AddExpenseViewModel.reset()` can reset currency back to the fake EUR sentinel and never reload it.**
3. **Repository `Result.Error` / thrown save exceptions leave `mutation` running, disabling the Save button forever.**
4. **Transaction date-header totals display same-currency groups using `homeCurrency`, not the transaction currency.**
5. **Transaction amount filter/sort still compares raw `effectiveAmount` across currencies.**
6. **Changing transaction type + transfer metadata is not atomic despite the comment saying it is.**
7. **Several transaction edit dialogs appear to close before persistence succeeds.**
8. **Ownership validation is centralized in Add Expense but still duplicated/incomplete in Transactions edit flows.**
9. **Route-filter clearing uses `TransactionFilter()` instead of `null`, causing active-filter ambiguity.**
10. **Filter sheet cannot display/preserve all active filter fields, especially amount/correlation route filters.**

Recommended fix order:
1. Fix manual-add currency loading and mutation-state bugs.
2. Fix transaction currency display and currency-aware amount filter/sort.
3. Replace non-atomic type/transfer update with one repository transaction.
4. Standardize ownership validation in transaction edit flows.
5. Fix route-filter state semantics and filter-sheet preservation.
6. Add focused ViewModel tests before Compose tests.

---

# Status of Previously Known Slice 5 Findings

## S5-PREV-001 — Initial route filter is consumed/clearable

**Status:** Partially resolved.

Evidence:
- `TransactionsScreen` has:

```kotlin
LaunchedEffect(initialFilter) {
    if (initialFilter != null) {
        viewModel.applyFilter(initialFilter)
    } else {
        viewModel.clearRouteFilter()
    }
}
```

- `TransactionsViewModel.clearRouteFilter()` exists.

Problem:
`clearRouteFilter()` sets:

```kotlin
_filter.value = TransactionFilter()
```

not `null`.

That means “no filter” and “empty active filter” are represented differently across APIs:
- `clearFilter()` uses `null`.
- `clearRouteFilter()` uses `TransactionFilter()`.

Impact:
- UI may think a filter is active because `activeFilter != null`.
- Filter sheet receives a non-null current filter even when no filter should exist.
- Tests can pass parser/route behavior while UI filter chip state remains ambiguous.

Recommendation:
Use one canonical no-filter state: `null`.

---

## S5-PREV-002 — Filter chip matches active filter

**Status:** Partially unresolved.

The filter model supports:
- category
- merchant
- type
- date range
- ownership
- min amount
- max amount
- correlationId

But `TransactionFilterSheet` only visibly edits:
- category
- type
- ownership
- year/month date range

It does not expose:
- merchant route filter
- min/max amount
- correlationId / route context

When the user opens and applies the sheet, current min/max/correlation filters can be dropped.

---

## S5-PREV-003 — Mixed-currency totals are not raw-summed

**Status:** Partially resolved.

Good:
- `TransactionsScreen` checks group currencies.
- If date group contains multiple currencies, date header gets `totalAmount = null` and displays “Mixed currencies”.

Problem:
If a date group contains a single non-home currency, the total is calculated from that currency but formatted with `homeCurrency`.

Example:
- All transactions for May 15 are USD.
- User home currency is EUR.
- Header sums USD amounts but formats as EUR.

This is incorrect.

---

## S5-PREV-004 — Add Expense never falls back to fake EUR silently

**Status:** Unresolved / critical.

`AddExpenseState` still defaults:

```kotlin
homeCurrency: String = "EUR"
```

A guard was added:

```kotlin
if (currentState.homeCurrency == "EUR" && homeCurrencyJob?.isActive == true) {
    error("Loading currency settings...")
    return
}
```

This is worse than a silent fallback for real EUR users:
- `homeCurrencyJob` is a long-lived collector.
- If the real user currency is EUR, `homeCurrency == "EUR"` and the job is active forever.
- Save is blocked forever.

Also, `reset()` recreates `AddExpenseState(date = now)` with default `homeCurrency = "EUR"`.
The existing collector may not re-emit after reset, so the next sheet open can remain stuck on fake EUR.

---

## S5-PREV-005 — Double-tap save is idempotency-safe

**Status:** Partially resolved.

Good:
- `save()` checks `currentState.isSaving`.
- It sets `isSaving = true` before launching the coroutine.

Problem:
The UI disables using `state.mutation.isRunning`, while the guard uses `isSaving`.
These two states can drift.

Critical drift exists:
- repository `Result.Error` clears `isSaving` but does not clear/update `mutation`
- catch block clears `isSaving` but does not clear/update `mutation`

Result:
Save button can stay disabled forever after a failed repository save.

---

## S5-PREV-006 — Transfer fields clear atomically when transaction type changes

**Status:** Unresolved.

Add Expense:
- `selectTransactionType(type)` only changes `transactionType`.
- It does not clear `transferDirection` or `transferAccountName` when leaving `TRANSFER`.
- Save masks the metadata with `takeIf`, so persistence is mostly safe, but UI state remains stale.

Transactions edit:
- `updateExpenseType()` comments “Atomic update”.
- But it performs two repository calls:
  1. `updateExpenseType(expense, newType)`
  2. `updateTransferDetails(...)`

If call 1 succeeds and call 2 fails, database state is inconsistent.

---

## S5-PREV-007 — Ownership/shared validation is centralized

**Status:** Partial.

Good:
- `AddExpenseViewModel.save()` uses `OwnershipValidator.validate(...)`.

Still unresolved:
- `TransactionsViewModel.updateSharedExpenseDetails()` and `updateOwnership()` duplicate validation.
- They only partially validate percentage.
- They parse share amount via `toDoubleOrNull()` instead of the shared amount parser/sanitizer.
- They do not appear to enforce the same rules as Add Expense.
- Public VM API can still receive both `isNotMine = true` and `isSharedExpense = true`.

---

## S5-PREV-008 — Dialogs close only after persistence success

**Status:** Partially unresolved.

Good:
- `AddExpenseSheet` closes on `mutation.isSuccess`.

Problem:
Transaction edit dialogs appear to close immediately after invoking ViewModel mutation.

Example from category flow:

```kotlin
expenseToCategorize?.let { viewModel.updateCategory(it, categoryId, applyToAll) }
expenseToCategorize = null
```

If repository update fails, the dialog is already gone.

---

# Issues Found

---

## S5-001 — Real EUR home-currency users can never save manual expenses

**Severity:** Critical  
**Files:**
- `AddExpenseViewModel.kt`
- `AddExpenseState`

## Problem

`homeCurrency` uses `"EUR"` as a sentinel loading value.

Save guard:

```kotlin
if (currentState.homeCurrency == "EUR" && homeCurrencyJob?.isActive == true) {
    ...
    return
}
```

Since `homeCurrencyJob` is expected to stay active for the lifetime of the ViewModel, a legitimate EUR user is always treated as “still loading”.

## Impact

Manual expense creation is broken for users whose real home currency is EUR.

## Fix Strategy

Replace sentinel string with typed state.

```kotlin
sealed interface CurrencyLoadState {
    data object Loading : CurrencyLoadState
    data class Ready(val code: String) : CurrencyLoadState
    data class Error(val message: UiText) : CurrencyLoadState
}
```

State:

```kotlin
data class AddExpenseState(
    val currencyState: CurrencyLoadState = CurrencyLoadState.Loading,
    ...
)
```

Save:

```kotlin
val currency = when (val currencyState = currentState.currencyState) {
    CurrencyLoadState.Loading -> {
        showError("Loading currency settings...")
        return
    }
    is CurrencyLoadState.Error -> {
        showError("Currency settings unavailable")
        return
    }
    is CurrencyLoadState.Ready -> currencyState.code
}
```

## Acceptance Tests

- Real EUR emitted by repository allows save with `"EUR"`.
- Before first currency emission, save is blocked with loading message.
- Repository failure blocks save with visible error.
- USD emitted saves with `"USD"`.

---

## S5-002 — `reset()` can restore fake EUR and leave ViewModel stuck

**Severity:** Critical  
**File:** `AddExpenseViewModel.kt`

## Problem

`reset()` does:

```kotlin
_state.value = AddExpenseState(date = timeProvider.now())
```

This resets `homeCurrency` to default `"EUR"`.

But the existing `homeCurrencyJob` may not emit again after reset. If no new currency emission happens, the next save sees:
- `homeCurrency == "EUR"`
- `homeCurrencyJob.isActive == true`

and blocks forever.

## Fix Strategy

Do not reset currency state as part of form reset.

Option A:

```kotlin
fun reset() {
    val currencyState = _state.value.currencyState
    _state.value = AddExpenseState(
        date = timeProvider.now(),
        currencyState = currencyState
    )
}
```

Option B:
Separate form state from settings state:

```kotlin
data class AddExpenseFormState(...)
data class AddExpenseUiState(
    val form: AddExpenseFormState,
    val currencyState: CurrencyLoadState
)
```

## Acceptance Tests

- After reset, previously loaded currency remains loaded.
- After successful save and reopen, manual add can save again without waiting for another currency emission.
- Real EUR still works after reset.

---

## S5-003 — Failed save can leave mutation running forever

**Severity:** Critical  
**File:** `AddExpenseViewModel.kt`

## Problem

On repository `Result.Error`:

```kotlin
_state.update {
    it.copy(
        isSaving = false,
        saveResult = SaveResult.Error(...)
    )
}
```

On catch:

```kotlin
_state.update {
    it.copy(
        isSaving = false,
        saveResult = SaveResult.Error(...)
    )
}
```

Neither updates:

```kotlin
mutation = MutationState.error(...)
```

But `AddExpenseSheet` disables Save using:

```kotlin
enabled = !state.mutation.isRunning
```

So after failure:
- `isSaving = false`
- `mutation` remains running
- Save button remains disabled

## Fix Strategy

Every exit path from save must update both states or remove duplicate state.

Preferred:
Use only `mutation`.

```kotlin
private fun setSaveError(message: String) {
    _state.update {
        it.copy(
            isSaving = false,
            saveResult = SaveResult.Error(message),
            mutation = MutationState.error("save", UiText.DynamicString(message))
        )
    }
}
```

Then call it for:
- validation errors
- duplicate
- repository Result.Error
- thrown exception

Better:
Remove `isSaving` and derive it from `mutation.isRunning`.

## Acceptance Tests

- Repository `Result.Error` re-enables Save.
- Thrown exception re-enables Save.
- Duplicate re-enables Save.
- Validation errors never set mutation running.
- Success closes sheet exactly once.

---

## S5-004 — Add Expense save uses live `_state.value.homeCurrency` instead of validated snapshot

**Severity:** Medium  
**File:** `AddExpenseViewModel.kt`

## Problem

Validation reads:

```kotlin
val currentState = _state.value
```

but repository call uses:

```kotlin
currency = _state.value.homeCurrency
```

If currency changes between validation and repository call, saved currency may differ from the state the user saw.

## Fix Strategy

Capture once:

```kotlin
val currency = currentState.currency
...
manualExpenseRepository.addManualExpense(
    currency = currency,
    ...
)
```

## Acceptance Test

- If currency emits a new value during save, transaction uses the currency captured at save tap.

---

## S5-005 — Route-filter clearing uses empty filter instead of no filter

**Severity:** Medium/High  
**Files:**
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`

## Problem

`clearRouteFilter()` sets:

```kotlin
_filter.value = TransactionFilter()
```

while `clearFilter()` sets:

```kotlin
_filter.value = null
```

This creates two different “no filter” states.

## Impact

- Active filter indicator can be wrong.
- Filter sheet can receive non-null `currentFilter` when no filter is active.
- Paging uses the “filter active” code path even though all values are null.

## Fix Strategy

Canonical policy:

```kotlin
private val NoFilter: TransactionFilter? = null
```

Change:

```kotlin
fun clearRouteFilter() {
    if (_filter.value != null) {
        _filter.value = null
        reloadIfAll()
    }
}
```

Add helper:

```kotlin
private fun TransactionFilter?.isMeaningful(): Boolean =
    this != null && (
        categoryId != null ||
        merchantName != null ||
        transactionType != null ||
        dateRange != null ||
        ownership != null ||
        minAmount != null ||
        maxAmount != null
    )
```

UI should show active filter only when meaningful.

## Acceptance Tests

- Opening Transactions with `initialFilter = null` results in `filter.value == null`.
- Filter chip inactive after route clear.
- ALL paging reloads once when a meaningful filter is cleared.
- Empty `TransactionFilter()` is normalized to null.

---

## S5-006 — Filter sheet drops active amount/correlation filters

**Severity:** Medium  
**Files:**
- `TransactionFilter.kt`
- `TransactionFilterSheet.kt`

## Problem

`TransactionFilter` supports `minAmount`, `maxAmount`, and `correlationId`.

`TransactionFilterSheet` does not expose them and does not preserve them when applying a new filter:

```kotlin
TransactionFilter(
    categoryId = selectedCategoryId,
    transactionType = selectedType,
    merchantName = currentFilter?.merchantName,
    dateRange = dateRangeToUse
)
```

Dropped:
- ownership, unless screen re-adds it
- minAmount
- maxAmount
- correlationId

## Impact

Drilldown routes or analytics links can pass amount filters that become invisible and are lost on filter edits.

## Fix Strategy

Either:
1. Add UI for min/max amount and route-context chips, or
2. Preserve non-edited filter fields explicitly.

```kotlin
val newFilter = currentFilter.copy(
    categoryId = selectedCategoryId,
    transactionType = selectedType,
    dateRange = dateRangeToUse,
    ownership = selectedOwnership.toRepositoryOrNull()
).normalize()
```

If amount filters are intentionally route-only, show a non-editable chip:

```text
Amount: 50–100
```

with a clear action.

## Acceptance Tests

- Route min/max filter appears in UI.
- Applying category/date changes does not silently drop min/max unless user clears it.
- Correlation/highlight route metadata is preserved or intentionally consumed.

---

## S5-007 — Date headers use home currency for same-currency transaction groups

**Severity:** High  
**File:** `TransactionsScreen.kt`

## Problem

Date header does:

```kotlin
val currencies = items.map { it.expense.currency }.distinct()
val isMixedCurrency = currencies.size > 1

DateHeader(
    totalAmount = if (isMixedCurrency) null else items.sumOf { it.expense.signedEffectiveAmount() },
    homeCurrency = if (isMixedCurrency) null else homeCurrency
)
```

If all items are USD and home currency is EUR, USD totals are formatted as EUR.

## Fix Strategy

Use the group currency when not mixed:

```kotlin
val groupCurrency = currencies.singleOrNull()

DateHeader(
    totalAmount = if (groupCurrency == null) null else items.sumOf { it.expense.signedEffectiveAmount() },
    currency = groupCurrency
)
```

Rename parameter from `homeCurrency` to `currency` to avoid misuse.

## Acceptance Tests

- Single USD group displays USD even when home currency is EUR.
- Single EUR group displays EUR.
- Mixed USD/EUR group displays mixed-currency label.
- No `DateHeader` default currency exists.

---

## S5-008 — Amount filter/sort compares raw effective amounts across currencies

**Severity:** High  
**Files:**
- `TransactionsViewModel.kt`
- `ExpenseRepository.getExpensesPagedDynamic(...)`

## Problem

Sorting/filtering uses:

```kotlin
expense.effectiveAmount
```

For non-ALL tabs, in-memory amount sort/filter uses raw effective amount.
For ALL tab, repository receives `minAmount`, `maxAmount`, and `sortOrder`; this likely also operates on stored raw amount/effective amount.

## Impact

Mixed-currency lists can sort/filter incorrectly.

Example:
- 100 JPY and 50 USD
- Raw sort says 100 JPY > 50 USD
- Converted sort likely says 50 USD > 100 JPY

## Fix Strategy

Add currency-aware transaction list money model:

```kotlin
data class TransactionAmountUi(
    val originalAmount: Double,
    val originalCurrency: String,
    val effectiveAmount: Double,
    val normalizedAmount: Double?,
    val normalizedCurrency: String,
    val conversionStatus: ConversionStatus
)
```

For amount filter/sort:
- Convert each transaction to home currency using transaction date.
- If conversion fails:
  - either exclude with visible warning,
  - or keep but mark “not comparable”.
- Surface filter quality state.

```kotlin
data class TransactionsDataQuality(
    val partialCurrencyConversion: Boolean,
    val failedCurrencyCodes: Set<String>
)
```

## Acceptance Tests

- Mixed-currency amount sort uses normalized home-currency value.
- Conversion failure shows a warning.
- Amount filter does not silently include/exclude unconverted transactions.
- ALL and non-ALL tabs use the same comparison policy.

---

## S5-009 — Mixed-currency date-header label is hardcoded and not actionable

**Severity:** Low/Medium  
**File:** `TransactionsScreen.kt`

## Problem

Date header displays:

```kotlin
"Mixed currencies"
```

hardcoded.

It does not show:
- source currencies,
- whether normalized total is available,
- why total is omitted.

## Fix Strategy

Move string to resources and optionally show:

```text
Mixed: USD, EUR
```

or:

```text
Total unavailable: mixed currencies
```

Longer-term, use normalized `MoneyAggregate` with warning.

---

## S5-010 — Transaction type + transfer metadata update is not atomic

**Severity:** High  
**Files:**
- `TransactionsViewModel.kt`
- `ExpenseRepository`
- DAO layer

## Problem

`updateExpenseType()` says:

```kotlin
// Atomic update - set type AND transfer fields together
```

But it calls:

```kotlin
expenseRepository.updateExpenseType(expense, newType)
expenseRepository.updateTransferDetails(...)
```

If the first succeeds and the second fails, the transaction type can become TRANSFER without required direction/account, or non-TRANSFER with stale transfer metadata.

## Fix Strategy

Create a single repository method:

```kotlin
suspend fun updateExpenseTypeAndTransferDetails(
    expenseId: Long,
    transactionType: TransactionType,
    transferDirection: TransferDirection?,
    transferAccountName: String?
)
```

DAO:

```kotlin
@Query("""
UPDATE expenses
SET transactionType = :type,
    transferDirection = :direction,
    transferAccountName = :accountName,
    updatedAt = :updatedAt
WHERE id = :expenseId
""")
suspend fun updateTypeAndTransfer(...)
```

Or use Room transaction:

```kotlin
@Transaction
suspend fun updateTypeAndTransferTransaction(...)
```

But one SQL update is preferred.

## Acceptance Tests

- Changing TRANSFER → PURCHASE clears direction/account in one repository call.
- Changing PURCHASE → TRANSFER requires direction/account and persists all fields.
- Simulated DAO failure leaves original row unchanged.
- VM no longer calls two separate mutation methods.

---

## S5-011 — Add Expense transfer fields are stale after leaving TRANSFER

**Severity:** Medium  
**File:** `AddExpenseViewModel.kt`

## Problem

`selectTransactionType(type)` only updates type.

If user:
1. selects TRANSFER,
2. fills direction/account,
3. switches to PURCHASE,
4. switches back to TRANSFER,

old direction/account silently reappear.

Persistence is masked by `takeIf`, but UI state is stale.

## Fix Strategy

```kotlin
fun selectTransactionType(type: TransactionType) {
    _state.update {
        if (type == TransactionType.TRANSFER) {
            it.copy(transactionType = type)
        } else {
            it.copy(
                transactionType = type,
                transferDirection = null,
                transferAccountName = ""
            )
        }
    }
}
```

If preserving draft fields is desired, make it explicit:

```kotlin
val transferDraft: TransferDraft?
```

## Acceptance Tests

- Switching from TRANSFER to PURCHASE clears transfer UI state.
- Saving PURCHASE never passes transfer fields.
- Switching back to TRANSFER requires fresh direction/account.

---

## S5-012 — Transaction ownership edit flow bypasses shared `OwnershipValidator`

**Severity:** High  
**Files:**
- `TransactionsViewModel.kt`
- `OwnershipValidator`

## Problem

`AddExpenseViewModel` uses:

```kotlin
OwnershipValidator.validate(...)
```

But transaction edit methods duplicate partial checks.

Examples:
- `updateOwnership()` checks only percentage range.
- `myShareAmount.toDoubleOrNull()` silently becomes null.
- Shared name/share requirements may not match Add Expense.
- `isNotMine` and `isSharedExpense` may both be true if called directly.

## Fix Strategy

Use `OwnershipValidator` in all public VM mutation paths.

```kotlin
val result = OwnershipValidator.validate(
    isNotMine = isNotMine,
    isSharedExpense = isSharedExpense,
    sharedWithName = sharedWithName,
    sharePercentageText = mySharePercentage,
    shareAmountText = myShareAmount
)
if (result is Invalid) {
    emitError(result.message)
    return
}
```

Then derive parsed values from validator output, not by reparsing strings.

Improve validator to return typed valid data:

```kotlin
sealed interface OwnershipValidationResult {
    data class Valid(
        val normalizedSharedWithName: String?,
        val sharePercentage: Int?,
        val shareAmount: Double?
    )
    data class Invalid(val message: UiText)
}
```

## Acceptance Tests

- Add Expense and Edit Ownership reject identical invalid inputs.
- Both flags true is rejected or normalized predictably.
- Invalid share amount is rejected, not silently treated as null.
- Shared expense requires shared-with name and exactly one share mode if that is the domain rule.

---

## S5-013 — Legacy transaction ownership methods can still cause stale sequential writes

**Severity:** Medium/High  
**File:** `TransactionsViewModel.kt`

## Problem

`updateOwnership()` was added as the safe atomic path, but old methods still exist:
- `updateNotMineDetails(...)`
- `updateSharedExpenseDetails(...)`

They still do sequential writes in some cases.

## Fix Strategy

Make old methods private or remove them.

If UI still needs them, route internally through `updateOwnership()` with current values.

```kotlin
@Deprecated("Use updateOwnership")
fun updateNotMineDetails(...) = updateOwnership(...)
```

## Acceptance Tests

- No UI calls old sequential methods.
- Static/contract test fails if `TransactionsScreen` calls deprecated ownership methods.
- Public VM API exposes one ownership mutation path.

---

## S5-014 — Transaction edit dialogs close before persistence success

**Severity:** High  
**Files:**
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`

## Problem

Category dialog closes immediately:

```kotlin
viewModel.updateCategory(...)
expenseToCategorize = null
```

Likely similar pattern exists for:
- rename merchant
- type change
- ownership edit
- location edit
- recurring mark

## Impact

If mutation fails:
- dialog is already gone,
- user loses input,
- failure appears as snackbar/error only,
- user must reopen and re-enter data.

## Fix Strategy

Use per-mutation state and close dialogs only on success.

ViewModel:

```kotlin
data class TransactionMutationState(
    val operation: TransactionOperation? = null,
    val targetExpenseId: Long? = null,
    val isRunning: Boolean = false,
    val error: UiText? = null,
    val successEvent: TransactionMutationSuccess? = null
)
```

Screen:
- Keep dialog open while `isRunning`.
- Disable confirm while running.
- Show inline error.
- Close only after matching success event.

## Acceptance Tests

- Category update failure keeps dialog open and shows error.
- Category update success closes dialog.
- Rename failure keeps user input.
- Type change failure keeps dialog open.
- Ownership failure keeps dialog open.

---

## S5-015 — Mutation loading state is global and can block unrelated transaction actions

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

Transactions mutations use one `_isLoading` flag for:
- initial loading
- delete
- category update
- merchant rename
- type update
- recurring
- maybe more

This can block or visually confuse the entire screen for row-level operations.

## Fix Strategy

Separate:
- screen loading
- paging loading
- refresh loading
- row mutation loading

```kotlin
val rowMutations: Map<Long, RowMutationState>
```

Acceptance:
- Updating one row disables only that row/dialog.
- Initial list loading still uses screen loading.
- Pull refresh independent from row mutation.

---

## S5-016 — `loadMore()` cancels previous load-more job instead of ignoring duplicate call

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

`loadMore()` does:

```kotlin
if (_isLoadingMoreState.value) return
...
loadMoreJob?.cancel()
loadMoreJob = viewModelScope.launch { ... }
```

The guard usually prevents duplicate calls, but there is still a race before `_isLoadingMoreState.value = true` inside coroutine.

Two rapid calls can:
1. both see false,
2. second cancels first,
3. first may have already started,
4. request state becomes hard to reason about.

## Fix Strategy

Use `Mutex` or set flag before launching:

```kotlin
if (!_isLoadingMoreState.compareAndSet(false, true)) return
```

Since `MutableStateFlow` has no compareAndSet, use `Mutex`:

```kotlin
private val pagingMutex = Mutex()

fun loadMore() {
    viewModelScope.launch {
        pagingMutex.withLock {
            if (!canLoadMore()) return@withLock
            _isLoadingMoreState.value = true
            try { ... } finally { _isLoadingMoreState.value = false }
        }
    }
}
```

## Acceptance Tests

- Two rapid `loadMore()` calls perform one repository page fetch.
- Cancelled previous request does not clear loading for the active request.
- Page index remains correct.

---

## S5-017 — `refresh()` can leave `_isRefreshing` true for non-ALL error path

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

For non-ALL tabs, refresh relies on `transactions.onEach` to clear `_isRefreshing`.

If the upstream flow fails before emitting, `_isRefreshing` may remain true. There is no visible catch around the reactive flow.

## Fix Strategy

Add catch/finally-like state handling in flow:

```kotlin
.catch { e ->
    _isRefreshing.value = false
    _error.emit(...)
    emit(emptyList())
}
```

Or handle refresh as explicit command with state.

## Acceptance Tests

- Repository error during non-ALL refresh clears refreshing state.
- Error is visible.
- Existing list is retained or intentionally replaced.

---

## S5-018 — Transactions reactive flow has no visible catch

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

The main `transactions` flow calls repository flows inside `flatMapLatest`.
If repository flow throws, there is no visible `.catch`.

## Impact

- StateFlow may stop collecting or emit no useful error.
- Refresh spinner can remain active.
- UI may silently freeze.

## Fix Strategy

```kotlin
.flatMapLatest { params ->
    buildTransactionFlow(params)
        .catch { e ->
            if (e is CancellationException) throw e
            _error.emit("Failed to load transactions: ${e.message}")
            emit(emptyList()) // or keep previous via separate state model
        }
}
```

Better:
Expose `TransactionsUiState` with loading/error/data.

---

## S5-019 — Filter/query/tab changes reset ALL paging aggressively and may cause duplicate loads

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

## Problem

For ALL tab:
- `search()`
- `setSortOrder()`
- `setOwnershipFilter()`
- `applyFilter()`
- `refresh()`

all call:
1. reset paging
2. load initial page

The reactive `transactions` flow also observes filter/query changes.

## Impact

- Multiple reloads can happen for one user action.
- Search has no debounce.
- Rapid typing can trigger many page loads.

## Fix Strategy

Model ALL paging as a reactive `flatMapLatest` keyed by query/filter/sort.

Use:
```kotlin
data class TransactionListQuery(...)
```

Then:
```kotlin
queryFlow
    .debounce(250)
    .distinctUntilChanged()
    .flatMapLatest { query -> pager.flow(query) }
```

Short-term:
- Add debounce for search.
- Add distinct checks before resetting/reloading.
- Add request IDs to `loadMore` and `loadInitialAll`.

## Acceptance Tests

- Typing “starbucks” does not trigger one DB page load per keystroke.
- Setting same sort order does not reload.
- Applying same filter does not reload.

---

## S5-020 — `setInitialValuesIfBlank()` does not sanitize initial amount

**Severity:** Medium  
**File:** `AddExpenseViewModel.kt`

## Problem

Manual input goes through:

```kotlin
updateAmount(value) -> AmountInputSanitizer.sanitize(value)
```

But prefill does:

```kotlin
amount = amount ?: current.amount
```

So route/clipboard prefill can place unsanitized text into the field.

## Fix Strategy

```kotlin
amount = amount?.let(AmountInputSanitizer::sanitize) ?: current.amount
```

Also trim merchant:

```kotlin
merchant = merchant?.take(100)?.trim().orEmpty()
```

## Acceptance Tests

- Prefill `"12.345abc"` becomes `"12.34"`.
- Prefill merchant is capped at 100 chars.
- Prefill does not overwrite non-blank user input.

---

## S5-021 — `setInitialValuesIfBlank()` can mark initial values applied even when both current fields are not blank

**Severity:** Low/Medium  
**File:** `AddExpenseViewModel.kt`

## Problem

If either amount or merchant is already non-blank, function returns without setting `initialValuesApplied = true`.

That may be intentional. But if a later recomposition sends the same prefill after user clears fields, prefill can apply unexpectedly because `initialValuesApplied` is still false.

## Fix Strategy

Set `initialValuesApplied = true` whenever a prefill payload was received, even if skipped due to dirty form.

```kotlin
if (hasPrefill) initialValuesApplied = true
```

Acceptance depends on intended UX.

---

## S5-022 — Add Expense validation and messages are hardcoded in ViewModel

**Severity:** Medium  
**File:** `AddExpenseViewModel.kt`

Examples:
- `"Merchant name is required"`
- `"Enter a valid amount"`
- `"Amount is too large"`
- `"Date cannot be in the future"`
- transfer validation messages
- duplicate message

## Fix Strategy

Use `UiText` in state and resources in UI.

```kotlin
val merchantError: UiText?
val amountError: UiText?
```

## Acceptance Tests

- ViewModel emits stable error types/resources, not raw strings.
- UI localizes messages.

---

## S5-023 — Transaction VM user-facing messages are hardcoded

**Severity:** Medium  
**File:** `TransactionsViewModel.kt`

Examples:
- `"Transaction deleted"`
- `"Category updated"`
- `"Failed to update type: ..."`
- `"Location saved"`

## Fix Strategy

Use event model with `UiText`.

```kotlin
sealed interface TransactionsEvent {
    data class Error(val message: UiText) : TransactionsEvent
    data class Success(val message: UiText) : TransactionsEvent
}
```

---

## S5-024 — Transaction date header uses “Mixed currencies” instead of normalized MoneyAggregate

**Severity:** Medium/High  
**File:** `TransactionsScreen.kt`

## Problem

Avoiding raw sum is good, but it prevents useful total display for mixed-currency days.

## Fix Strategy

If multi-currency support is expected:
- Build normalized date-group totals in ViewModel.
- Use `MoneyAggregate`.
- Display normalized total plus warning chip.

```kotlin
data class DateGroupUi(
    val dateLabel: String,
    val items: List<TransactionItemUi>,
    val total: MoneyDisplayUi
)
```

## Acceptance Tests

- Mixed USD/EUR date displays normalized home-currency total when rates exist.
- Conversion failure shows partial warning.
- No raw sum fallback.

---

## S5-025 — Transactions screen is still too monolithic

**Severity:** Medium  
**File:** `TransactionsScreen.kt`

Problem:
The file mixes:
- route filter effect
- list rendering
- tab chips
- sorting
- all dialogs
- row item
- date header
- debug actions
- location correction
- mutation handling

This makes Slice 5 regressions likely.

## Fix Strategy

Extract:
```text
TransactionsRoute.kt
TransactionsScreen.kt
TransactionList.kt
TransactionDateHeader.kt
TransactionRow.kt
TransactionFilterBar.kt
TransactionDialogs.kt
TransactionMutationHost.kt
```

State should be collected in Route only. Screen should be mostly stateless.

---

# Implementation Plan for Agent

## Phase 1 — Fix manual add critical bugs

Files:
- `AddExpenseViewModel.kt`
- `AddExpenseSheet.kt`
- tests

Steps:
1. Replace `homeCurrency: String = "EUR"` sentinel with `CurrencyLoadState`.
2. Preserve currency state during `reset()`.
3. Use captured currency snapshot during save.
4. Replace duplicate `isSaving`/`mutation` state or guarantee they update together.
5. Update all save failure paths to `MutationState.error`.
6. Sanitize prefilled amount.
7. Add tests.

Acceptance:
- EUR users can save.
- Failed save re-enables Save.
- Reset does not break loaded currency.

---

## Phase 2 — Fix transaction currency display/sort/filter

Files:
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`
- currency/domain service if needed

Steps:
1. Rename `DateHeader.homeCurrency` to `currency`.
2. Pass group currency for same-currency date group.
3. Move date grouping to ViewModel as UI model.
4. Add normalized amount model for sort/filter.
5. Show warnings for conversion failure.
6. Add tests for same-currency non-home and mixed-currency cases.

Acceptance:
- USD group displays USD even when home currency is EUR.
- Mixed-currency filters/sorts use normalized value.
- Conversion failure is visible.

---

## Phase 3 — Fix route-filter semantics

Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `TransactionFilter.kt`
- `TransactionFilterSheet.kt`

Steps:
1. Normalize empty filter to `null`.
2. Make `clearRouteFilter()` call canonical clear.
3. Add `TransactionFilter.isMeaningful()`.
4. Preserve or display amount/correlation route filters.
5. Add tests.

Acceptance:
- `initialFilter = null` means no active filter.
- Filter chip reflects real active state.
- Route amount filters are not silently dropped.

---

## Phase 4 — Make transaction mutations durable and dialog-safe

Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `ExpenseRepository`
- DAO

Steps:
1. Add single repository method for type + transfer update.
2. Remove/deprecate old sequential ownership methods.
3. Use `OwnershipValidator` for transaction ownership edits.
4. Add per-dialog/per-row mutation state.
5. Close dialogs only on matching success event.
6. Add tests.

Acceptance:
- Type/transfer update is atomic.
- Ownership validation matches Add Expense.
- Failed dialog mutation keeps dialog open.

---

## Phase 5 — Paging/search hardening

Files:
- `TransactionsViewModel.kt`

Steps:
1. Add paging mutex or atomic request state.
2. Debounce search for ALL tab.
3. Add catch around main transaction flow.
4. Ensure refresh spinner clears on all error paths.
5. Add tests.

Acceptance:
- Duplicate load-more calls do not duplicate/cancel incorrectly.
- Repository errors are visible.
- Refresh state always clears.

---

# Recommended Tests

## `AddExpenseViewModelCurrencyTest`

Cases:
- real EUR allows save.
- loading currency blocks save.
- reset preserves loaded currency.
- reset after success does not reintroduce fake EUR.
- currency snapshot is used during save.

## `AddExpenseViewModelMutationTest`

Cases:
- `Result.Success` sets mutation success.
- `Result.Duplicate` sets mutation error and re-enables Save.
- `Result.Error` sets mutation error and re-enables Save.
- thrown exception sets mutation error and re-enables Save.
- double tap calls repository once.

## `AddExpensePrefillTest`

Cases:
- prefilled amount is sanitized.
- prefilled merchant is trimmed/capped.
- dirty form is not overwritten.
- prefill is not applied unexpectedly after user clears fields.

## `TransactionsRouteFilterTest`

Cases:
- `initialFilter = null` clears to null.
- applying route filter sets exact filter.
- clearing route filter resets paging once.
- empty filter normalizes to null.
- route min/max/correlation are preserved or surfaced.

## `TransactionsCurrencyDisplayTest`

Cases:
- same-currency USD group displays USD, not home EUR.
- same-currency EUR group displays EUR.
- mixed group displays mixed state.
- mixed group with conversion uses normalized total if implemented.

## `TransactionsAmountFilterSortCurrencyTest`

Cases:
- amount sort uses normalized home-currency amount.
- amount filter uses normalized amount.
- conversion failure warning appears.
- ALL and MONTH tabs produce consistent ordering.

## `TransactionTypeTransferAtomicTest`

Cases:
- transfer to purchase clears metadata atomically.
- purchase to transfer requires direction/account.
- simulated second-step failure cannot leave inconsistent row.
- ViewModel calls one repository method.

## `TransactionsOwnershipValidationTest`

Cases:
- edit ownership uses same validator as add expense.
- both not-mine and shared true is rejected or normalized.
- invalid share amount rejected.
- invalid percentage rejected.
- valid shared percentage persists.

## `TransactionDialogPersistenceTest`

Cases:
- category update failure keeps dialog open.
- rename failure keeps dialog open.
- ownership failure keeps dialog open.
- success closes dialog.

## `TransactionsPagingTest`

Cases:
- duplicate loadMore performs one load.
- search debounce prevents per-keystroke page load.
- refresh error clears spinner.
- repository flow exception emits error state.

---

# Final Severity Table

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S5-001 | Critical | Unresolved | Real EUR users can never save manual expenses |
| S5-002 | Critical | Unresolved | `reset()` can restore fake EUR and leave VM stuck |
| S5-003 | Critical | Unresolved | Failed save leaves mutation running forever |
| S5-004 | Medium | Unresolved | Save uses live currency instead of validated snapshot |
| S5-005 | Med/High | Unresolved | Route-filter clear uses empty filter, not null |
| S5-006 | Medium | Unresolved | Filter sheet drops amount/correlation filters |
| S5-007 | High | Unresolved | Same-currency date headers use home currency incorrectly |
| S5-008 | High | Unresolved | Amount filter/sort raw-compares currencies |
| S5-009 | Low/Med | Unresolved | Mixed-currency label hardcoded/not actionable |
| S5-010 | High | Unresolved | Type + transfer update is not atomic |
| S5-011 | Medium | Unresolved | Add Expense transfer fields stale after leaving transfer |
| S5-012 | High | Unresolved | Transaction ownership edit bypasses shared validator |
| S5-013 | Med/High | Unresolved | Old sequential ownership mutation methods remain public |
| S5-014 | High | Unresolved | Transaction edit dialogs close before persistence success |
| S5-015 | Medium | Design debt | Global loading state for row mutations |
| S5-016 | Medium | Unresolved | `loadMore()` duplicate-call race |
| S5-017 | Medium | Needs fix | Refresh can stay true on non-ALL flow error |
| S5-018 | Medium | Unresolved | Main transactions flow lacks visible catch |
| S5-019 | Medium | Design debt | ALL paging reloads aggressively/no search debounce |
| S5-020 | Medium | Unresolved | Prefilled amount bypasses sanitizer |
| S5-021 | Low/Med | Needs decision | Prefill can apply later after dirty skip |
| S5-022 | Medium | Unresolved | Add Expense VM hardcoded strings |
| S5-023 | Medium | Unresolved | Transactions VM hardcoded strings |
| S5-024 | Med/High | Enhancement | Mixed-currency headers omit normalized aggregate |
| S5-025 | Medium | Design debt | `TransactionsScreen` is monolithic |

---

# Immediate Agent Task List

## Task A — Manual add critical fixes
- Replace fake EUR sentinel with typed currency loading state.
- Preserve currency across reset.
- Fix mutation error paths.
- Sanitize prefill.
- Add ViewModel tests.

## Task B — Transaction currency correctness
- Fix DateHeader to use group currency.
- Add currency-aware amount sort/filter.
- Add conversion warning state.

## Task C — Filter route semantics
- Normalize no-filter to `null`.
- Preserve/display route-only amount filters.
- Add filter tests.

## Task D — Atomic mutations
- Implement one repository/DAO method for type + transfer metadata.
- Route all ownership edits through `OwnershipValidator`.
- Remove/deprecate old sequential mutation methods.

## Task E — Dialog/mutation UX
- Add per-dialog mutation state.
- Close transaction edit dialogs only on success.
- Keep input visible on failure.

---

# Source Links Used

- Commit: https://github.com/panospao7/Cost-agregator/commit/ea3f716eebba8c513edeeba40db394c10ca829cb
- `AddExpenseViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt
- `AddExpenseSheet.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt
- `TransactionsViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt
- `TransactionsScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt
- `TransactionFilter.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt
- `TransactionFilterSheet.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilterSheet.kt
- `TransferDirectionBadge.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/components/TransferDirectionBadge.kt