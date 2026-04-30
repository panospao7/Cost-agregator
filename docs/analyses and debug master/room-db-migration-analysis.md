# Room Database / Migrations / Schema Integrity Deep Analysis

Branch: `master-refactor`

Scope:
- `AppDatabase.kt`
- Room entities
- recent migrations up to schema v92
- fresh-install callback
- key financial tables
- migration test structure

This is a static review; I did not execute the Android migration test suite.

---

## Executive verdict

The database layer is mature but fragile.

Strong points:

- `APP_DATABASE_SCHEMA_VERSION = 92`
- `exportSchema = true`
- centralized `ALL_MIGRATIONS`
- no destructive migration in production builder
- many healing migrations for schema drift
- migration tests exist
- several serious legacy issues are explicitly handled

But the biggest architectural problem is:

> To make Room schema validation happy, several important financial invariants were removed from DB-level constraints and pushed into repository/domain logic.

That makes the schema less protective than the business logic assumes.

The highest-risk issues are:

1. fresh-install schema and migrated schema do not enforce the same constraints
2. non-Room partial unique indexes were added then later removed by healing migrations
3. several “one active / one linked / one current user” rules are not DB-enforced
4. future schema needs from currency/forecast/recurring are not yet represented
5. some rebuild migrations rely on fragile table-shape assumptions
6. migration tests may not fully protect every v6→v92 dirty-data path

---

# Strong parts

## 1. Centralized migration registry

`AppDatabase.configureBuilder()` uses:

```kotlin
.addMigrations(*ALL_MIGRATIONS)
.addCallback(FRESH_INSTALL_CALLBACK)
```

and `ALL_MIGRATIONS` includes every migration from `6→7` through `91→92`.

This is good. It avoids different builders accidentally registering different migration sets.

## 2. No silent destructive migration in normal builder

The production builder does **not** use `fallbackToDestructiveMigration()`.

That is correct for a finance app.

There is a test that explicitly uses fallback, but the main builder comment says old schemas must be migrated or handled through backup/recovery UX.

## 3. Recent migrations are careful about FK table rebuilds

Some migrations correctly disable FK enforcement before parent-table rebuilds, then run `PRAGMA foreign_key_check`.

Examples:
- manual recurring rebuild in `71→72`
- bank/alert rebuild in `73→74`
- raw notification / exchange / budget forecast repair in `83→84`

That is good defensive work.

## 4. There are healing migrations for real-world schema drift

Migrations `67→68`, `83→84`, and `84→92` show that the codebase has already encountered real schema drift and tries to converge users back to Room’s expected schema.

Good direction, but it also reveals that the schema has become hard to reason about.

---

# Critical / high-priority findings

## 1. Fresh-install schema and migrated schema can enforce different rules

### Where

- `FRESH_INSTALL_CALLBACK`
- `MIGRATION_84_85`
- `MIGRATION_85_86`
- `MIGRATION_86_87`
- `MIGRATION_87_88`
- `MIGRATION_88_89`
- `MIGRATION_89_90`
- `MIGRATION_90_91`

### Problem

`FRESH_INSTALL_CALLBACK` adds constraints/indexes that are outside Room’s exported schema contract, especially:

- raw notification partial unique dedupe indexes
- CHECK constraints for budgets
- CHECK constraints for pending reviews
- CHECK constraints for mileage
- CHECK constraints for savings goals

But later “healing” migrations remove some non-Room constraints/indexes to make migrated schemas match Room’s expected schema.

Example:

- Fresh install adds partial unique raw-notification dedupe indexes.
- `84→85` drops those partial indexes on migrated DBs.
- Fresh install rebuilds `budgets` with CHECK constraints.
- `85→86` rebuilds `budgets` without those CHECK constraints.

### Impact

A new user and an upgraded user can get different database behavior.

Example differences:

- fresh install may reject some duplicate raw notifications
- migrated install may allow them
- fresh install may reject invalid budget amounts via CHECK
- migrated install may allow them after `85→86`

This is dangerous because QA on fresh installs may pass while upgraded real-user databases behave differently.

### Severity

**Critical**

### Fix

Create a single post-open schema hardening path that runs after Room validation and applies supported runtime constraints consistently to both fresh and migrated DBs.

Options:

1. Move non-Room runtime constraints into an `onOpen` schema-hardening callback.
2. Use triggers where Room index parity blocks partial unique indexes.
3. Add a dedicated `RuntimeSchemaHardener` with idempotent SQL and diagnostics.
4. Add tests that compare fresh-install runtime schema vs upgraded runtime schema.

### Test

