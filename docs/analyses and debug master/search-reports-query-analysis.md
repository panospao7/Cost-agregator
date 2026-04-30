# Search / Reports / Query Interpretation Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- natural-language search screen
- deterministic natural-language search engine
- AI assistant query interpretation
- financial query execution
- transaction filter mapping
- accounting exports
- accountant PDF report generation
- export paging and formatting

This is a static review; I did not run the app or tests.

---

## Executive verdict

This area has two separate query systems:

1. **Legacy/local natural-language search**
   - `NaturalLanguageSearchEngine`
   - `NaturalLanguageSearchScreen`
   - `NaturalLanguageSearchViewModel`

2. **AI assistant financial query pipeline**
   - `InterpretFinancialQueryUseCase`
   - `QueryInterpretationService`
   - `ExecuteFinancialQueryUseCase`
   - `AssistantViewModel`

The AI assistant path is much stronger than the older natural-language search path, but both still have correctness and privacy risks.

The biggest issues are:

1. legacy natural-language search has several parser bugs and ignores many extracted filters
2. assistant conversation history appears to be persisted even when history is disabled, unless the repository blocks it internally
3. broad assistant queries load full result sets into memory instead of using SQL aggregates
4. mixed-currency sorting/filtering is still raw numeric in several places
5. multi-filter drilldowns can become broader than the assistant answer
6. export paging can produce inconsistent files if data changes during export
7. accountant PDF reports may include non-expense transaction types

---

# Architecture map

## Legacy natural-language search

Flow:

```text
NaturalLanguageSearchScreen
→ NaturalLanguageSearchViewModel
→ NaturalLanguageSearchEngine
→ NaturalLanguageExpenseQueryRepository
→ NaturalLanguageExpenseQueryRepositoryImpl
→ ExpenseDao.getExpensesBetween(...)
```

This path is mostly deterministic regex/rules.

## AI assistant financial query

Flow:

```text
AssistantSheet
→ AssistantViewModel
→ InterpretFinancialQueryUseCase
→ FinancialQueryInterpretationInputBuilder
→ HybridQueryInterpretationService
→ Cloud / On-device / No-op QueryInterpretationService
→ ExecuteFinancialQueryUseCase
→ ExpenseRepository dynamic assistant query
→ optional MapFinancialQueryToNavigationUseCase
```

This path is better modeled and supports structured intents.

## Exports / reports

Flow:

```text
AccountingExportRepository.exportExpenses()
→ DeterministicExpenseExportPager
→ ExpenseRepository.getExpensesBetweenPagedForDeterministicExport()
→ ExpenseExportMapper
→ QuickBooksIIFExporter / XeroCSVExporter / FreshBooksExporter / AccountantReportPdfExporter
```

---

# Strong parts

## 1. Assistant query execution uses parameterized dynamic SQL

`ExpenseRepository.buildExpenseDynamicQueryParts()` uses `SimpleSQLiteQuery` with bound args.

The sort order comes from a closed enum, not user input.

Good.

## 2. Assistant avoids raw mixed-currency display totals in some places

`ExecuteFinancialQueryUseCase` groups totals by expense currency and displays values like:

```text
10.00 EUR + 12.00 USD
```

That is better than silently showing `22`.

## 3. Export paging is deterministic

`DeterministicExpenseExportPager` fetches in pages and the DAO export query orders by:

```text
date ASC, id ASC, merchant COLLATE NOCASE ASC
```

Good for repeatable export order.

## 4. Accounting export policy rejects risky datasets

`AccountingExportPolicy` requires:

- single currency
- purchase-only transactions

for QuickBooks/Xero/FreshBooks exports.

Good.

## 5. CSV formula injection is mitigated

CSV exporters neutralize fields beginning with:

```text
= + - @
```

Good.

## 6. Query input builder has redaction support

`FinancialQueryInterpretationInputBuilder` can alias merchants/categories and redact free-text before cloud use.

Good direction.

---

# Critical / high-priority findings

## 1. Legacy merchant extraction is broken because the query is lowercased first

### Where

`NaturalLanguageSearchEngine.interpretQuery()`

The code lowercases the query:

```kotlin
val normalized = query.lowercase()
```

Then merchant extraction looks for capitalized words:

```kotlin
Regex("""(?:at|from)\s+([A-Z][a-zA-Z]+)""")
```

