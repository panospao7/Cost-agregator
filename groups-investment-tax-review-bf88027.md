# Groups / Investment / Tax Engine Review

Reviewed commits:

```text
7ea473b
e219f1a
dbf1419
44f51f3
6a68609
e0079f5
bf88027
```

Latest reviewed head:

```text
bf880276254474009a122bd35fb8eb5ad0e84c69
```

## Executive verdict

These commits made **substantial progress**, especially:

```text
✅ group settlement table + DAO + migration
✅ GroupLifecycleCoordinator exists
✅ single-currency group validation improved
✅ linked expense ownership now routes through TransactionLifecycleCoordinator in some paths
✅ shared budget offsets now expose MoneyAggregate fields and partial warnings
✅ investment addHolding writes holding + value snapshot + BUY transaction atomically
✅ portfolio history carry-forward implemented
✅ investment summary aggregate exists
✅ price staleness flag exists
✅ TaxSettings fiscal year fields exist
✅ TaxEstimator uses tax country/fiscal-year settings
✅ mileage fallback fixed
✅ CSV sanitizer extracted
✅ TaxEstimate / TaxYearSummary now include MoneyAggregate fields
✅ TaxRateProvider interface + DemoTaxRateProvider + Hilt binding added
✅ golden scenario tests added
```

But I would **not** call these engines fully finalized yet.

Current status:

```text
Groups:     partial / beta-stable
Investment: partial / beta-stable
Tax:        partial / estimate-only beta
```

No catastrophic regression is obvious, but there are still correctness gaps.

---

# 1. Groups engine status

## Good

### Settlement persistence exists

`GroupSettlementEntity`, `GroupSettlementDao`, and migration `119→120` exist.

`GroupLifecycleCoordinator.recordSettlement()` validates:

```text
group active
currency == group.defaultCurrency
from/to members belong to group
```

and inserts a settlement.

### Single-currency policy improved

`GroupLifecycleCoordinator.addExpense()` rejects expense currency mismatching group currency.

`GroupTransactionCoordinator.addExpenseWithLink()` also rejects linked system expense currency mismatch.

### Some side-effect deferral exists

`createSystemExpenseAndLinkToGroup()` calls:

```kotlin
TransactionLifecycleCoordinator.createExpense(..., SideEffectMode.DEFER)
```

inside the transaction, then dispatches post-commit in `.also`.

That is good.

---

## Remaining group issues

### G-1 — Lifecycle events are not real persisted events

`GroupLifecycleCoordinator.emitLifecycleEvent()` currently logs:

```kotlin
Timber.d("GroupLifecycleEvent...")
```

and calls budget/side-effect logic. It does not write a `group_lifecycle_events` table.

So group lifecycle/audit is **not complete**.

### G-2 — Existing linked-expense path can still run side effects too early

`GroupTransactionCoordinator.addExpenseWithLink()` calls:

```kotlin
normalizeLinkedSystemExpense(...)
```

inside `database.withTransaction`.

That calls:

```kotlin
TransactionLifecycleCoordinator.updateOwnership()
```

which writes an event and dispatches side effects after its own transaction.

Because this is invoked from inside the group transaction path, side effects may still run before the outer group operation is fully committed.

So G02/G03 are only **partially fixed**.

### G-3 — Hard delete still bypasses lifecycle

`deleteGroupAtomic()` still directly deletes group rows and then calls:

```kotlin
expenseDao.clearSharedExpenseFlags(expenseId)
```

outside `TransactionLifecycleCoordinator`.

The comment says this is intentional, but from a release-safety perspective this means hard delete is still a lifecycle bypass.

### G-4 — Member removal balance gate is too weak

`removeMember()` blocks only if member is payer of unsettled group expenses:

```text
paidById == memberId && settledAt == null
```

It does not compute net balance including:

```text
splits
amounts owed
settlement records
member as debtor but not payer
```

So a member with outstanding balance can still be removable.

### G-5 — Shared budget offset aggregates may use different conversion basis

`SharedExpenseBudgetOffsetEngine` computes numeric totals using:

```kotlin
convertAsOf(..., atMillis = expense.date)
```

Good.

But the `MoneyAggregate` fields are built from buckets using:

```kotlin
MoneyAggregateBuilder.fromBuckets(...)
```

