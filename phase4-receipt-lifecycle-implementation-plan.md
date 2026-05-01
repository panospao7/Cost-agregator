# Phase 4 — Receipt Lifecycle Foundation Implementation Plan

## 0. Phase 4 Mission

Phase 4 creates a single, coherent receipt lifecycle layer.

Current audit problems:

- 5 receipt entry paths: camera, gallery/file import, bank statement, email ingestion, manual OCR fallback.
- `ReceiptRepository` is a 930-line god class.
- 4 classes directly access `ScannedReceiptDao` outside `ReceiptRepository`.
- Receipt linking is duplicated and unsafe.
- Same receipt can be linked multiple times without guardrails.
- Bank statements, retail receipts, and email receipts are all mixed in `ScannedReceipt` without a document type.
- Warranty extraction runs on inappropriate documents such as bank statements.
- Receipt images are excluded from backup.
- Scanned receipt deduplication does not exist.
- Receipt line items are stored as embedded JSON.
- OCR failures are stored as placeholder text instead of explicit lifecycle state.
- Email receipts have no image but downstream features may assume one exists.

The goal is to introduce a **ReceiptLifecycleCoordinator** and related small services so all receipt intake, processing, matching, linking, deletion, backup, and downstream triggers follow one contract.

---

# 1. Dependency on Phase 3

Phase 4 should integrate with Phase 3’s `TransactionLifecycleCoordinator`.

Important boundary:

- `ReceiptLifecycleCoordinator` owns receipt/document lifecycle.
- `TransactionLifecycleCoordinator` owns expense creation/update/delete.
- Receipt code must not insert expenses directly.
- Transaction code must not directly mutate receipt tables except through a small receipt-linking service.

To avoid circular dependencies:

- Do **not** inject the full `ReceiptLifecycleCoordinator` into `TransactionLifecycleCoordinator`.
- Create a small `ReceiptLinkService` or `ReceiptLinkCoordinator`.
- Both coordinators can depend on `ReceiptLinkService`.

Recommended ownership:

| Concern | Owner |
|---|---|
| OCR / parse / save receipt | `ReceiptLifecycleCoordinator` |
| Create expense from receipt | `TransactionLifecycleCoordinator` |
| Link receipt to expense | `ReceiptLinkService` |
| Match receipt to existing expense | `ReceiptMatchingCoordinator` or existing matcher + link service |
| Warranty/return/price/item side effects | `ReceiptSideEffectDispatcher` |
| Receipt CRUD/query | slim `ReceiptRepository` |

---

# 2. Non-goals

Do not solve everything at once.

Phase 4 should **not** include:

- replacing ML Kit OCR
- building a real price comparison API
- full receipt UI redesign
- complete bank API integration
- complete relational line-item migration unless you choose to split it as a late Phase 4 PR
- soft-deleting all receipts unless all receipt queries are updated
- OCR language/model expansion
- full cloud/offline AI rewrite

---

# 3. Target Architecture

## 3.1 New central receipt lifecycle owner

Create:

`ReceiptLifecycleCoordinator`

Responsibilities:

1. Process camera/gallery/file receipt inputs.
2. Process email receipt inputs.
3. Process bank statement documents.
4. Process receipt batches.
5. Save manual receipt records when OCR fails or user skips OCR.
6. Run receipt validation.
7. Run duplicate receipt detection.
8. Run OCR.
9. Run parser.
10. Persist `ScannedReceipt`.
11. Create receipt-derived pending reviews where configured.
12. Trigger downstream side effects.
13. Write receipt lifecycle events.
14. Expose lifecycle status for UI/debug.

It should not directly create expenses except by delegating to `TransactionLifecycleCoordinator`.

---

## 3.2 Split the current `ReceiptRepository`

Current `ReceiptRepository` handles too much:

- OCR orchestration
- parsing
- scan persistence
- expense creation
- bank statement parsing
- pending review creation
- warranty extraction
- matching
- debug export
- receipt deletion
- candidate expense search

Target split:

| Component | Responsibility |
|---|---|
| `ReceiptLifecycleCoordinator` | orchestration/state machine |
| `ReceiptRepository` | thin receipt data/query gateway |
| `ReceiptLinkService` | link/unlink receipt to expense safely |
| `ReceiptDuplicateDetector` | receipt-level duplicate detection |
| `ReceiptInputValidator` | MIME/file/size/image/PDF checks |
| `ReceiptQualityEvaluator` | OCR/parse confidence policy |
| `ReceiptSideEffectDispatcher` | warranty, return, price, categorization, matching triggers |
| `BankStatementLifecycleProcessor` | statement-specific flow |
| `EmailReceiptLifecycleProcessor` | email-specific flow |
| `ReceiptAssetStore` | file persistence, deletion, backup manifest support |
| `ReceiptDebugExporter` | debug-only raw OCR export |

`ReceiptRepository` can remain as the compatibility facade during migration, but orchestration should move out of it.

---

# 4. Core Receipt Invariants

Every receipt/document record should have:

1. Explicit source type.
2. Explicit document type.
3. Explicit processing status.
4. Explicit created/updated timestamp.
5. Explicit currency if parsed amount exists.
6. No fake OCR success represented as text only.
7. A lifecycle event for important transitions.
8. Duplicate detection attempted where applicable.
9. Safe link semantics if connected to an expense.
10. Downstream side effects triggered only for appropriate document types.

---

# 5. New / Updated Domain Concepts

## 5.1 Receipt source type

Add a source type concept.

Suggested values:

- `CAMERA`
- `GALLERY`
- `FILE_IMPORT`
- `EMAIL`
- `BANK_STATEMENT`
- `MANUAL_RECORD`
- `BATCH_SCAN`
- `DEBUG_IMPORT`
- `UNKNOWN`

This answers: **where did the document come from?**

---

## 5.2 Receipt document type

Add a document type concept.

Suggested values:

- `RETAIL_RECEIPT`
- `EMAIL_RECEIPT`
- `BANK_STATEMENT`
- `MANUAL_PLACEHOLDER`
- `PDF_RECEIPT`
- `UNKNOWN`

This answers: **what kind of document is this?**

This is important because:

- bank statements should not trigger warranty extraction
- email receipts may have no image
- manual placeholders may have no OCR
- retail receipts can trigger matching, warranty, return windows, item categorization, and price protection

---

## 5.3 Receipt processing status

Add a processing status separate from `matchStatus`.

Suggested values:

- `CAPTURED`
- `VALIDATING`
- `VALIDATION_FAILED`
- `DUPLICATE_DETECTED`
- `OCR_PENDING`
- `OCR_RUNNING`
- `OCR_FAILED`
- `OCR_COMPLETED`
- `PARSE_FAILED`
- `PARSED`
- `REVIEW_CREATED`
- `EXPENSE_CREATED`
- `SIDE_EFFECTS_COMPLETED`
- `DELETED`

`MatchStatus` should remain focused only on matching:

- `UNMATCHED`
- `AUTO_MATCHED`
- `SUGGESTED`
- `MANUALLY_MATCHED`
- `REJECTED`

---

## 5.4 Receipt lifecycle event ledger

Add a receipt-specific event table.

Suggested event types:

- `RECEIPT_CAPTURED`
- `VALIDATION_FAILED`
- `DUPLICATE_SKIPPED`
- `OCR_STARTED`
- `OCR_COMPLETED`
- `OCR_FAILED`
- `PARSE_COMPLETED`
- `PARSE_FAILED`
- `RECEIPT_SAVED`
- `PENDING_REVIEW_CREATED`
- `EXPENSE_CREATED_FROM_RECEIPT`
- `RECEIPT_LINKED_TO_EXPENSE`
- `RECEIPT_UNLINKED_FROM_EXPENSE`
- `MATCH_SUGGESTED`
- `MATCH_REJECTED`
- `WARRANTY_EXTRACTION_TRIGGERED`
- `ITEM_CATEGORIZATION_TRIGGERED`
- `SIDE_EFFECT_FAILED`
- `RECEIPT_DELETED`
- `ASSET_DELETED`
- `BACKUP_EXPORTED`
- `RESTORED_FROM_BACKUP`

Fields:

- event ID
- receipt ID, nullable for pre-save validation failures
- source type
- document type
- event type
- occurred at
- old status
- new status
- actor/source
- message
- metadata JSON
- error details, nullable

---

## 5.5 Receipt-expense link table

Current `ScannedReceipt.expenseId` supports only one expense per receipt.

This is unsafe because:

- a bank statement can produce many pending reviews and many expenses
- linking one statement receipt to multiple transactions overwrites `expenseId`
- duplicate linking logic is scattered

Add a link table:

`receipt_expense_links`

Fields:

- link ID
- receipt ID
- expense ID
- link type
- confidence
- source
- created at
- created by
- is primary
- metadata JSON

Suggested link types:

- `DIRECT_SAVE`
- `REVIEW_APPROVAL`
- `AUTO_MATCH`
- `MANUAL_MATCH`
- `EMAIL_AUTO_CREATE`
- `BANK_STATEMENT_TRANSACTION`
- `DEBUG_RESTORE`

Indexes:

- receipt ID
- expense ID
- unique receipt ID + expense ID
- link type
- created at

Keep `ScannedReceipt.expenseId` temporarily for backward compatibility, but the link table should become the canonical source.

Compatibility rule:

- for normal single retail receipts, also update legacy `expenseId`
- for bank statements, use link table only or set primary link only if explicitly needed
- do not overwrite existing legacy link without explicit relink policy

---

# 6. Database Changes

## 6.1 `ScannedReceipt` additions

Add nullable/defaulted columns:

- `sourceType`
- `documentType`
- `processingStatus`
- `sourceFingerprint`
- `imageHash`
- `textFingerprint`
- `semanticFingerprint`
- `ocrConfidence`
- `parseFailureReason`
- `updatedAt`

Optional later:

- `assetSizeBytes`
- `assetMimeType`
- `assetWidth`
- `assetHeight`
- `pdfPageCount`
- `isAssetBackedUp`
- `rawTextRetentionPolicy`

---

## 6.2 Backfill strategy

For existing receipts:

### Source/document type

- if linked to `EmailReceiptSource` → `EMAIL`, `EMAIL_RECEIPT`
- if `parsedMerchant = "Bank Statement"` or known statement debug marker → `BANK_STATEMENT`, `BANK_STATEMENT`
- if `imagePath IS NULL` and no email source → `UNKNOWN`, `UNKNOWN`
- if `rawOcrText` starts with failure placeholder → `MANUAL_RECORD`, `MANUAL_PLACEHOLDER`
- otherwise → `GALLERY` or `UNKNOWN`, `RETAIL_RECEIPT`

### Processing status

- if `rawOcrText` starts with `"Scan Failed"` → `OCR_FAILED`
- if `rawOcrText` starts with `"[OCR Failed or Skipped]"` → `OCR_FAILED`
- if parsed merchant/amount/date exists → `PARSED`
- else if raw OCR text is non-blank → `OCR_COMPLETED`
- else → `CAPTURED`

### Link table

Backfill `receipt_expense_links` from existing `ScannedReceipt.expenseId`.

- `linkType = AUTO_MATCHED` if existing match status is `AUTO_MATCHED`
- `linkType = MANUAL_MATCH` if existing match status is `MANUALLY_MATCHED`
- else `linkType = DIRECT_SAVE` or `UNKNOWN`
- `confidence = matchConfidence` if available

---

# 7. Receipt Lifecycle Flow

## 7.1 Camera/gallery/file retail receipt flow

Target flow:

1. UI gets URI.
2. ViewModel calls `ReceiptLifecycleCoordinator.processReceiptInput`.
3. Coordinator validates URI/file.
4. Coordinator persists/copies asset through `ReceiptAssetStore`.
5. Coordinator computes image/file hash.
6. Coordinator checks receipt duplicate detector.
7. Coordinator records lifecycle event.
8. Coordinator runs OCR.
9. Coordinator evaluates OCR quality.
10. Coordinator runs receipt parser.
11. Coordinator evaluates parse quality.
12. Coordinator saves `ScannedReceipt`.
13. Coordinator optionally creates pending review if batch mode/config says so.
14. Coordinator triggers side effects based on document type and confidence.
15. UI receives structured result.

Important:

- OCR failure should not be represented only as raw text.
- Manual fallback can still save a receipt record, but status should be explicit.
- Duplicate capture should return existing receipt information.

---

## 7.2 Receipt save to expense flow

Target flow:

1. User confirms parsed receipt fields.
2. ViewModel builds transaction creation request.
3. `TransactionLifecycleCoordinator` creates expense.
4. Source link info includes receipt ID.
5. Transaction flow calls `ReceiptLinkService` inside its source-link step.
6. Receipt link event is written.
7. Receipt match status becomes `MANUALLY_MATCHED` or `AUTO_MATCHED` depending on source.
8. Receipt side effects run if needed.

`ReceiptRepository.createExpenseFromReceipt()` should be removed or reduced to a compatibility wrapper.

---

## 7.3 Pending review approval from receipt

Target flow:

1. Review approval uses `TransactionLifecycleCoordinator`.
2. If `PendingReview.scannedReceiptId` exists, request includes source receipt ID.
3. `ReceiptLinkService` links the receipt to the created expense.
4. For bank statements, this creates a link row without overwriting single legacy `expenseId`.
5. For normal retail receipts, it may update legacy `expenseId` for compatibility.

`ReviewQueueRepository` should not call `scannedReceiptDao.linkToExpense()` directly.

---

## 7.4 Bank statement flow

Target flow:

1. Coordinator receives bank statement URI.
2. Validate as statement-capable document.
3. Persist asset.
4. OCR/PDF text extraction.
5. Save one `ScannedReceipt` as `documentType = BANK_STATEMENT`.
6. Parse statement transactions via bank statement parser.
7. For each detected transaction:
   - run duplicate checks against expenses and pending reviews
   - create `PendingReview` candidate if not duplicate
   - store statement transaction metadata if needed
8. Do **not** run warranty extraction.
9. Do **not** run price protection.
10. Do **not** run normal retail receipt matching.
11. Write summary lifecycle event.

Important:

- A bank statement is a source document for many transaction candidates.
- It should not behave like a single receipt linked to one expense.

---

## 7.5 Email receipt flow

Target flow:

1. Email service parses provider-specific email.
2. Email service calls `ReceiptLifecycleCoordinator.processEmailReceipt`.
3. Coordinator checks message ID uniqueness.
4. Coordinator computes email/source fingerprint.
5. Coordinator checks semantic duplicate.
6. Coordinator saves `ScannedReceipt` with `documentType = EMAIL_RECEIPT`.
7. Coordinator saves `EmailReceiptSource`.
8. If configured for auto-expense:
   - delegate expense creation to `TransactionLifecycleCoordinator`
   - link via `ReceiptLinkService`
9. Trigger applicable side effects:
   - warranty extraction if content supports it
   - return window
   - item categorization if line items exist
   - no image-dependent feature unless image exists

Email ingestion should not inject or call `ScannedReceiptDao` directly.

---

# 8. Receipt Duplicate Detection

## 8.1 New component

Create `ReceiptDuplicateDetector`.

It should use multiple signals:

1. external source ID  
   Example: email message ID.

2. exact file hash  
   Same uploaded file.

3. image perceptual hash  
   Same physical receipt photographed differently.

4. normalized OCR text fingerprint  
   Same recognized text.

5. semantic fingerprint  
   merchant + amount + date + currency + item digest.

6. email fingerprint  
   provider + merchant + amount + date + message metadata.

## 8.2 Duplicate result

Return structured result:

- no duplicate
- exact duplicate
- likely duplicate
- possible duplicate
- existing receipt ID
- confidence
- reason
- recommended action

## 8.3 Policies

### Camera/gallery

- exact duplicate → show existing receipt
- likely duplicate → ask user whether to continue
- possible duplicate → allow but warn

### Batch import

- exact/likely duplicate → skip
- possible duplicate → include in batch report

### Email

- same message ID → duplicate
- same strong fingerprint → duplicate
- weak semantic duplicate → skip or review depending confidence

### Bank statement

- exact document duplicate → skip
- statement transaction duplicate → handled per transaction against expenses/pending reviews

---

# 9. Input Validation and Quality Policy

## 9.1 Receipt input validator

Validate before OCR:

- URI is readable
- MIME type supported
- file size within configured limit
- PDF page limit documented
- image dimensions above minimum threshold
- image is decodable
- not empty/zero-byte
- no dangerous unsupported file type

Optional later:

- blur detection
- underexposure detection
- receipt-like content detection

## 9.2 OCR quality evaluator

Record and evaluate:

- text length
- block count
- average confidence if available
- parser critical fields found
- language/script hints if available
- OCR failure reason

Rules:

- low OCR quality may still save receipt record
- low OCR quality should not silently create expense
- low OCR quality should mark receipt as needing manual review
- AI assist should not run on unusable OCR text unless image-based AI is available

---

# 10. Downstream Side Effect Policy

Create `ReceiptSideEffectDispatcher`.

It should run post-save or post-link effects based on document type, source, confidence, and user settings.

## 10.1 Retail receipt

Allowed effects:

- warranty extraction
- return window extraction
- item categorization
- receipt-to-expense matching
- AI assist availability
- price protection candidate detection

## 10.2 Email receipt

Allowed effects:

- warranty extraction if text has product/warranty signal
- return window extraction
- item categorization if parsed items exist
- price protection if parsed items exist
- no image-only features unless image exists

## 10.3 Bank statement

Allowed effects:

- pending review generation
- debug issue detection
- transaction duplicate checks

Blocked effects:

- warranty extraction
- return window extraction
- price protection
- item categorization
- normal receipt matching

## 10.4 Manual placeholder

Allowed effects:

- manual matching if enough fields exist
- AI assist only if text/image exists

Blocked effects:

- auto warranty
- auto price protection
- auto expense creation

---

# 11. PR Implementation Plan

## PR 0 — Baseline and audit guard

### Goal

Document current behavior before changing it.

### Actions

1. Run compile and tests.
2. Record existing failures.
3. Add a receipt lifecycle audit checklist to docs.
4. Add temporary grep list for direct `ScannedReceiptDao` calls.
5. Do not change behavior yet.

### Done when

- Baseline status is known.
- Existing direct DAO leaks are documented.

---

## PR 1 — Receipt lifecycle models and schema

### Goal

Add the data model foundation.

### Add

- receipt source type
- receipt document type
- receipt processing status
- receipt lifecycle event type
- receipt-expense link entity
- receipt event entity
- DAOs for links/events

### Modify `ScannedReceipt`

Add:

- source type
- document type
- processing status
- fingerprints/hashes
- OCR confidence
- parse failure reason
- updatedAt

### Migration

Backfill:

- document type
- source type
- processing status
- receipt-expense links from legacy `expenseId`

### Tests

- Room migration test
- link table backfill test
- event DAO test
- existing receipt with `expenseId` still works
- email receipt with `imagePath = null` survives migration

### Done when

- Schema supports explicit lifecycle state.
- Existing receipts are not lost.

---

## PR 2 — Receipt data gateway cleanup

### Goal

Make `ReceiptRepository` thinner without behavior changes.

### Actions

1. Move raw DAO operations into clearly named repository methods.
2. Add methods for:
   - save receipt
   - update processing status
   - update parsed fields
   - get by ID
   - get recent receipts
   - get unmatched receipts
   - delete receipt record
3. Add event writing helper.
4. Keep existing public methods as wrappers for now.

### Done when

- Receipt data access has clean named methods.
- No UI changes yet.
- No behavior change.

---

## PR 3 — ReceiptLinkService

### Goal

Centralize receipt-expense linking.

### New component

`ReceiptLinkService`

Responsibilities:

- link receipt to expense
- unlink receipt
- approve match suggestion
- reject match suggestion
- clear match
- prevent accidental overwrite
- write link lifecycle event
- update legacy `ScannedReceipt.expenseId` only for compatible single-link cases

### Policies

- `FAIL_IF_ALREADY_LINKED`
- `ALLOW_MULTIPLE_FOR_STATEMENT`
- `REPLACE_EXISTING_EXPLICIT`
- `LEGACY_COMPAT_SINGLE_PRIMARY`

### Migrate

Replace:

- `ScannedReceiptDao.linkToExpense()`
- `ReceiptRepository.linkReceiptToExpense()`
- manual load-copy-update matching path

### Tests

- link normal receipt once
- second link fails by default
- explicit relink works
- bank statement can link multiple expenses
- unlink clears match state
- legacy `expenseId` stays compatible for normal receipt
- lifecycle event written

### Done when

- linking is safe and centralized.

---

## PR 4 — Coordinator skeleton

### Goal

Introduce `ReceiptLifecycleCoordinator` without moving all flows.

### Coordinator methods

Add high-level methods for:

- process receipt URI
- process batch
- process bank statement
- process email receipt
- save manual receipt record
- delete receipt with asset cleanup
- get lifecycle state

### Internals

Coordinator should call existing repository methods initially.

### Done when

- Coordinator exists and compiles.
- Existing flows still work.
- Tests can exercise coordinator with fakes.

---

## PR 5 — Input validation and asset store

### Goal

Centralize file handling.

### Add

`ReceiptAssetStore`

Responsibilities:

- create temp camera URI
- persist image/PDF copy
- compute file hash
- delete asset
- list referenced assets
- list orphan assets
- provide backup manifest data

Add:

`ReceiptInputValidator`

Validates:

- readable URI
- MIME type
- size limit
- PDF/image support
- decodable image
- minimum dimension where possible

### Migrate

- `ReceiptOcrService.createTempImageUri()` can delegate to asset store or stay OCR-adjacent temporarily.
- `ReceiptRepository.deleteReceipt()` should use asset store.

### Tests

- invalid URI rejected
- oversized file rejected
- unsupported MIME rejected
- asset copy path recorded
- delete removes asset
- missing image is allowed for email receipts

### Done when

- file lifecycle is no longer scattered.

---

## PR 6 — Camera/gallery/file scan migration

### Goal

Move normal scan processing into coordinator.

### Files

- `ReceiptScanViewModel`
- `ReceiptScanScreen`
- `ReceiptRepository`
- `ReceiptOcrService`
- `ReceiptParser`

### Change

`ReceiptScanViewModel.processImageUri()` calls coordinator.

Coordinator handles:

- validation
- duplicate check placeholder or basic exact hash check
- OCR
- parse
- save receipt
- status/event writes
- manual fallback

### Preserve

- scan step UI behavior
- review state
- manual fallback behavior
- parser debug data

### Fix

- OCR failure should become explicit status, not only raw placeholder text.
- parse failure should become explicit status.
- no warranty extraction yet for failed/manual placeholder records.

### Tests

- successful scan reaches review
- OCR failure saves manual placeholder with correct status
- parse failure creates parse-failed receipt state
- gallery/file path works
- receipt event sequence written

### Done when

- retail scan path no longer orchestrated by god repository.

---

## PR 7 — Receipt-to-expense save migration

### Goal

Move receipt-confirmed expense creation to Phase 3 transaction lifecycle.

### Files

- `ReceiptScanViewModel`
- `ReceiptRepository.createExpenseFromReceipt`
- `TransactionLifecycleCoordinator`
- `ReceiptLinkService`

### Change

Receipt save should:

1. validate user-confirmed receipt fields
2. call `TransactionLifecycleCoordinator`
3. include receipt source link info
4. link receipt through `ReceiptLinkService`
5. update receipt lifecycle state

### Remove

- direct expense creation from `ReceiptRepository`
- duplicated receipt link update

### Tests

- receipt save creates expense through transaction coordinator
- receipt is linked once
- duplicate expense result is handled
- failed transaction creation does not mark receipt as linked
- link event written

### Done when

- receipt save does not directly create expenses in receipt repository.

---

## PR 8 — Review queue receipt link migration

### Goal

Remove direct receipt DAO access from review approval.

### Files

- `ReviewQueueRepository`
- `ReviewViewModel`
- `ReceiptLinkService`

### Change

When a pending review with `scannedReceiptId` is approved:

- transaction lifecycle creates expense
- receipt link service links receipt to expense
- bank statement receipts use multi-link policy
- normal receipts use single-link policy

### Tests

- review approval links receipt
- bank statement review approvals create multiple link rows
- normal receipt cannot be overwritten accidentally
- no direct `scannedReceiptDao.linkToExpense()` remains

### Done when

- `ReviewQueueRepository` no longer injects or calls `ScannedReceiptDao`.

---

## PR 9 — Bank statement lifecycle extraction

### Goal

Move bank statement processing out of `ReceiptRepository`.

### Add

`BankStatementLifecycleProcessor`

Responsibilities:

- process statement document
- save statement as receipt document
- parse transactions
- deduplicate transactions
- create pending reviews
- write statement-level summary events

### Change

`ReceiptLifecycleCoordinator.processBankStatement()` delegates to this processor.

### Fix

- bank statements do not trigger warranty extraction
- bank statements do not trigger receipt matching
- bank statements do not use single `expenseId` as canonical link
- statement duplicate document detection exists at least by exact file/text hash

### Tests

- statement creates one receipt document
- statement creates multiple pending reviews
- duplicate statement skipped
- duplicate transaction skipped
- approving multiple statement reviews links all via link table
- warranty extraction not called

### Done when

- statement processing is separated and document-type-aware.

---

## PR 10 — Email receipt lifecycle migration

### Goal

Remove direct `ScannedReceiptDao` access from email ingestion.

### Files

- `EmailReceiptIngestionService`
- email provider parsers
- `EmailReceiptDao`
- `ReceiptLifecycleCoordinator`

### Change

Email service:

- parses provider-specific email
- calls coordinator
- no direct `ScannedReceiptDao`

Coordinator handles:

- message ID dedup
- email fingerprint dedup
- scanned receipt creation
- email source creation
- optional transaction creation via Phase 3 coordinator
- receipt link
- receipt events
- downstream side effects

### Fix

- image is explicitly optional
- document type is `EMAIL_RECEIPT`
- raw email storage/truncation is deliberate and documented
- missing currency becomes validation/review, not silent EUR fallback

### Tests

- same message ID duplicate
- same strong fingerprint duplicate
- email receipt saved with null image path
- email auto-expense delegates to transaction coordinator
- receipt link written
- no direct `ScannedReceiptDao` access remains

### Done when

- email receipt lifecycle is fully coordinator-owned.

---

## PR 11 — Receipt matching migration

### Goal

Make matching use the canonical link service.

### Files

- `ReceiptTransactionMatcher`
- `ReceiptMatchingWorker`
- `ReceiptMatchingViewModel`
- `ReceiptRepository`

### Change

Worker flow:

1. get unmatched receipts through repository/query port
2. matcher scores candidates
3. suggested matches saved through link/match service
4. auto-matches linked through link service
5. events written

### Fix

- no duplicate link implementation
- avoid matching bank statements as retail receipts
- allow configurable lookback window, not only hardcoded 7 days
- do not overwrite existing links without policy

### Tests

- auto-match creates link row
- suggested match updates match status
- rejected suggestion stays rejected
- bank statement excluded
- already-linked receipt skipped
- configurable lookback works

### Done when

- matching has one link path.

---

## PR 12 — Warranty and return window integration

### Goal

Make receipt downstream warranty logic document-aware and remove direct receipt DAO access.

### Files

- `AutoCreateWarrantyFromReceiptUseCase`
- `WarrantyTrackerRepository`
- `WarrantyTextExtractor`
- `ReturnWindowDao`
- `WarrantyExpirationWorker`

### Change

Warranty flow should receive receipt data from coordinator/repository, not direct DAO.

Remove:

- `WarrantyTrackerRepository` direct `ScannedReceiptDao` reads
- `WarrantyTrackerRepository.createManualPlaceholderReceipt()` direct receipt insert

If warranty feature needs a placeholder receipt, it must call receipt lifecycle coordinator.

### Fix

- no warranty extraction for bank statements
- no warranty extraction for OCR failure placeholders
- return window creation is idempotent
- duplicate return-window paths consolidated
- default return days centralized in one policy resolver

### Tests

- retail receipt can create warranty
- email receipt can create warranty if text supports it
- bank statement skipped
- manual placeholder skipped
- existing warranty prevents duplicate
- return window not duplicated

### Done when

- warranty/return windows are receipt-lifecycle-aware.

---

## PR 13 — Price protection integration

### Goal

Remove direct receipt DAO access and make price protection document-aware.

### Files

- `PriceProtectionTracker`
- `PriceProtectionViewModel`
- `ReceiptRepository`

### Change

Price protection reads receipts through a receipt query port/repository method.

Filter:

- retail receipts
- email receipts with parsed items
- recent receipts within eligible window
- exclude bank statements
- exclude OCR failure placeholders

### Fix

- unify return-window days policy with warranty/return module
- clearly mark simulated results
- no direct `ScannedReceiptDao` injection

### Tests

- only eligible receipt types included
- bank statements excluded
- missing image does not break email receipt handling
- simulated flag preserved

### Done when

- price protection no longer bypasses receipt lifecycle.

---

## PR 14 — Item categorization consistency

### Goal

Make item categorization status and data writes consistent.

### Files

- `CategorizeReceiptItemsUseCase`
- `ReceiptItemCategorizationRepository`
- `ReceiptItemCategorizationDao`
- `ReceiptScanViewModel`
- `ScannedReceiptDao`

### Change

Coordinator/side-effect dispatcher decides when item categorization is allowed.

Allowed only when:

- document type supports line items
- parsed items are non-empty
- receipt is not OCR failed
- receipt is not bank statement

### Fix

- update receipt categorization status transactionally with categorization rows
- avoid status `READY` with no rows
- avoid deleting old categorizations before replacement is ready
- add event for categorization started/completed/failed

### Optional late improvement

Introduce normalized `receipt_line_items` table.

If not doing that now, keep JSON but isolate parsing/serialization behind one adapter.

### Tests

- no categorization for bank statement
- no categorization for OCR failure
- successful categorization writes rows and status together
- failed categorization sets status correctly
- user correction updates row and timestamp

### Done when

- item categorization lifecycle is deterministic.

---

## PR 15 — Receipt duplicate detection

### Goal

Add real scanned-receipt deduplication.

### Add

`ReceiptDuplicateDetector`

Initial implementation can support:

- exact asset hash
- normalized text fingerprint
- semantic fingerprint

Later implementation can add perceptual image hash.

### Add columns/indexes

- image hash index
- text fingerprint index
- semantic fingerprint index
- source fingerprint index

### Behavior

- camera/gallery: warn or return duplicate result
- batch: skip strong duplicates
- email: skip message/fingerprint duplicates
- statement: skip duplicate document

### Tests

- same image file detected
- same OCR text detected
- same semantic receipt detected
- different purchases at same merchant/same day not falsely blocked if details differ
- batch duplicate counted in result

### Done when

- scanning the same receipt twice is detected before creating duplicate records.

---

## PR 16 — Receipt deletion and asset cleanup

### Goal

Make deletion safe and traceable.

### Add

`deleteReceiptWithCascadingCleanup`

Behavior:

1. load receipt
2. write delete event
3. delete or unlink dependent side effects according to existing FK behavior
4. delete database row
5. delete image asset if no other receipt references it
6. write asset deletion event

### Add orphan cleanup

Create maintenance method/worker:

- find files in `filesDir/receipts`
- compare to referenced image paths
- delete old unreferenced files
- keep recent temp files for grace period

### Tests

- receipt delete removes image file
- email receipt delete does not require image
- linked expense is not deleted
- orphan cleanup removes unused file
- referenced file is preserved

### Done when

- receipt files do not accumulate unmanaged forever.

---

## PR 17 — Backup and export integration

### Goal

Fix the current database-only backup gap.

### Backup target

Move from database-only backup to archive backup:

- database file
- receipt image files
- receipt asset manifest
- backup metadata

### Manifest should include

- receipt ID
- image path
- file hash
- size
- MIME type
- created at
- backup path

### Restore

- restore database
- restore receipt files
- verify hash if possible
- mark missing assets if file restore fails

### User-facing export

Add optional receipt export mode:

- receipt metadata CSV
- OCR text export if user chooses
- images zipped
- maybe accountant report attachment index

Do not include raw OCR text by default if privacy is a concern.

### Tests

- backup includes receipt image
- restore restores image
- missing asset is detected
- email receipt backup works without image
- database-only legacy backup still importable if supported

### Done when

- receipt images are not silently lost during backup/restore.

---

## PR 18 — Direct DAO guardrails and cleanup

### Goal

Prevent regressions.

### Approved direct `ScannedReceiptDao` access

Only:

- `ReceiptRepository`
- `ReceiptLinkService`
- migrations/tests
- maybe `ReceiptLifecycleCoordinator` only if you skip repository abstraction, but repository is preferred

Forbidden production direct access from:

- `EmailReceiptIngestionService`
- `WarrantyTrackerRepository`
- `PriceProtectionTracker`
- `ReviewQueueRepository`
- UI/ViewModels
- workers

### Add scan checks for

- `scannedReceiptDao.insert`
- `scannedReceiptDao.update`
- `scannedReceiptDao.linkToExpense`
- direct `ScannedReceiptDao` injection
- `"Scan Failed:"` used as state instead of status
- `"Bank Statement"` magic string for document type
- hardcoded `"EUR"` in receipt lifecycle creation
- direct expense creation from receipt repository
- warranty extraction on bank statements

### Done when

- audit check passes.
- direct DAO leaks are gone.

---

# 12. Final Target by Entry Path

## Camera

Final state:

- UI obtains URI.
- Coordinator validates, dedups, OCRs, parses, saves.
- User reviews.
- Expense creation delegates to transaction lifecycle.
- Receipt links through link service.

## Gallery/file import

Same as camera, with source type `GALLERY` or `FILE_IMPORT`.

## Batch scan

- Coordinator processes each file.
- Strong duplicates skipped.
- Pending reviews created only where configured.
- Batch result summarizes success/failure/duplicates.

## Bank statement

- Saved as `BANK_STATEMENT` document.
- Creates many pending transaction candidates.
- Does not run warranty/price/item categorization.
- Uses link table for approved transactions.

## Email receipt

- Saved as `EMAIL_RECEIPT`.
- Message ID/fingerprint dedup.
- No image required.
- Expense creation goes through transaction lifecycle if enabled.
- Downstream features run only if compatible.

## Manual placeholder

- Explicit document type/status.
- No fake success state.
- No automatic warranty/price/categorization.

---

# 13. Test Strategy

## 13.1 Unit tests

Add tests for:

- receipt input validation
- duplicate detection
- lifecycle state transitions
- receipt link policies
- bank statement exclusion from downstream effects
- email receipt no-image behavior
- asset deletion
- event writing

## 13.2 Integration tests

Add tests for:

- camera/gallery successful scan
- OCR failure fallback
- parse failure fallback
- receipt save to expense
- review approval link
- bank statement multi-review flow
- email receipt flow
- matching worker flow
- warranty extraction gating
- price protection gating
- backup/restore with image files

## 13.3 Regression tests from audit

Must cover:

1. Same receipt scanned twice is detected.
2. Receipt already linked cannot be overwritten accidentally.
3. Bank statement does not trigger warranty extraction.
4. Bank statement can link to multiple approved expenses.
5. Email receipt with `imagePath = null` does not crash downstream features.
6. Review approval no longer calls `ScannedReceiptDao` directly.
7. Warranty repository no longer creates placeholder receipts directly.
8. Price protection no longer injects `ScannedReceiptDao`.
9. OCR failure is explicit status, not only fake raw text.
10. Receipt images are included in backup or clearly reported missing.
11. Receipt deletion removes image asset.
12. Item categorization status matches actual categorization rows.

---

# 14. Acceptance Criteria

Phase 4 is complete when:

1. All receipt entry paths go through `ReceiptLifecycleCoordinator`.
2. `ReceiptRepository` is no longer a god orchestrator.
3. Receipt source type, document type, and processing status are explicit.
4. Receipt lifecycle events are written for major transitions.
5. Receipt-expense linking uses `ReceiptLinkService`.
6. Receipt-to-expense many-link cases are supported through a link table.
7. Bank statements no longer behave like normal retail receipts.
8. Email receipts work without image assumptions.
9. Scanned receipt duplicate detection exists.
10. Direct `ScannedReceiptDao` access outside approved classes is removed.
11. Warranty, return window, price protection, and item categorization are document-type-aware.
12. Receipt image deletion and orphan cleanup exist.
13. Receipt images are included in backup/export strategy or explicitly reported.
14. Tests cover all receipt paths and known audit regressions.
15. Guardrails prevent new direct DAO leaks.

---

# 15. Recommended Implementation Order Summary

Recommended order:

1. Baseline and audit guard.
2. Add lifecycle schema: source type, document type, status, events, link table.
3. Thin `ReceiptRepository`.
4. Add `ReceiptLinkService`.
5. Add `ReceiptLifecycleCoordinator` skeleton.
6. Add asset store and input validator.
7. Migrate camera/gallery/file processing.
8. Migrate receipt save to expense via transaction lifecycle.
9. Migrate review approval receipt linking.
10. Extract bank statement lifecycle.
11. Migrate email ingestion.
12. Migrate receipt matching.
13. Gate warranty/return windows by document type.
14. Gate price protection by document type.
15. Make item categorization status/data consistent.
16. Add receipt duplicate detection.
17. Add deletion/asset cleanup.
18. Add backup/export support for receipt assets.
19. Add direct DAO guardrails.
20. Update docs and close audit rows only after tests pass.

This order keeps the risky behavior changes behind schema/state/linking foundations first, then migrates each entry path one by one.