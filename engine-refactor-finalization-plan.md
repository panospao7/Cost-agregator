# Engine Refactor Finalization Plan

Goal:

```text
Finish the engine stabilization/refactor so the backend/domain layer can be called stable before moving deeply into UI.
```

Final engine state should mean:

```text
1. no enabled P0/P1 engine TODO is TODO-only
2. all public financial totals are currency-safe
3. failed conversions never masquerade as home currency
4. privacy gates protect cloud/location/AI routes
5. assistant, analytics, dashboard, forecast share data-quality semantics
6. user-visible mutations go through lifecycle/coordinator APIs
7. atomic writes roll back correctly
8. guard scripts prevent regressions
9. golden scenario tests pass
```

---

# Phase 0 — Freeze and reconcile

## PR-0 — Tracker reconciliation

Before more code, fix the tracker.

Statuses:

```text
FIXED
PARTIAL
TODO_ONLY
CONTAINED
DEFERRED_DESIGN
WONT_FIX
```

Rules:

```text
TODO comment only != fixed
implementation plan comment != fixed
partial migration != fixed
feature hidden/disabled = contained
```

Expected reconciliation:

```text
Assistant amount filters → PARTIAL
Legacy NL → PARTIAL/TODO_ONLY
Analytics canonical input → PARTIAL
Groups → TODO_ONLY or CONTAINED
Tax → TODO_ONLY or CONTAINED
Guards → PARTIAL until real scanning exists
Golden scenario tests → PARTIAL until real assertions exist
```

Acceptance:

```text
tracker counts match reality
no optimistic “fixed” status for plan-only work
```

---

# Phase 1 — Assistant and legacy search consistency

This is the highest remaining cross-engine inconsistency.

## PR-1 — Unified assistant filtering helper

Create:

```kotlin
data class AssistantFilterResult(
    val rows: List<ExpenseWithCategory>,
    val dataQuality: FinancialQueryDataQuality
)
```

Implement:

```kotlin
assistantFilteredExpensesCurrencyAware(intent, period)
```

Rules:

```text
push date/type/category/merchant/ownership to DAO
do not push minAmount/maxAmount to DAO
interpret amount filter as home currency unless query specifies currency
convert each expense using convertAsOf(expense.date)
failed conversion = exclude + partial warning
```

Use this helper in:

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
No assistant query path passes minAmount/maxAmount directly to repository.
All assistant result types carry non-default dataQuality when rows are excluded.
```

Tests:

```text
AssistantListAmountFilterCurrencyAwareTest
AssistantCountAmountFilterCurrencyAwareTest
AssistantTotalAmountFilterCurrencyAwareTest
AssistantLargestAmountFilterCurrencyAwareTest
AssistantBreakdownAmountFilterCurrencyAwareTest
AssistantMissingRatePartialWarningTest
AssistantSameIntentResultsConsistentTest
```

---

## PR-2 — Legacy Natural Language minimum safety

Fix legacy NL enough that it is not misleading.

Required:

```text
merchant extraction uses original query
no raw SQL min/max amount prefilter
no raw fallback on conversion failure
single non-home total uses MoneyAggregateBuilder
category/location either applied or explicitly marked unsupported
```

Short-term acceptable policy:

```text
category/location filters parsed but marked unsupported
```

Better policy:

```text
apply category filter by category ID/name
location can remain unsupported until map/location query design exists
```

Acceptance:

```text
Legacy NL never compares raw USD/GBP/EUR amounts as if same currency.
UI/result model knows if parsed filters were unsupported.
```

Tests:

```text
LegacyNlMerchantExtractionOriginalCaseTest
LegacyNlNoRawAmountPrefilterTest
LegacyNlNoRawFallbackOnMissingRateTest
LegacyNlSingleNonHomeConvertsToHomeTest
LegacyNlCategoryUnsupportedOrAppliedTest
```

---

# Phase 2 — Analytics canonical migration

## PR-3 — Make AnalyticsInputAssembler production-ready

Current assembler is a foundation. Make it real.

Convert from object/static helper to injectable class:

```kotlin
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
)
```

Output:

```kotlin
data class NormalizedAnalyticsInput(
    val period: PeriodRange,
    val homeCurrency: String,
    val includedExpenses: List<NormalizedExpense>,
    val excludedExpenses: List<ExcludedExpense>,
    val dataQuality: AnalyticsDataQuality
)
```

Fix:

```text
home currency from settings
excludedExpenses populated
stale-rate count populated
missing-rate count populated
shared/not-mine/effectiveAmount semantics preserved
transaction type preserved
merchantKey/categoryId preserved
```

Acceptance:

```text
No hardcoded EUR.
Input captures enough metadata for category, merchant, personality, forecast, and dashboard engines.
```

Tests:

```text
AnalyticsInputAssemblerHomeCurrencyTest
AnalyticsInputAssemblerMissingRateExclusionTest
AnalyticsInputAssemblerStaleRateWarningTest
AnalyticsInputAssemblerPreservesSharedExpenseTest
AnalyticsInputAssemblerPreservesTransactionTypeTest
```

---

## PR-4 — Migrate analytics engines to canonical input

Migrate in order:

```text
1. totals
2. daily buckets
3. category breakdown
4. merchant breakdown
5. advanced analytics
6. spending personality
7. location analytics if not fully covered by map engine
```

Rules:

```text
engines should not query raw ExpenseRepository themselves
engines should not independently normalize currencies unless their job is normalization
all analytics cards/outputs should use the same normalized input and dataQuality
```

Acceptance:

```text
Dashboard, Analytics, and Assistant totals agree for the same period/filter.
Daily bucket sums equal normalized period total.
Spending personality is hidden/partial or uses normalized input.
```

Tests:

```text
AnalyticsEnginesSameInputSameTotalsTest
DailyBucketsExactPeriodRangeTest
CategoryBreakdownSumsToTotalTest
MerchantBreakdownSumsToTotalTest
SpendingPersonalityCurrencySafeTest
AnalyticsDataQualityPropagatesTest
```

---

# Phase 3 — Map/location final hardening

Map is much improved, but finish the remaining contract.

## PR-5 — Map conversion and warning contract

Ensure:

```text
marker uses convertAsOf(expense.date)
marker failed conversion displays native currency
marker warning is available
heatmap excludes failed conversion rows
insights exclude failed conversion rows
map state exposes conversion warning count/details
```

Remove unused raw paths:

```text
spendingDomainExpenses
heatmapExpenses
any old raw LocatedExpense path if superseded
```

Acceptance:

```text
No map display formats a failed foreign-currency amount as home currency.
Warnings are visible to consumers.
```

Tests:

```text
MapMarkerUsesHistoricalConversionTest
MapMarkerFailedConversionNativeCurrencyTest
MapMarkerSharedExpenseUsesEffectiveAmountTest
HeatmapExcludesFailedConversionsTest
LocationInsightsExcludesFailedConversionsTest
MapConversionWarningStateTest
```

---

# Phase 4 — Forecast quality completion

## PR-6 — Forecast actual quality hardening

Actuals are started. Lock it.

Ensure:

```text
inputCount
includedCount
excludedCount
missingRateCount
staleRateCount
confidencePenalty
warnings
```

Acceptance:

```text
Forecast based on partial actuals is marked partial and confidence is reduced.
```

Tests:

```text
ForecastActualMissingRateQualityTest
ForecastActualStaleRateQualityTest
ForecastConfidencePenaltyTest
```

---

## PR-7 — Forecast planned/recurring quality

Only after actual quality is stable.

Implement future-money normalizer:

```kotlin
ForecastMoneyNormalizer.normalizeFutureAmount(...)
```

Policy:

```text
same currency = exact
foreign with usable current rate = approximate
foreign with stale rate = warning or exclude by policy
missing rate = exclude + partial
```

Apply to:

```text
planned expenses
recurring occurrences
detected recurring patterns
```

Acceptance:

```text
Forecast does not raw-sum future foreign obligations.
```

Tests:

```text
ForecastPlannedMissingRateExcludedTest
ForecastPlannedCurrentRateApproximateTest
ForecastRecurringUnknownCurrencyExcludedTest
ForecastRecurringStaleRateWarningTest
```

---

# Phase 5 — Atomicity and lifecycle finish

## PR-8 — Subscription atomic creation

Fix:

```text
validateAndCreate inserts subscription + baseline price history in one transaction
isSubscription = true
createdAt/updatedAt valid
candidate accept path uses validateAndCreate
calendar recurrence used for next date
```

Acceptance:

```text
No subscription can exist without baseline history when history recording is requested.
```

Tests:

```text
SubscriptionValidateAndCreateAtomicTest
SubscriptionCreatedIsSubscriptionTest
SubscriptionCandidateAcceptUsesValidatedCreateTest
SubscriptionCalendarNextDateTest
```

---

## PR-9 — Investment final atomicity/history

Already improved:

```text
addHolding atomic
updatePrice atomic
```

Still finish:

```text
portfolio history carries forward latest value per holding
portfolio allocation does not depend on raw mixed-currency summary
raw PortfolioSummary deprecated or aggregate-backed
```

Acceptance:

```text
Portfolio value does not drop to zero on days without price updates.
Mixed-currency portfolio does not expose fake home-currency total.
```

Tests:

```text
PortfolioHistoryCarryForwardTest
PortfolioHistoryMissingPriceStaleWarningTest
PortfolioAllocationMixedCurrencyWarningTest
InvestmentPublicSummaryUsesMoneyAggregateTest
```

---

## PR-10 — Warranty/return-window contract

Lock:

```text
nullable refundAmount
refundCurrency from linked expense if available
fallback to home currency, not hardcoded EUR
half-open warranty end-date semantics
```

Acceptance:

```text
Returned item without refund is valid.
Returned item with refund has correct currency.
```

Tests:

```text
WarrantyReturnedNoRefundTest
WarrantyReturnedWithLinkedExpenseCurrencyTest
WarrantyReturnedHomeCurrencyFallbackTest
WarrantyEndDateHalfOpenTest
```

---

# Phase 6 — Categorization and merchant contracts

## PR-11 — DAO conflict contracts

Fix:

```text
MerchantCategoryDao.insert returns Long
insertAll returns List<Long> or structured result
alias insert detects conflict
normalized canonical name ambiguity handled
```

Acceptance:

```text
callers can distinguish inserted vs ignored/conflict
alias conflicts are not silently swallowed
```

Tests:

```text
MerchantCategoryInsertConflictTest
MerchantAliasConflictDetectedTest
CanonicalNameAmbiguityTest
```

---

## PR-12 — Cache invalidation and stats

Implement central writers:

```text
CategoryMappingWriter
MerchantMappingWriter
```

They emit:

```text
CategoryMappingChanged
MerchantMappingChanged
```

Invalidate:

```text
categorization cache
merchant alias cache
semantic keyword cache
```

Stats update:

```text
only after committed transaction lifecycle event
```

Acceptance:

```text
category correction immediately affects future categorization
merchant stats do not update after rolled-back transaction
```

Tests:

```text
CategoryCacheInvalidationTest
MerchantAliasCacheInvalidationTest
MerchantStatsPostCommitTest
MerchantStatsRollbackNoUpdateTest
SemanticKeywordCollisionPolicyTest
```

---

# Phase 7 — Groups

If groups remain enabled, this phase is required.  
If not, contain feature as Beta and disable misleading actions.

## PR-13 — GroupLifecycleCoordinator

Implement:

```kotlin
GroupLifecycleCoordinator
```

Methods:

```text
createGroup
addMember
removeMember
addExpense
archiveGroup
deleteGroupPermanently
recordSettlement
updateLinkedExpenseOwnership
```

Rules:

```text
current-user member invariant enforced
side effects deferred until DB commit
linked expense updates go through TransactionLifecycleCoordinator
hard delete is explicit and guarded
```

Acceptance:

```text
No group-linked financial mutation bypasses lifecycle.
```

Tests:

```text
GroupCurrentUserInvariantTest
GroupAddExpenseDeferredSideEffectsTest
GroupLinkedExpenseUsesTransactionLifecycleTest
GroupArchiveVsHardDeleteTest
```

---

## PR-14 — Group currency and settlement policy

Short-term recommended policy:

```text
groups are single-currency
group expense currency must equal group.currency
settlements persist in group.currency
legacy mixed-currency groups show warning
```

Acceptance:

```text
Group totals and settlements cannot raw-sum mixed currencies.
Settle action creates durable settlement record.
```

Tests:

```text
GroupRejectsForeignCurrencyExpenseTest
GroupMixedCurrencyLegacyWarningTest
GroupSettlementPersistsTest
GroupSettlementUpdatesBalanceTest
```

---

# Phase 8 — Tax/business

If tax is visible, implement. If not, contain as Estimate/Beta.

## PR-15 — TaxSettingsRepository

Implement:

```text
selectedCountry
filingCurrency
fiscalYearStartMonth
fiscalYearStartDay
```

TaxEstimator should consume settings instead of implicit defaults.

Acceptance:

```text
selected tax country survives process/app restart
fiscal year range is explicit and tested
```

Tests:

```text
TaxSettingsPersistenceTest
FiscalYearRangeTest
TaxFilingCurrencyTest
```

---

## PR-16 — Business report currency and CSV safety

Fix:

```text
deductible totals use MoneyAggregate
income totals use MoneyAggregate
category business totals use MoneyAggregate
CSV cells sanitize = + - @ tab CR/LF
business/tax field updates go through TransactionLifecycleCoordinator
```

Acceptance:

```text
business report never raw-sums mixed currency
CSV cannot execute formula injection
business tax edits emit lifecycle event
```

Tests:

```text
BusinessReportMoneyAggregateTest
BusinessCategoryMoneyAggregateTest
BusinessCsvFormulaSafetyTest
BusinessTaxLifecycleUpdateTest
```

---

# Phase 9 — Real CI guards

Current guard tasks are wired but must become real.

## PR-17 — Raw-money guard

Implement scanner for:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
CurrencyFormatter.format(..., homeCurrency)
data class ... total: Double in domain/engine public result
```

Allowlist:

```text
MoneyAggregateBuilder
CurrencyConverter
tests
native-currency row display
explicit @RawMoneyAllowed comment
```

Acceptance:

```text
seeded violation fails
current allowlisted code passes
Gradle check runs guard
```

---

## PR-18 — Time guard

Implement scanner for:

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
explicit @DirectTimeAllowed comment
```

Acceptance:

```text
seeded direct time call fails
Gradle check runs guard
```

---

## PR-19 — Privacy/cloud/provider guard

Implement or harden scanner for cloud providers.

Require provider files to reference:

```text
PrivacyGate / CloudAiGuard
CloudPayloadRedactor
PrivacyAuditLogger or existing PrivacyAuditDao mechanism
```

Acceptance:

```text
new cloud provider without redactor/gate fails guard
```

---

## PR-20 — Lifecycle bypass guard

Ensure direct DAO writes that mutate expenses/receipts/groups/recurring state are either:

```text
inside lifecycle coordinator
inside migration/backfill allowlist
inside test
```

Acceptance:

```text
new direct mutation DAO use in repository/service fails unless allowlisted
```

---

# Phase 10 — Golden scenario tests

Current golden smoke test is infrastructure only. Add real scenarios.

## PR-21 — Mixed currency financial scenario

Seed:

```text
home EUR
EUR purchase
USD purchase with rate
GBP purchase missing rate
shared expense
transfer
deposit
```

Assert:

```text
dashboard/analytics/assistant totals agree
GBP excluded or shown partial
transfer/deposit excluded from spending totals
shared effective amount used
```

---

## PR-22 — Privacy/location/cloud scenario

Seed:

```text
cloud disabled
GPS disabled
external geocoding disabled
```

Assert:

```text
cloud provider not called
GPS provider not called
geocoder not called
audit/privacy decision logged
local fallback used where available
```

---

## PR-23 — Receipt/review/recurring scenario

Seed:

```text
pending review with receipt
recurring bill
receipt scan duplicate
```

Assert:

```text
receipt links transactionally
duplicate links to existing expense or warning
recurring mark-paid/skip semantics correct
events written
```

---

## PR-24 — Groups scenario

Seed:

```text
group
members
group expense
settlement
```

Assert:

```text
group currency enforced
settlement persisted
linked expense updated through lifecycle
balances correct
```

---

## PR-25 — Tax/business/investment scenario

Seed:

```text
business expenses multi-currency
investment holdings multi-currency
tax settings
```

Assert:

```text
business report MoneyAggregate partial if missing rate
investment portfolio not fake home total
tax year range correct
```

---

# Phase 11 — Final cleanup

## PR-26 — Remove stale TODOs

After implementation/deferral:

```text
delete obsolete TODO comments
replace remaining with issue IDs and DEFERRED_DESIGN rationale
```

No hot-path TODO should say:

```text
TODO fix correctness
```

without tracker ID/status.

---

## PR-27 — Final engine audit

Run:

```text
./gradlew test
./gradlew check
all guard scripts
all golden scenarios
```

Then update:

```text
ENGINE_ISSUES_MASTER_TRACKER.md
DEPENDENCY_MAP.md
ARCHITECTURE_DECISIONS.md if present
```

Final tracker should show:

```text
P0/P1 enabled engine issues: 0 TODO_ONLY
P0/P1 enabled partials: 0 or explicitly contained
Deferred design items documented
```

---

# Final recommended execution order

```text
0. Tracker reconciliation
1. Assistant unified filtering/dataQuality
2. Legacy NL minimum safety
3. AnalyticsInputAssembler production version
4. Analytics engine migration
5. Map final warning/cleanup
6. Forecast planned/recurring quality
7. Subscription/investment/warranty finish
8. Categorization/merchant contracts
9. Groups lifecycle/currency policy
10. Tax/business implementation or containment
11. Real CI guards
12. Golden scenario tests
13. TODO cleanup/final audit
```

---

# What can be deferred safely

Do not include these in final engine stabilization:

```text
MoneyAmount BigDecimal/minor-units rewrite
canonical export/import schema
investment lot ledger / realized gains
official tax-rate provider
full multi-currency group settlement engine
dedicated Cloud AI audit table
full backup privacy-mode redesign
```

Mark as:

```text
DEFERRED_DESIGN
```

They are architecture/product design sprints, not stabilization fixes.

---

# Final definition of done

The engine refactor is finalized when:

```text
1. Tracker has zero enabled P0/P1 TODO_ONLY items.
2. Public financial totals use MoneyAggregate or native-currency display.
3. No failed conversion is formatted as home currency.
4. Assistant, Analytics, Forecast, Dashboard share data-quality semantics.
5. Legacy NL is either safe or contained.
6. Map/location respects privacy and normalized-money rules.
7. Subscription, investment, warranty writes are atomic.
8. Group and tax features are either implemented or contained.
9. Categorization conflicts/cache invalidation are deterministic.
10. Guard scripts fail on seeded violations.
11. Golden scenario tests pass.
12. Remaining items are explicitly DEFERRED_DESIGN.
```