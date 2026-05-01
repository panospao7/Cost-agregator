# Database Invariants & Migration Parity Audit

**Date:** 2026-05-01  
**Phase:** 7 — Database Invariants and Migration Parity  
**Scope:** All `.kt` files in `app/src/main/java/` + `app/src/androidTest/` + `app/schemas/`

---

## Summary Statistics

| Metric | Count |
|---|---|
| Total tables (Room entities) | 48 |
| Unique indexes (Room-declared + callback) | ~25 unique constraints |
| Foreign keys | ~35 FK declarations |
| CHECK constraints | 10 (on 4 tables) |
| SQLite triggers | **0** |
| Migration count | 85 migrations (v6→v104) |
| Schema JSON files | 60 (gaps: v1-32, 54-55, 58, 61-63, 66, 97-99 missing) |
| Missing constraints identified | **6** |
| `fallbackToDestructiveMigration()` | **Not called** (correct) |

---

## 1. Existing Constraints & Indexes

### 1.1 Unique Indexes (Room-Declared)

| Table | Index Columns | Notes |
|---|---|---|
| `expenses` | `dedupeKey` (UNIQUE) | Atomic duplicate prevention |
| `merchant_canonicals` | `normalizedName` (UNIQUE) | |
| `merchant_canonicals` | `searchKey` (UNIQUE) | Upgraded in MIGRATION_72_73 |
| `merchant_aliases` | `rawName` (UNIQUE) | |
| `merchant_aliases` | `normalizedKey` (UNIQUE) | Upgraded in MIGRATION_72_73 |
| `merchant_locations` | `normalizedMerchantName`, `areaKey` (UNIQUE) | |
| `merchant_location_corrections` | `normalizedMerchantName`, `areaKey` (UNIQUE) | |
| `exchange_rates` | `fromCurrency`, `toCurrency` (UNIQUE) | |
| `ai_artifacts` | `targetKey`, `capability`, `promptVersion`, `sourceHash` (UNIQUE) | |
| `receipt_expense_links` | `receiptId`, `expenseId` (UNIQUE) | |
| `recurring_occurrences` | `occurrenceKey` (UNIQUE) | |
| `recurring_reminder_deliveries` | `occurrenceId`, `reminderWindow` (UNIQUE) | |
| `bank_connections` | `bankId` (UNIQUE) | |
| `group_members` | `groupId`, `name` (UNIQUE) | |
| `warranties` | `receiptId` (UNIQUE) | Upgraded in MIGRATION_66_67 |
| `return_windows` | `expenseId` (UNIQUE) | Upgraded in MIGRATION_80_81 |
| `email_receipt_sources` | `emailMessageId` (UNIQUE partial) | `WHERE emailMessageId IS NOT NULL` |
| `pending_reviews` | `rawNotificationId` (UNIQUE) | Upgraded in MIGRATION_81_82 |

### 1.2 Fresh-Install-Only Partial Unique Indexes (FRESH_INSTALL_CALLBACK)

| Table | Index Columns | Purpose |
|---|---|---|
| `raw_notifications` | `packageName, timestamp, title, text` WHERE all NOT NULL | Close NULL dedup loophole |
| `raw_notifications` | `packageName, timestamp` WHERE both NULL | |
| `raw_notifications` | `packageName, timestamp, text` WHERE title NULL | |
| `raw_notifications` | `packageName, timestamp, title` WHERE text NULL | |

### 1.3 Composite (Non-Unique) Indexes

All 48 entities have appropriate non-unique indexes for query performance. Full list documented in each entity's `@Entity(indices = [...])` annotation.

---

## 2. Missing Unique Indexes

### 2.1 `group_expenses.expenseId` — No Unique Constraint

**Entity:** `GroupExpense`  
**Problem:** The `expenseId` column references an `Expense` but has **no unique constraint**. A single expense can theoretically be linked to multiple groups, creating ambiguity in group settlement.  
**Evidence:** `GroupExpense.kt` line 46: `val expenseId: Long?` — the `indices` block has `Index(value = ["expenseId"])` which is non-unique.  
**Severity:** MEDIUM — MIGRATION_70_71 deduplicates duplicate expenseId links at migration time, but no DB constraint prevents re-introduction.  
**Current protection:** Only the MIGRATION_70_71 cleanup SQL and Kotlin-level `GroupsRepositoryImpl` guards.

### 2.2 `raw_notifications.dedupeFingerprint` — No Column Exists

**Entity:** `RawNotification`  
**Problem:** There is no `dedupeFingerprint` column on `raw_notifications`. Deduplication is handled by a 4-column covering index `(packageName, timestamp, title, text, bigText)` plus four partial unique indexes on fresh install. However, since SQLite treats `NULL != NULL`, these indexes **cannot** prevent duplicates when any of these columns is NULL.  
**Evidence:** `RawNotification.kt` — no fingerprint/dedupeKey column. The fresh-install partial indexes close some NULL loopholes but not all permutations.  
**Severity:** HIGH — migration 73→74 and 83→84 repeatedly need to clean up legacy duplicates, proving the NULL loophole is a real problem.  
**Recommendation:** Add a `dedupeFingerprint TEXT` column computed from all notification fields, with a unique index `WHERE dedupeFingerprint IS NOT NULL`.

### 2.3 `planned_expenses` — No Dedup Key

**Entity:** `PlannedExpense`  
**Problem:** No unique constraint on `(sourceOccurrenceKey, sourceRecurringRuleId, date)` or any equivalent dedup key.  
**Severity:** LOW-MEDIUM — the planned expense lifecycle relies on app-layer dedup via `PlannedExpenseRepository`.  
**Recommendation:** Consider `UNIQUE(sourceOccurrenceKey) WHERE sourceOccurrenceKey IS NOT NULL`.

### 2.4 `budgets` — No Partial Unique Index (Single Active Overall Budget)

**Entity:** `Budget`  
**Problem:** The comment in `Budget.kt` lines 21-22 states:
> At most one active overall budget: `UNIQUE(isActive) WHERE isActive = 1 AND categoryId IS NULL`
> At most one active budget per category: `UNIQUE(categoryId) WHERE isActive = 1 AND categoryId IS NOT NULL`

**But these are NOT enforced at the DB level.** They are enforced only in `BudgetDao` through transactional methods (`insertAndActivateOverall`, `insertAndActivateCategory`, `updateAndEnforceActiveScope`, `setActiveAndEnforceScope`). Room's `@Index` annotation does not support `WHERE` clauses, so these partial unique indexes can only be created via raw SQL in the FRESH_INSTALL_CALLBACK — and they are **not** present there either (MIGRATION_86_87 explicitly **removes** them).  
**Severity:** HIGH — if a bug in the app layer skips the transaction helper and calls `budgetDao.insert(budget)` directly, duplicate active budgets can be created silently.  
**Evidence:** `BudgetDao.kt` line 9: `@Insert(onConflict = OnConflictStrategy.ABORT)` — no conflict resolution. The FRESH_INSTALL_CALLBACK does not create these indexes. MIGRATION_71_72 once healed duplicates but did not keep the constraints. MIGRATION_86_87 removed them.

### 2.5 `group_members.isCurrentUser` — No Partial Unique Index (One Current User Per Group)

**Problem:** There is no DB constraint enforcing "at most one `isCurrentUser = 1` per `groupId`". The entity has `Index(value = ["groupId", "isCurrentUser"])` which is non-unique.  
**Evidence:** MIGRATION_70_71 had to demote duplicate current-user rows, proving this is a real problem.  
**Severity:** MEDIUM — MIGRATION_70_71 heals existing duplicates but does not prevent re-introduction.  
**Recommendation:** Add a partial unique index: `CREATE UNIQUE INDEX ... ON group_members(groupId) WHERE isCurrentUser = 1`.

### 2.6 `subscription_candidates` — Removed Partial Unique Index

**Problem:** MIGRATION_90_91 and 74_75 removed the `index_subscription_candidates_pending_merchant_interval` partial unique index. The entity now has only Room-declared non-unique indexes.  
**Severity:** LOW — the app-layer `SubscriptionManagementRepository` handles dedup explicitly. The `subscription_candidates` table is a staging area, not ground truth.  
**Evidence:** `FreshInstallIndexParityTest.kt` explicitly tests that duplicates can now be inserted without collision.

---

## 3. CHECK Constraints

### 3.1 Room Entities — No `@Check` Annotations

Room does not support `@Check` annotations. All CHECK constraints are applied via:
1. **MIGRATION_75_76** (upgrade path — rebuilds tables with CHECK constraints)
2. **FRESH_INSTALL_CALLBACK** (fresh install — idempotent table rebuilds)

### 3.2 CHECK Constraints Currently Enforced

