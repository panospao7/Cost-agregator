# Testing Workflow — Deep Gap Analysis

> **Date:** April 5, 2026  
> **Scope:** Phase-by-phase audit of `TESTING-WORKFLOW.md` against actual production code  
> **Method:** Full glob + read of production files, cross-referenced against existing tests and planned coverage  
> **Result:** 47 gaps found across all phases, plus 3 entirely missing component categories  
> **Status:** All findings have been applied to `TESTING-WORKFLOW.md` — that document is now the authoritative source

---

## Summary of Findings

| Category | Gaps Found | Impact |
|----------|-----------|--------|
| Phase 0 missed cleanup targets | 3 | 3 integration files not audited |
| Phase 1A-1E missing production methods/files | 12 | Key methods and engines not in plan |
| Phase 2A missing E2E pipelines | 5 | Critical real-world flows uncovered |
| Phase 2B missing cross-component interactions | 3 | Important consistency paths skipped |
| Phase 2C missing ViewModels | 13 | 20 untested VMs, only 7 planned |
| Phase 2D missing Repositories | 6 | 33 untested repos, only 5 planned |
| Phase 3A missing DAOs | 4 | 36 untested DAOs, only 6 planned |
| Phase 3B missing component categories | 6 | Workers, mappers, use cases, adapters absent |
| Name mismatches in workflow | 3 | Will confuse agents |

---

## Phase 0 — Cleanup Gaps

### GAP-0.1: Three unaudited integration files

The `integration/` package has **9** files, but Phase 0 only targets **6** for deletion. The remaining 3 were never audited for quality:

| File | Status |
|------|--------|
| `integration/MultiCurrencyAnalyticsTest.kt` | Survived — references real types but needs quality audit |
| `integration/CategorizationPipelineIntegrationTest.kt` | Survived — not mentioned in any plan |
| `integration/ExpenseCreationPipelineIntegrationTest.kt` | Survived — not mentioned in any plan |

**Action:** Add a step 0.12 to Phase 0: audit these 3 files for the same "inline arithmetic vs real production code" pattern. If fake, delete. If real, keep but verify they follow project conventions (MockK, `runTest`).

### GAP-0.2: `CategorizationEngineDebugTest.kt` not audited

This file exists in `domain/categorization/` alongside the `CategorizationEngineTest.kt` that's already flagged for the assertion-less test deletion and `runBlocking` → `runTest` refactor. The "debug" variant was never checked.

**Action:** Include in the Phase 0 audit sweep.

---

## Phase 1A — Regression Guards Gaps

### GAP-1A.1: Missing `SynthesisEngine` golden test

`SynthesisEngine` is a critical forecasting component that feeds into dashboard widgets. It has two public methods (`synthesize`, `calculateBlockPartyData`) and specific constants (confidence thresholds: `0.70`, `0.90`, `0.85`; biweekly tolerance `±2 days`). No golden test is planned in any phase for it.

**Action:** Add `domain/logic/SynthesisEngineGoldenTest.kt` to Phase 1A.

### GAP-1A.2: Missing `MonteCarloSpendingSimulator` deterministic golden test

Monte Carlo uses seed `42L` with `1000` iterations. The CRASH-TEST doc specifies "Exact P50 value (snapshot)" as a golden assertion (N-7e). No test file is planned in Phase 1A for this.

**Action:** Add `domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt` to Phase 1A.

### GAP-1A.3: Missing `AutomatedSavingsRuleEngine` roundup golden test

CRASH-TEST specifies N-7g: `€17.30, roundUp=5 → savingsAmount=2.70`. No golden test planned in Phase 1A.

**Action:** Add `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt` to Phase 1A (just the single golden assertion — the full test suite is in Phase 3B).

---

## Phase 1B — Split/Settlement/Groups Gaps

### GAP-1B.1: `CustomSplitParser` is untested

`SplitCalculator` and `SharedExpenseManager` both delegate custom split parsing to `CustomSplitParser` (in `domain/logic/CustomSplitParser.kt`). This class defines `AMOUNT_TOLERANCE = 0.01`, `PERCENT_TOLERANCE = 0.1`, and `parseAndValidate` + `referencesMember`. No test exists and none is planned.

**Action:** Add `domain/logic/CustomSplitParserTest.kt` to Phase 1B. It's a pure function class — no mocks needed.

### GAP-1B.2: Missing `GroupTransactionCoordinator` data-layer test

The data-layer `GroupTransactionCoordinator` has additional public methods not on the domain interface (`addExpenseToGroupAtomic`, `deleteGroupAtomic`). A test file exists (`data/database/GroupTransactionCoordinatorTest.kt`) but was not audited for completeness.

**Action:** Phase 1B agent should read the existing test and add coverage for the additional atomic methods if missing.

### GAP-1B.3: Missing group use case tests

Three group-related use cases have **zero tests**:
- `AddGroupExpenseUseCase`
- `DeleteGroupUseCase`
- `DeleteGroupMemberUseCase`

**Action:** Add these to Phase 1B scope — they are simple wrappers around `GroupsRepository` but the delegation and error handling need verification.

### GAP-1B.4: `SharedExpenseBudgetOffsetEngine` not mentioned

This engine in `domain/groups/` uses `SharedExpenseManager` for budget offset logic. No test exists or is planned.

**Action:** Add `domain/groups/SharedExpenseBudgetOffsetEngineTest.kt` to Phase 1B.

---

## Phase 1C — Budget & Pace Gaps

### GAP-1C.1: Missing `BudgetForecastingEngine` stub method

`updateForecastAccuracy` is a **stub** (comments say "this is a Flow so we'd need to collect it" — assigns `null`, no actual logic). This should be documented in a test as explicitly unimplemented.

**Action:** Add a test in `BudgetForecastingEngineTest` (which already exists) that documents this stub: `@Test fun 'updateForecastAccuracy is a no-op stub'()`.

### GAP-1C.2: Missing `BudgetAutopilotEngine` dead dependency detection

`InsightsEngine`, `SpendingPaceCalculator`, and `MonteCarloSpendingSimulator` are injected but **never used** in `BudgetAutopilotEngine`. Tests should verify this doesn't silently break when these dependencies throw.

**Action:** Add to Phase 1C scope: `BudgetAutopilotEngineDeadDepsTest.kt` — verify engine works when unused deps throw exceptions (proves they're truly unused).

### GAP-1C.3: Missing `BudgetMonitor` test

`BudgetMonitor` is a service with `checkBudgets()` and `cleanup()`. It injects `NotificationService` and `BudgetRepository`. Zero tests exist or are planned.

**Action:** Add `domain/budget/BudgetMonitorTest.kt` to Phase 1C.

### GAP-1C.4: Missing `SharedBudgetManager` test

This manager has `getSharedBudgetProgress` and `getMemberContributions`. Injects `BudgetRepository`, `ExpenseDao`, `TimeProvider`. Zero tests.

**Action:** Add `domain/budget/SharedBudgetManagerTest.kt` to Phase 1C.

### GAP-1C.5: Missing `BudgetRecommendationEngine` boundary test

Phase 3B plans a `BudgetRecommendationEngineTest.kt` but it's low priority. Given this engine generates user-facing recommendations and has multiple risk tier thresholds, it should be elevated to Phase 1C.

**Action:** Move `BudgetRecommendationEngineTest.kt` from Phase 3B to Phase 1C.

---

## Phase 1D — Health & Currency Gaps

### GAP-1D.1: `FinancialHealthCalculator` (V1) not covered

`domain/health/FinancialHealthCalculator.kt` is a separate class from `FinancialHealthScoreV2`. It has `calculateHealthScores(expenses, budgetStatuses, pendingReviews, todayStreak, ...)`. If V1 is still in use (e.g., by `ComputeDashboardWidgetsUseCase`), it needs tests.

**Action:** Verify if `FinancialHealthCalculator` is still referenced. If yes, add `domain/health/FinancialHealthCalculatorTest.kt` to Phase 1D. If deprecated, document and skip.

### GAP-1D.2: Unreachable code in `FinancialHealthScoreV2.calculateRunwayScore`

Line analysis shows the `else { RUNWAY_TARGET_MONTHS }` branch in `calculateRunwayScore` is unreachable (preceded by `if (monthlyExpenses <= 0) return 50`). A test should document this dead branch.

**Action:** Add a test in `HealthScoreEdgeCaseTest` that explicitly covers the `monthlyExpenses <= 0` early return and documents the unreachable else.

### GAP-1D.3: Missing `CurrencyConverter.convertMultiple` test

Phase 1D plans `CurrencyConverterEdgeCaseTest` and `CurrencyConverterStressTest` but the `convertMultiple` method (which aggregates conversions and tracks `failures`) is not explicitly called out.

**Action:** Add `convertMultiple` scenarios to `CurrencyConverterEdgeCaseTest`: partial failures, all failures, empty list.

---

## Phase 1E — Analytics Sub-Engines Gaps

### GAP-1E.1: `TransferDirectionAnalytics` not included

This is a **stateful** analytics tracker in `domain/analytics/` with `recordAutoDetection`, `recordUserCorrection`, `reset`, `getReport`. It manages a `StateFlow<TransferInsights>`. Zero tests exist. It's not in Phase 1E.

**Action:** Add `domain/analytics/TransferDirectionAnalyticsTest.kt` to Phase 1E.

### GAP-1E.2: Analytics model files not validated

`AdvancedAnalyticsModels.kt` and `AnalyticsModels.kt` define enums, data classes, and helper methods. While model classes rarely need dedicated tests, any computed properties or validation logic in them should be tested.

**Action:** Read both model files. If they contain computed properties (e.g., `val percentage: Double get() = ...`), add spot checks to the Phase 1E engine tests that use those models.

---

## Phase 2A — E2E Pipelines Gaps

### GAP-2A.1: Missing Receipt OCR Pipeline E2E

The receipt processing pipeline (`ProcessReceiptUseCase` → `ReceiptOcrService` → `ReceiptParser` → `MerchantNormalizer` → `CategorizationEngine`) is a critical user-facing flow with **zero E2E coverage**. Individual components have unit tests but the pipeline integration is untested.

**Action:** Add `e2e/ReceiptProcessingPipelineTest.kt` to Phase 2A. Mock only `ReceiptOcrService` (returns known OCR text), use real `ReceiptParser`, `MerchantNormalizer`, `CategorizationEngine`.

### GAP-2A.2: Missing Notification→Categorization→Review Pipeline E2E

The notification pipeline (`NotificationProcessingPipeline` → `AppParserRegistry` → `HybridExpenseClassifier` → `ConfidenceRouter` → expense or pending review) is only tested at the reliability/stress level. No E2E test follows a notification through to a correctly categorized expense.

**Action:** Add `e2e/NotificationCategorizationPipelineTest.kt` to Phase 2A.

### GAP-2A.3: Missing Savings→Budget Pipeline E2E

`SmartSavingsEngine` → `MonteCarloSpendingSimulator` → `BudgetCalculator` is a pipeline that calculates safe savings amounts based on spending forecasts. No E2E test exists.

**Action:** Add `e2e/SavingsBudgetPipelineTest.kt` to Phase 2A.

### GAP-2A.4: Missing Dashboard Compilation Pipeline E2E

`ComputeDashboardWidgetsUseCase` is the most complex use case in the codebase — it orchestrates `InsightsEngine`, `SynthesisEngine`, `MonteCarloSpendingSimulator`, `FinancialHealthCalculator`, `FinancialHealthScoreV2`, `FinancialStressForecastEngine`, and more. Zero tests exist for this use case.

**Action:** Add `e2e/DashboardCompilationPipelineTest.kt` to Phase 2A. This is the single most impactful missing E2E test.

### GAP-2A.5: Missing Warranty auto-creation Pipeline E2E

`AutoCreateWarrantyFromReceiptUseCase` → `WarrantyTextExtractor` → `WarrantyTrackerRepository` is a pipeline from receipt scan to warranty creation. The use case has a test but `WarrantyTextExtractor` is untested standalone.

**Action:** Add `domain/receipt/WarrantyTextExtractorTest.kt` to Phase 1E (standalone) and reference it in the E2E warranty pipeline.

---

## Phase 2B — Cross-Component Gaps

### GAP-2B.1: Missing `FinancialHealthCalculator` vs `FinancialHealthScoreV2` consistency test

Both classes calculate health scores with different algorithms. If both are active, their outputs for the same input should be compared.

**Action:** Add to `consistency/HealthScoreConsistencyTest.kt` in Phase 2B.

### GAP-2B.2: Missing budget period alignment across engines

`BudgetCalculator`, `BudgetForecastingEngine`, and `BudgetAutopilotEngine` all independently calculate budget periods. A consistency test should verify they produce the same period range for the same budget.

**Action:** Add `consistency/BudgetPeriodAlignmentTest.kt` to Phase 2B.

### GAP-2B.3: Missing `amount` vs `effectiveAmount` consistency test

Bug B-07 documents that `InsightsEngine.getLegacyInsights` uses `amount` while analytics uses `effectiveAmount`. The consistency suite should have a dedicated test for this.

**Action:** Add to `consistency/AmountEffectiveAmountConsistencyTest.kt` in Phase 2B.

---

## Phase 2C — ViewModel Gaps

### GAP-2C.1: Only 7 of 20 untested ViewModels are planned

Phase 2C (5) + Phase 3B (2) = **7** ViewModels planned. **13 untested ViewModels** are not in any phase:

| ViewModel | Dependencies | Priority |
|-----------|-------------|----------|
| `NaturalLanguageSearchViewModel` | 1 | HIGH — user-facing search |
| `ManualRecurringExpenseViewModel` | 2 | MEDIUM |
| `ReceiptMatchingViewModel` | 2 | MEDIUM |
| `TaxConfigurationViewModel` | 2 | LOW |
| `VisualSplitViewModel` | 2 | MEDIUM — financial calculations |
| `BillRemindersViewModel` | 1 | LOW |
| `BillNegotiationViewModel` | 1 | LOW |
| `LifestyleInflationViewModel` | 1 | LOW |
| `CategorizationDebugViewModel` | 1 | LOW |
| `SpendingChallengesViewModel` | 1 | LOW |
| `CategoryViewModel` | 1 | MEDIUM — core feature |
| `CarbonFootprintViewModel` | 2 | LOW |
| `BankConnectionsViewModel` | 0 | LOW |
| `AdvancedAnalyticsViewModel` | 2 | MEDIUM |

**Action:** Add at minimum `NaturalLanguageSearchViewModel`, `VisualSplitViewModel`, `CategoryViewModel`, and `AdvancedAnalyticsViewModel` to Phase 2C. Move the remaining LOW-priority VMs to Phase 3B.

### GAP-2C.2: Name mismatches in workflow

The workflow uses incorrect names that will confuse agents:
- `SubscriptionViewModel` → actual name is `SubscriptionManagementViewModel`
- `BudgetForecastViewModel` → actual name is `BudgetForecastingViewModel`
- `WarrantyViewModel` → actual name is `WarrantyTrackerViewModel`

**Action:** Fix names in `TESTING-WORKFLOW.md`.

---

## Phase 2D — Repository Gaps

### GAP-2D.1: Only 5 of 33 untested repositories planned

Phase 2D covers `DatabaseBackupRepositoryImpl`, `GroupsRepositoryImpl`, `NaturalLanguageExpenseQueryRepositoryImpl`, `MultiCurrencyRepository`, `AccountingExportRepository`. **28 repositories** are missing from all phases.

Most critical missing repositories:

| Repository | Deps | Why Important |
|------------|------|---------------|
| `SharedExpenseDataPortAdapter` | 4 | Bridge between domain groups and data layer |
| `ExportDataRepository` | 3 | User data export — correctness critical |
| `SubscriptionManagementRepository` | 4 | Financial subscription tracking |
| `ManualExpenseRepository` | 15 | Most complex repository in the codebase |
| `CurrencyRatesRepositoryImpl` | 3 | Currency rate management |
| `SavingsGoalRepository` | 1 | Savings feature data layer |

**Action:** Add at minimum `SharedExpenseDataPortAdapter`, `ExportDataRepository`, and `ManualExpenseRepository` to Phase 2D. Move the remaining to Phase 3B.

---

## Phase 3A — DAO Gaps

### GAP-3A.1: Only 6 of 36 untested DAOs planned

Phase 3A covers `BudgetDao`, `CategoryDao`, `RecurringExpenseDao`, `SavingsGoalDao`, `GroupExpenseDao`, `ExchangeRateDao`. **30 DAOs** remain uncovered.

Most critical missing DAOs (have complex queries or multi-table joins):

| DAO | Why Important |
|-----|---------------|
| `ExpenseGroupDao` | Group management — FK constraints |
| `GroupMemberDao` | Member management — FK constraints |
| `WarrantyDao` | Warranty lifecycle |
| `RecommendationDao` | AI recommendations storage |
| `MerchantNormalizationDao` | Merchant name resolution |
| `ScannedReceiptDao` | Receipt data persistence |

**Action:** Add these 6 to Phase 3A (total 12 DAO tests). Remaining 24 are simple CRUD and can stay in a future Phase 4.

---

## Phase 3B — Remaining Coverage Gaps

### GAP-3B.1: Workers completely absent from all phases

5 `*Worker.kt` files exist. Only 1 has a test (`MerchantKeyBackfillWorker` in androidTest). The other 4 are not in any phase:
- `DailyBriefingWorker`
- `WarrantyExpirationWorker`
- `ReceiptMatchingWorker`
- `LocationBackfillWorker`

**Action:** Add a "Workers" batch to Phase 3B.

### GAP-3B.2: Mappers and adapters absent from all phases

6 mapper/adapter files exist with zero tests:
- `DashboardExpenseMapper`
- `TransactionFilterUiMapper`
- `DashboardWidgetUiMapper`
- `DashboardContractsAdapter`
- `SharedExpenseDataPortAdapter`
- `ExchangeRateStoreAdapter`

**Action:** Add `DashboardExpenseMapper` and `ExchangeRateStoreAdapter` to Phase 3B (the others are tested through their consumers).

### GAP-3B.3: 13 untested use cases absent from all phases

Critical untested use cases not in any phase:

| Use Case | Dependencies | Priority |
|----------|-------------|----------|
| `ComputeDashboardWidgetsUseCase` | 9 | CRITICAL — main dashboard |
| `ProcessReceiptUseCase` | 4 | HIGH — receipt scanning |
| `DetectDuplicateExpenseUseCase` | 3 | HIGH — data quality |
| `CategorizeExpenseUseCase` | 2 | HIGH — categorization |
| `CalculateBudgetStatusUseCase` | 1 | MEDIUM |
| `CategorizeReceiptItemsUseCase` | 8 | MEDIUM |
| `PrioritizeReviewItemsUseCase` | 1 | MEDIUM |
| `GenerateTransactionInsightUseCase` | 5 | LOW |
| `DetectSemanticDuplicateUseCase` | 1 | LOW |

**Action:** Add `ComputeDashboardWidgetsUseCase`, `ProcessReceiptUseCase`, `DetectDuplicateExpenseUseCase`, and `CategorizeExpenseUseCase` to Phase 2A (they are pipeline entry points). Add the rest to Phase 3B.

### GAP-3B.4: Receipt OCR sub-components untested

These receipt-processing files have zero tests and are not in any phase:
- `OcrPreprocessingPipeline` (Bitmap processing — needs Robolectric)
- `ReceiptOcrService` (ML Kit integration — needs instrumented or heavy mocking)
- `OcrLanguageProcessor` (pure function — easy to test)
- `EnhancedMerchantExtractor` (depends on DAO)
- `WarrantyTextExtractor` (pure function — easy to test)

**Action:** Add `OcrLanguageProcessor` and `WarrantyTextExtractor` to Phase 1E (pure function engines). Add `EnhancedMerchantExtractor` to Phase 3B. `OcrPreprocessingPipeline` and `ReceiptOcrService` need Robolectric/instrumented tests — add to Phase 3A.

### GAP-3B.5: `SavingsGamificationEngine` not in any phase

Has `calculateStreak`, `getAchievements`, `calculateLevel`, `getLevelTitle`. Depends on `SavingsGoalRepository` and `TimeProvider`.

**Action:** Add `domain/savings/SavingsGamificationEngineTest.kt` to Phase 3B.

### GAP-3B.6: Hybrid/NoOp AI services absent

12 Hybrid and NoOp AI service implementations have no tests:
- 6 `Hybrid*Service` (non-receipt-item variants)
- 6 `NoOp*Service`

These are simple delegation/fallback classes. Low priority but should be documented.

**Action:** Add a single `data/ai/provider/HybridServiceDelegationTest.kt` to Phase 3B that tests all 6 hybrid services delegate correctly. NoOp services are trivial — skip unless time allows.

---

## Cross-Cutting Issues

### XCUT-1: No migration tests for recent schema changes

The git status shows `AppDatabase.kt` has modifications. `DatabaseMigrationTest.kt` and `MigrationContractTest.kt` exist in androidTest but may not cover the latest migration. No phase addresses migration test updates.

**Action:** Add a step to Phase 0 or Phase 3A: verify existing migration tests cover the current schema version.

### XCUT-2: No Compose UI tests planned

`COMPONENT-TEST-MATRIX.md` Section 23 mentions P3 Compose UI tests for top 4 screens, but no phase in the workflow addresses them.

**Action:** If Compose UI tests are desired, create a Phase 4 for them. Otherwise, document this as intentionally deferred.

### XCUT-3: `BudgetForecastingEngine.MIN_HISTORY_MONTHS` and `CONFIDENCE_THRESHOLD_*` unused

These companion constants are declared public but never referenced in the code. Tests should either verify they're used or document them as dead code.

**Action:** Add this check to Phase 1C as part of `BudgetForecastingEngine` test review.

---

## Updated Phase Scope (Recommended)

### Phase 1A additions (+3 files)
- `SynthesisEngineGoldenTest.kt`
- `MonteCarloSpendingSimulatorGoldenTest.kt`
- `AutomatedSavingsRuleEngineGoldenTest.kt`

### Phase 1B additions (+4 files)
- `CustomSplitParserTest.kt`
- `SharedExpenseBudgetOffsetEngineTest.kt`
- `AddGroupExpenseUseCaseTest.kt` (+ `DeleteGroupUseCaseTest`, `DeleteGroupMemberUseCaseTest` — can be one file)

### Phase 1C additions (+4 files)
- `BudgetMonitorTest.kt`
- `SharedBudgetManagerTest.kt`
- `BudgetRecommendationEngineTest.kt` (moved from 3B)
- `BudgetAutopilotEngineDeadDepsTest.kt` (can be added to existing `BudgetAutopilotEngineTest`)

### Phase 1D additions (+1 check)
- `FinancialHealthCalculatorTest.kt` (if V1 is still active)
- `convertMultiple` scenarios added to planned `CurrencyConverterEdgeCaseTest`

### Phase 1E additions (+3 files)
- `TransferDirectionAnalyticsTest.kt`
- `OcrLanguageProcessorTest.kt`
- `WarrantyTextExtractorTest.kt`

### Phase 2A additions (+4 pipelines)
- `ReceiptProcessingPipelineTest.kt`
- `NotificationCategorizationPipelineTest.kt`
- `SavingsBudgetPipelineTest.kt`
- `DashboardCompilationPipelineTest.kt`

### Phase 2B additions (+3 files)
- `HealthScoreConsistencyTest.kt`
- `BudgetPeriodAlignmentTest.kt`
- `AmountEffectiveAmountConsistencyTest.kt`

### Phase 2C additions (+4 VMs)
- `NaturalLanguageSearchViewModelTest.kt`
- `VisualSplitViewModelTest.kt`
- `CategoryViewModelTest.kt`
- `AdvancedAnalyticsViewModelTest.kt`

### Phase 2D additions (+3 repos)
- `SharedExpenseDataPortAdapterTest.kt`
- `ExportDataRepositoryTest.kt`
- `ManualExpenseRepositoryTest.kt`

### Phase 3A additions (+6 DAOs)
- `ExpenseGroupDaoTest.kt`
- `GroupMemberDaoTest.kt`
- `WarrantyDaoTest.kt`
- `RecommendationDaoTest.kt`
- `MerchantNormalizationDaoTest.kt`
- `ScannedReceiptDaoTest.kt`

### Phase 3B additions (+8 items)
- Workers batch (4 workers)
- `SavingsGamificationEngineTest.kt`
- `EnhancedMerchantExtractorTest.kt`
- `HybridServiceDelegationTest.kt`
- `DashboardExpenseMapperTest.kt`
- Use cases: `CalculateBudgetStatusUseCase`, `PrioritizeReviewItemsUseCase`, `DetectSemanticDuplicateUseCase`, `CategorizeReceiptItemsUseCase`

---

## Updated Test Count Estimates

| Phase | Original | Added | New Total |
|-------|----------|-------|-----------|
| 0 | -142 | +3 files to audit | -142 |
| 1A | 8 | +6 | **14** |
| 1B | 20 | +10 | **30** |
| 1C | 15 | +12 | **27** |
| 1D | 15 | +5 | **20** |
| 1E | 12 | +9 | **21** |
| 2A | 25 | +16 | **41** |
| 2B | 35 | +9 | **44** |
| 2C | 25 | +12 | **37** |
| 2D | 20 | +9 | **29** |
| 3A | 30 | +12 | **42** |
| 3B | 40 | +20 | **60** |
| **Total** | **~289** | **+120** | **~407** |

---

## Fixes Required in TESTING-WORKFLOW.md

1. **Phase 0:** Add step 0.12 — audit 3 surviving integration files
2. **Phase 2C:** Fix VM name mismatches:
   - `SubscriptionViewModel` → `SubscriptionManagementViewModel`
   - `BudgetForecastViewModel` → `BudgetForecastingViewModel`
   - `WarrantyViewModel` → `WarrantyTrackerViewModel`
3. **Phase 1A:** Add 3 golden test files
4. **Phase 1B:** Add `CustomSplitParser`, `SharedExpenseBudgetOffsetEngine`, group use cases
5. **Phase 1C:** Add `BudgetMonitor`, `SharedBudgetManager`, move `BudgetRecommendationEngine` from 3B
6. **Phase 1E:** Add `TransferDirectionAnalytics`, `OcrLanguageProcessor`, `WarrantyTextExtractor`
7. **Phase 2A:** Add 4 E2E pipelines (receipt, notification, savings, dashboard)
8. **Phase 2B:** Add 3 consistency tests
9. **Phase 2C:** Add 4 high-priority VMs
10. **Phase 2D:** Add 3 critical repos
11. **Phase 3A:** Add 6 additional DAOs
12. **Phase 3B:** Add workers, mappers, use cases, AI service delegation
