# Engine 4 Current Audit — Groups / Investment / Tax

Target branch inspected: `fix/pipeline-1-5-local-issues`  
Mode: static GitHub inspection only.  
No Gradle, compile, KSP, Hilt, Room, lint, or tests were run.

## Self-review verdict

**YELLOW / RED-LEANING**

Engine 4 is **significantly improved** compared with the old tracker, especially in groups and investment write-safety. But it is **not clean**.

The strongest improvements:

- `GroupLifecycleCoordinator` now injects and checks `DatabaseWriteBarrier`.
- `GroupTransactionCoordinator` also checks `DatabaseWriteBarrier` on group writes.
- Linked group expense side effects are now collected as `PostCommitActionBatch` and run after the outer transaction.
- `GroupTransactionCoordinator.addMemberToGroup()` sets `joinedAt = timeProvider.now()`.
- Standalone group expense low-level path now enforces single-currency policy.
- Hard-delete cleanup of shared expense flags is now inside the transaction and writes a bulk transaction event.
- `InvestmentTracker` now injects `DatabaseWriteBarrier`.
- `InvestmentTracker.addHolding()` writes investment + initial value + BUY transaction atomically.
- `InvestmentTracker.updatePrice()` is transaction-wrapped.
- `InvestmentViewModel` no longer sets `investments = emptyList()`; it loads performances.
- `TaxEstimator` has MoneyAggregate fields and TaxSettings/TaxRateProvider integration.
- Fiscal year settings exist.
- Mileage null fallback is fixed.

The biggest remaining problems:

1. Group lifecycle events are still not consistently atomic with mutations.
2. Group balance calculation is still historically wrong.
3. Settlement validation is still weak.
4. Some low-level group paths still bypass lifecycle semantics.
5. Investment UI still consumes raw mixed-currency `PortfolioSummary`.
6. Investment aggregate API still returns raw mixed-currency `PortfolioSummary`.
7. Investment row performances loaded by ViewModel do not carry aggregates/data quality.
8. Investment validation still accepts non-finite values and weak currency.
9. Investment allocation/history still have currency/data-source problems.
10. Tax conversion basis is latest/current-rate style, not transaction-date historical.
11. Tax filing currency and home currency semantics are mixed.
12. Business reports still raw-sum, hardcode euro, use direct `Dispatchers.IO`, and lack formula-injection protection.

Verdict:

> Better than the old report, but still not safe to call “clean.”  
> Treat Engine 4 as **beta-stable for basic use**, not hardened.

---

# 1. Engine scout

## Engine

Engine 4 — Groups / Investment / Tax.

Sub-engines:

- `GroupLifecycleCoordinator`
- `GroupTransactionCoordinator`
- `GroupBalanceCalculator`
- `SharedExpenseBudgetOffsetEngine`
- `SettlementCalculator`
- `InvestmentTracker`
- `InvestmentViewModel`
- `TaxEstimator`
- `TaxSettingsRepository`
- `TaxRateProvider`
- `BusinessExpenseReportGenerator`
- `BusinessExpenseRepository`

## Risk level

High.

From the architecture docs, `GroupLifecycleCoordinator` affects groups, expenses, budget offsets, and analytics. `GroupTransactionCoordinator` affects shared expenses and budget offsets. Tax affects tax reports/export. Investment is lower blast-radius but still money-sensitive.

## Affected pipelines

| Area | Affected pipelines |
|---|---|
| Groups | Shared expense groups, enhanced splits, budget offsets, expense lifecycle, analytics |
| Shared budget offset | Budget management, dashboard, analytics |
| Investment | Investment UI, portfolio widgets, price history |
| Tax | Tax reports, business reports, export/accounting |
| Business reports | Export, tax prep, privacy-sensitive CSV/text output |
| Write barrier | Backup/restore maintenance safety |

## Schema/migration impact

Current audit does not require immediate schema changes.

Potential future migrations:

- settlement idempotency key
- group member historical participation snapshot
- investment transaction FK / ledger expansion
- merchant/business report run diagnostics
- business report aggregate tables or report-run ledger
- tax report run metadata
- category/project aggregate provenance

Given recent DB recovery, defer schema unless absolutely necessary.

## Hilt/DI impact

Potential future DI changes:

- inject `@IoDispatcher` into `BusinessExpenseReportGenerator`
- inject `CurrencyConverter` / `TaxSettingsRepository` into `BusinessExpenseReportGenerator`
- introduce `InvestmentValuationPolicy`
- introduce `GroupLifecycleEventWriter`
- possibly add dedicated `BusinessReportCurrencyPolicyResolver`

---

# 2. Positive findings

## Groups

### 2.1 Write barrier coverage improved

`GroupLifecycleCoordinator` injects `DatabaseWriteBarrier` and checks it in user-facing write methods.