That builder may use non-historical/current conversion depending implementation. Therefore:

```text
numeric effectiveBudgetSpend
```

and:

```text
adjustedSpendAggregate.displayAmount
```

can disagree.

### G-6 — Archived groups excluded from budget offsets

The file explicitly documents archived groups being excluded. That may be acceptable product policy, but it is still an accounting caveat.

## Groups verdict

```text
Group core creation/settlement/currency policy: improved
Production-stable group lifecycle: not yet
```

I would mark Groups:

```text
BETA unless you fix lifecycle events + hard-delete/member-removal/budget aggregate consistency.
```

---

# 2. Investment engine status

## Good

### Add holding atomicity improved

`addHolding()` writes:

```text
Investment
InvestmentValue snapshot
InvestmentTransaction BUY
```

inside `database.withTransaction`.

Good.

### Portfolio summary aggregate exists

`getPortfolioSummaryAggregate()` creates a `MoneyAggregate` from per-currency buckets.

Good.

### Portfolio history carry-forward exists

`getPortfolioValueHistory()` carries latest known value per holding forward.

Good.

### Price staleness exists

`InvestmentDataQuality` has:

```text
staleHoldingCount
missingPriceCount
lastUpdatedAt
```

and `InvestmentPerformance` has:

```text
isPriceStale
```

Good foundation.

---

## Remaining investment issues

### I-1 — Raw public summary still exists and is still used

`getPortfolioSummary()` is deprecated but still returns raw mixed-currency `Double` totals.

More importantly:

```kotlin
getPortfolioAllocation()
```

still calls:

```kotlin
getPortfolioSummary()
```

and computes allocation from raw mixed-currency totals.

That means investment allocation is still currency-unsafe.

### I-2 — `getPortfolioSummaryAggregate()` still returns raw `PortfolioSummary`

The aggregate is good, but the returned `PortfolioSummary` inside the `Triple` still contains raw totals:

```text
totalValue
totalInvested
totalGainLoss
byType
```

If callers use `summary.totalValue` instead of `aggregate.displayAmount`, fake totals can still leak.

### I-3 — Investment performance aggregate model is awkward

`getInvestmentPerformances()` attaches the same portfolio-level aggregate to every individual `InvestmentPerformance`.

Each row still exposes raw:

```text
currentValue
gainLoss
gainLossPercent
```

without per-row currency/dataQuality.

Better:

```text
InvestmentPerformance.currentValueMoney
InvestmentPerformance.costBasisMoney
InvestmentPerformance.dataQuality
```

### I-4 — Price staleness is hardcoded and incomplete

Current logic:

```text
7 days = stale
30 days = declared but not used
```

No settings/repository policy, no warning list, no “very stale” exposure.

### I-5 — Portfolio history is still raw and time-zone fragile

`getPortfolioValueHistory()` returns:

```text
DailyPortfolioValue(date, totalValue: Double)
```

This raw-sums holdings across currencies.

It also uses:

```kotlin
Calendar.getInstance()
24 * 60 * 60 * 1000L
```

so it is vulnerable to timezone/DST issues.

### I-6 — Ledger is minimal only

BUY transaction is wired. SELL/DIVIDEND/realized gains remain deferred, which is fine if investment is beta.

## Investment verdict

```text
Investment basic add/update/summary aggregate: improved
Investment public analytics/allocation/history: not fully stable
```

I would mark Investment:

```text
BETA unless raw allocation/history/public summary are contained or fixed.
```

---

# 3. Tax / business engine status

## Good

### Tax settings improved

`TaxSettingsRepository` now has:

```text
tax country
filing currency
fiscal year start month/day
VAT enabled
business report currency policy
```

`TaxEstimator` uses tax country/fiscal-year start.

### Tax aggregates improved

`TaxEstimate` and `TaxYearSummary` include:

```text
deductibleAggregate
vatAggregate
taxableIncomeAggregate
incomeAggregate
estimatedTaxAggregate
isPartial
conversionWarnings
```

Good direction.

### Mileage fallback fixed

`BusinessExpenseRepository.getTotalMileageDeduction()` now computes:

```text
calculatedDeduction ?: distanceKm * deductionRatePerKm
```

and filters invalid distance/rate.

### CSV sanitizer exists

`CsvCellSanitizer` neutralizes formula-prefix cells.

### TaxRateProvider exists

`TaxRateProvider`, `DemoTaxRateProvider`, and Hilt binding exist.

---

## Remaining tax issues

### T-1 — `TaxRateProvider` is injected but unused

`TaxEstimator` receives:

```kotlin
taxRateProvider: TaxRateProvider
```

but does not call:

```kotlin
taxRateProvider.getRate(...)
```

It still uses:

```kotlin
TaxConfigurationFactory.getConfiguration(taxSettings.getTaxCountry())
taxConfig.getVatRate()
```

So T08 is **foundation only**, not fully implemented.

### T-2 — `updateBusinessTaxFields()` is a stub

`TransactionLifecycleCoordinator.updateBusinessTaxFields()` currently only logs:

```kotlin
Timber.d("T10...")
```

and has a comment saying actual DAO update depends on existing fields.

It does not:

```text
load expense
update business/tax fields
write TransactionEvent
dispatch side effects
```

So T10 is **not fixed**.

### T-3 — Tax year categorized deductions are still raw mixed-currency

`getTaxYearSummary()` builds:

```kotlin
categorizedDeductions: Map<String, Double>
```

from:

```kotlin
businessExpenseRepository.getExpensesByCategory(...)
businessExpenseRepository.getTotalBusinessExpenses(...)
```

Then computes uncategorized via raw subtraction.

That can still raw-sum or raw-subtract mixed currencies.

The aggregate fields help the summary, but category breakdown remains unsafe.

### T-4 — `taxableIncomeAggregate` and `estimatedTaxAggregate` are synthetic raw aggregates

They are constructed with raw `displayAmount` and empty source buckets/failures:

```kotlin
MoneyAggregate(displayAmount = taxableIncome, ...)
```

This may be acceptable for derived values, but it hides quality linkage. If deductible/income was partial, estimated tax should also carry partial/derived warning.

### T-5 — VAT provider/semantics still partial

VAT confidence is set to `"LOW"`, good.

But:

```text
per-expense VAT fields are absent
recoverable VAT is hardcoded 0.0
VAT rate still comes from TaxConfiguration, not TaxRateProvider
```

So VAT is estimate-only.

### T-6 — CSV sanitizer edge case

`CsvCellSanitizer.sanitize()` checks `field.trimStart()` for formula prefixes, but if dangerous input starts with tab/newline followed by `=`, it returns:

```text
' + original field
```

without stripping tab/newline in that branch.

Safer:

```kotlin
val cleaned = field.replace("\t", " ").replace("\n", " ").replace("\r", " ")
val trimmed = cleaned.trimStart()
if (trimmed starts formula char) "'$cleaned" else cleaned
```

### T-7 — Stale implementation-plan comments remain

`TaxEstimator.kt` still contains a large plan for TaxSettings entity/DAO even though repository exists and implementation is different. That is stale and can mislead future agents.

## Tax verdict

```text
Tax estimate core: improved and usable as estimate-only
Tax/business production-grade engine: not stable yet
```

I would mark Tax:

```text
Estimate-only / Beta
```

until T10 and category MoneyAggregate breakdown are fixed.

---

# 4. Commit batch assessment

## `7ea473b`

Good first batch:

```text
SharedExpenseBudgetOffsetEngine aggregate fields
portfolio history carry-forward
mileage fallback
CSV sanitizer
TaxSettings fields
```

But many changes were partial/foundation.

## `e219f1a` / `dbf1419`

Good group lifecycle direction:

```text
post-commit side effect pattern introduced
ownership normalization through TransactionLifecycleCoordinator
business tax lifecycle method added
```

But:

```text
updateBusinessTaxFields is stub
addExpenseWithLink ownership update still has side-effect timing risk
```

## `44f51f3`

Good:

```text
TaxRateProvider interface
price staleness fields
```

But:

```text
TaxRateProvider unused
veryStale threshold unused
```

## `6a68609`

Good:

```text
TaxEstimate / TaxYearSummary MoneyAggregate fields
VAT semantics renamed
```

But:

```text
raw category breakdown remains
derived aggregates do not carry full data quality
```

## `e0079f5`

Docs update useful, but may be too optimistic if it says all G/I/T fixed.

## `bf88027`

Good golden tests:

