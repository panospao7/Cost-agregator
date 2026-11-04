package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
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
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.DomainDayBudgetStatus
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.text.DashboardTextKeys
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.usecase.savings.SavingsSweepComputationResult
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fix-1.0.4 F1/F4/F5: dashboard widgets that read the overall budget snapshot.
 *
 * - F1 Runway remaining money is the snapshot limit minus the snapshot's EFFECTIVE spend (the Home
 *   hero's number), whatever the budget period; a positive limit without a finite effective spend
 *   makes Runway unavailable. Block Party reads the limit only for a month-scale period.
 * - F4 the Home budget summary counts exceeded budgets from effectiveHealthStatus, never the gross
 *   healthStatus.
 * - F5 Today/Week/Month stay a true, available 0.00 when only an unrated row exists in history.
 *
 * Real [SynthesisEngine] (only its converter is a mock). "Now" is 2026-06-15 12:00 in the JVM
 * default zone (30-day June); fixtures are built in that same zone.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComputeDashboardWidgetsUseCaseEffectiveBudgetTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var currencyConverter: CurrencyConverter

    // ---------------------------------------------------------------- F1 Runway remaining

    /** Old behaviour read limit - month spend = 12000 - 300 = 11700. */
    @Test
    fun `F1 yearly limit 12000 with effective 9300 gives runway remaining 2700 not 11700`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(12_000.0, effective = 9_300.0, start = date(2026, 1, 1), end = date(2027, 1, 1))

        val runway = runway(computeUseCase.compute(data(listOf(purchase(1L, 300.0, at(2, 12))), listOf(budget))))

        assertFalse(runway.isUnavailable)
        assertEquals(2_700.0, runway.discretionaryRemaining, 1e-6)
        assertEquals(12_000.0, runway.totalBudget, 1e-6)
        // Daily burn 300 / 15 = 20 per day; 2700 / 20 = 135 days.
        assertEquals(135, runway.daysRemaining)
    }

    /** Old behaviour read 100 - 350 = 0 and reported CRITICAL with 0 days. */
    @Test
    fun `F1 weekly limit 100 with effective 50 and month spend 350 gives remaining 50 not zero days`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(100.0, effective = 50.0, start = at(15, 0), end = at(22, 0))

        val runway = runway(computeUseCase.compute(data(listOf(purchase(1L, 350.0, at(2, 12))), listOf(budget))))

        assertFalse(runway.isUnavailable)
        assertEquals(50.0, runway.discretionaryRemaining, 1e-6)
        // Daily burn 350 / 15 = 23.33; 50 / 23.33 = 2.14 rounds to 2 days, not the old 0.
        assertEquals(2, runway.daysRemaining)
    }

    /** Control: a month-length budget with effective equal to month spend keeps limit - spend. */
    @Test
    fun `F1 control monthly limit 1000 with effective 300 gives remaining 700`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(1_000.0, effective = 300.0, start = at(1, 0), end = date(2026, 7, 1))

        val runway = runway(computeUseCase.compute(data(listOf(purchase(1L, 300.0, at(2, 12))), listOf(budget))))

        assertFalse(runway.isUnavailable)
        assertEquals(700.0, runway.discretionaryRemaining, 1e-6)
    }

    @Test
    fun `F1 positive limit with null effective spend makes runway unavailable`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(1_000.0, effective = null, start = at(1, 0), end = date(2026, 7, 1))

        val result = computeUseCase.compute(data(listOf(purchase(1L, 300.0, at(2, 12))), listOf(budget)))

        assertTrue(runway(result).isUnavailable)
        assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
    }

    @Test
    fun `F1 positive limit with NaN effective spend makes runway unavailable`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(1_000.0, effective = Double.NaN, start = at(1, 0), end = date(2026, 7, 1))

        val result = computeUseCase.compute(data(listOf(purchase(1L, 300.0, at(2, 12))), listOf(budget)))

        assertTrue(runway(result).isUnavailable)
    }

    // ---------------------------------------------------------------- F1 Block Party period gate

    @Test
    fun `F1 Block Party is hidden for 7 and 365 day budget periods`() = runTest {
        fixedNowMs = at(15, 12)
        listOf(7L, 365L).forEach { days ->
            val budget = budget(2_000.0, effective = 0.0, start = at(1, 0), end = date(2026, 6, 1).plusDays(days))
            val result = computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(budget)))

            assertTrue("$days-day period hides the card", blockDays(result).isEmpty())
            assertTrue(result.allWidgets.none { it is DashboardWidget.BudgetBlockParty })
        }
    }

    @Test
    fun `F1 Block Party is shown for 28 30 and 31 day budget periods`() = runTest {
        fixedNowMs = at(15, 12)
        listOf(28L, 30L, 31L).forEach { days ->
            val budget = budget(2_000.0, effective = 0.0, start = at(1, 0), end = date(2026, 6, 1).plusDays(days))
            val result = computeUseCase.compute(data(listOf(purchase(1L, 20.0, at(3, 12))), listOf(budget)))

            assertEquals("$days-day period shows the June grid", 30, blockDays(result).size)
        }
    }

    // ---------------------------------------------------------------- F4 Home summary

    @Test
    fun `F4 gross 120 effective 90 limit 100 is not counted as exceeded`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(
            100.0, effective = 90.0, start = at(1, 0), end = date(2026, 7, 1),
            gross = 120.0, grossHealth = BudgetHealthStatus.EXCEEDED, effectiveHealth = BudgetHealthStatus.WARNING
        )

        val summary = budgetSummary(computeUseCase.compute(data(emptyList(), listOf(budget))))

        assertEquals(UiText.fromKey(DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK), summary)
    }

    @Test
    fun `F4 effective 120 over limit 100 is counted as exceeded`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(
            100.0, effective = 120.0, start = at(1, 0), end = date(2026, 7, 1),
            gross = 120.0, grossHealth = BudgetHealthStatus.EXCEEDED, effectiveHealth = BudgetHealthStatus.EXCEEDED
        )

        val summary = budgetSummary(computeUseCase.compute(data(emptyList(), listOf(budget))))

        assertEquals(UiText.fromKey(DashboardTextKeys.WIDGET_BUDGET_EXCEEDED_FORMAT, 1), summary)
    }

    @Test
    fun `F4 null effectiveHealthStatus is not counted as exceeded even when gross health is exceeded`() = runTest {
        fixedNowMs = at(15, 12)
        val budget = budget(
            100.0, effective = null, start = at(1, 0), end = date(2026, 7, 1),
            gross = 120.0, grossHealth = BudgetHealthStatus.EXCEEDED, effectiveHealth = null
        )

        val summary = budgetSummary(computeUseCase.compute(data(emptyList(), listOf(budget))))

        assertEquals(UiText.fromKey(DashboardTextKeys.WIDGET_ALL_BUDGETS_ON_TRACK), summary)
    }

    // ---------------------------------------------------------------- F5 Today / Week / Month

    /**
     * No purchase this month and only last month's unrated USD 50: the history row makes the
     * overall data quality partial, but Today, Week and Month are a true, available 0.00.
     */
    @Test
    fun `F5 no purchases this month and last month unrated 50 gives available zero today week and month`() = runTest {
        fixedNowMs = at(15, 12)
        coEvery { currencyConverter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } answers {
            ConversionOutcome.Failed(
                firstArg(), "USD", "EUR", arg(3),
                ConversionFailureType.MISSING_RATE,
                "TEST_MISSING_RATE"
            )
        }
        val unrated = purchase(1L, 50.0, date(2026, 5, 20).plusHours(12)).copy(currency = "USD")

        val result = computeUseCase.compute(data(listOf(unrated), emptyList()))

        val input = (result.normalizedInput as DashboardNormalizedInputResult.Available).input
        assertEquals(
            "setup: the only history row has no rate, so overall quality is unavailable",
            com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE,
            input.dataQuality.conversionQuality
        )
        val period = result.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertEquals(0.0, period.todaySpent, 0.0)
        assertEquals(0.0, period.weekSpent, 0.0)
        assertEquals(0.0, period.monthSpent, 0.0)
        assertFalse(period.isUnavailable)
        assertFalse(period.isTodayUnavailable)
        assertFalse(period.isWeekUnavailable)
        assertFalse(period.isMonthUnavailable)
    }

    /** Control: an unrated row INSIDE this month does make the month unavailable. */
    @Test
    fun `F5 control unrated purchase this month makes the month unavailable`() = runTest {
        fixedNowMs = at(15, 12)
        coEvery { currencyConverter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } answers {
            ConversionOutcome.Failed(
                firstArg(), "USD", "EUR", arg(3),
                ConversionFailureType.MISSING_RATE,
                "TEST_MISSING_RATE"
            )
        }
        val unrated = purchase(1L, 50.0, at(3, 12)).copy(currency = "USD")

        val period = computeUseCase.compute(data(listOf(unrated), emptyList()))
            .allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()

        assertTrue(period.isMonthUnavailable)
        assertTrue(period.isUnavailable)
    }

    // ---------------------------------------------------------------- harness

    private fun runway(result: CompiledDashboardData): DashboardWidget.FinancialRunway =
        result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

    private fun blockDays(result: CompiledDashboardData): List<DomainDayBudgetStatus> =
        result.allWidgets.filterIsInstance<DashboardWidget.BudgetBlockParty>().singleOrNull()?.days ?: emptyList()

    private fun budgetSummary(result: CompiledDashboardData): UiText? =
        result.allWidgets.filterIsInstance<DashboardWidget.BudgetHealthWidget>().single().summary

    private fun budget(
        amount: Double,
        effective: Double?,
        start: Long,
        end: Long,
        gross: Double = 0.0,
        grossHealth: BudgetHealthStatus = BudgetHealthStatus.ON_TRACK,
        effectiveHealth: BudgetHealthStatus? = null
    ) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = gross,
        remainingAmount = amount - gross,
        percentUsed = 0.0,
        healthStatus = grossHealth,
        periodStart = start,
        periodEnd = end,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED",
        effectiveSpend = effective,
        effectiveHealthStatus = effectiveHealth
    )

    private fun purchase(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun data(expenses: List<Expense>, budgets: List<BudgetStatusSnapshot>): ProcessedDashboardData {
        val dashboardData = DashboardData(
            expenses = expenses.map { it.toDashboardExpense() },
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

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        transactionType = DashboardTransactionType.PURCHASE,
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        spendingExclusionReason = spendingExclusionReason
    )

    /** Epoch millis of June [day] 2026 at [hour]:00 in the JVM default zone. */
    private fun at(day: Int, hour: Int): Long =
        LocalDateTime.of(2026, 6, day, hour, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Midnight of a calendar date in the JVM default zone, as epoch millis. */
    private fun date(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun Long.plusDays(days: Long): Long =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).plusDays(days).toInstant().toEpochMilli()

    private fun Long.plusHours(hours: Long): Long =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).plusHours(hours).toInstant().toEpochMilli()

    @Before
    fun setup() {
        currencyConverter = mockk(relaxed = true)
        val insightsEngine = mockk<com.yourname.expensetracker.domain.analytics.InsightsEngine>(relaxed = true)
        val monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true)
        coEvery { monteCarloSimulator.simulate(any(), any(), any(), any(), any()) } returns null
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
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter),
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
