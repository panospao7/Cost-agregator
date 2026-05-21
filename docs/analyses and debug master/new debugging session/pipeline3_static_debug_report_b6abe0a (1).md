# Pipeline 3 Static Debug Report — Receipt Capture / OCR / Email / Bank Statement

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 3 is **substantially improved**, but it is **not closed**.

The refactor added real lifecycle infrastructure:

```text
ReceiptLifecycleCoordinator
ReceiptLinkService
ReceiptSideEffectDispatcher
ReceiptDuplicateDetector
ReceiptInputValidator
BankStatementLifecycleProcessor
ReceiptEvent
ReceiptExpenseLink
RawContentSanitizer integration
```

But current code still has several correctness holes. The biggest finding is that the tracker marks some Pipeline 3 items as fixed when the code is only **partially fixed**.

Highest remaining user-impact risks:

1. **Receipt persistence is still two-phase**: `ReceiptRepository.processReceipt()` inserts a receipt before `ReceiptLifecycleCoordinator` writes final metadata/events.
2. **Duplicate batch scans can create or leave pending reviews for duplicate/ghost receipts.**
3. **Deprecated `ReceiptRepository.createExpenseFromReceipt()` can still create an expense and return success even if receipt linking fails.**
4. **Email receipt source insert conflict is mishandled when `emailMessageId` conflicts but fingerprint lookup misses.**
5. **Valid files with null provider MIME can pass validator but fail OCR because OCR service does not reuse validator MIME fallback.**
6. **Bank statement import has stronger dedupe now, but receipt/event/review/status writes are not one atomic import unit.**
7. **Receipt link service still directly mutates expense category via DAO, bypassing transaction lifecycle events/side effects.**
8. **Privacy/raw-storage is improved, but debug export and side-effect behavior under sanitized OCR modes remain unclear.**

Current status: **yellow/orange**. Core paths are much better, but Pipeline 3 still needs lifecycle hardening before being called stable.

---

# Sources checked

- Commit page:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Prior Pipeline 3 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-3-receipt-capture-ocr-email-debug-report.md

- Current code:
  - `ReceiptLifecycleCoordinator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
  - `ReceiptRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
  - `ReceiptLinkService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
  - `ReceiptSideEffectDispatcher.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
  - `BankStatementLifecycleProcessor.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
  - `ReceiptInputValidator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptInputValidator.kt
  - `ReceiptOcrService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt
  - `ScannedReceipt.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt
  - `ScannedReceiptDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt
  - `EmailReceiptSource.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt
  - `EmailReceiptDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt

---

# 1. Tracker reconciliation

Master tracker currently says Pipeline 3:

| ID | Tracker status |
|---|---|
| P3-P0-01 | fixed |
| P3-P1-01 | fixed |
| P3-P1-02 | fixed |
| P3-P1-03 | TODO |
| P3-P1-04 | partial |
| P3-P1-05 | partial |
| P3-P1-06 | TODO |
| P3-P1-07 | TODO |
| P3-P1-08 | TODO |
| P3-P1-09 | TODO |
| P3-P1-10 | TODO |

My status after checking current code:

| ID | My status | Reason |
|---|---:|---|
| P3-P0-01 | **Mostly fixed / partial caveat** | Main paths now set/repair `createdAt`, but entity still defaults to `0L`, and direct insert APIs can still persist bad timestamps if callers pass bad objects. |
| P3-P1-01 | **Partial, not fixed** | Metadata update + event insert are transactional, but receipt row is still inserted earlier by `ReceiptRepository.processReceipt()`. Crash between insert and coordinator update leaves ghost/lifecycle-incomplete receipt. |
| P3-P1-02 | **Mostly fixed** | `ReceiptLinkService.link/unlink` now guards restore mode. Caveat: it uses `RestoreMaintenanceMode` directly, not the shared `DatabaseWriteBarrier`. |
| P3-P1-03 | **Mostly fixed** | `ReceiptSideEffectDispatcher` now persists `AUTO_MATCHED`/`SUGGESTED` results. Caveat: no durable `MATCH_NOT_FOUND` event. |
| P3-P1-04 | **Partial** | `ReceiptLifecycleCoordinator.createExpenseFromReceipt()` is now closer to atomic, but deprecated `ReceiptRepository.createExpenseFromReceipt()` still has unsafe behavior. |
| P3-P1-05 | **Partial / still risky** | Some direct methods have write barrier, but direct delete/match methods still bypass lifecycle/events and several methods remain public. |
| P3-P1-06 | **Partial** | Many insert callers check `>0`, but DAO remains `IGNORE`, public, and email-source conflict handling is incomplete. |
| P3-P1-07 | **Mostly fixed** | Main OCR parse now passes home currency. Some hardcoded EUR fallbacks/default params remain. |
| P3-P1-08 | **Partial** | `PARSE_FAILED` status is now preserved, but explicit `PARSE_FAILED` event is not written. |
| P3-P1-09 | **Partial** | Batch path now requests review creation, but review creation happens inside repository before final lifecycle dedupe/events. |
| P3-P1-10 | **Partial** | Bank statement dedupe now checks expenses + pending reviews, but import writes are not atomic and races remain. |

Medium issues from prior report:

| Issue | My status |
|---|---:|
| P2-12 MIME fallback | **Partial** — validator has extension fallback, OCR service does not reuse it. |
| P2-13 file-size limit mismatch | **Fixed** — shared 50 MB limit. |
| P2-14 asset filename collision | **Fixed** — OCR image save uses UUID. |
| P2-15 PDF truncation warning only logged | **Mostly fixed** for receipt scan; bank statement path still weak. |
| P2-16 raw OCR/email policy | **Partial** — write-time sanitization exists, debug export/side-effect behavior still weak. |
| P2-17 side-effect failures only logged | **Mostly fixed** — dispatcher writes `SIDE_EFFECT_FAILED`. |
| P2-18 link service direct category mutation | **Open** — direct `expenseDao.updateCategory()` remains. |
| P2-19 email duplicate message-ID only | **Partial** — text/semantic dedupe added, but race/source conflict caveats remain. |
| P2-20 sparse receipt events | **Partial** — more events exist, but entry/validation/parse/no-match/asset-delete gaps remain. |

---

# 2. Original issue evaluation

## P3-P0-01 — Scanned receipts saved with `createdAt = 0`

### Current state

Improved.

Main scan paths now set `createdAt = timeProvider.now()`, and the coordinator repairs `createdAt == 0L` when updating receipt metadata.

Also:

- manual fallback sets `createdAt`,
- parse-failure receipt sets `createdAt`,
- email receipt sets `createdAt` and `updatedAt`,
- bank statement lifecycle sets both.

### Remaining caveat

`ScannedReceipt.createdAt` still defaults to `0L`, and `ReceiptRepository.insertReceipt(receipt)` accepts a caller-provided `ScannedReceipt` without enforcing timestamp integrity.

Also, several first-phase inserts set `createdAt` but leave `updatedAt = 0L` until the coordinator update happens. If a crash occurs before coordinator update, a row can still have `updatedAt = 0`.

### Classification

- **Original P0 mostly fixed.**
- Remaining issue is **data-integrity hardening**.

### Fix strategy

Add a single insert helper:

```kotlin
fun ScannedReceipt.withRequiredTimestamps(now: Long): ScannedReceipt
```

Apply it in:

- `ReceiptRepository.processReceipt`,
- `ReceiptRepository.saveManualReceiptRecord`,
- `ReceiptRepository.insertReceipt`,
- `ReceiptLifecycleCoordinator.saveEmailReceipt`,
- `BankStatementLifecycleProcessor`.

Acceptance:

```sql
SELECT COUNT(*) FROM scanned_receipts
WHERE createdAt = 0 OR updatedAt = 0;
```

must be zero after all normal pipeline operations.

---

## P3-P1-01 — Receipt save/update/event not truly atomic

### Current state

Partially fixed, but tracker overstates it.

Good:

- `ReceiptLifecycleCoordinator.processReceiptInput()` wraps final `scannedReceiptDao.update(updated)` + `ReceiptEvent(RECEIPT_SAVED)` in `database.withTransaction`.
- It also writes `OCR_FAILED` and `PDF_PARTIAL` inside that same transaction.

Still not truly atomic:

- `ReceiptRepository.processReceipt()` inserts the `ScannedReceipt` before the lifecycle coordinator finalizes it.
- Pending review may also be inserted inside repository before final coordinator dedupe/status/event work.
- If app/process crashes between repository insert and coordinator transaction, DB can contain:

```text
scanned_receipts row exists
sourceType/documentType may still be UNKNOWN
processingStatus may still be CAPTURED/PARSE_FAILED
no final RECEIPT_SAVED event
maybe pending review already exists
```

Fallback catch path also inserts manual receipt and then event outside an explicit transaction.

### User impact

User may see a receipt row that is incomplete or not traceable in `receipt_events`. Debugging “scan completed but no receipt status/event” remains possible.

### Fix strategy

Refactor repository OCR/parse into a draft-producing API.

Preferred:

```kotlin
data class ProcessedReceiptDraft(
    val imagePath: String?,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,
    val parsedTaxAmount: Double?,
    val currency: String,
    val confidence: Float,
    val processStage: ReceiptProcessStage,
    val failureReason: String?,
    val pagesProcessed: Int?,
    val totalPages: Int?
)
```

Then only the coordinator inserts:

```kotlin
database.withTransaction {
    val receiptId = scannedReceiptDao.insert(receipt)
    receiptEventDao.insert(RECEIPT_SAVED / OCR_FAILED / PARSE_FAILED)
    if (options.createReview) pendingReviewDao.insert(review)
}
```

Minimum patch:

- Add a `RECEIPT_CAPTURED` event immediately when repository inserts.
- Add a cleanup/recovery worker for incomplete receipts.
- Make fallback insert+event transactional.

---

## P3-P1-02 — `ReceiptLinkService` lacks restore guard

### Current state

Mostly fixed.

`ReceiptLinkService.linkReceiptToExpense()` and `unlinkReceiptFromExpense()` now check restore mode before writes.

### Remaining caveat

The rest of the codebase increasingly uses `DatabaseWriteBarrier`. `ReceiptLinkService` checks `RestoreMaintenanceMode` directly. This works for restore mode, but is not the shared universal write contract.

### Fix strategy

Inject and use `DatabaseWriteBarrier`:

```kotlin
writeBarrier.checkWritesAllowed("ReceiptLinkService.linkReceiptToExpense")
writeBarrier.checkWritesAllowed("ReceiptLinkService.unlinkReceiptFromExpense")
```

Keep `RestoreMaintenanceMode` only if there is a reason beyond the write barrier.

---

## P3-P1-03 — Matching result computed but not persisted

### Current state

Mostly fixed.

`ReceiptSideEffectDispatcher.dispatchAfterSave()` now:

- calls `receiptTransactionMatcher.findBestMatch(receipt)`,
- auto-links high-confidence matches through `ReceiptLinkService`,
- saves medium-confidence suggestions by updating `suggestedExpenseId`, `matchStatus = SUGGESTED`, `matchConfidence`,
- writes `MATCH_SUGGESTED` for suggested matches,
- writes `SIDE_EFFECT_FAILED` if matching/linking fails.

### Remaining caveats

- `NoMatch` writes no event, so the audit trail cannot distinguish “matcher ran and found nothing” from “matcher never ran”.
- Auto-match success depends on `ReceiptLinkService` writing `RECEIPT_LINKED_TO_EXPENSE`, which is fine but not matcher-specific.
- Matcher side effect uses the saved/sanitized receipt object. If raw OCR storage mode redacts/empties OCR text, matching quality may degrade unless matcher only uses structured parsed fields.

### Fix strategy

Add:

```text
MATCH_ATTEMPTED
AUTO_MATCHED
MATCH_SUGGESTED
MATCH_NOT_FOUND
MATCH_FAILED
```

Use `ReceiptEvent.metadata` to include candidate count, score, and matched expense ID.

---

## P3-P1-04 — Receipt-created expense + link not atomic in convenience paths

### Current state

Partial.

Good:

- `ReceiptLifecycleCoordinator.createExpenseFromReceipt()` now wraps create + link in a DB transaction and uses deferred transaction side effects.
- It links through `ReceiptLinkService`.

Still unsafe:

- `ReceiptRepository.createExpenseFromReceipt()` still exists with `DeprecationLevel.ERROR`.
- In the repository path, after `TransactionLifecycleCoordinator.createExpense()` returns `Created`, it calls `receiptLinkService.linkReceiptToExpense()` but does **not** check the result.
- It returns `Success(expenseId)` even if the receipt link failed.
- It also manually links receipt item categorization after the link call.
- Transaction side effects may run before the receipt link exists because the repository path calls coordinator without deferring side effects.

### User impact

If this deprecated method is reached by suppression/reflection/old code, user can get:

```text
expense created
receipt not linked
receipt items not reliably attached
UI says save succeeded
audit trail incomplete
```

### Fix strategy

Delete the repository method or make it impossible in release builds.

If keeping temporarily:

```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
if (linkResult.isFailure) throw ...
```

But best fix is:

- only one create-from-receipt API,
- coordinator owns create + link,
- post-commit actions dispatch after outer transaction commits.

---

## P3-P1-05 — Direct repository methods bypass lifecycle

### Current state

Partial.

Some direct write methods now check `DatabaseWriteBarrier`:

- `insertReceipt`
- `updateCategorizationStatus`
- `deleteReceipt`
- `clearAllScannedReceipts`

But they still bypass lifecycle semantics:

- `deleteReceipt(receipt)` deletes image and row without `ReceiptEvent`, without link cleanup, and without asset failure audit.
- `clearAllScannedReceipts()` has no debug guard and no audit.
- `saveMatchSuggestion()`, deprecated `linkReceiptToExpense()`, `approveMatchSuggestion()`, `rejectAllSuggestions()`, and `clearMatchForReceipt()` mutate receipt matching state directly without lifecycle events and without visible write-barrier checks.
- `ReceiptExpenseLink` has no DB FK, so direct deletes can leave orphan links.

### Classification

Actual bug + architectural risk.

### Fix strategy

1. Make unsafe methods `internal` or move to `DebugReceiptRepository`.
2. Replace all direct match mutations with `ReceiptLinkService` / coordinator methods.
3. Add static guard:

```bash
grep -R "scannedReceiptDao\.\(insert\|update\|delete\|deleteAll\)" app/src/main/java
```

Allowed only in:

```text
ReceiptLifecycleCoordinator
BankStatementLifecycleProcessor
ReceiptRepository OCR draft transition only
DataRetentionWorker raw-text purge only
approved debug-only classes
Room migrations
```

4. Add orphan diagnostics:

```sql
SELECT * FROM receipt_expense_links
WHERE receiptId NOT IN (SELECT id FROM scanned_receipts)
   OR expenseId NOT IN (SELECT id FROM expenses);
```

---

## P3-P1-06 — `ScannedReceiptDao.insert()` IGNORE conflicts not checked

### Current state

Partial.

Good:

- Most direct `scannedReceiptDao.insert()` calls now `require(id > 0)`.
- `ReceiptRepository.insertReceipt()` checks the result.
- `saveEmailReceipt()` checks result.
- Bank statement lifecycle checks via repository insert.

Still problematic:

1. `ScannedReceiptDao.insert()` is still public and uses `IGNORE`.
2. There is no central `insertOrResolve()` result type.
3. `ScannedReceipt` has no unique indexes on `imageHash`, `textFingerprint`, `semanticFingerprint`, or `sourceFingerprint`, so `IGNORE` may not protect the most important duplicate dimensions.
4. Email receipt source conflict handling is incomplete:
   - `EmailReceiptSource.emailMessageId` is unique.
   - `insertOrIgnore()` may fail due to message ID conflict.
   - The code then looks up existing row by fingerprint only.
   - If message ID conflicts but fingerprint differs/missing, the newly inserted receipt can remain without an email source row.

### Fix strategy

Create:

```kotlin
sealed interface ReceiptInsertResult {
    data class Inserted(val id: Long) : ReceiptInsertResult
    data class Duplicate(val existingReceiptId: Long, val reason: String) : ReceiptInsertResult
    data class ConflictUnresolved(val reason: String) : ReceiptInsertResult
}
```

Add partial unique indexes where safe:

```text
UNIQUE(imageHash) WHERE imageHash IS NOT NULL
UNIQUE(textFingerprint) WHERE textFingerprint IS NOT NULL
UNIQUE(semanticFingerprint) WHERE semanticFingerprint IS NOT NULL
UNIQUE(sourceFingerprint) WHERE sourceFingerprint IS NOT NULL AND sourceFingerprint != ''
```

For email source insert conflict:

```kotlin
if (sourceId == -1L) {
    val existing =
        emailReceiptDao.getByFingerprint(fingerprint)
        ?: sanitizedMessageId?.let { emailReceiptDao.getByMessageId(it) }

    if (existing != null) {
        throw DuplicateReceiptException(existing.receiptId)
    } else {
        throw IllegalStateException("EmailReceiptSource insert conflict unresolved")
    }
}
```

---

## P3-P1-07 — Currency fallback hardcoded EUR in OCR parse path

### Current state

Mostly fixed.

Good:

- `ReceiptRepository.processReceipt()` resolves home currency and passes it to `receiptParser.parse(...)`.
- Parse-failure and manual fallback use home currency.
- Email receipt uses email currency or home currency.
- Bank parser resolves home currency.

Remaining caveats:

- Some fallback/default values still use `"EUR"`.
- Deprecated create-from-receipt APIs still default `currency = "EUR"`.
- Pre-OCR duplicate shortcut returns a dummy parsed receipt with `"EUR"` even though it mostly returns an existing DB receipt.
- Bank statement receipt-level currency still falls back to `"EUR"` if not resolved.

### Fix strategy

Introduce a no-hardcoded-currency rule:

```kotlin
interface CurrencyFallbackProvider {
    suspend fun homeOrUnknown(): String
}
```

Use home currency or nullable/unknown state everywhere. Add static guard:

```bash
grep -R "\"EUR\"" app/src/main/java/com/yourname/expensetracker | grep -i receipt
```

---

## P3-P1-08 — Parse failures classified as `OCR_COMPLETED`

### Current state

Partial.

Good:

- `ReceiptRepository.processReceipt()` sets `processingStatus = PARSE_FAILED` when parser throws.
- `ReceiptLifecycleCoordinator.processReceiptInput()` respects existing `PARSE_FAILED` status instead of reclassifying as `OCR_COMPLETED`.

Still missing:

- No explicit `ReceiptEvent(eventType = "PARSE_FAILED")` is written.
- The coordinator always writes `RECEIPT_SAVED`; only OCR failure gets a second failure event.
- Debug/audit still cannot clearly distinguish:
  - OCR succeeded + parser failed,
  - OCR succeeded + no structured fields found.

### Fix strategy

Add process-stage result:

```kotlin
enum class ReceiptProcessStage {
    OCR_FAILED,
    PARSE_FAILED,
    PARSED,
    OCR_COMPLETED_NO_STRUCTURED_FIELDS
}
```

Then event mapping:

```text
PARSE_FAILED -> ReceiptEvent(PARSE_FAILED, errorDetails)
OCR_COMPLETED_NO_STRUCTURED_FIELDS -> ReceiptEvent(OCR_COMPLETED)
PARSED -> ReceiptEvent(PARSED)
```

---

## P3-P1-09 — Batch receipt import no longer creates pending reviews

### Current state

Partial.

Good:

- `ReceiptRepository.processBatch()` now calls `processReceiptInput(... createReview = true)`.
- So batch import is actionable again in the happy path.

Problem:

`createReview` is passed to `ReceiptRepository.processReceipt()`, which creates `PendingReview` **before** the coordinator finishes dedupe/final metadata/events.

This causes two important risks:

1. Post-OCR duplicate detection can return an existing receipt while the newly inserted pending review remains.
2. Pending review creation is not represented as a lifecycle event such as `REVIEW_CREATED`.

### User impact

Batch import can create an actionable review for a duplicate receipt, or leave a review pointing to a receipt that was later deleted as a ghost duplicate.

### Fix strategy

Move review creation out of `ReceiptRepository.processReceipt()` and into the coordinator transaction after dedupe.

Correct order:

```text
OCR/parse draft
dedupe
database.withTransaction {
    insert/update receipt
    insert RECEIPT_SAVED/PARSED event
    if createReview && not duplicate:
        insert PendingReview
        insert REVIEW_CREATED event
}
```

Add cleanup:

```text
If duplicate ghost receipt is deleted, delete/cascade any pending review created for it and delete orphan asset.
```

---

## P3-P1-10 — Bank statement lifecycle dedupe weaker than legacy

### Current state

Improved but partial.

Good:

`BankStatementLifecycleProcessor` now checks:

- existing approved expenses,
- existing pending reviews,
- merchant key / merchant name,
- date window,
- amount tolerance,
- currency,
- transaction type.

This is much better than the tracker suggests.

Remaining issues:

1. Statement receipt insert, receipt event, per-transaction reviews, status update, and processing-complete event are not one atomic import.
2. Per-transaction duplicate check and pending review insert are not wrapped in one DB transaction, so concurrent imports can race.
3. Duplicates skipped are mostly parsing logs/debug data, not durable per-transaction diagnostic events.
4. Legacy `ReceiptRepository.processStatement()` still exists as a public path and has different behavior.
5. No statement import run ledger exists.

### User impact

A crash mid-statement import can leave:

```text
statement receipt saved
some pending reviews created
processing status not updated
PROCESSING_COMPLETE missing
duplicates not durably explained
```

### Fix strategy

Create `BankStatementImportRun` and `BankStatementTransactionImport` tables or reuse pipeline diagnostics.

Minimal patch:

```kotlin
database.withTransaction {
    insert statement receipt
    insert RECEIPT_SAVED
    for each tx:
        check duplicate and insert review atomically
        insert TX_REVIEW_CREATED / TX_DUPLICATE_SKIPPED
    update statement status
    insert PROCESSING_COMPLETE
}
```

Better:

```text
statement_import_runs(id, receiptId, status, startedAt, completedAt, counts)
statement_import_items(runId, txFingerprint, status, reviewId, duplicateEntityId, reason)
```

---

# 3. New/current issues found

## P3-NEW-01 — Batch duplicate scan can leave pending review for ghost/duplicate receipt

### Severity

P1.

### Evidence

Batch import passes `createReview = true`. `ReceiptRepository.processReceipt()` inserts `PendingReview` during the initial receipt insert transaction. Later, `ReceiptLifecycleCoordinator.processReceiptInput()` performs exact/text/semantic duplicate checks. In the exact duplicate branch it deletes the newly inserted receipt row and returns the existing receipt; in the text/semantic duplicate branch it marks the new row `DUPLICATE_DETECTED`.

There is no explicit pending-review cleanup in these duplicate branches.

### User impact

User may see a duplicate review even though the receipt was detected as duplicate. If the receipt row was deleted and there is no FK/cascade, the review may point to a non-existent scanned receipt.

### Fix strategy

Do not create `PendingReview` before coordinator-level dedupe completes.

Short-term patch:

```kotlin
if (duplicateDetected) {
    pendingReviewDao.deleteByScannedReceiptId(newReceiptId)
    assetStore.deleteAsset(newReceipt.imagePath)
}
```

Long-term: move review creation to coordinator transaction after dedupe.

---

## P3-NEW-02 — Ghost duplicate cleanup does not delete persisted asset

### Severity

P1/P2.

### Evidence

When an exact duplicate is detected after OCR, coordinator deletes the newly inserted receipt row:

```text
scannedReceiptDao.delete(receipt)
```

but the OCR service has already saved an image file. I did not see matching asset cleanup in that branch.

### User impact

Duplicate scans can accumulate orphan image files not visible in DB. Backup/restore asset manifest can drift from DB.

### Fix strategy

When deleting ghost duplicate:

```kotlin
database.withTransaction {
    pendingReviewDao.deleteByScannedReceiptId(receipt.id)
    scannedReceiptDao.delete(receipt)
    receiptEventDao.insert(DUPLICATE_DETECTED)
}
assetStore.deleteAsset(receipt.imagePath)
```

Also add orphan asset scanner:

```text
files/receipts/* not referenced by scanned_receipts.imagePath
```

---

## P3-NEW-03 — Email source insert conflict can produce receipt without source row

### Severity

P1.

### Evidence

In `processEmailReceipt()`:

1. Insert `ScannedReceipt`.
2. Build `EmailReceiptSource`.
3. Call `emailReceiptDao.insertOrIgnore(emailSource)`.
4. If insert ignored, lookup only by fingerprint.
5. If fingerprint lookup misses, code continues.

But `EmailReceiptSource.emailMessageId` is unique. Conflict can be caused by message ID, not fingerprint.

### User impact

Email receipt may be saved, and possibly expense-created, without the corresponding `email_receipt_sources` row. Future dedupe/provider analytics become inconsistent.

### Fix strategy

If `insertOrIgnore()` fails, resolve by both fingerprint and message ID:

```kotlin
val existing =
    emailReceiptDao.getByFingerprint(fingerprint)
        ?: sanitizedMessageId?.let { emailReceiptDao.getByMessageId(it) }

if (existing != null) {
    throw DuplicateEmailReceipt(existing.receiptId)
} else {
    throw IllegalStateException("EmailReceiptSource conflict unresolved")
}
```

Inside transaction, throw to rollback the newly inserted receipt unless a duplicate result is intentionally returned.

---

## P3-NEW-04 — Validator MIME fallback and OCR MIME routing disagree

### Severity

P1/P2.

### Evidence

`ReceiptInputValidator` handles null MIME by extension fallback.

`ReceiptOcrService.processUri()` independently calls `contentResolver.getType(uri) ?: ""` and rejects unsupported/blank MIME.

### User impact

A valid image/PDF from a provider that returns null MIME can pass validation, then fail OCR and be saved as fallback/failed scan.

### Fix strategy

Create one shared resolver:

```kotlin
data class ResolvedReceiptInput(
    val uri: Uri,
    val mimeType: String,
    val sizeBytes: Long?
)
```

Flow:

```text
ReceiptInputValidator.resolveAndValidate(uri) -> ResolvedReceiptInput
ReceiptOcrService.processUri(resolvedInput)
```

Also add magic-byte sniffing:

- JPEG/PNG/WebP/HEIC headers,
- `%PDF`.

---

## P3-NEW-05 — ReceiptRepository deprecated create-from-receipt returns success even if link fails

### Severity

P1.

### Evidence

`ReceiptRepository.createExpenseFromReceipt()` calls `receiptLinkService.linkReceiptToExpense(...)` after expense creation but does not inspect the `Result`.

### User impact

User gets success response and created expense, but receipt remains unlinked.

### Fix strategy

Delete method or force failure if link result fails. Until deletion, make method:

```kotlin
@Deprecated(level = DeprecationLevel.ERROR)
internal suspend fun ...
```

and add a static guard that no production code calls it.

---

## P3-NEW-06 — ReceiptLinkService writes expense category directly

### Severity

P1/P2.

### Evidence

`ReceiptLinkService.linkReceiptToExpense()` can call:

```text
expenseDao.updateCategory(expenseId, bestCategoryId)
```

It intentionally bypasses `TransactionLifecycleCoordinator.updateCategory()` due to dependency cycle.

### User impact

Expense category can change without:

- transaction `UPDATED` event,
- budget recalculation,
- analytics/cache invalidation,
- merchant learning,
- side-effect failure audit.

### Fix strategy

Break cycle with a port:

```kotlin
interface ExpenseCategoryAssignmentPort {
    suspend fun assignCategoryFromReceiptItems(
        expenseId: Long,
        categoryId: Long,
        source: String
    )
}
```

Implementation uses transaction lifecycle coordinator.

Alternative:

- publish post-commit command `ReceiptCategoryDetected`,
- process it through transaction lifecycle later.

---

## P3-NEW-07 — Side effects use sanitized OCR text, possibly disabling features silently

### Severity

P2, possibly P1 UX depending on privacy promise.

### Evidence

Coordinator sanitizes `rawOcrText` before passing `updated` receipt to `sideEffectDispatcher.dispatchAfterSave(updated)`.

If `rawOcrStorageMode` is redacted/metadata-only/do-not-store, downstream side effects may receive redacted/empty OCR text.

### User impact

Warranty extraction, item categorization, price protection, and matching may silently degrade after privacy settings change.

### Fix strategy

Decide product contract:

Option A — privacy mode controls persistence only:

```text
Use ephemeral raw OCR text for in-memory side effects.
Never persist it.
Write event: RAW_USED_EPHEMERALLY.
```

Option B — privacy mode also blocks raw-text side effects:

```text
Skip side effects needing raw text.
Write SIDE_EFFECT_SKIPPED_PRIVACY.
Show UX note.
```

Either is acceptable; silent degradation is not.

---

## P3-NEW-08 — `ReceiptEvent(RECEIVED)` is written at side-effect dispatch, not input receive

### Severity

P2.

### Evidence

`ReceiptSideEffectDispatcher` writes `RECEIVED` when a saved receipt enters side-effect dispatch.

That is not the same as “receipt input received”.

### User impact

Debug timeline becomes misleading:

```text
RECEIVED after RECEIPT_SAVED
```

### Fix strategy

Rename dispatcher event to:

```text
SIDE_EFFECT_DISPATCH_STARTED
```

Add true front-door event in coordinator:

```text
INPUT_RECEIVED
VALIDATION_PASSED
VALIDATION_FAILED
```

For validation failure before receipt ID exists, use nullable `receiptId`.

---

## P3-NEW-09 — Link/unlink audit can be false-positive

### Severity

P2.

### Evidence

`ReceiptExpenseLinkDao.unlink()` returns `Unit`, not affected row count. `ReceiptLinkService.unlinkReceiptFromExpense()` writes `RECEIPT_UNLINKED_FROM_EXPENSE` regardless of whether a link row actually existed.

Also, `linkReceiptToExpense()` checks expense existence before transaction. Because `ReceiptExpenseLink` has no FK, a concurrent expense delete could still create an orphan link.

### User impact

Audit trail can claim an unlink happened when no link was removed. Race can create orphan link.

### Fix strategy

Change DAO:

```kotlin
@Query(...)
suspend fun unlink(...): Int
```

Then:

```text
0 rows -> return NoOp / LinkNotFound, write optional warning event
>0 rows -> write RECEIPT_UNLINKED
```

Move expense/receipt existence validation inside the transaction, or add FKs.

---

## P3-NEW-10 — Bank statement import lacks durable run/item ledger

### Severity

P1/P2.

### Evidence

Bank statement processing returns `DebugData` and writes statement-level events, but per-transaction outcomes are mostly in `parsingLogs`.

### User impact

After import, users/debuggers cannot reliably answer:

```text
Which parsed transactions became pending reviews?
Which were skipped as duplicates?
Which failed and why?
```

### Fix strategy

Add durable per-item records:

```text
bank_statement_import_runs
bank_statement_import_items
```

or use `PipelineDiagnosticEvent` with:

```text
pipeline = bank_statement
stage = tx_dedupe / tx_review_insert
outcome = CREATED / DUPLICATE_EXPENSE / DUPLICATE_PENDING / ERROR
entityId = reviewId or expenseId
```

---

## P3-NEW-11 — Raw parser debug export is not privacy-gated

### Severity

P1/P2 privacy.

### Evidence

`ReceiptRepository.exportParserDebugData()` concatenates receipt debug data including `rawOcrText`. It does not visibly check a privacy/export/debug consent gate.

If raw OCR storage is allowed, this can export sensitive receipt text.

### User impact

Sensitive OCR/email body data can be exported through a debug function without a dedicated privacy decision.

### Fix strategy

Require:

```text
BuildConfig.DEBUG OR explicit user export consent
AND PrivacyGate(DEBUG_RAW_EXPORT)
```

Apply redaction based on storage/export mode.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize:

1. **Two-phase receipt insert can leave ghost/incomplete rows.**
2. **Duplicate batch scans can leave pending reviews for duplicate/ghost receipts.**
3. **Deprecated repository create-from-receipt can return success without link.**
4. **Email source conflict can leave receipt without `EmailReceiptSource`.**
5. **Valid files with null MIME can fail OCR despite validator passing.**
6. **Bank statement import can partially complete with no atomic run ledger.**
7. **Direct receipt delete/match APIs can bypass events/link cleanup.**
8. **ReceiptLinkService direct category mutation bypasses transaction lifecycle.**
9. **Privacy raw OCR modes can silently degrade side effects or leak via debug export.**
10. **Unlink audit can claim changes that did not occur.**

## Architectural / cleanup work

Still important, but lower urgency:

1. Remove deprecated receipt repository convenience APIs.
2. Add receipt insert-result domain type.
3. Add unique partial indexes for fingerprints.
4. Add canonical `ReceiptLifecycleEventType` enum.
5. Add import-run tables for statement imports.
6. Break dependency cycle with expense-category assignment port.
7. Add static DAO mutation guard.
8. Normalize currency fallback contract.

---

# 5. Recommended implementation plan

## PR 1 — True atomic receipt save lifecycle

### Goal

No receipt row can exist without lifecycle metadata and at least one event.

### Files

- `ReceiptRepository.kt`
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptEventDao.kt`
- tests

### Tasks

1. Change repository OCR path to return `ProcessedReceiptDraft`, not persisted entity.
2. Coordinator does final insert/update/event/review in one transaction.
3. Fallback manual receipt insert + `OCR_FAILED` event in one transaction.
4. Add recovery diagnostic for existing incomplete rows.

### Acceptance tests

```text
receipt_insert_and_RECEIPT_SAVED_are_atomic
crash_between_ocr_and_save_does_not_leave_unknown_receipt
ocr_failure_insert_and_event_are_atomic
parse_failure_insert_and_PARSE_FAILED_event_are_atomic
```

---

## PR 2 — Dedupe + pending review cleanup

### Goal

Duplicate detection cannot leave ghost reviews/assets.

### Files

- `ReceiptLifecycleCoordinator.kt`
- `ReceiptDuplicateDetector.kt`
- `PendingReviewDao.kt`
- `ReceiptAssetStore.kt`
- migrations

### Tasks

1. Move pending review creation after dedupe.
2. Add cleanup for existing ghost duplicate branches.
3. Add unique partial indexes for non-null fingerprints.
4. Add `insertOrResolve()` helper.
5. Delete duplicate asset files post-commit.

### Acceptance tests

```text
duplicate_exact_hash_does_not_create_pending_review
duplicate_text_fingerprint_does_not_create_actionable_review
duplicate_cleanup_deletes_orphan_asset
concurrent_duplicate_receipt_insert_resolves_existing
```

---

## PR 3 — Email receipt source conflict hardening

### Goal

No email receipt can be saved without coherent email source metadata.

### Files

- `ReceiptLifecycleCoordinator.kt`
- `EmailReceiptDao.kt`
- `EmailReceiptSource.kt`
- tests

### Tasks

1. Resolve `insertOrIgnore()` conflict by fingerprint and message ID.
2. Make fingerprint unique if intended.
3. Throw/rollback on unresolved conflict.
4. Add diagnostic events for duplicate source conflicts.
5. Add race tests.

### Acceptance tests

```text
email_message_id_conflict_returns_duplicate_existing_receipt
email_fingerprint_conflict_returns_duplicate_existing_receipt
email_source_conflict_unresolved_rolls_back_scanned_receipt
email_receipt_success_always_has_email_source_row
```

---

## PR 4 — Remove unsafe receipt repository mutations

### Goal

Coordinator/service owns lifecycle; repository cannot silently mutate state.

### Files

- `ReceiptRepository.kt`
- `ReceiptLinkService.kt`
- UI/ViewModels/callers
- CI/static guard

### Tasks

1. Delete or make internal:
   - `ReceiptRepository.createExpenseFromReceipt`
   - direct match mutation methods
   - direct delete method
2. Route all linking/unlinking through `ReceiptLinkService`.
3. Route delete through `ReceiptLifecycleCoordinator.deleteReceipt`.
4. Add static guard for direct DAO mutations.
5. Add event for match rejection/clear.

### Acceptance tests

```text
deprecated_createExpenseFromReceipt_has_no_production_callers
direct_delete_does_not_exist_in_release_path
save_match_suggestion_writes_MATCH_SUGGESTED_event
reject_suggestion_writes_MATCH_REJECTED_event
```

---

## PR 5 — Receipt-created expense/link atomicity finalization

### Goal

Create expense from receipt is atomic and side effects run post-commit.

### Files

- `ReceiptLifecycleCoordinator.kt`
- `TransactionLifecycleCoordinator.kt`
- `ReceiptLinkService.kt`

### Tasks

1. Keep only one public create-from-receipt entry.
2. Use DB-only create result or deferred post-commit action contract.
3. Check link result and rollback on failure.
4. Dispatch transaction + receipt side effects after outer commit.

### Acceptance tests

```text
link_failure_rolls_back_expense
created_expense_has_receipt_link_before_side_effects
post_commit_side_effects_run_once
duplicate_expense_links_receipt_to_existing_when_allowed
```

---

## PR 6 — MIME/OCR input contract unification

### Goal

Validator and OCR service agree on MIME/size.

### Files

- `ReceiptInputValidator.kt`
- `ReceiptOcrService.kt`
- new `ReceiptInputResolver.kt`

### Tasks

1. Shared MIME resolver with extension + magic-byte fallback.
2. `ReceiptOcrService.processUri()` accepts resolved MIME.
3. Add `%PDF`, JPG, PNG, WebP header sniffing.
4. Keep shared 50 MB size limit.

### Acceptance tests

```text
jpg_null_provider_mime_passes_and_ocr_runs
pdf_null_provider_mime_passes_and_pdf_path_runs
unknown_binary_rejected
validator_and_ocr_use_same_size_limit
```

---

## PR 7 — Bank statement import run ledger + atomicity

### Goal

Every statement transaction has durable outcome.

### Files

- `BankStatementLifecycleProcessor.kt`
- new import run/item entities + DAOs, or `PipelineDiagnosticEvent`
- `PendingReviewDao.kt`

### Tasks

1. Create run row at start.
2. Use per-transaction deduper inside DB transaction.
3. Insert pending review and item outcome atomically.
4. Write final run status and counts.
5. Record duplicate reasons durably.

### Acceptance tests

```text
statement_import_partial_failure_records_failed_item
statement_import_duplicate_existing_expense_records_item_duplicate
statement_import_duplicate_pending_records_item_duplicate
statement_import_status_COMPLETE_only_after_all_items_processed
concurrent_statement_import_does_not_create_duplicate_reviews
```

---

## PR 8 — Privacy/debug/side-effect contract

### Goal

Raw OCR/email storage setting applies consistently and visibly.

### Files

- `ReceiptLifecycleCoordinator.kt`
- `ReceiptSideEffectDispatcher.kt`
- `ReceiptRepository.exportParserDebugData`
- privacy gate/settings

### Tasks

1. Decide ephemeral raw side-effect policy.
2. If side effects skipped by privacy, write `SIDE_EFFECT_SKIPPED_PRIVACY`.
3. If ephemeral raw is allowed, keep it out of DB and write diagnostic.
4. Gate parser debug export.
5. Redact debug output by policy.

### Acceptance tests

```text
raw_ocr_do_not_store_does_not_persist_body
raw_ocr_do_not_store_records_side_effect_skip_or_ephemeral_use
debug_export_blocked_without_privacy_consent
debug_export_redacts_when_policy_requires
```

---

## PR 9 — Receipt link service lifecycle correctness

### Goal

Link/unlink audit matches actual DB changes.

### Files

- `ReceiptLinkService.kt`
- `ReceiptExpenseLinkDao.kt`
- optional migration for FKs

### Tasks

1. Make `unlink()` return affected rows.
2. Do not write unlink event for no-op unless event type is `UNLINK_NOOP`.
3. Validate receipt/expense existence inside transaction.
4. Consider adding FKs to `receipt_expense_links`.
5. Replace direct category DAO mutation with port.

### Acceptance tests

```text
unlink_nonexistent_link_does_not_write_success_event
link_fails_if_expense_deleted_before_transaction_commit
receipt_link_no_orphan_rows_after_delete
category_propagation_writes_transaction_updated_event
```

---

# 6. Suggested tracker updates

Update Pipeline 3 tracker:

| ID | Suggested status |
|---|---|
| P3-P0-01 | Mostly fixed / timestamp integrity caveat |
| P3-P1-01 | Partial, not fixed |
| P3-P1-02 | Mostly fixed |
| P3-P1-03 | Mostly fixed |
| P3-P1-04 | Partial |
| P3-P1-05 | Partial / high priority |
| P3-P1-06 | Partial |
| P3-P1-07 | Mostly fixed |
| P3-P1-08 | Partial |
| P3-P1-09 | Partial |
| P3-P1-10 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P3-NEW-01 | P1 | Batch duplicate scan can leave pending review for ghost/duplicate receipt |
| P3-NEW-02 | P1/P2 | Ghost duplicate cleanup does not delete persisted asset |
| P3-NEW-03 | P1 | Email source insert conflict can produce receipt without source row |
| P3-NEW-04 | P1/P2 | Validator MIME fallback and OCR MIME routing disagree |
| P3-NEW-05 | P1 | Deprecated repository create-from-receipt returns success even if link fails |
| P3-NEW-06 | P1/P2 | ReceiptLinkService writes expense category directly |
| P3-NEW-07 | P2/P1 | Side effects use sanitized OCR text and may silently degrade |
| P3-NEW-08 | P2 | `RECEIVED` event written at side-effect dispatch, not input receive |
| P3-NEW-09 | P2 | Link/unlink audit can be false-positive |
| P3-NEW-10 | P1/P2 | Bank statement import lacks durable run/item ledger |
| P3-NEW-11 | P1/P2 | Raw parser debug export is not privacy-gated |

---

# 7. Golden tests for Pipeline 3

Add or verify:

```text
receipt_scan_success_sets_createdAt_updatedAt_and_RECEIPT_SAVED
receipt_scan_ocr_failure_sets_OCR_FAILED_and_event_atomically
receipt_scan_parse_failure_sets_PARSE_FAILED_and_PARSE_FAILED_event
receipt_scan_duplicate_exact_hash_returns_existing_without_pending_review
receipt_scan_duplicate_text_fingerprint_does_not_create_actionable_review
receipt_duplicate_cleanup_deletes_orphan_asset
batch_receipt_import_creates_pending_review_only_after_dedupe
batch_receipt_import_writes_REVIEW_CREATED_event
receipt_without_currency_uses_home_currency_USD
receipt_with_explicit_EUR_preserves_EUR_when_home_USD
strong_receipt_match_creates_receipt_expense_link
medium_receipt_match_saves_suggestion_and_event
no_match_writes_MATCH_NOT_FOUND_or_debug_event
receipt_link_blocked_during_restore
receipt_unlink_nonexistent_is_noop_not_success_event
receipt_delete_removes_links_and_writes_RECEIPT_DELETED
receipt_delete_asset_failure_writes_ASSET_DELETE_FAILED
deprecated_createExpenseFromReceipt_has_no_production_callers
receipt_create_expense_link_failure_rolls_back_expense
email_receipt_duplicate_by_message_id_skipped
email_receipt_duplicate_by_semantic_fingerprint_skipped
email_source_conflict_rolls_back_or_returns_duplicate
email_receipt_success_always_has_email_source
bank_statement_import_skips_existing_expense_duplicate
bank_statement_import_skips_existing_pending_duplicate
bank_statement_import_records_per_transaction_outcomes
jpg_with_null_provider_mime_passes_and_ocr_runs
pdf_with_null_provider_mime_passes_and_pdf_path_runs
parallel_receipt_saves_generate_unique_asset_paths
raw_ocr_policy_metadata_only_stores_no_raw_text
debug_export_requires_privacy_consent
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "ScannedReceipt(" app/src/main/java
grep -R "scannedReceiptDao.insert" app/src/main/java
grep -R "scannedReceiptDao.update" app/src/main/java
grep -R "scannedReceiptDao.delete" app/src/main/java
grep -R "ReceiptEvent(" app/src/main/java
grep -R "PendingReview(" app/src/main/java | grep -i receipt
grep -R "linkReceiptToExpense" app/src/main/java
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "rawOcrText" app/src/main/java
grep -R "exportParserDebugData" app/src/main/java
grep -R "getType(uri)" app/src/main/java/com/yourname/expensetracker/domain/receipt
grep -R "updateCategory" app/src/main/java/com/yourname/expensetracker/domain/receipt
```

Allowed direct `ScannedReceiptDao` mutation list should be explicit:

```text
ReceiptLifecycleCoordinator
BankStatementLifecycleProcessor
ReceiptRepository only during draft-transition PR, then remove
DataRetentionWorker raw-text purge only
approved debug-only repository
Room migrations
```

Definition of done:

```text
- No persisted receipt has createdAt=0 or updatedAt=0.
- No receipt row can exist without a lifecycle event.
- Pending reviews are created only after duplicate detection.
- Duplicate cleanup deletes DB row, pending review, and asset.
- Email receipt success always has EmailReceiptSource.
- Receipt-created expense + link is atomic.
- Deprecated repository create-from-receipt has no production callsites.
- ReceiptLinkService does not directly mutate expense category.
- Validator and OCR service share MIME/size resolution.
- Bank statement import has durable per-transaction outcomes.
- Raw OCR/email body debug export is privacy-gated.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix true atomic receipt save lifecycle** — repository should return draft, coordinator inserts/events/reviews.
2. **Fix duplicate + pending-review/asset cleanup** — prevents duplicate actionable reviews and orphan files.
3. **Fix email source conflict handling** — rollback or return duplicate on message-ID/fingerprint conflict.
4. **Remove/neutralize deprecated repository create-from-receipt** — avoid success without link.
5. **Unify MIME resolver between validator and OCR** — fixes valid-file scan failures.
6. **Bank statement run/item ledger + atomic per-item outcomes.**
7. **Direct repository mutation/static guard cleanup.**
8. **ReceiptLinkService category propagation port.**
9. **Privacy/debug export/side-effect policy.**
10. **Receipt event taxonomy cleanup and no-op unlink correctness.**