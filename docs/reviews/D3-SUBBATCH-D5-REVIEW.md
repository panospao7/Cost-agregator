# D3 SubBatch D.5 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.5 is stale: 8 rows are resolved and 1 row is partially resolved in current code but are still listed as fully open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 21 SubBatch D.5 rows against current code and classified each with brief evidence
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree

## SubBatch D.5 Audit

1. `Disk cache` never evicts — add size/age-based pruning (B44)  
   **Status:** PARTIALLY_RESOLVED  
   **Evidence:** `ImageCache.kt:23-25` now defines `MAX_CACHE_SIZE_BYTES = 50MB`, and `rebuildIndex()/touchCacheEntry()/updateCacheEntry()` all call `evictIfNeededLocked()` (`ImageCache.kt:129-181`). I found no age-based pruning logic; eviction is size-only.  
   **Suggested registry wording:**
   ```
   - `Disk cache` now evicts by size, but still lacks age-based pruning for stale entries (B44) **[PARTIALLY_RESOLVED - `ImageCache` now enforces a 50MB size cap via `evictIfNeededLocked()`, but no age-based pruning exists]**
   ```

2. `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44)  
   **Status:** RESOLVED  
   **Evidence:** `BankStatementParser.parse()` still computes `columnInfo` (`BankStatementParser.kt:87-88`), and `extractTransactionFromRow(..., columnInfo)` now uses `columnInfo.transactionDateOrder` to choose between first/value dates (`BankStatementParser.kt:631-645`).  
   **Suggested registry wording:**
   ```
   - `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44) **[RESOLVED - detected date-column order now flows into `extractTransactionFromRow(...)` and is used when selecting the transaction date]**
   ```

3. `EnhancedMerchantExtractor.isPrice()` only filters lines with currency token — reject total/amount lines without currency (B44)  
   **Status:** STILL_OPEN  
   **Evidence:** `isPrice()` still requires both a decimal amount and a currency token (`EnhancedMerchantExtractor.kt:226-230`), so lines like `TOTAL 123.45` remain eligible merchant candidates through the top-of-receipt fallback (`EnhancedMerchantExtractor.kt:122-129`).

4. `EnhancedMerchantExtractor` drops known merchant when OCR yields no candidates — fall back to existingMerchant (B44)  
   **Status:** STILL_OPEN  
   **Evidence:** `extractMerchant()` only returns `existingMerchant` if `verifyExistingMerchant()` finds a close OCR candidate (`EnhancedMerchantExtractor.kt:35-45`); when `candidates` is empty, verification returns `null` (`EnhancedMerchantExtractor.kt:190-209`) and the method falls through to `Unknown Merchant` (`EnhancedMerchantExtractor.kt:59-76`).

5. `OcrPreprocessingPipeline` median-filter allocates new list per pixel — use reusable buffer (B44)  
   **Status:** STILL_OPEN  
   **Evidence:** `denoise()` still allocates `val neighbors = mutableListOf<Int>()` inside the inner pixel loop and sorts it for every pixel (`OcrPreprocessingPipeline.kt:223-237`).

6. `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43)  
   **Status:** RESOLVED  
   **Evidence:** `parseAndValidate()` converts totals and split values to integer minor units / basis points before validation (`CustomSplitParser.kt:52-55`, `102-145`), with conversion enforced through `toMinorUnitsOrNull()` (`CustomSplitParser.kt:159-167`).  
   **Suggested registry wording:**
   ```
   - `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43) **[RESOLVED - totals and split values are now converted to integer minor units / basis points before sum validation]**
   ```

7. `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43)  
   **Status:** RESOLVED  
   **Evidence:** `CUSTOM_AMOUNT`/`UNEQUAL` values are rejected unless `toMinorUnitsOrNull()` can represent them exactly in cents; fractional-cent inputs now fail validation (`CustomSplitParser.kt:109-114`, `159-167`).  
   **Suggested registry wording:**
   ```
   - `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43) **[RESOLVED - amount splits now reject fractional-cent values via exact minor-unit validation]**
   ```

8. `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43)  
   **Status:** RESOLVED  
   **Evidence:** recurring detection now groups by `canonicalMerchantKey(it.merchant, it.merchantKey)` (`RecurringExpenseEngine.kt:42`, `50`), and that helper prefers stored `merchantKey` before canonical generation fallback (`RecurringExpenseEngine.kt:142-147`).  
   **Suggested registry wording:**
   ```
   - `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43) **[RESOLVED - recurring grouping now prefers stored `merchantKey` and otherwise falls back to canonical merchant-key generation]**
   ```

9. `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43)  
   **Status:** RESOLVED  
   **Evidence:** `synthesizeInternal()` sanitizes `pastSumDaily` first (`SynthesisEngine.kt:99`), `lastKnownTotal` is read from the sanitized series (`SynthesisEngine.kt:173`), and `sanitizePastSumDaily()` replaces non-finite points with the last finite value (`SynthesisEngine.kt:541-549`).  
   **Suggested registry wording:**
   ```
   - `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43) **[RESOLVED - past spending series is sanitized before tail lookup and forecast emission]**
   ```

10. `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43)  
    **Status:** RESOLVED  
    **Evidence:** date parsing now uses `LocalDate.parse(...)` with `DateTimeFormatter` and `ResolverStyle.STRICT` across supported formats (`GenericTransactionParser.kt:226-314`).  
    **Suggested registry wording:**
    ```
    - `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43) **[RESOLVED - notification date parsing now uses strict `java.time` / `LocalDate` parsing with `ResolverStyle.STRICT`]**
    ```

11. `GreekBankParser` direction detection doesn't recognize Latin codes — extend detection (B43)  
    **Status:** STILL_OPEN  
    **Evidence:** transfer parsing explicitly treats single-letter Latin `D`/`C` as direction metadata, not merchant (`GreekBankParser.kt:258-260`), but `DEBIT_CODES`/`CREDIT_CODES` and `detectGreekDirection()` still only recognize Greek codes plus full-word `DEBIT`/`CREDIT` (`GreekBankParser.kt:33-34`, `298-317`).

12. `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed)  
    **Status:** RESOLVED  
    **Evidence:** `BillReminderManager` now delegates next-date advancement and monthly normalization to `RecurrenceCalculator` (`BillReminderManager.kt:101-123`), and `RecurrenceCalculator` explicitly handles `SEMI_ANNUALLY` and `ANNUALLY` (`RecurrenceCalculator.kt:41-43`, `65-79`).  
    **Suggested registry wording:**
    ```
    - `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed) **[RESOLVED - reminder date advancement and monthly-cost conversion now delegate to `RecurrenceCalculator`, which explicitly handles `SEMI_ANNUALLY` and `ANNUALLY`]**
    ```

13. `ComputeDashboardWidgetsUseCase` keeps only `overallBudget` as resolved limit — resolve as `overall-or-category-sum` (B43-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `buildContext()` still sets `totalBudgetAmount` from `overallBudget?.budgetAmount ?: 0.0` only (`ComputeDashboardWidgetsUseCase.kt:291-315`), and that unresolved limit is still reused in runway/block-party/safe-to-spend paths (`ComputeDashboardWidgetsUseCase.kt:391-417`, `707-708`).

14. `CalculateBudgetStatusUseCase.getBudgetHealth()` ignores `CRITICAL` — count explicitly (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** `getBudgetHealth()` only counts `EXCEEDED` and `WARNING`; `CRITICAL` is neither counted nor represented in `overallStatus`, and falls into `healthyCount` (`CalculateBudgetStatusUseCase.kt:25-39`).

15. `ComputeDashboardWidgetsUseCase` budget summary says "all on track" when nothing EXCEEDED — treat non-ON_TRACK as non-healthy (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** `computeBudgetSummary()` still checks only `EXCEEDED`; otherwise it returns `WIDGET_ALL_BUDGETS_ON_TRACK`, even when budgets are `WARNING` or `CRITICAL` (`ComputeDashboardWidgetsUseCase.kt:547-552`).

16. `ReviewExpenseUseCase` returns Success when categoryId is null — require non-null category (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** `ReviewExpenseUseCase.invoke()` still calls `expenseRepository.updateExpenseCategory(expenseId, categoryId)` and returns `Result.Success` without rejecting `null` (`ExpenseUseCases.kt:79-82`).

17. `ProcessReceiptUseCase` coerces missing merchant/total to "Unknown"/0.0 with no review signal — return incomplete result (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** receipt processing still normalizes missing merchant to `"Unknown"` (`ProcessReceiptUseCase.kt:48-50`) and missing amount to `0.0` (`ProcessReceiptUseCase.kt:54-57`) without an incomplete/review-needed result.

18. `LifestyleSavingsPromptUseCase` maxCap becomes 0 but coerceAtLeast(1.0) forces 1% uplift — handle zero rates explicitly (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** `maxCap = currentSavingsRatePercent * MAX_SAVINGS_CAP_PERCENT` can still be `0.0` (`LifestyleSavingsPromptUseCase.kt:98-99`), but emitted `suggestedMonthlyUplift` is still forced to at least `1.0` (`LifestyleSavingsPromptUseCase.kt:122-125`).

19. `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48)  
    **Status:** RESOLVED  
    **Evidence:** `allocationPercentage` is now derived from the final capped allocation, `state.allocated / safeSweepAmount`, after all allocation passes complete (`MonthlySavingsSweepUseCase.kt:333-343`).  
    **Suggested registry wording:**
    ```
    - `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48) **[RESOLVED - allocationPercentage is now derived from finalized allocated amounts, not the pre-cap urgency share]**
    ```

20. `ComputeMoneyRadarUseCase` depends directly on `AnomalyAlertDao` — introduce repository interface (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** the use case still imports and injects `AnomalyAlertDao` directly (`ComputeMoneyRadarUseCase.kt:4`, `93-100`) and reads alerts from `anomalyAlertDao.getActiveAlerts()` (`ComputeMoneyRadarUseCase.kt:221-239`).

21. `MonthlySavingsSweepUseCase` redefines `effectiveAmount` locally — use canonical property (B48)  
    **Status:** STILL_OPEN  
    **Evidence:** `Expense` now exposes a canonical `effectiveAmount` property in the entity (`Expense.kt:125-130`), but `MonthlySavingsSweepUseCase` still declares its own duplicate private extension with the same ownership logic (`MonthlySavingsSweepUseCase.kt:481-487`).

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.5` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `Disk cache` never evicts — add size/age-based pruning (B44)
   ```
   with
   ```
   - `Disk cache` now evicts by size, but still lacks age-based pruning for stale entries (B44) **[PARTIALLY_RESOLVED - `ImageCache` now enforces a 50MB size cap via `evictIfNeededLocked()`, but no age-based pruning exists]**
   ```

2. Replace
   ```
   - `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44)
   ```
   with
   ```
   - `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44) **[RESOLVED - detected date-column order now flows into `extractTransactionFromRow(...)` and is used when selecting the transaction date]**
   ```

3. Replace
   ```
   - `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43)
   ```
   with
   ```
   - `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43) **[RESOLVED - totals and split values are now converted to integer minor units / basis points before sum validation]**
   ```

4. Replace
   ```
   - `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43)
   ```
   with
   ```
   - `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43) **[RESOLVED - amount splits now reject fractional-cent values via exact minor-unit validation]**
   ```

5. Replace
   ```
   - `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43)
   ```
   with
   ```
   - `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43) **[RESOLVED - recurring grouping now prefers stored `merchantKey` and otherwise falls back to canonical merchant-key generation]**
   ```

6. Replace
   ```
   - `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43)
   ```
   with
   ```
   - `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43) **[RESOLVED - past spending series is sanitized before tail lookup and forecast emission]**
   ```

7. Replace
   ```
   - `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43)
   ```
   with
   ```
   - `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43) **[RESOLVED - notification date parsing now uses strict `java.time` / `LocalDate` parsing with `ResolverStyle.STRICT`]**
   ```

8. Replace
   ```
   - `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed)
   ```
   with
   ```
   - `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed) **[RESOLVED - reminder date advancement and monthly-cost conversion now delegate to `RecurrenceCalculator`, which explicitly handles `SEMI_ANNUALLY` and `ANNUALLY`]**
   ```

9. Replace
   ```
   - `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48)
   ```
   with
   ```
   - `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48) **[RESOLVED - allocationPercentage is now derived from finalized allocated amounts, not the pre-cap urgency share]**
   ```

10. Leave the other 12 SubBatch D.5 bullets unchanged; they are still open in current code.
