# DAO ↔ Entity ↔ Repository Map

> Complete mapping of all 62 DAOs (58 in DaoModule + 3 in AiModule + 1 unbound) to their entities and consuming repositories/services.
>
> Last updated: 2026-05-18

---

## Legend

- **DAO** → Room Data Access Object interface
- **Entity** → Room @Entity class
- **Repository** → Classes that inject this DAO
- **Consumers** → ViewModels / Services that further depend on the repository

---

## Expense Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExpenseDao` | `Expense` | `ExpenseRepository`, `MultiCurrencyRepository`, `NotificationRepository`, `ReviewQueueRepository`, `ReceiptRepository`, `ManualExpenseRepository`, `AnalyticsRepository`, `BudgetAutopilotEngine`, `NaturalLanguageExpenseQueryRepositoryImpl` | HomeVM, TransactionsVM, ReviewVM, BudgetVM, AnalyticsVM, AddExpenseVM, ReceiptScanVM, SavingsGoalsVM, CashFlowCalendarVM, etc. |
| `TransactionEventDao` | `TransactionEvent` | `TransactionLifecycleCoordinator` | Audit log (10+ creation paths) |
| `CategoryDao` | `Category` | `CategoryRepository`, `BudgetRepository` | Every ViewModel (ubiquitous) |
| `UserCorrectionDao` | `UserCorrection` | `ExpenseRepository`, `NotificationRepository`, `ReviewQueueRepository` | Debug, Transactions |
| `SourceStatsDao` | `SourceStats` | `NotificationRepository`, `ReviewQueueRepository` | Debug, Review |
| `MerchantCategoryDao` | `MerchantCategory` | `MerchantCategoryRepository` | Categorization engine |
| `MerchantNormalizationDao` | `MerchantAlias`, `MerchantCanonical` | `MerchantNormalizationRepository` | Categorization |

## Budget Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `BudgetDao` | `Budget` | `BudgetRepository` | BudgetVM, BudgetForecastingVM |
| `BudgetForecastDao` | `BudgetForecast` | `BudgetCalculator` | BudgetVM |
| `BudgetAdjustmentDao` | `BudgetAdjustmentRecommendation`, `BudgetAdjustmentEvent` | `BudgetAutopilotEngine` | BudgetVM |

## Notification / Review Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `RawNotificationDao` | `RawNotification` | `NotificationRepository`, `ReviewQueueRepository` | TransactionsVM, ReviewVM, DebugVM |
| `PendingReviewDao` | `PendingReview` | `NotificationRepository`, `ReviewQueueRepository`, `ExpenseRepository`, `ReceiptRepository` | ReviewVM, TransactionsVM |
| `BlockedPackageDao` | `BlockedPackage` | `NotificationRepository` | Notification filter |

## Receipt Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ScannedReceiptDao` | `ScannedReceipt` | `ReceiptRepository`, `DataRetentionWorker`, `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | ReceiptScanVM, ReviewVM |
| `ReceiptItemCategorizationDao` | `ReceiptItemCategorization` | `ReceiptItemCategorizationRepository`, `ReceiptLinkService` | AI categorization |
| `ReceiptEventDao` | `ReceiptEvent` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | Receipt audit |
| `ReceiptExpenseLinkDao` | `ReceiptExpenseLink` | `ReceiptLinkService`, `ReceiptLifecycleCoordinator` | Receipt matching |

## Recurring Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ManualRecurringExpenseDao` | `ManualRecurringExpense` | `RecurringExpenseRepository`, `ManualRecurringExpenseRepository` | RecurringExpensesVM, FinancialWeatherRepository, ManualRecurringExpenseVM |
| `PlannedExpenseDao` | `PlannedExpense` | `PlannedExpenseRepository`, `RecurringPlanProjectionService` | HomeVM, FinancialWeatherRepository |
| `RecurringOccurrenceDao` | `RecurringOccurrence` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer` | RecurringExpensesVM, BillReminderWorker |
| `RecurringReminderDeliveryDao` | `RecurringReminderDelivery` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer` | BillReminderWorker |
| `RecurringLifecycleEventDao` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator` | Recurring audit log |

## Currency Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExchangeRateDao` | `ExchangeRate` | `CurrencyConverter`, `ExchangeRateStoreAdapter` | MultiCurrencyRepository, all currency-aware pipelines |

## Savings Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SavingsGoalDao` | `SavingsGoal` | `SavingsGoalRepository` | SavingsGoalsVM, FinancialWeatherRepository |
| `SavingsSweepPlanDao` | `SavingsSweepPlan` | `MonthlySavingsSweepUseCase` | Automated savings |

## AI Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `AiArtifactDao` | `AiArtifactEntity` | `AiArtifactRepositoryImpl` | HomeVM, ReviewVM, ReceiptScanVM |
| `AiChatSessionDao` | `AiChatSessionEntity` | `AiChatRepositoryImpl` | AssistantVM |
| `AiChatMessageDao` | `AiChatMessageEntity` | `AiChatRepositoryImpl` | AssistantVM |

## Groups Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExpenseGroupDao` | `ExpenseGroup` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupMemberDao` | `GroupMember` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupExpenseDao` | `GroupExpense` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupSettlementDao` | `GroupSettlementEntity` | `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupLifecycleEventDao` | `GroupLifecycleEventEntity` | `GroupLifecycleCoordinator` | Group lifecycle audit log |

