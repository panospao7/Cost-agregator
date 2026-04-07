# Final Verification — Batch 46: Domain Models — Dashboard & Recommendation

## Scope
- `com/yourname/expensetracker/domain/model/BlockPartyDay.kt`
- `com/yourname/expensetracker/domain/model/CategoryBreakdown.kt`
- `com/yourname/expensetracker/domain/model/CategoryInfo.kt`
- `com/yourname/expensetracker/domain/model/FinancialForecast.kt`
- `com/yourname/expensetracker/domain/model/PeriodDrillDownState.kt`
- `com/yourname/expensetracker/domain/model/PeriodRange.kt`
- `com/yourname/expensetracker/domain/model/PeriodTotal.kt`
- `com/yourname/expensetracker/domain/model/PlannedExpense.kt`
- `com/yourname/expensetracker/domain/model/RecurringPattern.kt`
- `com/yourname/expensetracker/domain/model/Result.kt`
- `com/yourname/expensetracker/domain/model/SavingsGoal.kt`
- `com/yourname/expensetracker/domain/model/UpcomingItem.kt`
- `com/yourname/expensetracker/domain/model/UiText.kt`
- `com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt`
- Supporting files read during verification:
  - `com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
  - `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
  - `com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt`
  - `com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`
  - `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`
  - `com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
  - `com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
  - `com/yourname/expensetracker/domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`
  - `com/yourname/expensetracker/domain/model/dashboard/FinancialWeather.kt`
  - `com/yourname/expensetracker/domain/model/dashboard/DomainDayBudgetStatus.kt`
  - `com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
  - `com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`
  - `com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
  - `com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt`
  - `com/yourname/expensetracker/data/database/entity/Expense.kt`
  - `com/yourname/expensetracker/data/database/entity/SavingsGoal.kt`
  - `com/yourname/expensetracker/data/database/entity/PlannedExpense.kt`
  - `com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt`
  - `com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt`
  - `com/yourname/expensetracker/ui/components/RetroBudgetBlockPartyCard.kt`
  - `com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt`
  - `com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
  - `com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
  - `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
  - `com/yourname/expensetracker/ui/screens/recurring/RecurringExpensesScreen.kt`
  - `com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/model/BlockPartyDay.kt:3,17` | High | Architecture violation | `BlockPartyDay` is still a domain model that imports and exposes the Room `Expense` entity via `topTransactions`. The issue is real, but it is an architectural boundary problem rather than a runtime-critical defect. | B | DOWNGRADED | Replace `List<Expense>` with a small domain preview DTO and map at the repository/use-case boundary. |
| 2 | `domain/model/FinancialForecast.kt:10` | Low | Localization / contract inconsistency | `actionableInsights` is `List<String>` while the same feature family already uses `UiText`. This is a real contract inconsistency, but no current call site demonstrates a production failure. | R | DOWNGRADED | Use `List<UiText>` or typed message keys and resolve strings in presentation. |
| 3 | `domain/model/FinancialForecast.kt:13-17` | Low | Sentinel value API smell | `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as a sentinel. No current consumer misuses `horizon.days`, so the reports overstated the immediate risk, but the model contract is still brittle. | B | DOWNGRADED | Model the calendar-bound case explicitly (sealed type, nullable days, or `effectiveDays(...)`). |
| 4 | `domain/model/PeriodRange.kt:3-9` | Medium | Missing invariant | `PeriodRange` accepts `end < start`, which yields negative `duration` and a nonsensical `contains()` contract. This is a real model invariant gap. | B | CONFIRMED | Add `require(end >= start)` in `init` or normalize before construction. |
| 5 | `domain/model/PlannedExpense.kt:6` | Low | Missing validation | `PlannedExpense.amount` has no domain-level non-negative invariant. The primary UI path currently rejects `amt <= 0`, so the impact is lower than reported, but the model still permits invalid values from other construction paths. | R | DOWNGRADED | Enforce `amount >= 0` in the model/factory or introduce a separate type for credits/income. |
| 6 | `domain/model/RecurringPattern.kt:19-29` | Low | Temporal abstraction | `RecurrenceFrequency` mixes approximate fixed-day values for calendar frequencies and exposes `intervalInMs` derived from them. The active infinite-loop scenario reported by the debugger is not present, but the API is still misleading for monthly/quarterly/annual use. | B | DOWNGRADED | Remove `intervalInMs` for calendar-based frequencies or move date math to a calendar-aware helper. |
| 7 | `domain/model/SavingsGoal.kt:10` | Low | Sentinel default / contract inconsistency | `SavingsGoal.createdAt` defaults to `0L` while entity mappings generally provide real timestamps. I did not find a verified production path currently relying on the default, so severity is lower, but the default is still wrong if used. | B | DOWNGRADED | Remove the default or supply it from an injected clock at creation time. |
| 8 | `domain/model/UpcomingItem.kt:13` | Medium | Identity collision | `UpcomingItem.Recurring.id` uses only `merchantName`, so multiple recurring rules for the same merchant collapse to the same logical identity. Current `FinancialWeatherCard` rendering does not key on `item.id`, but the collision is still real at the model-contract level. | B | DOWNGRADED | Use `pattern.id` when available, otherwise build a composite stable key. |
| 9 | `domain/model/budget/MonteCarloBudgetImpact.kt:17-47` | Medium | Domain/UI coupling | `MonteCarloBudgetImpact` stores preformatted UI strings and hardcodes EUR formatting in the domain layer. The locale-specific subclaim in the debugger report was overstated, but the broader presentation/currency coupling is real. | B | DOWNGRADED | Keep raw values only and format currency/messages in presentation using the selected locale/currency. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/logic/SynthesisEngine.kt:331-389` | Medium | Ranking / correctness | `calculateBlockPartyData()` sorts and truncates `topTransactions` by raw `Expense.amount`, while the rest of the budgeting pipeline uses `effectiveAmount`. Shared-expense days can therefore surface the wrong “top” transactions and wrong ordering. | Sort and take by `effectiveAmount`, not raw `amount`, before building `topTransactions`. |
| 2 | `domain/logic/NarrativeGenerator.kt:3,28-145` | Medium | Layer violation / localization | `NarrativeGenerator` lives in the domain layer but imports app `R` and constructs fully formatted user-facing strings directly. That bypasses the `UiText` abstraction and keeps Android resource concerns in domain logic. | Emit `UiText`/message keys end-to-end and move final resource resolution/formatting to presentation. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `D#2` | `domain/model/RecurringPattern.kt:26,28-29` | The claimed active infinite-loop/divide-by-zero path is not present. `RecurringExpenseEngine` explicitly excludes `IRREGULAR` before using `frequency.days`, and `RecurringExpenseRepository` also special-cases `IRREGULAR`. The remaining problem is only the lower-severity API smell retained in Verified Issue #6. |
| 2 | `D#5` | `domain/model/UpcomingItem.kt:13-17` | Kotlin `data class copy()` re-invokes the constructor, so body properties are recomputed from the new constructor argument. `Recurring.copy(pattern = newPattern)` does not retain stale derived values. |
| 3 | `D#6` | `domain/model/UpcomingItem.kt:23-27` | Same as above for `UpcomingItem.Planned`: copied instances recompute body properties from the new `expense` argument. |
| 4 | `D#8` | `domain/model/budget/MonteCarloBudgetImpact.kt:46` | The locale-specific decimal-separator claim was overstated: this helper is only used for display text, and locale-dependent separators are not inherently wrong. The real problem is domain-layer formatting and hardcoded EUR, retained as Verified Issue #9. |
| 5 | `D#10` | `domain/model/RecurringPattern.kt:3` | Unused `LocalDate` import is style cleanup, not a functional defect. |
| 6 | `D#12` | `domain/model/Result.kt:10-11` | `data object` vs `object` inconsistency has no demonstrated functional impact in this sealed result model. |
| 7 | `D#13` | `domain/model/FinancialForecast.kt:43-48` | Co-locating `WeatherNarrative` in `FinancialForecast.kt` is a file-organization preference, not a verified bug. |
| 8 | `D#14` | `domain/model/CategoryBreakdown.kt:7` | `Float` percentage values are consistent with adjacent analytics/UI contracts, and no concrete correctness issue was demonstrated. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Block-party transaction preview pipeline | High | Layer leakage | `BlockPartyDay` carries `Expense`, `ComputeDashboardWidgetsUseCase` maps it to `DomainExpenseSummary`, and `DashboardWidgetUiMapper` then recreates an `Expense` for UI rendering. The pipeline crosses the domain boundary twice and keeps Room-entity shape alive end-to-end. | `domain/model/BlockPartyDay.kt`, `domain/logic/SynthesisEngine.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `ui/mappers/DashboardWidgetUiMapper.kt`, `ui/components/BudgetBlockPartyCard.kt` | Keep a lightweight domain/UI preview DTO through the whole pipeline instead of reusing/rebuilding `Expense`. |
| 2 | Category breakdown models | Medium | Duplicate model / name collision | Two different `CategoryBreakdown` types exist with overlapping semantics and are imported by different screens/components. This is a real maintenance hazard, even though I did not find a concrete runtime failure. | `domain/model/CategoryBreakdown.kt`, `domain/analytics/AnalyticsModels.kt`, `domain/analytics/TotalsAggregationEngine.kt`, `data/repository/AnalyticsRepository.kt`, `ui/components/CategoryBreakdownSheet.kt`, `ui/components/CategoryDonutChart.kt` | Consolidate or rename the analytics-specific variant and keep mapper boundaries explicit. |
| 3 | Period range models | Medium | Duplicate model / name collision | `domain.model.PeriodRange` and `domain.analytics.PeriodRange` coexist with different semantics, forcing import discipline and increasing wrong-type risk in analytics/AI/budget flows. | `domain/model/PeriodRange.kt`, `domain/analytics/AdvancedAnalyticsModels.kt`, `domain/analytics/AdvancedAnalyticsEngine.kt`, `domain/ai/model/FinancialQueryModels.kt`, `domain/budget/BudgetCalculator.kt`, `ui/screens/analytics/AnalyticsViewModel.kt` | Rename one type or introduce an explicit conversion layer with distinct names. |
| 4 | Forecast / narrative text pipeline | Medium | Inconsistent localization boundary | This feature family mixes raw `String`, `UiText`, hardcoded currency text, and direct Android `R` usage in domain logic. The result is two competing text pipelines in the same dashboard/recommendation feature. | `domain/model/FinancialForecast.kt`, `domain/model/UiText.kt`, `domain/logic/NarrativeGenerator.kt`, `domain/model/budget/MonteCarloBudgetImpact.kt`, `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`, `data/repository/FinancialWeatherRepository.kt` | Standardize on typed domain text tokens (`UiText` or message keys) and keep final formatting/resource resolution in presentation. |
| 5 | Savings-goal model boundary | Medium | Duplicate model / boundary bypass | Domain and entity `SavingsGoal` / `GoalProtectionLevel` definitions differ, and `SavingsGoalsViewModel` imports entity types directly instead of consuming domain models. | `domain/model/SavingsGoal.kt`, `data/database/entity/SavingsGoal.kt`, `data/repository/SavingsGoalRepository.kt`, `ui/screens/savings/SavingsGoalsViewModel.kt` | Keep Room entities internal to the data layer and expose only domain models to UI/viewmodels. |
| 6 | Forecast timestamp generation | Low | Testability | `FinancialForecast.generatedAt` is populated with `Instant.now()` in `SynthesisEngine` rather than the injected `TimeProvider`, so forecast outputs are not fully deterministic under test. | `domain/model/FinancialForecast.kt`, `domain/logic/SynthesisEngine.kt` | Use `Instant.ofEpochMilli(timeProvider.now())`. |

## Summary
- Total verified issues: 11
- Confirmed: 11 (Critical: 0, High: 1, Medium: 5, Low: 5)
- False positives: 8
- Missed issues found: 2
- Files affected: 10/38 analyzed files (including 8/14 scoped domain-model files)

## Key Patterns
- Domain contracts in this batch still leak both data-layer concerns (`Expense`) and presentation concerns (formatted strings, Android resources).
- Several models rely on sentinel/default values instead of explicit modeling (`REST_OF_MONTH.days = 0`, `createdAt = 0L`, `IRREGULAR.days = 0`).
- Adjacent packages define duplicate concepts (`CategoryBreakdown`, `PeriodRange`, `SavingsGoal`), increasing mapper churn and import ambiguity.
- Time and money abstractions are inconsistent: some paths are calendar-aware, while others still expose approximate fixed-day or preformatted-currency APIs.
