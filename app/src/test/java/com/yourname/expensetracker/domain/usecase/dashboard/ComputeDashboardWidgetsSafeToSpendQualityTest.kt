package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
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
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * F-DEV-2 regression: the SafeToSpend card must only carry a partial-conversion
 * quality when the normalized dashboard input is genuinely partial. Previously the
 * currency-quality flag was hardcoded partial, so every user (EUR home, no foreign
 * currency) saw "Some amounts could not be converted".
 */
class ComputeDashboardWidgetsSafeToSpendQualityTest {

    private val timeProvider = object : TimeProvider {
        override fun now(): Long = ts(2026, 4, 15)
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
    fun `eur home with no expenses and no budget is not partial`() = runTest {
        val safeToSpend = computeSafeToSpend(emptyList())

        assertTrue(safeToSpend.isUnavailable)
        assertFalse("SafeToSpend must not be partial with no data", safeToSpend.isPartial)
        assertFalse("R31: overall dashboard result must not be partial", lastResult.isPartial)
        val quality = safeToSpend.currencyQuality
        assertNotNull(quality)
        assertFalse("currencyQuality must not be partial with no data", quality!!.isPartial)
        assertNull("No warning message when nothing is partial", quality.warningMessage)
        assertFalse("R31: no budget means hasBudget is false (set-budget CTA)", safeToSpend.hasBudget)
    }

    @Test
    fun `eur home with an overall budget reports hasBudget true`() = runTest {
        val now = timeProvider.now()
        val budget = BudgetStatusSnapshot(
            null, 1234.5, null, 0.0, 1234.5, 0.0,
            BudgetHealthStatus.ON_TRACK,
            now, now + 86_400_000L, currency = "EUR", currencyAssumption = "USER_CONFIRMED",
            effectiveSpend = 0.0
        )
        val safeToSpend = computeSafeToSpend(emptyList(), listOf(budget))

        // Previously encoded the FL-06 bug (SafeToSpend hardcoded unavailable for every user);
        // S3 now computes remaining discretionary amount when budget and spend are complete.
        assertFalse(safeToSpend.isUnavailable)
        org.junit.Assert.assertEquals(1234.5, requireNotNull(safeToSpend.amount), 0.0)
        assertTrue("R31: an existing budget must suppress the set-budget CTA", safeToSpend.hasBudget)
    }

    @Test
    fun `eur home with one eur expense is not partial`() = runTest {
        val monthStart = TimePeriodUtils.getStartOfMonth(timeProvider.now())
        val safeToSpend = computeSafeToSpend(
            listOf(createExpense(25.0, monthStart + 86_400_000L, "EUR"))
        )

        assertFalse(safeToSpend.isPartial)
        val quality = safeToSpend.currencyQuality
        assertNotNull(quality)
        assertFalse("A same-currency expense is fully converted", quality!!.isPartial)
        assertFalse("R31: overall dashboard result must not be partial", lastResult.isPartial)
        assertNull(quality.warningMessage)
    }

    @Test
    fun `usd expense without a rate marks safe to spend quality unavailable`() = runTest {
        val monthStart = TimePeriodUtils.getStartOfMonth(timeProvider.now())
        val safeToSpend = computeSafeToSpend(
            listOf(createExpense(25.0, monthStart + 86_400_000L, "USD"))
        )

        // S3 distinguishes partial (some rows converted) from unavailable (0 of 1 rows converted):
        // when all rows fail conversion, SafeToSpend is unavailable, not partial.
        assertTrue("Missing rate on all rows must mark SafeToSpend unavailable", safeToSpend.isUnavailable)
        assertFalse("When 0 of 1 rows convert, SafeToSpend is unavailable rather than partial", safeToSpend.isPartial)
        val quality = safeToSpend.currencyQuality
        assertNotNull(quality)
        assertTrue("Missing rate must mark currencyQuality partial", quality!!.isPartial)
        assertNotNull("R31: partial quality must carry a warning message", quality.warningMessage)
    }

    @Test
    fun `FL-06 complete budget and spend computes remaining discretionary amount`() = runTest {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val budget = BudgetStatusSnapshot(
            null, 1000.0, null, 300.0, 700.0, 30.0,
            BudgetHealthStatus.ON_TRACK,
            now, now + 86_400_000L, currency = "EUR", currencyAssumption = "USER_CONFIRMED",
            effectiveSpend = 300.0
        )
        val safeToSpend = computeSafeToSpend(
            listOf(createExpense(300.0, monthStart + 86_400_000L, "EUR")),
            listOf(budget)
        )

        assertFalse("Complete budget + spend must not be unavailable", safeToSpend.isUnavailable)
        assertFalse("Complete budget + spend must not be partial", safeToSpend.isPartial)
        org.junit.Assert.assertEquals(700.0, requireNotNull(safeToSpend.amount), 0.001)
        org.junit.Assert.assertEquals(1000.0, requireNotNull(safeToSpend.totalBudget), 0.001)
        assertTrue(safeToSpend.hasBudget)
    }

    @Test
    fun `FL-06 safe to spend stays unavailable when budget missing or currency incomplete or input partial`() = runTest {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val eurExpense = createExpense(300.0, monthStart + 86_400_000L, "EUR")
        val validBudget = BudgetStatusSnapshot(
            null, 1000.0, null, 300.0, 700.0, 30.0,
            BudgetHealthStatus.ON_TRACK,
            now, now + 86_400_000L, currency = "EUR", currencyAssumption = "USER_CONFIRMED",
            effectiveSpend = 300.0
        )

        // 1. Missing budget -> unavailable
        val missingBudgetResult = computeSafeToSpend(listOf(eurExpense), emptyList())
        assertTrue("Missing budget must stay unavailable", missingBudgetResult.isUnavailable)
        assertNull(missingBudgetResult.amount)
        assertFalse(missingBudgetResult.hasBudget)

        // 2. Budget fails currency completeness (mismatched currency, legacy assumption, partial budget)
        for (incompleteBudget in listOf(
            validBudget.copy(currency = "USD"),
            validBudget.copy(currencyAssumption = "LEGACY_DEFAULT"),
            validBudget.copy(isPartial = true)
        )) {
            val incompleteResult = computeSafeToSpend(listOf(eurExpense), listOf(incompleteBudget))
            assertTrue("Incomplete budget must stay unavailable", incompleteResult.isUnavailable)
            assertNull(incompleteResult.amount)
            assertTrue(incompleteResult.hasBudget)
        }

        // 3. Partial dashboard spend input (unconvertible USD row) with a COMPLETE budget snapshot.
        // FL-06 changed the source of the hero number: it is the snapshot's own limit minus its
        // effectiveSpend (the budget's spend is converted by the budget pipeline, whose partiality
        // is carried by snapshot.isPartial, covered in step 2). A partial calendar-month dashboard
        // aggregate no longer feeds the amount, so the amount stays 1000 - 300 = 700, and the partial state is
        // surfaced through isPartial and the currency-quality warning instead of nulling the amount.
        val partialInputResult = computeSafeToSpend(
            listOf(eurExpense, createExpense(50.0, monthStart + 86_400_000L, "USD")),
            listOf(validBudget)
        )
        assertFalse("Complete snapshot stays available", partialInputResult.isUnavailable)
        assertTrue("Partial spend input must mark SafeToSpend partial", partialInputResult.isPartial)
        assertTrue(
            "Partial spend input must surface a partial currency-quality warning",
            partialInputResult.currencyQuality!!.isPartial
        )
        org.junit.Assert.assertEquals(700.0, requireNotNull(partialInputResult.amount), 0.001)
    }

    @Test
    fun `FL-06 monthSpent greater than budget clamps safe to spend to zero`() = runTest {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val budget = BudgetStatusSnapshot(
            null, 1000.0, null, 1250.0, 0.0, 125.0,
            BudgetHealthStatus.EXCEEDED,
            now, now + 86_400_000L, currency = "EUR", currencyAssumption = "USER_CONFIRMED",
            effectiveSpend = 1250.0
        )
        val safeToSpend = computeSafeToSpend(
            listOf(createExpense(1250.0, monthStart + 86_400_000L, "EUR")),
            listOf(budget)
        )

        assertFalse("Exceeded complete budget is still available", safeToSpend.isUnavailable)
        assertFalse(safeToSpend.isPartial)
        org.junit.Assert.assertEquals(0.0, requireNotNull(safeToSpend.amount), 0.0)
    }

    @Test
    fun `N-08-5 PeriodSummary distinguishes unavailable from zero and keeps isPartial separate`() = runTest {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)

        // 1. Genuinely zero period: Available with 0.0 values, isUnavailable = false, isPartial = false
        computeSafeToSpend(emptyList())
        val zeroSummary = lastResult.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertFalse("Genuinely zero period must not be unavailable", zeroSummary.isUnavailable)
        assertFalse("Genuinely zero period must not be partial", zeroSummary.isPartial)
        org.junit.Assert.assertEquals(0.0, zeroSummary.todaySpent, 0.0)
        org.junit.Assert.assertEquals(0.0, zeroSummary.weekSpent, 0.0)
        org.junit.Assert.assertEquals(0.0, zeroSummary.monthSpent, 0.0)

        // 2. All-unconvertible period (only USD row with no rate): isUnavailable = true, isPartial = false
        computeSafeToSpend(listOf(createExpense(50.0, monthStart + 86_400_000L, "USD")))
        val unconvertibleSummary = lastResult.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertTrue("Fully unconvertible period must be unavailable", unconvertibleSummary.isUnavailable)
        assertFalse("Unavailable must not set isPartial (separate concepts)", unconvertibleSummary.isPartial)
    }

    @Test
    fun `RB1-S3-2 PeriodSummary mixed-horizon case marks today unavailable while month stays available`() = runTest {
        val now = timeProvider.now()
        val monthStart = TimePeriodUtils.getStartOfMonth(now)
        val todayStart = TimePeriodUtils.getStartOfDay(now)

        // Today has 1 unconvertible USD row (0 included, 1 excluded -> todayAggregate UNAVAILABLE),
        // while earlier in the month has 1 convertible EUR row (monthAggregate PARTIAL, not UNAVAILABLE).
        computeSafeToSpend(
            listOf(
                createExpense(100.0, monthStart + 86_400_000L, "EUR"),
                createExpense(40.0, todayStart + 1_000L, "USD")
            )
        )
        val summary = lastResult.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()

        assertTrue("Today has 0 convertible rows so isTodayUnavailable must be true", summary.isTodayUnavailable)
        assertFalse("Month has a converted EUR row so isMonthUnavailable must be false", summary.isMonthUnavailable)
        assertFalse("Top-level isUnavailable must be false when month is available", summary.isUnavailable)
        assertTrue("Mixed-horizon partial conversion must mark isPartial true", summary.isPartial)
        org.junit.Assert.assertEquals(100.0, summary.monthSpent, 0.0)
    }

    @Test
    fun `RB1-S3-2 PeriodSummary all-unavailable control marks all four flags true`() = runTest {
        val now = timeProvider.now()
        val todayStart = TimePeriodUtils.getStartOfDay(now)

        // Single unconvertible USD row today (inside today, week, and month windows) with no EUR rows anywhere.
        computeSafeToSpend(listOf(createExpense(50.0, todayStart + 1_000L, "USD")))
        val summary = lastResult.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()

        assertTrue("isTodayUnavailable must be true when all three horizons fail", summary.isTodayUnavailable)
        assertTrue("isWeekUnavailable must be true when all three horizons fail", summary.isWeekUnavailable)
        assertTrue("isMonthUnavailable must be true when all three horizons fail", summary.isMonthUnavailable)
        assertTrue("Top-level isUnavailable must be true when all three horizons are unavailable", summary.isUnavailable)
    }

    private lateinit var lastResult: CompiledDashboardData

    private suspend fun computeSafeToSpend(
        expenses: List<Expense>,
        budgets: List<BudgetStatusSnapshot> = emptyList()
    ): DashboardWidget.SafeToSpend {
        val processed = createProcessedData(expenses.map { it.toDashboardExpense() })
        val result = computeUseCase.compute(
            processed.copy(data = processed.data.copy(budgetStatuses = budgets))
        )
        lastResult = result
        return result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
    }

    private fun createExpense(amount: Double, date: Long, currency: String): Expense = Expense(
        amount = amount,
        currency = currency,
        merchant = "Test",
        transactionType = TransactionType.PURCHASE,
        date = date,
        createdAt = System.currentTimeMillis(),
    )

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
