package com.yourname.expensetracker.data.repository

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
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ComputeDashboardWidgetsUseCase
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardWidget
import com.yourname.expensetracker.domain.usecase.savings.LifestyleSavingsPromptUseCase
import com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCase
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DashboardContractsAdapterTest {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
    private val financialWeatherRepository = mockk<FinancialWeatherRepository>(relaxed = true)
    private val savingsGoalRepository = mockk<com.yourname.expensetracker.domain.savings.SavingsGoalRepository>(relaxed = true)
    private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val timeBoundaryTicker = mockk<TimeBoundaryTicker>(relaxed = true)

    private lateinit var adapter: DashboardContractsAdapter

    @Before
    fun setup() {
        adapter = DashboardContractsAdapter(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            budgetRepository = budgetRepository,
            reviewQueueRepository = reviewQueueRepository,
            financialWeatherRepository = financialWeatherRepository,
            savingsGoalRepository = savingsGoalRepository,
            analyticsRepository = analyticsRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            timeBoundaryTicker = timeBoundaryTicker
        )
    }

    @Test
    fun `observeRecurringPatterns uses confirmed recurring feed for dashboard forecast consumers`() = runTest {
        val confirmedPattern = recurringPattern("Confirmed Rent", 900.0)
        val mergedSuggestion = recurringPattern("Suggested Gym", 45.0)

        every { financialWeatherRepository.getConfirmedRecurringPatterns() } returns flowOf(listOf(confirmedPattern))
        every { financialWeatherRepository.getAllRecurringPatterns() } returns flowOf(listOf(mergedSuggestion))

        val result = adapter.observeRecurringPatterns().first()

        assertEquals(listOf("Confirmed Rent"), result.map { it.merchantName })
        verify(exactly = 1) { financialWeatherRepository.getConfirmedRecurringPatterns() }
        verify(exactly = 0) { financialWeatherRepository.getAllRecurringPatterns() }
    }

    @Test
    fun `observeBudgetStatuses propagates isPartial and conversionWarning to snapshot`() = runTest {
        val budget = com.yourname.expensetracker.data.database.entity.Budget(
            id = 1L,
            categoryId = null,
            amount = 100.0,
            currency = "USD",
            period = com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY,
            startDate = 1_700_000_000_000L,
            isActive = true
        )
        val status = com.yourname.expensetracker.domain.budget.BudgetStatus(
            budget = budget,
            category = null,
            spentAmount = 50.0,
            remainingAmount = 0.0,
            percentUsed = 0f,
            healthStatus = com.yourname.expensetracker.domain.budget.BudgetHealthStatus.UNKNOWN,
            periodStart = 1_700_000_000_000L,
            periodEnd = 1_702_000_000_000L,
            effectiveLimit = 100.0,
            isPartial = true,
            conversionWarning = "Budget limit could not be converted from USD to EUR"
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(listOf(status))

        val result = adapter.observeBudgetStatuses().first()

        assertEquals(1, result.size)
        assertEquals(true, result[0].isPartial)
        assertEquals("Budget limit could not be converted from USD to EUR", result[0].conversionWarning)
        assertEquals(com.yourname.expensetracker.domain.budget.BudgetHealthStatus.UNKNOWN, result[0].healthStatus)
    }

    @Test
    fun `observeDashboardExpenses carries shared-expense identity across the boundary`() = runTest {
        // P5-004 (RP-05): the isSharedExpense flag must survive the adapter
        // mapping — downstream deposit exclusion is otherwise tautological.
        val now = 1_712_000_000_000L
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        val sharedDeposit = com.yourname.expensetracker.data.database.entity.Expense(
            id = 1L, amount = 100.0, currency = "EUR",
            merchant = "Shared rent", transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            date = now, categoryId = null, isNotMine = false,
            isSharedExpense = true, isManualEntry = false
        )
        val ownDeposit = sharedDeposit.copy(id = 2L, merchant = "Salary", isSharedExpense = false)
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedDeposit, category = null),
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = ownDeposit, category = null)
            )
        )

        val result = adapter.observeDashboardExpenses().first()

        assertEquals(2, result.size)
        val byId = result.associateBy { it.id }
        assertEquals(true, byId[1L]?.isSharedExpense)
        assertEquals(false, byId[2L]?.isSharedExpense)
    }

    /**
     * P5-004 (RP-05) full round-trip through the REAL production mappers:
     * entity `isSharedExpense=true` → DashboardContractsAdapter.toDomainDashboard()
     * → DashboardExpense.isSharedExpense=true → ComputeDashboardWidgetsUseCase
     * .toExpenseEntity() (via compute()) → reconstructed entity keeps
     * isSharedExpense=true.
     *
     * The observable proof of the reconstructed entity is the deposit-exclusion
     * consequence (NEW-P5-003): a shared DEPOSIT must be excluded from dashboard
     * income while a shared PURCHASE remains ordinary spend. (The normalized
     * pipeline intentionally hardcodes isSharedExpense=false on NormalizedExpense,
     * so the flag's survival is only observable through the deposit filter.)
     */
    @Test
    fun `shared expense identity survives adapter to dashboard to entity round trip`() = runTest {
        // Fixed wall-clock timestamps in the JVM's default zone: `now` mid-month,
        // fixture expenses 9 days earlier — strictly inside [monthStart, now) in
        // every timezone, matching the current-period scoping of
        // produceDashboardNormalizedInput.
        val now = java.time.LocalDateTime.of(2024, 4, 15, 12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val midMonthDate = java.time.LocalDateTime.of(2024, 4, 6, 12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(now)
        val sharedDeposit = com.yourname.expensetracker.data.database.entity.Expense(
            id = 1L, amount = 100.0, currency = "EUR",
            merchant = "Shared rent", transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            date = midMonthDate, categoryId = null, isNotMine = false,
            isSharedExpense = true, isManualEntry = false
        )
        val sharedPurchase = sharedDeposit.copy(
            id = 3L,
            merchant = "Shared dinner",
            transactionType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            myShareAmount = 25.0
        )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedDeposit, category = null),
                com.yourname.expensetracker.data.database.model.ExpenseWithCategory(expense = sharedPurchase, category = null)
            )
        )

        // Leg 1 — REAL production mapper: entity → DashboardExpense.
        val dashboardExpenses = adapter.observeDashboardExpenses().first()
        val dashboardById = dashboardExpenses.associateBy { it.id }
        val mappedDeposit = dashboardById[1L]
        val mappedPurchase = dashboardById[3L]
        assertEquals(true, mappedDeposit?.isSharedExpense)
        assertEquals(true, mappedPurchase?.isSharedExpense)

        // Leg 2 — REAL production use case: DashboardExpense → toExpenseEntity()
        // → produceDashboardNormalizedInput deposit filter. The shared DEPOSIT
        // must be excluded from income; the shared PURCHASE must remain spend.
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
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val multiCurrencyRepository = mockk<MultiCurrencyRepository>(relaxed = true)
        coEvery { multiCurrencyRepository.getHomeCurrencyPurchaseTotal(any(), any()) } returns
            MoneyAggregate.empty(CurrencyCode("EUR"))
        val computeUseCase = ComputeDashboardWidgetsUseCase(
            writeBarrier = mockk(relaxed = true),
            insightsEngine = insightsEngine,
            synthesisEngine = SynthesisEngine(timeProvider = object : TimeProvider {
                override fun now(): Long = now
            }, currencyConverter = mockk(relaxed = true)),
            monteCarloSimulator = mockk<MonteCarloSpendingSimulator>(relaxed = true),
            timeProvider = object : TimeProvider {
                override fun now(): Long = now
            },
            healthCalculator = mockk(relaxed = true),
            healthScoreV2 = healthScoreV2,
            lifestyleSavingsPromptUseCase = mockk<LifestyleSavingsPromptUseCase>(relaxed = true).apply {
                coEvery { evaluateAndPrompt() } returns null
            },
            monthlySavingsSweepUseCase = mockk<MonthlySavingsSweepUseCase>(relaxed = true).apply {
                coEvery { computeSweepRecommendation() } returns null
            },
            computeMoneyRadarUseCase = mockk(relaxed = true),
            stressForecastEngine = mockk<FinancialStressForecastEngine>(relaxed = true).apply {
                coEvery { computeStressForecast() } returns StressForecastResult(
                    horizons = listOf(
                        StressHorizon(30, 0.0, 0.0, 0.0, StressRiskLevel.LOW, 0.0, 0.0, 0.0)
                    ),
                    overallRiskLevel = StressRiskLevel.LOW,
                    earliestCrunchDate = null,
                    recommendations = emptyList()
                )
            },
            forecastInputAssembler = mockk(relaxed = true),
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            currencySettingsRepository = currencySettingsRepository,
            multiCurrencyRepository = multiCurrencyRepository
        )

        val data = com.yourname.expensetracker.domain.usecase.dashboard.DashboardData(
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
        val processedData = com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData(
            data = data,
            summary = com.yourname.expensetracker.domain.model.dashboard.SpendingSummary(
                totalSpent = 0.0,
                previousTotalSpent = null,
                changePercent = null,
                dailyHistory = emptyList(),
                previousDailyHistory = emptyList(),
                transactionCount = 0
            ),
            categoryBreakdown = emptyList()
        )

        val compiled = computeUseCase.compute(processedData)

        // Deposit-exclusion consequence: the shared DEPOSIT is excluded from
        // income, so the canonical income aggregate is 0.0 despite the 100.0
        // shared deposit — only possible if the reconstructed entity kept
        // isSharedExpense=true. depositAggregate is the single source feeding
        // FinancialRunway.monthlyIncome (ComputeDashboardWidgetsUseCase).
        val normalizedInput = compiled.normalizedInput
            as com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Available
        assertEquals(0.0, normalizedInput.input.depositAggregate!!.displayAmount, 0.0001)
        // The shared PURCHASE remains ordinary spend (not excluded by the flag).
        assertEquals(25.0, compiled.totalSpent, 0.0001)
        val periodSummary = compiled.allWidgets.filterIsInstance<DashboardWidget.PeriodSummary>().single()
        assertEquals(25.0, periodSummary.monthSpent, 0.0001)
        // Same-input control: an OWN deposit is counted as income (proves the
        // exclusion above comes from isSharedExpense, not from a blanket rule).
        val ownDepositOnly = listOf(
            com.yourname.expensetracker.data.database.model.ExpenseWithCategory(
                expense = sharedDeposit.copy(id = 9L, isSharedExpense = false),
                category = null
            )
        )
        every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(ownDepositOnly)
        val ownIncome = adapter.observeDashboardExpenses().first()
        assertEquals(false, ownIncome.single().isSharedExpense)
        val compiledOwn = computeUseCase.compute(
            processedData.copy(
                data = data.copy(expenses = ownIncome)
            )
        )
        val normalizedInputOwn = compiledOwn.normalizedInput
            as com.yourname.expensetracker.domain.usecase.dashboard.DashboardNormalizedInputResult.Available
        assertEquals(100.0, normalizedInputOwn.input.depositAggregate!!.displayAmount, 0.0001)
    }

    private fun recurringPattern(merchant: String, amount: Double): RecurringPattern {
        return RecurringPattern(
            merchantName = merchant,
            averageAmount = amount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = 1_710_000_000_000L,
            confidence = 1.0f,
            previousDates = emptyList()
        )
    }
}
