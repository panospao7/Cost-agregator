## Technical Plan

### Scope
- In: all **HIGH** items in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` under `### B.6: Notification/Service/Worker Pipeline` (lines 368-384), limited to notification capture, recommendation state/persistence, briefing delivery/workers, notification ID allocation, duplicate-detection compliance, bill reminder / bank import semantics, prompt cooldown start, anomaly alert atomicity, and deep-link / navigation payload preservation.
- Out: all **MEDIUM/LOW** B.6 rows, Room schema/entity/migration work, boot/restart wakeup policy, parser thousand-separator fixes, widget/DataStore hardening, and any cross-pipeline opportunistic cleanup.
- Assumptions / unknowns:
  - `B.4` must be locally committed before B.6 execution begins; this file is preparatory until that gate opens.
  - B.6 should not require Room schema changes; if any fix appears to need entities/migrations/indices, stop and split rather than widening scope.
  - Some registry rows appear partially addressed by prior A.4 / A.8-era fixes (notably currency-aware duplicate paths and stale-refresh overwrite guards); those rows still need **live-file verification + targeted regression tests** before being marked resolved.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/service/RecommendationDismissalHandler.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/service/RecommendationStateManager.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/service/NotificationService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt`
- modify: `app/src/main/AndroidManifest.xml`
- modify: `app/src/test/java/com/yourname/expensetracker/service/NotificationFilterTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/service/RecommendationDismissalHandlerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/service/RecommendationStateManagerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/repository/RecommendationRepositoryTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/database/dao/RecommendationDaoTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/util/NotificationIdGeneratorTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineReliabilityTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeScreenWidgetTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-20.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-21.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-23.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md`
- create: `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/service/AndroidNotificationServiceTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStatementDuplicateTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/MainActivityDeepLinkTest.kt`
- create: `docs/reviews/REVIEW-B6.md`

### 1. Objective & Blast Radius
- **Core issue:** B.6 has no CRITICAL rows, but it has a wide set of HIGH reliability bugs where notification ingestion, background delivery, recommendation state, and navigation contracts can silently drift from persisted truth or user intent.
- **Blast radius:** notification listeners, dashboard recommendations, AI briefing delivery, warranty reminders, notification/review/statement duplicate checks, reminder + bank import semantics, prompt cooldown state, anomaly alerts, `MainActivity` deep-link handling, `HomeScreen` recommendation navigation, and the registry/final-verification trail.

### 2. The Single Source of Truth
- **Notification capture truth:** `NotificationFilter.shouldCapture()` decides whether a communication/email/SMS package is relevant; only deterministic finance-app package IDs may bypass heuristics.
- **Notification text truth:** `NotificationCaptureService` must preserve nullable / blank raw values until fallback resolution is complete; empty string is **not** equivalent to “field absent”.
- **Recommendation truth:** persistence in `RecommendationRepository` is authoritative; in-memory removal must not survive a failed archive, and same-user refreshes must not be blocked by blunt identity short-circuits.
- **Duplicate truth:** `DuplicateDetectionPolicy` + currency/type-aware DAO helpers are the only legal duplicate standard for notification auto-accept, statement import, and review approval.
- **Delivery truth:** briefing delivery is complete only when the notification service confirms dispatch; workers must time out and retry instead of converting failures to `Result.success()`.
- **Notification ID truth:** `NotificationIdGenerator` owns all band boundaries; warranty IDs must stay wholly inside the warranty band.
- **Recurrence / transaction truth:** use enums and established A.10 transaction-type semantics, not `frequency.name` string matching or `abs(amount)` logic that erases meaning.
- **Prompt truth:** cooldown starts when a prompt is actually emitted by `LifestyleSavingsPromptUseCase`, not only after a user acts on it.
- **Alert truth:** anomaly dedupe must be single-flight per expense inside the process; no double insert/send from concurrent `checkAndAlert()` calls.
- **Deep-link truth:** only hosts supported by `AndroidManifest.xml` + `MainActivity.handleIntent()` may be emitted; payloads must survive all the way to the destination screen.

> [!WARNING]
> - Do **not** touch B.6 MEDIUM/LOW rows in this plan.
> - Do **not** change Room entities, schemas, migrations, table names, or indices.
> - Do **not** refactor the whole navigation stack; preserve existing screen contracts and add the smallest payload plumbing needed.
> - If a row already appears compliant, prove it with a focused regression test and avoid production churn.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1 — Notification ingress correctness
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/service/NotificationFilterTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt`
- Checklist:
  - [ ] `NotificationFilter.kt`: split “always capture” finance-app package IDs from communication carrier packages (`Gmail`, `Viber`, SMS apps). Communication apps must go through heuristics instead of unconditional capture.
  - [ ] `NotificationCaptureService.kt`: stop calling `orEmpty()` before fallback decisions in both posted-notification and manual-refresh paths; keep the current dedupe hash stable by normalizing only at the final hash boundary.
  - [ ] `NotificationCaptureService.kt`: make `effectiveBigText` treat blank `bigText` as missing so `infoText` / `summaryText` can win.
  - [ ] `NotificationFilterTest.kt`: replace the old “Gmail always true” expectation with regressions proving bank-like Gmail/SMS content captures and unrelated personal content does not.
  - [ ] `NotificationCaptureServiceFallbackTest.kt`: add a focused regression for blank `bigText` falling back to `infoText`/`summaryText`.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.NotificationFilterTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.NotificationCaptureServiceFallbackTest"`
