# Dashboard / Totals / Budget — Remedy Plan Review

**Review date:** 2026-05-02  
**Branch:** `master-refactor` (current working tree)  
**Source document:** `dashboard-budget-remedy-plan.md`

---

## VERDICT: FAIL

> 15 issues found: 0 CRITICAL, 9 MAJOR, 6 MINOR. Multiple planned fixes remain unimplemented.  
> New issues discovered beyond the original plan.

---

## PR-by-PR Assessment

### PR 1 — Baseline Regression Tests

| Status | **PARTIALLY RESOLVED** |
|---|---|

The codebase has extensive tests (`TotalsAggregationEngineTest.kt`, `TotalsAggregationEngineDeepTest.kt`, `TotalsAggregationEngineValidationTest.kt`). However, of the 8 suggested regression tests:

| # | Suggested test | Status |
|---|---|---|
| 1 | Monthly total equals category breakdown sum | NOT PRESENT — categories test percentages, not cross-widget equality |
| 2 | Daily total includes last transaction of day | NOT PRESENT |
| 3 | Week spanning two months: child-day ≤ parent week | NOT PRESENT |
| 4 | Current week spend when week starts in previous month | NOT PRESENT |
| 5 | Six-month trend contains data outside current month | NOT PRESENT |
| 6 | Safe-to-spend with no budget ≠ month spent | NOT PRESENT |
| 7 | Mixed currencies are not raw-summed | NOT PRESENT |
| 8 | Previous-month comparison uses calendar months | NOT PRESENT |

Test infrastructure exists (mockk, `runTest`, `FakeTimeProvider`) but the targeted regression tests locking in the specific behaviours listed in the plan are absent.

---

### PR 2 — Period Boundary Correctness

| Status | **PARTIALLY RESOLVED** |
|---|---|

**What's fixed:**
- Weekly drill-down from month uses **clipped week ranges** (`generateWeekStarts` limits weeks to month boundaries; partial weeks get date-range labels). ✅
- Weekly `PeriodTotal.startDateMs` / `endDateMs` already use **canonical week boundaries** (line 105–106), not MIN/MAX. ✅
- Yearly `PeriodTotal` uses canonical year bounds. ✅
- `getDailyTotalsForRange()` uses the stored `startDateMs`/`endDateMs` from the parent period for drill-down. ✅

**Issues remaining:**

- [ISSUE-1] [MAJOR] **Monthly `PeriodTotal` uses DAO `MIN(date)`/`MAX(date)` instead of canonical month boundaries** — `TotalsAggregationEngine.kt:59-60`
  ```kotlin
  startDateMs = monthly?.startDate ?: monthStart,
  endDateMs   = monthly?.endDate   ?: monthEnd,
  ```
  When the DAO returns data (monthly ≠ null), `startDateMs` = first transaction in month and `endDateMs` = last transaction. This narrows the half-open range and excludes days before the first transaction / after the last transaction.
  **Fix:** Always use `monthStart`/`monthEnd` (canonical), ignoring DAO MIN/MAX entirely.

- [ISSUE-2] [MAJOR] **Daily `PeriodTotal` uses DAO `MIN(date)`/`MAX(date)`** — `TotalsAggregationEngine.kt:328-329`
  ```kotlin
  startDateMs = daily?.startDate ?: dayStart,
  endDateMs   = daily?.endDate   ?: dayEnd,
  ```
  Same pattern — when transactions exist, the range is narrowed to MIN/MAX. For a single day this is usually benign but technically incorrect.
  **Fix:** Always use `dayStart`/`dayEnd`.

- [ISSUE-3] [MINOR] **DAO aggregation queries still use `MIN(date)`/`MAX(date)`** — `ExpenseDao.kt:1741-1744`, `1757-1760`
  ```sql
  MIN(date) as startDate,
  MAX(date) as endDate,
  ```
  These columns are still computed and returned, enabling the bug above.
  **Fix:** Remove `startDate`/`endDate` columns from aggregation queries; let the engine supply canonical boundaries.

---

### PR 3 — Split Dashboard Expense Scopes

| Status | **STILL PRESENT** |
|---|---|

**What's fixed:** Nothing. The contract remains unchanged.

**Issues:**

