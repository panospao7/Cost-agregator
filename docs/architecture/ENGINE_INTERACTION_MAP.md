# Engine Interaction Map

> **Last updated:** 2026-10-28 (merge of feature/ai-evolution 4add2466 into feature/manual-entry-currency, NOT validated: BillReminderWorker row unions installment reminders (S7b) with the BILL presentation (D-M1); both mec and int rows kept). Earlier: 2026-10-24 (app-lock-privacy-mode 1a: notification presentation, re-post and deep-link rows added; BillReminderWorker row updated). Earlier: 2026-10-13 (schema/savings delta verified against code: DB v151; prior guarded-worker/guard verification retained)
>
> **Purpose:** Before fixing any engine, check this map to know which pipelines will be affected.  
> **Rule:** Any engine change requires verifying ALL affected pipelines still work.

---

## Engine → Pipeline Impact Matrix

| Engine | Pipelines Affected | Risk Level |
|--------|-------------------|------------|
| **CurrencyConverter** | 5 (Dashboard), 6 (Budget/Forecast/Cashflow), 12 (Export), Groups, Investment, Tax, `RateEligibilityEvaluator` (user-present rate eligibility, via `convertOutcome`) | 🔴 CRITICAL |
| **RateEligibilityEvaluator** (`domain/currency`) | Read-only rule owner for "does a stored rate qualify for a user-present save" (calendar date in an injected entry zone, age <= 7 days, older leg for via-EUR). Reads rates only through `CurrencyConverter.convertOutcome(TRANSACTION_DATE)`; no clock, network or logging. Follows the `CurrencyConverter` CRITICAL row: a converter or `ExchangeRateStore` lookup change affects its result. Siblings: `CurrencyAmountPolicy`, `CurrencySelectionPolicy`, `UserPresentRatePreview`. manual-entry-currency A1: no production consumer yet (A2 adds the coordinator consumer) | 🟡 HIGH |
| **MerchantNormalizer** | 1 (Notification dedupe), 2 (Transaction dedupe), 3 (Receipt matching), 4 (Recurring matching), 11 (Email), Analytics. `normalize()` can WRITE even with `autoCreate = false` (fuzzy match >= 0.95 inserts/updates a merchant_aliases row via `linkAliasToCanonical`, and logs the raw name); a merchant_canonicals row (`createNewMerchant`) only with `autoCreate = true`, so write-free callers (quick-add Fill) must not reach it | 🔴 CRITICAL |
| **CategorizationEngine** | 1 (Auto-categorize), 3 (Receipt items), 11 (Email), Budget (category totals), Add-Expense quick-add Fill (read-only consumer of the pure `normalizedCanonicalNameForMerchant()` only) | 🟡 HIGH |
| **ReceiptParser** | 3 (OCR), 11 (Email providers), 10 (Bank statement) | 🟡 HIGH |
| **MoneyAggregate/Builder** | 5 (Dashboard), 6 (Budget), 12 (Export), Analytics | 🟡 HIGH |
| **TimeProvider/TimePeriodUtils** | ALL pipelines (timestamps, periods, scheduling) | 🔴 CRITICAL |
| **PrivacyGate/CloudPayloadPolicy** | 1 (Notification), 3 (Receipt), 8 (AI), 10 (Bank), 11 (Email), 7 (Backup) | 🟡 HIGH |
| **TransactionSideEffectDispatcher** | 2 (Lifecycle), 4 (Recurring match), 5 (Budget recheck), Analytics | 🟡 HIGH |
| **WarrantyTextExtractor** | 3 (Receipt side effects) only | 🟢 LOW |
| **NotificationSubscriptionDetector** | 4 (Recurring detection) only; also reads installment link sets via RecurringInstallmentEligibility (S6c-F.2) | 🟢 LOW |
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
| **InstallmentProjectionPort** (implemented: BillReminderManager (S7b), CashFlowCalculator (S8b-2), FinancialStressForecastEngine (S8b-2), HomeInstallmentWeatherSource (S8b-3)) | Read-only projection of future SCHEDULED installment entries (sibling `InstallmentUpcomingProjection`; RECURRING_RULE contract unchanged). Wired readers: `CashFlowCalculator` (dated outflows), `FinancialStressForecastEngine` (known bounded outflows), `HomeInstallmentWeatherSource` → `HomeViewModel` weather-widget replace (never `UpcomingItems`; `RecurringPlanProjectionService` itself unchanged); reminder reader: `BillReminderManager` / `BillReminderWorker` (per-entry delivery dedupe key). Owner of plan state and writes: `InstallmentPlanLifecycleCoordinator` (see LEGAL_PATHS "Installment Plan Mutations"). MUST NOT be reachable from any cloud payload assembly (briefing, insight, assistant inputs): guard `installment_cloud_reachability`. Any cash-flow/forecast output feeding a cloud payload is computed without this projection. A currency-mixed projection is converted by the engine or reported partial, never summed as home currency and never shown as 0 | 🟡 HIGH |
| **BillReminderWorker** | 4 (Reminder dispatch), BillReminderSettings runtime check; installment reminders via BillReminderManager (S7b), BILL presentation applied (D-M1); builder owner 2 of the notification presentation (resolves the BILL decision, calls `NotificationPresentationApplier`); `internal const CHANNEL_ID` is imported (const-inlined) by `NotificationKindClassifier` | 🟡 HIGH |
| **NotificationService / AndroidNotificationService** (`domain/service`, `data/service`) | Kind-aware posts only (`*ForKind`, INV-12) from BudgetMonitor (BUDGET), AnomalyAlertOrchestrator (ANOMALY), DeliverProactiveBriefingNotificationUseCase (BRIEFING), WarrantyExpirationWorker (WARRANTY), ReceiptMatchingWorker (RECEIPT_MATCH); builder owner 1 (`postForKind` + `rebuildDiscreet` sweep). `DeliveryResult.PERMISSION_DENIED` is returned for a platform `SecurityException`; every caller must handle it explicitly. 4 constructor params (context, resolver, applier, `PrivacyDiagnosticsEmitter`) | 🟡 HIGH |
| **NotificationPresentationApplier / NotificationContentPolicy / NotificationPresentationResolver** | Every app notification of the six kinds (5 service callers + BillReminderWorker + the re-post sweep). Applier = the one place a decision becomes builder state (PRIVATE, generic public version, FULL/DISCREET text, BigText, action policy, kind marker). Policy = pure single owner of FULL/DISCREET and the action policy (BILL AUTH_REQUIRED on API 31+). Resolver = bounded 500 ms fail-closed read of `AppLockSettingsRepository`. Texts: `strings_notification_presentation.xml` (en/es/el) | 🟡 HIGH |
| **NotificationKindClassifier / NotificationKindMarker** | Re-post sweep only: marker first, channel / id-range fallback, other channels (incl. capture) skipped; marker value = `NotificationKind.stableToken` (renaming a constant must not change it) | 🟡 HIGH |
| **Post-commit discreet re-post** (`AppLockSettingsOperations.requestDiscreetChange` → `DiscreetRepostTrigger` → `NotificationService.repostActiveAsDiscreet()`; the 1a `UpdateDiscreetNotificationsUseCase` was retired in S20-d) | Runs only after a committed discreet write on the false → true edge; never on a failed / rejected write, never when turned off. Diagnostics: `NOTIF_REPOSTED_DISCREET(COUNT)` (at least one re-posted), `NOTIF_REPOST_FAILED(EXCEPTION_CLASS)` (per-notification failure, query failure, or a sweep failure caught at the trigger boundary). A sweep failure never fails the committed write. Called from `AppLockPrivacySettingsViewModel` in `@ApplicationScope` under `NonCancellable` with NO outer serializer: the domain-owned `AppLockSettingsWriteSerializer` (`domain/privacy/applock`, S18 ii-a) is taken inside `AppLockSettingsOperations` (S20-d) | 🟡 HIGH |
| **DeepLinkPolicy / DeepLinkApplier** (`ui/navigation`) + **AppLockDeepLinkRouter** (`ui/applock`, S21-b) | MainActivity deep-link intake (`handleIntent`, `onCreate` + `onNewIntent`), every notification tap (explicit MainActivity + ACTION_VIEW), `MarkDashboardBriefingOpenedUseCase` (the only link-reachable write), read-only `ExpenseRepository.getExpenseById`. Policy is pure; the applier alone navigates; the router is the only caller of the controller's link query / held-link release / release signal (a held link is memory-only and released by a posted `Dispatchers.Main` task once the lock is open). Installment hosts (`installments`, `installment_plan`) are not in `DeepLinkHost` until loan S13-a; the policy rejects them as UNKNOWN_HOST (Home) | 🟡 HIGH |
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
        never the sentinel; unflagged callers unchanged; setter #1 (ManualExpenseRepository) is active since B1;
        setter #2 (ReceiptScanViewModel.saveExpenseInternal, the only receipt-review site) is active since MEC C1:
        ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt maps a flagged ValidationFailed with a rateRefusal to
        the payload-free RateNotEligibleException; the email/batch receipt path stays unflagged)
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

### InstallmentProjectionPort changes affect (implemented: BillReminderManager (S7b), CashFlowCalculator (S8b-2), FinancialStressForecastEngine (S8b-2), HomeInstallmentWeatherSource (S8b-3)):
```
InstallmentProjectionPort (read-only; implemented over InstallmentPlanReadModel)
  ├── CashFlowCalculator (future SCHEDULED entries become dated outflows) — implemented by S8b-2 for calculateDailyCashFlow ONLY
  │     (INV-16 convertOutcome; a failed conversion omits the entry and sets isPartial); getUpcomingBills stays byte-identical,
  │     so the health score (OD-12) and SmartSavingsEngine stay unwired
  ├── ForecastInputAssembler — SUPERSEDED by A15: NOT a projection consumer and NOT modified (fenced below). The merge source is
  │     HomeInstallmentWeatherSource (S8b-3, data/repository, off the cloud surface): it feeds projected entries as MUST planned inputs
  │     into the UNMODIFIED assemble → SynthesisEngine.synthesize → NarrativeGenerator.generate (so the existing planned-expense
  │     currency normalisation applies, INV-16) and removes RecurringInstallmentEligibility.excludedSnapshotIds from the snapshots
  │     while entries are projected (INV-29); HomeViewModel merges it after ComputeDashboardWidgetsUseCase.compute()
  ├── FinancialStressForecastEngine (known bounded outflows) — implemented by S8b-2, Home-only (sole caller ComputeDashboardWidgetsUseCase);
  │     HZ01-B conversion; while at least one entry is projected, installment-linked history leaves the Monte Carlo baseline via
  │     RecurringInstallmentEligibility (INV-29 "global while projected"); SynthesisEngine is not edited
  ├── Home weather merge point — implemented by S8b-3 (behind HomeViewModel.installmentsEnabled, default InstallmentsFeature.ENABLED = true on this unreleased branch, owner-approvals 91(b)):
  │     HomeViewModel REPLACES the single FinancialWeatherWidget (HomeWeatherMerge) with HomeInstallmentWeatherSource's aware weather and
  │     exposes InstallmentUpcomingProjection rows (31 days, plan currency, sibling of RecurringPlanProjectionService, whose contract is
  │     unchanged) as a separate Home-only state; projections are never an UpcomingItem variant; invalidation = InstallmentPlanReadRepository.observeOverview
  ├── BillReminderManager / BillReminderWorker (installment due reminders, existing settings)
  └── NOT: any cloud payload assembly (HybridDashboardBriefingService inputs, DashboardBriefingInputBuilder,
      TransactionInsightInputBuilder, FinancialQueryInterpretationInputBuilder) — INV-31, enforced by the
      registered guard installment_cloud_reachability; payload inputs are computed without the projection.
      The guard also fences the cloud briefing chain files directly (S8b-1 / A15): FinancialWeatherRepository,
      ForecastInputAssembler, SynthesisEngine, NarrativeGenerator and DashboardDataProvider, plus (owner-approvals
      59a) the production adapter DashboardContractsAdapter that feeds them into the dashboard data (8 exact-file
      entries, 12 surface entries in total), so any projection-aware weather logic must live in a separate off-surface Home-only file
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
        reconcile at the start of each recordDueEntries run: coordinator part implemented in S7a-1 (D-18 option (b)),
        worker call site (S7a-2) pending. Until a caller exists, the only production reconcile() caller is
        InstallmentPreBackupReconciler; restored or orphaned data is not repaired until the next .costbackup export
        or until the S7a-2 worker lands.
        Lost in-memory batches are backstopped by the next budget trigger
```

### AddExpenseViewModel installment save → InstallmentPlanLifecycleCoordinator.declareWithNewExpense (loan-installments S11a; enabled by S13 on this unreleased branch, behind InstallmentsFeature.ENABLED = true, owner-approvals 91(b)):
```
AddExpenseViewModel.save() [installments ON]
  → ManualExpenseRepository.buildInstallmentPurchaseRequest (builder passes userPresentRate = null)
  → InstallmentPlanLifecycleCoordinator.declareWithNewExpense (INV-27 atomic: purchase, plan, entries; sets userPresentRate)
  → post-commit batches via InstallmentPostCommitDispatcher
  Never calls addManualExpense; plain save (installments OFF) is unchanged.
```

### InstallmentPlanLifecycleCoordinator.recordDueEntries and InstallmentCandidateChargeProbe (implemented — loan-installments S7a-1):
```
recordDueEntries(today, zone)   (callers S7a-2 worker and S10 overview: pending)
  ├── reconcile() first (D-18); its report and batches are carried in Done.postCommit for the caller to dispatch
  │     through InstallmentPostCommitDispatcher (single owner of the post-commit loop, S10b-1 F3; also used by
  │     InstallmentPreBackupReconciler and InstallmentEntryActionsViewModel; see LEGAL_PATHS "Installment Plan Mutations")
  ├── one writeTxn per due AUTO_RECORD entry:
  │     ├── InstallmentCandidateChargeProbe.hasCandidate (read-only boolean port, called inside the entry transaction;
  │     │     impl data/repository/InstallmentCandidateChargeProbeImpl: plan DAO reads + facts reader; shares the
  │     │     candidate scan with InstallmentPlanReadRepositoryImpl suggestions; no amount or id crosses the port)
  │     └── TransactionLifecycleCoordinator.createExpenseDbOnlyV2 (STRICT_EXTERNAL_ID) + entry claim in the same txn
  └── closed write barrier (DatabaseAccessBlockedException) stops the run: report.stoppedByBarrier = true, still
      Done(report, all batches so far), no throw (owner item 39(a)); the caller maps the marker to a retry
NOT: any DAO write from the probe; any cloud payload (INV-31); any worker scheduling (S7a-2)
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
  - ForecastInputAssembler baselines: unchanged (A15); the Home-only installment baseline exclusion lives in HomeInstallmentWeatherSource (S8b-3)
Direct consumers of RecurringInstallmentEligibility added in S6c-F.2 (INV-5, INV-29, decision 46(b); suppression by expense id, one batch read per call, no new DAO query):
  - InsightsEngine.findAnomalies: excludedSnapshotIds(safeExpenses) once; ids dropped from the merged result of BOTH
    anomaly paths (merchant multiplier + AnomalyDetector); baselines unchanged; a reader failure goes to the
    existing anomalies stage catch (no anomalies, fail-closed)
  - NotificationSubscriptionDetector.detectSubscriptions: excludedExpenseIds(...) once after the size check, filter,
    MIN_TRANSACTIONS re-check before grouping; the notification pipeline is not edited
Known gap: the snapshot form cannot see the INSTALLMENT_PURCHASE / _REVERSED flag (effectiveAmount = 0 makes it harmless).
Layer-1 type/ownership rule owner (S6c-F.3, owner item 37(b), decision 46(a)):
RecurringLinkTypeEligibility (domain/recurring; pure object, no I/O) is the SINGLE layer-1 rule.
  - mayCreateLink(type, isNotMine): !isNotMine && type not in {TRANSFER, DEPOSIT, UNKNOWN}; used for NEW links by
    OccurrenceConflictResolver.resolve, RecurringLifecycleCoordinator.linkExpenseToOccurrence (pre-check) and the gate
    isExpenseEligibleForRecurring(existingLink = false) (auto-link, manual link via claimLinkSuppressInWrite,
    detailed-link reason, reconcile new-link / relink)
  - mayKeepLink(type, isNotMine): !isNotMine && type not in {TRANSFER, DEPOSIT}; used for RE-VALIDATING an existing link
    (reconcileExpenseLinkAfterUpdate "still matches", which reconcileAllLinkedExpensesAfterBulkUpdate runs): an existing
    link on an UNKNOWN expense is KEPT (R2); a bulk edit never reverts PAID to PLANNED
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
- NotificationSubscriptionDetector — only affects recurring detection (S6c-F.2: also filters installment-linked / plan-purchase expenses through RecurringInstallmentEligibility, INV-29)
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
- DeepLinkPolicy / DeepLinkApplier — verify MainActivity intake, notification tap intents, the manifest filter and the DL_* diagnostics; loan S13-a adds the `installments` / `installment_plan` hosts (policy-flag gated, confirmation dialog via MainViewModel before any navigation, shown only after unlock) and BillReminderWorker N2 (installment-reminder release under NonCancellable, never replacing a CancellationException)

### VERY DANGEROUS to change (foundational):
- MoneyAggregate model — verify every consumer of financial totals
- ExpenseDao queries — verify every repository and coordinator
- Room migrations — verify backup/restore compatibility
- Hilt modules — verify entire DI graph
- PrivacyDecision sealed interface — adding/modifying variants affects 39+ callers using blocksExecution() and reason() across backup/export/geocoding/location/currency/warranty/AI pipelines
