# Complete Backend & Database Map - ExpenseTracker

**Generated:** 2026-05-01  
**Total Files Mapped:** 490+ (255 domain + 208 data + 27 di)  
**Test Coverage:** 317+ test files in `app/src/test/java`

---

## Table of Contents

1. [Domain Package (244 files)](#domain-package)
   - [AI/ML Subsystem](#ai-subsystem)
   - [Analytics & Insights](#analytics--insights)
   - [Budget Management](#budget-management)
   - [Categorization Engine](#categorization-engine)
   - [Data Models](#data-models)
   - [Use Cases](#use-cases)
   - [Utilities](#utilities)
2. [Data Package (206 files)](#data-package)
   - [Database Layer](#database-layer)
   - [Repositories](#repositories)
   - [AI Providers](#ai-providers)
   - [Services](#services)
3. [DI/Modules Package (27 files)](#dimodules-package)
4. [Dependency Graph & Data Flow](#dependency-graph--data-flow)

---

## DOMAIN PACKAGE

### Core Time Types (2 files)

**Location:** `com.yourname.expensetracker.domain.core.time`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `core/time/PeriodRange.kt` | PeriodRange | Typed half-open period model `[startInclusive, endExclusive)` with kind (PeriodKind), zoneId, label, contains() | Model | PeriodKind | No |
| `core/time/PeriodKind.kt` | PeriodKind | Semantic period enum: TODAY, THIS_WEEK, LAST_WEEK, LAST_7_DAYS, THIS_MONTH, LAST_MONTH, LAST_30_DAYS, THIS_QUARTER, LAST_QUARTER, THIS_YEAR, LAST_YEAR, CUSTOM | Enum | - | No |

### Receipt Lifecycle Models (11 files)

**Location:** `com.yourname.expensetracker.domain.receipt` and `.lifecycle`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `receipt/ReceiptSourceType.kt` | ReceiptSourceType | Enum: 9 receipt source types (CAMERA, GALLERY, FILE_IMPORT, EMAIL, BANK_STATEMENT, etc.) | Enum | - | No |
| `receipt/ReceiptDocumentType.kt` | ReceiptDocumentType | Enum: 6 document types (RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT) | Enum | - | No |
| `receipt/ReceiptProcessingStatus.kt` | ReceiptProcessingStatus | Enum: 14 processing states from CAPTURED to DELETED | Enum | - | No |
| `receipt/EmailReceiptData.kt` | EmailReceiptData | Structured email receipt data with parsed fields (amount, merchant, currency, date, items) | Model | - | No |
| `receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | ReceiptLifecycleCoordinator | **Single entry point** for all receipt processing: validate → persist → OCR → dedupe → save → event log → side effects | Coordinator | ReceiptRepository, ReceiptLinkService, ReceiptAssetStore, ReceiptInputValidator, ScannedReceiptDao, ReceiptExpenseLinkDao, ReceiptEventDao, TimeProvider, BankStatementLifecycleProcessor, ReceiptSideEffectDispatcher, ReceiptDuplicateDetector | No |
| `receipt/lifecycle/ReceiptLinkService.kt` | ReceiptLinkService | Centralized receipt-expense linking via many-to-many join table. Creates/removes links and writes audit events. | Service | ReceiptExpenseLinkDao, ScannedReceiptDao, ReceiptEventDao, TimeProvider | No |
| `receipt/lifecycle/ReceiptAssetStore.kt` | ReceiptAssetStore | File persistence for receipt assets: copy to app-local storage, SHA-256 hash, camera temp URIs, backup manifests | Store | Context (filesDir) | No |
| `receipt/lifecycle/ReceiptInputValidator.kt` | ReceiptInputValidator | URI/MIME/size validation before processing pipeline | Service | Context | No |
| `receipt/lifecycle/ReceiptDuplicateDetector.kt` | ReceiptDuplicateDetector | 3-signal deduplication: EXACT_HASH (1.0), TEXT_FINGERPRINT (0.95), SEMANTIC (0.8), EXTERNAL_ID (1.0) | Service | ScannedReceiptDao, ReceiptExpenseLinkDao | No |
| `receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | ReceiptSideEffectDispatcher | Document-type-gated post-save side effects (warranty, categorization, matching, price protection) | Dispatcher | AutoCreateWarrantyFromReceiptUseCase, CategorizeReceiptItemsUseCase, ReceiptTransactionMatcher, PriceProtectionTracker | No |
| `receipt/lifecycle/BankStatementLifecycleProcessor.kt` | BankStatementLifecycleProcessor | Statement-specific lifecycle: OCR → parse → PendingReview creation → lifecycle events | Processor | ReceiptRepository, ScannedReceiptDao, ReceiptEventDao, ReceiptLinkService, BankStatementParser, PendingReviewDao, MerchantNormalizer, HybridExpenseClassifier | No |
| `receipt/lifecycle/BankStatementResult.kt` | BankStatementResult | Structured result: receiptId, transactionsFound, reviewsCreated, duplicatesSkipped | Model | - | No |

### Recurring Lifecycle Models (5 files)

**Location:** `com.yourname.expensetracker.domain.recurring` and `.lifecycle`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `recurring/RecurringOccurrenceExpander.kt` | RecurringOccurrenceExpander | Pure utility to expand recurrence rules into concrete occurrence candidates within a half-open date range. Calendar-aware advancement (DST/leap-year safe). | Utility | TimePeriodUtils | No |
| `recurring/OccurrenceConflictResolver.kt` | OccurrenceConflictResolver | Resolves occurrence candidates against actual expenses. Matching: same day, merchant (case-insensitive), amount ±10%, same currency. Each expense matched at most once. | Engine | MerchantKeyGenerator, TimePeriodUtils | No |
| `recurring/RecurringPlanProjectionService.kt` | RecurringPlanProjectionService | Bridges recurring lifecycle to forecasting by materialising PlannedExpense rows from PLANNED occurrences. Deduplicates via sourceOccurrenceKey. | Service | RecurringLifecycleCoordinator, PlannedExpenseDao, TimeProvider | No |
| `recurring/lifecycle/RecurringLifecycleCoordinator.kt` | RecurringLifecycleCoordinator | **Primary entry point** for generating/managing recurring occurrences. Pipelines expand→resolve→materialize. Also provides linkExpenseToOccurrence(), getOccurrences(), updateOccurrenceStatus(), getDueReminders(). | Coordinator | RecurringOccurrenceExpander, OccurrenceConflictResolver, RecurringOccurrenceMaterializer, RecurringOccurrenceDao, ExpenseDao, TimeProvider, ManualRecurringExpenseDao, RecurringReminderDeliveryDao | No |
| `recurring/lifecycle/RecurringOccurrenceMaterializer.kt` | RecurringOccurrenceMaterializer | Persists resolved occurrences (INSERT IGNORE, UPDATE on status change) and creates RecurringReminderDelivery rows for PLANNED occurrences (DUE_DAY, N_DAYS_BEFORE, OVERDUE). | Materializer | RecurringOccurrenceDao, RecurringReminderDeliveryDao, TimeProvider | No |

### Transaction Lifecycle Models (8 files)

**Location:** `com.yourname.expensetracker.domain.transaction`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `transaction/ExpenseSource.kt` | ExpenseSource | Enum: 14 expense origin sources (MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, CSV_IMPORT, BANK_API_SYNC, etc.) | Enum | - | No |
| `transaction/LifecycleEventType.kt` | LifecycleEventType | Enum: 14 lifecycle event types (CREATED, UPDATED, DELETED, CREATE_DUPLICATE_SKIPPED, etc.) | Enum | - | No |
| `transaction/DeduplicationMode.kt` | DeduplicationMode | Enum: deduplication strategy (STANDARD, STRICT_EXTERNAL_ID, BULK_IMPORT, SKIP_FOR_DEBUG_RESTORE) | Enum | - | No |
| `transaction/CreateExpenseRequest.kt` | CreateExpenseRequest | Source-neutral creation request with 40+ fields including required fields, optionals, source links, and policy controls | Model | ExpenseSource, TransactionType, PaymentMethod, TransferDirection, DeduplicationMode | No |
| `transaction/CreateExpenseResult.kt` | CreateExpenseResult | Sealed result: Created, DuplicateSkipped, ValidationFailed, InsertConflict, Error | Model | - | No |
| `transaction/ExpenseUpdates.kt` | ExpenseUpdates | Patch-style update model for modifying existing expenses | Model | TransactionType, PaymentMethod, TransferDirection | No |
| `transaction/lifecycle/TransactionLifecycleCoordinator.kt` | TransactionLifecycleCoordinator | **Single entry point** for ALL expense CUD: validate → normalize → dedupe → insert atomic → event log → side effects | Coordinator | ExpenseDao, TransactionEventDao, TimeProvider, TransactionSideEffectDispatcher | No |
| `transaction/lifecycle/TransactionSideEffectDispatcher.kt` | TransactionSideEffectDispatcher | Post-creation side effects: budget check, anomaly alert, merchant-category learning | Dispatcher | ExpenseDao, CategoryDao, BudgetMonitor, AnomalyAlertOrchestrator, MerchantCategoryRepository | No |

### AI Subsystem (58 files)

**Location:** `com.yourname.expensetracker.domain.ai`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/model/AiArtifactPresentation.kt` | AiArtifactPresentation | Presentation models for AI artifacts | Model | - | No |
| `ai/model/AiLoadState.kt` | AiLoadState | Loading state enums for AI operations | Model/Enum | - | No |
| `ai/model/AiModels.kt` | AiModels | Core AI capability and routing models | Model | - | No |
| `ai/model/AiRuntimeStatusModels.kt` | AiRuntimeStatusModels | Runtime status data classes | Model | - | No |
| `ai/model/CaptureAssistModels.kt` | CaptureAssistModels | Receipt capture assistance models | Model | - | No |
| `ai/model/FinancialQueryModels.kt` | FinancialQueryModels | Natural language query models | Model | - | No |
| `ai/model/NotificationParsingModels.kt` | NotificationParsingModels | SMS/notification parsing models | Model | - | No |
| `ai/model/OnDeviceRuntimePresentation.kt` | OnDeviceRuntimePresentation | On-device ML runtime presentation | Model | - | No |
| `ai/model/ReceiptItemCategorizationModels.kt` | ReceiptItemCategorizationModels | Receipt item categorization models | Model | - | No |
| `ai/model/ReviewPriorityModels.kt` | ReviewPriorityModels | Transaction review prioritization models | Model | - | No |
| `ai/model/SemanticDuplicateModels.kt` | SemanticDuplicateModels | Duplicate detection models | Model | - | No |
| `ai/model/WarrantyExtractionModels.kt` | WarrantyExtractionModels | Warranty data extraction models | Model | - | No |
| `ai/policy/AiPolicy.kt` | AiPolicy | AI policy interface | Service | - | No |
| `ai/policy/AiPolicyImpl.kt` | AiPolicyImpl | AI policy implementation | Service | AiPolicy | No |
| `ai/policy/DefaultAiCapabilityRouter.kt` | DefaultAiCapabilityRouter | Default routing logic | Engine | AiCapabilityRouter | No |
| `ai/service/AiArtifactRepository.kt` | AiArtifactRepository | AI artifact storage interface | Repository | - | No |
| `ai/service/AiCapabilityRouter.kt` | AiCapabilityRouter | Routes AI requests to providers | Service | AiModels | No |
| `ai/service/AiChatRepository.kt` | AiChatRepository | Chat history storage interface | Repository | - | No |
| `ai/service/AiEngagementRepository.kt` | AiEngagementRepository | User engagement tracking | Repository | - | No |
| `ai/service/AiEnvironmentMonitor.kt` | AiEnvironmentMonitor | Monitors device/network environment | Service | - | No |
| `ai/service/AiSettingsRepository.kt` | AiSettingsRepository | AI settings persistence | Repository | - | No |
| `ai/service/AiWorkScheduler.kt` | AiWorkScheduler | Schedules AI background tasks | Service | - | No |
| `ai/service/CategorizationAssistService.kt` | CategorizationAssistService | Assists with expense categorization | Service | - | No |
| `ai/service/DashboardBriefingService.kt` | DashboardBriefingService | Generates dashboard briefings | Service | - | No |
| `ai/service/DedupeJudgeService.kt` | DedupeJudgeService | Judges duplicate transactions | Service | - | No |
| `ai/service/NotificationFallbackParser.kt` | NotificationFallbackParser | Parses SMS when AI unavailable | Service | - | No |
| `ai/service/QueryInterpretationService.kt` | QueryInterpretationService | Interprets natural language queries | Service | - | No |
| `ai/service/ReceiptAssistService.kt` | ReceiptAssistService | Assists receipt extraction | Service | - | No |
| `ai/service/ReceiptItemCategorizationService.kt` | ReceiptItemCategorizationService | Categorizes receipt line items | Service | - | No |
| `ai/service/ReviewExplanationService.kt` | ReviewExplanationService | Explains why items need review | Service | - | No |
| `ai/service/ReviewPriorityScorer.kt` | ReviewPriorityScorer | Scores review priority | Service | - | No |
| `ai/service/SemanticDuplicateDetector.kt` | SemanticDuplicateDetector | Detects semantic duplicates | Service | - | No |
| `ai/usecase/CategorizationAssistInputBuilder.kt` | CategorizationAssistInputBuilder | Builds categorization assist inputs | UseCase | - | No |
| `ai/usecase/CategorizeReceiptItemsUseCase.kt` | CategorizeReceiptItemsUseCase | Categorizes receipt items | UseCase | ReceiptItemCategorizationService | No |
| `ai/usecase/DashboardBriefingInputBuilder.kt` | DashboardBriefingInputBuilder | Builds briefing inputs | UseCase | - | No |
| `ai/usecase/DedupeJudgeInputBuilder.kt` | DedupeJudgeInputBuilder | Builds dedupe judge inputs | UseCase | - | No |
| `ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` | DeliverProactiveBriefingNotificationUseCase | Delivers proactive briefing | UseCase | DashboardBriefingService, AiArtifactRepository | No |
| `ai/usecase/DetectSemanticDuplicateUseCase.kt` | DetectSemanticDuplicateUseCase | Detects semantic duplicates | UseCase | SemanticDuplicateDetector | No |
| `ai/usecase/ExecuteFinancialQueryUseCase.kt` | ExecuteFinancialQueryUseCase | Executes financial queries | UseCase | QueryInterpretationService | No |
| `ai/usecase/ExplainPendingReviewUseCase.kt` | ExplainPendingReviewUseCase | Explains pending review items | UseCase | ReviewExplanationService | No |
| `ai/usecase/FinancialQueryInterpretationInputBuilder.kt` | FinancialQueryInterpretationInputBuilder | Builds query inputs | UseCase | - | No |
| `ai/usecase/GenerateDashboardBriefingUseCase.kt` | GenerateDashboardBriefingUseCase | Generates dashboard briefings | UseCase | DashboardBriefingService | No |
| `ai/usecase/GenerateTransactionInsightUseCase.kt` | GenerateTransactionInsightUseCase | Generates transaction insights | UseCase | - | No |
| `ai/usecase/GetAiRuntimeStatusUseCase.kt` | GetAiRuntimeStatusUseCase | Gets AI runtime status | UseCase | AiEnvironmentMonitor | No |
| `ai/usecase/InterpretFinancialQueryUseCase.kt` | InterpretFinancialQueryUseCase | Interprets financial queries | UseCase | QueryInterpretationService | No |
| `ai/usecase/JudgePendingReviewDuplicateUseCase.kt` | JudgePendingReviewDuplicateUseCase | Judges review duplicates | UseCase | DedupeJudgeService | No |
| `ai/usecase/MapFinancialQueryToNavigationUseCase.kt` | MapFinancialQueryToNavigationUseCase | Maps queries to UI navigation | UseCase | - | No |
| `ai/usecase/PrioritizeReviewItemsUseCase.kt` | PrioritizeReviewItemsUseCase | Prioritizes review items | UseCase | ReviewPriorityScorer | No |
| `ai/usecase/ReceiptAssistInputBuilder.kt` | ReceiptAssistInputBuilder | Builds receipt assist inputs | UseCase | - | No |
| `ai/usecase/ReceiptItemCategorizationInputBuilder.kt` | ReceiptItemCategorizationInputBuilder | Builds item categorization inputs | UseCase | - | No |
| `ai/usecase/ReviewExplanationInputBuilder.kt` | ReviewExplanationInputBuilder | Builds review explanation inputs | UseCase | - | No |
| `ai/usecase/SuggestCategoryFallbackUseCase.kt` | SuggestCategoryFallbackUseCase | Fallback category suggestion | UseCase | - | No |
| `ai/usecase/SuggestReceiptExtractionUseCase.kt` | SuggestReceiptExtractionUseCase | Suggests receipt extraction | UseCase | ReceiptAssistService | No |
| `ai/usecase/SyncProactiveBriefingWorkUseCase.kt` | SyncProactiveBriefingWorkUseCase | Syncs briefing work schedules | UseCase | AiWorkScheduler | No |

### Analytics & Insights (16 files)

**Location:** `com.yourname.expensetracker.domain.analytics`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `analytics/AdvancedAnalyticsDashboard.kt` | AdvancedAnalyticsDashboard | Advanced analytics dashboard logic | Engine | - | No |
| `analytics/AdvancedAnalyticsEngine.kt` | AdvancedAnalyticsEngine | Advanced analytics computation | Engine | - | No |
| `analytics/AdvancedAnalyticsModels.kt` | AdvancedAnalyticsModels | Advanced analytics data models | Model | - | No |
| `analytics/AnalyticsModels.kt` | AnalyticsModels | Core analytics models | Model | - | No |
| `analytics/AnomalyDetector.kt` | AnomalyDetector | Detects spending anomalies | Engine | - | No |
| `analytics/CategoryInsightEngine.kt` | CategoryInsightEngine | Generates category insights | Engine | - | No |
| `analytics/DayOfWeekAnalyzer.kt` | DayOfWeekAnalyzer | Analyzes spending by day of week | Engine | - | No |
| `analytics/InsightsEngine.kt` | InsightsEngine | Central insights generation | Engine | - | No |
| `analytics/MerchantInsightEngine.kt` | MerchantInsightEngine | Merchant-specific insights | Engine | - | No |
| `analytics/MonthlyComparisonCalculator.kt` | MonthlyComparisonCalculator | Calculates monthly comparisons | Engine | - | No |
| `analytics/SpendingPaceCalculator.kt` | SpendingPaceCalculator | Calculates spending pace | Engine | - | No |
| `analytics/SpendingPersonalityClassifier.kt` | SpendingPersonalityClassifier | Classifies spending personality | Engine | - | No |
| `analytics/SpendingThresholdCalculator.kt` | SpendingThresholdCalculator | Calculates spending thresholds | Engine | - | No |
| `analytics/TotalsAggregationEngine.kt` | TotalsAggregationEngine | Aggregates financial totals | Engine | - | No |
| `analytics/TransferDirectionAnalytics.kt` | TransferDirectionAnalytics | Analyzes transfer directions | Engine | - | No |

### Backup & Export (7 files)

**Location:** `com.yourname.expensetracker.domain.backup`, `export`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `backup/DatabaseBackupRepository.kt` | DatabaseBackupRepository | Database backup interface | Repository | - | No |
| `backup/DatabaseOperationResults.kt` | DatabaseOperationResults | Backup operation result models | Model | - | No |
| `export/AccountingExporters.kt` | AccountingExporters | Accounting system exporters | Service | - | No |
| `export/ExportTransaction.kt` | ExportTransaction | Transaction export models | Model | - | No |

### Budget Management (9 files)

**Location:** `com.yourname.expensetracker.domain.budget`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `budget/BudgetAutopilotEngine.kt` | BudgetAutopilotEngine | Auto-adjusts budgets | Engine | - | No |
| `budget/BudgetCalculator.kt` | BudgetCalculator | Budget calculations | Engine | - | No |
| `budget/BudgetForecastingEngine.kt` | BudgetForecastingEngine | Forecasts budget impact | Engine | - | No |
| `budget/BudgetModels.kt` | BudgetModels | Budget data models | Model | - | No |
| `budget/BudgetMonitor.kt` | BudgetMonitor | Monitors budget status | Engine | - | No |
| `budget/BudgetRecommendationEngine.kt` | BudgetRecommendationEngine | Recommends budget adjustments | Engine | - | No |
| `budget/BudgetRecommendationInputs.kt` | BudgetRecommendationInputs | Budget recommendation inputs | Model | - | No |
| `budget/SharedBudgetManager.kt` | SharedBudgetManager | Manages shared budgets | Engine | - | No |

### Categorization Engine (7 files)

**Location:** `com.yourname.expensetracker.domain.categorization`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `categorization/CategorizationEngine.kt` | CategorizationEngine | Core categorization logic | Engine | CategoryKeywords, ContextualInferenceEngine | No |
| `categorization/CategoryKeywords.kt` | CategoryKeywords | Category keyword database | Service | - | No |
| `categorization/ContextualInferenceEngine.kt` | ContextualInferenceEngine | Context-aware categorization | Engine | - | No |
| `categorization/GreeklishNormalizer.kt` | GreeklishNormalizer | Greek to Latin text conversion | Utility | - | No |
| `categorization/MerchantCanonicalizer.kt` | MerchantCanonicalizer | Standardizes merchant names | Engine | - | No |
| `categorization/SemanticKeywordMatcher.kt` | SemanticKeywordMatcher | Semantic keyword matching | Engine | - | No |

### Challenge, Config, Currency (9 files)

**Location:** Various domain subsystems

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `challenge/SpendingChallengeManager.kt` | SpendingChallengeManager | Manages spending challenges | Engine | - | No |
| `config/AppConfig.kt` | AppConfig | Application configuration | Config | - | No |
| `currency/CurrencyConverter.kt` | CurrencyConverter | Currency conversion logic | Engine | - | No |
| `currency/CurrencyRatesRepository.kt` | CurrencyRatesRepository | Exchange rates interface | Repository | - | No |
| `currency/CurrencySettingsRepository.kt` | CurrencySettingsRepository | Currency settings interface | Repository | - | No |
| `currency/ExchangeRateContracts.kt` | ExchangeRateContracts | Exchange rate interfaces | Service | - | No |

### Debug Utilities (6 files)

**Location:** `com.yourname.expensetracker.domain.debug`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `debug/AiRuntimeDiagnostics.kt` | AiRuntimeDiagnostics | Diagnoses AI runtime issues | Utility | - | No |
| `debug/DebugData.kt` | DebugData | Debug data models | Model | - | No |
| `debug/DebugIssue.kt` | DebugIssue | Issue reporting models | Model | - | No |
| `debug/DebugIssueDetector.kt` | DebugIssueDetector | Detects and reports issues | Engine | - | No |
| `debug/NotificationSeeder.kt` | NotificationSeeder | Seeds test notifications | Utility | - | No |
| `debug/ServiceDiagnostics.kt` | ServiceDiagnostics | Service diagnostics | Utility | - | No |

### Dashboard & Engine (3 files)

**Location:** `com.yourname.expensetracker.domain.engine`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `engine/DashboardFollowThroughEngine.kt` | DashboardFollowThroughEngine | Generates dashboard recommendations | Engine | - | No |

### Forecasting & Financial (8 files)

**Location:** `com.yourname.expensetracker.domain.forecasting`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `forecasting/DataQualityAssessor.kt` | DataQualityAssessor | Assesses historical data quality | Engine | - | No |
| `forecasting/FinancialStressForecastEngine.kt` | FinancialStressForecastEngine | Stress tests financial scenarios | Engine | - | No |
| `forecasting/HistoricalSpendingDistribution.kt` | HistoricalSpendingDistribution | Models spending distribution | Model | - | No |
| `forecasting/MonteCarloResult.kt` | MonteCarloResult | Monte Carlo simulation results | Model | - | No |
| `forecasting/MonteCarloSpendingSimulator.kt` | MonteCarloSpendingSimulator | Simulates spending scenarios | Engine | - | No |

### Groups & Shared Expenses (8 files)

**Location:** `com.yourname.expensetracker.domain.groups`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `groups/GroupTransactionCoordinator.kt` | GroupTransactionCoordinator | Coordinates group transactions | Engine | - | No |
| `groups/SettlementCalculator.kt` | SettlementCalculator | Calculates settlement amounts | Engine | - | No |
| `groups/SharedExpenseBudgetOffsetEngine.kt` | SharedExpenseBudgetOffsetEngine | Budget impact for shared expenses | Engine | - | No |
| `groups/SharedExpenseManager.kt` | SharedExpenseManager | Manages shared expenses | Engine | - | No |
| `groups/SharedExpensePort.kt` | SharedExpensePort | Port for shared expenses | Service | - | No |
| `groups/usecase/AddGroupExpenseUseCase.kt` | AddGroupExpenseUseCase | Adds group expenses | UseCase | - | No |
| `groups/usecase/DeleteGroupMemberUseCase.kt` | DeleteGroupMemberUseCase | Deletes group members | UseCase | - | No |
| `groups/usecase/DeleteGroupUseCase.kt` | DeleteGroupUseCase | Deletes groups | UseCase | - | No |

### Health & Income (3 files)

**Location:** `com.yourname.expensetracker.domain.health`, `income`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `health/FinancialHealthCalculator.kt` | FinancialHealthCalculator | Calculates financial health score | Engine | - | No |
| `health/FinancialHealthScoreV2.kt` | FinancialHealthScoreV2 | Health score models v2 | Model | - | No |
| `income/RecurringIncomeTracker.kt` | RecurringIncomeTracker | Tracks recurring income | Engine | - | No |

### Intelligence/ML (5 files)

**Location:** `com.yourname.expensetracker.domain.intelligence`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `intelligence/ConfidenceRouter.kt` | ConfidenceRouter | Routes based on confidence scores | Engine | - | No |
| `intelligence/CrossSourceDeduplication.kt` | CrossSourceDeduplication | Deduplicates cross-source data | Engine | - | No |
| `intelligence/TransactionClassifier.kt` | TransactionClassifier | Classifies transactions | Engine | - | No |
| `intelligence/ml/ExpenseCategoryClassifier.kt` | ExpenseCategoryClassifier | ML-based category classifier | Engine | - | No |
| `intelligence/ml/ExpenseClassifier.kt` | ExpenseClassifier | ML expense classification | Engine | - | No |
| `intelligence/ml/FeatureExtractor.kt` | FeatureExtractor | Extracts ML features | Engine | - | No |
| `intelligence/ml/HybridExpenseClassifier.kt` | HybridExpenseClassifier | Hybrid classification | Engine | - | No |
| `intelligence/ml/MerchantNormalizer.kt` | MerchantNormalizer | Normalizes merchant names | Utility | - | No |

### Investment & Lifestyle (3 files)

**Location:** `com.yourname.expensetracker.domain.investment`, `lifestyle`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `investment/InvestmentTracker.kt` | InvestmentTracker | Tracks investments | Engine | - | No |
| `lifestyle/LifestyleInflationDetector.kt` | LifestyleInflationDetector | Detects lifestyle inflation | Engine | - | No |

### Location Services (11 files)

**Location:** `com.yourname.expensetracker.domain.location`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `location/AreaSpendingEngine.kt` | AreaSpendingEngine | Analyzes spending by area | Engine | - | No |
| `location/GeocodingResult.kt` | GeocodingResult | Geocoding result models | Model | - | No |
| `location/LocatedExpense.kt` | LocatedExpense | Expense with location data | Model | - | No |
| `location/LocationInsightsEngine.kt` | LocationInsightsEngine | Location-based insights | Engine | - | No |
| `location/LocationModels.kt` | LocationModels | Location data models | Model | - | No |
| `location/LocationResolver.kt` | LocationResolver | Resolves merchant locations | Engine | - | No |
| `location/LocationResolverPorts.kt` | LocationResolverPorts | Location resolver ports/interfaces | Service | - | No |
| `location/NearbyPoi.kt` | NearbyPoi | Nearby point of interest | Model | - | No |
| `location/SpendingHeatmapEngine.kt` | SpendingHeatmapEngine | Generates heatmaps | Engine | - | No |
| `location/TravelDetectionEngine.kt` | TravelDetectionEngine | Detects travel patterns | Engine | - | No |

### Logic & Business Rules (7 files)

**Location:** `com.yourname.expensetracker.domain.logic`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `logic/CustomSplitParser.kt` | CustomSplitParser | Parses custom expense splits | Parser | - | No |
| `logic/NarrativeGenerator.kt` | NarrativeGenerator | Generates expense narratives | Engine | - | No |
| `logic/RecurrenceCalculator.kt` | RecurrenceCalculator | Calculates recurrence patterns | Engine | - | No |
| `logic/RecurringExpenseEngine.kt` | RecurringExpenseEngine | Manages recurring expenses | Engine | - | No |
| `logic/SplitCalculator.kt` | SplitCalculator | Calculates expense splits | Engine | - | No |
| `logic/SynthesisEngine.kt` | SynthesisEngine | Synthesizes expense data | Engine | - | No |

### Data Models (24 files)

**Location:** `com.yourname.expensetracker.domain.model`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `model/BlockPartyDay.kt` | BlockPartyDay | Block party day models | Model | - | No |
| `model/CategoryBreakdown.kt` | CategoryBreakdown | Category spending breakdown | Model | - | No |
| `model/CategoryInfo.kt` | CategoryInfo | Category information models | Model | - | No |
| `model/FinancialForecast.kt` | FinancialForecast | Financial forecast models | Model | - | No |
| `model/PeriodDrillDownState.kt` | PeriodDrillDownState | Period drill-down state | Model | - | No |
| `model/PeriodRange.kt` | PeriodRange | Time period range | Model | - | No |
| `model/PeriodTotal.kt` | PeriodTotal | Period total models | Model | - | No |
| `model/PlannedExpense.kt` | PlannedExpense | Planned expense models | Model | - | No |
| `model/RecurringPattern.kt` | RecurringPattern | Recurring transaction pattern | Model | - | No |
| `model/Result.kt` | Result | Generic result wrapper | Model | - | No |
| `model/SavingsGoal.kt` | SavingsGoal | Savings goal models | Model | - | No |
| `model/UiText.kt` | UiText | UI text localization | Model | - | No |
| `model/UpcomingItem.kt` | UpcomingItem | Upcoming transaction models | Model | - | No |
| `model/budget/MonteCarloBudgetImpact.kt` | MonteCarloBudgetImpact | Budget impact models | Model | - | No |
| `model/dashboard/BudgetStatusSnapshot.kt` | BudgetStatusSnapshot | Budget status snapshot | Model | - | No |
| `model/dashboard/DashboardCategoryBreakdown.kt` | DashboardCategoryBreakdown | Dashboard category data | Model | - | No |
| `model/dashboard/DashboardExpenseMapper.kt` | DashboardExpenseMapper | Maps expenses for dashboard | Mapper | - | No |
| `model/dashboard/DashboardPrimitives.kt` | DashboardPrimitives | Dashboard UI primitives | Model | - | No |
| `model/dashboard/DomainBlockStatus.kt` | DomainBlockStatus | Block status models | Model | - | No |
| `model/dashboard/DomainDayBudgetStatus.kt` | DomainDayBudgetStatus | Daily budget status | Model | - | No |
| `model/dashboard/FinancialWeather.kt` | FinancialWeather | Financial weather metaphors | Model | - | No |
| `model/dashboard/SpendingSummary.kt` | SpendingSummary | Spending summary models | Model | - | No |
| `model/navigation/DomainTransactionFilter.kt` | DomainTransactionFilter | Transaction filter models | Model | - | No |
| `model/recommendation/DashboardFollowThroughRecommendation.kt` | DashboardFollowThroughRecommendation | Dashboard recommendations | Model | - | No |
| `model/recommendation/RecommendationPriority.kt` | RecommendationPriority | Recommendation priority enum | Enum | - | No |
| `model/recommendation/RecommendationStatus.kt` | RecommendationStatus | Recommendation status enum | Enum | - | No |

### Natural Language (3 files)

**Location:** `com.yourname.expensetracker.domain.naturallanguage`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `naturallanguage/NaturalLanguageExpenseQueryRepository.kt` | NaturalLanguageExpenseQueryRepository | NL query interface | Repository | - | No |
| `naturallanguage/NaturalLanguageSearchEngine.kt` | NaturalLanguageSearchEngine | NL search logic | Engine | - | No |
| `naturallanguage/SpeechInputGateway.kt` | SpeechInputGateway | Speech input interface | Service | - | No |

### Other Domains (10 files)

**Location:** Various domain subsystems

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `negotiation/SmartBillNegotiationEngine.kt` | SmartBillNegotiationEngine | Bill negotiation logic | Engine | - | No |
| `price/PriceProtectionTracker.kt` | PriceProtectionTracker | Tracks price protection | Engine | - | No |
| `reminder/BillReminderManager.kt` | BillReminderManager | Bill reminder management | Engine | - | No |
| `savings/AutomatedSavingsRuleEngine.kt` | AutomatedSavingsRuleEngine | Automated savings rules | Engine | - | No |
| `savings/SavingsGamificationEngine.kt` | SavingsGamificationEngine | Gamification for savings | Engine | - | No |
| `savings/SavingsGoalRepository.kt` | SavingsGoalRepository | Savings goal interface | Repository | - | No |
| `savings/SmartSavingsEngine.kt` | SmartSavingsEngine | Smart savings logic | Engine | - | No |
| `service/NotificationService.kt` | NotificationService | Notification interface | Service | - | No |
| `split/EnhancedSplitManager.kt` | EnhancedSplitManager | Enhanced split management | Engine | - | No |
| `subscription/NotificationSubscriptionDetector.kt` | NotificationSubscriptionDetector | Detects subscriptions | Engine | - | No |
| `subscription/SubscriptionManagerEngine.kt` | SubscriptionManagerEngine | Subscription management | Engine | - | No |

### Parsing & Receipt (13 files)

**Location:** `com.yourname.expensetracker.domain.parser`, `receipt`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `parser/AppParserRegistry.kt` | AppParserRegistry | Registry of parsers | Registry | - | No |
| `parser/GenericTransactionParser.kt` | GenericTransactionParser | Generic parsing logic | Parser | - | No |
| `parser/ParsedTransactionEnums.kt` | ParsedTransactionEnums | Parsed transaction enums | Enum | - | No |
| `parser/TransferDirectionDetector.kt` | TransferDirectionDetector | Detects transfer direction | Engine | - | No |
| `parser/parsers/GoogleWalletParser.kt` | GoogleWalletParser | Google Wallet parser | Parser | - | No |
| `parser/parsers/GreekBankParser.kt` | GreekBankParser | Greek bank parser | Parser | - | No |
| `parser/parsers/RevolutParser.kt` | RevolutParser | Revolut parser | Parser | - | No |
| `parser/parsers/SmsParser.kt` | SmsParser | SMS parser | Parser | - | No |
| `receipt/BankStatementParser.kt` | BankStatementParser | Bank statement parser | Parser | - | No |
| `receipt/EnhancedMerchantExtractor.kt` | EnhancedMerchantExtractor | Merchant extraction | Engine | - | No |
| `receipt/MerchantRulesPolicy.kt` | MerchantRulesPolicy | Merchant business rules | Service | - | No |
| `receipt/OcrLanguageProcessor.kt` | OcrLanguageProcessor | OCR language processing | Engine | - | No |
| `receipt/OcrPreprocessingPipeline.kt` | OcrPreprocessingPipeline | OCR preprocessing | Engine | - | No |
| `receipt/ReceiptOcrService.kt` | ReceiptOcrService | OCR interface | Service | - | No |
| `receipt/ReceiptParser.kt` | ReceiptParser | Receipt parsing interface | Service | - | No |
| `receipt/ReceiptSource.kt` | ReceiptSource | Receipt source enum | Enum | - | No |
| `receipt/WarrantyTextExtractor.kt` | WarrantyTextExtractor | Extracts warranty text | Engine | - | No |
| `receiptmatching/ReceiptTransactionMatcher.kt` | ReceiptTransactionMatcher | Matches receipts to transactions | Engine | - | No |

### Tax (2 files)

**Location:** `com.yourname.expensetracker.domain.tax`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `tax/TaxConfiguration.kt` | TaxConfiguration | Tax configuration | Config | - | No |
| `tax/TaxEstimator.kt` | TaxEstimator | Tax estimation logic | Engine | - | No |

### Text & UI Keys (2 files)

**Location:** `com.yourname.expensetracker.domain.text`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `text/DashboardTextKeys.kt` | DashboardTextKeys | Dashboard text keys | Config | - | No |
| `text/DomainTextKeys.kt` | DomainTextKeys | Domain text keys | Config | - | No |

### Use Cases (13 files)

**Location:** `com.yourname.expensetracker.domain.usecase`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/budget/CalculateBudgetStatusUseCase.kt` | CalculateBudgetStatusUseCase | Calculates budget status | UseCase | BudgetCalculator | No |
| `usecase/budget/GetMonteCarloBudgetImpactUseCase.kt` | GetMonteCarloBudgetImpactUseCase | Gets budget impact forecast | UseCase | - | No |
| `usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | ComputeDashboardWidgetsUseCase | Computes dashboard widgets | UseCase | - | No |
| `usecase/dashboard/ComputeMoneyRadarUseCase.kt` | ComputeMoneyRadarUseCase | Computes money radar | UseCase | - | No |
| `usecase/dashboard/DashboardDataProvider.kt` | DashboardDataProvider | Dashboard data provider | Service | - | No |
| `usecase/dashboard/DashboardRepositoryContracts.kt` | DashboardRepositoryContracts | Dashboard repo contracts | Service | - | No |
| `usecase/expense/CategorizeExpenseUseCase.kt` | CategorizeExpenseUseCase | Categorizes expenses | UseCase | CategorizationEngine | No |
| `usecase/expense/DetectDuplicateExpenseUseCase.kt` | DetectDuplicateExpenseUseCase | Detects duplicate expenses | UseCase | - | No |
| `usecase/expense/ExpenseUseCases.kt` | ExpenseUseCases | Expense use cases facade | Service | - | No |
| `usecase/forecast/CalculateFinancialForecastUseCase.kt` | CalculateFinancialForecastUseCase | Calculates financial forecast | UseCase | - | No |
| `usecase/receipt/ProcessReceiptUseCase.kt` | ProcessReceiptUseCase | Processes receipts | UseCase | - | No |
| `usecase/savings/LifestyleSavingsPromptUseCase.kt` | LifestyleSavingsPromptUseCase | Lifestyle savings prompt | UseCase | - | No |
| `usecase/savings/MonthlySavingsSweepUseCase.kt` | MonthlySavingsSweepUseCase | Monthly savings sweep | UseCase | - | No |
| `usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` | AutoCreateWarrantyFromReceiptUseCase | Creates warranty from receipt | UseCase | - | No |

### Utilities (24 files)

**Location:** `com.yourname.expensetracker.domain.util`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `util/AmountExtractionUtils.kt` | AmountExtractionUtils | Extracts amounts from text | Utility | - | No |
| `util/AmountUtils.kt` | AmountUtils | Amount utility functions | Utility | - | No |
| `util/AppConstants.kt` | AppConstants | Application constants | Config | - | No |
| `util/BKTree.kt` | BKTree | BK-tree data structure | DataStructure | - | No |
| `util/CommonPatterns.kt` | CommonPatterns | Common regex patterns | Utility | - | No |
| `util/CurrencyFormatter.kt` | CurrencyFormatter | Currency formatting | Utility | - | No |
| `util/CurrencyNormalizer.kt` | CurrencyNormalizer | Currency normalization | Utility | - | No |
| `util/DateFormatterUtils.kt` | DateFormatterUtils | Date formatting | Utility | - | No |
| `util/GeoUtils.kt` | GeoUtils | Geolocation utilities | Utility | - | No |
| `util/MerchantCleaner.kt` | MerchantCleaner | Cleans merchant names | Utility | - | No |
| `util/MerchantKeyGenerator.kt` | MerchantKeyGenerator | Generates merchant keys | Utility | - | No |
| `util/Money.kt` | Money | Money value object | Model | - | No |
| `util/NotificationIdGenerator.kt` | NotificationIdGenerator | Generates notification IDs | Utility | - | No |
| `util/StatisticsUtils.kt` | StatisticsUtils | Statistical functions | Utility | - | No |
| `util/StringDistanceUtils.kt` | StringDistanceUtils | String distance algorithms | Utility | - | No |
| `util/SystemTimeProvider.kt` | SystemTimeProvider | Production clock implementation | Service | TimeProvider | No |
| `util/TimePeriodUtils.kt` | TimePeriodUtils | **Canonical calendar boundary math** — 7 new helpers in Phase 2: parseMonthKeyToRange, getLastNCalendarDaysRange, getLastNCompleteDaysRange, getTrailingElapsedRange, getDayIndexForSparkline, toPeriodRange, daysBetween; getLastNDaysRange deprecated. All functions are pure (no clock calls). | Utility | - | No |
| `util/TimeProvider.kt` | TimeProvider | **Single source of "now"** — interface, injected into 50+ classes across domain/data/UI. Production impl: SystemTimeProvider. Test impl: FakeTimeProvider. | Service | - | No |

### Widget (2 files)

**Location:** `com.yourname.expensetracker.domain.widget`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `widget/model/WidgetStyle.kt` | WidgetStyle | Widget style models | Model | - | No |
| `widget/service/WidgetStyleRepository.kt` | WidgetStyleRepository | Widget style interface | Repository | - | No |

---

## DATA PACKAGE

### Database Layer (89 files)

**Location:** `com.yourname.expensetracker.data.database`

#### Main Database & Coordinator (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `database/AppDatabase.kt` | AppDatabase | Room database definition | Database | All entities, DAOs | No |
| `database/GroupTransactionCoordinator.kt` | GroupTransactionCoordinator | Coordinates group transactions | Engine | GroupExpenseDao, GroupMemberDao | No |

#### Type Converters (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `database/converter/Converters.kt` | Converters | Room type converters | Converter | - | No |

#### DAOs (55 files)

**Location:** `com.yourname.expensetracker.data.database.dao`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `dao/AiArtifactDao.kt` | AiArtifactDao | AI artifacts DAO | DAO | - | No |
| `dao/AiChatMessageDao.kt` | AiChatMessageDao | Chat messages DAO | DAO | - | No |
| `dao/AiChatSessionDao.kt` | AiChatSessionDao | Chat sessions DAO | DAO | - | No |
| `dao/AnomalyAlertDao.kt` | AnomalyAlertDao | Anomaly alerts DAO | DAO | - | No |
| `dao/BankConnectionDao.kt` | BankConnectionDao | Bank connections DAO | DAO | - | No |
| `dao/BlockedPackageDao.kt` | BlockedPackageDao | Blocked packages DAO | DAO | - | No |
| `dao/BudgetAdjustmentDao.kt` | BudgetAdjustmentDao | Budget adjustments DAO | DAO | - | No |
| `dao/BudgetDao.kt` | BudgetDao | Budgets DAO | DAO | - | No |
| `dao/BudgetForecastDao.kt` | BudgetForecastDao | Budget forecasts DAO | DAO | - | No |
| `dao/CategoryDao.kt` | CategoryDao | Categories DAO | DAO | - | No |
| `dao/EmailReceiptDao.kt` | EmailReceiptDao | Email receipts DAO | DAO | - | No |
| `dao/ExchangeRateDao.kt` | ExchangeRateDao | Exchange rates DAO | DAO | - | No |
| `dao/ExpenseDao.kt` | ExpenseDao | Expenses DAO | DAO | - | Yes |
| `dao/ExpenseGroupDao.kt` | ExpenseGroupDao | Expense groups DAO | DAO | - | No |
| `dao/GroupExpenseDao.kt` | GroupExpenseDao | Group expenses DAO | DAO | - | No |
| `dao/GroupMemberDao.kt` | GroupMemberDao | Group members DAO | DAO | - | No |
| `dao/HealthScoreHistoryDao.kt` | HealthScoreHistoryDao | Health score history DAO | DAO | - | No |
| `dao/InvestmentDao.kt` | InvestmentDao | Investments DAO | DAO | - | No |
| `dao/InvestmentValueDao.kt` | InvestmentValueDao | Investment values DAO | DAO | - | No |
| `dao/ManualRecurringExpenseDao.kt` | ManualRecurringExpenseDao | Manual recurring DAO | DAO | - | No |
| `dao/MerchantCategoryDao.kt` | MerchantCategoryDao | Merchant categories DAO | DAO | - | No |
| `dao/MerchantLocationDao.kt` | MerchantLocationDao | Merchant locations DAO | DAO | - | No |
| `dao/MerchantNormalizationDao.kt` | MerchantNormalizationDao | Merchant normalization DAO | DAO | - | No |
| `dao/MileageTrackingDao.kt` | MileageTrackingDao | Mileage tracking DAO | DAO | - | No |
| `dao/PendingReviewDao.kt` | PendingReviewDao | Pending review DAO | DAO | - | No |
| `dao/PlannedExpenseDao.kt` | PlannedExpenseDao | Planned expenses DAO | DAO | - | No |
| `dao/PromptStateDao.kt` | PromptStateDao | Prompt state DAO | DAO | - | No |
| `dao/RawNotificationDao.kt` | RawNotificationDao | Raw notifications DAO | DAO | - | No |
| `dao/ReceiptItemCategorizationDao.kt` | ReceiptItemCategorizationDao | Receipt items DAO | DAO | - | No |
| `dao/RecommendationDao.kt` | RecommendationDao | Recommendations DAO | DAO | - | No |
| `dao/RecurringExpenseDao.kt` | RecurringExpenseDao | Recurring expenses DAO | DAO | - | No |
| `dao/ReturnWindowDao.kt` | ReturnWindowDao | Return windows DAO | DAO | - | No |
| `dao/SavingsGoalDao.kt` | SavingsGoalDao | Savings goals DAO | DAO | - | No |
| `dao/SavingsSweepPlanDao.kt` | SavingsSweepPlanDao | Savings sweep plans DAO | DAO | - | No |
| `dao/ScannedReceiptDao.kt` | ScannedReceiptDao | Scanned receipts DAO | DAO | - | No |
| `dao/SourceStatsDao.kt` | SourceStatsDao | Source statistics DAO | DAO | - | No |
| `dao/SpendingPersonalityProfileDao.kt` | SpendingPersonalityProfileDao | Spending personality DAO | DAO | - | No |
| `dao/SplitItemAssignmentDao.kt` | SplitItemAssignmentDao | Split assignments DAO | DAO | - | No |
| `dao/SplitTemplateDao.kt` | SplitTemplateDao | Split templates DAO | DAO | - | No |
| `dao/StressForecastSnapshotDao.kt` | StressForecastSnapshotDao | Stress forecast DAO | DAO | - | No |
| `dao/SubscriptionCandidateDao.kt` | SubscriptionCandidateDao | Subscription candidates DAO | DAO | - | No |
| `dao/SubscriptionPriceHistoryDao.kt` | SubscriptionPriceHistoryDao | Subscription price history DAO | DAO | - | No |
| `dao/SubscriptionUsageDao.kt` | SubscriptionUsageDao | Subscription usage DAO | DAO | - | No |
| `dao/ReceiptEventDao.kt` | ReceiptEventDao | Receipt lifecycle events DAO (`receipt_events`) | DAO | - | No |
| `dao/ReceiptExpenseLinkDao.kt` | ReceiptExpenseLinkDao | Receipt-expense link DAO (`receipt_expense_links`) | DAO | - | No |
| `dao/RecurringOccurrenceDao.kt` | RecurringOccurrenceDao | Recurring occurrences DAO (`recurring_occurrences`) — insert (IGNORE), insertAll, update, getByKey, getBySource, getByDateRange, getByStatus, updateStatus | DAO | - | No |
| `dao/RecurringReminderDeliveryDao.kt` | RecurringReminderDeliveryDao | Recurring reminder deliveries DAO (`recurring_reminder_deliveries`) — insert, insertAll, update, getByOccurrenceAndWindow, getPendingDeliveries | DAO | - | No |
| `dao/TransactionEventDao.kt` | TransactionEventDao | Transaction lifecycle events DAO | DAO | - | No |
| `dao/UserCorrectionDao.kt` | UserCorrectionDao | User corrections DAO | DAO | - | No |
| `dao/WarrantyDao.kt` | WarrantyDao | Warranties DAO | DAO | - | No |

#### Entities (56 files)

**Location:** `com.yourname.expensetracker.data.database.entity`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `entity/AiArtifactEntity.kt` | AiArtifactEntity | AI artifact entity | Entity | - | No |
| `entity/AiChatMessageEntity.kt` | AiChatMessageEntity | Chat message entity | Entity | - | No |
| `entity/AiChatSessionEntity.kt` | AiChatSessionEntity | Chat session entity | Entity | - | No |
| `entity/AnomalyAlert.kt` | AnomalyAlert | Anomaly alert entity | Entity | - | No |
| `entity/BankConnection.kt` | BankConnection | Bank connection entity | Entity | - | No |
| `entity/BlockedPackage.kt` | BlockedPackage | Blocked package entity | Entity | - | No |
| `entity/Budget.kt` | Budget | Budget entity | Entity | - | No |
| `entity/BudgetAdjustmentRecommendation.kt` | BudgetAdjustmentRecommendation | Budget recommendation entity | Entity | - | No |
| `entity/BudgetForecast.kt` | BudgetForecast | Budget forecast entity | Entity | - | No |
| `entity/Category.kt` | Category | Category entity | Entity | - | No |
| `entity/EmailReceiptSource.kt` | EmailReceiptSource | Email receipt entity | Entity | - | No |
| `entity/ExchangeRate.kt` | ExchangeRate | Exchange rate entity | Entity | - | No |
| `entity/Expense.kt` | Expense | Core expense entity | Entity | - | No |
| `entity/ExpenseGroup.kt` | ExpenseGroup | Expense group entity | Entity | - | No |
| `entity/GroupExpense.kt` | GroupExpense | Group expense entity | Entity | - | No |
| `entity/GroupMember.kt` | GroupMember | Group member entity | Entity | - | No |
| `entity/HealthScoreHistory.kt` | HealthScoreHistory | Health score history entity | Entity | - | No |
| `entity/Investment.kt` | Investment | Investment entity | Entity | - | No |
| `entity/InvestmentValue.kt` | InvestmentValue | Investment value entity | Entity | - | No |
| `entity/ManualRecurringExpense.kt` | ManualRecurringExpense | Manual recurring expense entity | Entity | - | No |
| `entity/MerchantAlias.kt` | MerchantAlias | Merchant alias entity | Entity | - | No |
| `entity/MerchantCanonical.kt` | MerchantCanonical | Canonical merchant entity | Entity | - | No |
| `entity/MerchantCategory.kt` | MerchantCategory | Merchant category entity | Entity | - | No |
| `entity/MerchantLocation.kt` | MerchantLocation | Merchant location entity | Entity | - | No |
| `entity/MerchantLocationCorrection.kt` | MerchantLocationCorrection | Location correction entity | Entity | - | No |
| `entity/MileageTracking.kt` | MileageTracking | Mileage tracking entity | Entity | - | No |
| `entity/PendingReview.kt` | PendingReview | Pending review entity | Entity | - | No |
| `entity/PlannedExpense.kt` | PlannedExpense | Planned expense entity | Entity | - | No |
| `entity/PromptState.kt` | PromptState | Prompt state entity | Entity | - | No |
| `entity/RawNotification.kt` | RawNotification | Raw notification entity | Entity | - | No |
| `entity/ReceiptItemCategorization.kt` | ReceiptItemCategorization | Receipt item entity | Entity | - | No |
| `entity/RecommendationEntity.kt` | RecommendationEntity | Recommendation entity | Entity | - | No |
| `entity/ReturnWindow.kt` | ReturnWindow | Return window entity | Entity | - | No |
| `entity/SavingsGoal.kt` | SavingsGoal | Savings goal entity | Entity | - | No |
| `entity/SavingsSweepPlan.kt` | SavingsSweepPlan | Savings sweep plan entity | Entity | - | No |
| `entity/ReceiptEvent.kt` | ReceiptEvent | Immutable receipt lifecycle event log (table: `receipt_events`); records every CAPTURED/OCR_FAILED/PARSED/EXPENSE_CREATED/DELETED transition with actor, status transitions, timestamps | Entity | - | No |
| `entity/ReceiptExpenseLink.kt` | ReceiptExpenseLink | Many-to-many receipt↔expense join (table: `receipt_expense_links`); supports single and multi-link relationships with confidence, source, and metadata | Entity | - | No |
| `entity/ScannedReceipt.kt` | ScannedReceipt | Scanned receipt entity — 10 new Phase 4 columns: sourceType, documentType, processingStatus, sourceFingerprint, imageHash, textFingerprint, semanticFingerprint, ocrConfidence, parseFailureReason, updatedAt | Entity | - | No |
| `entity/RecurringOccurrence.kt` | RecurringOccurrence | Recurring occurrence entity (table: `recurring_occurrences`) — stores expanded occurrence candidates with status tracking (PLANNED/PAID/SKIPPED/MISSED/CANCELLED). Unique constraint on occurrenceKey for idempotent insert. | Entity | - | No |
| `entity/RecurringReminderDelivery.kt` | RecurringReminderDelivery | Reminder delivery entity (table: `recurring_reminder_deliveries`) — scheduled reminders per occurrence and window (DUE_DAY, N_DAYS_BEFORE, OVERDUE). Status: SCHEDULED/SENT/DISMISSED/SNOOZED/FAILED. | Entity | - | No |
| `entity/SourceStats.kt` | SourceStats | Source statistics entity | Entity | - | No |
| `entity/SpendingPersonalityProfileEntity.kt` | SpendingPersonalityProfileEntity | Spending profile entity | Entity | - | No |
| `entity/TransactionEvent.kt` | TransactionEvent | Immutable lifecycle event log (table: `transaction_events`); records every CREATED/UPDATED/DELETED/etc. transition with actor, timestamps, and before/after snapshots | Entity | - | No |
| `entity/SplitItemAssignment.kt` | SplitItemAssignment | Split item assignment entity | Entity | - | No |
| `entity/SplitTemplate.kt` | SplitTemplate | Split template entity | Entity | - | No |
| `entity/StressForecastSnapshot.kt` | StressForecastSnapshot | Stress forecast entity | Entity | - | No |
| `entity/SubscriptionCandidate.kt` | SubscriptionCandidate | Subscription candidate entity | Entity | - | No |
| `entity/SubscriptionPriceHistory.kt` | SubscriptionPriceHistory | Subscription price entity | Entity | - | No |
| `entity/SubscriptionUsage.kt` | SubscriptionUsage | Subscription usage entity | Entity | - | No |
| `entity/UserCorrection.kt` | UserCorrection | User correction entity | Entity | - | No |
| `entity/Warranty.kt` | Warranty | Warranty entity | Entity | - | No |

#### Database Models (6 files)

**Location:** `com.yourname.expensetracker.data.database.model`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `model/DashboardWidgetConfig.kt` | DashboardWidgetConfig | Dashboard widget config | Model | - | No |
| `model/ExpenseGroupWithDetails.kt` | ExpenseGroupWithDetails | Expense group with details | Model | - | No |
| `model/ExpenseWithCategory.kt` | ExpenseWithCategory | Expense with category | Model | - | No |
| `model/ExpenseWithCategoryName.kt` | ExpenseWithCategoryName | Expense with category name | Model | - | No |
| `model/ExpenseWithCategory_Extensions.kt` | ExpenseWithCategory_Extensions | Extension functions | Utility | - | No |
| `model/PendingReviewWithReceipt.kt` | PendingReviewWithReceipt | Review with receipt | Model | - | No |

### Repositories (56 files)

**Location:** `com.yourname.expensetracker.data.repository`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `repository/AccountingExportRepository.kt` | AccountingExportRepository | Export to accounting systems | Repository | - | No |
| `repository/AiArtifactRepositoryImpl.kt` | AiArtifactRepositoryImpl | AI artifacts implementation | Repository | AiArtifactDao | No |
| `repository/AiChatRepositoryImpl.kt` | AiChatRepositoryImpl | Chat history implementation | Repository | AiChatSessionDao, AiChatMessageDao | No |
| `repository/AiEngagementRepositoryImpl.kt` | AiEngagementRepositoryImpl | Engagement tracking | Repository | - | No |
| `repository/AiSettingsRepositoryImpl.kt` | AiSettingsRepositoryImpl | AI settings implementation | Repository | - | No |
| `repository/AnalyticsRepository.kt` | AnalyticsRepository | Analytics data access | Repository | ExpenseDao | No |
| `repository/BudgetRepository.kt` | BudgetRepository | Budget data access | Repository | BudgetDao, BudgetForecastDao | No |
| `repository/BusinessExpenseRepository.kt` | BusinessExpenseRepository | Business expenses | Repository | ExpenseDao | No |
| `repository/CategoryRepository.kt` | CategoryRepository | Category data access | Repository | CategoryDao | No |
| `repository/CurrencyDataRepository.kt` | CurrencyDataRepository | Currency data access | Repository | - | No |
| `repository/CurrencyRatesRepositoryImpl.kt` | CurrencyRatesRepositoryImpl | Exchange rates implementation | Repository | ExchangeRateDao | No |
| `repository/CurrencySettingsRepositoryImpl.kt` | CurrencySettingsRepositoryImpl | Currency settings implementation | Repository | - | No |
| `repository/DashboardContractsAdapter.kt` | DashboardContractsAdapter | Adapts dashboard contracts | Repository | - | No |
| `repository/DashboardRepository.kt` | DashboardRepository | Dashboard data access | Repository | Multiple DAOs | No |
| `repository/DatabaseBackupRepositoryImpl.kt` | DatabaseBackupRepositoryImpl | Backup implementation | Repository | AppDatabase | No |
| `repository/ExpenseRepository.kt` | ExpenseRepository | Core expense repository | Repository | ExpenseDao, UserCorrectionDao | No |
| `repository/ExportDataRepository.kt` | ExportDataRepository | Export data access | Repository | Multiple DAOs | No |
| `repository/FinancialWeatherRepository.kt` | FinancialWeatherRepository | Financial weather data | Repository | - | No |
| `repository/GroupsRepository.kt` | GroupsRepository | Groups interface | Repository | - | No |
| `repository/GroupsRepositoryImpl.kt` | GroupsRepositoryImpl | Groups implementation | Repository | GroupExpenseDao, GroupMemberDao | No |
| `repository/LocationResolverPortsAdapters.kt` | LocationResolverPortsAdapters | Location adapters | Repository | - | No |
| `repository/ManualExpenseRepository.kt` | ManualExpenseRepository | Manual expense entry | Repository | ExpenseDao | No |
| `repository/ManualRecurringExpenseRepository.kt` | ManualRecurringExpenseRepository | Recurring expense data | Repository | ManualRecurringExpenseDao | No |
| `repository/MerchantCategoryRepository.kt` | MerchantCategoryRepository | Merchant categories | Repository | MerchantCategoryDao | No |
| `repository/MerchantLocationRepository.kt` | MerchantLocationRepository | Merchant locations | Repository | MerchantLocationDao | No |
| `repository/MerchantNormalizationRepository.kt` | MerchantNormalizationRepository | Merchant normalization | Repository | MerchantNormalizationDao | No |
| `repository/MerchantRulesRepository.kt` | MerchantRulesRepository | Merchant business rules | Repository | - | No |
| `repository/MultiCurrencyRepository.kt` | MultiCurrencyRepository | Multi-currency support | Repository | Multiple DAOs | No |
| `repository/NaturalLanguageExpenseQueryRepositoryImpl.kt` | NaturalLanguageExpenseQueryRepositoryImpl | NL query implementation | Repository | ExpenseDao | No |
| `repository/NotificationProcessingPipeline.kt` | NotificationProcessingPipeline | Notification pipeline | Service | RawNotificationDao | No |
| `repository/NotificationRepository.kt` | NotificationRepository | Notification data access | Repository | RawNotificationDao | No |
| `repository/ParserEnumMappers.kt` | ParserEnumMappers | Parser enum mappings | Utility | - | No |
| `repository/PlannedExpenseRepository.kt` | PlannedExpenseRepository | Planned expenses | Repository | PlannedExpenseDao | No |
| `repository/PromptStateRepository.kt` | PromptStateRepository | Prompt state persistence | Repository | PromptStateDao | No |
| `repository/ReceiptItemCategorizationRepository.kt` | ReceiptItemCategorizationRepository | Receipt items | Repository | ReceiptItemCategorizationDao | No |
| `repository/ReceiptRepository.kt` | ReceiptRepository | Receipt data access | Repository | ScannedReceiptDao, EmailReceiptDao | No |
| `repository/RecommendationRepository.kt` | RecommendationRepository | Recommendations | Repository | RecommendationDao | No |
| `repository/RecurringExpenseRepository.kt` | RecurringExpenseRepository | Recurring expenses | Repository | RecurringExpenseDao | No |
| `repository/ReviewQueueRepository.kt` | ReviewQueueRepository | Pending review queue | Repository | PendingReviewDao | No |
| `repository/SavingsGoalRepository.kt` | SavingsGoalRepository | Savings goals | Repository | SavingsGoalDao | No |
| `repository/SharedExpenseDataPortAdapter.kt` | SharedExpenseDataPortAdapter | Shared expense adapter | Repository | GroupExpenseDao | No |
| `repository/SourceStatsRepository.kt` | SourceStatsRepository | Source statistics | Repository | SourceStatsDao | No |
| `repository/SubscriptionManagementRepository.kt` | SubscriptionManagementRepository | Subscription data | Repository | SubscriptionCandidateDao | No |
| `repository/UserCorrectionRepository.kt` | UserCorrectionRepository | User corrections | Repository | UserCorrectionDao | No |
| `repository/WarrantyTrackerRepository.kt` | WarrantyTrackerRepository | Warranty data | Repository | WarrantyDao | No |
| `repository/WidgetStyleRepositoryImpl.kt` | WidgetStyleRepositoryImpl | Widget styles | Repository | - | No |

### AI Providers (38 files)

**Location:** `com.yourname.expensetracker.data.ai`

#### Provider Implementations (22 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/provider/CloudCategorizationAssistService.kt` | CloudCategorizationAssistService | Cloud-based categorization | Service | - | Yes |
| `ai/provider/CloudDashboardBriefingService.kt` | CloudDashboardBriefingService | Cloud-based briefing | Service | - | No |
| `ai/provider/CloudDedupeJudgeService.kt` | CloudDedupeJudgeService | Cloud-based dedup | Service | - | Yes |
| `ai/provider/CloudQueryInterpretationService.kt` | CloudQueryInterpretationService | Cloud-based query | Service | - | Yes |
| `ai/provider/CloudReceiptAssistService.kt` | CloudReceiptAssistService | Cloud receipt extraction | Service | - | Yes |
| `ai/provider/CloudReceiptItemCategorizationService.kt` | CloudReceiptItemCategorizationService | Cloud item categorization | Service | - | No |
| `ai/provider/CloudReviewExplanationService.kt` | CloudReviewExplanationService | Cloud review explanation | Service | - | No |
| `ai/provider/CloudWarrantyExtractionService.kt` | CloudWarrantyExtractionService | Cloud warranty extraction | Service | - | No |
| `ai/provider/DefaultAiEnvironmentMonitor.kt` | DefaultAiEnvironmentMonitor | Environment monitoring | Service | - | No |
| `ai/provider/HybridCategorizationAssistService.kt` | HybridCategorizationAssistService | Hybrid categorization | Service | - | No |
| `ai/provider/HybridDashboardBriefingService.kt` | HybridDashboardBriefingService | Hybrid briefing | Service | - | No |
| `ai/provider/HybridDedupeJudgeService.kt` | HybridDedupeJudgeService | Hybrid dedup | Service | - | No |
| `ai/provider/HybridQueryInterpretationService.kt` | HybridQueryInterpretationService | Hybrid query | Service | - | No |
| `ai/provider/HybridReceiptAssistService.kt` | HybridReceiptAssistService | Hybrid receipt | Service | - | No |
| `ai/provider/HybridReceiptItemCategorizationService.kt` | HybridReceiptItemCategorizationService | Hybrid item cat | Service | - | No |
| `ai/provider/HybridReviewExplanationService.kt` | HybridReviewExplanationService | Hybrid review | Service | - | No |
| `ai/provider/NoOpCategorizationAssistService.kt` | NoOpCategorizationAssistService | No-op categorization | Service | - | No |
| `ai/provider/NoOpDashboardBriefingService.kt` | NoOpDashboardBriefingService | No-op briefing | Service | - | No |
| `ai/provider/NoOpDedupeJudgeService.kt` | NoOpDedupeJudgeService | No-op dedup | Service | - | No |
| `ai/provider/NoOpQueryInterpretationService.kt` | NoOpQueryInterpretationService | No-op query | Service | - | No |
| `ai/provider/NoOpReceiptAssistService.kt` | NoOpReceiptAssistService | No-op receipt | Service | - | No |
| `ai/provider/NoOpReviewExplanationService.kt` | NoOpReviewExplanationService | No-op review | Service | - | No |
| `ai/provider/OnDeviceCategorizationAssistService.kt` | OnDeviceCategorizationAssistService | On-device categorization | Service | - | No |
| `ai/provider/OnDeviceDashboardBriefingService.kt` | OnDeviceDashboardBriefingService | On-device briefing | Service | - | No |
| `ai/provider/OnDeviceDedupeJudgeService.kt` | OnDeviceDedupeJudgeService | On-device dedup | Service | - | No |
| `ai/provider/OnDeviceNotificationParser.kt` | OnDeviceNotificationParser | On-device parser | Service | - | No |
| `ai/provider/OnDeviceQueryInterpretationService.kt` | OnDeviceQueryInterpretationService | On-device query | Service | - | No |
| `ai/provider/OnDeviceReceiptAssistService.kt` | OnDeviceReceiptAssistService | On-device receipt | Service | - | No |
| `ai/provider/OnDeviceReceiptItemCategorizationService.kt` | OnDeviceReceiptItemCategorizationService | On-device item cat | Service | - | No |
| `ai/provider/OnDeviceReviewExplanationService.kt` | OnDeviceReviewExplanationService | On-device review | Service | - | No |
| `ai/provider/OnDeviceReviewPriorityScorer.kt` | OnDeviceReviewPriorityScorer | On-device scorer | Service | - | No |
| `ai/provider/OnDeviceSemanticDuplicateDetector.kt` | OnDeviceSemanticDuplicateDetector | On-device duplicate | Service | - | No |
| `ai/provider/SmartReceiptAssistService.kt` | SmartReceiptAssistService | Smart receipt assist | Service | - | No |

#### Provider Internals (4 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/provider/internal/CloudCorrelation.kt` | CloudCorrelation | Cloud request correlation | Utility | - | No |
| `ai/provider/internal/CloudJsonParser.kt` | CloudJsonParser | Cloud JSON parsing | Parser | - | No |
| `ai/provider/internal/CloudPiiSanitizer.kt` | CloudPiiSanitizer | PII sanitization | Security | - | No |
| `ai/provider/internal/CloudRetryPolicy.kt` | CloudRetryPolicy | Retry policy | Utility | - | No |

#### AI Worker (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/worker/AiWorkSchedulerImpl.kt` | AiWorkSchedulerImpl | Work scheduler implementation | Service | - | No |
| `ai/worker/DailyBriefingWorker.kt` | DailyBriefingWorker | Daily briefing worker | Worker | DashboardBriefingService | No |

### Other Data Layer Services (23 files)

**Location:** Various data subsystems

#### Currency (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `currency/ExchangeRateStoreAdapter.kt` | ExchangeRateStoreAdapter | Exchange rate adapter | Repository | ExchangeRateDao | No |

#### Email Ingestion (5 files)

**Location:** `com.yourname.expensetracker.data.email`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `email/EmailReceiptIngestionService.kt` | EmailReceiptIngestionService | Email receipt ingestion | Service | - | No |
| `email/provider/AmazonReceiptParser.kt` | AmazonReceiptParser | Amazon receipt parser | Parser | - | No |
| `email/provider/AppleReceiptParser.kt` | AppleReceiptParser | Apple receipt parser | Parser | - | No |
| `email/provider/EmailReceiptParser.kt` | EmailReceiptParser | Email receipt parser interface | Service | - | No |
| `email/provider/UberReceiptParser.kt` | UberReceiptParser | Uber receipt parser | Parser | - | No |

#### Location Services (9 files)

**Location:** `com.yourname.expensetracker.data.location`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `location/AndroidForegroundLocationProvider.kt` | AndroidForegroundLocationProvider | Android location provider | Service | - | No |
| `location/CompositeGeocodingService.kt` | CompositeGeocodingService | Composite geocoding | Service | Multiple geocoding services | No |
| `location/GeoapifyGeocodingService.kt` | GeoapifyGeocodingService | Geoapify geocoding | Service | - | No |
| `location/GooglePlacesGeocodingService.kt` | GooglePlacesGeocodingService | Google Places geocoding | Service | - | No |
| `location/LocationBackfillWorker.kt` | LocationBackfillWorker | Location backfill worker | Worker | MerchantLocationDao | No |
| `location/MerchantKeyBackfillWorker.kt` | MerchantKeyBackfillWorker | Merchant key backfill | Worker | ExpenseDao | No |
| `location/NominatimGeocodingService.kt` | NominatimGeocodingService | Nominatim geocoding | Service | - | No |
| `location/OverpassNearbyService.kt` | OverpassNearbyService | Overpass nearby POI | Service | - | No |
| `location/PhotonGeocodingService.kt` | PhotonGeocodingService | Photon geocoding | Service | - | No |
| `location/internal/LogSanitizer.kt` | LogSanitizer | Log sanitization | Security | - | No |

#### Provider (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `provider/MerchantCategoryProvider.kt` | MerchantCategoryProvider | Merchant category provider | Provider | - | No |

#### Security (2 files)

**Location:** `com.yourname.expensetracker.data.security`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `security/BankTokenCipher.kt` | BankTokenCipher | Bank token encryption | Security | - | No |
| `security/SecureKeyStorage.kt` | SecureKeyStorage | Secure key storage | Security | - | No |

#### Services (2 files)

**Location:** `com.yourname.expensetracker.data.service`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `service/AndroidNotificationService.kt` | AndroidNotificationService | Android notifications | Service | - | No |

#### Speech (1 file)

**Location:** `com.yourname.expensetracker.data.speech`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `speech/AndroidSpeechInputGateway.kt` | AndroidSpeechInputGateway | Android speech input | Service | - | No |

---

## DI/MODULES PACKAGE

**Location:** `com.yourname.expensetracker.di`

| File | Class | Purpose | Type | Provides | Tests |
|------|-------|---------|------|----------|-------|
| `AiModule.kt` | AiModule | AI service binding | Module | All AI services | No |
| `ApplicationScope.kt` | ApplicationScope | App scope annotation | Annotation | - | No |
| `BackupRepositoryModule.kt` | BackupRepositoryModule | Backup binding | Module | DatabaseBackupRepository | No |
| `CashFlowModule.kt` | CashFlowModule | Cash flow binding | Module | CashFlowCalculator | No |
| `CurrencyModule.kt` | CurrencyModule | Currency binding | Module | CurrencyConverter, ExchangeRates | No |
| `DaoModule.kt` | DaoModule | DAO injection | Module | All DAOs | No |
| `DashboardContractsModule.kt` | DashboardContractsModule | Dashboard contracts | Module | DashboardRepositoryContracts | No |
| `DatabaseModule.kt` | DatabaseModule | Database initialization | Module | AppDatabase, GroupTransactionCoordinator | No |
| `DispatchersModule.kt` | DispatchersModule | Coroutine dispatchers | Module | IO, Default, Main | No |
| `EmailIngestionModule.kt` | EmailIngestionModule | Email parsing | Module | Email receipt parsers | No |
| `EmptyStateModule.kt` | EmptyStateModule | Empty state registry | Module | Empty state configurations | No |
| `EmptyStateRegistryInitializer.kt` | EmptyStateRegistryInitializer | Empty state init | Initializer | - | No |
| `ExportModule.kt` | ExportModule | Export binding | Module | AccountingExporters | No |
| `GroupsModule.kt` | GroupsModule | Groups binding | Module | GroupTransactionCoordinator | No |
| `LocationResolverPortsModule.kt` | LocationResolverPortsModule | Location ports | Module | All geocoding services | No |
| `NaturalLanguageModule.kt` | NaturalLanguageModule | NL binding | Module | Speech, NL search | No |
| `NetworkModule.kt` | NetworkModule | Network client | Module | Retrofit, OkHttp | No |
| `NetworkQualifiers.kt` | NetworkQualifiers | Network qualifiers | Qualifier | - | No |
| `OcrImprovementsModule.kt` | OcrImprovementsModule | OCR binding | Module | OCR preprocessors | No |
| `ReceiptParsingModule.kt` | ReceiptParsingModule | Receipt parsing | Module | All receipt parsers | No |
| `SavingsModule.kt` | SavingsModule | Savings binding | Module | Savings engines | No |
| `SavingsRepositoryBindingsModule.kt` | SavingsRepositoryBindingsModule | Savings repos | Module | Savings repositories | No |
| `SecurityModule.kt` | SecurityModule | Security binding | Module | Token cipher, Key storage | No |
| `ServiceModule.kt` | ServiceModule | Services binding | Module | All domain services | No |
| `SubscriptionModule.kt` | SubscriptionModule | Subscription binding | Module | Subscription detection | No |
| `TaxModule.kt` | TaxModule | Tax binding | Module | Tax estimator | No |
| `TimeModule.kt` | TimeModule | Time provider | Module | TimeProvider, SystemTimeProvider | No |

---

## Dependency Graph & Data Flow

### Core Data Flow Architecture

```
User Input (Notification/Manual Entry)
    ↓
ParserRegistry (SMS, Email, Manual)
    ↓
GenericTransactionParser / SpecializedParsers
    ↓
TransactionLifecycleCoordinator.createExpense()
    │  [validate → normalize → dedupe → insert atomic]
    ↓
TransactionEvent (event log) + Expense (stored in DB)
    ↓
TransactionSideEffectDispatcher.dispatchOnCreated()
    │  [budget check → anomaly alert → pattern learning]
    ↓
ExpenseRepository (read layer)
    ↓
Use Cases (Domain layer)
    ↓
Engines (Analytics, Budget, Categorization, etc.)
    ↓
Dashboard/UI
```

**Key change (Phase 3):** All expense creation, update, and delete operations now route through
`TransactionLifecycleCoordinator` instead of being scattered across multiple repositories and services.
This ensures consistent validation, deduplication, event logging, and side-effect dispatch.

**Key change (Phase 4):** All receipt processing now routes through `ReceiptLifecycleCoordinator` instead
of being scattered across multiple screens and services. Receipt-expense linking is centralized in
`ReceiptLinkService` with a many-to-many join table and audit trail in `receipt_events`.

### AI Pipeline

```
Raw Input (Receipt/Notification/Query)
    ↓
AiCapabilityRouter (decision point)
    ↓
┌─────────────────┬──────────────┬─────────────────┐
│ Cloud Provider  │ OnDevice ML  │ Fallback/NoOp   │
│ (best quality)  │ (offline)    │ (no AI)         │
└─────────────────┴──────────────┴─────────────────┘
    ↓
Specific AI Service (Categorization/Receipt/Dedup)
    ↓
Result (with confidence score)
    ↓
ConfidenceRouter (routes based on confidence)
    ↓
Domain Engine (categorization, dedup, etc.)
```

### Database Entity Relationships

**Core Transaction Entities:**
- `Expense` ← Core transaction (now includes `source` column for origin tracking)
- `TransactionEvent` ← Immutable lifecycle audit log
- `PendingReview` ← Needs user review
- `Category` ← Transaction category
- `UserCorrection` ← User adjustments

**Related Entities:**
- `ScannedReceipt`, `EmailReceiptSource` ← Receipt sources
- `ManualRecurringExpense`, `RecurringExpense` ← Recurring patterns
- `Budget`, `BudgetForecast` ← Budget tracking
- `SavingsGoal`, `SavingsSweepPlan` ← Savings
- `GroupExpense`, `GroupMember`, `ExpenseGroup` ← Shared expenses
- `MerchantCanonical`, `MerchantAlias`, `MerchantLocation` ← Merchant data
- `ExchangeRate` ← Currency conversion
- `Warranty`, `ReturnWindow` ← Warranty tracking
- `Investment`, `InvestmentValue` ← Investment tracking
- `SubscriptionCandidate`, `SubscriptionUsage` ← Subscription detection
- `RawNotification`, `BlockedPackage` ← Notification tracking

### Repository → DAO → Entity Flow

```
UseCase
    ↓
Repository (business logic, data transformation)
    ↓
DAO (Room database access)
    ↓
Entity (Room-managed table)
    ↓
SQLite Database
```

### Engine → Repository → DAO Chain

**Example: Budget Calculation**
```
CalculateBudgetStatusUseCase
    ↓
BudgetCalculator (domain logic)
    ↓
BudgetRepository (provides data)
    ↓
BudgetDao + ExpenseDao (queries)
    ↓
Budget + Expense entities
```

**Example: Categorization**
```
CategorizeExpenseUseCase
    ↓
CategorizationEngine (domain logic)
    ↓
CategoryKeywords + ContextualInferenceEngine
    ↓
MerchantCategoryRepository
    ↓
MerchantCategoryDao
    ↓
MerchantCategory entity
```

### AI Services → Domain Engines

```
AiModels (input/output contracts)
    ↓
AiCapabilityRouter (routes to provider)
    ↓
Cloud/OnDevice/NoOp Providers
    ↓
Domain AI Service (wraps provider)
    ↓
UseCase (business logic)
    ↓
Engine (integration with other domain logic)
```

### Cross-Cutting Concerns

| Concern | Implementation | Files |
|---------|----------------|-------|
| **Dependency Injection** | Dagger/Hilt | `di/*Module.kt` |
| **Database** | Room | `data/database/*` |
| **Security** | Encryption, Key Storage | `data/security/*` |
| **Geocoding** | Multiple providers | `data/location/*` |
| **AI** | Cloud + OnDevice + Fallback | `data/ai/provider/*` |
| **Parsing** | Strategy pattern | `domain/parser/*`, `data/email/*` |
| **Utilities** | Shared helpers | `domain/util/*` |

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| **Domain Files** | 271 (includes 8 transaction lifecycle + 11 receipt lifecycle + 5 recurring lifecycle files) |
| **Data Files** | 214 (includes ReceiptEvent, ReceiptExpenseLink, RecurringOccurrence, RecurringReminderDelivery entities/DAOs) |
| **DI Modules** | 27 |
| **Total Backend Files** | 505+ |
| **Test Files** | 317+ |
| **Database Entities** | 60 (includes TransactionEvent, ReceiptEvent, ReceiptExpenseLink, RecurringOccurrence, RecurringReminderDelivery) |
| **DAOs** | 59 (includes TransactionEventDao, ReceiptEventDao, ReceiptExpenseLinkDao, RecurringOccurrenceDao, RecurringReminderDeliveryDao) |
| **Repositories** | 56 |
| **Use Cases** | ~30 |
| **Engines** | ~50 |
| **AI Providers** | 32 |

---

## Key Architecture Patterns

1. **Clean Architecture** - Domain, Data, DI separation
2. **Repository Pattern** - Data abstraction layer
3. **Use Case Pattern** - Single responsibility use cases
4. **Strategy Pattern** - Multiple AI providers, parsers, geocoders
5. **Adapter Pattern** - Data/domain boundary adaptation
6. **Observer Pattern** - Flow-based reactive data
7. **Builder Pattern** - Complex AI input construction
8. **Decorator Pattern** - Hybrid AI services wrapping
9. **Factory Pattern** - ParserRegistry, AppDatabase
10. **Singleton Pattern** - Repositories, Engines via DI
11. **Coordinator Pattern (Phase 3)** - `TransactionLifecycleCoordinator` is the single entry point for all expense CUD, enforcing consistent validation, deduplication, and audit logging
12. **Event Sourcing Lite (Phase 3)** - `transaction_events` table records every expense lifecycle transition as an immutable append-only log
13. **Coordinator Pattern (Phase 4)** - `ReceiptLifecycleCoordinator` is the single entry point for all receipt processing, with document-type-gated side effects
14. **Event Sourcing Lite (Phase 4)** - `receipt_events` table records every receipt lifecycle transition as an immutable append-only log with status tracking
15. **Coordinator Pattern (Phase 5)** - `RecurringLifecycleCoordinator` is the primary entry point for generating and managing recurring occurrences, with auto-link hook into `TransactionLifecycleCoordinator`
16. **Expand → Resolve → Materialize Triad (Phase 5)** - `RecurringOccurrenceExpander` + `OccurrenceConflictResolver` + `RecurringOccurrenceMaterializer` form a three-phase pipeline for occurrence lifecycle
17. **Reminder Delivery Scheduling (Phase 5)** - `recurring_reminder_deliveries` table with `getPendingDeliveries(now)` query for WorkManager-based dispatch

---

**End of Complete Backend Map**

