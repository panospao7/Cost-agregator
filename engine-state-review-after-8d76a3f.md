# Engine Fix Evaluation — through `8d76a3f2`

Reviewed main engine-fix commits:

```text
8243fc07
33d31b01
ec36984d
68d24f3c
7483df14
c3d97815
0615408f
72d7f9fb
22098bde
a3aa46f9
15cb44b9
8d76a3f2
```

Review type: static GitHub/code review, not local Gradle execution.

## Executive verdict

This is a **strong engine-stabilization batch**.

You materially improved:

```text
MoneyAggregate foundation
MoneyAggregateBuilder consistency
DAO aggregate filters
ConvertedMoney semantics
GPS privacy gate
speech recognizer lifecycle
forecast actual-quality population
assistant model dataQuality surface
map date filtering and effectiveAmount usage
subscription creation validation
subscription baseline price history
investment add validation
warranty refund currency inference
manual warranty placeholder currency
```

But the engine layer is **not fully stable yet**.

The current state is closer to:

```text
Core transaction/receipt/backup/privacy pipelines: mostly stable
MoneyAggregate foundation: good, needs final polish
Map/location engine: still partial
Assistant/NL engine: still partial
Analytics canonical-input migration: mostly skeleton/TODO
Categorization/cache/merchant learning: mostly TODO
Groups lifecycle/currency/settlement: mostly TODO
Investment/subscription/warranty: partly real code, partly still non-atomic
Tax/business: mostly TODO/design debt
CI guards: planned/TODO, not enforcement yet
```

So: **good progress, no obvious broad pipeline regression, but there are still important engine fixes before “backend stable.”**

---

# 1. What improved significantly

## 1.1 MoneyAggregate foundation

Good additions:

```text
MoneyAggregateBuilder.fromBuckets()
single non-home currency now converts to home currency
stale-rate vs missing-rate mapping improved
transactionCount added to ConversionFailure
failedTransactionCount now sums transaction counts
contract tests added
```

This fixes one of the biggest systemic issues: raw mixed-currency totals.

## 1.2 DAO aggregate filters

Good:

```text
business aggregate query excludes non-spending
located merchant totals exclude not-mine / deposits / transfers / null merchantKey
DB-backed tests added
```

This reduces false totals in tax/location/business analytics.

## 1.3 ConvertedMoney

Good:

```text
identity conversion is successful
failed conversion preserves reason/message
```

This fixes a subtle but important semantic bug.

## 1.4 GPS privacy gate

Good:

```text
SpendingMapViewModel injects PrivacyGate
fetchDeviceLocation() checks DEVICE_GPS_LOCATION
denied GPS produces snackbar instead of provider call
```

This is a real privacy improvement.

## 1.5 Forecast actual quality

Good:

```text
ForecastInputAssembler now populates ForecastDataQuality for actual exclusions
excludedActualCount
confidencePenalty
isPartial
```

This is the right first slice. Planned/recurring quality can wait, but should remain open.

## 1.6 Warranty/subscription/investment

Good real-code fixes:

```text
return-window refundCurrency inferred from linked expense
manual warranty placeholder uses home currency
subscription validateAndCreate() added
subscription baseline history recordedAt set
subscription amount/currency/merchant validation added
investment addHolding validates quantity/price/currency
initial InvestmentValue snapshot recorded on add
```

These are meaningful improvements.

---

# 2. Important remaining issues

## P0/P1 — Map markers still can display failed conversion as home currency

`SpendingMapViewModel` now uses `effectiveAmount`, good.

But on conversion failure it still does:

```kotlin
Pair(e.effectiveAmount, false)
```

Then `SpendingMapScreen` displays:

```kotlin
CurrencyFormatter.format(marker.amount, homeCurrency)
```

And the screen does not use `marker.isConverted`.

So a failed USD→EUR conversion can still appear as:

```text
€100
```

when it is actually:

```text
$100
```

### Fix

Change marker model:

```kotlin
data class MapExpenseMarker(
    val displayAmount: Double,
    val displayCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val conversionStatus: ConversionStatus,
    val warning: String?
)
```

Rules:

```text
converted/home → display home currency
failed → display native currency and warning
```

Priority: high.

---

## P1 — Heatmap/insights normalization is still not implemented

You added `LocatedMoneyExpense`, good.

But `SpendingMapViewModel` still says:

```kotlin
// TODO use moneyExpenses with heatmapEngine.computeNormalized()
val heatmap = heatmapEngine.compute(heatmapExpenses)
val insights = insightsEngine.compute(spendingDomainExpenses)
```

And `SpendingHeatmapEngine.computeNormalized()` is a stub returning empty list.

`LocationInsightsEngine` still sums:

```kotlin
acc.totalSpend += expense.amount
```

So heatmap and insights still raw-sum amounts.

### Fix

Implement:

```text
SpendingHeatmapEngine.computeNormalized()
LocationInsightsEngine.computeNormalized()
```

Use only:

```text
HOME_CURRENCY
CONVERTED
```

Exclude failed conversions and return/report partial state.

---

## P1 — Assistant result `dataQuality` exists but is not populated

`FinancialQueryResult.Summary`, `Breakdown`, and `TransactionList` now have:

```kotlin
dataQuality: FinancialQueryDataQuality
```

Good.

But `ExecuteFinancialQueryUseCase` returns default dataQuality almost everywhere.

Example: `executeLargest()` counts failed conversions, excludes rows, then has TODO:

```text
Surface failedConversions in the return type
```

### Fix

Populate dataQuality for:

```text
largest
total
average
category breakdown
merchant breakdown
transaction list
count with amount filter
```

At minimum:

```kotlin
dataQuality = FinancialQueryDataQuality(
    isPartial = failedConversions > 0,
    excludedCount = failedConversions,
    warnings = listOf(...)
)
```

---

## P1 — Assistant amount filters are still raw

`ExecuteFinancialQueryUseCase` still passes:

```kotlin
minAmount = intent.filters.minAmount
maxAmount = intent.filters.maxAmount
```

to repository filters.

The comments correctly say this is not currency-aware.

### Fix

For now, do in-memory filtering after period/type/category/merchant narrowing:

```text
if amount filter has no currency → interpret as home currency
convert each row effectiveAmount to home currency
compare normalized amount
failed conversion → exclude and mark partial
```

Do not push this into SQL yet.

---

## P1 — Legacy Natural Language Search is still mostly TODO

The legacy NL engine still:

```text
lowercases query before merchant extraction
parses category/location filters but does not apply them
documents raw fallback on conversion failure
does broad date-bounded loading
```

Example:

```kotlin
val normalized = query.lowercase()
val merchants = extractMerchants(normalized)
```

But `extractMerchants()` expects capitalized merchant names.

### Fix

Minimum:

```text
extract merchants from original query
apply category filters or mark unsupported
hide/mark location filters if unsupported
replace raw amount filtering with same currency-aware helper as Assistant
```

Also `NaturalLanguageSearchViewModel` still has the old single-non-home bug:

```kotlin
if (amounts.size == 1) amounts.first().first
else convertMultiple(...)
```

If the only bucket is USD and home is EUR, it returns raw USD as “home total.”

Fix it via `MoneyAggregateBuilder`.

---

## P1 — NormalizedAnalyticsInput is only a skeleton

`NormalizedAnalyticsInput.kt` exists. Good.

But the actual analytics engines are not migrated yet.

The commit mostly added:

```text
data classes
TODOs
forecast actual-quality population
```

Still needed:

```text
AnalyticsInputAssembler
TotalsAggregationEngine migration
InsightsEngine migration
SpendingPersonalityClassifier migration
AdvancedAnalyticsEngine migration
daily bucket contract
confidence/data-quality propagation
```

This remains one of the bigger engine design tasks.

---

## P1 — MoneyAggregate companion/mappers still have older warning semantics

`MoneyAggregateBuilder` warning is now good:

```text
Total excludes X transaction(s) across Y currency bucket(s)
```

But `MoneyAggregate.partial()` still says:

```kotlin
"Total excludes ${failures.size} transaction(s)"
```

And `MultiConversionAggregate.toMoneyAggregate()` also uses failure count as transaction count.

### Fix

Make all constructors/mappers use:

```text
failedTransactionCount
failedBucketCount
```

or route everything through `MoneyAggregateBuilder`.

---

## P1 — Investment add is validated but not atomic

`addHolding()` does:

```text
insert investment
insert initial InvestmentValue
```

without transaction wrapping.

If the second insert fails, you can get:

```text
holding without initial value history
```

`updateInvestmentPrice()` still has explicit TODO:

```text
Wrap updatePrice + insert in database.withTransaction
```

### Fix

Inject database or repository transaction wrapper:

```kotlin
database.withTransaction {
    val id = investmentDao.insert(validated)
    investmentValueDao.insert(...)
}
```

Same for price update.

---

## P1 — Investment portfolio history still undercounts days

The history code groups values by day and sums only days with values.

It does not carry forward the latest known value for each holding across days.

So holdings disappear on days without price updates.

### Fix

For each day:

```text
for each holding:
  latest value <= dayEnd
  carry forward
sum portfolio value
```

---

## P1 — Raw Investment `PortfolioSummary` still exists beside aggregate

`getPortfolioSummary()` still raw-sums holdings into `PortfolioSummary`.

The aggregate is returned separately in another path, but any UI/engine still using `PortfolioSummary.totalValue` can show fake mixed-currency totals.

### Fix

Either:

```text
deprecate raw PortfolioSummary totals
```

or add:

```kotlin
PortfolioSummary.moneyAggregate: MoneyAggregate
```

and make raw totals internal only.

---

## P1 — Warranty return handling improved but still has edge cases

Good:

```text
refundCurrency inferred from linked expense
```

Remaining:

```text
markAsReturned now requires refundAmount: Double, no nullable no-refund return
fallback currency is EUR, not home currency
update is not obviously transactional with linked expense lookup
```

### Fix

Use:

```kotlin
refundAmount: Double? = null
```

and fallback:

```text
linked expense currency → home currency → explicit unknown/native
```

Avoid hardcoded EUR unless documented as legacy fallback.

---

## P1 — Subscription validation exists but must be enforced everywhere

`validateAndCreate()` is good.

But you should verify all creation paths use it:

```text
manual add
candidate accept
import/AI detection
tests/fakes
```

Also still needed:

```text
price update + price history atomic
candidate next-date tests
calendar recurrence tests
```

The commit says fixed day offsets replaced, but I did not see `nextOccurrence` via quick find in `SubscriptionManagerEngine`, so verify exact call sites.

---

## P1 — Categorization/merchant fixes are mostly TODO-only

`a3aa46f9` mainly added/verified TODOs for:

```text
MerchantNormalizer alias conflict
MerchantCategoryDao return type/conflict contract
CategorizationEngine cache invalidation
TransactionSideEffectDispatcher merchant stats
SemanticKeywordMatcher collision policy
```

These are not fixed yet.

### Remaining needed

```text
insert methods return Long conflict result
alias conflict detection
cache invalidation events
merchant stats update after committed expense
semantic collision policy
```

---

## P1 — Groups remain mostly TODO/design

`15cb44b9` lists:

```text
GroupLifecycleCoordinator TODO
single-currency group enforcement TODO
settlement policy TODO
hard delete lifecycle TODO
```

I did not find a real implemented coordinator file in the reviewed head.

So groups are still not engine-stable.

Needed:

```text
GroupLifecycleCoordinator
single-currency policy or real multi-currency settlement
recordSettlement()
archive vs hard delete contract
linked-expense ownership updates through TransactionLifecycleCoordinator
```

---

## P1/P2 — Tax/business remains mostly TODO/design

Still open:

```text
TaxSettingsRepository
selected country persistence
filing currency
fiscal year assumptions
BusinessReport MoneyAggregate
CSV formula safety
business/tax lifecycle updates
official estimate disclaimers later in UI
```

This is not release-core unless tax feature is visible.

---

## P2 — Guards are TODO, not CI protection yet

`PR-E23/E24` are listed as TODOs.

You still need actual scripts/tasks for:

```text
raw money aggregate guard
wall-clock/time guard
lifecycle bypass guard if not already wired
cloud provider redactor/PrivacyGate guard
```

Until then, regressions can re-enter.

---

# 3. Pipeline / architecture regression assessment

I did **not** see obvious broad regression to the fixed pipelines from these engine commits.

Good signs:

```text
DAO filter changes are safer than before
MoneyAggregate changes are mostly additive
GPS privacy gate improves privacy pipeline
ConvertedMoney semantics improve currency pipeline
Speech destroy is additive
Warranty/subscription/investment changes do not appear to touch transaction lifecycle core
```

But there are some localized regression risks:

## Risk 1 — Map marker conversion claim is incomplete

Commit message says no raw fallback, but actual screen still formats failed conversion amount with home currency. That is a correctness regression risk if users trust map values.

## Risk 2 — Investment add can create orphan value/holding state

Because insert holding + insert initial value are not transactional.

## Risk 3 — Warranty `markAsReturned()` signature changed

It now requires `refundAmount: Double`.

If callers previously passed no refund amount, this may break compile or force fake zero-refunds. Verify all callers.

## Risk 4 — Assistant/NL dataQuality fields may create false confidence

Adding fields with default empty values is safe structurally, but if UI starts displaying them before they are populated, it may imply “no issues.”

## Risk 5 — TODO-only commits may overstate fixed state

Several commits have titles like `PR-E15 to E24`, but many are TODO/comment tracking. That is okay only if the tracker says “triaged,” not “fixed.”

---

# 4. General engine state now

My estimate:

```text
Core lifecycle/receipt/backup/privacy pipelines: 80–90% stable
Money/currency primitive layer: 75–85% stable
Dashboard/budget analytics data layer: 65–75% stable
Forecast actual quality: 55–65% stable
Map/location engines: 50–60% stable
Assistant AI query engine: 50–60% stable
Legacy NL search: 35–45% stable
Subscription/warranty: 55–70% stable
Investment: 40–55% stable
Groups: 30–45% stable
Tax/business: 30–45% stable
Categorization/cache/merchant learning: 50–60% stable
CI regression guards: 25–40% stable
```

Overall backend/engine state:

```text
Good beta foundation, not yet production-stable.
```

The important part: the remaining issues are now more localized and tractable. Earlier the problems were systemic lifecycle/currency/privacy failures. Now they are mostly incomplete migrations and missing contract tests.

---

# 5. Recommended next fixes

## Next PR 1 — Finish map money correctness

Fix:

```text
MapExpenseMarker carries displayCurrency/originalCurrency/conversionStatus
SpendingMapScreen does not format failed conversion as home currency
computeNormalized() implemented
LocationInsightsEngine normalized variant implemented
partial conversion count surfaced
```

Tests:

```text
MapMarkerMissingRateShowsNativeCurrencyTest
HeatmapUsesNormalizedAmountsTest
LocationInsightsExcludesFailedConversionsTest
```

## Next PR 2 — Assistant/NL currency-quality contract

Fix:

```text
populate FinancialQueryDataQuality
assistant amount filters currency-aware
largest/total/average/breakdowns warn on exclusions
legacy NL merchant extraction from original query
legacy NL single non-home total uses MoneyAggregateBuilder
legacy NL parsed filters applied or marked unsupported
```

Tests:

```text
AssistantAmountFilterHomeCurrencyTest
AssistantLargestMissingRatePartialTest
LegacyNlMerchantExtractionTest
LegacyNlSingleNonHomeConvertsToHomeTest
```

## Next PR 3 — Analytics canonical migration

Implement:

```text
AnalyticsInputAssembler
migrate key engines to NormalizedAnalyticsInput
daily buckets exact selected period
spending personality uses normalized input
```

Tests:

```text
NormalizedAnalyticsInputMissingRateTest
AnalyticsEnginesSameInputSameTotalsTest
DailyBucketRangeContractTest
```

## Next PR 4 — Investment/subscription/warranty atomicity

Fix:

```text
investment addHolding transaction
investment price update transaction
portfolio history carry-forward
subscription price update transaction
verify all subscription creation paths use validateAndCreate
warranty markAsReturned nullable refund + home currency fallback
```

## Next PR 5 — Categorization + groups

Fix:

```text
MerchantCategoryDao conflict return contracts
cache invalidation
merchant stats post-commit
GroupLifecycleCoordinator
group currency policy
persistent settlement records
```

## Next PR 6 — Tax/business/guards

Fix:

```text
TaxSettingsRepository
business report MoneyAggregate
business CSV formula safety
raw money aggregate CI guard
time/wall-clock CI guard
```

---

# 6. What I would defer

Still defer these as design sprints:

```text
MoneyAmount minor-unit/BigDecimal rewrite
canonical export/import schema
full multi-currency group settlements
investment lot ledger / realized gains
official tax-rate provider
Cloud AI audit dedicated table
full backup privacy mode migration if not yet wired
```

Do not mix those into the current stabilization pass.

---

# 7. Tests you should run now

Before more changes, run:

```text
./gradlew test
./gradlew testDebugUnitTest
./gradlew connectedCheck   # if feasible
./gradlew lint             # if configured
```

Targeted tests to add/confirm:

```text
MoneyAggregateBuilderTest
ExpenseDaoAggregateFilterTest
ConvertedMoneyTest
SpendingMapGpsPrivacyDeniedNoProviderCallTest
MapMarkerMissingRateDoesNotDisplayHomeCurrencyTest
HeatmapUsesNormalizedAmountsTest
AssistantLargestMissingRatePartialWarningTest
AssistantAmountFilterCurrencyAwareTest
LegacyNlSingleNonHomeTotalConvertsTest
ForecastActualQualityMissingRateTest
InvestmentAddHoldingAtomicTest
InvestmentPriceUpdateAtomicTest
SubscriptionValidateAndCreateAllCallersTest
WarrantyReturnedNoRefundAllowedTest
GroupSettlementPersistsTest
```

---

# 8. Final assessment

This engine batch is **good and directionally correct**.

The best improvements are:

```text
MoneyAggregateBuilder + tests
DAO aggregate filter tests
ConvertedMoney semantics
real GPS privacy gate
forecast actual quality
subscription/investment/warranty first real code fixes
```

But do not call the engine pass complete yet.

The biggest remaining high-priority issues are:

```text
1. Map still mislabels failed currency conversions.
2. Heatmap/insights normalization is still TODO/stub.
3. Assistant dataQuality is not populated.
4. Assistant amount filters are still raw.
5. Legacy NL is still mostly TODO.
6. NormalizedAnalyticsInput is skeleton, not migration.
7. Investment/subscription/warranty need atomicity/completeness tests.
8. Groups/tax/categorization/guards are still mostly TODO/design.
```

No broad pipeline regression is obvious, but there are localized correctness risks. The architecture is better than before, but now needs **contract completion and tests**, not just more TODO tracking.

---

# Sources reviewed

Compare / commits:

- https://github.com/panospao7/Cost-agregator/compare/33d31b013c2f06bd59749af456e6249fdb3d7f16...8d76a3f2
- https://github.com/panospao7/Cost-agregator/commit/ec36984d
- https://github.com/panospao7/Cost-agregator/commit/68d24f3c
- https://github.com/panospao7/Cost-agregator/commit/7483df14
- https://github.com/panospao7/Cost-agregator/commit/c3d97815
- https://github.com/panospao7/Cost-agregator/commit/0615408f
- https://github.com/panospao7/Cost-agregator/commit/72d7f9fb
- https://github.com/panospao7/Cost-agregator/commit/22098bde
- https://github.com/panospao7/Cost-agregator/commit/a3aa46f9
- https://github.com/panospao7/Cost-agregator/commit/15cb44b9
- https://github.com/panospao7/Cost-agregator/commit/8d76a3f2

Key files:

- `MoneyAggregate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAggregateBuilder.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `ConvertedMoney.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/core/money/ConvertedMoney.kt

- `SpendingMapViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `SpendingMapScreen.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt

- `SpendingHeatmapEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt

- `LocationInsightsEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt

- `ExecuteFinancialQueryUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt

- `FinancialQueryModels.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt

- `NaturalLanguageSearchEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt

- `NaturalLanguageSearchViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt

- `NormalizedAnalyticsInput.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt

- `ForecastInputAssembler.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `SubscriptionManagerEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `InvestmentTracker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt

- `WarrantyTrackerRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/8d76a3f2/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt