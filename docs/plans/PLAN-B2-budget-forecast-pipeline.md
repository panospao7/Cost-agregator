# PLAN-B2 — Budget/Forecasting Pipeline

## 1. Objective & Blast Radius

- **Core issue:** the B.2 pipeline still has CRITICAL/HIGH drift in budget-window derivation, forecast horizon math, aggregate spend semantics, singleton lifecycle handling, and downstream forecasting inputs. The fix must close only the B.2 **CRITICAL** and **HIGH** items from `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`.
- **In scope (CRITICAL/HIGH only):**
  - canonical budget-period windowing
  - budget forecast horizon/risk correctness
  - shared-budget spend semantics
  - budget autopilot aggregation/history-confidence correctness
  - budget monitor thread-safety/background lifecycle
  - financial stress forecast input quality
  - recurring next-date freshness
  - financial forecast synthesis inputs + rollover freshness
  - month-end sweep upcoming-cost + goal-cap logic
  - Money Radar spent-to-date + urgency scoring
  - totals aggregation zero-period handling
  - non-negative recommendation savings
- **Out of scope:**
  - all B.2 MEDIUM/LOW items
  - schema/entity/migration work
  - unrelated UI redesigns
  - any cross-pipeline fixes that belong to B.1/B.3/B.4/B.9/B.10/B.11
- **Live-audit note:** several registry items appear partially or fully addressed in current source already (notably rolling-window handling, uncapped carbon reads, some A.9/A.10 downstream fixes, and dashboard expense rollover). Execution must **verify current code first** and prefer regression lock-in over production churn where the implementation is already compliant.
- **Primary blast radius:**
  - `app/src/main/java/com/yourname/expensetracker/domain/budget/`
  - `app/src/main/java/com/yourname/expensetracker/domain/forecasting/`
  - `app/src/main/java/com/yourname/expensetracker/domain/logic/`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/`
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/`
  - `app/src/main/java/com/yourname/expensetracker/domain/carbon/`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/`
  - targeted tests under `app/src/test/java/` and `app/src/test/kotlin/`

> [!WARNING]
> - Do **not** change Room entities, DAOs, migrations, or schema versions in B.2.
> - Do **not** break public repository APIs; if a safer internal helper can be used, prefer that over signature churn.
> - Do **not** mix B.2 MEDIUM/LOW cleanup into these batches.
> - If a file is already compliant, keep production code stable and add/update tests only.

## 2. The Single Source of Truth

- **Budget period windows:** `BudgetCalculator` is the only authority for budget-period boundaries. No duplicate period arithmetic may remain in `BudgetForecastingEngine`, `SharedBudgetManager`, or other callers.
- **Historical/next-window derivation:** callers that need anything other than “window containing now” must use explicit evaluation-time logic (`calculatePeriodWindowForTime(...)` semantics), not a wrapper that implicitly reads `timeProvider.now()`.
- **Budget spend semantics:** all budget/progress/forecast surfaces must use the active budget window plus existing A.1/A.10 rules:
  - purchase-only spending
  - effective amount / ownership-adjusted amount
  - overall budgets aggregate whole-wallet spend, **not** only uncategorized rows
- **Forecast horizon semantics:** a budget forecast is for the **remaining duration of the active budget period**, not an arbitrary 30-day approximation.
- **Confidence semantics:** confidence narrows uncertainty and messaging; it must **never** downgrade a deterministic overspend/overrun into a “safe” result.
- **History semantics for pace/trend/volatility:** algorithms that reason about periods/days must include explicit zero-spend buckets inside the analyzed range when the absence of spend is itself meaningful.
- **Missing-data behavior:** when upstream inputs are unknown, degrade gracefully. Do **not** substitute budget caps for income or hardcode `knownUpcoming = 0.0`.
- **Singleton lifecycle rule:** `BudgetMonitor` must be thread-safe and must survive app background/foreground transitions; normal lifecycle events must not permanently kill its scope.

> [!WARNING]
> - Preserve A.1 effective-amount behavior, A.5 time-boundary helpers, A.9 uncapped/aggregate query fixes, and A.10 transaction-type rules.
> - Do **not** reintroduce month-to-date shortcuts for non-monthly budgets.
> - Do **not** keep two competing implementations of the same budget-window or risk-tier logic.

## 3. File-by-File Execution Checklist (micro-batches)

### Batch 1 — Canonical budget-period window semantics
**Dependency:** prerequisite for Batches 2 and 3.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
  - Keep rolling budgets anchored to the active cycle that contains `now`; do **not** reintroduce any `+30 days` shortcut.
  - Fix `CALENDAR` yearly budgets so they resolve Jan 1 → Jan 1, not anniversary-style anchor windows.
  - Preserve `calculatePeriodWindow(period, anchorDate)` as a convenience wrapper only if needed, but make explicit that historical/next-window callers must use the evaluation-time path.
  - Do **not** change non-yearly calendar semantics that already match `TimePeriodUtils`.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorTest.kt`
  - Add/refresh assertions for calendar-year yearly windows and historical/next-window derivation.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorBoundaryTest.kt`
  - Lock in rolling anchored-cycle behavior, Jan/Feb coercion, and explicit-evaluation-time usage.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetCalculatorTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetCalculatorBoundaryTest"`

> [!WARNING]
> - Do **not** widen this batch into medium/low month-bucketing cleanup.
> - Do **not** refactor unrelated callers yet; only establish the canonical window rules first.

### Batch 2 — Budget forecast horizon + overspend correctness
**Dependency:** after Batch 1.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
  - Remove duplicate in-engine budget-period arithmetic and delegate to `BudgetCalculator` semantics (directly or via a helper backed by it).
  - Forecast against the **remaining active budget-period duration**, not the default 30-day approximation.
  - Ensure deterministic projected overspend remains `1.0` regardless of low confidence.
  - Keep forecast persistence / deactivation behavior unchanged.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`
  - Add regressions for remaining-period duration, deterministic overspend probability, and yearly calendar budgets.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetTrendBoundaryTest.kt`
  - Update only the expectations that legitimately move because the horizon is now the active budget window.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModelTest.kt`
  - Verify the UI contract stays stable if forecast semantics change behind the same method signature.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetForecastingEngineTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetTrendBoundaryTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.budget.BudgetForecastingViewModelTest"`

> [!WARNING]
> - Do **not** turn this batch into a UI redesign or a multi-period exploratory forecast feature.
> - If a second “custom horizon” product contract is discovered, stop and split it rather than overloading this budget-adherence path.

### Batch 3 — Shared budget progress must match budget semantics
**Dependency:** after Batch 1.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt`
  - Replace month-to-date logic with the active budget period window from `BudgetCalculator`.
  - For overall budgets, aggregate whole-wallet budget spend; do **not** treat `categoryId == null` as “uncategorized only.”
  - Preserve effective-amount behavior and the existing public DTO shape.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/SharedBudgetManagerTest.kt`
  - Add regressions for overall budgets, non-monthly windows, and alignment with budget spend semantics.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.SharedBudgetManagerTest"`

> [!WARNING]
> - Do **not** reintroduce the nullable-category aggregate helper for overall-budget semantics.
> - Do **not** broaden this batch into member-contribution persistence or group-sharing redesign.

### Batch 4 — Carbon footprint one-shot suspend lock-in
**Dependency:** independent; execute early because it is CRITICAL but likely audit-only.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculator.kt`
  - Audit first. If the method already uses a one-shot uncapped DAO read, keep production code unchanged except for clarifying comments if needed.
  - If any `Flow` collection or long-lived observation remains, replace it with a one-shot suspend read.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculatorTest.kt`
  - Lock in the one-shot uncapped DAO path and verify no flow-based read is used.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculatorTest"`

> [!WARNING]
> - If code is already compliant, this batch should be test/doc only.
> - Do **not** change emission factors, recommendation copy, or category heuristics here.

