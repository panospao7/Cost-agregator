# Currency Implementation Plan

## Goal

Make the app currency-safe across:

- dashboard
- budgets
- analytics
- forecasts
- health score
- savings
- groups
- exports
- AI/search summaries
- UI formatting

The immediate problem is not lack of infrastructure. The app already has:

- `CurrencyConverter`
- `CurrencySettingsRepository`
- `CurrencyRatesRepository`
- `ExchangeRateStore`
- `MultiCurrencyRepository`

The problem is that the main pipelines bypass them and raw-sum `effectiveAmount`.

---

# Core strategy

Do this in two layers:

## Layer 1 — Fast correctness bridge

Use existing grouped-by-currency DAO helpers and `MultiCurrencyRepository` to stop raw mixed-currency totals.

This gives quick visible correctness.

## Layer 2 — Durable money model

Add explicit currency fields to money-bearing entities and gradually move domain models from:

```text
Double amount
```

to:

```text
amount + currency + conversion status
```

Do not attempt a giant full rewrite first.

---

# Non-negotiable rules

## Rule 1

No financial aggregate may sum raw `effectiveAmount` across rows unless all rows are guaranteed to have the same currency.

## Rule 2

Every displayed money value must know its currency.

No more:

```text
CurrencyFormatter.format(amount)
```

unless the currency is explicit.

## Rule 3

Unknown currency must not silently become EUR.

Use:

```text
UNKNOWN
ASSUMED_HOME_CURRENCY
ASSUMED_LEGACY_EUR
USER_CONFIRMED
PARSED_FROM_SOURCE
```

## Rule 4

Currency conversion failures must be visible.

Do not silently drop failed currencies from totals.

---

# Phase 0 — Guardrails before refactor

## Goal

Prevent more currency bugs while changing the architecture.

## Tasks

### 0.1 Add a currency issue tracker epic

Create one epic:

```text
CURRENCY-FOUNDATION
```

Sub-tags:

```text
CURRENCY-DASHBOARD
CURRENCY-BUDGET
CURRENCY-ANALYTICS
CURRENCY-FORECAST
CURRENCY-SAVINGS
CURRENCY-UI
CURRENCY-SCHEMA
CURRENCY-PARSERS
```

### 0.2 Add CI grep checks

Fail or warn on new usages of:

```text
.sumOf { it.effectiveAmount }
CurrencyFormatter.format(amount)
currency = "EUR"
DEFAULT_CURRENCY = "EUR"
SUM(EFFECTIVE_AMOUNT_SQL)
```

Do not fail existing code immediately. Start with warning mode.

### 0.3 Deprecate dangerous helpers

Mark these as unsafe:

```text
ExpenseDao.getTotalSpentBetween
ExpenseDao.getTotalForPeriod
ExpenseDao.getCategorySpentInPeriod
CurrencyFormatter.format(amount) without currency
Double.toCurrency() without currency
```

Use deprecation messages like:

```text
Use currency-aware aggregate path.
```

### 0.4 Add test fixture

Create a simple canonical multi-currency fixture:

```text
Expense A: 50 EUR
Expense B: 100 USD
Exchange rate: 1 USD = 0.92 EUR
Expected EUR total: 142 EUR
Raw wrong total: 150
```

Use this same fixture in dashboard, budget, analytics, and forecast tests.

---

# Phase 1 — Define currency-safe domain result types

## Goal

Before touching every pipeline, define common result shapes.

## Add new domain types

Recommended package:

```text
domain/core/money
```

Types:

```text
CurrencyCode
MoneyAmount
ConvertedMoney
MoneyBucket
MoneyAggregate
ConversionFailure
CurrencyAssumption
```

Suggested meanings:

```text
MoneyAmount:
- amount
- currency

ConvertedMoney:
- original amount/currency
- converted amount/currency
- rate used
- rate timestamp
- conversion status

MoneyBucket:
- currency
- amount

MoneyAggregate:
- display amount
- display currency
- source buckets
- conversion failures
- isPartial
- warning message
```

## Important

Do not rename or delete existing `Money` immediately.

