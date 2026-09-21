# Engine 4 Implementation Plan — Groups / Investment / Tax

Current verdict: **YELLOW / red-leaning**  
Goal: harden groups, investment, tax/business without regressing shared-expense, budget, analytics, investment UI, export, or backup/restore pipelines.

Do **not** start with schema changes.  
Do **not** combine groups + investment + tax in one PR.  
Do **not** change global money/currency primitives.

---

# Priority strategy

Engine 4 has three semi-independent areas:

1. **Groups** — shared expenses, balances, settlements, budget offsets.
2. **Investment** — portfolio summary/performance/history.
3. **Tax/business** — tax estimates, business reports, CSV/export.

Fix order should be:

```text
PR1 Groups no-schema invariants
PR2 Investment validation/UI aggregate safety
PR3 Business report export safety
PR4 Group lifecycle atomicity
PR5 Group balance correctness
PR6 Investment summary/allocation/history cleanup
PR7 Tax currency/FX basis correctness
PR8 Deferred schema-backed improvements
```

---

# PR1 — Group no-schema invariant hardening

## Closes

- `E4-NOW-002` createGroup can preserve `joinedAt = 0`
- `E4-NOW-003` system expense group-link may bypass group currency
- `E4-NOW-006` settlement validation weak, partially

## Files

```text
GroupLifecycleCoordinator.kt
GroupTransactionCoordinator.kt
GroupSettlementEntity.kt if needed
group tests
```

## Implementation

### 1. Normalize initial member `joinedAt`

During group creation, before insert:

```kotlin
val now = timeProvider.now()
members.map {
    it.copy(joinedAt = if (it.joinedAt > 0L) it.joinedAt else now)
}
```

Ensure current user member also gets nonzero timestamp.

### 2. Enforce currency in `createSystemExpenseAndLinkToGroup`

Inside transaction after loading group:

```kotlin
require(currency == group.defaultCurrency) {
    "Group expenses must use group currency ${group.defaultCurrency}"
}
```

Also validate the `currency` parameter used for `GroupExpense`.

### 3. Validate settlements

Before insert:

```kotlin
require(amount.isFinite() && amount > 0.0)
require(fromMemberId != toMemberId)
```

Do not add idempotency key yet.

## Tests

```text
createGroup_setsJoinedAtForInitialMembers()
createGroup_preservesExistingNonZeroJoinedAt()
createSystemExpenseAndLinkToGroup_rejectsCurrencyMismatch()
recordSettlement_rejectsZeroNegativeNaNInfinity()
recordSettlement_rejectsSelfSettlement()
validGroupCreateAddExpenseSettlement_stillWorks()
```

## Pipeline tests

```text
sharedExpense_createGroupFlow_stillWorks()
sharedExpense_addExpense_stillWorks()
budgetOffset_groupExpenseStillIncluded()
```

## Risk

Medium, no schema.

---

# PR2 — Investment validation and UI aggregate safety

## Closes

- `E4-NOW-008`
- `E4-NOW-010`
- `E4-NOW-011`

## Files

```text
InvestmentTracker.kt
InvestmentViewModel.kt
InvestmentPerformance model
investment tests
```

## Implementation

### 1. Validate investment inputs

In `addHolding()`:

```kotlin
require(symbol.trim().isNotBlank())
require(name.trim().isNotBlank())
require(quantity.isFinite() && quantity > 0.0)
require(purchasePrice.isFinite() && purchasePrice > 0.0)
require(currency.trim().uppercase().matches(Regex("^[A-Z]{3}$")))
require(purchaseDate > 0L)
```

Also validate optional/current fields:

```kotlin
currentPrice == null || currentPrice.isFinite() && currentPrice > 0.0
fees == null || fees.isFinite() && fees >= 0.0
```

In `updatePrice()`:

```kotlin
require(newPrice.isFinite() && newPrice > 0.0)
```

### 2. Expose aggregate/data quality to ViewModel

Stop discarding:

```kotlin
val (summary, aggregate, dataQuality) = ...
```

Create UI state containing:

```text
raw summary for legacy display if needed
portfolioValueAggregate
costBasisAggregate
gainLossAggregate
dataQuality
isPartial
warningMessage
```

### 3. Per-row aggregate

For row performances, ensure aggregate belongs to that holding only, not whole portfolio.

## Tests

```text
addHolding_rejectsNaNInfinityInvalidCurrencyBlankSymbolDateZero()
updatePrice_rejectsZeroNegativeNaNInfinity()
validAddHolding_insertsInvestmentValueAndBuyTransaction()
viewModel_exposesPortfolioAggregateAndDataQuality()
investmentPerformance_rowAggregateContainsOnlyThatHolding()
```

## Pipeline tests

```text
investmentScreen_validPortfolioStillLoads()
investmentScreen_multicurrencyShowsAggregateWarning()
portfolioSummary_doesNotUseRawMixedCurrencyAsPrimaryTotal()
```

## Risk

Medium, no schema.

---

# PR3 — Business report export safety quick win

## Closes

- `E4-NOW-016` partially
- `T05`
- `T07`
- part of `T06`

## Files

```text
BusinessExpenseReportGenerator.kt
CsvCellSanitizer if exists
TaxSettingsRepository.kt maybe
business report tests
```

## Implementation

### 1. Inject dispatcher

Replace:

```kotlin
withContext(Dispatchers.IO)
```

with injected:

```kotlin
@IoDispatcher private val ioDispatcher: CoroutineDispatcher
```

### 2. Formula-safe CSV

Use existing `CsvCellSanitizer` if available.

Sanitize every text cell:

```text
merchant
businessPurpose
businessCategory
businessProject
notes
location fields
```

Neutralize leading:

```text
= + - @ tab CR
```

### 3. Remove hardcoded euro

Minimum no-schema fix:

- use report currency from settings if available
- otherwise use explicit provided currency
- never inline `"€"` directly

If full currency conversion is not done yet, label totals as:

```text
"Amounts are source-currency/raw; mixed-currency report is partial"
```

Better:

- MoneyAggregate-backed totals in later PR.

### 4. Add warning metadata

Report should include:

```text
isPartial
warningMessage
displayCurrency
```

if mixed currency is detected but not fully converted.

## Tests

```text
businessCsv_neutralizesFormulaMerchantNotesProject()
businessReport_nonEurDoesNotHardcodeEuro()
businessReport_usesInjectedDispatcher()
businessReport_mixedCurrencyMarksPartial()
```

## Risk

Medium. Hilt impact if constructor changes.

---

# PR4 — Group lifecycle event atomicity

## Closes

- `E4-NOW-001`

## Files

```text
GroupLifecycleCoordinator.kt
GroupTransactionCoordinator.kt
GroupLifecycleEventDao.kt
```

## Implementation options

### Preferred

Coordinator owns transaction:

```kotlin
database.withTransaction {
    mutationDbOnly()
    lifecycleEventDao.insert(...)
}
postCommitSideEffects()
```

### Alternative

Low-level coordinator accepts callback:

```kotlin
createGroupWithMembersAtomic(..., onInsideTransaction = { eventDao.insert(...) })
```

Do not emit lifecycle event in a separate transaction if it is supposed to audit the mutation.

## Tests

```text
createGroup_mutationAndLifecycleEventAtomic()
addMember_mutationAndLifecycleEventAtomic()
removeMember_deleteAndLifecycleEventAtomic()
recordSettlement_insertAndLifecycleEventAtomic()
archiveGroup_eventAtomic()
```

## Risk

Medium/high. No schema if using existing events.

---

# PR5 — Group balance correctness

## Closes

- `E4-NOW-004`
- `E4-NOW-005`

## Files

```text
GroupBalanceCalculator.kt
SplitCalculator.kt
GroupSettlementDao.kt
GroupMemberDao.kt
```

## Implementation

### 1. Historical participation

Do not use current member count for old equal splits.

For each group expense:

- get expense date
- get members active at that date using `joinedAt`
- compute share using `SplitCalculator`

If member removal date is not tracked, document limitation and at least stop newly-added later members from changing old expenses.

### 2. Settlement filters

Only include settlements with active status:

```text
RECORDED
COMPLETED
```

and matching group/default currency.

## Tests

```text
newMemberAddedLater_doesNotShareOldEqualExpense()
memberBalance_usesJoinedAtParticipation()
cancelledSettlement_doesNotAffectBalance()
foreignCurrencySettlement_ignoredOrDiagnostic()
```

## Risk

High for group balances. Do after PR1.

---

# PR6 — Investment summary/allocation/history cleanup

## Closes

- `E4-NOW-009`
- `E4-NOW-012`
- `E4-NOW-013`

## Files

```text
InvestmentTracker.kt
InvestmentViewModel.kt
PortfolioSummary models
```

## Implementation

### 1. New safe model

Create:

```kotlin
data class PortfolioSummaryAggregate(
    val totalValue: MoneyAggregate,
    val costBasis: MoneyAggregate,
    val gainLoss: MoneyAggregate,
    val dataQuality: InvestmentDataQuality,
    val valuationBasis: String
)
```

Keep raw `PortfolioSummary` deprecated.

### 2. Allocation same-source rule

Use same value source for numerator and denominator:

- latest value if all have latest
- else current price × quantity with warning

### 3. History aggregate

Replace raw daily `Double` total with aggregate/dataQuality per day.

## Tests

```text
aggregateSummary_noRawCrossCurrencyPrimaryTotal()
allocation_percentagesUseSameSourceAndSumToOne()
portfolioHistory_multicurrencyUsesAggregateAndWarnings()
```

## Risk

Medium/high. UI model changes likely.

---

# PR7 — Tax currency and FX-basis correctness

## Closes

- `E4-NOW-014`
- `E4-NOW-015`
- parts of `T01`, `T03`, `T08`, `T09`

## Files

```text
TaxEstimator.kt
TaxSettingsRepository.kt
TaxRateProvider.kt
ExpenseDao tax queries
```

## Implementation

### 1. Filing currency is tax calculation currency

Tax calculations should target:

```text
taxSettings.filingCurrency
```

Do not silently use home currency for tax estimate totals.

If home currency differs, surface warning.

### 2. Income must have currency

Replace raw:

```kotlin
estimatedAnnualIncome: Double
```

with:

```text
MoneyAmount/MoneyAggregate or amount + currency
```

If too broad, add overload and deprecate raw one.

### 3. Historical FX

For expenses/income:

- convert per transaction date using `convertAsOf(expense.date)`
- or group by currency + rate date

Do not use latest/current rate for closed tax years.

### 4. Settings validation

Validate:

- country code
- currency code
- fiscal month/day combination

Prefer durable persistence if tax-critical.

## Tests

```text
taxEstimate_usesFilingCurrency()
taxEstimate_homeCurrencyDifferent_addsWarning()
taxIncome_requiresCurrency()
taxDeductions_useTransactionDateFx()
taxClosedYearStableWhenLatestRateChanges()
taxSettings_rejectsInvalidCurrencyAndFeb31()
```

## Risk

High. Do after business quick win.

---

# PR8 — Deferred schema-backed improvements

Only after DB baseline is stable.

Possible migrations:

```text
settlement idempotency key
group member removal/active interval history
investment transaction FK + lot ledger
daily portfolio aggregate cache
business/tax report run ledger
tax rate metadata snapshot
```

Each must be separate with migration tests.

---

# Engine 4 specific non-regression checklist

## Groups — lifecycle and writes

- [ ] Group creation still works.
- [ ] Initial current user invariant still holds.
- [ ] Every initial member has nonzero `joinedAt`.
- [ ] Add member still works.
- [ ] Add member sets nonzero `joinedAt`.
- [ ] Remove member still blocks removing last/current user when required.
- [ ] Archive group still works.
- [ ] Hard delete still requires explicit confirmation and archive-first if using lifecycle path.
- [ ] Group writes respect `DatabaseWriteBarrier`.
- [ ] Lifecycle events are written for user-visible mutations.
- [ ] Mutation/event atomicity is either guaranteed or documented.

## Groups — expenses and side effects

- [ ] Standalone group expense uses group default currency.
- [ ] System expense + group link rejects currency mismatch.
- [ ] Existing linked expense currency mismatch is rejected.
- [ ] Linked ownership update still goes through transaction lifecycle DB-only path.
- [ ] Side effects dispatch only after outer group transaction commits.
- [ ] Failure inside group transaction does not dispatch budget/analytics side effects.
- [ ] Shared expense flags are consistent after hard delete.

## Groups — balances and settlements

- [ ] Equal split for old expense does not change when new member is added later.
- [ ] Balance calculation respects member participation date where possible.
- [ ] Cancelled/pending settlements do not affect balance.
- [ ] Foreign-currency settlement is rejected or ignored with diagnostic.
- [ ] Settlement amount must be finite and positive.
- [ ] Self-settlement is rejected.
- [ ] Valid settlement still updates balances.
- [ ] Double-tap duplicate settlement is either prevented or documented deferred.

## Shared budget offsets

- [ ] Shared group expense still contributes to adjusted budget spend.
- [ ] Conversion uses historical `convertAsOf`.
- [ ] Conversion failure marks partial state.
- [ ] Source currency warning remains visible.
- [ ] Archived group historical obligations are handled or documented deferred.
- [ ] Personal/shared/reimbursed amounts reconcile.

## Investment — writes and validation

- [ ] Add holding still works for valid input.
- [ ] Add holding writes Investment + InvestmentValue + BUY transaction atomically.
- [ ] Add holding rejects NaN/infinity quantity/price.
- [ ] Add holding rejects invalid currency.
- [ ] Add holding rejects blank symbol/name.
- [ ] Add holding rejects purchaseDate `0`.
- [ ] Update price still works for valid positive finite price.
- [ ] Update price rejects zero/negative/NaN/infinity.
- [ ] Investment writes respect `DatabaseWriteBarrier`.

## Investment — UI/summary/performance

- [ ] Investment screen loads holdings.
- [ ] Investment screen loads performance rows.
- [ ] Portfolio summary exposes MoneyAggregate/dataQuality.
- [ ] Raw mixed-currency summary is not primary display for multi-currency holdings.
- [ ] Per-row aggregate contains only that holding.
- [ ] Stale/missing price warnings remain visible.
- [ ] Allocation chart percentages use same numerator/denominator source.
- [ ] Allocation sums approximately to 100% when complete.
- [ ] Portfolio history does not raw-sum mixed currencies as final truth.

## Tax

- [ ] Tax estimate still runs.
- [ ] Tax year summary still runs.
- [ ] Filing currency is explicit.
- [ ] Home currency fallback does not silently relabel tax numbers.
- [ ] Estimated income has explicit currency or raw overload is deprecated.
- [ ] Deductions/income use historical FX for closed periods.
- [ ] VAT estimate remains clearly estimate-only.
- [ ] Fiscal year start is respected.
- [ ] Invalid tax settings are rejected.

## Business report / export

- [ ] Business report still generates.
- [ ] Business CSV still generates.
- [ ] CSV cells neutralize formula injection.
- [ ] Report does not hardcode euro for non-EUR users.
- [ ] Mixed-currency report is aggregate-backed or marked partial.
- [ ] Merchant/purpose/project/notes respect privacy/export mode if available.
- [ ] Mileage report still computes fallback deduction.
- [ ] Report generator uses injected dispatcher, not direct `Dispatchers.IO`.

## Backup/restore/write barrier

- [ ] Group writes blocked during restore.
- [ ] Investment writes blocked during restore.
- [ ] Tax/business report generation does not mutate DB unexpectedly.
- [ ] Raw DAO writes are not introduced in user-facing paths.
- [ ] No destructive migration or rescue rerun.

## Tests/static review

- [ ] Engine unit tests added for each fix.
- [ ] Pipeline tests added for group/budget/investment/tax consumers.
- [ ] No `@Ignore`.
- [ ] No weak assertions only checking non-null.
- [ ] Tests include invalid money/currency cases.
- [ ] Tests use fixed time provider where timestamps matter.
- [ ] Static review confirms no side effects before commit.
- [ ] Static review confirms no raw mixed-currency display regression.

## Build/schema discipline

- [ ] No schema change in PR1–PR7 unless explicitly planned.
- [ ] No global `CurrencyConverter` semantics change.
- [ ] No global `MoneyAmount` representation change.
- [ ] No broad Hilt rewiring in one PR.
- [ ] Any migration has schema JSON and migration test.
- [ ] DB baseline v145 remains stable.

---

# Final validation commands

Only after all Engine 4 PRs are complete:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration added:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# Definition of done

Engine 4 is clean when:

- group invariants hold in lifecycle and low-level paths
- group side effects dispatch post-commit only
- lifecycle events are atomic or explicitly diagnostic
- balances use historical participation and valid settlements
- investment validation rejects corrupt financial values
- investment UI exposes aggregate/dataQuality, not raw mixed totals
- allocation/history are currency-safe or marked partial
- tax uses filing currency and historical FX basis
- business reports are currency-safe, formula-safe, and privacy-aware
- all affected pipelines have regression tests