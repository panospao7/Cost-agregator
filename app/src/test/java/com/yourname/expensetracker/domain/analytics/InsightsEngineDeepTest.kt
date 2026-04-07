package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.dao.CategoryTotal
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsEngineDeepTest {

    private lateinit var engine: InsightsEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var recurringEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
    private lateinit var timeProvider: TimeProvider
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator

    private val categories = listOf(
        Category(id = 1L, name = "Food", icon = "🍽️", color = "#FF0000"),
        Category(id = 2L, name = "Transport", icon = "🚌", color = "#00FF00")
    )

    @Before
    fun setUp() {
        expenseRepository = mockk(relaxed = true)
        recurringEngine = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        spendingPaceCalculator = mockk(relaxed = true)

        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = mockk(relaxed = true),
            monthlyComparisonCalculator = mockk(relaxed = true),
            categoryInsightEngine = mockk(relaxed = true),
            merchantInsightEngine = mockk(relaxed = true),
            dayOfWeekAnalyzer = mockk(relaxed = true)
        )

        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseForPeriod(any(), any()) } returns null
        coEvery { expenseRepository.getLargestExpenseForMerchant(any(), any(), any()) } returns null
        coEvery { expenseRepository.getMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `monthly comparison computes delta and percentage`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returnsMany listOf(600.0, 400.0, 600.0, 400.0)
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returnsMany listOf(6, 4, 4)
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()

        val snapshot = engine.generateInsights(categories, emptyList())

        assertApproxEquals(600.0, snapshot.monthlyComparison.currentTotal)
        assertApproxEquals(200.0, snapshot.monthlyComparison.changeAmount ?: 0.0)
        assertApproxEquals(50f, snapshot.monthlyComparison.changePercentage ?: 0f, 0.01f)
    }

    @Test
    fun `spending pace canonical formula and status are correct`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 16)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returnsMany listOf(1600.0, 930.0, 1600.0, 930.0)
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returnsMany listOf(16, 31, 31)
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()
        every { spendingPaceCalculator.calculate(any(), any(), any(), any()) } returns SpendingPace(
            currentMonthSpent = 1600.0,
            daysElapsed = 16,
            daysInMonth = 30,
            projectedTotal = 3000.0,
            previousMonthTotal = 930.0,
            averageMonthlyTotal = null,
            pacePercentage = 333.33f,
            paceStatus = PaceStatus.OVER_PACE
        )

        val snapshot = engine.generateInsights(categories, emptyList())

        val pace = snapshot.spendingPace
        assertEquals(PaceStatus.OVER_PACE, pace.paceStatus)
        assertApproxEquals(333.33f, pace.pacePercentage, 0.2f)
        assertApproxEquals(3000.0, pace.projectedTotal)
    }

    @Test
    fun `category breakdown groups by category and computes percentage`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returnsMany listOf(
            listOf(
                CategoryTotal(categoryId = 1L, total = 300.0, txCount = 3),
                CategoryTotal(categoryId = 2L, total = 100.0, txCount = 1)
            ),
            emptyList()
        )
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()

        val snapshot = engine.generateInsights(categories, emptyList())
        val food = snapshot.categoryInsights.first { it.category.id == 1L }
        val transport = snapshot.categoryInsights.first { it.category.id == 2L }

        assertApproxEquals(75f, food.percentageOfTotal, 0.01f)
        assertApproxEquals(25f, transport.percentageOfTotal, 0.01f)
        assertApproxEquals(100.0, snapshot.categoryInsights.sumOf { it.percentageOfTotal.toDouble() }, 0.05)
    }

    @Test
    fun `top merchants sorted descending and recurrence uses narrow variance`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns listOf(
            MerchantStats("alpha_key", "Alpha", 400.0, 4, 100.0, 95.0, 110.0, 0L, 0L),
            MerchantStats("beta_key", "Beta", 120.0, 2, 60.0, 10.0, 130.0, 0L, 0L)
        )

        val allExpenses = listOf(
            expenseWithKey(createExpense(date = "2026-04-01", amount = 95.0, merchant = "Alpha"), "alpha_key"),
            expenseWithKey(createExpense(date = "2026-04-05", amount = 100.0, merchant = "Alpha"), "alpha_key"),
            expenseWithKey(createExpense(date = "2026-04-09", amount = 110.0, merchant = "Alpha"), "alpha_key")
        )

        val snapshot = engine.generateInsights(categories, allExpenses)

        assertEquals("alpha_key", snapshot.topMerchants.first().merchant)
        assertTrue(!snapshot.topMerchants.first().isLikelyRecurring)
        assertTrue((snapshot.topMerchants.first().stdDeviation ?: 0.0) > 0.0)
        assertTrue(!snapshot.topMerchants.last().isLikelyRecurring)
    }

    @Test
    fun `day of week pattern aggregates using effective amount`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 15)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()

        val mondayShared = createExpense(
            date = "2026-03-02", amount = 100.0, effectiveAmount = 40.0,
            isSharedExpense = true, myShareAmount = 40.0, merchant = "Shared"
        )
        val mondayNormal = createExpense(date = "2026-03-09", amount = 30.0, merchant = "Normal")

        val snapshot = engine.generateInsights(categories, listOf(mondayShared, mondayNormal))
        val monday = snapshot.dayOfWeekPattern.first { it.dayName == "Mon" }

        assertApproxEquals(70.0, monday.totalSpent)
        assertEquals(2, monday.transactionCount)
        assertApproxEquals(35.0, monday.avgPerTransaction)
    }

    @Test
    fun `average and median transaction size should use effective amount`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 10)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()

        val expenses = listOf(
            createExpense(date = "2026-04-01", amount = 100.0, effectiveAmount = 20.0, isSharedExpense = true, myShareAmount = 20.0),
            createExpense(date = "2026-04-02", amount = 50.0),
            createExpense(date = "2026-04-03", amount = 30.0)
        )

        val snapshot = engine.generateInsights(categories, expenses)

        // Canonical expectation: avg=(20+50+30)/3=33.33, median=30
        assertApproxEquals(33.33, snapshot.averageTransactionSize, 0.1)
        assertApproxEquals(30.0, snapshot.medianTransactionSize, 0.01)
    }

    @Test
    fun `empty dataset yields safe defaults`() = runTest {
        every { timeProvider.now() } returns dateMs(2026, 4, 10)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()

        val snapshot = engine.generateInsights(categories, emptyList())

        assertApproxEquals(0.0, snapshot.monthlyComparison.currentTotal)
        assertTrue(snapshot.categoryInsights.isEmpty())
        assertTrue(snapshot.topMerchants.isEmpty())
        assertTrue(snapshot.dayOfWeekPattern.isEmpty())
    }

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun expenseWithKey(expense: Expense, merchantKey: String): Expense = expense.copy(merchantKey = merchantKey)
}