```text
group settlement persistence
foreign currency rejection
member removal simple case
hard delete requires archive
investment add atomic
investment summary aggregate
tax aggregate fields
mileage fallback
```

But tests do not cover the remaining risky cases:

```text
group side effects rollback
group member with net debt blocked
group hard-delete lifecycle
shared budget aggregate/historical conversion consistency
investment allocation mixed-currency
investment history mixed-currency
tax category MoneyAggregate
business tax lifecycle update
TaxRateProvider actually used
CSV tab/newline formula edge
```

---

# 5. Stability ratings

```text
Groups creation/currency/settlement persistence: 7.5/10
Groups lifecycle/audit/deletion/budget offsets: 5.5/10

Investment add/update/basic aggregate: 8/10
Investment allocation/history/performance data quality: 5.5/10

Tax estimate aggregate foundation: 7/10
Tax/business lifecycle/category/VAT/provider safety: 5/10
```

Overall:

```text
Groups / Investment / Tax engines are NOT fully stable yet.
They are beta-stable foundations with several remaining correctness gaps.
```

---

# 6. Final blocker list before calling these stable

## Groups blockers

```text
1. Persist real group lifecycle events or mark audit deferred.
2. Fix addExpenseWithLink side-effect timing:
   updateOwnership must support SideEffectMode.DEFER or group path must dispatch after outer commit.
3. Strengthen member-removal balance gate using real net balances including settlements.
4. Make shared budget MoneyAggregate use the same historical conversion policy as numeric totals.
5. Decide archived-group budget offset policy.
6. Keep hard-delete contained/admin-only or route cleanup through lifecycle/audit.
```

## Investment blockers

```text
1. Replace getPortfolioAllocation() raw summary math with aggregate-safe allocation.
2. Prevent callers from using raw PortfolioSummary totals as display totals.
3. Make portfolio history currency-aware or clearly native-currency-only/partial.
4. Add per-investment performance money/dataQuality, not same portfolio aggregate on every row.
5. Finish price staleness model: settings, veryStale, warnings.
```

## Tax blockers

```text
1. Implement TransactionLifecycleCoordinator.updateBusinessTaxFields().
2. Make categorizedDeductions MoneyAggregate-backed or per-currency.
3. Actually use TaxRateProvider or mark provider as future foundation.
4. Propagate partial quality into taxableIncome/estimatedTax aggregates.
5. Harden CsvCellSanitizer for tab/newline-before-formula.
6. Remove stale TaxSettings implementation-plan comment.
```

---

# 7. Can you move forward?

My recommendation:

```text
Do not mark G/I/T stable yet.
```

But you can split the decision:

```text
Move forward if you label:
- Groups = Beta
- Investment = Beta
- Tax = Estimate-only/Beta
```

If you want engine stability before UI, do one final focused PR:

```text
PR-GIT-FINAL
1. group lifecycle/side-effect deferral cleanup
2. investment allocation/history MoneyAggregate cleanup
3. business/tax lifecycle method real implementation
4. tax category aggregate + TaxRateProvider usage decision
5. CSV sanitizer hardening
6. tests for the above
```

After that, I’d be comfortable saying these engines are stable enough.

---

# 8. Minimum tests to add

```text
GroupAddExpenseWithLinkRollbackNoSideEffectsTest
GroupRemoveMemberWithNetDebtBlockedTest
SharedBudgetAggregateUsesHistoricalRateTest
GroupHardDeleteLifecycleOrContainmentTest

PortfolioAllocationMixedCurrencySafeTest
PortfolioHistoryMixedCurrencyPartialTest
InvestmentPerformancePerRowCurrencyQualityTest
InvestmentVeryStalePriceWarningTest

BusinessTaxUpdateWritesTransactionEventTest
TaxCategoryBreakdownMixedCurrencyAggregateTest
TaxRateProviderUsedByEstimatorTest
CsvSanitizerTabBeforeFormulaTest
TaxEstimatePartialQualityPropagatesTest
```

---

# 9. Final answer

Are these engines stable now?

```text
No, not fully.
```

Are the fixes valuable?

```text
Yes, very valuable.
```

Can you move to other issues?

```text
Only if Groups/Investment/Tax are treated as beta/estimate-only.
```

If the goal is “all engines stable before UI,” do one more G/I/T stabilization pass first.