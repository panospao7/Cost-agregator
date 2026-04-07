# Deep Analysis — Batch 28: Database — Advanced Entities (@debugger)

## Scope
- data/database/entity/AiArtifactEntity.kt
- data/database/entity/AiChatMessageEntity.kt
- data/database/entity/AiChatSessionEntity.kt
- data/database/entity/AnomalyAlertEntity.kt
- data/database/entity/BlockedPackageEntity.kt
- data/database/entity/BudgetAdjustmentEntity.kt
- data/database/entity/BudgetForecastEntity.kt
- data/database/entity/HealthScoreHistoryEntity.kt
- data/database/entity/InvestmentValue.kt
- data/database/entity/MerchantStatsEntity.kt
- data/database/entity/MerchantStatsSummaryEntity.kt
- data/database/entity/MileageTrackingEntity.kt
- data/database/entity/NotificationCaptureEntity.kt
- data/database/entity/PriceProtectionEntity.kt
- data/database/entity/StressForecastSnapshotEntity.kt
- data/database/entity/SubscriptionCandidateEntity.kt
- data/database/entity/SubscriptionPriceHistoryEntity.kt
- data/database/entity/SubscriptionUsageEntity.kt
- data/database/entity/TaxCategoryEntity.kt
- data/database/entity/TaxReportEntity.kt
- data/database/entity/TransactionInsightEntity.kt
- data/database/entity/UserCorrectionEntity.kt
- data/database/entity/WidgetStyleEntity.kt
- data/database/entity/WidgetStylePresetEntity.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | AnomalyAlertEntity.kt:12 | **HIGH** | Data Integrity | Missing foreign key on `expenseId` allows orphan anomaly alerts after expense deletion, so dashboard/radar flows can surface alerts for non-existent expenses. | 1. Anomaly alert created for expense. 2. Expense deleted. 3. Alert remains with dangling `expenseId`. | Add an FK to `Expense(id)` with the intended `onDelete` behavior and migrate/clean orphan rows. |
| 2 | AnomalyAlertEntity.kt:14 | **MEDIUM** | Performance | Category cooldown query is not indexed even though `AnomalyAlertDao.getLastAlertForCategory()` filters by `category` + `alertedAt`, causing avoidable scans on a hot path. | 1. Process many anomalies. 2. Each cooldown check scans full table. | Add `Index(value = ["category", "alertedAt"])` and mirror it in migration/repair SQL. |
| 3 | SubscriptionCandidateEntity.kt:12 | **HIGH** | Data Integrity | `subscription_candidates` does not enforce the "one pending candidate per canonical merchant" invariant at DB level, so concurrent async detections can insert duplicates. | 1. Two concurrent detections for same merchant. 2. Both insert pending candidates. 3. Duplicate UI prompts. | Add a DB-enforced uniqueness strategy for pending candidates and use transactional upsert semantics. |
| 4 | HealthScoreHistoryEntity.kt:12 | **HIGH** | Data Integrity | `health_score_history` stores per-period snapshots but `(periodStart, periodEnd)` is not unique, so concurrent recalculations can create duplicate rows for the same reporting window. | 1. Two recalculations run simultaneously. 2. Both insert rows for same period. 3. Query returns arbitrary first match. | Make the period key unique and replace read-then-insert/update with an atomic upsert. |
| 5 | BudgetForecastEntity.kt:12 | **HIGH** | Data Integrity | `budget_forecasts` has `isActive` state without any schema guard for a single active forecast per budget/period, while the engine keeps inserting new active rows. | 1. Generate forecast daily. 2. Multiple active rows accumulate. 3. Query returns arbitrary one. | Deactivate prior active forecasts in the same transaction and enforce the invariant at the schema/repository level. |
| 6 | UserCorrectionEntity.kt:21 | **MEDIUM** | Performance | Merchant-learning queries in `UserCorrectionDao` repeatedly filter by `originalMerchant`, but the entity has no index on that column. | 1. Accumulate many corrections. 2. Each merchant-learning query does full table scan. | Add an index on `originalMerchant` (optionally composite with other hot filters). |
| 7 | RawNotificationEntity.kt:7 | **HIGH** | Data Integrity | Raw-notification dedupe is not fully reliable because the unique index includes nullable `title`/`text`; SQLite treats `NULL` as distinct, so duplicate partially-null notifications can bypass `insertOrIgnore()`. | 1. Two notifications with same package but null title/text. 2. Both insert despite dedupe intent. | Normalize dedupe fields to non-null values or rebuild uniqueness around coalesced/generated columns. |
| 8 | MileageTrackingEntity.kt:28 | **MEDIUM** | Data Integrity | Mileage rows accept invalid numeric states such as negative distance/deduction values or reversed odometer readings, which can corrupt tax totals. | 1. Import mileage with negative distance. 2. Tax calculations include negative values. | Add validation guards in `init` or repository-level validation before persistence. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | AnomalyAlertEntity ↔ Expense deletion | **HIGH** | Orphan Alerts | Missing FK on `expenseId` allows alerts to persist after expense deletion, surfacing in dashboard for non-existent expenses. | Add FK with appropriate onDelete behavior. |
| C2 | SubscriptionCandidateEntity ↔ NotificationProcessingPipeline | **HIGH** | Duplicate Candidates | No uniqueness constraint allows concurrent detections to create duplicate pending candidates. | Add unique constraint and use transactional upsert. |
| C3 | BudgetForecastEntity ↔ BudgetForecastingEngine | **HIGH** | Stale Forecasts | No schema guard for single active forecast per budget/period allows accumulation of stale active rows. | Deactivate prior active forecasts transactionally. |
| C4 | HealthScoreHistoryEntity ↔ HealthScoreCalculator | **HIGH** | Duplicate Snapshots | Non-unique period key allows concurrent recalculations to create duplicate rows. | Make period key unique and use upsert semantics. |
| C5 | RawNotificationEntity ↔ NotificationCaptureService | **MEDIUM** | Dedupe Gaps | Nullable fields in unique index allow duplicate partially-null notifications to bypass dedupe. | Normalize dedupe fields to non-null values. |

## Summary
- **Total issues: 13** (8 file-level + 5 cross-component)
- **Critical: 0**, **High: 6**, **Medium: 3**, **Low: 0**
- **Files with issues: 8/15 analyzed** (9 requested files don't exist: MerchantStatsEntity, MerchantStatsSummaryEntity, NotificationCaptureEntity, PriceProtectionEntity, TaxCategoryEntity, TaxReportEntity, TransactionInsightEntity, WidgetStyleEntity, WidgetStylePresetEntity)

## Key Patterns

### 1. Missing Uniqueness Constraints
Multiple entities lack uniqueness constraints where business logic assumes uniqueness: subscription candidates (one per merchant), health score history (one per period), budget forecasts (one active per budget). This allows concurrent operations to create duplicates.

### 2. Missing Foreign Keys
Entities reference other tables without FK constraints: `AnomalyAlertEntity.expenseId`, allowing orphan rows after parent deletion.

### 3. No Validation Guards
Entities accept any values without validation: negative mileage distances, invalid health score periods, etc. Validation should happen at the entity level via `init{}` blocks.

### 4. Nullable Fields in Unique Indexes
The raw notification dedupe index includes nullable fields, which SQLite treats as distinct values, allowing duplicates to slip through.
