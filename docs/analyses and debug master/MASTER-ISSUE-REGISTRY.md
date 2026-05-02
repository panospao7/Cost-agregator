# Master Issue Registry

> Generated: 2026-05-02
> Sources: 21 review documents across all subsystems
> Consolidation: Every issue marked STILL PRESENT or PARTIALLY RESOLVED

---

## Summary

- **Total unresolved issues:** 356
- **By status:** STILL PRESENT = 254, PARTIALLY RESOLVED = 102
- **By severity:** CRITICAL = 25, MAJOR/HIGH = 190, MEDIUM = 62, MINOR/LOW = 79
- **By subsystem coverage:** 18 subsystem areas affected

---

## Priority Batches (Recommended Execution Order)

### Batch A: Critical Data Integrity (17 issues, ~15 files)
Immediate financial-data-corruption risks before multi-currency or shared-expense production use.

| Issue | Description | File(s) |
|-------|-------------|---------|
| TRN-11 | `NotificationRepository.deleteAll()` wipes all expenses | NotificationRepository.kt |
| TRN-3 | `PendingReviewDao.approveAllPending()` footgun bypasses expense creation | PendingReviewDao.kt |
| TRN-14 | rawNotificationId not unique � race creates duplicate expenses | Expense.kt |
| TRN-4 | PendingReviewDao.insert() uses REPLACE � silently loses data | PendingReviewDao.kt |
| SHR-2 | addExpenseToGroup() not transactional � TOCTOU risks | GroupTransactionCoordinator.kt |
| SHR-4 | Hard delete leaves linked Expenses semantically orphaned | GroupTransactionCoordinator.kt |
| SHR-7 | paidById cross-group rule not DB-enforced | GroupExpense.kt, AppDatabase.kt |
| BUD-7 | Category deletion converts category budgets to overall (FK SET NULL) | Budget.kt |
| DB-3 | group_expenses.paidById same-group enforcement out of scope | AppDatabase.kt, GroupExpense.kt |
| DB-8 | Cascade deletes risk financial history loss (group?expense, receipt?warranty) | Multiple entity files |
| WRN-1 | Receipt?warranty propagation missing on match | ReceiptLinkService.kt |
| WRN-5 | Deleting receipt cascades to delete warranties/return windows | Warranty.kt, ReturnWindow.kt |
| AID-9 | No AI result application boundary � suggestions auto-apply without audit | Multiple AI services |
| CURR-6 | Home currency change without re-normalization | CurrencySettingsRepositoryImpl.kt |
| BUD-5 | Critical budgets counted as healthy in CalculateBudgetStatusUseCase | CalculateBudgetStatusUseCase.kt |
| BUD-19 | Budget autopilot ignores budget period (weekly gets monthly recommendation) | BudgetAutopilotEngine.kt |
| FCST-1 | Month forecast counts each recurring pattern only once (weekly �10?�10 not �40) | SynthesisEngine.kt |

### Batch B: Currency Normalization (21 issues, ~18 files)
Multi-currency safety: raw-sums, missing currency fields, hardcoded EUR, missing conversion snapshots.

| Issue | Description | File(s) |
|-------|-------------|---------|
| CURR-1 | Expense baseAmount/baseCurrency/exchangeRateUsed schema-only, never populated | Expense.kt, CurrencyConverter.kt |
| CURR-2 | Exchange rate unique constraint prevents historical rates | ExchangeRate.kt |
| CURR-4 | CurrencyConverter.convert() has no date/context parameter | CurrencyConverter.kt |
| TRN-6 | Approval cannot correct currency � no finalCurrency param | ReviewQueueRepository.kt |
| RCP-7 | Receipt matching ignores currency � 100 USD matches 100 EUR | ReceiptTransactionMatcher.kt |
| RCP-10 | Receipt review cannot edit currency | ReceiptScanState.kt |
| RCP-9 | Receipt currency defaults to EUR too aggressively | ReceiptRepository.kt |
| REC-13 | Bill reminder monthly total raw-sums currencies (�20+$20=40) | BillReminderManager.kt |
| REC-12 | Recurring detection uses first currency in merchant group | RecurringExpenseEngine.kt |
| LOC-9 | Location analytics raw-sum currencies | SpendingHeatmapEngine, LocationInsightsEngine |
| DSH-10 | TotalsAggregationEngine uses deprecated raw-sum DAO methods | TotalsAggregationEngine.kt |
| DSH-8/9 | CategorySpending/SpendingSummary.currency hardcoded EUR | ComputeDashboardWidgetsUseCase.kt |
| BUD-17 | Budget suggestions raw-sum currencies + hardcode euro | BudgetRepository.kt |
| SRH-7 | Assistant min/max amount filters not currency-aware | ExpenseRepository.kt |
| SRH-6 | executeLargest uses raw effectiveAmount without conversion | ExecuteFinancialQueryUseCase.kt |
| WRN-8 | Protected value raw-sums gross (non-effective, no currency) | WarrantyDao.kt |
| WRN-26 | Price protection not currency-safe | PriceProtectionTracker.kt |
| FCST-8 | Forecast money is raw Double with no currency | FinancialForecast.kt, ForecastComponents.kt |
| BUD-27 | BudgetRecommendationEngine hardcodes EUR in health summary | BudgetRecommendationEngine.kt |
| REC-18 | Subscription recommendations hardcode � in user-facing text | SubscriptionManagerEngine.kt |
| WRN-4 | Manual warranties create fake placeholder receipts (hardcoded EUR) | WarrantyTrackerViewModel.kt |

### Batch C: Coordinator Adoption (25 issues, ~18 files)
Lifecycle coordinators exist but legacy paths bypass them.

| Issue | Description | File(s) |
|-------|-------------|---------|
| RCP-N1 | ReceiptMatchingViewModel uses legacy paths, bypassing ReceiptLinkService | ReceiptMatchingViewModel.kt |
| RCP-N3 | Batch processing does not use ReceiptLifecycleCoordinator | ReceiptRepository.kt |
| RCP-1 | Legacy `saveReceiptImage()` uses collision-prone filenames | ReceiptOcrService.kt |
| RCP-24 | Legacy `deleteReceipt()` deletes image before DB | ReceiptRepository.kt |
| RCP-20 | Batch path bypasses ReceiptDuplicateDetector | ReceiptRepository.kt |
| RCP-4 | Manual scan triggers warranty extraction before user confirmation | ReceiptRepository.kt |
| REC-1 | Legacy getNotificationsDue() still active with no persistence | BillReminderManager.kt |
| REC-2 | Legacy markBillPaid() does not create/link actual payment | BillReminderManager.kt |
| REC-22 | Legacy markBillPaid() doesn't update updatedAt | BillReminderManager.kt |
| WRN-N2 | Dual receipt-linking paths create split-brain risk | ReceiptRepository.kt, ReceiptLinkService.kt |
| BAK-N1 | Legacy importDatabase() no maintenance mode or journal | DatabaseBackupRepositoryImpl.kt |
| BAK-5/6/7 | Legacy import lacks crash-atomicity, maintenance mode, restart | DatabaseBackupRepositoryImpl.kt |
| SHR-18 | Adapter bypasses coordinator for deleteGroup | SharedExpenseDataPortAdapter.kt |
| SHR-19 | Soft vs hard delete group inconsistency | DeleteGroupUseCase.kt, SharedExpenseManager.kt |
| FCST-N1 | Weather path passes manualRecurringEntities=emptyList() | FinancialWeatherRepository.kt |
| FCST-5 | Dashboard vs weather forecast use different data scopes | ComputeDashboardWidgetsUseCase.kt |
| FCST-6 | Weather forecast ignores detected recurring patterns | FinancialWeatherRepository.kt |
| FCST-N2 | Dashboard forecast bypasses AnalyticsCurrencyNormalizer | ComputeDashboardWidgetsUseCase.kt |
| WRK-1 | KEEP freezes old worker config forever | All schedule() methods |
| WRK-9 | Startup schedules all workers unconditionally | AppStartupCoordinator.kt |
| WRK-5 | AI briefing has no WorkManager constraints | AiWorkSchedulerImpl.kt |
| PRV-9 | Background workers not synced on setting changes | AiSettingsViewModel.kt |
| RCP-27 | PDF processing limits to 5 pages silently | ReceiptOcrService.kt |
| TRN-10 | markAsRelevant() side effects miss recommendation/subscription/transfer | ReviewQueueRepository.kt |
| RSP-R2A | No migration path for schema versions 1-5 | AppDatabase.kt |

### Batch D: Privacy Gate Coverage (18 issues, ~15 files)
Entry points bypassing privacy gate � potential data leaks.

| Issue | Description | File(s) |
|-------|-------------|---------|
| PRV-N1 | Photon/Geoapify/GooglePlaces geocoding bypass privacy gate | PhotonGeocodingService.kt, GeoapifyGeocodingService.kt, GooglePlacesGeocodingService.kt |
| PRV-1 | BootReceiver/ServiceRestartReceiver start notification service unconditionally | BootReceiver.kt, ServiceRestartReceiver.kt |
| PRV-2 | Finance-app notifications captured unconditionally (no deny-keywords) | NotificationFilter.kt |
| PRV-14 | DataStore corruption handler fails open (enables AI silently) | AiSettingsRepositoryImpl.kt |
| AID-N1 | CloudQueryInterpretationService has zero privacy guards | CloudQueryInterpretationService.kt |
| AID-N2 | CloudReceiptItemCategorizationService / CloudWarrantyExtractionService lack allowCloudAi/PrivacyGate | Multiple cloud service files |
| AID-5 | Cloud providers inconsistently use PrivacyGate vs inline checks | Multiple cloud service files |
| AID-10 | Per-request diagnostics not systematically captured | AiRuntimeDiagnostics.kt |
| SRH-21 | No UI notice that cloud queries include merchant context | CloudQueryInterpretationService.kt |
| SRH-29 | Exported files in cache without encryption | AccountingExportRepository.kt |
| WRN-15 | Cloud extraction has no on-device fallback | WarrantyTrackerRepository.kt |
| WRK-8 | Startup sync no error containment | AppStartupCoordinator.kt |
| AID-9 | AI result application boundary absent | Multiple AI services |
| AID-4 | Runtime fallback missing in 6 of 7 hybrid services | Hybrid*Service.kt files |
| PRV-3 | Notification posting vs reading permission confusion | MainActivity.kt, NotificationPermissionDialog.kt |
| PRV-10 | Foreground service type location on notification service | AndroidManifest.xml |
| PRV-11 | POST_NOTIFICATIONS on first launch not JIT | MainActivity.kt |
| PRV-16 | Deep links exported through custom scheme without auth | AndroidManifest.xml |

