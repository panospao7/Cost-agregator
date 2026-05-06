# Groups / Shared Expenses, Investment Tracking, and Tax Engines Debug Report

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

This report covers three secondary-but-financially-important subsystems:

```text
A. Groups / shared expenses / settlements / shared budget offsets
B. Investment tracking / portfolio valuation
C. Tax / business expense / mileage reporting
```

These are not isolated “nice-to-have” features. They feed or affect:

```text
dashboard totals
budget status
analytics
cash-flow
export/accounting
tax reports
user trust in financial correctness
```

The good news:

- Group split logic has strong validation and integer-cent settlement logic.
- Group create/link flows now use transaction coordinators in several places.
- Shared budget offset engine exists and tries to avoid double-counting linked group expenses.
- Investment tracker has a simple portfolio/performance engine and price history table.
- Tax estimator moved from hardcoded flat rates to `TaxConfiguration`.
- Business expense repository now enforces purchase-only semantics in several paths.
- Mileage validation exists.
- Unit tests exist for groups, split calculators, investment tracker, tax estimator/calculation, business reports.

The bad news:

> These three subsystems still operate heavily on raw `Double`, incomplete currency contracts, direct DAO updates, and partially disconnected UI/repository paths.

Highest-risk findings:

1. **Group current-user inserts may violate the `currentUserGroupKey` invariant.**
2. **Group system-expense creation calls `TransactionLifecycleCoordinator` inside an outer transaction, so post-commit side effects are not truly post-commit.**
3. **Group-linked expense normalization directly mutates `ExpenseDao` and does not write lifecycle update events.**
4. **Settlement calculator labels mixed-currency balances as home currency without conversion.**
5. **Shared budget offsets drop conversion failures and do not expose partial state.**
6. **Investment portfolio totals raw-sum holdings across currencies.**
7. **Investment price update is not atomic with price-history insert.**
8. **Investment history does not carry forward last known values, so portfolio charts can undercount.**
9. **Investment UI still loads an empty performance list.**
10. **Tax/business totals are not currency-normalized.**
11. **Mileage deduction aggregate undercounts rows where `calculatedDeduction` is null.**
12. **Business/tax reports hardcode euro formatting and have weak CSV safety/privacy contracts.**

Main recommendation:

> Treat these engines as financial contract engines. They need DB-backed scenario tests that prove group splits, portfolio value, and tax/business summaries reconcile with dashboard/analytics/export.

---

# 2. Groups / shared expenses / settlements

## 2.1 Intended architecture

The intended group flow is:

```text
SharedExpenseGroupsScreen / VisualSplitEditor
→ SharedExpenseGroupsViewModel / use cases
→ GroupsRepository / SharedExpenseManager
→ GroupTransactionCoordinator
→ ExpenseGroupDao / GroupMemberDao / GroupExpenseDao
→ optional TransactionLifecycleCoordinator for system expenses
→ Expense / GroupExpense link
→ SharedExpenseBudgetOffsetEngine
→ Budget / dashboard / analytics
```

Main entities:

```text
ExpenseGroup
GroupMember
GroupExpense
Expense
```

Main engines:

```text
SharedExpenseManager
GroupTransactionCoordinator
SplitCalculator
SettlementCalculator
SharedExpenseBudgetOffsetEngine
```

---

## 2.2 Strengths

Good pieces:

- `GroupTransactionCoordinator` uses `database.withTransaction` for many multi-table flows.
- `GroupExpense.expenseId` is unique, so one expense cannot be linked to multiple group expenses.
- `GroupExpense.paidById` has FK `RESTRICT`.
- A migration-created trigger is documented to enforce `paidById` belongs to the same group.
- `SplitCalculator` uses integer cents for equal and custom split rounding.
- `CustomSplitParser` validates exact member coverage and exact totals/percentages.
- `SettlementCalculator` uses exact DFS/backtracking with deterministic greedy fallback.
- `SharedExpenseBudgetOffsetEngine` tries to avoid double-counting linked system expenses.

These are strong foundations.

---

## 2.3 Groups finding P0-1 — Current-user member inserts may violate DB invariant

`GroupMember` documents this invariant:

```text
isCurrentUser = true  → currentUserGroupKey = groupId
isCurrentUser = false → currentUserGroupKey = NULL
```

