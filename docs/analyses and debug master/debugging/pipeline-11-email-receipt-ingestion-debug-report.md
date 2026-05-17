# Pipeline 11 Debug Report — Email Receipt Ingestion

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 11 is **partially functional but not clean/stable yet**.

Good foundations exist:

- `EmailReceiptIngestionService`
- provider parsers for Amazon, Uber, Apple
- `EmailReceiptSource`
- `EmailReceiptDao`
- `ReceiptLifecycleCoordinator.saveEmailReceipt()`
- email receipt source metadata
- message-ID dedupe
- fingerprint dedupe
- transaction creation through `TransactionLifecycleCoordinator`
- receipt-expense linking through `ReceiptLinkService`
- deferred transaction side effects after outer transaction

But the pipeline is still **yellow/orange** because email ingestion is split between two competing paths:

```text
EmailReceiptIngestionService.processEmailReceipt(...)
ReceiptLifecycleCoordinator.processEmailReceipt(EmailReceiptData)
```

The service path is richer because it parses providers and creates expenses, but it bypasses parts of the receipt lifecycle side-effect contract. The coordinator path is lifecycle-cleaner, but does not create expenses and only dedupes by message ID.

Main risks:

1. same receipt with different/forwarded message ID can bypass fingerprint dedupe;
2. existing expense duplicate is treated as failure instead of link/review;
3. raw email body/subject/sender are persisted without write-time privacy policy;
4. restore/write barrier is incomplete at the email service boundary;
5. provider parsing is hardcoded and limited;
6. email receipt source insertion conflicts are ignored;
7. receipt side effects are not consistently dispatched;
8. no durable parse/error diagnostics;
9. no mailbox/provider sync ledger/cursor if real email-provider ingestion is intended.

Current state: **beta utility ingestion, not production-clean email pipeline**.

---

# Severity scale

- **P0 / Critical:** can silently duplicate expenses, leak raw private email data, or write during restore.
- **P1 / High:** common pipeline correctness/privacy/lifecycle gap.
- **P2 / Medium:** edge correctness, diagnostics, provider coverage, regression risk.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Provider parser works for Amazon/Apple/Uber/etc. | Partial. Amazon/Uber/Apple exist. No parser registry and no broad “etc.” support. |
| Email source stored | Mostly yes via `EmailReceiptSource`; insert conflict result is ignored. |
| Receipt lifecycle used | Partial. Uses `saveEmailReceipt()`, but not the full `processEmailReceipt()` lifecycle path. |
| Matching to expense works | Partial. Creates a new expense; does not first match/link to existing expense cleanly. |
| Warranty/price/subscription effects gated | Partial/unclear. Service path does not call receipt post-save side dispatcher. |
| Duplicate email skipped | Partial. Same message ID is skipped; forwarded/re-sent same receipt can bypass. |
| No duplicate expense | Not guaranteed. Existing duplicate expense result is treated as failure instead of linking. |
| Analytics counts once | Not guaranteed under duplicate/resend/forward cases. |

---

# Positive findings to preserve

## PF-01 — Provider-specific parser layer exists

`EmailReceiptIngestionService` has hardcoded provider parsers:

```text
AmazonReceiptParser
UberReceiptParser
AppleReceiptParser
```

and fallback detection for unknown providers by trying all known parsers.

## PF-02 — Email source metadata exists

`EmailReceiptSource` stores:

```text
receiptId
emailSender
emailSubject
emailMessageId
parsedAt
provider
confidence
fingerprint
```

and links to `ScannedReceipt` with `CASCADE`.

## PF-03 — Message-ID uniqueness exists

`EmailReceiptSource` has a unique index on:

```text
emailMessageId
```

and `EmailReceiptIngestionService` checks `getByMessageId()` before doing work.

## PF-04 — Email-created expenses use transaction lifecycle

The service creates expenses through:

```kotlin
TransactionLifecycleCoordinator.createExpense(request, SideEffectMode.DEFER)
```

inside the outer transaction, then dispatches post-creation side effects after commit.

That is the right direction.

## PF-05 — Receipt-expense linking uses `ReceiptLinkService`

Email-created expense rows are linked with:

```text
linkType = EMAIL_RECEIPT
source = EMAIL_RECEIPT
```

which preserves the receipt/expense relationship.

## PF-06 — `saveEmailReceipt()` sets lifecycle metadata and timestamps

`ReceiptLifecycleCoordinator.saveEmailReceipt()` overrides:

```text
sourceType = EMAIL
documentType = EMAIL_RECEIPT
processingStatus = PARSED
createdAt = now
updatedAt = now
```

and writes a `RECEIPT_SAVED` event.

---

# Issue P1-01 — Duplicate fingerprint includes message ID, so forwarded/re-sent receipts can bypass dedupe

## Severity

P1 / High

## Evidence

`EmailReceiptIngestionService.createFingerprint()` builds:

```text
merchant_lowercase + roundedAmount + dateBucket + messageId
```

when `messageId` is nonblank.

Then the service checks:

```text
emailReceiptDao.getByFingerprint(fingerprint)
```

and `findExistingScannedReceipt(fingerprint)`.

But `findExistingScannedReceipt()` recomputes scanned-receipt fingerprints without message ID.

So if the same receipt is forwarded or re-sent with a different message ID, the dedupe fingerprint changes and will not match the previous email source or scanned receipt.

## Impact

Same real-world receipt can create duplicate receipts/expenses when:

```text
email is forwarded
provider re-sends with new Message-ID
mailbox import imports duplicate copy
user has same email in multiple folders
```

## Fixing strategy

Separate identity fingerprint from content fingerprint.

## Implementation plan

1. Replace current single fingerprint with:

```kotlin
messageFingerprint = canonicalMessageId(messageId)
contentFingerprint = merchant + amount + transactionDate + currency + provider/orderNumber
```

2. Store both fields or encode both in metadata.

3. Dedupe order:

```text
messageId exact
→ provider order number
→ content fingerprint
→ scanned receipt semantic fingerprint
→ existing expense duplicate
```

4. Add DB unique/index for content fingerprint if safe:

```text
provider + contentFingerprint
```

or maintain non-unique but resolve deterministically.

5. Tests:

```text
same_message_id_skipped
same_receipt_different_message_id_skipped_by_content_fingerprint
forwarded_email_duplicate_skipped
same_amount_same_merchant_different_day_not_skipped
same_amount_same_merchant_same_day_different_order_number_not_skipped_if_order_differs
```

---

# Issue P1-02 — Existing expense duplicate is treated as failure, not link/review

## Severity

P1 / High

## Evidence

`createExpenseFromReceipt()` only treats `CreateExpenseResult.Created` as success.

For all other outcomes, including likely `DuplicateSkipped`, it throws:

```text
EmailReceiptExpenseCreationException
```

Then the outer pipeline returns:

```text
ParseError("Failed to create expense from receipt")
```

## Impact

If the email receipt matches an already-existing manual/notification/bank expense, the pipeline can fail instead of:

```text
linking the receipt to the existing expense
marking receipt AUTO_MATCHED/MANUALLY_MATCHED
showing successful duplicate-safe ingestion
```

User-visible symptom:

```text
email receipt import failed
```

when the correct result should be:

```text
receipt linked to existing transaction
```

## Fixing strategy

Handle `DuplicateSkipped(existingExpenseId)` as a valid email-ingestion outcome.

## Implementation plan

1. Change result model:

```kotlin
sealed class EmailReceiptResult {
    data class Success(val receiptId: Long, val expenseIds: List<Long>) : EmailReceiptResult()
    data class LinkedExisting(val receiptId: Long, val expenseId: Long) : EmailReceiptResult()
    data class DuplicateReceipt(val existingReceiptId: Long) : EmailReceiptResult()
    data class NeedsReview(val receiptId: Long, val reviewId: Long) : EmailReceiptResult()
    data class ParseError(val reason: String) : EmailReceiptResult()
}
```

2. In `createExpenseFromReceipt()`:

```kotlin
when (result) {
    Created -> link new expense
    DuplicateSkipped -> link receipt to result.existingExpenseId
    ValidationFailed -> create pending review
    InsertConflict -> resolve by dedupe key, then link or review
}
```

3. Tests:

```text
email_receipt_duplicate_existing_expense_links_receipt
email_receipt_duplicate_existing_expense_returns_LinkedExisting
email_receipt_validation_failure_creates_pending_review
email_receipt_insert_conflict_resolves_existing_expense
```

---

# Issue P1-03 — Service path only partially uses receipt lifecycle

## Severity

P1 / High

## Evidence

`EmailReceiptIngestionService` manually:

```text
detects provider
parses email
creates ScannedReceipt
calls ReceiptLifecycleCoordinator.saveEmailReceipt()
creates EmailReceiptSource
calls ProcessReceiptUseCase
creates expense
links receipt
```

It does not use the coordinator method:

```kotlin
ReceiptLifecycleCoordinator.processEmailReceipt(EmailReceiptData)
```

The coordinator path writes `RECEIPT_SAVED` and dispatches receipt side effects, but the service path performs its own orchestration.

## Impact

Two email receipt ingestion contracts exist.

This creates mixed behavior for:

```text
dedupe
events
side effects
privacy
restore guard
expense creation
receipt matching
warranty/price/subscription hooks
```

## Fixing strategy

Make one lifecycle owner for email receipts.

## Implementation plan

Preferred:

1. Move provider parsing into the lifecycle coordinator or a dedicated `EmailReceiptLifecycleCoordinator`.

2. Single flow:

```text
receive email
→ provider parse
→ dedupe message/content/semantic
→ save ScannedReceipt + EmailReceiptSource + RECEIPT_SAVED event atomically
→ match/link existing expense or create review/expense
→ dispatch receipt and transaction side effects post-commit
```

3. Deprecate direct manual service orchestration.

4. Tests:

```text
all_email_ingestion_paths_write_same_events
all_email_ingestion_paths_use_same_dedupe
all_email_ingestion_paths_dispatch_same_side_effects
```

---

# Issue P1-04 — Raw email body/subject/sender are persisted without write-time privacy policy

## Severity

P1 / High

## Evidence

`EmailReceiptIngestionService` stores:

```kotlin
rawOcrText = emailBody.take(5000)
```

and `EmailReceiptSource` stores:

```text
emailSender
emailSubject
emailMessageId
```

There is no visible privacy gate or raw-email storage mode at write time.

`DataRetentionWorker` previously only clearly covered raw notification and raw OCR retention; email-specific raw body/source retention was noted as incomplete in Pipeline 8.

## Impact

Email receipts can contain highly sensitive data:

```text
name
address
email
order number
payment card last 4
delivery location
purchased items
account identifiers
```

A user may want receipt-derived expenses without raw email body retention.

## Fixing strategy

Add write-time email raw-data storage policy.

## Implementation plan

1. Extend privacy settings:

```kotlin
enum class EmailReceiptStorageMode {
    STORE_RAW,
    STORE_REDACTED,
    STORE_METADATA_ONLY,
    DO_NOT_STORE_RAW
}
```

2. Apply before `ScannedReceipt` / `EmailReceiptSource` insert:

```text
rawOcrText = raw/redacted/empty
emailSubject = raw/redacted/empty/hash
emailSender = raw/domain-only/hash depending policy
messageId = hash or raw according to policy
```

3. Parser receives raw body in memory, but DB stores according to policy.

4. Add retention target:

```text
EmailReceiptSourceRetentionTarget
```

5. Tests:

```text
email_metadata_only_stores_no_raw_body
email_metadata_only_redacts_subject
email_raw_disabled_still_parses_expense
email_retention_purges_subject_body_after_cutoff
backup_redacted_excludes_email_subject_and_body
```

---

# Issue P1-05 — Restore/write barrier is incomplete at email service boundary

## Severity

P1 / High

## Evidence

`ReceiptLifecycleCoordinator.saveEmailReceipt()` checks restore maintenance mode.

But `EmailReceiptIngestionService` itself does not inject/check `RestoreMaintenanceMode`.

There is a direct write path before/around lifecycle calls:

```kotlin
emailReceiptDao.insertOrIgnore(emailSource)
```

Especially in the existing scanned receipt branch, the service can insert `EmailReceiptSource` without calling `saveEmailReceipt()` first.

