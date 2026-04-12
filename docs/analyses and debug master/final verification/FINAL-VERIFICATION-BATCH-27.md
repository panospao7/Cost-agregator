# Final Verification — Batch 27: Database — Advanced DAOs

> **[B.4-SCOPE ISSUES RESOLVED]** All issues within B.4 scope have been addressed across B.4 micro-batches. See `docs/reviews/REVIEW-B4.md` for targeted validation evidence and waivers.
> **[ONE ITEM DEFERRED — NOT B.4 SCOPE]** Issue 12 (`getPortfolioValueHistory()` N+1 per-investment query fan-out) was explicitly deferred; it is not a B.4 item and remains open for a future batch.

## Scope
- `com/yourname/expensetracker/data/database/dao/AiArtifactDao.kt`
- `com/yourname/expensetracker/data/database/dao/AiChatMessageDao.kt`
- `com/yourname/expensetracker/data/database/dao/AiChatSessionDao.kt`
- `com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt`
- `com/yourname/expensetracker/data/database/dao/BlockedPackageDao.kt`
- `com/yourname/expensetracker/data/database/dao/BudgetAdjustmentDao.kt`
- `com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt`
- `com/yourname/expensetracker/data/database/dao/HealthScoreHistoryDao.kt`
- `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt`
- `com/yourname/expensetracker/data/database/dao/MileageTrackingDao.kt`
- `com/yourname/expensetracker/data/database/dao/StressForecastSnapshotDao.kt`
- `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt`
- `com/yourname/expensetracker/data/database/dao/SubscriptionPriceHistoryDao.kt`
- `com/yourname/expensetracker/data/database/dao/SubscriptionUsageDao.kt`
- `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt`
- `com/yourname/expensetracker/data/database/entity/AiArtifactEntity.kt`
- `com/yourname/expensetracker/data/database/entity/AiChatMessageEntity.kt`
- `com/yourname/expensetracker/data/database/entity/AiChatSessionEntity.kt`
- `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt`
- `com/yourname/expensetracker/data/database/entity/BlockedPackage.kt`
- `com/yourname/expensetracker/data/database/entity/BudgetAdjustmentRecommendation.kt`
- `com/yourname/expensetracker/data/database/entity/BudgetForecast.kt`
- `com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt`
- `com/yourname/expensetracker/data/database/entity/InvestmentValue.kt`
- `com/yourname/expensetracker/data/database/entity/MileageTracking.kt`
- `com/yourname/expensetracker/data/database/entity/StressForecastSnapshot.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionPriceHistory.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt`
- `com/yourname/expensetracker/data/database/entity/UserCorrection.kt`
- `com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- `com/yourname/expensetracker/data/repository/UserCorrectionRepository.kt`
- `com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
- `com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
- `com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
- `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`
- `com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt`