`GroupMemberDao.setCurrentUser()` correctly sets:

```sql
currentUserGroupKey = :groupId
```

But group creation paths build `GroupMember` rows with:

```kotlin
isCurrentUser = true
currentUserGroupKey = null
```

Examples:

- `SharedExpenseManager.createGroup()` creates `SharedExpenseMember(isCurrentUser = ...)`.
- `SharedExpenseDataPortAdapter.toEntity()` maps it to `GroupMember` without setting `currentUserGroupKey`.
- `GroupTransactionCoordinator.createGroupWithMembersAtomic()` inserts the members directly.

If the migration CHECK constraint is active, creating a group with a current user can fail.

### Fix

When mapping to entity after group ID is known:

```kotlin
member.copy(
    groupId = groupId,
    currentUserGroupKey = if (member.isCurrentUser) groupId else null
)
```

For every current-user promotion or insert, use one invariant-safe path.

Priority: highest.

---

## 2.4 Groups finding P0-2 — Group expense creation reintroduces nested transaction side-effect risk

`GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()` opens:

```kotlin
database.withTransaction { ... }
```

Inside it calls:

```kotlin
transactionLifecycleCoordinator.createExpense(...)
```

But the transaction lifecycle coordinator itself opens a transaction and then runs post-create side effects:

```text
budget monitor
anomaly detection
merchant learning
recurring link
```

Because the group outer transaction is still active, those side effects are not truly post-commit.

Failure example:

```text
outer group transaction starts
→ create system expense
→ lifecycle side effects run
→ groupExpense insert fails
→ outer transaction rolls back
→ side effects already ran for rolled-back expense
```

### Fix

Use the Pipeline 2 deferred-side-effect model:

```text
database.withTransaction {
    create expense DB-only
    create group link
    normalize linked expense
    collect postCommitActions
}
run postCommitActions
```

Priority: highest.

---

## 2.5 Groups finding P0-3 — Linked expense normalization bypasses transaction lifecycle events

`GroupTransactionCoordinator.normalizeLinkedSystemExpense()` directly calls:

```text
expenseDao.updateIsNotMine(...)
expenseDao.updateIsSharedExpense(...)
expenseDao.updateMySharePercentage(...)
expenseDao.updateMyShareAmount(...)
```

This mutates real expense rows but does not write:

```text
TransactionEvent.UPDATED
```

It also bypasses update side effects for budget/analytics/recurring consistency.

### Fix

Add lifecycle update API:

```kotlin
transactionLifecycleCoordinator.updateSharedOwnership(
    expenseId,
    isSharedExpense = true,
    myShareAmount = currentUserShare,
    source = GROUP_EXPENSE
)
```

It should write `UPDATED` or `GROUP_LINKED` event and dispatch post-commit recalculation.

Priority: highest.

---

## 2.6 Groups finding P0-4 — Mixed-currency settlements are labeled as home currency without conversion

`SettlementCalculator.calculateSettlements()` does:

```kotlin
val distinctCurrencies = balances.values.map { it.currency }.distinct()
val groupCurrency = if (distinctCurrencies.size == 1) {
    distinctCurrencies.first()
} else {
    getHomeCurrencySync()
}
val normalized = normalizeBalancesToCents(balances)
```

But `normalizeBalancesToCents()` just converts raw `netBalance` values to cents. It does **not** convert currencies.

So if balances contain:

```text
Alice +10 EUR
Bob -10 USD
```

the settlement can be labeled as home currency even though the amounts were not converted.

### Fix

Do one of:

```text
A. Require one group currency and reject mixed-currency balances.
B. Convert all balances to group/home currency using historical/as-of rates.
C. Return partial/unsupported mixed-currency settlement result.
```

Do not silently relabel raw mixed-currency values.

Priority: highest.

---

## 2.7 Groups finding P1-1 — Group currency consistency is not fully enforced

`SharedExpensePort` says group expenses must match group default currency.

But `GroupTransactionCoordinator.addExpenseWithLink()` accepts a `currency` and uses:

```kotlin
val expenseCurrency = currency ?: group.defaultCurrency
```

It does not reject a non-default currency.

`GroupExpense` also stores its own currency, which can differ from group default.

### Fix

Pick a policy:

```text
single-currency group:
  require expense.currency == group.defaultCurrency

multi-currency group:
  store original currency and group/home converted amount + partial warnings
```

