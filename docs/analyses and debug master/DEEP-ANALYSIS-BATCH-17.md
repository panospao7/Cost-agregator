# Deep Analysis — Batch 17: UI Screens (@reviewer)

## Scope
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `HomeViewModel.kt:172-195,332-344` | HIGH | Logic | `reloadDashboard()` does not re-trigger the failing `processedDataFlow`; after an upstream error, the Home retry path only refreshes recommendations/trends/totals, so `HomeScreen`'s retry button can leave the dashboard stuck in the same error state. | Drive dashboard loading from an explicit refresh trigger or call a repository/provider refresh API that recreates the upstream flow on retry. |
| 2 | `TransactionsViewModel.kt:125-173,256-263` | HIGH | Logic | `TransactionFilter.ownership` is never applied by `applyFilter()`. The list is filtered from `_ownershipFilter`, not from `filter.ownership`, so external callers can pass an ownership filter and the banner/UI can claim it is active while the result set remains unfiltered. | When applying a `TransactionFilter`, sync `_ownershipFilter` from `filter.ownership` (or remove ownership from `TransactionFilter` and use a single source of truth). |
| 3 | `TransactionsScreen.kt:159-175`; `TransactionsViewModel.kt:327-374` | HIGH | Performance | Pagination has no terminal `hasMore` state. Once the user reaches the last page, `shouldLoadMore` toggles back to `true` after every empty fetch and continuously reissues `loadMore()` calls. | Track end-of-pagination explicitly (e.g. `hasMore = nextItems.size == PAGE_SIZE`) and include it in the load-more guard. |
| 4 | `TransactionsScreen.kt:1540-1660`; `TransactionsViewModel.kt:466-479` | HIGH | Functional bug | Transfer metadata cannot be corrected unless the transaction type also changes. `ChangeTypeDialog` disables Save when `selectedType == currentType`, so existing TRANSFER rows cannot update direction/account name even though `TransactionsViewModel.updateTransferDetails()` exists. | Enable Save when transfer metadata changes, and call `updateTransferDetails()` when the type stays `TRANSFER`. |
| 5 | `AddExpenseViewModel.kt:343-354,378-383` | HIGH | Data integrity | Manual expense creation and recurring-rule creation are not atomic. If the expense save succeeds but `addRecurringExpense()` throws, the UI reports a failure even though the transaction was already inserted, which encourages duplicate retries. | Wrap both writes in one transactional use case, or treat recurring-rule failure as a secondary warning after reporting the expense save as successful. |
| 6 | `AddExpenseViewModel.kt:94-110,390-392` | MEDIUM | Lifecycle/State | `reset()` does not cancel the in-flight merchant suggestion search job. A delayed search from the previous session can repopulate suggestions after dismiss/reset and leak stale state into the next open. | Cancel `searchJob` inside `reset()`/dismiss flows and clear it before replacing state. |
| 7 | `AddExpenseSheet.kt:99-104` | MEDIUM | Compose state | Initial prefill is keyed with `LaunchedEffect(Unit)`, so updated `initialAmount` / `initialMerchant` values are ignored when the same sheet instance is reused. | Key the effect to `initialAmount` and `initialMerchant` (with a guard to avoid overwriting live user edits). |
| 8 | `AnalyticsViewModel.kt:232-235,340,586-649` | HIGH | Logic | Year-over-year analytics are computed from `purchases`, which only contains the currently selected period. For TODAY/WEEK/MONTH/QUARTER, the prior-year data is missing, so the YoY card becomes null or misleading outside large ranges. | Build YoY from a dataset that spans both current-year and prior-year windows (e.g. `allExpenses` or a dedicated repository query). |
| 9 | `BudgetViewModel.kt:53-75,89-109` | MEDIUM | Performance | `uiState` recomputes `calculateAdjustedSpend()` for every budget on every emission, including unrelated manual/autopilot state changes. This adds repeated offset-engine work and can block composition-facing collectors with unnecessary recalculation. | Move adjusted-spend computation behind a dedicated flow keyed only by budget status changes, and run it on a background dispatcher/cached layer. |
| 10 | `BudgetViewModel.kt:53-55,117-132,163-202` | MEDIUM | Logic | Budget suggestions are loaded only from `_refreshTrigger`, but budget mutations do not bump that trigger. After add/delete/toggle, the suggestions banner can remain stale and continue recommending already-created budgets. | Refresh suggestions after budget mutations, or derive suggestions reactively from the current budget/status stream. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `HomeScreen` / `AnalyticsScreen` / `TransactionsScreen` / `TransactionsViewModel` | HIGH | Navigation drill-downs pass explicit `dateRange`s into Transactions, but Transactions intersects them with the currently selected tab range (`MONTH` by default). Deep links from dashboard/analytics can therefore open with empty or truncated results for older periods. | When an external filter includes a date range, switch to a neutral/custom tab or treat the passed filter as authoritative instead of intersecting it with the default tab range. |
| 2 | `AddExpenseSheet` / `AddExpenseViewModel` / `TransactionsScreen(EditOwnershipDialog)` / analytics-budget calculations | HIGH | Both add/edit flows allow `isNotMine` and `isSharedExpense` at the same time. Downstream calculations use `Expense.effectiveAmount`, which zeroes `isNotMine` before any shared-share math, so shared expenses can silently disappear from budgets/analytics. | Make ownership modes mutually exclusive in both UIs/view-models and normalize any existing contradictory rows. |
| 3 | `HomeScreen` / `TransactionsViewModel` / `AnalyticsViewModel` | MEDIUM | “Month” semantics are inconsistent across screens: Home drill-downs often use calendar-month ranges, while Transactions and Analytics define MONTH as rolling last 30 days. Users can move between screens with the same label and see different totals. | Standardize period definitions across features or relabel rolling windows explicitly (e.g. `Last 30 days`). |
| 4 | All reviewed screens except AI settings-style screens | MEDIUM | These screens consistently use `collectAsState()` instead of `collectAsStateWithLifecycle()`, despite the project already depending on lifecycle-aware collection elsewhere. On heavy flows (home, transactions, analytics, budget) that increases the risk of unnecessary upstream work while the host lifecycle is stopped. | Switch screen-level state collection to `collectAsStateWithLifecycle()` for long-lived flows. |

## Summary
- Total issues: 10
- Critical: 0, High: 6, Medium: 4, Low: 0
- Files with issues: 7/12

## Key Patterns
- Filter/state contracts are split across multiple sources of truth (`TransactionFilter`, tab state, ownership state), which breaks navigation-driven drill-down behavior.
- Several flows treat follow-up work as UI-side side effects instead of transactional/domain operations (`AddExpense`, Home retry, budget suggestion refresh), creating partial-success and stale-state paths.
- Heavy derived computations are performed directly in screen-facing state pipelines (`BudgetViewModel`, analytics/home collectors), which increases recomposition cost and background work.
- Period semantics are duplicated and inconsistent across screens, especially for month-based drill-downs.