- [ISSUE-4] [MAJOR] **`observeDashboardExpenses()` is still a single current-month feed** — `DashboardContractsAdapter.kt:54-61`
  ```kotlin
  val (monthStart, monthEnd) = TimePeriodUtils.getMonthRange(now)
  expenseRepository.getExpensesWithCategoryInPeriod(monthStart, monthEnd)
  ```
  This returns only current-month expenses, yet the same list feeds:
  - **Recent transactions** (line 734: `ctx.purchases.take(5)`) → shows only current-month transactions, not truly recent
  - **Six-month trend** (lines 511–516) → only current month has data; other 5 months are empty and skipped
  - **No-spend streak** (line 782–790) → purchase days limited to current month; streaks can never span month boundaries
  - **Health score inputs** (line 570–580) → limited to current month

  **Fix:** Split into explicit feeds as recommended:
  1. `observeCurrentMonthPurchases`
  2. `observeCurrentWeekPurchases`
  3. `observeRecentTransactions(limit)`
  4. `observeSixMonthTrend`
  5. `observePurchaseDaysForStreaks`
  6. `observeCurrentMonthSummary`

- [ISSUE-5] [MAJOR] **Six-month trend is empty for all months except current** — `ComputeDashboardWidgetsUseCase.kt:526-528`
  ```kotlin
  monthKeys.forEach { (yr, mo) ->
      val monthExpenses = purchasesByMonth[Pair(yr, mo)] ?: emptyList()
      if (monthExpenses.isEmpty()) return@forEach  // <-- all past months skip here
  ```
  Because the data source is current-month only, the loop iterates 6 month keys but only the current month has data. The trend widget shows at most 1 series.
  **Fix:** Feed the trend computation from a dedicated 6-month data source.

---

### PR 4 — Calendar-Aware Previous-Period Comparison

| Status | **STILL PRESENT** |
|---|---|

**What's fixed:** Nothing. Both locations still use raw millisecond subtraction.

**Issues:**

- [ISSUE-6] [MAJOR] **`AnalyticsRepository.getSpendingSummary` uses `start - periodLength`** — `AnalyticsRepository.kt:63-65`
  ```kotlin
  val periodLength = end - start
  val previousStart = start - periodLength
  val previousEnd = start
  ```
  For a 31-day month (March), `periodLength` = 31 days in ms. The "previous" becomes Jan 29 – Feb 28 instead of Feb 1 – Mar 1. Wrong for every non-30-day month.

- [ISSUE-7] [MAJOR] **`DashboardContractsAdapter.observeCategoryBreakdown` uses same logic** — `DashboardContractsAdapter.kt:130-132`
  ```kotlin
  val periodLength = (end - start).coerceAtLeast(1L)
  val previousStart = start - periodLength
  val previousEnd = start
  ```
  Same bug for category breakdown change-from-last-period calculations. March compares to ~Jan 29–Feb 28 instead of Feb 1–Mar 1.

  **Fix for both:** Use `TimePeriodUtils.getMonthRange(timestamp, monthOffset = -1)` or equivalent calendar-aware navigation that produces the actual previous calendar month, not a raw-duration span.

---

### PR 5 — Multi-Currency Correctness

| Status | **PARTIALLY RESOLVED** |
|---|---|

**What's fixed:**
- `DashboardExpense` has a `currency` field mapped from the entity. ✅
- `toDomainDashboard()` maps real `currency` from entity. ✅
- `AnalyticsRepository.getSpendingSummary()` uses `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` for currency-converted totals. ✅
- `AnalyticsRepository.getCategoryBreakdown()` uses `MultiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals()`. ✅
- `ComputeDashboardWidgetsUseCase` today/week spend uses `MultiCurrencyRepository`. ✅

**Issues remaining:**

- [ISSUE-8] [MAJOR] **`CategorySpending.currency` defaults to `"EUR"`** — `ComputeDashboardWidgetsUseCase.kt:182`
  ```kotlin
  val currency: String = "EUR"
  ```
  The category breakdown amounts are correctly converted via `MultiCurrencyRepository`, but the metadata field still hardcodes EUR.
  **Fix:** Pull home currency from `CurrencySettingsRepository` or pass it through from the analytics layer.

- [ISSUE-9] [MAJOR] **`SpendingSummary.currency` defaults to `"EUR"`** — `domain/model/dashboard/SpendingSummary.kt:13` and `data/repository/AnalyticsRepository.kt:29`
  ```kotlin
  val currency: String = "EUR"
  ```
  Despite the analytics layer now computing currency-converted totals, the domain model still has a hardcoded default.
  **Fix:** Make `currency` required (no default) and populate from `homeCurrency`.

- [ISSUE-10] [MAJOR] **`TotalsAggregationEngine` uses deprecated DAO methods that raw-sum across mixed currencies** — `TotalsAggregationEngine.kt`
  The engine calls `expenseRepository.getMonthlyTotalsForPeriod()`, `getWeeklyTotalsForPeriod()`, `getDailyTotalsWithDatesForPeriod()`, and `getCategoryBreakdown()` — all via the old repository that hits DAO methods deprecated as:
  ```
  @Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")
  ```
  The totals dashboard widget (period drill-down) still operates on raw sums. €10 + $10 = raw 20.
  **Fix:** Either route `TotalsAggregationEngine` through `MultiCurrencyRepository` for currency-aware totals, or keep the raw DAO path only when all rows share a single currency (add a guard).

---

### PR 6 — Safe-to-Spend Semantics

| Status | **STILL PRESENT** |
|---|---|

**Issue:**

- [ISSUE-11] [MAJOR] **Safe-to-spend falls back to `monthSpent` when no overall budget exists** — `ComputeDashboardWidgetsUseCase.kt:717`
  ```kotlin
  amount = if (ctx.overallBudget != null) ctx.safeToSpend else ctx.monthSpent,
  ```
  This is the exact code identified in the plan. When there is no overall budget, the widget displays the full month spent as "safe to spend" — semantically wrong and misleading.
  **Fix:** Per the plan recommendation: hide `SafeToSpend` when no overall budget exists, or show a CTA ("Set a monthly budget").

---

### PR 7 — Uncategorized Category Handling

| Status | **STILL PRESENT** |
|---|---|

**What's fixed:** A default "Uncategorized" category is seeded in `CategoryRepository.ensureDefaultCategories()`. ✅

**Issues remaining:**

- [ISSUE-12] [MAJOR] **`TotalsAggregationEngine.getCategoryBreakdown` drops null-category expenses** — `TotalsAggregationEngine.kt:191`
  ```kotlin
  if (result.id == null) return@mapNotNull null
  ```
  Expenses with `categoryId = null` are silently excluded from the breakdown. This means the category breakdown sum ≠ total spend whenever uncategorized expenses exist.

- [ISSUE-13] [MAJOR] **`AnalyticsRepository.getCategoryBreakdown` also drops null-category** — `AnalyticsRepository.kt:148`
  ```kotlin
  val cat = categoryId?.let { categoryMap[it] } ?: return@mapNotNull null
  ```
  Same bug in the analytics path. Null-category expenses contribute to `totalSpent` (in the summary) but are absent from the category breakdown.

  **Fix for both:** When `categoryId` is null, map to the seeded "Uncategorized" category:
  ```kotlin
  val cat = categoryId?.let { categoryMap[it] } ?: categoryMap.values.firstOrNull { it.name == "Uncategorized" } ?: return@mapNotNull null
  ```

---

### PR 8 — Average / Status Correctness

| Status | **STILL PRESENT** |
|---|---|

**Issues:**

- [ISSUE-14] [MAJOR] **`dropLast(1)` excludes current period by position, not by key** — `TotalsAggregationEngine.kt:241, 250`
  ```kotlin
  months.dropLast(1).map { it.total }.average()
  weeks.dropLast(1).map { it.total }.average()
  ```
  If the current period has **no transactions**, it won't appear in the DAO result list. `dropLast(1)` then removes whichever period happens to be last (e.g., the previous month/week), producing a wrong average.
  **Fix:** Exclude the current period by comparing period keys/ranges:
  ```kotlin
  months.filterNot { it.monthKey == currentMonthKey }.map { it.total }.average()
  ```

- [ISSUE-15] [MINOR] **Zero-spend years are excluded from yearly average** — `TotalsAggregationEngine.kt:233`
  ```kotlin
  if (total > 0) total else null  // filtered by mapNotNull
  ```
  Years with zero spending are excluded from the average. If the user has 3 years of data and 1 year with no purchases, the average is calculated over 2 years instead of 3, inflating the result.

- [ISSUE-16] [MINOR] **Monthly/weekly averages use DAO results (which omit zero-spend periods) instead of calendar buckets** — `TotalsAggregationEngine.kt:238-253`
  The monthly average pulls `getMonthlyTotalsForPeriod(startMs, now)` which returns only months that have at least one expense. Calendar months with zero transactions are absent from the list.
  **Fix:** Generate calendar buckets for the lookback window, fill with zero totals for missing periods, then compute average.

---

### PR 9 — Reactivity Cleanup

| Status | **PARTIALLY RESOLVED** |
|---|---|

**What's fixed:**
- The dashboard pipeline is driven by `combine` of multiple `Flow` sources (`observeDashboardExpenses`, `observeDashboardCategories`, `observeBudgetStatuses`, etc.). Changes to any source trigger re-computation. ✅
- `DashboardDataProvider.getProcessedDataFlow()` re-reads time boundaries on every emission via `flatMapLatest`. ✅

**Issues remaining:**

- [ISSUE-17] [MINOR] **`AnalyticsRepository.getSpendingSummary` and `getCategoryBreakdown` are snapshot `flow { emit(...) }`** — `AnalyticsRepository.kt:67, 136`
  These are not Room-reactive. They re-execute when the outer pipeline triggers them, but they don't independently react to DB changes.
  **Fix:** Add DAO `Flow` aggregate queries (e.g., `@Query(...) fun observeSpendingSummaryFlow(...): Flow<SpendingSummaryRow>`) and use them directly.

---

## New Issues Discovered (Not in Original Plan)

- [ISSUE-18] [MINOR] **`CategorySpending.moneyTotal` uses `amount` field not `effectiveAmount`** — `ComputeDashboardWidgetsUseCase.kt:183-184`
  ```kotlin
  val moneyTotal: MoneyAmount get() = MoneyAmount(total, CurrencyCode(currency))
  ```
  The `total` field comes from `DashboardCategoryBreakdown.amount` which is already currency-converted via `MultiCurrencyRepository`. But the naming is confusing — it wraps the display `total` (which is already effective) rather than the raw `amount`. The `MoneyAmount` constructor also uses the hardcoded default currency. Low-impact.

- [ISSUE-19] [MINOR] **`PeriodSummary` month spend duplicates `totalSpent`** — `ComputeDashboardWidgetsUseCase.kt:319, 731`
  ```kotlin
  monthSpent = summary.totalSpent,  // same as totalSpent
  ```
  The `PeriodSummary` widget shows `todaySpent`, `weekSpent`, `monthSpent`. But `totalSpent` and `monthSpent` are identical (both set to `summary.totalSpent`). If the summary is always for the current month, this is correct but redundant. Low-impact.

- [ISSUE-20] [MINOR] **`MonthlyComparisonCalculator` hardcodes `displayCurrency = "EUR"`** — `MonthlyComparisonCalculator.kt:15`
  ```kotlin
  displayCurrency: String = "EUR"
  ```
  This is used by advanced analytics, not the dashboard directly, but it's another hardcoded default.

---

## Summary Table

| PR | Title | Status | Key Issues |
|---|---|---|---|
| 1 | Baseline regression tests | PARTIALLY | 8 specific regression tests missing |
| 2 | Period boundary correctness | PARTIALLY | Monthly/daily MIN/MAX in DAO; weekly fixed |
| 3 | Split dashboard expense scopes | STILL PRESENT | Single current-month feed serves 4+ widgets |
| 4 | Calendar-aware previous-period | STILL PRESENT | Both locations use raw ms subtraction |
| 5 | Multi-currency correctness | PARTIALLY | Hardcoded EUR in models; TotalsAggregationEngine uses deprecated raw-sum DAO |
| 6 | Safe-to-spend semantics | STILL PRESENT | Falls back to monthSpent when no budget |
| 7 | Uncategorized category handling | STILL PRESENT | Both aggregation paths drop null-category expenses |
| 8 | Average/status correctness | STILL PRESENT | `dropLast(1)` position-based; zero periods excluded |
| 9 | Reactivity cleanup | PARTIALLY | Snapshot flows wrapped reactively; no DAO Flow aggregates |

---

## Recommended Fix Priority

1. **[ISSUE-6/7]** Calendar-aware previous period (PR 4) — breaks all period comparisons
2. **[ISSUE-14]** Fix `dropLast(1)` position-based exclusion (PR 8) — can corrupt averages
3. **[ISSUE-11]** Safe-to-spend fallback (PR 6) — user-facing semantic bug
4. **[ISSUE-4/5]** Split expense scopes (PR 3) — empty trend, wrong streaks, wrong recent transactions
5. **[ISSUE-12/13]** Uncategorized bucket (PR 7) — category breakdown ≠ total
6. **[ISSUE-10]** TotalsAggregationEngine multi-currency (PR 5) — raw sums in totals dashboard
7. **[ISSUE-1/2]** MIN/MAX period boundaries (PR 2) — drill-down range narrowing
8. **[ISSUE-8/9]** Hardcoded currency defaults (PR 5)
9. **[ISSUE-17]** DAO Flow aggregates (PR 9)
10. **[ISSUE-15/16]** Zero-period exclusion in averages (PR 8)
11. All PR 1 regression tests
