# Pipeline 2 open issues + PR plan — commit `ad91767`

Mode: **static GitHub/code review only**. I did **not** run Gradle/tests.

## Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `TransactionLifecycleCoordinator.kt`
- `TransactionSideEffectPlanner.kt`
- `TransactionSideEffectDispatcher.kt`
- `PostCommitActionRunnerImpl.kt`
- `DiagnosticSideEffectEventWriter.kt`
- `ExpenseRepository.kt`
- `ExpenseDao.kt`
- `Expense.kt`
- `CreateExpenseRequest.kt`
- `CreateExpenseResult.kt`
- `LifecycleEventType.kt`
- `TransactionEvent.kt`
- `CreateExpenseSourceLinkMapper.kt`
- `SourceLinkWriterImpl.kt`
- `EntitySourceLink.kt`
- `GroupTransactionCoordinator.kt`
- `ReviewQueueRepository.kt`
- `ReceiptRepository.kt`
- `ManualExpenseRepository.kt`

---

# Executive status

Pipeline 2 is **not closed**.

A lot is fixed or improved:

- create path is centralized,
- source-link table exists,
- source links are written atomically during create,
- review approval appears mostly migrated through coordinator,
- group post-commit side effects improved,
- side-effect runner now emits generic diagnostic events,
- maintenance writes in `ExpenseRepository` now have write-barrier checks.

But there are still **real open gaps**, mostly around:

1. update validation,
2. diagnostics/dedup edge cases,
3. bulk/group atomicity,
4. provenance callsite completeness,
5. public DAO mutation/static guards,
6. receipt legacy path removal,
7. manual hook using synthetic row.

---

# Complete open issue register

## A. Old tracker issues still open / partial

| ID | Status | Severity | Issue | Notes |
|---|---:|---:|---|---|
| P2-P1-01 | Partial | P1/P2 | Business/tax update accepts unsupported fields | `updateBusinessFlags()` is restore-guarded, but `businessUsePercent`, `taxCategory`, `vatEligible` are accepted and logged as ignored. This is still a caller-contract bug. |
| P2-P1-02 | Partial | P1/P2 | Failed creates not fully durable/diagnosable | `CREATE_ATTEMPTED`, validation, duplicate, conflict events exist. But restore-blocked create only logs/returns error; no durable `CREATE_BLOCKED_RESTORE`. Also rollback-prone when invoked inside outer transactions. |
| P2-P1-03 | Partial | P2 | `STRICT_EXTERNAL_ID` mostly fixed, but audit/dedupe mismatch remains | Strict conflict can resolve via `findIdByDedupeKey()`, but TODO says `CREATE_ATTEMPTED` dedupe key does not match strict persisted `idem:{source}:{key}`. Standard/bulk insert races still do not resolve existing ID consistently. |
| P2-P1-04 | Partial | P2 | Debug/restore methods guarded but weak audit | Debug delete/restore are DEBUG + write-barrier guarded, but no meaningful lifecycle/debug audit events are written for aggregate destructive operations. |
| P2-P1-05 | Open/Partial | P1 | Public DAO mutation surface still exists | `ExpenseDao` still exposes public insert/update/delete/mutation methods and has its own TODO. Repository maintenance writes now have barriers, but no static allowlist/CI guard proves all direct mutations are approved. |

---

## B. Old lower-priority Pipeline 2 issues still open / partial

| ID | Status | Severity | Issue | Notes |
|---|---:|---:|---|---|
| P2-06 | Partial | P2 | Group hard-delete lifecycle still incomplete | Shared flag cleanup now happens in transaction and emits `BULK_UPDATED`, but permanent delete still has TODOs: no explicit confirmation flag, weak group lifecycle audit, no outstanding-balance validation, no “last current user” guard. |
| P2-07 | Partial | P2 | Bulk side effects incomplete | `planBulkUpdated()` still only creates a bulk budget check. No anomaly invalidation, dashboard/cache invalidation, merchant/category model refresh, or recurring reconciliation. |
| P2-09 | Partial/Test gap | P2/P3 | Soft/hard delete semantics need tests | Expense delete path appears improved, but FK/orphan behavior still needs explicit tests for receipt links, group links, recurring links, transaction events. |
| P2-10 | Partial | P1/P2 | Deferred side-effect contract still relies on caller discipline | V2 DB-only APIs exist, but deprecated `createExpense(..., SideEffectMode)` still exists internally and TODO says static guard is needed to prevent `IMMEDIATE` inside outer transactions. |
| P2-11 | Mostly fixed, residual | P2 | Duplicate visibility improved but edge cases remain | Review approval now routes through coordinator, but insert-race/strict audit mismatch still remain under diagnostics/dedup PR. |
| P2-12 | Mostly fixed | P3 | Duplicate budget checks | No major open blocker found; keep regression tests. |