Requested in the original reports but not present in this repository: `com/yourname/expensetracker/data/database/dao/MerchantStatsDao.kt`, `MerchantStatsSummaryDao.kt`, `NotificationCaptureDao.kt`, `PriceProtectionDao.kt`, `TaxCategoryDao.kt`, `TaxReportDao.kt`, `TransactionInsightDao.kt`.

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/database/entity/UserCorrection.kt:21-27`, `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:55-92` | High | Performance | `user_corrections` still has no index on `originalMerchant`, while hot lookup/aggregation methods (`getMerchantTotalCorrections`, `getMerchantRejectionCount`, `getMerchantStats`, `getMostCommonCategoryForMerchant`) filter on it directly. | B | **[RESOLVED BY B.4 — late closeout: `Index("originalMerchant")` entity annotation added; `MIGRATION_76_77` creates `index_user_corrections_originalMerchant`; schema bumped to version 77; `DatabaseMigrationTest` extended with `migrate_76_to_77_adds_originalMerchant_index` and `migrate_75_to_77_chain_passes_and_has_originalMerchant_index`]** | Add at least `Index(value = ["originalMerchant"])`; consider a composite index for hot combinations such as `(originalMerchant, wasRejected)`. |
| 2 | `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt:14-19`, `com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt:32-33` | High | Performance | Category cooldown checks query `category` + `alertedAt`, but the table has no `(category, alertedAt)` index. | B | **[RESOLVED BY B.4 — late closeout: `Index(value = ["category", "alertedAt"])` entity annotation added; `MIGRATION_77_78` creates `index_anomaly_alerts_category_alertedAt`; schema bumped to version 78; `MigrationContractTest` extended with `migration_77_to_78_adds_category_alertedAt_index_on_anomaly_alerts`]** | Add `Index(value = ["category", "alertedAt"])` and mirror it in migration/repair SQL. |
| 3 | `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt:14-18`, `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt:36-37` | High | Data integrity | `getByCanonicalMerchant()` still uses `LIMIT 1` without `ORDER BY`, and the schema still allows multiple pending rows for the same merchant, so reads become nondeterministic after duplicate inserts. | B | **[RESOLVED BY B.4 — Batch 7]** | Enforce one pending candidate per canonical merchant and add deterministic ordering such as `ORDER BY updatedAt DESC, id DESC`. |
| 4 | `com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt:26-27`, `com/yourname/expensetracker/data/database/entity/BudgetForecast.kt:22-26` | High | SQL correctness | `getForecastForDate()` still returns `LIMIT 1` without ordering even though the schema permits multiple overlapping active forecasts for the same budget/period. | B | **[RESOLVED BY B.4 — Batch 7]** | Add deterministic ordering (`ORDER BY forecastDate DESC, id DESC`) and a uniqueness rule for active budget-period forecasts. |
| 5 | `com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt:14-18`, `com/yourname/expensetracker/data/database/dao/HealthScoreHistoryDao.kt:49-50`, `com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:499-503` | Medium | Data integrity | The table only has a non-unique index on `(periodStart, periodEnd)`, but callers treat that pair as a single logical record and update only the first row returned. Duplicate period rows remain possible and silently ambiguous. | B | **[RESOLVED BY B.4 — Batch 8]** | Make `(periodStart, periodEnd)` unique and switch to upsert/single-row retrieval semantics. |
| 6 | `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:43-53`, `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:83-92` | Low | Determinism | `getMostCommonMerchantCorrection()` and `getMostCommonCategoryForMerchant()` still break ties with `LIMIT 1` and no secondary ordering, so equal-frequency results can flip arbitrarily. | B | **[RESOLVED BY B.4 — Batch 5]** | Add a stable secondary sort, e.g. recency/id/alphabetical tie-breakers. |
| 7 | `com/yourname/expensetracker/data/database/entity/MileageTracking.kt:22-26`, `com/yourname/expensetracker/data/database/dao/MileageTrackingDao.kt:32-39` | Medium | Performance | Reporting queries combine `isBusinessTrip = 1` with date-range filters, but the table only has separate indexes on `isBusinessTrip` and `date`. | B | **[RESOLVED BY B.4 — Batch 8]** | Add a composite index such as `Index(value = ["isBusinessTrip", "date"])` and add it to migrations. |
| 8 | `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:683-733`, `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt:123-140` | High | Race condition | Subscription candidate dedupe is still implemented as read-then-insert without a transaction or DB uniqueness constraint, so concurrent notifications can create duplicate pending candidates/prompts. | B | **[RESOLVED BY B.4 — Batch 7]** | Wrap dedupe + insert in a transaction and enforce uniqueness for pending candidates at the schema level. |
| 9 | `com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt:84-98`, `com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt:23-27` | High | Data integrity | Forecast generation still inserts new active rows without deactivating older ones, which allows stale overlapping active forecasts and makes active-row reads inconsistent. | B | **[RESOLVED BY B.4 — Batch 7]** | Replace insert-only persistence with a transactional “deactivate previous active rows, then insert new row” flow plus an active-row uniqueness rule. |
| 10 | `com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt:63-75` | Medium | Transaction | `appendMessage()` still persists the message and updates the session timestamp as two separate writes, so `updatedAt` can lag the actual latest message if the second write fails or interleaves. | B | **[RESOLVED BY B.4 — Batch 9]** | Move both writes into one Room transaction (`withTransaction` or an `@Transaction` DAO method). |
| 11 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:90-107`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-37` | High | Logic error | `getInvestmentPerformance()` still computes “all-time” high/low from only the last 30 days of history. | B | **[RESOLVED BY B.4 — late closeout: true all-time min/max DAO queries (`getMaxPrice`/`getMinPrice` from epoch 0) used; recent-value ordering also corrected via `recentValues.lastOrNull()` on ASC-ordered window]** | Add DAO queries for true all-time min/max and use them instead of the 30-day slice. |
| 12 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:214-221`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-25` | Medium | Performance | `getPortfolioValueHistory()` still executes one history query per investment, creating an avoidable N+1 query pattern. | B | **[DEFERRED — not addressed in B.4; current code still uses per-investment `getValuesBetween()` calls; batched portfolio-history query remains an open item for a future batch]** | Add a batched/date-grouped DAO query that aggregates portfolio history across investment IDs in one pass. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:92-99`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-25` | Medium | Logic error | `getValuesBetween()` returns rows ordered by `timestamp ASC`, but `getInvestmentPerformance()` reads `historicalValues.firstOrNull()` for `dayChange` / `dayChangePercent`, so it surfaces the oldest value in the 30-day window rather than the latest one. | **[RESOLVED BY B.4 — late closeout]** Fixed via `recentValues.lastOrNull()` on the ASC-ordered 30-day window, returning the most-recent sample. |
| 2 | `com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt:22`, `com/yourname/expensetracker/data/database/dao/SubscriptionUsageDao.kt:27-28` | Low | Performance | `getAllUsageSince()` filters and sorts by `usedAt` across all subscriptions, but the table only indexes `(subscriptionId, usedAt)`. Because `usedAt` is not the leading column, this global query is effectively unindexed. | **[RESOLVED BY B.4 — Batch 7]** Add a standalone `Index(value = ["usedAt"])` if global usage-by-time queries are expected to stay. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 0 | - | - | None. Every reported issue corresponded to a real defect or risk; only severity was overstated for Issues 5, 6, and 12. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `UserCorrectionDao -> UserCorrectionRepository -> ConfidenceRouter` | High | Performance | **[RESOLVED BY B.4 — late closeout: `MIGRATION_76_77` creates `index_user_corrections_originalMerchant`; schema version bumped to 77]** Merchant-learning rejection-rate lookups remain on a hot path while `originalMerchant` is unindexed, so classifier latency scales with correction-history growth. | `data/database/dao/UserCorrectionDao.kt`, `data/database/entity/UserCorrection.kt`, `data/repository/UserCorrectionRepository.kt`, `domain/intelligence/ConfidenceRouter.kt` | Index is now in place; consider caching/pre-aggregation if the path remains latency-sensitive. |
| 2 | `SubscriptionCandidateDao -> NotificationProcessingPipeline -> subscription prompt/UI flow` | High | Race condition | **[RESOLVED BY B.4 — Batch 7]** Pending-candidate dedupe is still read-then-insert, so concurrent detections can create duplicate rows and duplicate prompts. | `data/database/dao/SubscriptionCandidateDao.kt`, `data/database/entity/SubscriptionCandidate.kt`, `data/repository/NotificationProcessingPipeline.kt` | Enforce one pending row per canonical merchant and perform dedupe + insert transactionally. |
| 3 | `BudgetForecastDao -> BudgetForecastingEngine` | High | Data integrity | **[RESOLVED BY B.4 — Batch 7]** Forecast generation still accumulates overlapping active rows, while retrieval APIs allow arbitrary active-row selection. | `data/database/dao/BudgetForecastDao.kt`, `data/database/entity/BudgetForecast.kt`, `domain/budget/BudgetForecastingEngine.kt` | Transactionally deactivate prior active forecasts before insert, and enforce an active-row uniqueness rule. |
| 4 | `AiChatMessageDao + AiChatSessionDao -> AiChatRepositoryImpl` | Medium | Transaction consistency | **[RESOLVED BY B.4 — Batch 9]** Chat message persistence and session touch still happen as separate writes, so session ordering metadata can drift from message history. | `data/database/dao/AiChatMessageDao.kt`, `data/database/dao/AiChatSessionDao.kt`, `data/repository/AiChatRepositoryImpl.kt` | Wrap append-message persistence in a single Room transaction. |
| 5 | `InvestmentValueDao -> InvestmentTracker` | High | Logic / performance | **[PARTIALLY RESOLVED BY B.4 — late closeout: true all-time min/max queries in place; recent-value ordering corrected via `recentValues.lastOrNull()`; N+1 portfolio-history fan-out (per-investment `getValuesBetween()`) was NOT addressed and remains deferred]** | `data/database/dao/InvestmentValueDao.kt`, `domain/investment/InvestmentTracker.kt` | Resolved: all-time aggregates use epoch-0 queries; `lastOrNull()` used for recent-value ordering. Deferred: per-investment history fan-out; batched portfolio-history query not yet implemented. |

## Summary
- Total verified issues: 12
- Confirmed: 12 (Critical: 0, High: 7, Medium: 4, Low: 1)
- False positives: 0
- Missed issues found: 2
- Files affected: 9/15 existing DAO files

## Key Patterns
- At the time of verification, missing or incomplete indexing was the dominant DAO problem in this batch, especially for hot lookup/filter combinations. The two highest-priority missing indexes (`originalMerchant` on `user_corrections`, `(category, alertedAt)` on `anomaly_alerts`) were added by B.4 late closeout via `MIGRATION_76_77` and `MIGRATION_77_78` respectively.
- At the time of verification, several APIs relied on `LIMIT 1` without either deterministic ordering or schema-level uniqueness, so duplicates turned into arbitrary reads. Resolved by B.4 — Batch 7 (subscription candidates, budget forecasts) and Batch 5 (user-correction tie-breaking).
- At the time of verification, multi-step persistence flows were not transactional, which left concurrency gaps and stale metadata. Resolved by B.4 — Batch 7 (`NotificationProcessingPipeline`, `BudgetForecastingEngine`) and Batch 9 (`AiChatRepositoryImpl`).
- At the time of verification, higher-level analytics code was misusing DAO query semantics in multiple places (30-day windows labeled as all-time, per-investment query fan-out, ordering assumptions returning stale metrics). Partially resolved by B.4 late closeout (`InvestmentTracker` true all-time queries and `lastOrNull()` ordering fix). Deferred: per-investment `getPortfolioValueHistory()` N+1 fan-out was not addressed in B.4; batched portfolio-history query remains an open item.
