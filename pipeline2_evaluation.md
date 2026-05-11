# Pipeline 2 — Transaction Lifecycle evaluation

## Executive verdict

Compared with the older debug report/tracker baseline, Pipeline 2 is **in significantly better shape**.

My current status call:

- **4/5 tracker items are materially fixed**
- **1 tracker item is still open in substance**
- and there are **2 important residual risks** outside the narrow tracker rows:
  1. lifecycle bypass is still not hard-enforced
  2. test coverage is still too weak to call this stable

So the honest answer is:

> **Pipeline 2 is mostly fixed architecturally, but not fully clean/stable yet.**

---

## Tracker issue-by-issue

## P2-P1-01 — `updateBusinessTaxFields()` missed restore guard
**Tracker:** ✅ fixed  
**My verdict:** **fixed for the stated bug, but only partially clean overall**

What is now true:
- `TransactionLifecycleCoordinator.updateBusinessTaxFields()` checks restore mode before writing.
- It writes an `UPDATED` `TransactionEvent` inside a DB transaction.

Why I still wouldn’t call this perfectly clean:
- The method is narrower than its signature suggests.
- `businessUsePercent`, `taxCategory`, and `vatEligible` are accepted but effectively **no-op** for compatibility.
- Only `isBusinessExpense` and `receiptRequired -> requiresReceipt` are actually persisted.

So:
- the **restore-guard bug is fixed**
- but the **business/tax patch surface is semantically partial**

---

## P2-P1-02 — Failed creates invisible in `transaction_events`
**Tracker:** ✅ fixed  
**My verdict:** **fixed and fairly solid**

At HEAD, `createExpense()` writes:
- `CREATE_ATTEMPTED`
- `CREATE_VALIDATION_FAILED`
- `CREATE_INSERT_CONFLICT`
- `CREATED`
- `CREATE_DUPLICATE_SKIPPED` in strict-idempotent duplicate resolution

That is a real improvement.

Caveat:
- several event writes are wrapped in `runCatching`, so observability is still best-effort if event logging itself fails.
- But the original invisibility problem is clearly fixed.

---

## P2-P1-03 — `STRICT_EXTERNAL_ID` returned weak `InsertConflict`
**Tracker:** ✅ fixed  
**My verdict:** **fixed**

Current behavior:
- strict mode namespaces the dedupe key as `idem:${source}:$key`
- on insert conflict it calls `findIdByDedupeKey(...)`
- if found, returns `DuplicateSkipped(existingExpenseId=...)`

That is the right recovery shape for idempotent retries.

Main caution:
- I did not find strong DB-backed tests for this path, so I trust the code structure more than runtime proof.

---

## P2-P1-04 — Debug/restore methods bypass lifecycle
**Tracker:** ✅ fixed  
**My verdict:** **mostly fixed / acceptable**

What’s good now:
- `ExpenseRepository.deleteAllExpenses()`:
  - checks write barrier
  - is blocked outside `BuildConfig.DEBUG`
- `restoreDebugSnapshot()`:
  - checks write barrier
  - is blocked outside `BuildConfig.DEBUG`
- `createDebugSnapshot()` is also debug-only

This is good enough for debug-only tooling.

Caveat:
- these paths still intentionally bypass normal lifecycle events.
- For debug tooling that is acceptable, but it’s not “normal business lifecycle”.

---

## P2-P1-05 — Public DAO mutation surface still enables bypass
**Tracker:** TODO ONLY  
**My verdict:** **still NOT closed**

This remains the clearest open Pipeline 2 issue.

Evidence:
- `ExpenseDao` still publicly exposes raw mutation methods:
  - `insert`
  - `update`
  - `delete`
  - many column-specific `update...`
- I did **not** find a compile-time/static allowlist guard.
- `ExpenseRepository` itself documents remaining intentional bypasses:
  - location backfill helpers
  - merchant key backfill
  - receipt-link category propagation
  - group cleanup/system normalization
- that same header is also **stale**, which is another sign the closure boundary is not tightly controlled.

So although many real callers were migrated, the architecture is still relying on **discipline**, not **enforcement**.

---

## Important implemented improvements beyond the tracker

## 1. Nested transaction side-effect bug: improved, not fully proven closed
This was one of the biggest old Pipeline 2 risks.

What I verified at HEAD:
- `ManualExpenseRepository.addManualExpense()` now calls
  - `createExpense(..., SideEffectMode.DEFER)`
  - then `dispatchPostCreationSideEffects(...)` **after** outer commit
- sampled `ReviewQueueRepository` transaction path does the same
- `NotificationProcessingPipeline` also uses `SideEffectMode.DEFER`

That is a **real fix direction**.

Why I still won’t call it fully closed:
- `TransactionLifecycleCoordinator` still contains an explicit TODO saying callers could still misuse `IMMEDIATE` inside caller-managed transactions.
- I did **not** re-verify every single creation caller, especially the older group/email/bank seams.

So:
- **major improvement**
- **not mathematically sealed**

---

## 2. Update lifecycle centralization is much better
This is one of the strongest actual improvements in HEAD.

`ExpenseRepository` now routes many user mutation paths through the coordinator:
- category update
- full-row update
- merchant update
- type update
- transfer details
- ownership updates
- location update
- bulk category update
- bulk merchant update
- delete through coordinator

That is substantial progress versus the older report.

Why still not fully clean:
- intentional bypasses still exist
- no static enforcement exists
- docs/comments are stale enough that I would not trust closure claims blindly

---

## 3. Event model is good, but audit ergonomics are still thin
`TransactionEvent` is a solid model:
- `expenseId`
- `eventType`
- `source`
- `actor`
- `occurredAt`
- `dedupeKey`
- `duplicateExpenseId`
- `beforeSnapshot`
- `afterSnapshot`
- `metadata`
- `reason`

That’s strong.

But `TransactionEventDao` is still minimal:
- `insert`
- `getEventsForExpense`

So audit/debug usefulness is still weaker than the event model deserves.

---

## 4. Biggest remaining stability blocker: tests are still weak
This is the main reason I won’t call Pipeline 2 stable.

At HEAD, `TransactionLifecycleCoordinatorTest` is still basically mock-only smoke coverage:
- valid create
- negative amount
- blank merchant
- invalid currency

It does **not** prove:
- real Room transaction behavior
- `transaction_events` persistence
- duplicate blocking with real schema/indexes
- strict external ID retry resolution
- rollback safety
- post-commit side-effect timing
- restore-blocked real DB writes
- update/delete snapshot correctness

So even where the code looks right, the **stability proof is missing**.

---

## Final scorecard

If I were updating Pipeline 2 today, I’d mark it roughly:

- **P2-P1-01 restore guard on business/tax update:** **✅ fixed**  
  but feature semantics still partial
- **P2-P1-02 failed-create event visibility:** **✅ fixed**
- **P2-P1-03 strict external idempotency resolution:** **✅ fixed**
- **P2-P1-04 debug/restore bypass safety:** **✅ fixed**
- **P2-P1-05 public DAO bypass surface:** **⚠ still open**

And outside the narrow tracker:
- **nested transaction post-commit boundary:** **mostly fixed / not fully proven**
- **update path centralization:** **mostly fixed**
- **tests:** **not strong enough for “stable”**

---

## Bottom-line answer

### Are Pipeline 2 issues fixed?
**Mostly, yes for the main tracked items.**

### Are they clean and stable?
**No, not fully.**

The best summary is:

> **Pipeline 2 is one of the more successful refactors in this branch, but it still lacks hard enforcement and DB-backed contract proof, so I would not declare it fully closed yet.**

---

## Sources
- Master tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Original Pipeline 2 debug report:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/debugging/pipeline-2-transaction-lifecycle-debug-report.md
- Coordinator:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- Expense repository:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- Expense DAO:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- Transaction event model/DAO:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt
- Sample nested-transaction callers:
  - Manual:  
    https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
  - Review queue:  
    https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
  - Notification pipeline:  
    https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- Tests:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt