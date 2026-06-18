package com.yourname.expensetracker.domain.usecase.forecast

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.FinancialForecast
import com.yourname.expensetracker.domain.model.ForecastComponents
import com.yourname.expensetracker.domain.model.ForecastHorizon
import com.yourname.expensetracker.domain.model.GoalProtectionLevel
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RiskLevel
import com.yourname.expensetracker.domain.model.SavingsGoal
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Calendar

class CalculateFinancialForecastUseCaseTest {

    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var plannedExpenseRepository: PlannedExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var budgetRepository: BudgetRepository
    private lateinit var recurringPatternsProvider: MergedRecurringPatternsProvider
    private lateinit var forecastInputAssembler: ForecastInputAssembler
    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var timeBoundaryTicker: TimeBoundaryTicker
    private lateinit var timeProvider: TimeProvider

    private lateinit var useCase: CalculateFinancialForecastUseCase

    @Before
    fun setup() {
        expenseRepository = mockk()
        recurringExpenseRepository = mockk()
        plannedExpenseRepository = mockk()
        savingsGoalRepository = mockk()
        budgetRepository = mockk()
        recurringPatternsProvider = mockk()
        synthesisEngine = mockk()
        timeBoundaryTicker = mockk()
        timeProvider = mockk()

        val mockAnalyticsCurrencyNormalizer = mockk<com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer>(relaxed = true)
        val mockCurrencySettingsRepository = mockk<com.yourname.expensetracker.domain.currency.CurrencySettingsRepository>(relaxed = true)

        every { mockCurrencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { mockCurrencySettingsRepository.resolveHomeCurrency() } returns
            com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved(
                com.yourname.expensetracker.domain.core.money.CurrencyCode("EUR")
            )
        coEvery {
            mockAnalyticsCurrencyNormalizer.normalizeSnapshots(any(), any())
        } coAnswers {
            val snapshots = firstArg<List<ExpenseSnapshot>>()
            AnalyticsNormalizationResult(
                homeCurrency = "EUR",
                normalizedExpenses = emptyList(),
                includedExpenses = snapshots,
                warnings = emptyList(),
                latestRateTimestamp = null,
                totalInputCount = snapshots.size
            )
        }

        forecastInputAssembler = ForecastInputAssembler(
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = mockAnalyticsCurrencyNormalizer,
            currencySettingsRepository = mockCurrencySettingsRepository,
            currencyConverter = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            recurringOccurrenceDao = mockk(relaxed = true),
            databaseReadBarrier = mockk(relaxed = true)
        )

        every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(0L)
        every { timeProvider.now() } returns ms(2026, Calendar.JANUARY, 15, 12)
        every { recurringPatternsProvider.getConfirmedPatterns(any()) } returns emptyList()

        useCase = CalculateFinancialForecastUseCase(
            expenseRepository = expenseRepository,
            recurringExpenseRepository = recurringExpenseRepository,
            plannedExpenseRepository = plannedExpenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            budgetRepository = budgetRepository,
            recurringPatternsProvider = recurringPatternsProvider,
            forecastInputAssembler = forecastInputAssembler,
            synthesisEngine = synthesisEngine,
            timeBoundaryTicker = timeBoundaryTicker
        )
    }

    @Test
    fun `invoke currentMonthSpent includes only month-to-date purchases owned by user`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val monthStart = ms(2026, Calendar.JANUARY, 1, 0)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(
                expense(amount = 50.0, type = TransactionType.PURCHASE, date = monthStart + 2 * DAY_MS),
                expense(amount = 30.0, type = TransactionType.PURCHASE, date = now),
                expense(amount = 1_000.0, type = TransactionType.DEPOSIT, date = monthStart + 3 * DAY_MS),
                expense(amount = 200.0, type = TransactionType.TRANSFER, date = monthStart + 4 * DAY_MS),
                expense(
                    amount = 80.0,
                    type = TransactionType.PURCHASE,
                    date = monthStart + 5 * DAY_MS,
                    isNotMine = true
                ),
                expense(amount = 999.0, type = TransactionType.PURCHASE, date = monthStart - DAY_MS),
                expense(amount = 123.0, type = TransactionType.PURCHASE, date = now + DAY_MS)
            )
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        assertEquals(80.0, capturedInput.captured.spendingPace.currentMonthSpent, 0.0001)
    }

    @Test
    fun `invoke preserves mixed planned expense priorities in synthesis input`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(emptyList())
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        val must = PlannedExpense(
            id = 1,
            description = "Rent",
            amount = 1200.0,
            date = now + DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.MUST
        )
        val likely = PlannedExpense(
            id = 2,
            description = "Groceries",
            amount = 200.0,
            date = now + 2 * DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
        )
        val optional = PlannedExpense(
            id = 3,
            description = "Headphones",
            amount = 80.0,
            date = now + 3 * DAY_MS,
            categoryId = null,
            isRecurring = false,
            priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.OPTIONAL
        )
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(listOf(must, likely, optional))

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        val prioritiesById = capturedInput.captured.plannedExpenses.associate { it.id to it.priority }
        assertEquals(PlannedExpensePriority.MUST, prioritiesById[must.id])
        assertEquals(PlannedExpensePriority.LIKELY, prioritiesById[likely.id])
        assertEquals(PlannedExpensePriority.OPTIONAL, prioritiesById[optional.id])
    }

    @Test
    fun `invoke uses shared early projection path instead of legacy times three heuristic`() = runTest {
        val now = ms(2026, Calendar.APRIL, 2, 12)
        val monthStart = ms(2026, Calendar.APRIL, 1, 0)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(
                expense(amount = 200.0, type = TransactionType.PURCHASE, date = monthStart)
            )
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        assertEquals(1200.0, capturedInput.captured.spendingPace.projectedTotal, 0.0001)
        assertEquals(2, capturedInput.captured.spendingPace.daysElapsed)
    }

    @Test
    fun `invoke passes cumulative history real pace and mapped goal protection to synthesis`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
        val monthStart = ms(2026, Calendar.JANUARY, 1, 0)
        val previousMonthStart = ms(2025, Calendar.DECEMBER, 1, 0)

        every { timeProvider.now() } returns now
        every { expenseRepository.getAllExpenses() } returns flowOf(
            listOf(
                expense(amount = 10.0, type = TransactionType.PURCHASE, date = monthStart),
                expense(amount = 15.0, type = TransactionType.PURCHASE, date = monthStart + DAY_MS),
                expense(amount = 25.0, type = TransactionType.PURCHASE, date = monthStart + DAY_MS),
                expense(amount = 31.0, type = TransactionType.PURCHASE, date = previousMonthStart + 2 * DAY_MS),
                expense(amount = 31.0, type = TransactionType.PURCHASE, date = previousMonthStart + 8 * DAY_MS),
                expense(amount = 31.0, type = TransactionType.PURCHASE, date = previousMonthStart + 15 * DAY_MS),
                expense(amount = 31.0, type = TransactionType.PURCHASE, date = previousMonthStart + 22 * DAY_MS),
                expense(amount = 31.0, type = TransactionType.PURCHASE, date = previousMonthStart + 28 * DAY_MS),
                expense(amount = 999.0, type = TransactionType.PURCHASE, date = now + DAY_MS)
            )
        )
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
        every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(emptyList())
        every {
            savingsGoalRepository.observeSavingsGoals()
        } returns flowOf(
            listOf(
                SavingsGoal(
                    id = 1L,
                    name = "Emergency",
                    targetAmount = 1000.0,
                    currentAmount = 100.0,
                    targetDate = now + 10 * DAY_MS,
                    protectionLevel = GoalProtectionLevel.STRICT,
                    createdAt = now - DAY_MS
                )
            )
        )

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        assertEquals(listOf(10.0, 50.0), capturedInput.captured.pastSumDaily.take(2))
        assertEquals(GoalProtectionLevel.STRICT, capturedInput.captured.savingsGoals.single().protectionLevel)
        assertEquals(50.0, capturedInput.captured.spendingPace.currentMonthSpent, 0.0001)
        assertEquals(155.0, capturedInput.captured.spendingPace.previousMonthTotal ?: 0.0, 0.0001)
        assertEquals(com.yourname.expensetracker.domain.analytics.PaceStatus.UNDER_PACE, capturedInput.captured.spendingPace.paceStatus)
    }

    @Test
    fun `invoke merges recurring as manual plus high-confidence detected with manual precedence`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
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
                    frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                    nextDate = now + DAY_MS
                )
            )
        )

        every { recurringPatternsProvider.getConfirmedPatterns(any()) } returns listOf(
            RecurringPattern(
                merchantName = "Netflix",
                averageAmount = 99.0,
                currency = "EUR",
                frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 1,
                amountVariancePercent = 0.1,
                nextExpectedDate = now + DAY_MS,
                confidence = 0.95f,
                previousDates = emptyList()
            ),
            RecurringPattern(
                merchantName = "Gym",
                averageAmount = 30.0,
                currency = "EUR",
                frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 1,
                amountVariancePercent = 0.1,
                nextExpectedDate = now + DAY_MS,
                confidence = 0.85f,
                previousDates = emptyList()
            ),
            RecurringPattern(
                merchantName = "LowConfidence",
                averageAmount = 40.0,
                currency = "EUR",
                frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 1,
                amountVariancePercent = 0.1,
                nextExpectedDate = now + DAY_MS,
                confidence = 0.60f,
                previousDates = emptyList()
            )
        )

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        val recurringByMerchant = capturedInput.captured.recurringPatterns.associateBy { it.merchantName }
        assertEquals(2, capturedInput.captured.recurringPatterns.size)
        assertEquals(15.0, recurringByMerchant.getValue("Netflix").averageAmount, 0.0001)
        assertEquals(1.0f, recurringByMerchant.getValue("Netflix").confidence)
        assertEquals(30.0, recurringByMerchant.getValue("Gym").averageAmount, 0.0001)
        assertTrue("Low confidence detected patterns should be excluded", !recurringByMerchant.containsKey("LowConfidence"))
    }

    @Test
    fun `invoke uses confirmed recurring only and ignores unconfirmed suggestions`() = runTest {
        val now = ms(2026, Calendar.JANUARY, 15, 12)
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
                    id = 10,
                    merchant = "Confirmed Utility",
                    amount = 75.0,
                    frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                    nextDate = now + DAY_MS
                )
            )
        )
        every { recurringPatternsProvider.getConfirmedPatterns(any()) } returns listOf(
            RecurringPattern(
                merchantName = "Confirmed Utility",
                averageAmount = 75.0,
                currency = "EUR",
                frequency = com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY,
                periodVarianceDays = 0,
                amountVariancePercent = 0.0,
                nextExpectedDate = now + DAY_MS,
                confidence = 1.0f,
                previousDates = emptyList(),
                id = 10
            )
        )

        val capturedInput = slot<ForecastInputAssembler.ForecastInput>()
        every { synthesisEngine.synthesize(capture(capturedInput)) } returns dummyForecast(now)

        useCase.invoke().first()

        assertEquals(listOf("Confirmed Utility"), capturedInput.captured.recurringPatterns.map { it.merchantName })
    }

    private fun expense(
        amount: Double,
        type: TransactionType,
        date: Long,
        isNotMine: Boolean = false
    ): Expense {
        return Expense(
            amount = amount,
            merchant = "M",
            transactionType = type,
            date = date,
            isNotMine = isNotMine
        )
    }

    private fun dummyForecast(now: Long): FinancialForecast {
        return FinancialForecast(
            horizon = ForecastHorizon.REST_OF_MONTH,
            generatedAt = Instant.ofEpochMilli(now),
            confidence = 1.0,
            components = ForecastComponents(
                recurringExpenses = emptyList(),
                pastSpendingPoints = emptyList(),
                projectedSpendingPoints = emptyList(),
                totalCommitted = 0.0,
                totalLikely = 0.0,
                predictedDiscretionary = 0.0,
                discretionaryBudget = 0.0,
                riskLevel = RiskLevel.LOW
            ),
            actionableInsights = emptyList()
        )
    }

    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}