Current `domain/util/Money.kt` is currency-unaware. Deprecate it later or rename its role to decimal arithmetic.

For now, introduce new names to avoid massive breakage.

---

# Phase 2 — Strengthen MultiCurrencyRepository into the central aggregation bridge

## Goal

Use the existing unused repository as the migration bridge.

## Current problem

`MultiCurrencyRepository` is the only proper aggregation path, but nothing uses it.

## Tasks

### 2.1 Rename or wrap it

Either keep the name or create:

```text
CurrencyAggregationRepository
```

backed by `MultiCurrencyRepository`.

Recommended responsibilities:

```text
total spending in target currency
category totals in target currency
merchant totals in target currency
daily totals in target currency
monthly totals in target currency
totals grouped by original currency
conversion failure reporting
```

### 2.2 Do not silently exclude failed conversions

Current `CurrencyConverter.convertMultiple()` has strict semantics where failed conversions are not added.

That is okay internally, but UI/domain results must expose:

```text
failedConversions
isPartial = true
```

Example UI warning:

```text
“Total excludes 2 transactions because rates were unavailable.”
```

### 2.3 Add ownership/type filters

The repository should support:

```text
PURCHASE only
DEPOSIT only
WITHDRAWAL only
exclude transfers
include/exclude isNotMine
effective amount
gross amount
date range
category
merchant
```

### 2.4 Add home-currency convenience methods

Most app screens want home currency:

```text
getHomeCurrencyTotal(...)
getHomeCurrencyCategoryTotals(...)
getHomeCurrencyDailyHistory(...)
```

These should read `CurrencySettingsRepository.homeCurrency`.

---

# Phase 3 — Database schema additions

## Goal

Give core money-bearing entities explicit currency.

## Do not try to convert everything perfectly in one migration

Room migrations cannot easily use DataStore home currency.

Use safe defaults plus post-migration backfill.

---

## 3.1 Add currency to Budget

Current:

```text
Budget.amount: Double
```

Add:

```text
currency: String
currencyAssumption: String
```

Recommended initial values:

```text
currency = "EUR"
currencyAssumption = "LEGACY_DEFAULT"
```

Then run a post-migration startup backfill:

- if app home currency exists and user has changed it, optionally migrate old budgets to home currency with assumption flag
- otherwise keep EUR but mark as legacy assumed

Better UX:

```text
“Your existing budgets were assigned EUR because older versions did not store budget currency. Please review.”
```

## 3.2 Add currency to PlannedExpense

Add:

```text
currency
currencyAssumption
```

Future planned expenses must inherit from:

- user input
- recurring source currency
- subscription source currency
- home currency fallback with warning

## 3.3 Add currency to SavingsGoal

Add:

```text
targetCurrency
currentCurrency
```

Prefer one currency per goal:

```text
currency
targetAmount
currentAmount
```

## 3.4 Add currency to BudgetForecast

Add:

```text
currency
conversionStatus
```

## 3.5 Add currency to StressForecastSnapshot

Add:

```text
currency
conversionStatus
```

All monetary fields in the snapshot should be understood as that currency.

## 3.6 Add currency to AnomalyAlert

Add:

```text
currency
baseAmount?
baseCurrency?
```

## 3.7 Add currency to SavingsSweepPlan

Add:

```text
currency
conversionStatus
```

## 3.8 Add currency to SpendingChallengeEntity

Add:

```text
currency
```

At least for:

```text
targetAmount
baselineAmount
```

---

# Phase 4 — Dashboard first

## Why first

Dashboard is the most visible wrong result.

## Tasks

### 4.1 Add currency to DashboardExpense

Current:

```text
amount
effectiveAmount
```

Add:

```text
currency
```

Fix mapper so it copies from `Expense.currency`.

### 4.2 Fix ExpenseSnapshot hardcoded EUR

Current issue:

```text
currency = "EUR"
```

Change it to use actual expense currency.

### 4.3 Replace dashboard sums

Replace raw calculations like:

```text
todayPurchases.sumOf { it.effectiveAmount }
weekPurchases.sumOf { it.effectiveAmount }
monthPurchases.sumOf { it.effectiveAmount }
```

