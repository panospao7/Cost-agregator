# Pipeline 6 Static Debug Report — Budget / Forecasting / Cashflow

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 6 is **significantly improved** since the earlier debug report, but it is **not fully clean**.

A number of previous P1 items are now genuinely addressed:

```text
BudgetForecast createdAt/currency now set by BudgetForecastingEngine
BudgetForecastDao uses REPLACE and deactivates old active forecasts
BudgetRepository CRUD has DatabaseWriteBarrier
BudgetForecastingEngine has DatabaseWriteBarrier
PlannedExpenseRepository has DatabaseWriteBarrier + timestamp repair + insert-result check
BudgetMonitor recomputes alert percent from adjusted spend when available
Rollover now propagates partial/warning state
ForecastInputAssembler normalizes actual expenses and planned expenses
ForecastDataQuality affects SynthesisEngine confidence
PlannedExpense status filtering is PLANNED-only
Recurring occurrence forecast input filters PLANNED-only
CashFlowCalculator normalizes actual income/expenses and recurring predictions
CashFlowCalculator returns deduped predicted recurring list
Stress forecast excludes PAID recurring occurrences
Budget delete now deletes forecasts first in a transaction
```

But several fixes are **partial** because the surrounding contract is still incomplete.

Highest remaining user-impact risks:

1. **Budget monitor still may not use adjusted/shared spend**, because `BudgetRepository` does not appear to populate `adjustedSpendBreakdown`; the monitor only uses it if someone else already attached it.
2. **Budget conversion failures can still display as `ON_TRACK` / 0%**, even though the status is unreliable.
3. **Forecast assembly is a read-like operation that writes recurring occurrences/reminders** through `generateOccurrences()`.
4. **Recurring patterns and confirmed occurrences can still be raw-summed in `SynthesisEngine`**, even though planned expenses are now normalized by the assembler.
5. **Budget notification timestamp updates bypass `DatabaseWriteBarrier`.**
6. **Debug budget restore path bypasses the write barrier.**
7. **Forecast/budget conversions fall back to latest/raw values with weak typed quality propagation.**
8. **Stress forecast is labeled better now, but still mostly backed by net-cashflow estimate, not real balance.**
9. **Budget delete now avoids FK failure by hard-deleting forecast history, which contradicts the earlier “forecast history is analytically valuable” contract.**
10. **Silent EUR fallback still exists in budget/forecast paths.**

Current status: **yellow**. Pipeline 6 is no longer obviously broken, but budget/forecast/cashflow numbers can still become misleading in multi-currency, shared-expense, restore-mode, and recurring-edge cases.

---

# Sources checked

- Commit page:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 6 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-6-budget-forecasting-cashflow-debug-report.md

- Current code:
  - `BudgetForecast.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt
  - `BudgetForecastDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt
  - `BudgetForecastingEngine.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt
  - `BudgetRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt
  - `BudgetMonitor.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt
  - `BudgetModels.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt
  - `PlannedExpense.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt
  - `PlannedExpenseDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt
  - `PlannedExpenseRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt
  - `ForecastInputAssembler.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt
  - `ForecastDataQuality.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastDataQuality.kt
  - `SynthesisEngine.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
  - `CashFlowCalculator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt
  - `FinancialStressForecastEngine.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt
  - `AccountBalanceProvider.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/forecasting/AccountBalanceProvider.kt
  - `NetCashflowBalanceProvider.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/forecasting/NetCashflowBalanceProvider.kt

---

# 1. Tracker reconciliation

Master tracker currently says Pipeline 6:

| ID | Tracker status |
|---|---|
| P6-P1-01 | fixed |
| P6-P1-02 | fixed |
| P6-P1-03 | TODO |
| P6-P1-04 | TODO |
| P6-P1-05 | TODO |
| P6-P1-06 | TODO |
| P6-P1-07 | fixed |
| P6-P1-08 | TODO |
| P6-P1-09 | fixed |
| P6-P1-10 | TODO |
| P6-P1-11 | TODO |
| P6-P1-12 | TODO |
| P6-P1-13 | TODO |
| P6-P1-14 | TODO |
| P6-P1-15 | TODO |

My current status after checking code:

| ID | My status | Reason |
|---|---:|---|
| P6-P1-01 | **Mostly fixed / caveat** | Forecast unique index changed to `(budgetId, targetPeriodStart, forecastDate)` and DAO uses `REPLACE`; refresh should usually work. Caveat: same-ms generation can replace history, and `targetPeriodEnd` is not part of the unique key. |
| P6-P1-02 | **Fixed for engine path** | `BudgetForecastingEngine.generateForecast()` sets `createdAt = now` and `currency = homeCurrency`. Entity defaults remain unsafe for direct inserts. |
| P6-P1-03 | **Partial** | Budget CRUD, forecast engine, and planned repository have write barriers. Budget notification timestamp updates and debug restore still bypass. DAO mutation surface remains public. |
| P6-P1-04 | **Partial** | `BudgetMonitor` recomputes percent from `adjustedSpendBreakdown.effectiveSpend` when present, but `BudgetRepository.createBudgetStatus()` does not appear to populate `adjustedSpendBreakdown`. |
| P6-P1-05 | **Mostly fixed** | Rollover loop now propagates `periodAggregate.isPartial` and warnings. Remaining issue: spend aggregates still use latest-rate MCR paths, so rate-basis quality is still weak. |
| P6-P1-06 | **Partial** | Budget limit conversion now uses `convertAsOf(periodEnd)` in repository/forecast engine, but fallback to latest/raw is weak and spend-vs-limit FX basis still differs. |
| P6-P1-07 | **Mostly fixed** | `SynthesisEngine.synthesize(input)` applies `input.dataQuality.confidencePenalty`. Warnings are not yet first-class in `FinancialForecast`. |
| P6-P1-08 | **Mostly fixed / caveat** | `ForecastInputAssembler` normalizes planned expenses to home currency and excludes failures. But `SynthesisEngine` remains unsafe if called directly with raw planned rows. |
| P6-P1-09 | **Fixed** | Mapper and engine both filter `status == "PLANNED"`. |
| P6-P1-10 | **Mostly fixed / currency caveat** | Assembler filters materialized occurrences to `PLANNED` and carries status. It still does not normalize `ConfirmedOccurrence.expectedAmount`. |
| P6-P1-11 | **Mostly fixed / caveat** | `CashFlowCalculator` converts actual income/expenses and recurring predictions, sets currency and partial flags. It uses latest conversion, not historical/as-of, and starting balance is only documented as home-currency. |
| P6-P1-12 | **Fixed** | `DailyCashFlow.predictedRecurring` now returns `deduplicatedPredicted`. |
| P6-P1-13 | **Partial** | `AccountBalanceProvider` exists, but current implementation is still net-cashflow estimate. Result has `mode`, but field names like `projectedBalance` can still mislead if UI ignores mode. |
| P6-P1-14 | **Fixed** | `ACTIVE_OCCURRENCE_STATUSES = setOf("PLANNED")`; PAID is excluded. |
| P6-P1-15 | **Mechanically fixed / product caveat** | Delete no longer fails because forecasts are deleted first. But this hard-deletes forecast history, conflicting with the entity comment that forecast history has analytical value. |

Older P2 items:

| Old issue | My status |
|---|---:|
| P2-16 PlannedExpenseRepository bypass/inserts | **Mostly fixed** — write barrier, timestamps, open key, insert result check added; still no sealed result/lifecycle event. |
| P2-17 Budget suggestions hardcode euro | **Fixed** — uses `CurrencyFormatter.formatMoney(monthlyAvg, homeCurrency)`. |
| P2-18 Budget invalidation trigger raw total query | **Fixed** — uses `observeExpenseMutationClock()`. |
| P2-19 Autopilot apply-all rollback | **Likely fixed at repository boundary** — `updateBudgetOrThrow()` exists; caller still needs verification. |
| P2-20 Budget monitor diagnostics | **Partial/fixed core** — writes `PipelineDiagnosticEvent`; still no typed budget-monitor entity and some early returns lack event. |

---

# 2. Original issue evaluation

## P6-P1-01 — Budget forecast refresh unique conflict

### Current state

Mostly fixed.

Current `BudgetForecast` index is:

```text
budgetId + targetPeriodStart + forecastDate
```

not the old:

```text
budgetId + targetPeriodStart
```

`BudgetForecastDao.insert()` uses `OnConflictStrategy.REPLACE`, and `insertWithDeactivation()` deactivates active forecasts for the same budget/target period before inserting the new forecast.

### Remaining caveats

1. `targetPeriodEnd` is not in the unique key.
2. `forecastDate` is millisecond-based. Two forecast generations in the same millisecond for the same budget/period can conflict.
3. Because insert uses `REPLACE`, conflict can delete/replace instead of preserving history.
4. The entity comments still discuss old/contradictory invariants.

### Classification

- Original user bug: mostly fixed.
- Remaining: edge/history correctness.

### Fix strategy

Use either:

```text
budgetId + targetPeriodStart + targetPeriodEnd + forecastDate
```

or a dedicated immutable forecast-run ID.

If “only one active per period” is required, implement a materialized active key:

```text
activeForecastPeriodKey = budgetId:targetPeriodStart:targetPeriodEnd when isActive = true
```

and keep historical rows unconstrained except by primary key/run ID.

---

## P6-P1-02 — Forecast rows `createdAt = 0` and wrong currency

### Current state

Fixed for the main engine.

`BudgetForecastingEngine.generateForecast()` now sets:

```kotlin
createdAt = now
currency = homeCurrency
```

### Remaining caveat

`BudgetForecast` still defaults to:

```kotlin
createdAt = 0L
currency = "EUR"
```

and `BudgetForecastDao.insert()` is public. A direct caller can still persist bad metadata.

### Fix strategy

Add an insert wrapper/coordinator:

```kotlin
ForecastLifecycleCoordinator.createForecast(...)
```

Make direct DAO insertion restricted by static guard.

Add migration/repair:

```sql
SELECT COUNT(*) FROM budget_forecasts
WHERE createdAt = 0 OR currency IS NULL OR currency = '';
```

---

## P6-P1-03 — Budget/forecast/planned writes lack restore/write barrier

### Current state

Partial.

Fixed:

- `BudgetRepository.addBudget/updateBudget/updateBudgetOrThrow/deleteBudget/toggleBudget/deleteAll` call `writeBarrier`.
- `BudgetForecastingEngine.generateForecast()` and `updateForecastAccuracy()` call `writeBarrier`.
- `PlannedExpenseRepository.add/delete/deleteById` call `writeBarrier`.

Still open:

- `BudgetRepository.updateExceededNotification()`
- `BudgetRepository.updateCriticalNotification()`
- `BudgetRepository.updateWarningNotification()`

These mutate the budget row but do not call `writeBarrier`.

Also:

- `BudgetRepository.restoreDebugSnapshot()` writes `replaceAllAndEnforceActiveScopes()` / `deleteAll()` without a write barrier and without an obvious `BuildConfig.DEBUG` guard.
- Direct DAO surfaces remain public.

### User impact

During restore/restart-required mode, budget alert timestamps or debug restore can mutate database state.

### Fix strategy

Add barrier to all budget writes, including notification timestamps:

```kotlin
suspend fun updateExceededNotification(id: Long, timestamp: Long) {
    writeBarrier.checkWritesAllowed("BudgetRepository.updateExceededNotification")
    budgetDao.updateExceededNotification(id, timestamp)
}
```

For debug snapshot restore:

```kotlin
if (!BuildConfig.DEBUG) error("Debug snapshot restore is debug-only")
writeBarrier.checkWritesAllowed("BudgetRepository.restoreDebugSnapshot")
```

---

## P6-P1-04 — Budget alerts use gross percent when adjusted shared spend exists

### Current state

Partial.

Good:

`BudgetMonitor` now does:

```kotlin
val spent = status.adjustedSpendBreakdown?.effectiveSpend ?: status.spentAmount
val adjustedPercent = spent / status.effectiveLimit
```

and uses `adjustedPercent` for alert thresholds and notification content.

Problem:

`BudgetRepository.createBudgetStatus()` does not appear to populate `adjustedSpendBreakdown`, even though `SharedExpenseBudgetOffsetEngine` is injected.

There is a TODO inside `BudgetMonitor` explicitly noting that the monitor may see `null` and fall back to gross spend.

### User impact

Shared/reimbursed expenses can still trigger false budget alerts unless the status came from a UI path that manually attached adjusted spend.

### Fix strategy

Move adjusted-spend computation into `BudgetRepository.createBudgetStatus()` so every consumer receives the same canonical `BudgetStatus`.

Add fields:

```kotlin
grossSpentAmount
adjustedSpendAmount
grossPercentUsed
adjustedPercentUsed
percentBasis
```

Budget monitor should only use canonical `alertPercentUsed`.

---

## P6-P1-05 — Rollover ignores partial conversion state

### Current state

Mostly fixed.

The rollover loop now propagates:

```kotlin
budgetIsPartial = budgetIsPartial || periodAggregate.isPartial
budgetWarningMessage += periodAggregate.warningMessage
```

### Remaining issue

`getAggregateSpent()` still uses `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` / category totals, which are mostly latest-rate based per Pipeline 5 findings. So rollover quality is propagated, but the underlying rate basis may still be inconsistent with period-end budget limit conversion.

### Fix strategy

Use explicit historical aggregate API:

```kotlin
getHomeCurrencyPurchaseTotalHistorical(start, end, basis = TRANSACTION_DATE)
```

Then propagate:

```text
rolloverPartialPeriodCount
rolloverFailedConversionCount
rolloverRateBasis
```

---

## P6-P1-06 — Budget limit conversion uses current/latest rate

### Current state

Partial.

Good:

- `BudgetRepository.convertBudgetAmountToHomeCurrencyAsOf()` uses `convertAsOf(..., asOfMillis = periodEnd)`.
- `BudgetForecastingEngine.generateForecast()` also tries `convertAsOf(..., periodEnd)`.

Problems:

1. `BudgetRepository` still uses latest-rate MCR spend aggregate while limit uses period-end historical rate.
2. If budget conversion fails, `BudgetRepository` returns a `MoneyAggregate` with the original source currency but then forces `percent = 0` and `health = ON_TRACK`.
3. `BudgetForecastingEngine` falls back to latest conversion, then raw budget amount, but does not attach a typed forecast quality warning.
4. Forecast output has no first-class `limitConversionBasis`.

### User impact

Budget can show “ON_TRACK” even when the app does not know the comparable home-currency budget limit.

Forecast risk can be under/overstated if the budget limit was raw-fallback.

### Fix strategy

Add a typed budget-limit conversion outcome:

```kotlin
sealed interface BudgetLimitConversion {
    data class Converted(val amount: Double, val basis: RateBasis) : BudgetLimitConversion
    data class Failed(val reason: String) : BudgetLimitConversion
}
```

If failed:

```text
percentUsed = null
healthStatus = UNKNOWN / UNRELIABLE
isPartial = true
```

Do not display `ON_TRACK`.

---

## P6-P1-07 — Forecast data quality ignored by `SynthesisEngine`

### Current state

Mostly fixed.

`SynthesisEngine.synthesize(input)` now computes:

```kotlin
finalConfidence = forecast.confidence - input.dataQuality.confidencePenalty
```

### Remaining issue

The reduced confidence is the only durable output. Warnings like:

```text
3 actual transactions excluded
2 planned expenses excluded
missing FX rates
```

are not carried into `FinancialForecast`.

### Fix strategy

Extend `FinancialForecast` with:

```kotlin
dataQuality: ForecastDataQuality
warnings: List<String>
isPartial: Boolean
```

Then UI can show a warning instead of just lower confidence.

---

## P6-P1-08 — Planned expenses not normalized before forecast arithmetic

### Current state

Mostly fixed through the assembler.

`ForecastInputAssembler` now:

- filters planned entities to `PLANNED`,
- converts planned expenses to home currency,
- excludes planned expenses when conversion fails,
- adds planned conversion warnings,
- sets `excludedPlannedCount`.

But `SynthesisEngine` still groups by currency and raw-sums values. That is only safe if every caller uses `ForecastInputAssembler`.

### User impact

Any direct call to `SynthesisEngine.synthesize(...)` with raw planned expenses can still raw-sum currencies.

### Fix strategy

Make `SynthesisEngine` accept only normalized models:

```kotlin
data class NormalizedPlannedExpense(
    val amountHome: Double,
    val displayCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String
)
```

Or add a guard:

```kotlin
require(plannedExpenses.all { it.currency == spendingPace.displayCurrency })
```

---

## P6-P1-09 — Cancelled/skipped planned expenses enter forecast

### Current state

Fixed.

Both:

```text
ForecastInputAssembler.mapPlannedExpenses()
SynthesisEngine.synthesizeInternal()
```

filter `status == "PLANNED"`.

### Remaining cleanup

Replace raw string statuses with enum/sealed status.

---

## P6-P1-10 — Recurring occurrence status lost before forecast

### Current state

Mostly fixed.

`ForecastInputAssembler` now:

```kotlin
materializedOccurrences.filter { it.status == "PLANNED" }
```

and maps status into `ConfirmedOccurrence`.

So cancelled/skipped/paid occurrences no longer enter `confirmedOccurrences`.

### Remaining problems

1. `ConfirmedOccurrence` does not carry enough data:
   - linked expense ID,
   - paid amount,
   - paid currency,
   - paid timestamp,
   - source key.
2. `ConfirmedOccurrence.expectedAmount` is not normalized to home currency before `SynthesisEngine` sums it.
3. Assembler calls `generateOccurrences()` while assembling forecast input, which performs writes and may create reminder deliveries.

### Fix strategy

Create:

```kotlin
data class NormalizedConfirmedOccurrence(
    val occurrenceId: Long,
    val occurrenceKey: String,
    val dueDate: Long,
    val amountHome: Double,
    val displayCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val status: RecurringOccurrenceStatus
)
```

Forecast input should not perform side-effecting generation unless explicitly requested.

---

## P6-P1-11 — Cash-flow calendar raw-sums multi-currency amounts

### Current state

Mostly fixed.

`CashFlowCalculator` now:

- resolves home currency,
- converts income rows,
- converts expense rows,
- converts deduped recurring predictions,
- excludes conversion failures,
- sets `DailyCashFlow.currency`,
- sets `isPartial` and `failedConversionCount`.

### Remaining caveats

1. It uses latest conversion, not `convertAsOf(expense.date)`.
2. `startingBalance` is documented as home currency but not typed/enforced.
3. Returned `income` and `expenses` lists are raw `Expense` entities. If the UI sums those lists, it can still raw-sum.
4. `predictedRecurring` list contains original recurring pattern amounts/currencies, not normalized display rows.

### Fix strategy

Return typed normalized cashflow rows:

```kotlin
data class CashFlowLineItem(
    val originalAmount: Double,
    val originalCurrency: String,
    val amountHome: Double?,
    val displayCurrency: String,
    val conversionFailure: String?
)
```

Make starting balance:

```kotlin
startingBalance: MoneyAmount
```

or require `NormalizedBalance`.

---

## P6-P1-12 — Cash-flow output displays pre-dedup recurring predictions

### Current state

Fixed.

Returned `DailyCashFlow.predictedRecurring` now uses `deduplicatedPredicted`.

### Caveat

Dedup is merchant/date only, not amount/currency/fingerprint aware. It can suppress a valid recurring prediction if the same merchant has multiple same-day transactions.

### Fix strategy

Use content-aware dedupe:

```text
merchantKey + date + amount tolerance + currency + source rule ID
```

---

## P6-P1-13 — Stress forecast is not real account-balance forecast

### Current state

Partial.

Good:

- `AccountBalanceProvider` abstraction exists.
- `NetCashflowBalanceProvider` is explicit fallback.
- `StressForecastResult.mode` exists.
- Engine returns `NET_CASHFLOW_ESTIMATE`.

Still not complete:

- There is no manual or bank balance provider yet.
- Result fields remain named `projectedBalance` and `minProjectedBalance`.
- If UI ignores `mode`, user can still read the output as real account balance.
- `resolveDisplayCurrency()` silently defaults to EUR.

### Fix strategy

Add stronger model:

```kotlin
data class BalanceBaseline(
    val amount: Double,
    val currency: String,
    val source: BalanceSource,
    val warning: String?
)
```

And rename output if source is estimated:

```text
projectedNetCashflowEstimate
```

or force UI label based on `mode`.

---

## P6-P1-14 — Stress forecast counts PAID occurrences as active outflows

### Current state

Fixed.

`ACTIVE_OCCURRENCE_STATUSES` is now:

```kotlin
setOf("PLANNED")
```

PAID is excluded.

### Cleanup

There is stale comment/dead defensive code referring to PAID branch. Clean it to prevent future confusion.

---

## P6-P1-15 — Deleting budget can fail after forecasts exist

### Current state

Mechanically fixed.

`BudgetRepository.deleteBudget()` now runs:

```kotlin
database.withTransaction {
    budgetForecastDao.deleteForecastsForBudget(budget.id)
    budgetDao.delete(budget)
}
```

So FK `RESTRICT` should not block deletion.

### New concern

This hard-deletes forecast history, while `BudgetForecast` comments say the FK is `RESTRICT` to preserve historical forecasts because they have standalone analytical value.

### Classification

- Original delete failure: fixed.
- Product/audit semantics: partial.

### Fix strategy

Introduce explicit delete policy:

```kotlin
enum class BudgetDeletePolicy {
    ARCHIVE_WITH_FORECAST_HISTORY,
    HARD_DELETE_WITH_FORECAST_HISTORY,
    RESTRICT_IF_FORECAST_HISTORY_EXISTS
}
```

Default should likely be archive, not silent history deletion.

---

# 3. New/current issues found

## P6-NEW-01 — Budget notification timestamp writes bypass write barrier

### Severity

P1.

### Evidence

`BudgetRepository.updateExceededNotification()`, `updateCriticalNotification()`, and `updateWarningNotification()` call DAO update methods directly.

### User impact

Budget rows can be mutated during restore/restart-required state.

### Fix

Add `writeBarrier.checkWritesAllowed()` to each method.

---

## P6-NEW-02 — `restoreDebugSnapshot()` mutates budgets without barrier/debug guard

### Severity

P1/P2.

### Evidence

`BudgetRepository.restoreDebugSnapshot()` calls:

```text
budgetDao.replaceAllAndEnforceActiveScopes(...)
budgetDao.deleteAll()
```

without a visible write barrier or `BuildConfig.DEBUG` guard.

### User impact

Debug/repair path can modify budget state during restore, or potentially in release if reachable.

### Fix

Guard with both:

```kotlin
if (!BuildConfig.DEBUG) error("Debug-only")
writeBarrier.checkWritesAllowed("BudgetRepository.restoreDebugSnapshot")
```

---

## P6-NEW-03 — Budget conversion failure maps to `ON_TRACK`

### Severity

P1.

### Evidence

When `initialLimitAggregate.isPartial` is true, code forces:

```text
percent = 0
health = ON_TRACK
```

and relies on warning fields.

### User impact

A budget with unknown converted limit can look safe.

### Fix

Add:

```kotlin
BudgetHealthStatus.UNKNOWN
```

or:

```kotlin
isReliable = false
percentUsed = null
```

Do not display `ON_TRACK` for unknown math.

---

## P6-NEW-04 — Adjusted shared-spend pipeline is not canonical

### Severity

P1.

### Evidence

`BudgetMonitor` uses `adjustedSpendBreakdown` only if present, but repository-created statuses appear to leave it null.

### User impact

False alerts for shared/reimbursed spend.

### Fix

Compute adjusted spend in `BudgetRepository.createBudgetStatus()`.

---

## P6-NEW-05 — Forecast budget-limit conversion fallback can silently use raw amount

### Severity

P1.

### Evidence

`BudgetForecastingEngine` tries historical conversion, then latest conversion, then raw `budget.amount`.

It logs but does not add structured forecast quality.

### User impact

Forecast can compare normalized spending to raw foreign budget amount.

### Fix

Return structured conversion failure and make forecast partial/unreliable.

---

## P6-NEW-06 — Forecast history can be replaced on same-ms generation

### Severity

P2/P1 edge.

### Evidence

Unique key:

```text
budgetId + targetPeriodStart + forecastDate
```

with `REPLACE`.

### User impact

Rapid concurrent generation can replace a forecast row instead of preserving history.

### Fix

Use generated `forecastRunId` or include `targetPeriodEnd` and avoid `REPLACE`.

---

## P6-NEW-07 — Forecast input assembly performs side-effecting occurrence generation

### Severity

P1.

### Evidence

`ForecastInputAssembler.assemble()` calls:

```kotlin
recurringLifecycleCoordinator.generateOccurrences(...)
```

for each active manual recurring rule.

`FinancialStressForecastEngine` and `CashFlowCalculator` also call occurrence generation from read-like forecast paths.

### User impact

Opening forecast/cashflow can mutate recurring occurrence tables and possibly create reminder deliveries, depending on Pipeline 4 generation behavior.

### Fix

Separate read and write modes:

```kotlin
OccurrenceQueryMode.EXISTING_ONLY
OccurrenceQueryMode.MATERIALIZE_WITHOUT_REMINDERS
OccurrenceQueryMode.MATERIALIZE_WITH_REMINDERS
```

Forecast/cashflow should use no-reminder mode at minimum.

---

## P6-NEW-08 — Recurring patterns remain unnormalized in `SynthesisEngine`

### Severity

P1.

### Evidence

`ForecastInputAssembler.mergeRecurringPatterns()` has a TODO saying patterns retain original currency.

`SynthesisEngine` sums:

```text
recurringPatterns.averageAmount
monthlyRecurringTotal
recurringOnDay
```

without conversion.

### User impact

A USD recurring bill and EUR budget can be raw-summed.

### Fix

Normalize recurring patterns in assembler:

```kotlin
RecurringPattern.amountHome
RecurringPattern.displayCurrency
```

or create `NormalizedRecurringPattern`.

---

## P6-NEW-09 — Confirmed occurrences are not currency-normalized

### Severity

P1.

### Evidence

Assembler maps `RecurringOccurrence.expectedAmount` and `expectedCurrency` into `ConfirmedOccurrence`, but `SynthesisEngine` sums `expectedAmount`.

### User impact

Confirmed manual occurrences can raw-sum into committed totals.

### Fix

Normalize occurrence amounts before adding to forecast input.

---

## P6-NEW-10 — Cashflow uses latest FX, not date-specific FX

### Severity

P2/P1 for historical cashflow.

### Evidence

`CashFlowCalculator` uses `currencyConverter.convert(...)`, not `convertAsOf(...)`.

### User impact

Historical cashflow calendar can change when current FX rates change.

### Fix

Use:

```kotlin
convertAsOf(amount, currency, homeCurrency, expense.date)
```

For recurring future predictions, use forecast-date or latest basis explicitly.

---

## P6-NEW-11 — Stress forecast detected-only patterns raw-sum currency

### Severity

P1.

### Evidence

`FinancialStressForecastEngine.expandDetectedPatterns()` sums `pattern.averageAmount` directly.

Manual occurrence path converts, but detected-only fallback does not.

### User impact

Detected recurring obligations in foreign currencies distort stress forecast.

### Fix

Pass display currency into expansion and convert each pattern.

---

## P6-NEW-12 — Stress forecast has no partial/data-quality output

### Severity

P2.

### Evidence

Actual expenses/deposits are normalized and excluded on conversion failure, but `StressForecastResult` does not expose partial counts/warnings.

### User impact

User sees a stress forecast without knowing some transactions were excluded.

### Fix

Add:

```kotlin
dataQuality: ForecastDataQuality
```

to `StressForecastResult`.

---

## P6-NEW-13 — Budget delete silently deletes forecast history

### Severity

P2/P1 depending on product promise.

### Evidence

`deleteBudget()` deletes forecasts first.

### User impact

Accuracy history and forecast audit disappear when budget is deleted.

### Fix

Archive budgets by default or require explicit delete-history policy.

---

## P6-NEW-14 — Silent EUR fallback remains

### Severity

P2/P1 for non-EUR users.

### Evidence

`BudgetRepository.resolveHomeCurrency()` and `FinancialStressForecastEngine.resolveDisplayCurrency()` default to `"EUR"` on failure.

### User impact

If settings fail, user can see EUR-denominated budgets/forecasts silently.

### Fix

Use explicit:

```kotlin
HomeCurrencyResolution.Failed
```

and mark financial outputs unavailable/partial.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize these:

1. **Budget monitor can still alert on gross spend because adjusted spend is not canonical.**
2. **Budget conversion failure can display as `ON_TRACK`.**
3. **Budget notification timestamp writes bypass restore barrier.**
4. **Forecast/cashflow read paths write recurring occurrences/reminders.**
5. **Recurring patterns and confirmed occurrences can still raw-sum currencies in synthesis/block-party.**
6. **Forecast budget-limit conversion can fall back to raw value without structured partial state.**
7. **Stress detected recurring patterns raw-sum currency.**
8. **Cashflow uses latest FX for historical actuals.**
9. **Budget deletion silently removes forecast history.**
10. **Silent EUR fallback can mislabel outputs.**

## Architectural / cleanup work

Important but lower immediate urgency:

1. Create `BudgetLifecycleCoordinator`.
2. Create `ForecastLifecycleCoordinator`.
3. Add typed budget/forecast/cashflow quality models.
4. Replace raw string planned/occurrence statuses with enums.
5. Add typed normalized models for planned, recurring, occurrence, cashflow rows.
6. Add explicit rate-basis APIs.
7. Add static guard for direct budget/planned/forecast DAO writes.
8. Add forecast run ledger.
9. Add archive semantics for budgets.
10. Remove stale comments and dead PAID branches.

---

# 5. Recommended implementation plan

## PR 1 — Budget alert correctness and conversion reliability

### Goal

Budget cards and alerts cannot claim safe state from unreliable math.

### Files

- `BudgetRepository.kt`
- `BudgetMonitor.kt`
- `BudgetModels.kt`
- `BudgetStatusSnapshot` / dashboard mapping

### Tasks

1. Compute `adjustedSpendBreakdown` inside `BudgetRepository.createBudgetStatus()`.
2. Add:
   - `grossPercentUsed`
   - `adjustedPercentUsed`
   - `alertPercentUsed`
   - `percentBasis`
3. Add `BudgetHealthStatus.UNKNOWN` or `BudgetReliability`.
4. If budget limit conversion fails, status is unreliable, not `ON_TRACK`.
5. Budget monitor uses `alertPercentUsed`.

### Acceptance tests

```text
budget_status_populates_adjustedSpendBreakdown
budget_alert_uses_adjusted_percent_when_shared_reimbursement_exists
budget_conversion_failure_health_unknown_not_on_track
budget_card_shows_conversion_warning
```

---

## PR 2 — Restore/write barrier sweep

### Goal

All budget/planned/forecast writes obey restore mode.

### Files

- `BudgetRepository.kt`
- `BudgetForecastDao` callsites
- `PlannedExpenseRepository.kt`
- static guard

### Tasks

1. Add barrier to notification timestamp updates.
2. Add barrier + DEBUG guard to `restoreDebugSnapshot()`.
3. Search all direct:
   - `budgetDao.update`
   - `budgetDao.delete`
   - `budgetForecastDao.insert/update/delete`
   - `plannedExpenseDao.insert/update/delete`
4. Allow only lifecycle/repository/coordinator paths.

### Acceptance tests

```text
restore_blocks_budget_notification_timestamp_update
restore_blocks_debug_budget_restore
restore_blocks_forecast_accuracy_update
restore_blocks_planned_delete
```

---

## PR 3 — Normalize recurring forecast inputs

### Goal

No `SynthesisEngine` arithmetic on raw recurring currencies.

### Files

- `ForecastInputAssembler.kt`
- `SynthesisEngine.kt`
- `ConfirmedOccurrence` model
- `RecurringPattern` or new normalized DTO

### Tasks

1. Convert recurring patterns to home currency before `ForecastInput`.
2. Convert confirmed occurrences to home currency.
3. Exclude conversion failures and increment `excludedRecurringCount`.
4. Add guard in `SynthesisEngine` requiring one display currency.
5. Add data-quality warnings.

### Acceptance tests

```text
recurring_pattern_usd_converted_before_synthesis
confirmed_occurrence_usd_converted_before_committed_total
recurring_conversion_failure_excluded_and_reduces_confidence
synthesis_engine_rejects_mixed_currency_input
```

---

## PR 4 — Make forecast assembly read-safe

### Goal

Forecast/cashflow screens do not create reminder deliveries or unexpected DB rows.

### Files

- `RecurringLifecycleCoordinator.kt`
- `ForecastInputAssembler.kt`
- `FinancialStressForecastEngine.kt`
- `CashFlowCalculator.kt`

### Tasks

1. Add generation options:
   ```kotlin
   createOccurrences: Boolean
   createReminderDeliveries: Boolean
   source: String
   ```
2. Forecast/cashflow uses:
   ```text
   createOccurrences = true only if needed
   createReminderDeliveries = false
   ```
3. Pure read mode uses existing occurrences only.
4. Add diagnostics when generation is skipped/unavailable.

### Acceptance tests

```text
forecast_assemble_does_not_create_reminder_deliveries
cashflow_calculate_does_not_create_reminder_deliveries
forecast_existing_only_mode_does_not_write_db
restore_mode_forecast_does_not_attempt_occurrence_write
```

---

## PR 5 — Forecast conversion quality hardening

### Goal

Forecasts know when they used fallback/latest/raw conversion.

### Files

- `BudgetForecastingEngine.kt`
- `BudgetForecast.kt`
- `FinancialForecast` model
- maybe `ForecastDataQuality.kt`

### Tasks

1. Use typed budget-limit conversion outcome.
2. Do not raw-fallback without marking forecast partial.
3. Persist:
   - `rateBasis`
   - `isPartial`
   - `conversionWarning`
   - `excludedActualCount`
   - `excludedPlannedCount`
   - `excludedRecurringCount`
4. Surface warnings in forecast UI.

### Acceptance tests

```text
forecast_budget_limit_missing_rate_marks_partial
forecast_budget_limit_latest_fallback_records_basis
forecast_confidence_reduced_by_limit_conversion_failure
forecast_does_not_compare_normalized_spend_to_raw_foreign_limit_silently
```

---

## PR 6 — Cashflow normalized line items and rate basis

### Goal

Cashflow UI cannot raw-sum returned row lists.

### Files

- `CashFlowCalculator.kt`
- `DailyCashFlow` model
- cashflow UI/ViewModel

### Tasks

1. Return normalized line items.
2. Use `convertAsOf()` for historical actuals.
3. Use explicit latest/forecast-date rate for future recurring predictions.
4. Type starting balance as home-currency `MoneyAmount`.
5. Surface day-level warnings.

### Acceptance tests

```text
cashflow_actual_usd_expense_uses_expense_date_rate
cashflow_starting_balance_requires_home_currency
cashflow_income_list_contains_normalized_amount
cashflow_recurring_prediction_contains_normalized_amount
```

---

## PR 7 — Stress forecast quality + detected recurring conversion

### Goal

Stress forecast has correct currency and transparent baseline.

### Files

- `FinancialStressForecastEngine.kt`
- `StressForecastResult`
- `NetCashflowBalanceProvider.kt`
- UI

### Tasks

1. Convert detected-only recurring patterns in `expandDetectedPatterns()`.
2. Add `ForecastDataQuality` to `StressForecastResult`.
3. Add `BalanceBaseline` object with:
   - source,
   - amount,
   - currency,
   - warning.
4. Avoid silent EUR fallback.
5. Rename or clearly label net-cashflow estimate.

### Acceptance tests

```text
stress_detected_usd_recurring_converted_to_home
stress_conversion_failure_marks_partial
stress_result_exposes_net_cashflow_baseline_warning
stress_home_currency_failure_does_not_silent_eur
```

---

## PR 8 — Budget delete/archive policy

### Goal

Budget deletion semantics are explicit and do not silently erase useful history.

### Files

- `BudgetRepository.kt`
- `Budget` entity/migration if archive chosen
- UI delete dialog

### Tasks

1. Add `isArchived` to `Budget`, or delete policy enum.
2. Default delete action archives budget and keeps forecasts.
3. If hard delete is requested, delete forecasts transactionally with explicit user confirmation.
4. Add forecast-history count to delete confirmation.

### Acceptance tests

```text
delete_budget_default_archives_and_keeps_forecasts
hard_delete_budget_deletes_forecasts_with_explicit_policy
delete_budget_with_forecast_history_not_silent
archived_budget_not_active_in_statuses
```

---

## PR 9 — Forecast history uniqueness cleanup

### Goal

Forecast history is stable under fast/concurrent generation.

### Files

- `BudgetForecast.kt`
- `BudgetForecastDao.kt`
- Room migration

### Tasks

1. Add `forecastRunId` or include `targetPeriodEnd` in unique index.
2. Stop using `REPLACE` for normal history inserts.
3. Return sealed result for duplicate/concurrent insert.
4. Add migration test.

### Acceptance tests

```text
two_forecasts_same_millisecond_do_not_replace_history
forecast_unique_key_includes_target_period_end
latest_active_forecast_returns_newest
forecast_history_count_increases_on_refresh
```

---

# 6. Suggested tracker updates

Update Pipeline 6 tracker:

| ID | Suggested status |
|---|---|
| P6-P1-01 | Mostly fixed / edge caveat |
| P6-P1-02 | Fixed for engine path |
| P6-P1-03 | Partial |
| P6-P1-04 | Partial |
| P6-P1-05 | Mostly fixed |
| P6-P1-06 | Partial |
| P6-P1-07 | Mostly fixed |
| P6-P1-08 | Mostly fixed / direct-engine caveat |
| P6-P1-09 | Fixed |
| P6-P1-10 | Mostly fixed / currency caveat |
| P6-P1-11 | Mostly fixed / rate-basis caveat |
| P6-P1-12 | Fixed |
| P6-P1-13 | Partial |
| P6-P1-14 | Fixed |
| P6-P1-15 | Mechanically fixed / archive-policy caveat |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P6-NEW-01 | P1 | Budget notification timestamp writes bypass write barrier |
| P6-NEW-02 | P1/P2 | `restoreDebugSnapshot()` mutates budgets without barrier/debug guard |
| P6-NEW-03 | P1 | Budget conversion failure maps to `ON_TRACK` |
| P6-NEW-04 | P1 | Adjusted shared-spend pipeline is not canonical |
| P6-NEW-05 | P1 | Forecast budget-limit conversion fallback can silently use raw amount |
| P6-NEW-06 | P2/P1 | Forecast history can be replaced on same-ms generation |
| P6-NEW-07 | P1 | Forecast input assembly performs side-effecting occurrence generation |
| P6-NEW-08 | P1 | Recurring patterns remain unnormalized in `SynthesisEngine` |
| P6-NEW-09 | P1 | Confirmed occurrences are not currency-normalized |
| P6-NEW-10 | P2/P1 | Cashflow uses latest FX, not date-specific FX |
| P6-NEW-11 | P1 | Stress forecast detected-only patterns raw-sum currency |
| P6-NEW-12 | P2 | Stress forecast has no partial/data-quality output |
| P6-NEW-13 | P2/P1 | Budget delete silently deletes forecast history |
| P6-NEW-14 | P2/P1 | Silent EUR fallback remains |

---

# 7. Golden tests for Pipeline 6

Add or verify:

```text
forecast_sets_createdAt_and_home_currency
generate_forecast_twice_same_period_keeps_history_and_latest_active
two_forecasts_same_millisecond_do_not_replace_history
restore_blocks_budget_crud
restore_blocks_budget_notification_timestamp_update
restore_blocks_forecast_generation
restore_blocks_forecast_accuracy_update
restore_blocks_planned_expense_insert
restore_blocks_debug_budget_restore
budget_status_populates_adjusted_spend_breakdown
budget_alert_uses_adjusted_shared_spend_percent
budget_conversion_failure_status_unknown_not_on_track
rollover_prior_period_missing_rate_marks_status_partial
budget_limit_period_end_conversion_records_rate_basis
forecast_budget_limit_missing_rate_marks_forecast_partial
forecast_confidence_reduced_when_actual_expenses_excluded
forecast_confidence_reduced_when_planned_conversion_failed
forecast_confidence_reduced_when_recurring_conversion_failed
planned_expense_usd_converted_to_home_currency
cancelled_planned_expense_excluded_from_forecast
skipped_planned_expense_excluded_from_forecast
confirmed_occurrence_usd_converted_before_synthesis
recurring_pattern_usd_converted_before_monthly_recurring_total
forecast_assembly_does_not_create_reminder_deliveries
cashflow_converts_actual_income_and_expense_to_home_currency
cashflow_uses_expense_date_rate_for_actuals
cashflow_converts_recurring_prediction_to_home_currency
cashflow_display_uses_deduped_predicted_recurring
cashflow_starting_balance_requires_home_currency
stress_forecast_excludes_paid_occurrences_from_future_outflows
stress_detected_recurring_usd_converted_to_home
stress_forecast_exposes_net_cashflow_baseline_warning
stress_forecast_partial_when_conversion_fails
delete_budget_default_archives_or_requires_explicit_history_delete
budget_suggestion_uses_home_currency_symbol
home_currency_failure_does_not_silent_eur_for_budget_forecast
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "BudgetForecast(" app/src/main/java
grep -R "budgetForecastDao.insert" app/src/main/java
grep -R "insertWithDeactivation" app/src/main/java
grep -R "BudgetRepository.updateExceededNotification" app/src/main/java
grep -R "updateWarningNotification" app/src/main/java
grep -R "restoreDebugSnapshot" app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt
grep -R "adjustedSpendBreakdown" app/src/main/java
grep -R "BudgetHealthStatus.ON_TRACK" app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt
grep -R "currencyConverter.convert(" app/src/main/java/com/yourname/expensetracker/domain/budget
grep -R "generateOccurrences" app/src/main/java/com/yourname/expensetracker/domain/forecasting app/src/main/java/com/yourname/expensetracker/domain/cashflow
grep -R "averageAmount" app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
grep -R "expectedAmount" app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
grep -R "groupBy { it.currency }" app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
grep -R "getOrDefault(\"EUR\")" app/src/main/java/com/yourname/expensetracker/domain app/src/main/java/com/yourname/expensetracker/data/repository
grep -R "deleteForecastsForBudget" app/src/main/java
```

Allowed raw-money usage should be explicit:

```text
- original transaction row display
- source bucket construction before conversion
- already-normalized DTOs whose currency is asserted
- debug/test fixtures
```

Definition of done:

```text
- Budget alerts use canonical adjusted/net spend.
- Budget conversion failure never displays as reliable ON_TRACK.
- Every budget/planned/forecast write checks DatabaseWriteBarrier.
- Forecast assembly does not create reminder deliveries.
- Recurring patterns and confirmed occurrences are normalized before synthesis.
- Forecast quality includes actual/planned/recurring/limit conversion failures.
- Cashflow returns normalized line items and uses date-specific actual FX.
- Stress forecast converts detected recurring patterns and exposes data quality.
- Budget deletion has explicit archive/delete-history semantics.
- Silent EUR fallback is replaced by explicit failure/partial output.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Budget alert correctness + unknown conversion state** — prevents false or misleading budget alerts.
2. **Write-barrier sweep** — budget notification timestamps and debug restore.
3. **Normalize recurring patterns and confirmed occurrences before synthesis** — prevents mixed-currency forecast totals.
4. **Make forecast/cashflow occurrence generation read-safe** — no reminder deliveries from forecast screens.
5. **Forecast conversion quality hardening** — no raw budget fallback without partial state.
6. **Cashflow normalized line items + date-specific FX.**
7. **Stress forecast detected-pattern conversion + data quality.**
8. **Budget delete/archive policy.**
9. **Forecast history unique-key cleanup.**
10. **Replace silent EUR fallback with explicit home-currency resolution.**