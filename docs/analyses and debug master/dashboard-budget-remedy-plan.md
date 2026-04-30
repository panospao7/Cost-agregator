# Dashboard / Totals / Budget Remedy Plan

Branch: `master-refactor`

## Goal

Make dashboard numbers internally consistent across:

- monthly/weekly/daily drill-down
- category breakdowns
- budget widgets
- safe-to-spend
- recent transactions
- six-month trend
- multi-currency totals

The main rule: **every widget must declare its own time range, transaction filter, ownership rule, and currency rule.**

---

## PR 1 — Add baseline regression tests first

### Purpose

Lock current behavior before changing core aggregation logic.

### Add tests for

1. Monthly total equals category breakdown sum.
2. Daily total includes the last transaction of the day.
3. Week spanning two months does not show child-day totals greater than the parent week.
4. Current week spend works when the week starts in the previous month.
5. Six-month trend contains data outside the current month.
6. Safe-to-spend with no overall budget does not equal month spent.
7. Mixed currencies are not raw-summed.
8. Previous-month comparison uses the actual previous calendar month.

### Suggested test support

Use:

- fake `TimeProvider`
- in-memory Room DB
- deterministic dates
- seeded expenses with:
  - purchase
  - deposit
  - transfer
  - `isNotMine`
  - shared expense
  - null category
  - mixed currency

### Done when

Tests reproduce the suspected failures before fixes.

---

## PR 2 — Fix period boundary correctness

### Files

- `TotalsAggregationEngine.kt`
- `HomeViewModel.kt`
- maybe `PeriodTotal` docs/model if needed

### Problem

`PeriodTotal.startDateMs` / `endDateMs` sometimes use DAO `MIN(date)` / `MAX(date)` instead of full half-open period boundaries.

### Fix

For every `PeriodTotal`, store canonical half-open ranges:

- month: month start → next month start
- week: selected/clipped week start → selected/clipped week end
- day: day start → next day start
- year: year start → next year start

Do **not** use transaction min/max as drill-down boundaries.

### Weekly month-drilldown rule

When drilling from a month into weeks, use **clipped week ranges**:

- start = max(realWeekStart, monthStart)
- end = min(realWeekEnd, monthEnd)

This keeps parent week total and child daily totals aligned.

### Done when

- Month card total equals category breakdown total.
- Week parent total equals sum of displayed days.
- Last transaction in period is never excluded.
- Empty periods still have correct full boundaries.

---

## PR 3 — Split dashboard expense scopes

### Files

- `DashboardRepositoryContracts.kt`
- `DashboardContractsAdapter.kt`
- `DashboardDataProvider.kt`
- `ComputeDashboardWidgetsUseCase.kt`

### Problem

`observeDashboardExpenses()` currently behaves like a current-month expense feed, but the dashboard uses it for broader meanings.

### Replace one generic feed with explicit feeds

Recommended contract split:

1. `observeCurrentMonthPurchases`
2. `observeCurrentWeekPurchases`
3. `observeRecentTransactions(limit)`
4. `observeSixMonthTrend`
5. `observePurchaseDaysForStreaks`
6. `observeCurrentMonthSummary`

Do not let `ComputeDashboardWidgetsUseCase` infer six-month trend or all-time streaks from a current-month list.

### Done when

- Recent transactions are actually recent, not only current-month recent.
- Six-month trend uses six months of data.
- Current week spend includes previous-month days if the current week crosses a month.
- Streak/personal best uses the intended history window.

---

## PR 4 — Calendar-aware previous-period comparison

### Files

- `AnalyticsRepository.kt`
- `DashboardContractsAdapter.kt`
- possibly `TimePeriodUtils.kt`

### Problem

Previous period is calculated by subtracting raw milliseconds. This is wrong for calendar months.

### Fix

Create one domain helper for previous ranges:

- current month → previous calendar month
- current week → previous calendar week
- current day → previous day
- custom range → keep duration-based fallback if needed

Dashboard monthly comparison should use:

- current: start of current month → start of next month
- previous: start of previous month → start of current month

Category breakdown change should use the same logic.

### Done when

- March compares to February.
- February compares to January.
- Leap year February works.
- DST weeks do not shift incorrectly.

---

## PR 5 — Multi-currency correctness

### Files

- `DashboardExpense` model
- `DashboardContractsAdapter.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `ExpenseDao.kt`
- currency / multi-currency repository layer

### Problem

Some dashboard totals sum raw `effectiveAmount`. `ComputeDashboardWidgetsUseCase` also creates forecast snapshots with hardcoded `"EUR"`.

### Fix

1. Add `currency` to `DashboardExpense`.
2. Map real expense currency in `toDomainDashboard`.
3. Remove hardcoded `"EUR"` from forecast snapshot creation.
4. Use grouped-by-currency DAO helpers before converting.
5. Pick one base display currency for dashboard totals.
6. Convert each currency bucket before summing.

### Rule

No dashboard or budget total may sum raw amounts unless:

- all rows have the same currency, or
- amounts are already normalized to the selected base currency.

### Done when

- €10 + $10 is not shown as raw `20`.
- Category percentages are based on converted base-currency totals.
- Forecast input uses real currencies.
- Budget comparisons use the same base currency as displayed totals.

---

## PR 6 — Safe-to-spend semantics

### File

- `ComputeDashboardWidgetsUseCase.kt`

### Problem

When no overall budget exists, safe-to-spend currently falls back to month spent. That is semantically wrong.

### Decision needed

Choose one behavior:

1. Hide SafeToSpend when no overall budget exists.
2. Show CTA: “Set a monthly budget.”
3. Use financial weather discretionary budget, but label it differently.

Recommended: **hide SafeToSpend unless there is an overall budget or a trusted discretionary budget source.**

### Authoritative formula

If budget exists:

- remaining budget
- minus committed expenses
- minus likely planned expenses if desired
- divided by days remaining

Avoid mixing “already spent” with “safe remaining.”

### Done when

- No budget never displays month spent as safe-to-spend.
- Days remaining handles last day of month safely.
- Negative safe-to-spend is displayed intentionally or clamped with clear UX.

---

## PR 7 — Uncategorized category handling

### Files

- `AnalyticsRepository.kt`
- `TotalsAggregationEngine.kt`
- category breakdown UI

### Problem

Null categories are currently easy to drop during mapping.

### Fix

Represent uncategorized as a real virtual bucket:

- id: null or reserved virtual id
- label: “Uncategorized”
- icon/color fallback

### Done when

- Category breakdown sum equals total spend.
- Uncategorized spend is visible.
- Percentages add up correctly.

---

## PR 8 — Average/status correctness

### File

- `TotalsAggregationEngine.kt`

### Problems

- Zero-spend periods are excluded from averages.
- `dropLast(1)` can remove the wrong historical period if current period has no transactions.

### Fix

Generate calendar buckets first, then fill missing periods with zero totals.

Exclude current period by comparing period keys/ranges, not by list position.

### Done when

- Average includes intended zero-spend months/weeks.
- Excluding current period never removes the wrong period.
- Status labels are stable when current month has no expenses.

---

## PR 9 — Reactivity cleanup

### Files

- `AnalyticsRepository.kt`
- `ExpenseDao.kt`
- `DashboardDataProvider.kt`

### Problem

Some analytics flows are snapshot-style `flow { emit(...) }`, not truly Room-reactive.

### Fix options

Preferred:

- Add DAO `Flow` aggregate queries for dashboard summary and category breakdown.

Alternative:

- Explicitly document snapshot behavior and trigger refresh from a known expense invalidation flow.

### Done when

Dashboard updates after:

- adding expense
- deleting expense
- editing category
- changing ownership/shared amount
- changing transaction type

without requiring manual reload.

---

## Recommended implementation order

1. PR 1: tests
2. PR 2: period boundaries
3. PR 3: dashboard scope split
4. PR 4: previous-period comparison
5. PR 6: safe-to-spend
6. PR 7: uncategorized bucket
7. PR 8: average/status cleanup
8. PR 9: reactivity
9. PR 5: multi-currency, unless multi-currency is already user-facing — then move it before PR 6

## Highest-value quick fixes

If you want the fastest bug reduction:

1. Fix `PeriodTotal` boundaries.
2. Clip weekly drill-down ranges to selected month.
3. Remove safe-to-spend fallback to `monthSpent`.
4. Add `currency` to `DashboardExpense` and remove hardcoded `"EUR"`.
5. Split current-month expenses from recent/trend/streak sources.

## Sources reviewed

- `DashboardContractsAdapter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt

- `DashboardDataProvider.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt

- `ComputeDashboardWidgetsUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `TotalsAggregationEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `AnalyticsRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `ExpenseDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `TimePeriodUtils.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt