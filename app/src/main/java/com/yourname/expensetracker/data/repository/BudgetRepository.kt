package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.BudgetForecastDao
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
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.util.CurrencyFormatter
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

/**
 * Repository for budget data and status calculations.
 *
 * Coordinates between BudgetDao, CategoryDao, ExpenseDao, and multi-currency
 * services to provide budget snapshots, spending status, and health assessments.
 * Uses [MultiCurrencyRepository] for currency-safe aggregation.
 */
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
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val writeBarrier: DatabaseWriteBarrier,
    private val database: AppDatabase,
    private val budgetForecastDao: BudgetForecastDao
) {
    data class DebugBudgetSnapshot(
        val budgets: List<Budget>
    )

    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()

    suspend fun getActiveBudgets(): List<Budget> = budgetDao.getActiveBudgets()

    suspend fun getActiveBudgetSnapshots(): List<BudgetSnapshot> =
        getActiveBudgets().map { budget ->
            val converted = convertBudgetAmountToHomeCurrencyLatest(
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
        // Reactive trigger from ExpenseDao: re-emits whenever the expenses table
        // is invalidated by Room (any INSERT/UPDATE/DELETE on expenses). The value
        // (Int) is discarded — it is used only for invalidation so that budget
        // statuses recompute when expenses change, not just at midnight or on
        // budget/category table changes.
        //
        // Without this trigger, getBudgetStatuses() only recomputed on:
        //   1. Day boundary rollovers (timeBoundaryTicker.dayBoundaryTicks())
        //   2. Budget or category table changes (activeBudgetsFlow / getAllFlow())
        // Expense changes were invisible until the next day boundary.
        //
        // P2-18: Uses observeExpenseMutationClock() — a cheap SELECT COUNT(*)
        // invalidation-only query — instead of the deprecated getTotalSpentFlow()
        // which performed an unnecessary raw-money aggregate.
        val expenseInvalidationTrigger: Flow<*> = expenseDao.observeExpenseMutationClock().map { }
        return timeBoundaryTicker.dayBoundaryTicks().flatMapLatest { _ ->
            combine(
                budgetDao.getActiveBudgetsFlow(),
                categoryDao.getAllFlow(),
                expenseInvalidationTrigger
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

    /**
     * Computes the current [BudgetStatus] for a budget, including compounding rollover.
     *
     * Spent amounts used in both the current period and rollover calculations come
     * from aggregate SQL queries that filter by `transactionType = 'PURCHASE'` and
     * `isNotMine = 0` and use **effective amounts** (ownership-adjusted amounts that
     * correctly handle shared/not-mine splits). This ensures rollover surplus is
     * computed against the user's true spending, not raw transaction amounts.
     */
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
        //
        // TODO (P6-CURRENT-001): Budget limit and spend use different FX bases.
        // The limit is converted at period-end historical rate (below), but spend
        // aggregates use latest-rate via MultiCurrencyRepository. For consistency,
        // both should use the same rate basis (either both historical or both latest).
        //
        // P6-P1-06: Convert the budget limit at the period-end historical rate
        // so it matches the rate basis of expenses (converted at transaction-date
        // rates). Falls back to latest rate if no historical rate exists, with a
        // partial/warning marker.
        val initialLimitAggregate = convertBudgetAmountToHomeCurrencyAsOf(
            amount = budget.amount,
            sourceCurrency = budget.currency,
            asOfMillis = periodEnd
        )
        val spentAggregate = getAggregateSpent(budget.categoryId, window.start, window.end)
        val spent = spentAggregate.displayAmount
        val baseLimit = initialLimitAggregate.displayAmount
        var budgetWarningMessage = initialLimitAggregate.warningMessage
        var budgetIsPartial = initialLimitAggregate.isPartial || spentAggregate.isPartial

        // Compute effectiveLimit: base amount plus any rollover surplus from prior periods.
        // budget.amount stays as the original base limit (no mutation).
        var effectiveLimit = baseLimit

        // TODO (P2-2): Budget rollover currently queries each budget individually (N+1).
        // Batch-fetch all budgets with a single query and process in-memory.

        // LOG-002: Implement Compounding Rollover - BUG-2 FIX
        //
        // BUD-2 / BUD-11: This loop calls getAggregateSpent() once per completed period,
        // resulting in N+1 queries for N past periods. For budgets with many
        // rollover periods (e.g. a daily budget with years of history) this is
        // a performance gap.
        //
        // ## BUD-11: Materialized rollover ledger (planned)
        // The `BudgetPeriodLedger` approach eliminates the N+1 entirely:
        //
        // 1. Create a new table `budget_period_ledger` with columns:
        //    `(budgetId, periodStart, periodEnd, surplus, spent, limit, updatedAt)`.
        // 2. On every expense INSERT/UPDATE/DELETE that affects a budget's category,
        //    atomically recompute and upsert the ledger row for the affected period.
        //    This can be done via a Room `@Transaction` in `BudgetDao` or via a
        //    SQL trigger on the `expenses` table.
        // 3. During `createBudgetStatus`, replace the per-period loop with a single
        //    `SELECT surplus FROM budget_period_ledger WHERE budgetId = ? AND periodEnd <= ?`
        //    query — O(1) instead of O(N).
        // 4. The ledger is always consistent because updates happen synchronously
        //    with expense mutations. No background sync or staleness concerns.
        // 5. Migration: backfill the ledger for existing budgets by iterating past
        //    periods once during the schema migration or on first access.
        //
        // BUD-12-FIXED: rolloverDeficitTracking now implemented.
        // When `rolloverDeficitTracking` is true, deficits (negative surplus)
        // are carried forward to reduce the next period's effective limit.
        // When false (default), only surplus is carried forward (surplus-only mode).
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
                val periodAggregate = getAggregateSpent(budget.categoryId, period.start, period.end)
                val spentInPeriod = periodAggregate.displayAmount
                budgetIsPartial = budgetIsPartial || periodAggregate.isPartial
                if (periodAggregate.warningMessage != null) {
                    budgetWarningMessage = listOfNotNull(budgetWarningMessage, periodAggregate.warningMessage)
                        .distinct().joinToString(" ")
                }
                // BUD-12-FIXED: Use rolloverDeficitTracking flag to control whether deficits carry forward.
                val carryover = runningEffectiveLimit - spentInPeriod
                val effectiveCarryover = if (budget.rolloverDeficitTracking) carryover else carryover.coerceAtLeast(0.0)
                runningEffectiveLimit = baseLimit + effectiveCarryover
            }
            effectiveLimit = runningEffectiveLimit
        }

        // BUD-P5-P1-1: Guard against mixed-currency percent computation.
        // When the budget limit could not be converted to home currency,
        // initialLimitAggregate.isPartial is true and baseLimit/effectiveLimit
        // is in the budget's source currency, while spent is always in home
        // currency.  Computing spent / effectiveLimit would mix currencies.
        val budgetConversionFailed = initialLimitAggregate.isPartial
        val percent = if (effectiveLimit > 0 && !budgetConversionFailed) {
            (spent / effectiveLimit).toFloat()
        } else {
            0f
        }
        // Do not compute remaining with mixed currencies when limit conversion failed
        val remaining = if (!budgetConversionFailed) {
            (effectiveLimit - spent).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val health = when {
            // When conversion failed, status is genuinely unknown — do not mislead
            // the user with a green ON_TRACK indicator. The isPartial and
            // conversionWarning fields provide additional context for the UI.
            budgetConversionFailed -> BudgetHealthStatus.UNKNOWN
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
        val homeCurrency = resolveHomeCurrency()
        // TODO (P3-05): N+1 category budget query — getHomeCurrencyPurchaseCategoryTotals is called
        // once per budget period in the rollover loop. Batch all periods into a single DAO query
        // that returns per-category totals grouped by period to eliminate the N+1 pattern.
        return if (categoryId != null) {
            multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(start, end)[categoryId]
                ?: com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
                    CurrencyCode(homeCurrency)
                )
        } else {
            multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end)
        }
    }

    /**
     * Converts a budget amount to home currency using the **latest** available exchange rate.
     *
     * This method is intentionally named `Latest` to make the rate basis explicit.
     * Callers doing historical/period reporting must use [convertBudgetAmountToHomeCurrencyAsOf]
     * instead so budget limits are converted at period-appropriate historical rates consistent
     * with how expenses are converted.
     *
     * @see convertBudgetAmountToHomeCurrencyAsOf
     */
    private suspend fun convertBudgetAmountToHomeCurrencyLatest(
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

    /**
     * Converts a budget amount to home currency using the exchange rate valid
     * as of [asOfMillis] (epoch ms).
     *
     * This is the period-appropriate conversion for [createBudgetStatus] and
     * historical/period reports. It uses [CurrencyConverter.convertAsOf] so
     * budget limits are converted at the same historical rate basis as expenses
     * (i.e. the rate closest to, but not after, the period end).
     *
     * Falls back to the latest rate via [convertBudgetAmountToHomeCurrencyLatest]
     * if no historical rate is available, but marks the result as partial with a
     * warning so consumers know the rate basis is not period-accurate.
     *
     * @param amount       the budget amount in its source currency
     * @param sourceCurrency the budget's declared currency code
     * @param asOfMillis   epoch-ms timestamp for the rate lookup (typically periodEnd)
     * @return a [MoneyAggregate] in home currency, possibly partial if conversion failed
     */
    private suspend fun convertBudgetAmountToHomeCurrencyAsOf(
        amount: Double,
        sourceCurrency: String,
        asOfMillis: Long
    ): com.yourname.expensetracker.domain.core.money.MoneyAggregate {
        val homeCurrency = resolveHomeCurrency()
        if (sourceCurrency.equals(homeCurrency, ignoreCase = true)) {
            return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
                amount = amount,
                currency = CurrencyCode(homeCurrency)
            )
        }

        // Try historical rate first
        val historical = currencyConverter.convertAsOf(amount, sourceCurrency, homeCurrency, asOfMillis)
        if (historical != null) {
            return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
                amount = historical.convertedAmount,
                currency = CurrencyCode(homeCurrency)
            )
        }

        // Fall back to latest rate, but mark as partial with a warning
        val latest = currencyConverter.convert(amount, sourceCurrency, homeCurrency)
        if (latest != null) {
            Timber.d("Historical rate unavailable for %s → %s as of %d; falling back to latest rate",
                sourceCurrency, homeCurrency, asOfMillis)
            return com.yourname.expensetracker.domain.core.money.MoneyAggregate.singleCurrency(
                amount = latest.convertedAmount,
                currency = CurrencyCode(homeCurrency)
            ).copy(
                isPartial = true,
                warningMessage = "Budget limit converted at latest rate instead of period-end " +
                    "historical rate (as-of $asOfMillis)"
            )
        }

        // CURR-C62-14: Do not return raw source-currency amount — that would mix currencies
        Timber.w("Failed to convert budget amount $amount from $sourceCurrency to $homeCurrency (as of $asOfMillis)")
        return com.yourname.expensetracker.domain.core.money.MoneyAggregate.empty(
            CurrencyCode(homeCurrency)
        ).copy(
            isPartial = true,
            warningMessage = "Budget limit could not be converted from $sourceCurrency to $homeCurrency",
            conversionQuality = ConversionQuality.UNAVAILABLE
        )
    }

    private suspend fun resolveHomeCurrency(): String {
        return try {
            currencySettingsRepository.homeCurrency().first()
        } catch (e: Exception) {
            throw IllegalStateException("Home currency unavailable: ${e.message}", e)
        }
    }

    suspend fun addBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Long> {
        return try {
            writeBarrier.checkWritesAllowed("BudgetRepository.addBudget")
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            if (budget.startDate <= 0) throw IllegalArgumentException("Invalid budget start date")
            val budgetToInsert = if (budget.createdAt == 0L) {
                budget.copy(createdAt = timeProvider.now())
            } else budget
            val id = when {
                !budgetToInsert.isActive -> budgetDao.insert(budgetToInsert)
                budgetToInsert.categoryId == null -> budgetDao.insertAndActivateOverall(budgetToInsert)
                else -> budgetDao.insertAndActivateCategory(budgetToInsert)
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
            writeBarrier.checkWritesAllowed("BudgetRepository.updateBudget")
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

    /**
     * Throwing variant of [updateBudget] for use inside transactions that must
     * roll back on failure.
     *
     * P2-19: Unlike [updateBudget] which catches exceptions and returns
     * [Result.Error], this method propagates exceptions so they are visible
     * to Room's [androidx.room.withTransaction] and the outer transaction
     * rolls back correctly.
     *
     * @throws IllegalArgumentException if budget amount is invalid
     * @throws IllegalStateException if writes are blocked (restore mode)
     */
    suspend fun updateBudgetOrThrow(budget: Budget) {
        writeBarrier.checkWritesAllowed("BudgetRepository.updateBudgetOrThrow")
        if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
        val resetBudget = budget.copy(
            lastWarningNotifiedAt = null,
            lastCriticalNotifiedAt = null,
            lastExceededNotifiedAt = null
        )
        budgetDao.updateAndEnforceActiveScope(resetBudget)
    }

    suspend fun deleteBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            writeBarrier.checkWritesAllowed("BudgetRepository.deleteBudget")
            // Delete forecasts first to avoid FK constraint violation,
            // then delete the budget — all in a single DB transaction.
            database.withTransaction {
                budgetForecastDao.deleteForecastsForBudget(budget.id)
                budgetDao.delete(budget)
            }
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete budget ${budget.id}")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete budget (it may have forecast history — use archive policy or contact support)")
        }
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            writeBarrier.checkWritesAllowed("BudgetRepository.toggleBudget")
            budgetDao.setActiveAndEnforceScope(id, isActive)
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle budget $id")
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to toggle budget")
        }
    }

    suspend fun deleteAll(): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            writeBarrier.checkWritesAllowed("BudgetRepository.deleteAll")
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
        if (!com.yourname.expensetracker.BuildConfig.DEBUG) {
            return com.yourname.expensetracker.domain.model.Result.Error(
                UnsupportedOperationException("restoreDebugSnapshot is debug-only"),
                "Debug-only operation"
            )
        }
        writeBarrier.checkWritesAllowed("BudgetRepository.restoreDebugSnapshot")
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
        val homeCurrency = resolveHomeCurrency()

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
                    reason = "Based on your ${CurrencyFormatter.formatMoney(monthlyAvg, homeCurrency)} monthly average spend."
                )
            }
            .sortedByDescending { it.suggestedAmount }
            .take(3)
    }

    private fun List<CategorySpentTotal>.associateToCategoryTotalMap(): Map<Long, Double> {
        return associate { it.categoryId to it.total }
    }

    suspend fun updateExceededNotification(id: Long, timestamp: Long) {
        writeBarrier.checkWritesAllowed("BudgetRepository.updateExceededNotification")
        budgetDao.updateExceededNotification(id, timestamp)
    }

    suspend fun updateCriticalNotification(id: Long, timestamp: Long) {
        writeBarrier.checkWritesAllowed("BudgetRepository.updateCriticalNotification")
        budgetDao.updateCriticalNotification(id, timestamp)
    }

    suspend fun updateWarningNotification(id: Long, timestamp: Long) {
        writeBarrier.checkWritesAllowed("BudgetRepository.updateWarningNotification")
        budgetDao.updateWarningNotification(id, timestamp)
    }
}
