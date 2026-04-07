# Final Verification — Batch 48: Domain Use Cases & Text

## Scope
- `com/yourname/expensetracker/domain/usecase/budget/CalculateBudgetStatusUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardRepositoryContracts.kt`
- `com/yourname/expensetracker/domain/usecase/expense/CategorizeExpenseUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/expense/DetectDuplicateExpenseUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/expense/ExpenseUseCases.kt`
- `com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
- `com/yourname/expensetracker/domain/text/DashboardTextKeys.kt`
- `com/yourname/expensetracker/domain/text/DomainTextKeys.kt`
- Supporting integration files read during verification:
  - `com/yourname/expensetracker/domain/budget/BudgetModels.kt`
  - `com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt`
  - `com/yourname/expensetracker/domain/lifestyle/LifestyleInflationDetector.kt`
  - `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
  - `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
  - `com/yourname/expensetracker/data/repository/PromptStateRepository.kt`
  - `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - `com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
  - `com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
  - `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
  - `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - `com/yourname/expensetracker/data/database/dao/PromptStateDao.kt`
  - `com/yourname/expensetracker/data/database/dao/WarrantyDao.kt`
  - `com/yourname/expensetracker/data/database/entity/PromptState.kt`
  - `com/yourname/expensetracker/data/database/entity/Warranty.kt`
  - `com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt`
  - `com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/usecase/budget/CalculateBudgetStatusUseCase.kt:25-39` | Medium | Logic | `getBudgetHealth()` ignores `CRITICAL`, so critical budgets are counted as healthy and can still yield overall `ON_TRACK`. | B | DOWNGRADED | Count `CRITICAL` explicitly and include it in overall-status precedence. |
