# Review: Forecasting / Cash Flow / Financial Weather Analysis

**Review date**: 2026-05-02
**Source analysis**: `docs/analyses and debug master/forecasting-cashflow-weather-analysis.md`
**Branch**: current worktree (master-refactor)
**Reviewer**: Automated Code Reviewer

---

## VERDICT: FAIL

Substantial progress has been made on several issues (notably the rainy emoji, occurrence lifecycle infrastructure, and the upcoming bills range helper), but multiple **critical** issues remain unresolved. The three most impactful unresolved issues are:

1. **Issue 1 (CRITICAL)**: Recurring patterns still counted only once per rest-of-month forecast (under-counts weekly/biweekly bills by factor of 2–5).
2. **Issue 4 (CRITICAL)**: Monte Carlo still double-counts recurring spend — historical distribution includes recurring merchants, and `knownUpcoming` adds them again.
3. **Issue 11 (CRITICAL)**: `CashFlowCalculator` daily prediction still only checks `nextExpectedDate` per pattern, missing all future occurrences beyond the first.

---

## Issue-by-Issue Status

### Issue 1 — Main month forecast counts each recurring pattern only once

**Status**: **STILL PRESENT**

**Evidence**: `SynthesisEngine.synthesizeInternal()` lines 144–146 and 156–158 both filter by:
```kotlin
it.nextExpectedDate >= startOfToday && it.nextExpectedDate < endOfMonthExclusive
```
and sum `it.averageAmount` *once*. For a €10/weekly bill with 4 occurrences remaining, this counts €10 instead of €40.

**What changed**: A `RecurringOccurrenceExpander` exists at `domain/recurring/RecurringOccurrenceExpander.kt` and `RecurringLifecycleCoordinator` uses it — but `SynthesisEngine.synthesizeInternal()` does **not** use either. Only `buildRecurringByDayFromOccurrences()` (Block Party) and `FinancialStressForecastEngine.calculateRecurringOutflows()` use the canonical occurrence path.

**Fix needed**: `synthesizeInternal()` must either:
- Call `RecurringLifecycleCoordinator.generateOccurrences()` for each manual rule and sum `expectedAmount` of occurrences in range, or
- Use `RecurringOccurrenceExpander.expand()` directly for detected patterns.

---

### Issue 2 — Block Party recurrence logic can mark days before nextExpectedDate

**Status**: **PARTIALLY RESOLVED**

**What changed**: When `recurringOccurrenceDao` is injected (non-null), `buildRecurringByDayFromOccurrences()` is used — this queries materialized occurrences (PAID + PLANNED) from the DB, which are correctly date-bounded. This avoids the bug for manual rules.

**What remains broken**:
- **Detected-only patterns** (id==null): still use `isRecurringExpected()` fallback at line 582, which has no `date >= nextExpectedDate` guard.
- **Manual rules with zero occurrence rows**: fall back to `isRecurringExpected()` at line 566, same problem.
- **`isRecurringExpected()` itself** (lines 450–508): for WEEKLY, checks only day-of-week; for BIWEEKLY, uses `floorMod` which can match dates before anchor. No guard `date >= pattern.nextExpectedDate.startOfDay()`.

---

### Issue 3 — Block Party monthly recurring total uses monthly equivalent, day spikes use actual occurrences

**Status**: **STILL PRESENT**

**Evidence**: `SynthesisEngine.calculateBlockPartyData()` lines 317–322:
```kotlin
val totalMonthlyRecurring = components.recurringExpenses.sumOf { pattern ->
    when (pattern.frequency) {
        RecurrenceFrequency.IRREGULAR -> 0.0
        else -> RecurrenceCalculator.toMonthlyAmount(pattern.averageAmount, pattern.frequency)
    }
}
```
This uses `4.33` multiplier for weekly bills. Meanwhile daily spikes (from `recurringByDay`) use actual occurrence counts. For a 5-Monday month, monthly equivalent ≈ €43.30 but daily spikes sum to €50 — inconsistent day targets.

