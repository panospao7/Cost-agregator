# Bank Statement Parsing & Import Audit

**Date:** 2026-05-04  
**Scope:** Full codebase audit of bank statement parsing, importing, storing, and deduplication  
**Auditor:** Scout Agent  

---

## 1. Parser Inventory

### 1.1 `BankStatementParser` (Primary — OCR-based statement parser)
- **File:** `domain/receipt/BankStatementParser.kt` (766 lines)
- **Role:** Parses OCR output (`List<TextBlock>`) into `List<ParsedTransaction>`. Receives spatial text blocks from `ReceiptOcrService`.
- **Dependencies:** `CurrencyNormalizer`, `MerchantCleaner`, `TimeProvider`, `AmountUtils`, `CommonPatterns`
- **Three parsing strategies in order:**
  1. **Revolut-specific** (`tryParseRevolutTransaction`): Uses spatial layout (left/right positions) to distinguish money-out vs money-in columns. Detects date pattern `MMM d, yyyy`. Classifies via `classifyRevolutStatementType()` (TRANSFER, WITHDRAWAL, DEPOSIT, PURCHASE).
  2. **Greek NBG-specific** (`tryParseGreekNbgTransaction`): Parses NBG rows with `Χ` (debit) / `Π` (credit) indicators. Uses `DD/MM/YYYY HH:MM:SS` timestamp format. European number format (comma decimal).
  3. **Generic fallback** (`extractTransactionFromRow`): Uses `CommonPatterns.AMOUNT_REGEX` to find amount candidates, scores them (prefers leftmost non-balance amount), does keyword-based type detection (Greek + English keywords).

### 1.2 `GreekBankParser` (Notification-based parser — separate from statement parser)
- **File:** `domain/parser/parsers/GreekBankParser.kt` (370 lines)
- **Role:** Parses Greek bank **notifications** (NBG, Alpha, Eurobank, Piraeus), NOT full statements. Implements `AppNotificationParser`.
- **Patterns:** PURCHASE_PATTERNS, DEPOSIT_PATTERNS, TRANSFER_PATTERNS with extensive Greek keyword support (`Χρέωση`, `Πίστωση`, `Μεταφορά`, etc.)
- **Currency:** Accepts `homeCurrency` via DI (injected from `CurrencySettingsRepository` via `ParserModule`).

### 1.3 `RevolutParser` (Notification-based parser)
- **File:** `domain/parser/parsers/RevolutParser.kt` (159 lines)
- **Role:** Parses Revolut app **notifications** (e.g., "Paid €12.50 at SKLAVENITIS", "Received €100.00 from John"). NOT full statements.
- **Patterns:** PAID_AT_PATTERN, PAID_TO_PATTERN, RECEIVED_PATTERN, ATM_PATTERN.
- **Note:** This is a separate code path from the Revolut-specific handling inside `BankStatementParser`.

### 1.4 `GreekBankParser` vs `BankStatementParser` NBG parsing — DIFFERENT PARSERS
- `GreekBankParser` handles **single-transaction notifications** from Greek banking apps.
- `BankStatementParser.tryParseGreekNbgTransaction()` handles **NBG statement screenshots** with multiple rows.
- These are independent implementations with no shared parsing logic, despite targeting the same banks.

---

## 2. Supported Formats

| Format | Parser Path | Confidence | Limitations |
|--------|-------------|-----------|------------|
| **Revolut statement screenshots** | `BankStatementParser.tryParseRevolutTransaction()` | 0.95 | Requires `MMM d, yyyy` date format; relies on spatial column positions; only detects `€`, `$`, `£` |
| **Greek NBG statement screenshots** | `BankStatementParser.tryParseGreekNbgTransaction()` | 0.90 | Fixed column structure; expects `Χ`/`Π` delimiter; `DD/MM/YYYY HH:MM:SS` date format |
| **Generic bank statements** (any bank) | `BankStatementParser.extractTransactionFromRow()` | 0.70 (fallback) | Uses heuristic amount scoring; keyword-based type detection; may confuse transaction amount with running balance |
| **Greek bank notifications** (NBG, Alpha, Eurobank, Piraeus) | `GreekBankParser` | 0.90–0.92 | Notification-only; single transaction per call |
| **Revolut notifications** | `RevolutParser` | 0.88–0.95 | Notification-only; patterns for paid/received/ATM |

### Amount parsing support:
- **European format:** `1.234,56` ✔
- **US format:** `1,234.56` ✔
- **Plain decimal:** `1234.56` ✔
- **Currency symbols:** `€`, `$`, `£` + `EUR`, `USD`, `GBP` text codes ✔
- **Thousands-separated:** Both European and US grouping ✔ (via `AmountUtils.parseAmount()`)
- **Negative amounts:** Leading `-`, `−`, parentheses `(100)` ✔

### Date parsing support:
- `dd/MM/yyyy` and `dd/MM/yyyy HH:mm:ss` ✔
- `MMM d yyyy` (Revolut) via `DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US)` ✔
- `dd/MM/yy` (2-digit year) ✔ (padded to 20yy)
- Header-derived transaction date vs value date column detection ✔

---

## 3. UI / Menu Entry Points

### 3.1 Menu Entry
- **File:** `ui/screens/review/ReviewScreen.kt` (line 188-196)
- **UI text:** `stringResource(R.string.review_import_bank_statement)` = "Import Bank Statement" (from `strings.xml` line 412)
- **Location:** Debug menu (3-dot overflow menu) in the ReviewScreen, below "Mass Insert" and above "Export Parser Data"
- **Launcher:** `statementLauncher` — `ActivityResultContracts.OpenDocument()` accepting `image/*` and `application/pdf`

### 3.2 ViewModel Binding
- **File:** `ui/screens/review/ReviewViewModel.kt` (line 724-750)
- **Method:** `processStatement(uri: Uri)` — calls `receiptRepository.processStatement(uri)` (the **legacy** path)
- **State:** Reuses `_isBatchProcessing` / `_batchProgress` state
- **Debug:** Saves debug data to `DebugDataStorage` (JSON file at `app/filesDir/last_debug_data.json`)

### 3.3 ⚠️ CRITICAL: Dual implementation — new path is NOT wired to UI
There are TWO parallel implementations:

| Path | Location | Used by UI? | documentType set? | Lifecycle events? | Side effects suppressed? |
|------|----------|-------------|-------------------|-------------------|--------------------------|
| **NEW (lifecycle-aware)** | `ReceiptLifecycleCoordinator.processBankStatement()` → `BankStatementLifecycleProcessor.processBankStatement()` | ❌ **NOT CALLED from anywhere** | ✅ `BANK_STATEMENT` | ✅ RECEIPT_SAVED + PROCESSING_COMPLETE | ✅ Via `ReceiptSideEffectDispatcher` |
| **OLD (legacy)** | `ReviewViewModel.processStatement()` → `ReceiptRepository.processStatement()` | ✅ **This is what the UI uses** | ❌ **NOT SET** — `documentType` field absent from `ScannedReceipt` constructor call | ❌ **NONE** | ❌ **No gate** — warranty/matching/categorization may fire |

The `ReceiptLifecycleCoordinator.processBankStatement()` method exists (line 470-472) and delegates to `BankStatementLifecycleProcessor`, but **no production code calls it**. The UI's "Import Bank Statement" button goes through the legacy `ReceiptRepository.processStatement()` path instead.

---

## 4. Data Flow (PDF/Image → Parsed Transactions → Storage)

### 4.1 Full data flow diagram

```
User taps "Import Bank Statement" (ReviewScreen.kt:188)
    ↓
ActivityResultContracts.OpenDocument() launches file picker (image/* or application/pdf)
    ↓
ReviewViewModel.processStatement(uri) (ReviewViewModel.kt:724)
    ↓
ReceiptRepository.processStatement(uri) [LEGACY PATH] (ReceiptRepository.kt:574)
    │
    ├─ 1. OCR: receiptOcrService.processUri(uri)
    │        Returns OcrResult(fullText, blocks: List<TextBlock>, savedImagePath)
    │        TextBlock = { text, confidence, left, top, right, bottom }
    │
    ├─ 2. Parse: statementParser.parse(ocrResult.blocks, homeCurrency)
    │        BankStatementParser.parse():
    │        ├─ groupBlocksIntoRowLists() — vertical proximity grouping (50% overlap)
    │        ├─ detectDateColumns() — header keyword scanning for date column order
    │        ├─ tryParseRevolutTransaction() per row — spatial analysis
    │        ├─ tryParseGreekNbgTransaction() per row — NBG format
    │        └─ extractTransactionFromRow() per row — generic fallback
    │        Returns List<ParsedTransaction>
    │
    ├─ 3. Save ScannedReceipt (one record per statement)
    │        ScannedReceipt(
    │          imagePath, rawOcrText,
    │          parsedTotal = null,           // varies per transaction
    │          parsedMerchant = "Bank Statement",
    │          parsedDate = timeProvider.now(),
    │          parsedItems = null,
    │          currency = firstTx.currency ?: "EUR",
    │          confidence = 0.8f
    │          // ⚠️ NO documentType set!
    │          // ⚠️ NO sourceType set!
    │          // ⚠️ NO processingStatus set!
    │        )
    │
    └─ 4. For EACH parsed transaction:
           ├─ Normalize merchant (MerchantNormalizer)
           ├─ Auto-categorize (HybridExpenseClassifier)
           ├─ Deduplication check:
           │   ├─ Pre-fetch: pendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware()
           │   │   (merchantKey + dateWindow ±1h + amount ±0.01 + currency + type)
           │   ├─ Cross-source dedup: crossSourceDeduplication.resolvePendingReviewDuplicate()
           │   └─ Transactional check (inside withTransaction):
           │       ├─ hasExpenseDuplicateInRangeCurrencyAware() — checks Expense table
           │       └─ getPendingDuplicateCandidateInRangeTypeAware() — checks PendingReview table
           │       Outcomes: INSERTED, REPLACED_AND_INSERTED, SKIPPED_EXPENSE_DUPLICATE,
           │                 SKIPPED_PENDING_EXISTING, SKIPPED_DISCARD_NEW, SKIPPED_PENDING_DUPLICATE_RACE
           └─ Insert PendingReview (if not duplicate)
                   PendingReview(
                     scannedReceiptId = receiptId,
                     suggestedAmount, suggestedCurrency, suggestedMerchant,
                     suggestedDate, confidence, etc.
                   )

    ↓
Returns BatchResult(successCount, failureCount, errors, debugData)
    ↓
ReviewViewModel: stores debugData, shows success/error toast
```

### 4.2 Where parsed transactions are stored

- **ScannedReceipt** table: One row per statement (receiptId in the 90s range)
  - `parsedMerchant = "Bank Statement"` (hardcoded string, not a real merchant)
  - `parsedTotal = null` (legitimate — statement has many transactions)
  - `currency` = first transaction's currency or "EUR"
  - **No `documentType`, `sourceType`, or `processingStatus`** set in the legacy path

- **PendingReview** table: One row per parsed transaction
  - `scannedReceiptId` links back to the statement receipt
  - `suggestedAmount`, `suggestedCurrency`, `suggestedMerchant`, `suggestedDate`
  - `packageName = "statement.import"`
  - `notificationTitle = "Bank Screenshot"` / `"Bank Statement Transaction"`

### 4.3 The new (unused) lifecycle path

```
ReceiptLifecycleCoordinator.processBankStatement(uri) — EXISTS BUT UNUSED
    ↓
BankStatementLifecycleProcessor.processBankStatement(uri) (BankStatementLifecycleProcessor.kt:74)
    │
    ├─ 1. OCR: receiptRepository.runStatementOcr(uri)
    ├─ 2. Parse: bankStatementParser.parse(ocrResult.blocks)
    ├─ 3. Save ScannedReceipt WITH lifecycle metadata:
    │      sourceType = BANK_STATEMENT
    │      documentType = BANK_STATEMENT
    │      processingStatus = PARSED
    ├─ 4. Write RECEIPT_SAVED lifecycle event
    ├─ 5. For each transaction:
    │      ├─ Merchant normalization + auto-categorization
    │      ├─ Dedup: pendingReviewDao.getPendingByMerchant() [simpler than legacy]
    │      │   (merchantKey + exact name + amount diff < 0.01 + same currency)
    │      └─ Insert PendingReview
    ├─ 6. Write PROCESSING_COMPLETE lifecycle event
    └─ Returns BankStatementResult(receiptId, transactionsFound, reviewsCreated, duplicatesSkipped)
```

---

## 5. Issues Found

### 🔴 CRITICAL ISSUES

#### 5.1 Dual implementation: new lifecycle path is NOT wired to UI
- **Files:** `ReceiptLifecycleCoordinator.kt:470-472`, `ReviewViewModel.kt:724-750`, `ReviewScreen.kt:112-115`
- **Severity:** CRITICAL
- **Description:** The `ReceiptLifecycleCoordinator.processBankStatement()` → `BankStatementLifecycleProcessor.processBankStatement()` path is fully implemented but **never called from any production code**. The UI's "Import Bank Statement" button calls the legacy `ReceiptRepository.processStatement()` path instead.
- **Consequences:**
  - Statement receipts saved without `documentType = BANK_STATEMENT` — they appear as regular receipts
  - No lifecycle events written (no audit trail)
  - No gating in `ReceiptSideEffectDispatcher` — warranty extraction, receipt matching, item categorization may fire on bank statements
  - The `ReceiptMatchingWorker` (line 60) already checks `documentType == BANK_STATEMENT`, but since the legacy path doesn't set it, bank statement receipts may still be matched against expenses

#### 5.2 Legacy `ReceiptRepository.processStatement()` does NOT set `documentType`
- **File:** `ReceiptRepository.kt:598-608`
- **Severity:** CRITICAL
- **Evidence:** The `ScannedReceipt` constructor call (line 598-608) does NOT set `sourceType`, `documentType`, or `processingStatus`. These fields were added to the entity after this code was written. The new lifecycle path sets them correctly.
- **Impact:** Bank statement receipts are indistinguishable from regular OCR receipts in the database, causing incorrect behavior in warranty extraction, receipt matching, and side-effect dispatching.

#### 5.3 `ScannedReceipt` legacy constructor has no `documentType`/`sourceType` fields
- **File:** `ReceiptRepository.kt:598-608`
- **Severity:** HIGH
- **Evidence:** The `ScannedReceipt` data class likely has these fields as nullable with defaults. The constructor call omits them, so they remain null/empty. Any code filtering by `documentType == BANK_STATEMENT` will miss these receipts.

### 🟠 HIGH ISSUES

#### 5.4 `BankStatementParser` defaults `homeCurrency` to `"EUR"`
- **File:** `BankStatementParser.kt`, lines 81, 269, 396, 521
- **Severity:** HIGH
- **Evidence:** The `parse()` method signature: `fun parse(blocks: List<TextBlock>, homeCurrency: String = "EUR")`. The `ReceiptRepository.processStatement()` (line 583) calls `statementParser.parse(ocrResult.blocks)` without passing a currency, so it always defaults to EUR.
- **Impact:** Non-EUR bank statements will have their currency incorrectly set to EUR. Greek banks are typically EUR but Revolut multi-currency statements (GBP, USD) will be wrong unless the Revolut-specific path detects the symbol.
- **Known issue:** Documented in `session-ses_2204.md` line 2546 as "Phase 11.4 — BankStatementParser still defaults to 'EUR'"

#### 5.5 Two different deduplication strategies exist
- **Legacy path** (`ReceiptRepository.kt:629-721`): Complex 2-phase dedup with pre-fetch + transactional re-check, ±1h date window, ±€0.01 amount tolerance, currency-aware, type-aware.
- **New path** (`BankStatementLifecycleProcessor.kt:157-168`): Simpler dedup — `pendingReviewDao.getPendingByMerchant()` + exact amount difference < 0.01 + same currency. Lacks date-window and type-awareness.
- **Severity:** HIGH — inconsistencies can cause either duplicate imports or missed deduplication depending on which path runs.

#### 5.6 `ReceiptDuplicateDetector` is NOT used in either bank statement path
- **File:** `ReceiptDuplicateDetector.kt` (four fingerprint strategies: EXACT_HASH, TEXT_FINGERPRINT, SEMANTIC, EXTERNAL_ID)
- **Severity:** HIGH
- **Evidence:** Neither `ReceiptRepository.processStatement()` nor `BankStatementLifecycleProcessor.processBankStatement()` calls `ReceiptDuplicateDetector`. Both use ad-hoc `PendingReview`-level dedup only. This means:
  - Same statement imported twice → two `ScannedReceipt` rows (no hash/fingerprint check)
  - Individual transactions might be caught by the merchant+amount+date-window check, but the statement document itself is not deduplicated

#### 5.7 `ReceiptMatchingWorker` can match legacy-path bank statements as regular receipts
- **File:** `ReceiptMatchingWorker.kt:60-61`
- **Severity:** HIGH
- **Evidence:** The worker skips receipts with `documentType == BANK_STATEMENT`. But the legacy path doesn't set this field, so imported statement receipts have `documentType = null` or `UNKNOWN`. The worker will attempt to match them against purchase transactions, which is semantically wrong.

### 🟡 MEDIUM ISSUES

#### 5.8 Generic fallback amount detection can still pick running balance
- **File:** `BankStatementParser.kt:571-599`
- **Severity:** MEDIUM
- **Evidence:** The amount candidate scoring favors leftmost amounts over the rightmost (likely balance). But the fallback branch (line 591-596) uses `maxWithOrNull` on score, breaking ties by earliest position. If the transaction amount has no currency symbol and the balance does, the balance could win.
- **Regression test:** `BankStatementParserTest.kt:105-136` covers the basic case but edge cases (equal scores, missing currency symbols) are not tested.

#### 5.9 Greek NBG parser assumes fixed column positions
- **File:** `BankStatementParser.kt:407-472`
- **Severity:** MEDIUM
- **Evidence:** Assumes split by whitespace yields parts in a specific order: date (0), time (1), valeur (2), store code (3), transaction code (4), then merchant until `Χ`/`Π` indicator. Any variation in NBG statement format will cause parse failures.
- **Only one test** covers NBG format (`BankStatementParserTest.kt:29-69`), and it uses a simplified format without the full NBG column structure.

#### 5.10 `DebugDataStorage` persists debug JSON to world-readable file
- **File:** `DebugDataStorage.kt:24`
- **Severity:** MEDIUM
- **Evidence:** The file is stored at `context.filesDir/last_debug_data.json` with no encryption or permission restrictions. It contains full OCR text and parsed transaction data.

#### 5.11 `tryParseGreekNbgTransaction` catches exception but returns null at wrong indentation
- **File:** `BankStatementParser.kt:469-472`
- **Severity:** MEDIUM
- **Evidence:** The `return null` on line 471 is at incorrect indentation (inside catch block but visually confusing). The structure works correctly (returns null on exception) but suggests past editing issues.

#### 5.12 PendingReview dedup in new path lacks date window
- **File:** `BankStatementLifecycleProcessor.kt:157-168`
- **Severity:** MEDIUM
- **Evidence:** The dedup check only matches by `merchantKey` + `merchantName` + amount (within €0.01) + currency. It does NOT check date proximity. A recurring transaction (e.g., monthly Netflix) from a new statement would be falsely flagged as duplicate.

#### 5.13 No type-level dedup in new path's dedup
- **File:** `BankStatementLifecycleProcessor.kt:157-168`
- **Evidence:** The dedup does not consider `suggestedType`. A debit transaction and a credit transaction with the same merchant and amount would collide.

### 🟢 LOW ISSUES

#### 5.14 `parseEuropeanNumber()` method is redundant
- **File:** `BankStatementParser.kt:494-504`
- **Severity:** LOW
- **Evidence:** The method just calls `AmountUtils.parseAmount(cleaned)`. It's only used by `tryParseGreekNbgTransaction()` and adds no value beyond the utility method.

#### 5.15 `extractAllDates()` assigns `transactionDate = firstDate` always
- **File:** `BankStatementParser.kt:722-726`
- **Severity:** LOW
- **Evidence:** The `AllDatesResult` always sets `transactionDate = firstDate` regardless of header analysis. The header-derived `columnInfo.transactionDateOrder` is only used to choose between `allDates.firstDate` and `allDates.valueDate` in `extractTransactionFromRow()` (lines 648-658). The `transactionDate` field is effectively dead.

#### 5.16 Revolut date parser locale-dependent
- **File:** `BankStatementParser.kt:383`
- **Severity:** LOW
- **Evidence:** Uses `Locale.US` for parsing `MMM d yyyy`. While Revolut statements are typically in English, non-English device locales could theoretically cause issues if month names are localized in the OCR output.

#### 5.17 Hardcoded statement strings
- **File:** `BankStatementParser.kt` line 365: `merchant = "Revolut Transaction"`; ReceiptRepository.kt line 602: `parsedMerchant = "Bank Statement"`; BankStatementLifecycleProcessor.kt line 100: `parsedMerchant = "Bank Statement"`
- **Severity:** LOW
- These are display strings that should be localized or at least defined as constants.

#### 5.18 `ReceiptRepository.processStatement()` test coverage gap
- **Severity:** MEDIUM
- The `BankStatementParser` has unit tests (481 lines), but `ReceiptRepository.processStatement()` has no direct unit test. The legacy orchestration (OCR → parse → save → dedup → insert) is only covered by integration tests.

---

## 6. Recommendations

### P0: Immediate fixes (blocking correctness)

1. **Wire the new lifecycle path to the UI.** Replace `ReviewViewModel.processStatement()` → `receiptRepository.processStatement()` with `receiptLifecycleCoordinator.processBankStatement()`. This instantly fixes: missing `documentType`, missing lifecycle events, and missing side-effect gating.

2. **Migrate or delete the legacy `ReceiptRepository.processStatement()`.** Once the new path is wired, either remove the old method or deprecate it to prevent accidental use. Keep it only if `ReceiptScanViewModel` or other paths still reference it.

3. **Backfill `documentType` for existing statement receipts.** Run a migration or one-time update to set `documentType = BANK_STATEMENT` and `sourceType = BANK_STATEMENT` on receipts where `parsedMerchant = "Bank Statement"`.

### P1: High priority

4. **Pass `homeCurrency` to `BankStatementParser.parse()`.** The legacy path calls `statementParser.parse(ocrResult.blocks)` without currency. Inject `CurrencySettingsRepository` into the call site (or into the ViewModel) and pass the user's home currency.

5. **Unify deduplication strategy.** Align the legacy and new-path dedup logic. Add date-window and type-awareness to the new path's dedup. Consider using `ReceiptDuplicateDetector` for document-level dedup (hash/fingerprint) alongside the transaction-level dedup.

6. **Add `ReceiptDuplicateDetector` call to `BankStatementLifecycleProcessor`.** Before saving the `ScannedReceipt`, check if the same statement was already imported (by SHA-256 hash or OCR text fingerprint).

### P2: Medium priority

7. **Add comprehensive tests for `ReceiptRepository.processStatement()`.** The orchestration layer has no direct tests. Mock OCR and parser outputs, verify PendingReview insertion, dedup outcomes, and error handling.

8. **Add NBG format regression tests.** The current NBG test uses a simplified format. Add tests with the real column structure (date, time, valeur date, store code, transaction code, merchant, Χ/Π, amount).

9. **Fix generic fallback amount detection edge cases.** When amounts have equal heuristic scores, prefer the leftmost (transaction amount over balance). Add tests for multi-amount scenarios where the running balance has a currency symbol and the transaction amount does not.

10. **Review `DebugDataStorage` privacy.** Consider encrypting the debug data file or storing it only in memory, as it contains full OCR text and parsed transaction information.

### P3: Low priority / cleanup

11. **Remove `parseEuropeanNumber()`** and inline `AmountUtils.parseAmount()` calls.
12. **Remove dead `AllDatesResult.transactionDate` field** or use it properly.
13. **Extract hardcoded strings** ("Bank Statement", "Revolut Transaction", "Bank Screenshot") into constants.
14. **Clean up indentation** in `tryParseGreekNbgTransaction()` catch block.
