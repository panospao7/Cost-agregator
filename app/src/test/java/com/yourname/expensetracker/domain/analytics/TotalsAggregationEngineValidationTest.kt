package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.PeriodMoneyAggregate
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var multiCurrencyRepo: MultiCurrencyRepository
    private lateinit var categoryRepository: CategoryRepository

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
            categoryId = categoryId,
            createdAt = System.currentTimeMillis()
        )
    }

    @Before
    fun setup() {
        expenseRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        multiCurrencyRepo = mockk()
        categoryRepository = mockk(relaxed = true)

        // Must emit at least one value for reactiveFlow / reactiveCategoryBreakdownFlow to work
        every { expenseRepository.getTotalSpent() } returns flowOf(null)

        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(MoneyAggregate.empty(CurrencyCode("EUR")))
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns emptyMap()
        coEvery { multiCurrencyRepo.getWeeklyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns emptyList()
        coEvery { categoryRepository.getAll() } returns emptyList()

        engine = TotalsAggregationEngine(expenseRepository, timeProvider, multiCurrencyRepo, categoryRepository, Dispatchers.Unconfined)
    }

    // ========== SCENARIO 1: Known Data Verification ==========

    @Test
    fun `monthly totals sum correctly`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock monthly totals for current year via MultiCurrencyRepository
        val monthlyAggregates = listOf(
            MonthMoneyAggregate("2024-01", MoneyAggregate.singleCurrency(1500.0, CurrencyCode("EUR"), 15)),
            MonthMoneyAggregate("2024-02", MoneyAggregate.singleCurrency(2000.0, CurrencyCode("EUR"), 20)),
            MonthMoneyAggregate("2024-03", MoneyAggregate.singleCurrency(1800.0, CurrencyCode("EUR"), 18))
        )
        
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns monthlyAggregates
        
        // When: Get monthly totals for 2024
        val result = engine.getMonthlyTotals(2024).first()
        
        // Then: Verify totals match expected values
        assertEquals(12, result.size)
        assertEquals(1500.0, result.first { it.periodKey == "2024-01" }.totalAmount, 0.01)
        assertEquals(2000.0, result.first { it.periodKey == "2024-02" }.totalAmount, 0.01)
        assertEquals(1800.0, result.first { it.periodKey == "2024-03" }.totalAmount, 0.01)
        
        // Verify total sum
        val totalSum = result.sumOf { it.totalAmount }
        assertEquals(5300.0, totalSum, 0.01)
    }

    @Test
    fun `daily totals sum correctly for a week`() = runTest {
        // Given: Fixed reference date (April 15, 2024 - Monday)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock daily totals for a full week via MultiCurrencyRepository
        val dailyAggregates = listOf(
            PeriodMoneyAggregate("2024-04-15", MoneyAggregate.singleCurrency(100.0, CurrencyCode("EUR"), 2)),
            PeriodMoneyAggregate("2024-04-16", MoneyAggregate.singleCurrency(150.0, CurrencyCode("EUR"), 3)),
            PeriodMoneyAggregate("2024-04-17", MoneyAggregate.singleCurrency(200.0, CurrencyCode("EUR"), 4)),
            PeriodMoneyAggregate("2024-04-18", MoneyAggregate.singleCurrency(0.0, CurrencyCode("EUR"), 0)),
            PeriodMoneyAggregate("2024-04-19", MoneyAggregate.singleCurrency(0.0, CurrencyCode("EUR"), 0)),
            PeriodMoneyAggregate("2024-04-20", MoneyAggregate.singleCurrency(0.0, CurrencyCode("EUR"), 0)),
            PeriodMoneyAggregate("2024-04-21", MoneyAggregate.singleCurrency(0.0, CurrencyCode("EUR"), 0))
        )
        
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns dailyAggregates
        
        // When: Get daily totals for a week
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 22)
        val result = engine.getDailyTotalsForRange(startMs, endMs).first()
        
        // Then: Verify totals match expected values
        assertEquals(7, result.size)
        assertEquals(100.0, result.first { it.startDateMs == createDate(2024, 4, 15) }.totalAmount, 0.01)
        assertEquals(150.0, result.first { it.startDateMs == createDate(2024, 4, 16) }.totalAmount, 0.01)
        assertEquals(200.0, result.first { it.startDateMs == createDate(2024, 4, 17) }.totalAmount, 0.01)
        
        // Verify total sum
        val totalSum = result.sumOf { it.totalAmount }
        assertEquals(450.0, totalSum, 0.01)
    }

    // ========== SCENARIO 2: Period Boundary Testing ==========

    @Test
    fun `transaction at midnight is included in correct day`() = runTest {
        // Given: Transaction at exactly midnight
        
        // Mock daily totals that include this transaction
        val dailyAggregates = listOf(
            PeriodMoneyAggregate("2024-04-15", MoneyAggregate.singleCurrency(100.0, CurrencyCode("EUR"), 1))
        )
        
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns dailyAggregates
        
        // When: Get daily totals for the day containing midnight
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 16)
        val result = engine.getDailyTotalsForRange(startMs, endMs).first()
        
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
        val dailyAggregates = listOf(
            PeriodMoneyAggregate("2024-04-15", MoneyAggregate.singleCurrency(100.0, CurrencyCode("EUR"), 1))
        )
        
        coEvery { multiCurrencyRepo.getDailyAggregatesHistorical(any(), any()) } returns dailyAggregates
        
        // When: Get daily totals for the day containing 23:59:59
        val startMs = createDate(2024, 4, 15)
        val endMs = createDate(2024, 4, 16)
        val result = engine.getDailyTotalsForRange(startMs, endMs).first()
        
        // Then: Transaction should be included in April 15
        assertEquals(1, result.size)
        assertEquals(createDate(2024, 4, 15), result[0].startDateMs)
    }

    // ========== SCENARIO 3: Empty Period Handling ==========

    @Test
    fun `empty period returns zero totals`() = runTest {
        // Given: No transactions in period
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns emptyList()
        
        // When: Get monthly totals
        val result = engine.getMonthlyTotals(2024).first()
        
        // Then: Should return explicit zero buckets
        assertEquals(12, result.size)
        assertTrue(result.all { it.totalAmount == 0.0 && it.transactionCount == 0 })
    }

    @Test
    fun `category breakdown handles zero grand total`() = runTest {
        // Given: No transactions (grand total = 0)
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns emptyMap()
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024").first()
        
        // Then: Should return empty list
        assertTrue(result.isEmpty())
    }

    // ========== SCENARIO 4: Daily Average Calculations ==========

    @Test
    fun `average calculation for period type DAY is correct`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock total spend for the last 30 days via MultiCurrencyRepository.
        // average = total / daysCount, so total = 150.0 * 30 = 4500.0
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } returns
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(MoneyAggregate.singleCurrency(4500.0, CurrencyCode("EUR"), 30))
        
        // When: Calculate average for DAY period type
        val average = engine.getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)
        
        // Then: Should return computed average (4500 / 30 = 150)
        assertEquals(150.0, average, 0.01)
    }

    @Test
    fun `average calculation for period type MONTH is correct`() = runTest {
        // Given: Fixed reference date (April 15, 2024)
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate
        
        // Mock monthly totals for last 12 months via MultiCurrencyRepository
        val monthlyAggregates = listOf(
            MonthMoneyAggregate("2023-05", MoneyAggregate.singleCurrency(1000.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2023-06", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 12)),
            MonthMoneyAggregate("2023-07", MoneyAggregate.singleCurrency(1100.0, CurrencyCode("EUR"), 11))
        )
        
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns monthlyAggregates
        
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
        val monthlyAggregates = listOf(
            MonthMoneyAggregate("2023-05", MoneyAggregate.singleCurrency(1000.0, CurrencyCode("EUR"), 10)),
            MonthMoneyAggregate("2023-06", MoneyAggregate.singleCurrency(1200.0, CurrencyCode("EUR"), 12)),
            MonthMoneyAggregate("2024-04", MoneyAggregate.singleCurrency(800.0, CurrencyCode("EUR"), 8)) // Current month (partial)
        )
        
        coEvery { multiCurrencyRepo.getMonthlyAggregatesHistorical(any(), any()) } returns monthlyAggregates
        
        // When: Calculate average for MONTH period type, excluding current month
        val average = engine.getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = true)
        
        // Then: Should be (1000 + 1200) / 2 = 1100 (excluding current month)
        assertEquals(1100.0, average, 0.01)
    }

    // ========== SCENARIO 5: Category Breakdown Percentages ==========

    @Test
    fun `category breakdown percentages sum to 100`() = runTest {
        // Given: Category breakdown results via MultiCurrencyRepository
        val categoryAggregates = mapOf<Long?, MoneyAggregate>(
            1L to MoneyAggregate.singleCurrency(500.0, CurrencyCode("EUR"), 5),
            2L to MoneyAggregate.singleCurrency(300.0, CurrencyCode("EUR"), 3),
            3L to MoneyAggregate.singleCurrency(200.0, CurrencyCode("EUR"), 2)
        )
        
        val categories = listOf(
            Category(id = 1, name = "Food", icon = "food", color = "#FF0000"),
            Category(id = 2, name = "Transport", icon = "vehicle", color = "#00FF00"),
            Category(id = 3, name = "Entertainment", icon = "entertain", color = "#0000FF")
        )
        
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns categoryAggregates
        coEvery { categoryRepository.getAll() } returns categories
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024").first()
        
        // Then: Percentages should sum to 100
        val totalPercentage = result.sumOf { it.percentageOfTotal.toDouble() }
        assertEquals(100.0, totalPercentage, 0.01)
        
        // Verify individual percentages
        val foodResult = result.find { it.category.name == "Food" }
        val transportResult = result.find { it.category.name == "Transport" }
        val entertainmentResult = result.find { it.category.name == "Entertainment" }
        assertNotNull(foodResult)
        assertNotNull(transportResult)
        assertNotNull(entertainmentResult)
        assertEquals(50.0, foodResult!!.percentageOfTotal, 0.01) // 500/1000 = 50%
        assertEquals(30.0, transportResult!!.percentageOfTotal, 0.01) // 300/1000 = 30%
        assertEquals(20.0, entertainmentResult!!.percentageOfTotal, 0.01) // 200/1000 = 20%
    }

    @Test
    fun `category breakdown with single category has 100 percent`() = runTest {
        // Given: Only one category
        val categoryAggregates = mapOf<Long?, MoneyAggregate>(
            1L to MoneyAggregate.singleCurrency(1000.0, CurrencyCode("EUR"), 10)
        )
        
        val categories = listOf(
            Category(id = 1, name = "Food", icon = "food", color = "#FF0000")
        )
        
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns categoryAggregates
        coEvery { categoryRepository.getAll() } returns categories
        
        // When: Get category breakdown
        val startMs = createDate(2024, 4, 1)
        val endMs = createDate(2024, 5, 1)
        val result = engine.getCategoryBreakdown(startMs, endMs, "April 2024").first()
        
        // Then: Single category should have 100%
        assertEquals(1, result.size)
        assertEquals(100.0, result[0].percentageOfTotal, 0.01)
    }

    @Test
    fun `category_percentage_rounding_sum_invariant`() = runTest {
        val categoryAggregates = mapOf<Long?, MoneyAggregate>(
            1L to MoneyAggregate.singleCurrency(33.33, CurrencyCode("EUR"), 1),
            2L to MoneyAggregate.singleCurrency(33.33, CurrencyCode("EUR"), 1),
            3L to MoneyAggregate.singleCurrency(33.33, CurrencyCode("EUR"), 1)
        )
        val categories = listOf(
            Category(id = 1, name = "A", icon = "a", color = "#111111"),
            Category(id = 2, name = "B", icon = "b", color = "#222222"),
            Category(id = 3, name = "C", icon = "c", color = "#333333")
        )
        // These icons are all 1 char, well within the 10-char limit
        coEvery { multiCurrencyRepo.getCategoryAggregatesHistorical(any(), any()) } returns categoryAggregates
        coEvery { categoryRepository.getAll() } returns categories

        val result = engine.getCategoryBreakdown(createDate(2024, 4, 1), createDate(2024, 5, 1), "April 2024").first()
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
        
        // Mock yearly totals via MultiCurrencyRepository (amount + tx count)
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val year = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR)
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(when (year) {
                2020 -> MoneyAggregate.empty(CurrencyCode("EUR"))
                2021 -> MoneyAggregate.singleCurrency(5000.0, CurrencyCode("EUR"), 50)
                2022 -> MoneyAggregate.singleCurrency(6000.0, CurrencyCode("EUR"), 60)
                2023 -> MoneyAggregate.singleCurrency(7000.0, CurrencyCode("EUR"), 70)
                2024 -> MoneyAggregate.singleCurrency(3000.0, CurrencyCode("EUR"), 30) // Current year (partial)
                else -> MoneyAggregate.empty(CurrencyCode("EUR"))
            })
        }
        
        // When: Get yearly totals
        val result = engine.getYearlyTotals().first()
        
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

    @Test
    fun `yearly totals status uses average of completed years only`() = runTest {
        // Validates that getYearlyTotals computes status against the average of
        // completed years (excludeCurrent = true), so a partial current year
        // does not drag the average down and distort all status flags.
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate

        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val year = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR)
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(when (year) {
                2020 -> MoneyAggregate.empty(CurrencyCode("EUR"))
                2021 -> MoneyAggregate.singleCurrency(5000.0, CurrencyCode("EUR"), 50)
                2022 -> MoneyAggregate.singleCurrency(6000.0, CurrencyCode("EUR"), 60)
                2023 -> MoneyAggregate.singleCurrency(7000.0, CurrencyCode("EUR"), 70)
                2024 -> MoneyAggregate.singleCurrency(1500.0, CurrencyCode("EUR"), 15) // Partial current year — must NOT be in average
                else -> MoneyAggregate.empty(CurrencyCode("EUR"))
            })
        }

        val result = engine.getYearlyTotals().first()

        // Average of completed years = (5000 + 6000 + 7000) / 3 = 6000.0
        // 2021 (5000) < 6000 → UNDER_AVERAGE
        // 2022 (6000) >= 6000 → OVER_AVERAGE
        // 2023 (7000) > 6000 → OVER_AVERAGE
        // 2024 (1500) < 6000 → UNDER_AVERAGE
        val s2021 = result.find { it.periodKey == "2021" }
        val s2022 = result.find { it.periodKey == "2022" }
        val s2023 = result.find { it.periodKey == "2023" }
        val s2024 = result.find { it.periodKey == "2024" }

        assertNotNull(s2021)
        assertNotNull(s2022)
        assertNotNull(s2023)
        assertNotNull(s2024)

        assertEquals(PeriodStatus.UNDER_AVERAGE, s2021!!.status)
        assertEquals(PeriodStatus.OVER_AVERAGE, s2022!!.status)
        assertEquals(PeriodStatus.OVER_AVERAGE, s2023!!.status)
        assertEquals(PeriodStatus.UNDER_AVERAGE, s2024!!.status)
    }

    // ========== SCENARIO 8: Yearly Purchase-Only Count Contract ==========

    @Test
    fun `yearly totals transaction counts reflect purchase-only repository contract`() = runTest {
        // Validates that getYearlyTotals surfaces the purchase-only transaction
        // count returned by the MultiCurrencyRepository contract.
        val referenceDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns referenceDate

        // Simulate repository returning purchase-only totals and counts
        coEvery { multiCurrencyRepo.getHomeCurrencyPurchaseTotalHistoricalResult(any(), any()) } answers {
            val startMs = firstArg<Long>()
            val year = Calendar.getInstance().apply { timeInMillis = startMs }.get(Calendar.YEAR)
            com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available(when (year) {
                2020 -> MoneyAggregate.empty(CurrencyCode("EUR"))
                2023 -> MoneyAggregate.singleCurrency(8500.0, CurrencyCode("EUR"), 95)   // purchase-only sum and count
                2024 -> MoneyAggregate.singleCurrency(2100.0, CurrencyCode("EUR"), 22)   // purchase-only sum and count (partial year)
                else -> MoneyAggregate.empty(CurrencyCode("EUR"))
            })
        }

        val result = engine.getYearlyTotals().first()

        val y2023 = result.find { it.periodKey == "2023" }
        val y2024 = result.find { it.periodKey == "2024" }
        assertNotNull(y2023)
        assertNotNull(y2024)

        // Amounts are purchase-only
        assertEquals(8500.0, y2023!!.totalAmount, 0.01)
        assertEquals(2100.0, y2024!!.totalAmount, 0.01)

        // Counts come from MoneyAggregate.totalTransactionCount
        assertEquals(95, y2023.transactionCount)
        assertEquals(22, y2024.transactionCount)

        // Period type is YEAR
        assertEquals(PeriodType.YEAR, y2023.periodType)
        assertEquals(PeriodType.YEAR, y2024.periodType)
    }
}
