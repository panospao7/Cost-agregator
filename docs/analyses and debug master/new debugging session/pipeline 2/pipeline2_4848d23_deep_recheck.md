# Pipeline 2 deep recheck — commit `4848d23`

Reviewed commit: https://github.com/panospao7/Cost-agregator/commit/4848d23522fe4ae69210e0e1f382693454ba583f

Mode: static GitHub/code review only. I did **not** run Gradle/tests.

## Executive verdict

Pipeline 2 is **not fully clean yet**, but many previous blockers are now fixed or mostly fixed.

The latest commit itself is small: it adds the Hilt binding for `TransactionDatePolicy -> DefaultTransactionDatePolicy` and fixes `ExpenseDaoMutationAccessTest` stream/sequence handling. The binding is good and likely fixes DI/test compile issues.

However, after checking the current code at `4848d23`, I still see several open or partial items:

```text
Critical/important remaining:
1. GroupLifecycleCoordinator exists but hard-delete policy is still incomplete.
2. GroupLifecycleCoordinator appears to dispatch side effects inside DB transactions.
3. Source-link requirements are only warned, not enforced.
4. Bulk changed-field model exists, but coordinator callsites still call default UNKNOWN.
5. ExpenseDao mutation guard is WARNING, not ERROR, and architecture test accepts WARNING.
6. Receipt legacy create path is disabled but still present; static guard not confirmed.
7. Delete/FK/orphan behavior remains mostly a test gap.
```

So current status:

```text
Pipeline 2: improved and close, but NOT ready for final closure.
```

---

# What looks fixed / much improved

## P2-P1-01 / P2-NEW-06 — Business/tax API

**Status: mostly fixed.**

`TransactionLifecycleCoordinator` now has `updateBusinessExpensePatch(...)`, `BusinessExpensePatch`, and `BusinessExpenseUpdateResult`. Unsupported fields are rejected with `UPDATE_VALIDATION_FAILED`, and supported fields persist:

- `isBusinessExpense`
- `requiresReceipt`
- `businessPurpose`
- `businessCategory`
- `businessProject`

Evidence:
- `updateBusinessExpensePatch()` exists and rejects unsupported fields.
- Legacy `updateBusinessFlags()` delegates to patch API.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Remaining caveat:

- `ExpenseRepository` still has stale comments saying business/tax updates are “not yet implemented.” Not a runtime bug, but update the docs/comments to avoid future confusion.

---

## P2-NEW-01 — update validation

**Status: mostly fixed.**

`updateExpense()` now validates final state using `TransactionValidator.validateFinalExpenseState(...)` before persisting. `updateTypeAndTransferDetails()` and transfer update paths also appear to validate final transfer state.

Evidence:
- Final validation happens before `expenseDao.update(finalExpense)`.
- Invalid final state writes `UPDATE_VALIDATION_FAILED`.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Remaining caveat:

- `updateExpense()` uses the caller-provided full `Expense` row. That means fields like `createdAt`/other non-edit fields can still be overwritten if caller passes stale/bad data. This is less severe than the original validation bug, but worth testing.

Required final tests:

```text
update_invalid_amount_does_not_mutate
update_invalid_transfer_does_not_mutate
update_invalid_state_writes_UPDATE_VALIDATION_FAILED
update_valid_state_writes_UPDATED
update_preserves_createdAt_or_has_explicit_contract
```

---

## P2-NEW-02 — write barrier

**Status: mostly fixed.**

The coordinator uses `checkWritesAllowed(...)`, wrapping `DatabaseWriteBarrier`, instead of direct `restoreMaintenanceMode.isWritesAllowed()`.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Required final static test:

```text
TransactionLifecycleCoordinator.kt contains no restoreMaintenanceMode.isWritesAllowed
```

---

## P2-NEW-03 — restore-blocked create diagnostic

**Status: mostly fixed.**

`emitCreateBlockedDiagnosticBestEffort(...)` exists and emits:

```text
pipeline = TRANSACTION
stage = CREATE_EXPENSE
outcome = BLOCKED
severity = WARNING
reason = RESTORE_BLOCKED or WRITE_BARRIER_DENIED
```

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Required final tests:

```text
restore_blocked_create_emits_diagnostic
restore_blocked_create_does_not_insert_expense
restore_blocked_create_does_not_write_CREATE_ATTEMPTED
diagnostic_metadata_is_privacy_safe
```

---

## P2-NEW-04 / P2-NEW-05 — strict dedupe and insert conflict resolver

**Status: mostly fixed.**

Good:

- `strictExternalDedupeKey(...)` exists.
- `createAttemptDedupeKey(...)` uses strict key for `STRICT_EXTERNAL_ID`.
- `resolveExistingIdAfterInsertConflict(...)` exists.
- Resolved conflicts become `CREATE_DUPLICATE_SKIPPED`; unresolved conflicts become `CREATE_INSERT_CONFLICT`.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Required tests remain:

```text
strict_external_attempt_key_equals_persisted_key
strict_external_retry_returns_existing_id
standard_insert_conflict_resolves_existing_id
bulk_insert_conflict_resolves_existing_id
unresolved_conflict_only_then_writes_CREATE_INSERT_CONFLICT
```

---

## P2-NEW-07 — category-to-category reassignment

**Status: mostly fixed for atomicity, partial for changed-field side effects.**

Good:

- `ExpenseDao.updateCategoryForCategory(...)` exists.
- `bulkUpdateCategory(categoryId, newCategoryId)` uses one SQL update.
- Writes one `BULK_UPDATED` event in the same transaction.

Sources:
- DAO: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- Coordinator: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

Remaining issue:

`bulkUpdateCategory(categoryId, newCategoryId)` still calls:

```kotlin
dispatchBulkPostCommitSideEffects(source, affectedCount)
```

which calls:

```kotlin
planner.planBulkUpdated(source, affectedCount, null)
```

So it does **not** pass:

```kotlin
changedFields = setOf(BulkChangedField.CATEGORY)
```

That means the changed-field model exists, but this callsite is still using `UNKNOWN`.

Required fix:

```kotlin
planner.planBulkUpdated(
    source = source,
    affectedCount = affectedCount,
    correlationId = correlationId,
    changedFields = setOf(BulkChangedField.CATEGORY)
)
```

---

## P2-NEW-08 — review merchant key parity

**Status: mostly fixed.**

Good:

- `ReviewQueueRepository.approveReview()` no longer uses `normalizedMerchantForKeys`.
- It resolves currency once and passes the resolved merchant/currency to `CreateExpenseRequest`.
- Coordinator owns persisted merchant key/dedupe key.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

Remaining tests needed:

```text
approve_review_does_not_pre_normalize_merchant
approve_review_uses_resolvedCurrency
approve_review_same_merchant_as_auto_accept_same_merchantKey
```

---

## P2-NEW-09 — future-date policy

**Status: fixed.**

Latest commit adds the Hilt binding:

```kotlin
@Binds
@Singleton
abstract fun bindTransactionDatePolicy(
    impl: DefaultTransactionDatePolicy
): TransactionDatePolicy
```

Source:
- Commit diff: https://github.com/panospao7/Cost-agregator/commit/4848d23522fe4ae69210e0e1f382693454ba583f
- DiagnosticsModule: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt

This likely fixes the DI failure for `TransactionValidator`.

---

## P2-NEW-10 — group create/link orphan prevention

**Status: mostly fixed.**

Good:

- `GroupExpenseAtomicRollback` exists.
- `createSystemExpenseAndLinkToGroup()` uses DB-only create.
- If link already exists or link insert returns `<= 0`, it throws rollback signal after system expense creation.
- Catches rollback signal outside and returns public result.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

Required tests:

```text
create_system_expense_link_insert_failure_rolls_back_expense
create_system_expense_link_already_exists_rolls_back_expense
create_system_expense_success_runs_actions_after_commit
```

---

## P2-NEW-11 — group provenance

**Status: fixed.**

`CreateExpenseRequest` in `createSystemExpenseAndLinkToGroup()` now passes:

```kotlin
source = ExpenseSource.GROUP_EXPENSE
groupId = groupId
```

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

Required test:

```text
group_created_system_expense_has_GROUP_source_link_with_groupId
```

---

## P2-NEW-12 — addExpenseWithLink ownership result check

**Status: mostly fixed, but one caveat.**

Good:

- `addExpenseWithLink()` now handles `OwnershipUpdateResult.NotFound` as rollback.
- It reloads the expense row and verifies:
  - `isSharedExpense == true`
  - `isNotMine == false`
  - `myShareAmount` approximately matches current user share.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

Caveat:

The check does **not** verify all potentially relevant ownership fields:

```text
sharedWithName
ownerName
mySharePercentage
```

This may be acceptable if your group semantics only depend on `isSharedExpense`, `isNotMine`, and `myShareAmount`. If not, add checks.

Required final tests:

```text
noop_but_wrong_ownership_rolls_back_group_link
updated_but_wrong_myShareAmount_rolls_back_group_link
link_insert_failure_rolls_back_ownership_update
```

---

## P2-NEW-14 — side-effect failed transaction event

**Status: mostly fixed.**

Good:

- `TransactionSideEffectFailureEventWriter` exists.
- It writes `LifecycleEventType.SIDE_EFFECT_FAILED`.
- `CompositeSideEffectEventWriter` calls both diagnostic writer and transaction-failure writer.
- `DiagnosticsModule` binds `SideEffectEventWriter` to `CompositeSideEffectEventWriter`.

Sources:
- Transaction writer: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/TransactionSideEffectFailureEventWriter.kt
- Composite writer: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/sideeffect/CompositeSideEffectEventWriter.kt
- DI: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt

Caveat:

`reason = reason.take(200)` can leak raw data if side-effect failure reasons ever include merchant/notes/source payloads. Prefer controlled reason codes or sanitized messages.

Required tests:

```text
side_effect_failed_writes_SIDE_EFFECT_FAILED
side_effect_completed_does_not_write_SIDE_EFFECT_FAILED
failure_metadata_is_privacy_safe
```

---

## P2-NEW-15 — manual persisted hook

**Status: fixed, but regression tests still needed.**

Earlier review found this fixed. I did not re-open `ManualExpenseRepository` this pass due tool limit, but no new evidence suggests regression.

Required tests:

```text
manual_recommendation_uses_persisted_expense
manual_recommendation_sees_baseAmount_exchangeRate_merchantKey_dedupeKey
duplicate_manual_create_does_not_generate_recommendations
```

---

## P2-NEW-16 — receipt legacy path

**Status: mostly guarded, not ideal.**

Good:

- `createExpenseFromReceipt()` is `DeprecationLevel.ERROR`.
- Body no longer performs non-atomic create/link/categorization.
- It now returns an error saying the path is permanently disabled.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

Remaining caveat:

The method still exists in production source. If your final standard is “no production legacy path,” this is still not clean. If your standard is “impossible to accidentally use,” this is probably acceptable because it is ERROR-deprecated and returns error.

Required static guard:

```text
no production caller may reference createExpenseFromReceipt
```

Better final cleanup:

```text
delete method entirely once all callers/tests are migrated.
```

---

## P2-NEW-17 — source-link fallback

**Status: partial.**

Good:

- `SourceLinkFallbackPolicy` exists.
- `CreateExpenseSourceLinkMapper` only creates `LEGACY_SOURCE_ONLY` when fallback policy is explicitly `LEGACY_BACKFILL_ONLY`.
- `CreateExpenseSourceLinkRequirements` exists.

Sources:
- Mapper: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt
- Requirements: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt
- Request: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

Remaining problem:

`TransactionLifecycleCoordinator` only logs a warning when required source provenance is missing:

```kotlin
Timber.w("Missing source provenance fields...")
```

It does **not** fail validation.

That means runtime creates can still succeed with no source link if a source-specific caller forgets its ID field. This is weaker than the intended invariant:

```text
Every non-manual runtime create must carry concrete source provenance.
```

Required fix:

For source-specific sources, convert missing provenance from warning to validation failure unless:

```kotlin
sourceLinkFallbackPolicy == LEGACY_BACKFILL_ONLY
```

---

## P2-NEW-18 — debug snapshot diagnostic/audit

**Status: mostly fixed.**

Good:

- `DebugExpenseAuditWriter` exists.
- `createDebugSnapshot()` diagnostic is best-effort.
- `deleteAllExpenses()` and `restoreDebugSnapshot()` can write aggregate events.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

Required final tests:

```text
debug_delete_all_audit_failure_rolls_back_delete
debug_restore_audit_failure_rolls_back_restore
debug_snapshot_diagnostic_failure_does_not_fail_snapshot
metadata_contains_no_raw_expense_data
```

---

## P2-NEW-19 / P2-07 — bulk changed fields

**Status: partial.**

Good:

- `BulkChangedField` exists.
- `planBulkUpdated(...)` accepts `changedFields`.
- Planner creates actions for budget, anomaly, merchant learning, analytics cache, recurring.

Sources:
- Planner: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt
- BulkChangedField: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/BulkChangedField.kt

Remaining issues:

1. Coordinator callsites still use default `UNKNOWN` because `dispatchBulkPostCommitSideEffects(source, affectedCount)` does not pass fields.
2. Group hard-delete also calls `planBulkUpdated(...)` without `changedFields`.
3. Several bulk actions are still disabled/no-op:

```text
bulk_merchant_category_dirty -> DISABLED_BY_SETTINGS
bulk_merchant_canonical_stats_dirty -> DISABLED_BY_SETTINGS
bulk_analytics_cache_invalidation -> DISABLED_BY_SETTINGS
bulk_recurring_reconciliation -> DISABLED_BY_SETTINGS
```

This may be acceptable if intentionally documented, but it is not full “real invalidation.”

Required final fixes:

```kotlin
bulk category -> changedFields = CATEGORY
bulk merchant -> changedFields = MERCHANT, MERCHANT_KEY
group cleanup -> changedFields = OWNERSHIP, AMOUNT_EFFECTIVE
```

And decide whether disabled actions are acceptable or need real dirty markers.

---

## P2-NEW-20 / P2-P1-05 — static DAO guard

**Status: partial / not strong enough.**

Good:

- `RestrictedExpenseDaoMutation` exists.
- `ExpenseDao` mutating methods are annotated.
- `ExpenseDaoMutationAccessTest` exists.

Sources:
- Annotation: https://github.com/panospao7/Cost-agregator/blob/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/dao/RestrictedExpenseDaoMutation.kt
- DAO: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- Architecture test: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/test/java/com/yourname/expensetracker/architecture/ExpenseDaoMutationAccessTest.kt

Problems:

1. Annotation is still:

```kotlin
RequiresOptIn.Level.WARNING
```

not `ERROR`.

2. Architecture test explicitly accepts either WARNING or ERROR:

```text
restricted_annotation_uses_warning_or_error_level
```

3. `ExpenseRepository` has class-level opt-in:

```kotlin
@OptIn(RestrictedExpenseDaoMutation::class)
class ExpenseRepository
```

That is too broad. It weakens the goal of narrow allowlisted mutation bypasses.

4. The test does not appear to scan raw `expenseDao.update/delete/insert` calls outside approved files.

Required final fixes:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
```

Architecture test should require ERROR, not WARNING.

Also prefer function-level opt-in in `ExpenseRepository`, not class-level.

---

# Important new/regression risk found

## GroupLifecycleCoordinator violates post-commit side-effect invariant

This is the biggest new concern.

`GroupLifecycleCoordinator.emitLifecycleEvent(...)` writes a group lifecycle event, then immediately calls:

```kotlin
budgetMonitor.get().checkBudgets()
sideEffectDispatcher.dispatchOnCreated(...)
```

Several public methods call:

```kotlin
database.withTransaction {
    emitLifecycleEvent(...)
}
```

That means budget checks and transaction side effects can run **inside a Room transaction**.

Source:
- https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinator.kt

This regresses the Pipeline 2 invariant:

```text
DB transaction commits first.
Side effects run after commit.
Outer transaction owner dispatches side effects.
```

Required fix:

Split `emitLifecycleEvent` into:

```kotlin
private suspend fun writeGroupLifecycleEvent(...)
private suspend fun dispatchGroupLifecycleSideEffectsAfterCommit(...)
```

Then call side effects only after `database.withTransaction { ... }` returns successfully.

---

# Group hard-delete lifecycle is still incomplete

`GroupLifecycleCoordinator` exists, but `deleteGroupPermanently(...)`:

- returns `Boolean`, not `PermanentGroupDeleteResult`;
- only checks confirmation and inactive group;
- does not visibly check outstanding balances before hard delete;
- does not block current-user membership before hard delete;
- delegates to `groupCoordinator.permanentlyDeleteGroup(groupId)`, which is still the low-level Boolean path;
- low-level `GroupTransactionCoordinator` still has TODOs around hard-delete guard and member deletion validation.

Sources:
- Group lifecycle coordinator: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/groups/GroupLifecycleCoordinator.kt
- Low-level group coordinator: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt
- `PermanentGroupDeleteResult` exists but is not used by `GroupLifecycleCoordinator`: https://raw.githubusercontent.com/panospao7/Cost-agregator/4848d23522fe4ae69210e0e1f382693454ba583f/app/src/main/java/com/yourname/expensetracker/domain/groups/lifecycle/PermanentGroupDeleteResult.kt

Required fix:

Use `PermanentGroupDeleteResult` and enforce:

```text
confirmPermanentDelete
group exists
group inactive/archived
no outstanding balances
no current-user membership
GROUP_PERMANENTLY_DELETED event
post-commit side effects only
```

---

# Status table at `4848d23`

| Issue | Status |
|---|---:|
| P2-P1-01 / P2-NEW-06 business/tax API | **Mostly fixed** |
| P2-P1-02 failed-create diagnostics | **Mostly fixed, tests needed** |
| P2-P1-03 strict/conflict dedupe | **Mostly fixed, tests needed** |
| P2-P1-04 debug audit | **Mostly fixed, tests needed** |
| P2-P1-05 DAO mutation guard | **Partial** |
| P2-06 group hard-delete lifecycle | **Partial/Open** |
| P2-07 bulk side effects | **Partial** |
| P2-09 delete/FK/orphan tests | **Still test gap** |
| P2-10 deferred side-effect contract | **Mostly fixed in transaction lifecycle, but new group lifecycle regression** |
| P2-11 duplicate visibility | **Mostly fixed, tests needed** |
| P2-12 duplicate budget checks | **Mostly fixed, tests needed** |
| P2-NEW-01 update validation | **Mostly fixed** |
| P2-NEW-02 write barrier | **Mostly fixed** |
| P2-NEW-03 restore-blocked diagnostic | **Mostly fixed** |
| P2-NEW-04 strict attempt key | **Mostly fixed** |
| P2-NEW-05 insert conflict resolver | **Mostly fixed** |
| P2-NEW-07 category reassignment | **Atomicity fixed, changed-field callsite partial** |
| P2-NEW-08 review merchant key parity | **Mostly fixed** |
| P2-NEW-09 future-date policy | **Fixed** |
| P2-NEW-10 group create/link orphan | **Mostly fixed** |
| P2-NEW-11 group provenance | **Fixed** |
| P2-NEW-12 ownership result check | **Mostly fixed** |
| P2-NEW-13 group lifecycle coordinator | **Exists, but incomplete / regression risk** |
| P2-NEW-14 side-effect failed transaction event | **Mostly fixed** |
| P2-NEW-15 manual persisted hook | **Fixed, tests needed** |
| P2-NEW-16 receipt legacy path | **Guarded/disabled, not deleted** |
| P2-NEW-17 source-link fallback | **Partial: fallback fixed, requirements only warning** |
| P2-NEW-18 debug snapshot diagnostic | **Mostly fixed** |
| P2-NEW-19 bulk changed fields | **Partial: model exists, callsites/defaults incomplete** |
| P2-NEW-20 static guard coverage | **Partial: WARNING-level and weak architecture test** |

---

# Remaining blocker PRs before closure

## PR 1 — Fix GroupLifecycleCoordinator side-effect regression

Tasks:

```text
- Split event write from side-effect dispatch.
- Do not call budgetMonitor or sideEffectDispatcher inside database.withTransaction.
- Dispatch after commit only.
- Add rollback test: lifecycle transaction failure does not run side effects.
```

## PR 2 — Finish group permanent delete contract

Tasks:

```text
- Use PermanentGroupDeleteResult.
- Require confirmPermanentDelete.
- Block active groups.
- Block outstanding balances.
- Block current-user membership.
- Write lifecycle event.
- Keep side effects post-commit only.
```

## PR 3 — Harden source provenance enforcement

Tasks:

```text
- Missing source provenance should be validation failure, not Timber.w, for source-specific runtime creates.
- Allow only LEGACY_BACKFILL_ONLY to bypass.
- Add tests for review/receipt/group/bank/CSV/email/notification.
```

## PR 4 — Finish bulk changed-field callsites

Tasks:

```text
- category bulk passes CATEGORY.
- merchant bulk passes MERCHANT + MERCHANT_KEY.
- group cleanup passes OWNERSHIP + AMOUNT_EFFECTIVE.
- Decide/document disabled/no-op bulk actions.
```

## PR 5 — Harden DAO guard

Tasks:

```text
- RestrictedExpenseDaoMutation -> RequiresOptIn.Level.ERROR.
- Architecture test requires ERROR.
- Remove class-level opt-in from ExpenseRepository; use function-level allowlists.
- Add raw expenseDao mutation call scan outside approved files.
```

## PR 6 — Receipt legacy static guard

Tasks:

```text
- Either delete createExpenseFromReceipt entirely, or keep ERROR-disabled method.
- Add static test: no production caller can call it.
```

## PR 7 — Final regression/golden tests

Tasks:

```text
- delete/FK/orphan suite
- duplicate/no-budget side-effect suite
- restore blocked diagnostics
- group create/link rollback
- business patch unsupported fields
- side-effect failure event
```

---

# Final verdict

Do **not** close Pipeline 2 yet.

Current state is:

```text
Pipeline 2 is close and many core transaction lifecycle fixes are in place,
but it is not clean/ready because group lifecycle and static-guard contracts
are still incomplete, and a new post-commit side-effect regression appears
inside GroupLifecycleCoordinator.
```

The most important immediate fix is:

```text
Move GroupLifecycleCoordinator side effects out of database.withTransaction.
```

After that, finish:

```text
group permanent delete result/policy
source provenance enforcement
bulk changed-field callsites
ERROR-level DAO mutation guard
```

Then run the golden/static tests and update the tracker.