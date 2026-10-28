# Engine Interaction Map

> **Last updated:** 2026-10-24 (app-lock-privacy-mode 1a: notification presentation, re-post and deep-link rows added; BillReminderWorker row updated). Earlier: 2026-10-13 (schema/savings delta verified against code: DB v151; prior guarded-worker/guard verification retained)
>
> **Purpose:** Before fixing any engine, check this map to know which pipelines will be affected.  
> **Rule:** Any engine change requires verifying ALL affected pipelines still work.

---

## Engine → Pipeline Impact Matrix

| Engine | Pipelines Affected | Risk Level |
|--------|-------------------|------------|
| **CurrencyConverter** | 5 (Dashboard), 6 (Budget/Forecast/Cashflow), 12 (Export), Groups, Investment, Tax | 🔴 CRITICAL |
| **MerchantNormalizer** | 1 (Notification dedupe), 2 (Transaction dedupe), 3 (Receipt matching), 4 (Recurring matching), 11 (Email), Analytics. `normalize()` can WRITE even with `autoCreate = false` (fuzzy match >= 0.95 inserts/updates a merchant_aliases row via `linkAliasToCanonical`, and logs the raw name); a merchant_canonicals row (`createNewMerchant`) only with `autoCreate = true`, so write-free callers (quick-add Fill) must not reach it | 🔴 CRITICAL |
| **CategorizationEngine** | 1 (Auto-categorize), 3 (Receipt items), 11 (Email), Budget (category totals), Add-Expense quick-add Fill (read-only consumer of the pure `normalizedCanonicalNameForMerchant()` only) | 🟡 HIGH |
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
| **BillReminderWorker** | 4 (Reminder dispatch), BillReminderSettings runtime check; builder owner 2 of the notification presentation (resolves the BILL decision, calls `NotificationPresentationApplier`); `internal const CHANNEL_ID` is imported (const-inlined) by `NotificationKindClassifier` | 🟡 HIGH |
| **NotificationService / AndroidNotificationService** (`domain/service`, `data/service`) | Kind-aware posts only (`*ForKind`, INV-12) from BudgetMonitor (BUDGET), AnomalyAlertOrchestrator (ANOMALY), DeliverProactiveBriefingNotificationUseCase (BRIEFING), WarrantyExpirationWorker (WARRANTY), ReceiptMatchingWorker (RECEIPT_MATCH); builder owner 1 (`postForKind` + `rebuildDiscreet` sweep). `DeliveryResult.PERMISSION_DENIED` is returned for a platform `SecurityException`; every caller must handle it explicitly. 4 constructor params (context, resolver, applier, `PrivacyDiagnosticsEmitter`) | 🟡 HIGH |
| **NotificationPresentationApplier / NotificationContentPolicy / NotificationPresentationResolver** | Every app notification of the six kinds (5 service callers + BillReminderWorker + the re-post sweep). Applier = the one place a decision becomes builder state (PRIVATE, generic public version, FULL/DISCREET text, BigText, action policy, kind marker). Policy = pure single owner of FULL/DISCREET and the action policy (BILL AUTH_REQUIRED on API 31+). Resolver = bounded 500 ms fail-closed read of `AppLockSettingsRepository`. Texts: `strings_notification_presentation.xml` (en/es/el) | 🟡 HIGH |
| **NotificationKindClassifier / NotificationKindMarker** | Re-post sweep only: marker first, channel / id-range fallback, other channels (incl. capture) skipped; marker value = `NotificationKind.stableToken` (renaming a constant must not change it) | 🟡 HIGH |
| **Post-commit discreet re-post** (`AppLockSettingsOperations.requestDiscreetChange` → `DiscreetRepostTrigger` → `NotificationService.repostActiveAsDiscreet()`; the 1a `UpdateDiscreetNotificationsUseCase` was retired in S20-d) | Runs only after a committed discreet write on the false → true edge; never on a failed / rejected write, never when turned off. Diagnostics: `NOTIF_REPOSTED_DISCREET(COUNT)` (at least one re-posted), `NOTIF_REPOST_FAILED(EXCEPTION_CLASS)` (per-notification failure, query failure, or a sweep failure caught at the trigger boundary). A sweep failure never fails the committed write. Called from `AppLockPrivacySettingsViewModel` in `@ApplicationScope` under `NonCancellable` with NO outer serializer: the domain-owned `AppLockSettingsWriteSerializer` (`domain/privacy/applock`, S18 ii-a) is taken inside `AppLockSettingsOperations` (S20-d) | 🟡 HIGH |
| **DeepLinkPolicy / DeepLinkApplier** (`ui/navigation`) + **AppLockDeepLinkRouter** (`ui/applock`, S21-b) | MainActivity deep-link intake (`handleIntent`, `onCreate` + `onNewIntent`), every notification tap (explicit MainActivity + ACTION_VIEW), `MarkDashboardBriefingOpenedUseCase` (the only link-reachable write), read-only `ExpenseRepository.getExpenseById`. Policy is pure; the applier alone navigates; the router is the only caller of the controller's link query / held-link release / release signal (a held link is memory-only and released by a posted `Dispatchers.Main` task once the lock is open) | 🟡 HIGH |
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
  └── TransactionLifecycleCoordinator (create-time snapshot; update re-snapshots against current H;
        converted amount rounded via roundSnapshot, rate stored unrounded, no-rate/rounding-Unavailable
        stores sentinel 0.0/""/0.0 + one best-effort MISSING_RATE)
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

NOTE: normalize() is NOT read-only, even with autoCreate = false. A fuzzy match with
confidence >= 0.95 calls linkAliasToCanonical (MerchantNormalizer.kt:98-103 →
MerchantNormalizationRepository.linkAliasToCanonical → DAO insert of a merchant_aliases row or
an occurrenceCount update on an existing one) and logs the raw name (Timber.w, :102, on a link
conflict). A merchant_canonicals row (createNewMerchant → repository.insertCanonical) is created
only when autoCreate = true (:111-114). The quick-add lookup (QuickAddLearnedCategoryLookup)
therefore never calls it; see CategorizationEngine below.
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

CategorizationEngine.normalizedCanonicalNameForMerchant()   [pure: canonicalize + Greeklish fold; no I/O]
  └── QuickAddLearnedCategoryLookup (nl-quick-add C2, domain/quickadd; READ-ONLY)
        → MerchantCategoryRepository.getCategoryByNormalizedCanonical(key)
        → AddExpenseViewModel.fill (fills the form only; Save persists via the unchanged manual path)
  Changing the key function changes which learned category quick-add finds (it must stay the single
  owner of key derivation). Reader: QuickAddLearnedCategoryLookupTest. Writer side: the user
  CategoryRepository.kt:87 (learning stores the key in merchant_categories.normalizedCanonicalName).
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
  ├── NotificationPresentationResolver.resolve(BILL, SDK_INT)   (per reminder, after claim + revalidation)
  ├── BillReminderWorker.sendNotification(…, decision) → NotificationSendResult (private sealed interface)
  │     └── NotificationPresentationApplier.apply(…)  (title/text, PRIVATE, public version, actions, BILL marker)
  └── BillReminderSettingsRepository (runtime enabled/quiet hours check)

Also affected by BillReminderWorker.CHANNEL_ID / BILL shape / Snooze+Dismiss action templates:
  NotificationKindClassifier (const-only import) and AndroidNotificationService.rebuildDiscreet
  (BILL_SHAPE, BILL_ACTION_TEMPLATES are duplicated there; BillNotificationSweepParityTest pins the two)
```

### NotificationPresentationApplier / NotificationContentPolicy changes affect:
```
AndroidNotificationService.postForKind
  ├── BudgetMonitor (BUDGET), AnomalyAlertOrchestrator (ANOMALY), DeliverProactiveBriefingNotificationUseCase (BRIEFING)
  ├── WarrantyExpirationWorker (WARRANTY), ReceiptMatchingWorker (RECEIPT_MATCH)
  └── DeliveryResult.PERMISSION_DENIED handling in every caller
AndroidNotificationService.rebuildDiscreet (re-post sweep: BILL via NotificationPresentationResolver.resolve for the action term, then NotificationContentPolicy.decideDiscreet; S18-ii-b)
BillReminderWorker.sendNotification
NotificationKindMarker (stamped by the applier, read by NotificationKindClassifier)
```

### Post-commit discreet re-post chain:
```
AppLockPrivacySettingsViewModel.setDiscreetNotifications  (@ApplicationScope, NonCancellable; NO outer serializer)
  └── AppLockSettingsOperations.requestDiscreetChange       (WRITE 5, S20-d; takes AppLockSettingsWriteSerializer inside)
        ├── AppLockSettingsRepository.getLoadState()          (before value)
        ├── AppLockSettingsRepository.setDiscreetNotificationsIfNotForced()   (Rejected(FORCED) while the lock is on)
        ├── AppLockController.onPlainSettingWritten(...)      (write-sequence counter, D6)
        └── on Committed → DiscreetRepostTrigger.onCommittedWrite(before, after)
              └── false → true only → NotificationService.repostActiveAsDiscreet()
                    ├── NotificationKindClassifier.classify() + NotificationKindMarker.read()
                    ├── SweepPacer.pause(SWEEP_REPOST_SPACING_MS = 300) before every re-post (cancellable; skipped ones not paused)
                    ├── BILL: NotificationPresentationResolver.resolve (bounded read; actionsSuppressed = actionPolicy != UNCHANGED)
                    ├── NotificationContentPolicy.decideDiscreet(kind, sdk, actionsSuppressed) → NotificationPresentationApplier
                    └── NOTIF_REPOSTED_DISCREET(COUNT) / NOTIF_REPOST_FAILED(EXCEPTION_CLASS)

AppLockPrivacySettingsViewModel.saveReview  (@ApplicationScope, NonCancellable; NO outer serializer; S20-e)
  └── AppLockSettingsOperations.requestReviewSave(D2 record)   (WRITE 2b; takes AppLockSettingsWriteSerializer inside)
        ├── weakening (lock off) from UNLOCKED: CHANGE prompt via AppLockController.startReviewSaveAttempt (STARTED)
        ├── AppLockSettingsRepository.reviewSave()            (the ONLY production caller of the conditional write)
        ├── AppLockController.onReviewSavedWithoutPrompt / onOpenOriginSettingApplied (state move, D6 sequence, D7 diagnostics)
        └── on Committed → DiscreetRepostTrigger.onCommittedWrite(false, after)
              └── same sweep as above (a committed review-save with discreet on sweeps; refused / failed never does)
AppLockPrivacySettingsViewModel.retry / refreshIfTransient → AppLockController.requestSettingsReread()  (S20-e, INV-47)
```

### App lock prompt and lifecycle chain (S20-a; LIVE since S20-c wires MainActivity):
```
MainActivity.onCreate (S20-c): secureWindow.applyInitial(window) → DB gate → binder.install(application) → promptHost.attach(this)
  → secureWindow.bind(this) → setContent { ... AppLockShell { MainScreen } }  (MainScreen only inside the gate's open branch)
  Changing MainActivity.onCreate order, the shell or the gate affects: MainScreenCallSiteTest, AppLockShellTest, AppLockGateSourceScanTest,
  SecureWindowController (single FLAG_SECURE writer), the R24 prompt-once claim, deep-link intake (S21 will gate handleIntent).
AppLockLifecycleBinder (Application.ActivityLifecycleCallbacks; exact started-activity count; sole INV-25 source)
  ├── 0→1 → AppLockController.onAppForegrounded()
  ├── 1→0 and !isChangingConfigurations → AppLockController.onAppBackgrounded()   (config-change stop/start pair not reported)
  └── install and every start while Resolving → AppLockController.resolve()  (@ApplicationScope)

AppLockController → DeviceAuthenticator (DI binding) → AppLockAuthenticatorRelay (weak holder, main thread only)
  └── BiometricPromptDeviceAuthenticator (one per Activity, built by AppLockPromptHost.attach)
        └── androidx.biometric BiometricPrompt
AppLockPromptHost (per Activity, ON_DESTROY)
  ├── relay.unregister(registration, isChangingConfigurations)
  │     └── lost attempt id (once, !isChangingConfigurations) → AppLockController.onPromptHostLost(id)
  └── relay.register on the recreated Activity → BiometricPromptDeviceAuthenticator.adopt(id, callback) (rotation)
Changing the relay, host or binder affects: AppLockController attempt closing (T9-T12, T35), T32-T34 prompt background,
  the adapter state machine (BiometricPromptAdapterStateMachineTest), AppLockRelaySourceScanTest (no worker/receiver/service reach).
Workers, receivers and services do NOT participate (INV-36).
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
  ├── HybridDashboardBriefingService (cloud/on-device/fallback; DASHBOARD_BRIEFING only - the per-transaction
  │     insight no longer uses it: GenerateTransactionInsightUseCase is on-device only via
  │     OnDeviceDashboardBriefingService, ai-fixes B2d)
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
  (AutoCreateWarrantyFromReceiptUseCase no longer calls PrivacyGate: ai-fixes B1a removed the
   CLOUD_AI_WARRANTY_EXTRACTION gate; warranty extraction is local-only and guarded upstream by
   ReceiptSideEffectPlanner Policy A. CloudWarrantyExtractionService has no production caller.)
  ↑ PrivacyDecision.FailClosed: 38 call sites across 26 files now use blocksExecution()
    (blocksExecution() metric; `PrivacyGate.check(...)` itself: 50 call sites
    across 33 production files — different metric; both reduced by one by ai-fixes B1a)
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
- BillReminderWorker — reminder dispatch (no longer fully isolated since app-lock 1a: shares the notification presentation applier and its CHANNEL_ID/shape with the sweep)
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
- NotificationPresentationApplier / NotificationContentPolicy / AndroidNotificationService — verify all six notification kinds (FULL and DISCREET), BillReminderWorker, the re-post sweep, the kind marker and the PERMISSION_DENIED handling of every caller
- DeepLinkPolicy / DeepLinkApplier — verify MainActivity intake, notification tap intents, the manifest filter and the DL_* diagnostics

### VERY DANGEROUS to change (foundational):
- MoneyAggregate model — verify every consumer of financial totals
- ExpenseDao queries — verify every repository and coordinator
- Room migrations — verify backup/restore compatibility
- Hilt modules — verify entire DI graph
- PrivacyDecision sealed interface — adding/modifying variants affects 39+ callers using blocksExecution() and reason() across backup/export/geocoding/location/currency/warranty/AI pipelines
