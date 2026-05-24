# Pipeline 2 deep recheck — commit `1e24ddd`

Commit reviewed:  
https://github.com/panospao7/Cost-agregator/commit/1e24dddefa34c91621bc6b99c3d8fc89b19e9509

Mode: **static GitHub review only**. I did **not** run Gradle/tests.

## Executive verdict

**Not ready to close Pipeline 2 yet.**

The recent commit correctly improves **P2-NEW-10** and partially improves **P2-NEW-12**, but Pipeline 2 still has open gaps:

1. Business/tax patch contract is still missing.
2. Receipt legacy create path still exists.
3. Group lifecycle coordinator/hard-delete policy still missing.
4. `SideEffectMode` legacy overload still exists.
5. `ExpenseDao` mutation restriction is only `WARNING`, not hard guard.
6. Bulk changed-field side effects exist, but several actions are still `Skipped(NOT_APPLICABLE)`.
7. Side-effect failures are still not clearly mirrored to `transaction_events.SIDE_EFFECT_FAILED`.
8. `addExpenseWithLink()` checks only `NotFound`, but does **not verify final ownership row fields**.

---

# Recent commit evaluation

## P2-NEW-10 — Group create-system-expense-and-link orphan risk

**Status: Mostly fixed.**

The commit adds `GroupExpenseAtomicRollback`, intended to be thrown inside `database.withTransaction` so Room rolls back the already-created expense if the later group link fails. The commit message explicitly describes this rollback intent. The code now throws on:

- linked expense already attached,
- group expense insert returning `<= 0`.

This is the right fix pattern because returning an error from `withTransaction` would commit.

Evidence:

- Commit description says the fix throws instead of returning `Error` for post-create failures.
- `createSystemExpenseAndLinkToGroup()` now throws `GroupExpenseAtomicRollback` after system expense creation when link checks fail.
- The method catches `GroupExpenseAtomicRollback` outside the transaction and converts it to public result.

Source:
- Commit page: https://github.com/panospao7/Cost-agregator/commit/1e24dddefa34c91621bc6b99c3d8fc89b19e9509
- Group coordinator: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

### Caveat

This still needs tests:

```text
create_system_expense_link_insert_failure_rolls_back_expense
create_system_expense_link_already_exists_rolls_back_expense
create_system_expense_source_link_failure_rolls_back_expense
create_system_expense_post_commit_actions_not_run_after_rollback
```

Do not mark closed without those.

---

## P2-NEW-11 — Group-created system expense missing `groupId`

**Status: Fixed.**

`CreateExpenseRequest` in `createSystemExpenseAndLinkToGroup()` now includes:

```kotlin
source = ExpenseSource.GROUP_EXPENSE
groupId = groupId
```

This should allow concrete group provenance instead of falling back to weak source-only provenance.

Evidence: `groupId = groupId` appears in the request construction near the group expense create path.

Source:
- Group coordinator: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

Required test:

```text
group_created_system_expense_has_GROUP_EXPENSE_source_link_with_groupId
```

---

## P2-NEW-12 — `addExpenseWithLink()` ownership update result not checked

**Status: Partial, not fully fixed.**

The recent commit now checks:

```kotlin
OwnershipUpdateResult.NotFound -> throw GroupExpenseAtomicRollback
OwnershipUpdateResult.Updated / NoOp -> proceed
```

This is better than before.

But it still does **not verify the final expense row** after `updateOwnershipDbOnlyV2()`.

Risk:

```text
OwnershipUpdateResult.NoOp can be accepted even if the row does not actually match:
- isSharedExpense = true
- isNotMine = false
- myShareAmount = currentUserShare
- owner/shared fields expected by group link
```

The original plan required reading the expense after mutation and asserting final ownership/share fields inside the same transaction.

Source:
- Group coordinator ownership check: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

### Required follow-up

Inside the same transaction, after `updateOwnershipDbOnlyV2()`:

```kotlin
val updatedExpense = expenseDao.getById(systemExpenseId)
    ?: throw GroupExpenseAtomicRollback(...)

if (!updatedExpense.isSharedExpense ||
    updatedExpense.isNotMine ||
    updatedExpense.myShareAmount != currentUserShare
) {
    throw GroupExpenseAtomicRollback(...)
}
```

Use approximate equality for `Double`.

Required tests:

```text
add_existing_expense_noop_but_wrong_ownership_rolls_back_group_link
add_existing_expense_updated_but_row_mismatch_rolls_back_group_link
add_existing_expense_link_insert_failure_rolls_back_ownership_update
```

---

# Broader Pipeline 2 status by issue

## Fixed / mostly fixed

| Issue | Status | Evidence |
|---|---:|---|
| P2-NEW-01 update validation | Mostly fixed | `TransactionValidator` exists and `updateExpense()` validates final state before update. |
| P2-NEW-02 write-barrier normalization | Mostly fixed | No direct `restoreMaintenanceMode.isWritesAllowed` found in coordinator. `checkWritesAllowed()` wraps `DatabaseWriteBarrier`. |
| P2-NEW-03 restore-blocked create diagnostic | Mostly fixed | `emitCreateBlockedDiagnosticBestEffort()` emits `AppPipeline.TRANSACTION`, `CREATE_EXPENSE`, `BLOCKED`. |
| P2-NEW-04 strict attempt dedupe key | Mostly fixed | `createAttemptDedupeKey()` uses strict external key for `STRICT_EXTERNAL_ID`. |
| P2-NEW-05 insert conflict resolver | Mostly fixed | `resolveExistingIdAfterInsertConflict()` exists and resolved conflicts become `CREATE_DUPLICATE_SKIPPED`. |
| P2-NEW-09 future-date policy | Fixed | `TransactionValidator` uses `TransactionDatePolicy`, not hardcoded `addDays(now, 1)`. |
| P2-NEW-10 group system-expense atomic rollback | Mostly fixed | New rollback exception used after create. |
| P2-NEW-11 group provenance | Fixed | `groupId = groupId` now passed. |
| P2-NEW-15 manual hook synthetic expense | Fixed | Manual repository fetches persisted row via `expenseDao.getById(id)` for hooks. |
| P2-NEW-17 source-link fallback | Mostly fixed | `LEGACY_SOURCE_ONLY` now requires explicit `SourceLinkFallbackPolicy.LEGACY_BACKFILL_ONLY`. |
| P2-NEW-18 debug snapshot audit | Mostly fixed | Debug snapshot emits diagnostic; delete/restore write aggregate audit events. |

Sources:
- Validator: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidator.kt
- Lifecycle coordinator: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- Source-link mapper: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt
- Manual repository: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
- Expense repository debug methods: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

---

# Still open / not ready

## 1. P2-NEW-06 / P2-P1-01 — Business/tax API still missing patch contract

**Status: Open.**

I found **no** `updateBusinessExpensePatch()` in `TransactionLifecycleCoordinator`.

That means the earlier issue remains unless implemented under another name:

```text
businessUsePercent / taxCategory / vatEligible accepted-but-ignored risk
```

Required fix:

```text
BusinessExpensePatch
BusinessExpenseUpdateResult
UnsupportedFields result
UPDATE_VALIDATION_FAILED for rejected fields
```

---

## 2. P2-NEW-16 — Receipt legacy `createExpenseFromReceipt()` still exists

**Status: Open / blocker.**

`ReceiptRepository.createExpenseFromReceipt()` is still present, deprecated with `DeprecationLevel.ERROR`, but still implemented.

It still builds a `CreateExpenseRequest` with:

```kotlin
source = ExpenseSource.RECEIPT_SCAN
```

but the shown request construction does **not** include:

```kotlin
scannedReceiptId = receiptId
```

It also still suppresses deprecation to call:

```kotlin
coordinator.createExpense(request)
```

Source:
- Receipt repository legacy method: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

This is risky for two reasons:

1. The legacy path should have been removed or guarded.
2. With stricter provenance validation, this path may now fail or produce weak/missing receipt provenance.

Required fix:

```text
delete createExpenseFromReceipt()
or replace body with ReceiptLifecycleCoordinator atomic path
add static guard: no production createExpenseFromReceipt
```

---

## 3. P2-NEW-13 / P2-06 — GroupLifecycleCoordinator still missing

**Status: Open.**

`GroupTransactionCoordinator` still has a file-level TODO to create `GroupLifecycleCoordinator`.

Hard delete still exists as:

```kotlin
permanentlyDeleteGroup(groupId): Boolean
```

and comment still says hard delete bypasses lifecycle coordinator and writes no audit events.

Source:
- Group coordinator TODO/hard-delete comments: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

Required fix:

```text
GroupLifecycleCoordinator
confirmPermanentDelete flag
block active groups
block outstanding balances
block current-user membership
GROUP_PERMANENTLY_DELETED event
```

---

## 4. P2-NEW-14 — Side-effect failure event contract still incomplete

**Status: Open / unproven.**

`PostCommitActionRunnerImpl` calls:

```kotlin
eventWriter.failed(...)
```

for failures, but I found no direct `SIDE_EFFECT_FAILED` transaction event writing in the runner.

Source:
- Post-commit runner: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerImpl.kt

If `eventWriter` is bound to a composite writer elsewhere, verify it. Otherwise this remains open.

Required check:

```bash
grep -R "SIDE_EFFECT_FAILED" app/src/main/java
grep -R "TransactionSideEffectFailureEventWriter" app/src/main/java
grep -R "CompositeSideEffectEventWriter" app/src/main/java
```

---

## 5. P2-NEW-19 / P2-07 — Bulk side effects have changed fields, but many actions are no-op/skipped

**Status: Partial.**

Good:

```kotlin
planBulkUpdated(..., changedFields: Set<BulkChangedField>)
```

exists.

Good:

- `BulkChangedField` exists.
- Planner conditionally adds budget/anomaly/cache/merchant/recurring aggregate actions.

But several bulk actions currently return:

```kotlin
SideEffectOutcome.Skipped(SideEffectSkipReason.NOT_APPLICABLE)
```

for anomaly/cache/merchant/recurring placeholders.

So this is **contract shape fixed**, but **real side-effect invalidation not fully implemented**.

Sources:
- Planner changed-field API and action list: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- `BulkChangedField`: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/BulkChangedField.kt

Required fix:

```text
replace NOT_APPLICABLE placeholders with real dirty markers / invalidators,
or explicitly document that current app has no such cache/model and tests assert skipped contract.
```

---

## 6. P2-NEW-20 / P2-P1-05 — `ExpenseDao` mutation guard is too weak

**Status: Partial.**

Good:

- `RestrictedExpenseDaoMutation` exists.
- Many DAO mutation methods are annotated.

But the annotation is:

```kotlin
RequiresOptIn.Level.WARNING
```

not:

```kotlin
RequiresOptIn.Level.ERROR
```

So this is not a hard compile guard.

Source:
- Restricted annotation: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
- Annotated DAO methods: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

Required fix:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
```

Also verify architecture tests exist:

```bash
grep -R "ExpenseDaoMutationAccessTest" app/src/test app/src/androidTest
```

---

## 7. P2-10 — `SideEffectMode` still exists as active compatibility overload

**Status: Partial/open.**

`TransactionLifecycleCoordinator` still imports `SideEffectMode` and has a deprecated overload:

```kotlin
createExpense(request, sideEffectMode)
```

It is only `DeprecationLevel.WARNING`, not `ERROR`.

Source:
- Lifecycle coordinator SideEffectMode overload: https://github.com/panospao7/Cost-agregator/blob/1e24dddefa34c91621bc6b99c3d8fc89b19e9509/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Required fix:

```text
DeprecationLevel.ERROR
or remove overload
add static guard: no production SideEffectMode usage
```

---

# Possible new regression introduced by stricter provenance

Because `CreateExpenseSourceLinkMapper` now only creates `LEGACY_SOURCE_ONLY` when explicit fallback policy is set, runtime creates with source-specific types must pass concrete source fields.

This is good architecturally.

But the still-existing `ReceiptRepository.createExpenseFromReceipt()` builds a `RECEIPT_SCAN` request without visible `scannedReceiptId = receiptId`.

If provenance requirements are enforced elsewhere, this legacy method may now fail. If not enforced, it creates no concrete receipt link.

So the provenance fix and the legacy receipt path now conflict. The right answer is to delete or route that legacy receipt method.

---

# Final status table

| Issue | Current status at `1e24ddd` |
|---|---:|
| P2-P1-01 / P2-NEW-06 business/tax API | **Open** |
| P2-P1-02 failed-create diagnostics | **Mostly fixed** |
| P2-P1-03 strict/conflict dedupe | **Mostly fixed** |
| P2-P1-04 debug audit | **Mostly fixed** |
| P2-P1-05 DAO mutation guard | **Partial** |
| P2-06 group hard-delete lifecycle | **Open** |
| P2-07 bulk side effects | **Partial** |
| P2-09 delete/FK/orphan tests | **Unknown/test gap** |
| P2-10 deferred side-effect contract | **Partial** |
| P2-11 duplicate visibility | **Mostly fixed, test required** |
| P2-12 duplicate budget checks | **Mostly fixed, test required** |
| P2-NEW-01 update validation | **Mostly fixed** |
| P2-NEW-02 write barrier | **Mostly fixed** |
| P2-NEW-03 restore-blocked diagnostic | **Mostly fixed** |
| P2-NEW-04 strict attempt key | **Mostly fixed** |
| P2-NEW-05 insert conflict resolver | **Mostly fixed** |
| P2-NEW-07 category reassignment | **Likely fixed; verify coordinator callsite** |
| P2-NEW-08 review merchant key parity | **Likely fixed; approval path needs targeted test** |
| P2-NEW-09 future-date policy | **Fixed** |
| P2-NEW-10 group create/link orphan | **Mostly fixed** |
| P2-NEW-11 group provenance | **Fixed** |
| P2-NEW-12 ownership result check | **Partial** |
| P2-NEW-13 group lifecycle coordinator | **Open** |
| P2-NEW-14 side-effect failed transaction event | **Open/unproven** |
| P2-NEW-15 manual persisted hook | **Fixed** |
| P2-NEW-16 receipt legacy path | **Open/blocker** |
| P2-NEW-17 source-link fallback | **Mostly fixed** |
| P2-NEW-18 debug snapshot diagnostic | **Mostly fixed** |
| P2-NEW-19 bulk changed fields | **Partial** |
| P2-NEW-20 static guard coverage | **Partial** |

---

# Recommended next PRs before calling Pipeline 2 clean

## PR 1 — Finish group atomicity verification

Fix:

- P2-NEW-12 residual.

Tasks:

```text
- After updateOwnershipDbOnlyV2(), reload expense inside transaction.
- Verify isSharedExpense/isNotMine/myShareAmount/current-user share.
- Throw GroupExpenseAtomicRollback on mismatch.
- Add rollback tests.
```

## PR 2 — Delete/replace receipt legacy path

Fix:

- P2-NEW-16.
- provenance conflict from P2-NEW-17.

Tasks:

```text
- Delete ReceiptRepository.createExpenseFromReceipt()
- or delegate to ReceiptLifecycleCoordinator atomic path.
- Add static guard.
```

## PR 3 — Business/tax patch contract

Fix:

- P2-P1-01 / P2-NEW-06.

Tasks:

```text
- Add BusinessExpensePatch.
- Add BusinessExpenseUpdateResult.
- Reject unsupported fields.
- No silent drops.
```

## PR 4 — GroupLifecycleCoordinator

Fix:

- P2-06 / P2-NEW-13.

Tasks:

```text
- Move hard delete policy out of low-level coordinator.
- Require confirm flag.
- Block active/outstanding/current-user cases.
- Write lifecycle events.
```

## PR 5 — Harden static guards

Fix:

- P2-P1-05 / P2-NEW-20 / P2-10.

Tasks:

```text
- RestrictedExpenseDaoMutation Level.ERROR.
- Add ExpenseDaoMutationAccessTest.
- Remove or ERROR-deprecate SideEffectMode overload.
```

## PR 6 — Side-effect failure transaction-event mirror

Fix:

- P2-NEW-14.

Tasks:

```text
- Add TransactionSideEffectFailureEventWriter or confirm composite writer.
- Write SIDE_EFFECT_FAILED for transaction/expense failures.
```

## PR 7 — Bulk side effects real invalidation or explicit skipped contract

Fix:

- P2-07 / P2-NEW-19.

Tasks:

```text
- Replace NOT_APPLICABLE placeholders with real dirty markers/invalidation.
- Or document/test no-op contract if no cache/model exists.
```

---

# Bottom line

The recent commit is **directionally correct** and fixes the most dangerous orphan-system-expense case.

But Pipeline 2 is **not clean yet**.

I would mark current state as:

```text
Pipeline 2: improved, core create/update much stronger, group atomicity improved,
but still NOT READY for closure.
```

Main blockers:

```text
1. Receipt legacy create path still exists.
2. Business/tax silent-drop issue still open.
3. Group hard-delete lifecycle still open.
4. Ownership NoOp is trusted without row verification.
5. Static guards are warning-level, not hard.
6. SideEffectMode still exists.
7. Side-effect failure event contract incomplete.
```

Do not close Pipeline 2 until those are resolved and the static/golden tests pass.