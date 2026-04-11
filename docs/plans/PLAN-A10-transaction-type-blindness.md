# PLAN-A10 — Transaction Type Blindness

## 1. Objective & Blast Radius

- **The Core Issue:** Several spending-oriented pipelines still accept all `Expense` rows and then sum `effectiveAmount` without first enforcing transaction semantics. That makes deposits, transfers, withdrawals, and unknown rows leak into spend-facing totals even though A.1 already standardized ownership math.
- **Canonical semantic split for A.10:**
  - **Spending metrics:** `PURCHASE` only, then apply A.1 ownership math (`effectiveAmount`).
  - **Cash-flow / account-movement metrics:** may include deposits, transfers, withdrawals, and purchases when calculating inflow/outflow or balances, but those movements must never be presented as spending.
- **Blast Radius:**
  - **Registry-listed production files:** `SpendingHeatmapEngine.kt`, `BudgetRepository.kt`, `CashFlowCalculator.kt`, `BusinessExpenseReportGenerator.kt`, `TaxEstimator.kt`, `FinancialHealthCalculator.kt`, `CategoryInsightEngine.kt`, `TotalsAggregationEngine.kt`, `RecurringIncomeTracker.kt`
  - **Supporting dependencies required to implement safely:** `DomainTransactionType.kt`, `ExpenseDao.kt`, `SpendingMapViewModel.kt`, `BusinessExpenseRepository.kt`
  - **Supporting tests that must be updated or created:** `ExpenseDaoTest.kt`, `SpendingMapViewModelStressTest.kt`, `BudgetRepositoryTruncationTest.kt`, `CashFlowCalculatorTest.kt`, `TaxEstimatorTest.kt`, `CategoryInsightEngineTest.kt`, `TotalsAggregationEngineTest.kt`, `FinancialHealthCalculatorBoundaryTest.kt`, create `BusinessExpenseReportGeneratorTest.kt`, create `RecurringIncomeTrackerTest.kt`
- **Assumptions / unknowns to keep explicit:**
  - A.1 remains the amount rule. A.10 only changes **which rows qualify** for a metric.
  - Existing DAO aggregate surfaces already treat spending as `PURCHASE`-only in several places; A.10 must align remaining in-memory scans to that standard.
  - `SpendingHeatmapEngine` receives `LocatedExpense`, which currently has no transaction type. Preferred fix: filter before mapping in `SpendingMapViewModel`; do **not** widen DTOs unless unavoidable.
  - `DailyCashFlow` has no dedicated transfer bucket. Preserve the public DTO shape; if transfers remain inside `income` / `expenses`, document those lists as inflow/outflow buckets, not pure spending.
  - If execution discovers a product requirement that ATM withdrawals must count as “spending,” stop and escalate. That would conflict with current purchase-only budget/totals semantics and with the epic text.
  - Negative `PURCHASE` refunds are **not** a reclassification target in A.10. Preserve current sign behavior; only fix transaction-type gating.

## 2. The Single Source of Truth (The Standard)

- **Canonical A.10 rule:**
  - **SQL spending filters:** one Room-safe DAO helper/constant that means “spending transaction” and resolves to `transactionType = 'PURCHASE'`.
  - **Kotlin in-memory spending filters:** one shared helper for transaction enums (preferably in `DomainTransactionType.kt` as the Kotlin mirror of the DAO rule) that returns `true` only for `PURCHASE`.
  - **Money math after the filter:** always use `effectiveAmount`, never raw `amount`, for spend-facing totals.
- **Standard formulas:**
  - **SQL spending aggregate:** `SUM(EFFECTIVE_AMOUNT_SQL)` over rows matching the canonical spending filter.
  - **Kotlin spending aggregate:** `expenses.filter { it.transactionType.isSpendingMetric() }.sumOf { it.effectiveAmount }`
- **Transaction semantics under A.10:**
  - `PURCHASE` → spending metric
  - `DEPOSIT` → inflow only, never spending
  - `TRANSFER` → account movement only, never spending
  - `WITHDRAWAL` → account movement/outflow only, never spending
  - `UNKNOWN` → exclude from spending unless some caller already has an explicit non-spend-compatible reason to keep it
- **Cross-epic invariants:**
  - A.1 ownership math stays canonical.
  - Do **not** globally change `ExpenseDao.getExpensesBetween()` or other generic range methods to `PURCHASE`-only; cash-flow and other movement-aware consumers still need full row sets.
  - Do **not** redefine spend as “positive amount” or “anything that is not a deposit.”
  - Do **not** change report formatting, budget formulas, health-score weights, or time-boundary semantics in A.10.

## 3. File-by-File Execution Checklist

Hard dependency: implement the shared transaction-semantics foundation first, then fix direct offenders, then audit already-compliant consumers and lock them in with tests. Audit-only/no-op is acceptable where the DAO/repository contract already resolves the issue.

### Batch 1 — Canonical transaction semantics foundation (3 files)
- **Scope:** `DomainTransactionType.kt`, `ExpenseDao.kt`, `ExpenseDaoTest.kt`
- **Why first:** every downstream batch needs one in-memory spending rule and one SQL spending rule.
- **Complete when:** one canonical spending filter exists in SQL and Kotlin, and DAO tests prove non-spend rows are excluded from spend-only queries.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/DomainTransactionType.kt`
  - Add one shared in-memory helper for spending semantics (`PURCHASE` only).
  - If needed, add the smallest possible companion/helper for the data-layer enum too, so epic-listed files can use the same rule without duplicating `== PURCHASE`.
  - Do **not** rename enum values, move packages, or refactor all enum mapping code across the app.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - Add one canonical Room-safe spending filter constant (and aliased variant only if needed).
  - Reuse that helper in the spend-facing queries that directly feed A.10 consumers when those queries are touched.
  - Keep generic row-range queries generic; do **not** silently turn them into spending-only queries.
  - Do **not** change schema, entities, migrations, or column names.
- [ ] `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`
  - Add mixed fixtures with `PURCHASE`, `DEPOSIT`, `TRANSFER`, `WITHDRAWAL`, and `UNKNOWN`.
  - Prove spend-facing aggregates exclude non-purchases.
  - Include one shared purchase and one `isNotMine` purchase so A.1 + A.10 behavior is verified together.

> [!WARNING]
> - Do **not** change `Expense.effectiveAmount`.
> - Do **not** change `ExpenseDao.getExpensesBetween()` semantics globally.
> - Do **not** introduce a second ad-hoc spend rule downstream once Batch 1 exists.

### Batch 2 — Heatmap input correction without changing map semantics broadly (3-4 files)
- **Scope:** `SpendingHeatmapEngine.kt`, `SpendingMapViewModel.kt`, `SpendingMapViewModelStressTest.kt`, optional `SpendingHeatmapEngineStressTest.kt`
- **Why next:** the heatmap is a direct user-visible false-positive surface.
- **Complete when:** only spending rows reach the heatmap path, while marker/location UI behavior remains stable unless explicitly intended otherwise.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt`
  - Update the contract/comments so the engine clearly operates on **already filtered spending-only** amounts.
  - Keep the clustering and normalization algorithm unchanged.
  - Only add runtime guards if upstream filtering cannot be made reliable without widening scope.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt`
  - Keep the full located-expense set for marker/state behavior unless product behavior explicitly says otherwise.
  - Derive the **heatmap subset** by filtering `filteredExpenses` through the canonical spending helper **before** mapping to `LocatedExpense`.
  - Ensure deposits/transfers/withdrawals no longer contribute to `heatmapPoints`.
  - Do **not** rewrite map filtering UI, stats refresh, or location assignment flows.
- [ ] `app/src/test/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModelStressTest.kt`
  - Add a regression where located purchase + deposit + transfer + withdrawal rows are present and only the purchase contributes to heatmap state.
  - Preserve existing state-management/performance assertions.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngineStressTest.kt` *(only if engine logic, not just caller filtering, changes)*
  - Update tests to match the new engine contract.
  - Do not manufacture transaction-type tests here if the caller fully owns filtering.

> [!WARNING]
> - Do **not** “fix” the heatmap by changing clustering math.
> - Do **not** widen `LocatedExpense` unless caller-side filtering proves impossible.

### Batch 3 — Budget and tax spend-surface confirmation (4 files)
- **Scope:** `BudgetRepository.kt`, `TaxEstimator.kt`, `BudgetRepositoryTruncationTest.kt`, `TaxEstimatorTest.kt`
- **Why now:** both are financially sensitive and appear mostly DAO-driven already.
- **Complete when:** both files are either unchanged-but-proven compliant or minimally updated to use the canonical helper/contract language.

- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - Audit `getBudgetStatuses()` and `getSuggestions()` and confirm they still depend on purchase-only DAO aggregates.
  - If already compliant, prefer no-op production code plus regression tests over cosmetic edits.
  - Do **not** change rollover math, period anchoring, or alert thresholds.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt`
  - Audit VAT and deductible calculations against the new canonical spend rule.
  - If already compliant, keep production code stable and lock the behavior in tests.
  - Do **not** fix unrelated annualization or tax-bracket issues here.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRepositoryTruncationTest.kt`
  - Add mixed-type fixtures proving deposits/transfers/withdrawals do not affect budget spend.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/tax/TaxEstimatorTest.kt`
  - Add mixed-type fixtures proving only purchase/business-purchase totals affect VAT/deductible math.

> [!WARNING]
> - Do **not** broaden budgets into “all outflows count as spend.”
> - Do **not** change tax policy/configuration behavior in A.10.

### Batch 4 — Business report surface audit (3 files)
- **Scope:** `BusinessExpenseReportGenerator.kt`, `BusinessExpenseRepository.kt`, create `BusinessExpenseReportGeneratorTest.kt`
- **Why now:** business reports must not re-admit non-spend movements via row lists, ranking, or CSV export.
- **Complete when:** totals, top expenses, missing-receipt rows, and CSV output are proven purchase-only for business spend.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGenerator.kt`
  - Audit all report surfaces and confirm they are backed by purchase-only business rows plus `effectiveAmount`.
  - If already compliant, keep changes minimal and test-driven.
  - Do **not** change report layout, mileage logic, or CSV column order.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/BusinessExpenseRepository.kt`
  - Keep this as a thin DAO pass-through.
  - Do **not** duplicate spending filters here if the DAO already owns them.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGeneratorTest.kt`
  - Create focused regressions for purchase vs deposit/transfer/withdrawal business rows.
  - Assert only purchase rows appear in totals/rankings/missing-receipt lines/CSV spend lines.

> [!WARNING]
> - Do **not** mix A.10 with receipt-storage, business-mileage, or schema changes.
> - Do **not** treat `isBusinessExpense = 1` as sufficient spend semantics without transaction type.

### Batch 5 — Cash-flow vs spending semantics separation (4 files)
- **Scope:** `CashFlowCalculator.kt`, `RecurringIncomeTracker.kt`, `CashFlowCalculatorTest.kt`, create `RecurringIncomeTrackerTest.kt`
- **Why now:** these are the only epic-listed files where non-spend movements are intentionally valid in some calculations.
- **Complete when:** balance math remains movement-aware, while spend-side ratios stop counting non-spend rows as spending.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt`
  - Replace “deposit or negative amount = income, else expense” with explicit movement classification.
  - Recommended movement rule:
    - deposit → inflow
    - transfer incoming → inflow
    - transfer outgoing → outflow
    - purchase → outflow
    - withdrawal → outflow
    - unknown → excluded from spending math; keep only the minimal legacy fallback necessary for balance compatibility
  - Preserve `DailyCashFlow` shape and date bucketing.
  - If transfers remain inside `income` / `expenses`, document them as movement buckets, not spend buckets.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/income/RecurringIncomeTracker.kt`
  - Keep recurring-income detection deposit-only.
  - Change `getIncomeExpenseRatio()` so:
    - income side = deposit semantics
    - expense side = canonical spending semantics only
  - Prefer aggregate totals where that reduces row-level type-branching without changing behavior.
  - Do **not** change frequency detection or confidence scoring.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculatorTest.kt`
  - Add incoming deposit, outgoing transfer, incoming transfer, withdrawal, and purchase fixtures.
  - Assert daily balances include intended movements while spend-only interpretation is not leaked.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/income/RecurringIncomeTrackerTest.kt`
  - Create regressions proving deposits drive recurring income and transfers/withdrawals do not inflate the spending side of the ratio.

> [!WARNING]
> - Do **not** collapse cash flow into purchase-only math.
> - Do **not** invent transfer direction from merchant text in A.10; use existing stored type/direction/sign information only.

### Batch 6 — Financial health spending correction (2-3 files)
- **Scope:** `FinancialHealthCalculator.kt`, `FinancialHealthCalculatorBoundaryTest.kt`, optional create `FinancialHealthCalculatorTransactionTypeTest.kt`
- **Why now:** health scores are spend-control metrics and currently sum all rows.
- **Complete when:** today/week/month spending totals and volatility ignore deposits/transfers/withdrawals while preserving A.5 boundary behavior.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
  - Filter each period’s rows through canonical spending semantics before computing spend totals and daily volatility.
  - Keep score weights, thresholds, streak math, and boundary utilities unchanged.
  - Do **not** touch the separate dashboard migration TODO that currently passes `emptyList()`.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBoundaryTest.kt`
  - Add mixed-type fixtures proving non-spend rows do not alter spend-control scores while boundary behavior stays correct.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorTransactionTypeTest.kt` *(only if needed)*
  - Add focused non-boundary regressions if the boundary test becomes too overloaded.

> [!WARNING]
> - Do **not** change `calculateHealthScores()` signature.
> - Do **not** mix this batch with widget/API redesign work.

### Batch 7 — Category and totals audit lock-in (4-5 files)
- **Scope:** `CategoryInsightEngine.kt`, `TotalsAggregationEngine.kt`, `CategoryInsightEngineTest.kt`, `TotalsAggregationEngineTest.kt`, optional `TotalsAggregationEngineValidationTest.kt`
- **Why last:** these appear mostly compliant already and should be closed out with minimal/no-op changes plus regression coverage.
- **Complete when:** both files are either unchanged-but-proven compliant or minimally updated to align with the shared helper.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
  - Audit current purchase-only filtering against the shared helper.
  - If already compliant, keep behavior stable and only reduce duplication if the edit is tiny and low-risk.
  - Do **not** change category ranking, fallback handling, or telemetry behavior.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
  - Audit repository-backed totals/averages and confirm they remain spend-only.
  - If already compliant, prefer no-op production code plus test coverage.
  - Do **not** change period labeling, averages, or status rules.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngineTest.kt`
  - Add mixed-type fixtures and assert only purchases affect category totals/percentages.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineTest.kt`
  - Add repository fixtures showing engine outputs represent spending-only totals even when mixed transaction types exist upstream.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineValidationTest.kt` *(only if extra lock-in is needed)*
  - Add a narrow regression around spend-only repository assumptions.

> [!WARNING]
> - Audit-only means no-op is acceptable.
> - Do **not** manufacture refactors just to touch already-compliant files.

## 4. Verification Plan

- **Compile after every code micro-batch:**
  - `./gradlew.bat :app:compileDebugKotlin`
- **Room / Android test validation:**
  - Rebuild compile-validated DAO queries.
  - Run DAO instrumentation coverage at minimum for `ExpenseDaoTest`.
- **Targeted tests by batch:**
  - **Batch 1:** `ExpenseDaoTest`
  - **Batch 2:** `SpendingMapViewModelStressTest`, `SpendingHeatmapEngineStressTest` *(if engine changed)*
  - **Batch 3:** `BudgetRepositoryTruncationTest`, `TaxEstimatorTest`
  - **Batch 4:** `BusinessExpenseReportGeneratorTest`
  - **Batch 5:** `CashFlowCalculatorTest`, `RecurringIncomeTrackerTest`
  - **Batch 6:** `FinancialHealthCalculatorBoundaryTest`, optional `FinancialHealthCalculatorTransactionTypeTest`
  - **Batch 7:** `CategoryInsightEngineTest`, `TotalsAggregationEngineTest`, optional `TotalsAggregationEngineValidationTest`
- **Epic full lane after all batches land:**
  - `./gradlew.bat :app:testDebugUnitTest`
- **Reviewer anti-regression checks:**
  - Confirm every epic-listed production file is either fixed or explicitly no-op audited.
  - Search epic files for unfiltered spend sums such as `sumOf { it.effectiveAmount }` and confirm each is either:
    - gated by the canonical spend helper, or
    - explicitly movement-aware cash-flow logic
  - Search for lingering heuristics like `expense.amount < 0` being used as a substitute for transaction-type semantics outside cash-flow logic.
  - Confirm generic full-range queries were **not** globally narrowed to purchases.
- **Required fixture mix for new/updated tests:**
  - at least one `PURCHASE`
  - at least one `DEPOSIT`
  - at least one `TRANSFER`
  - at least one `WITHDRAWAL`
  - one shared purchase with non-full `effectiveAmount`
  - one `isNotMine` purchase where relevant
  - one incoming vs outgoing transfer distinction where cash flow is tested
- **Must remain true after verification:**
  - no schema/entity/migration change
  - no public API break
  - no A.1 regression
  - no time-boundary regression
  - no report-layout/budget-formula/health-score-threshold drift

## 5. Documentation & Registry Updates (CRITICAL)

- **Documentation phase order (after review PASS only):**
  1. `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  2. exact final-verification files
  3. matching deep-analysis mirror files
  4. architecture docs only if Batch 1 introduced a reusable canonical spending helper worth documenting
- **Registry update:**
  - Update only the exact `### A.10: Transaction Type Blindness` block in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - Append `[RESOLVED BY A.10]` to that block only
  - Do **not** mark adjacent A-epics or B-pipelines resolved
- **Final-verification files to update after PASS:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-05.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-32.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-37.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
- **Deep-analysis mirror files to update after final-verification closeout:**
  - matching `DEEP-ANALYSIS-BATCH-02*.md`
  - matching `DEEP-ANALYSIS-BATCH-03*.md`
  - matching `DEEP-ANALYSIS-BATCH-05*.md`
  - matching `DEEP-ANALYSIS-BATCH-32*.md`
  - matching `DEEP-ANALYSIS-BATCH-33*.md`
  - matching `DEEP-ANALYSIS-BATCH-37*.md`
  - matching `DEEP-ANALYSIS-BATCH-39*.md`
  - matching `DEEP-ANALYSIS-BATCH-41*.md`
  - matching `DEEP-ANALYSIS-BATCH-42*.md`
  - matching `DEEP-ANALYSIS-BATCH-45*.md`
- **Architecture docs (only if Batch 1 adds a reusable helper/contract):**
  - `docs/reference/BACKEND-MAP-INDEX.md`
  - `docs/reference/BACKEND-DEPENDENCIES.md`
  - mirrored copies under `docs/analyses and debug master/` only if they are already maintained in sync
  - The note must explicitly say:
    - spending metrics now use the canonical transaction-type spending filter plus A.1 `effectiveAmount`
    - cash-flow/account-movement paths intentionally keep non-spend movement semantics
- **Docs micro-batching rule:**
  - keep docs edits to 1-5 files per patch
  - recommended docs order:
    - registry (+ architecture docs if needed)
    - final-verification files in two 5-file patches
    - deep-analysis mirrors in 5-file patches until complete

## Acceptance Criteria

- [ ] Every registry-listed A.10 production file is either fixed or explicitly audited no-op.
- [ ] One canonical spending rule exists for SQL and one for in-memory transaction checks.
- [ ] Spend-facing metrics use `PURCHASE`-only semantics plus `effectiveAmount`.
- [ ] Deposits/transfers/withdrawals no longer affect heatmap spend, budget spend, tax spend, business spend, health spend, category insights, totals aggregates, or the spending side of income-vs-expense ratio.
- [ ] Cash-flow/account-movement calculations still include intended non-spend movements without relabeling them as spending.
- [ ] Generic history/range queries were not globally narrowed to purchases.
- [ ] Negative/refund sign behavior remains unchanged.
- [ ] No schema/entity/public API breaks were introduced.
- [ ] Targeted tests, full unit lane, and DAO instrumentation coverage pass.
- [ ] Registry and affected batch docs are updated only after review PASS.

## Recommended Batch Order

1. Batch 1 — canonical transaction semantics foundation
2. Batch 2 — heatmap input correction
3. Batch 3 — budget and tax confirmation
4. Batch 4 — business report audit
5. Batch 5 — cash-flow vs spending separation
6. Batch 6 — financial health correction
7. Batch 7 — category/totals audit lock-in
8. full-epic review
9. docs micro-batches after PASS
