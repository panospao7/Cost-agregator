# Final Verification — Batch 20: Services & Receivers

## Scope
- `com/yourname/expensetracker/service/NotificationCaptureService.kt`
- `com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
- `com/yourname/expensetracker/service/NavigationTargetResolver.kt`
- `com/yourname/expensetracker/service/RecommendationCacheService.kt`
- `com/yourname/expensetracker/service/RecommendationDeduplicator.kt`
- `com/yourname/expensetracker/service/RecommendationDismissalHandler.kt`
- `com/yourname/expensetracker/service/RecommendationLifecycleManager.kt`
- `com/yourname/expensetracker/service/RecommendationStateManager.kt`
- `com/yourname/expensetracker/service/TransactionFilterSerializer.kt`
- `com/yourname/expensetracker/service/NotificationFilter.kt`
- `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `com/yourname/expensetracker/data/repository/NotificationRepository.kt`
- `com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
- `com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt`
- `com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`
- `com/yourname/expensetracker/data/database/entity/RawNotification.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `com/yourname/expensetracker/domain/engine/DashboardFollowThroughEngine.kt`
- `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
- `com/yourname/expensetracker/domain/model/recommendation/RecommendationPriority.kt`
- `com/yourname/expensetracker/domain/parser/AppParserRegistry.kt`
- `com/yourname/expensetracker/domain/service/NotificationService.kt`
- `com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`
- `com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
- `com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
- `com/yourname/expensetracker/receiver/BootReceiver.kt`
- `com/yourname/expensetracker/receiver/ServiceRestartReceiver.kt`
- `com/yourname/expensetracker/ui/MainActivity.kt`
- `com/yourname/expensetracker/ui/mappers/TransactionFilterUiMapper.kt`
- `com/yourname/expensetracker/ui/navigation/NavigationDestination.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
- `com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`
- `com/yourname/expensetracker/service/RecommendationInvalidator.kt`

Additional non-`app/src/main/java` file checked during verification: `app/src/main/AndroidManifest.xml`.

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/service/NotificationFilter.kt:9-22,52-55` | High | Privacy / filtering | `MONITORED_PACKAGES` unconditionally whitelists Gmail, Viber, and SMS apps, so unrelated personal messages bypass heuristics and are stored in the raw-notification pipeline. | R | CONFIRMED | Split financial apps from communication apps and require content-based financial checks for inbox/chat packages. |
| 2 | `com/yourname/expensetracker/service/NotificationFilter.kt:35,57-59` | Medium | Heuristic logic | `shouldCapture()` lowercases the content, but `REGEX_CURRENCY` only matches uppercase `EUR/USD/GBP/CHF`, so unknown-package discovery misses lowercase currency codes. | D | DOWNGRADED | Make the currency regex case-insensitive or lowercase the pattern too. |
| 3 | `com/yourname/expensetracker/service/NotificationCaptureService.kt:213-215,237,270,323-325,335` | High | Parsing logic | `title/text/bigText` are normalized with `orEmpty()` before `processNotification()`, so `effectiveBigText = bigText ?: infoText ?: summaryText` never falls back when `bigText` is absent and amount-bearing extras are ignored. | R | CONFIRMED | Preserve nullable extras, or treat blank strings as absent before the fallback chain. |
| 4 | `com/yourname/expensetracker/service/NotificationCaptureService.kt:236-238,297-302,371-383` | Medium | Lifecycle / data loss | `onDestroy()` cancels `serviceJob` immediately, so an in-flight `processNotification()` coroutine can be aborted before `repository.processAndSave()` completes and the notification is dropped. | D | DOWNGRADED | Drain or persist in-flight work before cancellation, or move persistence off the cancellable service scope. |
| 5 | `com/yourname/expensetracker/service/RecommendationDismissalHandler.kt:19-33` | High | State consistency | Dismissal removes the card from in-memory state first and only logs repository failures, so a failed archive leaves the DB active and the recommendation reappears on the next refresh. | B | CONFIRMED | Persist first, or roll back/retry the optimistic state change when `repository.dismiss()` fails. |
| 6 | `com/yourname/expensetracker/service/RecommendationStateManager.kt:49-77` | High | State freshness | `refreshForUser()` is skipped for the already-active user unless `forceRefresh=true`, so normal invalidation/cleanup paths do not load newly generated recommendations or drop stale ones. | R | CONFIRMED | Remove the same-user short-circuit and replace it with request coalescing or explicit in-flight refresh tracking. |
| 7 | `com/yourname/expensetracker/service/RecommendationStateManager.kt:63-69` | High | Ordering logic | Recommendations are sorted with `compareByDescending { it.priority }`, which uses enum ordinal order and places `LOW` ahead of `HIGH`. | D | CONFIRMED | Use a stable numeric rank (`HIGH > MEDIUM > LOW`) consistently with the repository/DAO ordering. |
| 8 | `com/yourname/expensetracker/service/RecommendationStateManager.kt:122-127` | Medium | Multi-user state | `clearForUser(userId)` always clears `_recommendations`, even when the cleared user is not the currently displayed user. | R | DOWNGRADED | Only clear in-memory state when `currentUserId == userId`; otherwise clear repository/cache state only. |
| 9 | `com/yourname/expensetracker/service/RecommendationDeduplicator.kt:77-105` | Medium | Deduplication logic | `computeSignature()` always includes `rec.category`, so merchant-target recommendations with the same effective filter but different originating categories are treated as distinct cards. | R | CONFIRMED | Build target-specific signatures and only include category when the target semantics actually depend on category. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/repository/RecommendationRepository.kt:74-103` / `com/yourname/expensetracker/data/database/dao/RecommendationDao.kt:21-35` | High | Limit enforcement | `saveAll()` limits only the new batch, not the total active set. Existing active recommendations are never evicted when new ones are inserted, so the repository can accumulate more than 5 `ACTIVE` rows even though the feature contract says the active limit is 5. | Enforce the cap transactionally against `existing + new` recommendations: merge, sort once, keep the top 5, and archive/delete the rest. |
| 2 | `com/yourname/expensetracker/service/RecommendationStateManager.kt:49-77` | Medium | Concurrency / stale result | `refreshForUser()` launches independent background loads and always publishes their result. If two refreshes race (for different users or contexts), a slower stale request can overwrite the newer state. | Serialize refreshes, cancel superseded jobs, or check `currentUserId` before assigning `_recommendations.value`. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Reviewer #3 / Debugger #1 / Debugger #16 | `NotificationCaptureService.kt:226-248` | All accesses to `processedNotifications` and `processCount` occur from service/main-thread callbacks in this class; the background coroutines never touch that cache. The claimed concurrent map mutation / `removeIf` crash path is not substantiated by the actual threading model here. |
| 2 | Reviewer #8 / Debugger #7 | `RecommendationCacheService.kt:56-83` | `RecommendationCacheService.getById()` is not referenced anywhere under `app/src/main/java`; the reported stale-overwrite/coalescing problem is speculative hardening, not a verified runtime defect in the current codebase. |
| 3 | Debugger #5 | `RecommendationStateManager.kt:43,50-51,109` | The report focuses on adding `@Volatile` to `currentUserId`, but the real bug is refresh sequencing/suppression, not a demonstrated stale-read failure. `@Volatile` alone would not fix the observable state issues. |
| 4 | Debugger #8 | `NotificationCaptureService.kt:222` | The extra `orEmpty()` calls are redundant, but redundancy by itself is not a bug. The real defect is the confirmed nullable-fallback breakage above. |
| 5 | Debugger #10 / Debugger C3 | `NotificationCaptureService.kt:69` | The report's `NotificationIdGenerator.forBudget()` collision example does not match current call sites, and an actual collision requires another notification path to emit the exact ID `1001`. That is too speculative to treat as a verified current bug. |
| 6 | Debugger #11 / Debugger #12 | `RecommendationStateManager.kt:83-97,122-131` | Missing logging is an observability concern, but it is not a separate verified runtime defect here. The real functional problems are the confirmed dismissal/state and clear-for-user bugs. |
| 7 | Debugger #13 | `RecommendationCacheService.kt:38-42` | This report entry explicitly describes an invariant that is already being maintained (all access is under the mutex), so it is not an issue. |
| 8 | Debugger #15 | `AndroidNotificationService.kt:97` | `targetKey` is only produced internally as `dashboard_home:yyyy-MM-dd`; the claimed URI query-parameter injection path does not exist in the current codebase. |
| 9 | Reviewer #14 | `DeliverProactiveBriefingNotificationUseCase.kt:43-45` | Using `targetKey.hashCode()` is inconsistent with the notification-ID helper, but the report's overwrite claim is only theoretical in the current usage pattern (one internal key format, one daily briefing). It is not a verified current bug. |
| 10 | Debugger C4 | `RecommendationDismissalHandler.kt` ↔ `RecommendationStateManager.kt` | No call site invokes both dismissal paths. The current Home flow uses `RecommendationDismissalHandler.dismiss()` only, so the claimed "double dismissal" pipeline does not occur. |
| 11 | Debugger C5 | `RecommendationLifecycleManager.kt:34-43` ↔ `RecommendationStateManager.kt:49-77` | At most this is redundant I/O, and often it is skipped entirely by the same-user refresh guard. It is not a meaningful defect compared with the confirmed expiration/refresh problems. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Recommendation generation → persistence → UI state | High | Reactivity / stale UI | New recommendations are generated and saved asynchronously, but the UI state is one-shot loaded from `RecommendationStateManager` and same-user refreshes are suppressed. Newly inserted cards often never surface automatically. | `data/repository/NotificationProcessingPipeline.kt`, `data/repository/RecommendationRepository.kt`, `service/RecommendationStateManager.kt`, `ui/screens/home/HomeViewModel.kt` | Make the state manager collect `RecommendationRepository.observeActiveForUser()` for the active user, or trigger a guaranteed refresh after `saveAll()` completes. |
| 2 | Repository dedupe ↔ in-memory dedupe | High | Deduplication mismatch | `RecommendationRepository.saveAll()` compares new recommendations with existing DB rows using a different signature algorithm than `RecommendationDeduplicator`, so cross-run duplicate recommendations are not filtered reliably. | `data/repository/RecommendationRepository.kt`, `service/RecommendationDeduplicator.kt` | Centralize canonical signature generation and reuse the same algorithm for both new and persisted recommendations. |
| 3 | Repository/DAO ordering ↔ state-manager ordering | High | Priority inconsistency | The repository/DAO rank `HIGH > MEDIUM > LOW`, but `RecommendationStateManager` sorts by raw enum order in reverse, so the UI can show a different top-5 set than persistence selected. | `service/RecommendationStateManager.kt`, `data/repository/RecommendationRepository.kt`, `data/database/dao/RecommendationDao.kt` | Use one shared priority-rank function across DAO, repository, and state manager. |
| 4 | Periodic lifecycle cleanup → repository cleanup | Medium | Expiration / cleanup | The periodic lifecycle loop calls `cleanupExpired()`, but repository cleanup only deletes rows already marked `EXPIRED`; it never marks time-expired `ACTIVE` rows first. | `service/RecommendationLifecycleManager.kt`, `data/repository/RecommendationRepository.kt`, `data/database/dao/RecommendationDao.kt` | Add a bulk expire step before deletion, or make cleanup update and delete expired rows in one DAO path. |
| 5 | Anomaly notification → deep link routing | High | Navigation | Anomaly notifications deep-link to `expensetracker://transaction/{id}`, but the manifest does not declare that host and `MainActivity.handleIntent()` does not route it. Tapping the alert cannot reach the intended destination. | `data/service/AndroidNotificationService.kt`, `ui/MainActivity.kt`, `app/src/main/AndroidManifest.xml` | Add the `transaction` deep link and route it, or reuse an already-supported destination with the expense ID as a parameter. |
| 6 | Recommendation navigation payload → UI callbacks | Medium | Context loss | `NavigationAction.ToAnalytics(period)` and `ToMap(location)` carry context, but `HomeScreen`/`MainActivity` drop the payload and only switch tabs, so recommendation-specific period/location context never reaches the destination. | `service/NavigationTargetResolver.kt`, `ui/screens/home/HomeScreen.kt`, `ui/MainActivity.kt`, `ui/navigation/NavigationDestination.kt` | Extend the navigation contract so analytics/map destinations accept and consume payload parameters end-to-end. |
| 7 | Notification listener keep-alive | Medium | Performance / battery | The listener is force-started at boot and re-started every minute via a repeating alarm even when access is missing or the listener is already healthy. | `service/NotificationCaptureService.kt`, `receiver/BootReceiver.kt`, `receiver/ServiceRestartReceiver.kt` | Remove the fixed keep-alive alarm and rely on `requestRebind()` plus bounded retry/backoff only after real disconnects. |

## Summary
- Total verified issues: 9
- Confirmed: 9 (Critical: 0, High: 5, Medium: 4, Low: 0)
- False positives: 11
- Missed issues found: 2
- Files affected: 17/36

## Key Patterns
- The recommendation subsystem lacks a single authoritative reactive source of truth: generation, deduplication, ranking, cleanup, and UI presentation all apply different rules.
- Several recommendation APIs are multi-user on paper but not safely sequenced in practice, so refresh and clear operations can publish stale or incorrect state.
- Notification handling is still too broad and too platform-fragile: intake is over-permissive, nullable text extraction is brittle, and published deep links are not always actually routable.
