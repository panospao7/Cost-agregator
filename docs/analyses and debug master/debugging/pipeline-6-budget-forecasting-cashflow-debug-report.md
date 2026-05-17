# Pipeline 6 Debug Report — Budget / Forecasting / Cash Flow

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 6 is **partially stabilized but not clean/stable yet**.

The refactor has added important foundations:

- `BudgetCalculator` as canonical period-boundary authority.
- Budget spent calculations now mostly use `MultiCurrencyRepository`.
- Budget status carries `isPartial` and `conversionWarning`.
- Budget rollover and deficit tracking exist.
- Budget monitor avoids marking notifications sent unless delivery succeeds.
- Forecast input assembly normalizes actual expenses.
- Recurring/planned forecast dedupe exists.
- Monte Carlo simulator has deterministic seed + data-quality confidence.
- Cash-flow calculator now uses recurring occurrences for manual rules.

But the pipeline remains **yellow/orange** because several core user-visible paths can still produce wrong or broken results:

1. budget forecast refresh can fail due conflicting DB unique index;
2. forecasts are saved with `createdAt = 0` and often wrong currency;
3. budget/forecast/planned writes lack restore/write-barrier ownership;
4. budget alerts still use gross percent even when adjusted shared spend exists;
5. rollover ignores partial conversion state from prior periods;
6. planned expenses are not actually converted before forecast arithmetic;
7. skipped/cancelled/paid recurring occurrences can still enter forecast totals;
8. cash-flow calendar raw-sums multi-currency amounts;
9. stress forecast is still not a real cash-balance forecast;
10. delete budget can fail after forecasts due `RESTRICT` FK.

Overall: **usable beta, not production-clean**.

---

# Severity scale

- **P0 / Critical:** broken core feature, duplicate/wrong financial totals, or data corruption.
- **P1 / High:** common user-visible wrong budget/forecast/cash-flow output, restore/write-barrier hole, lifecycle bypass.
- **P2 / Medium:** important edge correctness, diagnostics, stale UX, maintainability/regression risk.
- **P3 / Low:** polish/cleanup.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Budget CRUD works | Partial. Basic add/update/delete exists, but no restore guard/lifecycle events; delete can fail if forecasts exist. |
| Category budget and overall budget distinct | Mostly yes via `categoryId = null` vs non-null. Active-scope contract is still ambiguous. |
| Category delete restricted when active budget exists | Good. Budget FK uses `RESTRICT`. |
| Budget rollover works | Partial. Rollover/deficit tracking exists, but prior-period conversion partials are ignored and rollover is N+1. |
| Budget alert threshold correct | Partial/buggy. Gross `percentUsed` is still used even when adjusted spend is available. |
| Budget monitor called after expense changes | Mostly yes through transaction side effects, but budget CRUD itself does not trigger monitor explicitly. |
| Forecast uses expenses + budgets + recurring | Partial. Actual expenses are normalized; recurring/planned paths still have status/currency gaps. |
| Monte Carlo handles sparse data | Mostly yes. Degraded deterministic mode and confidence exist. |
| Deterministic forecast stable with fixed clock | Partial. Random seed is fixed; some paths still use system/default Calendar and `System.currentTimeMillis()` for timing. |
| Cash-flow calendar does not double-count planned and actual | Not guaranteed. Merchant/date dedupe is weak and output still exposes pre-dedup predictions. |

---

# Positive findings to preserve

## PF-01 — Budget period logic is much cleaner

`BudgetCalculator` now owns budget-period windows and validates `periodMode` instead of silently defaulting unknown strings to calendar mode.

Good properties:

```text
ROLLING vs CALENDAR explicit
half-open period ranges
month-end/leap-year/DST comments
explicit evaluation-time API
```

## PF-02 — Budget spent calculations are mostly currency-aware

`BudgetRepository.createBudgetStatus()` uses `MultiCurrencyRepository` for spending, not raw mixed-currency `SUM(amount)`.

It also uses effective ownership-aware spending from DAO paths.

## PF-03 — Budget active-scope helpers are better

`BudgetDao` has transaction helpers:

```text
insertAndActivateOverall
insertAndActivateCategory
updateAndEnforceActiveScope
setActiveAndEnforceScope
replaceAllAndEnforceActiveScopes
```

Materialized keys also help enforce active-budget uniqueness.

## PF-04 — Rollover deficit tracking exists

`rolloverDeficitTracking` controls whether negative carryover reduces the next period. This is the right contract.

## PF-05 — Budget monitor delivery semantics improved

`BudgetMonitor` updates notification timestamps only when `NotificationService` reports delivered.

This avoids suppressing alerts when OS/user permission blocks notification delivery.

## PF-06 — Forecast actual-expense input is normalized

`ForecastInputAssembler` uses `AnalyticsCurrencyNormalizer.normalizeSnapshots()` before building:

```text
pastSumDaily
spendingPace
forecast actuals
```

## PF-07 — Monte Carlo forecast is deterministic and quality-aware

`MonteCarloSpendingSimulator` uses fixed seed `42L` and `DataQualityAssessor`.

Sparse data gets degraded output instead of fake high-confidence output.

## PF-08 — Cash-flow recurrence path is better than before

`CashFlowCalculator` now generates/query recurring occurrences for manual rules instead of only ad-hoc recurrence estimation.

---

# Issue P1-01 — Budget forecast refresh can fail because DB unique index conflicts with “deactivate then insert”

## Severity

P1 / High

## Evidence

`BudgetForecastDao.insertWithDeactivation()` deactivates existing active forecasts for:

```text
budgetId + targetPeriodStart + targetPeriodEnd
```

then inserts a new row.

But `BudgetForecast` has a unique index on:

```text
budgetId + targetPeriodStart
```

So a second forecast for the same budget and same period can still violate the DB unique constraint even after the older row is inactive.

## Impact

The first forecast generation may work, but refresh/regenerate for the same active budget period can fail.

User-visible result:

```text
Forecast screen → refresh forecast → failed insert / constraint exception
```

It also contradicts the intended history model: inactive forecasts are supposed to remain for accuracy tracking.

## Fixing strategy

Decide whether forecast history is allowed. Current code says yes, so the unique index must not block inactive historical forecasts.

## Implementation plan

1. Replace unique index:

```kotlin
Index(value = ["budgetId", "targetPeriodStart"], unique = true)
```

with one of:

```text
A. unique active materialized key:
   activeForecastPeriodKey = "$budgetId:$targetPeriodStart:$targetPeriodEnd" only when isActive=1

B. unique composite:
   budgetId + targetPeriodStart + forecastDate
```

2. Migration:

```sql
DROP INDEX IF EXISTS index_budget_forecasts_budgetId_targetPeriodStart;
CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_budgetId_targetPeriodStart_targetPeriodEnd_forecastDate
ON budget_forecasts(budgetId, targetPeriodStart, targetPeriodEnd, forecastDate);
```

3. If Room cannot express partial active unique index, use materialized key.

4. Add tests:

```text
generate_forecast_twice_same_budget_period_keeps_history_and_only_latest_active
insertWithDeactivation_deactivates_old_forecast
latest_active_forecast_returns_newest
```

---

# Issue P1-02 — Forecast rows are persisted with `createdAt = 0` and wrong/default currency

## Severity

P1 / High

## Evidence

`BudgetForecast.createdAt` defaults to `0L`.

`BudgetForecastingEngine.generateForecast()` creates:

```kotlin
BudgetForecast(...)
```

without setting:

```text
createdAt
currency
```

So `createdAt` remains `0L`, and `currency` defaults to `"EUR"` even if home currency is USD/GBP/etc.

## Impact

Forecast ordering, retention, export, debug timelines, and UI currency labels can be wrong.

A USD user can get forecast numbers normalized to USD but persisted as EUR.

## Fixing strategy

Persist forecast metadata explicitly at the forecast boundary.

## Implementation plan

1. In `generateForecast()`:

```kotlin
val forecast = BudgetForecast(
    ...
    currency = homeCurrency,
    createdAt = now
)
```

2. Add integrity diagnostic:

```sql
SELECT * FROM budget_forecasts WHERE createdAt = 0 OR currency IS NULL OR currency = '';
```

3. Add tests:

```text
forecast_sets_createdAt
forecast_currency_equals_home_currency
forecast_not_default_EUR_when_home_USD
```

---

# Issue P1-03 — Budget, forecast, and planned-expense write paths lack restore/write-barrier ownership

## Severity

P1 / High

## Evidence

The following write paths do not visibly check `RestoreMaintenanceMode`:

```text
BudgetRepository.addBudget/updateBudget/deleteBudget/toggleBudget/deleteAll
BudgetForecastingEngine.generateForecast → BudgetForecastDao.insertWithDeactivation
BudgetForecastingEngine.updateForecastAccuracy
PlannedExpenseRepository.add/delete
Budget notification timestamp updates
```

## Impact

Budgets, planned expenses, and forecasts can be mutated while restore is in unsafe mode.

This can corrupt restored state or create mixed pre/post-restore forecast rows.

## Fixing strategy

Introduce a `BudgetLifecycleCoordinator` / `ForecastLifecycleCoordinator` or inject a shared write barrier into repository write methods.

## Implementation plan

1. Add lowest-boundary guard:

```kotlin
private fun checkWritesAllowed() {
    if (!restoreMaintenanceMode.isWritesAllowed()) {
        throw IllegalStateException("Database writes blocked during restore")
    }
}
```

2. Apply to:

```text
budget CRUD
forecast insert/update
planned expense CRUD/status/link
budget notification timestamp updates
```

3. Add lifecycle/diagnostic events:

```text
BUDGET_CREATED
BUDGET_UPDATED
BUDGET_DELETED
FORECAST_CREATED
FORECAST_ACCURACY_UPDATED
PLANNED_EXPENSE_CREATED
PLANNED_EXPENSE_FULFILLED
```

4. Tests:

```text
restore_blocks_add_budget
restore_blocks_update_budget
restore_blocks_forecast_insert
restore_blocks_planned_expense_insert
restore_blocks_budget_notification_timestamp_update
```

---

# Issue P1-04 — Budget alerts still use gross `percentUsed` when adjusted shared spend exists

## Severity

P1 / High

## Evidence

`BudgetViewModel` computes `AdjustedSpendBreakdown` using `SharedExpenseBudgetOffsetEngine`.

`BudgetMonitor.processBudgetStatus()` chooses:

```kotlin
val spent = status.adjustedSpendBreakdown?.effectiveSpend ?: status.spentAmount
```

but then uses:

```kotlin
val percent = status.percentUsed
```

`status.percentUsed` was calculated earlier from gross `spentAmount / effectiveLimit`.

## Impact

Budget alerts can still trigger based on gross spending even when adjusted shared/reimbursed spending is below threshold.

The notification can also be internally inconsistent:

```text
spent displayed = adjusted/net
percent displayed = gross percent
```

## Fixing strategy

If adjusted spend exists, recompute percent/health from adjusted spend.

## Implementation plan

1. Add helper:

```kotlin
fun BudgetStatus.withAdjustedSpendApplied(): BudgetStatus
```

or compute monitor-local:

```kotlin
val effectiveSpent = status.adjustedSpendBreakdown?.effectiveSpend ?: status.spentAmount
val effectivePercent = if (status.effectiveLimit > 0) {
    effectiveSpent / status.effectiveLimit
} else 0.0
```

2. Use `effectivePercent` for:

```text
warning threshold
critical threshold
exceeded threshold
notification content
```

3. Consider storing both:

```text
grossPercentUsed
netPercentUsed
```

4. Tests:

```text
shared_reimbursed_budget_does_not_alert_on_gross_percent
budget_alert_uses_adjusted_percent
notification_spent_and_percent_are_consistent
```

---

# Issue P1-05 — Rollover ignores partial conversion state from prior periods

## Severity

P1 / High for multi-currency budgets with rollover

## Evidence

`BudgetRepository.createBudgetStatus()` initializes:

```kotlin
budgetIsPartial = initialLimitAggregate.isPartial || spentAggregate.isPartial
```

But in the rollover loop it does:

```kotlin
val spentInPeriod = getAggregateSpent(...).displayAmount
```

and ignores:

```text
periodAggregate.isPartial
periodAggregate.warningMessage
periodAggregate.conversionFailures
```

## Impact

A rollover effective limit can be computed from incomplete historical spending but shown as reliable.

Example:

```text
January USD transactions missing FX
February rollover carries false surplus
current budget status shows no warning
```

## Fixing strategy

Carry `MoneyAggregate` quality through rollover computation.

## Implementation plan

1. Change loop:

```kotlin
val periodAggregate = getAggregateSpent(...)
val spentInPeriod = periodAggregate.displayAmount
budgetIsPartial = budgetIsPartial || periodAggregate.isPartial
warnings += periodAggregate.warningMessage
```

2. Add source-bucket/debug data if possible:

```text
rolloverPartialPeriodCount
rolloverFailedTransactionCount
```

3. Tests:

```text
rollover_status_partial_when_prior_period_rate_missing
rollover_warning_includes_prior_period_conversion_failure
rollover_effective_limit_does_not_look_clean_when_history_partial
```

---

# Issue P1-06 — Budget limit conversion uses current/latest rate, not period-specific rate

## Severity

P1 / High for historical/period budget reports

## Evidence

`BudgetRepository.convertBudgetAmountToHomeCurrency()` uses:

```kotlin
currencyConverter.convert(amount, sourceCurrency, homeCurrency)
```

and has a TODO saying period reports should use:

```text
convertAsOf(..., atMillis = periodEnd)
```

`BudgetForecastingEngine` also normalizes `budget.amount` via current conversion.

## Impact

Budget utilization can compare:

```text
expenses converted historically by transaction date
vs budget limit converted at latest/current rate
```

This can cause inconsistent budget percent and forecast risk.

## Fixing strategy

Define budget conversion basis:

```text
current active budget status → latest rate may be acceptable
historical/period reports → period-end or period-start rate
forecast target period → forecastDate or periodStart basis
```

## Implementation plan

1. Add explicit API:

```kotlin
convertBudgetLimitForStatus(budget, periodEnd)
convertBudgetLimitForForecast(budget, forecastDate)
```

2. Use `convertAsOf()` for period-bound reports.

3. Return `MoneyAggregate` with warning if conversion fails.

4. Tests:

```text
historical_budget_status_uses_period_end_rate
forecast_budget_limit_uses_forecast_date_rate
budget_percent_warns_when_limit_conversion_missing
```

---

# Issue P1-07 — Forecast data quality exists but `SynthesisEngine` ignores it

## Severity

P1 / High

## Evidence

`ForecastInputAssembler.ForecastInput` has:

```kotlin
dataQuality: ForecastDataQuality
```

and TODO:

```text
Extend ForecastInput with conversionQuality field so forecast confidence can be reduced when currency normalization is partial.
```

The assembler computes:

