# Evaluation of commit `8c645d80b5af18d609885fb2303daf9148a1dfb9`

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/8c645d80b5af18d609885fb2303daf9148a1dfb9

Review type: static GitHub/code review, not local Gradle execution.

---

## 1. Executive verdict

This is a **good forward commit**. It meaningfully advances the engine stabilization work.

Big wins:

```text
✅ map markers now carry displayCurrency/originalCurrency/conversionWarning
✅ map marker UI formats marker.amount using marker.displayCurrency, not always home currency
✅ SpendingHeatmapEngine.computeNormalized() implemented
✅ LocationInsightsEngine.computeNormalized() implemented
✅ SpendingMapViewModel uses LocatedMoneyExpense for heatmap/insights
✅ SpendingMapViewModel checks DEVICE_GPS_LOCATION privacy before GPS provider call
✅ Assistant largest query now has dataQuality when failed conversions are excluded
✅ Assistant LIST amount filtering is now in-memory and home-currency-normalized
✅ Legacy NL merchant extraction now uses original query
✅ NaturalLanguageSearchViewModel total now uses MoneyAggregateBuilder
✅ AnalyticsInputAssembler skeleton created
✅ Investment addHolding + updatePrice now wrapped in database.withTransaction
✅ Warranty markAsReturned supports nullable refundAmount and home-currency fallback
✅ Subscription recordPriceChange wrapped in transaction
✅ guard script stubs added
```

But this is **not the end of the engine TODOs**.

Main remaining risks:

```text
P1: Assistant dataQuality is only populated for largest; list/total/average/breakdown/count still default or raw.
P1: Assistant COUNT and non-LIST paths still use raw minAmount/maxAmount through repository filtering.
P1: Legacy NL still applies raw SQL min/max before in-memory conversion and still raw-fallbacks on conversion failure.
P1: AnalyticsInputAssembler is hardcoded to EUR and does not yet migrate engines.
P1: AnalyticsInputAssembler loses isSharedExpense and individual excluded expense IDs.
P1: Subscription validateAndCreate is not transactional and may not mark isSubscription depending entity defaults.
P1: Tax year summary still uses raw business category totals in places.
P1: Groups and categorization are still mostly TODO-only.
P1: guard scripts are stubs, not CI enforcement.
P2: mapConversionWarnings exists in state but is not visibly surfaced as a UI/banner warning.
```

Overall:

```text
Engine state after this commit: better, but still mid-stabilization.
No broad pipeline regression obvious.
Several tracker statuses are now stale/inconsistent.
```

---

# 2. Commit-specific evaluation

## 2.1 Map/location

### Good

`MapExpenseMarker` now includes:

```kotlin
displayCurrency
originalCurrency
conversionWarning
```

`SpendingMapScreen` now formats marker detail with:

```kotlin
CurrencyFormatter.format(marker.amount, marker.displayCurrency)
```

This fixes the previous serious issue:

```text
failed USD→EUR conversion shown as €100
```

Now failed conversion can show native currency and warning.

Also good:

```text
SpendingHeatmapEngine.computeNormalized()
LocationInsightsEngine.computeNormalized()
SpendingMapViewModel builds LocatedMoneyExpense
failed conversions excluded from heatmap/insights
mapConversionWarnings count tracked
DEVICE_GPS_LOCATION gate checked before locationProvider.getLastKnownLocation()
```

### Remaining issues

#### M1 — Marker conversion uses current rate, not historical rate

Markers use:

```kotlin
currencyConverter.convert(...)
```

Heatmap uses:

```kotlin
convertAsOf(..., atMillis = e.date)
```

For consistency, marker display should also use historical conversion:

```kotlin
currencyConverter.convertAsOf(
    amount = e.effectiveAmount,
    fromCurrency = e.currency,
    toCurrency = homeCurrency,
    atMillis = e.date
)
```

Priority: P1.

#### M2 — `mapConversionWarnings` is not surfaced visibly

State has:

```kotlin
mapConversionWarnings
```

but `SpendingMapScreen` does not appear to show a banner/card.

Fix:

```text
if mapConversionWarnings > 0:
  show “Map excludes N expenses from heatmap/insights due to missing rates.”
```

Priority: P2/P1 depending map importance.

#### M3 — old raw-path variables remain

`spendingDomainExpenses` and `heatmapExpenses` are still created but not used. Remove them to avoid future accidental raw-path reintroduction.

Priority: P2.

---

## 2.2 Assistant / AI query execution

### Good

`executeLargest()` now:

```text
normalizes mixed-currency rows to home currency
excludes failed conversions
sets FinancialQueryDataQuality for failures
```

`executeList()` now disables raw SQL min/max and filters in memory after converting to home currency.

Good direction.

### Remaining issues

#### A1 — `executeList()` still returns empty/default dataQuality

It counts:

```kotlin
failedConversions++
```

but returns:

```kotlin
dataQuality = FinancialQueryDataQuality()
```

Fix:

```kotlin
dataQuality = FinancialQueryDataQuality(
    isPartial = failedConversions > 0,
    excludedCount = failedConversions,
    missingRateCount = failedConversions,
    warnings = ...
)
```

Priority: P1.

#### A2 — `executeCount()` still uses raw SQL min/max filters

`executeCount()` calls repository with:

```kotlin
minAmount = intent.filters.minAmount
maxAmount = intent.filters.maxAmount
```

That is still currency-unsafe.

Fix:

```text
If amount filter exists:
  fetch narrowed rows without min/max
  normalize in memory
  count filtered rows
  return Summary with dataQuality
```

Priority: P1.

#### A3 — `assistantFilteredExpenses()` still passes raw min/max

This affects:

```text
largest
total
average
category breakdown
merchant breakdown
```

if intent has amount filters.

Fix:

```text
create one helper:
getAssistantFilteredExpensesCurrencyAware(intent, period)
```

It should:

```text
1. query period/type/category/merchant/ownership without amount min/max
2. normalize amount if amount filter exists
3. exclude failed conversions
4. return rows + FinancialQueryDataQuality
```

Priority: P1.

#### A4 — total/average/breakdown still default dataQuality

Current:

```kotlin
dataQuality = FinancialQueryDataQuality()
```

Fix all result types.

Priority: P1.

#### A5 — category/merchant sorting still suppresses failed conversion to 0

Sort key code uses:

```kotlin
currencyConverter.convert(...) ?: 0.0
```

That can push failed foreign-currency groups to the bottom silently.

Better:

```text
exclude from sorted confident ranking
add partial warning
or sort native groups after converted groups with warning
```

Priority: P1.

---

## 2.3 Legacy Natural Language Search

### Good

Merchant extraction now uses original query:

```kotlin
val merchants = extractMerchants(query)
```

NaturalLanguageSearchViewModel now uses `MoneyAggregateBuilder`, which fixes single non-home total display in that specific total path.

### Remaining issues

#### N1 — raw SQL amount prefilter still happens

`executeSearch()` still extracts:

```kotlin
minAmount
maxAmount
```

and passes them to:

```kotlin
getExpensesBetweenFiltered(..., minAmount, maxAmount)
```

before doing in-memory currency conversion.

This can incorrectly exclude rows before conversion.

Fix:

```text
for amount-filtered NL query:
  pass minAmount=null, maxAmount=null to repository
  apply currency-aware filter in memory
```

Priority: P1.

#### N2 — conversion failure raw-fallback still exists

Legacy NL does:

```kotlin
currencyConverter.convert(...)?.convertedAmount ?: expense.amount
```

This is still wrong. Failed conversions should be:

```text
excluded + warning
```

or native-only, not compared as home currency.

Priority: P1.

#### N3 — category/location filters still ignored

The engine logs warning, but UI can still show parsed chips as if applied.

Options:

```text
A. Actually apply category filters.
B. Mark categories/locations as parsed-but-not-applied in result.
C. Hide/disable legacy NL advanced filters and route to Assistant.
```

Priority: P1/P2.

---

## 2.4 Analytics canonical input

### Good

`AnalyticsInputAssembler` was created.

This is the right architectural direction:

```text
fetch once
normalize once
feed engines the same canonical input
```

### Serious limitations

#### AN1 — hardcoded EUR

It does:

```kotlin
normalizer.normalizeExpenses(expenses, "EUR")
homeCurrency = "EUR"
normalizedCurrency = "EUR"
```

This must be home currency from settings.

Fix:

```kotlin
suspend fun build(
    period,
    homeCurrency,
    ...
)
```

or inject `CurrencySettingsRepository`.

Priority: P1.

#### AN2 — object, not injectable

`object AnalyticsInputAssembler` makes dependency injection awkward.

Better:

```kotlin
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
)
```

Priority: P1.

#### AN3 — excludedExpenses is empty

It has:

```kotlin
val excluded: List<ExcludedExpense> = emptyList()
```

while dataQuality has excluded count.

This is okay as Stage 1, but not complete.

Priority: P2.

#### AN4 — `isSharedExpense = false`

This loses shared-state semantics:

```kotlin
isSharedExpense = false
```

because `ExpenseSnapshot` lacks the field.

Fix the snapshot/normalizer to carry shared fields.

Priority: P1 if analytics uses shared expenses.

#### AN5 — not yet used by analytics engines

Still need migration of:

```text
TotalsAggregationEngine
InsightsEngine
SpendingPersonalityClassifier
AdvancedAnalyticsEngine
category/merchant analytics
daily buckets
location analytics
```

Priority: P1.

---

## 2.5 Warranty/subscription/investment atomicity

### Warranty

Good:

```text
markAsReturned(refundAmount: Double? = null)
linked expense currency → home currency fallback
no hardcoded EUR unless home currency fallback fails
```

Remaining:

```text
if refundAmount=null, status becomes RETURNED but no refund fields update
```

This may be valid, but tests should lock the contract:

```text
returned with no refund
returned with refund
returned with explicit currency
returned with linked expense currency
```

### Subscription

Good:

```text
recordPriceChange uses database.withTransaction
recordedAt set
```

Remaining issue:

```text
validateAndCreate() inserts subscription and baseline history outside database.withTransaction
```

Fix:

```kotlin
database.withTransaction {
    val id = recurringExpenseRepository.insert(subscription)
    if (recordPriceHistory) priceHistoryDao.insert(...)
}
```

Also verify `ManualRecurringExpense` is created with:

```text
isSubscription = true
```

If the entity default is false, `getAllSubscriptions()` will never see newly created subscriptions.

Priority: P1.

### Investment

Good:

```text
addHolding insert + initial value snapshot in transaction
updatePrice update + value history in transaction
```

Remaining:

```text
getPortfolioSummary() raw Double remains
getPortfolioAllocation() uses raw getPortfolioSummary()
portfolio history still does not carry forward holding values
```

Priority: P1/P2.

---

## 2.6 Categorization/groups/tax/guards

### Categorization

Commit adds better TODO guidance only.

Still open:

```text
MerchantCategoryDao.insert returns Unit
alias conflict handling
cache invalidation
merchant stats post-commit
semantic collision policy
```

### Groups

Commit adds TODOs only.

Still open:

```text
GroupLifecycleCoordinator
single-currency policy
recordSettlement()
archive vs hard delete
linked expense ownership updates through TransactionLifecycleCoordinator
```

### Tax

TaxSettings is still TODO.

Also `getTaxYearSummary()` still uses:

```text
businessExpenseRepository.getExpensesByCategory()
businessExpenseRepository.getTotalBusinessExpenses()
```

which may still be raw/mixed-currency depending repository internals.

Need:

```text
BusinessReport MoneyAggregate all the way through
TaxSettingsRepository
CSV safety
business/tax updates through lifecycle
```

### Guards

Scripts exist but are stubs:

```text
check_raw_money_aggregates.kts
check_direct_time_calls.kts
```

They only print messages and do not fail CI.

---

# 3. Tracker consistency

Latest tracker says:

```text
34 fixed with code, 39 TODO-only, 35 deferred
```

but the summary says:

```text
31 fixed, 39 TODO-only, 35 deferred
```

Also several statuses are stale:

```text
W11 location insights include non-spending → appears fixed in map path
W12 map/insight amounts not normalized → mostly fixed for map path
W14 merchant extraction broken → partially fixed
W16 NL amount filter unsafe → partially fixed, not done
W26 map date-range inclusive end → likely fixed earlier
I02 price update atomic → appears fixed
W07 subscription price change atomic → appears fixed for recordPriceChange, not validateAndCreate
W22 subscription validation → partially fixed
A01/A06 analytics canonical input → foundation exists, not migration complete
```

Recommendation:

```text
Do a tracker reconciliation PR before more TODO work.
```

Use statuses:

```text
FIXED
PARTIAL
TODO_ONLY
DEFERRED_DESIGN
CONTAINED
```

Avoid counting “PARTIAL” as fixed.

---

# 4. Current engine state estimate

After `8c645d8`:

```text
Core pipelines:                 85–90%
Money/currency primitive layer: 80–88%
Map/location:                   70–80%
Assistant query engine:          60–70%
Legacy NL:                      45–55%
Analytics canonical migration:  35–45%
Warranty/subscription:           70–80%
Investment:                     60–70%
Categorization/merchant:         55–65%
Groups:                         30–40%
Tax/business:                   40–50%
CI guards:                      20–30%
```

Overall engine layer:

```text
Good stabilization progress, still not “backend stable.”
```

---

# 5. Detailed implementation plan for remaining TODOs

The latest tracker says approximately:

```text
39 TODO-only
35 deferred
```

I would not try to fix the 35 deferred design items now. Focus on TODO-only and partial fixes.

---

## Phase 0 — Reconcile tracker and tests

### PR-0A — Tracker reconciliation

Update each item to:

```text
FIXED
PARTIAL
TODO_ONLY
DEFERRED_DESIGN
CONTAINED
```

Expected changes:

```text
W11 → FIXED/PARTIAL
W12 → PARTIAL/FIXED depending tests
W14 → PARTIAL
W16 → PARTIAL
I02 → FIXED
W07 → PARTIAL
W22 → PARTIAL
A01/A06 → PARTIAL
```

Acceptance:

```text
tracker count matches real code state
no TODO-only counted as fixed
```

### PR-0B — Add tests for `8c645d8`

Tests:

```text
MapMarkerFailedConversionNativeCurrencyTest
HeatmapComputeNormalizedExcludesFailedTest
LocationInsightsComputeNormalizedExcludesFailedTest
AssistantListAmountFilterPartialTest
AssistantCountAmountFilterRawRegressionTest
LegacyNlAmountPrefilterRegressionTest
AnalyticsInputAssemblerHomeCurrencyTest
InvestmentAddHoldingAtomicTest
InvestmentUpdatePriceAtomicTest
SubscriptionValidateAndCreateAtomicTest
WarrantyMarkReturnedNoRefundTest
```

---

# 6. Phase 1 — Finish Assistant + NL correctness

## PR-1A — One currency-aware assistant filter helper

Create:

```kotlin
data class AssistantFilterResult(
    val rows: List<ExpenseWithCategory>,
    val dataQuality: FinancialQueryDataQuality
)
```

Function:

```kotlin
private suspend fun assistantFilteredExpensesCurrencyAware(
    intent: FinancialQueryIntent,
    period: PeriodRange
): AssistantFilterResult
```

Rules:

```text
date/type/category/merchant/ownership pushed to repository
minAmount/maxAmount NOT pushed to SQL
amount filter interpreted as home currency for now
foreign row converted using convertAsOf(expense.date)
failed conversion excluded + dataQuality warning
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

Acceptance:

```text
no assistant path passes minAmount/maxAmount to repository
all result types carry dataQuality
```

---

## PR-1B — Legacy NL minimum safe behavior

Fix:

```text
no raw SQL min/max prefilter
no raw fallback on conversion failure
category/location marked unsupported or applied
single non-home total already via builder
```

Short-term if not applying category/location:

```kotlin
QueryInterpretation.unsupportedFilters = setOf(CATEGORY, LOCATION)
```

Acceptance:

```text
legacy NL does not pretend unsupported filters are applied
failed conversion is excluded or warning surfaced
```

---

# 7. Phase 2 — Analytics canonical migration

## PR-2A — Make `AnalyticsInputAssembler` real

Change from object to injectable class.

Inputs:

```kotlin
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
)
```

Fix:

```text
home currency from settings
normalizedCurrency = homeCurrency
excludedExpenses populated at least as grouped exclusions
isSharedExpense preserved by extending ExpenseSnapshot
stale/missing counts mapped correctly
```

Acceptance:

```text
home USD produces NormalizedAnalyticsInput.homeCurrency=USD
no hardcoded EUR except fallback comments
```

---

## PR-2B — Migrate first analytics consumers

Order:

```text
1. Totals/daily buckets
2. category breakdown
3. merchant breakdown
4. spending personality
5. advanced analytics
```

Rule:

```text
no analytics engine queries raw ExpenseRepository itself if NormalizedAnalyticsInput is available
```

Acceptance:

```text
dashboard/analytics/forecast use same total for same period
```

---

# 8. Phase 3 — Map/location final hardening

## PR-3A — Historical conversion for markers

Replace:

```kotlin
currencyConverter.convert(...)
```

with:

```kotlin
convertAsOf(..., atMillis = e.date)
```

Acceptance:

```text
marker and heatmap conversion agree for same expense
```

## PR-3B — Surface map warnings

Show:

```text
N expenses excluded from heatmap/insights due to missing rates
```

Acceptance:

```text
mapConversionWarnings visible in UI or returned domain quality
```

---

# 9. Phase 4 — Subscription / warranty / investment finish

## PR-4A — Subscription creation atomicity

Wrap:

```text
subscription insert + baseline price history
```

in transaction.

Verify:

```text
isSubscription = true
createdAt/updatedAt set if fields exist
currency uppercase validated
candidate accept uses validateAndCreate
```

## PR-4B — Investment history carry-forward

Implement:

```text
for each day:
  for each holding:
    latest value <= dayEnd
    carry forward
```

Also deprecate/hide raw portfolio summaries from callers.

## PR-4C — Warranty tests and end-date contract

Lock:

```text
returned with no refund
returned with refund
half-open end-date semantics
home currency fallback
```

---

# 10. Phase 5 — Categorization and merchant TODOs

## PR-5A — DAO conflict contracts

Fix:

```text
MerchantCategoryDao.insert(): Long
alias insert returns conflict result
normalizedCanonicalName lookup disambiguated
```

Tests:

```text
MerchantCategoryInsertConflictTest
MerchantAliasConflictTest
CanonicalNameAmbiguityTest
```

## PR-5B — Cache invalidation and stats

Implement:

```text
CategoryMappingWriter
MerchantMappingWriter
CategoryMappingChanged / MerchantMappingChanged events
post-commit merchant stats update
semantic collision policy
```

Acceptance:

```text
category correction invalidates cache immediately
merchant stats update only after transaction commit
```

---

# 11. Phase 6 — Groups

## PR-6A — GroupLifecycleCoordinator

Implement:

```text
createGroup
addMember
removeMember
addExpense
archiveGroup
deleteGroupPermanently
recordSettlement
```

Rules:

```text
currentUserGroupKey invariant
defer side effects until after commit
linked expense ownership updates through TransactionLifecycleCoordinator
```

## PR-6B — Group currency policy

Short-term recommendation:

```text
single-currency groups
reject group expense if currency != group.currency
legacy mixed rows show warning
settlements persist in group currency
```

Acceptance:

```text
group settlement is durable, not UI-local
group totals cannot raw-sum mixed currency
```

---

# 12. Phase 7 — Tax/business

## PR-7A — TaxSettingsRepository

Add:

```text
selectedCountry
filingCurrency
fiscalYearStartMonth
fiscalYearStartDay
```

TaxEstimator consumes settings instead of default factory.

## PR-7B — Business report MoneyAggregate + CSV safety

Fix:

```text
category deductions use MoneyAggregate
uncategorized calculation not raw mixed-currency
CSV cells neutralize = + - @ tab CR/LF
business/tax field updates through TransactionLifecycleCoordinator
```

---

# 13. Phase 8 — Guards

## PR-8A — Implement raw money guard

Current script is stub.

Implement scanning for:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
CurrencyFormatter.format(..., homeCurrency)
public total: Double in engine result
```

Allowlist:

```text
MoneyAggregateBuilder
CurrencyConverter
tests
native currency row display
```

Wire to Gradle `check`.

## PR-8B — Implement direct time guard

Scan for:

```text
System.currentTimeMillis()
Calendar.getInstance()
Date()
Instant.now()
LocalDate.now()
```

Allowlist:

```text
TimeProvider
platform adapters
tests
```

Wire to Gradle `check`.

---

# 14. Phase 9 — Golden scenario tests

Add these last:

```text
MixedCurrencyDashboardAnalyticsAssistantScenarioTest
MapLocationPrivacyCurrencyScenarioTest
SubscriptionWarrantyInvestmentAtomicityScenarioTest
GroupSettlementLifecycleScenarioTest
TaxBusinessReportCurrencyScenarioTest
```

Each asserts:

```text
no raw mixed-currency total
partial warnings surfaced
privacy gates block providers
transactions/lifecycle events written
atomicity preserved on simulated failure
```

---

# 15. Priority order

Do this exact order:

```text
1. Tracker reconciliation
2. Tests for 8c645d8
3. Assistant/NL currency/dataQuality completion
4. AnalyticsInputAssembler real home-currency version
5. Analytics engine migration
6. Map historical conversion + visible warning
7. Subscription/investment/warranty atomicity tests
8. Categorization DAO/cache contracts
9. GroupLifecycleCoordinator + currency policy
10. TaxSettings + business report MoneyAggregate
11. CI guards
12. Golden scenario tests
```

---

# 16. What to defer

Keep these deferred:

```text
MoneyAmount BigDecimal/minor-units rewrite
investment lot ledger
official tax provider
multi-currency group settlement engine
Cloud AI audit dedicated table
canonical export/import schema
full privacy-mode backup redesign
```

Do not mix them into the current engine stabilization.

---

# 17. Final recommendation

`8c645d8` is a good stabilization commit, but it should be treated as:

```text
map/assistant/analytics/atomicity foundation pass
```

not as final completion.

The next best move is:

```text
1. reconcile tracker,
2. add tests for the new behavior,
3. finish Assistant/NL dataQuality and currency filters,
4. make AnalyticsInputAssembler real and migrate consumers.
```

Once those are done, the engine layer will be much closer to “stable backend.”