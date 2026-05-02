# Phase 10: Analytics / Forecast / AI Cleanup — Implementation Plan

**Date**: 2026-05-02
**Source Audit**: `docs/analyses and debug master/analytics-forecast-cleanup-audit.md`
**Scope**: 190 files (116 source + 74 test) across analytics, forecasting, AI/ML, savings, health
**Strategy**: 9 sequential batches ordered by dependency — correctness first, then type-safety, then polish

---

## Table of Contents
1. [Batch 1: Fix Double-Counting in Forecasting](#batch-1-fix-double-counting-in-forecasting-critical)
2. [Batch 2: Adopt PeriodRange & PeriodKind](#batch-2-adopt-periodrange--periodkind-high)
3. [Batch 3: Currency-Aware BudgetForecastingEngine](#batch-3-currency-aware-budgetforecastingengine-high)
4. [Batch 4: MoneyAmount Phase A — Top-Level Models](#batch-4-moneyamount-phase-a--top-level-models-high)
5. [Batch 5: MoneyAmount Phase B — Sub-Models](#batch-5-moneyamount-phase-b--sub-models-high)
6. [Batch 6: MoneyAmount Phase C — Engine Implementations](#batch-6-moneyamount-phase-c--engine-implementations-high)
7. [Batch 7: MoneyAmount Phase D — UI Mappers](#batch-7-moneyamount-phase-d--ui-mappers-medium)
8. [Batch 8: Address Hardcoded Currency Values](#batch-8-address-hardcoded-currency-values-medium)
9. [Batch 9: Centralize Data Quality Reporting](#batch-9-centralize-data-quality-reporting-low)

---

## Batch 1: Fix Double-Counting in Forecasting (CRITICAL)

### Scope
- **In**: `ForecastInputAssembler`, `FinancialStressForecastEngine`, `MonthlySavingsSweepUseCase`
- **Out**: Nothing removed — only refactored to use `RecurringOccurrenceExpander` / `RecurringLifecycleCoordinator` as SSoT

### Context
`ForecastInputAssembler.assemble()` calls `mergeRecurringPatterns()` (which returns `RecurringPattern` list) AND independently passes `plannedExpenses: List<PlannedExpense>`. The KDoc at line 42-50 explicitly warns this causes double-counting. `FinancialStressForecastEngine.calculateRecurringOutflows()` uses ad-hoc calendar math (`while` loop rolling `nextDate` by period) instead of using the occurrence infrastructure. `MonthlySavingsSweepUseCase.calculateKnownUpcomingObligations()` independently sums recurring + planned without dedup.

### Files

**Modify (source):**
- `app/src/main/java/.../domain/forecasting/ForecastInputAssembler.kt`
- `app/src/main/java/.../domain/forecasting/FinancialStressForecastEngine.kt`
- `app/src/main/java/.../domain/usecase/savings/MonthlySavingsSweepUseCase.kt`

**Modify (tests):**
- `app/src/test/java/.../domain/forecasting/ForecastInputAssemblerTest.kt`
- `app/src/test/java/.../domain/forecasting/FinancialStressForecastEngineTest.kt`
- `app/src/test/java/.../domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`

### Implementation Steps

#### Step 1.1 — Add cross-deduplication by occurrenceKey to ForecastInputAssembler
- In `ForecastInputAssembler.assemble()`, after calling `mergeRecurringPatterns()` and mapping planned expenses, **cross-deduplicate** planned expenses that have a `sourceOccurrenceKey` matching any occurrence materialized by `RecurringLifecycleCoordinator.generateOccurrences()`.
- Query `RecurringOccurrenceDao` for all `PLANNED`/`PAID` occurrences in the forecast window. Build a `Set<String>` of occurrence keys.
- Filter `plannedExpenses` to exclude any whose `sourceOccurrenceKey` (or equivalent linking field) is present in that set.
- Remove the TODO comment at lines 42-50 and replace with a doc note confirming dedup is active.

#### Step 1.2 — Replace ad-hoc calendar math in FinancialStressForecastEngine
- In `calculateRecurringOutflows()`, replace the `while (nextDate in startDate..endDate)` loop with a call to `RecurringOccurrenceExpander.expand()` for each active manual recurring rule.
- Sum `candidate.totalAmount` from the returned `OccurrenceCandidate` list (already in the rule's currency — see note below about conversion).
- For detected patterns (not from `ManualRecurringExpense`), keep the existing calendar math as-is but add a clarifying comment that detected patterns don't have occurrence records.
- Retire the `TODO: Convert pattern.averageAmount to display currency` at line 224 by implementing currency conversion via `AnalyticsCurrencyNormalizer` (or at minimum convert the expanded candidates).

#### Step 1.3 — Deduplicate in MonthlySavingsSweepUseCase
- In `calculateKnownUpcomingObligations()`, instead of independently summing `recurringUpcoming + plannedUpcoming`, query materialized occurrences from `RecurringOccurrenceDao` for the month window and sum their amounts directly.
- Planned expenses that are linked to an occurrence (via `linkedExpenseId` or `sourceOccurrenceKey`) should be excluded.
- Remove the double-counting risk.

#### Step 1.4 — Update tests
- `ForecastInputAssemblerTest`: Add test cases with overlapping recurring rules and planned expenses, verify the dedup reduces the total to the correct non-double-counted value.
- `FinancialStressForecastEngineTest`: Update expected recurring outflow totals to reflect occurrence-based expansion. Add test that verifies the engine does NOT double-count planned expenses that were materialized from the same rule.
- `MonthlySavingsSweepUseCaseTest`: Add test verifying that known upcoming obligations no longer double-count when a manual recurring rule and a planned expense cover the same expense.

### Dependencies
- `RecurringOccurrenceExpander`, `RecurringOccurrenceDao`, and `RecurringLifecycleCoordinator` must be injectable/accessible. They are already injected in `ForecastInputAssembler` (line 57-58: `recurringLifecycleCoordinator`) and available in `FinancialStressForecastEngine`. Verify injection in `MonthlySavingsSweepUseCase`.

### Risks
- **Risk 1**: Occurrence expansion depends on manual recurring rules being active. If rules are not properly seeded, forecasting could under-count. Mitigation: keep fallback to detected patterns for rules that have no manual counterpart.
- **Risk 2**: The `MonthlySavingsSweepUseCase` may not have `RecurringOccurrenceDao` injected — may need constructor injection addition. Mitigation: audit the constructor before modifying.
- **Rollback**: Revert to ancestor commit. The `mergeRecurringPatterns()` + independent planned expenses pattern still exists as a working (if incorrect) fallback in git history.

### Validation Strategy
- Run `ForecastInputAssemblerTest`, `FinancialStressForecastEngineTest`, `MonthlySavingsSweepUseCaseTest` — all must pass.
- Run `BudgetForecastingEngineTest` to ensure no regression in the budget forecasting path (it doesn't use these assemblers, but verify).
- Manual: Create a manual recurring expense + a planned expense from the same rule. Verify forecast total and sweep recommendations don't double-count.

### Acceptance Criteria
- [ ] `ForecastInputAssembler.assemble()` cross-deduplicates planned expenses by occurrence key
- [ ] `FinancialStressForecastEngine.calculateRecurringOutflows()` uses occurrence expander for manual rules
- [ ] `MonthlySavingsSweepUseCase.calculateKnownUpcomingObligations()` deduplicates via occurrence records
- [ ] All existing tests pass; new dedup tests pass
- [ ] TODO comments referencing double-counting are removed or marked resolved

---

## Batch 2: Adopt PeriodRange & PeriodKind (HIGH)

### Scope
- **In**: All analytics, forecasting, savings, and health engines that use custom period types or raw `Long` pairs
- **Out**: `AnalyticsPeriod` enum, `AnalyticsPeriodRange` data class (replaced by `PeriodKind` + `PeriodRange`)

### Context
`domain.core.time.PeriodRange` and `PeriodKind` are defined but completely unused. Every engine and model uses its own period type: `AnalyticsPeriod` (WEEK, MONTH, QUARTER, YEAR, CUSTOM), `AnalyticsPeriodRange` (raw startMs/endMs), `TimePeriod` (TODAY through ALL), `ForecastHorizon`, and raw `startDate: Long, endDate: Long` parameter pairs. This is a pure type-system migration — no behavioral change.

### Files

**Modify (source):**
- `domain/analytics/AdvancedAnalyticsModels.kt` — replace `AnalyticsPeriod` + `AnalyticsPeriodRange` with `PeriodKind` + `PeriodRange`
- `domain/analytics/AnalyticsModels.kt` — replace `TimePeriod` enum usage with `PeriodKind`, replace raw `startMs/endMs` params with `PeriodRange`
- `domain/analytics/AdvancedAnalyticsEngine.kt` — update `getPeriodRange()` return type and all internal period calculations
- `domain/analytics/AdvancedAnalyticsDashboard.kt` — update `generateDashboardData()` to accept `PeriodRange` instead of `startDate: Long, endDate: Long`
- `domain/analytics/SpendingPaceCalculator.kt` — update params
- `domain/analytics/DayOfWeekAnalyzer.kt` — update params
- `domain/analytics/CategoryInsightEngine.kt` — update params
- `domain/analytics/MerchantInsightEngine.kt` — update params
- `domain/analytics/InsightsEngine.kt` — update params
- `domain/analytics/AnalyticsRepository.kt` — update repository methods
- `domain/forecasting/FinancialStressForecastEngine.kt` — update period params
- `domain/forecasting/ForecastInputAssembler.kt` — update horizon computation
- `domain/forecasting/CalculateFinancialForecastUseCase.kt` — update period param passing
- `domain/health/FinancialHealthScoreV2.kt` — replace raw `periodStart: Long, periodEnd: Long` with `PeriodRange`
- `domain/health/FinancialHealthCalculator.kt` — replace raw `Long` pairs with `PeriodRange`
- `domain/savings/SmartSavingsEngine.kt` — replace raw timestamps
- `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` — replace raw timestamps
- `domain/budget/BudgetForecastingEngine.kt` — replace raw pair from `budgetCalculator.calculatePeriodRange()`

**Mark as deprecated:**
- `domain/model/PeriodRange.kt` — add `@Deprecated("Use domain.core.time.PeriodRange instead")` annotation. This is the legacy 14-line untyped version.

**Modify (tests) — all affected test files (30+):**
- All test files in `app/src/test/.../domain/analytics/`
- `FinancialStressForecastEngineTest.kt`
- `ForecastInputAssemblerTest.kt`
- `CalculateFinancialForecastUseCaseTest.kt`
- `FinancialHealthScoreV2Test.kt`
- `FinancialHealthCalculator*.kt` test files
- `SmartSavingsEngineTest.kt`
- `MonthlySavingsSweepUseCaseTest.kt`
- `BudgetForecastingEngineTest.kt`

### Implementation Steps

#### Step 2.1 — Phase out AnalyticsPeriod and AnalyticsPeriodRange
- In `AdvancedAnalyticsModels.kt`:
  - Delete `enum class AnalyticsPeriod { WEEK, MONTH, QUARTER, YEAR, CUSTOM }`
  - Delete `data class AnalyticsPeriodRange(period, startMs, endMs, label, comparisonRange)`
  - All references to `AnalyticsPeriod` → `PeriodKind` (WEEK→THIS_WEEK, MONTH→THIS_MONTH, QUARTER→THIS_QUARTER, YEAR→THIS_YEAR, CUSTOM→CUSTOM)
  - All references to `AnalyticsPeriodRange` → `PeriodRange` from `domain.core.time`

#### Step 2.2 — Phase out TimePeriod enum
- In `AnalyticsModels.kt`:
  - Replace `enum class TimePeriod { TODAY, WEEK, MONTH, QUARTER, YEAR, ALL }` with `PeriodKind` imports
  - Map: TODAY→TODAY, WEEK→THIS_WEEK, MONTH→THIS_MONTH, QUARTER→THIS_QUARTER, YEAR→THIS_YEAR, ALL→CUSTOM
  - Update all models that hold a `TimePeriod` field

#### Step 2.3 — Replace raw Long pairs with PeriodRange in method signatures
- For each engine/repository listed above:
  - Replace `fun foo(startMs: Long, endMs: Long)` with `fun foo(range: PeriodRange)`
  - Use `range.startInclusiveMillis` and `range.endExclusiveMillis` internally
  - Use `range.kind` for period-type dispatch (replaces `when(period) { AnalyticsPeriod.WEEK -> ... }`)
  - Use `range.contains(timestamp)` for membership checks

#### Step 2.4 — Update AdvancedAnalyticsEngine.getPeriodRange()
- Change return type from `AnalyticsPeriodRange` to `PeriodRange`
- Construct `PeriodRange(kind=..., startInclusiveMillis=..., endExclusiveMillis=..., zoneId=..., label=...)` instead of `AnalyticsPeriodRange(...)`
- Remove `comparisonRange` from return — callers that need comparison should call `getPeriodRange()` twice with different `PeriodKind`s

#### Step 2.5 — Deprecate old domain.model.PeriodRange
- Add: `@Deprecated(message = "Use domain.core.time.PeriodRange instead", replaceWith = ReplaceWith("PeriodRange", "com.yourname.expensetracker.domain.core.time.PeriodRange"))`
- Search for remaining usages of the old `PeriodRange` and migrate them

#### Step 2.6 — Update all test files
- Replace all `AnalyticsPeriod.MONTH` with `PeriodKind.THIS_MONTH`, etc.
- Replace raw `AnalyticsPeriodRange(period, startMs, endMs, label, null)` with `PeriodRange(kind, startInclusiveMillis, endExclusiveMillis, label=label)`
- Ensure all test assertions still pass

### Dependencies
- `domain.core.time.PeriodKind` — already defined, no changes needed
- `domain.core.time.PeriodRange` — already defined, no changes needed
- `TimePeriodUtils` — already used to compute boundaries, no changes needed

### Risks
- **Risk 1**: `ALL` in `TimePeriod` has no direct `PeriodKind` equivalent. Use `CUSTOM` and compute the full range at call site. Mitigation: document the mapping.
- **Risk 2**: Some callers currently compare periods by enum equality. `PeriodKind` is also an enum, so equality still works. Verify no callers rely on specific enum ordinal values.
- **Risk 3**: `AnalyticsPeriodRange.comparisonRange` is lost. Mitigation: callers that need comparison should explicitly compute the previous period.
- **Rollback**: Git revert. This is a pure refactor with no behavioral change — all tests should pass identically before and after.

### Validation Strategy
- Run **all** test files listed in the audit (70+ tests). Every test must pass.
- Grep for remaining references to `AnalyticsPeriod`, `AnalyticsPeriodRange`, and `TimePeriod` — none should remain outside deprecated annotations.
- Manual smoke test: generate dashboard data, verify periods display correctly in UI.

### Acceptance Criteria
- [ ] `AnalyticsPeriod` and `AnalyticsPeriodRange` fully removed
- [ ] `TimePeriod` enum fully replaced by `PeriodKind`
- [ ] All method signatures use `PeriodRange` instead of raw `(Long, Long)` pairs
- [ ] `domain.model.PeriodRange` marked `@Deprecated`
- [ ] All 70+ tests pass without modification to assertions
- [ ] Zero remaining references to old period types (grep-verified)

---

## Batch 3: Currency-Aware BudgetForecastingEngine (HIGH)

### Scope
- **In**: `BudgetForecastingEngine`
- **Out**: Nothing removed

### Context
`BudgetForecastingEngine.generateForecast()` calls `expenseDao.getSpentAmount()` which uses `getCategorySpentInPeriod()` / `getTotalSpentBetween()` — raw SQL sums with **no currency normalization**. This is the **only** engine that bypasses `AnalyticsCurrencyNormalizer` entirely. Every other engine normalizes through it.

### Files

**Modify (source):**
- `app/src/main/java/.../domain/budget/BudgetForecastingEngine.kt`

**Modify (tests):**
- `app/src/test/java/.../domain/budget/BudgetForecastingEngineTest.kt`
- `app/src/test/java/.../domain/budget/BudgetForecastingEngineStubTest.kt`

### Implementation Steps

#### Step 3.1 — Inject AnalyticsCurrencyNormalizer and MultiCurrencyRepository
- Add `analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer` and `currencySettingsRepository: CurrencySettingsRepository` to the constructor.
- Resolve the home currency via `currencySettingsRepository.homeCurrency().first()`.

#### Step 3.2 — Normalize expense data before computation
- In `generateForecast()`:
  - Replace `getSpentAmount(budget, periodStart, elapsedEnd)` which uses raw DAO sum
  - Instead: query expenses via repository, convert to `ExpenseSnapshot`, normalize through `analyticsCurrencyNormalizer.normalizeSnapshots()`, then compute `spentToDate` from the normalized snapshots
  - For historical data: similarly normalize before computing trends

#### Step 3.3 — Add conversion warning tracking
- Track `AnalyticsConversionWarning` from the normalization result
- If `normalized.isPartial`, add a note to the forecast confidence score (reduce by a factor, e.g., `lossPercentage * 0.5`)

#### Step 3.4 — Update tests
- `BudgetForecastingEngineTest`: Update to mock `AnalyticsCurrencyNormalizer` and `MultiCurrencyRepository`. Verify that the engine passes normalized data through.
- `BudgetForecastingEngineStubTest`: Update stub setup.

### Dependencies
- `AnalyticsCurrencyNormalizer` — already injectable, used by 10+ other engines
- `MultiCurrencyRepository` — already injectable
- `BudgetForecastingEngine` already has `expenseDao` injected; replacing raw DAO calls with repository+normalizer pattern

### Risks
- **Risk 1**: The `BudgetForecastingEngine` currently uses `getCategorySpentInPeriod()` which is category-filtered. The normalizer path needs equivalent category filtering. Mitigation: filter `ExpenseSnapshot` by `categoryId` before normalization.
- **Risk 2**: Performance — adding normalization adds overhead. Mitigation: the normalizer is already fast (it's just iterating a list with currency lookups). This is not in a hot loop.
- **Rollback**: Git revert. The raw SQL path still exists in DAO.

### Validation Strategy
- Run `BudgetForecastingEngineTest` and `BudgetForecastingEngineStubTest` — must pass.
- Run `BudgetForecastingViewModelTest` to ensure UI layer still works.
- Manual: create budgets with multi-currency expenses, verify forecasts use normalized home-currency values.

### Acceptance Criteria
- [ ] `BudgetForecastingEngine` injects and uses `AnalyticsCurrencyNormalizer`
- [ ] `getSpentAmount()` no longer calls raw `expenseDao` sums directly for multi-currency scenarios
- [ ] Conversion warnings tracked in forecast output
- [ ] All budget forecasting tests pass
- [ ] Multi-currency expenses are correctly normalized before forecast computation

---

## Batch 4: MoneyAmount Phase A — Top-Level Models (HIGH)

### Scope
- **In**: Top-level output models: `AnalyticsDashboardData`, `FinancialForecast`, `MonteCarloResult`, `SpendingPace`, `SavingsRecommendation`, `SavingsSweepRecommendation`, `FinancialHealthResult`
- **Out**: `displayCurrency: String` fields — replaced by typed currency in `MoneyAmount`

### Context
All monetary values in analytics, forecasting, savings, and health models are raw `Double` with a separate `displayCurrency: String` field. `MoneyAmount(amount: Double, currency: CurrencyCode)` exists but is unused. This phase wraps top-level model monetary fields in `MoneyAmount`, replacing `displayCurrency: String` with the inherent `CurrencyCode` from within `MoneyAmount`.

### Files

**Modify (source):**
- `domain/analytics/AdvancedAnalyticsDashboard.kt` — `AnalyticsDashboardData`
- `domain/analytics/AnalyticsModels.kt` — `SpendingPace`
- `domain/model/FinancialForecast.kt` — `ForecastComponents`
- `domain/forecasting/MonteCarloResult.kt` — `MonteCarloResult`
- `domain/savings/SmartSavingsEngine.kt` — `SavingsRecommendation`, `GoalAllocation`
- `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` — `SavingsSweepRecommendation`
- `domain/health/FinancialHealthScoreV2.kt` — `FinancialHealthResult`
- `domain/health/FinancialHealthCalculator.kt` — `HealthScoreResult`

**Modify (tests):**
- `AdvancedAnalyticsDashboardTest.kt`
- `SpendingPaceCalculatorValidationTest.kt`, `SpendingPaceGoldenTest.kt`, etc.
- `FinancialStressForecastEngineTest.kt`
- `MonteCarloSpendingSimulatorTest.kt`
- `SmartSavingsEngineTest.kt`
- `MonthlySavingsSweepUseCaseTest.kt`
- `FinancialHealthScoreV2Test.kt`
- `FinancialHealthCalculator*.kt` test files
- `CalculateFinancialForecastUseCaseTest.kt`

### Implementation Steps

#### Step 4.1 — Create Conversion Helpers
- In a new or existing utility file (`MoneyAmountExtensions.kt`):
  - `MoneyAmount.Companion.fromDisplayCurrency(amount: Double, currency: String): MoneyAmount` — parses `currency: String` to `CurrencyCode` (or defaults)
  - `MoneyAmount.toDouble(): Double` — extracts `.amount` when currency context is already established
  - Extension for tests: `Double.toMoney(currency: CurrencyCode = CurrencyCode.EUR): MoneyAmount`

#### Step 4.2 — Update AnalyticsDashboardData
- `totalSpent: Double` → `totalSpent: MoneyAmount`
- `totalIncome: Double` → `totalIncome: MoneyAmount`
- `netCashflow: Double` → `netCashflow: MoneyAmount`
- Remove `displayCurrency: String` — derived from `totalSpent.currency`
- Add computed `val displayCurrency: CurrencyCode get() = totalSpent.currency`

#### Step 4.3 — Update SpendingPace
- `currentMonthSpent: Double` → `currentMonthSpent: MoneyAmount`
- `projectedTotal: Double` → `projectedTotal: MoneyAmount`
- `previousMonthTotal: Double?` → `previousMonthTotal: MoneyAmount?`
- `averageMonthlyTotal: Double?` → `averageMonthlyTotal: MoneyAmount?`
- Remove `displayCurrency: String` — derived from currency
- Keep `pacePercentage: Float` and `paceStatus: PaceStatus` as-is (non-monetary)

#### Step 4.4 — Update ForecastComponents (FinancialForecast.kt)
- `totalCommitted: Double` → `totalCommitted: MoneyAmount`
- `totalLikely: Double` → `totalLikely: MoneyAmount`
- `predictedDiscretionary: Double` → `predictedDiscretionary: MoneyAmount`
- `discretionaryBudget: Double` → `discretionaryBudget: MoneyAmount`
- All `List<Double>` spending point arrays → keep as `Double` (these are normalized already — add doc note)

#### Step 4.5 — Update MonteCarloResult
- `percentile10..percentile90: Double` → `MoneyAmount`
- `spentToDate: Double` → `spentToDate: MoneyAmount`
- `knownUpcoming: Double` → `knownUpcoming: MoneyAmount`
- `budgetAmount: Double?` → `budgetAmount: MoneyAmount?`
- Remove `displayCurrency: String` — derived from `spentToDate.currency`

#### Step 4.6 — Update Savings/Health top-level models
- `SavingsRecommendation.safeAmount: Double` → `safeAmount: MoneyAmount`
- `SavingsSweepRecommendation.safeSweepAmount: Double` → `safeSweepAmount: MoneyAmount`
- `FinancialHealthResult`: keep component `score: Int` as-is (non-monetary, percentages), but any monetary fields → `MoneyAmount`
- `HealthScoreResult`: keep `composite: Int` as-is, update any monetary fields

#### Step 4.7 — Update tests
- In all test files: replace `SpendingPace(currentMonthSpent = 500.0, ...)` with `SpendingPace(currentMonthSpent = MoneyAmount(500.0, CurrencyCode.EUR), ...)`
- Create a test helper: `val EUR_500 = MoneyAmount(500.0, CurrencyCode.EUR)` or use the `Double.toMoney()` extension

### Dependencies
- Phase A produces the model changes but does NOT change engine internals — engines still construct `Double` values that are wrapped at the model boundary.
- Phases C (Batch 6) will push `MoneyAmount` into engine internals.

### Risks
- **Risk 1**: Serialization of `MoneyAmount` in the entity layer (Room DB entities use `Double`). This phase only touches domain models, not entities. Mitigation: data layer remains `Double`; domain layer wraps/unwraps at boundary.
- **Risk 2**: Large number of test files to update. Mitigation: use IDE refactoring tools; the `.toMoney()` extension pattern minimizes boilerplate.
- **Risk 3**: `displayCurrency: String` is used in toString/debug output. Mitigation: `MoneyAmount.formatDisplay()` already provides formatted output.
- **Rollback**: Git revert.

### Validation Strategy
- Run all test files in Phase A scope — all must pass.
- Grep for remaining `displayCurrency: String` in the updated models — should be zero in the models listed.

### Acceptance Criteria
- [ ] `AnalyticsDashboardData` uses `MoneyAmount` for all monetary fields; no `displayCurrency: String`
- [ ] `SpendingPace` uses `MoneyAmount` for all monetary fields; no `displayCurrency: String`
- [ ] `ForecastComponents` uses `MoneyAmount` for all total fields
- [ ] `MonteCarloResult` uses `MoneyAmount` for all percentile bands
- [ ] `SavingsRecommendation` and `SavingsSweepRecommendation` use `MoneyAmount`
- [ ] `FinancialHealthResult` monetary fields use `MoneyAmount`
- [ ] All existing tests pass with updated model construction
- [ ] No `displayCurrency: String` in the updated top-level models

---

## Batch 5: MoneyAmount Phase B — Sub-Models (HIGH)

### Scope
- **In**: `EnhancedCategoryAnalytics`, `EnhancedMerchantAnalytics`, `StatisticalInsights`, `SpendingPatternAnalysis`, `AnalyticsDashboardCategoryBreakdown`, `DashboardMerchantBreakdown`, `MonthlyDataPoint`, `DayOfWeekSpending`
- **Out**: None removed

### Context
Phase A wrapped top-level models. Phase B propagates `MoneyAmount` into the sub-models that top-level models contain. This is the second stage of the audit's recommendation.

### Files

**Modify (source):**
- `domain/analytics/AdvancedAnalyticsModels.kt` — `EnhancedCategoryAnalytics`, `EnhancedMerchantAnalytics`, `StatisticalInsights`, `SpendingPatternAnalysis`, `TransactionPercentiles`, `HistogramBin`
- `domain/analytics/AdvancedAnalyticsDashboard.kt` — `AnalyticsDashboardCategoryBreakdown`, `DashboardMerchantBreakdown`, `MonthlyDataPoint`, `DayOfWeekSpending`
- `domain/analytics/AnalyticsModels.kt` — `CategoryInsight`, `MerchantInsight`, `MonthPeriod`, `DayOfWeekInsight`, `MonthlyComparison`, `YearOverYearComparison`, `AnomalyTransaction`
- `domain/model/FinancialForecast.kt` — `WeatherNarrative`, `NarrativeSection` (if containing monetary references)
- `domain/forecasting/MonteCarloResult.kt` — `SimulationMetadata` (if monetary)
- `domain/health/FinancialHealthScoreV2.kt` — `HealthScoreResult`, `HealthBreakdown`

**Modify (tests):**
- `AdvancedAnalyticsEngineTest.kt`
- `AdvancedAnalyticsEngineDeepTest.kt`
- `AdvancedAnalyticsDashboardTest.kt`
- `InsightsEngineTest.kt` and siblings
- `CategoryInsightEngineTest.kt`
- `MerchantInsightEngineTest.kt`
- All other test files that construct these sub-models

### Implementation Steps

#### Step 5.1 — Update EnhancedCategoryAnalytics
- `totalSpent: Double` → `totalSpent: MoneyAmount`
- `averagePerTransaction: Double` → `averagePerTransaction: MoneyAmount`
- `medianTransaction: Double` → `medianTransaction: MoneyAmount`
- `previousPeriodTotal: Double?` → `previousPeriodTotal: MoneyAmount?`
- `budgetAmount: Double?` → `budgetAmount: MoneyAmount?`
- `budgetRemaining: Double?` → `budgetRemaining: MoneyAmount?`
- `minTransaction: Double` → `minTransaction: MoneyAmount`
- `maxTransaction: Double` → `maxTransaction: MoneyAmount`
- `percentile25: Double` → `percentile25: MoneyAmount`
- `percentile75: Double` → `percentile75: MoneyAmount`
- `sparklineData: List<Double>` → keep `List<Double>` (these are normalized series)
- `velocity: Double` → keep `Double` (it's a percentage/rate)
- Remove `displayCurrency: String`

#### Step 5.2 — Update EnhancedMerchantAnalytics
- Same pattern as `EnhancedCategoryAnalytics`

#### Step 5.3 — Update StatisticalInsights
- `meanTransaction: Double` → `meanTransaction: MoneyAmount`
- `percentile25, percentile50, percentile75: Double` → `MoneyAmount`
- Keep deviation/volatility as `Double` (statistical, not monetary)

#### Step 5.4 — Update dashboard sub-models
- `AnalyticsDashboardCategoryBreakdown.amount: Double` → `amount: MoneyAmount`
- `DashboardMerchantBreakdown.amount: Double` → `amount: MoneyAmount`
- `MonthlyDataPoint.spending: Double` → `spending: MoneyAmount`; `income: Double` → `income: MoneyAmount`
- `DayOfWeekSpending` amounts → `MoneyAmount`

#### Step 5.5 — Update AnalyticsModels sub-models
- `CategoryInsight.avgAmount, minAmount, maxAmount, totalSpent: Double` → `MoneyAmount`
- `MerchantInsight` similar fields → `MoneyAmount`
- `MonthlyComparison` amounts → `MoneyAmount`
- `YearOverYearComparison` amounts → `MoneyAmount`

#### Step 5.6 — Update all test files
- Replace `EnhancedCategoryAnalytics(totalSpent = 1234.56, ...)` with `EnhancedCategoryAnalytics(totalSpent = MoneyAmount(1234.56, CurrencyCode.EUR), ...)`
- Use `EUR_1234_MONEY` constants or extensions as needed.

### Dependencies
- Batch 4 must be complete (MoneyAmount type available, helpers created)
- Engine implementations NOT changed yet (Batch 6 handles that)

### Risks
- **Risk 1**: Large surface area — 100+ fields to convert across 15+ models. Mitigation: systematic approach per model, one model at a time.
- **Risk 2**: Some fields like `volatility`, `velocity`, `confidence` are percentages/rates and should NOT be `MoneyAmount`. Mitigation: follow the audit's table in section 5.2 which clearly identifies monetary vs. non-monetary fields.
- **Rollback**: Git revert.

### Validation Strategy
- Run all affected test files — all must pass.
- Compile the full project — no type errors.

### Acceptance Criteria
- [ ] `EnhancedCategoryAnalytics` monetary fields use `MoneyAmount`
- [ ] `EnhancedMerchantAnalytics` monetary fields use `MoneyAmount`
- [ ] `StatisticalInsights` monetary fields use `MoneyAmount`
- [ ] All dashboard sub-models use `MoneyAmount` for monetary fields
- [ ] All `AnalyticsModels.kt` sub-models use `MoneyAmount` for monetary fields
- [ ] Non-monetary fields (percentages, rates, confidence scores) remain `Double`
- [ ] Full project compiles; all tests pass

---

## Batch 6: MoneyAmount Phase C — Engine Implementations (HIGH)

### Scope
- **In**: Engine implementations that construct Phase A/B models — must now construct `MoneyAmount` values
- **Out**: `// SAFE: data normalized via AnalyticsCurrencyNormalizer` comment pattern — replaced by actual type-safety

### Context
Phases A and B changed models to require `MoneyAmount`. Now engine implementations need to construct `MoneyAmount` values instead of raw `Double`. This is where the fragile trust-based `// SAFE: ...` comment pattern (20+ instances) is replaced by actual typed construction. Engines that currently pass `displayCurrency: String` should instead pass the actual `CurrencyCode`.

### Files

**Modify (source) — all engines listed in audit sections 1.1, 2.1, 4.1, 4.2:**
- `domain/analytics/AdvancedAnalyticsEngine.kt`
- `domain/analytics/AdvancedAnalyticsDashboard.kt`
- `domain/analytics/SpendingPaceCalculator.kt`
- `domain/analytics/DayOfWeekAnalyzer.kt`
- `domain/analytics/CategoryInsightEngine.kt`
- `domain/analytics/MerchantInsightEngine.kt`
- `domain/analytics/InsightsEngine.kt`
- `domain/analytics/AnalyticsCurrencyNormalizer.kt` — update return types
- `domain/forecasting/ForecastInputAssembler.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `domain/forecasting/MonteCarloSpendingSimulator.kt`
- `domain/forecasting/CalculateFinancialForecastUseCase.kt`
- `domain/savings/SmartSavingsEngine.kt`
- `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `domain/health/FinancialHealthScoreV2.kt`
- `domain/health/FinancialHealthCalculator.kt`
- `domain/budget/BudgetForecastingEngine.kt`

**Modify (tests) — all corresponding test files:**
- All 70+ test files as needed to match new constructors

### Implementation Steps

#### Step 6.1 — Update AnalyticsCurrencyNormalizer
- `normalizeSnapshots()` returns `NormalizationResult` containing `List<ExpenseSnapshot>` where `ExpenseSnapshot.effectiveAmount` is already normalized to home currency.
- Add a helper method: `fun resolveDisplayCurrency(): CurrencyCode` that returns the parsed home currency code.
- Return `CurrencyCode` from the resolution instead of `String`.

#### Step 6.2 — Update engine implementations (one engine at a time)
For each engine:
1. At the point where a `Double` monetary value is computed (after normalization), wrap it: `MoneyAmount(value, resolvedCurrencyCode)`
2. Replace `displayCurrency: String = ""` parameters with `displayCurrency: CurrencyCode` or remove them entirely (derived from `MoneyAmount`)
3. Remove `// SAFE: data normalized via...` comments — the type system now enforces this
4. For values that are sums of normalized data: use `MoneyAmount(total, currency)`
5. For values that are averages of normalized data: use `MoneyAmount(average, currency)`

#### Step 6.3 — Handle cross-currency sums in FinancialHealthScoreV2
- `calculateRunwayScore()` sums `savingsGoals.sumOf { it.currentAmount }` across potentially different currencies. Now that `SavingsGoal` amounts are `MoneyAmount`, implement the TODO at line 309: convert each goal's `currentAmount` to home currency before summing. If a goal's currency differs from home and no rate is available, exclude it with a warning.

#### Step 6.4 — Handle the RecurringOutflows currency TODO in FinancialStressForecastEngine
- In `calculateRecurringOutflows()`, after expanding occurrences, convert each `candidate.amount` from its currency to the display currency using `AnalyticsCurrencyNormalizer` (or the rate provider). The TODO at line 224 should be resolved.

#### Step 6.5 — Update test files
- All assertions that compare monetary values: `assertEquals(500.0, result.totalSpent)` → `assertEquals(MoneyAmount(500.0, EUR), result.totalSpent)`
- All test data setup: use `MoneyAmount` for monetary fields

### Dependencies
- Batches 1-5 must be complete
- `MultiCurrencyRepository` or `ExchangeRateProvider` must be accessible for currency conversion in step 6.3 and 6.4

### Risks
- **Risk 1**: `FinancialHealthScoreV2.calculateRunwayScore()` conversion may fail for some savings goals. Mitigation: excluded goals contribute a `ConversionFailure` to the output; document the limitation.
- **Risk 2**: Performance regression from wrapping millions of `MoneyAmount` objects. Mitigation: `MoneyAmount` is a data class (inexpensive), and the normalization already iterates all expenses. The wrap step adds negligible overhead.
- **Risk 3**: `SpendingPace` and `ForecastComponents` retain some `List<Double>` fields for sparklines/normalized series. Ensure these are intentionally left as `Double` and documented.
- **Rollback**: Git revert.

### Validation Strategy
- Run the full test suite (70+ test files). All must pass.
- Grep for remaining `// SAFE: data normalized via` comments — verify they are reduced to zero or replaced by doc references to `MoneyAmount`.
- Grep for remaining `displayCurrency: String` in engine implementations — verify it's replaced by `CurrencyCode` or removed.

### Acceptance Criteria
- [ ] All engines construct `MoneyAmount` for monetary output values
- [ ] `AnalyticsCurrencyNormalizer` returns/resolves `CurrencyCode` instead of `String`
- [ ] `// SAFE: data normalized via...` comments removed (20+ locations)
- [ ] `FinancialHealthScoreV2.calculateRunwayScore()` converts goal currencies before summing
- [ ] `FinancialStressForecastEngine.calculateRecurringOutflows()` converts occurrence amounts to display currency
- [ ] All 70+ tests pass

---

## Batch 7: MoneyAmount Phase D — UI Mappers (MEDIUM)

### Scope
- **In**: UI mappers, ViewModels, Composables that consume analytics/forecast/savings/health models
- **Out**: `displayCurrency: String` extraction patterns

### Context
Phase D updates the UI layer to extract values from `MoneyAmount` rather than relying on separate `displayCurrency: String` fields. This is the final stage of the MoneyAmount migration.

### Files

**Modify (source) — UI and mapping layer:**
- `ui/screens/analytics/AnalyticsViewModel.kt`
- `ui/screens/analytics/AdvancedAnalyticsViewModel.kt`
- `ui/mappers/MonteCarloBudgetImpactUiMapper.kt`
- `ui/screens/budget/BudgetForecastingViewModel.kt`
- `ui/components/FinancialStressForecastCard.kt`
- `ui/components/MonteCarloForecastCard.kt`
- `ui/components/ForecastTimeline.kt`
- `DashboardWidgetUiMapper.kt`
- `MoneyRadarWidget.kt`
- `TotalsDashboardCard.kt`
- `RetroTotalsDashboardCard.kt`
- `HealthScoreWidget.kt`
- `FinancialHealthScoreV2Widget.kt`
- `ui/screens/savings/SavingsViewModel.kt` (or equivalent)

**Modify (tests):**
- `AnalyticsViewModelStressTest.kt`
- `AdvancedAnalyticsViewModelTest.kt`
- `BudgetForecastingViewModelTest.kt`

### Implementation Steps

#### Step 7.1 — Update ViewModel mapping logic
- Where ViewModels extract: `model.displayCurrency` → `model.totalSpent.currency.code`
- Where ViewModels format for display: `"${model.displayCurrency} ${model.totalSpent}"` → `model.totalSpent.formatDisplay()`
- Update ViewModel state classes to use `MoneyAmount` or formatted `String` instead of `Double` + `String` pairs

#### Step 7.2 — Update UI Composables
- Replace `displayCurrency: String` parameters with `currencyCode: CurrencyCode` where needed
- Use `CurrencyCode.symbolFor()` for display symbols
- `MonteCarloForecastCard`, `FinancialStressForecastCard`, `ForecastTimeline` — update data bindings

#### Step 7.3 — Update UI mappers
- `MonteCarloBudgetImpactUiMapper` — extract values from `MonteCarloResult`'s `MoneyAmount` fields
- `DashboardWidgetUiMapper` — map `AnalyticsDashboardData` monetary fields to UI primitives
- `MoneyRadarWidget` — update amount extraction

#### Step 7.4 — Update test files
- Update mock data to use `MoneyAmount` construction
- Update assertions for formatted display strings if changed

### Dependencies
- Batches 1-6 must be complete

### Risks
- **Risk 1**: UI formatting could change rendering. Mitigation: `MoneyAmount.formatDisplay()` produces the same format (`"€1,234.56"`) as the previous manual formatting — verify this.
- **Risk 2**: ViewModels may cache `Double` values for recomposition performance. Mitigation: extract `.amount` from `MoneyAmount` and cache the `Double` in local ViewModel state if needed.
- **Rollback**: Git revert.

### Validation Strategy
- Run ViewModel tests and UI tests.
- Manual UI walkthrough: dashboard, analytics, savings, health, budget forecasting screens — verify monetary values display correctly with proper currency symbols.

### Acceptance Criteria
- [ ] All ViewModels extract values from `MoneyAmount` instead of `displayCurrency: String`
- [ ] All UI Composables use `MoneyAmount.formatDisplay()` or `CurrencyCode.symbolFor()` for rendering
- [ ] No remaining `displayCurrency: String` fields in UI state classes (except transitional mappings)
- [ ] UI tests pass; manual UI review confirms correct display

---

## Batch 8: Address Hardcoded Currency Values (MEDIUM)

### Scope
- **In**: `SmartSavingsEngine`, `FinancialHealthCalculator`, `FinancialStressForecastEngine`, `CurrencySettingsRepositoryImpl`
- **Out**: Nothing removed — constants are made configurable or clearly documented

### Context
Hardcoded `Double` constants exist across multiple engines:
- `SmartSavingsEngine`: `DEFAULT_CAP_WEEK=75.0`, `DEFAULT_CAP_MONTH=200.0`, `DEFAULT_CAP_QUARTER=500.0`
- `FinancialHealthCalculator`: `DEFAULT_DAILY_TARGET=50.0`, `DEFAULT_WEEKLY_TARGET=350.0`, `DEFAULT_MONTHLY_TARGET=1500.0`
- `FinancialStressForecastEngine`: `DEFAULT_EMERGENCY_BUFFER_FALLBACK=500.0`
- `CurrencySettingsRepositoryImpl`: `DEFAULT_EMERGENCY_BUFFER_FALLBACK=500.0` (duplicate)

These are home-currency-denominated but undocumented. The recommendation: make configurable where possible, document where not.

### Files

**Modify (source):**
- `domain/savings/SmartSavingsEngine.kt`
- `domain/health/FinancialHealthCalculator.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `data/repository/CurrencySettingsRepositoryImpl.kt`

**Modify (tests):**
- `SmartSavingsEngineTest.kt`
- `FinancialHealthCalculator*.kt` test files
- `FinancialStressForecastEngineTest.kt`

### Implementation Steps

#### Step 8.1 — Make emergency buffer configurable
- `CurrencySettingsRepositoryImpl.DEFAULT_EMERGENCY_BUFFER_FALLBACK` is already stored in preferences. Remove the duplicate constant from `FinancialStressForecastEngine` and inject the repository's value instead.
- If the preference is absent, fall back to a well-documented constant.

#### Step 8.2 — Document home-currency nature of SmartSavingsEngine caps
- Add a KDoc block on `DEFAULT_CAP_WEEK`, `DEFAULT_CAP_MONTH`, `DEFAULT_CAP_QUARTER` explaining these are in home currency units and represent conservative weekly/monthly/quarterly savings caps.
- Optionally: expose these as user-editable settings (out of scope for this phase, but add a TODO if desired).

#### Step 8.3 — Document home-currency nature of FinancialHealthCalculator targets
- Add KDoc on `DEFAULT_DAILY_TARGET`, `DEFAULT_WEEKLY_TARGET`, `DEFAULT_MONTHLY_TARGET` explaining these are home-currency-denominated baseline spending targets.
- Optionally: make them configurable via a `HealthSettingsRepository` (out of scope, add TODO).

#### Step 8.4 — Update tests
- If constants were moved to be injected, update test stubs to provide them.
- Verify that the emergency buffer fallback is correctly resolved from preferences in tests.

### Dependencies
- Batch 3 (CurrencySettingsRepository already available)

### Risks
- **Risk 1**: Making emergency buffer a preference may change behavior if the preference key already exists with a different value. Mitigation: check existing keys; if `EMERGENCY_BUFFER_KEY` already exists, use it as-is.
- **Risk 2**: Changing constants could alter existing recommendations. Mitigation: keep the same default values — only document and centralize.
- **Rollback**: Git revert.

### Validation Strategy
- Run affected test files — must pass.
- Verify emergency buffer resolves from preferences correctly.

### Acceptance Criteria
- [ ] `FinancialStressForecastEngine` no longer has its own `DEFAULT_EMERGENCY_BUFFER_FALLBACK` — uses `CurrencySettingsRepository`'s value
- [ ] `SmartSavingsEngine` caps are KDoc-documented as home-currency-denominated
- [ ] `FinancialHealthCalculator` targets are KDoc-documented as home-currency-denominated
- [ ] All tests pass

---

## Batch 9: Centralize Data Quality Reporting (LOW)

### Scope
- **In**: Create a unified `DataQualityReport` model. Aggregate scattered quality metrics.
- **Out**: Nothing removed

### Context
Data quality metrics are scattered: `DataQualityAssessor` (forecasting), `AnalyticsConversionWarning` (currency), `conversionConfidence` (health), `AnalyticsNormalizationResult.lossPercentage`. No unified reporting. Low priority but valuable for user trust and debugging.

### Files

**Create:**
- `domain/analytics/DataQualityReport.kt` — unified model
- `domain/analytics/DataQualityAssembler.kt` — assembles report from scattered metrics

**Modify (source):**
- `domain/forecasting/DataQualityAssessor.kt` — update to produce structured output consumable by DataQualityAssembler
- `domain/forecasting/MonteCarloSpendingSimulator.kt` — pipe quality metrics to assembler
- `domain/health/FinancialHealthScoreV2.kt` — pipe conversion confidence
- `domain/analytics/AnalyticsCurrencyNormalizer.kt` — expose normalization loss percentage in structured form

### Implementation Steps

#### Step 9.1 — Create DataQualityReport model
```kotlin
data class DataQualityReport(
    val overallQuality: QualityLevel, // EXCELLENT, GOOD, FAIR, POOR, INSUFFICIENT_DATA
    val currencyConversionLoss: ConversionLossSummary,
    val historicalDataVolume: DataVolumeSummary,
    val freshness: DataFreshnessSummary,
    val aiConfidence: AiConfidenceSummary?,
    val warnings: List<UiText>
)
```
- Define sub-models for each section.

#### Step 9.2 — Create DataQualityAssembler
- `DataQualityAssembler` aggregates from `DataQualityAssessor`, `AnalyticsCurrencyNormalizer`, and health/AI confidence metrics.
- Provides a single method: `assemble(period: PeriodRange): DataQualityReport`

#### Step 9.3 — Integrate into dashboard
- Add `dataQualityReport` field to `AnalyticsDashboardData` (nullable — only when available)
- Add a `DataQualityBanner` composable showing the overall quality level and key warnings

#### Step 9.4 — Update tests
- Add `DataQualityAssemblerTest.kt` verifying aggregation logic
- Add test for `DataQualityReport` model serialization

### Dependencies
- Batches 1-8 must be complete

### Risks
- **Risk 1**: `DataQualityAssessor` currently produces `SimulationConfidence` — may need restructuring. Mitigation: keep `SimulationConfidence` as-is; add a secondary output method.
- **Risk 2**: Low priority — may be deferred if Batches 1-8 consume all time. Mitigation: this batch is explicitly labeled LOW and can be postponed.

### Validation Strategy
- Run new `DataQualityAssemblerTest`.
- Manual: verify dashboard shows quality banner when conversion warnings exist.

### Acceptance Criteria
- [ ] `DataQualityReport` model defined with all sub-models
- [ ] `DataQualityAssembler` aggregates quality metrics from all sources
- [ ] `AnalyticsDashboardData` includes optional `dataQualityReport`
- [ ] Dashboard UI shows quality banner
- [ ] Tests pass

---

## Execution Summary

| Batch | Severity | Est. Files | Depends On | Key Risk |
|-------|----------|-----------|------------|----------|
| 1: Double-Counting | 🔴 CRITICAL | 6 | None | Occurrence expansion failures |
| 2: PeriodRange | 🔴 HIGH | 40+ | None | No behavioral change (pure refactor) |
| 3: BudgetForecast Currency | 🔴 HIGH | 3 | None | Performance overhead |
| 4: MoneyAmount Phase A | 🔴 HIGH | 20+ | None | Serialization compatibility |
| 5: MoneyAmount Phase B | 🔴 HIGH | 30+ | Batch 4 | Large surface area |
| 6: MoneyAmount Phase C | 🔴 HIGH | 40+ | Batches 1-5 | Cross-currency TODOs |
| 7: MoneyAmount Phase D | 🟡 MEDIUM | 15+ | Batches 1-6 | UI display changes |
| 8: Hardcoded Values | 🟡 MEDIUM | 6 | Batch 3 | Behavior change from preference lookup |
| 9: Data Quality | 🟢 LOW | 5 | Batches 1-8 | Low priority, may defer |

**Total estimated files modified**: 165+ across 9 batches

---

## Global Acceptance Criteria
- [ ] All 6 blockers resolved (double-counting, PeriodRange, MoneyAmount, BudgetForecastingEngine currency, hardcoded currency, RecurringOccurrence unused)
- [ ] Zero failing tests across the full 70+ test suite
- [ ] Zero remaining `// SAFE: data normalized via...` fragile comment patterns
- [ ] Zero remaining `AnalyticsPeriod`, `AnalyticsPeriodRange`, or `TimePeriod` references
- [ ] Zero remaining `displayCurrency: String` in domain models (UI view states may retain for display)
- [ ] All forecasting paths use `RecurringOccurrenceExpander` for recurring expansion
- [ ] All engines normalize through `AnalyticsCurrencyNormalizer` before monetary computation
- [ ] Full project compiles without type errors