### Batch E: DB Schema & Migration Hardening (20 issues, ~12 files)

| Issue | Description | File(s) |
|-------|-------------|---------|
| DB-2 | Budget forecast + subscription candidate invariants not DB-enforced | BudgetForecast.kt, SubscriptionCandidate.kt |
| DB-4 | INSERT SELECT* still used in 5 critical migration paths | AppDatabase.kt |
| DB-5 | repairTable() still uses all-or-nothing salvage | AppDatabase.kt |
| DB-6 | Exchange rate uniqueness per pair, not per pair+date | ExchangeRate.kt |
| DB-7 | String @ColumnInfo(defaultValue) annotations inconsistent | Multiple entity files |
| RSP-R2B | Multi-hop MIGRATION_96_100 with missing schema JSON for v97/98/99 | AppDatabase.kt |
| RSP-R3A | No migration tests for versions 92->108 (16 migrations uncovered) | DatabaseMigrationTest.kt |
| RSP-A1 | MIGRATION_107_108 CHECK may fail if openSourceOccurrenceKey mismatched | AppDatabase.kt |
| TRN-13 | Nullable dedupeKey weakens DB duplicate protection | Expense.kt, ExpenseDao.kt |
| TRN-15 | Resolved reviews' suggested fields mutated by upsert | PendingReviewDao.kt |
| SHR-16 | currentUserGroupKey CHECK constraint no-op for NULL values | GroupMember.kt, AppDatabase.kt |
| BUD-25 | Budget forecast uniqueness app-layer only (no DB unique index) | BudgetForecastDao.kt |
| BUD-28 | Category names not unique | Category.kt, CategoryDao.kt |
| BUD-29 | CategoryDao.getByName() exact/case-sensitive | CategoryDao.kt |
| BUD-30 | Default categories not protected at DAO level | CategoryDao.kt |
| BUD-31 | Deleting category cascades to delete merchant mappings | MerchantCategory.kt |
| RCP-19 | ScannedReceiptDao.insert() uses REPLACE | ScannedReceiptDao.kt |
| RCP-N4 | receipt_item_categorizations insert uses REPLACE | ReceiptItemCategorizationDao.kt |
| RSP-R5A | No legacy DB importer for pre-v6 schemas | (new file needed) |
| RSP-R6A | No fresh-vs-migrated side-by-side parity test | DatabaseMigrationTest.kt |

### Batch F: Remaining Issues (255 issues across all subsystems)
See Full Issue Inventory below � sorted by severity within each subsystem area.

---

## Full Issue Inventory (by Subsystem)

### 1. Transaction Lifecycle

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | TRN-11 | NotificationRepository.deleteAll() wipes expenses+reviews+corrections | CRITICAL | STILL PRESENT | NotificationRepository.kt | Rename/split method |
| 2 | TRN-3 | PendingReviewDao.approveAllPending() footgun bypasses expense creation | CRITICAL | STILL PRESENT | PendingReviewDao.kt | Restrict/remove DAO footgun |
| 3 | TRN-14 | rawNotificationId not unique on Expense � race creates duplicates | CRITICAL | STILL PRESENT | Expense.kt | Add unique index |
| 4 | TRN-4 | PendingReviewDao.insert() uses REPLACE � loses status/receipt linkage | CRITICAL | STILL PRESENT | PendingReviewDao.kt | Change to ABORT/IGNORE |
| 5 | TRN-6 | Approval cannot correct currency � no finalCurrency param | CRITICAL | STILL PRESENT | ReviewQueueRepository.kt | Add finalCurrency param |
| 6 | TRN-8 | Raw duplicate check after parse/AI fallback (waste) | MAJOR | STILL PRESENT | NotificationProcessingPipeline.kt | Add fingerprint pre-check |
| 7 | TRN-9 | Raw notification dedupe depends on fragile fields | MAJOR | STILL PRESENT | RawNotificationDao.kt | Add content fingerprint |
| 8 | TRN-12 | FK ON DELETE SET NULL detaches source audit | MAJOR | STILL PRESENT | Expense.kt | Add immutable source metadata |
| 9 | TRN-16 | Source stats mutable counters, not event-derived | MAJOR | STILL PRESENT | SourceStatsDao.kt | Add event ledger |
| 10 | TRN-17 | Bulk approval no structured result | MAJOR | STILL PRESENT | ReviewQueueRepository.kt | Add BulkReviewResult |
| 11 | TRN-18 | Location approval partial state (lat+null lon=USER_MANUAL) | MAJOR | STILL PRESENT | ReviewQueueRepository.kt | Add pair-validation |
| 12 | TRN-2 | Fallback pending reviews use fake 0.01 EUR confidence=1.0 | MAJOR | PARTIALLY | ReviewQueueRepository.kt, TransactionLifecycleCoordinator.kt | Add extractionState/nullable amount |
| 13 | TRN-5 | Validator missing location-pair validation | MAJOR | PARTIALLY | TransactionLifecycleCoordinator.kt | Add location pair validator |
| 14 | TRN-7 | Duplicate outcomes carry existingExpenseId but not persisted | MAJOR | PARTIALLY | CreateExpenseResult.kt, ReviewQueueRepository.kt | Persist DuplicateResolution record |
| 15 | TRN-10 | markAsRelevant() misses recommendation/subscription/transfer effects | MAJOR | PARTIALLY | ReviewQueueRepository.kt | Add missing side effects |
| 16 | TRN-13 | Nullable dedupeKey � paths bypassing coordinator leave null | MAJOR | PARTIALLY | Expense.kt, ExpenseDao.kt | Add DB CHECK non-null |
| 17 | TRN-15 | Resolved reviews' suggested fields mutated by upsert | MAJOR | PARTIALLY | PendingReviewDao.kt | Restrict upsert to PENDING


### 2. Receipt Lifecycle

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | RCP-6 | Receipt item categorizations not linked when receipt?expense | CRITICAL | STILL PRESENT | ReceiptLinkService.kt, ReceiptItemCategorizationDao.kt | Wire linkToExpense call |
| 2 | RCP-7 | Receipt matching ignores currency (100 USD = 100 EUR) | CRITICAL | STILL PRESENT | ReceiptTransactionMatcher.kt | Add currency check |
| 3 | RCP-10 | Receipt review cannot edit currency | CRITICAL | STILL PRESENT | ReceiptScanState.kt | Add currency picker |
| 4 | RCP-14 | Item-level tax duplicated per item | MAJOR | STILL PRESENT | ReceiptItemCategorizationRepository.kt | Fix tax distribution |
| 5 | RCP-16 | Receipt item rows lack stable identity | MAJOR | STILL PRESENT | ReceiptItemCategorization.kt | Add itemIndex/fingerprint |
| 6 | RCP-18 | Receipt total from line items without source tracking | MAJOR | STILL PRESENT | ReceiptParser.kt | Add total-source tracking |
| 7 | RCP-19 | ScannedReceiptDao.insert() uses REPLACE | MAJOR | STILL PRESENT | ScannedReceiptDao.kt | Change conflict strategy |
| 8 | RCP-21 | Receipt matching can match bank statement receipts | MAJOR | STILL PRESENT | ScannedReceiptDao.kt | Add documentType filter |
| 9 | RCP-22 | Receipt matching approve leaves stale suggestion fields | MAJOR | STILL PRESENT | ReceiptRepository.kt | Clear suggestedExpenseId |
| 10 | RCP-23 | Matching uses gross in UI, effective in scoring | MAJOR | STILL PRESENT | ReceiptMatchingScreen.kt, ReceiptTransactionMatcher.kt | Align amount basis |
| 11 | RCP-30 | Item categorization does not affect expense/budget model | MAJOR | STILL PRESENT | ReceiptRepository.kt | Define item?budget allocation |
| 12 | RCP-N1 | ReceiptMatchingViewModel bypasses ReceiptLinkService | MAJOR | STILL PRESENT | ReceiptMatchingViewModel.kt | Wire to ReceiptLinkService |
| 13 | RCP-N2 | No currency editing in receipt review UI | MAJOR | STILL PRESENT | ReceiptScanState.kt, ReceiptScanViewModel.kt | Add editCurrency field+UI |
| 14 | RCP-2 | Unknown-size content providers bypass file-size protection | MAJOR | STILL PRESENT | ReceiptOcrService.kt | Add streaming copy limit |
| 15 | RCP-11 | AI quick save uses suggestions without confidence thresholds | MAJOR | STILL PRESENT | ReceiptScanViewModel.kt | Add confidence check |
| 16 | RCP-12 | AI receipt extraction validation incomplete (no total>0, tax>=0, date check) | MAJOR | STILL PRESENT | CloudReceiptAssistService.kt | Add ReceiptAssistSuggestionValidator |
| 17 | RCP-13 | Receipt item AI validation checks count only | MAJOR | STILL PRESENT | CategorizeReceiptItemsUseCase.kt | Add full item validation |
| 18 | RCP-29 | OCR saved image too low quality for cloud assist | MAJOR | STILL PRESENT | ReceiptOcrService.kt | Store original-quality variant |
| 19 | RCP-27 | PDF processing silently limits to first 5 pages | MEDIUM | STILL PRESENT | ReceiptOcrService.kt | Add user-visible warning |
| 20 | RCP-28 | OCR retry inconsistent (PDF path no retry) | MEDIUM | STILL PRESENT | ReceiptOcrService.kt | Add retry to PDF path |
| 21 | RCP-17 | Line item parser defines unused patterns [2][3] | MINOR | STILL PRESENT | ReceiptParser.kt | Remove dead code |
| 22 | RCP-1 | Legacy saveReceiptImage() uses collision-prone filenames | MAJOR | PARTIALLY | ReceiptOcrService.kt | Route through ReceiptAssetStore |
| 23 | RCP-3 | Failed parsing creates fake 0.01 EUR pending reviews | MAJOR | PARTIALLY | ReceiptRepository.kt | Use nullable amount+extractionState |
| 24 | RCP-4 | Manual scan triggers warranty before user confirmation | MAJOR | PARTIALLY | ReceiptRepository.kt | Gate warranty on autoCreateReview |
| 25 | RCP-9 | Receipt currency defaults to EUR too aggressively | MAJOR | PARTIALLY | ReceiptRepository.kt | Use parsed/null currency |
| 26 | RCP-15 | Item categorization save not fully transactional | MEDIUM | PARTIALLY | CategorizeReceiptItemsUseCase.kt | Add @Transaction wrapper |
| 27 | RCP-20 | Duplicate receipt not detected by content in batch path | MAJOR | PARTIALLY | ReceiptRepository.kt | Route batch through coordinator |
| 28 | RCP-24 | Legacy deleteReceipt() deletes image before DB | MAJOR | PARTIALLY | ReceiptRepository.kt | Fix delete ordering |
| 29 | RCP-N3 | Batch processing not using ReceiptLifecycleCoordinator | MEDIUM | STILL PRESENT | ReceiptRepository.kt | Route through coordinator |
| 30 | RCP-N4 | receipt_item_categorizations insert uses REPLACE | MEDIUM | STILL PRESENT | ReceiptItemCategorizationDao.kt | Change to ABORT/IGNORE |

### 3. Recurring/Subscription

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | REC-3 | Overdue bills advance only one interval | CRITICAL | STILL PRESENT | BillReminderManager.kt | Implement pay-through-today |
| 2 | REC-13 | Monthly total raw-sums currencies | CRITICAL | PARTIALLY | BillReminderManager.kt | Return Map<Currency,Double> |
| 3 | REC-2 | Legacy markBillPaid() no actual payment | CRITICAL | PARTIALLY | BillReminderManager.kt | Delegate to coordinator |
| 4 | REC-7 | Price change recorded but amount not updated | MAJOR | STILL PRESENT | SubscriptionManagerEngine.kt | Make recordPriceChange transactional |
| 5 | REC-10 | Detection misses annual/semiannual patterns | MAJOR | STILL PRESENT | RecurringExpenseEngine.kt | Use frequency-specific windows |
| 6 | REC-12 | Detection uses first currency in group | MAJOR | STILL PRESENT | RecurringExpenseEngine.kt | Group by merchant+currency |
| 7 | REC-14 | Manual recurring expenses lack categoryId | MAJOR | STILL PRESENT | ManualRecurringExpense.kt | Add nullable categoryId |
| 8 | REC-15 | getByMerchant() exact-match collisions | MAJOR | STILL PRESENT | ManualRecurringExpenseDao.kt | Use MerchantKeyGenerator |
| 9 | REC-18 | Recommendations hardcode EUR | MAJOR | STILL PRESENT | SubscriptionManagerEngine.kt | Use MoneyFormatter |
| 10 | REC-19 | Recommendation savings double-count | MAJOR | STILL PRESENT | SubscriptionManagerEngine.kt | Take max per subscription |
| 11 | REC-21 | Domain PlannedExpense drops currency field | MAJOR | STILL PRESENT | domain/model/PlannedExpense.kt | Add currency to domain model |
| 12 | REC-22 | Legacy markBillPaid() no updatedAt | MAJOR | STILL PRESENT | BillReminderManager.kt | Add updatedAt+lifecycle event |
| 13 | REC-23 | recordPriceChange() doesn't update amount | MAJOR | STILL PRESENT | SubscriptionManagerEngine.kt | Add DAO update call |
| 14 | REC-1 | Legacy getNotificationsDue() still active | MAJOR | PARTIALLY | BillReminderManager.kt | Retire legacy path |
| 15 | REC-4 | Irregular items stuck forever | MAJOR | STILL PRESENT | RecurrenceCalculator.kt, BillReminderManager.kt | Require user input |
| 16 | REC-8 | First price change no visible PriceChange | MAJOR | STILL PRESENT | SubscriptionManagerEngine.kt | Insert baseline history row |
| 17 | REC-20 | Effective amount for stability (design choice) | MEDIUM | STILL PRESENT | RecurringExpenseEngine.kt | Document design choice |
| 18 | REC-24 | Duplicate notification risk both paths | MINOR | STILL PRESENT | BillReminderManager.kt, RecurringLifecycleCoordinator.kt | Remove legacy path |
| 19 | REC-25 | isRecurring not set correctly for occurrence-linked | MINOR | STILL PRESENT | ForecastInputAssembler.kt | Consider sourceRecurringRuleId |

### 4. Currency & Exchange

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | CURR-1 | Expense baseAmount fields schema-only never populated | CRITICAL | PARTIALLY | Expense.kt, CurrencyConverter.kt | Populate at creation time |
| 2 | CURR-2 | Rate unique constraint prevents historical rows | CRITICAL | STILL PRESENT | ExchangeRate.kt, ExchangeRateContracts.kt | Change to pair+date unique |
| 3 | CURR-4 | convert() no date/context parameter | CRITICAL | STILL PRESENT | CurrencyConverter.kt | Add convertAsOf() |
| 4 | CURR-6 | Home currency change no re-normalization | CRITICAL | STILL PRESENT | CurrencySettingsRepositoryImpl.kt | Separate display vs accounting currency |
| 5 | CURR-8 | setLastRateUpdate even with zero rates | MAJOR | STILL PRESENT | CurrencyRatesRepositoryImpl.kt | Guard with rates.isNotEmpty() |
| 6 | CURR-9 | lastRateUpdate DataStore vs rates Room drift | MAJOR | STILL PRESENT | CurrencySettingsRepositoryImpl.kt | Use Room as source of truth |
| 7 | CURR-3 | getTotalSpentFlow() (line 256) currency-unsafe | MAJOR | PARTIALLY | ExpenseDao.kt | Deprecate + replace |
| 8 | CURR-5 | PlannedExpense normalized snapshot fields missing | MAJOR | PARTIALLY | PlannedExpense.kt | Add baseAmount/baseCurrency |
| 9 | CURR-7 | CurrencyCode not pushed to boundary layers | MAJOR | PARTIALLY | CurrencyCode.kt, CurrencySettingsRepositoryImpl.kt | Adopt CurrencyCode at boundaries |
| 10 | CURR-14 | DomainExchangeRate doesn't include validDate | MAJOR | STILL PRESENT | ExchangeRateContracts.kt, ExchangeRateStoreAdapter.kt | Add validDate to domain |
| 11 | CURR-15 | CurrencyConverter uses raw String params | MAJOR | STILL PRESENT | CurrencyConverter.kt | Accept CurrencyCode params |
| 12 | CURR-18 | getTotalSpentFlow() not deprecated | MAJOR | STILL PRESENT | ExpenseDao.kt | Deprecate + replace |
| 13 | CURR-10 | getAllRatesForBase() naming confusing | MEDIUM | STILL PRESENT | ExchangeRateDao.kt, ExchangeRateContracts.kt | Rename to getRatesToCurrency |
| 14 | CURR-11 | Money uses Double internally | MEDIUM | PARTIALLY | MoneyAmount.kt | Migrate to Long minor units |
| 15 | CURR-12 | formatAmount() not deprecated | MEDIUM | PARTIALLY | CurrencyFormatter.kt, MoneyAmount.kt | Deprecate old, fix delegation |
| 16 | CURR-17 | Unchecked cast in aggregateCurrencyTotalsToMoneyAggregate | MEDIUM | STILL PRESENT | MultiCurrencyRepository.kt | Add else?Timber.w branch |
| 17 | CURR-13 | HRK still in active list, no legacy marking | MINOR | STILL PRESENT | CurrencyConverter.kt, CurrencyCode.kt | Add isActive metadata |
| 18 | CURR-16 | ECB refresh computes N�N pairs (~380 rows) | MINOR | STILL PRESENT | CurrencyRatesRepositoryImpl.kt | Consider lazy triangulation |
| 19 | CURR-19 | CurrencyFormatter hardcodes 2 decimal places | MINOR | STILL PRESENT | CurrencyFormatter.kt | Use Currency.getDefaultFractionDigits |


### 5. Settings & Privacy

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | PRV-N1 | Photon/Geoapify/GooglePlaces bypass privacy gate | CRITICAL | STILL PRESENT | PhotonGeocodingService.kt, GeoapifyGeocodingService.kt, GooglePlacesGeocodingService.kt | Add privacyGate.check() |
| 2 | PRV-2 | Finance-app notifications captured unconditionally | CRITICAL | STILL PRESENT | NotificationFilter.kt | Add deny-keyword list |
| 3 | PRV-1 | BootReceiver/ServiceRestartReceiver start unconditionally | MAJOR | PARTIALLY | BootReceiver.kt, ServiceRestartReceiver.kt | Add privacy gate checks |
| 4 | PRV-3 | Notification posting vs reading permission confusion | MAJOR | STILL PRESENT | MainActivity.kt, NotificationPermissionDialog.kt | Add listener-permission flow |
| 5 | PRV-9 | Background workers not synced on setting changes | MAJOR | STILL PRESENT | AiSettingsViewModel.kt | Create per-feature sync use cases |
| 6 | PRV-10 | location FGS type on notification service | MAJOR | STILL PRESENT | AndroidManifest.xml, NotificationCaptureService.kt | Remove location FGS type |
| 7 | PRV-11 | POST_NOTIFICATIONS on first launch not JIT | MAJOR | STILL PRESENT | MainActivity.kt | Make JIT |
| 8 | PRV-14 | DataStore corruption fails open (enables AI silently) | MAJOR | STILL PRESENT | AiSettingsRepositoryImpl.kt | Fail closed + warn user |
| 9 | PRV-16 | Deep links exported through custom scheme without auth | MAJOR | STILL PRESENT | AndroidManifest.xml | Add auth confirmation |
| 10 | PRV-5 | AI settings allow contradictory states | MEDIUM | PARTIALLY | AiSettingsViewModel.kt, AiSettingsScreen.kt | Add cross-field UI guard |
| 11 | PRV-6 | Disabling cloud AI doesn't handle stored API keys | MEDIUM | PARTIALLY | AiSettingsViewModel.kt | Add confirmation+key status |
| 12 | PRV-15 | Conversation history toggle lacks purge semantics | MEDIUM | PARTIALLY | AiSettingsScreen.kt | Add purge actions to settings |
| 13 | PRV-N2 | saveApiKey() blank-input deletes key without confirmation | MINOR | STILL PRESENT | AiSettingsViewModel.kt | Add confirmation dialog |

### 6. Backup / Restore / Export

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | BAK-10 | Reset database no typed confirmation or debug guard | CRITICAL | STILL PRESENT | DatabaseBackupRepositoryImpl.kt | Add confirmation+restart |
| 2 | BAK-12 | Export UI loads all expenses into memory | MAJOR | STILL PRESENT | DeterministicExpenseExportPager.kt | Stream write incrementally |
| 3 | BAK-13 | Export no snapshot consistency | MAJOR | STILL PRESENT | AccountingExportRepository.kt | Use stable ID snapshot |
| 4 | BAK-14 | JSON export silently converts invalid numbers to 0.0 | MAJOR | STILL PRESENT | AccountingExportRepository.kt | Fail on non-finite values |
| 5 | BAK-15 | Date range validation weak | MAJOR | STILL PRESENT | ExportOptionsViewModel.kt | Add date range guards |
| 6 | BAK-N1 | Legacy importDatabase() no maintenance mode/journal | MAJOR | STILL PRESENT | DatabaseBackupRepositoryImpl.kt, DebugViewModel.kt | Route through restoreCostBackup |
| 7 | BAK-NB | DebugViewModel fragile transactionCount==-1 heuristic | MAJOR | STILL PRESENT | DebugViewModel.kt | Use proper restart detection |
| 8 | BAK-NF | Legacy import verification only 5 tables | MAJOR | STILL PRESENT | DatabaseBackupRepositoryImpl.kt | Populate allTableCounts |
| 9 | BAK-1 | Legacy exportDatabase() deprecated but accessible | MAJOR | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Hide behind debug mode |
| 10 | BAK-5 | Legacy import no journal (delete-then-copy) | MAJOR | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Backport journal |
| 11 | BAK-6 | Legacy import no maintenance mode | MAJOR | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Add maintenance mode |
| 12 | BAK-7 | Legacy import returns plain Success | MAJOR | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Return SuccessNeedsRestart |
| 13 | BAK-8 | Legacy/safety backup filenames collide | MEDIUM | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Add UUID suffix |
| 14 | BAK-9 | Legacy hasMeaningfulData() only 5 old fields | MEDIUM | PARTIALLY | DatabaseBackupRepositoryImpl.kt | Extend field coverage |
| 15 | BAK-11 | CSV/JSON export not labeled non-restorable | MINOR | PARTIALLY | ExportOptionsScreen.kt | Add warning label |
| 16 | BAK-16 | Temp export file not deleted on clearExport() | MINOR | PARTIALLY | ExportOptionsViewModel.kt | Add file cleanup |
| 17 | BAK-NC | BackupEncryptionService reads entire ZIP into memory | MINOR | STILL PRESENT | BackupEncryptionService.kt | Use CipherOutputStream |
| 18 | BAK-ND | RestoreJournal writes state then immediately deletes | MINOR | STILL PRESENT | RestoreJournal.kt | Skip terminal-state write |
| 19 | BAK-NE | RestoreMaintenanceMode.exit() no reschedule | MINOR | STILL PRESENT | RestoreMaintenanceMode.kt | Reschedule on exit(NORMAL) |

### 7. Dashboard & Totals

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | DSH-2 | Drill-down boundaries use MIN/MAX date | CRITICAL | STILL PRESENT | TotalsAggregationEngine.kt, ExpenseDao.kt | Use canonical calendar boundaries |
| 2 | DSH-3 | Weekly drill-down shows days outside month | CRITICAL | STILL PRESENT | TotalsAggregationEngine.kt, HomeViewModel.kt | Clip week to month boundary |
| 3 | DSH-4 | Previous-period comparison uses ms duration | MAJOR | STILL PRESENT | AnalyticsRepository.kt, DashboardContractsAdapter.kt | Use calendar-aware navigation |
| 4 | DSH-6 | Safe-to-spend falls back to monthSpent when no budget | MAJOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Show "Set budget" CTA |
| 5 | DSH-8 | dropLast(1) excludes by position not period key | MAJOR | STILL PRESENT | TotalsAggregationEngine.kt | Filter by period key |
| 6 | DSH-9 | Category breakdown drops uncategorized expenses | MAJOR | STILL PRESENT | TotalsAggregationEngine.kt, AnalyticsRepository.kt | Map to "Uncategorized" bucket |
| 7 | DSH-N1 | computeSpendingTrend() skips empty months | MAJOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Emit zero-filled series |
| 8 | DSH-N2 | computeSpendingTrend() doubles data | MAJOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Fix data scope |
| 9 | DSH-1 | Expense stream current-month only feeds 4+ widgets | MAJOR | PARTIALLY | DashboardContractsAdapter.kt | Split into explicit feeds |
| 10 | DSH-5 | Dashboard fixed but drill-down totals raw-sum | MAJOR | PARTIALLY | TotalsAggregationEngine.kt | Route through MultiCurrencyRepository |
| 11 | DSH-7 | Zero-spend periods excluded from averages | MEDIUM | STILL PRESENT | TotalsAggregationEngine.kt | Generate full calendar buckets |
| 12 | DSH-10 | One-shot analytics flows (mitigated) | MEDIUM | STILL PRESENT | AnalyticsRepository.kt | Use DAO reactive flows |
| 13 | DSH-N4 | PersonalBest bounded by oldest purchase day | MEDIUM | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Use all-time purchase days |
| 14 | DSH-REM3 | DAO agg queries still compute MIN/MAX date | MINOR | STILL PRESENT | ExpenseDao.kt | Remove startDate/endDate from agg |
| 15 | DSH-N3 | CategorySpending.currency default EUR | MINOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Pull from home currency |
| 16 | DSH-REM18 | CategorySpending.moneyTotal confusing naming | MINOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Rename/clarify |
| 17 | DSH-REM19 | PeriodSummary monthSpend duplicates totalSpend | MINOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Remove redundancy |
| 18 | DSH-REM20 | MonthlyComparisonCalculator hardcodes displayCurrency=EUR | MINOR | STILL PRESENT | MonthlyComparisonCalculator.kt | Make configurable |


### 8. AI / ML / Intelligence

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | AIML-6 | Anomaly detection compares within current month only | CRITICAL | STILL PRESENT | AnomalyDetector.kt | Add historical baselines |
| 2 | AIML-7 | Anomaly detector does not suppress known recurring bills | CRITICAL | STILL PRESENT | AnomalyDetector.kt, InsightsEngine.kt | Cross-reference recurring |
| 3 | AIML-25 | Runway not based on account balance (goal-funded) | CRITICAL | STILL PRESENT | FinancialHealthScoreV2.kt | Integrate real balances |
| 4 | AIML-29 | Smart savings ignores upcoming committed bills | CRITICAL | STILL PRESENT | SmartSavingsEngine.kt | Inject RecurringLifecycleCoordinator |
| 5 | AIML-11 | Source trust inflated by duplicates | MAJOR | STILL PRESENT | SourceStats.kt | Exclude duplicates from trust |
| 6 | AIML-12 | Source stats mutable counters, not event-derived | MAJOR | STILL PRESENT | SourceStatsDao.kt | Add event-ledger |
| 7 | AIML-14 | Merchant rejection keys use raw string | MAJOR | STILL PRESENT | ConfidenceRouter.kt, UserCorrectionDao.kt | Integrate MerchantNormalizer |
| 8 | AIML-15 | Model persistence not durable on background | MAJOR | STILL PRESENT | TransactionClassifier.kt | Flush before cancel |
| 9 | AIML-16 | ML model files leak sensitive vocabulary | MAJOR | STILL PRESENT | TransactionClassifier.kt, ExpenseCategoryClassifier.kt | Encrypt model files |
| 10 | AIML-17 | Category classifier returns stale/deleted IDs | MAJOR | STILL PRESENT | HybridExpenseClassifier.kt | Validate against active categories |
| 11 | AIML-18 | Category ML only trains on merchant tokens | MAJOR | STILL PRESENT | ExpenseCategoryClassifier.kt | Use all ExpenseFeatures |
| 12 | AIML-19 | Hybrid classifier uses current time not event timestamp | MAJOR | STILL PRESENT | HybridExpenseClassifier.kt | Pass explicit eventTime |
| 13 | AIML-20 | Category learning globally changes from single correction | MAJOR | STILL PRESENT | HybridExpenseClassifier.kt | Add confidence-based learning |
| 14 | AIML-26 | Bill reliability is pattern proxy, not actual payment data | MAJOR | STILL PRESENT | FinancialHealthScoreV2.kt | Use occurrence lifecycle |
| 15 | AIML-27 | Budget adherence double-counts hierarchy | MAJOR | STILL PRESENT | FinancialHealthScoreV2.kt | Normalize hierarchical budgets |
| 16 | AIML-30 | Smart savings uses hardcoded currencyless caps | MAJOR | STILL PRESENT | SmartSavingsEngine.kt | Use SpendingThresholdCalculator |
| 17 | AIML-31 | Smart savings treats uncategorized as discretionary | MAJOR | STILL PRESENT | SmartSavingsEngine.kt | Treat uncategorized as unknown |
| 18 | AIML-32 | Lifestyle inflation uses English merchant keywords | MAJOR | STILL PRESENT | LifestyleInflationDetector.kt | Add category-based detection |
| 19 | AIML-3 | InsightsEngine always uses current calendar month | MAJOR | STILL PRESENT | InsightsEngine.kt | Accept periodRange parameter |
| 20 | AIML-4 | Previous-period comparison uses ms duration | MAJOR | PARTIALLY | AnalyticsRepository.kt, AnalyticsViewModel.kt | Calendar-aware comparison |
| 21 | AIML-5 | Missing months cause size mismatch in correlation | MAJOR | PARTIALLY | LifestyleInflationDetector.kt | Align by sorted composite key |
| 22 | AIML-8 | Anomaly method priority uses ordinal | MAJOR | STILL PRESENT | AnomalyDetector.kt | Add explicit priority field |
| 23 | AIML-9 | Uncategorized spend dropped in repository path | MAJOR | PARTIALLY | AnalyticsRepository.kt, AnalyticsViewModel.kt | Fix repository path |
| 24 | AIML-10 | Suspect detection uses raw merchant no currency | MAJOR | PARTIALLY | AnalyticsViewModel.kt | Use DuplicateDetectionPolicy |
| 25 | AIML-13 | ConfidenceRouter cache stale after reject/approve | MAJOR | STILL PRESENT | ConfidenceRouter.kt | Add event-driven invalidation |
| 26 | AIML-21 | Recommendation dedupe includes raw timestamps | MAJOR | STILL PRESENT | RecommendationDeduplicator.kt | Use semantic signatures |
| 27 | AIML-22 | Recommendation Flow captures stale nowMillis | MAJOR | PARTIALLY | RecommendationDao.kt, RecommendationRepository.kt | Add periodic expiration |
| 28 | AIML-23 | Recommendation persistence uses REPLACE | MAJOR | PARTIALLY | RecommendationDao.kt | Remove REPLACE from raw DAO |
| 29 | AIML-24 | Dashboard follow-through uses gross amount + EUR | MAJOR | PARTIALLY | DashboardFollowThroughEngine.kt | Use effectiveAmount + CurrencyFormatter |
| 30 | AIML-34 | Analytics ViewModel heavy work on UI thread | MEDIUM | PARTIALLY | AnalyticsViewModel.kt | Continue optimization |
| 31 | AIML-35 | Analytics cache invalidation clears all caches | MEDIUM | PARTIALLY | AnalyticsViewModel.kt | Targeted invalidation |
| 32 | AIML-36 | AnomalyDetector uses Calendar.getInstance() | MINOR | STILL PRESENT | AnomalyDetector.kt | Inject TimeProvider |

### 9. Budgets & Categories

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | BUD-5 | Critical budgets counted as healthy | CRITICAL | STILL PRESENT | CalculateBudgetStatusUseCase.kt | Include CRITICAL in counts |
| 2 | BUD-7 | Category deletion converts budgets to overall (FK SET NULL) | CRITICAL | STILL PRESENT | Budget.kt | Change to RESTRICT or add budgetScope |
| 3 | BUD-19 | Autopilot ignores budget period (weekly gets monthly) | CRITICAL | STILL PRESENT | BudgetAutopilotEngine.kt | Normalize to budget period |
| 4 | BUD-11 | Rollover N-per-period queries (1000+ for daily) | MAJOR | STILL PRESENT | BudgetRepository.kt | Implement BudgetPeriodLedger |
| 5 | BUD-13 | Budget monitor treats undelivered notifications as delivered | MAJOR | STILL PRESENT | BudgetMonitor.kt | Return DeliveryResult |
| 6 | BUD-20 | Autopilot no hierarchy control (overall+category) | MAJOR | STILL PRESENT | BudgetAutopilotEngine.kt | Add hierarchical reconciliation |
| 7 | BUD-21 | Autopilot apply-all not transactional | MAJOR | STILL PRESENT | BudgetViewModel.kt | Wrap in transaction |
| 8 | BUD-22 | Autopilot apply uses stale budget snapshot | MEDIUM | STILL PRESENT | BudgetViewModel.kt | Refresh inside loop |
| 9 | BUD-23 | BudgetForecastingEngine accuracy incomplete (placeholder) | MAJOR | STILL PRESENT | BudgetForecastingEngine.kt | Implement accuracy computation |
| 10 | BUD-25 | Budget forecast uniqueness app-layer only | MAJOR | STILL PRESENT | BudgetForecastDao.kt | Add materialized key |
| 11 | BUD-28 | Category names not unique | MAJOR | STILL PRESENT | Category.kt, CategoryDao.kt | Add unique index on name |
| 12 | BUD-30 | Default categories not protected at DAO level | MAJOR | STILL PRESENT | CategoryDao.kt | Add isDefault guard |
| 13 | BUD-32 | Merchant-category learning globally overwrites | MAJOR | STILL PRESENT | ExpenseRepository.kt | Add confidence-based learning |
| 14 | BUD-33 | Bulk category update not transactional | MAJOR | STILL PRESENT | ExpenseRepository.kt | Add database.withTransaction |
| 15 | BUD-37 | Merchant-category mappings lack source/audit fields | MAJOR | STILL PRESENT | MerchantCategory.kt | Add source/createdAt/updatedAt |
| 16 | BUD-4 | Alert and card disagree on shared expenses | MAJOR | PARTIALLY | BudgetMonitor.kt, BudgetCard.kt | Align on adjusted spend |
| 17 | BUD-6 | Summary card uses raw health, cards use adjusted | MAJOR | PARTIALLY | BudgetSummaryCard.kt | Align health computations |
| 18 | BUD-9 | Budget validation split UI vs repository | MAJOR | PARTIALLY | BudgetRepository.kt, BudgetViewModel.kt | Centralize BudgetDraftValidator |
| 19 | BUD-10 | Invalid periodMode silently becomes calendar mode | MAJOR | STILL PRESENT | BudgetCalculator.kt | Use enum for periodMode |
| 20 | BUD-12 | Rollover only carries surplus not deficits | MEDIUM | STILL PRESENT | BudgetRepository.kt | Add policy selection |
| 21 | BUD-15 | Budget alert IDs overflow (toInt()) | MEDIUM | STILL PRESENT | BudgetMonitor.kt | Use stable ID mapping |
| 22 | BUD-16 | Budget status cache 30s stale alerts | MEDIUM | STILL PRESENT | BudgetRepository.kt | Add change-driven invalidation |
| 23 | BUD-17 | Budget suggestions raw-sum currencies | MAJOR | PARTIALLY | BudgetRepository.kt | Use MultiCurrencyRepository |
| 24 | BUD-27 | BudgetRecommendationEngine hardcodes EUR | MEDIUM | PARTIALLY | BudgetRecommendationEngine.kt | Use CurrencyFormatter |
| 25 | BUD-29 | getByName() exact/case-sensitive | MEDIUM | STILL PRESENT | CategoryDao.kt | Use COLLATE NOCASE |
| 26 | BUD-31 | Delete category cascades merchant mappings | MEDIUM | STILL PRESENT | MerchantCategory.kt | Change to SET NULL |
| 27 | BUD-34 | Category update doesn't call learnFromCorrection() | MEDIUM | STILL PRESENT | ExpenseRepository.kt | Wire classifier training |
| 28 | BUD-35 | Cannot clear category via repo overload | MEDIUM | STILL PRESENT | ExpenseRepository.kt | Allow null categoryId |
| 29 | BUD-36 | Merchant canonical lookup nondeterministic | MEDIUM | STILL PRESENT | MerchantCategoryDao.kt | Add unique constraint |


### 10. Warranty / Returns

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | WRN-1 | Receipt match doesn't propagate to warranty expenseId | MAJOR | STILL PRESENT | ReceiptLinkService.kt | Create ReceiptAssetCoordinator |
| 2 | WRN-2 | Confirm warranty doesn't set status=ACTIVE | MAJOR | STILL PRESENT | WarrantyTrackerViewModel.kt | Call promoteReviewDraft |
| 3 | WRN-3 | Reject warranty leaves stale return window | MAJOR | STILL PRESENT | WarrantyTrackerViewModel.kt | Delete/dismiss return window |
| 4 | WRN-4 | Manual warranties create fake placeholder receipts | MAJOR | STILL PRESENT | WarrantyTrackerViewModel.kt | Make receiptId nullable |
| 5 | WRN-5 | Delete receipt cascades warranties+return windows | MAJOR | STILL PRESENT | Warranty.kt, ReturnWindow.kt | SET NULL or soft-delete |
| 6 | WRN-6 | One warranty per receipt too restrictive | MAJOR | STILL PRESENT | Warranty.kt | Allow multiple per receipt |
| 7 | WRN-8 | Protected value raw-sums gross amount (not effective, no currency) | MAJOR | STILL PRESENT | WarrantyDao.kt | Use effectiveAmount+currency |
| 8 | WRN-9 | Warranty end-date expires too early (excludes last day) | MAJOR | STILL PRESENT | WarrantyTextExtractor.kt | Include last day |
| 9 | WRN-10 | Worker reminder text inaccurate (hardcoded 7d for ranges) | MAJOR | STILL PRESENT | WarrantyExpirationWorker.kt | Use actual days remaining |
| 10 | WRN-11 | Warranty reminders repeat every day (no persisted state) | MAJOR | STILL PRESENT | WarrantyExpirationWorker.kt | Add WarrantyReminderState entity |
| 11 | WRN-12 | markWarrantyAsClaimed() no claimedAt | MAJOR | STILL PRESENT | WarrantyTrackerRepository.kt | Add claimedAt to DAO |
| 12 | WRN-13 | Marked returned not linked to refund expense | MAJOR | STILL PRESENT | ReturnWindow.kt, ReturnWindowDao.kt | Add refund expense link |
| 13 | WRN-14 | Return refund amount no currency field | MAJOR | STILL PRESENT | ReturnWindow.kt | Add refundCurrency |
| 14 | WRN-15 | Cloud extraction no on-device fallback | MAJOR | STILL PRESENT | WarrantyTrackerRepository.kt | Use hybrid router |
| 15 | WRN-16 | Cloud extraction ignores confidence thresholds | MAJOR | STILL PRESENT | CloudWarrantyExtractionService.kt | Apply confidence threshold |
| 16 | WRN-17 | Confidence scales inconsistent (local 0..100, cloud 0..1) | MAJOR | STILL PRESENT | WarrantyTextExtractor.kt, WarrantyExtractionResult.kt | Normalize to 0..1 |
| 17 | WRN-18 | Low-confidence drafts use fake defaults | MAJOR | STILL PRESENT | AutoCreateWarrantyFromReceiptUseCase.kt | Improve fallback handling |
| 18 | WRN-19 | Review UI cannot edit warranty fields | MAJOR | STILL PRESENT | WarrantyTrackerScreen.kt | Add edit form |
| 19 | WRN-20 | Manual warranty path not transactional | MAJOR | STILL PRESENT | WarrantyTrackerViewModel.kt | Wrap in transaction |
| 20 | WRN-21 | Price protection uses scan date not purchase date | MAJOR | STILL PRESENT | PriceProtectionTracker.kt | Use expense purchase date |
| 21 | WRN-22 | Price-protection window ignores merchant return window | MAJOR | STILL PRESENT | PriceProtectionTracker.kt | Use merchant return window |
| 22 | WRN-23 | Simulated deals shown as real (UI ignores isSimulated) | MAJOR | STILL PRESENT | PriceProtectionScreen.kt | Display isSimulated flag |
| 23 | WRN-24 | Excluded tracking keys not persisted | MAJOR | STILL PRESENT | PriceProtectionViewModel.kt | Persist excluded keys |
| 24 | WRN-25 | No stable price-protection item identity | MAJOR | STILL PRESENT | PriceProtectionTracker.kt | Add stable fingerprint |
| 25 | WRN-26 | Price protection not currency-safe | MAJOR | STILL PRESENT | PriceProtectedItem.kt, PriceDropAlert.kt | Add currency |
| 26 | WRN-28 | Negotiation hardcoded market rates (no metadata) | MAJOR | STILL PRESENT | SmartBillNegotiationEngine.kt | Add rate metadata+staleness |
| 27 | WRN-29 | Negotiation ignores billing frequency | MAJOR | STILL PRESENT | SmartBillNegotiationEngine.kt | Normalize to monthly |
| 28 | WRN-30 | Negotiation currency-hardcoded to euros | MAJOR | STILL PRESENT | SmartBillNegotiationEngine.kt | Use MoneyFormatter |
| 29 | WRN-N1 | ReceiptLinkService.unlink doesn't clean warranty/return | MAJOR | STILL PRESENT | ReceiptLinkService.kt | Extend unlink to warranties |
| 30 | WRN-N2 | Dual receipt-linking paths split-brain | MAJOR | STILL PRESENT | ReceiptRepository.kt, ReceiptLinkService.kt | Deprecate legacy linking |
| 31 | WRN-7 | Return-window uniqueness inconsistent | MEDIUM | STILL PRESENT | ReturnWindow.kt | Align schema with DAO |
| 32 | WRN-27 | Credit-card benefits not tied to actual cards | MEDIUM | STILL PRESENT | PriceProtectionTracker.kt | Use actual payment methods |
| 33 | WRN-31 | Service-type detection misclassifies (order-dependent) | MEDIUM | STILL PRESENT | SmartBillNegotiationEngine.kt | Fix detection priority |
| 34 | WRN-32 | Customer value based on history count not tenure | MEDIUM | STILL PRESENT | SmartBillNegotiationEngine.kt | Use time-based tenure |
| 35 | WRN-N3 | Manual placeholder receipt wrong documentType | MINOR | STILL PRESENT | WarrantyTrackerRepository.kt | Set MANUAL_PLACEHOLDER |
| 36 | WRN-N4 | createWarrantyForReview exists but unused by VM | MINOR | STILL PRESENT | WarrantyTrackerViewModel.kt | Wire to use case method |

### 11. Location Enrichment

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | LOC-9 | Location analytics raw-sum currencies | CRITICAL | STILL PRESENT | SpendingHeatmapEngine, LocationInsightsEngine | Route through MultiCurrencyRepository |
| 2 | LOC-10 | Background geocoding Greece-biased | CRITICAL | STILL PRESENT | NominatimGeocodingService.kt | Add configurable home country |
| 3 | LOC-3 | Overpass auto-accepted without recency/distance/name checks | MAJOR | STILL PRESENT | LocationResolver.kt | Add validation gates |
| 4 | LOC-6 | Partial coordinate rows invisible (lat!=null,lon=null) | MAJOR | STILL PRESENT | ExpenseDao.kt | Fix to OR condition |
| 5 | LOC-8 | Marker uses gross amount not effective | MAJOR | STILL PRESENT | SpendingMapViewModel.kt | Use effectiveAmount |
| 6 | LOC-11 | Nominatim retry violates rate policy | MAJOR | STILL PRESENT | NominatimGeocodingService.kt | Rate-limit retries individually |
| 7 | LOC-16 | Location write API accepts invalid coords | MAJOR | STILL PRESENT | SpendingMapViewModel.kt, ExpenseDao.kt | Add LocationDraftValidator |
| 8 | LOC-17 | onPoiSelected uses SOURCE_OVERPASS_POI for user selection | MAJOR | STILL PRESENT | SpendingMapViewModel.kt | Add USER_CONFIRMED_POI source |
| 9 | LOC-2 | Overpass uses device location for old transactions | MAJOR | PARTIALLY | LocationResolver.kt | Add isRecent gate |
| 10 | LOC-4 | POI selection saves globally (no area scoping) | MAJOR | PARTIALLY | SpendingMapViewModel.kt | Area-scope POI selection |
| 11 | LOC-7 | Place insights include non-spending types | MAJOR | PARTIALLY | LocationInsightsEngine.kt | Add transaction-type filter |
| 12 | LOC-12 | Backfill retry misses Retryable/exceptions | MAJOR | PARTIALLY | LocationBackfillWorker.kt | Increment all outcomes |
| 13 | LOC-13 | Area spending merges unrelated same-name areas | MEDIUM | STILL PRESENT | AreaSpendingEngine.kt | Add coarse-geo qualifier |
| 14 | LOC-14 | Travel detection uses truncating toLong() for negative | MEDIUM | STILL PRESENT | TravelDetectionEngine.kt | Use floor-based bucketing |
| 15 | LOC-15 | Travel home inference purely frequency-based | MEDIUM | STILL PRESENT | TravelDetectionEngine.kt | Add robustness measures |

### 12. Search / Reports

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | SRH-1 | Legacy merchant extraction broken (lowercase+[A-Z] regex) | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Fix regex or retire |
| 2 | SRH-2 | Legacy search extracts filters never applies | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Apply extracted filters |
| 3 | SRH-3 | Legacy amount uses gross, exact==, no currency | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Use effectiveAmount+range |
| 4 | SRH-7 | Amount filters not currency-aware | MAJOR | STILL PRESENT | ExpenseRepository.kt, ExpenseQueryFilters.kt | Add currency to filters |
| 5 | SRH-8 | Multi-filter drilldown broader than answer | MAJOR | STILL PRESENT | MapFinancialQueryToNavigationUseCase.kt | Support multi-value nav |
| 6 | SRH-10 | "This week" semantics inconsistent | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Unify semantics |
| 7 | SRH-11 | Previous-period raw ms duration | MAJOR | STILL PRESENT | ExecuteFinancialQueryUseCase.kt | Calendar-aware ranges |
| 8 | SRH-12 | AI query output validation too weak | MAJOR | STILL PRESENT | OnDeviceQueryInterpretationService.kt | Add validation guards |
| 9 | SRH-13 | Uncategorized spend excluded from breakdown | MAJOR | STILL PRESENT | ExecuteFinancialQueryUseCase.kt | Include uncategorized bucket |
| 10 | SRH-14 | Merchant filtering only merchantKey (no name fallback) | MAJOR | STILL PRESENT | ExpenseRepository.kt | Add merchant name fallback |
| 11 | SRH-17 | Ambiguous DD/MM vs MM/DD parsing | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Locale-aware parsing |
| 12 | SRH-19 | Legacy search defaults to 0?now (full history) | MAJOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Add date range bounds |
| 13 | SRH-20 | Hybrid query no runtime fallback | MAJOR | STILL PRESENT | HybridQueryInterpretationService.kt | Add cascading fallback |
| 14 | SRH-21 | No UI notice about cloud merchant exposure | MAJOR | STILL PRESENT | CloudQueryInterpretationService.kt | Add privacy notice |
| 15 | SRH-22 | Query model lacks currency/source/status filters | MAJOR | STILL PRESENT | ExpenseQueryFilters.kt | Extend filter model |
| 16 | SRH-23 | Results not labeled exact vs partial | MAJOR | STILL PRESENT | FinancialQueryResult.kt | Add metadata |
| 17 | SRH-24 | Export paging not atomic (offset-based) | MAJOR | STILL PRESENT | DeterministicExpenseExportPager.kt | ID-based snapshot |
| 18 | SRH-25 | PDF includes non-expense types | MAJOR | STILL PRESENT | ExpenseDao.kt | Add transactionType filter |
| 19 | SRH-26 | PDF period shows exclusive end | MAJOR | STILL PRESENT | AccountantReportPdfExporter.kt | Fix period display |
| 20 | SRH-29 | Exported files in cache, no encryption | MAJOR | STILL PRESENT | AccountingExportRepository.kt | Add encryption/redaction |
| 21 | SRH-N1 | AI prompt schema lacks minAmount/maxAmount fields | MAJOR | STILL PRESENT | OnDeviceQueryInterpretationService.kt | Add amount fields to schema |
| 22 | SRH-5 | Broad queries load full results for totals/avgs | MEDIUM | PARTIALLY | ExecuteFinancialQueryUseCase.kt | SQL aggregates for all types |
| 23 | SRH-6 | executeLargest raw effectiveAmount | MEDIUM | PARTIALLY | ExecuteFinancialQueryUseCase.kt | Add currency conversion |
| 24 | SRH-9 | Clarification for null period never reached | MEDIUM | PARTIALLY | ExecuteFinancialQueryUseCase.kt, InterpretFinancialQueryUseCase.kt | Fix interpretation default |
| 25 | SRH-18 | Invalid parse caught but generic error | MEDIUM | PARTIALLY | NaturalLanguageSearchViewModel.kt | User-friendly clarification |
| 26 | SRH-27 | Large transaction threshold flat 500 (�500 != �500) | MEDIUM | PARTIALLY | AccountantReportPdfExporter.kt | Threshold currency-aware |
| 27 | SRH-N2 | Dead code date pattern always null | MINOR | STILL PRESENT | NaturalLanguageSearchEngine.kt | Remove or fix |
| 28 | SRH-N3 | Tight coupling (CloudQuery instantiate OnDevice) | MINOR | STILL PRESENT | CloudQueryInterpretationService.kt | Inject via DI |


### 13. Shared Expenses

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | SHR-2 | addExpenseToGroup() not transactional � TOCTOU | CRITICAL | STILL PRESENT | GroupTransactionCoordinator.kt | Wrap in withTransaction |
| 2 | SHR-3 | Archived groups vanish from budget offsets | CRITICAL | STILL PRESENT | SharedExpenseBudgetOffsetEngine.kt | Include archived |
| 3 | SHR-4 | Hard delete leaves Expenses orphaned (isSharedExpense=true, no group) | CRITICAL | STILL PRESENT | GroupTransactionCoordinator.kt | Clean up Expense rows |
| 4 | SHR-7 | paidById cross-group not DB-enforced | CRITICAL | STILL PRESENT | GroupExpense.kt | Add trigger or materialized key |
| 5 | SHR-11 | Invalid custom split silently falls back to equal split | MAJOR | STILL PRESENT | SplitCalculator.kt | Surface fallback to user |
| 6 | SHR-12 | myShareAmount drifts from group split data | MAJOR | STILL PRESENT | GroupTransactionCoordinator.kt | Add recompute on split change |
| 7 | SHR-13 | Item assignment not transactional+unvalidated | MAJOR | STILL PRESENT | EnhancedSplitManager.kt | Add transaction+validation |
| 8 | SHR-14 | Split templates weakly validated | MAJOR | STILL PRESENT | EnhancedSplitManager.kt | Add template validation |
| 9 | SHR-16 | currentUserGroupKey CHECK no-op for NULL | MAJOR | STILL PRESENT | GroupMember.kt, AppDatabase.kt | Fix CHECK+set at creation |
| 10 | SHR-17 | addExpenseToGroup() accepts non-EQUAL split types without data | MAJOR | STILL PRESENT | GroupTransactionCoordinator.kt | Add customSplitsJson param |
| 11 | SHR-1 | Standalone expenses drop custom split payloads | MAJOR | PARTIALLY | GroupTransactionCoordinator.kt | Fix adapter null-path |
| 12 | SHR-5 | Existing-expense linking defaults to now() not expense date | MAJOR | STILL PRESENT | AddGroupExpenseUseCase.kt | Default to expense date |
| 13 | SHR-6 | At-least-one current user not enforced | MAJOR | STILL PRESENT | GroupTransactionCoordinator.kt, SharedExpenseManager.kt | Add validation |
| 14 | SHR-10 | Custom split requires all members (no subset) | MAJOR | STILL PRESENT | CustomSplitParser.kt | Support subset splits |
| 15 | SHR-15 | Two settlement calculation paths diverge | MAJOR | STILL PRESENT | SplitCalculator.kt, SettlementCalculator.kt | Unify paths |
| 16 | SHR-18 | deleteGroup() bypasses coordinator | MINOR | STILL PRESENT | SharedExpenseDataPortAdapter.kt | Route through coordinator |
| 17 | SHR-19 | Delete semantics inconsistent (soft vs hard) | MINOR | STILL PRESENT | DeleteGroupUseCase.kt, SharedExpenseManager.kt | Consolidate semantics |

### 14. Database & Migration

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | DB-3 | paidById same-group enforcement explicitly out of scope | CRITICAL | STILL PRESENT | AppDatabase.kt, GroupExpense.kt | Add trigger or materialized key |
| 2 | DB-4 | INSERT SELECT* still in 5 critical migrations | MAJOR | PARTIALLY | AppDatabase.kt | Replace with explicit columns |
| 3 | DB-2 | Budget forecast + subscription candidate not DB-enforced | MAJOR | PARTIALLY | BudgetForecast.kt, SubscriptionCandidate.kt | Add materialized key |
| 4 | DB-5 | repairTable() all-or-nothing salvage | MAJOR | STILL PRESENT | AppDatabase.kt | Implement partial salvage |
| 5 | DB-6 | Exchange rate unique per pair not per pair+date | MAJOR | PARTIALLY | ExchangeRate.kt | Change unique index |
| 6 | DB-8 | Cascade deletes risk financial history loss | MAJOR | PARTIALLY | Multiple entities | Soft-delete or SET NULL |
| 7 | DB-1 | Fresh-vs-migrated parity gap raw_notifications indexes | MEDIUM | PARTIALLY | AppDatabase.kt | Remove stale partial indexes |
| 8 | DB-7 | String defaultValue annotations inconsistent | MINOR | PARTIALLY | Multiple entity files | Standardize on quoted form |
| 9 | DB-N1 | MIGRATION_107_108 CHECK gap in 106?107 | MINOR | STILL PRESENT | AppDatabase.kt | Document |


### 15. Background Workers

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | WRK-5 | AI briefing no WorkManager constraints | MAJOR | STILL PRESENT | AiWorkSchedulerImpl.kt | Apply WorkerSpec constraints |
| 2 | WRK-6 | AI briefing skips cached artifact delivery | MAJOR | STILL PRESENT | DailyBriefingWorker.kt, DeliverProactiveBriefingNotificationUseCase.kt | Use result sealed class |
| 3 | WRK-7 | AI briefing retries permanent exceptions | MAJOR | STILL PRESENT | DailyBriefingWorker.kt | Classify transient/permanent |
| 4 | WRK-8 | Startup sync no error containment | MAJOR | STILL PRESENT | AppStartupCoordinator.kt | Wrap in runCatching |
| 5 | WRK-9 | Startup schedules all workers unconditionally | MAJOR | PARTIALLY | AppStartupCoordinator.kt | Create feature-aware sync |
| 6 | WRK-11 | Merchant-key backfill no per-run budget | MAJOR | STILL PRESENT | MerchantKeyBackfillWorker.kt | Add maxBatches/maxDuration |
| 7 | WRK-12 | No central background job audit table | MAJOR | STILL PRESENT | (new file) | Add BackgroundJobRun entity |
| 8 | WRK-15 | AI briefing not calendar-day aligned | MAJOR | STILL PRESENT | AiWorkSchedulerImpl.kt | One-time + reschedule |
| 9 | WRK-16 | Warranty worker mixes reconciliation+notifications | MAJOR | STILL PRESENT | WarrantyExpirationWorker.kt | Split into separate workers |
| 10 | WRK-1 | KEEP freezes old worker config forever | MAJOR | PARTIALLY | All schedule() methods | Wire version + CANCEL_AND_REENQUEUE |
| 11 | WRK-2 | Warranty notifications repeat daily (no persisted state) | MAJOR | PARTIALLY | WarrantyExpirationWorker.kt | Add WarrantyReminderState |
| 12 | WRK-3 | Location backfill retries transient indefinitely | MAJOR | PARTIALLY | LocationBackfillWorker.kt | Increment all outcomes |
| 13 | WRK-10 | Merchant-key backfill retries forever on bad rows | MAJOR | PARTIALLY | MerchantKeyBackfillWorker.kt | Add persistent tracking |
| 14 | WRK-N1 | DailyBriefingWorker missing WorkerSpec gate | MEDIUM | STILL PRESENT | DailyBriefingWorker.kt | Add WorkerSpec.enabled |
| 15 | WRK-N2 | All schedule() ignore WorkerSpec.constraints | MEDIUM | STILL PRESENT | All schedule methods | Centralize WorkerSpecScheduler |
| 16 | WRK-N5 | Merchant KEEP prevents re-schedule after failure | MEDIUM | STILL PRESENT | MerchantKeyBackfillWorker.kt | Use REPLACE for one-shot |
| 17 | WRK-N6 | WorkerSpec.version entirely unused | MEDIUM | STILL PRESENT | WorkerSpec.kt | Implement version scheduling |
| 18 | WRK-13 | Lifecycle observer not idempotent | MEDIUM | STILL PRESENT | AppStartupCoordinator.kt | Add initialized guard |
| 19 | WRK-14 | Background observer swallows release errors | MEDIUM | STILL PRESENT | AppBackgroundLifecycleObserver.kt | Log in release too |
| 20 | WRK-N3 | BillReminderWorker notification ID collision | MEDIUM | STILL PRESENT | BillReminderWorker.kt | Use stable ID generator |
| 21 | WRK-N4 | BillReminderWorker marks SENT before confirming | MEDIUM | STILL PRESENT | BillReminderWorker.kt | Return delivery result |
| 22 | WRK-N7 | RestoreMode exit no reschedule | MINOR | STILL PRESENT | RestoreMaintenanceMode.kt | Reschedule on exit |
| 23 | WRK-N18 | DataRetentionWorker missing WorkerSpec gate | MINOR | STILL PRESENT | DataRetentionWorker.kt | Add WorkerSpec.enabled |
| 24 | WRK-N19 | Restore mode may skip scheduled runs silently | MINOR | STILL PRESENT | Multiple workers | Consider retry() not success |

### 16. Forecasting / Cash Flow

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | FCST-1 | Month forecast counts each pattern once (weekly �10?�10 not �40) | CRITICAL | STILL PRESENT | SynthesisEngine.kt | Use RecurringOccurrenceExpander |
| 2 | FCST-4 | Monte Carlo double-counts recurring (distribution+knownUpcoming) | CRITICAL | STILL PRESENT | MonteCarloSpendingSimulator.kt | Filter recurring from discretionary |
| 3 | FCST-8 | Forecast money raw Double no currency | CRITICAL | STILL PRESENT | FinancialForecast.kt, ForecastComponents.kt | Add currency to forecast models |
| 4 | FCST-9 | Stress forecast balance=0.0 | CRITICAL | STILL PRESENT | FinancialStressForecastEngine.kt | Integrate real balance or rename |
| 5 | FCST-11 | CashFlow includes only next occurrence | CRITICAL | STILL PRESENT | CashFlowCalculator.kt | Use RecurringOccurrenceExpander |
| 6 | FCST-7 | Planned expenses double-count with recurring | CRITICAL | PARTIALLY | SynthesisEngine.kt, ForecastInputAssembler.kt | Cross-deduplicate |
| 7 | FCST-3 | Block Party monthly vs actual spikes inconsistent | MAJOR | STILL PRESENT | SynthesisEngine.kt | Sum actual occurrences |
| 8 | FCST-5 | Dashboard vs weather forecast different data scopes | MAJOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt, FinancialWeatherRepository.kt | Dedicated forecast source |
| 9 | FCST-6 | Weather ignores detected patterns (confirmed only) | MAJOR | STILL PRESENT | FinancialWeatherRepository.kt | Use getAllRecurringPatterns |
| 10 | FCST-10 | Income timing too simple (no payday detection) | MAJOR | STILL PRESENT | FinancialStressForecastEngine.kt | Recurring income matching |
| 11 | FCST-12 | CashFlow double-counts actual+predicted same day | MAJOR | STILL PRESENT | CashFlowCalculator.kt | Deduplicate by merchant/date |
| 12 | FCST-14 | Forecast confidence disconnected from data quality | MAJOR | STILL PRESENT | SynthesisEngine.kt | Integrate DataQualityAssessor |
| 13 | FCST-2 | Block Party marks days before nextExpectedDate | MAJOR | PARTIALLY | SynthesisEngine.kt | Add date>=nextExpectedDate guard |
| 14 | FCST-N1 | Weather path manualRecurringEntities=emptyList() | MAJOR | STILL PRESENT | FinancialWeatherRepository.kt | Pass actual manual entities |
| 15 | FCST-N2 | Dashboard forecast bypasses AnalyticsCurrencyNormalizer | MAJOR | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt | Route through normalizer |
| 16 | FCST-15 | Monte Carlo recency overstates quality | MEDIUM | STILL PRESENT | MonteCarloSpendingSimulator.kt | Apply 3-day filter |
| 17 | FCST-16 | Distribution excludes quiet weeks, biasing upward | MEDIUM | STILL PRESENT | HistoricalSpendingDistribution.kt | Include zero-spend weeks |
| 18 | FCST-17 | Fallback hides failures (catch-all zeroed) | MEDIUM | STILL PRESENT | SynthesisEngine.kt, FinancialWeatherRepository.kt | Add structured diagnostics |
| 19 | FCST-N3 | Inconsistent merchantKey fallback between paths | MEDIUM | STILL PRESENT | ComputeDashboardWidgetsUseCase.kt, ForecastInputAssembler.kt | Unify fallback logic |
| 20 | FCST-N4 | SynthesisEngine doesn't check PlannedExpense.status | MEDIUM | STILL PRESENT | SynthesisEngine.kt | Add status filter |
| 21 | FCST-N5 | Dead code in HistoricalSpendingDistribution | MINOR | STILL PRESENT | HistoricalSpendingDistribution.kt | Remove dead code |

### 17. AI Integration

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | AID-4 | Runtime fallback missing in 6 of 7 hybrid services | CRITICAL | STILL PRESENT | Hybrid*Service.kt (6 files) | Create HybridExecutor |
| 2 | AID-9 | No AI result application boundary + audit | CRITICAL | STILL PRESENT | Multiple AI services | Add validation boundary |
| 3 | AID-N1 | CloudQueryInterpretationService zero privacy guards | CRITICAL | STILL PRESENT | CloudQueryInterpretationService.kt | Add all privacy guards |
| 4 | AID-N2 | CloudReceiptItemCategorization+WarrantyExtraction lack checks | MAJOR | STILL PRESENT | CloudReceiptItemCategorizationService.kt, CloudWarrantyExtractionService.kt | Inject AiSettingsRepository+PrivacyGate |
| 5 | AID-5 | Cloud providers no unified gate | MAJOR | PARTIALLY | Multiple cloud providers | Create CloudAiGate |
| 6 | AID-9-PR8 | AI output validation uneven | MAJOR | PARTIALLY | StrictAiJsonParsing.kt, multiple services | Standardize validators |
| 7 | AID-N3 | Providers inconsistently use PrivacyGate | MEDIUM | STILL PRESENT | Multiple cloud providers | Inject PrivacyGate into all |
| 8 | AID-N4 | CloudDedupeJudgeService confidence not bounded 0-1 | MEDIUM | STILL PRESENT | CloudDedupeJudgeService.kt | Use boundedConfidenceOrNull |
| 9 | AID-N5 | CloudReceiptAssistService lacks bounded validation | MEDIUM | STILL PRESENT | CloudReceiptAssistService.kt | Add positivity+epoch checks |
| 10 | AID-N6 | No canonical AI defaults object | MINOR | STILL PRESENT | AiModels.kt, AiSettingsRepositoryImpl.kt | Create DefaultAiSettings |
| 11 | AID-10 | Per-request diagnostics incomplete | MEDIUM | PARTIALLY | AiRuntimeDiagnostics.kt | Extend coverage |
| 12 | AID-E | AiPolicyTest misleading comment | MINOR | STILL PRESENT | AiPolicyTest.kt | Fix comment |
| 13 | AID-F | CloudDashboardBriefingService logs full URL | MINOR | STILL PRESENT | CloudDashboardBriefingService.kt | Trim log output |

### 18. Migration Policy

| # | Issue ID | Description | Sev | Status | File(s) | Fix Pattern |
|---|----------|-------------|-----|--------|---------|-------------|
| 1 | RSP-R2A | No migration path for schema versions 1-5 | CRITICAL | STILL PRESENT | AppDatabase.kt | Add v1->v6 migrations or legacy importer |
| 2 | RSP-R3A | No migration tests for versions 92->108 (16 uncovered) | MAJOR | STILL PRESENT | DatabaseMigrationTest.kt | Add migration tests |
| 3 | RSP-A1 | MIGRATION_107_108 CHECK may fail with existing data | MAJOR | STILL PRESENT | AppDatabase.kt | Add pre-healing step |
| 4 | RSP-R2B | Multi-hop MIGRATION_96_100 missing schema for v97/98/99 | MINOR | STILL PRESENT | AppDatabase.kt | Add per-version or confirm unreleased |
| 5 | RSP-R3B | No real-DB snapshot migration tests | MINOR | STILL PRESENT | DatabaseMigrationTest.kt | Add real-DB tests |
| 6 | RSP-R3C | No PRAGMA foreign_key_check in test assertions | MINOR | STILL PRESENT | DatabaseMigrationTest.kt | Add FK check utility |
| 7 | RSP-R4A | No pre-upgrade backup prompt | MINOR | STILL PRESENT | MainActivity.kt | Add upgrade detection |
| 8 | RSP-R5A | No legacy DB importer for pre-v6 | MINOR | STILL PRESENT | (new file) | Implement LegacyDatabaseImporter |
| 9 | RSP-R6A | No fresh-vs-migrated parity test | MINOR | STILL PRESENT | DatabaseMigrationTest.kt | Add parity test |
| 10 | RSP-A2 | SimpleDateFormat not thread-safe | MINOR | STILL PRESENT | CsvExpenseImporter.kt | Use DateTimeFormatter |
| 11 | RSP-A3 | countRowsFromSourceTable string interpolation | MINOR | STILL PRESENT | DatabaseBackupRepositoryImpl.kt | Add table name whitelist |


---

## Cross-Subsystem Batch Suggestions

### Suggestion 1: Wire `RecurringOccurrenceExpander` into forecast/cashflow paths
**Files:** SynthesisEngine.kt, CashFlowCalculator.kt, FinancialWeatherRepository.kt, ForecastInputAssembler.kt
**Issues fixed:** FCST-1, FCST-6, FCST-11, FCST-N1, FCST-7
**Pattern:** Replace `nextExpectedDate` single-occurrence with `RecurringOccurrenceExpander.expand()` or `RecurringLifecycleCoordinator.generateOccurrences()`.

### Suggestion 2: Retire legacy `BillReminderManager` paths
**Files:** BillReminderManager.kt
**Issues fixed:** REC-1, REC-2, REC-3, REC-4, REC-22, REC-24
**Pattern:** Delegate to `RecurringLifecycleCoordinator`. Remove or deprecate `getNotificationsDue()` and `markBillPaid()`.

### Suggestion 3: Deprecate and bypass `ReceiptRepository` legacy linking
**Files:** ReceiptRepository.kt, ReceiptMatchingViewModel.kt, ReceiptLinkService.kt
**Issues fixed:** RCP-N1, RCP-N3, RCP-1, RCP-24, RCP-20, WRN-N2
**Pattern:** Route all receipt ops through `ReceiptLifecycleCoordinator` and `ReceiptLinkService`.

### Suggestion 4: Hard-code `MultiCurrencyRepository` adoption in remaining aggregates
**Files:** TotalsAggregationEngine.kt, SpendingHeatmapEngine.kt, LocationInsightsEngine.kt, AreaSpendingEngine.kt, BudgetRepository.kt
**Issues fixed:** DSH-10, LOC-9, BUD-17, CURR-3, CURR-18
**Pattern:** Replace deprecated raw-sum DAO calls with `MultiCurrencyRepository` equivalents.

### Suggestion 5: Add `PrivacyGate` checks to all external-service providers
**Files:** PhotonGeocodingService.kt, GeoapifyGeocodingService.kt, GooglePlacesGeocodingService.kt, CloudQueryInterpretationService.kt, CloudReceiptItemCategorizationService.kt, CloudWarrantyExtractionService.kt
**Issues fixed:** PRV-N1, AID-N1, AID-N2, AID-N3
**Pattern:** Add `privacyGate.check()` at entry of every public method.

### Suggestion 6: Normalize AI confidence scales and validation
**Files:** CloudReceiptAssistService.kt, CloudDedupeJudgeService.kt, CloudWarrantyExtractionService.kt, CloudReceiptItemCategorizationService.kt, StrictAiJsonParsing.kt
**Issues fixed:** AID-N4, AID-N5, AI-9(PR-8), WRN-16, WRN-17
**Pattern:** Use `boundedConfidenceOrNull()`, add positivity/epoch-range checks, create shared `AiOutputValidators`.

### Suggestion 7: Fix period boundary correctness across dashboard/analytics
**Files:** TotalsAggregationEngine.kt, ExpenseDao.kt, AnalyticsRepository.kt, DashboardContractsAdapter.kt
**Issues fixed:** DSH-2, DSH-3, DSH-4, DSH-REM3, AIML-4
**Pattern:** Replace MIN/MAX date with canonical calendar boundaries; replace ms-subtraction with `TimePeriodUtils`.

### Suggestion 8: Add DB invariant enforcement for remaining entities
**Files:** BudgetForecast.kt, SubscriptionCandidate.kt, ExchangeRate.kt, AppDatabase.kt
**Issues fixed:** DB-2, DB-6, BUD-25, DB-3
**Pattern:** Add materialized key columns + UNIQUE indexes + CHECK constraints following the budget/group_member pattern.

---

## Summary Statistics

### By Severity

| Severity | Count | % |
|----------|-------|---|
| CRITICAL | 25 | 7% |
| MAJOR/HIGH | 190 | 53% |
| MEDIUM | 62 | 17% |
| MINOR/LOW | 79 | 22% |
| **Total** | **356** | **100%** |

### By Status

| Status | Count | % |
|--------|-------|---|
| STILL PRESENT | 254 | 71% |
| PARTIALLY RESOLVED | 102 | 29% |
| **Total** | **356** | **100%** |

### By Subsystem

| Subsystem | Total | Critical | Major | Medium | Minor |
|-----------|-------|----------|-------|--------|-------|
| Transaction | 17 | 5 | 12 | 0 | 0 |
| Receipt | 30 | 4 | 20 | 4 | 2 |
| Recurring | 19 | 3 | 13 | 1 | 2 |
| Currency | 19 | 4 | 8 | 4 | 3 |
| Privacy | 13 | 2 | 7 | 2 | 2 |
| Backup | 19 | 1 | 10 | 2 | 6 |
| Dashboard | 18 | 2 | 8 | 4 | 4 |
| AI/ML | 32 | 4 | 21 | 5 | 2 |
| Budget | 29 | 3 | 16 | 8 | 2 |
| Warranty | 36 | 0 | 30 | 4 | 2 |
| Location | 15 | 2 | 10 | 3 | 0 |
| Search | 28 | 0 | 21 | 5 | 2 |
| Shared | 17 | 4 | 11 | 0 | 2 |
| DB | 9 | 1 | 4 | 1 | 3 |
| Workers | 24 | 0 | 13 | 9 | 2 |
| Forecast | 21 | 6 | 9 | 5 | 1 |
| AI Integration | 13 | 3 | 3 | 5 | 2 |
| Migration Policy | 11 | 1 | 2 | 0 | 8 |
| **Total** | **356** | **40** | **198** | **54** | **36** |

---

## Recommended Execution Strategy

### Phase 0 � Immediate (Batch A: Critical Data Integrity)
Fix the 17 critical financial-corruption issues that can silently lose or distort data.

### Phase 1 � Multi-Currency Safety (Batch B)
Fix all currency raw-sum, hardcoded EUR, and missing conversion fields.

### Phase 2 � Coordinator Adoption (Batch C)
Route all legacy paths through the new lifecycle coordinators.

### Phase 3 � Privacy Hardening (Batch D)
Close all bypassable privacy-gate entry points.

### Phase 4 � DB Schema Invariants (Batch E)
Add remaining DB-level constraints and migration tests.

### Phase 5 � Remaining (Batch F)
All other issues by severity within each subsystem.

---

*Generated 2026-05-02 from 21 review documents.*
