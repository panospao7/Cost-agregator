# Search / Reports / Query Interpretation — Cross-Check Review

**Review date:** 2026-05-02  
**Source analysis:** `docs\analyses and debug master\search-reports-query-analysis.md`  
**Current branch:** working tree (post `master-refactor`)

## VERDICT: FAIL

Critical and high issues remain unresolved across both the legacy natural-language search path and the AI assistant query path.

---

# Cross-check results (29 original issues)

Each issue from the original analysis is re-examined against the current codebase.

| # | Original title | Status | Evidence |
|---|---|---|---|
| 1 | Legacy merchant extraction broken (lowercase) | **STILL PRESENT** | `NaturalLanguageSearchEngine.kt:135` lowercases, line 282 uses `[A-Z]` regex on lowercased text |
| 2 | Legacy search extracts but doesn't apply filters | **STILL PRESENT** | `executeSearch():163-199` — locations, categories extracted but never used in filtering |
| 3 | Legacy amount filtering: raw amount, exact Double, no currency | **STILL PRESENT** | Line 182-188 uses `expense.amount` (not `effectiveAmount`), exact `==` comparison, no currency |
| 4 | Assistant history persisted when disabled | **RESOLVED** | `AiChatRepositoryImpl.kt:45,64` — both `createSession()` and `appendMessage()` check `shouldPersistHistory()` (= `storeConversationHistory`) |
| 5 | Assistant broad queries load full result sets | **PARTIALLY RESOLVED** | `executeCount` and `executeList` now use SQL `SELECT COUNT(*)`. But `executeTotal`, `executeAverage`, `executeCategoryBreakdown`, `executeMerchantBreakdown`, `executeLargest` still load ALL matching rows via `assistantFilteredExpenses()` |
| 6 | Mixed-currency sorting raw numeric | **PARTIALLY RESOLVED** | Category/merchant breakdowns (lines 91–98, 128–136) now use `currencyConverter.convertMultiple()` for cross-currency sorting. But `executeLargest` line 161 still uses raw `effectiveAmount` without conversion |
| 7 | Assistant min/max amount filters not currency-aware | **STILL PRESENT** | `ExpenseRepository.kt:273-280` — `effectiveAmount >= ?` / `<= ?` with no currency filter. `ExpenseQueryFilters` has no `currency` field |
| 8 | Multi-filter drilldown broader than answer | **STILL PRESENT** | `MapFinancialQueryToNavigationUseCase.kt:20-22` — still uses `singleOrNull()`; multi-value categories/merchants/types are dropped |
| 9 | Local fallback defaults missing periods to current month | **PARTIALLY RESOLVED** | `ExecuteFinancialQueryUseCase.kt:41-44` now returns `Clarification` if period is null. But `InterpretFinancialQueryUseCase.resolvePeriod()` line 297 still defaults to current month, so the interpretation layer always fills a period and the execution-layer clarification is never triggered |
| 10 | "This week" inconsistent semantics | **STILL PRESENT** | Bare "this week" (line 190-199) uses rolling 7 days. Richer queries (line 292) use `getWeekRange(now, 0)` = calendar week |
| 11 | Previous-period comparison uses raw duration | **STILL PRESENT** | `ExecuteFinancialQueryUseCase.kt:260-263` — `PeriodRange(period.start - duration, period.start)` still uses raw millisecond arithmetic |
| 12 | AI query output validation too weak | **STILL PRESENT** | `OnDeviceQueryInterpretationService.kt:172-173` — `optDoubleOrNull` with no finite/non-negative/min≤max validation. Period only checks `endMs > startMs` (line 251) |
| 13 | Uncategorized spend disappears from breakdown | **STILL PRESENT** | `ExecuteFinancialQueryUseCase.kt:88` — `.filter { it.expense.categoryId != null }` still excludes uncategorized |
| 14 | Merchant filtering depends on merchantKey only | **STILL PRESENT** | `ExpenseRepository.kt:262-269` — only `merchantKey IN (...)`; no fallback to `merchant COLLATE NOCASE IN (...)` |
| 15 | Date parsing uses device date directly | **RESOLVED** | `NaturalLanguageSearchEngine.kt:14` — `TimeProvider` is now injected and used throughout |
| 16 | "Last month" rolling vs calendar | **RESOLVED** | Lines 37–47 now use calendar-month semantics: `end.withDayOfMonth(1).minusMonths(1)` for start, `end.withDayOfMonth(1).minusDays(1)` for end |
| 17 | Ambiguous numeric date parsing (DD/MM vs MM/DD) | **STILL PRESENT** | Line 95–103 still hardcodes day/month/year interpretation |
| 18 | Invalid date parse can throw | **PARTIALLY RESOLVED** | Line 101 `LocalDate.of(year, month, day)` still has no try/catch. ViewModel catches it (line 103–106) but shows generic error, not clarification |
| 19 | Legacy search fetches broad date range | **STILL PRESENT** | `resolveDateRangeMillis()` line 201–209 defaults to `0L → now` when no date range extracted |
| 20 | Hybrid query interpretation no runtime fallback | **STILL PRESENT** | `HybridQueryInterpretationService.kt:27-32` — single routing decision; CLOUD→unsupported skips ON_DEVICE |
| 21 | Query prompt exposes merchant list to cloud | **STILL PRESENT** | No UI notice that cloud queries include merchant/category context when redaction is off. The `toCloudPromptInput()` aliasing exists but only when redaction is on |
| 22 | Query intent model lacks currency/source/status filters | **STILL PRESENT** | `ExpenseQueryFilters` (FinancialQueryModels.kt:44-54) unchanged — still missing currency, payment method, review status, source, receipt state, etc. |
| 23 | Assistant results not labeled exact vs partial | **STILL PRESENT** | `FinancialQueryResult` sealed interface has no metadata about interpretation source, confidence, or exactness |
| 24 | Export paging inconsistent during data changes | **STILL PRESENT** | `DeterministicExpenseExportPager.kt:23-38` — still offset-based (`LIMIT :pageSize OFFSET :offset`) without read transaction |
| 25 | Accountant PDF includes non-expense types | **STILL PRESENT** | `ExpenseDao.kt:939-940` — export query filters only by date + `isNotMine = 0`, no transactionType filter. `AccountingExportRepository` bypasses policy for PDF (line 140-145) |
| 26 | Accountant PDF period shows exclusive end | **STILL PRESENT** | `AccountantReportPdfExporter.kt:39` — `formatMonth(startDate) - formatMonth(endDate)` does not adjust for half-open range |
| 27 | Large transaction threshold raw 500 | **PARTIALLY RESOLVED** | PDF now groups by currency (line 40-41, 65) so threshold applies per-currency group. But the fixed `500.0` (line 280) still treats 500 JPY the same as 500 EUR |
| 28 | Accounting export files omit currency columns | **RESOLVED** | All three exporters (QuickBooks line 38, Xero line 76, FreshBooks line 135) now include `currency` column |
| 29 | Export files in cache, no encryption | **STILL PRESENT** | `AccountingExportRepository.kt:94` — still writes to `cacheDir/exports` via FileProvider; no encryption/redaction option |

---

# New issues discovered (not in original analysis)

### [ISSUE-N1] [HIGH] AI prompt schema has no amount filter fields

- **Where:** `OnDeviceQueryInterpretationService.kt:94-103` (`buildPrompt()` JSON schema)
- **Problem:** The JSON schema instructs the on-device/cloud model to return `periodKeyword`, `categoryNames`, `merchantNames`, `transactionTypes`, etc. — but **not** `minAmount` or `maxAmount`. Yet `parseStructured()` (lines 172–173) tries to read them via `optDoubleOrNull("minAmount")` / `optDoubleOrNull("maxAmount")`.
- **Impact:** AI interpretation of queries like *"expenses over $50"* or *"under €20"* will **never** produce amount filters. The model has no way to communicate price thresholds because the prompt schema doesn't include the fields.
- **Fix:** Add `"minAmount": null, "maxAmount": null` to the JSON schema in the prompt, and add rules about when to populate them.

### [ISSUE-N2] [LOW] Dead code: date pattern that always returns null

- **Where:** `NaturalLanguageSearchEngine.kt:114-119`
- **Problem:** The pattern `Regex("over \\$(\\d+)")` is registered as a `datePatterns` extractor but its `extractor` always returns `null`. It is a no-op that adds confusion.
- **Fix:** Remove this pattern from `datePatterns`, or move the "over $" matching to `extractAmounts`.

### [ISSUE-N3] [MINOR] Tight coupling in CloudQueryInterpretationService

- **Where:** `CloudQueryInterpretationService.kt:42`
- **Problem:** `private val promptHelper = OnDeviceQueryInterpretationService()` instantiates the on-device service directly (not via DI). This bypasses Hilt, making testing harder and creating an undeclared dependency.
- **Fix:** Inject the prompt helper or extract a shared `FinancialQueryPromptFormatter` class.

---

# Status summary

| Status | Count |
|---|---|
| RESOLVED | 5 (issues 4, 15, 16, 28) |
| PARTIALLY RESOLVED | 5 (issues 5, 6, 9, 18, 27) |
| STILL PRESENT | 19 (issues 1, 2, 3, 7, 8, 10, 11, 12, 13, 14, 17, 19, 20, 21, 22, 23, 24, 25, 26, 29) |
| NEW issues found | 3 (N1, N2, N3) |

**Resolved count:** 5 of 29 (17%)  
**Unresolved count:** 24 of 29 (83%) — plus 3 new

---

# Resolved issues — detail

### [ISSUE-4] RESOLVED — Assistant history persistence gated

`AiChatRepositoryImpl` now checks `storeConversationHistory` at both `createSession()` (line 45) and `appendMessage()` (line 64). Sessions are not created when history is disabled, and messages are not appended. The ViewModel still calls `persistUserTurn`/`persistAssistantTurn` without its own check, but the repository-layer guard prevents actual writes.

### [ISSUE-15] RESOLVED — TimeProvider injected

`NaturalLanguageSearchEngine` constructor now accepts `TimeProvider` (line 14). All date calculations use `timeProvider.now()` instead of `LocalDate.now()`. Tests can inject a fake clock.

### [ISSUE-16] RESOLVED — "Last month" uses calendar months

For `"month"` unit, the engine now computes:
- `start = end.withDayOfMonth(1).minusMonths(1)` (first day of previous month)
- `adjustedEnd = end.withDayOfMonth(1).minusDays(1)` (last day of previous month)

This matches user expectation for "last month" meaning the prior calendar month.

### [ISSUE-28] RESOLVED — Currency columns in export files

`QuickBooksIIFExporter` (line 38), `XeroCSVExporter` (line 64, 76), and `FreshBooksExporter` (line 122, 135) all include the expense currency in their output rows.

---

# Partially resolved issues — detail

### [ISSUE-5] PARTIALLY RESOLVED — SQL aggregates for some queries

`executeCount()` and `executeList()` now use `getAssistantExpenseCountFiltered()` → `SELECT COUNT(*)`. However, `executeTotal()`, `executeAverage()`, `executeCategoryBreakdown()`, `executeMerchantBreakdown()`, and `executeLargest()` still call `assistantFilteredExpenses()` which loads **all** matching rows via `getAssistantExpensesFiltered()` (no LIMIT). For a year of data, this can be thousands of rows.

### [ISSUE-6] PARTIALLY RESOLVED — Currency-aware sorting in breakdowns

Category and merchant breakdowns now use `currencyConverter.convertMultiple()` to produce sort keys for multi-currency groups. Single-currency groups are sorted by raw amount within that currency (correct since within one currency, raw = value). However, `executeLargest()` (line 161) still uses `.maxByOrNull { it.expense.effectiveAmount }` — no currency conversion, so ¥1000 beats €20.

### [ISSUE-9] PARTIALLY RESOLVED — Execution layer asks for clarification

`ExecuteFinancialQueryUseCase.invoke()` now returns `Clarification` when `period` is null (line 41–44). But the interpretation layer (`resolvePeriod()`, line 297) always fills a default (current month), so the execution-layer clarification is never reached in practice. The implicit "this month" assumption still applies.

### [ISSUE-18] PARTIALLY RESOLVED — Error caught but not clarified

`NaturalLanguageSearchViewModel` has a catch block (line 103–106) that prevents crashes from invalid date parses. But it shows a generic error state rather than a user-friendly clarification like "That date doesn't look right. Try DD/MM/YYYY."

### [ISSUE-27] PARTIALLY RESOLVED — Per-currency PDF grouping

The PDF now groups expenses by currency and applies `LARGE_TRANSACTION_THRESHOLD` within each currency group. So ¥500 and $500 each trigger "Large Transaction" within their own section. However, the threshold is still a flat `500.0` — ¥500 (≈€3) is not meaningfully "large."

---

# Still present — highest priority

The following 19 issues remain exactly as described in the original analysis. They are listed in priority order:

### Top priority (must-fix)

1. **[ISSUE-1]** Legacy merchant extraction broken — no merchant search results from legacy NL path
2. **[ISSUE-2]** Legacy filters extracted but ignored — category/location queries return all expenses
3. **[ISSUE-3]** Legacy amount filtering wrong — uses gross `amount`, exact `==`, no currency
4. **[ISSUE-7]** Assistant amount filters not currency-aware — "$50" matches ¥51, €51
5. **[ISSUE-8]** Multi-filter drilldown broader than answer — tap "Amazon + Lidl" → opens all transactions
6. **[ISSUE-10]** "This week" inconsistent — bare query vs. richer query give different ranges
7. **[ISSUE-11]** Previous-period comparison wrong for calendar periods
8. **[ISSUE-12]** AI output validation weak — model can request huge ranges, negative amounts
9. **[ISSUE-13]** Uncategorized excluded from category breakdown — totals don't add up
10. **[ISSUE-24]** Export paging not atomic — rows can skip/duplicate during concurrent edits
11. **[ISSUE-25]** Accountant PDF mixes deposits/transfers with expenses

### Medium priority

12. **[ISSUE-14]** Merchant filtering misses rows without merchantKey
13. **[ISSUE-17]** Ambiguous DD/MM/YYYY parsing — US vs. EU confusion
14. **[ISSUE-19]** Legacy search can scan entire history (0→now default)
15. **[ISSUE-20]** No cascading fallback: cloud → on-device → deterministic
16. **[ISSUE-21]** No UI notice about cloud merchant exposure
17. **[ISSUE-22]** Query model missing currency/source/status filters
18. **[ISSUE-23]** No confidence/exactness metadata on results
19. **[ISSUE-26]** PDF period display shows exclusive end month
20. **[ISSUE-29]** Exported files unencrypted in cache

---

# Coverage

### Requirements met: NO

The AI assistant query path has partial coverage for basic queries but:
- Amount filters are not currency-aware (issue 7)
- AI cannot emit amount filters at all (new issue N1)
- Breakdowns miss uncategorized rows (issue 13)
- Period handling has inconsistencies (issues 9, 10, 11)
- Drilldown can be misleadingly broad (issue 8)

The legacy NL search path has severe correctness gaps (issues 1, 2, 3) that make most queries unreliable.

### Testing adequate: NO

The analysis recommended 20 regression tests. Based on the code review:
- Only 4 of 20 would pass (those corresponding to issues 4, 15, 16, 28)
- 6 would partially pass (issues 5, 6, 9, 18, 27 and maybe 6)
- 10 would fail (all STILL PRESENT issues)
- No test files appear to have been added for the recommended regression suite

---

# Recommended next actions

### Immediate (PR 1)
1. **Unify or retire legacy NL search** — route `NaturalLanguageSearchScreen` through `InterpretFinancialQueryUseCase` + `ExecuteFinancialQueryUseCase`. The legacy `NaturalLanguageSearchEngine` has too many bugs to be salvageable as a separate code path.

### Short-term (PR 2–3)
2. **Fix AI amount filter support** (new issue N1) — add `minAmount`/`maxAmount` to the prompt JSON schema
3. **Add currency-aware amount filtering** (issue 7) — extend `ExpenseQueryFilters` with `currency` field and add `currency = ?` to dynamic SQL
4. **Fix drilldown exactness** (issue 8) — support multi-value filters in navigation or disable drilldown when not exact
5. **Fix export snapshot consistency** (issue 24) — capture ID list first, then export by IDs, inside a read transaction

### Medium-term (PR 4–6)
6. **Move assistant summaries to SQL aggregates** (issue 5 remaining) — add DAO methods for totals/averages/breakdowns/largest with proper currency grouping
7. **Fix PDF transaction type filtering** (issue 25) — add `AND transactionType = 'PURCHASE'` (or configurable type filter) to the export query used by PDF
8. **Add AI query validation** (issue 12) — period range guards, amount bounds, max date horizon
9. **Fix legacy NL search bugs** (issues 1, 2, 3, 17, 18, 19) — unless retiring the path entirely
