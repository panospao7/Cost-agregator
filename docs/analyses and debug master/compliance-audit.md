# Compliance Audit: New Foundation Bypasses

**Date:** 2026-05-04  
**Scope:** `app/src/main/java/com/yourname/expensetracker/`  
**Auditor:** Scout Agent  

---

## 1. Direct DAO inserts/updates (bypassing coordinators)

### 1.1 `scannedReceiptDao.insert()` outside whitelist

**Whitelist:** `ReceiptRepository.kt`, `ReceiptLifecycleCoordinator.kt`, `ReceiptLinkService.kt`

| FILE | LINE | DETAILS | SEVERITY |
|------|------|---------|----------|
| `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 204 | Direct `scannedReceiptDao.insert(statementReceipt)` call. This class is NOT in the whitelist. Should delegate to `ReceiptRepository` or `ReceiptLifecycleCoordinator`. | **HIGH** |

✅ `expenseDao.insert()` — 0 occurrences (all inserts go through `TransactionLifecycleCoordinator`)  
✅ `expenseDao.insertAtomic()` — only in `TransactionLifecycleCoordinator.kt:248`  
✅ `expenseDao.update()` — only in `TransactionLifecycleCoordinator.kt:368`  
✅ `recurringExpenseDao.insert()` — 0 occurrences  
✅ `manualRecurringExpenseDao.insert()` — 0 occurrences  
✅ `budgetDao.insert()` — only in `BudgetRepository.kt:280` (whitelisted)  

---

## 2. Hardcoded EUR defaults (not using CurrencySettings)

### 2.1 `currency: String = "EUR"` in domain model data classes (no @ColumnInfo)

| FILE | LINE | ENTITY | SEVERITY |
|------|------|--------|----------|
| `domain/model/dashboard/DashboardPrimitives.kt` | 16 | `DashboardExpense.currency: String = "EUR"` | **HIGH** |
| `domain/model/dashboard/SpendingSummary.kt` | 13 | `SpendingSummary.currency: String = "EUR"` | **HIGH** |
| `domain/model/SavingsGoal.kt` | 10 | Domain `SavingsGoal.currency: String = "EUR"` | **HIGH** |
| `data/repository/AnalyticsRepository.kt` | 29 | `SpendingSummary.currency: String = "EUR"` | **HIGH** |
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 202 | `CategorySpending.currency: String = "EUR"` | **MEDIUM** |
| `domain/text/UiTextArg.kt` | 6 | `currency: String = "EUR"` | **MEDIUM** |
| `domain/savings/SmartSavingsEngine.kt` | 29 | `SavingsRecommendation.currency: String = ""` (empty, not EUR, but no real default) | **LOW** |

### 2.2 `currency: String = "EUR"` in ViewModel/UI state classes

| FILE | LINE | STATE CLASS | SEVERITY |
|------|------|-------------|----------|
| `ui/screens/analytics/AnalyticsViewModel.kt` | 51 | `BudgetVsActualItem.displayCurrency: String = "EUR"` | **MEDIUM** |
| `ui/screens/analytics/AnalyticsViewModel.kt` | 89 | `AnalyticsState.homeCurrency: String = "EUR"` | **MEDIUM** |
| `ui/screens/map/SpendingMapViewModel.kt` | 95 | `SpendingMapState.homeCurrency: String = "EUR"` (documents as acceptable placeholder) | **LOW** |
| `ui/screens/home/HomeViewModel.kt` | 149 | `stateIn(..., "EUR")` — immediately replaced by repository flow | **LOW** |
| `ui/screens/tax/TaxConfigurationViewModel.kt` | 33 | `TaxConfigState.currency: String = "EUR"` | **MEDIUM** |

### 2.3 `currency = "EUR"` hardcoded assignments

| FILE | LINE | CONTEXT | SEVERITY |
|------|------|---------|----------|
| `data/repository/ReceiptRepository.kt` | 225, 241, 254, 275, 288, 607 | Hardcoded `"EUR"` when creating ScannedReceipt/ParsedReceipt objects | **HIGH** |
| `data/repository/ReviewQueueRepository.kt` | 489 | `suggestedCurrency = "EUR"` for placeholder reviews | **MEDIUM** |
| `data/repository/WarrantyTrackerRepository.kt` | 365 | `currency = "EUR"` when creating warranty receipt | **HIGH** |
| `data/repository/BudgetRepository.kt` | 269 | Fallback `"EUR"` in `resolveHomeCurrency()` catch block | **LOW** |
| `data/repository/NotificationProcessingPipeline.kt` | 513, 576 | `else -> "EUR"` in currency detection (acceptable fallback) | **LOW** |
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | 254 | `currency = "EUR"` when OCR fails (TODO comment acknowledges) | **MEDIUM** |
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | 1028 | `"EUR"` fallback when home currency fails to load | **LOW** |
| `util/CsvExpenseImporter.kt` | 141 | `currency = "EUR"` — hardcoded, should use CurrencySettings | **HIGH** |
| `domain/bank/BankApiIntegration.kt` | 314 | `currency = "EUR"` — STUB comment acknowledges issue | **MEDIUM** |
| `service/debug/LegacyDataMigrationService.kt` | 262, 398 | `currency = "EUR"` fallback when cursor value is null | **LOW** |
| `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 195 | `currency = "EUR"` fallback when no parsed currency | **MEDIUM** |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 83 | `FALLBACK_CURRENCY = "EUR"` constant | **LOW** |
| `domain/receipt/ReceiptParser.kt` | 183 | `homeCurrency: String = "EUR"` default param | **MEDIUM** |
| `domain/receipt/BankStatementParser.kt` | 116, 120 | Hardcoded `"EUR"` return when currency detection fails | **LOW** |
| `ui/screens/debug/DebugDataStorage.kt` | 93 | `currency = "EUR"` fallback | **LOW** |

### 2.4 `currency: String = "EUR"` default params in analytics engines

| FILE | LINE | METHOD | SEVERITY |
|------|------|--------|----------|
| `domain/analytics/InsightsEngine.kt` | 92, 124, 264, 688, 702 | Multiple methods default to `"EUR"` | **MEDIUM** |
| `domain/analytics/CategoryInsightEngine.kt` | 63 | `displayCurrency: String = "EUR"` | **MEDIUM** |
| `domain/analytics/SpendingPaceCalculator.kt` | 43 | `displayCurrency: String = "EUR"` | **MEDIUM** |
| `domain/analytics/AnomalyDetector.kt` | 153 | `displayCurrency: String = "EUR"` | **MEDIUM** |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | — | Internal usage; depends on caller | **LOW** |

### 2.5 `currency: String = "EUR"` default params in savings/forecasting engines

| FILE | LINE | METHOD | SEVERITY |
|------|------|--------|----------|
| `domain/savings/SmartSavingsEngine.kt` | 88, 104, 434 | `homeCurrency: String = "EUR"` | **MEDIUM** |
| `domain/forecasting/MonteCarloSpendingSimulator.kt` | 63 | `displayCurrency: String = "EUR"` | **MEDIUM** |

### 2.6 UI Components with hardcoded `currency: String = "EUR"` defaults

All of these make it possible to render amounts with wrong currency symbol if the caller doesn't explicitly pass the currency:

| FILE | LINE(S) | SEVERITY |
|------|---------|----------|
| `ui/components/TotalsDashboardCard.kt` | 48, 121 | **MEDIUM** |
| `ui/components/RetroTotalsDashboardCard.kt` | 55, 323, 371, 608 | **MEDIUM** |
| `ui/components/BudgetBlockPartyCard.kt` | 61, 174, 249 | **MEDIUM** |
| `ui/components/RetroBudgetBlockPartyCard.kt` | 55, 800 | **MEDIUM** |
| `ui/components/CategoryBreakdownSheet.kt` | 37, 130 | **MEDIUM** |
| `ui/components/RetroCategoryBreakdownSheet.kt` | 48, 284 | **MEDIUM** |
| `ui/components/RetroTopCategoriesCard.kt` | 56, 207, 421, 659, 763 | **MEDIUM** |
| `ui/components/PeriodGridView.kt` | 22 | **MEDIUM** |
| `ui/components/PeriodBlock.kt` | 28 | **MEDIUM** |
| `ui/components/MonteCarloForecastCard.kt` | 36 | **MEDIUM** |
| `ui/components/FinancialRunwayCard.kt` | 32 | **MEDIUM** |
| `ui/components/ForecastTimeline.kt` | 37 | **MEDIUM** |
| `ui/components/SpendingTrendChart.kt` | 39 | **MEDIUM** |
| `ui/components/analytics/StatisticalVisualizations.kt` | 34, 122, 168, 288, 354 | **MEDIUM** |

### 2.7 UI Screen functions with hardcoded `currency: String = "EUR"` defaults

| FILE | LINE | FUNCTION | SEVERITY |
|------|------|----------|----------|
| `ui/screens/analytics/AnalyticsScreen.kt` | 653 | `HourOfDayChartBento(..., currency: String = "EUR")` | **MEDIUM** |
| `ui/screens/analytics/AnalyticsScreen.kt` | 720 | `StatMicro(..., currency: String = "EUR")` | **MEDIUM** |
| `ui/screens/analytics/AnalyticsScreen.kt` | 1608 | `AreaSpendingItem(..., homeCurrency: String = "EUR")` | **MEDIUM** |
| `ui/screens/analytics/AnalyticsScreen.kt` | 1651 | `TravelInsightCard(..., homeCurrency: String = "EUR")` | **MEDIUM** |
| `ui/screens/budget/BudgetScreen.kt` | 952 | Composable default `"EUR"` | **MEDIUM** |
| `ui/screens/budget/BudgetScreen.kt` | 1099 | Composable default `"EUR"` | **MEDIUM** |
| `ui/screens/transactions/TransactionsScreen.kt` | 797 | Composable default `"EUR"` | **MEDIUM** |
| `ui/screens/groups/SharedExpenseGroupsScreen.kt` | 680 | Composable default `"EUR"` | **MEDIUM** |

### 2.8 Repository layer default params

| FILE | LINE | METHOD | SEVERITY |
|------|------|--------|----------|
| `data/repository/ReceiptRepository.kt` | 340 | `currency: String = "EUR"` | **HIGH** |
| `data/repository/RecurringExpenseRepository.kt` | 32, 70 | `currency: String = "EUR"` in `createRecurringExpenseEntity()` and `addRecurringExpense()` | **HIGH** |

### 2.9 Acceptable entity defaults (filtered — have @ColumnInfo)

The following entity classes have `currency: String = "EUR"` WITH `@ColumnInfo(defaultValue = "'EUR'")` and are **EXEMPT** per audit rules:
- `Expense.kt:58` 
- `ScannedReceipt.kt:69`
- `Budget.kt:66`
- `GroupExpense.kt:81`
- `BudgetAdjustmentRecommendation.kt:57`
- `AnomalyAlert.kt:51`
- `PlannedExpense.kt:41`
- `StressForecastSnapshot.kt:92`
- `SpendingChallengeEntity.kt:37`
- `SubscriptionCandidate.kt:58`
- `SubscriptionPriceHistory.kt:34`
- `ManualRecurringExpense.kt:25`
- `Investment.kt:28`
- `BudgetForecast.kt:68`
- `SavingsSweepPlan.kt:77`
- `SavingsGoal.kt:19` (entity)

---

## 3. Raw currency sums (not using MultiCurrencyRepository/CurrencyConverter)

### 3.1 `.sumOf { it.amount }` without normalization

| FILE | LINE | CODE | SEVERITY |
|------|------|------|----------|
| `domain/logic/SynthesisEngine.kt` | 168 | `plannedExpenses.filter{...}.sumOf { it.amount }` — `PlannedExpense` has NO currency field | **HIGH** |
| `domain/logic/SynthesisEngine.kt` | 196 | `plannedExpenses.filter{...}.sumOf { it.amount } * LIKELY_EXPENSE_WEIGHT` | **HIGH** |
| `domain/logic/SynthesisEngine.kt` | 247 | `mapValues { it.value.sumOf { exp -> exp.amount } }` | **HIGH** |
| `domain/logic/SynthesisEngine.kt` | 254 | `mapValues { it.value.sumOf { exp -> exp.amount } * LIKELY_EXPENSE_WEIGHT }` | **HIGH** |
| `domain/savings/SavingsGamificationEngine.kt` | 196 | `currentMonthContributions.sumOf { it.amount }` | **MEDIUM** |
| `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt` | 170 | `items.sumOf { it.amount }` (same-receipt items likely same currency) | **LOW** |
| `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt` | 175 | `itemsInCategory.sumOf { it.amount }` (same-receipt items) | **LOW** |
| `ui/screens/cashflow/CashFlowCalendarScreen.kt` | 352 | `cashFlow.income.sumOf { abs(it.amount) }` (comment: "almost always same-currency") | **MEDIUM** |

### 3.2 `SUM(amount)` or raw SQL aggregates without currency conversion

| FILE | LINE | QUERY | SEVERITY |
|------|------|-------|----------|
| `data/database/AppDatabase.kt` | 486 | `SUM(amount) as totalSpent` in merchant migration SQL | **MEDIUM** |
| `data/database/dao/ReceiptItemCategorizationDao.kt` | 95 | `SUM(itemAmount)` — no currency awareness | **LOW** |
| `data/database/dao/WarrantyDao.kt` | 72 | `SUM(COALESCE(e.amount, 0))` — wrapped expense join | **MEDIUM** |
| `data/database/dao/GroupExpenseDao.kt` | 41, 48 | `SUM(totalAmount)` — groups are single-currency | **LOW** |
| `data/database/dao/SplitItemAssignmentDao.kt` | 15 | `SUM(assignedAmount)` — single-expense splits | **LOW** |

### 3.3 Known gap: TotalsAggregationEngine

| FILE | LINE | DETAILS | SEVERITY |
|------|------|---------|----------|
| `domain/analytics/TotalsAggregationEngine.kt` | 24-45 | Self-documented gap: "**CURRENCY NORMALIZATION: GAP — no normalization applied**". All methods (`getMonthlyTotals`, `getWeeklyTotals`, `getDailyTotals`, `getYearlyTotals`, `getCategoryBreakdown`) operate on raw DAO totals. | **HIGH** |
| `domain/analytics/TotalsAggregationEngine.kt` | 211 | `val grandTotal = categoryResults.sumOf { it.total }` | **HIGH** |

### 3.4 Amount arithmetic without conversion

| FILE | LINE | PATTERN | SEVERITY |
|------|------|---------|----------|
| `domain/analytics/AdvancedAnalyticsEngine.kt` | 240 | `budgetRemaining = budget?.let { it.amount - total }` | **MEDIUM** |
| `domain/budget/BudgetAutopilotEngine.kt` | 121-122 | `budget.amount - maxDelta`, `budget.amount + maxDelta` | **MEDIUM** |
| `domain/budget/BudgetRecommendationEngine.kt` | 53, 151 | `budget.amount - currentSpending` | **MEDIUM** |
| `domain/budget/SharedBudgetManager.kt` | 59 | `budget.amount - totalSpent` | **MEDIUM** |
| `domain/subscription/SubscriptionManagerEngine.kt` | 195, 212 | `subscription.amount - newAmount`, `(new.amount - old.amount) / old.amount` | **MEDIUM** |

### 3.5 `ExpenseDao.kt` — all `SUM(${EFFECTIVE_AMOUNT_SQL})` queries are deprecated

| FILE | LINE | DETAILS | SEVERITY |
|------|------|---------|----------|
| `data/database/dao/ExpenseDao.kt` | 259-261 | `getTotalSpentFlow()` is `@Deprecated("Returns raw Double without currency conversion. Use MultiCurrencyRepository for currency-aware aggregation.")` | **HIGH** |
| `data/database/dao/ExpenseDao.kt` | Multiple (68+ occurrences) | All `SUM(${EFFECTIVE_AMOUNT_SQL})` queries — `EFFECTIVE_AMOUNT_SQL` adjusts for ownership (isNotMine, shared) but NOT for currency. These are raw-currency sums. | **HIGH** |

---

## 4. Direct System.currentTimeMillis() (not using TimeProvider)

**Whitelist:** `SystemTimeProvider.kt:12` (the TimeProvider implementation), `TimeProvider.kt` (interface doc)

### 4.1 Domain/Engine layer violations

| FILE | LINE | USAGE | SEVERITY |
|------|------|-------|----------|
| `domain/forecasting/FinancialStressForecastEngine.kt` | 81, 149 | Performance timing `System.currentTimeMillis() - startTime` | **MEDIUM** |
| `domain/health/FinancialHealthScoreV2.kt` | 85, 192 | Performance timing | **MEDIUM** |
| `domain/receipt/ReceiptOcrService.kt` | 603, 620 | File naming `receipt_${System.currentTimeMillis()}.jpg` | **LOW** |
| `domain/receipt/lifecycle/ReceiptAssetStore.kt` | 47, 69 | File naming | **LOW** |
| `domain/debug/NotificationSeeder.kt` | 42, 97, 126, 157, 178, 199 | Debug-only seed data | **LOW** |
| `domain/debug/ServiceDiagnostics.kt` | 35, 44 | Debug/telemetry timestamps | **LOW** |
| `domain/debug/AiRuntimeDiagnostics.kt` | 20, 30, 34 | Debug-only diagnostics | **LOW** |
| `domain/ai/validation/AiOutputValidators.kt` | 30 | Date range validation | **LOW** |
| `domain/parser/AppParserRegistry.kt` | 37 | Validation epoch | **LOW** |

### 4.2 DAO layer violations (default parameter values)

| FILE | LINE(S) | SEVERITY |
|------|---------|----------|
| `data/database/dao/BudgetAdjustmentDao.kt` | 54, 57, 60 | **HIGH** |
| `data/database/dao/SavingsSweepPlanDao.kt` | 96, 102, 108, 114 | **HIGH** |
| `data/database/dao/SpendingPersonalityProfileDao.kt` | 71 | **HIGH** |
| `data/database/dao/RecommendationDao.kt` | 36, 56, 76, 108, 127, 141, 150, 186, 192 | **HIGH** |
| `data/database/dao/SubscriptionCandidateDao.kt` | 79, 90 | **HIGH** |
| `data/database/dao/SplitTemplateDao.kt` | 28 | **HIGH** |
| `data/database/dao/SplitItemAssignmentDao.kt` | 31 | **HIGH** |
| `data/database/dao/AiArtifactDao.kt` | 52, 55 | **HIGH** |

### 4.3 Database/Infrastructure layer violations

| FILE | LINE | USAGE | SEVERITY |
|------|------|-------|----------|
| `data/database/AppDatabase.kt` | 478, 1294 | Migration SQL timestamp | **LOW** |
| `data/backup/CostbackupBundle.kt` | 46, 66 | Backup timestamp | **LOW** |
| `data/backup/RestoreJournal.kt` | 41, 69 | Restore journal timestamp | **LOW** |
| `data/privacy/PrivacyAuditLoggerImpl.kt` | 22 | Audit log timestamp | **MEDIUM** |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | 554, 567, 949 | Staging/temp file naming | **LOW** |
| `data/ai/provider/DefaultAiEnvironmentMonitor.kt` | 62 | Environment timestamp | **LOW** |

### 4.4 Service/Worker/UI layer violations

| FILE | LINE | USAGE | SEVERITY |
|------|------|-------|----------|
| `service/warranty/WarrantyExpirationWorker.kt` | 81 | `val now = System.currentTimeMillis()` | **MEDIUM** |
| `service/reminder/DismissReminderReceiver.kt` | 39 | `val now = System.currentTimeMillis()` | **MEDIUM** |
| `service/reminder/SnoozeReminderReceiver.kt` | 39 | `val now = System.currentTimeMillis()` | **MEDIUM** |
| `ui/screens/debug/CategorizationDebugScreen.kt` | 79, 330 | Debug screen timestamps | **LOW** |
| `ui/screens/debug/DebugViewModel.kt` | 466 | Refresh signal timestamp | **LOW** |
| `ui/screens/debug/DebugViewerScreen.kt` | 156, 264, 400 | Clipboard debug data | **LOW** |
| `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt` | 461 | `referenceNowMillis` default param | **MEDIUM** |
| `ui/screens/home/HomeScreen.kt` | 1150 | `referenceNowMillis` default param | **MEDIUM** |

---

## 5. Old lifecycle paths (not using coordinators)

### 5.1 Direct PendingReviewDao.insert() — NOT FOUND ✅
Zero occurrences outside `ReviewQueueRepository`.

### 5.2 Direct RawNotificationDao.insert() — NOT FOUND ✅
Zero occurrences outside the notification pipeline.

### 5.3 Expense creation bypassing TransactionLifecycleCoordinator

✅ **No direct `expenseDao.insert(Expense(...))` calls found** — All expense creation goes through `TransactionLifecycleCoordinator.createExpense()`.

✅ Callers verified using the coordinator:
- `CsvExpenseImporter.kt:149`
- `BankApiIntegration.kt:179`
- `ReceiptRepository.kt:383`
- `ReviewQueueRepository.kt:205`
- `GroupTransactionCoordinator.kt:516`
- `ManualExpenseRepository.kt:74`
- `NotificationProcessingPipeline.kt:291`
- `EmailReceiptIngestionService.kt:109`
- `LegacyDataMigrationService.kt:80`

---

## 6. Screens that don't use new currency formatting

### 6.1 Hardcoded `€` symbol in UI

| FILE | LINE | CODE | SEVERITY |
|------|------|------|----------|
| `ui/screens/receiptscan/ReceiptScanScreen.kt` | 72-74 | `CurrencyFormatter.getCurrencySymbol(currencyCode ?: "EUR")` with doc: "defaulting to '€' (EUR)" | **MEDIUM** |
| `ui/components/CategoryDonutChart.kt` | 101 | Hardcoded `"Total €${String.format(...)}"` in content description | **MEDIUM** |
| `ui/screens/debug/DebugViewModel.kt` | 297-301 | Test/demo data strings with "€" — debug only | **LOW** |

### 6.2 `format(amount: Double` without `currency: String` parameter

**No exact violations found** — all format functions in UI screens either accept a currency parameter or use `CurrencyFormatter.format()` properly. ✅

---

## Summary of Severity Distribution

| SEVERITY | COUNT | KEY ACTIONS |
|----------|-------|-------------|
| **HIGH** | ~25+ | BankStatementLifecycleProcessor DAO bypass, hardcoded EUR in domain models/importers, SynthesisEngine unnormalized sums, TotalsAggregationEngine currency gap, DAO default `System.currentTimeMillis()` params, ExpenseDao deprecated SUM queries |
| **MEDIUM** | ~60+ | UI component hardcoded EUR defaults, analytics engine EUR defaults, ViewModel state defaults, raw amount arithmetic in budget engines, System.currentTimeMillis in workers/services |
| **LOW** | ~25+ | Debug-only violations, acceptable fallbacks, file naming timestamps, documented placeholders |

## Top 10 Critical Fixes Required

1. **`BankStatementLifecycleProcessor.kt:204`** — Replace `scannedReceiptDao.insert()` with delegation to `ReceiptRepository` or `ReceiptLifecycleCoordinator`
2. **`SynthesisEngine.kt:168,196,247,254`** — `PlannedExpense` has no currency field; sums mix currencies. Add currency field or normalize before summing.
3. **`TotalsAggregationEngine.kt`** — Documented gap: all aggregation methods lack currency normalization. Inject `AnalyticsCurrencyNormalizer`.
4. **`ExpenseDao.kt`** — All 68+ `SUM(${EFFECTIVE_AMOUNT_SQL})` queries return raw doubles without currency conversion. Use `MultiCurrencyRepository` instead.
5. **`CsvExpenseImporter.kt:141`** — Hardcoded `currency = "EUR"` should read from `CurrencySettingsRepository.homeCurrency()`.
6. **`data/repository/ReceiptRepository.kt`** — Multiple hardcoded `currency = "EUR"` assignments (lines 225, 275, 288, etc.).
7. **`data/repository/RecurringExpenseRepository.kt:32,70`** — Default param `currency: String = "EUR"` in repository methods.
8. **`domain/model/dashboard/DashboardPrimitives.kt:16`** — `DashboardExpense.currency: String = "EUR"` default defeats multi-currency.
9. **`WarrantyTrackerRepository.kt:365`** — Hardcoded `currency = "EUR"` when creating warranty receipt.
10. **DAO `System.currentTimeMillis()` defaults** — All DAO interfaces with `timestamp: Long = System.currentTimeMillis()` params (~20 occurrences) should accept `TimeProvider` or require caller to pass timestamp.
