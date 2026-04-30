# Dashboard Totals & Budget Aggregation Analysis

Branch: `master-refactor`

## Executive verdict

The most critical remaining risks are **date-boundary mismatches** and **scope mismatches** between:

- dashboard monthly data
- trend/history data
- drill-down totals
- budget/safe-to-spend widgets
- multi-currency totals

The code has strong intent around purchase-only and effective ownership-adjusted amounts, but several consumers use the same data stream for different meanings.

---

## Highest priority issues

### 1. Dashboard expense stream only loads current month, but widgets treat it like broader history

Source:
- `DashboardContractsAdapter.observeDashboardExpenses()`

It observes expenses only for:

- current month start
- current month end

But `ComputeDashboardWidgetsUseCase` uses `data.expenses` for:

- six-month spending trend
- no-spend personal best
- recent transactions
- weekly spend
- runway/forecast input snapshots

Impact:

- `SpendingTrend` likely only shows current month, not six months.
- No-spend streak “personal best” is only based on current month data.
- `weekSpent` is wrong when the week crosses a month boundary.
- Recent transactions are not truly recent if the current month has little/no activity.
- Forecast/runway may be using too little history unless downstream engines compensate.

Recommended fix:

Separate dashboard data contracts:

- current month purchases
- recent transactions
- last six months trend aggregates
- all-time purchase-day history for no-spend best
- current week range data
- forecast input source

Do not reuse one “current month expenses” list for all widgets.

Severity: **High**

---

### 2. Month/week/day drill-down boundaries can exclude transactions or show child totals that do not match parent totals

Source:
- `TotalsAggregationEngine.getMonthlyTotals()`
- `TotalsAggregationEngine.getWeeklyTotals()`
- `TotalsAggregationEngine.buildDailyPeriodTotals()`
- `HomeViewModel.loadCategoryBreakdownForPeriod()`

Monthly totals use DAO aggregate rows where `startDate = MIN(date)` and `endDate = MAX(date)`. Those are transaction timestamps, not full period boundaries.

Impact:

If a user taps a month and loads category breakdown, the breakdown range can become:

- first transaction timestamp
- last transaction timestamp

Since DAO filters use half-open ranges like `date < endMs`, the last transaction can be excluded.

This can make:

- month total card = €500
- category breakdown sum = €460

Same issue can happen for daily totals.

Recommended fix:

For `PeriodTotal`, always store full period boundaries:

- month start → next month start
- day start → next day start
- year start → next year start

DAO `MIN(date)` / `MAX(date)` can be kept only as metadata, not as drill-down boundaries.

Severity: **Critical**

---

### 3. Weekly drill-down can show days outside the selected month

Source:
- `TotalsAggregationEngine.getWeeklyTotals()`
- `HomeViewModel.drillDownToPeriod()`

Weekly totals are queried only inside the selected month, but each weekly `PeriodTotal` stores the full calendar week range.

Impact:

For a partial week at month boundary:

- parent week total only includes in-month expenses
- child day drill-down loads the full week
- child daily sum can exceed parent total
- previous/next month expenses appear inside the selected month drill-down

Example:

Selecting “W1 Apr” might show March 30–31 expenses in the day drill-down even though the parent total only counted April 1 onward.

Recommended fix:

Either:

1. make weekly totals full-week totals, including outside-month days, or  
2. keep month drill-down partial and store clipped boundaries:
   - `max(weekStart, monthStart)`
   - `min(weekEnd, monthEnd)`

For dashboard UX, option 2 is probably better.

Severity: **Critical**

---

### 4. Previous-period comparison uses milliseconds duration, not calendar periods

Sources:
- `AnalyticsRepository.getSpendingSummary()`
- `DashboardContractsAdapter.observeCategoryBreakdown()`

Previous period is calculated as:

- `periodLength = end - start`
- `previousStart = start - periodLength`
- `previousEnd = start`

For calendar months, this is wrong because months have different lengths.

Impact:

March comparison may start in late January instead of February depending on the month length. Category “change from last period” and dashboard previous-month insight can be misleading.

Recommended fix:

Use calendar-aware previous ranges:

- previous month: start of previous calendar month → start of current month
- previous week: previous week start → current week start
- previous day: previous day start → current day start

Severity: **High**

---

### 5. Multi-currency totals appear to be raw summed

Sources:
- `ExpenseDao.getTotalSpentBetween()`
- `ExpenseDao.getMonthlyTotalsForPeriod()`
- `ExpenseDao.getCategoryBreakdown()`
- `TotalsAggregationEngine`
- `ComputeDashboardWidgetsUseCase.computeRunwayAndForecast()`

The SQL aggregates sum effective amounts directly. Also, dashboard forecast snapshots hardcode currency as `"EUR"`.

Impact:

If user has mixed currencies:

- €20 + $20 becomes “40”
- category percentages are wrong
- budgets compare raw mixed amounts
- runway and safe-to-spend can be wrong
- forecast input currency is corrupted

Recommended fix:

Add currency to dashboard domain models and aggregate per currency before conversion.

Options:

- store normalized base-currency amount on `Expense`
- or query grouped by currency and convert via currency repository
- never hardcode `"EUR"` in dashboard snapshots

Severity: **Critical if multi-currency is enabled**

---

### 6. Safe-to-spend fallback is semantically wrong when no overall budget exists

Source:
- `ComputeDashboardWidgetsUseCase.assembleWidgets()`

If no overall budget exists, safe-to-spend amount becomes `ctx.monthSpent`.

Impact:

A user with no budget sees “safe to spend” equal to what they already spent. That is not safe-to-spend.

Recommended fix:

If no overall budget exists:

- hide SafeToSpend widget, or
- show “Set a budget”, or
- use financial weather discretionary budget if available

Severity: **High UX / logic bug**

---

## Medium priority issues

### 7. Average period status ignores zero-spend periods

Source:
- `TotalsAggregationEngine.getAverageForPeriodType()`
- DAO aggregate methods return only periods with transactions

Monthly/week averages are based only on returned SQL rows, so months/weeks with zero spending are excluded.

Impact:

“Over average” / “under average” labels may not reflect true calendar average.

Recommended fix:

Generate full calendar buckets first, then average across all buckets depending on intended UX.

Severity: **Medium**

---

### 8. `dropLast(1)` is unsafe for excluding current month/week

Source:
- `TotalsAggregationEngine.getAverageForPeriodType()`

When excluding current period, it does:

- get SQL aggregate rows
- `dropLast(1)`

But if current month/week has no transactions, the last returned row may be a previous period, so valid historical data is dropped.

Recommended fix:

Filter by actual period key/boundary instead of list position.

Severity: **Medium**

---

### 9. Category breakdown drops uncategorized expenses

Sources:
- `AnalyticsRepository.getCategoryBreakdown()`
- `TotalsAggregationEngine.getCategoryBreakdown()`

Some category queries exclude null category IDs or map them out.

Impact:

Top categories may sum to less than total spending. This may be intended, but if the UI shows percentages of “total spending”, uncategorized spend disappears.

Recommended fix:

Represent uncategorized as a real dashboard bucket.

Severity: **Medium**

---

### 10. Dashboard analytics flows are one-shot flows

Source:
- `AnalyticsRepository.getSpendingSummary()`
- `AnalyticsRepository.getCategoryBreakdown()`

They use `flow { suspendQuery(); emit(...) }`, not Room reactive DAO flows.

The dashboard currently gets refreshed indirectly because another observed expenses flow exists, but the analytics repository itself is not reactive.

Recommended fix:

Either:

- expose Room `Flow` aggregate queries, or
- explicitly treat analytics as snapshot-only and document the refresh trigger

Severity: **Medium**

---

## Budget aggregation concerns

### Budget status source is cleaner than dashboard totals, but safe-to-spend mixes concepts

Source:
- `DashboardContractsAdapter.observeBudgetStatuses()`
- `ComputeDashboardWidgetsUseCase.buildContext()`
- `ComputeDashboardWidgetsUseCase.assembleWidgets()`

Budget statuses are mapped into `BudgetStatusSnapshot`, but SafeToSpend uses:

- overall budget if present
- financial weather discretionary budget for amount
- fallback to month spent if no budget

This mixes:

- actual spend
- budget remaining
- forecast discretionary budget

Recommended direction:

Define one authoritative SafeToSpend formula:

- budget remaining
- minus committed/planned
- divided by days remaining
- with clear fallback when no budget exists

Severity: **High**

---

## Strong parts already present

These are good and should be preserved:

1. DAO has canonical spending filter:
   - purchase-only through `SPENDING_TYPE_SQL`

2. DAO has canonical effective amount:
   - not-mine rows become zero
   - shared expenses use user share

3. Dashboard adapter maps data-layer models to domain dashboard contracts.

4. Totals engine centralizes yearly/monthly/weekly/daily drill-down.

5. HomeViewModel keeps totals drill-down state separate from normal dashboard widgets.

The main problem is not lack of structure. It is that some boundaries and data scopes are inconsistent.

---

## Recommended fix order

### Phase 1 — Fix drill-down correctness

Fix `PeriodTotal.startDateMs/endDateMs` to always be full/clipped period boundaries.

Priority:

1. monthly boundaries
2. daily boundaries
3. partial-week boundaries

Add tests:

- month with transactions on first and last day
- day with multiple transactions
- week spanning two months
- category breakdown sum equals parent total

---

### Phase 2 — Split dashboard expense data scopes

Replace one `observeDashboardExpenses()` source with clearer sources.

Minimum viable split:

- currentMonthExpenses
- currentWeekExpenses
- recentTransactions
- sixMonthTrendAggregates
- purchaseDayHistoryForStreaks

This avoids loading full history into UI while keeping each widget correct.

---

### Phase 3 — Fix previous-period comparisons

Use calendar-aware previous ranges.

Test cases:

- February → January
- March → February
- leap year February
- month after 31-day month
- DST boundary weeks

---

### Phase 4 — Multi-currency safety

If multi-currency is active, dashboard totals must not raw-sum amounts.

Add a rule:

No dashboard/budget total can sum `amount` or `effectiveAmount` unless all rows are same currency or already normalized.

---

### Phase 5 — Safe-to-spend semantics

Decide:

- hide if no budget
- or show CTA to create budget
- or use forecast discretionary amount

Do not use `monthSpent` as safe-to-spend.

---

## Suggested regression test list

1. **Monthly drill-down category sum**
   - Three expenses in April.
   - One on April 1, one mid-month, one April 30.
   - Monthly total must equal category breakdown total.

2. **Daily drill-down includes last transaction**
   - Two expenses on same day.
   - Category breakdown must include both.

3. **Partial week parent/child consistency**
   - Week spans March/April.
   - April W1 parent total must equal sum of displayed child days.

4. **Six-month trend**
   - Expenses in each of last six months.
   - Trend should contain six series, not only current month.

5. **Week spent across month boundary**
   - Today is early month.
   - Week includes previous month days.
   - `PeriodSummary.weekSpent` should include full week.

6. **No overall budget**
   - SafeToSpend should not equal month spent.

7. **Mixed currency**
   - €10 and $10 should not become raw `20` unless converted.

8. **Previous month comparison**
   - March current period should compare to February, not a 31-day shifted range.

---

## Source files reviewed

- `docs/architecture/CODEBASE_SEGMENTS.md`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardRepositoryContracts.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`