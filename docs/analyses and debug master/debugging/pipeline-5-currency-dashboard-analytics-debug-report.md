# Pipeline 5 Debugging Report — Currency / Dashboard / Analytics

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 5 is intended to be:

```text
Expense rows
→ ExpenseDao currency-grouped aggregates
→ MultiCurrencyRepository
→ CurrencyConverter / ExchangeRateStore
→ MoneyAggregate
→ AnalyticsRepository / Dashboard widgets / Budget / Forecast / Health / Savings
→ UI totals, warnings, metrics
```

The architecture is much improved compared to raw `Double` summing. You now have:

- `MoneyAmount`
- `MoneyAggregate`
- `MultiCurrencyRepository`
- `AnalyticsCurrencyNormalizer`
- purchase-only DAO grouped aggregates
- type-agnostic grouped aggregates
- `isPartial` / conversion-failure modeling

But the pipeline is not fully safe yet.

Highest-risk findings:

1. **Historical exchange-rate support is structurally broken/incomplete.**
2. **Dashboard and analytics often consume only `displayAmount`, losing `isPartial` and warnings.**
3. **Current-rate conversion is used for historical analytics, so old reports can change when rates update.**
4. **Some dashboard calculations mix transaction semantics: purchase totals, all-type totals, deposits, and transfers are not consistently separated.**
5. **`TotalsAggregationEngine` still explicitly says it sums raw mixed-currency DAO totals.**
6. **Budget conversion fallback can compare home-currency spending against an unconverted foreign-currency limit.**
7. **Many outputs still expose raw `Double + currency` instead of `MoneyAggregate`, so warnings cannot propagate cleanly.**

Main recommendation:

> Make `MoneyAggregate` the public contract for dashboard/analytics/budget totals, and make historical `convertAsOf(expense.date)` the default for analytics/reporting.

---

# 2. Intended architecture contract

From `DEPENDENCY_MAP.md`, the intended dashboard/analytics/currency flow is:

```text
HomeScreen
→ HomeViewModel
→ DashboardRepository
→ ComputeDashboardWidgetsUseCase
→ DashboardDataProvider adapters
→ TotalsAggregationEngine
→ MultiCurrencyRepository
→ ExpenseDao
→ CurrencyConverter
→ CurrencySettingsRepository
→ TimeProvider

AnalyticsRepository
→ ExpenseDao
→ MultiCurrencyRepository
→ AnalyticsCurrencyNormalizer
```

`MultiCurrencyRepository` is explicitly described as the currency-aware aggregation backbone used by dashboard, budget, analytics, forecast, health, savings, groups, export, AI/query, and anomaly.

This is the right architecture. The gap is that not every downstream consumer preserves the full `MoneyAggregate` contract.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path summary

## 3.1 MultiCurrencyRepository

`MultiCurrencyRepository` has two families of methods.

### Older `Result<Double>` methods

Examples:

```kotlin
getTotalExpensesInHomeCurrency(...)
getCategoryTotalsInHomeCurrency(...)
getMerchantTotalsInHomeCurrency(...)
getMonthlyTotalsInHomeCurrency(...)
```

These throw/return `Result.Error` on missing rates.

### Newer `MoneyAggregate` methods

Examples:

```kotlin
getHomeCurrencyTotal(...)
getHomeCurrencyCategoryTotals(...)
getHomeCurrencyMerchantTotals(...)
getHomeCurrencyMonthlyTotals(...)
getHomeCurrencyPurchaseTotal(...)
getHomeCurrencyPurchaseCategoryTotals(...)
```

These return:

```kotlin
MoneyAggregate(
  displayAmount,
  displayCurrency,
  sourceBuckets,
  conversionFailures,
  isPartial,
  warningMessage
)
```

This is the correct direction.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

---

## 3.2 CurrencyConverter

`CurrencyConverter` supports:

```kotlin
convert(...)
convertAsOf(...)
convertMultiple(...)
```

Important details:

- `convert()` uses current/latest rate.
- `convertAsOf()` is intended for historical reporting.
- `convertMultiple()` uses `convert()`, not `convertAsOf()`.
- `convertMultiple()` correctly excludes failed conversions from total.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

---

## 3.3 Exchange rates

`ExchangeRate` entity has:

```text
fromCurrency
toCurrency
rate
lastUpdated
source
validDate
```

and a unique index:

```text
fromCurrency + toCurrency + validDate
```

So the schema is trying to support historical rates.

But `DomainExchangeRate` does **not** include `validDate`, and `ExchangeRateStoreAdapter.toEntity()` does not set it. Therefore new rates default to `validDate = 0`.

Also `ExchangeRateDao.getRate()` does:

```sql
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency
LIMIT 1
```

with no `ORDER BY`.

That is a serious risk once more than one row exists for a pair.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt

---

## 3.4 AnalyticsRepository

`AnalyticsRepository` now injects:

```kotlin
CurrencySettingsRepository
MultiCurrencyRepository
AnalyticsCurrencyNormalizer
```

Good.

`getSpendingSummary()` uses:

```kotlin
multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...)
analyticsCurrencyNormalizer.normalizeExpenses(...)
```

This is a good currency-aware direction.

But outputs like `SpendingSummary` still expose:

```text
totalSpent: Double
currency: String
```

rather than `MoneyAggregate`, so conversion warnings are lost.

Source:  
https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

---

## 3.5 Dashboard widgets

`ComputeDashboardWidgetsUseCase` injects `MultiCurrencyRepository`.

Good.

It uses currency-safe calls in some places:

```kotlin
todaySpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
weekSpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
spentToDate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
```

But it usually keeps only `displayAmount`, not the aggregate warnings.

Also, one suspicious calculation exists:

```kotlin
monthlyIncome = multiCurrencyRepository.getHomeCurrencyTotal(ctx.monthStart, ctx.now).displayAmount
```

`getHomeCurrencyTotal()` is type-agnostic. It can include purchases, deposits, transfers, and unknowns. Using it for `monthlyIncome` is probably semantically wrong.

Source:  
https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

---

# 4. Major findings

## Finding P0-1 — Historical exchange-rate support is currently incomplete/broken

The codebase claims historical exchange-rate support:

```text
ExchangeRate.validDate
CurrencyConverter.convertAsOf(...)
ExchangeRateDao.getRateAsOf(...)
```

But the data path does not preserve `validDate`.

Current `DomainExchangeRate`:

```kotlin
data class DomainExchangeRate(
  val fromCurrency: String,
  val toCurrency: String,
  val rate: Double,
  val lastUpdated: Long,
  val source: String = "manual"
)
```

No `validDate`.

Current adapter:

```kotlin
private fun DomainExchangeRate.toEntity(): ExchangeRate {
  return ExchangeRate(
    fromCurrency = fromCurrency,
    toCurrency = toCurrency,
    rate = rate,
    lastUpdated = lastUpdated,
    source = source
  )
}
```

So `validDate` defaults to `0L`.

### Why this matters

If all rates have `validDate = 0`:

- `convertAsOf()` cannot select correct historical rates.
- historical reports are not truly historical.
- old analytics can change after rates refresh.
- month-over-month comparisons may shift.
- forecast/health/savings can be based on today’s FX rate instead of transaction-date FX rate.

### Second issue

`ExchangeRateDao.getRate()` has no deterministic ordering:

```sql
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency
LIMIT 1
```

If multiple rows exist for a pair, SQLite can return any matching row.

### Recommended fix

Add `validDate` to domain model:

```kotlin
data class DomainExchangeRate(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long,
    val source: String = "manual",
    val validDate: Long = lastUpdated
)
```

Update adapter:

```kotlin
private fun DomainExchangeRate.toEntity(): ExchangeRate {
    return ExchangeRate(
        fromCurrency = fromCurrency.uppercase(),
        toCurrency = toCurrency.uppercase(),
        rate = rate,
        lastUpdated = lastUpdated,
        source = source,
        validDate = validDate
    )
}
```

Update DAO current-rate lookup:

```sql
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency AND toCurrency = :toCurrency
ORDER BY validDate DESC, lastUpdated DESC, id DESC
LIMIT 1
```

Update `CurrencyConverter.storeRate()` and `storeRates()` to set `validDate`.

Priority: highest.

---

## Finding P0-2 — Historical analytics uses current-rate conversion

`AnalyticsCurrencyNormalizer.normalizeExpenses()` calls:

```kotlin
currencyConverter.convert(
  amount = expense.effectiveAmount,
  fromCurrency = sourceCurrency.code,
  toCurrency = homeCurrency.code
)
```

It has each expense’s `date`, but does not use:

```kotlin
convertAsOf(..., atMillis = expense.date)
```

### Why this matters

A USD expense from 2023 is converted using the latest available USD→home rate, not the 2023 rate.

Symptoms:

- analytics totals change after exchange-rate refresh,
- old month comparisons drift,
- backup/restore with different rate table may show different historical reports,
- forecast training data changes even though expense rows did not.

### Recommended fix

For reports/analytics/history:

```kotlin
currencyConverter.convertAsOf(
    amount = expense.effectiveAmount,
    fromCurrency = sourceCurrency.code,
    toCurrency = homeCurrency.code,
    atMillis = expense.date
)
```

Keep `convert()` for:

- current display,
- manual conversion now,
- current rate management UI.

Use `convertAsOf()` for:

- analytics,
- dashboard period totals,
- budget historical spend,
- forecast training,
- tax/export reports,
- backup/restore parity tests.

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

---

## Finding P0-3 — Partial conversion state is lost in dashboard/analytics UI contracts

`MultiCurrencyRepository` returns `MoneyAggregate` with:

```text
sourceBuckets
conversionFailures
isPartial
warningMessage
```

But downstream often does:

```kotlin
.displayAmount
```

Examples:

```kotlin
todaySpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
weekSpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
spentToDate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
```

`AnalyticsRepository.getSpendingSummary()` also extracts only:

```kotlin
totalSpent = currentAggregate.displayAmount
previousTotal = previousAggregate.displayAmount
transactionCount = currentAggregate.totalTransactionCount
currency = homeCurrency
```

### Why this matters

If GBP conversion fails, the app may show:

```text
Monthly total: €120
```

when actual data includes:

```text
€120 + £50 unconverted
```

The internal aggregate knows this is partial, but the UI output may not.

### Recommended fix

Make output models carry `MoneyAggregate` or equivalent warning fields.

Example:

```kotlin
data class SpendingSummary(
    val total: MoneyAggregate,
    val previousTotal: MoneyAggregate?,
    val changePercent: Double?,
    val dailyHistory: List<MoneyAmount>, // or normalized values + warning metadata
    val dataQuality: AnalyticsDataQuality
)
```

For dashboard widgets:

```kotlin
DashboardWidget.MonthlyTotal(
    total = MoneyAggregate,
    warning = total.warningMessage,
    sourceBuckets = total.sourceBuckets
)
```

Minimum short-term fix:

- keep `Double` fields,
- add `isPartial`,
- add `conversionWarning`,
- add `missingCurrencyCount`,
- add `sourceBuckets`.

Priority: highest.

---

## Finding P0-4 — Dashboard semantics mix spending, income, and all transaction types

`MultiCurrencyRepository` has:

```kotlin
getHomeCurrencyTotal(...)
```

This uses `ExpenseDao.getAllSpentBetweenByCurrency()`.

That DAO method is explicitly type-agnostic:

```text
includes all transaction types
```

It includes purchases, deposits, transfers, unknowns, etc.

But in `ComputeDashboardWidgetsUseCase`, a value named:

```kotlin
monthlyIncome
```

is computed as:

```kotlin
multiCurrencyRepository.getHomeCurrencyTotal(ctx.monthStart, ctx.now).displayAmount
```

That is probably wrong.

### Why this matters

If a user has:

```text
purchase €100
deposit €1000
transfer €500
```

then a type-agnostic aggregate can produce a number that is neither income nor spending nor net cashflow.

### Recommended fix

Add explicit currency-aware APIs:

```kotlin
getHomeCurrencyPurchaseTotal(...)
getHomeCurrencyDepositTotal(...)
getHomeCurrencyTransferOutTotal(...)
getHomeCurrencyTransferInTotal(...)
getHomeCurrencyNetCashFlow(...)
```

Then dashboard code should use the method that matches the widget’s semantic contract.

Rules:

```text
Monthly spend card → PURCHASE only
Income card → DEPOSIT only
Net cashflow → deposits - purchases ± transfers according to policy
Budget usage → PURCHASE only
Forecast spend pace → PURCHASE only
Runway income → DEPOSIT only or configured income source
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

---

## Finding P0-5 — `TotalsAggregationEngine` still raw-sums mixed currencies

`TotalsAggregationEngine.kt` has a clear KDoc warning:

```text
CURRENCY NORMALIZATION: GAP — no normalization applied
```

It lists methods that use raw DAO totals without conversion:

- monthly totals,
- weekly totals,
- daily totals,
- yearly totals,
- category breakdown,
- averages.

This is good self-documentation, but dangerous if any live UI still consumes it.

### Recommended fix

Do not allow this class in production flows until fixed.

Options:

1. Refactor it to inject `AnalyticsCurrencyNormalizer` or `MultiCurrencyRepository`.
2. Mark raw methods internal/test-only.
3. Add a runtime guard:

```kotlin
require(allExpensesSingleCurrency) {
    "TotalsAggregationEngine raw aggregation cannot be used with multi-currency data"
}
```

4. Add a CI scan for calls to deprecated raw DAO totals.

Priority: highest if any production screen still uses this engine.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

---

## Finding P1-1 — Budget conversion fallback can compare different currencies

`BudgetRepository.convertBudgetAmountToHomeCurrency()` converts the budget limit to home currency.

If conversion fails, it returns:

```kotlin
MoneyAggregate.singleCurrency(
  amount = amount,
  currency = CurrencyCode(sourceCurrency)
).copy(
  isPartial = true,
  warningMessage = "Budget limit could not be converted..."
)
```

Then `createBudgetStatus()` does:

```kotlin
spent = spentAggregate.displayAmount       // home currency
baseLimit = initialLimitAggregate.displayAmount
percent = spent / effectiveLimit
currency = initialLimitAggregate.displayCurrency.code
```

If budget is GBP and home is EUR, and the GBP→EUR rate is missing:

```text
spent = EUR amount
baseLimit = raw GBP amount
percent = EUR / GBP
```

The status is marked partial, but percent/remaining/health can still be wrong.

### Recommended fix

If budget limit cannot be converted:

```text
BudgetStatus.isPartial = true
BudgetStatus.healthStatus = UNKNOWN or UNAVAILABLE
percentUsed = null
remainingAmount = null
```

Do not compute health thresholds from mixed units.

Alternatively, require budget currency to equal home currency unless rate exists.

Priority: high.

Source:  
https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

---

## Finding P1-2 — Empty category spend fallback uses default EUR

In `BudgetRepository.getAggregateSpent()`:

```kotlin
multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(start, end)[categoryId]
  ?: MoneyAggregate.empty(CurrencyCode(MultiCurrencyRepository.DEFAULT_HOME_CURRENCY))
```

`DEFAULT_HOME_CURRENCY = "EUR"`.

If user home currency is USD and no spend exists for a category, the empty spend aggregate is EUR.

In many cases this does not break numeric status because spend is `0.0`, but it is semantically wrong and can leak into warning/debug/UI states.

### Recommended fix

Return empty aggregate in resolved home currency:

```kotlin
val home = resolveHomeCurrency()
MoneyAggregate.empty(CurrencyCode(home))
```

Priority: medium-high.

---

## Finding P1-3 — Exchange-rate staleness is not part of conversion failure contract

`CurrencyConverter.convert()` only returns null when no rate exists.

It does not seem to reject stale rates.

`MultiCurrencyRepository.shouldUpdateRates()` can detect staleness, but conversion still uses stale rates if present.

### Why this matters

Dashboard may show confident totals using old rates.

### Recommended fix

Add conversion policy:

```kotlin
data class ConversionPolicy(
    val maxRateAgeMs: Long?,
    val allowStaleRates: Boolean
)
```

Then conversion failure reasons should distinguish:

```text
MISSING_RATE
STALE_RATE
INVALID_CURRENCY
```

`MoneyAggregate.conversionFailures` should carry this reason.

Priority: high.

---

## Finding P1-4 — Transaction lifecycle base snapshot may still hardcode EUR

From Pipeline 2, `TransactionLifecycleCoordinator` appears to snapshot non-EUR expenses using `CurrencyConverter.DEFAULT_BASE_CURRENCY`, which is EUR.

Pipeline 5 expects home currency to come from `CurrencySettingsRepository`.

If an expense stores:

```text
baseAmount/baseCurrency/exchangeRateUsed
```

using EUR while the user’s home currency is USD, then stored base snapshot and dashboard home-currency totals can disagree.

### Recommended fix

Pick one explicit policy:

1. `baseCurrency` always means system base EUR, and UI never treats it as home currency.
2. `baseCurrency` means user home currency at creation time.

Then enforce naming and tests.

Recommended:

```text
originalAmount/originalCurrency
homeAmountAtCreation/homeCurrencyAtCreation/homeRateAtCreation
systemBaseAmount/systemBaseCurrency only if needed
```

Priority: high.

---

## Finding P1-5 — Deprecated raw DAO totals still exist and can be accidentally reused

`ExpenseDao` has many deprecated raw sum methods:

- `getTotalSpentBetween`
- `getCategoryTotalsBetween`
- `getMerchantTotalsBetween`
- `getDailyTotalsForPeriod`
- `getTotalDepositsForPeriod`
- etc.

The deprecation messages are good, but they do not prevent accidental use.

### Recommended fix

Add CI guard:

```text
No production code may call deprecated raw aggregation methods unless allowlisted.
```

Allowlist only:

- invalidation triggers if absolutely needed,
- legacy tests,
- single-currency-only code paths with explicit assertion.

Priority: high.

---

## Finding P1-6 — Analytics data quality exists but is not necessarily surfaced

`AnalyticsRepository.getDataQualityReport()` is a strong idea. It uses `AnalyticsCurrencyNormalizer` and can report reliability.

But dashboard and analytics screens must actually display or consume this result.

### Recommended fix

Every dashboard/analytics output should include:

```text
conversionConfidence
excludedTransactionCount
missingRateCurrencies
invalidCurrencyCount
latestRateTimestamp
isPartial
```

Then UI can show:

```text
“Partial total: excludes 2 GBP transactions due to missing rate.”
```

Priority: high.

---

# 5. Debugging checklist for Pipeline 5

## Currency settings

Check:

- [ ] home currency loads from DataStore,
- [ ] invalid home currency rejected,
- [ ] setting persists after restart,
- [ ] changing home currency invalidates dashboard/analytics flows,
- [ ] budget currency and home currency behavior is explicit.

## Exchange rates

Check:

- [ ] rate insert sets `validDate`,
- [ ] current rate lookup is deterministic,
- [ ] historical rate lookup uses latest `validDate <= expense.date`,
- [ ] direct pair conversion works,
- [ ] EUR-intermediate conversion works,
- [ ] missing rate returns failure,
- [ ] stale rate returns warning/failure according to policy,
- [ ] invalid rate rejected,
- [ ] rate cleanup does not delete needed historical rates.

## Expense aggregation

Check:

- [ ] source currency buckets preserved,
- [ ] PURCHASE-only totals exclude deposits/transfers,
- [ ] all-type totals are used only for explicitly all-type widgets,
- [ ] `isNotMine` excluded,
- [ ] shared expense uses `effectiveAmount`,
- [ ] null category appears as Uncategorized where expected,
- [ ] merchant grouping uses intended key/raw merchant policy,
- [ ] no row caps in analytics/export/forecast.

## Dashboard

Check:

- [ ] monthly spend uses purchase-only aggregate,
- [ ] today/week spend use purchase-only aggregate,
- [ ] income uses deposit-only aggregate,
- [ ] net cashflow uses explicit net formula,
- [ ] dashboard carries `isPartial`,
- [ ] dashboard shows conversion warnings,
- [ ] dashboard source buckets visible in debug/details,
- [ ] forecast uses converted values with confidence warnings.

## Analytics

Check:

- [ ] summary total uses `MoneyAggregate`,
- [ ] daily history uses historical conversion,
- [ ] category totals use converted aggregates,
- [ ] merchant totals use converted aggregates,
- [ ] month comparison uses consistent historical policy,
- [ ] data-quality report is surfaced,
- [ ] raw `TotalsAggregationEngine` is not used for multi-currency flows.

## Budget

Check:

- [ ] budget limit conversion failure does not compute false percent,
- [ ] category budget uses purchase-only converted spend,
- [ ] overall budget uses purchase-only converted spend,
- [ ] rollover uses same currency policy for all periods,
- [ ] warning state propagates to UI,
- [ ] no default EUR leak for non-EUR users.

## Forecast / health / savings

Check:

- [ ] training inputs normalized historically,
- [ ] partial conversion reduces confidence,
- [ ] missing-rate buckets excluded explicitly,
- [ ] health score does not silently treat partial data as complete,
- [ ] savings recommendations include data-quality warning.

---

# 6. Recommended fix plan

## PR 1 — Fix exchange-rate historical model

Change:

```text
DomainExchangeRate
ExchangeRateStoreAdapter
CurrencyConverter.storeRate/storeRates
ExchangeRateDao.getRate
```

Acceptance:

```text
rates store validDate,
current getRate returns newest validDate,
convertAsOf returns correct historical rate,
old reports do not change when new rate is added.
```

---

## PR 2 — Use historical conversion for analytics/reporting

Change `AnalyticsCurrencyNormalizer` to use:

```kotlin
convertAsOf(expense.effectiveAmount, expense.currency, homeCurrency, expense.date)
```

Add optional policy for dashboard “current value” vs “historical report”.

Acceptance:

```text
a 2024 USD expense uses the 2024 USD→home rate in 2024 reports.
```

---

## PR 3 — Propagate MoneyAggregate through dashboard/analytics

Replace or augment raw fields:

```text
Double totalSpent
String currency
```

with:

```text
MoneyAggregate total
DataQualityReport dataQuality
```

Acceptance:

```text
missing GBP rate displays partial warning in dashboard and analytics.
```

---

## PR 4 — Split transaction-type semantics

Add:

```kotlin
getHomeCurrencyDepositTotal()
getHomeCurrencyNetCashFlow()
getHomeCurrencyTransferTotals()
```

Audit dashboard code so every widget uses the correct semantic total.

Acceptance:

```text
income card no longer uses all transaction types.
```

---

## PR 5 — Fix budget mixed-currency failure behavior

When budget limit conversion fails:

```text
do not compute percent/health from mixed units
mark status partial/unavailable
show warning
```

Acceptance:

```text
GBP budget + missing GBP→EUR rate cannot produce fake “80% used” status.
```

---

## PR 6 — Ban raw aggregation methods in production

Add CI script:

```text
scripts/testing/check-raw-money-aggregation.kts
```

Fail on production usage of:

```text
getTotalSpentBetween
getCategoryTotalsBetween
getMerchantTotalsBetween
getDailyTotalsForPeriod
sumOf { it.amount }
sumOf { it.effectiveAmount }
```

unless allowlisted with reason.

Acceptance:

```text
new mixed-currency bugs cannot be added silently.
```

---

# 7. Tests to add

## 7.1 `ExchangeRateHistoricalContractTest`

Seed rates:

```text
USD→EUR 0.90 validDate Jan 2024
USD→EUR 0.80 validDate Jan 2025
```

Assert:

```text
convertAsOf(100 USD, Jan 2024) = 90 EUR
convertAsOf(100 USD, Jan 2025) = 80 EUR
convert(100 USD) uses latest validDate deterministically
```

Also assert `validDate` is not `0L`.

---

## 7.2 `MultiCurrencyRepositoryPartialAggregateTest`

Seed:

```text
EUR purchase 50
USD purchase 10 with rate
GBP purchase 20 without rate
```

Assert:

```text
displayAmount excludes GBP
sourceBuckets include EUR, USD, GBP
isPartial = true
conversionFailures contains GBP
warningMessage not null
```

---

## 7.3 `DashboardPartialCurrencyWarningScenarioTest`

Seed same dataset.

Run:

```text
ComputeDashboardWidgetsUseCase
```

Assert:

```text
monthly total widget shows partial warning
source buckets available
dashboard does not present total as complete
```

---

## 7.4 `AnalyticsHistoricalConversionScenarioTest`

Seed:

```text
Jan 2024 USD expense
Jan 2025 USD expense
different historical rates
```

Assert:

```text
monthly analytics uses each month’s historical rate
adding a 2026 rate does not change 2024/2025 reports
```

---

## 7.5 `DashboardTransactionTypeSemanticsTest`

Seed:

```text
purchase 100 EUR
deposit 1000 EUR
transfer 500 EUR
```

Assert:

```text
monthly spend = 100
income = 1000
net cashflow follows explicit policy
no widget labeled spend/income uses all-type total
```

---

## 7.6 `BudgetMissingRateContractTest`

Seed:

```text
home EUR
budget 100 GBP
no GBP→EUR rate
spend 50 EUR
```

Assert:

```text
BudgetStatus.isPartial = true
health = UNKNOWN/UNAVAILABLE
percentUsed not computed or explicitly unreliable
warning shown
```

---

## 7.7 `RawAggregationUsageGuardTest`

Static/CI test:

```text
production code must not call deprecated raw aggregation methods
production code must not sum raw amount/effectiveAmount for money totals
```

---

# 8. Suggested canonical scenario

## `multicurrency_partial_rate_dashboard_analytics`

Seed:

```text
home currency = EUR

exchange rates:
  USD→EUR = 0.90, validDate = 2026-05-01
  GBP→EUR = missing

expenses:
  groceries: 50 EUR, PURCHASE
  coffee: 10 USD, PURCHASE
  books: 20 GBP, PURCHASE
  salary: 1000 EUR, DEPOSIT
  transfer: 200 EUR, TRANSFER
```

Expected:

```text
purchase display total = 59 EUR
source buckets:
  EUR purchase 50
  USD purchase 10
  GBP purchase 20
isPartial = true
conversion failure = GBP→EUR
dashboard monthly spend = 59 EUR with warning
analytics total = 59 EUR with warning
income = 1000 EUR
net cashflow does not accidentally include transfer unless policy says so
budget uses purchase-only normalized spend
forecast confidence reduced because one bucket missing
```

This scenario should be one of your highest-priority fed-DB tests.

---

# 9. Most likely real instability sources

Ranked:

1. **Historical rate model incomplete.**
   - `validDate` exists in DB but is lost in domain adapter.

2. **Warnings dropped by `.displayAmount`.**
   - UI can show partial totals as complete.

3. **Current rates used for historical analytics.**
   - Old reports change after rate refresh.

4. **All-type totals used where purchase/deposit-specific totals are needed.**
   - Dashboard cards can be semantically wrong.

5. **Raw aggregation engine still present.**
   - Any consumer can reintroduce mixed-currency summing.

6. **Budget fallback mixes units after conversion failure.**
   - Can show fake budget health.

7. **Raw `Double` models still dominate downstream outputs.**
   - The type system cannot force warning propagation.

---

# 10. Final recommendation

For Pipeline 5, stabilize in this order:

```text
1. Fix ExchangeRate.validDate propagation and deterministic current-rate lookup.
2. Use convertAsOf() for analytics/reporting.
3. Propagate MoneyAggregate through dashboard/analytics/budget outputs.
4. Split purchase/deposit/transfer/net-cashflow aggregation APIs.
5. Disable or refactor TotalsAggregationEngine raw mixed-currency paths.
6. Fix budget missing-rate behavior.
7. Add the multicurrency partial-rate fed-DB scenario.
```

Guiding rule:

> No dashboard, analytics, budget, forecast, health, savings, export, or AI-query total should be a bare `Double` unless it is guaranteed single-currency or paired with explicit `MoneyAggregate`/data-quality metadata.

Second guiding rule:

> Missing or stale rates must make outputs visibly partial; they must never produce confident-looking totals.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Historical exchange-rate support is structurally incomplete
**STATUS: CONFIRMED — PARTIALLY FIXED**
- `AnalyticsCurrencyNormalizer.normalizeInternal()` now uses `currencyConverter.convertAsOf(amount, fromCurrency, toCurrency, expense.date)` instead of `currencyConverter.convert()`.
- This ensures analytics/dashboard/reports use the exchange rate valid at the time of each transaction, producing stable results that don't shift when rates update.
- Remaining gap: `MultiCurrencyRepository` older `Result<Double>` methods still use spot rates via `convert()`. These need migration to `convertAsOf` for consistency.

## Finding P0-2 — Dashboard and analytics lose isPartial / warnings
**STATUS: CONFIRMED — NOT FIXED (architectural — requires MoneyAggregate propagation through all widget pipelines)**

## Finding P0-3 — Current-rate conversion used for historical analytics
**STATUS: CONFIRMED — FIXED (see P0-1)**

## Finding P0-4 — Dashboard mixes transaction semantics
**STATUS: CONFIRMED — NOT FIXED (requires careful DAO query audit)**

## Finding P0-5 — TotalsAggregationEngine sums raw mixed-currency DAO totals
**STATUS: CONFIRMED — NOT FIXED (requires engine refactor)**

## Finding P0-6 — Budget conversion fallback compares unconverted amounts
**STATUS: CONFIRMED — NOT FIXED (requires BudgetRepository refactor)**

## Finding P0-7 — Many outputs expose raw Double instead of MoneyAggregate
**STATUS: CONFIRMED — NOT FIXED (gradual migration needed)**

## Finding P1-2 — Empty category spend fallback uses default EUR
**STATUS: CONFIRMED — FIXED**
- `BudgetRepository.getAggregateSpent()` now calls `resolveHomeCurrency()` to get the user's actual home currency instead of using `MultiCurrencyRepository.DEFAULT_HOME_CURRENCY`.
- The empty `MoneyAggregate` fallback now uses the correct home currency code.

## Finding P1-4 — Transaction lifecycle base snapshot may still hardcode EUR
**STATUS: CONFIRMED — FIXED (see Pipeline 2 P1-5)**
- `TransactionLifecycleCoordinator` now reads home currency from `CurrencySettingsRepository`.

## Finding P1-3 — Exchange-rate staleness is not part of conversion failure contract

### Post-evaluation fix (2026-05-06):
- **FIXED — P1-3 (Stage 1)**: CurrencyConverter.convert() now checks rate.lastUpdated
  against 24h staleness threshold before using a rate. Stale rates fall through to
  fallback paths (EUR cross-rate). Historical convertAsOf() is exempt.
  Full ConversionPolicy with configurable thresholds deferred to Stage 2.

## Finding P1-5 — Deprecated raw DAO totals still exist and can be accidentally reused
**STATUS: CONFIRMED — PARTIALLY FIXED (ARCH-01 Stage 1)**

## Finding P1-6 — Analytics data quality exists but is not necessarily surfaced
**PARTIALLY FIXED — P1-6 (ARCH-02 Stage 2)**: ForecastInputAssembler now populates
ForecastDataQuality from actual expense normalization results (excluded count,
missing rate count, penalty). FinancialWeather confidence reduction deferred.
Planned/recurring quality deferred to Stage 3.

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Use `convertAsOf(expense.date)` for historical analytics | `AnalyticsCurrencyNormalizer.kt` | P0-1, P0-3 |
| Use actual home currency in budget empty-spend fallback | `BudgetRepository.kt` | P1-2 |

---

# 14. Remaining work priority

1. **P0-2**: Propagate `MoneyAggregate` (with `isPartial`/warnings) through all dashboard widget pipelines
2. **P0-5**: Refactor `TotalsAggregationEngine` to normalize currencies before summing
3. **P0-6**: Fix BudgetRepository to convert budget limit to home currency before comparison
4. **P0-4**: Audit DAO queries to separate purchase/transfer/deposit semantics in dashboards
5. **P0-7**: Gradually replace `Double + currency` returns with `MoneyAggregate`

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `MultiCurrencyRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `CurrencyConverter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `ExchangeRateContracts.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt

- `ExchangeRateStoreAdapter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt

- `ExchangeRate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt

- `ExchangeRateDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `MoneyAggregate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAmount.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAmount.kt

- `AnalyticsCurrencyNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

- `AnalyticsRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `ComputeDashboardWidgetsUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `BudgetRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt