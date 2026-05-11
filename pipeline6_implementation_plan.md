# Pipeline 6 Implementation Plan

**Status:** 14/15 P1 ✅, 1/5 P2 ✅ — 1 P1 + 4 P2 remaining

---

## PR 1 — P6-P1-06: Budget limit conversion uses period-specific historical rates

**Priority:** P1 / High  
**Files:** `BudgetRepository.kt`, `BudgetForecastingEngine.kt`

### Problem
`convertBudgetAmountToHomeCurrencyLatest()` uses `currencyConverter.convert()` (latest spot rate). Historical/period budget reports compare expenses converted at transaction-date rates against budget limits converted at latest rates — causing inconsistent percent utilization.

### Fix

1. **BudgetRepository.kt** — Add `convertBudgetAmountToHomeCurrencyAsOf()`:
   ```kotlin
   private suspend fun convertBudgetAmountToHomeCurrencyAsOf(
       amount: Double, sourceCurrency: String, asOfMillis: Long
   ): MoneyAggregate {
       // Same structure as Latest variant but uses convertAsOf(amount, from, to, atMillis)
   }
   ```

2. **BudgetRepository.kt** — Update `createBudgetStatus()`:
   - Replace `convertBudgetAmountToHomeCurrencyLatest(amount, budget.currency)` with `convertBudgetAmountToHomeCurrencyAsOf(amount, budget.currency, periodEnd)`
   - The periodEnd is the canonical "as-of" timestamp for this budget period

3. **BudgetForecastingEngine.kt** — Update `generateForecast()`:
   - Replace `currencyConverter.convert(budget.amount, budget.currency, homeCurrency)` with `currencyConverter.convertAsOf(budget.amount, budget.currency, homeCurrency, periodEnd)`
   - Fall back to `convert()` if `convertAsOf()` returns null

4. **Keep** `convertBudgetAmountToHomeCurrencyLatest()` for callers that explicitly need latest rate (active budget snapshots in `getActiveBudgetSnapshots()`)

### Tests
- `historical_budget_status_uses_period_end_rate`
- `forecast_budget_limit_uses_period_end_rate`
- `budget_limit_falls_back_to_latest_when_historical_unavailable`

---

## PR 2 — P2 fixes bundle

**Priority:** P2 / Medium  
**Files:** `PlannedExpenseRepository.kt`, `ExpenseDao.kt`, `BudgetRepository.kt`, `BudgetViewModel.kt`, `BudgetMonitor.kt`

### P2-16: PlannedExpenseRepository invariants

**File:** `PlannedExpenseRepository.kt`

1. Set `createdAt` and `updatedAt` at insert time:
   ```kotlin
   val withTimestamps = expense.copy(
       createdAt = if (expense.createdAt == 0L) timeProvider.now() else expense.createdAt,
       updatedAt = timeProvider.now()
   )
   ```

2. Check insert result for conflict (`-1` = IGNORE skip):
   ```kotlin
   val id = plannedExpenseDao.insertPlannedExpense(withTimestamps)
   if (id == -1L) throw IllegalStateException("Duplicate planned expense")
   return id
   ```

3. Set `openSourceOccurrenceKey` from `sourceOccurrenceKey` if not already populated:
   ```kotlin
   val withOpenKey = withTimestamps.copy(
       openSourceOccurrenceKey = withTimestamps.openSourceOccurrenceKey
           ?: withTimestamps.sourceOccurrenceKey
   )
   ```
   (Preserves existing `openSourceOccurrenceKey` if already set, otherwise populates from `sourceOccurrenceKey`.)

4. Inject `TimeProvider` into the repository.

5. Update class-level KDoc: remove the TODO block, document the new invariants.

### P2-18: Budget invalidation trigger replacement

**File:** `ExpenseDao.kt`, `BudgetRepository.kt`

1. Add cheap invalidation query to `ExpenseDao`:
   ```kotlin
   @Query("SELECT MAX(updatedAt) FROM expenses")
   fun observeExpenseMutationClock(): Flow<Long?>
   ```

2. Replace the invalidation trigger in `BudgetRepository.getBudgetStatuses()`:
   ```kotlin
   // Before:
   val expenseInvalidationTrigger: Flow<*> = expenseDao.getTotalSpentFlow().map { }
   // After:
   val expenseInvalidationTrigger: Flow<*> = expenseDao.observeExpenseMutationClock().map { }
   ```

3. Remove the TODO (P2-18) comment block.

### P2-19: Autopilot apply-all true rollback

**Files:** `BudgetRepository.kt`, `BudgetViewModel.kt`

1. Add `updateBudgetOrThrow()` to `BudgetRepository`:
   ```kotlin
   suspend fun updateBudgetOrThrow(budget: Budget) {
       writeBarrier.checkWritesAllowed("BudgetRepository.updateBudgetOrThrow")
       if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
       val resetBudget = budget.copy(
           lastWarningNotifiedAt = null, lastCriticalNotifiedAt = null, lastExceededNotifiedAt = null
       )
       budgetDao.updateAndEnforceActiveScope(resetBudget)
   }
   ```

2. Update `applyAllAutopilotRecommendations()` in BudgetViewModel:
   - Replace `budgetRepository.updateBudget(updatedBudget)` with `budgetRepository.updateBudgetOrThrow(updatedBudget)`
   - Only clear recommendations after successful transaction (already done)
   - Remove the TODO (P2-19) comment block

### P2-20: Budget monitor diagnostic ledger

**Files:** `BudgetMonitor.kt`, existing `PipelineDiagnosticEvent` infrastructure

1. Inject `PipelineDiagnosticEventDao` into `BudgetMonitor`.

2. Write diagnostic events at key decision points:
   - `CHECK_STARTED` — when `processBudgetStatus()` begins
   - `STATUS_COMPUTED` — after budget statuses derived with percent/health
   - `ALERT_SENT` — after notification dispatched successfully
   - `ALERT_FAILED` — if notification delivery fails
   - `ALERT_BLOCKED` — if throttled (already sent recently)
   - `CHECK_FAILED` — on exception during processing

3. Use existing `PipelineDiagnosticEvent` entity with stage `"budget_monitor"`:
   ```kotlin
   PipelineDiagnosticEvent(
       pipeline = "budget_monitor",
       stage = "ALERT_SENT",
       outcome = "OK",
       budgetId = status.budget.id,
       percentUsed = adjustedPercent.toDouble(),
       ...
   )
   ```

4. Write on `Dispatchers.IO` to avoid blocking the monitor coroutine.
