# Deep Analysis — Batch 27: Database — Advanced DAOs (@debugger)

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
- data/database/dao/MerchantStatsDao.kt
- data/database/dao/MerchantStatsSummaryDao.kt
- data/database/dao/MileageTrackingDao.kt
- data/database/dao/NotificationCaptureDao.kt
- data/database/dao/PriceProtectionDao.kt
- data/database/dao/StressForecastSnapshotDao.kt
- data/database/dao/SubscriptionCandidateDao.kt
- data/database/dao/SubscriptionPriceHistoryDao.kt
- data/database/dao/SubscriptionUsageDao.kt
- data/database/dao/TaxCategoryDao.kt
- data/database/dao/TaxReportDao.kt
- data/database/dao/TransactionInsightDao.kt
- data/database/dao/UserCorrectionDao.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | UserCorrectionDao.kt:55-92 | **HIGH** | Performance | `user_corrections` has no index on `originalMerchant`, but DAO methods aggregate/filter on it repeatedly; this turns merchant-learning lookups into table scans on a hot classification path. | 1. Accumulate 1000+ user corrections. 2. Call `getMostCommonMerchantCorrection()`. 3. Full table scan on every call. | Add an index on `originalMerchant` (preferably a composite index if rejection-rate queries are hot). |
| 2 | AnomalyAlertDao.kt:32-33 | **HIGH** | Performance | Category cooldown lookup is unindexed: `getLastAlertForCategory()` filters by `category` and `alertedAt`, but `anomaly_alerts` lacks a `(category, alertedAt)` index. | 1. Process many anomalies across categories. 2. Each cooldown check does full table scan. | Add `Index(value = ["category", "alertedAt"])` and migration support. |
| 3 | SubscriptionCandidateDao.kt:36-37 | **HIGH** | Data Integrity | `getByCanonicalMerchant()` uses `LIMIT 1` without `ORDER BY`, and the schema does not enforce one pending candidate per merchant, so reads become nondeterministic once duplicates exist. | 1. Two concurrent detections insert candidates for same merchant. 2. `getByCanonicalMerchant()` returns arbitrary one. | Add deterministic ordering and a uniqueness rule for the active/pending merchant key. |
| 4 | BudgetForecastDao.kt:26-27 | **HIGH** | Data Integrity | `getForecastForDate()` returns `LIMIT 1` with no ordering while multiple active overlapping forecasts are allowed, so callers can get an arbitrary forecast row. | 1. Multiple forecasts exist for same date. 2. Query returns arbitrary row. | Add `ORDER BY forecastDate DESC, id DESC` and enforce one active forecast per budget/period. |
| 5 | HealthScoreHistoryDao.kt:49-50 | **HIGH** | Data Integrity | Health score history assumes one row per `(periodStart, periodEnd)`, but the schema only has a non-unique index; duplicates are legal and callers arbitrarily take the first match. | 1. Concurrent recalculations create duplicate rows. 2. Query returns arbitrary first match. | Make `(periodStart, periodEnd)` unique and switch to upsert/single-row retrieval semantics. |
| 6 | UserCorrectionDao.kt:43-53, 83-92 | **MEDIUM** | Data Integrity | `getMostCommonMerchantCorrection()` and `getMostCommonCategoryForMerchant()` break ties with `LIMIT 1` and no secondary sort, so equal-frequency results can flip nondeterministically. | 1. Two merchants have equal correction counts. 2. Result flips between calls. | Add a stable secondary ordering such as recency/id/alphabetical tie-breakers. |
| 7 | MileageTrackingDao.kt:32-39 | **MEDIUM** | Performance | Mileage reporting queries combine `isBusinessTrip` with date ranges, but the table only has separate single-column indexes, which is suboptimal for reporting scans. | 1. Query mileage for date range with business filter. 2. Two separate index scans instead of one composite. | Add a composite index on `(isBusinessTrip, date)`. |
| 8 | NotificationProcessingPipeline.kt:683-733 | **HIGH** | Race Condition | Subscription candidate dedupe is implemented as read-then-insert without a transaction/constraint, so concurrent notifications can create duplicate pending candidates and duplicate UI prompts. | 1. Two notifications for same merchant arrive simultaneously. 2. Both pass dedupe check. 3. Two duplicate candidates inserted. | Wrap dedupe + insert in a transaction and enforce uniqueness at the DB layer. |
| 9 | BudgetForecastingEngine.kt:84-98 | **HIGH** | Data Integrity | Budget forecasting is insert-only and never deactivates prior active rows, so stale overlapping active forecasts accumulate and interact badly with nondeterministic reads. | 1. Generate forecast daily for a month. 2. 30 active forecast rows exist. 3. Query returns arbitrary one. | Replace with a transactional "deactivate previous active rows + insert new row" flow. |
| 10 | AiChatRepositoryImpl.kt:63-75 | **MEDIUM** | Data Integrity | AI chat append persists message insert and session `updatedAt` touch as separate writes, so session ordering can drift if one write fails/interleaves. | 1. Message insert succeeds but session update fails. 2. Session shows stale `updatedAt`. | Move both writes into one Room transaction. |
| 11 | InvestmentValueDao.kt:24-37 | **HIGH** | Logic Error | Investment performance reports "all-time" high/low using only the last 30 days of values, which is logically incorrect. | 1. Investment had peak value 6 months ago. 2. "All-time high" shows 30-day max instead. | Add DAO aggregates for true all-time min/max and use them instead of the 30-day slice. |
| 12 | InvestmentTracker.kt:203-227 | **HIGH** | Performance | Portfolio value history performs an N+1 query pattern by fetching value history separately for each investment. | 1. Portfolio has 10 investments. 2. 10 separate DAO queries for value history. | Add a batched/date-grouped DAO query for portfolio history. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | SubscriptionCandidateDao ↔ NotificationProcessingPipeline | **HIGH** | Duplicate Candidates | Read-then-insert without transaction allows concurrent notifications to create duplicate pending candidates. | Add DB uniqueness constraint and use transactional upsert. |
| C2 | BudgetForecastDao ↔ BudgetForecastingEngine | **HIGH** | Stale Forecasts | Insert-only forecast persistence with no deactivation causes stale overlapping forecasts to accumulate. | Deactivate prior active forecasts transactionally before inserting new ones. |
| C3 | InvestmentValueDao ↔ InvestmentTracker | **HIGH** | Incorrect Metrics | "All-time" high/low is actually 30-day extrema, and portfolio history uses N+1 queries. | Add proper all-time aggregates and batched portfolio history query. |
| C4 | UserCorrectionDao ↔ MerchantNormalizer | **MEDIUM** | Unindexed Lookups | Merchant correction lookups are unindexed, causing table scans on the hot merchant-learning path. | Add index on `originalMerchant`. |

## Summary
- **Total issues: 16** (12 file-level + 4 cross-component)
- **Critical: 0**, **High: 8**, **Medium: 4**, **Low: 0**
- **Files with issues: 10/15 analyzed** (7 requested files don't exist: MerchantStatsDao, MerchantStatsSummaryDao, NotificationCaptureDao, PriceProtectionDao, TaxCategoryDao, TaxReportDao, TransactionInsightDao)

## Key Patterns

### 1. Missing Indexes on Hot Paths
Multiple DAOs filter/aggregate on columns without indexes: `originalMerchant` in user corrections, `(category, alertedAt)` in anomaly alerts, `(isBusinessTrip, date)` in mileage tracking. These turn O(1) lookups into full table scans.

### 2. Nondeterministic LIMIT 1 Queries
Multiple DAOs use `LIMIT 1` without `ORDER BY`: subscription candidates, budget forecasts, health score history, user correction aggregates. Once duplicates exist (which they can, due to missing unique constraints), results are arbitrary.

### 3. Insert-Only Persistence Patterns
Budget forecasts and health score history use insert-only patterns without deactivating or deduplicating prior rows, leading to data accumulation and nondeterministic reads.

### 4. Race Conditions in Read-Then-Insert
Subscription candidate dedupe and AI chat session updates use read-then-insert/write patterns without transactions, allowing concurrent operations to create duplicates or lose updates.