with currency aggregation in home currency.

### 4.4 Update dashboard models

`SpendingSummary` should include:

```text
currency
sourceCurrencyBuckets
conversionWarnings
isPartial
```

Daily history should become either:

```text
List<MoneyAmount>
```

or:

```text
List<PeriodMoneyTotal>
```

with a currency.

### 4.5 UI formatting

Home screen widgets must format with the returned currency.

No default EUR.

## Acceptance test

Fixture:

```text
50 EUR + 100 USD at 0.92
```

Dashboard month total should show:

```text
142 EUR
```

not:

```text
150 EUR
```

---

# Phase 5 — Budget pipeline

## Why second

Budgets are financial-control logic. Wrong currency here gives wrong advice.

## Tasks

### 5.1 Budget entity uses currency

All new budgets must have explicit currency.

Default new budget currency:

```text
home currency
```

not hardcoded EUR.

### 5.2 BudgetStatus gets currency

Current:

```text
spentAmount: Double
remainingAmount: Double
```

Change domain model to include:

```text
currency
spentAmount
remainingAmount
sourceCurrencyBuckets
conversionFailures
isPartial
```

### 5.3 Convert spend into budget currency

Budget spend should be:

```text
expense buckets grouped by currency
→ convert each bucket to budget.currency
→ sum converted values
```

### 5.4 Rollover must use budget currency

Rollover calculation should never use raw expense amounts.

Also fix earlier issue:

```text
do not mutate Budget.amount with rollover effective amount
```

Use:

```text
baseLimit
effectiveLimit
rolloverCarry
```

### 5.5 Budget alerts use currency

BudgetMonitor must format:

```text
spent amount
budget amount
remaining amount
```

with `budget.currency`.

### 5.6 Conversion failure behavior

If budget has unconvertible expenses:

Options:

1. Show partial budget status with warning.
2. Exclude failed conversions but lower confidence.
3. Block “healthy/on-track” label until conversion is complete.

Recommended:

```text
status = PARTIAL_DATA
```

or add warning:

```text
“Budget excludes 3 USD transactions due missing rate.”
```

## Acceptance tests

```text
Budget 200 EUR
Expense 50 EUR
Expense 100 USD at 0.92
spent = 142 EUR
remaining = 58 EUR
percent = 71%
```

---

# Phase 6 — UI formatting cleanup

## Goal

Stop silent EUR display bugs.

## Tasks

### 6.1 Make formatter require currency in new API

Introduce safer methods:

```text
formatMoney(amount, currency)
formatMoneyCompact(amount, currency)
formatMoneyWithSign(amount, currency)
```

Keep old methods temporarily but deprecated.

### 6.2 Fix UiTextArg.Money

Current:

```text
currency: String? = null
```

Make currency required, or require caller to provide a resolved display currency.

If nullable must remain for compatibility, treat null as an error/warning in debug builds.

### 6.3 Replace major UI call sites

Priority screens:

1. HomeScreen
2. BudgetScreen
3. AnalyticsScreen
4. CashFlowCalendarScreen
5. SavingsGoalsScreen
6. SpendingChallengesScreen
7. FinancialStressForecastCard
8. FinancialWeatherCard
9. MoneyRadarWidget

### 6.4 Remove default EUR from display path

`CurrencyFormatter.DEFAULT_CURRENCY = "EUR"` can remain internally only for legacy/debug, but production UI should not rely on it.

## Acceptance test

Search/CI check:

```text
CurrencyFormatter.format(amount)
```

should have zero production call sites except approved legacy wrappers.

---

# Phase 7 — Analytics pipeline

## Goal

Make insights, charts, comparisons, anomaly detection, and personality classification currency-safe.

## Tasks

### 7.1 Convert analytics input to home currency

Before analytics engines run, build a normalized analytics dataset:

```text
AnalyticsExpense:
- original amount/currency
- effective amount/currency
- home amount/currency
- conversion status
```

Analytics engines should operate on:

```text
homeAmount
```

only when conversion succeeded.

### 7.2 Preserve source buckets

Analytics results should still expose:

```text
sourceCurrencyBuckets
failedConversions
```

### 7.3 Category analytics

Replace raw:

```text
expenses.sumOf { it.effectiveAmount }
```

with converted category totals.

### 7.4 Day/week/month analytics

Daily/monthly histories should be in one declared currency.

### 7.5 Anomaly detection

For anomaly detection:

- compare amounts in normalized currency
- include original currency in anomaly details
- do not compare raw JPY/EUR/USD magnitudes

### 7.6 Spending personality

All spending ratios should use normalized currency.

## Acceptance tests

```text
Largest category across EUR/USD uses converted totals.
JPY transaction does not dominate just because numeric amount is large.
Analytics chart declares EUR or home currency.
```

---

# Phase 8 — Forecasting, stress, health, savings

## Goal

Make predictive engines operate in one declared currency.

## Tasks

### 8.1 ForecastInputAssembler

Convert historical expenses into home/base currency before building forecast input.

Each forecast input should carry:

```text
forecastCurrency
conversionCompleteness
```

### 8.2 FinancialStressForecastEngine

Replace hardcoded:

```text
DEFAULT_EMERGENCY_BUFFER = 500.0 // EUR
```

with configurable money:

```text
500 in home currency
```

or user setting.

### 8.3 MonteCarloSpendingSimulator

Keep simulator numeric internally, but require all inputs to be pre-normalized to one currency.

Add field to result:

```text
currency
```

### 8.4 FinancialHealthScoreV2

Income, expenses, budgets, savings goals must all be in comparable currency.

If savings goals have different currencies and cannot convert, health score should show partial confidence.

### 8.5 SmartSavingsEngine

Safe-to-save caps must be currency-aware.

Hardcoded numbers like:

```text
75
200
500
```

must be interpreted in home currency or configured per user.

### 8.6 Savings goals

Goals need explicit currency.

Do not sum savings goals across currencies unless converted.

## Acceptance tests

```text
Stress forecast uses home currency.
Smart savings recommendation says “EUR” or “USD”.
Health score confidence drops if conversion unavailable.
```

---

# Phase 9 — Groups and shared expenses

## Goal

Group balances, settlements, and shared budget offsets must respect currency.

## Tasks

### 9.1 Group default currency

New group default currency should come from:

```text
home currency
```

not hardcoded EUR.

### 9.2 GroupExpense currency

Each group expense already has currency. Keep it.

### 9.3 Settlement calculation

SettlementCalculator should either:

1. settle per currency separately, or
2. convert everything to group default currency.

Recommended for safety:

```text
per-currency settlement first
```

Example:

```text
Alice owes Bob 20 EUR
Alice owes Bob 15 USD
```

Do not silently merge unless conversion is explicit.

### 9.4 SharedExpenseBudgetOffsetEngine

Budget offsets should convert shared expenses into budget currency.

## Acceptance tests

```text
EUR group expense and USD group expense are not raw-summed.
Settlement output declares currency.
Budget offset uses budget currency.
```

---

# Phase 10 — Export and reports

## Goal

Exports must be honest and restorable/reportable.

## Tasks

### 10.1 PDF report

Already groups by currency. Keep that.

Optional:

- add converted home-currency summary
- show exchange rates used
- show conversion warning

### 10.2 CSV/accounting exports

Include explicit currency column everywhere.

If target accounting format is single-currency, enforce:

```text
all exported rows same currency
```

or convert with explicit exchange-rate metadata.

### 10.3 JSON export

Add:

```text
amount
currency
homeAmount
homeCurrency
conversionRate
conversionStatus
```

if exporting converted values.

## Acceptance tests

```text
CSV includes currency.
Single-currency accounting export rejects mixed currencies unless converted.
PDF does not raw-sum mixed currencies.
```

---

# Phase 11 — Parser and ingestion defaults

## Goal

Stop silently inventing EUR.

## Tasks

### 11.1 CurrencyNormalizer

Current behavior:

```text
unknown -> EUR
```

Change semantics to:

```text
unknown -> null / UNKNOWN
```

Then caller decides fallback.

### 11.2 AmountExtractionUtils

Do not fallback to EUR internally.

