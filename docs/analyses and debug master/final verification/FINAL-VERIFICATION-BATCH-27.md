# Final Verification — Batch 27: Database — Advanced DAOs

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
| 1 | `com/yourname/expensetracker/data/database/entity/UserCorrection.kt:21-27`, `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:55-92` | High | Performance | `user_corrections` still has no index on `originalMerchant`, while hot lookup/aggregation methods (`getMerchantTotalCorrections`, `getMerchantRejectionCount`, `getMerchantStats`, `getMostCommonCategoryForMerchant`) filter on it directly. | B | CONFIRMED | Add at least `Index(value = ["originalMerchant"])`; consider a composite index for hot combinations such as `(originalMerchant, wasRejected)`. |
| 2 | `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt:14-19`, `com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt:32-33` | High | Performance | Category cooldown checks query `category` + `alertedAt`, but the table has no `(category, alertedAt)` index. | B | CONFIRMED | Add `Index(value = ["category", "alertedAt"])` and mirror it in migration/repair SQL. |
| 3 | `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt:14-18`, `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt:36-37` | High | Data integrity | `getByCanonicalMerchant()` still uses `LIMIT 1` without `ORDER BY`, and the schema still allows multiple pending rows for the same merchant, so reads become nondeterministic after duplicate inserts. | B | CONFIRMED | Enforce one pending candidate per canonical merchant and add deterministic ordering such as `ORDER BY updatedAt DESC, id DESC`. |
| 4 | `com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt:26-27`, `com/yourname/expensetracker/data/database/entity/BudgetForecast.kt:22-26` | High | SQL correctness | `getForecastForDate()` still returns `LIMIT 1` without ordering even though the schema permits multiple overlapping active forecasts for the same budget/period. | B | CONFIRMED | Add deterministic ordering (`ORDER BY forecastDate DESC, id DESC`) and a uniqueness rule for active budget-period forecasts. |
| 5 | `com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt:14-18`, `com/yourname/expensetracker/data/database/dao/HealthScoreHistoryDao.kt:49-50`, `com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:499-503` | Medium | Data integrity | The table only has a non-unique index on `(periodStart, periodEnd)`, but callers treat that pair as a single logical record and update only the first row returned. Duplicate period rows remain possible and silently ambiguous. | B | DOWNGRADED | Make `(periodStart, periodEnd)` unique and switch to upsert/single-row retrieval semantics. |
| 6 | `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:43-53`, `com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:83-92` | Low | Determinism | `getMostCommonMerchantCorrection()` and `getMostCommonCategoryForMerchant()` still break ties with `LIMIT 1` and no secondary ordering, so equal-frequency results can flip arbitrarily. | B | DOWNGRADED | Add a stable secondary sort, e.g. recency/id/alphabetical tie-breakers. |
| 7 | `com/yourname/expensetracker/data/database/entity/MileageTracking.kt:22-26`, `com/yourname/expensetracker/data/database/dao/MileageTrackingDao.kt:32-39` | Medium | Performance | Reporting queries combine `isBusinessTrip = 1` with date-range filters, but the table only has separate indexes on `isBusinessTrip` and `date`. | B | CONFIRMED | Add a composite index such as `Index(value = ["isBusinessTrip", "date"])` and add it to migrations. |
| 8 | `com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:683-733`, `com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt:123-140` | High | Race condition | Subscription candidate dedupe is still implemented as read-then-insert without a transaction or DB uniqueness constraint, so concurrent notifications can create duplicate pending candidates/prompts. | B | CONFIRMED | Wrap dedupe + insert in a transaction and enforce uniqueness for pending candidates at the schema level. |
| 9 | `com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt:84-98`, `com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt:23-27` | High | Data integrity | Forecast generation still inserts new active rows without deactivating older ones, which allows stale overlapping active forecasts and makes active-row reads inconsistent. | B | CONFIRMED | Replace insert-only persistence with a transactional “deactivate previous active rows, then insert new row” flow plus an active-row uniqueness rule. |
| 10 | `com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt:63-75` | Medium | Transaction | `appendMessage()` still persists the message and updates the session timestamp as two separate writes, so `updatedAt` can lag the actual latest message if the second write fails or interleaves. | B | CONFIRMED | Move both writes into one Room transaction (`withTransaction` or an `@Transaction` DAO method). |
| 11 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:90-107`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-37` | High | Logic error | `getInvestmentPerformance()` still computes “all-time” high/low from only the last 30 days of history. | B | CONFIRMED | Add DAO queries for true all-time min/max and use them instead of the 30-day slice. |
| 12 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:214-221`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-25` | Medium | Performance | `getPortfolioValueHistory()` still executes one history query per investment, creating an avoidable N+1 query pattern. | B | DOWNGRADED | Add a batched/date-grouped DAO query that aggregates portfolio history across investment IDs in one pass. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:92-99`, `com/yourname/expensetracker/data/database/dao/InvestmentValueDao.kt:24-25` | Medium | Logic error | `getValuesBetween()` returns rows ordered by `timestamp ASC`, but `getInvestmentPerformance()` reads `historicalValues.firstOrNull()` for `dayChange` / `dayChangePercent`, so it surfaces the oldest value in the 30-day window rather than the latest one. | Use `lastOrNull()` for the ascending result set, or fetch the latest snapshot explicitly via `getLatestValue()`. |
| 2 | `com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt:22`, `com/yourname/expensetracker/data/database/dao/SubscriptionUsageDao.kt:27-28` | Low | Performance | `getAllUsageSince()` filters and sorts by `usedAt` across all subscriptions, but the table only indexes `(subscriptionId, usedAt)`. Because `usedAt` is not the leading column, this global query is effectively unindexed. | Add a standalone `Index(value = ["usedAt"])` if global usage-by-time queries are expected to stay. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 0 | - | - | None. Every reported issue corresponded to a real defect or risk; only severity was overstated for Issues 5, 6, and 12. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `UserCorrectionDao -> UserCorrectionRepository -> ConfidenceRouter` | High | Performance | Merchant-learning rejection-rate lookups remain on a hot path while `originalMerchant` is unindexed, so classifier latency scales with correction-history growth. | `data/database/dao/UserCorrectionDao.kt`, `data/database/entity/UserCorrection.kt`, `data/repository/UserCorrectionRepository.kt`, `domain/intelligence/ConfidenceRouter.kt` | Add the merchant lookup index and consider caching/pre-aggregation if this path remains latency-sensitive. |
| 2 | `SubscriptionCandidateDao -> NotificationProcessingPipeline -> subscription prompt/UI flow` | High | Race condition | Pending-candidate dedupe is still read-then-insert, so concurrent detections can create duplicate rows and duplicate prompts. | `data/database/dao/SubscriptionCandidateDao.kt`, `data/database/entity/SubscriptionCandidate.kt`, `data/repository/NotificationProcessingPipeline.kt` | Enforce one pending row per canonical merchant and perform dedupe + insert transactionally. |
| 3 | `BudgetForecastDao -> BudgetForecastingEngine` | High | Data integrity | Forecast generation still accumulates overlapping active rows, while retrieval APIs allow arbitrary active-row selection. | `data/database/dao/BudgetForecastDao.kt`, `data/database/entity/BudgetForecast.kt`, `domain/budget/BudgetForecastingEngine.kt` | Transactionally deactivate prior active forecasts before insert, and enforce an active-row uniqueness rule. |
| 4 | `AiChatMessageDao + AiChatSessionDao -> AiChatRepositoryImpl` | Medium | Transaction consistency | Chat message persistence and session touch still happen as separate writes, so session ordering metadata can drift from message history. | `data/database/dao/AiChatMessageDao.kt`, `data/database/dao/AiChatSessionDao.kt`, `data/repository/AiChatRepositoryImpl.kt` | Wrap append-message persistence in a single Room transaction. |
| 5 | `InvestmentValueDao -> InvestmentTracker` | High | Logic / performance | Investment analytics still combine incorrect “all-time” metrics with per-investment history fan-out, so the pipeline is both inaccurate and unnecessarily expensive. | `data/database/dao/InvestmentValueDao.kt`, `domain/investment/InvestmentTracker.kt` | Add true all-time aggregate queries and batched portfolio-history queries, then replace per-investment loops. |

## Summary
- Total verified issues: 12
- Confirmed: 12 (Critical: 0, High: 7, Medium: 4, Low: 1)
- False positives: 0
- Missed issues found: 2
- Files affected: 9/15 existing DAO files

## Key Patterns
- Missing or incomplete indexing is the dominant DAO problem in this batch, especially for hot lookup/filter combinations.
- Several APIs still rely on `LIMIT 1` without either deterministic ordering or schema-level uniqueness, so duplicates turn into arbitrary reads.
- Multi-step persistence flows are still not transactional, which leaves concurrency gaps and stale metadata.
- Higher-level analytics code is misusing DAO query semantics in multiple places (30-day windows labeled as all-time, per-investment query fan-out, and ordering assumptions that return stale metrics).
