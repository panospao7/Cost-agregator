# Engine Fix Implementation Plan After `8243fc0` and `33d31b0`

Reviewed commits:

```text
8243fc077e6e228623c161bc5b7abbaddf333799
33d31b013c2f06bd59749af456e6249fdb3d7f16
```

## 1. Current status

These two commits are a good step.

### `8243fc0` improved

```text
✅ MoneyAggregateBuilder helper
✅ single non-home currency now converts to home currency
✅ stale-rate vs missing-rate mapping improved
✅ business expense DAO spending filter added
✅ located merchant DAO spending/isNotMine/merchantKey filters added
✅ SharedExpenseBudgetOffsetEngine exposes partial conversion state
✅ SpeechInputGateway.destroy() added and wired
✅ tracker wording changed from “resolved” to “triaged”
```

### `33d31b0` improved

```text
✅ assistant largest-query no longer raw-fallbacks failed conversions
✅ assistant category/merchant sorting now converts single non-home groups
✅ map markers use effectiveAmount instead of raw amount
✅ map date filter changed to half-open end date
✅ map LocatedExpense carries currency
✅ map insights use spending-only expenses
```

But several fixes are still partial.

Important remaining caveats:

```text
⚠ SpendingMapViewModel GPS privacy gate is still TODO/commented, not actually injected.
⚠ Map marker conversion still falls back to raw amount on failure.
⚠ Heatmap/insights still do not fully normalize amounts to home currency.
⚠ Assistant failed conversion count is not surfaced in result data quality.
⚠ Assistant amount filters are still raw numeric filters.
⚠ MoneyAggregate failedTransactionCount is still misleading.
⚠ Tracker status counts appear inconsistent: header says 25 fixed, summary says 22 fixed.
⚠ No tests were added in these two commits.
```

So the direction is right, but you should now move from “fixing code paths” to “locking contracts with tests.”

---

# 2. Main strategy

Do not keep fixing random TODOs one by one.

Use this order:

```text
1. Stabilize shared primitives.
2. Lock money/currency correctness.
3. Lock privacy/cloud/location gates.
4. Lock analytics/query consistency.
5. Lock lifecycle/domain side effects.
6. Then handle advanced engines.
7. Finally add broad scenario tests.
```

The biggest mistake now would be jumping into investment/tax/groups before the money and query contracts are stable.

---

# 3. Immediate next PR: test and harden what you just changed

## PR-E0 — Contract tests for `8243fc0` / `33d31b0`

Before new engine fixes, add tests for the new foundation.

### Add tests

```text
MoneyAggregateBuilderTest
ExpenseDaoAggregateFilterTest
SharedExpenseBudgetOffsetPartialTest
AssistantLargestCurrencyFailureTest
AssistantBreakdownSortCurrencyTest
SpeechInputGatewayLifecycleTest
```

### Required cases

#### `MoneyAggregateBuilderTest`

```text
empty buckets → displayCurrency = homeCurrency
single home bucket → no conversion
single non-home bucket → converts to homeCurrency
mixed buckets → converts all convertible buckets
stale rate → FailureReason.RATE_STALE
missing rate → FailureReason.MISSING_RATE
failure warning says currency bucket, not transaction
transactionCounts are preserved in sourceBuckets
```

#### `ExpenseDaoAggregateFilterTest`

Seed rows:

```text
business purchase
business deposit
business transfer
not-mine located purchase
located merchant without merchantKey
```

Assert:

```text
business aggregate includes spending only
located merchant aggregate excludes not-mine
located merchant aggregate excludes deposits/transfers
located merchant aggregate excludes null merchantKey
```

#### `AssistantLargestCurrencyFailureTest`

Seed:

```text
EUR 100
USD 200 missing rate
EUR 150
```

Assert:

```text
largest = EUR 150
USD row excluded
partial/failure TODO documented or result warning exists
```

If result type cannot expose partial state yet, create a test with TODO/assert current behavior clearly.

### Why this PR first

Because `MoneyAggregateBuilder` is now a central primitive. If it is wrong, every later engine fix inherits the bug.

---

# 4. Phase 1 — Money and time primitives

This phase prevents future fake totals and bad date ranges.

## PR-E1 — Fix `MoneyAggregate` diagnostics

Current issue:

```kotlin
failedTransactionCount = conversionFailures.size
```

This is bucket count, not transaction count.

### Implement

Add transaction count to conversion failure:

```kotlin
data class ConversionFailure(
    val originalAmount: MoneyAmount,
    val targetCurrency: CurrencyCode,
    val reason: FailureReason,
    val transactionCount: Int = 0
)
```

Update `MoneyAggregateBuilder`:

```kotlin
val failedCountsByCurrency = byCurrency transaction counts
```

Then:

```kotlin
val failedTransactionCount: Int
    get() = conversionFailures.sumOf { it.transactionCount }

val failedBucketCount: Int
    get() = conversionFailures.size
```

Fix warning:

```text
Total excludes 2 transactions across 1 currency bucket.
```

### Tests

```text
MoneyAggregateFailedTransactionCountTest
MoneyAggregateWarningMessageTest
```

---

## PR-E2 — `ConvertedMoney` failure semantics

Fix tracker items:

```text
M01 ConvertedMoney.identity() treated as failed
M08 ConvertedMoney.failed(reason) ignores reason
```

### Implement

```kotlin
sealed interface ConvertedMoneyStatus {
    data object ExactSameCurrency
    data object Converted
    data class Failed(val reason: FailureReason, val message: String?)
}
```

Or simpler:

```kotlin
data class ConvertedMoney(
    ...
    val isSuccess: Boolean,
    val isExactIdentity: Boolean = false,
    val failureReason: FailureReason? = null,
    val failureMessage: String? = null
)
```

Rules:

```text
same currency = success, exact identity
failed = success false + reason
```

### Tests

```text
ConvertedMoneyIdentityIsSuccessTest
ConvertedMoneyFailureReasonTest
```

---

## PR-E3 — Time period contract

Fix:

```text
M04 PeriodKind timezone math
M11 week-number helpers
M12 last-7-days naming
```

### Implement

Use `java.time`:

```kotlin
data class AppTimeZone(val zoneId: ZoneId)

fun PeriodKind.toRange(
    referenceInstant: Instant,
    zoneId: ZoneId
): PeriodRange
```

Contracts:

```text
calendar month = local month boundaries
trailing 7 days = now minus 7 days to now
last 7 calendar days = start of six days ago to tomorrow start
week = app-configured week start or ISO explicitly
```

### Tests

```text
PeriodRangeDstBoundaryTest
CalendarMonthZoneTest
Trailing7DaysNoFutureRemainderTest
IsoWeekNumberTest
```

---

# 5. Phase 2 — Location/map engine correctness

You already partially fixed this. Finish it next because privacy + money correctness intersect here.

## PR-E4 — Real map GPS privacy gate

Current state in `SpendingMapViewModel`:

```text
PrivacyGate check is commented TODO.
```

### Implement

Inject:

```kotlin
private val privacyGate: PrivacyGate
```

Then:

```kotlin
val decision = privacyGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)
if (decision is PrivacyDecision.Denied) {
    _state.update {
        it.copy(
            snackbarMessage = "Device GPS is disabled in Privacy settings.",
            deviceLatitude = null,
            deviceLongitude = null
        )
    }
    return
}
```

Also do not fetch GPS immediately unless needed.

Change behavior:

```text
Android permission granted ≠ app privacy allowed
```

### Tests

```text
SpendingMapGpsPrivacyDeniedNoProviderCallTest
SpendingMapGpsAllowedCallsProviderTest
```

---

## PR-E5 — No raw fallback for map money

Current issue:

```kotlin
conversion failed → e.amount
```

This is still wrong.

### Implement

Introduce:

```kotlin
data class MapMoneyDisplay(
    val displayAmount: Double?,
    val displayCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val isPartial: Boolean,
    val warning: String?
)
```

For marker:

```text
same currency → display effectiveAmount home
conversion success → display converted home
conversion failure → display original effectiveAmount + original currency, warning
```

Never label raw USD as EUR.

### Tests

```text
MapMarkerConversionFailureShowsNativeCurrencyTest
MapMarkerUsesEffectiveAmountTest
MapMarkerSharedExpenseEffectiveAmountTest
```

---

## PR-E6 — Normalize heatmap and insights

Current map engines still receive raw `LocatedExpense.amount`.

### Implement

Create:

```kotlin
data class LocatedMoneyExpense(
    val expenseId: Long,
    val latitude: Double,
    val longitude: Double,
    val normalizedAmount: Double?,
    val normalizedCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val conversionStatus: ConversionStatus,
    val merchant: String,
    val date: Long,
    val transactionType: TransactionType
)
```

Then update:

```text
SpendingHeatmapEngine
LocationInsightsEngine
AreaSpendEngine
TravelPatternEngine
```

to consume `LocatedMoneyExpense` or `MoneyAggregate`.

### Rule

```text
if conversion missing:
  exclude from normalized heatmap intensity
  include warning/partial count
```

### Tests

```text
LocationHeatmapMissingRatePartialTest
LocationInsightsExcludeDepositsTest
LocationInsightsNormalizedAmountTest
```

---

# 6. Phase 3 — Assistant and Natural Language engines

Do this after map because both need the same data-quality thinking.

## PR-E7 — Assistant result data quality

Current issue:

```text
assistant excludes failed conversions but result does not say partial
```

### Implement

Add:

```kotlin
data class FinancialQueryDataQuality(
    val isPartial: Boolean = false,
    val warnings: List<String> = emptyList(),
    val excludedCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0
)
```

Add to result models:

```kotlin
FinancialQueryResult.Summary(..., dataQuality)
FinancialQueryResult.Breakdown(..., dataQuality)
FinancialQueryResult.TransactionList(..., dataQuality)
FinancialQueryResult.Largest(..., dataQuality)
```

### Tests

```text
AssistantLargestMissingRatePartialWarningTest
AssistantSummaryMissingRateWarningTest
AssistantBreakdownPartialWarningTest
```

---

## PR-E8 — Currency-aware amount filters

Current issue:

```text
minAmount/maxAmount compare raw effectiveAmount across all currencies
```

### Implement

Extend interpreted query model:

```kotlin
data class ExtractedAmountFilter(
    val amount: Double,
    val currency: String?,
    val operator: AmountOperator
)
```

Policy:

```text
If currency specified:
  compare each transaction after converting to that currency or home currency.

If no currency specified:
  interpret amount as home currency and compare normalized effective amounts.

If conversion fails:
  exclude row and mark partial.
```

Do not push this into SQL initially. Fetch candidate period/type rows, normalize in memory, then filter. Later optimize.

### Tests

```text
AssistantAmountFilterUsdQueryTest
AssistantAmountFilterHomeCurrencyDefaultTest
AssistantAmountFilterMissingRatePartialTest
```

---

## PR-E9 — Legacy NL critical fixes

Tracker still has:

```text
W14 merchant extraction broken
W15 parsed filters ignored
W16 amount filter currency unsafe
W30 broad paging
W31 unstable offset paging
```

### Implement in order

1. Merchant extraction on original query, not lowercase.
2. Apply category filters or stop displaying them as applied.
3. Apply location filters or mark unsupported.
4. Use currency-aware amount filter helper from PR-E8.
5. Replace offset paging with keyset or bounded in-memory after filtered query.

### Tests

```text
LegacyNlMerchantExtractionCaseInsensitiveTest
LegacyNlCategoryFilterAppliedTest
LegacyNlLocationFilterAppliedOrUnsupportedTest
LegacyNlAmountFilterCurrencyAwareTest
LegacyNlNoRawMixedCurrencyTotalTest
```

---

# 7. Phase 4 — Analytics consistency

This is probably the biggest remaining engine design area. Do not patch individual analytics engines randomly.

## PR-E10 — `NormalizedAnalyticsInput`

Create one canonical input:

```kotlin
data class NormalizedAnalyticsInput(
    val period: PeriodRange,
    val homeCurrency: String,
    val includedExpenses: List<NormalizedExpense>,
    val excludedExpenses: List<ExcludedExpense>,
    val dataQuality: AnalyticsDataQuality
)

data class NormalizedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalCurrency: String,
    val normalizedAmount: Double,
    val normalizedCurrency: String,
    val date: Long,
    val merchantKey: String?,
    val categoryId: Long?,
    val transactionType: TransactionType,
    val ownership: OwnershipFields
)
```

Assembler:

```kotlin
AnalyticsInputAssembler.build(period)
```

Uses:

```text
ExpenseRepository query once
AnalyticsCurrencyNormalizer / CurrencyConverter
spending/deposit/type filters
data quality report
```

### Tests

```text
NormalizedAnalyticsInputMixedCurrencyTest
NormalizedAnalyticsInputMissingRateExcludesAndWarnsTest
NormalizedAnalyticsInputPeriodRangeTest
```

---

## PR-E11 — Migrate analytics engines to canonical input

Migrate in this order:

```text
1. TotalsAggregationEngine
2. InsightsEngine
3. SpendingPersonalityClassifier
4. AdvancedAnalyticsEngine
5. CategoryAnalytics
6. MerchantAnomaly / MerchantIntelligence
7. Location analytics
```

Rule:

```text
engines should not query raw expenses themselves
engines should not call CurrencyConverter themselves unless explicitly responsible
engines consume NormalizedAnalyticsInput
```

### Tests

```text
AnalyticsEnginesSameInputSameTotalsTest
SpendingPersonalityCurrencySafeTest
AnalyticsConfidencePenaltyTest
DailyChartExplicitPeriodBucketsTest
```

---

## PR-E12 — Forecast quality actuals first

You already planned this.

Populate `ForecastInput.dataQuality` from normalized actuals.

Do not start planned/recurring yet unless actuals are stable.

### Tests

```text
ForecastActualQualityMissingRateTest
FinancialWeatherPartialForecastWarningTest
```

---

# 8. Phase 5 — Categorization and merchant normalization

These are mostly correctness/invariant fixes.

## PR-E13 — DAO result contracts

Fix:

```text
C01 alias conflict
C05 MerchantCategoryDao.insert returns Unit
C06 ambiguous normalizedCanonicalName
```

### Implement

For every insert that can conflict:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(...): Long
```

Then:

```text
-1L means ignored/conflict
```

Alias linking:

```text
check rawName + normalizedKey conflict before insert
return AliasLinkResult.Conflict(existing)
```

### Tests

```text
MerchantAliasConflictDetectedTest
MerchantCategoryInsertConflictResultTest
CanonicalNameAmbiguityTest
```

---

## PR-E14 — Cache invalidation and stats

Fix:

```text
C04 categorization cache stale
C08 merchant stats not consistently updated
C12 semantic collisions
```

### Implement

Centralize writes:

```text
CategoryMappingWriter
MerchantLearningWriter
```

Every mapping write emits:

```text
CategoryMappingChanged
MerchantMappingChanged
```

Invalidates:

```text
category cache
merchant alias cache
semantic keyword cache
```

Post-commit expense creation/update should update canonical stats only after transaction lifecycle commit.

### Tests

```text
CategoryMappingInvalidatesCacheTest
MerchantStatsUpdatedAfterCommittedExpenseTest
SemanticKeywordCollisionPolicyTest
```

---

# 9. Phase 6 — Groups engine

Groups have several P0s. Do them as one coherent group sprint.

## PR-E15 — Group invariants and lifecycle

Fix:

```text
G01 current-user member key invariant
G02 side effects inside outer txn
G03 linked expense normalization bypasses lifecycle
G08 hard delete bypasses coordinator
G09 direct member delete bypasses validation
```

### Implement

Create:

```kotlin
GroupLifecycleCoordinator
```

Methods:

```text
createGroup()
addMember()
removeMember()
addExpense()
updateLinkedExpenseOwnership()
archiveGroup()
deleteGroupPermanently()
recordSettlement()
```

Rules:

```text
current user member currentUserGroupKey = groupId
linked expense updates use TransactionLifecycleCoordinator.updateOwnership()
side effects are deferred until after transaction commit
hard delete route is explicit and guarded
```

### Tests

```text
GroupCurrentUserKeyInvariantTest
GroupAddExpenseDeferredSideEffectsTest
GroupLinkedExpenseUsesTransactionLifecycleTest
GroupArchiveVsDeleteContractTest
```

---

## PR-E16 — Group currency and settlement policy

Pick one now:

### Recommended short-term policy

```text
Groups are single-currency.
All group expenses must be in group currency.
Legacy mixed-currency rows show partial/unsupported warning.
```

Implement:

```text
reject adding group expense if currency != group.currency
settlements use group.currency
```

Long-term multi-currency settlements can be deferred.

### Tests

```text
GroupRejectsForeignCurrencyExpenseTest
GroupSettlementCurrencyContractTest
GroupMixedCurrencyLegacyWarningTest
```

---

# 10. Phase 7 — Warranty and subscription engines

## PR-E17 — Warranty / return windows

Fix:

```text
W02 return-window refund currency
W20 warranty end-date semantics
W21 manual receipt hardcodes EUR
```

### Implement

Return window:

```kotlin
refundAmount: Double?
refundCurrency: String?
```

When linked to expense:

```text
default refundCurrency = expense.currency
```

Warranty date semantics:

```text
startInclusive
endExclusive
```

Manual receipt fallback:

```text
homeCurrency from CurrencySettingsRepository
```

### Tests

```text
ReturnWindowRefundCurrencyFromExpenseTest
WarrantyEndDateHalfOpenTest
ManualReceiptFallbackHomeCurrencyTest
```

---

## PR-E18 — Subscription creation and price history

Fix:

```text
W07 price change atomic
W22 subscription createdAt/currency/validation
W23 candidate fixed millis next date
```

### Implement

Create command:

```kotlin
data class CreateSubscriptionCommand(
    val merchant: String,
    val amount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val startDate: Long
)
```

Repository enforces:

```text
amount > 0
currency valid
createdAt/updatedAt set
baseline price history recordedAt set
```

Price update:

```text
database.withTransaction {
  insert price history
  update subscription amount/currency
}
```

Candidate next date:

```text
RecurrenceCalculator.nextOccurrence(lastSeen, frequency)
```

### Tests

```text
SubscriptionCreateValidationTest
SubscriptionPriceUpdateAtomicTest
SubscriptionCandidateCalendarNextDateTest
```

---

# 11. Phase 8 — Investment engine

## PR-E19 — Investment atomicity and validation

Fix:

```text
I02 price update not atomic
I07 timestamps/quantity/price validation
I06 DAO aggregate mismatch
```

### Implement

Investment repository command:

```kotlin
AddInvestmentCommand(
    symbol,
    quantity,
    purchasePrice,
    currency,
    fees,
    purchaseDate
)
```

Validate:

```text
quantity > 0
price > 0
fees >= 0
currency valid
createdAt > 0
```

Price update:

```text
withTransaction {
  insert value history
  update current price
}
```

Raw DAO aggregate methods:

```text
either include fees consistently or deprecate and guard
```

### Tests

```text
InvestmentAddValidationTest
InvestmentPriceUpdateAtomicTest
InvestmentDaoAggregateMatchesTrackerTest
```

---

## PR-E20 — Portfolio history

Fix:

```text
I03 portfolio history undercounts days
```

### Implement

For each date bucket:

```text
carry forward latest known value per holding
sum holdings per day
```

Do not only count holdings with price update that day.

### Tests

```text
PortfolioHistoryCarriesForwardHoldingValueTest
PortfolioHistoryMissingPriceMarkedStaleTest
```

Lot ledger remains deferred:

```text
I04 add InvestmentTransaction table
```

Do not do that unless you are ready for a design sprint.

---

# 12. Phase 9 — Tax and business engines

## PR-E21 — Tax settings and mileage

Fix:

```text
T02 mileage deduction fallback if not truly fixed
T03 selected tax country persistence
T09 fiscal year assumptions
```

### Implement

```kotlin
data class TaxSettings(
    val countryCode: String,
    val filingCurrency: String,
    val fiscalYearStartMonth: Int,
    val fiscalYearStartDay: Int
)
```

Repository:

```text
TaxSettingsRepository
```

TaxEstimator consumes settings.

### Tests

```text
TaxCountryPersistenceTest
FiscalYearRangeTest
MileageDeductionFallbackTest
```

---

## PR-E22 — Business reports currency and CSV safety

Fix:

```text
T05 euro formatting
T06 raw mixed-currency reports
T07 CSV formula safety
T10 business/tax updates bypass lifecycle
```

### Implement

Business report fields:

```kotlin
deductibleTotal: MoneyAggregate
incomeTotal: MoneyAggregate
vatEstimate: MoneyAggregate? or EstimatedTaxAmount
```

CSV sanitizer:

```text
neutralize = + - @ tab CR LF leading spaces
```

Business/tax expense field updates:

```kotlin
TransactionLifecycleCoordinator.updateBusinessTaxFields(...)
```

### Tests

```text
BusinessReportMoneyAggregateTest
BusinessCsvFormulaInjectionTest
BusinessTaxLifecycleUpdateTest
```

---

# 13. Phase 10 — Remaining money policy / guards

## PR-E23 — No new raw financial aggregates guard

Add CI script:

```text
check_raw_money_aggregates.kts
```

Flag patterns:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
CurrencyFormatter.format(rawDouble, homeCurrency)
total: Double in public engine result
```

Allowlist:

```text
MoneyAggregateBuilder
CurrencyConverter
local non-financial chart internals with explicit comment
tests
```

Do not make this too strict initially. Start as warning/manual, then make CI required.

---

## PR-E24 — Time guard

Add guard for:

```text
System.currentTimeMillis()
Date()
Calendar.getInstance()
Instant.now()
LocalDate.now()
```

Allowlist:

```text
TimeProvider
platform adapters
tests
```

This prevents backsliding.

---

# 14. Testing suite architecture

Build tests in layers.

## Layer 1 — primitive unit tests

```text
MoneyAggregateBuilderTest
ConvertedMoneyTest
CurrencyCodeValidationTest
PeriodRangeTest
AmountParserTest
```

## Layer 2 — DAO integration tests

```text
ExpenseDaoAggregateFilterTest
MerchantCategoryConflictTest
MileageDaoFallbackTest
InvestmentDaoAggregateTest
```

Use in-memory Room.

## Layer 3 — engine contract tests

```text
AnalyticsInputAssemblerTest
SharedExpenseBudgetOffsetEngineTest
LocationInsightsMoneyTest
SubscriptionManagerEngineTest
TaxEstimatorMoneyAggregateTest
InvestmentTrackerAggregateTest
```

## Layer 4 — lifecycle/coordinator tests

```text
GroupLifecycleCoordinatorTest
TransactionLifecycleBusinessTaxFieldsTest
RecurringMarkPaidLifecycleTest
WarrantyLifecycleEventTest if implemented
```

## Layer 5 — scenario tests

Golden scenarios:

```text
MixedCurrencyCoreFinancialScenarioTest
PrivacyCloudLocationDeniedScenarioTest
ReceiptReviewSubscriptionScenarioTest
GroupSharedBudgetScenarioTest
TaxBusinessMileageScenarioTest
AssistantQueryCurrencyScenarioTest
BackupRestoreMoneyIntegrityScenarioTest
```

These should assert:

```text
no fake totals
partial warnings propagated
events created
privacy-denied routes do not call provider
side effects occur after commit
```

---

# 15. Recommended PR order

Use this exact order if possible:

```text
PR-E0  Tests for 8243fc0/33d31b0
PR-E1  MoneyAggregate diagnostics
PR-E2  ConvertedMoney failure semantics
PR-E4  Real map GPS privacy gate
PR-E5  No raw fallback map markers
PR-E6  LocatedMoneyExpense heatmap/insights
PR-E7  Assistant result dataQuality
PR-E8  Currency-aware assistant amount filters
PR-E9  Legacy NL critical fixes
PR-E10 NormalizedAnalyticsInput
PR-E11 Migrate analytics engines
PR-E12 Forecast actual data quality
PR-E13 Merchant/category DAO contracts
PR-E14 Merchant/category cache + stats
PR-E15 Group lifecycle coordinator
PR-E16 Group currency/settlement policy
PR-E17 Warranty/return-window fixes
PR-E18 Subscription correctness
PR-E19 Investment atomicity/validation
PR-E20 Portfolio history
PR-E21 Tax settings/mileage
PR-E22 Business reports/csv/lifecycle
PR-E23 Raw money aggregate guard
PR-E24 Time guard
```

If this feels too big, split into milestones:

```text
Milestone A: Money + Map + Assistant
Milestone B: Analytics + Forecast
Milestone C: Categorization + Groups
Milestone D: Warranty + Subscription + Investment + Tax
Milestone E: Guards + Golden Scenarios
```

---

# 16. What to defer intentionally

Do not force these now unless you want a design sprint:

```text
MoneyAmount BigDecimal/minorUnits rewrite
canonical export/import schema
Investment lot ledger
WarrantyLifecycleEvent table
Cloud AI audit event table
full multi-currency group settlements
official tax-rate provider
full BackupPrivacyMode migration if not already in scope
```

Mark them as:

```text
DEFERRED DESIGN
```

not TODO-only bug.

---

# 17. Definition of “engine stable”

I would call the engine layer stable when:

```text
1. No enabled P0 bug is TODO-only.
2. Every TODO-only P0 is either fixed, feature-hidden, or design-deferred.
3. MoneyAggregate is used for public financial totals.
4. No enabled cloud/location provider bypasses PrivacyGate.
5. No engine raw-fallbacks failed currency conversion as home currency.
6. Analytics, dashboard, forecast, and assistant share data-quality semantics.
7. Lifecycle coordinators own user-visible financial mutations.
8. Engine tracker counts are internally consistent.
9. Golden scenario tests pass.
10. CI guards prevent new lifecycle, money, time, and privacy bypasses.
```

---

# 18. My practical recommendation

Do not continue adding many code fixes without tests.

Your next three PRs should be:

```text
1. Contract tests for MoneyAggregateBuilder + DAO filters + assistant/map changes.
2. Finish map privacy/no-raw-fallback normalization.
3. Add assistant result dataQuality + currency-aware amount filters.
```

Then move to:

```text
NormalizedAnalyticsInput
```

That will give you a strong foundation for the rest.

---

# 19. Sources reviewed

Commits:

- https://github.com/panospao7/Cost-agregator/commit/8243fc077e6e228623c161bc5b7abbaddf333799
- https://github.com/panospao7/Cost-agregator/commit/33d31b013c2f06bd59749af456e6249fdb3d7f16

Key files:

- `ENGINE_ISSUES_MASTER_TRACKER.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/33d31b013c2f06bd59749af456e6249fdb3d7f16/docs/analyses%20and%20debug%20master/ENGINE_ISSUES_MASTER_TRACKER.md

- `MoneyAggregateBuilder.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `MoneyAggregate.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyMappers.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyMappers.kt

- `SpendingMapViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `ExecuteFinancialQueryUseCase.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt

- `ExpenseDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/33d31b013c2f06bd59749af456e6249fdb3d7f16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt