# Deep Analysis — Batch 02: Budget Engines (@reviewer)

## Per-File Issues

| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | BudgetCalculator.kt:44-49 | MEDIUM | ROLLING Uses Fixed 30 Days | ROLLING+MONTHLY uses `addDays(start, 30)` instead of `cal.add(Calendar.MONTH, 1)`. Real months have 28-31 days. ROLLING monthly starting Feb 1 ends Mar 3, overlapping next cycle. | Use `cal.add(Calendar.MONTH, 1)` for true rolling month. |
| 2 | BudgetCalculator.kt:93-125 | MEDIUM | Anchor Day Drift | anchorDay=31 in February (28 days) causes anchor to drift from 31 to 28 permanently. | Store original anchor day, coerce against each target month independently. |
| 3 | BudgetForecastingEngine.kt:57-60 | HIGH | Fixed 30-Day Forecast Window | `calculatePredictedSpending()` always uses `forecastPeriodDays=30` regardless of budget period (DAILY=1, WEEKLY=7, YEARLY=365). WEEKLY budget gets 4.3× overprediction. | Pass actual remaining period duration. |
| 4 | BudgetForecastingEngine.kt:82 | HIGH | Double-Counting in predictedRemaining | `predictedRemaining = budget.amount - spentToDate - predictedSpending` — predictedSpending is total forecast, not additional spend. Should pro-rate by remaining days. | `predictedSpending = monthlyAvg * (remainingDays / 30.0)`. |
| 5 | BudgetForecastingEngine.kt:161-173 | MEDIUM | Incorrect Trend Algorithm | `dropLast(2)` vs `takeLast(2)` split ratio varies wildly. Order-dependent, not time-weighted. | Use linear regression (least-squares slope) normalized by mean. |
| 6 | BudgetForecastingEngine.kt:292-303 | LOW | Dead Seasonal Code | `calculateSeasonalFactor()` requires `monthsOfHistory >= 6` but max window is 90 days (3-4 months). Condition never true. | Extend historical window to ≥6 months or remove dead code. |
| 7 | BudgetForecastingEngine.kt:74-79 | MEDIUM | Confidence Multiplied Wrong | `probability * confidence` — low confidence (0.3) reduces overspend probability from 1.0 to 0.3. Should increase uncertainty, not decrease. | For deterministic case (buffer<0), return 1.0 unconditionally. |
| 8 | BudgetForecastingEngine.kt:422-435 | LOW | Dead Code | `updateForecastAccuracy()` does nothing — forecast always null. | Rewrite to use DAO query. |
| 9 | BudgetMonitor.kt:82-93 | HIGH | CancellationException Swallowed | `catch (e: Exception)` catches CancellationException. Breaks structured concurrency. | Add `if (e is CancellationException) throw e` at top of catch. |
| 10 | BudgetMonitor.kt:110-121 | MEDIUM | Race Condition | `cachedStatuses` accessed from IO dispatcher with no synchronization. Torn reads, NPE risk. | Use Mutex or @Volatile + AtomicReference. |
| 11 | BudgetMonitor.kt:30-31 | MEDIUM | Memory Leak | `serviceJob = SupervisorJob()` created at construction but cleanup() must be explicitly called. After cleanup, coroutines silently fail. | Add guard `if (serviceJob.isCancelled) return`. |
| 12 | BudgetAutopilotEngine.kt:253-254 | LOW | Population vs Sample Variance | Uses population variance (÷N) while BudgetForecastingEngine uses sample variance (÷N-1). 41% difference with 2 months data. | Pick one convention consistently. |
| 13 | BudgetAutopilotEngine.kt:326 | LOW | Confidence Scaling Bug | `historicalSpend.size / 100.0` = 0.03 (negligible). ForecastingEngine uses `size / 12.0` = 0.25. Same data, wildly different confidence. | Change to `size / 12.0`. |
| 14 | BudgetAutopilotEngine.kt:241 | LOW | Negative Budget Possible | `average * (1 + trend * PROJECTION_MONTHS)` — if trend is -0.4, produces negative trendAdjustedSpend. | Add `.coerceAtLeast(0.0)`. |
| 15 | BudgetRecommendationEngine.kt:66-67 | MEDIUM | Negative potentialSavings | `potentialSavings = forecast.predictedSpending - remaining` — can be negative. UI shows "save -€200". | Change to `.coerceAtLeast(0.0)` or null. |
| 16 | BudgetRecommendationEngine.kt:116 | LOW | Contradictory Logic | Early-period check triggers warning unnecessarily with corrected formula. | Use `(spentToDate + predictedSpending) / budget.amount > 1.0`. |
| 17 | SharedBudgetManager.kt:44-46 | HIGH | Uses amount Instead of effectiveAmount | `totalSpent += expense.amount` — shared expenses counted at full amount. Violates documented contract. | Change to `totalSpent += expense.effectiveAmount`. |
| 18 | SharedBudgetManager.kt:36-41 | MEDIUM | Incorrect Query for Overall Budgets | When `budget.categoryId == null`, filter becomes `expense.categoryId == null`, only including uncategorized expenses. | Change to: `budget.categoryId == null || expense.categoryId == budget.categoryId`. |
| 19 | SharedBudgetManager.kt:33 | LOW | Hardcoded Month | `getStartOfMonth(now)` always used regardless of budget period. | Use `BudgetCalculator.calculatePeriodRange(budget, now)`. |
| 20 | SharedBudgetManager.kt:36 | LOW | 2000-Row Default Limit | `getExpensesBetween(...)` default limit=2000. Busy shared budget silently truncates. | Pass explicit larger limit or use SUM query. |