```text
isPartial
confidencePenalty
excludedActualCount
excludedPlannedCount
conversionWarnings
```

But `SynthesisEngine.synthesize(input)` only passes:

```text
pastSumDaily
recurringPatterns
plannedExpenses
savingsGoals
budgetStatuses
spendingPace
confirmedOccurrences
```

It does not pass `dataQuality`.

Then confidence is hardcoded around:

```text
0.85 minus no-budget/no-baseline/no-recurring penalties
```

## Impact

Forecast output can show high confidence even if actual expenses were excluded due missing FX rates.

## Fixing strategy

Make forecast confidence include input quality.

## Implementation plan

1. Change `SynthesisEngine.synthesize(input)` to pass `input.dataQuality`.

2. Add parameter:

```kotlin
dataQuality: ForecastDataQuality = ForecastDataQuality()
```

3. Apply:

```kotlin
forecastConfidence -= dataQuality.confidencePenalty
if (dataQuality.isPartial) add insight/warning
```

4. Add warnings to `FinancialForecast` model or metadata.

5. Tests:

```text
forecast_confidence_reduced_when_actual_expenses_excluded
forecast_confidence_reduced_when_planned_conversion_failed
forecast_contains_partial_currency_warning
```

---

# Issue P1-08 — Planned expenses are not actually normalized before forecast arithmetic

## Severity

P1 / High for multi-currency planned expenses

## Evidence

`ForecastInputAssembler.assemble()` calculates:

```kotlin
val normalizedAmount = ...
```

for each planned expense, but then returns:

```kotlin
pe
```

instead of copying the planned expense with normalized amount/currency.

`SynthesisEngine` then groups planned expenses by currency and sums raw amounts anyway:

```kotlin
byCurrency.values.sum()
```

It logs multiple currencies but still adds the raw currency buckets together.

## Impact

Forecast committed/likely/planned totals can raw-sum:

```text
100 EUR + 100 USD = 200 homeCurrency
```

without conversion.

## Fixing strategy

Normalize planned expenses to forecast display/home currency before they enter `SynthesisEngine`.

## Implementation plan

1. Change domain planned mapping:

```kotlin
pe.copy(
    amount = normalizedAmount,
    currency = resolvedHomeCurrency
)
```

2. If conversion fails:
   - exclude from arithmetic,
   - include in `ForecastDataQuality.excludedPlannedCount`,
   - add visible warning.

3. Remove “group by currency then sum raw values” fallback from `SynthesisEngine`.

4. Tests:

```text
planned_expense_USD_converted_to_home_EUR_before_forecast
planned_conversion_failure_excludes_from_forecast_and_reduces_confidence
synthesis_engine_does_not_sum_multiple_raw_currencies
```

---

# Issue P1-09 — Cancelled/skipped planned expenses still enter forecast

## Severity

P1 / High

## Evidence

`ForecastInputAssembler.mapPlannedExpenses()` filters only:

```kotlin
status != "FULFILLED"
```

`SynthesisEngine` also filters only:

```kotlin
it.status != "FULFILLED"
```

So statuses like:

```text
SKIPPED
CANCELLED
```

remain included.

## Impact

Cancelled planned expenses can still reduce discretionary budget and appear in block-party forecasts.

## Fixing strategy

Use explicit active planned statuses.

## Implementation plan

1. Define enum:

```kotlin
enum class PlannedExpenseStatus {
    PLANNED, FULFILLED, SKIPPED, CANCELLED
}
```

2. Forecast input should include only:

```text
PLANNED
```

or maybe `PLANNED + LIKELY` depending model, but not terminal statuses.

3. DAO query should support:

```sql
WHERE status = 'PLANNED'
```

4. Tests:

```text
cancelled_planned_expense_excluded_from_forecast
skipped_planned_expense_excluded_from_forecast
fulfilled_planned_expense_excluded_from_forecast
planned_expense_included
```

---

# Issue P1-10 — Recurring occurrence status is lost before forecast, causing possible double-count/invalid totals

## Severity

P1 / High

## Evidence

`ForecastInputAssembler` queries:

```kotlin
recurringOccurrenceDao.getByDateRange(...)
```

and maps every row to `ConfirmedOccurrence` without filtering status.

It also uses all occurrence keys for planned-expense dedupe.

`ConfirmedOccurrence` does not carry:

```text
status
paidAt
linkedExpenseId
paidAmount
```

`SynthesisEngine` then treats every confirmed occurrence as upcoming committed spending.

## Impact

Occurrences with statuses like:

```text
PAID
SKIPPED
CANCELLED
MISSED
IGNORED
```

can be counted as future committed spending.

Paid occurrences can double-count if the actual payment is already in `pastSumDaily`.

Skipped/cancelled occurrences can appear as obligations.

## Fixing strategy

Forecast must consume typed occurrence status.

## Implementation plan

1. Extend domain model:

```kotlin
data class ConfirmedOccurrence(
    val dueDate: Long,
    val expectedAmount: Double,
    val expectedCurrency: String,
    val merchant: String,
    val categoryId: Long?,
    val status: RecurringOccurrenceStatus,
    val linkedExpenseId: Long?,
    val paidAt: Long?
)
```

