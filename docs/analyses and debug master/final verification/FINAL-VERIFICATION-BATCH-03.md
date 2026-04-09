# Final Verification — Batch 03: Savings & Health

> **[RESOLVED BY A.1]** The `effectiveAmount` vs `amount` inconsistency has been standardized across the codebase. All related issues in this batch are now resolved.

## Scope
- `com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`
- `com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
- `com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt`
- `com/yourname/expensetracker/domain/savings/SavingsGoalRepository.kt` *(supporting validation)*
- `com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
- `com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
- `com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt` *(budget-policy cross-check)*
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` *(dashboard pipeline validation)*
- `com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
- `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
- `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
- `com/yourname/expensetracker/domain/budget/BudgetModels.kt`
- `com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt`
- `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`
- `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/database/entity/SavingsGoal.kt`
- `com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt`
- `com/yourname/expensetracker/data/database/dao/HealthScoreHistoryDao.kt`
- Planned scope entries not present in the codebase: `com/yourname/expensetracker/domain/savings/SavingsModels.kt`, `com/yourname/expensetracker/domain/health/FinancialHealthModels.kt`, `com/yourname/expensetracker/domain/health/HealthScoreModels.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/savings/AutomatedSavingsRuleEngine.kt:94-97` | High | Validation | `PERCENTAGE_OF_INCOME` accepts negative, `NaN`, and infinite percentages; that can emit negative/non-finite rule executions and poison monthly-cap state. | R | CONFIRMED | Reject non-finite or `<= 0` percentages before computing the amount. |
| 2 | `domain/savings/AutomatedSavingsRuleEngine.kt:55-79,214-245` | High | Idempotency | `WEEKLY_NO_SPEND` is evaluated on every `evaluateRules()` call and has no persisted once-per-period guard, so qualifying periods can mint repeated rewards. | B | DOWNGRADED | Persist executions per stable weekly period and skip already-awarded periods. |
| 3 | `domain/savings/AutomatedSavingsRuleEngine.kt:52-53,250-303` | High | State | Monthly-cap enforcement lives only in an in-memory singleton map, so caps reset after process death/app restart. | R | CONFIRMED | Move cap usage to durable storage and update it atomically with execution persistence. |
| 4 | `domain/savings/SmartSavingsEngine.kt:77-85` | High | Aggregation | `calculateBudgetSurplus()` sums every positive remaining budget; when overall and category budgets coexist, surplus is double-counted. | R | CONFIRMED | Reuse the same overall-vs-category selection rule as `MonthlySavingsSweepUseCase`. |
| 5 | `domain/savings/SmartSavingsEngine.kt:44-74,143-199` | High | Allocation | `calculateSafeToSaveAmount(goal, ...)` computes a portfolio-wide amount, but returns it per goal; `goal` only changes messaging, so multiple goals can each receive the same full recommendation. | R | CONFIRMED | Separate “safe amount available” from goal allocation, or allocate/cap by remaining target and priority. |
| 6 | `domain/savings/SmartSavingsEngine.kt:186-197` | Medium | Forecasting | WEEK and QUARTER horizons scale a month-end Monte Carlo forecast by `0.25`/`3.0`, even though the simulator models only the current month. | R | CONFIRMED | Restrict Monte Carlo usage to `MONTH`, or build horizon-specific simulations. |
| 7 | `domain/savings/SmartSavingsEngine.kt:97-106` | Low | Data hygiene | Spending-pace analysis sums raw `effectiveAmount`; malformed negative shares can invert average daily spend and inflate savings recommendations. | D | DOWNGRADED | Clamp spend inputs to `>= 0` or validate expense normalization upstream. |
| 8 | `domain/savings/SmartSavingsEngine.kt:157-174` | Medium | Baseline | `monthlyDiscretionary` always divides by `3.0` for any non-empty 90-day history, which materially understates users with only partial history. | D | CONFIRMED | Divide by actual covered months/days, not a hard-coded `3.0`. |
| 9 | `domain/savings/SavingsGamificationEngine.kt:34-73` | Medium | Logic | Streaks and monthly contribution totals are synthetic placeholders based on `createdAt` and current balances rather than contribution history; the 30-day lookback and hardcoded streak values make the metrics non-behavioral. | B | DOWNGRADED | Persist contribution events and derive streaks/counts/totals from those events. |
| 10 | `domain/savings/SavingsGamificationEngine.kt:97-99,117-119,127-129,138-140` | Low | State | Achievement `unlockedAt` timestamps are recomputed with `timeProvider.now()` on every read, so unlock dates drift on refresh. | B | DOWNGRADED | Persist first-unlock timestamps and reuse them. |
| 11 | `domain/savings/SavingsGamificationEngine.kt:123-130` | Low | Logic | `goal_crusher` progress uses `goals.firstOrNull()` instead of the most advanced incomplete goal, underreporting progress for multi-goal users. | R | CONFIRMED | Use the maximum normalized progress across incomplete goals. |
| 12 | `domain/savings/SavingsGamificationEngine.kt:129-130` | Low | Validation | `goal.currentAmount / goal.targetAmount` is unguarded; `targetAmount == 0.0` can produce `Infinity`/`NaN` progress. | D | CONFIRMED | Guard zero/negative targets before division. |
| 13 | `domain/savings/SavingsModels.kt:missing` | Low | Architecture | The batch plan expects a shared savings model file, but savings DTOs/enums are embedded inside engine implementations. | R | CONFIRMED | Extract shared savings models or update the plan/docs to match reality. |
| 14 | `domain/health/FinancialHealthScoreV2.kt:107-108,424-437,499-527` | Medium | Trend | Trend calculation compares against `getMostRecent()` without excluding the current period, so recalculations can compare a score to the same period’s previous snapshot instead of the prior period. | B | CONFIRMED | Compare against the latest different period (or last closed period), then save. |
| 15 | `domain/health/FinancialHealthScoreV2.kt:339-364` | High | Aggregation | Budget-adherence scoring sums all budgets and all overspend, so overall and category budgets can be double-counted. | R | CONFIRMED | Normalize budget selection once and reuse that policy here. |
| 16 | `domain/health/FinancialHealthModels.kt:missing` | Low | Architecture | Planned shared health-score models are embedded in `FinancialHealthScoreV2.kt` instead of a dedicated model file. | R | CONFIRMED | Extract models or update architecture docs. |
| 17 | `domain/health/HealthScoreModels.kt:missing` | Low | Architecture | Legacy health-score DTOs remain embedded in `FinancialHealthCalculator.kt`; the planned model file is absent. | R | CONFIRMED | Extract models or remove the stale plan entry. |
| 18 | `domain/health/FinancialHealthCalculator.kt:91-99,134-147,181-194` | High | Logic | Today/week/month spending totals and volatility use all expense rows, so deposits and transfers are treated as spend and can materially distort legacy health scores. | B | CONFIRMED | Restrict these calculations to actual spending transaction types. |
| 19 | `domain/health/FinancialHealthCalculator.kt:103-107,150-154,197-201` | Low | Logic | `budgetStatuses.all { ... }` on an empty list returns `true`, so users with no budgets still receive the “all budgets on track” bonus. | R | CONFIRMED | Require `budgetStatuses.isNotEmpty()` before granting that bonus. |
| 20 | `domain/health/FinancialHealthCalculator.kt:217-237` | Low | Maintainability | `calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it, so the API implies period-aware behavior that is not implemented. | R | CONFIRMED | Remove the parameter or make the logic period-aware. |
| 21 | `domain/health/FinancialHealthCalculator.kt:131-132,395-414` | Medium | Time boundary | Legacy week calculations use locale-dependent `Calendar.firstDayOfWeek`, while the rest of the app standardizes on Monday via `TimePeriodUtils`. | D | CONFIRMED | Reuse `TimePeriodUtils.getStartOfWeek()`/`getWeekRange()` consistently. **[RESOLVED BY A.5]** |
| 22 | `domain/health/FinancialHealthCalculator.kt:103-104` | Low | Logic | `calculateTodayScore()` increments `noSpendStreak` locally when `spentToday == 0`, even though the caller already passes an up-to-date streak, so today’s no-spend bonus is double-counted. | D | CONFIRMED | Trust the supplied streak value instead of adding `+1` locally. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/health/FinancialHealthScoreV2.kt:76-97,373-383` | Medium | Logic | Bill reliability is computed from only the current evaluation period’s expenses, but `RecurringExpenseEngine.getPatterns(expenses)` needs multi-occurrence historical data. For typical monthly bills, this usually yields no patterns and a synthetic default score of `75`, making 20% of the KPI largely non-evidence-based. | Build reliability from multi-month historical expense data (or `getPatterns()` over repository history), then evaluate current-period payment consistency separately. |
| 2 | `domain/savings/AutomatedSavingsRuleEngine.kt:219-220` | Medium | Time boundary | `WEEKLY_NO_SPEND` uses a rolling `now - 7 days` window instead of a stable calendar week / last completed week. Even after deduplication, the rule would still represent a sliding window rather than a weekly cycle. | Evaluate a fixed weekly range (for example, the last completed Monday-Sunday period) and key persistence to that stable period. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #2 | `domain/savings/AutomatedSavingsRuleEngine.kt:296` | The month key is internal only; zero-based/non-padded formatting is ugly but functionally safe because the same helper generates and compares all keys. |
| 2 | Debugger #3 | `domain/savings/AutomatedSavingsRuleEngine.kt:53,299-304` | This is stale in-memory state, not an accumulating leak. Old keys are pruned on the next evaluation, and nothing grows while the engine is idle. |
| 3 | Debugger #6 | `domain/savings/SmartSavingsEngine.kt:193-197` | The formula is heuristic and poorly explained, but there is no approved spec proving the `0.3` factor is incorrect. |
| 4 | Debugger #12 | `domain/health/FinancialHealthScoreV2.kt:100-105` | Flooring with `toInt()` is a scoring-policy choice; no rounding rule in the approved batch scope says this must round to nearest. |
| 5 | Debugger #13 | `domain/health/FinancialHealthScoreV2.kt:182-195` | The method intentionally returns a degraded result and embeds an explicit warning recommendation. The real problem is downstream rendering, not the local catch block by itself. |
| 6 | Debugger #15 | `domain/health/FinancialHealthScoreV2.kt:244-250` | The `+1` is deliberate because current-day spend is already included in `observedSpend`; excluding the partial current day would undercount elapsed coverage. |
| 7 | Debugger #16 | `domain/health/FinancialHealthCalculator.kt:250,272,297` | Current repository code rejects zero-amount budgets on add/update, so the reported divide-by-zero requires invalid persisted data outside normal reachable flows. |
| 8 | Debugger Cross #1 | `domain/savings/SmartSavingsEngine.kt:44-199`, `domain/health/FinancialHealthScoreV2.kt:89-97,232-306` | These components intentionally model different concepts (save-now recommendation vs health-score components). Divergent outputs alone do not make the pipeline incorrect. |
| 9 | Debugger Cross #5 | `domain/savings/SmartSavingsEngine.kt:180-184`, `domain/forecasting/MonteCarloSpendingSimulator.kt:49-132` | Passing `budgetAmount = null` is an explicit unconstrained-simulation choice. The unused discretionary context is weak design, but the reported cross-component defect is not established from current requirements. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | SmartSavingsEngine → FinancialHealthScoreV2 → MonthlySavingsSweepUseCase | High | Consistency | Budget headroom is aggregated with conflicting rules: the sweep use case avoids overall/category double counting, while savings and health engines do not. | `domain/savings/SmartSavingsEngine.kt`; `domain/health/FinancialHealthScoreV2.kt`; `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` | Centralize budget selection/aggregation and reuse it everywhere. |
| 2 | FinancialHealthCalculator ↔ FinancialHealthScoreV2 ↔ ComputeDashboardWidgetsUseCase | Medium | Architecture | The dashboard renders two incompatible “financial health” KPIs side by side. They use different formulas, different transaction filters, and different week definitions, so users can receive contradictory health signals. | `domain/health/FinancialHealthCalculator.kt`; `domain/health/FinancialHealthScoreV2.kt`; `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Choose a canonical health score or clearly mark one as legacy/experimental. |
| 3 | AutomatedSavingsRuleEngine → SavingsGamificationEngine | Medium | Data flow | Automated savings produces transient `RuleExecution` objects, but gamification has no contribution-event source, so automated savings can never feed streaks or achievements. | `domain/savings/AutomatedSavingsRuleEngine.kt`; `domain/savings/SavingsGamificationEngine.kt` | Persist savings contribution events and consume that shared history in gamification. |
| 4 | AutomatedSavingsRuleEngine ↔ SmartSavingsEngine | Low | Duplication | Essential/discretionary category classification is duplicated as hard-coded string sets in both engines. | `domain/savings/AutomatedSavingsRuleEngine.kt`; `domain/savings/SmartSavingsEngine.kt` | Move category essentiality into a shared policy/service. |
| 5 | Savings/Health engines ↔ planned `*Models.kt` files | Low | Structure | Shared DTOs/enums are embedded inside engine classes while the planned model files are absent, increasing coupling and documentation drift. | `domain/savings/AutomatedSavingsRuleEngine.kt`; `domain/savings/SmartSavingsEngine.kt`; `domain/savings/SavingsGamificationEngine.kt`; `domain/health/FinancialHealthScoreV2.kt`; `domain/health/FinancialHealthCalculator.kt` | Extract shared models into dedicated files or update the batch plan. |
| 6 | FinancialHealthScoreV2 → HealthScoreHistoryDao | Low | Concurrency | Trend determination and same-period upsert are not atomic. Concurrent calculations can compare against stale history and insert duplicate rows for the same period because `(periodStart, periodEnd)` is not unique. | `domain/health/FinancialHealthScoreV2.kt`; `data/database/dao/HealthScoreHistoryDao.kt`; `data/database/entity/HealthScoreHistory.kt` | Add a unique DB constraint for period keys and perform compare/upsert in one transaction. |
| 7 | FinancialHealthScoreV2 → ComputeDashboardWidgetsUseCase | Medium | Error handling | V2 swallows fatal calculation exceptions into a neutral-looking `50` result, and the dashboard always renders that fallback as a normal widget. Operational failures therefore surface as legitimate health data. | `domain/health/FinancialHealthScoreV2.kt`; `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Return structured error state (or `null`) for fatal failures and suppress/decorate the widget accordingly. |

## Summary
- Total verified issues: 29
- Confirmed: 29 (Critical: 0, High: 8, Medium: 8, Low: 13)
- False positives: 9
- Missed issues found: 2
- Files affected: 8/8 scope entries

## Key Patterns
- Event history is missing in multiple places, so several “smart” features are still driven by placeholders or transient in-memory state (`RuleExecution`, savings streaks, bill reliability).
- Budget aggregation policy is inconsistent across the batch; overall and category budgets are sometimes treated as peers and sometimes as mutually exclusive views.
- Time-boundary logic is ad hoc (`now - 7 days`, locale-dependent week starts, same-period trend comparisons), which creates subtle but repeatable semantic drift.
- Model ownership has drifted from the batch plan: reusable DTOs/enums remain embedded in engine files instead of shared model files.
