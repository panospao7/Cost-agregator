package com.yourname.expensetracker.domain.usecase.dashboard

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.NormalizedForecastInput
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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RP-05 review follow-up (P5-003): the synthesis baseline mean
 * (SpendingPace.averageMonthlyTotal) must treat completed zero-spend months that
 * were OBSERVED as real history contributing 0.0 -- a month with deposits
 * recorded but nothing bought stays in the mean; only months with no observed
 * rows of any kind (pre-install) are excluded. Asserted through the full
 * compute() path via the assembled NormalizedForecastInput.
 */
class ComputeDashboardWidgetsHistoricalMeanPolicyTest {

    private var fixedNowMs: Long = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = fixedNowMs
    }
    private val assemblerSlot = slot<NormalizedForecastInput>()
    private lateinit var computeUseCase: ComputeDashboardWidgetsUseCase

    @Before
    fun setup() {
        fixedNowMs = toEpochMs(2026, 6, 15, 12, 0)

        val healthScoreV2 = mockk<com.yourname.expensetracker.domain.health.FinancialHealthScoreV2>(relaxed = true)
        coEvery { healthScoreV2.calculateHealthScore(any(), any()) } returns
            com.yourname.expensetracker.domain.health.HealthScoreOutcome.Available(
                com.yourname.expensetracker.domain.health.FinancialHealthResult(
                    overallScore = 50,
                    savingsRateScore = 50,
                    runwayScore = 50,
                    budgetAdherenceScore = 50,
                    billReliabilityScore = 50,
                    factorContributions = emptyList(),
                    trend = com.yourname.expensetracker.domain.health.HealthTrend.STABLE,
                    recommendation = null
                )
            )
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))

        // Echo stub (same pattern as ComputeDashboardWidgetsUseCasePaceWiringTest):
        // captures the assembled NormalizedForecastInput and carries its pace
        // through so the mean policy is observable while SynthesisEngine still
        // receives a real ForecastInput.
        val forecastInputAssembler = mockk<ForecastInputAssembler>(relaxed = true)
        coEvery { forecastInputAssembler.assembleNormalized(capture(assemblerSlot)) } answers {
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
            synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = mockk(relaxed = true),
            timeProvider = timeProvider,
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true).apply {
                coEvery { evaluateAndPrompt() } returns null
            },
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true).apply {
                coEvery { computeSweepRecommendation() } returns null
            },
            computeMoneyRadarUseCase = mockk(relaxed = true),
            stressForecastEngine = mockk(relaxed = true),
            forecastInputAssembler = forecastInputAssembler,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository,
            // Real calculator with the test's fake TimeProvider -- pace math is real.
            spendingPaceCalculator = com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator(timeProvider)
        )
    }

    @Test
    fun `deposit-only completed month stays in the mean as zero`() = runTest {
        // M-1 (May): 300 purchased. M-2 (April): salary deposited, nothing bought.
        // Mean = (300 + 0) / 2 = 150 -- the observed zero-spend month must not be
        // dropped from the denominator.
        val expenses = listOf(
            purchase(1L, 100.0, toEpochMs(2026, 6, 2, 9, 0)),
            purchase(2L, 300.0, toEpochMs(2026, 5, 10, 9, 0)),
            deposit(3L, 2500.0, toEpochMs(2026, 4, 10, 9, 0))
        )

        computeUseCase.compute(createProcessedData(expenses))

        assertEquals(150.0, assemblerSlot.captured.spendingPace.averageMonthlyTotal!!, 0.0001)
    }

    @Test
    fun `months with no observed rows stay excluded from the mean`() = runTest {
        // Only M-1 and M-2 hold rows; M-3..M-5 are pre-install empties.
        // Mean = (300 + 400) / 2 = 350 -- never (300 + 400) / 5 = 140.
        val expenses = listOf(
            purchase(1L, 100.0, toEpochMs(2026, 6, 2, 9, 0)),
            purchase(2L, 300.0, toEpochMs(2026, 5, 10, 9, 0)),
            purchase(3L, 400.0, toEpochMs(2026, 4, 10, 9, 0))
        )

        computeUseCase.compute(createProcessedData(expenses))

        assertEquals(350.0, assemblerSlot.captured.spendingPace.averageMonthlyTotal!!, 0.0001)
    }

    @Test
    fun `zero-amount purchase row keeps a completed month in the mean as zero`() = runTest {
        // M-2 holds a recorded 0.0 purchase -- hasPurchases stays true, so the
        // month contributes 0.0: mean = (300 + 0) / 2 = 150.
        val expenses = listOf(
            purchase(1L, 100.0, toEpochMs(2026, 6, 2, 9, 0)),
            purchase(2L, 300.0, toEpochMs(2026, 5, 10, 9, 0)),
            purchase(3L, 0.0, toEpochMs(2026, 4, 10, 9, 0))
        )

        computeUseCase.compute(createProcessedData(expenses))

        assertEquals(150.0, assemblerSlot.captured.spendingPace.averageMonthlyTotal!!, 0.0001)
    }

    private fun purchase(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "M$id",
        transactionType = TransactionType.PURCHASE, date = date,
        categoryId = 7L, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun deposit(id: Long, amount: Double, date: Long) = Expense(
        id = id, amount = amount, currency = "EUR", merchant = "D$id",
        transactionType = TransactionType.DEPOSIT, date = date,
        categoryId = null, isNotMine = false, isSharedExpense = false,
        isManualEntry = false
    )

    private fun createProcessedData(expenses: List<Expense>): ProcessedDashboardData {
        val dashboardExpenses = expenses.map { it.toDashboardExpense() }
        val data = DashboardData(
            expenses = dashboardExpenses,
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

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        currency = currency,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        isSharedExpense = isSharedExpense
    )

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
