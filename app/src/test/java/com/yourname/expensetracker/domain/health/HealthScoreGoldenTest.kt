package com.yourname.expensetracker.domain.health

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HealthScoreGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var budgetRepository: BudgetRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var savingsGoalRepository: SavingsGoalRepository
    private lateinit var recurringExpenseEngine: RecurringExpenseEngine
    private lateinit var healthScoreHistoryDao: HealthScoreHistoryDao
    private lateinit var engine: FinancialHealthScoreV2

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
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
        every { savingsGoalRepository.getAllGoals() } returns flowOf(emptyList())
        coEvery { recurringExpenseEngine.getPatterns(any()) } returns emptyList()
        coEvery { healthScoreHistoryDao.getMostRecent() } returns null
        coEvery { healthScoreHistoryDao.getHistoryForPeriod(any(), any()) } returns emptyList()

        engine = FinancialHealthScoreV2(
            budgetRepository = budgetRepository,
            expenseRepository = expenseRepository,
            savingsGoalRepository = savingsGoalRepository,
            recurringExpenseEngine = recurringExpenseEngine,
            healthScoreHistoryDao = healthScoreHistoryDao,
            timeProvider = timeProvider,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            cashFlowCalculator = mockk(),
            writeBarrier = mockk(relaxed = true),
        )
    }

    @Test
    fun `golden march with no budgets and no goals returns overall score 57 and stable trend`() = runTest {
        every { timeProvider.now() } returns atTime("2026-03-31", 23, 59, 59)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns goldenMarchExpenses()

        val result = engine.calculateHealthScore()

        assertApproxEquals(50.0, result.savingsRateScore.toDouble(), 0.01)
        assertApproxEquals(50.0, result.runwayScore.toDouble(), 0.01)
        assertApproxEquals(50.0, result.budgetAdherenceScore.toDouble(), 0.01)
        assertApproxEquals(75.0, result.billReliabilityScore.toDouble(), 0.01)
        assertApproxEquals(55.0, result.overallScore.toDouble(), 0.01)
        assertEquals(HealthTrend.IMPROVING, result.trend)
    }

    @Test
    fun `new user with no data returns default asymmetry overall score 55`() = runTest {
        every { timeProvider.now() } returns atTime("2026-04-01", 12, 0, 0)
        coEvery { expenseRepository.getExpensesBetween(any(), any()) } returns emptyList()

        val result = engine.calculateHealthScore()

        assertApproxEquals(50.0, result.savingsRateScore.toDouble(), 0.01)
        assertApproxEquals(50.0, result.runwayScore.toDouble(), 0.01)
        assertApproxEquals(50.0, result.budgetAdherenceScore.toDouble(), 0.01)
        assertApproxEquals(75.0, result.billReliabilityScore.toDouble(), 0.01)
        assertApproxEquals(55.0, result.overallScore.toDouble(), 0.01)
        assertEquals(HealthTrend.IMPROVING, result.trend)
    }

    private fun goldenMarchExpenses() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
        createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
        createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
        createExpense("2026-03-20", 89.90, merchant = "Zara", id = 10L),
        createExpense("2026-03-22", 12.30, merchant = "Pharmacy", id = 11L),
        createExpense(
            "2026-03-25",
            35.00,
            effectiveAmount = 17.50,
            merchant = "Friend Lunch",
            category = "dining",
            id = 12L,
            isSharedExpense = true,
            mySharePercentage = 50
        ),
        createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L)
    )

    private fun atTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = com.yourname.expensetracker.dateToMillis(date)
        return java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}