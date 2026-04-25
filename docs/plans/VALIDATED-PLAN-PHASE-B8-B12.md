# Validated Remedy Plan: B.8, B.9, B.11, B.12

> Audit sources reviewed: `docs/reviews/AUDIT-PHASE-B8-B9-B11-B12.md` and `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`.
> 14 candidate issues were supplied. 13 are verified still open in the current codebase; 1 (`TransactionsViewModel` external `dateRange` clipping) could not be reproduced and should be removed or re-scoped.

## B.8 Top Issues

### Issue: `MonthlySavingsSweepUseCase` null Monte Carlo fallback hardcodes `100.0`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt:229-233`
**Fix:** Refactor `calculateRiskBuffer(...)` so the null-Monte-Carlo path derives a bounded fallback from current sweep inputs (`spentToDate`, `knownUpcoming`, remaining month context, and/or budget size) instead of returning a constant. Keep the fallback deterministic and test the null-simulator path explicitly.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`, `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`

### Issue: `SpendingPaceCalculator` uses arbitrary `* 3.0` projection heuristic
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt:97-103`
**Fix:** Remove the literal `monthSpent * 3.0` fallback. Rework projected-total calculation to use a calendar-aware, explainable baseline (for example previous-month daily rate or a bounded linear projection after a minimum elapsed-days threshold). Reorder the calculation if needed so the baseline is available before projection is computed.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`, `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculatorValidationTest.kt`, `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculatorDeepTest.kt`

### Issue: domain `SavingsGoal.createdAt` defaults to `0L`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt:10`
**Fix:** Remove the `0L` default from the domain model and require all constructors/boundary mappers to pass a real timestamp. Keep clock ownership at the boundary (`TimeProvider`/database entity), then update any production call sites and test factories that relied on the default.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`, `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`

## B.9 Top Issues

### Issue: `HomeViewModel.reloadDashboard()` does not recreate the failed dashboard pipeline
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt:172-195` creates `processedDataFlow` once; `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt:332-344` retry only refreshes side data and never re-subscribes that pipeline.
**Fix:** Add a dedicated dashboard reload trigger (or equivalent retry state) and build `processedDataFlow` from it with `flatMapLatest`, so retry reconstructs the upstream flow after a terminal `Error`. Keep the existing trend/recommendation reloads, but make the dashboard pipeline itself restartable.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`, `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeViewModelStressTest.kt` or a new focused retry test

### Issue: `BudgetForecastingViewModel` loses `budget` on first-load forecast failure
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt:46-47` starts loading without storing `budget`; `.../BudgetForecastingViewModel.kt:73-85` only writes `budget` on success; `.../BudgetForecastingViewModel.kt:93-97` retries only when `_uiState.value.budget` is non-null.
**Fix:** Persist the requested budget before running the forecast and preserve it on failure, so `refreshForecast()` can retry after the first failed load. Add a regression test for first-call failure followed by successful retry.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt`, `app/src/test/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModelTest.kt`

### Issue: `Expense.isNotMine` and `isSharedExpense` can both be true
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt:78-80,125-130` allows both flags and resolves the conflict by zeroing via `isNotMine`; current create/edit write paths can still persist conflicting ownership state (`app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt:186-196,330-337`).
**Fix:** Enforce ownership-mode mutual exclusivity at write boundaries and repository update paths, and normalize legacy rows that already contain both flags. Do **not** add a Room-entity `init` throw that could break reads of existing bad data; use write-path validation plus a one-time cleanup/backfill.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`

### Issue: external `dateRange` filters are clipped by tab windows
**Verified:** NO
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt:117-120` applies the external filter on entry; `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt:140-147` uses `params.filter.dateRange ?: getTimeRangeForTab(params.tab)`; `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt:645-658` forwards `startDate`/`endDate` directly for paged queries. No active tab-range intersection remains in the current code.
**Fix:** No code remediation for the original clipping defect. Remove or re-scope the stale registry/audit entry; if there is still a UX concern, reopen it as a separate “custom-period tab semantics/banner state” issue rather than a clipped-range bug.
**Files to modify:** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, `docs/reviews/AUDIT-PHASE-B8-B9-B11-B12.md`

### Issue: `BudgetViewModel` recomputes adjusted spend on every combined emission
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt:53-64` recomputes `calculateAdjustedSpend(status)` inside the `combine` block for statuses, suggestions, manual state, and autopilot state; `.../BudgetViewModel.kt:89-108` makes each recomputation a suspend offset-engine call.
**Fix:** Move adjusted-spend enrichment into a statuses-only flow (or cache keyed by budget/period) so unrelated UI emissions do not trigger fresh shared-expense calculations. Keep suggestion/manual/autopilot state combination separate from the expensive offset pass.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt`, `app/src/test/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModelStressTest.kt`

## B.11 Top Issues

### Issue: seeded merchant mappings omit `normalizedCanonicalName`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt:49-53` seeds `MerchantCategory(merchantPattern = merchant, categoryId = catId)` only; the entity still has `normalizedCanonicalName` available at `app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantCategory.kt:27`.
**Fix:** Seed merchant mappings through the same canonical-normalization path used by learned mappings, and backfill existing `merchant_categories` rows where `normalizedCanonicalName` is null. Avoid leaving seeded rows outside the fuzzy canonical lookup contract.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantCategoryDao.kt`, `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt`

### Issue: `RecurringExpenseEngine` groups merchants by `lowercase().trim()` instead of canonical key
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt:41-50`
**Fix:** Group manual rules and detected expenses by canonical merchant key (`merchantKey` when present, otherwise a canonical fallback such as `MerchantKeyGenerator.generate(...)`) so aliases/casing/spacing variants collapse consistently across recurring detection and manual overrides.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`, `app/src/test/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngineStressTest.kt`, `app/src/test/java/com/yourname/expensetracker/domain/logic/RecurringExpenseEngineEmptyListTest.kt`

### Issue: `SynthesisEngine` uses `pastSumDaily.lastOrNull()` without `isFinite()` guard
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:172,215`
**Fix:** Sanitize `pastSumDaily` before using it for `lastKnownTotal` and projected-point math. Reject or coerce non-finite tails so a single `NaN`/`Infinity` does not poison every projected value downstream.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`, `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`, `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineStressTest.kt`

## B.12 Top Issues

### Issue: `SharedExpenseManager.addExpense()` does not validate same-group `paidById` membership
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt:148-176` fetches `groupMemberIds` only for custom-split validation and never checks `paidById`; the DB schema only enforces that `paidById` references *a* member row, not a member from the same group (`app/src/main/java/com/yourname/expensetracker/data/database/entity/GroupExpense.kt:35-40,53-55`).
**Fix:** Validate `paidById in groupMemberIds` for every split type before persistence, and preferably route expense creation through the coordinator-backed path so same-group validation is enforced transactionally at the data boundary too. Fix this together with the adapter/coordinator bypass below.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`, `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt`, `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt`, `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerTest.kt`

### Issue: `SharedExpenseBudgetOffsetEngine` sums raw `amount` instead of `effectiveAmount`
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt:68-76`
**Fix:** Replace `sumOf { it.amount }` with the canonical owned-spend field/helper. The current filter reduces blast radius, but the engine should still follow the project-wide `effectiveAmount` contract and have regression coverage for ownership edge cases.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`, `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt`

### Issue: `SharedExpenseDataPortAdapter.addMember()` bypasses coordinator validation
**Verified:** YES
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt:42-43` inserts directly with `memberDao.insert(...)`; the coordinator path that enforces active-group/current-user validation already exists at `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt:90-95` and `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt:105-144`.
**Fix:** Route `addMember()` through `GroupTransactionCoordinator.addMemberToGroup(...)` instead of the raw DAO insert. Because the current port contract returns `Long`, either (a) change the port/manager contract to a typed validation result, or (b) perform a validated insert through the coordinator and then resolve the created member ID in a follow-up read. Do not keep the direct DAO bypass.
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt`, `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt`, `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`, `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`

## Priority Order (combined)
1. Ownership-flag conflict (`Expense.isNotMine` + `isSharedExpense`) - B.9
2. Dashboard retry does not recreate failed pipeline - B.9
3. Budget forecast first-load failure leaves retry dead - B.9
4. `SharedExpenseManager.addExpense()` missing same-group `paidById` validation - B.12
5. `SharedExpenseDataPortAdapter.addMember()` bypasses coordinator validation - B.12
6. Seeded merchant mappings missing `normalizedCanonicalName` - B.11
7. `SynthesisEngine` non-finite `pastSumDaily` poisoning - B.11
8. `RecurringExpenseEngine` grouping by non-canonical merchant text - B.11
9. `BudgetViewModel` adjusted-spend recomputation on every emission - B.9
10. `MonthlySavingsSweepUseCase` hardcoded null-Monte-Carlo fallback - B.8
11. `SpendingPaceCalculator` arbitrary `* 3.0` projection - B.8
12. `SharedExpenseBudgetOffsetEngine` raw `amount` instead of `effectiveAmount` - B.12
13. Domain `SavingsGoal.createdAt` default `0L` - B.8
14. Remove stale external `dateRange` clipping entry from registry/audit - B.9

## Estimated Effort
~40 hours