- Rollback / stop rule:
  - If the fix spills into `BootReceiver`, `ServiceRestartReceiver`, restart alarms, or parser package registry changes, stop and split; those are not HIGH B.6 scope.
- Done when:
  - Finance apps may still bypass heuristics, but Gmail/Viber/SMS no longer do.
  - Blank notification fields no longer block fallback text capture.

#### Batch 2 — Recommendation dismiss / refresh state consistency
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/service/RecommendationDismissalHandler.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/service/RecommendationStateManager.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/service/RecommendationDismissalHandlerTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/service/RecommendationStateManagerTest.kt`
- Checklist:
  - [ ] `RecommendationDismissalHandler.kt`: remove the “drop from memory first, just log repository failure” behavior; persistence must either happen first or trigger an explicit rollback/refresh path so UI and DB cannot diverge.
  - [ ] `RecommendationStateManager.kt`: same-user refreshes must re-query when invalidation/reload paths call `refreshForUser(userId)`; do not keep the current `currentUserId == userId && !forceRefresh` short-circuit as the blocking gate.
  - [ ] `RecommendationStateManager.kt`: preserve the existing generation-based stale publish guard; do not regress the concurrent overwrite fix while removing the bad same-user skip.
  - [ ] `RecommendationDismissalHandlerTest.kt`: add failure-path assertions proving failed archive does not leave UI permanently ahead of storage.
  - [ ] `RecommendationStateManagerTest.kt`: add regressions for same-user refresh after invalidation/reload and for stale in-flight refreshes still being rejected.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationDismissalHandlerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationStateManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationLifecycleManagerTest"`
- Rollback / stop rule:
  - If the fix requires changing `HomeViewModel` public flows or replacing the state owner outright, stop and split; keep this batch inside the existing recommendation service boundary.
- Done when:
  - Failed dismissals cannot leave persistent ACTIVE rows masked by local state.
  - Normal same-user invalidation / reload paths actually refresh.

#### Batch 3 — Recommendation active-set cap enforcement
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/database/dao/RecommendationDaoTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/RecommendationRepositoryTest.kt`
- Checklist:
  - [ ] `RecommendationDao.kt`: add the narrow DAO helper(s) needed to inspect/prune the full active set without relying on the existing `LIMIT 5` query.
  - [ ] `RecommendationRepository.kt`: change `saveAll()` so it enforces the **global** max-5 ACTIVE cap after merging existing ACTIVE rows with deduplicated new recommendations.
  - [ ] `RecommendationRepository.kt`: prune or archive overflow deterministically by priority + recency; do not let old ACTIVE rows accumulate off-screen.
  - [ ] `RecommendationDaoTest.kt` and `RecommendationRepositoryTest.kt`: add regressions proving more than five ACTIVE rows cannot survive repeated `saveAll()` calls.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.dao.RecommendationDaoTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.RecommendationRepositoryTest"`
- Rollback / stop rule:
  - If enforcing the cap appears to require schema changes, stop. B.6 must solve this with repository/DAO behavior only.
- Done when:
  - Every `saveAll()` leaves at most five ACTIVE recommendations per user in storage, not just in the latest insert batch.

#### Batch 4 — AI briefing delivery truth
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/service/NotificationService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/service/AndroidNotificationServiceTest.kt`
- Checklist:
  - [ ] `NotificationService.kt`: introduce the smallest possible AI-briefing delivery-result contract so the caller can distinguish “notification dispatched” from “returned early / not delivered”.
  - [ ] `AndroidNotificationService.kt`: return “not delivered” when notifications are disabled or the AI briefing path exits before `notify()`; return “delivered” only after dispatch completes.
  - [ ] `DeliverProactiveBriefingNotificationUseCase.kt`: record `lastDeliveredDashboardBriefingKey` only after positive delivery confirmation.
  - [ ] `DeliverProactiveBriefingNotificationUseCaseTest.kt`: unignore/fix the existing ignored regression and add a negative case for “notifications disabled / service returned false”.
  - [ ] `AndroidNotificationServiceTest.kt`: prove the service result contract around enabled vs disabled notifications.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCaseTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.service.AndroidNotificationServiceTest"`
- Rollback / stop rule:
  - Do not widen budget/anomaly notification APIs unless compile fallout forces a trivial companion update.
- Done when:
  - AI briefings are marked delivered only after actual notification dispatch.

#### Batch 5 — Daily briefing worker timeout / retry behavior
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
- Checklist:
  - [ ] `DailyBriefingWorker.kt`: wrap the generation + delivery path in an explicit timeout constant.
  - [ ] `DailyBriefingWorker.kt`: rethrow `CancellationException`, return `Result.retry()` for timeout/transient failures, and return `Result.success()` only after the bounded pipeline finishes.
  - [ ] `DailyBriefingWorkerTest.kt`: update the current “failure still returns success” expectations to retry/timeout semantics.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.worker.DailyBriefingWorkerTest"`
- Rollback / stop rule:
  - If the worker needs scheduler/work-request changes, defer them; B.6 only needs bounded execution + retry semantics here.
- Done when:
  - A transient stall or exception can no longer silently drop the day’s briefing as “success”.

#### Batch 6 — Warranty notification ID range repair
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/util/NotificationIdGeneratorTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`
- Checklist:
  - [ ] `NotificationIdGenerator.kt`: repartition the warranty band so 7-day and 30-day warranty IDs remain fully inside `10000-19999` and never overlap receipt IDs.
  - [ ] `NotificationIdGeneratorTest.kt`: remove the now-stale ignored range tests if the fix makes them valid, and add explicit non-overlap assertions against the receipt band.
  - [ ] `WarrantyExpirationWorkerTest.kt`: assert 30-day notifications stay below the receipt range boundary.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.util.NotificationIdGeneratorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.warranty.WarrantyExpirationWorkerTest"`
- Rollback / stop rule:
  - Do not renumber unrelated notification bands.
- Done when:
  - Warranty notification IDs are provably non-overlapping with receipt IDs.

#### Batch 7 — Notification auto-accept + review approval duplicate policy verification
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineReliabilityTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/ReviewQueueRepositoryTest.kt`
- Checklist:
  - [ ] Read first: confirm both files already use `DuplicateDetectionPolicy` + currency/type-aware DAO helpers. If compliant, keep production edits minimal and land only missing regressions.
  - [ ] `NotificationProcessingPipeline.kt`: remove any remaining blind duplicate fallback if one still exists in the auto-accept or pending-review path.
  - [ ] `ReviewQueueRepository.kt`: keep approval duplicate checks fully currency-aware and type-aware.
  - [ ] `NotificationProcessingPipelineReliabilityTest.kt` + `ReviewQueueRepositoryTest.kt`: add/keep regressions proving same merchant/date/amount with different currency is **not** treated as a duplicate.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.NotificationProcessingPipelineReliabilityTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.ReviewQueueRepositoryTest"`
- Rollback / stop rule:
  - Do not change the canonical A.4 policy constants or DAO SQL shape unless a live blind path is still present.
- Done when:
  - Notification auto-accept and review approval are both proven currency-aware end-to-end.

#### Batch 8 — Statement import duplicate policy verification
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStatementDuplicateTest.kt`
- Checklist:
  - [ ] Read first: confirm statement import duplicate checks pass currency and type through both prefetched and transactional branches.
  - [ ] `ReceiptRepository.kt`: patch any remaining blind branch only if the live file still deviates.
  - [ ] `ReceiptRepositoryStatementDuplicateTest.kt`: add a narrow regression proving same merchant/date/amount in different currency does not collapse during statement import.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.ReceiptRepositoryStatementDuplicateTest"`
- Rollback / stop rule:
  - If the only remaining gap lives in OCR/parser normalization, defer it to B.3/B.11 instead of widening this batch.
- Done when:
  - Statement import is also demonstrably currency-aware.

#### Batch 9 — Recurrence and bank-transaction semantics
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt`
- Checklist:
  - [ ] `BillReminderManager.kt`: replace string comparisons on `frequency.name` with enum-driven branches; explicitly handle `ANNUALLY`, `SEMI_ANNUALLY`, and `IRREGULAR` in both next-date and monthly-total logic.
  - [ ] `BankApiIntegration.kt`: preserve transaction meaning from bank movements instead of forcing `PURCHASE + abs(amount)`; if `BankTransaction` needs a local type/direction field, extend it **inside this file** rather than spreading changes across the app.
  - [ ] `BankApiIntegration.kt`: mirror existing A.10 transaction-type semantics already used elsewhere in the app; do not invent a bank-only sign convention.
  - [ ] Add focused tests covering annual/semiannual/irregular reminders and debit/credit/transfer/refund bank mappings.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.bank.BankApiIntegrationTest"`
- Rollback / stop rule:
  - Do not drag in sync scheduling, token refresh, or `shouldSync()` LOW-scope work.
- Done when:
  - Reminder calculations no longer depend on invalid enum names.
  - Bank imports no longer convert every credit/debit into a positive purchase expense.

#### Batch 10 — Lifestyle prompt cooldown start
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt`
- Checklist:
  - [ ] `LifestyleSavingsPromptUseCase.kt`: record prompt impression when `evaluateAndPrompt()` decides to emit a recommendation.
  - [ ] Keep acceptance/dismissal/deferral tracking as separate follow-up actions; do not collapse them into impression tracking.
  - [ ] `LifestyleSavingsPromptUseCaseTest.kt`: add a positive regression for `recordPrompt()` on non-null output and negative regressions for all null/suppressed paths.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCaseTest"`
- Rollback / stop rule:
  - If call frequency shows this use case is invoked repeatedly before UI render, stop and split the impression-recording boundary rather than baking double-counting into domain code.
- Done when:
  - Cooldown starts when the prompt is shown, not only after user action.

#### Batch 11 — Anomaly alert atomic single-flight
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt`
- Checklist:
  - [ ] `AnomalyAlertOrchestrator.kt`: make the “already alerted?” check and insert/send path single-flight per expense within the process (prefer keyed mutex / local guard; avoid schema changes).
  - [ ] Preserve existing cooldown and severity rules.
  - [ ] `AnomalyAlertOrchestratorTest.kt`: add a concurrency regression proving two simultaneous `checkAndAlert()` calls for the same expense produce one insert and one notification.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestratorTest"`
- Rollback / stop rule:
  - Do not add entity/index/migration work to solve this.
- Done when:
  - Concurrent anomaly checks cannot double-insert or double-send for the same expense.

#### Batch 12 — In-app recommendation payload preservation
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeScreenWidgetTest.kt`
- Checklist:
  - [ ] `HomeScreen.kt`: stop dropping `NavigationAction.ToAnalytics(period)` / `ToMap(location)` payloads; callback signatures must carry the actual period/location values.
  - [ ] `MainActivity.kt`: store pending analytics/map payloads in narrow UI state, pass them to the destination tab, and clear them after first consumption.
  - [ ] `AnalyticsScreen.kt`: consume an initial period exactly once and map only the supported resolver outputs.
  - [ ] `SpendingMapScreen.kt`: consume an initial merchant/location hint exactly once and safely focus/select a matching marker or show a non-crashing fallback message.
  - [ ] `HomeScreenWidgetTest.kt`: update source-level assertions so payload forwarding is locked in.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.home.HomeScreenWidgetTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.home.HomeViewModelRecommendationTest"`
- Rollback / stop rule:
  - If this starts turning into a full navigation-architecture rewrite, stop and split; B.6 only needs payload preservation, not a new nav system.
- Done when:
  - Recommendation-driven analytics/map navigation carries payloads all the way to the destination screen.

#### Batch 13 — Anomaly notification deep-link normalization
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
  - modify: `app/src/main/AndroidManifest.xml`
  - create: `app/src/test/java/com/yourname/expensetracker/ui/MainActivityDeepLinkTest.kt`
- Checklist:
  - [ ] `AndroidNotificationService.kt`: stop emitting orphaned anomaly URLs unless the app truly supports them; prefer a host that `MainActivity` and the manifest already own.
  - [ ] `AndroidManifest.xml` + `MainActivity.kt`: keep the manifest/handler host list aligned with emitted links and parse any optional anomaly query payload defensively.
  - [ ] `MainActivityDeepLinkTest.kt`: prove anomaly deep links route into a supported in-app destination without crashing on missing/invalid payloads.
  - [ ] Keep AI briefing deep links untouched except for any harmless shared helper extraction.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.MainActivityDeepLinkTest"`
- Rollback / stop rule:
  - If exact expense highlighting requires a large transaction-detail feature, land the supported-host fix only and document the remaining enhancement separately.