**Fix**: Sum actual occurrences for the month instead of using `toMonthlyAmount`.

---

### Issue 4 — Monte Carlo likely double-counts recurring spend

**Status**: **STILL PRESENT**

**Evidence**: 
- `HistoricalSpendingDistribution.computeDistribution()` lines 78–82: filters for PURCHASE + WITHDRAWAL without removing recurring merchants.
- `MonteCarloSpendingSimulator.simulate()` line 95: `spentToDate + knownUpcoming + sampledDiscretionary` where `sampledDiscretionary` is drawn from a distribution that includes recurring spend.

`FinancialStressForecastEngine` has the correct pattern (lines 393–406 — it filters out recurring merchants from discretionary purchases). But the main `MonteCarloSpendingSimulator` does not.

**Fix**: Build a discretionary-only historical distribution, or filter recurring merchant keys from the historical data before fitting the log-normal distribution.

---

### Issue 5 — Dashboard forecast and financial-weather forecast use different data scopes

**Status**: **STILL PRESENT**

**Evidence**:
- `FinancialWeatherRepository.getFinancialWeather()` line 41: uses `expenseRepository.getAllExpenses()` — **full history**.
- `DashboardContractsAdapter.observeDashboardExpenses()` lines 54–60: queries `getExpensesWithCategoryInPeriod(monthStart, monthEnd)` — **current month only**.
- `ComputeDashboardWidgetsUseCase.computeRunwayAndForecast()` lines 344–374: converts current-month dashboard expenses to snapshots → feeds `forecastInputAssembler.assemble()` → feeds `synthesisEngine.synthesize()`.

Result: Financial Weather uses full-history spending pace while Runway/Monte Carlo/Block Party use current-month-only data. They **will disagree**.

**Fix**: Create a dedicated forecast data source that avoids the current-month dashboard stream for forecasting.

---

### Issue 6 — Confirmed recurring patterns used; detected patterns ignored

**Status**: **STILL PRESENT**

**Evidence**: 
- `FinancialWeatherRepository.getFinancialWeather()` line 48: `mergedRecurringPatternsProvider.getConfirmedPatterns(manualRecurring = recurringEntities)` — returns only confirmed/manual patterns.
- `CalculateFinancialForecastUseCase.synthesizeForecast()` line 59: same call.

`getAllRecurringPatterns()` exists (lines 126–136) and merges detected patterns, but it is **not used** to feed the main weather forecast.

Additionally, `assemble()` is called with `manualRecurringEntities = emptyList()` (line 53), which means **no** manual rules have their occurrences generated via `RecurringLifecycleCoordinator` for the weather forecast path — only confirmed patterns flow through as `detectedRecurringPatterns`.

---

### Issue 7 — Planned expenses can double-count recurring/subscription obligations

**Status**: **PARTIALLY RESOLVED**

**What changed (positive)**:
- `PlannedExpense` entity now has: `sourceOccurrenceKey`, `sourceRecurringRuleId`, `status`, `linkedActualExpenseId`, `merchantKey`, `openSourceOccurrenceKey`.
- `ForecastInputAssembler.assemble()` lines 337–352 cross-deduplicates planned expenses against materialized occurrences via `sourceOccurrenceKey`.
- `RecurringPlanProjectionService` generates planned expenses with `sourceOccurrenceKey` and checks for existing matches before insertion.

**What remains**:
- `SynthesisEngine.synthesizeInternal()` still counts `recurringPatterns` and `plannedExpenses` independently (lines 143–176). A planned expense derived from a recurring rule may have been deduplicated against materialized occurrences, but the underlying `recurringPattern` entry still flows into both `committedUpcomingBills` and `likelyUpcomingBills`. The cross-deduplication only prevents double-counting between planned expenses and occurrences, **not** between recurring patterns and planned expenses.
- The planned expense's `status` field is not checked by SynthesisEngine — FULFILLED/PAID planned expenses could still be counted.

---

### Issue 8 — Forecast money is raw `Double` with no currency

**Status**: **STILL PRESENT**