Return:

```text
amount
currency?
currencyConfidence
```

### 11.3 ReceiptParser

If no symbol/text currency found:

```text
currency = null
currencySource = UNKNOWN
```

Receipt review UI should default to home currency with warning.

### 11.4 Bank parsers

Greek-specific parsers may default to EUR only if source is explicitly Greek bank or EUR account.

BankStatementParser should not globally default to EUR.

### 11.5 AddExpenseViewModel

Default new manual expense currency to home currency.

## Acceptance tests

```text
Unknown currency receipt does not silently become EUR.
Manual expense default follows home currency.
Greek bank parser can default EUR only under Greek/EUR parser context.
```

---

# Phase 12 — Historical conversion snapshots

## Goal

Prevent old reports from changing after rate refresh.

## Current issue

Exchange rates appear latest-only by pair.

If totals are converted using latest rates, historical reports can change over time.

## Durable fix

Add historical rate support:

```text
fromCurrency
toCurrency
rate
validDate
fetchedAt
source
```

Unique:

```text
fromCurrency + toCurrency + validDate + source
```

## Expense-level snapshot

For future expenses, optionally store:

```text
baseAmount
baseCurrency
exchangeRateUsed
exchangeRateTimestamp
conversionStatus
```

This gives stable reporting.

## Backfill policy

For old data:

```text
baseAmount = null
conversionStatus = LEGACY_NOT_CONVERTED
```

or convert using closest available rate and mark:

```text
APPROXIMATED_FROM_AVAILABLE_RATE
```

Do not pretend old conversions are exact.

## Acceptance tests

```text
Historical report uses historical rate if available.
Rate refresh today does not change last year’s converted totals if snapshot exists.
Legacy rows show approximate/unknown conversion status.
```

---

# Phase 13 — Replace raw DAO aggregate usage

## Goal

Eliminate unsafe aggregate paths.

## Tasks

### 13.1 Keep grouped DAO helpers

These are useful:

```text
getAllSpentBetweenByCurrency
getAllCategoryTotalsBetweenByCurrency
getMerchantTotalsByCurrency
getMonthlyTotalsByCurrency
```

### 13.2 Deprecate raw total helpers

Unsafe:

```text
getTotalSpentBetween
getTotalForPeriod
getCategorySpentInPeriod
```

Keep only if renamed to make danger obvious:

```text
getRawTotalSpentBetweenUnsafe
```

### 13.3 Refactor pipelines one by one

Order:

1. Dashboard
2. Budget
3. Analytics
4. Forecast
5. Health
6. Savings
7. Search/AI query
8. Groups
9. Exports

## Acceptance check

Production code should not call raw aggregate DAO methods except inside approved grouped-by-currency implementations.

---

# Phase 14 — AI/search financial queries

## Goal

Assistant answers must not raw-rank or raw-filter across currencies.

## Tasks

### 14.1 Add currency to query filters

Financial query filters need:

```text
currency
targetCurrency
amountCurrency
```

### 14.2 Amount filters

Query:

```text
expenses over $50
```

must not match:

```text
¥51
```

unless conversion is requested and available.

### 14.3 Largest/top queries

Use converted home amount or ask clarification.

### 14.4 Output

If mixed currencies are present:

```text
show per-currency buckets
```

or:

```text
converted total in home currency with warning
```

## Acceptance tests

```text
“largest purchase” across USD/EUR uses converted value.
“over $50” does not raw-compare every currency.
Assistant answer includes currency.
```

---

# Suggested PR sequence

## PR 1 — Guardrails and tests

- add canonical multi-currency test fixture
- add warning grep checks
- deprecate unsafe formatter overloads and raw aggregate methods
- no behavior change yet

## PR 2 — Core money result types

- add `CurrencyCode`, `MoneyAmount`, `MoneyAggregate`, conversion failure models
- add mapper helpers
- no major pipeline change yet

## PR 3 — Upgrade MultiCurrencyRepository

- expose home-currency aggregate methods
- expose conversion failures
- support filters needed by dashboard/budget
- add unit tests

## PR 4 — Dashboard currency fix

