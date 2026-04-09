# PLAN A.6 — Mixed Numeric Types (Float vs Double for financial data)

## 1. Objective & Blast Radius
- **The Core Issue:** A.6 is a contract-cleanup epic: financial-domain models currently mix `Float` and `Double` for related monetary, percentage, and history values. That inconsistency forces repeated narrowing/widening casts, bakes chart-friendly numeric types into domain/repository contracts, and creates avoidable precision loss in financial calculations.
- **Blast Radius:**
  - **Primary model contracts:** `SpendingSummary.kt`, `BudgetStatusSnapshot.kt`, `CategoryBreakdown.kt`, `DashboardCategoryBreakdown.kt`, `MonteCarloBudgetImpact.kt`, `FinancialForecast.kt`
  - **Immediate producers/adapters:** `AnalyticsRepository.kt`, `BudgetRepository.kt`, `DashboardContractsAdapter.kt`, `FinancialWeatherRepository.kt`, `TotalsAggregationEngine.kt`, `CalculateFinancialForecastUseCase.kt`
  - **Direct domain/use-case consumers:** `SynthesisEngine.kt`, `ComputeDashboardWidgetsUseCase.kt`, `ComputeMoneyRadarUseCase.kt`, `FinancialHealthCalculator.kt`, `DashboardBriefingInputBuilder.kt`
  - **Presentation/chart boundaries likely needing explicit conversion to `Float`:** `AnalyticsViewModel.kt`, `AnalyticsScreen.kt`, `CategoryDonutChart.kt`, `CategoryBreakdownSheet.kt`, `RetroCategoryBreakdownSheet.kt`, `HomeScreen.kt`, `RetroTopCategoriesCard.kt`, `BudgetScreen.kt`
  - **Test/fixture surfaces:** tests that directly instantiate any of the six A.6 models or assert percentage/history literal types (`Float` suffixes / `assertEquals(...f)` / `assertApproxEquals(...f)`)
- **Assumptions / Unknowns:**
  - The registry text includes `MonteCarloBudgetImpact.kt` and `FinancialForecast.kt`, but the current checked-in files already use `Double` for the A.6-targeted numeric fields. Treat those two files as **audit-first**: read them, verify they already satisfy the canonical rule, and only touch compile-neighbors/tests/docs if a remaining `Float` contract still leaks through their producers/consumers.
  - `BudgetStatus.percentUsed` in `domain/budget/BudgetModels.kt` is still `Float`, but the user explicitly constrained this epic to the listed files and warned against broad contract changes. Therefore A.6 should prefer converting **when mapping into/out of** `BudgetStatusSnapshot` rather than widening `BudgetStatus` itself, unless compile evidence shows that the snapshot cleanup is impossible without a tiny adjacent contract shim.
  - UI/presentation models such as `AnalyticsState.changePercent`, `BudgetVsActualItem.percentUsed`, `CategorySpending.percentage`, and chart progress values intentionally use `Float` in some places because Compose APIs (`LinearProgressIndicator`, canvas sweep math) accept `Float`. Those are boundary-level types and are **not** in-scope for A.6 beyond explicit conversion points.
  - The separate issues around `FinancialForecast.actionableInsights`, `ForecastHorizon.REST_OF_MONTH.days`, `MonteCarloBudgetImpact` formatted strings/EUR coupling, and `DashboardCategoryBreakdown.changeFromLastPeriod` are **not** part of A.6. Do not broaden this epic into those unrelated model/UX cleanups.

## 2. Single Source of Truth (The Standard)
- **Canonical numeric-type rule:** all **financial domain/repository contracts** in A.6 scope must use `Double` for money-adjacent totals, percent/risk ratios, and historical spending series. Convert to `Float` only at explicit UI/chart/rendering boundaries.
- **Practical standard for this epic:**
  1. **Domain and repository DTOs:** `Double`
  2. **Intermediate calculations:** keep in `Double`; do not compute in `Float` and widen afterward
  3. **Adapter boundaries into Compose/chart models:** narrow with explicit `.toFloat()` only where the target API or UI state is intentionally `Float`
  4. **Tests:** prefer `Double` literals/assertions for A.6-targeted fields; remove `f` suffixes where those fields become `Double`
  5. **No persistence/schema widening in this epic:** Room entities/columns/DAO schema changes are out of scope unless compile evidence proves a listed contract cannot be made consistent without them
- **Canonical ownership by file family:**
  - `domain/model/...` and `data/repository/...` A.6 DTOs are the contract owners
  - UI/state/chart files consume those contracts and are the only acceptable place to downcast to `Float`
- **Out of scope for A.6:**
  - Room entity field type changes (`Budget`, `Expense`, Room columns, migrations, SQL schema)
  - Business-logic redefinitions (budget thresholds, Monte Carlo risk thresholds, forecast formulas, trend semantics)
  - Cleanup of unrelated duplicate model families, localization boundaries, text models, or sentinel-value APIs

## 3. File-by-File Execution Checklist

### Execution order / safe micro-batches
1. **Batch 1 — Spending summary contract cleanup**
   - **Scope:** `domain/model/dashboard/SpendingSummary.kt`, `data/repository/AnalyticsRepository.kt`, `data/repository/DashboardContractsAdapter.kt`
   - **Why first:** this is the clearest end-to-end A.6 path (`repository DTO -> domain DTO -> dashboard consumers`) and establishes the canonical `Double` history/percentage rule before downstream chart consumers are adjusted.
   - **Validation focus:** dashboard compile-neighbors and tests that instantiate `SpendingSummary`
   - **Complete when:** both repository and domain `SpendingSummary` variants expose `Double` for `changePercent`, `dailyHistory`, and `previousDailyHistory`, with no hidden `Float` lists left in the adapter path.
2. **Batch 2 — Budget/category domain contract cleanup**
   - **Scope:** `domain/model/dashboard/BudgetStatusSnapshot.kt`, `domain/model/CategoryBreakdown.kt`, `domain/analytics/TotalsAggregationEngine.kt`
   - **Why second:** these are low-blast-radius domain DTOs with concrete `Float` fields, but they feed multiple UI/tests; landing them together minimizes churn.
   - **Validation focus:** totals aggregation + budget snapshot constructor fixtures + category sheet/chart compile-neighbors
   - **Complete when:** snapshot/category DTOs use `Double` for percentages and their direct producers calculate in `Double`.
3. **Batch 3 — Forecast / Monte Carlo audit and compile-neighbor alignment**
   - **Scope:** `domain/model/budget/MonteCarloBudgetImpact.kt`, `domain/model/FinancialForecast.kt`, `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`, `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`, `data/repository/FinancialWeatherRepository.kt`
   - **Why third:** the two registry-listed model files appear already compliant, so this batch is primarily to confirm no `Float` leakage remains in nearby producer/consumer paths and to keep forecast tests stable.
   - **Validation focus:** forecast and Monte Carlo use-case tests only; no broad business-logic changes.
   - **Complete when:** the audit is documented in code comments/plan execution notes via unchanged or minimally touched files, and any compile-neighbor still narrowing/widening A.6-targeted values prematurely is cleaned up.
4. **Batch 4 — UI/chart boundary conversions only**
   - **Scope:** minimum presentation consumers needed after Batches 1-3 compile breaks surface (likely `ComputeDashboardWidgetsUseCase.kt`, `AnalyticsViewModel.kt`, `AnalyticsScreen.kt`, `CategoryDonutChart.kt`, `CategoryBreakdownSheet.kt`, `RetroCategoryBreakdownSheet.kt`, `HomeScreen.kt`, `BudgetScreen.kt`, `DashboardBriefingInputBuilder.kt`)
   - **Why fourth:** UI should adapt after contracts settle; this batch exists to make boundary narrowing explicit and local.
   - **Validation focus:** no business math changes, only conversion-site correctness and chart/progress compatibility.
   - **Complete when:** all remaining `Float` use is clearly presentation-bound (`toFloat()` at the edge) and no domain/repository DTO forces `Float`.
5. **Batch 5 — Documentation / registry / report sync**
   - **Scope:** registry A.6 block, A.6-related rows only in batch reports, and A.6-related rows only in matching deep-analysis mirrors
   - **Validation focus:** no unrelated rows marked resolved; audit-only notes preserved where files were intentionally not broadened.
   - **Complete when:** docs reflect only the numeric-type cleanup actually implemented under A.6.