**Evidence**: `FinancialForecast`, `ForecastComponents`, `FinancialWeather`, `MonteCarloResult`, `StressHorizon` all use raw `Double` for amounts.

**Mitigation present**: `AnalyticsCurrencyNormalizer` normalizes all input to home currency before entering the forecast pipeline. `MonteCarloResult` and `StressHorizon` have a `displayCurrency` field to communicate which currency the amounts represent.

**Remaining risk**: If normalization fails or is bypassed (e.g., `ComputeDashboardWidgetsUseCase` manually constructs `ExpenseSnapshot` at lines 344–365 without normalization), mixed-currency raw summation can occur silently.

---

### Issue 9 — Financial Stress Forecast uses `currentBalance = 0.0`

**Status**: **STILL PRESENT**

**Evidence**: `FinancialStressForecastEngine.resolveStartingBalanceBaseline()` line 584–586 still returns `0.0`. Comment says: "Use a neutral baseline instead of fabricating a balance from cashflow."

The widget label has **not** been renamed to "cash-flow pressure" as suggested. The fix recommendation (either integrate real balance or rename) is not implemented.

---

### Issue 10 — Stress forecast income timing is too simple

**Status**: **STILL PRESENT**

**Evidence**: `FinancialStressForecastEngine.estimateIncome()` lines 365–378: still averages deposits over 90 days and scales linearly. No payday detection, no recurring income pattern matching, no expected-date-based income projection.

---

### Issue 11 — CashFlowCalculator only includes the next recurring occurrence

**Status**: **STILL PRESENT**

**Evidence**: `CashFlowCalculator.calculateDailyCashFlow()` lines 119–128:
```kotlin
for (pattern in recurringPatterns) {
    val expectedDayStart = TimePeriodUtils.getStartOfDay(pattern.nextExpectedDate)
    if (expectedDayStart >= currentDayStart && expectedDayStart < currentDayEnd) {
        predictedRecurringList.add(pattern)
    }
}
```
Only `nextExpectedDate` is checked per pattern. For a 90-day cash flow, weekly/biweekly/monthly bills after the first occurrence are missing.

**Note**: `getUpcomingBills()` (lines 177–217) **has** been updated to use the canonical occurrence path — this is a positive fix for that specific method. But `calculateDailyCashFlow()` was not similarly updated.

---

### Issue 12 — CashFlowCalculator double-counts actual and predicted recurring on the same day

**Status**: **STILL PRESENT**

**Evidence**: `calculateDailyCashFlow()` lines 131–148 add historical expenses (`dayExpensesTotal += exp.effectiveAmount`) and predicted recurring (`dayExpensesTotal += recurring.averageAmount`) independently. No deduplication by merchant, date, or amount.

---

### Issue 13 — `getUpcomingBills` uses suspicious negative range helper

**Status**: **RESOLVED**

**Evidence**: `CashFlowCalculator.getUpcomingBills()` line 181 now uses:
```kotlin
val endDate = TimePeriodUtils.addDays(startOfToday, daysAhead + 1)
```
Clean, direct, correct.

---

### Issue 14 — Forecast confidence is too optimistic and disconnected from data quality

**Status**: **STILL PRESENT**

**Evidence**: `SynthesisEngine.synthesizeInternal()` lines 276–280:
```kotlin
var forecastConfidence = 0.85
if (budgetLimit <= 0) forecastConfidence -= 0.15
if (spendingPace.averageMonthlyTotal == null) forecastConfidence -= 0.10
if (recurringPatterns.isEmpty()) forecastConfidence -= 0.05
```
No integration with `DataQualityAssessor`. No consideration of sparse data, currency issues, detection confidence, stale data, or distribution quality.

---

### Issue 15 — Monte Carlo recency scoring overstates quality

**Status**: **STILL PRESENT**

**Evidence**: `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` lines 241–256: counts weeks with `total > 0.0`, not weeks that passed the 3-distinct-transaction-days quality filter. The code comment acknowledges this limitation.

---

### Issue 16 — Historical distribution excludes quiet weeks, overstating future spend

**Status**: **STILL PRESENT**

**Evidence**: `HistoricalSpendingDistribution.computeDistribution()` line 96: `MIN_TRANSACTION_DAYS_PER_WEEK = 3`. True zero-spend weeks are excluded alongside incomplete-data weeks, biasing the distribution upward for naturally low-spend users.

---

### Issue 17 — Forecast fallback can hide serious failures

**Status**: **STILL PRESENT**

**Evidence**: `SynthesisEngine.synthesize()` lines 97–121 catches all exceptions and returns a zeroed/degraded forecast. `FinancialWeatherRepository.getFinancialWeather()` lines 92–105 does the same. No diagnostic logging beyond `Timber.e()`. No structured diagnostics (input counts, exception categories, missing data reasons, etc.).

---

### Issue 18 — Rainy weather icon appears malformed

**Status**: **RESOLVED**

**Evidence**: `NarrativeGenerator.kt` line 38 now uses `"🌧️"` (valid cloud-with-rain emoji).

---

## New Issues Found (Not in Original Analysis)

### [NEW-1] [MAJOR] Weather forecast path does not generate occurrences for manual rules — `ForecastInputAssembler.kt` / `FinancialWeatherRepository.kt`

`FinancialWeatherRepository.getFinancialWeather()` passes `manualRecurringEntities = emptyList()` to `assemble()` (line 53). This means the occurrence-generation loop at `ForecastInputAssembler.assemble()` lines 323–335 does **nothing** — no manual recurring rules have their occurrences materialized for the weather forecast path. The confirmed recurring patterns are passed as `detectedRecurringPatterns` instead (line 54), which bypasses occurrence generation entirely. The weather forecast uses only `nextExpectedDate` from the pattern, not the expanded occurrences.

**Fix**: Pass the actual `manualRecurringEntities` to `assemble()` so occurrences are generated, or query occurrences separately and sum their expected amounts.

---

### [NEW-2] [MAJOR] Dashboard forecast bypasses `AnalyticsCurrencyNormalizer` — `ComputeDashboardWidgetsUseCase.kt`

`ComputeDashboardWidgetsUseCase.computeRunwayAndForecast()` lines 344–365 manually constructs `ExpenseSnapshot` objects from `DashboardExpense` using `expense.effectiveAmount` directly, without running them through `AnalyticsCurrencyNormalizer`. This bypasses multi-currency normalization and may cause mixed-currency raw summation in the dashboard runway/monte-carlo/block-party paths.

Compare with `ForecastInputAssembler.assemble()` line 317 which normalizes expenses before use.

**Fix**: Route dashboard expenses through `ForecastInputAssembler.mapExpenseSnapshots()` and then through `AnalyticsCurrencyNormalizer`, or use `forecastInputAssembler.assemble()` directly with properly-mapped data.

---

### [NEW-3] [MEDIUM] Inconsistent `merchantKey` fallback between dashboard and assembler paths — `ComputeDashboardWidgetsUseCase.kt` vs `ForecastInputAssembler.kt`

- `ComputeDashboardWidgetsUseCase` line 351: `MerchantKeyGenerator.generate(expense.merchant).ifBlank { null }` — yields `null` for blank keys.
- `ForecastInputAssembler` line 368–372: `MerchantKeyGenerator.generate(merchantName).takeIf { it.isNotBlank() } ?: merchantName.lowercase().trim()` — yields a fallback.

This can cause matching inconsistencies when the same expense flows through both paths.

---

### [NEW-4] [MEDIUM] `SynthesisEngine` does not check `PlannedExpense.status` — `SynthesisEngine.kt`

`SynthesisEngine.synthesizeInternal()` counts planned expenses at lines 148–150 and 160–162 without checking whether the planned expense has been FULFILLED/PAID (status field). The domain model `PlannedExpense` does not have a `status` field (only `sourceOccurrenceKey`). The database entity has `status`, `linkedActualExpenseId`, etc., but these are lost during mapping in `ForecastInputAssembler.mapPlannedExpenses()` (lines 100–117) — only `sourceOccurrenceKey` is carried forward.

