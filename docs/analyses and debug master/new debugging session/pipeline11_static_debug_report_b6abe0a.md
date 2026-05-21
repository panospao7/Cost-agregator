# Pipeline 11 Static Debug Report — Email Receipt Ingestion

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 11 is **materially improved**, but **not closed**.

Major improvements now present:

```text
EmailReceiptIngestionService is now mostly detector/delegate only
ReceiptLifecycleCoordinator.processEmailReceipt() owns receipt/source/expense/link flow
content fingerprint no longer includes messageId
DuplicateSkipped existing expense is handled as link-existing
write barrier exists at EmailReceiptIngestionService entry
email sender/subject/body/messageId are sanitized via RawContentSanitizer
receipt side effects are now dispatched after email save
PipelineDiagnosticEvent is used for some dedupe/write-blocked outcomes
```

However, several important bugs remain.

Highest user-impact risks:

1. **Email source insert conflict is only partially handled**. Message-ID conflict with different/missing fingerprint can still create receipt/expense without `EmailReceiptSource`.
2. **Low-confidence / validation-failed receipts still have no pending-review route**. Some receipts become auto-approved; others become saved receipt with no expense/review.
3. **Transaction side effects are dispatched twice**: coordinator dispatches after email processing, then service dispatches again.
4. **Content fingerprint is too weak** and also privacy-leaky because it stores merchant/amount/date bucket as plaintext.
5. **Message-ID dedupe depends on sanitized `sourceFingerprint` instead of stable hashed message ID**.
6. **Parse failures before receipt save are not durably logged**.
7. **Email raw-storage mode does not clearly cover parsed item details/fingerprint/diagnostic/export fields**.
8. **Batch ingestion still has no import-run ledger, resumability, or summary.**

Current status: **yellow**. The core lifecycle refactor is good, but dedupe/conflict/review/privacy/diagnostic hardening is still needed.

---

# Sources checked

- Commit page:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 11 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-11-email-receipt-ingestion-debug-report.md

- Current code:
  - `EmailReceiptIngestionService.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
  - `ReceiptLifecycleCoordinator.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
  - `EmailReceiptSource.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt
  - `EmailReceiptDao.kt`  
    https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt
  - Provider parser directory:  
    https://github.com/panospao7/Cost-agregator/tree/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/email/provider

---

# 1. Tracker reconciliation

Master tracker currently says:

| ID | Tracker status |
|---|---|
| P11-P1-01 | fixed |
| P11-P1-02 | fixed |
| P11-P1-03 | TODO |
| P11-P1-04 | partial |
| P11-P1-05 | fixed |
| P11-P1-06 | TODO |
| P11-P1-07 | TODO |
| P11-P1-08 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P11-P1-01 | **Mostly fixed / caveat** | `createFingerprint()` is content-only now. But fingerprint is weak: merchant + amount + 5-min date bucket, no currency/provider/order ID, and stored plaintext. |
| P11-P1-02 | **Mostly fixed / caveat** | `DuplicateSkipped` now links receipt to existing expense. But post-creation side effects may run for an existing expense and may run twice. |
| P11-P1-03 | **Mostly fixed / partial** | Service now delegates mutation to coordinator. Caveats: service still dispatches transaction side effects and still owns hardcoded parser selection. |
| P11-P1-04 | **Partial** | Sender/subject/body/messageId are sanitized, but `fingerprint` and `parsedItems` can still persist sensitive derived data. |
| P11-P1-05 | **Mostly fixed / caveat** | Service checks `DatabaseWriteBarrier`; coordinator checks restore mode. But restore-denied path tries to write diagnostics while writes are blocked. |
| P11-P1-06 | **Partial, not TODO** | `insertOrIgnore()` result is checked, but only resolves by fingerprint. Message-ID-only conflict remains unresolved. |
| P11-P1-07 | **Mostly fixed / new caveat** | `sideEffectDispatcher.dispatchAfterSave(saved)` now runs. But transaction side effects are also dispatched by service, likely duplicating coordinator dispatch. |
| P11-P1-08 | **Open** | No confidence-based pending-review route. Validation failure only logs; no review is created. |

Older P2 issues:

| Issue | My status |
|---|---:|
| Provider parser registry hardcoded | **Open** |
| Currency/amount support narrow | **Likely open** |
| Durable parse/error diagnostics | **Partial** |
| Mailbox/provider sync ledger/cursor | **Open** |
| Batch summary/progress/backpressure | **Open** |

---

# 2. Original issue evaluation

## P11-P1-01 — Duplicate fingerprint includes message ID

### Current state

Mostly fixed.

Current `EmailReceiptIngestionService.createFingerprint()` ignores `messageId` and returns:

```text
merchant.lowercase + roundedAmount + dateBucket
```

This fixes the original forwarded/re-sent-email issue where different message IDs changed the dedupe key.

### Remaining bugs

The new fingerprint is too weak:

```text
merchant + amount + 5-minute bucket
```

It omits:

- currency,
- provider,
- provider order number,
- normalized order/reference ID,
- item hash,
- receipt date vs received date confidence,
- sender/account context.

Two different same-merchant/same-amount purchases in the same 5-minute bucket can be collapsed. Conversely, a re-sent receipt with a slightly different parsed timestamp can miss dedupe.

Also, the fingerprint is stored as plaintext in `EmailReceiptSource.fingerprint`, so it can leak merchant/amount/timing even when email storage mode is metadata-only.

### Fix strategy

Use separate stable identifiers:

```kotlin
data class EmailReceiptIdentity(
    val messageIdHash: String?,
    val providerOrderIdHash: String?,
    val contentFingerprintHash: String,
    val semanticFingerprintHash: String?
)
```

Content fingerprint should include:

```text
provider
merchantKey
amount
currency
receiptDateDay
order/reference hash if available
item summary hash if available
```

Store hashes, not raw merchant/amount strings.

---

## P11-P1-02 — Existing expense duplicate treated as failure

### Current state

Mostly fixed.

`ReceiptLifecycleCoordinator.processEmailReceipt()` handles:

```kotlin
CreateExpenseResult.DuplicateSkipped
```

by linking the email receipt to `existingExpenseId`.

### Remaining caveats

1. The existing expense ID is added to `expenseIds`.
2. The coordinator later dispatches `dispatchPostCreationSideEffects(expenseId, EMAIL_RECEIPT)`.
3. The service then dispatches `dispatchPostCreationSideEffects(...)` again for every returned expense ID.

For an existing expense, “post creation” side effects are semantically wrong. The side effect should be:

```text
receipt linked to existing expense
```

not:

```text
expense was newly created
```

### Fix strategy

Return richer outcome:

```kotlin
data class EmailReceiptProcessResult.Success(
    val receiptId: Long,
    val createdExpenseIds: List<Long>,
    val linkedExistingExpenseIds: List<Long>,
    val reviewIds: List<Long>
)
```

Then dispatch:

```text
createdExpenseIds -> transaction post-create side effects
linkedExistingExpenseIds -> receipt-link side effects only
```

---

## P11-P1-03 — Service path only partially uses receipt lifecycle

### Current state

Mostly fixed.

The service comment now accurately says it is a detector/delegate and does not create expenses/receipts directly. It calls:

```kotlin
ReceiptLifecycleCoordinator.processEmailReceipt(...)
```

This removes the old inline fallback path.

### Remaining caveats

- `EmailReceiptIngestionService` still dispatches transaction post-creation side effects after the coordinator already does.
- Parser selection is still hardcoded in service.
- Service still injects many old dependencies that appear unused or no longer should be owned here.
- There are two `EmailReceiptData` types:
  - domain `EmailReceiptData`,
  - data/email batch `EmailReceiptData`.
  This is confusing for agents and future maintainers.

### Fix strategy

Make service purely:

```text
detect + parse + call EmailReceiptLifecycleCoordinator
```

No transaction side effects, no DAO dependencies, no legacy constructor transaction runner.

---

## P11-P1-04 — Raw email body/subject/sender privacy policy

### Current state

Partial.

Good:

- Coordinator reads `emailReceiptStorageMode`.
- `rawEmailBody` is sanitized before becoming `ScannedReceipt.rawOcrText`.
- sender, subject, and message ID are sanitized before `EmailReceiptSource` insert.

Remaining leaks/ambiguities:

1. `EmailReceiptSource.fingerprint` stores raw merchant/amount/date bucket.
2. `ScannedReceipt.parsedItems` can contain purchased item descriptions from raw email.
3. `parsedMerchant`, `parsedTotal`, and `parsedDate` may be acceptable derived data, but product policy should say so explicitly.
4. Debug/export/backup behavior for email-derived fields is not proven.
5. Diagnostics are mostly safe now, but there is no typed privacy policy around email ingestion events.

### User impact

User may choose metadata-only email storage and still retain sensitive item/merchant/order-derived data.

### Fix strategy

Define email storage semantics:

| Mode | Allowed persisted data |
|---|---|
| STORE_RAW | raw sender/subject/body/items |
| STORE_REDACTED | redacted sender/subject/body/items |
| STORE_METADATA_ONLY | provider, timestamps, hashed identifiers, parsed amount/currency/date only |
| DO_NOT_STORE | no sender/subject/body/items/fingerprint plaintext |

Add tests that inspect all tables.

---

## P11-P1-05 — Restore barrier incomplete

### Current state

Mostly fixed.

Good:

- `EmailReceiptIngestionService.processEmailReceipt()` calls `writeBarrier.checkWritesAllowed(...)`.
- `ReceiptLifecycleCoordinator.processEmailReceipt()` checks restore maintenance mode.

Remaining problem:

When coordinator detects blocked writes, it calls:

```kotlin
emitEmailReceiptDiagnostic(...)
```

which inserts a `PipelineDiagnosticEvent`.

If restore mode blocks writes, this diagnostic insert is itself a DB write during restore. It may fail, or worse, bypass the intended write policy.

### Fix strategy

For write-blocked paths:

- either do not write Room diagnostics,
- or use a restore-safe diagnostic sink,
- or explicitly allow diagnostics during restore with a documented exception.

Do not silently attempt normal DAO inserts while writes are blocked.

---

## P11-P1-06 — Email source insert conflicts ignored

### Current state

Partial.

The coordinator now checks:

```kotlin
val sourceId = emailReceiptDao.insertOrIgnore(emailSource)
if (sourceId == -1L) {
    val existing = emailReceiptDao.getByFingerprint(fingerprint)
    if (existing != null) {
        scannedReceiptDao.deleteById(savedId)
        capturedDuplicate = Duplicate(existing.receiptId)
        return@withTransaction
    }
}
```

This fixes fingerprint-conflict cases.

But the unique index is on:

```text
emailMessageId
```

So conflict can be caused by message ID, while fingerprint lookup returns null.

In that case the code continues after a failed source insert, writes `RECEIPT_SAVED`, and may create/link an expense. Result:

```text
ScannedReceipt exists
expense may exist
EmailReceiptSource missing
message ID dedupe source missing
audit/source trace incomplete
```

### Fix strategy

Implement:

```kotlin
sealed interface EmailSourceInsertResult {
    data class Inserted(val id: Long)
    data class Duplicate(val existingSource: EmailReceiptSource, val reason: Reason)
    data class ConflictUnresolved(val message: String)
}
```

Resolution order:

```text
messageIdHash -> fingerprintHash -> receiptId -> fail/rollback
```

If conflict unresolved, rollback receipt insert.

---

## P11-P1-07 — Receipt post-save side effects skipped

### Current state

Mostly fixed, but introduced a new double-dispatch caveat.

Coordinator now runs:

```kotlin
sideEffectDispatcher.dispatchAfterSave(saved)
```

after email receipt is committed.

That fixes the original issue.

But coordinator also dispatches transaction post-creation side effects, and service dispatches them again for every returned expense ID.

### User impact

Potential duplicate:

- budget recalculation,
- recurring matching,
- anomaly/intelligence events,
- notification/recommendation side effects,
- logs/diagnostics.

Many side effects may be idempotent, but this should not be assumed.

### Fix strategy

Only the lifecycle owner should dispatch side effects.

The service should return coordinator result without additional dispatch.

---

## P11-P1-08 — No pending-review route for uncertain email receipts

### Current state

Open.

Current service validates only:

```text
amount > 0
merchant nonblank
date > 0
```

If valid, coordinator auto-creates an approved expense.

If transaction creation returns `ValidationFailed`, coordinator only logs:

```text
Email receipt validation failed
```

Then returns success with receipt ID and empty expense list. There is no pending review.

### User impact

Two bad cases:

1. Low-confidence parse becomes approved expense.
2. Transaction validation failure produces receipt but no expense/review, so user may not know action is needed.

### Fix strategy

Add policy:

```kotlin
EmailReceiptImportPolicy(
    autoCreateExpenseThreshold = 0.90,
    createReviewThreshold = 0.50
)
```

Route:

```text
high confidence -> create/link expense
medium confidence -> PendingReview
validation failed -> PendingReview with errors
low confidence -> save receipt + parse failed/needs review event
```

---

# 3. New/current issues found

## P11-NEW-01 — Transaction side effects are likely dispatched twice

### Severity

P1/P2.

### Evidence

Coordinator dispatches transaction side effects after email processing. Service also loops over `coordinatorResult.expenseIds` and dispatches transaction post-creation side effects.

### Impact

Side effects can duplicate or observe already-mutated state.

### Fix

Remove service-level dispatch. Coordinator is the lifecycle owner.

---

## P11-NEW-02 — Existing-expense links are treated as created-expense side effects

### Severity

P1/P2.

### Evidence

`DuplicateSkipped(existingExpenseId)` is added to `expenseIds`, then passed through post-creation side-effect dispatch.

### Impact

Existing expense may be reprocessed as newly created.

### Fix

Separate created vs linked-existing result lists.

---

## P11-NEW-03 — Email content fingerprint can falsely dedupe distinct purchases

### Severity

P1.

### Evidence

Fingerprint is only:

```text
merchant + rounded amount + 5-minute date bucket
```

### Impact

Two same-merchant/same-amount purchases in a short window can collapse into one duplicate.

### Fix

Include currency, provider, order/reference hash, item hash, and day-level receipt date. Store hash.

---

## P11-NEW-04 — Email content fingerprint leaks merchant/amount/date

### Severity

P1/P2 privacy.

### Evidence

`EmailReceiptSource.fingerprint` stores the plaintext fingerprint string.

### Impact

Metadata-only/Do-not-store modes can still leak purchase details through fingerprint.

### Fix

Store only cryptographic hashes, not raw concatenated fields.

---

## P11-NEW-05 — Message-ID dedupe is unstable under sanitization modes

### Severity

P1/P2.

### Evidence

Coordinator dedupes message ID by sanitizing it and checking `ScannedReceipt.sourceFingerprint`. If storage mode redacts/nulls message ID, exact message-ID dedupe is disabled or degraded.

### Impact

Same email can be re-imported if message ID is not stored raw. Or many rows can collide if a redacted sentinel is used incorrectly.

### Fix

Always store a keyed hash for dedupe:

```text
messageIdHash = HMAC(appSecret, canonicalMessageId)
```

This preserves dedupe without retaining raw message ID.

---

## P11-NEW-06 — Parse failures before receipt save are not durable

### Severity

P1/P2.

### Evidence

Service returns `ParseError` for:

```text
could not parse
invalid parsed data
processing exception
```

before coordinator writes any receipt/event in many cases.

### Impact

User says “email receipt did nothing,” and DB may have no record.

### Fix

Add `EmailIngestionEvent` or use `PipelineDiagnosticEvent` at service entry for:

```text
EMAIL_RECEIVED
PROVIDER_DETECTED
PARSE_FAILED
VALIDATION_FAILED
```

Do not store raw body in diagnostics.

---

## P11-NEW-07 — Provider confidence is ignored

### Severity

P1/P2.

### Evidence

Coordinator stores:

```text
ScannedReceipt.confidence = 0.7f
EmailReceiptSource.confidence = 1.0
```

rather than using `ParsedEmailReceipt.confidence`.

### Impact

Low-confidence parser output can auto-create expense, and stored confidence is misleading.

### Fix

Pass parser confidence into `EmailReceiptData` and persist it. Use it for review routing.

---

## P11-NEW-08 — Validation failure silently saves receipt without review

### Severity

P1.

### Evidence

When transaction lifecycle returns `ValidationFailed`, coordinator only logs. It does not create pending review or return a warning result.

### Impact

User may see import success but no expense and no review action.

### Fix

On validation failure:

```text
create PendingReview
write REVIEW_CREATED or EMAIL_REVIEW_CREATED event
return NeedsReview(reviewId)
```

---

## P11-NEW-09 — `insertOrIgnore` conflict cleanup can leave asset/source inconsistencies in future image-backed email receipts

### Severity

P2 now, P1 if email images/attachments are added.

### Evidence

On fingerprint conflict, coordinator deletes `ScannedReceipt` by ID but there is no generalized asset cleanup or event.

Currently email receipts use `imagePath = null`, so impact is limited.

### Fix

Centralize duplicate cleanup through receipt lifecycle cleanup method:

```text
delete receipt row
delete pending review
delete asset if any
write duplicate event safely
```

---

## P11-NEW-10 — Hardcoded parser registry remains

### Severity

P2.

### Evidence

Service directly instantiates:

```text
AmazonReceiptParser
UberReceiptParser
AppleReceiptParser
```

### Impact

Adding providers requires editing service; parser provenance is weak.

### Fix

Use Hilt multibinding parser registry.

---

## P11-NEW-11 — Batch import has no summary, checkpoint, or cancellation contract

### Severity

P2/P1 if mailbox import is intended.

### Evidence

`processBatch()` just maps emails one-by-one.

### Impact

Large mailbox import cannot resume, report progress, or retry failed messages only.

### Fix

Add email import run/message ledger and worker.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize:

1. **Message-ID insert conflict can create receipt/expense without email source row.**
2. **Low-confidence and validation-failed receipts do not route to review.**
3. **Transaction side effects are likely dispatched twice.**
4. **Existing duplicate expenses receive post-create side effects.**
5. **Weak fingerprint can false-dedupe distinct purchases.**
6. **Parse failures before save are not durably visible.**
7. **Email privacy mode can leak via fingerprint and parsed items.**
8. **Message-ID dedupe degrades under redacted/do-not-store modes.**

## Architectural / hardening work

Important but lower immediate urgency:

1. Parser registry / provider provenance.
2. Email import run ledger.
3. Batch progress and backpressure.
4. Shared receipt duplicate cleanup method.
5. Typed email ingestion result model.
6. Static guard that service does not mutate or dispatch lifecycle side effects.
7. Currency parsing via shared money parser.

---

# 5. Recommended implementation plan

## PR 1 — Email source conflict correctness

### Goal

No receipt/expense can be created when `EmailReceiptSource` insert failed unresolved.

### Files

- `EmailReceiptDao.kt`
- `ReceiptLifecycleCoordinator.kt`
- `EmailReceiptSource.kt`

### Tasks

1. Add `messageIdHash` field or repurpose safely.
2. Add `insertOrResolveEmailSource()`.
3. On `insertOrIgnore == -1`, resolve by:
   - message ID hash,
   - fingerprint hash,
   - receipt ID.
4. If unresolved, rollback.
5. Write duplicate diagnostic/event.

### Acceptance tests

```text
email_source_message_id_conflict_returns_duplicate
email_source_fingerprint_conflict_returns_duplicate
email_source_conflict_unresolved_rolls_back_receipt
email_success_always_has_email_source_row
```

---

## PR 2 — Remove duplicate transaction side effects

### Goal

Only lifecycle owner dispatches side effects once.

### Files

- `EmailReceiptIngestionService.kt`
- `ReceiptLifecycleCoordinator.kt`
- tests

### Tasks

1. Remove service-level `dispatchPostCreationSideEffects`.
2. Split result into:
   - created expenses,
   - linked existing expenses.
3. Dispatch post-create only for created expenses.
4. Add receipt-link event/side effect for existing link.

### Acceptance tests

```text
email_created_expense_dispatches_transaction_side_effect_once
email_duplicate_existing_expense_does_not_dispatch_create_side_effect
email_link_existing_dispatches_receipt_link_effect
```

---

## PR 3 — Pending review route

### Goal

Ambiguous email receipts are actionable, not silently accepted/lost.

### Files

- `ReceiptLifecycleCoordinator.kt`
- `PendingReviewDao.kt`
- `EmailReceiptIngestionService.kt`
- result models

### Tasks

1. Add `NeedsReview(reviewId)` result.
2. Use parser confidence.
3. Route validation failures to pending review.
4. Route medium confidence to pending review.
5. Write `EMAIL_REVIEW_CREATED`.

### Acceptance tests

```text
low_confidence_email_receipt_creates_pending_review
transaction_validation_failed_creates_pending_review
unknown_provider_receipt_goes_to_review
high_confidence_amazon_auto_creates_expense
```

---

## PR 4 — Privacy-safe identity/fingerprint model

### Goal

Dedupe without retaining raw sensitive identifiers.

### Files

- `EmailReceiptSource.kt`
- migration
- `RawContentSanitizer.kt`
- duplicate detector

### Tasks

1. Add hashed columns:
   - `messageIdHash`
   - `providerOrderIdHash`
   - `contentFingerprintHash`
2. Stop storing plaintext merchant/amount/date in `fingerprint`.
3. Use HMAC/keyed hash for message ID.
4. Define metadata-only and do-not-store derived-data rules.
5. Sanitize or suppress `parsedItems`.

### Acceptance tests

```text
metadata_only_stores_no_plaintext_fingerprint
do_not_store_keeps_message_id_dedupe_via_hash
parsed_items_redacted_when_policy_requires
same_receipt_different_message_id_dedupes_by_content_hash
```

---

## PR 5 — Durable pre-save diagnostics

### Goal

Every email ingestion attempt has a trace.

### Files

- new `EmailIngestionEvent.kt` or `PipelineDiagnosticEvent` usage
- `EmailReceiptIngestionService.kt`
- `ReceiptLifecycleCoordinator.kt`

### Tasks

1. Record:
   - `EMAIL_RECEIVED`
   - `PROVIDER_DETECTED`
   - `PARSE_FAILED`
   - `VALIDATION_FAILED`
   - `DUPLICATE`
   - `RECEIPT_SAVED`
   - `EXPENSE_CREATED`
   - `REVIEW_CREATED`
   - `LINK_FAILED`
2. Store only hashes/safe metadata.
3. Do not write Room diagnostics when restore writes are blocked unless using safe channel.

### Acceptance tests

```text
parse_failure_writes_safe_diagnostic
provider_detected_writes_event
validation_failed_writes_event
restore_blocked_does_not_write_room_diagnostic_unsafely
```

---

## PR 6 — Parser registry and provider provenance

### Goal

Provider support is extensible and observable.

### Files

- `EmailReceiptParser.kt`
- provider parsers
- Hilt module
- `EmailReceiptIngestionService.kt`

### Tasks

1. Create parser interface:
   ```kotlin
   interface EmailReceiptProviderParser {
       val providerId: String
       val parserVersion: String
       fun canParse(...)
       fun parse(...): ParsedEmailReceipt?
   }
   ```
2. Use DI set/multibinding.
3. Store parser ID/version/fallback flag.
4. Record parser failures safely.

### Acceptance tests

```text
parser_registry_selects_amazon
unknown_sender_fallback_records_parser_used
new_provider_added_without_service_change
parser_failure_records_safe_event
```

---

## PR 7 — Batch/mailbox import ledger

### Goal

Batch email ingestion can resume and report progress.

### Files

- new `EmailImportRun.kt`
- new `EmailMessageImport.kt`
- worker if needed
- `processBatch()`

### Tasks

1. Add run/message tables.
2. Track cursor/message ID hash/status.
3. Return summary counts.
4. Support cancellation checkpoints.
5. Retry only failed/unprocessed messages.

### Acceptance tests

```text
batch_import_reports_summary_counts
batch_import_records_each_message_outcome
batch_import_resume_skips_processed_messages
batch_import_retry_only_failed_messages
```

---

# 6. Suggested tracker updates

Update Pipeline 11 tracker:

| ID | Suggested status |
|---|---|
| P11-P1-01 | Mostly fixed / weak fingerprint caveat |
| P11-P1-02 | Mostly fixed / side-effect caveat |
| P11-P1-03 | Mostly fixed / partial |
| P11-P1-04 | Partial |
| P11-P1-05 | Mostly fixed / diagnostic caveat |
| P11-P1-06 | Partial |
| P11-P1-07 | Mostly fixed / duplicate side-effect caveat |
| P11-P1-08 | TODO / open |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P11-NEW-01 | P1/P2 | Transaction side effects likely dispatched twice |
| P11-NEW-02 | P1/P2 | Existing-expense links treated as created-expense side effects |
| P11-NEW-03 | P1 | Email content fingerprint can falsely dedupe distinct purchases |
| P11-NEW-04 | P1/P2 | Email content fingerprint leaks merchant/amount/date |
| P11-NEW-05 | P1/P2 | Message-ID dedupe unstable under sanitization modes |
| P11-NEW-06 | P1/P2 | Parse failures before receipt save are not durable |
| P11-NEW-07 | P1/P2 | Provider confidence is ignored |
| P11-NEW-08 | P1 | Validation failure silently saves receipt without review |
| P11-NEW-09 | P2/P1 future | Conflict cleanup lacks generalized receipt/asset cleanup |
| P11-NEW-10 | P2 | Hardcoded parser registry remains |
| P11-NEW-11 | P2/P1 | Batch import has no summary/checkpoint/cancellation contract |

---

# 7. Golden tests for Pipeline 11

Add or verify:

```text
same_message_id_skips_duplicate
same_receipt_different_message_id_skips_by_content_hash
same_merchant_amount_same_5min_different_order_not_deduped
email_source_message_id_conflict_returns_duplicate
email_source_conflict_unresolved_rolls_back_receipt
email_success_always_has_email_source_row
existing_manual_expense_is_linked_not_duplicated
duplicate_existing_expense_does_not_run_create_side_effect
created_email_expense_runs_post_create_side_effect_once
receipt_side_effect_dispatches_once_after_email_save
low_confidence_email_receipt_creates_pending_review
transaction_validation_failed_creates_pending_review
parse_failure_writes_safe_diagnostic
metadata_only_stores_no_raw_body_subject_sender_fingerprint
do_not_store_keeps_dedupe_via_message_id_hash
parsed_items_redacted_when_email_policy_requires
restore_blocks_email_ingestion_before_parse_or_write
restore_blocked_does_not_write_room_diagnostic_unsafely
parser_registry_selects_provider_and_records_version
batch_import_reports_created_duplicate_review_failed_counts
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "EmailReceiptIngestionService" app/src/main/java
grep -R "processEmailReceipt" app/src/main/java
grep -R "EmailReceiptSource(" app/src/main/java
grep -R "insertOrIgnore(emailSource" app/src/main/java
grep -R "getByMessageId" app/src/main/java
grep -R "getByFingerprint" app/src/main/java
grep -R "sourceFingerprint" app/src/main/java
grep -R "createFingerprint" app/src/main/java
grep -R "dispatchPostCreationSideEffects" app/src/main/java/com/yourname/expensetracker/data/email app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle
grep -R "confidence = 1.0" app/src/main/java
grep -R "confidence = 0.7f" app/src/main/java
grep -R "AmazonReceiptParser()" app/src/main/java
grep -R "UberReceiptParser()" app/src/main/java
grep -R "AppleReceiptParser()" app/src/main/java
grep -R "emailReceiptStorageMode" app/src/main/java
grep -R "parsedItems" app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle
```

Allowed mutation owner should be:

```text
ReceiptLifecycleCoordinator / future EmailReceiptLifecycleCoordinator only
```

Definition of done:

```text
- Email source insert conflict is always resolved or rolled back.
- Message-ID dedupe works through privacy-safe hash.
- Content dedupe is strong and privacy-safe.
- Existing expense duplicates link without create side effects.
- Transaction side effects run once.
- Low-confidence and validation-failed receipts create PendingReview.
- Raw email policy covers body, sender, subject, message ID, parsed items, fingerprint, diagnostics, exports.
- Parse failures are durably visible without raw data.
- Parser registry is injectable and records provenance.
- Batch import has summary/checkpoint/resume semantics.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Email source insert conflict resolution** — prevents source-less receipts/expenses.
2. **Remove duplicate transaction side effects and split created vs linked-existing.**
3. **Pending-review route for low-confidence and validation-failed email receipts.**
4. **Privacy-safe message/content fingerprint model.**
5. **Durable pre-save diagnostics.**
6. **Parser confidence propagation.**
7. **Parser registry/provenance.**
8. **Batch/mailbox import ledger.**
9. **Currency/money parser hardening for email providers.**