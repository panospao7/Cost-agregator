# PR 4 — Receipt / Email Integration

## Assumptions
PR1–PR3 are already merged:
- `entity_source_links` exists.
- `SourceLinkWriter` is available.
- `TransactionLifecycleCoordinator` writes expense source links.
- Pending-review promotion exists.
- `ReceiptExpenseLink` remains the functional receipt↔expense relation.

## Goal
Wire receipt and email flows into the new provenance layer without changing the core receipt lifecycle contract.

## Non-goals
- No bank-statement provenance work.
- No export/import work.
- No backup/restore work.
- No new schema migration.
- No removal of `ReceiptExpenseLink` or `ScannedReceipt.expenseId`.
- No refactor of OCR/parser internals.

---

## Current baseline
At `6fee004aa141878820db9240d751ea22f20c4a52`:
- `ReceiptLifecycleCoordinator` already owns receipt orchestration.
- `EmailReceiptIngestionService` already hashes `messageId` and delegates to the coordinator.
- `ReceiptLifecycleCoordinator.processEmailReceipt()` already:
  - saves `ScannedReceipt`
  - saves `EmailReceiptSource`
  - can create an expense
  - links receipt→expense through `ReceiptLinkService`
- `ReceiptLinkService` is the single functional link owner.
- `ReceiptRepository.processReceipt()` can still create receipt-driven `PendingReview` rows.

PR4 should add provenance wiring, not a new lifecycle model.

---

## Recommended architecture
Keep these rules:

1. **TransactionLifecycleCoordinator** owns expense provenance.
2. **ReceiptLinkService** owns receipt↔expense functional links.
3. **ReceiptLifecycleCoordinator** owns receipt/email document provenance.
4. **EmailReceiptIngestionService** stays a thin parse/adapter layer.
5. `ReceiptLinkService` may optionally write source links, but not when the coordinator already did.

---

## Files to modify

### Core
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`

### Likely new helper
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSourceLinkPayloadFactory.kt`

### Tests
- `ReceiptLifecycleCoordinatorTest.kt`
- `ReceiptLinkServiceTest.kt`
- `ReceiptRepositoryTest.kt`
- `EmailReceiptIngestionServiceTest.kt`

---

## 1. Add a small receipt provenance helper
Create `ReceiptSourceLinkPayloadFactory` to standardize receipt/email provenance payloads.

### It should build:
- `EMAIL_RECEIPT_SOURCE -> SCANNED_RECEIPT`
- `SCANNED_RECEIPT -> EXPENSE`
- duplicate-match payloads for resolved duplicates

### Safe fields only:
- `receiptId`
- `expenseId`
- `emailReceiptSourceId`
- `provider`
- `matchType`
- `confidence`
- `messageIdHash`
- `contentFingerprintHash`

### Never include:
- raw email subject/body/sender
- raw message IDs
- raw OCR text
- raw image paths

---

## 2. Receipt scan flow
For receipt-scanned flows:

### 2.1 Keep current receipt save behavior
Do not change:
- OCR
- parsing
- duplicate detection
- `ScannedReceipt` persistence
- `ReceiptEvent` lifecycle logic

### 2.2 Pending review creation from receipts
When `ReceiptRepository.processReceipt()` creates a `PendingReview`:
- call the PR3 pending-review provenance service
- write a source link from `SCANNED_RECEIPT` to `PENDING_REVIEW`
- use `REVIEWED_FROM`
- keep it idempotent

This covers receipt-created reviews, not only notification-created reviews.

### 2.3 Receipt → expense creation
When a scanned receipt becomes an expense:
- PR2 already writes the expense source link via `scannedReceiptId`
- `ReceiptLinkService.linkReceiptToExpense()` should still write the functional `ReceiptExpenseLink`
- but for this create path, disable duplicate provenance writing from the link service if the coordinator already wrote it

Recommended addition:
```kotlin
writeSourceLink: Boolean = true
```
on `ReceiptLinkService.linkReceiptToExpense(...)`.

For `createExpenseFromReceipt()`, pass `writeSourceLink = false`.

---

## 3. Receipt link service
Extend `ReceiptLinkService` carefully.

### Responsibilities remain:
- insert `ReceiptExpenseLink`
- update legacy `ScannedReceipt.expenseId` for non-bank receipts
- write `ReceiptEvent`
- preserve relink semantics

### Add provenance support:
- if `writeSourceLink = true`, write the source-link row for the receipt/expense relationship
- if `writeSourceLink = false`, skip provenance write because the coordinator already handled it

### Suggested provenance behavior
- direct save / approval: `CREATED_FROM`
- manual/existing receipt link: `LINKED_PROOF`
- relink: insert a new current provenance row, keep historical rows
- duplicate match: `DUPLICATE_MATCHED`

### Important
Do not make `ReceiptLinkService` depend on `TransactionLifecycleCoordinator`.
That would create a cycle. It should depend only on `SourceLinkWriter` and local receipt data.

---

## 4. Email receipt save flow
This is the main PR4 integration point.

### 4.1 Preserve the current adapter boundary
`EmailReceiptIngestionService` should remain parse-only and delegate all mutation to `ReceiptLifecycleCoordinator`.

### 4.2 Keep hashing and privacy rules
Continue to pass:
- hashed `messageId`
- sanitized sender/subject/body
- content fingerprint
- provider name

Never let raw email identifiers enter source-link metadata.

### 4.3 Save an email→receipt provenance row
Inside `ReceiptLifecycleCoordinator.processEmailReceipt()`:
- after inserting `EmailReceiptSource`
- write a source link:
  - target = `SCANNED_RECEIPT`
  - source = `EMAIL_RECEIPT_SOURCE`
  - role = `CREATED_FROM`
  - status = `ACTIVE`
  - isPrimary = true

This captures:
```text
email source -> receipt
```

### 4.4 Create expense from email receipt
When enough parsed data exists:
- pass `scannedReceiptId`
- pass `emailReceiptSourceId`
- pass `correlationId`

That lets PR2 write direct expense provenance for both source objects.

### 4.5 Link receipt to expense
After expense creation or duplicate resolution:
- call `ReceiptLinkService.linkReceiptToExpense(...)`
- set `writeSourceLink = false`
- keep the functional receipt link and legacy `expenseId` update

This avoids duplicate provenance rows.

---

## 5. Duplicate handling
PR4 should handle duplicates safely, but not noisily.

### Receipt duplicates
If receipt duplicate detection returns an existing receipt:
- do not create a new receipt row
- do not create a phantom source link
- if an existing expense can be resolved from the existing receipt, optionally attach a `DUPLICATE_MATCHED` source link to that existing target
- otherwise emit a diagnostic event only

### Email duplicates
If email duplicate detection hits:
- do not create a new `ScannedReceipt` / `EmailReceiptSource`
- if the duplicate maps to an existing receipt or expense, attach duplicate provenance to the existing target
- use hashed identity keys only
- never store raw message IDs or raw email text in metadata

### Source identity for duplicate attempts
Use external hashed identity keys such as:
- `external:email_message:<hash>`
- `external:email_fingerprint:<hash>`
- `external:receipt_hash:<hash>`

---

## 6. Pending-review from receipt flow
For receipt-generated reviews:
- write the review provenance link at creation time
- use the receipt source as the provenance anchor
- the later PR3 approval promotion should move/copy that provenance to the expense

This is important because PR3 only covered promotion logic; PR4 must cover receipt-originated review creation.

---

## 7. Privacy / metadata rules
All provenance metadata must be safe.

Allowed:
- local IDs
- source type
- document type
- provider
- confidence
- hashed message IDs
- hashed fingerprints
- link type
- duplicate reason
- correlation ID

Blocked:
- raw sender/subject/body
- raw OCR text
- raw email message IDs
- raw file paths
- bank account/card/IBAN values

Use the existing safe metadata pattern; do not invent a second privacy format.

---

## 8. Tests

### Receipt scan tests
- receipt save still works
- receipt-created review gets source link
- receipt-to-expense create writes source link via coordinator
- receipt link service writes functional link
- receipt relink keeps provenance history

### Email tests
- email receipt save writes `EMAIL_RECEIPT_SOURCE -> SCANNED_RECEIPT`
- email receipt expense creation writes both source IDs
- email link-to-expense uses functional link service
- email duplicate path does not create phantom provenance
- no raw message ID appears in metadata

### Integration tests
- created expense + source link + receipt link are atomic
- source-link failure rolls back create flow
- receipt link provenance is idempotent
- coordinator-written provenance is not duplicated by `ReceiptLinkService`

---

## 9. Recommended execution order
1. Add `ReceiptSourceLinkPayloadFactory`.
2. Extend `ReceiptLinkService` with provenance support and `writeSourceLink`.
3. Wire `ReceiptLifecycleCoordinator.processEmailReceipt()`.
4. Pass `emailReceiptSourceId` through email expense creation.
5. Wire receipt-created `PendingReview` provenance in `ReceiptRepository`.
6. Update tests.
7. Verify no raw email/receipt text leaks into metadata.

---

## 10. Acceptance criteria
PR4 is done when:
- scanned receipts can be traced to expenses with durable source links
- receipt-created reviews also get provenance
- email receipts create both receipt and expense provenance
- `ReceiptExpenseLink` still functions as before
- duplicate receipt/email paths are safe and privacy-preserving
- no raw email or OCR data leaks into provenance metadata

---

## Sources
- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52
- `ReceiptLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptLinkService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `ReceiptRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `EmailReceiptSource.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt
- `ReceiptExpenseLink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt
- Phase 4 receipt lifecycle plan:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/phase4-receipt-lifecycle-implementation-plan.md