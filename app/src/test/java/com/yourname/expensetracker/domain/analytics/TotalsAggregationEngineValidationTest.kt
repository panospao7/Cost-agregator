package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.dao.DailyTotal
import com.yourname.expensetracker.data.database.dao.MonthlyTotal
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Validation tests for TotalsAggregationEngine to ensure all aggregation
 * calculations are mathematically correct.
 * 
 * Tests cover:
 * 1. Known data verification with manual sum
 * 2. Period boundary transactions
 * 3. Empty period handling
 * 4. Daily average calculations
 * 5. Category breakdown percentages
 */
class TotalsAggregationEngineValidationTest {

    private lateinit var engine: TotalsAggregationEngine
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: TimeProvider

    // Helper to create timestamps for specific dates
    private fun createDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // Helper to create test expenses
    private fun createExpense(
        id: Long = 0,
        amount: Double,
        date: Long,
        categoryId: Long? = null,
        transactionType: TransactionType = TransactionType.PURCHASE
    ): Expense {
        return Expense(
            id = id,
            amount = amount,
            currency = "EUR",
            merchant = "Test Merchant",
            transactionType = transactionType,
            date = date,
            categoryId = categoryId
        )
    }

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        engine = TotalsAggregationEngine(expenseRepository, timeProvider, Dispatchers.Unconfined)
    }

    // ========== SCENARIO 1: Known Data Verification ==========

    @Test
    fun `monthly totals sum correctly`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock monthly totals for current year
        val monthlyTotals = listOf(
            MonthlyTotal(
                monthKey = "2024-01",
                startDate = createDate(2024, 1, 1),
                endDate = createDate(2024, 2, 1),
                total = 1500.0,
                txCount = 15
            ),
            MonthlyTotal(
                monthKey = "2024-02",
                startDate = createDate(2024, 2, 1),
                endDate = createDate(2024, 3, 1),
                total = 2000.0,
                txCount = 20
            ),
            MonthlyTotal(
                monthKey = "2024-03",
                startDate = createDate(2024, 3, 1),
                endDate = createDate(2024, 4, 1),
                total = 1800.0,
                txCount = 18
            )
        )
        
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 5300.0
        
        // When: Get monthly totals for 2024
        val result = engine.getMonthlyTotals(2024)
        
        // Then: Verify totals match expected values
        assertEquals(3, result.size)
        assertEquals(1500.0, result[0].totalAmount, 0.01)
        assertEquals(2000.0, result[1].totalAmount, 0.01)
        assertEquals(1800.0, result[2].totalAmount, 0.01)
        
        // Verify total sum
        val totalSum = result.sumOf { it.totalAmount }
        assertEquals(5300.0, totalSum, 0.01)
    }

    @Test
    fun `daily totals sum correctly for a week`() = runTest {
        // Given: Fixed reference date (April 15, 2024 - Monday)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock daily totals for a week
        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = createDate(2024, 4, 15),
                startDate = createDate(2024, 4, 15),
                endDate = createDate(2024, 4, 16),
                total = 100.0,
                txCount = 2
            ),
            DailyTotal(
                dayEpoch = createDate(2024, 4, 16),
                startDate = createDate(2024, 4, 16),
                endDate = createDate(2024, 4, 17),
                total = 150.0,
                txCount = 3
            ),
            DailyTotal(
                dayEpoch = createDate(2024, 4, 17),
                startDate = createDate(2024, 4, 17),
                endDate = createDate(2024, 4, 18),
                total = 200.0,
                txCount = 4
            )
        )
        
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        
        // When: Get daily totals for a week
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 22)
        val result = engine.getDailyTotalsForRange(startMs, endMs)
        
        // Then: Verify totals match expected values
        assertEquals(3, result.size)
        assertEquals(100.0, result[0].totalAmount, 0.01)
        assertEquals(150.0, result[1].totalAmount, 0.01)
        assertEquals(200.0, result[2].totalAmount, 0.01)
        
        // Verify total sum
        val totalSum = result.sumOf { it.totalAmount }
        assertEquals(450.0, totalSum, 0.01)
    }

    // ========== SCENARIO 2: Period Boundary Testing ==========

    @Test
    fun `transaction at midnight is included in correct day`() = runTest {
        // Given: Transaction at exactly midnight
        val transactionDate = createDate(2024, 4, 15, 0, 0)
        
        // Mock daily totals that include this transaction
        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = createDate(2024, 4, 15),
                startDate = createDate(2024, 4, 15),
                endDate = createDate(2024, 4, 16),
                total = 100.0,
                txCount = 1
            )
        )
        
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        
        // When: Get daily totals for the day containing midnight
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 16)
        val result = engine.getDailyTotalsForRange(startMs, endMs)
        
        // Then: Transaction should be included in April 15
        assertEquals(1, result.size)
        assertEquals(createDate(2024, 4, 15), result[0].startDateMs)
    }

    @Test
    fun `transaction at 23_59_59 is included in correct day`() = runTest {
        // Given: Transaction at 23:59:59
        val cal = Calendar.getInstance()
        cal.set(2024, 3, 15, 23, 59, 59) // April 15
        cal.set(Calendar.MILLISECOND, 999)
        val transactionDate = cal.timeInMillis
        
        // Mock daily totals that include this transaction
        val dailyTotals = listOf(
            DailyTotal(
                dayEpoch = createDate(2024, 4, 15),
                startDate = createDate(2024, 4, 15),
                endDate = createDate(2024, 4, 16),
                total = 100.0,
                txCount = 1
            )
        )
        
        coEvery { expenseRepository.getDailyTotalsWithDatesForPeriod(any(), any()) } returns dailyTotals
        
        // When: Get daily totals for the day containing 23:59:59
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 16)
        val result = engine.getDailyTotalsForRange(startMs, endMs)
        
        // Then: Transaction should be included in April 15
        assertEquals(1, result.size)
        assertEquals(createDate(2024, 4, 15), result[0].startDateMs)
    }

    // ========== SCENARIO 3: Empty Period Handling ==========

    @Test
    fun `empty period returns zero totals`() = runTest {
        // Given: No transactions in period
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns emptyList()
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } returns 0.0
        
        // When: Get monthly totals
        val result = engine.getMonthlyTotals(2024)
        
        // Then: Should return empty list
        assertTrue(result.isEmpty())
    }

    @Test
    fun `category breakdown handles zero grand total`() = runTest {
        // Given: No transactions (grand total = 0)
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns emptyList()
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024")
        
        // Then: Should return empty list
        assertTrue(result.isEmpty())
    }

    // ========== SCENARIO 4: Daily Average Calculations ==========

    @Test
    fun `average calculation for period type DAY is correct`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock average daily spend
        coEvery { expenseRepository.getAverageDailySpend(any(), any()) } returns 150.0
        
        // When: Calculate average for DAY period type
        val average = engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)
        
        // Then: Should return mocked average
        assertEquals(150.0, average, 0.01)
    }

    @Test
    fun `average calculation for period type MONTH is correct`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock monthly totals for last 12 months
        val monthlyTotals = listOf(
            MonthlyTotal(
                monthKey = "2023-05",
                startDate = createDate(2023, 5, 1),
                endDate = createDate(2023, 6, 1),
                total = 1000.0,
                txCount = 10
            ),
            MonthlyTotal(
                monthKey = "2023-06",
                startDate = createDate(2023, 6, 1),
                endDate = createDate(2023, 7, 1),
                total = 1200.0,
                txCount = 12
            ),
            MonthlyTotal(
                monthKey = "2023-07",
                startDate = createDate(2023, 7, 1),
                endDate = createDate(2023, 8, 1),
                total = 1100.0,
                txCount = 11
            )
        )
        
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals
        
        // When: Calculate average for MONTH period type
        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)
        
        // Then: Should be (1000 + 1200 + 1100) / 3 = 1100
        assertEquals(1100.0, average, 0.01)
    }

    @Test
    fun `average calculation excludes current month when requested`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock monthly totals for last 12 months, with current month
        val monthlyTotals = listOf(
            MonthlyTotal(
                monthKey = "2023-05",
                startDate = createDate(2023, 5, 1),
                endDate = createDate(2023, 6, 1),
                total = 1000.0,
                txCount = 10
            ),
            MonthlyTotal(
                monthKey = "2023-06",
                startDate = createDate(2023, 6, 1),
                endDate = createDate(2023, 7, 1),
                total = 1200.0,
                txCount = 12
            ),
            MonthlyTotal(
                monthKey = "2024-04",
                startDate = createDate(2024, 4, 1),
                endDate = createDate(2024, 5, 1),
                total = 800.0, // Current month (partial)
                txCount = 8
            )
        )
        
        coEvery { expenseRepository.getMonthlyTotalsForPeriod(any(), any()) } returns monthlyTotals
        
        // When: Calculate average for MONTH period type, excluding current month
        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = true)
        
        // Then: Should be (1000 + 1200) / 2 = 1100 (excluding current month)
        assertEquals(1100.0, average, 0.01)
    }

    // ========== SCENARIO 5: Category Breakdown Percentages ==========

    @Test
    fun `category breakdown percentages sum to 100`() = runTest {
        // Given: Category breakdown results
        val categoryResults = listOf(
            CategoryTotalResult(
                id = 1,
                name = "Food",
                icon = "food",
                color = "#FF0000",
                total = 500.0,
                txCount = 5
            ),
            CategoryTotalResult(
                id = 2,
                name = "Transport",
                icon = "transport",
                color = "#00FF00",
                total = 300.0,
                txCount = 3
            ),
            CategoryTotalResult(
                id = 3,
                name = "Entertainment",
                icon = "entertainment",
                color = "#0000FF",
                total = 200.0,
                txCount = 2
            )
        )
        
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024")
        
        // Then: Percentages should sum to 100
        val totalPercentage = result.sumOf { it.percentageOfTotal.toDouble() }
        assertEquals(100.0, totalPercentage, 0.01)
        
        // Verify individual percentages
        assertEquals(50.0f, result[0].percentageOfTotal, 0.01f) // 500/1000 = 50%
        assertEquals(30.0f, result[1].percentageOfTotal, 0.01f) // 300/1000 = 30%
        assertEquals(20.0f, result[2].percentageOfTotal, 0.01f) // 200/1000 = 20%
    }

    @Test
    fun `category breakdown with single category has 100 percent`() = runTest {
        // Given: Only one category
        val categoryResults = listOf(
            CategoryTotalResult(
                id = 1,
                name = "Food",
                icon = "food",
                color = "#FF0000",
                total = 1000.0,
                txCount = 10
            )
        )
        
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024")
        
        // Then: Single category should have 100%
        assertEquals(1, result.size)
        assertEquals(100.0f, result[0].percentageOfTotal, 0.01f)
    }

    @Test
    fun `category_percentage_rounding_sum_invariant`() = runTest {
        val categoryResults = listOf(
            CategoryTotalResult(id = 1, name = "A", icon = "a", color = "#111111", total = 33.33, txCount = 1),
            CategoryTotalResult(id = 2, name = "B", icon = "b", color = "#222222", total = 33.33, txCount = 1),
            CategoryTotalResult(id = 3, name = "C", icon = "c", color = "#333333", total = 33.33, txCount = 1)
        )
        coEvery { expenseRepository.getCategoryBreakdown(any(), any()) } returns categoryResults

        val result = engine.getCategoryBreakdown(createDate(2024, 4, 1), createDate(2024, 5, 1), "April 2024")
        val roundedPercentSum = result.sumOf { kotlin.math.round(it.percentageOfTotal * 100) / 100.0 }

        // Rounded percentages should preserve the 100% invariant within small float tolerance.
        assertApproxEquals(100.0, roundedPercentSum, 0.05)
    }

    // ========== SCENARIO 6: Period Status Determination ==========

    @Test
    fun `period status is UNDER_AVERAGE when below average`() {
        // Given: Total below average
        val total = 800.0
        val average = 1000.0
        
        // When: Determine period status
        val status = engine.getPeriodStatus(total, average)
        
        // Then: Should be UNDER_AVERAGE
        assertEquals(PeriodStatus.UNDER_AVERAGE, status)
    }

    @Test
    fun `period status is OVER_AVERAGE when above average`() {
        // Given: Total above average
        val total = 1200.0
        val average = 1000.0
        
        // When: Determine period status
        val status = engine.getPeriodStatus(total, average)
        
        // Then: Should be OVER_AVERAGE
        assertEquals(PeriodStatus.OVER_AVERAGE, status)
    }

    @Test
    fun `period status is NO_DATA when average is zero`() {
        // Given: Average is zero
        val total = 1000.0
        val average = 0.0
        
        // When: Determine period status
        val status = engine.getPeriodStatus(total, average)
        
        // Then: Should be NO_DATA
        assertEquals(PeriodStatus.NO_DATA, status)
    }

    // ========== SCENARIO 7: Yearly Totals Filtering ==========

    @Test
    fun `yearly totals filters out years with no data except current year`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock yearly totals (some with zero)
        coEvery { expenseRepository.getTotalForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val year = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR)
            when (year) {
                2021 -> 5000.0
                2022 -> 6000.0
                2023 -> 7000.0
                2024 -> 3000.0 // Current year (partial)
                else -> 0.0
            }
        }
        
        coEvery { expenseRepository.getTransactionCountForPeriod(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val year = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR)
            when (year) {
                2021 -> 50
                2022 -> 60
                2023 -> 70
                2024 -> 30
                else -> 0
            }
        }
        
        // When: Get yearly totals
        val result = engine.getYearlyTotals()
        
        // Then: Should include years with data and current year
        assertEquals(4, result.size) // 2021, 2022, 2023, 2024
        
        // Verify totals
        val total2021 = result.find { it.periodKey == "2021" }?.totalAmount
        val total2022 = result.find { it.periodKey == "2022" }?.totalAmount
        val total2023 = result.find { it.periodKey == "2023" }?.totalAmount
        val total2024 = result.find { it.periodKey == "2024" }?.totalAmount
        
        assertEquals(5000.0, total2021 ?: 0.0, 0.01)
        assertEquals(6000.0, total2022 ?: 0.0, 0.01)
        assertEquals(7000.0, total2023 ?: 0.0, 0.01)
        assertEquals(3000.0, total2024 ?: 0.0, 0.01)
    }
}
