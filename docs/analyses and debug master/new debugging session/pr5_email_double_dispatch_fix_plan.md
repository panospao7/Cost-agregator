# PR 5 — Email Double-Dispatch Fix

## Assumptions
PR1–PR4 are merged:
- typed side-effect outcomes exist
- transaction planner exists
- receipt planner exists
- post-commit runner exists
- source-link plumbing for email receipt provenance already exists

If PR3/PR4 APIs are not present yet, add them first.

---

## Baseline bug in current code
`ReceiptLifecycleCoordinator.processEmailReceipt()` still:
1. saves receipt + email source + provenance link
2. creates expense with deferred side-effects
3. records both created and duplicate-linked expense IDs in one list
4. after commit runs `dispatchAfterSave(saved)`
5. after commit runs `dispatchPostCreationSideEffects(...)` for every expense ID

That causes:
- created-expense actions being replayed for duplicate-linked existing expenses
- two independent post-commit execution sites in the same email flow
- receipt matching potentially re-running after the receipt was already explicitly linked

---

## Goal
Make the email flow have exactly one post-commit dispatch path:
- receipt side-effects once
- transaction side-effects once per newly created expense only
- no created-expense actions for duplicate-linked existing expenses
- no dispatch logic in `EmailReceiptIngestionService`

---

## Files to modify

### Core
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/EmailReceiptProcessResult.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`

### Likely helpers
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/EmailReceiptPostCommitPlan.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectPlanContext.kt` if the receipt planner needs an “already linked” flag

### Tests
- `ReceiptLifecycleCoordinatorTest.kt`
- `EmailReceiptIngestionServiceTest.kt`
- `ReceiptLinkServiceTest.kt`
- `ReceiptSideEffectPlannerTest.kt` or dispatcher compatibility tests

---

## Required design change
Split email outcomes into three buckets:

1. `createdExpenseIds`
2. `linkedExistingExpenseIds`
3. `receiptPostCommitActions` + `transactionPostCommitActions`

Important:
- only `createdExpenseIds` produce transaction-created side-effect batches
- `linkedExistingExpenseIds` are reporting/linking only
- the final runner call must happen once, after the outer transaction commits

If legacy compatibility needs `expenseIds`, keep it as a union or derived getter, but do **not** use it for dispatch.

---

## `ReceiptLifecycleCoordinator.processEmailReceipt()` changes

### Inside the transaction
For each email receipt:
- save receipt
- save email source
- write email→receipt provenance link
- collect receipt side-effects in a plan/batch
- create expense using the DB-only transaction API from PR3
- if `Created`, collect that expense’s transaction action batch
- if `DuplicateSkipped`, add the existing expense ID to `linkedExistingExpenseIds` only
- link receipt to expense with `writeSourceLink = false`

### Post-commit
Replace:
- `sideEffectDispatcher.dispatchAfterSave(saved)`
- the loop `for (expenseId in expenseIds) dispatchPostCreationSideEffects(...)`

with:
- one combined `postCommitActionRunner.run(combinedBatch)`

Combined batch must include:
- receipt side-effect batch
- transaction side-effect batches for **created** expenses only

### Receipt-side double-dispatch guard
When the email receipt has already been linked in the same flow:
- receipt matching must skip or return `Skipped(ALREADY_PROCESSED)`
- the planner/context should know the receipt is already linked
- do not allow the receipt save side-effects to re-match and re-link the same receipt

This is the key fix for the receipt-side double-dispatch path.

---

## `EmailReceiptProcessResult.kt` changes
Add explicit semantic fields:
- `createdExpenseIds: List<Long>`
- `linkedExistingExpenseIds: List<Long>`

Optional:
- keep `expenseIds` as a compatibility union only

This prevents future callers from treating linked-existing expenses as newly created ones.

---

## `EmailReceiptIngestionService.kt` changes
Keep it as a thin parser/delegate layer only:
- parse email
- hash message ID
- call `ReceiptLifecycleCoordinator.processEmailReceipt(...)`
- emit diagnostics
- return result

Remove or deprecate any unused constructor dependencies that would allow the service to dispatch post-commit work itself.

Do **not**:
- call any side-effect runner here
- call `dispatchPostCreationSideEffects`
- call receipt dispatch logic directly

---

## Context extension for receipt planner
If needed, extend the receipt side-effect context with:
- `processingOrigin = EMAIL_RECEIPT`
- `alreadyLinkedExpenseIds`
- `linkedWithinSameTransaction = true`

This allows the planner to:
- keep item categorization if appropriate
- skip receipt transaction matching when the receipt already has an explicit link
- avoid duplicate `RECEIPT_LINKED`/match actions

---

## Tests

### Email created-expense path
- created expense dispatches once
- receipt actions and transaction actions are combined once
- no extra transaction dispatch loop runs

### Email duplicate-linked path
- duplicate existing expense is linked
- no created-expense actions run
- receipt still succeeds
- no replay of `dispatchPostCreationSideEffects`

### Receipt already linked path
- receipt matching is skipped or marked `ALREADY_PROCESSED`
- no second link attempt happens
- no duplicate receipt action execution

### Service-level tests
- `EmailReceiptIngestionService` does not dispatch any post-commit work
- it only delegates and returns outcome

### Rollback tests
- if the transaction rolls back, no post-commit actions run
- if linking fails, no actions run
- duplicate early-exit path runs no actions

---

## Acceptance criteria
PR5 is done when:

1. Email receipt processing has a single post-commit execution site.
2. Newly created expenses get transaction side-effects exactly once.
3. Duplicate-linked existing expenses do not get created-expense side-effects.
4. Receipt post-save side-effects do not re-match a receipt already linked in the same flow.
5. `EmailReceiptIngestionService` cannot independently dispatch side effects.
6. The email flow still returns created vs linked-existing IDs clearly.
7. Rollback/duplicate paths run no actions.

---

## Implementation order
1. Add explicit created vs linked-existing result buckets.
2. Update `ReceiptLifecycleCoordinator.processEmailReceipt()` to build one combined post-commit plan.
3. Remove the final per-expense `dispatchPostCreationSideEffects` loop.
4. Add the “already linked” guard to receipt planning.
5. Trim `EmailReceiptIngestionService` down to a pure adapter.
6. Add regression tests for created, duplicate-linked, and already-linked paths.
7. Run grep audit to ensure no email path still calls post-create dispatch directly.

---

## Audit checks
These should be true after PR5:
- no production email path calls `dispatchPostCreationSideEffects`
- no email path treats `linkedExistingExpenseIds` as created expenses
- no receipt-side action re-links the same email receipt twice
- the service layer has no post-commit ownership

---

## Sources checked
- Current baseline commit:  
  https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b
- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `ReceiptLinkService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- Global side-effect contract doc:  
  `global_side_effect_dispatch_contract_plan.md`