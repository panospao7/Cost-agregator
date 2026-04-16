## Technical Plan

### Scope
- In: all **CRITICAL/HIGH** rows under `### B.8: Savings/Investment Pipeline` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`. Current registry state has **no CRITICAL rows**, so execution scope is the B.8 **HIGH** items only:
  - automated savings rule validation / calendar-week idempotency / durable monthly caps
  - Smart Savings portfolio allocation + budget-surplus double-count prevention
  - savings gamification streaks based on real contribution history
  - investment fee-aware cost basis + per-day portfolio history de-duplication
  - tax estimator progressive brackets / period-aligned income / business-only VAT scope / real year summary income
  - financial health budget-target normalization and requested-period budget status lookup
- Out: every B.8 **MEDIUM/LOW** row, B.2 month-end sweep medium fixes, B.48 allocation-display cleanup, B.03 exception-swallowing cleanup in `FinancialHealthScoreV2`, UI redesigns, schema/entity/migration changes, and any opportunistic dashboard-boundary refactor.
- Assumptions / unknowns:
  - Several B.8 HIGH rows appear partially or fully fixed already in live code (notably `InvestmentTracker` latest-snapshot selection, `FinancialHealthCalculator` purchase-only filtering, and effective-amount business deduction aggregation). Treat the live source/tests as truth; prefer regression lock-in + doc closure over unnecessary production churn.
  - There is no existing persistent ledger for automated-rule execution state or savings contributions. This plan assumes a **DataStore-backed repository** is acceptable because Room schema changes are forbidden.
  - `TaxEstimator` callers appear to use month/year windows. If multi-tax-year ranges are discovered during implementation, split or explicitly constrain the fix rather than guessing cross-year policy.
  - `BudgetRepository` currently exposes only `getBudgetStatuses()` for `timeProvider.now()`. If B.2 lands first with a historical-status helper, B.8 must reuse that helper instead of creating a second source of truth.

### Files
- create: `app/src/main/java/com/yourname/expensetracker/data/repository/AutomatedSavingsRuleStateRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/di/SavingsModule.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/AutomatedSavingsRuleStateRepositoryTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngineTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt`
- create: `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepository.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepositoryTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngineTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/investment/InvestmentTrackerTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/tax/TaxEstimatorTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- create: `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRepositoryHistoricalStatusTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2Test.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
- create: `docs/reviews/REVIEW-B8.md`

### 1. Objective & Blast Radius
- **Core issue:** B.8 still contains high-severity drift where savings recommendations, automated rule execution, gamification streaks, investment cost basis, tax estimation, and financial-health budgets all compute against inconsistent or fabricated sources of truth. The implementation must close only the B.8 **HIGH** issues without widening into medium/low cleanup or schema work.
- **Blast radius:**
  - `domain/savings/` recommendation and rule engines
  - savings view-model contribution flows
  - investment portfolio summaries/history
  - tax estimation used by the tax screen
  - `BudgetRepository` status derivation for historical evaluation
  - `domain/health/` calculators and downstream dashboard consumers
  - registry/final-verification documentation for Batches 03, 41, 45

> [!WARNING]
> - Do **not** change Room entities, `AppDatabase`, schema versions, migrations, indices, or column names.
> - Do **not** fold B.8 MEDIUM/LOW items into this plan, especially `MonthlySavingsSweepUseCase` medium rows, Monte Carlo fallback cleanup, and allocation-percentage cosmetic drift.
> - Do **not** remove `FinancialHealthScoreV2`'s broad exception fallback in this plan unless a reviewer explicitly reclassifies that separate B.03 issue into B.8 scope.
> - Do **not** widen into `ComputeDashboardWidgetsUseCase`'s pre-existing `expenses = emptyList()` TODO unless review proves it blocks the exact B.8 HIGH fixes. Record that separately if still live.

### 2. The Single Source of Truth
- **Automated savings rule state:** durable rule execution state must live in one persistent repository (`AutomatedSavingsRuleStateRepository`) keyed by stable rule identity + calendar period. In-memory maps are not allowed for monthly-cap enforcement or weekly idempotency.
- **Weekly no-spend semantics:** `WEEKLY_NO_SPEND` must evaluate against the stable calendar week returned by `TimePeriodUtils.getWeekRange(now)`, not a rolling `now - 7 days` window.
- **Savings recommendation semantics:** `SmartSavingsEngine` must compute one **portfolio-level** safe-to-save amount, then allocate that amount across active goals. A per-goal call must never hand the full portfolio amount to every goal.
- **Budget scope semantics for savings/health:** if an active overall budget exists, wallet-wide surplus/target calculations use that overall budget only. Category budgets are used only when no overall budget exists.
- **Savings streak semantics:** streaks must come from recorded contribution events, not inferred goal creation timestamps or hardcoded placeholders. No historical backfill by fabrication.
- **Investment cost basis:** invested amount = `(purchasePrice * quantity) + purchaseFees` everywhere B.8 touches gains/losses.
- **Portfolio daily history:** each investment contributes at most one snapshot per calendar day to `getPortfolioValueHistory()`; intra-day multiple updates must collapse to the latest snapshot for that investment/day.
- **Tax semantics:**
  - deductible + VAT bases must use business-only, purchase-only, effective-amount aggregates
  - progressive brackets must be applied cumulatively, not as a single flat bracket rate
  - period tax estimates must align income to the requested period instead of assuming “any non-zero period = one month”
  - `getTaxYearSummary()` must use real yearly income (deposit aggregate) and must not annualize already-annual values
- **Historical budget semantics for health:** `FinancialHealthScoreV2` must resolve budget statuses for the requested evaluation period, not for `timeProvider.now()`. `BudgetRepository` must expose one explicit-time helper rather than duplicating status math.
- **Legacy health spending-control semantics:** `FinancialHealthCalculator` must normalize budget targets by actual budget-window overlap and must not double-count overall + category budgets.

> [!WARNING]
> - Prefer narrow helper extraction over broad architecture rewrites.
> - If B.2 already introduced a canonical historical-budget helper or budget-normalization utility, reuse it; do **not** create a competing implementation in B.8.
> - For legacy users with existing goal balances but no recorded contribution events, return honest “no recorded streak history” behavior (zero streak / no fabricated monthly contributions). Do **not** synthesize backfilled events from `currentAmount`.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1 — Durable automated-rule state foundation
- Files:
  - create: `app/src/main/java/com/yourname/expensetracker/data/repository/AutomatedSavingsRuleStateRepository.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/repository/AutomatedSavingsRuleStateRepositoryTest.kt`
- Checklist:
  - [ ] Add one persistent repository for automated-savings state using DataStore (or equivalent non-Room storage already accepted in-app).
  - [ ] Repository must expose atomic period-scoped operations for:
    - reserving/checking a weekly no-spend reward for `(ruleStableKey, weekStart)`
    - reading/consuming month-to-date cap totals for `(ruleStableKey, yearMonth)`
  - [ ] State keys must be deterministic across process death and must not depend on transient object identity.
  - [ ] Repository must prune obsolete month/week entries so state does not grow without bound.
  - [ ] Test serialization and atomic update semantics; prove state survives repository recreation in the same test process.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepositoryTest"`
- Completion criteria:
  - [ ] No in-memory-only rule-state assumption remains in the planned engine path.
  - [ ] Persistence works without Room/schema changes.
- Stop / rollback rule:
  - If the chosen persistence approach requires new database tables or migrations, stop and redesign around existing key-value persistence.

#### Batch 2 — AutomatedSavingsRuleEngine validation + stable period semantics
- Dependency: after Batch 1.
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngine.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/di/SavingsModule.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngineTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt`
- Checklist:
  - [ ] Inject the durable state repository into `AutomatedSavingsRuleEngine` via `SavingsModule`.
  - [ ] Reject negative, `NaN`, and infinite `PERCENTAGE_OF_INCOME` values before any rule execution is emitted.
  - [ ] Replace rolling `now - 7 days` with `TimePeriodUtils.getWeekRange(now)` for `WEEKLY_NO_SPEND`.
  - [ ] Use the persistent state repository to make weekly no-spend rewards idempotent per stable week.
  - [ ] Replace the singleton mutable map monthly-cap logic with persistent month-to-date consumption from the state repository.
  - [ ] Keep public rule/result models stable; do **not** opportunistically remove unrelated injected dependencies or redesign rule DTOs.
  - [ ] Extend tests for invalid percentages, stable week boundaries, repeated `evaluateRules()` calls within one week, and process-recreated monthly cap state.
  - [ ] Keep the round-up golden case unchanged.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.AutomatedSavingsRuleEngineTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.AutomatedSavingsRuleEngineGoldenTest"`
- Completion criteria:
  - [ ] Invalid percentage rules emit nothing.
  - [ ] A qualifying no-spend week can mint at most one reward per rule/week.
  - [ ] Monthly caps survive process death/recreation.
- Stop / rollback rule:
  - If idempotency can be achieved only by changing rule DTO persistence or database schema, stop and split; that is outside B.8 HIGH scope.

#### Batch 3 — Portfolio-scoped smart savings recommendations
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngineTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt`
- Checklist:
  - [ ] Make `calculateBudgetSurplus()` honor overall-budget precedence so overall + category budgets are not double-counted.
  - [ ] Introduce one portfolio-level recommendation path that computes the safe amount once, then allocates across incomplete goals by remaining gap / urgency.
  - [ ] Keep backward compatibility: if a single-goal API remains public, make it delegate to the portfolio allocator rather than keeping two divergent implementations.
  - [ ] Update `SavingsGoalsViewModel` to consume one canonical portfolio allocation path instead of calling a portfolio-wide computation independently for every goal.
  - [ ] Prevent any goal from receiving more than its remaining gap.
  - [ ] Extend tests so multiple goals no longer each receive the full portfolio recommendation and overall-budget + category-budget coexistence does not inflate surplus.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.SmartSavingsEngineTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.savings.SavingsGoalsViewModelTest"`
- Completion criteria:
  - [ ] Smart recommendations sum to at most the portfolio-safe amount.
  - [ ] Overall budgets no longer stack with category budgets in surplus math.
- Stop / rollback rule:
  - Do **not** widen into B.8 MEDIUM `WEEK/QUARTER` Monte Carlo horizon cleanup here.

#### Batch 4 — Savings contribution history foundation
- Files:
  - create: `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepository.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepositoryTest.kt`
- Checklist:
  - [ ] Add one persistent contribution-history repository for savings events (goalId, amount, timestamp, optional source).
  - [ ] Keep storage non-Room and prune old events on write/read to bound state size.
  - [ ] Support append + query-by-date-range / full-history retrieval sufficient for streak and monthly summary calculations.
  - [ ] Tests must prove recorded events remain readable after repository recreation and that pruning does not remove current-month/current-streak events.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepositoryTest"`
- Completion criteria:
  - [ ] A durable source for real contribution history exists before gamification logic changes.
- Stop / rollback rule:
  - If this batch starts drifting toward a general event-sourcing framework, stop and keep the repository narrowly scoped to savings contributions only.

#### Batch 5 — SavingsGamificationEngine uses real contribution history
- Dependency: after Batch 4.
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/di/SavingsModule.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngineTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt`
- Checklist:
  - [ ] Inject contribution history into `SavingsGamificationEngine` and compute:
    - `lastSavingsDate` from the latest real contribution event
    - `monthlyContributions` and `totalContributedThisMonth` from current-month events
    - `currentStreakDays` from consecutive contribution days
    - `personalBestDays` from the longest consecutive run in recorded history
  - [ ] Replace the hardcoded `5`-day placeholder and `goal.createdAt` inference entirely.
  - [ ] Update the streak achievement (`saving_streak_7`) to reflect real streak progress/unlock state instead of static `false` / `0.3`.
  - [ ] Record contribution events only from real mutation paths in `SavingsGoalsViewModel` (`contributeToGoal`, accepted sweep allocations, and any other touched high-scope goal-increase path in that file). Record only after the amount update succeeds.
  - [ ] Keep legacy users honest: if there is no recorded history, do not fabricate streaks from existing balances.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.SavingsGamificationEngineTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.savings.SavingsGoalsViewModelTest"`
- Completion criteria:
  - [ ] Streaks/achievement progress are backed by recorded contribution events.
  - [ ] Manual contributions and accepted sweeps produce history entries.
- Stop / rollback rule:
  - Do **not** attempt historical reconstruction from `currentAmount`, `createdAt`, or `SavingsSweepPlan` rows alone.

#### Batch 6 — InvestmentTracker fee-aware accounting + daily history de-duplication
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/investment/InvestmentTrackerTest.kt`
- Checklist:
  - [ ] Include `purchaseFees` in cost basis for portfolio summary and single-investment gain/loss calculations.
  - [ ] Make `getPortfolioValueHistory()` collapse multiple same-day snapshots to the latest snapshot per investment/day before summing across investments.
  - [ ] Audit the existing `getInvestmentPerformance()` day-change selection logic first; if `lastOrNull()` is already correct, keep production code stable and add/retain regression proof only.
  - [ ] Add tests for fee-aware gain/loss, fee-aware gain/loss percent, and duplicate same-day snapshot collapse.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.investment.InvestmentTrackerTest"`
- Completion criteria:
  - [ ] Portfolio / per-investment gains no longer overstate returns when fees exist.
  - [ ] Daily portfolio history emits one summed value per day without intra-day double counting.
- Stop / rollback rule:
  - Do **not** change entities/DAOs unless a test proves the in-memory reduction cannot express the required fix.

#### Batch 7 — TaxEstimator progressive, period-aligned, business-only calculations
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/tax/TaxEstimatorTest.kt`
- Checklist:
  - [ ] Replace flat single-bracket application with cumulative progressive bracket tax calculation.
  - [ ] Align income to the requested period instead of collapsing any non-zero period to one month.
  - [ ] Keep deductible expenses on effective-amount business aggregates; if live code is already compliant, preserve code and add/retain regression coverage instead of churning.
  - [ ] Restrict VAT paid to business-only purchase spend, not all purchases.
  - [ ] Replace `getTaxYearSummary()` hardcoded `30000.0` with real deposit aggregate income for the target year (`ExpenseDao.getTotalDepositsForPeriod(...)` or equivalent existing aggregate).
  - [ ] Remove annualizing multipliers from `getTaxYearSummary()` once `estimateTaxes()` returns true period-aligned values.
  - [ ] Add tests for progressive brackets, monthly-period alignment, business-only VAT scope, and non-hardcoded year summary income.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.tax.TaxEstimatorTest"`
- Completion criteria:
  - [ ] Tax estimates no longer use a flat bracket.
  - [ ] Full-year summaries no longer depend on a hardcoded annual income.
  - [ ] VAT base is business-only.
- Stop / rollback rule:
  - If cross-year period handling becomes ambiguous during implementation, constrain the helper to the currently used same-year windows and document the limitation instead of guessing tax policy.

#### Batch 8 — Historical budget-status helper for requested-period health
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/data/repository/BudgetRepositoryHistoricalStatusTest.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2Test.kt`
- Checklist:
  - [ ] Add one explicit-time budget-status helper in `BudgetRepository` (for example `getBudgetStatusesAt(evaluationTime: Long)`) that reuses the same internal aggregate/status derivation as the reactive `getBudgetStatuses()` path.
  - [ ] Avoid duplicating budget-status math; extract shared internal logic if needed so current-time and explicit-time status resolution cannot drift.
  - [ ] Update `FinancialHealthScoreV2.calculateHealthScore(periodStart, periodEnd)` to resolve budget statuses for the requested period end (or the nearest valid in-period evaluation time), not `timeProvider.now()`.
  - [ ] Keep the existing public `calculateHealthScore(periodStart, periodEnd)` signature intact.
  - [ ] Extend tests to prove a historical period uses historical budget windows/statuses rather than current-time statuses.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.BudgetRepositoryHistoricalStatusTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthScoreV2Test"`
- Completion criteria:
  - [ ] `FinancialHealthScoreV2` no longer depends on `getBudgetStatuses().first()` for the wrong period.
  - [ ] Historical and current-time status derivation share one implementation path.
- Stop / rollback rule:
  - Do **not** turn this into a broader B.2 budget-refactor batch; keep the helper narrowly scoped to historical status lookup.

#### Batch 9 — FinancialHealthCalculator budget-target normalization + stale-row audit lock
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
  - create: `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt`
- Checklist:
  - [ ] Normalize spending-control targets by actual budget-window overlap instead of blindly summing raw budget amounts and dividing by fixed `/30` or `/4` heuristics.
  - [ ] Apply overall-budget precedence so aggregate wallet targets do not double-count overall + category budgets.
  - [ ] Preserve the already-live purchase-only filtering if audit confirms it is fixed; do **not** rework transaction filtering unnecessarily.
  - [ ] Add focused regressions for mixed daily/weekly/monthly/yearly budgets and overall+category coexistence.
  - [ ] Use this batch to live-audit the stale non-purchase-row registry item: if production code is already correct, lock it with tests/doc rather than reshaping the calculator.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorBudgetNormalizationTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorTransactionTypeTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorBoundaryTest"`
- Completion criteria:
  - [ ] Spending-control targets no longer mix unnormalized budget periods or double-count overall + category budgets.
  - [ ] The stale non-purchase-row issue is either code-fixed or explicitly documented as already resolved in live code.
- Stop / rollback rule:
  - Do **not** widen into dashboard DTO boundary cleanup or other `ComputeDashboardWidgetsUseCase` TODOs.

#### Batch 10 — Documentation closeout for HIGH rows only
- Dependency: after reviewer PASS.
- Files:
  - modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
  - modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
  - modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
  - create: `docs/reviews/REVIEW-B8.md`
- Checklist:
  - [ ] Update the B.8 section in `MASTER-ISSUE-REGISTRY.md` for every HIGH row addressed by this plan.
  - [ ] For rows proven already fixed in live code before B.8 edits (for example the existing `InvestmentTracker` latest-snapshot selection or `FinancialHealthCalculator` purchase-only filter), update the registry wording based on evidence instead of rewriting code just to match stale docs.
  - [ ] Mark exact resolved bullets with `[RESOLVED BY B.8]` only after code/tests and reviewer PASS exist.
  - [ ] Update the matching final-verification reports for Batches 03, 41, and 45 to mirror the resolved HIGH issues.
  - [ ] Create `docs/reviews/REVIEW-B8.md` during the review phase and include PASS/FAIL evidence.
- Validation:
  - Read-back audit of all documentation edits.
  - Ensure no MEDIUM/LOW B.8 rows were marked resolved by accident.
- Completion criteria:
  - [ ] Registry and final-verification docs match the shipped code/tests.
- Stop / rollback rule:
  - Do **not** update deep-analysis mirror docs from the planning/coding phases; follow playbook ordering after review PASS only.

### 4. Verification Plan
- **Per-batch static verification:**
  - Re-read every modified file.
  - Confirm imports/signatures remain valid.
  - Grep for stale anti-patterns after relevant batches:
    - `mutableMapOf<String, Double>()` monthly cap state in `AutomatedSavingsRuleEngine.kt`
    - `now - (7L * 24 * 60 * 60 * 1000)` in `AutomatedSavingsRuleEngine.kt`
    - `goal.createdAt` / `if (daysSinceLastContribution <= 1) 5 else 0` in `SavingsGamificationEngine.kt`
    - `30000.0` in `TaxEstimator.kt`
    - `budgetRepository.getBudgetStatuses().first()` in `FinancialHealthScoreV2.kt`
    - fixed `/30.0` or `/4.0` budget-target heuristics in `FinancialHealthCalculator.kt`
- **Serialized Gradle verification lane (orchestrator-owned):**
  1. `./gradlew.bat :app:compileDebugKotlin`
  2. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepositoryTest"`
  3. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.AutomatedSavingsRuleEngineTest"`
  4. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.AutomatedSavingsRuleEngineGoldenTest"`
  5. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.SmartSavingsEngineTest"`
  6. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.SavingsContributionHistoryRepositoryTest"`
  7. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.savings.SavingsGamificationEngineTest"`
  8. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.savings.SavingsGoalsViewModelTest"`
  9. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.investment.InvestmentTrackerTest"`
  10. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.tax.TaxEstimatorTest"`
  11. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.BudgetRepositoryHistoricalStatusTest"`
  12. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthScoreV2Test"`
  13. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorBudgetNormalizationTest"`
  14. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorTransactionTypeTest"`
  15. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.health.FinancialHealthCalculatorBoundaryTest"`
- **Reviewer focus points:**
  - No remaining in-memory-only monthly-cap or weekly-idempotency logic.
  - No per-goal duplication of portfolio-wide savings recommendations.
  - No fabricated streak math from `createdAt` or hardcoded placeholder values.
  - `InvestmentTracker` gain/loss includes fees and per-day history no longer double-counts same-day snapshots.
  - `TaxEstimator` no longer uses a flat bracket or hardcoded annual income.
  - `FinancialHealthScoreV2` uses requested-period budgets, not current-time budgets.
  - `FinancialHealthCalculator` normalized targets do not double-count overall + category budgets.

### 5. Documentation & Registry Updates
- **Registry update target:** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - In `### B.8: Savings/Investment Pipeline`, update the HIGH bullets for:
    - `AutomatedSavingsRuleEngine.PERCENTAGE_OF_INCOME` invalid percentage handling
    - `WEEKLY_NO_SPEND` repeat rewards + rolling-window week logic
    - monthly-cap persistence
    - `SmartSavingsEngine.calculateBudgetSurplus()` double-counting
    - `calculateSafeToSaveAmount()` per-goal full duplication
    - `SavingsGamificationEngine` fabricated streak logic
    - `InvestmentTracker` fee-aware gains and portfolio history day de-duplication
    - `TaxEstimator` progressive-bracket / period-alignment / year-summary / business-only VAT items
    - `FinancialHealthCalculator` spending-input / budget-normalization item(s) based on live audit outcome
    - `FinancialHealthScoreV2.calculateHealthScore(periodStart, periodEnd)` requested-period status item
  - If live audit proves a listed HIGH issue was already fixed before B.8 code changes, annotate that outcome accurately instead of forcing a no-op code edit.
- **Batch reports to update after reviewer PASS:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-03.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
- **Review artifact:**
  - Create `docs/reviews/REVIEW-B8.md` after implementation review.

### Implementation Steps
1. Audit stale B.8 HIGH rows already partially fixed in live code; prefer regression lock-in over unnecessary churn.
2. Land durable automated-rule state, then fix rule validation, stable-week idempotency, and persistent monthly caps.
3. Convert smart savings to a portfolio-scoped allocator and route the savings UI through that single path.
4. Add a durable savings contribution history, then switch gamification/streak logic to real recorded events.
5. Correct investment cost basis/history and tax estimator math without touching schemas.
6. Add explicit historical budget-status lookup, then align both health calculators to correct budget semantics.
7. Run serialized verification, obtain reviewer PASS, then update registry/final-verification docs and commit.

### Risks
- DataStore-backed rule/contribution ledgers can drift if period keys or prune rules are inconsistent; tests must recreate repositories and assert deterministic key behavior.
- Gamification cannot reconstruct truthful historical streaks from legacy balances alone; the safe behavior is to start recording forward, not to fabricate backfill.
- Tax period-alignment changes may alter sample UI values materially; the contract must be locked with tests before documentation closeout.
- Historical budget-status lookup can drift from the reactive path if logic is copied instead of shared.
- Some B.8 registry rows appear stale; documentation must be updated from live evidence, not from old batch wording.

### Acceptance Criteria
- [ ] Every B.8 CRITICAL/HIGH issue is either code-fixed or explicitly documented as already resolved after live audit; no CRITICAL/HIGH row is left ambiguous.
- [ ] Automated savings rejects invalid percentages, enforces one weekly no-spend reward per rule/week, and preserves monthly caps across process death.
- [ ] Smart savings recommendations are portfolio-scoped and cannot hand the full safe amount to every goal.
- [ ] Savings streaks/achievement progress come from recorded contribution history, not `createdAt` or hardcoded placeholders.
- [ ] Investment gains include fees and daily portfolio history no longer double-counts multiple same-day snapshots.
- [ ] Tax estimates use progressive brackets, period-aligned income, business-only VAT, and real annual income in year summaries.
- [ ] `FinancialHealthScoreV2` resolves requested-period budget statuses, and `FinancialHealthCalculator` uses normalized, non-duplicated budget targets.
- [ ] `:app:compileDebugKotlin` and all focused B.8 tests listed above pass in the serialized verification lane.
- [ ] Registry, final-verification docs, and `REVIEW-B8.md` are updated before commit.
