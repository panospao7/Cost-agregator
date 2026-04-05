# Testing Overhaul — Master Workflow

> **Date:** April 5, 2026 (updated with gap analysis findings)  
> **Total scope:** ~407 new tests + ~142 deletions + ~15 refactors  
> **Phases:** 12 (1 sequential prereq + 11 parallelizable)  
> **Estimated total effort:** ~32 agent-sessions  
> **Gap analysis:** See `TESTING-GAP-ANALYSIS.md` for the full audit behind these additions

---

## Document Map

```
docs/quality/
├── TESTING-WORKFLOW.md          ← YOU ARE HERE (master orchestration)
├── TESTING-GAP-ANALYSIS.md      ← Gap audit validating this workflow against production code
├── TESTING-AGENT-PLAYBOOK.md    ← Read FIRST (conventions, templates, base classes)
├── TEST-CLEANUP-PLAN.md         ← Phase 0 instructions (cleanup before creation)
├── CRASH-TEST-SCENARIOS.md      ← Golden values & edge cases (referenced by phases)
├── COMPONENT-TEST-MATRIX.md     ← Full 642-file inventory (referenced by phases)
├── REVIEW-testing-implementation.md     ← Background analysis
├── REVIEW-database-repository-subsystem.md
├── REVIEW-location-ocr-groups.md
└── REVIEW-analytics-savings-engines.md
```

### How agents use these docs

1. **Before any phase:** Read `TESTING-AGENT-PLAYBOOK.md` — conventions, base classes, DO NOTs
2. **During a phase:** Follow the phase instructions below. Reference `CRASH-TEST-SCENARIOS.md` for golden values and `COMPONENT-TEST-MATRIX.md` for file lists
3. **After a phase:** Run `./gradlew test` on affected modules to verify compilation and passing

---

## Phase Dependency Graph

```
Phase 0 (Cleanup) ─── MUST COMPLETE FIRST
       │
       ├──→ Phase 1A (Regression Guards)      ┐
       ├──→ Phase 1B (Split & Settlement)     │
       ├──→ Phase 1C (Budget & Pace)          ├── ALL PARALLEL
       ├──→ Phase 1D (Health & Currency)      │
       ├──→ Phase 1E (Analytics Sub-Engines)  ┘
       │
       │    (Phase 1 all complete)
       │
       ├──→ Phase 2A (E2E Pipelines)          ┐
       ├──→ Phase 2B (Cross-Component Suites) ├── ALL PARALLEL
       ├──→ Phase 2C (ViewModels)             │
       ├──→ Phase 2D (Repositories)           ┘
       │
       │    (Phase 2 all complete)
       │
       └──→ Phase 3A (DAO Instrumented)       ┐
       └──→ Phase 3B (Remaining Coverage)     ┘── PARALLEL
```

**Rule:** All Phase 1 batches can run simultaneously. All Phase 2 batches can run simultaneously. Phase 2 depends on Phase 1 (since E2E tests use the unit-tested engines). Phase 0 must finish before anything else starts.

---

## Phase 0 — Cleanup (SEQUENTIAL, prerequisite)

**Goal:** Remove waste, fix misplacements, standardize patterns.  
**Detailed instructions:** `TEST-CLEANUP-PLAN.md`  
**Cannot be parallelized** — it deletes/moves files that other agents might reference.

### Steps

| Step | Action | Files Affected |
|------|--------|---------------|
| 0.1 | Extract real test from `AnalyticsPipelineIntegrationTest.kt` → `integration/EffectiveAmountPipelineIntegrationTest.kt` | 1 created |
| 0.2 | Delete 6 fake integration test files | 6 deleted |
| 0.3 | Delete `RegexVerificationTest.kt` | 1 deleted |
| 0.4 | Delete 2 assertion-less test methods (in `OcrDocumentTest`, `CategorizationEngineTest`) | 2 methods removed |
| 0.5 | Move+rename `OcrParserTest.kt` → `domain/receipt/ReceiptParserTest.kt` | 1 moved |
| 0.6 | Move+rename `OcrDocumentTest.kt` → `domain/receipt/ReceiptParserOcrPatternsTest.kt` | 1 moved |
| 0.7 | Move+rename `InsightsLogicTest.kt` → `domain/analytics/RecurringIntervalLogicTest.kt` | 1 moved |
| 0.8 | Merge OCR test duplicates (7 overlapping scenarios between the two moved files) | ~7 tests removed |
| 0.9 | Refactor `CategorizationEngineTest.kt`: `runBlocking` → `runTest` | 1 refactored |
| 0.10 | Refactor 5 files to extend `AnalyticsEngineTestBase` | 5 refactored |
| 0.11 | Audit 3 surviving integration files (`MultiCurrencyAnalyticsTest`, `CategorizationPipelineIntegrationTest`, `ExpenseCreationPipelineIntegrationTest`) for fake-test pattern — delete if fake, keep if real | 3 audited |
| 0.12 | Audit `CategorizationEngineDebugTest.kt` for assertion quality | 1 audited |
| 0.13 | Verify: `./gradlew test` passes | — |

**Output:** Clean baseline — ~260 test files, 0 fake tests, 0 misplaced files.

---

## Phase 1A — Regression Guards (Golden Value Tests)

**Goal:** Lock down current calculation outputs so any future change is caught immediately.  
**Parallelizable with:** 1B, 1C, 1D, 1E  
**Reference:** `CRASH-TEST-SCENARIOS.md` Sections 2-6, Appendix A  
**Estimated tests:** 14 | **Estimated effort:** 1 session

### Files to Create

| # | File | Production Class | Scenarios |
|---|------|-----------------|-----------|
| 1 | `domain/logic/SplitCalculatorGoldenTest.kt` | `SplitCalculator.kt` | CRASH-TEST 4.1 (€100/3), 4.2 (€100/7), 4.3-4.4 (percentage splits) |
| 2 | `domain/analytics/SpendingPaceGoldenTest.kt` | `SpendingPaceCalculator.kt` | CRASH-TEST 3.1 (golden March day 15), 3.5 (last day) |
| 3 | `domain/budget/BudgetCalculatorGoldenTest.kt` | `BudgetCalculator.kt` | CRASH-TEST 2.1 (MONTHLY), 2.2 (ROLLING), 2.5 (YEARLY) |
| 4 | `domain/health/HealthScoreGoldenTest.kt` | `FinancialHealthScoreV2.kt` | CRASH-TEST 6.1 (golden March), 6.5 (new user defaults) |
| 5 | `domain/currency/CurrencyConverterGoldenTest.kt` | `CurrencyConverter.kt` | CRASH-TEST 5.1 (same currency), 5.3 (cross-rate) |
| 6 | `domain/logic/SynthesisEngineGoldenTest.kt` | `SynthesisEngine.kt` | CRASH-TEST 10.1-10.3 (confidence thresholds: 0.70/0.90/0.85, biweekly ±2 days) |
| 7 | `domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt` | `MonteCarloSpendingSimulator.kt` | CRASH-TEST N-7e: seed=42L, 1000 iterations, exact P50 snapshot |
| 8 | `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt` | `AutomatedSavingsRuleEngine.kt` | CRASH-TEST N-7g: €17.30 roundUp=5 → savingsAmount=2.70 |

### Agent context

- Read `TESTING-AGENT-PLAYBOOK.md` Section 3 (base classes), Section 5 (golden dataset)
- All files extend `AnalyticsEngineTestBase` except `SplitCalculatorGoldenTest` and `AutomatedSavingsRuleEngineGoldenTest` (pure functions / simple mocks)
- `SynthesisEngine` depends on `TimeProvider` only — extend `AnalyticsEngineTestBase`
- `MonteCarloSpendingSimulator` depends on `HistoricalSpendingDistribution` and `DataQualityAssessor` — mock both
- Use `assertApproxEquals` for all numerical assertions
- The golden values in the test MUST match `CRASH-TEST-SCENARIOS.md` exactly

---

## Phase 1B — Split, Settlement & Groups

**Goal:** Cover the most critical untested financial engines.  
**Parallelizable with:** 1A, 1C, 1D, 1E  
**Reference:** `CRASH-TEST-SCENARIOS.md` Section 4, `COMPONENT-TEST-MATRIX.md` Section 9  
**Estimated tests:** 30 | **Estimated effort:** 2 sessions

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `domain/logic/SplitCalculatorTest.kt` | `SplitCalculator.kt` | Equal/percentage/amount/unequal splits, sum preservation, CRASH-TEST 4.1-4.5 |
| 2 | `domain/logic/SplitCalculatorStressTest.kt` | `SplitCalculator.kt` | Int overflow (CRASH-TEST 4.5), 100 members, zero amount |
| 3 | `domain/logic/CustomSplitParserTest.kt` | `CustomSplitParser.kt` | `parseAndValidate` for all split modes, `referencesMember`, AMOUNT_TOLERANCE=0.01, PERCENT_TOLERANCE=0.1 |
| 4 | `domain/groups/SettlementCalculatorTest.kt` | `SettlementCalculator.kt` | Greedy solver, DFS solver, CRASH-TEST 4.6-4.8 |
| 5 | `domain/groups/SettlementCalculatorStressTest.kt` | `SettlementCalculator.kt` | 15-member DFS (Bug B-03), all-zero balances, DFS_ITERATION_LIMIT=100_000, TIME_BUDGET=0.5s |
| 6 | `domain/groups/SharedExpenseManagerTest.kt` | `SharedExpenseManager.kt` | Balance calculation, split dispatch, CRASH-TEST 4.9, `removeMember` guards, `addExpense` isFinite validation |
| 7 | `domain/groups/SharedExpenseBudgetOffsetEngineTest.kt` | `SharedExpenseBudgetOffsetEngine.kt` | Budget offset logic using SharedExpenseManager |
| 8 | `domain/groups/usecase/GroupUseCasesTest.kt` | `AddGroupExpenseUseCase.kt`, `DeleteGroupUseCase.kt`, `DeleteGroupMemberUseCase.kt` | Delegation correctness, error propagation |

### Agent context

- `SplitCalculator`, `SettlementCalculator`, and `CustomSplitParser` are **pure functions** — no mocks, no base class
- `SharedExpenseManager` needs `SharedExpenseDataPort` mock + `@IoDispatcher` → pass `testDispatcher`
- `SharedExpenseBudgetOffsetEngine` needs `SharedExpenseManager` mock
- Group use cases need `GroupsRepository` mock
- Read production files first: `domain/logic/SplitCalculator.kt`, `domain/logic/CustomSplitParser.kt`, `domain/groups/SettlementCalculator.kt`, `domain/groups/SharedExpenseManager.kt`, `domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- Verify bugs B-01, B-02, B-03 per `CRASH-TEST-SCENARIOS.md` Section 4 and Appendix B
- `SettlementCalculator` companion constants: `DFS_ITERATION_LIMIT = 100_000`, `DFS_TIME_BUDGET_NS = 500_000_000L`
- Use `TESTING-AGENT-PLAYBOOK.md` Section 6.4 (Split/Settlement template)

---

## Phase 1C — Budget & Spending Pace

**Goal:** Cover budget period calculation edge cases and pace boundary precision.  
**Parallelizable with:** 1A, 1B, 1D, 1E  
**Reference:** `CRASH-TEST-SCENARIOS.md` Sections 2-3  
**Estimated tests:** 27 | **Estimated effort:** 2 sessions

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `domain/budget/BudgetCalculatorBoundaryTest.kt` | `BudgetCalculator.kt` | CRASH-TEST 2.3 (anchor coercion), 2.4 (leap year), 2.6 (weekly), 2.7 (DST), 2.8 (empty mode). Note: ROLLING MONTHLY uses fixed 30 days (Bug B-15) |
| 2 | `domain/analytics/SpendingPaceBoundaryTest.kt` | `SpendingPaceCalculator.kt` | Already exists — **extend with**: CRASH-TEST 3.2 (day 1), 3.3 (NO_BASELINE), 3.4 (exactly 90%/110%), 3.6 (Float precision, Bug B-14). Constants: UNDER=90f, OVER=110f, conservative multiplier=3.0, full-linear day=7 |
| 3 | `domain/budget/BudgetMonitorTest.kt` | `BudgetMonitor.kt` | `checkBudgets()` triggers notifications at warning/critical/exceeded thresholds, `cleanup()` |
| 4 | `domain/budget/SharedBudgetManagerTest.kt` | `SharedBudgetManager.kt` | `getSharedBudgetProgress`, `getMemberContributions` — deps: BudgetRepository, ExpenseDao, TimeProvider |
| 5 | `domain/budget/BudgetRecommendationEngineTest.kt` | `BudgetRecommendationEngine.kt` | `generateRecommendations` risk tiers, `getBudgetHealthSummary`, `getRiskEmoji` — no constructor deps (pure function engine) |
| 6 | `domain/budget/BudgetForecastingEngineStubTest.kt` | `BudgetForecastingEngine.kt` | Add to existing test: verify `updateForecastAccuracy` is a no-op stub; verify unused public constants `MIN_HISTORY_MONTHS`, `CONFIDENCE_THRESHOLD_HIGH/MEDIUM` |

### Agent context

- `BudgetCalculator` depends only on `TimeProvider` — extend `AnalyticsEngineTestBase`
- `SpendingPaceCalculator` depends only on `TimeProvider`
- `SpendingPaceBoundaryTest.kt` already exists — **add** new test methods, do NOT recreate the file
- `BudgetMonitor` depends on `BudgetRepository`, `TimeProvider`, `NotificationService`, `@IoDispatcher` — mock all, pass `testDispatcher`
- `SharedBudgetManager` depends on `BudgetRepository`, `ExpenseDao`, `TimeProvider` — extend `AnalyticsEngineTestBase`
- `BudgetRecommendationEngine` has `@Inject constructor()` — no deps, pure function engine
- Verify bugs B-14, B-15 per Appendix B
- Use `FakeTimeProvider.forDate(2026, 3, 29, 3, 0)` for DST scenarios
- Check `BudgetForecastingEngine` constants: `MIN_HISTORY_MONTHS=3`, `CONFIDENCE_THRESHOLD_HIGH=0.8`, `CONFIDENCE_THRESHOLD_MEDIUM=0.6` (declared but unused in file — document in test)

---

## Phase 1D — Health Score & Currency

**Goal:** Cover financial health scoring edge cases and currency conversion paths.  
**Parallelizable with:** 1A, 1B, 1C, 1E  
**Reference:** `CRASH-TEST-SCENARIOS.md` Sections 5-6  
**Estimated tests:** 15 | **Estimated effort:** 1 session

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `domain/health/HealthScoreEdgeCaseTest.kt` | `FinancialHealthScoreV2.kt` | CRASH-TEST 6.2 (zero income), 6.3 (single category), 6.5 (new user asymmetry), 6.6 (toInt truncation), 6.7 (deposit-only) |
| 2 | `domain/currency/CurrencyConverterEdgeCaseTest.kt` | `CurrencyConverter.kt` | CRASH-TEST 5.2 (unknown currency), 5.4 (stale rate), 5.5 (zero amount), 5.6 (negative amount), 5.8 (accumulated drift) |
| 3 | `domain/currency/CurrencyConverterStressTest.kt` | `CurrencyConverter.kt` | 500 conversions accumulated, same-amount roundtrip |

### Agent context

- `FinancialHealthScoreV2` has many dependencies — see `TESTING-AGENT-PLAYBOOK.md` Section 8, mock ALL
- `CurrencyConverter` depends on `ExchangeRateStore` interface — mock it
- Verify bugs B-04, B-05 per Appendix B
- Read `COMPONENT-TEST-MATRIX.md` Section 12 for existing test status

---

## Phase 1E — Analytics Sub-Engines

**Goal:** Cover the 4 untested analytics sub-engines that feed into `InsightsEngine`.  
**Parallelizable with:** 1A, 1B, 1C, 1D  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 6 (rows 210-213)  
**Estimated tests:** 21 | **Estimated effort:** 2 sessions

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `domain/analytics/CategoryInsightEngineTest.kt` | `CategoryInsightEngine.kt` | Category totals, percentage calculation, golden March grocery/dining/rent percentages. `@Singleton @Inject constructor()` — no deps |
| 2 | `domain/analytics/MerchantInsightEngineTest.kt` | `MerchantInsightEngine.kt` | Top merchants ranking, frequency calculation, "likely recurring" heuristic. `@Singleton @Inject constructor()` — no deps |
| 3 | `domain/analytics/DayOfWeekAnalyzerTest.kt` | `DayOfWeekAnalyzer.kt` | Day mapping correctness (Bug B-17), weekend vs weekday spending. `@Singleton @Inject constructor()` — no deps |
| 4 | `domain/analytics/MonthlyComparisonCalculatorTest.kt` | `MonthlyComparisonCalculator.kt` | Month-over-month percentage, zero previous month, count changes. `@Singleton @Inject constructor()` — no deps |
| 5 | `domain/analytics/TransferDirectionAnalyticsTest.kt` | `TransferDirectionAnalytics.kt` | Stateful tracker: `recordAutoDetection`, `recordUserCorrection`, `reset`, `getReport`, `StateFlow<TransferInsights>` emissions. `@Singleton @Inject constructor()` — no deps |
| 6 | `domain/receipt/OcrLanguageProcessorTest.kt` | `OcrLanguageProcessor.kt` | `detectLanguage`, `normalizeGreekText`, `normalizeLatinText`, `autoNormalize`, `extractAmount`. `@Singleton @Inject constructor()` — no deps, pure functions |
| 7 | `domain/receipt/WarrantyTextExtractorTest.kt` | `WarrantyTextExtractor.kt` | `extract(ocrText)` → `WarrantyExtractionData`. No DI, default constructor, pure function |

### Agent context

- Items 1-4 and 6-7 are **simple pure-function engines** with no constructor dependencies — do NOT extend base classes
- Item 5 (`TransferDirectionAnalytics`) is stateful — test `StateFlow` emissions with Turbine
- Read each production file to determine constructor params before writing tests
- Use golden March dataset for validation tests
- Verify Bug B-17 (day-of-week mapping) for `DayOfWeekAnalyzer`
- `OcrLanguageProcessor` and `WarrantyTextExtractor` are placed here despite being in `domain/receipt/` because they are self-contained pure-function engines with no Android dependencies

---

## Phase 2A — E2E Pipeline Tests

**Goal:** Replace deleted fake integration tests with real multi-layer pipelines.  
**Depends on:** Phase 1 (all)  
**Parallelizable with:** 2B, 2C, 2D  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 22, `CRASH-TEST-SCENARIOS.md` Sections 7, 10-11  
**Estimated tests:** 41 | **Estimated effort:** 4 sessions

### Files to Create

| # | File | Pipeline | Scenarios |
|---|------|----------|-----------|
| 1 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | Notification→Expense→Dashboard (E2E-1) | Raw notification → parsed expense → dashboard total includes it |
| 2 | `e2e/AnalyticsPipelineTest.kt` | Expense→Analytics→Insights (E2E-2) | Golden March → InsightsEngine → category breakdown, pace, anomalies |
| 3 | `e2e/BudgetAlertPipelineTest.kt` | Budget→Monitor→Alert (E2E-3) | Budget 500, spent 450 → WARNING → spent 500 → EXCEEDED |
| 4 | `e2e/BudgetHealthScorePipelineTest.kt` | Budget→HealthScore (E2E-4) | Same period range → both engines → consistent scores |
| 5 | `e2e/GroupSettlementPipelineTest.kt` | Split→Settlement→Balance (E2E-5) | 4-member group → splits → settlement → verify all balances zero |
| 6 | `e2e/CurrencyAnalyticsPipelineTest.kt` | Currency→Analytics (E2E-6) | Multi-currency expenses → convert → totals in home currency |
| 7 | `e2e/ForecastSynthesisPipelineTest.kt` | Recurring→Synthesis→Forecast (E2E-7) | 3 recurring patterns → synthesis committed totals → forecast |
| 8 | `e2e/ReceiptProcessingPipelineTest.kt` | Receipt→OCR→Parse→Categorize (E2E-8) | Mock ReceiptOcrService → real ReceiptParser → real MerchantNormalizer → real CategorizationEngine → verify extracted amount, merchant, category |
| 9 | `e2e/NotificationCategorizationPipelineTest.kt` | Notification→Parse→Classify→Categorize (E2E-9) | Raw notification text → AppParserRegistry → HybridExpenseClassifier → ConfidenceRouter → verify expense or pending review |
| 10 | `e2e/SavingsBudgetPipelineTest.kt` | Savings→MonteCarlo→Budget (E2E-10) | SmartSavingsEngine → MonteCarloSpendingSimulator → BudgetCalculator → verify safe savings amount |
| 11 | `e2e/DashboardCompilationPipelineTest.kt` | Dashboard full compilation (E2E-11) | ComputeDashboardWidgetsUseCase → InsightsEngine + SynthesisEngine + HealthScore + StressForecast → verify compiled widget data. **Most complex and critical E2E test** |

### Agent context

- Use `FlowPipelineTestHarness.kt` in `e2e/` as reference for wiring real engines
- Extend `AnalyticsEngineTestBase` or `ViewModelTestUtils` depending on endpoint
- Mock only data sources (DAO, Repository) — use **real** engine instances
- Use `TESTING-AGENT-PLAYBOOK.md` Section 6.5 (E2E template)
- Golden values from `CRASH-TEST-SCENARIOS.md` Sections 7, 10, 11

---

## Phase 2B — Cross-Component Validation Suites

**Goal:** Verify consistency, precision, and resilience across the entire calculation stack.  
**Depends on:** Phase 1 (all)  
**Parallelizable with:** 2A, 2C, 2D  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 23 (Suites N-1 through N-7)  
**Estimated tests:** 44 | **Estimated effort:** 3 sessions

### Files to Create

| # | File | Suite | Focus |
|---|------|-------|-------|
| 1 | `consistency/FinancialArithmeticPrecisionTest.kt` | N-1 | toCents/fromCents roundtrip, 500 conversions drift, overflow boundary |
| 2 | `consistency/TemporalConsistencyTest.kt` | N-2 | Same "March 2026" across engines, DST, leap year |
| 3 | `consistency/EmptyZeroNullResilienceTest.kt` | N-3 | Every engine with empty/zero/null inputs — no crash |
| 4 | `consistency/ConstantsConsistencyTest.kt` | N-4 | Duplicated constants match across engines |
| 5 | `consistency/ErrorPropagationTest.kt` | N-6 | Sub-engine throws → parent degrades gracefully |
| 6 | `consistency/SplitParityConsistencyTest.kt` | N-1c, 11.4 | SplitCalc vs SharedExpenseManager produce identical results |
| 7 | `consistency/HealthScoreConsistencyTest.kt` | NEW | FinancialHealthCalculator (V1) vs FinancialHealthScoreV2 — same input → compare outputs (if V1 is still active) |
| 8 | `consistency/BudgetPeriodAlignmentTest.kt` | NEW | BudgetCalculator, BudgetForecastingEngine, BudgetAutopilotEngine all calculate same period for same budget |
| 9 | `consistency/AmountEffectiveAmountConsistencyTest.kt` | NEW, B-07 | Verify InsightsEngine.getLegacyInsights uses amount while analytics uses effectiveAmount — document divergence |

### Agent context

- Place in existing `consistency/` package
- These tests instantiate multiple production classes — mock only DAOs
- `ConstantsConsistencyTest` can use reflection or direct comparison
- `ErrorPropagationTest` uses `coEvery { ... } throws RuntimeException(...)` on sub-engines
- Reference `CRASH-TEST-SCENARIOS.md` Sections 11-12 for exact scenarios

---

## Phase 2C — ViewModel Tests

**Goal:** Cover the most critical untested ViewModels.  
**Depends on:** Phase 1 (all)  
**Parallelizable with:** 2A, 2B, 2D  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 19  
**Estimated tests:** 37 | **Estimated effort:** 3 sessions

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | `SharedExpenseGroupsViewModel.kt` | Initial state, add expense (non-atomic bug B-12), delete group, state races. Deps: `GroupsRepository`, `AddGroupExpenseUseCase`, `DeleteGroupUseCase`, `ManualExpenseRepository`, `ExpenseRepository` |
| 2 | `ui/screens/currency/CurrencyManagementViewModelTest.kt` | `CurrencyManagementViewModel.kt` | Currency selection, rate refresh, conversion display. Deps: `CurrencyDataRepository`, `CurrencyConverter`, `CurrencyRatesRepository`, `CurrencySettingsRepository` |
| 3 | `ui/screens/savings/SavingsGoalsViewModelTest.kt` | `SavingsGoalsViewModel.kt` | Goal CRUD, progress update, empty state. Deps: `SavingsGoalRepository`, `SmartSavingsEngine`, `SavingsGamificationEngine`, `LifestyleSavingsPromptUseCase`, `MonthlySavingsSweepUseCase` |
| 4 | `ui/screens/subscription/SubscriptionManagementViewModelTest.kt` | `SubscriptionManagementViewModel.kt` | Subscription list, cancel, cost calculation. Deps: `SubscriptionManagementRepository`, `TimeProvider` |
| 5 | `ui/screens/budget/BudgetForecastingViewModelTest.kt` | `BudgetForecastingViewModel.kt` | Forecast display, risk level rendering. Deps: `BudgetForecastingEngine`, `BudgetRecommendationEngine` |
| 6 | `ui/screens/naturallanguage/NaturalLanguageSearchViewModelTest.kt` | `NaturalLanguageSearchViewModel.kt` | Query submission, result display, empty results. Deps: `NaturalLanguageSearchEngine` |
| 7 | `ui/screens/split/VisualSplitViewModelTest.kt` | `VisualSplitViewModel.kt` | Split type switching, custom split editing, validation. Deps: `EnhancedSplitManager`, `Gson` |
| 8 | `ui/screens/categories/CategoryViewModelTest.kt` | `CategoryViewModel.kt` | Category CRUD, default categories. Deps: `CategoryRepository` |
| 9 | `ui/screens/analytics/AdvancedAnalyticsViewModelTest.kt` | `AdvancedAnalyticsViewModel.kt` | Period selection, data loading. Deps: `AdvancedAnalyticsDashboard`, `TimeProvider` |

### Agent context

- ALL extend `ViewModelTestUtils`
- Mock ALL constructor dependencies with `mockk(relaxed = true)`
- Use `runTest(testDispatcher)` + `advanceUntilIdle()`
- Use Turbine for StateFlow assertions
- Read each ViewModel's constructor to identify dependencies (see `TESTING-AGENT-PLAYBOOK.md` Section 8)
- Verify Bug B-12 (non-atomic creation in SharedExpenseGroupsViewModel)

---

## Phase 2D — Repository Tests

**Goal:** Cover untested repositories with known bugs.  
**Depends on:** Phase 1 (all)  
**Parallelizable with:** 2A, 2B, 2C  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 3, `REVIEW-database-repository-subsystem.md`  
**Estimated tests:** 29 | **Estimated effort:** 2 sessions

### Files to Create

| # | File | Production Class | What to Test |
|---|------|-----------------|-------------|
| 1 | `data/repository/DatabaseBackupRepositoryImplTest.kt` | `DatabaseBackupRepositoryImpl.kt` | Backup, restore, rollback safety (REVIEW ISSUE-2), WAL checkpoint. Deps: `Context`, `AppDatabase`, `@IoDispatcher` |
| 2 | `data/repository/GroupsRepositoryImplTest.kt` | `GroupsRepositoryImpl.kt` | Member delete with split references (REVIEW ISSUE-3), JSON parsing. Deps: `AppDatabase`, `ExpenseGroupDao`, `GroupMemberDao`, `GroupExpenseDao`, `GroupTransactionCoordinator`, `@IoDispatcher` |
| 3 | `data/repository/NaturalLanguageExpenseQueryRepositoryImplTest.kt` | `NaturalLanguageExpenseQueryRepositoryImpl.kt` | Pagination determinism (REVIEW ISSUE-4). Deps: `ExpenseDao` |
| 4 | `data/repository/MultiCurrencyRepositoryTest.kt` | `MultiCurrencyRepository.kt` | Missing rate handling, home currency conversion. Deps: `ExpenseDao`, `CurrencyConverter`, `TimeProvider` |
| 5 | `data/repository/AccountingExportRepositoryTest.kt` | `AccountingExportRepository.kt` | Export format correctness, special characters. Deps: `ExpenseRepository`, `CategoryRepository`, `QuickBooksIIFExporter`, `XeroCSVExporter`, `FreshBooksExporter` |
| 6 | `data/repository/SharedExpenseDataPortAdapterTest.kt` | `SharedExpenseDataPortAdapter.kt` | Bridge between domain groups and data layer — delegation correctness. Deps: `ExpenseGroupDao`, `GroupMemberDao`, `GroupExpenseDao`, `GroupTransactionCoordinator` |
| 7 | `data/repository/ExportDataRepositoryTest.kt` | `ExportDataRepository.kt` | User data export correctness. Deps: `Context`, `ExpenseRepository`, `CategoryRepository` |
| 8 | `data/repository/ManualExpenseRepositoryTest.kt` | `ManualExpenseRepository.kt` | Most complex repository (15 deps) — expense creation pipeline through categorization, normalization, budget monitoring. Mock ALL deps |

### Agent context

- Repositories inject DAOs — mock all DAOs with `mockk(relaxed = true)`
- Repositories inject `@IoDispatcher` — pass `testDispatcher`
- Some repositories inject `AppDatabase` for `withTransaction` — mock with `coEvery { database.withTransaction(any()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }`
- Reference `REVIEW-database-repository-subsystem.md` for known issues

---

## Phase 3A — DAO Instrumented Tests

**Goal:** Cover the most critical untested DAOs with real Room database tests.  
**Depends on:** Phase 2 (all)  
**Parallelizable with:** 3B  
**Reference:** `COMPONENT-TEST-MATRIX.md` Section 2b  
**Estimated tests:** 42 | **Estimated effort:** 3 sessions

### Files to Create (in `androidTest/`)

| # | File | DAO | What to Test |
|---|------|-----|-------------|
| 1 | `data/database/dao/BudgetDaoTest.kt` | `BudgetDao.kt` | Budget CRUD, period queries, warning thresholds |
| 2 | `data/database/dao/CategoryDaoTest.kt` | `CategoryDao.kt` | Category CRUD, defaults, uniqueness constraints |
| 3 | `data/database/dao/RecurringExpenseDaoTest.kt` | `RecurringExpenseDao.kt` | Recurring insert/query, active filter |
| 4 | `data/database/dao/SavingsGoalDaoTest.kt` | `SavingsGoalDao.kt` | Progress update, goal queries |
| 5 | `data/database/dao/GroupExpenseDaoTest.kt` | `GroupExpenseDao.kt` | Group expense queries, FK constraints |
| 6 | `data/database/dao/ExchangeRateDaoTest.kt` | `ExchangeRateDao.kt` | Rate upsert, staleness queries |
| 7 | `data/database/dao/ExpenseGroupDaoTest.kt` | `ExpenseGroupDao.kt` | Group CRUD, archive/restore, FK constraints with members |
| 8 | `data/database/dao/GroupMemberDaoTest.kt` | `GroupMemberDao.kt` | Member CRUD, FK constraints with group |
| 9 | `data/database/dao/WarrantyDaoTest.kt` | `WarrantyDao.kt` | Warranty lifecycle, expiry queries |
| 10 | `data/database/dao/RecommendationDaoTest.kt` | `RecommendationDao.kt` | Recommendation storage, read/unread state |
| 11 | `data/database/dao/MerchantNormalizationDaoTest.kt` | `MerchantNormalizationDao.kt` | Merchant name upsert, lookup |
| 12 | `data/database/dao/ScannedReceiptDaoTest.kt` | `ScannedReceiptDao.kt` | Receipt storage, OCR text persistence |

### Agent context

- These go in `app/src/androidTest/` (NOT `app/src/test/`)
- Use `Room.inMemoryDatabaseBuilder` — see `TESTING-AGENT-PLAYBOOK.md` Section 6.6
- Use `@RunWith(AndroidJUnit4::class)`
- Use `runBlocking` (not `runTest`) — Room in-memory DB requires real coroutine context
- Reference existing `ExpenseDaoTest.kt` in `androidTest/` as a pattern

---

## Phase 3B — Remaining Coverage

**Goal:** Fill remaining gaps across domain engines, services, and low-priority ViewModels.  
**Depends on:** Phase 2 (all)  
**Parallelizable with:** 3A  
**Reference:** `COMPONENT-TEST-MATRIX.md` Sections 10-18  
**Estimated tests:** 60 | **Estimated effort:** 4 sessions

### Files to Create

| # | File | Production Class | Priority |
|---|------|-----------------|----------|
| 1 | `domain/savings/SmartSavingsEngineGoldenTest.kt` | `SmartSavingsEngine.kt` | CRASH-TEST 9.4-9.6, Bug B-09 (hardcoded EUR caps) |
| 2 | `domain/forecasting/FinancialStressForecastEngineGoldenTest.kt` | `FinancialStressForecastEngine.kt` | CRASH-TEST 8.1-8.5 |
| 3 | `domain/receipt/BankStatementParserGoldenTest.kt` | `BankStatementParser.kt` | Statement parsing accuracy |
| 4 | `data/location/LogSanitizerTest.kt` | `LogSanitizer.kt` | PII redaction verification |
| 5 | `ui/screens/warranty/WarrantyTrackerViewModelTest.kt` | `WarrantyTrackerViewModel.kt` | Warranty list, expiry alerts. Deps: `WarrantyTrackerRepository` |
| 6 | `ui/screens/investment/InvestmentViewModelTest.kt` | `InvestmentViewModel.kt` | Portfolio display, P&L calc. Deps: `InvestmentTracker` |
| 7 | `consistency/ConcurrencyStateRaceTest.kt` | Multiple ViewModels | N-5 suite: rapid re-scan, concurrent operations |
| 8 | `domain/savings/SavingsGamificationEngineTest.kt` | `SavingsGamificationEngine.kt` | `calculateStreak`, `getAchievements`, `calculateLevel`, `getLevelTitle`. Deps: `SavingsGoalRepository`, `TimeProvider` |
| 9 | `domain/receipt/EnhancedMerchantExtractorTest.kt` | `EnhancedMerchantExtractor.kt` | `extractMerchant(ocrText, existingMerchant)`. Deps: `MerchantNormalizationDao` |
| 10 | `data/ai/provider/HybridServiceDelegationTest.kt` | 6 Hybrid*Service classes | Verify all 6 hybrid services delegate to cloud/on-device correctly |
| 11 | `domain/model/dashboard/DashboardExpenseMapperTest.kt` | `DashboardExpenseMapper.kt` | Mapping correctness |
| 12 | `data/currency/ExchangeRateStoreAdapterTest.kt` | `ExchangeRateStoreAdapter.kt` | Adapter delegation to DAO |

### Workers batch (in `androidTest/` or with Robolectric)

| # | File | Worker | What to Test |
|---|------|--------|-------------|
| 13 | `service/warranty/WarrantyExpirationWorkerTest.kt` | `WarrantyExpirationWorker.kt` | Expiry detection, notification trigger |
| 14 | `service/receiptmatching/ReceiptMatchingWorkerTest.kt` | `ReceiptMatchingWorker.kt` | Receipt-to-expense matching |
| 15 | `data/ai/worker/DailyBriefingWorkerTest.kt` | `DailyBriefingWorker.kt` | Briefing generation scheduling |
| 16 | `data/location/LocationBackfillWorkerTest.kt` | `LocationBackfillWorker.kt` | Location backfill execution |

### Untested use cases batch

| # | File | Use Case | What to Test |
|---|------|----------|-------------|
| 17 | `domain/usecase/expense/CategorizeExpenseUseCaseTest.kt` | `CategorizeExpenseUseCase.kt` | Delegation to CategorizationEngine + MerchantNormalizer |
| 18 | `domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt` | `DetectDuplicateExpenseUseCase.kt` | Duplicate detection logic |
| 19 | `domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt` | `CalculateBudgetStatusUseCase.kt` | Budget status computation |
| 20 | `domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt` | `PrioritizeReviewItemsUseCase.kt` | Priority scoring |

### Remaining low-priority ViewModels

| # | File | ViewModel |
|---|------|-----------|
| 21 | `ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt` | `ManualRecurringExpenseViewModel.kt` |
| 22 | `ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | `ReceiptMatchingViewModel.kt` |
| 23 | `ui/screens/lifestyle/LifestyleInflationViewModelTest.kt` | `LifestyleInflationViewModel.kt` |
| 24 | `ui/screens/carbon/CarbonFootprintViewModelTest.kt` | `CarbonFootprintViewModel.kt` |

### Agent context

- Mix of engine tests (extend `AnalyticsEngineTestBase`) and ViewModel tests (extend `ViewModelTestUtils`)
- Savings/forecast engines have hardcoded EUR constants (Bug B-09) — document in tests
- `ConcurrencyStateRaceTest` verifies state races — use `launch { }` + `advanceUntilIdle()`
- Reference `CRASH-TEST-SCENARIOS.md` Sections 8-10, 14

---

## Parallel Execution Matrix

This table shows which phases can run simultaneously:

| Time Slot | Agent 1 | Agent 2 | Agent 3 | Agent 4 | Agent 5 |
|-----------|---------|---------|---------|---------|---------|
| **Slot 1** | Phase 0 (Cleanup) | — | — | — | — |
| **Slot 2** | Phase 1A (Guards) | Phase 1B (Splits) | Phase 1C (Budget) | Phase 1D (Health) | Phase 1E (Analytics) |
| **Slot 3** | Phase 2A (E2E) | Phase 2B (Suites) | Phase 2C (VMs) | Phase 2D (Repos) | — |
| **Slot 4** | Phase 3A (DAOs) | Phase 3B (Remaining) | — | — | — |

**Minimum wall-clock time with 5 agents: 4 sessions**  
**Serial execution: ~18 sessions**

---

## Conflict Avoidance Rules

To ensure parallel agents don't step on each other:

1. **No shared file modifications** — each phase creates files in different packages. No phase modifies the same file as another.
2. **Base classes are READ-ONLY** — `AnalyticsEngineTestBase.kt`, `ViewModelTestUtils.kt`, `TestUtils.kt` are extended but never modified.
3. **Golden values are READ-ONLY** — `CRASH-TEST-SCENARIOS.md` is referenced but never modified.
4. **Package ownership** — each phase "owns" specific packages and naming patterns:
   - 1A: `*GoldenTest.kt` files across `domain/logic/`, `domain/analytics/`, `domain/budget/`, `domain/health/`, `domain/currency/`, `domain/forecasting/`, `domain/savings/`
   - 1B: `domain/logic/` (`SplitCalculator*`, `CustomSplitParser*`), `domain/groups/` (`Settlement*`, `SharedExpense*`, `usecase/GroupUseCases*`)
   - 1C: `domain/budget/` (`*BoundaryTest`, `*MonitorTest`, `SharedBudgetManager*`, `BudgetRecommendation*`), `domain/analytics/` (`SpendingPace*`)
   - 1D: `domain/health/` (`*EdgeCaseTest`, `FinancialHealthCalculator*`), `domain/currency/` (`*EdgeCaseTest`, `*StressTest`)
   - 1E: `domain/analytics/` (`CategoryInsight*`, `MerchantInsight*`, `DayOfWeek*`, `MonthlyComparison*`, `TransferDirection*`), `domain/receipt/` (`OcrLanguageProcessor*`, `WarrantyTextExtractor*`)
   - 2A: `e2e/`
   - 2B: `consistency/`
   - 2C: `ui/screens/`
   - 2D: `data/repository/`
   - 3A: `androidTest/data/database/dao/`
   - 3B: mixed — uses unique file names listed in phase (workers, mappers, use cases, hybrid services, remaining VMs)

5. **Name collisions** — Phase 1A creates `*GoldenTest.kt`, Phase 1B creates `*Test.kt` and `*StressTest.kt` for split/groups, Phase 1C creates `*BoundaryTest.kt` and `*MonitorTest.kt` for budget, Phase 1D creates `*EdgeCaseTest.kt` for health/currency. No two phases create the same filename.

---

## Progress Tracking

After each phase completes, update this table:

| Phase | Status | Tests Created | Tests Deleted | Agent | Date |
|-------|--------|---------------|---------------|-------|------|
| 0 | ⬜ Pending | — | ~142 | — | — |
| 1A | ⬜ Pending | 14 | — | — | — |
| 1B | ⬜ Pending | 30 | — | — | — |
| 1C | ⬜ Pending | 27 | — | — | — |
| 1D | ⬜ Pending | 20 | — | — | — |
| 1E | ⬜ Pending | 21 | — | — | — |
| 2A | ⬜ Pending | 41 | — | — | — |
| 2B | ⬜ Pending | 44 | — | — | — |
| 2C | ⬜ Pending | 37 | — | — | — |
| 2D | ⬜ Pending | 29 | — | — | — |
| 3A | ⬜ Pending | 42 | — | — | — |
| 3B | ⬜ Pending | 60 | — | — | — |

---

## Agent Prompt Template

When launching an agent to execute a phase, use this prompt structure:

```
Execute Phase [X] of the testing overhaul.

Read these docs FIRST (in order):
1. docs/quality/TESTING-AGENT-PLAYBOOK.md — conventions, templates, base classes
2. docs/quality/TESTING-WORKFLOW.md — find Phase [X] section for your specific instructions
3. docs/quality/TESTING-GAP-ANALYSIS.md — gap analysis context for why these tests were added
4. docs/quality/CRASH-TEST-SCENARIOS.md — golden values (if phase references it)
5. docs/quality/COMPONENT-TEST-MATRIX.md — file inventory (if phase references it)

Then:
1. Read each production file listed in the phase
2. Create the test files listed in the phase table
3. Follow all conventions from the playbook
4. Run ./gradlew test to verify compilation
5. Update the Progress Tracking table in TESTING-WORKFLOW.md
```