2. In assembler:
   - only count `PLANNED` future obligations;
   - exclude `SKIPPED`, `CANCELLED`, `IGNORED`, `MISSED`;
   - exclude `PAID` if actual expense is already in past/current spending;
   - optionally include future-dated paid commitments only if modeled explicitly.

3. Planned-expense dedupe should use only active planned occurrence keys unless the planned row is fulfilled.

4. Tests:

```text
skipped_occurrence_not_in_confirmed_forecast
cancelled_occurrence_not_in_confirmed_forecast
paid_occurrence_not_double_counted_with_actual_expense
planned_expense_dedup_uses_active_occurrence_keys_only
```

---

# Issue P1-11 — Cash-flow calendar raw-sums multi-currency amounts

## Severity

P1 / High for multi-currency users

## Evidence

`CashFlowCalculator` sums:

```kotlin
dayIncome += inc.effectiveAmount
dayExpensesTotal += exp.effectiveAmount
dayExpensesTotal += recurring.averageAmount
```

The comment says this is safe because single-day expenses are “almost always same-currency” and callers should normalize before calling.

But `CashFlowCalendarViewModel` calls `calculateDailyCashFlow()` directly and does not normalize first.

`DailyCashFlow.currency` defaults to empty string and is not populated.

## Impact

Cash-flow balances can raw-sum different currencies.

Example:

```text
+100 USD income - 100 EUR expense = 0
```

displayed in home currency context even though not converted.

## Fixing strategy

Cash-flow calculator must own normalization or require a typed normalized input.

## Implementation plan

1. Inject `AnalyticsCurrencyNormalizer` and `CurrencySettingsRepository` into `CashFlowCalculator`.

2. Normalize actual income/expenses to home currency before summing.

3. Convert recurring patterns/occurrences to home currency before summing.

4. Set:

```kotlin
DailyCashFlow.currency = homeCurrency
```

5. Add `CashFlowDataQuality`:

```text
isPartial
excludedCount
warnings
```

6. Tests:

```text
cashflow_converts_USD_income_to_EUR_home_currency
cashflow_converts_recurring_USD_to_home_currency
cashflow_partial_when_rate_missing
daily_cashflow_currency_is_home_currency
```

---

# Issue P1-12 — Cash-flow output displays pre-dedup recurring predictions

## Severity

P1/P2

## Evidence

`CashFlowCalculator` creates:

```kotlin
val deduplicatedPredicted = predictedRecurringList.filterNot { ... }
```

and uses it for balance math.

But the returned `DailyCashFlow` stores:

```kotlin
predictedRecurring = predictedRecurringList
```

not `deduplicatedPredicted`.

## Impact

The balance may correctly omit a predicted recurring bill, but the UI can still show the bill as predicted for that day.

User sees:

```text
Netflix actual payment exists
Netflix predicted bill still shown
balance excludes it
```

## Fixing strategy

Return the same deduped list that was used for arithmetic, plus optionally expose omitted predictions separately for debugging.

## Implementation plan

1. Change:

```kotlin
predictedRecurring = deduplicatedPredicted
```

2. Add debug field if useful:

```kotlin
dedupedRecurring: List<RecurringPattern>
```

3. Tests:

```text
cashflow_output_does_not_show_deduped_prediction
cashflow_balance_and_display_use_same_prediction_list
```

---

# Issue P1-13 — Stress forecast is still not a real account-balance forecast

## Severity

P1 / High

## Evidence

`FinancialStressForecastEngine` has TODO:

```text
Expose StressForecastMode and label output as stress index, not cash balance forecast.
```

It says no canonical account-balance source exists.

`resolveStartingBalanceBaseline()` returns:

```text
recentDeposits - recentExpenses over 90 days
```

using normalized aggregate totals, not actual account balance.

The comment also says the fallback is floored at `0.0`, but implementation returns `netCashflow` directly.

## Impact

`projectedBalance` can be presented as a cash balance even though it is only a 90-day net-cashflow estimate.

That can materially mislead users.

## Fixing strategy

Do not label this as cash balance until an `AccountBalanceProvider` exists.

## Implementation plan

1. Add:

```kotlin
enum class StressForecastMode {
    NEUTRAL_BASELINE,
    USER_ENTERED_BALANCE,
    BANK_BALANCE,
    NET_CASHFLOW_ESTIMATE
}
```

2. Add output field:

```kotlin
baselineMode
baselineAmount
baselineWarning
```

3. UI labels:

```text
Stress index estimate
Projected balance estimate based on recent cashflow
```

4. Implement `AccountBalanceProvider` later:

```text
ManualBalanceProvider
BankConnectionBalanceProvider
NetCashflowEstimator
```

5. Tests:

```text
stress_forecast_labels_net_cashflow_baseline
net_cashflow_baseline_warning_visible
manual_balance_provider_overrides_estimate
```

---

# Issue P1-14 — Stress forecast counts `PAID` recurring occurrences as active outflows

## Severity

P1 / High

## Evidence

`FinancialStressForecastEngine.ACTIVE_OCCURRENCE_STATUSES` includes:

```kotlin
setOf("PLANNED", "PAID")
```

`calculateRecurringOutflows()` sums PAID occurrence `paidAmount`/`paidCurrency`.

## Impact

If a bill has already been paid and the actual expense is in historical/current spending, stress forecast can subtract it again as a future outflow.

## Fixing strategy

For forward-looking horizons, default active obligations should be:

```text
PLANNED only
```

PAID should be excluded unless the model is explicitly doing retrospective accounting.

## Implementation plan

1. Change active statuses:

```kotlin
private val ACTIVE_OCCURRENCE_STATUSES = setOf("PLANNED")
```

2. If needed, include PAID only when:

```text
paidAt is in the future
```

which likely should not occur for real actual expenses.

3. Tests:

```text
paid_occurrence_not_counted_as_future_stress_outflow
planned_occurrence_counted_as_future_stress_outflow
paid_actual_expense_not_double_subtracted
```

---

# Issue P1-15 — Deleting a budget can fail after forecasts exist

## Severity

P1 / High

## Evidence

`BudgetForecast` FK:

```kotlin
ForeignKey(... onDelete = ForeignKey.RESTRICT)
```

`BudgetRepository.deleteBudget()` simply calls:

```kotlin
budgetDao.delete(budget)
```

It does not archive/delete forecasts first or show a specific error.

## Impact

A user can create a forecast for a budget, then be unable to delete that budget.

The error message is generic:

```text
Failed to delete budget
```

## Fixing strategy

Define budget deletion semantics with forecast history.

Options:

```text
A. Archive budget, keep forecasts.
B. Delete forecasts first, then delete budget.
C. Restrict delete but show explicit “budget has forecast history” UI.
```

## Implementation plan

1. Add `isArchived` to Budget, or a delete policy:

```kotlin
deleteBudget(budgetId, policy = ARCHIVE_WITH_HISTORY)
```

2. If hard delete:

```kotlin
database.withTransaction {
    budgetForecastDao.deleteForecastsForBudget(budgetId)
    budgetDao.delete(budget)
}
```

3. Add user-visible error if restricted.

4. Tests:

```text
delete_budget_with_forecasts_archives_budget
delete_budget_with_forecasts_policy_delete_history_removes_forecasts
delete_budget_restrict_message_is_specific
```

---

# Issue P2-16 — PlannedExpenseRepository bypasses invariants and ignores insert conflicts

## Severity

P2 / Medium, P1 if used for user-facing planning

## Evidence

`PlannedExpenseDao.insertPlannedExpense()` uses `OnConflictStrategy.IGNORE`.

`PlannedExpenseRepository.addPlannedExpense()` returns the raw insert ID without checking if it is `0`.

`PlannedExpense.createdAt` and `updatedAt` default to `0L`.

Repository writes do not set timestamps, materialized key, or restore guard.

## Impact

A duplicate planned expense can be silently skipped while caller thinks it was inserted.

Rows can have:

```text
createdAt = 0
updatedAt = 0
openSourceOccurrenceKey = null despite PLANNED sourceOccurrenceKey
```

## Fixing strategy

Make planned expense writes lifecycle-owned.

## Implementation plan

1. Add `PlannedExpenseLifecycleCoordinator`.

2. On insert:
   - check restore,
   - set createdAt/updatedAt,
   - set openSourceOccurrenceKey,
   - check insert result.

3. Return sealed result:

```kotlin
Inserted(id)
Duplicate(existingId)
ValidationFailed(errors)
```

4. Tests:

```text
planned_insert_sets_timestamps
planned_insert_sets_openSourceOccurrenceKey
planned_insert_conflict_returns_duplicate_not_zero
restore_blocks_planned_insert
```

---

# Issue P2-17 — Budget suggestions still hardcode euro symbol

## Severity

P2 / Medium

## Evidence

`BudgetRepository.getSuggestions()` builds reason:

```kotlin
"Based on your €${...} monthly average spend."
```

even though spending was computed through home-currency aggregation.

## Impact

USD/GBP/etc. users see incorrect currency label.

## Fixing strategy

Use home currency formatter.

## Implementation plan

1. Resolve home currency once in suggestions.

2. Use:

```kotlin
CurrencyFormatter.format(monthlyAvg, homeCurrency)
```

3. Tests:

```text
budget_suggestion_reason_uses_home_currency_USD
budget_suggestion_reason_uses_home_currency_GBP
```

---

# Issue P2-18 — Budget invalidation trigger uses deprecated raw total query

## Severity

P2 / Medium

## Evidence

`BudgetRepository.getBudgetStatuses()` uses:

```kotlin
expenseDao.getTotalSpentFlow().map { }
```

only as an invalidation trigger.

Even if value is discarded, the DAO query is still a raw aggregate query.

## Impact

This keeps deprecated raw-money paths alive and can perform unnecessary aggregate work just to trigger invalidation.

## Fixing strategy

