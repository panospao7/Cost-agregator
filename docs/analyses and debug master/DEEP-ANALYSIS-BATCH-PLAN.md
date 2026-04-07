# Deep Backend Analysis — Batch Organization Plan

**Generated:** 2026-04-06  
**Total Backend Files:** 477  
**Total Batches:** 25  
**Files per Batch:** ~19 average (range: 8-30)  
**Parallel Strategy:** Max 5 batches at a time (2 agents × 5 batches = 10 concurrent instances)

---

## Batch Execution Strategy

### Rules for Agents
1. **@reviewer** and **@debugger** analyze the SAME batch independently
2. Each agent writes findings to `docs/quality/DEEP-ANALYSIS-BATCH-XX.md`
3. After all 5 batches in a wave are done, move to next wave
4. If a batch times out, split it into 2 sub-batches

### Wave Execution Plan

| Wave | Batches | Files | Parallel Instances |
|------|---------|-------|-------------------|
| **Wave 1** | B01-B05 | ~95 | 10 (5 batches × 2 agents) |
| **Wave 2** | B06-B10 | ~95 | 10 |
| **Wave 3** | B11-B15 | ~95 | 10 |
| **Wave 4** | B16-B20 | ~95 | 10 |
| **Wave 5** | B21-B25 | ~97 | 10 |

---

## Batch Definitions

### Wave 1: Core Domain Engines

#### B01: Analytics Engines (16 files)
```
domain/analytics/AdvancedAnalyticsEngine.kt
domain/analytics/AdvancedAnalyticsDashboard.kt
domain/analytics/AnomalyDetector.kt
domain/analytics/CategoryInsightEngine.kt
domain/analytics/DayOfWeekAnalyzer.kt
domain/analytics/InsightsEngine.kt
domain/analytics/MerchantInsightEngine.kt
domain/analytics/MonthlyComparisonCalculator.kt
domain/analytics/SpendingPaceCalculator.kt
domain/analytics/SpendingPersonalityClassifier.kt
domain/analytics/SpendingThresholdCalculator.kt
domain/analytics/TotalsAggregationEngine.kt
domain/analytics/TransferDirectionAnalytics.kt
domain/analytics/AnalyticsModels.kt
domain/analytics/AdvancedAnalyticsModels.kt
domain/analytics/SpendingPaceModels.kt
```

#### B02: Budget Engines (8 files)
```
domain/budget/BudgetCalculator.kt
domain/budget/BudgetForecastingEngine.kt
domain/budget/BudgetAutopilotEngine.kt
domain/budget/BudgetMonitor.kt
domain/budget/BudgetRecommendationEngine.kt
domain/budget/SharedBudgetManager.kt
domain/budget/BudgetModels.kt
domain/budget/BudgetRecommendationModels.kt
```

#### B03: Savings & Health (8 files)
```
domain/savings/AutomatedSavingsRuleEngine.kt
domain/savings/SmartSavingsEngine.kt
domain/savings/SavingsGamificationEngine.kt
domain/savings/SavingsModels.kt
domain/health/FinancialHealthScoreV2.kt
domain/health/FinancialHealthModels.kt
domain/health/HealthScoreModels.kt
domain/health/FinancialHealthCalculator.kt
```

#### B04: Forecasting & Synthesis (10 files)
```
domain/forecasting/FinancialStressForecastEngine.kt
domain/forecasting/MonteCarloSpendingSimulator.kt
domain/forecasting/ForecastModels.kt
domain/forecasting/DataQualityAssessor.kt
domain/forecasting/HistoricalSpendingDistribution.kt
domain/logic/SynthesisEngine.kt
domain/logic/SynthesisModels.kt
domain/logic/RecurringExpenseEngine.kt
domain/logic/CustomSplitParser.kt
domain/logic/SplitCalculator.kt
```

#### B05: Use Cases - Dashboard, Expense, Forecast (13 files)
```
domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt
domain/usecase/dashboard/DashboardDataProvider.kt
domain/usecase/dashboard/DashboardContractsAdapter.kt
domain/usecase/expense/CategorizeExpenseUseCase.kt
domain/usecase/expense/DetectDuplicateExpenseUseCase.kt
domain/usecase/forecast/CalculateFinancialForecastUseCase.kt
domain/usecase/savings/LifestyleSavingsPromptUseCase.kt
domain/usecase/savings/MonthlySavingsSweepUseCase.kt
domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt
domain/usecase/budget/CalculateBudgetStatusUseCase.kt
domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt
domain/usecase/dashboard/DashboardTextKeys.kt
```

---

### Wave 2: AI Subsystem

#### B06: AI Models, Policies, Router (12 files)
```
domain/ai/model/AiModels.kt
domain/ai/model/AiRuntimeStatusModels.kt
domain/ai/model/AiArtifactModels.kt
domain/ai/model/AiArtifactPresentation.kt
domain/ai/model/OnDeviceRuntimePresentation.kt
domain/ai/policy/AiPolicy.kt
domain/ai/policy/DefaultAiCapabilityRouter.kt
domain/ai/policy/AiCapabilityModels.kt
domain/ai/policy/AiRuntimeModels.kt
domain/ai/policy/AiSettingsModels.kt
domain/ai/policy/AiSettingsRepository.kt
domain/ai/policy/AiSettingsModels.kt
```

#### B07: AI Use Cases - Input Builders (12 files)
```
domain/ai/usecase/CategorizationAssistInputBuilder.kt
domain/ai/usecase/DedupeJudgeInputBuilder.kt
domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt
domain/ai/usecase/ReceiptAssistInputBuilder.kt
domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt
domain/ai/usecase/ReviewExplanationInputBuilder.kt
domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt
domain/ai/usecase/ExecuteFinancialQueryUseCase.kt
domain/ai/usecase/ExplainPendingReviewUseCase.kt
domain/ai/usecase/GenerateDashboardBriefingUseCase.kt
domain/ai/usecase/GetAiRuntimeStatusUseCase.kt
domain/ai/usecase/InterpretFinancialQueryUseCase.kt
```

#### B08: AI Use Cases - Navigation, Review, Sync (12 files)
```
domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt
domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt
domain/ai/usecase/PrioritizeReviewItemsUseCase.kt
domain/ai/usecase/SuggestCategoryFallbackUseCase.kt
domain/ai/usecase/SuggestReceiptExtractionUseCase.kt
domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt
domain/ai/usecase/AiUseCaseModels.kt
domain/ai/service/CloudCategorizationAssistService.kt
domain/ai/service/CloudDashboardBriefingService.kt
domain/ai/service/CloudDedupeJudgeService.kt
domain/ai/service/CloudQueryInterpretationService.kt
domain/ai/service/CloudReceiptAssistService.kt
```

#### B09: AI Services - Cloud & OnDevice (14 files)
```
domain/ai/service/CloudReceiptItemCategorizationService.kt
domain/ai/service/CloudReviewExplanationService.kt
domain/ai/service/CloudWarrantyExtractionService.kt
domain/ai/service/OnDeviceCategorizationAssistService.kt
domain/ai/service/OnDeviceDashboardBriefingService.kt
domain/ai/service/OnDeviceDedupeJudgeService.kt
domain/ai/service/OnDeviceQueryInterpretationService.kt
domain/ai/service/OnDeviceReceiptAssistService.kt
domain/ai/service/OnDeviceReviewExplanationService.kt
domain/ai/service/HybridCategorizationAssistService.kt
domain/ai/service/HybridDashboardBriefingService.kt
domain/ai/service/HybridDedupeJudgeService.kt
domain/ai/service/HybridQueryInterpretationService.kt
domain/ai/service/HybridReceiptAssistService.kt
```

#### B10: AI Services - Hybrid + Workers (10 files)
```
domain/ai/service/HybridReviewExplanationService.kt
domain/ai/service/HybridReceiptItemCategorizationService.kt
domain/ai/service/HybridServiceDelegationModels.kt
domain/ai/service/SmartReceiptAssistService.kt
domain/ai/provider/internal/CloudJsonParser.kt
domain/ai/provider/internal/CloudPiiSanitizer.kt
domain/ai/provider/internal/CloudCorrelation.kt
domain/ai/provider/internal/CloudRetryPolicy.kt
data/ai/worker/DailyBriefingWorker.kt
data/ai/worker/AiWorkerModels.kt
```

---

### Wave 3: Data Layer - Database

#### B11: Database - AppDatabase & Migrations (10 files)
```
data/database/AppDatabase.kt
data/database/AppDatabaseMigrations.kt
data/database/DatabaseModule.kt
data/database/DatabaseModels.kt
data/database/converter/Converters.kt
data/database/converter/DateConverters.kt
data/database/converter/MapConverters.kt
data/database/converter/UriConverters.kt
data/database/GroupTransactionCoordinator.kt
data/database/TransactionRollback.kt
```

#### B12: Database - Core Entities (12 files)
```
data/database/entity/Expense.kt
data/database/entity/Category.kt
data/database/entity/Budget.kt
data/database/entity/RecurringExpense.kt
data/database/entity/SavingsGoal.kt
data/database/entity/Subscription.kt
data/database/entity/Warranty.kt
data/database/entity/ReturnWindow.kt
data/database/entity/Recommendation.kt
data/database/entity/PendingReview.kt
data/database/entity/ScannedReceipt.kt
data/database/entity/ReceiptItemCategorization.kt
```

#### B13: Database - Group & Financial Entities (12 files)
```
data/database/entity/ExpenseGroup.kt
data/database/entity/GroupMember.kt
data/database/entity/GroupExpense.kt
data/database/entity/SplitItemAssignment.kt
data/database/entity/BankConnection.kt
data/database/entity/ExchangeRate.kt
data/database/entity/MerchantCanonical.kt
data/database/entity/MerchantAlias.kt
data/database/entity/MerchantLocation.kt
data/database/entity/EmailReceiptSource.kt
data/database/entity/ManualRecurringExpense.kt
data/database/entity/Investment.kt
```

#### B14: Database - DAOs Core (12 files)
```
data/database/dao/ExpenseDao.kt
data/database/dao/CategoryDao.kt
data/database/dao/BudgetDao.kt
data/database/dao/RecurringExpenseDao.kt
data/database/dao/SavingsGoalDao.kt
data/database/dao/SubscriptionDao.kt
data/database/dao/WarrantyDao.kt
data/database/dao/ReturnWindowDao.kt
data/database/dao/RecommendationDao.kt
data/database/dao/PendingReviewDao.kt
data/database/dao/ScannedReceiptDao.kt
data/database/dao/ReceiptItemCategorizationDao.kt
```

#### B15: Database - DAOs Extended (12 files)
```
data/database/dao/ExpenseGroupDao.kt
data/database/dao/GroupMemberDao.kt
data/database/dao/GroupExpenseDao.kt
data/database/dao/SplitItemAssignmentDao.kt
data/database/dao/BankConnectionDao.kt
data/database/dao/ExchangeRateDao.kt
data/database/dao/MerchantNormalizationDao.kt
data/database/dao/MerchantLocationDao.kt
data/database/dao/EmailReceiptDao.kt
data/database/dao/ManualRecurringExpenseDao.kt
data/database/dao/InvestmentDao.kt
data/database/dao/InvestmentValueDao.kt
```

---

### Wave 4: Data Layer - Repositories

#### B16: Repositories - Core (12 files)
```
data/repository/ExpenseRepository.kt
data/repository/BudgetRepository.kt
data/repository/CategoryRepository.kt
data/repository/CurrencyRatesRepository.kt
data/repository/MultiCurrencyRepository.kt
data/repository/CurrencyConverter.kt
data/repository/SavingsGoalRepository.kt
data/repository/SubscriptionRepository.kt
data/repository/WarrantyTrackerRepository.kt
data/repository/RecommendationRepository.kt
data/repository/PendingReviewRepository.kt
data/repository/NotificationRepository.kt
```

#### B17: Repositories - Groups & AI (12 files)
```
data/repository/GroupsRepositoryImpl.kt
data/repository/SharedExpenseDataPortAdapter.kt
data/repository/DatabaseBackupRepositoryImpl.kt
data/repository/NotificationProcessingPipeline.kt
data/repository/ReceiptRepository.kt
data/repository/MerchantRulesRepository.kt
data/repository/AiArtifactRepositoryImpl.kt
data/repository/AiChatRepositoryImpl.kt
data/repository/FinancialWeatherRepository.kt
data/repository/ExportDataRepository.kt
data/repository/AccountingExportRepository.kt
data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt
```

#### B18: Repositories - Location & Security (10 files)
```
data/repository/LocationRepository.kt
data/repository/LocationInsightsRepository.kt
data/repository/LocationResolver.kt
data/repository/MerchantLocationRepository.kt
data/repository/CarbonFootprintRepository.kt
data/repository/PriceProtectionRepository.kt
data/security/SecureKeyStorage.kt
data/security/SecurityModule.kt
data/security/EncryptionUtils.kt
data/email/EmailReceiptIngestionService.kt
```

#### B19: Geocoding Services (8 files)
```
data/location/CompositeGeocodingService.kt
data/location/NominatimGeocodingService.kt
data/location/GeoapifyGeocodingService.kt
data/location/GooglePlacesGeocodingService.kt
data/location/PhotonGeocodingService.kt
data/location/OverpassNearbyService.kt
data/location/LocationModels.kt
data/location/LogSanitizer.kt
```

#### B20: Services & Receivers (10 files)
```
data/service/NotificationCaptureService.kt
data/service/AndroidNotificationService.kt
data/service/NavigationTargetResolver.kt
data/service/RecommendationCacheService.kt
data/service/RecommendationDeduplicator.kt
data/service/RecommendationDismissalHandler.kt
data/service/RecommendationLifecycleManager.kt
data/service/RecommendationStateManager.kt
data/service/TransactionFilterSerializer.kt
data/service/NotificationFilter.kt
```

---

### Wave 5: Remaining + DI

#### B21: Categorization & Intelligence (10 files)
```
domain/categorization/CategorizationEngine.kt
domain/categorization/ContextualInferenceEngine.kt
domain/categorization/MerchantCanonicalizer.kt
domain/categorization/SemanticKeywordMatcher.kt
domain/categorization/HybridExpenseClassifier.kt
domain/categorization/ConfidenceRouter.kt
domain/categorization/FeatureExtractor.kt
domain/categorization/CategorizationModels.kt
domain/intelligence/ml/MerchantNormalizer.kt
domain/intelligence/ml/EnhancedMerchantExtractor.kt
```

#### B22: Parsing & Receipt (12 files)
```
domain/parser/AppParserRegistry.kt
domain/parser/GreekBankParser.kt
domain/parser/RevolutParser.kt
domain/parser/GoogleWalletParser.kt
domain/parser/SmsParser.kt
domain/parser/GenericTransactionParser.kt
domain/parser/TransferDirectionDetector.kt
domain/parser/ParserModels.kt
domain/receipt/ReceiptOcrService.kt
domain/receipt/ReceiptParser.kt
domain/receipt/OcrPreprocessingPipeline.kt
domain/receipt/BankStatementParser.kt
```

#### B23: Utilities & Models (14 files)
```
domain/util/TimeProvider.kt
domain/util/TimePeriodUtils.kt
domain/util/AmountUtils.kt
domain/util/Money.kt
domain/util/MerchantKeyGenerator.kt
domain/util/MerchantCleaner.kt
domain/util/StatisticsUtils.kt
domain/util/StringDistanceUtils.kt
domain/util/NotificationIdGenerator.kt
domain/model/UiText.kt
domain/model/BlockPartyStatus.kt
domain/model/PlannedExpense.kt
domain/model/RecurringPattern.kt
domain/model/CategoryInfo.kt
```

#### B24: Workers, Alerts, Other Domain (10 files)
```
domain/alerts/AnomalyAlertOrchestrator.kt
domain/carbon/CarbonFootprintCalculator.kt
domain/cashflow/CashFlowCalculator.kt
domain/engine/DashboardFollowThroughEngine.kt
domain/export/AccountingExporters.kt
domain/groups/SharedExpenseManager.kt
domain/groups/SettlementCalculator.kt
domain/groups/SharedExpenseBudgetOffsetEngine.kt
domain/price/PriceProtectionTracker.kt
domain/tax/TaxEstimator.kt
```

#### B25: DI Modules (27 files)
```
di/AppModule.kt
di/AiModule.kt
di/DatabaseModule.kt
di/DaoModule.kt
di/DispatchersModule.kt
di/EmptyStateModule.kt
di/EmptyStateRegistryInitializer.kt
di/ExportModule.kt
di/GroupsModule.kt
di/LocationModule.kt
di/NetworkModule.kt
di/NetworkQualifiers.kt
di/NotificationModule.kt
di/ReceiptModule.kt
di/SavingsModule.kt
di/SecurityModule.kt
di/ServiceModule.kt
di/SharedExpenseModule.kt
di/SubscriptionModule.kt
di/TaxModule.kt
di/TimeModule.kt
di/UseCaseModule.kt
di/WidgetStyleModule.kt
di/WorkManagerModule.kt
di/ApplicationScopeModule.kt
di/AnalyticsModule.kt
di/BudgetModule.kt
```

---

## Output Format for Each Batch

Each agent writes to: `docs/quality/DEEP-ANALYSIS-BATCH-XX.md`

```markdown
# Deep Analysis — Batch XX: [Name]

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | ... | ... | ... | ... | ... |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | ... | ... | ... | ... |

## @debugger Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | ... | ... | ... | ... | ... |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | ... | ... | ... | ... |

## Consolidated Issues
| # | Source | File | Severity | Type | Description |
|---|--------|------|----------|------|-------------|
| 1 | reviewer | ... | ... | ... | ... |
| 2 | debugger | ... | ... | ... | ... |
```

---

## Execution Commands

### Wave 1 (Batches B01-B05)
```bash
# 10 parallel instances: reviewer × 5 batches + debugger × 5 batches
# Each agent reads the batch files from COMPLETE-BACKEND-MAP.md
# Each agent writes findings to docs/quality/DEEP-ANALYSIS-BATCH-XX.md
```

### After Each Wave
1. Consolidate findings from all batch files
2. Review for duplicates between reviewer and debugger
3. Create prioritized fix list
4. Move to next wave