### Batch 5 — Autopilot totals, zero-months, and sparse-history confidence
**Dependency:** after Batch 1.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
  - Stop double-counting when both an overall budget and category budgets are active; summary totals must come from one canonical scope.
  - Fill zero-spend month buckets inside the lookback window before trend/volatility math.
  - Enforce `MIN_HISTORY_MONTHS` so empty/one-point histories do not yield misleadingly strong confidence.
  - Keep recommendation DTOs and apply flows backward-compatible.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngineTest.kt`
  - Add regressions for overall+category coexistence, zero-spend month infill, and low-history confidence handling.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetAutopilotEngineTest"`

> [!WARNING]
> - Do **not** change budget-application UI in this batch unless compile fails force a tiny fix.
> - Do **not** widen this batch into medium/low month-bucketing harmonization beyond what the high-severity items require.

### Batch 6 — Budget monitor synchronization and background-safe lifecycle
**Dependency:** independent.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
  - Preserve synchronized access to `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp`.
  - Preserve `CancellationException` rethrow behavior.
  - Split destructive teardown from normal background behavior so `onStop()` does not permanently cancel the monitor scope.
- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`
  - Replace `budgetMonitor.cleanup()` on app stop with a non-destructive background hook, or remove the call if unnecessary after the monitor fix.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorTest.kt`
  - Replace the old “post-cleanup no reads” expectation with a background/foreground-safe expectation.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorStressTest.kt`
  - Add concurrency/throttle and cancellation regressions that prove state stays coherent and the monitor survives lifecycle transitions.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetMonitorTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetMonitorStressTest"`

> [!WARNING]
> - Do **not** swallow cancellation as a “fix.”
> - Do **not** add app-global mutable state outside the monitor just to work around lifecycle issues.

### Batch 7 — Financial stress forecast input quality + recurring next-date freshness
**Dependency:** independent.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
  - Stop presenting current-month net cashflow as a true account balance.
  - Remove the budget-as-income fallback; missing income history must degrade gracefully instead of fabricating inflow.
  - Include zero-spend days when building empirical daily discretionary samples.
- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
  - Roll stale `nextExpectedDate` values forward until they land in the future for emitted patterns.
  - Apply one consistent rule to detected and manual patterns at the domain-output level.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt`
  - Add regressions for zero-spend-day inclusion, degraded income handling, and non-misleading balance semantics.
- [ ] **Modify** `app/src/test/kotlin/com/yourname/expensetracker/domain/logic/RecurringExpenseEngineTest.kt`
  - Add stale-pattern fixtures and assert future-rolled `nextExpectedDate` values.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngineTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.logic.RecurringExpenseEngineTest"`

> [!WARNING]
> - Do **not** invent a fake bank-balance source if one does not already exist.
> - Do **not** mutate recurring rows in storage from this engine unless a separate persistence contract is explicitly added and tested.

### Batch 8 — Financial forecast synthesis inputs + rollover freshness
**Dependency:** independent, but safest after Batch 1.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
  - Replace placeholder `pastSumDaily`, placeholder `SpendingPace`, and forced `TRACKING` goal protection values with real derived inputs.
  - Introduce a day-boundary trigger so the flow recomputes across day/month rollover even when repositories stay silent.
  - Keep `invoke(): Flow<FinancialForecast>` intact.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
  - Add assertions for real cumulative history, real pace inputs, preserved goal protection levels, and rollover-trigger recomputation.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.forecast.CalculateFinancialForecastUseCaseTest"`

> [!WARNING]
> - Do **not** replace this use case with direct UI-side composition.
> - Do **not** hardcode `ON_PACE`, empty history, or fake goal protection as a fallback.

### Batch 9 — Month-end savings sweep + Money Radar deterministic inputs
**Dependency:** independent.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
  - Replace `knownUpcoming = 0.0` with actual known upcoming obligations before month-end.
  - Cap each goal allocation by remaining goal gap before applying concentration caps.
  - Preserve existing recommendation DTO shape.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`
  - Add regressions for known-upcoming inputs and overfund-prevention.
- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
  - Bound spent-to-date to `expense.date <= now`.
  - Make urgency scoring reflect both probability and overrun magnitude / risk tier.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
  - Add future-dated purchase exclusion and magnitude-driven urgency regressions.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCaseTest"`

> [!WARNING]
> - Do **not** change Monte Carlo engine math in this batch unless a compile blocker forces a tiny alignment fix.
> - Do **not** let future-dated expenses contaminate any spent-to-date widget logic.

### Batch 10 — Zero-spend totals + non-negative recommendation savings
**Dependency:** independent.

- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
  - Zero-fill missing daily/weekly/monthly periods inside the requested range.
  - Keep labels chronological and stable even when some periods have no transactions.
  - Perform zero-fill in the engine layer unless an existing repository contract already provides exact buckets.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineTest.kt`
  - Add zero-period and stable-label regressions.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineValidationTest.kt`
  - Confirm zero-filled output does not change aggregate sums.
- [ ] **Modify** `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt`
  - Clamp `potentialSavings` at `0.0` so recommendations never display negative savings.
- [ ] **Modify** `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngineTest.kt`
  - Add a regression proving `potentialSavings` cannot go below zero.

**Batch validation:**
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.analytics.TotalsAggregationEngineTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.analytics.TotalsAggregationEngineValidationTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetRecommendationEngineTest"`

> [!WARNING]
> - Do **not** change period-status thresholds, only missing-period materialization.
> - Do **not** broaden this batch into medium-severity dashboard-summary cleanup.

## 4. Verification Plan

- **Serialized verification lane:** because this is a Phase B pipeline, keep Gradle verification serialized through the orchestrator. Coding agents should avoid overlapping long Gradle runs.
- **Per-batch minimum gate:**
  - read back every changed file
  - `./gradlew.bat :app:compileDebugKotlin`
  - run only the smallest focused test selectors for the active batch
- **Sentinel regressions to keep green even when no production edits are expected:**
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.carbon.CarbonFootprintCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.BudgetRepositoryTruncationTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.cashflow.CashFlowCalculatorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.business.BusinessExpenseReportGeneratorTest"`
  - These act as guardrails for registry items that appear already resolved in live code.
- **Final B.2 verification lane:**
  - `./gradlew.bat :app:compileDebugKotlin`
  - targeted tests from Batches 1–10
  - if one batch required only audit/test lock-in, still include its focused test class in final evidence
- **Failure handling:**
  - if a batch cannot be made green, revert that batch before moving on
  - do **not** stack later B.2 edits on a red baseline

> [!WARNING]
> - Do **not** claim B.2 complete based on static inspection alone.
> - Do **not** use full-suite failures unrelated to B.2 as a reason to skip targeted evidence.

## 5. Documentation & Registry Updates

- [ ] **Update** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - In the `### B.2: Budget/Forecasting Pipeline` section (currently around lines 150–183), mark each addressed **CRITICAL** and **HIGH** item with `[RESOLVED BY B.2]`.
  - If an item was already compliant in source and B.2 only added regression lock-in, still disposition it under B.2 with an explicit note in the review doc.
- [ ] **Update** the exact final-verification files tied to B.2 batches:
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-04.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-05.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-22.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-27.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-28.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-32.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-37.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-40.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-48.md`
- [ ] **Create/update** `docs/reviews/REVIEW-B2.md`
  - record each micro-batch
  - list changed files
  - capture targeted verification commands/results
  - record any audit-only/no-op decisions and why no production edit was needed
- [ ] **Update** `docs/plans/EXECUTION-PLAYBOOK.md` only after B.2 review passes and documentation closeout is complete.
- [ ] **Documentation ordering rule:** follow playbook order exactly:
  1. Master Registry
  2. exact final-verification rows
  3. matching deep-analysis mirror rows only if they still need mirroring

> [!WARNING]
> - Do **not** mark B.2 done until code, tests, review, registry, and final-verification docs are all updated in the same closeout sequence.
> - Do **not** update deep-analysis mirrors before the registry and final-verification files.
