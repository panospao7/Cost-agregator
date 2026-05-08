# Evaluation — engine fix commits

Reviewed:

```text
6bdcf39
db277f0
abe4a7c
622b4a8
24c4de1
```

Review type: static GitHub review, not local Gradle/test execution.

## Verdict

This is a **positive engine-stabilization start**, especially for:

```text
GPS/privacy gate in LocationResolver
Cloud query redaction
warranty AI privacy gate
subscription recordedAt
warranty timestamps
MoneyAggregate introduction
some tax/investment/subscription aggregate paths
SharedExpenseBudgetOffsetEngine convertAsOf
speech recognizer destroy()
```

But I would **not mark the engine pass complete**.

Main concerns:

```text
1. Many tracker items were moved to TODO ONLY, not fixed.
2. New MoneyAggregate methods often skip conversion when there is only one non-home currency.
3. Several new DAO aggregate queries are missing transaction-type / isNotMine filters.
4. Some “fixed” location/map and NLP items are still only partially fixed.
5. Shared budget offsets still drop conversion failures without returning partial state.
6. Speech destroy exists but is not in the interface and is not wired to ViewModel lifecycle.
```

So the current status is:

```text
Engine quick wins: good
MoneyAggregate foundation: good but incomplete
Tracker wording: too optimistic
Production readiness: not yet
```

---

# 1. Commit `6bdcf39`

## Good

This commit adds useful real fixes and annotations:

```text
GPS privacy gate in LocationResolver
Warranty AI extraction privacy gate
Cloud query redaction marked as fixed
SharedExpenseManager runBlocking removed
autoCreate=false placeholder/category learning guard work
```

The LocationResolver change is especially good:

```text
PrivacyGate.DEVICE_GPS_LOCATION checked before locationProvider.getLastKnownLocation()
PrivacyGate.EXTERNAL_GEOCODING checked before geocoding
PrivacyGate.OVERPASS_API checked before nearby POI call
```

## Concern

A large part of the commit is TODO-only comments. That is fine as tracking, but not as “resolved.”

Examples still TODO-only:

```text
group hard delete lifecycle
group linked-expense normalization bypass
merchant alias conflict
investment DAO aggregate fee mismatch
```

Recommendation:

```text
Use "documented / accepted debt", not "fixed", for TODO-only items.
```

---

# 2. Commit `db277f0`

## Good

Some useful fixes landed across engine files:

```text
warranty timestamps
subscription recordedAt paths
merchant/category normalization cleanup
speech recognizer lifecycle work
tax/investment TODO classification
```

## Major concern

The tracker says:

```text
all 105 issues resolved
```

but the final tracker status is closer to:

```text
22 fixed with code
48 TODO-only
35 deferred
```

That is not “resolved” in the engineering sense. It is:

```text
22 fixed
83 intentionally not fixed yet
```

Recommendation:

```text
Rename tracker language:
“All 105 issues triaged”
not:
“All 105 issues resolved”
```

This matters because future agents may assume TODO-only P0 items are safe.

---

# 3. Commit `abe4a7c` — MoneyAggregate adoption

## Good

This is the most important commit in the batch.

It adds MoneyAggregate paths for:

```text
Warranty protected value
Subscription monthly total
Investment portfolio summary
Tax deductible/income aggregates
Analytics spending summary
Location merchant stats
```

This is the right direction. It starts replacing raw `Double` totals with:

```text
display amount
display currency
source buckets
conversion failures
partial flag
```

## Serious issue A — single non-home currency is not converted

Several new aggregate methods do:

```kotlin
if (sourceBuckets.size == 1) {
    return MoneyAggregate.singleCurrency(...)
}
```

This skips conversion even when:

```text
homeCurrency = EUR
only bucket = USD
```

Affected examples:

```text
WarrantyTrackerRepository.getTotalProtectedValueAggregate()
SubscriptionManagerEngine.getTotalMonthlySubscriptionCostAggregate()
InvestmentTracker.getPortfolioSummaryAggregate()
TaxEstimator.buildDeductibleAggregate()
TaxEstimator.buildIncomeAggregate()
```

Impact:

```text
A single USD warranty/subscription/investment/tax bucket is returned as USD,
even though the method claims to convert to home currency.
```

Fix:

```text
Always convert to home currency unless bucket.currency == homeCurrency.
```

Use the same pattern as `MultiCurrencyRepository.aggregateToMoneyAggregate()`.

Priority: P1/P0 for tax/budget-facing calculations.

---

## Serious issue B — stale-rate failures are mapped as missing-rate

The new manual mappings do:

```kotlin
reason = FailureReason.MISSING_RATE
```

for every `FailedConversion`.

But `CurrencyConverter` now distinguishes:

```text
MISSING_RATE
STALE_RATE
```

Use:

```kotlin
failed.toConversionFailure()
```

instead of manual mapping.

Affected:

```text
WarrantyTrackerRepository
SubscriptionManagerEngine
InvestmentTracker
TaxEstimator
```

Priority: P1.

---

## Serious issue C — new DAO query for business expenses misses spending filter

`ExpenseDao.getBusinessExpensesBetweenByCurrency()` currently appears to filter:

```sql
WHERE isBusinessExpense = 1
AND date >= :startDate
AND date < :endDate
```

but it does **not** include:

```sql
AND ${SPENDING_TYPE_SQL}
```

The old raw method did include spending type.

Impact:

```text
business deposits/transfers/withdrawals flagged business can enter deductible tax totals
```

Fix:

```sql
WHERE isBusinessExpense = 1
AND ${SPENDING_TYPE_SQL}
AND date >= :startDate
AND date < :endDate
```

Priority: P0/P1 for tax correctness.

---

## Serious issue D — located merchant aggregate query includes non-spending / not-mine

`getLocatedMerchantTotalsByCurrency()` lacks filters from the deprecated old query:

```sql
${SPENDING_TYPE_SQL}
isNotMine = 0
merchantKey IS NOT NULL
```

Impact:

```text
location merchant totals can include deposits/transfers/not-mine rows
```

Also it selects `merchant` while grouping by `merchantKey,currency`, which can return arbitrary merchant display text. Use `MIN(merchant)`.

Fix:

```sql
SELECT merchantKey,
       MIN(merchant) AS merchant,
       UPPER(COALESCE(currency, 'EUR')) AS currency,
       SUM(...) AS total,
       COUNT(*) AS txCount
FROM expenses
WHERE latitude IS NOT NULL
  AND longitude IS NOT NULL
  AND ${SPENDING_TYPE_SQL}
  AND isNotMine = 0
  AND merchantKey IS NOT NULL
GROUP BY merchantKey, UPPER(COALESCE(currency, 'EUR'))
```

Priority: P1.

---

## Serious issue E — `AnalyticsRepository.getLocationSpendSummary()` still raw-sums rows

It groups by merchant, then does:

```kotlin
val totalSpend = rows.sumOf { it.total }
val primaryCurrency = rows.first().currency
MoneyAggregate.singleCurrency(totalSpend, primaryCurrency)
```

That still raw-sums mixed currencies per merchant.

Fix:

```text
Convert per merchant rows with MoneyAggregate / CurrencyConverter,
or return per-currency buckets without display total.
```

Priority: P1.

---

# 4. Commit `622b4a8`

## Good

Good small hardening:

```text
MileageTrackingDao.getTotalDeductionWithFallback()
CASE fallback for NULL calculatedDeduction
business trip filter added
InvestmentTracker transactionCount fixed from always-1 to holdings.size
MoneyAggregate KDoc clarified
Time period KDocs clarified
```

## Concern

Some items are still only TODO:

```text
AccountantReportPdfExporter filingCurrency
SharedExpenseBudgetOffsetEngine partial conversion metadata
Subscription VM fixed-millis next-date
```

Fine, but don’t mark them as fixed.

---

# 5. Commit `24c4de1`

## Good

This commit improves real behavior:

```text
CurrencyConverter injected into warranty/subscription/investment/tax engines
SharedExpenseBudgetOffsetEngine uses convertAsOf
ExecuteFinancialQueryUseCase largest query attempts normalization
AndroidSpeechInputGateway.destroy()
Budget/Weather convertAsOf TODOs documented
```

## Remaining issue A — SharedExpenseBudgetOffsetEngine still drops failures

It now uses:

```kotlin
currencyConverter.convertAsOf(...)
```

Good.

But on failure it only logs:

```text
conversion failed
```

and omits that amount from totals.

Returned `BudgetSpendBreakdown` has no:

```text
isPartial
conversionWarnings
failedConversionCount
sourceBuckets
```

Impact:

```text
Budget may be lower than reality without UI/model knowing it is partial.
```

Fix:

```kotlin
data class BudgetSpendBreakdown(
    ...
    val isPartial: Boolean,
    val conversionWarnings: List<String>,
    val failedConversionCount: Int
)
```

Priority: P1.

---

## Remaining issue B — Assistant “largest” still falls back to raw amount

`ExecuteFinancialQueryUseCase.executeLargest()` converts mixed-currency rows, but if conversion fails:

```kotlin
?: expense.effectiveAmount
```

That reintroduces raw mixed-currency comparison.

Fix:

```text
exclude failed-conversion rows and mark result partial,
or return clarification/warning.
```

Priority: P1.

---

## Remaining issue C — category/merchant breakdown sorting still unsafe

For each category/merchant group:

```kotlin
if (byCurrency.size == 1) byCurrency.first().first
else convertMultiple(...).total
```

If category A is all JPY and category B is all USD, each group has one currency, so sort keys are raw cross-currency numbers.

Fix:

```text
always convert group totals to home currency for sorting,
unless group currency == homeCurrency.
```

Priority: P1.

---

## Remaining issue D — amount filters still raw

Assistant list/count paths still pass:

```kotlin
minAmount
maxAmount
```

directly to repository filters. These are currency-unsafe.

Fix:

```text
amount filters need explicit currency or normalized comparison.
```

Priority: P1/P2.

---

## Remaining issue E — speech destroy is not part of interface or wired

`AndroidSpeechInputGateway.destroy()` exists, good.

But:

```text
SpeechInputGateway interface has no destroy()
ViewModel onCleared does not call it
```

So lifecycle leak risk is reduced only if callers know concrete type.

Fix:

```kotlin
interface SpeechInputGateway {
    ...
    fun destroy()
}
```

Then call from relevant ViewModel `onCleared()`.

Priority: P1 if voice is exposed.

---

## Remaining issue F — map UI still has unresolved engine-facing problems

Even after LocationResolver GPS gate, `SpendingMapViewModel` still:

```text
fetches device location immediately when Android permission is granted
does not check app-level DEVICE_GPS_LOCATION privacy gate
uses e.amount for marker conversion, not e.effectiveAmount
falls back to raw amount when conversion fails
uses <= end date instead of half-open <
passes LocatedExpense without currency
heatmap still raw-sums effectiveAmount
insights include all transaction types
```

Some of this is UI-6, but it is also engine-data correctness.

Priority: P1.

---

# 6. MoneyAggregate foundation review

## Good foundation

`MoneyAggregate` is the right abstraction.

Good fields:

```text
displayAmount
displayCurrency
sourceBuckets
conversionFailures
isPartial
warningMessage
```

## Needs hardening

### Problem 1 — `failedTransactionCount` is misleading

Current:

```kotlin
failedTransactionCount = conversionFailures.size
```

That is bucket count, not transaction count.

Either:

```text
rename it to failedBucketCount only
```

or add:

```kotlin
transactionCount: Int
```

to `ConversionFailure`.

### Problem 2 — warning text says transactions but means buckets

Current warning style:

```text
Total excludes N transaction(s)
```

But N is conversion-failure bucket count.

Fix:

```text
“Total excludes N currency bucket(s)”
```

or add real transaction count.

### Problem 3 — empty aggregate often defaults to EUR

Several engines return:

```kotlin
MoneyAggregate.empty(CurrencyCode.EUR)
```

instead of user home currency.

Fix:

```text
resolve home currency before empty return
```

---

# 7. Tracker accuracy

The tracker currently says:

```text
All 105 issues have been addressed:
22 fixed
48 TODO-only
35 deferred
```

I strongly recommend changing wording.

Better:

```text
All 105 issues have been triaged:
22 fixed with code
48 documented as TODO-only
35 deferred for design/migration
```

Because `TODO ONLY` includes real P0/P1 bugs:

```text
return-window refund currency
bill negotiation monthly mismatch
location insights non-spending
map amount normalization
legacy NL merchant extraction
legacy NL parsed filters ignored
business report currency
group settlement/mixed currency
MoneyAmount Double/NaN
```

Those are not resolved.

---

# 8. Highest-priority follow-up fixes

## PR 1 — common MoneyAggregate converter helper

Create one helper:

```kotlin
MoneyAggregateBuilder.fromBuckets(
    buckets,
    homeCurrency,
    currencyConverter
)
```

Rules:

```text
empty → home currency
same as home → no conversion
single non-home → convert to home
mixed → convert each bucket
failureType STALE_RATE → FailureReason.RATE_STALE
```

Then use it in:

```text
WarrantyTrackerRepository
SubscriptionManagerEngine
InvestmentTracker
TaxEstimator
Analytics location summaries
```

This fixes many problems at once.

---

## PR 2 — fix aggregate DAO filters

Fix:

```text
getBusinessExpensesBetweenByCurrency: add SPENDING_TYPE_SQL
getLocatedMerchantTotalsByCurrency: add SPENDING_TYPE_SQL, isNotMine=0, merchantKey not null, MIN(merchant)
```

Add tests.

---

## PR 3 — SharedExpenseBudgetOffsetEngine partial result

Add:

```text
isPartial
conversionWarnings
failedConversionCount
```

to `BudgetSpendBreakdown`.

---

## PR 4 — map/location normalization pass

Fix:

```text
SpendingMapViewModel app-level GPS privacy gate
marker uses effectiveAmount
no raw fallback as home currency
half-open date end
pass currency to LocatedExpense
spending-only insights
```

---

## PR 5 — assistant query money correctness

Fix:

```text
largest query excludes failed conversions / warns partial
category/merchant sort converts all groups to home
amount filters become currency-aware or marked native
```

---

## PR 6 — speech lifecycle

Fix:

```text
SpeechInputGateway.destroy()
ViewModel.onCleared()
partial result handling later
```

---

# 9. Tests to add now

```text
MoneyAggregateSingleNonHomeConvertsToHomeTest
MoneyAggregateStaleRateFailureReasonTest
BusinessExpensesByCurrencySpendingOnlyTest
LocatedMerchantTotalsSpendingOnlyTest
WarrantyProtectedValueAggregateHomeCurrencyTest
SubscriptionMonthlyAggregateHomeCurrencyTest
InvestmentPortfolioAggregateHomeCurrencyTest
TaxDeductibleAggregateSpendingOnlyTest
SharedBudgetOffsetPartialConversionTest
AssistantLargestMissingRateDoesNotRawFallbackTest
MapMarkerMissingRateDoesNotRawFallbackTest
SpeechGatewayDestroyOnClearedTest
```

Most important:

```text
MoneyAggregateSingleNonHomeConvertsToHomeTest
BusinessExpensesByCurrencySpendingOnlyTest
LocatedMerchantTotalsSpendingOnlyTest
```

---

# 10. Overall assessment

This batch is a **good start** and shows the right direction.

But the engine pass should not be considered done yet.

Accurate status:

```text
Engine quick wins: partially complete
MoneyAggregate migration: started, needs consistency hardening
Privacy/redaction: improved for query + location/warranty gates
Analytics/location/NLP: still partial
Tracker: triaged, not resolved
```

I would continue with the common MoneyAggregate helper and DAO-filter tests before moving to more engine fixes.

---

# Sources

Commits:

- https://github.com/panospao7/Cost-agregator/commit/6bdcf39a86033321e50618bdf1f847b274c91961
- https://github.com/panospao7/Cost-agregator/commit/db277f086e0478ee2e9dc7d57d1ef8402fd52a23
- https://github.com/panospao7/Cost-agregator/commit/abe4a7c50db2e9656c9b2c1463f25227e65de1d1
- https://github.com/panospao7/Cost-agregator/commit/622b4a8c364d30d45618ef658a38fdf7a06a5715
- https://github.com/panospao7/Cost-agregator/commit/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88

Key files:

- `ENGINE_ISSUES_MASTER_TRACKER.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/docs/analyses%20and%20debug%20master/ENGINE_ISSUES_MASTER_TRACKER.md

- `MoneyAggregate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyMappers.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyMappers.kt

- `CurrencyConverter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `WarrantyTrackerRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

- `SubscriptionManagerEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `InvestmentTracker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt

- `TaxEstimator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `AnalyticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `SharedExpenseBudgetOffsetEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

- `SpendingMapViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `CloudQueryInterpretationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt

- `AndroidSpeechInputGateway.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/24c4de164222dd38f15d38bc9a6ff6d8cbb6cd88/app/src/main/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt