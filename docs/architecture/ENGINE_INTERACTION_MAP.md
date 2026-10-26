# Engine Interaction Map

> **Last updated:** 2026-10-13 (schema/savings delta verified against code: DB v151; prior guarded-worker/guard verification retained)
>
> **Purpose:** Before fixing any engine, check this map to know which pipelines will be affected.  
> **Rule:** Any engine change requires verifying ALL affected pipelines still work.

---

## Engine → Pipeline Impact Matrix

| Engine | Pipelines Affected | Risk Level |
|--------|-------------------|------------|
| **CurrencyConverter** | 5 (Dashboard), 6 (Budget/Forecast/Cashflow), 12 (Export), Groups, Investment, Tax, `RateEligibilityEvaluator` (user-present rate eligibility, via `convertOutcome`) | 🔴 CRITICAL |
| **RateEligibilityEvaluator** (`domain/currency`) | Read-only rule owner for "does a stored rate qualify for a user-present save" (calendar date in an injected entry zone, age <= 7 days, older leg for via-EUR). Reads rates only through `CurrencyConverter.convertOutcome(TRANSACTION_DATE)`; no clock, network or logging. Follows the `CurrencyConverter` CRITICAL row: a converter or `ExchangeRateStore` lookup change affects its result. Siblings: `CurrencyAmountPolicy`, `CurrencySelectionPolicy`, `UserPresentRatePreview`. manual-entry-currency A1: no production consumer yet (A2 adds the coordinator consumer) | 🟡 HIGH |
| **MerchantNormalizer** | 1 (Notification dedupe), 2 (Transaction dedupe), 3 (Receipt matching), 4 (Recurring matching), 11 (Email), Analytics | 🔴 CRITICAL |
| **CategorizationEngine** | 1 (Auto-categorize), 3 (Receipt items), 11 (Email), Budget (category totals) | 🟡 HIGH |
| **ReceiptParser** | 3 (OCR), 11 (Email providers), 10 (Bank statement) | 🟡 HIGH |
| **MoneyAggregate/Builder** | 5 (Dashboard), 6 (Budget), 12 (Export), Analytics | 🟡 HIGH |
| **TimeProvider/TimePeriodUtils** | ALL pipelines (timestamps, periods, scheduling) | 🔴 CRITICAL |
| **PrivacyGate/CloudPayloadPolicy** | 1 (Notification), 3 (Receipt), 8 (AI), 10 (Bank), 11 (Email), 7 (Backup) | 🟡 HIGH |
| **TransactionSideEffectDispatcher** | 2 (Lifecycle), 4 (Recurring match), 5 (Budget recheck), Analytics | 🟡 HIGH |
| **WarrantyTextExtractor** | 3 (Receipt side effects) only | 🟢 LOW |
| **NotificationSubscriptionDetector** | 4 (Recurring detection) only | 🟢 LOW |
| **LocationBackfill/Geocoding** | Location enrichment only | 🟢 LOW |
| **NLP/AI Categorization** | 1 (Notification), 3 (Receipt), 11 (Email) | 🟡 HIGH |
| **InvestmentTracker** | Investment portfolio only | 🟢 LOW |
| **TaxEstimator** | Tax reports, Export | 🟢 LOW |
| **GroupTransactionCoordinator** | Groups, shared expenses, budget offsets (domain interface; atomic writes executed by `data/database/GroupTransactionCoordinator.kt`) | 🟡 HIGH |
| **DailyBucketEngine** | 5 (Dashboard), 6 (Budget), Analytics | 🟢 LOW |
| **BudgetVsActualEngine** | 5 (Dashboard), 6 (Budget), Analytics | 🟢 LOW |
| **AnalyticsInputAssembler** | 5 (Dashboard), 6 (Budget/Forecast), Analytics | 🟡 HIGH |
| **ReceiptMatchLifecycleService** | 3 (Receipt matching) only | 🟢 LOW |
| **RecurringRuleLifecycleCoordinator** | 4 (Recurring lifecycle), 2 (Transaction reconcile) | 🟡 HIGH |
| **RecurringLifecycleEventWriter** | 4 (Recurring), 7 (Backup audit) | 🟢 LOW |
| **InstallmentProjectionPort** (planned — loan-installments S6a port, S8b wiring; not in code yet) | Read-only projection of future SCHEDULED installment entries (sibling `InstallmentUpcomingProjection`; RECURRING_RULE contract unchanged). Wired readers: `CashFlowCalculator` (dated outflows), `ForecastInputAssembler` (projected entries; installment-linked history excluded from pattern baselines), `FinancialStressForecastEngine` (known bounded outflows), the recurring-sibling merge point at the Home upcoming items (`RecurringPlanProjectionService` itself unchanged); reminder reader: `BillReminderManager` / `BillReminderWorker` (per-entry delivery dedupe key). Owner of plan state and writes: `InstallmentPlanLifecycleCoordinator` (see LEGAL_PATHS "Installment Plan Mutations"). MUST NOT be reachable from any cloud payload assembly (briefing, insight, assistant inputs): guard `installment_cloud_reachability`. Any cash-flow/forecast output feeding a cloud payload is computed without this projection. A currency-mixed projection is converted by the engine or reported partial, never summed as home currency and never shown as 0 | 🟡 HIGH |
| **BillReminderWorker** | 4 (Reminder dispatch), BillReminderSettings runtime check | 🟢 LOW |
| **WorkerExecutionGuard** | ALL workers (all 10 CoroutineWorkers run through it, incl. `NotificationIntakeWorker` + snooze/dismiss reminder action workers), 7 (Backup restore gating) | 🟡 HIGH |
| **WorkerRegistry** | 12 (Startup scheduling), 7 (Backup resume via spec lookup) | 🟢 LOW |
| **SharedExpenseManager** | Groups, shared expenses (domain facade over `SharedExpenseDataPort` → `SharedExpenseDataPortAdapter` → `GroupTransactionCoordinator`; the GroupLifecycleCoordinator wrapper was removed, GR-14u36) | 🟡 HIGH |
| **GroupSettlementBalancePolicy** | Groups screen (displayed totals/balances, settlement plan gate), Settlements | 🟢 LOW |
| **GroupBalanceCalculator** | none in production (no caller; pending owner decision, Inc 3) | 🟢 LOW |
| **HybridRouter** | 8 (AI cloud/on-device routing), 10 (Bank statement), 3 (Receipt) | 🟡 HIGH |
| **AccountingExportPolicy** | 12 (Export), Tax reports | 🟢 LOW |
| **NetCashflowBalanceProvider** | 6 (Budget/Forecast/Cashflow) | 🟢 LOW |
| **AdvancedAnalyticsEngine** | 6 (Analytics views: category/merchant/pattern/statistical insights) — requires `NormalizedAnalyticsInput` | 🟡 HIGH |
| **TotalsAggregationEngine** | 5 (Dashboard totals) — aggregation routes through `MultiCurrencyRepository` | 🟡 HIGH |
| **MoneyNormalizationEngine** | Money pipelines (conversion outcomes, rate basis, bucket policy) via `domain/core/money/` | 🟡 HIGH |
| **ComparableHomeValue** | Transactions amount sort/filter/count in home currency (read-only rule owner in `domain/core/money/`; SQL mirror `ExpenseDao.COMPARABLE_HOME_VALUE_E_SQL`; parity-tested). Assistant queries (`getAssistantExpensesFiltered`/`getAssistantExpenseCountFiltered`) stay on raw `EFFECTIVE_AMOUNT_E_SQL`. Shared builder semantics: `ExpenseRepository.buildExpenseDynamicQueryParts` requires `AmountSemantics` (`RawEffective` for the assistant = byte-identical SQL/args, unescaped search; `HomeComparable(H)` = CV WHERE/ORDER BY + tiebreak + escaped search; `AmountOpsDisabled` = no amount SQL); excluded count = `ExpenseRepository.countExpensesWithoutHomeValue`. Consumer: `TransactionsViewModel` (ALL tab via `AmountSemantics`; time-range tabs in memory via `ComparableHomeValue`; reads repositories only, no writes); `Expense.normalizedEffectiveAmount` delegates to `ComparableHomeValue.snapshotValue` (date header only) | 🟡 HIGH |
| **roundSnapshot / CurrencyFractionDigits** (`domain/core/money`) | Sole snapshot rounding rule (HALF_EVEN on `BigDecimal.valueOf`, home minor-unit digits, one-minor-unit floor, `Unavailable(NON_FINITE_OR_NON_POSITIVE \| DIGITS_UNAVAILABLE)`) and the single minor-unit digits accessor; pure, no logging. Consumers (F9): `TransactionLifecycleCoordinator` is the sole consumer of `roundSnapshot` (create and `updateExpense` converted outcomes via `convertedSnapshot`: rounded amount + home currency + unrounded rate; Unavailable or no rate stores the sentinel `0.0`/`""`/`0.0` plus one best-effort `MISSING_RATE`, home Failed gives `HOME_CURRENCY_UNAVAILABLE`); `SourceCurrencyAllocation` delegates its digits lookup. No other code rounds a snapshot | 🟡 HIGH |
| **ReadHomeCurrencyState** (`CurrencySettingsRepository.observeReadHomeCurrency`) | Typed read-time home currency for Transactions amount ordering: `Loading` (consumer-prepended) / `Available(code)` / `Unavailable(reason)` with reason READ_ERROR, TIMEOUT or INVALID_CODE; bounded codes only, no raw reason text. `TransactionsViewModel` prepends `Loading`, applies a 5 s `TIMEOUT`, drops same-code `Available` repeats, and maps non-Available to `AmountOpsDisabled` (INV-9) | 🟡 HIGH |
| **SubscriptionManagerEngine** | Subscriptions (validate/create, price history, usage, candidates, health, savings) | 🟡 HIGH |
| **SynthesisEngine** | 6 (Month-end forecast, read-only) | 🟢 LOW |
| **BudgetForecastingEngine** | 5/6 (Budget forecast generation + `BudgetForecastDao` persistence via `insertForecast`/`insertWithDeactivation`) | 🟢 LOW |
| **NaturalLanguageSearchEngine** | NL query search (read-only; keyset `SearchCursor` pagination) | 🟢 LOW |
| **BudgetAutopilotEngine** | 6 (Budget autopilot correctness — historical category monthly spend read once via `MultiCurrencyRepository.getHistoricalCategoryMonthlySpend()` as typed `domain/core/money/CategoryMonthlySpend` rows, RP-08; the engine only reads budgets via `BudgetRepository.getActiveBudgets()` — apply writes go through `BudgetViewModel.applyAutopilotRecommendation()`/apply-all → `BudgetRepository.updateBudget()`/`updateBudgetOrThrow()`) | 🟡 HIGH |
| **SpendingPaceCalculator** | 5 (Dashboard spending pace via `ComputeDashboardWidgetsUseCase`, wired 0f1f587b), 6 (Analytics: canonical pace in `InsightsEngine`, `SpendingPersonalityClassifier`; consumed by `BudgetAutopilotEngine`) — read-only; expects caller-pre-normalized input via `AnalyticsCurrencyNormalizer` | 🟢 LOW |
| **AssetCleanupCoordinator** | 3 (Receipt uncommitted-asset cleanup, RP-12 12c) | 🟢 LOW |

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
  ├── TransactionLifecycleCoordinator (create-time snapshot; update re-snapshots against current H;
  │     converted amount rounded via roundSnapshot, rate stored unrounded, no-rate/rounding-Unavailable
  │     stores sentinel 0.0/""/0.0 + one best-effort MISSING_RATE)
  └── RateEligibilityEvaluator.evaluate() (convertOutcome, TRANSACTION_DATE basis; read-only;
        manual-entry-currency A1; A2: sole consumer in production is TransactionLifecycleCoordinator's
        flagged branch, taken only for CreateExpenseRequest.userPresentRate != null — ONE evaluate()
        read supplies eligibility and rate, snapshot = roundSnapshot(amount x Eligible.rate, home);
        refusal = ValidationFailed(rateRefusal) + CREATE_VALIDATION_FAILED + MANUAL_SAVE_BLOCKED_NO_RATE,
        never the sentinel; unflagged callers unchanged; no production setter until B1)
        └── UserPresentRatePreview (advisory preview; same roundSnapshot as the stored snapshot)
```

### MerchantNormalizer changes affect:
```
MerchantNormalizer.normalize()
  ├── TransactionLifecycleCoordinator (merchantKey generation)
  │     └── Duplicate detection (dedupeKey)
  ├── NotificationProcessingPipeline (parser merchant normalization)
  ├── ReviewQueueRepository (approval merchant key)
  ├── ReceiptTransactionMatcher (matching score)
  ├── RecurringLifecycleCoordinator (occurrence matching via MerchantKeyGenerator)
  ├── EmailReceiptIngestionService (expense creation)
  ├── MerchantKeyBackfillWorker (backfill existing rows via MerchantKeyGenerator)
  └── MerchantInsightEngine (merchant grouping via merchantKey)
```

### CategorizationEngine changes affect:
```
CategorizationEngine.categorize() / categorizeWithContext()
  ├── NotificationProcessingPipeline (auto-categorize)
  ├── ReviewQueueRepository (suggested category)
  ├── ReceiptItemCategorizationService (interface — Cloud/OnDevice/Hybrid variants)
  ├── EmailReceiptIngestionService (email expense category)
  ├── BudgetRepository (category budget matching)
  └── AdvancedAnalyticsEngine (category analytics)
```

### ReceiptMatchLifecycleService changes affect:
```
ReceiptMatchLifecycleService.saveMatchSuggestion() / rejectAllSuggestions() / clearMatchForReceipt()
  ├── ReceiptMatchingWorker (auto-match; durable outcome events)
  ├── ReceiptMatchingViewModel (suggestions; approval/rejection writes go
  │     through ReceiptLinkService.linkReceiptToExpense())
  └── ReceiptLifecycleEventTypes (MATCH_SUGGESTED / MATCH_APPROVED / MATCH_REJECTED / MATCH_CLEARED)
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
  ├── BillReminderWorker.sendNotification() → NotificationSendResult (private sealed interface)
  └── BillReminderSettingsRepository (runtime enabled/quiet hours check)
