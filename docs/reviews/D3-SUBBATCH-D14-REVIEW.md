# D3 Review — SubBatch D.14

Scope audited:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` → `### SubBatch D.14`
- `docs/reviews/AUDIT-PHASE-C-D.md`

Summary:
- Total issues audited: **14**
- **RESOLVED:** 6
- **PARTIALLY_RESOLVED:** 1
- **STILL_OPEN:** 7
- **FALSE_POSITIVE:** 0

## Detailed Audit

### D14-01 — `ExpenseGroupDao.insertGroupWithMembers()` unused in production
- **Classification:** STILL_OPEN
- **Evidence:** `ExpenseGroupDao.kt:74-92` still defines the helper. Grep found no production callsites. `GroupTransactionCoordinator.createGroupWithMembers()` performs its own `database.withTransaction { groupDao.insert(...); memberDao.insertAll(...) }` flow instead (`GroupTransactionCoordinator.kt:82-90`).
- **Suggested registry wording if status should change:** No status change.

### D14-02 — `ExpenseGroupDao` `groupId <= 0` guard unreachable
- **Classification:** STILL_OPEN
- **Evidence:** The guard is still present in `ExpenseGroupDao.kt:79-82`. `insert(group)` uses Room `@Insert` with default abort behavior, so it returns a positive row id or throws; this branch is still unreachable in normal Room semantics.
- **Suggested registry wording if status should change:** No status change.

### D14-03 — `ExpenseGroupDao` `memberIds.any { it <= 0 }` guard unreachable
- **Classification:** STILL_OPEN
- **Evidence:** The guard is still present in `ExpenseGroupDao.kt:84-89`. `GroupMemberDao.insertAll()` is also a plain `@Insert`, so failed inserts throw instead of returning non-positive ids.
- **Suggested registry wording if status should change:** No status change.

### D14-04 — `ManualRecurringExpenseDao.insert()` uses `REPLACE`
- **Classification:** RESOLVED
- **Evidence:** `ManualRecurringExpenseDao.kt:45-46` now uses `@Insert(onConflict = OnConflictStrategy.ABORT)` instead of `REPLACE`.
- **Suggested registry wording if status should change:**

```markdown
- `ManualRecurringExpenseDao.insert()` uses `REPLACE` — use `ABORT` for create-only (B15) **[RESOLVED - DAO now uses `@Insert(onConflict = OnConflictStrategy.ABORT)`]**
```

### D14-05 — `MerchantNormalizationDao.linkAliasToCanonical()` read-then-insert
- **Classification:** STILL_OPEN
- **Evidence:** `MerchantNormalizationDao.kt:104-123` still does `getAliasByRawName()` followed by conditional `updateAlias(...)` / `insertAlias(...)`. This is still not a true SQL upsert, and the insert path remains `IGNORE`-based.
- **Suggested registry wording if status should change:** No status change.

### D14-06 — `ExpenseDao.getChanges()` exposes SQLite `changes()` as standalone helper
- **Classification:** STILL_OPEN
- **Evidence:** `ExpenseDao.kt:93-94` still exposes `@Query("SELECT changes()") suspend fun getChanges(): Int`, and grep found no current production callsites.
- **Suggested registry wording if status should change:** No status change.

### D14-07 — `ScannedReceiptDao.linkToExpense()` updates only `expenseId`
- **Classification:** RESOLVED
- **Evidence:** `ScannedReceiptDao.kt:40-41` now updates both `expenseId` and `matchStatus = 'AUTO_MATCHED'`, which resolves the original issue text.
- **Suggested registry wording if status should change:** No status change.
- **Note:** The current closeout note overstates the implementation: the query does **not** clear `suggestedExpenseId` or `matchConfidence`.

### D14-08 — `ReturnWindowDao` returns single row without 1:1 enforcement
- **Classification:** PARTIALLY_RESOLVED
- **Evidence:** `ReturnWindow.kt:25-28` now enforces a unique index on `expenseId`, so `getReturnWindowByExpenseId()` is 1:1-safe. But `ReturnWindowDao.kt:28-29` still returns a single row for `receiptId` without DB-level uniqueness on `receiptId`.
- **Suggested registry wording if status should change:**

```markdown
- `ReturnWindowDao` single-row contract is only partially enforced: `expenseId` is now unique, but `receiptId` is still non-unique while `getReturnWindowByReceiptId()` returns a single row — add DB-level uniqueness on `receiptId` or return a list for receipt-based lookups (B14) **[PARTIALLY_RESOLVED - `expenseId` uniqueness is enforced; `receiptId` uniqueness is not]**
```

### D14-09 — `SpendingThresholdCalculator` percentile query uses raw `amount`
- **Classification:** RESOLVED
- **Evidence:** `ExpenseDao.kt:801-809` now selects `EFFECTIVE_AMOUNT_SQL` in `getAmountsForPercentileCalc(...)`, filtered to PURCHASE rows only. The android test suite also contains direct coverage for the shared-expense case.
- **Suggested registry wording if status should change:**

```markdown
- `SpendingThresholdCalculator` percentile query uses raw `amount` not `effectiveAmount` — shared expenses inflate threshold, anomaly detection less sensitive (B36-missed) **[RESOLVED - `ExpenseDao.getAmountsForPercentileCalc()` now uses canonical `EFFECTIVE_AMOUNT_SQL` for PURCHASE-only percentile inputs]**
```

### D14-10 — `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException`
- **Classification:** RESOLVED
- **Evidence:** `AnomalyAlertOrchestrator.kt:154-160` now explicitly rethrows `CancellationException` before generic exception handling in both the inner and outer catch blocks.
- **Suggested registry wording if status should change:**

```markdown
- `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException` — re-throw before generic catch (B36-missed) **[RESOLVED - inner/outer catch blocks now rethrow `CancellationException` before generic handling]**
```

### D14-11 — `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end for end-exclusive query
- **Classification:** RESOLVED
- **Evidence:** `AdvancedAnalyticsDashboard.kt:166-224` now advances month buckets with `nextMonthStart` and clamps to a half-open `[bucketStart, bucketEnd)` range. `AdvancedAnalyticsDashboardTest.kt:81-113` also verifies end-date exclusion semantics.
- **Suggested registry wording if status should change:**

```markdown
- `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end passed to end-exclusive repo query — use start-of-next-month exclusive boundary (B36-missed) **[RESOLVED - monthly buckets now use calendar-month starts with next-month exclusive end boundaries]**
```

### D14-12 — `AdvancedAnalyticsDashboard` monthly trend N+1 pattern
- **Classification:** RESOLVED
- **Evidence:** `AdvancedAnalyticsDashboard.kt:170-201` loads the requested range once via `expenseRepository.getExpensesBetween(startDate, endDate)` and groups in memory by month; there is no per-month repository query inside `getMonthlyTrend()` anymore.
- **Suggested registry wording if status should change:**

```markdown
- `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — batch into single date-range query or aggregate SQL (B36-missed) **[RESOLVED - `getMonthlyTrend()` now loads the range once and groups monthly buckets in memory]**
```

### D14-13 — `AdvancedAnalyticsEngine` current-period sparklines stop before today
- **Classification:** STILL_OPEN
- **Evidence:** `AdvancedAnalyticsEngine.kt:621-645` computes `daysPassed = ((now - period.startMs) / DAY).toInt()` and then builds points with `for (i in 0 until daysPassed)`. For a current in-progress day, that excludes today's index entirely; on day 1 it can still produce an empty sparkline.
- **Suggested registry wording if status should change:** No status change.

### D14-14 — `SpendingPersonalityClassifier` confidence mixes normalized `0..1` features with raw `transactionsPerMonth`
- **Classification:** STILL_OPEN
- **Evidence:** `SpendingPersonalityClassifier.kt:143` stores raw `transactionsPerMonth = purchases.size / ANALYSIS_MONTHS`, and `calculateConfidence()` still uses `featureScores.values` wholesale when computing variance (`SpendingPersonalityClassifier.kt:398-402`). The raw count-scale feature is still mixed with normalized features.
- **Suggested registry wording if status should change:** No status change.

## Registry Update Instructions

Apply these status updates in `MASTER-ISSUE-REGISTRY.md` under `### SubBatch D.14`.

1. Replace:

```markdown
- `ManualRecurringExpenseDao.insert()` uses `REPLACE` — use `ABORT` for create-only (B15)
```

With:

```markdown
- `ManualRecurringExpenseDao.insert()` uses `REPLACE` — use `ABORT` for create-only (B15) **[RESOLVED - DAO now uses `@Insert(onConflict = OnConflictStrategy.ABORT)`]**
```

2. Replace:

```markdown
- `ReturnWindowDao` returns single row without 1:1 enforcement — enforce uniqueness (B14)
```

With:

```markdown
- `ReturnWindowDao` single-row contract is only partially enforced: `expenseId` is now unique, but `receiptId` is still non-unique while `getReturnWindowByReceiptId()` returns a single row — add DB-level uniqueness on `receiptId` or return a list for receipt-based lookups (B14) **[PARTIALLY_RESOLVED - `expenseId` uniqueness is enforced; `receiptId` uniqueness is not]**
```

3. Replace:

```markdown
- `SpendingThresholdCalculator` percentile query uses raw `amount` not `effectiveAmount` — shared expenses inflate threshold, anomaly detection less sensitive (B36-missed)
```

With:

```markdown
- `SpendingThresholdCalculator` percentile query uses raw `amount` not `effectiveAmount` — shared expenses inflate threshold, anomaly detection less sensitive (B36-missed) **[RESOLVED - `ExpenseDao.getAmountsForPercentileCalc()` now uses canonical `EFFECTIVE_AMOUNT_SQL` for PURCHASE-only percentile inputs]**
```

4. Replace:

```markdown
- `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException` — re-throw before generic catch (B36-missed)
```

With:

```markdown
- `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException` — re-throw before generic catch (B36-missed) **[RESOLVED - inner/outer catch blocks now rethrow `CancellationException` before generic handling]**
```

5. Replace:

```markdown
- `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end passed to end-exclusive repo query — use start-of-next-month exclusive boundary (B36-missed)
```

With:

```markdown
- `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end passed to end-exclusive repo query — use start-of-next-month exclusive boundary (B36-missed) **[RESOLVED - monthly buckets now use calendar-month starts with next-month exclusive end boundaries]**
```

6. Replace:

```markdown
- `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — batch into single date-range query or aggregate SQL (B36-missed)
```

With:

```markdown
- `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — batch into single date-range query or aggregate SQL (B36-missed) **[RESOLVED - `getMonthlyTrend()` now loads the range once and groups monthly buckets in memory]**
```

### Optional wording cleanup (status unchanged)

If you want the existing resolved note to match current code more precisely, narrow the D14 `ScannedReceiptDao.linkToExpense()` closeout note from:

```markdown
**[RESOLVED BY B.4 — Batch 9: link atomically sets matched status and clears suggestion metadata]**
```

To:

```markdown
**[RESOLVED BY B.4 — Batch 9: link now updates `expenseId` and atomically sets `matchStatus = 'AUTO_MATCHED'`]**
```

## Batch 6 Registry Sync Addendum

- D14-13 (`AdvancedAnalyticsEngine` current-period sparkline boundaries/indexing): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: sparkline day indexing is now day-safe and current-period output includes today.
