# Pipeline 6 Debugging Report — Budget / Forecasting / Cash Flow

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 6 is intended to be:

```text
Expense / Budget / Recurring / Planned / Savings data
→ BudgetRepository / BudgetMonitor
→ ForecastInputAssembler
→ SynthesisEngine
→ MonteCarloSpendingSimulator
→ CashFlowCalculator / FinancialStressForecastEngine
→ dashboard weather / budget screen / cash-flow calendar / alerts
```

The codebase has strong improvements:

- `BudgetCalculator` centralizes budget period windows.
- `BudgetRepository` uses `MultiCurrencyRepository` for budget spend.
- `BudgetDao` has transactional active-budget helpers.
- `ForecastInputAssembler` normalizes actual expenses before forecast.
- `SynthesisEngine` documents double-count risks.
- Monte Carlo forecast is deterministic with fixed seed.
- Stress forecast normalizes actual expenses/deposits.

But this pipeline still has several high-risk correctness gaps.

Highest-risk findings:

1. **Forecast and cash-flow paths can still accidentally schedule recurring reminder rows** through `generateOccurrences()`.
2. **Forecast math still raw-sums planned/recurring expenses across currencies.**
3. **CashFlowCalculator raw-sums multi-currency expenses and recurring amounts.**
4. **Budget limit conversion failure can still produce fake percent/health values.**
5. **Budget alerts use raw budget amount in notification text, not effective rollover limit.**
6. **Budget alerts do not use the shared-expense adjusted spend that the Budget UI computes.**
7. **BudgetAutopilotEngine uses raw monthly aggregate DAO totals, likely not currency-normalized.**
8. **Forecast confidence ignores currency conversion failures / excluded transactions.**
9. **FinancialWeatherRepository may bypass manual recurring occurrence generation by passing `manualRecurringEntities = emptyList()` to the assembler.**
10. **`SynthesisEngine.calculateBlockPartyData()` uses `runBlocking` to query Room from a non-suspend method.**

Main recommendation:

> Pipeline 6 needs one canonical normalized forecast input contract: all actual, planned, recurring, budget, savings, and group-offset amounts must be in the same home currency, with `isPartial` / confidence metadata carried all the way to UI.

---

# 2. Intended architecture contract

Relevant dependency map areas:

```text
Dashboard/Analytics/Currency:
HomeViewModel
→ DashboardRepository
→ ComputeDashboardWidgetsUseCase
→ MultiCurrencyRepository
→ AnalyticsRepository
→ AnalyticsCurrencyNormalizer
```

```text
Recurring Lifecycle:
ForecastInputAssembler
CashFlowCalculator
FinancialWeatherRepository
→ RecurringLifecycleCoordinator
→ RecurringOccurrenceDao
```

```text
Transaction Lifecycle:
TransactionSideEffectDispatcher
→ BudgetMonitor.checkBudget()
```

So Pipeline 6 sits on top of Pipelines 2, 4, and 5.

This means any instability in:

- transaction lifecycle events,
- recurring occurrence materialization,
- currency conversion,
- planned expense invariants,

directly affects budget and forecast correctness.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path summary

## 3.1 BudgetRepository

`BudgetRepository.getBudgetStatuses()`:

```text
dayBoundaryTicks()
→ active budgets
→ categories
→ expenseDao.getTotalSpentFlow() as invalidation trigger
→ deriveBudgetStatuses()
```

`createBudgetStatus()`:

```text
period window from BudgetCalculator
→ convert budget limit to home currency
→ get aggregate purchase spend from MultiCurrencyRepository
→ apply rollover if enabled
→ compute percent / remaining / health
→ return BudgetStatus
```

Strengths:

- purchase-only spend,
- `isNotMine` excluded by DAO path,
- multi-currency spend aggregate,
- active-budget enforcement via DAO helpers,
- period boundary refresh.

Risks:

- converted budget limit fallback can mix units,
- category empty aggregate defaults to EUR,
- rollover performs N+1 period queries,
- rollover uses only `.displayAmount`, losing partial conversion state from historical periods,
- repository does not set `createdAt` despite entity comment requiring it.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetDao.kt

---

## 3.2 BudgetMonitor

`BudgetMonitor.checkBudgets()`:

```text
throttle 1 minute
→ get cached budget statuses
→ process each status
→ send warning/critical/exceeded notification
→ update notification timestamp only if delivered
```