---

## C. New/current open gaps from latest code

| New ID | Severity | Issue | Evidence / notes |
|---|---:|---|---|
| P2-NEW-01 | P1 | Full-row `updateExpense()` still lacks create-equivalent validation | Create validates amount/currency/date/transfer/location/ownership; update mostly recomputes dedupe/currency and writes. Need shared final-state validator. |
| P2-NEW-02 | P1/P2 | `TransactionLifecycleCoordinator` injects `writeBarrier` but uses `restoreMaintenanceMode` directly | Code TODO `P2-CURRENT-007`. This weakens centralized barrier/error semantics. |
| P2-NEW-03 | P2 | Restore-blocked create has no durable diagnostic | Code TODO `P2-CURRENT-011`; currently Timber + `CreateExpenseResult.Error`. |
| P2-NEW-04 | P2 | Strict external attempt event dedupe key mismatch | Code TODO `P2-CURRENT-012`. |
| P2-NEW-05 | P2 | Standard/BULK insert race can return unresolved `InsertConflict` | Code TODO `P2-CURRENT-005`; should resolve existing ID via duplicate/range/dedupe lookup where possible. |
| P2-NEW-06 | P2 | Business/tax API silently drops accepted fields | Code TODO `P2-CURRENT-014`; same as P2-P1-01 but keep as current-code issue. |
| P2-NEW-07 | P2 | Category-to-category bulk reassignment is non-atomic | Code TODO `P2-CURRENT-015`; loops per expense through `updateCategory()`. Crash mid-loop leaves partial migration. |
| P2-NEW-08 | P2 | Review approval merchant key can diverge from auto-accept path | Code TODO `P2-CURRENT-006`; double-normalization risk. |
| P2-NEW-09 | P2 | Future-date policy hardcoded | Code TODO `P2-CURRENT-020`; validation uses fixed now + 1 day tolerance. |
| P2-NEW-10 | P1/P2 | Group create-system-expense-and-link can commit orphan system expense on non-throwing link failure | In `createSystemExpenseAndLinkToGroup()`, after coordinator creates expense, later `groupExpenseDao.insert()` failure returns an error outcome instead of throwing. Room commits if lambda returns normally. This can break the advertised atomicity. |
| P2-NEW-11 | P1/P2 | Group-created system expense does not pass `groupId` into `CreateExpenseRequest` | `source = GROUP_EXPENSE` is set, but `groupId = groupId` is not passed, so source link falls back to legacy/source-only instead of concrete group provenance. |
| P2-NEW-12 | P2 | `addExpenseWithLink()` does not visibly check DB-only ownership update result | It calls `updateOwnershipDbOnlyV2()` and uses `postCommitActions`; if the mutation result can be non-success, the group link may commit while ownership metadata failed. Needs explicit assertion/test. |
| P2-NEW-13 | P2 | Permanent group delete still lacks group lifecycle coordinator | File-level TODO says create `GroupLifecycleCoordinator`; hard delete still low-level. |
| P2-NEW-14 | P2 | Side-effect failures are durable only in generic diagnostics, not `transaction_events` | `LifecycleEventType.SIDE_EFFECT_FAILED` exists, but runner emits `DiagnosticEvent` outcomes. Decide contract: generic diagnostics is enough, or also write transaction event / remove unused enum. |
| P2-NEW-15 | P2 | Manual recommendation hook uses synthetic expense | Code TODO `P2-CURRENT-019`; recommendation/AI hook may miss persisted conversion snapshot/base fields. |
| P2-NEW-16 | P1/P2 | Receipt `createExpenseFromReceipt()` legacy path should be removed/guarded | Previous report found non-atomic create → link → categorization. If still present, delete or replace with receipt lifecycle coordinator. Add grep/static guard. |
| P2-NEW-17 | P2 | Source-link fallback creates legacy partial links for source-only requests | Mapper creates `LEGACY_SOURCE_ONLY` when no specific fields are present. Fine for migrations, weak for real manual/group/bank/import callsites. Callers should pass explicit source links. |
| P2-NEW-18 | P2/P3 | Debug snapshot generation lacks diagnostic/audit event | Debug create snapshot reads are allowed, but no durable record that snapshot was generated/restored/deleted. |
| P2-NEW-19 | P2 | Bulk merchant/category side effects lack changed-field semantics | Planner only gets `source` + `affectedCount`; no `changedFields`, so it cannot choose targeted invalidations. |
| P2-NEW-20 | P2 | Static guard coverage missing | Need CI test to forbid `ExpenseDao` mutations outside coordinator/allowlisted maintenance/debug/migration paths. |

---

# Closed / mostly closed items not included as active PR blockers

| Issue | Current status |
|---|---|
| Review duplicate precheck bypass | Mostly fixed/obsolete if approval now routes create through coordinator. Keep regression test. |
| Group update side effects before outer commit | Mostly fixed for `addExpenseWithLink()` via DB-only mutation + post-commit batch. |
| Group hard-delete shared flag cleanup audit/side effect | Mostly fixed: event inside transaction + post-commit bulk action. Remaining issue is broader group hard-delete lifecycle. |
| Recurring unlink twice on delete | No duplicate direct unlink found in inspected delete planner path. Keep regression test. |
| Base source-link table/model | Implemented. Remaining issue is callsite completeness, not absence of model. |
| Maintenance writes write-barrier sweep | Improved: location, backfill, merchant-key methods have barrier. Remaining issue is static enforcement. |

---

# PR organization

## PR 1 — Transaction validation + write-barrier normalization

### Fixes

- P2-NEW-01
- P2-NEW-02
- P2-NEW-06
- P2-NEW-09
- P2-P1-01
- part of P2-P1-05

### Tasks

1. Add `TransactionValidator`.
2. Extract create validation into validator.
3. Add update/final-state validation:
   - amount > 0,
   - amount <= max policy,
   - nonblank valid currency,
   - date policy,
   - transfer metadata required for `TRANSFER`,
   - location pair validity,
   - ownership normalization/invariants,
   - business/tax patch semantics.
4. Replace direct `restoreMaintenanceMode.isWritesAllowed()` checks in `TransactionLifecycleCoordinator` with `writeBarrier.checkWritesAllowed(...)`.
5. Replace `updateBusinessFlags()` loose API with explicit patch/result or remove unsupported fields.
6. Make future-date tolerance configurable via validation policy.

### Acceptance tests

```text
create_uses_write_barrier
update_uses_write_barrier
update_rejects_negative_amount
update_rejects_blank_currency
update_rejects_invalid_currency
update_rejects_invalid_future_date_by_policy
update_rejects_transfer_without_direction_or_account
update_rejects_lat_without_lon
update_normalizes_conflicting_ownership
business_patch_reports_unsupported_fields
```

---

## PR 2 — Create diagnostics + dedup conflict hardening

### Fixes

- P2-P1-02
- P2-P1-03
- P2-NEW-03
- P2-NEW-04
- P2-NEW-05
- residual P2-11

### Tasks

1. Add durable restore-blocked event/diagnostic:
   - either `CREATE_BLOCKED_RESTORE` diagnostic,
   - or transaction diagnostic table row.
2. Fix strict external attempt dedupe key:
   - `idem:${source}:${idempotencyKey ?: externalFingerprint}`.
3. For all insert conflicts, attempt to resolve existing ID:
   - strict dedupe key lookup,
   - range/currency/type duplicate lookup,
   - maybe dedupe-key lookup as fallback.
4. Return `DuplicateSkipped(existingExpenseId)` when resolvable.
5. Only return `InsertConflict` when no existing row can be found.
6. Decide whether failed create attempt events must survive outer transaction rollback. If yes, use separate diagnostic writer outside Room transaction lifecycle.

### Acceptance tests

```text
restore_blocked_create_writes_durable_diagnostic
strict_external_attempt_event_uses_idem_key
strict_external_retry_returns_existing_id
standard_insert_race_returns_duplicate_when_existing_id_resolvable
bulk_insert_race_returns_duplicate_when_existing_id_resolvable
validation_failed_create_has_correlation_id
duplicate_skipped_event_contains_existing_id_when_known
```

---

## PR 3 — Source-link/provenance callsite completion

### Fixes

- P2-NEW-11
- P2-NEW-17
- remaining part of P2-NEW-04
- provenance part of P2-P1-02/P2-P1-03

### Tasks

1. Pass `groupId = groupId` in `createSystemExpenseAndLinkToGroup()`.
2. Add explicit source links for manual entries instead of `LEGACY_SOURCE_ONLY` if desired.
3. Audit all `CreateExpenseRequest` callsites:
   - notification,
   - review approval,
   - receipt,
   - email,
   - bank,
   - CSV/import,
   - group,
   - recurring.
4. Ensure source-link metadata appears in:
   - `CREATE_ATTEMPTED`,
   - `CREATED`,
   - `CREATE_DUPLICATE_SKIPPED`,
   - `CREATE_VALIDATION_FAILED`,
   - `CREATE_INSERT_CONFLICT`.
5. Add source-link query tests for each source type.

### Acceptance tests

```text
group_created_expense_has_group_source_link
review_approved_expense_has_pending_review_source_link
receipt_created_expense_has_scanned_receipt_source_link
email_receipt_expense_has_email_source_link
csv_import_expense_has_import_row_source_link
bank_sync_expense_has_bank_source_link
manual_entry_does_not_get_misleading_legacy_source_link_or_is_explicitly_marked_manual
duplicate_event_contains_source_link_metadata
```

---

## PR 4 — Group transaction atomicity + lifecycle hardening

### Fixes

- P2-NEW-10
- P2-NEW-12
- P2-NEW-13
- P2-06 residual
- P2-10 residual for group paths

### Tasks

1. In `createSystemExpenseAndLinkToGroup()`, after expense creation, any group-link failure must throw/rollback, not return normal error.
2. Inspect and assert `updateOwnershipDbOnlyV2()` result in `addExpenseWithLink()`.
3. Add `GroupLifecycleCoordinator` or at least lifecycle event writer for:
   - group created,
   - member added,
   - member removed,
   - group archived,
   - group permanently deleted,
   - settlement recorded.
4. Permanent delete:
   - require explicit confirmation flag,
   - validate no outstanding balances,
   - prevent deleting last current user without transfer/archive,
   - prefer archive path.
5. Keep post-commit side effects outside outer transaction.

### Acceptance tests

```text
group_create_system_expense_link_failure_rolls_back_expense
group_link_ownership_update_failure_rolls_back_group_link
group_expense_create_dispatches_side_effects_after_outer_commit
group_expense_create_rollback_does_not_dispatch_side_effects
permanent_group_delete_requires_confirmation
permanent_group_delete_blocks_outstanding_balances
permanent_group_delete_writes_lifecycle_event
archive_group_preserves_group_and_links
```

---

## PR 5 — Atomic bulk operations + richer bulk side effects

### Fixes

- P2-NEW-07
- P2-NEW-19
- P2-07

### Tasks

1. Replace category-to-category loop with single DAO update:
   - `UPDATE expenses SET categoryId = :new WHERE categoryId = :old`.
2. Write one `BULK_UPDATED` event with affected count and changed fields.
3. Add `changedFields` to `planBulkUpdated()`.
4. Expand bulk side effects:
   - budget recheck,
   - anomaly invalidation/re-evaluation,
   - merchant/category model refresh or dirty marker,
   - dashboard/cache invalidation if applicable,
   - recurring reconciliation only if merchant/type/date changed.
5. Do not dispatch N per-row side effects for bulk changes.

### Acceptance tests

```text
bulk_category_reassignment_is_single_sql_update
bulk_category_reassignment_rolls_back_atomically
bulk_category_reassignment_writes_one_bulk_event
bulk_category_reassignment_dispatches_one_bulk_batch
bulk_merchant_update_marks_merchant_stats_dirty
bulk_category_update_invalidates_dashboard_or_analytics_cache
```

---

## PR 6 — Receipt legacy path removal / static guard

### Fixes

- P2-NEW-16

### Tasks

1. Grep all production callers of `createExpenseFromReceipt`.
2. Delete the method if unused.
3. If still needed, replace with receipt lifecycle coordinator that does:
   - receipt write,
   - expense create,
   - receipt link,
   - item categorization,
   - source link,
   - event writes
   in one DB transaction.
4. Add static guard to prevent production usage of deprecated receipt create path.

### Acceptance tests

```text
grep_createExpenseFromReceipt_has_no_production_callers
receipt_expense_create_link_is_atomic
receipt_link_failure_rolls_back_expense
receipt_item_categorization_failure_policy_is_explicit
receipt_source_link_written_atomically
```

---

## PR 7 — Side-effect diagnostics contract decision

### Fixes

- P2-NEW-14

### Tasks

1. Decide canonical durable side-effect failure location:
   - generic `PipelineDiagnosticEvent` only, or
   - also `transaction_events.SIDE_EFFECT_FAILED`.
2. If generic diagnostics are canonical:
   - document that `SIDE_EFFECT_FAILED` enum is deprecated/unused,
   - remove enum if safe.
3. If transaction events are required:
   - add transaction side-effect event writer.
4. Add query-level tests proving side-effect failures are visible.

### Acceptance tests

```text
side_effect_started_written_to_diagnostics
side_effect_completed_written_to_diagnostics
side_effect_failed_retryable_written_to_diagnostics
side_effect_cancelled_rethrows_cancellation
transaction_side_effect_failed_event_written_if_contract_requires_it
```

---

## PR 8 — Manual create hook correctness

### Fixes

- P2-NEW-15

### Tasks

1. After coordinator create returns ID, fetch persisted expense:
   - `expenseDao.getById(id)`.
2. Use persisted row for AI/recommendation hook.
3. Or change `CreateExpenseResult.Created` to carry a persisted snapshot.
4. Ensure conversion fields/base amount are available to downstream hooks.

### Acceptance tests

```text
manual_recommendation_uses_persisted_expense
manual_recommendation_sees_base_amount
manual_recommendation_sees_exchange_rate_used
manual_recommendation_handles_missing_persisted_expense_gracefully
```

---

## PR 9 — DAO mutation static guard + debug audit

### Fixes

- P2-P1-04 residual
- P2-P1-05
- P2-NEW-18
- P2-NEW-20

### Tasks

1. Add CI/static test scanning for `ExpenseDao` mutation calls.
2. Allow only:
   - `TransactionLifecycleCoordinator`,
   - approved maintenance methods with write barrier,
   - debug-only methods,
   - migrations/backfills with explicit comments.
3. Emit aggregate debug lifecycle/diagnostic events for:
   - delete all expenses,
   - restore debug snapshot,
   - create debug snapshot if desired.
4. Keep destructive debug methods DEBUG-only.

### Acceptance tests

```text
ci_fails_on_unapproved_expenseDao_insert
ci_fails_on_unapproved_expenseDao_update
ci_fails_on_unapproved_expenseDao_delete
debug_delete_all_writes_aggregate_event
debug_restore_snapshot_writes_RESTORED_FROM_DEBUG_SNAPSHOT
debug_methods_unavailable_in_release
```

---

## PR 10 — Delete/FK/orphan regression suite

### Fixes

- P2-09 residual

### Tasks

1. Add tests for expense delete:
   - transaction event survives,
   - receipt links do not become invalid,
   - group links handled,
   - recurring occurrence unlink handled once.
2. Add tests for hard vs soft delete semantics.
3. Verify current public API no longer exposes stale entity-delete overload.

### Acceptance tests

```text
delete_expense_preserves_transaction_events
delete_expense_does_not_cascade_audit_log
delete_expense_receipt_links_policy_is_correct
delete_expense_group_links_policy_is_correct
delete_expense_recurring_unlink_runs_once
delete_by_id_uses_latest_snapshot
entity_delete_overload_not_public_or_not_used
```

---

# Recommended execution order

1. **PR 1 — validation + write-barrier normalization**
2. **PR 2 — diagnostics + dedup conflict hardening**
3. **PR 4 — group atomicity/lifecycle hardening**
4. **PR 3 — provenance callsite completion**
5. **PR 5 — atomic bulk + richer side effects**
6. **PR 6 — receipt legacy path removal**
7. **PR 8 — manual persisted-row hook**
8. **PR 7 — side-effect diagnostics decision**
9. **PR 9 — DAO mutation static guard + debug audit**
10. **PR 10 — delete/FK/orphan regression suite**

If you want the fewest PRs, merge PR 7 into PR 2 and PR 10 into PR 9. But I would keep PR 4 separate because group atomicity is high-risk.