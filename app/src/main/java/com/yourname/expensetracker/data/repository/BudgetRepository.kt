package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.CategorySpentTotal
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.groups.SharedExpenseBudgetOffsetEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetCalculator: BudgetCalculator,
    private val timeProvider: com.yourname.expensetracker.domain.util.TimeProvider,
    private val offsetEngine: SharedExpenseBudgetOffsetEngine,
    private val timeBoundaryTicker: TimeBoundaryTicker,
    private val currencyConverter: CurrencyConverter,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository
) {
    data class DebugBudgetSnapshot(
        val budgets: List<Budget>
    )

    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()

    suspend fun getActiveBudgets(): List<Budget> = budgetDao.getActiveBudgets()

    suspend fun getActiveBudgetSnapshots(): List<BudgetSnapshot> =
        getActiveBudgets().map { budget ->
            val converted = convertBudgetAmountToHomeCurrency(
                amount = budget.amount,
                sourceCurrency = budget.currency
            )
            BudgetSnapshot(
                categoryId = budget.categoryId,
                amount = converted.displayAmount,
                currency = converted.displayCurrency.code
            )
        }
    
    suspend fun getById(id: Long): Budget? = budgetDao.getById(id)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        // Recalculate the expense query window on every day boundary so the flow
        // stays current after midnight rollovers without requiring re-subscription.
        return timeBoundaryTicker.dayBoundaryTicks().flatMapLatest { _ ->
            // Use a lightweight invalidation trigger instead of fetching all expense rows.
            // getTotalSpentFlow() emits whenever the expenses table changes (Room invalidation),
            // so the combine block re-runs and executes fresh aggregate queries.
            combine(
                budgetDao.getActiveBudgetsFlow(),
                categoryDao.getAllFlow(),
                expenseDao.getTotalSpentFlow().map { it ?: 0.0 }
            ) { budgets, categories, _ ->
                deriveBudgetStatuses(
                    budgets = budgets,
                    categories = categories,
                    evaluationTime = timeProvider.now()
                )
            }
        }
    }

    suspend fun getBudgetStatusesAt(evaluationTime: Long): List<BudgetStatus> {
        return deriveBudgetStatuses(
            budgets = budgetDao.getActiveBudgets(),
            categories = categoryDao.getAll(),
            evaluationTime = evaluationTime
        )
    }

    private suspend fun deriveBudgetStatuses(
        budgets: List<Budget>,
        categories: List<Category>,
        evaluationTime: Long
    ): List<BudgetStatus> {
        val categoryMap = categories.associateBy { it.id }
        return budgets.map { budget ->
            createBudgetStatus(
                budget = budget,
                categoryMap = categoryMap,
                evaluationTime = evaluationTime
            )
        }
    }

    private suspend fun createBudgetStatus(
        budget: Budget,
        categoryMap: Map<Long, Category>,
        evaluationTime: Long
    ): BudgetStatus {
        val (periodStart, periodEnd) = budgetCalculator.calculatePeriodRange(budget, evaluationTime)
        val window = PeriodRange(periodStart, periodEnd)

        // Use aggregate SQL queries instead of fetching raw rows.
        // getCategorySpentInPeriod / getTotalForPeriod already filter by
        // transactionType = 'PURCHASE' AND isNotMine = 0 and use effectiveAmount.
        val initialLimitAggregate = convertBudgetAmountToHomeCurrency(
            amount = budget.amount,
            sourceCurrency = budget.currency
        )
        val spentAggregate = getAggregateSpent(budget.categoryId, window.start, window.end)
        val spent = spentAggregate.displayAmount
        val baseLimit = initialLimitAggregate.displayAmount
        var budgetWarningMessage = initialLimitAggregate.warningMessage
        var budgetIsPartial = initialLimitAggregate.isPartial || spentAggregate.isPartial

        // Compute effectiveLimit: base amount plus any rollover surplus from prior periods.
        // budget.amount stays as the original base limit (no mutation).
        var effectiveLimit = baseLimit

        // LOG-002: Implement Compounding Rollover - BUG-2 FIX
        //
        // BUD-2: This loop calls getAggregateSpent() once per completed period,
        // resulting in N+1 queries for N past periods. For budgets with many
        // rollover periods (e.g. a daily budget with years of history) this is
        // a performance gap. A future optimization should batch-fetch all period
        // spendings with a single multi-period range query, or compute rollover
        // incrementally and persist the accumulated surplus.
        if (budget.rollover) {
            val budgetFirstStart = budget.startDate
            val periods = mutableListOf<PeriodRange>()
            // Use explicit evaluation times so every completed anchored cycle is
            // visited in order, regardless of where the evaluation time falls.
            var currentWindow = budgetCalculator.calculatePeriodWindowForTime(
                budget.period, budgetFirstStart, budgetFirstStart
            )
            while (currentWindow.end <= window.start) {
                periods.add(currentWindow)
                currentWindow = budgetCalculator.calculatePeriodWindowForTime(
                    budget.period, budgetFirstStart, currentWindow.end
                )
            }
            var runningEffectiveLimit = baseLimit
            for (period in periods) {
                val spentInPeriod = getAggregateSpent(budget.categoryId, period.start, period.end).displayAmount
                val surplus = (runningEffectiveLimit - spentInPeriod).coerceAtLeast(0.0)
                runningEffectiveLimit = baseLimit + surplus
            }
            effectiveLimit = runningEffectiveLimit
        }

        val percent = if (effectiveLimit > 0) (spent / effectiveLimit).toFloat() else 0f
        val remaining = (effectiveLimit - spent).coerceAtLeast(0.0)

        val health = when {
            percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
            percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
            percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
            else -> BudgetHealthStatus.ON_TRACK
        }

        return BudgetStatus(
            budget = budget.copy(
                amount = baseLimit,                   // Keep original base limit, NOT the rollover-inflated amount
                currency = initialLimitAggregate.displayCurrency.code
            ),
            category = categoryMap[budget.categoryId],
            spentAmount = spent,
            remainingAmount = remaining,
            percentUsed = percent,
            healthStatus = health,
            periodStart = periodStart,
            periodEnd = periodEnd,
            effectiveLimit = effectiveLimit,            // Store the rolled-over limit separately
            currency = initialLimitAggregate.displayCurrency.code,
            isPartial = budgetIsPartial,
            conversionWarning = listOfNotNull(budgetWarningMessage, spentAggregate.warningMessage)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
        )
    }

    /**
     * Returns the aggregate spend for a half-open date window using SQL-level
     * aggregation.  Delegates to [ExpenseDao.getCategorySpentInPeriod] for
     * category-scoped budgets and [ExpenseDao.getTotalForPeriod] for
     * whole-wallet budgets.  Both DAO methods already filter by
     * `transactionType = 'PURCHASE' AND isNotMine = 0` and use the
     * effective-amount SQL helper, matching the previous in-memory logic.
     */
    private suspend fun getAggregateSpent(
        categoryId: Long?,
        start: Long,
        end: Long
    ): com.yourname.expensetracker.domain.core.money.MoneyAggregate {
        return if (categoryId != null) {
            multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(start, end)[categoryId]
                ?: com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
                    CurrencyCode(MultiCurrencyRepository.DEFAULT_HOME_CURRENCY)
                )
        } else {
            multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end)
        }
    }

    private suspend fun convertBudgetAmountToHomeCurrency(
        amount: Double,
        sourceCurrency: String
    ): com.yourname.expensetracker.domain.core.money.MoneyAggregate {
        val homeCurrency = resolveHomeCurrency()
        if (sourceCurrency.equals(homeCurrency, ignoreCase = true)) {
            return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
                amount = amount,
                currency = CurrencyCode(homeCurrency)
            )
        }

        val conversion = currencyConverter.convert(amount, sourceCurrency, homeCurrency)
        if (conversion != null) {
            return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
                amount = conversion.convertedAmount,
                currency = CurrencyCode(homeCurrency)
            )
        }

        Timber.w("Failed to convert budget amount $amount from $sourceCurrency to $homeCurrency, using original amount")
        return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
            amount = amount,
            currency = CurrencyCode(sourceCurrency)
        ).copy(
            isPartial = true,
            warningMessage = "Budget limit could not be converted from $sourceCurrency to $homeCurrency"
        )
    }

    private suspend fun resolveHomeCurrency(): String {
        return try {
            currencySettingsRepository.homeCurrency().first()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read home currency, defaulting to EUR")
            "EUR"
        }
    }

    suspend fun addBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Long> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            if (budget.startDate <= 0) throw IllegalArgumentException("Invalid budget start date")
            val id = when {
                // Inactive budgets can never violate the partial unique indexes,
                // so a plain insert is safe and avoids needless deactivation work.
                !budget.isActive -> budgetDao.insert(budget)
                // Active overall budget → atomically demote the previous active overall row.
                budget.categoryId == null -> budgetDao.insertAndActivateOverall(budget)
                // Active category budget → atomically demote the previous active row for this category.
                else -> budgetDao.insertAndActivateCategory(budget)
            }
            // budgetMonitor.checkBudgets() // Removed to avoid circular dependency. Monitor should observe flow.
            com.yourname.expensetracker.domain.model.Result.Success(id)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add budget")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to add budget")
        }
    }

    suspend fun updateBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            // Reset notifications when budget is edited so user gets fresh alerts (BUG-7 Fix)
            val resetBudget = budget.copy(
                lastWarningNotifiedAt = null,
                lastCriticalNotifiedAt = null,
                lastExceededNotifiedAt = null
            )
            budgetDao.updateAndEnforceActiveScope(resetBudget)
            // budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update budget ${budget.id}")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to update budget")
        }
    }

    suspend fun deleteBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.delete(budget)
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete budget ${budget.id}")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete budget")
        }
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.setActiveAndEnforceScope(id, isActive)
            // budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle budget $id")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to toggle budget")
        }
    }

    suspend fun deleteAll(): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.deleteAll()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all budgets")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete all budgets")
        }
    }

    suspend fun createDebugSnapshot(): DebugBudgetSnapshot {
        return DebugBudgetSnapshot(budgets = budgetDao.getAll())
    }

    suspend fun restoreDebugSnapshot(snapshot: DebugBudgetSnapshot): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            if (snapshot.budgets.isNotEmpty()) {
                budgetDao.replaceAllAndEnforceActiveScopes(snapshot.budgets)
            } else {
                budgetDao.deleteAll()
            }
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore debug budget snapshot")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to restore budgets")
        }
    }

    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()
        val categoriesWithoutBudget = categories.filter { !categoriesWithBudget.contains(it.id) }

        if (categoriesWithoutBudget.isEmpty()) return emptyList()

        val now = timeProvider.now()
        val endExclusive = TimePeriodUtils.getEndOfDay(now)
        val oldestDate = expenseDao.getOldestExpenseDate() ?: now
        
        // Use up to 3 months of history, but at least 1 month if available
        // If data is less than 15 days, results might be unreliable, but we'll try to extrapolate conservatively
        val (threeMonthsAgo, _) = TimePeriodUtils.getLastNCalendarDaysRange(now, 90)
        val effectiveStart = maxOf(oldestDate, threeMonthsAgo)

        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(effectiveStart).atZone(zone).toLocalDate()
        val endDateExclusive = Instant.ofEpochMilli(endExclusive).atZone(zone).toLocalDate()
        val daysDiff = ChronoUnit.DAYS.between(startDate, endDateExclusive).toInt().coerceAtLeast(1)
        
        // If we have very little data (e.g. < 7 days), skip suggestions to avoid noise (LOG-010)
        if (daysDiff < 7) return emptyList()

        val daysInCurrentMonth = TimePeriodUtils.getDaysInMonth(now).coerceAtLeast(1)
        val monthsDivisor = daysDiff.toDouble() / daysInCurrentMonth.toDouble()

        // BUD-4: Use MultiCurrencyRepository instead of deprecated raw-sum DAO call.
        // getHomeCurrencyPurchaseCategoryTotals returns Map<Long?, MoneyAggregate>;
        // we filter to only the categories without budgets and extract .displayAmount.
        val categorySpentById: Map<Long, Double> = multiCurrencyRepository
            .getHomeCurrencyPurchaseCategoryTotals(effectiveStart, endExclusive)
            .entries
            .filter { it.key != null }
            .associate { it.key!! to it.value.displayAmount }
            .filterKeys { it in categoriesWithoutBudget.map { cat -> cat.id } }

        val basedOnMonths = Math.round(monthsDivisor).toInt().coerceAtLeast(1)

        return categoriesWithoutBudget
            .mapNotNull { category ->
                val spent = categorySpentById[category.id] ?: 0.0
                val monthlyAvg = if (monthsDivisor > 0) spent / monthsDivisor else 0.0
                if (monthlyAvg <= 20.0) return@mapNotNull null

                BudgetSuggestion(
                    categoryId = category.id,
                    categoryName = category.name,
                    categoryIcon = category.icon,
                    // increase buffer to 20% (LOG-016)
                    suggestedAmount = (monthlyAvg * 1.2).coerceAtLeast(20.0),
                    basedOnMonths = basedOnMonths,
                    reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                )
            }
            .sortedByDescending { it.suggestedAmount }
            .take(3)
    }

    private fun List<CategorySpentTotal>.associateToCategoryTotalMap(): Map<Long, Double> {
        return associate { it.categoryId to it.total }
    }

    suspend fun updateExceededNotification(id: Long, timestamp: Long) {
        budgetDao.updateExceededNotification(id, timestamp)
    }

    suspend fun updateCriticalNotification(id: Long, timestamp: Long) {
        budgetDao.updateCriticalNotification(id, timestamp)
    }

    suspend fun updateWarningNotification(id: Long, timestamp: Long) {
        budgetDao.updateWarningNotification(id, timestamp)
    }
}
