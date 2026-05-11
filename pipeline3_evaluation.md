# Pipeline 3 — Receipt Capture / OCR / Email evaluation

## Executive verdict

My status call at HEAD:

- **1 issue clearly fixed**
- **2 issues improved but not clean**
- **2 issues still open**
- **1 tracker TODO is partly stale**
- plus **one serious residual lifecycle flaw** still exists outside the narrow tracker wording

So the honest summary is:

> **Pipeline 3 is structurally better, but not closure-ready.**

---

## Status by tracker issue

## P3-P0-01 — Scanned receipts saved with `createdAt = 0`
**Tracker:** TODO ONLY  
**My verdict:** **STILL OPEN and important**

This is still real.

### Evidence
`ScannedReceipt` still defines:
- `createdAt: Long = 0L`
- `updatedAt: Long = 0L`

And in the main OCR/manual paths, `ReceiptRepository.processReceipt()` and `saveManualReceiptRecord()` still construct `ScannedReceipt(...)` **without** explicitly setting `createdAt`/`updatedAt`.

So for:
- camera/gallery OCR success
- OCR parse-failure fallback
- manual placeholder/fallback save

the sentinel-zero risk still exists.

### Important nuance
The newer **email** path in `ReceiptLifecycleCoordinator.processEmailReceipt()` **does** set:
- `createdAt = now`
- `updatedAt = now`

So this bug is no longer universal across all receipt sources. But Pipeline 3 overall still has it.

**Call:** **not fixed**

---

## P3-P1-01 — Receipt save/update/event not atomic
**Tracker:** ✅ fixed  
**My verdict:** **improved, but not fully clean**

### What is genuinely fixed
There is real transactional work now:
- `ReceiptLifecycleCoordinator.processReceiptInput()` wraps the final receipt update + `RECEIPT_SAVED` event in `database.withTransaction`
- `ReceiptLinkService.linkReceiptToExpense()` wraps link insert + receipt update + event in one transaction
- `unlinkReceiptFromExpense()` does the same

So the old “no transaction at all” shape is no longer true.

### Why I still would not call it clean
The lifecycle is still split across owners.

`processReceiptInput()` still starts by calling `receiptRepository.processReceipt(...)`, and **that repository already inserts the receipt row first**.  
Only after that does the coordinator:
- compute hashes
- run duplicate detection
- update status/fingerprints
- write event(s)

So the receipt insert and the later lifecycle/event finalization are still not one unified owner-boundary transaction.

### Serious residual flaw
There is a particularly concerning branch here:

- coordinator calls `receiptRepository.processReceipt(...)`
- then runs exact-hash duplicate detection
- if it finds an `EXACT_HASH` duplicate, it returns the existing receipt

But in the code I reviewed, that return happens **after the new row was already inserted**, and I did **not** see cleanup of that newly inserted duplicate row in that branch.

That means Pipeline 3 may still create a duplicate row and then return the older receipt object.

**Call:** **partial / not stable**

---

## P3-P1-02 — `ReceiptLinkService` lacks restore guard
**Tracker:** ✅ fixed  
**My verdict:** **FIXED and reasonably clean**

This one looks solid.

### Evidence
Both:
- `linkReceiptToExpense(...)`
- `unlinkReceiptFromExpense(...)`

check `restoreMaintenanceMode.isWritesAllowed()` up front and fail closed on blocked writes.

**Call:** **fixed**

---

## P3-P1-03 — Matching result computed but not persisted
**Tracker:** TODO ONLY  
**My verdict:** **tracker is stale; issue is mostly fixed, but not fully proven**

### What improved
In `ReceiptMatchingWorker`:
- `AutoMatch` calls `receiptLinkService.linkReceiptToExpense(...)`
- `Suggested` calls `receiptRepository.saveMatchSuggestion(...)`

And in `ReceiptLinkService`, linking now updates the receipt copy with:
- `expenseId = expenseId`
- `suggestedExpenseId = null`
- `matchStatus = AUTO_MATCHED / MANUALLY_MATCHED`
- `matchConfidence = confidence`
- `updatedAt = now`

That directly fixes the older problem where auto-match links existed but the receipt still looked `UNMATCHED`.

### Why I’m still slightly cautious
I did not re-verify the body/tests of the suggestion persistence path deeply enough to certify it end-to-end at DB level.

So:
- **auto-match persistence looks fixed**
- **suggested-match persistence likely exists**
- **runtime proof is still weak**

**Call:** **mostly fixed / partial+**

---

## P3-P1-04 — Receipt-created expense + link not atomic in convenience paths
**Tracker:** TODO ONLY  
**My verdict:** **STILL OPEN**

This is still real in the deprecated convenience path.

### Evidence
`ReceiptRepository.createExpenseFromReceipt(...)` still:
1. calls `coordinator.createExpense(request)`
2. then calls `receiptLinkService.linkReceiptToExpense(...)`
3. then updates item categorization links

So if expense creation succeeds but linking fails, the expense already exists.

That is exactly the non-atomic shape the tracker was warning about.

### Important nuance
The KDoc says callers have been migrated, so this may be a **reduced blast-radius footgun**, not a widely-used active path.  
But the code still exists and still has the flaw.

**Call:** **not fixed**

---

## P3-P1-05 — Direct repository methods bypass lifecycle
**Tracker:** TODO ONLY  
**My verdict:** **STILL OPEN**

This remains clearly open.

### Evidence
`ReceiptRepository` still exposes public methods like:
- `insertReceipt(receipt)`
- `deleteReceipt(receipt)`
- `clearAllScannedReceipts()`

These now have write-barrier guards, which is good, but they still bypass the coordinator-owned lifecycle boundary.

Also:
- `deleteReceipt(receipt)` deletes image + row directly
- `clearAllScannedReceipts()` deletes assets and rows directly

Those are not equivalent to the richer coordinator path with audit/event ownership.

The file KDoc warns “internal use only”, but there is no hard enforcement.

**Call:** **open**

---

## Important implemented improvements beyond the tracker

## 1. Link integrity is much better than in the old debug report
This is a real improvement.

`ReceiptLinkService` now:
- validates the receipt exists
- validates the expense exists
- handles duplicate link insert failure instead of silently ignoring it
- updates `matchStatus`
- writes `RECEIPT_LINKED_TO_EXPENSE` / `RECEIPT_UNLINKED_FROM_EXPENSE`
- propagates `expenseId` to warranty / return-window / item categorization

That is materially better than the old report state.

### But not perfectly clean
`ReceiptExpenseLink` still has **no DB foreign keys**.  
The entity explicitly documents that referential integrity is enforced at the application layer.

So the service logic is better, but the data model is still weaker than FK-backed integrity.

---

## 2. Email receipt flow is better, but privacy/ownership are not fully closed
This is another real improvement.

### What looks better
`ReceiptLifecycleCoordinator.processEmailReceipt()` now exists and:
- restore-gates writes
- writes receipt + email source + event in a transaction
- creates expenses using `transactionLifecycleCoordinator.createExpense(..., SideEffectMode.DEFER)`
- dispatches post-commit side effects after the transaction

That is a good response to the older nested side-effect problem.

### Remaining problems
It still stores raw message-id-like identifiers:
- `sourceFingerprint = messageId`
- `EmailReceiptSource.emailMessageId = messageId`

So the privacy contract is still incomplete.

Also, receipt/email ownership is still more complex than ideal across coordinator/service/repository seams.

**Call:** **improved, not fully closed**

---

## 3. There is still a lifecycle bypass inside linking
Inside `ReceiptLinkService`, receipt-item majority category propagation still does a direct:

- `expenseDao.updateCategory(expenseId, bestCategoryId)`

The code itself labels this as a **deferred design compromise** because of circular dependency concerns.

So even in an improved area, Pipeline 3 still contains a deliberate lifecycle bypass.

---

## 4. Tests are not strong enough to call Pipeline 3 stable
This is one of the biggest reasons I won’t call it stable.

`ReceiptLifecycleCoordinatorTest` is still basically narrow mock coverage:
- happy path validation/process call
- validation failure

I did **not** see strong DB-backed contract coverage for:
- duplicate cleanup behavior
- createdAt stamping
- link atomicity
- email receipt flow
- restore-blocked writes
- auto-match/suggested-match persistence
- receipt event audit correctness

So even when the code direction is good, the **proof is weak**.

---

## Final scorecard

If I rewrote the Pipeline 3 table for current HEAD, I’d mark it roughly like this:

- **P3-P0-01 createdAt = 0 sentinel:** **⚠ OPEN**
- **P3-P1-01 save/update/event atomicity:** **⚠ PARTIAL**
- **P3-P1-02 restore guard in link service:** **✅ FIXED**
- **P3-P1-03 matching result persistence:** **⚠ MOSTLY FIXED / tracker stale**
- **P3-P1-04 expense+link atomicity in convenience paths:** **⚠ OPEN**
- **P3-P1-05 direct repository bypasses lifecycle:** **⚠ OPEN**

And outside the tracker:
- **exact-hash duplicate branch after pre-insert save:** **serious remaining flaw**
- **email privacy/raw identifier storage:** **partial**
- **tests:** **not strong enough for “stable”**

---

## Bottom-line answer

### Are Pipeline 3 issues fixed?
**Some are.**  
There are real improvements, especially in:
- restore guarding
- link lifecycle handling
- auto-match state updates
- email post-commit side-effect handling

### Are they clean and stable?
**No.**

Best summary:

> **Pipeline 3 has good refactor progress, but it still has open lifecycle/timestamp gaps and at least one serious duplicate-flow weakness, so I would not declare it clean or stable yet.**

---

## Sources

- Master tracker  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Original Pipeline 3 debug report  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/debugging/pipeline-3-receipt-lifecycle-debug-report.md
- `ReceiptLifecycleCoordinator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
- `ReceiptLinkService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `ReceiptMatchingWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
- `ScannedReceipt.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt
- `ReceiptExpenseLink.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt
- `ReceiptExpenseLinkDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt
- `ReceiptLifecycleCoordinatorTest.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt