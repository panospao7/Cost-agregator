# DAO ↔ Entity ↔ Repository Map

> Complete mapping of all 68 Room DAOs (64 bound in DaoModule + 3 in AiModule + 1 unbound) to their entities and consuming repositories/services.
>
> Last updated: 2026-09-21

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
- **Duplicate-detection ownership split (RP-11, commit `cad0b664`):** `ExpenseDao` deliberately splits duplicate queries by ownership:
  - **Blocking prechecks intentionally INCLUDE `isNotMine` rows:** `isDuplicateCurrencyAware` (via the `existsByMerchantKeyInRangeCurrencyAware` / `existsByMerchantKeyPrefixInRangeCurrencyAware` / `existsByMerchantInRangeCurrencyAware` family) and collision preflight `findBlockingDuplicateIdCurrencyAware` (via `getBlockingCandidateByMerchantKeyInRangeCurrencyAware` / `getBlockingCandidateByMerchantKeyPrefixInRangeCurrencyAware` / `getBlockingCandidateByMerchantInRangeCurrencyAware`) scan across ownership so a not-mine expense occupying the same identity space still blocks a duplicate write. `TransactionLifecycleCoordinator` uses them in its mutation collision preflights (`updateExpense`, `updateMerchant`, `updateType`, `updateTypeAndTransferDetails`, `bulkUpdateMerchant`) and aborts with a controlled `DuplicateUpdateException` instead of dying on the raw `dedupeKey` unique index; `bulkUpdateMerchant` treats zero affected rows as a successful no-op (no event, no dispatch).
  - **Resolver/suggestion queries filter `isNotMine = 0` in SQL:** `findDuplicateIdCurrencyAware` over the `getDuplicateCandidateByMerchantKeyInRangeCurrencyAware` / `getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware` / `getDuplicateCandidateByMerchantInRangeCurrencyAware` family (plus import-suggestion `getDuplicateCandidateForImportCurrencyAware`) must never resolve to a not-mine row and must not be used for mutation collision preflights.
- **CI guardrail:** direct DAO access outside the lifecycle/allowlist files is constrained by `scripts/guardrails/dao-access-check.kts` + `scripts/guardrails/dao-approved-files.txt` (tiered allowlist) and `scripts/verify_db_access_boundaries.py`; see `docs/ci/DB_ROOM_INVENTORY.md` and `docs/ci/guard-policy.md`.

---

## Expense Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExpenseDao` | `Expense` | `ExpenseRepository`, `MultiCurrencyRepository`, `NotificationRepository`, `ReviewQueueRepository`, `ReceiptRepository`, `ManualExpenseRepository`, `AnalyticsRepository`, `BusinessExpenseRepository`, `BudgetRepository`, `NotificationProcessingPipeline`, `NaturalLanguageExpenseQueryRepositoryImpl`, `ExpenseReadStore`, `GroupTransactionCoordinator`, `TransactionLifecycleCoordinator`, `TransactionSideEffectPlanner`, `DefaultExpenseCategoryAssignmentService`, `RecurringLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator`, `ReceiptLinkService`, `BankStatementLifecycleProcessor`, `SourceLinkBackfillWorker`, `AnomalyAlertOrchestrator`, `AdvancedAnalyticsDashboard`, `SpendingThresholdCalculator`, `BudgetForecastingEngine`, `SharedBudgetManager`, `CarbonFootprintCalculator`, `SpendingChallengeManager`, `RecurringIncomeTracker`, `LifestyleInflationDetector`, `TaxEstimator` — *RP-11 contract: blocking duplicate prechecks intentionally include `isNotMine` rows; fuzzy resolvers filter `isNotMine = 0` (see Database Facts)* | HomeVM, TransactionsVM, ReviewVM, BudgetVM, AnalyticsVM, AddExpenseVM, ReceiptScanVM, SavingsGoalsVM, CashFlowCalendarVM, etc. |
| `TransactionEventDao` | `TransactionEvent` | `TransactionLifecycleCoordinator` | Audit log (10+ creation paths) |
| `CategoryDao` | `Category` | `CategoryRepository`, `BudgetRepository`, `TransactionSideEffectPlanner`, `LegacyDataMigrationService`, `CsvExpenseImporter`, `JsonExpenseImporter` | Every ViewModel (ubiquitous) |
| `UserCorrectionDao` | `UserCorrection` | `ExpenseRepository`, `NotificationRepository`, `ReviewQueueRepository`, `UserCorrectionRepository` | Debug, Transactions |
| `SourceStatsDao` | `SourceStats` | `NotificationRepository`, `ReviewQueueRepository`, `NotificationProcessingPipeline`, `SourceStatsRepository` | Debug, Review |
| `MerchantCategoryDao` | `MerchantCategory` | `MerchantCategoryRepository`, `CategoryRepository` | Categorization engine |
| `MerchantNormalizationDao` | `MerchantAlias`, `MerchantCanonical` | `MerchantNormalizationRepository`, `EnhancedMerchantExtractor` | Categorization |

## Budget Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `BudgetDao` | `Budget` | `BudgetRepository`, `CategoryRepository` | BudgetVM, BudgetForecastingVM |
| `BudgetForecastDao` | `BudgetForecast` | `BudgetRepository`, `BudgetForecastingEngine` | BudgetVM |
| `BudgetAdjustmentDao` | `BudgetAdjustmentRecommendation`, `BudgetAdjustmentEvent` | — | BudgetVM |

## Notification / Review Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `NotificationIntakeDao` | `NotificationIntakeEntity` | `NotificationIntakeCoordinator`, `NotificationIntakePayloadRepairer`, `NotificationIntakeRecoveryScheduler`, `NotificationIntakeWorker`, `RetentionModule` (purge) | Notification intake pipeline |
| `RawNotificationDao` | `RawNotification` | `NotificationRepository`, `ReviewQueueRepository`, `NotificationProcessingPipeline`, `SourceLinkBackfillWorker`, `RetentionModule` (purge) | TransactionsVM, ReviewVM, DebugVM |
| `PendingReviewDao` | `PendingReview` | `NotificationRepository`, `ReviewQueueRepository`, `ExpenseRepository`, `ReceiptRepository`, `NotificationProcessingPipeline`, `ReceiptLifecycleCoordinator`, `BankStatementLifecycleProcessor`, `BankApiIntegration`, `LegacyDataConsistencyChecker`, `SourceLinkBackfillWorker`, `RetentionModule` (redact) | ReviewVM, TransactionsVM |
| `BlockedPackageDao` | `BlockedPackage` | `NotificationRepository`, `NotificationCaptureGate`, `NotificationCaptureService` | Notification filter |

## Receipt Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ScannedReceiptDao` | `ScannedReceipt` | `ReceiptRepository`, `ReceiptInsertResolver`, `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `ReceiptMatchLifecycleService`, `ReceiptSideEffectPlanner`, `ReceiptDuplicateDetector`, `AssetCleanupCoordinator`, `BankStatementLifecycleProcessor`, `LegacyDataConsistencyChecker`, `SourceLinkBackfillWorker`, `ReceiptDebugExporter`, `RetentionModule` (privacy purge) | ReceiptScanVM, ReviewVM |
| `ReceiptItemCategorizationDao` | `ReceiptItemCategorization` | `ReceiptItemCategorizationRepository`, `ReceiptLinkService` | AI categorization |
| `ReceiptEventDao` | `ReceiptEvent` | `ReceiptLifecycleCoordinator`, `ReceiptMatchLifecycleService`, `ReceiptLifecycleEventWriter`, `ReceiptSideEffectPlanner`, `ReceiptRepository`, `LegacyDataConsistencyChecker` | Receipt audit |
| `ReceiptExpenseLinkDao` | `ReceiptExpenseLink` | `ReceiptLinkService`, `ReceiptLifecycleCoordinator`, `ReceiptDuplicateDetector`, `ReceiptRepository`, `SourceLinkBackfillWorker` | Receipt matching |

## Recurring Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ManualRecurringExpenseDao` | `ManualRecurringExpense` | `RecurringExpenseRepository`, `ManualRecurringExpenseRepository`, `SubscriptionManagementRepository`, `RecurringLifecycleCoordinator`, `RecurringRuleLifecycleCoordinator` (P4) | RecurringExpensesVM, FinancialWeatherRepository, ManualRecurringExpenseVM |
| `PlannedExpenseDao` | `PlannedExpense` | `PlannedExpenseRepository`, `RecurringPlanProjectionService`, `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringRuleLifecycleCoordinator` (P4) | HomeVM, FinancialWeatherRepository |
| `RecurringOccurrenceDao` | `RecurringOccurrence` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringRuleLifecycleCoordinator` (P4), `RecurringPlanProjectionService`, `CashFlowCalculator`, `FinancialStressForecastEngine`, `ForecastInputAssembler`, `SynthesisEngine`, `MonthlySavingsSweepUseCase`, `LegacyDataConsistencyChecker` | RecurringExpensesVM, BillReminderWorker |
| `RecurringReminderDeliveryDao` | `RecurringReminderDelivery` | `RecurringLifecycleCoordinator`, `RecurringOccurrenceMaterializer`, `RecurringRuleLifecycleCoordinator` (P4) | BillReminderWorker |
| `RecurringLifecycleEventDao` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator`, `RecurringLifecycleEventWriter` (P4), `RecurringRuleLifecycleCoordinator` (P4), `ManualRecurringExpenseRepository`, `RecurringExpenseRepository`, `LegacyDataConsistencyChecker` | Recurring audit log |
| `RecurringExpenseDao` ⚠️ | `ManualRecurringExpense` (same table) | *(deprecated — queries `manual_recurring_expenses`; use `ManualRecurringExpenseDao`; still bound in `DaoModule`)* | — |

