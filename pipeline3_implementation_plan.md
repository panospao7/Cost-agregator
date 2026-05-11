# Pipeline 3 implementation plan — Receipt Capture / OCR / Email

## Goal
Move Pipeline 3 from **“improved but partial”** to **“single-owner, atomic, privacy-safe, DB-proven”**.

## Execution order
1. **PR1 — Fix receipt creation boundary + timestamps**
2. **PR2 — Make receipt save/dedupe/event truly atomic**
3. **PR3 — Remove legacy expense/link bypasses**
4. **PR4 — Finish email ownership + privacy closure**
5. **PR5 — Harden matching worker + restore behavior**
6. **PR6 — Close stale tracker items and remaining TODOs**
7. **PR7 — Add DB contract tests + docs sync**

---

## PR1 — Fix creation boundary + timestamps
**Priority:** Critical  
**Files:** `ScannedReceipt.kt`, `ScannedReceiptDao.kt`, `ReceiptRepository.kt`, `ReceiptLifecycleCoordinator.kt`

### Why
`createdAt=0` is still possible in some paths. Parse-success now stamps `createdAt`, but parse-failure/manual paths still need closure. Current coordinator “repairs” bad timestamps after insert; that is not the right boundary.

### Changes
- Add one shared factory/helper: `buildNewReceipt(now, ...)`
- Every insert path must set:
  - `createdAt = now`
  - `updatedAt = now`
- Remove “repair after insert” as primary strategy; keep only as defensive assertion.
- Change `ScannedReceiptDao.insert()` away from broad `REPLACE` to explicit lifecycle semantics (`ABORT` + handled outcome, or repository wrapper).
- Add guard: no persisted `ScannedReceipt` may have `createdAt <= 0`.

### Done when
No camera/OCR/manual/email/bank-statement path can persist a receipt with zero timestamps.

---

## PR2 — Make save/dedupe/event truly atomic
**Priority:** Critical  
**Files:** `ReceiptLifecycleCoordinator.kt`, `ReceiptRepository.kt`, duplicate detector code

### Why
`processReceiptInput()` still calls `receiptRepository.processReceipt()` first, so the row can exist before final lifecycle decisions. Exact-hash duplicate flow is especially risky.

### Changes
- Split `ReceiptRepository.processReceipt()` into **OCR/parse only** returning an unsaved draft.
- Make `ReceiptLifecycleCoordinator` the **single owner** of:
  - dedupe
  - insert/update
  - review creation
  - receipt events
- Ensure exact-hash duplicate is resolved **before insert**.
- If post-parse duplicate is detected after a provisional row exists, clean it up explicitly in the same transaction.
- Keep side effects strictly **post-commit**.

### Done when
No “ghost” duplicate receipt can be inserted and then ignored.

---

## PR3 — Remove legacy expense/link bypasses
**Priority:** High  
**Files:** `ReceiptRepository.kt`, `ReceiptLifecycleCoordinator.kt`, `ReceiptLinkService.kt`

### Changes
- Delete or hard-block legacy `ReceiptRepository.createExpenseFromReceipt()`.
- Route all receipt→expense creation through the coordinator atomic path only.
- Restrict `insertReceipt()`, `deleteReceipt()`, `clearAllScannedReceipts()` to maintenance/internal use.
- Replace `ReceiptLinkService` direct `expenseDao.updateCategory(...)` with a small port/service owned by transaction lifecycle.
- Add CI allowlist/grep guard for raw receipt/expense mutation bypasses.

### Done when
No normal business flow can bypass receipt lifecycle or transaction lifecycle.

---

## PR4 — Finish email ownership + privacy closure
**Priority:** Critical  
**Files:** `EmailReceiptIngestionService.kt`, `ReceiptLifecycleCoordinator.kt`, `EmailReceiptSource.kt`, privacy settings/retention files

### Why
The service still has a TODO to delegate to the coordinator, and raw `messageId` is still persisted.

### Changes
- Thin `EmailReceiptIngestionService` to:
  - provider detect
  - parse email
  - delegate to `ReceiptLifecycleCoordinator.processEmailReceipt(...)`
- Replace raw `messageId` storage with deterministic hash fields.
- Stop using raw `sourceFingerprint = messageId`.
- Add explicit email privacy/retention policy if still missing.
- Route low-confidence email parses to `PendingReview`, not auto-create.

### Done when
Email receipt processing has one owner and no raw message IDs persist.

---

## PR5 — Matching worker + restore hardening
**Priority:** High  
**Files:** `ReceiptMatchingWorker.kt`, `WorkerExecutionGuard.kt`, matching persistence methods

### Changes
- Add `executionGuard.checkpoint(...)` inside the worker loop.
- Ensure suggested matches persist:
  - `suggestedExpenseId`
  - `matchStatus = SUGGESTED`
  - `matchConfidence`
- Emit durable diagnostics for:
  - auto-match success
  - suggestion saved
  - link failure
  - restore-blocked
- Add mid-run restore flip tests.

### Done when
Matching cannot keep mutating after restore flip, and suggestions are fully persisted.

---

## PR6 — Close stale tracker/TODO items
**Priority:** High

### Items
- **P3-P1-03:** mostly implemented; prove with DB tests.
- **P3-P1-06:** tracker is stale; current problem is weak insert semantics, not IGNORE/0. Rewrite and fix.
- **P3-P1-07:** remove remaining hardcoded `"EUR"` fallbacks; always use `homeCurrency()`.
- **P3-P1-08:** parse-failure classification is partly fixed; lock it down with tests.
- **P3-P1-09:** audit batch import entrypoint so actionable reviews are always created where intended.
- **P3-P1-10:** re-audit bank-statement dedupe parity versus legacy behavior before declaring fixed.

---

## PR7 — DB contract suite + docs sync
**Tests to add**
- `ReceiptLifecycleDbContractTest`
- `ReceiptDuplicateCleanupTest`
- `ReceiptTimestampPolicyTest`
- `ReceiptExpenseAtomicityTest`
- `EmailReceiptPrivacyAndDedupeTest`
- `ReceiptMatchingPersistenceTest`
- `ReceiptRestoreModeIntegrationTest`

## Closure criteria
- no zero timestamps
- no pre-insert/ghost duplicate flaw
- no legacy receipt→expense helper in business paths
- email lifecycle single-owned + hashed IDs
- matching persisted + restore-safe
- tracker updated only after green DB tests

## Sources
- Tracker: https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `ReceiptLifecycleCoordinator.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
- `ReceiptLinkService.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `ScannedReceipt.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt
- `ScannedReceiptDao.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt
- `ReceiptMatchingWorker.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
- `WorkerExecutionGuard.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
- `EmailReceiptIngestionService.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `EmailReceiptSource.kt`: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt