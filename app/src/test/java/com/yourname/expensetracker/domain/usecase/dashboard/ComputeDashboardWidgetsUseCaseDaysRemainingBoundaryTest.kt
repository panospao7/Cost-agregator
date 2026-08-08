package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Direct boundary tests for [ComputeDashboardWidgetsUseCase] SafeToSpend
 * `daysRemaining` driven through the real compute path with fixed timestamps.
 *
 * These cases pin exact widget output for month-end boundaries:
 * - Feb 28 2024 (leap year, day 28 of 29) -> daysRemaining = 1
 * - Feb 29 2024 (leap year, last day)     -> daysRemaining = 0
 * - Mar 31 2024 (31-day month, last day)  -> daysRemaining = 0
 */
class ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    @Before
    fun setup() {
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 100f,
            paceStatus = PaceStatus.NO_BASELINE,
            displayCurrency = "EUR"
        )
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
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
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendation() } returns null
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
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        computeUseCase = ComputeDashboardWidgetsUseCase(
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = healthCalculator,
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = mockk<com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler>(relaxed = true),
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository
        )
    }

    @Test
    fun `Feb 28 2024 SafeToSpend daysRemaining is exactly 1`() = runTest {
        fixedNowMs = toEpochMs(2024, 2, 28, 12, 0)

        val result = computeUseCase.compute(createProcessedData(emptyList()))
        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()

        assertEquals("Feb 28 2024 (leap) must have 1 day remaining", 1, safeToSpend.daysRemaining)
    }

    @Test
    fun `Feb 29 2024 SafeToSpend daysRemaining is exactly 0`() = runTest {
        fixedNowMs = toEpochMs(2024, 2, 29, 12, 0)

        val result = computeUseCase.compute(createProcessedData(emptyList()))
        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()

        assertEquals("Feb 29 2024 (leap last day) must have 0 days remaining", 0, safeToSpend.daysRemaining)
    }

    @Test
    fun `Mar 31 2024 SafeToSpend daysRemaining is exactly 0`() = runTest {
        fixedNowMs = toEpochMs(2024, 3, 31, 12, 0)

        val result = computeUseCase.compute(createProcessedData(emptyList()))
        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()

        assertEquals("Mar 31 2024 (31-day month last day) must have 0 days remaining", 0, safeToSpend.daysRemaining)
    }

    private fun createProcessedData(expenses: List<DashboardExpense>): ProcessedDashboardData {
        val data = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 0,
            weather = FinancialWeather(
                state = WeatherState.UNKNOWN,
                headline = UiText.DynamicString(""),
                summary = UiText.DynamicString(""),
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
        val summary = SpendingSummary(
            totalSpent = 0.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 0
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