### Batch 1 — Spending summary contract cleanup
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/SpendingSummary.kt`
  - Change `changePercent`, `dailyHistory`, and `previousDailyHistory` from `Float`/`List<Float>` to `Double`/`List<Double>`.
  - Keep `totalSpent`, `previousTotalSpent`, and `transactionCount` unchanged.
  - Do **not** add invariants about history length; that was already identified as a false positive in prior reports.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
  - Update the repository-local `SpendingSummary` DTO to the same `Double` contract as the domain model.
  - Stop narrowing daily arrays and `changePercent` with `.toFloat()`; emit `DoubleArray.map { it }` / raw `Double` instead.
  - Keep period math, DAO calls, and previous-period comparison logic unchanged.
  - Audit comments/KDoc so they no longer describe `Float` history output.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
  - Ensure the `observeSpendingSummary(...)` mapper passes the now-`Double` fields through without extra casts.
  - Leave unrelated recurring/planned/budget mapping untouched except where compile-neighbor adjustments are required by later batches.
- [ ] Compile-neighbor audits for this batch (read before coding; touch only if compile requires it)
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardRepositoryContracts.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` (`dailySpending` argument currently receives `summary.dailyHistory`)
  - `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeViewModelStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`

> [!WARNING]
> Do **not** broaden Batch 1 into analytics-screen state refactors or chart-library rewrites. If a UI consumer still wants `Float`, add an explicit conversion at that consumer in Batch 4.

### Batch 2 — Budget/category domain contract cleanup
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/BudgetStatusSnapshot.kt`
  - Change `percentUsed` from `Float` to `Double`.
  - Leave amount/date/status fields unchanged.
  - Do **not** add unrelated invariant logic in A.6 unless a tiny `isFinite()` guard is required to keep tests meaningful; invariant hardening belongs to separate issue tracks.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt`
  - Change `percentageOfTotal` from `Float` to `Double`.
  - Keep `totalAmount`/`transactionCount`/`periodLabel` unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
  - Stop narrowing category percentages to `Float`; calculate and pass `Double` end-to-end.
  - Preserve sorting, fallback category metadata, and aggregation behavior.
- [ ] Compile-neighbor audits for this batch (touch only if compile requires it)
  - Producers of `BudgetStatusSnapshot`: `DashboardContractsAdapter.kt`, `FinancialWeatherRepository.kt`, `CalculateFinancialForecastUseCase.kt`
  - Direct consumers of `BudgetStatusSnapshot.percentUsed`: `FinancialHealthCalculator.kt`, `DashboardBriefingInputBuilder.kt`, `ComputeDashboardWidgetsUseCase.kt`
  - Budget-related UI/tests likely requiring explicit `toFloat()` or `Double` assertion updates: `BudgetScreen.kt`, `BudgetViewModel.kt`, `BudgetMonitor.kt`, `FinancialHealthCalculatorBoundaryTest.kt`, `SynthesisEngineTest.kt`, `SynthesisEngineStressTest.kt`, `SynthesisEngineGoldenTest.kt`, `DashboardWidgetConsistencyTest.kt`, `CrossGroupIntegrationTest.kt`
  - Category breakdown presentation/test neighbors: `CategoryBreakdownSheet.kt`, `RetroCategoryBreakdownSheet.kt`, `HomeScreen.kt`, `HomeViewModel.kt`, `CategoryDonutChart.kt`, `CategoryBreakdownTest.kt`, `TotalsAggregationEngineTest.kt`, `TotalsAggregationEngineDeepTest.kt`

> [!WARNING]
> Do **not** change `domain/budget/BudgetModels.kt`, `data/database/entity/Budget.kt`, or any Room schema in this batch unless a compile failure proves a one-line adjacent type shim is unavoidable. The preferred A.6 path is widening the domain snapshot, not persistence/model-schema expansion.

### Batch 3 — Forecast / Monte Carlo audit and compile-neighbor alignment
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/budget/MonteCarloBudgetImpact.kt` **(audit-first)**
  - Confirm the current A.6-targeted numeric fields (`budgetAmount`, `p50Forecast`, `expectedOverrun`, `probabilityOfOverrun`) are already `Double`.
  - Do **not** use A.6 to remove `displayMessage`/`formattedOverrun` or formatting helpers; that is a separate documentation-coupling issue.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/FinancialForecast.kt` **(audit-first)**
  - Confirm `confidence`, `pastSpendingPoints`, and `projectedSpendingPoints` already satisfy the `Double` rule.
  - Do **not** use A.6 to change `actionableInsights`, `ForecastHorizon`, or narrative colocation.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
  - Audit the construction path to ensure any A.6-targeted lists/ratios passed into `FinancialForecast` neighbors remain `Double`.
  - Keep the separate B05/B48 logic issues (placeholder pace/history/goal mapping) out of scope unless a tiny type-only compile fix is needed.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
  - Audit that `BudgetRiskInfo` / `MonteCarloBudgetImpact` interactions remain `Double` end-to-end and no stray `.toFloat()` conversion is introduced in the risk path.
  - Do not change messaging or risk-tier rules.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
  - Audit forecast-related producer flow for any unnecessary narrowing of forecast history/ratios.
  - Keep `RecurringPattern.confidence` and other unrelated cross-model `Float` contracts unchanged; they are outside A.6’s exact registry text.
- [ ] Test audits for this batch
  - `app/src/test/java/com/yourname/expensetracker/domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepositoryTest.kt`

> [!WARNING]
> If Batch 3 reveals that `MonteCarloBudgetImpact.kt` and `FinancialForecast.kt` already fully comply, leave their source files unchanged and record them as verified-no-op A.6 audits in the review/docs. Do **not** manufacture code churn just because the registry listed them.

### Batch 4 — UI/chart boundary conversions only
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
  - If `SynthesisEngine.calculateBlockPartyData(...)` still takes `List<Float>`, convert `summary.dailyHistory` to `Float` at this call site (`map(Double::toFloat)` or equivalent) instead of keeping the domain summary as `Float`.
  - Keep block-party math and widget ordering unchanged.
  - Audit `computeCategoryTotals()` and `SpendingTrendSeries` creation for explicit narrowing only where UI models remain `Float`.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
  - Update `BudgetVsActualItem.percentUsed` mapping if `BudgetStatusSnapshot.percentUsed` becomes `Double`; keep the UI item itself as `Float` only if Compose progress APIs require it.
  - Avoid broad refactors of `AnalyticsState`, `YearOverYearComparison`, or analytics domain models not named in A.6.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
  - Replace any direct `Float` assumptions on budget percentage inputs with explicit `.toFloat()` conversion at the rendering boundary.
  - Keep formatting, labels, and thresholds unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/components/CategoryDonutChart.kt`
  - If `domain.analytics.CategoryBreakdown` remains `Float`, this file may need no A.6 changes; if a compile-neighbor path now passes `Double`, narrow only inside draw logic.
  - Do not use A.6 to consolidate the duplicate category breakdown types.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/components/CategoryBreakdownSheet.kt`
  - Update boundary conversion from `CategoryBreakdown.percentageOfTotal` to progress-bar `Float` explicitly.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/components/RetroCategoryBreakdownSheet.kt`
  - Audit only if compile breaks surface after Batch 2; explicit `Float` conversion is acceptable here because it is pure presentation.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
  - If `DomainCategorySpending.percentage` stays `Float`, likely no code change; otherwise keep chart/progress math on `Float` at the UI boundary only.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/components/RetroTopCategoriesCard.kt`
  - Audit only if category percentage type changes ripple into this widget.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
  - If `BudgetStatusSnapshot.percentUsed` widens and a downstream UI path exposes it directly, narrow for progress bars and threshold comparisons locally.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
  - If `status.percentUsed` becomes `Double`, keep percentage-to-int formatting intact with an explicit `Double` calculation; no behavioral change.

> [!WARNING]
> Batch 4 must remain a **boundary-only** cleanup. Do **not** convert unrelated UI state models to `Double` if they only exist to satisfy Compose/chart APIs.

### Failure / rollback containment notes
- If widening `BudgetStatusSnapshot.percentUsed` produces too much churn because many call sites mirror `BudgetStatus.percentUsed: Float`, keep the widening confined to the snapshot mapping boundaries and add explicit `.toDouble()` at snapshot construction sites rather than changing `BudgetStatus` itself.
- If `ComputeDashboardWidgetsUseCase` is the only consumer broken by `SpendingSummary.dailyHistory -> List<Double>`, prefer a one-line local narrowing conversion over changing `SynthesisEngine` in A.6. `SynthesisEngine` is not a listed A.6 file and should remain behaviorally unchanged.
- If category percentage type cleanup starts spilling into `domain.analytics.CategoryBreakdown` or `CategoryInsight` models, stop and document the spillover; those are adjacent but outside the exact A.6 registry text unless compile-neighbor precision requires a minimal shim.
- If a supposedly compliant audit file (`FinancialForecast.kt`, `MonteCarloBudgetImpact.kt`) needs no source change, keep it untouched and carry the verification in the review/doc updates rather than forcing a no-op edit.

## 4. Verification Plan
- **Compile after every micro-batch:**
  - `./gradlew.bat :app:compileDebugKotlin`
- **Full unit test pass after all A.6 batches land:**
  - `./gradlew.bat :app:testDebugUnitTest`
- **Minimum focused tests by batch:**

### Batch 1 focused verification
- `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeViewModelStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`

### Batch 2 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/model/CategoryBreakdownTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBoundaryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineGoldenTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyTest.kt`

### Batch 3 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepositoryTest.kt`

### Batch 4 focused verification
- `app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModelStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsStateStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/DashboardWidgetConsistencyStressTest.kt`
- Re-run any presentation tests directly affected by explicit `toFloat()` boundary adjustments.

### Static verification / grep checks
- Search targeted files to ensure no A.6 domain/repository contract still declares:
  - `changePercent: Float`
  - `dailyHistory: List<Float>` / `previousDailyHistory: List<Float>`
  - `percentUsed: Float` in `BudgetStatusSnapshot`
  - `percentageOfTotal: Float` in `domain/model/CategoryBreakdown`
- Confirm any remaining `Float` usage after A.6 is either:
  - UI state / Compose rendering, or
  - non-A.6 model families not listed in the registry text and intentionally deferred
- Check imports and overload resolution after type widening; Kotlin can silently change numeric overload selection when literals lose the `f` suffix.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, append `[RESOLVED BY A.6]` to **only** the exact A.6 registry block supplied in the task:
    1. `### A.6: Mixed Numeric Types (Float vs Double for financial data)`
    2. `**Batches affected:** 24, 36, 46, 47`
    3. `**Severity:** MEDIUM`
    4. `**Description:** Financial domain models mix \`Float\` and \`Double\`: \`SpendingSummary\` uses \`Double\` totals with \`Float\` histories, \`BudgetStatusSnapshot\` stores \`percentUsed\` as \`Float\` while amounts are \`Double\`, \`CategoryBreakdown\` uses \`Float\` percentages, \`MonteCarloBudgetImpact\` uses \`Float\` for risk fields. This introduces avoidable precision loss in financial calculations.`
    5. `**Affected files:** \`SpendingSummary.kt\`, \`BudgetStatusSnapshot.kt\`, \`CategoryBreakdown.kt\`, \`DashboardCategoryBreakdown.kt\`, \`MonteCarloBudgetImpact.kt\`, \`FinancialForecast.kt\``
    6. `**Suggested fix:** Use \`Double\` consistently in all domain/repository models. Convert to \`Float\` only at chart/UI rendering boundaries.`
  - Do **not** mark adjacent A.x or B.x items resolved.

- **Final verification report updates — A.6 rows only:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-24.md`
    - Mark only the mixed numeric-type false-positive/convention row and any A.6-specific `BudgetStatusSnapshot`/`CategoryBreakdown`/`SpendingSummary` wording that directly maps to the epic as `[RESOLVED BY A.6]` or update its summary sentence to note the cleanup landed under A.6.
    - Do **not** touch unrelated invariant, sentinel, or formatting rows.
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
    - Update only the A.6-related `AdvancedAnalyticsModels`/`AnalyticsModels` precision note if an A.6-targeted row exists in the report narrative; do not alter unrelated analytics logic findings.
    - If no explicit A.6 row exists beyond model references, keep this file limited to a short note in the summary or leave untouched.
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-46.md`
    - Mark only the `CategoryBreakdown.kt` mixed numeric-type note and any directly related `FinancialForecast` / `MonteCarloBudgetImpact` A.6 precision wording if truly addressed.
    - Do **not** mark localization/text-pipeline/sentinel items resolved.
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-47.md`
    - Mark only Issue #5 (`SpendingSummary` numeric precision) and Issue #8 (`BudgetStatusSnapshot` numeric precision) as `[RESOLVED BY A.6]`.
    - Leave Issue #7 (`DashboardCategoryBreakdown.changeFromLastPeriod`) untouched unless it is explicitly implemented in another epic.

- **Deep-analysis mirrors — A.6 rows only:**
  - Update only the A.6-related rows/summary bullets in:
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-24.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-24-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md` **only if an A.6-specific row actually exists or a summary bullet explicitly references mixed numeric types**
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36-DEBUGGER.md` **same constraint as above**
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-46.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-46-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-47.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-47-DEBUGGER.md`
  - Explicitly keep documentation edits scoped to A.6 rows mentioning:
    - `SpendingSummary`
    - `BudgetStatusSnapshot.percentUsed`
    - `CategoryBreakdown.percentageOfTotal`
    - any batch-summary bullet that explicitly references mixed `Float`/`Double` numeric-type drift
  - Do **not** resolve rows about `FinancialForecast.actionableInsights`, `ForecastHorizon.days`, `MonteCarloBudgetImpact` formatting/UI coupling, or `DashboardCategoryBreakdown.changeFromLastPeriod` under A.6.
