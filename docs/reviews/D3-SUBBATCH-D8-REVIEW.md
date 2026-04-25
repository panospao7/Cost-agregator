# D3 SubBatch D.8 Review

VERDICT: FAIL

Issues:
- [ISSUE-1] [MAJOR] `MASTER-ISSUE-REGISTRY.md` SubBatch D.8 is stale: 5 currently-open rows are now resolved, 1 row is partially resolved, and 1 existing resolved marker remains valid but should stay unchanged - `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` - apply the exact replacements under `Registry Update Instructions`

Coverage:
- Requirements met: yes - audited all 15 SubBatch D.8 rows against current code and checked relevant approved-plan references in `docs/plans/VALIDATED-PLAN-PHASE-C-D.md`, `PLAN-REMAINING-PHASE2-FIXES-V2.md`, `PLAN-PHASE2-OPEN-FIXES-V3.md`, and `AUDIT-PLAN-BATCHES-5-8.md`
- Testing adequate: no - no tests were run in this pass; conclusions are based on direct source inspection, and several D.8 utility items still lack focused regression coverage

## Plan Alignment

- Batch 10 / parser-utility hardening is only partially complete in current code: `CurrencyNormalizer`, `DateFormatterUtils`, and `StringDistanceUtils` align with the approved fixes, but `ClipboardAmountParser`, `AmountUtils`, `MerchantCleaner`, `Money.format()`, `HapticFeedback`, and email fingerprint uniqueness remain open.
- The recurring-income cleanup plan is also only partially complete: grouping no longer uses raw merchant strings, but blank merchants are still not filtered out.

## SubBatch D.8 Audit

1. `ClipboardAmountParser` regex grabs partial match on thousands-formatted values — anchor whole-token matching (B23-missed)  
   **Status:** STILL_OPEN  
   **Evidence:** `ClipboardAmountParser.kt:8` still uses an unanchored `("""(?:€|$|EUR)?\s*(\d{1,6}[\.,]\d{2})\s*(?:€|$|EUR)?""")` pattern, and `ClipboardAmountParser.kt:17-21` still uses `regex.find(text)`, so a grouped value like `1,234.56` can still be matched as the partial tail `234.56` instead of the whole token.

2. `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed)  
   **Status:** RESOLVED  
   **Evidence:** `CsvExpenseImporter.kt:118-148` now uses a fixed palette of 6-digit `#RRGGBB` colors only, and `Category.kt:20-22` still validates the same 6-digit format.  
   **Suggested registry wording:**
   ```
   - `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed) **[RESOLVED - importer category colors are now limited to 6-digit `#RRGGBB` values, matching `Category` validation]**
   ```

3. `AmountUtils` comma-group validation accepts `1,0000` — require 3-digit chunks (B23)  
   **Status:** STILL_OPEN  
   **Evidence:** `AmountUtils.kt:93-106` still accepts any all-digit comma groups when group sizes are consistent; `1,0000` produces a single 4-digit post-comma group, so it still passes and is normalized instead of being rejected.

4. `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23)  
   **Status:** RESOLVED  
   **Evidence:** `CurrencyNormalizer.kt:18` now uppercases with `Locale.ROOT`.  
   **Suggested registry wording:**
   ```
   - `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23) **[RESOLVED - currency normalization now uppercases with `Locale.ROOT`]**
   ```

5. `MerchantCleaner` stop-word stripping truncates at first internal `" at"` — strip only anchored positions (B23)  
   **Status:** STILL_OPEN  
   **Evidence:** `MerchantCleaner.kt:17-20` still includes `at` in `stopWords`, and `MerchantCleaner.kt:33-35` still truncates on `candidate.indexOf(" $stop", ignoreCase = true)`, so internal text such as `"Store at Mall"` is still cut at the first internal ` at`.

6. `Money.format()` depends on device locale — use fixed locale or `BigDecimal.toPlainString()` (B23)  
   **Status:** STILL_OPEN  
   **Evidence:** `Money.kt:139` still uses `String.format("%.2f", amount)` without an explicit locale, so formatting remains device-locale dependent.

7. `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23)  
   **Status:** RESOLVED  
   **Evidence:** `DateFormatterUtils.kt:11-29` now defines a bounded 16-entry LRU cache, and the old `ThreadLocal` formatter cache is gone.  
   **Suggested registry wording:**
   ```
   - `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23) **[RESOLVED - `DateFormatterUtils` no longer uses a `ThreadLocal` formatter cache and now keeps a bounded 16-entry LRU cache]**
   ```

8. `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23)  
   **Status:** RESOLVED  
   **Evidence:** `DateFormatterUtils.kt:13-16` defines `FormatterCacheKey(pattern, locale)`, and `DateFormatterUtils.kt:31-37` now keys the formatter cache by both values.  
   **Suggested registry wording:**
   ```
   - `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23) **[RESOLVED - formatter cache keys now include both pattern and locale]**
   ```

9. `HapticFeedback` uses `CONFIRM`/`REJECT` without pre-30 fallback — gate on `SDK_INT` (B23)  
   **Status:** STILL_OPEN  
   **Evidence:** `HapticFeedback.kt:13-19` still unconditionally uses `HapticFeedbackConstants.CONFIRM` and `REJECT`, while `app/build.gradle.kts:15` shows `minSdk = 26`, so the planned pre-30 fallback gate is still missing.

10. `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23)  
    **Status:** RESOLVED  
    **Evidence:** `StringDistanceUtils.kt:7-8` now hoists the regexes to object-level constants, and `StringDistanceUtils.kt:126-127` reuses them inside `isFuzzyMatch(...)`.  
    **Suggested registry wording:**
    ```
    - `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23) **[RESOLVED - emoji/noise stripping regexes are now object-level constants reused across calls]**
    ```

11. `EmailReceiptSource.fingerprint` is primary dedupe lookup but schema only adds non-unique index — make unique (B13-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `EmailReceiptSource.kt:23-29` still defines a non-unique fingerprint index, `EmailReceiptDao.kt:50-51` still resolves dedupe with `LIMIT 1`, and `EmailReceiptIngestionService.kt:169-173` still treats fingerprint as a primary duplicate check before insert.

12. `GroupTransactionCoordinator.deleteGroup()` always returns `true` — return affected-row count (B11-missed)  
    **Status:** STILL_OPEN  
    **Evidence:** `GroupTransactionCoordinator.kt:333-339` still returns `true` whenever `archiveGroup(groupId)` does not throw, and `ExpenseGroupDao.kt:63-64` still returns `Unit`, so nonexistent groups are still reported as successful deletions.

13. `InvestmentTracker.getValuesBetween()` returns ascending, `getInvestmentPerformance()` reads `firstOrNull()` for day change — use `lastOrNull()` (B27-missed) **[RESOLVED BY B.4 — Batch 10 / late closeout (ISSUE-B4-11): `recentValues.lastOrNull()` on ASC-ordered window confirmed correct]**  
    **Status:** RESOLVED  
    **Evidence:** `InvestmentTracker.kt:90-95,238-240` now derives day change from `getPreviousDayCloseSnapshot(...)` / `getLatestValueBefore(...)`; the old ascending-list `firstOrNull()` bug is no longer present.  
    **Suggested registry wording:** No change - the existing `[RESOLVED BY B.4 ...]` marker still matches current code.

14. `FinancialHealthScoreV2.saveToHistory()` read-then-insert without uniqueness guarantee — add unique constraint, use UPSERT (B41)  
    **Status:** STILL_OPEN  
    **Evidence:** `FinancialHealthScoreV2.kt:522-550` still does `getHistoryForPeriod(...)` followed by `update(...)` or `insert(...)`, while `HealthScoreHistory.kt:12-18` still defines only a non-unique index on `(periodStart, periodEnd)`, so concurrent saves can still race and create duplicate rows.

15. `RecurringIncomeTracker` groups deposits by raw merchant including blank — skip blank merchants (B41)  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `RecurringIncomeTracker.kt:37-49` now groups deposits by `MerchantKeyGenerator.generate(it.merchant)` instead of raw merchant text, but it still does not filter blank merchants; `MerchantKeyGenerator.kt:43-48` returns an empty key for blank names, so blank deposits can still be grouped together and surface a blank `source`.  
    **Suggested registry wording:**
    ```
    - `RecurringIncomeTracker` now groups deposits by canonical merchant key, but still keeps blank merchants instead of skipping them (B41) **[PARTIALLY_RESOLVED - grouping switched from raw merchant strings to `MerchantKeyGenerator.generate(...)`, but blank merchant rows are still grouped under an empty key and can surface a blank source name]**
    ```

## Registry Update Instructions

Apply the following exact replacements under `### SubBatch D.8` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`:

1. Replace
   ```
   - `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed)
   ```
   with
   ```
   - `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed) **[RESOLVED - importer category colors are now limited to 6-digit `#RRGGBB` values, matching `Category` validation]**
   ```

2. Replace
   ```
   - `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23)
   ```
   with
   ```
   - `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23) **[RESOLVED - currency normalization now uppercases with `Locale.ROOT`]**
   ```

3. Replace
   ```
   - `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23)
   ```
   with
   ```
   - `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23) **[RESOLVED - `DateFormatterUtils` no longer uses a `ThreadLocal` formatter cache and now keeps a bounded 16-entry LRU cache]**
   ```

4. Replace
   ```
   - `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23)
   ```
   with
   ```
   - `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23) **[RESOLVED - formatter cache keys now include both pattern and locale]**
   ```

5. Replace
   ```
   - `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23)
   ```
   with
   ```
   - `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23) **[RESOLVED - emoji/noise stripping regexes are now object-level constants reused across calls]**
   ```

6. Replace
   ```
   - `RecurringIncomeTracker` groups deposits by raw merchant including blank — skip blank merchants (B41)
   ```
   with
   ```
   - `RecurringIncomeTracker` now groups deposits by canonical merchant key, but still keeps blank merchants instead of skipping them (B41) **[PARTIALLY_RESOLVED - grouping switched from raw merchant strings to `MerchantKeyGenerator.generate(...)`, but blank merchant rows are still grouped under an empty key and can surface a blank source name]**
   ```

7. Leave the other 8 still-open D.8 bullets unchanged.

8. Leave the existing `InvestmentTracker... **[RESOLVED BY B.4 ...]**` line unchanged; it still matches current code.

## Batch 6 Registry Sync Addendum

- D8-15 (`RecurringIncomeTracker` blank canonical merchant keys): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: blank/empty canonical keys are now filtered before recurring-income grouping.