Given current code comments, enforce single-currency for now.

Priority: high.

---

## 2.8 Groups finding P1-2 — Shared budget offsets drop conversion failures

`SharedExpenseBudgetOffsetEngine` uses:

```kotlin
currencyConverter.convertMultiple(...)
```

and logs failures:

```text
"conversion failures: N transactions dropped"
```

But the returned `BudgetSpendBreakdown` only contains plain `Double` totals:

```text
totalPersonalSpend
totalSharedSpend
totalReimbursed
effectiveBudgetSpend
```

There is no:

```text
isPartial
conversionFailures
warningMessage
sourceBuckets
failedTransactionCount
```

So budget UI can show a confident adjusted spend that excludes failed conversions.

### Fix

Return `MoneyAggregate` or add data-quality fields:

```kotlin
data class BudgetSpendBreakdown(
    val personalSpend: MoneyAggregate,
    val sharedSpend: MoneyAggregate,
    val reimbursed: MoneyAggregate,
    val effectiveBudgetSpend: MoneyAggregate,
    val isPartial: Boolean,
    val warnings: List<String>
)
```

Priority: high.

---

## 2.9 Groups finding P1-3 — Shared budget offset uses current rates, not historical rates

The engine converts:

```text
personal spend
shared spend
reimbursements
```

using `CurrencyConverter.convertMultiple()`, which is current-rate based.

For budget reports and historical periods, conversion should generally use:

```text
expense date / group expense date
```

### Fix

Use historical conversion:

```text
convertAsOf(amount, from, home, atMillis = expense.date)
```

Priority: high.

---

## 2.10 Groups finding P1-4 — Hard delete path bypasses coordinator

`SharedExpenseDataPortAdapter.deleteGroup()` directly calls:

```kotlin
groupDao.delete(group.toEntity())
```

The file comment already warns this bypasses `GroupTransactionCoordinator`.

Because FKs cascade, hard delete can erase group members and group expenses, leaving linked system expenses semantically standalone.

### Fix

Route all group deletion through coordinator:

```text
archiveGroup() for normal user delete
permanentlyDeleteGroup() only for explicit destructive admin action
```

Also if hard delete occurs, write lifecycle events or normalize linked expenses intentionally.

Priority: high.

---

## 2.11 Groups finding P1-5 — Direct member delete can bypass split-reference validation

`SharedExpenseManager.removeMember()` correctly checks:

```text
paid expenses
custom split references
equal split date participation
```

But `SharedExpenseDataPortAdapter.removeMember()` directly calls:

```kotlin
memberDao.delete(...)
```

If any caller uses the port directly, it can bypass domain validation.

### Fix

Keep deletion validation in one coordinator/use case, not in optional caller code.

Priority: medium-high.

---

## 2.12 Groups finding P1-6 — `runBlocking` inside domain calculators

Both `SharedExpenseManager` and `SettlementCalculator` use `runBlocking` to get home currency fallback.

This can block callers and makes tests harder.

### Fix

Make APIs suspend where currency is needed, or require explicit currency parameter.

Priority: medium.

---

# 3. Investment tracking

## 3.1 Intended architecture

Expected flow:

```text
InvestmentPortfolioScreen
→ InvestmentViewModel
→ InvestmentTracker / InvestmentRepository
→ InvestmentDao / InvestmentValueDao
→ price updates / value history
→ portfolio summary / allocation / performance
→ dashboard widget
```

Current implementation is simpler:

```text
InvestmentViewModel
→ InvestmentTracker
→ InvestmentDao / InvestmentValueDao directly
```

No dedicated repository/factory/quote provider is visible.

---

## 3.2 Strengths

Good pieces:

- `Investment` and `InvestmentValue` tables exist.
- `InvestmentValue` has FK cascade on `investmentId`.
- `InvestmentTracker` computes portfolio summary, performance, allocation, top/worst performers.
- Price update records value history.
- Uses `TimeProvider` for price update timestamps.
- `InvestmentTrackerTest` exists.

---

## 3.3 Investment finding P0-1 — Portfolio raw-sums mixed currencies

`InvestmentTracker.getPortfolioSummary()` does:

```kotlin
totalValue += investment.currentPrice * investment.quantity
totalInvested += (investment.purchasePrice * investment.quantity) + investment.purchaseFees
```

It does not group or convert currencies.

If a portfolio contains:

```text
AAPL in USD
ETF in EUR
BTC in USD
UK stock in GBP
```

the summary raw-sums nominal values and returns one unlabeled `Double`.

### Fix

Return `MoneyAggregate`:

```kotlin
PortfolioSummary(
    totalValue: MoneyAggregate,
    totalInvested: MoneyAggregate,
    totalGainLoss: MoneyAggregate,
    byType: Map<InvestmentType, MoneyAggregate>
)
```

Use historical/current conversion policy explicitly.

Priority: highest.

---

## 3.4 Investment finding P0-2 — Price update is not atomic with history insert

`InvestmentTracker.updatePrice()` does:

```kotlin
investmentDao.updatePrice(...)
investmentValueDao.insert(...)
```

without `database.withTransaction`.

If the second insert fails, current price changes but history is missing.

If the first update succeeds and app crashes before history insert, same problem.

### Fix

Create an `InvestmentRepository.updatePriceAtomic()`:

```text
database.withTransaction {
    update investment current price
    insert investment value snapshot
}
```

Priority: highest.

---

## 3.5 Investment finding P0-3 — Portfolio history undercounts days without snapshots

`getPortfolioValueHistory()` groups by days where each investment has value rows.

It sums only snapshots present on a day.

If investment A has a Monday snapshot and investment B has a Tuesday snapshot, Monday excludes B and Tuesday excludes A.

A portfolio history should usually carry forward the latest known value for each active holding.

### Fix

For each day:

```text
for every active holding:
  use latest snapshot <= dayEnd
  or current price if no snapshot and policy allows
```

Also show data freshness:

```text
stale price count
missing price count
```

Priority: highest.

---

## 3.6 Investment finding P0-4 — No lot/transaction ledger

`Investment` stores:

```text
purchasePrice
quantity
purchaseFees
purchaseDate
```

This supports only one purchase lot.

It cannot represent:

```text
multiple buys
partial sells
dividends
stock splits
fees after purchase
realized gain/loss
deposits/withdrawals
cost basis by method
```

### Fix

Add:

```text
InvestmentTransaction
  investmentId
  type = BUY/SELL/DIVIDEND/FEE/SPLIT/TRANSFER
  quantity
  price
  amount
  currency
  fees
  date
```

Then derive:

```text
quantity
cost basis
realized gains
unrealized gains
```

Priority: high before serious investment use.

---

## 3.7 Investment finding P1-1 — Current investment UI does not show investment performances

`InvestmentViewModel.loadPortfolioData()` sets:

```kotlin
_investments.value = emptyList()
```

So the UI cannot show actual performance list despite `InvestmentTracker` supporting it.

### Fix

Expose:

```text
all active investments flow
performance flow/list
loading/error state
stale price warnings
```

Priority: high.

---

## 3.8 Investment finding P1-2 — DAO aggregate methods disagree with tracker math

`InvestmentDao.getTotalInvestedAmount()` uses:

```sql
SUM(purchasePrice * quantity)
```

But `InvestmentTracker` uses:

```text
purchasePrice * quantity + purchaseFees
```

So DAO summary and tracker summary can disagree.

### Fix

Either include fees in DAO aggregate or remove DAO raw aggregate methods from production use.

Priority: high.

---

## 3.9 Investment finding P1-3 — Investment timestamps and validation are not enforced

`Investment` documents:

```text
createdAt must be set
lastUpdated must be set
```

But the entity defaults to `0L`, and there is no repository/factory ensuring:

```text
price finite and > 0
quantity finite and > 0
currency valid
symbol nonblank
createdAt > 0
lastUpdated > 0
```

### Fix

Create:

```text
InvestmentRepository.addInvestment()
InvestmentFactory
```

and prevent UI/direct code from inserting raw entities.

Priority: high.

---

## 3.10 Investment finding P1-4 — Direct `Dispatchers.IO` instead of injected dispatcher

`InvestmentTracker` uses:

```kotlin
withContext(Dispatchers.IO)
```

instead of `@IoDispatcher`.

This makes tests less deterministic and inconsistent with most newer code.

### Fix

Inject:

```kotlin
@IoDispatcher private val ioDispatcher: CoroutineDispatcher
```

Priority: medium.

---

## 3.11 Investment finding P1-5 — Price staleness is not modeled

Portfolio totals use `currentPrice` no matter how old `lastUpdated` is.

### Fix

Add:

```text
stalePriceThreshold
staleHoldingCount
oldestPriceAge
portfolioDataQuality
```

UI should show:

```text
“3 holdings have stale prices.”
```

Priority: medium-high.

---

# 4. Tax / business expense / mileage

## 4.1 Intended architecture

Expected flow:

```text
TaxConfigurationScreen
→ TaxConfigurationViewModel
→ TaxEstimator
→ BusinessExpenseRepository
→ ExpenseDao / MileageTrackingDao
→ business/tax/mileage summaries
→ export/report
```

Related fields live on `Expense`:

```text
isBusinessExpense
businessCategory
businessPurpose
businessProject
requiresReceipt
effectiveAmount
currency
transactionType
```

---

## 4.2 Strengths

Good pieces:

- `TaxConfiguration` exists.
- `TaxEstimator` supports country-specific bracket configuration.
- `TaxEstimator` uses cumulative progressive brackets, not a single flat rate.
- `BusinessExpenseRepository` enforces purchase-only filtering in several paths.
- DAO aggregate usage avoids capped row scans.
- Mileage validation checks finite, positive distance/rate and `createdAt > 0`.
- Business report generator separates mileage and missing receipts.

---

## 4.3 Tax finding P0-1 — Tax/business totals are not currency-normalized

`BusinessExpenseRepository.getTotalBusinessExpenses()` delegates to DAO aggregate:

```text
SUM(effectiveAmount)
```

`TaxEstimator` uses that value as deductible amount.

`getTaxYearSummary()` gets income from:

```kotlin
expenseDao.getTotalDepositsForPeriod(...)
```

Those sums are raw numeric totals. They do not group/convert by currency.

If user has:

```text
business expense 100 EUR
business expense 100 USD
deposit 1000 GBP
```

tax estimates raw-sum them.

### Fix

Use `MultiCurrencyRepository` / `AnalyticsCurrencyNormalizer` style aggregation:

```text
deductibleExpenses: MoneyAggregate
income: MoneyAggregate
taxCurrency: CurrencyCode
partial warnings
```

For tax, choose explicit tax currency:

```text
country tax currency
or user-selected filing currency
```

Priority: highest.

---

## 4.4 Tax finding P0-2 — Mileage deduction aggregate undercounts null calculated deductions

`BusinessExpenseReportGenerator.generateMileageReport()` correctly computes fallback:

```kotlin
trip.calculatedDeduction ?: (trip.distanceKm * trip.deductionRatePerKm)
```

But `BusinessExpenseRepository.getTotalMileageDeduction()` delegates to DAO:

```sql
SELECT SUM(calculatedDeduction)
```

If `calculatedDeduction` is null, that trip contributes nothing.

`TaxEstimator.getTaxYearSummary()` uses:

```kotlin
businessExpenseRepository.getTotalMileageDeduction(...)
```

So tax-year summary can undercount mileage deductions.

### Fix

Change DAO query:

```sql
SELECT SUM(
  CASE
    WHEN calculatedDeduction IS NOT NULL THEN calculatedDeduction
    ELSE distanceKm * deductionRatePerKm
  END
)
FROM mileage_tracking
WHERE isBusinessTrip = 1
  AND date >= :startDate
  AND date < :endDate
```

Priority: highest.

---

## 4.5 Tax finding P0-3 — User-selected tax country is not persisted or injected consistently

`TaxConfigurationViewModel` has selected country in UI state and calls:

```kotlin
TaxConfigurationFactory.getConfiguration(selectedCountry)
```

But `TaxModule` always provides:

```kotlin
GreeceTaxConfiguration()
```

`TaxConfigurationFactory.getCurrentConfiguration()` also always returns Greece.

So:

- UI selection is local only.
- Default estimator calls still use Greece.
- App restart loses selection.
- Other consumers cannot know selected tax region.

### Fix

Add:

```text
TaxSettingsRepository
  selectedCountry
  filingCurrency
  mileageRatePolicy
  fiscalYearStart
```

Inject effective tax config from settings.

Priority: highest.

---

## 4.6 Tax finding P1-1 — VAT estimation assumes every deductible expense is standard-rate VAT-inclusive

`TaxEstimator` computes:

