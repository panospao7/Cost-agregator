package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainExpenseSummary
import com.yourname.expensetracker.domain.model.dashboard.toTransactionSummary
import com.yourname.expensetracker.domain.model.TransactionSummary
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─── Domain models exposed to the UI layer ───────────────────────────────────

sealed class DashboardWidget {
    /**
     * DSH-6: Safe-to-spend widget showing how much the user can safely spend
     * for the remainder of the budget period.
     *
     * ## Fallback behavior
     * When no budget is configured (`totalBudget == null`), the widget falls back
     * to displaying the **month-to-date total spend** (not a remaining budget).
     * This can be confusing because the amount shown is money *already spent*
     * rather than money *available to spend*.
     *
     * ## Recommended CTA
     * UI should display a call-to-action encouraging users to set a budget when
     * `totalBudget == null`:
     * > "Set a monthly budget to see how much you can safely spend."
     *
     * @param amount       Safe-to-spend amount when a budget exists, or
     *                     month-to-date total spent as a fallback.
     * @param totalBudget  The overall budget amount, or `null` if no budget is set.
     * @param daysRemaining Days left in the current budget period.
     */
    data class SafeToSpend(
        val amount: Double,
        val totalBudget: Double?,
        val daysRemaining: Int
    ) : DashboardWidget()

    data class BudgetBlockParty(
        val days: List<DomainDayBudgetStatus>
    ) : DashboardWidget()

    data class SpendingPaceWidget(
        val pace: SpendingPace
    ) : DashboardWidget()

    data class PendingReviewAlert(
        val count: Int
    ) : DashboardWidget()

    data class PeriodSummary(
        val todaySpent: Double,
        val weekSpent: Double,
        val monthSpent: Double
    ) : DashboardWidget()

    data class TopCategories(
        val categories: List<CategorySpending>
    ) : DashboardWidget()

    data class BudgetHealthWidget(
        val statuses: List<BudgetStatusSnapshot>,
        val summary: UiText?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<DashboardExpense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: UiText,
        val icon: String
    ) : DashboardWidget()

    data class SpendingTrend(
        val series: List<SpendingTrendSeries>
    ) : DashboardWidget()

    data class FinancialWeatherWidget(
        val weather: FinancialWeather
    ) : DashboardWidget()

    data class FinancialRunway(
        val daysRemaining: Int,
        val totalBudget: Double,
        val discretionaryRemaining: Double,
        val averageDailyDiscretionarySpend: Double,
        val monthlyIncome: Double,
        val committedExpenses: Double,
        val likelyExpenses: Double,
        val status: RunwayStatus
    ) : DashboardWidget()

    enum class RunwayStatus {
        HEALTHY,   // 14+ days
        CAUTION,   // 7–13 days
        CRITICAL,  // < 7 days
        NO_INCOME  // No deposits detected
    }

    data class MonteCarloForecast(
        val result: MonteCarloResult
    ) : DashboardWidget()

    data object TotalsDashboard : DashboardWidget()

    data class NoSpendStreak(
        val currentStreak: Int,
        val personalBest: Int,
        val daysWithoutSpendingThisMonth: Int
    ) : DashboardWidget()

    data class FinancialHealthScoreWidget(
        val healthScore: com.yourname.expensetracker.domain.health.HealthScoreResult
    ) : DashboardWidget()

    /**
     * Financial Health Score V2 widget (F5) - Enhanced health score calculation.
     */
    data class FinancialHealthScoreV2Widget(
        val healthScore: com.yourname.expensetracker.domain.health.FinancialHealthResult
    ) : DashboardWidget()
    
    data class LifestyleSavingsPrompt(
        val inflationRate: Double,
        val suggestedUplift: Double,
        val reason: String,
        val hasExistingGoals: Boolean
    ) : DashboardWidget()
    
    /**
     * Monthly Savings Sweep widget - shown at month-end when there is
     * underspend that can be safely swept to savings goals.
     */
    data class SavingsSweepPrompt(
        val sweepAmount: Double,
        val underspend: Double,
        val riskBuffer: Double,
        val goalAllocations: List<SweepGoalAllocation>,
        val confidence: Double,
        val daysUntilMonthEnd: Int
    ) : DashboardWidget()
    
    data class SweepGoalAllocation(
        val goalId: Long,
        val goalName: String,
        val suggestedAmount: Double,
        val currentProgress: Double,
        val targetAmount: Double
    )
    
    data class MoneyRadar(
        val data: MoneyRadarData
    ) : DashboardWidget()

    /**
     * Financial Stress Forecast widget (F8) - Shows 30/60/90 day cash crunch prediction.
     */
    data class FinancialStressForecast(
        val result: com.yourname.expensetracker.domain.forecasting.StressForecastResult
    ) : DashboardWidget()
}

data class CategorySpending(
    val category: CategoryInfo,
    val total: Double,
    val percentage: Float,
    val currency: String = "EUR"
) {
    val moneyTotal: MoneyAmount get() = MoneyAmount(total, CurrencyCode(currency))
}

data class SpendingTrendSeries(
    val label: String,
    val data: List<Float>,
    val isCurrentMonth: Boolean
)

data class CompiledDashboardData(
    val allWidgets: List<DashboardWidget>,
    val totalSpent: Double,
    val txCount: Int
)

// ─── Use Case ─────────────────────────────────────────────────────────────────

@Singleton
class ComputeDashboardWidgetsUseCase @Inject constructor(
    private val insightsEngine: InsightsEngine,
    private val synthesisEngine: SynthesisEngine,
    private val monteCarloSimulator: MonteCarloSpendingSimulator,
    private val timeProvider: TimeProvider,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val healthCalculator: com.yourname.expensetracker.domain.health.FinancialHealthCalculator,
    private val healthScoreV2: com.yourname.expensetracker.domain.health.FinancialHealthScoreV2,
    private val lifestyleSavingsPromptUseCase: com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase,
    private val monthlySavingsSweepUseCase: com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase,
    private val computeMoneyRadarUseCase: ComputeMoneyRadarUseCase,
    private val stressForecastEngine: com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine,
    private val forecastInputAssembler: ForecastInputAssembler
) {

    /**
     * Intermediate holder for pre-computed time/expense context shared across sub-methods.
     * Keeps the top-level `compute()` method small enough to avoid DEX register overflow.
     */
    private class ComputeContext(
        val data: ProcessedDashboardData,
        val now: Long,
        val todayStart: Long,
        val weekStart: Long,
        val monthStart: Long,
        val calendar: java.util.Calendar,
        val daysInMonth: Int,
        val dayOfMonth: Int,
        val daysRemaining: Int,
        val purchases: List<DashboardExpense>,
        val deposits: List<DashboardExpense>,
        val expenseEntities: List<TransactionSummary>,
        val totalSpent: Double,
        val monthSpent: Double,
        val txCount: Int,
        val previousMonthTotal: Double,
        val todaySpent: Double,
        val todayTxCount: Int,
        val weekSpent: Double,
        val overallBudget: BudgetStatusSnapshot?,
        val totalBudgetAmount: Double,
        val safeToSpend: Double
    )

    /** Pure computation: maps raw dashboard data → a list of ordered [DashboardWidget]s. */
    suspend fun compute(processedData: ProcessedDashboardData): CompiledDashboardData {
        val ctx = buildContext(processedData)

        val runwayResult = computeRunwayAndForecast(ctx)
        val blockPartyDays = computeBlockParty(ctx, runwayResult)
        val monteCarloWidget = computeMonteCarlo(ctx, runwayResult)
        val categoryTotals = computeCategoryTotals(ctx)
        val trend = computeSpendingTrend(ctx)
        val insightText = buildNaturalLanguageInsight(
            ctx.monthSpent, ctx.previousMonthTotal, ctx.todaySpent, ctx.todayTxCount
        )
        val budgetSummary = computeBudgetSummary(ctx)
        val streakData = calculateStreakData(ctx.calendar, ctx.data.data.expenses, ctx.monthStart)
        val healthScore = computeHealthScore(ctx, streakData.first)
        val healthScoreV2Result = computeHealthScoreV2(ctx)
        val lifestyleWidget = computeLifestyleWidget()
        val savingsSweepWidget = computeSavingsSweepWidget()
        val moneyRadarData = computeMoneyRadarUseCase.compute()
        val stressForecastResult = computeStressForecast()

        val widgets = assembleWidgets(
            ctx, runwayResult, blockPartyDays, monteCarloWidget,
            categoryTotals, trend, insightText, budgetSummary,
            streakData, healthScore, healthScoreV2Result,
            lifestyleWidget, savingsSweepWidget, moneyRadarData, stressForecastResult
        )

        return CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = ctx.totalSpent,
            txCount = ctx.txCount
        )
    }

    // ── Sub-methods to keep each function under the DEX 256-register limit ───

    private suspend fun buildContext(processedData: ProcessedDashboardData): ComputeContext {
        val data = processedData.data
        val summary = processedData.summary
        val expenses = data.expenses
        val now = timeProvider.now()
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val weekStart = TimePeriodUtils.getStartOfWeek(now)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)

        val purchases = expenses.filter {
            it.transactionType == DashboardTransactionType.PURCHASE && !it.isNotMine
        }
        val deposits = expenses.filter { it.transactionType == DashboardTransactionType.DEPOSIT }

        val todayPurchases = purchases.filter { it.date >= todayStart }
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        val budgetStatuses = data.budgetStatuses
        val overallBudget = budgetStatuses.find { it.budgetCategoryId == null }

        return ComputeContext(
            data = processedData,
            now = now,
            todayStart = todayStart,
            weekStart = weekStart,
            monthStart = monthStart,
            calendar = calendar,
            daysInMonth = daysInMonth,
            dayOfMonth = dayOfMonth,
            daysRemaining = daysInMonth - dayOfMonth,
            purchases = purchases,
            deposits = deposits,
            expenseEntities = expenses.map { it.toTransactionSummary() },
            totalSpent = summary.totalSpent,
            monthSpent = summary.totalSpent,
            txCount = summary.transactionCount,
            previousMonthTotal = summary.previousTotalSpent ?: 0.0,
            todaySpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(todayStart, now).displayAmount,
            todayTxCount = todayPurchases.size,
            weekSpent = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(weekStart, now).displayAmount,
            overallBudget = overallBudget,
            totalBudgetAmount = overallBudget?.budgetAmount ?: 0.0,
            safeToSpend = data.weather.discretionaryBudget
        )
    }

    private data class RunwayResult(
        val currentPace: SpendingPace,
        val forecast: com.yourname.expensetracker.domain.model.FinancialForecast,
        val financialRunway: DashboardWidget.FinancialRunway,
        val totalRemaining: Double,
        val totalCommitted: Double,
        val totalLikely: Double,
        val purchasesThisMonth: List<DashboardExpense>
    )

    private suspend fun computeRunwayAndForecast(ctx: ComputeContext): RunwayResult {
        val purchasesThisMonth = ctx.purchases.filter { it.date >= ctx.monthStart }
        val data = ctx.data.data
        val expenseSnapshots = data.expenses.map { expense ->
            ExpenseSnapshot(
                id = expense.id,
                amount = expense.amount,
                effectiveAmount = expense.effectiveAmount,
                currency = expense.currency,
                merchant = expense.merchant,
                merchantKey = MerchantKeyGenerator.generate(expense.merchant).ifBlank { null },
                transactionType = when (expense.transactionType) {
                    DashboardTransactionType.PURCHASE -> DomainTransactionType.PURCHASE
                    DashboardTransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
                    DashboardTransactionType.TRANSFER -> DomainTransactionType.TRANSFER
                    DashboardTransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
                    DashboardTransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
                },
                date = expense.date,
                categoryId = expense.categoryId,
                isNotMine = expense.isNotMine,
                transferDirection = null,
                notes = null
            )
        }

        val assembledInput = forecastInputAssembler.assemble(
            expenses = expenseSnapshots,
            manualRecurringEntities = emptyList(),
            detectedRecurringPatterns = data.recurringPatterns,
            plannedExpenses = data.plannedExpenses,
            savingsGoals = data.goals,
            budgetStatuses = data.budgetStatuses
        )
        val forecast = synthesisEngine.synthesize(assembledInput)
        val currentPace = assembledInput.spendingPace

        val totalCommitted = forecast.components?.totalCommitted ?: 0.0
        val totalLikely = forecast.components?.totalLikely ?: 0.0
        val averageDailyBurn = if (ctx.dayOfMonth > 0) ctx.monthSpent / ctx.dayOfMonth else 0.0
        val monthlyIncome = multiCurrencyRepository.getHomeCurrencyTotal(ctx.monthStart, ctx.now).displayAmount
        val totalRemaining = ctx.data.data.weather.discretionaryBudget.coerceAtLeast(0.0)

        val runwayDays = if (averageDailyBurn > 0 && totalRemaining > 0) {
            (totalRemaining / averageDailyBurn).toInt().coerceAtLeast(0)
        } else {
            0
        }

        val runwayStatus = when {
            monthlyIncome == 0.0 -> DashboardWidget.RunwayStatus.NO_INCOME
            runwayDays >= 14     -> DashboardWidget.RunwayStatus.HEALTHY
            runwayDays >= 7      -> DashboardWidget.RunwayStatus.CAUTION
            else                 -> DashboardWidget.RunwayStatus.CRITICAL
        }

        return RunwayResult(
            currentPace = currentPace,
            forecast = forecast,
            financialRunway = DashboardWidget.FinancialRunway(
                daysRemaining = runwayDays,
                totalBudget = ctx.totalBudgetAmount,
                discretionaryRemaining = totalRemaining,
                averageDailyDiscretionarySpend = averageDailyBurn,
                monthlyIncome = monthlyIncome,
                committedExpenses = totalCommitted,
                likelyExpenses = totalLikely,
                status = runwayStatus
            ),
            totalRemaining = totalRemaining,
            totalCommitted = totalCommitted,
            totalLikely = totalLikely,
            purchasesThisMonth = purchasesThisMonth
        )
    }

    private fun computeBlockParty(
        ctx: ComputeContext,
        runwayResult: RunwayResult
    ): List<DomainDayBudgetStatus> {
        val domainBlocks = synthesisEngine.calculateBlockPartyData(
            forecast = runwayResult.forecast,
            expenses = ctx.expenseEntities,
            dailySpending = ctx.data.summary.dailyHistory.map { it.toFloat() },
            budgetLimit = ctx.totalBudgetAmount
        )

        // Build a lookup map from categoryId → categoryName so we can populate
        // DomainExpenseSummary.categoryName with a real label rather than an ID string.
        val categoryNameById: Map<Long, String> = ctx.data.data.categories
            .associate { it.id to it.name }

        return domainBlocks.map { domain ->
            DomainDayBudgetStatus(
                dayOfMonth = domain.dayOfMonth,
                date = domain.date,
                actualSpent = domain.actualSpent,
                targetBudget = domain.targetBudget,
                isToday = domain.isToday,
                status = when (domain.status) {
                    BlockPartyStatus.UNDER_BUDGET -> DomainBlockStatus.UNDER_BUDGET
                    BlockPartyStatus.OVER_BUDGET  -> DomainBlockStatus.OVER_BUDGET
                    BlockPartyStatus.FUTURE       -> DomainBlockStatus.FUTURE
                    BlockPartyStatus.TODAY        -> DomainBlockStatus.TODAY
                    BlockPartyStatus.BILL_DAY     -> DomainBlockStatus.BILL_DAY
                    BlockPartyStatus.NO_DATA      -> DomainBlockStatus.NO_DATA
                },
                baseTarget = domain.baseTarget,
                recurringImpact = domain.recurringImpact,
                plannedImpact = domain.plannedImpact,
                recurringItems = domain.recurringItems,
                plannedItems = domain.plannedItems,
                topTransactions = domain.topTransactions.map { expense ->
                    DomainExpenseSummary(
                        id = expense.id,
                        amount = expense.effectiveAmount,
                        description = expense.merchant,
                        // Resolve a human-readable category label from the pre-loaded category
                        // list; keep null if the expense has no category or the ID is unknown.
                        // Never encode a numeric ID into a name field.
                        categoryName = expense.categoryId?.let { categoryNameById[it] },
                        date = expense.date
                    )
                }
            )
        }
    }

    private suspend fun computeMonteCarlo(
        ctx: ComputeContext,
        runwayResult: RunwayResult
    ): DashboardWidget.MonteCarloForecast? {
        return try {
            // NOTE: spentToDate now uses MultiCurrencyRepository for proper multi-currency conversion.
            val spentToDate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ctx.monthStart, ctx.now).displayAmount
            val knownUpcoming = runwayResult.totalCommitted + runwayResult.totalLikely
            val budgetForMC = if (ctx.totalBudgetAmount > 0) ctx.totalBudgetAmount else null

            val mcResult = monteCarloSimulator.simulate(
                spentToDate = spentToDate,
                knownUpcoming = knownUpcoming,
                budgetAmount = budgetForMC
            )

            mcResult?.let { DashboardWidget.MonteCarloForecast(it) }
        } catch (e: Exception) {
            Timber.e(e, "Monte Carlo simulation failed")
            null
        }
    }

    private fun computeCategoryTotals(ctx: ComputeContext): List<CategorySpending> {
        return ctx.data.categoryBreakdown.map {
            CategorySpending(
                category = CategoryInfo(
                    id = it.categoryId,
                    name = it.categoryName,
                    icon = it.categoryIcon,
                    color = it.categoryColor,
                    isIncome = false
                ),
                total = it.amount,
                percentage = it.percentage.toFloat()
            )
        }
    }

    private fun computeSpendingTrend(ctx: ComputeContext): DashboardWidget.SpendingTrend {
        val trendSeriesCal = java.util.Calendar.getInstance()
        val trendSeries = mutableListOf<SpendingTrendSeries>()
        // DSH-N2: Deduplicate by expense ID to prevent shared/duplicate expenses
        // from being counted twice in the spending trend. Shared expenses that
        // appear in both the payer's and participant's records have the same
        // expense ID — the distinctBy ensures each unique expense is counted once.
        val purchasesByMonth = ctx.data.data.expenses
            .filter { it.transactionType == DashboardTransactionType.PURCHASE && !it.isNotMine }
            .distinctBy { it.id }
            .groupBy { expense ->
                trendSeriesCal.timeInMillis = expense.date
                Pair(trendSeriesCal.get(java.util.Calendar.YEAR), trendSeriesCal.get(java.util.Calendar.MONTH))
            }

        val monthKeys = mutableListOf<Pair<Int, Int>>()
        val baseCal = java.util.Calendar.getInstance().apply { timeInMillis = ctx.now }
        repeat(6) {
            monthKeys.add(0, Pair(baseCal.get(java.util.Calendar.YEAR), baseCal.get(java.util.Calendar.MONTH)))
            baseCal.add(java.util.Calendar.MONTH, -1)
        }

        val monthLabels = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        monthKeys.forEach { (yr, mo) ->
            val monthExpenses = purchasesByMonth[Pair(yr, mo)] ?: emptyList()

            val tempCal = java.util.Calendar.getInstance()
            tempCal.set(yr, mo, 1, 0, 0, 0)
            tempCal.set(java.util.Calendar.MILLISECOND, 0)
            val mStart = tempCal.timeInMillis
            val daysInThisMonth = tempCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

            // DSH-N1: Emit zero-filled series for empty months instead of
            // skipping them, so the spending trend chart shows continuous data.
            val daily = DoubleArray(daysInThisMonth)
            monthExpenses.forEach { exp ->
                val dayIdx = TimePeriodUtils.daysBetween(mStart, exp.date).coerceIn(0, daysInThisMonth - 1)
                daily[dayIdx] += exp.effectiveAmount
            }
            var running = 0.0
            val cumulative = daily.map { d -> running += d; running.toFloat() }

            val isCurrentMonth = (yr == ctx.calendar.get(java.util.Calendar.YEAR) &&
                    mo == ctx.calendar.get(java.util.Calendar.MONTH))

            trendSeries.add(SpendingTrendSeries(
                label = monthLabels[mo],
                data = cumulative,
                isCurrentMonth = isCurrentMonth
            ))
        }

        return DashboardWidget.SpendingTrend(series = trendSeries)
    }

    private fun computeBudgetSummary(ctx: ComputeContext): UiText? {
        val budgetStatuses = ctx.data.data.budgetStatuses
        if (budgetStatuses.isEmpty()) return null
        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        return if (exceeded > 0) UiText.fromKey(DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT, exceeded)
        else UiText.fromKey(DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK)
    }

    private fun computeHealthScore(
        ctx: ComputeContext,
        currentStreak: Int
    ): com.yourname.expensetracker.domain.health.HealthScoreResult {
        val dashboardExpenses = ctx.data.data.expenses
        return healthCalculator.calculateHealthScores(
            // TODO ISSUE-3: FinancialHealthCalculator.calculateHealthScores still expects List<Expense>;
            //  pass empty list until that API is migrated to TransactionSummary / DashboardExpense.
            expenses = emptyList(),
            budgetStatuses = ctx.data.data.budgetStatuses,
            pendingReviews = ctx.data.data.pendingCount,
            todayStreak = calculateStreakForPeriod(dashboardExpenses, ctx.todayStart, ctx.now),
            weekStreak = calculateStreakForPeriod(dashboardExpenses, ctx.weekStart, ctx.now),
            monthStreak = calculateStreakForPeriod(dashboardExpenses, ctx.monthStart, ctx.now),
            noSpendStreak = currentStreak
        )
    }

    private suspend fun computeHealthScoreV2(
        ctx: ComputeContext
    ): FinancialHealthResult? {
        return try {
            healthScoreV2.calculateHealthScore(
                periodStart = ctx.monthStart,
                periodEnd = TimePeriodUtils.getEndOfMonth(ctx.now)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate financial health score v2")
            null
        }
    }

    private suspend fun computeLifestyleWidget(): DashboardWidget.LifestyleSavingsPrompt? {
        val recommendation = runCatching {
            withTimeout(3000L) {
                lifestyleSavingsPromptUseCase.evaluateAndPrompt()
            }
        }
            .onFailure { Timber.e(it, "Failed to evaluate lifestyle savings prompt") }
            .getOrNull()
        return recommendation?.let {
            DashboardWidget.LifestyleSavingsPrompt(
                inflationRate = it.inflationRate,
                suggestedUplift = it.suggestedMonthlyUplift,
                reason = it.reason,
                hasExistingGoals = it.goals.isNotEmpty()
            )
        }
    }

    private suspend fun computeSavingsSweepWidget(): DashboardWidget.SavingsSweepPrompt? {
        return try {
            withTimeout(3000L) {
                monthlySavingsSweepUseCase.computeSweepRecommendation()
            }?.let { recommendation ->
                DashboardWidget.SavingsSweepPrompt(
                    sweepAmount = recommendation.safeSweepAmount,
                    underspend = recommendation.totalUnderspend,
                    riskBuffer = recommendation.riskBuffer,
                    goalAllocations = recommendation.goalAllocations.map { allocation ->
                        DashboardWidget.SweepGoalAllocation(
                            goalId = allocation.goalId,
                            goalName = allocation.goalName,
                            suggestedAmount = allocation.suggestedAllocation,
                            currentProgress = allocation.currentProgress,
                            targetAmount = allocation.targetAmount
                        )
                    },
                    confidence = recommendation.confidence,
                    daysUntilMonthEnd = TimePeriodUtils.daysBetween(recommendation.computedAt, recommendation.monthEnd)
                        .coerceAtLeast(0)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute savings sweep widget")
            null
        }
    }

    private suspend fun computeStressForecast(): com.yourname.expensetracker.domain.forecasting.StressForecastResult? {
        return try {
            stressForecastEngine.computeStressForecast()
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute financial stress forecast")
            null
        }
    }

    private fun assembleWidgets(
        ctx: ComputeContext,
        runwayResult: RunwayResult,
        blockPartyDays: List<DomainDayBudgetStatus>,
        monteCarloWidget: DashboardWidget.MonteCarloForecast?,
        categoryTotals: List<CategorySpending>,
        trend: DashboardWidget.SpendingTrend,
        insightText: Pair<UiText, String>?,
        budgetSummary: UiText?,
        streakData: Triple<Int, Int, Int>,
        healthScore: com.yourname.expensetracker.domain.health.HealthScoreResult,
        healthScoreV2Result: com.yourname.expensetracker.domain.health.FinancialHealthResult?,
        lifestyleWidget: DashboardWidget.LifestyleSavingsPrompt?,
        savingsSweepWidget: DashboardWidget.SavingsSweepPrompt?,
        moneyRadarData: MoneyRadarData,
        stressForecastResult: com.yourname.expensetracker.domain.forecasting.StressForecastResult?
    ): List<DashboardWidget> {
        val weather = ctx.data.data.weather
        val budgetStatuses = ctx.data.data.budgetStatuses
        val pendingCount = ctx.data.data.pendingCount
        val (currentStreak, personalBest, daysWithoutSpendingThisMonth) = streakData

        return buildList {
            add(DashboardWidget.FinancialWeatherWidget(weather))

            // Money Radar Widget (F4) - Today's unified alerts
            add(DashboardWidget.MoneyRadar(moneyRadarData))

            // Financial Stress Forecast Widget (F8) - 30/60/90 day cash crunch prediction
            if (stressForecastResult != null) {
                add(DashboardWidget.FinancialStressForecast(stressForecastResult))
            }

            // Lifestyle Savings Prompt (if applicable)
            if (lifestyleWidget != null) {
                add(lifestyleWidget)
            }

            if (savingsSweepWidget != null) {
                add(savingsSweepWidget)
            }

            // Emit a single authoritative health KPI.
            if (healthScoreV2Result != null) {
                add(DashboardWidget.FinancialHealthScoreV2Widget(healthScoreV2Result))
            } else {
                add(DashboardWidget.FinancialHealthScoreWidget(healthScore))
            }

            add(DashboardWidget.TotalsDashboard)

            // No-Spend Streak Widget (gamification) - always shown
            add(DashboardWidget.NoSpendStreak(
                currentStreak = currentStreak,
                personalBest = personalBest,
                daysWithoutSpendingThisMonth = daysWithoutSpendingThisMonth
            ))

            add(
                DashboardWidget.SafeToSpend(
                    // DSH-6: When no budget is configured (totalBudget == null), set amount to 0.0
                    // so the UI can show a clear CTA ("Set a budget") instead of showing monthSpent,
                    // which confusingly displays money *already spent* as if it were *available*.
                    amount = if (ctx.overallBudget != null) ctx.safeToSpend else 0.0,
                    totalBudget = ctx.overallBudget?.budgetAmount,
                    daysRemaining = ctx.daysRemaining
                )
            )
            if (runwayResult.totalRemaining > 0 || ctx.totalBudgetAmount > 0) add(runwayResult.financialRunway)
            if (monteCarloWidget != null) add(monteCarloWidget)
            if (blockPartyDays.isNotEmpty()) add(DashboardWidget.BudgetBlockParty(blockPartyDays))
            if (runwayResult.currentPace.paceStatus != PaceStatus.NO_BASELINE) {
                add(DashboardWidget.SpendingPaceWidget(runwayResult.currentPace))
            }
            add(trend)
            if (pendingCount > 0) add(DashboardWidget.PendingReviewAlert(pendingCount))
            if (insightText != null) add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
            add(DashboardWidget.PeriodSummary(ctx.todaySpent, ctx.weekSpent, ctx.monthSpent))
            if (budgetStatuses.isNotEmpty()) add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
            if (categoryTotals.isNotEmpty()) add(DashboardWidget.TopCategories(categoryTotals.take(5)))
            if (ctx.purchases.isNotEmpty()) add(DashboardWidget.RecentTransactions(ctx.purchases.take(5)))
        }
    }

    private fun buildNaturalLanguageInsight(
        monthSpent: Double,
        previousMonthTotal: Double,
        todaySpent: Double,
        txCount: Int
    ): Pair<UiText, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return when {
                diff < 0 -> Pair(
                    UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_SPENT_LESS_FORMAT, -diff),
                    "📉"
                )
                diff > previousMonthTotal * 0.2 -> Pair(
                    UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_SPENT_HIGHER_FORMAT, diff),
                    "📈"
                )
                else -> null
            }
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                UiText.fromKey(DashboardTextKeys.WIDGET_INSIGHT_TODAY_SPENT_FORMAT, todaySpent, txCount),
                "💡"
            )
        }
        return null
    }

    /**
     * Calculates no-spend streak data for gamification.
     * @return Triple of (currentStreak, personalBest, daysWithoutSpendingThisMonth)
     */
    private fun calculateStreakData(
        calendar: java.util.Calendar,
        expenses: List<DashboardExpense>,
        startOfMonth: Long
    ): Triple<Int, Int, Int> {
        val now = calendar.timeInMillis
        val oneDayMs = TimePeriodUtils.DAY_IN_MILLIS
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        
        // Track unique PURCHASE days owned by the user.
        val purchaseDays = expenses
            .filter {
                it.transactionType == DashboardTransactionType.PURCHASE &&
                    !it.isNotMine
            }
            .map { TimePeriodUtils.getStartOfDay(it.date) }
            .filter { it <= todayStart }
            .distinct()
            .sorted()

        // Explicit empty-history handling: no purchases yet => streak spans elapsed month days.
        if (purchaseDays.isEmpty()) {
            val elapsedDaysThisMonth = dayOfMonth.coerceAtLeast(0)
            return Triple(elapsedDaysThisMonth, elapsedDaysThisMonth, elapsedDaysThisMonth)
        }
        
        // Calculate current streak (from today backward), bounded by app-history start.
        val oldestPurchaseDay = purchaseDays.first()
        var currentStreak = 0
        var checkDate = todayStart
        val purchaseDaySet = purchaseDays.toHashSet()
        
        while (checkDate >= oldestPurchaseDay) {
            if (!purchaseDaySet.contains(checkDate)) {
                currentStreak++
                checkDate -= oneDayMs
            } else {
                break
            }
        }
        
        // Calculate personal best (max no-spend gap between purchase days).
        var personalBest = currentStreak
        for (index in 1 until purchaseDays.size) {
            val gapDays = TimePeriodUtils.daysBetween(purchaseDays[index - 1], purchaseDays[index]) - 1
            if (gapDays > personalBest) {
                personalBest = gapDays
            }
        }
        
        // Month metric should only include elapsed days (not future days).
        val expenseDaysThisMonth = purchaseDays.count { it >= startOfMonth && it <= todayStart }
        val elapsedDaysThisMonth = dayOfMonth.coerceAtLeast(0)
        val daysWithoutSpendingThisMonth = (elapsedDaysThisMonth - expenseDaysThisMonth).coerceAtLeast(0)
        
        return Triple(currentStreak, personalBest.coerceAtLeast(currentStreak), daysWithoutSpendingThisMonth)
    }
    
    /**
     * Calculates consecutive days without spending for a specific period.
     */
    private fun calculateStreakForPeriod(
        expenses: List<DashboardExpense>,
        periodStart: Long,
        periodEnd: Long
    ): Int {
        val periodStartDay = TimePeriodUtils.getStartOfDay(periodStart)
        val today = TimePeriodUtils.getStartOfDay(periodEnd)
        if (today < periodStartDay) return 0

        val elapsedDays = TimePeriodUtils.daysBetween(periodStartDay, today).coerceAtLeast(0) + 1

        val expenseDays = expenses
            .asSequence()
            .filter {
                it.transactionType == DashboardTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    it.date >= periodStart &&
                    it.date < periodEnd
            }
            .map { TimePeriodUtils.getStartOfDay(it.date) }
            .toSet()

        // No expenses in elapsed period => max possible no-spend streak.
        if (expenseDays.isEmpty()) return elapsedDays

        var streak = 0
        var checkDay = today
        val oneDayMs = TimePeriodUtils.DAY_IN_MILLIS
        while (checkDay >= periodStartDay && !expenseDays.contains(checkDay)) {
            streak++
            checkDay -= oneDayMs
        }
        
        return streak
    }



}