Create DB fresh at v92 and migrated from v6→v92, then compare:

- index list
- trigger list
- CHECK/table SQL for critical tables
- FK list
- insertion behavior for duplicate/invalid rows

---

## 2. Business invariants were removed from DB-level enforcement

### Where

- `MIGRATION_86_87`
- `MIGRATION_87_88`
- `MIGRATION_88_89`
- `MIGRATION_89_90`
- `MIGRATION_90_91`

### Problem

Several healing migrations explicitly drop non-Room partial/unique indexes:

- `index_budgets_active_overall`
- `index_budgets_active_category`
- `index_group_members_groupId_currentUser`
- `index_group_expenses_expenseId_unique`
- `index_budget_forecasts_active_budget_period`
- `index_subscription_candidates_pending_merchant_interval`

These map to important business rules:

- one active overall budget
- one active budget per category
- one current-user member per group
- one system expense linked to at most one group expense
- one active budget forecast per budget/period
- one pending subscription candidate per merchant/interval

The entity comments say some are now enforced transactionally in DAO/repository logic, but the database no longer protects them.

### Impact

Any direct DAO write, future code path, import path, test helper, or race condition can violate core financial invariants.

Examples:

- two active monthly budgets for groceries
- two current-user members in one group
- one expense linked to two group expenses
- duplicate active forecasts for the same budget period

### Severity

**Critical**

### Fix

If Room rejects extra partial indexes, use triggers instead.

Examples:

```sql
CREATE TRIGGER prevent_duplicate_active_overall_budget
BEFORE INSERT ON budgets
WHEN NEW.isActive = 1 AND NEW.categoryId IS NULL
BEGIN
  SELECT RAISE(ABORT, 'Only one active overall budget allowed')
  WHERE EXISTS (
    SELECT 1 FROM budgets
    WHERE isActive = 1 AND categoryId IS NULL
  );
END;
```

For updates, add matching `BEFORE UPDATE` triggers.

For `group_expenses.expenseId`, SQLite unique indexes allow multiple NULLs, so a normal Room unique index could work:

```kotlin
Index(value = ["expenseId"], unique = true)
```

That should allow multiple standalone group expenses where `expenseId IS NULL`.

### Test

Attempt violating inserts/updates directly through DAO/raw SQL and assert DB rejects them.

---

## 3. `group_expenses.expenseId` is non-unique even though one system expense should not link twice

### Where

`GroupExpense.kt`

Current entity index:

```kotlin
Index(value = ["expenseId"])
```

`MIGRATION_70_71` deduplicates legacy non-null `expenseId`.

But `MIGRATION_88_89` drops `index_group_expenses_expenseId_unique`.

### Impact

One real `Expense` can be linked to multiple `GroupExpense` rows.

That can cause:

- duplicate shared spend
- wrong group balances
- budget-offset double counting
- one system expense appearing in multiple groups
- ambiguous unlink/delete behavior

### Severity

**Critical**

### Fix

Make the entity index unique:

```kotlin
Index(value = ["expenseId"], unique = true)
```

SQLite allows multiple `NULL` values in a unique index, so standalone group expenses still work.

Migration:

1. dedupe existing non-null `expenseId`
2. drop old index
3. create unique index
4. add regression test

---

## 4. `group_members` does not enforce exactly one current user per group

### Where

`GroupMember.kt`

Current indexes:

```kotlin
Index(value = ["groupId"])
Index(value = ["groupId", "isCurrentUser"])
Index(value = ["groupId", "name"], unique = true)
```

`MIGRATION_70_71` demotes duplicate current users once, but later migration `87→88` drops the partial unique current-user index.

### Impact

Future bad writes can create:

- zero current-user members
- multiple current-user members

This breaks:

- current-user share calculation
- group budgets
- split ownership
- shared expense dashboard logic

### Severity

**High**

### Fix

Use triggers to enforce:

- at most one current user per group
- optionally at least one current user per active group

At minimum:

```text
no INSERT/UPDATE may create two rows where groupId=X and isCurrentUser=1
```

“At least one” is harder because deleting/demoting may be valid inside a transaction. Enforce it in coordinator plus add an integrity scanner.

---

## 5. `paidById` can reference a member from another group

### Where

`GroupExpense.kt`

`paidById` has FK to `GroupMember.id`, but there is no DB rule that:

```text
group_expenses.groupId == group_members.groupId for paidById
```

Migration comment says trigger-based same-group enforcement is explicitly out of scope.

### Impact

A row can exist where:

- group expense belongs to group A
- payer belongs to group B

Group balances and payer credit become wrong.

### Severity

**High**

### Fix

Add trigger:

```sql
CREATE TRIGGER group_expense_paid_by_same_group_insert
BEFORE INSERT ON group_expenses
BEGIN
  SELECT RAISE(ABORT, 'paidById must belong to same group')
  WHERE NOT EXISTS (
    SELECT 1 FROM group_members
    WHERE id = NEW.paidById
      AND groupId = NEW.groupId
  );
END;
```

Add matching update trigger.

---

## 6. Budget uniqueness is not DB-enforced

### Where

`Budget.kt`
`MIGRATION_71_72`
`MIGRATION_86_87`

The entity comment says active-budget invariants are enforced transactionally in DAO/repository because Room schema must match generated metadata.

But `86→87` drops the active-budget partial unique indexes.

### Impact

Two active category budgets can exist if any code path bypasses the repository guard or a race occurs.

This can corrupt:

- budget status
- safe-to-spend
- dashboard widgets
- adjustment recommendations
- forecasts

### Severity

**High**

### Fix

Use triggers or a separate `active_budget_keys` table.

If keeping repository-only enforcement, add:

- transaction-level DAO tests
- concurrent write tests
- startup integrity repair scanner

---

## 7. Raw notification dedupe is inconsistent

### Where

- `RawNotification.kt`
- `FRESH_INSTALL_CALLBACK`
- `MIGRATION_83_84`
- `MIGRATION_84_85`

`RawNotification` declares only a non-unique covering index:

```kotlin
Index(value = ["packageName", "timestamp", "title", "text"])
```

Fresh install adds partial unique indexes for NULL-safe dedupe cases.

But `84→85` drops those partial indexes for migrated DBs.

### Impact

Fresh installs and upgraded installs can differ.

Also, because SQLite treats `NULL != NULL`, a normal unique index over nullable title/text is insufficient anyway.

Duplicate notifications can re-enter the pipeline and cause:

- duplicate pending reviews
- duplicate expenses
- duplicate raw history
- source stats distortion

### Severity

**High**

### Fix

Use either:

1. DAO-level insert with normalized fingerprint column, or
2. add `dedupeFingerprint TEXT NOT NULL` to `raw_notifications`, unique indexed, or
3. use triggers / runtime hardening consistently on fresh and migrated DBs.

Best fix:

```text
raw_notifications.dedupeFingerprint = hash(packageName, timestampBucket, titleOrEmpty, textOrEmpty)
UNIQUE(dedupeFingerprint)
```

---

## 8. Some rebuild migrations use fragile `INSERT SELECT *`

### Where

`MIGRATION_75_76`

Example:

```sql
INSERT INTO expenses_new SELECT * FROM expenses
```

### Problem

This assumes the old table has exactly the same column order and count as the new table.

That is fragile after many schema repairs and long-hop upgrade paths.

### Impact

If a legacy user has column-order drift or missing/excess columns, migration can:

- fail at startup
- copy wrong values into wrong columns
- silently corrupt data

### Severity

**High**

### Fix

Always use explicit column lists for table rebuilds:

```sql
INSERT INTO expenses_new (
  id, amount, currency, merchant, ...
)
SELECT
  id, amount, currency, merchant, ...
FROM expenses
```

Also add `PRAGMA table_info` assertions before risky rebuilds.

---

## 9. `repairTable()` can drop partially salvageable data

### Where

`MIGRATION_67_68`

The helper preserves data only if the old table contains **all** canonical columns:

```kotlin
if (exists && oldColumns.containsAll(canonicalColumns.toSet())) {
    INSERT all columns
}
```

If one column is missing, it recreates the table and drops old data.

### Impact

For malformed late-feature tables, this may be acceptable. But it can still lose user-visible data such as:

- anomaly alerts
- prompt states
- health history
- subscription candidates
- budget adjustment records
- email receipt sources

### Severity

**Medium / High**

### Fix

Use partial salvage:

- intersect old and new columns
- insert available columns
- supply defaults for missing columns
- log migration repair outcome

Example:

```text
copyColumns = oldColumns ∩ canonicalColumns
missingColumns = canonicalColumns - oldColumns
```

Then insert copyable columns and let defaults fill the rest.

---

## 10. Several money-bearing tables still lack currency/base-money schema

### Where

- `Expense.kt`
- `Budget.kt`
- `PlannedExpense.kt`
- forecast entities
- stress forecast snapshots
- budget forecasts

### Problem

From previous currency/forecast audits, the schema still lacks:

- transaction base amount
- base currency
- exchange rate used
- exchange-rate timestamp
- budget currency
- planned expense currency
- forecast currency
- historical exchange-rate rows

### Impact

Even perfect migrations cannot protect financial correctness while the schema cannot represent:

- original amount
- normalized amount
- display currency
- historical conversion rate

Affected tables include:

- `expenses`
- `budgets`
- `planned_expenses`
- `budget_forecasts`
- `stress_forecast_snapshots`
- `manual_recurring_expenses` partially has currency, but no conversion snapshot

### Severity

**Critical if multi-currency is user-facing**

### Fix

Add schema support for `MoneySnapshot`:

For `expenses`:

```text
originalAmount
originalCurrency
baseAmount
baseCurrency
exchangeRateUsed
exchangeRateTimestamp
conversionStatus
```

For `budgets`:

```text
currency
```

For `planned_expenses`:

```text
currency
baseAmount
baseCurrency
conversionStatus
sourceType
sourceId
occurrenceDate
status
linkedExpenseId
generatedKey
```

---

## 11. Planned/recurring lifecycle tables are missing

### Where

- `PlannedExpense.kt`
- `ManualRecurringExpense.kt`
- no occurrence/reminder table visible in current DB entity list

### Problem

The schema does not represent recurring occurrence lifecycle:

- due date
- paid/skipped/missed
- linked actual expense
- reminder sent/dismissed/snoozed
- generated planned item uniqueness

### Impact

The app cannot reliably prevent:

- duplicate planned items
- duplicate reminders
- planned + actual double counting
- recurring + planned double counting
- stale deleted recurring instances

### Severity

**Critical for forecasting/reminders**

### Fix

Add:

```text
recurring_occurrences
recurring_reminder_states
warranty_reminder_states
background_job_runs
```

Minimum recurring occurrence unique key:

```text
sourceType + sourceId + occurrenceDate
```

---

## 12. `PlannedExpense` has no uniqueness guard

### Where

`PlannedExpense.kt`

Current indexes:

```kotlin
date
categoryId
```

No source key, no status, no linked actual.

### Impact

A generator or worker can insert the same future item repeatedly.

Forecasting cannot know whether a planned item is:

- future
- paid
- skipped
- matched to actual
- generated from recurring rule
- manually entered

### Severity

**High**

### Fix

Add lifecycle fields and unique generated key.

---

## 13. `ExchangeRate` is latest-only by schema

### Where

`ExchangeRate.kt`

Unique index:

```kotlin
Index(value = ["fromCurrency", "toCurrency"], unique = true)
```

### Problem

Only one row per currency pair can exist.

No historical `validDate`.

### Impact

Past reports can change after rate refresh.

### Severity

**High / Critical for historical reporting**

### Fix

Change uniqueness to:

```text
fromCurrency + toCurrency + validDate + source
```

Add:

```text
fetchedAt
validDate
source
```

---

## 14. String default annotations are inconsistent

### Where

Examples:

```kotlin
@ColumnInfo(defaultValue = "EUR")
@ColumnInfo(defaultValue = "manual")
@ColumnInfo(defaultValue = "'ROLLING'")
```

Some string defaults are quoted and some are not.

Migrations often use SQL defaults like:

```sql
DEFAULT 'EUR'
DEFAULT 'manual'
```

### Impact

Room schema validation is sensitive to defaults. Inconsistent quoting can create schema drift or future migration surprises.

The current tests may pass, but this is brittle.

### Severity

**Medium**

### Fix

Standardize string defaults.

For SQL string literals, prefer quoted annotation values:

```kotlin
@ColumnInfo(defaultValue = "'EUR'")
@ColumnInfo(defaultValue = "'manual'")
```

Then regenerate schema and align migrations.

---

## 15. Cascade behavior can erase financial relationship history

### Where

Examples:

- `GroupExpense.groupId → ExpenseGroup ON DELETE CASCADE`
- `GroupExpense.expenseId → Expense ON DELETE CASCADE`
- `Warranty.receiptId → ScannedReceipt ON DELETE CASCADE`
- `ReturnWindow.receiptId → ScannedReceipt ON DELETE CASCADE`

### Problem

Cascades are convenient but dangerous for financial/audit data.

Deleting a group deletes group expense records. Linked system expenses can remain with shared flags but no group audit context.

Deleting a receipt deletes warranties/return windows.

### Impact

Financial history can lose context.

### Severity

**High for shared expenses; Medium for warranty/return**

### Fix

For financial history, prefer:

- soft delete
- archive
- explicit destructive delete UX
- `ON DELETE SET NULL` plus immutable audit fields
- separate historical snapshot fields

---

# Schema gaps from previous audits

The DB schema currently does not support the safest version of fixes we identified earlier.

## Needed for dashboard/currency correctness

- `expenses.baseAmount`
- `expenses.baseCurrency`
- `expenses.exchangeRateUsed`
- `budgets.currency`
- historical exchange rates

## Needed for recurring/forecast correctness

- `planned_expenses.currency`
- `planned_expenses.status`
- `planned_expenses.sourceType`
- `planned_expenses.sourceId`
- `planned_expenses.occurrenceDate`
- `planned_expenses.linkedExpenseId`
- unique `generatedKey`
- `recurring_occurrences`

## Needed for reminders/background idempotency

- `recurring_reminder_states`
- `warranty_reminder_states`
- `background_job_runs`
- per-worker/per-item attempt state

## Needed for shared/split integrity

- unique non-null system expense link
- same-group payer enforcement
- one current user per group
- stricter reimbursement amount checks
- group archive/delete history policy

## Needed for AI privacy/audit

- AI route diagnostics table or safe artifact metadata
- no raw prompts
- provider used
- redaction applied
- cloud/image used flags

---

# Recommended fix order

## PR 1 — Fresh vs migrated schema parity test

Add a test that builds:

1. fresh v92 DB through normal builder
2. migrated v6→v92 DB

Then compare:

- tables
- columns
- indexes
- triggers
- FK lists
- table SQL for CHECK constraints
- behavior for critical invalid inserts

This test should fail today if fresh-only runtime constraints remain.

## PR 2 — Restore DB protection for critical invariants

Use Room-compatible unique indexes where possible:

- `group_expenses.expenseId` unique

Use triggers for partial business rules:

- one active overall budget
- one active category budget
- one current user per group
- paidById belongs to same group
- raw notification dedupe if no fingerprint column yet

## PR 3 — Replace `INSERT SELECT *` in migrations

Every table rebuild should use explicit column lists.

Prioritize:

- `expenses`
- `budgets`
- `pending_reviews`
- `mileage_tracking`
- any table with financial data

## PR 4 — Add money schema foundation

Add normalized/base-money fields to `expenses`, `budgets`, and `planned_expenses`.

Add historical exchange-rate schema.

## PR 5 — Add recurring/planned occurrence schema

Add:

- `recurring_occurrences`
- `recurring_reminder_states`
- planned lifecycle fields

## PR 6 — Add runtime schema hardener

If Room validation prevents some DB constraints from living in migrations, apply them after validation through a centralized hardener and test it directly.

## PR 7 — Add DB integrity scanner

At startup or debug diagnostics, scan for:

- duplicate active budgets
- duplicate group expense links
- multiple current users per group
- orphaned shared expenses
- planned duplicates
- missing currency/base amount
- FK violations

Do not auto-repair destructive cases without user-safe policy.

---

# Regression tests to add

1. Fresh v92 and migrated v6→v92 enforce the same raw-notification duplicate behavior.
2. Fresh v92 and migrated v6→v92 enforce the same budget invalid-amount behavior.
3. Two active overall budgets are rejected.
4. Two active category budgets are rejected.
5. Two current-user members in one group are rejected.
6. Zero-current-user active group is detected by integrity scanner.
7. One expense cannot be linked to two group expenses.
8. `paidById` from another group is rejected.
9. Deleting a group does not silently corrupt linked expense semantics.
10. `INSERT SELECT *` migrations are replaced by explicit-column migrations.
11. v6→v92 long-hop migration passes with seeded dirty data.
12. v33→v92, v51→v92, v70→v92, v83→v92 long-hop migrations pass.
13. Missing/invalid FK rows are repaired or migration fails explicitly.
14. Planned recurring generated item uniqueness prevents duplicate instances.
15. Exchange rates can store two different valid dates for same pair.
16. Mixed-currency expense backfill marks conversion status correctly.
17. `PRAGMA foreign_key_check` passes after every table-rebuild migration.

---

# Top three priorities

If you only do three things first:

1. **Add a fresh-vs-migrated schema parity test.**
2. **Restore DB-level enforcement for group/budget uniqueness using triggers or Room-compatible indexes.**
3. **Add schema support for money snapshots and planned/recurring occurrence identity.**

Those give the biggest safety gain.

---

# Sources reviewed

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `Expense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `RawNotification.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/RawNotification.kt

- `PendingReview.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt

- `PlannedExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `ManualRecurringExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt

- `ExchangeRate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt

- `Budget.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Budget.kt

- `GroupExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt

- `GroupMember.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupMember.kt

- `ScannedReceipt.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `Warranty.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Warranty.kt

- `ReturnWindow.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt

- `DatabaseMigrationTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt