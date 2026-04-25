# D3 SubBatch D.1 Review

Scope audited: `MASTER-ISSUE-REGISTRY.md` → `### D.3: Medium (Quick Wins)` → `### SubBatch D.1`

Read context:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

## Summary
- Total issues audited: **15**
- **RESOLVED:** 4
- **PARTIALLY_RESOLVED:** 1
- **STILL_OPEN:** 10
- **FALSE_POSITIVE:** 0

## Issue Audit

| # | Registry issue | Status | Evidence | Suggested registry wording if status should change |
|---|---|---|---|---|
| 1 | `BudgetDao.getOverallBudget()` and `getByCategory()` assume single active row — add deterministic `ORDER BY` | **RESOLVED** | `BudgetDao.kt:82-90` now uses `ORDER BY id DESC LIMIT 1` for both queries. | Replace with: `- \`BudgetDao.getOverallBudget()\` and \`getByCategory()\` assumed single active row — both queries now use deterministic \`ORDER BY id DESC LIMIT 1\` on active budgets **[RESOLVED]**` |
| 2 | `ExpenseDao.searchMerchants()` uses `UPPER(merchant) LIKE '%...%'` — use normalized/indexed search key | **STILL_OPEN** | `ExpenseDao.kt:737-741` still filters with `WHERE UPPER(merchant) LIKE '%' || UPPER(:query) || '%'`; it groups by `merchantKey` later, but the search predicate is still raw-merchant based and non-index-friendly. | No status change; keep open. |
| 3 | `WarrantyDao.getTotalProtectedValue()` treats `status = 'ACTIVE'` as sufficient — add `currentTime` filter | **RESOLVED** | `WarrantyDao.kt:67-68` now includes `AND w.warrantyEndDate > :currentTime`. | Replace with: `- \`WarrantyDao.getTotalProtectedValue()\` treated \`status = 'ACTIVE'\` as sufficient — query now also filters by \`warrantyEndDate > :currentTime\` **[RESOLVED]**` |
| 4 | `WarrantyDao.getTotalProtectedValue()` sums raw `expense.amount` instead of `effectiveAmount` | **STILL_OPEN** | `WarrantyDao.kt:64` still does `SUM(COALESCE(e.amount, 0))`. | No status change; keep open. |
| 5 | `ExpenseDao` → `BudgetRepository.getSuggestions()` N+1 per-category loop | **STILL_OPEN** | `BudgetRepository.kt:286-289` iterates categories and calls `expenseDao.getCategorySpentInPeriod(...)` once per category. | No status change; keep open. |
| 6 | `CsvExpenseImporter` `line.split(",")` breaks quoted CSV fields | **STILL_OPEN** | `CsvExpenseImporter.kt:74-81` still parses with `val parts = line.split(",")`. | No status change; keep open. |
| 7 | `CsvExpenseImporter` failed date parse silently substitutes `System.currentTimeMillis()` | **STILL_OPEN** | `CsvExpenseImporter.kt:84-88` still falls back to `System.currentTimeMillis()` on parse failure. | No status change; keep open. |
| 8 | `RecurringPattern.kt` missing invariants — allows negative/non-finite amounts, negative variance days, out-of-range confidence/percentage | **RESOLVED** | `RecurringPattern.kt:18-29` now enforces non-blank merchant/currency, positive finite `averageAmount`, non-negative variance, bounded `confidence`, and non-negative dates. | Replace with: `- \`RecurringPattern.kt\` missing invariants — model now enforces positive finite amounts, non-negative variances/dates, bounded confidence, and non-blank merchant/currency **[RESOLVED]**` |
| 9 | `WarrantyExtractionModels.kt` missing invariants — allows negative `warrantyMonths`, negative `returnDays`, out-of-range `confidence` | **STILL_OPEN** | `WarrantyExtractionModels.kt:17-26` defines the model fields but has no `init` validation. | No status change; keep open. |
| 10 | `NotificationParsingModels.kt` missing invariants — documents positive amount and bounded confidence but enforces neither | **STILL_OPEN** | `NotificationParsingModels.kt:33-40` defines `amount` and `confidence` with no validation despite the KDoc contract. | No status change; keep open. |
| 11 | `DomainTransactionFilter.correlationId` dropped by `TransactionFilterSerializer` — recommendation-generated filters lose end-to-end trace | **RESOLVED** | `TransactionFilterSerializer.kt:30,51,110-123` serializes and deserializes `correlationId`; `DomainTransactionFilter.kt:13` still carries the field. | Replace with: `- \`DomainTransactionFilter.correlationId\` dropped by \`TransactionFilterSerializer\` — serializer now preserves \`correlationId\` in both serialize/deserialize paths **[RESOLVED]**` |
| 12 | Artifact hashing — several use cases derive `sourceHash` from `hashCode().toString()`, weaker than SHA-256 for long-lived cache identity | **PARTIALLY_RESOLVED** | `SuggestReceiptExtractionUseCase.kt:183-200` now uses stable SHA-256, but other paths still use weak hashes: `JudgePendingReviewDuplicateUseCase.kt:57`, `ExplainPendingReviewUseCase.kt:65`, `GenerateDashboardBriefingUseCase.kt:70`, `GenerateTransactionInsightUseCase.kt:105`, `CategorizeReceiptItemsUseCase.kt:106`. | Replace with: `- Artifact hashing — \`SuggestReceiptExtractionUseCase\` now uses stable SHA-256 over deterministic business fields, but multiple AI use cases still derive \`sourceHash\` from \`hashCode().toString()\` (including dedupe judge, review explanation, dashboard briefing, transaction insight, and receipt-item categorization) **[PARTIALLY_RESOLVED]**` |
| 13 | `toReadableMessage()` / route-diagnostic formatting / failure-message assembly duplicated across AI use cases | **STILL_OPEN** | Near-identical helpers remain duplicated in `SuggestReceiptExtractionUseCase.kt:204-265`, `JudgePendingReviewDuplicateUseCase.kt:156-206`, `ExplainPendingReviewUseCase.kt:137-162`, and `GenerateDashboardBriefingUseCase.kt:132-154`. | No status change; keep open. |
| 14 | `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` treats any `total > 0` week as qualifying — confidence overstated | **STILL_OPEN** | `MonteCarloSpendingSimulator.kt:245-249` still counts `recentTotals.count { it > 0.0 }`. | No status change; keep open. |
| 15 | `SpendingPatternsCard` `maxOfOrNull(...) ?: 1.0` produces `NaN` when all totals `0.0` — use `takeIf { it > 0 } ?: 1.0` | **STILL_OPEN** | `AnalyticsScreen.kt:586-589` still computes `maxSpend = ... ?: 1.0`; when all totals are `0.0`, `heightRatio` becomes `0.0 / 0.0`. | No status change; keep open. |

## Registry Update Instructions

Apply the following status updates in `MASTER-ISSUE-REGISTRY.md` under `### SubBatch D.1`:

1. Replace the current bullet for the Budget DAO ordering issue with:

   `- \`BudgetDao.getOverallBudget()\` and \`getByCategory()\` assumed single active row — both queries now use deterministic \`ORDER BY id DESC LIMIT 1\` on active budgets **[RESOLVED]**`

2. Replace the current bullet for the warranty active-time filter issue with:

   `- \`WarrantyDao.getTotalProtectedValue()\` treated \`status = 'ACTIVE'\` as sufficient — query now also filters by \`warrantyEndDate > :currentTime\` **[RESOLVED]**`

3. Replace the current bullet for the `RecurringPattern.kt` invariants issue with:

   `- \`RecurringPattern.kt\` missing invariants — model now enforces positive finite amounts, non-negative variances/dates, bounded confidence, and non-blank merchant/currency **[RESOLVED]**`

4. Replace the current bullet for the `DomainTransactionFilter.correlationId` serializer issue with:

   `- \`DomainTransactionFilter.correlationId\` dropped by \`TransactionFilterSerializer\` — serializer now preserves \`correlationId\` in both serialize/deserialize paths **[RESOLVED]**`

5. Replace the current bullet for artifact hashing with:

   `- Artifact hashing — \`SuggestReceiptExtractionUseCase\` now uses stable SHA-256 over deterministic business fields, but multiple AI use cases still derive \`sourceHash\` from \`hashCode().toString()\` (including dedupe judge, review explanation, dashboard briefing, transaction insight, and receipt-item categorization) **[PARTIALLY_RESOLVED]**`

6. Leave the other 10 SubBatch D.1 entries open with no status marker change.

## Batch 6 Registry Sync Addendum

- D1-7 (`CsvExpenseImporter` failed-date fallback to wall-clock now): **RESOLVED BY D3-TIME-DETERMINISM**.
- Revalidation outcome: importer now surfaces invalid date rows as import failures instead of substituting `System.currentTimeMillis()`.
