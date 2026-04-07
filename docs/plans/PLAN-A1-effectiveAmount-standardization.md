# PLAN A.1 — effectiveAmount Standardization

## 1. Objective & Blast Radius
- **The Core Issue:** Raw `amount` is being used in shared-expense-sensitive totals, rankings, filters, and displays where the correct value is the user-owned `effectiveAmount`. This overstates spend or income for shared and `isNotMine` rows across SQL, repositories, domain engines, reports, exports, and the transactions UI.
- **Blast Radius:**
  - **SQL / data access:** `ExpenseDao.kt`, `ExpenseRepository.kt`
  - **Analytics / dashboard:** `AdvancedAnalyticsDashboard.kt`, `AdvancedAnalyticsEngine.kt`, `InsightsEngine.kt`, `TotalsAggregationEngine.kt`, `FinancialWeatherRepository.kt`
  - **Budgeting / forecasting:** `BudgetRepository.kt`, `SharedBudgetManager.kt`, `BudgetForecastingEngine.kt`
  - **Currency / reporting / export:** `MultiCurrencyRepository.kt`, `BusinessExpenseReportGenerator.kt`, `TaxEstimator.kt`, `AccountingExportRepository.kt`
  - **Cashflow / income / challenges:** `RecurringIncomeTracker.kt`, `SpendingChallengeManager.kt`, `CashFlowCalculator.kt`
  - **Receipt matching / transaction presentation:** `ReceiptTransactionMatcher.kt`, `ExpenseWithCategory.kt`, `TransactionsScreen.kt`
  - **Supporting dependencies the coder must audit in the same pass:** `ExpenseWithCategory_Extensions.kt`, `BusinessExpenseRepository.kt`, `ExpenseDaoTest.kt`, `AdvancedAnalyticsDashboardTest.kt`, `SharedBudgetManagerTest.kt`, `BudgetForecastingEngineTest.kt`, `MultiCurrencyRepositoryTest.kt`, `CashFlowCalculatorTest.kt`, `SharedExpenseFlowTest.kt`, `MonthlyTotalFlowTest.kt`, `AnalyticsPipelineTest.kt`, `BudgetAlertPipelineTest.kt`, `GoldenMasterVerificationTest.kt`, `MultiCurrencyAnalyticsTest.kt`, `CrossGroupIntegrationTest.kt`
- **Assumptions / unknowns:**
  - The canonical ownership rule already lives in `Expense.effectiveAmount`; this epic must reuse that rule, not redefine it.
  - Some screens/reports may still need to expose the original posted amount. If so, keep raw `amount` only as explicitly labeled reference data; all totals, rankings, thresholds, and “your spend” displays must use `effectiveAmount`.

## 2. The Single Source of Truth (The Standard)
- The single source of truth for SQL ownership math is one shared helper owned by `ExpenseDao` and backed by Room-safe compile-time constants for `@Query` usage. Do not hand-write `CASE WHEN isSharedExpense ...` arithmetic anywhere else.
- Canonical rule:
  - **SQL aggregates, filters, ordering, thresholds, ranking:** use the shared `ExpenseDao` effective-amount SQL helper/constant.
  - **In-memory Kotlin math over `Expense`:** use `expense.effectiveAmount`.
  - **UI text that represents “your spend”, “your share”, “budget spent”, “converted total”, or “deductible total”:** derive from `effectiveAmount`.
  - **Raw `amount`:** only allowed for explicitly labeled original transaction/reference displays, never for ownership-sensitive totals.

## 3. File-by-File Execution Checklist
Hard dependency: implement the **Data Layer** helper/query work first, then update **Domain Layer** consumers, then finish **UI/ViewModel Layer** formatting. Do not mix partial raw/effective rules across files.

### Domain Layer
- [ ] `AdvancedAnalyticsDashboard.kt`
  - Replace every spend/income/category/merchant/monthly/weekly/weekend accumulation currently using `expense.amount` with `expense.effectiveAmount`.
  - Keep existing transaction-type branching and current period/window behavior intact.
  - Do **not** rewrite unrelated dispatcher or date-boundary logic in this epic.
- [ ] `AdvancedAnalyticsEngine.kt`
  - Audit remaining raw-amount reads and ensure any “largest transaction”, merchant stat, or ranking consumer uses effective-owned values.
  - Rely on helper-backed repository/DAO results for merchant statistics instead of reintroducing raw amount math locally.
  - Do **not** combine this with merchant-key canonicalization or performance refactors.
- [ ] `SharedBudgetManager.kt`
  - Compute `totalSpent`, `remaining`, `percentUsed`, `perMemberAverage`, and `isOverBudget` from `expense.effectiveAmount`.
  - Preserve current category scoping and placeholder member-contribution behavior.
- [ ] `BusinessExpenseReportGenerator.kt`
  - Use `effectiveAmount` for total business spend, top-expense ranking, printable report lines, missing-receipt lines, and CSV/exported amount fields.
  - Keep mileage calculations and report layout unchanged.
- [ ] `RecurringIncomeTracker.kt`
  - Use `effectiveAmount` when averaging recurring deposits and when calculating the income-vs-expense ratio.
  - Keep frequency detection and date prediction logic unchanged.
- [ ] `SpendingThresholdCalculator.kt`
  - Verify percentile inputs now come from effective-owned purchase amounts after the DAO helper migration.
  - Update comments/tests only if needed; do **not** change the percentile algorithm in this epic.
- [ ] `TaxEstimator.kt`
  - Use `effectiveAmount` for deductible totals, VAT estimates, and categorized deduction totals.
  - Leave tax bracket/configuration behavior unchanged.
- [ ] `ReceiptTransactionMatcher.kt`
  - Compare receipt totals against `transaction.effectiveAmount`, not raw `transaction.amount`.
  - Any positive-amount fallback checks must use effective-owned value as well.
  - Keep current score weights unless a regression test proves they must move.
- [ ] `SpendingChallengeManager.kt`
  - Use `effectiveAmount` for no-spend checks, streak-day totals, challenge progress, and average daily spend.
  - Do **not** fold unrelated floating-point-epsilon fixes into this change set.
- [ ] `InsightsEngine.kt`
  - Remove remaining raw `amount` usage from insight thresholds/copy (for example, largest-transaction insight display/threshold checks).
  - Audit `DashboardExpense`-based spending-pace paths so they cannot silently discard effective-share semantics.
  - Keep public overloads and return models stable.
- [ ] `BudgetForecastingEngine.kt`
  - Verify spent-to-date and historical totals stay helper-backed/effective-owned end-to-end.
  - Do **not** change forecast formulas, confidence rules, or seasonal logic here.
- [ ] `CashFlowCalculator.kt`
  - Calculate daily income/expense totals from `effectiveAmount` while leaving current transaction classification rules stable for this epic.
  - Ensure `startingBalance`/`endingBalance` derivations use the same effective-owned totals.
- [ ] `TotalsAggregationEngine.kt`
  - Verify monthly/weekly/daily/category totals and averages only consume helper-backed repository results.
  - Do **not** reopen zero-fill/timeline issues in this batch.
- [ ] `FinancialWeatherRepository.kt`
  - Verify all spending inputs passed into synthesis/narrative generation remain effective-owned after the repository/insights changes.
  - If no code change is required, still add regression coverage proving no raw-amount re-entry exists.

> [!WARNING]
> Do not change the `Expense` data class, the `effectiveAmount` getter logic, transaction enums, or time-window semantics while doing the domain-layer replacements.

### Data Layer
- [ ] `ExpenseDao.kt`
  - Introduce one shared effective-amount SQL source of truth and migrate every duplicated aggregate/ranking/filter expression to it.
  - Update at minimum: total-spent flows, category-spent queries, percentile input query, total-for-period queries, merchant/category totals, merchant stats, top merchants, largest-expense ordering, daily/weekly/monthly totals, average daily spend, location merchant totals, and business-expense totals/groupings.
  - Business-expense list/aggregate queries must be made ownership-safe and consistent with the downstream reporting/tax consumers.
  - Audit merchant-average surfaces (`AVG(amount)` style queries) and route them through the effective rule whenever the result represents user-owned typical spend.
- [ ] `ExpenseRepository.kt`
  - Replace the inline `effectiveAmountExpr` in `getExpensesPagedDynamic()` with the DAO-owned standard.
  - Make min/max amount filters and amount sorting effective-aware without renaming `SortOrder` values or changing repository method signatures.
  - Keep public API behavior backward-compatible.
- [ ] `BudgetRepository.kt`
  - Do not add new raw-spend math.
  - Confirm `getBudgetStatuses()` and `getSuggestions()` still resolve through helper-backed DAO totals.
  - Add regression coverage for shared-expense budget spend after the DAO change.
- [ ] `MultiCurrencyRepository.kt`
  - Convert `effectiveAmount` instead of raw `amount` in total conversion, per-currency totals, per-expense conversion results, category totals, merchant totals, and monthly totals.
  - Keep embedded `Expense` objects raw/unchanged; only conversion math changes.
- [ ] `AccountingExportRepository.kt`
  - Export effective-owned amounts into report/export payloads, summary totals, category totals, and large-transaction review lines.
  - Do **not** change exporter interfaces, file naming, or sharing behavior.
- [ ] `ExpenseWithCategory.kt`
  - Update amount-formatting helpers used for transaction summaries so they reflect effective-owned value wherever the UI is presenting user spend.
  - Leave embedded entity fields untouched.
- [ ] `ExpenseWithCategory_Extensions.kt` *(supporting dependency discovered during audit)*
  - Make the extension formatter match the same effective-amount rule so imported callers cannot keep rendering raw amounts accidentally.
  - Do not leave the member property and extension property with divergent behavior.
- [ ] `BusinessExpenseRepository.kt` *(check-only supporting dependency)*
  - Keep it as a thin DAO pass-through after DAO query fixes.
  - Do **not** duplicate aggregation logic here.

> [!WARNING]
> Do not change Room entities, `@Entity` annotations, table schemas, migrations, or column names. This epic must stay query-only and backward-compatible.

> [!WARNING]
> `ExpenseRepository` public APIs must not break. If internal helpers are added, hide them behind existing methods or add a deprecation path instead of signature churn.

> [!WARNING]
> Room must still be able to validate every `@Query`. Centralize the SQL in a Room-safe way; do not “solve” this by hand-inlining a second or third `CASE` copy somewhere else.

### UI / ViewModel Layer
- [ ] `TransactionsScreen.kt`
  - Ensure transaction rows and any owned-spend amount text render the effective-aware formatter.
  - Preserve existing grouped totals that already sum `it.expense.effectiveAmount`.
  - Do **not** alter filtering, pagination, edit flows, or persisted form values.

> [!WARNING]
> Do not persist `effectiveAmount` anywhere. Storage remains raw `amount` plus ownership fields; only presentation and calculations change.

## 4. Verification Plan
- **Unit Tests:**
  - Update `app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt`
    - Add shared-expense fixtures covering `myShareAmount`, `mySharePercentage`, and `isNotMine` for totals, percentile inputs, merchant/category aggregates, largest-expense ordering, and business-expense totals.
  - Update `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboardTest.kt`
    - Assert dashboard totals, top categories, top merchants, monthly trend, and weekly pattern use effective-owned values.
  - Update `app/src/test/java/com/yourname/expensetracker/domain/budget/SharedBudgetManagerTest.kt`
    - Replace raw-total expectations with effective-owned totals for shared rows.
  - Update `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngineTest.kt`
    - Add a shared-expense history fixture to prove forecast inputs use effective-owned values.
  - Update `app/src/test/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepositoryTest.kt`
    - Assert conversion inputs and per-currency/month/category/merchant totals use effective amounts.
  - Update `app/src/test/java/com/yourname/expensetracker/integration/MultiCurrencyAnalyticsTest.kt`
    - Add a shared-expense/missing-rate case proving the attempted conversion amount is the effective-owned amount.
  - Update `app/src/test/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculatorTest.kt`
    - Assert daily balances change by effective-owned expense totals, not raw totals.
  - Update `app/src/test/java/com/yourname/expensetracker/e2e/SharedExpenseFlowTest.kt`, `MonthlyTotalFlowTest.kt`, `AnalyticsPipelineTest.kt`, `BudgetAlertPipelineTest.kt`, `GoldenMasterVerificationTest.kt`, and `CrossGroupIntegrationTest.kt`
    - Enforce DAO → repository → engine → UI parity on shared-expense totals.
  - Update `app/src/test/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepositoryTest.kt` if pace or summary snapshots depend on the modified totals.
  - Create focused tests if no file already exists:
    - `app/src/test/java/com/yourname/expensetracker/domain/business/BusinessExpenseReportGeneratorTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/tax/TaxEstimatorTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcherTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManagerTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/domain/income/RecurringIncomeTrackerTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt`
  - Every new or updated test must include at least:
    - one shared purchase (`amount = 100`, `effectiveAmount = 40` or `50`),
    - one percentage-based shared purchase,
    - one `isNotMine` purchase contributing `0.0`,
    - and, where applicable, one deposit to validate income/cashflow behavior.
- **Syntax/Lint:**
  - Rebuild Room/KSP generated sources so every modified SQL query is compile-validated.
  - Ensure no imports are broken and no duplicate formatter imports remain around `ExpenseWithCategory` formatting.
  - Run targeted compile + unit test + instrumentation test passes; at minimum, compile the app module, run `testDebugUnitTest`, and run the DAO instrumentation suite covering `ExpenseDaoTest`.
  - Verify there is **no** Room schema change, migration change, dropped column, or altered table definition.
  - Verify the dynamic SQL path in `ExpenseRepository.getExpensesPagedDynamic()` still binds correctly with the centralized helper and aliasing.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, update only the `### A.1: effectiveAmount vs amount Inconsistency` block (the heading plus its `Batches affected`, `Severity`, `Description`, `Affected files`, and `Suggested fix` lines) to append `[RESOLVED BY A.1]` once the implementation and regression tests are merged.
  - Do **not** mark adjacent Section A epics as resolved.
- **Batch Reports:**
  - Update the affected batch report files to note this issue is now resolved:
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-01.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-05.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-12.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-16.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-17.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-29.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-32.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-37.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-38.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
  - If the same A.1 problem is explicitly mentioned in matching deep-analysis files (`DEEP-ANALYSIS-BATCH-01*.md`, `...02*.md`, `...03*.md`, `...05*.md`, `...12*.md`, `...16*.md`, `...17*.md`, `...29*.md`, `...32*.md`, `...33*.md`, `...36*.md`, `...37*.md`, `...38*.md`, `...41*.md`, `...45*.md`), add the same `[RESOLVED BY A.1]` note there as well.
  - Do **not** bulk-edit unrelated findings in those reports.
- **Architecture Maps:**
  - If this fix introduces a new shared DAO helper/constant or any new repository helper, update the architecture docs so the canonical aggregation path is documented:
    - `docs/reference/BACKEND-MAP-INDEX.md`
    - `docs/reference/BACKEND-DEPENDENCIES.md`
    - mirrored copies under `docs/analyses and debug master/` if they are kept in sync
  - The update must explicitly state that ownership-sensitive expense aggregation now flows through the centralized `ExpenseDao` effective-amount standard.
