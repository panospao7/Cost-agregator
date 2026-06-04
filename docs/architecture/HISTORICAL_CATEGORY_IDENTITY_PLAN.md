# Historical Category Identity Plan

## Problem Statement

`AnalyticsInputAssembler` derives category names from the **current** category table at assembly time:

```kotlin
categoryNameSnapshot = current category name by ID from CategoryRepository
```

This means any historical analytics query that includes expenses belonging to a category that was later **deleted** or **renamed** will reflect the *current* state of the category table, not the category identity that was active when the expense was recorded. Concretely:

- **Deleted categories** — The expense appears as "Uncategorized" or falls back to a missing-name placeholder.
- **Renamed categories** — Old expenses inherit the new name, silently rewriting history.

The `categoryNameSnapshot` field on `NormalizedExpense` exists and is populated, but it is populated from live data at query time, not from a historical record. The field is a snapshot of the **current** table lookup, not a snapshot taken at expense creation time.

---

## Design Options

### Option A — Soft-delete categories (recommended first step)

Add an `isArchived` (or `isDeleted`) boolean flag to the `categories` table. Instead of hard-deleting a category row, set this flag to `true`. The UI filters archived categories out of pickers and management screens, but analytics queries can still resolve old category names by ID because the row still exists.

**Pros:**
- Minimal schema change (single column, nullable or default `false`).
- No migration of existing expense rows.
- Backwards-compatible — existing analytics queries continue to work.
- Lowest risk and fastest to implement.

**Cons:**
- Does not handle rename history — if a category is renamed, old expenses still show the new name.
- Category table grows with soft-deleted rows; may need eventual cleanup.
- Does not capture the full historical timeline of a category's name/color/icon.

**Required schema work:**
- Add `isArchived INTEGER NOT NULL DEFAULT 0` to `categories` table (or equivalent Room entity field).
- Update DAO queries that list categories to add `WHERE isArchived = 0`.
- Update UI category pickers/managers to respect the flag.
- No migration of existing expense data needed.

### Option B — Snapshot category fields on expense

Store `categoryNameSnapshot`, `categoryColorSnapshot`, and `categoryIconSnapshot` directly on the `expenses` table (or `normalized_expense` view). Populate these fields when the expense is created or when its category assignment changes.

**Pros:**
- True historical fidelity — each expense carries the category identity that was active at the time of its last category assignment.
- Renames and deletions are fully insulated from history.
- No separate history table to query.

**Cons:**
- Requires a Room migration to add columns to the `expenses` table.
- Snapshot values become stale if a user bulk-recategories expenses (the old snapshot remains unless a backfill job is run).
- Increases storage per expense row.
- Backfill for existing expenses requires a one-time migration.

**Required schema work:**
- Add `categoryNameSnapshot TEXT`, `categoryColorSnapshot TEXT`, `categoryIconSnapshot TEXT` to the `expenses` entity.
- Migration to populate existing rows (nullable — old rows get `null` and fall back to current table lookup).
- Update all expense creation/update paths to write snapshot fields.

### Option C — Category history table

Create a dedicated `category_history` table:

```sql
CREATE TABLE category_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    color TEXT,
    icon TEXT,
    valid_from INTEGER NOT NULL,  -- epoch millis when this identity became active
    valid_to INTEGER,             -- epoch millis when superseded/deleted, NULL = current
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);
```

Analytics queries then resolve `category_history.name` where `valid_from <= expense.date AND (valid_to IS NULL OR expense.date < valid_to)`.

**Pros:**
- Most correct — captures the full rename/identity timeline for every category.
- No per-expense storage bloat.
- Single source of truth for category identity at any point in time.

**Cons:**
- Significant schema and query complexity.
- Requires a trigger or application-level logic to write history rows on every category update.
- Migration to backfill the history table for existing categories.
- Higher engineering cost.
- Over-engineered if the app has few category renames/deletions.

---

## Recommendation

**Option A (soft-delete categories)** is the recommended first step because:

1. **Lowest risk** — single column, no existing data migration.
2. **Addresses the most painful case** — deleted categories making old expenses appear uncategorized.
3. **No rename history required yet** — category renames are rare in practice; soft-delete covers the deletion case that causes the most visible data loss.
4. **Fast to implement** — can be done independently of other analytics work.

| Criterion | Option A | Option B | Option C |
|-----------|----------|----------|----------|
| Implementation effort | Low | Medium | High |
| Schema migration | Minimal (1 column) | Medium (3 columns on expenses) | High (new table) |
| Addresses deleted categories | ✅ | ✅ | ✅ |
| Addresses renamed categories | ❌ | ✅ | ✅ |
| Backfill needed | No | Yes | Yes |
| Runtime query overhead | None | None | Join + range filter |

If rename history becomes a requirement, Option A can be extended later with an optional rename audit log, or the team can migrate to Option B/C.

---

## Status

**DEFERRED** — Requires schema work and is not part of the Engine 2 analytics fixes (PR1–PR6, PR8). The current `categoryNameSnapshot` mechanism in `AnalyticsInputAssembler` provides a best-effort lookup from the current category table; this works adequately for the vast majority of queries. The soft-delete schema change should be planned as a separate PR (e.g., PR-CAT1) with its own Room migration and testing cycle.

---

## Tracker Entry

Add to `ENGINE_ISSUES_MASTER_TRACKER.md` under Analytical Engines:

```
| A15 | P1 | Category deletion/rename distorts history | Enhancement | Soft-delete (isArchived) designed and deferred; see docs/architecture/HISTORICAL_CATEGORY_IDENTITY_PLAN.md | ⏭ DEFERRED |
```
