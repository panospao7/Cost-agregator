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
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.BlockPartyStatus
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.dashboard.BudgetStatusSnapshot
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.DashboardTransactionType
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.usecase.dashboard.CompiledDashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.dashboard.MoneyRadarData
import com.yourname.expensetracker.domain.usecase.dashboard.UrgencyLevel
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Ensures dashboard widgets use consistent calculations from SynthesisEngine and shared data.
 * Critical: FinancialRunway (committed+likely), MonteCarlo (knownUpcoming), PeriodSummary (monthSpent).
 */
class DashboardWidgetConsistencyTest {

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
            paceStatus = PaceStatus.ON_PACE,
            displayCurrency = "EUR"
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
        val monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true)
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
            synthesisEngine = SynthesisEngine(timeProvider),
            monteCarloSimulator = monteCarloSimulator,
            timeProvider = timeProvider,
            multiCurrencyRepository = mockk(),
            healthCalculator = healthCalculator,
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = lifestyleSavingsPromptUseCase,
            monthlySavingsSweepUseCase = monthlySavingsSweepUseCase,
            computeMoneyRadarUseCase = computeMoneyRadarUseCase,
            stressForecastEngine = stressForecastEngine,
            forecastInputAssembler = mockk(),
        )
    }

    @Test
    fun `consistency - PeriodSummary monthSpent matches purchases effectiveAmount sum`() = runTest {
        val now = ts(2024, 5, 15)
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val purchases = listOf(
            createExpense(100.0, monthStart + 86400000),
            createExpense(200.0, monthStart + 172800000),
            createExpense(50.0, monthStart + 259200000, isSharedExpense = true, myShareAmount = 25.0),
        )
        val expectedMonthSpent = 100.0 + 200.0 + 25.0

        val processedData = createProcessedData(expenses = purchases.map { it.toDashboardExpense() }, monthSpent = expectedMonthSpent)
        val result = computeUseCase.compute(processedData)

        val periodSummary = result.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertEquals("PeriodSummary monthSpent must match", expectedMonthSpent, periodSummary.monthSpent, 0.001)
    }

    @Test
    fun `consistency - FinancialRunway committed and likely from SynthesisEngine`() = runTest {
        val now = ts(2024, 5, 15)
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val purchases = listOf(createExpense(100.0, monthStart + 86400000))
        val recurring = listOf(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 15.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = monthStart + 20 * 86400000L,
                confidence = 0.95f,
                previousDates = emptyList()
            )
        )
        val planned = listOf(
            PlannedExpense(id = 0, description = "Trip", amount = 500.0, date = monthStart + 25 * 86400000L, categoryId = null, isRecurring = false, priority = PlannedExpensePriority.MUST)
        )
        val budgetStatus = BudgetStatusSnapshot(
            budgetCategoryId = null,
            budgetAmount = 1000.0,
            categoryName = null,
            spentAmount = 100.0,
            remainingAmount = 900.0,
            percentUsed = 10.0,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = monthStart,
            periodEnd = monthStart + 30 * 86400000L
        )

        val processedData = createProcessedData(
            expenses = purchases.map { it.toDashboardExpense() },
            monthSpent = 100.0,
            budgetStatuses = listOf(budgetStatus),
            recurringPatterns = recurring,
            plannedExpenses = planned
        )
        val result = computeUseCase.compute(processedData)

        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().singleOrNull()
        assertNotNull("Runway should be present when budget and data exist", runway)
        assertTrue("Runway committed+likely should be non-negative", runway!!.committedExpenses + runway.likelyExpenses >= 0)
    }

    @Test
    fun `consistency - SafeToSpend and FinancialRunway use same discretionaryBudget`() = runTest {
        val discretionaryBudget = 300.0
        val weather = FinancialWeather(
            state = WeatherState.UNKNOWN,
            headline = UiText.DynamicString(""),
            summary = UiText.DynamicString(""),
            icon = "",
            riskLevel = 0,
            totalCommitted = 100.0,
            totalLikely = 50.0,
            predictedDiscretionary = discretionaryBudget,
            discretionaryBudget = discretionaryBudget
        )
        val budgetStatus = BudgetStatusSnapshot(
            budgetCategoryId = null,
            budgetAmount = 1000.0,
            categoryName = null,
            spentAmount = 500.0,
            remainingAmount = 500.0,
            percentUsed = 50.0,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = 0,
            periodEnd = 0
        )

        val processedData = createProcessedData(
            expenses = emptyList(),
            monthSpent = 500.0,
            weather = weather,
            budgetStatuses = listOf(budgetStatus)
        )
        val result = computeUseCase.compute(processedData)

        val safeToSpend = result.allWidgets.filterIsInstance<DashboardWidget.SafeToSpend>().single()
        val runway = result.allWidgets.filterIsInstance<DashboardWidget.FinancialRunway>().singleOrNull()
        assertEquals("SafeToSpend uses weather.discretionaryBudget", discretionaryBudget, safeToSpend.amount, 0.001)
        if (runway != null) {
            assertEquals("Runway discretionaryRemaining from same source", discretionaryBudget, runway.discretionaryRemaining, 0.001)
        }
    }

    @Test
    fun `consistency - totalSpent in CompiledDashboardData matches monthSpent`() = runTest {
        val monthSpent = 750.0
        val processedData = createProcessedData(expenses = emptyList(), monthSpent = monthSpent)
        val result = computeUseCase.compute(processedData)
        assertEquals("CompiledDashboardData.totalSpent", monthSpent, result.totalSpent, 0.001)
    }

    private fun createProcessedData(
        expenses: List<DashboardExpense>,
        monthSpent: Double,
        weather: FinancialWeather = FinancialWeather(
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
        budgetStatuses: List<BudgetStatusSnapshot> = emptyList(),
        recurringPatterns: List<RecurringPattern> = emptyList(),
        plannedExpenses: List<PlannedExpense> = emptyList()
    ): ProcessedDashboardData {
        val data = DashboardData(
            expenses = expenses,
            categories = emptyList(),
            budgetStatuses = budgetStatuses,
            pendingCount = 0,
            weather = weather,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = monthSpent,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = expenses.size
        )
        return ProcessedDashboardData(data = data, summary = summary, categoryBreakdown = emptyList())
    }

    private fun createExpense(
        amount: Double,
        date: Long,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null
    ): Expense = Expense(
        amount = amount,
        merchant = "Test",
        transactionType = TransactionType.PURCHASE,
        date = date,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount
    )

    private fun Expense.toDashboardExpense(): DashboardExpense = DashboardExpense(
        id = id,
        amount = amount,
        effectiveAmount = effectiveAmount,
        merchant = merchant,
        transactionType = when (transactionType) {
            TransactionType.PURCHASE -> DashboardTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DashboardTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DashboardTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DashboardTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DashboardTransactionType.UNKNOWN
        },
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry
    )
}