Good fix:

- notification timestamps update only when delivery succeeds.

Risks:

- notification content recomputes percent using `spent / budget.amount`, not `status.effectiveLimit`;
- budget monitor uses raw `status.spentAmount`, not the shared-expense adjusted spend shown by `BudgetViewModel`;
- partial conversion warnings do not affect alert severity;
- no lifecycle/debug event for alert attempts/failures.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt

---

## 3.3 ForecastInputAssembler

`ForecastInputAssembler.assemble()`:

```text
resolve home currency
→ normalize expense snapshots
→ generate occurrences for manual recurring rows
→ query materialized occurrences
→ dedupe planned expenses by sourceOccurrenceKey
→ build past daily cumulative spending
→ merge recurring patterns
→ build spending pace
→ return ForecastInput
```

Strengths:

- normalizes actual expenses via `AnalyticsCurrencyNormalizer`,
- filters `FULFILLED` planned expenses,
- dedupes planned expenses against materialized recurring occurrences,
- purchase-only pace logic.

Risks:

- calls `generateOccurrences()` with default reminder windows from Pipeline 4;
- conversion failures are dropped from forecast input instead of lowering confidence visibly;
- planned expenses are not normalized to home currency;
- manual recurring generation may be bypassed by `FinancialWeatherRepository`, which passes `manualRecurringEntities = emptyList()`;
- date ranges sometimes use inclusive `..now` instead of consistently half-open ranges.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt

---

## 3.4 SynthesisEngine

`SynthesisEngine.synthesize()` computes:

```text
committed recurring
committed planned
likely recurring
likely planned
monthly recurring
goal reserves
projected points
discretionary budget
risk level
confidence
insights
```

Strengths:

- catches exceptions and returns degraded fallback,
- computes recurring multi-occurrence weekly/biweekly estimates,
- uses budget status snapshots,
- documents block-party logic.

Risks:

- recurring/planned/savings amounts are raw `Double`,
- planned expenses are grouped by currency but then raw-summed anyway,
- recurring patterns are not converted to display currency,
- confidence does not include currency/data-quality failures,
- `calculateBlockPartyData()` calls Room via `runBlocking`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

---

## 3.5 CashFlowCalculator

`CashFlowCalculator.calculateDailyCashFlow()`:

```text
generate recurring occurrences
→ query planned occurrences
→ get historical expenses
→ classify day transactions as income/outflow
→ add predicted recurring
→ update running balance
```

Strengths:

- explicit transaction-type classification,
- deposits/incoming transfers are inflow,
- purchases/withdrawals/outgoing transfers are outflow,
- unknown/unclassified transfers excluded.

Risks:

- raw-sums `effectiveAmount` with a comment assuming same-currency days;
- recurring predicted amounts are raw `averageAmount`;
- no `MoneyAggregate`, `isPartial`, or conversion failure metadata;
- calls `generateOccurrences()` and can create reminder rows as a side effect;
- `CashFlowCalendarViewModel` exposes `homeCurrency` but does not normalize cash-flow rows.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModel.kt

---

## 3.6 Monte Carlo and stress forecast

`HistoricalSpendingDistribution` normalizes expenses before fitting weekly totals.

`MonteCarloSpendingSimulator`:

```text
fit historical weekly log-normal distribution
→ fixed seed simulation
→ P10/P25/P50/P75/P90
→ probability under budget
```

`FinancialStressForecastEngine`:

```text
resolve display currency
→ normalize deposits and expenses
→ recurring outflows
→ income estimate
→ discretionary Monte Carlo
→ 30/60/90 day stress horizons
```

Strengths:

- deterministic seed,
- quality assessor exists,
- stress forecast normalizes actual expenses and deposits,
- stress forecast admits there is no true account-balance source and uses neutral baseline.

Risks:

- Monte Carlo confidence ignores currency conversion failures;
- stress recurring outflow calls `generateOccurrences()` and can schedule reminders;
- detected recurring fallback in stress forecast raw-sums pattern amounts without conversion;
- neutral starting balance makes “cash crunch” probability a stress index, not real balance risk.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt

---

# 4. Major findings

## Finding P0-1 — Forecast/cash-flow paths still have side effects

This repeats Pipeline 4 because it directly affects Pipeline 6.

These read/projection paths call `RecurringLifecycleCoordinator.generateOccurrences()`:

- `ForecastInputAssembler.assemble()`
- `CashFlowCalculator.calculateDailyCashFlow()`
- `CashFlowCalculator.getUpcomingBills()`
- `FinancialStressForecastEngine.calculateRecurringOutflows()`

If `generateOccurrences()` schedules reminders by default, then opening:

- dashboard financial weather,
- forecast screen,
- cash-flow calendar,
- stress forecast,

can create `recurring_reminder_deliveries`.

That is a severe architectural bug.

### Recommendation

Split recurring APIs:

```kotlin
previewOccurrences(...)
materializeOccurrences(..., scheduleReminders = false)
scheduleReminderDeliveries(...)
```

For all Pipeline 6 read/projection calls:

```kotlin
scheduleReminders = false
```

Only explicit reminder flows should schedule reminder deliveries.

Priority: highest.

---

## Finding P0-2 — Forecast still raw-sums future planned/recurring currencies

Actual expenses are normalized before forecast, which is good.

But future inputs are not consistently normalized:

```text
RecurringPattern.averageAmount
PlannedExpense.amount
SavingsGoal.targetAmount/currentAmount
BudgetStatusSnapshot.budgetAmount
```

`SynthesisEngine` groups planned expenses by currency and logs if there are multiple currencies, but then it sums the values anyway.

Examples:

```text
committedPlanned = values.sum()
likelyPlanned = values.sum() * weight
totalMonthlyPlanned = grouped values sum
plannedOnDay = grouped values sum
```

This avoids silent ignorance but still produces wrong numbers.

### Recommendation

Create:

```kotlin
NormalizedForecastInput
```

where every amount is either:

```text
MoneyAmount in home currency
```

or:

```text
MoneyAggregate with source buckets + failures
```

Normalize:

- planned expenses by date,
- recurring expected amounts by due date,
- savings goal amounts,
- budget snapshots,
- group offsets.

If conversion fails, exclude from numeric total and reduce confidence / show warning.

Priority: highest.

---

## Finding P0-3 — CashFlowCalculator raw-sums mixed currencies

`CashFlowCalculator` says single-day expenses are “almost always same-currency” and sums `effectiveAmount`.

That assumption is not safe for this app.

A user can have:

```text
EUR grocery
USD coffee
GBP subscription
```

on the same day. Cash-flow ending balance becomes meaningless.

Also recurring patterns are added as raw `averageAmount`.

### Recommendation

Inject a normalizer/converter into `CashFlowCalculator`.

Return:

```kotlin
data class DailyCashFlow(
    val income: MoneyAggregate,
    val outflow: MoneyAggregate,
    val predictedRecurring: MoneyAggregate,
    val endingBalance: MoneyAggregate,
    val isPartial: Boolean,
    val warning: String?
)
```

Short-term:

- resolve home currency,
- convert each expense as of expense date,
- convert recurring by due date,
- mark day partial when any conversion fails.

Priority: highest.

---

## Finding P0-4 — Budget conversion failure can compute fake percent/health

`BudgetRepository.convertBudgetAmountToHomeCurrency()` returns a partial aggregate in the original currency when budget-limit conversion fails.

Then `createBudgetStatus()` still does:

```text
spent = home-currency spend
baseLimit = original-currency limit
percent = spent / baseLimit
health = based on percent
```

Example:

```text
home EUR
budget 100 GBP
missing GBP→EUR
spent 90 EUR
```

The app can compute `90 / 100 = 90%` even though the denominator is GBP.

### Recommendation

If budget limit conversion fails:

```text
BudgetStatus.isPartial = true
percentUsed = null or 0 with health UNKNOWN
remainingAmount = null or 0 with warning
healthStatus = UNKNOWN / UNAVAILABLE
```

If model cannot represent nullable percent yet, add:

```kotlin
val calculationReliable: Boolean
val healthStatus = UNKNOWN
```

Priority: highest.

---

## Finding P0-5 — BudgetAutopilot likely uses raw mixed-currency DAO totals

`BudgetAutopilotEngine` uses:

```text
ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()
ExpenseDao.getMonthlySpendingTotalsBetween()
```

The comments say this avoids raw-row truncation, which is good.

But this path does not appear to use `MultiCurrencyRepository` or `AnalyticsCurrencyNormalizer`.

So recommendations can be based on raw monthly totals across currencies.

### Recommendation

Use currency-grouped monthly totals:

```text
month + category + currency → amount
```

Then convert per month using historical `convertAsOf()`.

