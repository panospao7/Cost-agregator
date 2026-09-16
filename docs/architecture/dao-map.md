# DAO ↔ Entity ↔ Repository Map

> Complete mapping of all 68 Room DAOs (64 bound in DaoModule + 3 in AiModule + 1 unbound) to their entities and consuming repositories/services.
>
> Last updated: 2026-09-07

---

## Legend

- **DAO** → Room Data Access Object interface
- **Entity** → Room @Entity class
- **Repository** → Classes that inject this DAO
- **Consumers** → ViewModels / Services that further depend on the repository

---

## Database Facts (verified against code)

- **Schema version:** `APP_DATABASE_SCHEMA_VERSION = 148` (`data/database/AppDatabase.kt`); migration baseline is **v145** (older DBs use the rescue/import path).
- **Registered in `@Database`:** 70 entities + 68 DAO accessors; type converters in `data/database/converter/Converters.kt` (22 `@TypeConverter` methods).
- **Migrations** (`data/database/DatabaseMigrations.kt`, exported snapshots under `app/schemas/.../148.json`):
  - `MIGRATION_145_146` — creates `negotiation_outcomes` table (FK → `manual_recurring_expenses`, 3 indexes).
  - `MIGRATION_146_147` — group soft-delete: adds `group_members.leftAt`, `group_expenses.idempotencyKey`; drops the unique `(groupId, name)` index on `group_members` and recreates it as non-unique.
  - `MIGRATION_147_148` — PR12A: adds 9 worker-run tracing columns to `background_job_runs` (`workId`, `uniqueWorkName`, `specVersion`, `runAttempt`, `leaseId`, `terminalReasonCode`, `terminalDiagnosticCode`, `partialFailureCount`, `failedTargetCount`).
- **Single source of truth for migration config:** `data/database/DatabaseSchemaPolicy.kt` (`CURRENT_VERSION`, `MIGRATION_BASELINE = 145`, `ALL_MIGRATIONS`) — production, tests, and CI must read from it.
- **Hilt binding split:** 64 DAOs provided in `di/DaoModule.kt`; the 3 AI DAOs (`AiArtifactDao`, `AiChatSessionDao`, `AiChatMessageDao`) are provided by the `AiModule` companion object (`di/AiModule.kt`); `SourceStatsEventDao` is registered in `AppDatabase` but **not Hilt-bound** (no direct consumer).
- **Write-restriction:** direct `ExpenseDao` mutations require the `RestrictedExpenseDaoMutation` opt-in (`data/database/dao/RestrictedExpenseDaoMutation.kt`); hard enforcement is via the `ExpenseDaoMutationAccessTest` architecture test.
- **CI guardrail:** direct DAO access outside the lifecycle/allowlist files is constrained by `scripts/guardrails/dao-access-check.kts` + `scripts/guardrails/dao-approved-files.txt` (tiered allowlist) and `scripts/verify_db_access_boundaries.py`; see `docs/ci/DB_ROOM_INVENTORY.md` and `docs/ci/guard-policy.md`.

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
| `NotificationIntakeDao` | `NotificationIntakeEntity` | `NotificationProcessingPipeline` | Notification intake pipeline |
| `RawNotificationDao` | `RawNotification` | `NotificationRepository`, `ReviewQueueRepository` | TransactionsVM, ReviewVM, DebugVM |
| `PendingReviewDao` | `PendingReview` | `NotificationRepository`, `ReviewQueueRepository`, `ExpenseRepository`, `ReceiptRepository` | ReviewVM, TransactionsVM |
| `BlockedPackageDao` | `BlockedPackage` | `NotificationRepository` | Notification filter |

## Receipt Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ScannedReceiptDao` | `ScannedReceipt` | `ReceiptRepository`, `DataRetentionWorker`, `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `ReceiptMatchLifecycleService` | ReceiptScanVM, ReviewVM |
| `ReceiptItemCategorizationDao` | `ReceiptItemCategorization` | `ReceiptItemCategorizationRepository`, `ReceiptLinkService` | AI categorization |
| `ReceiptEventDao` | `ReceiptEvent` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `ReceiptMatchLifecycleService` | Receipt audit |
| `ReceiptExpenseLinkDao` | `ReceiptExpenseLink` | `ReceiptLinkService`, `ReceiptLifecycleCoordinator`, `ReceiptMatchLifecycleService` | Receipt matching |

## Recurring Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ManualRecurringExpenseDao` | `ManualRecurringExpense` | `RecurringExpenseRepository`, `ManualRecurringExpenseRepository`, `RecurringRuleLifecycleCoordinator` (P4) | RecurringExpensesVM, FinancialWeatherRepository, ManualRecurringExpenseVM |
| `PlannedExpenseDao` | `PlannedExpense` | `PlannedExpenseRepository`, `RecurringPlanProjectionService`, `RecurringRuleLifecycleCoordinator` (P4) | HomeVM, FinancialWeatherRepository |
| `RecurringOccurrenceDao` | `RecurringOccurrence` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringRuleLifecycleCoordinator` (P4) | RecurringExpensesVM, BillReminderWorker |
| `RecurringReminderDeliveryDao` | `RecurringReminderDelivery` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringRuleLifecycleCoordinator` (P4) | BillReminderWorker |
| `RecurringLifecycleEventDao` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator`, `RecurringLifecycleEventWriter` (P4), `RecurringRuleLifecycleCoordinator` (P4) | Recurring audit log |
| `RecurringExpenseDao` ⚠️ | `ManualRecurringExpense` (same table) | *(deprecated — queries `manual_recurring_expenses`; use `ManualRecurringExpenseDao`; still bound in `DaoModule`)* | — |

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
| `BankStatementImportRunDao` | `BankStatementImportRun` | — | Bank statement import |
| `BankStatementImportItemDao` | `BankStatementImportItem` | — | Bank statement import |

## Subscription Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SubscriptionCandidateDao` | `SubscriptionCandidate` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionPriceHistoryDao` | `SubscriptionPriceHistory` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionUsageDao` | `SubscriptionUsage` | `SubscriptionManagementRepository` | SubscriptionVM |

## Bill Negotiation Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `NegotiationOutcomeDao` | `NegotiationOutcomeEntity` | `SmartBillNegotiationEngine` | BillNegotiationVM |

## Warranty Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `WarrantyDao` | `Warranty` | `WarrantyTrackerRepository`, `ReceiptLinkService` | WarrantyTrackerVM |
| `ReturnWindowDao` | `ReturnWindow` | `WarrantyTrackerRepository`, `ReceiptLinkService` | WarrantyTrackerVM |
| `WarrantyLifecycleEventDao` | `WarrantyLifecycleEvent` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `WarrantyReminderDeliveryDao` | `WarrantyReminderDelivery` | `WarrantyExpirationWorker` (claim-before-notify) | Durable reminder sent-state (v143) |

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
| `BackgroundJobRunDao` | `BackgroundJobRun` | Workers directly, `WorkerRunLoggerImpl` | Worker tracking |
| `RecommendationDao` | `RecommendationEntity` | `RecommendationRepository` | AI recommendations |
| `StressForecastSnapshotDao` | `StressForecastSnapshot` | `FinancialStressForecastEngine` | Cash flow |
| `SourceStatsEventDao` | `SourceStatsEvent` | — | Source stats event tracking (event-based, v117+) |
| `PipelineDiagnosticEventDao` | `PipelineDiagnosticEvent` | `NotificationProcessingPipeline` | Cross-pipeline diagnostic tracking |
| `OperationRunDao` | `OperationRun` | `CompositeOperationRunRecorder` | Durable operation run tracking |
| `OperationRunEventDao` | `OperationRunEvent` | `CompositeOperationRunRecorder` | Durable operation run events |
| `EntitySourceLinkDao` | `EntitySourceLink` | Provenance services | Source link tracking |

---

## Cross-Cutting DAO Usage Heatmap

| DAO | # of Repository Consumers | Risk Level |
|-----|--------------------------|------------|
| `ExpenseDao` | **7** repositories + **4** domain services | 🔴 CRITICAL — breaking this DAO breaks the entire app |
| `CategoryDao` | **2+** repositories | 🟡 HIGH — ubiquitous in ViewModels |
| `PendingReviewDao` | **4** repositories | 🟡 HIGH — review pipeline |
| `RawNotificationDao` | **2** repositories | 🟡 HIGH — notification pipeline |
| `ScannedReceiptDao` | **5** consumers | 🟡 HIGH — receipt pipeline |
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
| `BackgroundJobRunDao` | **1** consumer | 🟢 LOW (worker run logging) |
| `ReceiptExpenseLinkDao` | **3** consumers | 🟡 HIGH — receipt matching |
| `ReceiptEventDao` | **3** consumers | 🟢 MEDIUM — receipt lifecycle |
| `NotificationIntakeDao` | **1** consumer | 🟢 MEDIUM — notification intake |
| `EntitySourceLinkDao` | **1** consumer | 🟢 LOW — source link tracking |
| `NegotiationOutcomeDao` | **1** consumer | 🟢 LOW — bill negotiation outcome tracking |
| `BankStatementImportRunDao` | **0** direct | 🟢 LOW — bank statement import |
| `BankStatementImportItemDao` | **0** direct | 🟢 LOW — bank statement import |
| **Total: 68 Room DAOs (64 DaoModule + 3 AiModule + 1 unbound: SourceStatsEventDao)** | | |