### Cross-Component Issues

| # | Components | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | ForecastingEngine → RecommendationEngine | HIGH | Data Model Mismatch | ForecastingEngine produces `BudgetForecast` with `ForecastRiskLevel`, RecommendationEngine consumes `BudgetRecommendationForecast` with `BudgetRecommendationRiskLevel`. Completely separate enums/classes. No mapping code. | Create single canonical risk level enum or add explicit mapping extension. |
| 2 | ForecastingEngine.predictedSpending → RecommendationEngine.potentialSavings | HIGH | Cascading Formula Error | Double-counting in predictedRemaining cascades into recommendation engine. For weekly budget: predictedSpending ≈ 4× actual, massively inflated potentialSavings. | Fix Issues #3 and #4 first, then verify potentialSavings reflects actual period-appropriate excess. |
| 3 | AutopilotEngine vs ForecastingEngine | MEDIUM | Inconsistent Statistics | Both compute historical stats independently: different variance formulas, different trend algorithms. Same data produces different predictions. | Extract shared `SpendingStatistics` utility class. |
| 4 | SharedBudgetManager bypasses BudgetCalculator | MEDIUM | Inconsistent Period Logic | Uses own `getStartOfMonth()` instead of `BudgetCalculator.calculatePeriodRange()`. Shared budget progress always uses calendar month. | Inject BudgetCalculator and use `calculatePeriodRange(budget, now)`. |
| 5 | BudgetMonitor → BudgetRepository.getBudgetStatuses() | LOW | Hidden Coupling | 30-second cache + 60-second throttle = ~90 second worst-case notification delay. | Invalidate cache when called from expense-insert path. |

### Overlapping Functionality

| # | Files | Description | Recommendation |
|---|-------|-------------|----------------|
| 1 | BudgetForecastingEngine, BudgetAutopilotEngine | Both compute historical statistics (mean, variance, trend) independently with different implementations. | Extract shared `SpendingStatistics` utility. |
| 2 | BudgetForecastingEngine, BudgetRecommendationEngine | RecommendationEngine uses ForecastingEngine's output but with separate risk level enums and data classes. | Unify risk level types. |
| 3 | SharedBudgetManager, BudgetCalculator | SharedBudgetManager duplicates period calculation logic instead of using BudgetCalculator. | Inject BudgetCalculator into SharedBudgetManager. |

### Summary
- **Total issues: 25** (20 per-file + 5 cross-component)
- **Files with issues: 7/8** (only BudgetRecommendationInputs.kt is clean)
- **HIGH: 6** (Issues #3, #4, #9, #17, Cross #1, Cross #2)
- **MEDIUM: 9**
- **LOW: 7**

### Top 3 Priorities
1. **Issues #3 + #4 + Cross #2**: Forecast engine's predictedSpending computed for fixed 30-day window irrespective of budget period, double-counted against spentToDate. Cascades into recommendations.
2. **Issue #9**: CancellationException swallowed in BudgetMonitor — breaks structured concurrency.
3. **Issue #17**: SharedBudgetManager uses `expense.amount` instead of `effectiveAmount` — violates documented contract.
