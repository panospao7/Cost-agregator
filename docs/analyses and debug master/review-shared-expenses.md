# Shared Expenses / Splits / Reimbursements – Cross-Check Review

**Source analysis**: `shared-expenses-analysis.md` (942 lines, 15 issues)  
**Target codebase**: `app/src/main/java/com/yourname/expensetracker`  
**Review date**: 2026-05-02  

---

## VERDICT: FAIL

Of the 15 issues from the analysis:
- **2 RESOLVED** (issues 8, 9)  
- **1 PARTIALLY RESOLVED** (issue 1)  
- **12 STILL PRESENT** (issues 2–7, 10–15)  
- **4 NEW issues found** (issues 16–19)

---

## Issue-by-issue cross-check

### [ISSUE-1] Standalone group expenses drop custom split payloads → PARTIALLY RESOLVED

**Analysis claim**: `SharedExpenseManager.addExpense()` → adapter → `addExpenseToGroup()` drops `customSplitsJson`.

**Current state**:

- `SharedExpenseManager.addExpense()` now takes `expenseId: Long` (non‑nullable Kotlin type, line 130). The domain manager always goes through the `addExpenseWithLink` path.  
  ✅ The **SharedExpenseManager entry point is now safe**.

- `SharedExpenseDataPortAdapter.addExpense()` (line 68–99) still branches on `expense.expenseId != null`. When `expenseId == null`, it calls `addExpenseToGroup()`, which does **not** accept or persist `customSplitsJson`.  
  ❌ The adapter path remains vulnerable.

- `GroupTransactionCoordinator.addExpenseToGroup()` (line 168–232) still hardcodes `customSplitsJson = null` at line 198 and creates `GroupExpense` at line 208–217 **without the field**.

- If any caller constructs a `SharedGroupExpense` with `expenseId = null` and `splitType = CUSTOM_AMOUNT/CUSTOM_PERCENT/UNEQUAL`, the custom split data is **silently dropped**.

**Severity**: HIGH (if standalone expenses are used) / LOW (current main flow is protected)  
**Verdict**: PARTIALLY RESOLVED. The main ViewModel flow is safe, but the coordinator method and adapter null-path still have the bug.

---

### [ISSUE-2] `addExpenseToGroup()` is not transactional → STILL PRESENT

**Analysis claim**: The coordinator method performs validation + insert outside `database.withTransaction`.

**Current state** (data coordinator, lines 168–232):

```kotlin
override suspend fun addExpenseToGroup(...): GroupExpenseCreationResult = withContext(ioDispatcher) {
    try {
        // 1. get group (not in transaction)
        val group = groupDao.getById(groupId)               // line 179
        // 2. get members (not in transaction)
        val members = memberDao.getAllForGroup(groupId)      // line 185
        // 3. validate (not in transaction)
        validateExpenseParticipants(...)                     // line 190
        // 4. insert group expense (not in transaction)
        val expenseId = groupExpenseDao.insert(expense)      // line 219
    }
}
```

No `database.withTransaction` wrapper. This contrasts with `addExpenseWithLink()` (line 252) and `createSystemExpenseAndLinkToGroup()` (line 426), both of which **do** use `database.withTransaction`.

**TOCTOU risks remain**: group archiving, member deletion, payer membership change between validation and insert.

**Verdict**: STILL PRESENT.

---

### [ISSUE-3] Archived groups disappear from budget-offset calculations → STILL PRESENT

**Analysis claim**: Budget engine uses `getActiveGroupsWithDetails()` → archived groups excluded, but linked expenses still have `isSharedExpense = true` → they vanish from both shared and personal spend.

**Current state**:

```kotlin
// SharedExpenseBudgetOffsetEngine.kt line 58
val activeGroups = groupsRepository.getActiveGroupsWithDetails()

// GroupsRepositoryImpl.kt line 43
val groups = groupDao.getActive()  // isActive = 1 only
```

- Personal spend filter (line 78–83) excludes `isSharedExpense` expenses.
- Shared spend only uses in-scope group expenses from **active** groups.
- **Archived groups**: linked expenses are excluded from personal (they're shared) but their group is not in `activeGroups` → **they vanish from effective budget spend**.

**Note on currency**: The engine now uses `currencyConverter.convertMultiple()` (lines 87, 118, 127), which is an improvement over raw summing. But the active‑groups‑only filter remains.

**Verdict**: STILL PRESENT.

---

### [ISSUE-4] Hard delete / permanent delete leaves linked expenses semantically orphaned → STILL PRESENT

**Analysis claim**: Deleting a group leaves linked `Expense` rows with `isSharedExpense = true` but no group record.

**Current state**:

- `GroupTransactionCoordinator.permanentlyDeleteGroup()` (line 364) → `deleteGroupAtomic()` (line 581):
  ```kotlin
  database.withTransaction {
      groupExpenseDao.deleteAllForGroup(groupId)  // deletes group_expenses
      memberDao.deleteAllForGroup(groupId)         // deletes members
      groupDao.delete(it)                          // deletes group
  }
  ```
  **No cleanup of linked `Expense` rows.**

- `SharedExpenseDataPortAdapter.deleteGroup()` (line 133) → `groupDao.delete(group.toEntity())` — same result via FK cascade.

- The FK on `GroupExpense.expenseId` has `onDelete = CASCADE`, but that fires **when the Expense is deleted**, not when the GroupExpense is deleted. So the cascade goes the wrong direction.

- After hard delete: linked Expenses retain `isSharedExpense = true`, `myShareAmount != null`, but there is no longer a group to explain them.

**Verdict**: STILL PRESENT.

---

### [ISSUE-5] Existing-expense linking uses wrong date → STILL PRESENT

**Analysis claim**: Linking an existing expense defaults group expense date to `now()` rather than the original expense date.

**Current state** (`AddGroupExpenseUseCase.invoke()`, line 34):

```kotlin
val resolvedDate = date ?: timeProvider.now()
```

If the caller does not pass an explicit date, the group expense gets creation time, not the linked system expense's `date`.

**Note**: The ViewModel now uses `invokeAtomic` (which creates a new system expense atomically), so the UI path is safe. But the linking-use-case API itself is still vulnerable when called directly.

**Verdict**: STILL PRESENT.

---

### [ISSUE-6] Current-user member is assumed but not guaranteed → STILL PRESENT

**Analysis claim**: No enforcement that every group has exactly one current-user member.

**Current state**:

- `validateSingleCurrentUser()` (data coordinator line 66) only checks `size > 1`, not `size == 0`.
- `SharedExpenseManager.createGroup()` (line 66): `isCurrentUser = name.equals(currentUserName, ignoreCase = true)` — if no member name matches `"Me"`, zero current users are created.
- `resolveCurrentUserShare()` (line 617) uses `members.singleOrNull { it.isCurrentUser }` — returns `null` if 0 or >1 current users.
- DB-level: partial unique index `idx_group_members_currentUserGroupKey` exists, but initial creation does **not** set `currentUserGroupKey` (see **NEW-1** below).

**Verdict**: STILL PRESENT. At-most-one is enforced; at-least-one is not.

---

### [ISSUE-7] `paidById` same-group rule is not DB-enforced → STILL PRESENT

**Analysis claim**: No database rule ensures `paidById` belongs to the same `groupId`.

**Current state** (`GroupExpense.kt` line 30–35):

```kotlin
ForeignKey(
    entity = GroupMember::class,
    parentColumns = ["id"],
    childColumns = ["paidById"],
    onDelete = ForeignKey.RESTRICT
)
```

The FK ensures `paidById` references **some** `GroupMember.id`, but does **not** verify the member belongs to the same `groupId`. Coordinator validates this in code, but direct DAO inserts can bypass.

**Verdict**: STILL PRESENT.

---

### [ISSUE-8] One system expense can be linked more than once → RESOLVED

**Analysis claim**: `GroupExpense.expenseId` had a non-unique index, allowing duplicate links.

**Current state** (`GroupExpense.kt` line 39):

```kotlin
Index(value = ["expenseId"], unique = true)
```

✅ The index is now `unique = true`. SQLite allows multiple NULL values, so standalone expenses (`expenseId = null`) are still permitted. The coordinator also checks `getGroupExpenseForExpense()` before inserting.

**Verdict**: RESOLVED.

---

### [ISSUE-9] Multi-currency raw-summed in shared budget offset → RESOLVED

**Analysis claim**: The engine summed personal and shared spend without currency conversion.

**Current state** (`SharedExpenseBudgetOffsetEngine.kt`):

```kotlin
// line 87–88: personal spend converted
val personalResult = currencyConverter.convertMultiple(personalPairs, homeCurrency)
val totalPersonalSpend = personalResult.total

// line 117–120: shared spend converted
val sharedResult = if (sharedSpendPairs.isNotEmpty()) {
    currencyConverter.convertMultiple(sharedSpendPairs, homeCurrency)
} else null
val totalSharedSpend = sharedResult?.total ?: 0.0

// line 125–128: reimbursed converted
val reimbursedResult = if (reimbursedPairs.isNotEmpty()) {
    currencyConverter.convertMultiple(reimbursedPairs, homeCurrency)
} else null
val totalReimbursed = reimbursedResult?.total ?: 0.0
```

✅ All three components (personal, shared, reimbursed) now go through `currencyConverter.convertMultiple()` before aggregation. Failed conversions are logged and dropped.

**Verdict**: RESOLVED.

---

### [ISSUE-10] Custom split semantics force all current members → STILL PRESENT

**Analysis claim**: `CustomSplitParser` requires `parsed.keys == groupMemberIds`.

**Current state** (`CustomSplitParser.kt` line 122):

```kotlin
if (parsed.keys != groupMemberIds) {
    return CustomSplitParseResult.Invalid(
        reason = "Custom split entries must include every member exactly once",
        ...
    )
}
```

Still enforces all-or-nothing. No support for subset splits (e.g., "only Alice and Bob split this one").

**Verdict**: STILL PRESENT.

---

### [ISSUE-11] Invalid custom split data silently falls back to equal split → STILL PRESENT

**Analysis claim**: `SplitCalculator` falls back to equal split for malformed custom data.

**Current state** (`SplitCalculator.kt`):

Lines 136–143, 159–166, 182–189 all call `fallbackToEqualForInvalidLegacyData()`:

```kotlin
private fun fallbackToEqualForInvalidLegacyData(
    expense: GroupExpense,
    members: List<GroupMember>,
    reason: String
): Map<Long, Double> {
    Timber.w("Invalid legacy custom split data... Falling back to equal split...")
    return calculateEqualSplit(expense.totalAmount, members)
}
```

A malformed custom split becomes a plausible-looking equal split. The warning is logged but not surfaced to the user.

**Verdict**: STILL PRESENT.

---

### [ISSUE-12] Linked `Expense.myShareAmount` can drift from group split data → STILL PRESENT

**Analysis claim**: No recompute path when group split details change.

**Current state**:

- `normalizeLinkedSystemExpense()` (line 595–603) sets `myShareAmount` at creation time only.
- No code in `GroupTransactionCoordinator`, `GroupsRepository`, or any use case that recomputes `Expense.myShareAmount` when `customSplitsJson` or members are updated.
- The `GroupExpense` entity has its own `myShareAmount` field (line 62), but the linked `Expense` is not updated.

**Verdict**: STILL PRESENT.

---

### [ISSUE-13] Enhanced split item assignment is not transactional and lacks validation → STILL PRESENT

**Analysis claim**: `assignItemsToParticipants()` does delete + insert without transaction and without validation.

**Current state** (`EnhancedSplitManager.kt` lines 191–211):

```kotlin
suspend fun assignItemsToParticipants(
    expenseId: Long,
    assignments: List<ItemAssignment>
) {
    splitItemAssignmentDao.deleteAllForExpense(expenseId)  // delete
    val entities = assignments.mapIndexed { ... }           // build
    splitItemAssignmentDao.insertAssignments(entities)      // insert
}
```

- **No `@Transaction` annotation or `database.withTransaction` wrapper**.
- **No validation**: amounts can be negative, NaN, infinite; total not checked against expense/receipt total; participant names can be blank.

**Verdict**: STILL PRESENT.

---

### [ISSUE-14] Split templates are weakly validated → STILL PRESENT

**Analysis claim**: Template creation stores shares JSON without validation.

**Current state** (`EnhancedSplitManager.kt` lines 136–151):

```kotlin
suspend fun createTemplate(
    name: String,
    totalSplits: Int,
    splitType: SplitTemplate.SplitType,
    shares: List<SplitShare>
): Long {
    val template = SplitTemplate(
        name = name,
        totalSplits = totalSplits,
        splitType = splitType,
        shares = gson.toJson(shares),  // no validation
        ...
    )
    return splitTemplateDao.insertTemplate(template)
}
```

No checks on:
- Percentage sum = 100%
- Amount sum = expected total
- Non-negative, finite values
- Participant count > 0

**Verdict**: STILL PRESENT.

---

### [ISSUE-15] Two settlement calculation paths → STILL PRESENT

**Analysis claim**: `SplitCalculator.simplifyBalances()` and `SettlementCalculator.calculateSettlements()` coexist and can produce different results.

**Current state**:

- `SplitCalculator.simplifyBalances()` (line 358–404): greedy algorithm, used by `SharedExpenseGroupsScreen` (line 431).
- `SettlementCalculator.calculateSettlements()` (line 41–78): DFS/backtracking optimal solver with budget limits.

Both still exist and are used from different call sites. They can produce different settlement plans for the same balances.

**Verdict**: STILL PRESENT.

---

## New issues found during review

### [ISSUE-16] [NEW] [MAJOR] `currentUserGroupKey` CHECK constraint is a no‑op for NULL values

**Where**:
- `AppDatabase.kt` migration (lines 6339–6343)
- `GroupMember.kt` entity (line 43)
- `GroupMemberDao.kt` (line 100–101)
- `GroupTransactionCoordinator.kt` (line 78–101)

**Problem**:

The CHECK constraint:

```sql
CHECK (
    (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
    OR
    (isCurrentUser = 1 AND currentUserGroupKey = groupId)
)
```

In SQLite, `NULL = groupId` evaluates to **NULL** (not FALSE). The CHECK constraint passes if the expression is not FALSE. When `isCurrentUser = 1` and `currentUserGroupKey IS NULL`:

- Clause 1: `FALSE AND TRUE` → FALSE
- Clause 2: `TRUE AND NULL` → NULL
- Overall: `FALSE OR NULL` → NULL → **CHECK passes**

The CHECK does **not** reject a row with `isCurrentUser = 1` and `currentUserGroupKey = NULL`.

Meanwhile, `currentUserGroupKey` is **never set** during initial group creation:
- `SharedExpenseManager.createGroup()` does not set it.
- `GroupTransactionCoordinator.createGroupWithMembers()` / `createGroupWithMembersAtomic()` do not set it.
- Only `GroupMemberDao.markAsCurrentUser()` (line 100–101) sets it, and that is only called via `setCurrentUser()`, which is used for **post-creation promotion**, not initial setup.

**Impact**: The DB-level partial unique index (`currentUserGroupKey` UNIQUE) allows multiple NULL values (SQLite standard behavior). Combined with the no‑op CHECK, the materialized-key enforcement is **completely ineffective** for the initial creation path.

**Fix**: 
1. Set `currentUserGroupKey = groupId` at creation time in the coordinator when `isCurrentUser = true`.
2. Fix the CHECK to be: `isCurrentUser = 0 OR (isCurrentUser = 1 AND currentUserGroupKey IS NOT NULL AND currentUserGroupKey = groupId)` (though `IS NOT NULL` alone would suffice since the UNIQUE index handles duplicates).

---

### [ISSUE-17] [NEW] [MAJOR] `addExpenseToGroup()` accepts non‑EQUAL split types without custom-split validation or storage

**Where**:
- `GroupTransactionCoordinator.addExpenseToGroup()` (lines 168–232)

**Problem**: The method signature accepts `splitType: SplitType` but no `customSplitsJson`. Inside:

- `validateCustomSplitPayloadFormat()` is **not called**.
- `validateExpenseParticipants()` receives `customSplitsJson = null` (line 198) and only validates EQUAL splits (line 84–96 of SplitCalculator).
- The `GroupExpense` is created without `customSplitsJson` (line 208–217).

If a caller passes `splitType = CUSTOM_AMOUNT` to `addExpenseToGroup()`:
1. No validation error — the method proceeds.
2. A row is stored with `splitType = CUSTOM_AMOUNT` and `customSplitsJson = NULL`.
3. Later, `SplitCalculator` falls back to equal split (Issue 11).

**Impact**: This silently corrupts financial data — a user creating a custom-split standalone expense gets an equal-split result.

**Fix**: Either:
- Add `customSplitsJson` parameter to `addExpenseToGroup()` and validate it, or
- Reject non‑EQUAL split types in `addExpenseToGroup()` and require callers to use `addExpenseWithLink()` or `createSystemExpenseAndLinkToGroup()`.

---

### [ISSUE-18] [NEW] [MINOR] `SharedExpenseDataPortAdapter.deleteGroup()` bypasses the coordinator

**Where**:
- `SharedExpenseDataPortAdapter.deleteGroup()` (lines 133–135)

**Problem**: The adapter calls `groupDao.delete(group.toEntity())` directly, bypassing `GroupTransactionCoordinator.deleteGroupAtomic()` and `permanentlyDeleteGroup()`. While this works today (FK cascade handles member/expense cleanup), any **future enhancement** to the atomic delete path (e.g., cleaning up linked `Expense` rows, triggering events) would **not** apply to this path.

**Fix**: Route through the coordinator:
```kotlin
override suspend fun deleteGroup(group: SharedExpenseGroup) {
    transactionCoordinator.permanentlyDeleteGroup(group.id)
}
```

Note: this would also need to coordinate with `deleteGroup` (soft delete / archive). Currently `deleteGroup` in the adapter is **only** reached via `SharedExpenseManager.deleteGroup()` (line 422–424), which is separate from the ViewModel's soft-delete flow (`DeleteGroupUseCase` → archive).

---

### [ISSUE-19] [NEW] [MINOR] `DeleteGroupUseCase` performs soft delete but `SharedExpenseManager.deleteGroup()` performs hard delete — inconsistent semantics

**Where**:
- `DeleteGroupUseCase.invoke()` (calls `groupsRepository.deleteGroup()` → `coordinator.deleteGroup()` → `groupDao.archiveGroup()` — soft)
- `SharedExpenseManager.deleteGroup()` (calls `sharedExpenseDataPort.deleteGroup()` → `groupDao.delete()` — hard)

**Problem**: Two different "delete group" entry points do fundamentally different things:
- The ViewModel's `deleteGroup()` → `DeleteGroupUseCase` → archives (soft delete).
- The domain manager's `deleteGroup()` → adapter's `deleteGroup()` → permanently deletes from DB.

A caller choosing the wrong API can accidentally permanently delete data.

**Fix**: Rename or consolidate. E.g.:
- `deleteGroup` → soft delete / archive
- `permanentlyDeleteGroup` → hard delete
- Ensure all public APIs use the same naming and semantics.

---

## Coverage

- **Requirements met**: PARTIALLY. The linked-expense creation path is solid and atomic. Currency conversion is now properly applied in budget-offset calculations. The duplicate expense link is prevented at the DB level. However, many architectural weaknesses remain — particularly around standalone expenses, archive/delete semantics, and missing recompute paths.
- **Testing adequate**: UNKNOWN. The analysis mentions tests exist for the primary coordinator flows, budget offset, balances, and split parsing. However, the analysis also lists 15 specific regression tests that are needed. Without checking the current test suite, it's unclear how many of those have been added.

---

## Recommended fix priority (updated)

Based on current codebase state:

1. **PR 1 — Fix `addExpenseToGroup()`**: Add `customSplitsJson` parameter + transaction wrapper + validation. This closes Issues 1, 2, and 17 together.
2. **PR 2 — Fix archive/delete financial semantics** (Issues 3, 4, 18, 19): Archived groups should count historically; hard delete must clean up or unlink `Expense` rows; unify delete paths.
3. **PR 3 — Fix date for existing-expense linking** (Issue 5): Default to the original expense's date.
4. **PR 4 — Enforce current-user invariant** (Issues 6, 16): Set `currentUserGroupKey` at creation; add at-least-one validation.
5. **PR 5 — DB invariants** (Issues 7, 8): Issue 8 is already resolved. Add cross-group `paidById` guard.
6. **PR 6 — Address remaining design weaknesses** (Issues 10–15): Custom split participant subsets, strict-vs-legacy fallback, `myShareAmount` recompute, transactional item assignment, template validation, settlement path unification.
