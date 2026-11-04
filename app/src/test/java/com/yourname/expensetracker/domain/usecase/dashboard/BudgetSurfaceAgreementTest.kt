package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.AdjustedSpendBreakdown
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthScoreOutcome
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.model.dashboard.toBudgetStatusSnapshot
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * fix-1.0.4 (FL-06 family), plan section 3 truth table: the Home headline, Runway remainder and
 * Financial Weather pool read ONE shared budget figure, whatever the budget period; Monte Carlo and
 * Block Party only see month-scale periods.
 *
 * Real [SynthesisEngine], [NarrativeGenerator] and [ComputeDashboardWidgetsUseCase]; only the data
 * layer (budget statuses, converter, simulator, peripheral use cases) is mocked. The weather is
 * produced the way the weather repository does: synthesize the forecast, then narrate it.
 *
 * "Now" is 2026-06-15 12:00 in the JVM default zone (30-day June). Warning 75 percent, critical 90
 * percent, no obligations, no goals.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BudgetSurfaceAgreementTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var currencyConverter: CurrencyConverter
    private lateinit var synthesisEngine: SynthesisEngine
    private val narrativeGenerator = NarrativeGenerator()

    /** Budget argument of every Monte Carlo simulate() call, in call order. */
    private val simulatedBudgetAmounts = mutableListOf<Double?>()

    private class Surfaces(
        val headline: DashboardWidget.SafeToSpend,
        val runway: DashboardWidget.FinancialRunway,
        val weatherAvailable: Double,
        val weatherRisk: RiskLevel,
        val weatherState: WeatherState,
        val monteCarloBudget: Double?,
        val monteCarloCalls: Int,
        val blockPartyShown: Boolean,
        val weatherSummary: UiText,
        val homeBudgetSummary: UiText?,
        val hasBudgetHealthWidget: Boolean
    )

    // ---------------------------------------------------------------- truth table rows

    @Test
    fun `yearly 12000 limit with effective 9300 agrees on 2700 and is LOW with clear skies`() = runTest {
        val s = surfaces(
            snapshot(12_000.0, effective = 9_300.0, start = date(2026, 1, 1), end = date(2027, 1, 1), gross = 9_300.0)
        )

        assertEquals(2_700.0, s.headline.amount!!, 1e-6)
        assertEquals(2_700.0, s.runway.discretionaryRemaining, 1e-6)
        assertEquals(2_700.0, s.weatherAvailable, 1e-6)
        assertEquals(RiskLevel.LOW, s.weatherRisk)
        assertEquals(WeatherState.CLEAR_SKIES, s.weatherState)
        assertEquals(1, s.monteCarloCalls)
        assertNull("a yearly limit is not a calendar-month total", s.monteCarloBudget)
        assertFalse("yearly period hides Block Party", s.blockPartyShown)
    }

    @Test
    fun `weekly 100 limit with effective 150 agrees on 0 and is CRITICAL and STORMY`() = runTest {
        val s = surfaces(
            snapshot(100.0, effective = 150.0, start = at(15, 0), end = at(22, 0), gross = 150.0)
        )

        assertFalse(s.headline.isUnavailable)
        assertEquals(0.0, s.headline.amount!!, 0.0)
        assertFalse(s.runway.isUnavailable)
        assertEquals(0.0, s.runway.discretionaryRemaining, 0.0)
        assertEquals(DashboardWidget.RunwayStatus.CRITICAL, s.runway.status)
        assertEquals(0.0, s.weatherAvailable, 0.0)
        assertEquals(RiskLevel.CRITICAL, s.weatherRisk)
        assertEquals(WeatherState.STORMY, s.weatherState)
        assertEquals(1, s.monteCarloCalls)
        assertNull("a weekly limit is not a calendar-month total", s.monteCarloBudget)
        assertFalse("weekly period hides Block Party", s.blockPartyShown)
    }

    @Test
    fun `rolling month-length 1000 limit with effective 950 agrees on 50 and is CRITICAL and STORMY`() = runTest {
        // 2026-06-10 to 2026-07-10: 30 days, month-scale but not calendar-aligned.
        val s = surfaces(
            snapshot(1_000.0, effective = 950.0, start = date(2026, 6, 10), end = date(2026, 7, 10), gross = 950.0)
        )

        assertEquals(50.0, s.headline.amount!!, 1e-6)
        assertEquals(50.0, s.runway.discretionaryRemaining, 1e-6)
        assertEquals(50.0, s.weatherAvailable, 1e-6)
        assertEquals("95 percent is over the 90 percent critical line", RiskLevel.CRITICAL, s.weatherRisk)
        assertEquals(WeatherState.STORMY, s.weatherState)
        assertEquals(1, s.monteCarloCalls)
        assertEquals(1_000.0, s.monteCarloBudget!!, 0.0)
        assertTrue("a month-scale rolling period keeps Block Party", s.blockPartyShown)
    }

    @Test
    fun `headline amount runway remainder and weather pool are equal for every row`() = runTest {
        val rows = listOf(
            snapshot(12_000.0, 9_300.0, date(2026, 1, 1), date(2027, 1, 1), gross = 9_300.0) to 2_700.0,
            snapshot(100.0, 150.0, at(15, 0), at(22, 0), gross = 150.0) to 0.0,
            snapshot(1_000.0, 950.0, date(2026, 6, 10), date(2026, 7, 10), gross = 950.0) to 50.0
        )

        rows.forEach { (snapshot, expected) ->
            val s = surfaces(snapshot)
            assertEquals("headline ${snapshot.budgetAmount}", expected, s.headline.amount!!, 1e-6)
            assertEquals("runway ${snapshot.budgetAmount}", expected, s.runway.discretionaryRemaining, 1e-6)
            assertEquals("weather ${snapshot.budgetAmount}", expected, s.weatherAvailable, 1e-6)
        }
    }

    /**
     * Unavailable rows: limit 1,000, gross 1,100, no usable effective figure. Every surface says
     * unavailable; none shows a zero pool or a green "on track".
     */
    @Test
    fun `unavailable effective spend gives an unavailable headline runway weather and Home summary`() = runTest {
        val base = snapshot(
            1_000.0, effective = 1_100.0, start = date(2026, 6, 10), end = date(2026, 7, 10), gross = 1_100.0
        )
        val rows = mapOf(
            "effective spend null" to base.copy(effectiveSpend = null, effectiveHealthStatus = null),
            "percent unknown" to base.copy(
                percentKnown = false,
                effectiveHealthStatus = BudgetHealthStatus.UNKNOWN
            )
        )

        rows.forEach { (name, snapshot) ->
            val s = surfaces(snapshot)
            assertTrue("$name: headline unavailable", s.headline.isUnavailable)
            assertNull("$name: headline amount", s.headline.amount)
            assertTrue("$name: runway unavailable", s.runway.isUnavailable)
            assertEquals("$name: weather", WeatherState.UNKNOWN, s.weatherState)
            assertEquals(
                "$name: weather summary",
                UiText.fromKey(com.yourname.expensetracker.domain.text.DomainTextKeys.WEATHER_SUMMARY_UNAVAILABLE),
                s.weatherSummary
            )
            assertTrue("$name: budget widget still shown", s.hasBudgetHealthWidget)
            assertNull("$name: Home budget summary is never green", s.homeBudgetSummary)
        }
    }

    // ---------------------------------------------------------------- failing boundaries

    /** Boundary: effective spend one cent under the limit still leaves a positive remainder everywhere. */
    @Test
    fun `boundary effective spend one cent under the limit leaves 0_01 on every surface`() = runTest {
        val s = surfaces(
            snapshot(1_000.0, effective = 999.99, start = date(2026, 6, 10), end = date(2026, 7, 10), gross = 999.99)
        )

        assertEquals(0.01, s.headline.amount!!, 1e-6)
        assertEquals(0.01, s.runway.discretionaryRemaining, 1e-6)
        assertEquals(0.01, s.weatherAvailable, 1e-6)
    }

    /** Boundary: a 27-day and a 32-day period are not month-scale, a 28-day period is. */
    @Test
    fun `boundary Monte Carlo limit only for 28 to 31 day periods`() = runTest {
        val outside = listOf(27L, 32L).map { days ->
            surfaces(
                snapshot(1_000.0, 300.0, date(2026, 6, 1), date(2026, 6, 1).plusDays(days), gross = 300.0)
            )
        }
        val inside = surfaces(
            snapshot(1_000.0, 300.0, date(2026, 6, 1), date(2026, 6, 1).plusDays(28), gross = 300.0)
        )

        outside.forEach {
            assertNull(it.monteCarloBudget)
            assertFalse(it.blockPartyShown)
        }
        assertEquals(1_000.0, inside.monteCarloBudget!!, 0.0)
        assertTrue(inside.blockPartyShown)
    }

    // ---------------------------------------------------------------- harness

    private suspend fun surfaces(snapshot: BudgetStatusSnapshot): Surfaces {
        fixedNowMs = at(15, 12)
        simulatedBudgetAmounts.clear()
        val purchase = DashboardExpense(
            id = 1L, amount = 300.0, effectiveAmount = 300.0, currency = "EUR", merchant = "M1",
            transactionType = DashboardTransactionType.PURCHASE, date = at(2, 12),
            categoryId = 7L, isNotMine = false, isManualEntry = false
        )
        val result = computeUseCase.compute(data(listOf(purchase), listOf(snapshot)))

        // Weather the way the weather repository builds it: synthesize, then narrate.
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = listOf(snapshot),
            spendingPace = pace(),
            displayCurrency = "EUR"
        )
        val narrative = narrativeGenerator.generate(forecast, listOf(snapshot), "EUR")

        return Surfaces(
            headline = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single(),
            runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single(),
            weatherAvailable = forecast.components.discretionaryBudget,
            weatherRisk = forecast.components.riskLevel,
            weatherState = narrative.state,
            monteCarloBudget = simulatedBudgetAmounts.lastOrNull(),
            monteCarloCalls = simulatedBudgetAmounts.size,
            blockPartyShown = result.allWidgets.any { it is DashboardWidget.BudgetBlockParty },
            weatherSummary = narrative.summary,
            homeBudgetSummary = result.allWidgets.filterIsInstance<DashboardWidget.BudgetHealthWidget>()
                .singleOrNull()?.summary,
            hasBudgetHealthWidget = result.allWidgets.any { it is DashboardWidget.BudgetHealthWidget }
        )
    }

    private fun pace() = SpendingPace(
        currentMonthSpent = 300.0,
        daysElapsed = 15,
        daysInMonth = 30,
        projectedTotal = 600.0,
        previousMonthTotal = null,
        averageMonthlyTotal = null,
        pacePercentage = 100.0f,
        paceStatus = PaceStatus.ON_PACE,
        displayCurrency = "EUR"
    )

    /** Snapshot derived from a real [BudgetStatus] with a shared-expense breakdown, as production maps it. */
    private fun snapshot(
        limit: Double,
        effective: Double,
        start: Long,
        end: Long,
        gross: Double
    ): BudgetStatusSnapshot = BudgetStatus(
        budget = Budget(
            id = 1L,
            categoryId = null,
            amount = limit,
            period = BudgetPeriod.MONTHLY,
            startDate = start,
            notifyAtWarning = 0.75f,
            notifyAtCritical = 0.9f,
            currency = "EUR",
            currencyAssumption = "USER_CONFIRMED"
        ),
        category = null,
        spentAmount = gross,
        remainingAmount = (limit - gross).coerceAtLeast(0.0),
        percentUsed = (gross / limit).toFloat(),
        healthStatus = if (gross >= limit) BudgetHealthStatus.EXCEEDED else BudgetHealthStatus.WARNING,
        periodStart = start,
        periodEnd = end,
        effectiveLimit = limit,
        adjustedSpendBreakdown = AdjustedSpendBreakdown(
            personalSpend = effective,
            sharedSpend = 0.0,
            reimbursedAmount = 0.0,
            netSharedLiability = 0.0,
            effectiveSpend = effective,
            pendingReimbursements = 0.0
        )
    ).toBudgetStatusSnapshot()

    private fun data(expenses: List<DashboardExpense>, budgets: List<BudgetStatusSnapshot>): ProcessedDashboardData {
        val dashboardData = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = budgets,
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
        return ProcessedDashboardData(data = dashboardData, summary = summary, categoryBreakdown = emptyList())
    }

    /** Epoch millis of June [day] 2026 at [hour]:00 in the JVM default zone. */
    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 6, day, hour, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun Long.plusDays(days: Long): Long =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).plusDays(days).toInstant().toEpochMilli()

    @Before
    fun setup() {
        currencyConverter = mockk(relaxed = true)
        synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } answers {
            simulatedBudgetAmounts.add(arg<Double?>(2))
            null
        }
        val healthScoreV2 = mockk<FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns HealthScoreOutcome.Available(
            FinancialHealthResult(
                overallScore = 50,
                savingsRateScore = 50,
                runwayScore = 50,
                budgetAdherenceScore = 50,
                billReliabilityScore = 50,
                factorContributions = emptyList(),
                trend = HealthTrend.STABLE,
                recommendation = null
            )
        )
        val lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true)
        coEvery { lifestyleSavingsPromptUseCase.evaluateAndPrompt() } returns null
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
        coEvery { monthlySavingsSweepUseCase.computeSweepRecommendationResult() } returns
            SavingsSweepComputationResult.NoRecommendation()
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
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
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
                confirmedOccurrences = emptyList(),
                displayCurrency = input.homeCurrency.code,
                dataQuality = input.dataQuality
            )
        }

        computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = mockk(relaxed = true),
            synthesisEngine = synthesisEngine,
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = currencyConverter,
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }
}
