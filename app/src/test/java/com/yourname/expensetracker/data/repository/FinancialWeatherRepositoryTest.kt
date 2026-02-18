package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialWeatherRepositoryTest {

    private val notificationRepository = mockk<NotificationRepository>()
    private val insightsEngine = mockk<InsightsEngine>()
    private val budgetRepository = mockk<BudgetRepository>()
    private val recurringExpenseEngine = mockk<RecurringExpenseEngine>()
    private val recurringExpenseDao = mockk<RecurringExpenseDao>()
    private val plannedExpenseDao = mockk<PlannedExpenseDao>()
    private val savingsGoalDao = mockk<SavingsGoalDao>()
    private val synthesisEngine = mockk<SynthesisEngine>()
    private val narrativeGenerator = mockk<NarrativeGenerator>()
    private val analyticsRepository = mockk<AnalyticsRepository>()
    private val timeProvider = mockk<TimeProvider>()

    private lateinit var repository: FinancialWeatherRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        // Mock shared data - must be set up BEFORE repository construction
        // because recurringPatternsFlow is initialized at construction time
        every { timeProvider.now() } returns 1705320000000L // Jan 15, 2024
        every { notificationRepository.getAllExpenses() } returns flowOf(emptyList())
        every { recurringExpenseDao.getAllFlow() } returns flowOf(emptyList())
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        repository = FinancialWeatherRepository(
            notificationRepository,
            insightsEngine,
            budgetRepository,
            recurringExpenseEngine,
            recurringExpenseDao,
            plannedExpenseDao,
            savingsGoalDao,
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

        every { notificationRepository.getAllExpenses() } returns flowOf(expenses)
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { plannedExpenseDao.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalDao.getAllGoals() } returns flowOf(emptyList())

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
        coEvery { insightsEngine.getSpendingPaceSuspend(any()) } returns spendingPace

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

    private fun createExpense(amount: Double, date: Long, type: TransactionType): Expense {
        return Expense(
            amount = amount,
            merchant = "Test",
            date = date,
            transactionType = type
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
