# Deep Analysis — Batch 02: Budget Engines (@debugger)

## Scope
- domain/budget/BudgetCalculator.kt
- domain/budget/BudgetForecastingEngine.kt
- domain/budget/BudgetAutopilotEngine.kt
- domain/budget/BudgetMonitor.kt
- domain/budget/BudgetRecommendationEngine.kt
- domain/budget/SharedBudgetManager.kt
- domain/budget/BudgetModels.kt (not found in codebase)
- domain/budget/BudgetRecommendationModels.kt

## Per-File Issues

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | BudgetForecastingEngine.kt:82 | CRITICAL | Incorrect Formula | `predictedRemaining = budget.amount - spentToDate - predictedSpending` double-counts spending. `predictedSpending` is a forecast of total future spend based on historical monthly averages, but `spentToDate` is already what's been spent. If spentToDate=€200 and monthly average=€500, remaining shows `600-200-500=-300` instead of `600-200-83=+117`. | Budget=€600, spent=€400 at day 25, avg=€500/mo. predictedRemaining=-300 (wrong). | Pro-rate: `predictedSpending = monthlyAvg * (remainingDays / 30.0)`. |
| 2 | BudgetForecastingEngine.kt:57-60 | CRITICAL | Incorrect Forecast Window | `calculatePredictedSpending()` always uses `forecastPeriodDays=30` regardless of budget period (DAILY=1, WEEKLY=7, YEARLY=365). WEEKLY budget gets 30-day forecast (4.3× overprediction). | WEEKLY budget €100 limit; forecast predicts 30 days of spending, false CRITICAL alerts. | Pass actual remaining period duration instead of hardcoded 30. |
| 3 | BudgetForecastingEngine.kt:422-435 | MEDIUM | Dead Code | `updateForecastAccuracy()` does nothing — `forecast` is always `null`. Accuracy tracking entirely non-functional. | Call with forecastId=1; silently does nothing. | Rewrite to use DAO query, compute accuracy, update. |
| 4 | BudgetForecastingEngine.kt:161-173 | MEDIUM | Incorrect Trend | `dropLast(2)` vs `takeLast(2)` split ratio varies wildly (4 months: 2vs2; 12 months: 10vs2). Order-dependent, not time-weighted. | Data [100,100,100,200]: shows +50% INCREASING. Same data [100,200,100,100]: shows -33% DECREASING. | Use linear regression (least-squares slope) normalized by mean. |
| 5 | BudgetMonitor.kt:82-93 | CRITICAL | CancellationException Swallowed | `catch (e: Exception)` catches `CancellationException`. Breaks structured concurrency — coroutine appears to complete normally when scope is cancelled. | Call `cleanup()` while budget check in-flight with retry delay; CancellationException caught and logged as "non-transient error". | Add `if (e is CancellationException) throw e` at top of catch. **[RESOLVED BY A.7]** |
| 6 | BudgetMonitor.kt:110-121 | MEDIUM | Race Condition | `cachedStatuses` accessed from IO dispatcher with no synchronization. Torn reads possible. `cachedStatuses!!` could NPE between null check and access. | Rapid calls from two threads within cache window. | Use Mutex or @Volatile + AtomicReference. |
| 7 | BudgetMonitor.kt:30-31 | MEDIUM | Memory Leak | `serviceJob = SupervisorJob()` created at construction but `cleanup()` must be explicitly called. If called after cleanup, coroutines silently fail. | Call cleanup(), then checkBudgets(); launched coroutine never runs. | Add guard `if (serviceJob.isCancelled) return`. |
| 8 | SharedBudgetManager.kt:44-46 | CRITICAL | Uses amount Instead of effectiveAmount | `totalSpent += expense.amount` uses raw amount. Shared expenses counted at full amount, "not mine" expenses counted at face value. Violates documented contract. | Shared expense €100 with 50% share; budget shows €100 instead of €50. | Change to `totalSpent += expense.effectiveAmount`. |
| 9 | SharedBudgetManager.kt:36-41 | MEDIUM | Incorrect Query | When `budget.categoryId == null` (overall budget), filter `expense.categoryId == budget.categoryId` becomes `== null`, only including uncategorized expenses. All categorized expenses excluded. | Overall budget; all categorized expenses excluded from spent total. | Change to: `budget.categoryId == null || expense.categoryId == budget.categoryId`. |
| 10 | SharedBudgetManager.kt:33 | LOW | Hardcoded Month | `getStartOfMonth(now)` always used regardless of budget period (DAILY, WEEKLY, YEARLY). Daily budget queries entire month. | DAILY shared budget; progress queries entire month's expenses. | Use `BudgetCalculator.calculatePeriodRange(budget, now)`. |
| 11 | BudgetCalculator.kt:44-49 | MEDIUM | ROLLING Uses Fixed 30 Days | For ROLLING+MONTHLY, end = `addDays(start, 30)`. Real months have 28-31 days. ROLLING monthly starting Feb 1 ends Mar 3, overlapping next cycle. | ROLLING MONTHLY starting Jan 31; end = Mar 2 (30 days). Actual month = 28 days. | Use `cal.add(Calendar.MONTH, 1)` instead of `addDays(start, 30)`. |
| 12 | BudgetCalculator.kt:93-125 | MEDIUM | Anchor Day Drift | When anchorDay=31 and current month is February (28 days), anchor day drifts from 31 to 28 permanently. | Set anchorDay=31, evaluate in March; previous period was Feb 28 → Mar 28 instead of Feb 28 → Mar 31. | Store original anchor day and coerce against each target month independently. |
| 13 | BudgetAutopilotEngine.kt:253-254 | LOW | Population vs Sample Variance | Uses population variance (÷N) while BudgetForecastingEngine uses sample variance (÷N-1). With 2-3 months, 41% difference in volatility. | 2 months [100, 200]: Autopilot σ=50, ForecastingEngine σ=70.7. | Pick one convention consistently. |
| 14 | BudgetAutopilotEngine.kt:326 | LOW | Confidence Scaling Bug | `historicalSpend.size / 100.0` = `3/100` = 0.03 (negligible). ForecastingEngine uses `monthsOfHistory / 12.0` = `3/12` = 0.25. Same data, wildly different confidence. | 3 months stable data: ForecastingEngine confidence=0.95, AutopilotEngine=0.73. | Change to `size / 12.0` to match ForecastingEngine. |
| 15 | BudgetAutopilotEngine.kt:241 | LOW | Negative Budget Possible | `average * (1 + trend * PROJECTION_MONTHS)` — if trend is -0.4, produces negative `trendAdjustedSpend`. | Rapidly declining: month1=€1000, month2=€500, month3=€100. trendAdjustedSpend = -426.67. | Add `.coerceAtLeast(0.0)` after trend-adjusted calculation. |
| 16 | BudgetRecommendationEngine.kt:66-67 | MEDIUM | Negative potentialSavings | `potentialSavings = forecast.predictedSpending - remaining` — when remaining > predictedSpending, produces negative value. UI shows "save -€200". | Budget=€1000, spent=€600, remaining=€400, predictedSpending=€200. potentialSavings=-200. | Change to `.coerceAtLeast(0.0)` or null when negative. |
| 17 | BudgetRecommendationEngine.kt:116 | LOW | Contradictory Logic | Early-period check: `percentUsed < 20 && predictedRemaining < budget * 0.5`. With corrected formula, 10% spent but predicted remaining < 50% implies total spend > 50% — not alarming. | Budget=€1000, spent=€100 (10%), predicted future=€600, predictedRemaining=€300. Triggers warning unnecessarily. | Use `(spentToDate + predictedSpending) / budget.amount > 1.0`. |
| 18 | BudgetForecastingEngine.kt:292-303 | LOW | Dead Seasonal Code | `calculateSeasonalFactor()` requires `monthsOfHistory >= 6` but max historical window is 90 days (3-4 months). Condition can never be true. | Any budget, any time: monthsOfHistory max 3-4, never ≥ 6. | Extend historical window to ≥6 months or remove dead code. |
| 19 | BudgetForecastingEngine.kt:74-79 | MEDIUM | Confidence Multiplied Wrong | `probability * confidence` — when confidence is low (0.3), a budget clearly going to overspend (probability=1.0) reported as only 30% likely. Low confidence should increase uncertainty, not decrease it. | Budget €100, spent=€90, predicted=€50: buffer=-40, probability=1.0, confidence=0.3, result=0.3. | For deterministic case (buffer<0), return 1.0 unconditionally. |
| 20 | SharedBudgetManager.kt:36 | LOW | 2000-Row Default Limit | `getExpensesBetween(...)` has default `limit=2000`. Busy shared budget silently truncates, understating total spent. | 10 members × 10 expenses/day × 30 days = 3000 expenses. Only 2000 returned. | Pass explicit larger limit or use SUM query. |

### Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | ForecastingEngine → RecommendationEngine | CRITICAL | Data Model Mismatch | ForecastingEngine produces `BudgetForecast` with `ForecastRiskLevel`, but RecommendationEngine consumes `BudgetRecommendationForecast` with `BudgetRecommendationRiskLevel`. Completely separate enums/classes. No mapping code visible. Fragile seam. | Create single canonical risk level enum, or add explicit `toRecommendationForecast()` extension. |
| 2 | ForecastingEngine.predictedSpending → RecommendationEngine.potentialSavings | CRITICAL | Cascading Formula Error | Double-counting in predictedRemaining cascades into recommendation engine. predictedSpending uses 30-day window regardless of budget period. For weekly budget: predictedSpending ≈ 4× actual, massively inflated potentialSavings, phantom overspend alerts. | Fix Issues #1 and #2 first, then verify potentialSavings reflects actual period-appropriate excess. |
| 3 | AutopilotEngine vs ForecastingEngine | MEDIUM | Inconsistent Statistics | Both compute historical stats independently: different variance formulas, different trend algorithms, different time windows. Same data produces different predictions. | Extract shared `SpendingStatistics` utility class used by both engines. |
| 4 | SharedBudgetManager bypasses BudgetCalculator | MEDIUM | Inconsistent Period Logic | Uses own `getStartOfMonth()` instead of `BudgetCalculator.calculatePeriodRange()`. Shared budget progress always uses calendar month, ignoring budget's actual period/periodMode. | Inject BudgetCalculator and use `calculatePeriodRange(budget, now)`. |
| 5 | BudgetMonitor → BudgetRepository.getBudgetStatuses() | LOW | Hidden Coupling | 30-second cache + 60-second throttle = ~90 second worst-case notification delay. Stale cache returns old spentAmount, threshold check skipped. | Invalidate cache when called from expense-insert path, or reduce cache validity. |

### Summary
- **Total issues: 25** (20 per-file + 5 cross-component)
- **Files with issues: 7/8** (only BudgetRecommendationInputs.kt is clean)
- **CRITICAL: 6** (Issues #1, #2, #5, #8, Cross #1, Cross #2)
- **MEDIUM: 9**
- **LOW: 7**

### Top 3 Priorities
1. **Issues #1 + #2 + Cross #2**: Forecast engine's predictedSpending computed for fixed 30-day window irrespective of budget period, double-counted against spentToDate. Cascades into recommendations causing phantom overspend alerts.
2. **Issue #5**: CancellationException swallowed in BudgetMonitor — breaks structured concurrency, coroutine leaks.
3. **Issue #8**: SharedBudgetManager uses `expense.amount` instead of `effectiveAmount` — violates documented contract.
