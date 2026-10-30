# Loan installments — discovery D-11 / D-9 / Q13 (scout, 2026-10-24; persisted by orchestrator)

Status: discovery input for S4a–S4d. Line numbers are `fun` lines in ExpenseDao.kt at b2fd00b9..722ed307 and drift.
Needs a Sonnet confirm pass for: WarrantyDao 85/103, BackupVerifier 653-654, MonthlySavingsSweepUseCase:667, ERROR-deprecated callers.

## D-9 — raw_money_aggregates guard
- `scripts/verify_raw_money_aggregates.py` (guard id `raw_money_aggregates`, rules G-MONEY-RAW-01..07) is a line-regex scanner over production `*.kt`; it does NOT parse SQL/@Query.
- No baseline, no allowlist keys; only a hard-coded `ALLOWLIST_FILES` set of 7 file names (incl. ExpenseDao.kt, BudgetDao.kt).
- Consequence: adopting SPENDING_PREDICATE / EFFECTIVE_AMOUNT in @Query strings changes no key; plan text "re-key only, non-increasing count" (IM-6) is vacuous for the SQL work and should be rewritten. Real risk: Kotlin reader changes (`sumOf { it.effectiveAmount }`) can newly trip G-MONEY-RAW-02 / currency_guardrails check 1 outside exempt files.

## D-11 — ExpenseDao aggregates (W = WHERE isNotMine=0 + CASE effective amount in SELECT)
Most spending aggregates do BOTH (filter + CASE). Rule: filter → SPENDING_PREDICATE (alias variant for `e`), and decide per batch whether the SELECT also swaps to EFFECTIVE_AMOUNT (redundant once the WHERE removes flagged rows). Edit only the listed aggregates — never a blanket replace (dedupe BLOCKING/RESOLVER families ~440-1290 must not change, INV-8).

- **S4a (budget / category-by-month), 8, SPENDING, W:** getCategorySpentInPeriod 1321 (deprecated ERROR), getCategorySpentInPeriodFlow 1333, getCategorySpentTotalsInPeriod 1354, getEffectiveSpentBetweenForCategory 1572 (type-agnostic), getCategoryTotalsBetweenByCurrency 1645, getMonthlySpendingTotals 1784, getMonthlySpendingTotalsBetween 1805, getMonthlySpendingTotalsByCategoryBetween 1827 (last three deprecated ERROR). Budget spent path appears to go via MultiCurrencyRepository — confirm in S4a.
- **S4b (dashboard totals/period/summary), 12, SPENDING:** getTotalSpentFlow 317, getTotalSpentBetween 1546, getTotalSpentBetweenByCurrency 1594, getAllSpentBetweenByCurrency 1708, getAllCategoryTotalsBetweenByCurrency 1726, getAllMonthlyTotalsBetweenByCurrency 1764, getPurchaseCount 1861 (COUNT), getSpendingDailyTotalsBetween 1884, getTotalForPeriod 1901, getCountForPeriod 1910 (COUNT), getCategoryBreakdown 2477 (alias e), getCategoryTotalsBetween 1858.
- **S4c (analytics/trends/merchant/search/NL), ~16, SPENDING:** getDailyTotalsForPeriod 2051, getWeeklyTotalsForPeriod 2408, getMonthlyTotalsForPeriod 2426, getDailyTotalsWithDatesForPeriod 2444, getAverageDailySpend 2459, getDayOfWeekPattern 2093, getMonthlyTotalsBetweenByCurrency 1686, getCategoryTotalsForPeriod 1926, getMerchantTotalsBetweenByCurrency 1667, getAllMerchantTotalsBetweenByCurrency 1747, getMerchantTotalsBetween 1843, getMerchantStats 1949, getAllMerchantStats 1971, getTopMerchantsForPeriod 1995, getRecurringCandidates 2076, getLocatedMerchantTotals 2262, getLocatedMerchantTotalsByCurrency 2286. Spending-adjacent (decide): getAmountsForPercentileCalc 1432, getLargestExpenseForPeriod 2007, getLargestExpenseForMerchant 2020. NL RawQuery builder (ExpenseRepository ~440-500): MINE adds `e.isNotMine = 0` (row-set, not a sum) — decide whether MINE adopts SPENDING_PREDICATE_E; pinned by ExpenseRepositoryAssistantSqlGoldenTest / AmountSemanticsSqlTest (verify present on the branch).
- **S4d (remaining), 5 SPENDING CASE-only (no isNotMine WHERE):** getTotalBusinessExpensesBetween 2508, getBusinessExpensesBetweenByCurrency 2525, getBusinessExpensesByCategory 2542, getBusinessExpensesByProject 2559, getBusinessCategoryCurrencyTotals 2579. UNSURE: searchMerchants 1403 (suggestion, not a total — likely exempt marker), countExpensesBetween 1510 (export/list count).
- **Other DAOs:** GroupExpenseDao, SplitItemAssignmentDao, BudgetAdjustmentDao, ReceiptItemCategorizationDao, SavingsSweepPlanDao, Mileage, Investment → NON_SPENDING. WarrantyDao 85/103 CASE copy → confirm.
- **NON_SPENDING in ExpenseDao:** deposit totals 1616/2107/2119/2125; COUNT(*) 331/358/1513; countLocated/Unlocated; AVG lat/lon 2335.
- **Never touch:** dedupe families (no SUM); AppDatabase.kt:545 migration SUM; BackupVerifier.kt:653-654 manifest totals (possibly stale column/type names — confirm, do not change for parity).

## Q13 — Kotlin raw `.amount` spending readers
`Expense.effectiveAmount` already zeroes flagged rows, so effectiveAmount users are already correct.
- RAW `.amount`, spending (plan-gate approval required before S4d changes them; they also ignore isNotMine — fixing is a visible behaviour change): `domain/location/SpendingHeatmapEngine.kt:107`, `domain/location/LocationInsightsEngine.kt:69`.
- Already effectiveAmount: AnalyticsViewModel 741/759, BusinessExpenseReportGenerator 84, NaturalLanguageSearchViewModel 133, ExecuteFinancialQueryUseCase 153/220/531, RecurringIncomeTracker 135-136, AdvancedAnalyticsDashboard, TravelDetectionEngine, AreaSpendingEngine, AutomatedSavingsRuleEngine 145/221/255/258, SmartSavingsEngine 558, MonthlySavingsSweepUseCase 667 (local private extension — confirm equals the canonical getter).
- Second grep pass needed in S4d for `.sum()` over `map { it.amount }` and Flow reducers.

## Risks
- `getAll*ByCurrency` family feeds several segments — assign to one batch only.
- ERROR-deprecated callers matched by name — segment assignment provisional.
- ExpenseDao.kt is also modified in the main checkout (feature/ai-evolution uncommitted work) → merge conflicts likely (D-13).