| Table | CHECK Constraint | Source |
|---|---|---|
| `savings_goals` | `targetAmount > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `savings_goals` | `currentAmount >= 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `mileage_tracking` | `distanceKm > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `mileage_tracking` | `deductionRatePerKm >= 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `mileage_tracking` | `fuelCost IS NULL OR fuelCost >= 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `mileage_tracking` | `endOdometer IS NULL OR startOdometer IS NULL OR endOdometer >= startOdometer` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `pending_reviews` | `suggestedAmount > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `pending_reviews` | `suggestedType IN ('PURCHASE','WITHDRAWAL','TRANSFER','DEPOSIT','UNKNOWN')` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `budgets` | `amount > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `budgets` | `notifyAtWarning > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `budgets` | `notifyAtCritical > 0` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |
| `budgets` | `notifyAtWarning <= notifyAtCritical` | MIGRATION_75_76 + FRESH_INSTALL_CALLBACK |

### 3.3 Missing CHECK Constraints

**`expenses`**: No CHECK `amount > 0`, no CHECK `currency` format validation. Amount 0 or negative can be stored.  
**`expenses`**: No CHECK `date > 0` — future/past sentinel values allowed.  
**`planned_expenses`**: No CHECK `amount > 0`.  
**`budgets`**: No CHECK `currency IN (...)` — must be one of the ISO-4217 set.  
**`group_expenses`**: No CHECK `totalAmount > 0`.  

---

## 4. Foreign Keys & Cascade Rules

### 4.1 Complete FK Map

| Child Table | Parent Table | FK Column | Delete Rule | Update Rule |
|---|---|---|---|---|
| `expenses` | `raw_notifications` | `rawNotificationId` | SET NULL | (default) |
| `expenses` | `categories` | `categoryId` | SET NULL | (default) |
| `expenses` | `split_templates` | `splitTemplateId` | SET NULL | (default) |
| `budgets` | `categories` | `categoryId` | SET NULL | (default) |
| `pending_reviews` | `raw_notifications` | `rawNotificationId` | SET NULL | (default) |
| `pending_reviews` | `scanned_receipts` | `scannedReceiptId` | SET NULL | (default) |
| `user_corrections` | `categories` | `originalCategoryId` | SET NULL | (default) |
| `user_corrections` | `categories` | `correctedCategoryId` | SET NULL | (default) |
| `scanned_receipts` | `expenses` | `expenseId` | SET NULL | (default) |
| `warranties` | `scanned_receipts` | `receiptId` | CASCADE | (default) |
| `warranties` | `expenses` | `expenseId` | SET NULL | (default) |
| `return_windows` | `scanned_receipts` | `receiptId` | CASCADE | (default) |
| `return_windows` | `expenses` | `expenseId` | SET NULL | (default) |
| `mileage_tracking` | `expenses` | `linkedExpenseId` | SET NULL | (default) |
| `merchant_canonicals` | `categories` | `categoryId` | SET NULL | (default) |
| `merchant_aliases` | `merchant_canonicals` | `canonicalId` | CASCADE | (default) |
| `merchant_categories` | `categories` | `categoryId` | CASCADE | (default) |
| `budget_forecasts` | `budgets` | `budgetId` | CASCADE | (default) |
| `budget_adjustment_recommendations` | `budgets` | `budgetId` | CASCADE | (default) |
| `budget_adjustment_recommendations` | `categories` | `categoryId` | SET NULL | (default) |
| `budget_adjustment_events` | `budgets` | `budgetId` | CASCADE | (default) |
| `savings_sweep_plan` | `savings_goals` | `goalId` | CASCADE | (default) |
| `subscription_price_history` | `manual_recurring_expenses` | `subscriptionId` | CASCADE | (default) |
| `subscription_usage` | `manual_recurring_expenses` | `subscriptionId` | CASCADE | (default) |
| `investments` | N/A | N/A | N/A | N/A |
| `investment_values` | `investments` | `investmentId` | CASCADE | (default) |
| `bank_connections` | `categories` | `defaultCategoryId` | SET NULL | (default) |
| `expense_groups` | N/A | N/A | N/A | N/A |
| `group_members` | `expense_groups` | `groupId` | CASCADE | (default) |
| `group_expenses` | `expense_groups` | `groupId` | CASCADE | (default) |
| `group_expenses` | `expenses` | `expenseId` | CASCADE | (default) |
| `group_expenses` | `group_members` | `paidById` | RESTRICT | (default) |
| `split_item_assignments` | `expenses` | `expenseId` | CASCADE | (default) |
| `anomaly_alerts` | `expenses` | `expenseId` | CASCADE | (default) |
| `ai_chat_messages` | `ai_chat_sessions` | `sessionId` | CASCADE | (default) |
| `receipt_item_categorizations` | `scanned_receipts` | `receiptId` | CASCADE | (default) |
| `receipt_item_categorizations` | `expenses` | `expenseId` | SET NULL | (default) |
| `email_receipt_sources` | `scanned_receipts` | `receiptId` | CASCADE | (default) |
| `spending_challenges` | `categories` | `categoryId` | SET NULL | (default) |
| `planned_expenses` | `categories` | `categoryId` | SET NULL | (default) |

### 4.2 Missing Foreign Keys

| Child Table | Parent Table | Missing FK Column | Severity |
|---|---|---|---|
| `receipt_events` | `scanned_receipts` | `receiptId` (no FK declared in entity) | LOW — event log, intentional |
| `receipt_expense_links` | `scanned_receipts` / `expenses` | No FK declared (documented in migration comment) | LOW — entity design choice |
| `recurring_occurrences` | N/A | No FK to `manual_recurring_expenses` or `expenses` | LOW — designed without FKs |
| `recurring_reminder_deliveries` | `recurring_occurrences` | `occurrenceId` (no FK declared) | LOW — documented entity choice |
| `recurring_lifecycle_events` | `recurring_occurrences` | `occurrenceId` (no FK declared) | LOW — documented entity choice |

These are all **intentional** — documented in migration comments as "FK note: The Room entities ... do NOT declare foreign key annotations."

### 4.3 Notable Cascade Rules

- **`group_expenses.paidById` → `group_members(id)`: ON DELETE RESTRICT** — critical for referential integrity. Prevents deleting a group member who has paid expenses. Corrected in MIGRATION_51_52 from the original (invalid) SET NULL.
- **`group_expenses.expenseId` → `expenses(id)`: ON DELETE CASCADE** — deleting an expense automatically removes its group link.
- **`group_members.groupId` → `expense_groups(id)`: ON DELETE CASCADE** — deleting a group removes all members.
- **`merchant_aliases.canonicalId` → `merchant_canonicals(id)`: ON DELETE CASCADE** — deleting a canonical merchant removes its aliases.

---

## 5. Trigger-Needed Business Rules

### 5.1 Business Rules Enforced ONLY in Kotlin (Not DB)

| Rule | Enforced Where | DB-Level? | Risk |
|---|---|---|---|
| One active overall budget | `BudgetDao` transactional methods | ❌ No DB constraint | HIGH |
| One active category budget per category | `BudgetDao` transactional methods | ❌ No DB constraint | HIGH |
| One current user per group | `GroupMemberDao` methods | ❌ No DB constraint | MEDIUM |
| `group_expenses.paidById` belongs to same group | Kotlin (`GroupsRepositoryImpl`) | ❌ No DB trigger/CHECK | MEDIUM |
| No duplicate `dedupeKey` on expenses | `index_expenses_dedupeKey` (UNIQUE) | ✅ DB constraint | N/A |
| CHECK constraints (amounts > 0, etc.) | SQLite CHECK constraints | ✅ DB constraint | N/A |

### 5.2 Existing SQLite Triggers

**None.** Search for `CREATE TRIGGER` / `DROP TRIGGER` across all files returned zero results. All cross-table business rules are enforced in app-layer code (repositories, DAOs, coordinators).

### 5.3 Analysis of Missing Trigger-Enforced Rules

For **"paidById belongs to same group"**, a trigger would need to:
```sql
CREATE TRIGGER group_expenses_paid_belongs_to_group
BEFORE INSERT ON group_expenses
FOR EACH ROW
BEGIN
    SELECT RAISE(ABORT, 'paidById must belong to the same group')
    WHERE NOT EXISTS (
        SELECT 1 FROM group_members
        WHERE id = NEW.paidById AND groupId = NEW.groupId
    );
END;
```
This is **intentionally out of scope** per MIGRATION_70_71 comment: "Trigger-based paidById same-group enforcement is explicitly OUT OF SCOPE."

---

## 6. Migration Chain Quality

### 6.1 Complete Migration List (v6 → v104)

```
6→7 → 7→8 → 8→9 → 9→10 → 10→11 → 11→12 → 12→13 → 13→14 → 14→15 → 15→16 → 16→17 → 17→18 → 18→19 → 19→20 → 20→21 → 21→22 → 22→23 → 23→24 → 24→25 → 25→26 → 26→27 → 27→28 → 28→29 → 29→30 → 30→31 → 31→32 → 32→33 → 33→34 → 34→35 → 35→36 → 36→37 → 37→38 → 38→39 → 39→40 → 40→41 → 41→42 → 42→43 → 43→44 → 44→45 → 45→46 → 46→47 → 47→48 → 48→49 → 49→50 → 50→51 → 51→52 → 52→53 → 53→54 → 54→55 → 55→56 → 56→57 → 57→58 → 58→59 → 59→60 → 60→61 → 61→62 → 62→63 → 63→64 → 64→65 → 65→66 → 66→67 → 67→68 → 68→69 → 69→70 → 70→71 → 71→72 → 72→73 → 73→74 → 74→75 → 75→76 → 76→77 → 77→78 → 78→79 → 79→80 → 80→81 → 81→82 → 82→83 → 83→84 → 84→85 → 85→86 → 86→87 → 87→88 → 88→89 → 89→90 → 90→91 → 91→92 → 92→93 → 93→94 → 94→95 → 95→96 → 96→100 → 100→101 → 101→102 → 102→103 → 103→104
```

### 6.2 Migration Chain Issues

| Issue | Details | Severity |
|---|---|---|
| **Missing v1→v6** | No migration for v1→v6. App presumably starts at v6. | LOW (documented) |
| **Gap at v96→v100** | Intentional jump — migration name `MIGRATION_96_100` encompasses v96→97→98→99→100 | MEDIUM (documented) |
| **No destructive migration** | `fallbackToDestructiveMigration()` is NOT called in production. Correct. | ✅ Good |
| **Destructive in test** | `DatabaseMigrationTest.kt` line 301 uses `.fallbackToDestructiveMigration()` | ✅ Test-only, acceptable |
| **Duplicate table creation** | Several migrations use `CREATE TABLE IF NOT EXISTS` which silently skips if table exists. Safe but masks drift. | LOW |

### 6.3 Migration Test Coverage

| Test File | Type | Coverage |
|---|---|---|
| `DatabaseMigrationTest.kt` | androidTest | Tests ALL migrations from v6→current using exported schema JSONs |
| `MigrationContractTest.kt` | androidTest | Tests specific migrations (6→7, 7→8, 8→9, 9→10, 69→70, 72→73, 77→78, 78→79, 79→80, 91→92) |
| `FreshInstallIndexParityTest.kt` | androidTest | Verifies fresh-install indexes match expected set |
| `FreshInstallBatch8ParityTest.kt` | androidTest | Verifies CHECK constraints + FK on fresh install |
| `DedupeKeyUniquenessRegressionTest.kt` | androidTest | Tests dedupeKey uniqueness |

### 6.4 Schema Export JSON Coverage

Schema JSON files exist for versions: 33-53, 56-57, 59-60, 64-65, 67-96, 100-103.

**Missing schema JSON files:**
- v1-32: Never exported (schema export likely enabled later)
- v54, v55: Missing (no schema export for these versions)
- v58: Missing
- v61, v62, v63: Missing
- v66: Missing
- v97, v98, v99: Missing (jumped over by MIGRATION_96_100)
- v104: Currently building (current version, schema export only on clean build)

The number of missing JSONs is significant (25+ versions). This means Room's compile-time schema verification cannot validate migration chains that pass through these versions. However, `DatabaseMigrationTest.kt` runs all migrations sequentially, which catches many issues.

---

## 7. Integrity Scanner Gap Analysis

### 7.1 Existing Integrity/Validation Code

Search for `integrity`, `validate`, `scan`, `diagnostic`, `consistency` found mostly UI strings and unrelated patterns. **There is no dedicated integrity scanner** in the codebase.

### 7.2 What Should Be Scanned (Gaps)

| Invariant | Should Detect | Currently Detected? |
|---|---|---|
| Duplicate active budgets | SQL query: `SELECT COUNT(*) FROM budgets WHERE isActive=1 AND categoryId IS NULL` | ❌ No runtime scanner |
| Duplicate active category budgets | SQL query: `SELECT categoryId, COUNT(*) FROM budgets WHERE isActive=1 GROUP BY categoryId HAVING COUNT(*) > 1` | ❌ No runtime scanner |
| Duplicate group expense links | SQL query: `SELECT expenseId, COUNT(*) FROM group_expenses WHERE expenseId IS NOT NULL GROUP BY expenseId HAVING COUNT(*) > 1` | ❌ No runtime scanner |
| Multiple current users per group | SQL query: `SELECT groupId, COUNT(*) FROM group_members WHERE isCurrentUser=1 GROUP BY groupId HAVING COUNT(*) > 1` | ❌ No runtime scanner |
| Planned expense duplicates | SQL query: `SELECT sourceOccurrenceKey, COUNT(*) ...` | ❌ No runtime scanner |
| Raw notification duplicates | SQL query: `SELECT packageName, timestamp, COUNT(*) ...` | ❌ No runtime scanner |
| Expenses with null dedupeKey | SQL query: `SELECT COUNT(*) FROM expenses WHERE dedupeKey IS NULL` | ❌ No runtime scanner |
| Partial lat/lon rows | SQL query: `SELECT COUNT(*) FROM expenses WHERE (latitude IS NULL) != (longitude IS NULL)` | ❌ No runtime scanner |
| Invalid currency values | SQL query: `SELECT DISTINCT currency FROM expenses WHERE currency NOT IN (known set)` | ❌ No runtime scanner |
| Orphaned FKs | SQL: `PRAGMA foreign_key_check` | ❌ No runtime checker (only in migrations) |

### 7.3 Existing DebugTools

There is a `DebugIssueDetector.kt` and `DebugViewModel.kt` that provide some diagnostic capabilities, but these are UI-level and do not perform systematic DB integrity scanning.

---

## 8. Fresh vs Migrated Parity

### 8.1 Parity Tests

| Test | Purpose | Status |
|---|---|---|
| `FreshInstallIndexParityTest.kt` | Verifies exact index set on fresh install | ✅ Present |
| `FreshInstallBatch8ParityTest.kt` | Verifies CHECK constraints + FK on fresh install | ✅ Present |

### 8.2 Known Schema Differences

**Issue: Raw Notification Dedup Indexes**
- **Fresh install:** Creates 4 partial unique indexes (`FRESH_INSTALL_CALLBACK`)
- **Migrated DB:** These indexes are **not** created by any migration (they were removed by MIGRATION_84_85). Migrated DBs rely on a non-unique covering index instead.
- **Impact:** Fresh-install DBs have stronger dedup enforcement than migrated DBs.

**Issue: Budget Partial Unique Indexes**
- **Fresh install:** No `index_budgets_active_overall` or `index_budgets_active_category` (MIGRATION_86_87 removed them from migrated path)
- **Migrated DB:** Same — removed by MIGRATION_86_87
- **Status:** Parity achieved (neither has them)

**Issue: Group Members Current User Index**
- **Fresh install:** Has `index_group_members_groupId_isCurrentUser` (non-unique) and `index_group_members_groupId_name` (unique)
- **Migrated DB:** Same indexes ensured by MIGRATION_87_88
- **Status:** Parity achieved

**Issue: Group Expenses expenseId Index**
- **Fresh install:** `index_group_expenses_expenseId` (non-unique)
- **Migrated DB:** Same — MIGRATION_88_89 removed the old unique version
- **Status:** Parity achieved

### 8.3 Column Default Consistency

Most columns with `@ColumnInfo(defaultValue = ...)` are consistent between entity definitions and migration SQL. Some historical mismatches were fixed in:
- **MIGRATION_48_49** (scanned_receipts defaults)
- **MIGRATION_49_50** (massive rebuild for 10+ tables)
- **MIGRATION_50_51** (merchant_locations areaKey default fix)
- **MIGRATION_85_86** (budgets default rebuild)
- **MIGRATION_91_92** (email_receipt_sources emailMessageId default fix)

---

## 9. Direct Repository Access

### 9.1 Repository Methods That Bypass Lifecycle Coordinators

The following repository methods directly mutate database tables without going through lifecycle coordinators (TransactionLifecycleCoordinator, ReceiptSideEffectDispatcher, etc.):

| Repository | Method | Bypasses | Risk |
|---|---|---|---|
| `ExpenseRepository` | `insertExpense` | May not trigger lifecycle events | MEDIUM |
| `ExpenseRepository` | `updateExpense` | May not trigger lifecycle events | MEDIUM |
| `BudgetRepository` | `updateBudget`, `deleteBudget`, `deleteAll` | No coordinator | LOW (budget lifecycle is simpler) |
| `BudgetRepository` | `updateExceededNotification` | Direct DAO call | LOW |
| `ReceiptRepository` | Various mutations | May skip receipt lifecycle | MEDIUM |
| `NotificationRepository` | Various mutations | Direct DAO access | LOW |
| `GroupsRepositoryImpl` | Various mutations | GroupTransactionCoordinator exists but not always used | MEDIUM |

The `TransactionLifecycleCoordinator` is the recommended path for expense mutations, but direct `ExpenseDao` calls can still be made via repositories.

### 9.2 Deprecated DAO Methods Still Present

From `ExpenseDao.kt`:
- Multiple `@Deprecated` raw SUM queries across mixed currencies (25+ methods) — marked `@Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")`
- `@Deprecated("Use getAllFlow(limit) or getPage(limit, offset) to prevent OOM")`

From `RecurringExpenseDao.kt`:
- `@Deprecated` method for `getAll()`
- `@Deprecated` method for `getAllActive()`

From `GroupExpenseDao.kt`:
- 2 `@Deprecated` raw SUM methods

From `GroupMemberDao.kt`:
- 5 `@Deprecated` methods

From `ExpenseGroupDao.kt`:
- 5 `@Deprecated` methods

**Observation:** These deprecated methods are still defined in the DAO interfaces and could be called by repositories without compile-time warnings if the calling code doesn't suppress them.

---

## 10. Recommended Fixes (Priority-Ordered)

### P0 — Critical (Data Integrity Risk)

1. **Add partial unique indexes for budgets** in `FRESH_INSTALL_CALLBACK`:
   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_active_overall 
   ON budgets(isActive) WHERE isActive = 1 AND categoryId IS NULL;
   CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_active_category 
   ON budgets(categoryId) WHERE isActive = 1 AND categoryId IS NOT NULL;
   ```
   Also add a restoration migration (104→105) to clean up any duplicates that may have accumulated and create these indexes.

2. **Add partial unique index for group_members.isCurrentUser:**
   ```sql
   CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_one_current_user 
   ON group_members(groupId) WHERE isCurrentUser = 1;
   ```

### P1 — High (Duplicate Prevention)

3. **Add a `dedupeFingerprint` column to `raw_notifications`** with a partial unique index. This is the only reliable way to close the SQLite NULL != NULL loophole. Backfill from existing columns in a migration.

4. **Create an integrity scanner** (`DatabaseIntegrityScanner.kt`) that runs on app startup (or via a periodic WorkManager task) and checks:
   - Duplicate active budgets (overall and per-category)
   - Multiple current users per group
   - Duplicate group_expense expenseId links
   - Expenses with null dedupeKey
   - Partial lat/lon pairs
   - Invalid currency values
   - Orphaned foreign key references

### P2 — Medium (Defense in Depth)

5. **Create a fresh-vs-migrated parity test** that builds both paths and compares:
   - Exact index set (PRAGMA index_list + index_info)
   - Column default values (PRAGMA table_info)
   - CHECK constraints (PRAGMA table_info)
   - FK declarations (PRAGMA foreign_key_list)

6. **Add CHECK constraint for `expenses.amount > 0`** via migration + FRESH_INSTALL_CALLBACK.

7. **Add a unique partial index on `group_expenses(expenseId) WHERE expenseId IS NOT NULL`**.

### P3 — Low (Housekeeping)

8. **Export schema JSON for missing versions.** Consider backfilling v54, v55, v58, v61-63, v66, v97-99.

9. **Add explicit NOT NULL CHECK to `expenses.date`** (currently no constraint prevents `date = 0`).

10. **Remove truly unused `@Deprecated` DAO methods** or add lint rules to prevent calling them.

---

## Appendix: Entity Inventory

| # | Table (Entity) | PK | FK Count | Unique Indexes | CHECKs |
|---|---|---|---|---|---|
| 1 | `raw_notifications` | id (auto) | 0 | 4 partial (fresh-only) | 0 |
| 2 | `blocked_packages` | packageName | 0 | 0 | 0 |
| 3 | `expenses` | id (auto) | 3 | dedupeKey | 0 |
| 4 | `categories` | id (auto) | 0 | 0 | 0 |
| 5 | `merchant_categories` | merchantPattern | 1 | 0 | 0 |
| 6 | `pending_reviews` | id (auto) | 2 | rawNotificationId | 2 |
| 7 | `user_corrections` | id (auto) | 2 | 0 | 0 |
| 8 | `source_stats` | packageName | 0 | 0 | 0 |
| 9 | `budgets` | id (auto) | 1 | 0 | 4 |
| 10 | `scanned_receipts` | id (auto) | 1 | 0 | 0 |
| 11 | `manual_recurring_expenses` | id (auto) | 0 | 0 | 0 |
| 12 | `planned_expenses` | id (auto) | 1 | 0 | 0 |
| 13 | `savings_goals` | id (auto) | 0 | 0 | 2 |
| 14 | `merchant_canonicals` | id (auto) | 1 | 2 (normalizedName, searchKey) | 0 |
| 15 | `merchant_aliases` | id (auto) | 1 | 2 (rawName, normalizedKey) | 0 |
| 16 | `merchant_locations` | id (auto) | 0 | 1 (name+areaKey) | 0 |
| 17 | `merchant_location_corrections` | id (auto) | 0 | 1 (name+areaKey) | 0 |
| 18 | `ai_artifacts` | id (auto) | 0 | 1 (4-column) | 0 |
| 19 | `ai_chat_sessions` | id (auto) | 0 | 0 | 0 |
| 20 | `ai_chat_messages` | id (auto) | 1 | 0 | 0 |
| 21 | `recommendations` | id (String) | 0 | 0 | 0 |
| 22 | `receipt_item_categorizations` | id (auto) | 2 | 0 | 0 |
| 23 | `warranties` | id (auto) | 2 | receiptId (unique) | 0 |
| 24 | `return_windows` | id (auto) | 2 | expenseId (unique) | 0 |
| 25 | `subscription_price_history` | id (auto) | 1 | 0 | 0 |
| 26 | `subscription_usage` | id (auto) | 1 | 0 | 0 |
| 27 | `mileage_tracking` | id (auto) | 1 | 0 | 4 |
| 28 | `exchange_rates` | id (auto) | 0 | 1 (from+to currency) | 0 |
| 29 | `expense_groups` | id (auto) | 0 | 0 | 0 |
| 30 | `group_members` | id (auto) | 1 | 1 (groupId+name) | 0 |
| 31 | `group_expenses` | id (auto) | 3 | 0 | 0 |
| 32 | `budget_forecasts` | id (auto) | 1 | 0 | 0 |
| 33 | `investments` | id (auto) | 0 | 0 | 0 |
| 34 | `investment_values` | id (auto) | 1 | 0 | 0 |
| 35 | `bank_connections` | id (auto) | 1 | bankId | 0 |
| 36 | `split_templates` | id (auto) | 0 | 0 | 0 |
| 37 | `split_item_assignments` | id (auto) | 1 | 0 | 0 |
| 38 | `anomaly_alerts` | id (auto) | 1 | 0 | 0 |
| 39 | `prompt_states` | id (auto) | 0 | 0 | 0 |
| 40 | `health_score_history` | id (auto) | 0 | 0 | 0 |
| 41 | `savings_sweep_plan` | id (auto) | 1 | 0 | 0 |
| 42 | `subscription_candidates` | id (auto) | 0 | 0 | 0 |
| 43 | `budget_adjustment_recommendations` | id (auto) | 2 | 0 | 0 |
| 44 | `budget_adjustment_events` | id (auto) | 1 | 0 | 0 |
| 45 | `spending_personality_profiles` | id (auto) | 0 | 0 | 0 |
| 46 | `stress_forecast_snapshots` | id (auto) | 0 | 0 | 0 |
| 47 | `email_receipt_sources` | id (auto) | 1 | emailMessageId (partial unique) | 0 |
| 48 | `spending_challenges` | id (auto) | 1 | 0 | 0 |
| 49 | `transaction_events` | id (auto) | 0 | 0 | 0 |
| 50 | `receipt_events` | id (auto) | 0 | 0 | 0 |
| 51 | `receipt_expense_links` | id (auto) | 0 | 1 (receiptId+expenseId) | 0 |
| 52 | `recurring_occurrences` | id (auto) | 0 | occurrenceKey | 0 |
| 53 | `recurring_reminder_deliveries` | id (auto) | 0 | 1 (occurrenceId+reminderWindow) | 0 |
| 54 | `recurring_lifecycle_events` | id (auto) | 0 | 0 | 0 |
| 55 | `privacy_audit_events` | id (auto) | 0 | 0 | 0 |
