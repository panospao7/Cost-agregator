package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.MoneyRadarData
import com.yourname.expensetracker.domain.usecase.dashboard.UrgencyLevel
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Stress tests for dashboard widget consistency.
 */
class DashboardWidgetConsistencyStressTest {

    private val timeProvider = mockk<TimeProvider>()
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    private fun ts(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setup() {
        every { timeProvider.now() } returns ts(2024, 5, 15)
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns SpendingPace(
            currentMonthSpent = 500.0,
            daysElapsed = 15,
            daysInMonth = 30,
            projectedTotal = 1000.0,
            previousMonthTotal = 800.0,
            averageMonthlyTotal = 800.0,
            pacePercentage = 100f,
            paceStatus = PaceStatus.ON_PACE
        )
        val monteCarloSimulator = mockk<com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any()) } returns null
        val healthCalculator = mockk<com.yourname.expensetracker.domain.health.FinancialHealthCalculator>(relaxed = true)
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns FinancialHealthResult(
            overallScore = 50,
            savingsRateScore = 50,
            runwayScore = 50,
            budgetAdherenceScore = 50,
            billReliabilityScore = 50,
            factorContributions = emptyList(),
            trend = HealthTrend.STABLE,
            recommendation = null
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
            urgencyScore = 0,
            urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(),
            anomalyAlerts = emptyList(),
            budgetRisk = null,
            topReasons = emptyList(),
            primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(
                StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
            ),
            overallRiskLevel = StressRiskLevel.LOW,
            earliestCrunchDate = null,
            recommendations = emptyList()
        )

        computeUseCase = ComputeDashboardWidgetsUseCase(
            insightsEngine = insightsEngine,
            synthesisEngine = com.yourname.expensetracker.domain.logic.SynthesisEngine(timeProvider),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = healthCalculator,
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine
        )
    }

    @Test
    fun `stress - 100 compute calls produce consistent totalSpent`() = runTest {
        val monthSpent = 1234.56
        val processedData = processedDataFrom(monthSpent, emptyList())

        repeat(100) {
            val result = computeUseCase.compute(processedData)
            assertEquals("totalSpent must be deterministic", monthSpent, result.totalSpent, 0.001)
        }
    }

    @Test
    fun `stress - 50 runs with varying monthSpent`() = runTest {
        for (i in 1..50) {
            val monthSpent = i * 100.0
            val processedData = processedDataFrom(monthSpent, emptyList())
            val result = computeUseCase.compute(processedData)
            assertEquals("totalSpent for $i", monthSpent, result.totalSpent, 0.001)
        }
    }

    @Test
    fun `stress - 50 expenses PeriodSummary consistent`() = runTest {
        val now = ts(2024, 5, 15)
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val expenses = (1..50).map { i ->
            Expense(
                amount = i * 2.0,
                merchant = "M$i",
                transactionType = TransactionType.PURCHASE,
                date = monthStart + i * 86400L
            )
        }
        val expectedMonthSpent = expenses.sumOf { it.effectiveAmount }
        val processedData = processedDataFrom(expectedMonthSpent, expenses)

        val result = computeUseCase.compute(processedData)
        val periodSummary = result.allWidgets.filterIsInstance<com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget.PeriodSummary>().single()
        assertEquals(expectedMonthSpent, periodSummary.monthSpent, 0.001)
    }

    private fun processedDataFrom(
        monthSpent: Double,
        expenses: List<Expense>
    ): com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData {
        val data = com.yourname.expensetracker.domain.usecase.dashboard.DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 0,
            weather = com.yourname.expensetracker.data.repository.FinancialWeather(
                state = com.yourname.expensetracker.data.repository.WeatherState.UNKNOWN,
                headline = "",
                summary = "",
                icon = "",
                riskLevel = 0,
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0
            ),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = com.yourname.expensetracker.data.repository.SpendingSummary(
            totalSpent = monthSpent,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData(
            data = data,
            summary = summary,
            categoryBreakdown = emptyList()
        )
    }
}
