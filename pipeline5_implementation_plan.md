# Pipeline 5 implementation plan — Currency / Dashboard / Analytics

## Goal
Move Pipeline 5 from **“architecturally improved but inconsistent”** to **“single conversion contract, partial-state preserved, budget-safe, test-proven.”**

## Main remaining problems
1. **Mixed rate basis**: totals often use latest-rate while analytics rows use `convertAsOf(expense.date)`.
2. **Partial-state leaks away**: `MoneyAggregate` exists, but many downstream consumers flatten to `Double`.
3. **Budget vs actual is not normalized under the same policy.**
4. **Stale-rate warnings exist but barely affect quality scoring.**
5. **Tests do not prove historical aggregate correctness.**

---

## PR0 — Contract inventory + test skeleton
**Priority:** Critical

### Work
- Freeze a short contract doc under `docs/`:
  - which surfaces must use **historical/as-of**
  - which may use **latest**
  - which must preserve `isPartial`/`warningMessage`
- Add skeleton tests:
  - `MultiCurrencyHistoricalAggregationTest`
  - `AnalyticsRepositoryRateBasisContractTest`
  - `DashboardPartialStatePropagationTest`
  - `BudgetNormalizationContractTest`
  - `DataQualityReportStaleRateTest`

### Done when
You have an explicit rate-basis matrix before changing APIs.

---

## PR1 — Unify rate basis in `MultiCurrencyRepository`
**Priority:** Critical

### Files
- `MultiCurrencyRepository.kt`
- `ExpenseDao.kt` or new minimal row projections
- possibly new `RateBasis.kt`

### Problem
Current totals still document latest-rate conversion, while normalized analytics uses `convertAsOf`.

### Changes
Introduce an explicit rate-basis contract:
- `LATEST_AVAILABLE`
- `AS_OF_TRANSACTION_DATE`

Recommended shape:
- internal shared helper:
  - `aggregateExpenses(rows, homeCurrency, rateBasis, groupBy?)`
- add minimal row projection for historical paths:
  - `amount/effectiveAmount`
  - `currency`
  - `date`
  - `categoryId`
  - `merchant`
  - `transactionType`

Migrate these to **historical/as-of** first:
- `getHomeCurrencyPurchaseTotal`
- purchase category totals
- merchant totals used by analytics/dashboard
- monthly totals
- weekly/daily totals

### Important note
Grouped SQL currency buckets are not enough for historical correctness.  
For as-of conversion, aggregate from rows or date-bucketed rows, not from already-collapsed currency totals.

### Done when
One period cannot mix latest-rate headline totals with as-of daily history.

---

## PR2 — Make `AnalyticsRepository` internally consistent
**Priority:** Critical

### Files
- `AnalyticsRepository.kt`

### Changes
1. `getSpendingSummary()`:
   - use the same historical-rate aggregate policy for:
     - total spent
     - previous total
     - daily history
2. `getCategoryBreakdown()`:
   - stop treating `sumOf(displayAmount)` as the only contract
   - preserve aggregate quality metadata per category
3. Define a small analytics DTO that carries:
   - `displayAmount`
   - `currency`
   - `isPartial`
   - `warningMessage`
   - maybe `failedConversionCount`

### Done when
`SpendingSummary` and category breakdown no longer mix two conversion bases.

---

## PR3 — Propagate partial-state through dashboard adapters/widgets
**Priority:** High

### Files
- `DashboardContractsAdapter.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- dashboard domain DTOs/models

### Problem
Top-level summary preserves warnings better now, but category/widget layers still flatten too much.

### Changes
- Extend category/widget-facing models to carry:
  - `isPartial`
  - `warningMessage`
  - optionally `sourceCurrencyCount` / `failedConversionCount`
- `observeCategoryBreakdown()` should stop mapping to bare `amount + percentage` only.
- In widget context, store `MoneyAggregate` or a small `DisplayMoneySummary`, not raw `Double` only.
- Replace remaining `.displayAmount`-only internal paths where quality matters.

### Done when
Dashboard/UI can show caveats for partial totals instead of silently flattening them away.

---

## PR4 — Normalize budgets under the same money contract
**Priority:** Critical  
**Dependency:** PR1

### Files
- `AnalyticsRepository.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- budget-related repository/use-case files
- likely a new `BudgetNormalizationService.kt`

