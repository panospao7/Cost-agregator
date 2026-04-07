# Deep Analysis — Batch 14: Database - DAOs Core (@reviewer)

## Scope
- data/database/dao/ExpenseDao.kt
- data/database/dao/CategoryDao.kt
- data/database/dao/BudgetDao.kt
- data/database/dao/RecurringExpenseDao.kt
- data/database/dao/SavingsGoalDao.kt
- data/database/dao/SubscriptionDao.kt (not found in codebase)
- data/database/dao/WarrantyDao.kt
- data/database/dao/ReturnWindowDao.kt
- data/database/dao/RecommendationDao.kt
- data/database/dao/PendingReviewDao.kt
- data/database/dao/ScannedReceiptDao.kt
- data/database/dao/ReceiptItemCategorizationDao.kt

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `data/database/dao/BudgetDao.kt` | MAJOR | Data integrity | `getOverallBudget()` and `getByCategory()` use `LIMIT 1` with no `ORDER BY`, but the schema does not enforce "only one active budget" per overall/category. If duplicates exist, reads and notification updates hit an arbitrary row. | Add partial unique constraints for active budgets (`categoryId` when `isActive = 1`, plus a single active overall budget), and add deterministic ordering as a fallback. |
| 2 | `data/database/dao/ExpenseDao.kt` | MAJOR | SQL correctness | `getRecentMerchantNames()` uses `SELECT DISTINCT merchant ... ORDER BY date DESC`. That does **not** reliably return the most recently used unique merchants; the ordering date per distinct merchant is undefined. | Rewrite as `GROUP BY merchant ORDER BY MAX(date) DESC LIMIT 100`. |
| 3 | `data/database/dao/ExpenseDao.kt` | MAJOR | Performance / Index usage | `searchMerchants()` uses `UPPER(merchant) LIKE '%' || UPPER(:query) || '%'` and scans full rows in a CTE. This defeats existing merchant indexes and becomes a full-table scan on each keystroke. | Add an indexed normalized search column / FTS table, or switch to prefix search on an indexed key; also project only needed columns. |
| 4 | `data/database/dao/ExpenseDao.kt` | MAJOR | SQL correctness | `getBusinessExpensesMissingReceipts()` treats `rawNotificationId IS NULL` as "missing receipt". That is the wrong relation: manual/email/scanned expenses can already have a linked receipt through `scanned_receipts.expenseId` while still having `rawNotificationId = NULL`. | Replace the predicate with `NOT EXISTS (SELECT 1 FROM scanned_receipts sr WHERE sr.expenseId = expenses.id)` or add a real receipt-link flag. |
| 5 | `data/database/dao/ExpenseDao.kt` | MAJOR | SQL correctness | Business aggregates (`getTotalBusinessExpensesBetween`, `getBusinessExpensesByCategory`, `getBusinessExpensesByProject`) sum raw `amount` and ignore `isNotMine` / shared-expense share logic used elsewhere. Business reports can overstate spend. | Reuse the same effective-amount `CASE` logic used in other spending queries and filter out `isNotMine = 0`. |
| 6 | `data/database/dao/WarrantyDao.kt` | MAJOR | SQL correctness | `getTotalProtectedValue()` defines active warranties as `status = 'ACTIVE'` only, while `getActiveWarrantyCount()` also requires `warrantyEndDate > now`. If status cleanup lags, expired warranties stay in protected-value totals. | Add a `currentTime` parameter and filter by `warrantyEndDate > :currentTime`, or expire statuses before reading aggregates. |
| 7 | `data/database/dao/RecommendationDao.kt` | MAJOR | Time-based query correctness | `observeActiveByUser(userId, nowMillis = System.currentTimeMillis())` freezes "now" at subscription time. Expired recommendations can remain visible until another DB write or explicit cleanup occurs. | Drive `nowMillis` from a ticker/requery, or materialize expiry first (`expireOld`) and remove wall-clock filtering from the reactive query. |
| 8 | `data/database/dao/ReceiptItemCategorizationDao.kt` | MAJOR | SQL correctness | `getTotalForCategoryInExpense()` uses `suggestedCategoryId = :categoryId OR userCorrectedCategoryId = :categoryId`, so a corrected item can still be counted under its old suggested category. | Use `COALESCE(userCorrectedCategoryId, suggestedCategoryId) = :categoryId` so user corrections override suggestions. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | Batch plan / DAO layer / `AppDatabase` | MAJOR | `SubscriptionDao.kt` from the approved Batch B14 scope is not present in the repo, and `AppDatabase` exposes no subscription-core DAO/entity matching the plan. The batch is only reviewable as 11/12 files, which means the planned scope is out of sync with the implementation. | Update the batch plan to reflect the current subscription storage model, or restore the missing DAO/entity if subscription core persistence is still intended. |

### Summary
- Total issues: 9
- Files with issues: 5/12
- Requirements met: No — 11/12 requested DAO files were reviewable; `SubscriptionDao.kt` is missing. Parameter binding is generally safe, and I did not find a clear DAO-layer N+1 pattern beyond normal Room relation batching, but several query correctness/indexing issues remain.
- Testing adequate: No — no DAO-focused coverage was evident here for recent-merchant ordering, receipt-link detection, business aggregate correctness, corrected-category totals, budget uniqueness, or time-based recommendation expiry.

## @reviewer Addendum — Missing DAO Files

Analyzed the 6 previously missing DAO files:
- `CategoryDao.kt`
- `RecurringExpenseDao.kt`
- `SavingsGoalDao.kt`
- `ReturnWindowDao.kt`
- `PendingReviewDao.kt`
- `ScannedReceiptDao.kt`

### Additional Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 9 | `data/database/dao/CategoryDao.kt` | MAJOR | Data integrity | `getByName()` at lines 38-39 does a case-sensitive `WHERE name = :name LIMIT 1`, but the schema has no unique / `COLLATE NOCASE` index on `categories.name` (`app/schemas/.../70.json` lines 532-573). `CsvExpenseImporter.getOrCreateCategory()` (`util/CsvExpenseImporter.kt` lines 115-129) relies on that lookup before inserting, so duplicate category names can be created, and once duplicates exist this read returns an arbitrary row. | Add a unique normalized-name constraint (or unique `name COLLATE NOCASE` index), query case-insensitively, and keep a deterministic fallback order if duplicates already exist. |
| 10 | `data/database/dao/SavingsGoalDao.kt` | MAJOR | Transaction boundary / Lost update | `updateGoalAmount()` at lines 18-19 overwrites `currentAmount` with a caller-computed absolute value. `SavingsGoalsViewModel.contributeToGoal()` (`ui/screens/savings/SavingsGoalsViewModel.kt` lines 213-221) reads the old balance and then writes `goal.currentAmount + amount`, so concurrent contributions can lose one update. | Replace it with an atomic delta update (`SET currentAmount = currentAmount + :delta`) or perform read-modify-write inside a transaction with conflict detection. |
| 11 | `data/database/dao/ReturnWindowDao.kt` | MAJOR | Data integrity | `getReturnWindowByReceiptId()` / `getReturnWindowByExpenseId()` at lines 28-32 return a single row, but `return_windows` only has non-unique indexes on `receiptId` / `expenseId` (`app/schemas/.../70.json` lines 2869-3000). `insertReturnWindow()` at lines 34-35 accepts blind inserts, and `WarrantyTrackerRepository.addReturnWindow()` / extraction flow (`data/repository/WarrantyTrackerRepository.kt` lines 106-107 and 184-213) can recreate rows for the same receipt, making these lookups nondeterministic. | If the relation is intended to be 1:1, add unique constraints / upsert by `receiptId` (and `expenseId` if needed). Otherwise return `List<ReturnWindow>` and handle multiplicity explicitly. |
| 12 | `data/database/dao/PendingReviewDao.kt` | MAJOR | Performance / Index usage | The legacy-name fallback queries at lines 85-97, 118-124, 215-235, and 285-305 search by `suggestedMerchant` plus status/date, but `pending_reviews` has no index on `suggestedMerchant` (`app/schemas/.../70.json` lines 781-838). Those paths sit in duplicate-check code during notification ingestion and statement import (`data/repository/NotificationProcessingPipeline.kt` lines 505-513; `data/repository/ReceiptRepository.kt` lines 522-529 and 571-579), so legacy rows with `suggestedMerchantKey IS NULL` degrade into table scans. | Backfill merchant keys and add a composite index such as `(status, suggestedMerchant, suggestedDate)`; normalize stored currency so `UPPER()` can be removed from hot-path predicates. |
| 13 | `data/database/dao/PendingReviewDao.kt` | MAJOR | SQL correctness | `getPendingReviewsByMerchantAndDateRange()` / `getPendingByMerchant()` at lines 99-109 and 126-131 use keyed-first fallback instead of merging keyed and legacy name-only rows. If a merchant has both keyed rows and older `suggestedMerchantKey IS NULL` rows, the legacy rows disappear from merchant-specific review queries. `ReviewQueueRepository.getPendingReviewsByMerchant()` (`data/repository/ReviewQueueRepository.kt` lines 501-503) uses this path. | Return a merged `UNION` / distinct result set (or merge both lists inside the transaction) instead of returning early when keyed rows exist. |
| 14 | `data/database/dao/ScannedReceiptDao.kt` | MAJOR | SQL correctness / Cross-component state | `linkToExpense()` at lines 40-41 only sets `expenseId`; it leaves `matchStatus`, `suggestedExpenseId`, and `matchConfidence` untouched. Both `ReceiptRepository.createExpenseFromReceipt()` (`data/repository/ReceiptRepository.kt` lines 312-316) and `ReviewQueueRepository` approval flow (`data/repository/ReviewQueueRepository.kt` lines 133-139) call it. Because `getUnmatchedReceipts()` at lines 46-47 filters only on `matchStatus = 'UNMATCHED'`, already-linked receipts can remain in the unmatched queue and be re-matched again. | Make `linkToExpense()` atomically set a matched status and clear stale suggestion metadata; also harden `getUnmatchedReceipts()` to require `expenseId IS NULL`. |

### Additional Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 2 | `CategoryDao` / `CategoryRepository.ensureDefaultCategories()` / startup callers | MAJOR | `ensureDefaultCategories()` performs `getCount()` → `insertAll()` → follow-up reads across multiple DAO calls (`data/repository/CategoryRepository.kt` lines 27-64), and it is invoked from multiple startup/view-model paths (`ui/screens/home/HomeViewModel.kt` line 160, `ui/screens/categories/CategoryViewModel.kt` line 25, `ui/screens/debug/DebugViewModel.kt` line 247). Because `categories.name` is not unique (`app/schemas/.../70.json` lines 532-573), concurrent seeding can create duplicate default categories and duplicate merchant-category mappings. | Wrap the seed path in one DB transaction and enforce unique normalized category names so repeated initialization is idempotent. |
| 3 | `RecurringExpenseDao` / `RecurringExpenseRepository` / recurring engines | MAJOR | The app still exposes both `RecurringExpenseDao` and `ManualRecurringExpenseDao` (`data/database/AppDatabase.kt` lines 78-80; `di/DaoModule.kt` lines 79-84), but `RecurringExpenseRepository` keeps injecting the deprecated DAO (`data/repository/RecurringExpenseRepository.kt` lines 14-23). `RecurringExpenseEngine` then builds its manual override map from `getAll()` results (`domain/logic/RecurringExpenseEngine.kt` lines 38-41), so inactive legacy rows still influence recurring-pattern suppression even though the replacement DAO already exposes active-only queries. | Migrate the legacy repository / engines to `ManualRecurringExpenseDao` (or at least active-only methods) and retire the deprecated DAO binding. |

### Addendum Summary
- Newly analyzed DAO files: 6/6 missing files.
- New issues in this addendum: 7.
- Updated overall total issues: 16.
- Updated files with issues: 10/11 reviewable DAO files. I did not find a separate standalone DAO-query defect in `RecurringExpenseDao.kt`, but its deprecated wiring still creates a cross-component problem.
- Requirements met: Yes for the missing-file gap — all 6 missing DAO files were reviewed, and `SubscriptionDao.kt` was correctly skipped because it does not exist.
- Testing adequate: No — I still found no DAO/repository tests covering category uniqueness and seed races, atomic savings-goal contributions, return-window uniqueness, pending-review legacy-name completeness/performance, or receipt link-state transitions.
