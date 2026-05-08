# Detailed Implementation Plan for Remaining Engine TODOs

## Goal

Turn TODOs into one of four states:

```text
FIXED          real implementation + tests
CONTAINED      feature disabled/beta/guarded
DEFERRED       design sprint needed, documented clearly
DELETED        obsolete TODO removed
```

Do not keep vague TODOs in hot paths.

---

# 1. First pass — TODO triage cleanup

## PR-T0 — Normalize the tracker

Create columns:

```text
ID
Area
Severity
Current status
Required behavior
Implementation owner
Feature enabled?
Blocking release?
Test required
```

Use statuses only:

```text
OPEN
IN_PROGRESS
FIXED
CONTAINED
DEFERRED_DESIGN
WONT_FIX
```

Avoid:

```text
TODO-only resolved
```

Acceptance:

```text
No TODO-only item is counted as fixed.
Every P0/P1 TODO has an owner and next action.
```

---

# 2. Foundation phase — money, time, privacy

## PR-T1 — Finish MoneyAggregate consistency

Fix:

```text
all MoneyAggregate constructors use transaction counts correctly
MoneyAggregate.partial() warning uses failedTransactionCount
MultiConversionAggregate mapper uses same semantics
empty aggregate uses home currency
no single non-home bucket returned as fake home total
```

Tests:

```text
MoneyAggregatePartialWarningTest
MoneyAggregateMapperTransactionCountTest
SingleNonHomeBucketConvertsTest
EmptyAggregateUsesHomeCurrencyTest
```

Acceptance:

```text
No public financial aggregate reports failed bucket count as transaction count.
```

---

## PR-T2 — Raw money aggregate guard

Add CI guard for:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
CurrencyFormatter.format(rawDouble, homeCurrency)
public engine result total: Double
```

Allowlist:

```text
MoneyAggregateBuilder
CurrencyConverter
tests
localized native-currency row display
```

Start as warning, then make required.

---

## PR-T3 — TimeProvider / java.time guard

Guard direct use of:

```text
System.currentTimeMillis()
Calendar.getInstance()
Date()
LocalDate.now()
Instant.now()
```

Allow only:

```text
TimeProvider
platform adapters
tests
```

Acceptance:

```text
New engine code cannot add wall-clock calls silently.
```

---

# 3. Map/location TODOs

## PR-T4 — Finish map money correctness

Fix:

```text
Map marker model carries displayCurrency and originalCurrency
failed conversion displays native currency, not home currency
no fallback raw amount formatted as home currency
isPartial/warning exposed
```

Tests:

```text
MapMarkerMissingRateShowsNativeCurrencyTest
MapMarkerUsesEffectiveAmountTest
MapMarkerSharedExpenseEffectiveAmountTest
```

---

## PR-T5 — Normalize heatmap and insights

Implement:

```text
LocatedMoneyExpense
SpendingHeatmapEngine.computeNormalized()
LocationInsightsEngine.computeNormalized()
```

Rules:

```text
converted/home rows included
failed conversions excluded from normalized totals
partial warning returned
deposits/transfers excluded from spending insights
```

Tests:

```text
HeatmapUsesNormalizedAmountsTest
LocationInsightsMissingRatePartialTest
LocationInsightsSpendingOnlyTest
```

---

# 4. Assistant and legacy natural language TODOs

## PR-T6 — Populate assistant dataQuality

Fix:

```text
largest query
summary/total query
average query
breakdowns
transaction list/count
```

Expose:

```text
isPartial
excludedCount
missingRateCount
staleRateCount
warnings
```

Tests:

```text
AssistantLargestMissingRatePartialTest
AssistantSummaryPartialWarningTest
AssistantBreakdownPartialWarningTest
```

---

## PR-T7 — Currency-aware assistant amount filters

Policy:

```text
amount without currency = home currency
amount with currency = compare in specified currency
failed conversion = excluded + partial warning
```

Do filtering in memory after narrowing by date/type/merchant/category.

Tests:

```text
AssistantAmountFilterHomeCurrencyTest
AssistantAmountFilterUsdTest
AssistantAmountFilterMissingRatePartialTest
```

---

## PR-T8 — Legacy NL minimum correctness

Fix:

```text
merchant extraction uses original query
single non-home total uses MoneyAggregateBuilder
category filters are applied or marked unsupported
location filters are applied or marked unsupported
amount filters use currency-aware helper
```

Tests:

```text
LegacyNlMerchantExtractionTest
LegacyNlSingleNonHomeConvertsTest
LegacyNlCategoryFilterContractTest
LegacyNlAmountFilterCurrencyAwareTest
```

---

# 5. Analytics and forecast TODOs

## PR-T9 — Build AnalyticsInputAssembler

Implement canonical input:

```text
NormalizedAnalyticsInput
includedExpenses
excludedExpenses
dataQuality
period range
home currency
```

Rules:

```text
all analytics engines consume normalized input
raw expenses not re-queried inside engines
conversion failures are explicit
```

Tests:

```text
NormalizedAnalyticsInputMixedCurrencyTest
NormalizedAnalyticsInputMissingRateTest
NormalizedAnalyticsInputPeriodRangeTest
```

---

## PR-T10 — Migrate key analytics engines

Order:

```text
totals
daily buckets
category breakdown
merchant breakdown
spending personality
advanced analytics
location analytics
```

Acceptance:

```text
All analytics sections use the same normalized total and same warnings.
```

Tests:

```text
AnalyticsEnginesSameInputSameTotalsTest
DailyBucketRangeContractTest
SpendingPersonalityCurrencySafeTest
```

---

## PR-T11 — Forecast quality phase 2

You already started actuals. Later add:

```text
planned quality
recurring quality
stale-rate warnings
confidence penalty propagation
```

Do not block core stabilization on this unless forecast is prominent.

Tests:

```text
ForecastActualQualityTest
ForecastPlannedMissingRateTest
ForecastRecurringPartialTest
```

---

# 6. Categorization and merchant TODOs

## PR-T12 — DAO conflict contracts

Fix:

```text
MerchantCategoryDao insert returns Long
alias insert conflict is detectable
canonical name ambiguity handled
```

Tests:

```text
MerchantCategoryInsertConflictTest
MerchantAliasConflictTest
CanonicalNameAmbiguityTest
```

---

## PR-T13 — Cache invalidation and merchant stats

Implement:

```text
CategoryMappingChanged event
MerchantMappingChanged event
cache invalidation after mapping writes
merchant stats update only after committed expense
```

Tests:

```text
CategoryCacheInvalidationTest
MerchantStatsPostCommitTest
SemanticKeywordCollisionPolicyTest
```

---

# 7. Groups TODOs

## PR-T14 — GroupLifecycleCoordinator

Implement coordinator for:

```text
create group
add member
remove member
add expense
archive group
record settlement
linked expense ownership update
```

Rules:

```text
group-linked expense mutations go through TransactionLifecycleCoordinator
side effects deferred until commit
hard delete explicit and guarded
```

Tests:

```text
GroupCurrentUserInvariantTest
GroupLinkedExpenseLifecycleTest
GroupArchiveVsDeleteTest
```

---

## PR-T15 — Group currency and settlement policy

Short-term recommendation:

```text
groups are single-currency
reject foreign-currency group expense
legacy mixed rows show warning
settlements use group currency
```

Tests:

```text
GroupRejectsForeignCurrencyExpenseTest
GroupSettlementPersistsTest
GroupMixedCurrencyLegacyWarningTest
```

---

# 8. Warranty, subscription, investment TODOs

## PR-T16 — Warranty/return-window completeness

Fix:

```text
nullable refundAmount
refundCurrency from linked expense or home currency
no hardcoded EUR fallback
half-open warranty end-date semantics
```

Tests:

```text
ReturnWindowRefundCurrencyTest
WarrantyNoRefundAllowedTest
WarrantyEndDateHalfOpenTest
```

---

## PR-T17 — Subscription atomicity

Fix:

```text
all creation paths use validateAndCreate
price update + price history in one transaction
candidate next date uses calendar recurrence
```

Tests:

```text
SubscriptionAllCreationPathsValidatedTest
SubscriptionPriceUpdateAtomicTest
SubscriptionCandidateCalendarNextDateTest
```

---

## PR-T18 — Investment atomicity/history

Fix:

```text
add holding + initial value in transaction
price update + value history in transaction
portfolio history carries forward latest holding values
raw PortfolioSummary deprecated or aggregate-backed
```

Tests:

```text
InvestmentAddHoldingAtomicTest
InvestmentPriceUpdateAtomicTest
PortfolioHistoryCarryForwardTest
InvestmentAggregateVsRawGuardTest
```

Defer:

```text
lot ledger
realized gains
tax lots
```

---

# 9. Tax/business TODOs

## PR-T19 — TaxSettingsRepository

Implement:

```text
selected country
filing currency
fiscal year start
home/business currency policy
```

Tests:

```text
TaxCountryPersistenceTest
FiscalYearRangeTest
TaxFilingCurrencyTest
```

---

## PR-T20 — Business report money and CSV safety

Fix:

```text
deductible totals use MoneyAggregate
income totals use MoneyAggregate
CSV formula injection sanitized
business/tax expense updates use lifecycle coordinator
```

Tests:

```text
BusinessReportMoneyAggregateTest
BusinessCsvFormulaSafetyTest
BusinessTaxLifecycleUpdateTest
```

---

# 10. CI and regression guards

## PR-T21 — Lifecycle bypass guard

Fail build for new direct calls to lifecycle-bypassing DAO methods outside allowlist.

## PR-T22 — Cloud privacy/redactor guard

Require cloud providers to reference:

```text
PrivacyGate
CloudPayloadRedactor
PrivacyAuditLogger or existing audit mechanism
```

## PR-T23 — Worker guard

Require workers to use:

```text
WorkerExecutionGuard
RestoreMaintenanceMode guard
privacy gate where relevant
```

---

# 11. Golden scenario tests

After the focused PRs, add 5 scenario tests.

```text
MixedCurrencyCoreScenarioTest
PrivacyCloudLocationDeniedScenarioTest
ReceiptReviewRecurringScenarioTest
GroupSharedBudgetScenarioTest
AssistantAnalyticsCurrencyScenarioTest
```

Each should assert:

```text
no fake totals
partial warnings exist
privacy-denied providers not called
lifecycle events written
side effects after commit
```

---

# 12. Recommended execution order

Use this order:

```text
1. Tracker cleanup
2. MoneyAggregate consistency
3. CI raw-money/time guards
4. Map marker/heatmap normalization
5. Assistant dataQuality + amount filters
6. Legacy NL minimum correctness
7. AnalyticsInputAssembler
8. Analytics engine migration
9. Categorization DAO/cache fixes
10. Group lifecycle/currency policy
11. Warranty/subscription/investment atomicity
12. Tax/business report fixes
13. Privacy/lifecycle/worker guards
14. Golden scenario tests
```

---

# 13. What to defer deliberately

Do not do these inside the stabilization sprint:

```text
MoneyAmount BigDecimal/minor-unit rewrite
canonical export/import schema
investment lot ledger
official tax-rate provider
multi-currency group settlement engine
Cloud AI dedicated audit table
full backup privacy mode redesign
```

Mark them:

```text
DEFERRED_DESIGN
```

---

# 14. Definition of done

Engines are stable when:

```text
No enabled P0/P1 TODO is TODO-only.
All public financial totals use MoneyAggregate or native-currency display.
Failed currency conversion is never formatted as home currency.
Privacy gates protect enabled cloud/location providers.
Analytics/forecast/assistant expose partial data quality.
User-visible mutations go through lifecycle coordinators.
CI guards prevent money/time/privacy/lifecycle regressions.
Golden scenario tests pass.
```