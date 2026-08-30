# Deprecation Escalation Changelog (`DeprecationLevel.ERROR`)

**Process rule (PR-GR-10a):** every escalation of a Kotlin declaration to
`@Deprecated(..., level = DeprecationLevel.ERROR)` in production source
(`app/src/main/java`) must be announced in this ledger **before it lands**.
An ERROR level turns any remaining call site into a `compileDebugKotlin`
breakage (rounds R12/R13 zeroed all Kotlin validation this way), so the
escalation and its migration path must be on record up front. The enforcing
guard `scripts/ci/verify_deprecation_escalations.py` (registered
`deprecation_escalations`, blocking) checks **presence** of a matching row —
file + symbol + date + reason + migration target — for every live
ERROR-deprecation fingerprint, and flags rows whose site no longer exists as
stale for cleanup. The guard enforces presence, not the approval flow behind
an entry; review approval of the escalation itself happens in the PR.

**Fingerprint semantics:** `(file, declaration name)`. Overloads of the same
name in the same file share one fingerprint and one row. Rows are seeded
(back-filled) at guard introduction on 2026-08-30 with the escalation reason
and migration target recorded in each site's deprecation message.

## Escalation ledger

| File | Symbol | Date | Reason | Migration target |
| --- | --- | --- | --- | --- |
| app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt | getMerchantAnalytics | 2026-08-30 | Legacy self-fetching overload reads raw repository data instead of normalized inputs | getMerchantAnalytics(input, historicalInput, limit) |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalSpentFlow | 2026-08-30 | Raw Double SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategorySpentInPeriod | 2026-08-30 | Raw Double SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategorySpentInPeriodFlow | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategorySpentTotalsInPeriod | 2026-08-30 | Raw Double SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalSpentBetween | 2026-08-30 | Raw SUM across mixed currencies; TaxEstimator must use currency-aware totals (P5-P1-5) | MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getEffectiveSpentBetweenForCategory | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getMonthlySpendingTotals | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getMonthlySpendingTotalsBetween | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getMonthlySpendingTotalsByCategoryBetween | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getMerchantTotalsBetween | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategoryTotalsBetween | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getSpendingDailyTotalsBetween | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalForPeriod | 2026-08-30 | Raw Double SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository.getHomeCurrencyPurchaseTotal() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategoryTotalsForPeriod | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getDailyTotalsForPeriod | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalDepositsForPeriod | 2026-08-30 | Raw deposit SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware deposit aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalDeposits | 2026-08-30 | Raw deposit SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware deposit aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getLocatedMerchantTotals | 2026-08-30 | Raw SUM across mixed currencies for located expenses | getLocatedMerchantTotalsByCurrency() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getWeeklyTotalsForPeriod | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getMonthlyTotalsForPeriod | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getDailyTotalsWithDatesForPeriod | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getAverageDailySpend | 2026-08-30 | Raw AVG over mixed-currency daily sums (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getCategoryBreakdown | 2026-08-30 | Raw SUM across mixed currencies without conversion (P5-P1-5) | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getTotalBusinessExpensesBetween | 2026-08-30 | Raw SUM across mixed currencies for business expenses | getBusinessExpensesBetweenByCurrency() |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getBusinessExpensesByCategory | 2026-08-30 | Raw SUM across mixed currencies for business categories | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | getBusinessExpensesByProject | 2026-08-30 | Raw SUM across mixed currencies for business projects | MultiCurrencyRepository currency-aware aggregation |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | createExpenseFromReceipt | 2026-08-30 | Non-atomic receipt expense creation; receipt lifecycle owns the atomic path | ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt(request) |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | clearMatchForReceipt | 2026-08-30 | Match mutations are receipt-lifecycle-owned | ReceiptMatchLifecycleService.clearMatchForReceipt() |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | exportParserDebugData | 2026-08-30 | Debug OCR export moved behind the exporter boundary | ReceiptDebugExporter.exportParserDebugData() |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | debugReceipt | 2026-08-30 | Debug OCR access moved behind the exporter boundary | ReceiptDebugExporter.debugReceipt() |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | saveMatchSuggestion | 2026-08-30 | Match mutations are receipt-lifecycle-owned | ReceiptMatchLifecycleService |
| app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt | rejectAllSuggestions | 2026-08-30 | Match mutations are receipt-lifecycle-owned | ReceiptMatchLifecycleService |
| app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt | deleteAll | 2026-08-30 | Dangerous bulk delete also wiped imported expense data | deleteAllNotifications() or targeted cleanup |
| app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt | getHomeCurrencyTotal | 2026-08-30 | Type-agnostic latest-rate aggregation with ambiguous rate semantics | getHomeCurrencyPurchaseTotalHistorical() or getHomeCurrencyPurchaseTotal() |
| app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt | getHomeCurrencyWeeklyTotals | 2026-08-30 | Type-agnostic latest-rate weekly totals include deposits and transfers | getWeeklyAggregatesHistorical(startDate, endDate) |
| app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt | getHomeCurrencyDailyTotals | 2026-08-30 | Type-agnostic latest-rate daily totals include deposits and transfers | getDailyAggregatesHistorical(startDate, endDate) |
| app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt | getHomeCurrencyPurchaseTotalHistorical | 2026-08-30 | Throws when home currency is unavailable; callers must handle explicitly | getHomeCurrencyPurchaseTotalHistoricalResult() |
| app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt | write | 2026-08-30 | Legacy single-arg write bypasses the transaction context (interface + override share the fingerprint) | write(context, event) inside DomainTransactionRunner.runInTransaction |
| app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt | createExpenseFromReceipt | 2026-08-30 | Permanently disabled legacy path; atomic combined API replaces it | createExpenseAndLinkReceipt(request) |
| app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt | markBillPaid | 2026-08-30 | Removed fake paid-marking; bills must become real expenses through the transaction lifecycle | Create expense via TransactionLifecycleCoordinator then RecurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId) |
| app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt | reconcilePlannedVsActual | 2026-08-30 | Split into explicit occurrence generation plus report steps | ensureOccurrencesGeneratedForReconciliation() + calculatePlannedVsActualReport() |
| app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt | write | 2026-08-30 | Legacy single-arg write bypasses the transaction context (interface + override share the fingerprint) | write(context, event) inside DomainTransactionRunner.runInTransaction |
| app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt | createExpense | 2026-08-30 | V1 create API superseded by explicit V2 side-effect APIs (both overloads share the fingerprint) | createExpenseStandaloneV2() or createExpenseDbOnlyV2() |