Add a cheap invalidation-only query.

## Implementation plan

1. Add DAO:

```kotlin
@Query("SELECT MAX(updatedAt) FROM expenses")
fun observeExpenseMutationClock(): Flow<Long?>
```

or:

```kotlin
@Query("SELECT COUNT(*) FROM expenses")
fun observeExpenseCountForInvalidation(): Flow<Int>
```

2. Replace `getTotalSpentFlow()` trigger.

3. Static guard should allow no raw aggregate usage in production.

4. Tests:

```text
budget_status_recomputes_when_expense_inserted
budget_status_recomputes_when_expense_updated
budget_status_does_not_call_raw_total_query
```

---

# Issue P2-19 — Autopilot apply-all transaction does not actually rollback on per-budget failure

## Severity

P2 / Medium

## Evidence

`BudgetViewModel.applyAllAutopilotRecommendations()` wraps the loop in:

```kotlin
database.withTransaction { ... }
```

but calls:

```kotlin
budgetRepository.updateBudget(updatedBudget)
```

`updateBudget()` catches exceptions and returns `Result.Error` instead of throwing.

Inside the transaction, the ViewModel logs the error but continues.

## Impact

The transaction may commit partial updates despite comment saying all updates succeed/fail atomically.

## Fixing strategy

Transaction participants must throw on failure or use DAO/coordinator methods that propagate exceptions.

## Implementation plan

1. Add repository method:

```kotlin
suspend fun updateBudgetOrThrow(budget: Budget)
```

2. In apply-all:

```kotlin
database.withTransaction {
    for (...) budgetRepository.updateBudgetOrThrow(updatedBudget)
}
```

3. Only clear recommendations after successful transaction.

4. Tests:

```text
autopilot_apply_all_rolls_back_when_one_update_fails
autopilot_recommendations_not_cleared_on_failure
autopilot_apply_all_commits_all_on_success
```

---

# Issue P2-20 — Budget monitor has no durable diagnostic ledger

## Severity

P2 / Medium

## Evidence

`BudgetMonitor` logs retries/failures with `Timber`, but there is no durable budget-monitor event table.

Missing durable outcomes:

```text
CHECK_STARTED
CHECK_SKIPPED_THROTTLE
STATUS_COMPUTED
ALERT_ELIGIBLE
ALERT_SENT
ALERT_BLOCKED_PERMISSION
ALERT_FAILED
CHECK_FAILED
```

## Impact

If a user says “I crossed budget but got no alert,” debugging still requires logs.

## Fixing strategy

Add monitor diagnostic events.

## Implementation plan

1. Add entity:

```kotlin
BudgetMonitorEvent(
    id,
    budgetId,
    periodStart,
    periodEnd,
    stage,
    outcome,
    percentUsed,
    spent,
    limit,
    timestamp,
    message
)
```

2. Write event for every alert decision.

3. Tests:

```text
budget_alert_sent_writes_event
budget_alert_blocked_permission_writes_event
budget_check_throttled_writes_debug_event_if_debug_enabled
```

---

# Recommended fixing order

## PR 1 — Forecast persistence hardening

Files:

```text
BudgetForecast.kt
BudgetForecastDao.kt
BudgetForecastingEngine.kt
Room migration
```

Fix:

```text
- remove/replace conflicting unique index
- set createdAt
- set forecast currency
- add repeated forecast generation tests
```

## PR 2 — Restore/write barrier for budget/planned/forecast writes

Files:

```text
BudgetRepository.kt
BudgetForecastingEngine.kt
PlannedExpenseRepository.kt
PlannedExpenseDao.kt
```

Fix:

```text
- restore guard
- timestamp guarantees
- insert conflict handling
```

## PR 3 — Budget alert net-spend correctness

Files:

```text
BudgetMonitor.kt
BudgetStatus/BudgetModels.kt
BudgetViewModel.kt
```

Fix:

```text
- recompute percent/health from adjusted spend
- notification content uses same spend/percent basis
```

## PR 4 — Forecast input status + planned-currency hardening

Files:

```text
ForecastInputAssembler.kt
SynthesisEngine.kt
ConfirmedOccurrence model
PlannedExpense model
```

Fix:

```text
- normalize planned expenses
- exclude CANCELLED/SKIPPED/FULFILLED planned rows
- carry recurring occurrence status
- exclude paid/skipped/cancelled occurrences from future commitments
```

## PR 5 — Forecast confidence quality propagation

Files:

```text
ForecastInputAssembler.kt
SynthesisEngine.kt
FinancialForecast model/UI
```

Fix:

```text
- apply ForecastDataQuality.confidencePenalty
- surface partial forecast warnings
```

## PR 6 — Cash-flow currency normalization

Files:

```text
CashFlowCalculator.kt
CashFlowCalendarViewModel.kt
DailyCashFlow model
```

Fix:

```text
- normalize actual income/expenses
- normalize recurring predictions
- set currency
- return deduped predicted list
```

## PR 7 — Stress forecast relabel/balance source

Files:

```text
FinancialStressForecastEngine.kt
StressForecastResult model
UI stress forecast cards
```

Fix:

```text
- introduce StressForecastMode
- stop presenting net-cashflow estimate as real balance
- exclude PAID occurrences from future obligations
```

## PR 8 — Budget deletion semantics

Files:

```text
BudgetRepository.kt
BudgetForecastDao.kt
BudgetViewModel.kt
Budget UI
```

Fix:

```text
- archive budget or delete forecasts transactionally
- explicit user-facing error
```

## PR 9 — Budget rollover quality propagation

Files:

```text
BudgetRepository.kt
BudgetStatus model
```

Fix:

```text
- propagate prior-period partial conversion warnings
- maybe add rollover ledger later
```

## PR 10 — Diagnostics and guardrails

Files:

```text
BudgetMonitor.kt
new BudgetMonitorEvent.kt/Dao
currency/budget guard scripts
```

Fix:

```text
- durable alert diagnostics
- static guard against raw budget/cashflow sums
```

---

# Golden tests to add

```text
generate_forecast_twice_same_period_keeps_latest_active_and_history
forecast_sets_createdAt_and_home_currency
restore_blocks_budget_crud
restore_blocks_forecast_insert
restore_blocks_planned_expense_insert
budget_alert_uses_adjusted_shared_spend_percent
rollover_prior_period_missing_rate_marks_status_partial
budget_limit_historical_conversion_uses_period_basis
planned_expense_forecast_converts_to_home_currency
cancelled_planned_expense_excluded_from_forecast
skipped_planned_expense_excluded_from_forecast
paid_recurring_occurrence_not_counted_as_future_commitment
skipped_recurring_occurrence_not_counted_as_future_commitment
forecast_confidence_reduced_when_currency_data_partial
cashflow_converts_actual_income_and_expense_to_home_currency
cashflow_converts_recurring_prediction_to_home_currency
cashflow_display_uses_deduped_predicted_recurring
stress_forecast_excludes_paid_occurrences_from_future_outflows
stress_forecast_labels_net_cashflow_baseline_as_estimate
delete_budget_with_forecast_history_has_explicit_policy
autopilot_apply_all_rolls_back_on_failure
budget_suggestion_uses_home_currency_symbol
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "BudgetForecast(" app/src/main/java
grep -R "insertWithDeactivation" app/src/main/java
grep -R "budgetForecastDao.insert" app/src/main/java
grep -R "PlannedExpense(" app/src/main/java
grep -R "insertPlannedExpense" app/src/main/java
grep -R "status != \"FULFILLED\"" app/src/main/java
grep -R "confirmedOccurrences" app/src/main/java
grep -R "getByDateRange" app/src/main/java/com/yourname/expensetracker/domain/forecasting
grep -R "effectiveAmount" app/src/main/java/com/yourname/expensetracker/domain/cashflow
grep -R "predictedRecurring = predictedRecurringList" app/src/main/java
grep -R "€" app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt
grep -R "getTotalSpentFlow" app/src/main/java
```

Allowed raw-money use should be explicit:

```text
- source bucket creation before MoneyAggregate conversion
- already-normalized ExpenseSnapshot lists
- single-currency unit tests
```

Everything else should use:

```text
MoneyAggregate
AnalyticsCurrencyNormalizer
ForecastInputAssembler normalized input
MultiCurrencyRepository historical/current APIs with explicit naming
```

---

# Definition of done

```text
- Forecast regeneration for the same budget period succeeds and keeps history.
- BudgetForecast rows never persist createdAt=0 or wrong default currency.
- Budget/planned/forecast writes respect restore maintenance mode.
- Budget alerts use adjusted/net shared spend consistently.
- Rollover carries partial conversion warnings from prior periods.
- Budget limit conversion basis is explicit and tested.
- Planned expenses are normalized before forecast arithmetic.
- CANCELLED/SKIPPED/FULFILLED planned expenses do not affect forecast.
- Recurring occurrence status is carried into forecast and terminal statuses are excluded.
- Forecast confidence is reduced when input currency conversion is partial.
- Cash-flow calendar normalizes actual and recurring amounts to home currency.
- Cash-flow display and balance use the same deduped predicted-recurring list.
- Stress forecast is labelled as estimate/stress index unless backed by real account balance.
- PAID recurring occurrences are not counted as future stress outflows.
- Budget deletion has explicit archive/delete-history/restrict semantics.
- Autopilot apply-all truly rolls back on failure.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `BudgetRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `BudgetDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetDao.kt

- `Budget.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

- `BudgetCalculator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt

- `BudgetForecastingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt

- `BudgetForecast.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt

- `BudgetForecastDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt

- `BudgetMonitor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt

- `BudgetModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetModels.kt

- `BudgetViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt

- `BudgetForecastingViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt

- `PlannedExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/PlannedExpenseRepository.kt

- `PlannedExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `PlannedExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- `SynthesisEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt

- `MonteCarloSpendingSimulator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt

- `DataQualityAssessor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt

- `FinancialStressForecastEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt

- `CashFlowCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `CashFlowCalendarViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModel.kt