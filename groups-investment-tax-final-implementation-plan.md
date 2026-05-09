# Groups / Investment / Tax — Final Implementation Plan

Goal:

```text
Move Groups / Investment / Tax from beta-contained to stable-enough engine state.
```

Remaining core issues:

```text
Groups:
1. member removal balance gate incomplete
2. lifecycle/audit events not persisted
3. hard delete bypasses lifecycle
4. shared budget MoneyAggregate may use different conversion basis than numeric totals

Investment:
5. portfolio allocation raw-sums mixed currencies
6. raw PortfolioSummary still public/leaky
7. portfolio history raw and time-fragile
8. per-investment performance lacks per-row money/dataQuality

Tax:
9. updateBusinessTaxFields() is stub
10. categorized deductions raw-sum mixed currencies
11. derived tax aggregates hide partial quality
12. tax provider scope/metadata needs clarity
```

---

# Phase 0 — Tracker correction

## PR-GIT-0 — Mark true statuses

Update tracker:

```text
Groups → PARTIAL/BETA
Investment → PARTIAL/BETA
Tax → ESTIMATE_ONLY/BETA
```

Specific:

```text
G02 PARTIAL
G03 PARTIAL
G06 PARTIAL
G07 PARTIAL
I03 PARTIAL
I05 PARTIAL
I06 OPEN/PARTIAL
I09 PARTIAL
T01 PARTIAL
T02 FIXED
T03 FIXED/PARTIAL
T04 PARTIAL
T05 PARTIAL
T06 OPEN/PARTIAL
T07 FIXED
T08 PARTIAL
T09 FIXED/PARTIAL
T10 OPEN
```

Acceptance:

```text
No “stable/fixed” status for comment-only or beta-contained code.
```

---

# Phase 1 — Groups

## PR-G1 — Real group net balance calculator

### Problem

Current member removal checks only:

```text
unsettled expenses where member is payer
settlement records involving member
```

It does not compute actual net balance from:

```text
expenses paid
member shares owed
settlements paid/received
```

### Implement

Create:

```kotlin
class GroupBalanceCalculator @Inject constructor(...)
```

Core model:

```kotlin
data class GroupMemberBalance(
    val groupId: Long,
    val memberId: Long,
    val currency: String,
    val paidTotal: Double,
    val owedShareTotal: Double,
    val settlementsPaid: Double,
    val settlementsReceived: Double,
    val netBalance: Double
)
```

Formula:

```text
netBalance = paidTotal - owedShareTotal - settlementsPaid + settlementsReceived
```

Interpretation:

```text
netBalance > 0 → group owes member
netBalance < 0 → member owes group/others
near zero → settled
```

Use epsilon:

```kotlin
private const val BALANCE_EPSILON = 0.01
```

Use only group currency because you enforce single-currency groups.

### Use in removal

In `GroupLifecycleCoordinator.removeMember()`:

```kotlin
val balance = groupBalanceCalculator.calculateMemberBalance(groupId, memberId)
require(abs(balance.netBalance) <= BALANCE_EPSILON) {
    "Cannot remove member with unsettled balance"
}
```

Also block if member is linked to unsettled split metadata.

### Tests

```text
GroupMemberBalancePayerPositiveTest
GroupMemberBalanceDebtorNegativeTest
GroupMemberBalanceAfterSettlementZeroTest
RemoveMemberWithNetDebtBlockedTest
RemoveMemberWithNetCreditBlockedTest
RemoveMemberAfterSettlementAllowedTest
```

Acceptance:

```text
Member removal is based on real net balance, not only settlement rows.
```

---

## PR-G2 — Persistent group lifecycle events

### Problem

`emitLifecycleEvent()` logs only. No durable audit trail.

### Implement

Entity:

```kotlin
@Entity(
    tableName = "group_lifecycle_events",
    indices = [Index("groupId"), Index("eventType"), Index("createdAt")]
)
data class GroupLifecycleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val eventType: String,
    val actorMemberId: Long?,
    val relatedExpenseId: Long?,
    val relatedSettlementId: Long?,
    val payloadJson: String?,
    val createdAt: Long,
    val source: String
)
```

DAO:

```kotlin
insert(event)
getEventsForGroup(groupId)
deleteEventsForGroup(groupId)
```

Event types:

```text
GROUP_CREATED
MEMBER_ADDED
MEMBER_REMOVED
EXPENSE_ADDED
EXPENSE_LINKED
OWNERSHIP_UPDATED
SETTLEMENT_RECORDED
GROUP_ARCHIVED
GROUP_DELETED
```

Migration:

```text
DB version +1
CREATE TABLE group_lifecycle_events...
```

Wire:

```text
createGroup()
addMember()
removeMember()
addExpense()
addExpenseWithLink()
recordSettlement()
archiveGroup()
deleteGroupPermanently()
```

### Tests

```text
GroupCreatedWritesLifecycleEventTest
GroupSettlementWritesLifecycleEventTest
GroupMemberRemovedWritesLifecycleEventTest
GroupLinkedExpenseWritesLifecycleEventTest
GroupLifecycleMigrationCreatesTableTest
```

Acceptance:

```text
Every group mutation creates a durable lifecycle event.
```

---

## PR-G3 — Hard delete lifecycle/containment

### Problem

Hard delete clears shared expense flags directly and deletes group data.

### Option A — Production-safe

Replace direct clear with lifecycle method:

```kotlin
transactionLifecycleCoordinator.updateSharedOwnership(
    expenseId = id,
    newOwnership = OwnershipPersonal,
    sideEffectMode = DEFER
)
```

Then delete group rows.

Write:

```text
GROUP_DELETED
```

event before/after deletion. If events are deleted with group, store deleted-group audit separately or keep events.

### Option B — Containment

If keeping destructive hard delete:

```text
only allow if group.archived = true
only allow with explicit force flag
mark as admin/destructive
do not expose in normal UI
```

Recommended:

```text
Option A for linked expense cleanup + Option B for UI guard.
```

### Tests

```text
HardDeleteRequiresArchivedTest
HardDeleteClearsLinkedExpenseThroughLifecycleTest
HardDeleteWritesDeletedEventTest
HardDeleteWithoutForceRejectedTest
```

---

## PR-G4 — Shared budget MoneyAggregate historical consistency

### Problem

Numeric offsets use:

```text
convertAsOf(expense.date)
```

but aggregate display may use builder with current/latest rates.

### Implement

Add builder method:

```kotlin
MoneyAggregateBuilder.fromConvertedRows(
    convertedRows: List<ConvertedMoneyRow>,
    homeCurrency: CurrencyCode
)
```

Model:

```kotlin
data class ConvertedMoneyRow(
    val originalAmount: Double,
    val originalCurrency: CurrencyCode,
    val convertedAmount: Double?,
    val targetCurrency: CurrencyCode,
    val transactionCount: Int = 1,
    val failureReason: FailureReason? = null
)
```

In `SharedExpenseBudgetOffsetEngine`, reuse the exact conversion results already used for numeric totals.

Do not re-convert buckets.

### Tests

```text
SharedBudgetAggregateMatchesNumericHistoricalTotalTest
SharedBudgetMissingRatePartialAggregateTest
SharedBudgetStaleRateWarningAggregateTest
```

Acceptance:

```text
effectiveBudgetSpend and adjustedSpendAggregate.displayAmount cannot disagree because of conversion basis.
```

---

# Phase 2 — Investment

## PR-I1 — Aggregate-safe portfolio allocation

### Problem

`getPortfolioAllocation()` uses raw `getPortfolioSummary()` totals.

### Implement

New model:

```kotlin
data class PortfolioAllocationResult(
    val items: List<PortfolioAllocationItem>,
    val total: MoneyAggregate,
    val dataQuality: InvestmentDataQuality
)

data class PortfolioAllocationItem(
    val type: InvestmentType,
    val value: MoneyAggregate,
    val percentageOfConvertedTotal: Double?,
    val nativeBuckets: List<MoneyBucket>,
    val isPartial: Boolean
)
```

Algorithm:

```text
1. group active holdings by InvestmentType
2. bucket each group by currency
3. build MoneyAggregate per group
4. build total aggregate
5. percentage = group.displayAmount / total.displayAmount only if both non-partial enough
```

If group conversion fails:

```text
percentageOfConvertedTotal = null
dataQuality warning
```

Deprecate old:

```kotlin
@Deprecated("Currency-unsafe. Use getPortfolioAllocationAggregate().")
fun getPortfolioAllocation()
```

### Tests

```text
PortfolioAllocationSingleCurrencyTest
PortfolioAllocationMixedCurrencyConvertedTest
PortfolioAllocationMissingRatePartialTest
PortfolioAllocationDoesNotUseRawSummaryTest
```

Acceptance:

```text
Portfolio allocation never divides raw USD+EUR values.
```

---

## PR-I2 — Contain raw PortfolioSummary

### Problem

`PortfolioSummary` still exposes raw totals.

### Implement

Option A:

Add aggregate-backed model:

```kotlin
data class PortfolioSummaryAggregateResult(
    val totalValue: MoneyAggregate,
    val totalInvested: MoneyAggregate,
    val totalGainLoss: MoneyAggregate,
    val gainLossPercent: Double?,
    val byType: List<PortfolioAllocationItem>,
    val dataQuality: InvestmentDataQuality
)
```

Replace public callers with aggregate model.

Old method:

```kotlin
@Deprecated(
    message = "Currency-unsafe. Use getPortfolioSummaryAggregateResult().",
    level = DeprecationLevel.ERROR // eventually
)
```

Option B:

Keep raw method internal/private.

### Tests

```text
PublicInvestmentSummaryUsesAggregateTest
RawPortfolioSummaryDeprecatedGuardTest
MixedCurrencySummaryPartialWarningTest
```

Acceptance:

```text
No production UI/domain path uses raw PortfolioSummary as display total.
```

---

## PR-I3 — Portfolio history MoneyAggregate/per-currency

### Problem

History returns:

```text
DailyPortfolioValue(totalValue: Double)
```

and raw-sums across currencies.

### Implement

New:

```kotlin
data class DailyPortfolioValueAggregate(
    val date: Long,
    val value: MoneyAggregate,
    val nativeBuckets: List<MoneyBucket>,
    val dataQuality: InvestmentDataQuality
)
```

Algorithm:

```text
for each day:
  carry forward latest value per holding
  bucket by currency
  convert using convertAsOf(dayEnd or priceDate policy)
  build aggregate
```

Date handling:

```text
use java.time ZoneId
no Calendar
no +24h millis
```

Use:

```kotlin
LocalDate.plusDays(1).atStartOfDay(zone).toInstant()
```

### Tests

```text
PortfolioHistoryCarryForwardAggregateTest
PortfolioHistoryMixedCurrencyPartialTest
PortfolioHistoryDstBoundaryTest
PortfolioHistoryMissingRateWarningTest
```

Acceptance:

```text
Portfolio history is currency-safe and zone-safe.
```

---

## PR-I4 — Per-investment performance money/dataQuality

### Problem

Each `InvestmentPerformance` contains raw values and same portfolio aggregate.

### Implement

```kotlin
data class InvestmentPerformance(
    ...
    val currentValue: MoneyAmount,
    val costBasis: MoneyAmount,
    val unrealizedGain: MoneyAmount,
    val convertedCurrentValue: MoneyAmount?,
    val convertedCostBasis: MoneyAmount?,
    val convertedGain: MoneyAmount?,
    val dataQuality: InvestmentHoldingDataQuality
)
```

If you cannot change existing fields now, add:

```text
currentValueAggregate
costBasisAggregate
gainAggregate
```

for each row.

### Tests

```text
InvestmentPerformancePerRowCurrencyTest
InvestmentPerformanceMissingRatePartialTest
InvestmentPerformanceStalePriceWarningTest
```

---

## PR-I5 — Price staleness policy

### Problem

Stale thresholds are hardcoded and incomplete.

### Implement

Settings:

```kotlin
data class InvestmentSettings(
    val stalePriceThresholdDays: Int = 7,
    val veryStalePriceThresholdDays: Int = 30
)
```

Quality:

```kotlin
data class InvestmentDataQuality(
    val staleHoldingCount: Int,
    val veryStaleHoldingCount: Int,
    val missingPriceCount: Int,
    val warnings: List<String>
)
```

Apply to:

```text
summary
allocation
history
performance
```

### Tests

```text
InvestmentFreshPriceNoWarningTest
InvestmentStalePriceWarningTest
InvestmentVeryStalePriceWarningTest
```

---

# Phase 3 — Tax / business

## PR-T1 — Real updateBusinessTaxFields lifecycle method

### Problem

`TransactionLifecycleCoordinator.updateBusinessTaxFields()` is a log stub.

### Implement

Command:

```kotlin
data class BusinessTaxFieldsUpdate(
    val isBusinessExpense: Boolean?,
    val businessUsePercent: Double?,
    val taxCategory: String?,
    val vatEligible: Boolean?,
    val deductiblePercentage: Double?,
    val source: String
)
```

Coordinator:

```kotlin
suspend fun updateBusinessTaxFields(
    expenseId: Long,
    update: BusinessTaxFieldsUpdate,
    sideEffectMode: SideEffectMode = SideEffectMode.DISPATCH_AFTER_COMMIT
): TransactionLifecycleResult
```

Flow:

```text
1. load existing expense
2. validate update
3. database.withTransaction:
   - update fields through DAO
   - insert TransactionEvent(type=BUSINESS_TAX_FIELDS_UPDATED)
4. dispatch side effects after commit if requested
```

DAO needs targeted update method.

Validation:

```text
businessUsePercent 0..100
deductiblePercentage 0..100
taxCategory allowed/free text sanitized
```

### Tests

```text
BusinessTaxUpdateWritesExpenseFieldsTest
BusinessTaxUpdateWritesLifecycleEventTest
BusinessTaxUpdateRollbackNoSideEffectTest
BusinessTaxInvalidPercentRejectedTest
```

Acceptance:

```text
Business/tax edits no longer bypass transaction lifecycle.
```

---

## PR-T2 — Categorized deductions MoneyAggregate

### Problem

`TaxYearSummary.categorizedDeductions: Map<String, Double>` is raw.

### Implement

Replace/add:

```kotlin
data class TaxCategoryDeductionAggregate(
    val categoryName: String,
    val amount: MoneyAggregate,
    val transactionCount: Int,
    val dataQuality: TaxReportDataQuality
)
```

TaxYearSummary:

```kotlin
val categorizedDeductionAggregates: List<TaxCategoryDeductionAggregate>
```

Keep old `Map<String, Double>` deprecated or derived only for legacy UI when single-currency.

Algorithm:

```text
group business expenses by tax/category
for each category:
  bucket by currency using effectiveAmount
  MoneyAggregateBuilder with home/filing currency
```

Uncategorized:

```text
compute from actual uncategorized rows
do not subtract raw category totals from raw total
```

### Tests

```text
TaxCategoryDeductionsMixedCurrencyAggregateTest
TaxUncategorizedComputedFromRowsTest
TaxCategoryMissingRatePartialTest
TaxCategorySumsToIncludedTotalTest
```

---

## PR-T3 — Propagate partial quality to derived tax aggregates

### Problem

Derived fields:

```text
taxableIncomeAggregate
estimatedTaxAggregate
```

hide partial source quality.

### Implement

When deriving from:

```text
incomeAggregate
deductibleAggregate
vatAggregate
```

merge quality:

```kotlin
fun MoneyAggregate.derive(
    amount: Double,
    label: String,
    sources: List<MoneyAggregate>
): MoneyAggregate
```

Or add:

```kotlin
DerivedMoneyAggregateQuality(
    sourcePartials,
    warnings
)
```

Rules:

```text
if source partial → derived partial
if source stale → derived stale warning
if source missing → derived warning
```

### Tests

```text
TaxableIncomeInheritsDeductionPartialTest
EstimatedTaxInheritsIncomePartialTest
VatEstimateLowConfidenceWarningTest
```

---

## PR-T4 — Clarify TaxRateProvider scope

### Problem

Current provider is used for VAT only.

### Option A — Rename

If it only provides VAT:

```kotlin
interface VatRateProvider
```

### Option B — Expand

```kotlin
data class TaxRateConfig(
    val vatRate: Double,
    val incomeBrackets: List<TaxBracket>,
    val socialSecurityRates: ...
    val source: TaxRateSource
)
```

Recommended for now:

```text
Rename or document as VAT/demo provider.
Keep official tax provider deferred.
```

Add metadata to estimate:

```text
taxRateSource = DEMO
vatRateSource = DEMO_PROVIDER
confidence = LOW
```

### Tests

```text
TaxEstimatorUsesVatProviderTest
TaxEstimateMarksDemoProviderLowConfidenceTest
TaxProviderFallbackToConfigTest
```

---

## PR-T5 — Business report formatting policy

### Problem

Engine should not format money directly.

Ensure reports return:

```text
MoneyAggregate + filingCurrency
```

Exporter/UI formats.

If any exporter still hardcodes `€`, replace with:

```kotlin
CurrencyFormatter.format(amount, filingCurrency)
```

### Tests

```text
BusinessReportNoHardcodedEuroTest
BusinessReportUsesFilingCurrencyTest
```

---

# Phase 4 — Tests / guards / docs

## PR-X1 — Golden scenario updates

Add/extend scenarios:

```text
GroupFullSettlementLifecycleScenarioTest
InvestmentMixedCurrencyPortfolioScenarioTest
TaxBusinessMixedCurrencyLifecycleScenarioTest
```

### Group scenario asserts

```text
settlement persists
member cannot be removed with net balance
lifecycle events written
hard delete lifecycle cleanup
shared budget offset aggregate matches numeric
```

### Investment scenario asserts

```text
allocation aggregate safe
history aggregate safe
stale price warnings
raw summary not used by public model
```

### Tax scenario asserts

```text
business/tax update lifecycle event
category aggregate mixed-currency safe
derived aggregate partial propagation
CSV sanitized
VAT provider metadata shown
```

---

## PR-X2 — Guard updates

Raw money guard should flag:

```text
PortfolioSummary.totalValue public use
DailyPortfolioValue.totalValue public use
TaxYearSummary.categorizedDeductions: Map<String, Double>
```

Allow only:

```text
deprecated legacy methods
tests
Room persistence fields
```

Lifecycle guard should flag direct business/tax DAO update paths outside coordinator.

---

# Recommended implementation order

```text
1. PR-G1 group net balance calculator
2. PR-G4 shared budget aggregate historical consistency
3. PR-I1 investment allocation aggregate-safe
4. PR-I2 contain raw PortfolioSummary
5. PR-T1 real updateBusinessTaxFields()
6. PR-T2 tax categorized deductions MoneyAggregate
7. PR-T3 derived tax partial quality
8. PR-I3 portfolio history aggregate/zone-safe
9. PR-G2 persistent group lifecycle events
10. PR-G3 hard delete lifecycle/containment
11. PR-I4/I5 performance + staleness
12. PR-T4/T5 provider scope + formatting cleanup
13. PR-X1/X2 golden tests and guards
```

If you want the smallest “stable enough” cut:

```text
1. group net balance calculator
2. investment allocation aggregate-safe
3. real updateBusinessTaxFields()
4. tax category MoneyAggregate
5. derived tax partial propagation
6. shared budget aggregate consistency
```

Then you can label:

```text
Groups / Investment / Tax = stable enough, with advanced audit/ledger/tax-provider features deferred.
```

---

# Definition of done

## Groups stable when

```text
member removal uses real net balances
linked expense cleanup uses lifecycle or hard delete is contained
shared budget aggregates match historical conversion totals
settlements persist and affect balances
lifecycle events are persisted or explicitly deferred/hidden
```

## Investment stable when

```text
allocation is MoneyAggregate-backed
raw summary is not used by public UI/domain
history is aggregate/per-currency/partial-aware
performance rows carry money/dataQuality
stale prices are modeled consistently
```

## Tax stable when

```text
business/tax updates go through lifecycle coordinator
category deductions use MoneyAggregate
derived aggregates inherit partial quality
VAT/tax provider source is explicit
CSV and business reports are safe and filing-currency-aware
```