### Impact

A query like:

```text
show transactions at Starbucks
```

becomes:

```text
show transactions at starbucks
```

The merchant regex no longer matches.

So merchant extraction is effectively dead for most typed queries.

### Severity

**High**

### Fix

Run merchant extraction on the original query, not the lowercased query, or use known merchant lookup/merchant keys.

---

## 2. Legacy search extracts filters but does not apply most of them

### Where

`NaturalLanguageSearchEngine.executeSearch()`

The engine extracts:

- locations
- categories
- merchants
- amount filters
- query type

But execution mostly only applies:

- date range
- merchant filter for `FIND_TRANSACTIONS`
- amount filter for `FIND_TRANSACTIONS`

`SPENDING_BY_CATEGORY` just returns expenses for the date range. Location/category filters are not applied.

### Impact

Examples:

```text
groceries last month
```

may return all last-month expenses.

```text
restaurants near Athens
```

location is extracted but ignored.

### Severity

**High**

### Fix

Either:

1. remove unsupported chips from the UI, or  
2. apply all extracted filters in SQL/repository:
   - category IDs
   - merchant key
   - amount range
   - transaction type
   - location/geofence if supported

---

## 3. Legacy amount filtering uses raw amount, exact Double equality, and no currency

### Where

`NaturalLanguageSearchEngine.executeSearch()`

Amount comparisons use:

```kotlin
expense.amount == amount.value
expense.amount > amount.value
expense.amount < amount.value
```

### Problems

- Uses gross `amount`, not `effectiveAmount`.
- Exact Double equality is brittle.
- No currency interpretation.
- `€20`, `$20`, and `20` are treated the same.
- Shared/not-mine ownership semantics are ignored.

### Impact

A shared €100 expense where my share is €50 will not match “over 80” correctly depending on user expectation.

Mixed-currency queries are meaningless.

### Severity

**High**

### Fix

Use:

- `effectiveAmount` for personal-spend queries
- amount tolerance for exact match
- currency-aware filters
- explicit ownership scope

---

## 4. Assistant conversation history may be persisted even when history is disabled

### Where

`AssistantViewModel`

`submitQuery()` calls:

```kotlin
val sessionId = ensureSessionIfNeeded()
persistUserTurn(sessionId, query)
persistAssistantTurn(sessionId, result)
```

`ensureSessionIfNeeded()` does not check `storeConversationHistory`.

`persistUserTurn()` only checks `sessionId != null`.

### Impact

Unless `AiChatRepository` itself refuses writes when history is disabled, the app can store assistant queries even when the UI setting says conversation history is off.

Financial queries can contain very sensitive text:

```text
how much did I spend at fertility clinic?
salary from employer
transactions at pharmacy
```

### Severity

**Critical / privacy**

### Fix

Enforce at the ViewModel/use-case boundary and repository boundary:

```kotlin
if (!settings.storeConversationHistory) return null
```

Also ensure:

- no session is created when history is off
- no messages are appended when history is off
- turning history off offers to delete old history

---

## 5. Assistant broad queries load full result sets into memory

### Where

`ExecuteFinancialQueryUseCase`

Methods like:

- `executeTotal`
- `executeAverage`
- `executeCategoryBreakdown`
- `executeMerchantBreakdown`
- `executeLargest`

call:

```kotlin
assistantFilteredExpenses(...)
```

which loads all matching rows, then groups/sums in Kotlin.

### Impact

A query like:

```text
total spending this year
top merchants this year
average spending this year
```

can load thousands of rows into memory.

This can cause:

- slow assistant responses
- UI jank
- memory pressure
- inconsistent behavior on large user histories

### Severity

**High**

### Fix

Use DAO aggregate queries for assistant summaries:

- total by currency
- count
- average by currency
- max by currency or normalized base amount
- top categories by currency
- top merchants by currency

Reserve full row loading for drilldown/list views.

---

## 6. Mixed-currency sorting is still raw numeric

### Where

`ExecuteFinancialQueryUseCase`

Examples:

```kotlin
.sortedByDescending { grouped -> grouped.sumOf { it.expense.effectiveAmount } }
.maxByOrNull { it.expense.effectiveAmount }
```

### Problem

Display is grouped by currency, but sorting is not.

So:

```text
JPY 1000
EUR 20
USD 30
```

can be sorted by raw numeric amount, not value.

### Impact

“Largest purchase” and “top merchants” can be wrong across currencies.

### Severity

**High / Critical if multi-currency is user-facing**

### Fix

Sort using normalized/base amount or reject cross-currency ranking unless conversion is available.

---

## 7. Assistant min/max amount filters are not currency-aware

### Where

`ExpenseRepository.buildExpenseDynamicQueryParts()`

Min/max filters use:

```sql
effectiveAmount >= ?
effectiveAmount <= ?
```

No currency filter or conversion exists.

### Impact

Query:

```text
show expenses over $50
```

will match:

- `€51`
- `¥51`
- `£51`

as raw numbers.

### Severity

**High**

### Fix

Add currency to `ExpenseQueryFilters`:

```kotlin
currency: CurrencyCode?
```

Then either:

- filter within the same currency, or
- convert all amounts to a base currency before filtering.

---

## 8. Multi-merchant / multi-category drilldown can be broader than the assistant answer

### Where

`MapFinancialQueryToNavigationUseCase`

It maps only:

```kotlin
categoryId = intent.filters.categoryIds.singleOrNull()
merchantName = intent.filters.merchants.singleOrNull()
transactionType = intent.filters.transactionTypes.singleOrNull()
```

If there are multiple categories or merchants, the filter becomes null.

### Impact

The assistant answer may be for:

```text
Amazon and Lidl this month
```

but tapping drilldown opens all transactions for the period because merchant filter is dropped.

### Severity

**High**

### Fix

Support multi-value transaction filters in navigation/UI, or disable drilldown when filter cannot be represented exactly.

Rule:

> Drilldown must never be broader than the answer.

---

## 9. Assistant local fallback defaults missing periods to current month instead of asking

### Where

`InterpretFinancialQueryUseCase.resolvePeriod()`

If no period is found, it defaults to:

```kotlin
TimePeriodUtils.getMonthRange(now, 0)
```

### Impact

Query:

```text
how much did I spend at Amazon?
```

silently means:

```text
this month
```

The user may expect all-time, recent, or a clarification.

### Severity

**High UX / correctness**

### Fix

For ambiguous queries, return clarification:

```text
Which period should I use?
- This month
- Last month
- This year
- All time
```

Only default to current month if the UI clearly labels it.

---

## 10. “This week” has inconsistent semantics

### Where

`InterpretFinancialQueryUseCase`

For bare query:

```text
this week
```

it returns rolling last 7 days:

```kotlin
now - 7 days → now
```

But general period resolution for queries containing `week` uses calendar week:

```kotlin
TimePeriodUtils.getWeekRange(now, 0)
```

### Impact

These can differ:

```text
this week
spent this week
```

One may be rolling 7 days; the other calendar week.

### Severity

**High**

### Fix

Use one semantic model:

- `this week` = calendar week
- `last 7 days` = rolling 7 days

---

## 11. Previous-period comparison uses raw duration

### Where

`ExecuteFinancialQueryUseCase.previousEquivalentPeriod()`

```kotlin
val duration = period.end - period.start
PeriodRange(period.start - duration, period.start)
```

### Problem

Same issue found in dashboard analytics: calendar months/weeks should not be compared by raw milliseconds.

### Impact

March can compare against a range starting in late January instead of February, depending on duration.

### Severity

**High**

### Fix

Use calendar-aware previous ranges based on the period type.

---

## 12. AI query output validation is too weak for amounts and periods

### Where

`OnDeviceQueryInterpretationService.parseStructured()`

Issues:

- `minAmount` / `maxAmount` are accepted via `optDouble`.
- No finite/non-negative validation.
- No `min <= max` validation.
- Period only checks `endMs > startMs`.
- No maximum range guard.
- No plausible timestamp range.
- No query-cost guard.

### Impact

A bad model output can request:

- huge historical range
- future range
- negative amounts
- impossible amount filter
- NaN-like values depending on JSON parsing behavior

### Severity

**High**

### Fix

Add shared validators:

```kotlin
boundedMoneyFilter()
boundedPeriodRange()
finitePositiveDouble()
maxRangeDays()
```

If validation fails, return clarification/unsupported.

---

## 13. Uncategorized spend disappears from assistant category breakdown

### Where

`ExecuteFinancialQueryUseCase.executeCategoryBreakdown()`

It filters:

```kotlin
.filter { it.expense.categoryId != null }
```

### Impact

Category breakdown rows can sum to less than total spending.

This confuses users:

```text
Total: €500
Top categories sum: €390
```

### Severity

**Medium / High**

### Fix

Add virtual bucket:

```text
Uncategorized
```

---

## 14. Merchant filtering depends on merchantKey and misses rows with missing/stale keys

### Where

`ExpenseRepository.buildExpenseDynamicQueryParts()`

Merchant filters are converted to keys:

```kotlin
MerchantKeyGenerator.generate(it)
```

then applied as:

```sql
e.merchantKey IN (...)
```

### Impact

Rows with null or stale `merchantKey` are not found.

This matters because you already have merchant-key backfill workers and migration history around merchant keys.

### Severity

**Medium / High**

### Fix

Use:

```sql
merchantKey IN (...) OR merchant COLLATE NOCASE IN (...)
```

or ensure merchantKey is non-null and enforced/backfilled before assistant search depends on it.

---

# Legacy natural-language search-specific issues

## 15. Date parsing uses device current date directly

### Where

`NaturalLanguageSearchEngine`

It calls `LocalDate.now()` directly.

### Impact

Tests are harder. Queries can behave differently around midnight/timezone changes.

### Fix

Inject `TimeProvider`.

---

## 16. “Last month” is rolling one month, not previous calendar month

### Where

`NaturalLanguageSearchEngine.datePatterns`

For `last month`:

```text
today.minusMonths(1) → today
```

### Impact

On April 26, “last month” means March 26–April 26, not March 1–April 1.

### Severity

**High**

### Fix

Use calendar previous month.

---

## 17. Ambiguous numeric date parsing

### Where

`NaturalLanguageSearchEngine`

Pattern:

```text
(\d{1,2})/(\d{1,2})/(\d{2,4})
```

is interpreted as day/month/year.

### Impact

For US users, `04/05/2026` may mean April 5, but parser reads May 4.

### Severity

**Medium / High**

### Fix

Use locale-aware parsing, or ask clarification for ambiguous dates.

---

## 18. Invalid date parse can throw and fail the whole search

### Where

`LocalDate.of(year, month, day)`

No catch around invalid dates inside pattern extraction.

### Impact

Query:

```text
show expenses on 31/02/2026
```

can send ViewModel into error state.

### Fix

Return clarification/unsupported instead of throwing.

---

## 19. Legacy search fetches broad date range then filters in memory

### Where

`NaturalLanguageExpenseQueryRepositoryImpl`

It pages all expenses between `startMs` and `endMs`, then `NaturalLanguageSearchEngine` applies some filters in memory.

Default range can be:

```text
0 → now
```

### Impact

A query with no date can scan all history.

### Severity

**Medium / High**

### Fix

Push filters into SQL and require/ask for a time range for broad queries.

---

# Assistant / AI query path-specific issues

## 20. Hybrid query interpretation has no runtime provider fallback

### Where

`HybridQueryInterpretationService`

If router selects cloud:

```kotlin
AiRoute.CLOUD -> cloudQueryInterpretationService.interpret(input)
```

and cloud returns unsupported, the use case falls back to local heuristic, not on-device model.

### Impact

A temporary cloud failure can degrade from rich AI interpretation to basic keyword fallback even when on-device is available.

### Severity

**Medium / High**

### Fix

Use the `HybridExecutor` remedy from the AI audit:

```text
cloud → on-device → deterministic fallback
```

when mode/policy allows.

---

## 21. Query interpretation prompt can expose recent merchant list to cloud

### Where

`FinancialQueryInterpretationInputBuilder`
`OnDeviceQueryInterpretationService.buildPrompt()`
`CloudQueryInterpretationService`

When redaction is off, prompt includes:

- raw query
- known categories
- up to 20 known merchants in prompt
- lookup keys
- conversation history

### Impact

This may be intended, but it is sensitive. The UI should clearly say that query interpretation may send merchant names/history to cloud when cloud AI is enabled and redaction is off.

### Severity

**High privacy UX**

### Fix

Cloud provider should enforce `CloudAiGate`, and UI should show:

```text
Cloud query interpretation may include merchant/category context unless redaction is enabled.
```

---

## 22. Query intent model lacks currency, source, and status filters

### Where

`FinancialQueryModels.kt`

`ExpenseQueryFilters` has:

- period
- merchants
- category IDs
- transaction types
- ownership
- min/max amount

Missing:

- currency
- payment method/account
- review status
- source app/source type
- reimbursement state
- receipt-attached state
- planned vs actual
- business/tax flags

### Impact

Queries like these cannot be represented correctly:

```text
USD expenses last month
cash purchases over 20
transactions from Revolut
business expenses this quarter
expenses with receipts
unreviewed transactions
```

### Severity

**Medium / High**

### Fix

Extend `ExpenseQueryFilters` gradually.

---

## 23. Assistant result summaries are not clearly labeled as exact vs partial

### Where

`FinancialQueryResult`
`AssistantResultCard`

The execution path can return:

- SQL exact count
- in-memory exact summary
- fallback heuristic interpretation
- unsupported/provider fallback

But the result does not expose confidence/completeness.

### Impact

The assistant may answer confidently after falling back to weak local heuristics.

### Fix

Add result metadata:

```kotlin
interpretationSource
confidence
filtersApplied
filtersDropped
isExact
warnings
```

---

# Export / report issues

## 24. Export paging can be inconsistent if data changes during export

### Where

`DeterministicExpenseExportPager`

It uses offset paging:

```text
LIMIT 2000 OFFSET n
```

without a transaction/snapshot boundary.

### Impact

If an expense is inserted/deleted/edited during export:

- rows can be skipped
- rows can be duplicated
- totals can disagree
- export becomes non-repeatable

### Severity

**High**

### Fix

Options:

1. Run export in a read transaction.
2. Capture IDs first, then page by IDs.
3. Use keyset pagination based on `(date, id, merchantKey)`.
4. Disable writes during export if acceptable.

Best: capture stable ID list for the date range, then export those IDs.

---

## 25. Accountant PDF may include non-expense transaction types

### Where

`AccountingExportRepository`
`AccountantReportPdfExporter`
`ExpenseDao.getExpensesBetweenForExport`

Accounting CSV/IIF exports validate purchase-only through `AccountingExportPolicy`.

But `ACCOUNTANT_REPORT_PDF` bypasses the accounting policy and receives the same fetched expenses.

The DAO export query filters by:

```sql
date range
isNotMine = 0
```

but not purchase-only.

### Impact

PDF “Total Expenses” can include:

- deposits
- transfers
- withdrawals
- unknown transaction types

depending on what rows exist.

### Severity

**High**

### Fix

Apply an explicit report policy:

- expense report = purchases/withdrawals only, depending on intended definition
- income report = deposits only
- transfer report = transfers only

Do not mix transaction types silently.

---

## 26. Accountant PDF period display can show the exclusive end month

### Where

`AccountantReportPdfExporter`

It builds period text:

```kotlin
formatMonth(startDate) - formatMonth(endDate)
```

But query ranges are half-open:

```text
start inclusive, end exclusive
```

### Impact

April 1 → May 1 can display:

```text
Apr 2026 - May 2026
```

even though report covers April only.

### Severity

**Medium**

### Fix

Display:

```kotlin
endDate - 1 millisecond
```

or use explicit date range formatting.

---

## 27. Large transaction threshold is raw 500 per currency

### Where

`AccountantReportPdfExporter`

```kotlin
LARGE_TRANSACTION_THRESHOLD = 500.0
```

### Impact

`500 JPY`, `500 EUR`, and `500 USD` are treated the same.

### Severity

**Medium / High multi-currency**

### Fix

Use base-currency equivalent or per-currency thresholds.

---

## 28. Accounting export files omit explicit currency columns

### Where

`QuickBooksIIFExporter`
`XeroCSVExporter`
`FreshBooksExporter`

The dataset is single-currency validated, but the generated CSV/IIF rows do not clearly include currency.

### Impact

If imported into an accounting system with a different account currency, values can be interpreted incorrectly.

### Severity

**Medium**

### Fix

Include currency where target format supports it, or encode the selected currency in file metadata/header/filename.

---

## 29. Exported files live in cache and are shared through FileProvider

### Where

`AccountingExportRepository`

Files are written to:

```text
cacheDir/exports
```

and exposed with FileProvider.

### Impact

Good for temporary sharing, but:

- cache can be cleared
- files may contain sensitive financial data
- no encryption/redaction option at report level

### Severity

**Medium / privacy**

### Fix

Show export sensitivity warning and support redacted/encrypted export modes.

---

# Recommended fix order

## PR 1 — Decide whether to retire or unify legacy natural-language search

The legacy `NaturalLanguageSearchEngine` is much weaker than the assistant path.

Recommended:

- either remove/retire it,
- or make it call the same `InterpretFinancialQueryUseCase + ExecuteFinancialQueryUseCase`.

Do not maintain two query semantics.

## PR 2 — Fix assistant history persistence privacy

Ensure `storeConversationHistory=false` means:

- no session creation
- no message persistence
- no payload JSON persistence
- no old history retained without user control

## PR 3 — Add query validation and cost limits

Validate:

- period range
- amount bounds
- currency filter
- max result size
- max date horizon

Add query execution metadata:

```text
exact / partial / filters dropped / fallback used
```

## PR 4 — Move assistant summaries to SQL aggregates

Add DAO methods:

- totals by currency
- averages by currency
- top merchants by currency
- top categories by currency
- largest transaction with currency/base amount
- count with same filters

## PR 5 — Fix drilldown exactness

If an assistant answer uses multiple merchants/categories/types, drilldown must represent those filters or be disabled.

## PR 6 — Add currency-aware query filters

Extend `ExpenseQueryFilters` with:

```kotlin
currency
baseCurrencyAmountMin
baseCurrencyAmountMax
```

or equivalent money model.

## PR 7 — Fix export snapshot consistency

Replace offset paging over live data with stable ID snapshot or read transaction.

## PR 8 — Apply transaction-type policy to PDF reports

Accountant PDF should not silently mix deposits/transfers with expenses.

---

# Regression tests to add

1. Legacy query “at Starbucks” extracts merchant correctly.
2. Legacy “last month” uses previous calendar month.
3. Invalid date query returns clarification, not exception.
4. Category query applies category filter.
5. Location query either applies location filter or does not display extracted location.
6. Assistant history disabled creates no session/messages.
7. Assistant query with no period asks clarification instead of silently using current month.
8. “this week” means the same thing across assistant fallback paths.
9. Previous-period comparison for March compares to February.
10. Query with multi-merchant filter does not open unfiltered drilldown.
11. Uncategorized appears in category breakdown.
12. Assistant total query uses SQL aggregate, not full row scan.
13. Mixed-currency largest purchase uses converted/base amount or refuses ranking.
14. “over $50” does not match all currencies blindly.
15. Export during concurrent insert/delete does not skip/duplicate rows.
16. Accountant PDF excludes deposits/transfers or reports them separately.
17. PDF half-open date range displays correct end date.
18. Large transaction threshold is currency-aware.
19. CSV formula injection remains neutralized.
20. Export file includes or clearly declares currency.

---

# Top three fixes

If you only fix three things first:

1. **Unify legacy NL search with the assistant financial query pipeline.**
2. **Fix assistant conversation-history persistence when history is disabled.**
3. **Move assistant totals/breakdowns to validated, currency-aware SQL aggregates.**

Those remove the biggest correctness, privacy, and performance risks.

---

# Sources reviewed

- `NaturalLanguageSearchEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt

- `NaturalLanguageExpenseQueryRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageExpenseQueryRepository.kt

- `NaturalLanguageExpenseQueryRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt

- `NaturalLanguageSearchViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt

- `NaturalLanguageSearchScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt

- `FinancialQueryModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt

- `InterpretFinancialQueryUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt

- `FinancialQueryInterpretationInputBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt

- `ExecuteFinancialQueryUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt

- `MapFinancialQueryToNavigationUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt

- `HybridQueryInterpretationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt

- `CloudQueryInterpretationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt

- `OnDeviceQueryInterpretationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt

- `AssistantViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt

- `AssistantSheet.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `AccountingExportRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt

- `DeterministicExpenseExportPager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt

- `AccountingExportPolicy.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt

- `ExpenseExportMapper.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt

- `AccountingExporters.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt

- `AccountantReportPdfExporter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt