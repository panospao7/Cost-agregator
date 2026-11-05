package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthCalculator
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * F5 with the real use case and the real [CurrencyConverter] (empty rate store): a USD row with
 * no stored rate is genuinely unconvertible. History rows outside the current month make the
 * overall result partial but must not hide a true 0.00 for Today, Week and Month.
 */
class ComputeDashboardWidgetsPeriodSummaryUnratedTest {

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = ts(2026, 4, 20)
    }

    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    private fun ts(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun setup() {
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50, savingsRateScore = 50, runwayScore = 50,
                budgetAdherenceScore = 50, billReliabilityScore = 50,
                factorContributions = emptyList(), trend = HealthTrend.STABLE, recommendation = null
            )
        )
        val computeMoneyRadarUseCase = mockk<ComputeMoneyRadarUseCase>(relaxed = true)
        coEvery { computeMoneyRadarUseCase.compute(any()) } returns MoneyRadarData(
            urgencyScore = 0, urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(), anomalyAlerts = emptyList(),
            budgetRisk = null, topReasons = emptyList(), primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
            overallRiskLevel = StressRiskLevel.LOW, earliestCrunchDate = null, recommendations = emptyList()
        )

        // No stored rates at all: any non-EUR expense has no conversion path.
        val exchangeRateStore = mockk<ExchangeRateStore>(relaxed = true)
        coEvery { exchangeRateStore.getRate(any(), any()) } returns null
        coEvery { exchangeRateStore.getRateAsOf(any(), any(), any()) } returns null
        coEvery { exchangeRateStore.getLatestRateForPair(any(), any()) } returns null
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { forecastInputAssembler.assembleNormalized(any()) } answers {
            val input = arg<NormalizedForecastInput>(0)
            ForecastInputAssembler.ForecastInput(
                pastSumDaily = input.pastSumDaily,
                recurringPatterns = input.recurringPatterns,
                plannedExpenses = input.plannedExpenses,
                savingsGoals = input.savingsGoals,
                budgetStatuses = input.budgetStatuses,
                spendingPace = input.spendingPace,
                displayCurrency = input.homeCurrency.code,
                dataQuality = input.dataQuality
            )
        }

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = mockk<InsightsEngine>(relaxed = true),
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true),
            timeProvider = timeProvider,
            healthCalculator = mockk<FinancialHealthCalculator>(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true),
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true),
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `F5 only row is last month's unrated USD so the result is partial but Today Week and Month are available zero`() = runTest {
        val (result, period) = compute(listOf(expense(25.0, ts(2026, 3, 10), "USD")))

        assertTrue("an unrated history row makes the overall result partial", result.isPartial)
        assertFalse(period.isTodayUnavailable)
        assertFalse(period.isWeekUnavailable)
        assertFalse(period.isMonthUnavailable)
        assertFalse("the whole widget must not be unavailable", period.isUnavailable)
        assertEquals(0.0, period.todaySpent, 0.001)
        assertEquals(0.0, period.weekSpent, 0.001)
        assertEquals(0.0, period.monthSpent, 0.001)
    }

    @Test
    fun `F5 control unrated USD inside this month makes the month unavailable`() = runTest {
        val (_, period) = compute(listOf(expense(25.0, ts(2026, 4, 3), "USD")))

        assertTrue(period.isMonthUnavailable)
        assertTrue("the whole-widget flag follows the month aggregate", period.isUnavailable)
    }

    @Test
    fun `F5 unrated USD on the 3rd with today the 20th leaves Today available and Month unavailable`() = runTest {
        val (_, period) = compute(listOf(expense(25.0, ts(2026, 4, 3), "USD")))

        assertFalse("nothing was bought today, Today is a true 0.00", period.isTodayUnavailable)
        assertTrue(period.isMonthUnavailable)
        assertEquals(0.0, period.todaySpent, 0.001)
    }

    @Test
    fun `F5 control rated EUR purchase today is available and counted in every period`() = runTest {
        val (result, period) = compute(listOf(expense(25.0, ts(2026, 4, 20) - 3_600_000L, "EUR")))

        assertFalse(result.isPartial)
        assertFalse(period.isTodayUnavailable)
        assertFalse(period.isMonthUnavailable)
        assertEquals(25.0, period.todaySpent, 0.001)
        assertEquals(25.0, period.monthSpent, 0.001)
    }

    private suspend fun compute(
        expenses: List<DashboardExpense>
    ): Pair<CompiledDashboardData, DashboardWidget.PeriodSummary> {
        val result = computeUseCase.compute(createProcessedData(expenses))
        return result to result.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
    }

    private fun expense(amount: Double, date: Long, currency: String): DashboardExpense = DashboardExpense(
        id = date + amount.toLong(),
        amount = amount,
        effectiveAmount = amount,
        currency = currency,
        merchant = "Test",
        transactionType = DashboardTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        isManualEntry = false,
        spendingExclusionReason = null
    )

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
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }
}
