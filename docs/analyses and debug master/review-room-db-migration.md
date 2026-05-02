# Review: Room DB Migration Analysis — Cross-Checked Against Current Codebase (v108)

**Date:** 2026-05-02  
**Analysis reviewed:** `room-db-migration-analysis.md` (written against v92, branch `master-refactor`)  
**Codebase reviewed:** `app/src/main/java/com/yourname/expensetracker/data/database/` (current, v108)  

---

## VERDICT: FAIL

The codebase has made significant progress since v92 — the most critical issues (budget/group_member invariance, expenseId uniqueness, raw_notification dedupe) are now resolved via materialized invariant key columns + unique indexes + CHECK constraints. However, several important issues remain unresolved or only partially addressed, and new `INSERT SELECT *` patterns were introduced in recent migrations.

---

## Issues

### [ISSUE-1] [MAJOR] Fresh-install vs migrated schema still has minor parity gap for raw_notifications — `AppDatabase.kt` (FRESH_INSTALL_CALLBACK lines 4893–4917, MIGRATION_84_85 lines 5471–5474)

**Status: PARTIALLY RESOLVED**

The original analysis flagged that `FRESH_INSTALL_CALLBACK` adds partial unique dedupe indexes on `raw_notifications` while `MIGRATION_84_85` drops them — creating a behavior gap between fresh and migrated DBs.

**What changed:** The `RawNotification` entity now has a `dedupeFingerprint TEXT` column with a `UNIQUE` index (line 18 of `RawNotification.kt`), applied to both fresh installs (via entity DDL) and migrated DBs (via `MIGRATION_104_105` lines 6144–6211). This is the **primary** dedupe mechanism now, rendering the partial indexes largely redundant.

**What remains:** `FRESH_INSTALL_CALLBACK` still creates the 4 partial unique indexes (lines 4893–4917), which `MIGRATION_84_85` still drops (lines 5471–5474). No post-92 migration re-applies them to migrated DBs. This is now a **low-severity** gap since `dedupeFingerprint` handles dedup, but it still means fresh installs have 4 extra indexes that migrated DBs don't.

**Suggested fix:** Remove the 4 partial indexes from `FRESH_INSTALL_CALLBACK` since `dedupeFingerprint` now handles dedup. Add a comment explaining the migration to the fingerprint-based approach.

---

### [ISSUE-2] [MAJOR] Budget forecast and subscription candidate invariants still not DB-enforced — `BudgetForecast.kt`, `SubscriptionCandidate.kt`

**Status: PARTIALLY RESOLVED**

Three of the five invariant groups are now DB-enforced via materialized key columns + unique indexes + CHECK constraints (budgets via `activeOverallKey`/`activeCategoryKey`, group_members via `currentUserGroupKey`, planned_expenses via `openSourceOccurrenceKey`). This is a major improvement.

**What remains unresolved:**

- **Budget forecasts** (`BudgetForecast.kt`): `MIGRATION_89_90` drops `index_budget_forecasts_active_budget_period` (the partial unique index enforcing "one active forecast per budget/period"). No materialized key was added for this invariant. The entity at line 23–25 only has regular non-unique indexes. Two active forecasts for the same budget/period can still be inserted.

- **Subscription candidates** (`SubscriptionCandidate.kt`): `MIGRATION_90_91` drops `index_subscription_candidates_pending_merchant_interval`. No materialized key was added. The entity at line 18–20 only has non-unique indexes on `canonicalMerchant`, `isConverted`, and `confidence`.

**Impact:** Duplicate active forecasts or duplicate pending subscription candidates can still enter the DB via raw inserts or race conditions.

**Suggested fix:** Add materialized key columns (`activeBudgetPeriodKey` for budget_forecasts, `pendingMerchantIntervalKey` for subscription_candidates) with UNIQUE indexes and CHECK constraints, same pattern as budgets/group_members/planned_expenses.

---

### [ISSUE-3] [CRITICAL] `group_expenses.paidById` same-group enforcement still explicitly out of scope — `AppDatabase.kt` line 3764

**Status: STILL PRESENT**

The original analysis reported that `paidById` can reference a `GroupMember` from a different group because there is no DB rule enforcing `group_expenses.groupId == group_members.groupId` for `paidById`.

**What changed:** The `paidById` FK was changed from `ON DELETE CASCADE` to `ON DELETE RESTRICT` (line 34 of `GroupExpense.kt`), which prevents accidental deletion of a payer member. But the cross-group invariant is still not enforced.

The comment at line 3764 explicitly states: "Trigger-based paidById same-group enforcement is explicitly OUT OF SCOPE."

**Impact:** A row can still exist where the group expense belongs to group A but the payer belongs to group B, corrupting group balances and payer credit.

**Suggested fix:** Add BEFORE INSERT and BEFORE UPDATE triggers as recommended in the original analysis (lines 379–393 of `room-db-migration-analysis.md`). Or add a materialized key column similar to the budget/group_member pattern.

---

### [ISSUE-4] [MAJOR] `INSERT SELECT *` still used in critical table-rebuild migrations — `AppDatabase.kt` multiple locations

**Status: PARTIALLY RESOLVED**

The original analysis flagged that `MIGRATION_75_76` uses `INSERT INTO expenses_new SELECT * FROM expenses` (fragile — assumes exact column order/count match).

**What changed:** Some migrations now use explicit column lists (e.g., `MIGRATION_85_86` lines 5519–5553 uses explicit columns for budgets rebuild). Good.

**What remains** — `SELECT *` still appears in these critical rebuild paths:

| Migration | Table | Line |
|-----------|-------|------|
| MIGRATION_49_50 | `expenses` | 1761 |
| MIGRATION_75_76 | `expenses` | 4755 |
| MIGRATION_106_107 | `budgets` | 6316 |
| MIGRATION_106_107 | `group_members` | 6349 |
| MIGRATION_106_107 | `planned_expenses` | 6395 |
| MIGRATION_107_108 | `planned_expenses` | 6479 |
| FRESH_INSTALL_CALLBACK | `savings_goals`, `mileage_tracking`, `pending_reviews`, `group_members`, `planned_expenses` | 4934, 4969, 5007, 5098, 5138 |

FRESH_INSTALL_CALLBACK `SELECT *` is harmless (tables are empty on fresh install). But the migration paths (49→50, 75→76, 106→107, 107→108) are dangerous for long-hop upgrade paths where column-order drift could silently corrupt data.

**Impact:** If a legacy user has column-order drift from earlier schema changes, these migrations can copy wrong values into wrong columns.

**Suggested fix:** Replace all remaining `INSERT INTO ... SELECT *` with explicit column lists matching the new table's column order, as already done in `MIGRATION_85_86`.

---

### [ISSUE-5] [MEDIUM] `repairTable()` in MIGRATION_67_68 still uses all-or-nothing salvage — `AppDatabase.kt` lines 3438–3470

**Status: STILL PRESENT**

The `repairTable()` helper (lines 3438–3470) still drops all old data if even **one** canonical column is missing:
```kotlin
if (exists && oldColumns.containsAll(canonicalColumns.toSet())) {
    // copy
} // else: data lost
```

No partial salvage (intersection of columns, defaults for missing ones) was added.

**Impact:** For malformed tables, all user-visible data in anomaly_alerts, prompt_states, health_score_history, subscription_candidates, budget_adjustment_records, and email_receipt_sources can still be silently dropped.

**Suggested fix:** Implement partial salvage as suggested in the original analysis: intersect old and new columns, copy available columns, supply defaults for missing ones, log outcome.

---

### [ISSUE-6] [MAJOR] Exchange rate uniqueness still only per currency pair, not per pair+date — `ExchangeRate.kt`

**Status: PARTIALLY RESOLVED**

The `ExchangeRate` entity now has a `validDate` field (line 36) and a lookup index on `(fromCurrency, toCurrency, validDate)` (line 22). This allows storing multiple rates for different dates.

**What remains:** The **unique** index is still only on `(fromCurrency, toCurrency)` (line 21), meaning at most one row per currency pair can exist — historical rates still get overwritten. The entity comment (lines 12–16) still warns about this limitation.

**Impact:** Past reports can change after rate refresh. Historical reporting is not accurate.

**Suggested fix:** Change the unique index to `(fromCurrency, toCurrency, validDate, source)` and remove or demote the pair-only unique constraint. Add a migration to deduplicate existing data before applying the new uniqueness.

---

### [ISSUE-7] [MINOR] String `@ColumnInfo(defaultValue)` annotations still inconsistent — multiple entity files

**Status: PARTIALLY RESOLVED**

The original analysis flagged inconsistent quoting of string defaults in `@ColumnInfo` annotations.

**Current state:**
- **Quoted** (correct per Room best practice): `Budget.kt` (`"'EUR'"`, `"'ROLLING'"`), `PlannedExpense.kt` (`"'EUR'"`), `BudgetForecast.kt` (`"'EUR'"`)
- **Unquoted**: `Expense.kt` (`"EUR"`, `"UNKNOWN"`), `GroupExpense.kt` (`"EUR"`), `ManualRecurringExpense.kt` (`"EUR"`), `SubscriptionCandidate.kt` (`"EUR"`, `"pending"`)

Room typically handles both forms, but this inconsistency is brittle and can cause schema drift between Room's generated JSON and migration-created SQL.

**Suggested fix:** Standardize on quoted form (`"'EUR'"`) for all string defaults across all entities, then regenerate schema JSON and verify migration alignment.

---

### [ISSUE-8] [MEDIUM] Cascade deletes still risk financial history loss — `GroupExpense.kt`, `Warranty.kt`, `ReturnWindow.kt`

**Status: PARTIALLY RESOLVED**

`paidById` FK changed to `RESTRICT` (prevents member deletion while referenced as payer). Good.

**What remains:**
- `GroupExpense.groupId → ExpenseGroup ON DELETE CASCADE` (line 22)
- `GroupExpense.expenseId → Expense ON DELETE CASCADE` (line 28)
- `Warranty.receiptId → ScannedReceipt ON DELETE CASCADE`
- `ReturnWindow.receiptId → ScannedReceipt ON DELETE CASCADE`

Deleting a group silently deletes all group expense records. Deleting an expense silently removes its group links. Deleting a receipt silently removes warranties and return windows.

**Impact:** Financial audit context can be lost without user awareness.

**Suggested fix:** Add soft-delete columns (`deletedAt`, `archivedAt`) for financial tables, or at minimum change cascades on expense/group to `SET NULL` and handle cleanup in coordinated transactions.

---

### [ISSUE-9] [MINOR] New schema gap: schema jumped from 96 to 100 — `AppDatabase.kt` line 6633

**Observation:** `MIGRATION_96_100` jumps 4 versions (97, 98, 99 skipped). The comment at lines 5893–5907 explains this is intentional (collapsed Phase 5 migrations). This is documented and acceptable but worth noting for future migration authors: if a user is on v97/v98/v99 from an intermediate build, no migration path exists.

---

### Issues RESOLVED (confirmed)

The following issues from the original analysis are **fully resolved** in the current codebase:

| # | Issue | Resolution |
|---|-------|------------|
| 3 | `group_expenses.expenseId` non-unique | Entity now has `Index(value = ["expenseId"], unique = true)`. Applied via MIGRATION_104_105. |
| 4 | `group_members` no current-user enforcement | `currentUserGroupKey` + UNIQUE index + CHECK constraint. Applied via MIGRATION_104_105 + MIGRATION_106_107. |
| 6 | Budget uniqueness not DB-enforced | `activeOverallKey`/`activeCategoryKey` + UNIQUE indexes + CHECK constraint. Applied via same migrations. |
| 7 | Raw notification dedupe inconsistent | `dedupeFingerprint` column + UNIQUE index. Applied via MIGRATION_104_105. |
| 11 | Recurring/planned lifecycle tables missing | `RecurringOccurrence`, `RecurringReminderDelivery`, `RecurringLifecycleEvent`, `BackgroundJobRun` all exist. PlannedExpense has lifecycle fields. |
| 12 | `PlannedExpense` no uniqueness guard | `openSourceOccurrenceKey` + UNIQUE index + CHECK constraint. |

---

## New Issues Found (not in original analysis)

### [ISSUE-N1] [MINOR] MIGRATION_107_108 CHECK constraint gap existed in 106→107 — `AppDatabase.kt` lines 6414–6418

The CHECK constraint in MIGRATION_106_107 for planned_expenses allowed a PLANNED row with `sourceOccurrenceKey = 'A'` to have `openSourceOccurrenceKey = 'B'` (any non-null value). MIGRATION_107_108 fixes this. This is a good catch but indicates the 106→107 constraint was shipped with a logic gap. Any rows inserted between migration 106 and 107 may have broken the invariant. The fix migration heals data before applying the stricter constraint, which is correct.

---

## Coverage

- **Requirements met:** Yes — all 15 issues from the original analysis were cross-checked against the current v108 codebase. The most critical invariants (budget, group_member, group_expense) are now DB-enforced.
- **Testing adequate:** Unknown — the original analysis's test recommendations (fresh-vs-migrated parity test, constraint violation tests, long-hop migration tests) were not verified in this review because test files were not in scope. The existence of `DatabaseMigrationTest.kt` is noted but not reviewed.

---

## Priority Recommendations

If only three fixes are made next:

1. **[ISSUE-3]** Add trigger-based same-group enforcement for `group_expenses.paidById` — this is the most impactful remaining invariant gap.
2. **[ISSUE-4]** Replace remaining `INSERT SELECT *` with explicit column lists in MIGRATION_49_50, MIGRATION_75_76, MIGRATION_106_107, and MIGRATION_107_108 — reduces silent corruption risk for long-hop upgraders.
3. **[ISSUE-2]** Add materialized invariant keys for `budget_forecasts` and `subscription_candidates` — complete the DB enforcement coverage.
