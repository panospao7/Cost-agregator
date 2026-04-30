# Currency & Exchange Deep Analysis — `master-refactor`

## Executive verdict

The currency layer has a solid starting shape:

- `CurrencyConverter`
- `ExchangeRateStore`
- `ExchangeRateDao`
- `CurrencySettingsRepository`
- ECB refresh path
- per-currency aggregate DAO helpers

But the current architecture is not yet safe as the app-wide money foundation.

The biggest problem is this:

> The app stores transaction currency, but does not store a normalized/base amount or historical conversion snapshot per transaction.

That means financial totals can only be correct if every consumer remembers to group by currency and convert at query time. Several current paths still raw-sum `effectiveAmount`, so mixed-currency totals can silently become wrong.

---

## Highest-priority findings

## 1. No transaction-level conversion snapshot

### Where

- `Expense.kt`
- `CurrencyConverter.kt`
- `ExchangeRate.kt`

`Expense` has:

- `amount`
- `currency`

But it does not have:

- normalized/base amount
- base currency at booking time
- exchange rate used
- exchange-rate date
- conversion source
- conversion confidence/status

`ExchangeRate` stores only latest pair state by `fromCurrency + toCurrency`.

### Why this is critical

If a user records:

- `$100` on January 5
- `$100` on March 5

the correct EUR totals should use the historical rate appropriate to each transaction date, or at least the rate captured when the transaction was created.

Currently, `CurrencyConverter.convert()` has no `asOfDate` parameter and uses whatever rate is currently stored.

### Impact

Old dashboard numbers can change after exchange rates refresh.

Examples:

- January dashboard total changes in April.
- Budget history changes after rate update.
- Tax/business reports become non-reproducible.
- Shared-expense settlements can drift over time.
- Receipt/notification totals may no longer match imported bank records.

### Severity

**Critical**

### Recommended fix

Add a money snapshot model.

Minimum fields on money-bearing records:

```kotlin
originalAmount
originalCurrency
baseAmount
baseCurrency
exchangeRateUsed
exchangeRateTimestamp
exchangeRateSource
conversionStatus
```

For `Expense`, either:

1. migrate existing `amount` / `currency` to mean original amount/currency and add normalized fields, or
2. introduce an embedded `MoneySnapshot`.

All reporting/budgeting should sum `baseAmount`, not raw `amount`.

---

## 2. Exchange rates are latest-state only, not historical

### Where

- `ExchangeRate.kt`
- `ExchangeRateDao.kt`
- `CurrencyConverter.kt`

`ExchangeRate` has a unique index on:

```kotlin
fromCurrency, toCurrency
```

`ExchangeRateDao.insertOrUpdate()` uses `OnConflictStrategy.REPLACE`.

### Problem

Each pair only has one current row. Refreshing rates overwrites the previous rate.

### Impact

You cannot answer:

> What rate was valid on the transaction date?

You can only answer:

> What is the latest cached rate for this pair?

That is not enough for stable financial history.

### Severity

**Critical**

### Recommended fix

Change rate storage to historical/batched rates.

Suggested schema:

```kotlin
ExchangeRate(
    fromCurrency,
    toCurrency,
    rate,
    validDate,
    fetchedAt,
    source
)
```

Unique index:

```kotlin
fromCurrency, toCurrency, validDate, source
```

Then add:

```kotlin
getRateAsOf(from, to, date)
```

For current UI conversion, latest rate is fine. For stored expenses and reports, use historical/snapshot rate.

---

## 3. DAO still exposes raw mixed-currency aggregate queries

### Where

- `ExpenseDao.kt`

Examples include:

- `getTotalSpentBetween`
- `getCategorySpentInPeriod`
- `getCategorySpentInPeriodFlow`
- `getCategorySpentTotalsInPeriod`
- merchant average queries

The file also contains newer grouped-by-currency helpers like:

- `getTotalSpentBetweenByCurrency`
- `getCategoryTotalsBetweenByCurrency`

That is good, but raw-sum methods still exist and are easy to call.

### Problem

Raw-sum query:

```sql
SUM(EFFECTIVE_AMOUNT_SQL)
```

does not group by currency.

So:

- `€50`
- `$50`
- `£50`

can become:

```text
150
```

with no meaningful currency.

### Impact

Any consumer still using raw aggregate methods can show corrupted totals.

Affected likely areas:

- dashboard
- budget status
- category totals
- reports
- merchant averages
- shared budget offset
- forecasting

### Severity

**Critical**

### Recommended fix

Create a hard rule:

> No cross-row aggregate may return `Double` money unless it is already in one declared currency.

Replace raw aggregate methods with either:

```kotlin
MoneyTotal(amount, currency)
```

or:

```kotlin
List<CurrencyBucket>
```

Then convert at the boundary.

Deprecate raw-sum methods with names like:

```kotlin
@Deprecated("Unsafe for multi-currency. Use ...ByCurrency")
```

---

## 4. `CurrencyConverter.convert()` has no date/context parameter

### Where

- `CurrencyConverter.kt`

Current method:

```kotlin
convert(amount, fromCurrency, toCurrency)
```

### Problem

It cannot distinguish between:

- booking-time conversion
- current display conversion
- historical report conversion
- future/planned forecast conversion

Those are different business meanings.

### Impact

The same method can be used incorrectly in multiple places.

### Recommended split

Use explicit APIs:

```kotlin
convertNow(amount, from, to)
convertAsOf(amount, from, to, timestamp)
createMoneySnapshot(amount, currency, baseCurrency, transactionDate)
```

For reports, never use `convertNow()` unless explicitly showing “converted using today’s rate.”

### Severity

**High**

---

## 5. `PlannedExpense` has no currency

### Where

- `PlannedExpense.kt`

Fields include:

- `description`
- `amount`
- `date`
- `categoryId`
- `isRecurring`
- `priority`

But no currency.

### Problem

Planned/future expenses are implicitly in some currency, probably home currency, but that is not encoded.

### Impact

Forecasts and recurring/bill planning can become wrong when:

- user has foreign subscriptions
- user travels
- imported planned payments are not in home currency
- home currency changes later

Example:

- Netflix plan stored as `15`
- Is that `15 EUR`, `15 USD`, or `15 GBP`?

### Severity

**High**

### Recommended fix

Add:

```kotlin
currency: String
baseAmount: Double?
baseCurrency: String?
exchangeRateUsed: Double?
conversionStatus: ConversionStatus
```

For future expenses, decide policy:

- use latest available rate and mark as estimated
- or store original currency only and convert dynamically in forecasts

But do not leave it implicit.

---

## 6. Home currency can be changed without re-normalizing stored financial data

### Where

- `CurrencySettingsRepository.kt`
- `CurrencySettingsRepositoryImpl.kt`

`setHomeCurrency(currencyCode)` writes the new currency setting.

### Problem

There is no visible app-wide migration/re-normalization path when home currency changes.

If existing expenses are normalized to the old home currency in the future, changing home currency will require recalculation.

Even now, dashboard/report consumers may interpret totals differently after the setting changes.

### Impact

Changing home currency from EUR to USD can make old totals ambiguous.

### Recommended fix

Separate:

- `transaction original currency`
- `accounting base currency`
- `display currency`

Changing display currency should not mutate stored accounting data.

If you allow changing accounting base currency, run an explicit migration/recalculation job.

### Severity

**High**

---

## 7. Currency validation is weak at repository/settings boundaries

### Where

- `CurrencySettingsRepositoryImpl.kt`
- `ExchangeRateStoreAdapter.kt`
- `ExchangeRateDao.kt`

`setHomeCurrency(currencyCode)` stores the raw string.

`ExchangeRateStoreAdapter` passes values through directly.

`CurrencyConverter` uppercases input, but the lower-level store/DAO do not enforce that themselves.

### Impact

Possible invalid values:

- `"eur"`
- `" EUR "`
- `"EURO"`
- `""`
- `"XYZ"`

could enter settings or rate storage if a caller bypasses `CurrencyConverter`.

### Recommended fix

Create a single currency-code value object:

```kotlin
@JvmInline
value class CurrencyCode private constructor(val value: String)
```

Factory:

```kotlin
CurrencyCode.parseOrNull(raw)
```

Enforce:

- trim
- uppercase
- ISO/support allowlist
- no blank values

Use it in:

- settings
- rate store
- converter
- parser outputs
- receipt AI outputs
- planned expenses
- budgets

### Severity

**High**

---

## 8. Rate refresh can mark rates as updated even if zero rates are stored

### Where

- `CurrencyRatesRepositoryImpl.kt`

The refresh snippet stores `rates`, then calls:

```kotlin
currencySettingsRepository.setLastRateUpdate(System.currentTimeMillis())
rates.size
```

### Problem

If parsing succeeds structurally but produces an empty `rates` list, `lastRateUpdate` can still be set to now.

### Impact

The app may believe rates are fresh even though no usable rates exist.

### Recommended fix

Only update `lastRateUpdate` when:

```kotlin
rates.isNotEmpty()
```

Better:

- verify minimum expected currency coverage
- verify direct home-currency rates exist
- verify required supported currencies exist
- otherwise fail refresh and keep previous timestamp

### Severity

**High**

---

## 9. `lastRateUpdate` is stored separately from actual rate rows

### Where

- `CurrencySettingsRepositoryImpl.kt`
- `ExchangeRateDao.kt`

`lastRateUpdate` lives in DataStore. Rates live in Room.

### Problem

These can drift.

Examples:

1. rates inserted but DataStore update fails
2. DataStore says fresh but rates table was cleared
3. old rates are cleaned up but `lastRateUpdate` remains fresh
4. restore/backup restores one but not the other

### Impact

`areRatesStale()` may return false while no rates are actually available.

### Recommended fix

Use the database as source of truth for rate freshness:

```kotlin
SELECT MAX(lastUpdated) FROM exchange_rates
```

Or store refresh batch metadata in Room:

```kotlin
ExchangeRateRefreshBatch(
    id,
    source,
    fetchedAt,
    homeCurrency,
    rateCount,
    status
)
```

Then staleness comes from successful refresh batch state.

### Severity

**High**

---

## 10. `getAllRatesForBase(baseCurrency)` naming is confusing

### Where

- `ExchangeRateDao.kt`
- `CurrencyConverter.kt`
- `ExchangeRateStoreAdapter.kt`

DAO implementation:

```sql
SELECT * FROM exchange_rates WHERE toCurrency = :baseCurrency
```

### Problem

The name “rates for base” can be interpreted as:

- from base to all currencies, or
- all currencies to base

The query returns rows where the target is the base currency.

### Impact

A UI may show inverse values or incomplete rates if it assumes `fromCurrency = baseCurrency`.

### Recommended fix

Rename explicitly:

```kotlin
getRatesToCurrency(targetCurrency)
getRatesFromCurrency(sourceCurrency)
```

If the UI wants “1 EUR = X USD”, it should use from-currency queries.

### Severity

**Medium / High depending on UI usage**

---

## 11. Money uses `Double`

### Where

- `Expense.amount`
- `ExchangeRate.rate`
- `CurrencyConverter`
- `PlannedExpense.amount`
- DAO aggregates

### Problem

`Double` is risky for financial amounts.

It can create:

- rounding drift
- cent mismatches
- split/settlement inconsistencies
- display differences
- equality/dedupe instability

### Recommended fix

At minimum:

- store money in minor units as `Long` where possible
- keep exchange rates as `BigDecimal` or scaled decimal
- use a `Money` value object for domain math

Example:

```kotlin
data class Money(
    val minor: Long,
    val currency: CurrencyCode
)
```

For rates:

```kotlin
data class ExchangeRateValue(
    val numerator: BigDecimal
)
```

### Severity

**Medium / High**

---

## 12. Formatting is too simple for real currency display

### Where

- `CurrencyConverter.formatAmount()`

Current behavior uses:

```kotlin
"$symbol${String.format("%.2f", amount)}"
```

### Problems

- JPY normally has zero decimal places.
- Some currencies have different minor-unit rules.
- Locale-dependent decimal separator may combine oddly with manual symbol prefix.
- Negative amounts/refunds may display awkwardly.
- Symbol ambiguity: `$` can mean USD, CAD, AUD.

### Recommended fix

Use `NumberFormat.getCurrencyInstance(locale)` where possible.

Or implement app-specific display:

```kotlin
formatMoney(Money, displayMode = SYMBOL_WITH_CODE)
```

Example:

```text
€12.34
USD 12.34
JPY 1200
```

### Severity

**Medium**

---

## 13. Supported currency list contains HRK

### Where

- `SupportedCurrency`

`HRK` appears in the supported currencies list.

### Problem

Croatia adopted EUR in 2023. HRK may still be useful for historical records, but should probably be marked as legacy.

### Impact

If users can select HRK for new expenses, that may be wrong.

### Recommended fix

Add currency metadata:

```kotlin
isActive: Boolean
validFrom
validTo
```

Then allow HRK for historical import, but hide it from new-entry defaults unless explicitly enabled.

### Severity

**Low / Medium**

---

# Strong parts

## 1. Strict multi-conversion aggregate exists

`CurrencyConverter.convertMultiple()` returns:

- total
- target currency
- failed conversions

and explicitly does not add failed conversions into the total.

This is good. Keep this behavior.

## 2. DAO has grouped-by-currency helpers

`ExpenseDao` includes grouped helpers like:

- total by currency
- category by currency
- merchant by currency

This is the right direction.

The issue is migration completeness: unsafe raw aggregate paths still exist.

## 3. Exchange-rate XML parser is hardened

`CurrencyRatesRepositoryImpl` uses a secure XML parser setup with external entities disabled.

Good security posture.

## 4. DI abstraction is clean

`CurrencyModule` binds:

- `CurrencySettingsRepository`
- `CurrencyRatesRepository`
- `ExchangeRateStore`

This is good architecture. Consumers can depend on domain interfaces.

---

# Cross-pipeline risk map

## Dashboard / analytics

High risk if using raw `SUM(EFFECTIVE_AMOUNT_SQL)`.

Correct path:

```text
DAO grouped by currency → converter → base/display money total
```

Wrong path:

```text
DAO raw sum → display as home currency
```

## Budgets

Budgets need an explicit budget currency.

Possible model:

```kotlin
Budget(
    amount,
    currency,
    period,
    categoryId
)
```

Then expense totals must be converted into budget currency before comparison.

## Shared expenses

Shared expenses need clear semantics:

- group expense original currency
- payer paid currency
- participant share currency
- settlement currency
- budget-impact currency

Do not raw-sum group shares with personal expenses unless converted.

## Receipts / OCR / AI

Receipt extraction must output:

- amount
- currency
- currency confidence/source

If missing, do not silently default to EUR unless the merchant/source context makes that safe.

## Planned / recurring / subscriptions

`PlannedExpense` currently lacks currency. This should be fixed before recurring/subscription analysis if those features use planned expenses.

## Bank imports

Bank imports often include both:

- transaction currency
- account currency
- exchange rate / converted amount

The app should preserve both if available.

---

# Recommended fix order

## PR 1 — Add canonical money model

Create:

```kotlin
CurrencyCode
Money
MoneySnapshot
ConversionStatus
```

Use these in domain first, then migrate entities.

## PR 2 — Add transaction conversion snapshots

Add to `Expense`:

```kotlin
baseAmount
baseCurrency
exchangeRateUsed
exchangeRateTimestamp
conversionStatus
```

Backfill existing rows:

- EUR rows → baseAmount = amount, rate = 1
- non-EUR rows → conversionStatus = MISSING_RATE until converted

## PR 3 — Add historical exchange-rate storage

Change exchange-rate uniqueness from pair-only to pair + valid date/source.

Add:

```kotlin
getRateAsOf(from, to, timestamp)
```

## PR 4 — Deprecate raw aggregate DAO methods

Mark unsafe raw sums as deprecated.

Move consumers to grouped-by-currency or normalized-base fields.

## PR 5 — Fix planned expenses currency

Add currency to `PlannedExpense`.

If recurring/subscription uses another entity, apply the same rule there.

## PR 6 — Make home/display/accounting currency explicit

Separate:

- accounting base currency
- display currency
- original transaction currency

Changing display currency should not rewrite transactions.

## PR 7 — Harden refresh freshness

Only set `lastRateUpdate` after non-empty validated rate insert.

Prefer Room refresh batch metadata over DataStore timestamp.

## PR 8 — Validation and formatting

Validate currency codes at all boundaries.

Replace manual formatting with currency-aware formatting.

---

# Regression tests to add

1. `€10 + $10` is not raw-summed as `20`.
2. Dashboard total uses converted base/display currency.
3. Category total grouped by currency converts correctly.
4. Budget in EUR compares correctly against USD expense.
5. Historical report does not change after latest rate update.
6. Expense stores conversion snapshot at creation.
7. Missing exchange rate marks conversion as failed, not 1:1.
8. `PlannedExpense` with USD appears correctly in forecast.
9. Changing display currency does not mutate stored original amount.
10. `setHomeCurrency("bad")` is rejected.
11. Empty rate refresh does not update `lastRateUpdate`.
12. Cleared rates make staleness/availability reflect missing rates.
13. JPY formatting does not force `.00`.
14. HRK is treated as legacy if intended.
15. `getRatesToCurrency` / `getRatesFromCurrency` semantics are tested separately.

---

# If you only fix three things

1. **Add normalized/base money snapshot to `Expense`.**
2. **Stop all raw mixed-currency SQL sums from feeding dashboard/budget/report totals.**
3. **Add currency to `PlannedExpense` and future/recurring money records.**

Those three will prevent most serious financial correctness bugs.

---

# Sources reviewed

- `CurrencyConverter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `CurrencyRatesRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyRatesRepository.kt

- `CurrencySettingsRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencySettingsRepository.kt

- `ExchangeRateContracts.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/currency/ExchangeRateContracts.kt

- `ExchangeRateStoreAdapter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt

- `CurrencyRatesRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/CurrencyRatesRepositoryImpl.kt

- `CurrencySettingsRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt

- `ExchangeRate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt

- `Expense.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `PlannedExpense.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `ExchangeRateDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `ExpenseDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `CurrencyModule.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/di/CurrencyModule.kt