```

### InstallmentProjectionPort changes affect (planned — loan-installments S6a/S8b/S7b; not in code yet):
```
InstallmentProjectionPort (read-only; implemented over InstallmentPlanReadModel)
  ├── CashFlowCalculator (future SCHEDULED entries become dated outflows)
  ├── ForecastInputAssembler (projected entries; installment-linked expenses excluded from baselines)
  ├── FinancialStressForecastEngine (known bounded outflows)
  ├── Home upcoming items merge point (sibling of RecurringPlanProjectionService; its contract unchanged)
  ├── BillReminderManager / BillReminderWorker (installment due reminders, existing settings)
  └── NOT: any cloud payload assembly (HybridDashboardBriefingService inputs, DashboardBriefingInputBuilder,
      TransactionInsightInputBuilder, FinancialQueryInterpretationInputBuilder) — INV-31, enforced by the
      registered guard installment_cloud_reachability; payload inputs are computed without the projection
```

### TransactionLifecycleCoordinator → installment plan reaction (implemented — loan-installments S6c-1):
```
TransactionSideEffectPlanner.planUpdated() / planDeleted()
  └── installment_expense_changed / installment_expense_deleted (SideEffectCategory.INSTALLMENT_PLAN)
        → TLC PostCommitActionRunner (after commit; planned for update kinds FULL / TYPE / CURRENCY /
          TRANSFER_DETAILS / PAYMENT_CORE and for every delete; SPENDING_INCLUSION never reacts)
        → InstallmentPlanLifecycleCoordinator.onExpenseChanged / onExpenseDeleted (coordinator decides applicability)
        → plan Done.postCommit batches re-dispatched through the same runner (budget re-check etc.)
        Backstops: plan reconcile() before a .costbackup export (InstallmentPreBackupReconciler, implemented);
        reconcile at the start of each recordDueEntries run is PLANNED, binding on S7a (D-18 option (b));
        recordDueEntries does not exist yet. Until S7a, the only reconcile() caller is InstallmentPreBackupReconciler;
        restored or orphaned data is not repaired until the next .costbackup export or until S7a lands.
        Lost in-memory batches are backstopped by the next budget trigger
```

### Installment exclusion from recurring link / generation / pattern detection (implemented — S6c-2, S6c-2b, S6c-3):
```
RecurringInstallmentEligibility (domain/recurring; reads InstallmentEntryLinkReader, a domain/installment port)
  ├── RecurringLifecycleCoordinator.isExpenseEligibleForRecurring (auto-link, manual link, reconcileExpenseLinkAfterUpdate;
  │     reconcile unlinks an already linked expense that became installment-linked: INV-5 repair, Unlinked outcome)
  ├── OccurrenceConflictResolver.resolve (generateOccurrences, projectOccurrences, createRule, activateRule,
  │     topUpHorizon, updateRule; one batch read per resolve)
  └── RecurringExpenseEngine.detectPatterns (INV-29; single entry point to detectPatternsFromSnapshots)
        ├── MergedRecurringPatternsProvider.getPatterns() / getPatternsFromSnapshots
        ├── InsightsEngine (getPatternsFromSnapshots, two call sites)
        └── AnalyticsViewModel (getPatternsFromSnapshots)
Inherited consumer effects (no code change in the consumers):
  - FinancialHealthScoreV2 Bill Reliability: patterns no longer include installment-linked series
  - InsightsEngine anomaly suppression: installment merchants are no longer suppressed as recurring
    (OWNER decision pending whether that is wanted)
  - AnalyticsViewModel occurrence counts come from unfiltered snapshots (the exclusion applies to pattern
    detection only, not to counts)
  - ForecastInputAssembler baselines: its own installment baseline handling is S8b
Outside INV-29: the subscription-candidate detector (NotificationSubscriptionDetector) is not filtered (follow-up).
Known gap: the snapshot form cannot see the INSTALLMENT_PURCHASE / _REVERSED flag (effectiveAmount = 0 makes it harmless).
```

### WorkerExecutionGuard changes affect:
```
WorkerExecutionGuard.runGuardedWithContext() / runGuarded()
  ├── All 10 CoroutineWorkers (guard check at entry; allowlist is empty)
  │     ├── RestoreMaintenanceMode (block during restore)
  │     ├── WorkerRunLogger (start/success/skipped/retry/failure)
  │     ├── WorkerTimeoutPolicy (RETRY / PROPAGATE_CANCELLATION)
  │     ├── RetryableWorkerException (retry contract; sanitized reasonCode — PR12J-1)
  │     └── NotificationPermissionChecker (permission gating)
  └── PrivacyRuntimeWorkerPolicy (privacy-toggle gating)
```

### WorkerRegistry changes affect:
```
WorkerRegistry.scheduleAll() / entries
  ├── AppStartupCoordinator (startup scheduling)
  ├── RestoreMaintenanceMode (pause via WorkerSpec.DEFAULTS keys)
  ├── PrivacySettingsRepositoryImpl (resume via Entry.schedule lookup)
  └── WorkerSpecScheduler (centralized scheduling)
```

### SharedExpenseManager / GroupTransactionCoordinator changes affect:
```
SharedExpenseManager.createGroup() / addMember() / removeMember() / addExpense() / archiveGroup()
  ├── SharedExpenseDataPortAdapter (barrier-checked data-layer delegation)
  ├── GroupTransactionCoordinator (atomic group/member/expense writes via DomainTransactionRunner)
  ├── TransactionLifecycleCoordinator (createExpenseDbOnlyV2 + updateOwnershipDbOnlyV2)
  ├── PostCommitActionRunner (post-commit side effects)
  └── GroupSettlementBalancePolicy.displayedBalances (read-time display balances; uses SplitCalculator;
      GroupBalanceCalculator has no production caller, Inc 3)
```

### HybridRouter changes affect:
```
HybridRouter.execute()
  ├── HybridDashboardBriefingService (cloud/on-device/fallback)
  ├── HybridCategorizationAssistService
  ├── HybridDedupeJudgeService
  ├── HybridQueryInterpretationService
  ├── HybridReceiptAssistService
  ├── HybridReceiptItemCategorizationService
  ├── HybridReviewExplanationService
  └── CloudPayloadPolicy (pre-flight policy check)
```

### AccountingExportPolicy changes affect:
```
AccountingExportPolicy.validateGlobalDataset()
  ├── ExportOptionsViewModel (CSV/JSON export)
  ├── AccountingExportRepository (export stream)
  └── ExpenseExportMapper (export field mapping)
```

### PrivacyGate / CloudPayloadPolicy changes affect:
```
PrivacyGate.check(capability, context)
  ├── NotificationCaptureService (notification privacy)
  ├── Cloud providers (7 AI services — receipt assist, categorization, dedupe, etc.)
  ├── CompositeGeocodingService (all 4 geocoding providers)
  ├── DatabaseBackupRepositoryImpl (backup/export)
  ├── LocationBackfillWorker (background location)
  ├── DataRetentionWorker (data purging)
  └── DailyBriefingWorker (AI briefing)
  ↑ PrivacyDecision.FailClosed: 39 call sites across 27 files now use blocksExecution()
    (blocksExecution() metric; `PrivacyGate.check(...)` itself: 51 call sites
    across 34 production files — different metric, both current)
    which returns true for both Denied and FailClosed variants,
    providing consistent fail-closed behavior across all pipelines.
```

---

## Safe vs Dangerous Changes

### SAFE to change (isolated engines):
- WarrantyTextExtractor — only affects receipt side effects
- NotificationSubscriptionDetector — only affects recurring detection
- InvestmentTracker — isolated portfolio domain
- TaxEstimator — isolated tax reports
- LocationBackfill — isolated enrichment
- ReceiptMatchLifecycleService — isolated receipt matching domain
- BillReminderWorker — isolated reminder dispatch
- DailyBucketEngine — isolated daily bucket computation
- BudgetVsActualEngine — isolated budget comparison
- RecurringLifecycleEventWriter — isolated event writing
- WorkerRegistry — isolated worker registry
- GroupBalanceCalculator — isolated group balance computation
- AccountingExportPolicy — isolated export validation
- NetCashflowBalanceProvider — isolated forecast input
- SynthesisEngine — read-only month-end forecast computation
- NaturalLanguageSearchEngine — read-only NL query search

### DANGEROUS to change (shared engines):
- CurrencyConverter — verify dashboard, budget, forecast, export, cashflow
- MerchantNormalizer — verify dedupe, matching, analytics, recurring
- CategorizationEngine — verify notification, receipt, email, budget
- TimeProvider — verify ALL timestamp-dependent logic (enforced by G-TIME-01 guard `scripts/verify_time_boundaries.py`)
- PrivacyGate — verify ALL privacy-sensitive paths
- RecurringRuleLifecycleCoordinator — verify recurring, transaction reconciliation, reminder dispatch, backup
- WorkerExecutionGuard — verify ALL 10 CoroutineWorkers, backup restore gating, notification permission checking
- GroupTransactionCoordinator / SharedExpenseManager — verify groups, expenses, budget offsets, analytics
- HybridRouter — verify ALL AI hybrid services fed by the router
- AdvancedAnalyticsEngine — verify analytics UI consumers; requires `NormalizedAnalyticsInput` (assemble via `AnalyticsInputAssembler`)
- TotalsAggregationEngine — verify dashboard totals consumers
- MoneyNormalizationEngine — verify all money conversion/bucket-policy consumers in `domain/core/money/`
- SubscriptionManagerEngine — verify subscriptions, price history, usage, candidates, negotiation outcome writes

### VERY DANGEROUS to change (foundational):
- MoneyAggregate model — verify every consumer of financial totals
- ExpenseDao queries — verify every repository and coordinator
- Room migrations — verify backup/restore compatibility
- Hilt modules — verify entire DI graph
- PrivacyDecision sealed interface — adding/modifying variants affects 39+ callers using blocksExecution() and reason() across backup/export/geocoding/location/currency/warranty/AI pipelines
