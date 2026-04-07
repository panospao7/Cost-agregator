# Final Verification — Batch 05: Use Cases - Dashboard, Expense, Forecast

## Scope
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `com/yourname/expensetracker/domain/usecase/expense/CategorizeExpenseUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/expense/DetectDuplicateExpenseUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/budget/CalculateBudgetStatusUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`
- `com/yourname/expensetracker/domain/text/DashboardTextKeys.kt`

Supporting validation files read during verification:
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `com/yourname/expensetracker/data/repository/PromptStateRepository.kt`
- `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
- `com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- `com/yourname/expensetracker/domain/intelligence/CrossSourceDeduplication.kt`
- `com/yourname/expensetracker/domain/lifestyle/LifestyleInflationDetector.kt`
- `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- `com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- `com/yourname/expensetracker/domain/model/SavingsGoal.kt`
- `com/yourname/expensetracker/domain/model/BlockPartyDay.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt`
- `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
- `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
- `com/yourname/expensetracker/ui/components/FinancialRunwayCard.kt`
- `com/yourname/expensetracker/ui/components/RetroBudgetBlockPartyCard.kt`
- `com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt`
- `com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `data/repository/DashboardContractsAdapter.kt:49-54` | High | Time-window logic | `observeDashboardExpenses()` captures the current month once with `System.currentTimeMillis()`. After a month rollover, the expense flow keeps observing the old month until the flow is recreated. | R | CONFIRMED | Inject/use a reactive time source and switch the underlying expense query when the active month changes. |
| 2 | `domain/usecase/dashboard/DashboardDataProvider.kt:75-108` | High | Stale snapshot | `getProcessedDataFlow()` recalculates month boundaries only when `getAllDataFlow()` emits. If the app stays open across midnight/month-end with no upstream DB change, summary and category breakdown stay on the old month. | R | CONFIRMED | Add a day/month ticker flow or a reactive current-period key so analytics recompute on time rollover. |
| 3 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:653-656` | Medium | Business logic | When no overall budget exists, `SafeToSpend.amount` is populated with `ctx.monthSpent`, which is already-spent money, not money still safe to spend. | B | DOWNGRADED | Hide the widget, show an explicit “no budget” state, or leave the amount null/zero instead of reusing `monthSpent`. |
| 4 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:435-441` | Low | Data contract | Block Party maps `categoryName = expense.categoryId?.toString()`. The field is named `categoryName`, but the implementation stores an ID string and relies on downstream re-parsing. | R | DOWNGRADED | Carry a real category name, or rename the field to `categoryId` and update downstream mappers accordingly. |
| 5 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:138-145,183-205,237-307,351-355` | Medium | Performance | Money Radar gathers due bills, anomalies, and budget risk sequentially, re-fetches recurring patterns, and performs a separate income query during scoring despite the code comment claiming parallel collection. | B | CONFIRMED | Use `coroutineScope`/`async` for independent reads and reuse shared inputs (patterns, month window, income) across sub-calculations. |
| 6 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:250-256` | High | Forecast input bug | `getBudgetRisk()` uses `expenseRepository.getExpensesSince(monthStart)` and sums purchases without bounding to `now`, so future-dated purchases already entered later in the month inflate `spentToDate`. | R | CONFIRMED | Query `monthStart..now` explicitly, or filter `expense.date <= now` before summing. |
| 7 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt:237-243,351-355` | Medium | Temporal consistency | `compute()` captures `now`, but `getBudgetRisk()` and `getMonthlyIncome()` call `timeProvider.now()` again. Around midnight, Money Radar can mix different months in one render. | D | CONFIRMED | Pass the top-level `now` into all helper methods and reuse one month window throughout the computation. |
| 8 | `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt:46-63` | High | Duplicate detection | Candidate pruning never constrains transaction type, so deposits/transfers/withdrawals with matching amount/date/merchant can be treated as purchase duplicates. | R | CONFIRMED | Filter candidates by comparable transaction type/source before passing them to cross-source deduplication. |
| 9 | `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt:46-55` | High | Duplicate detection | The use case pulls candidates through `ExpenseRepository.getExpensesBetween()`, which delegates to `ExpenseDao.getExpensesBetween()` and excludes `isNotMine = 1`. Re-importing a transaction previously marked “not mine” can therefore create a duplicate row. | D | DOWNGRADED | Use a duplicate-detection query that can include `isNotMine` rows, or query by dedupe key / merchant+amount window without the ownership filter. |
| 10 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:85-94` | High | Domain mapping | Savings goals are all mapped with `GoalProtectionLevel.TRACKING`, dropping real `STRICT` and `WARNING` protection levels before forecasting. | R | CONFIRMED | Map the entity protection enum to the matching domain enum instead of hardcoding `TRACKING`. |
| 11 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:110-128` | High | Forecast quality | The standalone forecast use case feeds `SynthesisEngine` placeholder inputs (`pastSumDaily = emptyList()`, `previousMonthTotal = null`, `averageMonthlyTotal = null`), so projected history and discretionary forecasts lose the real spending baseline. | B | CONFIRMED | Build cumulative month-to-date spending points and baseline totals from actual expense history before calling `synthesize()`. |
| 12 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:117-118` | High | Risk classification | `paceStatus = PaceStatus.ON_PACE` is hardcoded. This suppresses `OVER_PACE` risk escalation in `SynthesisEngine.determineRiskLevel()` and underreports forecast risk. | D | UPGRADED | Compute the actual pace status from real spending history (or reuse `InsightsEngine` / `SpendingPaceCalculator`). |
| 13 | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:41-44,122-132` | Medium | Anti-nag | Cooldown checks `hasPromptedRecently(...)`, but `evaluateAndPrompt()` never calls `recordPrompt(...)`, so the same recommendation can be shown repeatedly on refresh if the user takes no explicit action. | R | DOWNGRADED | Record prompt display when the recommendation is surfaced, or have the caller record display state immediately. |
| 14 | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt:74-79` | Medium | Unit mismatch | The heuristic treats `abs(savingsRate) <= 1.0` as a ratio and multiplies by 100, but `LifestyleInflationDetector` already emits `savingsRate` as a percentage. A true `0.5%` rate becomes `50%`. | B | CONFIRMED | Remove the heuristic and define one explicit percentage-vs-ratio contract across detector, use case, and tests. |
| 15 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:109-113` | High | Risk underestimation | Sweep Monte Carlo input hardcodes `knownUpcoming = 0.0`, so deterministic upcoming obligations are ignored when computing the safety buffer. | B | CONFIRMED | Include recurring/planned upcoming obligations in the Monte Carlo input via a shared obligation aggregation helper. |
| 16 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:233-295` | High | Allocation logic | Goal allocations are never capped by each goal’s remaining gap (`targetAmount - currentAmount`), so the recommendation can overfund goals past their targets. | R | CONFIRMED | Cap each goal by its remaining gap, then redistribute leftover sweep amount or leave the remainder unallocated. |
| 17 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt:265-278` | Medium | Allocation policy | The last goal bypasses `MAX_SINGLE_ALLOCATION_PERCENT` by receiving the entire remaining amount, so the documented concentration cap is not actually enforced. | D | CONFIRMED | Apply the cap to all passes and redistribute leftover funds in a second pass (or keep some amount unallocated). |
| 18 | `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt:106-113` | Medium | Audit metadata | `createWarrantyForReview()` always passes `autoDetect = true`, even when `userModifiedData` is supplied, so user-corrected warranties are persisted as auto-detected. | R | CONFIRMED | Set `autoDetect = false` for user-edited confirmations, or persist a distinct “confirmed from auto-detect” provenance state. |
| 19 | `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt:49-61,112-118` | Medium | Messaging | High-risk results can produce messages like “High risk of exceeding budget by €0.00” when probability is high but median spend is still under budget. | R | CONFIRMED | Special-case zero expected overrun, or use a tail-based excess metric for the displayed amount. |
| 20 | `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt:16-21,79-100` | Low | Documentation | The KDoc risk-tier descriptions do not match the actual threshold cascade implemented in `determineRiskTier()`, which can mislead future maintainers. | D | DOWNGRADED | Update the KDoc to describe the implemented escalation logic precisely, or change the code to match the documented rule set. |
| 21 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:365-381` | Medium | Runway logic | If the user has discretionary budget remaining but `averageDailyBurn == 0.0` (for example, early in the month with no purchases yet), runway days become `0` and the widget reports `CRITICAL`. | D | CONFIRMED | Treat zero burn with remaining budget as “full remaining runway” (or an unknown runway state), not immediate exhaustion. |
| 22 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:302-308,669` | Medium | Snapshot consistency | `monthSpent` comes from `summary.totalSpent`, while `todaySpent` and `weekSpent` are recomputed from `purchases`. Because these values come from different reactive paths, the period summary can be internally inconsistent. | D | CONFIRMED | Derive all period totals from the same snapshot, or ensure summary and expense lists are produced atomically from one source. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:32-38,97-128` | Medium | Stale time boundary | The forecast flow recomputes `now`, `monthStart`, and `currentDay` only when one of the five repository flows emits. If the app stays open across a day/month rollover with no data change, the forecast remains on the old period. | Add a reactive time/ticker flow and combine it with the repository flows so forecasts refresh on clock boundaries. |
| 2 | `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt:106-113,168-176,195-247` | High | Review flow breakage | Medium-confidence extraction persists a `PENDING_REVIEW` draft, but `createWarrantyForReview()` tries to insert a new warranty with the same unique `receiptId`. That conflicts with the existing draft and returns `AlreadyExists` instead of finalizing the reviewed warranty. | Update the existing draft in place (merge user edits, set `needsReview = false`, activate it) instead of inserting a second row. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #1 | `ComputeDashboardWidgetsUseCase.kt:363-364` | `FinancialForecast.components` is non-null by type and the fallback forecast also initializes it. The safe-call is redundant, but it does not create an actual runtime or logic bug in the current code. |
| 2 | Debugger #2 | `ComputeDashboardWidgetsUseCase.kt:298-299` | `ctx.daysRemaining` is only displayed in the Safe-to-Spend UI. It is not used as a divisor anywhere in this path, and “remaining days excluding today” is a valid interpretation. |
| 3 | Debugger #9 | `CalculateFinancialForecastUseCase.kt:114` | `projectedTotal = monthSpent` is poor modeling, but `projectedTotal` is not consumed by `SynthesisEngine` in this use case, so it does not currently change the returned `FinancialForecast`. |
| 4 | Debugger #12 | `CalculateFinancialForecastUseCase.kt:106` | The inclusive upper bound is only an exact-`now` convention difference and does not create a demonstrated correctness issue in this flow. |
| 5 | Debugger #14 | `MonthlySavingsSweepUseCase.kt:246-258` | The code already handles `totalUrgency == 0.0` by falling back to equal allocation, so the described edge case does not break behavior. |
| 6 | Debugger #15 | `LifestyleSavingsPromptUseCase.kt:98-99` | Forcing a minimum 1% suggestion is a product-policy choice. The current contracts do not prove that a 0%/negative savings-rate user must never receive a nudge. |
| 7 | Debugger #17 | `AutoCreateWarrantyFromReceiptUseCase.kt:82-87` | `ReceiptRepository` explicitly treats `LowConfidence` as “draft persisted, needs review.” The return type is awkward, but the current caller does not mis-handle it. |
| 8 | Debugger #18 | `AutoCreateWarrantyFromReceiptUseCase.kt:45` | Instantiating `WarrantyTextExtractor()` directly is a testability/design concern, not an actual functional defect in the current implementation. |
| 9 | Debugger #19 | `ComputeDashboardWidgetsUseCase.kt:206-229` | The `Calendar` instance is created inside a per-call `ComputeContext`; it is not shared mutable state across concurrent invocations. |
| 10 | Debugger #22 | `CalculateFinancialForecastUseCase.kt:32-129` | Recomputing on every upstream emission is standard reactive behavior. The report did not demonstrate a concrete correctness bug or measured performance regression requiring debounce/conflate here. |
| 11 | Debugger #23 | `MonthlySavingsSweepUseCase.kt:199` | The exact-`now` inclusive/exclusive difference is an insignificant millisecond-boundary convention, not a material sweep-calculation defect. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `DashboardContractsAdapter → DashboardDataProvider → Home dashboard` | High | Period drift | The dashboard uses incompatible “current month” mechanisms: the adapter freezes the expense period at subscription time, while processed analytics only refresh when upstream data changes. Around month rollover, different dashboard sections can show different months or remain stale. | `data/repository/DashboardContractsAdapter.kt`, `domain/usecase/dashboard/DashboardDataProvider.kt`, `ui/screens/home/HomeViewModel.kt` | Centralize current-period resolution behind a reactive time key shared by all dashboard inputs. |
| 2 | `DashboardDataProvider → ComputeDashboardWidgetsUseCase → ComputeMoneyRadarUseCase` | Medium | Snapshot inconsistency | Most widgets use `ProcessedDashboardData`, but Money Radar is recomputed from fresh repository reads inside widget assembly, so one dashboard render can mix different DB snapshots. | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`, `ui/screens/home/HomeViewModel.kt` | Feed Money Radar from the already-fetched dashboard snapshot, or compute all dashboard widgets from one aggregation layer. |
| 3 | `MonthlySavingsSweepUseCase → ComputeDashboardWidgetsUseCase → HomeScreen` | Medium | Dead-end pipeline | `DashboardWidget.SavingsSweepPrompt` exists, but widget assembly never emits it and `HomeScreen` currently renders that widget as an empty placeholder. The savings-sweep feature stops outside the dashboard path. | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `ui/screens/home/HomeScreen.kt` | Either wire sweep recommendations into widget assembly and render them, or remove the dead contract until the feature is integrated. |
| 4 | `CalculateFinancialForecastUseCase ↔ ComputeMoneyRadarUseCase ↔ MonthlySavingsSweepUseCase` | High | Obligation policy drift | Forecast-related paths use inconsistent obligation models: standalone forecast includes recurring/planned/goals, Money Radar includes only near-term recurring bills, and sweep recommendations hardcode `knownUpcoming = 0.0`. Users can receive conflicting risk signals. | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`, `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`, `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` | Extract a shared obligation aggregation policy and reuse it across forecast, radar, and sweep logic. |
| 5 | `DetectDuplicateExpenseUseCase → ExpenseRepository → ExpenseDao` | High | Candidate filtering | Duplicate detection relies on a generic date-window query that silently filters out `isNotMine` rows before cross-source matching. The repository/DAO contract is too narrow for dedupe use. | `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt`, `data/repository/ExpenseRepository.kt`, `data/database/dao/ExpenseDao.kt` | Add a dedicated duplicate-candidate query (or repository method) with explicit dedupe semantics instead of reusing the general expenses-between API. |

## Summary
- Total verified issues: 22
- Confirmed: 22 (Critical: 0, High: 10, Medium: 10, Low: 2)
- False positives: 11
- Missed issues found: 2
- Files affected: 10/13

## Key Patterns
- Time-driven logic is not consistently reactive; several flows only refresh when repositories emit, not when calendar boundaries change.
- Forecast-related use cases rely on placeholder or lossy inputs (`TRACKING`, `ON_PACE`, empty history) instead of shared canonical forecasting helpers.
- Dashboard assembly mixes precomputed snapshot data with fresh repository reads, creating internally inconsistent renders.
- Savings-sweep logic lacks a complete allocation/obligation model and is not fully wired through the dashboard/UI pipeline.
- Warranty review handling persists drafts, but the confirmation lifecycle is incomplete and provenance metadata is not preserved correctly.
