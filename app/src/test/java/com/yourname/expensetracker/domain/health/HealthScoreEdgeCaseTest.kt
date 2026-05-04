package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthScoreEdgeCaseTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var healthScoreHistoryDao: HealthScoreHistoryDao

    private lateinit var calculator: FinancialHealthScoreV2

    @Before
    override fun setUp() {
        super.setUp()

        budgetRepository = mockk(relaxed = true)
        expenseRepository = mockk(relaxed = true)
        savingsGoalRepository = mockk(relaxed = true)
        recurringExpenseEngine = mockk(relaxed = true)
        healthScoreHistoryDao = mockk(relaxed = true)
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")

        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()
        coEvery { healthScoreHistoryDao.getMostRecent() } returns null
        coEvery { healthScoreHistoryDao.getHistoryForPeriod(any(), any()) } returns emptyList()
        coEvery { healthScoreHistoryDao.insert(any()) } returns 1L
        coEvery { healthScoreHistoryDao.update(any()) } just runs
        coEvery { healthScoreHistoryDao.deleteOlderThan(any()) } returns 0

        calculator = FinancialHealthScoreV2(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            healthScoreHistoryDao = healthScoreHistoryDao,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository
            cashFlowCalculator = mock(),
        )
    }

    @Test
    fun `zero income keeps savings rate score neutral at fifty`() = runTest {
        // Arrange
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(
                id = 1L,
                amount = 420.0,
                type = TransactionType.PURCHASE,
                date = fixedNow
            )
        )

        // Act
        val result = calculator.calculateHealthScore()

        // Assert
        assertEquals(50, result.savingsRateScore)
        assertEquals(55, result.overallScore)
    }

    @Test
    fun `single category purchases with expenses above income floor savings score to zero`() = runTest {
        // Arrange
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(
                id = 2L,
                amount = 1_000.0,
                type = TransactionType.DEPOSIT,
                categoryId = null,
                date = fixedNow
            ),
            expense(
                id = 3L,
                amount = 1_200.0,
                type = TransactionType.PURCHASE,
                categoryId = 2L,
                date = fixedNow
            )
        )

        // Act
        val result = calculator.calculateHealthScore()

        // Assert
        assertEquals(0, result.savingsRateScore)
        assertEquals(40, result.overallScore)
    }

    @Test
    fun `new user asymmetry defaults bill reliability to seventy five and overall to fifty five`() = runTest {
        // Arrange
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()
        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()

        // Act
        val result = calculator.calculateHealthScore()

        // Assert
        assertEquals(50, result.savingsRateScore)
        assertEquals(50, result.runwayScore)
        assertEquals(50, result.budgetAdherenceScore)
        assertEquals(75, result.billReliabilityScore)
        assertEquals(55, result.overallScore)
        assertEquals(HealthTrend.STABLE, result.trend)
    }

    @Test
    fun `weighted score uses toInt truncation and does not round bug b zero four`() = runTest {
        // Arrange
        val midMonth = millis(2026, 4, 15)
        every { timeProvider.now() } returns midMonth

        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(
                id = 4L,
                amount = 1_000.0,
                type = TransactionType.DEPOSIT,
                categoryId = null,
                date = millis(2026, 4, 2)
            ),
            expense(
                id = 5L,
                amount = 801.0,
                type = TransactionType.PURCHASE,
                categoryId = 2L,
                date = millis(2026, 4, 10)
            )
        )

        every { budgetRepository.getBudgetStatuses() } returns flowOf(
            listOf(
                budgetStatus(
                    amount = 1_000.0,
                    spent = 100.0,
                    periodStart = millis(2026, 4, 1),
                    periodEnd = millis(2026, 4, 30)
                )
            )
        )

        coEvery { recurringExpenseEngine.getPatterns(any()) } returns listOf(
            recurringPattern(confidence = 0.95f, averageAmount = 120.0)
        )

        // Act
        val result = calculator.calculateHealthScore()

        // Assert
        val weightedRaw =
            result.savingsRateScore * 0.30 +
                result.runwayScore * 0.25 +
                result.budgetAdherenceScore * 0.25 +
                result.billReliabilityScore * 0.20

        assertEquals(99, result.savingsRateScore)
        assertEquals(0, result.runwayScore)
        assertEquals(100, result.budgetAdherenceScore)
        assertEquals(100, result.billReliabilityScore)
        assertApproxEquals(74.70, weightedRaw, 0.000001)
        assertEquals(74, result.overallScore)
    }

    @Test
    fun `deposit only period gives max savings score with neutral runway and budget`() = runTest {
        // Arrange
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns listOf(
            expense(
                id = 6L,
                amount = 2_500.0,
                type = TransactionType.DEPOSIT,
                categoryId = null,
                date = fixedNow
            ),
            expense(
                id = 7L,
                amount = 500.0,
                type = TransactionType.DEPOSIT,
                categoryId = null,
                date = fixedNow
            )
        )

        // Act
        val result = calculator.calculateHealthScore()

        // Assert
        assertEquals(100, result.savingsRateScore)
        assertEquals(50, result.runwayScore)
        assertEquals(50, result.budgetAdherenceScore)
        assertEquals(75, result.billReliabilityScore)
        assertEquals(70, result.overallScore)
    }

    private fun expense(
        id: Long,
        amount: Double,
        type: TransactionType,
        date: Long,
        categoryId: Long? = null
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            merchant = "M$id",
            transactionType = type,
            date = date,
            categoryId = categoryId,
            isNotMine = false
        )
    }

    private fun budgetStatus(
        amount: Double,
        spent: Double,
        periodStart: Long,
        periodEnd: Long
    ): BudgetStatus {
        return BudgetStatus(
            budget = Budget(
                id = 1L,
                categoryId = 2L,
                amount = amount,
                period = BudgetPeriod.MONTHLY,
                startDate = periodStart
            ),
            category = null,
            spentAmount = spent,
            remainingAmount = (amount - spent).coerceAtLeast(0.0),
            percentUsed = if (amount > 0.0) (spent / amount).toFloat() else 0f,
            healthStatus = BudgetHealthStatus.ON_TRACK,
            periodStart = periodStart,
            periodEnd = periodEnd
            effectiveLimit = 0.0,
        )
    }

    private fun recurringPattern(confidence: Float, averageAmount: Double): RecurringPattern {
        return RecurringPattern(
            merchantName = "Utility Bill",
            averageAmount = averageAmount,
            currency = "EUR",
            frequency = RecurrenceFrequency.MONTHLY,
            periodVarianceDays = 0,
            amountVariancePercent = 0.0,
            nextExpectedDate = fixedNow,
            confidence = confidence,
            previousDates = listOf(fixedNow - 30L * 24L * 60L * 60L * 1000L),
            categoryId = 5L
        )
    }

    private fun millis(year: Int, month: Int, day: Int): Long {
        return LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}