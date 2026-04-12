# Deep Analysis — Batch 27: Database — Advanced DAOs (@reviewer)

> **[B.4 SYNC]** Issue dispositions below have been updated to reflect the B.4 final closeout. Authoritative resolutions are recorded in `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-27.md`.

## Scope
- data/database/dao/AiArtifactDao.kt
- data/database/dao/AiChatMessageDao.kt
- data/database/dao/AiChatSessionDao.kt
- data/database/dao/AnomalyAlertDao.kt
- data/database/dao/BlockedPackageDao.kt
- data/database/dao/BudgetAdjustmentDao.kt
- data/database/dao/BudgetForecastDao.kt
- data/database/dao/HealthScoreHistoryDao.kt
- data/database/dao/InvestmentValueDao.kt
- data/database/dao/MerchantStatsDao.kt *(not present in repository)*
- data/database/dao/MerchantStatsSummaryDao.kt *(not present in repository)*
- data/database/dao/MileageTrackingDao.kt
- data/database/dao/NotificationCaptureDao.kt *(not present in repository)*
- data/database/dao/PriceProtectionDao.kt *(not present in repository)*
- data/database/dao/StressForecastSnapshotDao.kt
- data/database/dao/SubscriptionCandidateDao.kt
- data/database/dao/SubscriptionPriceHistoryDao.kt
- data/database/dao/SubscriptionUsageDao.kt
- data/database/dao/TaxCategoryDao.kt *(not present in repository)*
- data/database/dao/TaxReportDao.kt *(not present in repository)*
- data/database/dao/TransactionInsightDao.kt *(not present in repository)*
- data/database/dao/UserCorrectionDao.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `app/src/main/java/com/yourname/expensetracker/data/database/entity/UserCorrection.kt:21-27`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:55-92` | HIGH | Performance | `user_corrections` has no index on `originalMerchant`, but the DAO repeatedly filters/aggregates by that column (`getMerchantTotalCorrections`, `getMerchantRejectionCount`, `getMerchantStats`, `getMostCommonCategoryForMerchant`). These are table scans once correction history grows. **[RESOLVED BY B.4 — late closeout: `Index("originalMerchant")` entity annotation added; `MIGRATION_76_77` creates `index_user_corrections_originalMerchant`; schema bumped to version 77; `DatabaseMigrationTest` extended]** | Add an index that covers merchant learning lookups, at minimum `Index(value = ["originalMerchant"])`; ideally consider a composite index such as `(originalMerchant, wasRejected)` if rejection-rate queries remain hot. |
| 2 | `app/src/main/java/com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt:14-19`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt:32-33` | HIGH | Performance | The category cooldown query filters by `category` and `alertedAt`, but the table only indexes `expenseId`, `(merchant, alertedAt)`, `(severity, alertedAt)`, and `(dismissed, alertedAt)`. Category-level alert suppression will degrade into full scans. **[RESOLVED BY B.4 — late closeout: `Index(value = ["category", "alertedAt"])` entity annotation added; `MIGRATION_77_78` creates `index_anomaly_alerts_category_alertedAt`; schema bumped to version 78; `MigrationContractTest` extended]** | Add `Index(value = ["category", "alertedAt"])` and include it in the migration repair SQL. |
| 3 | `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt:14-18`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/SubscriptionCandidateDao.kt:36-37` | HIGH | Data integrity | The schema does not enforce a single pending candidate per merchant, and `getByCanonicalMerchant()` uses `LIMIT 1` with no `ORDER BY`. Once duplicates exist, reads become nondeterministic. **[RESOLVED BY B.4 — Batch 7]** | Enforce uniqueness for the active/pending candidate key (for example via a dedicated unique pending-key column or a unique constraint that models "one pending per merchant"), and add deterministic ordering such as `ORDER BY updatedAt DESC, id DESC`. |
| 4 | `app/src/main/java/com/yourname/expensetracker/data/database/dao/BudgetForecastDao.kt:23-27`, `app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt:22-26` | HIGH | SQL correctness | `getForecastForDate()` returns `LIMIT 1` without `ORDER BY`, even though the schema allows multiple active forecasts for the same budget/period. That can return an arbitrary row. **[RESOLVED BY B.4 — Batch 7]** | Add deterministic ordering (`ORDER BY forecastDate DESC, id DESC`) and introduce a uniqueness rule/index that prevents overlapping active forecasts for the same budget/period. |
| 5 | `app/src/main/java/com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt:14-18`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/HealthScoreHistoryDao.kt:49-50` | MEDIUM | Data integrity | The DAO/API assumes one history row per `(periodStart, periodEnd)`, but the table only has a non-unique index on that pair. Duplicate period rows are allowed and callers must arbitrarily pick one. **[RESOLVED BY B.4 — Batch 8]** | Make `(periodStart, periodEnd)` unique and switch callers to an upsert/single-row fetch path instead of list-first semantics. |
| 6 | `app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:43-53`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt:83-92` | LOW | Data integrity | `getMostCommonMerchantCorrection()` and `getMostCommonCategoryForMerchant()` break ties with `LIMIT 1` but no secondary ordering. Equal-frequency results can flip between runs/devices. **[RESOLVED BY B.4 — Batch 5]** | Add a deterministic secondary sort, e.g. `ORDER BY COUNT(*) DESC, MAX(createdAt) DESC, correctedMerchant ASC` / `correctedCategoryId ASC`. |
| 7 | `app/src/main/java/com/yourname/expensetracker/data/database/entity/MileageTracking.kt:22-26`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/MileageTrackingDao.kt:32-39` | MEDIUM | Performance | The main reporting queries filter `isBusinessTrip = 1` and a date range, but the table only has separate indexes on `isBusinessTrip` and `date`. SQLite can use only one efficiently, so larger mileage histories will scan/filter unnecessarily. **[RESOLVED BY B.4 — Batch 8]** | Add a composite index such as `Index(value = ["isBusinessTrip", "date"])` and reflect it in migrations. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `UserCorrectionDao -> UserCorrectionRepository -> ConfidenceRouter` | HIGH | Performance | `ConfidenceRouter` calls `userCorrectionRepository.getMerchantStats()` on the classification hot path (`app/src/main/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt:257-279`). Because `originalMerchant` is unindexed, classifier latency grows with correction history. **[RESOLVED BY B.4 — late closeout: `MIGRATION_76_77` creates `index_user_corrections_originalMerchant`; schema version bumped to 77]** | Add the merchant index from Issue 1 and consider caching/pre-aggregation if this path stays latency-sensitive. |
| 2 | `SubscriptionCandidateDao -> NotificationProcessingPipeline -> SubscriptionManagementViewModel` | HIGH | Data integrity | `detectAndSaveSubscriptionCandidate()` does a read-then-insert dedupe check (`app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt:683-733`) without a transaction or unique constraint. Concurrent notifications for the same merchant can create duplicate pending candidates and duplicate user prompts. **[RESOLVED BY B.4 — Batch 7]** | Wrap dedupe + insert in a transaction and back it with a uniqueness rule that enforces one pending candidate per canonical merchant. |
| 3 | `BudgetForecastDao -> BudgetForecastingEngine` | HIGH | Data integrity | `generateForecast()` always inserts a new forecast (`app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt:84-98`) and never deactivates prior active rows. Combined with `getForecastForDate()`/`getLatestActiveForecast()`, stale overlapping "active" forecasts can accumulate and be read back inconsistently. **[RESOLVED BY B.4 — Batch 7]** | Replace the insert-only flow with a transactional "deactivate old active rows, insert new row" operation and enforce uniqueness for active budget-period forecasts. |
| 4 | `AiChatMessageDao + AiChatSessionDao -> AiChatRepositoryImpl -> AssistantViewModel` | MEDIUM | Transaction | `appendMessage()` inserts the message and updates the session timestamp as two separate writes (`app/src/main/java/com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt:63-75`). If the second write fails or interleaves, session ordering can lag behind the actual latest message. **[RESOLVED BY B.4 — Batch 9]** | Move message insert + session touch into a single Room transaction (`withTransaction` or an `@Transaction` DAO method). |
| 5 | `InvestmentValueDao -> InvestmentTracker` | HIGH | SQL correctness / Performance | `getInvestmentPerformance()` loads only the last 30 days of value history but reports the max/min as "all-time" highs/lows (`app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt:90-107`). `getPortfolioValueHistory()` also issues one history query per investment (`InvestmentTracker.kt:203-227`), creating an N+1 pattern. **[PARTIALLY RESOLVED BY B.4 — late closeout: true all-time min/max queries in place; recent-value ordering corrected via `recentValues.lastOrNull()`; N+1 portfolio-history fan-out (per-investment `getValuesBetween()`) was NOT addressed and remains deferred]** | Add dedicated DAO queries for true all-time aggregates and for batched/date-grouped portfolio history, then use those instead of per-investment loops. |

## Summary
- Total issues: 12
- Critical: 0, High: 8, Medium: 3, Low: 1
- Files with issues: 8/15 existing files reviewed *(15 of 22 requested DAO files exist in this repository)*

## Key Patterns
- Several tables rely on `LIMIT 1` reads without either a deterministic `ORDER BY` or a uniqueness rule that guarantees only one logical match.
- Hot-path lookups were added without the matching composite/lookup indexes, especially around correction learning, anomaly cooldowns, and business-mileage reports.
- Multiple repository/domain flows perform read-then-write or multi-step writes outside a transaction, so the DAO contracts are not strong enough to preserve one-row-per-key invariants under concurrency.
