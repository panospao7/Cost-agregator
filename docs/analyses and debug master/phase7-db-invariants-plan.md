# Phase 7 — Database Invariants: Final Implementation Plan

**Date:** 2026-05-02  
**Based on:** DB Invariants Audit + Phase 7 Template Plan (option A)  
**DB version at start:** v104  
**DB version after Phase 7:** v106  
**Decision:** Endorse template plan’s **Option A (Materialized Invariant Keys)** with refinements from the audit.

---

## 1. Executive Summary

### 1.1 Core Decision

We **reject** the audit’s P0 recommendation to re-add raw SQL partial unique indexes (`WHERE isActive = 1 …`) for budgets. The template plan correctly identifies that raw partial indexes caused the historical problem in MIGRATION_86_87: Room cannot model partial indexes via `@Entity(indices = …)`, so export-time schema JSON does not contain them, causing Room’s runtime validation to see “extra indexes” and fail.

Instead we use the **materialized invariant key** pattern:

1. Add nullable “key” columns to the entity class.
2. Set the key value only when the business condition holds (e.g. `isActive = 1`), leave it `NULL` otherwise.
3. Declare a normal Room `@Index(unique = true)` on the key column.
4. Add CHECK constraints (or triggers) to prevent mismatched key states.
5. Since SQLite allows arbitrarily many `NULL`s in a unique index, the index exactly enforces the conditional invariant **and** Room can validate it.

### 1.2 Scope

**In scope:**
- 4 entities modified (`Budget`, `GroupMember`, `GroupExpense`, `RawNotification`, `PlannedExpense`)
- 2 Room migrations (104→105 heal+indexes, 105→106 CHECK rebuilds)
- FRESH_INSTALL_CALLBACK update
- New/updated DAO transaction helpers
- `DatabaseIntegrityScanner.kt` (new)
- Fresh-vs-migrated parity tests
- Budget active-uniqueness constraint test suite
- Schema JSON export for v105, v106

**Out of scope:**
- Triggers for `paidById` same-group enforcement (explicitly scoped out by MIGRATION_70_71)
- Removing deprecated DAO methods (P3 housekeeping)
- Backfilling missing schema JSON files for historical versions
- Sub-second performance tuning of the integrity scanner (correctness first)

---

## 2. Entity Changes (Room-Compatible)

### 2.1 `Budget.kt` — Materialized Active Keys

**Add two nullable internal columns:**

```kotlin
/** Set to 1 when this is the active overall budget, NULL otherwise. */
val activeOverallKey: Int? = null
/** Set to categoryId when this is the active category budget, NULL otherwise. */
val activeCategoryKey: Long? = null
```

**New Room indexes (replace existing non-unique ones):**

```kotlin
Index(value = ["activeOverallKey"], unique = true),
Index(value = ["activeCategoryKey"], unique = true)
```

**Retain:** `Index(value = ["categoryId"])`, `Index(value = ["isActive"])` (non-unique, needed for queries).

**Key assignment rules:**

| Budget state | `isActive` | `categoryId` | `activeOverallKey` | `activeCategoryKey` |
|---|---|---|---|---|
| Inactive | 0 | any | NULL | NULL |
| Active overall | 1 | NULL | 1 | NULL |
| Active category | 1 | NOT NULL | NULL | = categoryId |

### 2.2 `GroupMember.kt` — Materialized Current-User Key

**Add one nullable internal column:**

```kotlin
/** Set to groupId when isCurrentUser = 1, NULL otherwise. */
val currentUserGroupKey: Long? = null
```

**New Room index (replaces non-unique `[groupId, isCurrentUser]`):**

```kotlin
Index(value = ["currentUserGroupKey"], unique = true)
```

**Retain:** `Index(value = ["groupId"])`, `Index(value = ["groupId", "name"], unique = true)`.

### 2.3 `GroupExpense.kt` — Expense Link Uniqueness

**Change the `expenseId` index from non-unique to unique:**

```kotlin
// Before:
Index(value = ["expenseId"])
// After:
Index(value = ["expenseId"], unique = true)
```

Since `expenseId` is `Long?`, SQLite allows multiple NULLs, but one expense can no longer be linked to multiple groups. Standalone group expenses (expenseId = NULL) remain unrestricted.

### 2.4 `RawNotification.kt` — Dedupe Fingerprint

**Add one nullable column:**

```kotlin
/** Deterministic fingerprint for deduplication. Computed at insert time.
 *  NULL for legacy rows that predate this column; must NOT be NULL for new rows. */
val dedupeFingerprint: String? = null
```

**New Room index:**

```kotlin
Index(value = ["dedupeFingerprint"], unique = true)
```

Legacy rows have `NULL` fingerprints → no collision. New rows **must** compute a fingerprint before insert (enforced in `NotificationRepository`).

**Fingerprint formula:** `SHA-256(packageName || "|" || timestamp || "|" || (title ?: "") || "|" || (text ?: "") || "|" || (bigText ?: ""))` truncated to 64 hex chars.

### 2.5 `PlannedExpense.kt` — Open Occurrence Key

**Add one nullable column:**

```kotlin
/** Copy of sourceOccurrenceKey for rows with status = 'PLANNED'.
 *  NULL when status is FULFILLED, SKIPPED, or CANCELLED. */
val openSourceOccurrenceKey: String? = null
```

**New Room index:**

```kotlin
Index(value = ["openSourceOccurrenceKey"], unique = true)
```

Multiple NULLs allowed (fulfilled/cancelled rows). Only one PLANNED row per `sourceOccurrenceKey` is enforced.

---

## 3. Migration Plan

### 3.1 Migration 104 → 105: Heal Data + Add Columns + Backfill + Re-index

This is the complex, high-risk migration. It must run in a single transaction.

#### Step 1: Heal duplicate data

```sql
--- 1a. Budgets: deactivate duplicate active overall budgets (keep highest id)
UPDATE budgets SET isActive = 0
WHERE isActive = 1 AND categoryId IS NULL
  AND id NOT IN (
    SELECT MAX(id) FROM budgets
    WHERE isActive = 1 AND categoryId IS NULL
  );

--- 1b. Budgets: deactivate duplicate active category budgets (keep highest id per category)
UPDATE budgets SET isActive = 0
WHERE isActive = 1 AND categoryId IS NOT NULL
  AND id NOT IN (
    SELECT MAX(id) FROM budgets
    WHERE isActive = 1 AND categoryId IS NOT NULL
    GROUP BY categoryId
  );

--- 1c. Group members: demote duplicate current users (keep highest id per group)
UPDATE group_members SET isCurrentUser = 0
WHERE isCurrentUser = 1
  AND id NOT IN (
    SELECT MAX(id) FROM group_members
    WHERE isCurrentUser = 1
    GROUP BY groupId
  );

--- 1d. Group expenses: nullify duplicate expense links (keep earliest id)
UPDATE group_expenses SET expenseId = NULL
WHERE expenseId IS NOT NULL
  AND id NOT IN (
    SELECT MIN(id) FROM group_expenses
    WHERE expenseId IS NOT NULL
    GROUP BY expenseId
  );

--- 1e. Planned expenses: supersede duplicate occurrence keys
--- For each duplicate sourceOccurrenceKey, keep the newest PLANNED row,
--- set older rows to CANCELLED
UPDATE planned_expenses SET status = 'CANCELLED'
WHERE sourceOccurrenceKey IS NOT NULL
  AND status = 'PLANNED'
  AND id NOT IN (
    SELECT MAX(id) FROM planned_expenses
    WHERE sourceOccurrenceKey IS NOT NULL AND status = 'PLANNED'
    GROUP BY sourceOccurrenceKey
  );
```

#### Step 2: Add new columns

```sql
ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER DEFAULT NULL;
ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER DEFAULT NULL;
ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER DEFAULT NULL;
ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT DEFAULT NULL;
ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT DEFAULT NULL;
```

#### Step 3: Backfill materialized keys

```sql
--- Budgets: backfill activeOverallKey and activeCategoryKey
UPDATE budgets SET activeOverallKey = 1
WHERE isActive = 1 AND categoryId IS NULL;
UPDATE budgets SET activeCategoryKey = categoryId
WHERE isActive = 1 AND categoryId IS NOT NULL;
--- Inactive budgets already have NULL (default), no-op needed.

--- Group members: backfill currentUserGroupKey
UPDATE group_members SET currentUserGroupKey = groupId
WHERE isCurrentUser = 1;
--- Non-current members already have NULL.

--- Planned expenses: backfill openSourceOccurrenceKey
UPDATE planned_expenses SET openSourceOccurrenceKey = sourceOccurrenceKey
WHERE status = 'PLANNED' AND sourceOccurrenceKey IS NOT NULL;

--- Raw notifications: backfill dedupeFingerprint for existing rows
--- Use a deterministic computation; SQLite doesn't have SHA-256 built-in,
--- so we construct a simpler fingerprint from the existing columns.
UPDATE raw_notifications SET dedupeFingerprint =
  packageName || '|' || CAST(timestamp AS TEXT) || '|' ||
  COALESCE(title, '') || '|' || COALESCE(text, '') || '|' ||
  COALESCE(bigText, '');
```

#### Step 4: Drop old indexes & create new Room-compatible indexes

```sql
--- Budgets: drop existing indexes, Room will re-create via entity
DROP INDEX IF EXISTS index_budgets_categoryId;
DROP INDEX IF EXISTS index_budgets_isActive;
CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId);
CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive);
CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey);
CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey);

--- Group members: drop old non-unique composite, create new unique key index
DROP INDEX IF EXISTS index_group_members_groupId_isCurrentUser;
CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId);
CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey);

--- Group expenses: drop old non-unique, create unique
DROP INDEX IF EXISTS index_group_expenses_expenseId;
CREATE UNIQUE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId);

--- Raw notifications: create unique fingerprint index
CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedupeFingerprint ON raw_notifications (dedupeFingerprint);

--- Planned expenses: create unique open occurrence key index
CREATE UNIQUE INDEX IF NOT EXISTS index_planned_expenses_openSourceOccurrenceKey ON planned_expenses (openSourceOccurrenceKey);
```

#### Step 5: Log healing statistics (optional debug)

```sql
--- NOT PART OF MIGRATION. Use in integration tests only.
-- SELECT 'budget_dupes_healed', changes() etc.
```

### 3.2 Migration 105 → 106: Add CHECK Constraints via Table Rebuilds

Rebuild `budgets` and `group_members` tables to add CHECK constraints that prevent bad materialized key states. Follows the established pattern from MIGRATION_75_76.

#### Budgets table rebuild

```sql
CREATE TABLE budgets_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    categoryId INTEGER,
    amount REAL NOT NULL CHECK(amount > 0),
    period TEXT NOT NULL,
    periodMode TEXT NOT NULL DEFAULT 'ROLLING',
    startDate INTEGER NOT NULL,
    isActive INTEGER NOT NULL DEFAULT 1,
    notifyAtWarning REAL NOT NULL DEFAULT 0.75 CHECK(notifyAtWarning > 0),
    notifyAtCritical REAL NOT NULL DEFAULT 0.9 CHECK(notifyAtCritical > 0),
    rollover INTEGER NOT NULL DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'EUR',
    currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
    createdAt INTEGER NOT NULL,
    lastWarningNotifiedAt INTEGER,
    lastCriticalNotifiedAt INTEGER,
    lastExceededNotifiedAt INTEGER,
    activeOverallKey INTEGER,
    activeCategoryKey INTEGER,
    CHECK(notifyAtWarning <= notifyAtCritical),
    CHECK (
        (isActive = 0 AND activeOverallKey IS NULL AND activeCategoryKey IS NULL)
        OR
        (
            isActive = 1 AND
            (
                (categoryId IS NULL AND activeOverallKey = 1 AND activeCategoryKey IS NULL)
                OR
                (categoryId IS NOT NULL AND activeOverallKey IS NULL AND activeCategoryKey = categoryId)
            )
        )
    ),
    FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
);

INSERT INTO budgets_new SELECT * FROM budgets;
DROP TABLE budgets;
ALTER TABLE budgets_new RENAME TO budgets;
CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId);
CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive);
CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey);
CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey);
```

#### Group members table rebuild

```sql
CREATE TABLE group_members_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    groupId INTEGER NOT NULL,
    name TEXT NOT NULL,
    email TEXT,
    isCurrentUser INTEGER NOT NULL DEFAULT 0,
    joinedAt INTEGER NOT NULL,
    currentUserGroupKey INTEGER,
    CHECK (
        (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
        OR
        (isCurrentUser = 1 AND currentUserGroupKey = groupId)
    ),
    FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
);

INSERT INTO group_members_new SELECT * FROM group_members;
DROP TABLE group_members;
ALTER TABLE group_members_new RENAME TO group_members;
CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId);
CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey);
CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name);
```

---

## 4. FRESH_INSTALL_CALLBACK Update

The existing `FRESH_INSTALL_CALLBACK` needs to be updated to create the new materialized-key tables on fresh install. Currently it rebuilds `budgets` (with existing CHECKs), `savings_goals`, `mileage_tracking`, `pending_reviews`.

### Changes needed:

1. **Replace** the existing `budgets_new` CREATE TABLE in FRESH_INSTALL_CALLBACK with the version from Migration 105→106 (includes `activeOverallKey`, `activeCategoryKey` columns and the new CHECK constraint).

2. **Replace** the `group_members` table creation (currently handled by Room's auto-generated DDL) with a rebuild that includes `currentUserGroupKey` and its CHECK. Since FRESH_INSTALL_CALLBACK runs **after** Room creates the initial tables, we do a table rebuild pattern (same as budgets currently).

3. **Remove** the 4 partial `raw_notifications` unique indexes (index_raw_notifications_dedup_nonnull, _both_null, _title_null, _text_null) — they are no longer needed because `dedupeFingerprint` unique index provides stronger protection.

4. **Add** a table rebuild for `raw_notifications` to include the `dedupeFingerprint` column (Room's auto-generated DDL won't have it since it's nullable with no default — but wait, Room will create it because it's in the entity now. So we don't need a rebuild, we just need the corresponding index. Room handles the column, we need the index.)

Actually, let's be careful here. The `FRESH_INSTALL_CALLBACK` is called **after** Room's `onCreate` has already created all tables using the generated DDL. Since we're modifying the entities:
- `Budget.kt` gains `activeOverallKey` and `activeCategoryKey` → Room auto-creates these columns on fresh install
- `GroupMember.kt` gains `currentUserGroupKey` → Room auto-creates it
- `GroupExpense.kt` changes an index from non-unique to unique → Room handles it
- `RawNotification.kt` gains `dedupeFingerprint` → Room auto-creates it
- `PlannedExpense.kt` gains `openSourceOccurrenceKey` → Room auto-creates it

So on fresh install, Room creates the columns. In FRESH_INSTALL_CALLBACK we need to:
1. Add CHECK constraints via table rebuilds (budgets, group_members)
2. Remove the old 4 partial `raw_notifications` indexes
3. Add any supplementary unique indexes that Room might not auto-create (but Room should auto-create them since they're in `@Entity(indices=...)`)
4. Verify the fresh install has parity with migrated

**Actually** — Room auto-creates all `@Index` declarations from entities. So we don't need to manually create them. The FRESH_INSTALL_CALLBACK only needs to:
1. Add CHECK constraints (since Room doesn't support `@Check`)
2. Add non-Room indexes (e.g., performance indexes that Room doesn't know about)
3. Remove any indexes that conflict with old partial index patterns

**Revised FRESH_INSTALL_CALLBACK changes:**

```kotlin
// REMOVE the 4 raw_notifications partial unique indexes (they're obsoleted by dedupeFingerprint)
// No longer needed.

// ADD budgets CHECK constraint via table rebuild (update existing rebuild to include new columns)

// ADD group_members CHECK constraint via table rebuild
```

### Detailed FRESH_INSTALL_CALLBACK changes:

#### (a) Update budgets rebuild

The current rebuild at line 5014-5040 must be updated to include `activeOverallKey`, `activeCategoryKey`, and the new CHECK. The `currency` and `currencyAssumption` columns were also missing from the fresh-install rebuild — fix that inconsistency.

#### (b) Add group_members rebuild

After the budgets rebuild, add:

```sql
CREATE TABLE group_members_new (... as in migration 105→106 ...)
INSERT INTO group_members_new SELECT id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey FROM group_members
DROP TABLE group_members
ALTER TABLE group_members_new RENAME TO group_members
-- Re-create indexes
```

#### (c) Remove old raw_notifications partial indexes

Delete the 4 `CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedup_*` statements (lines 4889-4912). They are superseded by `dedupeFingerprint` unique index which Room auto-creates.

---

## 5. DAO / Repository Updates

### 5.1 `BudgetDao` — Key Maintenance

The existing transactional helpers (`insertAndActivateOverall`, `insertAndActivateCategory`, `updateAndEnforceActiveScope`, `setActiveAndEnforceScope`, `replaceAllAndEnforceActiveScopes`) must be updated to set/clear the materialized keys.

**Changes:**

```kotlin
@Transaction
suspend fun insertAndActivateOverall(budget: Budget): Long {
    require(budget.isActive)
    deactivateAllActiveOverallBudgets()
    return insert(budget.copy(
        activeOverallKey = 1,
        activeCategoryKey = null
    ))
}

@Transaction
suspend fun insertAndActivateCategory(budget: Budget): Long {
    require(budget.isActive)
    val catId = requireNotNull(budget.categoryId)
    deactivateAllActiveCategoryBudgets(catId)
    return insert(budget.copy(
        activeOverallKey = null,
        activeCategoryKey = catId
    ))
}
```

**New DAO methods needed:**

```kotlin
/** Low-level: deactivate a budget and clear its materialized keys. */
@Query("""
    UPDATE budgets SET isActive = 0, 
    activeOverallKey = NULL, activeCategoryKey = NULL 
    WHERE id = :id
""")
suspend fun deactivateAndClearKeys(id: Long)

/** Verify no duplicate active keys exist (integrity check helper). */
@Query("""
    SELECT COUNT(*) FROM budgets 
    WHERE activeOverallKey IS NOT NULL
""")
suspend fun countActiveOverallKeys(): Int

@Query("""
    SELECT categoryId, COUNT(*) as cnt FROM budgets 
    WHERE activeCategoryKey IS NOT NULL 
    GROUP BY categoryId HAVING cnt > 1
""")
suspend fun findDuplicateActiveCategoryKeys(): List<CategoryKeyDuplicate>
```

### 5.2 `GroupMemberDao` — Key Maintenance

The `setCurrentUser` method must be updated:

```kotlin
@Transaction
suspend fun setCurrentUser(groupId: Long, memberId: Long) {
    clearCurrentUser(groupId)
    val updated = markAsCurrentUserAndSetKey(groupId, memberId)
    require(updated > 0) { "Member $memberId not found in group $groupId" }
}

@Query("""
    UPDATE group_members 
    SET isCurrentUser = 1, currentUserGroupKey = :groupId 
    WHERE id = :memberId AND groupId = :groupId
""")
suspend fun markAsCurrentUserAndSetKey(groupId: Long, memberId: Long): Int
```

The `clearCurrentUser` query must also clear the key:

```kotlin
@Query("""
    UPDATE group_members 
    SET isCurrentUser = 0, currentUserGroupKey = NULL 
    WHERE groupId = :groupId AND isCurrentUser = 1
""")
suspend fun clearCurrentUser(groupId: Long)
```

### 5.3 `RawNotificationDao` / `NotificationRepository` — Fingerprint Computation

The `NotificationRepository` (or whatever class inserts `RawNotification` rows) must compute `dedupeFingerprint` before insert. The DAO should have:

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT)
suspend fun insert(notification: RawNotification): Long
```

But the caller is responsible for computing the fingerprint. A helper function:

```kotlin
fun computeDedupeFingerprint(
    packageName: String,
    timestamp: Long,
    title: String?,
    text: String?,
    bigText: String?
): String {
    val raw = "$packageName|$timestamp|${title ?: ""}|${text ?: ""}|${bigText ?: ""}"
    // Use SHA-256, take first 64 hex chars (32 bytes)
    return raw.sha256().take(64)
}
```

### 5.4 `PlannedExpenseDao` / Repository — Key Maintenance

When a planned expense is inserted, updated to FULFILLED/CANCELLED/SKIPPED, or its `sourceOccurrenceKey` changes, update `openSourceOccurrenceKey`:

```kotlin
@Query("""
    UPDATE planned_expenses 
    SET openSourceOccurrenceKey = 
        CASE WHEN status = 'PLANNED' THEN sourceOccurrenceKey ELSE NULL END
    WHERE id = :id
""")
suspend fun refreshOpenOccurrenceKey(id: Long)
```

---

## 6. DatabaseIntegrityScanner

### 6.1 Design

A new class `DatabaseIntegrityScanner.kt` in `data/database/integrity/` that:

1. Runs queries to detect invariant violations
2. Logs violations via Timber
3. Optionally reports to a diagnostic dashboard (DebugIssueDetector integration)
4. Can be triggered at app startup and/or via a WorkManager periodic task

### 6.2 Scans to Implement

| # | Invariant | Detection Query | Priority |
|---|---|---|---|
| 1 | Duplicate active overall budgets | `SELECT COUNT(*) FROM budgets WHERE activeOverallKey IS NOT NULL` > 1 | P0 |
| 2 | Duplicate active category budgets | `SELECT activeCategoryKey, COUNT(*) FROM budgets WHERE activeCategoryKey IS NOT NULL GROUP BY activeCategoryKey HAVING COUNT(*) > 1` | P0 |
| 3 | Multiple current users per group | `SELECT currentUserGroupKey, COUNT(*) FROM group_members WHERE currentUserGroupKey IS NOT NULL GROUP BY currentUserGroupKey HAVING COUNT(*) > 1` | P0 |
| 4 | Duplicate group expense links | `SELECT expenseId, COUNT(*) FROM group_expenses WHERE expenseId IS NOT NULL GROUP BY expenseId HAVING COUNT(*) > 1` | P0 |
| 5 | Planned expense open key dupes | `SELECT openSourceOccurrenceKey, COUNT(*) FROM planned_expenses WHERE openSourceOccurrenceKey IS NOT NULL GROUP BY openSourceOccurrenceKey HAVING COUNT(*) > 1` | P1 |
| 6 | Raw notification fingerprint dupes | `SELECT dedupeFingerprint, COUNT(*) FROM raw_notifications WHERE dedupeFingerprint IS NOT NULL GROUP BY dedupeFingerprint HAVING COUNT(*) > 1` | P1 |
| 7 | Expenses with null dedupeKey | `SELECT COUNT(*) FROM expenses WHERE dedupeKey IS NULL` | P1 |
| 8 | Partial lat/lon pairs | `SELECT COUNT(*) FROM expenses WHERE (latitude IS NULL) != (longitude IS NULL)` | P2 |
| 9 | Orphaned FKs | `PRAGMA foreign_key_check` | P1 |
| 10 | Mismatched budget keys | `SELECT COUNT(*) FROM budgets WHERE isActive=1 AND ((activeOverallKey IS NULL AND activeCategoryKey IS NULL) OR ...)` — verifies CHECK constraint holds | P2 |
| 11 | Mismatched group member keys | `SELECT COUNT(*) FROM group_members WHERE isCurrentUser=1 AND currentUserGroupKey IS NULL` | P2 |

### 6.3 Scan Result Model

```kotlin
data class IntegrityViolation(
    val invariantName: String,
    val tableName: String,
    val severity: Severity,  // CRITICAL, WARNING, INFO
    val count: Int,
    val sampleIds: List<Long>,
    val detail: String,
    val detectedAt: Long
)
```

### 6.4 Trigger Points

1. **Cold start:** Run P0 scans synchronously (fast queries). Queue P1/P2 on a coroutine.
2. **Periodic:** WorkManager `PeriodicWorkRequest` every ~24h runs all scans.
3. **Manual:** `DebugIssueDetector` integration exposes a "Run Integrity Scan" button.

---

## 7. Fresh-vs-Migrated Parity Tests

### 7.1 New Test: `Phase7FreshVsMigratedParityTest.kt`

**Location:** `app/src/androidTest/java/com/yourname/expensetracker/data/database/`

**Purpose:** Prove that a fresh-install DB and a migrated (v1→v106) DB have identical:
- Index sets (name, columns, uniqueness)
- Table schemas (column names, types, defaults, not-null)
- CHECK constraints
- FK declarations

**Test structure:**

```kotlin
@RunWith(AndroidJUnit4::class)
class Phase7FreshVsMigratedParityTest {
    
    @Test
    fun freshAndMigrated_haveIdenticalIndexes() { ... }
    
    @Test
    fun freshAndMigrated_haveIdenticalTableSchemas() { ... }
    
    @Test
    fun freshAndMigrated_haveIdenticalCheckConstraints() { ... }
    
    @Test
    fun freshAndMigrated_haveIdenticalForeignKeys() { ... }
    
    @Test
    fun noRawPartialIndexesOnBudgets() {
        // Assert no index on budgets has a WHERE clause
    }
    
    @Test
    fun dedupeFingerprintIndexExistsOnBothPaths() { ... }
}
```

### 7.2 New Test: `BudgetActiveUniquenessTest.kt`

**Location:** `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/`

**Purpose:** Prove the DB-level constraints prevent invariant violations.

```kotlin
@RunWith(AndroidJUnit4::class)
class BudgetActiveUniquenessTest {
    
    @Test(expected = SQLiteConstraintException::class)
    fun insertDuplicateActiveOverallBudget_fails() { ... }
    
    @Test(expected = SQLiteConstraintException::class)
    fun insertDuplicateActiveCategoryBudget_fails() { ... }
    
    @Test
    fun insertInactiveOverallBudget_succeeds() { ... }
    
    @Test
    fun insertMultipleInactiveOverallBudgets_succeeds() { ... }
    
    @Test
    fun reactivateBudget_afterDeactivation_succeeds() { ... }
    
    @Test(expected = SQLiteConstraintException::class)
    fun insertMismatchedActiveKeys_fails() { ... }
}
```

### 7.3 New Test: `GroupMemberCurrentUserUniquenessTest.kt`

Similarly proves `currentUserGroupKey` unique index and CHECK constraint.

### 7.4 New Test: `GroupExpenseLinkUniquenessTest.kt`

Proves `expenseId` unique index prevents double-linking.

---

## 8. Implementation Batches

### Batch 1: Entity Updates (Schema-only, no behavior change)

**Files to modify:**
- `entity/Budget.kt` — add `activeOverallKey`, `activeCategoryKey`, update indices
- `entity/GroupMember.kt` — add `currentUserGroupKey`, update indices
- `entity/GroupExpense.kt` — change expenseId index to `unique = true`
- `entity/RawNotification.kt` — add `dedupeFingerprint`, add index
- `entity/PlannedExpense.kt` — add `openSourceOccurrenceKey`, add index

**Validation:** Build succeeds. Room generates new schema JSON (v105).

**Completion criteria:**
- [ ] All 5 entity files compile
- [ ] Room annotation processor generates new schema at `app/schemas/.../105.json`
- [ ] `105.json` contains all 5 new columns and 5 new unique indexes

---

### Batch 2: Migration 104→105 (Data Healing + Re-index)

**Files to modify:**
- `AppDatabase.kt` — add `MIGRATION_104_105` constant + register in `ALL_MIGRATIONS`
- `AppDatabase.kt` — bump `@Database(version = 105)`

**Important:** The migration runs healing SQL **before** adding columns, because ALTER TABLE ADD COLUMN doesn't allow SELECT subqueries in the same statement easily. All healing must use existing columns.

**Validation:** 
- `DatabaseMigrationTest.kt` — runs all migrations 6→105
- Migration with dirty historical data (start from v6, inject duplicate budgets at a mid-version, migrate to v105, verify healing)

**Completion criteria:**
- [ ] Migration compiles and is registered
- [ ] `DatabaseMigrationTest` passes (all versions 6→105)
- [ ] Test with injected duplicate budgets passes (duplicates healed, no constraint violations)
- [ ] Test with injected duplicate group members passes
- [ ] Test with injected duplicate group expense links passes
- [ ] Schema JSON v105 matches generated code
- [ ] No raw partial indexes present on budgets table after migration

---

### Batch 3: Migration 105→106 (CHECK Constraints via Table Rebuilds)

**Files to modify:**
- `AppDatabase.kt` — add `MIGRATION_105_106` constant + register in `ALL_MIGRATIONS`
- `AppDatabase.kt` — bump `@Database(version = 106)`

**Validation:**
- `DatabaseMigrationTest.kt` — runs all migrations 6→106
- Verify CHECK constraints exist (PRAGMA table_info on budgets and group_members)

**Completion criteria:**
- [ ] Migration compiles
- [ ] `DatabaseMigrationTest` passes (all versions 6→106)
- [ ] `PRAGMA table_info(budgets)` shows CHECK constraints
- [ ] `PRAGMA table_info(group_members)` shows CHECK constraint
- [ ] Inserting a row with mismatched keys (isActive=1 but activeOverallKey=NULL) fails
- [ ] Schema JSON v106 matches generated code

---

### Batch 4: FRESH_INSTALL_CALLBACK Update

**Files to modify:**
- `AppDatabase.kt` — `FRESH_INSTALL_CALLBACK`

**Changes:**
1. Update `budgets_new` table rebuild to include new columns and new CHECK
2. Add `group_members` table rebuild with `currentUserGroupKey` and CHECK
3. Remove 4 legacy `raw_notifications` partial unique index creations
4. Remove any stale references to the removed partial indexes

**Validation:**
- FreshInstallIndexParityTest passes
- FreshInstallBatch8ParityTest passes (or updated to reflect new constraints)
- Phase7FreshVsMigratedParityTest passes

**Completion criteria:**
- [ ] Fresh install creates all new columns
- [ ] Fresh install has CHECK constraints matching migrated
- [ ] Fresh install has unique indexes matching migrated
- [ ] No legacy partial raw_notifications indexes on fresh install
- [ ] Fresh-vs-migrated parity test passes

---

### Batch 5: DAO / Repository Updates

**Files to modify:**
- `dao/BudgetDao.kt` — update transactional helpers to maintain keys
- `dao/GroupMemberDao.kt` — update `setCurrentUser` and `clearCurrentUser` to maintain keys
- `dao/PlannedExpenseDao.kt` — add `refreshOpenOccurrenceKey`
- Repository classes that insert `RawNotification` — add fingerprint computation

**Validation:** Unit tests + integration tests.

**Completion criteria:**
- [ ] `insertAndActivateOverall` sets `activeOverallKey=1`, clears `activeCategoryKey`
- [ ] `insertAndActivateCategory` sets `activeCategoryKey=categoryId`, clears `activeOverallKey`
- [ ] `updateAndEnforceActiveScope` correctly updates keys on activation/deactivation
- [ ] `setCurrentUser` sets `currentUserGroupKey=groupId`
- [ ] `clearCurrentUser` clears `currentUserGroupKey=NULL`
- [ ] RawNotification inserts always have `dedupeFingerprint` set
- [ ] Planned expenses refresh `openSourceOccurrenceKey` on status change
- [ ] All existing tests still pass (regression check)

---

### Batch 6: DatabaseIntegrityScanner

**Files to create:**
- `data/database/integrity/DatabaseIntegrityScanner.kt`
- `data/database/integrity/IntegrityViolation.kt`
- `data/database/integrity/IntegrityScanResult.kt`

**Files to modify:**
- `DebugIssueDetector.kt` — integrate scanner
- `AppDatabase.kt` — expose a `runIntegrityScan()` convenience method (optional)

**Validation:** Unit tests mocking DAOs, integration test on real DB.

**Completion criteria:**
- [ ] All 11 scan queries compile and run without errors
- [ ] Scanner correctly detects artificially injected violations
- [ ] Scanner returns clean result on a pristine DB
- [ ] Violations are logged via Timber at appropriate levels
- [ ] Scanner performance: P0 scans complete in <500ms on a typical DB
- [ ] DebugIssueDetector integration shows scan results

---

### Batch 7: Test Suite

**Files to create:**
- `app/src/androidTest/.../database/Phase7FreshVsMigratedParityTest.kt`
- `app/src/androidTest/.../database/dao/BudgetActiveUniquenessTest.kt`
- `app/src/androidTest/.../database/dao/GroupMemberCurrentUserUniquenessTest.kt`
- `app/src/androidTest/.../database/dao/GroupExpenseLinkUniquenessTest.kt`
- `app/src/androidTest/.../database/dao/DedupeFingerprintUniquenessTest.kt`
- `app/src/androidTest/.../database/dao/PlannedExpenseOpenKeyUniquenessTest.kt`

**Files to update:**
- `FreshInstallIndexParityTest.kt` — update expected index set
- `FreshInstallBatch8ParityTest.kt` — update expected CHECK constraint set
- `DatabaseMigrationTest.kt` — add migration-with-dirty-data test cases
- `MigrationContractTest.kt` — add 104→105 and 105→106 contract tests

**Completion criteria:**
- [ ] All new tests pass
- [ ] Existing tests pass (regression)
- [ ] Test coverage for all 6 invariant violations (duplicate active budget, category budget, current user, expense link, fingerprint, occurrence key)
- [ ] No raw partial indexes test passes (query sqlite_master for WHERE clauses on budget/group_member indexes)

---

## 9. Risk Assessment & Rollback

### 9.1 High-Risk Items

| Risk | Mitigation | Rollback |
|---|---|---|
| Migration 104→105 fails on large DBs due to healing subqueries | Test with 100k+ row DBs in DatabaseMigrationTest | Healing queries use id-based subqueries (indexed PK), not full table scans |
| Backfill computation time for dedupeFingerprint on millions of raw_notifications | Compute incrementally; delay to Batch 3 if needed | Skip backfill, leave legacy rows as NULL (UI-only concern) |
| Table rebuild (105→106) loses data if interrupted | Full transaction wrapping; backup table before DROP | `CREATE TABLE AS SELECT` first, verify row counts match before DROP |
| Room schema validation rejects new indexes | Validate generated schema JSON before committing | Rollback entity changes, investigate Room version compatibility |
| FRESH_INSTALL_CALLBACK fails on empty-table rebuilds (INSERT INTO ... SELECT * FROM empty) | Already tested pattern from MIGRATION_75_76; INSERT FROM empty table is a no-op | Never fails |

### 9.2 Migration Failure Rollback Procedure

If migration 104→105 or 105→106 fails in production:

1. The entire migration is wrapped in a transaction — on failure, no changes are committed.
2. Room will retry on next app launch (migration version check).
3. If persistent, fallback: the app can `fallbackToDestructiveMigration()` as a last resort, but this is **NOT** the default and requires explicit user consent.

### 9.3 Known Unknowns

- **Room version compatibility:** Ensure the Room version in `build.gradle` supports the `unique = true` attribute on nullable column indexes (confirmed: Room 2.5+ supports this since it delegates to SQLite which supports it since 3.0).
- **CHECK constraint on group_members rebuild:** Verify that `currentUserGroupKey` column in the rebuild SQL has correct affinity (`INTEGER`, not `TEXT`).
- **`dedupeFingerprint` backfill:** SQLite string concatenation with `||` handles NULLs correctly (NULL || 'x' = NULL, but COALESCE(title, '') prevents this). Verify in migration test.

---

## 10. Acceptance Criteria (Phase-Level)

- [ ] All 5 entities modified with new columns/indices — Room generates correct schema
- [ ] 2 migrations (104→105, 105→106) pass full chain test (v6→v106)
- [ ] MIGRATION_104_105 heals duplicate data (budgets, group_members, group_expenses, planned_expenses)
- [ ] MIGRATION_104_105 backfills materialized keys correctly
- [ ] MIGRATION_104_105 creates all 5 new unique indexes
- [ ] MIGRATION_105_106 creates CHECK constraints on budgets and group_members
- [ ] Fresh-install DB is identical to migrated DB (parity test passes)
- [ ] No raw partial indexes exist on budgets, group_members, or group_expenses tables
- [ ] DAO transactional helpers correctly maintain materialized keys
- [ ] Inserting a duplicate active budget fails with SQLiteConstraintException
- [ ] Inserting a duplicate current user per group fails with SQLiteConstraintException
- [ ] Inserting a duplicate group expense link fails with SQLiteConstraintException
- [ ] Inserting a duplicate planned occurrence key (PLANNED status) fails
- [ ] Inserting a duplicate raw notification fingerprint fails
- [ ] DatabaseIntegrityScanner detects all 11 invariant violations on a corrupted DB
- [ ] DatabaseIntegrityScanner returns clean result on a valid DB
- [ ] All existing tests pass (no regressions)
- [ ] `PRAGMA integrity_check` returns OK on both fresh and migrated DBs
- [ ] Schema JSON files exported for v105 and v106

---

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
