# Deep Analysis — Batch 44: Parsers — Remaining & Performance (@reviewer)

## Scope
- `domain/parser/parsers/RevolutParser.kt`
- `domain/parser/parsers/SmsParser.kt`
- `domain/performance/ImageCache.kt`
- `domain/performance/PerformanceMonitor.kt` *(not present in repository)*
- `domain/performance/PerformanceModels.kt` *(not present in repository)*
- `domain/price/PriceProtectionTracker.kt`
- `domain/receipt/BankStatementParser.kt`
- `domain/receipt/EnhancedMerchantExtractor.kt`
- `domain/receipt/MerchantRulesPolicy.kt`
- `domain/receipt/OcrLanguageProcessor.kt`
- `domain/receipt/OcrPreprocessingPipeline.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/parser/parsers/SmsParser.kt:151-164` | HIGH | Logic | `detectSmsDirection()` defaults tie/unknown cases to `INCOMING`. Ambiguous or keyword-light transfer SMS messages therefore get recorded as incoming money instead of unknown/outgoing. | Return `null` on ties for `TRANSFER`, and only default deposits to `INCOMING` when there is explicit deposit evidence. |
| 2 | `domain/performance/ImageCache.kt:23-29,46-53` | MEDIUM | Correctness / Performance | The cache key is based only on `uri.toString().hashCode()`. Requested dimensions are ignored, so a previously cached large decode can be returned for a later thumbnail request of the same URI. | Include normalized URI + requested width/height in a stable key. |
| 3 | `domain/performance/ImageCache.kt:17-18,83-100` | MEDIUM | Resource Management | Disk cache has no eviction policy, TTL, or size cap. Every unique URI is kept until `clearCache()` is called, so cache growth is unbounded. | Add max-size / age-based pruning (or use `DiskLruCache`-style behavior) and log cleanup failures. |
| 4 | `domain/price/PriceProtectionTracker.kt:45-48,70-79,187-190` | HIGH | Business Logic | Eligibility and alert windows are calculated from `receipt.createdAt` and `Instant.now()` instead of the parsed purchase date and injected `TimeProvider`. Imported old receipts can appear eligible, and time behavior is non-deterministic in tests/runtime. | Use `receipt.parsedDate ?: receipt.createdAt` as purchase date, and replace all `Instant.now()` calls with `timeProvider.now()`. |
| 5 | `domain/price/PriceProtectionTracker.kt:199-209,248-279` | HIGH | Functional Bug | Core production paths fabricate price drops, competitor deals, and coupons from hard-coded heuristics. `PriceProtectionViewModel` consumes these methods directly, so users can see fake savings/opportunities. | Move simulations behind a debug/fake provider, or return empty/unavailable states until a real pricing/coupon data source exists. |
| 6 | `domain/price/PriceProtectionTracker.kt:355-365` | MEDIUM | Performance | `getDealsCouponsAndBenefits()` loads the entire receipts table with `receiptDao.getAll()` and then keeps only `take(20)`. This does unnecessary DB and allocation work as the table grows. | Add a DAO query with `LIMIT 20` (or paging) and fetch only the needed rows. |
| 7 | `domain/receipt/BankStatementParser.kt:445-473` | HIGH | Logic | Generic row parsing selects the "best" amount by score and then by largest absolute value. On rows that contain both transaction amount and running balance, the larger balance value is likely to win. | Make amount selection column-aware, prefer the transaction amount column over balance columns, and avoid using magnitude as the main tie-breaker. |
| 8 | `domain/receipt/BankStatementParser.kt:197-212` | HIGH | Parsing | Revolut statement parsing bypasses `AmountUtils` and converts commas to dots manually. Amounts with thousands separators (for example `€1,234.56` or `€1.234,56`) fail to parse. | Parse the extracted token with `AmountUtils.parseAmount()` instead of ad-hoc replacement logic. |
| 9 | `domain/receipt/BankStatementParser.kt:242-249,261-269` | HIGH | Business Logic | The Revolut statement path collapses rows into only `DEPOSIT` or `PURCHASE`. Transfers, refunds, and ATM withdrawals are misclassified even when the description explicitly says `Transfer to/from`, `Top-up`, or similar. | Classify by description keywords + money direction, and emit `TRANSFER` / `WITHDRAWAL` / refund-specific types where applicable. |
| 10 | `domain/receipt/BankStatementParser.kt:74-75,111-123,420,518-523` | MEDIUM | Dead Logic / Incorrect Behavior | Header/date-column detection is computed but never used. The parser always treats the first detected date as the transaction date, even when statement headers indicate a different column order. | Either apply `columnInfo` when selecting `transactionDate` vs `valueDate`, or remove the dead feature to avoid misleading behavior/comments. |
| 11 | `domain/receipt/EnhancedMerchantExtractor.kt:97-118,226-230` | MEDIUM | Extraction Logic | `isPrice()` only filters lines that contain both a decimal amount and a currency token. Lines such as `TOTAL 123.45` or `AMOUNT 15.00` remain eligible merchant candidates and can outrank the real merchant. | Treat labeled total/amount/payment lines as non-merchants even without a currency symbol. |
| 12 | `domain/receipt/EnhancedMerchantExtractor.kt:35-46,70-76` | MEDIUM | Fallback Logic | When a trusted `existingMerchant` is provided but OCR yields no verifiable candidates, the extractor discards the known merchant and returns `Unknown Merchant`. | Fall back to `existingMerchant` with lower confidence when OCR is empty/noisy instead of dropping it. |
| 13 | `domain/receipt/OcrLanguageProcessor.kt:52-57` | HIGH | Internationalization | After correctly detecting `CYRILLIC`, `ARABIC`, or `CJK`, the code still normalizes with Latin-only rules. That strips most script-specific characters and destroys the OCR text. | Add per-script normalizers, or at minimum preserve those scripts instead of routing them through `normalizeLatinText()`. |
| 14 | `domain/receipt/OcrLanguageProcessor.kt:139-151,162-175` | HIGH | Incorrect Calculation | Amount extraction mishandles localized separators: Greek values with thousands separators fail (`1.234,56` → invalid), while Latin comma-decimal values are inflated (`25,50` → `2550`). | Reuse `AmountUtils.parseAmount()` (or another shared locale-aware parser) for both Greek and Latin amount extraction. |
| 15 | `domain/receipt/OcrPreprocessingPipeline.kt:223-237` | MEDIUM | Performance | The median-filter denoiser allocates a new `MutableList` and sorts it for every pixel. On large receipt bitmaps this creates heavy GC pressure and avoidable CPU cost. | Use a fixed-size reusable buffer/array and a small median-selection routine instead of per-pixel list allocation + sort. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | OCR enhancement pipeline | HIGH | Integration Gap | `OcrPreprocessingPipeline`, `OcrLanguageProcessor`, and `EnhancedMerchantExtractor` are provided through DI but are not wired into `ReceiptOcrService` / `ReceiptParser`. The batch adds OCR-improvement components that currently have no runtime effect. | Inject and apply preprocessing before OCR, pass language normalization into parsing, and use enhanced merchant extraction as a post-OCR merchant-resolution step. |
| 2 | Revolut parsing consistency | HIGH | Inconsistent Business Rules | `RevolutParser` correctly models transfers/withdrawals from app notifications, while `BankStatementParser`'s Revolut path reduces similar rows to purchase/deposit only. Same source family, different semantics. | Share a common Revolut transaction-classification helper between notification and bank-statement parsing paths. |
| 3 | Merchant normalization flow | MEDIUM | Fragmentation | Merchant cleanup/matching is split across `MerchantCleaner`, `MerchantRulesPolicy`, and `EnhancedMerchantExtractor.cleanMerchantName()`, each with different rules. This increases inconsistent merchant naming and weakens downstream canonical matching. | Centralize merchant normalization/cleanup behind one domain service used by all parsers and OCR flows. |
| 4 | Price-protection UI pipeline | HIGH | Placeholder Data Leakage | `PriceProtectionViewModel` directly surfaces `PriceProtectionTracker.monitorPriceDrops()` and `getDealsCouponsAndBenefits()`, so the tracker’s simulated outputs propagate into user-facing state. | Put external pricing/coupon providers behind interfaces and expose explicit `unavailable` / `not yet implemented` states instead of synthetic data. |

## Summary
- Total issues: 15
- Critical: 0, High: 8, Medium: 7, Low: 0
- Files with issues: 7/9 present files *(2 planned files — `PerformanceMonitor.kt`, `PerformanceModels.kt` — are missing from the repository and could not be reviewed)*

## Key Patterns
- Several modules contain placeholder or simulation logic in production code paths (`PriceProtectionTracker`) instead of cleanly separated fake providers.
- Parsing logic is fragmented: similar concepts (merchant normalization, Revolut classification, localized amount parsing) are implemented differently across components instead of sharing one trusted utility.
- OCR-improvement components exist but are not integrated into the end-to-end OCR pipeline, so the codebase pays maintenance cost without receiving runtime benefit.
- Performance-sensitive image/OCR code still contains avoidable allocation patterns and unbounded caching behavior.
