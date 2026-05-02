# Phase 7 Adjustment — Avoid Raw Partial Index Drift

## Key correction

Do **not** re-add raw SQL partial unique indexes for budgets as originally written:

```sql
CREATE UNIQUE INDEX ... WHERE isActive = 1 ...
```

That risks repeating the historical problem from `MIGRATION_86_87`: Room does not model partial indexes via `@Entity(indices = ...)`, so migration/schema validation can see “extra indexes” and fail.

Also: simply “updating schema JSON” is not enough. Room’s runtime expected schema comes from generated code, not hand-edited JSON.

---

# Recommended approach

## 1. Use Room-visible unique indexes wherever possible

### `group_expenses.expenseId`

This does **not** need a partial index. SQLite allows multiple `NULL`s in a unique index.

Use:

```kotlin
Index(value = ["expenseId"], unique = true)
```

Migration:

1. heal duplicates
2. drop old non-unique index
3. create unique index

Room can validate this.

---

### `planned_expenses.sourceOccurrenceKey`

Also does not need partial index if policy is “one occurrence key forever”.

Use:

```kotlin
Index(value = ["sourceOccurrenceKey"], unique = true)
```

Multiple `NULL`s are allowed.

If cancelled/superseded planned rows should allow regeneration, add a materialized nullable key instead:

```kotlin
val openSourceOccurrenceKey: String?
```

Set it only for active/open planned rows, then:

```kotlin
Index(value = ["openSourceOccurrenceKey"], unique = true)
```

---

### `raw_notifications.dedupeFingerprint`

Do not use partial index.

Add:

```kotlin
val dedupeFingerprint: String?
```

and:

```kotlin
Index(value = ["dedupeFingerprint"], unique = true)
```

Multiple legacy `NULL`s are allowed. New rows must always set fingerprint.

---

## 2. For budgets, avoid raw partial indexes

Budget uniqueness is the tricky case because the invariant is conditional:

- one active overall budget
- one active budget per category

Use one of these two safe options.

---

# Option A — Recommended: Materialized invariant keys

Add internal columns to `Budget`:

```kotlin
val activeOverallKey: Int? = null
val activeCategoryKey: Long? = null
```

Rules:

```text
inactive budget:
  activeOverallKey = NULL
  activeCategoryKey = NULL

active overall budget:
  categoryId = NULL
  activeOverallKey = 1
  activeCategoryKey = NULL

active category budget:
  categoryId != NULL
  activeOverallKey = NULL
  activeCategoryKey = categoryId
```

Then declare Room-visible unique indexes:

```kotlin
Index(value = ["activeOverallKey"], unique = true),
Index(value = ["activeCategoryKey"], unique = true)
```

SQLite allows many `NULL`s, so this enforces exactly what we need.

## Add CHECK constraints in migration

To stop bad keys from bypassing the invariant:

```sql
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
)
```

Room usually does not validate CHECK constraints strictly, and your DB already uses migration-created CHECKs.

## Pros

- Room-compatible.
- No raw partial index drift.
- DB-enforced uniqueness.
- Works on fresh and migrated DBs.

## Cons

- Adds internal columns.
- DAO/repository must maintain keys.
- Requires table rebuild or careful migration.

---

# Option B — SQLite triggers instead of partial indexes

Create triggers:

- `budgets_one_active_overall_insert`
- `budgets_one_active_overall_update`
- `budgets_one_active_category_insert`
- `budgets_one_active_category_update`

Example:

```sql
CREATE TRIGGER budgets_one_active_overall_insert
BEFORE INSERT ON budgets
WHEN NEW.isActive = 1 AND NEW.categoryId IS NULL
BEGIN
  SELECT RAISE(ABORT, 'only one active overall budget')
  WHERE EXISTS (
    SELECT 1 FROM budgets
    WHERE isActive = 1 AND categoryId IS NULL
  );
END;
```

For update, exclude `OLD.id`.

## Pros

- No entity columns.
- Room generally ignores triggers in schema validation.
- Enforces DB-level invariant.

## Cons

- Raw SQL object not visible to Room schema.
- Easy to lose during table rebuild migrations.
- Must test fresh and migrated installs carefully.
- Harder to reason about than unique indexes.

---

# My recommendation

Use:

## Room-visible unique indexes

For:

- `group_expenses.expenseId`
- `planned_expenses.sourceOccurrenceKey`
- `raw_notifications.dedupeFingerprint`

## Materialized invariant keys

For:

- budget active overall/category uniqueness
- group current-user uniqueness if you want Room-visible enforcement

For `group_members`, add:

```kotlin
val currentUserGroupKey: Long? = null
```

Set:

```text
isCurrentUser = true  -> currentUserGroupKey = groupId
isCurrentUser = false -> currentUserGroupKey = NULL
```

Then:

```kotlin
Index(value = ["currentUserGroupKey"], unique = true)
```

Add CHECK:

```sql
CHECK (
  (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
  OR
  (isCurrentUser = 1 AND currentUserGroupKey = groupId)
)
```

This avoids raw partial index drift entirely.

---

# Revised Phase 7 migration strategy

## Migration `CURRENT -> NEXT`

1. Add internal invariant columns:
   - `budgets.activeOverallKey`
   - `budgets.activeCategoryKey`
   - `group_members.currentUserGroupKey`
   - `raw_notifications.dedupeFingerprint`
2. Heal existing duplicates:
   - deactivate duplicate active budgets
   - demote duplicate current users
   - null duplicate group expense links
   - supersede/null duplicate planned occurrence keys
3. Backfill invariant keys:
   - active budget keys
   - current user keys
   - raw notification fingerprints
4. Rebuild tables if CHECK constraints are added.
5. Add Room-declared unique indexes through entity annotations where possible.
6. Export new schema JSON.
7. Add migration tests with dirty historical data.

---

# What to change in the Phase 7 plan

Replace this:

```sql
CREATE UNIQUE INDEX ... WHERE isActive = 1 ...
```

with either:

1. materialized key columns + normal Room unique indexes, or
2. triggers, if you strongly prefer no new columns.

I recommend materialized key columns because they are easier to validate and less likely to recreate the historical partial-index problem.

---

# Important test additions

Add tests specifically proving:

1. Room migration validation passes with the new constraints.
2. Fresh install and migrated DB have the same Room-visible indexes.
3. Dirty historical duplicate budgets are healed.
4. Duplicate active budget insert fails.
5. Duplicate current user insert fails.
6. Duplicate group expense link fails.
7. Duplicate planned occurrence key fails.
8. Duplicate raw notification fingerprint fails.
9. No raw partial indexes are present unless deliberately allowlisted.

Query to assert no problematic partial budget indexes:

```sql
SELECT name, sql
FROM sqlite_master
WHERE type = 'index'
  AND tbl_name = 'budgets'
  AND sql LIKE '% WHERE %';
```

Expected: none, if using materialized keys.

---

# Bottom line

Your AI analysis is correct: re-adding the historical budget partial indexes is risky.

The safest Phase 7 correction is:

- **Do not re-add raw partial budget indexes.**
- Use **Room-visible unique indexes** where nullable unique columns are enough.
- Use **materialized invariant keys** for conditional uniqueness.
- Keep raw SQL partial indexes out of the migration path unless you fully accept Room validation complexity.