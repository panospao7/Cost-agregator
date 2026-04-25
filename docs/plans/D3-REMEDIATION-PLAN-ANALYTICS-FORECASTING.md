# D3 Remediation Plan — Analytics / Forecasting / Insight Consistency

## Purpose

This plan targets the still-open D.3 standalone-medium issues that continue to cause analytics drift, forecasting divergence, performance inefficiencies, and budgeting/insight inconsistencies. It also folds in a small number of tightly-coupled registry dependencies where leaving them open would keep the same surfaces divergent after the D.3 fixes land.

## Sources Reviewed

- `docs/reviews/D3-STANDALONE-MEDIUM-FINAL-SUMMARY.md`
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/D3-SUBBATCH-D9-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D10-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D11-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D12-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D13-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D14-REVIEW.md`
- `docs/reviews/D3-SUBBATCH-D15-REVIEW.md`

## Scope

### In scope

- Still-open D.9-D.15 analytics/forecasting/budgeting issues related to:
  - analytics engine drift
  - weekly/monthly boundary inconsistencies
  - N+1 / aggregate-query opportunities
  - forecasting model divergence
  - threshold / confidence correctness
  - insight-engine reimplementation drift
- Closely-coupled registry dependencies that affect the same surfaces and would otherwise keep outputs inconsistent after D.3 fixes:
  - Group 9: analytics engine consistency
  - Group 11: weekly aggregate boundary contract
  - Group 17: merchant analytics inconsistency
  - Group 18: forecasting duplication / divergent assumptions
  - Group 28: financial weather vs dashboard forecast divergence
  - verified adjacent open items in `D.16` and `D.1` where they are directly part of the same drift/performance lane

### Out of scope

- UI-only cleanup unrelated to analytics/forecasting correctness
- localization-only issues
- AI/privacy issues outside analytics/forecasting consumers
- Room schema version bumps or migrations unless later implementation proves a new projection cannot be added without one (not expected)
- unrelated notification/service/worker fixes

## Assumptions / Unknowns

- **Assumption:** merchant grouping should converge on canonical `merchantKey`, while user-facing labels remain display names.
- **Assumption:** forecast surfaces should converge on the same recurring-pattern source set; current best candidate is “manual recurring + high-confidence detected recurring, with manual taking precedence.”
- **Assumption:** weekly/monthly analytics should continue using local-calendar, half-open period semantics already established by `TimePeriodUtils`.
- **Unknown:** whether product wants `FinancialHealthCalculator` reweighted so `EXCELLENT` remains reachable, or whether status bands should instead be lowered to match the current max score shape.
- **Unknown:** whether `TransferDirectionAnalytics` needs historical persistence/rebuild, or only in-memory correction accuracy for the current session.
- **Unknown:** whether `BudgetForecastingEngine.generateForecast()` callers depend on the persisted forecast id today; if not, the return-contract fix can remain inside the same batch but should be validated against callers before signature changes.

## Grouped Issue List

### Group A — Analytics boundary drift and ordering correctness

1. **D.15 / Group 11** — `ExpenseDao` weekly aggregates expose `MIN(date)` / `MAX(date)` transaction timestamps as week boundaries instead of canonical Monday-start week windows.
2. **D.15** — `DayOfWeekAnalyzer` sorts results by total spend instead of weekday order.
3. **D.14** — `AdvancedAnalyticsEngine` current-period sparklines stop before today, which can render the first day of a period as empty.
4. **D.14** — `SpendingPersonalityClassifier` confidence mixes normalized features with raw `transactionsPerMonth`.
5. **D.9** — `FinancialHealthCalculator` treats `budgetStatuses.all { ... }` as true for an empty list and awards an “all budgets on track” bonus with no budgets configured.
6. **D.10** — `FinancialHealthCalculator` score ceilings make `EXCELLENT (85-100)` unreachable.

### Group B — Merchant analytics and insight-engine reimplementation drift

1. **D.15 / Group 9** — `InsightsEngine` injects focused analytics engines but still reimplements monthly comparison, category insights, merchant insights, and day-of-week logic inline.
2. **D.15 / Group 17** — `AdvancedAnalyticsEngine` merchant analytics group by raw merchant text, fragment aliases, and re-filter the full historical list for each merchant (`O(merchants × history)`).
3. **D.16 (coupled)** — `MerchantInsightEngine` still groups by `merchant.lowercase()` rather than canonical `merchantKey`.
4. **D.16 (coupled)** — `CategoryInsightEngine` re-filters `previousExpenses` for every category (`O(categories × previous-expenses)`).
5. **D.16 (coupled)** — `AdvancedAnalyticsEngine.getMerchantAnalytics()` uses `getExpensesSince(historicalStart)` without capping history at `period.endMs`, allowing post-period data to leak into history.
6. **D.2 coupled dependency** — `InsightsEngine` merchant anomaly baseline still includes current-month data, and recurring frequency output still uses `30 / intervalDays` instead of occurrence semantics.

### Group C — Forecasting divergence across budget / weather / forecast surfaces

1. **D.15** — `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing, timezone rules, trend heuristics, and confidence formulas for the same history.
2. **D.10** — `RecurringIncomeTracker` confidence still compares millisecond-squared variance against a tiny fixed threshold rather than day-scale variance.
3. **Group 28** — `FinancialWeatherRepository` merges detected + manual recurring patterns, while dashboard forecast use cases still derive forecast inputs from manual recurring rows only.
4. **Group 18** — `FinancialStressForecastEngine`, `MonteCarloSpendingSimulator`, and `DataQualityAssessor` still describe/score forecast confidence with divergent assumptions.
5. **D.16 (coupled)** — `BudgetForecastingEngine.generateForecast()` returns the pre-insert object instead of the persisted forecast identity.

### Group D — Performance / N+1 / aggregate-query opportunities

1. **D.15** — `AdvancedAnalyticsEngine` merchant analytics still perform repeated merchant-specific filtering over the same history.
2. **D.16 (coupled)** — `CategoryInsightEngine` still performs a previous-period filter per category.
3. **D.1 (coupled)** — `BudgetRepository.getSuggestions()` still loops categories and performs one aggregate query per category instead of batching.
4. **Regression lock** — `AdvancedAnalyticsDashboard` monthly trend N+1 is resolved; keep it that way while refactoring adjacent analytics code.

### Group E — In-memory analytics correction consistency

1. **D.15** — `TransferDirectionAnalytics` user corrections update accuracy counters only; incoming/outgoing totals and top endpoints remain stale.

### Threshold / percentile note

- The percentile/effective-amount bug in `SpendingThresholdCalculator` is **already resolved**. No production fix is needed in this plan unless regression tests show drift. The remaining “threshold correctness” work is now score/confidence threshold normalization in `FinancialHealthCalculator`, `RecurringIncomeTracker`, and `SpendingPersonalityClassifier`.

## File-Level Fix Plan

### Primary production files to modify

- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - replace transaction-derived weekly boundary metadata with canonical period-boundary output or week-key-only output plus canonical mapping upstream
  - add/extend aggregate support for batched budget suggestions if needed
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - normalize weekly aggregate boundary contract if repository mapping is the safest place to do so
- `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - remove per-category aggregate loop in `getSuggestions()`
- `app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`
  - stop using forecast-input assembly logic that diverges from dashboard/use-case forecast paths
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
  - preserve chronological weekday order
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
  - include the current day in sparklines
  - canonicalize merchant grouping
  - eliminate repeated full-history re-filtering
  - cap historical reads at the analysis period end
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
  - delegate to canonical calculators/engines instead of reimplementing logic inline
  - align anomaly baseline and recurring-frequency semantics while the file is open
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt`
  - group on canonical merchant identity, not raw/lowercased merchant strings
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
  - precompute previous-period totals instead of filtering per category
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt`
  - remain the authoritative monthly-comparison implementation used by `InsightsEngine`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt`
  - normalize confidence inputs and split count-based data quality from feature-scale variance
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt`
  - make correction handling rebuild/adjust endpoint totals and direction counters coherently
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`
  - consume the same normalized monthly history helper as forecasting
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
  - consume the same normalized monthly history helper as autopilot
  - return persisted forecast identity/contract-correct object
- `app/src/main/java/com/yourname/expensetracker/domain/income/RecurringIncomeTracker.kt`
  - convert interval variance/confidence to day-scale semantics
- `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
  - remove empty-budget bonus bug
  - reconcile weight/status thresholds so score bands are internally consistent
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
  - share the same recurring-pattern and forecast-input assembly used by financial weather/dashboard forecast paths
- `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
  - consume the unified forecast input contract only; do not preserve parallel assumptions in callers
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
  - align confidence/horizon semantics with the simulation layer or explicitly adapt them
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
  - share confidence/horizon assumptions with stress-forecast consumers where appropriate
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/DataQualityAssessor.kt`
  - align confidence-language thresholds with the surfaces that expose forecast confidence

### Preferred helper files to create (if extraction is needed)

- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsWindowingSupport.kt`
  - shared week/day ordering and canonical period-boundary helpers for analytics consumers
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetHistorySeriesBuilder.kt`
  - single source of truth for month-key generation, zero-fill, observed-month counts, and local-time bucketing
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt`
  - single source of truth for recurring merge rules, `pastSumDaily`, and `SpendingPace` assembly across weather/dashboard forecast paths

### Tests to update or add

- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboardTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifierTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TransferDirectionAnalyticsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepositoryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt`
- `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`

## Batching Strategy

### Batch 1 — Canonical analytics boundaries and chronological ordering

**Goal:** fix period-boundary contract errors and chart-order drift before touching higher-level consumers.

**Files:**
- `ExpenseDao.kt`
- `ExpenseRepository.kt`
- `DayOfWeekAnalyzer.kt`
- `AdvancedAnalyticsEngine.kt`
- related boundary/order tests

**Work:**
- replace `MIN(date)` / `MAX(date)` week-boundary semantics with canonical Monday-start weekly boundaries
- keep the weekly aggregate output half-open and local-calendar aligned
- fix `DayOfWeekAnalyzer` to emit Monday→Sunday order without sorting by spend
- fix sparkline generation so current-day data is included when the requested period contains “today”

**Dependencies:** none; this is the lowest-blast-radius foundation batch.

**Validation strategy:**
- unit tests for weekday order and current-day sparkline inclusion
- DAO boundary tests for canonical week start/end behavior
- confirm no regression in already-fixed monthly trend behavior

**Completion criteria:**
- weekly outputs no longer depend on first/last transaction timestamps
- weekday consumers receive stable chronological ordering
- first-day/current-day sparklines are non-empty when spend exists

**Failure / rollback note:**
- if SQL-side canonical week-boundary generation becomes brittle, keep SQL responsible only for totals and move boundary normalization into repository mapping; do not keep the current `MIN/MAX` contract.

### Batch 2 — Merchant identity convergence and insight-engine delegation

**Goal:** stop merchant-name drift and remove duplicate business logic across analytics engines.

**Files:**
- `AdvancedAnalyticsEngine.kt`
- `InsightsEngine.kt`
- `MerchantInsightEngine.kt`
- `CategoryInsightEngine.kt`
- `MonthlyComparisonCalculator.kt`
- related analytics tests

**Work:**
- define one merchant grouping rule (`merchantKey`) and one display-label rule (`displayName`/best human-readable merchant)
- bound merchant-history reads to the analysis window end
- pre-group merchant history once instead of filtering the full history for each merchant
- precompute previous-category totals once instead of re-filtering per category
- refactor `InsightsEngine.generateInsights()` to call the injected calculators/engines rather than local `build*` implementations
- while `InsightsEngine` is open, fold in the coupled drift fixes for merchant anomaly baselines and recurring-frequency output

**Dependencies:** Batch 1 helpful but not blocking.

**Validation strategy:**
- deep/stress analytics tests for ranking stability and performance-sensitive paths
- merchant alias/canonicalization regression tests
- side-by-side tests proving `InsightsEngine` and delegated calculators now match for the same fixture set

**Completion criteria:**
- no targeted path groups merchants by raw/lowercased merchant text only
- `InsightsEngine` no longer reimplements monthly/category/merchant/day-of-week logic inline
- merchant anomaly baselines exclude current-month data
- targeted performance hotspots no longer require `O(merchants × history)` or `O(categories × previous-expenses)` scans

**Failure / rollback note:**
- if full delegation is too disruptive, land merchant canonicalization + precomputed maps first and then move `InsightsEngine` delegation in a second pass; do not mix a half-delegated state with multiple competing merchant/grouping rules.

### Batch 3 — Budget forecasting / autopilot history alignment

**Goal:** make budget forecast and autopilot consume the same normalized monthly history and comparable confidence inputs.

**Files:**
- `BudgetAutopilotEngine.kt`
- `BudgetForecastingEngine.kt`
- `RecurringIncomeTracker.kt`
- preferred helper: `BudgetHistorySeriesBuilder.kt`
- related budget/forecast tests

**Work:**
- create one shared month-series builder for:
  - month-key generation
  - local-time bucketing
  - zero-fill behavior
  - observed-vs-filled month counts
- route both autopilot and forecasting engines through that builder
- align trend and confidence inputs so the same history cannot produce contradictory “stable vs risky” signals for purely implementation reasons
- convert `RecurringIncomeTracker` variance/confidence from ms² semantics to day-scale semantics
- fix `BudgetForecastingEngine.generateForecast()` to return contract-correct persisted data if callers need the stored id

**Dependencies:** independent, but should precede cross-surface forecast convergence.

**Validation strategy:**
- deterministic budget fixtures shared between autopilot and forecasting tests
- explicit comparison tests proving both engines see the same normalized history series
- recurring-income confidence regressions with weekly/biweekly/monthly deposit fixtures

**Completion criteria:**
- autopilot and forecasting ingest identical month series for the same budget/history window
- confidence differences, if any, come from explicit product rules rather than different bucketing/timezone math
- recurring-income confidence no longer depends on raw ms² thresholds

**Failure / rollback note:**
- if heuristic unification is too risky, keep shared history normalization mandatory and document any remaining output-layer differences explicitly; do not allow separate local month-key helpers to survive.

### Batch 4 — Forecast input convergence across weather, dashboard, and synthesis paths

**Goal:** remove remaining divergence between financial weather and dashboard forecast generation.

**Files:**
- `FinancialWeatherRepository.kt`
- `CalculateFinancialForecastUseCase.kt`
- `SynthesisEngine.kt`
- preferred helper: `ForecastInputAssembler.kt`
- optionally `FinancialStressForecastEngine.kt`, `MonteCarloSpendingSimulator.kt`, `DataQualityAssessor.kt`

**Work:**
- choose and document one recurring-pattern merge policy across all forecast consumers
- centralize `pastSumDaily`, `SpendingPace`, planned-expense mapping, savings-goal mapping, and budget-snapshot assembly
- remove manual-only forecast input assembly where weather/dashboard already use merged manual + detected patterns
- keep `SynthesisEngine` as a pure consumer of the shared input contract, not a place where callers smuggle different assumptions
- if batch size permits, align stress-forecast confidence language with Monte Carlo/data-quality outputs; otherwise record it as an explicit follow-on subtask under the same remediation stream

**Dependencies:** Batch 3 preferred.

**Validation strategy:**
- same fixture set fed through `FinancialWeatherRepository` and `CalculateFinancialForecastUseCase`
- verify matching recurring-pattern counts, `pastSumDaily`, and spending-pace inputs
- regression tests around day/month rollover behavior remain green

**Completion criteria:**
- dashboard/weather forecast surfaces no longer disagree because they consume different recurring-input sources
- forecast-input assembly lives in one place
- stress/Monte Carlo confidence semantics are either aligned or explicitly isolated behind documented adapter logic

**Failure / rollback note:**
- if stress-forecast semantic alignment proves too large for this batch, land the shared dashboard/weather input assembler first and defer stress-model harmonization to a follow-on patch without reopening divergent recurring-source logic.

### Batch 5 — Threshold correctness, correction consistency, and residual aggregate-query cleanup

**Goal:** close the remaining score/threshold bugs and the last targeted N+1 cleanup in the same lane.

**Files:**
- `FinancialHealthCalculator.kt`
- `SpendingPersonalityClassifier.kt`
- `TransferDirectionAnalytics.kt`
- `BudgetRepository.kt`
- `ExpenseDao.kt` (if new grouped suggestion query is needed)
- `SpendingThresholdCalculator.kt` tests only

**Work:**
- require a non-empty budget list before awarding “all budgets on track” bonus points
- reconcile health-score ceilings/status bands so `EXCELLENT` is not unreachable
- normalize or separate `transactionsPerMonth` before confidence variance is computed in `SpendingPersonalityClassifier`
- make transfer-direction corrections update direction totals and top-endpoint lists, not just accuracy counters
- replace per-category budget suggestion loops with one batched grouped-total path
- add regression coverage to ensure the already-fixed percentile/effective-amount behavior does not regress

**Dependencies:** Batch 2 may already touch `SpendingPersonalityClassifier`; merge carefully.

**Validation strategy:**
- health-score regression tests for empty-budget and score-band behavior
- personality-classifier tests for normalized confidence math
- transfer-direction correction tests covering incorrect → corrected → reverted flows
- DAO/repository tests ensuring budget suggestions are batched and spend semantics remain correct

**Completion criteria:**
- no vacuous all-budgets bonus remains
- health-score status bands and achievable score ceilings are internally consistent
- transfer correction analytics fully reconcile after correction/revert
- `BudgetRepository.getSuggestions()` no longer performs one aggregate query per category

**Failure / rollback note:**
- if product decision on health-score bands is blocked, land the empty-budget-bonus fix immediately and keep the weight/status-band change behind a separate approval step; do not hold correctness on the empty-bonus bug for a product copy decision.

## Performance-Risk Notes

- **Merchant canonicalization will move aggregates.** Expect some merchants to merge or rename when `merchantKey` becomes authoritative. This is a correctness improvement, but ranking snapshots and golden outputs will change.
- **Weekly boundary contract changes can break chart assumptions.** Any consumer that expects transaction-derived week start/end timestamps must be updated in the same batch.
- **Shared history normalization will shift forecast numbers.** Budget forecast/autopilot outputs may change even when no “bug” is visible in isolation, because they will stop using separate month-series rules.
- **Delegation can change fallback behavior.** `InsightsEngine` currently protects each branch independently; refactoring must preserve cancellation behavior and branch-level fallback isolation.
- **Transfer correction recomputation must stay bounded.** Rebuilding full in-memory stats on every correction is acceptable only if bounded by the existing tracked-transfer cap; avoid unbounded recompute cost.
- **Aggregate-query replacement must preserve A.1/A.10 semantics.** Any new grouped DAO query must keep `effectiveAmount`, spending-only filters, and half-open date boundaries intact.

## Validation Matrix

- **Boundary correctness:** `ExpenseDaoBoundaryConsistencyTest`, `ExpenseDaoTest`, `DayOfWeekAnalyzerTest`, `AdvancedAnalyticsEngineTest`
- **Analytics convergence:** `InsightsEngineTest`, `InsightsEngineDeepTest`, `InsightsEngineValidationTest`, `MerchantInsightEngineTest`, `CategoryInsightEngineTest`, `AdvancedAnalyticsEngineStressTest`
- **Forecast convergence:** `BudgetAutopilotEngineTest`, `BudgetForecastingEngineTest`, `CalculateFinancialForecastUseCaseTest`, `FinancialWeatherRepositoryTest`, `FinancialStressForecastEngineTest`, `MonteCarloSpendingSimulatorTest`
- **Threshold/correction correctness:** `SpendingPersonalityClassifierTest`, new/updated `FinancialHealthCalculator` tests, `TransferDirectionAnalyticsTest`, `SpendingThresholdCalculatorTest`

## Overall Acceptance Criteria

- [ ] Weekly analytics use canonical calendar boundaries, not transaction-derived `MIN/MAX` timestamps.
- [ ] Weekday analytics and charts emit stable chronological order.
- [ ] Current-period sparklines include the current day when applicable.
- [ ] Merchant analytics/insights group by canonical merchant identity and keep display labels human-readable.
- [ ] `InsightsEngine` delegates to canonical analytics engines/calculators instead of reimplementing overlapping logic.
- [ ] No targeted path retains the known `O(merchants × history)`, `O(categories × previous-expenses)`, or per-category budget-suggestion query pattern.
- [ ] Budget autopilot and budget forecasting consume the same normalized monthly history and comparable confidence inputs.
- [ ] Financial weather and dashboard forecast paths consume the same recurring-pattern and forecast-input assembly rules.
- [ ] Confidence / score thresholds operate on comparable units and no vacuous bonuses or unreachable score bands remain.
- [ ] Transfer-direction corrections reconcile counters, totals, and top endpoints consistently.
- [ ] Targeted regression/unit/instrumentation tests are updated or added for every touched batch.

## Recommended Execution Order

1. **Batch 1** — boundaries / ordering foundation
2. **Batch 2** — merchant identity + insight delegation
3. **Batch 3** — forecast/autopilot history alignment
4. **Batch 4** — weather/dashboard/synthesis forecast-input convergence
5. **Batch 5** — thresholds, corrections, and remaining aggregate-query cleanup

This order minimizes rollback cost: period primitives first, then analytics consumers, then forecast-core alignment, then cross-surface forecast convergence, then residual score/performance cleanup.
