package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialWeatherRepositoryTest {

    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val insightsEngine = mockk<InsightsEngine>(relaxed = true)
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val recurringExpenseEngine = mockk<RecurringExpenseEngine>(relaxed = true)
    private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val synthesisEngine = mockk<SynthesisEngine>(relaxed = true)
    private val narrativeGenerator = mockk<NarrativeGenerator>(relaxed = true)
    private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)

    private lateinit var repository: FinancialWeatherRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1705320000000L // Jan 15, 2024
        
        repository = FinancialWeatherRepository(
            expenseRepository,
            insightsEngine,
            budgetRepository,
            recurringExpenseRepository,
            recurringExpenseEngine,
            plannedExpenseRepository,
            savingsGoalRepository,
            synthesisEngine,
            narrativeGenerator,
            analyticsRepository,
            timeProvider
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getFinancialWeather correctly calculates past daily cumulative spend including day 0`() = runTest {
        // Arrange
        val now = 1705320000000L // Jan 15, 2024
        val monthStart = 1704060000000L // Jan 1, 2024 00:00:00

        val expenses = listOf(
            createExpense(amount = 10.0, date = monthStart, type = TransactionType.PURCHASE), // Day 0
            createExpense(amount = 20.0, date = monthStart + 86400000L, type = TransactionType.PURCHASE), // Day 1
            createExpense(amount = 5.0, date = now, type = TransactionType.PURCHASE) // Today (Day 14)
        )

        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val spendingPace = SpendingPace(
            currentMonthSpent = 35.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 70.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 50.0f,
            paceStatus = PaceStatus.ON_PACE
        )
        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns spendingPace

        val capturedPastSum = slot<List<Double>>()
        every { synthesisEngine.synthesize(
            pastSumDaily = capture(capturedPastSum),
            recurringPatterns = any(),
            plannedExpenses = any(),
            savingsGoals = any(),
            budgetStatuses = any(),
            spendingPace = any()
        ) } returns createMockForecast()

        every { narrativeGenerator.generate(any(), any()) } returns createMockNarrative()

        // Act
        val weatherFlow = repository.getFinancialWeather()
        val result = weatherFlow.first()

        // Assert
        val pastSum = capturedPastSum.captured
        assertEquals(15, pastSum.size) // Jan 1 to Jan 15 inclusive
        assertEquals(10.0, pastSum[0], 0.01) // Day 0
        assertEquals(30.0, pastSum[1], 0.01) // Day 1 (10 + 20)
        assertEquals(30.0, pastSum[2], 0.01) // Day 2 (no spending, cumulative stays same)
        assertEquals(35.0, pastSum[14], 0.01) // Day 14 (30 + 5)
    }

    @Test
    fun `maps forecast components to weather state risk totals and upcoming items`() = runTest {
        val now = 1705320000000L // Jan 15, 2024
        every { timeProvider.now() } returns now

        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 15,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0f,
            paceStatus = PaceStatus.NO_BASELINE
        )

        val recurring = listOf(
            RecurringPattern(
                merchantName = "Rent",
                averageAmount = 600.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 3 * 24 * 60 * 60 * 1000L,
                confidence = 0.95f,
                previousDates = emptyList()
            )
        )
        val planned = listOf(
            PlannedExpense(
                id = 1,
                description = "Laptop",
                amount = 500.0,
                date = now + 5 * 24 * 60 * 60 * 1000L,
                categoryId = null,
                isRecurring = false,
                priority = PlannedExpensePriority.LIKELY
            )
        )
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns recurring

        every {
            synthesisEngine.synthesize(any(), any(), any(), any(), any(), any())
        } returns FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.ofEpochMilli(now),
            confidence = 0.8,
            components = ForecastComponents(
                recurringExpenses = recurring,
                plannedExpenses = planned,
                pastSpendingPoints = listOf(10.0, 20.0),
                projectedSpendingPoints = listOf(25.0, 40.0),
                totalCommitted = 600.0,
                totalLikely = 350.0,
                predictedDiscretionary = 150.0,
                discretionaryBudget = 300.0,
                riskLevel = RiskLevel.HIGH
            ),
            actionableInsights = emptyList()
        )
        every { narrativeGenerator.generate(any(), any()) } returns WeatherNarrative(
            state = WeatherState.RAINY,
            icon = "🌧️",
            headline = "Rainy",
            summary = "Careful"
        )

        val result = repository.getFinancialWeather().first()

        assertEquals(WeatherState.RAINY, result.state)
        assertEquals(70, result.riskLevel)
        assertEquals(600.0, result.totalCommitted, 0.01)
        assertEquals(350.0, result.totalLikely, 0.01)
        assertEquals(150.0, result.predictedDiscretionary, 0.01)
        assertEquals(listOf(10.0, 20.0), result.pastSpendingPoints)
        assertEquals(listOf(25.0, 40.0), result.projectedSpendingPoints)
        assertEquals(2, result.upcomingItems.size)
    }

    @Test
    fun `no recurring patterns and no budget still returns sane defaults`() = runTest {
        val now = 1705320000000L
        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns SpendingPace(
            currentMonthSpent = 0.0,
            daysElapsed = 1,
            daysInMonth = 31,
            projectedTotal = 0.0,
            previousMonthTotal = null,
            averageMonthlyTotal = null,
            pacePercentage = 0f,
            paceStatus = PaceStatus.NO_BASELINE
        )
        coEvery { recurringExpenseEngine.getPatterns(any<List<Expense>>()) } returns emptyList()
        every { synthesisEngine.synthesize(any(), any(), any(), any(), any(), any()) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any()) } returns createMockNarrative()

        val result = repository.getFinancialWeather().first()
        assertEquals(WeatherState.CLEAR_SKIES, result.state)
        assertEquals(0.0, result.totalCommitted, 0.01)
        assertEquals(0.0, result.totalLikely, 0.01)
        assertTrue(result.upcomingItems.isEmpty())
        assertEquals(0, result.totalRecurringCount)
    }

    // =========================================================================
    // A.1 effectiveAmount regression — no raw-amount re-entry
    // =========================================================================

    @Test
    fun `daily cumulative spend uses effectiveAmount for shared fixed-share expense`() = runTest {
        val now = 1705320000000L // Jan 15, 2024
        val monthStart = 1704060000000L // Jan 1, 2024 00:00:00

        val expenses = listOf(
            // Regular purchase day 0: effectiveAmount = 10
            createExpense(amount = 10.0, date = monthStart, type = TransactionType.PURCHASE),
            // Shared fixed-share purchase day 1: raw = 100, effective = 40
            createSharedFixedExpense(rawAmount = 100.0, myShare = 40.0, date = monthStart + 86400000L),
            // Regular purchase today (day 14): effectiveAmount = 5
            createExpense(amount = 5.0, date = now, type = TransactionType.PURCHASE)
        )

        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        val spendingPace = SpendingPace(
            currentMonthSpent = 55.0, daysElapsed = 15, daysInMonth = 31,
            projectedTotal = 110.0, previousMonthTotal = null, averageMonthlyTotal = null,
            pacePercentage = 50.0f, paceStatus = PaceStatus.ON_PACE
        )
        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns spendingPace

        val capturedPastSum = slot<List<Double>>()
        every { synthesisEngine.synthesize(
            pastSumDaily = capture(capturedPastSum),
            recurringPatterns = any(), plannedExpenses = any(),
            savingsGoals = any(), budgetStatuses = any(), spendingPace = any()
        ) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        val pastSum = capturedPastSum.captured
        assertEquals(15, pastSum.size)
        assertEquals(10.0, pastSum[0], 0.01)  // Day 0: 10
        assertEquals(50.0, pastSum[1], 0.01)  // Day 1: 10 + 40 (NOT 10 + 100)
        assertEquals(50.0, pastSum[2], 0.01)  // Day 2: cumulative unchanged
        assertEquals(55.0, pastSum[14], 0.01) // Day 14: 50 + 5 = 55
    }

    @Test
    fun `daily cumulative spend uses effectiveAmount for percentage-based shared expense`() = runTest {
        val now = 1705320000000L // Jan 15, 2024
        val monthStart = 1704060000000L // Jan 1, 2024 00:00:00

        val expenses = listOf(
            // Percentage shared day 0: raw=100, 50% → effective=50
            createSharedPercentExpense(rawAmount = 100.0, sharePercent = 50, date = monthStart),
            // Regular purchase day 1: effective = 20
            createExpense(amount = 20.0, date = monthStart + 86400000L, type = TransactionType.PURCHASE)
        )

        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns SpendingPace(
            currentMonthSpent = 70.0, daysElapsed = 15, daysInMonth = 31,
            projectedTotal = 140.0, previousMonthTotal = null, averageMonthlyTotal = null,
            pacePercentage = 50.0f, paceStatus = PaceStatus.ON_PACE
        )

        val capturedPastSum = slot<List<Double>>()
        every { synthesisEngine.synthesize(
            pastSumDaily = capture(capturedPastSum),
            recurringPatterns = any(), plannedExpenses = any(),
            savingsGoals = any(), budgetStatuses = any(), spendingPace = any()
        ) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        val pastSum = capturedPastSum.captured
        assertEquals(50.0, pastSum[0], 0.01)  // Day 0: 50 (NOT 100)
        assertEquals(70.0, pastSum[1], 0.01)  // Day 1: 50 + 20 = 70
    }

    @Test
    fun `isNotMine expenses are excluded from daily cumulative spend and pace input`() = runTest {
        val now = 1705320000000L // Jan 15, 2024
        val monthStart = 1704060000000L // Jan 1, 2024 00:00:00

        val regularExpense = createExpense(amount = 25.0, date = monthStart, type = TransactionType.PURCHASE)
        val notMineExpense = createIsNotMineExpense(rawAmount = 500.0, date = monthStart + 86400000L)

        val expenses = listOf(regularExpense, notMineExpense)

        every { expenseRepository.getAllExpenses() } returns flowOf(expenses)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())

        coEvery { insightsEngine.getSpendingPaceSuspend(any<List<Expense>>()) } returns SpendingPace(
            currentMonthSpent = 25.0, daysElapsed = 15, daysInMonth = 31,
            projectedTotal = 50.0, previousMonthTotal = null, averageMonthlyTotal = null,
            pacePercentage = 50.0f, paceStatus = PaceStatus.ON_PACE
        )

        val capturedPastSum = slot<List<Double>>()
        val capturedPaceExpenses = slot<List<Expense>>()
        every { synthesisEngine.synthesize(
            pastSumDaily = capture(capturedPastSum),
            recurringPatterns = any(), plannedExpenses = any(),
            savingsGoals = any(), budgetStatuses = any(), spendingPace = any()
        ) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any()) } returns createMockNarrative()
        coEvery { insightsEngine.getSpendingPaceSuspend(capture(capturedPaceExpenses)) } returns SpendingPace(
            currentMonthSpent = 25.0, daysElapsed = 15, daysInMonth = 31,
            projectedTotal = 50.0, previousMonthTotal = null, averageMonthlyTotal = null,
            pacePercentage = 50.0f, paceStatus = PaceStatus.ON_PACE
        )

        repository.getFinancialWeather().first()

        // isNotMine purchase is filtered out from daily cumulative (line 144: !it.isNotMine)
        val pastSum = capturedPastSum.captured
        assertEquals(25.0, pastSum[0], 0.01) // Day 0: only the regular purchase
        assertEquals(25.0, pastSum[1], 0.01) // Day 1: isNotMine filtered out, stays at 25

        // isNotMine expense is also filtered out of pace input (line 165: !it.isNotMine)
        val paceExpenses = capturedPaceExpenses.captured
        assertTrue(
            "isNotMine expenses should be filtered from pace input",
            paceExpenses.none { it.isNotMine }
        )
    }

    private fun createExpense(amount: Double, date: Long, type: TransactionType): Expense {
        return Expense(
            amount = amount,
            merchant = "Test",
            date = date,
            transactionType = type
        )
    }

    private fun createSharedFixedExpense(rawAmount: Double, myShare: Double, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "SharedFixed",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isSharedExpense = true,
            myShareAmount = myShare
        )
    }

    private fun createSharedPercentExpense(rawAmount: Double, sharePercent: Int, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "SharedPercent",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isSharedExpense = true,
            mySharePercentage = sharePercent
        )
    }

    private fun createIsNotMineExpense(rawAmount: Double, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "NotMine",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isNotMine = true
        )
    }

    private fun createMockForecast(): FinancialForecast {
        val components = ForecastComponents(
            recurringExpenses = emptyList(),
            pastSpendingPoints = emptyList(),
            projectedSpendingPoints = emptyList(),
            totalCommitted = 0.0,
            totalLikely = 0.0,
            predictedDiscretionary = 0.0,
            discretionaryBudget = 0.0,
            riskLevel = RiskLevel.LOW
        )
        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = java.time.Instant.now(),
            confidence = 1.0,
            components = components,
            actionableInsights = emptyList()
        )
    }

    private fun createMockNarrative(): WeatherNarrative {
        return WeatherNarrative(
            state = WeatherState.CLEAR_SKIES,
            icon = "☀️",
            headline = "Clear",
            summary = "OK"
        )
    }
}
