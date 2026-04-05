# Component Test Matrix — Full Codebase

> **Date:** April 5, 2026  
> **Production files:** 642 | **Existing test files:** 269  
> **Legend:** ✅ = has tests | ❌ = no tests | ⚠️ = tests exist but insufficient  
> **Test types:** U = Unit | I = Integration | S = Stress | E = E2E | A = AndroidTest (instrumented) | C = Compose UI | V = Validation (golden values)  
> **For AI agents:** This file lists every production file and what tests it needs. For each component marked ❌ or ⚠️, generate tests following the conventions in `TESTING-AGENT-PLAYBOOK.md`. Use golden values from `CRASH-TEST-SCENARIOS.md` for any `V`-type tests.  
> **Start here:** `TESTING-WORKFLOW.md` — master orchestration with phased batches  
> **Companion files:** `TESTING-AGENT-PLAYBOOK.md` (conventions & templates), `CRASH-TEST-SCENARIOS.md` (golden values & edge cases)

---

## Table of Contents

1. [Data Layer — AI Providers](#1-data-layer--ai-providers)
2. [Data Layer — Database](#2-data-layer--database)
3. [Data Layer — Repositories](#3-data-layer--repositories)
4. [Data Layer — Location](#4-data-layer--location)
5. [Data Layer — Other Services](#5-data-layer--other-services)
6. [Domain Layer — Analytics](#6-domain-layer--analytics)
7. [Domain Layer — AI](#7-domain-layer--ai)
8. [Domain Layer — Budget & Forecasting](#8-domain-layer--budget--forecasting)
9. [Domain Layer — Groups & Splits](#9-domain-layer--groups--splits)
10. [Domain Layer — Categorization & Intelligence](#10-domain-layer--categorization--intelligence)
11. [Domain Layer — Location & Geo](#11-domain-layer--location--geo)
12. [Domain Layer — Savings & Financial Health](#12-domain-layer--savings--financial-health)
13. [Domain Layer — Parsers](#13-domain-layer--parsers)
14. [Domain Layer — Receipt & OCR](#14-domain-layer--receipt--ocr)
15. [Domain Layer — Use Cases](#15-domain-layer--use-cases)
16. [Domain Layer — Utilities](#16-domain-layer--utilities)
17. [Domain Layer — Other Engines](#17-domain-layer--other-engines)
18. [Service Layer](#18-service-layer)
19. [UI Layer — ViewModels](#19-ui-layer--viewmodels)
20. [UI Layer — Screens & Components](#20-ui-layer--screens--components)
21. [DI Layer](#21-di-layer)
22. [Multi-Pipeline E2E Test Suites](#22-multi-pipeline-e2e-test-suites)
23. [Suggested New Test Suites](#23-suggested-new-test-suites)
24. [Priority Matrix](#24-priority-matrix)

---

## 1. Data Layer — AI Providers

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 1 | `CloudCategorizationAssistService.kt` | ✅ | CloudCategorizationAssistServiceTest | U |
| 2 | `CloudDashboardBriefingService.kt` | ✅ | CloudDashboardBriefingServiceTest | U |
| 3 | `CloudDedupeJudgeService.kt` | ✅ | CloudDedupeJudgeServiceTest | U |
| 4 | `CloudQueryInterpretationService.kt` | ✅ | CloudQueryInterpretationServiceTest | U |
| 5 | `CloudReceiptAssistService.kt` | ✅ | CloudReceiptAssistServiceTest | U |
| 6 | `CloudReceiptItemCategorizationService.kt` | ✅ | CloudReceiptItemCategorizationServiceTest | U |
| 7 | `CloudReviewExplanationService.kt` | ✅ | CloudReviewExplanationServiceTest | U |
| 8 | `CloudWarrantyExtractionService.kt` | ✅ | CloudWarrantyExtractionServiceTest | U |
| 9 | `DefaultAiEnvironmentMonitor.kt` | ❌ | — | **U** — battery/network state logic |
| 10 | `HybridCategorizationAssistService.kt` | ❌ | — | **U** — cloud→on-device fallback paths |
| 11 | `HybridDashboardBriefingService.kt` | ❌ | — | **U** — fallback paths |
| 12 | `HybridDedupeJudgeService.kt` | ❌ | — | **U** — fallback paths |
| 13 | `HybridQueryInterpretationService.kt` | ❌ | — | **U** — fallback paths |
| 14 | `HybridReceiptAssistService.kt` | ❌ | — | **U** — fallback paths |
| 15 | `HybridReceiptItemCategorizationService.kt` | ✅ | HybridReceiptItemCategorizationServiceTest | U |
| 16 | `HybridReviewExplanationService.kt` | ❌ | — | **U** — fallback paths |
| 17 | `NoOp*Service.kt` (6 files) | ❌ | — | Low priority — verify returns empty/default |
| 18 | `OnDeviceCategorizationAssistService.kt` | ✅ | OnDeviceCategorizationAssistServiceTest | U |
| 19 | `OnDeviceDashboardBriefingService.kt` | ✅ | OnDeviceDashboardBriefingServiceTest | U |
| 20 | `OnDeviceDedupeJudgeService.kt` | ✅ | OnDeviceDedupeJudgeServiceTest | U |
| 21 | `OnDeviceNotificationParser.kt` | ❌ | — | **U** — parse notification text |
| 22 | `OnDeviceQueryInterpretationService.kt` | ✅ | OnDeviceQueryInterpretationServiceTest | U |
| 23 | `OnDeviceReceiptAssistService.kt` | ✅ | OnDeviceReceiptAssistServiceTest | U |
| 24 | `OnDeviceReceiptItemCategorizationService.kt` | ❌ | — | **U** — item categorization |
| 25 | `OnDeviceReviewExplanationService.kt` | ✅ | OnDeviceReviewExplanationServiceTest | U |
| 26 | `OnDeviceReviewPriorityScorer.kt` | ❌ | — | **U** — priority scoring |
| 27 | `OnDeviceSemanticDuplicateDetector.kt` | ❌ | — | **U** — duplicate detection |
| 28 | `SmartReceiptAssistService.kt` | ✅ | SmartReceiptAssistServiceTest | U |
| 29 | `CloudJsonParser.kt` | ✅ | CloudJsonParserTest | U |
| 30 | `CloudRetryPolicy.kt` | ✅ | CloudRetryPolicyTest | U |
| 31 | `CloudCorrelation.kt` | ❌ | — | **U** — correlation tracking |
| 32 | `CloudPiiSanitizer.kt` | ❌ | — | **U, V** — verify PII stripping |
| 33 | `AiWorkSchedulerImpl.kt` | ❌ | — | **U** — schedule/cancel logic |
| 34 | `DailyBriefingWorker.kt` | ❌ | — | **U** — worker execution |

---

## 2. Data Layer — Database

### 2a. Core Database

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 35 | `AppDatabase.kt` | ✅ | DatabaseMigrationTest (A), MigrationContractTest (A) | A, V — all 46 migration chains |
| 36 | `GroupTransactionCoordinator.kt` | ✅ | GroupTransactionCoordinatorTest | U, S |
| 37 | `Converters.kt` | ✅ | ConvertersTest | U |

### 2b. DAOs (45 files — only 9 have tests)

| # | DAO | Status | Existing Tests | Tests Needed |
|---|-----|--------|---------------|--------------|
| 38 | `ExpenseDao.kt` | ✅ | ExpenseDaoTest (A), DaoBoundaryConsistencyTest | A, U |
| 39 | `PendingReviewDao.kt` | ✅ | PendingReviewDaoTest (A) | A |
| 40 | `AiArtifactDao.kt` | ✅ | AiArtifactDaoTest (A) | A |
| 41 | `AiChatSessionDao.kt` | ✅ | AiChatSessionDaoTest (A) | A |
| 42 | `AiChatMessageDao.kt` | ✅ | AiChatMessageDaoTest (A) | A |
| 43 | `RecommendationDao.kt` | ✅ | RecommendationDaoTest | U |
| 44 | `BudgetDao.kt` | ❌ | — | **A** — budget CRUD, period queries, warning thresholds |
| 45 | `CategoryDao.kt` | ❌ | — | **A** — category CRUD, defaults, uniqueness |
| 46 | `RecurringExpenseDao.kt` | ❌ | — | **A** — recurring insert/query, active filter |
| 47 | `SavingsGoalDao.kt` | ❌ | — | **A** — progress update, goal queries |
| 48 | `ExpenseGroupDao.kt` | ❌ | — | **A** — group CRUD |
| 49 | `GroupMemberDao.kt` | ❌ | — | **A** — member add/remove, FK constraints |
| 50 | `GroupExpenseDao.kt` | ❌ | — | **A** — group expense queries |
| 51 | `MerchantLocationDao.kt` | ❌ | — | **A** — upsert, unique index |
| 52 | `MerchantCategoryDao.kt` | ❌ | — | **A** — category mapping |
| 53 | `MerchantNormalizationDao.kt` | ❌ | — | **A** — canonical/alias queries |
| 54 | `WarrantyDao.kt` | ❌ | — | **A** — expiry queries, status filter |
| 55 | `ExchangeRateDao.kt` | ❌ | — | **A** — rate upsert, staleness |
| 56 | `InvestmentDao.kt` | ❌ | — | **A** — portfolio CRUD |
| 57 | `InvestmentValueDao.kt` | ❌ | — | **A** — price history |
| 58 | `BankConnectionDao.kt` | ❌ | — | **A** — connection state |
| 59 | `SubscriptionCandidateDao.kt` | ❌ | — | **A** — candidate CRUD |
| 60 | `SubscriptionPriceHistoryDao.kt` | ❌ | — | **A** — price tracking |
| 61 | `SubscriptionUsageDao.kt` | ❌ | — | **A** — usage data |
| 62 | `ScannedReceiptDao.kt` | ❌ | — | **A** — receipt matching |
| 63 | `ReceiptItemCategorizationDao.kt` | ❌ | — | **A** — item category mapping |
| 64 | `ReturnWindowDao.kt` | ❌ | — | **A** — return deadline queries |
| 65 | `UserCorrectionDao.kt` | ❌ | — | **A** — correction tracking |
| 66 | `SourceStatsDao.kt` | ❌ | — | **A** — stats increment |
| 67 | `PlannedExpenseDao.kt` | ❌ | — | **A** — planned expense queries |
| 68 | `ManualRecurringExpenseDao.kt` | ❌ | — | **A** — manual recurring CRUD |
| 69 | `RawNotificationDao.kt` | ❌ | — | **A** — notification insert, dedup |
| 70 | `BlockedPackageDao.kt` | ❌ | — | **A** — block list |
| 71 | `BudgetForecastDao.kt` | ❌ | — | **A** — forecast storage |
| 72 | `BudgetAdjustmentDao.kt` | ❌ | — | **A** — adjustment history |
| 73 | `EmailReceiptDao.kt` | ❌ | — | **A** — email receipt tracking |
| 74 | `HealthScoreHistoryDao.kt` | ❌ | — | **A** — history upsert/prune |
| 75 | `SplitTemplateDao.kt` | ❌ | — | **A** — template CRUD |
| 76 | `SplitItemAssignmentDao.kt` | ❌ | — | **A** — assignment tracking |
| 77 | `AnomalyAlertDao.kt` | ❌ | — | **A** — alert CRUD |
| 78 | `MileageTrackingDao.kt` | ❌ | — | **A** — mileage entries |
| 79 | `PromptStateDao.kt` | ❌ | — | **A** — prompt versioning |
| 80 | `SpendingPersonalityProfileDao.kt` | ❌ | — | **A** — profile CRUD |
| 81 | `StressForecastSnapshotDao.kt` | ❌ | — | **A** — snapshot storage |
| 82 | `SavingsSweepPlanDao.kt` | ❌ | — | **A** — sweep plan CRUD |

### 2c. Entities (46 files)

| # | Note | Tests Needed |
|---|------|-------------|
| 83-128 | Entity data classes | **U** — for entities with computed properties/validation. Most are pure data holders (LOW priority). Key entities to test: `Expense.kt` (dedupeKey generation), `Budget.kt` (period mode validation), `GroupExpense.kt` (split serialization) |

### 2d. Database Models (6 files)

| # | Component | Status | Tests Needed |
|---|-----------|--------|-------------|
| 129 | `ExpenseWithCategory.kt` | ❌ | Low — join POJO |
| 130 | `PendingReviewWithReceipt.kt` | ❌ | Low — join POJO |
| 131-134 | Others | ❌ | Low |

---

## 3. Data Layer — Repositories

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 135 | `ExpenseRepository.kt` | ✅ | ExpenseRepositoryTest, StressTest | U, S, I |
| 136 | `ReceiptRepository.kt` | ⚠️ | StressTest only | **U** — processReceipt atomicity, processBatch dispatcher |
| 137 | `ReviewQueueRepository.kt` | ✅ | Test + StressTest | U, S |
| 138 | `NotificationProcessingPipeline.kt` | ✅ | StressTest, OversizedAmount, Reliability | U, S |
| 139 | `DatabaseBackupRepositoryImpl.kt` | ❌ | — | **U, I** — backup/restore, rollback safety, WAL checkpoint |
| 140 | `GroupsRepositoryImpl.kt` | ❌ | — | **U, I** — member delete, split JSON parsing in transaction |
| 141 | `BudgetRepository.kt` | ⚠️ | StressTest, RolloverTest | **U** — period calculation, status queries |
| 142 | `CategoryRepository.kt` | ⚠️ | StressTest only | **U** — CRUD, default categories |
| 143 | `WarrantyTrackerRepository.kt` | ✅ | WarrantyTrackerRepositoryTest | U |
| 144 | `FinancialWeatherRepository.kt` | ✅ | FinancialWeatherRepositoryTest | U |
| 145 | `RecommendationRepository.kt` | ✅ | RecommendationRepositoryTest | U |
| 146 | `MerchantRulesRepository.kt` | ✅ | MerchantRulesRepositoryTest | U |
| 147 | `AiChatRepositoryImpl.kt` | ✅ | AiChatRepositoryImplTest | U |
| 148 | `AiArtifactRepositoryImpl.kt` | ✅ | AiArtifactRepositoryImplTest | U |
| 149 | `NotificationRepository.kt` | ⚠️ | StressTest only | **U** — CRUD basics |
| 150 | `NaturalLanguageExpenseQueryRepositoryImpl.kt` | ❌ | — | **U** — query pagination, deterministic ordering |
| 151 | `MultiCurrencyRepository.kt` | ❌ | — | **U, V** — conversion integration, rate refresh |
| 152 | `AccountingExportRepository.kt` | ❌ | — | **U** — export format correctness |
| 153 | `ExportDataRepository.kt` | ❌ | — | **U** — data aggregation for export |
| 154 | `SubscriptionManagementRepository.kt` | ❌ | — | **U** — subscription lifecycle |
| 155 | `MerchantNormalizationRepository.kt` | ❌ | — | **U** — canonical matching |
| 156 | `MerchantLocationRepository.kt` | ❌ | — | **U** — cache hit/miss, corrections |
| 157 | `MerchantCategoryRepository.kt` | ❌ | — | **U** — mapping CRUD |
| 158 | `RecurringExpenseRepository.kt` | ❌ | — | **U** — detection, active filter |
| 159 | `SavingsGoalRepository.kt` | ❌ | — | **U** — goal progress update |
| 160 | `PlannedExpenseRepository.kt` | ❌ | — | **U** — CRUD |
| 161 | `DashboardRepository.kt` | ❌ | — | **U** — widget data |
| 162 | `DashboardContractsAdapter.kt` | ❌ | — | **U** — adapter mapping |
| 163 | `AnalyticsRepository.kt` | ❌ | — | **U** — aggregation queries |
| 164 | `CurrencyRatesRepositoryImpl.kt` | ❌ | — | **U** — rate fetch/store |
| 165 | `CurrencySettingsRepositoryImpl.kt` | ❌ | — | **U** — settings persistence |
| 166 | `CurrencyDataRepository.kt` | ❌ | — | **U** — data access |
| 167 | `AiSettingsRepositoryImpl.kt` | ❌ | — | **U** — settings CRUD |
| 168 | `AiEngagementRepositoryImpl.kt` | ❌ | — | **U** — engagement metrics |
| 169 | `ManualExpenseRepository.kt` | ❌ | — | **U** — manual expense CRUD |
| 170 | `ManualRecurringExpenseRepository.kt` | ❌ | — | **U** — manual recurring CRUD |
| 171 | `ReceiptItemCategorizationRepository.kt` | ❌ | — | **U** — item categorization |
| 172 | `UserCorrectionRepository.kt` | ❌ | — | **U** — correction tracking |
| 173 | `SourceStatsRepository.kt` | ❌ | — | **U** — stats update |
| 174 | `PromptStateRepository.kt` | ❌ | — | **U** — prompt versioning |
| 175 | `SharedExpenseDataPortAdapter.kt` | ❌ | — | **U** — port adapter |
| 176 | `BusinessExpenseRepository.kt` | ❌ | — | **U** — business expense filter |
| 177 | `WidgetStyleRepositoryImpl.kt` | ❌ | — | **U** — style persistence |
| 178 | `LocationResolverPortsAdapters.kt` | ❌ | — | **U** — port adapter |
| 179 | `GroupsRepository.kt` (interface) | — | — | No test needed (interface) |
| 180 | `ParserEnumMappers.kt` | ❌ | — | **U** — enum mapping correctness |

---

## 4. Data Layer — Location

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 181 | `CompositeGeocodingService.kt` | ✅ | CompositeGeocodingServiceStressTest | U, S, **V** — cascade order |
| 182 | `NominatimGeocodingService.kt` | ⚠️ | LocaleTest only | **U** — API response parsing, error handling |
| 183 | `PhotonGeocodingService.kt` | ❌ | — | **U** — response parsing, PII logging |
| 184 | `GeoapifyGeocodingService.kt` | ❌ | — | **U** — response parsing |
| 185 | `GooglePlacesGeocodingService.kt` | ❌ | — | **U** — response parsing |
| 186 | `OverpassNearbyService.kt` | ❌ | — | **U** — POI query building |
| 187 | `AndroidForegroundLocationProvider.kt` | ❌ | — | **U** — permission handling (needs Robolectric) |
| 188 | `LocationBackfillWorker.kt` | ❌ | — | **U** — worker execution |
| 189 | `MerchantKeyBackfillWorker.kt` | ✅ | MerchantKeyBackfillWorkerTest (A) | A |
| 190 | `LogSanitizer.kt` | ❌ | — | **U, V** — verify PII redaction |

---

## 5. Data Layer — Other Services

| # | Component | Status | Tests Needed |
|---|-----------|--------|-------------|
| 191 | `SecureKeyStorage.kt` | ✅ | U |
| 192 | `BankTokenCipher.kt` | ❌ | **U** — encrypt/decrypt roundtrip |
| 193 | `ExchangeRateStoreAdapter.kt` | ❌ | **U** — store/retrieve rates |
| 194 | `AndroidNotificationService.kt` | ❌ | **U** (Robolectric) — notification channel/post |
| 195 | `AndroidSpeechInputGateway.kt` | ❌ | **U** (Robolectric) — speech intent creation |
| 196 | `MerchantCategoryProvider.kt` | ❌ | **U** — bulk lookup |
| 197 | `EmailReceiptIngestionService.kt` | ✅ | U |
| 198 | `AmazonReceiptParser.kt` | ❌ | **U** — email parsing |
| 199 | `AppleReceiptParser.kt` | ❌ | **U** — email parsing |
| 200 | `UberReceiptParser.kt` | ❌ | **U** — email parsing |
| 201 | `EmailReceiptParser.kt` (interface) | — | No test needed |

---

## 6. Domain Layer — Analytics

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 202 | `InsightsEngine.kt` | ✅ | ValidationTest, StressTest, DeepTest, EdgeCaseTest, Test | U, S, V, **E** |
| 203 | `AdvancedAnalyticsEngine.kt` | ✅ | DeepTest, Test, StressTest | U, S, **V** — stdDev inconsistency |
| 204 | `TotalsAggregationEngine.kt` | ✅ | DeepTest, Test, ValidationTest | U, V, **E** — weekly boundary |
| 205 | `SpendingPaceCalculator.kt` | ✅ | ValidationTest, DeepTest, BoundaryTest | U, V, **E** |
| 206 | `AnomalyDetector.kt` | ✅ | AnomalyDetectorTest | U, **V** — threshold boundaries |
| 207 | `SpendingPersonalityClassifier.kt` | ✅ | Test | U |
| 208 | `SpendingThresholdCalculator.kt` | ✅ | Test | U |
| 209 | `AdvancedAnalyticsDashboard.kt` | ✅ | Test | U |
| 210 | `CategoryInsightEngine.kt` | ❌ | — | **U, V** — category trends, percentage calc |
| 211 | `MerchantInsightEngine.kt` | ❌ | — | **U, V** — merchant ranking |
| 212 | `DayOfWeekAnalyzer.kt` | ❌ | — | **U, V** — day mapping correctness |
| 213 | `MonthlyComparisonCalculator.kt` | ❌ | — | **U, V** — month-over-month percentage |
| 214 | `TransferDirectionAnalytics.kt` | ❌ | — | **U** — transfer vs purchase classification |
| 215 | `AdvancedAnalyticsModels.kt` | ❌ | — | Low — data classes |
| 216 | `AnalyticsModels.kt` | ❌ | — | Low — data classes |

---

## 7. Domain Layer — AI

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 217 | `AiPolicyImpl.kt` | ✅ | AiPolicyTest | U |
| 218 | `DefaultAiCapabilityRouter.kt` | ✅ | DefaultAiCapabilityRouterTest | U |
| 219 | `CategorizationAssistInputBuilder.kt` | ✅ | Test | U |
| 220 | `ReceiptAssistInputBuilder.kt` | ✅ | Test | U |
| 221 | `ReceiptItemCategorizationInputBuilder.kt` | ✅ | Test | U |
| 222 | `FinancialQueryInterpretationInputBuilder.kt` | ✅ | Test | U |
| 223 | `ReviewExplanationInputBuilder.kt` | ✅ | Test | U |
| 224 | `DashboardBriefingInputBuilder.kt` | ❌ | — | **U** — input construction |
| 225 | `DedupeJudgeInputBuilder.kt` | ✅ | Test | U |
| 226 | `GetAiRuntimeStatusUseCase.kt` | ✅ | Test | U |
| 227-238 | All other AI use cases | ✅ | Tests exist | U |
| 239 | `AiRuntimeDiagnostics.kt` | ❌ | — | **U** — diagnostic output |
| 240 | AI model classes (12 files) | ⚠️ | 3 tests for 12 files | Low priority |
| 241 | AI service interfaces (17 files) | — | — | No tests needed (interfaces) |

---

## 8. Domain Layer — Budget & Forecasting

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 242 | `BudgetCalculator.kt` | ✅ | BudgetCalculatorTest, StressTest | U, S, **V** — all 5 periods, leap year, anchor coercion |
| 243 | `BudgetForecastingEngine.kt` | ✅ | BudgetForecastingEngineTest | U, **V** — seasonal adjustment |
| 244 | `BudgetAutopilotEngine.kt` | ✅ | BudgetAutopilotEngineTest | U |
| 245 | `BudgetMonitor.kt` | ✅ | BudgetMonitorTest, StressTest | U, S |
| 246 | `BudgetRecommendationEngine.kt` | ❌ | — | **U** — recommendation generation |
| 247 | `SharedBudgetManager.kt` | ❌ | — | **U** — group budget coordination |
| 248 | `BudgetModels.kt` | ❌ | — | Low — data classes |
| 249 | `BudgetRecommendationInputs.kt` | ❌ | — | Low — data classes |
| 250 | `MonteCarloSpendingSimulator.kt` | ✅ | MonteCarloSpendingSimulatorTest | U, **V** — determinism, percentiles |
| 251 | `FinancialStressForecastEngine.kt` | ✅ | FinancialStressForecastEngineTest | U, **V** — risk levels, fallbacks |
| 252 | `HistoricalSpendingDistribution.kt` | ❌ | — | **U, V** — mu/sigma fitting |
| 253 | `DataQualityAssessor.kt` | ❌ | — | **U** — confidence scoring |
| 254 | `MonteCarloResult.kt` | ❌ | — | Low — data class |

---

## 9. Domain Layer — Groups & Splits

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 255 | `SplitCalculator.kt` | ⚠️ | SplitCalculationPrecisionTest | **U, V, S** — equal/percentage/amount splits, Int overflow, remainder distribution |
| 256 | `SettlementCalculator.kt` | ❌ | — | **U, V, S** — DFS solver, timeout guard, greedy comparison, 15-member stress |
| 257 | `SharedExpenseManager.kt` | ❌ | — | **U, V** — balance calc, split parity with SplitCalculator |
| 258 | `SharedExpenseBudgetOffsetEngine.kt` | ✅ | Test | U |
| 259 | `GroupTransactionCoordinator.kt` (domain) | ❌ | — | **U** — atomic operations |
| 260 | `SharedExpensePort.kt` | — | — | Interface — no test needed |
| 261 | `CustomSplitParser.kt` | ✅ | CustomSplitParserTest | U |
| 262 | `EnhancedSplitManager.kt` | ❌ | — | **U** — split management |
| 263 | `AddGroupExpenseUseCase.kt` | ❌ | — | **U** — expense creation flow |
| 264 | `DeleteGroupMemberUseCase.kt` | ❌ | — | **U** — member removal guards |
| 265 | `DeleteGroupUseCase.kt` | ❌ | — | **U** — cascade delete |

---

## 10. Domain Layer — Categorization & Intelligence

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 266 | `CategorizationEngine.kt` | ⚠️ | Test, StressTest, DebugTest, ComponentsTest | **U, V** — fix assertion-less test, test all strategies |
| 267 | `ContextualInferenceEngine.kt` | ⚠️ | StressTest only | **U** — inference logic |
| 268 | `SemanticKeywordMatcher.kt` | ⚠️ | StressTest only | **U** — keyword matching |
| 269 | `MerchantCanonicalizer.kt` | ⚠️ | StressTest only | **U** — canonicalization rules |
| 270 | `GreeklishNormalizer.kt` | ❌ | — | **U, V** — Greek→Latin mapping |
| 271 | `CategoryKeywords.kt` | ❌ | — | Low — static data |
| 272 | `TransactionClassifier.kt` | ❌ | — | **U** — classification routing |
| 273 | `ConfidenceRouter.kt` | ✅ | Test, EdgeCaseTest | U |
| 274 | `CrossSourceDeduplication.kt` | ❌ | — | **U** — dedup logic |
| 275 | `HybridExpenseClassifier.kt` | ✅ | Test, StressTest | U, S |
| 276 | `ExpenseCategoryClassifier.kt` | ❌ | — | **U** — ML classifier (needs Context mock) |
| 277 | `FeatureExtractor.kt` | ✅ | Test | U |
| 278 | `MerchantNormalizer.kt` | ✅ | Test, StressTest | U, S |
| 279 | `ExpenseClassifier.kt` | ❌ | — | Low — interface |

---

## 11. Domain Layer — Location & Geo

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 280 | `LocationResolver.kt` | ⚠️ | Test (2 tests only), StressTest | **U, V** — full 8-step cascade, cache, GPS bias |
| 281 | `SpendingHeatmapEngine.kt` | ⚠️ | StressTest only | **U, V** — clustering, coordinate binning |
| 282 | `TravelDetectionEngine.kt` | ⚠️ | StressTest only | **U, V** — travel period detection |
| 283 | `LocationInsightsEngine.kt` | ⚠️ | StressTest only | **U** — geographic insights |
| 284 | `AreaSpendingEngine.kt` | ⚠️ | StressTest only | **U** — regional aggregation |
| 285 | `LocationResolverPorts.kt` | — | — | Interface |
| 286 | `GeocodingResult.kt` | ❌ | — | Low — data class |
| 287-289 | Other models | ❌ | — | Low |

---

## 12. Domain Layer — Savings & Financial Health

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 290 | `AutomatedSavingsRuleEngine.kt` | ✅ | Test | U, **V** — round-up, spare change, monthly cap |
| 291 | `SmartSavingsEngine.kt` | ✅ | Test | U, **V** — weighted combination, horizon caps |
| 292 | `SavingsGamificationEngine.kt` | ❌ | — | **U** — gamification logic |
| 293 | `FinancialHealthScoreV2.kt` | ✅ | Test | U, **V** — weighted score, truncation, trend |
| 294 | `FinancialHealthCalculator.kt` | ❌ | — | **U** — alternative calculator |
| 295 | `SavingsGoalRepository.kt` (domain) | — | — | Interface |

---

## 13. Domain Layer — Parsers

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 296 | `AppParserRegistry.kt` | ✅ | Test, RoutingTest, StressTest | U, S |
| 297 | `GenericTransactionParser.kt` | ✅ | Test, StressTest | U, S |
| 298 | `TransferDirectionDetector.kt` | ✅ | Test, StressTest | U, S |
| 299 | `GreekBankParser.kt` | ✅ | Test, StressTest | U, S |
| 300 | `RevolutParser.kt` | ✅ | Test, StressTest | U, S |
| 301 | `SmsParser.kt` | ✅ | Test | U |
| 302 | `GoogleWalletParser.kt` | ✅ | Test | U |
| 303 | `ParsedTransactionEnums.kt` | ❌ | — | Low — enums |

---

## 14. Domain Layer — Receipt & OCR

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 304 | `ReceiptParser.kt` | ✅ | ReceiptParserTest | U |
| 305 | `BankStatementParser.kt` | ✅ | BankStatementParserTest | U |
| 306 | `ReceiptOcrService.kt` | ❌ | — | **U** — OCR invocation, EXIF handling |
| 307 | `OcrPreprocessingPipeline.kt` | ❌ | — | **U** — image preprocessing |
| 308 | `OcrLanguageProcessor.kt` | ❌ | — | **U** — language detection |
| 309 | `EnhancedMerchantExtractor.kt` | ❌ | — | **U** — merchant name extraction |
| 310 | `WarrantyTextExtractor.kt` | ❌ | — | **U** — warranty clause extraction |
| 311 | `MerchantRulesPolicy.kt` | ❌ | — | **U** — rule evaluation |
| 312 | `ReceiptTransactionMatcher.kt` | ❌ | — | **U, V** — fuzzy matching scores |
| 313 | `ReceiptSource.kt` | — | — | Interface |

---

## 15. Domain Layer — Use Cases

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 314 | `CalculateBudgetStatusUseCase.kt` | ❌ | — | **U** — budget vs actual |
| 315 | `GetMonteCarloBudgetImpactUseCase.kt` | ✅ | Test | U |
| 316 | `ComputeDashboardWidgetsUseCase.kt` | ❌ | — | **U, E** — full widget orchestration |
| 317 | `ComputeMoneyRadarUseCase.kt` | ✅ | Test | U |
| 318 | `CategorizeExpenseUseCase.kt` | ❌ | — | **U** — categorization flow |
| 319 | `DetectDuplicateExpenseUseCase.kt` | ❌ | — | **U** — duplicate detection |
| 320 | `ExpenseUseCases.kt` | ❌ | — | **U** — facade operations |
| 321 | `ProcessReceiptUseCase.kt` | ❌ | — | **U, E** — end-to-end receipt flow |
| 322 | `CalculateFinancialForecastUseCase.kt` | ✅ | Test | U |
| 323 | `LifestyleSavingsPromptUseCase.kt` | ✅ | Test | U |
| 324 | `MonthlySavingsSweepUseCase.kt` | ✅ | Test | U |
| 325 | `AutoCreateWarrantyFromReceiptUseCase.kt` | ✅ | Test | U |

---

## 16. Domain Layer — Utilities

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 326 | `TimePeriodUtils.kt` | ✅ | Test, ValidationTest, StressTest | U, V, S |
| 327 | `AmountUtils.kt` | ✅ | Test, StressTest | U, S |
| 328 | `AmountExtractionUtils.kt` | ❌ | — | **U** — regex extraction |
| 329 | `StatisticsUtils.kt` | ⚠️ | StressTest only | **U, V** — stdDev sample vs population |
| 330 | `StringDistanceUtils.kt` | ⚠️ | StressTest only | **U** — Levenshtein correctness |
| 331 | `MerchantKeyGenerator.kt` | ✅ | Test, StressTest | U, S |
| 332 | `MerchantCleaner.kt` | ⚠️ | StressTest only | **U** — cleaning rules |
| 333 | `CurrencyNormalizer.kt` | ✅ | CurrencyNormalizerConsistencyTest | U |
| 334 | `CurrencyFormatter.kt` | ❌ | — | **U, V** — format by locale/symbol |
| 335 | `DateFormatterUtils.kt` | ❌ | — | **U** — date formatting |
| 336 | `GeoUtils.kt` | ❌ | — | **U, V** — Haversine distance formula |
| 337 | `Money.kt` | ✅ | MoneyTest | U |
| 338 | `NotificationIdGenerator.kt` | ✅ | Test | U |
| 339 | `BKTree.kt` | ❌ | — | **U** — insertion, fuzzy search |
| 340 | `CommonPatterns.kt` | ❌ | — | **U** — regex validation |
| 341 | `AppConstants.kt` | — | — | No test needed (constants) |
| 342 | `SystemTimeProvider.kt` | — | — | Trivial (delegates to System.currentTimeMillis) |
| 343 | `TimeProvider.kt` | — | — | Interface |

---

## 17. Domain Layer — Other Engines

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 344 | `DashboardFollowThroughEngine.kt` | ✅ | Test | U |
| 345 | `RecurringExpenseEngine.kt` | ⚠️ | StressTest, EmptyListTest | **U, V** — pattern detection, interval calculation |
| 346 | `SynthesisEngine.kt` | ✅ | Test, StressTest | U, S, **V** — forecast formulas |
| 347 | `NarrativeGenerator.kt` | ❌ | — | **U** — text generation |
| 348 | `RecurrenceCalculator.kt` | ❌ | — | **U, V** — recurrence computation |
| 349 | `CurrencyConverter.kt` | ⚠️ | CurrencyConversionTest | **U, V** — cross-rate, precision, edge cases |
| 350 | `CashFlowCalculator.kt` | ✅ | Test | U |
| 351 | `CarbonFootprintCalculator.kt` | ✅ | Test | U |
| 352 | `TaxEstimator.kt` | ⚠️ | TaxCalculationTest | **U, V** — tax bracket calculations |
| 353 | `PriceProtectionTracker.kt` | ✅ | Test | U |
| 354 | `BankApiIntegration.kt` | ❌ | — | **U** — API flow |
| 355 | `SubscriptionManagerEngine.kt` | ❌ | — | **U** — subscription lifecycle |
| 356 | `NotificationSubscriptionDetector.kt` | ❌ | — | **U** — recurring bill detection |
| 357 | `SpendingChallengeManager.kt` | ❌ | — | **U** — challenge logic |
| 358 | `SmartBillNegotiationEngine.kt` | ❌ | — | **U** — negotiation analysis |
| 359 | `BillReminderManager.kt` | ❌ | — | **U** — reminder scheduling |
| 360 | `InvestmentTracker.kt` | ❌ | — | **U** — portfolio tracking |
| 361 | `LifestyleInflationDetector.kt` | ❌ | — | **U, V** — inflation detection formula |
| 362 | `RecurringIncomeTracker.kt` | ❌ | — | **U** — income pattern detection |
| 363 | `BusinessExpenseReportGenerator.kt` | ❌ | — | **U** — report generation |
| 364 | `ImageCache.kt` | ❌ | — | Low — performance utility |
| 365 | `AnomalyAlertOrchestrator.kt` | ✅ | Test | U |
| 366 | `NaturalLanguageSearchEngine.kt` | ❌ | — | **U** (needs Context mock) — search flow |

---

## 18. Service Layer

| # | Component | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 367 | `NotificationCaptureService.kt` | ✅ | StressTest | U, S |
| 368 | `NotificationFilter.kt` | ✅ | Test | U |
| 369 | `TransactionFilterSerializer.kt` | ✅ | Test | U |
| 370 | `NavigationTargetResolver.kt` | ✅ | Test | U |
| 371 | `RecommendationLifecycleManager.kt` | ✅ | Test | U |
| 372 | `RecommendationDismissalHandler.kt` | ✅ | Test | U |
| 373 | `RecommendationDeduplicator.kt` | ✅ | Test | U |
| 374 | `RecommendationCacheService.kt` | ✅ | Test | U |
| 375 | `RecommendationInvalidator.kt` | ❌ | — | **U** — invalidation logic |
| 376 | `RecommendationStateManager.kt` | ❌ | — | **U** — state management |
| 377 | `ReceiptMatchingWorker.kt` | ❌ | — | **U** — worker execution |
| 378 | `WarrantyExpirationWorker.kt` | ❌ | — | **U** — expiration check |

---

## 19. UI Layer — ViewModels

| # | ViewModel | Status | Existing Tests | Tests Needed |
|---|-----------|--------|---------------|--------------|
| 379 | `MainViewModel.kt` | ✅ | StressTest | U, S |
| 380 | `HomeViewModel.kt` | ✅ | StressTest, RecommendationTest, WidgetTest | U, S |
| 381 | `TransactionsViewModel.kt` | ✅ | StressTest | U, S |
| 382 | `ReviewViewModel.kt` | ✅ | StressTest | U, S |
| 383 | `BudgetViewModel.kt` | ✅ | StressTest | U, S |
| 384 | `AnalyticsViewModel.kt` | ✅ | StressTest, StateStressTest | U, S |
| 385 | `SpendingMapViewModel.kt` | ✅ | StressTest | U, S |
| 386 | `AddExpenseViewModel.kt` | ✅ | StressTest | U, S |
| 387 | `ReceiptScanViewModel.kt` | ✅ | StressTest | U, S — add stale-state, cancellation guard |
| 388 | `DebugViewModel.kt` | ✅ | StressTest | U, S |
| 389 | `ExportOptionsViewModel.kt` | ✅ | Test | U |
| 390 | `AiSettingsViewModel.kt` | ✅ | Test, ScreenTextTest | U |
| 391 | `AssistantViewModel.kt` | ✅ | Test | U |
| 392 | `PriceProtectionViewModel.kt` | ✅ | Test | U |
| 393 | `CashFlowCalendarViewModel.kt` | ✅ | Test | U |
| 394 | `BudgetForecastingViewModel.kt` | ❌ | — | **U, S** — Monte Carlo visualization |
| 395 | `AdvancedAnalyticsViewModel.kt` | ❌ | — | **U** — analytics orchestration |
| 396 | `SharedExpenseGroupsViewModel.kt` | ❌ | — | **U, S** — non-atomic creation, state races |
| 397 | `CurrencyManagementViewModel.kt` | ❌ | — | **U, V** — conversion display |
| 398 | `SavingsGoalsViewModel.kt` | ❌ | — | **U** — goal progress |
| 399 | `SubscriptionManagementViewModel.kt` | ❌ | — | **U** — subscription state |
| 400 | `WarrantyTrackerViewModel.kt` | ❌ | — | **U** — warranty display |
| 401 | `BankConnectionsViewModel.kt` | ❌ | — | **U** — connection flow |
| 402 | `InvestmentViewModel.kt` | ❌ | — | **U** — portfolio display |
| 403 | `NaturalLanguageSearchViewModel.kt` | ❌ | — | **U** — search flow |
| 404 | `CarbonFootprintViewModel.kt` | ❌ | — | **U** — calculation display |
| 405 | `LifestyleInflationViewModel.kt` | ❌ | — | **U** — inflation display |
| 406 | `CategoryViewModel.kt` | ❌ | — | **U** — CRUD operations |
| 407 | `SpendingChallengesViewModel.kt` | ❌ | — | **U** — challenge state |
| 408 | `BillNegotiationViewModel.kt` | ❌ | — | **U** — negotiation flow |
| 409 | `BillRemindersViewModel.kt` | ❌ | — | **U** — reminder management |
| 410 | `VisualSplitViewModel.kt` | ❌ | — | **U** — visual split editing |
| 411 | `ManualRecurringExpenseViewModel.kt` | ❌ | — | **U** — manual recurring CRUD |
| 412 | `ReceiptMatchingViewModel.kt` | ❌ | — | **U** — receipt matching flow |
| 413 | `TaxConfigurationViewModel.kt` | ❌ | — | **U** — tax config |
| 414 | `CategorizationDebugViewModel.kt` | ❌ | — | Low — debug |

---

## 20. UI Layer — Screens & Components

All 77 screen composables and ~51 component files currently have **zero** Compose UI tests.

**Priority Compose tests to add:**

| Priority | Screen | Type | What to test |
|----------|--------|------|-------------|
| **P1** | `HomeScreen.kt` | C | Widget rendering, period navigation, empty state |
| **P1** | `TransactionsScreen.kt` | C | List rendering, filter sheet, empty state |
| **P1** | `BudgetScreen.kt` | C | Budget cards, progress bars, period display |
| **P1** | `ReviewScreen.kt` | C | Review list, approve/reject actions |
| **P2** | `AddExpenseSheet.kt` | C | Form validation, amount input, category picker |
| **P2** | `AnalyticsScreen.kt` | C | Chart rendering, period selector |
| **P2** | `ReceiptScanScreen.kt` | C | Camera trigger, OCR result display |
| **P2** | `SharedExpenseGroupsScreen.kt` | C | Group list, balance display, settlement |
| **P3** | `SpendingMapScreen.kt` | C | Map rendering, marker display |
| **P3** | `AssistantSheet.kt` | C | Chat UI, AI response display |

---

## 21. DI Layer

27 DI module files, all untested. Most are tested transitively through integration tests.

| # | Module | Tests Needed |
|---|--------|-------------|
| 415 | `EmptyStateModule.kt` | **U** — catches UI import violation |
| 416 | All other modules | Low — verify wiring compiles via Hilt test runner |

---

## 22. Multi-Pipeline E2E Test Suites

These test suites trace data flow through multiple components with predetermined expected values.

### Suite E2E-1: Notification → Expense → Dashboard

```
NotificationCaptureService
  → NotificationFilter
    → AppParserRegistry → GreekBankParser/RevolutParser
      → NotificationProcessingPipeline
        → ExpenseRepository.insert()
          → CategorizationEngine.categorize()
            → DashboardContractsAdapter
              → HomeViewModel.state
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-1a | Greek bank notification "Πληρωμή €45.30 LIDL" | Final expense: amount=45.30, category=groceries, merchant=Lidl |
| E2E-1b | Revolut notification "Paid €15.99 to Netflix" | Final expense: amount=15.99, category=entertainment, merchant=Netflix |
| E2E-1c | Duplicate notification (same dedupeKey) | Second insert rejected, no duplicate expense |
| E2E-1d | Non-relevant notification (OTP code) | Filtered out, no expense created |

### Suite E2E-2: Expense → Analytics → Insights → Dashboard Widgets

```
ExpenseRepository (golden dataset)
  → InsightsEngine.generateInsights()
    → SpendingPaceCalculator
    → AnomalyDetector
    → CategoryInsightEngine
    → MerchantInsightEngine
  → TotalsAggregationEngine
  → AdvancedAnalyticsEngine
  → ComputeDashboardWidgetsUseCase
    → HomeViewModel.state
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-2a | Golden March dataset | `currentMonthSpent = 1283.59`, `projectedTotal` matches SpendingPace formula |
| E2E-2b | Golden March + Feb | `monthlyComparison.changePercent ≈ 21.32%` |
| E2E-2c | Category breakdown | grocery=136.10, dining=46.80, rent=800.00 (effectiveAmount) |
| E2E-2d | Sum of category percentages | Must equal exactly 100.00% |
| E2E-2e | effectiveAmount consistency | All analytics use effectiveAmount, legacy insight uses amount for largest tx |

### Suite E2E-3: Receipt → OCR → Categorization → Expense → Review

```
ReceiptScanViewModel.scanReceipt(image)
  → ReceiptOcrService → OcrPreprocessingPipeline
    → ReceiptParser.parse()
      → CategorizeReceiptItemsUseCase
        → ReceiptRepository.processReceipt()
          → ReviewQueueRepository.addPendingReview()
            → ReviewViewModel.state (badge count)
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-3a | Standard receipt (total €67.80) | Parsed amount matches, categorized, pending review created |
| E2E-3b | Receipt with warranty text | AutoCreateWarrantyFromReceiptUseCase triggered |
| E2E-3c | Duplicate receipt scan | Matched to existing expense |
| E2E-3d | Invalid/empty image | Graceful failure, no crash |

### Suite E2E-4: Budget → Monitor → Health Score → Dashboard

```
BudgetRepository.getActiveBudgets()
  → BudgetCalculator.calculatePeriodRange()
    → ExpenseRepository.getTotalForPeriod()
      → BudgetMonitor.checkBudgets()
        → FinancialHealthScoreV2.calculateHealthScore()
          → HomeViewModel.healthWidget
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-4a | Golden data, monthly budget €1500 | spentAmount=1283.59, within budget |
| E2E-4b | Budget of €1000 (exceeded) | budgetAdherence score drops, risk level HIGH |
| E2E-4c | No income data | savingsRateScore=50 (neutral) |
| E2E-4d | Period alignment | BudgetCalculator period matches HealthScore period |

### Suite E2E-5: Group Expense → Split → Settlement → Balance Display

```
SharedExpenseGroupsViewModel.addExpense()
  → SharedExpenseManager.addExpense()
    → SplitCalculator.calculateSplitAmounts()
    → SharedExpenseManager.calculateBalances()
      → SettlementCalculator.calculateSettlements()
        → SharedExpenseGroupsViewModel.state (balances, settlements)
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-5a | 3 members, 3 expenses (€90/60/30) | Net balances: A=+30, B=0, C=-30 |
| E2E-5b | SplitCalc vs SharedExpense parity | Both produce identical net balances |
| E2E-5c | Settlement count | Both greedy and DFS produce valid settlements |
| E2E-5d | Post-settlement balance | All members settle to zero (±0.01) |
| E2E-5e | Sum preservation | Σ split amounts = original expense amount |

### Suite E2E-6: Currency Conversion → Multi-Currency Analytics

```
CurrencyConverter.convert()
  → Expense.effectiveAmount
    → InsightsEngine (uses effectiveAmount)
    → SpendingPaceCalculator (uses effectiveAmount)
    → FinancialHealthScoreV2 (uses effectiveAmount)
    → TotalsAggregationEngine (uses effectiveAmount)
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-6a | €100 expense + $50 expense (rate 1.085) | Total in EUR = 100 + 46.08 = 146.08 |
| E2E-6b | Cross-rate GBP→JPY via EUR | Conversion correct to ±0.01 |
| E2E-6c | Missing rate | Expense excluded from total, failure reported |
| E2E-6d | Rate staleness | Stale rate warning propagated to UI |

### Suite E2E-7: Recurring Detection → Forecast → Stress Test

```
RecurringExpenseEngine.getPatterns()
  → SynthesisEngine.synthesize() (committed/likely)
  → FinancialStressForecastEngine.computeStressForecast()
  → MonteCarloSpendingSimulator.simulate()
  → BudgetForecastingEngine.generateForecast()
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-7a | 3 monthly recurring (rent, Netflix, utilities) | All detected with confidence > 0.90 |
| E2E-7b | Recurring → Synthesis committed | Committed total = sum of high-confidence patterns |
| E2E-7c | Stress forecast with recurring > 80% income | Subscription warning recommendation generated |
| E2E-7d | Monte Carlo determinism | Same inputs → identical percentiles (seed=42L) |

### Suite E2E-8: Savings Rules → Smart Savings → Goal Progress

```
AutomatedSavingsRuleEngine.evaluateRules()
  → SmartSavingsEngine.calculateSafeToSaveAmount()
    → SavingsGoalRepository.updateProgress()
      → SavingsGoalsViewModel.state
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-8a | €17.30 purchase, round-up rule (€5) | savingsAmount = 2.70 |
| E2E-8b | €2500 deposit, 10% income rule | savingsAmount = 250.00, capped by monthly max |
| E2E-8c | Smart savings: WEEK horizon | safeAmount ≤ €75 cap |
| E2E-8d | Essential category exclusion | Rent excluded from discretionary spending |

### Suite E2E-9: Export Pipeline

```
ExpenseRepository.getExpenses()
  → CurrencyConverter.convert() (if multi-currency)
    → AccountingExporters (QuickBooks/Xero/FreshBooks)
      → ExportDataRepository.generateExport()
        → ExportOptionsViewModel.export()
```

| Test | Input | Verify |
|------|-------|--------|
| E2E-9a | 14 golden expenses → CSV export | All 14 rows, correct totals |
| E2E-9b | Multi-currency export | Converted amounts match |
| E2E-9c | Special characters in merchant | CSV properly escaped |
| E2E-9d | Empty dataset | Valid empty file, no crash |

---

## 23. Suggested New Test Suites

### Suite N-1: Financial Arithmetic Precision Suite

**Purpose:** Verify all monetary calculations are precise to the cent.

| Test | Components | Scenario |
|------|-----------|----------|
| N-1a | SplitCalc, SettlementCalc, SharedExpenseMgr | 1000 expenses × 7 members → sum preservation |
| N-1b | CurrencyConverter | 500 conversions accumulated → total drift < 0.01 |
| N-1c | SpendingPace, HealthScore, Analytics | Same golden data → all totals identical |
| N-1d | toCents/fromCents roundtrip | Every amount from 0.01 to 99999.99 → roundtrip exact |
| N-1e | toCents overflow boundary | Verify failure at €21,474,836.48 |

### Suite N-2: Temporal Consistency Suite

**Purpose:** Verify all time-based calculations produce consistent results across components.

| Test | Components | Scenario |
|------|-----------|----------|
| N-2a | BudgetCalc, TimePeriodUtils | Same "March 2026" → identical start/end millis |
| N-2b | InsightsEngine, TotalsAggregation | Same month → same total (no boundary skew) |
| N-2c | SpendingPace, HealthScore | Same date range → same expense set |
| N-2d | RecurringEngine, SynthesisEngine | Same pattern → same next expected date |
| N-2e | DST transition | March 29 2026 → all day boundaries correct |
| N-2f | Leap year | Feb 29 2024 → all period calculations correct |

### Suite N-3: Empty/Zero/Null Resilience Suite

**Purpose:** Verify every engine handles degenerate inputs without crashing.

| Test | Components | Input |
|------|-----------|-------|
| N-3a | All analytics engines | Empty expense list |
| N-3b | BudgetCalculator | Zero budget amount |
| N-3c | SplitCalculator | Zero members, zero amount |
| N-3d | CurrencyConverter | Zero amount, empty currency string |
| N-3e | HealthScore | No deposits, no budgets, no goals, no patterns |
| N-3f | SynthesisEngine | No recurring, no planned, no budget |
| N-3g | MonteCarlo | Zero days remaining |
| N-3h | SettlementCalc | All balances zero |
| N-3i | All ViewModels | Empty initial state load |

### Suite N-4: Constants Consistency Suite

**Purpose:** Verify hardcoded constants match across duplicated locations.

| Test | What to verify |
|------|---------------|
| N-4a | `AutomatedSavingsRuleEngine.ESSENTIAL_CATEGORIES == SmartSavingsEngine.ESSENTIAL_CATEGORIES` |
| N-4b | `SplitCalculator.toCents() == SharedExpenseManager.toCents() == SettlementCalculator.amountToCents()` |
| N-4c | `SplitCalculator.calculateEqualSplit() parity with SharedExpenseManager.calculateEqualSplit()` |
| N-4d | All `0.01` thresholds consistent across SplitCalc, SettlementCalc |
| N-4e | InsightsEngine day-of-week mapping == AdvancedAnalytics day-of-week mapping |
| N-4f | StatisticsUtils.calculateStdDev matches AdvancedAnalytics inline stdDev (sample vs population) |
| N-4g | All hardcoded EUR amounts documented in CRASH-TEST-SCENARIOS Appendix A |

### Suite N-5: Concurrency & State Race Suite

**Purpose:** Verify concurrent operations don't corrupt state.

| Test | Components | Scenario |
|------|-----------|----------|
| N-5a | ReceiptScanViewModel | Rapid re-scan: start analysis, immediately start new scan |
| N-5b | SharedExpenseGroupsVM | Concurrent addExpense + deleteGroup |
| N-5c | HomeViewModel | Concurrent period navigation + data refresh |
| N-5d | NotificationPipeline | 100 notifications in parallel |
| N-5e | ReviewQueueRepository | Concurrent approve + reject same item |
| N-5f | BudgetRepository | Concurrent budget update + status check |

### Suite N-6: Error Propagation Suite

**Purpose:** Verify errors in sub-engines don't crash parent orchestrators.

| Test | Parent | Child Failure | Expected Behavior |
|------|--------|-------------|-------------------|
| N-6a | InsightsEngine | SpendingPaceCalculator throws | Other 7 sub-engines still return data |
| N-6b | InsightsEngine | AnomalyDetector throws | Anomalies = empty, rest intact |
| N-6c | FinancialHealthScoreV2 | ExpenseRepository throws | All scores = 50, trend = STABLE |
| N-6d | ComputeDashboardWidgets | InsightsEngine throws | Graceful degradation to empty widgets |
| N-6e | CompositeGeocodingService | Nominatim timeout | Fallback to Photon |
| N-6f | HybridCategorizationAssist | Cloud API error | Fallback to on-device |

### Suite N-7: Regression Guard Suite

**Purpose:** Golden master tests that catch any change in calculation output.

| Test | Engine | Golden Input | Golden Output (exact) |
|------|--------|-------------|----------------------|
| N-7a | SpendingPace | Golden March, day 15 | `currentMonthSpent=991.79, projectedTotal=2049.03, pacePercentage≈175.0` |
| N-7b | HealthScore | Golden March, no goals/budgets | `overallScore=57` |
| N-7c | Equal split | €100 / 3 members | `[33.34, 33.33, 33.33]` |
| N-7d | Settlement | A:+50, B:-30, C:-20 | 2 transactions, total volume=50 |
| N-7e | MonteCarlo | Deterministic seed=42L | Exact P50 value (snapshot) |
| N-7f | BudgetCalc | MONTHLY, March 15 | `start=Mar 1, end=Apr 1` |
| N-7g | SavingsRoundUp | €17.30, roundUp=5 | `savingsAmount=2.70` |
| N-7h | CategoryBreakdown | Golden March | `grocery%`, `dining%`, `rent%` exact |

---

## 24. Priority Matrix

### Test Effort Summary

| Priority | Category | New Tests | Effort |
|----------|---------|-----------|--------|
| **P0** | E2E Pipeline Suites (E2E-1 to E2E-9) | ~40 | 5 days |
| **P0** | Regression Guards (N-7) | ~8 | 0.5 day |
| **P1** | Groups/Splits (SettlementCalc, SharedExpenseMgr) | ~20 | 2 days |
| **P1** | Financial Precision (N-1) | ~5 | 0.5 day |
| **P1** | Missing DAO instrumented tests (top 10 DAOs) | ~30 | 2 days |
| **P2** | Untested ViewModels (top 5: Groups, Currency, Savings, Subscription, Budget Forecasting) | ~25 | 2 days |
| **P2** | Temporal Consistency (N-2) | ~6 | 0.5 day |
| **P2** | Empty/Zero Resilience (N-3) | ~9 | 0.5 day |
| **P2** | Constants Consistency (N-4) | ~7 | 0.5 day |
| **P2** | Error Propagation (N-6) | ~6 | 0.5 day |
| **P3** | Untested analytics sub-engines (4 files) | ~12 | 1 day |
| **P3** | Concurrency Races (N-5) | ~6 | 0.5 day |
| **P3** | Compose UI tests (top 4 screens) | ~16 | 2 days |
| **P3** | Remaining untested repositories (~20 files) | ~40 | 3 days |
| **P4** | Remaining untested domain engines (~12 files) | ~24 | 2 days |
| **P4** | Remaining untested ViewModels (~10 files) | ~20 | 2 days |
| **P4** | Email parsers, workers, services | ~15 | 1 day |
| | **TOTAL** | **~289** | **~25 days** |

### Top 10 Most Critical Missing Tests

| Rank | Component | Why |
|------|-----------|-----|
| 1 | `SettlementCalculator.kt` | DFS exponential blowup risk, zero tests |
| 2 | `SharedExpenseManager.kt` | Duplicated split logic, zero tests |
| 3 | `SharedExpenseGroupsViewModel.kt` | Non-atomic creation bug, zero tests |
| 4 | E2E-2: Analytics Pipeline | 20 fake integration tests need replacing |
| 5 | E2E-5: Group Split→Settlement→Balance | No end-to-end validation exists |
| 6 | `BudgetDao.kt` | Core financial DAO, zero instrumented tests |
| 7 | `CurrencyManagementViewModel.kt` | Financial operations, zero tests |
| 8 | N-1: Financial Precision Suite | No cross-component precision validation |
| 9 | `DatabaseBackupRepositoryImpl.kt` | Known rollback safety bug, zero tests |
| 10 | E2E-4: Budget→Health Score | Period alignment never verified end-to-end |

---

## Agent Implementation Guide

> This section is for AI agents tasked with generating tests from this matrix.

### How to use this document

1. **Read `TESTING-AGENT-PLAYBOOK.md` first** — it has templates, base classes, and conventions
2. **Pick a row from a table above** where Status is ❌ or ⚠️
3. **Read the production file** listed in the Component column
4. **Identify constructor dependencies** — these become `mockk(relaxed = true)` in your test
5. **Check "Tests Needed" column** — use the letter codes to decide what kind of test to write
6. **For V-type tests**, look up expected values in `CRASH-TEST-SCENARIOS.md`
7. **For E2E tests**, use the pipeline diagrams in Section 22 to understand the flow

### Letter codes explained

| Code | What to Create | Base Class | Special Notes |
|------|---------------|------------|---------------|
| **U** | Unit test | `AnalyticsEngineTestBase` or none | Mock all deps, test one method at a time |
| **I** | Integration test | None | Use real instances of 2+ components, mock only DAO |
| **S** | Stress test | `AnalyticsEngineTestBase` | Large datasets (1000+ items), timing assertions |
| **E** | E2E pipeline test | `AnalyticsEngineTestBase` | Real instances of entire pipeline, mock only data source |
| **A** | AndroidTest (instrumented) | None | Place in `androidTest/`, use `Room.inMemoryDatabaseBuilder` |
| **C** | Compose UI test | None | Use `createComposeRule()`, place in `androidTest/` |
| **V** | Validation (golden values) | `AnalyticsEngineTestBase` | Use exact values from `CRASH-TEST-SCENARIOS.md` |

### Batch generation order

When asked to generate "all missing tests", work in this order:
1. **P0 first**: Regression guards (N-7), then E2E suites (E2E-1 through E2E-9)
2. **P1 next**: SettlementCalculator, SharedExpenseManager, Financial Precision (N-1)
3. **P2 then**: Untested ViewModels, temporal/empty/error suites
4. **P3 last**: Remaining repositories, compose tests, workers