| 2 | `data/repository/DashboardContractsAdapter.kt:49-53` | High | Logic | `observeDashboardExpenses()` snapshots the current month once with `System.currentTimeMillis()`, so long-lived collectors stay on the old month after rollover. | B | CONFIRMED | Drive the date window from `TimeProvider`/a reactive refresh signal instead of a one-time timestamp. |
| 3 | `domain/usecase/dashboard/DashboardDataProvider.kt:41-43,49-53,60,87-99,113-133` | Low | Error handling | Several flows silently replace repository failures with empty/default values, making data-source outages look like valid “no data” states. | R | DOWNGRADED | Log each caught exception or surface an explicit degraded/error state. |
| 4 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:535-540` | Medium | Logic | Budget summary says “all budgets on track” whenever nothing is `EXCEEDED`, even if budgets are already `WARNING` or `CRITICAL`. | R | DOWNGRADED | Treat any non-`ON_TRACK` status as non-healthy in the summary text. |
| 5 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:653-656` | Medium | Logic | When no overall budget exists, `SafeToSpend.amount` falls back to `monthSpent`, so already-spent money is labeled as spendable. | B | DOWNGRADED | Hide the widget or compute a real discretionary baseline when no spendable budget exists. |
| 6 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:435-440` | Low | Data mapping | `DomainExpenseSummary.categoryName` is populated with `categoryId?.toString()`, creating a hidden ID-through-name contract. | R | DOWNGRADED | Add a real `categoryId` field (or map the actual name) and update the UI mapper accordingly. |
| 7 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:250-256` | High | Logic | Budget-risk spending uses `getExpensesSince(monthStart)` with no upper bound, so future-dated purchases inflate “spent to date”. | R | CONFIRMED | Query `[monthStart, now)` or filter `date <= now` before summing. |
| 8 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:337-345` | High | Logic | Budget-risk urgency scoring uses only overrun probability, so a `HIGH`/`CRITICAL` risk driven by overrun magnitude can contribute zero budget-risk score. | R | CONFIRMED | Score from `riskTier`, or combine probability and magnitude. |
| 9 | `domain/usecase/expense/ExpenseUseCases.kt:79-85` | Medium | Logic | `ReviewExpenseUseCase` returns `Success` even when `categoryId` is null and `ExpenseRepository.updateExpenseCategory()` no-ops. | R | CONFIRMED | Require a non-null category for this path or persist an actual review/confirmation state. |
| 10 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:97-119` | High | Logic | The standalone forecast fabricates `SpendingPace` (`projectedTotal = monthSpent`, fixed `ON_PACE`, fixed `pacePercentage`), so forecast inputs diverge from the real dashboard/weather path. | B | CONFIRMED | Reuse the same pace-calculation path used by dashboard/weather forecasting. |
| 11 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:121-127` | High | Logic | The standalone forecast passes `pastSumDaily = emptyList()`, so synthesis loses the actual month-to-date spend curve. | B | CONFIRMED | Build cumulative daily spend from expenses before calling `synthesize()`. |
| 12 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:85-94` | Medium | Logic | Savings goals are always mapped to `TRACKING`, dropping stored `STRICT`/`WARNING` protection semantics. | B | CONFIRMED | Map the entity protection level to the domain enum, as done in the dashboard adapter. |
| 13 | `domain/usecase/receipt/ProcessReceiptUseCase.kt:27-43` | Low | Logic | Missing merchant/total are coerced to `"Unknown"`/`0.0` but still returned as a successful parse with no review-needed signal. | R | DOWNGRADED | Return an explicit incomplete/review-needed result when critical receipt fields are absent. |
| 14 | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:74-79` | High | Contract | The use case treats `savingsRate <= 1.0` as a fraction, but `LifestyleInflationDetector` already emits percentages; low real rates can be inflated by 100x. | B | CONFIRMED | Standardize the contract to percentage units and remove the heuristic conversion. |
| 15 | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:41-45,122-131` | High | State management | Prompt eligibility is checked, but prompt display is never recorded, so dashboard refreshes can repeat the same recommendation indefinitely. | R | CONFIRMED | Record prompt display before returning, or expose one atomic evaluate-and-record API. |
| 16 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:198-202,422-425` | Medium | Logic | Sweep-risk spending includes `WITHDRAWAL`, while budget underspend and related budget paths use purchase-only spend. | R | DOWNGRADED | Align the spending filter with the budget pipeline’s purchase-only logic. |
| 17 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:209-213` | Medium | Logic | Null Monte Carlo falls back to a hard-coded `100.0` risk buffer even though the comment claims a percentage-based estimate. | R | DOWNGRADED | Derive the fallback from actual budget/spend context, or skip recommendation when uncertainty cannot be estimated. |
| 18 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:269-289` | Low | Logic | `allocationPercentage` keeps the pre-cap urgency share, so displayed percentages can disagree with final allocations. | R | DOWNGRADED | Recalculate percentages from the finalized allocation amounts. |
| 19 | `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt:10-13` | Low | Maintainability | `userCorrectionRepository` is injected but never used. | D | CONFIRMED | Remove the dependency or integrate it into deduplication logic. |
| 20 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:89-96` | Low | Architecture | `ComputeMoneyRadarUseCase` depends directly on `AnomalyAlertDao`, bypassing the repository boundary used for its other inputs. | D | DOWNGRADED | Introduce an anomaly-alert repository interface and inject that instead of the DAO. |
| 21 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:430-435` | Low | Maintainability | `effectiveAmount` is redefined locally instead of using the entity’s canonical property, creating two sources of truth. | D | DOWNGRADED | Remove the local extension and use `Expense.effectiveAmount`. |
| 22 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:138-141` | Low | Performance | Independent Money Radar data fetches run sequentially despite the code comment claiming parallel gathering. | D | DOWNGRADED | Fetch independent inputs with `coroutineScope`/`async` and `await()`. |
| 23 | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:98-124` | Medium | Logic | When current savings rate is 0, `maxCap` becomes 0 but `coerceAtLeast(1.0)` still forces a 1% uplift, bypassing the stated cap. | D | CONFIRMED | Handle zero/near-zero savings rates explicitly instead of overriding the cap with a hard minimum. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt:49-61,112-117` | Medium | Logic / Messaging | `expectedOverrun` is clamped to `0.0`, but medium/high/critical display text always interpolates that value. Probability-driven risk cases can therefore say “exceed budget by €0.00”. | Choose messages from both `riskTier` and whether the risk is probability-only; use probability-focused wording when `expectedOverrun == 0.0`. |
| 2 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:269-277` | Medium | Logic | `MAX_SINGLE_ALLOCATION_PERCENT` is enforced only for non-last goals. The final “remainder” branch can exceed the cap, so the advertised concentration limit is not actually enforced. | Compute/cap all allocations consistently, then redistribute leftover across uncapped goals instead of dumping the remainder into the final item. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `D#1 / D Cross-Component C5` | `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt:80-98` | The reported reproduction is incorrect: a 26% overrun probability is not “LOW” under the documented thresholds. The code’s cascading thresholds do not inflate tiers the way the report claims. |
| 2 | `D#7` | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:298` | `daysRemaining = daysInMonth - dayOfMonth` is correct for “days remaining after today”, and no verified consumer divides by this value. |
| 3 | `D#11` | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:298` | `calculateDueBillsScore()` legitimately remains `suspend` because it calls the suspend `getMonthlyIncome()`. |
| 4 | `D#14` | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:365` | `Calendar.DAY_OF_MONTH` is 1-based, so the supposed divide-by-zero case cannot happen. |
| 5 | `D#15` | `data/repository/DashboardContractsAdapter.kt:160` | `changeFromLastPeriod = 0.0` is a placeholder, but no consumer in this batch reads the field, so no concrete failing behavior was verified. |
| 6 | `D#16` | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:302-303` | `totalSpent` and `monthSpent` intentionally both mirror the current-period summary here; no contradictory semantics or bad behavior were found. |
| 7 | `D#17` | `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt:82-87` | The medium-confidence path intentionally persists a review draft while still signaling “low confidence”; the unique `receiptId` constraint prevents duplicate warranty creation. |
| 8 | `D Cross-Component C2` | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:54-95; data/repository/DashboardContractsAdapter.kt:82-136` | The duplicated mapping is a maintainability smell, but the concrete bugs are already captured by the confirmed forecast/protection-level issues. No additional standalone pipeline defect beyond those was verified. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `DashboardContractsAdapter.observeDashboardExpenses()` → `DashboardDataProvider` → `ComputeDashboardWidgetsUseCase` | High | Consistency | Dashboard expense widgets can stay pinned to the previous month after rollover while other analytics recompute for the new month, producing split-brain dashboard state. | `data/repository/DashboardContractsAdapter.kt`, `domain/usecase/dashboard/DashboardDataProvider.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Centralize month-window ownership and make it time-provider-driven/reactive. |
| 2 | `FinancialWeatherRepository.getFinancialWeather()` ↔ `DashboardContractsAdapter.observeRecurringPatterns()` → `ComputeDashboardWidgetsUseCase` | High | Consistency | Financial weather uses merged detected+manual recurring patterns, but dashboard forecast widgets get only manual recurring rows, so weather/runway/block-party/Monte Carlo can disagree on the same screen. | `data/repository/FinancialWeatherRepository.kt`, `data/repository/DashboardContractsAdapter.kt`, `domain/usecase/dashboard/DashboardDataProvider.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Feed both weather and dashboard forecasting from the same recurring-pattern source. |
| 3 | `ComputeDashboardWidgetsUseCase.compute()` → `ComputeMoneyRadarUseCase.compute()` | Medium | Performance | Money Radar re-fetches budgets, expenses, anomalies, recurring data, and reruns Monte Carlo even though dashboard compilation already computed overlapping inputs/results. | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | Share a dashboard snapshot/current-cycle cache instead of recomputing from repositories. |
| 4 | `ComputeDashboardWidgetsUseCase.computeLifestyleWidget()` → `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` → `PromptStateRepository` | High | State management | The display path shows lifestyle prompts directly from evaluation output, but no layer records that the prompt was displayed, so cooldown logic cannot prevent repeated impressions. | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`, `data/repository/PromptStateRepository.kt` | Expose a single API that evaluates eligibility and records the display atomically. |
| 5 | `CalculateFinancialForecastUseCase` ↔ `ComputeDashboardWidgetsUseCase` | High | Consistency | The standalone forecast and dashboard forecast prepare materially different synthesis inputs (synthetic pace vs real pace, empty history vs cumulative history, hardcoded vs mapped protection levels), so different entry points can show different forecasts for the same month. | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Extract shared forecast-preparation logic and reuse it in both entry points. |
| 6 | `CalculateFinancialForecastUseCase` / `FinancialWeatherRepository` → `ExpenseRepository.getAllExpenses()` | Medium | Data completeness | Both forecast paths consume `getAllExpenses()`, which is capped to 500 rows, so high-volume accounts can get truncated spend history and incomplete current-month forecast inputs. | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`, `data/repository/FinancialWeatherRepository.kt`, `data/repository/ExpenseRepository.kt` | Replace the capped “all expenses” feed with a date-scoped query or paged aggregation tailored to forecast needs. |

## Summary
- Total verified issues: 23 file-level issues (plus 6 retained cross-component pipeline issues)
- Confirmed: 23 (Critical: 0, High: 7, Medium: 8, Low: 8)
- False positives: 8
- Missed issues found: 2
- Files affected: 12/17

## Key Patterns
- Forecast preparation is duplicated across multiple entry points, and the duplicate paths have already diverged in materially user-visible ways.
- Several dashboard widgets collapse richer domain states into oversimplified buckets (`CRITICAL` treated as healthy, budget risk reduced to probability only, “safe to spend” inferred from spent money).
- Anti-nag/stateful recommendation flows are evaluated, but not atomically recorded, so they are vulnerable to repeated impressions.
- Some financial pipelines silently degrade to defaults or capped data sources, which hides operational problems and can understate real spending state.
