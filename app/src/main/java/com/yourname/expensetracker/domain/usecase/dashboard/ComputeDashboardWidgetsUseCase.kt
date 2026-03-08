package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.FinancialWeather
import com.yourname.expensetracker.data.repository.WeatherState
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.ui.components.BlockStatus
import com.yourname.expensetracker.ui.components.DayBudgetStatus
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
        val days: List<DayBudgetStatus>
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
        val statuses: List<BudgetStatus>,
        val summary: String?
    ) : DashboardWidget()

    data class RecentTransactions(
        val expenses: List<Expense>
    ) : DashboardWidget()

    data class NaturalLanguageInsight(
        val text: String,
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
}

data class CategorySpending(
    val category: Category,
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
    private val timeProvider: TimeProvider
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
            it.transactionType == TransactionType.PURCHASE && !it.isNotMine
        }
        val deposits = expenses.filter { it.transactionType == TransactionType.DEPOSIT }

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

        // ── Financial Runway ─────────────────────────────────────────────────
        val currentDayIdx = ((now - monthStart) / 86_400_000L).toInt().coerceAtLeast(0)

        val currentPace = try {
            insightsEngine.getSpendingPaceSuspend(expenses)
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
            val dayIndex = ((exp.date - monthStart) / 86_400_000L).toInt()
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
            expenses = expenses,
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
            DayBudgetStatus(
                dayOfMonth = domain.dayOfMonth,
                date = domain.date,
                actualSpent = domain.actualSpent,
                targetBudget = domain.targetBudget,
                isToday = domain.isToday,
                status = when (domain.status) {
                    BlockPartyStatus.UNDER_BUDGET -> BlockStatus.UNDER_BUDGET
                    BlockPartyStatus.OVER_BUDGET  -> BlockStatus.OVER_BUDGET
                    BlockPartyStatus.FUTURE       -> BlockStatus.FUTURE
                    BlockPartyStatus.TODAY        -> BlockStatus.TODAY
                    BlockPartyStatus.BILL_DAY     -> BlockStatus.BILL_DAY
                    BlockPartyStatus.NO_DATA      -> BlockStatus.NO_DATA
                },
                baseTarget = domain.baseTarget,
                recurringImpact = domain.recurringImpact,
                plannedImpact = domain.plannedImpact,
                recurringItems = domain.recurringItems,
                plannedItems = domain.plannedItems,
                topTransactions = domain.topTransactions
            )
        }

        // ── Category totals ──────────────────────────────────────────────────
        val categoryTotals = categoryBreakdown.map {
            CategorySpending(it.category, it.total, it.percentage)
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
            .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
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
                val dayIdx = ((exp.date - mStart) / 86_400_000L).toInt().coerceIn(0, daysInThisMonth - 1)
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
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track"
        } else null

        // ── Assemble widget list ─────────────────────────────────────────────
        val widgets = buildList {
            add(DashboardWidget.FinancialWeatherWidget(weather))
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
    ): Pair<String, String>? {
        if (previousMonthTotal > 0) {
            val diff = monthSpent - previousMonthTotal
            return when {
                diff < 0 -> Pair(
                    "You've spent €${String.format("%.0f", -diff)} less than last month so far.",
                    "📉"
                )
                diff > previousMonthTotal * 0.2 -> Pair(
                    "Spending is €${String.format("%.0f", diff)} higher than last month.",
                    "📈"
                )
                else -> null
            }
        }
        if (txCount > 0 && todaySpent > 0) {
            return Pair(
                "You've spent €${String.format("%.2f", todaySpent)} today across $txCount transactions.",
                "💡"
            )
        }
        return null
    }
}