Or route autopilot through `MultiCurrencyRepository` monthly/category aggregates that preserve `MoneyAggregate`.

Budget recommendations should be in the budget’s own currency or home currency explicitly.

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt

---

## Finding P1-1 — Budget notification text is wrong with rollover

`BudgetRepository` correctly returns:

```text
budget.amount = base converted limit
effectiveLimit = rollover-adjusted limit
percentUsed = spent / effectiveLimit
```

But `BudgetMonitor.sendNotification()` recomputes display percent as:

```text
spent / budget.amount
```

So if rollover increases effective limit, alert text is wrong.

Example:

```text
base budget = 100
rollover effective limit = 200
spent = 150
actual percent = 75%
notification percent = 150%
```

Threshold decisions use `status.percentUsed`, but notification text uses `budget.amount`.

### Recommendation

Change `sendNotification()` to accept:

```kotlin
percentUsed = status.percentUsed
effectiveLimit = status.effectiveLimit
currency = status.currency
conversionWarning = status.conversionWarning
```

Do not recompute from `budget.amount`.

Priority: high.

---

## Finding P1-2 — Budget UI and BudgetMonitor disagree on shared-expense offset

`BudgetViewModel` computes:

```text
AdjustedSpendBreakdown
```

using `SharedExpenseBudgetOffsetEngine`.

But `BudgetMonitor` sends alerts based on:

```text
status.spentAmount
```

not adjusted effective spend.

So the UI can say:

```text
budget OK after shared/reimbursement logic
```

while notifications say:

```text
budget exceeded
```

or the reverse.

### Recommendation

Decide one contract:

1. Budget alerts use raw gross spend.
2. Budget alerts use adjusted spend.
3. UI shows both gross and adjusted, with alert mode configurable.

Then implement the same contract in:

- `BudgetRepository`
- `BudgetViewModel`
- `BudgetMonitor`
- dashboard widgets.

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

---

## Finding P1-3 — Forecast confidence ignores partial currency normalization

`ForecastInputAssembler` calls:

```text
analyticsCurrencyNormalizer.normalizeSnapshots()
```

and uses:

```text
normalized.includedExpenses
```

But the excluded/failure set is not passed into:

- `ForecastInput`,
- `SynthesisEngine`,
- `MonteCarloSpendingSimulator`,
- `FinancialWeather`,
- UI narrative.

So missing exchange rates reduce the data silently.

### Recommendation

Extend forecast input:

```kotlin
data class ForecastDataQuality(
    val actualExpenseCount: Int,
    val includedExpenseCount: Int,
    val excludedExpenseCount: Int,
    val conversionFailures: List<ConversionFailure>,
    val isPartial: Boolean
)
```

Then reduce confidence:

```text
confidence -= partialPenalty
```

and show narrative:

```text
“Forecast excludes 3 GBP transactions due to missing rate.”
```

Priority: high.

---

## Finding P1-4 — FinancialWeatherRepository may bypass manual recurring occurrence generation

`FinancialWeatherRepository` does:

```text
confirmedRecurringPatterns = mergedRecurringPatternsProvider.getConfirmedPatterns(manualRecurring = recurringEntities)

forecastInputAssembler.assemble(
    manualRecurringEntities = emptyList(),
    detectedRecurringPatterns = confirmedRecurringPatterns,
    ...
)
```

Because `manualRecurringEntities` is empty, the assembler loop that calls:

```text
recurringLifecycleCoordinator.generateOccurrences(rule.id, ...)
```

does not run for those manual rules.

That means:

- recurring patterns may flow through as detected-like patterns,
- materialized occurrence dedupe may not happen freshly,
- planned-expense cross-dedup can depend on stale occurrence rows.

This may be intentional if `getConfirmedPatterns()` already includes rule IDs, but the call shape contradicts the assembler’s documented contract.

### Recommendation

Pass actual manual recurring entities to the assembler:

```kotlin
manualRecurringEntities = recurringEntities
detectedRecurringPatterns = detectedOnlyPatterns
```

Or split API:

```kotlin
assembleFromEntities(...)
assembleFromAlreadyMergedPatterns(...)
```

Do not mix the two modes.

Priority: high.

---

## Finding P1-5 — `SynthesisEngine.calculateBlockPartyData()` uses `runBlocking`

`SynthesisEngine` is mostly pure/domain logic, but `calculateBlockPartyData()` calls Room DAO through:

```text
runBlocking { occurrenceDao.getByDateRange(...) }
```

This can block UI or ViewModel caller threads and makes tests harder.

### Recommendation

Make `calculateBlockPartyData()` suspend, or prefetch occurrence rows in the caller/assembler:

```kotlin
suspend fun calculateBlockPartyData(...)
```

or:

```kotlin
BlockPartyInput(recurringOccurrences = ...)
```

Priority: high.

---

## Finding P1-6 — Stress forecast is not true cash-balance forecast

`FinancialStressForecastEngine` uses:

```text
resolveStartingBalanceBaseline() = 0.0
```

and the file comments correctly say there is no account-balance source.

This is honest, but the UI must not present it as:

```text
you will run out of money
```

unless the user has provided a starting balance or account balance.

### Recommendation

Expose mode:

```text
StressForecastMode.NEUTRAL_BASELINE
StressForecastMode.USER_BALANCE
StressForecastMode.ACCOUNT_BALANCE
```

If neutral baseline:

```text
headline = “Stress index”
not “cash balance forecast”
```

Priority: medium-high.

---

## Finding P1-7 — Budget `createdAt` and currency normalization are not enforced

`Budget` says:

```text
createdAt must be set at creation. 0L = unset sentinel.
```

But `BudgetRepository.addBudget()` does not set it.

Also, budget currency is accepted as-is. Lowercase/invalid currency can leak into conversion.

### Recommendation

In `addBudget()`:

```kotlin
val normalized = budget.copy(
    currency = budget.currency.uppercase(),
    createdAt = budget.createdAt.takeIf { it > 0 } ?: timeProvider.now()
)
```

Validate:

```text
amount > 0
startDate > 0
valid currency
warning threshold < critical threshold
periodMode in ROLLING/CALENDAR
```

Priority: medium-high.

---

## Finding P2-1 — Budget suggestions hardcode euro symbol

`BudgetRepository.getSuggestions()` builds reason text with:

```text
"Based on your €..."
```

That is wrong for non-EUR home currency.

### Recommendation

Use `CurrencyFormatter` and resolved home currency.

Priority: medium.

---

## Finding P2-2 — Rollover implementation is N+1 and loses partial-state history

`BudgetRepository` loops through every completed period and calls `getAggregateSpent()` per period.

The code comment already flags this.

Additional issue:

```text
getAggregateSpent(...).displayAmount
```

drops historical period partial warnings.

If one prior period had missing rates, rollover can be computed from partial data without warning being included.

### Recommendation

Batch period aggregates or persist rollover ledger.

Also track:

```text
rolloverPartial = any prior period partial
rolloverWarning = joined prior conversion warnings
```

Priority: medium-high.

---

# 5. Debugging checklist for Pipeline 6

## Budget CRUD / invariants

Check:

- [ ] active overall budget uniqueness,
- [ ] active category budget uniqueness,
- [ ] category delete is restricted when budget exists,
- [ ] inactive budget insert cannot violate keys,
- [ ] `createdAt` set,
- [ ] currency valid/uppercase,
- [ ] thresholds valid,
- [ ] periodMode valid.

## Budget status

Check:

- [ ] correct period window for rolling/calendar modes,
- [ ] purchase-only spend,
- [ ] `isNotMine` excluded,
- [ ] shared/gross/net contract explicit,
- [ ] foreign-currency budget converted,
- [ ] missing budget-rate does not produce fake percent,
- [ ] spend missing-rate sets partial warning,
- [ ] rollover effective limit correct,
- [ ] rollover partial warnings preserved,
- [ ] category empty aggregate uses home currency, not default EUR.

## Budget alerts

Check:

- [ ] warning/critical/exceeded thresholds,
- [ ] period cooldown,
- [ ] notification timestamp updates only on delivered,
- [ ] rollover percent text uses `effectiveLimit`,
- [ ] alert spend matches UI contract: gross or adjusted,
- [ ] partial conversion suppresses or degrades alert severity,
- [ ] notification content uses correct currency symbol.

## Forecast input

Check:

- [ ] actual expenses normalized to home currency,
- [ ] conversion failures propagated,
- [ ] planned expenses normalized,
- [ ] recurring expected amounts normalized,
- [ ] savings goals normalized,
- [ ] budget snapshots reliable/partial,
- [ ] fulfilled planned expenses excluded,
- [ ] planned and recurring occurrences deduped,
- [ ] calling forecast does not schedule reminders.

## Synthesis / weather

Check:

- [ ] committed = future high-confidence obligations only,
- [ ] likely = future probable obligations only,
- [ ] planned priorities weighted correctly,
- [ ] no raw mixed-currency sums,
- [ ] confidence reduced for missing data/rates,
- [ ] no `runBlocking` Room query,
- [ ] risk level matches budget/pace/partial data.

## Cash-flow calendar

Check:

- [ ] income/outflow classification,
- [ ] incoming/outgoing transfers,
- [ ] unknown transfers excluded,
- [ ] all amounts normalized,
- [ ] recurring predictions normalized,
- [ ] starting balance currency explicit,
- [ ] cash-flow calculation does not schedule reminders,
- [ ] warning shown when partial.

## Monte Carlo / stress

Check:

- [ ] historical weekly distribution normalized by transaction date,
- [ ] missing-rate exclusions lower confidence,
- [ ] deterministic seed stable in tests,
- [ ] sparse history returns degraded result,
- [ ] no-budget probability semantics correct,
- [ ] stress forecast not presented as real account balance unless balance source exists,
- [ ] detected recurring fallback converted to display currency.

---

# 6. Recommended fix plan

## PR 1 — Make forecast/cash-flow side-effect free

Change all Pipeline 6 calls to recurring generation so they do not schedule reminders.

Acceptance:

```text
Opening dashboard/weather/cash-flow/stress forecast creates zero new recurring_reminder_deliveries rows.
```

---

## PR 2 — Normalize all forecast input amounts

Create `NormalizedForecastInput`.

Normalize:

- actual expenses,
- planned expenses,
- recurring occurrences/patterns,
- savings goals,
- budget snapshots.

Acceptance:

```text
SynthesisEngine never raw-sums mixed currencies.
```

---

## PR 3 — Fix budget partial conversion semantics

If budget limit conversion fails:

```text
health = UNKNOWN / UNAVAILABLE
percent unreliable
warning visible
no fake budget alert
```

Acceptance:

```text
foreign-currency budget with missing rate cannot produce confident health.
```

---

## PR 4 — Fix BudgetMonitor notification content and spend contract

Pass full `BudgetStatus` into notification builder.

Use:

```text
status.percentUsed
status.effectiveLimit
status.currency
status.conversionWarning
```

Decide gross vs adjusted alert contract and apply consistently.

---

## PR 5 — Make CashFlowCalculator currency-safe

Inject converter/normalizer.

Return `MoneyAggregate` or equivalent partial metadata per day.

Acceptance:

```text
EUR + USD + missing GBP day shows converted EUR total with partial warning.
```

---

## PR 6 — Fix BudgetAutopilot currency behavior

Replace raw monthly totals with currency-aware monthly/category aggregates.

Acceptance:

```text
Autopilot recommendations do not change incorrectly when mixed-currency transactions exist.
```

---

## PR 7 — Remove `runBlocking` from SynthesisEngine

Make block-party recurrence input pre-fetched or suspend.

Acceptance:

```text
No Room DAO call through runBlocking in forecast/domain UI path.
```

---

# 7. Tests to add

## `BudgetForeignCurrencyMissingRateContractTest`

Seed:

```text
home EUR
budget 100 GBP
no GBP→EUR rate
spend 50 EUR
```

Assert:

```text
BudgetStatus.isPartial = true
health = UNKNOWN/UNAVAILABLE
percent not trusted
warning visible
BudgetMonitor does not send confident exceeded/warning alert
```

---

## `BudgetRolloverNotificationContractTest`

Seed:

```text
base budget 100 EUR
rollover surplus 100 EUR
current effective limit 200 EUR
spent 150 EUR
```

Assert:

```text
status.percentUsed = 75%
notification text says 75%, not 150%
```

---

## `BudgetSharedExpenseAlertContractTest`

Seed:

```text
shared expense
reimbursement state
category budget
```

Assert:

```text
Budget screen and BudgetMonitor use same gross/adjusted contract.
```

---

## `ForecastNoReminderSideEffectsTest`

Run:

```text
FinancialWeatherRepository.getFinancialWeather()
ForecastInputAssembler.assemble()
CashFlowCalculator.calculateDailyCashFlow()
FinancialStressForecastEngine.computeStressForecast()
```

Assert:

```text
no new recurring_reminder_deliveries
unless explicitly requested
```

---

## `ForecastNormalizedFutureInputsTest`

Seed:

```text
home EUR
planned expense 100 USD with historical rate
recurring 50 GBP missing rate
savings goal EUR
```

Assert:

```text
planned converted
recurring missing rate creates partial warning
confidence reduced
no raw USD/GBP summed into EUR forecast
```

---

## `CashFlowMultiCurrencyPartialDayTest`

Seed same-day:

```text
income 1000 EUR
expense 10 USD with rate
expense 20 GBP missing rate
recurring 15 EUR
```

Assert:

```text
ending balance converted for EUR+USD+EUR
GBP failure visible
day is partial
```

---

## `BudgetAutopilotMultiCurrencyContractTest`

Seed:

```text
3 months category spend:
EUR + USD + GBP
rates for USD only
```

Assert:

```text
recommendation carries partial warning/confidence
does not raw-sum GBP nominal amount
```

---

## `FinancialWeatherManualRecurringOccurrenceTest`

Seed:

```text
manual recurring rule
planned expense with sourceOccurrenceKey
```

Run weather forecast.

Assert:

```text
manual recurring occurrences generated or explicitly pre-existing
planned expense deduped
no duplicate recurring+planned total
```

---

# 8. Suggested canonical scenario

## `budget_forecast_cashflow_multicurrency_no_double_count`

Seed:

```text
home currency EUR

rates:
  USD→EUR = 0.90
  GBP→EUR missing

budget:
  groceries monthly 300 EUR
  rollover enabled

expenses:
  groceries 50 EUR
  groceries 10 USD
  groceries 20 GBP
  salary 1000 EUR deposit
  transfer 200 EUR outgoing

recurring:
  Netflix 12 EUR monthly

planned:
  car insurance 100 USD MUST
  linked planned Netflix occurrence
```

Run:

```text
BudgetRepository.getBudgetStatuses()
BudgetMonitor.checkBudgets()
ForecastInputAssembler.assemble()
SynthesisEngine.synthesize()
CashFlowCalculator.calculateDailyCashFlow()
FinancialStressForecastEngine.computeStressForecast()
```

Expected:

```text
budget spend = 59 EUR plus missing GBP warning
budget health is partial
forecast planned USD converted to 90 EUR
GBP missing-rate lowers confidence
Netflix recurring/planned not double-counted
cash-flow income = 1000 EUR
cash-flow outflow includes purchases/transfer according to policy
no reminder rows created by forecast/cash-flow
dashboard/weather shows partial warning
```

This is the Pipeline 6 fed-DB acceptance test.

---

# 9. Most likely real instability sources

Ranked:

1. **Side-effecting recurring generation in forecast/cash-flow.**
2. **Raw mixed-currency future forecast amounts.**
3. **Raw mixed-currency cash-flow calculation.**
4. **Budget conversion failure producing fake health.**
5. **Budget alert content ignoring rollover effective limit.**
6. **Budget UI adjusted spend and alerts using different spend contracts.**
7. **Autopilot raw monthly DAO totals.**
8. **Forecast confidence ignoring dropped conversion failures.**
9. **Manual recurring path mismatch in FinancialWeatherRepository.**
10. **`runBlocking` Room query in SynthesisEngine.**

---

# 10. Final recommendation

For Pipeline 6, stabilize in this order:

```text
1. Make forecast/cash-flow side-effect free.
2. Normalize all forecast/cash-flow future inputs, not only actual expenses.
3. Fix budget missing-rate semantics.
4. Fix BudgetMonitor rollover/adjusted-spend alert contract.
5. Make CashFlowCalculator MoneyAggregate-based.
6. Make BudgetAutopilot currency-aware.
7. Add the canonical budget_forecast_cashflow_multicurrency_no_double_count scenario.
```

Guiding rule:

> Forecasting and cash-flow are read/projection pipelines. They must not schedule reminders, mutate recurring state unexpectedly, or present partial currency data as complete.

Second guiding rule:

> Budget, forecast, and cash-flow should never operate on bare `Double` totals unless every contributing amount is known to be in the same currency.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Forecast/cashflow paths accidentally schedule recurring reminders
**STATUS: CONFIRMED — FIXED (in Pipeline 4)**
- Default `reminderWindows` parameter in `RecurringLifecycleCoordinator.generateOccurrences()` changed from `listOf("DUE_DAY")` to `emptyList()`.
- All read/projection callers (ForecastInputAssembler, CashFlowCalculator, FinancialStressForecastEngine) now correctly produce no reminder side effects.

