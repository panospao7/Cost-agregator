# Pipeline 5 — Currency / Dashboard / Analytics evaluation

## Executive verdict

My current status call:

- **1 tracker item is clearly fixed**
- **3 tracker TODO rows are stale/improved in current code**
- **but 3 important issues are still substantively open**
- **and the test proof is not strong enough to call the pipeline stable**

Best summary:

> **Pipeline 5 is materially improved, but not closure-ready.**

The biggest remaining problem is this:

> **the pipeline still mixes two conversion contracts**
> - aggregate totals often use **latest/current rates**
> - normalized analytics paths use **historical `convertAsOf(expense.date)`**

That means the same reporting surface can still be internally inconsistent.

---

## Issue-by-issue

## P5-P1-01 — Historical totals use latest-rate aggregate conversion
**Tracker:** TODO ONLY  
**My verdict:** **STILL OPEN and important**

This is still the main Pipeline 5 gap.

### Evidence
In `MultiCurrencyRepository` current code:
- `getHomeCurrencyTotal()`
- `getHomeCurrencyCategoryTotals()`
- `getHomeCurrencyMerchantTotals()`
- `getHomeCurrencyMonthlyTotals()`
- `getHomeCurrencyPurchaseTotal()`

all still document that they use **latest-rate conversion** via `CurrencyConverter.convertMultiple()`, and they still contain explicit TODOs for historical variants.

At the same time:
- `AnalyticsCurrencyNormalizer.normalizeExpenses()` uses `currencyConverter.convertAsOf(..., atMillis = expense.date)`

So the pipeline is split:
- **totals** = latest-rate
- **normalized row-level analytics** = historical-rate

### Why that matters
`AnalyticsRepository.getSpendingSummary()` currently:
- gets the headline total via `multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...)`
- but uses normalized expenses for the daily history path

So one summary can still combine **two different rate bases**.

**Call:** **open, not clean**

---

## P5-P1-02 — `ExchangeRateDao.getRate()` ambiguous with historical rows
**Tracker:** ✅ fixed  
**My verdict:** **FIXED for the stated bug**

Current `ExchangeRateDao` now has:
- `getRate(fromCurrency, toCurrency)` with  
  `ORDER BY lastUpdated DESC LIMIT 1`
- `getRateAsOf(...)` with  
  `validDate <= :validDate ORDER BY validDate DESC LIMIT 1`

That directly fixes the old “arbitrary row” ambiguity for the non-historical getter.

### Caveat
This does **not** solve P5-P1-01 by itself, because most repo aggregate methods still use latest-rate conversion instead of as-of conversion.

**Call:** **fixed, but only for the narrow DAO bug**

---

## P5-P1-03 — Dashboard adapter drops `MoneyAggregate` / partial warnings
**Tracker:** TODO ONLY  
**My verdict:** **tracker is stale; now PARTIAL, not closed**

There is real improvement here.

### What is better now
`DashboardContractsAdapter.observeSpendingSummary()` now preserves:
- `isPartial = summary.isPartial`
- `warningMessage = summary.aggregate?.warningMessage ...`

So for the **top-level spending summary**, the adapter no longer fully drops partial-state.

### What is still not clean
The deeper breakdown/widget layer still loses the richer contract.

`AnalyticsRepository.getCategoryBreakdown()` has an explicit TODO saying:
- category percentages are computed from `displayAmount`
- partial aggregates should carry `isPartial` / `warningMessage`
- but the current breakdown shape does not expose those fields

Then `ComputeDashboardWidgetsUseCase.computeCategoryTotals()` maps that thinner breakdown forward again.

So:
- top-level summary improved
- category/widget layers still flatten too much

**Call:** **PARTIAL**

---

## P5-P1-04 — Weekly/daily totals drilldown functionally broken
**Tracker:** TODO ONLY  
**My verdict:** **tracker is stale; functionally improved, but not fully clean**

This specific bug looks materially improved.

### What exists now
`MultiCurrencyRepository` now has:
- `getHomeCurrencyWeeklyTotals(...)`
- `getHomeCurrencyDailyTotals(...)`

Both:
- pull uncapped expenses
- group by week/day
- build `PeriodMoneyAggregate`

So this is no longer the old “empty/raw deprecated path” shape.

### Why I still won’t call it fully clean
Those methods use `MoneyAggregateBuilder.fromBuckets(...)`, which ultimately relies on `CurrencyConverter.convertMultiple()` — i.e. **latest-rate**, not historical as-of conversion.

So:
- **functional drilldown exists now**
- **historical correctness is still wrong**

**Call:** **mostly fixed functionally / not fully stable analytically**

---

## P5-P1-05 — Dashboard widgets raw-sum `effectiveAmount`
**Tracker:** TODO ONLY  
**My verdict:** **much improved, but still PARTIAL**

This row is too pessimistic for current HEAD.

### What is clearly better
`ComputeDashboardWidgetsUseCase` now pulls important summary values from `MultiCurrencyRepository`, e.g.:
- `todaySpent = getHomeCurrencyPurchaseTotal(...).displayAmount`
- `weekSpent = getHomeCurrencyPurchaseTotal(...).displayAmount`
- Monte Carlo `spentToDate` also comes from `MultiCurrencyRepository`

So the obvious raw-sum bug is not as universal as the tracker suggests.

### Why I still do not call it closed
The widget layer still works mostly with:
- `Double`
- raw budget amounts
- flattened DTOs

It does **not** propagate `MoneyAggregate` or warnings end-to-end.

So even where the displayed number is normalized, the widget system still cannot fully express:
- partial totals
- stale/missing-rate caveats
- source bucket detail

**Call:** **PARTIAL / substantially improved**

---

## P5-P1-06 — Stale-rate state not propagated to analytics quality
**Tracker:** TODO ONLY  
**My verdict:** **tracker is stale; now PARTIAL**

This is a good example of “implemented, but not fully carried through”.

### What improved
`AnalyticsCurrencyNormalizer` now explicitly detects stale rates:
- if the rate timestamp is more than 7 days older than the expense date, it emits `STALE_EXCHANGE_RATE`

And `DataQualityReport.fromNormalization(...)` now carries:
- `warnings = normalization.warnings.map { it.message }`

So stale-rate state is **visible** now.

### What is still missing
`DataQualityReport.conversionConfidence` is still computed only from:
- `lossPercentage`
- i.e. excluded/unconverted rows

That means stale-rate warnings do **not** reduce the confidence score, and there is no dedicated stale-rate severity/count metric in the report.

So:
- stale state is propagated as warnings
- not propagated strongly enough into the quality score

**Call:** **PARTIAL**

---

## P5-P1-07 — Inconsistent `MoneyAggregateBuilder` use
**Tracker:** TODO ONLY  
**My verdict:** **probably mostly fixed; tracker looks stale**

Current `MultiCurrencyRepository` now has internal helper paths that explicitly use:
- `MoneyAggregateBuilder.fromBuckets(...)`

and the helper comment specifically says it preserves consistent warning behavior and failed transaction counts.

This is a real improvement over the older manual bucket mapping problem.

### Remaining caution
Even if repository construction is now more consistent, many downstream consumers still only use:
- `displayAmount`
- or flattened DTO fields

So the **builder inconsistency** itself looks much better, but the **consumer contract** is still lossy.

**Call:** **mostly fixed inside the repository layer**

---

## P5-P1-08 — Budget-vs-actual comparisons not fully normalized
**Tracker:** TODO ONLY  
**My verdict:** **STILL OPEN**

This is still explicitly documented in current code.

### Evidence
`AnalyticsRepository.getCategoryBreakdown()` contains a current TODO saying:
- budget snapshots from `BudgetRepository.getActiveBudgetSnapshots()`
- use `convert()` current exchange rate
- which may differ from the conversion used by the spend data
- and should be normalized using the same period-appropriate conversion

Also in `ComputeDashboardWidgetsUseCase`, `totalBudgetAmount` is still taken as:
- `overallBudget?.budgetAmount ?: 0.0`

That is still a flattened numeric path, not a fully normalized money contract.

**Call:** **open**

---

## What is genuinely better now

These are real improvements:

- `ExchangeRateDao.getRate()` ambiguity fix is real
- `AnalyticsCurrencyNormalizer` is real and uses `convertAsOf(expense.date)`
- stale/missing/invalid currency warnings exist
- `MoneyAggregateBuilder` centralization exists
- weekly/daily drilldown methods now exist
- dashboard summary now preserves `isPartial` + warning text better than before
- several widget totals now use `MultiCurrencyRepository` rather than obvious raw summing

So Pipeline 5 is **not** in a bad early-refactor state anymore.

---

## Why I still would not call it clean/stable

### 1. Mixed rate basis still exists
This is the big one.
- totals often use **latest**
- normalized analytics uses **historical**
- that can make one surface internally inconsistent

### 2. Partial-state still leaks away downstream
Even where `MoneyAggregate` exists, many consumers still only use:
- `displayAmount`
- `currency`
- plain `Double`

### 3. Budget comparisons are still not contract-safe
Budget amounts and spend amounts are not yet guaranteed to be normalized under the same period/rate policy.

### 4. Tests are not strong enough for closure
What I found:
- `MultiCurrencyRepositoryTest` is real and sizable
- but I did **not** see evidence of tests covering:
  - historical/as-of aggregate correctness
  - weekly/daily historical behavior
  - end-to-end dashboard partial warning propagation
- `AnalyticsCurrencyNormalizerTest` covers:
  - same-currency
  - foreign conversion
  - invalid currency
  - invalid home currency
  - missing rate
- but I did **not** see stale-rate test coverage

So the proof is still incomplete.

---

## Final scorecard

If I rewrote Pipeline 5 for HEAD `c424274`, I’d mark it roughly:

- **P5-P1-01 historical totals use latest-rate conversion:** **⚠ OPEN**
- **P5-P1-02 ambiguous `getRate()`:** **✅ FIXED**
- **P5-P1-03 dashboard adapter drops partial warnings:** **⚠ PARTIAL / tracker stale**
- **P5-P1-04 daily/weekly drilldown broken:** **⚠ MOSTLY FIXED functionally / tracker stale**
- **P5-P1-05 widgets raw-sum effectiveAmount:** **⚠ PARTIAL / materially improved**
- **P5-P1-06 stale-rate state not propagated:** **⚠ PARTIAL / tracker stale**
- **P5-P1-07 inconsistent builder use:** **⚠ MOSTLY FIXED / tracker stale**
- **P5-P1-08 budget-vs-actual normalization:** **⚠ OPEN**

---

## Bottom-line answer

### Are Pipeline 5 issues fixed?
**Some are, yes.**  
There is real progress in:
- rate lookup correctness
- normalization architecture
- partial-warning modeling
- drilldown support
- widget normalization in several places

### Are they clean and stable?
**No.**

Best summary:

> **Pipeline 5 has a much stronger money/currency architecture now, but it still mixes latest-rate and historical-rate contracts and still loses partial-state in downstream consumers, so I would not declare it clean or stable yet.**

---

## Sources

### Docs
- Master tracker  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Original Pipeline 5 debug report  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/debugging/pipeline-5-currency-dashboard-analytics-debug-report.md

### Code
- `MultiCurrencyRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
- `ExchangeRateDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt
- `CurrencyConverter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt
- `AnalyticsRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt
- `DashboardContractsAdapter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt
- `ComputeDashboardWidgetsUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `AnalyticsCurrencyNormalizer.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
- `DataQualityReport.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/DataQualityReport.kt
- `MoneyAggregateBuilder.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

### Tests
- `MultiCurrencyRepositoryTest.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepositoryTest.kt
- `AnalyticsCurrencyNormalizerTest.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizerTest.kt

## Scope note
This was a **static code/doc review** of current GitHub HEAD on **May 11, 2026**. I did **not** run Gradle, Room tests, or the app.