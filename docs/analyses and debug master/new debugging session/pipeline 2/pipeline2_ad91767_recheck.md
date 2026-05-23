# Pipeline 2 recheck — commit `ad91767`

Static review only; I did not run Gradle/tests.

## Executive verdict

Pipeline 2 is **better than the old report**, especially around source links, write barriers, review approval, and group post-commit handling. But it is **not clean/closed**.

The master tracker still marks P2-P1-01..04 fixed and P2-P1-05 TODO, but I would revise that: the tracker says those statuses under Pipeline 2, while the current code still has documented TODOs/caveats for several of them. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md))

## High-priority remaining blockers

1. **Full-row `updateExpense()` still lacks create-equivalent validation.**  
   Create validation checks amount/currency/date/transfer/ownership/location, but `updateExpense()` mostly recomputes dedupe/currency and writes the row. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))

2. **`deleteExpense(expense: Expense)` stale-snapshot overload still exists.**  
   `deleteExpense(id)` loads inside transaction, but entity overload still snapshots the passed object. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))

3. **Category-to-category bulk reassignment is still non-atomic.**  
   Code explicitly has `TODO P2-CURRENT-015` and loops through `updateCategory()`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))

4. **Deprecated receipt create path still exists and is still non-atomic internally.**  
   It is `DeprecationLevel.ERROR`, which helps, but method body still does create → receipt link → item categorization separately. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt))

5. **Durable diagnostics are improved but not fully solved.**  
   Restore-blocked create only logs/returns error; there is no durable restore-blocked event. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt))

---

# Status table

| Issue | Current status | Notes |
|---|---:|---|
| P2-P1-01 business/tax update restore guard | **Partial** | Restore guard exists, but `businessUsePercent`, `taxCategory`, `vatEligible` are still accepted no-ops. Code explicitly documents this as partial. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| P2-P1-02 failed creates invisible | **Partial** | `CREATE_ATTEMPTED`, validation, duplicate, insert-conflict events exist. But restore-blocked create is not durable, and events can still be rollback-prone when coordinator is called inside an outer transaction. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| P2-P1-03 `STRICT_EXTERNAL_ID` conflict | **Mostly fixed** | Conflict resolves to existing ID for strict mode, but attempt dedupe key still uses standard key; code has TODO for mismatch. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| P2-P1-04 debug/restore methods | **Mostly fixed** | Debug methods are write-barrier/debug guarded, but still weak on lifecycle audit. Expense repo now has write-barrier guards around debug delete/restore. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| P2-P1-05 public DAO mutation surface | **Partial** | Maintenance writes now have barriers: backfill attempts, location, merchant key. But static allowlist/CI guard still not proven. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt)) |
| P2-06 group hard-delete cleanup | **Mostly fixed** | Now clears flags inside transaction, writes `BULK_UPDATED`, and dispatches post-commit bulk actions. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt)) |
| P2-07 bulk side effects | **Partial** | Bulk post-commit exists, but planner bulk path is still budget-focused, not full anomaly/merchant/dashboard invalidation. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt)) |
| P2-08 delete snapshot stale | **Partial** | ID delete fixed; entity overload remains stale. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt)) |
| P2-09 hard/soft delete contract | **Mostly fixed** | Hard-delete semantics are documented; still needs FK/orphan tests. |
| P2-10 deferred side effects | **Mostly fixed** | Create V2 and ownership DB-only flow exist. Group linking now collects actions and runs after outer transaction. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt)) |
| P2-11 duplicate visibility | **Mostly fixed** | Review approval now routes create through coordinator. Old precheck method exists but is not used in approval path. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt)) |
| P2-12 duplicate budget checks | **Mostly fixed** | Review-created expenses use deferred transaction actions instead of direct duplicate budget checks. |

---

# New issue recheck

| Old report ID | Current status | Notes |
|---|---:|---|
| P2-NEW-01 update lacks validation | **Open** | Still no shared create/update validator. |
| P2-NEW-02 update side effects before outer commit | **Mostly fixed for group flow** | `addExpenseWithLink()` uses `updateOwnershipDbOnlyV2()` and runs actions post-commit. Generic full-update still relies on caller discipline. |
| P2-NEW-03 stale delete overload | **Open** | Public entity overload still exists. |
| P2-NEW-04 source-link fields not persisted | **Mostly fixed** | Mapper now maps review/receipt/email/group/CSV/bank/import fields, coordinator writes links atomically and emits `SOURCE_LINKED`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkMapper.kt)) ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriter.kt)) |
| P2-NEW-05 review duplicate precheck bypass | **Fixed / obsolete** | Approval now delegates to coordinator inside transaction. |
| P2-NEW-06 review dedupe wrong currency | **Mostly fixed, dead-code caveat** | Temporary `Expense.dedupeKey` still uses `review.suggestedCurrency`, but coordinator recomputes from request currency. Clean up to avoid future misuse. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt)) |
| P2-NEW-07 receipt-created expense non-atomic | **Partial/Open** | Compile-time deprecated with ERROR, but unsafe body remains. |
| P2-NEW-08 category reassignment non-atomic | **Open** | Explicit TODO remains. |
| P2-NEW-09 bulk side effects only budget | **Open/Partial** | Still budget-focused. |
| P2-NEW-10 group hard-delete audit + side effects | **Mostly fixed** | Atomic event + post-commit bulk path added. |
| P2-NEW-11 durable side-effect failures | **Open/Unproven** | `SIDE_EFFECT_FAILED` enum exists, but I did not see transaction-event writing from inspected planner/coordinator paths. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt)) |
| P2-NEW-12 recurring unlink twice | **Fixed** | Delete now plans recurring unlink via planner; no extra direct unlink in delete path seen. |
| P2-NEW-13 manual recommendation uses synthetic expense | **Not reverified** | Needs `ManualExpenseRepository` pass; leave as open until checked. |

## Recommended next PR order

1. Add shared `TransactionValidator.validateFinalState()` and call it from `updateExpense()` and atomic type/transfer updates.
2. Delete or `ERROR`-hide `deleteExpense(expense: Expense)` and route repository delete by ID only.
3. Replace category-to-category bulk loop with one DAO update + one `BULK_UPDATED`.
4. Remove/rewrite `ReceiptRepository.createExpenseFromReceipt()`.
5. Add durable `CREATE_BLOCKED_RESTORE` / `SIDE_EFFECT_FAILED` diagnostics.
6. Expand `planBulkUpdated()` beyond budget check.
7. Add CI/static guard for allowed `ExpenseDao` mutation callers.