## Finding P0-2 — Forecast math raw-sums planned/recurring across currencies
**STATUS: CONFIRMED — NOT FIXED (requires currency normalization in ForecastInputAssembler output)**

## Finding P0-3 — CashFlowCalculator raw-sums multi-currency expenses
**STATUS: CONFIRMED — NOT FIXED (requires currency normalization)**

## Finding P0-4 — Budget limit conversion failure produces fake health values
**STATUS: CONFIRMED — NOT FIXED (requires BudgetRepository / BudgetMonitor refactor)**

## Finding P0-5 — Budget alerts use raw budget.amount, not effectiveLimit
**STATUS: CONFIRMED — FIXED**
- `BudgetMonitor.sendNotification()` now accepts `effectiveLimit` parameter from `BudgetStatus`.
- Notification text uses `effectiveLimit` (which includes rollover adjustments) instead of raw `budget.amount`.
- Notification text now includes the total budget limit in the message for user clarity.

## Finding P0-6 — Budget alerts don't use shared-expense adjusted spend
**STATUS: CONFIRMED — NOT FIXED (requires BudgetMonitor to use adjustedSpendBreakdown from BudgetStatus)**

## Finding P0-7 — BudgetAutopilotEngine uses raw monthly aggregate
**STATUS: CONFIRMED — NOT FIXED (requires currency normalization)**

## Finding P0-8 — Forecast confidence ignores currency conversion failures
**STATUS: CONFIRMED — NOT FIXED (requires SynthesisEngine + AnalyticsCurrencyNormalizer integration)**

## Finding P0-9 — FinancialWeatherRepository may bypass manual recurring occurrence generation
**STATUS: CONFIRMED — NOT FIXED (requires investigation of assembler interaction)**

## Finding P0-10 — SynthesisEngine.calculateBlockPartyData uses runBlocking
**STATUS: CONFIRMED — FIXED**
- `calculateBlockPartyData()` changed from `fun` to `suspend fun`.
- `runBlocking` wrapper removed; DAO call now uses normal coroutine suspension.
- Caller `ComputeDashboardWidgetsUseCase.computeBlockParty()` also changed to `suspend fun`.
- Unused `import kotlinx.coroutines.runBlocking` removed from SynthesisEngine.

## Finding P1-7 — Budget createdAt and currency normalization are not enforced
**STATUS: CONFIRMED — PARTIALLY FIXED**
- `BudgetRepository.addBudget()` now sets `createdAt = timeProvider.now()` when the budget's `createdAt` is the sentinel value `0L`.
- Budget currency default (`EUR`) remains as a schema default; the UI should set the proper currency at creation time.

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Budget alerts use effectiveLimit instead of raw amount | `BudgetMonitor.kt` | P0-5 |
| Remove runBlocking from calculateBlockPartyData | `SynthesisEngine.kt`, `ComputeDashboardWidgetsUseCase.kt` | P0-10 |
| Set createdAt when adding budgets with sentinel 0L | `BudgetRepository.kt` | P1-7 |

---

# 14. Remaining work priority

1. **P0-2/P0-3**: Normalize all forecast/cashflow amounts to home currency before aggregation
2. **P0-4**: Handle budget limit conversion failure gracefully (skip or flag as partial)
3. **P0-6**: Use BudgetStatus.adjustedSpendBreakdown in budget alerts when available
4. **P0-7**: Normalize BudgetAutopilotEngine monthly totals to home currency
5. **P0-8**: Incorporate AnalyticsCurrencyNormalizer warnings into forecast confidence
6. **P0-9**: Verify FinancialWeatherRepository passes manual recurring entities to assembler

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `BudgetRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `Budget.kt`  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

- `BudgetDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetDao.kt

- `BudgetCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt

- `BudgetMonitor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt

- `BudgetViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt

- `BudgetAutopilotEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt

- `SharedExpenseBudgetOffsetEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `FinancialWeatherRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt

- `SynthesisEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

- `CashFlowCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `CashFlowCalendarViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModel.kt

- `HistoricalSpendingDistribution.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt

- `MonteCarloSpendingSimulator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt

- `DataQualityAssessor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt

- `FinancialStressForecastEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt

- `PlannedExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt