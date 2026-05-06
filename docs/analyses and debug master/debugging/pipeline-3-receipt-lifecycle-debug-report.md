# Pipeline 3 Debugging Report — Receipt Capture / OCR / Email Receipt → Match / Expense / Analytics

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static code review, not local/device execution.

## 1. Executive summary

Pipeline 3 is intended to be:

```text
camera/gallery/email/bank-statement receipt input
→ ReceiptLifecycleCoordinator
→ input validation / OCR / parsing
→ duplicate detection
→ ScannedReceipt
→ ReceiptEvent
→ ReceiptSideEffectDispatcher
→ warranty / item categorization / receipt matching / price protection
→ ReceiptLinkService
→ ReceiptExpenseLink
→ TransactionLifecycleCoordinator if creating expense
→ dashboard / analytics
```

The architecture is good, but the implementation currently has several important instability risks.

Highest-risk findings:

1. **Duplicate detection happens after `ReceiptRepository.processReceipt()` has already inserted a receipt row.**
2. **Receipt side effects are split between `ReceiptRepository`, `ReceiptLifecycleCoordinator`, `ReceiptScanViewModel`, worker code, and email ingestion.**
3. **`ReceiptLinkService` does not appear to validate that the expense exists, and `ReceiptExpenseLink` has no foreign keys.**
4. **`ReceiptLinkService` ignores the insert result from `ReceiptExpenseLinkDao.insert()` even though the DAO uses `IGNORE`.**
5. **Auto-match links created by `ReceiptLinkService` do not update `ScannedReceipt.matchStatus`, so the matching worker can repeatedly process the same receipt.**
6. **Email receipt ingestion wraps the whole flow in an outer DB transaction while calling `TransactionLifecycleCoordinator.createExpense()`, reintroducing the “post-commit side effects inside outer transaction” issue from Pipeline 2.**
7. Existing tests are mostly mock/unit/parser tests, not DB-backed receipt lifecycle contract tests.

Main recommendation:

> Make `ReceiptLifecycleCoordinator` the true owner of receipt persistence, dedupe, event logging, side effects, and link state transitions. Keep `ReceiptRepository` as a lower-level adapter, not a second lifecycle owner.

---

# 2. Intended architecture contract

From the dependency map, Receipt Lifecycle is intended as:

```text
ReceiptScanScreen / ReviewScreen / EmailReceiptIngestionService
→ ReceiptLifecycleCoordinator
→ ReceiptInputValidator
→ ReceiptAssetStore
→ ReceiptOcrService
→ ReceiptParser
→ ReceiptDuplicateDetector
→ ScannedReceiptDao
→ ReceiptEventDao
→ ReceiptSideEffectDispatcher
→ ReceiptLinkService
→ ReceiptExpenseLinkDao
→ ExpenseDao
```

Downstream side effects:

```text
AutoCreateWarrantyFromReceiptUseCase
ReceiptItemCategorizationService / CategorizeReceiptItemsUseCase
ReceiptTransactionMatcher
PriceProtectionTracker
```

This is the correct target shape.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path

## 3.1 Camera/gallery receipt path

Current `ReceiptScanViewModel.processImageUri()` calls:

```kotlin
receiptLifecycleCoordinator.processReceiptInput(uri)
```

That coordinator:

```text
restore write guard
→ ReceiptInputValidator.validate(uri)
→ receiptRepository.processReceipt(uri, autoCreateReview = false)
→ compute file hash from receipt.imagePath
→ duplicate check
→ update ScannedReceipt with source/document/status/fingerprints
→ insert ReceiptEvent(RECEIPT_SAVED)
→ insert ReceiptEvent(OCR_FAILED) if needed
→ sideEffectDispatcher.dispatchAfterSave(updated)
```

The issue is that `receiptRepository.processReceipt()` already does major lifecycle work before the coordinator dedupes:

```text
OCR
parse
normalize merchant
insert ScannedReceipt
optional PendingReview
warranty extraction side effect
```

So the coordinator is not truly controlling the full lifecycle yet.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt

---

## 3.2 Save expense from receipt path

Current `ReceiptScanViewModel.saveExpenseInternal()` calls:

```kotlin
receiptRepository.createExpenseFromReceipt(...)
```

That method is marked deprecated, but it still performs real work:

```text
load receipt
detect tax-inclusive
normalize merchant
classify category
TransactionLifecycleCoordinator.createExpense()
ReceiptLinkService.linkReceiptToExpense()
receiptItemCategorizationDao.linkToExpense()
hybridClassifier.learnFromCorrection()
```

It does route expense creation through `TransactionLifecycleCoordinator`, which is good.

But receipt linking and item-categorization updates are post-create source-specific side effects and are not fully atomic with expense creation.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

---

## 3.3 Receipt matching worker path

`ReceiptMatchingWorker`:

```text
getProcessableReceipts()
→ matcher.findBestMatch(receipt)
→ if AutoMatch:
     receiptLinkService.linkReceiptToExpense(...)
     send notification
→ if Suggested:
     receiptRepository.saveMatchSuggestion(...)
```

`saveMatchSuggestion()` updates:

```text
matchStatus = SUGGESTED
suggestedExpenseId
matchConfidence
```

But `ReceiptLinkService.linkReceiptToExpense()` does not update `matchStatus` to `AUTO_MATCHED` or `MANUALLY_MATCHED`.

This is probably a concrete bug.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

---

## 3.4 Email receipt ingestion path

`EmailReceiptIngestionService.processEmailReceipt()`:

```text
detect provider
parse provider-specific receipt
validate amount/merchant/date
normalize merchant
dedupe by messageId / fingerprint / recent scanned receipt
database.withTransaction {
    receiptLifecycleCoordinator.saveEmailReceipt(scannedReceipt)
    emailReceiptDao.insertOrIgnore(emailSource)
    processReceiptUseCase(...)
    coordinator.createExpense(...)
    receiptLinkService.linkReceiptToExpense(...)
}
```

This is powerful, but risky because it calls `TransactionLifecycleCoordinator.createExpense()` inside an outer `database.withTransaction`. As noted in Pipeline 2, coordinator side effects can run before the outer email transaction has truly committed.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

---

# 4. Major findings

## Finding P0-1 — Duplicate detection happens too late

`ReceiptLifecycleCoordinator.processReceiptInput()` calls:

```kotlin
val (receipt, parsed) = receiptRepository.processReceipt(...)
```

But `ReceiptRepository.processReceipt()` already inserts a `ScannedReceipt`.

Only after that does the coordinator compute:

```text
fileHash
textFingerprint
semanticFingerprint
```

and call `ReceiptDuplicateDetector`.

This means the app can create a new row before discovering the receipt is a duplicate.

### Concrete exact-hash problem

If exact hash duplicate is found:

```kotlin
if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
    val existing = scannedReceiptDao.getById(...)
    return Result.success(existing)
}
```

The newly inserted duplicate row from `ReceiptRepository.processReceipt()` is not deleted, marked duplicate, or linked to the existing row.

So an exact-hash duplicate can leave behind an extra `ScannedReceipt` row.

### Why this matters

Symptoms:

- duplicate receipts appear in UI,
- duplicate raw OCR text remains stored,
- duplicate warranty extraction may already have run,
- receipt count/dashboard metrics may be wrong,
- backup/restore includes junk duplicate receipt assets,
- later matching worker processes duplicate rows.

### Recommended fix

Move dedupe earlier.

Best design:

```text
1. validate URI
2. persist/copy asset
3. compute exact file hash
4. check duplicate by hash BEFORE OCR
5. if duplicate:
     write DUPLICATE_DETECTED event referencing existing receipt
     do not OCR
     do not insert a new active receipt
     do not run side effects
6. if not duplicate:
     OCR
     parse
     compute text/semantic fingerprints
     insert receipt + event atomically
     dispatch side effects after commit
```

If OCR must happen before asset hashing, then at minimum:

```text
if duplicate discovered after repository insert:
    mark current row DUPLICATE_DETECTED
    or delete current row + asset
    and write receipt event
```

Priority: highest.

---

## Finding P0-2 — Side effects are duplicated across layers

`ReceiptRepository.processReceipt()` already triggers:

```text
AutoCreateWarrantyFromReceiptUseCase
```

Then `ReceiptLifecycleCoordinator.processReceiptInput()` triggers:

```text
ReceiptSideEffectDispatcher.dispatchAfterSave()
```

For retail receipts, that dispatcher runs:

```text
AutoCreateWarrantyFromReceiptUseCase
CategorizeReceiptItemsUseCase
ReceiptTransactionMatcher.findBestMatch()
PriceProtectionTracker.findBetterDeals()
```

Then `ReceiptScanViewModel` can also auto-trigger:

```text
CategorizeReceiptItemsUseCase
```

So warranty extraction and item categorization can happen more than once.

### Why this matters

Even if the use cases are idempotent, duplicated calls create instability:

- duplicate warranty drafts,
- duplicate AI artifacts,
- repeated cloud/on-device AI work,
- inconsistent item categorization state,
- performance and battery waste,
- confusing debug logs.

### Recommended fix

Create a strict ownership rule:

```text
ReceiptRepository:
  OCR, parse, low-level DAO reads/writes only if necessary

ReceiptLifecycleCoordinator:
  lifecycle state transitions, dedupe, events, side-effect orchestration

ReceiptScanViewModel:
  UI state only; no automatic second side-effect unless explicitly user-triggered

ReceiptMatchingWorker:
  background matching only; must update match status and event logs
```

Remove warranty extraction from `ReceiptRepository.processReceipt()`.

Let `ReceiptSideEffectDispatcher` be the only automatic post-save side-effect owner.

Priority: highest.

---

## Finding P0-3 — Receipt links can become false-success or orphaned

`ReceiptExpenseLinkDao.insert()` uses:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insert(link: ReceiptExpenseLink): Long
```

But `ReceiptLinkService.linkReceiptToExpense()` ignores the returned row ID.

If a duplicate link already exists, Room will ignore the insert. The service will still:

```text
update ScannedReceipt.expenseId
update warranty expense IDs
update return window expense IDs
link receipt item categorizations
write RECEIPT_LINKED_TO_EXPENSE event
return Result.success(link)
```

That is a false success.

Also, `ReceiptExpenseLink` entity has indexes but no visible `ForeignKey` annotations to `scanned_receipts` or `expenses`.

So the database does not appear to enforce:

```text
receiptId must exist
expenseId must exist
delete receipt/expense cascades or restricts link
```

The service loads the receipt, but it does **not** validate the expense exists.

### Why this matters

Possible symptoms:

- link rows pointing to deleted/nonexistent expenses,
- duplicate link events,
- warranty/return windows attached to the wrong or nonexistent expense,
- analytics or receipt matching seeing stale links,
- UI says linked when actual link insert was ignored.

### Recommended fix

1. Add foreign keys to `ReceiptExpenseLink`:

```kotlin
ForeignKey(
  entity = ScannedReceipt::class,
  parentColumns = ["id"],
  childColumns = ["receiptId"],
  onDelete = CASCADE
)

ForeignKey(
  entity = Expense::class,
  parentColumns = ["id"],
  childColumns = ["expenseId"],
  onDelete = CASCADE or SET_NULL depending on policy
)
```

2. Inject `ExpenseDao` into `ReceiptLinkService` and validate expense exists before insert.

3. Check insert result:

```kotlin
val linkId = receiptExpenseLinkDao.insert(link)
if (linkId <= 0) return Result.failure(DuplicateLinkException(...))
```

4. Only update receipt/warranty/items and write event if the link insert actually happened.

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

---

## Finding P0-4 — Auto-matching probably repeats because `matchStatus` is not updated

`ReceiptMatchingWorker` gets processable receipts:

```text
matchStatus IN ('UNMATCHED', 'SUGGESTED')
```

On auto-match, it calls:

```kotlin
receiptLinkService.linkReceiptToExpense(...)
```

But `ReceiptLinkService.linkReceiptToExpense()` updates:

```text
expenseId
suggestedExpenseId = null
updatedAt
```

It does not update:

```text
matchStatus = AUTO_MATCHED
matchConfidence = confidence
```

So the same receipt can remain `UNMATCHED`, and the worker can process it again.

If the duplicate link is rejected by the existing-links check, the worker does not inspect the `Result.failure`; it still increments `autoMatched` and sends a notification.

### Why this matters

Symptoms:

- repeated “receipt auto matched” notifications,
- repeated worker attempts,
- noisy logs,
- links/events inconsistent with status,
- battery/background churn.

### Recommended fix

Change `ReceiptLinkService.linkReceiptToExpense()` to accept match status semantics:

```kotlin
linkReceiptToExpense(
  ...,
  linkType = "AUTO_MATCH",
  matchStatus = MatchStatus.AUTO_MATCHED,
  matchConfidence = confidence
)
```

Or infer:

```text
AUTO_MATCH → AUTO_MATCHED
MANUAL / REVIEW_APPROVAL → MANUALLY_MATCHED
DIRECT_SAVE → MANUALLY_MATCHED or AUTO_MATCHED depending contract
```

Also make worker check result:

```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
if (linkResult.isSuccess) {
    autoMatched++
    send notification
} else {
    Timber.w("Auto-match link failed: ...")
}
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

---

## Finding P0-5 — Email ingestion has nested transaction / post-commit side-effect risk

`EmailReceiptIngestionService` wraps the process in:

```kotlin
database.withTransaction { ... }
```

Inside it calls:

```kotlin
coordinator.createExpense(request)
```

But `TransactionLifecycleCoordinator.createExpense()` itself performs transaction + post-create side effects.

This is the same problem as Pipeline 2:

```text
outer email transaction starts
→ create expense
→ coordinator side effects run
→ later email link/source write fails
→ outer transaction rolls back
→ side effects may already have run
```

### Recommended fix

Email ingestion should use a deferred post-commit model:

```text
database.withTransaction {
    save receipt
    save email source
    create expense DB-only
    create receipt-expense link
    collect postCommitActions
}

run postCommitActions
```

This requires `TransactionLifecycleCoordinator` to support deferred side effects.

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

---

## Finding P1-1 — `ScannedReceiptDao.insert(IGNORE)` comment implies uniqueness that entity does not enforce

`ScannedReceiptDao.insert()` says `IGNORE` prevents duplicates by fingerprint/hash.

But `ScannedReceipt` entity visibly defines normal indexes only for:

```text
expenseId
createdAt
matchStatus
processingStatus
```

It does not define unique indexes for:

```text
imageHash
textFingerprint
semanticFingerprint
sourceFingerprint
```

So `IGNORE` will not prevent duplicate fingerprints unless a migration added additional indexes outside the entity. Based on the entity alone, this looks inconsistent.

### Recommended fix

Either:

1. add explicit unique indexes where safe:

```kotlin
Index(value = ["imageHash"], unique = true)
Index(value = ["sourceFingerprint"], unique = true)
```

and be careful with nullable columns in SQLite,

or:

2. remove the misleading comment and make duplicate prevention purely service-level.

For text/semantic fingerprints, full uniqueness may be too aggressive, so prefer service-level dedupe plus explicit duplicate rows/events.

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

---

## Finding P1-2 — `ReceiptEventDao` is too minimal for debugging

`ReceiptEventDao` only exposes:

```text
insert
getEventsForReceipt
```

For a lifecycle-heavy pipeline, you need more.

Recommended DAO methods:

```kotlin
getRecentEvents(limit)
getEventsByType(eventType, limit)
getEventsBetween(start,end)
getEventsBySourceType(sourceType)
getFailureEvents(limit)
countEventsForReceipt(receiptId)
```

Also, `getEventsForReceipt()` orders by `occurredAt DESC`. If multiple events share the same timestamp, order is unstable.

Use:

```sql
ORDER BY occurredAt ASC, id ASC
```

for timeline reconstruction, or provide both chronological and reverse-chronological methods.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDao.kt

---

## Finding P1-3 — Receipt lifecycle events are incomplete

The model says events should represent:

```text
capture
validation
OCR
parsing
dedupe
save
link
unlink
delete
failure
```

Current code mostly writes:

```text
RECEIPT_SAVED
OCR_FAILED
DUPLICATE_DETECTED
RECEIPT_LINKED_TO_EXPENSE
RECEIPT_UNLINKED_FROM_EXPENSE
RECEIPT_DELETED
```

Missing useful events:

```text
RECEIPT_INPUT_RECEIVED
VALIDATION_FAILED
ASSET_PERSISTED
OCR_STARTED
OCR_COMPLETED
PARSE_STARTED
PARSE_COMPLETED
PARSE_FAILED
SIDE_EFFECT_STARTED
SIDE_EFFECT_FAILED
MATCH_SUGGESTED
MATCH_AUTO_ACCEPTED
EXPENSE_CREATED_FROM_RECEIPT
```

### Recommended fix

Create a typed event enum/sealed class instead of raw strings.

```kotlin
enum class ReceiptLifecycleEventType {
    INPUT_RECEIVED,
    VALIDATION_FAILED,
    ASSET_PERSISTED,
    OCR_COMPLETED,
    PARSE_COMPLETED,
    RECEIPT_SAVED,
    DUPLICATE_DETECTED,
    MATCH_SUGGESTED,
    RECEIPT_LINKED_TO_EXPENSE,
    EXPENSE_CREATED_FROM_RECEIPT,
    SIDE_EFFECT_FAILED,
    RECEIPT_DELETED
}
```

Priority: high.

---

## Finding P1-4 — `ReceiptRepository.createExpenseFromReceipt()` is deprecated but still used by UI

`ReceiptScanViewModel.saveExpenseInternal()` calls:

```kotlin
receiptRepository.createExpenseFromReceipt(...)
```

The method is annotated deprecated and says it should be replaced.

It currently does route through `TransactionLifecycleCoordinator`, so it is not as bad as the deprecation message suggests. But as a design smell, the UI should not depend on a deprecated compatibility method for a primary flow.

### Recommended fix

Add a proper lifecycle API:

```kotlin
ReceiptLifecycleCoordinator.createExpenseFromReceipt(...)
```

It should:

```text
load receipt
resolve tax-inclusive amount
create expense via TransactionLifecycleCoordinator in deferred-safe mode
link receipt via ReceiptLinkService
write ReceiptEvent(EXPENSE_CREATED_FROM_RECEIPT)
link item categorizations
run post-commit side effects
return created expense ID
```

Then migrate `ReceiptScanViewModel` to call the coordinator.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

---

## Finding P1-5 — Email receipt paths are duplicated/inconsistent

There are at least two email receipt flows:

1. `ReceiptLifecycleCoordinator.processEmailReceipt(emailData)`
2. `EmailReceiptIngestionService.processEmailReceipt(...)`

They do not appear equivalent.

Coordinator path:

```text
dedupe by messageId via scannedReceipt.sourceFingerprint
insert ScannedReceipt
insert EmailReceiptSource
insert ReceiptEvent
dispatch side effects
```

Email ingestion service path:

```text
provider-specific parser
dedupe by EmailReceiptDao messageId/fingerprint
saveEmailReceipt()
insert EmailReceiptSource
processReceiptUseCase()
create expense
link receipt
```

### Why this matters

Different call sites may produce different:

- source fingerprints,
- EmailReceiptSource rows,
- events,
- side effects,
- expense creation behavior,
- dedupe results.

### Recommended fix

Choose one canonical email entry point.

Recommended:

```text
EmailReceiptIngestionService parses provider-specific email
→ produces EmailReceiptData / ParsedEmailReceipt
→ ReceiptLifecycleCoordinator.processEmailReceipt(...)
→ optional create-expense/link path owned by coordinator
```

Avoid direct DB orchestration in both places.

Priority: high.

---

## Finding P1-6 — Receipt matching algorithm still needs multi-currency hardening

`ReceiptTransactionMatcher` now applies a currency mismatch penalty, which is good.

But it still compares raw nominal amounts:

```text
receipt.parsedTotal vs transaction.effectiveAmount
```

If receipt is EUR and expense is USD, a nominally similar number can still score high enough if merchant/date match strongly.

### Recommended fix

Use `CurrencyConverter`/`MultiCurrencyRepository`:

```text
if currencies match:
    compare raw values
else if historical conversion available:
    convert receipt or transaction to common home currency
else:
    amountScore = 0 or require manual review
```

For automatic matching, currency mismatch without conversion should usually block `AutoMatch`.

Priority: medium-high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt

---

## Finding P1-7 — Receipt matching uses hardcoded dispatcher/time constants

`ReceiptTransactionMatcher.findBestMatch()` uses:

```kotlin
withContext(Dispatchers.Default)
lookbackDays * 86400000
```

This is not catastrophic, but it makes tests harder and ignores the app’s time abstraction.

### Recommended fix

Inject dispatcher:

```kotlin
@DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
```

Use shared time/date utilities for day windows.

Priority: medium.

---

## Finding P1-8 — Raw OCR/email text storage needs privacy/debug clarity

Receipts store:

```text
rawOcrText
```

Email ingestion stores:

```text
emailBody.take(5000)
```

There is a data retention worker, and `ScannedReceipt` has:

```text
rawOcrTextPurgedAt
```

But the receipt pipeline itself does not clearly show privacy gate/redaction decisions at capture time.

### Recommended fix

Add:

```text
receipt raw text retention setting
cloud AI redaction status
raw OCR persisted yes/no
raw OCR purged yes/no
```

to receipt diagnostics.

If user disables raw text retention, store:

```text
rawOcrText = ""
rawOcrTextPurgedAt = now
```

or sanitized text only.

Priority: medium-high.

---

## Finding P2-1 — `ReceiptInputValidator` may reject valid URIs when MIME is unavailable

`ReceiptInputValidator` requires `contentResolver.getType(uri)` to return a supported MIME type.

Some file providers or `file://` URIs may return null.

This can make gallery/camera flows fail depending on provider/device.

### Recommended fix

Fallback MIME inference:

```text
ContentResolver.getType(uri)
→ file extension
→ sniff header
→ image decode/PDF header
```

Also add explicit support tests for:

```text
content:// camera URI
content:// gallery URI
file:// URI if app uses one internally
PDF URI
HEIC URI
provider with null MIME
```

Priority: medium.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptInputValidator.kt

---

# 5. Debugging checklist for Pipeline 3

## Input / Android layer

Check:

- [ ] camera temp URI created
- [ ] FileProvider authority matches manifest
- [ ] camera app receives write permission
- [ ] gallery URI read permission available
- [ ] MIME type detected
- [ ] file size valid
- [ ] image/PDF readable
- [ ] HEIC works on target devices
- [ ] OCR service supports the MIME type accepted by validator

## OCR / parse

Check:

- [ ] OCR service returns saved image path
- [ ] OCR full text non-empty
- [ ] OCR failure creates manual receipt row
- [ ] parse failure preserves OCR text
- [ ] merchant parsed
- [ ] amount parsed
- [ ] date parsed
- [ ] currency parsed
- [ ] line items parsed
- [ ] tax-inclusive detection correct

## Dedupe

Check:

- [ ] exact image hash computed before OCR or duplicate row cleaned up
- [ ] text fingerprint computed
- [ ] semantic fingerprint computed
- [ ] duplicate result records existing receipt ID
- [ ] duplicate event written
- [ ] duplicate does not run side effects
- [ ] duplicate does not leave active extra row unless intentional

## Persistence

Check:

- [ ] `ScannedReceipt` inserted once
- [ ] sourceType set correctly
- [ ] documentType set correctly
- [ ] processingStatus set correctly
- [ ] imageHash/textFingerprint/semanticFingerprint stored
- [ ] createdAt/updatedAt set
- [ ] rawOcrText retention policy respected
- [ ] `ReceiptEvent` sequence complete

## Side effects

Check:

- [ ] warranty extraction runs once
- [ ] item categorization runs once
- [ ] receipt matching runs once
- [ ] price protection runs once
- [ ] failures logged as receipt events
- [ ] side effects run after final DB commit

## Link/match

Check:

- [ ] receipt exists
- [ ] expense exists
- [ ] link insert return value checked
- [ ] duplicate link returns failure or existing link
- [ ] `ScannedReceipt.expenseId` updated
- [ ] `matchStatus` updated
- [ ] `matchConfidence` updated
- [ ] warranty/return windows receive expenseId
- [ ] item categorizations receive expenseId
- [ ] `RECEIPT_LINKED_TO_EXPENSE` event written only on real link success

## Expense creation from receipt

Check:

- [ ] amount uses tax-inclusive rule correctly
- [ ] currency uses editable/OCR/home value correctly
- [ ] expense uses `TransactionLifecycleCoordinator`
- [ ] `TransactionEvent.CREATED` written
- [ ] receipt link created
- [ ] receipt event `EXPENSE_CREATED_FROM_RECEIPT` written
- [ ] dashboard total updated
- [ ] analytics counts expense once
- [ ] no duplicate expense created

## Email receipts

Check:

- [ ] provider detection correct
- [ ] provider parser correct
- [ ] messageId dedupe
- [ ] fingerprint dedupe
- [ ] EmailReceiptSource inserted once
- [ ] ScannedReceipt sourceType/documentType correct
- [ ] expense created only when desired
- [ ] receipt linked to expense
- [ ] duplicate email does not create duplicate expense
- [ ] side effects are post-commit

---

# 6. Recommended fix plan

## PR 1 — Fix link correctness

Change `ReceiptLinkService`:

1. validate expense exists,
2. check link insert result,
3. update `matchStatus` / `matchConfidence`,
4. only write event after successful insert,
5. return existing-link result explicitly.

Add FK constraints to `ReceiptExpenseLink`.

Acceptance:

```text
duplicate link does not write fake event
auto-match changes receipt to AUTO_MATCHED
worker does not process same auto-matched receipt again
orphan link impossible
```

---

## PR 2 — Move side effects to one owner

Remove warranty extraction from `ReceiptRepository.processReceipt()`.

Make only `ReceiptSideEffectDispatcher` responsible for automatic post-save effects.

Make side effects idempotent and event-logged.

Acceptance:

```text
one receipt save produces one warranty attempt, one item categorization attempt, one matching attempt
```

---

## PR 3 — Move duplicate detection earlier

Refactor receipt processing:

```text
validate
persist/copy asset
hash
dedupe exact hash
OCR
parse
fingerprints
dedupe text/semantic
insert receipt
event
post-commit side effects
```

Acceptance:

```text
exact duplicate receipt does not leave an active extra ScannedReceipt row
duplicate side effects do not run
```

---

## PR 4 — Add DB-backed receipt lifecycle tests

Create:

```text
ReceiptLifecycleCoordinatorDbContractTest
ReceiptLinkServiceDbContractTest
ReceiptMatchingWorkerDbContractTest
EmailReceiptLifecycleDbContractTest
```

Use real in-memory Room.

---

## PR 5 — Unify email receipt flow

Pick a single canonical email lifecycle path.

Prefer:

```text
EmailReceiptIngestionService parses provider email
→ ReceiptLifecycleCoordinator persists/dedupes/events/links/creates expense
```

Acceptance:

```text
same email input always produces same receipt/event/source/link behavior regardless of entry point
```

---

## PR 6 — Deferred post-commit for email/receipt expense creation

Do not call side-effecting `TransactionLifecycleCoordinator.createExpense()` inside outer email/receipt DB transactions unless side effects can be deferred.

Acceptance:

```text
if link/email-source write fails, expense side effects do not fire for rolled-back data
```

---

# 7. Tests to add

## 7.1 `ReceiptLifecycleCoordinatorDbContractTest`

Cases:

1. valid image receipt creates one `ScannedReceipt`,
2. writes `RECEIPT_SAVED`,
3. OCR failure writes `OCR_FAILED`,
4. parse failure preserves raw OCR text,
5. exact duplicate returns existing receipt and does not leave active duplicate,
6. post-OCR duplicate marks duplicate and writes event,
7. restore mode blocks writes.

---

## 7.2 `ReceiptLinkServiceDbContractTest`

Cases:

1. link valid receipt + valid expense,
2. duplicate link returns duplicate/existing result,
3. nonexistent receipt fails,
4. nonexistent expense fails,
5. non-bank receipt cannot relink unless `allowRelink`,
6. bank-statement receipt can have multiple links,
7. link updates:
   - `expenseId`,
   - `matchStatus`,
   - `matchConfidence`,
   - warranty expenseId,
   - return window expenseId,
   - item categorization expenseId,
8. event written exactly once.

---

## 7.3 `ReceiptMatchingWorkerDbContractTest`

Cases:

1. high-confidence match creates link and marks `AUTO_MATCHED`,
2. worker second run does not reprocess/send duplicate notification,
3. suggested match sets `SUGGESTED`,
4. rejected/manual status is skipped,
5. bank-statement and OCR_FAILED receipts skipped,
6. restore mode skips safely.

---

## 7.4 `ReceiptToExpenseNoDoubleCountScenarioTest`

Seed:

```text
existing bank/card expense: €42.50, merchant Lidl
receipt OCR text: Lidl total €42.50
```

Expected:

```text
receipt saved
receipt linked to existing expense
no new duplicate expense
analytics counts €42.50 once
dashboard total unchanged except receipt count/link state
```

---

## 7.5 `ReceiptCreateExpenseScenarioTest`

Seed:

```text
receipt with parsed merchant/amount/date/items
no existing matching expense
```

Action:

```text
user saves receipt as expense
```

Expected:

```text
expense created via TransactionLifecycleCoordinator
TransactionEvent.CREATED
ReceiptExpenseLink row
ReceiptEvent.EXPENSE_CREATED_FROM_RECEIPT or RECEIPT_LINKED_TO_EXPENSE
item categorizations linked to expense
dashboard total includes expense once
```

---

## 7.6 `EmailReceiptLifecycleScenarioTest`

Input:

```text
Amazon/Apple/Uber email
messageId
parsed amount/merchant/date/items
```

Expected:

```text
EmailReceiptSource row
ScannedReceipt EMAIL_RECEIPT row
ReceiptEvent.RECEIPT_SAVED
expense created through lifecycle
receipt linked
duplicate same messageId skipped
analytics counts once
```

---

## 7.7 `ReceiptRawTextPrivacyRetentionTest`

Cases:

1. raw OCR retention enabled → raw text stored,
2. raw OCR retention disabled → raw text redacted/purged immediately,
3. retention worker purges old OCR text,
4. receipt parsing metadata remains after raw purge,
5. AI/cloud side effects receive redacted text if privacy requires.

---

# 8. Suggested canonical scenario

## `receipt_matching_analytics_no_double_count`

Seed:

```text
home currency EUR
category: groceries
existing expense:
  merchant = LIDL
  amount = 42.50 EUR
  date = 2026-05-01
  source = NOTIFICATION_CAPTURE
receipt OCR:
  merchant = LIDL
  total = 42.50 EUR
  date = 2026-05-01
  items = bread, milk
```

Run:

```text
ReceiptLifecycleCoordinator.processReceiptInput(uri)
ReceiptMatchingWorker.runOnce()
```

Assert:

```text
ScannedReceipt count = 1
ReceiptEvent includes RECEIPT_SAVED
ReceiptExpenseLink count = 1
linked expenseId = existing expense ID
ScannedReceipt.matchStatus = AUTO_MATCHED
TransactionEvent.CREATED count for receipt = 0
dashboard monthly total = 42.50 EUR
analytics groceries total = 42.50 EUR
receipt item categorizations linked to expense
worker second run does not create another event/notification
```

This one test catches the most important receipt bug class: duplicate spending.

---

# 9. Most likely real instability sources

Ranked:

1. **Late duplicate detection after insert.**
   - Can leave duplicate receipt rows and side effects.

2. **Duplicate side effects.**
   - Warranty/item categorization/matching can run from multiple places.

3. **ReceiptLinkService not updating match status.**
   - Matching worker can repeat and notify repeatedly.

4. **Link insert false success.**
   - `IGNORE` result is ignored; event may be written despite no inserted link.

5. **No FK constraints on receipt-expense link.**
   - Orphan links possible.

6. **Email ingestion nested transaction side effects.**
   - Side effects can run before final commit.

7. **Deprecated repository method used by primary UI.**
   - Confusing ownership and future regression risk.

8. **Mock-only lifecycle tests.**
   - Real DB/link/event/match behavior unproven.

---

# 10. Final recommendation

For Pipeline 3, stabilize in this order:

```text
1. Fix ReceiptLinkService correctness.
2. Fix matchStatus updates so worker does not repeat.
3. Move duplicate detection before insert/side effects.
4. Make ReceiptSideEffectDispatcher the only automatic side-effect owner.
5. Unify email receipt lifecycle path.
6. Add DB-backed receipt/link/matching/email scenario tests.
7. Add privacy/raw OCR retention tests.
```

Guiding rule:

> A receipt should never create duplicate receipt rows, duplicate expenses, duplicate side effects, or fake link events.

Once that is true, receipt → analytics/dashboard becomes much safer.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Duplicate detection happens too late
**STATUS: CONFIRMED — NOT FIXED (architectural refactor needed)**
`ReceiptRepository.processReceipt()` does insert a ScannedReceipt before the coordinator runs dedupe. On exact-hash duplicate, the newly inserted row is not deleted. This remains a design gap requiring a larger refactor to move dedupe before insert.

## Finding P0-2 — Side effects are duplicated across layers
**STATUS: CONFIRMED — NOT FIXED (refactor needed)**
`ReceiptRepository.processReceipt()` runs warranty extraction. `ReceiptSideEffectDispatcher.dispatchAfterSave()` also runs warranty and more. Confirmed duplicated side-effect execution paths. Fixing requires removing warranty from ReceiptRepository, which is a larger refactor.

## Finding P0-3 — Receipt links can become false-success or orphaned
**STATUS: CONFIRMED — FIXED**
- `ReceiptLinkService.linkReceiptToExpense()` now checks the `receiptExpenseLinkDao.insert()` return value. If `linkId <= 0` (duplicate/conflict), returns `Result.failure` immediately, preventing false audit events and stale state updates.
- `ReceiptLinkService` now validates that the expense exists (via `expenseDao.getById()`) before attempting to link. Returns `Result.failure` if the expense is not found.
- No FK constraints added (requires Room migration), but application-level validation is now in place.

## Finding P0-4 — Auto-matching probably repeats because matchStatus is not updated
**STATUS: CONFIRMED — FIXED**
- `ReceiptLinkService.linkReceiptToExpense()` now accepts an optional `matchStatus: MatchStatus?` parameter and always updates `matchStatus` and `matchConfidence` on the `ScannedReceipt` during linking.
- Default resolution: `AUTO_MATCH` → `AUTO_MATCHED`, `DIRECT_SAVE`/`REVIEW_APPROVAL` → `MANUALLY_MATCHED`.
- `ReceiptMatchingWorker` now checks `linkReceiptToExpense()` result: only increments `autoMatched` and sends notification on `Result.success`. Failed links are logged with `Timber.w`.

## Finding P0-5 — Email ingestion has nested transaction / post-commit side-effect risk
**STATUS: CONFIRMED — FIXED**
- `EmailReceiptIngestionService.createExpenseFromReceipt()` now calls `coordinator.createExpense(request, SideEffectMode.DEFER)` instead of the default `IMMEDIATE`.
- After the `transactionRunner` block completes, `coordinator.dispatchPostCreationSideEffects()` is called for each created expense, ensuring side effects only run after the outer Room transaction has committed.

## Finding P1-1 — ScannedReceiptDao.insert(IGNORE) comment implies uniqueness that entity does not enforce
**STATUS: CONFIRMED — NOT FIXED (schema change needed)**

## Finding P1-2 — ReceiptEventDao is too minimal for debugging
**STATUS: CONFIRMED — NOT FIXED (enhancement, not bug)**

## Finding P1-3 — Receipt lifecycle events are incomplete
**STATUS: CONFIRMED — NOT FIXED (enhancement)**

## Finding P1-4 — ReceiptRepository.createExpenseFromReceipt() is deprecated but still used by UI
**STATUS: CONFIRMED — NOT FIXED (migration needed)**

## Finding P1-5 — Email receipt paths are duplicated/inconsistent
**STATUS: CONFIRMED — NOT FIXED (architectural unification needed)**

## Finding P1-6 — Receipt matching algorithm still needs multi-currency hardening
**STATUS: CONFIRMED — NOT FIXED (enhancement)**

## Finding P1-7 — Receipt matching uses hardcoded dispatcher/time constants
**STATUS: CONFIRMED — NOT FIXED (low priority)**

## Finding P1-8 — Raw OCR/email text storage needs privacy/debug clarity
**STATUS: CONFIRMED — NOT FIXED (privacy enhancement)**

## Finding P2-1 — ReceiptInputValidator may reject valid URIs when MIME is unavailable
**STATUS: CONFIRMED — NOT FIXED (edge case)**

---

# 12. New issues discovered (not in original report)

## NEW-1 — ReceiptRepository.approveMatchSuggestion bypasses ReceiptLinkService
`ReceiptMatchingViewModel.approveSuggestion()` calls `ReceiptRepository.approveMatchSuggestion()` which updates `ScannedReceipt.expenseId` and `matchStatus = MANUALLY_MATCHED` directly, but does NOT call `ReceiptLinkService`. This means `receipt_expense_links` join table and receipt audit events are missing for manual match approvals.

**Severity: P1**
**Recommendation:** Route `approveMatchSuggestion` through `ReceiptLinkService.linkReceiptToExpense()`.

## NEW-2 — ReceiptRepository.linkReceiptToExpense (legacy) is inconsistent with ReceiptLinkService
`ReceiptRepository` has its own `linkReceiptToExpense()` that updates `ScannedReceipt` only (matchStatus + expenseId). It does NOT touch the `receipt_expense_links` join table. This creates two parallel linking models — one via ReceiptLinkService (join table + receipt + events) and one via ReceiptRepository (receipt only).

**Severity: P1**
**Recommendation:** Deprecate `ReceiptRepository.linkReceiptToExpense()` and route all linking through `ReceiptLinkService`.

## NEW-3 — ReceiptRepository.createExpenseFromReceipt double-links item categorizations
Both `ReceiptRepository.createExpenseFromReceipt()` and `ReceiptLinkService.linkReceiptToExpense()` call `receiptItemCategorizationDao.linkToExpense()`. The call in ReceiptRepository is redundant.

**Severity: P2**
**Recommendation:** Remove the `linkToExpense` call from `ReceiptRepository.createExpenseFromReceipt()`.

## NEW-4 — ReceiptSideEffectDispatcher.dispatchAfterSave discards matcher result
For RETAIL_RECEIPT, the dispatcher calls `receiptTransactionMatcher.findBestMatch()` but discards the result — no link or suggestion is persisted. This is wasted computation (the worker will later do the actual matching + persist).

**Severity: P2**
**Recommendation:** Remove the matcher call from the dispatcher, or use it to write a suggestion.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Check link insert result, validate expense exists | `ReceiptLinkService.kt` | P0-3 |
| Update matchStatus/matchConfidence on link | `ReceiptLinkService.kt` | P0-4 |
| Check link result before success/notification | `ReceiptMatchingWorker.kt` | P0-4 |
| Use SideEffectMode.DEFER + post-commit dispatch | `EmailReceiptIngestionService.kt` | P0-5 |

---

# 14. Remaining work priority

1. **P0-1**: Move duplicate detection before receipt insert (architectural refactor)
2. **P0-2**: Remove warranty extraction from ReceiptRepository, make ReceiptSideEffectDispatcher sole owner
3. **NEW-1**: Route approveMatchSuggestion through ReceiptLinkService
4. **NEW-2**: Deprecate ReceiptRepository.linkReceiptToExpense legacy path
5. **P1-1**: Add unique indexes for fingerprint fields on ScannedReceipt (Room migration)
6. **P0-3 (FK)**: Add foreign key constraints to ReceiptExpenseLink (Room migration)
7. **P1-4**: Migrate deprecated createExpenseFromReceipt to ReceiptLifecycleCoordinator
8. **P1-5**: Unify email receipt lifecycle to single canonical path

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `ReceiptLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `ReceiptLinkService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

- `ReceiptSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt

- `ReceiptDuplicateDetector.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptDuplicateDetector.kt

- `ReceiptInputValidator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptInputValidator.kt

- `ReceiptAssetStore.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptAssetStore.kt

- `ReceiptTransactionMatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt

- `ReceiptMatchingWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

- `ReceiptScanViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt

- `EmailReceiptIngestionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `ScannedReceipt.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `ReceiptEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptEvent.kt

- `ReceiptExpenseLink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt

- `ScannedReceiptDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

- `ReceiptEventDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDao.kt

- `ReceiptExpenseLinkDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt

- Existing mock lifecycle test  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt

- Existing receipt e2e parser/categorization test  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/e2e/ReceiptProcessingPipelineTest.kt