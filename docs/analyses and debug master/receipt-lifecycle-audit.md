# Receipt Lifecycle Audit — Full Codebase Inventory

> Generated: 2026-05-01  
> Source: `app/src/main/java/com/yourname/expensetracker/`  
> Files analyzed: All `.kt` files in main source set

---

## Summary Statistics

| Metric | Count |
|---|---|
| Receipt entry paths | **5** (Camera, Gallery, File import, Bank statement, Email ingestion) |
| Receipt-to-expense linking paths | **3** (ReceiptScan → approval, ReviewQueue approval, Email ingestion → auto-expense) |
| Direct DAO calls outside ReceiptRepository | **10+** (EmailReceiptIngestionService, WarrantyTrackerRepository, PriceProtectionTracker) |
| Downstream features fed by receipts | **6** (Expenses, Pending Reviews, Warranties, Return Windows, Price Protection, Item Categorization) |
| Receipt-related Workers | **2** (ReceiptMatchingWorker, WarrantyExpirationWorker) |
| Units with direct `ScannedReceiptDao` access | **4** classes outside ReceiptRepository |
| Files involved in receipt lifecycle | **~40+** files across data, domain, ui layers |

---

## 1. Receipt Processing Paths

### 1a. Image Capture

**File**: `ui/screens/receiptscan/ReceiptScanScreen.kt`

Three entry points are launched via `ActivityResultContracts`:

1. **Camera** (line 90: `ActivityResultContracts.TakePicture()`)
   - Creates temp URI via `ReceiptScanViewModel.createTempPhotoUri()` → `ReceiptRepository.createTempPhotoUri()` → `ReceiptOcrService.createTempImageUri()` (FileProvider URI)
   - On success: `ReceiptScanViewModel.processPhoto()` → `processImageUri()`

2. **Gallery** (line 97: `ActivityResultContracts.OpenDocument()`)
   - Accepts any document (image/PDF) via system picker
   - On selection: `ReceiptScanViewModel.processGalleryImage(uri)` → `processImageUri()`

3. **File Import** (also via `OpenDocument`, same launcher as gallery)

**ViewModel flow** (`ReceiptScanViewModel.processImageUri()`, line 204):
- Sets `ScanStep.PROCESSING`
- Calls `receiptRepository.processReceipt(uri, autoCreateReview = false)` — note: manual scans do NOT auto-create PendingReview
- On success: transitions to `ScanStep.REVIEW` with parsed data
- On OCR failure: falls back to `saveManualReceiptRecord(uri)` — saves image without OCR text
- On total failure: transitions to `ScanStep.ERROR`

**Risks**:
- No image-quality validation before processing
- No duplicate image detection on capture
- Large images consume memory with no progressive loading

### 1b. OCR Processing

**File**: `domain/receipt/ReceiptOcrService.kt` (667 lines)

- **Technology**: Google ML Kit `TextRecognition` with `TextRecognizerOptions.DEFAULT_OPTIONS` (Latin model, 93 languages)
- **Singleton** `@Singleton`, shared ML recognizer protected by `Mutex` for serialized access
- Methods:
  - `processUri(uri)` — Auto-routes by MIME type to `processImage()` or `processPdf()`
  - `processImage(uri)` — Loads bitmap, saves compressed JPEG copy, runs ML Kit OCR with 3-retry, extracts `TextBlock` list with confidence filtering (< 0.2 confidence blocks skipped)
  - `processPdf(uri)` — Tries PDFBox direct text extraction first; if < 100 chars, falls back to rendering pages to bitmaps + running OCR on each page (max 5 pages)
  - `persistImageCopy(uri)` — Saves compressed image without running OCR (used for manual fallback)
  - `close()` — Releases ML Kit recognizer resources
- **Image handling**: EXIF rotation correction, dynamic dimension selection based on available memory (384–1024px)
- **Storage**: Images saved to `filesDir/receipts/receipt_<timestamp>.jpg` (JPEG quality 80%)
- **File size limit**: 20MB via `validateFileSize()`

**Config**: `domain/config/AppConfig.kt` — `MAX_OCR_IMAGE_DIMENSION = 1024`, `MAX_OCR_FILE_SIZE_MB = 20`

**Risks**:
- The ML Kit recognizer is never explicitly closed in production code (only `close()` method exists but is not called on app lifecycle events)
- PDF processing extracts text from only first 5 pages
- No OCR confidence threshold applied at service level (only block-level < 0.2 filter)

### 1c. Receipt Parsing

**File**: `domain/receipt/ReceiptParser.kt` (795 lines)

- **Singleton** `@Singleton` with `MerchantRulesPolicy` and `TimeProvider` dependencies
- Core method: `parse(rawText, homeCurrency = "EUR")` returns `ParsedReceipt`
- Processing pipeline:
  1. `normalizeGreekOcr(text)` — Handles Greek OCR hallucinations (e.g., "ZYNOAO" → "ΣΥΝΟΛΟ"), fuzzy matching via `StringDistanceUtils`, compound keyword normalization, currency detection
  2. `extractMerchant(lines)` — Scans first 10 lines for header markers, validates via `MerchantRulesPolicy`, filters card processor names
  3. `extractDate(text)` — Parses DD/MM/YYYY or DD/MM/YY with year sanity check (±10 years from current)
  4. `extractTotal(lines)` — Priority-based: TOTAL_KEY (keyword) > AMOUNT_KEY > CASH_KEY > standalone fallback amount; filters non-total indicators (receipt numbers, time, change)
  5. `extractSubtotal(text)` — Matches subtotal keywords
  6. `extractTax(text)` — Multiple patterns for Greek ΦΠΑ OCR variations, including corrupted "0.n.A" patterns
  7. `extractLineItems(text)` — Two regex patterns: "description  amount" and "qty x description  amount"; deduplicates by normalized description
  8. `calculateConfidence(merchant, total, date, items, tax)` — Weighted scoring (merchant 15%, total 40%, date 15%, items 15%, tax 5%, cross-validation 10%)
- Serialization: `lineItemsToJson()`, `lineItemsFromJson()` using JSONArray/JSONObject
- Confidence: Returns 0f if no critical data (merchant, total, date)

**Models**:
- `ParsedReceipt(merchantName, total, subtotal, tax, date, currency, lineItems, confidence)`
- `LineItem(description, quantity, unitPrice, totalPrice)`

**Risks**:
- Hardcoded `homeCurrency = "EUR"` default (though `detectCurrency` may override)
- `maxAmount` validation rejects > 50000 (B2B receipts could exceed this)
- Line items limited to two regex patterns — many receipt formats will be missed
- No structured address/store code extraction

### 1d. Bank Statement Processing

**File**: `domain/receipt/BankStatementParser.kt`

Found via `BankStatementParser` references:

- Injected into `ReceiptRepository` (line 58)
- Used by `ReceiptRepository.processStatement(uri)` (line 492)
- Parses `OcrResult.blocks` (spatial text blocks with positions) for NBG (National Bank of Greece) format
- Creates a single `ScannedReceipt` with `parsedMerchant = "Bank Statement"` → then creates one `PendingReview` per detected transaction
- Includes window-based deduplication against existing expenses and pending reviews (currency-aware)
- Uses `DebugIssueDetector` to flag low-confidence transactions

**File**: `data/repository/ReceiptRepository.kt` (lines 492–706)
- `processStatement()` is a major method (214 lines) handling:
  - OCR → BankStatementParser → per-transaction PendingReview creation
  - Deduplication with `hasExpenseDuplicateInRangeCurrencyAware()` and `hasExpenseDuplicateInRange()`
  - `StatementInsertOutcome` enum with 6 states including race-condition handling
  - Debug data generation with full parsing logs

**Risks**:
- Tight coupling: bank statement logic lives inside `ReceiptRepository` (not separated)
- NBG-specific: may not generalize to other bank formats without new parser implementations
- Deduplication logic is duplicated between statement processing and review approval paths

### 1e. Email Receipt Ingestion

**File**: `data/email/EmailReceiptIngestionService.kt` (443 lines)
**File**: `data/email/provider/AmazonReceiptParser.kt`
**File**: `data/email/provider/AppleReceiptParser.kt`
**File**: `data/email/provider/UberReceiptParser.kt`
**File**: `data/email/provider/EmailReceiptParser.kt` (interface + `BaseEmailParser`)
**File**: `data/database/entity/EmailReceiptSource.kt`

- **Flow**:
  1. `processEmailReceipt(emailBody, receivedAt, provider)` is the main entry
  2. Delegates to provider-specific parser (Amazon/Uber/Apple) via `parseEmailReceipt()`
  3. Generates fingerprint: `"${merchant.lowercase()}_${amount}_${date}"` for deduplication
  4. Checks `EmailReceiptSource` by `emailMessageId` first, then `ScannedReceipt` by fingerprint
  5. Creates `ScannedReceipt` with `imagePath = null` (no image for email), stores email body snippet as `rawOcrText`
  6. Creates `EmailReceiptSource` FK record
  7. Routes through `ProcessReceiptUseCase` or directly creates expense via `TransactionLifecycleCoordinator`
  8. Returns `EmailReceiptResult` (Success/Duplicate/ParseError)

**Entity**: `EmailReceiptSource(receiptId, emailSender, emailSubject, emailMessageId, parsedAt, provider, confidence, fingerprint)`
- FK → `ScannedReceipt` with CASCADE delete
- Unique index on `emailMessageId`
- Index on `fingerprint` for dedup

**DAO**: `EmailReceiptDao` with CRUD, `getByMessageId()`, `getByFingerprint()`, `getByProvider()`, `deleteOlderThan()`

**Risks**:
- Email receipt has NO image (`imagePath = null`) — downstream features (warranty extraction, price protection) may assume image always exists
- `EmailReceiptIngestionService` accesses `scannedReceiptDao` directly (anti-pattern)
- `rawOcrText` = `emailBody.take(5000)` — large emails truncated
- Duplicate detection by fingerprint may have false positives on amount rounding

---

## 2. Receipt Storage & Data Model

### 2a. ScannedReceipt Entity

**File**: `data/database/entity/ScannedReceipt.kt`

```kotlin
@Entity(tableName = "scanned_receipts")
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String?,                              // null for email receipts
    val rawOcrText: String,                              // Always set, even on failure
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,                            // JSON array of line items
    val parsedTaxAmount: Double?,
    @ColumnInfo(defaultValue = "EUR") val currency: String = "EUR",
    val confidence: Float,
    val expenseId: Long? = null,                         // FK to Expense (SET NULL on delete)
    @ColumnInfo(defaultValue = "UNMATCHED") val matchStatus: MatchStatus = MatchStatus.UNMATCHED,
    val matchConfidence: Float? = null,
    val suggestedExpenseId: Long? = null,
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "PENDING") val itemCategorizationStatus: CategorizationStatus = CategorizationStatus.PENDING
)
```

**Foreign Keys**:
- `expenseId` → `Expense(id)` with `SET NULL` on delete

**Indices**:
- `expenseId`, `createdAt`, `matchStatus` (named `index_scanned_receipts_matchStatus`)

**Enums**:
- `MatchStatus`: `UNMATCHED`, `AUTO_MATCHED`, `SUGGESTED`, `MANUALLY_MATCHED`, `REJECTED`
- `CategorizationStatus`: `PENDING`, `ANALYZING`, `READY`, `CORRECTED`, `SKIPPED`

**Note**: The `parsedItems` field stores line items as a JSON string. There is NO normalized receipt line items table — items are embedded JSON.

### 2b. ScannedReceiptDao

**File**: `data/database/dao/ScannedReceiptDao.kt`

```kotlin
@Dao
interface ScannedReceiptDao {
    suspend fun insert(receipt: ScannedReceipt): Long          // REPLACE on conflict
    suspend fun update(receipt: ScannedReceipt)
    suspend fun delete(receipt: ScannedReceipt)
    fun getAllFlow(): Flow<List<ScannedReceipt>>               // ORDER BY createdAt DESC
    suspend fun getAll(): List<ScannedReceipt>
    suspend fun getReceiptsPaged(limit: Int, offset: Int)
    suspend fun getById(id: Long): ScannedReceipt?
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?
    suspend fun getCount(): Int
    suspend fun deleteAll()
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)  // Sets expenseId + matchStatus='AUTO_MATCHED'
    suspend fun updateCategorizationStatus(receiptId: Long, status: String)
    suspend fun getUnmatchedReceipts(): List<ScannedReceipt>     // matchStatus='UNMATCHED'
    suspend fun getReceiptsWithSuggestions(): List<ScannedReceipt> // matchStatus='SUGGESTED'
    suspend fun getRecentReceipts(since: Long, limit: Int)
}
```

### 2c. ReceiptRepository Methods

**File**: `data/repository/ReceiptRepository.kt` (930 lines)

Complete method inventory:

| Method | Lines | Description | DAO/Delegation |
|---|---|---|---|
| `processReceipt(uri, autoCreateReview)` | 109–261 | OCR → parse → save receipt → conditional PendingReview → warranty extraction | Own DAO calls: insert, update |
| `saveManualReceiptRecord(uri)` | 263–297 | Persist image only, no OCR | Own DAO call: insert |
| `createExpenseFromReceipt(...)` | 309–391 | Create expense from receipt after review → link receipt to expense | `TransactionLifecycleCoordinator` + own DAO: linkToExpense |
| `createTempPhotoUri()` | 405–407 | Delegate to OCR service | Delegation only |
| `getReceiptById(id)` | 409–410 | Delegate to DAO | Delegation only |
| `updateCategorizationStatus(...)` | 413–414 | Delegate to DAO | Delegation only |
| `deleteReceipt(receipt)` | 417–419 | Delete image file + DAO delete | Own DAO call |
| `getReceiptCount()` | 422–423 | Delegate to DAO | Delegation only |
| `processBatch(uris, onProgress)` | 437–487 | Batch OCR with concurrency semaphore (max 3) | Delegates to `processReceipt` |
| `processStatement(uri)` | 492–706 | Bank statement OCR → parser → per-tx PendingReview + dedup | Own DAO calls + complex dedup |
| `clearAllScannedReceipts()` | 761–766 | Delete all images + all receipts | Own DAO calls |
| `exportParserDebugData()` | 772–793 | Paged export of all receipts for debugging | Own DAO calls |
| `debugReceipt(receiptId)` | 799–801 | Single receipt debug info | Delegation only |
| `getUnmatchedReceipts()` | 833–834 | Delegate to DAO | Delegation only |
| `linkReceiptToExpense(...)` | 837–849 | Update receipt with expenseId + AUTO_MATCHED | Own DAO: update |
| `saveMatchSuggestion(...)` | 852–864 | Set SUGGESTED status | Own DAO: update |
| `approveMatchSuggestion(receiptId)` | 867–876 | Approve → MANUALLY_MATCHED | Own DAO: update |
| `rejectAllSuggestions(receiptId)` | 879–886 | Reject → REJECTED | Own DAO: update |
| `getReceiptsWithSuggestions()` | 889–890 | Delegate to DAO | Delegation only |
| `getExpenseById(id)` | 893–894 | Delegate to expenseDao | Cross-DAO access |
| `clearMatchForReceipt(receiptId)` | 897–905 | Reset to UNMATCHED | Own DAO: update |
| `getCandidateExpensesForReceipt(...)` | 908–929 | Scored expense candidates for manual matching | expenseDao access |

**Dependencies injected**: `AppDatabase`, `ScannedReceiptDao`, `ExpenseDao`, `PendingReviewDao`, `ReceiptOcrService`, `ReceiptParser`, `BankStatementParser`, `CategorizationEngine`, `MerchantNormalizer`, `HybridExpenseClassifier`, `CrossSourceDeduplication`, `DebugIssueDetector`, `TimeProvider`, `AutoCreateWarrantyFromReceiptUseCase`, `TransactionLifecycleCoordinator`

**Key Risk**: `ReceiptRepository` handles TOO MUCH — OCR orchestration, parsing, review creation, expense creation, bank statement processing, batch processing, warranty extraction triggering, receipt matching, debug export. It spans both data and domain concerns.

### 2d. Line Items / Source Documents

**Line Items**: There is NO normalized `ReceiptLineItem` table. Line items are stored as:
- JSON string in `ScannedReceipt.parsedItems`
- Parsed via `ReceiptParser.LineItem` model and `lineItemsToJson()`/`lineItemsFromJson()` serialization methods
- Individual item categorizations are stored in `receipt_item_categorizations` table (see section 4d)

**Source Document concept**: There is no `SourceDocument` entity. Instead:
- `ReceiptSource` (sealed interface, `domain/receipt/ReceiptSource.kt`): `UriRef(value: String)` or `ParsedContent(rawText, merchant, amount, date, imagePath)`
- `EmailReceiptSource` tracks email-specific metadata linked to `ScannedReceipt`
- No unified document type discriminator on `ScannedReceipt` (bank statements vs regular receipts vs email receipts all mixed)

### 2e. OCR Text Storage & Retention

- `ScannedReceipt.rawOcrText: String` — ✅ Always stored, even on failure ("Scan Failed: ...")
- Manual receipts get `"[OCR Failed or Skipped]"` placeholder
- Email receipts store `emailBody.take(5000)` as `rawOcrText`
- OCR text is **never purged** — not purgeable, no retention policy
- Used by: AI Assist, warranty extraction, price protection, debug export, receipt matching
- Used by `SuggestReceiptExtractionUseCase` which checks for blank/failed OCR before proceeding

---

## 3. Receipt → Expense Linking

### 3a. Link Creation Paths

There are **3 distinct paths** that link a receipt to an expense:

1. **Direct scan → expense creation** (`ReceiptRepository.createExpenseFromReceipt()`, line 354):
   - Called from `ReceiptScanViewModel.saveExpenseInternal()`
   - Uses `scannedReceiptDao.linkToExpense(receiptId, expenseId)` — sets `expenseId + matchStatus='AUTO_MATCHED'`

2. **Review Queue approval** (`ReviewQueueRepository.approveReview()`, line 257):
   - When a `PendingReview` has `scannedReceiptId != null`, approval calls `scannedReceiptDao.linkToExpense(receiptId, id)`
   - Link happens inside the approval transaction

3. **Auto-matching via ReceiptMatchingWorker** (`ReceiptRepository.linkReceiptToExpense()`, line 837):
   - Called when `ReceiptTransactionMatcher` returns `AutoMatch` (score >= 0.95)
   - Uses `receipt.copy(expenseId, matchStatus=AUTO_MATCHED, matchConfidence)` then `scannedReceiptDao.update()`
   - Note: this path does NOT use `linkToExpense()` DAO method — it manually sets fields

**Same receipt linked multiple times?** The `linkToExpense()` DAO method overwrites `expenseId` without checking if already set. The manual `update()` path also overwrites. There's no guard against re-linking.

### 3b. Receipt → Pending Review

Two paths create `PendingReview` from receipts:

1. **Batch processing** (`ReceiptRepository.processReceipt()`, lines 165–186):
   - When `autoCreateReview = true` (batch mode)
   - Creates `PendingReview` with `scannedReceiptId = insertedReceiptId`, amount from parser, merchant from parser, category from `HybridExpenseClassifier`

2. **Bank statement processing** (`ReceiptRepository.processStatement()`, lines 569–582):
   - Creates one `PendingReview` per detected transaction
   - All linked to the same `receiptId` (single receipt record per statement image)
   - Includes deduplication against expenses and other pending reviews

**Parse failure review** (lines 240–256):
   - When OCR succeeded but parsing failed, a placeholder `PendingReview` is created with `suggestedAmount = 0.01` (sentinel), `suggestedMerchant = "Parsing Failed"`

### 3c. Auto-Matching / Reconciliation

**ReceiptTransactionMatcher** (`domain/receiptmatching/ReceiptTransactionMatcher.kt`, 141 lines):
- Singleton, uses `ExpenseRepository`, `MerchantNormalizer`, `StringDistanceUtils`
- `findBestMatch(receipt, lookbackDays = 7)` → returns `MatchResult`
- Scoring: amount match (35%), merchant fuzzy match (40%), date proximity (20%), transaction type (5%)
- Thresholds: >= 0.95 = AutoMatch, >= 0.80 = Suggested, else NoMatch
- Only considers transactions within ±7 days of receipt date
- Only considers `PURCHASE` type transactions with positive amounts

**ReceiptMatchingWorker** (`data/repository/ReceiptMatchingWorker.kt`, 129 lines):
- Runs every 2 hours via `PeriodicWorkRequest`
- Iterates unmatched receipts → calls `matcher.findBestMatch()`
- AutoMatch → `linkReceiptToExpense()` + sends notification
- Suggested → `saveMatchSuggestion()` (sets `matchStatus=SUGGESTED`)
- Schedule/cancel/runOnce companion methods

**Manual matching UI**: `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt` + `ReceiptMatchingScreen.kt`
- Shows unmatched receipts side-by-side with candidate expenses
- Manual approve/reject suggestions
- Debounced re-run of matcher

**Risks**:
- Matching only considers 7-day window — older receipt-to-expense matches are missed
- `linkToExpense()` DAO method and `linkReceiptToExpense()` repository method are redundant — both do the same thing differently
- No "unlink" operation defined (only `clearMatchForReceipt()` which resets to UNMATCHED)

---

## 4. Receipt → Downstream Features

### 4a. Warranty Extraction

**Files**:
- `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` (324 lines)
- `domain/receipt/WarrantyTextExtractor.kt` — regex-based text extraction + return window days
- `data/repository/WarrantyTrackerRepository.kt` (344 lines)
- `data/database/entity/Warranty.kt`
- `data/database/dao/WarrantyDao.kt`

**Flow**:
1. `AutoCreateWarrantyFromReceiptUseCase.execute(receiptId, receiptText)` is called from `ReceiptRepository.processReceipt()` (line 193)
2. Checks existing warranty by `receiptId` — returns `AlreadyExists` if found
3. Extracts warranty data via `WarrantyTextExtractor.extract(receiptText)` — regex-based:
   - Warranty duration patterns (months/years)
   - Product names
   - Support phone/email
   - Return policy windows
4. Confidence thresholds: >= 70% auto-create, 40-70% create review draft (`WarrantyStatus.PENDING_REVIEW`), < 40% skip
5. Warranty created via `WarrantyTrackerRepository.addWarrantyIgnoreConflicts()` 
6. Automatically creates `ReturnWindow` via `persistReturnWindow()`

**Warranty Entity** (`data/database/entity/Warranty.kt`):
```kotlin
data class Warranty(
    val receiptId: Long,                                  // FK → ScannedReceipt
    val expenseId: Long?,                                  // FK → Expense
    val productName: String,
    val merchantName: String,
    val purchaseDate: Long,
    val warrantyDurationMonths: Int,
    val warrantyEndDate: Long,
    val warrantyType: WarrantyType,                        // MANUFACTURER, EXTENDED, STORE, THIRD_PARTY
    val status: WarrantyStatus,                            // ACTIVE, EXPIRING_SOON, EXPIRED, CLAIMED, PENDING_REVIEW
    val supportPhone: String?, val supportEmail: String?,
    val warrantyDocumentUrl: String?,
    val notes: String?,
    // F1 Pipeline fields:
    val autoDetected: Boolean = false,
    val extractionConfidence: Double = 0.0,
    val extractionSource: String = "",
    val needsReview: Boolean = false,
    ...
)
```

**Risks**:
- Two separate extraction paths: regex-based in `WarrantyTextExtractor` and cloud-AI-based in `CloudWarrantyExtractionService` (in `WarrantyTrackerRepository`)
- ReturnWindow is auto-created with warranty but there's a separate `upsertReturnWindowForReceipt()` that duplicates logic
- `WarrantyTrackerRepository` accesses `scannedReceiptDao` directly (anti-pattern)
- `createManualPlaceholderReceipt()` in `WarrantyTrackerRepository` inserts `ScannedReceipt` directly (bypassing ReceiptRepository)

### 4b. Return Windows

**Files**:
- `data/database/entity/ReturnWindow.kt` — Entity with FK → `ScannedReceipt` (CASCADE) and → `Expense` (SET NULL)
- `data/database/dao/ReturnWindowDao.kt`
- `data/repository/WarrantyTrackerRepository.kt` (return window methods)

**ReturnWindow Entity**:
```kotlin
data class ReturnWindow(
    val receiptId: Long, val expenseId: Long?,
    val productName: String, val merchantName: String,
    val purchaseDate: Long, val returnDays: Int,
    val returnDeadline: Long,
    val returnPolicyUrl: String?,
    val returnConditions: String?,
    val status: ReturnStatus,  // RETURNABLE, EXPIRED, RETURNED, EXCHANGED, NON_RETURNABLE
    val returnedAt: Long?, val refundAmount: Double?,
    val createdAt: Long, val updatedAt: Long
)
```

**Creation points**:
1. `WarrantyTrackerRepository.extractReturnWindow()` — from OCR/receipt data, called during warranty processing
2. `WarrantyTrackerRepository.upsertReturnWindowForReceipt()` — upsert logic called from `AutoCreateWarrantyFromReceiptUseCase`
3. `AutoCreateWarrantyFromReceiptUseCase.persistReturnWindow()` — always after warranty creation
4. `PriceProtectionTracker.getReturnWindow()` — hardcoded merchant-based return window (30/90/14/15 days)

**Expiry**: `WarrantyTrackerRepository.reconcileExpiredItems()` marks expired return windows in batch, called from `WarrantyExpirationWorker`

**Risks**:
- Duplicate return window creation paths: `upsertReturnWindowForReceipt()` and `createReviewDraftWarranty().persistReturnWindow()` may race
- Return window default days double-defined: `WarrantyTrackerRepository.defaultReturnDaysForMerchant()` and `PriceProtectionTracker.getReturnWindow()` have different values

### 4c. Price Protection

**Files**:
- `domain/price/PriceProtectionTracker.kt` (481 lines)
- `ui/screens/price/PriceProtectionViewModel.kt`
- `ui/screens/price/PriceProtectionScreen.kt`

- Accesses `ScannedReceiptDao` directly (anti-pattern, bypasses ReceiptRepository)
- `getPriceProtectedItems()` — reads recent 30-day receipts, parses items JSON, filters by price-protectable categories
- `isEligibleForPriceProtection(receipt)` — checks if within 30-day window
- `monitorPriceDrops()` — simulated price monitoring flow
- `findBetterDeals()`, `findCoupons()`, `getCreditCardBenefits()` — all simulated
- `getDealsCouponsAndBenefits()` — aggregates across recent receipts

**Risks**:
- All price monitoring is **simulated** (no real API calls) — `isSimulated = true` on all results
- Uses `receiptDao.getRecentReceipts(since)` directly (bypasses all ReceiptRepository logic)
- Duplicate return window days logic with `WarrantyTrackerRepository`

### 4d. Item Categorization

**Files**:
- `data/database/entity/ReceiptItemCategorization.kt` — Entity with FK → `ScannedReceipt` (CASCADE) and → `Expense` (SET NULL)
- `data/database/dao/ReceiptItemCategorizationDao.kt` (94 lines)
- `data/repository/ReceiptItemCategorizationRepository.kt` (106 lines)
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `domain/ai/model/ReceiptItemCategorizationModels.kt`

**Flow**:
1. Triggered from `ReceiptScanViewModel.analyzeReceiptItemsInternal()` (line 1060)
2. Calls `CategorizeReceiptItemsUseCase(receiptId)`
3. Builds input via `ReceiptItemCategorizationInputBuilder.build(receipt, settings)`
4. Routes to cloud or on-device AI via capability router
5. Saves categorizations via `ReceiptItemCategorizationRepository.saveCategorizationResult()`
6. Each item gets a `suggestedCategoryId` with confidence and alternatives
7. User can correct via `updateUserCorrection()` in ViewModel

**ReceiptItemCategorization Entity**:
```kotlin
data class ReceiptItemCategorization(
    val receiptId: Long,
    val expenseId: Long?,
    val itemDescription: String,
    val itemAmount: Double,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String?,
    val confidence: Float,
    val aiRationale: String?,
    val alternativeCategoriesJson: String?,
    val userCorrectedCategoryId: Long?,
    val userCorrectedCategoryName: String?,
    val userCorrectedAt: Long?,
    val taxAmount: Double?,
    val isNewCategorySuggestion: Boolean = false,
    val createdAt: Long, val updatedAt: Long
)
```

**Risks**:
- Item categorizations are deleted and re-created each analysis run (`deleteByReceiptId` then `saveCategorizationResult`) — no delta/merge
- `CategorizeReceiptItemsUseCase` accesses `ReceiptRepository` directly (not through a coordinator)
- Item categorization status on `ScannedReceipt` is updated separately from the actual categorization data

### 4e. AI Assist

**Files**:
- `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` — AI receipt extraction suggestions
- `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt` — AI category suggestions
- `domain/ai/usecase/ReceiptAssistInputBuilder.kt` — builds AI input from receipt
- `domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `data/ai/provider/CloudReceiptAssistService.kt` — cloud AI for receipt data
- `data/ai/provider/OnDeviceReceiptAssistService.kt` — on-device AI (Gemini Nano)
- `data/ai/provider/SmartReceiptAssistService.kt` — orchestrator: vision → cloud text → on-device
- `data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `domain/ai/model/CaptureAssistModels.kt` — `ReceiptAssistInput`, `CategorizationAssistInput`

**Flow**:
1. `SuggestReceiptExtractionUseCase(receiptId)` — checks for usable OCR text, builds input with receipt data + image, routes to AI provider
2. Results fed into `ReceiptScanViewModel` state as `ReceiptAssistSuggestion`/`CategoryAssistSuggestion`
3. User can apply individual fields or all at once
4. "Quick Save" feature uses AI to fill missing fields before saving

**Cloud receipt assist** uses formatted OCR text + image analysis prompt with error handling for Greek OCR corruption.

**Risks**:
- AI assist builds OCR text input but has NO OCR confidence validation before AI call
- `SmartReceiptAssistService` chains 4 fallback strategies but only logs which was used
- `AiArtifactRepository` is used for caching AI results but caching logic spans ViewModel + use case

---

## 5. Receipt Export/Backup

### Export

**Files**:
- `domain/export/AccountantReportPdfExporter.kt` — PDF report generation (NO receipt images included)
- `domain/export/AccountingExporters.kt` — CSV/IIF export helpers (NO receipt data)
- `domain/export/ExpenseExportMapper.kt` — maps expense → export DTO (NO receipt info)
- `domain/export/ExportTransaction.kt` — domain DTO (no receipt fields)
- `domain/export/AccountingExportPolicy.kt` — validation rules

**Key finding**: Receipt images and receipt data are **NOT included** in any export format. The `exportParserDebugData()` method in `ReceiptRepository` exports raw OCR text for debugging but is not a user-facing export feature. The `ReviewViewModel` calls `receiptRepository.exportParserDebugData()` for "export" but this is debug-only.

### Backup

**Files**:
- `domain/backup/DatabaseBackupRepository.kt` — interface
- `data/repository/DatabaseBackupRepositoryImpl.kt` — implementation (1169 lines)

**Key finding**: Backup is **database-only** (SQLite file copy). Receipt images stored in `filesDir/receipts/` are **NOT included** in backup. The backup file is just the Room database (`AppDatabase.DATABASE_NAME`). Receipt image files would be lost on device wipe.

**Risks**:
- Receipt images are excluded from backup/restore
- `DatabaseStats` does not track receipt count
- No export format includes receipt images or receipt metadata

---

## 6. Direct DAO Access & Validation Gaps

### Direct `scannedReceiptDao` Calls Outside `ReceiptRepository`

| Class | File | Direct DAO Access |
|---|---|---|
| **EmailReceiptIngestionService** | `data/email/EmailReceiptIngestionService.kt` | `scannedReceiptDao.insert()`, `scannedReceiptDao.getById()`, `scannedReceiptDao.getRecentReceipts()`, `scannedReceiptDao.update()` (lines 228, 398, 401) |
| **WarrantyTrackerRepository** | `data/repository/WarrantyTrackerRepository.kt` | `scannedReceiptDao.getById()`, `scannedReceiptDao.insert()` (lines 152, 342) |
| **PriceProtectionTracker** | `domain/price/PriceProtectionTracker.kt` | `receiptDao.getRecentReceipts()` (line 26, via injected `ScannedReceiptDao`) |
| **ReviewQueueRepository** | `data/repository/ReviewQueueRepository.kt` | `scannedReceiptDao.linkToExpense()` (line 257) |

**Total**: 4 classes with direct DAO access outside ReceiptRepository (10+ individual call sites).

### Validation Gaps

1. **No image quality validation**: No blur detection, minimum resolution check, or content validation before OCR
2. **No OCR confidence threshold**: ReceiptRepository accepts ANY OCR output — no minimum confidence requirement
3. **No duplicate receipt detection at capture**: No image fingerprinting/hashing to prevent scanning same receipt twice
4. **Email deduplication is by fingerprint only**: `"${merchant.lowercase()}_${amount}_${date}"` — collision-prone (same amount at same merchant on same day = different receipts)
5. **Bank statement deduplication is complex but duplicated**: `hasExpenseDuplicateInRange()` and `hasExpenseDuplicateInRangeCurrencyAware()` duplicate similar logic
6. **No receipt count/memory threshold**: Batch processing (max 3 concurrent) has no receipt DB count check before processing
7. **Receipt → warranty always triggered**: `processReceipt()` always calls `warrantyUseCase.execute()` — no opt-out, no check if receipt is from bank statement (which creates 1 receipt per statement, triggering warranty extraction on the "Bank Statement" merchant)

### Duplicate Receipt Detection

- **Email receipts**: Fingerprint-based dedup (`EmailReceiptDao.getByFingerprint()`) + messageId unique index
- **Scanned receipts**: **NO duplicate detection** — no image hashing, no content fingerprint
- **Receipt matching dedup**: `ReceiptRepository` has expense dedup for statement processing, but no receipt-to-receipt dedup
- The `DetectDuplicateExpenseUseCase` has `OCR_IMPORT` source type but this is for expense dedup, not receipt dedup

### Currency Handling in Receipts

- **Entity default**: `@ColumnInfo(defaultValue = "EUR") val currency: String = "EUR"`
- **Parser**: `detectCurrency(text)` checks for €/EUR/ΕΥΡΩ, $/USD, £/GBP — returns null if not found, falls back to `homeCurrency = "EUR"`
- **Manual receipts**: Hardcoded `currency = "EUR"` in `saveManualReceiptRecord()`
- **Email receipts**: Uses parsed receipt currency or falls back to home currency
- **Expense creation from receipt**: `ReceiptScanViewModel` resolves via `parsedReceipt?.currency ?: currencySettingsRepository.homeCurrency() ?: "EUR"`
- **Hardcoded EUR as ultimate fallback** throughout the codebase

---

## 7. Anti-Patterns & Risks

### Architectural

1. **ReceiptRepository is a god class** (930 lines):
   - Handles OCR orchestration, parsing, review creation, expense creation, bank statement processing, batch processing, warranty triggering, receipt matching, debug export
   - Mixes data-layer concerns (DAO calls) with domain orchestration
   - No separation between single-receipt and batch processing paths

2. **Direct DAO leaks**: 4 classes inject `ScannedReceiptDao` directly, bypassing `ReceiptRepository`:
   - `EmailReceiptIngestionService` (inserts receipts)
   - `WarrantyTrackerRepository` (creates placeholder receipts)
   - `PriceProtectionTracker` (reads receipts for price monitoring)
   - `ReviewQueueRepository` (links receipts to expenses)

3. **Duplicated linking logic**:
   - `ScannedReceiptDao.linkToExpense()` (DAO-level UPDATE) vs `ReceiptRepository.linkReceiptToExpense()` (load + copy + update)
   - Two paths do the same thing differently

### Data Model

4. **Receipt line items as embedded JSON**: `parsedItems` is a JSON string on the `ScannedReceipt` entity — no relational integrity, no indexing, no querying
5. **Receipt images excluded from backup**: Only the database is backed up; image files are not
6. **No receipt image lifecycle management**: Images accumulate in `filesDir/receipts/` with no cleanup policy

### Processing

7. **No OCR confidence minimum**: `processReceipt()` accepts all OCR output regardless of confidence
8. **Warranty extraction always runs**: Even for bank statement receipts (1 receipt = 1 warranty attempt with merchant "Bank Statement")
9. **No duplicate receipt at capture**: Same physical receipt can be scanned multiple times
10. **Hardcoded EUR**: Multiple fallback paths default to EUR without user awareness

### Matching

11. **7-day matching window**: `ReceiptTransactionMatcher` only considers ±7 days — stale receipts never auto-match
12. **Two matching code paths**: `ReceiptTransactionMatcher` (domain) and inline matching in `ReceiptMatchingWorker` (service)

---

## 8. Recommended ReceiptLifecycleCoordinator Design

Based on the audit findings, a `ReceiptLifecycleCoordinator` should:

### Responsibilities

1. **Unified entry point** for ALL receipt processing:
   - Camera/gallery capture → `processReceipt(uri, source)`
   - Email ingestion → `processEmailReceipt(emailData)`
   - Bank statement → `processStatement(uri)`
   - File import → `processReceipt(uri, source)`

2. **Orchestrate the full pipeline** in order:
   ```
   Validate(input) → OCR → Parse → Normalize → 
   CheckDuplicate → Save → Link → 
   TriggerSideEffects(warranty, categorization, matching)
   ```

3. **Own all receipt state transitions**:
   - Capture → Processing → Analyzed → Matched → Expensed
   - Track status in a unified state machine

### Extraction Points from `ReceiptRepository`

1. **OCR orchestration** → move to coordinator (currently spread across `processReceipt`, `processStatement`, `saveManualReceiptRecord`)
2. **Pending review creation** → coordinator decides when to create
3. **Warranty triggering** → coordinator calls `AutoCreateWarrantyFromReceiptUseCase`
4. **Item categorization triggering** → coordinator schedules
5. **Receipt matching scheduling** → coordinator triggers `ReceiptMatchingWorker`

### Additional Responsibilities

6. **Duplicate detection**: Image hashing/fingerprinting at capture time
7. **Image validation**: Blur detection, minimum size, content check before OCR
8. **Image lifecycle**: Cleanup policy for unreferenced images
9. **Export integration**: Receipt data included in export formats
10. **Backup integration**: Receipt images included with database backup

### Proposed API

```kotlin
class ReceiptLifecycleCoordinator @Inject constructor(
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val receiptRepository: ReceiptRepository,
    private val warrantyUseCase: AutoCreateWarrantyFromReceiptUseCase,
    private val categorizeUseCase: CategorizeReceiptItemsUseCase,
    private val matchingWorker: ReceiptMatchingWorker, 
    private val duplicateDetector: ReceiptDuplicateDetector
) {
    suspend fun processImageCapture(uri: Uri, source: ReceiptSourceType): ReceiptProcessingResult
    suspend fun processEmailReceipt(emailData: EmailReceiptData): EmailReceiptResult
    suspend fun processBankStatement(uri: Uri): BatchResult
    suspend fun processBatch(uris: List<Uri>): BatchResult
    suspend fun linkReceiptToExpense(receiptId: Long, expenseId: Long): Result<Unit>
    suspend fun unlinkReceipt(receiptId: Long): Result<Unit>
    suspend fun getFullReceiptLifecycle(receiptId: Long): ReceiptLifecycleState
    suspend fun deleteReceiptWithCascadingCleanup(receiptId: Long)
}
```

### Files That Need Refactoring

| Priority | File | Issue | Action |
|---|---|---|---|
| HIGH | `ReceiptRepository.kt` | God class, 930 lines | Extract coordinator, keep thin data access |
| HIGH | `EmailReceiptIngestionService.kt` | Direct DAO access | Route through coordinator |
| HIGH | `WarrantyTrackerRepository.kt` | Direct DAO access + placeholder receipt creation | Route through coordinator |
| HIGH | `PriceProtectionTracker.kt` | Direct DAO access | Route through coordinator |
| MED | `ReviewQueueRepository.kt` | Direct linkToExpense call | Route through coordinator |
| MED | `ReceiptMatchingWorker.kt` | Tight coupling to ReceiptRepository | Interact with coordinator |
| MED | `AutoCreateWarrantyFromReceiptUseCase.kt` | Called from ReceiptRepository | Called from coordinator |
| LOW | `ReceiptScanViewModel.kt` | Direct categorization triggering | Coordinator manages side-effects |

---

## Appendix: Complete File Inventory

### Data Layer
| File | Lines | Role |
|---|---|---|
| `data/database/entity/ScannedReceipt.kt` | 59 | Receipt entity |
| `data/database/entity/PendingReview.kt` | 69 | Pending review entity (FK→receipt) |
| `data/database/entity/ReceiptItemCategorization.kt` | 56 | Item categorization entity (FK→receipt) |
| `data/database/entity/Warranty.kt` | ~70 | Warranty entity (FK→receipt) |
| `data/database/entity/ReturnWindow.kt` | 57 | Return window entity (FK→receipt) |
| `data/database/entity/EmailReceiptSource.kt` | 53 | Email receipt source (FK→receipt) |
| `data/database/entity/SplitItemAssignment.kt` | ~30 | Split item (has receiptItemId) |
| `data/database/dao/ScannedReceiptDao.kt` | 54 | Receipt DAO |
| `data/database/dao/ReceiptItemCategorizationDao.kt` | 94 | Item categorization DAO |
| `data/database/dao/EmailReceiptDao.kt` | 70 | Email receipt DAO |
| `data/database/dao/WarrantyDao.kt` | ~60 | Warranty DAO |
| `data/database/dao/ReturnWindowDao.kt` | ~60 | Return window DAO |
| `data/database/model/PendingReviewWithReceipt.kt` | ~15 | Join model |
| `data/repository/ReceiptRepository.kt` | 930 | Main receipt repository |
| `data/repository/ReceiptItemCategorizationRepository.kt` | 106 | Item categorization repository |
| `data/repository/WarrantyTrackerRepository.kt` | 344 | Warranty + return window repository |
| `data/repository/ReviewQueueRepository.kt` | 645 | Review queue (links receipts→expenses) |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | 1169 | Database backup (excludes images) |
| `data/email/EmailReceiptIngestionService.kt` | 443 | Email receipt ingestion |
| `data/email/provider/AmazonReceiptParser.kt` | ~50 | Amazon email parser |
| `data/email/provider/AppleReceiptParser.kt` | ~30 | Apple email parser |
| `data/email/provider/UberReceiptParser.kt` | ~30 | Uber email parser |
| `data/email/provider/EmailReceiptParser.kt` | ~60 | Email parser interface |
| `data/ai/provider/CloudReceiptAssistService.kt` | ~310 | Cloud AI receipt assist |
| `data/ai/provider/OnDeviceReceiptAssistService.kt` | ~120 | On-device AI receipt assist |
| `data/ai/provider/SmartReceiptAssistService.kt` | ~60 | AI provider orchestrator |
| `data/ai/provider/CloudReceiptItemCategorizationService.kt` | ~200 | Cloud item categorization |
| `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt` | ~50 | On-device item categorization |
| `data/ai/provider/CloudWarrantyExtractionService.kt` | ~290 | Cloud warranty extraction |

### Domain Layer
| File | Lines | Role |
|---|---|---|
| `domain/receipt/ReceiptOcrService.kt` | 667 | ML Kit OCR |
| `domain/receipt/ReceiptParser.kt` | 795 | Receipt text parser |
| `domain/receipt/BankStatementParser.kt` | ~460 | Bank statement parser |
| `domain/receipt/ReceiptSource.kt` | 15 | Sealed input source types |
| `domain/receipt/WarrantyTextExtractor.kt` | ~500 | Regex warranty extractor |
| `domain/receipt/EnhancedMerchantExtractor.kt` | ~100 | Enhanced merchant extraction |
| `domain/receiptmatching/ReceiptTransactionMatcher.kt` | 141 | Auto-matching engine |
| `domain/price/PriceProtectionTracker.kt` | 481 | Price protection logic |
| `domain/usecase/receipt/ProcessReceiptUseCase.kt` | 87 | Receipt processing use case |
| `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` | 324 | Auto warranty creation |
| `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` | ~240 | AI receipt extraction |
| `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt` | ~120 | AI category fallback |
| `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt` | ~280 | AI item categorization |
| `domain/ai/usecase/ReceiptAssistInputBuilder.kt` | ~70 | AI input builder |
| `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt` | ~70 | Categorization input builder |
| `domain/ai/usecase/CategorizationAssistInputBuilder.kt` | ~170 | Category assist input builder |
| `domain/ai/model/CaptureAssistModels.kt` | ~40 | AI assist models |
| `domain/ai/model/ReceiptItemCategorizationModels.kt` | ~20 | Categorization models |
| `domain/ai/util/AiArtifactSourceHash.kt` | ~130 | AI artifact hashing |
| `domain/backup/DatabaseBackupRepository.kt` | 59 | Backup interface |
| `domain/export/AccountantReportPdfExporter.kt` | 283 | PDF export (no receipts) |
| `domain/export/AccountingExporters.kt` | ~120 | CSV/IIF export (no receipts) |
| `domain/config/AppConfig.kt` | ~210 | OCR/Receipt config constants |
| `domain/debug/DebugData.kt` | ~40 | Debug data model |
| `domain/debug/DebugIssueDetector.kt` | ~130 | Issue detection |

### UI Layer
| File | Lines | Role |
|---|---|---|
| `ui/screens/receiptscan/ReceiptScanScreen.kt` | 1300 | Scan UI (camera/gallery/review) |
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | 1169 | Scan VM (orchestrates scan flow) |
| `ui/screens/receiptmatching/ReceiptMatchingScreen.kt` | ~400 | Receipt matching UI |
| `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt` | ~180 | Matching VM |
| `ui/screens/price/PriceProtectionScreen.kt` | ~780 | Price protection UI |
| `ui/screens/price/PriceProtectionViewModel.kt` | ~130 | Price protection VM |
| `ui/screens/review/ReviewScreen.kt` | ~1500 | Review screen (shows receipts) |
| `ui/screens/review/ReviewViewModel.kt` | ~770 | Review VM |
| `ui/screens/debug/DebugViewerScreen.kt` | ~200 | Debug OCR viewer |
| `ui/screens/debug/DebugDataStorage.kt` | ~130 | Debug data storage |
| `ui/screens/debug/DebugScreen.kt` | ~1500 | Debug screen (import/export DB) |

### Service/Worker Layer
| File | Lines | Role |
|---|---|---|
| `service/receiptmatching/ReceiptMatchingWorker.kt` | 129 | Periodic receipt matching |
| `service/warranty/WarrantyExpirationWorker.kt` | 97 | Daily warranty expiry check |

### DI Layer
| File | Role |
|---|---|
| `di/DaoModule.kt` | Provides all DAOs |
| `di/EmailIngestionModule.kt` | Email parser bindings |
| `di/BackupRepositoryModule.kt` | Backup repository bindings |

---

*End of audit. Total: **~40+ files**, **5 entry paths**, **3 linking paths**, **6 downstream features**, **4 direct DAO leaks**, **12+ anti-patterns identified**.*
