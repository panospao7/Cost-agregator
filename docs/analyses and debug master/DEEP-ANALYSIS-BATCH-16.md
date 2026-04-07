# Deep Analysis — Batch 16: UI Components & Screens (@reviewer)

## Scope
- `FinancialHealthScoreV2Widget.kt`
- `MoneyRadarWidget.kt`
- `PersonalityProfileCard.kt`
- `ForecastTimeline.kt`
- `SpendingTrendChart.kt`
- `BentoCard.kt`
- `CategoryDonutChart.kt`
- `SpendingPaceGauge.kt`
- `TransactionsScreen.kt`
- `TransactionFilterSheet.kt`
- `AddExpenseSheet.kt`
- `AddExpenseViewModel.kt`
- `LoadingSkeleton.kt`
- `EmptyState.kt`
- `ContextualActionRegistry.kt`
- `TransactionsViewModel.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `TransactionsScreen.kt:159-175` | HIGH | Performance/Logic | Infinite pagination retry on the `ALL` tab. `shouldLoadMore` becomes `true` again after every empty page because there is no `endReached/hasMore` guard, so the UI can keep re-querying forever once the user scrolls to the end. The loop is completed by `TransactionsViewModel.loadMore()` (`TransactionsViewModel.kt:327-374`). | Track `hasMorePages`/`endReached` in the ViewModel, set it to false when a page returns fewer than `PAGE_SIZE` items, and include it in both the UI trigger and ViewModel guard logic. |
| 2 | `TransactionsScreen.kt:1657-1659` | HIGH | Functional bug | `ChangeTypeDialog` only enables Save when `selectedType != currentType`. For existing transfer transactions, users cannot correct transfer direction/account name unless they also change the transaction type. The dedicated `updateTransferDetails()` path exists in the ViewModel but is never reachable from this dialog. | Enable Save when either the type changes **or** transfer metadata changes, or split transfer-detail editing into its own action wired to `updateTransferDetails()`. |
| 3 | `TransactionFilterSheet.kt:41-42,74-80,288-300` | HIGH | Functional bug | Existing date filters cannot be reliably cleared from the sheet. `selectedYear/selectedMonth` are not initialized from `currentFilter`, and when no new date is selected the code falls back to `currentFilter?.dateRange`, so tapping “Reset all” and then “Apply” can keep the old date filter active. | Represent date selection explicitly in local sheet state, initialize it from `currentFilter`, and make reset/apply write `dateRange = null` instead of falling back to the previous filter. |
| 4 | `TransactionsScreen.kt:465-466,799-808` | HIGH | Business logic | Date header totals are computed from unsigned `effectiveAmount` only, so purchase-heavy days are shown as positive/green totals and mixed deposit/purchase days are netted incorrectly. | Sum a signed amount derived from `transactionType` + `effectiveAmount` (e.g. purchases/withdrawals negative, deposits positive, transfer semantics defined consistently) before rendering the header badge. |
| 5 | `TransactionsViewModel.kt:391-522` | HIGH | State consistency | Most edit actions (`updateCategory`, `updateMerchant`, `updateExpenseType`, `updateNotMineDetails`, `updateSharedExpenseDetails`) mutate the database but do not refresh `_pagedExpenses`. Because the `ALL` tab is driven by a snapshot `MutableStateFlow` instead of a live Room flow, edits can appear to “not stick” until the user manually refreshes or changes tabs. | After successful mutations, refresh the `ALL` snapshot (or update the in-memory item directly). Longer term, unify `ALL` tab data with an observable paging source instead of a detached snapshot list. |
| 6 | `TransactionsScreen.kt:57,1061` | HIGH | Data presentation | The transaction row displays `transaction.formattedAmount`, but the imported extension formatter is shadowed by the member property on `ExpenseWithCategory` (`ExpenseWithCategory.kt:41-42`). The rendered value therefore uses raw `expense.amount` with no purchase/deposit sign and ignores `effectiveAmount`, so shared/not-mine transactions are misrepresented. | Remove the duplicate formatter source, keep a single canonical amount formatter based on signed `effectiveAmount`, and update the row to use that single formatter. |
| 7 | `TransactionsScreen.kt:353-418` | MEDIUM | UX/Logic | The active-filter banner is shown only when `activeFilter != null`. Ownership-only filtering is stored separately in `ownershipFilter`, so a user can be actively filtered while the screen shows no banner and no obvious “clear filters” affordance. The apply path that creates this state is in `TransactionFilterSheet.kt:294-304`. | Treat ownership as part of one unified filter model, or at minimum make banner visibility and summary text also depend on `ownershipFilter != ALL`. |
| 8 | `TransactionsViewModel.kt:499-516` | MEDIUM | Business logic/Architecture | Shared-expense validation is inconsistent between create and edit flows. `AddExpenseViewModel.save()` (`AddExpenseViewModel.kt:267-302`) requires a participant name and exactly one of share % / share amount, but the edit path accepts blank participant names and both-or-neither share fields. That can create ambiguous “shared” rows that fall back to full `effectiveAmount`. | Extract shared-expense validation into a reusable validator/use case and apply the exact same rules in both add and edit flows before persisting changes. |

## Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `TransactionsScreen` + `TransactionsViewModel` | HIGH | The transactions pipeline uses two different data models: live Room flows for time-bounded tabs and a detached snapshot list for `ALL`. That architectural split is the root cause of stale edits on `ALL` and makes pagination correctness much harder to reason about. | Move `ALL` onto a real paging/observable source (e.g. Paging 3 or a DAO-backed paged flow) so all tabs share the same update semantics. |
| 2 | `ExpenseWithCategory` + `ExpenseWithCategory_Extensions` + `TransactionsScreen` | HIGH | Money/date formatting logic is duplicated in multiple places, and member-vs-extension shadowing makes it easy to render the wrong representation without compiler errors. This already caused raw amount display drift from the intended signed/effective amount semantics. | Centralize transaction formatting into one formatter layer/model and delete conflicting duplicate properties/extensions. |
| 3 | `TransactionFilterSheet` + `TransactionsScreen` + `TransactionsViewModel` | MEDIUM | Filter state is split between `TransactionFilter` and a separate ownership state, while the sheet also keeps its own date-selection state. This creates hidden filters, reset/apply drift, and banner mismatches. | Use a single immutable UI filter state object that includes ownership/date/category/type and pass it end-to-end through sheet, screen, and ViewModel. |
| 4 | `AddExpenseViewModel` + `EditOwnershipDialog` + `TransactionsViewModel` | MEDIUM | Ownership/shared-expense rules are duplicated across create and edit flows, and the edit flow writes not-mine/shared state in separate repository calls. That increases the chance of rule drift and partial updates. | Consolidate ownership editing into one validated command/use case and persist the combined state transactionally. |

## Summary
- Total issues: 8
- Critical: 0, High: 6, Medium: 2, Low: 0
- Files with issues: 4/16

## Key Patterns
- Most problems are concentrated in the transactions stack rather than the standalone widgets.
- The biggest systemic problem is split state/logic ownership: formatting rules, filter state, and ownership validation all exist in multiple places and have already drifted.
- The `ALL` tab behaves differently from every other tab because it is backed by a snapshot instead of a live query, which is driving both stale-UI bugs and pagination edge cases.
- The reviewed dashboard/analytics widgets were generally clean; the main risk area is cross-component transaction correctness, not isolated Compose drawing code.
