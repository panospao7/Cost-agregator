# Deep Analysis — Batch 13: Database - Group & Financial Entities (@reviewer)

## Scope
- data/database/entity/ExpenseGroup.kt
- data/database/entity/GroupMember.kt
- data/database/entity/GroupExpense.kt
- data/database/entity/SplitItemAssignment.kt
- data/database/entity/BankConnection.kt
- data/database/entity/ExchangeRate.kt
- data/database/entity/MerchantCanonical.kt
- data/database/entity/MerchantAlias.kt
- data/database/entity/MerchantLocation.kt
- data/database/entity/EmailReceiptSource.kt
- data/database/entity/ManualRecurringExpense.kt
- data/database/entity/Investment.kt

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `data/database/entity/GroupMember.kt` | MAJOR | Constraint | The schema allows multiple members in the same group with `isCurrentUser = true`. Several DAO/domain paths read this as a single row (`LIMIT 1` / first match), so results become nondeterministic if data drifts. | Enforce one current-user row per group with a partial unique index (`UNIQUE(groupId) WHERE isCurrentUser = 1`) or transactional logic that clears the previous current user before setting a new one. |
| 2 | `data/database/entity/GroupExpense.kt` | MAJOR | Relationship | `expenseId` is used like a 1:1 link (`getGroupExpenseForExpense()` returns a single row), but there is no uniqueness constraint. The same expense can be linked to multiple `group_expenses` rows. | Add a unique index for non-null `expenseId` (or `groupId, expenseId` if multi-group linking is truly intended) and make DAO semantics explicit. |
| 3 | `data/database/entity/GroupExpense.kt` | MAJOR | Foreign Key | `paidById` only references `group_members.id`; it does **not** guarantee that the payer belongs to the same `groupId` as the expense. Cross-group payer references are therefore valid at DB level. | Add a composite relationship: make `(groupId, id)` uniquely addressable in `group_members` and reference `(groupId, paidById)` from `group_expenses`. |
| 4 | `data/database/entity/BankConnection.kt` | MAJOR | Foreign Key | `defaultCategoryId` is clearly an internal category reference, but it has no FK to `categories`. Category deletion can leave stale IDs behind and imports can point to nonexistent categories. | Add `ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["defaultCategoryId"], onDelete = SET_NULL)` and index the column if it is queried. |
| 5 | `data/database/entity/MerchantCanonical.kt` | MAJOR | Constraint | Canonical merchant lookup/creation is keyed by `searchKey`, but the entity only enforces uniqueness on `normalizedName`. Different display names that normalize to the same `searchKey` can coexist, making `getCanonicalBySearchKey(... LIMIT 1)` nondeterministic. | Make `searchKey` the unique identity, keep `normalizedName` as display text, and migrate existing duplicates deterministically. |
| 6 | `data/database/entity/MerchantAlias.kt` | MAJOR | Constraint | Alias resolution reads by `normalizedKey LIMIT 1`, but only `rawName` is unique. Multiple aliases with the same `normalizedKey` can point to different canonicals, so merchant normalization can return arbitrary results. | Enforce canonical consistency on `normalizedKey` (or `normalizedKey + canonicalId` with conflict rules) and avoid single-row lookup unless uniqueness is guaranteed. |
| 7 | `data/database/entity/MerchantLocation.kt` | MAJOR | Nullability / Constraint | `areaKey` participates in the composite unique index but is nullable. In SQLite, multiple `(normalizedMerchantName, NULL)` rows bypass uniqueness, so "global" cache entries can duplicate and break upsert semantics. | Make `areaKey` non-null with DB default `'global'`, backfill legacy `NULL`s, and normalize nulls before insert. |
| 8 | `data/database/entity/ManualRecurringExpense.kt` | MAJOR | Default Value | Kotlin default `isSubscription = true` misclassifies new generic recurring expenses as subscriptions when callers omit the flag. This already happens in non-subscription creation paths. | Change the constructor default to `false` for new rows; keep legacy compatibility in migration SQL only, or use explicit factory methods for subscription vs recurring-expense creation. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `GroupExpense`, `SplitItemAssignment`, `ExchangeRate`, `EmailReceiptSource`, `ManualRecurringExpense`, `Investment` | MAJOR | Financially sensitive numeric fields have no DB-level `CHECK` constraints or constructor validation (`totalAmount`, `assignedAmount`, `rate`, `confidence`, `amount`, `purchasePrice`, `quantity`, `reimbursedAmount`, etc.). Invalid negative/zero/out-of-range values can be stored and downstream logic assumes sane inputs. | Add invariant checks in constructors/repositories and back them with SQL `CHECK` constraints in migrations (e.g. `rate > 0`, `confidence BETWEEN 0 AND 1`, `quantity > 0`, non-negative money fields). |

### Summary
- Total issues: 9
- Files with issues: 7/12 directly (`GroupMember`, `GroupExpense`, `BankConnection`, `MerchantCanonical`, `MerchantAlias`, `MerchantLocation`, `ManualRecurringExpense`)
- Requirements met: no — the batch defines the required entities, but several key integrity rules are not enforced at schema level (uniqueness, same-group relationships, FK coverage, safe defaults).
- Testing adequate: no — no targeted DAO tests were provided for the flagged behaviors. The risky cases here need DB-level tests for duplicate participant names, duplicate alias normalized keys, exchange-rate query plans/base-currency lookups, recurring-expense ordering parity, and merchant-location cache scoping.

_No direct structural issue stood out in `ExpenseGroup.kt`; `SplitItemAssignment.kt`, `ExchangeRate.kt`, `EmailReceiptSource.kt`, and `Investment.kt` still participate in the shared validation gap above._
