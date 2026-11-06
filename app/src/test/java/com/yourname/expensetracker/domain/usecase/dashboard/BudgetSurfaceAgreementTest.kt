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
import com.yourname.expensetracker.domain.forecasting.ConfidenceLevel
import com.yourname.expensetracker.domain.forecasting.MonteCarloResult
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.SimulationConfidence
import com.yourname.expensetracker.domain.forecasting.SimulationMetadata
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
import org.junit.Assert.assertSame
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

    /** Argument of every ComputeMoneyRadarUseCase.compute() call, in call order. */
    private val radarInputs = mutableListOf<RadarBudgetInput>()

    /**
     * When true the simulator returns a deterministic result carrying the limit it was given, so
     * the Home card exists; when false it returns null (no card), the default for the rest of the file.
     */
    private var seedCardResult = false

    private class Surfaces(
        val card: DashboardWidget.MonteCarloForecast?,
        val radarInputs: List<RadarBudgetInput>,
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

    /**
     * P-05-5 single source: the Money Radar's budget input is `Simulated(card.result)` where
     * `card.result` is the very object the Home Monte Carlo card shows (identity, not equality), so
     * the Radar budget limit is the card's budgetAmount, rollover included, and there is exactly
     * one simulation per compute.
     */
    @Test
    fun `P-05-5 the Radar budget risk is derived from the same result object as the Home card`() = runTest {
        val rows = listOf(
            "plain 1000" to status(1_000.0, 300.0, date(2026, 6, 10), date(2026, 7, 10), 300.0),
            "rollover surplus 1000 plus 100" to
                status(1_000.0, 300.0, date(2026, 6, 10), date(2026, 7, 10), 300.0, effectiveLimit = 1_100.0)
        )

        rows.forEach { (name, budgetStatus) ->
            val s = surfaces(budgetStatus.toBudgetStatusSnapshot(), seedCard = true)

            val card = requireNotNull(s.card) { "$name: Home Monte Carlo card is shown" }
            val radarInput = s.radarInputs.single()
            assertTrue("$name: radar input is Simulated", radarInput is RadarBudgetInput.Simulated)
            assertSame(
                "$name: radar reads the card's result object",
                card.result,
                (radarInput as RadarBudgetInput.Simulated).result
            )
            assertEquals("$name: card limit", budgetStatus.effectiveLimit, card.result.budgetAmount!!, 0.0)
            assertEquals("$name: radar limit equals card budgetAmount", card.result.budgetAmount, radarInput.result.budgetAmount)
            assertEquals("$name: simulator limit", listOf(budgetStatus.effectiveLimit), simulatedBudgetAmounts.toList())
            assertEquals("$name: one simulation per compute", 1, s.monteCarloCalls)
        }
    }

    @Test
    fun `P-05-5 a weekly or yearly budget gives the Radar None and the card no limit`() = runTest {
        val rows = listOf(
            "weekly" to status(100.0, 50.0, at(15, 0), at(22, 0), 50.0),
            "yearly" to status(12_000.0, 9_300.0, date(2026, 1, 1), date(2027, 1, 1), 9_300.0)
        )

        rows.forEach { (name, budgetStatus) ->
            val s = surfaces(budgetStatus.toBudgetStatusSnapshot(), seedCard = true)

            assertNull("$name: no limit reaches the simulator", s.monteCarloBudget)
            assertEquals("$name: radar sees no comparable budget", listOf<RadarBudgetInput>(RadarBudgetInput.None), s.radarInputs)
            assertTrue("$name: card says not comparable", requireNotNull(s.card).budgetNotComparable)
        }
    }

    @Test
    fun `P-05-5 no overall budget gives the Radar None`() = runTest {
        assertEquals(listOf<RadarBudgetInput>(RadarBudgetInput.None), radarInputsFor(emptyList(), seedCard = true))
    }

    @Test
    fun `P-05-5 a month-scale budget without a card result gives the Radar Unknown not None`() = runTest {
        // Simulator returns null: the card is absent while a comparable month-scale budget exists.
        val monthScale = status(1_000.0, 300.0, date(2026, 6, 10), date(2026, 7, 10), 300.0)

        val s = surfaces(monthScale.toBudgetStatusSnapshot(), seedCard = false)

        assertNull(s.card)
        assertEquals(listOf<RadarBudgetInput>(RadarBudgetInput.Unknown), s.radarInputs)
    }

    @Test
    fun `P-05-5 a month-scale budget with unavailable effective spend gives the Radar Unknown`() = runTest {
        val base = snapshot(1_000.0, 1_100.0, date(2026, 6, 10), date(2026, 7, 10), gross = 1_100.0)

        val s = surfaces(base.copy(effectiveSpend = null, effectiveHealthStatus = null), seedCard = true)

        assertEquals(listOf<RadarBudgetInput>(RadarBudgetInput.Unknown), s.radarInputs)
    }

    @Test
    fun `P-05-5 a month-scale budget with a zero limit gives the Radar None as no positive limit`() = runTest {
        val zeroLimit = snapshot(0.0, 0.0, date(2026, 6, 10), date(2026, 7, 10), gross = 0.0)
            .copy(percentUsed = 0.0)

        assertEquals(
            listOf<RadarBudgetInput>(RadarBudgetInput.None),
            radarInputsFor(listOf(zeroLimit), seedCard = true)
        )
    }

    @Test
    fun `P-05-5 a failed budget read gives the Radar Unreadable even with a seeded card`() = runTest {
        assertEquals(
            listOf<RadarBudgetInput>(RadarBudgetInput.Unreadable),
            radarInputsFor(emptyList(), seedCard = true, budgetReadFailed = true)
        )
    }

    /**
     * Radar inputs only: with no budget and no income the Home headline and Runway widgets are
     * legitimately absent, so [surfacesFor] (which needs them) does not apply.
     */
    private suspend fun radarInputsFor(
        snapshots: List<BudgetStatusSnapshot>,
        seedCard: Boolean,
        budgetReadFailed: Boolean = false
    ): List<RadarBudgetInput> {
        fixedNowMs = at(15, 12)
        simulatedBudgetAmounts.clear()
        radarInputs.clear()
        seedCardResult = seedCard
        val purchase = DashboardExpense(
            id = 1L, amount = 300.0, effectiveAmount = 300.0, currency = "EUR", merchant = "M1",
            transactionType = DashboardTransactionType.PURCHASE, date = at(2, 12),
            categoryId = 7L, isNotMine = false, isManualEntry = false
        )
        computeUseCase.compute(data(listOf(purchase), snapshots, budgetReadFailed))
        return radarInputs.toList()
    }

    // ---------------------------------------------------------------- harness

    private suspend fun surfaces(snapshot: BudgetStatusSnapshot, seedCard: Boolean = false): Surfaces =
        surfacesFor(listOf(snapshot), seedCard)

    private suspend fun surfacesFor(
        snapshots: List<BudgetStatusSnapshot>,
        seedCard: Boolean = false,
        budgetReadFailed: Boolean = false
    ): Surfaces {
        fixedNowMs = at(15, 12)
        simulatedBudgetAmounts.clear()
        radarInputs.clear()
        seedCardResult = seedCard
        val purchase = DashboardExpense(
            id = 1L, amount = 300.0, effectiveAmount = 300.0, currency = "EUR", merchant = "M1",
            transactionType = DashboardTransactionType.PURCHASE, date = at(2, 12),
            categoryId = 7L, isNotMine = false, isManualEntry = false
        )
        val result = computeUseCase.compute(data(listOf(purchase), snapshots, budgetReadFailed))

        // Weather the way the weather repository builds it: synthesize, then narrate.
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = emptyList(),
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            savingsGoals = emptyList(),
            budgetStatuses = snapshots,
            spendingPace = pace(),
            displayCurrency = "EUR"
        )
        val narrative = narrativeGenerator.generate(forecast, snapshots, "EUR")

        return Surfaces(
            card = result.allWidgets.filterIsInstance<DashboardWidget.MonteCarloForecast>().singleOrNull(),
            radarInputs = radarInputs.toList(),
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

    /** Deterministic stand-in for the simulator result: carries the arguments it was given. */
    private fun seededCardResult(spent: Double, known: Double, limit: Double?) = MonteCarloResult(
        percentile10 = spent + known - 50.0,
        percentile25 = spent + known - 25.0,
        percentile50 = spent + known,
        percentile75 = spent + known + 25.0,
        percentile90 = spent + known + 50.0,
        probabilityUnderBudget = limit?.let { if (spent + known > it) 0.2 else 0.9 },
        budgetAmount = limit,
        spentToDate = spent,
        knownUpcoming = known,
        confidence = SimulationConfidence(0.7, ConfidenceLevel.HIGH, "seeded"),
        metadata = SimulationMetadata(
            qualifyingWeeks = 10, totalWeeksExamined = 12, iterations = 1000,
            logNormalMu = 0.0, logNormalSigma = 1.0, daysRemaining = 15, computedAt = fixedNowMs
        ),
        displayCurrency = "EUR"
    )

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
    ): BudgetStatusSnapshot = status(limit, effective, start, end, gross).toBudgetStatusSnapshot()

    /** [effectiveLimit] is the limit incl. rollover; it defaults to [limit]. */
    private fun status(
        limit: Double,
        effective: Double,
        start: Long,
        end: Long,
        gross: Double,
        effectiveLimit: Double = limit
    ): BudgetStatus = BudgetStatus(
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
        effectiveLimit = effectiveLimit,
        adjustedSpendBreakdown = AdjustedSpendBreakdown(
            personalSpend = effective,
            sharedSpend = 0.0,
            reimbursedAmount = 0.0,
            netSharedLiability = 0.0,
            effectiveSpend = effective,
            pendingReimbursements = 0.0
        )
    )

    private fun data(
        expenses: List<DashboardExpense>,
        budgets: List<BudgetStatusSnapshot>,
        budgetReadFailed: Boolean = false
    ): ProcessedDashboardData {
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
            goals = emptyList(),
            budgetReadFailed = budgetReadFailed
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
            val limit = arg<Double?>(2)
            simulatedBudgetAmounts.add(limit)
            if (seedCardResult) seededCardResult(arg(0), arg(1), limit) else null
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
        coEvery { computeMoneyRadarUseCase.compute(any()) } answers {
            radarInputs.add(firstArg())
            MoneyRadarData(
                urgencyScore = 0,
                urgencyLevel = UrgencyLevel.GREEN,
                dueBills = emptyList(),
                anomalyAlerts = emptyList(),
                budgetRisk = null,
                topReasons = emptyList(),
                primaryCta = null
            )
        }
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
