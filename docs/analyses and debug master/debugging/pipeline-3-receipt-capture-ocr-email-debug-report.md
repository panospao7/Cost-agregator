# Pipeline 3 Debug Report — Receipt Capture / OCR / Email Receipt

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 3 is **partially lifecycle-aware but not fully clean/stable yet**.

The refactor introduced good infrastructure:

- `ReceiptLifecycleCoordinator`
- `ReceiptInputValidator`
- `ReceiptDuplicateDetector`
- `ReceiptLinkService`
- `ReceiptSideEffectDispatcher`
- `ReceiptEvent`
- `ReceiptExpenseLink`
- receipt source/document/status fields
- restore guard in main coordinator entry points

But the current state is still **yellow/orange**, not production-clean, because there are several correctness and durability holes:

1. successful scanned receipts can be inserted with `createdAt = 0`;
2. receipt save + metadata update + event insert are not atomic;
3. receipt matching computes a match but does not persist it;
4. some direct repository methods still bypass lifecycle/events/restore guard;
5. `ReceiptLinkService` can mutate receipts/links/expenses without restore guard;
6. duplicate handling can leave duplicate rows or unobserved insert conflicts;
7. email receipts have weaker dedupe/privacy handling;
8. receipt-created expense + link is not atomic in deprecated convenience paths.

---

# Severity scale

- **P0 / Critical:** can corrupt money data, lose receipt evidence, or silently create wrong rows.
- **P1 / High:** lifecycle bypass, restore/write-barrier hole, broken matching/linking, missing audit.
- **P2 / Medium:** common edge correctness, weak diagnostics, bad UX/debuggability.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Camera/gallery/file sources | Partially OK. URI validation exists. |
| Email receipt source | Partially OK. `processEmailReceipt()` exists, but dedupe/privacy are weak. |
| URI permission/readability | Mostly OK. `ReceiptInputValidator` opens stream. |
| MIME validation | Partial. Requires `ContentResolver.getType()`, no fallback sniffing. |
| File size validation | Partial. Validator allows 50 MB, OCR rejects 20 MB. |
| Receipt asset saved | Mostly OK, but asset naming collision risk exists. |
| SHA/hash generated | Partial. Exact hash exists; perceptual hash TODO. |
| OCR result captured | Mostly OK. OCR/PDF paths exist. |
| Parser extracts fields | Mostly OK, with currency fallback issue. |
| Duplicate detection | Partial. Hash/text/semantic exist, but insert/result semantics are weak. |
| `ScannedReceipt` inserted | Yes, but timestamps and insert conflicts are problematic. |
| `ReceiptEvent` inserted | Partial. Success/failure stages are not fully covered. |
| Linked to existing expense when matched | Not clean. Matcher result is not persisted by side effect. |
| No duplicate expense created | Partial. Receipt-created expense uses transaction coordinator, but link atomicity gaps remain. |
| Item categorization saved | Likely, but failure observability weak. |
| Warranty side effect only when eligible | Coarse gating only. Actual eligibility delegated. |
| Price protection only when eligible | Coarse gating only. Actual eligibility delegated. |
| Analytics counts expense once | Not provable from this slice. Link and category propagation gaps can affect counts. |

---

# Positive findings to preserve

## PF-01 — A real receipt lifecycle coordinator now exists

`ReceiptLifecycleCoordinator.processReceiptInput()` is the intended central entry point for receipt images/PDFs:

```text
restore guard
→ input validation
→ OCR/parse through ReceiptRepository
→ duplicate detection
→ scanned receipt metadata update
→ ReceiptEvent
→ side effects
```

This is the right direction.

## PF-02 — Input validation is much better

`ReceiptInputValidator` checks:

- readable URI,
- MIME type,
- file size,
- image decode validity.

This should remain the front gate.

## PF-03 — Receipt duplicate detector has multiple strategies

`ReceiptDuplicateDetector` supports:

- exact image hash,
- normalized OCR text fingerprint,
- semantic fingerprint,
- external source ID.

This is a good contract.

## PF-04 — Receipt link service is the right abstraction

`ReceiptLinkService` centralizes:

- `receipt_expense_links`,
- legacy `ScannedReceipt.expenseId`,
- receipt event logging,
- warranty/return-window expense propagation,
- item categorization `expenseId` propagation.

This should become the only allowed receipt-link mutation path.

## PF-05 — Bank statement pipeline is more structured than before

`BankStatementLifecycleProcessor` has:

- restore guard,
- pre-OCR hash dedupe,
- OCR,
- parser,
- AI validation,
- statement-level `ScannedReceipt`,
- `ReceiptEvent`,
- per-transaction `PendingReview`.

Good foundation, but still needs duplicate hardening.

---

# Issue P0-01 — Successful scanned receipts can be saved with `createdAt = 0`

## Severity

P0 / Critical

## Evidence

`ScannedReceipt.createdAt` defaults to `0L`.

`ReceiptRepository.processReceipt()` creates successful OCR receipts without setting `createdAt` or `updatedAt`.

`ReceiptLifecycleCoordinator.processReceiptInput()` later updates lifecycle metadata and sets `updatedAt`, but preserves the original `createdAt = 0`.

## Impact

This breaks many downstream behaviors:

- receipt ordering by `createdAt DESC`;
- retention worker may treat new raw OCR as ancient;
- matching fallback uses `receipt.parsedDate ?: receipt.createdAt`, so date-less receipts match around 1970;
- recent-receipt queries miss new receipts;
- diagnostics/debug timeline becomes wrong;
- backup/export ordering and history are unreliable.

## Fixing strategy

Make timestamps mandatory at the lifecycle boundary and prohibit sentinel timestamps for persisted receipts.

## Implementation plan

1. In `ReceiptRepository.processReceipt()`, set:

```kotlin
val now = timeProvider.now()
ScannedReceipt(
    ...
    createdAt = now,
    updatedAt = now
)
```

2. Do this for:
   - successful OCR/parse receipt,
   - parse-failure receipt,
   - manual fallback receipt,
   - statement common receipt if legacy `processStatement()` remains.

3. In `ReceiptLifecycleCoordinator.processReceiptInput()`, when updating existing receipt:

```kotlin
val created = if (receipt.createdAt == 0L) timeProvider.now() else receipt.createdAt
val updated = receipt.copy(createdAt = created, updatedAt = now)
```

4. Add DB/data-integrity diagnostic:

```sql
SELECT * FROM scanned_receipts WHERE createdAt = 0 OR updatedAt = 0;
```

5. Tests:

```text
process_receipt_sets_createdAt_and_updatedAt
process_receipt_parse_failure_sets_createdAt_and_updatedAt
manual_receipt_record_sets_createdAt_and_updatedAt
lifecycle_update_repairs_zero_createdAt
new_receipt_without_parsed_date_matches_using_real_createdAt_not_1970
```

---

# Issue P1-02 — Receipt save/update/event is not atomic

## Severity

P1 / High

## Evidence

`ReceiptRepository.processReceipt()` inserts `ScannedReceipt`.

Then `ReceiptLifecycleCoordinator.processReceiptInput()` separately:

- computes hash/fingerprints,
- updates the receipt,
- inserts `ReceiptEvent`,
- dispatches side effects.

These are not wrapped in one database transaction.

## Impact

A failure between insert and lifecycle update can leave:

```text
ScannedReceipt row exists
but sourceType/documentType/status/fingerprints are missing
and no ReceiptEvent exists
```

The app can then show orphan/unknown receipts that are hard to debug.

## Fixing strategy

Separate OCR/parse from persistence, or make the lifecycle coordinator own the final DB transaction.

## Implementation plan

Preferred design:

1. Change repository to return a transient processed payload:

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
    val taxInclusive: Boolean
)
```

2. Coordinator does:

```kotlin
database.withTransaction {
    insert ScannedReceipt with all metadata/fingerprints/timestamps
    insert ReceiptEvent(RECEIPT_SAVED or OCR_FAILED/PARSE_FAILED)
}
```

3. Side effects stay post-commit.

Minimum patch:

```kotlin
database.withTransaction {
    scannedReceiptDao.update(updated)
    receiptEventDao.insert(...)
}
```

Tests:

```text
metadata_update_and_receipt_event_are_atomic
event_insert_failure_rolls_back_metadata_update
inserted_receipt_always_has_lifecycle_event
```

---

# Issue P1-03 — Receipt matching result is computed but not persisted

## Severity

P1 / High

## Evidence

`ReceiptSideEffectDispatcher.dispatchAfterSave()` calls:

```kotlin
receiptTransactionMatcher.findBestMatch(receipt)
```

But the returned `MatchResult` is ignored.

`ReceiptTransactionMatcher` returns:

- `AutoMatch`
- `Suggested`
- `NoMatch`

but does not call `ReceiptLinkService` or save a suggestion.

## Impact

Pipeline checklist item “receipt linked to existing expense when matched” is not satisfied for the post-save side effect path.

A receipt can be OCR-parsed and have a strong transaction match, but the DB state remains:

```text
matchStatus = UNMATCHED
expenseId = null
receipt_expense_links = empty
```

## Fixing strategy

Make matching a command, not only a scorer.

## Implementation plan

1. Add a persisting method:

```kotlin
suspend fun matchAndPersist(receipt: ScannedReceipt): MatchResult
```

2. Behavior:

```text
AutoMatch(score >= 0.95)
→ ReceiptLinkService.linkReceiptToExpense(
     linkType = "AUTO_MATCH",
     source = "RECEIPT_MATCHER",
     confidence = score,
     matchStatus = AUTO_MATCHED
  )

Suggested(score >= 0.80)
→ update ScannedReceipt.suggestedExpenseId
→ matchStatus = SUGGESTED
→ matchConfidence = score
→ ReceiptEvent MATCH_SUGGESTED

NoMatch
→ optional ReceiptEvent MATCH_NOT_FOUND
```

3. Update side effect dispatcher to call `matchAndPersist`.

4. Tests:

```text
strong_match_creates_receipt_expense_link
strong_match_sets_legacy_expenseId_and_AUTO_MATCHED
medium_match_saves_suggestion_without_link
no_match_leaves_receipt_unmatched
bank_statement_receipt_is_not_matched
```

---

# Issue P1-04 — `ReceiptLinkService` lacks restore maintenance guard

## Severity

P1 / High

## Evidence

`ReceiptLifecycleCoordinator.processReceiptInput()`, `processEmailReceipt()`, `saveEmailReceipt()`, and `deleteReceipt()` check `restoreMaintenanceMode`.

But `ReceiptLinkService.linkReceiptToExpense()` and `unlinkReceiptFromExpense()` do not visibly check restore/write mode.

## Impact

Any caller can mutate during restore:

- `receipt_expense_links`,
- `ScannedReceipt.expenseId`,
- `matchStatus`,
- warranty expense links,
- return-window expense links,
- receipt item categorization expense links,
- possibly expense category.

## Fixing strategy

The write barrier must exist at every shared write boundary, not only entry-point coordinators.

## Implementation plan

1. Inject `RestoreMaintenanceMode` into `ReceiptLinkService`.

2. At top of link/unlink:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    return Result.failure(IllegalStateException("Database writes blocked during restore"))
}
```

3. Add tests:

```text
link_receipt_to_expense_blocked_during_restore
unlink_receipt_from_expense_blocked_during_restore
restore_mode_does_not_mutate_link_table_or_receipt
```

---

# Issue P1-05 — Receipt-created expense and receipt-link are not atomic in convenience paths

## Severity

P1 / High

## Evidence

Both deprecated convenience paths still exist:

- `ReceiptRepository.createExpenseFromReceipt()`
- `ReceiptLifecycleCoordinator.createExpenseFromReceipt()`

They do:

```text
TransactionLifecycleCoordinator.createExpense()
→ ReceiptLinkService.linkReceiptToExpense()
```

If expense creation succeeds but linking fails, the expense remains without a receipt link.

## Impact

The user may press “Save receipt as expense” and get:

```text
Expense created
receipt still unlinked
warranty/item categorization not attached
analytics may count expense but receipt UX says unresolved
```

## Fixing strategy

For user-facing “create expense from receipt,” expense creation + receipt link must be atomic, with post-commit side effects deferred.

## Implementation plan

1. Replace convenience API with explicit orchestration:

```kotlin
database.withTransaction {
    val createResult = transactionLifecycleCoordinator.createExpense(
        request,
        sideEffectMode = SideEffectMode.DEFER
    )
    receiptLinkService.linkReceiptToExpenseTxOnly(...)
    receiptItemCategorizationDao.linkToExpense(...)
}
dispatch transaction side effects after commit
dispatch receipt side effects after commit if needed
```

2. If `ReceiptLinkService` cannot be used inside existing transaction cleanly, expose a transaction-safe internal method:

```kotlin
internal suspend fun linkReceiptToExpenseInTransaction(...)
```

3. Remove or make deprecated methods private/internal once all callers migrate.

4. Tests:

```text
expense_creation_rolls_back_when_receipt_link_fails
receipt_link_failure_does_not_leave_orphan_expense
successful_create_from_receipt_writes_CREATED_and_RECEIPT_LINKED_events
post_commit_side_effects_run_once_after_atomic_create_and_link
```

---

# Issue P1-06 — Direct repository methods bypass receipt lifecycle and restore guard

## Severity

P1 / High

## Evidence

`ReceiptRepository` still exposes methods that directly mutate receipt data:

```text
insertReceipt()
deleteReceipt()
clearAllScannedReceipts()
linkReceiptToExpense() deprecated
approveMatchSuggestion() deprecated
clearMatchForReceipt()
saveMatchSuggestion()
processStatement() legacy
```

Some have KDoc saying “internal use only,” but they are still public suspend functions.

## Impact

These paths can bypass:

- `ReceiptLifecycleCoordinator`,
- `ReceiptEvent`,
- `ReceiptLinkService`,
- restore maintenance mode,
- link cleanup,
- asset cleanup consistency,
- side effects.

Because `ReceiptExpenseLink` has no DB foreign keys, direct delete can leave orphan link rows.

## Fixing strategy

Constrain direct writes and add static guards.

## Implementation plan

1. Move debug/destructive methods to `DebugReceiptRepository`.

2. Make lifecycle-bypass methods `internal` where possible.

3. Add CI/static guard:

```text
No direct scannedReceiptDao.insert/update/delete from app code except:
- ReceiptLifecycleCoordinator
- ReceiptRepository OCR draft persistence only until refactored
- BankStatementLifecycleProcessor
- approved debug-only classes
```

4. Replace deprecated link methods with `ReceiptLinkService`.

5. Tests:

```text
delete_receipt_via_coordinator_writes_RECEIPT_DELETED_and_deletes_links
direct_repository_delete_not_available_in_release
clearAllScannedReceipts_debug_only_and_audited
orphan_receipt_expense_link_diagnostic_detects_broken_links
```

---

# Issue P1-07 — `ScannedReceiptDao.insert()` uses `IGNORE`, but many callers do not check `0`

## Severity

P1 / High

## Evidence

`ScannedReceiptDao.insert()` uses `OnConflictStrategy.IGNORE`.

Several call sites assume the returned ID is valid:

- `ReceiptRepository.processReceipt()`
- `saveManualReceiptRecord()`
- parse-failure path
- `ReceiptLifecycleCoordinator.processEmailReceipt()`
- `saveEmailReceipt()`
- fallback insert in `processReceiptInput()`

Bank statement processor does check `receiptId <= 0`, but most paths do not.

## Impact

If a unique constraint exists now or is added later for fingerprints/source IDs, insert conflict can return `0`, then the code may create:

```text
ReceiptEvent(receiptId = 0)
EmailReceiptSource(receiptId = 0)
PendingReview(scannedReceiptId = 0)
```

or return a fake receipt ID.

## Fixing strategy

Every `insert()` result must be treated as a domain outcome.

## Implementation plan

1. Add helper:

```kotlin
suspend fun ScannedReceiptDao.insertOrResolve(receipt: ScannedReceipt): ReceiptInsertResult
```

2. Result:

```kotlin
sealed interface ReceiptInsertResult {
    data class Inserted(val id: Long) : ReceiptInsertResult
    data class Conflict(val existingReceipt: ScannedReceipt?) : ReceiptInsertResult
}
```

3. At minimum:

```kotlin
val id = scannedReceiptDao.insert(receipt)
require(id > 0) { "ScannedReceipt insert conflict" }
```

4. Tests:

```text
receipt_insert_conflict_does_not_create_event_with_id_zero
email_receipt_insert_conflict_returns_existing_receipt
manual_fallback_insert_conflict_is_visible
```

---

# Issue P1-08 — Currency fallback is still hardcoded to EUR in OCR parse path

## Severity

P1 / High for multi-currency users

## Evidence

`ReceiptRepository.processReceipt()` calls:

```kotlin
receiptParser.parse(ocrResult.fullText)
```

`ReceiptParser.parse()` defaults `homeCurrency = "EUR"`.

The repository has `CurrencySettingsRepository`, but successful parsing does not pass the user’s home currency.

## Impact

If OCR text does not contain an explicit currency and the user’s home currency is USD/GBP/etc., receipt currency may become EUR incorrectly.

That affects:

- expense creation from receipt,
- duplicate semantic fingerprint,
- matching amount/currency score,
- analytics,
- tax/business exports.

## Fixing strategy

Resolve home currency before parsing and pass it to the parser.

## Implementation plan

1. In `processReceipt()`:

```kotlin
val homeCur = homeCurrency()
val parsed = receiptParser.parse(ocrResult.fullText, homeCurrency = homeCur)
```

2. Use `parsed.currency ?: homeCur` if parser model supports nullable; otherwise ensure parser returns the fallback passed in.

3. Remove hardcoded `"EUR"` fallback from duplicate/failure dummy parsed results where possible.

4. Tests:

```text
receipt_without_currency_uses_home_currency_USD
receipt_with_explicit_EUR_preserves_EUR_when_home_USD
semantic_fingerprint_uses_resolved_currency
```

---

# Issue P1-09 — Parse failures are classified as `OCR_COMPLETED`, not `PARSE_FAILED`

## Severity

P1 / High for observability and review UX

## Evidence

When OCR succeeds but parsing throws, `ReceiptRepository.processReceipt()` saves a receipt with OCR text and null parsed fields.

`ReceiptLifecycleCoordinator.processReceiptInput()` then determines status:

```text
OCR_FAILED if raw text says scan failed
PARSED if parsedMerchant != null
else OCR_COMPLETED
```

There is a `ReceiptProcessingStatus.PARSE_FAILED` value, but this path does not appear to use it.

## Impact

A parser exception is hidden as a generic OCR-completed receipt.

The app cannot reliably answer:

```text
OCR worked, but parser failed
```

and review creation/recovery may be inconsistent.

## Fixing strategy

Return processing provenance from the repository.

## Implementation plan

1. Add outcome:

```kotlin
enum class ReceiptProcessStage {
    OCR_FAILED,
    PARSE_FAILED,
    PARSED,
    OCR_COMPLETED_NO_STRUCTURED_FIELDS
}
```

2. Repository returns:

```kotlin
data class ReceiptProcessResult(
    val receipt: ScannedReceipt,
    val parsed: ParsedReceipt?,
    val stage: ReceiptProcessStage,
    val failureReason: String?
)
```

3. Coordinator maps:

```text
PARSE_FAILED → ReceiptProcessingStatus.PARSE_FAILED
```

4. Write event:

```text
PARSE_FAILED
```

with `errorDetails`.

5. Tests:

```text
parser_exception_sets_PARSE_FAILED_status
parser_exception_writes_PARSE_FAILED_event
ocr_success_no_structured_fields_sets_OCR_COMPLETED_not_PARSE_FAILED
```

---

# Issue P1-10 — Batch receipt import no longer creates pending reviews

## Severity

P1/P2 depending on intended UX

## Evidence

`ReceiptRepository.processBatch()` now routes each URI through:

```kotlin
receiptLifecycleCoordinator.get().processReceiptInput(uri)
```

`processReceiptInput()` calls:

```kotlin
receiptRepository.processReceipt(imageUri = uri, autoCreateReview = false)
```

Older repository flow had `autoCreateReview` support. The lifecycle path disables it.

## Impact

Batch-imported receipts may be saved but not appear in review queue as expenses to confirm.

This can look like “scan succeeded but nothing actionable happened.”

## Fixing strategy

Make review creation an explicit lifecycle option.

## Implementation plan

1. Add options:

```kotlin
data class ReceiptProcessingOptions(
    val createReview: Boolean = false,
    val autoMatchExistingExpense: Boolean = true
)
```

2. Use:

```kotlin
processReceiptInput(uri, options = ReceiptProcessingOptions(createReview = true))
```

for batch-import if intended.

3. Review creation must:
   - happen in DB transaction with receipt save/event,
   - set `ReceiptProcessingStatus.REVIEW_CREATED`,
   - write `REVIEW_CREATED` event.

4. Tests:

```text
batch_receipt_import_creates_pending_review_when_enabled
manual_scan_does_not_create_review_when_disabled
review_created_event_written
review_sentinel_amount_null_requires_user_override
```

---

# Issue P1-11 — Bank statement lifecycle dedupe is weaker than legacy statement path

## Severity

P1 / High if bank statement import is part of Pipeline 3/10

## Evidence

`BankStatementLifecycleProcessor` creates `PendingReview` rows after checking existing pending reviews by merchant/amount/currency.

It does not appear to use the stronger legacy duplicate checks from `ReceiptRepository.processStatement()` that checked:

- date window,
- merchant key,
- amount tolerance,
- currency,
- transaction type,
- existing expenses.

## Impact

Importing a bank statement can create pending reviews for transactions that already exist as expenses.

That can lead to duplicate expenses if the user approves them.

## Fixing strategy

Move the stronger duplicate policy into a shared statement transaction dedupe service.

## Implementation plan

1. Create:

```kotlin
StatementTransactionDeduper
```

2. Check both:

```text
ExpenseDao existing approved expenses
PendingReviewDao existing pending reviews
```

with:

```text
merchantKey/raw merchant
date window
amount tolerance
currency
transaction type
source
```

3. Return structured outcome:

```kotlin
sealed interface StatementTxDedupeResult {
    data object Unique
    data class ExistingExpense(val expenseId: Long)
    data class ExistingPendingReview(val reviewId: Long)
}
```

4. Tests:

```text
statement_import_skips_existing_expense_duplicate
statement_import_skips_existing_pending_review_duplicate
statement_import_type_aware_income_vs_purchase
statement_import_currency_aware_duplicate
```

---

# Issue P2-12 — MIME validation has no fallback when provider returns null

## Severity

P2 / Medium

## Evidence

`ReceiptInputValidator` has a TODO for MIME fallback detection.

If `ContentResolver.getType(uri)` returns null, validation fails.

## Impact

Some gallery/file providers can return null MIME type even for valid images/PDFs.

User-visible symptom:

```text
valid receipt image cannot be scanned
```

## Fixing strategy

Add extension/header sniff fallback.

## Implementation plan

1. If `getType()` is null:
   - inspect file extension,
   - inspect magic bytes,
   - fallback to `BitmapFactory` bounds decode for images,
   - fallback to `%PDF` header for PDFs.

2. Tests:

```text
jpg_with_null_provider_mime_passes_by_header
pdf_with_null_provider_mime_passes_by_header
unknown_binary_rejected
```

---

# Issue P2-13 — Validator and OCR service disagree on max file size

## Severity

P2 / Medium

## Evidence

`ReceiptInputValidator` default max is 50 MB.

`ReceiptOcrService` has `MAX_FILE_SIZE = 20 MB`.

## Impact

A file can pass validation then fail during OCR.

## Fixing strategy

Use one shared config constant.

## Implementation plan

1. Create:

```kotlin
ReceiptInputLimits.MAX_FILE_SIZE_BYTES
```

2. Use it in both validator and OCR service.

3. Tests:

```text
file_size_limit_consistent_between_validator_and_ocr
25mb_file_either_passes_both_or_fails_both
```

---

# Issue P2-14 — Receipt asset filenames can collide under concurrency

## Severity

P2 / Medium, P1 if batch imports overwrite images

## Evidence

`ReceiptOcrService.saveReceiptImage()` uses:

```text
receipt_${System.currentTimeMillis()}.jpg
```

Batch import runs with concurrency up to 3.

`ReceiptAssetStore.persistReceiptAsset()` has a safer `{timestamp}_{uuid}.jpg`, but OCR service does not use it.

## Impact

Two receipts saved within the same millisecond can target the same filename.

Possible result:

- overwritten image,
- wrong asset linked to receipt,
- bad image hash,
- backup manifest mismatch.

## Fixing strategy

Use `ReceiptAssetStore` as the single asset owner.

## Implementation plan

1. Replace filename generation with UUID:

```kotlin
receipt_${timeProvider.now()}_${UUID.randomUUID()}.jpg
```

2. Better: move save logic into `ReceiptAssetStore`.

3. Tests:

```text
parallel_receipt_saves_generate_unique_paths
batch_import_does_not_overwrite_asset_files
```

---

# Issue P2-15 — PDF page truncation warning is only logged

## Severity

P2 / Medium

## Evidence

PDF processing only uses first 5 pages and logs a warning when more pages exist.

No receipt event/status/metadata appears to expose this.

## Impact

A user scanning a multi-page PDF may miss transactions/items after page 5 without seeing a UI warning.

## Fixing strategy

Surface partial-processing state.

## Implementation plan

1. Add fields to OCR result:

```kotlin
data class OcrResult(
    ...
    val pagesProcessed: Int?,
    val totalPages: Int?,
    val partial: Boolean
)
```

2. Store in `ReceiptEvent.metadata`.

3. UI warning:

```text
Only first 5 pages processed.
```

4. Tests:

```text
multipage_pdf_records_partial_processing_event
multipage_pdf_debug_metadata_has_pagesProcessed_and_totalPages
```

---

# Issue P2-16 — Raw OCR/email body storage policy is too coarse

## Severity

P2 / Medium, P1 if privacy requirements are strict

## Evidence

Receipt scan stores raw OCR text in `ScannedReceipt.rawOcrText`.

Email receipt stores full email body as `rawOcrText`.

Retention worker can purge later, but write-time policy is not visible in this slice.

`exportParserDebugData()` exports raw OCR from DB.

## Impact

Users may want receipt-derived expenses without long-term raw OCR/email body retention.

## Fixing strategy

Separate processing from raw text retention.

## Implementation plan

1. Add privacy setting:

```kotlin
enum class ReceiptRawTextStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY
}
```

2. Apply at write time:
   - parser receives text in memory,
   - DB stores according to policy.

3. For email:
   - store parsed fields,
   - redact body by default if policy requires.

4. Guard debug export:

```text
only DEBUG build or explicit user export consent
```

5. Tests:

```text
raw_text_disabled_stores_empty_or_redacted_ocr
email_body_redacted_when_policy_requires
parser_still_works_with_in_memory_text
debug_export_respects_privacy_setting
```

---

# Issue P2-17 — Side-effect failures are only logged, not audited

## Severity

P2 / Medium

## Evidence

`ReceiptSideEffectDispatcher` wraps warranty, item categorization, matching, and price protection in try/catch and logs failures.

No `ReceiptEvent` appears to be written for:

- warranty extraction failed,
- item categorization failed,
- matching failed,
- price protection failed.

## Impact

User sees a saved receipt but no warranty/item categories/match, with no durable reason.

## Fixing strategy

Make side-effect outcomes durable.

## Implementation plan

1. Inject `ReceiptEventDao` into dispatcher.

2. For each side effect write:

```text
SIDE_EFFECT_STARTED
SIDE_EFFECT_SUCCEEDED
SIDE_EFFECT_FAILED
```

or specific events:

```text
WARRANTY_EXTRACTION_FAILED
ITEM_CATEGORIZATION_FAILED
MATCH_SUGGESTION_CREATED
PRICE_PROTECTION_FAILED
```

3. Tests:

```text
warranty_failure_writes_receipt_event
categorization_failure_writes_receipt_event
matcher_no_match_writes_optional_debug_event
```

---

# Issue P2-18 — `ReceiptLinkService` mutates expense category directly

## Severity

P2 / Medium, possibly P1 for lifecycle consistency

## Evidence

`ReceiptLinkService.linkReceiptToExpense()` can propagate item majority category to an expense with:

```text
expenseDao.updateCategory(expenseId, bestCategoryId)
```

The code comment says this intentionally bypasses `TransactionLifecycleCoordinator.updateCategory()` because of circular dependency.

## Impact

Receipt linking can change expense category without:

- transaction lifecycle `UPDATED` event,
- budget side effects,
- analytics/cache invalidation,
- merchant learning.

This can create inconsistent transaction history.

## Fixing strategy

Break the dependency cycle with a port/event.

## Implementation plan

1. Define a port:

```kotlin
interface ExpenseCategoryAssignmentPort {
    suspend fun assignCategoryFromReceiptItems(expenseId: Long, categoryId: Long, source: String)
}
```

2. Implement port using transaction lifecycle coordinator in data/domain boundary.

3. Or publish a post-commit command:

```text
ReceiptItemMajorityCategoryDetected(expenseId, categoryId)
```

and handle it outside `ReceiptLinkService`.

4. Tests:

```text
receipt_item_category_propagation_writes_transaction_UPDATED_event
budget_recalculation_runs_once_after_receipt_category_assignment
linking_does_not_directly_call_expenseDao_updateCategory
```

---

# Issue P2-19 — Email receipt duplicate detection is message-ID only

## Severity

P2 / Medium

## Evidence

`processEmailReceipt()` checks:

```text
scannedReceiptDao.getBySourceFingerprint(emailData.messageId)
```

If message ID is blank or changed, it does not appear to use text/semantic dedupe.

## Impact

Forwarded emails, provider re-sends, imported mailbox copies, or missing message IDs can create duplicate receipt rows.

## Fixing strategy

Run email receipts through the same duplicate detector.

## Implementation plan

1. Compute:

```text
sourceFingerprint = messageId if present
textFingerprint = normalized body
semanticFingerprint = merchant + amount + date + currency
```

2. Call `ReceiptDuplicateDetector.checkDuplicate()` with all available fingerprints.

3. Tests:

```text
email_receipt_duplicate_by_message_id_skipped
email_receipt_duplicate_by_semantic_fingerprint_skipped
email_receipt_blank_message_id_uses_text_or_semantic_dedupe
```

---

# Issue P2-20 — Receipt events are too sparse for full pipeline debugging

## Severity

P2 / Medium

## Evidence

Current main events include:

- `RECEIPT_SAVED`
- `OCR_FAILED`
- `DUPLICATE_DETECTED`
- `RECEIPT_DELETED`
- `RECEIPT_LINKED_TO_EXPENSE`
- `RECEIPT_UNLINKED_FROM_EXPENSE`
- bank `PROCESSING_COMPLETE`

Missing or inconsistent:

- `RECEIVED`
- `VALIDATION_FAILED`
- `OCR_STARTED`
- `OCR_COMPLETED`
- `PARSE_STARTED`
- `PARSE_FAILED`
- `REVIEW_CREATED`
- `MATCH_SUGGESTED`
- `AUTO_MATCHED`
- `SIDE_EFFECT_FAILED`
- `ASSET_DELETE_FAILED`

## Impact

Debugging still requires guessing and reading logs.

## Fixing strategy

Define a canonical receipt event enum and write durable events at every exit.

## Implementation plan

1. Add enum:

```kotlin
enum class ReceiptLifecycleEventType {
    RECEIVED,
    VALIDATION_FAILED,
    OCR_STARTED,
    OCR_COMPLETED,
    OCR_FAILED,
    PARSE_STARTED,
    PARSED,
    PARSE_FAILED,
    DUPLICATE_DETECTED,
    RECEIPT_SAVED,
    REVIEW_CREATED,
    MATCH_SUGGESTED,
    RECEIPT_LINKED_TO_EXPENSE,
    SIDE_EFFECT_FAILED,
    RECEIPT_DELETED
}
```

2. Replace string literals where feasible.

3. Tests:

```text
validation_failure_writes_event_without_receipt_id
ocr_failure_writes_event
parse_failure_writes_event
successful_receipt_has_received_saved_events
```

---

# Recommended fixing order

## PR 1 — Timestamp + insert-result hardening

Files:

```text
ReceiptRepository.kt
ReceiptLifecycleCoordinator.kt
ScannedReceiptDao.kt
ReceiptLifecycleCoordinatorTest.kt
```

Fix:

```text
- set createdAt/updatedAt everywhere
- repair zero createdAt on lifecycle update
- check insert result > 0 everywhere
```

## PR 2 — Atomic receipt lifecycle save

Files:

```text
ReceiptLifecycleCoordinator.kt
ReceiptRepository.kt
ReceiptEventDao.kt
```

Fix:

```text
- save metadata + event in one transaction
- convert repository persistence into draft/result if possible
```

## PR 3 — Persist matching outcomes

Files:

```text
ReceiptTransactionMatcher.kt
ReceiptSideEffectDispatcher.kt
ReceiptLinkService.kt
ReceiptRepository.kt
```

Fix:

```text
- AutoMatch creates link through ReceiptLinkService
- Suggested updates suggestion fields + event
```

## PR 4 — Restore guard + lifecycle bypass guard

Files:

```text
ReceiptLinkService.kt
ReceiptRepository.kt
scripts/lifecycle-bypass-guard.*
docs/architecture/CONTRACTS.md
```

Fix:

```text
- restore guard in link/unlink
- deprecated direct repository methods internal/debug-only
- static guard for direct ScannedReceiptDao mutations
```

## PR 5 — Receipt-created expense atomicity

Files:

```text
ReceiptLifecycleCoordinator.kt
ReceiptLinkService.kt
TransactionLifecycleCoordinator.kt
ReviewQueueRepository.kt
ReceiptScanViewModel.kt
```

Fix:

```text
- create expense + link receipt atomically
- defer transaction side effects until post-commit
```

## PR 6 — Currency + validation consistency

Files:

```text
ReceiptRepository.kt
ReceiptParser.kt
ReceiptInputValidator.kt
ReceiptOcrService.kt
ReceiptInputLimits.kt
```

Fix:

```text
- pass home currency into parser
- shared file size limit
- MIME fallback sniffing
```

## PR 7 — Email and bank statement dedupe

Files:

```text
ReceiptLifecycleCoordinator.kt
ReceiptDuplicateDetector.kt
BankStatementLifecycleProcessor.kt
StatementTransactionDeduper.kt
```

Fix:

```text
- email text/semantic dedupe
- statement expense + pending-review duplicate check
```

## PR 8 — Privacy/raw OCR policy + debug export gate

Files:

```text
ReceiptLifecycleCoordinator.kt
ReceiptRepository.kt
PrivacySettingsRepository
DataRetentionWorker
```

Fix:

```text
- raw OCR/email body storage mode
- redaction at write time
- debug export respects privacy
```

---

# Golden tests to add

```text
receipt_scan_success_sets_createdAt_updatedAt_and_RECEIPT_SAVED
receipt_scan_ocr_failure_sets_OCR_FAILED_and_event
receipt_scan_parse_failure_sets_PARSE_FAILED_and_event
receipt_scan_duplicate_exact_hash_does_not_create_new_active_receipt
receipt_scan_duplicate_text_marks_duplicate_and_writes_event
receipt_without_currency_uses_home_currency
batch_receipt_import_creates_pending_review_when_configured
strong_receipt_match_creates_receipt_expense_link
medium_receipt_match_saves_suggestion
receipt_create_expense_link_failure_rolls_back_expense
receipt_link_blocked_during_restore
receipt_delete_removes_links_and_writes_RECEIPT_DELETED
direct_delete_repository_method_not_available_in_release
email_receipt_duplicate_by_message_id_skipped
email_receipt_duplicate_by_semantic_fingerprint_skipped
bank_statement_import_skips_existing_expense_duplicate
parallel_receipt_saves_generate_unique_asset_paths
raw_ocr_policy_metadata_only_stores_no_raw_text
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "ScannedReceipt(" app/src/main/java
grep -R "scannedReceiptDao.insert" app/src/main/java
grep -R "scannedReceiptDao.update" app/src/main/java
grep -R "scannedReceiptDao.delete" app/src/main/java
grep -R "ReceiptEvent(" app/src/main/java
grep -R "linkReceiptToExpense" app/src/main/java
grep -R "processReceipt(" app/src/main/java
grep -R "createExpenseFromReceipt" app/src/main/java
grep -R "rawOcrText" app/src/main/java
```

Allowed direct `ScannedReceiptDao` mutation list should be explicit:

```text
ReceiptLifecycleCoordinator
BankStatementLifecycleProcessor
DataRetentionWorker for raw-text purge only
approved debug-only repository
Room migrations
```

Definition of done:

```text
- No persisted ScannedReceipt has createdAt=0 or updatedAt=0.
- Every insert result from ScannedReceiptDao.insert() is checked.
- Receipt metadata update + ReceiptEvent insert are atomic.
- Matching side effect persists AUTO_MATCHED or SUGGESTED state.
- ReceiptLinkService respects restore maintenance mode.
- User-facing receipt-created expense + link is atomic.
- ReceiptRepository legacy direct mutation methods are removed/internal/debug-only.
- OCR parser receives actual home currency fallback.
- Email receipt dedupe uses messageId + text/semantic fingerprints.
- Statement import skips existing approved expenses and pending reviews.
- Raw OCR/email body storage respects privacy policy.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `ReceiptLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `ReceiptInputValidator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptInputValidator.kt

- `ReceiptDuplicateDetector.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptDuplicateDetector.kt

- `ReceiptLinkService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

- `ReceiptSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt

- `ReceiptAssetStore.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptAssetStore.kt

- `ReceiptOcrService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt

- `ReceiptParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt

- `BankStatementLifecycleProcessor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

- `ReceiptTransactionMatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt

- `ScannedReceipt.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `ScannedReceiptDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

- `ReceiptEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptEvent.kt

- `ReceiptExpenseLink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt

- `ReceiptExpenseLinkDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt