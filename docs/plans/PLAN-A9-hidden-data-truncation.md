# PLAN-A9 — Hidden Data Truncation / DAO Default Limits

## 1. Objective & Blast Radius

- **The Core Issue:** Several DAO-backed methods promise complete history (`all`, `between`, `history`, `export`, `analysis`, `forecast`, `report`) but silently stop at default `LIMIT 500` or `LIMIT 2000`. Downstream consumers then calculate totals, trends, forecasts, exports, and tax estimates on partial datasets with no warning.
- **Blast Radius:** budget status/rollover, forecasting, autopilot, shared-budget progress, carbon reporting, cashflow, accounting export, financial weather, multi-currency totals, tax estimation, and any callers that depend on complete expense history through repository APIs.
- **Execution assumptions to keep explicit:**
  - `SpendingThresholdCalculator.kt` is likely already compliant and should be treated as audit-only unless A.9 changes prove otherwise.
  - `RecurringExpenseRepository.kt` is likely unaffected and should remain audit-only unless Batch 1 creates a new dependency.
  - `FinancialWeatherRepository.kt` may become compliant through repository-level fixes without direct edits.
  - If execution discovers additional direct DAO consumers outside the registry-listed scope, do not expand opportunistically; document and escalate only if they block A.9.

## 2. The Single Source of Truth (The Standard)

- **Canonical A.9 rule:** any method whose contract implies complete data must not silently return a capped subset.
- **Allowed completeness strategies:**
  1. **Aggregate SQL preferred** when callers need totals, counts, buckets, grouped summaries, or month/category/currency breakdowns.
  2. **Exhaustive paging preferred** when callers truly need row-level data or per-row heuristics.
  3. **Uncapped reactive observation preferred** only when the consumer semantically needs a complete live dataset.
  4. **`TruncationDetected` required only if** a full-data semantic must remain capped for compatibility and cannot be eliminated during A.9.
- **Cross-epic invariants that must not regress:**
  - Use effective-amount SQL helpers, never regress to raw `amount`.
  - Preserve half-open date ranges (`date >= start AND date < end`).
  - Preserve current localtime month/day bucketing semantics.
  - Preserve stable paging order (`date` + `id`, and export’s deterministic sort).
- **Aggregate SQL is preferable for:**
  - `BudgetRepository.kt`
  - `BudgetForecastingEngine.kt`
  - `BudgetAutopilotEngine.kt`
  - `SharedBudgetManager.kt`
  - `TaxEstimator.kt`
  - grouped totals in `MultiCurrencyRepository.kt`
- **Exhaustive paging is preferable for:**
  - `ExpenseRepository.kt` compatibility methods returning row lists
  - `AccountingExportRepository.kt`
  - `CarbonFootprintCalculator.kt`
  - `CashFlowCalculator.kt`
  - row-returning conversion methods in `MultiCurrencyRepository.kt`

## 3. File-by-File Execution Checklist

### Batch 1 — Foundation query contract and repository semantics (4 files)
- **Scope:** `ExpenseDao.kt`, `ExpenseRepository.kt`, `ExpenseRepositoryTest.kt`, `ExpenseRepositoryTruncationTest.kt`
- **Why first:** every later batch depends on the canonical repository/data-retrieval contract.
- **Complete when:** repository methods with full-data semantics no longer rely on DAO default caps, and later batches have the safe query surface they need.

- [ ] `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
  - Add explicit uncapped variants for “all” and “between” semantics used by A.9 consumers.
  - Add only the aggregate projections needed by later batches using effective-amount SQL.
  - Keep intentionally bounded paged/top-N/recent/worker-batch queries intact.
  - Do **not** change entities, schema, column names, or migrations.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
  - Make `getAllExpenses()`, `getExpensesBetween()`, and `getExpensesBetweenFlow()` completeness-safe.
  - Preserve public signatures; hide paging internally if needed.
  - Keep explicitly paged methods paged.
  - Add `TruncationDetected` only if silent capping truly cannot be removed.
  - Do **not** refactor unrelated repository behavior.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryTest.kt`
  - Update mocks/assertions for new uncapped or aggregate DAO methods.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryTruncationTest.kt`
  - Add regressions proving histories above 500 and 2000 rows are fully returned or explicitly surfaced as truncation.

> [!WARNING]
> - Do **not** “fix” A.9 by merely increasing limits.
> - Do **not** remove intentional pagination APIs.
> - Do **not** widen `TruncationDetected` into a global sealed result unless absolutely necessary.

### Batch 2 — Budget status and rollover completeness (3-4 files)
- **Scope:** `BudgetRepository.kt`, `BudgetRolloverTest.kt`, `BudgetRepositoryStressTest.kt`, optional `BudgetRepositoryTruncationTest.kt`
- [ ] Replace capped expense-window dependence with invalidation-trigger + aggregate-query behavior.
- [ ] Preserve rollover arithmetic, `TimeBoundaryTicker`, thresholds, and period-window logic.
- [ ] Add large-history coverage only if existing tests cannot prove correctness.

> [!WARNING]
> - Do **not** rewrite the budget algorithm.
> - Do **not** change anchor semantics under A.9.

### Batch 3 — Forecasting and autopilot monthly history (5 files)
- **Scope:** `BudgetForecastingEngine.kt`, `BudgetAutopilotEngine.kt`, `BudgetForecastingEngineTest.kt`, `BudgetTrendBoundaryTest.kt`, `BudgetAutopilotEngineTest.kt`
- [ ] Replace capped raw history reads with complete monthly aggregate history.
- [ ] Preserve month ordering, confidence math, thresholds, and UI-facing output.

### Batch 4 — Shared budget and tax aggregate consumers (4 files)
- **Scope:** `SharedBudgetManager.kt`, `TaxEstimator.kt`, `SharedBudgetManagerTest.kt`, `TaxEstimatorTest.kt`
- [ ] Replace row scans with aggregate totals.
- [ ] Preserve null-category behavior, shared-budget placeholder semantics, and tax policy assumptions.

### Batch 5 — Multi-currency completeness and grouped conversion (3 files)
- **Scope:** `MultiCurrencyRepository.kt`, `MultiCurrencyRepositoryTest.kt`, `MultiCurrencyAnalyticsTest.kt`
- [ ] Use aggregate SQL for totals and grouped breakdowns.
- [ ] Keep row-returning conversion complete via exhaustive paging if still required.
- [ ] Preserve missing-rate behavior and warning semantics.

### Batch 6 — Export and cashflow row completeness (4 files)
- **Scope:** `AccountingExportRepository.kt`, `CashFlowCalculator.kt`, `CashFlowCalculatorTest.kt`, `AccountingExportRepositoryTest.kt`
- [ ] Export through deterministic exhaustive paging, not capped “between” calls.
- [ ] Make cashflow complete without changing balance math, grouping, or risk thresholds.

### Batch 7 — Carbon reporting completeness (3-4 files)
- **Scope:** `CarbonFootprintCalculator.kt`, `CarbonFootprintCalculatorTest.kt`, `CarbonFootprintTest.kt`, optional `CrossGroupIntegrationTest.kt`
- [ ] Replace capped range flow usage with a complete one-shot retrieval strategy.
- [ ] Preserve merchant/category heuristics, recommendation text, trend shape, and effective-amount usage.

### Batch 8 — Consumer follow-through audits / no-op confirmation (3-5 files)
- **Scope:** `FinancialWeatherRepository.kt`, `SpendingThresholdCalculator.kt`, `RecurringExpenseRepository.kt`, plus tests only if touched
- [ ] Audit first.
- [ ] Leave files unchanged if repository-level fixes already resolve A.9 for them.
- [ ] If touched, keep changes minimal and avoid widening scope.

> [!WARNING]
> - Audit-only means no-op is acceptable.
> - Do **not** manufacture edits just because a file appears in the registry list.

## 4. Verification Plan

- **Compile after every micro-batch:**
  - `./gradlew.bat :app:compileDebugKotlin`
- **Focused tests by batch:**
  - **Batch 1:** `*ExpenseRepositoryTest`, `*ExpenseRepositoryTruncationTest`
  - **Batch 2:** `*BudgetRolloverTest`, `*BudgetRepositoryStressTest`, optional `*BudgetRepositoryTruncationTest`
  - **Batch 3:** `*BudgetForecastingEngineTest`, `*BudgetTrendBoundaryTest`, `*BudgetAutopilotEngineTest`
  - **Batch 4:** `*SharedBudgetManagerTest`, `*TaxEstimatorTest`
  - **Batch 5:** `*MultiCurrencyRepositoryTest`, `*MultiCurrencyAnalyticsTest`
  - **Batch 6:** `*CashFlowCalculatorTest`, `*AccountingExportRepositoryTest`
  - **Batch 7:** `*CarbonFootprintCalculatorTest`, `*CarbonFootprintTest`, optional `*CrossGroupIntegrationTest`
  - **Batch 8:** `*FinancialWeatherRepositoryTest` and/or `*SpendingThresholdCalculatorTest` only if touched
- **Epic full lane after all code batches land:**
  - `./gradlew.bat :app:testDebugUnitTest`
- **Review rules:**
  - Reviewer must confirm each registry-listed file is either fixed or explicitly no-op audited.
  - Any residual silent truncation must be fixed one issue at a time in 1-3 file patches.

## 5. Documentation & Registry Updates (CRITICAL)

- **Documentation phase order:**
  1. `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  2. exact `FINAL-VERIFICATION-BATCH-XX.md` rows only
  3. matching `DEEP-ANALYSIS-BATCH-XX*.md` mirror rows only
- **Registry update after reviewer PASS:**
  - Mark only the exact `### A.9: Hidden Data Truncation / DAO Default Limits` block as `[RESOLVED BY A.9]`.
- **Final-verification files to update after PASS:**
  - `FINAL-VERIFICATION-BATCH-01.md`
  - `FINAL-VERIFICATION-BATCH-02.md`
  - `FINAL-VERIFICATION-BATCH-03.md`
  - `FINAL-VERIFICATION-BATCH-05.md`
  - `FINAL-VERIFICATION-BATCH-14.md`
  - `FINAL-VERIFICATION-BATCH-27.md`
  - `FINAL-VERIFICATION-BATCH-32.md`
  - `FINAL-VERIFICATION-BATCH-33.md`
  - `FINAL-VERIFICATION-BATCH-37.md`
  - `FINAL-VERIFICATION-BATCH-39.md`
  - `FINAL-VERIFICATION-BATCH-41.md`
  - `FINAL-VERIFICATION-BATCH-44.md`
  - `FINAL-VERIFICATION-BATCH-45.md`
- **Deep-analysis mirrors to update after final-verification closeout:**
  - matching `DEEP-ANALYSIS-BATCH-01*.md`, `02*.md`, `03*.md`, `05*.md`, `14*.md`, `27*.md`, `32*.md`, `33*.md`, `37*.md`, `39*.md`, `41*.md`, `44*.md`, `45*.md`
- **Docs constraint:** do not mark rows resolved for audit-only no-op files unless reviewer confirms they are resolved via the implemented contract changes.

## Acceptance Criteria

- [ ] Every registry-listed A.9 production file is either fixed or explicitly audited as no-op, with no silent default-cap behavior left in targeted paths.
- [ ] `ExpenseRepository.getAllExpenses()`, `getExpensesBetween()`, and `getExpensesBetweenFlow()` no longer silently return capped subsets.
- [ ] Aggregate-only consumers use SQL aggregation instead of capped raw row reads.
- [ ] Row-sensitive consumers use exhaustive paging or uncapped one-shot retrieval.
- [ ] Any retained capped full-data path emits a typed `TruncationDetected` signal and never reports partial data as complete.
- [ ] No Room entity/schema/migration changes were made.
- [ ] No public repository API was broken.
- [ ] All new SQL preserves effective-amount semantics and half-open date windows.
- [ ] `:app:compileDebugKotlin` and `:app:testDebugUnitTest` pass for the epic.
- [ ] Registry and affected batch docs are updated only after review PASS.

## Recommended Batch Order

1. Batch 1 — foundation query contract
2. Batch 2 — budget status / rollover
3. Batch 3 — forecasting / autopilot
4. Batch 4 — shared budget / tax
5. Batch 5 — multi-currency
6. Batch 6 — export / cashflow
7. Batch 7 — carbon
8. Batch 8 — audit-only follow-through
9. review → docs micro-batches