**Fix**: Add `status` to the domain `PlannedExpense` model and filter out FULFILLED/PAID planned expenses in SynthesisEngine.

---

### [NEW-5] [MINOR] `HistoricalSpendingDistribution` has dead code — `HistoricalSpendingDistribution.kt`

The `toDomain()` method at lines 210–217 maps data-layer `TransactionType` to domain `DomainTransactionType`, but it is never called. The expenses are already normalized to `ExpenseSnapshot` by `AnalyticsCurrencyNormalizer`, and the filtering at lines 78–82 uses `DomainTransactionType` directly from the snapshot.

**Fix**: Remove dead code.

---

## Summary Table

| # | Issue | Severity | Status |
|---|-------|----------|--------|
| 1 | Recurring only counted once | CRITICAL | **STILL PRESENT** |
| 2 | Block Party marks days before nextExpectedDate | HIGH | **PARTIALLY RESOLVED** |
| 3 | Block Party monthly equivalent vs actual spikes | HIGH | **STILL PRESENT** |
| 4 | Monte Carlo double-counts recurring | CRITICAL | **STILL PRESENT** |
| 5 | Different data scopes (weather vs dashboard) | HIGH | **STILL PRESENT** |
| 6 | Detected patterns ignored | HIGH | **STILL PRESENT** |
| 7 | Planned/Recurring/Actual double-count | CRITICAL | **PARTIALLY RESOLVED** |
| 8 | Raw Double, no currency | CRITICAL | **STILL PRESENT** |
| 9 | Stress forecast balance = 0.0 | CRITICAL | **STILL PRESENT** |
| 10 | Stress income timing too simple | HIGH | **STILL PRESENT** |
| 11 | CashFlow only next occurrence | CRITICAL | **STILL PRESENT** |
| 12 | CashFlow double-counts actual+predicted | HIGH | **STILL PRESENT** |
| 13 | getUpcomingBills range helper | MED/HIGH | **RESOLVED** |
| 14 | Forecast confidence too optimistic | HIGH | **STILL PRESENT** |
| 15 | Monte Carlo recency overstates quality | MEDIUM | **STILL PRESENT** |
| 16 | Quiet weeks excluded from distribution | MED/HIGH | **STILL PRESENT** |
| 17 | Fallback hides failures | MEDIUM | **STILL PRESENT** |
| 18 | Rainy icon malformed | LOW | **RESOLVED** |
| NEW-1 | Weather path skips occurrence generation | MAJOR | **NEW** |
| NEW-2 | Dashboard bypasses currency normalizer | MAJOR | **NEW** |
| NEW-3 | Inconsistent merchantKey fallback | MEDIUM | **NEW** |
| NEW-4 | PlannedExpense status not checked | MEDIUM | **NEW** |
| NEW-5 | Dead code in HistoricalSpendingDistribution | MINOR | **NEW** |

---

## Coverage

- **Requirements met**: No — the original analysis identified 18 issues; only 2 are fully resolved and 2 are partially resolved. 14 issues remain fully present, plus 5 new issues were found.
- **Testing adequate**: No — none of the 16 recommended regression tests appear to exist in the codebase. The analysis recommended 16 specific test cases; verification would require examining the test directory.

---

## Recommended Priority Fix Order

Given the current state, the top three fixes from the original analysis remain the most impactful:

1. **Issue 1 + Issue 11**: Create a canonical recurring occurrence expander and use it in `SynthesisEngine.synthesizeInternal()` and `CashFlowCalculator.calculateDailyCashFlow()`. The `RecurringOccurrenceExpander` already exists — it just needs to be wired into these two call sites.
2. **Issue 4**: Filter recurring merchants out of `HistoricalSpendingDistribution` so Monte Carlo samples discretionary-only spending (follow the `FinancialStressForecastEngine` pattern).
3. **Issue 7 (remaining gap)**: Extend `PlannedExpense` domain model with `status` and filter out already-fulfilled planned expenses in `SynthesisEngine`.