## Investment Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `InvestmentDao` | `Investment` | `InvestmentTracker` | InvestmentVM |
| `InvestmentValueDao` | `InvestmentValue` | `InvestmentTracker` | InvestmentVM |
| `InvestmentTransactionDao` | `InvestmentTransaction` | — | InvestmentVM |

## Bank Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `BankConnectionDao` | `BankConnection` | — | — |

## Subscription Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SubscriptionCandidateDao` | `SubscriptionCandidate` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionPriceHistoryDao` | `SubscriptionPriceHistory` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionUsageDao` | `SubscriptionUsage` | `SubscriptionManagementRepository` | SubscriptionVM |

## Warranty Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `WarrantyDao` | `Warranty` | `WarrantyTrackerRepository`, `ReceiptLinkService` | WarrantyTrackerVM |
| `ReturnWindowDao` | `ReturnWindow` | `WarrantyTrackerRepository`, `ReceiptLinkService` | WarrantyTrackerVM |
| `WarrantyLifecycleEventDao` | `WarrantyLifecycleEvent` | `WarrantyTrackerRepository` | WarrantyTrackerVM |

## Split Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SplitTemplateDao` | `SplitTemplate` | `EnhancedSplitManager` | VisualSplitVM |
| `SplitItemAssignmentDao` | `SplitItemAssignment` | `EnhancedSplitManager` | VisualSplitVM |

## Challenge Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SpendingChallengeDao` | `SpendingChallengeEntity` | `SpendingChallengeRepository` | SpendingChallengesVM |
| `SpendingPersonalityProfileDao` | `SpendingPersonalityProfileEntity` | — | — |

## Location / Merchant Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `MerchantLocationDao` | `MerchantLocation`, `MerchantLocationCorrection` | `MerchantLocationRepository` | Map, Location |
| `MileageTrackingDao` | `MileageTracking` | — | — |

## Other Domains

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `PrivacyAuditDao` | `PrivacyAuditEvent` | `PrivacyAuditLoggerImpl` | Privacy audit |
| `AnomalyAlertDao` | `AnomalyAlert` | `AnomalyAlertRepositoryImpl` | Analytics, Dashboard |
| `HealthScoreHistoryDao` | `HealthScoreHistory` | — | — |
| `EmailReceiptDao` | `EmailReceiptSource` | `EmailReceiptIngestionService` | Email ingestion |
| `PromptStateDao` | `PromptState` | `PromptStateRepository` | Savings prompts |
| `BackgroundJobRunDao` | `BackgroundJobRun` | Workers directly | Worker tracking |
| `RecommendationDao` | `RecommendationEntity` | `RecommendationRepository` | AI recommendations |
| `StressForecastSnapshotDao` | `StressForecastSnapshot` | `FinancialStressForecastEngine` | Cash flow |
| `SourceStatsEventDao` | `SourceStatsEvent` | — | Source stats event tracking (event-based, v117+) |
| `PipelineDiagnosticEventDao` | `PipelineDiagnosticEvent` | `NotificationProcessingPipeline` | Cross-pipeline diagnostic tracking |
| `OperationRunDao` | `OperationRun` | `CompositeOperationRunRecorder` | Durable operation run tracking |
| `OperationRunEventDao` | `OperationRunEvent` | `CompositeOperationRunRecorder` | Durable operation run events |

---

## Cross-Cutting DAO Usage Heatmap

| DAO | # of Repository Consumers | Risk Level |
|-----|--------------------------|------------|
| `ExpenseDao` | **7** repositories + **4** domain services | 🔴 CRITICAL — breaking this DAO breaks the entire app |
| `CategoryDao` | **2+** repositories | 🟡 HIGH — ubiquitous in ViewModels |
| `PendingReviewDao` | **4** repositories | 🟡 HIGH — review pipeline |
| `RawNotificationDao` | **2** repositories | 🟡 HIGH — notification pipeline |
| `ScannedReceiptDao` | **4** consumers | 🟡 HIGH — receipt pipeline |
| `BudgetDao` | **1** repository | 🟢 MEDIUM |
| `TransactionEventDao` | **1** consumer | 🟢 MEDIUM (append-only log) |
| `RecurringOccurrenceDao` | **2** consumers | 🟢 MEDIUM |
| `ExchangeRateDao` | **2** consumers | 🟢 MEDIUM |
| `ManualRecurringExpenseDao` | **2** repositories | 🟢 MEDIUM |
| `ExpenseGroupDao` | **2** repositories | 🟢 MEDIUM |
| `WarrantyDao` | **2** repositories | 🟢 MEDIUM |
| `ReturnWindowDao` | **2** repositories | 🟢 MEDIUM |
| `BudgetAdjustmentDao` | **1** consumer | 🟢 LOW |
| `AiArtifactDao` | **1** repository | 🟢 LOW |
| `SourceStatsEventDao` | **0** direct repositories | 🟢 LOW (event-based tracking) |
| `GroupLifecycleEventDao` | **1** consumer | 🟢 LOW (append-only event log) |
| `PipelineDiagnosticEventDao` | **1** consumer | 🟢 LOW (diagnostic tracking) |
| `OperationRunDao` | **1** consumer | 🟢 LOW (operation run tracking) |
| `OperationRunEventDao` | **1** consumer | 🟢 LOW (run events) |
| **Total: 62 DAOs (58 DaoModule + 3 AiModule + 1 unbound)** | | |

