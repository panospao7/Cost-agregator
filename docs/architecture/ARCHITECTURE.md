# ExpenseTracker Architecture Guide

## How to Use This Document

### For Quick Understanding
→ Read **Architecture Overview** and **Layer Structure** sections

### For Adding Features
1. Read **Key Components** to find similar patterns
2. Check **Quick Reference** → "Add New Screen/Parser/Entity"

### For Bug Analysis (RECOMMENDED WORKFLOW)

1. Start with **CODEBASE_SEGMENTS.md** to find the owning segment.
2. Use **CODEBASE_INVENTORY.md** for the current route / subsystem map.
3. Trace the flow here when you need cross-layer context.
4. Use the quick reference tables for likely failure points.

---

## Table of Contents
1. Architecture Overview
2. Layer Structure
3. Data Flow
4. Key Components
5. Dependency Injection
6. Database Schema
7. Recent Changes & Fixes
8. Quick Reference

## Current Project Metrics
- Database version: v141 (migrated from v131 through v139–v141 for recurring lifecycle hardening, occurrence status typing, and reminder delivery FK enforcement)
- 926 Kotlin source files (388 domain, 280 data, 164 ui, 31 di, 63 other/util)
- 62 DAOs (58 in DaoModule + 3 in AiModule + 1 unbound), 64 entities registered in AppDatabase
- 39 @HiltViewModel (38 *ViewModel.kt files + 1 inline in RecurringExpensesScreen.kt)
- 30 @Module Hilt modules
- SimpleDateFormat → DateTimeFormatter: **100% complete** (38 replacements across 21 files, 0 remaining in production code)
- REPLACE → IGNORE: **14 of 14 DAOs converted** (3 kept with KDoc: ExchangeRateDao ×2, AiArtifactDao ×1)
- Bank statement AI parsing: **complete** (on-device→cloud→parser 3-tier validation with per-transaction source tracking)
- Compliance audit: **HIGH fixes completed** (6 HIGH, 21 MEDIUM KDoc resolutions)
- Destination-driven navigation via `NavigationDestination`
- 6 shell destinations in the app chrome; Assistant is an overlay/entry surface, not a bottom tab
- Deep links are handled in `ui/MainActivity.kt` (`handleIntent` / `onNewIntent`); saved navigation state stays in `NavigationController`
- Startup/background pipeline: `MainApplication` → `AppStartupDelegate` → `AppStartupCoordinator` → `AppBackgroundLifecycleObserver`; restore journal checked before any work is scheduled
- Worker instrumentation: `WorkerRunLogger` (`domain/workers/WorkerRunLogger.kt`) provides per-run success/skipped/retry/failure tracking via `BackgroundJobRunDao`. `WorkerExecutionGuard` (`domain/workers/WorkerExecutionGuard.kt`) provides structured guarded execution with logging, exception handling, and restore-mode gating. Both bound via `WorkerModule` (`di/WorkerModule.kt`).
- `DatabaseReadBarrier` (`data/backup/DatabaseReadBarrier.kt`) and `DatabaseWriteBarrier` (`data/backup/DatabaseWriteBarrier.kt`) provide operation-level read/write blocking during restore — throw `IllegalStateException` if writes are attempted in non-NORMAL/BACKUP_EXPORTING modes.
- WorkManager periodic jobs include: `DailyBriefingWorker`, `LocationBackfillWorker`, `MerchantKeyBackfillWorker`, `WarrantyExpirationWorker`, `BillReminderWorker`, `ReceiptMatchingWorker`, `DataRetentionWorker` (all 7 paused during restore via `RestoreMaintenanceMode`). Each worker individually injects `RestoreMaintenanceMode` and calls `isWritesAllowed()` at the start of `doWork()` to self-pause during restore. All workers use `WorkerSpecScheduler` for centralized scheduling with version-change detection.
- `HybridRouter` (`domain/ai/HybridRouter.kt`) replaces duplicated cloud/on-device/fallback routing logic across 6 hybrid AI services (AID-4).
- `AtRestEncryptionService` (`data/privacy/AtRestEncryptionService.kt`): AES-256-GCM via Android Keystore for ML model data at rest.
- `SourceStatsEvent` entity + `SourceStatsEventDao`: event-based notification source stats tracking.
- `WorkerRegistry` (`domain/workers/WorkerRegistry.kt`): single-source-of-truth registry for all 7 background workers; replaces hardcoded lists in `RestoreMaintenanceMode`/`AppStartupCoordinator`.
- `PrivacyBlocked` (`domain/privacy/PrivacyBlocked.kt`): sealed interface standardizing privacy-denied states with capability-specific subclasses (CloudAiDisabled, ReceiptImageUploadDisabled, etc.).
- `AccountingExportPolicy` (`domain/export/AccountingExportPolicy.kt`): export policy validation (single-currency, purchase-only checks, global dataset validation).
- `DataQualityReport` (`domain/analytics/DataQualityReport.kt`): unified data class aggregating quality metrics from analytics, forecasting, currency conversion, and AI pipelines.
- `RestoreMaintenanceMode` now supports `BACKUP_EXPORTING` mode and uses `WorkerRegistry.pauseAllWorkers()`/`resumeAllWorkers()` for centralized worker lifecycle.
- `RestoreJournal` has new `ASSETS_RESTORING` state for crash-safe asset recovery tracking.
- `RecurringRuleLifecycleCoordinator` (`domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`): rule-level lifecycle (deactivate/delete with atomic cleanup of occurrences/reminders/planned expenses).
- `PrivacyDecision.FailClosed` + `blocksExecution()`/`reason()`: structured fail-closed mechanism — both `Denied` and `FailClosed` block execution; 30+ callers across backup/export/geocoding/location/currency/warranty now block on `FailClosed`.
- `AccountBalanceProvider` (`domain/forecasting/AccountBalanceProvider.kt`): interface for resolving current account balance.
- `NetCashflowBalanceProvider` (`domain/forecasting/NetCashflowBalanceProvider.kt`): 90-day net cashflow fallback implementation of `AccountBalanceProvider`.
- `PrivacyBlockedCard` (`ui/components/PrivacyBlockedCard.kt`): reusable Compose UI card for privacy-blocked states with lock icon.
- New architecture docs: `ENGINE_INTERACTION_MAP.md` (engine-to-pipeline impact matrix) and `LEGAL_PATHS.md` (single allowed implementation path for each operation).
- `ReceiptMatchLifecycleService` (`domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt`): lifecycle-aware receipt match mutations with write barrier, transaction, and durable events.
- `RecurringRuleLifecycleCoordinator` is now the **single writer** for all rule lifecycle mutations, enforced by `RecurringArchitectureGuardTest` (14 static architecture guards).
- `RecurringOccurrenceStatus` typed enum + `RecurringOccurrenceTransitionPolicy` for validated status transitions.
- `RecurringLifecycleEventWriter` `writeCritical()`/`writeDiagnostic()` split for provenance vs informational events.
- `BillReminderSettingsRepository` + `ReminderSettingsModule` DI module for runtime reminder dispatch control.
- AI, location, shared-expense, split, privacy, backup-encryption, and `.costbackup` bundle backup/restore flows are first-class subsystems

### Architecture Drift Updates (2026-05-13 — Pipeline 3: Receipt Match Lifecycle & Debug Redaction)
- **ReceiptMatchLifecycleService** created (`domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt`) — `@Singleton @Inject` lifecycle-aware service replacing direct `ReceiptRepository` match mutations (now `DeprecationLevel.ERROR`). Every operation: checks `DatabaseWriteBarrier`, runs inside Room `withTransaction`, writes durable `ReceiptEvent`. Methods: `saveMatchSuggestion()`, `approveMatchSuggestion()`, `rejectAllSuggestions()`, `clearMatchForReceipt()`. Dependencies: `AppDatabase`, `ScannedReceiptDao`, `ReceiptEventDao`, `DatabaseWriteBarrier`, `TimeProvider`.
- **ReceiptDebugExporter audit instrumentation** — every debug export decision writes a `DiagnosticEvent` (ALLOWED/DENIED) with reason codes (`consent_blank`, `storage_mode_<mode>`, `receipt_not_found`). `formatReceiptDebug()` signature changed: `includeImagePath` parameter defaults to `false` for privacy-by-default image path redaction.
- **Exception message redaction policy** — `ReceiptRepository` error logging sanitizes exception messages: URIs → `[REDACTED_URI]`, file paths → `[REDACTED_PATH]`, URLs → `[REDACTED_URL]`, emails → `[REDACTED_EMAIL]`, 12-19 digit sequences → `[REDACTED_NUMBER]`.
- **Debug data privacy-by-default** — `BankStatementLifecycleProcessor.debugData` set to `null` by default. Raw OCR text export requires `exportConsent` + `RawStorageMode.STORE_RAW`. Image paths redacted unless `includeImagePath=true`.
- **DeprecationLevel.ERROR sweep** — `ReceiptRepository.clearMatchForReceipt()`, `saveMatchSuggestion()`, `rejectAllSuggestions()`, `exportParserDebugData()`, `debugReceipt()` all escalated to `ERROR`. `ReceiptMatchingWorker`/`ReceiptMatchingViewModel` migrated to `ReceiptMatchLifecycleService`. `ReviewViewModel` migrated to `ReceiptDebugExporter`.
- **ReviewViewModel** — migrated debug data calls from `receiptRepository` to `ReceiptDebugExporter`. `@Suppress("DEPRECATION")` scoped to method-level only (`clearScannedData()`).
- **`@Suppress("DEPRECATION")` scoping pattern** — annotation moved from class-level to minimum necessary method-level, signaling policy of silencing deprecation only at call-site.

### Architecture Drift Updates (2026-05-06)
- `TransactionLifecycleCoordinator` now resolves home currency from `CurrencySettingsRepository` for conversion snapshots in both create and update paths (EUR constant is fallback only).
- `SnoozeReminderReceiver` and `DismissReminderReceiver` are now Hilt-enabled entry points (`@AndroidEntryPoint`) and use injected `RecurringReminderDeliveryDao`, `TimeProvider`, and `RestoreMaintenanceMode`.
- `BackupVerifier` verification strictness was increased: critical lifecycle/event tables (`transaction_events`, `receipt_events`, `receipt_expense_links`, `recurring_occurrences`, `recurring_reminder_deliveries`, `recurring_lifecycle_events`) are now treated as `TIER_1_EXACT`.
- Generic exports in `ExportOptionsViewModel` now use `effectiveAmount` for CSV/JSON generation (preview + streaming), aligning exports with ownership-adjusted accounting semantics.

### Architecture Drift Updates (2026-05-09)
- **GroupLifecycleCoordinator** implemented (`domain/groups/GroupLifecycleCoordinator.kt`) — a `@Singleton @Inject` domain-level coordinator wrapping the domain `GroupTransactionCoordinator` interface. Enforces 7 business rules: member count/currentUser/duplicate validation (createGroup), active-group/duplicate/single-currentUser checks (addMember), last-currentUser gate (removeMember), single-currency policy (addExpense), soft-delete (archiveGroup), explicit-confirmation guard (deleteGroupPermanently), and currency-match/member-ownership validation (recordSettlement). No Dagger cycle — depends on domain interface, not data-layer implementation.
- **[Dagger/DependencyCycle] FIXED:** Deleted `SubscriptionModule.kt` — its pass-through `@Provides` method was the sole cause of the cycle involving `SubscriptionManagerEngine`. `SubscriptionManagerEngine` is already auto-provided by its `@Singleton @Inject constructor`.
- **DaoModule FIXED:** Added 3 missing `@Provides` bindings (`WarrantyLifecycleEventDao`, `GroupSettlementDao`, `InvestmentTransactionDao`) resolving `[Dagger/MissingBinding]` errors.
- **CloudPayloadRedactor Stage 2 COMPLETE:** Migrated 6 cloud providers from direct `CloudPiiSanitizer` calls to `CloudPayloadRedactor`. Affected: `CloudReviewExplanationService`, `CloudReceiptAssistService`, `CloudCategorizationAssistService`, `CloudDedupeJudgeService`, `CloudReceiptItemCategorizationService`, `CloudWarrantyExtractionService`. Removed `shouldRedact` extra argument from `redactor.redactMerchant()` calls. `DashboardBriefingPromptFormatter` intentionally remains on `CloudPiiSanitizer` (it is a prompt formatter, not a cloud service; `DefaultCloudPayloadRedactor` wraps `CloudPiiSanitizer` anyway).
- **GroupLifecycleScenarioTest** created with 21 tests covering all 7 lifecycle methods + end-to-end scenario. 3 pre-existing test compilation errors fixed (`ExchangeRateDaoTest`, `PendingReviewDaoTest`, `WarrantyDaoTest`). Production + test compilation: BUILD SUCCESSFUL.

### Architecture Drift Updates (2026-05-09 — afternoon)
- **GeoCoordinate** created (`domain/location/GeoCoordinate.kt`) — validated coordinate value class rejecting NaN, Infinity, out-of-range, and null-island coordinates. Used by `AreaSpendingEngine` and `TravelDetectionEngine`.
- **MarketRateProvider** created (`domain/negotiation/MarketRateProvider.kt`) — interface for market-rate data consumed by SmartBillNegotiationEngine. New `negotiation/` domain package.
- **StaticMarketRateProvider** created (`data/negotiation/StaticMarketRateProvider.kt`) — `@Singleton @Inject` seed-data implementation in new `data/negotiation/` sub-package. No Dagger module needed.
- **AssistantHistoryMode** created (`domain/ai/model/AssistantHistoryMode.kt`) — enum (OFF/REDACTED/RAW) for conversation history redaction in AiChatRepositoryImpl.
- **SearchCursor** added (`domain/naturallanguage/NaturalLanguageExpenseQueryRepository.kt`) — keyset pagination cursor (date, id) replacing offset pagination.
- **QueryDataQuality** added (`domain/naturallanguage/NaturalLanguageSearchEngine.kt`) — data class tracking `unsupportedLocations` and `failedCurrencyConversions` flags on NL query results.
- **Keyset pagination refactored:** `NaturalLanguageSearchEngine.executeSearch()` and `NaturalLanguageExpenseQueryRepositoryImpl` migrated from offset pagination to keyset pagination. `ExpenseDao.getExpensesFilteredKeyset()` added as filtered Room query with categoryIds, transactionType, merchant LIKE, keyword LIKE, DESC ordering.
- **SpendingMapViewModel.onCenterOnMeRequested()** — new method deferring GPS fetch to explicit user action (W27 pattern). No automatic location request on ViewModel init.
- **AreaSpendingEngine.computeNormalized()** and **TravelDetectionEngine.computeNormalized()** — new `MoneyAggregate`-based computation paths using `AnalyticsCurrencyNormalizer` for currency-safe per-expense normalization.

### Architecture Drift Updates (2026-05-09 — analytics)
- **DailyBucketEngine** created (`domain/analytics/DailyBucketEngine.kt`) — `@Singleton @Inject` engine that builds exact-range daily expense buckets from `NormalizedAnalyticsInput`. Used by dashboard sparkline, analytics daily-trend chart, and cash-flow daily views. Buckets exactly cover the `PeriodRange` without "last N days from now" offset logic.
- **BudgetVsActualEngine** created (`domain/analytics/BudgetVsActualEngine.kt`) — `@Singleton @Inject` engine that compares actual category spending (from `NormalizedAnalyticsInput`) against `BudgetSnapshot` limits. Returns `BudgetVsActualResult` with per-category `percentageUsed` and `isOverBudget` flags, plus consolidated `AnalyticsDataQuality`.
- **AnalyticsInputAssembler converted** from `object` singleton to `@Singleton @Inject` class with constructor-injected `ExpenseRepository`, `AnalyticsCurrencyNormalizer`, `CurrencySettingsRepository`, `TimeProvider`, and `CategoryRepository`. New `build(expenses, homeCurrency, period, options)` overload accepts pre-fetched expense lists.
- **AnalyticsInputOptions** (`domain/analytics/AnalyticsInputAssembler.kt`) — new data class with `spendingOnly`, `excludeNotMine`, `includeDepositsForBehavior` flags.
- **AnalyticsViewModel** now wired to `AnalyticsInputAssembler` directly — eliminating the 5× `normalizeExpenses` calls that previously duplicated normalization across category, merchant, trend, daily-bucket, and budget-vs-actual pipelines.
- **All analytics engines (12+)** now accept `NormalizedAnalyticsInput` as their input type, sharing a single normalization pass per period rather than each engine performing its own raw-expense normalization.
- **confidencePenalty** (`Double`, default `0.0`) and **confidenceMultiplier** (`Double`, default `1.0`) added to `AnalyticsDataQuality`. Propagated from `AnalyticsCurrencyNormalizer` conversion failures through to downstream engines (`InsightsEngine`, `SpendingPaceCalculator`, `BudgetVsActualEngine`, etc.).
- **warnings: List<AnalyticsConversionWarning>** added to `AnalyticsDataQuality` (in addition to the legacy `conversionWarnings: List<String>`). Structured warnings carry `AnalyticsConversionWarningType` for programmatic handling.
- **categoryNameSnapshot** (`String?`) added to `NormalizedExpense` — snapshot of the category name at normalization time, preventing drift when category names are later edited.
- **BudgetVsActualEngine** logic extracted from `AnalyticsViewModel` — the ViewModel no longer performs inline budget-vs-actual aggregation.

### Architecture Drift Updates (2026-05-12 — deep pipeline debugging & fail-closed propagation)
- **RecurringRuleLifecycleCoordinator** created (`domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`) — `@Singleton @Inject` coordinator for rule-level lifecycle mutations (deactivate, delete). Atomically deactivates recurring rules and cleans up generated occurrences, reminders, and planned expenses in a single `withTransaction` block. Guards writes via `DatabaseWriteBarrier`. Dependencies: `AppDatabase`, `writeBarrier`, `timeProvider`, `ManualRecurringExpenseDao`, `RecurringOccurrenceDao`, `RecurringReminderDeliveryDao`, `PlannedExpenseDao`, `RecurringLifecycleEventDao`.
- **PrivacyDecision.FailClosed** added to `PrivacyDecision` sealed interface — new `FailClosed(reason)` variant alongside `Allowed`, `NotApplicable`, `Denied`. `blocksExecution()` returns `true` for both `Denied` and `FailClosed`. `reason()` returns the description for all variants. Over 30 callers across backup/export/geocoding/location/currency/warranty now use `blocksExecution()` or `reason()` instead of ad-hoc `is Denied` checks (P8-PR1 fail-closed propagation).
- **AccountBalanceProvider** (`domain/forecasting/AccountBalanceProvider.kt`) — interface with `currentBalance(currency)` for resolving current account balance. Designed for future implementations: `BankConnectionBalanceProvider`, `ManualBalanceProvider`.
- **NetCashflowBalanceProvider** (`domain/forecasting/NetCashflowBalanceProvider.kt`) — `@Singleton @Inject` fallback `AccountBalanceProvider`. Estimates balance from 90-day net cashflow using `MultiCurrencyRepository.getHomeCurrencyDepositTotal()` and `getHomeCurrencyPurchaseTotal()`. Used by `FinancialStressForecastEngine` for cashflow-aware stress testing.
- **PrivacyBlockedCard** (`ui/components/PrivacyBlockedCard.kt`) — new reusable Jetpack Compose `@Composable` card displaying a lock icon, "Feature disabled" title, and the specific `PrivacyBlocked` reason string. Used by `PrivacySettingsViewModel`, `BackupRestoreScreen`, and other screens for consistent privacy-denied UI messaging.
- **Fail-closed propagation completed** — 30+ callers across 10+ files now use `PrivacyDecision.blocksExecution()` for consistent fail-closed behavior: `DatabaseBackupRepositoryImpl`, `NotificationCaptureService`, `CompositeGeocodingService` (all 4 providers), `CloudDashboardBriefingService`, `CloudCategorizationAssistService`, `CloudDedupeJudgeService`, `CloudQueryInterpretationService`, `CloudReceiptAssistService`, `CloudReceiptItemCategorizationService`, `CloudReviewExplanationService`, `CloudWarrantyExtractionService`, `SmartReceiptAssistService`, `DailyBriefingWorker`, `DataRetentionWorker`, `LocationBackfillWorker`, `OverpassNearbyService`, `AndroidForegroundLocationProvider`.
- **PrivacySettingsViewModel** — updated to display `PrivacyBlockedCard` for each denied capability. `AiSettingsViewModel` updated for cloud AI policy changes.
- **DatabaseWriteBarrier** wired into `SubscriptionManagerEngine` and `EnhancedSplitManager` — both now check `checkWritesAllowed()` before performing writes (P0 restore-safety fix).
- **DataRetentionWorker** — enhanced retention expansion: now deletes receipts matching retention period, with comprehensive diagnostic logging.
- **ReceiptRepository** — heavy refactoring (175 lines changed): receipt lifecycle fixes, improved deduplication, textLines extraction.
- **MultiCurrencyRepository** — enhanced with budget-aware aggregation methods (116 lines modified). `getHomeCurrencyDepositTotal()` and `getHomeCurrencyPurchaseTotal()` added for `NetCashflowBalanceProvider`.
- **DeprecationLevel.ERROR sweep** — all deprecated DAO methods and unsafe aggregation paths escalated from WARNING to ERROR. 5 contract tests added: `LifecycleBarrierContractTest`, `MoneyContractTest`, `PrivacyStorageContractTest`, `RecurringDeactivateContractTest`, `SideEffectContractTest`.
- **SAFE engine P1 fixes** — `CurrencyCode` ASCII validation (rejects non-ASCII), `MoneyAggregate` finite guard (rejects NaN/Infinity), warranty privacy improvements, `MoneyBucket` finite guard.
- **New architecture documents**: `ENGINE_INTERACTION_MAP.md` (engine-to-pipeline impact matrix with risk levels) and `LEGAL_PATHS.md` (single-allowed-path architecture law for expense mutations, privacy, exports, etc.).
- **External review fixes** (21 P0/P1 + P2/P3 items): privacy gate unification, trend normalization, budget wiring, geocoding gate fix, purpose-aware redaction, `AccountBalanceProvider` integration, `NetCashflowBalanceProvider` implementation.
- **Leftover issues tracker** (`docs/LEFTOVER_ISSUES_PIPELINES_1_8.md`) — 55 P2/P3/enhancement items for all 8 pipelines tracked for future sprints.

### Architecture Drift Updates (2026-05-18 — currency normalization + privacy overhaul)

#### Currency Normalization Overhaul (CURR series, 10 commits)
- **DB upgraded to v131** (from v129). Migration 129→130 adds nullable email fields + hash columns to `email_receipt_source` (privacy). Migration 130→131 backfills `validDate` for legacy `exchange_rates` rows.
- **New `domain/core/money/` package** — 14 files: `ConversionOutcome` (sealed: Converted/Failed), `ConversionFailureType` enum, `RateBasis` enum (LATEST_AVAILABLE/TRANSACTION_DATE/PERIOD_START/PERIOD_END/FORECAST_DATE/PERIOD_MIDPOINT_ESTIMATE), `StaleRatePolicy`, `ConversionPath`, `ConversionQuality`, `MoneyNormalizationEngine`, `MoneyAggregateMetadata`, `MoneyAggregateResult` (sealed: Available/Unavailable), `HomeCurrencyForMoneyMath`, `BucketDatePolicy`, `NormalizationResult`, `NormalizedExpense`, `TransactionTypeFilter`.
- **`CurrencyConverter`** — new `convertOutcome()` returning typed result; fails immediately if historical basis requested without `atMillis` (no silent fallback); `convertMultiple()` refactored.
- **`MoneyAggregate`** — new `rateBasis` field, `quality`, metadata counters; `MoneyAggregateBuilder.fromBuckets()` enforces `RequireBucketDate`.
- **`ExchangeRateStoreAdapter`** — rejects `validDate=null/0` at storage boundary; `storeRate/storeRates` set `validDate = startOfDay(now)`.
- **`ExchangeRateDao`** — `getRate()` uses `ORDER BY validDate DESC`; new `getLatestRateForPair()`.
- **`HomeCurrencyResolution`** — sealed interface (`Resolved`/`FirstRunDefault`/`Failed`); `CurrencySettingsRepository.resolveHomeCurrency()` typed resolution.
- **`DashboardNormalizedInput`** — aggregates with `CurrencyDataQuality`, `CurrencyQualityUi`; `produceDashboardNormalizedInput()` method; typed `DashboardNormalizedInputResult`.
- **`BudgetForecastResult`** — typed unavailable state; `BudgetForecastingEngine.generateForecastResult` real implementation (no zero/UNKNOWN sentinel inference).
- **`ForecastRiskLevel.UNKNOWN`** added; `BudgetScreen` exhaustive matching; `BudgetForecast` entity updated for `rateBasis`.
- **All silent EUR fallbacks removed** from 10+ domain components: `AnalyticsRepository`, `AdvancedAnalyticsEngine`, `CashFlowCalculator`, `FinancialStressForecastEngine`, `HistoricalSpendingDistribution`, `FinancialHealthCalculator`, `FinancialHealthScoreV2`, `InvestmentTracker`, `SubscriptionManagerEngine`, `MonthlySavingsSweepUseCase`, `ExpenseUseCases`, `NarrativeGenerator`.
- **Legacy API deprecations** — `getHomeCurrencyPurchaseTotal` raised to `DeprecationLevel.ERROR`; legacy `fromBuckets` restricted to `LATEST_AVAILABLE` + `@Deprecated`.
- **Guard script**: `scripts/verify_money_boundaries.py` (G-MONEY-01 through G-MONEY-21 rules).
- **New docs**: `docs/currency/rate-basis-policy.md`, `docs/currency/money-aggregate-contract.md`, `docs/currency/money-boundary-guard.md`.

#### Global Privacy / Raw-Storage / Redaction Overhaul (PRIV series, 16 commits)
- **PrivacySettings load state** — `PrivacySettingsLoadState` sealed interface (`Loaded`/`FirstRunDefault`/`CorruptedFailClosed`); `FAIL_CLOSED_DEFAULTS` (all raw modes `DO_NOT_STORE`); corruption sentinel via `ReplaceFileCorruptionHandler`.
- **Raw Persistence Policy framework** — `RawSourceType` enum, `RawPersistencePolicy` (hashMode + storageMode), `RawPersistencePolicyResolver`, `RawContentSanitizer` HMAC-safe variants (removed `String.hashCode()`).
- **`SensitiveHashingService`** — interface + `DefaultSensitiveHashingService` (HMAC-SHA256); used for messageId hashing, fingerprint hashing, diagnostic sourceId hashing.
- **`SafePrivacyMetadata`** — blocks 14 sensitive key substrings; `put()` sanitizes base64/Bearer/IBAN/card numbers/file paths/JWTs → `[REDACTED]`; `putHash()` validates approved key set + hex-like value format.
- **Notification privacy** — `NotificationCaptureGate` checks settings + PrivacyGate before extras extraction; `NotificationPersistencePayload` sanitized for all 4 modes; `NotificationProcessingPipeline` title/text sanitized per `rawNotificationStorageMode`; `isPackageBlockedFast` returns true (fail-closed) until first cache emission.
- **Cloud/AI privacy** — `CloudPayloadPolicy` interface + `DefaultCloudPayloadPolicy`; `PreparedCloudPayload` contract for all 7 cloud providers; `CloudPayloadRedactor` replaced entirely by `CloudPayloadPolicy`; new `CloudPayloadPurpose` enum values (`BANK_STATEMENT_VALIDATION`, `BANK_TRANSACTION_CLASSIFICATION`, `EXPORT_SUMMARY`).
- **Bank privacy** — `BankTransactionPersistencePayload` hashes `providerTransactionId/accountId/counterparty`; `BankApiIntegration` idempotencyKey hashed, description/notes redacted per policy.
- **Export/Backup privacy** — `ExportPrivacyGate` with typed capabilities (`EXPENSE_EXPORT_RAW`/`ENCRYPTED`/`REDACTED`/`DEBUG_RAW_EXPORT`/`RAW_DATABASE_EXPORT`); added to composite `PrivacyGate`.
- **Privacy Gate wiring** — `PrivacyCapabilityHandlingPolicy` covering all 26 `PrivacyCapability` values; `CompositePrivacyGate` fails closed for unhandled sensitive capabilities; all secondary constructors changed from `Allowed` to `FailClosed` gate.
- **Retention system** — `RetentionTarget` interface + `RetentionPurgeResult`; `RetentionRegistry` with 5 registered targets; new `RetentionModule` (`di/RetentionModule.kt`); `DataRetentionWorker` injects `RetentionRegistry` instead of inline list; `EmailReceiptDao` has `redactSensitiveFieldsOlderThan()` (redact not delete).
- **Email privacy** — `EmailReceiptSource`: `emailSender`/`emailSubject` nullable; added `emailMessageIdHash` + `contentFingerprintHash` columns (DB v130); raw messageId only stored in `STORE_RAW` mode; HMAC-SHA256 for messageId hashing (no plaintext fallback — fails closed on hash failure); `createFingerprint` removed `hashCode().toString(16)` fallback.
- **Privacy audit** — `PrivacyAuditContext` typed audit context with `forCloudCall()` factory; `PrivacyAuditLogger` with typed `logDecision`/`logCloudCall` overloads.
- **Guard script**: `scripts/verify_privacy_boundaries.py` (G1-G13 rules).
- **New docs**: `docs/privacy/raw-storage-policy.md`.
- **18 new test files** for privacy behavioral verification.

### Architecture Drift Updates (2026-05-20 — Pipeline 4: Recurring Lifecycle Hardening & Single Writer)
- **DB upgraded to v141** (from v131). Migration 139→140 canonicalizes occurrence keys with full dedup strategy. Migration 140→141 adds FOREIGN KEY on `recurring_reminder_deliveries.occurrenceId` → `recurring_occurrences.id` with CASCADE delete.
- **RecurringRuleLifecycleCoordinator** — expanded to become the single writer for all rule lifecycle mutations: `createRule()`, `activateRule()`, `updateRule()`, `deactivateRule()`, `deleteRule()`. All operations atomic in `withTransaction`, guarded by `DatabaseWriteBarrier`, with durable lifecycle events. Deactivation deletes (not cancels) open PLANNED occurrences/planned rows for clean regeneration. Activation generates 12 months of future occurrences atomically. Direct dependencies: `RecurringOccurrenceExpander`, `OccurrenceConflictResolver`, `RecurringOccurrenceMaterializer`, `ExpenseDao`, `RecurringLifecycleEventWriter`.
- **OccurrenceGenerationOptions** created (`domain/recurring/lifecycle/OccurrenceGenerationOptions.kt`) — data class controlling reminder creation, windows, generation source, and past-due allowance during occurrence generation.
- **RecurringExpenseReconcileResult** created (`domain/recurring/lifecycle/RecurringExpenseReconcileResult.kt`) — sealed interface with 6 variants (Linked, Unlinked, Relinked, UpdatedLinkedSnapshot, NoMatch, Skipped) replacing opaque Boolean/Unit returns for link/unlink/reconcile ops.
- **RecurringOccurrenceStatus** created (`domain/recurring/lifecycle/RecurringOccurrenceStatus.kt`) — typed enum (PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED) replacing raw status strings. `RecurringOccurrenceTransitionPolicy` centralizes transition validation with `canTransition()`/`requireAllowed()`.
- **RecurringLifecycleEventWriter** — split into `writeCritical()` (always writes, returns Long, for provenance) and `writeDiagnostic()` (best-effort, swallows exceptions, for informational). `SafeEventMetadata` dependency removed.
- **BillReminderSettings + BillReminderSettingsRepository** created (`domain/reminder/BillReminderSettings.kt`, `domain/reminder/BillReminderSettingsRepository.kt`) — runtime dispatch settings (enabled/disabled, quiet hours). SharedPreferences-backed impl bound via new **ReminderSettingsModule** (`di/ReminderSettingsModule.kt`).
- **BillReminderWorker** — enhanced with post-claim revalidation (`getDispatchableClaimedReminder()`), `NotificationSendResult` sealed interface (Sent/Failed), runtime settings check, `ReminderSettingsRepository` injection.
- **TransactionUpdateKind** — expanded with `AMOUNT`, `DATE`, `CURRENCY`, `OWNERSHIP`, `PAYMENT_CORE`. New `affectsRecurringMatch()` centralizes which update kinds trigger reconciliation.
- **RecurringArchitectureGuardTest** created — 14 static architecture guard tests enforcing single-writer principal: no direct DAO mutation outside coordinator, no legacy `markBillPaid`, no raw `updateOccurrenceStatus` outside coordinator, critical events use `eventWriter` not direct DAO.
- **Pipeline4LifecycleGoldenTest** created — golden tests for create → update → deactivate → reactivate → delete lifecycle through repositories.
- **RecurringOccurrenceDao** — `updateLinkedPaymentSnapshot()` for in-place snapshot updates. `getPlannedIdsBySource()` added.
- **PlannedExpenseDao** — `fulfillByOccurrenceKey()` takes `expenseId`. `deleteOpenPlannedByRecurringRuleId()` for deactivation cleanup.
- **RecurringReminderDeliveryDao** — `suppressOpenDeliveriesForOccurrence()` takes `now` + reason. `markSentFromClaimed()`, `markFailedFromClaimed()`, `cancelClaimedDelivery()`, `reopenDeliveryForOccurrenceWindow()`, `recoverStaleClaimedDeliveries()`, `deleteByOccurrenceIds()` added.
- **Single-writer principal enforced** — `RecurringRuleLifecycleCoordinator` sole writer for rule lifecycle. Legacy `BillReminderManager.markBillPaid()` removed entirely (correct path: create actual expense → `linkExpenseToOccurrence()`).
- **5 new test files** — `RecurringArchitectureGuardTest`, `Pipeline4LifecycleGoldenTest`, 2 new instrumented migration tests in `MigrationContractTest`.

### Architecture Drift Updates (2026-05-11 — pipeline evaluation & closure)
- **Database version upgraded to v129** (from v124) — later superseded by v130→v131 in the currency/privacy overhaul. Migrations 124→129 add durable diagnostics tables (`operation_runs`, `operation_run_events`), expand `pipeline_diagnostic_events` with 9 new columns, add `correlationId`/`causationId` to `transaction_events`, and add `isTerminal`/`eventId` to `operation_run_events`.
- **WorkerRegistry** (`domain/workers/WorkerRegistry.kt`) — `object` with typed `Entry` list (specName + schedule lambda) for all 7 background workers. `scheduleAll(context)` iterates entries with `runCatching` for resilience. Replaces hardcoded lists in `RestoreMaintenanceMode.scheduleAllWorkers()` and `AppStartupCoordinator.scheduleStartupWork()` (P7-P1-07).
- **PrivacyBlocked** (`domain/privacy/PrivacyBlocked.kt`) — sealed interface with concrete subclasses: `CloudAiDisabled`, `ReceiptImageUploadDisabled`, `ExternalGeocodingDisabled`, `NotificationCaptureDisabled`, `RawExportDisabled`, `Custom`. All 4 privacy gates (`NotificationPrivacyGate`, `LocationPrivacyGate`, `CloudAiPrivacyGate`, `BackupPrivacyGate`) now return `PrivacyBlocked` instead of ad-hoc `Denied(reason)` strings. Provides consistent UI messaging for privacy-denied states.
- **PrivacySettings** — added `blockCloudAi: Boolean` field for explicit cloud AI blocking independent of the `cloudAiEnabled` toggle.
- **PrivacyAuditLogger** — now logs the specific `PrivacyBlocked` subclass (via `privacyBlockedType: String`) in audit events for richer diagnostics.
- **AccountingExportPolicy** (`domain/export/AccountingExportPolicy.kt`) — `@Inject` class providing `requireSingleCurrency()`, `requirePurchaseTransactions()`, and `validateGlobalDataset()` returning `GlobalDatasetValidation` with rowCount, distinctCurrencies, transactionTypes, and validation errors.
- **RestoreMaintenanceMode** — new `BACKUP_EXPORTING` mode added (allows DB reads but blocks writes during backup export). New `pauseAllWorkers()`/`resumeAllWorkers()` methods delegating to `WorkerRegistry.entries` for centralized worker lifecycle. `DatabaseReadBarrier` now also allows `BACKUP_EXPORTING` mode.
- **RestoreJournal** — new `ASSETS_RESTORING` state added for crash-safe asset/receipt-image recovery tracking (between DB restore and full completion).
- **DataQualityReport** (`domain/analytics/DataQualityReport.kt`) — unified data class aggregating `totalExpenses`, `expensesWithCurrency`, `expensesWithMerchant`, `expensesWithCategory`, `conversionConfidence` (0.0–1.0), and `warnings`. Factory `fromNormalization()` consumes `AnalyticsNormalizationResult`. Computed properties: `isReliable`, `qualityLabel`. Used by analytics, forecasting, and health engines as a shared quality contract.
- **NotificationCaptureService** — refactored for privacy-gate refresh: uses `PrivacyBlocked` for denied states, improved shutdown handling, `sourceFingerprint` sanitization via `RawContentSanitizer`.
- **EmailReceiptIngestionService** — heavy refactoring (278 lines changed): `emailMessageId` sanitization, improved lifecycle handling, privacy-gate integration.
- **ReceiptLifecycleCoordinator** — heavy refactoring (192 lines changed): textLines extraction, delete atomic snapshot, PARSE_FAILED handling, batch review support.
- **TransactionLifecycleCoordinator** — heavy refactoring (226 lines changed): eventLogged flag enforcement, bulk side effects, ghost duplicate cleanup, ERROR deprecation.
- **BudgetRepository / BudgetMonitor** — period-specific budget rate conversion: budgets now use `MultiCurrencyRepository` for currency-safe per-period aggregation rather than raw-amount comparisons.
- **MultiCurrencyRepository** — budget-aware aggregation methods added (176 lines modified): period-range-aware totals with consistent currency conversion for budget pipelines.
- **CSV export format** — version metadata comment line added (`# ExpenseTracker Export v2, rowCount=..., from=... to=...`). `CsvExpenseImporter` updated to parse/skip the comment line.
- **ExportOptionsViewModel** — uses CSV metadata line; enhanced export with `ExportTransaction` schema fields.
- **Pipeline diagnostics** — `PipelineDiagnosticEvent` entity extended with forensic fields; `NotificationProcessingPipeline` and other pipelines write richer diagnostic events.
- **WorkerExecutionGuard** — enhanced with checkpoints/yield points for cooperative cancellation during long-running worker operations (P3-P1-02).
- **RecurringLifecycleCoordinator** — unlink reopens PLANNED occurrence; bill reminder checkpoint/snooze/dismiss TODOs; occurrence projection fixes.
- **CashFlowCalculator** — currency-safe aggregation using `AnalyticsCurrencyNormalizer` for per-expense normalization instead of raw-amount sums.
- **SynthesisEngine** — `require` checks hardened with `PLANNED` filter to exclude planned-but-not-realized expenses from forecast calculation.
- **AppStartupCoordinator** — uses `WorkerRegistry.scheduleAll()` instead of hardcoded worker schedule calls.

### Architecture Drift Updates (2026-05-10 — universal gap closure & hotfix)
- **Database version upgraded to v124** (from v123). Migration 123→124 adds forensic/debug fields for pipeline diagnostics and data-integrity tracking.
- **GroupLifecycleEventEntity** + **GroupLifecycleEventDao** created (`data/database/entity/GroupLifecycleEventEntity.kt`, `data/database/dao/GroupLifecycleEventDao.kt`) — immutable audit log for group lifecycle transitions (create group, add/remove member, add expense, archive, delete, record settlement). Registered in AppDatabase entities and DaoModule.
- **PipelineDiagnosticEvent** + **PipelineDiagnosticEventDao** created (`data/database/entity/PipelineDiagnosticEvent.kt`, `data/database/dao/PipelineDiagnosticEventDao.kt`) — diagnostic tracking for all 8+ data pipelines (notification, transaction lifecycle, receipt, recurring, currency, budget/forecast, backup/restore, privacy/AI). Each pipeline writes diagnostic events on success/drop/failure. Used by `NotificationProcessingPipeline.writePipelineDiagnosticEvent()`.
- **WorkerRunLogger** (`domain/workers/WorkerRunLogger.kt`) + **WorkerRunLoggerImpl** — per-worker-run interface with start/success/skipped/retry/failure lifecycle. Writes to `BackgroundJobRun` table via `BackgroundJobRunDao`. Bound via new **WorkerModule** (`di/WorkerModule.kt`).
- **WorkerExecutionGuard** (`domain/workers/WorkerExecutionGuard.kt`) — structured guarded execution wrapper for workers: checks `RestoreMaintenanceMode`, creates `WorkerRunLogger` handle, wraps in try-catch, records outcome. Used by all 7 workers (replacing ad-hoc per-worker logging).
- **DatabaseReadBarrier** (`data/backup/DatabaseReadBarrier.kt`) and **DatabaseWriteBarrier** (`data/backup/DatabaseWriteBarrier.kt`) — `@Singleton` guards that throw `IllegalStateException` when operations are attempted during restore non-NORMAL modes. Provides finer-grained operation-level blocking beyond `RestoreMaintenanceMode`.
- **RawStorageMode** (`domain/privacy/RawStorageMode.kt`) — enum with 4 values: `STORE_RAW`, `STORE_REDACTED`, `STORE_METADATA_ONLY`, `DO_NOT_STORE`. Controls write-time privacy for raw notification/OCR/email content.
- **RawContentSanitizer** (`domain/privacy/RawContentSanitizer.kt`) — Kotlin `object` that applies `RawStorageMode` to raw OCR text, email subjects, and email senders at write time. Consumed by `ReceiptLifecycleCoordinator` (OCR) and `EmailReceiptIngestionService`.
- **EffectiveCloudAiPolicy** / **EffectiveCloudAiPolicyResolver** (`domain/privacy/EffectiveCloudAiPolicy.kt`) — resolves effective cloud AI policy from both `PrivacySettingsRepository` (data-layer privacy toggles) and `AiSettingsRepository` (domain-layer AI settings). Produces `EffectiveCloudAiPolicy` data class with `cloudAllowed`, `redactBeforeCloud`, `receiptImageUploadAllowed`, `bankStatementCloudAllowed` flags. Used by hybrid AI services for pre-flight policy checks.
- **GroupBalanceCalculator** (`domain/groups/GroupBalanceCalculator.kt`) — `@Singleton @Inject` calculator with `GroupMemberBalance` data class. Computes per-member net balance from paid totals, owed shares, and settlements. Used by `GroupLifecycleCoordinator` for balance-aware operations.
- **CsvCellSanitizer** enhanced — now also handles single-quote prefix (`'`) injection vector, coordinated with `AccountingExporters` for consistent sanitization across all export paths.
- **JsonExpenseImporter** (`util/JsonExpenseImporter.kt`) — JSON bulk import engine supporting v1 (flat) and v2 (enriched) row formats. Routes through `TransactionLifecycleCoordinator` with `DeduplicationMode.BULK_IMPORT`. Supports import result reporting with per-row status tracking.
- **ImportCoordinator** (`util/ImportCoordinator.kt`) — orchestrates CSV/JSON import flows: format detection via content inspection, delegates to `CsvExpenseImporter` or `JsonExpenseImporter`, reports `ImportResult` with summary statistics.
- **SubscriptionModule deleted** — confirmed removed; `SubscriptionManagerEngine` is auto-provided by `@Singleton @Inject constructor`. No Dagger cycle remaining.
- **PrivacySettings** — added `receiptImageCloudEnabled` and `bankStatementAiEnabled` boolean fields for fine-grained cloud AI toggles.
- **Additional CI guard** — `check_direct_time_calls.kts` (`scripts/guards/`) enforces `TimeProvider` usage and flags direct `System.currentTimeMillis()` / `Instant.now()` calls.
- **MoneyAmount.kt** — `format()` extension now handles zero, negative, and large amounts correctly (M12 fix). `MoneyAggregate.kt` now guards against division by zero in `perDayAverage()` (M11 fix).
- **ExportOptionsViewModel** — switched CSV/JSON export to use `ExportTransaction` schema fields (7C/7D), aligned with new `ExpenseExportMapper` and `ExportTransaction` domain model.

### Architecture Drift Updates (2026-05-09 — groups/tax/export/investment)
- **TaxRateProvider** created (`domain/tax/TaxRateProvider.kt`) — interface for tax-rate data consumed by `TaxEstimator`. Returns `TaxRateResult` with standard/reduced VAT rates per country+region, with `TaxRateMetadata` describing source confidence.
- **DemoTaxRateProvider** created (`data/tax/DemoTaxRateProvider.kt`) — `@Singleton @Inject` seed-data implementation with static EUR rates for GR/DE/FR/IT/ES/GB/US. Metadata declares LOW confidence; no Dagger module needed.
- **CsvCellSanitizer** created (`domain/export/CsvCellSanitizer.kt`) — Kotlin `object` that neutralizes leading `=`, `+`, `-`, `@` characters and strips tabs/newlines from CSV cells, preventing formula injection in exported files.
- **InvestmentDataQuality** added (`domain/investment/InvestmentTracker.kt`) — data class tracking investment price freshness: `isPartial`, `staleHoldingCount` (7-day threshold), `missingPriceCount`, `lastUpdatedAt`. Returned by `getPortfolioSummaryAggregate()` and used for portfolio data-quality warnings in investment UI.
- **GroupLifecycleCoordinator** — `emitLifecycleEvent()` now dispatches real post-commit side effects: `BudgetMonitor.checkBudgets()` and `TransactionSideEffectDispatcher.dispatchOnCreated()` for group expenses (G02 deferred side-effects). No Dagger cycle — depends on domain interface, not data-layer implementation.
- **TaxEstimator** — `TaxEstimate` and `TaxYearSummary` now carry `MoneyAggregate` fields (`deductibleAggregate`, `vatAggregate`, `taxableIncomeAggregate`, `incomeAggregate`, `estimatedTaxAggregate`) replacing raw Double totals with per-currency-bucket aggregates via `MoneyAggregateBuilder.fromBuckets()`. `buildDeductibleAggregate()` and `buildIncomeAggregate()` are private methods producing `MoneyAggregate` from expense data (T01).

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Screens   │  │  ViewModels  │  │ Components   │              │
│  │  (Compose)  │  │   (State)    │  │  (Reusable) │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└────────────────────────────┬───────────────────────────────────────┘
                             │ calls
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                       DOMAIN LAYER                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Engines    │  │   Models     │  │   Services   │              │
│  │ (Business    │  │  (Data       │  │  (Interfaces│              │
│  │   Logic)     │  │   Classes)   │  │   & Abstr.) │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└────────────────────────────┬───────────────────────────────────────┘
                             │ uses
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                        DATA LAYER                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ Repositories │  │    DAOs      │  │  Services    │              │
│  │  (Data       │  │  (Database   │  │  (Android    │              │
│  │   Access)    │  │   Queries)    │  │   System)    │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Layer Structure

### UI Layer (`ui/`)
```
ui/
├── MainActivity.kt              # App entry, navigation, deep links
├── MainViewModel.kt             # App-wide state
├── navigation/                  # NavigationDestination + controller
├── theme/                       # Compose theming
│   └── Theme.kt                 # Material 3 colors/typography
├── components/                  # Reusable composables
│   ├── BentoCard.kt            # Dashboard card layout
│   ├── FinancialWeatherCard.kt  # Forecast display
│   ├── BudgetBlockPartyCard.kt # Budget visualization
│   └── ...
├── screens/                     # Shell screens + feature surfaces
│   ├── home/                    # Home / dashboard
│   ├── transactions/            # Activity / transaction flow
│   ├── review/                  # Review queue
│   ├── budget/                  # Budget + budget detail
│   ├── analytics/               # Analytics views
│   ├── map/                     # Spending map / location views
│   ├── cashflow/                # Cash flow calendar
│   ├── bank/                    # Bank connections
│   ├── investment/              # Investment portfolio
│   ├── currency/                # Currency management
│   ├── tax/                     # Tax configuration
│   └── ...
└── util/
    ├── HapticFeedback.kt       # Haptic feedback utilities
    └── ClipboardAmountParser.kt # Clipboard parsing
```

### Domain Layer (`domain/`)
```
domain/
├── ai/                          # AI capabilities, policies, models, use cases
├── alerts/                      # Anomaly alert orchestration
├── analytics/                   # Insights, totals, advanced analytics
├── bank/                        # Bank API integration
├── budget/                      # Budget management
├── business/                    # Business expense reporting
├── categorization/               # Merchant categorization pipeline
├── carbon/                      # Carbon footprint
├── cashflow/                    # Cash flow calculator
├── forecasting/                 # Monte Carlo + stress forecast engines
├── groups/                      # Shared expense and settlement flows
├── location/                    # Location enrichment and POI lookup
├── parser/                      # Notification parsing
├── receipt/                     # OCR, receipt processing, lifecycle models
│   ├── ReceiptSourceType.kt     # Enum: CAMERA, GALLERY, FILE_IMPORT, EMAIL, etc.
│   ├── ReceiptDocumentType.kt   # Enum: RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, etc.
│   ├── ReceiptProcessingStatus.kt # Enum: CAPTURED → DELETED (14 values)
│   ├── EmailReceiptData.kt      # Structured email receipt data
│   └── lifecycle/               # Receipt lifecycle coordinator + services
│       ├── ReceiptLifecycleCoordinator.kt   # Single entry point for all receipt processing
│       ├── ReceiptMatchLifecycleService.kt  # Lifecycle-aware receipt match mutations + events
│       ├── ReceiptLinkService.kt            # Centralized receipt-expense linking (multi-link)
│       ├── ReceiptAssetStore.kt             # File persistence, hash computation, backup manifest
│       ├── ReceiptInputValidator.kt         # URI / MIME / size validation
│       ├── ReceiptDuplicateDetector.kt      # 3-signal dedup (hash, text, semantic)
│       ├── ReceiptSideEffectDispatcher.kt   # Document-type-gated downstream effects
│       └── BankStatementLifecycleProcessor.kt # Statement-specific processing
├── split/                       # Split-template and expense splitting logic
├── privacy/                     # Privacy capability gates, audit logger, sanitizer, storage modes
│   ├── PrivacyCapability.kt    # Enum of 21 gated capabilities
│   ├── PrivacyGate.kt          # Interface for capability evaluation
│   ├── PrivacyDecision.kt      # Sealed: Allowed / Denied(reason)
│   ├── PrivacyBlocked.kt       # Sealed interface: CloudAiDisabled, ReceiptImageUploadDisabled, etc.
│   ├── PrivacySettings.kt      # Data class with 10+ privacy toggles + 2 retention settings
│   ├── PrivacySettingsRepository.kt  # Interface for reading/writing settings
│   ├── PrivacyAuditLogger.kt   # Logs every gate check decision
│   ├── NotificationPrivacyGate.kt    # Guards notification capture/allowlist
│   ├── CloudAiPrivacyGate.kt         # Guards all CLOUD_AI_* capabilities
│   ├── LocationPrivacyGate.kt        # Guards geocoding, GPS, backfill, Overpass
│   ├── BackupPrivacyGate.kt          # Guards raw/encrypted backup
│   ├── CompositePrivacyGate.kt       # Chains all gates; first Denied short-circuits
│   ├── RedactionSanitizer.kt         # PII redaction before cloud calls
│   ├── RawStorageMode.kt             # Enum: STORE_RAW / STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE
│   ├── RawContentSanitizer.kt        # Applies RawStorageMode to OCR/email content at write time
│   └── EffectiveCloudAiPolicy.kt     # Resolves effective cloud AI policy from privacy + AI settings
├── service/                     # Domain service interfaces
├── usecase/                     # Use cases / orchestration
├── model/                       # Shared domain models
├── negotiation/                  # Market-rate provider for bill negotiation
├── core/
│   ├── money/                   # Type-safe money primitives (CurrencyCode, MoneyAmount, etc.)
│   └── time/                    # Typed time period models (PeriodRange, PeriodKind)
├── transaction/                 # Transaction lifecycle models
│   ├── ExpenseSource.kt         # Enum of 14 expense origin sources
│   ├── LifecycleEventType.kt    # Enum of 14 lifecycle event types
│   ├── DeduplicationMode.kt     # Enum of deduplication strategies
│   ├── CreateExpenseRequest.kt  # Source-neutral creation request (40+ fields)
│   ├── CreateExpenseResult.kt   # Sealed result (Created, DuplicateSkipped, etc.)
│   ├── ExpenseUpdates.kt        # Patch-style update model
│   └── lifecycle/               # Lifecycle coordinator + dispatcher
│       ├── TransactionLifecycleCoordinator.kt    # Single entry point for ALL expense CUD
│       └── TransactionSideEffectDispatcher.kt    # Post-creation side effects (budget, anomaly, learning)
├── recurring/                   # Recurring occurrence lifecycle
│   ├── RecurringOccurrenceExpander.kt    # Expands recurrence rules into concrete occurrences
│   ├── OccurrenceConflictResolver.kt     # Resolves candidates vs actual expenses
│   ├── RecurringPlanProjectionService.kt # Materialises planned expenses from occurrences
│   └── lifecycle/               # Recurring lifecycle coordinator + materializer
│       ├── RecurringLifecycleCoordinator.kt      # Primary entry point for occurrence generation
│       ├── RecurringRuleLifecycleCoordinator.kt  # Single writer for rule CRUD lifecycle
│       ├── RecurringOccurrenceMaterializer.kt    # Persists occurrences + creates reminders
│       ├── OccurrenceGenerationOptions.kt        # Controls reminder creation during generation
│       ├── RecurringExpenseReconcileResult.kt    # Sealed result for link/unlink operations
│       └── RecurringOccurrenceStatus.kt          # Typed enum replacing raw status strings
├── reminder/                    # Bill reminder manager
├── subscription/                # Subscription detection / management
├── tax/                         # Tax configuration and estimation
├── export/                      # Export flows
├── performance/                 # Performance helpers
├── debug/                       # Debug-only diagnostics
├── diagnostics/                 # Database integrity
├── dto/                         # Data transfer objects
├── util/                        # Shared utilities
└── workers/                     # Worker specifications, run logging, execution guard, and registry
    ├── WorkerSpec.kt            # Worker default specs (interval, constraints, backoff)
    ├── WorkerSpecScheduler.kt   # Centralized scheduling with version-change detection
    ├── WorkerRunLogger.kt       # Per-run lifecycle tracking (start/success/skipped/retry/failure)
    ├── WorkerExecutionGuard.kt  # Structured guarded execution wrapper
    └── WorkerRegistry.kt        # Single source-of-truth for all 7 workers (specName + schedule lambda)
```

### Data Layer (`data/`)
```
data/
├── repository/                   # Data access (single source of truth)
├── backup/                       # **NEW — Phase 9: .costbackup bundle format + restore engine**
│   ├── CostbackupBundle.kt      # AES-256-GCM encrypted ZIP: header + manifest + DB + receipt images + checksums
│   ├── RestoreMaintenanceMode.kt # 8-state mode manager; pauses 7 workers + notification capture during restore
│   ├── RestoreJournal.kt        # Crash-safe 8-state restore journal (PREPARING → COMPLETE/FAILED)
│   └── BackupVerifier.kt        # Full 56-entity 3-tier verification (EXACT / VALIDITY / OPTIONAL)
├── ai/provider/                 # Cloud + on-device AI providers
├── email/provider/              # Email receipt parsers (Amazon, Uber, Apple, etc.)
├── location/                    # Location services and geocoding implementations
│   ├── CompositeGeocodingService.kt    # Multi-provider fallback chain
│   ├── NominatimGeocodingService.kt    # OpenStreetMap geocoding
│   ├── GeoapifyGeocodingService.kt     # Geoapify API geocoding
│   ├── GooglePlacesGeocodingService.kt # Google Places API geocoding
│   ├── PhotonGeocodingService.kt       # Photon API geocoding
│   ├── LocationBackfillWorker.kt       # Periodic location backfill worker
│       └── MerchantKeyBackfillWorker.kt    # One-shot merchant key backfill
├── negotiation/                  # Market-rate data implementations
│   └── StaticMarketRateProvider.kt     # @Singleton @Inject seed-data impl
├── tax/                          # Tax-rate data implementations
│   └── DemoTaxRateProvider.kt         # @Singleton @Inject seed-data impl
├── security/                    # Secure storage / crypto helpers
├── speech/                      # Speech input services
├── privacy/                     # **NEW — Privacy data layer**
│   ├── PrivacySettingsRepositoryImpl.kt  # DataStore-backed settings
│   ├── BackupEncryptionService.kt        # AES-256-GCM encrypt/decrypt
│   ├── ExportAnonymizer.kt               # Strips raw text from exports
│   └── DataRetentionWorker.kt            # WorkManager purging worker
├── database/
│   ├── AppDatabase.kt          # Room database (v141) — 64 entities registered
│   ├── entity/                  # Room entities across finance, AI, groups, location, settings, and privacy
│   │   ├── RecurringLifecycleEvent.kt   # Phase 5b — audit log for recurring occurrences
│   │   ├── PrivacyAuditEvent.kt         # Phase 6 — privacy gate audit log
│   │   ├── GroupLifecycleEventEntity.kt # Group lifecycle events (group_lifecycle_events table)
│   │   └── PipelineDiagnosticEvent.kt   # Pipeline diagnostic event tracking (pipeline_diagnostic_events table)
│   ├── dao/                     # Room DAOs
│   │   ├── RecurringLifecycleEventDao.kt
│   │   ├── PrivacyAuditDao.kt
│   │   ├── GroupLifecycleEventDao.kt
│   │   └── PipelineDiagnosticEventDao.kt
│   ├── model/                   # Database models
│   └── converter/               # Type converters
├── backup/                      # Backup infrastructure additions
│   ├── DatabaseReadBarrier.kt   # Operation-level read blocking during restore
│   └── DatabaseWriteBarrier.kt  # Operation-level write blocking during restore
├── service/
│   └── AndroidNotificationService.kt # Android notifications
└── provider/
    └── MerchantCategoryProvider.kt # Pre-defined categories
```

### Startup / Background Pipeline (`startup/`)
```text
MainApplication
  └─ AppStartupDelegate
       └─ AppStartupCoordinator
             ├─ checkRestoreJournal()    ← Phase 9: crash recovery before any work
             │    └─ RestoreJournal.checkAndRecover() → RecoveryResult
             │         (NoAction / CompleteClean / CleanedNonDestructive /
             │          RecoveredFromSwap / CriticalRecoveryRequired)
             ├─ scheduleStartupWork()    ← uses WorkerRegistry.scheduleAll()
             ├─ AppBackgroundLifecycleObserver
            └─ WorkManager jobs
               ├─ DailyBriefingWorker         (Phase 8 — every 24h, privacy-gated)
               ├─ LocationBackfillWorker      (Phase 8 — every 12h, overwrite guard)
               ├─ MerchantKeyBackfillWorker   (Phase 8 — one-shot, legacy backfill)
               ├─ WarrantyExpirationWorker    (Phase 8 — every 24h, idempotent)
               ├─ BillReminderWorker          (Phase 8 — every 6h, disabled by default)
               ├─ ReceiptMatchingWorker       (Phase 8 — every 2h, automated matching)
               └─ DataRetentionWorker         (Phase 6 — every 24h)
                                    ↑ All 7 workers cancelled by
                                      RestoreMaintenanceMode during restore
```

---

## Data Flow

### Notification → Expense Flow
```
NotificationCaptureService (Android)
        ↓
NotificationTextParts.extract()   ← unified API: resolves text from notification extras in a single pass
        ↓
AppParserRegistry → Specific Parser (GreekBank, Revolut, etc.)
       ↓
ConfidenceRouter → Determine confidence level
       ↓
CategorizationEngine → Assign category
       ↓
NotificationRepository → Save to DB
       ↓
ReviewQueueRepository → Add to review queue (if needed)
       ↓
ReviewScreen (UI) → User approves/rejects
       ↓
TransactionLifecycleCoordinator.createExpense()
       │  [validate → normalize → dedupe → insert atomic → event log]
       ↓
TransactionSideEffectDispatcher.dispatchOnCreated()
       │  [budget check → anomaly alert → pattern learning]
       ↓
Expense persisted + lifecycle event recorded
```

### Receipt → AI Categorization Flow
```
ReceiptScanScreen
    ↓
ReceiptOcrService → ReceiptParser
    ↓
CategorizeReceiptItemsUseCase / receipt AI providers
    ↓
ReceiptRepository → item categorization entities
    ↓
Receipt review UI / corrections
```

### Forecast Flow
```
HomeScreen
    │
    ▼
HomeViewModel
    │
    ▼
FinancialWeatherRepository
    │
    ├──► BudgetRepository ──────────────► BudgetCalculator
    │                                        │
    ├──► RecurringExpenseRepository ──────► SynthesisEngine
    │                                        │
    └──► ExpenseRepository ──────────────► NarrativeGenerator
                                                      │
                                                      ▼
                                              FinancialForecast
                                                      │
                                                      ▼
                                              HomeScreen (UI)
```

---

## Key Components

### Main Entry Points
| Component | File | Purpose |
|-----------|------|---------|
| Application | `MainApplication.kt` | Hilt + WorkManager configuration |
| Startup delegate | `startup/AppStartupDelegate.kt` | Hilt entry-point bootstrap |
| Startup coordinator | `startup/AppStartupCoordinator.kt` | Lifecycle observer + startup jobs |
| Main Activity | `ui/MainActivity.kt` | Navigation host + deep links |
| Database | `data/database/AppDatabase.kt` | Room DB v141 |
| NotificationCaptureService | `service/NotificationCaptureService.kt` | Android notification listener service |

### Core Engines
| Engine | File | Purpose |
|--------|------|---------|
| Forecast | `domain/logic/SynthesisEngine.kt` | Month-end prediction (deterministic) |
| Monte Carlo | `domain/forecasting/MonteCarloSpendingSimulator.kt` | Probabilistic spending forecast (stochastic) |
| Budget | `domain/budget/BudgetMonitor.kt` | Budget alerts |
| Categorization | `domain/categorization/CategorizationEngine.kt` | Auto-categorization (5-layer pipeline) |
| Recurring | `domain/logic/RecurringExpenseEngine.kt` | Pattern detection |
| Insights | `domain/analytics/InsightsEngine.kt` | Spending insights coordinator |
| Advanced Analytics | `domain/analytics/AdvancedAnalyticsEngine.kt` | Higher-order analytics surface |
| Spending Pace | `domain/analytics/SpendingPaceCalculator.kt` | Pace calculation |
| Anomaly Detection | `domain/analytics/AnomalyDetector.kt` | Unusual transactions |
| Month Comparison | `domain/analytics/MonthlyComparisonCalculator.kt` | Month-vs-month comparison |
| Category Insights | `domain/analytics/CategoryInsightEngine.kt` | Category analysis |
| Merchant Insights | `domain/analytics/MerchantInsightEngine.kt` | Merchant patterns |
| Day of Week | `domain/analytics/DayOfWeekAnalyzer.kt` | Day patterns |
| Daily Buckets | `domain/analytics/DailyBucketEngine.kt` | Exact-range daily expense buckets from NormalizedAnalyticsInput |
| Budget vs Actual | `domain/analytics/BudgetVsActualEngine.kt` | Category-level budget-limit vs actual-spending comparison |
| Totals Aggregation | `domain/analytics/TotalsAggregationEngine.kt` | Period totals aggregation |
| Dashboard Widgets | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Dashboard widget computation |
| Dashboard Data | `domain/usecase/dashboard/DashboardDataProvider.kt` | Dashboard data provider |
| AI Follow-Through | `domain/ai/...` | Recommendation, assistant, and receipt intelligence flows |
| AssistantHistorySettings | `domain/ai/model/AssistantHistoryMode.kt` | Enum: OFF/REDACTED/RAW for conversation history redaction |
| Transaction Lifecycle | `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | Single entry point for ALL expense CUD |
| Receipt Lifecycle | `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | Single entry point for ALL receipt processing |
| Group Lifecycle | `domain/groups/GroupLifecycleCoordinator.kt` | Domain-level coordinator for group lifecycle: create, add/remove member, add expense, archive, permanent-delete, record settlement (7 methods, 8 invariants). `emitLifecycleEvent()` dispatches real post-commit side effects (budget check + expense side-effect dispatch). |
| Tax Rate Provider | `domain/tax/TaxRateProvider.kt` | Interface for tax-rate data (standard/reduced VAT rates per country+region) consumed by TaxEstimator |
| Privacy Gate | `domain/privacy/CompositePrivacyGate.kt` | Chains 4 privacy sub-gates |

### New Categorization Components (Feb 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Greeklish Normalizer | `domain/categorization/GreeklishNormalizer.kt` | Normalize Greek to Latin |
| Merchant Canonicalizer | `domain/categorization/MerchantCanonicalizer.kt` | Canonical merchant names |
| Semantic Keyword Matcher | `domain/categorization/SemanticKeywordMatcher.kt` | Keyword-based matching |
| Contextual Inference | `domain/categorization/ContextualInferenceEngine.kt` | Inference based on amount/time |
| Category Keywords | `domain/categorization/CategoryKeywords.kt` | Pre-defined keyword mappings |

### Monte Carlo Spending Simulator (Mar 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Simulator Engine | `domain/forecasting/MonteCarloSpendingSimulator.kt` | 1000-iteration Monte Carlo simulation |
| Result Model | `domain/forecasting/MonteCarloResult.kt` | Percentiles (P10/P25/P50/P75/P90) |
| Distribution Builder | `domain/forecasting/HistoricalSpendingDistribution.kt` | Weekly aggregation + log-normal fitting |
| Quality Assessor | `domain/forecasting/DataQualityAssessor.kt` | Confidence scoring |
| UI Card | `ui/components/MonteCarloForecastCard.kt` | Dashboard widget display |

### Location Enrichment System (Mar 2026) - ALL 5 FEATURES IMPLEMENTED
| Component | File | Purpose |
|-----------|------|---------|
| Composite Geocoder | `data/location/CompositeGeocodingService.kt` | Multi-provider fallback chain |
| Nominatim | `data/location/NominatimGeocodingService.kt` | OpenStreetMap |
| Geoapify | `data/location/GeoapifyGeocodingService.kt` | Geoapify API |
| Google Places | `data/location/GooglePlacesGeocodingService.kt` | Google Places API |
| Photon | `data/location/PhotonGeocodingService.kt` | Photon API |
| Location Resolver | `domain/location/LocationResolver.kt` | Domain coordination |
| Location Models | `domain/location/LocationModels.kt` | Location domain models |
| GeoCoordinate | `domain/location/GeoCoordinate.kt` | Validated coordinate value class rejecting NaN/Infinity/out-of-range/null-island |
| Map Screen | `ui/screens/map/SpendingMapScreen.kt` | Map visualization |

### Market Rate & Negotiation (May 2026)
| Component | File | Purpose |
|-----------|------|---------|
| MarketRateProvider | `domain/negotiation/MarketRateProvider.kt` | Interface for market-rate data consumed by SmartBillNegotiationEngine |
| StaticMarketRateProvider | `data/negotiation/StaticMarketRateProvider.kt` | @Singleton @Inject seed-data implementation with no Dagger module needed |

### Tax Rate Provider & Export Sanitizer (May 2026)
| Component | File | Purpose |
|-----------|------|---------|
| TaxRateProvider | `domain/tax/TaxRateProvider.kt` | Interface for tax-rate data consumed by TaxEstimator. Returns TaxRateResult with standard/reduced VAT rates, currency, country, region. |
| DemoTaxRateProvider | `data/tax/DemoTaxRateProvider.kt` | @Singleton @Inject seed-data implementation with static EUR rates for EU countries plus GB/US. No Dagger module needed. |
| CsvCellSanitizer | `domain/export/CsvCellSanitizer.kt` | Kotlin `object` — centralized CSV formula injection prevention. Neutralizes leading `=`, `+`, `-`, `@` and strips tabs/newlines. Used by all CSV export paths. |

### Investment Data Quality (May 2026)
| Component | File | Purpose |
|-----------|------|---------|
| InvestmentDataQuality | `domain/investment/InvestmentTracker.kt` | Data class tracking price freshness: `isPartial`, `staleHoldingCount` (7-day threshold), `missingPriceCount`, `lastUpdatedAt`. Returned by `getPortfolioSummaryAggregate()` alongside `PortfolioSummary` and `MoneyAggregate`. |

### TaxEstimator MoneyAggregate Output (May 2026)
`TaxEstimate` and `TaxYearSummary` (both in `domain/tax/TaxEstimator.kt`) now carry `MoneyAggregate` fields — `deductibleAggregate`, `vatAggregate`, `taxableIncomeAggregate`, `incomeAggregate`, `estimatedTaxAggregate` — replacing raw Double totals with per-currency-bucket aggregates. Backed by `buildDeductibleAggregate()` and `buildIncomeAggregate()` private methods using `MoneyAggregateBuilder.fromBuckets()`.

### Natural Language Search — Pagination & Data Quality (May 2026)
| Component | File | Purpose |
|-----------|------|---------|
| SearchCursor | `domain/naturallanguage/NaturalLanguageExpenseQueryRepository.kt` | Keyset pagination cursor (date, id) for NL query results |
| QueryDataQuality | `domain/naturallanguage/NaturalLanguageSearchEngine.kt` | Data class tracking unsupportedLocations and failedCurrencyConversions on NL queries |

**Keyset pagination:** `NaturalLanguageSearchEngine.executeSearch()` and `NaturalLanguageExpenseQueryRepositoryImpl` migrated from offset pagination to keyset pagination via `SearchCursor`. `ExpenseDao.getExpensesFilteredKeyset()` added as the backing filtered Room query.

### Advanced Analytics Features (Mar 2026)
... (same high-level mapping as before) ...

### Parsers (Notification Processing)
| Parser | File | Handles |
|--------|------|---------|
| Greek Bank | `domain/parser/parsers/GreekBankParser.kt` | NBG, Alpha, Eurobank, Piraeus |
| Revolut | `domain/parser/parsers/RevolutParser.kt` | Revolut app |
| Google Wallet | `domain/parser/parsers/GoogleWalletParser.kt` | Google Pay |
| SMS | `domain/parser/parsers/SmsParser.kt` | SMS bank notifications |
| Generic | `domain/parser/GenericTransactionParser.kt` | Fallback parser |

---

## Dependency Injection

### Hilt Modules (27 total)
- **Core:** `DatabaseModule`, `DaoModule`, `DispatchersModule`, `ApplicationScope`, `TimeModule`, `ServiceModule`, `WorkerModule`
- **AI:** `AiModule`, `OcrImprovementsModule`, `NaturalLanguageModule`
- **Dashboard:** `DashboardContractsModule`, `DashboardAnomalyModule`
- **Finance:** `CashFlowModule`, `SavingsModule`, `SavingsRepositoryBindingsModule`, `CurrencyModule`, `TaxModule`, `ExportModule`
- **Shared expense / groups:** `GroupsModule`, `BackupRepositoryModule`
- **Location / network:** `LocationResolverPortsModule`, `NetworkModule`
- **Security & privacy:** `SecurityModule`, `PrivacyModule`, `ParserModule`, `ReceiptParsingModule`, `EmptyStateModule`, `EmptyStatePresentationModule`, `EmailIngestionModule`

### Key Bindings
- `AppDatabase` from `DatabaseModule`
- DAO singletons from `DaoModule`
- typed repository + engine bindings per feature module
- secure storage and API-key bindings through the security module
- location provider abstractions through the location module

---

### Multi-Currency Architecture (May 2026)

A 7-phase refactoring (~70 files) introduced type-safe money primitives and wired currency-aware aggregation into all 10+ financial pipelines.

#### New `domain/core/money/` Package

| Type | File | Purpose |
|------|------|---------|
| `CurrencyCode` | `domain/core/money/CurrencyCode.kt` | Type-safe ISO 4217 wrapper (inline value class). Replaces raw `String` currency codes. |
| `MoneyAmount` | `domain/core/money/MoneyAmount.kt` | Amount + currency pair. Prevents mixed-currency arithmetic via `require()` in `plus()`/`minus()`. |
| `ConvertedMoney` | `domain/core/money/ConvertedMoney.kt` | Full conversion trace: original + converted + rate + timestamp + `ConversionStatus`. |
| `MoneyBucket` | `domain/core/money/MoneyBucket.kt` | Per-currency subtotal (currency, amount, transaction count). Used before conversion. |
| `MoneyAggregate` | `domain/core/money/MoneyAggregate.kt` | **Primary aggregation return type.** Replaces raw `Double`. Contains display amount, source buckets, conversion failures, `isPartial` flag. |
| `ConversionFailure` | `domain/core/money/ConversionFailure.kt` | Records failed conversions with `FailureReason` (MISSING_RATE, INVALID_AMOUNT, RATE_STALE, UNKNOWN). |
| `CurrencyAssumption` | `domain/core/money/CurrencyAssumption.kt` | Enum: `UNKNOWN`, `ASSUMED_HOME_CURRENCY`, `ASSUMED_LEGACY_EUR`, `USER_CONFIRMED`, `PARSED_FROM_SOURCE`. Prevents silent EUR defaults. |
| `MoneyMappers` | `domain/core/money/MoneyMappers.kt` | Bridge from legacy `ConversionResult`/`FailedConversion` → new `ConvertedMoney`/`ConversionFailure`. |
| `MoneyFormatUtils` | `domain/core/money/MoneyFormatUtils.kt` | `MoneyAmount` extension functions (`formatMoney()`, `formatMoneyCompact()`, `formatMoneyWithSign()`) delegating to `CurrencyFormatter`. |

#### `MultiCurrencyRepository` — Canonical Aggregation Backbone

**File:** `data/repository/MultiCurrencyRepository.kt`

The central aggregation bridge. Data flow:

```
ExpenseDao (DAO)
    │ getAllSpentBetweenByCurrency(startDate, endDate)
    ▼
List<CurrencyTotal>  ← per-currency grouped SQL (not raw mixed sum)
    │
    ▼
CurrencyConverter.convertMultiple(amounts, homeCurrency)
    │
    ▼
MoneyAggregate  ← displayAmount, sourceBuckets, conversionFailures, isPartial
    │
    ▼
UI (HomeScreen, BudgetScreen, AnalyticsScreen, etc.)
```

Key methods: `getTotalExpensesInHomeCurrency()`, `getHomeCurrencyCategoryTotals()`, `getHomeCurrencyDailyHistory()`, `getMerchantTotalsInHomeCurrency()`.

Wired into 10+ pipelines: Dashboard, Budget, Analytics, Forecast, Health, Savings, Groups, Export, AI/Query, Anomaly.

#### `AnalyticsCurrencyNormalizer`

**File:** `domain/analytics/AnalyticsCurrencyNormalizer.kt`

Per-expense home-currency normalization used by analytics engines. Converts a list of `Expense` entities into `AnalyticsNormalizationResult` with per-currency buckets, converted amounts, and failure tracking. All analytics, forecasting, health, and savings engines normalize through this component before performing aggregation.

#### Design Decisions

1. **Safe defaults:** `CurrencyCode.parseOr()` falls back to a caller-provided default (not implicit EUR).
2. **Currency assumption tracking:** `CurrencyAssumption` enum on every money-bearing entity records *why* a currency was assigned (legacy default, home currency, user-confirmed, parsed).
3. **Partial aggregate handling:** `MoneyAggregate.isPartial = true` when some currencies could not be converted. UI must display a warning.
4. **Deprecated unsafe paths:** 22+ `ExpenseDao` methods marked `@Deprecated("Use MultiCurrencyRepository for currency-aware aggregation")`.
5. **Deprecated formatter overloads:** `CurrencyFormatter.format(amount)` — defaults to EUR silently; replaced by `formatMoney(amount, currencyCode)`.
6. **DAOs remain raw grouped by currency:** Safe helpers like `getAllSpentBetweenByCurrency()` return `List<CurrencyTotal>` — the conversion happens in the repository.
7. **History rate support:** `ExchangeRate` added `validDate` column; `Expense` added `baseAmount`, `baseCurrency`, `exchangeRateUsed` for stable historical reporting.

---

### Time / Period Semantics Foundation (Phase 2 — May 2026)

A 98-file cross-cutting refactoring established a single source of truth for time handling.

#### New `domain/core/time/` Package

| Type | File | Purpose |
|------|------|---------|
| `PeriodRange` | `domain/core/time/PeriodRange.kt` | Typed half-open period `[startInclusive, endExclusive)` with `kind`, `zoneId`, `label`, `contains()`, `isCalendarPeriod`. Replaces raw `Pair<Long, Long>`. |
| `PeriodKind` | `domain/core/time/PeriodKind.kt` | Enum: `TODAY`, `THIS_WEEK`, `LAST_WEEK`, `LAST_7_DAYS`, `THIS_MONTH`, `LAST_MONTH`, `LAST_30_DAYS`, `THIS_QUARTER`, `LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR`, `CUSTOM`. Distinguishes calendar vs rolling semantics. |

#### Key Contract Changes

1. **`TimeProvider.now()` is the single source of "now"** (injected into 50+ classes). Direct `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()` are forbidden in business logic (whitelist exceptions in `TIME_SEMANTICS.md`).
2. **All period ranges are half-open** `[startInclusive, endExclusive)`. No more `23:59:59.999` endpoints.
3. **Calendar labels use calendar-range helpers** (`getMonthRange`, `getWeekRange`, etc.). Rolling labels use rolling helpers (`getLastNCalendarDaysRange`, `getLastNCompleteDaysRange`). Never `getLastNDaysRange(30)` for "This Month".
4. **Raw millis division** (`(end - start) / 86_400_000`) is replaced with DST-safe `TimePeriodUtils.daysBetween()`.
5. **38 entity `System.currentTimeMillis()` defaults** migrated to `0L` sentinel.
6. **`DateFormatterUtils`** — all 13 methods accept explicit timestamps (no internal `Instant.now()`).
7. **`RecurrenceFrequency.days`** — removed from constructor, now a computed property.

See [`docs/development/TIME_SEMANTICS.md`](../development/TIME_SEMANTICS.md) for full developer rules.

---

### Transaction Lifecycle Architecture (Phase 3 — May 2026)

A 120+ file cross-cutting feature establishing a single, auditable entry point for all expense creation, update, and delete operations.

#### New `domain/transaction/` Package

| Type | File | Purpose |
|------|------|---------|
| `ExpenseSource` | `domain/transaction/ExpenseSource.kt` | Enum tracking the origin of every expense: MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, REVIEW_APPROVAL, RECEIPT_SCAN, CSV_IMPORT, EMAIL_RECEIPT, GROUP_EXPENSE, BANK_API_SYNC, etc. (14 values) |
| `LifecycleEventType` | `domain/transaction/LifecycleEventType.kt` | Enum of lifecycle transition types: CREATED, UPDATED, DELETED, CREATE_DUPLICATE_SKIPPED, etc. (14 values) |
| `DeduplicationMode` | `domain/transaction/DeduplicationMode.kt` | Enum: STANDARD, STRICT_EXTERNAL_ID, BULK_IMPORT, SKIP_FOR_DEBUG_RESTORE |
| `CreateExpenseRequest` | `domain/transaction/CreateExpenseRequest.kt` | Source-neutral creation request with 40+ fields covering all expense properties, source-link fields, and deduplication policy controls |
| `CreateExpenseResult` | `domain/transaction/CreateExpenseResult.kt` | Sealed result: Created(id), DuplicateSkipped(existingId, reason), ValidationFailed(errors), InsertConflict(dedupeKey), Error(exception) |
| `ExpenseUpdates` | `domain/transaction/ExpenseUpdates.kt` | Patch-style update model for modifying existing expense fields |

#### New `domain/transaction/lifecycle/` Package

| Component | File | Purpose |
|-----------|------|---------|
| `TransactionLifecycleCoordinator` | `lifecycle/TransactionLifecycleCoordinator.kt` | **Single entry point** for ALL expense creation/update/delete. Pipeline: validate → normalize → dedupe → insert atomic → event logging → side effects. `createExpense()` now accepts a `SideEffectMode` param (`IMMEDIATE` or `DEFER`). When `DEFER` is used, callers invoke `dispatchPostCreationSideEffects()` separately to run side effects outside the DB transaction, fixing the nested-transaction/post-commit bug where side effects previously ran inside the DB transaction. Injected by 10+ consumer classes. |
| `TransactionSideEffectDispatcher` | `lifecycle/TransactionSideEffectDispatcher.kt` | Consolidates post-creation side effects: budget check, anomaly alert, merchant-category pattern learning. Best-effort / fire-and-forget. |

**C1 Migration complete:** 8 targeted update methods added (category, merchant, type,
transfer, ownership, location, bulk). All expense mutations now route through
the coordinator with full lifecycle event tracking.

- TransactionSideEffectDispatcher now has dispatchOnUpdated() and dispatchOnDeleted()
  for post-update/post-delete budget/anomaly/merchant side effects.
- Rate staleness: CurrencyConverter.convert() checks rates against 24h threshold;
  stale rates fall through to EUR cross-rate fallback paths.

#### Migration Paths (all now route through coordinator)

| Path | PR | Status |
|------|----|--------|
| Manual Entry | PR 2 | Migrated |
| Pending Review Approval | PR 3 | Migrated |
| Notification Auto-Accept | PR 4 | Migrated |
| Receipt Path | PR 5 | Migrated |
| CSV Import | PR 6 | Migrated (dedup + lifecycle) |
| MainActivity direct DAO | PR 6 | Removed |
| Delete Lifecycle | PR 7 | Migrated |
| Email Receipt | PR 7 | Migrated |
| Group/Shared | PR 8 | Migrated |
| Bank API | PR 9 | Migrated |

#### New Guardrails

- `docs/development/DAO_ACCESS_GUARDRAILS.md` — defines approved ExpenseDao access patterns
- `scripts/guardrails/dao-access-check.kts` — CI-enforceable check for violations
- `scripts/guardrails/dao-approved-files.txt` — approved file list for the check

#### New DB Layer

- `TransactionEvent` — Room entity for `transaction_events` table (immutable lifecycle audit log)
- `TransactionEventDao` — DAO with `insert()` and `getEventsForExpense()`
- `Expense.source` — new nullable column tracking expense origin (ExpenseSource as String)
- Migration 94→95: adds `source` column + creates `transaction_events` table with indices

---

### Receipt Lifecycle Architecture (Phase 4 — May 2026)

A ~20-file cross-cutting feature establishing a single, auditable entry point for all receipt processing, with document-type-aware lifecycle management.

#### New `domain/receipt/` Models

| Type | File | Purpose |
|------|------|---------|
| `ReceiptSourceType` | `domain/receipt/ReceiptSourceType.kt` | Enum: CAMERA, GALLERY, FILE_IMPORT, EMAIL, BANK_STATEMENT, MANUAL_RECORD, BATCH_SCAN, DEBUG_IMPORT, UNKNOWN |
| `ReceiptDocumentType` | `domain/receipt/ReceiptDocumentType.kt` | Enum: RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT, UNKNOWN |
| `ReceiptProcessingStatus` | `domain/receipt/ReceiptProcessingStatus.kt` | Enum: 14 values from CAPTURED through DELETED, covering the full receipt lifecycle |
| `EmailReceiptData` | `domain/receipt/EmailReceiptData.kt` | Structured email receipt with parsed financial fields (amount, merchant, currency, date, items) |

#### New `domain/receipt/lifecycle/` Package

| Component | File | Purpose |
|-----------|------|---------|
| `ReceiptLifecycleCoordinator` | `lifecycle/ReceiptLifecycleCoordinator.kt` | **Single entry point** for all receipt processing. Pipeline: validate → persist asset → OCR/parse → dedupe → save → event logging → side effects. Handles camera/gallery, email, bank statement, and manual receipt paths. |
| `ReceiptLinkService` | `lifecycle/ReceiptLinkService.kt` | Centralized receipt-expense link management via `receipt_expense_links` join table. Supports many-to-many links (BANK_STATEMENT) and single links (all other types). Writes audit events for every link/unlink. **New behaviors:** validates expense exists (fails fast if not found); checks `ReceiptExpenseLinkDao.insert()` return value to detect duplicate links; **RCP-30:** propagates item-majority category to expense when `categoryId` is null. |
| `ReceiptAssetStore` | `lifecycle/ReceiptAssetStore.kt` | File persistence layer: copies receipt images to app-local storage, computes SHA-256 hashes, creates camera temp URIs via FileProvider, generates backup manifests. |
| `ReceiptInputValidator` | `lifecycle/ReceiptInputValidator.kt` | URI/MIME/size validation: checks readability, supported MIME types (JPEG, PNG, WebP, PDF, HEIC), file size limit (50 MB), bitmap decode validity. |
| `ReceiptDuplicateDetector` | `lifecycle/ReceiptDuplicateDetector.kt` | 3-signal deduplication: EXACT_HASH (SHA-256, 1.0 confidence), TEXT_FINGERPRINT (normalized OCR text, 0.95), SEMANTIC (merchant+amount+date+currency, 0.8), plus EXTERNAL_ID for email dedup. |
| `ReceiptSideEffectDispatcher` | `lifecycle/ReceiptSideEffectDispatcher.kt` | Document-type-gated post-save effects: RETAIL_RECEIPT → warranty extraction, item categorization, transaction matching, price protection. EMAIL_RECEIPT → item categorization only. BANK_STATEMENT/MANUAL_PLACEHOLDER → no effects. |
| `BankStatementLifecycleProcessor` | `lifecycle/BankStatementLifecycleProcessor.kt` | Statement-specific processing: OCR → parse transactions → create PendingReview entries → lifecycle events. Returns structured `BankStatementResult`. |

- Pre-OCR exact-hash dedupe: ReceiptAssetStore.computeUriHash() called before OCR.
  Exact-hash duplicates skip OCR/parse/insert entirely, eliminating orphan rows.

#### Migration Paths (all now route through coordinator)

| Path | PR | Status |
|------|----|--------|
| Camera/Gallery/File scan | PR 4 | Migrated |
| Receipt→Expense save (with link service) | PR 4 | Migrated |
| Review queue receipt linking | PR 5 | Migrated |
| Bank statement processing | PR 5 | Migrated |
| Email receipt ingestion | PR 5 | Migrated |
| Warranty/Return/Price side effects | PR 6 | Document-type-gated |
| Receipt matching | PR 7 | Migrated via LinkService |
| Item categorization gating | PR 7 | Status-consistent + document-type gating |

#### New DB Layer

- `ReceiptEvent` — Room entity for `receipt_events` table (immutable lifecycle audit log for receipts)
- `ReceiptEventDao` — DAO with `insert()` and `getEventsForReceipt()`
- `ReceiptExpenseLink` — Room entity for `receipt_expense_links` table (many-to-many receipt↔expense join)
- `ReceiptExpenseLinkDao` — DAO with `insert()`, `getLinksForReceipt()`, `getLinksForExpense()`, `unlink()`, `deleteAllLinksForReceipt()`
- `ScannedReceipt` — 10 new columns: `sourceType`, `documentType`, `processingStatus`, `sourceFingerprint`, `imageHash`, `textFingerprint`, `semanticFingerprint`, `ocrConfidence`, `parseFailureReason`, `updatedAt`
- Migration 95→96: adds `receipt_events` and `receipt_expense_links` tables, adds 10 columns to `scanned_receipts`
- Migration 95→96: removes `transaction_events_eventType_source_index` before re-creating it to fix schema drift

---

### Recurring / Planned / Reminder Lifecycle Foundation (Phase 5 — May 2026)

A ~5-file domain expansion establishing an auditable lifecycle for recurring-expense occurrences, with conflict resolution and reminder scheduling.

#### Phase 5b Additions

| Component | File | Purpose |
|-----------|------|---------|
| `RecurringLifecycleEvent` (entity) | `data/database/entity/RecurringLifecycleEvent.kt` | Immutable event log recording every lifecycle transition for a recurring occurrence (OCCURRENCE_GENERATED, OCCURRENCE_PAID, OCCURRENCE_SKIPPED, etc.). Indices on occurrenceId, occurredAt, eventType. |
| `RecurringLifecycleEventDao` | `data/database/dao/RecurringLifecycleEventDao.kt` | DAO with `insert()` and `getEventsForOccurrence()`. |
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | `@HiltWorker` — periodic WorkManager worker (every 4h) that queries `RecurringLifecycleCoordinator.getDueReminders()` and dispatches Android notifications for due/overdue bills. |
| `ForecastInputAssembler` | `domain/forecasting/ForecastInputAssembler.kt` | Central forecast-input assembler that merges manual recurring patterns and planned expenses. Now injects `RecurringLifecycleCoordinator` for future occurrence-based dedup integration (TODO: use `generateOccurrences()` as single source of truth). |
| `ReconciliationReport` | `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` | `data class` + `reconcilePlannedVsActual()` method. Compares planned vs actual spending for a recurring rule over the past N months. Tracks drift, driftPercent, matched/unmatched/over-budget counts. |

**New DB layer (migration 101→102):** `recurring_lifecycle_events` table.

#### New `domain/recurring/` Package — Expansion & Resolution

| Component | File | Purpose |
|-----------|------|---------|
| `RecurringOccurrenceExpander` | `domain/recurring/RecurringOccurrenceExpander.kt` | Pure utility that expands a recurrence rule into concrete occurrence candidates within a half-open date range. Supports WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/SEMI_ANNUALLY/ANNUALLY frequencies via calendar-aware arithmetic (DST/leap-year safe). |
| `OccurrenceConflictResolver` | `domain/recurring/OccurrenceConflictResolver.kt` | Resolves occurrence candidates against actual expenses to determine PLANNED/PAID/SKIPPED status. Matching rules: same calendar day, merchant match (case-insensitive via MerchantKeyGenerator), amount ±10% tolerance, same currency. Each expense matched at most once. |
| `RecurringPlanProjectionService` | `domain/recurring/RecurringPlanProjectionService.kt` | Bridges the recurring lifecycle system to forecasting/budgeting by materialising `PlannedExpense` rows from PLANNED occurrences. Prevents double-count risk by deduplicating via `sourceOccurrenceKey`. |

#### New `domain/recurring/lifecycle/` Package — Coordination & Materialization

| Component | File | Purpose |
|-----------|------|---------|
| `RecurringLifecycleCoordinator` | `lifecycle/RecurringLifecycleCoordinator.kt` | **Primary entry point** for generating and managing recurring occurrences. Orchestrates expand → resolve → materialize pipeline. Provides `generateOccurrences()`, `linkExpenseToOccurrence()` (best-effort post-creation linking), `getOccurrences()`, `updateOccurrenceStatus()`, and `getDueReminders()`. **New constructor dependencies:** `RestoreMaintenanceMode`, `ExpenseDao`, `ManualRecurringExpenseDao`, `TimeProvider`. |
| `RecurringOccurrenceMaterializer` | `lifecycle/RecurringOccurrenceMaterializer.kt` | Persists resolved occurrences and creates reminder deliveries. INSERT with IGNORE for new (occurrenceKey unique constraint), UPDATE for status changes. Creates `RecurringReminderDelivery` rows for PLANNED occurrences (DUE_DAY, N_DAYS_BEFORE, OVERDUE windows). |

#### Cross-Cutting Integration

- **TransactionLifecycleCoordinator auto-link hook (Phase 3 ↔ Phase 5):** After every `createExpense()`, a best-effort call to `recurringLifecycleCoordinator.linkExpenseToOccurrence()` attempts to match the new expense to a PLANNED occurrence on the same calendar day. Failures are silently caught (non-blocking).
- **ForecastInputAssembler** updated to inject `RecurringLifecycleCoordinator` for future dedup integration (replacing ad-hoc recurrence expansion with coordinator-driven generation).
- **Subscription math normalization** fixed across subscription detection and recurring engines.
- RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence(expenseId) — direct
  linkedExpenseId lookup resets occurrence to PLANNED on expense deletion/change.

#### New DB Layer

- `RecurringOccurrence` — Room entity for `recurring_occurrences` table (sourceType, sourceId, occurrenceKey unique, dueDate, status, linkedExpenseId, expectedAmount/Currency, paid fields, frequency, merchant, categoryId, timestamps). Indices on (sourceType+sourceId), dueDate, status, occurrenceKey (unique), linkedExpenseId.
- `RecurringOccurrenceDao` — DAO with insert (IGNORE), insertAll, update, getByKey, getBySource, getByDateRange, getByStatus, updateStatus.
- `RecurringReminderDelivery` — Room entity for `recurring_reminder_deliveries` table (occurrenceId, reminderWindow, scheduledAt, status, lastSentAt, dismissedAt, snoozedUntil, notificationId, createdAt). Indices on (occurrenceId+reminderWindow), status, scheduledAt.
- `RecurringReminderDeliveryDao` — DAO with insert, insertAll, update, getByOccurrenceAndWindow, getPendingDeliveries.
- `PlannedExpense` — 2 new columns: `sourceOccurrenceKey` (TEXT, nullable) and `sourceRecurringRuleId` (INTEGER, nullable) for cross-linking planned expenses to recurring occurrences.
- Migration 96→100: creates both new tables with indices, adds 2 columns to `planned_expenses`.

### Phase 6 — Privacy & Capability Gates (May 2026)

Phase 6 introduces a privacy capability gate system, audit logging, data retention,
backup encryption, and an export anonymizer for the expense tracker database.

#### Domain — Privacy Gate Architecture (12 files)

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacyCapability` | `domain/privacy/PrivacyCapability.kt` | Enum of 21 gated capabilities (NOTIFICATION_CAPTURE, CLOUD_AI_RECEIPT_ASSIST, EXTERNAL_GEOCODING, RAWBACKUP_EXPORT, ENCRYPTED_BACKUP, etc.) |
| `PrivacyGate` (interface) | `domain/privacy/PrivacyGate.kt` | Contract: `check(capability, context) → PrivacyDecision`. Fail-closed, audit-logged, deterministic per capability+settings. |
| `PrivacyDecision` | `domain/privacy/PrivacyDecision.kt` | Sealed interface: `Allowed` or `Denied(reason)` |
| `PrivacySettings` | `domain/privacy/PrivacySettings.kt` | Data class with 10 privacy toggles (notificationCapture, cloudAi, redactBeforeCloud, receiptImageCloud, externalGeocoding, backgroundLocationBackfill, deviceGpsLocation, encryptedBackup, debugDataPersistence) + 2 retention day settings |
| `PrivacySettingsRepository` | `domain/privacy/PrivacySettingsRepository.kt` | Interface for reading/writing settings |
| `PrivacyAuditLogger` | `domain/privacy/PrivacyAuditLogger.kt` | Logs every gate check decision (capability, decision, reason, context, caller) to the privacy_audit_events table |
| `NotificationPrivacyGate` | `domain/privacy/NotificationPrivacyGate.kt` | Guards NOTIFICATION_CAPTURE and NOTIFICATION_PACKAGE_ALLOWLIST |
| `LocationPrivacyGate` | `domain/privacy/LocationPrivacyGate.kt` | Guards EXTERNAL_GEOCODING, BACKGROUND_LOCATION_BACKFILL, DEVICE_GPS_LOCATION, OVERPASS_API |
| `CloudAiPrivacyGate` | `domain/privacy/CloudAiPrivacyGate.kt` | Guards all CLOUD_AI_* capabilities plus RECEIPT_IMAGE_CLOUD_UPLOAD |
| `BackupPrivacyGate` | `domain/privacy/BackupPrivacyGate.kt` | Guards RAWBACKUP_EXPORT and ENCRYPTED_BACKUP based on `encryptedBackupEnabled` setting |
| `CompositePrivacyGate` | `domain/privacy/CompositePrivacyGate.kt` | Chains all gates; returns first `Denied` or `Allowed` if all pass |
| `RedactionSanitizer` | `domain/privacy/RedactionSanitizer.kt` | PII redaction helper for notification text and OCR content before cloud calls |

#### Data Layer — Privacy (4 files)

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacySettingsRepositoryImpl` | `data/privacy/PrivacySettingsRepositoryImpl.kt` | DataStore-backed implementation of settings repository |
| `BackupEncryptionService` | `data/privacy/BackupEncryptionService.kt` | AES-256-GCM encrypt/decrypt with PBKDF2 key derivation |
| `ExportAnonymizer` | `data/privacy/ExportAnonymizer.kt` | Strips rawOcrText and raw notification content from temp DB copy before export |
| `DataRetentionWorker` | `data/privacy/DataRetentionWorker.kt` | Periodic WorkManager worker (every 24h) that purges expired raw notifications and OCR text based on retention settings |

#### Privacy DB Layer

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacyAuditEvent` (entity) | `data/database/entity/PrivacyAuditEvent.kt` | Room entity for `privacy_audit_events` table. Fields: id, capability, decision, reason, context (JSON), timestampMs, caller. Indices on timestampMs, capability, caller. |
| `PrivacyAuditDao` | `data/database/dao/PrivacyAuditDao.kt` | DAO with `insert()` and `getRecent(limit)`. |

**Migrations:** 102→103 creates `privacy_audit_events` table; 103→104 adds `rawContentPurgedAt` to `raw_notifications` and `rawOcrTextPurgedAt` to `scanned_receipts` for data retention purging.

#### Privacy UI

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacySettingsViewModel` | `ui/screens/privacysettings/PrivacySettingsViewModel.kt` | Reads/writes all 10 privacy settings via repository |
| `PrivacySettingsScreen` | `ui/screens/privacysettings/PrivacySettingsScreen.kt` | Compose screen with toggles for all privacy settings |

#### DI Wiring

`PrivacyModule.kt` in `di/` binds all four gates (Notification, Location, CloudAI, Backup)
into `CompositePrivacyGate`. It also provides `BackupEncryptionService`, `ExportAnonymizer`,
`DataRetentionWorker`, and `PrivacyAuditLogger`. `DatabaseBackupRepositoryImpl` is updated
to check privacy gates and optionally encrypt/sanitize exports.

**Phase 6 is complete.**

---

### Phase 7 — DB Invariants (May 2026)

Phase 7 materializes invariant key columns on 5 entities to enforce business-domain uniqueness constraints at the database level, with a heal+backfill+unique-index migration pattern.

#### Modified Entities

| Entity | Invariant Column | Unique Index | Semantics |
|--------|-----------------|--------------|-----------|
| `Budget` | `activeOverallKey` (LONG) | `idx_budgets_activeOverallKey` | Set to `1` when `isActive=true AND categoryId IS NULL`; at most one row |
| `Budget` | `activeCategoryKey` (LONG) | `idx_budgets_activeCategoryKey` | Set to `categoryId` when `isActive=true AND categoryId IS NOT NULL`; at most one per category |
| `GroupMember` | `currentUserGroupKey` (LONG) | `idx_group_members_currentUserGroupKey` | Set to `groupId` when `isCurrentUser=true`; at most one current user per group |
| `GroupExpense` | _(existing `expenseId`)_ | `idx_group_expenses_expenseId` (made UNIQUE) | Each expense linked at most once to a group |
| `RawNotification` | `dedupeFingerprint` (TEXT) | `idx_raw_notifications_dedupeFingerprint` | Deterministic SHA-256 fingerprint; unique on non-null values |
| `PlannedExpense` | `openSourceOccurrenceKey` (TEXT) | `idx_planned_expenses_openSourceOccurrenceKey` | Set to `sourceOccurrenceKey` when `status='PLANNED'`; at most one open planned occurrence |

#### Integrity Scanner

| Component | File | Purpose |
|-----------|------|---------|
| `DatabaseIntegrityScanner` | `domain/diagnostics/DatabaseIntegrityScanner.kt` | Scans DB for 11 invariant violations: duplicate active budgets (overall + category), multiple current users per group, duplicate group-expense links, duplicate planned-occurrence keys, raw-notification fingerprint dupes, null dedupe keys, partial lat/lon rows, invalid currency values, orphaned warranties and receipt links. Exposes `runFullScan()` and `runCriticalScans()`. |

**Migration:** 104→105 — 5-step: heal duplicates → add columns → backfill keys → drop stale indexes → create unique indexes. All wrapped in a single transaction with FK guard.

---

### Phase 8 — Background Workers (May 2026)

Phase 8 introduces a persistent job-run tracking table, a worker-specification model, a centralized scheduler, and fixes/scheduling for 7 background workers.

#### New Components

| Component | File | Purpose |
|-----------|------|---------|
| `BackgroundJobRun` (entity) | `data/database/entity/BackgroundJobRun.kt` | Persistent record of each worker execution: workerName, startedAt/finishedAt, status (SCHEDULED/RUNNING/SUCCESS/FAILED/RETRY), rowsScanned/Updated, notificationsSent, retryReason, errorMessage |
| `BackgroundJobRunDao` | `data/database/dao/BackgroundJobRunDao.kt` | DAO with `insert()`, `update()`, `getRecent(workerName)`, `getStaleRunningRuns()` |
| `WorkerSpec` | `domain/workers/WorkerSpec.kt` | Data class modeling worker name, version, enabled flag, constraints, repeat interval, backoff policy. Ships `DEFAULTS` map with specs for all 7 workers. |
| `WorkerSpecScheduler` | `domain/workers/WorkerSpecScheduler.kt` | **Centralized scheduling object** that all workers use instead of duplicating schedule logic. Reads `WorkerSpec.DEFAULTS` by worker name, handles version-change detection (force REPLACE when version bumps), and delegates to `WorkManager.enqueueUniquePeriodicWork` / `enqueueUniqueWork`. Stateless Kotlin `object` — no DI needed. All 7 workers are scheduled via this scheduler in `AppStartupCoordinator`. |

#### Workers Summary

| Worker | Location | Schedule | Key Change in Phase 8 |
|--------|----------|----------|-----------------------|
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | Every 6h, flex 15min | **Disabled by default** (user opt-in); fixed to query `RecurringLifecycleCoordinator` via `WorkerSpec` |
| `ReceiptMatchingWorker` | `service/receiptmatching/ReceiptMatchingWorker.kt` | Every 2h | Scheduled via `AppStartupCoordinator`; automated receipt↔expense matching |
| `WarrantyExpirationWorker` | `service/warranty/WarrantyExpirationWorker.kt` | Every 24h | **Idempotency fix:** in-memory `notifiedKeys` set prevents duplicate notifications across 7-day and 30-day windows |
| `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` | Every 12h, UNMETERED | **Overwrite guard:** skips expenses that already have lat/lon; privacy-gated via `PrivacyCapability.BACKGROUND_LOCATION_BACKFILL` |
| `DailyBriefingWorker` | `data/ai/worker/DailyBriefingWorker.kt` | Every 24h | **Privacy gate:** checks `CLOUD_AI_DAILY_BRIEFING` at runtime; exits early if denied |
| `MerchantKeyBackfillWorker` | `data/location/MerchantKeyBackfillWorker.kt` | One-shot | One-time backfill of `merchantKey` for legacy expense rows |
| `DataRetentionWorker` | `data/privacy/DataRetentionWorker.kt` | Every 24h | Pre-existing (Phase 6); moved to `WorkerSpec` governance |

**Migration:** 105→106 creates `background_job_runs` table with indices on `(workerName, startedAt)` and `(status)`. All workers now scheduled through `AppStartupCoordinator.scheduleStartupWork()` with specs defined in `WorkerSpec.DEFAULTS`.

---

### Phase 9 — Backup & Restore Engine (May 2026)

Phase 9 introduces a complete backup/restore subsystem with an encrypted bundle format,
crash-safe journaling, worker pausing, and full 56-entity verification.

#### New `data/backup/` Package

| Component | File | Purpose |
|-----------|------|---------|
| `CostbackupBundle` | `data/backup/CostbackupBundle.kt` | **`.costbackup` bundle format.** Outer layer: `COSTBACKUP1` magic (10B) + format version (2B) + salt (16B) + IV (12B) + AES-256-GCM ciphertext. Inner layer: standard ZIP containing `manifest.json`, `database.sqlite`, `files/receipts/`, `checksums.json`. Data classes: `BackupManifest`, `BackupIncludes`, `BackupOptionsManifest`, `ChecksumsManifest`. Exceptions: `WrongBackupPasswordException`, `UnsupportedBackupVersionException`, `InvalidBackupFormatException`, `ChecksumMismatchException`. Exposes `create()` and `extract()` as `Result<T>`. |
| `RestoreMaintenanceMode` | `data/backup/RestoreMaintenanceMode.kt` | **8-state maintenance mode manager.** Modes: `NORMAL`, `BACKUP_EXPORTING`, `RESTORE_PREPARING`, `RESTORE_STAGING`, `RESTORE_SWAPPING`, `RESTORE_VERIFYING`, `RESTORE_ROLLING_BACK`, `RESTORE_COMPLETE_RESTART_REQUIRED`. On `enter()`: cancels all 7 WorkManager workers by tag via `WorkerSpec.DEFAULTS`. State persisted in `SharedPreferences` (survives process death). `isWritesAllowed()` returns `false` for all modes except `NORMAL` and `BACKUP_EXPORTING`. `exit(forceRestartRequired)` optionally transitions to `RESTORE_COMPLETE_RESTART_REQUIRED` to keep writes blocked until app restart. |
| `RestoreJournal` | `data/backup/RestoreJournal.kt` | **Crash-safe 8-state restore journal.** State machine: `PREPARING → STAGED → SAFETY_BACKUP_CREATED → SWAPPING → VERIFYING → COMPLETE` (with `ROLLING_BACK` and `FAILED` as recovery paths). Writes `restore_journal.json` before each critical step. `checkAndRecover()` returns `RecoveryResult` sealed class: `NoAction`, `CompleteClean`, `CleanedNonDestructive`, `RecoveredFromSwap`, `CriticalRecoveryRequired`. Non-destructive states (`PREPARING`, `STAGED`, `FAILED`) clean staging files automatically. Destructive states (`SWAPPING`, `VERIFYING`, `ROLLING_BACK`) signal the caller to attempt safety-backup recovery. |
| `BackupVerifier` | `data/backup/BackupVerifier.kt` | **Full 56-entity verification in 3 tiers.** `TIER_1_EXACT` (30 tables — user/business data: row count **must** match exactly). `TIER_2_VALIDITY` (16 tables — derived/event-log: must pass integrity + FK checks). `TIER_3_OPTIONAL` (10 tables — cache/external: may be absent). Methods: `verify()` returns `VerificationSummary` with per-table results; `verifyQuick()` throws on first failure for pre-swap gating. Runs `PRAGMA integrity_check` and `PRAGMA foreign_key_check` on the restored database. |

#### Integration Points

| Component | What Changed |
|-----------|--------------|
| `DatabaseBackupRepositoryImpl` | **Rewritten** to use `CostbackupBundle.create()`/`extract()`, `BackupVerifier.verify()`/`verifyQuick()`, `RestoreJournal` for each step, and `RestoreMaintenanceMode.enter()`/`exit()` to pause workers during restore. Export path now produces `.costbackup` bundles; import path runs the full journaled restore pipeline. |
| `AppStartupCoordinator` | **`initialize()` now calls `checkRestoreJournal()` first** — before `registerLifecycleObserver()` and `scheduleStartupWork()`. Injects `RestoreJournal` and `RestoreMaintenanceMode`. On startup, reads the journal and handles crash recovery (cleans non-destructive states, logs critical failures, resets maintenance mode to `NORMAL`). |
| `NotificationCaptureService` | **Injects `RestoreMaintenanceMode`** — checks `isWritesAllowed()` before processing any incoming notification. During restore, all notification ingestion is silently blocked. |

#### Restore Pipeline Flow

```
1. RestoreMaintenanceMode.enter(RESTORE_PREPARING)
   → cancels all 7 WorkManager workers
   → NotificationCaptureService blocks writes

2. RestoreJournal.beginJournal(source, staged, live)
   → writes restore_journal.json (state=PREPARING)

3. CostbackupBundle.extract(bundleFile, tempDir, password)
   → decrypts AES-256-GCM → extracts ZIP → validates manifest + checksums

4. RestoreJournal.transitionTo(STAGED)

5. BackupVerifier.verifyQuick(stagedDbFile, manifestTableCounts)
   → PRAGMA integrity_check + foreign_key_check + Tier 1 exact counts

6. Safety backup of live DB created

7. RestoreJournal.transitionTo(SAFETY_BACKUP_CREATED)

8. Swap staged → live (file rename)

9. RestoreJournal.transitionTo(SWAPPING)

10. Reopen live DB; BackupVerifier.verify(liveDbFile, manifestTableCounts)
    → full 56-entity 3-tier verification

11. RestoreJournal.transitionTo(VERIFYING)

12. On success: RestoreJournal.commitJournal() → delete journal file
    On failure: RestoreMaintenanceMode.enter(RESTORE_ROLLING_BACK)
                → restore safety backup → RestoreJournal.failJournal()

13. RestoreMaintenanceMode.exit(forceRestartRequired=true)
    → blocks writes until app restart
```

**DB impact:** No migration. Database stays at **v106** at this point (Phase 9 adds no entities or columns — the bundle packages the existing schema).

---

### Phase 10 — Analytics / Forecast / AI Cleanup (May 2026)

Phase 10 is the **final architecture cross-cutting phase**, resolving correctness issues,
hardening currency normalization, and establishing a shared data-quality contract across
all analytics, forecasting, health, and savings pipelines.

#### Double-Counting Fix — Occurrence-Based Dedup

| Component | File | Change |
|-----------|------|--------|
| `ForecastInputAssembler` | `domain/forecasting/ForecastInputAssembler.kt` | Injects `RecurringOccurrenceDao` and cross-deduplicates planned expenses that share a `sourceOccurrenceKey` with PLANNED/PAID occurrences from the recurring lifecycle coordinator. Resolves the long-standing double-count risk documented in the class KDoc. |
| `MonthlySavingsSweepUseCase` | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` | Injects `RecurringOccurrenceDao` for occurrence-aware underspend calculation during month-end sweep recommendations. Prevents forecasted occurrences from inflating budget-remainder totals. |

Both engines now reconcile planned-vs-materialized occurrences before summing forecast inputs,
eliminating the primary source of double-counted projections.

#### `PeriodKind.toPeriodRange()` Extension

| Component | File | Purpose |
|-----------|------|---------|
| `PeriodKind.toPeriodRange()` | `domain/core/time/PeriodKind.kt` | Extension function converting any `PeriodKind` (including `CUSTOM`) into a concrete `PeriodRange` anchored at `now`. Delegates to `TimePeriodUtils` for calendar-aware boundary computation (DST/leap-year safe). Throws `IllegalArgumentException` for `CUSTOM` without explicit bounds. |

```kotlin
// One-liner replaces ad-hoc when-blocks throughout the codebase
val monthRange = PeriodKind.THIS_MONTH.toPeriodRange(now = timeProvider.now())
```

Provides a single, auditable conversion path from semantic period kind to concrete half-open
range, eliminating the spread of manual boundary computation across 10+ consumers.

#### Currency Normalization Wiring — `BudgetForecastingEngine`

| Component | File | Change |
|-----------|------|--------|
| `BudgetForecastingEngine` | `domain/budget/BudgetForecastingEngine.kt` | Now injects `AnalyticsCurrencyNormalizer` and `CurrencySettingsRepository`. All monetary operations (spent-to-date, trend computation, risk assessment) go through the normalizer instead of raw SQL sums. Replaces `ExpenseDao` aggregate queries with normalized `MoneyAggregate`-based computation. |

This completes the multi-currency architecture coverage: every financial pipeline that
aggregates expense amounts now routes through `AnalyticsCurrencyNormalizer`.

#### `MoneyAmount` / `MoneyAggregate` — Approved Type Designation

| Type | File | Purpose |
|------|------|---------|
| `MoneyAmount` | `domain/core/money/MoneyAmount.kt` | KDoc now opens with `★ APPROVED TYPE ★` — the single approved domain type for all monetary values. Replaces raw `Double` and `Pair<Double, String>` in domain models, analytics outputs, and UI state. |
| `MoneyAggregate` | `domain/core/money/MoneyAggregate.kt` | KDoc now opens with `★ APPROVED TYPE ★` — the single approved result type for all financial aggregation. Replaces raw `Double` totals that silently mixed currencies. |

Both types carry explicit migration guidance in their KDoc. New code must use these types;
legacy `Double` + `displayCurrency` pairs should be converted opportunistically.

#### Hardcoded Currency Defaults — Documented Constants

| Engine | Constants | KDoc Annotation |
|--------|-----------|-----------------|
| `SmartSavingsEngine` | `DEFAULT_CAP_WEEK=75.0`, `DEFAULT_CAP_MONTH=200.0`, `DEFAULT_CAP_QUARTER=500.0`, `DEFAULT_FALLBACK_MONTHLY_DISCRETIONARY=500.0`, `homeCurrency="EUR"` default parameter | Documented as "home-currency units (e.g., EUR, USD)" with TODO to make configurable via `CurrencySettingsRepository` |
| `FinancialHealthCalculator` | `DEFAULT_DAILY_TARGET=50.0`, `DEFAULT_WEEKLY_TARGET=350.0`, `DEFAULT_MONTHLY_TARGET=1500.0`, `homeCurrency` resolved via `CurrencySettingsRepository` (falls back to `"EUR"`) | Documented as "home-currency units" with note to derive intelligently from income or historical averages |

These constants are **explicitly scoped as home-currency defaults**, not silent EUR assumptions.
Every hardcoded monetary value now carries KDoc explaining its denomination and future
migration path.

#### `DataQualityReport` — Shared Quality Contract

| Component | File | Purpose |
|-----------|------|---------|
| `DataQualityReport` | `domain/analytics/DataQualityReport.kt` | Unified data class aggregating quality metrics from analytics, forecasting, currency conversion, and AI pipelines. Properties: `totalExpenses`, `expensesWithCurrency`, `expensesWithMerchant`, `expensesWithCategory`, `conversionConfidence` (0.0–1.0), `warnings`. Factory method `fromNormalization()` consumes `AnalyticsNormalizationResult`. Computed properties: `isReliable`, `qualityLabel`. |

All engines that use `AnalyticsCurrencyNormalizer` should pipe its output through
`DataQualityReport.fromNormalization()` to produce a consistent quality signal for UI
consumption. The `isReliable` flag (`totalExpenses > 0 && conversionConfidence >= 0.5`)
provides a single boolean gate for displaying analytics results.

#### Summary

Phase 10 closes the remaining correctness and documentation gaps across the analytics,
forecasting, and AI surface:
1. **Double-counting eliminated** in two forecast-adjacent pipelines via occurrence-based dedup
2. **Period-kind-to-range** unified in a single extension function
3. **Currency normalization** completed in the budget forecasting engine
4. **Approved types** formally designated with `★ APPROVED TYPE ★` KDoc markers
5. **Hardcoded defaults** surfaced and documented with migration paths
6. **Data quality** standardized via a shared report contract

**DB impact:** No migration. Database stays at **v106** at this point (Phase 10 adds no entities or columns).

---

### Bank Statement AI Validation (Post-Phase 10)

A new AI pipeline validates and corrects bank statement OCR output before creating `PendingReview` entries. The pipeline is three-tier: on-device AI → cloud AI → deterministic parser fallback, with per-transaction source tracking for full auditability.

#### AI Pipeline — `ValidateBankStatementTransactionsUseCase`

**File:** `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt`

| Step | Component | Purpose |
|------|-----------|---------|
| 1 | `OnDeviceReceiptAssistService.suggestFromText()` | Privacy-preserving on-device AI (no network). Always tried first. |
| 2 | `PrivacyGate.check(CLOUD_AI_BANK_STATEMENT)` | Gate check before any cloud data is sent. |
| 3 | `SmartReceiptAssistService.suggestFromText()` | Cloud AI fallback via Gemini (on-device→cloud retry chain). |
| 4 | Parser-only fallback | If both AI paths fail, all candidates are returned as `PARSER_ONLY`. |

Each validated transaction carries a `source` field: `PARSER_ONLY`, `AI_VALIDATED` (AI confirmed), or `AI_CORRECTED` (AI changed values).

#### `BankStatementParser.preFilterRows()`

**File:** `domain/receipt/BankStatementParser.kt` (line 268)

New row-level filter that strips noise before any transaction extraction:
1. Blank/very short lines (headers, page numbers)
2. Bank-specific header keywords
3. Pure-number lines without date/amount patterns (card/account numbers)
4. Date-only lines without amounts
5. Exact duplicate rows (keeps first occurrence)

#### Text-Only AI Services

| Service | File | `suggestFromText()` Purpose |
|---------|------|----------------------------|
| `CloudReceiptAssistService` | `data/ai/provider/CloudReceiptAssistService.kt` | Text-only Gemini call with `CLOUD_AI_BANK_STATEMENT` privacy gate self-defense. Uses `temperature=0.1`, `responseMimeType=application/json`, no image handling. |
| `OnDeviceReceiptAssistService` | `data/ai/provider/OnDeviceReceiptAssistService.kt` | Text-only ML Kit GenAI call with no image processing. |
| `SmartReceiptAssistService` | `data/ai/provider/SmartReceiptAssistService.kt` | Orchestrates on-device→cloud text fallback. Privacy gate responsibility is delegated to the use case. |

#### Privacy Capabilities — Bank Statement

| Capability | Enum Value | Guarded By |
|------------|-----------|------------|
| Cloud AI bank statement | `PrivacyCapability.CLOUD_AI_BANK_STATEMENT` | `CloudAiPrivacyGate` — checks `cloudAiEnabled` setting |
| AI bank statement parsing | `PrivacyCapability.AI_BANK_STATEMENT_PARSING` | `CloudAiPrivacyGate` — checks `cloudAiEnabled` setting |

Both are gated by the master `cloudAiEnabled` toggle in `PrivacySettings`. `CloudAiPrivacyGate` now includes these capabilities in its `when` block alongside the existing `CLOUD_AI_RECEIPT_ASSIST` / `CLOUD_AI_ITEM_CATEGORIZATION` entries.

#### Debug Data — Per-Transaction Source Tracking

**File:** `domain/debug/DebugData.kt` (line 21)

`DebugData.validationSources: Map<Int, String>` maps transaction index to its origin:
- `"PARSER_ONLY"` — deterministic parser, no AI intervention
- `"AI_VALIDATED"` — AI confirmed the parser's output
- `"AI_CORRECTED"` — AI corrected merchant/amount/currency/date

Exposed in debug JSON export as `validationSource` per transaction. `BankStatementLifecycleProcessor` logs the AI/PARSER split after validation.

#### Wiring in `BankStatementLifecycleProcessor`

**File:** `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` (line 72)

Now injects `ValidateBankStatementTransactionsUseCase` as `transactionValidator`. After deterministic parsing (step 2), AI validation runs (step 2b):
1. Converts parsed transactions to `DebugTransaction` list
2. Calls `transactionValidator.validateTransactions(ocrText, candidates, homeCurrency)`
3. Maps validated results to `validationSources: Map<Int, String>`
4. Merges AI results (when `confidence > 0.5` and AI-sourced) with original parser values
5. Logs the AI/PARSER split for diagnostics

---

### Compliance Audit (May 2026)

A full compliance audit scanned the entire codebase (`app/src/main/java/com/yourname/expensetracker/`) for issues across 6 categories: direct DAO bypasses, hardcoded EUR defaults, raw currency sums, `System.currentTimeMillis()` violations, old lifecycle paths, and non-currency-formatted UI.

#### HIGH Severity Fixes

| # | Finding | Fix Applied |
|---|---------|-------------|
| H1 | `BankStatementLifecycleProcessor` direct `scannedReceiptDao.insert()` bypassing the whitelist | Delegated to `ReceiptRepository` |
| H2 | `SynthesisEngine` raw `sumOf { it.amount }` on `PlannedExpense` with no currency field | Added `currency` field to domain `PlannedExpense`; sums grouped by currency before aggregation |
| H3 | Hardcoded `currency = "EUR"` in domain models (`DashboardPrimitives`, `SpendingSummary`, `SavingsGoal`) | KDoc documented each EUR default with explicit migration path annotation |
| H4 | `CsvExpenseImporter` and `ReceiptRepository` hardcoded `"EUR"` | Changed to `CurrencySettingsRepository.homeCurrency()` with EUR fallback |
| H5 | DAO interfaces using `System.currentTimeMillis()` as default parameter values | KDoc documented all ~20 occurrences as coupling risk requiring `TimeProvider` injection |
| H6 | `ExpenseDao` deprecated `SUM()` queries verified | All `@Deprecated("Use MultiCurrencyRepository")` markers confirmed; no new callers added |

#### MEDIUM Severity Fixes (21 files)

KDoc annotation of EUR defaults applied across 4 analytics engines (`InsightsEngine`, `SmartSavingsEngine`, `MonthlySavingsSweepUseCase`, `SpendingPaceCalculator`) and 17 UI components (`TotalsDashboardCard`, `BudgetBlockPartyCard`, `CategoryBreakdownSheet`, `StatisticalVisualizations`, and more). Each hardcoded `"EUR"` default now carries KDoc stating the home-currency assumption and the required migration to `CurrencySettingsRepository`.

#### Infrastructure Improvements

| Fix | Details |
|-----|---------|
| SimpleDateFormat → DateTimeFormatter | **38 replacements** across 21 files. 0 `SimpleDateFormat` remaining in production code. All KDoc references updated to mention thread-safe `DateTimeFormatter`. |
| REPLACE → IGNORE | **14 DAOs** changed from `OnConflictStrategy.REPLACE` to `IGNORE`. Only 3 kept as REPLACE: `ExchangeRateDao` ×2 (composite unique index, newer data overwrites older) and `AiArtifactDao` ×1 (existing KDoc). |
| Category Name Uniqueness | NOCASE index added via migration **112→113** + `FRESH_INSTALL_CALLBACK`. `addCategory()` now uses `withTransaction` + lowercase normalization. `existsByName()` query added. |
| BudgetForecastingEngine | Currency normalization verified as already fully correct. No changes needed. |
| MultiCurrency Audit | 5 engines audited: `InsightsEngine` (caller responsibility noted), `SpendingPaceCalculator` (SAFE), `CategoryInsightEngine` (SAFE), `AdvancedAnalyticsEngine` (SAFE), `TotalsAggregationEngine` (GAP documented). |

#### Database Version History (Extended)

| Version | Feature / Purpose |
|---------|-------------------|
| **107** | SimpleDateFormat→DateTimeFormatter migrations (no schema change) |
| **108** | REPLACE→IGNORE DAO conversions (batch R+S, no schema change) |
| **109** | Quick wins: isFinite guards, stale matchConfidence clear (Y1+Y8, no schema change) |
| **110** | CURR-2 + TRN-2: exchange rate history, synthetic placeholder fixes |
| **111** | BUD-1: budgets categoryId FK RESTRICT |
| **112** | Category name uniqueness: COLLATE NOCASE index + lowercase normalization |
| **113** | FRESH_INSTALL_CALLBACK alignment for NOCASE index |
| **114** | **WRN-6/WRN-13/BUD-12:** warranties receiptId index non-unique; `refundExpenseId` column on `return_windows`; `rolloverDeficitTracking` column on `budgets` |
| **115** | **I8:** Unique index on `budget_forecasts(budgetId, forecastDate)` to prevent duplicate forecasts |
| **116** | **DB-8:** BudgetForecast FK from CASCADE to RESTRICT |
| **117** | **SourceStatsEvent table:** Creates `source_stats_events` table for event-based notification source stats tracking |

---

## Database Schema

### Version: v141 (current) — see drift entries above for P3/P4 changes
### Historical: v120 (post-hardening; latest migration at that time: 119→120 for InvestmentTransaction, WarrantyLifecycleEvent, GroupSettlementEntity)

The Room schema in v120 includes all tables from v106 plus:

**Phase 5b additions (migration 100→101→102):**

- **New columns on `planned_expenses`:** `status` (TEXT, default PLANNED), `linkedActualExpenseId` (INTEGER), `merchantKey` (TEXT), `updatedAt` (INTEGER, default 0).
- **`recurring_reminder_deliveries`:** unique index hardened on `(occurrenceId, reminderWindow)` — deduplicates stale rows.
- **New table:** `recurring_lifecycle_events` — immutable event log for recurring occurrence lifecycle transitions. Event types: OCCURRENCE_GENERATED, OCCURRENCE_PAID, OCCURRENCE_SKIPPED, OCCURRENCE_CANCELLED, REMINDER_SCHEDULED, REMINDER_SENT, REMINDER_DISMISSED, PLANNED_GENERATED, DRIFT_DETECTED.

**Phase 6 additions (migration 102→103→104):**

- **New table:** `privacy_audit_events` — append-only log of every privacy gate decision (capability, decision ALLOWED/DENIED, reason, context JSON, timestampMs, caller).
- **New columns on `raw_notifications`:** `rawContentPurgedAt` (INTEGER, nullable) — timestamp when raw notification content was purged for data retention.
- **New columns on `scanned_receipts`:** `rawOcrTextPurgedAt` (INTEGER, nullable) — timestamp when raw OCR text was purged for data retention.

**Phase 7 additions (migration 104→105):**

- **New columns on `budgets`:** `activeOverallKey` (INTEGER, unique), `activeCategoryKey` (INTEGER, unique) — materialized invariant keys for active-budget uniqueness.
- **New columns on `group_members`:** `currentUserGroupKey` (INTEGER, unique) — materialized invariant key ensuring one current user per group.
- **New columns on `raw_notifications`:** `dedupeFingerprint` (TEXT, unique) — deterministic SHA-256 fingerprint for notification deduplication.
- **New columns on `planned_expenses`:** `openSourceOccurrenceKey` (TEXT, unique) — materialized invariant key for open planned occurrences.
- **Group expenses index hardened:** `group_expenses.expenseId` index converted to UNIQUE.

**Phase 8 additions (migration 105→106):**

- **New table:** `background_job_runs` — persistent record of worker executions. Columns: id, workerName, startedAt, finishedAt, status (SCHEDULED/RUNNING/SUCCESS/FAILED/RETRY), rowsScanned, rowsUpdated, notificationsSent, retryReason, errorMessage. Indices on `(workerName, startedAt)` and `(status)`.

**Post-Phase 10 hardening migrations (v107→v120):**

| Migration | Purpose | Schema Change |
|-----------|---------|---------------|
| **106→107** | SimpleDateFormat→DateTimeFormatter replacement | No schema change (code-only) |
| **107→108** | REPLACE→IGNORE DAO conversions (batch R+S) | No schema change (code-only) |
| **108→109** | Quick wins: isFinite guards, stale matchConfidence clear | No schema change (code-only) |
| **109→110** | CURR-2 + TRN-2: exchange rate history, synthetic placeholder fixes | Schema adjustments for exchange rate history |
| **110→111** | BUD-1: budgets categoryId FK RESTRICT | FK constraint change on `budgets.categoryId` |
| **111→112** | Category name uniqueness: COLLATE NOCASE index + lowercase normalization | Partial unique index `idx_categories_name_nocase` on `categories(name COLLATE NOCASE)` |
| **112→113** | FRESH_INSTALL_CALLBACK alignment for NOCASE index |
| **113→114** | WRN-6/WRN-13/BUD-12: warranties index + refundExpenseId + rolloverDeficitTracking | Schema: warranties index, return_windows col, budgets col |
| **114→115** | I8: BudgetForecast unique index on (budgetId, forecastDate) | Partial unique index on `budget_forecasts` |
| **115→116** | DB-8: BudgetForecast FK CASCADE→RESTRICT | FK constraint change on `budget_forecasts.budgetId` |
| **116→117** | SourceStatsEvent table for event-based notification stats | New table `source_stats_events` with indices | Callback-triggered index creation for fresh installs |

The full schema now covers:

- Core finance and capture/review: raw notifications, blocked packages, expenses, categories, merchant categories, merchant canonical/alias normalization (`MerchantCanonical`, `MerchantAlias`), pending reviews, user corrections, source stats, budgets, scanned receipts, manual recurring expenses, planned expenses, savings goals
- AI and assistant: AI artifacts, chat sessions/messages, recommendations, and receipt item categorization
- Location: merchant locations and merchant location corrections
- Groups and split: expense groups, group members, group expenses, split templates, and split item assignments
- Planning, alerts, and tracking: anomaly alerts, budget forecasts, budget adjustment recommendations/events, stress forecast snapshots, health score history, savings sweep plans, spending personality profiles, spending challenges
- Financial products and support tables: warranties, return windows, subscription price history/usage/candidates, mileage tracking, exchange rates, bank connections, investments/investment values, and email receipt sources
- **Audit & privacy (Phase 5b/6):** recurring_lifecycle_events, privacy_audit_events

Use the database file and migration chain as the source of truth for the exact table list.

---

## Recent Changes & Fixes

### Current Themes
- destination-driven navigation replaced the older boolean-flag / tab-index approach
- advanced analytics and totals aggregation are now dedicated, first-class flows
- AI, location, groups, split, export, currency, tax, subscriptions, and security are represented in both domain and data layers
- startup/background work is centralized through `MainApplication` and the `startup/` pipeline
- database/schema and DI have expanded significantly; exact file lists should be read from the current codebase, not this summary
- typed errors, time standardization (half-open periods, single source of "now"), accessibility, and performance refactors are now cross-cutting concerns

---

### UI/UX Fixes (47 fixes across batches A-H)

- Batch A: Navigation & Main
- C1: Deep links re-applied on config change — process only when savedInstanceState==null, clear intent after handling
- C2: Back from non-home tabs exits app — route back to Home tab before allowing exit
- C3: Budget detail navigation loses category context — added NavigationDestination.BudgetDetail(categoryId, name)
- C4: Split editor loses expense context on config change — persist expenseId/amount/currency in save token
- C5: Dashboard errors swallowed as empty state — propagate error/loading states through ProcessedDashboardUiState sealed class
- H1: activeTransactionFilter lost on config change — use rememberSaveable with custom listSaver

- Batch B: Dashboard Widgets
- H2: Forecast horizon tab state invalidates after refresh — key state to result.horizons, clamp on change
- H3: Missing legend entry for budget limit line — added third legend item with matching color
- H4: Interactive chips below 48dp touch target — added minimumInteractiveComponentSize() + heightIn(min=48dp)
- H5: Dashboard chip row overflows on small screens — changed to horizontalScroll Row
- H6: Empty donut chart silently hidden — render explicit empty state card
- H7: Enhanced empty state not scrollable — wrapped with verticalScroll + responsive alignment
- H8: Dismiss affordance too small (16dp) + nested clickables — separate IconButton with sizeIn(minWidth=48dp)

- Batch C: Transactions & Review
- H9: Clear filters doesn't fully reset state — reset both _filter and _ownershipFilter, reload data
- H10: Filter ignores tab date range on non-ALL tabs — intersect tab range with filter range, apply amount constraints
- H11: Pull-to-refresh indicator stops prematurely — drive isRefreshing from ViewModel state
- H12: ALL-tab query race conditions — track/cancel prior load job, use request ID guard
- H13: Add Expense advanced fields weakly validated — validate TRANSFER direction/account, share % 0-100, proper keyboards
- H14: Review screen missing "Approve All" — added with confirmation dialog + progress feedback
- H15: Debug actions always accessible in production — gate behind BuildConfig.DEBUG + confirmation dialogs

- Batch D: Analytics & Charts
- H16: Analytics cache stale after transaction changes — added expense freshness signal to cache key, invalidate on change
- H17: Recurring frequency shows fabricated data — use real occurrence count; hide metric if unavailable
- H18: Budget progress NaN when budget is zero — guard with if (budget > 0), show "No budget set" fallback
- H19: Forecast budget line X-range misaligned — compute from actual max series X, not list-size arithmetic

- Batch E: Budget & Savings
- C6: Savings Goals "Add Goal" FAB is no-op — wired to Add Goal dialog with validation + snackbar
- C7: Cash Flow Calendar day selection shows no details — added bottom sheet with income/expenses/recurring/balance
- H20: Cash flow calendar matches only DAY_OF_MONTH — match by full date (normalized midnight millis)
- H21: Budget card shows contradictory numbers — use adjusted spend consistently for progress/remaining/over
- H22: SavingsGoals refresh creates duplicate collectors — cancel prior job before starting new collector
- H23: Smart recommendation "Save" button is no-op — hook to contribution logic with confirmation + snackbar
- H24: Forecast shows no confidence interval — added Low/Base/High bounds with progress bars

- Batch F: AI Assistant
- C8: AI Assistant exceptions leave chat stuck "thinking" — wrap pipeline in try/catch/finally, always reset loading
- C9: AI card components missing from codebase — created AiInsightsCard, AiRecommendationCard, AiChatBubble, AiTypingIndicator
- H25: AI raw exception text shown to users — map to sanitized user messages, log technical details only
- H26: Clear conversation hidden when history disabled — always show "Clear current conversation"
- H27: No API key/connection UX in AI settings — added masked input, secure storage note, "Test connection" CTA

- Batch G: Advanced Features
- H28: Voice search permanently disabled but visible — removed mic action until feature-ready
- H29: Group split dialog has no per-member inputs — added %/amount inputs for non-equal splits with validation
- H30: Price protection "File claim" is no-op — wired to URL launcher, implemented deals from receipts
- H31: Protected items tab is read-only — added Track/Remove actions with confirmation + undo
- H32: VisualSplitEditor crashes on invalid colors — wrapped with runCatching, validate on save/load
- H33: No settlement plan in group detail — added transfer pairs section with one-tap settle
- H34: Subscription cards missing renewal dates — display on cards, require date picker in add dialog
- H35: Bank disconnect has no confirmation — added modal with consequences + undo snackbar

- Batch H: Shared Components & Theme
- C14: Typography hardcodes colors (breaks light theme) — removed color from TextStyle definitions
- H36: Shared components use hardcoded colors — switched to MaterialTheme.colorScheme tokens
- H37: FAB menu has no outside-tap/back dismissal — added BackHandler + scrim with outside-tap dismissal
- H38: Transfer badge loses semantics when label hidden — added contentDescription for incoming/outgoing transfers
- H39: Bottom nav has 6 tabs (Material recommends 3-5) — added small-screen overflow (4 tabs + "More" dropdown)

- Batch I: Settings & Edge Cases (covered separately in release notes; not counted in this 47-fix summary)

- Coverage note: the 47 fixes above cover batches A-H only; related edge-case items are documented in the full release notes.
### Performance Batch (formerly "Phase 10")
- WarrantyDao N+1 query → JOIN
- Geocoding double throttling
- Analytics recomputation
- Unbounded queries → paged
- Missing composite indices (3)
- OCR mutex narrowed
- HTTP clients shared + cached
- Chart recomposition optimized
- Recent transactions capped

### Accessibility Batch (formerly "Phase 11")
- Chart semantics (3 charts)
- Touch targets (3 components)
- BudgetBlockPartyCard semantics
- Heading semantics
- FAB size increased
- Dynamic contentDescriptions
- Redundant speech removed
- Text truncation improved
- Color contrast improved
- Overlapping semantics fixed
- Badge text improved
- Legend labels expanded

### New Components Batch (formerly "Phase 12")
- Domain Models: `DomainBlockStatus`, `DomainDayBudgetStatus`, `DomainTransactionFilter`, `DomainExpenseSummary`, `AiServiceError`, `AiServiceResult`, `GeocodingError`, `GeocodingLookupResult`, `GeocodingBatchResult`, `NearbyPoiResult`, `ProcessingResult` (NotificationProcessingPipeline)
- UI Mappers: `DashboardWidgetUiMapper`, `TransactionFilterUiMapper`
- Repositories: `GroupsRepository`, `GroupsRepositoryImpl`
- Use Cases: `DeleteGroupMemberUseCase`, `DeleteGroupUseCase`, `AddGroupExpenseUseCase`

### Post-Review Hardening (May 2026)

Cross-cutting fixes applied after architecture review tightened correctness, consistency, and edge-case handling across Phases 3–5:

**Transactional guarantees (Phase 3+4+5):** All coordinator operations (create, update, delete, link, unlink, materialize) now run inside a single Room transaction. Receipt delete performs post-commit file cleanup.

**Validation hardening:** Full validation wired for future-date checks, transfer direction/account, expense ownership, and ISO 4217 currency codes in all paths.

**Deduplication completeness:** `deduplicationMode` / `idempotencyKey` fully propagated through coordinators. `STRICT_EXTERNAL_ID` mode uses `idem:`-prefixed dedupeKeys. `BULK_IMPORT` runs standard dedup (no external-id skip). Text and semantic dedup now run post-OCR in the receipt pipeline. Duplicate detection returns real existing IDs (not placeholders).

**Receipt lifecycle fixes:** `processEmailReceipt()` fully implemented with non-bank receipt relink prevention. Hardcoded EUR removed from receipt asset paths. Duplicate receipts correctly marked `DUPLICATE_DETECTED`. Asset double-persistence removed.

**Recurring / materialization fixes:** `RecurringOccurrenceExpander` uses `rule.nextDate` as the expansion anchor. `ReminderDeliveryDao` unique index on (`occurrenceId`, `reminderWindow`). Materialization runs transactionally. `PlannedExpense` gains `status`, `linkedExpenseId`, `merchantKey` columns. Subscription cost-per-use normalized to monthly.

**DI & code quality:** Hilt `@Inject` added to `RecurringOccurrenceExpander` and `OccurrenceConflictResolver`. `ManualExpenseRepository` and `SmartBillNegotiationEngine` DAO leaks fixed. `linkExpenseToOccurrence()` matches on merchant/amount/currency. NLP last-month uses calendar-month boundaries. Structured JSON snapshots for all audit events.

### Historical Addendum: Updated UI Layer Structure
- New screen directories:
  - `groups/` - Shared expense groups
  - `warranty/` - Warranty tracker
  - `carbon/` - Carbon footprint
  - `lifestyle/` - Lifestyle inflation
  - `challenge/` - Spending challenges
  - `negotiation/` - Bill negotiation
  - `price/` - Price protection
  - `naturallanguage/` - Natural language search
  - `split/` - Visual split editor
  - `bank/` - Bank connections
  - `subscription/` - Subscription management
  - `savings/` - Savings goals
  - `investment/` - Investment portfolio
  - `reminder/` - Bill reminders
  - `export/` - Export options
  - `currency/` - Currency management
  - `tax/` - Tax configuration
  - `receiptmatching/` - Receipt matching
  - `assistant/` - AI assistant
  - `aisettings/` - AI settings

### Historical Addendum: Updated Domain Layer Structure
- New domain directories:
  - `groups/` - Shared expense logic
  - `warranty/` - Warranty tracking
  - `carbon/` - Carbon footprint
  - `lifestyle/` - Lifestyle inflation
  - `challenge/` - Spending challenges
  - `negotiation/` - Bill negotiation
  - `price/` - Price protection
  - `naturallanguage/` - Natural language search
  - `split/` - Split transactions
  - `subscription/` - Subscription management
  - `savings/` - Savings goals
  - `investment/` - Investment tracking
  - `reminder/` - Bill reminders
  - `export/` - Export functionality
  - `currency/` - Currency management
  - `tax/` - Tax estimation
  - `receiptmatching/` - Receipt matching
  - `ai/` - AI services and use cases
  - `forecasting/` - Monte Carlo simulation
  - `model/dashboard/` - Domain models for dashboard
  - `model/navigation/` - Domain models for navigation

### Stage 1 Architecture Foundations (2026-05-06)
- BackupPrivacyMode enum (4 backup privacy levels)
- CloudPayloadRedactor interface (unified cloud AI redaction contract)
- ForecastDataQuality data class (additive field for partial-currency forecasting)
- CI guard: scripts/guards/check_lifecycle_bypasses.kts
- WorkerContractTest verifying all 7 default workers

### Stage 2 — CloudPayloadRedactor Migration Complete (2026-05-09)
- Migrated 6 cloud providers from direct `CloudPiiSanitizer` calls to `CloudPayloadRedactor`:
  `CloudReviewExplanationService`, `CloudReceiptAssistService`, `CloudCategorizationAssistService`,
  `CloudDedupeJudgeService`, `CloudReceiptItemCategorizationService`, `CloudWarrantyExtractionService`.
- Removed extra `shouldRedact` argument from `redactor.redactMerchant()` calls.
- `DashboardBriefingPromptFormatter` intentionally left on `CloudPiiSanitizer` (prompt formatter, not cloud service; `DefaultCloudPayloadRedactor` wraps `CloudPiiSanitizer`).

## Quick Reference

### Add New Parser
1. Create `domain/parser/parsers/NewParser.kt` extending base parser
2. Register in `AppParserRegistry.parserList`
3. Add test cases in `domain/parser/`

### Add New Screen
1. Create the screen and ViewModel under the feature directory.
2. Add a `NavigationDestination` entry if it should be routable.
3. Register the route in `NavigationController`, and render the destination in `ui/MainActivity.kt`'s destination `when` block; deep-link handling lives in `MainActivity.handleIntent()`.
4. Add DI bindings only if the screen introduces new data or domain dependencies.

### Add New Database Entity
1. Create `data/database/entity/NewEntity.kt`.
2. Add it to `AppDatabase` and update the migration chain.
3. Create the matching DAO in `data/database/dao/NewEntityDao.kt`.
4. Bind the DAO / repository in the appropriate Hilt module.
5. Update any schema notes only after the migration is in place.

### Check Bug Sources
| Issue | Check Files |
|-------|-------------|
| Forecast wrong | `SynthesisEngine`, `MonteCarloSpendingSimulator`, `BudgetCalculator` |
| Budget alerts | `BudgetMonitor`, notification service bindings |
| Parser failing | `AppParserRegistry`, specific parsers, `ConfidenceRouter` |
| OCR / receipt issues | `ReceiptOcrService`, `ReceiptParser`, AI receipt categorization flow |
| Category wrong | `CategorizationEngine`, `MerchantCanonicalizer`, `HybridExpenseClassifier` |
| Recurring missed | recurring-expense engine + repositories |
| Analytics slow | `InsightsEngine`, `AdvancedAnalyticsEngine`, totals aggregation |
| Navigation broken | `NavigationDestination`, `NavigationController`, `MainActivity.handleIntent()` |
| Map / location issues | location resolver + geocoding providers |
| AI flow issues | AI module, assistant / follow-through use cases |

---

## Testing

### Unit Tests Location
```
app/src/test/java/com/yourname/expensetracker/
└── ...
app/src/test/kotlin/com/yourname/expensetracker/
├── domain/
│   ├── budget/
│   │   ├── BudgetMonitorTest.kt
│   │   └── BudgetCalculatorTest.kt
│   ├── logic/
│   │   └── RecurringExpenseEngineTest.kt
│   ├── parser/
│   │   ├── GreekBankParserTest.kt
│   │   └── RevolutParserTest.kt
│   └── analytics/
│       └── InsightsEngineTest.kt
├── data/repository/
│   ├── ExpenseRepositoryTest.kt
│   └── FinancialWeatherRepositoryTest.kt
└── domain/util/
    └── TimePeriodUtilsTest.kt
```

### Run Tests
```bash
./gradlew testDebugUnitTest
```

---

## Common Patterns

### StateFlow Usage
```kotlin
// In ViewModel
val state: StateFlow<UiState> = repository.data
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue)

// In Composable
val state by viewModel.state.collectAsState()
```

### Repository Pattern
```kotlin
@Singleton
class ExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun getExpenses(): Flow<List<Expense>> = expenseDao.getAll()
    
    suspend fun insertExpense(expense: Expense) = withContext(ioDispatcher) {
        expenseDao.insert(expense)
    }
}
```

### Engine Pattern
```kotlin
class SynthesisEngine @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringExpenseRepository
) {
    suspend fun generateForecast(): FinancialForecast = withContext(Dispatchers.Default) {
        // Complex calculation
    }
}
```

---

## Navigation

## Appendix: Complete File Reference

### UI Components (`ui/components/`)
| Component | Purpose |
|-----------|---------|
| `BentoCard.kt` | Dashboard card layout |
| `PulseDot.kt` | Animated pulse indicator |
| `FinancialWeatherCard.kt` | Forecast display (explicit states) |
| `SpendingTrendChart.kt` | Trend visualization |
| `SpendingPaceGauge.kt` | Spending pace gauge |
| `ChartMarker.kt` | Chart markers |
- ... (additional components documented as introduced in the codebase)

### Domain Models (`domain/model/`)
- See expanded domain model list in this document as features were added.

### All Repositories (`data/repository/`)
- See expanded repository list in codebase for Groups, AI, and analytics.

### Android Services & Receivers
- Notification Capture, Service Restart, Boot

### Database Entities (Room)
- See expanded entity list in the updated DB schema section.

---

## Segment Mapping

- Use `NavigationDestination` as the routing source of truth; segment numbering is documented in `CODEBASE_SEGMENTS.md`.
- Deep links are handled in `ui/MainActivity.kt` (`handleIntent` / `onNewIntent`); navigation state is owned by `NavigationController`.

### F1–F15 Architecture Mapping

| Feature | Primary Flow | Key Domain / Use Case | Key Data + DB |
|---|---|---|---|
| **F1 Receipt → Warranty Pipeline** | Scanned receipt → warranty extraction → persisted warranty | `AutoCreateWarrantyFromReceiptUseCase`, `WarrantyTextExtractor` | `WarrantyDao`, `warranties` table |
| **F2 Notification → Subscription Detection** | Transaction stream → recurring pattern detection → candidate surfaced | `NotificationSubscriptionDetector`, `SubscriptionManagerEngine` | `SubscriptionCandidateDao`, `subscription_candidates` |
| **F3 Monte Carlo → Budget Linking** | Forecast simulation → budget impact insights | `GetMonteCarloBudgetImpactUseCase`, `MonteCarloSpendingSimulator` | Budget/expense DAOs + forecast models |
| **F4 Today's Money Radar Widget** | Home aggregation → radar widget model → dashboard render | `ComputeDashboardWidgetsUseCase` | `MoneyRadarWidget`, dashboard config |
| **F5 Financial Health Score 2.0** | Health computation → trend snapshot persistence | `FinancialHealthScoreV2` | `HealthScoreHistoryDao`, `health_score_history` |
| **F6 Smart Savings Sweeps** | Month-end underspend → safe sweep plan generation | `MonthlySavingsSweepUseCase` | `SavingsSweepPlanDao`, `savings_sweep_plan` |
| **F7 Anomaly → Real-Time Alerts** | Analytics anomaly detection → alert orchestration + cooldown | `AnomalyDetector`, `AnomalyAlertOrchestrator` | `AnomalyAlertDao`, `anomaly_alerts` |
| **F8 Financial Stress Forecast (30/60/90d)** | Forward stress simulation → snapshot + risk levels | `FinancialStressForecastEngine` | `StressForecastSnapshotDao`, `stress_forecast_snapshots` |
| **F9 AI Budget Autopilot** | Trend analysis → recommendation → application event | `BudgetAutopilotEngine` | `budget_adjustment_recommendations`, `budget_adjustment_events` |
| **F10 Contextual Empty States** | No-data contexts → contextual CTA rendering | Empty-state strategy components | `EnhancedEmptyState`, `EmptyStateAction` |
| **F11 Shared Expenses → Budget Offset** | Shared spend + reimbursements → effective budget pressure | `SharedExpenseBudgetOffsetEngine` | `group_expenses` reimbursement columns |
| **F12 Lifestyle Inflation → Savings Goals** | Lifestyle drift signal → prompt + savings guidance | `LifestyleSavingsPromptUseCase`, `LifestyleInflationDetector` | `prompt_states` |
| **F13 Spending Personality Profile** | Spending behavior analysis → profile classification | `SpendingPersonalityClassifier` | `SpendingPersonalityProfileDao`, `spending_personality_profiles` |
| **F14 Email Receipt Ingestion** | Email parser → receipt linkage → normalized source tracking | `EmailReceiptIngestionService`, `EmailReceiptParser` | `EmailReceiptDao`, `email_receipt_sources` |
| **F15 Conversational Finance Assistant** | Assistant query → AI context/results → UI card/sheet | Assistant orchestration in `AssistantViewModel` | `ai_chat_sessions`, `ai_chat_messages`, AI artifacts |

### DI Module Updates (Feature Wave)

- `DatabaseModule`: migration chain extended to **MIGRATION_67_68**.
- `DaoModule`: feature DAOs bound for anomaly/health/sweep/subscription/stress/personality/email/budget adjustment paths.
- `SubscriptionModule`: **DELETED** (2026-05-09) — `SubscriptionManagerEngine` auto-provided by `@Singleton @Inject constructor`.
- `EmptyStateModule`: contextual empty-state behavior bindings.
- Existing `AiModule`, `SecurityModule`, `NetworkModule`, `GroupsModule` reused by F1/F9/F11/F14/F15 integration points.

### Migration History (Recent)

| Migration | Feature / Purpose |
|---|---|
| 53→54 | F1 receipt→warranty auto-detection fields |
| 54→55 | F11 shared expense reimbursement/budget-offset fields |
| 55→56 | F12 prompt state persistence |
| 56→57 | F5 health score history table |
| 57→58 | F6 savings sweep planning table |
| 58→59 | F2 subscription candidate detection table |
| 59→60 | Health score schema replay safety |
| 60→61 | F9 budget autopilot recommendation/event tables |
| 61→62 | F8 stress forecast snapshot table |
| 62→63 | F13 spending personality profile table |
| 63→64 | Stress snapshot replay safety |
| 64→65 | F14 email receipt source table |
| 65→66 | Email-ingested receipt nullable image path |
| 66→67 | Warranty deduplication hardening |
| **67→68** | **Migration repair pass: rebuild anomaly/feature-wave tables to canonical schemas, fix malformed zero-column tables, preserve data when structure is valid** |

This appendix is historical context for the earlier feature-wave rollout and is not part of the current architecture body.

---

## Post-Roadmap Hardening (Batches A–L)

After the initial feature-wave rollout, the codebase underwent 12 structured hardening batches to address data-integrity, currency-safety, privacy, and invariant-enforcement issues identified across 21 review documents.

### Batch Summary

| Batch | Scope | Key Changes |
|-------|-------|-------------|
| **A** | Critical Data Integrity (17 issues) | Fixed NotificationRepository.deleteAll() wipe, PendingReviewDao REPLACE data loss, non-transactional group operations, cascade-delete risks, budget autopilot period mismatch |
| **B** | Currency Normalization (21 issues) | Populated baseAmount/baseCurrency/exchangeRateUsed fields, fixed raw-sum aggregations across dashboard, forecast, location, warranty; added currency-aware filtering |
| **C** | Coordinator Adoption (25 issues) | Routed legacy paths through ReceiptLifecycleCoordinator, TransactionLifecycleCoordinator, GroupLifecycleCoordinator; eliminated split-brain receipt-linking |
| **D** | Privacy Hardening (~15 issues) | Closed bypassable PrivacyGate entry points; added privacy checks to backup/export, location, AI cloud calls, notification capture |
| **E** | DB Schema Invariants (~12 issues) | Added materialized-key CHECK constraints for active budgets, group members; unique indexes for dedupe keys; FK enforcement for paidById same-group rule |
| **F** | Remaining High-Severity (~30 issues) | Fixed AI output validation, confidence-scale normalization, period-boundary correctness, search query currency-awareness |
| **G** | Notification Pipeline (~10 issues) | Added dedupe-fingerprint unique index, oversized-amount protection, reliability retry logic, fallback parser coverage |
| **H** | Warranty & Return Window (~8 issues) | Fixed cascade-delete from receipt→warranty, added price-protection currency safety, manual warranty receipt-placeholder EUR hardcode |
| **I** | Shared Expense & Groups (~10 issues) | Soft-delete consistency, settlement-calculator precision, multi-currency group settlement, budget offset engine hardening |
| **J** | Search & Query (~12 issues) | Currency-aware min/max filters, effectiveAmount normalization in largest-queries, cross-source deduplication time-window fixes |
| **K** | Forecast & Weather (~10 issues) | Weather forecast recurring-pattern integration, dashboard-vs-weather data-scope alignment, stress-fallback degraded-mode tests |
| **L** | Migration Policy & Final Audit (~8 issues) | Migration path for schema versions 1–5, worker-config freeze resolution, startup-workflow conditional scheduling, migration repair pass (67→68) |

### Architectural Impact

- **Lifecycle Coordinators** now mediate all write paths for receipts, transactions, groups, and recurring expenses, replacing ad-hoc repository methods.
- **AnalyticsCurrencyNormalizer** middleware sits between all DAO aggregations and engine consumers, ensuring multi-currency safety without per-engine changes.
- **Materialized-Key Constraints** (CHECK + UNIQUE) enforce active-budget and group-membership invariants at the SQLite level, preventing data corruption from code bugs.
- **PrivacyGate** is invoked at every public entry point of backup, export, location, and AI services; all privacy decisions are audited via `PrivacyAuditDao`.
- **Migration Policy** now includes a forward-only repair-migration pattern (used in 67→68) and a schema-version 1–5 import compatibility layer.

### Metrics

- ~178 issues resolved across 12 batches
- ~120 files modified or added
- 7 lifecycle coordinators introduced (transaction, receipt, recurring, group, plus 3 domain-use-case coordinators)
- 3 normalizer/validator middleware services added (currency, privacy, AI-output)
- 15+ materialized-key constraints deployed
- Database version advanced from v68 to v120