- add currency to `DashboardExpense`
- fix `ExpenseSnapshot.currency`
- use currency-aware totals for today/week/month
- pass currency to HomeScreen formatter

This gives visible win.

## PR 5 — Budget schema and budget status fix

- add `Budget.currency`
- add migration
- update new-budget creation
- update `BudgetStatus`
- convert spending into budget currency
- update BudgetScreen and BudgetMonitor

## PR 6 — UI formatter cleanup

- replace major `CurrencyFormatter.format(amount)` call sites
- update `UiTextArg.Money`
- keep old API deprecated but not removed

## PR 7 — Analytics normalized dataset

- build analytics input in home currency
- migrate category/month/day engines
- show conversion warnings

## PR 8 — Forecast/stress/savings/health

- normalize forecast inputs
- add currency to outputs
- remove hardcoded EUR emergency buffer
- add confidence warnings

## PR 9 — Entity currency expansion

- PlannedExpense currency
- SavingsGoal currency
- BudgetForecast currency
- StressForecastSnapshot currency
- SavingsSweepPlan currency
- SpendingChallenge currency
- AnomalyAlert currency

## PR 10 — Parser default cleanup

- unknown currency no longer silently EUR
- receipt/manual add default to home currency with warning
- bank parsers use contextual default only

## PR 11 — Historical rates and snapshots

- historical exchange rates
- optional expense base snapshot
- conversion status for legacy data

## PR 12 — Cleanup unsafe raw paths

- remove or rename unsafe DAO methods
- enforce CI failure for new raw sums
- remaining exceptions documented

---

# Migration policy for existing users

## Existing expenses

They already have `currency`, default likely EUR.

Do not mass-change expense currency unless user confirms.

## Existing budgets

Because old budgets had no currency:

Recommended:

```text
budget.currency = current home currency if available during post-migration backfill
otherwise EUR
currencyAssumption = LEGACY_ASSUMED
```

Show review prompt:

```text
“Older budgets did not store currency. Please review budget currencies.”
```

## Existing savings goals/planned expenses

Same approach:

```text
assign home currency
mark assumption
allow user review
```

## Existing forecasts/analytics snapshots

Old snapshots can be invalidated or marked legacy.

Do not mix old raw snapshots with new currency-safe ones.

---

# Testing matrix

## Unit tests

- currency conversion direct pair
- EUR-intermediate conversion
- failed conversion returns warning
- per-currency grouping
- budget percent after conversion
- formatter requires explicit currency
- unknown parser currency remains unknown

## DAO tests

- grouped totals by currency
- category totals by currency
- monthly totals by currency
- no raw mixed-currency totals in new APIs

## Integration tests

- dashboard total with EUR + USD
- budget status with EUR budget + USD expense
- analytics chart with mixed currencies
- forecast generated in home currency
- savings goal in USD while home currency EUR

## UI tests

- HomeScreen shows correct currency symbol
- BudgetScreen shows budget currency
- AnalyticsScreen displays conversion warning
- AddExpense defaults to home currency
- Receipt scan allows currency correction

## Regression tests

Canonical fixture everywhere:

```text
50 EUR
100 USD
USD→EUR = 0.92
expected total = 142 EUR
```

Also add:

```text
1000 JPY should not rank above 20 EUR unless converted value is higher.
```

---

# Definition of done

Currency foundation is acceptable when:

1. Dashboard does not raw-sum mixed currencies.
2. Budgets have explicit currency.
3. Budget spend is converted into budget currency.
4. Analytics outputs declare currency.
5. Forecast/stress/savings/health outputs declare currency.
6. UI money formatting always receives currency.
7. Unknown currency is not silently EUR.
8. Conversion failures are visible.
9. New code cannot introduce raw `effectiveAmount` sums without warning/failure.
10. Existing legacy rows are marked with currency assumption status.

---

# Practical priority

If you only do three PRs first:

1. **Wire `MultiCurrencyRepository` into dashboard totals.**
2. **Add `Budget.currency` and make budget status currency-aware.**
3. **Remove silent EUR formatting defaults from major UI screens.**

That will fix the most user-visible wrong-money behavior fastest.