- Done when:
  - Anomaly notifications no longer point at unsupported hosts.

### 4. Verification Plan
- **Per-batch minimum gate:** `./gradlew.bat :app:compileDebugKotlin`.
- **Serialized verification lane:** per the playbook, B.6 verification must run one lane at a time; do not overlap long Gradle runs with other active Phase B pipelines.
- **Targeted class lane after each batch:** run only that batch’s focused tests listed above.
- **Final B.6 verification lane (after all batches are complete):**
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.NotificationFilterTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.NotificationCaptureServiceFallbackTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationDismissalHandlerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationStateManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.RecommendationLifecycleManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.database.dao.RecommendationDaoTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.RecommendationRepositoryTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.ai.usecase.DeliverProactiveBriefingNotificationUseCaseTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.service.AndroidNotificationServiceTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.worker.DailyBriefingWorkerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.util.NotificationIdGeneratorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.warranty.WarrantyExpirationWorkerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.NotificationProcessingPipelineReliabilityTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.ReviewQueueRepositoryTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.ReceiptRepositoryStatementDuplicateTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.reminder.BillReminderManagerTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.bank.BankApiIntegrationTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCaseTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.alerts.AnomalyAlertOrchestratorTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.home.HomeScreenWidgetTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.home.HomeViewModelRecommendationTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.MainActivityDeepLinkTest"`
- **Read-back verification requirement:** after each micro-batch, re-read every changed file and confirm imports/signatures stayed local to the batch.
- **Completion gate:** B.6 is not complete until reviewer PASS, registry updates, final-verification updates, and documented evidence/waivers are all in place.

### 5. Documentation & Registry Updates
- After reviewer PASS, update `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` in the `### B.6: Notification/Service/Worker Pipeline` section:
  - mark each HIGH row resolved with `[RESOLVED BY B.6]`, or
  - if a row turned out to be already-fixed in live code, document it as “verified compliant during B.6” rather than silently rewriting history.
- Update exact final-verification files referenced by the registry batches for B.6 HIGH rows:
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-20.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-21.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-23.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md`
- Also verify whether the `BillReminderManager` row must update `FINAL-VERIFICATION-BATCH-43.md`, because the registry text cites `B43` even though the B.6 header list omits it.
- Create/update `docs/reviews/REVIEW-B6.md` with:
  - batch-by-batch file list,
  - targeted test evidence,
  - any “already compliant, test-only” dispositions,
  - any explicit waivers or deferrals.
- Documentation order must follow the playbook:
  1. Master Registry
  2. exact final-verification batch files
  3. matching deep-analysis mirror rows only if they exist and only after the first two are updated.

### Risks
- Several B.6 HIGH rows look partially fixed already; careless churn can regress A.4/A.8 behavior.
- The recommendation-cap fix is easy to get wrong because the existing DAO read path is already limited to 5 rows.
- Changing notification delivery contracts can ripple outside the AI briefing path if not kept narrow.
- Navigation/deep-link payload fixes can balloon into a full navigation rewrite unless strictly constrained to existing tabs/screens.
- Bank import semantics are under-modeled in the current placeholder API; keep any model extension local to `BankApiIntegration.kt` and its tests.
- Anomaly dedupe must be solved without schema work; choose a process-local single-flight solution unless existing storage already supports stronger guarantees.

### Acceptance Criteria
- [ ] No B.6 HIGH row still depends on unconditional Gmail/Viber/SMS capture or blank `bigText` masking fallback text.
- [ ] Recommendation dismiss + refresh paths cannot leave in-memory state ahead of persistent truth.
- [ ] `RecommendationRepository.saveAll()` cannot leave more than five ACTIVE rows for a user.
- [ ] AI briefing delivery is recorded only after confirmed notification dispatch.
- [ ] `DailyBriefingWorker` uses timeout/retry semantics instead of converting all failures to success.
- [ ] Warranty 7-day and 30-day IDs stay inside a non-overlapping warranty band.
- [ ] Notification auto-accept, statement import, and review approval are all proven currency-aware.
- [ ] `BillReminderManager` uses enum-driven recurrence handling and `BankApiIntegration` preserves transaction meaning.
- [ ] `LifestyleSavingsPromptUseCase` starts cooldown when the prompt is emitted.
- [ ] `AnomalyAlertOrchestrator` cannot double-send for the same concurrent expense.
- [ ] Recommendation analytics/map navigation payloads and anomaly deep links survive end-to-end.
- [ ] `:app:compileDebugKotlin` and the targeted B.6 test lane pass, and B.6 docs are updated in the required order.
