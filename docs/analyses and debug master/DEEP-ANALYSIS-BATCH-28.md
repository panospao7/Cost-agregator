# Deep Analysis — Batch 28: Database — Advanced Entities (@reviewer)

> **[B.4 SYNC]** Issue dispositions below have been updated to reflect the B.4 final closeout. Authoritative resolutions are recorded in `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-28.md`.

## Scope
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiArtifactEntity.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiChatMessageEntity.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AiChatSessionEntity.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/BlockedPackage.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetAdjustmentRecommendation.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/BudgetForecast.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/InvestmentValue.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/MileageTracking.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/StressForecastSnapshot.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionPriceHistory.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/UserCorrection.kt`

Requested batch filenames not present in this repo:
- `data/database/entity/MerchantStatsEntity.kt`
- `data/database/entity/MerchantStatsSummaryEntity.kt`
- `data/database/entity/NotificationCaptureEntity.kt`
- `data/database/entity/PriceProtectionEntity.kt`
- `data/database/entity/TaxCategoryEntity.kt`
- `data/database/entity/TaxReportEntity.kt`
- `data/database/entity/TransactionInsightEntity.kt`
- `data/database/entity/WidgetStyleEntity.kt`
- `data/database/entity/WidgetStylePresetEntity.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/database/entity/AnomalyAlert.kt:12-26` | HIGH | Foreign key gap | `expenseId` is a required reference to an expense, but the entity declares no FK to `expenses(id)`. Deleting an expense leaves orphan `anomaly_alerts` rows that still surface through `getActiveAlerts()` and dashboard anomaly summaries. **[RESOLVED BY B.4 — Batch 6]** | Add `ForeignKey(entity = Expense::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = CASCADE)` (or nullable `SET_NULL` plus filtering), rebuild the table in migration, and purge existing orphans. |
| 2 | `data/database/entity/AnomalyAlert.kt:14-18` | MEDIUM | Index / performance | `AnomalyAlertDao.getLastAlertForCategory()` filters by `category` and `alertedAt`, but the table only indexes `expenseId`, `merchant+alertedAt`, `severity+alertedAt`, and `dismissed+alertedAt`. Category cooldown checks therefore degrade to scans on a hot write path. **[RESOLVED BY B.4 — late closeout: `Index(value = ["category", "alertedAt"])` annotation added to `AnomalyAlert` entity; `MIGRATION_77_78` creates `index_anomaly_alerts_category_alertedAt` (schema version 78)]** | Add `Index(value = ["category", "alertedAt"])` and include it in migration/repair SQL. |
| 3 | `data/database/entity/SubscriptionCandidate.kt:13-18` | HIGH | Data integrity / concurrency | The table has no DB-level uniqueness for the "one pending candidate per canonical merchant" invariant. `NotificationProcessingPipeline` does a check-then-insert in async jobs, so concurrent detections for the same merchant can both insert pending rows. **[RESOLVED BY B.4 — Batch 7]** | Enforce a DB uniqueness key for active/pending candidates (for example via a derived pending key / partial-unique strategy) and switch the insert path to a transactional upsert. |
| 4 | `data/database/entity/HealthScoreHistory.kt:14-18` | MEDIUM | Data integrity | The model behaves like one snapshot per `(periodStart, periodEnd)` window, but that pair is only indexed, not unique. `FinancialHealthScoreV2` does a read-then-insert/update, so concurrent recalculations can store duplicate rows for the same period and skew history analytics. **[RESOLVED BY B.4 — Batch 8]** | Make `(periodStart, periodEnd)` unique and replace the read-then-write sequence with an atomic upsert in a transaction. |
| 5 | `data/database/entity/BudgetForecast.kt:22-27,55-56` | HIGH | Lifecycle / schema contract | The schema has an `isActive` flag but no constraint preventing multiple active forecasts for the same budget/period. The forecasting engine inserts new rows without deactivating old ones, so "latest active forecast" becomes ambiguous and stale active rows accumulate. **[RESOLVED BY B.4 — Batch 7]** | Deactivate prior active rows for the same budget/period in the same transaction before insert, and add a business-key safeguard for the active row invariant. |
| 6 | `data/database/entity/UserCorrection.kt:21-27` | MEDIUM | Index / cross-component mismatch | The classifier-learning DAO queries repeatedly filter and aggregate by `originalMerchant`, but the entity has no index on that column. Merchant-correction lookups will full-scan as the table grows. **[RESOLVED BY B.4 — late closeout: `Index("originalMerchant")` annotation added to entity; `MIGRATION_76_77` creates `index_user_corrections_originalMerchant` (schema version 77)]** | Add at least `Index("originalMerchant")`; consider a composite such as `(packageName, originalMerchant)` or `(originalMerchant, wasRejected)` for the hottest queries. |
| 7 | `data/database/entity/RawNotification.kt:9-14,25-26` | MEDIUM | Schema / dedup correctness | The duplicate-prevention unique index includes nullable `title` and `text`. In SQLite, `NULL` values do not collide under a unique index, so `insertOrIgnore()` is not a reliable dedupe barrier for partially-null notifications. **[RESOLVED BY B.4 — Batch 6]** | Normalize `title`/`text` to non-null empty strings at the schema boundary, or rebuild dedupe around coalesced/generated columns so DB uniqueness matches app expectations. |
| 8 | `data/database/entity/MileageTracking.kt:35-55` | MEDIUM | Data integrity | Mileage invariants are completely unconstrained: negative `distanceKm`, negative deduction rates, negative deductions, or `endOdometer < startOdometer` can all be persisted. Those rows then feed tax-deduction totals directly. **[RESOLVED BY B.4 — Batch 8]** | Add `init` validation (or repository-layer validation) for non-negative values and odometer ordering before persistence. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `ExpenseRepository.deleteExpense()` → `AnomalyAlertDao.getActiveAlerts()` → `ComputeMoneyRadarUseCase` | HIGH | Orphaned reference | Because `anomaly_alerts.expenseId` is not protected by an FK and no cleanup runs on expense deletion, the dashboard can continue showing anomaly alerts for expenses that no longer exist. **[RESOLVED BY B.4 — Batch 6]** | Add FK-backed cleanup (preferred) or explicitly delete related anomaly alerts inside the expense-deletion transaction. |
| 2 | `NotificationProcessingPipeline.launchSubscriptionDetection()` → `SubscriptionCandidateDao.insert()` | HIGH | Race condition | Subscription detection runs fire-and-forget and only pre-checks for an existing pending merchant before insert. Without DB uniqueness, concurrent jobs can produce duplicate pending candidates for the same merchant. **[RESOLVED BY B.4 — Batch 7]** | Make the pending-candidate invariant enforceable at the DB layer and use upsert/transactional insertion. |
| 3 | `BudgetForecastingEngine.generateForecast()` → `BudgetForecastDao.getLatestActiveForecast()` | HIGH | State ambiguity | New forecasts are inserted as active, but existing active forecasts are not retired first. DAO reads then pick an arbitrary "latest active" row among a growing pile of active records. **[RESOLVED BY B.4 — Batch 7]** | Wrap deactivate-old + insert-new in one transaction and treat active forecast selection as a true invariant, not a best-effort convention. |
| 4 | `FinancialHealthScoreV2.saveToHistory()` → `HealthScoreHistoryDao.getHistoryForPeriod()/insert()/update()` | MEDIUM | Non-atomic upsert | The current read-then-write flow is race-prone and depends on a non-unique period key, so the same reporting window can be stored multiple times under concurrent recalculation. **[RESOLVED BY B.4 — Batch 8]** | Add a unique period key and collapse the operation into a single upsert inside a transaction. |
| 5 | `NotificationCaptureService` → `NotificationProcessingPipeline` → `RawNotificationDao.insertOrIgnore()` | HIGH | Dedupe gap | Partially-null raw notifications can bypass the unique index, so duplicate notifications may be reprocessed and, in the oversized-fallback path, create duplicate pending reviews. **[RESOLVED BY B.4 — Batch 6]** | Normalize nullable dedupe fields before persistence or rebuild uniqueness around coalesced/generated columns. |

## Summary
- Total issues: 8
- Critical: 0, High: 3, Medium: 5, Low: 0
- Files with issues: 7/16 reviewed source files

## Key Patterns
- Referential integrity is inconsistent: some "reference" columns (notably `anomaly_alerts.expenseId` and `subscription_candidates.convertedSubscriptionId`) are modeled as relationships in business logic but not enforced in schema.
- Several advanced tables rely on application-side conventions instead of DB invariants (`pending candidate per merchant`, `single active forecast per budget/period`, `single health snapshot per period`). That leaves correctness exposed to concurrency and multi-entry pipelines.
- Indexing is incomplete for actual DAO access patterns, especially on learning/cooldown paths (`category` lookups in anomaly alerts, `originalMerchant` lookups in user corrections).
- Numeric/state validation is generally absent in manual-entry or analytics snapshot entities, so malformed values can be persisted and later aggregated without any guardrails.
