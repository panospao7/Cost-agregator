# Pipeline 3 — Receipt Capture / OCR / Email Audit

Repository: `panospao7/Cost-agregator`  
Pinned commit reviewed: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Method: browser/source audit against the pinned GitHub commit. I could not run local `rg`, Gradle, or tests in this environment, so execution results are not claimed.

## 1. Commit / docs reconciliation

The pinned GitHub page identifies commit `83b798e` and shows the full SHA context for the requested commit【turn2view0†L171-L is clear status drift:

- `PIPELINE_3_CONSOLIDATED_ISSUES.md` still says P3 had partial/TODO/open items, including P3-P1-09, P3-P1-10, and NEW-P3-005..008.
- `PIPELINE_ISSUES_MASTER_TRACKER.md` says all listed P3 old and new issues are fixed.
- Actual code is mixed: many fixes are present, but I found remaining correctness/architecture gaps below.

The legal architecture requires receipt processing through `ReceiptLifecycleCoordinator`, link/unlink through `ReceiptLinkService`, match mutation through `ReceiptMatchLifecycleService`, and forbids direct `ScannedReceipt.expenseId` updates or receipt mutation without events.

## 2. High-priority findings

### P3-AUDIT-001 — Manual match approval/clear bypasses `ReceiptLinkService`

Severity: P1  
Status: Open  
Area: matching/link lifecycle ownership

`ReceiptMatchLifecycleService.approveMatchSuggestion()` directly updates `ScannedReceipt.expenseId`, clears `suggestedExpenseId`, sets `MANUALLY_MATCHED`, and writes `MATCH_APPROVED`.  
`clearMatchForReceipt()` directly clears `expenseId`, sets `UNMATCHED`, and writes `MATCH_CLEARED`.

This violates the legal path: link/unlink must go through `ReceiptLinkService`, which owns the join table, legacy field, warranty/return/item propagation, and events.

Impact:

- No `receipt_expense_links` join row is created on manual approval.
- Warranty, return-window, item-categorization propagation is skipped.
- Source-link/provenance is skipped.
- Later queries that rely on the join table may disagree with `ScannedReceipt.expenseId`.

Recommended fix:

- `approveMatchSuggestion()` should call `ReceiptLinkService.linkReceiptToExpense()` inside the same transaction or expose an in-transaction link primitive.
- `clearMatchForReceipt()` should use `ReceiptLinkService.unlinkReceiptFromExpense()` or an in-transaction unlink primitive.
- Add tests proving manual approve creates a join-table link and clear removes it.

---

### P3-AUDIT-002 — Bank statement receipt insert/status/event is not fully atomic

Severity: P1  
Status: Open  
Area: bank statement lifecycle / atomicity

`BankStatementLifecycleProcessor` inserts/resolves the statement receipt through `ReceiptRecordWriter`, attaches the import run, then writes `RECEIPT_SAVED` afterward. Later, final status is updated with `scannedReceiptDao.update(...)`, and the `PROCESSING_COMPLETE` event is written afterward, not in the same explicit transaction.

Impact:

- Crash/failure between receipt insert and `RECEIPT_SAVED` can leave a receipt without a lifecycle event.
- Crash/failure between status update and `PROCESSING_COMPLETE` can leave state/event drift.
- This contradicts the universal expectation that receipt mutation + event are atomic.

Recommended fix:

- Add a bank-statement lifecycle write method that wraps receipt insert/update + lifecycle event + import-run attach in one `database.withTransaction`.
- Treat `ReceiptRecordWriter` as a low-level helper, not a complete lifecycle boundary unless it also writes events.

---

### P3-AUDIT-003 — PendingReview creation after receipt save is non-atomic and can silently fail

Severity: P1  
Status: Open / regression risk for P3-P1-09  
Area: receipt capture review queue

`processReceiptInput()` inserts the receipt and `RECEIPT_SAVED` event inside a transaction. But the `PendingReview` for `options.createReview` or low confidence is created afterward via `runCatching`, and failure is only logged; the function still returns success.

Impact:

- A low-confidence/batch receipt can be saved successfully but never appear in review.
- There is no durable terminal diagnostic/event for review creation failure.
- The master tracker’s “P3-P1-09 fixed” claim is only partially true.

Recommended fix:

- If review is required, create it in the same transaction as receipt insert, or return a typed partial-failure result with durable diagnostic.
- Add tests: forced `PendingReviewDao.insert()` failure must not silently return success when review is mandatory.

---

### P3-AUDIT-004 — Residual `CancellationException` swallowing via `runCatching`

Severity: P1  
Status: Open  
Area: structured concurrency

Known broad `catch` sites were mostly fixed. Examples:

- `ReceiptSideEffectDispatcher.dispatchAfterSave()` rethrows `CancellationException`.
- Bank statement per-item catch rethrows cancellation before recovery logic.
- `ReceiptLinkService.unlinkReceiptFromExpense()` rethrows cancellation.

But new `runCatching` sites still swallow cancellation:

- `ReceiptLinkService.linkReceiptToExpense()` wraps category propagation in `runCatching { ... }.onFailure { ... }` without rethrowing `CancellationException`.
- `BankStatementLifecycleProcessor` uses `runCatching { recurringExpenseRepository.getByMerchantFuzzy(...) }.getOrNull()` and continues.

Impact:

- Coroutine cancellation can be converted into a normal warning/no-op.
- This undermines the universal cancellation contract despite U-CANCEL being marked fixed.

Recommended fix:

- Replace with a cancellation-safe helper, e.g. `runSuspendCatchingNonCancellation`.
- Add architecture guard coverage for `runCatching` as well as `catch (Exception)`.

---

### P3-AUDIT-005 — Privacy/logging issue remains for category data

Severity: P2  
Status: Partial  
Area: privacy diagnostics/logging

The master tracker says NEW-P3-006 was fixed by redacting PII in Timber calls. However, `ReceiptLinkService` still logs category IDs and category-frequency details during receipt-item category propagation.

This is less severe than raw OCR/email body leakage, but the original issue explicitly mentioned merchant/category logging. If category IDs are user-defined or infer sensitive spending categories, this remains a production telemetry/privacy concern.

Recommended fix:

- Remove category IDs/frequencies from production logs or gate behind debug-only logging.
- Emit safe counts only, e.g. `itemCategoryCount`.

## 3. Old issue verification

| ID | P3 consolidated | Master tracker | Actual code status |
|---|---:|---:|---|
| P3-P0-01 createdAt=0 | Fixed | Fixed | Fixed. Coordinator/email paths use `ReceiptTimestampPolicy.forInsert`; bank statement explicitly sets `createdAt/updatedAt` from `timeProvider`. |
| P3-P1-01 receipt save/update/event atomic | Fixed | Fixed | Partial. Main capture insert+event is transactional, but bank statement insert/status/event are not fully atomic. |
| P3-P1-02 link service lacks restore guard | Fixed | Fixed | Fixed. Link/unlink call `writeBarrier.checkWritesAllowed`. |
| P3-P1-03 matching result not persisted | Fixed | Fixed | Partial. Suggestions/no-match events are persisted, but manual approval directly writes `expenseId` instead of using link service. |
| P3-P1-04 receipt-created expense + link not atomic | Partial | Fixed | Mostly fixed. Deprecated `createExpenseFromReceipt()` is disabled; `createExpenseAndLinkReceipt()` uses DB-only transaction lifecycle and links before post-commit side effects. |
| P3-P1-05 direct repository/DAO bypass | Partial | Fixed | Still open/partial. Direct `ScannedReceipt.expenseId` updates exist in match approve/clear; bank statement direct status update exists. |
| P3-P1-06 insert IGNORE conflict not checked | Fixed | Fixed | Fixed in main paths. `ReceiptInsertResolver.insertOrResolve()` checks insert ID and resolves ignored conflicts. |
| P3-P1-07 hardcoded EUR fallback | Fixed | Fixed | Fixed in reviewed paths. Coordinator fallback is `XXX`, not EUR; repository parses with home currency. |
| P3-P1-08 parse failures as OCR_COMPLETED | Fixed | Fixed | Fixed. Repository catches parser failure, sets `PARSE_FAILED`, returns draft to coordinator. |
| P3-P1-09 batch import pending reviews | TODO in consolidated | Fixed | Partial. Review creation exists, but is post-insert, non-atomic, and failure is swallowed. |
| P3-P1-10 bank statement dedupe weak | TODO in consolidated | Fixed | Mostly fixed. Bank statement checks existing expenses and pending reviews with currency/type/window/tolerance. |

## 4. New issue verification

| ID | Actual status |
|---|---|
| NEW-P3-001 CE swallowed in side-effect dispatcher | Fixed in the named site; CE is rethrown. |
| NEW-P3-002 CE swallowed in bank per-item | Fixed in the named catch; CE is rethrown. Residual `runCatching` issue remains separately. |
| NEW-P3-003 CE swallowed in unlink | Fixed; CE is rethrown. |
| NEW-P3-004 double `attachReceipt` | Appears fixed; only one attach in inserted path and one in duplicate path are visible. |
| NEW-P3-005 post-OCR duplicate race | Fixed for persisted path: duplicate check/update/event are inside `database.withTransaction`; insert conflicts also resolved by resolver. |
| NEW-P3-006 privacy leak | Partial; some redaction exists, but category details are still logged. |
| NEW-P3-007 delete event for missing receipt | Fixed; delete rechecks existence inside transaction before event/write/delete. |
| NEW-P3-008 homeCurrency thread starvation | Partially verified. Email coordinator resolves home currency before DB transaction. I did not verify the claimed timeout wrapper in all currency providers. |

## 5. Matching and worker review

Good:

- `ReceiptMatchingWorker` uses `WorkerExecutionGuard.runGuardedWithContext` before work.
- Auto-match overlap guard exists via `ScannedReceiptDao.claimForAutoMatch`, conditional on `matchStatus IN ('UNMATCHED','SUGGESTED')`.
- Match attempt/no-match/skipped/link-failed durable events exist in `ReceiptMatchLifecycleService`.

Gap:

- Manual approval/clear does not use `ReceiptLinkService`; this is the largest matching correctness issue.

## 6. Email ingestion review

Good:

- `EmailReceiptIngestionService` documents that it delegates all mutation to `ReceiptLifecycleCoordinator.processEmailReceipt`.
- Entry point checks write barrier before parsing/mutating.
- It hashes message ID and sender in front-door diagnostics.
- Coordinator email flow resolves home currency before DB transaction, inserts receipt/email source/event, creates expense through `TransactionLifecycleCoordinator.createExpenseDbOnlyV2`, links receipt, then runs post-commit actions after commit.

Watch item:

- Link service is called from inside the coordinator transaction, but the link service itself opens its own `database.withTransaction`. Confirm Room nested transaction behavior under tests and ensure no side effects run inside rollback-sensitive blocks.

## 7. Bank statement review

Good:

- Write barrier is checked at entry and again before DB write after long OCR/parse.
- Raw OCR text is sanitized using `rawBankStatementStorageMode` before storage.
- Dedupe checks existing approved expenses and pending reviews with currency/type/window/tolerance.
- Per-item review + ledger item insert is transactional.
- Cancellation finalizes run as cancelled and rethrows.

Gaps:

- Statement receipt insert/event and final status/event atomicity are incomplete.
- `runCatching` recurring lookup can swallow cancellation.

## 8. Recommended fix order

1. Fix `ReceiptMatchLifecycleService.approveMatchSuggestion()` and `clearMatchForReceipt()` to use link/unlink lifecycle service.
2. Make bank statement receipt insert/status + lifecycle event atomic.
3. Make mandatory `PendingReview` creation transactional or return a typed partial-failure with durable diagnostic.
4. Add cancellation-safe helper and ban raw `runCatching` in suspend receipt lifecycle code.
5. Remove/gate category detail production logs.
6. Add contract tests:
   - Manual match approval creates `receipt_expense_links`.
   - Clear match removes join-table link and downstream warranty/return/item state.
   - Bank statement saved receipt always has `RECEIPT_SAVED`.
   - Status update and `PROCESSING_COMPLETE` are atomic.
   - Pending review insertion failure is not silently successful.
   - `CancellationException` propagates through `runCatching`-like sites.

## 9. Source index

- Commit page: 
- P3 consolidated tracker: 
- Master tracker P3 section: 
- Universal cancellation tracker: 
- Legal paths: 
- Coordinator receipt/email/delete/create-link code: , 
- Bank statement processor: 
- Link service: , , , 
- Match service: , 
- Matching worker: 
- Insert resolver: 
- Receipt repository parse failure: 
- Auto-match DAO claim: 
- Email ingestion service: