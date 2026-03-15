package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class InsightsEngineEdgeCaseTest {
    
    private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    private val recurringEngine = mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var engine: InsightsEngine

    @Before
    fun setup() {
        val spendingPaceCalculator = mockk<SpendingPaceCalculator>(relaxed = true)
        val anomalyDetector = mockk<AnomalyDetector>(relaxed = true)
        val monthlyComparisonCalculator = mockk<MonthlyComparisonCalculator>(relaxed = true)
        val categoryInsightEngine = mockk<CategoryInsightEngine>(relaxed = true)
        val merchantInsightEngine = mockk<MerchantInsightEngine>(relaxed = true)
        val dayOfWeekAnalyzer = mockk<DayOfWeekAnalyzer>(relaxed = true)
        
        every { timeProvider.now() } returns System.currentTimeMillis()
        coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        
        engine = InsightsEngine(
            expenseRepository = expenseRepository,
            recurringExpenseEngine = recurringEngine,
            timeProvider = timeProvider,
            spendingPaceCalculator = spendingPaceCalculator,
            anomalyDetector = anomalyDetector,
            monthlyComparisonCalculator = monthlyComparisonCalculator,
            categoryInsightEngine = categoryInsightEngine,
            merchantInsightEngine = merchantInsightEngine,
            dayOfWeekAnalyzer = dayOfWeekAnalyzer
        )
        
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        coEvery { expenseRepository.getCountForPeriod(any(), any()) } returns 0
        coEvery { expenseRepository.getCategoryTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getAllMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getMerchantStats() } returns emptyList()
        coEvery { expenseRepository.getRecurringCandidates() } returns emptyList()
        coEvery { expenseRepository.getDayOfWeekPattern(any(), any(), any()) } returns emptyList()
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns emptyList()
        coEvery { expenseRepository.getLargestExpenseForPeriod(any(), any()) } returns null
    }

    @Test
    fun `empty expenses list returns valid snapshot with zeros`() = runBlocking {
        val categories = listOf(
            Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")
        )
        
        val snapshot = engine.generateInsights(categories, emptyList())
        
        assertNotNull("Snapshot should not be null", snapshot)
        assertEquals(0.0, snapshot.monthlyComparison.currentTotal, 0.01)
        assertEquals(0, snapshot.monthlyComparison.currentCount)
        assertTrue("Category insights should be empty", snapshot.categoryInsights.isEmpty())
        assertTrue("Top merchants should be empty", snapshot.topMerchants.isEmpty())
    }

    @Test
    fun `single expense does not crash engine`() = runBlocking {
        val categories = listOf(Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF"))
        val expenses = listOf(
            makeExpense(merchant = "Test", amount = 10.0, daysAgo = 5)
        )
        
        val snapshot = engine.generateInsights(categories, expenses)
        
        assertNotNull("Should handle single expense", snapshot)
    }

    @Test
    fun `leap year february calculations are correct`() {
        val cal = Calendar.getInstance()
        cal.set(2024, Calendar.FEBRUARY, 29)
        every { timeProvider.now() } returns cal.timeInMillis
        val period = engine.getMonthPeriod(cal.timeInMillis)
        
        assertEquals(2024, period.year)
        assertEquals(Calendar.FEBRUARY, period.month)
        
        // Month period spans exactly one month
        val periodCal = Calendar.getInstance()
        periodCal.timeInMillis = period.startMs
        assertEquals(2024, periodCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, periodCal.get(Calendar.MONTH))
        assertEquals(1, periodCal.get(Calendar.DAY_OF_MONTH))

        periodCal.timeInMillis = period.endMs
        assertEquals(2024, periodCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, periodCal.get(Calendar.MONTH))
        assertEquals(1, periodCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `negative amounts are handled in buildDailyTotals`() = runBlocking {
        val expenses = listOf(
            makeExpense(merchant = "Refund", amount = -50.0, daysAgo = 0),
            makeExpense(merchant = "Purchase", amount = 100.0, daysAgo = 0)
        )
        
        val totals = engine.buildDailyTotals(expenses, 1)
        
        val totalSpent = totals.values.sum()
        assertEquals(50.0, totalSpent, 0.01)
    }

    @Test
    fun `very large amounts do not overflow`() = runBlocking {
        val largeAmount = Double.MAX_VALUE / 10
        val expenses = listOf(
            makeExpense(merchant = "BigPurchase", amount = largeAmount, daysAgo = 1)
        )
        
        val totals = engine.buildDailyTotals(expenses, 7)
        assertNotNull("Large amounts should not cause crash", totals)
    }

    @Test
    fun `anomaly detection skips merchants with zero historical average`() = runBlocking {
        val now = System.currentTimeMillis()
        val expense = Expense(
            id = 99L,
            amount = 120.0,
            currency = "EUR",
            merchant = "Zero Avg Merchant",
            transactionType = TransactionType.PURCHASE,
            date = now - 86_400_000L
        )
        val stats = MerchantStats(
            merchantName = "zero_avg_merchant",
            displayName = "Zero Avg Merchant",
            totalAmount = 0.0,
            transactionCount = 5,
            averageAmount = 0.0,
            minAmount = 0.0,
            maxAmount = 0.0,
            firstDate = now - 10 * 86_400_000L,
            lastDate = now - 2 * 86_400_000L
        )
        val topCurrent = stats.copy(maxAmount = 120.0)

        coEvery { expenseRepository.getMerchantStats() } returns listOf(stats)
        coEvery { expenseRepository.getTopMerchantsForPeriod(any(), any(), any()) } returns listOf(topCurrent)
        coEvery { expenseRepository.getLargestExpenseForMerchant(any(), any(), any()) } returns expense

        val snapshot = engine.generateInsights(
            categories = listOf(Category(id = 1L, name = "Food", icon = "food", color = "#FFFFFF")),
            allExpenses = listOf(expense)
        )
        assertTrue("Zero-average merchants must not generate divide-by-zero anomalies", snapshot.anomalies.isEmpty())
    }

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * 86_400_000L
    )
}
