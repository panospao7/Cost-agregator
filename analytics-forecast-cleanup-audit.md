# Phase 10 Audit: Analytics / Forecast / AI Cleanup

**Date**: 2026-05-02
**Scope**: Every `.kt` file related to analytics, forecasting, AI/ML, smart savings, financial health
**Context**: Phase 10 of roadmap — "Only after money/time/lifecycle foundations exist, clean up engines"

---

## Table of Contents
1. [Analytics Engine Inventory](#phase-1-analytics-engine-inventory)
2. [Forecasting Engine Inventory](#phase-2-forecasting-engine-inventory)
3. [AI/ML Engine Inventory](#phase-3-aiml-engine-inventory)
4. [Smart Savings & Health](#phase-4-smart-savings--health)
5. [Integration Gaps](#phase-5-integration-gaps)
6. [Blockers & Recommendations](#blockers--recommendations)

---

## Phase 1: Analytics Engine Inventory

### 1.1 Analytics Engines Found

| Engine | File | Lines | Status | Currency-Aware? |
|--------|------|-------|--------|-----------------|
| `AdvancedAnalyticsEngine` | `domain/analytics/AdvancedAnalyticsEngine.kt` | 1047 | ✅ Active | Yes (via `AnalyticsCurrencyNormalizer`) |
| `AnalyticsRepository` | `data/repository/AnalyticsRepository.kt` | 191 | ✅ Active | Yes (via `MultiCurrencyRepository` + `AnalyticsCurrencyNormalizer`) |
| `InsightsEngine` | `domain/analytics/InsightsEngine.kt` | 586 | ✅ Active | Assumes normalized via normalizer |
| `SpendingPaceCalculator` | `domain/analytics/SpendingPaceCalculator.kt` | 118 | ✅ Active | Assumes pre-normalized `Double` |
| `DayOfWeekAnalyzer` | `domain/analytics/DayOfWeekAnalyzer.kt` | 55 | ✅ Active | Assumes pre-normalized `Double` |
| `CategoryInsightEngine` | `domain/analytics/CategoryInsightEngine.kt` | 140 | ✅ Active | Assumes pre-normalized `Double` |
| `MerchantInsightEngine` | `domain/analytics/MerchantInsightEngine.kt` | 177 | ✅ Active | Assumes pre-normalized `Double` |
| `AnomalyDetector` | (inside `domain/analytics/`) | - | ✅ Active | Assumes pre-normalized `Double` |
| `MonthlyComparisonCalculator` | (inside `domain/analytics/`) | - | ✅ Active | Assumes pre-normalized `Double` |
| `AdvancedAnalyticsDashboard` | `domain/analytics/AdvancedAnalyticsDashboard.kt` | 392 | ✅ Active | Yes (via `AnalyticsCurrencyNormalizer`) |
| `AnalyticsCurrencyNormalizer` | `domain/analytics/AnalyticsCurrencyNormalizer.kt` | 281 | ✅ Active | **Central normalization hub** |
| `TransferDirectionAnalytics` | `domain/analytics/TransferDirectionAnalytics.kt` | - | ✅ Active | TBD |
| `AnalyticsWindowingSupport` | `domain/analytics/AnalyticsWindowingSupport.kt` | - | ✅ Active | TBD |

**ViewModels**:
| ViewModel | File | Status |
|-----------|------|--------|
| `AnalyticsViewModel` | `ui/screens/analytics/AnalyticsViewModel.kt` | ✅ Active |
| `AdvancedAnalyticsViewModel` | `ui/screens/analytics/AdvancedAnalyticsViewModel.kt` | ✅ Active |

**Tests** (20+ files):
- `AdvancedAnalyticsEngineTest.kt`, `AdvancedAnalyticsEngineDeepTest.kt`, `AdvancedAnalyticsEngineStressTest.kt`
- `AnalyticsStressTest.kt`, `AnalyticsPipelineTest.kt`
- `MultiCurrencyAnalyticsTest.kt`, `AnalyticsCurrencyNormalizerTest.kt`
- `AnalyticsViewModelStressTest.kt`, `AnalyticsStateStressTest.kt`
- `AdvancedAnalyticsDashboardTest.kt`, `AdvancedAnalyticsViewModelTest.kt`
- `SpendingPaceCalculatorValidationTest.kt`, `SpendingPaceCalculatorDeepTest.kt`, `SpendingPaceBoundaryTest.kt`, `SpendingPaceGoldenTest.kt`
- `DayOfWeekAnalyzerTest.kt`
- `CategoryInsightEngineTest.kt`, `MerchantInsightEngineTest.kt`
- `InsightsEngineTest.kt`, `InsightsEngineValidationTest.kt`, `InsightsEngineEdgeCaseTest.kt`, `InsightsEngineDeepTest.kt`, `InsightsEngineStressTest.kt`
- `TransferDirectionAnalyticsTest.kt`
- `AnalyticsWindowingSupportTest.kt`
- `TimePeriodAnalyticsAlignmentTest.kt`
- `GoldenAnalyticsDatasetTest.kt`, `GoldenAnalyticsDataset.kt`

### 1.2 Analytics Models

**`AnalyticsModels.kt`** (341 lines):
- Contains: `MonthPeriod`, `CategoryInsight`, `MerchantInsight`, `SpendingPace`, `AnomalyTransaction`, `RecurringExpense`, `DayOfWeekInsight`, `MonthlyComparison`
- `TimePeriod` enum: `TODAY, WEEK, MONTH, QUARTER, YEAR, ALL` — **NOT using `PeriodKind`**
- `AnalyticsConversionWarning` types: `INVALID_HOME_CURRENCY, INVALID_TRANSACTION_CURRENCY, MISSING_EXCHANGE_RATE`
- `InsightsSnapshot` — main snapshot model with `displayCurrency` field
- Models: `YearOverYearComparison`, `VelocityAnomaly`, `PostSalaryPattern`, `SuspectTransaction`
- **Note**: Uses `Double` for ALL monetary amounts — no `MoneyAmount`, `MoneyAggregate`, or `ConvertedMoney`

**`AdvancedAnalyticsModels.kt`** (253 lines):
- `AnalyticsPeriod` enum: `WEEK, MONTH, QUARTER, YEAR, CUSTOM` — **NOT using `PeriodKind`**
- `AnalyticsPeriodRange` — untyped `startMs/endMs` — **NOT using `PeriodRange` from `domain.core.time`**
- `EnhancedCategoryAnalytics` — uses `Double` for `totalSpent`, `previousPeriodTotal`, etc.
- `EnhancedMerchantAnalytics` — uses `Double` for all amounts
- `SpendingPatternAnalysis`, `StatisticalInsights`, `HistogramBin`, `TransactionPercentiles`
- All models carry `displayCurrency: String` as a display hint only — **NOT type-safe**

### 1.3 Currency in Analytics — CRITICAL FINDING

**What's good:**
- `AnalyticsCurrencyNormalizer` is a central normalization point that all engines use
- `AnalyticsRepository` uses `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` for totals
- `AdvancedAnalyticsEngine` normalizes ALL expenses through `analyticsCurrencyNormalizer.normalizeSnapshots()` before any computation
- `AdvancedAnalyticsDashboard` also normalizes through the normalizer
- Conversion warnings propagate properly (`AnalyticsConversionWarning`)

**What's wrong:**
- **Despite normalizer usage, all monetary values are `Double`** — no `MoneyAggregate`, `MoneyAmount`, or `ConvertedMoney` types are used
- `displayCurrency` is a `String` everywhere, not `CurrencyCode`
- `SpendingPaceCalculator`, `DayOfWeekAnalyzer`, `CategoryInsightEngine`, `MerchantInsightEngine`, `InsightsEngine` all operate on raw `Double` values with comments like "SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine"
- The comment pattern `// SAFE: data normalized via...` appears **20+ times** across the codebase — this is a fragile trust-based pattern, not type-safety
- `EnhancedCategoryAnalytics.totalSpent` is `Double`, should be `MoneyAmount`
- `StatisticalInsights.meanTransaction` is `Double`, should be `MoneyAmount`
- `SpendingPace.currentMonthSpent` is `Double`, should be `MoneyAmount`

### 1.4 AnalyticsDashboard

**`AdvancedAnalyticsDashboard.kt`** (392 lines):
- `AnalyticsDashboardData` — carries `totalSpent: Double`, `totalIncome: Double`, `netCashflow: Double`, `displayCurrency: String`
- Generates category breakdowns, merchant breakdowns, monthly trends, weekly patterns, insights
- Normalizes through `AnalyticsCurrencyNormalizer` before computation
- Uses raw `Calendar` API for date manipulation instead of `TimePeriodUtils` in some places
- Discounts with `PURCHASE` and `WITHDRAWAL` for spending; `DEPOSIT` for income
- `generateInsights()` produces hardcoded insights around spending/income ratio, weekend patterns, savings rate
- **Gap**: No `PeriodRange` usage — uses raw `startDate: Long, endDate: Long` parameters
- **Gap**: All monetary values are `Double`, not `MoneyAmount`

**Dashboard-adjacent files**:
- `ComputeDashboardWidgetsUseCase.kt` — widget computation
- `DashboardDataProvider.kt` — data provision
- `DashboardRepository.kt` — data access
- `DashboardContractsAdapter.kt` — adapter between contracts
- `DashboardPrimitives.kt` — primitive models
- `DashboardCategoryBreakdown.kt`, `DashboardExpenseMapper.kt`
- `DashboardWidgetUiMapper.kt`, `MoneyRadarWidget.kt`
- `RetroTotalsDashboardCard.kt`, `TotalsDashboardCard.kt`
- `ComputeMoneyRadarUseCase.kt` — visual radar chart
- `DashboardFollowThroughEngine.kt` — follow-through recommendations
- `DashboardAnomalyModule.kt`, `DashboardContractsModule.kt`

---

## Phase 2: Forecasting Engine Inventory

### 2.1 Forecast Engines Found

| Engine | File | Lines | Status | Currency-Aware? |
|--------|------|-------|--------|-----------------|
| `BudgetForecastingEngine` | `domain/budget/BudgetForecastingEngine.kt` | 346 | ✅ Active | **NO** — raw SQL sums |
| `FinancialStressForecastEngine` | `domain/forecasting/FinancialStressForecastEngine.kt` | 599 | ✅ Active | Yes (via `AnalyticsCurrencyNormalizer`) |
| `MonteCarloSpendingSimulator` | `domain/forecasting/MonteCarloSpendingSimulator.kt` | 257 | ✅ Active | Carries `displayCurrency: String` |
| `ForecastInputAssembler` | `domain/forecasting/ForecastInputAssembler.kt` | 404 | ✅ Active | Yes (via `AnalyticsCurrencyNormalizer`) |
| `CalculateFinancialForecastUseCase` | `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt` | 81 | ✅ Active | Yes (via `ForecastInputAssembler`) |
| `HistoricalSpendingDistribution` | `domain/forecasting/` (internal) | - | ✅ Active | TBD |
| `DataQualityAssessor` | `domain/forecasting/DataQualityAssessor.kt` | 131 | ✅ Active | N/A |

**UI**:
| Component | File | Status |
|-----------|------|--------|
| `BudgetForecastingViewModel` | `ui/screens/budget/BudgetForecastingViewModel.kt` | ✅ Active |
| `BudgetForecastingScreen` | `ui/screens/budget/BudgetForecastingScreen.kt` | ✅ Active |
| `FinancialStressForecastCard` | `ui/components/FinancialStressForecastCard.kt` | ✅ Active |
| `MonteCarloForecastCard` | `ui/components/MonteCarloForecastCard.kt` | ✅ Active |
| `ForecastTimeline` | `ui/components/ForecastTimeline.kt` | ✅ Active |
| `MonteCarloBudgetImpactUiMapper` | `ui/mappers/MonteCarloBudgetImpactUiMapper.kt` | ✅ Active |

**Tests**:
- `BudgetForecastingEngineTest.kt`, `BudgetForecastingEngineStubTest.kt`
- `FinancialStressForecastEngineTest.kt`
- `MonteCarloSpendingSimulatorTest.kt`, `MonteCarloSpendingSimulatorGoldenTest.kt`
- `ForecastInputAssemblerTest.kt`
- `CalculateFinancialForecastUseCaseTest.kt`
- `GetMonteCarloBudgetImpactUseCaseTest.kt`
- `BudgetForecastingViewModelTest.kt`

### 2.2 Forecast Models

**`FinancialForecast.kt`** (121 lines):
- `FinancialForecast` — `horizon: ForecastHorizon`, `confidence: Double`, `components: ForecastComponents`, `actionableInsights: List<UiText>`
- `ForecastHorizon` enum: `NEXT_7_DAYS` (fixedDays=7), `NEXT_30_DAYS` (fixedDays=30), `REST_OF_MONTH` (calendar-bound)
- `ForecastHorizon.Kind`: `FIXED_DAYS`, `REST_OF_MONTH`
- `ForecastComponents` — carries `recurringExpenses`, `plannedExpenses`, `goalReserves`, `pastSpendingPoints`, `projectedSpendingPoints`, `totalCommitted`, `totalLikely`, `predictedDiscretionary`, `discretionaryBudget`, `riskLevel`
- `RiskLevel` enum: `LOW, MEDIUM, HIGH, CRITICAL`
- `WeatherNarrative`, `NarrativeSection` — narrative display models
- **Gap**: All monetary values in `ForecastComponents` are `Double` — no `MoneyAmount` or `MoneyAggregate`
- **Gap**: No `PeriodRange` usage — horizon is defined by `ForecastHorizon` enum, not `PeriodKind`

**`MonteCarloResult.kt`** (92 lines):
- `MonteCarloResult` — `percentile10..percentile90`, `probabilityUnderBudget`, `spentToDate`, `knownUpcoming`, `confidence: SimulationConfidence`, `metadata: SimulationMetadata`, `displayCurrency: String`
- `SimulationConfidence` — `score: Double`, `level: ConfidenceLevel`, `reason: String`
- `ConfidenceLevel`: `HIGH (>=0.7)`, `MODERATE (0.4-0.69)`, `LOW (<0.4)`
- `SimulationMetadata` — qualifying weeks, iterations, log-normal params, days remaining, computed timestamp
- **Gap**: All values are `Double` — no `MoneyAmount`

**`StressForecastSnapshot`** (entity):
- `data/database/entity/StressForecastSnapshot.kt` — persistence model for stress forecasts

**`BudgetForecast`** (entity):
- `data/database/entity/BudgetForecast.kt` — `budgetId`, `forecastDate`, `predictedSpending`, `predictedRemaining`, `confidenceScore`, `riskLevel`, `overspendProbability`

### 2.3 Double-Counting Risk — CRITICAL FINDING

**`ForecastInputAssembler.kt`** explicitly documents this risk in its KDoc:

> "This assembler merges manual recurring patterns and planned expenses **independently**, which can lead to double-counting when a planned expense was derived from the same recurring rule."

The KDoc says to use `RecurringLifecycleCoordinator.generateOccurrences()` as the single source of truth, but:
1. `assemble()` calls `generateOccurrences()` for each active rule — but the **returned `ForecastInput` still independently includes both** `recurringPatterns` (from `mergeRecurringPatterns()`) and `plannedExpenses`
2. There is **no cross-deduplication** between the merged recurring patterns and the planned expense list
3. The comment at line 43 says "TODO: Use RecurringLifecycleCoordinator.generateOccurrences as the single source of truth" — this TODO is unresolved

**`CalculateFinancialForecastUseCase.kt`**:
- Passes `confirmedRecurringPatterns` as `detectedRecurringPatterns` to `assemble()`, but passes `manualRecurringEntities = emptyList()`
- The `assemble()` will still include planned expenses separately
- **Result**: If a planned expense has `isRecurring=true` and the pattern is also detected, it's counted twice

**`FinancialStressForecastEngine.kt`**:
- `calculateRecurringOutflows()` iterates `patterns.nextExpectedDate` and manually rolls forward using ad-hoc calendar math (lines 247-255) — **NOT using `RecurringOccurrenceExpander` or `RecurringLifecycleCoordinator`**
- There is a TODO comment at line 224: "TODO: Convert pattern.averageAmount to display currency if pattern.currency differs from display currency." — **not implemented**
- `estimateIncome()` simply divides 90-day deposits by 3 — no use of `MoneyAggregate`

**`MonthlySavingsSweepUseCase.kt`**:
- `calculateKnownUpcomingObligations()` independently sums `recurringUpcoming` + `plannedUpcoming` WITHOUT deduplication (lines 227-239)
- This is the same double-counting pattern as `ForecastInputAssembler`

### 2.4 Currency in Forecasts

**Good:**
- `FinancialStressForecastEngine` uses `AnalyticsCurrencyNormalizer` for expense normalization
- `ForecastInputAssembler` normalizes expenses through `analyticsCurrencyNormalizer.normalizeSnapshots()`
- `MonteCarloSpendingSimulator` carries `displayCurrency: String`

**Bad:**
- `BudgetForecastingEngine` uses raw `expenseDao` SQL sums (`getCategorySpentInPeriod`, `getTotalSpentBetween`) — **NO currency normalization at all**
- `FinancialStressForecastEngine.calculateRecurringOutflows()` sums `pattern.averageAmount` directly without currency conversion (has a TODO for this)
- All forecast models use `Double` for monetary values
- `MonteCarloResult` has `displayCurrency: String` but all percentile values are `Double`

---

## Phase 3: AI/ML Engine Inventory

### 3.1 AI Services — Complete Inventory

**Domain interfaces** (`domain/ai/service/`):
| Service | File | Description |
|---------|------|-------------|
| `DashboardBriefingService` | `DashboardBriefingService.kt` | Dashboard narrative generation |
| `ReviewExplanationService` | `ReviewExplanationService.kt` | Explain pending review items |
| `CategorizationAssistService` | `CategorizationAssistService.kt` | Category suggestions |
| `ReceiptAssistService` | `ReceiptAssistService.kt` | Receipt data extraction |
| `ReceiptItemCategorizationService` | `ReceiptItemCategorizationService.kt` | Categorize receipt line items |
| `QueryInterpretationService` | `QueryInterpretationService.kt` | Natural language financial queries |
| `DedupeJudgeService` | `DedupeJudgeService.kt` | Duplicate detection judgment |
| `AiCapabilityRouter` | `AiCapabilityRouter.kt` | Routes AI requests to cloud/on-device |
| `AiSettingsRepository` | `AiSettingsRepository.kt` | AI settings persistence |
| `AiEnvironmentMonitor` | `AiEnvironmentMonitor.kt` | Environment/connectivity checks |
| `AiEngagementRepository` | `AiEngagementRepository.kt` | Usage tracking |
| `AiChatRepository` | `AiChatRepository.kt` | Chat session persistence |
| `AiWorkScheduler` | `AiWorkScheduler.kt` | Work scheduling for AI tasks |
| `SemanticDuplicateDetector` | `SemanticDuplicateDetector.kt` | Semantic dedup |
| `ReviewPriorityScorer` | `ReviewPriorityScorer.kt` | Priority scoring |
| `NotificationFallbackParser` | `NotificationFallbackParser.kt` | Notification parsing |
| `AiArtifactRepository` | `AiArtifactRepository.kt` | AI artifact persistence |

**Data providers** (`data/ai/provider/`):
| Provider | Type | Description |
|----------|------|-------------|
| `CloudDashboardBriefingService` | Cloud | Dashboard briefing via Gemini |
| `OnDeviceDashboardBriefingService` | On-device | Local dashboard briefing |
| `HybridDashboardBriefingService` | Hybrid | Delegates based on router |
| `NoOpDashboardBriefingService` | Fallback | No-op fallback |
| `CloudReviewExplanationService` | Cloud | Review explanation |
| `OnDeviceReviewExplanationService` | On-device | Local review explanation |
| `HybridReviewExplanationService` | Hybrid | Delegating |
| `NoOpReviewExplanationService` | Fallback | |
| `CloudCategorizationAssistService` | Cloud | Category assist |
| `OnDeviceCategorizationAssistService` | On-device | |
| `HybridCategorizationAssistService` | Hybrid | |
| `NoOpCategorizationAssistService` | Fallback | |
| `CloudReceiptAssistService` | Cloud | Receipt extraction |
| `OnDeviceReceiptAssistService` | On-device | |
| `SmartReceiptAssistService` | Hybrid/Smart | Smart delegation |
| `HybridReceiptAssistService` | Hybrid | |
| `NoOpReceiptAssistService` | Fallback | |
| `CloudReceiptItemCategorizationService` | Cloud | Receipt line items |
| `OnDeviceReceiptItemCategorizationService` | On-device | |
| `HybridReceiptItemCategorizationService` | Hybrid | |
| `CloudDedupeJudgeService` | Cloud | Dedup judgment |
| `OnDeviceDedupeJudgeService` | On-device | |
| `HybridDedupeJudgeService` | Hybrid | |
| `NoOpDedupeJudgeService` | Fallback | |
| `CloudQueryInterpretationService` | Cloud | Query interpretation |
| `OnDeviceQueryInterpretationService` | On-device | |
| `HybridQueryInterpretationService` | Hybrid | |
| `NoOpQueryInterpretationService` | Fallback | |
| `CloudWarrantyExtractionService` | Cloud | Warranty extraction |
| `OnDeviceNotificationParser` | On-device | Notification parsing (privacy-first) |
| `OnDeviceReviewPriorityScorer` | On-device | Review prioritization (privacy-first) |
| `OnDeviceSemanticDuplicateDetector` | On-device | Semantic dedup (privacy-first) |
| `NoOp...` services | Fallback | For each capability |
| `DefaultAiEnvironmentMonitor` | Infrastructure | Network/model monitoring |
| `DashboardBriefingPromptFormatter` | Formatting | Prompt construction |
| `DashboardBriefingResponseParser` | Parsing | Response parsing |
| `StrictAiJsonParsing` | Parsing | Strict JSON parsing |

**Use Cases** (`domain/ai/usecase/`):
| Use Case | Description |
|----------|-------------|
| `GenerateDashboardBriefingUseCase` | Dashboard briefing orchestration |
| `ExplainPendingReviewUseCase` | Review explanation |
| `CategorizeReceiptItemsUseCase` | Receipt item categorization |
| `ExecuteFinancialQueryUseCase` | Financial query execution |
| `InterpretFinancialQueryUseCase` | Query interpretation |
| `DetectSemanticDuplicateUseCase` | Semantic dedup |
| `GenerateTransactionInsightUseCase` | Transaction insights |
| `DeliverProactiveBriefingNotificationUseCase` | Proactive notifications |
| `SyncProactiveBriefingWorkUseCase` | Background sync |
| `GetAiRuntimeStatusUseCase` | Runtime status |
| `CategorizationAssistInputBuilder` | Input assembly |
| `FinancialQueryInterpretationInputBuilder` | Input assembly |
| `DashboardBriefingInputBuilder` | Input assembly |
| `TransactionInsightInputBuilder` | Input assembly |
| `DedupeJudgeInputBuilder` | Input assembly |
| `AiArtifactFreshness` | Cache freshness |

**AI Workers**:
- `DailyBriefingWorker.kt` — WorkManager worker
- `AiWorkSchedulerImpl.kt` — Scheduling implementation

### 3.2 AI Models

**`AiModels.kt`** (196 lines):
- `AiCapability` enum: 12 capabilities (DASHBOARD_BRIEFING, REVIEW_EXPLANATION, etc.)
- `AiMode`: ON_DEVICE, CLOUD, AUTO
- `AiRoute`: ON_DEVICE, CLOUD, DETERMINISTIC_FALLBACK, DISABLED
- `OnDeviceModelStatus`: 8 states (AVAILABLE through UNKNOWN)
- `AiTargetType`: DASHBOARD, PENDING_REVIEW, SCANNED_RECEIPT, EXPENSE, ANALYTICS, QUERY_SESSION
- `AiArtifactStatus`: QUEUED, RUNNING, READY, FAILED, DISMISSED, APPLIED
- `AiSettings`: 17 settings fields (all boolean, default false except aiEnabled/allowOnDeviceAi)
- `AiRouteDecision`: route + reason + provider + model metadata
- `redactBeforeCloud: Boolean` — privacy feature

**Other AI model files**:
- `FinancialQueryModels.kt` — query interpretation models
- `WarrantyExtractionModels.kt` — warranty extraction models
- `NotificationParsingModels.kt` — notification parsing
- `CaptureAssistModels.kt` — capture assist
- `ReceiptItemCategorizationModels.kt` — item categorization
- `ReviewPriorityModels.kt` — review priority
- `AiArtifactPresentation.kt` — artifact presentation
- `SemanticDuplicateModels.kt` — semantic dedup
- `OnDeviceRuntimePresentation.kt` — runtime status presentation
- `AiRuntimeStatusModels.kt` — runtime status
- `AiLoadState.kt` — loading state

### 3.3 AI Policies & Routing

**`AiPolicyImpl.kt`** (54 lines):
- `canUseCloud(settings)` — checks `aiEnabled && allowCloudAi`
- `canUseCloudFor(settings, capability)` — per-capability cloud permission
- `shouldAllowOnDevice(settings, capability)` — per-capability on-device permission
- `shouldRedact(settings, capability)` — privacy redaction

**`DefaultAiCapabilityRouter.kt`** (354 lines):
- Full routing logic: ON_DEVICE mode → no cloud fallback (privacy)
- AUTO mode: cloud-first for some capabilities, on-device-first for others
- Checks: settings, network, WiFi-only mode, API key availability, on-device model status
- NOTIFICATION_PARSE, REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE: on-device only
- 5 capabilities are cloud-first: DASHBOARD_BRIEFING, REVIEW_EXPLANATION, DEDUPE_JUDGE, WARRANTY_EXTRACTION
- On-device implemented for 11 of 12 capabilities (only LOCATION_SUMMARY missing)

### 3.4 AI Data Quality

- **Forecasting Monte Carlo** has `DataQualityAssessor` — assesses data quality based on volume (40%), density (25%), fitness (20%), recency (15%) → produces `SimulationConfidence` with `HIGH/MODERATE/LOW`
- **AI receipt categorization** — no equivalent data quality scoring for AI outputs
- **Dashboard briefing** — no confidence score on AI-generated narratives
- **`FinancialHealthScoreV2`** has `conversionConfidence: Float` field (0.0-1.0) based on currency conversion loss
- **No unified data quality reporting** across all AI/analytics pipelines

### 3.5 Privacy in AI — FAVORABLE

- Privacy gates are **well implemented**:
  - `redactBeforeCloud` setting
  - On-device-first for NOTIFICATION_PARSE, REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE
  - ON_DEVICE mode blocks cloud fallback explicitly
  - `ReceiptImageCloudEnabled` defaults to `false`
  - `allowCloudAi` defaults to `false`
  - Gemini API key check before routing to cloud
  - WiFi-only for cloud option
- **No redaction implementation visible in provider code** — the `shouldRedact()` method exists but no actual redaction logic was found in the providers scanned. Further verification needed.

---

## Phase 4: Smart Savings & Health

### 4.1 Savings Engines

| Engine | File | Lines | Currency-Aware? |
|--------|------|-------|-----------------|
| `SmartSavingsEngine` | `domain/savings/SmartSavingsEngine.kt` | 578 | Yes (via `AnalyticsCurrencyNormalizer`) |
| `AutomatedSavingsRuleEngine` | `domain/savings/AutomatedSavingsRuleEngine.kt` | - | TBD |
| `SavingsGamificationEngine` | `domain/savings/SavingsGamificationEngine.kt` | - | TBD |
| `MonthlySavingsSweepUseCase` | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` | 504 | Yes (via `AnalyticsCurrencyNormalizer`) |
| `SavingsGoalRepository` (domain) | `domain/savings/SavingsGoalRepository.kt` | - | TBD |
| `SavingsGoalRepository` (data) | `data/repository/SavingsGoalRepository.kt` | - | TBD |
| `SavingsContributionHistoryRepository` | `data/repository/SavingsContributionHistoryRepository.kt` | - | TBD |
| `AutomatedSavingsRuleStateRepository` | `data/repository/AutomatedSavingsRuleStateRepository.kt` | - | TBD |
| `LifestyleSavingsPromptUseCase` | `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt` | - | TBD |

**Models**:
| Model | File | Currency-Aware? |
|-------|------|-----------------|
| `SavingsGoal` (domain) | `domain/model/SavingsGoal.kt` | `Double` amounts |
| `SavingsGoal` (entity) | `data/database/entity/SavingsGoal.kt` | `Double` amounts |
| `SavingsSweepPlan` | `data/database/entity/SavingsSweepPlan.kt` | `Double` amounts |
| `GoalSavingsRecommendation` | `domain/savings/SmartSavingsEngine.kt` | `Double` |
| `SavingsSweepRecommendation` | `MonthlySavingsSweepUseCase.kt` | `Double` |
| `GoalAllocation` | `MonthlySavingsSweepUseCase.kt` | `Double` |

**Key findings**:
- `SmartSavingsEngine` normalizes expenses through `AnalyticsCurrencyNormalizer` — ✅
- `MonthlySavingsSweepUseCase` normalizes through `AnalyticsCurrencyNormalizer` — ✅
- Has a TODO to inject `RecurringLifecycleCoordinator` for recurring-aware safe-to-save calculations — **not implemented**
- `calculateKnownUpcomingObligations()` in `MonthlySavingsSweepUseCase` sums recurring + planned without dedup — **double-counting risk**
- Monetary values use `Double` everywhere, not `MoneyAmount`
- `SmartSavingsEngine` has hardcoded caps: `DEFAULT_CAP_WEEK=75.0`, `DEFAULT_CAP_MONTH=200.0`, `DEFAULT_CAP_QUARTER=500.0` — these are in home currency units but hardcoded
- `SavingsGoal` amounts are `Double` with no currency field in domain model (entity has `currency: String`)

### 4.2 Health Engines

| Engine | File | Lines | Currency-Aware? |
|--------|------|-------|-----------------|
| `FinancialHealthScoreV2` | `domain/health/FinancialHealthScoreV2.kt` | 725 | Yes (via `AnalyticsCurrencyNormalizer`) |
| `FinancialHealthCalculator` | `domain/health/FinancialHealthCalculator.kt` | 566 | Yes (via `AnalyticsCurrencyNormalizer`) |
| `HealthScoreHistory` (entity) | `data/database/entity/HealthScoreHistory.kt` | - | N/A |

**UI**:
- `HealthScoreWidget.kt` — UI component
- `FinancialHealthScoreV2Widget.kt` — UI component

**Tests**:
- `FinancialHealthScoreV2Test.kt`
- `FinancialHealthCalculatorTransactionTypeTest.kt`
- `FinancialHealthCalculatorBudgetNormalizationTest.kt`
- `FinancialHealthCalculatorBoundaryTest.kt`
- `HealthScoreGoldenTest.kt`, `HealthScoreEdgeCaseTest.kt`

**Models**:
- `FinancialHealthResult` — `overallScore: Int`, `savingsRateScore`, `runwayScore`, `budgetAdherenceScore`, `billReliabilityScore`, `factorContributions`, `trend: HealthTrend`, `recommendation`, `displayCurrency: String`, `conversionConfidence: Float`
- `HealthFactorContribution` — `name`, `score`, `weight`, `explanation`
- `HealthTrend` — `IMPROVING`, `STABLE`, `DECLINING`
- `HealthScoreResult` — `today`, `week`, `month`, `composite: Int`, `displayCurrency`
- `PeriodHealthScore`, `HealthBreakdown`, `HealthStatus`

**Key findings**:
- `FinancialHealthScoreV2`:
  - Normalizes expenses through `AnalyticsCurrencyNormalizer` — ✅
  - Component weights: Savings Rate (30%), Runway (25%), Budget Adherence (25%), Bill Reliability (20%)
  - `conversionConfidence: Float` tracked in output — ✅
  - `calculateRunwayScore()` has TODO: "Convert goal.currentAmount to comparable currency before summing across goals" — **not implemented**
  - `SavingsGoal.currentAmount` is assumed to be in home currency — fragile
  - `calculateHistoricalMonthlyBaseline()` uses fallback `toExpenseSnapshot()` when normalization fails — may mix currencies
- `FinancialHealthCalculator`:
  - Normalizes through `AnalyticsCurrencyNormalizer` — ✅
  - Uses raw `double` for all monetary comparisons
  - `spendingOnly()` correctly filters to `isSpending` transaction types
  - Uses `TimePeriodUtils` for date ranges — good
  - Hardcoded budget targets: `DEFAULT_DAILY_TARGET=50.0`, `DEFAULT_WEEKLY_TARGET=350.0`, `DEFAULT_MONTHLY_TARGET=1500.0` — in home currency

### 4.3 Currency in Savings/Health

**Good:**
- Both `SmartSavingsEngine` and `MonthlySavingsSweepUseCase` normalize through `AnalyticsCurrencyNormalizer`
- Both health engines normalize through `AnalyticsCurrencyNormalizer`
- `FinancialHealthScoreV2` tracks `conversionConfidence`

**Bad:**
- All monetary values are `Double` across all savings and health models
- `SavingsGoal` domain model has no currency field
- `FinancialHealthScoreV2.calculateRunwayScore()` sums `savingsGoals.currentAmount` across potentially different currencies (has TODO)
- `SmartSavingsEngine` has hardcoded currency-denominated caps
- `FinancialHealthCalculator` has hardcoded currency-denominated budget targets

---

## Phase 5: Integration Gaps

### 5.1 PeriodRange Adoption

**What exists:**
- `domain.core.time.PeriodRange` — typed half-open range with `PeriodKind`, `zoneId`, `contains()`, `isCalendarPeriod`
- `domain.core.time.PeriodKind` — 14 period kinds (TODAY through CUSTOM)
- `domain.model.PeriodRange` — simple `start/end` pair (older version, likely legacy)

**Where PeriodRange SHOULD be used but ISN'T:**

| Location | Current Approach | Severity |
|----------|-----------------|----------|
| `AdvancedAnalyticsModels.kt` → `AnalyticsPeriodRange` | Raw `startMs: Long, endMs: Long` | HIGH |
| `AdvancedAnalyticsEngine.getPeriodRange()` | Returns `AnalyticsPeriodRange` with raw timestamps | HIGH |
| `AnalyticsPeriod` enum | Custom enum (WEEK, MONTH, QUARTER, YEAR, CUSTOM) — overlaps with `PeriodKind` | HIGH |
| `AnalyticsModels.kt` → `TimePeriod` enum | `TODAY, WEEK, MONTH, QUARTER, YEAR, ALL` — **should be `PeriodKind`** | HIGH |
| `AnalyticsModels.kt` → `MonthPeriod` | Custom `year/month/startMs/endMs` — should use `PeriodRange` | MEDIUM |
| `AdvancedAnalyticsDashboard.generateDashboardData()` | Raw `startDate: Long, endDate: Long` | HIGH |
| `AnalyticsRepository.getSpendingSummary()` | Raw `start: Long, end: Long` | MEDIUM |
| `BudgetForecastingEngine.generateForecast()` | `budgetCalculator.calculatePeriodRange()` returns raw pair | MEDIUM |
| `FinancialHealthScoreV2.calculateHealthScore()` | Raw `periodStart: Long, periodEnd: Long` | MEDIUM |
| `FinancialHealthCalculator.calculateHealthScores()` | Uses `TimePeriodUtils` directly | MEDIUM |
| `SmartSavingsEngine` methods | All use raw `Long` timestamps | MEDIUM |
| `MonthlySavingsSweepUseCase` | Uses raw `Long` dates, Calendar API | MEDIUM |
| `SpendingPaceCalculator.calculate()` | Raw `currentMonthStart: Long, previousMonthStart: Long` | MEDIUM |
| `DayOfWeekAnalyzer.analyze()` | Raw `startDate: Long, endDate: Long` | LOW |

**Verdict**: `PeriodRange` and `PeriodKind` are defined but **completely unused** across analytics, forecasting, savings, and health code. All these engines use their own custom period types or raw timestamps.

### 5.2 MoneySnapshot Adoption

**What exists:**
- `MoneyAmount` — typed amount + `CurrencyCode`, supports arithmetic with currency checking
- `MoneyAggregate` — aggregated totals with per-currency breakdown and conversion failures
- `ConvertedMoney` — conversion result with rate metadata
- `MoneyBucket` — per-currency bucket in aggregate
- `CurrencyCode` — typed currency enum
- `CurrencyAssumption` — assumption tracking

**Where MoneySnapshot types SHOULD be used but AREN'T:**

| Location | Current Approach | Severity |
|----------|-----------------|----------|
| `EnhancedCategoryAnalytics.totalSpent` | `Double` | HIGH |
| `EnhancedMerchantAnalytics.totalSpent` | `Double` | HIGH |
| `StatisticalInsights.meanTransaction` | `Double` | HIGH |
| `SpendingPatternAnalysis` (all amounts) | `Double` | HIGH |
| `AnalyticsDashboardData.totalSpent` | `Double` | HIGH |
| `SpendingPace.currentMonthSpent` | `Double` | HIGH |
| `ForecastComponents.totalCommitted` | `Double` | HIGH |
| `ForecastComponents.discretionaryBudget` | `Double` | HIGH |
| `MonteCarloResult.percentile50` | `Double` | HIGH |
| `FinancialForecast.components` | `ForecastComponents` with all `Double` | HIGH |
| `StressHorizon.projectedBalance` | `Double` | HIGH |
| `SavingsRecommendation.safeAmount` | `Double` | HIGH |
| `SavingsSweepRecommendation.safeSweepAmount` | `Double` | HIGH |
| `FinancialHealthResult.overallScore` | `Int` (composite, OK) | LOW |
| `FinancialHealthResult` component scores | `Int` (percentages, OK) | LOW |
| `HealthScoreResult.composite` | `Int` (normalized, OK) | LOW |

**Verdict**: EVERY monetary value in analytics, forecasting, savings, and health is `Double`. The `MoneyAmount`/`MoneyAggregate`/`ConvertedMoney` types exist but are used only in the currency conversion infrastructure itself, not in analytics or forecasting.

### 5.3 RecurringOccurrence Adoption

**What exists:**
- `RecurringOccurrence` (entity) — persistence model with status (`PLANNED`, `PAID`, `SKIPPED`, `MISSED`, `CANCELLED`, `IGNORED`), `occurrenceKey` for dedup, `linkedExpenseId`
- `RecurringOccurrenceDao` — DAO for CRUD
- `RecurringOccurrenceExpander` — expands recurrence rules into concrete `OccurrenceCandidate` within date ranges
- `RecurringOccurrenceMaterializer` — persists resolved occurrences + creates reminders
- `RecurringLifecycleCoordinator` — orchestrates expansion + resolution + materialization + linking
- `OccurrenceConflictResolver` — resolves candidate occurrences against actual expenses

**Where RecurringOccurrence SHOULD be used but ISN'T:**

| Location | Current Approach | Severity |
|----------|-----------------|----------|
| `ForecastInputAssembler.assemble()` | Independently merges manual + detected patterns + planned expenses | **CRITICAL — double counting** |
| `FinancialStressForecastEngine.calculateRecurringOutflows()` | Ad-hoc calendar math, `while` loop rolling `nextDate` by period | HIGH |
| `MonthlySavingsSweepUseCase.calculateKnownUpcomingObligations()` | Independent sums of recurring + planned | HIGH |
| `BudgetForecastingEngine` | No recurring expense consideration at all | MEDIUM |
| `SmartSavingsEngine` | Has a TODO to inject `RecurringLifecycleCoordinator` | MEDIUM |
| `FinancialHealthScoreV2.calculateBillReliabilityScore()` | Uses `RecurringExpenseEngine.getPatterns()` directly — OK but incomplete | MEDIUM |

**Verdict**: The `RecurringOccurrence` infrastructure is complete (expander, materializer, coordinator, DAO, entity), but forecasting engines do NOT use it as the single source of truth. Multiple engines independently expand recurring patterns, leading to duplication and potential double-counting.

### 5.4 DataQuality Tracking

**What exists:**
- `DataQualityAssessor` — assesses Monte Carlo simulation quality (volume 40%, density 25%, fitness 20%, recency 15%)
- `SimulationConfidence` — `score`, `level` (HIGH/MODERATE/LOW), `reason`
- `AnalyticsConversionWarning` — tracks conversion failures with source currencies
- `AnalyticsNormalizationResult.lossPercentage` — percentage of transactions excluded
- `FinancialHealthResult.conversionConfidence` — float 0-1

**What's MISSING:**
- **No unified `DataQualityReport` model** — data quality metrics are scattered
- **No AI output confidence scoring** — AI-generated narratives have no confidence score
- **No consistency checks** between analytics, forecasting, and dashboard results
- **No anomaly detection for data completeness** — gaps in expense data are not tracked
- **No data freshness indicators** in analytics/forecasting outputs

---

## Blockers & Recommendations

### Blocker 1: Double-Counting in Forecasting (CRITICAL)

**Problem**: Both `ForecastInputAssembler` and `MonthlySavingsSweepUseCase` independently merge recurring patterns and planned expenses without deduplication. The KDoc explicitly warns about this.

**Recommendation**: 
1. Make `RecurringLifecycleCoordinator.generateOccurrences()` the **single source of truth** for all recurring expansion
2. Remove ad-hoc expansion from `FinancialStressForecastEngine.calculateRecurringOutflows()`
3. Cross-deduplicate planned expenses by `sourceOccurrenceKey`
4. Replace `ForecastInputAssembler.mergeRecurringPatterns()` with occurrence-based projection

### Blocker 2: PeriodRange Not Adopted (HIGH)

**Problem**: `domain.core.time.PeriodRange` and `PeriodKind` are defined but unused. Every engine has its own period type.

**Recommendation**: 
1. Replace `AnalyticsPeriod` with `PeriodKind` in `AdvancedAnalyticsModels.kt`
2. Replace `AnalyticsPeriodRange` with `PeriodRange` (typed)
3. Replace `TimePeriod` enum in `AnalyticsModels.kt` with `PeriodKind`
4. Replace all raw `startMs/endMs` parameter pairs with `PeriodRange`
5. Deprecate `domain.model.PeriodRange` (the old untyped version)

### Blocker 3: Money Not Adopted (HIGH)

**Problem**: `MoneyAmount`, `MoneyAggregate`, and `ConvertedMoney` exist but aren't used in any analytics, forecasting, savings, or health model. Everything is raw `Double`.

**Recommendation** (staged):
1. **Phase A**: Add `MoneyAmount` wrapper to top-level models (`AnalyticsDashboardData`, `FinancialForecast`, `MonteCarloResult`, `SavingsRecommendation`)
2. **Phase B**: Propagate through `EnhancedCategoryAnalytics`, `EnhancedMerchantAnalytics`, `StatisticalInsights`, `SpendingPatternAnalysis`
3. **Phase C**: Update engine implementations to construct `MoneyAmount` values
4. **Phase D**: Update UI mappers to extract values from `MoneyAmount`

### Blocker 4: BudgetForecastingEngine Not Currency-Aware (HIGH)

**Problem**: `BudgetForecastingEngine` uses raw SQL sums (`expenseDao.getCategorySpentInPeriod()`) with NO currency normalization. This is the only engine that bypasses `AnalyticsCurrencyNormalizer`.

**Recommendation**: Route through `MultiCurrencyRepository` or `AnalyticsCurrencyNormalizer` before computing forecast metrics.

### Blocker 5: Hardcoded Currency Values (MEDIUM)

**Problem**: Hardcoded numeric constants exist in multiple engines:
- `SmartSavingsEngine`: `DEFAULT_CAP_WEEK=75.0`, `DEFAULT_CAP_MONTH=200.0`, `DEFAULT_CAP_QUARTER=500.0`
- `FinancialHealthCalculator`: `DEFAULT_DAILY_TARGET=50.0`, `DEFAULT_WEEKLY_TARGET=350.0`, `DEFAULT_MONTHLY_TARGET=1500.0`
- `FinancialStressForecastEngine`: `DEFAULT_EMERGENCY_BUFFER_FALLBACK=500.0`

**Recommendation**: Either make these settings configurable (user-editable) or at minimum document that they represent home-currency-denominated values.

### Blocker 6: Data Quality Not Centralized (MEDIUM)

**Problem**: Data quality metrics are scattered across `DataQualityAssessor` (forecasting), `AnalyticsConversionWarning` (currency), and `conversionConfidence` (health). No unified reporting.

**Recommendation**: Create a unified `DataQualityReport` model that aggregates:
- Currency conversion loss percentage
- Historical data volume/density
- AI confidence scores
- Data freshness
- Missing category/merchant counts

---

## File Count Summary

| Category | Source Files | Test Files | Total |
|----------|-------------|------------|-------|
| Analytics | 15 | 20 | 35 |
| Forecasting | 10 | 8 | 18 |
| AI/ML | 75 | 35 | 110 |
| Savings | 12 | 6 | 18 |
| Health | 4 | 5 | 9 |
| **Total** | **116** | **74** | **190** |

Note: AI/ML files dominate because of the extensive cloud/on-device/hybrid/NoOp provider pattern (4 implementations per capability).

---

## Summary of Integration Gaps

| Gap | Severity | Affected Components |
|-----|----------|-------------------|
| Double-counting: recurring + planned | 🔴 CRITICAL | ForecastInputAssembler, FinancialStressForecastEngine, MonthlySavingsSweepUseCase |
| PeriodRange not used | 🔴 HIGH | ALL analytics, forecasting, savings, health engines |
| MoneyAmount/MoneyAggregate not used | 🔴 HIGH | ALL analytics, forecasting, savings, health models |
| BudgetForecastingEngine not currency-aware | 🔴 HIGH | BudgetForecastingEngine |
| FinancialStressForecast recurring outflows unnormalized | 🟡 HIGH | FinancialStressForecastEngine |
| SmartSavingsEngine missing RecurringLifecycleCoordinator | 🟡 MEDIUM | SmartSavingsEngine |
| Runway score savings-currency TODO | 🟡 MEDIUM | FinancialHealthScoreV2 |
| Hardcoded currency caps/targets | 🟡 MEDIUM | SmartSavingsEngine, FinancialHealthCalculator, FinancialStressForecastEngine |
| No unified DataQualityReport | 🟢 LOW | All pipelines |
| AI data quality confidence not in outputs | 🟢 LOW | AI providers |
