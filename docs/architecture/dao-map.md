# DAO ↔ Entity ↔ Repository Map

> Complete mapping of all 54 DAOs to their entities and consuming repositories/services.

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
| `ExpenseDao` | `Expense` | `ExpenseRepository`, `MultiCurrencyRepository`, `NotificationRepository`, `ReviewQueueRepository`, `ReceiptRepository`, `ManualExpenseRepository`, `AnalyticsRepository`, `BudgetAutopilotEngine` | HomeVM, TransactionsVM, ReviewVM, BudgetVM, AnalyticsVM, AddExpenseVM, ReceiptScanVM, SavingsGoalsVM, CashFlowCalendarVM, etc. |
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
| `RawNotificationDao` | `RawNotification` | `NotificationRepository`, `ReviewQueueRepository`, `DataRetentionWorker` | TransactionsVM, ReviewVM, DebugVM |
| `PendingReviewDao` | `PendingReview` | `NotificationRepository`, `ReviewQueueRepository`, `ExpenseRepository`, `ReceiptRepository` | ReviewVM, TransactionsVM |
| `BlockedPackageDao` | `BlockedPackage` | `NotificationRepository` | Notification filter |

## Receipt Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ScannedReceiptDao` | `ScannedReceipt` | `ReceiptRepository`, `DataRetentionWorker`, `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | ReceiptScanVM, ReviewVM |
| `ReceiptEventDao` | `ReceiptEvent` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | Receipt audit |
| `ReceiptExpenseLinkDao` | `ReceiptExpenseLink` | `ReceiptLinkService`, `ReceiptLifecycleCoordinator` | Receipt matching |
| `ReceiptItemCategorizationDao` | `ReceiptItemCategorization` | `ReceiptItemCategorizationRepository`, `ReceiptLinkService` | AI categorization |

## Recurring Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ManualRecurringExpenseDao` | `ManualRecurringExpense` | `RecurringExpenseRepository`, `ManualRecurringExpenseRepository` | RecurringExpensesVM, FinancialWeatherRepository, ManualRecurringExpenseVM |
| `RecurringOccurrenceDao` | `RecurringOccurrence` | `RecurringLifecycleCoordinator`, `CashFlowCalculator`, `MonthlySavingsSweepUseCase` | RecurringExpensesVM, BillReminderWorker |
| `RecurringReminderDeliveryDao` | `RecurringReminderDelivery` | `RecurringLifecycleCoordinator` | BillReminderWorker |
| `RecurringLifecycleEventDao` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator` | Recurring audit log |
| `PlannedExpenseDao` | `PlannedExpense` | `PlannedExpenseRepository`, `RecurringPlanProjectionService` | HomeVM, FinancialWeatherRepository |

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
| `ExpenseGroupDao` | `ExpenseGroup` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator` | SharedExpenseGroupsVM |
| `GroupMemberDao` | `GroupMember` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator` | SharedExpenseGroupsVM |
| `GroupExpenseDao` | `GroupExpense` | `GroupsRepositoryImpl`, `GroupTransactionCoordinator` | SharedExpenseGroupsVM |

## Investment Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `InvestmentDao` | `Investment` | `InvestmentTracker` | InvestmentVM |
| `InvestmentValueDao` | `InvestmentValue` | `InvestmentTracker` | InvestmentVM |

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
| `PrivacyAuditDao` | `PrivacyAuditEvent` | `PrivacyAuditLogger`, `DataRetentionWorker` | Privacy audit |
| `AnomalyAlertDao` | `AnomalyAlert` | `AnomalyAlertRepositoryImpl` | Analytics, Dashboard |
| `HealthScoreHistoryDao` | `HealthScoreHistory` | — | — |
| `EmailReceiptDao` | `EmailReceiptSource` | `EmailReceiptIngestionService` | Email ingestion |
| `PromptStateDao` | `PromptState` | `PromptStateRepository` | Savings prompts |
| `BackgroundJobRunDao` | `BackgroundJobRun` | — | — |
| `RecommendationDao` | `RecommendationEntity` | `RecommendationRepository` | AI recommendations |
| `StressForecastSnapshotDao` | `StressForecastSnapshot` | `FinancialStressForecastEngine` | Cash flow |

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
| All other DAOs | **1** consumer | 🟢 LOW (isolated) |

