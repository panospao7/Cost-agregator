# Deep Analysis — Batch 44: Parsers — Remaining & Performance (@debugger)

## Scope
- domain/parser/parsers/RevolutParser.kt
- domain/parser/parsers/SmsParser.kt
- domain/performance/ImageCache.kt
- domain/performance/PerformanceMonitor.kt (NOT FOUND)
- domain/performance/PerformanceModels.kt (NOT FOUND)
- domain/price/PriceProtectionTracker.kt
- domain/receipt/BankStatementParser.kt
- domain/receipt/EnhancedMerchantExtractor.kt
- domain/receipt/MerchantRulesPolicy.kt
- domain/receipt/OcrLanguageProcessor.kt
- domain/receipt/OcrPreprocessingPipeline.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | SmsParser.kt:151-164 | **MAJOR** | Logic Error | SMS direction detection defaults ambiguous transfer/deposit messages to incoming, causing misclassified money flow. | 1. SMS: "Transfer of €50 processed". 2. No explicit "incoming" keyword. 3. Defaulted to incoming. 4. Money flow misclassified. | Return `null` for ambiguous transfers and only default deposits to incoming when explicit deposit evidence exists. |
| 2 | ImageCache.kt:23-29,46-53 | **MAJOR** | Logic Error | Image cache key ignores requested dimensions, so callers can receive a stale bitmap at the wrong size for the same URI. | 1. Request image at 100x100. 2. Cache stores with URI-only key. 3. Request same image at 200x200. 4. Gets 100x100 bitmap (stale/wrong size). | Include width/height in the cache key. |
| 3 | PriceProtectionTracker.kt:45-48,70-79,187-190 | **MAJOR** | Logic Error | Price-protection eligibility and remaining-window logic use `createdAt`/`Instant.now()` instead of parsed purchase date and injected time provider, producing wrong results for imported receipts and non-deterministic behavior. | 1. Import receipt with purchase date 6 months ago. 2. `createdAt` is today. 3. Price protection shows 90 days remaining instead of expired. | Use `parsedDate ?: createdAt` and replace direct clock access with `timeProvider.now()`. |
| 4 | PriceProtectionTracker.kt:199-209,248-279 | **MAJOR** | Logic Error | Production price-tracking paths fabricate price drops, deals, and coupons from hard-coded simulations, which can leak fake savings into the UI. | 1. User views price protection screen. 2. Fake price drops shown as real. 3. User expects savings that don't exist. | Move simulations behind debug/fake providers or return unavailable/empty states until real integrations exist. |
| 5 | PriceProtectionTracker.kt:355-365 | **MINOR** | Performance | Deals/coupons aggregation loads the full receipts table before trimming to 20 entries, which scales poorly. | 1. User has 10,000 receipts. 2. All loaded into memory. 3. Trimmed to 20. | Add a DAO query with `LIMIT 20`. |
| 6 | BankStatementParser.kt:445-473 | **MAJOR** | Logic Error | Generic bank-statement parsing prefers the highest-scored/largest amount candidate, so running balances can be selected instead of the actual transaction amount. | 1. Statement row: "€1,234.56 €5,000.00" (transaction + running balance). 2. Parser picks €5,000.00 as amount. 3. Wrong amount stored. | Make amount selection column-aware and avoid using magnitude as a primary tie-breaker. |
| 7 | BankStatementParser.kt:197-212 | **MAJOR** | Logic Error | Revolut statement amount parsing bypasses shared amount utilities and fails on thousands separators such as `1,234.56` / `1.234,56`. | 1. Revolut statement with "€1,234.56". 2. Parser fails on comma. 3. Amount rejected. | Parse extracted tokens via `AmountUtils.parseAmount()`. |
| 8 | BankStatementParser.kt:242-249,261-269 | **MAJOR** | Logic Error | Revolut statement rows are reduced to purchase/deposit only, misclassifying transfers, refunds, and withdrawals. | 1. Revolut statement with transfer row. 2. Parser classifies as purchase. 3. Transfer analytics corrupted. | Classify using description keywords plus inflow/outflow semantics and emit the correct transaction type. |
| 9 | BankStatementParser.kt:74-75,111-123,420,518-523 | **MINOR** | Dead Code | Date-column detection is computed but never applied, so the parser ignores the very header analysis it performs. | N/A — dead code. | Either use `columnInfo` when choosing transaction/value date or remove the dead logic. |
| 10 | EnhancedMerchantExtractor.kt:97-118,226-230 | **MINOR** | Logic Error | Merchant extraction can treat unlabeled total/amount lines as merchant candidates because `isPrice()` only rejects lines with both amount and currency token. | 1. Receipt line: "TOTAL €50.00". 2. `isPrice()` returns false (has both amount and currency). 3. Line considered as merchant candidate. | Exclude `TOTAL`/`AMOUNT`/`PAYMENT`-style lines even without currency symbols. |
| 11 | EnhancedMerchantExtractor.kt:35-46,70-76 | **MINOR** | Logic Error | A provided existing merchant is discarded when OCR candidates are empty/noisy, causing a fallback to `Unknown Merchant`. | 1. Existing merchant: "Starbucks". 2. OCR returns empty/noisy candidates. 3. Fallback to "Unknown Merchant" instead of keeping "Starbucks". | Fall back to the existing merchant with reduced confidence. |
| 12 | OcrLanguageProcessor.kt:52-57 | **MAJOR** | Logic Error | Non-Latin scripts are normalized with Latin-only rules after language detection, destroying Cyrillic/Arabic/CJK OCR text. | 1. OCR text in Cyrillic: "Привет". 2. Latin normalization destroys characters. 3. Text becomes garbage. | Add per-script normalization or preserve those scripts instead of routing through Latin normalization. |
| 13 | OcrLanguageProcessor.kt:139-151,162-175 | **MAJOR** | Logic Error | OCR amount extraction mishandles locale separators (`1.234,56` fails; comma-decimal Latin values like `25,50` become `2550`). | 1. OCR text: "€25,50". 2. Parser reads as 2550. 3. Wrong amount stored. | Reuse `AmountUtils.parseAmount()` or another shared locale-aware parser. |
| 14 | OcrPreprocessingPipeline.kt:223-237 | **MINOR** | Performance | Median denoising allocates and sorts a new list for every pixel, creating avoidable GC/CPU overhead on large images. | 1. Process large receipt image. 2. Thousands of pixel-level allocations. 3. GC pressure. | Use a fixed-size reusable buffer and median-selection routine. |
| 15 | OcrPreprocessingPipeline.kt, OcrLanguageProcessor.kt, EnhancedMerchantExtractor.kt | **MAJOR** | Architecture | OCR improvement components are registered in DI but not wired into the receipt OCR/parsing runtime path, so they currently provide no end-to-end benefit. | N/A — components exist but not connected. | Integrate them into `ReceiptOcrService` / parsing flow. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | SmsParser ↔ Transfer Pipeline | **MAJOR** | Misclassification | SMS direction detection defaults ambiguous transfers to incoming, misclassifying money flow. | Return `null` for ambiguous transfers. |
| C2 | ImageCache ↔ UI Components | **MAJOR** | Stale Bitmaps | Cache key ignores dimensions, so callers receive stale bitmaps at wrong sizes. | Include width/height in cache key. |
| C3 | PriceProtectionTracker ↔ UI | **MAJOR** | Fake Data in Production | Production paths fabricate price drops and deals from hard-coded simulations, leaking fake savings into the UI. | Move simulations behind debug providers. |
| C4 | BankStatementParser ↔ Amount Utilities | **MAJOR** | Amount Parsing Failures | Bank statement parser bypasses shared amount utilities, failing on thousands separators and misclassifying transaction types. | Use `AmountUtils.parseAmount()` and proper type classification. |
| C5 | OcrLanguageProcessor ↔ OCR Pipeline | **MAJOR** | Script Destruction | Non-Latin scripts are normalized with Latin-only rules, destroying Cyrillic/Arabic/CJK OCR text. | Add per-script normalization. |
| C6 | OCR Components ↔ ReceiptOcrService | **MAJOR** | Unwired Components | OCR improvement components are registered in DI but not wired into the runtime path, providing no end-to-end benefit. | Integrate into `ReceiptOcrService`. |

## Summary
- **Total issues: 21** (15 file-level + 6 cross-component)
- **Critical: 0**, **Major: 11**, **Minor: 4**
- **Files with issues: 9/9** analyzed (PerformanceMonitor.kt and PerformanceModels.kt not found)

## Key Patterns

### 1. Amount Parsing Failures
Bank statement parser and OCR language processor both fail on locale-specific number formats (thousands separators, comma decimals). Both should use shared `AmountUtils.parseAmount()`.

### 2. Fake Data in Production
Price protection tracker fabricates price drops and deals from hard-coded simulations in production code, leaking fake savings into the UI.

### 3. Unwired DI Components
OCR improvement components are registered in DI but not connected to the runtime path, providing no end-to-end benefit despite being fully implemented.

### 4. Script Destruction
OCR language processor normalizes all scripts through Latin-only rules, destroying Cyrillic/Arabic/CJK text.

### 5. Cache Key Incompleteness
Image cache key ignores dimensions, causing stale bitmaps at wrong sizes to be returned for the same URI.
