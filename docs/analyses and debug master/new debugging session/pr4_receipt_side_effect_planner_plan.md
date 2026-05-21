# PR 4 — Receipt Side-Effect Planner

## Baseline checked
At `fc002a583674d9e1734412c9df232e41d621549b`:

- `ReceiptLifecycleCoordinator.processReceiptInput()` still calls `sideEffectDispatcher.dispatchAfterSave(updated)` after the receipt save transaction.
- `ReceiptLifecycleCoordinator.processEmailReceipt()` still:
  - saves `ScannedReceipt` + `EmailReceiptSource`
  - writes `ReceiptSourceLink` to `SCANNED_RECEIPT`
  - creates expense with `SideEffectMode.DEFER`
  - links receipt to expense
  - then calls `dispatchAfterSave(saved)` and `dispatchPostCreationSideEffects(...)` after commit
- `ReceiptSideEffectDispatcher` still owns all receipt side-effect behavior in one imperative method:
  - warranty extraction
  - receipt item categorization
  - receipt transaction matching / auto-linking
  - price protection
  - `NoMatch` is currently silent
- `ReceiptLinkService.linkReceiptToExpense(... writeSourceLink = ...)` already owns the functional receipt-expense link, legacy FK propagation, and provenance source-link write.
- Direct-save/email-create paths already pass `writeSourceLink = false`; auto-match currently uses the default `true`.

## Goal
Move receipt post-save logic into a typed receipt planner that returns `PostCommitActionBatch`, so the receipt lifecycle coordinator can run receipt side effects exactly once after commit.

## Non-goals
- No transaction planner changes
- No source-link schema changes
- No bank/import/export changes
- No worker/batch changes
- No UI changes
- No removal of `ReceiptSideEffectDispatcher` yet
- No rewrite of `ReceiptLinkService` ownership

---

## Files to add
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectPlanContext.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectPlannerTest.kt`

Optional helper:
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectActionFactory.kt`

## Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt` only if an explicit helper for auto-match provenance is needed
- tests for `ReceiptLifecycleCoordinator`, `ReceiptSideEffectDispatcher`, `ReceiptLinkService`, and email receipt ingestion

---

## Planner contract
Use PR1 core model:
- `PostCommitAction`
- `PostCommitActionBatch`
- `PostCommitActionRunner`
- `SideEffectCategory.RECEIPT_MATCHING`
- `SideEffectCategory.RECEIPT_ITEM_CATEGORIZATION`
- `SideEffectCategory.WARRANTY`
- `SideEffectCategory.PRICE_PROTECTION`
- `SideEffectTriggerType.RECEIPT_SAVED`
- `SideEffectTriggerType.RECEIPT_LINKED`
- `SideEffectTriggerType.RECEIPT_UNLINKED`

### API
```kotlin
fun planAfterReceiptSaved(receipt: ScannedReceipt, correlationId: String?, causationId: String? = null): PostCommitActionBatch
fun planAfterReceiptLinked(receiptId: Long, expenseId: Long, linkType: String, correlationId: String?, causationId: String? = null): PostCommitActionBatch
fun planAfterReceiptUnlinked(receiptId: Long, expenseId: Long, correlationId: String?, causationId: String? = null): PostCommitActionBatch
```

### Required receipt-saved mapping
- `RETAIL_RECEIPT` + healthy status:
  - warranty extraction
  - item categorization
  - transaction matching
  - price protection
- `EMAIL_RECEIPT`:
  - item categorization only
- `BANK_STATEMENT` / `MANUAL_PLACEHOLDER` / unknown:
  - no automatic receipt side effects
- `OCR_FAILED` / `PARSE_FAILED` / `DUPLICATE_DETECTED`:
  - skip or empty batch

### Idempotency keys
- `receipt:{id}:saved:warranty_extraction`
- `receipt:{id}:saved:receipt_item_categorization`
- `receipt:{id}:saved:receipt_transaction_match`
- `receipt:{id}:saved:price_protection_check`

---

## Integration plan

### 1) `ReceiptLifecycleCoordinator.processReceiptInput()`
Replace direct dispatcher call with:
1. save receipt + lifecycle events in DB transaction
2. build `receiptActions = receiptSideEffectPlanner.planAfterReceiptSaved(updated, correlationId)`
3. after commit, run `postCommitActionRunner.run(receiptActions)`

### 2) `ReceiptLifecycleCoordinator.processEmailReceipt()`
Current risk: it mixes receipt save, expense create, receipt link, and post-commit dispatch, then dispatches transaction actions for every expense ID.

Refactor to:
- collect `receiptActions` for the saved email receipt
- collect `transactionActions` from `TransactionLifecycleCoordinator` for created expense only
- do **not** run create-expense actions for duplicate-existing expense IDs
- combine batches and run once after outer commit

Hard rule:
- direct-save/email-create paths keep `writeSourceLink = false`
- auto-match link actions may keep `writeSourceLink = true` because the link itself is the receipt-side-effect outcome

### 3) `ReceiptLifecycleCoordinator.createExpenseFromReceipt()`
Replace legacy `SideEffectMode.DEFER` path with PR3 transaction APIs.
After linking receipt to expense:
- combine transaction batch + receipt batch
- run once after commit
- no direct `dispatchPostCreationSideEffects(...)`

### 4) `ReceiptSideEffectDispatcher`
Refactor into compatibility wrapper only:
- keep old public method temporarily
- delegate planning to `ReceiptSideEffectPlanner`
- delegate execution to `PostCommitActionRunner`
- preserve existing receipt event semantics during migration

---

## Outcome / event rules
- `NoMatch` must no longer be silent; write a durable skipped/match-not-found event
- `Suggested` match stays `MATCH_SUGGESTED`
- `AutoMatch` stays `RECEIPT_LINKED_TO_EXPENSE`
- failures become typed side-effect failures, not just logs
- `ReceiptEvent` metadata must stay safe:
  - IDs, scores, match type, correlationId
  - no raw OCR, email body, sender, subject, or merchant text

---

## Test plan
### Planner tests
- retail receipt plans all four actions
- email receipt plans item categorization only
- bank statement plans no actions
- OCR/parse failed plans no actions
- no-match becomes skipped, not silent
- suggested match emits safe metadata
- auto-match uses receipt link service correctly

### Coordinator tests
- `processReceiptInput` runs receipt actions once after commit
- `processEmailReceipt` does not double-dispatch created-expense actions
- duplicate-existing email receipt does not run create-expense actions
- `createExpenseFromReceipt` combines receipt + transaction batches once
- rollback does not run receipt actions

### Link-service regression tests
- direct save/email create still pass `writeSourceLink = false`
- auto-match can still write the receipt-expense provenance link
- no duplicate provenance row on direct create

---

## Acceptance criteria
PR4 is done when:
- receipt side effects are planned, not dispatched imperatively
- receipt save and email receipt flows run actions exactly once after commit
- direct-save/email-create and auto-match provenance remain correct
- `NoMatch` becomes durable/skipped instead of silent
- receipt failures are typed and observable
- `ReceiptSideEffectDispatcher` is only a compatibility layer or removed from new callsites

---

## Sources checked
- Current commit:  
  https://github.com/panospao7/Cost-agregator/commit/fc002a583674d9e1734412c9df232e41d621549b
- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
- `ReceiptLinkService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fc002a583674d9e1734412c9df232e41d621549b/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- Global side-effect contract doc: `global_side_effect_dispatch_contract_plan.md`