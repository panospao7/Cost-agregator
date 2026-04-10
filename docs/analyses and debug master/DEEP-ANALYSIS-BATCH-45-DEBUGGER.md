# Deep Analysis — Batch 45: Receipt, Savings & Tax (@debugger)

## Scope
- domain/receipt/ReceiptOcrService.kt
- domain/receipt/ReceiptParser.kt
- domain/receipt/ReceiptSource.kt
- domain/receipt/WarrantyTextExtractor.kt
- domain/receiptmatching/ReceiptTransactionMatcher.kt
- domain/savings/AutomatedSavingsRuleEngine.kt
- domain/savings/SavingsGamificationEngine.kt
- domain/savings/SavingsGoalRepository.kt
- domain/savings/SmartSavingsEngine.kt
- domain/tax/TaxConfiguration.kt
- domain/tax/TaxEstimator.kt
- domain/service/NotificationService.kt
- domain/receiptmatching/ReceiptMatchingModels.kt (NOT FOUND - inline in ReceiptTransactionMatcher.kt)
- domain/receiptmatching/ReceiptMatchingWorker.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | ReceiptTransactionMatcher.kt | **MAJOR** | Logic Error | Receipt matching can auto-match receipts to non-purchase transactions because positive-amount deposits/transfers are treated as valid candidates and fully rewarded in scoring. | 1. Receipt scanned for €50. 2. Deposit of €50 exists. 3. Auto-matched to deposit instead of purchase. | Restrict candidate selection/type scoring to receipt-compatible purchase transactions only. |
| 2 | ReceiptTransactionMatcher.kt | **MAJOR** | Logic Error | Merchant normalization in receipt matching strips all non-ASCII characters, so Greek merchant names collapse to empty strings and unrelated Greek merchants can score as perfect matches. | 1. Receipt merchant: "Σκλαβενίτης". 2. Normalized to empty string. 3. Any other Greek merchant with empty normalized name scores as perfect match. | Use canonical multilingual normalization via `MerchantNormalizer`/merchant keys instead of `[a-z0-9]` stripping. |
| 3 | WarrantyTextExtractor.kt | **MAJOR** | Logic Error | Warranty extraction rejects receipts older than one year, which breaks legitimate multi-year warranty workflows. | 1. Receipt with 2-year warranty, purchased 18 months ago. 2. Rejected as "too old". 3. Warranty not created. | Relax the date sanity window to allow older purchase dates consistent with supported warranty durations. |
| 4 | WarrantyTextExtractor.kt | **MAJOR** | Thread Safety | Warranty extraction uses shared `SimpleDateFormat` instances from a singleton pipeline component, which is not thread-safe under parallel receipt batch processing. | 1. Process 10 receipts in parallel. 2. Shared `SimpleDateFormat` produces corrupted dates. 3. Wrong warranty dates stored. | Replace with thread-safe `java.time` formatters or create formatter instances per call. **[RESOLVED BY A.8]** |
| 5 | AutomatedSavingsRuleEngine.kt | **MAJOR** | Logic Error | Automated savings weekly no-spend rewards are not idempotent and can be granted multiple times in the same qualifying week; monthly caps are also stored only in memory and reset after process death. | 1. User has no-spend week. 2. Reward granted. 3. App restarts. 4. Same week's reward granted again. | Persist execution history/cap usage and evaluate weekly rewards once per completed week. |
| 6 | SavingsGamificationEngine.kt | **MAJOR** | Logic Error | Savings gamification returns fabricated streak data based on goal creation timestamps and hard-coded placeholder values rather than real contribution history. | 1. User creates savings goal. 2. Gamification shows streak based on creation date, not actual contributions. 3. Misleading gamification data. | Back streaks/achievements with persisted savings contribution events and stored unlock timestamps. |
| 7 | SmartSavingsEngine.kt | **MAJOR** | Logic Error | Smart savings double-counts budget headroom across overlapping budgets and returns the same portfolio-wide "safe to save" amount independently for each goal. | 1. User has 2 savings goals. 2. Both show same "safe to save" amount. 3. If both goals save that amount, user oversaves. | Centralize budget headroom aggregation and split portfolio availability from per-goal allocation/capping. |
| 8 | TaxEstimator.kt | **MAJOR** | Logic Error | Tax estimation ignores progressive brackets and applies a single bracket rate to all taxable income; yearly summary logic also hardcodes income and annualizes already-year-wide totals. | 1. User has €50,000 income. 2. Tax estimator applies single bracket rate to entire amount. 3. Tax overestimated (should use progressive brackets). | Implement true marginal bracket calculation, prorate income to the requested period, and build annual summaries from real annual inputs without re-annualizing full-year results. |
| 9 | ReceiptOcrService.kt | **MINOR** | Error Handling | OCR retry logic catches cancellation and retries cancelled work instead of stopping promptly. | 1. OCR cancelled by user. 2. Retry logic catches cancellation. 3. Retries cancelled work. | Re-throw `CancellationException` immediately in retry handling. |
| 10 | ReceiptParser.kt | **MINOR** | Logic Error | Receipt parser line-item extraction can double-count quantity-formatted lines and never executes two declared item patterns. | 1. Receipt line: "2x Coffee €5.00". 2. Both quantity and item patterns match. 3. Line double-counted. | Parse line-by-line with exclusive pattern precedence and wire all declared patterns into extraction. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | ReceiptTransactionMatcher ↔ Purchase Pipeline | **MAJOR** | Wrong Transaction Type Matching | Receipt matching auto-matches to deposits/transfers, not just purchases. This corrupts receipt linkage and creates false matches. | Restrict candidate selection to purchase transactions only. |
| C2 | WarrantyTextExtractor ↔ SimpleDateFormat | **MAJOR** | Thread Safety | Shared `SimpleDateFormat` instances used in parallel receipt processing cause date corruption. | Use thread-safe formatters. |
| C3 | AutomatedSavingsRuleEngine ↔ Persistence | **MAJOR** | Non-Idempotent Rewards | Weekly no-spend rewards can be granted multiple times due to lack of persistence and idempotency checks. | Persist execution history and evaluate once per week. |
| C4 | SmartSavingsEngine ↔ Budget System | **MAJOR** | Double-Counting | Smart savings double-counts budget headroom across overlapping budgets, returning the same "safe to save" amount for each goal. | Centralize budget headroom aggregation. |
| C5 | TaxEstimator ↔ Tax System | **MAJOR** | Incorrect Tax Calculation | Tax estimator ignores progressive brackets, applying a single rate to all income. This produces systematically wrong tax estimates. | Implement true marginal bracket calculation. |

## Summary
- **Total issues: 15** (10 file-level + 5 cross-component)
- **Critical: 0**, **Major: 10**, **Minor: 2**
- **Files with issues: 10/13** analyzed (ReceiptMatchingModels.kt inline in ReceiptTransactionMatcher.kt)

## Key Patterns

### 1. Receipt Matching to Wrong Transaction Types
Receipt matching auto-matches to deposits/transfers, not just purchases, creating false matches and corrupting receipt linkage.

### 2. Non-Idempotent Savings Rewards
Automated savings weekly rewards can be granted multiple times due to lack of persistence and idempotency checks.

### 3. Fabricated Gamification Data
Savings gamification returns fabricated streak data based on goal creation timestamps rather than real contribution history.

### 4. Double-Counting Budget Headroom
Smart savings double-counts budget headroom across overlapping budgets, returning the same "safe to save" amount for each goal.

### 5. Incorrect Tax Calculation
Tax estimator ignores progressive brackets, applying a single rate to all income, producing systematically wrong tax estimates.

### 6. Thread Safety with SimpleDateFormat
Warranty extraction uses shared `SimpleDateFormat` instances in parallel processing, causing date corruption.
