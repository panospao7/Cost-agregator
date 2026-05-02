# Review: Dashboard Budget Analysis — Cross-Check Against Current Codebase

Date: 2026-05-02
Review base: `docs/analyses and debug master/dashboard-budget-analysis.md`
Codebase state: current working tree (branch `master-refactor`)

---

## Executive Summary

The original analysis identified **10 concrete issues + 1 budget-aggregation concern**. Of these, **most remain unresolved** — only the `todaySpent` and `weekSpent` computations have been migrated to `MultiCurrencyRepository` (partial fix for Issues 1 and 5). Several improvements have been made (partial-week labels in drill-down, `MultiCurrencyRepository` for currency conversion), but the core architectural problems persist.

**Verdict: FAIL** — 2 issues are PARTIALLY RESOLVED; 9 are STILL PRESENT. 4 new issues found.

---

## Issue-by-Issue Cross-Check

### Issue 1 — Dashboard expense stream only loads current month, but widgets treat it like broader history

**Status: PARTIALLY RESOLVED**

**Original claim:** `DashboardContractsAdapter.observeDashboardExpenses()` loads only current-month expenses, but `ComputeDashboardWidgetsUseCase` uses them for 6-month trend, no-spend best, recent transactions, week spend, runway/forecast.

**Current state:**
- `DashboardContractsAdapter.observeDashboardExpenses()` (line 54–60) still only queries the current month range via `TimePeriodUtils.getMonthRange(now)`.
- `ComputeDashboardWidgetsUseCase.computeSpendingTrend()` (line 508–555) still uses `ctx.data.data.expenses` (current-month only) to build 6-month trend series. Empty months are silently skipped (line 528: `return@forEach`). **Broken.**
- `calculateStreakData()` (line 771–828) uses `expenses` parameter which is current-month only. **Personal best is artificially limited.**
- `assembleWidgets()` — `RecentTransactions` (line 734) uses `ctx.purchases.take(5)`, limited to current month.
- **Fixed:** `todaySpent` (line 322) and `weekSpent` (line 324) now query `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` directly from DB using the correct time windows — these values are now correct regardless of the expense stream scope.
- **Fixed:** `monthSpent` (line 319) comes from `summary.totalSpent` which goes through `MultiCurrencyRepository` in `AnalyticsRepository.getSpendingSummary()`.

**Remaining gaps:**
1. `SpendingTrend` — still shows only current month, not 6 months.
2. `NoSpendStreak.personalBest` — limited to current-month purchase days.
3. `RecentTransactions` — not truly recent if current month is empty/new.
4. `computeRunwayAndForecast()` — uses expense snapshots from `data.expenses` (current month only) — forecast input is missing history.

---

### Issue 2 — Month/week/day drill-down boundaries can exclude transactions or show child totals that do not match parent totals

**Status: STILL PRESENT**

**Original claim:** DAO aggregate rows use `MIN(date)`/`MAX(date)` as boundaries, and drill-down consumers use these as query ranges, potentially excluding the last transaction via `<` half-open semantics.

**Current state:**
- `ExpenseDao.getMonthlyTotalsForPeriod()` (line 1739–1753): `MIN(date) as startDate, MAX(date) as endDate`. **Unchanged.**
- `ExpenseDao.getWeeklyTotalsForPeriod()` (line 1723–1736): Same pattern. **Unchanged.**
- `ExpenseDao.getDailyTotalsWithDatesForPeriod()` (line 1755–1768): Same pattern. **Unchanged.**
- `TotalsAggregationEngine.getMonthlyTotals()` (line 37–68): Now generates full calendar boundaries for `PeriodTotal` when no DAO row exists (fallback to `monthStart`/`monthEnd`), but when a DAO row IS present, it uses `monthly.startDate` / `monthly.endDate` (= `MIN(date)` / `MAX(date)`). **Still broken for populated months.**
- `HomeViewModel.loadCategoryBreakdownForPeriod()` (line 678–694): Uses `period.startDateMs` and `period.endDateMs` directly — these may be transaction-timestamp boundaries, not calendar boundaries.
- `HomeViewModel.loadCategoryBreakdownForCurrentPeriod()` (line 701–743): Also uses `minOf`/`maxOf` on stored boundaries, propagating the problem.
- `HomeViewModel.drillDownToPeriod()` for WEEK→DAY (line 530–538): Uses `period.startDateMs` and `period.endDateMs` — same issue.

**Consequence:** If a month has transactions on days 1–28, the parent total correctly sums days 1–28 but the category breakdown may query day 1 through `MAX(date)` which, being `< endMs` half-open, could miss the last transaction. The child total may be less than the parent.

---

### Issue 3 — Weekly drill-down can show days outside the selected month

**Status: STILL PRESENT**

**Original claim:** Weekly `PeriodTotal` stores full calendar week boundaries, but the parent weekly total only sums in-month expenses, leading to child daily totals exceeding the parent.

**Current state:**
- `TotalsAggregationEngine.getWeeklyTotals()` (line 70–114): Improvements added — partial week labels (line 89–96) and `generateWeekStarts` clips to month (line 82). **But** the `PeriodTotal.endDateMs` is still set to the full 7-day week end (line 83: `TimePeriodUtils.addDays(weekStart, 7)`), NOT clipped to `monthEndMs`.
- `HomeViewModel.drillDownToPeriod()` for WEEK→DAY (line 530–538): Uses `period.startDateMs` to `period.endDateMs` — the FULL week, not the clipped month range.
- `getDailyTotalsForRange()` (line 133–143) queries the DB for the full week range, bringing in outside-month days.

**Consequence:** For a partial week at a month boundary, drilling down shows days from the adjacent month. The sum of daily totals exceeds the parent weekly total.

**Partial improvement:** The UI now labels partial weeks (e.g., "W1 (1 Apr–6 Apr)") but the boundary and data mismatch remains.

---

### Issue 4 — Previous-period comparison uses milliseconds duration, not calendar periods

**Status: STILL PRESENT**

**Original claim:** `periodLength = end - start` and `previousStart = start - periodLength` is wrong for calendar periods with different lengths.

**Current state:**
- `AnalyticsRepository.getSpendingSummary()` (line 63–64): `val periodLength = end - start; val previousStart = start - periodLength`. **Unchanged.**
- `DashboardContractsAdapter.observeCategoryBreakdown()` (line 130–132): `val periodLength = (end - start).coerceAtLeast(1L); val previousStart = start - periodLength`. **Unchanged.**

**Consequence:** March comparison starts ~31 days before March 1 (≈ Jan 29), not Feb 1. Category "change from last period" percentages in the dashboard are misleading.

---

### Issue 5 — Multi-currency totals appear to be raw summed

**Status: PARTIALLY RESOLVED**

**Original claim:** SQL aggregates sum effective amounts directly without currency conversion. Dashboard forecast snapshots hardcode `"EUR"`.

**Current state:**

| Data path | Status | Detail |
|-----------|--------|--------|
| Dashboard summary (`AnalyticsRepository.getSpendingSummary`) | **FIXED** | Uses `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` — currency-aware. |
| Dashboard category breakdown (`AnalyticsRepository.getCategoryBreakdown`) | **FIXED** | Uses `MultiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals()` — currency-aware. |
| Dashboard `todaySpent` / `weekSpent` | **FIXED** | Uses `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` directly. |
| Drill-down totals (`TotalsAggregationEngine` → `ExpenseRepository` → `ExpenseDao`) | **STILL BROKEN** | `ExpenseRepository.getMonthlyTotalsForPeriod()` / `getTotalForPeriod()` / `getCategoryBreakdown()` all call deprecated DAO methods that raw-sum `EFFECTIVE_AMOUNT_SQL` without currency conversion. |
| `CategorySpending.currency` | **STILL HARDCODED** | `ComputeDashboardWidgetsUseCase.kt` line 182: default `"EUR"`. Never overridden. |
| `SpendingSummary.currency` | **FIXED** | Now populated from `homeCurrency` settings. |
| Forecast input snapshots | **NEEDS REVIEW** | `computeRunwayAndForecast()` builds `ExpenseSnapshot` with raw `expense.currency` — currency conversion happens downstream in forecast engine. Acceptable if engine handles it. |

**Consequence:** Dashboard home widgets are currency-safe, but the drill-down totals screen (TotalsDashboard) still raw-sums mixed-currency amounts.

---

### Issue 6 — Safe-to-spend fallback is semantically wrong when no overall budget exists

**Status: STILL PRESENT**

**Original claim:** When no overall budget exists, `safeToSpend` amount becomes `ctx.monthSpent` — user sees "safe to spend" = what they already spent.

**Current state:**
- `ComputeDashboardWidgetsUseCase.assembleWidgets()` (line 715–720):
  ```kotlin
  amount = if (ctx.overallBudget != null) ctx.safeToSpend else ctx.monthSpent
  ```
  **Unchanged.**
- Improvement: `ctx.safeToSpend` is now `data.weather.discretionaryBudget` (line 327) instead of raw budget remaining. But the fallback to `monthSpent` is still wrong.

**Consequence:** User with no budget sees "Safe to Spend: €500" when they've already spent €500. UX is misleading.

---

### Issue 7 — Average period status ignores zero-spend periods

**Status: STILL PRESENT**

**Original claim:** Monthly/week averages are based only on returned SQL rows, excluding periods with zero spending.

**Current state:**
- `TotalsAggregationEngine.getAverageForPeriodType()`:
  - YEAR (line 228–234): `if (total > 0) total else null` — zero years excluded. **Unchanged.**
  - MONTH (line 238–244): Uses `expenseRepository.getMonthlyTotalsForPeriod()` which returns only populated months. **Unchanged.**
  - WEEK (line 247–253): Same pattern. **Unchanged.**
  - DAY (line 256–258): Uses `getAverageDailySpend()` which also only considers days with transactions. **Unchanged.**

**Consequence:** "Over average" / "Under average" labels may not reflect true calendar average.

---

### Issue 8 — `dropLast(1)` is unsafe for excluding current month/week

**Status: STILL PRESENT**

**Original claim:** When excluding current period, `dropLast(1)` drops the last returned row, but if current period has no transactions, a valid historical period is dropped.

**Current state:**
- `TotalsAggregationEngine.getAverageForPeriodType()`:
  - MONTH (line 241): `months.dropLast(1)`. **Unchanged.**
  - WEEK (line 250): `weeks.dropLast(1)`. **Unchanged.**

**Consequence:** If the current month has zero spending, the last populated month (e.g., previous month) is dropped from the average, skewing it.

---

### Issue 9 — Category breakdown drops uncategorized expenses

**Status: STILL PRESENT**

**Original claim:** Some category queries exclude null category IDs or map them out.

**Current state:**
- `TotalsAggregationEngine.getCategoryBreakdown()` (line 191–192): `if (result.id == null) return@mapNotNull null`. **Unchanged.**
- `AnalyticsRepository.getCategoryBreakdown()` (line 147–148): `val cat = categoryId?.let { categoryMap[it] } ?: return@mapNotNull null`. **Unchanged.**

**Consequence:** Top categories sum to less than total spending if uncategorized expenses exist. UI percentages are based on the sum of categorized amounts only, not total spending. This may be intentional for some screens, but the original analysis flagged it as a mismatch risk.

---

### Issue 10 — Dashboard analytics flows are one-shot flows

**Status: STILL PRESENT**

**Original claim:** `AnalyticsRepository.getSpendingSummary()` and `getCategoryBreakdown()` use `flow { ... }`, not Room reactive DAO flows.

**Current state:**
- `AnalyticsRepository.getSpendingSummary()` (line 67): `return flow { ... }`. **Unchanged.**
- `AnalyticsRepository.getCategoryBreakdown()` (line 136): `return flow { ... }`. **Unchanged.**

**Mitigation:** The dashboard IS effectively reactive because `DashboardDataProvider.getProcessedDataFlow()` uses `flatMapLatest` on the reactive `getAllDataFlow()`. When the underlying expense data changes, the analytics flows re-execute. So while the analytics flows themselves are one-shot, they are re-triggered reactively.

**Assessment:** This is a minor architectural concern, not a functional bug. The dashboard refreshes correctly.

---

## NEW ISSUES FOUND (not in original analysis)

### NEW-1 [MAJOR] `computeSpendingTrend()` skips empty months entirely

**File:** `ComputeDashboardWidgetsUseCase.kt` line 526–528

```kotlin
if (monthExpenses.isEmpty()) return@forEach
```

If any of the 6 months has zero expenses (whether truly zero or due to Issue 1's data scope), that month's series is omitted. The chart will show fewer than 6 bars, which is confusing for the user. Should emit a zero-filled series instead.

---

### NEW-2 [MAJOR] `computeSpendingTrend()` silently doubles data by applying cumulative on already-current-month-only data

**File:** `ComputeDashboardWidgetsUseCase.kt` line 508–555

Because Issue 1 limits `data.expenses` to current month only, the trend loop iterates 6 month keys but finds expenses only for the current month. The cumulative computation creates a "rising line" that looks like multi-month accumulation but actually represents only current-month spending, doubly misleading.

---

### NEW-3 [MINOR] `CategorySpending.currency` default `"EUR"` is never overridden

**File:** `ComputeDashboardWidgetsUseCase.kt` line 182

The `currency` field on `CategorySpending` is always `"EUR"` because `computeCategoryTotals()` never passes a different value. The home currency comes from `HomeViewModel.homeCurrency` but is never threaded into the widget computation.

---

### NEW-4 [MEDIUM] `PersonalBest` no-spend streak calculation is bounded by oldest purchase day in current month

**File:** `ComputeDashboardWidgetsUseCase.kt` line 799

```kotlin
val oldestPurchaseDay = purchaseDays.first()
...
while (checkDate >= oldestPurchaseDay) {
```

Since `purchaseDays` comes from `expenses` (current month only), `oldestPurchaseDay` is the first purchase day of the current month. The personal-best loop can never find gaps larger than the current month, so a user who went 45 days without spending last year will never see that displayed.

---

## Summary Table

| Issue | Status | Severity |
|-------|--------|----------|
| 1 — Expense stream scope | PARTIALLY RESOLVED | High |
| 2 — Drill-down boundary mismatch | STILL PRESENT | Critical |
| 3 — Weekly drill-down out-of-month days | STILL PRESENT | Critical |
| 4 — ms-based previous period | STILL PRESENT | High |
| 5 — Multi-currency raw sum | PARTIALLY RESOLVED | Critical (multi-currency) |
| 6 — Safe-to-spend fallback | STILL PRESENT | High (UX) |
| 7 — Zero-spend periods in average | STILL PRESENT | Medium |
| 8 — dropLast(1) unsafe | STILL PRESENT | Medium |
| 9 — Uncategorized dropped from breakdown | STILL PRESENT | Medium |
| 10 — One-shot analytics flows | STILL PRESENT | Medium (mitigated) |
| Budget — Safe-to-spend concept mix | STILL PRESENT | High |
| NEW-1 — Trend skips empty months | NEW | Major |
| NEW-2 — Trend misleading from Issue 1 | NEW | Major |
| NEW-3 — CategorySpending EUR hardcode | NEW | Minor |
| NEW-4 — PersonalBest limited by scope | NEW | Medium |

---

## Recommended Fix Priority (Updated)

### Phase 0 — Immediate
1. Fix `computeSpendingTrend()` to always emit all 6 months, using zero-filled series for empty months (NEW-1).
2. Fix `SafeToSpend` fallback — show "Set a budget" CTA instead of `monthSpent` (Issue 6).
3. Fix `dropLast(1)` to filter by period key/boundary instead of list position (Issue 8).

### Phase 1 — Drill-down correctness
1. Store **full calendar boundaries** in `PeriodTotal.startDateMs`/`endDateMs` regardless of transaction timestamps (Issue 2).
2. For partial weeks, store **clipped** boundaries (`max(weekStart, monthStart)` / `min(weekEnd, monthEnd)`) in the `PeriodTotal` used for drill-down (Issue 3).

### Phase 2 — Data scope separation
1. Split `observeDashboardExpenses()` into:
   - `currentMonthExpenses`
   - `recentTransactions` (last N, not month-bounded)
   - `allTimePurchaseDays` (for streaks — at minimum the days, not full rows)
   - `sixMonthMonthlyAggregates` (DB-level monthly aggregates)
2. Wire each widget to its correct data source (Issue 1, NEW-2, NEW-4).

### Phase 3 — Calendar-aware comparisons
1. Replace ms-duration-based previous period with calendar-aware ranges (Issue 4).
2. Add tests for leap year, DST, February.

### Phase 4 — Multi-currency drill-down
1. Migrate `TotalsAggregationEngine` to `MultiCurrencyRepository` for all aggregate queries (Issue 5 drill-down path).
2. Remove `CategorySpending.currency` hardcode (NEW-3).

### Phase 5 — Safe-to-spend and budget semantics
1. Define authoritative SafeToSpend formula (Budget aggregation concern).
2. Wire category breakdown to include uncategorized bucket (Issue 9).

---

## Source Files Cross-Referenced

| File | Checked |
|------|---------|
| `data/repository/DashboardContractsAdapter.kt` | ✅ |
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | ✅ |
| `domain/analytics/TotalsAggregationEngine.kt` | ✅ |
| `ui/screens/home/HomeViewModel.kt` | ✅ |
| `data/repository/AnalyticsRepository.kt` | ✅ |
| `data/database/dao/ExpenseDao.kt` | ✅ |
| `domain/usecase/dashboard/DashboardDataProvider.kt` | ✅ |
| `domain/usecase/dashboard/DashboardRepositoryContracts.kt` | ✅ |
| `data/repository/ExpenseRepository.kt` | ✅ |
| `data/repository/MultiCurrencyRepository.kt` | ✅ (partial, key methods reviewed) |
| `domain/model/PeriodTotal.kt` | ✅ |

---

*End of review.*