### Problem
Current code still compares normalized spend against raw/current-rate budget values.

### Changes
Introduce `NormalizedBudgetSnapshot`:
- normalized amount
- display currency
- rate basis used
- partial/warning fields if applicable

Policy recommendation:
- for period analytics, normalize budgets using the **same effective policy** as spend for that period
- stop using raw `overallBudget?.budgetAmount` directly in widget context

Update:
- category budget comparisons
- overall budget summary
- Monte Carlo input budget
- any spending-vs-budget percentages

### Done when
Budget-vs-actual comparisons are apples-to-apples.

---

## PR5 — Strengthen stale-rate quality scoring
**Priority:** High

### Files
- `AnalyticsCurrencyNormalizer.kt`
- `DataQualityReport.kt`

### Problem
Stale-rate warnings exist, but `conversionConfidence` is still based only on loss percentage.

### Changes
Extend normalization/reporting with:
- `staleRateCount`
- `staleRatePercentage`
- optional severity buckets

Update `DataQualityReport.fromNormalization(...)` so stale-rate-heavy results reduce confidence, not just emit strings.

Recommended rule:
- missing-rate = hard exclusion penalty
- stale-rate = softer confidence penalty

### Done when
Quality score reflects both missing and stale conversion risk.

---

## PR6 — Harden already-implemented fixes and remove residual inconsistency
**Priority:** High

### A. P5-P1-02 (`ExchangeRateDao.getRate`) — verify-only pass
Add DB tests for:
- newest `lastUpdated` row wins in `getRate()`
- `getRateAsOf()` returns the latest valid row not newer than `validDate`

### B. P5-P1-04 / P5-P1-07 — mostly-fixed items
- Reframe them from “existence” bugs to “correctness” bugs.
- Ensure weekly/daily totals use the new historical aggregation helper.
- Remove any remaining manual aggregate paths that bypass the shared builder/helper.

### C. P5-P1-05 — widget cleanup
Audit remaining raw number paths in widgets/forecast glue and either:
- normalize them, or
- mark them explicitly as non-currency-sensitive.

### Done when
The stale tracker rows are either fully closed or rewritten truthfully.

---

## PR7 — DB/unit contract suite + docs sync
**Priority:** Required for closure

### Tests to add
- `ExchangeRateDaoContractTest`
- `MultiCurrencyRepositoryHistoricalAggregationTest`
- `AnalyticsRepositorySpendingSummaryConsistencyTest`
- `CategoryBreakdownPartialStateTest`
- `BudgetNormalizationContractTest`
- `DashboardWidgetsCurrencyContractTest`
- `AnalyticsCurrencyNormalizerStaleRateTest`
- `DataQualityReportPenaltyTest`

### Minimum end-to-end scenarios
1. Same-period expenses in 2 currencies with different historical rates
2. Headline total equals sum of daily history under same basis
3. Category totals preserve partial-state
4. Budget comparison uses normalized budget, not raw amount
5. Stale-rate data lowers confidence
6. Weekly/daily drilldowns are historically correct

### Docs cleanup
Update tracker/docs after tests pass:
- P5-P1-03: now partial, not pure TODO
- P5-P1-04: function exists; correctness still pending
- P5-P1-05: materially improved, not fully closed
- P5-P1-06: warnings exist; scoring still partial
- P5-P1-07: mostly fixed inside repo layer

---

## Closure criteria
Pipeline 5 is “clean/stable” only when:
- all reporting surfaces that compare period spend use one explicit rate basis
- dashboard/category/widget consumers preserve partial-state
- budget amounts are normalized with the same comparison policy
- stale-rate risk affects quality score, not just warning strings
- tests prove historical correctness and downstream propagation

## Key sources
- Tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `MultiCurrencyRepository.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
- `AnalyticsRepository.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt
- `DashboardContractsAdapter.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt
- `ComputeDashboardWidgetsUseCase.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `AnalyticsCurrencyNormalizer.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
- `DataQualityReport.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/DataQualityReport.kt
- `MoneyAggregateBuilder.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt
- `ExchangeRateDao.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt