package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
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
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * FL-06 (fix-1.0.4 money-display): the SafeToSpend hero number must come from the
 * budget snapshot (`remainingAmount` / `periodEnd`) of a REAL budget period, never from
 * "limit minus calendar-month spend".
 *
 * Fixed clock: 2026-04-15 12:00 local. Independent oracles are written as literals.
 */
class ComputeDashboardWidgetsSafeToSpendBudgetPeriodTest {

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = ts(2026, 4, 15, 12)
    }

    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, 0, 0)
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
        coEvery { computeMoneyRadarUseCase.compute() } returns MoneyRadarData(
            urgencyScore = 0, urgencyLevel = UrgencyLevel.GREEN,
            dueBills = emptyList(), anomalyAlerts = emptyList(),
            budgetRisk = null, topReasons = emptyList(), primaryCta = null
        )
        val stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true)
        coEvery { stressForecastEngine.computeStressForecast() } returns StressForecastResult(
            horizons = listOf(StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)),
            overallRiskLevel = StressRiskLevel.LOW, earliestCrunchDate = null, recommendations = emptyList()
        )
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

    private fun budget(
        limit: Double,
        spent: Double,
        remaining: Double,
        periodStart: Long,
        periodEnd: Long
    ) = BudgetStatusSnapshot(
        budgetCategoryId = null,
        budgetAmount = limit,
        categoryName = null,
        spentAmount = spent,
        remainingAmount = remaining,
        percentUsed = if (limit > 0.0) spent / limit * 100.0 else 0.0,
        healthStatus = BudgetHealthStatus.ON_TRACK,
        periodStart = periodStart,
        periodEnd = periodEnd,
        currency = "EUR",
        currencyAssumption = "USER_CONFIRMED"
    )

    private val thisMonthEur300 get() = listOf(expense(300.0, ts(2026, 4, 2, 12)))

    @Test
    fun `FL-06 yearly budget 12000 with 9000 spent earlier and 300 this month shows 2700 never 11700`() = runTest {
        // Snapshot spent = 9000 earlier in the year + 300 this month = 9300; remaining = 2700.
        val yearly = budget(
            limit = 12_000.0, spent = 9_300.0, remaining = 2_700.0,
            periodStart = ts(2026, 1, 1), periodEnd = ts(2027, 1, 1)
        )

        val widget = safeToSpend(thisMonthEur300, listOf(yearly))

        assertFalse(widget.isUnavailable)
        assertEquals(2_700.0, requireNotNull(widget.amount), 0.001)
        assertNotEquals("calendar-month arithmetic (12000 - 300) must not be shown", 11_700.0, widget.amount!!, 0.001)
        assertEquals(12_000.0, requireNotNull(widget.totalBudget), 0.001)
        assertTrue(widget.hasBudget)
    }

    @Test
    fun `FL-06 rolling budget amount equals snapshot remainingAmount not calendar month remainder`() = runTest {
        // Rolling 30-day window Apr 10 .. May 10 (exclusive). Window spend 400, remaining 600.
        // Calendar month spend is only 300, so limit - month spend (700) would be wrong.
        val rolling = budget(
            limit = 1_000.0, spent = 400.0, remaining = 600.0,
            periodStart = ts(2026, 4, 10), periodEnd = ts(2026, 5, 10)
        )

        val widget = safeToSpend(thisMonthEur300, listOf(rolling))

        assertFalse(widget.isUnavailable)
        assertEquals(600.0, requireNotNull(widget.amount), 0.001)
        assertEquals(1_000.0, requireNotNull(widget.totalBudget), 0.001)
    }

    @Test
    fun `FL-06 overspent period clamps amount to zero and stays available`() = runTest {
        val exceeded = budget(
            limit = 1_000.0, spent = 1_250.0, remaining = -250.0,
            periodStart = ts(2026, 4, 1), periodEnd = ts(2026, 5, 1)
        )

        val widget = safeToSpend(thisMonthEur300, listOf(exceeded))

        assertFalse(widget.isUnavailable)
        assertEquals(0.0, requireNotNull(widget.amount), 0.0)
    }

    @Test
    fun `FL-06 limit of zero is unavailable with null amount and null totalBudget`() = runTest {
        val zeroLimit = budget(
            limit = 0.0, spent = 0.0, remaining = 0.0,
            periodStart = ts(2026, 4, 1), periodEnd = ts(2026, 5, 1)
        )

        val widget = safeToSpend(thisMonthEur300, listOf(zeroLimit))

        assertTrue("limit 0 must never render as an available amount", widget.isUnavailable)
        assertNull(widget.amount)
        assertNull(widget.totalBudget)
    }

    @Test
    fun `FL-06 incomplete snapshots are unavailable with null amount and null totalBudget`() = runTest {
        val complete = budget(
            limit = 1_000.0, spent = 300.0, remaining = 700.0,
            periodStart = ts(2026, 4, 1), periodEnd = ts(2026, 5, 1)
        )
        val incomplete = listOf(
            complete.copy(isPartial = true),
            complete.copy(percentKnown = false),
            complete.copy(conversionWarning = "x"),
            complete.copy(currency = "USD"),
            complete.copy(currency = null),
            complete.copy(currencyAssumption = "LEGACY_DEFAULT"),
            complete.copy(remainingAmount = Double.NaN)
        )

        for (snapshot in incomplete) {
            val widget = safeToSpend(thisMonthEur300, listOf(snapshot))
            assertTrue("incomplete snapshot $snapshot must be unavailable", widget.isUnavailable)
            assertNull(widget.amount)
            assertNull(widget.totalBudget)
        }
    }

    @Test
    fun `FL-06 daysRemaining derives from periodEnd not from the calendar month`() = runTest {
        // Now is Apr 15. periodEnd (exclusive) May 10 00:00 -> Apr 15..May 9 inclusive minus today
        // boundary => 24 days left after today. The calendar month would give 15.
        val rolling = budget(
            limit = 1_000.0, spent = 400.0, remaining = 600.0,
            periodStart = ts(2026, 4, 10), periodEnd = ts(2026, 5, 10)
        )

        assertEquals(24, safeToSpend(thisMonthEur300, listOf(rolling)).daysRemaining)
    }

    @Test
    fun `FL-06 daysRemaining is zero on the last day of the budget period and never negative`() = runTest {
        // periodEnd (exclusive) is tomorrow 00:00 -> today is the last day -> 0.
        val lastDay = budget(
            limit = 1_000.0, spent = 400.0, remaining = 600.0,
            periodStart = ts(2026, 4, 1), periodEnd = ts(2026, 4, 16)
        )
        assertEquals(0, safeToSpend(thisMonthEur300, listOf(lastDay)).daysRemaining)

        // periodEnd already in the past (stale snapshot) must clamp to 0, not go negative.
        val stale = lastDay.copy(periodEnd = ts(2026, 4, 1))
        assertEquals(0, safeToSpend(thisMonthEur300, listOf(stale)).daysRemaining)
    }

    private suspend fun safeToSpend(
        expenses: List<DashboardExpense>,
        budgets: List<BudgetStatusSnapshot>
    ): DashboardWidget.SafeToSpend {
        val processed = createProcessedData(expenses)
        val result = computeUseCase.compute(
            processed.copy(data = processed.data.copy(budgetStatuses = budgets))
        )
        return result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
    }

    private fun expense(amount: Double, date: Long) = DashboardExpense(
        id = 0L,
        amount = amount,
        effectiveAmount = amount,
        currency = "EUR",
        merchant = "Test",
        transactionType = DashboardTransactionType.PURCHASE,
        date = date,
        categoryId = null,
        isNotMine = false,
        isManualEntry = false
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
