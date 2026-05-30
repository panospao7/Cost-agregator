# Engine Interaction Map

> **Purpose:** Before fixing any engine, check this map to know which pipelines will be affected.  
> **Rule:** Any engine change requires verifying ALL affected pipelines still work.

---

## Engine → Pipeline Impact Matrix

| Engine | Pipelines Affected | Risk Level |
|--------|-------------------|------------|
| **CurrencyConverter** | 5 (Dashboard), 6 (Budget/Forecast/Cashflow), 12 (Export), Groups, Investment, Tax | 🔴 CRITICAL |
| **MerchantNormalizer** | 1 (Notification dedupe), 2 (Transaction dedupe), 3 (Receipt matching), 4 (Recurring matching), 11 (Email), Analytics | 🔴 CRITICAL |
| **CategorizationEngine** | 1 (Auto-categorize), 3 (Receipt items), 11 (Email), Budget (category totals) | 🟡 HIGH |
| **ReceiptParser** | 3 (OCR), 11 (Email providers), 10 (Bank statement) | 🟡 HIGH |
| **MoneyAggregate/Builder** | 5 (Dashboard), 6 (Budget), 12 (Export), Analytics | 🟡 HIGH |
| **TimeProvider/TimePeriodUtils** | ALL pipelines (timestamps, periods, scheduling) | 🔴 CRITICAL |
| **PrivacyGate/CloudPayloadPolicy** | 1 (Notification), 3 (Receipt), 8 (AI), 10 (Bank), 11 (Email), 7 (Backup) | 🟡 HIGH |
| **TransactionSideEffectDispatcher** | 2 (Lifecycle), 4 (Recurring match), 5 (Budget recheck), Analytics | 🟡 HIGH |
| **WarrantyExtractor** | 3 (Receipt side effects) only | 🟢 LOW |
| **SubscriptionDetector** | 4 (Recurring detection) only | 🟢 LOW |
| **LocationBackfill/Geocoding** | Location enrichment only | 🟢 LOW |
| **NLP/AI Categorization** | 1 (Notification), 3 (Receipt), 11 (Email) | 🟡 HIGH |
| **InvestmentTracker** | Investment portfolio only | 🟢 LOW |
| **TaxEstimator** | Tax reports, Export | 🟢 LOW |
| **GroupTransactionCoordinator** | Groups, shared expenses, budget offsets | 🟡 HIGH |
| **DailyBucketEngine** | 5 (Dashboard), 6 (Budget), Analytics | 🟢 LOW |
| **BudgetVsActualEngine** | 5 (Dashboard), 6 (Budget), Analytics | 🟢 LOW |
| **AnalyticsInputAssembler** | 5 (Dashboard), 6 (Budget/Forecast), Analytics | 🟡 HIGH |
| **ReceiptMatchLifecycleService** | 3 (Receipt matching) only | 🟢 LOW |
| **RecurringRuleLifecycleCoordinator** | 4 (Recurring lifecycle), 2 (Transaction reconcile) | 🟡 HIGH |
| **RecurringLifecycleEventWriter** | 4 (Recurring), 7 (Backup audit) | 🟢 LOW |
| **BillReminderWorker** | 4 (Reminder dispatch) | 🟢 LOW |

---

## Detailed Impact Chains

### CurrencyConverter changes affect:
```
CurrencyConverter.convert() / convertAsOf()
  ├── MultiCurrencyRepository (all aggregate totals)
  │     ├── Dashboard spending summary
  │     ├── Dashboard category breakdown
  │     ├── Dashboard weekly/daily/monthly drilldown
  │     ├── Budget spent calculation
  │     └── TotalsAggregationEngine
  ├── AnalyticsCurrencyNormalizer (per-row normalization)
  │     ├── AdvancedAnalyticsEngine
  │     ├── ForecastInputAssembler
  │     └── ComputeDashboardWidgetsUseCase (spending trend)
  ├── CashFlowCalculator (daily balance)
  ├── FinancialStressForecastEngine (starting balance)
  ├── BudgetForecastingEngine (limit conversion)
  ├── ExpenseExportMapper (conversion audit fields)
  └── TransactionLifecycleCoordinator (create-time snapshot)
```

### MerchantNormalizer changes affect:
```
MerchantNormalizer.normalize()
  ├── TransactionLifecycleCoordinator (merchantKey generation)
  │     └── Duplicate detection (dedupeKey)
  ├── NotificationProcessingPipeline (parser merchant normalization)
  ├── ReviewQueueRepository (approval merchant key)
  ├── ReceiptTransactionMatcher (matching score)
  ├── RecurringLifecycleCoordinator (occurrence matching)
  ├── EmailReceiptIngestionService (expense creation)
  ├── MerchantKeyBackfillWorker (backfill existing rows)
  └── AnalyticsEngine (merchant grouping)
```

### CategorizationEngine changes affect:
```
CategorizationEngine.classify()
  ├── NotificationProcessingPipeline (auto-categorize)
  ├── ReviewQueueRepository (suggested category)
  ├── ReceiptItemCategorizationService (line items)
  ├── EmailReceiptIngestionService (email expense category)
  ├── BudgetRepository (category budget matching)
  └── AnalyticsEngine (category analytics)
```

### ReceiptMatchLifecycleService changes affect:
```
ReceiptMatchLifecycleService.saveMatchSuggestion() / approveMatchSuggestion()
  ├── ReceiptMatchingWorker (auto-match)
  ├── ReceiptMatchingViewModel (user-match UI)
  └── ReceiptEvent (MATCH_SUGGESTED / MATCH_APPROVED / MATCH_REJECTED / MATCH_CLEARED)
```

### RecurringRuleLifecycleCoordinator changes affect:
```
RecurringRuleLifecycleCoordinator.createRule() / updateRule() / deactivateRule() / deleteRule()
  ├── ManualRecurringExpenseRepository (rule CRUD delegation)
  ├── RecurringExpenseRepository (delegation)
  ├── RecurringOccurrenceDao (indirect via transaction)
  ├── RecurringReminderDeliveryDao (indirect via transaction)
  ├── PlannedExpenseDao (indirect via transaction)
  └── RecurringLifecycleEventWriter (critical + diagnostic events)
```

### BillReminderWorker changes affect:
```
BillReminderWorker.doWork()
  ├── RecurringLifecycleCoordinator.getDispatchableClaimedReminder()
  ├── NotificationManager.sendNotification() → NotificationSendResult
  └── BillReminderSettingsRepository (runtime enabled/quiet hours check)
```

---

## Safe vs Dangerous Changes

### SAFE to change (isolated engines):
- WarrantyExtractor — only affects receipt side effects
- SubscriptionDetector — only affects recurring detection
- InvestmentTracker — isolated portfolio domain
- TaxEstimator — isolated tax reports
- LocationBackfill — isolated enrichment
- ReceiptMatchLifecycleService — isolated receipt matching domain
- BillReminderWorker — isolated reminder dispatch
- DailyBucketEngine — isolated daily bucket computation
- BudgetVsActualEngine — isolated budget comparison
- RecurringLifecycleEventWriter — isolated event writing

### DANGEROUS to change (shared engines):
- CurrencyConverter — verify dashboard, budget, forecast, export, cashflow
- MerchantNormalizer — verify dedupe, matching, analytics, recurring
- CategorizationEngine — verify notification, receipt, email, budget
- TimeProvider — verify ALL timestamp-dependent logic
- PrivacyGate — verify ALL privacy-sensitive paths
- RecurringRuleLifecycleCoordinator — verify recurring, transaction reconciliation, reminder dispatch, backup

### VERY DANGEROUS to change (foundational):
- MoneyAggregate model — verify every consumer of financial totals
- ExpenseDao queries — verify every repository and coordinator
- Room migrations — verify backup/restore compatibility
- Hilt modules — verify entire DI graph
