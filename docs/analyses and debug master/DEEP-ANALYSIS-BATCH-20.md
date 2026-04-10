# Deep Analysis — Batch 20: Services & Receivers (@reviewer)

## Scope
- Primary batch files:
  - `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/NavigationTargetResolver.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/RecommendationCacheService.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/RecommendationDeduplicator.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/RecommendationDismissalHandler.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/RecommendationLifecycleManager.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/RecommendationStateManager.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/TransactionFilterSerializer.kt`
  - `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`
- Auxiliary files read for pipeline verification:
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/database/dao/RawNotificationDao.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/engine/DashboardFollowThroughEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/parser/AppParserRegistry.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/service/NotificationService.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`
  - `app/src/main/java/com/yourname/expensetracker/receiver/BootReceiver.kt`
  - `app/src/main/java/com/yourname/expensetracker/receiver/ServiceRestartReceiver.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/mappers/TransactionFilterUiMapper.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `service/NotificationFilter.kt:9-22,52-55` | HIGH | Privacy / filtering | `MONITORED_PACKAGES` includes broad communication apps (`Gmail`, `Viber`, SMS apps) and `shouldCapture()` returns `true` for them before any amount/keyword check. That sends unrelated personal messages into the raw-notification capture pipeline. | Split strict financial packages from communication channels; only unconditional-allow bank/wallet apps and keep heuristic/content checks for inbox/chat packages. |
| 2 | `service/NotificationCaptureService.kt:213-215,270,323-325` | HIGH | Parsing logic | `title/text/bigText` are converted with `orEmpty()` before `processNotification()`. Because `bigText` is then never `null`, `effectiveBigText = bigText ?: infoText ?: summaryText` never falls back to `infoText`/`summaryText`, so notifications whose real amount sits in those extras are misparsed. | Preserve nullable extras, or normalize with `takeIf { it.isNotBlank() }` before the fallback chain. |
| 3 | `service/NotificationCaptureService.kt:226-247` | HIGH | Thread safety | Dedup uses a `synchronizedMap`, but the read-check-write sequence and `entries.removeIf` cleanup are not atomic. Concurrent binder callbacks can double-process the same notification, and cleanup can iterate while another thread mutates the map. | Replace with a `ConcurrentHashMap`/atomic `compute` strategy, or wrap the full check/update/cleanup section in explicit synchronization. |
| 4 | `service/RecommendationDismissalHandler.kt:20-33` | HIGH | State consistency | Dismissal removes the card from in-memory state first, then swallows repository failures. If persistence fails, the UI says the card is dismissed but the database still has it, so it reappears on the next reload/app start. | Return a result, retry/queue the persistence write, or roll back the optimistic state removal when `repository.dismiss()` fails. |
| 5 | `service/RecommendationStateManager.kt:49-77` | HIGH | State freshness | `refreshForUser()` is skipped whenever the same user is already active unless `forceRefresh=true`. Normal refresh calls from lifecycle/invalidation paths therefore fail to load newly generated recommendations or drop stale ones. | Remove the same-user short-circuit, or replace it with in-flight request coalescing instead of suppressing refreshes. |
| 6 | `service/RecommendationStateManager.kt:122-127` | HIGH | Multi-user logic | `clearForUser(userId)` always empties `_recommendations`, even when the cleared user is not the currently displayed user. Clearing background account data can therefore blank the active account's UI. | Only clear in-memory state when `currentUserId == userId`; otherwise only clear repository/cache data for the target user. **[RESOLVED BY A.8]** |
| 7 | `service/RecommendationDeduplicator.kt:80-105` | MEDIUM | Dedup logic | `computeSignature()` always includes `rec.category` before examining filter semantics. Merchant/recent-spending recommendations with the same effective target but originating from different categories will not deduplicate and can produce duplicate cards. | Build target-specific signatures: use category only for category/budget targets, and key merchant/recent recommendations from the parsed filter fields. |
| 8 | `service/RecommendationCacheService.kt:56-79` | LOW | Performance | `getById()` checks the cache under one lock, releases it, fetches from the repository, then locks again to store the result. Concurrent misses for the same ID are not coalesced, so the same recommendation can be fetched repeatedly. | Add in-flight request coalescing or hold a single keyed critical section for miss/fill operations. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Description | Suggested Fix |
|---|----------|----------|-------------|---------------|
| 9 | `NotificationProcessingPipeline.kt:626-642` → `RecommendationRepository.saveAll()` → `RecommendationStateManager.kt:35-41,49-77` → `HomeViewModel.kt:147-166,332-336` | HIGH | Recommendation generation is fire-and-forget, but UI state is not driven from `RecommendationRepository.observeActiveForUser()`. The home screen only does manual one-shot refreshes, and same-user refreshes are suppressed, so newly created recommendation cards often never surface automatically. | Make `RecommendationStateManager` collect the repository flow for the active user, or trigger a guaranteed refresh after `saveAll()` completes. |
| 10 | `RecommendationRepository.kt:84-89,109-115` + `RecommendationDeduplicator.kt:77-105` | HIGH | In-batch dedupe uses parsed semantic signatures, but existing-row dedupe compares `hashCode()` of the raw JSON string. Equivalent filters serialized with different field ordering/version noise bypass DB-side dedupe and duplicate cards accumulate across runs. | Centralize canonical signature generation and reuse the exact same signature for new and persisted recommendations. |
| 11 | `RecommendationLifecycleManager.kt:46-55,75-83` → `RecommendationRepository.kt:183-186` → `RecommendationDao.kt:138-139` | HIGH | The periodic lifecycle loop calls `cleanupExpired()`, but repository cleanup only deletes rows already marked `EXPIRED`. It never marks time-expired `ACTIVE` rows as expired, so the periodic "expiration" check does not actually expire stale recommendations. | Add a bulk expire step before deletion (all users), or provide a DAO method that both marks and deletes time-expired rows consistently. |
| 12 | `AndroidNotificationService.kt:131-138` → `AndroidManifest.xml:62-74` + `MainActivity.kt:137-159` | HIGH | Anomaly notifications deep-link to `expensetracker://transaction/{id}`, but the manifest does not declare a `transaction` host and `MainActivity.handleIntent()` does not handle it. Tapping the alert cannot reach the intended transaction detail flow. | Add the `transaction` deep-link host and implement routing for it, or reuse an already-supported route with the expense ID encoded as a query parameter. |
| 13 | `NavigationTargetResolver.kt:63-68` → `HomeScreen.kt:88-94` → `MainActivity.kt:355-364` | MEDIUM | `NavigationAction.ToAnalytics(period)` and `ToMap(location)` carry payloads, but the UI callbacks drop them and only switch tabs. Recommendation-specific context (period/location) is lost before navigation reaches the destination. | Extend the navigation callbacks/destinations to carry and consume period/location parameters end-to-end. |
| 14 | `DeliverProactiveBriefingNotificationUseCase.kt:43-45` → `AndroidNotificationService.kt:100-118` + `NotificationIdGenerator.kt:24-68` | MEDIUM | AI briefing notifications use `targetKey.hashCode()` directly instead of the app's namespaced ID generator. They can collide with other notification IDs and overwrite another visible notification or pending-intent slot. | Add a dedicated AI notification namespace in `NotificationIdGenerator` and route briefing IDs through it. |
| 15 | `NotificationCaptureService.kt:74,90-108` + `BootReceiver.kt:11-22` + `ServiceRestartReceiver.kt:11-20` | MEDIUM | The notification listener is force-started at boot and then every minute via a repeating alarm even when access is missing or the listener is already healthy. That creates unnecessary wakeups and background start attempts. | Remove the fixed keep-alive alarm; use `requestRebind()` plus bounded backoff only after real disconnect/failure events. |

## Summary
- Total issues: 15
- Critical: 0, High: 10, Medium: 4, Low: 1
- Files with issues: 9/10

## Key Patterns
- Recommendation state is not truly reactive: generation, invalidation, dismissal, cleanup, and UI state all rely on separate manual refresh paths with no single authoritative stream.
- Deduplication rules are inconsistent across layers: the in-memory/batch logic and persisted-row logic do not agree on what makes a recommendation unique.
- Notification handling is too permissive and too coupled to fragile platform behavior: communication-app capture is overbroad, service keep-alive is battery-heavy, and some published notification routes are not actually navigable.
