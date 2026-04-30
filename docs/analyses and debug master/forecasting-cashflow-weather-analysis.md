# Forecasting / Cash Flow / Financial Weather Deep Analysis

Branch: `master-refactor`

## Scope reviewed

Main files:
- `ForecastInputAssembler.kt`
- `SynthesisEngine.kt`
- `FinancialWeatherRepository.kt`
- `NarrativeGenerator.kt`
- `CalculateFinancialForecastUseCase.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `MonteCarloSpendingSimulator.kt`
- `HistoricalSpendingDistribution.kt`
- `DataQualityAssessor.kt`
- `FinancialStressForecastEngine.kt`
- `CashFlowCalculator.kt`
- forecast/dashboard domain models

## Executive verdict

The forecasting architecture is promising:

```text
transactions / budgets / recurring / planned / goals
→ ForecastInputAssembler
→ SynthesisEngine
→ FinancialWeather / dashboard widgets / Monte Carlo / stress forecast
```

But this area has several cross-pipeline risks. The biggest issue is that **there are multiple forecast paths using different data scopes and recurrence rules**, so Financial Weather, Monte Carlo, Runway, Block Party, Stress Forecast, and Cash Flow can disagree.

Highest-risk themes:

1. recurring bills are not consistently expanded into all occurrences
2. Monte Carlo can double-count known recurring/planned spend
3. dashboard forecast paths may use only current-month data
4. cash-flow and stress forecasts lack a real account-balance source
5. planned/recurring lifecycle is not strong enough to prevent double-counting
6. currency is absent from most forecast models and raw-summed everywhere

---

# Critical / high-priority findings

## 1. Main month forecast counts each recurring pattern only once

### Where

`SynthesisEngine.synthesizeInternal()`

Committed/likely recurring totals use:

```text
recurringPatterns.filter(nextExpectedDate in rest of month).sumOf(averageAmount)
```

That counts only the next occurrence.

### Impact

Weekly and biweekly bills are undercounted.

Example:

- Gym €10 weekly
- Today is April 1
- Four weekly payments remain

Expected committed recurring: about €40  
Current forecast likely counts: €10

This affects:

- Financial Weather
- Safe-to-spend
- total committed
- total likely
- discretionary budget
- Monte Carlo known upcoming
- runway

### Fix

Create one recurrence expansion helper:

```kotlin
expandOccurrences(pattern, startInclusive, endExclusive): List<ForecastOccurrence>
```

Use it everywhere instead of summing only `nextExpectedDate`.

Severity: **Critical**

---

## 2. Block Party recurrence logic can mark days before the next expected occurrence

### Where

`SynthesisEngine.isRecurringExpected()`

For weekly recurrence, it checks only day-of-week. For biweekly, `floorMod()` can also match dates before the anchor date.

### Impact

If `nextExpectedDate` is a future Monday, the calendar can mark earlier Mondays in the same month as bill days.

This can distort:

- daily target budget
- bill-day labels
- recurring impact
- user trust in the calendar

### Fix

Require:

```kotlin
date >= pattern.nextExpectedDate.startOfDay()
```

before checking weekly/biweekly/monthly cycle matches, unless intentionally rendering historical recurrence.

Severity: **High**

---

## 3. Block Party monthly recurring total uses monthly equivalent, but day spikes use actual calendar occurrences

### Where

`SynthesisEngine.calculateBlockPartyData()`

It subtracts:

```text
totalMonthlyRecurring = toMonthlyAmount(pattern.amount, frequency)
```

Then adds daily spikes for actual days in the visible month.

For weekly bills, monthly equivalent is usually `amount * 4.33`, while a real month can have 4 or 5 occurrences.

### Impact

The sum of daily targets can exceed or fall short of the actual budget.

Example:

- Weekly €10 bill
- A 5-Monday month
- Monthly equivalent ≈ €43.30
- Actual spikes = €50

Daily plan becomes inconsistent.

### Fix

For calendar-month UI, use actual expanded occurrences inside that month, not average monthly equivalents.

Severity: **High**

---

## 4. Monte Carlo likely double-counts recurring spend

### Where

`MonteCarloSpendingSimulator`
`HistoricalSpendingDistribution`
`ComputeDashboardWidgetsUseCase.computeMonteCarlo()`

Monte Carlo receives:

```text
spentToDate + knownUpcoming + sampled historical spending
```

But the historical distribution is built from all purchase/withdrawal spending. It does not remove recurring merchants or planned/known expenses.

### Impact

Known upcoming bills are added deterministically, then similar recurring spend is also sampled statistically from historical totals.

Example:

- Netflix included in historical weekly spending
- Netflix also included in known upcoming
- Forecast overstates spend

### Fix

Build a discretionary-only historical distribution:

```text
historical spending - confirmed recurring - matched planned/known events
```

Or run two models:

1. deterministic committed/planned/recurring
2. stochastic discretionary only

Severity: **Critical**

---

## 5. Dashboard forecast and financial-weather forecast use different data scopes

### Where

- `FinancialWeatherRepository.getFinancialWeather()`
- `ComputeDashboardWidgetsUseCase.computeRunwayAndForecast()`
- `DashboardContractsAdapter.observeDashboardExpenses()`

`FinancialWeatherRepository` uses `expenseRepository.getAllExpenses()`.

But dashboard widget recomputation uses `data.expenses`, which comes from dashboard expenses and is currently current-month scoped.

### Impact

The same dashboard can show:

- Financial Weather based on full history
- Runway/Monte Carlo/Block Party based on current-month-only expenses

This breaks:

- previous month baseline
- average monthly spending
- Monte Carlo context
- trend and pace
- forecast confidence

### Fix

Create explicit forecast data sources:

- current month actuals
- historical actuals
- discretionary history
- recurring rules
- planned occurrences
- budget statuses

Do not reuse `DashboardExpense` current-month stream for forecasting.

Severity: **High**

---

## 6. Confirmed recurring patterns are used, but detected recurring patterns are often ignored

### Where

`FinancialWeatherRepository.getFinancialWeather()`
`CalculateFinancialForecastUseCase`
`DashboardDataProvider`

The financial weather path uses:

```text
mergedRecurringPatternsProvider.getConfirmedPatterns(recurringEntities)
```

That means only manual/confirmed recurring rows are used.

There is a `getAllRecurringPatterns()` path that merges detected patterns, but it does not appear to feed the main weather forecast.

### Impact

The “predictive” engine can miss real recurring bills unless the user manually confirms them.

This may be intentional for privacy/control, but then the UI should say “confirmed bills only.” The spec suggests high-confidence detected recurring should affect forecasts.

### Fix

Use a policy:

- confirmed/manual: committed
- high-confidence detected: likely or committed
- medium-confidence detected: likely
- low-confidence detected: suggestion only

Severity: **High**

---

## 7. Planned expenses can double-count recurring/subscription obligations

### Where

`SynthesisEngine`
`ForecastInputAssembler`
`PlannedExpense`

Planned expenses have no source lifecycle fields:

- source recurring rule id
- source subscription id
- occurrence date
- linked actual expense id
- paid/matched/skipped status

### Impact

A recurring bill can appear as:

1. recurring pattern
2. generated planned expense
3. actual bank transaction

Forecast may count two or all three.

### Fix

Add occurrence identity:

```text
sourceType + sourceId + occurrenceDate
```

Then forecasting should include only unresolved future occurrences and exclude planned items already represented by recurring patterns or matched actuals.

Severity: **Critical**

---

## 8. Forecast money is raw `Double` with no currency

### Where

Most forecast models:

- `FinancialForecast`
- `ForecastComponents`
- `FinancialWeather`
- `MonteCarloResult`
- `StressHorizon`
- `DailyCashFlow`
- `PlannedExpense`

### Impact

Mixed-currency inputs are raw-summed:

```text
€20 + $20 = 40
```

Forecasts, budget comparison, stress probability, runway, and cash flow can all become financially meaningless.

### Fix

Forecasting must use `Money` / `MoneySnapshot` or base-currency-normalized amounts.

Minimum rule:

> Forecast totals must be in one declared currency.

Severity: **Critical if multi-currency is enabled**

---

## 9. Financial Stress Forecast uses `currentBalance = 0.0`

### Where

`FinancialStressForecastEngine.resolveStartingBalanceBaseline()`

The code intentionally uses neutral zero because there is no canonical account-balance source.

### Impact

This is not truly a cash-crunch forecast. It is more like:

```text
projected net flow from zero
```

A user with €5,000 cash and a user with €0 cash can get the same stress score.

### Fix

Either:

1. integrate real account/current balance, or
2. rename/reframe the widget as “cash-flow pressure,” not “cash crunch.”

Severity: **Critical UX / financial advice risk**

---

## 10. Stress forecast income timing is too simple

### Where

`FinancialStressForecastEngine.estimateIncome()`

It averages deposits over 90 days and scales linearly to 30/60/90 days.

### Impact

Payday timing is ignored.

Example:

- salary arrives in 20 days
- rent due tomorrow

Linear income smoothing can hide a near-term crunch.

### Fix

Use recurring income detection or deposit recurrence rules with actual expected dates.

Severity: **High**

---

## 11. CashFlowCalculator only includes the next recurring occurrence

### Where

`CashFlowCalculator.calculateDailyCashFlow()`

It adds recurring prediction only when:

```kotlin
pattern.nextExpectedDate is on current day
```

It does not expand future occurrences across the range.

### Impact

For a 90-day cash-flow calendar, weekly/biweekly/monthly recurring items after the first occurrence are missing.

### Fix

Use the same recurrence occurrence expander as Financial Weather and Stress Forecast.

Severity: **Critical**

---

## 12. CashFlowCalculator can double-count actual and predicted recurring on the same day

### Where

`CashFlowCalculator.calculateDailyCashFlow()`

It includes historical expenses and predicted recurring patterns for the same day.

### Impact

If the actual transaction has already happened, the predicted bill may still be added.

Example:

- Spotify actual expense on April 5
- recurring pattern nextExpectedDate April 5
- cash flow subtracts Spotify twice

### Fix

Before adding a predicted occurrence, check for matching actual expense by:

- merchant key
- date window
- amount tolerance
- currency

Severity: **High**

---

## 13. `getUpcomingBills(daysAhead)` uses a suspicious negative range helper

### Where

`CashFlowCalculator.getUpcomingBills()`

It uses:

```kotlin
TimePeriodUtils.getLastNDaysRange(now, -daysAhead).first
```

That is brittle and hard to reason about.

### Impact

Depending on helper implementation, the future horizon can be wrong.

### Fix

Use direct intent:

```kotlin
val horizonEnd = TimePeriodUtils.addDays(startOfToday, daysAhead)
```

Severity: **Medium / High**

---

## 14. Forecast confidence is too optimistic and disconnected from data quality

### Where

`SynthesisEngine.synthesizeInternal()`

Forecast confidence starts at `0.85` and only subtracts for:

- no budget
- no average monthly total
- no recurring patterns

It does not consider:

- sparse data
- detected vs manual recurring confidence
- mixed currency
- missing planned lifecycle
- stale data
- distribution quality
- duplicated planned/recurring risk

### Impact

A forecast can show high confidence while being based on weak or inconsistent inputs.

### Fix

Unify confidence scoring with `DataQualityAssessor` and add forecast-specific penalties.

Severity: **High**

---

## 15. Monte Carlo recency scoring overstates quality

### Where

`MonteCarloSpendingSimulator.countRecentQualifyingWeeks()`

It counts recent weeks with total > 0, not weeks that passed the original “3 distinct transaction days” quality filter.

### Impact

A recent week with one transaction counts as “recent qualifying,” even though it would not qualify for the distribution fit.

### Fix

Return per-week quality metadata from `HistoricalSpendingDistribution`, not just totals.

Severity: **Medium**

---

## 16. Historical distribution excludes quiet weeks, which can overstate future spend

### Where

`HistoricalSpendingDistribution.computeDistribution()`

Weeks with fewer than 3 transaction-days are excluded from the fit.

This improves data quality but can bias forecasts upward for naturally low-spend users.

### Impact

A user with many true quiet weeks can get a forecast based mostly on busier weeks.

### Fix

Distinguish:

- missing-data weeks
- true zero/quiet weeks

Do not exclude true quiet weeks from the spending distribution.

Severity: **Medium / High**

---

## 17. Forecast fallback can hide serious failures

### Where

`SynthesisEngine.synthesize()`
`FinancialWeatherRepository.getFinancialWeather()`

Errors degrade to medium/unknown forecasts.

That is good for app stability, but bad for diagnostics if not surfaced.

### Impact

Forecast may silently become generic or zeroed, and the user/dev cannot tell why.

### Fix

Add forecast diagnostics:

- input counts
- exception category
- missing data reasons
- currency conversion failures
- duplicate suppression counts

Severity: **Medium**

---

## 18. Rainy weather icon appears malformed

### Where

`NarrativeGenerator`

Rainy case icon is:

```text
"️"
```

Looks like a broken variation selector without a base emoji.

### Impact

Minor UI bug.

### Fix

Use a valid icon, e.g. `🌧️`.

Severity: **Low**

---

# Strong parts

## 1. Forecast assembly is centralized

`ForecastInputAssembler` is a good abstraction. Keep it as the boundary where entities become forecast-domain models.

## 2. Manual recurring patterns are rolled forward

`rollNextExpectedDateForward()` prevents stale manual recurring rows from staying in the past.

Good.

## 3. Planned expense priority weighting exists

MUST = 100%, LIKELY = 70%, OPTIONAL = 0%.

Good concept.

## 4. Monte Carlo exposes confidence metadata

`MonteCarloResult` includes quality/confidence metadata.

Good UX foundation.

## 5. Stress forecast separates recurring obligations and discretionary simulation

`FinancialStressForecastEngine` removes recurring merchants from discretionary simulation. This is better than the main Monte Carlo path.

## 6. Forecast models validate finite numeric values

Several domain models require finite positive amounts and bounded confidence.

Good defensive programming.

---

# Recommended fix order

## PR 1 — Create canonical ForecastOccurrence engine

Add one shared recurrence expansion component:

```kotlin
ForecastOccurrenceExpander
```

It should support:

- weekly
- biweekly
- monthly
- quarterly
- semiannual
- annual
- month-end handling
- start/end half-open ranges
- occurrence IDs

Use it in:

- `SynthesisEngine`
- `CashFlowCalculator`
- `FinancialStressForecastEngine`
- Block Party
- upcoming bills
- Monte Carlo known upcoming

## PR 2 — Add forecast money/currency foundation

Forecast models should use either:

- `Money`
- or normalized base-currency amounts with explicit currency

No forecast total should be raw `Double` without a currency.

## PR 3 — Fix planned/recurring/actual double-counting

Add occurrence lifecycle:

```text
sourceType
sourceId
occurrenceDate
status
linkedExpenseId
generatedKey
```

Forecast should include:

- actuals up to now
- future unresolved planned/recurring occurrences
- not both planned and recurring for same generated occurrence
- not predicted recurring if actual matching transaction already exists

## PR 4 — Unify forecast input scopes

Create a dedicated forecast repository contract:

```kotlin
ForecastDataSource
```

with explicit streams:

- actuals current month
- historical discretionary actuals
- confirmed recurring
- detected recurring candidates
- planned occurrences
- budget statuses
- savings goals
- income patterns
- account balances

Do not feed forecasting from current-month dashboard UI streams.

## PR 5 — Fix Monte Carlo double-counting

Historical distribution should be discretionary-only.

Known upcoming should contain committed/planned/recurring occurrences.

Monte Carlo should sample only the uncertain discretionary component.

## PR 6 — Reframe or fix stress forecast

Either add account balance support or rename the widget to “cash-flow pressure.”

If keeping “cash crunch,” require a real starting balance.

## PR 7 — Improve confidence and diagnostics

Forecast confidence should consider:

- data volume
- data recency
- currency completeness
- recurring confidence
- planned lifecycle quality
- account balance availability
- duplicate suppression
- forecast model quality

Also add non-sensitive diagnostics for debugging.

---

# Regression tests to add

1. Weekly recurring bill appears four/five times in rest-of-month forecast.
2. Biweekly recurring bill expands correctly from anchor date.
3. Recurring dates before `nextExpectedDate` are not marked as bill days.
4. Block Party monthly target sum matches budget for actual occurrences.
5. Monte Carlo does not double-count recurring bills.
6. Planned expense generated from recurring rule is not counted twice.
7. Actual paid bill suppresses predicted occurrence on same day.
8. Cash-flow 90-day calendar includes all recurring occurrences.
9. Upcoming bills horizon uses correct future range.
10. Stress forecast with no account balance is labeled as cash-flow pressure or asks for balance.
11. Salary timing affects near-term stress forecast.
12. Mixed-currency forecast refuses raw totals or converts correctly.
13. Forecast confidence drops for sparse/missing history.
14. Detected high-confidence recurring pattern appears as likely forecast item if policy allows it.
15. Financial Weather and Runway use the same forecast source and agree on committed/likely totals.
16. Rainy weather icon renders correctly.

---

# Top three fixes

If you only fix three things first:

1. **Add a canonical recurring occurrence expander and use it everywhere.**
2. **Prevent planned + recurring + actual double-counting through occurrence lifecycle keys.**
3. **Make Monte Carlo sample discretionary-only spending, not all historical spending.**

Those will remove the biggest forecast correctness bugs.

---

# Sources reviewed

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `SynthesisEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

- `FinancialWeatherRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt

- `NarrativeGenerator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt

- `CalculateFinancialForecastUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt

- `ComputeDashboardWidgetsUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `MonteCarloSpendingSimulator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt

- `HistoricalSpendingDistribution.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt

- `DataQualityAssessor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt

- `FinancialStressForecastEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt

- `CashFlowCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `financial weather.ini`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/financial%20weather.ini