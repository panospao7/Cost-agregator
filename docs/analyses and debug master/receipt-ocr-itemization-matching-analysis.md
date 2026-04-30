# Receipt / OCR / Itemization / Receipt Matching Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- receipt OCR service
- image/PDF processing
- receipt parser
- bank statement parser
- scanned receipt persistence
- receipt scan ViewModel
- receipt → expense creation
- receipt item AI categorization
- receipt matching/reconciliation
- statement screenshot import

Static review only; I did not run the Android app/tests.

---

## Executive verdict

This area is feature-rich and already has useful safeguards:

- local ML Kit OCR
- PDF text extraction + OCR fallback
- parsed receipt records
- line-item extraction
- receipt-to-expense matching
- AI receipt assist
- AI item categorization
- statement screenshot import
- duplicate checks when creating an expense from a receipt

But it is not yet safe enough as a financial source-of-truth pipeline.

The biggest problem:

> The app treats scanned receipts, receipt items, statement imports, expense creation, receipt matching, warranties, and AI item categorization as related but separate workflows. There is no single `ReceiptLifecycleCoordinator` that owns the state transitions.

Highest-risk issues:

1. receipt image filenames can collide
2. failed parsing still creates fake `0.01 EUR` pending reviews
3. statement screenshots create one `ScannedReceipt` shared by many pending transactions
4. receipt item categorizations are not linked to the created/matched expense
5. AI item categorization validates only item count, not category IDs, amounts, confidence, tax, or identity
6. item-level tax can be duplicated for every item in a category
7. receipt matching ignores currency and can link multiple receipts to one expense
8. receipt currency defaults to EUR too aggressively
9. raw OCR text is retained indefinitely
10. AI quick save uses AI suggestions without strong per-field confidence validation

---

# Core observed flows

## Manual receipt scan

```text
ReceiptScanViewModel.processImageUri()
→ ReceiptRepository.processReceipt(autoCreateReview = false)
→ ReceiptOcrService.processUri()
→ ReceiptParser.parse()
→ scanned_receipts insert
→ warranty extraction side effect
→ UI review
→ ReceiptRepository.createExpenseFromReceipt()
→ expense insert
→ scannedReceipt.linkToExpense()
```

## Batch receipt scan

```text
ReceiptRepository.processBatch()
→ processReceipt(autoCreateReview = true)
→ scanned_receipts insert
→ pending_reviews insert
→ warranty extraction side effect
```

## Statement screenshot import

```text
ReceiptRepository.processStatement()
→ OCR
→ BankStatementParser.parse(blocks)
→ one ScannedReceipt row for whole statement
→ one PendingReview per parsed transaction
```

## AI item categorization

```text
ReceiptScanViewModel.analyzeReceiptItemsInternal()
→ CategorizeReceiptItemsUseCase
→ ReceiptItemCategorizationInputBuilder
→ route cloud/on-device/fallback
→ ReceiptItemCategorizationRepository.saveCategorizationResult()
→ receipt_item_categorizations rows
```

## Receipt matching

```text
ReceiptMatchingViewModel.runAutoMatching()
→ ReceiptTransactionMatcher.findBestMatch()
→ ReceiptRepository.linkReceiptToExpense()
```

---

# Strong parts

## 1. OCR service handles images and PDFs

`ReceiptOcrService` supports:

- JPEG/PNG/WebP/HEIC
- PDF direct text extraction
- PDF rendering + OCR fallback
- EXIF rotation correction
- OCR timeout
- ML Kit recognizer mutex
- bitmap recycling

Good foundation.

## 2. Receipt expense creation does a canonical duplicate check

`createExpenseFromReceipt()` checks currency-aware duplicate candidates before `insertAtomic()`.

Good.

## 3. Batch processing has bounded concurrency

`processBatch()` uses a semaphore with max concurrency 3.

Good for avoiding OOM.

## 4. Statement import has transaction-aware duplicate checks

Statement import checks existing expenses and pending reviews using amount/date/currency/type windows.

Good.

## 5. Receipt item categorization has artifact records

The AI item categorization path records RUNNING/READY/FAILED artifacts.

Good for diagnostics.

## 6. Cloud receipt assist suppresses image upload when redaction is required

`CloudReceiptAssistService.buildImageInlineData()` returns null when `input.redactBeforeCloud` is true.

Good privacy behavior.

---

# Critical / high-priority findings

## 1. Receipt image filenames can collide

### Where

`ReceiptOcrService.saveReceiptImage()`

```kotlin
val fileName = "receipt_${System.currentTimeMillis()}.jpg"
```

### Problem

Batch OCR allows multiple images to process concurrently. Two receipts saved in the same millisecond can get the same filename.

`FileOutputStream(file)` can overwrite the existing file.

### Impact

Possible outcomes:

- receipt A and receipt B point to the same image path
- receipt B overwrites receipt A’s image
- deleting one receipt deletes image used by another receipt
- cloud receipt assist may analyze the wrong image
- warranty/return proof image can be wrong

### Severity

**Critical**

### Fix

Use collision-proof names:

```kotlin
receipt_${now}_${UUID.randomUUID()}.jpg
```

or content hash + suffix.

Also use `createNewFile()` or atomic write to a temp file then rename.

---

## 2. Unsupported/unknown content providers bypass file-size protection

### Where

`ReceiptOcrService.validateFileSize()`

If `statSize` is unknown:

```kotlin
Timber.w("Skipping size validation.")
return
```

### Problem

Some content providers return `-1`. The service then copies the entire stream to a temp file and decodes/processes it.

### Impact

A huge image/PDF can cause:

- disk pressure
- memory pressure
- slow OCR
- ANR-like user experience
- app crash

### Severity

**High**

### Fix

When size is unknown, enforce a streaming copy limit:

```text
copy at most MAX_FILE_SIZE + 1 bytes
abort if exceeded
```

Also enforce PDF page count/bitmap render limits before processing.

---

## 3. Failed parsing creates fake `0.01 EUR` pending reviews

### Where

`ReceiptRepository.processReceipt()`

On parse failure or missing total:

```kotlin
FALLBACK_SUGGESTED_AMOUNT = 0.01
suggestedCurrency = "EUR"
suggestedMerchant = "Parsing Failed"
```

### Problem

This creates a financially plausible but fake transaction.

If approved without correction, the app records a real `0.01 EUR` purchase.

### Impact

Corrupts:

- expense history
- budgets
- merchant/category learning
- recurring detection
- exports
- AI/search results

### Severity

**Critical**

### Fix

Pending reviews must support incomplete drafts:

```kotlin
suggestedAmount: Double?
suggestedCurrency: String?
extractionState = PARSE_FAILED
missingFields = AMOUNT | MERCHANT | CURRENCY
```

Do not invent money.

---

## 4. Manual receipt scan triggers warranty extraction before user confirmation

### Where

`ReceiptRepository.processReceipt(autoCreateReview = false)`

Even manual scans that do not auto-create a pending review still run:

```kotlin
warrantyUseCase.execute(receiptId, ocrResult.fullText)
```

### Problem

A user may scan a receipt, see bad OCR, abandon the review, or never create an expense. Warranty/return-window drafts can still be created.

### Impact

False warranties/return windows can appear for unconfirmed receipts.

This connects to earlier warranty issues:

- stale return windows
- low-confidence drafts
- receipt link drift

### Severity

**High**

### Fix

Move warranty extraction behind a receipt lifecycle event:

```text
receipt confirmed
receipt linked to expense
user explicitly enables auto-extract warranty
```

At minimum, mark generated assets as `sourceReceiptUnconfirmed = true`.

---

## 5. Statement screenshot import uses one `ScannedReceipt` for many transactions

### Where

`ReceiptRepository.processStatement()`

It creates one `ScannedReceipt`:

```kotlin
parsedMerchant = "Bank Statement"
```

Then creates one `PendingReview` per parsed transaction, all pointing to the same `scannedReceiptId`.

### Problem

`ScannedReceipt.expenseId` is single-valued.

If multiple pending reviews from that statement are approved and each approval links the scanned receipt to an expense, the statement receipt can only point to one final expense, likely the last approved one.

### Impact

- statement proof link is overwritten
- earlier approved transactions lose source linkage
- receipt matching UI may show a bank statement as an unmatched receipt
- one source image cannot accurately represent many transaction links

### Severity

**Critical**

### Fix

Separate source documents from transaction-level extraction.

Recommended model:

```text
SourceDocument(
  id,
  type = RECEIPT | BANK_STATEMENT,
  imagePath,
  rawText
)

SourceExtractedTransaction(
  sourceDocumentId,
  rowIndex,
  parsedAmount,
  parsedMerchant,
  parsedDate,
  pendingReviewId,
  expenseId
)
```

Do not store statement screenshots as ordinary one-expense receipts.

---

## 6. Receipt item categorizations are not linked when receipt becomes an expense

### Where

- `ReceiptRepository.createExpenseFromReceipt()`
- `ReceiptRepository.linkReceiptToExpense()`
- `ReceiptItemCategorizationDao.linkToExpense()`

The DAO has:

```kotlin
linkToExpense(receiptId, expenseId, timestamp)
```

But `createExpenseFromReceipt()` and receipt matching only call:

```kotlin
scannedReceiptDao.linkToExpense(receiptId, id)
```

### Impact

AI item categorizations remain:

```text
expenseId = null
```

even after the receipt is saved/matched to an expense.

That breaks:

- item-level budget/category allocation
- split-by-item workflows
- receipt item audit trail
- expense detail item breakdown
- item-level warranties/returns/price protection

### Severity

**Critical**

### Fix

Whenever a receipt links to an expense:

```kotlin
scannedReceiptDao.linkToExpense(...)
receiptItemCategorizationDao.linkToExpense(...)
warranty/return links update too
```

Use one coordinator:

```kotlin
ReceiptLifecycleCoordinator.linkReceiptToExpense(receiptId, expenseId)
```

---

## 7. Receipt matching ignores currency

### Where

`ReceiptTransactionMatcher.calculateMatchScore()`

Amount score:

```kotlin
abs(receipt.parsedTotal - transaction.effectiveAmount)
```

No currency check.

### Impact

A receipt for `100 USD` can match an expense for `100 EUR`.

### Severity

**Critical with multi-currency**

### Fix

Either:

- require same currency for receipt/expense matching, or
- compare normalized/base amounts with conversion confidence.

Add currency to score factors:

```text
currencyScore = 1.0 exact match
currencyScore = converted comparison if rate available
currencyScore = 0.0 if incompatible
```

---

## 8. Multiple receipts can link to one expense

### Where

- `ScannedReceipt.expenseId` has non-unique index
- `ReceiptRepository.linkReceiptToExpense()`
- `ReceiptTransactionMatcher`

### Problem

Schema allows many `scanned_receipts` rows to reference the same `expenseId`.

This may be intentional if multiple proof documents per expense are allowed, but the UI/model seems to treat one receipt as the match.

### Impact

Possible duplicate proof links:

- same receipt image scanned twice
- two receipts auto-match to same transaction
- one expense appears to have many receipt proofs
- approving a suggestion does not check whether the expense is already linked

### Severity

**High**

### Fix

Decide policy:

Option A — one receipt per expense:
```kotlin
Index(value = ["expenseId"], unique = true)
```

Option B — multiple attachments:
create explicit `ExpenseAttachment` table and do not call it receipt matching one-to-one.

Either way, auto-match should avoid matching to already-linked expenses unless user confirms.

---

## 9. Receipt currency defaults to EUR too aggressively

### Where

- `ReceiptParser.detectCurrency()`
- `ReceiptRepository.saveManualReceiptRecord()`
- parse-failure branches
- `ReceiptScanViewModel.saveExpenseInternal()`

`ReceiptParser.detectCurrency()` defaults to:

```kotlin
else -> "EUR"
```

The save flow then prefers parsed receipt currency over home currency.

### Impact

For non-EUR users or foreign receipts, missing currency becomes EUR silently.

Example:

- user in US scans receipt with no visible `$`
- parser defaults `EUR`
- expense saved as EUR, not USD/home currency

### Severity

**Critical with multi-currency**

### Fix

Use explicit confidence/source:

```kotlin
currency: String?
currencySource: SYMBOL | TEXT | MERCHANT_COUNTRY | HOME_DEFAULT | UNKNOWN
currencyConfidence: Float
```

If no currency detected, default to home currency only with a visible warning:

> “Currency was not found. Using home currency USD.”

Also allow user to edit currency in receipt review.

---

## 10. Receipt review cannot edit currency

### Where

`ReceiptScanViewModel`
`ReceiptRepository.createExpenseFromReceipt()`

The repository accepts currency, but the ViewModel/UI does not expose a currency correction field.

### Impact

Wrong OCR/default currency cannot be corrected during receipt approval.

### Severity

**High**

### Fix

Add `editCurrency` to `ReceiptScanState`.

Use it in:

```kotlin
ReceiptSaveRequest.currency
createExpenseFromReceipt(currency = request.currency)
```

---

## 11. AI quick save uses suggestions without per-field confidence thresholds

### Where

`ReceiptScanViewModel.buildQuickSavePreview()`

It accepts AI suggestions if fields are missing:

```kotlin
receiptSuggestion?.merchant?.value
receiptSuggestion?.total?.value
receiptSuggestion?.date?.value
```

No confidence threshold is checked.

### Impact

A low-confidence AI amount/merchant/date can be auto-filled into a quick-save preview.

There is user confirmation, but the whole purpose of quick save is trust/speed. Low-confidence values should not be treated as safe.

### Severity

**High**

### Fix

For each field:

```text
merchant confidence >= threshold
total confidence >= threshold
date confidence >= threshold
```

If confidence is missing, treat as low confidence unless provider is deterministic and validated.

Also show confidence in the preview.

---

## 12. AI receipt extraction output validation is incomplete

### Where

`SuggestReceiptExtractionUseCase`
`CloudReceiptAssistService`

Cloud parsing checks finite numeric values, but does not enforce:

- confidence in `0.0..1.0`
- total > 0
- tax >= 0
- tax <= total
- date plausible
- merchant non-blank after normalization
- amount max bound
- currency consistency

### Impact

AI can produce:

```text
total = -20
tax = 999
confidence = 42
date = 123
```

and the artifact may be marked ready. Manual save catches some amount problems, but not all values or artifacts.

### Severity

**High**

### Fix

Add shared validator:

```kotlin
ReceiptAssistSuggestionValidator
```

Reject or downgrade invalid suggestions before storing READY artifacts or allowing quick save.

---

## 13. Receipt item AI validation checks only item count

### Where

`CategorizeReceiptItemsUseCase.validateResult()`

Current validation:

```kotlin
if result.items.isEmpty()
if result.items.size != input.lineItems.size
```

### Problem

It does not validate:

- item identity/order
- item amount matches input amount
- category ID exists
- confidence is 0..1
- suggested category name matches ID
- tax distribution sums to receipt tax
- tax values are non-negative
- no duplicate/hallucinated item descriptions
- no negative/non-finite item amounts

### Impact

AI can return the correct number of rows but wrong financial/category data.

### Severity

**Critical**

### Fix

Validate every item against input by index or stable item ID:

```text
output[i].amount == input[i].totalPrice within cents
categoryId in allowed categories unless marked new
confidence in 0..1
tax sum <= totalTax
no negative/non-finite values
```

---

## 14. Item-level tax can be duplicated per item

### Where

`ReceiptItemCategorizationRepository.saveCategorizationResult()`

```kotlin
taxAmount = result.taxDistribution[item.suggestedCategory?.categoryId]
```

### Problem

`taxDistribution` is category-level:

```text
categoryId -> total tax for that category
```

If three items belong to the same category, each row gets the full category tax.

### Example

Tax distribution:

```text
Food -> €3.00
```

Three food items are saved as:

```text
item1.taxAmount = 3.00
item2.taxAmount = 3.00
item3.taxAmount = 3.00
```

Total item tax becomes `€9.00`.

### Severity

**Critical**

### Fix

Either:

1. store category tax in a separate table, or
2. allocate tax per item proportionally.

Recommended:

```text
ReceiptItemTaxAllocation(
  receiptItemCategorizationId,
  taxAmount
)
```

or add `itemTaxAmount` computed from item share.

---

## 15. Receipt item categorization save is not transactional

### Where

`ReceiptItemCategorizationRepository.saveCategorizationResult()`

It inserts item rows one by one.

### Problem

If saving fails halfway:

- partial item categorization rows remain
- use case catches failure and sets receipt status back to `PENDING`
- existing partial rows may cause `AlreadyAnalyzed` later, because the repo only checks whether any rows exist

### Impact

A receipt can have incomplete item analysis but be treated as already analyzed.

### Severity

**High**

### Fix

Wrap delete/insert/status update in a transaction.

Better:

```kotlin
replaceCategorizationForReceipt(receiptId, result)
```

which:

1. deletes old rows
2. inserts all new rows
3. updates receipt status
4. commits atomically

Also require expected item count on read.

---

## 16. Receipt item rows lack stable item identity

### Where

`ReceiptItemCategorization`

Current fields include:

- `itemDescription`
- `itemAmount`

No:

- item index
- item fingerprint
- quantity/unit identity
- original parsed line id

### Impact

Cannot safely:

- match AI output back to input line
- edit/rescan receipt items
- link warranty/return to a specific item
- split receipt items among participants
- persist price-protection tracking
- distinguish duplicate item names

### Severity

**High**

### Fix

Persist receipt line items as first-class rows:

```text
ReceiptLineItem(
  id,
  receiptId,
  lineIndex,
  description,
  quantity,
  unitPrice,
  totalPrice,
  currency,
  fingerprint
)
```

AI categorizations should reference `receiptLineItemId`.

---

## 17. Receipt line item parser defines patterns it does not use

### Where

`ReceiptParser`

`lineItemPatterns` includes four patterns, including `@ unit price` patterns.

But `extractLineItems()` only uses:

- pattern 0
- pattern 1

### Impact

Receipts with formats like:

```text
Item @ 2.50 5.00
2 x Item @ 2.50 5.00
```

will not parse despite patterns existing.

### Severity

**Medium / High**

### Fix

Either implement all patterns or remove unused patterns and tests.

Add line-item parser tests for:

- quantity x item total
- item @ unit total
- quantity x item @ unit total
- discounts
- negative lines
- tax lines

---

## 18. Receipt total can be derived from line items without enough safeguards

### Where

`ReceiptParser.parse()`

```kotlin
val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }
```

### Problem

If OCR misses total and line item parsing includes wrong rows, subtotal rows, duplicate rows, or partial pages, the app treats item sum as receipt total.

### Impact

The receipt amount can be wrong but appear complete.

### Severity

**High**

### Fix

Track total source:

```text
TOTAL_KEY
AMOUNT_KEY
LINE_ITEM_SUM
AI
USER
UNKNOWN
```

If total is from line item sum, lower confidence and require review.

---

## 19. `ScannedReceiptDao.insert()` uses `REPLACE`

### Where

`ScannedReceiptDao`

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
```

### Problem

`REPLACE` is delete + insert. If a conflict is ever introduced by future unique indexes, it can delete and recreate rows, breaking relationships.

### Impact

Potential future damage:

- receipt item rows cascade delete
- warranty/return rows cascade delete
- createdAt/id changes
- match status resets

### Severity

**Medium / High**

### Fix

Use explicit insert/update APIs:

```kotlin
insertNew()
updateParsedFields()
updateMatchStatus()
```

Avoid `REPLACE` for user-owned source records.

---

## 20. Duplicate receipt scanning is not detected by content

### Where

`ReceiptRepository.processBatch()`

It only dedupes selected URIs:

```kotlin
uris.distinctBy { it.toString() }
```

### Problem

The same image can be selected through different URIs or scanned twice.

### Impact

Duplicate scanned receipts, duplicate pending reviews, duplicate warranties/return windows.

### Severity

**High**

### Fix

Compute a source fingerprint:

```text
image content hash
normalized OCR text hash
parsed merchant/date/amount/currency key
```

Then decide:

- exact duplicate receipt → skip
- same receipt but new image → ask user
- same transaction proof → attach to existing expense

---

## 21. Receipt matching can match statement receipts

### Where

`ReceiptRepository.processStatement()`
`ScannedReceipt`
`ReceiptMatchingViewModel`

Statement screenshot records are inserted into `scanned_receipts` with normal `UNMATCHED` state.

### Problem

Receipt matching UI may treat a bank statement source document as a normal receipt.

### Impact

A bank statement with `parsedTotal = null` and merchant `"Bank Statement"` can appear in unmatched receipt queue and be manually/auto matched incorrectly.

### Severity

**High**

### Fix

Add source type:

```kotlin
ScannedReceipt.sourceType = RECEIPT_IMAGE | RECEIPT_PDF | BANK_STATEMENT | MANUAL_PLACEHOLDER
```

Receipt matching should include only receipt-like documents, not statement containers.

---

## 22. Receipt matching approve leaves stale suggestion fields

### Where

`ReceiptRepository.approveMatchSuggestion()`

It sets:

```kotlin
expenseId = suggestedId
matchStatus = MANUALLY_MATCHED
```

But does not obviously clear:

```text
suggestedExpenseId
matchConfidence
```

### Impact

Matched receipts may still carry stale suggestion metadata.

### Severity

**Medium**

### Fix

On approval:

```text
expenseId = suggestedId
suggestedExpenseId = null
matchStatus = MANUALLY_MATCHED
matchConfidence = 1.0 or approved score
matchedAt = now
matchedBy = USER
```

---

## 23. Receipt matching uses gross amount in UI and effective amount in scoring

### Where

- `ReceiptTransactionMatcher` uses `transaction.effectiveAmount`
- `ReceiptMatchingScreen` displays `expense.amount`

### Problem

For shared/not-mine expenses, the scoring and display can disagree.

### Impact

User sees:

```text
Suggested match: €100
```

but score matched against `effectiveAmount = €25`.

### Severity

**Medium / High**

### Fix

Display both where relevant:

```text
Gross: €100
My share: €25
```

or use consistent matching/display scope.

---

## 24. Receipt image deletion happens before DB deletion

### Where

`ReceiptRepository.deleteReceipt()`

```kotlin
receipt.imagePath?.let { ocrService.deleteImage(it) }
scannedReceiptDao.delete(receipt)
```

### Problem

If DB delete fails after image delete, the receipt row remains but the image is gone.

### Severity

**Medium / High**

### Fix

Use a safer deletion lifecycle:

1. mark receipt `PENDING_DELETE`
2. delete DB record / commit
3. delete image file
4. if image delete fails, enqueue cleanup

Or keep files content-addressed and garbage-collect unreferenced files.

---

## 25. Raw OCR text is retained indefinitely

### Where

`ScannedReceipt.rawOcrText`
`ReceiptRepository.exportParserDebugData()`

### Problem

OCR text can contain:

- card fragments
- bank account/IBAN
- phone/email
- addresses
- loyalty IDs
- names
- VAT/tax IDs
- purchase details

It is stored as a non-null field and exported in debug reports.

### Impact

Privacy exposure in:

- backups
- debug exports
- database restore/share
- AI artifacts
- support logs if exported

### Severity

**High / privacy**

### Fix

Add retention controls:

```text
keepRawOcrText
deleteRawOcrAfterApproval
rawOcrRetentionDays
redactedOcrText
```

Default should minimize raw retention.

---

## 26. Receipt debug data hardcodes EUR

### Where

`ReceiptScanViewModel`

Debug `ParsedTransaction` uses:

```kotlin
currency = "EUR"
```

even though `parsed.currency` exists.

### Impact

Debug/review diagnostics can mislead parser debugging and import testing.

### Severity

**Medium**

### Fix

Use:

```kotlin
currency = parsed.currency
```

---

## 27. PDF processing silently limits to first 5 pages

### Where

`ReceiptOcrService.extractPdfText()`
`ReceiptOcrService.processPdfWithOcr()`

Text extraction limits first 5 pages; OCR fallback processes up to 5 pages.

### Problem

This may be fine for receipts, but not for bank statements.

### Impact

Long statement imports can miss transactions after page 5.

### Severity

**High for statement imports**

### Fix

Different policy by source type:

- receipt PDF: 5-page max maybe okay
- bank statement: user-visible page limit or full paged processing
- show warning: “Only first 5 pages processed”

---

## 28. OCR retry is inconsistent

### Where

`ReceiptOcrService`

Image path uses:

```kotlin
runWithRetry(maxAttempts = 3)
```

PDF OCR path calls:

```kotlin
recognizeText(inputImage)
```

directly.

### Impact

Scanned PDF pages may fail due to a transient ML Kit issue without retry, while images retry.

### Severity

**Medium**

### Fix

Use the same retry wrapper for every OCR call.

---

## 29. OCR saved image may be too low quality for later cloud/image assist

### Where

`ReceiptOcrService.loadAndCorrectBitmap()`
`saveReceiptImage()`

Images are downsampled to 384–1024 px depending on memory, then compressed to JPEG quality 80.

### Problem

This is good for memory, but the saved image becomes the later source for image-aware AI. Small/blurred receipts may lose details.

### Impact

Cloud/on-device image assist may analyze a lower-quality copy than the original.

### Severity

**Medium**

### Fix

Store two variants:

- display thumbnail
- OCR/AI source image with bounded but higher resolution

Or store original securely if user opts in.

---

## 30. Item categorization does not affect the expense/category/budget model

### Where

`ReceiptItemCategorization`
`ReceiptRepository.createExpenseFromReceipt()`

The receipt expense has one `categoryId`. Item categorization is stored separately and does not allocate the expense across categories/budgets.

### Impact

A supermarket receipt containing:

- groceries
- household supplies
- medicine

may still count entirely under one category.

AI item categorization appears powerful, but financial calculations may ignore it.

### Severity

**High UX / financial correctness if item allocation is promised**

### Fix

Decide semantics:

Option A — item categorization is informational only  
Label it clearly.

Option B — item categorization drives budgets  
Add item-level budget allocation:

```text
ExpenseCategoryAllocation(
  expenseId,
  categoryId,
  amount,
  taxAmount,
  source = RECEIPT_ITEMS
)
```

Budget queries must use allocations where present.

---

# Recommended fix order

## PR 1 — Add `ReceiptLifecycleCoordinator`

All receipt state changes go through one coordinator:

```kotlin
processReceiptImage()
createPendingReviewFromReceipt()
createExpenseFromReceipt()
linkReceiptToExpense()
unlinkReceipt()
deleteReceipt()
processStatement()
saveItemCategorization()
```

It should update:

- scanned receipt
- receipt item rows
- warranty/return windows
- pending reviews
- expense links
- source/audit metadata

## PR 2 — Fix fake fallback money

Remove `0.01 EUR`.

Support incomplete pending reviews and incomplete receipts.

## PR 3 — Split source documents from receipt transactions

Bank statements are not one-expense receipts.

Add:

```text
SourceDocument
SourceExtractedTransaction
```

or at least `ScannedReceipt.sourceType`.

## PR 4 — Link item categorizations to expenses

On every receipt link/match/create:

```kotlin
receiptItemCategorizationDao.linkToExpense(receiptId, expenseId, now)
```

Do the same for warranties/return windows.

## PR 5 — Add stable receipt image/file identity

Use UUID filenames, content hashes, atomic writes, and file reference counting or garbage collection.

## PR 6 — Add currency correction and validation

Receipt review must allow currency editing.

Receipt save must validate:

- amount finite and positive
- currency valid
- date plausible
- merchant non-blank
- location pair valid if present

## PR 7 — Harden AI item categorization validation

Validate:

- item count
- item order/fingerprint
- amounts
- category IDs
- confidence bounds
- tax distribution
- no hallucinated values

## PR 8 — Fix tax allocation

Do not copy category-level tax total onto every item.

Store category-level tax separately or allocate item-level tax.

## PR 9 — Make receipt matching currency-aware and link-safe

- require same currency or conversion
- avoid already-linked expenses unless allowed
- clear suggestion fields after approval
- add source type filtering

## PR 10 — Add raw OCR retention controls

- purge raw OCR after approval
- redacted OCR storage
- debug export warning
- encrypted backup coverage

---

# Regression tests to add

1. Two receipts saved in same millisecond do not overwrite images.
2. Unknown-size URI larger than max is rejected during streaming copy.
3. Parse-failed receipt creates incomplete review, not `0.01 EUR`.
4. Manual scan does not create active warranty/return assets before confirmation.
5. Statement import with 3 transactions does not overwrite one receipt `expenseId`.
6. Statement source document does not appear in normal receipt matching queue.
7. Creating expense from receipt links item categorization rows to expense.
8. Manual receipt match links item categorization rows to expense.
9. Receipt match requires same currency or valid conversion.
10. Expense already linked to another receipt is not auto-matched again.
11. User can correct receipt currency before saving.
12. Missing currency defaults to home currency with warning, not hardcoded EUR.
13. AI quick save refuses low-confidence merchant/amount/date suggestions.
14. AI receipt suggestion rejects negative total, tax > total, invalid date, confidence > 1.
15. AI item categorization rejects hallucinated category IDs.
16. AI item categorization rejects amount mismatch against parsed line items.
17. AI item categorization save is atomic; partial rows do not remain after failure.
18. Item tax allocation sums to receipt tax, not duplicated per item.
19. Duplicate receipt image selected through two URIs is detected.
20. PDF statement over 5 pages shows a visible truncation warning or processes all pages.
21. Deleting receipt does not leave DB row pointing to missing image if delete fails.
22. Raw OCR purge removes text but preserves non-sensitive audit/source metadata.
23. Receipt matching display uses the same amount basis as scoring.
24. Item categorizations either affect budgets through allocations or are clearly informational.

---

# Top three fixes

If you only fix three things first:

1. **Create a receipt lifecycle coordinator and link receipt-derived assets/items whenever a receipt is matched or saved as an expense.**
2. **Remove fake fallback `0.01 EUR` and model incomplete receipt/pending-review states properly.**
3. **Make receipt matching and receipt saving currency-aware, with user-editable currency.**

Those give the biggest correctness and data-integrity gains.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `ReceiptOcrService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt

- `ReceiptParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt

- `BankStatementParser.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `ScannedReceipt.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `ScannedReceiptDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt

- `ReceiptScanViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt

- `CategorizeReceiptItemsUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt

- `ReceiptItemCategorizationInputBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt

- `ReceiptItemCategorizationModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt

- `ReceiptItemCategorization.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptItemCategorization.kt

- `ReceiptItemCategorizationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptItemCategorizationDao.kt

- `ReceiptItemCategorizationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptItemCategorizationRepository.kt

- `CloudReceiptItemCategorizationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt

- `OnDeviceReceiptItemCategorizationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt

- `HybridReceiptItemCategorizationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt

- `SuggestReceiptExtractionUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt

- `ReceiptAssistInputBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt

- `CloudReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `SmartReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt

- `ReceiptMatchingScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingScreen.kt

- `ReceiptMatchingViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt

- `ReceiptTransactionMatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt