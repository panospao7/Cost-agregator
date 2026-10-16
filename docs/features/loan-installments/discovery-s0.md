# loan-installments — S0 discovery findings

Author: scout (Sonnet override, read-only, ~22 tool calls). Persisted by feature-orchestrator, 2026-10-16.
Resolves impact-map UNVERIFIED items. Items 1, 2, 5 flagged by scout as needing confirmation.
Paths relative to `app/src/main/java/com/yourname/expensetracker/` unless noted.

## 1. Recurring generation worker / AUTO_RECORD host (IM-7)
- **No dedicated recurring "materialise" worker exists** (high confidence).
- `domain/workers/WorkerRegistry.kt:93-111` schedules: location_backfill, merchant_key_backfill,
  warranty_expiration_check, data_retention, bill_reminder_periodic (`service/reminder/BillReminderWorker.kt`),
  receipt_matching, ai_daily_briefing (`DailyBriefingWorker`, `scheduleAtMidnight`). Scheduled from
  `startup/AppStartupCoordinator.kt:708-714` via `WorkerRegistry.scheduleAll`.
- `BillReminderWorker` uses `WorkerExecutionGuard` (`:38`, `runGuardedWithContext` `:47`), only dispatches reminder
  deliveries via `RecurringLifecycleCoordinator.getDueReminders`; creates no expenses.
- `RecurringLifecycleCoordinator.generateOccurrences` (`domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt:103`)
  only non-test caller at :1294 (`ensureOccurrencesGeneratedForReconciliation`, documented retained dead code
  :1279-1284). Real generation driver likely `RecurringRuleLifecycleCoordinator` / projection service — UNTRACED (medium).
- **Implication:** AUTO_RECORD needs a **new worker** registered in `WorkerRegistry.kt` (spec key for
  `WorkerSpecScheduler`, `WorkerExecutionGuard`, writes only via TLC → `insertAtomic`). Patterns: `DailyBriefingWorker`,
  `BillReminderWorker`. Guard surface FG-10/FG-11.

## 2. Data wipe / rescue
- **No `clearAllTables` and no production erase-all use case found** (medium confidence).
- `ui/screens/debug/DebugViewModel.kt:133-193` calls per-repository `deleteAll` (e.g. `ExpenseRepository.kt:715`).
  `NotificationRepository.kt:199-303` notification-scoped. Many DAOs have their own `deleteAll()`.
- Clearing is explicit per DAO → new tables NOT covered automatically. Still to check: `data/privacy/DataRetentionWorker.kt`,
  settings screens.
- `data/rescue/FinancialRescueCoordinator.kt` (corrupt-DB rescue) copies an explicit table allow-list (categories,
  expenses, groups, members, group_expenses, split assignments; lines 36, 144-320) → **plan tables lost on rescue unless
  added**.

## 3. Export code
- `domain/export/ExpenseExportMapper.kt:20-92` (fields 22-55, `toExportTransaction()` :92);
  `domain/export/ExportTransaction.kt:13`; `data/repository/AccountingExportRepository.kt:115`;
  `domain/export/AccountantReportPdfExporter.kt:43`; `ui/screens/export/ExportOptionsViewModel.kt:78` (sample :374).
- "Not counted" marker: field on `ExportTransaction` + mapper, then CSV/JSON/PDF writers (writer locations UNTRACED).

## 4. Dashboard briefing upstream (cloud exclusion point)
- `domain/usecase/dashboard/DashboardDataProvider.kt:46-115` builds `ProcessedDashboardData` (:107); planning flow combines
  `financialWeatherRepository.observeFinancialWeather()`, `observeRecurringPatterns`, `observePlannedExpenses`.
- `domain/ai/usecase/DashboardBriefingInputBuilder.kt:59-68` reads `weather.upcomingItems` (cap 5), `totalCommitted`,
  `totalLikely`, `discretionaryBudget`; not `CashFlowCalculator` / `RecurringPlanProjectionService` directly.
- `data/repository/FinancialWeatherRepository.kt:106` `upcomingItems = buildUpcomingItems(...)` (def :133), fed by
  `forecast.components.confirmedOccurrences` (:109).
- **Entry point:** `UpcomingItem` sealed type (Recurring / Occurrence / Planned); exhaustive `when` at
  `DashboardBriefingInputBuilder.kt:87-91`. An installment variant or forecast `totalCommitted` contribution would reach the
  cloud briefing → exclusion must be explicit here.
- `RecurringPlanProjectionService` used only by `RecurringRuleLifecycleCoordinator` (:43), not the dashboard.

## 5. Backup privacy modes
- `domain/backup/BackupPrivacyMode.kt:10-14`: FULL_ENCRYPTED (raw text kept, images), REDACT_RAW_TEXT, REDACT_RAW_TEXT_EXCLUDE_IMAGES,
  ANONYMIZED_EXPORT.
- `DatabaseBackupRepositoryImpl.kt:619, 721-722` → `data/privacy/ExportAnonymizer.kt:55` `sanitizeExport`: explicit per-table
  UPDATEs (scanned_receipts, raw_notifications, notification_intake, ai_artifacts, ai_chat_messages, merchant_locations,
  email_receipt_sources, pending_reviews, bank_statement_import_items).
- **`expenses` is not sanitised at all** (merchant/notes kept in redacted modes; high confidence). Plan tables with free text
  (`displayName`, `lenderLabel`) need an explicit decision/entry. Manifest counts note at `DatabaseBackupRepositoryImpl.kt:726`.

## 6. Migration tests
- Version 151 (`AppDatabase.kt:43`); `MIGRATION_150_151` = `SavingsSweepMonthMigration` (`DatabaseMigrations.kt:149`,
  `SavingsSweepMonthMigration.kt:10`); list ~`DatabaseMigrations.kt:182`.
- **No 150→151 test** in `app/src/androidTest/.../data/database/DatabaseMigrationTest.kt` (latest seen: 147_148 at :4255,
  148_149 ~:4306-4333; 149_150 unverified). Pre-existing gap — outside this feature.
- Pattern: `migration_X_Y_<what>` → create DB at old version → `helper.runMigrationsAndValidate(..., DatabaseMigrations.MIGRATION_X_Y)`
  → cursor assertions. An ALL_MIGRATIONS-style list (~:168-172) is stale (ends 144_145).

## 7. Default categories
- `data/provider/MerchantCategoryProvider.kt:11-32` — 20 defaults: Groceries, Transport, Food, Entertainment, Shopping, Health,
  Utilities, Subscriptions, Travel, Electronics, Education, Fitness, Beauty, Pets, Home, Kids, Gifts, Banking, Legal & Gov,
  Uncategorized. **No Loan/Mortgage/Bills/Debt category.**
- Quirk: `MerchantCategoryProvider.kt:581` maps "Bills"/"BILLS" → Food (legacy; don't rely on it).
- Seeding: `CategoryRepository.ensureDefaultCategories` (`data/repository/CategoryRepository.kt:45-83`) via
  `categoryDao.seedDefaultsIfEmpty`; existing users only back-filled "Uncategorized" (BUG-012). A new default category would
  not reach existing installs without explicit back-fill.
- Savings engines (`SmartSavingsEngine.kt:677`, `AutomatedSavingsRuleEngine.kt:351`) match by category name; "loan",
  "mortgage", "bills" are essential keywords → a category named "Loans" would be treated as essential. Other "bills" hits:
  `FinancialHealthScoreV2.kt:735, 756`, `ui/components/dashboard/MoneyRadarWidget.kt:253`.

## 8. Add-expense currency
- `Expense.currency: String = "EUR"` (`@ColumnInfo(defaultValue = "'EUR'")`, `Expense.kt:58`) + `baseCurrency`, `baseAmount`,
  `exchangeRateUsed` (populated by TLC at creation).
- Add expense has **no currency choice**: `AddExpenseViewModel.kt:63` `homeCurrency`, from
  `currencySettingsRepository.homeCurrency()` (:93-95), save blocked while null (:278-280), saves home currency (:381);
  `AddExpenseSheet.kt:202-209` displays symbol only.
- Existing picker: `ui/screens/receiptscan/ReceiptScanScreen.kt:1379-1385` `private fun CurrencyPicker` (used :883) — must be
  extracted to reuse; `ui/screens/review/ReviewScreen.kt:1749` inline picker (S6-D5-003). No shared public picker.

## Touchpoints the plan must include (scout)
`ExportAnonymizer` decision/entry for plan text; `FinancialRescueCoordinator` allow-list; wipe path (once found); backup
manifest counts; 151→152 migration + test; new `WorkerRegistry` entry with `WorkerExecutionGuard`; `ExportTransaction`
marker; `UpcomingItem` variant + explicit cloud-briefing exclusion; currency picker extraction; category default/back-fill.

## Out-of-scope observations (for owner triage, not this feature)
- No migration test for 150→151.
- `ExportAnonymizer` does not sanitise `expenses` merchant/notes in redacted backup modes.
- "Bills" → Food mapping quirk in `MerchantCategoryProvider.kt:581`.
