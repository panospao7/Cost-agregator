# Final Verification — Batch 28: Database — Advanced Entities

## Scope
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
- `com/yourname/expensetracker/data/database/entity/RawNotification.kt`
- `com/yourname/expensetracker/data/database/entity/StressForecastSnapshot.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionPriceHistory.kt`
- `com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt`
- `com/yourname/expensetracker/data/database/entity/UserCorrection.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt:12-26` | High | Referential integrity | `expenseId` is treated as a required expense reference, but the table has no foreign key. `ExpenseRepository.deleteExpense()` deletes the expense only, and `ComputeMoneyRadarUseCase` still reads active alerts directly, so orphan anomaly alerts remain visible after parent deletion. | B | CONFIRMED | Add `ForeignKey(entity = Expense::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = CASCADE)` or delete related alerts in the same expense-deletion transaction; migrate and clean existing orphans. |
| 2 | `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt:14-18` | Medium | Performance / indexing | `AnomalyAlertDao.getLastAlertForCategory()` filters by `category` and `alertedAt`, but the entity only indexes `expenseId`, `merchant+alertedAt`, `severity+alertedAt`, and `dismissed+alertedAt`. Category cooldown checks therefore scan unnecessarily on a hot alerting path. | B | CONFIRMED | Add `Index(value = ["category", "alertedAt"])` and include it in migration/repair SQL. |
| 3 | `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt:12-18` | High | Concurrency / data integrity | The schema does not enforce the intended “one pending candidate per canonical merchant” invariant. `NotificationProcessingPipeline.detectAndSaveSubscriptionCandidate()` does a read-then-insert from fire-and-forget jobs, so concurrent detections can persist duplicate pending rows for the same merchant. | B | CONFIRMED | Enforce DB-level uniqueness for pending candidates and replace check-then-insert with a transactional upsert strategy. |
| 4 | `com/yourname/expensetracker/data/database/entity/HealthScoreHistory.kt:14-18` | Medium | Concurrency / data integrity | `(periodStart, periodEnd)` is only indexed, not unique. `FinancialHealthScoreV2.saveToHistory()` performs a read-then-insert/update sequence, so concurrent recalculations can create duplicate snapshots for the same reporting window. The bug is real, but the impact is lower than the debugger report claimed. | B | DOWNGRADED | Make `(periodStart, periodEnd)` unique and use a single transactional upsert. |
| 5 | `com/yourname/expensetracker/data/database/entity/BudgetForecast.kt:22-27,55-56` | Medium | State integrity | `isActive` is persisted without any safeguard that only one forecast per budget/period remains active. `BudgetForecastingEngine.generateForecast()` always inserts a new active row and never retires prior ones, so stale active rows accumulate; this can make date-based lookups nondeterministic if multiple active rows cover the same period. The issue is real, but current impact is lower than reported because `getLatestActiveForecast()` is ordered and presently unused outside the DAO. | B | DOWNGRADED | Deactivate older active forecasts for the same budget/period in the same transaction before inserting the new row, and add a schema/business-key guard for that invariant. |
| 6 | `com/yourname/expensetracker/data/database/entity/UserCorrection.kt:21-27` | Medium | Performance / indexing | `UserCorrectionDao` has multiple aggregation and lookup queries on `originalMerchant`, but the table has no index on that column. Merchant-learning queries will degrade to full scans as correction history grows. | B | CONFIRMED | Add at least `Index(value = ["originalMerchant"])`; consider a composite with `packageName` or rejection/approval flags for the hottest paths. |
| 7 | `com/yourname/expensetracker/data/database/entity/RawNotification.kt:7-14,25-26` | High | Dedupe correctness | The dedupe unique index includes nullable `title` and `text`. In SQLite, rows with `NULL` in a unique index do not collide, so `insertOrIgnore()` is not a reliable duplicate barrier for partially-null notifications. This is worse than the reviewer report indicated because the pipeline can then create duplicate oversized pending reviews when parsing fails and raw dedupe does not stop the duplicate early. | B | UPGRADED | Normalize dedupe fields to non-null values before insert, or enforce uniqueness via coalesced/generated columns so DB behavior matches app expectations. |
| 8 | `com/yourname/expensetracker/data/database/entity/MileageTracking.kt:35-55` | Medium | Validation / data integrity | The entity and repository accept impossible mileage states such as negative distance, negative deduction values, or `endOdometer < startOdometer`. Those values are later summed directly by mileage deduction/report queries, so invalid rows can corrupt tax outputs. | B | CONFIRMED | Add validation before persistence (entity `init`, repository guard, or DB check constraints) for non-negative values and odometer ordering. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt:55-58` | Low | Referential integrity | `convertedSubscriptionId` is documented as the ID of the related `ManualRecurringExpense`, but the schema does not enforce that relationship. Deleting the converted subscription can leave dangling IDs in historical candidates. | Add a nullable foreign key to `ManualRecurringExpense(id)` with `ON DELETE SET NULL`, and migrate existing rows accordingly. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| — | — | — | None identified. All reported issue families were real; two were severity overstatements rather than false positives. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `ExpenseRepository.deleteExpense()` → `AnomalyAlertDao.getActiveAlerts()` → `ComputeMoneyRadarUseCase` | High | Orphaned reference | Deleting an expense does not clean up anomaly alerts, and active alerts are rendered without re-checking parent expense existence. | `data/database/entity/AnomalyAlert.kt`, `data/repository/ExpenseRepository.kt`, `data/database/dao/AnomalyAlertDao.kt`, `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | Enforce FK-backed cleanup or delete related alerts inside the expense-deletion transaction. |
| 2 | `NotificationProcessingPipeline.launchSubscriptionDetection()` → `SubscriptionCandidateDao.insert()` | High | Race condition | Subscription detection runs asynchronously, pre-checks for a pending candidate, then inserts without any DB uniqueness guarantee, so concurrent jobs can create duplicate pending candidates. | `data/database/entity/SubscriptionCandidate.kt`, `data/database/dao/SubscriptionCandidateDao.kt`, `data/repository/NotificationProcessingPipeline.kt` | Add a DB-enforced pending-candidate uniqueness rule and use transactional upsert semantics. |
| 3 | `BudgetForecastingEngine.generateForecast()` → `BudgetForecastDao.getForecastForDate()` | Medium | State ambiguity | The engine never retires previous active forecasts, so repeated generations for the same budget period leave multiple active rows. Date-based active lookups can then become nondeterministic if overlapping active rows remain. | `data/database/entity/BudgetForecast.kt`, `data/database/dao/BudgetForecastDao.kt`, `domain/budget/BudgetForecastingEngine.kt` | Deactivate prior active rows transactionally before insert and enforce the active-row invariant at the schema/repository layer. |
| 4 | `FinancialHealthScoreV2.saveToHistory()` → `HealthScoreHistoryDao.getHistoryForPeriod()/insert()/update()` | Medium | Non-atomic upsert | History persistence depends on a non-unique period key plus a read-then-write sequence, so concurrent recalculations can store multiple snapshots for the same period. | `data/database/entity/HealthScoreHistory.kt`, `data/database/dao/HealthScoreHistoryDao.kt`, `domain/health/FinancialHealthScoreV2.kt` | Add a unique period key and collapse persistence into a single transactional upsert. |
| 5 | `NotificationCaptureService` → `NotificationProcessingPipeline` → `RawNotificationDao.insertOrIgnore()` | High | Dedupe gap | Partially-null raw notifications can bypass the unique index, so duplicate notifications may be reprocessed and, in the oversized-fallback path, create duplicate pending reviews. | `data/database/entity/RawNotification.kt`, `data/database/dao/RawNotificationDao.kt`, `service/NotificationCaptureService.kt`, `data/repository/NotificationProcessingPipeline.kt` | Normalize nullable dedupe fields before persistence or rebuild uniqueness around coalesced/generated columns. |

## Summary
- Total verified issues: 8
- Confirmed: 8 (Critical: 0, High: 3, Medium: 5, Low: 0)
- False positives: 0
- Missed issues found: 1
- Files affected: 7/16

## Key Patterns
- Business invariants are repeatedly implemented in application code but not enforced in schema (`expenseId` ownership, unique health periods, unique pending subscription candidates, single active forecast semantics).
- Several hot DAO paths are missing the indexes they actually query (`category` in anomaly alerts, `originalMerchant` in user corrections).
- Nullable columns are being used inside uniqueness contracts, which breaks dedupe assumptions under SQLite semantics.
- Manual-entry / analytics tables still lack basic validation guardrails, allowing malformed numeric data to flow into tax and reporting totals.
