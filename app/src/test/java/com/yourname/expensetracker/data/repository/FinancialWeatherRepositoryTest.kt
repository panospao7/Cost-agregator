package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
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
    private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val mergedRecurringPatternsProvider = mockk<MergedRecurringPatternsProvider>(relaxed = true)
    private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
    private val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
    private val synthesisEngine = mockk<SynthesisEngine>(relaxed = true)
    private val narrativeGenerator = mockk<NarrativeGenerator>(relaxed = true)
    private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var forecastInputAssembler: ForecastInputAssembler

    private lateinit var repository: FinancialWeatherRepository

    @Before
    fun setup() {
        every { timeProvider.now() } returns 1705320000000L // Jan 15, 2024
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true).also { mockNormalizer ->
            // Pass-through normalizer so buildPastSumDaily gets real expense data
            coEvery { mockNormalizer.normalizeSnapshots(any(), any()) } answers {
                val expenses = firstArg<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>()
                val homeCurrency = secondArg<String>()
                com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult(
                    homeCurrency = homeCurrency,
                    normalizedExpenses = expenses.map {
                        com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                    },
                    includedExpenses = expenses,
                    warnings = emptyList(),
                    latestRateTimestamp = null,
                    totalInputCount = expenses.size
                )
            }
        }
        forecastInputAssembler = ForecastInputAssembler(
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            currencyConverter = mockk<com.yourname.expensetracker.domain.currency.CurrencyConverter>(relaxed = true),
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true)
        )
        
        repository = FinancialWeatherRepository(
            expenseRepository,
            budgetRepository,
            recurringExpenseRepository,
            mergedRecurringPatternsProvider,
            plannedExpenseRepository,
            savingsGoalRepository,
            forecastInputAssembler,
            synthesisEngine,
            narrativeGenerator,
            analyticsRepository,
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            timeProvider = timeProvider
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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns emptyList()

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()

        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        // Act
        val weatherFlow = repository.getFinancialWeather()
        val result = weatherFlow.first()

        // Assert
        val pastSum = capturedInput.captured.pastSumDaily
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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

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
        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns recurring
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
        every {
            synthesisEngine.synthesize(any<ForecastInputAssembler.ForecastInput>())
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
        every { narrativeGenerator.generate(any(), any(), any()) } returns WeatherNarrative(
            state = WeatherState.RAINY,
            icon = "🌧️",
            headline = UiText.StringResource(R.string.domain_weather_headline_rainy_conditions),
            summary = UiText.StringResource(R.string.domain_weather_summary_rainy_conditions)
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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns emptyList()
        every { synthesisEngine.synthesize(any<ForecastInputAssembler.ForecastInput>()) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns emptyList()

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        val pastSum = capturedInput.captured.pastSumDaily
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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns emptyList()

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        val pastSum = capturedInput.captured.pastSumDaily
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
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns emptyList()

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        // isNotMine purchase is filtered out from daily cumulative (line 144: !it.isNotMine)
        val pastSum = capturedInput.captured.pastSumDaily
        assertEquals(25.0, pastSum[0], 0.01) // Day 0: only the regular purchase
        assertEquals(25.0, pastSum[1], 0.01) // Day 1: isNotMine filtered out, stays at 25

        // isNotMine expense is also excluded from computed spending pace
        assertEquals(25.0, capturedInput.captured.spendingPace.currentMonthSpent, 0.01)
    }

    @Test
    fun `getFinancialWeather merges recurring with manual precedence and confidence threshold`() = runTest {
        val now = 1705320000000L
        every { timeProvider.now() } returns now

        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        every {
            recurringExpenseRepository.getAllFlow()
        } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                    id = 1,
                    merchant = "Netflix",
                    amount = 15.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = now + 86_400_000L
                )
            )
        )
        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns listOf(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 15.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 86_400_000L,
                confidence = 1.0f,
                previousDates = emptyList()
            ),
            RecurringPattern(
                merchantName = "Gym",
                averageAmount = 35.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 86_400_000L,
                confidence = 0.8f,
                previousDates = emptyList()
            ),
        )

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        val byMerchant = capturedInput.captured.recurringPatterns.associateBy { it.merchantName }
        assertEquals(2, byMerchant.size)
        assertEquals(15.0, byMerchant.getValue("Netflix").averageAmount, 0.0001)
        assertEquals(1.0f, byMerchant.getValue("Netflix").confidence)
        assertTrue(byMerchant.containsKey("Gym"))
        assertTrue(!byMerchant.containsKey("Low"))
    }

    @Test
    fun `getFinancialWeather uses confirmed recurring only for forecast assembly`() = runTest {
        val now = 1705320000000L
        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
        every {
            recurringExpenseRepository.getAllFlow()
        } returns flowOf(
            listOf(
                com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                    id = 9,
                    merchant = "Confirmed Rent",
                    amount = 950.0,
                    frequency = RecurrenceFrequency.MONTHLY,
                    nextDate = now + 86_400_000L
                )
            )
        )
        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns listOf(
            RecurringPattern(
                id = 9,
                merchantName = "Confirmed Rent",
                averageAmount = 950.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 86_400_000L,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns createMockForecast()
        every { narrativeGenerator.generate(any(), any(), any()) } returns createMockNarrative()

        repository.getFinancialWeather().first()

        assertEquals(listOf("Confirmed Rent"), capturedInput.captured.recurringPatterns.map { it.merchantName })
        coVerify(exactly = 1) { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) }
        coVerify(exactly = 0) { mergedRecurringPatternsProvider.getConfirmedPatterns(any()) }
    }

    @Test
    fun `getConfirmedRecurringPatterns excludes unconfirmed merged suggestions`() = runTest {
        val now = 1705320000000L
        val manualRecurring = listOf(
            com.yourname.expensetracker.data.database.entity.ManualRecurringExpense(
                id = 9,
                merchant = "Confirmed Rent",
                amount = 950.0,
                frequency = RecurrenceFrequency.MONTHLY,
                nextDate = now + 86_400_000L
            )
        )
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(manualRecurring)
        every { mergedRecurringPatternsProvider.getConfirmedPatterns(manualRecurring) } returns listOf(
            RecurringPattern(
                id = 9,
                merchantName = "Confirmed Rent",
                averageAmount = 950.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 86_400_000L,
                confidence = 1.0f,
                previousDates = emptyList()
            )
        )
        coEvery { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) } returns listOf(
            RecurringPattern(
                merchantName = "Suggested Gym",
                averageAmount = 45.0,
                currency = "EUR",
                frequency = RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + 172_800_000L,
                confidence = 0.95f,
                previousDates = emptyList()
            )
        )

        val result = repository.getConfirmedRecurringPatterns().first()

        assertEquals(listOf("Confirmed Rent"), result.map { it.merchantName })
        verify(exactly = 1) { mergedRecurringPatternsProvider.getConfirmedPatterns(manualRecurring) }
        coVerify(exactly = 0) { mergedRecurringPatternsProvider.getPatternsFromSnapshots(any(), any()) }
    }

    private fun createExpense(amount: Double, date: Long, type: TransactionType): Expense {
        return Expense(
            amount = amount,
            merchant = "Test",
            date = date,
            transactionType = type,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createSharedFixedExpense(rawAmount: Double, myShare: Double, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "SharedFixed",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isSharedExpense = true,
            myShareAmount = myShare,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createSharedPercentExpense(rawAmount: Double, sharePercent: Int, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "SharedPercent",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isSharedExpense = true,
            mySharePercentage = sharePercent,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createIsNotMineExpense(rawAmount: Double, date: Long): Expense {
        return Expense(
            amount = rawAmount,
            merchant = "NotMine",
            date = date,
            transactionType = TransactionType.PURCHASE,
            isNotMine = true,
            createdAt = System.currentTimeMillis()
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
            generatedAt = java.time.Instant.ofEpochMilli(0L),
            confidence = 1.0,
            components = components,
            actionableInsights = emptyList()
        )
    }

    private fun createMockNarrative(): WeatherNarrative {
        return WeatherNarrative(
            state = WeatherState.CLEAR_SKIES,
            icon = "☀️",
            headline = UiText.StringResource(R.string.domain_weather_headline_clear_skies),
            summary = UiText.StringResource(
                R.string.domain_weather_summary_clear_skies_format,
                listOf("€0")
            )
        )
    }
}