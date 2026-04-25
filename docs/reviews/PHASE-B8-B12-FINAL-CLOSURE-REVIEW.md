# Phase B.8/B.9/B.11/B.12 Final Closure Review

## Verdict
PHASE_B8_B12_CLOSED

## Evidence

### 1) `* 3.0` heuristic removed from `CalculateFinancialForecastUseCase` and projection logic is shared
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt:199-204` now computes `projectedTotal` through `SpendingPaceProjection.calculateProjectedTotal(...)`.
- Shared projection logic now lives in `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceProjection.kt:6-27`.
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt:57-62,101-111` uses the same helper, so the early-day projection path is shared across both surfaces.
- A focused search of `CalculateFinancialForecastUseCase.kt` found no remaining `3.0` literal match.
- Regression coverage was added at `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt:178-209`.

### 2) `CategoryRepository.learnMerchantCategory()` no longer bypasses centralized engine/cache invalidation
- `app/src/main/java/com/yourname/expensetracker/data/repository/CategoryRepository.kt:93-95` now delegates directly to `categorizationEngine.learnMerchantCategory(merchantName, categoryId)`.
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt:452-456` performs the centralized path: create mapping, insert it, then call `invalidateCache()`.
- Focused regression coverage exists at `app/src/test/java/com/yourname/expensetracker/data/repository/CategoryRepositoryTest.kt:32-40`, which verifies engine delegation and verifies the repository no longer calls the DAO insert path directly.

### 3) Registry markers for this closure scope are accurate
- The master registry contains resolved markers for the scoped leftovers covering the `* 3.0` projection heuristic, the `SavingsGoal.createdAt` default, the seeded/backfilled canonical-name fix, and the `CategoryRepository.learnMerchantCategory()` centralized-engine path.
- Focused registry searches for those scoped leftovers now return the resolved entries for those items, and the previously cited duplicate open-entry locations no longer represent this closure scope.
- In other words, the registry evidence for the reviewed B.8/B.11 closure items is present and no stale open duplicate for those specific leftovers remains.

## Conclusion
The previously remaining Phase B.8/B.11 closure items are fixed and the registry now reflects that state accurately. No additional coder pass is required for this requested closure scope.
