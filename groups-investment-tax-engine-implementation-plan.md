# Groups / Investment / Tax Engine Implementation Plan

Scope:

```text
Groups: G02 G03 G06 G07
Investment: I03 I05 I06 I09
Tax/business/mileage: T01–T10
```

Also includes extra issues found from the recent engine-review context.

---

# 0. First: tracker status reconciliation

Several tracker statuses look stale based on the recent commits.

## Likely status corrections

| ID | Suggested current status |
|---|---|
| G02 | PARTIAL / OPEN — side-effect deferral still needs proof/tests |
| G03 | PARTIAL / MOSTLY FIXED — linked ownership update appears routed through lifecycle in newer code, but audit all call sites |
| G06 | PARTIAL / MOSTLY FIXED — `SharedExpenseBudgetOffsetEngine` had partial conversion metadata added earlier; verify model/tests |
| G07 | PARTIAL / MOSTLY FIXED — `convertAsOf()` was added earlier; verify every shared-offset path uses it |
| I03 | PARTIAL / MOSTLY FIXED — portfolio carry-forward was reportedly implemented; needs tests/edge audit |
| I05 | OPEN — engine/repository may expose data, but UI/ViewModel path still likely partial |
| I06 | PARTIAL — raw DAO aggregate methods deprecated, but public callers may remain |
| I09 | OPEN |
| T01 | PARTIAL — some business MoneyAggregate work exists, but tax summary end-to-end likely incomplete |
| T02 | MOSTLY FIXED — mileage SUM fallback was added earlier; verify tests |
| T03 | PARTIAL / MOSTLY FIXED — `TaxSettingsRepository` exists; confirm TaxEstimator consumes it |
| T04 | OPEN / DEFERRED |
| T05 | PARTIAL |
| T06 | PARTIAL |
| T07 | OPEN |
| T08 | DEFERRED DESIGN |
| T09 | PARTIAL — fiscal fields exist if `TaxSettings` has start month/day; confirm used everywhere |
| T10 | OPEN |

## Tracker rule

Use only:

```text
FIXED
PARTIAL
OPEN
CONTAINED
DEFERRED_DESIGN
```

Do not leave implemented items as `TODO ONLY`, and do not mark plan comments as `FIXED`.

---

# 1. Groups engine

## Current goal

Groups should be either:

```text
A. production-stable, with durable settlements and lifecycle-safe mutations
```

or:

```text
B. explicitly beta/contained, with misleading actions disabled
```

Recommended short-term: finish core group correctness, but defer full multi-currency settlements.

---

## PR-G1 — G02: defer side effects until after DB commit

### Problem

Group expense creation can create side effects while still inside an outer transaction:

```text
linked expense creation/update
member balance recalculation
notifications/events
stats updates
analytics invalidation
```

If the DB transaction rolls back, side effects can survive.

### Implementation

Introduce a transaction result pattern:

```text
Group mutation returns:
- persisted result
- postCommitEffects list
```

Then run effects only after `database.withTransaction` succeeds.

Suggested abstraction:

```text
PostCommitEffect
  - RecalculateGroupBalances(groupId)
  - EmitGroupLifecycleEvent(...)
  - RefreshBudgetOffsets(groupId)
  - NotifyGroupMembers(...)
```

For critical durability, use an outbox table:

```text
group_side_effect_outbox
```

and a worker drains it.

### Acceptance

```text
No external side effect runs before transaction commit.
Rollback means no side effects emitted.
```

### Tests

```text
GroupAddExpenseRollbackNoSideEffectsTest
GroupAddExpenseCommitRunsPostCommitEffectsTest
GroupSideEffectOutboxPersistsOnCommitTest
```

---

## PR-G2 — G03: linked expense ownership through lifecycle

### Problem

Group-linked expense normalization/ownership updates must not directly update DAO fields.

Required path:

```text
Group engine
→ TransactionLifecycleCoordinator.updateSharedOwnership()
→ transaction event
→ side effects after commit
```

### Implementation

Audit all direct writes to:

```text
isSharedExpense
sharedGroupId
sharedExpenseId
paidByMemberId
split metadata
effectiveAmount
isNotMine
```

Replace with coordinator calls.

Add guard allowlist:

```text
direct group-linked expense writes allowed only in migrations/tests
```

### Acceptance

```text
Every group-linked expense mutation creates transaction lifecycle event.
No repository/service directly mutates shared ownership fields.
```

### Tests

```text
GroupLinkedExpenseUsesTransactionLifecycleTest
GroupLinkedExpenseRollbackNoEventTest
DirectSharedOwnershipDaoWriteGuardTest
```

---

## PR-G3 — G06/G07: shared budget offsets MoneyAggregate + historical rates

### Problem

Shared budget offsets can still under-report if conversion fails or if current rates are used for historical expenses.

### Implementation

Update `SharedExpenseBudgetOffsetEngine` output to include:

```text
grossPersonalSpend: MoneyAggregate
sharedContributionOffset: MoneyAggregate
adjustedSpend: MoneyAggregate
isPartial
conversionWarnings
failedConversionCount
```

Use:

```text
convertAsOf(amount, fromCurrency, homeCurrency, atMillis = expense.date)
```

for every historical shared expense.

### Rules

```text
conversion failure excludes that offset from adjustedSpend
partial warning is propagated
stale rate counts as warning
```

### Acceptance

```text
Budget offsets never silently drop failed conversions.
Shared offsets use historical rate at expense date.
```

### Tests

```text
SharedOffsetUsesHistoricalRateTest
SharedOffsetMissingRatePartialTest
SharedOffsetStaleRateWarningTest
SharedOffsetAdjustedSpendMatchesMoneyAggregateTest
```

---

## PR-G4 — Group settlement and member lifecycle hardening

Even if not listed, these are closely related.

### Implement/verify

```text
GroupSettlementEntity is used by recordSettlement()
balances subtract settlements
member removal blocked if unsettled balance exists
group archive vs hard delete clearly separated
hard delete requires archived state
group lifecycle/audit events written
```

### Tests

```text
GroupSettlementPersistsTest
GroupSettlementUpdatesBalancesTest
GroupRemoveMemberWithBalanceBlockedTest
GroupHardDeleteRequiresArchivedTest
GroupLifecycleEventWrittenTest
```

---

# 2. Investment engine

## Current goal

Investment should not present fake portfolio totals, should not lose value history, and should clearly mark stale prices.

---

## PR-I1 — I03: portfolio history carry-forward

### Problem

Portfolio history can undercount days if it only sums values recorded on that day.

Correct rule:

```text
For each day and each holding, use latest known value at or before day end.
```

### Implementation

Build history as:

```text
date buckets
for each bucket:
  for each active holding:
    find latest price/value snapshot <= bucketEnd
    carry forward
  sum carried values
```

Handle:

```text
holding created after bucket → excluded
holding sold/deactivated before bucket → excluded if sell flow exists
missing price → stale/missing warning
```

### Acceptance

```text
Portfolio value does not drop to zero on days without price updates.
```

### Tests

```text
PortfolioHistoryCarriesForwardValueTest
PortfolioHistoryHoldingCreatedMidRangeTest
PortfolioHistoryMissingPriceStaleWarningTest
PortfolioHistoryMultiCurrencyPartialTest
```

---

## PR-I2 — I05: expose investment performances

### Problem

Engine may compute summary, but app needs active investments + performance stream/model.

### Implementation

Add repository/engine API:

```text
observeInvestmentPerformances()
getInvestmentPerformances()
```

Performance model should include:

```text
investmentId
symbol/name/type
quantity
purchasePrice
purchaseCurrency
currentPrice
currentCurrency
currentValue: MoneyAggregate or converted display amount
costBasis
unrealizedGain
unrealizedGainPercent
lastPriceUpdatedAt
dataQuality
```

Use `MoneyAggregateBuilder` for totals.

### Acceptance

```text
A caller can render active investments and performance without raw mixed-currency math.
```

### Tests

```text
InvestmentPerformanceSingleCurrencyTest
InvestmentPerformanceMixedCurrencyTest
InvestmentPerformanceMissingRatePartialTest
InvestmentPerformanceStalePriceWarningTest
```

---

## PR-I3 — I06: DAO aggregates agree with tracker math

### Problem

Raw DAO aggregate methods may ignore:

```text
fees
currency
active/inactive holdings
value history timing
```

### Implementation

Define official formulas:

```text
costBasis = quantity * purchasePrice + fees
currentValue = quantity * currentPrice
unrealizedGain = currentValue - costBasis
```

Then:

```text
remove raw DAO aggregate methods
or mark internal/deprecated
or make them return per-currency buckets only
```

Public totals must use:

```text
InvestmentTracker + MoneyAggregateBuilder
```

### Acceptance

```text
DAO raw aggregate cannot disagree with public tracker math.
```

### Tests

```text
InvestmentCostBasisIncludesFeesTest
InvestmentDaoAggregateDeprecatedNotUsedTest
InvestmentTrackerMatchesPerHoldingMathTest
```

---

## PR-I4 — I09: model price staleness

### Problem

Portfolio values are misleading if prices are old.

### Implementation

Add settings:

```text
stalePriceThresholdDays
veryStalePriceThresholdDays
```

Add data quality:

```text
InvestmentDataQuality
  - staleHoldingCount
  - veryStaleHoldingCount
  - missingPriceCount
  - lastUpdatedAt
  - warnings
```

Apply to:

```text
PortfolioSummaryAggregate
InvestmentPerformance
PortfolioAllocation
PortfolioHistory
```

### Acceptance

```text
Old prices reduce confidence and produce warnings.
```

### Tests

```text
InvestmentPriceFreshTest
InvestmentPriceStaleWarningTest
InvestmentPriceVeryStaleWarningTest
InvestmentMissingPriceExcludedOrPartialTest
```

---

## PR-I5 — investment ledger follow-through

Because `InvestmentTransaction` table exists, decide minimal wiring.

### Recommended minimum

On add holding:

```text
insert BUY transaction
insert holding
insert initial value snapshot
all in one transaction
```

Do not implement full sell/realized gains unless ready.

Mark as deferred:

```text
SELL flow
DIVIDEND flow
lot ledger
realized gains
tax lots
```

### Tests

```text
AddHoldingCreatesBuyTransactionTest
AddHoldingLedgerRollbackTest
```

---

# 3. Tax / business / mileage engine

## Current goal

Tax/business reports should be estimate-safe, currency-safe, and settings-driven.

---

## PR-T1 — T03/T09: TaxSettings fully consumed

### Problem

`TaxSettingsRepository` may exist, but estimators/reports must actually use it.

### Implementation

Tax settings fields:

```text
selectedCountry
filingCurrency
fiscalYearStartMonth
fiscalYearStartDay
vatEnabled
businessReportCurrencyPolicy
```

TaxEstimator should consume settings for:

```text
country config
filing currency
fiscal year range
VAT assumptions
```

### Acceptance

```text
Changing TaxSettings changes estimator/report behavior.
Fiscal year is not assumed to be calendar-year.
```

### Tests

```text
TaxSettingsPersistenceTest
TaxEstimatorUsesSelectedCountryTest
FiscalYearRangeFromSettingsTest
TaxFilingCurrencyAppliedTest
```

---

## PR-T2 — T01/T06: tax totals and business reports MoneyAggregate

### Problem

Tax/business totals may raw-sum mixed currencies.

### Implementation

Report models should use:

```text
MoneyAggregate deductibleExpenses
MoneyAggregate businessIncome
MoneyAggregate mileageDeduction
MoneyAggregate estimatedVatPortion
List<CategoryBusinessAggregate>
TaxReportDataQuality
```

Business category breakdown:

```text
per category MoneyAggregate
warnings per category
```

Rules:

```text
failed conversion excluded + partial warning
stale rates included with warning or excluded by policy
transaction types filtered to business-spending/income correctly
```

### Acceptance

```text
No tax/business report field raw-sums mixed currencies.
```

### Tests

```text
TaxTotalsMixedCurrencyAggregateTest
TaxTotalsMissingRatePartialTest
BusinessReportMoneyAggregateTest
BusinessCategoryAggregateSumsToTotalTest
BusinessIncomeVsExpenseSeparatedTest
```

---

## PR-T3 — T02: mileage deduction null fallback

### Problem

Mileage deduction can undercount if stored calculated deduction is null.

### Implementation

DAO/query rule:

```text
if calculatedDeduction is null:
  use distance * rate
else:
  use calculatedDeduction
```

Also filter:

```text
business trips only
valid distance > 0
valid rate >= 0
```

### Acceptance

```text
Null calculatedDeduction does not make mileage deduction disappear.
```

### Tests

```text
MileageDeductionUsesStoredValueTest
MileageDeductionFallbackDistanceTimesRateTest
MileageDeductionBusinessTripsOnlyTest
MileageDeductionInvalidRowsExcludedTest
```

---

## PR-T4 — T04: VAT estimate semantics

### Problem

VAT estimate assumes standard-rate VAT and can appear too authoritative.

### Implementation

Rename model fields:

```text
estimatedVatPaid → estimatedVatPortion
vatRecoverable → estimatedRecoverableVat
```

Add per-expense fields if possible:

```text
vatRatePercent
vatIncluded
vatEligible
vatCategory
```

If fields are missing:

```text
use standard-rate estimate
mark confidence low
```

### Acceptance

```text
VAT output is clearly estimated unless per-expense VAT data exists.
```

### Tests

```text
VatEstimateUsesPerExpenseRateTest
VatEstimateStandardRateLowConfidenceTest
VatEstimateZeroRatedExpenseTest
```

---

## PR-T5 — T05/T07: business report formatting and CSV safety

### T05

Engine should not hardcode euro formatting.

Return structured values:

```text
amount + currency
```

Exporter/UI formats using filing currency.

### T07

Create hardened CSV sanitizer:

```text
neutralize cells starting with:
= + - @ tab carriage-return newline
```

Also handle leading spaces before formula characters.

Apply to:

```text
merchant
category
notes
taxCategory
any free text
```

### Acceptance

```text
No hardcoded euro in business reports.
CSV export cannot execute formula injection.
```

### Tests

```text
BusinessReportNoHardcodedEuroTest
BusinessCsvSanitizesEqualsTest
BusinessCsvSanitizesPlusMinusAtTest
BusinessCsvSanitizesLeadingWhitespaceFormulaTest
BusinessCsvSanitizesNotesAndMerchantTest
```

---

## PR-T6 — T08: tax rate provider separation

### Problem

Tax rates are hardcoded and may appear official.

### Implementation

Create:

```text
TaxRateProvider
```

Implement:

```text
DemoTaxRateProvider
UserEditableTaxRateProvider
OfficialTaxRateProvider placeholder/deferred
```

Tax config metadata:

```text
source
country
region
lastUpdatedAt
confidence
isDemo
```

### Acceptance

```text
TaxEstimator knows whether rates are demo/user/official.
```

### Tests

```text
TaxRateProviderDemoMetadataTest
TaxEstimatorUsesProviderConfigTest
TaxEstimateDemoConfidenceLowTest
```

Can remain `DEFERRED_DESIGN` if tax is estimate-only.

---

## PR-T7 — T10: business/tax updates through lifecycle

### Problem

Business/tax field updates can bypass transaction lifecycle events.

### Implementation

Add method:

```text
TransactionLifecycleCoordinator.updateBusinessTaxFields(...)
```

Fields:

```text
isBusinessExpense
businessUsePercent
taxCategory
vatEligible
mileageTripId
receiptRequired
```

Rules:

```text
validate values
update expense
write transaction lifecycle event
run side effects after commit
```

### Acceptance

```text
All business/tax expense edits emit lifecycle event.
```

### Tests

```text
BusinessTaxUpdateWritesLifecycleEventTest
BusinessTaxUpdateRollbackNoEventTest
DirectBusinessTaxDaoWriteGuardTest
```

---

# 4. Additional issues to add to tracker

## G-ADD-01 — Group settlement persistence integration

If `GroupSettlementEntity` exists, verify:

```text
recordSettlement writes it
balances subtract it
UI action uses it
```

If not, add.

## G-ADD-02 — group audit events

Groups need lifecycle/audit events for:

```text
created
member added/removed
expense added
settlement recorded
archived
deleted
```

## G-ADD-03 — group member removal with balance

Block member removal if:

```text
member has non-zero balance
member is payer of linked expense
member has settlement history
```

## I-ADD-01 — investment public raw summary containment

Any UI/domain public method returning:

```text
totalValue: Double
totalGain: Double
```

must also include:

```text
currency/dataQuality
```

or be deprecated/internal.

## I-ADD-02 — investment deletion/archival

If deleting holdings exists:

```text
soft-delete preferred
preserve transaction/value history
```

## T-ADD-01 — tax report transaction type filter

Tax/business reports must not include:

```text
transfers
not-mine expenses
non-business personal expenses
```

unless explicitly requested.

## T-ADD-02 — tax category historical snapshots

If tax category names can change/delete, reports need:

```text
categoryNameSnapshot
```

or historical lookup.

---

# 5. Recommended execution order

```text
1. Tracker reconciliation for G/I/T statuses.
2. Subscription-independent group shared budget fixes: G06/G07.
3. Group lifecycle/settlement sprint: G02/G03 + G-ADD items.
4. Investment history/public summary: I03/I06.
5. Investment performance/staleness: I05/I09.
6. Tax settings/fiscal year: T03/T09.
7. Tax/business MoneyAggregate reports: T01/T06/T05.
8. Mileage fallback tests: T02.
9. CSV safety/lifecycle updates: T07/T10.
10. VAT/tax provider metadata: T04/T08.
11. Golden scenario tests.
```

---

# 6. Golden scenario tests

## Group scenario

Seed:

```text
group EUR
members A/B/C
EUR group expense
legacy USD mixed row
settlement A→B
budget with shared offset
missing GBP rate
```

Assert:

```text
foreign group expense rejected
legacy mixed warning shown
settlement persists
balances adjust
shared budget offset partial on missing rate
convertAsOf used
```

## Investment scenario

Seed:

```text
EUR ETF
USD stock with historical rates
stale BTC price
fees on purchase
missing-rate holding
```

Assert:

```text
history carries forward values
cost basis includes fees
portfolio total MoneyAggregate partial
stale price warning
public summary not raw fake EUR
```

## Tax/business scenario

Seed:

```text
business EUR purchase
business USD purchase with rate
business GBP purchase missing rate
mileage trip with null calculatedDeduction
CSV merchant '=CMD'
custom fiscal year
```

Assert:

```text
business totals use MoneyAggregate
GBP excluded with warning
mileage fallback applied
CSV sanitized
fiscal year range respected
lifecycle event written for business/tax edit
```

---

# 7. Definition of done

Groups are stable when:

```text
side effects are post-commit
linked expense mutations use TransactionLifecycleCoordinator
shared budget offsets are MoneyAggregate + historical-rate aware
settlements persist and affect balances
hard delete/member removal are guarded
```

Investment is stable when:

```text
portfolio history carries forward values
public totals are MoneyAggregate/dataQuality-backed
fees are consistently included in cost basis
price staleness is modeled
ledger table is either wired minimally or clearly deferred
```

Tax/business is stable when:

```text
TaxSettings drives country/currency/fiscal year
business/tax totals use MoneyAggregate
mileage fallback works
CSV is formula-safe
business/tax updates use lifecycle coordinator
tax-rate source is labeled demo/user/official
```