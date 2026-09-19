package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.FinancialStressForecastEngine
import com.yourname.expensetracker.domain.forecasting.ForecastDataQuality
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MonteCarloSpendingSimulator
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
import com.yourname.expensetracker.domain.forecasting.StressForecastResult
import com.yourname.expensetracker.domain.forecasting.StressHorizon
import com.yourname.expensetracker.domain.forecasting.StressRiskLevel
import com.yourname.expensetracker.domain.health.FinancialHealthResult
import com.yourname.expensetracker.domain.health.FinancialHealthScoreV2
import com.yourname.expensetracker.domain.health.HealthTrend
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * P5-010 (RP-07): runway edge cases must not invent a number.
 *
 * Proven through the real compute path with fixed timestamps:
 * - Day one with no purchases and a positive budget -> NO_BURN, null days,
 *   a bounded cap (<= 30), never CRITICAL.
 * - Positive burn with a 13.99-day quotient -> 14 via roundToInt; unchanged
 *   14/7 thresholds classify it HEALTHY, 13 stays CAUTION.
 * - Committed obligations exceeding the remainder -> days 0 + CRITICAL with
 *   the committed amount still visible and LIKELY amounts never deducted.
 * - No budget and no income -> no fabricated runway widget at all.
 * - Period end reached with no burn -> cap 0 (NO_BURN, not CRITICAL).
 */
class FinancialRunwayNoBurnTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase
    private lateinit var forecastInputAssembler: ForecastInputAssembler

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

        // P5-010: pass planned expenses through the assembler boundary so
        // SynthesisEngine can classify committed (MUST) vs likely (LIKELY).
        forecastInputAssembler = mockk(relaxed = true)
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
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk<CurrencyConverter>(relaxed = true)),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            healthCalculator = healthCalculator,
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            // RP-06 6a: real calculator + the test's fake TimeProvider so pace math is real.
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `day one no purchases positive budget is NO_BURN with bounded cap`() = runTest {
        fixedNowMs = toEpochMs(2024, 1, 1, 12, 0) // Jan 1 2024, day 1 of 31

        val result = computeUseCase.compute(
            createProcessedData(
                expenses = emptyList(),
                budgetStatuses = listOf(overallBudget(1000.0))
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(
            "NO_BURN must never fabricate a day count",
            DashboardWidget.RunwayStatus.NO_BURN, runway.status
        )
        assertNull("daysRemaining must be null for NO_BURN", runway.daysRemaining)
        assertNotNull("zeroBurnHorizonDays must be non-null for NO_BURN", runway.zeroBurnHorizonDays)
        val cap = runway.zeroBurnHorizonDays!!
        assertTrue("cap must be positive on day one", cap > 0)
        assertTrue("cap must never exceed 30", cap <= 30)
        assertEquals("cap = min(daysUntilPeriodEnd, 30)", 30, cap)
    }

    @Test
    fun `positive burn quotient 13 point 99 rounds to 14 and classifies HEALTHY`() = runTest {
        fixedNowMs = toEpochMs(2024, 7, 10, 12, 0) // Jul 10 2024, day 10 of 31

        // monthSpent 200 over 10 days -> burn 20; budget 479.8 -> remainder 279.8
        // quotient 13.99 -> roundToInt 14 -> HEALTHY (threshold >= 14, unchanged).
        val expenses = listOf(
            purchase(100.0, dayOffsetMs(1)),
            purchase(100.0, dayOffsetMs(2))
        )
        val result = computeUseCase.compute(
            createProcessedData(
                expenses = expenses,
                budgetStatuses = listOf(overallBudget(479.8))
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(
            "13.99 days must round to 14, not truncate to 13",
            14, runway.daysRemaining
        )
        assertEquals(
            "14 days classifies HEALTHY under unchanged thresholds",
            DashboardWidget.RunwayStatus.HEALTHY, runway.status
        )
        assertNull("no zero-burn cap when burn is positive", runway.zeroBurnHorizonDays)
    }

    @Test
    fun `positive burn quotient 13 point 4 rounds to 13 and classifies CAUTION`() = runTest {
        fixedNowMs = toEpochMs(2024, 7, 10, 12, 0)

        // burn 20, remainder 268.4 -> 13.42 -> 13 -> CAUTION (7..13, unchanged).
        val expenses = listOf(
            purchase(100.0, dayOffsetMs(1)),
            purchase(100.0, dayOffsetMs(2))
        )
        val result = computeUseCase.compute(
            createProcessedData(
                expenses = expenses,
                budgetStatuses = listOf(overallBudget(468.4))
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(13, runway.daysRemaining)
        assertEquals(DashboardWidget.RunwayStatus.CAUTION, runway.status)
    }

    @Test
    fun `committed exceeding remainder is CRITICAL with committed visible and likely not deducted`() = runTest {
        fixedNowMs = toEpochMs(2024, 7, 10, 12, 0)

        // remainingBeforeCommitments = 1000 - 100 = 900; committed MUST 5000
        // exceeds it -> effectiveRemaining 0 -> days 0 + CRITICAL. The LIKELY
        // 9999 must stay informational (shown, never deducted).
        val expenses = listOf(purchase(100.0, dayOffsetMs(2)))
        val planned = listOf(
            PlannedExpense(
                id = 0, description = "Rent", amount = 5000.0,
                date = fixedNowMs + 10 * 86_400_000L, categoryId = null,
                isRecurring = false, priority = PlannedExpensePriority.MUST
            ),
            PlannedExpense(
                id = 0, description = "Maybe trip", amount = 9999.0,
                date = fixedNowMs + 11 * 86_400_000L, categoryId = null,
                isRecurring = false, priority = PlannedExpensePriority.LIKELY
            )
        )
        val result = computeUseCase.compute(
            createProcessedData(
                expenses = expenses,
                budgetStatuses = listOf(overallBudget(1000.0)),
                plannedExpenses = planned
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(
            "exhausted remainder must be a non-null zero day count",
            0, runway.daysRemaining
        )
        assertEquals(DashboardWidget.RunwayStatus.CRITICAL, runway.status)
        assertNull("no zero-burn cap in CRITICAL", runway.zeroBurnHorizonDays)
        assertTrue(
            "committed amount must remain visible",
            runway.committedExpenses >= 5000.0
        )
        assertTrue(
            "likely amount must remain informational (present, not deducted into days)",
            runway.likelyExpenses > 0.0
        )
    }

    @Test
    fun `likely expenses are never deducted from a positive runway`() = runTest {
        fixedNowMs = toEpochMs(2024, 7, 10, 12, 0)

        // remainingBeforeCommitments = 10000 - 100 = 9900; committed MUST 5000
        // -> effectiveRemaining 4900, burn 10 -> 490 days. If LIKELY were
        // deducted the runway would collapse to 0/CRITICAL — it must not.
        val expenses = listOf(purchase(100.0, dayOffsetMs(2)))
        val planned = listOf(
            PlannedExpense(
                id = 0, description = "Rent", amount = 5000.0,
                date = fixedNowMs + 10 * 86_400_000L, categoryId = null,
                isRecurring = false, priority = PlannedExpensePriority.MUST
            ),
            PlannedExpense(
                id = 0, description = "Huge maybe", amount = 99999.0,
                date = fixedNowMs + 11 * 86_400_000L, categoryId = null,
                isRecurring = false, priority = PlannedExpensePriority.LIKELY
            )
        )
        val result = computeUseCase.compute(
            createProcessedData(
                expenses = expenses,
                budgetStatuses = listOf(overallBudget(10000.0)),
                plannedExpenses = planned
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(DashboardWidget.RunwayStatus.HEALTHY, runway.status)
        assertEquals(490, runway.daysRemaining)
        assertTrue("likely must stay informational", runway.likelyExpenses > 0.0)
    }

    @Test
    fun `no budget and no income produces no fabricated runway widget`() = runTest {
        fixedNowMs = toEpochMs(2024, 1, 15, 12, 0)

        val result = computeUseCase.compute(createProcessedData(expenses = emptyList()))

        // The assembly gate emits the runway widget only when a budget or a
        // positive remainder exists — with neither, no runway number may be
        // fabricated at all.
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().singleOrNull()
        assertNull(
            "no budget + no income must not emit a fabricated runway widget",
            runway
        )
    }

    @Test
    fun `period end reached with no burn gives cap zero`() = runTest {
        fixedNowMs = toEpochMs(2024, 1, 31, 12, 0) // Jan 31 2024, last day of 31

        val result = computeUseCase.compute(
            createProcessedData(
                expenses = emptyList(),
                budgetStatuses = listOf(overallBudget(1000.0))
            )
        )
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().single()

        assertEquals(DashboardWidget.RunwayStatus.NO_BURN, runway.status)
        assertNull(runway.daysRemaining)
        assertEquals(
            "daysUntilPeriodEnd == 0 must give cap 0 (UI shows 'No remaining period')",
            0, runway.zeroBurnHorizonDays
        )
    }

    // ── Harness helpers ──────────────────────────────────────────────────────

    private fun overallBudget(amount: Double): BudgetStatusSnapshot = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = amount,
        categoryName = null,
        spentAmount = 0.0,
        remainingAmount = amount,
        percentUsed = 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = fixedNowMs,
        periodEnd = fixedNowMs + 86_400_000L
    )

    private fun purchase(amount: Double, date: Long): DashboardExpense = DashboardExpense(
        id = date,
        amount = amount,
        effectiveAmount = amount,
        merchant = "Test",
        transactionType = DashboardTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        isManualEntry = false
    )

    private fun dayOffsetMs(daysFromMonthStart: Long): Long {
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(fixedNowMs)
        return monthStart + daysFromMonthStart * 86_400_000L
    }

    private fun createProcessedData(
        expenses: List<DashboardExpense>,
        budgetStatuses: List<BudgetStatusSnapshot> = emptyList(),
        plannedExpenses: List<PlannedExpense> = emptyList()
    ): ProcessedDashboardData {
        val data = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = budgetStatuses,
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
            plannedExpenses = plannedExpenses,
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

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