```kotlin
vatPaid = totalDeductible * (vatRate / (1 + vatRate))
```

This assumes:

```text
all business expenses include standard VAT
all VAT is recoverable
no reduced/exempt rates
no foreign invoices
no reverse-charge
no non-VAT expenses
```

For a personal finance app, this can be a useful rough estimate, but it should be clearly labeled.

### Fix

Add per-expense tax fields:

```text
taxRate
taxAmount
taxIncluded
taxRecoverable
taxCategory
jurisdiction
```

For now, rename to:

```text
estimatedVatPortion
```

and show warning.

Priority: high.

---

## 4.7 Tax finding P1-2 — Business report hardcodes euro formatting

`BusinessExpenseReportGenerator` writes:

```text
€${String.format("%.2f", amount)}
```

even though expenses have currencies and tax configuration can be US/USD.

### Fix

Use:

```text
CurrencyFormatter.format(amount, filingCurrency)
```

or per-currency grouped sections.

Priority: high.

---

## 4.8 Tax finding P1-3 — Business report raw-sums mixed currencies

`BusinessExpenseReportGenerator.generateReport()` does:

```kotlin
totalExpenses += expense.effectiveAmount
```

and groups by:

```text
businessCategory → Double
businessProject → Double
```

No conversion or source buckets.

### Fix

Return `MoneyAggregate` in report fields or group by currency.

Priority: high.

---

## 4.9 Tax finding P1-4 — Business CSV export has weak CSV/formula safety

`BusinessExpenseReportGenerator.generateCSVExport()` quotes fields with commas/quotes/newlines, but it does not neutralize CSV formula injection.

Fields like:

```text
merchant = "=HYPERLINK(...)"
notes = "+cmd"
project = "@..."
```

can become spreadsheet formulas.

### Fix

Use the same hardened CSV cell sanitizer from the export pipeline:

```text
escapeCSV + neutralize formula-leading characters
```

Priority: high.

---

## 4.10 Tax finding P1-5 — Tax configuration rates are hardcoded

`GreeceTaxConfiguration` and `UsTaxConfiguration` are hardcoded and comments say TODO for DB/remote config.

This is fine for demos/tests, but not safe for real filing.

### Fix

Separate:

```text
demo tax config
user-editable tax config
versioned official config source
```

Show:

```text
“Estimate only; verify with tax professional.”
```

The existing note says this, which is good, but UI should keep it visible.

Priority: medium-high.

---

## 4.11 Tax finding P1-6 — Fiscal year assumptions are calendar-year only

`TaxEstimator.getTaxYearSummary(year)` uses:

```text
Jan 1 → Jan 1 next year
```

Some countries/businesses use different fiscal-year periods.

### Fix

Tax settings should include:

```text
fiscalYearStartMonth
fiscalYearStartDay
```

Priority: medium.

---

## 4.12 Tax finding P1-7 — Business/tax updates may bypass transaction lifecycle events

Business classification fields live on `Expense`.

If UI updates:

```text
isBusinessExpense
businessCategory
businessPurpose
businessProject
requiresReceipt
```

through direct DAO/repository methods, those updates should write `TransactionEvent.UPDATED`.

This connects to Pipeline 2’s direct-update bypass issue.

### Fix

Add lifecycle update API:

```text
updateBusinessTaxFields(expenseId, patch, source)
```

Priority: high.

---

# 5. Combined debugging checklist

## Groups/shared expenses

Check:

- [ ] group created with exactly one current user,
- [ ] `currentUserGroupKey` set correctly,
- [ ] members have joinedAt > 0,
- [ ] group createdAt > 0,
- [ ] group default currency valid,
- [ ] group expense currency matches group policy,
- [ ] linked system expense created atomically with group link,
- [ ] side effects run only after final commit,
- [ ] linked expense update writes lifecycle event,
- [ ] custom split JSON validates every member exactly once,
- [ ] equal split excludes members who joined later,
- [ ] payer must be participant,
- [ ] member delete blocked when referenced,
- [ ] hard delete not reachable accidentally,
- [ ] mixed-currency settlement rejected/converted,
- [ ] shared budget offset exposes partial conversion warnings.

## Investment tracking

Check:

- [ ] investment creation validates symbol/name/currency/quantity/price,
- [ ] createdAt and lastUpdated set,
- [ ] price update + history insert atomic,
- [ ] portfolio summary currency-safe,
- [ ] history carries forward latest known values,
- [ ] stale price warnings shown,
- [ ] multiple buys/sells/dividends either unsupported explicitly or modeled,
- [ ] UI displays actual investment performances,
- [ ] DAO and tracker summary formulas agree,
- [ ] backup/restore preserves price history.

## Tax/business

Check:

- [ ] selected tax country persists,
- [ ] tax currency explicit,
- [ ] income and deductions normalized,
- [ ] missing rates produce partial warning,
- [ ] mileage fallback deduction included,
- [ ] VAT estimate clearly approximate,
- [ ] fiscal year configurable,
- [ ] business report currency-safe,
- [ ] CSV formula injection mitigated,
- [ ] business field edits write transaction lifecycle events,
- [ ] missing receipt list uses correct receipt/link state.

---

# 6. Recommended fix plan

## PR 1 — Fix group current-user invariant

Set `currentUserGroupKey` on insert when `isCurrentUser = true`.

Add DB test:

```text
create group with current user succeeds
two current users fail
currentUserGroupKey = groupId
```

Priority: P0.

---

## PR 2 — Fix group transaction side-effect boundary

Use deferred transaction lifecycle create for group system expenses.

Acceptance:

```text
if group link insert fails, no side effects run for rolled-back expense.
```

Priority: P0.

---

## PR 3 — Route shared-expense normalization through transaction lifecycle

Replace direct `ExpenseDao.updateIsSharedExpense/myShareAmount` calls with lifecycle update API.

Priority: P0.

---

## PR 4 — Make settlement/budget offset currency-safe

- Reject or convert mixed-currency settlements.
- Return partial warnings from shared budget offset.
- Use historical rates for period reports.

Priority: P0/P1.

---

## PR 5 — Make investment price updates atomic and currency-safe

- Add `InvestmentRepository`.
- Update price + insert history in one transaction.
- Return `MoneyAggregate` portfolio summary.

Priority: P0/P1.

---

## PR 6 — Fix investment history carry-forward and UI list

- Portfolio history should carry forward latest known values.
- `InvestmentViewModel` should load real performance list.

Priority: P1.

---

## PR 7 — Normalize tax/business totals

- Use tax filing currency.
- Convert income/deductions.
- Propagate partial warnings.

Priority: P0.

---

## PR 8 — Fix mileage deduction aggregate

Change DAO query to fallback to `distanceKm * deductionRatePerKm`.

Priority: P0.

---

## PR 9 — Persist tax settings

Add `TaxSettingsRepository`.

Priority: P1.

---

## PR 10 — Harden business reports/CSV

- No hardcoded euro.
- Formula injection mitigation.
- Per-currency or converted totals.
- Export privacy policy.

Priority: P1.

---

# 7. Tests to add

## Groups

```text
GroupCurrentUserInvariantDbTest
GroupExpenseLifecycleAtomicityTest
GroupLinkedExpenseLifecycleEventTest
SettlementMixedCurrencyContractTest
SharedBudgetOffsetPartialCurrencyTest
GroupHardDeleteSafetyTest
```

## Investment

```text
InvestmentPriceUpdateAtomicityTest
InvestmentPortfolioMultiCurrencyContractTest
InvestmentHistoryCarryForwardTest
InvestmentViewModelLoadsPerformancesTest
InvestmentValidationContractTest
InvestmentDaoTrackerFormulaParityTest
```

## Tax/business

```text
TaxBusinessMultiCurrencyContractTest
MileageDeductionFallbackAggregateTest
TaxSettingsPersistenceTest
BusinessReportCurrencyFormattingTest
BusinessCsvFormulaInjectionTest
BusinessTaxLifecycleUpdateEventTest
FiscalYearBoundaryTaxTest
```

---

# 8. Suggested canonical scenarios

## Scenario A — `group_shared_expense_budget_no_double_count`

Seed:

```text
home currency EUR
group Trip, defaultCurrency EUR
members:
  Me current user
  Alice
  Bob

group expense:
  dinner 90 EUR paid by Alice
  equal split
linked system expense exists
```

Expected:

```text
currentUserGroupKey set
groupExpense linked to system expense
system expense isSharedExpense = true
myShareAmount = 30
TransactionEvent.UPDATED or GROUP_LINKED exists
budget effective spend includes 30, not 90
dashboard/analytics do not double-count group link
settlement says Me pays Alice 30
```

---

## Scenario B — `investment_portfolio_multicurrency_history`

Seed:

```text
home currency EUR
AAPL 10 shares USD, current 100 USD, rate 0.90
ETF 5 shares EUR, current 50 EUR
BTC 0.1 USD, stale/missing rate
price snapshots on different days
```

Expected:

```text
portfolio value = converted USD + EUR
BTC missing/stale creates warning
history carries forward values for days without snapshots
price update writes current price and history atomically
UI performance list non-empty
```

---

## Scenario C — `tax_business_report_currency_mileage_contract`

Seed:

```text
tax country GR, filing currency EUR
business expense 100 EUR
business expense 100 USD with historical rate 0.90
business expense 50 GBP missing rate
deposit/income 1000 EUR
mileage trip:
  distance 100km
  deductionRate 0.30
  calculatedDeduction = null
```

Expected:

```text
deductions = 190 EUR plus GBP missing-rate warning
income = 1000 EUR
mileage deduction = 30 EUR
tax estimate marked partial because GBP missing
business report uses EUR formatting from config/settings
CSV formula fields neutralized
```

---

# 9. Most likely instability sources

Ranked:

1. **Group current-user invariant insert bug.**
2. **Group lifecycle nested transaction side effects.**
3. **Direct shared expense updates bypassing transaction events.**
4. **Mixed-currency settlements without conversion.**
5. **Investment portfolio raw mixed-currency sums.**
6. **Investment price update not atomic with history.**
7. **Investment history not carrying forward values.**
8. **Tax/business raw mixed-currency sums.**
9. **Mileage deduction aggregate ignoring null calculated deductions.**
10. **Tax configuration not persisted or consistently injected.**
11. **Business report hardcoded euro and weak CSV safety.**
12. **UI placeholders for investment list.**

---

# 10. Final recommendation

Stabilize in this order:

```text
1. Fix group current-user invariant.
2. Fix group system-expense deferred side-effect transaction boundary.
3. Route shared expense ownership updates through transaction lifecycle.
4. Make settlement and shared budget offset currency-safe.
5. Make investment portfolio outputs MoneyAggregate-based.
6. Make investment price update/history atomic.
7. Fix investment history carry-forward and UI performance loading.
8. Make tax/business income and deductions currency-normalized.
9. Fix mileage deduction aggregate fallback.
10. Persist tax settings and remove hardcoded report currency.
11. Add DB-backed scenarios for group, investment, and tax roundtrips.
```

Guiding rule:

> Shared expenses, investments, and taxes must not quietly produce confident numbers from partial, mixed-currency, or stale data.

Second guiding rule:

> Any feature that mutates `Expense` financial meaning — shared ownership, business tax flags, linked group state — must go through the transaction lifecycle and write an event.

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

## Groups / shared expenses

- `SharedExpenseManager.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt

- `SharedExpensePort.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt

- `SharedExpenseDataPortAdapter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt

- `GroupTransactionCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- `SplitCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt

- `CustomSplitParser.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitParser.kt

- `SettlementCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SettlementCalculator.kt

- `SharedExpenseBudgetOffsetEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

- `ExpenseGroup.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExpenseGroup.kt

- `GroupMember.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupMember.kt

- `GroupExpense.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt

- `GroupMemberDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupMemberDao.kt

- `GroupExpenseDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt

## Investment

- `InvestmentTracker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt

- `Investment.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Investment.kt

- `InvestmentValue.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/InvestmentValue.kt

- `InvestmentDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/InvestmentDao.kt

- `InvestmentValueDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt

- `InvestmentViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/investment/InvestmentViewModel.kt

## Tax / business / mileage

- `TaxConfiguration.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/tax/TaxConfiguration.kt

- `TaxEstimator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt

- `TaxConfigurationViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/tax/TaxConfigurationViewModel.kt

- `TaxModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/di/TaxModule.kt

- `BusinessExpenseRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt

- `BusinessExpenseReportGenerator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt

- `MileageTracking.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/MileageTracking.kt

- `MileageTrackingDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/MileageTrackingDao.kt

## Tests / inventory

- Test inventory:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docsplans/_all_rel_paths.txt