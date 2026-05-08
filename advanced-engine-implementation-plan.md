# Advanced Engine Implementation Plan

Goal:

```text
Finish or contain the remaining advanced engines without blocking the core app forever.
```

Advanced areas:

```text
1. Groups / shared expenses
2. Tax / business / mileage
3. Investment
4. Legacy Natural Language / Smart Search
5. Advanced analytics / personality / forecast
6. Warranty / subscription advanced lifecycle
7. Guards / migrations / scenario tests
```

---

# 0. First decision: implement vs contain

Before coding, classify each advanced feature:

```text
IMPLEMENT NOW
BETA / CONTAIN
DEFERRED DESIGN
HIDE / COMING SOON
```

Recommended:

| Area | Recommendation |
|---|---|
| Groups | Beta unless settlement/lifecycle finished |
| Tax/business | Estimate-only/Beta |
| Investment | Beta unless ledger/add/edit flows complete |
| Legacy NL | Beta or route to Assistant |
| Advanced analytics/personality | Partial-aware Beta |
| Warranty/subscription | Implement core lifecycle, Beta advanced automation |
| Guards/migrations | Implement now |

---

# 1. Groups / shared expenses

## Current issue summary

Remaining:

```text
persistent settlements missing
GroupLifecycleCoordinator incomplete/removed
hard delete/member delete lifecycle gaps
group lifecycle/audit events missing
single-currency policy partly enforced
multi-currency settlement deferred
linked expense ownership partially fixed
```

## PR-G1 — Group settlement persistence

Add entity:

```kotlin
@Entity(tableName = "group_settlements")
data class GroupSettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amount: Double,
    val currency: String,
    val createdAt: Long,
    val createdBy: Long?,
    val linkedExpenseId: Long? = null,
    val status: SettlementStatus = SettlementStatus.RECORDED
)
```

DAO:

```kotlin
insertSettlement()
getSettlementsForGroup()
deleteSettlement()
```

Repository method:

```kotlin
recordSettlement(groupId, fromMemberId, toMemberId, amount, currency)
```

Rules:

```text
amount > 0
currency == group.currency
from != to
members belong to group
group is active
```

Acceptance:

```text
Settle action changes durable state.
Balances subtract recorded settlements.
```

Tests:

```text
GroupSettlementPersistsTest
GroupSettlementUpdatesBalanceTest
GroupSettlementRejectsForeignCurrencyTest
GroupSettlementRejectsNonMemberTest
```

---

## PR-G2 — GroupLifecycleCoordinator

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
archiveGroup()
deleteGroupPermanently()
recordSettlement()
updateLinkedExpenseOwnership()
```

Rules:

```text
all group mutations write group event/audit
financial linked-expense changes route through TransactionLifecycleCoordinator
side effects deferred after transaction commit
hard delete only after archived
member removal blocked if unsettled balance exists
```

Add event entity if missing:

```kotlin
GroupLifecycleEventEntity(
    groupId,
    eventType,
    payloadJson,
    createdAt,
    source
)
```

Acceptance:

```text
No group financial mutation bypasses lifecycle.
```

Tests:

```text
GroupLifecycleEventWrittenTest
GroupLinkedExpenseUsesTransactionLifecycleTest
GroupHardDeleteRequiresArchivedTest
GroupRemoveMemberWithBalanceBlockedTest
```

---

## PR-G3 — Group currency policy finalization

Short-term policy:

```text
Groups are single-currency.
```

Implement:

```text
group expense currency must equal group.currency
settlements use group.currency
legacy mixed-currency rows mark group as partial/unsupported
```

Do not implement full multi-currency settlement now.

Acceptance:

```text
Group totals cannot raw-sum mixed currencies.
```

Tests:

```text
GroupRejectsForeignCurrencyExpenseTest
GroupLegacyMixedCurrencyWarningTest
```

Deferred:

```text
full multi-currency group settlements
```

---

# 2. Tax / business / mileage

## Current issue summary

Remaining:

```text
TaxSettingsRepository exists but not fully consumed
business reports need MoneyAggregate end-to-end
CSV formula safety needs real tests
business/tax updates need lifecycle path
fiscal year assumptions need tests
official tax provider deferred
mileage UI/engine likely partial
```

## PR-T1 — TaxSettings integration

Ensure TaxEstimator consumes:

```kotlin
TaxSettings(
    selectedCountry,
    filingCurrency,
    fiscalYearStartMonth,
    fiscalYearStartDay
)
```

Rules:

```text
no hardcoded default country in estimates
fiscal year range comes from settings
filingCurrency controls tax report display currency
```

Acceptance:

```text
Changing tax country/settings changes estimator output.
Settings persist across app restart.
```

Tests:

```text
TaxSettingsPersistenceTest
TaxEstimatorUsesSelectedCountryTest
FiscalYearRangeFromSettingsTest
TaxFilingCurrencyTest
```

---

## PR-T2 — Business report MoneyAggregate

Business reports should return:

```kotlin
BusinessReportSummary(
    val deductibleExpenses: MoneyAggregate,
    val businessIncome: MoneyAggregate,
    val vatEstimate: MoneyAggregate?,
    val categoryBreakdown: List<BusinessCategoryAggregate>,
    val dataQuality: TaxReportDataQuality
)
```

Rules:

```text
no raw sumOf(amount)
failed conversions excluded + warning
stale rates warning
category totals sum to included total
```

Acceptance:

```text
Business report never displays fake single-currency total.
```

Tests:

```text
BusinessReportMixedCurrencyAggregateTest
BusinessReportMissingRatePartialTest
BusinessCategoryBreakdownSumsToTotalTest
```

---

## PR-T3 — Business/tax lifecycle updates

Add transaction lifecycle API:

```kotlin
TransactionLifecycleCoordinator.updateBusinessTaxFields(
    expenseId,
    isBusinessExpense,
    taxCategory,
    deductiblePercentage,
    source
)
```

Use this instead of direct DAO updates.

Acceptance:

```text
business/tax field edits emit TransactionEvent.UPDATED.
```

Tests:

```text
BusinessTaxUpdateWritesLifecycleEventTest
BusinessTaxUpdateRollbackNoSideEffectTest
```

---

## PR-T4 — CSV formula safety

Implement sanitizer:

```text
if cell starts with = + - @ tab CR LF
prefix with '
```

Apply to:

```text
merchant
category
notes
tax category
any free text
```

Tests:

```text
BusinessCsvFormulaSafetyTest
BusinessCsvSanitizesMerchantTest
BusinessCsvSanitizesNotesTest
```

Deferred:

```text
official tax-rate provider
full jurisdiction-grade tax rules
```

---

# 3. Investment engine

## Current issue summary

Remaining:

```text
InvestmentTransaction table exists but ledger not wired
BUY/SELL/DIVIDEND flows missing
realized gains/cost basis deferred
raw PortfolioSummary consumers may remain
portfolio allocation mixed-currency safety needs verification
```

## PR-I1 — Wire InvestmentTransaction ledger minimally

Use existing table for:

```text
BUY when holding added
PRICE_UPDATE optional value history only, not transaction
DIVIDEND if supported later
SELL deferred if no UI/domain yet
```

When `addHolding()` succeeds:

```text
insert InvestmentEntity
insert InvestmentValueEntity
insert InvestmentTransactionEntity(type=BUY)
all in one transaction
```

Acceptance:

```text
Every holding has a corresponding BUY transaction.
```

Tests:

```text
InvestmentAddHoldingCreatesBuyTransactionTest
InvestmentAddHoldingAtomicLedgerRollbackTest
```

---

## PR-I2 — Portfolio allocation aggregate-safe

Replace raw allocation calculation.

Output:

```kotlin
PortfolioAllocationResult(
    val allocations: List<AllocationItem>,
    val total: MoneyAggregate,
    val dataQuality: InvestmentDataQuality
)
```

Rules:

```text
native currency rows displayed natively
converted total uses MoneyAggregateBuilder
failed conversions produce partial warning
```

Tests:

```text
PortfolioAllocationMixedCurrencyTest
PortfolioAllocationMissingRatePartialTest
```

---

## PR-I3 — Public summary cleanup

Deprecate or restrict:

```kotlin
getPortfolioSummary()
```

Make public path:

```kotlin
getPortfolioSummaryAggregate()
```

or:

```kotlin
PortfolioSummaryUiModel(total: MoneyAggregate, ...)
```

Acceptance:

```text
No UI/engine public path relies on raw mixed-currency PortfolioSummary.totalValue.
```

Tests:

```text
InvestmentPublicSummaryUsesMoneyAggregateTest
```

Deferred:

```text
SELL flow
realized gains
lot ledger
tax-lot accounting
official market data provider
```

---

# 4. Legacy Natural Language / Smart Search

## Current issue summary

Remaining:

```text
raw amount prefilter risk
conversion failure fallback risk
category/location parsed but not reliably applied
overlap with Assistant
```

## PR-NL1 — Containment decision

Choose one:

### Option A — Route to Assistant

Preferred long-term:

```text
Smart Search input → FinancialQueryIntent → Assistant execution engine
```

Legacy parser becomes fallback only.

### Option B — Make legacy path safe

Minimum:

```text
no raw min/max SQL amount prefilter
no raw fallback on conversion failure
category/location unsupported flags
MoneyAggregateBuilder for totals
```

Acceptance:

```text
Legacy NL cannot show fake total or pretend unsupported filters applied.
```

Tests:

```text
LegacyNlNoRawAmountPrefilterTest
LegacyNlNoRawFallbackOnMissingRateTest
LegacyNlUnsupportedFilterFlagTest
LegacyNlMerchantExtractionTest
```

Recommendation:

```text
Contain as Beta and progressively route to Assistant.
```

---

# 5. Advanced analytics / personality / forecast

## Current issue summary

Remaining:

```text
AnalyticsInputAssembler not fully consumed
shared expense state incomplete
stale-rate count incomplete
spending personality needs normalized input
forecast planned/recurring quality partial
dashboard/analytics/assistant consistency tests needed
```

## PR-A1 — Finish AnalyticsInputAssembler

Ensure:

```text
home currency from settings
excluded expenses populated
shared/not-mine/effective fields preserved
missing/stale rate counts populated
transaction type preserved
merchantKey/categoryId preserved
```

Acceptance:

```text
One canonical analytics input can serve dashboard, analytics, assistant, forecast.
```

Tests:

```text
AnalyticsInputPreservesSharedExpenseTest
AnalyticsInputStaleRateCountTest
AnalyticsInputExcludedExpenseIdsTest
```

---

## PR-A2 — Migrate advanced analytics engines

Migrate:

```text
SpendingPersonalityClassifier
AdvancedAnalyticsEngine
Merchant intelligence/anomalies
Category insights
Daily/period bucket engine
```

Rules:

```text
engines consume NormalizedAnalyticsInput
no independent raw ExpenseRepository query
dataQuality propagates
```

Tests:

```text
SpendingPersonalityUsesNormalizedInputTest
AdvancedAnalyticsPartialDataWarningTest
MerchantAnomalyCurrencySafeTest
DailyBucketsSumToTotalTest
```

---

## PR-A3 — Forecast planned/recurring quality

Extend forecast quality beyond actuals:

```text
planned expenses
recurring rules
detected recurring patterns
subscriptions
```

Rules:

```text
foreign future amount converted with current/latest rate and marked approximate
missing rate excluded + partial
stale rate warning
```

Tests:

```text
ForecastPlannedMissingRatePartialTest
ForecastRecurringStaleRateWarningTest
ForecastSubscriptionCurrencyQualityTest
```

---

# 6. Warranty / subscription advanced lifecycle

## Current issue summary

Remaining:

```text
WarrantyLifecycleEvent table may not be fully written
subscription validateAndCreate atomicity final verification
candidate calendar recurrence tests
warranty events for claim/return/confirm/reject
```

## PR-W1 — Wire WarrantyLifecycleEvent writes

On every warranty mutation:

```text
created
confirmed
rejected
claimed
returned
expired
extended
deleted/archived
```

write:

```kotlin
WarrantyLifecycleEventEntity
```

Acceptance:

```text
Warranty history can be reconstructed from events.
```

Tests:

```text
WarrantyCreatedEventTest
WarrantyClaimedEventTest
WarrantyReturnedEventTest
WarrantyRejectedEventTest
```

---

## PR-S1 — Subscription creation atomicity final lock

Ensure:

```text
validateAndCreate wrapped in database.withTransaction
isSubscription=true
baseline price history created atomically
candidate accept uses validateAndCreate
```

Tests:

```text
SubscriptionValidateAndCreateAtomicTest
SubscriptionCreatedIsSubscriptionTest
SubscriptionBaselineRollbackTest
SubscriptionCandidateAcceptUsesValidateAndCreateTest
```

---

# 7. Guards / migrations / scenario tests

## PR-GUARD1 — Real guard self-tests

For each guard:

```text
raw money guard
direct time guard
lifecycle bypass guard
privacy/cloud guard
```

add seeded violation tests that:

```text
create temp file with violation
run script
assert non-zero exit
```

Acceptance:

```text
guards are not just present; they fail on seeded violations.
```

Tests:

```text
RawMoneyGuardSeededFailureTest
DirectTimeGuardSeededFailureTest
LifecycleGuardSeededFailureTest
```

---

## PR-MIG1 — Real migration tests

Add:

```text
117→118 creates warranty_lifecycle_events
118→119 creates investment_transactions
117→119 preserves existing data
fresh 119 has all new tables
```

Tests:

```text
Migration117To118WarrantyEventsTableTest
Migration118To119InvestmentTransactionsTableTest
Migration117To119PreservesExpensesTest
Fresh119SchemaTest
```

---

## PR-SCENARIO1 — Advanced golden scenarios

Add:

```text
GroupSettlementScenarioTest
TaxBusinessScenarioTest
InvestmentPortfolioScenarioTest
LegacyNlAssistantConsistencyScenarioTest
AdvancedAnalyticsPartialDataScenarioTest
WarrantySubscriptionLifecycleScenarioTest
```

Each scenario asserts:

```text
no fake totals
events written
privacy respected
partial warnings surfaced
atomic rollback works
```

---

# 8. Recommended execution order

Do this order:

```text
1. Subscription atomicity final lock
2. Assistant/NL containment or routing
3. AnalyticsInputAssembler completion
4. Advanced analytics migration
5. Group settlement persistence
6. GroupLifecycleCoordinator
7. Warranty lifecycle event writes
8. Investment ledger minimal BUY wiring
9. Investment allocation MoneyAggregate
10. TaxSettings full integration
11. Business report MoneyAggregate + CSV safety
12. Guard self-tests
13. Migration integrity tests
14. Advanced golden scenarios
```

---

# 9. If you want to minimize scope

If the goal is “core stable + advanced beta,” do only:

```text
1. Subscription atomicity
2. Assistant unified helper
3. Legacy NL beta containment
4. AnalyticsInputAssembler enough for dashboard/analytics
5. Guard self-tests
6. Migration tests
7. Disable/label group settlement, tax, investment ledger as beta
```

Then defer:

```text
GroupLifecycleCoordinator
full tax/business report
investment ledger
advanced analytics personality migration
```

---

# 10. Final definition of done for advanced engines

Advanced engines are finalized when:

```text
Groups:
  settlements persist
  group lifecycle events exist
  linked expense changes use transaction lifecycle
  currency policy enforced

Tax/business:
  TaxSettings drives estimator
  business reports use MoneyAggregate
  CSV is sanitized
  business/tax edits use lifecycle

Investment:
  basic ledger wired
  allocation/summary currency-safe
  raw summaries contained
  history carry-forward tested

Legacy NL:
  safe or contained
  no raw amount prefilter
  unsupported filters explicit

Advanced analytics:
  normalized input consumed
  partial/stale/missing-rate quality propagated

Warranty/subscription:
  lifecycle events written
  subscription creation atomic

Guards/migrations:
  seeded guard failures tested
  migration integrity tested
```