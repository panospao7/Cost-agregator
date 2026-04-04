package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.dashboard.DomainBlockStatus
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.DomainExpenseSummary
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─── Domain models exposed to the UI layer ───────────────────────────────────

sealed class DashboardWidget {
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
        val statuses: List<com.yourname.expensetracker.domain.budget.BudgetStatus>,
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
    val percentage: Float
)

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
    private val healthCalculator: com.yourname.expensetracker.domain.health.FinancialHealthCalculator,
    private val healthScoreV2: com.yourname.expensetracker.domain.health.FinancialHealthScoreV2,
    private val lifestyleSavingsPromptUseCase: com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase,
    private val computeMoneyRadarUseCase: ComputeMoneyRadarUseCase,
    private val stressForecastEngine: com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
) {

    /** Pure computation: maps raw dashboard data → a list of ordered [DashboardWidget]s. */
    suspend fun compute(processedData: ProcessedDashboardData): CompiledDashboardData {
        val data = processedData.data
        val summary = processedData.summary
        val categoryBreakdown = processedData.categoryBreakdown

        val expenses = data.expenses
        val budgetStatuses = data.budgetStatuses
        val pendingCount = data.pendingCount
        val weather = data.weather
        val recurringPatterns = data.recurringPatterns
        val plannedExpenses = data.plannedExpenses
        val goals = data.goals

        val now = timeProvider.now()
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val weekStart = TimePeriodUtils.getStartOfWeek(now)
        val monthStart = TimePeriodUtils.getStartOfMonth(now)

        val purchases = expenses.filter {
            it.transactionType == DashboardTransactionType.PURCHASE && !it.isNotMine
        }
        val deposits = expenses.filter { it.transactionType == DashboardTransactionType.DEPOSIT }

        val weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.effectiveAmount }
        val todayPurchases = purchases.filter { it.date >= todayStart }
        val todaySpent = todayPurchases.sumOf { it.effectiveAmount }
        val todayTxCount = todayPurchases.size

        val totalSpent = summary.totalSpent
        val monthSpent = totalSpent
        val txCount = summary.transactionCount
        val previousMonthTotal = summary.previousTotalSpent ?: 0.0

        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val daysRemaining = daysInMonth - dayOfMonth

        val overallBudget = budgetStatuses.find { it.budget.categoryId == null }
        val safeToSpend = weather.discretionaryBudget
        val totalBudgetAmount = overallBudget?.budget?.amount ?: 0.0

        val expenseEntitiesForEngines = expenses.map { it.toEntityExpense() }

        // ── Financial Runway ─────────────────────────────────────────────────
        val currentDayIdx = TimePeriodUtils.daysBetween(monthStart, now).coerceAtLeast(0)

        val currentPace = try {
            insightsEngine.getSpendingPaceSuspend(expenseEntitiesForEngines)
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate spending pace")
            SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = currentDayIdx,
                daysInMonth = daysInMonth,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0f,
                paceStatus = PaceStatus.NO_BASELINE
            )
        }

        val purchasesThisMonth = purchases.filter { it.date >= monthStart }
        val amountByDay = DoubleArray(currentDayIdx + 1)
        purchasesThisMonth.forEach { exp ->
            val dayIndex = TimePeriodUtils.daysBetween(monthStart, exp.date)
            if (dayIndex in amountByDay.indices) amountByDay[dayIndex] += exp.effectiveAmount
        }
        var runningTotal = 0.0
        val pastSumDaily = amountByDay.map { runningTotal += it; runningTotal }

        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = goals,
            budgetStatuses = budgetStatuses,
            spendingPace = currentPace
        )

        val totalCommitted = forecast.components?.totalCommitted ?: 0.0
        val totalLikely = forecast.components?.totalLikely ?: 0.0

        val projectedSpendingPoints = forecast.components?.projectedSpendingPoints ?: emptyList()
        val projectedMonthlyTotal = projectedSpendingPoints.lastOrNull() ?: monthSpent

        val averageDailyBurn = if (dayOfMonth > 0) monthSpent / dayOfMonth else 0.0

        val monthlyIncome = deposits
            .filter { it.date >= monthStart }
            .sumOf { it.effectiveAmount }

        val totalRemaining = weather.discretionaryBudget.coerceAtLeast(0.0)

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

        val financialRunway = DashboardWidget.FinancialRunway(
            daysRemaining = runwayDays,
            totalBudget = totalBudgetAmount,
            discretionaryRemaining = totalRemaining,
            averageDailyDiscretionarySpend = averageDailyBurn,
            monthlyIncome = monthlyIncome,
            committedExpenses = totalCommitted,
            likelyExpenses = totalLikely,
            status = runwayStatus
        )

        // ── Block Party ──────────────────────────────────────────────────────
        val domainBlocks = synthesisEngine.calculateBlockPartyData(
            forecast = forecast,
            expenses = expenseEntitiesForEngines,
            dailySpending = summary.dailyHistory,
            budgetLimit = totalBudgetAmount
        )

        // ── Monte Carlo Forecast ─────────────────────────────────────────────
        val monteCarloWidget: DashboardWidget.MonteCarloForecast? = try {
            // spentToDate = purchases this month (same filter as everywhere else)
            val spentToDate = purchasesThisMonth.sumOf { it.effectiveAmount }
            // knownUpcoming = committed + likely from SynthesisEngine
            val knownUpcoming = totalCommitted + totalLikely
            // budget = overall monthly budget (null if none set)
            val budgetForMC = if (totalBudgetAmount > 0) totalBudgetAmount else null

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

        val blockPartyDays = domainBlocks.map { domain ->
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
                        categoryName = expense.categoryId?.toString(),
                        date = expense.date
                    )
                }
            )
        }

        // ── Category totals ──────────────────────────────────────────────────
        val categoryTotals = categoryBreakdown.map {
            CategorySpending(
                category = CategoryInfo(
                    id = it.category.id,
                    name = it.category.name,
                    icon = it.category.icon,
                    color = it.category.color,
                    isIncome = false
                ),
                total = it.total,
                percentage = it.percentage
            )
        }

        // ── Spending Pace widget ─────────────────────────────────────────────
        val baseline = overallBudget?.budget?.amount
            ?: if (previousMonthTotal > 0) previousMonthTotal else null

        val dayOfMonthCoerced = dayOfMonth.coerceAtLeast(1)
        val projectedTotal = if (dayOfMonth == 1) {
            if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
            else monthSpent * daysInMonth
        } else {
            monthSpent * daysInMonth.toDouble() / dayOfMonth
        }

        val pacePercentage = if (baseline != null && baseline > 0) {
            val expected = baseline * dayOfMonthCoerced / daysInMonth
            val calculated = (monthSpent / expected * 100).toFloat()
            if (calculated.isFinite()) calculated else 0f
        } else 0f

        val pace = SpendingPace(
            currentMonthSpent = monthSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousMonthTotal > 0) previousMonthTotal else null,
            averageMonthlyTotal = null,
            pacePercentage = pacePercentage,
            paceStatus = when {
                baseline == null || baseline <= 0 -> PaceStatus.NO_BASELINE
                pacePercentage < 90f             -> PaceStatus.UNDER_PACE
                pacePercentage > 110f            -> PaceStatus.OVER_PACE
                else                             -> PaceStatus.ON_PACE
            }
        )

        // ── Multi-month cumulative spending series ───────────────────────────
        val trendSeriesCal = java.util.Calendar.getInstance()
        val trendSeries = mutableListOf<SpendingTrendSeries>()
        val purchasesByMonth = expenses
            .filter { it.transactionType == DashboardTransactionType.PURCHASE && !it.isNotMine }
            .groupBy { expense ->
                trendSeriesCal.timeInMillis = expense.date
                Pair(trendSeriesCal.get(java.util.Calendar.YEAR), trendSeriesCal.get(java.util.Calendar.MONTH))
            }

        // Collect current month + up to 5 prior month keys, oldest first
        val monthKeys = mutableListOf<Pair<Int, Int>>()
        val baseCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        repeat(6) {
            monthKeys.add(0, Pair(baseCal.get(java.util.Calendar.YEAR), baseCal.get(java.util.Calendar.MONTH)))
            baseCal.add(java.util.Calendar.MONTH, -1)
        }

        val monthLabels = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        monthKeys.forEach { (yr, mo) ->
            val monthExpenses = purchasesByMonth[Pair(yr, mo)] ?: emptyList()
            if (monthExpenses.isEmpty()) return@forEach

            val tempCal = java.util.Calendar.getInstance()
            tempCal.set(yr, mo, 1, 0, 0, 0)
            tempCal.set(java.util.Calendar.MILLISECOND, 0)
            val mStart = tempCal.timeInMillis
            val daysInThisMonth = tempCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

            val daily = DoubleArray(daysInThisMonth)
            monthExpenses.forEach { exp ->
                val dayIdx = TimePeriodUtils.daysBetween(mStart, exp.date).coerceIn(0, daysInThisMonth - 1)
                daily[dayIdx] += exp.effectiveAmount
            }
            var running = 0.0
            val cumulative = daily.map { d -> running += d; running.toFloat() }

            val isCurrentMonth = (yr == calendar.get(java.util.Calendar.YEAR) &&
                    mo == calendar.get(java.util.Calendar.MONTH))

            trendSeries.add(SpendingTrendSeries(
                label = monthLabels[mo],
                data = cumulative,
                isCurrentMonth = isCurrentMonth
            ))
        }

        val trend = DashboardWidget.SpendingTrend(series = trendSeries)

        val insightText = buildNaturalLanguageInsight(
            monthSpent, previousMonthTotal, todaySpent, todayTxCount
        )

        val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            if (exceeded > 0) UiText.fromKey(DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT, exceeded)
            else UiText.fromKey(DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK)
        } else null

        // ── Calculate No-Spend Streak Data ────────────────────────────────────
        val (currentStreak, personalBest, daysWithoutSpendingThisMonth) = calculateStreakData(
            calendar, expenses, monthStart
        )

        // ── Calculate Financial Health Score ───────────────────────────────────
        val healthScore = healthCalculator.calculateHealthScores(
            expenses = expenseEntitiesForEngines,
            budgetStatuses = budgetStatuses,
            pendingReviews = pendingCount,
            todayStreak = calculateStreakForPeriod(expenseEntitiesForEngines, todayStart, now),
            weekStreak = calculateStreakForPeriod(expenseEntitiesForEngines, weekStart, now),
            monthStreak = calculateStreakForPeriod(expenseEntitiesForEngines, monthStart, now),
            noSpendStreak = currentStreak
        )

        // ── Calculate Financial Health Score V2 (F5) ─────────────────────────
        val healthScoreV2Result = try {
            healthScoreV2.calculateHealthScore(
                periodStart = monthStart,
                periodEnd = TimePeriodUtils.getEndOfMonth(now)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate financial health score v2")
            null
        }

        // ── Check for Lifestyle Savings Opportunity ─────────────────────────
        val lifestyleRecommendation = runCatching {
            withTimeout(3000L) {
                lifestyleSavingsPromptUseCase.evaluateAndPrompt()
            }
        }
            .onFailure { Timber.e(it, "Failed to evaluate lifestyle savings prompt") }
            .getOrNull()
        val lifestyleWidget: DashboardWidget.LifestyleSavingsPrompt? = lifestyleRecommendation?.let {
            DashboardWidget.LifestyleSavingsPrompt(
                inflationRate = it.inflationRate,
                suggestedUplift = it.suggestedMonthlyUplift,
                reason = it.reason,
                hasExistingGoals = it.goals.isNotEmpty()
            )
        }
        
        // ── Compute Money Radar (F4) ───────────────────────────────────────
        val moneyRadarData = computeMoneyRadarUseCase.compute()

        // ── Compute Financial Stress Forecast (F8) ─────────────────────────
        val stressForecastResult = try {
            stressForecastEngine.computeStressForecast()
        } catch (e: Exception) {
            Timber.e(e, "Failed to compute financial stress forecast")
            null
        }

        // ── Assemble widget list ─────────────────────────────────────────────
        val widgets = buildList {
            add(DashboardWidget.FinancialWeatherWidget(weather))
            
            // NEW: Money Radar Widget (F4) - Today's unified alerts
            add(DashboardWidget.MoneyRadar(moneyRadarData))
            
            // NEW: Financial Stress Forecast Widget (F8) - 30/60/90 day cash crunch prediction
            if (stressForecastResult != null) {
                add(DashboardWidget.FinancialStressForecast(stressForecastResult))
            }
            
            // NEW: Lifestyle Savings Prompt (if applicable)
            if (lifestyleWidget != null) {
                add(lifestyleWidget)
            }
            
            // NEW: Financial Health Score V2 Widget (F5)
            if (healthScoreV2Result != null) {
                add(DashboardWidget.FinancialHealthScoreV2Widget(healthScoreV2Result))
            }
            
            // Legacy Financial Health Score Widget (keep for comparison)
            add(DashboardWidget.FinancialHealthScoreWidget(healthScore))
            
            add(DashboardWidget.TotalsDashboard)
            
            // NEW: No-Spend Streak Widget (gamification)
            // Always show to encourage streak building, even at 0
            add(DashboardWidget.NoSpendStreak(
                currentStreak = currentStreak,
                personalBest = personalBest,
                daysWithoutSpendingThisMonth = daysWithoutSpendingThisMonth
            ))
            
            add(
                DashboardWidget.SafeToSpend(
                    amount = if (overallBudget != null) safeToSpend else monthSpent,
                    totalBudget = overallBudget?.budget?.amount,
                    daysRemaining = daysRemaining
                )
            )
            if (totalRemaining > 0 || totalBudgetAmount > 0) add(financialRunway)
            if (monteCarloWidget != null) add(monteCarloWidget)
            if (blockPartyDays.isNotEmpty()) add(DashboardWidget.BudgetBlockParty(blockPartyDays))
            if (pace.paceStatus != PaceStatus.NO_BASELINE) add(DashboardWidget.SpendingPaceWidget(pace))
            add(trend)
            if (pendingCount > 0) add(DashboardWidget.PendingReviewAlert(pendingCount))
            if (insightText != null) add(DashboardWidget.NaturalLanguageInsight(insightText.first, insightText.second))
            add(DashboardWidget.PeriodSummary(todaySpent, weekSpent, monthSpent))
            if (budgetStatuses.isNotEmpty()) add(DashboardWidget.BudgetHealthWidget(budgetStatuses, budgetSummary))
            if (categoryTotals.isNotEmpty()) add(DashboardWidget.TopCategories(categoryTotals.take(5)))
            if (purchases.isNotEmpty()) add(DashboardWidget.RecentTransactions(purchases.take(5)))
        }

        return CompiledDashboardData(
            allWidgets = widgets,
            totalSpent = totalSpent,
            txCount = txCount
        )
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
        expenses: List<com.yourname.expensetracker.data.database.entity.Expense>,
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
                it.transactionType == TransactionType.PURCHASE &&
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

    private fun DashboardExpense.toEntityExpense(): com.yourname.expensetracker.data.database.entity.Expense {
        val txType = when (transactionType) {
            DashboardTransactionType.PURCHASE -> com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
            DashboardTransactionType.WITHDRAWAL -> com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL
            DashboardTransactionType.TRANSFER -> com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER
            DashboardTransactionType.DEPOSIT -> com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT
            DashboardTransactionType.UNKNOWN -> com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN
        }
        return com.yourname.expensetracker.data.database.entity.Expense(
            id = id,
            amount = amount,
            merchant = merchant,
            transactionType = txType,
            date = date,
            categoryId = categoryId,
            isNotMine = isNotMine,
            isManualEntry = isManualEntry,
            merchantKey = com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate(merchant)
        )
    }
}
