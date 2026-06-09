# ExpenseTracker Dependency Map

> **Auto-generated dependency analysis.**  
> Answers: *"If this file breaks, what flows depend on it?"*

---

## Table of Contents

1. [Notification Capture Dependency Map](#1-notification-capture-dependency-map)
2. [Transaction Lifecycle Dependency Map](#2-transaction-lifecycle-dependency-map)
3. [Receipt Lifecycle Dependency Map](#3-receipt-lifecycle-dependency-map)
4. [Recurring Lifecycle Dependency Map](#4-recurring-lifecycle-dependency-map)
5. [Backup/Restore Dependency Map](#5-backuprestore-dependency-map)
6. [Privacy Gate Dependency Map](#6-privacy-gate-dependency-map)
7. [Dashboard/Analytics/Currency Dependency Map](#7-dashboardanalyticscurrency-dependency-map)
8. [Worker/Startup Dependency Map](#8-workerstartup-dependency-map)
9. [Hilt Module Map](#9-hilt-module-map)
10. [DAO/Repository Map](#10-daorepository-map)
11. [Location Services Dependency Map](#11-location-services-dependency-map)
12. [AI Provider Dependency Map](#12-ai-provider-dependency-map)
13. [Stage 1 Architecture Foundations](#13-stage-1-architecture-foundations)
14. [Provenance / Source Link Dependency Chain](#14-provenance--source-link-dependency-chain)
15. [Notification Intake / Capture Subsystem](#15-notification-intake--capture-subsystem)
16. [Domain Side-Effect Framework](#16-domain-side-effect-framework)
17. [Anomaly / Alerts Dependency Chain](#17-anomaly--alerts-dependency-chain)
18. [Financial Rescue Dependency Chain](#18-financial-rescue-dependency-chain)
19. [Business / Income / Lifestyle Engines Dependency Chain](#19-business--income--lifestyle-engines-dependency-chain)
20. [Domain Engine Layer](#20-domain-engine-layer)

Also see:
- [GroupBalanceCalculator Dependency Chain](#groupbalancecalculator-dependency-chain-2026-05-10)
- [Negotiation Dependency Chain](#negotiation-dependency-chain-2026-05-09)
- [Natural Language Search Dependency Chain](#natural-language-search-dependency-chain-2026-05-09)
- [ViewModel Constructor Injection Reference](#viewmodel-constructor-injection-reference)

---

## 1. Notification Capture Dependency Map

```
Android NotificationListener
  │
  ▼
NotificationCaptureService              [service/NotificationCaptureService.kt]
  │
  ├──► NotificationFilter               [domain/privacy/NotificationPrivacyGate.kt]
  │     └──► PrivacyCapability.NOTIFICATION_CAPTURE
  │
  ├──► PrivacyGate.check()              [domain/privacy/CompositePrivacyGate.kt]
  │
  ├──► RestoreMaintenanceMode            [data/backup/RestoreMaintenanceMode.kt]
  │     └── isActive() → pauses capture during restore
  │
  ▼
NotificationIntakeCoordinator           [domain/notification/capture/NotificationIntakeCoordinator.kt]
  │
  ├──► NotificationCaptureGate           [domain/notification/capture/NotificationCaptureGate.kt]
  ├──► NotificationCaptureDeduper        [domain/notification/capture/NotificationCaptureDeduper.kt]
  ├──► NotificationTransientPayloadCrypto [domain/notification/capture/NotificationTransientPayloadCrypto.kt]
  ├──► NotificationIntakeDao             [data/database/dao/NotificationIntakeDao.kt]
  │     └──► NotificationIntakeEntity    [data/database/entity/NotificationIntakeEntity.kt]
  ├──► NotificationIntakePayloadRepairer  [domain/notification/capture/NotificationIntakePayloadRepairer.kt]
  ├──► NotificationIntakeRecoveryScheduler [domain/notification/capture/NotificationIntakeRecoveryScheduler.kt]
  │
  ▼
NotificationIntakeWorker                [worker/NotificationIntakeWorker.kt]
  │  (processes queued intake entities asynchronously)
  │
  ▼
NotificationProcessingPipeline           [data/repository/NotificationProcessingPipeline.kt]
  │
  ├──► AppParserRegistry                 [domain/parser/AppParserRegistry.kt]
  │     ├── GreekBankParser              [domain/parser/parsers/GreekBankParser.kt]
  │     ├── RevolutParser                [domain/parser/parsers/RevolutParser.kt]
  │     ├── GoogleWalletParser           [domain/parser/parsers/GoogleWalletParser.kt]
  │     ├── SmsParser                    [domain/parser/parsers/SmsParser.kt]
  │     └── GenericTransactionParser     [domain/parser/GenericTransactionParser.kt]
  │
  ├──► ConfidenceRouter                  [domain/intelligence/ConfidenceRouter.kt]
  │
  ├──► TransactionClassifier             [domain/intelligence/TransactionClassifier.kt]
  │
  ├──► CategorizationEngine              [domain/categorization/CategorizationEngine.kt]
  │
  ▼
NotificationRepository                   [data/repository/NotificationRepository.kt]
  │
  ├──► RawNotificationDao
  ├──► BlockedPackageDao
  ├──► ExpenseDao
  ├──► PendingReviewDao
  ├──► UserCorrectionDao
  ├──► SourceStatsDao
  │
  ▼
ReviewQueueRepository                    [data/repository/ReviewQueueRepository.kt]
  │
  ├──► PendingReviewDao
  ├──► RawNotificationDao
  ├──► ExpenseDao
  ├──► SourceStatsDao
  ├──► ReceiptLinkService
  ├──► TransactionLifecycleCoordinator
  ├──► BudgetMonitor
  └──► HybridExpenseClassifier
       │
       ▼
  TransactionLifecycleCoordinator.createExpense()
       │
       ▼
  ExpenseDao / TransactionEventDao
```

### Consumer Classes (what depends on notification capture)

| Consumer | File | Dependency |
|----------|------|------------|
| `TransactionsViewModel` | `ui/screens/transactions/TransactionsViewModel.kt` | `NotificationRepository` |
| `ReviewViewModel` | `ui/screens/review/ReviewViewModel.kt` | `NotificationRepository`, `ReviewQueueRepository` |
| `DebugViewModel` | `ui/screens/debug/DebugViewModel.kt` | `NotificationRepository` |
| `CategorizationDebugViewModel` | `ui/screens/debug/CategorizationDebugViewModel.kt` | `CategorizationEngine` |
| `NotificationIntakeCoordinator` | `domain/notification/capture/NotificationIntakeCoordinator.kt` | `NotificationCaptureGate`, `NotificationCaptureDeduper`, `NotificationIntakeDao` |
| `NotificationIntakeWorker` | `worker/NotificationIntakeWorker.kt` | `NotificationIntakeDao`, `NotificationRepository`, `NotificationProcessingPipeline` |

---

## 2. Transaction Lifecycle Dependency Map

```
ALL expense creation paths route through:
  AddExpenseViewModel / ReceiptScanViewModel / ReviewViewModel /
  GroupsRepositoryImpl / BankApiIntegration / EmailReceiptIngestionService ...
       │
       ▼
TransactionLifecycleCoordinator          [domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt]
       │
       ├──► Validate (CreateExpenseRequest)
       ├──► Normalize (MerchantNormalizer, CategoryLookup)
       ├──► Dedupe (DuplicateDetectionPolicy)
       ├──► CurrencySettingsRepository.homeCurrency()  (home-currency snapshot source)
       ├──► insertAtomic (ExpenseDao) — ACID via withTransaction
       ├──► Event log (TransactionEventDao.insert())
       │
       │   SideEffectMode parameter (IMMEDIATE | DEFER)
       │   passed by every caller of createExpense():
       │     • ManualExpenseRepository     → SideEffectMode.IMMEDIATE
       │     • ReviewQueueRepository       → SideEffectMode.DEFER
       │     • ReceiptRepository           → SideEffectMode.IMMEDIATE
       │     • ExpenseRepository           → SideEffectMode.IMMEDIATE
       │     • GroupTransactionCoordinator → SideEffectMode.IMMEDIATE
       │     • EmailReceiptIngestionService→ SideEffectMode.IMMEDIATE
       │     • BankApiIntegration          → SideEffectMode.IMMEDIATE
       │   DEFER delays side effects until after the DB transaction commits,
       │   preventing foreign-key / consistency issues in deferred flows.
       │
       ▼
TransactionSideEffectDispatcher          [domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt]
  │  Compatibility facade — delegates to Planner + PostCommitActionRunner
  │  (retained for backward compat; new callers go through Planner directly)
  │
  ▼
TransactionSideEffectPlanner             [domain/transaction/lifecycle/TransactionSideEffectPlanner.kt]
  │  (builds PostCommitAction batches for onCreated/onUpdated/onDeleted)
  │
  ▼
PostCommitActionRunner                   [domain/sideeffect/PostCommitActionRunner.kt]
  │  (executes side-effect batches after DB transaction commits)
  │
  ├──► PostCommitActionBatch             [domain/sideeffect/PostCommitActionBatch.kt]
  ├──► SideEffectPriority                [domain/sideeffect/SideEffectPriority.kt]
  ├──► SideEffectTriggerType             [domain/sideeffect/SideEffectTriggerType.kt]
  ├──► SideEffectCategory               [domain/sideeffect/SideEffectCategory.kt]
  ├──► PostCommitActionRunnerImpl        [domain/sideeffect/PostCommitActionRunnerImpl.kt]
  │
  ├──► BudgetMonitor.checkBudget()
  ├──► AnomalyAlertOrchestrator.checkAndAlert()
  ├──► RecurringLifecycleCoordinator.linkExpenseToOccurrence()
  ├──► MerchantCategoryRepository.learnPattern()
  └──► MerchantNormalizationRepository   [data/repository/MerchantNormalizationRepository.kt]
```

### Full Call Chain

```
ViewModel
  └──► ManualExpenseRepository / ExpenseRepository
        └──► TransactionLifecycleCoordinator
              ├──► ExpenseDao
              ├──► TransactionEventDao
              ├──► BudgetMonitor → BudgetDao, CategoryDao
              ├──► AnomalyDetector → AnomalyAlertDao
              ├──► RecurringLifecycleCoordinator
              │     ├──► RecurringOccurrenceDao
              │     └──► RecurringReminderDeliveryDao
              └──► Side effects dispatcher
```

### Consumer Classes (12+ callers of TransactionLifecycleCoordinator)

| Consumer | File | Path |
|----------|------|------|
| `ManualExpenseRepository` | `data/repository/ManualExpenseRepository.kt` | Manual entry |
| `ReviewQueueRepository` | `data/repository/ReviewQueueRepository.kt` | Review approval |
| `ReceiptRepository` | `data/repository/ReceiptRepository.kt` | Receipt scan |
| `ExpenseRepository` | `data/repository/ExpenseRepository.kt` | Core expense CRUD |
| `GroupTransactionCoordinator` | `data/database/GroupTransactionCoordinator.kt` | Atomic group ops |
| `EmailReceiptIngestionService` | `data/email/EmailReceiptIngestionService.kt` | Email receipts |
| `BankApiIntegration` | `domain/bank/BankApiIntegration.kt` | Bank sync |
| `BusinessExpenseRepository` | `data/repository/BusinessExpenseRepository.kt` | Business expense report |
| `FinancialRescueCoordinator` | `data/rescue/FinancialRescueCoordinator.kt` | SQLite rescue import |

### Targeted Update Methods (Phase C migration)

TransactionLifecycleCoordinator now provides 8 targeted single-field/bulk update methods,
each writing TransactionEvent.UPDATED or BULK_UPDATED with before/after snapshots:

| Method | Updates | Event |
|--------|---------|-------|
| updateCategory(expenseId, newCategoryId) | categoryId | UPDATED |
| updateMerchant(expenseId, newMerchant) | merchant, merchantKey, dedupeKey | UPDATED |
| updateType(expenseId, newType) | transactionType, dedupeKey | UPDATED |
| updateTransferDetails(expenseId, direction, accountName) | transferDirection, transferAccountName | UPDATED |
| updateOwnership(expenseId, ...) | isNotMine, ownerName, isSharedExpense, sharedWithName, mySharePercentage, myShareAmount | UPDATED |
| updateLocation(expenseId, lat, lng, ...) | latitude, longitude, locationSource, placeId, resolvedAddress | UPDATED |
| bulkUpdateCategory(merchant, newCategoryId) | categoryId (all matching rows) | BULK_UPDATED |
| bulkUpdateMerchant(oldMerchant, newMerchant) | merchant, merchantKey, dedupeKey (all matching rows) | BULK_UPDATED |

### Side-Effect Dispatchers → Planner → PostCommitActionRunner (Phase C + Engine 3)

TransactionSideEffectDispatcher is now a **compatibility facade** that delegates
to TransactionSideEffectPlanner + PostCommitActionRunner. The planner builds
typed PostCommitAction batches; the runner executes them after the DB transaction
commits. The full side-effect framework lives in `domain/sideeffect/` (19 files).

```
TransactionLifecycleCoordinator.createExpense()
  │
  ├──► TransactionSideEffectDispatcher.dispatchOnCreated()  (facade)
  │     └──► TransactionSideEffectPlanner.planOnCreated()
  │           └──► PostCommitActionRunner.runAll()
  │                 ├──► BudgetMonitor.checkBudget()
  │                 ├──► AnomalyAlertOrchestrator.checkAndAlert()
  │                 ├──► RecurringLifecycleCoordinator.linkExpenseToOccurrence()
  │                 └──► MerchantCategoryRepository.learnPattern()
  │
  └──► TransactionSideEffectPlanner (direct — new callers)
        └──► PostCommitActionRunner
```

| Method | Called by | Systems |
|--------|-----------|---------|
| dispatchOnCreated(expenseId, source) | createExpense() | budget, anomaly, merchant learning, recurring linking |
| dispatchOnUpdated(expenseId, source) | updateCategory, updateMerchant, updateType, updateTransferDetails, updateOwnership | budget, anomaly, merchant learning |
| dispatchOnDeleted(expenseId, source) | deleteExpense() | budget |

All three are best-effort (fire-and-forget, wrapped in try-catch).

All methods: restore-mode guard, atomic DB transaction, lifecycle event logging.
See ExpenseRepository KNOWN BYPASS NOTE for migration status (11 routed, 7 intentional).

---

## 3. Receipt Lifecycle Dependency Map

```
ReceiptScanScreen / ReviewScreen / EmailReceiptIngestionService
       │
       ▼
ReceiptLifecycleCoordinator              [domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt]
       │
       ├──► ReceiptInputValidator        — URI/MIME/size validation
       ├──► ReceiptAssetStore            — File persistence + SHA-256 hash
       ├──► ReceiptOcrService            — OCR extraction
       ├──► ReceiptParser                — Structured parsing
       ├──► ReceiptDuplicateDetector     — 3-signal dedup (hash/text/semantic)
       ├──► ScannedReceiptDao            — Save entity
       ├──► ReceiptEventDao              — Lifecycle event log
       ├──► RawContentSanitizer          — Sanitizes raw OCR text
       │
       ▼
ReceiptSideEffectDispatcher              [domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt]
  │  Compatibility facade → delegates to ReceiptSideEffectPlanner + PostCommitActionRunner
  │
  ▼
ReceiptSideEffectPlanner                 [domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt]
  │
  ▼
PostCommitActionRunner                   [domain/sideeffect/PostCommitActionRunner.kt]
        │
        ├──► AutoCreateWarrantyFromReceiptUseCase
        │     └──► WarrantyDao, ReturnWindowDao
        ├──► ReceiptItemCategorizationService
        │     └──► ReceiptItemCategorizationDao
        ├──► ReceiptTransactionMatcher    [domain/receiptmatching/ReceiptTransactionMatcher.kt]
        └──► PriceProtectionTracker       [domain/price/PriceProtectionTracker.kt]

BankStatementLifecycleProcessor           [domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt]
  │  Processes bank statement imports through the receipt lifecycle
  ├──► ReceiptParser
  ├──► ReceiptAssetStore
  ├──► ReceiptInputValidator
  ├──► ScannedReceiptDao
  └──► ReceiptEventDao

ReceiptLinkService                        [domain/receipt/lifecycle/ReceiptLinkService.kt]
       │   Constructor dependencies (9 total):
       │   ├──► AppDatabase              — Transactional coordination
       │   ├──► ReceiptExpenseLinkDao    — Many-to-many link table
       │   ├──► ReceiptEventDao          — Link/unlink audit events
       │   ├──► ExpenseDao               — Cross-reference
       │   ├──► ScannedReceiptDao        — Verify receipt exists
       │   ├──► ReceiptItemCategorizationDao — RCP-30 category propagation
       │   ├──► WarrantyDao              — Auto-create warranty on link
       │   ├──► ReturnWindowDao          — Auto-create return window on link
       │   └──► TimeProvider             — Timestamps for link events
       │
       │   Behavioral notes:
       │   1. Validates expense exists before linking (returns failure fast)
       │   2. Checks insert() return value to detect and report duplicates
       │   3. Propagates item-majority category to expense via RCP-30
        │      (reads ReceiptItemCategorization rows, picks majority category,
        │       writes it back to Expense.categoryId via ExpenseDao.update())

Pre-OCR exact-hash dedup: ReceiptRepository.processReceipt() now computes
ReceiptAssetStore.computeUriHash() BEFORE OCR. If an exact-hash match exists,
OCR/parse/insert are skipped entirely.
```

### Entity & DAO Flow

```
Receipt Source (Camera/Gallery/Email/File)
  → ScannedReceipt (entity) → ScannedReceiptDao
  → ReceiptEvent (entity) → ReceiptEventDao
  → ReceiptExpenseLink (entity) → ReceiptExpenseLinkDao
  → Expense (entity) → ExpenseDao
  → ReceiptItemCategorization (entity) → ReceiptItemCategorizationDao
```

### ReceiptMatchLifecycleService (P3)

```
ReceiptMatchLifecycleService              [domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt]
  │  @Singleton @Inject
  │  4 lifecycle-aware receipt match mutation methods:
  │    saveMatchSuggestion(receiptId, expenseId, score)     → ReceiptEvent.MATCH_SUGGESTED
  │    approveMatchSuggestion(receiptId)                     → ReceiptEvent.MATCH_APPROVED
  │    rejectMatchSuggestion(receiptId)                      → ReceiptEvent.MATCH_REJECTED
  │    clearMatch(receiptExpenseLinkId)                      → ReceiptEvent.MATCH_CLEARED
  │
   ├──► AppDatabase (withTransaction for atomicity)
   ├──► DatabaseWriteBarrier (restore-safety gate)
   ├──► ScannedReceiptDao (update matchedExpenseId)
   ├──► ReceiptEventDao (insert lifecycle event)
   └──► TimeProvider (timestamps)
       │
       ▼
  Consumed by:
  ├──► ReceiptMatchingWorker          — Auto-match: saveMatchSuggestion()
  ├──► ReceiptRepository              — Delegates save/approve/reject/clear from non-lifecycle code paths
  │     └──► saveMatchSuggestion, approveMatchSuggestion (in ReceiptRepository)
  └──► ReceiptMatchingViewModel       — User-match UI: saveMatchSuggestion()
```

### Consumer Classes

| Consumer | File | Dependency |
|----------|------|------------|
| `ReceiptScanViewModel` | `ui/screens/receiptscan/ReceiptScanViewModel.kt` | `ReceiptLifecycleCoordinator`, `ReceiptRepository` |
| `ReviewViewModel` | `ui/screens/review/ReviewViewModel.kt` | `ReceiptLifecycleCoordinator`, `ReceiptRepository` |
| `ReceiptMatchingViewModel` | `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt` | `ReceiptRepository`, `ExpenseRepository`, `ReceiptMatchLifecycleService` |
| `ReceiptRepository` | `data/repository/ReceiptRepository.kt` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `ReceiptMatchLifecycleService` |
| `WarrantyTrackerRepository` | `data/repository/WarrantyTrackerRepository.kt` | `AutoCreateWarrantyFromReceiptUseCase` |
| `DashboardContractsAdapter` | `data/repository/DashboardContractsAdapter.kt` | Receipt counts |

---

## 4. Recurring Lifecycle Dependency Map

```
RecurringExpensesScreen
  │
  ▼
RecurringLifecycleCoordinator             [domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt]
   │   Constructor dependencies (10 total):
   │   ├──► RecurringOccurrenceExpander           — Expand rule → occurrence candidates
   │   ├──► OccurrenceConflictResolver            — Resolve candidates vs actual expenses
   │   ├──► RecurringOccurrenceMaterializer       — Persist + create reminders
   │   │     │
   │   │     ├──► RecurringOccurrenceDao          — INSERT IGNORE / UPDATE
   │   │     ├──► RecurringReminderDeliveryDao    — Reminder rows
   │   │     └──► RecurringLifecycleEventDao      — Audit log
   │   ├──► ExpenseDao                            — Cross-reference actual expenses
   │   ├──► TimeProvider                          — Due-date / reminder scheduling
   │   ├──► ManualRecurringExpenseDao             — Read recurring rule definitions
   │   ├──► RestoreMaintenanceMode                — Write gate: skip writes during restore
   │
   ▼
RecurringPlanProjectionService            [domain/recurring/RecurringPlanProjectionService.kt]
   │
   └──► PlannedExpenseDao                  — Materialise planned expenses

RecurringRuleLifecycleCoordinator          [domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt]
   │  @Singleton @Inject
   │  Single writer for ALL rule-level lifecycle mutations (P4):
   │    createRule()   → creates rule + generates occurrences + events
   │    updateRule()   → updates rule name/amount/pattern
   │    activateRule() → sets isActive=true + generates occurrences
   │    deactivateRule() → sets isActive=false, deletes PLANNED occurrences
   │    deleteRule()   → deletes rule + all occurrences/reminders/events
   │
   ├──► AppDatabase (withTransaction for atomicity)
   ├──► DatabaseWriteBarrier (restore-safety gate)
   ├──► ManualRecurringExpenseDao (read/update rule definition)
   ├──► RecurringOccurrenceDao (create/delete/update occurrence rows)
   ├──► RecurringReminderDeliveryDao (suppress/delete reminders)
   ├──► PlannedExpenseDao (cancel planned expenses)
   ├──► RecurringLifecycleEventDao (audit log)
   └──► TimeProvider (timestamps)
        │
        ▼
  Consumed by:
  ├──► ManualRecurringExpenseRepository        — Delegates CRUD
  ├──► RecurringExpensesViewModel              — Deactivate/delete UI actions
  └──► BillReminderManager                     — Rule lifecycle integration (deprecated)

RecurringLifecycleEventWriter              [domain/recurring/lifecycle/RecurringLifecycleEventWriter.kt]
   │  @Singleton @Inject
   │  Dual-channel event writer (P4):
   │    writeCritical(ruleId, type, payload) → Long (returns eventId)
   │    writeDiagnostic(ruleId, type, payload, parentEventId)
   │
   ├──► RecurringLifecycleEventDao (insert events)
   └──► TimeProvider (timestamps)

P4 Data Types (no DAO dependencies, pure domain models):
  ├──► OccurrenceGenerationOptions              — Controls reminder creation during generation
  ├──► RecurringExpenseReconcileResult          — Sealed interface: Linked, AlreadyLinked, NotFound, Conflict, etc.
  └──► RecurringOccurrenceStatus                — Typed enum: PLANNED/PAID/SKIPPED/MISSED/CANCELLED/IGNORED + transition policy

BillReminderSettings                       [domain/reminder/BillReminderSettings.kt]
  │  Data class: enabled, quietHoursStart, quietHoursEnd, dispatchInterval
  │
  ▼
BillReminderSettingsRepository             [domain/reminder/BillReminderSettingsRepository.kt]
  │  Interface + impl (BillReminderSettingsRepositoryImpl)
  │  Bound via ReminderSettingsModule
  │
  ▼
BillReminderWorker                         [service/reminder/BillReminderWorker.kt]
  └──► Checks runtime settings before dispatching notifications

TransactionLifecycleCoordinator
   └──► RecurringLifecycleCoordinator.linkExpenseToOccurrence()  — auto-link hook
   └──► RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expenseId) — direct
         linkedExpenseId lookup via getByLinkedExpenseId(), resets occurrence to PLANNED.

SnoozeReminderReceiver / DismissReminderReceiver
   ├──► RecurringReminderDeliveryDao
   ├──► TimeProvider
   └──► RestoreMaintenanceMode (write gate)
```

### Consumer Classes

| Consumer | File | Dependency |
|----------|------|------------|
| `RecurringExpensesViewModel` | `ui/screens/recurring/RecurringExpensesScreen.kt` | `RecurringExpenseRepository` |
| `ManualRecurringExpenseViewModel` | `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt` | `ManualRecurringExpenseRepository` |
| `HomeViewModel` | `ui/screens/home/HomeViewModel.kt` | `PlannedExpenseRepository` |
| `BudgetViewModel` | `ui/screens/budget/BudgetViewModel.kt` | Offset engine |
| `FinancialWeatherRepository` | `data/repository/FinancialWeatherRepository.kt` | `RecurringExpenseRepository`, `PlannedExpenseRepository` |
| `CashFlowCalculator` | `domain/cashflow/CashFlowCalculator.kt` | `RecurringLifecycleCoordinator` |
| `ForecastInputAssembler` | `domain/forecasting/ForecastInputAssembler.kt` | `RecurringLifecycleCoordinator` |
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | `RecurringLifecycleCoordinator.getDueReminders()`, `BillReminderSettingsRepository` |
| `BillRemindersViewModel` | `ui/screens/reminder/BillRemindersViewModel.kt` | `BillReminderManager`, `RecurringLifecycleCoordinator` |
| `RecurringRuleLifecycleCoordinator` | `domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | Single writer for rule CRUD (consumed by ViewModel) |
| `RecurringArchitectureGuardTest` | `test/.../RecurringArchitectureGuardTest.kt` | 19 static enforcements for single-writer + typed results |

---

## 5. Backup/Restore Dependency Map

```
BackupRestoreScreen (UI)
  │
  ▼
DatabaseBackupRepositoryImpl              [data/repository/DatabaseBackupRepositoryImpl.kt]
  │
  ├──► CostbackupBundle                   — AES-256-GCM encrypted ZIP
  │     ├── Header (metadata)
  │     ├── Manifest (file list + checksums)
  │     ├── Database (AppDatabase backup)
  │     ├── Receipt images (from ReceiptAssetStore)
  │     └── Checksums (SHA-256)
  │
  ├──► BackupEncryptionService            — AES-256-GCM / PBKDF2
  ├──► ExportAnonymizer                   — Strips raw OCR/notification text
  ├──► PrivacyGate.check(RAWBACKUP_EXPORT | ENCRYPTED_BACKUP)
  ├──► RestoreJournal                     — Crash-safe 9-state journal (ASSETS_RESTORING,
  │     EXPORTING, RESTORING_DATABASE, RESTORING_ASSETS, FINALIZING, COMPLETED, FAILED,
  │     CANCELLING, CANCELLED)
  └──► RestoreMaintenanceMode             — Pauses 7 workers during restore (uses
        WORKER_REGISTRY; new BACKUP_EXPORTING mode; pauseAllWorkers()/resumeAllWorkers()
        via WorkerRegistry.entries)
       │
       ▼
  TransactionLifecycleCoordinator         — Restore uses SKIP_FOR_DEBUG_RESTORE dedup mode

AppStartupCoordinator
  └──► checkRestoreJournal()              — Crash recovery on every startup
       └──► RestoreJournal.checkAndRecover()
            └──► RecoveryResult states
```

### Consumers

| Consumer | File | Dependency |
|----------|------|------------|
| `BackupRestoreViewModel` | `ui/screens/backup/BackupRestoreViewModel.kt` | `DatabaseBackupRepository` |
| `AppStartupCoordinator` | `startup/AppStartupCoordinator.kt` | `RestoreJournal`, `RestoreMaintenanceMode`, `WorkerRegistry` |
| `NotificationCaptureService` | `service/NotificationCaptureService.kt` | `RestoreMaintenanceMode.isActive()` |
| All 7 workers | Various | `RestoreMaintenanceMode` pause check via `WorkerExecutionGuard` |

### AccountBalanceProvider Dependency Chain (2026-05-12)

```
AccountBalanceProvider                    [domain/forecasting/AccountBalanceProvider.kt]
  │  Interface with currentBalance(currency)
  │
  └──► NetCashflowBalanceProvider         [domain/forecasting/NetCashflowBalanceProvider.kt]
        │  @Singleton @Inject (fallback)
        │
        ├──► MultiCurrencyRepository      — getHomeCurrencyDepositTotal() + getHomeCurrencyPurchaseTotal()
        └──► TimeProvider                 — 90-day window calculation
             │
             ▼
        FinancialStressForecastEngine     — cashflow-aware stress testing
```

### RecurringRuleLifecycleCoordinator Dependency Chain (2026-05-12)

```
RecurringRuleLifecycleCoordinator          [domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt]
  │  @Singleton @Inject
  │  Rule-level lifecycle mutations (deactivate, delete)
  │
  ├──► AppDatabase                        — withTransaction for atomicity
  ├──► DatabaseWriteBarrier               — Restore-safety gate
  ├──► ManualRecurringExpenseDao          — Read rule definition, set isActive=false
  ├──► RecurringOccurrenceDao             — Cancel future PLANNED occurrences
  ├──► RecurringReminderDeliveryDao       — Suppress reminders
  ├──► PlannedExpenseDao                  — Cancel planned expenses
  ├──► RecurringLifecycleEventDao         — Audit log
  └──► TimeProvider                       — Timestamps
       │
       ▼
  Consumed by:
  └──► RecurringExpensesViewModel         — Deactivate/delete UI actions
  └──► BillReminderManager                — Rule lifecycle integration
```

### PrivacyDecision Fail-Closed Chain (2026-06-01)

```
PrivacyDecision.FailClosed(reason)         [domain/privacy/PrivacyDecision.kt]
  │  New sealed variant alongside Allowed, Denied, NotApplicable
  │
  ├──► blocksExecution(): Boolean         — Returns true for Denied + FailClosed
  └──► reason(): String                   — Returns reason for all variants (safe to call without smart-cast)
       │
       ▼
  Used by 30+ callers across 10+ files:
  ├──► DatabaseBackupRepositoryImpl
  ├──► NotificationCaptureService
  ├──► CompositeGeocodingService (all 4 providers)
  ├──► CloudDashboardBriefingService
  ├──► CloudCategorizationAssistService
  ├──► CloudDedupeJudgeService
  ├──► CloudQueryInterpretationService
  ├──► CloudReceiptAssistService
  ├──► CloudReceiptItemCategorizationService
  ├──► CloudReviewExplanationService
  ├──► CloudWarrantyExtractionService
  ├──► SmartReceiptAssistService
  ├──► DailyBriefingWorker
  ├──► DataRetentionWorker
  ├──► LocationBackfillWorker
  ├──► OverpassNearbyService
  └──► AndroidForegroundLocationProvider
```

### AccountingExportPolicy Dependency Chain (2026-06-01)

```
AccountingExportPolicy                    [domain/export/AccountingExportPolicy.kt]
  │  @Inject constructor (no module needed)
  │
  ├──► requireSingleCurrency(transactions, exportName)     — validates all rows share same currency
  ├──► requirePurchaseTransactions(transactions, exportName) — validates all rows are purchases
  └──► validateGlobalDataset(transactions, exportName)      → GlobalDatasetValidation
        (rowCount, distinctCurrencies, transactionTypes, isSingleCurrency, isPurchaseOnly, errors)
  
  Used by:
  └──► ExportOptionsViewModel             — Pre-export validation
  └──► AccountingExporters                — Export pipeline validation
```

### Barriers & Infrastructure (17 files in data/backup/)

```
DatabaseReadBarrier                       [data/backup/DatabaseReadBarrier.kt]
  └── Constructor: RestoreMaintenanceMode
  └── checkReadAllowed(operation) — throws IllegalStateException during restore
      (NORMAL and BACKUP_EXPORTING modes pass through)

DatabaseReadBarrierFlowExt                [data/backup/DatabaseReadBarrierFlowExt.kt]
  └── Flow extension for read-barrier-guarded reactive streams

DatabaseWriteBarrier                      [data/backup/DatabaseWriteBarrier.kt]
  └── Constructor: RestoreMaintenanceMode
  └── checkWritesAllowed(operation) — throws IllegalStateException during restore
      (delegates to RestoreMaintenanceMode.isWritesAllowed())

MaintenanceOperationRunner                [data/backup/MaintenanceOperationRunner.kt]
  └── Manages backup/restore as maintenance operations with lifecycle hooks

AppOperationalState                       [data/backup/AppOperationalState.kt]
  └── Tracks NORMAL / BACKUP_EXPORTING / RESTORING operational modes

RestoreJournalImporter                    [data/backup/RestoreJournalImporter.kt]
  └── Handles import of restore journal from backup bundles

SqliteSnapshotCreator                     [data/backup/SqliteSnapshotCreator.kt]
  └── Creates SQLite-level snapshots for crash-safe backup

BackupVerifier                            [data/backup/BackupVerifier.kt]
  └── Post-restore integrity verification

RestoreDatabaseOpener                     [data/backup/RestoreDatabaseOpener.kt]
  └── Opens database in restore context with special write permissions

RestoreInternalWriteScope                 [data/backup/RestoreInternalWriteScope.kt]
  └── Scoped write access during restore for internal operations

RestoreDiagnosticsSink                    [data/backup/RestoreDiagnosticsSink.kt]
  └── Diagnostic logging during restore operations
```

### Workers paused during restore (core 7 via WorkerRegistry + 2 independent)

| Worker | File | Normal Schedule |
|--------|------|-----------------|
| `DailyBriefingWorker` | `data/ai/worker/DailyBriefingWorker.kt` | Every 24h |
| `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` | Every 12h |
| `MerchantKeyBackfillWorker` | `data/location/MerchantKeyBackfillWorker.kt` | One-shot |
| `WarrantyExpirationWorker` | `service/warranty/WarrantyExpirationWorker.kt` | Every 24h |
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | Every 6h |
| `ReceiptMatchingWorker` | `service/receiptmatching/ReceiptMatchingWorker.kt` | Every 2h |
| `DataRetentionWorker` | `data/privacy/DataRetentionWorker.kt` | Every 24h |
| `SourceLinkBackfillWorker` | `domain/provenance/SourceLinkBackfillWorker.kt` | One-shot (backfill) |
| `NotificationIntakeWorker` | `worker/NotificationIntakeWorker.kt` | On-demand (intake queue) |

---

## 6. Privacy Gate Dependency Map

```
PrivacySettingsScreen (UI)
  │
  ▼
PrivacySettingsViewModel                  [ui/screens/privacysettings/PrivacySettingsViewModel.kt]
  │
  ▼
PrivacySettingsRepositoryImpl             [data/privacy/PrivacySettingsRepositoryImpl.kt]
  │  (DataStore-backed, 10 toggles + 2 retention settings)
  │
  ▼ (read by)
CompositePrivacyGate                      [domain/privacy/CompositePrivacyGate.kt]
  │
  ├──► NotificationPrivacyGate            — NOTIFICATION_CAPTURE, NOTIFICATION_PACKAGE_ALLOWLIST
  │     └──► NotificationCaptureGate      [domain/privacy/NotificationCaptureGate.kt]
  ├──► LocationPrivacyGate                — EXTERNAL_GEOCODING, BACKGROUND_LOCATION_BACKFILL, GPS, OVERPASS
  ├──► CloudAiPrivacyGate                 — CLOUD_AI_* capabilities, RECEIPT_IMAGE_CLOUD_UPLOAD
  ├──► BackupPrivacyGate                  — RAWBACKUP_EXPORT, ENCRYPTED_BACKUP
  └──► ExportPrivacyGate                  — EXPORT_* capabilities
       │
       ▼
  PrivacyDecision (Allowed | Denied | FailClosed)

PrivacyAuditLogger                        [domain/privacy/PrivacyAuditLogger.kt]
  │  Interface + impl (PrivacyAuditLoggerImpl in data/privacy/)
  │  (logs every gate check → PrivacyAuditEvent entity → PrivacyAuditDao)
  │  (now logs PrivacyBlocked subclass type via `privacyBlockedType: String`)
  │
  ▼
PrivacyAuditEvent → PrivacyAuditDao       [data/database/entity + dao]

PrivacyBlocked                            [domain/privacy/PrivacyBlocked.kt]
  │  Sealed interface with concrete subclasses for standardized privacy-denied
  │  states. Returned by all 4 privacy gates instead of ad-hoc Denied(reason).
  │
  ├──► CloudAiDisabled
  ├──► ReceiptImageUploadDisabled
  ├──► ExternalGeocodingDisabled
  ├──► NotificationCaptureDisabled
  ├──► RawExportDisabled
  └──► Custom(capability, reason)

PrivacyCapability                         [domain/privacy/PrivacyCapability.kt]
  │  Enum of all gated capabilities

PrivacyCapabilityHandlingPolicy           [domain/privacy/PrivacyCapabilityHandlingPolicy.kt]
  │  Policy resolution for capability gating

PrivacySettings                           [domain/privacy/PrivacySettings.kt]
  │  Domain model for all privacy toggles

AtRestEncryptionService                   [data/privacy/AtRestEncryptionService.kt]
  │  (AES-256-GCM via Android Keystore for ML model data at rest)
  │  (Encrypts sensitive ML data before writing to disk, decrypts on read)
  │  (Key stored in hardware-backed Android Keystore, not extractable)

RedactionSanitizer                        [domain/privacy/RedactionSanitizer.kt]
  │  (PII redaction before cloud AI calls)
  │
  ▼
  Used by: CloudReceiptItemCategorizationService, CloudDashboardBriefingService, etc.

RawContentSanitizer                       [domain/privacy/RawContentSanitizer.kt]
  │  (Sanitizes raw OCR text and notification content based on RawStorageMode)
  │  (Used by ReceiptLifecycleCoordinator and EmailReceiptIngestionService)
  │
  ▼
RawStorageMode                            [domain/privacy/RawStorageMode.kt]
  │  (Enum: STORE_RAW, REDACT, STRIP)

RawPersistencePolicy                      [domain/privacy/RawPersistencePolicy.kt]
RawPersistencePolicyResolver              [domain/privacy/RawPersistencePolicyResolver.kt]
RawSourceType                             [domain/privacy/RawSourceType.kt]
  │  Determines persistence behavior per source type (notification/email/receipt/bank)

EffectiveCloudAiPolicy                    [domain/privacy/EffectiveCloudAiPolicy.kt]
  │  (Resolves effective cloud AI policy based on settings + capability)
  │  (Used by HybridRouter via CloudPayloadPolicy)

CloudPayloadPolicy                        [domain/privacy/CloudPayloadPolicy.kt]
  │  (Interface — replaces CloudPayloadRedactor — controls which fields are sent to cloud AI)
  │  (Used by all hybrid cloud AI services)
  │
  ▼
DefaultCloudPayloadPolicy                 [data/privacy/DefaultCloudPayloadPolicy.kt]
DefaultCloudPayloadRedactor               [data/privacy/DefaultCloudPayloadRedactor.kt]
  │  Data-layer implementations

SensitiveHashingService                   [domain/privacy/SensitiveHashingService.kt]
  │  Interface
  ▼
DefaultSensitiveHashingService            [data/privacy/DefaultSensitiveHashingService.kt]

RetentionRegistry                         [domain/privacy/RetentionRegistry.kt]
RetentionTarget                           [domain/privacy/RetentionTarget.kt]
  │  Registry of 5 data retention targets for DataRetentionWorker

Persistence Payloads (per-source-type privacy wrappers):
  ├── NotificationPersistencePayload       [domain/privacy/NotificationPersistencePayload.kt]
  ├── EmailReceiptPersistencePayload        [domain/privacy/EmailReceiptPersistencePayload.kt]
  ├── ReceiptPersistencePayload             [domain/privacy/ReceiptPersistencePayload.kt]
  ├── BankTransactionPersistencePayload     [domain/privacy/BankTransactionPersistencePayload.kt]
  └── PreparedCloudPayload                  [domain/privacy/PreparedCloudPayload.kt]

SafePrivacyMetadata                        [domain/privacy/SafePrivacyMetadata.kt]
PrivacyAuditContext                        [domain/privacy/PrivacyAuditContext.kt]

### Gate Consumers (who calls PrivacyGate.check())

| Capability | Called By | File |
|-----------|-----------|------|
| `NOTIFICATION_CAPTURE` | `NotificationCaptureService` | `service/NotificationCaptureService.kt` |
| `CLOUD_AI_RECEIPT_ASSIST` | `SmartReceiptAssistService` | `data/ai/provider/SmartReceiptAssistService.kt` |
| `CLOUD_AI_CATEGORIZATION_ASSIST` | `HybridCategorizationAssistService` | `data/ai/provider/HybridCategorizationAssistService.kt` |
| `CLOUD_AI_DEDUPE_JUDGE` | `HybridDedupeJudgeService` | `data/ai/provider/HybridDedupeJudgeService.kt` |
| `CLOUD_AI_BRIEFING` | `HybridDashboardBriefingService` | `data/ai/provider/HybridDashboardBriefingService.kt` |
| `CLOUD_AI_REVIEW_EXPLANATION` | `HybridReviewExplanationService` | `data/ai/provider/HybridReviewExplanationService.kt` |
| `CLOUD_AI_QUERY_INTERPRETATION` | `HybridQueryInterpretationService` | `data/ai/provider/HybridQueryInterpretationService.kt` |
| `EXTERNAL_GEOCODING` | `CompositeGeocodingService` | `data/location/CompositeGeocodingService.kt` |
| `BACKGROUND_LOCATION_BACKFILL` | `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` |
| `RAWBACKUP_EXPORT` | `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` |
| `ENCRYPTED_BACKUP` | `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` |
| `CLOUD_AI_WARRANTY_EXTRACTION` | `CloudWarrantyExtractionService` | `data/ai/provider/CloudWarrantyExtractionService.kt` |
| `CLOUD_AI_RECEIPT_ITEM_CATEGORIZATION` | `HybridReceiptItemCategorizationService` | `data/ai/provider/HybridReceiptItemCategorizationService.kt` |
| `DEVICE_GPS_LOCATION` | `AndroidForegroundLocationProvider` | `data/location/AndroidForegroundLocationProvider.kt` |
| `OVERPASS_API` | `OverpassNearbyService` | `data/location/OverpassNearbyService.kt` |
| `BACKUP_EXPORT` | `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` |

---

## 7. Dashboard/Analytics/Currency Dependency Map

```
HomeScreen
  │
  ▼
HomeViewModel                              [ui/screens/home/HomeViewModel.kt]
  │
  ├──► DashboardRepository                 [data/repository/DashboardRepository.kt]
  │     └── SharedPreferences (widget layout)
  │
  ├──► ComputeDashboardWidgetsUseCase      [domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt]
  │     └──► DashboardDataProvider
  │           ├──► DashboardExpenseRepository (adapter)
  │           ├──► DashboardCategoryRepository (adapter)
  │           ├──► DashboardBudgetRepository (adapter)
  │           ├──► DashboardReviewQueueRepository (adapter)
  │           ├──► DashboardFinancialWeatherRepository (adapter)
  │           ├──► DashboardSavingsGoalRepository (adapter)
  │           └──► DashboardAnalyticsRepository (adapter)
  │
  ├──► TotalsAggregationEngine             [domain/analytics/TotalsAggregationEngine.kt]
  │     └──► MultiCurrencyRepository
          │     ├──► ExpenseDao
          │     ├──► CurrencyConverter
          │     ├──► CurrencySettingsRepository
          │     └──► TimeProvider
  │
  └──► AnalyticsRepository                 [data/repository/AnalyticsRepository.kt]
        ├──► ExpenseDao
        ├──► MultiCurrencyRepository
        └──► AnalyticsCurrencyNormalizer

MultiCurrencyRepository                    [data/repository/MultiCurrencyRepository.kt]
  │  (Currency-aware aggregation backbone — wired into 10+ pipelines)
  │
  ├──► ExpenseDao (getAllSpentBetweenByCurrency, etc.)
  ├──► CurrencyConverter (convertMultiple)
  ├──► CurrencySettingsRepository (homeCurrency)
  └──► TimeProvider

AnalyticsCurrencyNormalizer                [domain/analytics/AnalyticsCurrencyNormalizer.kt]
  │  (Per-expense home-currency normalization)
  │
  └──► CurrencyConverter
```

### Pipelines using MultiCurrencyRepository

| Pipeline | File |
|----------|------|
| Dashboard totals | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` |
| Budget status | `data/repository/BudgetRepository.kt` |
| Analytics summary | `data/repository/AnalyticsRepository.kt` |
| Forecast | `data/repository/FinancialWeatherRepository.kt` |
| Health score | `domain/health/FinancialHealthScoreV2.kt` |
| Savings | `domain/savings/SmartSavingsEngine.kt` |
| Groups | `data/repository/GroupsRepositoryImpl.kt` |
| Export | `data/repository/ExportDataRepository.kt` |
| AI/Query | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt` |
| Anomaly | `domain/analytics/AnomalyDetector.kt` |

---

## 8. Worker/Startup Dependency Map

```
MainApplication (@HiltAndroidApp)
  │
  └──► AppStartupDelegate (@EntryPoint)
        │
        └──► AppStartupCoordinator
              │
              ├──► checkRestoreJournal()                 → RestoreJournal
              ├──► registerLifecycleObserver()           → AppBackgroundLifecycleObserver
              └──► scheduleStartupWork()
                    │
                    ├── LocationBackfillWorker            [data/location/LocationBackfillWorker.kt]
                    │     └──► PrivacyGate, GeocodingService, ExpenseDao
                    │
                    ├── MerchantKeyBackfillWorker         [data/location/MerchantKeyBackfillWorker.kt]
                    │     └──► ExpenseDao, MerchantNormalizationDao
                    │
                    ├── WarrantyExpirationWorker          [service/warranty/WarrantyExpirationWorker.kt]
                    │     └──► WarrantyDao, WarrantyReminderDeliveryDao, NotificationService
                    │
                    ├── DataRetentionWorker               [data/privacy/DataRetentionWorker.kt]
                    │     └──► RawNotificationDao, ScannedReceiptDao, PrivacyAuditDao
                    │
                    ├── BillReminderWorker                [service/reminder/BillReminderWorker.kt]
                    │     └──► RecurringLifecycleCoordinator, NotificationService
                    │
                    └── ReceiptMatchingWorker             [service/receiptmatching/ReceiptMatchingWorker.kt]
                          └──► ReceiptRepository, ExpenseRepository

WorkerSpec defaults                       [domain/workers/WorkerSpec.kt]
  └── DEFAULTS map with specs for all 7 workers (interval, constraints, backoff).
      Separate existingWorkPolicy (periodic) + oneShotPolicy (one-shot); a version
      bump always forces REPLACE. merchant_key_backfill = REPLACE; ai_daily_briefing
      = KEEP (protects the midnight self-reschedule chain).

WorkerSpecScheduler                       [domain/workers/WorkerSpecScheduler.kt]
  └── Centralized scheduling object — all workers use instead of duplicating
      schedule logic. Reads WorkerSpec.DEFAULTS by worker name, handles
      version-change detection (force REPLACE when version bumps).
      Stateless Kotlin object, no DI needed.

WorkerRunLogger                           [domain/workers/WorkerRunLogger.kt]
  └── Interface + WorkerRunLoggerImpl (@Singleton @Inject). Per-run lifecycle:
      start() returns WorkerRunHandle with success/skipped/retry/failure methods.
      Writes BackgroundJobRun rows via BackgroundJobRunDao. Bound via WorkerModule.

WorkerExecutionGuard                      [domain/workers/WorkerExecutionGuard.kt]
  └── Structured guarded execution for workers. Checks RestoreMaintenanceMode,
      creates WorkerRunLogger handle, wraps work in try-catch, records outcome.
      Used by all 7 workers to replace ad-hoc per-worker logging. Enhanced with
      checkpoints/yield() for cooperative cancellation during long-running work.
      Enforces requiresNotificationPermission via NotificationPermissionChecker
      (durable skip with NOTIFICATION_PERMISSION_DENIED). Catch precedence:
      CancellationException (rethrow) → RetryableWorkerException (Retry) →
      classifyTransient(message/IOException) (Retry) → permanent Failed.

RetryableWorkerException                  [domain/workers/RetryableWorkerException.kt]
  └── Typed retry signal. Workers that intend a WorkManager retry must throw this;
      a plain RuntimeException with a non-transient message is a PERMANENT failure.
      Used by LocationBackfillWorker + MerchantKeyBackfillWorker no-progress paths.

WorkerDrainController                     [domain/workers/WorkerDrainController.kt]
  └── Interface for draining/running workers during maintenance windows.
      NoOpWorkerDrainController provides no-op impl for normal operation.

WorkerLease                               [domain/workers/WorkerLease.kt]
  └── Exclusive lease mechanism to prevent concurrent worker execution.
      WorkerLeaseRegistry + WorkerLeaseRegistryImpl manage lease state.

WorkerRunContext                          [domain/workers/WorkerRunContext.kt]
  └── Context object passed through guarded execution.

NotificationPermissionChecker             [domain/workers/NotificationPermissionChecker.kt]
  └── Interface + impl (AndroidNotificationPermissionChecker in data/service/).
      Checked by WorkerExecutionGuard for notification-dependent workers.

PrivacyRuntimeWorkerPolicy               [domain/workers/PrivacyRuntimeWorkerPolicy.kt]
  └── Runtime privacy policy checks for workers.

WorkerRegistry                            [domain/workers/WorkerRegistry.kt]
  └── Kotlin `object`. Centralized single-source-of-truth registry for all 7
      managed background workers. Each `Entry` has specName (matching WorkerSpec.DEFAULTS)
      and schedule lambda. `scheduleAll(context)` iterates entries with runCatching.
      Replaces hardcoded worker lists in RestoreMaintenanceMode and AppStartupCoordinator.
      
      Registry entries:
        location_backfill       → LocationBackfillWorker.schedule()
        merchant_key_backfill   → MerchantKeyBackfillWorker.schedule()
        warranty_expiration     → WarrantyExpirationWorker.schedule()
        data_retention          → DataRetentionWorker.schedule()
        bill_reminder_periodic  → BillReminderWorker.schedule()
        receipt_matching        → ReceiptMatchingWorker.schedule()
        ai_daily_briefing       → WorkerSpecScheduler.scheduleAtMidnight()

  Note: SourceLinkBackfillWorker and NotificationIntakeWorker are NOT in the
  registry (they are one-shot/on-demand workers with independent scheduling).
```

### Worker → DAO Dependencies

All 7 workers individually inject and check **`RestoreMaintenanceMode.isWritesAllowed()`**
before performing write operations, ensuring workers yield during an active restore.
Workers now also use **`WorkerExecutionGuard.runGuarded()`** which wraps execution with
**`WorkerRunLogger`** lifecycle tracking (automatically records start/success/skipped/retry/failure).

| Worker | DAO Dependencies | Also Injects |
|--------|-----------------|--------------|
| `DailyBriefingWorker` | AiArtifactDao | RestoreMaintenanceMode, WorkerExecutionGuard, PrivacyGate |
| `LocationBackfillWorker` | ExpenseDao | RestoreMaintenanceMode, WorkerExecutionGuard, PrivacyGate |
| `MerchantKeyBackfillWorker` | ExpenseDao, MerchantNormalizationDao | RestoreMaintenanceMode, WorkerExecutionGuard |
| `WarrantyExpirationWorker` | WarrantyDao, WarrantyReminderDeliveryDao | RestoreMaintenanceMode, WorkerExecutionGuard |
| `BillReminderWorker` | RecurringOccurrenceDao, RecurringReminderDeliveryDao | RestoreMaintenanceMode, WorkerExecutionGuard |
| `ReceiptMatchingWorker` | ScannedReceiptDao, ExpenseDao, ReceiptExpenseLinkDao | RestoreMaintenanceMode, WorkerExecutionGuard |
| `DataRetentionWorker` | RawNotificationDao, ScannedReceiptDao, PrivacyAuditDao | RestoreMaintenanceMode, WorkerExecutionGuard |
| `SourceLinkBackfillWorker` | RawNotificationDao, ExpenseDao, ScannedReceiptDao, PendingReviewDao, ReceiptExpenseLinkDao, EmailReceiptDao, EntitySourceLinkDao | DatabaseWriteBarrier, TimeProvider |
| `NotificationIntakeWorker` | NotificationIntakeDao, NotificationRepository | DatabaseWriteBarrier, WorkerExecutionGuard, NotificationTransientPayloadCrypto, NotificationFilter |

---

## 9. Hilt Module Map

### Module → Provided Types → Consumers

#### Core Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `DatabaseModule` | `di/DatabaseModule.kt` | `AppDatabase`, `GroupTransactionCoordinator` | All DAOs, group operations |
| `DaoModule` | `di/DaoModule.kt` | ~62 DAO singletons | All repositories |
| `DispatchersModule` | `di/DispatchersModule.kt` | `@IoDispatcher`, `@DefaultDispatcher`, `ApplicationScope` | 50+ classes |
| `TimeModule` | `di/TimeModule.kt` | `TimeProvider` → `SystemTimeProvider` | 50+ classes |
| `ServiceModule` | `di/ServiceModule.kt` | `Gson`, `NotificationService`, `GeocodingService`, `NearbyPoiService`, `ForegroundLocationProvider`, `NavigationTargetResolver`, `WidgetStyleRepository`, `SpeechInputGateway` | Services, geocoding, navigation |
| `WorkerModule` | `di/WorkerModule.kt` | `WorkerRunLogger` → `WorkerRunLoggerImpl`, `NotificationPermissionChecker` → `AndroidNotificationPermissionChecker` | All 7 workers via `WorkerExecutionGuard` |

#### AI Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `AiModule` | `di/AiModule.kt` | AI repositories (6), AI services (10), AI DAOs (3), `RedactionSanitizer`, `AiPolicy`, `AiCapabilityRouter`, `AiWorkScheduler`, semantic detector, priority scorer, notification parser, `HybridRouter` (consolidates routing via `AiCapabilityRouter` + `AiSettingsRepository`), `CloudPayloadPolicy` (replaces `CloudPayloadRedactor`) | AI ViewModels, Workers, use cases |
| `OcrImprovementsModule` | `di/OcrImprovementsModule.kt` | `EnhancedMerchantExtractor`, `OcrLanguageProcessor`, `OcrPreprocessingPipeline` | Receipt OCR pipeline |
| `NaturalLanguageModule` | `di/NaturalLanguageModule.kt` | `NaturalLanguageExpenseQueryRepository` → impl | `NaturalLanguageSearchViewModel` |

#### Feature Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `CashFlowModule` | `di/CashFlowModule.kt` | `CashFlowCalculator` | CashFlowCalendarViewModel, SmartSavingsEngine |
| `CurrencyModule` | `di/CurrencyModule.kt` | `CurrencySettingsRepository`, `CurrencyRatesRepository`, `ExchangeRateStore` | All currency-aware pipelines |
| `DashboardContractsModule` | `di/DashboardContractsModule.kt` | 7 dashboard contract adapters | `ComputeDashboardWidgetsUseCase` |
| `DashboardAnomalyModule` | `di/DashboardAnomalyModule.kt` | `AnomalyAlertRepository` (domain + dashboard) | Analytics, dashboard |
| `SavingsModule` | `di/SavingsModule.kt` | `SmartSavingsEngine`, `AutomatedSavingsRuleStateRepository`, `SavingsContributionHistoryRepository`, `AutomatedSavingsRuleEngine`, `SavingsGamificationEngine` | Savings ViewModels |
| `SavingsRepositoryBindingsModule` | `di/SavingsRepositoryBindingsModule.kt` | `DomainSavingsGoalRepository` binding | Savings engines |
| `GroupsModule` | `di/GroupsModule.kt` | `GroupsRepository`, `SharedExpenseDataPort`, Use cases (3); auto-provided: `GroupLifecycleCoordinator`, `GroupBalanceCalculator` | Groups ViewModel |
| `TaxModule` | `di/TaxModule.kt` | `TaxConfiguration` → `GreeceTaxConfiguration`, auto-provided: `DemoTaxRateProvider` | Tax ViewModel |
| `ExportModule` | `di/ExportModule.kt` | `QuickBooksIIFExporter`, `XeroCSVExporter`, `FreshBooksExporter` | Export ViewModel |
| `ReminderSettingsModule` | `di/ReminderSettingsModule.kt` | `BillReminderSettingsRepository` → impl (P4) | BillReminderWorker, BillRemindersViewModel |

#### Infrastructure Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `NetworkModule` | `di/NetworkModule.kt` | `@LocationHttpClient`, `@CloudAiHttpClient` | Geocoding services, AI providers |
| `SecurityModule` | `di/SecurityModule.kt` | `SecureKeyStorage` | AI providers, encryption |
| `PrivacyModule` | `di/PrivacyModule.kt` | `CompositePrivacyGate`, `PrivacyAuditLogger`, `PrivacySettingsRepository` | Every gated capability, backup |
| `BackupRepositoryModule` | `di/BackupRepositoryModule.kt` | `DatabaseBackupRepository` → impl | BackupRestoreViewModel |
| `ParserModule` | `di/ParserModule.kt` | `GreekBankParser` | Notification parsing |
| `ReceiptParsingModule` | `di/ReceiptParsingModule.kt` | `MerchantRulesPolicy` binding | Receipt parsing |
| `EmptyStateModule` | `di/EmptyStateModule.kt` | `EmptyStateRegistryInitializer` multibind | Empty state UI |
| `EmailIngestionModule` | `di/EmailIngestionModule.kt` | `AmazonReceiptParser`, `UberReceiptParser`, `AppleReceiptParser` | Email ingestion |
| `LocationResolverPortsModule` | `di/LocationResolverPortsModule.kt` | `LocationCachePort`, `MerchantClusterPort` | Location enrichment |
| `DiagnosticsModule` | `di/DiagnosticsModule.kt` | `DiagnosticEventWriter`, `TransactionLifecycleEventWriter`, `ReceiptLifecycleEventWriter`, `RecurringLifecycleEventWriter`, `OperationRunRecorder`, `WorkerRunLogger`, `DiagnosticsRepository` | Diagnostics pipeline |
| `ProvenanceModule` | `di/ProvenanceModule.kt` | Provenance event recording | Provenance tracking |
| `RetentionModule` | `di/RetentionModule.kt` | `RetentionRegistry` with 5 `RetentionTarget` entries | Data retention workers |
| `NegotiationModule` | `di/NegotiationModule.kt` | `StaticMarketRateProvider` | BillNegotiationEngine, BillNegotiationViewModel |
| `EmptyStatePresentationModule` | `ui/components/emptystate/EmptyStatePresentationModule.kt` | `EmptyStateRegistryInitializer` multibind (via `DefaultEmptyStateRegistryInitializer`) | Empty state UI |

---

## 10. DAO/Repository Map

### Entity → DAO → Repository → Consumer

| Entity | DAO | Repository | Primary Consumers |
|--------|-----|------------|-------------------|
| `Expense` | `ExpenseDao` | `ExpenseRepository`, `MultiCurrencyRepository`, `NotificationRepository`, `ReviewQueueRepository`, `ReceiptRepository` | TransactionsVM, HomeVM, BudgetVM, AnalyticsVM, etc. |
| `Category` | `CategoryDao` | `CategoryRepository` | Every VM (categories are ubiquitous) |
| `PendingReview` | `PendingReviewDao` | `ReviewQueueRepository`, `NotificationRepository` | ReviewViewModel |
| `Budget` | `BudgetDao` | `BudgetRepository` | BudgetViewModel, BudgetForecastingVM |
| `ScannedReceipt` | `ScannedReceiptDao` | `ReceiptRepository` | ReceiptScanVM, ReviewVM |
| `RawNotification` | `RawNotificationDao` | `NotificationRepository` | TransactionsVM, DebugVM |
| `RecurringExpense` | `ManualRecurringExpenseDao` | `RecurringExpenseRepository` | RecurringExpensesVM, FinancialWeatherRepository |
| `ManualRecurringExpense` | `ManualRecurringExpenseDao` | `ManualRecurringExpenseRepository` | ManualRecurringExpenseVM |
| `RecurringOccurrence` | `RecurringOccurrenceDao` | `RecurringLifecycleCoordinator` | Recurring coordinator, BillReminderWorker |
| `PlannedExpense` | `PlannedExpenseDao` | `PlannedExpenseRepository` | HomeVM, FinancialWeatherRepository |
| `TransactionEvent` | `TransactionEventDao` | `TransactionLifecycleCoordinator` | Audit log (append-only) |
| `ReceiptEvent` | `ReceiptEventDao` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService`, `ReceiptMatchLifecycleService` | Receipt audit log |
| `ReceiptExpenseLink` | `ReceiptExpenseLinkDao` | `ReceiptLinkService`, `ReceiptMatchLifecycleService` | Receipt matching |
| `SavingsGoal` | `SavingsGoalDao` | `SavingsGoalRepository` | SavingsGoalsVM |
| `ExchangeRate` | `ExchangeRateDao` | `CurrencyConverter`, `MultiCurrencyRepository` | Currency conversion |
| `Investment` | `InvestmentDao` | (Direct DAO usage) | InvestmentVM |
| `BankConnection` | `BankConnectionDao` | (Direct DAO usage) | BankConnectionsVM |
| `AnomalyAlert` | `AnomalyAlertDao` | `AnomalyAlertRepositoryImpl` | Analytics, Dashboard |
| `BlockedPackage` | `BlockedPackageDao` | `NotificationRepository` | Notification filter |
| `SourceStats` | `SourceStatsDao` | `NotificationRepository` | Debug |
| `SourceStatsEvent` | `SourceStatsEventDao` | (via AppDatabase directly, no Hilt module yet) | Source stats event tracking (v117) |
| `PrivacyAuditEvent` | `PrivacyAuditDao` | `PrivacyAuditLogger` | Privacy audit |
| `AiArtifactEntity` | `AiArtifactDao` | `AiArtifactRepositoryImpl` | AI follow-through |
| `AiChatSession` | `AiChatSessionDao` | `AiChatRepositoryImpl` | Assistant |
| `AiChatMessage` | `AiChatMessageDao` | `AiChatRepositoryImpl` | Assistant |
| `AssistantHistorySettings` | *(enum)* | `AiChatRepositoryImpl` | Assistant (history redaction: OFF/REDACTED/RAW) |
| `ReceiptItemCategorization` | `ReceiptItemCategorizationDao` | `ReceiptItemCategorizationRepository` | AI categorization |
| `RecurringLifecycleEvent` | `RecurringLifecycleEventDao` | `RecurringLifecycleCoordinator`, `RecurringLifecycleEventWriter` | Recurring audit log |
| `RecurringReminderDelivery` | `RecurringReminderDeliveryDao` | `RecurringLifecycleCoordinator` | Reminder delivery |
| `Warranty` | `WarrantyDao` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `ReturnWindow` | `ReturnWindowDao` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `WarrantyLifecycleEvent` | `WarrantyLifecycleEventDao` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `WarrantyReminderDelivery` | `WarrantyReminderDeliveryDao` | `WarrantyExpirationWorker` | Durable reminder sent-state (v143; claim-before-notify) |
| `SubscriptionCandidate` | `SubscriptionCandidateDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionPriceHistory` | `SubscriptionPriceHistoryDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionUsage` | `SubscriptionUsageDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `EmailReceiptSource` | `EmailReceiptDao` | `EmailReceiptIngestionService` | Email receipt |
| `ExpenseGroup` | `ExpenseGroupDao` | `GroupsRepositoryImpl`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupMember` | `GroupMemberDao` | `GroupsRepositoryImpl`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupExpense` | `GroupExpenseDao` | `GroupsRepositoryImpl`, `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupSettlementEntity` | `GroupSettlementDao` | `GroupLifecycleCoordinator` → recordSettlement(), `GroupBalanceCalculator` | SharedExpenseGroupsVM |
| `GroupLifecycleEventEntity` | `GroupLifecycleEventDao` | `GroupLifecycleCoordinator` | Group lifecycle audit log |
| *(n/a)* | *(n/a)* | `GroupLifecycleCoordinator` → `GroupTransactionCoordinator` (domain interface), `TimeProvider`, `CurrencySettingsRepository` | Groups ViewModel |
| `SplitTemplate` | `SplitTemplateDao` | (Direct usage) | VisualSplitVM |
| `SplitItemAssignment` | `SplitItemAssignmentDao` | (Direct usage) | VisualSplitVM |
| `SpendingChallengeEntity` | `SpendingChallengeDao` | `SpendingChallengeRepository` | SpendingChallengesVM |
| `PromptState` | `PromptStateDao` | `PromptStateRepository` | Savings prompts |
| `BackgroundJobRun` | `BackgroundJobRunDao` | Workers directly, `WorkerRunLoggerImpl` | Worker tracking |
| `PipelineDiagnosticEvent` | `PipelineDiagnosticEventDao` | `NotificationProcessingPipeline` | Cross-pipeline diagnostics |
| `OperationRun` | `OperationRunDao` | `CompositeOperationRunRecorder` | Durable operation run tracking |
| `OperationRunEvent` | `OperationRunEventDao` | `CompositeOperationRunRecorder` | Durable operation run events |
| `EntitySourceLink` | `EntitySourceLinkDao` | `SourceLinkWriterImpl`, `SourceLinkBackfillWorker` | Provenance tracing |
| `NotificationIntakeEntity` | `NotificationIntakeDao` | `NotificationIntakeWorker`, `NotificationIntakeCoordinator` | Queued notification intake |
| `BankStatementImportRun` | `BankStatementImportRunDao` | (Direct DAO usage) | Bank statement import |
| `BankStatementImportItem` | `BankStatementImportItemDao` | (Direct DAO usage) | Bank statement import |
| `NegotiationOutcomeEntity` | `NegotiationOutcomeDao` | `SmartBillNegotiationEngine` | Bill negotiation |
| `Enriched entities not in original table:` | | | |
| `MerchantCanonical` | `MerchantNormalizationDao` | `MerchantNormalizationRepository` | Merchant normalization |
| `MerchantAlias` | `MerchantNormalizationDao` | `MerchantNormalizationRepository` | Merchant alias resolution |
| `MerchantCategory` | `MerchantCategoryDao` | `MerchantCategoryRepository` | Merchant→category mapping |
| `MerchantLocation` | `MerchantLocationDao` | `MerchantLocationRepository` | Merchant geo-location |
| `MerchantLocationCorrection` | `MerchantLocationDao` | `MerchantLocationRepository` | Manual corrections |
| `RecommendationEntity` | `RecommendationDao` | `RecommendationRepository` | Dashboard recommendations |
| `MileageTracking` | `MileageTrackingDao` | (Direct DAO usage) | Mileage tracking |
| `BudgetForecast` | `BudgetForecastDao` | `BudgetForecastingEngine` | Budget forecasting |
| `Investment` | `InvestmentDao` | (Direct DAO usage) | Investment ViewModel |
| `InvestmentValue` | `InvestmentValueDao` | (Direct DAO usage) | Investment ViewModel |
| `InvestmentTransaction` | `InvestmentTransactionDao` | `InvestmentTracker` | Investment audit |
| `HealthScoreHistory` | `HealthScoreHistoryDao` | `FinancialHealthScoreV2` | Health score history |
| `SavingsSweepPlan` | `SavingsSweepPlanDao` | `AutomatedSavingsRuleEngine` | Automated savings |
| `BudgetAdjustmentRecommendation` | `BudgetAdjustmentDao` | `BudgetRecommendationEngine` | Budget adjustments |
| `BudgetAdjustmentEvent` | `BudgetAdjustmentDao` | `BudgetAutopilotEngine` | Budget adjustment audit |
| `SpendingPersonalityProfileEntity` | `SpendingPersonalityProfileDao` | `SpendingPersonalityClassifier` | Analytics profiling |
| `StressForecastSnapshot` | `StressForecastSnapshotDao` | `FinancialStressForecastEngine` | Stress test snapshots |
| `SpendingChallengeEntity` | `SpendingChallengeDao` | `SpendingChallengeRepository` | Spending challenges |
| `WarrantyLifecycleEvent` | `WarrantyLifecycleEventDao` | `WarrantyTrackerRepository` | Warranty lifecycle audit |
| `WarrantyReminderDelivery` | `WarrantyReminderDeliveryDao` | `WarrantyExpirationWorker` | Reminder sent-state tracking |

---

## 11. Location Services Dependency Map

```
MapScreen / LocationResolver
  │
  ▼
LocationResolver                         [domain/location/LocationResolver.kt]
  │
  ├──► CompositeGeocodingService          [data/location/CompositeGeocodingService.kt]
  │     ├──► NominatimGeocodingService    [data/location/NominatimGeocodingService.kt]
  │     ├──► GeoapifyGeocodingService     [data/location/GeoapifyGeocodingService.kt]
  │     ├──► GooglePlacesGeocodingService [data/location/GooglePlacesGeocodingService.kt]
  │     └──► PhotonGeocodingService       [data/location/PhotonGeocodingService.kt]
  │
  ├──► OverpassNearbyService              [data/location/OverpassNearbyService.kt]
  ├──► AndroidForegroundLocationProvider  [data/location/AndroidForegroundLocationProvider.kt]
  │
  ├──► LocationPrivacyGate                [domain/privacy/LocationPrivacyGate.kt]
  │     └──► PrivacyGate.check(EXTERNAL_GEOCODING | BACKGROUND_LOCATION_BACKFILL | GPS | OVERPASS)
  │
  ├──► MerchantLocationRepository         [data/repository/MerchantLocationRepository.kt]
  └──► TravelDetectionEngine              [domain/location/TravelDetectionEngine.kt]

GeoCoordinate                               [domain/location/GeoCoordinate.kt]
  │
  ├──► AreaSpendingEngine.computeNormalized()  [domain/location/AreaSpendingEngine.kt]
  ├──► TravelDetectionEngine.computeNormalized() [domain/location/TravelDetectionEngine.kt]
  └──► SpendingMapViewModel                 [ui/screens/map/SpendingMapViewModel.kt]

Pipeline consumers:
- AnalyticsViewModel → LocationInsightsEngine, AreaSpendingEngine, TravelDetectionEngine
- SpendingMapViewModel → LocationResolver, onCenterOnMeRequested (W27: GPS defer)
- LocationBackfillWorker → CompositeGeocodingService + ExpenseDao
```

---

## 12. AI Provider Dependency Map

```
Domain AI Services (interfaces)
  │
  ├──► CategorizationAssistService
  │     ├──► CloudCategorizationAssistService
  │     ├──► OnDeviceCategorizationAssistService
  │     ├──► HybridCategorizationAssistService
  │     └──► NoOpCategorizationAssistService
  │
  ├──► DashboardBriefingService
  │     ├──► CloudDashboardBriefingService
  │     ├──► OnDeviceDashboardBriefingService
  │     ├──► HybridDashboardBriefingService
  │     └──► NoOpDashboardBriefingService
  │
  ├──► DedupeJudgeService
  │     ├──► CloudDedupeJudgeService
  │     ├──► OnDeviceDedupeJudgeService
  │     ├──► HybridDedupeJudgeService
  │     └──► NoOpDedupeJudgeService
  │
  ├──► QueryInterpretationService
  │     ├──► CloudQueryInterpretationService
  │     ├──► OnDeviceQueryInterpretationService
  │     ├──► HybridQueryInterpretationService
  │     └──► NoOpQueryInterpretationService
  │
  ├──► ReceiptAssistService
  │     ├──► SmartReceiptAssistService
  │     ├──► OnDeviceReceiptAssistService
  │     ├──► HybridReceiptAssistService
  │     └──► NoOpReceiptAssistService
  │
  ├──► ReviewExplanationService
  │     ├──► CloudReviewExplanationService
  │     ├──► OnDeviceReviewExplanationService
  │     ├──► HybridReviewExplanationService
  │     └──► NoOpReviewExplanationService
  │
  └──► ReceiptItemCategorizationService
        ├──► CloudReceiptItemCategorizationService
        ├──► OnDeviceReceiptItemCategorizationService
        └──► HybridReceiptItemCategorizationService

All Hybrid services use:
  ├──► HybridRouter (consolidates AiCapabilityRouter + CloudPayloadPolicy)
  ├──► PrivacyGate.check()           — Respects user privacy settings
  └──► CloudPayloadPolicy            — Replaces CloudPayloadRedactor, controls field-level payload filtering
```

---

## ViewModel Constructor Injection Reference

| ViewModel | Injected Dependencies |
|-----------|----------------------|
| `HomeViewModel` | Application, DashboardRepository, DashboardDataProvider, CategoryRepository, PlannedExpenseRepository, DashboardAnalyticsRepository, ExpenseRepository, ComputeDashboardWidgetsUseCase, AiSettingsRepository, AiArtifactRepository, AiEnvironmentMonitor, AiEngagementRepository, WidgetStyleRepository, TimeProvider, RecommendationStateManager, NavigationTargetResolver, RecommendationDismissalHandler, TotalsAggregationEngine, AdvancedAnalyticsEngine, CurrencySettingsRepository |
| `TransactionsViewModel` | NotificationRepository, ExpenseRepository, CategoryRepository, RecurringExpenseRepository, MerchantLocationRepository, TimeProvider, GeocodingService, CurrencySettingsRepository |
| `ReviewViewModel` | NotificationRepository, ReviewQueueRepository, CategoryRepository, ReceiptRepository, ExpenseRepository, DebugDataStorage, GeocodingService, PrivacyGate, ExplainPendingReviewUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase, JudgePendingReviewDuplicateUseCase, AiArtifactRepository, AiSettingsRepository, AiRuntimeDiagnostics, ReceiptLifecycleCoordinator, ReceiptDebugExporter |
| `BudgetViewModel` | BudgetRepository, CategoryRepository, SharedExpenseBudgetOffsetEngine, BudgetAutopilotEngine, TimeProvider, CurrencySettingsRepository, AppDatabase |
| `AddExpenseViewModel` | ManualExpenseRepository, ExpenseRepository, CategoryRepository, TimeProvider, CurrencySettingsRepository |
| `ReceiptScanViewModel` | ReceiptRepository, CategoryRepository, CurrencySettingsRepository, AiSettingsRepository, SavedStateHandle, TimeProvider, SuggestReceiptExtractionUseCase, SuggestCategoryFallbackUseCase, CategorizeReceiptItemsUseCase, ReceiptItemCategorizationRepository, AiArtifactRepository, AiRuntimeDiagnostics, ReceiptLifecycleCoordinator, ReceiptParser, TransactionLifecycleCoordinator, ReceiptLinkService, MerchantNormalizer, HybridExpenseClassifier |
| `AnalyticsViewModel` | ExpenseRepository, CategoryRepository, BudgetRepository, InsightsEngine, RecurringExpenseEngine, AnalyticsRepository, AdvancedAnalyticsEngine, AnalyticsCurrencyNormalizer, LocationInsightsEngine, AreaSpendingEngine, TravelDetectionEngine, SpendingPersonalityClassifier, TimeProvider, AnalyticsInputAssembler, CurrencyConverter, CurrencySettingsRepository, BudgetVsActualEngine, DailyBucketEngine |
| `AdvancedAnalyticsViewModel` | AnalyticsRepository, CategoryRepository |
| `BackupRestoreViewModel` | DatabaseBackupRepository |
| `SavingsGoalsViewModel` | SavingsGoalRepository, SmartSavingsEngine, AutomatedSavingsRuleEngine, SavingsGamificationEngine |
| `SubscriptionManagementViewModel` | SubscriptionManagementRepository |
| `CurrencyManagementViewModel` | CurrencySettingsRepository, CurrencyRatesRepository, MultiCurrencyRepository |
| `CarbonFootprintViewModel` | ExpenseRepository, CategoryRepository |
| `CashFlowCalendarViewModel` | CashFlowCalculator, TimeProvider, CurrencySettingsRepository |
| `DebugViewModel` | NotificationRepository, ExpenseRepository, BudgetRepository, CategoryRepository |
| `PrivacySettingsViewModel` | PrivacySettingsRepository |
| `VisualSplitViewModel` | SplitTemplateDao, SplitItemAssignmentDao |
| `WarrantyTrackerViewModel` | WarrantyTrackerRepository |
| `SpendingMapViewModel` | ExpenseRepository, CategoryRepository, LocationResolver |
| `InvestmentViewModel` | InvestmentDao, InvestmentValueDao |
| `BankConnectionsViewModel` | BankConnectionDao |
| `ReceiptMatchingViewModel` | ReceiptRepository, ExpenseRepository, ReceiptMatchLifecycleService |
| `AiSettingsViewModel` | AiSettingsRepository |
| `AssistantViewModel` | AiChatRepository, QueryInterpretationService |
| `BillRemindersViewModel` | BillReminderManager, RecurringLifecycleCoordinator |
| `RecurringExpensesViewModel` | RecurringExpenseRepository |
| `ManualRecurringExpenseViewModel` | ManualRecurringExpenseRepository |
| `SourceLinkDebugViewModel` | SourceLinkQueryService |
| `SourceLinkBackfillViewModel` | SourceLinkBackfillWorker |
| `BillNegotiationViewModel` | SmartBillNegotiationEngine, MarketRateProvider |
| `BudgetForecastingViewModel` | BudgetForecastingEngine, BudgetForecastDao |
| `CategoryViewModel` | CategoryRepository |
| `CategorizationDebugViewModel` | CategorizationEngine |
| `ExportOptionsViewModel` | AccountingExportPolicy, AccountingExporters |
| `LifestyleInflationViewModel` | LifestyleInflationDetector, ExpenseRepository |
| `MainViewModel` | (App-level state holder, no injected deps) |
| `NaturalLanguageSearchViewModel` | NaturalLanguageSearchEngine, AiChatRepository |
| `PriceProtectionViewModel` | PriceProtectionTracker |
| `SpendingChallengesViewModel` | SpendingChallengeRepository |
| `TaxConfigurationViewModel` | TaxConfiguration, TaxSettingsRepository |

---

> **Generated:** Manual analysis of 1100+ source files across 3 layers (UI/Domain/Data),  
> 33 Hilt @Module files (32 in `di/` + 1 `EmptyStatePresentationModule.kt`), 41 @HiltViewModel, 46+ repositories, ~68 DAOs, 70+ entities.  
> DB schema version: v147  
> **Next update:** Regenerate when significant architectural changes occur (new module, major refactor).

---

---

## 13. Stage 1 Architecture Foundations

### BackupPrivacyMode (Segment 18)
Enum with 4 values controlling backup privacy:
FULL_ENCRYPTED, REDACT_RAW_TEXT, REDACT_RAW_TEXT_EXCLUDE_IMAGES, ANONYMIZED_EXPORT.
Added as nullable field on BackupManifest.

### CloudPayloadPolicy (Segment 28)
Replaces CloudPayloadRedactor. Controls field-level payload filtering for cloud AI calls.
Implemented via HybridRouter consolidation in AiModule.

### ForecastDataQuality (Segment 1)
Additive data class (no consumer break) with fields: isPartial,
excludedActualCount, excludedPlannedCount, excludedRecurringCount,
conversionWarnings, confidencePenalty.

### CI Guard (Segment 9)
scripts/guards/check_lifecycle_bypasses.kts — scans for 14 forbidden
direct ExpenseDao calls, with documented allowlist.

### Rate Staleness
CurrencyConverter.convert() checks rate.lastUpdated against 24h threshold.

### MoneyAggregateBuilder (Segment 16)
`domain/core/money/MoneyAggregateBuilder.kt` — Common helper for building
MoneyAggregate from per-currency buckets. Used by WarrantyTrackerRepository,
SubscriptionManagerEngine, InvestmentTracker, TaxEstimator, and AnalyticsRepository.
Handles single non-home conversion, mixed-currency conversion, and failure mapping
(STALE_RATE vs MISSING_RATE).

### ConvertedMoney Failure Semantics (Segment 16)
`domain/core/money/ConvertedMoney.kt` now properly distinguishes three states:
- **identity()** — same-currency, always `isSuccess=true` (M01 fix)
- **success()** — cross-currency conversion succeeded
- **failed(reason, message)** — stores `failureReason` and `failureMessage` (M08 fix)
Previously `identity()` was misclassified as a failure and `failed()` discarded the reason.

---

## GroupBalanceCalculator Dependency Chain (2026-05-10)

```
GroupBalanceCalculator                    [domain/groups/GroupBalanceCalculator.kt]
  │  @Singleton @Inject
  │  Per-member net balance calculator
  │
  ├──► ExpenseGroupDao                   — Read group details
  ├──► GroupMemberDao                    — Read member list
  ├──► GroupExpenseDao                   — Read paid totals and owed shares
  └──► GroupSettlementDao                — Read settlements paid/received
       │
       ▼
  GroupMemberBalance                     — Data class: paidTotal, owedShareTotal,
       settlementsPaid, settlementsReceived, netBalance, isSettled

  Consumed by:
  └──► GroupLifecycleCoordinator         — Balance-aware operations
  └──► SharedExpenseGroupsViewModel      — UI display of member balances
```

## Negotiation Dependency Chain (2026-05-09)

```
MarketRateProvider                         [domain/negotiation/MarketRateProvider.kt]
  │
  ▼
StaticMarketRateProvider                   [data/negotiation/StaticMarketRateProvider.kt]
  │  @Singleton @Inject (no Dagger module needed)
  │
  ▼
SmartBillNegotiationEngine                [domain/negotiation/SmartBillNegotiationEngine.kt]
  │
  ▼
BillNegotiationScreen                     [ui/screens/negotiation/BillNegotiationScreen.kt]
```

## Natural Language Search Dependency Chain (2026-05-09)

```
QueryDataQuality                           [domain/naturallanguage/NaturalLanguageSearchEngine.kt]
  │  data class: unsupportedLocations, failedCurrencyConversions, hasWarnings
  │
  ▼
NaturalLanguageSearchEngine               [domain/naturallanguage/NaturalLanguageSearchEngine.kt]
  │  executeSearch(): keyset pagination, convertAsOf currency safety, no raw fallback
  │
  ├──► SearchCursor                        [domain/naturallanguage/NaturalLanguageExpenseQueryRepository.kt]
  │     (date, id) keyset cursor replacing offset pagination
  │
  ├──► NaturalLanguageExpenseQueryRepositoryImpl  [data/repository/...]
  │     getExpensesBetweenFilteredKeyset() delegates to ExpenseDao.getExpensesFilteredKeyset()
  │
  └──► ExpenseDao.getExpensesFilteredKeyset()  [data/database/dao/ExpenseDao.kt]
        Filtered Room @Query with categoryIds, transactionType, merchant LIKE, keyword LIKE, DESC ordering
```

---

## 14. Provenance / Source Link Dependency Chain

```
Expense / RawNotification / ScannedReceipt / ReceiptExpenseLink / PendingReview / EmailReceiptSource
  │
  ▼
SourceLinkWriter                           [domain/provenance/SourceLinkWriter.kt]
  │  Interface
  ▼
SourceLinkWriterImpl                       [domain/provenance/SourceLinkWriterImpl.kt]
  │  @Singleton @Inject
  │
  ├──► EntitySourceLinkDao                 — INSERT link rows
  ├──► SafeProvenanceMetadata              — Build metadata from source
  └──► SourceIdentityKeyFactory            — Deterministic identity keys
       │
       ▼
  Consumed by:
  ├──► NotificationRepository              — On notification → expense creation
  ├──► EmailReceiptIngestionService        — On email → expense creation
  ├──► ReceiptRepository                   — On receipt → expense creation
  └──► BankApiIntegration                  — On bank sync → expense creation

SourceIdentityKeyFactory                   [domain/provenance/SourceIdentityKeyFactory.kt]
  └── Creates deterministic identity keys per source type

SourceLinkPayload                          [domain/provenance/SourceLinkPayload.kt]
  │  Sealed interface for typed source link payloads:
  ├──► NotificationSourceLinkPayloadFactory
  ├──► ReceiptSourceLinkPayloadFactory
  ├──► BankSourceLinkPayloadFactory
  ├──► ImportSourceLinkPayloadFactory
  └──► PendingReviewSourcePayloadFactory

PendingReviewSourceLinkPromoter            [domain/provenance/PendingReviewSourceLinkPromoter.kt]
  │  Promotes pending-review source links to full EntitySourceLink rows
  ├──► PendingReviewDao
  ├──► EntitySourceLinkDao
  └──► PendingReviewSourceContext

SourceLinkQueryService                     [domain/provenance/SourceLinkQueryService.kt]
  │  Read-only query service for source link debugging
  └──► EntitySourceLinkDao

SourceLinkBackfillWorker                   [domain/provenance/SourceLinkBackfillWorker.kt]
  │  One-shot backfill: migrates legacy source metadata into entity_source_links
  ├──► ExpenseDao, RawNotificationDao, ScannedReceiptDao, PendingReviewDao
  ├──► ReceiptExpenseLinkDao, EmailReceiptDao, EntitySourceLinkDao
  └──► DatabaseWriteBarrier, TimeProvider

DuplicateSourceLinkPolicy                  [domain/provenance/DuplicateSourceLinkPolicy.kt]
SourceLinkFallbackPolicy                   [domain/provenance/SourceLinkFallbackPolicy.kt]
SourceLinkWriteException                   [domain/provenance/SourceLinkWriteException.kt]
SourceLinkWriteResult                      [domain/provenance/SourceLinkWriteResult.kt]
SourceLinkExportRef                        [domain/export/SourceLinkExportRef.kt]
SourceLinkEnums                            [domain/provenance/SourceLinkEnums.kt]
```

---

## 15. Notification Intake / Capture Subsystem

```
AndroidNotificationListener
  │
  ▼
NotificationCaptureService                 [service/NotificationCaptureService.kt]
  │
  ├──► PrivacyGate.check(NOTIFICATION_CAPTURE)
  ├──► RestoreMaintenanceMode.isActive()
  ├──► NotificationFilter
  │
  ▼
NotificationIntakeCoordinator              [domain/notification/capture/NotificationIntakeCoordinator.kt]
  │  @Singleton @Inject
  │  Queues incoming notifications as NotificationIntakeEntity rows
  │  instead of processing inline (crash-safe decoupling)
  │
  ├──► NotificationCaptureGate             [domain/notification/capture/NotificationCaptureGate.kt]
  │     └──► PrivacyCapabilityHandlingPolicy resolution
  ├──► NotificationCaptureDeduper          [domain/notification/capture/NotificationCaptureDeduper.kt]
  │     └──► Deduplicates by fingerprint + transient key
  ├──► NotificationTransientKeyProvider    [domain/notification/capture/NotificationTransientKeyProvider.kt]
  ├──► NotificationTransientPayloadCrypto  [domain/notification/capture/NotificationTransientPayloadCrypto.kt]
  ├──► NotificationIntakeDao               — INSERT intake rows
  │     └──► NotificationIntakeEntity      — status: PENDING / PROCESSING / COMPLETED / FAILED
  ├──► NotificationIntakePayloadRepairer   [domain/notification/capture/NotificationIntakePayloadRepairer.kt]
  └──► NotificationIntakeRecoveryScheduler [domain/notification/capture/NotificationIntakeRecoveryScheduler.kt]
       │
       ▼
  NotificationIntakeWorker                 [worker/NotificationIntakeWorker.kt]
    │  @HiltWorker
    │  Processes queued intake rows asynchronously
    │  Steps:
    │   1. Decrypt transient payload (NotificationTransientPayloadCrypto)
    │   2. Check RestoreMaintenanceMode + DatabaseWriteBarrier
    │   3. Apply NotificationFilter
    │   4. Parse via NotificationProcessingPipeline
    │   5. Create expense via NotificationRepository
    │   6. Mark intake row COMPLETED or FAILED
    │
    ├──► NotificationIntakeDao
    ├──► NotificationFilter
    ├──► NotificationProcessingPipeline
    ├──► NotificationRepository
    ├──► RestoreMaintenanceMode
    ├──► DatabaseWriteBarrier
    ├──► WorkerExecutionGuard
    └──► NotificationTransientPayloadCrypto

NotificationDomain Data Types:
  ├── CaptureSource                        — Enum: NOTIFICATION, SMS, WEARABLE
  ├── NotificationCaptureDecision          — CAPTURE / SILENCE / BLOCK
  ├── NotificationIntakeCaptureResult      — INTAKE_QUEUED / DEDUP_SKIPPED / BLOCKED / ERROR
  ├── NotificationPersistenceContext       — Context for write/redact decisions
  ├── NotificationPipelineOutcome          — CREATED / UPDATED / SKIPPED / DUPLICATE
  ├── RawNotificationFingerprint           — Hash-based dedup fingerprint
  ├── RawNotificationInsertResult          — INSERTED / DUPLICATE / BLOCKED
  └── NotificationMoneySignalDetector      [domain/notification/money/] — Signal detection
```

---

## 16. Domain Side-Effect Framework

```
PostCommitActionRunner                     [domain/sideeffect/PostCommitActionRunner.kt]
  │  Interface — runs post-DB-transaction side-effect batches
  │
  ▼
PostCommitActionRunnerImpl                 [domain/sideeffect/PostCommitActionRunnerImpl.kt]
  │  @Singleton @Inject
  │  Iterates PostCommitAction items, executes by priority order,
  │  collects SideEffectBatchResult, logs via SideEffectEventWriter
  │
  ├──► PostCommitActionBatch               — Immutable batch of actions
  │     └──► PostCommitAction              — Single action: actionId, trigger, priority, category, suspend () -> SideEffectOutcome
  ├──► SideEffectPriority                  — CRITICAL / HIGH / NORMAL / LOW
  ├──► SideEffectCategory                  — BUDGET / ANOMALY / MERCHANT_LEARNING / RECURRING / etc.
  ├──► SideEffectTriggerType               — ON_CREATED / ON_UPDATED / ON_DELETED
  ├──► SideEffectExecutionContext          — Context passed to each action
  ├──► SideEffectActionResult              — Per-action result
  ├──► SideEffectBatchResult               — Aggregate batch result
  ├──► SideEffectOutcome                   — SUCCESS / SKIPPED / FAILED / RETRY
  ├──► SideEffectSkipReason                — Why action was skipped
  ├──► SideEffectEventWriter               — Interface for recording side-effect events
  ├──► CompositeSideEffectEventWriter      — Delegates to multiple writers
  ├──► DiagnosticSideEffectEventWriter     — Writes diagnostic events
  ├──► TransactionSideEffectFailureEventWriter — Records transaction-level failures
  └──► SideEffectMetadataFactory           — Builds metadata for events

TransactionLifecycleCoordinator
  ├──► TransactionSideEffectPlanner        — Builds batches for expense CRUD
  │     ├──► BudgetMonitor
  │     ├──► AnomalyAlertOrchestrator
  │     ├──► RecurringLifecycleCoordinator
  │     ├──► MerchantCategoryRepository
  │     └──► MerchantNormalizationRepository
  └──► PostCommitActionRunner              — Executes the planned batch

ReceiptLifecycleCoordinator
  ├──► ReceiptSideEffectPlanner            — Builds batches for receipt CRUD
  │     ├──► AutoCreateWarrantyFromReceiptUseCase
  │     ├──► ReceiptItemCategorizationService
  │     ├──► ReceiptTransactionMatcher
  │     └──► PriceProtectionTracker
  └──► PostCommitActionRunner

MutationResult                             [domain/sideeffect/MutationResult.kt]
  └── Typed result wrapper for lifecycle mutations (success with eventId / failure)
```

---

## 17. Anomaly / Alerts Dependency Chain

```
AnomalyAlertOrchestrator                   [domain/alerts/AnomalyAlertOrchestrator.kt]
  │  @Singleton @Inject
  │  Central alert orchestrator — checks all anomaly detectors
  │  Called by TransactionSideEffectPlanner on expense create/update/delete
  │
  ├──► AnomalyDetector                     [domain/analytics/AnomalyDetector.kt]
  │     └──► MultiCurrencyRepository, ExpenseDao
  ├──► AnomalyAlertDao                     — Persist triggered alerts
  │     └──► AnomalyAlert                  — Entity: type, severity, message, expenseId
  └──► TimeProvider

AnomalyAlertRepository                     [domain/alerts/AnomalyAlertRepository.kt]
  │  Domain interface
  ▼
AnomalyAlertRepositoryImpl                 [data/repository/AnomalyAlertRepositoryImpl.kt]
  └──► AnomalyAlertDao, DashboardAnomalyModule (DI binding)

Dashboard consumer:
  └──► DashboardAnomalyModule              — Provides AnomalyAlertRepository (domain + dashboard)
```

---

## 18. Financial Rescue Dependency Chain

```
FinancialRescueCoordinator                 [data/rescue/FinancialRescueCoordinator.kt]
  │  Raw SQLite import path — bypasses Room migration chain for emergency data rescue
  │  Used when schema version mismatch prevents normal DB open
  │
  ├──► RescueConfig                        [data/rescue/RescueConfig.kt]
  │     └── Configuration for rescue operation
  ├──► FinancialRescueSnapshot             [data/rescue/FinancialRescueSnapshot.kt]
  │     └── Captures snapshots during rescue for rollback
  ├──► RescueActivity                      [data/rescue/RescueActivity.kt]
  │     └── UI activity for rescue flow
  └──► TransactionLifecycleCoordinator     — Dedup mode SKIP_FOR_DEBUG_RESTORE
```

---

## 19. Business / Income / Lifestyle Engines Dependency Chain

```
BusinessExpenseReportGenerator             [domain/business/BusinessExpenseReportGenerator.kt]
  │  @Singleton @Inject
  │  Generates business expense reports with tax categorization
  │
  ├──► ExpenseDao
  ├──► CategoryDao
  ├──► BusinessExpenseRepository            [data/repository/BusinessExpenseRepository.kt]
  └──► TaxConfiguration                    [domain/tax/TaxConfiguration.kt]

RecurringIncomeTracker                     [domain/income/RecurringIncomeTracker.kt]
  │  Tracks recurring income patterns alongside expenses
  └──► ExpenseDao, CategoryDao

LifestyleInflationDetector                 [domain/lifestyle/LifestyleInflationDetector.kt]
  │  Detects lifestyle inflation by comparing spending over time
  └──► ExpenseDao, MultiCurrencyRepository

SpendingChallengeManager                   [domain/challenge/SpendingChallengeManager.kt]
  │  Manages user-defined spending challenges
  └──► SpendingChallengeDao
```

---

## 20. Domain Engine Layer

```
DashboardFollowThroughEngine               [domain/engine/DashboardFollowThroughEngine.kt]
  │  Follow-through engine for dashboard recommendations
  │  Monitors whether users actioned suggested changes
  │
  └──► RecommendationDao, ExpenseDao
```

---```