## Currency Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExchangeRateDao` | `ExchangeRate` | `ExchangeRateStoreAdapter`, `CurrencyDataRepository` | MultiCurrencyRepository, all currency-aware pipelines |

## Savings Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SavingsGoalDao` | `SavingsGoal` | `SavingsGoalRepository` | SavingsGoalsVM, FinancialWeatherRepository |
| `SavingsSweepPlanDao` | `SavingsSweepPlan` | — | Automated savings |

## AI Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `AiArtifactDao` | `AiArtifactEntity` | `AiArtifactRepositoryImpl`, `RetentionModule` (purge) | HomeVM, ReviewVM, ReceiptScanVM |
| `AiChatSessionDao` | `AiChatSessionEntity` | `AiChatRepositoryImpl` | AssistantVM |
| `AiChatMessageDao` | `AiChatMessageEntity` | `AiChatRepositoryImpl`, `RetentionModule` (purge) | AssistantVM |

## Groups Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `ExpenseGroupDao` | `ExpenseGroup` | `GroupsRepositoryImpl`, `SharedExpenseDataPortAdapter`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupMemberDao` | `GroupMember` | `GroupsRepositoryImpl`, `SharedExpenseDataPortAdapter`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupExpenseDao` | `GroupExpense` | `GroupsRepositoryImpl`, `SharedExpenseDataPortAdapter`, `GroupTransactionCoordinator`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupSettlementDao` | `GroupSettlementEntity` | `GroupBalanceCalculator`, `SettlementCalculator` (DAO passed as parameter) | SharedExpenseGroupsVM |
| `GroupLifecycleEventDao` | `GroupLifecycleEventEntity` | — | Group lifecycle audit log |

## Investment Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `InvestmentDao` | `Investment` | `InvestmentTracker` | InvestmentVM |
| `InvestmentValueDao` | `InvestmentValue` | `InvestmentTracker` | InvestmentVM |
| `InvestmentTransactionDao` | `InvestmentTransaction` | `InvestmentTracker` | InvestmentVM |

## Bank Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `BankConnectionDao` | `BankConnection` | `BankConnectionLifecycleCoordinator`, `BankApiIntegration` | — |
| `BankStatementImportRunDao` | `BankStatementImportRun` | `BankStatementLifecycleProcessor` | Bank statement import |
| `BankStatementImportItemDao` | `BankStatementImportItem` | `BankStatementLifecycleProcessor` | Bank statement import |

## Subscription Domain

| DAO | Entity | Repository Consumers | Ultimate Consumers |
|-----|--------|---------------------|-------------------|
| `SubscriptionCandidateDao` | `SubscriptionCandidate` | `SubscriptionManagementRepository`, `SubscriptionManagerEngine`, `NotificationProcessingPipeline` | SubscriptionVM |
| `SubscriptionPriceHistoryDao` | `SubscriptionPriceHistory` | `SubscriptionManagementRepository`, `SubscriptionManagerEngine`, `SmartBillNegotiationEngine` | SubscriptionVM |
| `SubscriptionUsageDao` | `SubscriptionUsage` | `SubscriptionManagementRepository`, `SubscriptionManagerEngine` | SubscriptionVM |

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
| `PrivacyAuditDao` | `PrivacyAuditEvent` | `PrivacyAuditLoggerImpl`, `DataRetentionWorker` (via `AppDatabase.privacyAuditDao()`) | Privacy audit |
| `AnomalyAlertDao` | `AnomalyAlert` | `AnomalyAlertRepositoryImpl` | Analytics, Dashboard |
| `HealthScoreHistoryDao` | `HealthScoreHistory` | `FinancialHealthScoreV2` | — |
| `EmailReceiptDao` | `EmailReceiptSource` | `ReceiptLifecycleCoordinator`, `SourceLinkBackfillWorker`, `RetentionModule` (redact) | Email ingestion |
| `PromptStateDao` | `PromptState` | `PromptStateRepository` | Savings prompts |
| `BackgroundJobRunDao` | `BackgroundJobRun` | `WorkerRunLoggerImpl`, `WorkerExecutionGuard`, `DiagnosticsRepository`, `RetentionModule` (error redaction) | Worker tracking |
| `RecommendationDao` | `RecommendationEntity` | `RecommendationRepository` | AI recommendations |
| `StressForecastSnapshotDao` | `StressForecastSnapshot` | — | Cash flow |
| `SourceStatsEventDao` | `SourceStatsEvent` | — | Source stats event tracking (event-based, v117+) |
| `PipelineDiagnosticEventDao` | `PipelineDiagnosticEvent` | `DiagnosticEventWriter`, `DiagnosticsRepository`, `RetentionModule` (purge) | Cross-pipeline diagnostic tracking |
| `OperationRunDao` | `OperationRun` | `RoomOperationRunRecorder`, `DiagnosticsRepository`, `RestoreJournalImporter` | Durable operation run tracking |
| `OperationRunEventDao` | `OperationRunEvent` | `RoomOperationRunRecorder`, `DiagnosticsRepository`, `RestoreJournalImporter` | Durable operation run events |
| `EntitySourceLinkDao` | `EntitySourceLink` | `SourceLinkWriterImpl`, `SourceLinkQueryService`, `SourceLinkBackfillWorker`, `PendingReviewSourceLinkPromoterImpl`, `ExportDataRepository` | Source link tracking |

---

## Cross-Cutting DAO Usage Heatmap

| DAO | # of Repository Consumers | Risk Level |
|-----|--------------------------|------------|
| `ExpenseDao` | **11** repositories + **20** domain/store/coordinator consumers | 🔴 CRITICAL — breaking this DAO breaks the entire app |
| `CategoryDao` | **2** repositories + **4** services/importers | 🟡 HIGH — ubiquitous in ViewModels |
| `PendingReviewDao` | **5** repositories + **5** lifecycle/domain consumers | 🟡 HIGH — review pipeline |
| `RawNotificationDao` | **3** repositories + **2** consumers | 🟡 HIGH — notification pipeline |
| `ScannedReceiptDao` | **12** direct injectors + `RetentionModule` purge | 🟡 HIGH — receipt pipeline |
| `BudgetDao` | **2** repositories | 🟢 MEDIUM |
| `TransactionEventDao` | **6** consumers | 🟢 MEDIUM (append-only log) |
| `RecurringOccurrenceDao` | **10** consumers | 🟢 MEDIUM |
| `ExchangeRateDao` | **2** consumers | 🟢 MEDIUM |
| `ManualRecurringExpenseDao` | **3** repositories + **2** lifecycle coordinators | 🟢 MEDIUM |
| `ExpenseGroupDao` | **2** repositories + **2** coordinators/calculators | 🟢 MEDIUM |
| `WarrantyDao` | **2** consumers | 🟢 MEDIUM |
| `ReturnWindowDao` | **2** consumers | 🟢 MEDIUM |
| `BudgetAdjustmentDao` | **0** direct consumers | 🟢 LOW |
| `AiArtifactDao` | **1** repository (+ `RetentionModule` purge) | 🟢 LOW |
| `SourceStatsEventDao` | **0** direct repositories | 🟢 LOW (event-based tracking) |
| `GroupLifecycleEventDao` | **0** direct consumers | 🟢 LOW (append-only event log) |
| `PipelineDiagnosticEventDao` | **2** consumers | 🟢 LOW (diagnostic tracking) |
| `OperationRunDao` | **3** consumers | 🟢 LOW (operation run tracking) |
| `OperationRunEventDao` | **3** consumers | 🟢 LOW (run events) |
| `BackgroundJobRunDao` | **3** consumers | 🟢 LOW (worker run logging) |
| `ReceiptExpenseLinkDao` | **5** consumers | 🟡 HIGH — receipt matching |
| `ReceiptEventDao` | **6** consumers | 🟢 MEDIUM — receipt lifecycle |
| `NotificationIntakeDao` | **4** consumers | 🟢 MEDIUM — notification intake |
| `EntitySourceLinkDao` | **5** consumers | 🟢 LOW — source link tracking |
| `NegotiationOutcomeDao` | **1** consumer | 🟢 LOW — bill negotiation outcome tracking |
| `BankStatementImportRunDao` | **1** consumer (BankStatementLifecycleProcessor) | 🟢 LOW — bank statement import |
| `BankStatementImportItemDao` | **1** consumer (BankStatementLifecycleProcessor) | 🟢 LOW — bank statement import |
| **Total: 68 Room DAOs (64 DaoModule + 3 AiModule + 1 unbound: SourceStatsEventDao)** | | |