## Impact

During restore, email source rows can still be mutated by this service path.

## Fixing strategy

Put restore/write barrier at the email ingestion entry point and before every direct DAO write.

## Implementation plan

1. Inject `RestoreMaintenanceMode` or `DatabaseWriteBarrier` into `EmailReceiptIngestionService`.

2. At the top:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    return EmailReceiptResult.BlockedRestore
}
```

3. Also guard direct email source insert helper:

```kotlin
private suspend fun insertEmailSourceGuarded(...)
```

4. Tests:

```text
restore_blocks_email_receipt_ingestion_before_insert
restore_blocks_existing_scanned_receipt_email_source_insert
restore_blocks_batch_email_ingestion
```

---

# Issue P1-06 — Email source insert conflicts are ignored

## Severity

P1 / High

## Evidence

`EmailReceiptDao.insertOrIgnore()` returns the inserted row ID or `-1` on conflict.

The service ignores the return value in:

```text
existing scanned receipt branch
normal newly saved receipt branch
coordinator processEmailReceipt branch
```

## Impact

If the message-ID unique constraint fires after a receipt was saved, the pipeline can continue with inconsistent state:

```text
ScannedReceipt exists
EmailReceiptSource not inserted
dedupe source missing
expense may be created or rolled back depending later outcome
```

At minimum, the result is not semantically correct.

## Fixing strategy

Treat insert conflicts as domain outcomes.

## Implementation plan

1. Add DAO helper:

```kotlin
suspend fun insertOrResolve(source: EmailReceiptSource): EmailSourceInsertResult
```

2. Result:

```kotlin
Inserted(id)
Duplicate(existingSource)
Invalid(message)
```

3. If duplicate source exists:
   - return `DuplicateReceipt(existingSource.receiptId)`, or
   - link current parsed result to existing receipt only if safe.

4. Tests:

```text
email_source_message_id_conflict_returns_duplicate
email_source_fingerprint_conflict_returns_duplicate
insert_or_ignore_minus_one_never_ignored
```

---

# Issue P1-07 — Receipt post-save side effects are skipped/inconsistent in service path

## Severity

P1 / High

## Evidence

`ReceiptLifecycleCoordinator.processEmailReceipt()` calls:

```kotlin
sideEffectDispatcher.dispatchAfterSave(saved)
```

But `EmailReceiptIngestionService` uses:

```kotlin
saveEmailReceipt(scannedReceipt)
```

and does not call receipt side-effect dispatcher directly.

It does call transaction post-creation side effects after expense creation, but those are transaction-side effects, not receipt-side effects.

## Impact

Email receipt ingestion may skip or inconsistently run:

```text
receipt matching side effects
warranty extraction
return window creation
price protection detection
item categorization side effects
subscription candidate detection if receipt-side
```

## Fixing strategy

Email receipt save must dispatch the same receipt side effects as camera/gallery receipt save, or explicitly document different behavior.

## Implementation plan

1. Move email path into lifecycle coordinator and call:

```kotlin
dispatchAfterSave(savedEmailReceipt)
```

after the DB transaction commits.

2. Ensure side effects are document-type aware:

```text
EMAIL_RECEIPT should allow matching/item/subscription effects
EMAIL_RECEIPT should skip image-only warranty extraction if no image needed
```

3. Add events for side-effect outcomes.

4. Tests:

```text
email_receipt_dispatches_matching_side_effect
email_receipt_dispatches_item_categorization_when_items_exist
email_receipt_warranty_side_effect_gated_by_document_type
email_receipt_price_protection_side_effect_gated_by_eligibility
```

---

# Issue P1-08 — No pending-review route for uncertain email receipts

## Severity

P1 / High

## Evidence

If provider parsing validates:

```text
amount > 0
merchant nonblank
date > 0
```

the service attempts to create an approved expense immediately.

There is no confidence threshold route:

```text
low confidence → PendingReview
```

`ParsedEmailReceipt.confidence` exists but is only used for `EmailReceiptSource.confidence` and receipt-link confidence.

## Impact

An uncertain parsed email can become a real dashboard expense automatically.

Examples:

```text
wrong total extracted from HTML
authorization/estimate email parsed as receipt
multi-order shipment email
subscription renewal notice without charge
refund/credit email
```

## Fixing strategy

Add confidence and provider policy routing.

## Implementation plan

1. Add thresholds:

```kotlin
EmailReceiptImportPolicy(
    autoCreateExpenseThreshold = 0.90,
    createReviewThreshold = 0.50
)
```

2. Route:

```text
high confidence + no duplicate → create/link expense
medium confidence → PendingReview
low confidence → save receipt only with PARSE_FAILED/NEEDS_REVIEW
```

3. Add provider-specific confidence penalties:

```text
unknown provider
missing order number
HTML parse fallback
multiple totals found
currency missing
date fallback to receivedAt
```

4. Tests:

```text
low_confidence_email_receipt_creates_pending_review
unknown_provider_receipt_goes_to_review
high_confidence_amazon_receipt_auto_creates_expense
refund_email_goes_to_review_or_refund_policy
```

---

# Issue P2-09 — Provider parser registry is hardcoded and not DI/test friendly

## Severity

P2 / Medium

## Evidence

`EmailReceiptIngestionService` constructs parsers directly:

```kotlin
private val amazonParser = AmazonReceiptParser()
private val uberParser = UberReceiptParser()
private val appleParser = AppleReceiptParser()
```

## Impact

Adding providers requires editing the service.

It is harder to test parser selection, provider priority, parser failures, and feature flags.

## Fixing strategy

Use an injectable parser registry.

## Implementation plan

1. Add:

```kotlin
interface EmailReceiptProviderParser {
    val providerId: String
    fun canParse(sender: String, subject: String, body: String): Boolean
    fun parse(body: String, receivedAt: Long): ParsedEmailReceipt?
}
```

2. Inject:

```kotlin
Set<@JvmSuppressWildcards EmailReceiptProviderParser>
```

with Hilt multibindings.

3. Parser selection returns provenance:

```text
providerId
parserVersion
confidence
fallbackUsed
```

4. Tests:

```text
parser_registry_selects_amazon
parser_registry_falls_back_to_apple_when_sender_unknown
new_provider_can_be_added_without_modifying_service
parser_failure_records_provider_attempts
```

---

# Issue P2-10 — Currency/amount support is narrow

## Severity

P2 / Medium

## Evidence

`BaseEmailParser` amount regex visibly supports symbols/codes for:

```text
EUR
USD
GBP
€
$
£
```

This is narrower than the app’s general multi-currency ambitions.

## Impact

Receipts in other currencies can fail parsing or be parsed with wrong currency/amount behavior.

Examples:

```text
CAD
AUD
CHF
PLN
RON
TRY
JPY
```

## Fixing strategy

Use shared money parsing/currency primitives.

## Implementation plan

1. Replace local parser currency regex with shared `CurrencyCode` / money parser.

2. Support ISO currency codes and localized formats.

3. Use home currency only as explicit fallback with warning.

4. Tests:

```text
email_receipt_CAD_parses_amount_and_currency
email_receipt_CHF_parses_amount_and_currency
email_receipt_PLN_parses_amount_and_currency
email_receipt_missing_currency_uses_home_currency_with_warning_or_review
```

---

# Issue P2-11 — No durable parse/error diagnostics

## Severity

P2 / Medium

## Evidence

The service returns in-memory results:

```text
Success
Duplicate
ParseError
```

and logs with `Timber`.

It does not write durable events for:

```text
EMAIL_RECEIVED
PROVIDER_DETECTED
PARSER_FAILED
DUPLICATE_DETECTED
REVIEW_CREATED
EXPENSE_CREATED
LINK_FAILED
PIPELINE_ERROR
```

`ReceiptEvent` is only written after receipt save. If parsing fails before save, there may be no durable record.

## Impact

If a user says “my Amazon receipt email did nothing,” the app cannot reliably answer where it failed.

## Fixing strategy

Add email ingestion event ledger or extend receipt event with pre-receipt diagnostics.

## Implementation plan

1. Add entity:

```kotlin
EmailIngestionEvent(
    id,
    messageIdHash,
    provider,
    stage,
    outcome,
    receiptId,
    expenseId,
    reviewId,
    timestamp,
    reason,
    errorClass,
    errorMessage
)
```

2. Stages:

```text
RECEIVED
PROVIDER_DETECTED
PARSE_FAILED
PARSED
DUPLICATE_MESSAGE_ID
DUPLICATE_CONTENT
RECEIPT_SAVED
EXPENSE_CREATED
LINKED_EXISTING_EXPENSE
REVIEW_CREATED
FAILED
```

3. Tests:

```text
parse_failure_writes_email_ingestion_event
duplicate_message_id_writes_event
expense_created_writes_event
link_failure_writes_event
```

---

# Issue P2-12 — No mailbox/provider sync ledger or cursor

## Severity

P2 / Medium, P1 if real mailbox ingestion is intended

## Evidence

The current service accepts:

```text
emailBody
sender
subject
receivedAt
messageId
```

There is no visible Gmail/Outlook/IMAP connector, mailbox cursor, folder ID, sync run, or per-message ingestion status.

## Impact

If this is meant to be real mailbox ingestion, it cannot safely resume:

```text
last processed message
folder/label
provider account
page token
partial batch failure
already imported but unacknowledged messages
```

## Fixing strategy

If email-provider sync is in scope, add a sync lifecycle layer.

## Implementation plan

1. Add entities:

```kotlin
EmailAccountConnection
EmailSyncRun
EmailMessageImport
```

2. Track:

```text
provider
accountId
folder/label
messageId
threadId
receivedAt
cursorBefore
cursorAfter
status
receiptId
expenseId
errorCode
```

3. Batch import should checkpoint after each page/message.

4. Tests:

```text
email_sync_resume_after_crash
email_sync_skips_already_imported_message
email_sync_partial_failure_records_failed_message
email_sync_provider_disconnect_blocks_import
```

---

# Issue P2-13 — Batch processing lacks summary/progress/backpressure contract

## Severity

P2 / Medium

## Evidence

`processBatch()` simply maps emails:

```kotlin
emails.map { processEmailReceipt(...) }
```

The service-level mutex serializes each call.

## Impact

Large mailbox imports have no:

```text
progress
checkpoint
cancellation checkpoint
rate limiting
summary counts
retry policy
durable failed-message list
```

## Fixing strategy

Batch ingestion should be a worker-backed import job.

## Implementation plan

1. Add:

```kotlin
EmailReceiptImportWorker
```

2. Use sync run/import tables if provider ingestion is real.

3. Return summary:

```kotlin
EmailBatchIngestionSummary(
    total,
    created,
    linkedExisting,
    duplicates,
    reviews,
    parseErrors,
    failures
)
```

4. Tests:

```text
batch_import_reports_summary_counts
batch_import_can_cancel_after_current_message
batch_import_retry_only_failed_or_unprocessed_messages
```

---

# Recommended fixing order

## PR 1 — Dedupe contract hardening

Files:

```text
EmailReceiptIngestionService.kt
EmailReceiptSource.kt
EmailReceiptDao.kt
ReceiptDuplicateDetector.kt
```

Fix:

```text
- separate message ID dedupe from content fingerprint
- canonicalize message IDs
- handle insertOrIgnore conflict results
- tests for forwarded/re-sent emails
```

## PR 2 — Existing-expense linking path

Files:

```text
EmailReceiptIngestionService.kt
TransactionLifecycleCoordinator.kt
ReceiptLinkService.kt
ReceiptTransactionMatcher.kt
```

Fix:

```text
- DuplicateSkipped is success/link-existing
- validation/low-confidence creates PendingReview
- no duplicate expense on existing manual/notification/bank transaction
```

## PR 3 — Single email lifecycle owner

Files:

```text
ReceiptLifecycleCoordinator.kt
EmailReceiptIngestionService.kt
new EmailReceiptLifecycleCoordinator.kt optional
```

Fix:

```text
- one flow owns save/source/event/match/create/link/side-effects
- remove mixed service/coordinator behavior
```

## PR 4 — Privacy/write-time raw email policy

Files:

```text
PrivacySettings.kt
EmailReceiptIngestionService.kt
ReceiptLifecycleCoordinator.kt
DataRetentionWorker.kt
ExportAnonymizer.kt
```

Fix:

```text
- raw email body/subject/sender storage modes
- redaction at write time
- retention target for email artifacts
```

## PR 5 — Restore/write barrier

Files:

```text
EmailReceiptIngestionService.kt
EmailReceiptDao.kt call sites
DatabaseWriteBarrier if available
```

Fix:

```text
- restore mode blocks all email source/receipt/expense writes
```

## PR 6 — Parser registry and currency support

Files:

```text
EmailReceiptParser.kt
AmazonReceiptParser.kt
AppleReceiptParser.kt
UberReceiptParser.kt
EmailReceiptIngestionService.kt
```

Fix:

```text
- Hilt parser registry
- parser provenance
- shared money parser/currency support
```

## PR 7 — Diagnostics and batch/import ledger

Files:

```text
new EmailIngestionEvent.kt/Dao
EmailReceiptIngestionService.kt
optional EmailReceiptImportWorker.kt
```

Fix:

```text
- durable events for parse/duplicate/review/expense/link/failure
- batch summary and resume support
```

---

# Golden tests to add

```text
amazon_receipt_email_parses_and_creates_email_receipt_source
apple_receipt_email_parses_and_creates_email_receipt_source
uber_receipt_email_parses_and_creates_email_receipt_source
same_message_id_skips_duplicate
same_receipt_different_message_id_skips_by_content_fingerprint
forwarded_email_duplicate_skipped
email_source_insert_conflict_returns_duplicate_not_success
existing_manual_expense_is_linked_not_duplicated
existing_notification_expense_is_linked_not_duplicated
duplicate_expense_result_is_LinkedExisting
low_confidence_email_receipt_creates_pending_review
unknown_provider_receipt_goes_to_review
email_receipt_save_dispatches_receipt_side_effects
email_receipt_link_failure_rolls_back_expense_creation
restore_mode_blocks_email_ingestion
raw_email_metadata_only_stores_no_raw_body_or_subject
email_retention_purges_raw_body_subject
email_receipt_CAD_or_CHF_currency_parses_correctly
parse_failure_writes_durable_email_ingestion_event
batch_import_reports_created_duplicate_failed_counts
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "EmailReceiptIngestionService" app/src/main/java
grep -R "processEmailReceipt" app/src/main/java
grep -R "EmailReceiptSource(" app/src/main/java
grep -R "insertOrIgnore(emailSource" app/src/main/java
grep -R "createFingerprint" app/src/main/java
grep -R "rawOcrText = emailBody" app/src/main/java
grep -R "AmazonReceiptParser()" app/src/main/java
grep -R "UberReceiptParser()" app/src/main/java
grep -R "AppleReceiptParser()" app/src/main/java
grep -R "ExpenseSource.EMAIL_RECEIPT" app/src/main/java
```

Allowed email receipt write paths should become:

```text
EmailReceiptLifecycleCoordinator or ReceiptLifecycleCoordinator only
Room migrations
approved tests/debug tools
```

Definition of done:

```text
- Email dedupe works by message ID and content fingerprint separately.
- Same receipt with different message ID does not create duplicate expense.
- Existing expense duplicate links receipt instead of returning ParseError.
- Low-confidence/ambiguous email receipts go to PendingReview.
- Email receipt save/source/event/link/expense creation is owned by one lifecycle coordinator.
- Receipt-side effects are consistently dispatched or explicitly gated.
- Raw email body/subject/sender storage obeys privacy policy at write time.
- Restore mode blocks every email receipt write path.
- Email source insert conflicts are resolved as domain outcomes.
- Provider parsers are registry-based and carry provenance.
- Durable email ingestion diagnostics exist for parse/duplicate/review/expense/link/failure.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `EmailReceiptIngestionService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `EmailReceiptParser.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt

- `AmazonReceiptParser.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt

- `AppleReceiptParser.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt

- `UberReceiptParser.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt

- `EmailReceiptData.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/EmailReceiptData.kt

- `ReceiptLifecycleCoordinator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `EmailReceiptSource.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt

- `EmailReceiptDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt

- `ScannedReceiptDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt