# Slice 5 Debug Report — Transactions, Manual Add, Filters, Edit/Delete/Location Flows

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/transactions/*`
- `ui/screens/addexpense/*`
- `ui/components/TransferDirectionBadge.kt`
- `ui/components/LocationSearchPicker.kt`
- expected parser surface: `ui/util/ClipboardAmountParser.kt` or equivalent
- connected repositories/use cases:
  - `ExpenseRepository`
  - `ManualExpenseRepository`
  - `CategoryRepository`
  - `RecurringExpenseRepository`
  - `MerchantLocationRepository`
  - `CurrencySettingsRepository`

Sources inspected:
- Transactions directory: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions
- `TransactionsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt
- `TransactionsScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt
- `TransactionFilter.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt
- `TransactionFilterSheet.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilterSheet.kt
- Add expense directory: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense
- `AddExpenseViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt
- `AddExpenseSheet.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt
- Components directory: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components
- `TransferDirectionBadge.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/TransferDirectionBadge.kt
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 5 is a high-risk financial correctness slice. It owns the user-facing transaction ledger, manual transaction creation, filters, pagination, edit flows, location correction, ownership/shared-expense metadata, transfer metadata, and category mutation.

The current implementation is feature-rich and partially hardened. Good signs:
- `TransactionsViewModel` uses `TimeProvider` for tab date ranges.
- `TransactionFilterSheet` receives `referenceNowMs` instead of using raw system time for filter chips.
- ALL-tab pagination has explicit loading guards, request IDs, and dedupe.
- The ownership update path includes an atomic `updateOwnership(...)` method with a comment explaining why sequential updates are unsafe.
- Add Expense validates merchant, amount, transfer metadata, future dates, and shared-expense shape.
- Location edits update both the expense and merchant-location correction cache.

Main problems:
1. `TransactionsScreen.kt` is monolithic and difficult to test.
2. Transaction list currency display likely formats mixed-currency raw amounts as the home currency.
3. Transfer type edits are not atomic and may leave stale transfer details.
4. Add Expense can save with placeholder `"EUR"` before home currency is loaded.
5. Add Expense amount/share input filtering is too weak.
6. Mutation dialogs close before persistence result is known.
7. Initial transaction filters can become stale because `null` does not clear an old filter.
8. Ownership/shared validation is inconsistent between Add Expense and Edit Ownership.
9. Location edit leaks a domain service directly into the UI.
10. Many strings and accessibility labels remain hardcoded.
11. Filter/pagination behavior needs contract tests before deeper UI debugging.
12. Clipboard amount parser inventory/test status must be verified; it was expected by slice scope but not visible in the opened components tree.

Recommended strategy:
- Do not rewrite the ledger.
- Add ViewModel contract tests first.
- Fix money/currency and atomic mutation paths before cosmetic UI refactors.
- Extract UI components only after behavior tests exist.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*TransactionsViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AddExpenseViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TransactionFilter*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ClipboardAmountParser*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TransferDirectionBadge*" --stacktrace
```

Inventory current tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Transaction*" -o \
  -iname "*AddExpense*" -o \
  -iname "*Clipboard*" -o \
  -iname "*Transfer*"
```

If Compose tests are configured:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Robolectric Compose tests are configured:

```bash
./gradlew :app:testDebugUnitTest --tests "*TransactionsScreen*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AddExpenseSheet*" --stacktrace
```

Stop on first compile failure.

---

## 3. Current architecture map

### Transactions pipeline

```text
selected tab/search/filter/refresh
        ↓
TransactionsViewModel.transactions
        ↓
ExpenseRepository period query OR ALL-tab paged query
        ↓
in-memory amount/search/ownership filtering where needed
        ↓
sort/group into groupedTransactions
        ↓
TransactionsScreen LazyColumn
        ↓
TransactionItem rows + dialogs/actions
```

### Manual add pipeline

```text
AddExpenseSheet input fields
        ↓
AddExpenseViewModel state
        ↓
validation
        ↓
ManualExpenseRepository.addManualExpense(...)
        ↓
SaveResult
        ↓
sheet dismisses on success
```

### Main mutation paths

Transactions:
- delete
- update category
- rename merchant
- update transaction type
- transfer details
- not-mine/shared ownership
- location save/clear
- mark recurring

Add Expense:
- manual transaction save
- optional recurrence
- optional transfer metadata
- optional ownership/shared metadata
- home-currency assignment

---

# 4. Issues

## S5-001 — `TransactionsScreen.kt` is a monolithic high-blast-radius screen

Severity: High  
Files:
- `TransactionsScreen.kt`

Evidence:
The file owns:
- root screen state collection
- top app bar
- search
- sort menu
- filter sheet launch
- tab UI
- list rendering
- empty/loading/end states
- delete/category/rename/type/ownership/debug/location dialogs
- transaction row component
- date header
- empty state component

Problem:
This makes failures hard to localize. A transaction-row issue, filter bug, or dialog bug all compile/retest the same large file.

Fix strategy:
Extract UI-only components without changing behavior.

Implementation plan:
Create:
- `TransactionsRoute.kt` — collects ViewModel state and handles one-off events.
- `TransactionsScreenContent.kt` — pure content with state + callbacks.
- `TransactionsTopBar.kt`
- `TransactionsTabRow.kt`
- `TransactionsList.kt`
- `TransactionItem.kt`
- `TransactionDialogs.kt`
- `TransactionEmptyState.kt`
- `TransactionDateHeader.kt`

Keep `TransactionsViewModel` unchanged during first extraction.

Acceptance:
- `TransactionsScreen.kt` becomes route/orchestration only.
- `TransactionItem` can be Compose-tested independently.
- Dialogs can be tested without a full transaction list.
- No business logic moves into composables.

---

## S5-002 — Initial filter application can leave stale filters

Severity: High  
Files:
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`
- `TransactionFilter.kt`

Evidence:
`TransactionsScreen` applies `initialFilter` only when it is non-null. If a previous route opened with a filter and a later route opens with `null`, the old ViewModel filter is not cleared.

`TransactionFilter` includes `correlationId`, but this does not appear to be used to make filter application one-shot or route-stable.

Problem:
A user can land on Transactions from dashboard/analytics with a drill-down filter, then later open Transactions normally and still see the old filter.

Fix strategy:
Make route filter application explicit and idempotent.

Implementation plan:
- Treat `initialFilter == null` as “clear route-provided filter” when the route key changes.
- Use `correlationId` or a dedicated `initialFilterKey`.
- Add ViewModel API such as `applyInitialFilter(filter, key)` that ignores repeated same-key calls but clears stale route filters.

Acceptance:
- Opening Transactions with filter A applies A once.
- Recomposition does not re-apply A repeatedly.
- Opening Transactions with `null` after A clears route filter.
- Manual user filter changes are not overwritten by recomposition.
- Unit test covers route A → route null.

---

## S5-003 — Selected tab label can disagree with an explicit date-range filter

Severity: Medium/High  
Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `TransactionFilterSheet.kt`

Evidence:
The default tab is `MONTH`. If a filter has a date range, non-ALL queries use that filter date range, but selected tab can still show Month.

Problem:
The list may display a week/day/year drill-down while the tab UI still says Month. This is confusing and can cause wrong assumptions in tests.

Fix strategy:
Introduce an explicit “filtered mode” visual state.

Implementation plan:
- Keep selected tab for base quick ranges.
- When `activeFilter.dateRange != null`, show a prominent filter chip/summary.
- Optionally add a synthetic `CUSTOM` tab or clear tab selection style.
- Add tests verifying that dateRange filter is visible even when selected tab remains Month.

Acceptance:
- A date-range filter is always visible to the user.
- Tab counts are not interpreted as filtered result counts.
- Route from dashboard drill-down has a clear date label.

---

## S5-004 — Mixed-currency list totals are likely incorrect

Severity: Critical financial correctness  
Files:
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`
- `Expense`
- `CurrencySettingsRepository`
- `CurrencyConverter`

Evidence:
`DateHeader` receives a date total derived from each expense’s signed effective amount and formats it using `homeCurrency`.

Problem:
If the grouped items contain multiple currencies, summing raw effective amounts and formatting as home currency is wrong. Example: `10 USD + 10 EUR` must not be displayed as `20 USD` without conversion.

Fix strategy:
Move display-total computation into ViewModel/domain with currency conversion.

Implementation plan:
- Add a display model:
  - `TransactionListRow`
  - `TransactionDateGroup`
  - `displayAmount`
  - `displayCurrency`
  - `originalAmount/currency` if needed
- Convert group totals to home currency using a single currency service.
- Do not let composables sum money.
- If conversion unavailable, show:
  - original amount per row, and
  - group total as “mixed currency” or degraded state.

Acceptance:
- Date header total equals converted sum in home currency.
- Mixed-currency fixtures fail if raw amounts are summed.
- Row display clearly distinguishes original vs converted amount if both are shown.
- Add invariant test: two expenses in different currencies render a converted total.

---

## S5-005 — `homeCurrency` placeholder `"EUR"` can leak into UI and saves

Severity: High  
Files:
- `TransactionsViewModel.kt`
- `AddExpenseViewModel.kt`

Evidence:
Both ViewModels initialize home currency with `"EUR"` while waiting for `CurrencySettingsRepository.homeCurrency()`.

In `AddExpenseViewModel.save()`, the repository call uses the current state’s home currency from inside the coroutine. If the user saves before the flow emits, the transaction can be saved as EUR even for a non-EUR user.

Problem:
This is a real data correctness risk. A placeholder should never become persisted financial data.

Fix strategy:
Make currency loading explicit and block save until loaded.

Implementation plan:
- Replace placeholder with UI state:
  - `homeCurrency: String?`
  - `isCurrencyLoaded: Boolean`
- In Add Expense:
  - disable Save until currency is loaded.
  - if save is called directly before loaded, return `SaveResult.Error`.
- In Transactions:
  - loading/degraded display until home currency is loaded, or use repository-provided synchronous default that cannot be wrong.
- Use a typed fallback only in previews/tests, not production persistence.

Acceptance:
- AddExpense cannot call `ManualExpenseRepository.addManualExpense` with placeholder currency.
- Unit test: repository delays home currency; save does not persist EUR.
- UI test: save button disabled while currency unresolved.

---

## S5-006 — Add Expense amount/share input sanitizer is weak

Severity: High  
Files:
- `AddExpenseViewModel.kt`
- `AddExpenseSheet.kt`
- shared amount utilities from Slice 2

Evidence:
Amount inputs allow digits, dot, and comma with no strict shape enforcement. Share amount has similar filtering. The parser is only applied during save.

Problem:
Users can type invalid states such as multiple separators, ambiguous localized formats, too many decimals, or unsupported combinations. This increases validation errors and parser mismatch.

Fix strategy:
Use a single shared `AmountInputSanitizer` and pure tests.

Implementation plan:
- Add sanitizer in a shared domain/UI utility.
- Use it for:
  - Add Expense amount
  - shared amount
  - filter min/max
  - clipboard parser output
- Enforce:
  - one decimal separator
  - max fraction digits, probably 2 for money
  - max integer length
  - optional locale separator handling
- Decide whether comma means decimal or thousands separator.

Acceptance:
- Unit tests for sanitizer.
- `updateAmount("1.2.3")` cannot produce `"1.2.3"`.
- `updateAmount("12.345")` becomes accepted policy output or rejected state.
- Shared amount and main amount follow the same rules.

---

## S5-007 — Add Expense save is not idempotency-safe

Severity: High  
File:
- `AddExpenseViewModel.kt`

Evidence:
`save()` reads current state and sets `isSaving = true`, but it does not first return if already saving.

Problem:
A fast double-tap, keyboard submit, or direct test invocation can launch multiple save jobs. Duplicate detection may catch some cases, but financial write paths should be idempotent at the UI layer too.

Fix strategy:
Guard `save()` at the beginning.

Implementation plan:
- If `state.isSaving` is true, return.
- Optionally track `saveRequestId`.
- Disable all form mutation during save if needed.
- Add test that two rapid `save()` calls invoke repository once.

Acceptance:
- Repository is called once for two immediate save invocations.
- Save button remains disabled while saving.
- Duplicate detection remains as a second line of defense.

---

## S5-008 — Merchant suggestion search has no failure handling

Severity: Medium/High  
File:
- `AddExpenseViewModel.kt`

Evidence:
Merchant suggestions are loaded in a debounced coroutine. The repository call is not wrapped in failure handling.

Problem:
If merchant search throws, the coroutine can fail and leave stale suggestions or no user feedback.

Fix strategy:
Treat suggestions as recoverable UI state.

Implementation plan:
- Add suggestion loading/error state or silently clear on failure with logging.
- Use request token to prevent stale results from old queries.
- Add tests:
  - query “ab”, repository throws → suggestions clear and no crash.
  - query changes “am” → “amazon”; old result cannot overwrite new result.

Acceptance:
- Suggestion failure does not crash ViewModel scope.
- Stale suggestions are not displayed after failed/current query.
- Tests use virtual time for debounce.

---

## S5-009 — Suggestion amount formatting uses default locale

Severity: Medium  
File:
- `AddExpenseViewModel.kt`

Evidence:
Selecting a merchant suggestion formats average amount into the amount text. The formatting appears locale-sensitive.

Problem:
Default locale can produce comma decimals. If the parser expects a different shape, prefilled amounts may fail validation or differ by device locale.

Fix strategy:
Use the same amount formatter/sanitizer policy for suggestion prefill.

Implementation plan:
- Do not use raw default-locale formatting for editable amount fields.
- Format with a stable editable-money formatter.
- Add tests with a comma-decimal locale and dot-decimal locale.

Acceptance:
- Suggestion prefill parses successfully in all supported locales.
- The value displayed by prefill is accepted by `save()`.

---

## S5-010 — Transfer type update is not atomic and may leave stale transfer metadata

Severity: Critical data integrity  
File:
- `TransactionsViewModel.kt`

Evidence:
When changing type, the ViewModel first updates the transaction type, then separately updates transfer details if the new type is transfer. When changing away from transfer, there is no explicit clear of old transfer direction/account in the ViewModel.

Problem:
Failure between the two writes can leave a transaction marked transfer without details, or old transfer metadata on a non-transfer transaction. This is exactly the kind of ledger corruption that later filters/badges can expose.

Fix strategy:
Create one repository transaction for type + transfer metadata.

Implementation plan:
- Add repository method:
  - update transaction type
  - set transfer direction/account when type is TRANSFER
  - clear transfer fields when type is not TRANSFER
  - run in a DB transaction
- Make `TransactionsViewModel.updateExpenseType(...)` call only this atomic method.
- Add tests:
  - purchase → transfer with direction/account stores both.
  - transfer → purchase clears direction/account.
  - repository failure leaves original unchanged.

Acceptance:
- No public UI path updates type and transfer details in separate writes.
- Transfer filter returns only true transfer transactions.
- `TransferDirectionBadge` never renders for non-transfer rows with stale metadata.

---

## S5-011 — Ownership/shared-expense validation is inconsistent

Severity: High  
Files:
- `TransactionsViewModel.kt`
- `AddExpenseViewModel.kt`
- `EditOwnershipDialog` inside `TransactionsScreen.kt`

Evidence:
Add Expense requires stronger shared-expense validation:
- not-mine and shared cannot both be true.
- shared requires shared-with name.
- shared requires either percentage or amount, not both.
- percentage must be 0..100.
- amount must be greater than 0.

Transaction edit paths are weaker:
- `updateOwnership(...)` validates percentage range but not all shared invariants.
- older public methods `updateNotMineDetails(...)` and `updateSharedExpenseDetails(...)` remain callable and can still be used accidentally.

Problem:
The same concept can be valid in Add Expense but invalid after editing an existing transaction.

Fix strategy:
Extract one ownership validator.

Implementation plan:
- Create `OwnershipInput` and `OwnershipValidationResult`.
- Use the same validator in:
  - AddExpenseViewModel
  - TransactionsViewModel.updateOwnership
  - visual split entry if applicable
- Deprecate or make private the older sequential update methods.
- If other call sites need them, route them through the atomic update method.

Acceptance:
- Shared/not-mine invariants are identical across add/edit.
- Unit tests cover all ownership combinations.
- Old unsafe methods are either private, deprecated with tests, or removed.

---

## S5-012 — Mutation dialogs close before persistence result

Severity: High UX/debuggability  
Files:
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`

Affected flows:
- category update
- rename merchant
- change transaction type
- edit ownership
- edit location
- delete
- mark recurring

Evidence:
Dialogs generally set local dialog state to null immediately after calling the ViewModel mutation.

Problem:
If the mutation fails, the dialog is gone and the user only gets a snackbar. The input context is lost. This also makes automated UI debugging harder.

Fix strategy:
Represent mutations as stateful operations.

Implementation plan:
- Add `TransactionMutationState`:
  - `idle`
  - `saving(action, expenseId)`
  - `success`
  - `error(action, message)`
- Keep the dialog open on failure.
- Close only after success.
- For delete, consider undo instead of keeping confirmation open.
- At minimum, pass loading/error state into dialogs.

Acceptance:
- Rename failure keeps rename dialog open with error text.
- Category failure keeps category dialog open.
- Type change failure keeps type dialog open.
- Location save failure keeps location dialog open.
- Tests verify dialog-close behavior.

---

## S5-013 — Delete is destructive and has no undo

Severity: Medium/High  
Files:
- `TransactionsScreen.kt`
- `TransactionsViewModel.kt`
- repository delete path

Problem:
A financial ledger delete should ideally support undo or soft-delete. Current flow confirms then deletes. If delete succeeds, recovery is not visible.

Fix strategy:
Short-term:
- Improve confirmation details: merchant, amount, date.
- Add snackbar with Undo if repository supports restore.
Long-term:
- Soft delete with retention and explicit purge.

Implementation plan:
- Add repository support for undo if possible.
- Emit `TransactionDeleted(expenseSnapshot)` event.
- Snackbar action restores.
- If undo is not feasible, document hard-delete behavior and add stronger confirmation.

Acceptance:
- Delete test verifies either undo restore or explicit hard-delete behavior.
- UI does not hide deletion errors.
- ALL-tab pagination refresh after delete is tested.

---

## S5-014 — Location edit leaks domain service into UI

Severity: Medium/High  
Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `LocationSearchPicker.kt`
- `EditLocationDialog`

Evidence:
`TransactionsViewModel` exposes `geocodingService` publicly and passes it into the UI/dialog.

Problem:
The composable/dialog can perform domain work directly. This weakens testability and can bypass privacy/location policy decisions. It also makes previews and component tests harder.

Fix strategy:
Move location search/save orchestration behind ViewModel state/actions.

Implementation plan:
- ViewModel owns:
  - location query text
  - search results
  - loading/error
  - selected result
- UI only displays state and calls callbacks.
- Inject privacy/location gate if applicable.
- Keep saving merchant correction cache in ViewModel/repository layer.
- Add test:
  - searching calls fake geocoder.
  - save updates expense and merchant correction.
  - clear location clears expense.
  - denied privacy/location state does not call geocoder.

Acceptance:
- No public `val geocodingService` exposed from `TransactionsViewModel`.
- `EditLocationDialog` has no domain service dependency.
- Location edit is deterministic in unit tests.

---

## S5-015 — Location correction behavior is good but untested

Severity: Medium  
File:
- `TransactionsViewModel.kt`

Evidence:
Saving a location updates the expense and saves a merchant-location correction cache entry. This is desirable because future expenses for the merchant can reuse the correction.

Problem:
Because this is cross-feature behavior, it needs a contract test. Otherwise future refactors may only update the expense and forget the cache.

Fix strategy:
Add a targeted mutation test.

Acceptance:
- `updateLocation(...)` calls:
  - `expenseRepository.updateExpenseLocation(...)`
  - `merchantLocationRepository.saveCorrection(...)`
- The correction uses normalized merchant key.
- On expense update failure, correction is not saved.
- On correction failure, policy is explicit:
  - either whole mutation fails, or
  - expense save succeeds and cache failure is non-fatal with warning.

---

## S5-016 — Category color parsing is inconsistent

Severity: Medium  
Files:
- `TransactionsScreen.kt`
- `AddExpenseSheet.kt`
- Category model/rendering

Evidence:
Add Expense category UI parses category color as a hex color string. Transaction row parsing appears to convert a category color value with integer parsing. If the same category color is stored as `#RRGGBB`, the transaction row falls back to default color.

Problem:
The same category may display different colors across Add Expense and Transactions.

Fix strategy:
Create one safe category color parser.

Implementation plan:
- Add `CategoryColorParser.parse(colorString): Color`.
- Support:
  - `#RRGGBB`
  - `#AARRGGBB`
  - integer strings if legacy data exists
  - fallback color
- Use it in all category UI components.

Acceptance:
- Category color renders consistently in Add Expense and Transactions.
- Unit tests cover hex, ARGB, invalid, and legacy integer values.

---

## S5-017 — Date headers and list grouping should be model-driven

Severity: Medium  
Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`

Evidence:
`groupedTransactions` groups by formatted date string. UI then computes totals for each group.

Problem:
A formatted string is not a stable domain key. It is hard to test ordering, locale changes, and date total correctness.

Fix strategy:
Move to typed group model.

Implementation plan:
- `TransactionDateGroup(dateKey, displayLabel, rows, convertedTotal, itemCount)`
- ViewModel/domain creates groups.
- UI renders groups without computing totals.

Acceptance:
- Group ordering is tested by epoch date key.
- Group display label can be localized independently.
- UI no longer computes money totals.

---

## S5-018 — Filter sheet reset is local-only until apply

Severity: Low/Medium UX  
File:
- `TransactionFilterSheet.kt`

Evidence:
The sheet’s reset button clears local state. It does not immediately call `onClear`.

Problem:
This can be fine, but the label “Reset all” may imply immediate clearing. If the user taps reset then dismisses, nothing changes.

Fix strategy:
Decide the UX:
- Option A: rename to “Reset form”.
- Option B: call `onClear` immediately.
- Option C: keep local reset but add Apply button behavior clearly.

Acceptance:
- User action semantics are clear.
- Compose test covers reset + apply.
- Dismiss after reset behavior is documented/testable.

---

## S5-019 — Filter validation should reject invalid amount ranges

Severity: Medium  
Files:
- `TransactionFilterSheet.kt`
- `TransactionsViewModel.kt`
- `TransactionFilter.kt`

Problem:
The filter model supports `minAmount` and `maxAmount`. The agent must verify current sheet parsing. The system should reject:
- negative bounds if unsupported
- max < min
- ambiguous decimals
- too many decimals

Fix strategy:
Use shared amount sanitizer/parser from S5-006.

Acceptance:
- max < min shows inline validation error.
- invalid amount text does not apply filter.
- ALL-tab backend query and non-ALL in-memory filter use the same normalized bounds.

---

## S5-020 — Tab counts ignore active filters/search

Severity: Medium  
File:
- `TransactionsViewModel.kt`

Evidence:
Tab counts are loaded per tab period and refreshed via refresh trigger. They are not active-filter/search result counts.

Problem:
This may be intended, but the UI can mislead users if badges remain global while the list is filtered.

Fix strategy:
Define badge semantics:
- Global tab counts.
- Or filtered tab counts.
- Or hide counts when a filter/search is active.

Recommended short-term:
- Hide or visually mark counts while search/filter active.

Acceptance:
- Test asserts count behavior with active filter.
- Docs/comments define badge semantics.

---

## S5-021 — Mark-as-recurring does not update transaction row state

Severity: Medium  
Files:
- `TransactionsViewModel.kt`
- `TransactionsScreen.kt`
- `RecurringExpenseRepository`

Evidence:
`markAsRecurring(...)` adds a recurring expense record and emits a success message. It does not appear to update the original expense or refresh the row after success.

Problem:
The action may remain available for the same transaction, allowing duplicates or confusing repeated marking.

Fix strategy:
Define recurring-link semantics.

Implementation plan:
- If an expense can be linked to a recurring template, store that link or flag.
- Hide/disable “mark recurring” when already linked.
- Repository should reject duplicate merchant/amount/frequency entries if duplicates are not allowed.
- Refresh after success if UI state depends on it.

Acceptance:
- Mark recurring twice does not create duplicate templates.
- Row/action reflects already-recurring state.
- Test covers duplicate prevention.

---

## S5-022 — Payment method bank transfer vs transaction type transfer is confusing

Severity: Medium  
Files:
- `AddExpenseSheet.kt`
- `AddExpenseViewModel.kt`
- `TransactionsScreen.kt`

Evidence:
Add Expense has payment method `BANK_TRANSFER` and transaction type `TRANSFER`.

Problem:
A user can choose payment method bank transfer while transaction type is purchase, or transaction type transfer with another payment method. This might be valid, but the distinction must be explicit.

Fix strategy:
Clarify model and UI copy:
- Payment method = how a purchase/deposit happened.
- Transaction type transfer = movement between accounts, not spending.
- If type transfer is selected, consider auto-selecting payment method bank transfer or hiding payment method.

Acceptance:
- Product decision documented.
- Tests cover valid combinations.
- Transfer metadata required only for transaction type transfer.

---

## S5-023 — Future date is selectable then rejected only on save

Severity: Medium  
Files:
- `AddExpenseSheet.kt`
- `AddExpenseViewModel.kt`

Evidence:
Add Expense rejects future dates during save. The date picker itself allows selecting them.

Problem:
This creates avoidable user friction. It also creates UI states that cannot be persisted.

Fix strategy:
Add selectable date policy to the date picker.

Implementation plan:
- Date picker should disallow dates after end of today according to the same `TimeProvider`/zone policy used by ViewModel.
- ViewModel validation remains as authoritative protection.

Acceptance:
- Future dates cannot be selected in UI.
- Direct ViewModel test still rejects future date.
- Boundary test for end-of-today passes.

---

## S5-024 — `DateSelector` uses raw `Calendar.getInstance()` and stale remembered date state

Severity: Medium  
Files:
- `AddExpenseSheet.kt`

Evidence:
`DateSelector` preserves time-of-day by creating `Calendar` instances from current system defaults. Its date picker state is initialized from `dateMs`.

Problem:
This is not fully deterministic across zones/locales, and `rememberDatePickerState(initialSelectedDateMillis = dateMs)` can become stale if the parent date changes while the composable remains mounted.

Fix strategy:
Move date math to a pure utility or ViewModel.

Implementation plan:
- Create date replacement utility that takes:
  - old timestamp
  - selected date timestamp
  - zone ID
- Key the date picker state by `dateMs` or update it when date changes.
- Prefer java.time over Calendar.

Acceptance:
- Unit tests cover date replacement around DST boundaries.
- Resetting Add Expense updates date picker selected state.
- No raw `Calendar.getInstance()` in Add Expense UI.

---

## S5-025 — Add Expense uses hardcoded/dark semantic colors

Severity: Medium  
Files:
- `AddExpenseSheet.kt`
- Slice 2 theme primitives

Evidence:
The sheet surface/top app bar use app semantic colors directly instead of Material color scheme.

Problem:
This duplicates Slice 2 theme issues and can break light/dark/dynamic color consistency.

Fix strategy:
After Slice 2 primitives are stable, migrate Add Expense to Material theme tokens or app theme adapters.

Acceptance:
- Add Expense renders correctly in light/dark.
- No direct dark-only background unless intentionally part of app shell.
- Compose test smoke renders Add Expense in both themes.

---

## S5-026 — Hardcoded strings and accessibility labels remain

Severity: Medium  
Files:
- `TransactionsScreen.kt`
- `AddExpenseSheet.kt`

Examples:
- success snackbar action label `"OK"`
- end-of-list text
- transaction type expanded/collapsed descriptions
- notes expanded/collapsed descriptions
- ViewModel validation messages

Problem:
Hardcoded UI text is not localizable and makes UI tests brittle.

Fix strategy:
- Move visible strings to resources.
- For ViewModel messages, use `UiText` or stable error codes mapped by UI.
- Keep test assertions on resource text or semantic tags, not English literals.

Acceptance:
- No hardcoded visible English strings in Slice 5 screens/ViewModels except debug-only text.
- Tests do not depend on raw English if avoidable.

---

## S5-027 — ViewModel exposes unused or suspicious dependency

Severity: Low  
File:
- `TransactionsViewModel.kt`

Evidence:
The constructor includes a `NotificationRepository`-typed dependency named `repository`, but transaction logic appears to use `expenseRepository`, `categoryRepository`, etc.

Problem:
Unused dependencies increase fixture setup and break tests during unrelated refactors.

Fix strategy:
- Remove if unused.
- If needed for future behavior, rename and document.

Acceptance:
- ViewModel constructor contains only required dependencies.
- Tests have smaller fixture surface.

---

## S5-028 — Clipboard amount parser inventory is unclear

Severity: Medium  
Expected files:
- `ui/util/ClipboardAmountParser.kt` or equivalent
- MainActivity/FAB clipboard prefill path
- AddExpenseSheet initial prefill path

Observation:
The initial slice scope included `ClipboardAmountParser`, but it was not visible in the opened components directory. The agent must verify the actual path locally.

Problem:
Clipboard-derived amounts are a risky input source. They need strict parsing tests because SMS, banking apps, and receipts often include multiple numbers, dates, account endings, and currency symbols.

Fix strategy:
Locate or create a parser contract.

Required parser tests:
- `$12.34`
- `USD 12.34`
- `€12,34`
- `1,234.56`
- `1.234,56`
- negative/refund text
- text with multiple numbers
- bank card ending `1234` plus amount
- OTP/security code text should not parse as amount
- empty/huge values
- unsupported currency

Acceptance:
- Parser returns amount plus optional merchant/currency/confidence.
- Add Expense prefill uses sanitized parser output.
- Low-confidence/multiple-amount cases require user confirmation or no prefill.

---

# 5. Recommended tests to add

## JVM/ViewModel tests

### `TransactionsViewModelFilterPaginationTest`
Required cases:
- default tab loads month period.
- selecting ALL resets paging and loads first page.
- ALL loadMore appends unique items.
- ALL changing search resets paging.
- ALL changing sort resets paging.
- non-ALL explicit dateRange overrides tab range.
- clearFilter removes route filter.
- ownership filter maps correctly.
- amount min/max filter matches backend ALL behavior and non-ALL in-memory behavior.
- refresh on non-ALL does not leave `isRefreshing` stuck.
- repository failure emits error.

### `TransactionsViewModelMutationTest`
Required cases:
- delete success emits success and refreshes.
- delete failure emits error.
- category single update calls single repository method.
- category apply-all calls bulk method.
- rename blank is rejected before repository call.
- transfer conversion is atomic after fix.
- changing transfer to purchase clears transfer fields after fix.
- ownership validator rejects invalid shared inputs.
- location update writes expense location and merchant correction.
- clear location calls clear.
- mark recurring prevents duplicates after fix.

### `AddExpenseViewModelValidationTest`
Required cases:
- blank merchant rejected.
- invalid amount rejected.
- huge amount rejected.
- future date rejected.
- transfer requires direction and account.
- not-mine and shared cannot both be true.
- shared requires name.
- shared requires exactly one share field.
- percentage range enforced.
- share amount > 0.
- save before currency loaded does not persist.
- double save calls repository once.
- repository duplicate maps to `SaveResult.Duplicate`.
- repository error maps to visible error.
- suggestion failure does not crash.
- suggestion prefill amount parses across locales.

### `AmountInputSanitizerTest`
Required cases:
- multiple separators.
- comma decimal.
- dot decimal.
- thousands separators.
- max decimals.
- blank.
- very long number.
- negative if unsupported.

### `ClipboardAmountParserTest`
See S5-028.

---

## Compose/component tests

### `TransactionItemComponentTest`
Required cases:
- renders merchant/category/amount.
- category click invokes edit category.
- merchant click invokes rename.
- transfer item shows `TransferDirectionBadge`.
- not-mine/shared badges render.
- debug action hidden in release-equivalent config or only exposed when passed.

### `TransactionsScreenContentTest`
Required cases:
- loading state shows skeleton.
- empty state add action calls callback.
- filter chip visible for active filter.
- sort menu callback calls sort.
- pagination loading footer appears.
- end footer uses localized text.

### `TransactionFilterSheetTest`
Required cases:
- current filter prepopulates category/type/year/month/ownership.
- reset local state then apply clears.
- clear button calls onClear.
- invalid amount range blocks apply after validation fix.
- ownership-only filter applies.

### `AddExpenseSheetTest`
Required cases:
- save disabled while saving/currency loading.
- success dismisses.
- error remains visible.
- transfer section appears only for transfer type.
- shared/not-mine are mutually exclusive.
- future date cannot be selected after fix.
- prefilled amount/merchant applied only while pristine.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile commands.
2. Run current Slice 5 tests.
3. Locate actual clipboard parser:

```bash
find app/src/main/java app/src/test -iname "*Clipboard*" -o -iname "*AmountParser*"
```

4. Inventory repository methods used by Slice 5:
   - delete
   - category update
   - merchant update
   - type/transfer update
   - ownership update
   - location update/clear
   - recurring add
   - paged query
   - filtered query

## Phase B — Add contract tests before behavior changes

Add:
- `TransactionsViewModelFilterPaginationTest`
- `TransactionsViewModelMutationTest`
- `AddExpenseViewModelValidationTest`
- `AmountInputSanitizerTest`
- `ClipboardAmountParserTest`

Use fakes, not real Room, unless repository integration is the target.

## Phase C — Fix critical financial/data bugs

1. Prevent placeholder currency persistence.
2. Fix mixed-currency group totals.
3. Make transaction type + transfer details atomic.
4. Extract shared ownership validator.
5. Add save idempotency guard.
6. Add strict amount sanitizer.

## Phase D — Fix mutation UX/testability

1. Keep edit dialogs open on mutation failure.
2. Add mutation state/event model.
3. Add undo or stronger delete policy.
4. Move location search out of UI service dependency.
5. Add tests for location correction cache.

## Phase E — UI extraction

Extract:
- `TransactionItem`
- `TransactionsList`
- `TransactionDialogs`
- `TransactionFilterSummary`
- `AddExpenseContent`
- `DateSelector`
- `CategoryGrid`

Keep behavior unchanged except fixed bugs.

## Phase F — Polish/localization/accessibility

1. Replace hardcoded strings with resources.
2. Add semantic test tags for major actions.
3. Replace fixed category grid chunking with adaptive/FlowRow.
4. Align colors with Slice 2 theme decisions.

---

# 7. Cross-slice golden scenarios after local tests pass

Add these only after Slice 5 local tests are green:

1. Manual add expense appears in transaction list.
2. Manual add expense updates Home dashboard total.
3. Manual add transfer appears with transfer badge and is excluded/included according to dashboard policy.
4. Multi-currency transaction list date total equals converted sum.
5. Dashboard drill-down filter opens Transactions with correct range and clearable filter.
6. Category edit updates row and analytics/category totals.
7. Location edit updates map/location cache.
8. Delete transaction updates list and dashboard.
9. Clipboard amount prefill creates valid Add Expense state.
10. Shared/not-mine transaction is filtered correctly.

---

# 8. Acceptance checklist for Slice 5 green

Slice 5 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Transactions ViewModel filter/pagination tests pass.
- [ ] Transactions mutation tests pass.
- [ ] Add Expense validation tests pass.
- [ ] Clipboard parser tests pass or parser absence is explicitly resolved.
- [ ] Add Expense cannot persist placeholder `"EUR"`.
- [ ] Mixed-currency date/group totals are converted or explicitly degraded.
- [ ] Transfer type updates are atomic and clear stale metadata.
- [ ] Ownership validation is shared across add/edit.
- [ ] Amount input sanitizer is shared and tested.
- [ ] Save is idempotent.
- [ ] Mutation dialogs do not silently discard user input on failure.
- [ ] Location search no longer depends on a public domain service exposed to UI.
- [ ] Location correction cache behavior is tested.
- [ ] Initial filter route behavior is idempotent and clearable.
- [ ] Hardcoded visible strings are replaced or tracked.
- [ ] UI components are split enough for focused tests.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Protect financial correctness before UI polish.
- Use fake repositories and fixed `TimeProvider`.
- Add tests around every mutation path.
- Make money/currency behavior explicit.
- Keep existing route/navigation architecture.
- Extract UI into small components after behavior contracts exist.

Do not:
- Rewrite the whole ledger.
- Hide errors with generic snackbars only.
- Let composables compute financial totals.
- Use placeholder currency for persistence.
- Add new transaction features before transfer/ownership/currency invariants are tested.
- Pass domain services directly into composables.

Main invariant:

> For a fixed clock, fixed home currency, and fixed repository fixture, transaction list, filters, manual add, mutation dialogs, transfer metadata, ownership metadata, and location correction must behave deterministically and must never misrepresent money.