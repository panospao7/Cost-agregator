# Phase 4 — Receipt Lifecycle Foundation: Final Implementation Plan

> **Decision**: Option B — Improved plan based on the template but with merged PRs, corrected design choices, and sharper scope.
>
> **Generated**: 2026-05-01
> **Sources**: receipt-lifecycle-audit.md, phase4-receipt-lifecycle-implementation-plan.md, live codebase inspection
> **Schema version at baseline**: 95 (`APP_DATABASE_SCHEMA_VERSION`)

---

## 0. Key Design Decisions (vs Template Plan)

| Topic | Template Plan | Final Decision | Rationale |
|---|---|---|---|
| PR count | 19 PRs | **9-10 PRs** | Many PRs are tightly coupled and should be merged to avoid merge-hell and partial-state bugs |
| Receipt events table | Separate `receipt_lifecycle_events` | **Separate table** (confirmed) | Mixing receipt events into `TransactionEvent` would create a polymorphic table (receiptId OR expenseId non-null) — known anti-pattern. Keep parallel but separate. |
| Link table | New `receipt_expense_links` table | **Confirmed** | Current `ScannedReceipt.expenseId` is a single FK that gets silently overwritten. Bank statements need many-to-many. Link table is the canonical source. |
| Legacy `expenseId` | Keep temporarily | **Keep permanently for single-link retail receipts** | Simplifies queries for the 90% case. Link table used for bank statements, audit trail, and multi-link. Update both in sync for single-link. |
| Duplicate detection | Full perceptual hash in Phase 4 | **Exact hash + text fingerprint + semantic fingerprint now. Perceptual hash deferred.** | Adding image processing library integration is too much scope for Phase 4. The basic signals already catch 80%+ of duplicates. |
| Coordinator dependencies | Inject `WarrantyUseCase`, `CategorizeUseCase`, etc. directly | **Inject `ReceiptSideEffectDispatcher` instead** | The coordinator should not know about every downstream feature. A dispatcher that routes to the right effects based on document type keeps the coordinator focused. |
| `ReceiptRepository` fate | Thin it down but keep | **Keep as compatibility facade during migration, target: thin data gateway** | The repository has 930 lines and 20+ methods. Safe migration means keeping it as a forwarding layer until all callers are moved to the coordinator, then mark old methods deprecated. |

---

## 1. Dependency on Phase 3 (Transaction Lifecycle)

Phase 3 delivered `TransactionLifecycleCoordinator` (276 lines), `TransactionEvent` entity, `TransactionSideEffectDispatcher`, and the `CreateExpenseRequest`/`CreateExpenseResult` contract. The schema is at version 95.

### Boundary Contract

```
ReceiptLifecycleCoordinator           TransactionLifecycleCoordinator
         │                                       │
         │  ┌──────────────────────┐             │
         └──►  ReceiptLinkService  ◄─────────────┘
            │  (shared dependency)  │
            └──────────────────────┘
```

- **ReceiptLifecycleCoordinator** owns: OCR, parse, save receipt, duplicate detection, image/asset lifecycle, receipt events
- **TransactionLifecycleCoordinator** owns: expense validation, dedup, insert, expense events, expense-side effects
- **ReceiptLinkService** owns: linking/unlinking receipts to expenses, link policies, link events
- **Neither coordinator injects the other** — they share `ReceiptLinkService` only

### What Already Exists

The `EmailReceiptIngestionService` already injects `TransactionLifecycleCoordinator` and delegates expense creation to it. This pattern is correct and will be replicated for all receipt → expense paths.

---

## 2. Schema Changes (Single Migration: 95 → 96)

All schema additions in one migration to minimize table rebuilds.

### 2a. New Columns on `scanned_receipts`

| Column | Type | Default | Purpose |
|---|---|---|---|
| `sourceType` | TEXT | `'UNKNOWN'` | CAMERA, GALLERY, FILE_IMPORT, EMAIL, BANK_STATEMENT, etc. |
| `documentType` | TEXT | `'UNKNOWN'` | RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT |
| `processingStatus` | TEXT | `'CAPTURED'` | OCR_PENDING, OCR_RUNNING, OCR_COMPLETED, OCR_FAILED, PARSED, etc. |
| `sourceFingerprint` | TEXT | NULL | External source ID hash (email messageId, bank statement hash) |
| `imageHash` | TEXT | NULL | SHA-256 of the image/PDF file |
| `textFingerprint` | TEXT | NULL | Normalized OCR text hash |
| `semanticFingerprint` | TEXT | NULL | merchant+amount+date+currency digest |
| `ocrConfidence` | REAL | NULL | Average block confidence from ML Kit |
| `parseFailureReason` | TEXT | NULL | Why parsing failed (empty text, no amount found, etc.) |
| `updatedAt` | INTEGER | 0 | Last modification timestamp |

**Note**: The `imagePath` column is already nullable since migration 65→66 (email receipt support). No change needed.

### 2b. New Table: `receipt_expense_links`

```sql
CREATE TABLE IF NOT EXISTS receipt_expense_links (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    receiptId INTEGER NOT NULL,
    expenseId INTEGER NOT NULL,
    linkType TEXT NOT NULL,        -- DIRECT_SAVE, REVIEW_APPROVAL, AUTO_MATCH, MANUAL_MATCH, EMAIL_AUTO_CREATE, BANK_STATEMENT_TX
    confidence REAL,               -- match confidence if applicable
    source TEXT NOT NULL,          -- which component created this link
    isPrimary INTEGER NOT NULL DEFAULT 0,  -- primary link for backward compat
    metadata TEXT,                 -- JSON for extra context
    createdAt INTEGER NOT NULL,
    FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
    FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_receipt_expense_links_receipt_expense 
    ON receipt_expense_links (receiptId, expenseId);
CREATE INDEX IF NOT EXISTS idx_receipt_expense_links_expenseId 
    ON receipt_expense_links (expenseId);
CREATE INDEX IF NOT EXISTS idx_receipt_expense_links_linkType 
    ON receipt_expense_links (linkType);
CREATE INDEX IF NOT EXISTS idx_receipt_expense_links_createdAt 
    ON receipt_expense_links (createdAt);
```

### 2c. New Table: `receipt_lifecycle_events`

```sql
CREATE TABLE IF NOT EXISTS receipt_lifecycle_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    receiptId INTEGER,              -- nullable for pre-save validation failures
    eventType TEXT NOT NULL,        -- RECEIPT_CAPTURED, OCR_STARTED, OCR_COMPLETED, OCR_FAILED, PARSE_COMPLETED, PARSE_FAILED, RECEIPT_SAVED, RECEIPT_LINKED_TO_EXPENSE, RECEIPT_UNLINKED, DUPLICATE_SKIPPED, RECEIPT_DELETED, ASSET_DELETED, etc.
    sourceType TEXT,                -- CAMERA, GALLERY, EMAIL, etc.
    documentType TEXT,              -- RETAIL_RECEIPT, BANK_STATEMENT, etc.
    oldStatus TEXT,                 -- processing status before transition
    newStatus TEXT,                 -- processing status after transition
    actor TEXT,                     -- "user", "system:batch", "system:email"
    occurredAt INTEGER NOT NULL,
    message TEXT,                   -- human-readable description
    metadata TEXT,                  -- JSON: error details, file hash, etc.
    FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_receipt_lifecycle_events_receiptId 
    ON receipt_lifecycle_events (receiptId);
CREATE INDEX IF NOT EXISTS idx_receipt_lifecycle_events_eventType 
    ON receipt_lifecycle_events (eventType);
CREATE INDEX IF NOT EXISTS idx_receipt_lifecycle_events_occurredAt 
    ON receipt_lifecycle_events (occurredAt);
```

### 2d. New Indices on `scanned_receipts`

```sql
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_sourceFingerprint ON scanned_receipts (sourceFingerprint);
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_imageHash ON scanned_receipts (imageHash);
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_textFingerprint ON scanned_receipts (textFingerprint);
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_semanticFingerprint ON scanned_receipts (semanticFingerprint);
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_documentType ON scanned_receipts (documentType);
CREATE INDEX IF NOT EXISTS idx_scanned_receipts_processingStatus ON scanned_receipts (processingStatus);
```

### 2e. Backfill Strategy (runs in migration)

```sql
-- Document type backfill
UPDATE scanned_receipts 
SET documentType = CASE
    WHEN id IN (SELECT receiptId FROM email_receipt_sources) THEN 'EMAIL_RECEIPT'
    WHEN parsedMerchant = 'Bank Statement' THEN 'BANK_STATEMENT'
    WHEN rawOcrText LIKE 'Scan Failed:%' OR rawOcrText LIKE '[OCR Failed%' THEN 'MANUAL_PLACEHOLDER'
    WHEN imagePath IS NOT NULL THEN 'RETAIL_RECEIPT'
    ELSE 'UNKNOWN'
END;

-- Source type backfill
UPDATE scanned_receipts 
SET sourceType = CASE
    WHEN id IN (SELECT receiptId FROM email_receipt_sources) THEN 'EMAIL'
    WHEN parsedMerchant = 'Bank Statement' THEN 'BANK_STATEMENT'
    WHEN rawOcrText LIKE 'Scan Failed:%' OR rawOcrText LIKE '[OCR Failed%' THEN 'MANUAL_RECORD'
    ELSE 'GALLERY'
END;

-- Processing status backfill
UPDATE scanned_receipts 
SET processingStatus = CASE
    WHEN rawOcrText LIKE 'Scan Failed:%' OR rawOcrText LIKE '[OCR Failed%' THEN 'OCR_FAILED'
    WHEN parsedMerchant IS NOT NULL AND parsedTotal IS NOT NULL THEN 'PARSED'
    WHEN rawOcrText != '' AND rawOcrText NOT LIKE '[OCR%' AND rawOcrText NOT LIKE 'Scan Failed%' THEN 'OCR_COMPLETED'
    ELSE 'CAPTURED'
END;

-- Link table backfill from legacy expenseId
INSERT INTO receipt_expense_links (receiptId, expenseId, linkType, confidence, source, isPrimary, createdAt)
SELECT id, expenseId,
    CASE matchStatus 
        WHEN 'AUTO_MATCHED' THEN 'AUTO_MATCH'
        WHEN 'MANUALLY_MATCHED' THEN 'MANUAL_MATCH'
        ELSE 'DIRECT_SAVE'
    END,
    matchConfidence,
    'MIGRATION_95_96',
    1,
    COALESCE(createdAt, 0)
FROM scanned_receipts
WHERE expenseId IS NOT NULL;
```

---

## 3. Target Component Architecture

### New Components

| Component | File (create) | Responsibility |
|---|---|---|
| `ReceiptLifecycleCoordinator` | `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | Single entry point for ALL receipt processing |
| `ReceiptLinkService` | `domain/receipt/lifecycle/ReceiptLinkService.kt` | Link/unlink receipts to expenses safely |
| `ReceiptAssetStore` | `data/receipt/ReceiptAssetStore.kt` | File persistence, hash, cleanup, backup manifest |
| `ReceiptDuplicateDetector` | `domain/receipt/ReceiptDuplicateDetector.kt` | Multi-signal receipt deduplication |
| `ReceiptSideEffectDispatcher` | `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | Route post-save effects by document type |
| `BankStatementLifecycleProcessor` | `domain/receipt/statement/BankStatementLifecycleProcessor.kt` | Bank statement-specific flow |
| `EmailReceiptLifecycleProcessor` | `domain/receipt/email/EmailReceiptLifecycleProcessor.kt` | Email receipt-specific flow |
| `ReceiptInputValidator` | `domain/receipt/ReceiptInputValidator.kt` | MIME/size/image validation before OCR |
| `ReceiptLifecycleEventDao` | `data/database/dao/ReceiptLifecycleEventDao.kt` | DAO for `receipt_lifecycle_events` |
| `ReceiptExpenseLinkDao` | `data/database/dao/ReceiptExpenseLinkDao.kt` | DAO for `receipt_expense_links` |

### Modified Components

| Component | File (modify) | Change |
|---|---|---|
| `ReceiptRepository` | `data/repository/ReceiptRepository.kt` | Strip orchestrator logic; keep as thin data gateway + compatibility facade |
| `EmailReceiptIngestionService` | `data/email/EmailReceiptIngestionService.kt` | Remove direct `ScannedReceiptDao` access; route through coordinator |
| `WarrantyTrackerRepository` | `data/repository/WarrantyTrackerRepository.kt` | Remove direct `ScannedReceiptDao` access; receive receipt data from coordinator |
| `PriceProtectionTracker` | `domain/price/PriceProtectionTracker.kt` | Remove `ScannedReceiptDao` injection; query through repository method |
| `ReviewQueueRepository` | `data/repository/ReviewQueueRepository.kt` | Remove `scannedReceiptDao.linkToExpense()` call; use `ReceiptLinkService` |
| `ReceiptScanViewModel` | `ui/screens/receiptscan/ReceiptScanViewModel.kt` | Call coordinator instead of direct repository orchestration |
| `AutoCreateWarrantyFromReceiptUseCase` | `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` | Gate by document type |
| `ReceiptMatchingWorker` | `service/receiptmatching/ReceiptMatchingWorker.kt` | Use `ReceiptLinkService` instead of direct DAO/link methods |
| `ScannedReceipt` entity | `data/database/entity/ScannedReceipt.kt` | Add new columns |
| `ScannedReceiptDao` | `data/database/dao/ScannedReceiptDao.kt` | Add query methods for new columns/fingerprints |
| `AppDatabase` | `data/database/AppDatabase.kt` | Add new entities, DAOs, migration 95→96 |
| `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` | Include receipt images in backup |

---

## 4. PR Implementation Plan (9 PRs)

### PR 1: Schema Foundation + Data Gateway Cleanup

**Merge of**: Template PR 0 (baseline) + PR 1 (schema) + PR 2 (repository cleanup)

**Scope**:
- Add migration 95→96: all new columns, `receipt_expense_links`, `receipt_lifecycle_events`, new indices, backfill
- Add `ScannedReceipt` entity columns (`sourceType`, `documentType`, `processingStatus`, fingerprints, `ocrConfidence`, `parseFailureReason`, `updatedAt`)
- Add new entities: `ReceiptExpenseLink`, `ReceiptLifecycleEvent`
- Add DAOs: `ReceiptExpenseLinkDao`, `ReceiptLifecycleEventDao`
- Update `ScannedReceiptDao` with new query methods (`getBySourceFingerprint`, `getByImageHash`, `getByTextFingerprint`, `getBySemanticFingerprint`, `getByDocumentType`, `getRecentReceipts` with document type filter)
- Update `AppDatabase` to register new entities, DAOs, migration
- Slim `ReceiptRepository`: extract named data-access methods, deprecate orchestrator methods, add event-writing helper
- Add compile-time grep guard in `DaoModule.kt` to document allowed DAO injection points

**DO NOT**: Change any behavior. All existing flows must work identically.

**Tests**:
- Room migration 94→95→96 roundtrip test
- Backfill correctness: documentType, sourceType, processingStatus for all receipt variants
- Link table backfill from legacy expenseId
- New DAO CRUD tests
- Existing integration tests pass with new schema

**Acceptance criteria**:
- [ ] Schema supports explicit lifecycle state
- [ ] Existing receipts backfilled correctly
- [ ] App compiles and all existing tests pass
- [ ] `ReceiptRepository` has clear data-access methods separated from orchestrator methods

---

### PR 2: ReceiptLinkService

**Scope**:
- Create `ReceiptLinkService` (`domain/receipt/lifecycle/ReceiptLinkService.kt`)
- Link policies: `FAIL_IF_ALREADY_LINKED`, `ALLOW_MULTIPLE_FOR_STATEMENT`, `REPLACE_EXISTING_EXPLICIT`, `LEGACY_COMPAT_SINGLE_PRIMARY`
- Methods: `linkReceiptToExpense()`, `unlinkReceipt()`, `approveMatchSuggestion()`, `rejectMatchSuggestion()`, `clearMatch()`, `getLinksForReceipt()`, `getLinksForExpense()`
- Writes to both `receipt_expense_links` (canonical) and `scanned_receipts.expenseId` (legacy compat for single-link)
- Writes `RECEIPT_LINKED_TO_EXPENSE` / `RECEIPT_UNLINKED_FROM_EXPENSE` lifecycle events
- Replace: `ScannedReceiptDao.linkToExpense()` (DAО-level), `ReceiptRepository.linkReceiptToExpense()` (manual load-copy-update)

**Tests**:
- Link normal receipt once → success
- Second link to same receipt fails (FAIL_IF_ALREADY_LINKED policy)
- Explicit relink with REPLACE_EXISTING works
- Bank statement can link multiple expenses (ALLOW_MULTIPLE_FOR_STATEMENT)
- Unlink clears match state and `expenseId`
- Legacy `expenseId` stays compatible for single-link retail receipts
- Lifecycle event written on link/unlink
- Transaction rollback: failed link writes no event, no partial data

**Acceptance criteria**:
- [ ] Linking is safe, centralized, and policy-driven
- [ ] No accidental overwrite of existing links
- [ ] All 4 replaced call sites use `ReceiptLinkService`

---

### PR 3: Asset Store + Input Validator + Coordinator Skeleton

**Merge of**: Template PR 4 (coordinator skeleton) + PR 5 (asset store + input validator)

**Scope**:
- Create `ReceiptAssetStore`:
  - `createTempCameraUri()` — moved from `ReceiptOcrService`
  - `persistCopy(uri)` — save compressed JPEG, return persisted path + hash
  - `computeFileHash(path)` — SHA-256
  - `deleteAsset(path)` — delete file
  - `listOrphanAssets()` — find files in `filesDir/receipts/` not referenced by any receipt
  - `getBackupManifest()` — for PR 8
- Create `ReceiptInputValidator`:
  - Validate URI readable, MIME type, file size ≤ 20MB, image decodable, PDF page count ≤ 5
  - Return structured validation result
- Create `ReceiptLifecycleCoordinator` skeleton:
  - Inject: `ReceiptRepository`, `ReceiptOcrService`, `ReceiptParser`, `ReceiptAssetStore`, `ReceiptInputValidator`, `ReceiptLinkService`, `TimeProvider`, `ReceiptLifecycleEventDao`
  - Skeleton methods: `processReceiptInput(uri, sourceType)`, `processBatch(uris)`, `processBankStatement(uri)`, `processEmailReceipt(data)`, `saveManualReceiptRecord(uri)`, `deleteReceiptWithCascadingCleanup(id)`, `getLifecycleState(id)`
  - Methods delegate to existing repository flows initially (no behavior change)
  - Write lifecycle events at key checkpoints
- Update DI module (`DaoModule.kt` / new `ReceiptLifecycleModule.kt`)

**DO NOT**: Migrate any existing caller to use the coordinator yet.

**Tests**:
- Asset store: persist, hash, delete roundtrip
- Input validator: rejects invalid URI, oversized file, unsupported MIME, corrupt image
- Coordinator skeleton: compiles, delegates correctly, writes events
- Existing flows (ReceiptScanViewModel, EmailReceiptIngestionService) still work

**Acceptance criteria**:
- [ ] `ReceiptAssetStore` centralizes file operations
- [ ] `ReceiptInputValidator` blocks bad input before OCR
- [ ] `ReceiptLifecycleCoordinator` skeleton compiles and delegates unchanged behavior
- [ ] No regressions in existing scan/email/statement flows

---

### PR 4: Camera/Gallery/File Scan Migration

**Merge of**: Template PR 6 (camera/gallery migration) + PR 7 (receipt save to expense migration)

**Scope**:
- Migrate `ReceiptScanViewModel.processImageUri()` to call `ReceiptLifecycleCoordinator.processReceiptInput()`
- Coordinator orchestrates: validate → persist asset → hash → OCR → parse → save receipt → write events
- OCR failure: explicit `processingStatus = OCR_FAILED`, `parseFailureReason` set, NO fake placeholder text reliance
- Parse failure: explicit `processingStatus = PARSE_FAILED`
- Manual fallback: `processingStatus = CAPTURED`, `documentType = MANUAL_PLACEHOLDER`
- Migrate receipt save path: `ReceiptScanViewModel.saveExpenseInternal()` calls `TransactionLifecycleCoordinator.createExpense()` instead of `ReceiptRepository.createExpenseFromReceipt()`
- Link expense to receipt through `ReceiptLinkService`
- Remove `ReceiptRepository.createExpenseFromReceipt()` (or mark @Deprecated with forwarding)
- Ensure scan step UI behavior preserved (`ScanStep.PROCESSING`, `ScanStep.REVIEW`, `ScanStep.ERROR`)

**Tests**:
- Successful camera scan reaches review with correct processing status
- OCR failure → manual placeholder with explicit status, not fake text
- Parse failure → parse-failed receipt state, not silently saved as parsed
- Gallery/file import path works
- Receipt save creates expense through `TransactionLifecycleCoordinator`
- Receipt linked through `ReceiptLinkService`, link event written
- Duplicate expense result handled (does not mark receipt as linked)
- Failed transaction creation does not mark receipt as linked
- Receipt lifecycle event sequence written (CAPTURED → OCR_STARTED → OCR_COMPLETED → PARSE_COMPLETED → RECEIPT_SAVED → RECEIPT_LINKED_TO_EXPENSE)

**Acceptance criteria**:
- [ ] Retail scan path no longer orchestrated by `ReceiptRepository`
- [ ] Receipt save does not directly create expenses in `ReceiptRepository`
- [ ] OCR/parse failure states are explicit, not hidden as placeholder text

---

### PR 5: Review Queue + Bank Statement + Email Ingestion Migration

**Merge of**: Template PR 8 (review queue) + PR 9 (bank statement) + PR 10 (email ingestion)

**Scope**:

**Review Queue** (`ReviewQueueRepository`):
- Remove `scannedReceiptDao.linkToExpense()` direct call at line 257
- When a `PendingReview` with `scannedReceiptId` is approved, use `ReceiptLinkService` to link
- Bank statement reviews → `ALLOW_MULTIPLE_FOR_STATEMENT` policy
- Normal receipts → single-link policy
- Remove `ScannedReceiptDao` injection from `ReviewQueueRepository`

**Bank Statement** (`BankStatementLifecycleProcessor`):
- Extract 214-line `processStatement()` method from `ReceiptRepository` into dedicated processor
- Save one `ScannedReceipt` as `documentType = BANK_STATEMENT`
- Parse transactions, deduplicate, create `PendingReview` candidates
- Block warranty extraction, price protection, receipt matching, item categorization on bank statements
- Use link table for all statement transaction → expense links
- Add duplicate statement document detection (exact file hash + text fingerprint)

**Email Ingestion** (`EmailReceiptLifecycleProcessor`):
- `EmailReceiptIngestionService` calls coordinator instead of direct `ScannedReceiptDao`
- Coordinator handles: messageId dedup, email fingerprint dedup, receipt creation, email source creation
- Expense creation delegates to `TransactionLifecycleCoordinator` (already done)
- Receipt link through `ReceiptLinkService`
- Remove `ScannedReceiptDao` injection from `EmailReceiptIngestionService`
- Document type = `EMAIL_RECEIPT`, source type = `EMAIL`
- Image explicitly optional; downstream features gated by document type

**Tests**:
- Review approval links receipt via `ReceiptLinkService`
- Bank statement: multiple review approvals → multiple link rows
- Bank statement: warranty extraction NOT called
- Bank statement: duplicate document detection works
- Email: same messageId → duplicate detected
- Email: receipt saved with null image path
- Email: auto-expense delegates to `TransactionLifecycleCoordinator`
- No direct `ScannedReceiptDao` access from `EmailReceiptIngestionService` or `ReviewQueueRepository`

**Acceptance criteria**:
- [ ] `ReviewQueueRepository` no longer injects `ScannedReceiptDao`
- [ ] Bank statement processing separated from `ReceiptRepository`
- [ ] Email receipt lifecycle fully coordinator-owned
- [ ] Bank statements blocked from warranty/price/matching/categorization

---

### PR 6: Warranty + Return Window + Price Protection Integration

**Merge of**: Template PR 12 (warranty/return) + PR 13 (price protection)

**Scope**:

**Warranty & Return Windows**:
- `AutoCreateWarrantyFromReceiptUseCase`: add early return for `documentType != RETAIL_RECEIPT && documentType != EMAIL_RECEIPT`
- Remove direct `ScannedReceiptDao` reads from `WarrantyTrackerRepository` (lines 152, 342)
- `WarrantyTrackerRepository.createManualPlaceholderReceipt()` → route through `ReceiptLifecycleCoordinator`
- Unify return window days policy: `defaultReturnDaysForMerchant() + PriceProtectionTracker.getReturnWindow()` → single `ReturnPolicyResolver`
- Idempotency: `persistReturnWindow()` uses upsert, not insert-then-ignore

**Price Protection**:
- Remove `ScannedReceiptDao` injection from `PriceProtectionTracker` (line 18)
- Add repository method: `ReceiptRepository.getEligibleReceiptsForPriceProtection()` that filters by `documentType` and `processingStatus`
- Filter: only `RETAIL_RECEIPT` and `EMAIL_RECEIPT` with parsed items, within 30-day window
- Exclude: `BANK_STATEMENT`, `MANUAL_PLACEHOLDER`, OCR-failed receipts
- Clearly mark all results as simulated (already done; verify)

**Tests**:
- Retail receipt → warranty can be created
- Email receipt → warranty created only if text has product/warranty signal
- Bank statement → warranty NOT created
- Manual placeholder → warranty NOT created
- Existing warranty by receiptId → no duplicate (unique index enforces)
- Return window not duplicated (upsert semantics)
- Price protection includes only eligible document types
- Bank statements excluded from price protection
- No `ScannedReceiptDao` injection in `PriceProtectionTracker`

**Acceptance criteria**:
- [ ] Warranty/return windows are document-type-aware
- [ ] Price protection no longer bypasses receipt lifecycle
- [ ] No direct `ScannedReceiptDao` access from warranty or price modules

---

### PR 7: Receipt Matching + Item Categorization Consistency

**Merge of**: Template PR 11 (receipt matching) + PR 14 (item categorization)

**Scope**:

**Receipt Matching**:
- `ReceiptMatchingWorker`: use `ReceiptLinkService` for auto-match links instead of direct DAO
- Gate by document type: only match `RETAIL_RECEIPT`, skip `BANK_STATEMENT`, `MANUAL_PLACEHOLDER`
- Skip already-linked receipts
- Configurable lookback window (not hardcoded 7 days): add to `AppConfig` with default 7
- Remove duplicate link implementation (single path through `ReceiptLinkService`)
- Write `MATCH_SUGGESTED` / `MATCH_AUTO_APPLIED` lifecycle events

**Item Categorization**:
- `ReceiptSideEffectDispatcher` decides when categorization is allowed
- Allowed only when: `documentType` supports line items (`RETAIL_RECEIPT`, `EMAIL_RECEIPT`), `parsedItems` non-empty, not OCR-failed, not bank statement
- Update `itemCategorizationStatus` on `ScannedReceipt` transactionally with categorization rows
- Avoid `READY` status with zero rows
- Avoid deleting old categorizations before replacement is ready (upsert instead of delete-all-then-insert)
- Add events: `ITEM_CATEGORIZATION_STARTED`, `ITEM_CATEGORIZATION_COMPLETED`, `ITEM_CATEGORIZATION_FAILED`

**Tests**:
- Auto-match creates link row via `ReceiptLinkService`
- Suggested match updates `matchStatus = SUGGESTED`
- Bank statement excluded from matching
- Already-linked receipt skipped
- Configurable lookback: set to 30 days, older receipt matched; set to 1 day, same receipt skipped
- Item categorization: bank statement skipped, OCR-failure skipped
- Categorization status and rows written in same transaction
- Failed categorization sets correct status (PENDING → stays PENDING)

**Acceptance criteria**:
- [ ] Matching uses one link path through `ReceiptLinkService`
- [ ] Item categorization lifecycle is deterministic and document-type-aware

---

### PR 8: Duplicate Detection + Deletion/Cleanup + Backup Integration

**Merge of**: Template PR 15 (duplicate detection) + PR 16 (deletion/cleanup) + PR 17 (backup/export)

**Scope**:

**Duplicate Detection** (`ReceiptDuplicateDetector`):
- Multi-signal detection:
  1. External source ID (email messageId) → query `EmailReceiptDao.getByMessageId()`
  2. Exact file hash → query `ScannedReceiptDao.getByImageHash()`
  3. Normalized text fingerprint → query `ScannedReceiptDao.getByTextFingerprint()`
  4. Semantic fingerprint → query `ScannedReceiptDao.getBySemanticFingerprint()`
  5. Email fingerprint → query `EmailReceiptDao.getByFingerprint()`
- Return `DuplicateDetectionResult`: `NOT_DUPLICATE`, `EXACT_DUPLICATE(existingId)`, `LIKELY_DUPLICATE(existingId, confidence)`, `POSSIBLE_DUPLICATE(existingId, reason)`
- Policies: camera/gallery → warn user; batch → skip; email → auto-skip; statement → auto-skip
- Perceptual hash DEFERRED (requires image processing library — add TODO comment)
- Populate fingerprint columns during `processReceiptInput()` (PR 4 already has the hooks)

**Deletion & Cleanup** (`ReceiptLifecycleCoordinator.deleteReceiptWithCascadingCleanup()`):
- Load receipt → write DELETE event → delete DB row → delete image file (if exists and no other receipt references it)
- Orphan cleanup worker: find files in `filesDir/receipts/`, compare to referenced paths, delete unreferenced files older than 24h
- Email receipts: skip image deletion (no image)

**Backup Integration** (`DatabaseBackupRepositoryImpl`):
- Move from database-only backup to archive backup: DB file + receipt image files + manifest
- `ReceiptAssetStore.getBackupManifest()` provides list of receipt images with hash, size, path
- Restore: restore DB, restore images, verify hashes, mark missing assets
- User-facing export: optional receipt metadata CSV, OCR text export, images zipped
- Privacy: do NOT include raw OCR text by default

**Tests**:
- Same image file scanned twice → exact duplicate detected
- Same OCR text → text fingerprint duplicate detected
- Same semantic receipt (merchant+amount+date) → detected
- Different purchases same merchant same day with different items → NOT blocked (semantic fingerprint differ)
- Batch: duplicates counted in result, skipped
- Receipt delete removes image file
- Email receipt delete does not try to delete image
- Orphan cleanup removes unreferenced files, preserves referenced files
- Backup includes receipt images
- Restore restores images
- Database-only legacy backup still importable

**Acceptance criteria**:
- [ ] Scanning same receipt twice is detected before creating duplicate records
- [ ] Receipt files do not accumulate unmanaged forever
- [ ] Receipt images are not silently lost during backup/restore

---

### PR 9: Side Effect Dispatcher + Guardrails + Final Cleanup

**Merge of**: Template PR 18 (guardrails) + remaining template PR concepts

**Scope**:

**`ReceiptSideEffectDispatcher`**:
- Dispatches post-save or post-link effects based on document type:
  - `RETAIL_RECEIPT`: warranty extraction, return window, item categorization, receipt matching, AI assist availability, price protection candidate
  - `EMAIL_RECEIPT`: warranty (if text has product signal), return window, item categorization (if items exist), price protection (if items exist)
  - `BANK_STATEMENT`: pending review generation, debug issue detection, transaction duplicate checks (blocked: warranty, return, price, categorization, matching)
  - `MANUAL_PLACEHOLDER`: manual matching (if enough fields), AI assist (if text/image exists); blocked: auto-warranty, auto-price, auto-expense
- Coordinator calls dispatcher after save and after link

**Guardrails**:
- Enforce DAO access rules: only `ReceiptRepository`, `ReceiptLinkService`, `ReceiptLifecycleEventDao`, `ReceiptExpenseLinkDao` may inject `ScannedReceiptDao`
- Add compile-time check: grep for `ScannedReceiptDao` in non-approved packages
- Remove hardcoded `"EUR"` in new receipt creation paths; always use `currencySettingsRepository.homeCurrency() ?: "EUR"` with explicit fallback logging
- Replace magic string `"Bank Statement"` with `DocumentType.BANK_STATEMENT.name`
- Add `@Deprecated` annotations on old `ReceiptRepository` orchestrator methods
- Verify: no direct expense creation from receipt repository (all go through `TransactionLifecycleCoordinator`)
- Verify: no warranty extraction triggered on bank statements (static analysis + runtime assertion)
- Verify: OCR failure status is explicit, not only fake raw text

**Final Cleanup**:
- Remove deprecated DAO method `ScannedReceiptDao.linkToExpense()` if all callers migrated
- Remove deprecated `ReceiptRepository.linkReceiptToExpense()` and `createExpenseFromReceipt()`
- Update `processBatch()` to use coordinator path
- Update `onBatchProcessComplete()` flow

**Tests**:
- Compile-time DAO leak check passes
- Receipt creation path never hardcodes EUR
- Bank statement magic string replaced with enum
- All entry paths produce receipt lifecycle events
- Guardrail assertions don't trigger on normal operation

**Acceptance criteria**:
- [ ] All receipt entry paths go through `ReceiptLifecycleCoordinator`
- [ ] `ReceiptRepository` no longer a god orchestrator (≤ 400 lines target)
- [ ] Direct `ScannedReceiptDao` access outside approved classes is zero
- [ ] Warranty, return, price, and categorization are document-type-aware
- [ ] Guardrails prevent new direct DAO leaks
- [ ] All audit regressions covered by tests

---

## 5. Implementation Order & Dependency Graph

```
PR 1 (Schema + Data Gateway)
 │
 ├──► PR 2 (ReceiptLinkService)
 │      │
 │      ├──► PR 3 (Asset Store + Validator + Coordinator Skeleton)
 │      │      │
 │      │      ├──► PR 4 (Camera/Gallery Migration + Save to Expense)
 │      │      │      │
 │      │      │      ├──► PR 5 (Review Queue + Bank Statement + Email)
 │      │      │      │      │
 │      │      │      │      ├──► PR 6 (Warranty + Return + Price Protection)
 │      │      │      │      ├──► PR 7 (Matching + Item Categorization)
 │      │      │      │      │
 │      │      │      │      └──► PR 8 (Duplicate Detection + Deletion + Backup)
 │      │      │      │             │
 │      │      │      │             └──► PR 9 (Side Effects + Guardrails + Cleanup)
 │      │      │      │
 │      │      │      └── (PR 6-8 are parallelizable after PR 5)
 │      │      │
 │      └──────┴────── (all PRs depend on PR 2 for linking)
```

**Parallelizable after PR 5**: PR 6, PR 7, and PR 8 can be worked on in parallel since they affect different subsystems.

---

## 6. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Migration breaks existing receipt data | Medium | High | Backfill tested with real-data fixtures; `imagePath` nullable already handled |
| `ReceiptLinkService` race conditions | Medium | High | Transaction wrapping; unique index on (receiptId, expenseId) prevents duplicate links |
| Coordinator deadlocks with `TransactionLifecycleCoordinator` | Low | High | No circular injection; `ReceiptLinkService` is the only shared dependency |
| OCR failure detection regression | Medium | Medium | Explicit `processingStatus` field; tests for all failure modes |
| Bank statement warranty false positives | Low | Medium | Document type gating; tests verify warranty NOT called |
| Backup image inclusion too slow for large receipt sets | Low | Low | Async backup with progress; manifest-based incremental backup |

---

## 7. Test Strategy

### Unit Tests
- `ReceiptInputValidator`: all rejection cases
- `ReceiptDuplicateDetector`: each signal, dedup false positives/negatives
- `ReceiptLinkService`: all policy permutations
- `ReceiptSideEffectDispatcher`: correct effect routing per document type
- `BankStatementLifecycleProcessor`: dedup, review creation, blocked side effects
- `ReceiptAssetStore`: all file operations, orphan detection

### Integration Tests
- Camera/gallery successful scan → review state
- OCR failure → manual placeholder with correct status
- Parse failure → parse-failed state
- Receipt save → expense through `TransactionLifecycleCoordinator` → link through `ReceiptLinkService`
- Review approval → link (single + bank statement multi)
- Bank statement flow: one receipt → many reviews → many approved expenses with multi-link
- Email receipt flow: dedup, no-image path
- Matching worker: auto-match → link, suggestion → status
- Warranty gated by document type
- Price protection gated by document type
- Backup/restore with images
- Orphan cleanup

### Audit Regression Tests (from audit §13.3)
1. Same receipt scanned twice → detected as duplicate
2. Receipt already linked → cannot be overwritten accidentally
3. Bank statement → no warranty extraction triggered
4. Bank statement → can link to multiple approved expenses
5. Email receipt with `imagePath = null` → downstream features don't crash
6. Review approval → no direct `ScannedReceiptDao` call
7. Warranty repository → no direct receipt creation
8. Price protection → no `ScannedReceiptDao` injection
9. OCR failure → explicit `processingStatus`, not fake raw text
10. Receipt images → included in backup
11. Receipt deletion → removes image asset
12. Item categorization status → matches actual categorization rows

---

## 8. Acceptance Criteria (Phase 4 Complete)

1. All receipt entry paths (camera, gallery, file import, bank statement, email, manual fallback) go through `ReceiptLifecycleCoordinator`
2. `ReceiptRepository` is no longer a god orchestrator (target: ≤ 400 lines of pure data access)
3. Receipt source type, document type, and processing status are explicit on every record
4. Receipt lifecycle events are written for major transitions
5. Receipt-expense linking uses `ReceiptLinkService` with policy enforcement
6. Bank statement → many-expense links supported through `receipt_expense_links` table
7. Bank statements do not behave like retail receipts (no warranty, no price protection, no categorization)
8. Email receipts work without image assumptions
9. Scanned receipt duplicate detection exists (exact hash + text + semantic)
10. Direct `ScannedReceiptDao` access outside approved classes is zero
11. Warranty, return window, price protection, item categorization are document-type-aware
12. Receipt image deletion and orphan cleanup exist
13. Receipt images are included in backup/export
14. All 12 audit regression tests pass
15. Guardrails prevent new direct DAO leaks

---

## Appendix A: Files Created

| File | PR |
|---|---|
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | PR 3 |
| `domain/receipt/lifecycle/ReceiptLinkService.kt` | PR 2 |
| `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | PR 9 |
| `domain/receipt/ReceiptDuplicateDetector.kt` | PR 8 |
| `domain/receipt/ReceiptInputValidator.kt` | PR 3 |
| `domain/receipt/statement/BankStatementLifecycleProcessor.kt` | PR 5 |
| `domain/receipt/email/EmailReceiptLifecycleProcessor.kt` | PR 5 |
| `data/receipt/ReceiptAssetStore.kt` | PR 3 |
| `data/database/entity/ReceiptExpenseLink.kt` | PR 1 |
| `data/database/entity/ReceiptLifecycleEvent.kt` | PR 1 |
| `data/database/dao/ReceiptExpenseLinkDao.kt` | PR 1 |
| `data/database/dao/ReceiptLifecycleEventDao.kt` | PR 1 |

## Appendix B: Files Modified

| File | PR | Change |
|---|---|---|
| `data/database/AppDatabase.kt` | PR 1 | MIGRATION_95_96, new entities, new DAOs |
| `data/database/entity/ScannedReceipt.kt` | PR 1 | New columns |
| `data/database/dao/ScannedReceiptDao.kt` | PR 1 | New query methods |
| `data/repository/ReceiptRepository.kt` | PR 1,4,9 | Thinned, deprecated orchestrator methods |
| `data/email/EmailReceiptIngestionService.kt` | PR 5 | Remove direct DAO, route through coordinator |
| `data/repository/WarrantyTrackerRepository.kt` | PR 6 | Remove direct DAO, receive data from coordinator |
| `domain/price/PriceProtectionTracker.kt` | PR 6 | Remove DAO injection, use repository method |
| `data/repository/ReviewQueueRepository.kt` | PR 5 | Remove DAO injection, use `ReceiptLinkService` |
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | PR 4 | Call coordinator |
| `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` | PR 6 | Gate by document type |
| `service/receiptmatching/ReceiptMatchingWorker.kt` | PR 7 | Use `ReceiptLinkService` |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | PR 8 | Include receipt images |
| `di/DaoModule.kt` | PR 1,3 | New bindings, guardrail comments |

---

*End of final Phase 4 plan.*
