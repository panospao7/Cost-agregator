# Shared Expenses / Splits / Reimbursements Analysis

Branch: `master-refactor`

Scope:
- Segment 21: Enhanced Split Transactions
- Segment 24: Shared Expense Groups
- Segment 25: Shared Expense Budget Offset

## Executive verdict

This area is structurally better than expected: there is a clear coordinator, split calculator, custom split parser, and budget-offset engine.

But the highest-risk bugs are around **path inconsistency**:

1. linked group expenses are mostly safe,
2. standalone group expenses are much weaker,
3. archived/deleted groups can change budget math,
4. current-user membership is assumed but not always guaranteed,
5. custom split data can silently degrade to equal split.

The most critical thing to fix first is:

> Make every group expense creation path go through one atomic coordinator path that stores and validates the same fields, especially `customSplitsJson`, `date`, `currency`, and current-user share.

---

# Core data flow

## Linked group expense path

Typical UI path:

`SharedExpenseGroupsViewModel.addExpense()`  
→ `AddGroupExpenseUseCase.invokeAtomic()`  
→ `GroupsRepository.createSystemExpenseAndLinkToGroup()`  
→ `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()`  
→ inserts:
- `Expense`
- `GroupExpense`

This is the strongest path.

It creates a system expense with:

- `isSharedExpense = true`
- `myShareAmount = currentUserShare`
- `dedupeKey`
- `merchantKey`
- group link

This is good.

## Link existing expense path

`AddGroupExpenseUseCase.invoke()`  
→ `GroupsRepository.addExpenseWithLink()`  
→ `GroupTransactionCoordinator.addExpenseWithLink()`  
→ inserts `GroupExpense` and normalizes existing `Expense`.

Also mostly good, but has date-risk if callers do not pass the original expense date.

## Standalone group expense path

`SharedExpenseManager.addExpense()`  
→ `SharedExpenseDataPortAdapter.addExpense()`  
→ `GroupTransactionCoordinator.addExpenseToGroup()`

This is the weakest path.

It can validate custom splits in the domain manager, but the adapter/coordinator standalone method does not persist `customSplitsJson`.

---

# Critical / high-priority findings

## 1. Standalone group expenses drop custom split payloads

### Where

- `SharedExpenseManager.addExpense()`
- `SharedExpenseDataPortAdapter.addExpense()`
- `GroupTransactionCoordinator.addExpenseToGroup()`

### Problem

`SharedExpenseManager.addExpense()` validates and serializes custom splits for non-equal splits.

But if the expense is standalone, meaning `expenseId == null`, the adapter calls:

`transactionCoordinator.addExpenseToGroup(...)`

That method has no `customSplitsJson` parameter and creates `GroupExpense(customSplitsJson = null)`.

### Impact

A user can create a standalone group expense with `CUSTOM_AMOUNT`, `CUSTOM_PERCENT`, or `UNEQUAL`, but the persisted row loses the custom split details.

Later `SplitCalculator` sees invalid/missing custom data and falls back to equal split.

So a custom split like:

- A: €70
- B: €20
- C: €10

can silently become:

- A: €33.33
- B: €33.33
- C: €33.34

### Severity

**Critical** if standalone group expenses are user-facing.

### Fix

Add `customSplitsJson` to:

- domain coordinator interface `addExpenseToGroup`
- data coordinator implementation
- repository adapter calls

Then validate it inside the same transaction, same as linked expenses.

---

## 2. `addExpenseToGroup()` is not transactional despite coordinator contract

### Where

`GroupTransactionCoordinator.addExpenseToGroup()`

### Problem

The domain interface says coordinator operations are transactional.

But `addExpenseToGroup()` performs:

1. get group
2. get members
3. validate
4. insert group expense

outside `database.withTransaction`.

### Impact

Race/window risks:

- group can be archived between validation and insert
- member can be deleted between validation and insert
- payer membership can change
- insert can happen against stale validation assumptions

### Severity

**High**

### Fix

Wrap the full standalone path in `database.withTransaction`, just like `addExpenseWithLink()` and `createSystemExpenseAndLinkToGroup()`.

---

## 3. Archived groups can disappear from budget-offset calculations

### Where

`SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()`

### Problem

The engine uses:

`groupsRepository.getActiveGroupsWithDetails()`

So only active groups are considered.

But linked system expenses remain in the main `expenses` table with:

- `isSharedExpense = true`
- `myShareAmount = ...`

Then budget-offset personal spend excludes shared expenses:

`!expense.isSharedExpense`

### Impact

If a group is archived, its historical group expenses may no longer be included in shared spend, while linked system expenses are also excluded from personal spend.

Result:

> archived group expenses can vanish from effective budget spend.

Example:

- €100 shared dinner linked to active group → budget sees my €50 share.
- Archive group.
- Budget offset engine no longer sees group expense.
- Linked system expense is excluded from personal spend because it is shared.
- Effective budget spend can drop by €50.

### Severity

**Critical** if `SharedExpenseBudgetOffsetEngine` is used for real budget status.

### Fix options

Best:

- budget calculations should include historical group expenses regardless of group active status.

Alternative:

- archive only affects UI visibility, not financial history.
- add repository method: `getGroupsWithDetailsForPeriodIncludingArchived(periodStart, periodEnd)`.

Also add test:

- create linked shared expense
- archive group
- budget spend for historical period remains unchanged

---

## 4. Hard delete / permanent delete leaves linked expenses semantically orphaned

### Where

- `GroupTransactionCoordinator.deleteGroupAtomic()`
- `SharedExpenseDataPortAdapter.deleteGroup()`
- `Expense` shared flags

### Problem

Hard deleting a group cascades/deletes `group_expenses`, but linked `expenses` remain.

Those `Expense` rows can still have:

- `isSharedExpense = true`
- `myShareAmount != null`

But no group link remains.

### Impact

The app can have main expenses that say “shared” without a group record explaining the split.

This can corrupt:

- auditability
- budget-offset engine
- transaction history
- future edit/recompute logic

### Severity

**High**

### Fix

Before deleting group/group_expenses, decide policy:

Option A — preserve linked expenses as shared historical records:
- keep group/group_expense rows archived, do not hard-delete financial history.

Option B — unlink:
- reset linked expenses:
  - `isSharedExpense = false`
  - `myShareAmount = null`
  - `mySharePercentage = null`
  - `sharedWithName = null`

Option C — delete linked system expenses too:
- dangerous; only if user explicitly chooses “delete all transactions”.

---

## 5. Existing-expense linking can use the wrong date

### Where

`AddGroupExpenseUseCase.invoke()`

### Problem

When linking an already-existing system expense to a group, date defaults to:

`timeProvider.now()`

unless caller passes a date.

But the linked system expense already has its own `Expense.date`.

### Impact

If caller forgets to pass the original expense date:

- system expense date and group expense date diverge
- budget period filtering can include one but not the other
- linked expense exclusion can fail around month boundaries
- shared liability can appear in the wrong month

Example:

- system expense happened March 31
- user links it to group on April 2
- group expense date becomes April 2
- March budget may still see the linked system expense differently than April shared spend

### Severity

**High**

### Fix

For linking an existing expense, coordinator/repository should load the system expense and use its date by default.

Better API:

```kotlin
addExpenseWithLink(
    groupId,
    systemExpenseId,
    ...
    dateOverride: Long? = null
)
```

If `dateOverride == null`, use `existingExpense.date`.

---

## 6. Current-user member is assumed but not guaranteed

### Where

- `SharedExpenseManager.createGroup()`
- `GroupTransactionCoordinator.resolveCurrentUserShare()`
- `SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()`

### Problem

Several flows assume each group has exactly one current-user member.

But `SharedExpenseManager.createGroup()` marks a member as current user only if a name equals `currentUserName`.

If `memberNames` does not include `"Me"` or the configured current user name, the group can be created with **zero** current-user members.

Also, DB schema does not enforce “one current user per group” with a partial unique constraint.

### Impact

No current user means:

- linked group expense creation fails because current-user share cannot be calculated
- budget-offset engine skips the group entirely
- balances may still show, but personal ownership cannot be derived

Duplicate current users mean:

- `singleOrNull { it.isCurrentUser }` fails
- current-user share becomes null
- linked expense creation can fail unexpectedly

### Severity

**High**

### Fix

Enforce exactly one current user.

At domain level:

- if member list lacks current user, add one automatically
- or return validation error

At DB level:

- add partial unique index:
  - one `isCurrentUser = 1` per group

If Room schema validation makes partial indexes awkward, enforce with transaction + migration tests at minimum.

---

## 7. `paidById` same-group rule is not DB-enforced

### Where

`GroupExpense` entity

### Problem

`GroupExpense.paidById` has FK to `GroupMember.id`, but no database rule ensures that the member belongs to the same `groupId`.

Coordinator validates this, but direct DAO inserts or future code can bypass it.

### Impact

A row can theoretically exist with:

- `groupExpense.groupId = 10`
- `paidById` points to a member from group 20

Then calculations for group 10 will not correctly credit the payer.

### Severity

**High**

### Fix

Options:

1. use a trigger to enforce paid member belongs to same group
2. use composite FK design
3. restrict all writes through coordinator and add integrity tests/scanners

Given this is financial math, I’d prefer a DB-level guard.

---

## 8. One system expense can still be linked more than once at schema level

### Where

- `GroupExpense` entity
- `GroupExpenseDao`
- `AppDatabase` migrations

### Problem

`GroupExpense.expenseId` has a non-unique index.

Coordinator checks whether a system expense is already attached, but DB does not enforce uniqueness.

Migration history also shows duplicate healing, then later a non-Room unique index was dropped for schema parity.

### Impact

If duplicate links appear:

- one system expense can be excluded once from personal spend
- but multiple group expenses can add shared spend multiple times
- balances and budget offsets can double-count

### Severity

**High**

### Fix

Use a Room-expressible unique index:

```kotlin
Index(value = ["expenseId"], unique = true)
```

SQLite allows multiple `NULL` values in a unique index, so standalone group expenses with `expenseId = null` remain allowed.

Then add migration:

- dedupe existing non-null `expenseId`
- drop old index
- create unique index

---

## 9. Multi-currency is raw-summed in shared budget offset

### Where

`SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()`

### Problem

The engine sums:

- personal `Expense.effectiveAmount`
- shared `SplitCalculator.calculateMemberShare(...)`

without currency conversion.

### Impact

Mixed currency example:

- €50 personal
- $50 shared liability

Engine returns `100.0`, with no currency meaning.

### Severity

**Critical if multi-currency is user-facing**

### Fix

Budget offset should aggregate by currency and convert to selected budget/display currency.

Minimum:

- add currency buckets to `BudgetSpendBreakdown`
- convert before summing
- reject mixed-currency total if converter unavailable

---

## 10. Custom split semantics force all current members into every custom split

### Where

`CustomSplitParser.parseAndValidate()`

### Problem

Custom splits require:

`parsed.keys == groupMemberIds`

So a custom split must include every current group member exactly once.

### Impact

This is restrictive for real-world splits:

- one member did not participate
- member joined after the expense
- only two people split one item inside a larger group

Workaround is to include non-participants with `0.0`, but that must be generated perfectly by every caller.

### Severity

**Medium / High UX-data risk**

### Fix

Support explicit participant set for custom splits.

Validation should require:

- referenced member IDs are valid
- amounts/percentages sum correctly among participants
- payer can be non-participant if they paid on behalf of others
- late-joiner behavior is explicit

---

## 11. Invalid custom split data silently falls back to equal split

### Where

`SplitCalculator`

### Problem

If custom split parsing fails, `SplitCalculator` falls back to equal split.

This is helpful for legacy data but dangerous for current data.

### Impact

Malformed or missing custom payloads become plausible-looking equal splits.

This can hide serious bugs, especially the standalone custom split loss described above.

### Severity

**Medium / High**

### Fix

Separate modes:

- legacy read mode: fallback with warning
- strict financial mode: surface invalid split and exclude or flag
- UI mode: show “split data invalid”

For budget/balance correctness, I would not silently equal-split newly-created invalid rows.

---

## 12. Linked `Expense.myShareAmount` can drift from group split data

### Where

- `GroupTransactionCoordinator.normalizeLinkedSystemExpense()`
- `Expense.effectiveAmount`
- `GroupExpense.customSplitsJson`

### Problem

When linking/creating a shared expense, current-user share is copied into the main `Expense`.

But if group split details later change, there is no obvious recompute path to update the linked `Expense.myShareAmount`.

### Impact

Two sources of truth can diverge:

- group balance says current user owes €30
- dashboard/budget via `Expense.effectiveAmount` says €50

### Severity

**High if group expenses are editable**

### Fix

Make share recomputation part of any group expense update/member update.

Add invariant test:

- linked expense `myShareAmount` equals `SplitCalculator.calculateMemberShare()` for current user

---

## 13. Enhanced split item assignment is not transactional and lacks validation

### Where

`EnhancedSplitManager.assignItemsToParticipants()`

### Problem

It does:

1. delete all assignments for expense
2. insert new assignments

without transaction.

It also does not validate:

- assigned amounts are finite/non-negative
- total assigned equals expense or receipt total
- duplicate receipt item assignment rules
- participant exists
- blank participant names

### Impact

If insert fails after delete, all assignments are lost.

Invalid item-level split data can be persisted and later marked paid.

### Severity

**High for receipt item splitting**

### Fix

Move this into a DAO `@Transaction` or database coordinator method.

Validate:

- no negative/NaN/infinite amounts
- assignment total matches target total within cents
- participant IDs, not only names
- duplicate item policy

---

## 14. Split templates are weakly validated

### Where

`EnhancedSplitManager.createTemplate()`

### Problem

Templates store shares JSON, but creation does not validate:

- percentage sum = 100
- custom amount sum = expected total
- participant count > 0
- no negative values
- finite values

### Impact

A bad template can generate bad split previews or invalid downstream split data.

### Severity

**Medium**

### Fix

Validate templates by split type before saving.

For percentage templates, require total 100%.

For amount templates, either require a base total or mark as relative template.

---

## 15. There are two settlement calculation paths

### Where

- `SplitCalculator.simplifyBalances()`
- `SettlementCalculator.calculateSettlements()`

### Problem

There is an older/simple settlement simplifier and a newer optimal settlement calculator.

They may produce different settlement plans.

### Impact

Different screens/features can show different “who pays whom” results for the same balances.

### Severity

**Medium**

### Fix

Use `SettlementCalculator` as the only settlement-plan generator.

Keep `SplitCalculator` for split/share/balance math only.

---

# Strong parts

These should be preserved:

## 1. Atomic linked create path exists

`createSystemExpenseAndLinkToGroup()` correctly creates the system expense and group link in one transaction.

This avoids orphan windows.

## 2. Main `Expense.effectiveAmount` supports shared ownership

The `Expense` model has a clear effective amount rule:

- not mine → 0
- shared with explicit amount → my share
- shared with percentage → proportional share
- otherwise full amount

Good foundation.

## 3. Equal split uses cent-based rounding

`SplitCalculator.calculateEqualSplit()` distributes cents deterministically.

Good.

## 4. Custom split parser is strict

`CustomSplitParser` validates:

- unknown member IDs
- negative values
- non-finite values
- totals for amount/percentage splits

Good.

## 5. Member deletion has safety checks

Deletion blocks members who:

- paid expenses
- are referenced in custom splits
- participated in equal splits after joining

Good.

## 6. Tests already cover important cases

There are tests for:

- balances across split types
- joinedAt-aware equal splits
- budget offset linked-expense exclusion
- malformed custom split fallback
- settlement calculator stress cases
- coordinator transaction behavior

The missing tests are mostly around alternate paths and lifecycle changes.

---

# Recommended fix order

## PR 1 — Unify group expense creation paths

Make standalone and linked group expenses use the same validation/transaction logic.

Must include:

- `customSplitsJson`
- date
- currency
- payer validation
- current-user share calculation where relevant

## PR 2 — Fix archive/delete financial semantics

Decide:

- archived groups still count historically, or
- linked expenses are converted/unlinked on archive/delete

I recommend:

- archive = hide from active UI only, still count historical expenses
- hard delete = explicit destructive operation with clear linked-expense policy

## PR 3 — Fix date source for linking existing expenses

When linking an existing expense, default group expense date to `Expense.date`, not `now`.

## PR 4 — Enforce DB invariants

Add:

- unique non-null `expenseId` link via Room unique index
- current-user uniqueness per group, if possible
- paidById same-group guard via trigger or integrity checker

## PR 5 — Multi-currency support for shared budget offset

No raw summing across currencies.

## PR 6 — Strict-vs-legacy custom split handling

Do not silently equal-split invalid current data.

## PR 7 — Transactional item assignment

Make receipt item assignment atomic and validated.

---

# Regression tests to add

1. Standalone custom amount split persists `customSplitsJson`.
2. Standalone custom percent split does not become equal split.
3. `addExpenseToGroup()` rolls back if validation/insert fails.
4. Archive group does not erase historical budget impact.
5. Hard delete either unlinks/reset linked expenses or is blocked.
6. Existing-expense linking uses existing expense date by default.
7. Missing current-user member blocks group creation or auto-adds current user.
8. Duplicate current-user members are rejected.
9. Same system expense cannot be linked to two group expenses.
10. `paidById` from another group is rejected at DB/integrity level.
11. Mixed currency shared budget offset does not raw-sum.
12. Invalid custom split created through DAO is surfaced, not silently accepted as equal in strict path.
13. `Expense.myShareAmount` stays equal to calculated current-user share after split update.
14. Item assignment delete+insert is atomic.
15. Split templates reject invalid percentages/amounts.

---

# Overall priority

If you only fix three things:

1. **Fix standalone group expense custom split loss.**
2. **Fix archived/deleted group budget semantics.**
3. **Make existing-expense linking use the original expense date.**

Those are the most likely to produce wrong financial numbers.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `GroupTransactionCoordinator.kt` domain interface  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt

- `GroupTransactionCoordinator.kt` data implementation  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- `SharedExpenseManager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt

- `SharedExpensePort.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt

- `SharedExpenseDataPortAdapter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt

- `GroupsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt

- `GroupsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt

- `AddGroupExpenseUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt

- `SharedExpenseBudgetOffsetEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt

- `SplitCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/SplitCalculator.kt

- `CustomSplitParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitParser.kt

- `CustomSplitJsonCodec.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/CustomSplitJsonCodec.kt

- `EnhancedSplitManager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/split/EnhancedSplitManager.kt

- `GroupExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt

- `GroupMember.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupMember.kt

- `Expense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `GroupExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupExpenseDao.kt

- `SharedExpenseGroupsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt

- related tests:
  - `SharedExpenseManagerTest.kt`
  - `SharedExpenseBudgetOffsetEngineTest.kt`
  - `GroupTransactionCoordinatorTest.kt`