`GroupTransactionCoordinator` also checks the barrier in low-level methods.

This is a major improvement over the old report.

### 2.2 Linked expense side effects improved

`GroupTransactionCoordinator.addExpenseWithLink()` uses `updateOwnershipDbOnlyV2()` and collects `PostCommitActionBatch`. It runs post-commit actions only after the outer transaction returns success.

This substantially improves the old “side effects inside outer transaction” bug.

### 2.3 Group member joinedAt improved in add-member path

`GroupTransactionCoordinator.addMemberToGroup()` creates `GroupMember(joinedAt = timeProvider.now())`.

So add-member is improved.

### 2.4 Low-level standalone group expense currency check exists

`addExpenseToGroup()` now rejects provided currency mismatching `group.defaultCurrency`.

### 2.5 Hard delete cleanup improved

`deleteGroupAtomic()` now:

- gathers linked expense IDs
- deletes group rows
- clears shared flags inside the transaction
- writes a bulk transaction event inside the transaction
- runs post-commit bulk side effects after commit

This is much better than the old non-atomic cleanup.

---

## Investment

### 2.6 Write barrier added

`InvestmentTracker` injects `DatabaseWriteBarrier`.

`addHolding()` and `updatePrice()` check it.

### 2.7 Add holding is atomic

`addHolding()` inserts:

- `Investment`
- initial `InvestmentValue`
- BUY `InvestmentTransaction`

inside one transaction.

### 2.8 UI now loads actual performances

`InvestmentViewModel.loadPortfolioData()` loads holdings and maps each to `getInvestmentPerformance()`. The old “emptyList” issue is fixed.

### 2.9 Home currency no silent EUR fallback in ViewModel

`InvestmentViewModel` collects `homeCurrency()` into nullable state; it does not default to EUR there.

---

## Tax / Business

### 2.10 Tax settings exist

`TaxSettingsRepository` stores:

- tax country
- filing currency
- fiscal year start month/day
- VAT enabled
- business report currency policy

### 2.11 TaxRateProvider exists

`TaxRateProvider` has metadata and confidence fields, and is wired into VAT lookup.

### 2.12 TaxEstimator has MoneyAggregate fields

`TaxEstimate` and `TaxYearSummary` include aggregate structures.

### 2.13 Mileage fallback fixed

`BusinessExpenseReportGenerator.generateMileageReport()` uses:

```text
calculatedDeduction ?: distanceKm * deductionRatePerKm
```

So the null mileage deduction undercount is fixed.

---

# 3. Old issue reconciliation

## G01 — Current-user member invariant

Old tracker: FIXED  
Current status: **MOSTLY FIXED**

Evidence:

- `GroupLifecycleCoordinator.createGroup()` validates exactly one current user.
- `addMember()` rejects adding another current user.
- low-level `GroupTransactionCoordinator.addMemberToGroup()` sets `currentUserGroupKey`.

Remaining gap:

- direct low-level `createGroupWithMembersAtomic()` only validates “at most one” current user, not exactly one.
- direct DAO paths can still bypass domain-level invariant if used.
- lifecycle `createGroup()` passes supplied `members` to coordinator; it does not normalize `joinedAt`.

Decision: **mostly fixed / guard direct low-level paths**

---

## G02 — Group expense side effects inside outer transaction

Old tracker: FIXED  
Current status: **MOSTLY FIXED**

Evidence:

- `addExpenseWithLink()` uses DB-only ownership update and post-commit action batch.
- `createSystemExpenseAndLinkToGroup()` uses `createExpenseDbOnlyV2()` and post-commit action batch.

Remaining gaps:

- group lifecycle event emission itself is often after the mutation transaction, not always atomic with mutation.
- if event insert fails after mutation, audit trail can be missing.

Decision: **mostly fixed for transaction side effects, partial for lifecycle audit atomicity**

---

## G03 — Linked expense normalization bypasses lifecycle

Old tracker: PARTIAL  
Current status: **MOSTLY FIXED**

Evidence:

- linked ownership update goes through `TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2()`.
- success path verifies the row was actually updated.
- post-commit effects run after the group transaction.

Remaining gap:

- direct hard-delete path still clears shared flags through DAO, though now with bulk event and post-commit actions.

Decision: **mostly fixed**

---

## G04 — Mixed-currency settlements

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

`GroupLifecycleCoordinator.recordSettlement()` rejects `currency != group.defaultCurrency`.

Remaining gaps:

- no amount validation
- no `fromMemberId != toMemberId` validation
- no duplicate/idempotency key
- low-level settlement DAO remains a direct write path
- `GroupBalanceCalculator` does not filter settlement status/currency

Decision: **partial**

---

## G05 — Group currency consistency

Old tracker: FIXED  
Current status: **PARTIAL / better than before**

Evidence:

Fixed:

- `GroupLifecycleCoordinator.addExpense()` validates currency.
- `GroupTransactionCoordinator.addExpenseToGroup()` low-level standalone path validates currency.
- `addExpenseWithLink()` validates linked system expense currency against group currency.

Remaining gap:

- `createSystemExpenseAndLinkToGroup()` does not visibly check `currency == group.defaultCurrency`; it uses the passed currency to create both system and group expense.
- `addExpenseWithLink()` validates existing system expense currency but does not clearly validate the separate `currency` parameter before storing it in `GroupExpense`.

Decision: **partial**

---

## G06 — Shared budget offsets drop conversion failures

Old tracker: PARTIAL  
Current status: **PARTIAL**

Evidence:

- `BudgetSpendBreakdown` has partial/warning fields.
- conversions use `convertAsOf()`.
- `failedConversionCount` and warnings exist.

Remaining gap:

- aggregates are rebuilt as `MoneyAggregate.singleCurrency(...)`.
- source currency buckets and conversion failure provenance are not preserved inside the aggregate.
- conversion warnings exist separately, but aggregate debugging remains weak.

Decision: **partial**

---

## G07 — Shared offset historical rates

Old tracker: FIXED  
Current status: **MOSTLY FIXED**

Evidence:

`SharedExpenseBudgetOffsetEngine` uses `currencyConverter.convertAsOf(... atMillis = expense.date)` for personal, shared, and reimbursed amounts.

Remaining caveats:

- home currency resolution still controls target currency.
- if converter falls back internally, audit depends on converter metadata.
- source buckets are lost in final aggregate.

Decision: **mostly fixed**

---

## G08 — Hard delete path bypass

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Improved:

- `GroupLifecycleCoordinator.deleteGroupPermanently()` requires explicit confirmation and archive-first.
- `GroupTransactionCoordinator.deleteGroupAtomic()` now clears shared flags inside transaction and writes a bulk transaction event.

Still open:

- `GroupTransactionCoordinator.permanentlyDeleteGroup()` remains public and bypasses lifecycle coordinator’s confirmation/archive-first rule.
- direct hard delete writes transaction bulk event, not group lifecycle event.
- comments still say it bypasses lifecycle coordinator.

Decision: **partial**

---

## G09 — Direct member delete

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Improved:

- `GroupLifecycleCoordinator.removeMember()` checks write barrier.
- wraps balance check + delete in transaction.

Remaining gaps:

- event emission happens after the transaction, not inside it.
- balance calculation itself remains historically inaccurate.
- direct DAO/low-level paths can bypass.

Decision: **partial**

---

## G10 — runBlocking in domain calculators

Old tracker: DEFERRED_DESIGN  
Current status: **NOT OBSERVED in inspected files**

No runBlocking observed in inspected group files.

Decision: **probably fixed/not present, but needs repo grep before closing**

---

## I01 — Portfolio raw sums mixed currencies

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

- `getPortfolioSummaryAggregate()` returns `MoneyAggregate`.

But:

- it also returns `PortfolioSummary` whose `totalValue`, `totalInvested`, `gainLoss`, and `byType` are raw sums across holdings.
- `InvestmentViewModel` uses `(summary, _, _)`, discarding aggregate and dataQuality.
- UI state still exposes raw `PortfolioSummary`.

Decision: **partial / UI still effectively raw**

---

## I02 — Price update atomic

Old tracker: FIXED  
Current status: **MOSTLY FIXED**

Evidence:

`updatePrice()` wraps DAO update + value history insert in transaction and checks write barrier.

Remaining issue:

- `newPrice` is not validated for finite/positive values.

Decision: **mostly fixed with validation gap**

---

## I03 — Portfolio history carry-forward

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Carry-forward algorithm exists.

Remaining gaps:

- daily totals are raw `Double`.
- mixed currencies are summed into one number.
- data quality only marks missing history, not currency conversion failure/staleness per day.
- uses system default zone.

Decision: **partial**

---

## I04 — Transaction ledger

Old tracker: FIXED  
Current status: **PARTIAL / shell**

Evidence:

- `InvestmentTransaction` is written for initial BUY.

Remaining gaps:

- no SELL/DIVIDEND/FEE/SPLIT flow observed.
- no realized gain/cost basis lifecycle.
- old report mentioned FK gap; not reverified here.

Decision: **partial shell**

---

## I05 — UI performance

Old tracker: PARTIAL  
Current status: **PARTIAL**

Evidence:

Improved:

- ViewModel now loads non-empty performances using `getInvestmentPerformance()`.

Still weak:

- `getInvestmentPerformance()` returns raw current value/gain/loss and does not populate aggregate fields or data quality.
- ViewModel does not use `getInvestmentPerformances()`.
- even `getInvestmentPerformances()` attaches portfolio-level aggregate to each row, not per-row aggregate.

Decision: **partial**

---

## I06 — DAO aggregates disagree

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Raw `getPortfolioSummary()` is deprecated, but remains callable.

No static guard verified.

Decision: **partial**

---

## I07 — Investment timestamps/validation

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

`addHolding()` sets created/updated timestamps.

Validation only checks:

- quantity > 0
- purchasePrice > 0
- currency nonblank

Still allows:

- `Double.POSITIVE_INFINITY`
- `NaN` edge cases depending comparison behavior
- invalid currency like `123`
- blank symbol/name
- purchaseDate = 0
- bad currentPrice / fees / target / stop-loss

Decision: **partial**

---

## I08 — Injected dispatcher

Old tracker: FIXED  
Current status: **FIXED for InvestmentTracker**

`@IoDispatcher` is injected.

Decision: **fixed**

---

## I09 — Price staleness

Old tracker: PARTIAL  
Current status: **PARTIAL**

Evidence:

- 7-day/30-day thresholds exist.
- data quality has stale/missing counts.

Remaining gaps:

- thresholds are constants, not settings.
- ViewModel discards aggregate data quality from summary.
- per-row performance loaded via `getInvestmentPerformance()` lacks dataQuality.

Decision: **partial**

---

## T01 — Tax totals currency normalized

Old tracker: PARTIAL  
Current status: **PARTIAL / still risky**

Evidence:

- `buildDeductibleAggregate()` and `buildIncomeAggregate()` group by currency and use `MoneyAggregateBuilder`.

Problems:

- builder likely uses current/latest conversion, not per-transaction date basis.
- target currency uses home currency if available, else filing currency.
- tax estimate notes say filing currency.
- `estimatedAnnualIncome` is raw `Double` with no currency.
- `getTaxYearSummary()` computes `incomeAggregate.displayAmount` then passes it as raw annual income into `estimateTaxes()`, which treats output as filing-context.

Decision: **partial / currency semantics inconsistent**

---

## T02 — Mileage null deduction

Old tracker: FIXED  
Current status: **FIXED**

Evidence:

`generateMileageReport()` uses fallback `distanceKm * deductionRatePerKm`.

Decision: **fixed**

---

## T03 — Tax country/settings persisted

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Settings exist.

Remaining gaps:

- country/currency setters accept arbitrary strings.
- fiscal day/month can become invalid combo, e.g. February 31.
- uses `SharedPreferences.apply()`, async persistence.

Decision: **partial**

---

## T04 — VAT estimation

Old tracker: DEFERRED_DESIGN  
Current status: **STILL ESTIMATE ONLY**

Evidence:

VAT provider supplies standard VAT rate. Estimator computes VAT as a factor of deductible aggregate.

Decision: **still estimate-only / not actual VAT accounting**

---

## T05 — Business report hardcoded euro formatting

Old tracker: FIXED  
Current status: **OPEN**

Evidence:

`BusinessExpenseReportGenerator` still writes hardcoded euro symbol in formatted report.

Examples visible in source:

- `Total Business Expenses: €...`
- category/project lines with `€...`
- mileage rate/deduction with `€...`
- top expenses/missing receipts with `€...`

Decision: **reopen**

---

## T06 — Business report mixed currency

Old tracker: PARTIAL  
Current status: **OPEN**

Evidence:

`BusinessExpenseReportGenerator.generateReport()`:

- sums `expense.effectiveAmount`
- groups by `businessCategory` raw
- groups by `businessProject` raw
- does not use `MoneyAggregate`
- does not inject `CurrencyConverter` or `TaxSettingsRepository`

Decision: **open**

---

## T07 — Business CSV formula safety

Old tracker: FIXED  
Current status: **OPEN**

Evidence:

`escapeCSV()` only quotes comma/quote/newline. It does not neutralize formula-leading values like `=`, `+`, `-`, `@`.

No `CsvCellSanitizer` reference in `BusinessExpenseReportGenerator`.

Decision: **reopen**

---

## T08 — Tax rates provider

Old tracker: PARTIAL  
Current status: **PARTIAL**

Evidence:

`TaxRateProvider` exists, but source comment says it is currently only VAT. Income tax brackets still come from `TaxConfiguration`.

Decision: **partial**

---

## T09 — Fiscal year

Old tracker: FIXED  
Current status: **PARTIAL**

Evidence:

Fiscal year start month/day are used.

Remaining gaps:

- uses `java.util.Calendar`.
- no validation for invalid fiscal date combinations.
- timezone policy is system default.

Decision: **partial**

---

## T10 — Business/tax lifecycle

Old tracker: FIXED  
Current status: **NOT FULLY RE-AUDITED / likely partial**

Not enough current-source inspection of `TransactionLifecycleCoordinator.updateBusinessTaxFields()` in this pass.

Decision: **needs targeted audit**

---

# 4. New/current issues found

## E4-NOW-001 — Group lifecycle events are not consistently atomic

Severity: **P1_HIGH**

Evidence:

- `createGroup()` calls `groupCoordinator.createGroupWithMembers(...)`, then writes lifecycle event in a separate transaction.
- `addMember()` does same.
- `removeMember()` deletes in a transaction, then emits event after.
- `archiveGroup()` archives, then emits event after.
- `deleteGroupPermanently()` hard-deletes, then emits event after.

Impact:

A mutation can commit without audit event.

Recommended fix:

Move mutation + lifecycle event into one transaction or provide a lower-level transaction callback that writes event inside the same transaction.

Tests:

- event insert failure does not leave silent mutation, or mutation records diagnostic
- create/add/remove/archive event atomicity tests

---

## E4-NOW-002 — Group create can still preserve `joinedAt = 0`

Severity: **P1_HIGH**

Evidence:

`addMemberToGroup()` sets `joinedAt = timeProvider.now()`, but `createGroupWithMembers()` maps caller-provided `members` and does not normalize `joinedAt`.

Impact:

Initial group members can have sentinel join timestamps.

Recommended fix:

During group creation, copy members with:

```text
joinedAt = if existing > 0 then existing else now
```

Tests:

- createGroup sets nonzero joinedAt for every initial member
- preserves nonzero historical joinedAt if intentionally supplied

---

## E4-NOW-003 — `createSystemExpenseAndLinkToGroup()` appears to bypass single-currency group policy

Severity: **P1_HIGH**

Evidence:

In inspected `createSystemExpenseAndLinkToGroup()`, the passed `currency` is used to create system/group expense. No visible check against `group.defaultCurrency`.

Impact:

A low-level/direct caller can create mixed-currency group expense even though group policy is single-currency.

Recommended fix:

Inside transaction, before creating expense:

```text
if currency != group.defaultCurrency -> error
```

Tests:

- low-level createSystemExpenseAndLinkToGroup rejects currency mismatch
- lifecycle addExpense valid same-currency still works

---

## E4-NOW-004 — GroupBalanceCalculator still rewrites history

Severity: **P1_HIGH**

Evidence:

`GroupBalanceCalculator.calculateMemberBalance()` uses current member count for all historical equal splits.

Impact:

Adding/removing a member changes old owed shares. Member removal validation can be wrong.

Recommended fix:

Use `SplitCalculator` and participant rules per expense date; ideally persist participant snapshot or use joinedAt.

Tests:

- adding new member later does not change old split
- removing member balance gate uses historical participation

---

## E4-NOW-005 — GroupBalanceCalculator includes all settlements regardless of status/currency

Severity: **P1_HIGH**

Evidence:

It sums all settlements by `fromMemberId`/`toMemberId`. No status or currency filter.

Impact:

Cancelled/pending/foreign-currency settlements alter balances.

Recommended fix:

Filter `status in RECORDED/COMPLETED` and currency == group.defaultCurrency.

Tests:

- cancelled settlement ignored
- foreign-currency settlement ignored/rejected

---

## E4-NOW-006 — Settlement validation lacks positive/finite/self/idempotency checks

Severity: **P1_HIGH**

Evidence:

`recordSettlement()` validates group/currency/member ownership, but no visible checks for:

- amount finite
- amount > 0
- `fromMemberId != toMemberId`
- duplicate operation

Impact:

Zero/negative/self settlements can be inserted; double taps duplicate rows.

Recommended fix:

No-schema first:

- reject non-finite/<=0
- reject self settlement

Schema later:

- add client operation id / idempotency key

Tests:

- reject zero/negative/NaN/infinity
- reject self settlement
- double tap idempotency deferred or guarded

---

## E4-NOW-007 — Shared budget offsets still exclude archived groups

Severity: **P2_MEDIUM / P1 depending usage**

Evidence:

`SharedExpenseBudgetOffsetEngine` still calls `getActiveGroupsWithDetails()`. Source comment explicitly says archived groups are excluded and planned to fix.

Impact:

Archiving a group can remove historical shared obligations from budget offsets.

Recommended fix:

Use time-bounded all-groups query, include archived groups when expenses fall in period.

Tests:

- archived group expense still contributes to historical budget offset
- UI marks archived contribution if needed

---

## E4-NOW-008 — InvestmentViewModel discards aggregate/dataQuality and uses raw summary

Severity: **P1_HIGH**

Evidence:

`InvestmentViewModel` does:

```text
val (summary, _, _) = getPortfolioSummaryAggregate(holdings)
_portfolioSummary.value = summary
```

Impact:

UI still gets raw mixed-currency totals.

Recommended fix:

Create `PortfolioSummaryState` with:

- `summaryAggregate`
- `costBasisAggregate`
- `gainLossAggregate`
- `dataQuality`
- display currency

Tests:

- ViewModel exposes aggregate, not only raw summary
- multi-currency holdings show partial/aggregate warning

---

## E4-NOW-009 — `getPortfolioSummaryAggregate()` returns raw `PortfolioSummary`

Severity: **P1_HIGH**

Evidence:

The method computes raw totals before building aggregate and returns both.

Impact:

Even “safe” API includes unsafe values.

Recommended fix:

Create new model:

```text
PortfolioSummaryAggregate
```

with aggregate-backed totals; deprecate raw summary return.

Tests:

- aggregate API does not expose raw cross-currency total as primary UI value
- raw summary marked legacy/deprecated

---

## E4-NOW-010 — `getInvestmentPerformance()` lacks aggregate/dataQuality

Severity: **P1_HIGH**

Evidence:

ViewModel calls `getInvestmentPerformance(id)`, which returns raw performance only.

`getInvestmentPerformances()` has aggregate fields, but attaches portfolio-level aggregates to each row.

Impact:

Per-holding rows lack safe money model and stale-price warnings.

Recommended fix:

- add per-holding aggregate in `getInvestmentPerformance()`
- or update ViewModel to use corrected `getInvestmentPerformances()` where each row gets only its own aggregate

Tests:

- row aggregate contains only that holding
- stale row exposes dataQuality

---

## E4-NOW-011 — Investment validation remains weak

Severity: **P1_HIGH**

Evidence:

`addHolding()` only validates quantity > 0, purchasePrice > 0, currency nonblank. `updatePrice()` does not validate `newPrice`.

Impact:

NaN/infinity/invalid currency/blank symbol/bad date can enter calculations.

Recommended fix:

No-schema first:

- validate finite positive quantity/price/currentPrice
- validate nonnegative finite fees
- validate currency ASCII 3-letter or `CurrencyCode`
- validate symbol/name nonblank
- validate purchaseDate > 0
- validate `newPrice.isFinite() && newPrice > 0`

Tests:

- reject NaN/infinity/invalid currency/blank symbol/date zero
- valid holding still inserts transaction and value history

---

## E4-NOW-012 — Portfolio allocation numerator/denominator mismatch remains

Severity: **P1_HIGH**

Evidence:

Denominator uses `getPortfolioSummaryAggregate()`. Numerator uses latest value row or `0.0`.

Impact:

Holding can contribute to denominator but not numerator, so allocation can fail to sum to 100%.

Recommended fix:

Use same source for both numerator and denominator. If no latest value row, use `currentPrice * quantity` with missing-history warning.

Tests:

- allocations sum to ~1 for valid conversions
- missing latest value does not disappear silently

---

## E4-NOW-013 — Portfolio history still raw-sums currencies

Severity: **P1_HIGH**

Evidence:

`getPortfolioValueHistory()` builds `dayMap<String, Double>` and adds `latestTotalValue` across holdings.

Impact:

USD + EUR + GBP are treated as one number in chart.

Recommended fix:

Add `DailyPortfolioValueAggregate` with `MoneyAggregate` and data quality.

Tests:

- multi-currency history produces aggregate and warnings
- no raw mixed total in chart model

---

## E4-NOW-014 — Tax estimation uses raw income and inconsistent currency

Severity: **P1_HIGH**

Evidence:

`estimateTaxes(... estimatedAnnualIncome: Double)` has no currency.

`buildDeductibleAggregate()` uses home currency if available, else filing currency.

`TaxEstimate` notes say filing currency.

Impact:

Taxable income/deductions can mix home and filing currency.

Recommended fix:

- use filing currency for tax calculations
- require estimated income currency or `MoneyAmount`
- expose home-vs-filing conversion warning

Tests:

- tax estimate uses filing currency
- raw income rejected or requires explicit currency

---

## E4-NOW-015 — Tax conversion basis is not historical

Severity: **P1_HIGH**

Evidence:

Deductible/income aggregates group totals by currency then use `MoneyAggregateBuilder.fromBuckets(...)`. This loses per-transaction dates.

Impact:

Historical tax estimates can change when current/latest FX rates change.

Recommended fix:

Use per-transaction `convertAsOf(expense.date)` or group by currency + rate-date.

Tests:

- historical tax summary stable after latest FX changes
- deductions use transaction-date rates

---

## E4-NOW-016 — Business report is still raw/euro/formula-unsafe

Severity: **P1_HIGH / P1_SECURITY**

Evidence:

`BusinessExpenseReportGenerator`:

- raw-sums `effectiveAmount`
- hardcodes euro sign
- uses direct `Dispatchers.IO`
- `escapeCSV()` only quotes comma/quote/newline
- exports raw merchant/purpose/project/notes/locations

Impact:

- mixed-currency totals wrong
- non-EUR reports misleading
- spreadsheet formula injection possible
- privacy leakage in exports

Recommended fix:

- inject `@IoDispatcher`
- inject `TaxSettingsRepository`, `CurrencyConverter`
- use MoneyAggregate-backed totals
- use `CsvCellSanitizer`
- add redacted export mode

Tests:

- formula merchant `=cmd` neutralized
- non-EUR report does not hardcode euro
- mixed-currency category totals use aggregate/warnings

---

# 5. Current high-priority issue list

## Groups

| ID | Severity | Title |
|---|---:|---|
| E4-NOW-001 | P1 | lifecycle events not consistently atomic |
| E4-NOW-002 | P1 | createGroup can preserve joinedAt=0 |
| E4-NOW-003 | P1 | createSystemExpenseAndLinkToGroup may bypass group currency policy |
| E4-NOW-004 | P1 | balance uses current member count for historical splits |
| E4-NOW-005 | P1 | balance includes all settlement statuses/currencies |
| E4-NOW-006 | P1 | settlement lacks positive/finite/self/idempotency checks |
| E4-NOW-007 | P2/P1 | budget offsets exclude archived groups |

## Investment

| ID | Severity | Title |
|---|---:|---|
| E4-NOW-008 | P1 | ViewModel discards aggregate/dataQuality |
| E4-NOW-009 | P1 | aggregate API still returns raw PortfolioSummary |
| E4-NOW-010 | P1 | row performances lack aggregate/dataQuality |
| E4-NOW-011 | P1 | weak investment validation |
| E4-NOW-012 | P1 | allocation numerator/denominator mismatch |
| E4-NOW-013 | P1 | portfolio history raw-sums currencies |

## Tax / Business

| ID | Severity | Title |
|---|---:|---|
| E4-NOW-014 | P1 | raw income and filing/home currency mismatch |
| E4-NOW-015 | P1 | tax FX basis not historical |
| E4-NOW-016 | P1 | business report raw/euro/formula/privacy unsafe |

---

# 6. Recommended fix order

## PR1 — Group no-schema invariant hardening

Closes:

- E4-NOW-002
- E4-NOW-003
- E4-NOW-006 partially

Files:

- `GroupLifecycleCoordinator.kt`
- `GroupTransactionCoordinator.kt`
- group tests

Implementation:

1. Normalize initial member `joinedAt` during group creation.
2. Enforce group currency in `createSystemExpenseAndLinkToGroup()`.
3. Validate settlement amount finite/positive.
4. Reject self-settlement.
5. Keep no schema changes.

Tests:

- createGroup sets nonzero joinedAt
- low-level system expense link rejects currency mismatch
- settlement rejects zero/negative/NaN/self

Risk: medium, no schema.

---

## PR2 — Group lifecycle event atomicity

Closes:

- E4-NOW-001

Files:

- `GroupLifecycleCoordinator.kt`
- `GroupTransactionCoordinator.kt`
- `GroupLifecycleEventDao`

Implementation:

1. Create lower-level methods that accept event insertion inside the mutation transaction, or
2. Move lifecycle-controlled mutations fully into `GroupLifecycleCoordinator` transactions.

Tests:

- mutation + event atomic for create/add/remove/archive/settlement
- event failure behavior defined

Risk: medium/high.

---

## PR3 — Group balance correctness

Closes:

- E4-NOW-004
- E4-NOW-005

Files:

- `GroupBalanceCalculator.kt`
- `SplitCalculator.kt`
- settlement DAO if needed

Implementation:

1. For each expense, compute current user/member share with `SplitCalculator`.
2. Respect historical participation via `joinedAt` or stored participant snapshot.
3. Filter settlements by active status and group currency.

Tests:

- adding member later does not change old split
- cancelled settlement ignored
- foreign settlement ignored/rejected

Risk: high for group behavior.

---

## PR4 — Investment validation and UI aggregate safety

Closes:

- E4-NOW-008
- E4-NOW-010
- E4-NOW-011

Files:

- `InvestmentTracker.kt`
- `InvestmentViewModel.kt`
- investment tests

Implementation:

1. Validate finite positive investment fields.
2. Validate currency code and symbol/name/date.
3. Validate `updatePrice()`.
4. Make ViewModel expose aggregate/dataQuality.
5. Use per-holding aggregate rows.

Tests:

- invalid holdings rejected
- ViewModel exposes aggregate and stale warnings
- per-row aggregate only contains that holding

Risk: medium, no schema.

---

## PR5 — Investment summary/allocation/history model cleanup

Closes:

- E4-NOW-009
- E4-NOW-012
- E4-NOW-013

Implementation:

1. Introduce `PortfolioSummaryAggregate`.
2. Make allocation numerator/denominator same-source.
3. Add aggregate-backed portfolio history.

Risk: medium/high. Avoid schema unless necessary.

---

## PR6 — Business report safety quick win

Closes:

- E4-NOW-016 partially

Files:

- `BusinessExpenseReportGenerator.kt`
- business tests

Implementation:

1. Inject `@IoDispatcher`.
2. Replace direct `Dispatchers.IO`.
3. Use CSV formula sanitizer.
4. Remove hardcoded euro from formatted report or use explicit report currency.
5. Add redacted mode if existing privacy infrastructure supports it.

Tests:

- formula injection neutralized
- non-EUR report not euro-hardcoded
- direct dispatcher gone

Risk: medium, no schema.

---

## PR7 — Tax currency and FX-basis correctness

Closes:

- E4-NOW-014
- E4-NOW-015

Files:

- `TaxEstimator.kt`
- `TaxSettingsRepository.kt`
- tax tests

Implementation:

1. Use filing currency as tax calculation currency.
2. Make estimated income require currency.
3. Convert deductions/income per transaction date or currency+rate-date.
4. Surface rate source/basis metadata.

Risk: high. Do after PR6.

---

# 7. Pipeline regression matrix

## Groups

Must verify:

- create group
- add member
- remove member
- add standalone group expense
- create system expense + group link
- linked expense ownership update
- archive group
- hard delete path only if admin/debug
- settlement record
- budget offset after group expense
- analytics shared-expense visibility

## Investment

Must verify:

- add holding
- update price
- load portfolio summary
- load performances
- stale price warning
- allocation chart
- portfolio history chart
- target/stop-loss filtering

## Tax / Business

Must verify:

- tax estimate
- tax year summary
- business expense report
- business CSV export
- mileage report
- non-EUR filing currency
- mixed-currency business expenses
- formula-safe CSV cells

## Backup/restore

Must verify:

- group writes blocked during restore
- investment writes blocked during restore
- tax/business report generation does not mutate DB during restore
- no direct DAO writes bypass barrier in user flows

---

# 8. Static checks performed

Checked statically:

- group lifecycle coordinator write paths
- group transaction coordinator write paths
- linked expense side-effect timing
- hard-delete cleanup
- group balance logic
- shared budget offset conversion and active-group query
- investment add/update/summary/performance/history/allocation
- investment ViewModel loading path
- tax estimator aggregate/currency settings
- tax settings validation/persistence
- business report formatting/CSV/export
- test directory presence

Not fully checked:

- every group UI caller
- every GroupsRepository method
- every tax UI/export caller
- business/tax lifecycle update API
- Hilt graph
- compile/test status
- Room schema

---

# 9. Known compile risks for future fixes

Potential compile risks:

- changing group lifecycle event atomicity may require coordinator/interface changes
- changing `PortfolioSummary` model affects UI/Compose
- adding aggregate fields to investment UI state affects screens
- stricter investment validation may break tests/fixtures
- replacing `BusinessExpenseReportGenerator` dispatcher requires Hilt binding
- tax income currency API change affects all tax callers
- historical tax conversion may require new DAO query returning per-row expenses
- schema fixes require migration tests

---

# 10. Human validation commands

Do not run during individual static slices if following the orchestrator workflow.

After all Engine 4 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration changes are introduced:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changes are introduced:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# 11. Final conclusion

Engine 4 is **much improved**, but still not clean.

The best current state summary:

```text
Groups: improved write safety and side-effect timing, but balance/lifecycle/event correctness still partial.
Investment: write safety improved, but UI and summary models still raw/mixed-currency.
Tax: aggregate fields exist, but currency basis and filing/home semantics are inconsistent.
Business reports: still unsafe — raw sums, hardcoded euro, weak CSV escaping, privacy concerns.
```

Best first PR:

> **PR1 — Group no-schema invariant hardening**

Why:

- no schema
- clear correctness holes
- protects shared expenses and budget offsets
- low-to-medium blast radius

Best second PR:

> **PR4 — Investment validation and UI aggregate safety**

Why:

- no schema
- prevents corrupt values
- moves UI away from raw summary

Business report safety should be soon after because it has security/export risk.

Verdict: **YELLOW / RED-LEANING — improved but not production-hardened.**

---

# Sources used

Architecture:

- `ENGINE_INTERACTION_MAP.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/ENGINE_INTERACTION_MAP.md
- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/CODEBASE_SEGMENTS.md

Groups:

- `GroupLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinator.kt
- `GroupTransactionCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
- `GroupBalanceCalculator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt
- `SharedExpenseBudgetOffsetEngine.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

Investment:

- `InvestmentTracker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt
- `InvestmentViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/ui/screens/investment/InvestmentViewModel.kt

Tax / business:

- `TaxEstimator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt
- `BusinessExpenseReportGenerator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt
- `TaxSettingsRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/TaxSettingsRepository.kt
- `TaxRateProvider.kt`  
  https://github.com/panospao7/Cost-agregator/blob/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/tax/TaxRateProvider.kt

Tests/directories:

- group tests  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/groups
- investment tests  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/investment
- tax tests  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/tax
- business tests  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/business