# Deep Analysis — Batch 48: Domain Use Cases & Text (@reviewer)

## Scope
- domain/usecase/budget/CalculateBudgetStatusUseCase.kt
- domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt
- domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt
- domain/usecase/dashboard/DashboardContractsAdapter.kt
- domain/usecase/dashboard/DashboardDataProvider.kt
- domain/usecase/dashboard/DashboardRepositoryContracts.kt
- domain/usecase/expense/CategorizeExpenseUseCase.kt
- domain/usecase/expense/DetectDuplicateExpenseUseCase.kt
- domain/usecase/expense/ExpenseUseCases.kt
- domain/usecase/forecast/CalculateFinancialForecastUseCase.kt
- domain/usecase/receipt/ProcessReceiptUseCase.kt
- domain/usecase/savings/LifestyleSavingsPromptUseCase.kt
- domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt
- domain/text/DashboardTextKeys.kt
- domain/text/DomainTextKeys.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `CalculateBudgetStatusUseCase.kt:25-39` | HIGH | Logic | `getBudgetHealth()` counts only `EXCEEDED` and `WARNING`. `CRITICAL` budgets are treated as healthy and can still produce overall `ON_TRACK`, which breaks aggregate health reporting. | Count `CRITICAL` separately and include it in `healthyCount` subtraction and `overallStatus` precedence. |
| 2 | `DashboardContractsAdapter.kt:49-53` | HIGH | Logic | `observeDashboardExpenses()` captures the current month with `System.currentTimeMillis()` once, so long-lived collectors keep observing the old month after rollover. It also bypasses the injected clock abstraction used elsewhere. | Drive the period from `TimeProvider`/a refresh trigger or observe a broader range and filter with current month boundaries downstream. |
| 3 | `DashboardDataProvider.kt:41-43,49-53,60,87-99,113-133` | MEDIUM | Error handling | Multiple flows swallow repository/analytics exceptions and silently emit empty/default values without logging. That makes broken data sources look like valid “no data” states and hides production failures. | Log each caught exception with source context before emitting fallback data, or surface an explicit degraded/error state. |
| 4 | `ComputeDashboardWidgetsUseCase.kt:535-540` | HIGH | Logic | Budget summary says “all budgets on track” whenever nothing is `EXCEEDED`, even if budgets are already `CRITICAL` or `WARNING`. | Summarize any non-`ON_TRACK` state, or add dedicated warning/critical text keys instead of treating them as healthy. |
| 5 | `ComputeDashboardWidgetsUseCase.kt:653-656` | HIGH | Logic | When there is no overall budget, `SafeToSpend.amount` falls back to `monthSpent`. The UI label still says “safe to spend”, so spent money is presented as spendable balance. | Use an actual remaining/discretionary amount, or suppress the widget when no spendable baseline exists. |
| 6 | `ComputeDashboardWidgetsUseCase.kt:439-440` | MEDIUM | Data mapping | `DomainExpenseSummary.categoryName` is populated with `categoryId?.toString()` instead of a readable category name. This leaks IDs into UI-oriented domain models. | Map the real category name (join from available category metadata) or leave the field null until a name is available. |
| 7 | `ComputeMoneyRadarUseCase.kt:251-256` | HIGH | Logic | Budget-risk spending uses `expenseRepository.getExpensesSince(monthStart)` with no upper bound. Any future-dated expense already stored in the DB is counted as “spent to date”, inflating Monte Carlo inputs and urgency. | Query `[monthStart, now)` explicitly, or filter `date <= now` before summing. |
| 8 | `ComputeMoneyRadarUseCase.kt:337-345` | HIGH | Logic | Urgency scoring reduces budget risk to overrun probability only. A case that is `HIGH` by overrun magnitude but low probability can get a budget score of `0`, producing a low urgency score with a high-risk CTA. | Derive score from `riskTier`, or combine probability and overrun magnitude instead of ignoring one dimension. |
| 9 | `ExpenseUseCases.kt:79-85` | MEDIUM | Logic | `ReviewExpenseUseCase` claims to “review/confirm” an expense but only updates category. If `categoryId` is null, the repository no-ops and the use case still returns `Success`, so the review action may do nothing. | Require a valid category for this path, or persist actual review/confirmation state instead of equating review with category assignment. |
| 10 | `CalculateFinancialForecastUseCase.kt:110-127` | HIGH | Logic | The forecast is synthesized from an artificial `SpendingPace` (`ON_PACE`, `projectedTotal = monthSpent`) and `pastSumDaily = emptyList()`. This ignores the actual month-to-date spend trajectory and can diverge materially from the dashboard/weather forecast pipeline. | Build real cumulative daily spend and use the same pace-calculation path used by dashboard/weather forecasting. |
| 11 | `CalculateFinancialForecastUseCase.kt:85-94` | MEDIUM | Logic | Every savings goal is mapped to `GoalProtectionLevel.TRACKING`, discarding the stored protection level. Forecast logic receiving these goals loses strict/warning semantics. | Map each entity protection level to the correct domain enum, as done in other repository adapters. |
| 12 | `ProcessReceiptUseCase.kt:29-43` | MEDIUM | Logic | Missing parser data is converted to `merchant = "Unknown"` and `amount = 0.0`, but the use case still returns `Result.success(...)`. That can create bogus receipts instead of forcing review/error handling. | Fail or return an explicit incomplete/review-needed result when critical fields like merchant/total are missing. |
| 13 | `LifestyleSavingsPromptUseCase.kt:74-79` | HIGH | Contract | The use case treats `savingsRate <= 1.0` as a ratio and multiplies by 100, but `LifestyleInflationDetector` already emits percentage values. Real low rates like `0.8%` become `80%`, corrupting caps and recommendations. | Standardize the contract to percentage values and remove the heuristic conversion. |
| 14 | `LifestyleSavingsPromptUseCase.kt:41-45,122-131` | HIGH | Logic | Cooldown checks are performed, but prompt impressions are never recorded when a recommendation is returned. On plain dashboard refreshes the same prompt can be shown repeatedly because anti-nag state never advances. | Record the prompt display before returning a recommendation, or expose a single atomic “evaluate and record display” API. |
| 15 | `MonthlySavingsSweepUseCase.kt:199-202,422-425` | HIGH | Logic | Sweep risk uses `PURCHASE + WITHDRAWAL` as spent-to-date, while budget underspend and other budget/forecast paths use purchase-only spending. Cash withdrawals can therefore incorrectly suppress valid sweep recommendations. | Align the spending filter with the budget pipeline (same transaction types and effective-amount rules). |
| 16 | `MonthlySavingsSweepUseCase.kt:209-213` | HIGH | Logic | When Monte Carlo returns null, the fallback risk buffer is a hard-coded `100.0`, despite the comment claiming a “20%” estimate. This arbitrary value can block small-budget sweeps or understate uncertainty for large budgets. | Derive fallback from budget/spend context, or skip the recommendation when uncertainty cannot be estimated reliably. |
| 17 | `MonthlySavingsSweepUseCase.kt:269-289` | MEDIUM | Logic | `allocationPercentage` stores the original urgency share, not the actual post-cap/post-residual allocation share. Once caps or the “last item gets the remainder” branch apply, displayed percentages can disagree with `suggestedAllocation`. | Recalculate `allocationPercentage` from the finalized allocation amount divided by `safeSweepAmount`. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `DashboardContractsAdapter.observeDashboardExpenses()` → `DashboardDataProvider` → `ComputeDashboardWidgetsUseCase` | HIGH | Consistency | Dashboard transactions/widgets can stay pinned to the old month after rollover, while summary/category analytics recompute on later emissions for the new month. That creates split-brain dashboard state. | Centralize dashboard date-window ownership and make month boundaries reactive/time-provider-driven for all dashboard inputs. |
| 2 | `FinancialWeatherRepository.getFinancialWeather()` vs `DashboardContractsAdapter.observeRecurringPatterns()` → `ComputeDashboardWidgetsUseCase` | HIGH | Consistency | Financial weather is synthesized from merged detected+manual recurring patterns, but dashboard widget forecasting receives only manual recurring rows. Weather, runway, block-party, and Monte Carlo can disagree on the same screen. | Feed both weather and widget forecasting from the same recurring-pattern provider/output. |
| 3 | `ComputeDashboardWidgetsUseCase.compute()` → `ComputeMoneyRadarUseCase.compute()` | MEDIUM | Performance | Money Radar re-fetches budgets, expenses, deposits, recurring patterns, and reruns Monte Carlo even though dashboard compilation already has overlapping data. This adds avoidable DB work and duplicate simulation on every dashboard refresh. | Pass a shared dashboard snapshot into Money Radar, or cache/share the intermediate inputs/results for the current refresh cycle. |
| 4 | `ComputeDashboardWidgetsUseCase.computeLifestyleWidget()` → `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` → `PromptStateRepository` | HIGH | State management | The dashboard shows the lifestyle prompt directly from evaluation output, but no layer records that the prompt was displayed. Cooldown logic therefore cannot protect against repeated impressions. | Make the display path call an API that both evaluates eligibility and records the prompt impression atomically. |

## Summary
- Total issues: 17
- Critical: 0, High: 12, Medium: 5, Low: 0
- Files with issues: 9/17

## Key Patterns
- Several “summary” paths collapse richer domain states into overly simplistic buckets (`CRITICAL` treated as healthy, high budget risk reduced to probability only).
- The dashboard pipeline has multiple consistency problems because different widgets are fed from different data derivations of the same concepts (month window, recurring patterns, forecast inputs).
- Error handling often degrades to empty/default data without logging, which makes real failures indistinguishable from legitimate empty states.
- Forecast/savings features reuse similar financial concepts but not the same filtering rules, causing subtle disagreements (purchase-only vs purchase+withdrawal, prompt cooldown checked but never persisted).
