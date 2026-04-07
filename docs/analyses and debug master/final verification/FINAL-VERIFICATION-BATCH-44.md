# Final Verification — Batch 44: Parsers — Remaining & Performance

## Scope
- `com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt`
- `com/yourname/expensetracker/domain/parser/parsers/SmsParser.kt`
- `com/yourname/expensetracker/domain/performance/ImageCache.kt`
- `com/yourname/expensetracker/domain/price/PriceProtectionTracker.kt`
- `com/yourname/expensetracker/domain/receipt/BankStatementParser.kt`
- `com/yourname/expensetracker/domain/receipt/EnhancedMerchantExtractor.kt`
- `com/yourname/expensetracker/domain/receipt/MerchantRulesPolicy.kt`
- `com/yourname/expensetracker/domain/receipt/OcrLanguageProcessor.kt`
- `com/yourname/expensetracker/domain/receipt/OcrPreprocessingPipeline.kt`
- Supporting integration files read during verification:
  - `com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
  - `com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
  - `com/yourname/expensetracker/ui/screens/price/PriceProtectionViewModel.kt`
  - `com/yourname/expensetracker/di/OcrImprovementsModule.kt`
  - `com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt`
  - `com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`
  - `com/yourname/expensetracker/data/repository/MerchantRulesRepository.kt`
  - `com/yourname/expensetracker/domain/util/AmountUtils.kt`
  - `com/yourname/expensetracker/domain/util/CommonPatterns.kt`
  - `com/yourname/expensetracker/domain/util/MerchantCleaner.kt`
  - `com/yourname/expensetracker/domain/util/MerchantKeyGenerator.kt`
  - `com/yourname/expensetracker/domain/util/StringDistanceUtils.kt`
  - `com/yourname/expensetracker/domain/parser/ParsedTransactionEnums.kt`
- Missing from repository: `com/yourname/expensetracker/domain/performance/PerformanceMonitor.kt`, `com/yourname/expensetracker/domain/performance/PerformanceModels.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/parser/parsers/SmsParser.kt:151-164` | Medium | Logic | `detectSmsDirection()` returns `INCOMING` on tie/unknown cases for both `DEPOSIT` and `TRANSFER`. Ambiguous transfer SMS messages are therefore labeled as incoming money instead of unknown direction. | B | DOWNGRADED | Return `null` for ambiguous `TRANSFER` cases; only default deposits to incoming when the message has explicit credit/deposit evidence. |
| 2 | `domain/performance/ImageCache.kt:23-29,46-53` | Medium | Correctness / Performance | Cache entries are keyed only by `uri.toString().hashCode()`, so requests for the same URI at different target sizes can reuse the wrong bitmap variant. | B | CONFIRMED | Include normalized URI + requested dimensions in the cache key. |
| 3 | `domain/performance/ImageCache.kt:17-18,83-100` | Medium | Resource Management | The disk cache never evicts by age or size and grows until `clearCache()` is called manually. | R | CONFIRMED | Add size/age-based pruning and handle cleanup failures explicitly. |
| 4 | `domain/price/PriceProtectionTracker.kt:45-48,70-79,187-190` | High | Business Logic | Price-protection eligibility and remaining-window calculations use `receipt.createdAt` and direct `Instant.now()` calls instead of `receipt.parsedDate` and the injected `TimeProvider`, so imported/old receipts can be shown as eligible. | B | CONFIRMED | Use `receipt.parsedDate ?: receipt.createdAt` as the purchase date and replace direct clock access with `timeProvider.now()`. |
| 5 | `domain/price/PriceProtectionTracker.kt:199-209,248-279` | High | Functional Bug | Production paths fabricate price drops, competitor deals, and coupons from hard-coded heuristics; `PriceProtectionViewModel` exposes those results directly to users. | B | CONFIRMED | Move simulated data behind debug/fake providers or return explicit unavailable states until real integrations exist. |
| 6 | `domain/price/PriceProtectionTracker.kt:355-365` | Medium | Performance | `getDealsCouponsAndBenefits()` loads the full receipts table with `receiptDao.getAll()` and only then trims to 20 items. | B | CONFIRMED | Add a DAO query with `LIMIT 20` or use paging. |
| 7 | `domain/receipt/BankStatementParser.kt:445-473` | High | Logic | Generic amount selection breaks ties by largest absolute value, so rows containing both transaction amount and running balance can select the balance instead of the transaction amount. | B | CONFIRMED | Make amount selection column-aware and prefer transaction-amount columns over balance columns. |
| 8 | `domain/receipt/BankStatementParser.kt:197-212` | High | Parsing | Revolut statement parsing strips currency symbols and blindly replaces commas with dots, so amounts with thousands separators (for example `1,234.56` or `1.234,56`) fail. | B | CONFIRMED | Pass the extracted amount token to `AmountUtils.parseAmount()` instead of manual replacement logic. |
| 9 | `domain/receipt/BankStatementParser.kt:242-249,261-269` | High | Business Logic | The Revolut statement path emits only `DEPOSIT` or `PURCHASE`, so transfers, top-ups, refunds, and withdrawals are misclassified even when description text is explicit. | B | CONFIRMED | Classify from description keywords plus inflow/outflow semantics and emit the correct transaction type. |
| 10 | `domain/receipt/BankStatementParser.kt:74-75,111-123,420,518-523` | Medium | Dead Logic / Incorrect Behavior | Header/date-column detection is computed but never used; the parser always treats the first parsed date as the transaction date regardless of detected headers. | B | CONFIRMED | Either apply `columnInfo` when choosing transaction vs. value date or remove the unused header-analysis path. |
| 11 | `domain/receipt/EnhancedMerchantExtractor.kt:97-118,226-230` | Medium | Extraction Logic | `isPrice()` only filters lines containing both a decimal amount and a currency token, so lines like `TOTAL 123.45` remain eligible merchant candidates. | B | CONFIRMED | Reject total/amount/payment-style lines even when no currency symbol is present. |
| 12 | `domain/receipt/EnhancedMerchantExtractor.kt:35-46,70-76` | Medium | Fallback Logic | When a trusted `existingMerchant` is provided but OCR yields no verifiable candidates, the extractor drops the known merchant and falls back to `Unknown Merchant`. | B | CONFIRMED | Fall back to `existingMerchant` with reduced confidence when OCR candidates are empty or noisy. |
| 13 | `domain/receipt/OcrLanguageProcessor.kt:52-57` | High | Internationalization | `normalizeForLanguage()` routes Cyrillic, Arabic, and CJK text through Latin-only normalization after detecting those scripts, destroying most characters. | B | CONFIRMED | Add per-script normalizers or preserve those scripts instead of sending them through `normalizeLatinText()`. |
| 14 | `domain/receipt/OcrLanguageProcessor.kt:139-151,162-175` | High | Incorrect Calculation | OCR amount extraction mishandles locale-specific separators: comma-decimal Latin values are inflated (`25,50` → `2550`), and thousands-separated Greek values are parsed incorrectly or truncated. | B | CONFIRMED | Reuse `AmountUtils.parseAmount()` for both Greek and Latin amount extraction. |
| 15 | `domain/receipt/OcrPreprocessingPipeline.kt:223-237` | Medium | Performance | The median-filter denoiser allocates a new list and sorts it for every pixel, creating avoidable CPU and GC overhead on large images. | B | CONFIRMED | Use a fixed-size reusable buffer/array and a small median-selection routine. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/parser/parsers/RevolutParser.kt:37-54` | Medium | Parsing | Revolut notification regexes only accept a single decimal separator (`\d+[.,]\d{2}`), so notifications with thousands-separated amounts (for example `€1,234.56`) are not parsed at all. | Broaden the amount token regex to accept grouped numbers (for example `[\d.,]+`) and delegate final parsing to `AmountUtils.parseAmount()`. |
| 2 | `domain/parser/parsers/SmsParser.kt:37-42,118-120` | Medium | Parsing | `SmsParser` uses the same one-separator amount regex, which can reject or misread thousands-separated values by matching only part of the number. | Capture the full amount token and parse it with `AmountUtils.parseAmount()` instead of constraining the regex to `\d+[.,]\d{2}`. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R Cross-Component #3` | `domain/util/MerchantCleaner.kt:13-55; data/repository/MerchantRulesRepository.kt:117-137; domain/receipt/EnhancedMerchantExtractor.kt:215-221` | The codebase does have multiple merchant-cleaning helpers, but they serve different stages (receipt-header extraction, generic parser cleanup, OCR-candidate sanitization). The report does not demonstrate a concrete failing path, and downstream canonical matching already normalizes through `MerchantKeyGenerator`, so this is architectural overlap rather than a verified bug. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | OCR enhancement runtime path | High | Integration Gap | `OcrPreprocessingPipeline`, `OcrLanguageProcessor`, and `EnhancedMerchantExtractor` are registered in DI but never injected into `ReceiptOcrService` or `ReceiptParser`, so the batch's OCR-improvement components currently have no runtime effect. | `di/OcrImprovementsModule.kt`, `domain/receipt/OcrPreprocessingPipeline.kt`, `domain/receipt/OcrLanguageProcessor.kt`, `domain/receipt/EnhancedMerchantExtractor.kt`, `domain/receipt/ReceiptOcrService.kt`, `domain/receipt/ReceiptParser.kt` | Inject and apply preprocessing before OCR, run language-aware normalization after OCR, and use enhanced merchant extraction before persisting parsed receipts. |
| 2 | Revolut ingestion consistency | High | Inconsistent Business Rules | `RevolutParser` emits richer Revolut semantics (`TRANSFER`, `WITHDRAWAL`, incoming/outgoing transfers), while `BankStatementParser` collapses similar Revolut rows to `PURCHASE` or `DEPOSIT`, producing different downstream analytics for the same bank family. | `domain/parser/parsers/RevolutParser.kt`, `domain/receipt/BankStatementParser.kt` | Extract a shared Revolut transaction-classification helper and use it in both notification and statement parsers. |
| 3 | Price-protection UI data provenance | High | Placeholder Data Leakage | `PriceProtectionViewModel` directly exposes `monitorPriceDrops()` and `getDealsCouponsAndBenefits()`, so the tracker’s simulated pricing/coupon outputs become user-visible opportunities. | `domain/price/PriceProtectionTracker.kt`, `ui/screens/price/PriceProtectionViewModel.kt` | Put real/debug providers behind interfaces and expose explicit unavailable states until production data sources exist. |

## Summary
- Total verified issues: 15 file-level issues (plus 3 retained cross-component pipeline issues)
- Confirmed: 15 (Critical: 0, High: 7, Medium: 8, Low: 0)
- False positives: 1
- Missed issues found: 2
- Files affected: 8/9 scoped source files

## Key Patterns
- Locale-aware amount parsing is still inconsistent across parsers: multiple components bypass `AmountUtils` and regress on grouped or comma-decimal amounts.
- Several price-protection paths still ship placeholder/simulated production data instead of clearly separated fake providers.
- OCR improvement work exists as isolated components, but the end-to-end OCR pipeline is not wired to consume it.
- Statement parsing still relies on position-agnostic heuristics where column-aware logic is needed (amount selection, date-column interpretation, Revolut type mapping).
