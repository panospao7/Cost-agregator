# D3 SubBatch D.7 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.7 is stale: 5 rows are resolved in current code but are still listed as open - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacement lines under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 14 SubBatch D.7 issues against current code, classified each, captured brief evidence, and provided exact registry replacement text where status changes
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection of the current worktree

## SubBatch D.7 Audit

1. `WidgetStyleConfig` accepts any string key but persistence only restores allowlisted set — validate at boundary (B47)  
   **Status:** STILL_OPEN  
   **Evidence:** `WidgetStyleConfig.setStyle()` still accepts any `widgetId` and blindly stores it in the map (`WidgetStyle.kt:28-29`), while `WidgetStyleRepositoryImpl.parseConfig()` restores only `StyledWidgets.all` entries (`WidgetStyleRepositoryImpl.kt:50-57`). The boundary mismatch remains.  
   **Suggested registry wording if status should change:** No change.

2. `DashboardCategoryBreakdown.changeFromLastPeriod` hardcoded to `0.0` — calculate or remove (B47)  
   **Status:** STILL_OPEN  
   **Evidence:** `DashboardContractsAdapter.observeCategoryBreakdown()` still builds `DashboardCategoryBreakdown(... changeFromLastPeriod = 0.0)` (`DashboardContractsAdapter.kt:156-167`).  
   **Suggested registry wording if status should change:** No change.

3. `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47)  
   **Status:** RESOLVED  
   **Evidence:** `BudgetStatusSnapshot` now declares `percentUsed: Double` (`BudgetStatusSnapshot.kt:5-14`), so the numeric-type mismatch in this model is gone.  
   **Suggested registry wording:**
   ```
   - `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47) **[RESOLVED - `BudgetStatusSnapshot.percentUsed` is now stored as `Double`, matching the rest of the amount fields]**
   ```

4. `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed)  
   **Status:** RESOLVED  
   **Evidence:** `ComputeDashboardWidgetsUseCase` now preloads a `categoryNameById` map and assigns `categoryName = expense.categoryId?.let { categoryNameById[it] }` (`ComputeDashboardWidgetsUseCase.kt:420-452`) instead of stringifying the ID.  
   **Suggested registry wording:**
   ```
   - `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed) **[RESOLVED - `DomainExpenseSummary.categoryName` is now resolved from the preloaded category map instead of stringifying `categoryId`]**
   ```

5. `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed)  
   **Status:** RESOLVED  
   **Evidence:** `DashboardWidgetUiMapper` no longer fabricates `Expense` rows; it maps `DomainExpenseSummary` to lightweight `TransactionSummary` DTOs (`DashboardWidgetUiMapper.kt:19-26`). There is no hardcoded `PURCHASE` transaction type in the current mapper.  
   **Suggested registry wording:**
   ```
   - `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed) **[RESOLVED - the mapper now converts `DomainExpenseSummary` into lightweight `TransactionSummary` DTOs instead of fabricating synthetic `Expense` entities with a hardcoded transaction type]**
   ```

6. `CategoryRepository.learnMerchantCategory()` inserts without `normalizedCanonicalName` and without cache invalidation — route through engine path (B38)  
   **Status:** RESOLVED  
   **Evidence:** `CategoryRepository.learnMerchantCategory()` now delegates to `categorizationEngine.learnMerchantCategory()` (`CategoryRepository.kt:93-95`), and that engine path creates the mapping with `normalizedCanonicalName` and invalidates cache (`CategorizationEngine.kt:452-467`, `475-482`).  
   **Suggested registry wording if status should change:** No change (already marked resolved in the registry).

7. `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed)  
   **Status:** RESOLVED  
   **Evidence:** current `CategoryKeywords.kt` contains only a single `"roasters" to 0.85` entry (`CategoryKeywords.kt:49-55`); the duplicate declaration is gone.  
   **Suggested registry wording:**
   ```
   - `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed) **[RESOLVED - `CategoryKeywords` now contains only one `"roasters"` entry]**
   ```

8. `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed)  
   **Status:** RESOLVED  
   **Evidence:** keyword regexes are now built with Unicode-aware boundary lookarounds in `buildKeywordRegex()` (`SemanticKeywordMatcher.kt:140-147`) instead of blanket `\b...\b` wrapping, so punctuation-edge tokens are handled by the matcher logic.  
   **Suggested registry wording:**
   ```
   - `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed) **[RESOLVED - keyword matching now uses Unicode-aware boundary lookarounds instead of blanket `\b...\b` wrappers]**
   ```

9. `AppleReceiptParser.detectCurrency()` uses raw substring checks — match bounded tokens (B31-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `detectCurrency()` still uppercases the body and returns the first currency whose indicator list satisfies `text.contains(it)` (`AppleReceiptParser.kt:185-200`), including short unbounded markers like `"US"`, `"FR"`, `"DE"`, and `"IT"`.  
   **Suggested registry wording if status should change:** No change.

10. `UberReceiptParser` same currency detection issue (B31-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `UberReceiptParser.detectCurrency()` still uses the same raw `text.contains(it)` strategy (`UberReceiptParser.kt:232-247`) with short unbounded indicators like `"US"`, `"GR"`, `"DE"`, `"FR"`, and `"IT"`.  
    **Suggested registry wording if status should change:** No change.

11. `UberReceiptParser.parseUberDate()` fills in current year for year-less dates — derive from email `receivedAt` year (B31-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `parseUberDate()` still derives `currentYear = Calendar.getInstance().get(Calendar.YEAR)` and applies it when parsed dates default to 1970 (`UberReceiptParser.kt:203-224`); it does not use `receivedAt` to choose the year.  
    **Suggested registry wording if status should change:** No change.

12. `WarrantyTextExtractor` "date at start of line" regex not compiled with `MULTILINE` — add flag (B45-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** the start-of-line date pattern is still `Pattern.compile("^(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4})", Pattern.CASE_INSENSITIVE)` (`WarrantyTextExtractor.kt:144-145`) with no `Pattern.MULTILINE`, so `^` only matches the start of the full text.  
    **Suggested registry wording if status should change:** No change.

13. `Expense.splitTemplateId` has no FK — add nullable FK (B12-missed)  
    **Status:** RESOLVED  
    **Evidence:** `Expense` now declares a Room FK from `splitTemplateId` to `SplitTemplate.id` with `onDelete = SET_NULL` (`Expense.kt:10-31`), and the migration rebuild also adds the FK/index (`AppDatabase.kt:4722-4802`).  
    **Suggested registry wording if status should change:** No change (already marked resolved in the registry).

14. `PendingReview.suggestedType` stored as raw `String` — validate against allowed names (B12-missed)  
    **Status:** RESOLVED  
    **Evidence:** the migration rebuild now enforces `CHECK(suggestedType IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER', 'DEPOSIT', 'UNKNOWN'))` and coerces invalid legacy values before copy (`AppDatabase.kt:4650-4671`), matching the existing resolved marker.  
    **Suggested registry wording if status should change:** No change (already marked resolved in the registry).

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.7` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47)
   ```
   with
   ```
   - `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47) **[RESOLVED - `BudgetStatusSnapshot.percentUsed` is now stored as `Double`, matching the rest of the amount fields]**
   ```

2. Replace
   ```
   - `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed)
   ```
   with
   ```
   - `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed) **[RESOLVED - `DomainExpenseSummary.categoryName` is now resolved from the preloaded category map instead of stringifying `categoryId`]**
   ```

3. Replace
   ```
   - `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed)
   ```
   with
   ```
   - `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed) **[RESOLVED - the mapper now converts `DomainExpenseSummary` into lightweight `TransactionSummary` DTOs instead of fabricating synthetic `Expense` entities with a hardcoded transaction type]**
   ```

4. Replace
   ```
   - `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed)
   ```
   with
   ```
   - `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed) **[RESOLVED - `CategoryKeywords` now contains only one `"roasters"` entry]**
   ```

5. Replace
   ```
   - `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed)
   ```
   with
   ```
   - `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed) **[RESOLVED - keyword matching now uses Unicode-aware boundary lookarounds instead of blanket `\b...\b` wrappers]**
   ```

6. Leave the other 9 SubBatch D.7 bullets unchanged; they match current code status.

## Batch 6 Registry Sync Addendum

- D7-11 (`UberReceiptParser.parseUberDate()` year anchoring): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: year-less Uber dates are now anchored to `receivedAt` with near-year-boundary future-date clamping.
