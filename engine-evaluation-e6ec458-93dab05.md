# Engine Evaluation — `e6ec458` and `93dab05`

Reviewed commits:

```text
e6ec458b384197af5e019b4a7837bd43437a4610
93dab05682c4e3d8c08854b5499203e0a7d26224
```

Sources:

- https://github.com/panospao7/Cost-agregator/commit/e6ec458b384197af5e019b4a7837bd43437a4610
- https://github.com/panospao7/Cost-agregator/commit/93dab05682c4e3d8c08854b5499203e0a7d26224

---

## 1. Executive verdict

These commits are a **solid improvement**, especially for:

```text
✅ map marker currency display
✅ map marker historical conversion
✅ heatmap normalized-money path
✅ location insights normalized-money path
✅ GPS privacy gate
✅ assistant dataQuality added to more result types
✅ AnalyticsInputAssembler no longer hardcodes EUR
✅ TotalsAggregationEngine can consume NormalizedAnalyticsInput
✅ investment add/update atomicity improved
✅ subscription price-change atomicity improved
✅ golden scenario smoke-test infrastructure started
✅ raw-money/time guard tasks wired into Gradle check
```

But I would **not call the engine layer stable yet**.

Main reason:

```text
Several items are still TODO-only or partial, despite commit wording like “all addressed.”
```

The latest tracker itself says:

```text
108 total
34 fixed
52 TODO-only
22 deferred
```

So the situation is not “engines done.” It is:

```text
core foundations much better
but ~30–45 meaningful engine tasks remain, depending what features stay enabled
```

---

# 2. Commit `e6ec458` review

Commit title: tracker reconciliation + tests + assistant dataQuality + analytics.

## What is good

### 2.1 Assistant `dataQuality` improved

`ExecuteFinancialQueryUseCase` now populates `FinancialQueryDataQuality` in more paths:

```text
TransactionList
CategoryBreakdown
MerchantBreakdown
Largest
Total / Average structurally have dataQuality
```

This is the right direction.

### 2.2 `AnalyticsInputAssembler` now accepts `homeCurrency`

Good fix. Previous hardcoded `EUR` was risky.

Current signature:

```kotlin
build(
    period,
    homeCurrency,
    expenseRepository,
    normalizer
)
```

This is better than:

```text
homeCurrency = "EUR"
```

### 2.3 Tests added

Added tests include:

```text
MapMarkerConversionCurrencyTest
HeatmapNormalizesCurrencyTest
InvestmentTrackerTest additions
```

Good, but see concerns below.

---

## Remaining issues in `e6ec458`

### E6-P1-1 — Assistant amount filters still not consistently currency-aware

`executeList()` got an in-memory normalized amount path when `minAmount` / `maxAmount` exists. Good.

But other paths still call:

```kotlin
assistantFilteredExpenses(intent, period)
```

and that helper still passes:

```kotlin
minAmount = intent.filters.minAmount
maxAmount = intent.filters.maxAmount
```

to repository filtering.

Affected:

```text
executeTotal
executeAverage
executeLargest
executeCategoryBreakdown
executeMerchantBreakdown
```

So if the user asks:

```text
"total purchases over 50"
"largest transaction over 50"
"top merchants over 50"
```

those paths can still prefilter by raw amount.

### Fix

Create one helper:

```kotlin
assistantFilteredExpensesCurrencyAware(intent, period)
```

Rules:

```text
push date/type/category/merchant/ownership to DAO
do NOT push minAmount/maxAmount
normalize each row using convertAsOf(expense.date)
apply amount filters in memory
failed conversion => excluded + dataQuality warning
```

Use it everywhere.

Priority: P1.

---

### E6-P1-2 — Assistant conversions use current rate, not historical rate

Several assistant paths use:

```kotlin
currencyConverter.convert(...)
```

not:

```kotlin
convertAsOf(..., atMillis = expense.date)
```

For financial query accuracy, use historical conversion where transaction date exists.

Priority: P1.

---

### E6-P1-3 — Category/merchant breakdown failure handling is still semantically weak

For failed conversion in sort key, code does:

```kotlin
failedConversions++
0.0
```

That means a category/merchant group with failed conversion is still included in rows, but sorted as zero.

Better choices:

```text
A. exclude failed-conversion group from converted ranking + warning
B. show native currency group separately
C. mark row partial/unknown sort
```

Current behavior can under-rank large foreign-currency groups silently.

Priority: P1.

---

### E6-P2-1 — New map marker test is a helper test, not real ViewModel test

`MapMarkerConversionCurrencyTest` tests a private local helper inside the test, not the real `SpendingMapViewModel`.

It proves the desired contract, but not that production mapping follows it.

Need a real test around:

```text
SpendingMapViewModel.recomputeMapData()
```

or extracted mapper function.

Priority: P2/P1.

---

### E6-P2-2 — Heatmap test mainly tests `AnalyticsCurrencyNormalizer`, not `SpendingHeatmapEngine`

`HeatmapNormalizesCurrencyTest` is useful, but it does not directly prove:

```text
SpendingHeatmapEngine.computeNormalized()
LocationInsightsEngine.computeNormalized()
SpendingMapViewModel passes normalized inputs
```

Need direct tests for those engines.

Priority: P2.

---

# 3. Commit `93dab05` review

Commit title: analytics migration, map conversion, atomicity, categorization, groups, tax, guards, golden tests.

## What is good

### 3.1 Map marker historical conversion

`SpendingMapViewModel` now uses:

```kotlin
currencyConverter.convertAsOf(
    amount = e.effectiveAmount,
    fromCurrency = e.currency,
    toCurrency = currentState.homeCurrency,
    atMillis = e.date
)
```

Good. This fixes the earlier current-rate mismatch.

### 3.2 Map failed conversion no longer displays home currency

Map marker now carries:

```kotlin
displayCurrency
originalCurrency
conversionWarning
```

and failed conversion uses original currency.

Good. This was one of the most important map correctness issues.

### 3.3 Heatmap and insights normalized paths are implemented

Good additions:

```kotlin
SpendingHeatmapEngine.computeNormalized(...)
LocationInsightsEngine.computeNormalized(...)
```

They include only:

```text
HOME_CURRENCY
CONVERTED
```

and skip failed conversions.

Good.

### 3.4 Investment atomicity improved

`InvestmentTracker.addHolding()` now wraps:

```text
insert investment
insert initial value
```

inside:

```kotlin
database.withTransaction { ... }
```

`updatePrice()` also wraps:

```text
update current price
insert value history
```

Good.

### 3.5 Subscription price-change atomicity improved

`recordPriceChange()` now wraps:

```text
insert price history
update subscription amount
```

inside a DB transaction.

Good.

### 3.6 Guard tasks wired into Gradle

Gradle now registers:

```text
checkRawMoneyAggregates
checkDirectTimeCalls
```

and wires them into:

```text
check
```

Good structurally.

---

## Remaining issues in `93dab05`

### 93-P1-1 — Guard scripts are wired but still no-op

The scripts currently say they are not implemented:

```text
check_raw_money_aggregates.kts:
  TODO: Implement scan...

check_direct_time_calls.kts:
  TODO: Implement scan...
```

They print a message and do not actually scan or fail.

So this is not real protection yet.

### Fix

Implement actual scanning and fail on violations.

Also: check the existing lifecycle bypass script. The raw view still shows a likely broken multiline string around:

```text
println("
Found $violations ...
```

Run:

```bash
kotlin scripts/guards/check_lifecycle_bypasses.kts
./gradlew check
```

Priority: P1.

---

### 93-P1-2 — `AnalyticsInputAssembler` is still not a production assembler

Good foundation, but still partial.

Current limitations:

```text
object, not injectable
expenseRepository + normalizer passed manually
excludedExpenses = emptyList()
isSharedExpense = false
staleRateCount = 0
not clearly used by main analytics flows yet
```

It now takes `homeCurrency`, good.

But it still does not fully preserve:

```text
shared expense state
individual excluded expense IDs
stale-rate failures
source-specific data quality
```

### Fix

Convert to injectable:

```kotlin
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
)
```

Then make analytics consumers use it.

Priority: P1.

---

### 93-P1-3 — `TotalsAggregationEngine.computeFromNormalized()` is useful but not fully integrated

Good method, but it is additive.

Remaining issues:

```text
does not propagate input.dataQuality
always groups by day
uses systemZoneId()
dayEnd = dayStart + 86_400_000L, DST unsafe
comments still say TODO support PeriodType
```

So it is a good first method, not full analytics migration.

### Fix

Add:

```text
PeriodType granularity
zone-aware day end
dataQuality propagation
integration into AnalyticsViewModel/repository
```

Priority: P1/P2.

---

### 93-P1-4 — Subscription `validateAndCreate()` is still not atomic

`recordPriceChange()` is atomic now. Good.

But `validateAndCreate()` does:

```kotlin
val id = recurringExpenseRepository.insert(subscription)

if (recordPriceHistory) {
    priceHistoryDao.insert(...)
}
```

outside one transaction.

If price-history insert fails:

```text
subscription exists without baseline history
```

Also verify:

```text
isSubscription = true
```

because `getAllSubscriptions()` filters by:

```kotlin
it.isSubscription && it.isActive
```

The constructed `ManualRecurringExpense` in `validateAndCreate()` does not visibly set `isSubscription = true` in the reviewed code. If entity default is false, new subscriptions created by this method may not appear as subscriptions.

### Fix

```kotlin
database.withTransaction {
    val id = recurringExpenseRepository.insert(subscription.copy(isSubscription = true))
    priceHistoryDao.insert(...)
}
```

Priority: P1.

---

### 93-P1-5 — Investment raw summary still exists and is still used

`getPortfolioSummary()` is deprecated, good.

But it still raw-sums:

```kotlin
totalValue += investment.currentPrice * investment.quantity
```

and `getPortfolioAllocation()` still calls:

```kotlin
val summary = getPortfolioSummary()
```

So raw mixed-currency paths remain.

### Fix

Either:

```text
make allocation native-per-currency / aggregate-backed
```

or:

```text
disable allocation when mixed currencies exist
```

Priority: P1/P2.

---

### 93-P1-6 — Investment portfolio history still undercounts days

The TODO remains:

```text
Carry forward latest known value per holding for each day in range.
```

Current history only includes holdings with snapshots on that day.

So a holding disappears from portfolio history on days without price updates.

### Fix

For each day:

```text
for each holding:
  latest value <= dayEnd
  carry forward
sum all holdings
```

Priority: P1.

---

### 93-P1-7 — Categorization and groups are still implementation plans, not fixes

In `MerchantCategoryDao`, added content is only comments:

```text
NEXT STEPS
Change insert return from Unit to Long
Add conflict detection
```

In `GroupTransactionCoordinator`, added content is a detailed `GroupLifecycleCoordinator` plan, not actual coordinator code.

So these are not fixed yet:

```text
C01 alias conflict
C04 cache stale
C05 insert result contract
C08 merchant stats post-commit
G01 current-user invariant
G02 side effects inside txn
G03 linked expense lifecycle
G04 mixed-currency settlement
G08 hard delete
G09 direct member delete
```

Priority depends on feature exposure, but if groups/categorization are enabled, these remain P1.

---

### 93-P1-8 — Tax is still mostly a plan

`TaxEstimator` now has a detailed `TaxSettings` plan, but no actual:

```text
TaxSettings entity
TaxSettingsDao
TaxSettingsRepository
fiscal-year logic
selected country persistence
```

So tax remains design/TODO, not fixed.

Priority: P1 if tax feature visible; otherwise contain as estimate/beta.

---

### 93-P2-1 — Golden scenario is only fixture smoke test

`GoldenScenarioSmokeTest` checks that the test fixture can seed one expense.

Good infrastructure canary.

But it does not yet prove:

```text
mixed currency correctness
privacy denied provider not called
receipt/review lifecycle
group settlement
assistant/analytics consistency
backup/restore integrity
```

So call it:

```text
scenario infrastructure smoke test
```

not “golden scenario coverage.”

Priority: P2.

---

# 4. Pipeline / architecture regression assessment

I do **not** see broad regressions to the earlier fixed pipelines.

Positive:

```text
map/location privacy and currency improved
investment atomicity improved
subscription price-change atomicity improved
MoneyAggregate usage improved
assistant dataQuality improved
```

But there are localized risks:

## Risk A — Gradle `check` may now depend on broken/no-op guard scripts

The new raw-money/time guards are no-op. The lifecycle guard may still have script syntax risk. So CI may be either:

```text
falsely green because scripts do nothing
```

or:

```text
red because lifecycle script syntax breaks
```

Please run:

```bash
./gradlew check
kotlin scripts/guards/check_raw_money_aggregates.kts
kotlin scripts/guards/check_direct_time_calls.kts
kotlin scripts/guards/check_lifecycle_bypasses.kts
```

## Risk B — Assistant query behavior still inconsistent

Some assistant result paths use improved in-memory amount filtering, others still pass raw amount filters to repository.

This can make:

```text
list query
count query
total query
largest query
breakdown query
```

disagree for the same user intent.

## Risk C — Some “fixed” tracker statuses are too optimistic

Examples:

```text
W14 legacy NL merchant extraction: partly fixed
W16 NL amount filter unsafe: still TODO
A06 analytics consistency: foundation only
I02 investment price update atomic: likely fixed, tracker still TODO
W07 subscription price change atomic: likely fixed for recordPriceChange only
```

Tracker needs another reconciliation.

---

# 5. Current engine state estimate

After these commits:

```text
Core lifecycle/receipt/privacy pipelines:        85–90%
Money/currency primitives:                      85–90%
Map/location engine:                            80–85%
Assistant query engine:                         65–75%
Legacy NL search:                               45–55%
Analytics canonical migration:                  45–55%
Forecast quality:                               55–65%
Warranty/subscription:                          70–80%
Investment:                                     65–75%
Categorization/merchant learning:               55–65%
Groups:                                         30–40%
Tax/business:                                   40–50%
CI regression guards:                           25–35%
```

Overall:

```text
Engine layer is much better, but still not complete.
```

The remaining work is not “everything is broken.” It is mostly:

```text
finish partial migrations
turn TODO-only plans into code or deferrals
add real contract tests
implement guards
```

---

# 6. Is there more engine work to do?

Yes.

But I would split remaining work into three buckets.

## Bucket A — Must fix for backend stable

Approx:

```text
12–18 fixes
```

These are correctness/consistency items.

## Bucket B — Contain or beta-label

Approx:

```text
10–15 items
```

Advanced features can be guarded/labeled rather than fully finished now.

## Bucket C — Defer design

Approx:

```text
15–25 items
```

These are big design tasks:

```text
MoneyAmount BigDecimal/minor units
investment lot ledger
full multi-currency group settlements
official tax provider
canonical export/import schema
```

---

# 7. Recommended next implementation plan

## PR-1 — Tracker reconciliation

Do this immediately.

Update statuses:

```text
FIXED
PARTIAL
TODO_ONLY
DEFERRED_DESIGN
CONTAINED
```

Likely changes:

```text
W07 → PARTIAL/FIXED for recordPriceChange, still TODO for validateAndCreate atomicity
W11 → FIXED for map path if computeNormalized is used
W14 → PARTIAL
W16 → TODO_ONLY/PARTIAL
W26 → FIXED
A01 → PARTIAL, not deferred
A06 → PARTIAL, not fully fixed
A14 → FIXED/PARTIAL
I02 → FIXED for updatePrice
I03 → TODO_ONLY
I07 → PARTIAL
M10 → PARTIAL/no-op guard
```

Acceptance:

```text
No TODO-only or plan-only item counted as fixed.
```

---

## PR-2 — Assistant query consistency

Create one helper:

```kotlin
assistantFilteredExpensesCurrencyAware(intent, period)
```

Use it in:

```text
executeList
executeCount
executeLargest
executeTotal
executeAverage
executeCategoryBreakdown
executeMerchantBreakdown
```

Rules:

```text
never pass minAmount/maxAmount to repository
use convertAsOf(expense.date)
failed conversion excluded
dataQuality populated everywhere
```

Tests:

```text
AssistantCountAmountFilterCurrencyAwareTest
AssistantTotalAmountFilterCurrencyAwareTest
AssistantBreakdownAmountFilterCurrencyAwareTest
AssistantMissingRatePartialWarningTest
AssistantResultConsistencySameIntentTest
```

Priority: highest current engine issue.

---

## PR-3 — Legacy NL containment/fix

Minimum safe fix:

```text
do not prefilter raw SQL min/max
do not raw-fallback on conversion failure
mark category/location as unsupported if not applied
```

Better:

```text
route legacy NL to assistant execution path where possible
```

Tests:

```text
LegacyNlNoRawAmountPrefilterTest
LegacyNlNoRawFallbackOnMissingRateTest
LegacyNlCategoryLocationUnsupportedBadgeTest
LegacyNlMerchantExtractionOriginalCaseTest
```

Priority: P1 if Smart Search/NL visible.

---

## PR-4 — Analytics real migration

Make `AnalyticsInputAssembler` injectable.

Fix:

```text
excludedExpenses not empty
isSharedExpense preserved
staleRateCount populated
homeCurrency from CurrencySettingsRepository
```

Then migrate first consumers:

```text
TotalsAggregationEngine
daily chart buckets
category breakdown
merchant breakdown
spending personality
advanced analytics
```

Tests:

```text
AnalyticsInputAssemblerHomeCurrencyTest
AnalyticsInputAssemblerExcludedIdsTest
AnalyticsInputAssemblerPreservesSharedExpenseTest
TotalsFromNormalizedDataQualityTest
DailyBucketsExactRangeTest
SpendingPersonalityUsesNormalizedInputTest
```

Priority: P1.

---

## PR-5 — Subscription / investment finish

### Subscription

Fix:

```text
validateAndCreate transaction
set isSubscription = true
candidate accept path uses validateAndCreate
calendar next-date verified
```

Tests:

```text
SubscriptionValidateAndCreateAtomicTest
SubscriptionCreatedIsSubscriptionTest
SubscriptionCandidateAcceptUsesValidatedCreateTest
SubscriptionCalendarNextDateTest
```

### Investment

Fix:

```text
portfolio history carry-forward
allocation aggregate-safe
raw PortfolioSummary usage reduced/removed
```

Tests:

```text
PortfolioHistoryCarryForwardTest
PortfolioAllocationMixedCurrencyWarningTest
RawPortfolioSummaryNotUsedByPublicUiTest
```

Priority: P1.

---

## PR-6 — Real CI guards

Implement:

```text
check_raw_money_aggregates.kts
check_direct_time_calls.kts
fix/check_lifecycle_bypasses.kts syntax
```

Start with allowlist.

Raw-money guard should flag:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
CurrencyFormatter.format(rawDouble, homeCurrency)
data class ... total: Double in public engine result
```

Time guard should flag:

```text
System.currentTimeMillis()
Calendar.getInstance()
Date()
Instant.now()
LocalDate.now()
```

Acceptance:

```text
guards fail on seeded violation
guards pass current allowlisted code
./gradlew check runs them
```

Priority: P1.

---

## PR-7 — Categorization/merchant contracts

Fix:

```text
MerchantCategoryDao.insert(): Long
insertAll(): List<Long>
alias conflict detection
cache invalidation
merchant stats post-commit
semantic keyword collision policy
```

Tests:

```text
MerchantCategoryInsertConflictTest
MerchantAliasConflictTest
CategoryCacheInvalidationTest
MerchantStatsPostCommitTest
SemanticKeywordCollisionPolicyTest
```

Priority: P1/P2 depending categorization visibility.

---

## PR-8 — Groups lifecycle or containment

If groups are enabled, implement:

```text
GroupLifecycleCoordinator
single-currency group policy
recordSettlement()
archiveGroup()
linked expense updates through TransactionLifecycleCoordinator
```

If not ready, mark groups beta and disable misleading settlement button.

Tests:

```text
GroupCurrentUserInvariantTest
GroupRejectsForeignCurrencyExpenseTest
GroupSettlementPersistsTest
GroupLinkedExpenseLifecycleTest
GroupArchiveVsHardDeleteTest
```

Priority: P1 if enabled, otherwise contain.

---

## PR-9 — Tax/business containment or implementation

If tax visible:

```text
TaxSettingsRepository
selected country persistence
fiscal year settings
BusinessReport MoneyAggregate all fields
CSV formula sanitizer
business/tax lifecycle update method
```

If not, mark tax as estimate/beta.

Tests:

```text
TaxSettingsPersistenceTest
FiscalYearRangeTest
BusinessReportMoneyAggregateTest
BusinessCsvFormulaSafetyTest
BusinessTaxLifecycleUpdateTest
```

Priority: P1 if visible.

---

## PR-10 — Real golden scenario tests

Current scenario test is only fixture smoke.

Add:

```text
MixedCurrencyAnalyticsAssistantScenarioTest
MapPrivacyCurrencyScenarioTest
SubscriptionInvestmentAtomicityScenarioTest
GroupSettlementLifecycleScenarioTest
TaxBusinessReportScenarioTest
```

Each should assert:

```text
no fake home-currency totals
partial warnings surfaced
privacy providers not called when denied
atomic writes roll back on simulated failure
lifecycle events written
```

---

# 8. What I would defer

Still defer:

```text
MoneyAmount BigDecimal/minor units
investment lot ledger
official tax-rate provider
full multi-currency group settlement engine
Cloud AI dedicated audit table
canonical export/import schema
full backup privacy-mode redesign
```

These are design sprints, not stabilization tasks.

---

# 9. Final answer

Are the two commits good?

```text
Yes.
```

Are engines done?

```text
No.
```

What remains?

```text
Mostly completion work:
- assistant/NL consistency
- analytics migration
- real guards
- subscription/investment edge atomicity
- categorization contracts
- groups/tax if enabled
- real golden scenario tests
```

Most important next step:

```text
Do not add more TODO comments.
Convert the existing partial/TODO items into either code+tests or explicit DEFERRED_DESIGN/CONTAINED.
```

Best immediate next PR:

```text
Assistant query consistency + tracker reconciliation.
```

That will eliminate one of the last major sources of cross-engine disagreement.