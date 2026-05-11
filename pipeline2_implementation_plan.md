# Pipeline 2 implementation plan — Transaction Lifecycle
Scope: remaining Pipeline 2 gaps, residual risks in implemented fixes, and closure work needed to call the lifecycle clean/stable.

## Executive goal
Move Pipeline 2 from **“mostly fixed architecturally”** to **“hard-enforced and DB-proven”**.

The main remaining problems are:
1. **P2-P1-05 is still open**: raw `ExpenseDao` mutation surface still allows bypass.
2. **Nested-transaction safety is improved but still human-disciplined**, not enforced (`TODO (P2-10)` remains in `TransactionLifecycleCoordinator`).
3. **`updateBusinessTaxFields()` is restore-guarded but semantically partial**: `businessUsePercent`, `taxCategory`, `vatEligible` are accepted as no-ops.
4. **Stability proof is weak**: tests are still mostly mock-only, not Room/transaction contract tests.

---

## Recommended PR order

### PR0 — Inventory + safety net
**Priority:** Critical  
**Goal:** freeze the mutation inventory before more refactor.

### Work
- Inventory every expense mutation callsite with grep:
  - `expenseDao.insert`
  - `expenseDao.update`
  - `expenseDao.delete`
  - `expenseDao.updateCategory`
  - `expenseDao.updateMerchant`
  - `expenseDao.updateLocation`
  - `createExpense(` inside `withTransaction`
- Produce a short allowlist:
  - coordinator-owned
  - maintenance/backfill-owned
  - debug-only
  - unresolved/bad
- Add a temporary markdown doc under `docs/` with that inventory.

### Done when
You have a complete callsite map, not just the KDoc summary.

---

## PR1 — Close P2-P1-05 with mutation-boundary enforcement
**Priority:** Critical  
**Files:**
- `ExpenseDao.kt`
- `ExpenseRepository.kt`
- `TransactionLifecycleCoordinator.kt`
- backfill/maintenance callsites
- CI script / detekt config

### Problem
`ExpenseDao` still exposes raw mutators publicly (`delete`, `updateCategory`, etc.), so lifecycle bypass is still possible.

### Recommended design
Split mutation responsibilities instead of leaving one giant DAO:
- `ExpenseReadDao` — reads only
- `ExpenseLifecycleMutationDao` — create/update/delete used by lifecycle owner
- `ExpenseMaintenanceDao` — backfill/cleanup/debug-only mutations

This does not create perfect compiler isolation in a single module, but it creates a much stronger architectural boundary.

### Add guardrails
- CI grep/detekt rule: fail if lifecycle mutators are called outside allowlisted files.
- Allowlist only:
  - `TransactionLifecycleCoordinator`
  - explicitly approved maintenance/debug files

### Done when
No new feature code can casually call raw expense mutators.

---

## PR2 — Replace manual `SideEffectMode` discipline with explicit APIs
**Priority:** Critical  
**Files:**
- `TransactionLifecycleCoordinator.kt`
- `ManualExpenseRepository.kt`
- `ReviewQueueRepository.kt`
- `NotificationProcessingPipeline.kt`
- `GroupTransactionCoordinator.kt`

### Problem
Current model still relies on callers remembering `SideEffectMode.DEFER`. The code itself says this is error-prone.

### Changes
Replace the public “mode flag” API with two explicit paths:

1. `createExpenseStandalone(request)`
- owns its own DB tx
- always dispatches post-commit side effects itself

2. `createExpenseDbOnly(request)`
- DB work only
- returns a small result object:
  - `expenseId`
  - `source`
  - `postCommitActions` or enough data to dispatch later

3. `dispatchPostCommitActions(result)`

### Why
This removes the most important remaining human-footgun in Pipeline 2.

### Extra hardening
- If possible, assert/log when standalone create is called from inside an active transaction.

### Done when
Nested callers cannot accidentally trigger side effects pre-commit.

---

## PR3 — Finish remaining bypass migration
**Priority:** High  
**Files:**
- `ExpenseRepository.kt`
- receipt-link category propagation path
- group cleanup/normalization path
- maintenance/backfill callsites

### Work
Classify every remaining bypass into one of two buckets:

### A. Real business mutation
Must route through lifecycle and emit an event.
Examples:
- receipt-link category propagation if it changes business meaning

### B. Maintenance/system mutation
May stay outside normal lifecycle, but must be explicitly classified and audited.
Examples:
- location backfill
- merchant-key backfill
- system normalization
- debug snapshot restore

### Important design decision
For receipt-link circular dependency:
- either break the cycle so receipt-link can call lifecycle cleanly,
- or create a small event-writing maintenance path with explicit source like `RECEIPT_LINK_SYSTEM`.

Do **not** leave it as a silent ad hoc exception.

### Done when
Every remaining bypass is either removed or formally reclassified with explicit audit semantics.

---

## PR4 — Make `updateBusinessTaxFields()` truthful
**Priority:** High  
**Files:**
- `TransactionLifecycleCoordinator.kt`
- `Expense` entity/schema if needed
- snapshots / request DTOs / tests

### Current gap
The method is restore-guarded, but three accepted parameters are no-ops.

### Recommended decision
For immediate stability, choose one path and stop straddling both:

### Path A — Narrow now, honest now
- rename to something like `updateBusinessFlags()`
- keep only fields that truly persist today
- deprecate/remove no-op params

### Path B — Full feature completion
If those tax fields are real product requirements:
- add entity columns
- add Room migration
- add create/update/snapshot/export support
- add tests

### Recommendation
**Path A first** unless those fields are needed immediately.  
A truthful smaller API is safer than a broader misleading one.

### Done when
The public method no longer claims to mutate fields it ignores.

---

## PR5 — Harden implemented fixes
**Priority:** High

### P2-P1-02 failed-create event visibility
Keep the current event set, but add proof and failure policy:
- Room tests for:
  - `CREATE_ATTEMPTED`
  - `CREATE_VALIDATION_FAILED`
  - `CREATE_INSERT_CONFLICT`
  - `CREATE_DUPLICATE_SKIPPED`
- Decide policy for event insert failure:
  - best-effort for attempt/validation-only events is okay
  - but created/updated/deleted events inside business mutations must stay atomic with the write

### P2-P1-03 strict external idempotency
Add DB-backed tests covering:
- first create succeeds
- retry with same external ID returns duplicate with existing ID
- returned ID matches actual persisted row
- no extra row created

### P2-P1-04 debug/restore paths
Keep debug-only bypasses, but harden them:
- tests for release-build rejection
- tests for restore barrier blocking
- optional explicit debug event source if you want recoverable audit

### Money snapshot regression checks
The old debug report flagged home-currency issues; current create path now reads `homeCurrency()` instead of hardcoded EUR. Add regression tests so this does not slip back.

---

## PR6 — Expand lifecycle audit ergonomics
**Priority:** Medium  
**Files:**
- `TransactionEventDao.kt`
- debug/admin viewer code if any

### Add queries for
- `dedupeKey`
- `source`
- date range
- `expenseId + eventType`
- recent events by source

### Why
The event model is strong; the query surface is still thinner than the model.

### Done when
You can answer:
- why a row was created
- why it was considered duplicate
- what updated it
- which source mutated it
without manual DB spelunking.

---

## PR7 — Build the real DB contract suite
**Priority:** Critical for closure  
**Files:** new integration tests

### Add
- `TransactionLifecycleDbContractTest`
- `StrictExternalIdIdempotencyTest`
- `NestedTransactionPostCommitTest`
- `LifecycleUpdateEventParityTest`
- `RestoreModeLifecycleBlockTest`
- `BusinessFlagsUpdateTest`
- `CurrencySnapshotLifecycleTest`
- `LifecycleBypassGuardTest`

### Minimum scenario test
Create one end-to-end Room test that does:
1. create expense
2. duplicate create
3. merchant update
4. category update
5. ownership update
6. delete

Assert:
- row counts
- exact event types
- before/after snapshots
- no side effects before commit
- restore mode blocks writes
- duplicate returns correct existing ID

### Done when
Pipeline 2 behavior is proven against real Room transactions and indexes, not just mocks.

---

## Closure criteria by issue

- **P2-P1-01:** method is restore-guarded **and** semantically truthful
- **P2-P1-02:** failed-create events proven by Room tests
- **P2-P1-03:** strict retry/idempotency proven with real DB conflict path
- **P2-P1-04:** debug-only bypasses tested and explicitly scoped
- **P2-P1-05:** no unauthorized raw expense mutation paths remain

---

## My recommended execution order
1. PR0 inventory
2. PR7 test harness skeleton
3. PR1 DAO boundary enforcement
4. PR2 explicit standalone/db-only create APIs
5. PR3 remaining bypass migration
6. PR4 truthful business/tax API
7. PR5 hardening of implemented fixes
8. PR6 audit ergonomics + docs sync

---

## Sources
- Tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Pipeline 2 debug report:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/debugging/pipeline-2-transaction-lifecycle-debug-report.md
- Coordinator:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- Repository KDoc + debug paths:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
- DAO mutation surface:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- Event DAO/model:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
- Nested-caller samples:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- Current test file:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt