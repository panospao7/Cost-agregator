package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Validation tests for SpendingPaceCalculator to ensure pace calculations
 * are mathematically correct.
 * 
 * Tests cover:
 * 1. Daily rate comparisons
 * 2. Projected total calculations
 * 3. Pace percentage calculations
 * 4. Edge cases (first 3 days, zero spending, etc.)
 */
class SpendingPaceCalculatorValidationTest {

    private lateinit var calculator: SpendingPaceCalculator
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
        transactionType: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false
    ): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = id,
            amount = amount,
            effectiveAmount = amount,
            currency = "EUR",
            merchant = "Test Merchant",
            merchantKey = null,
            transactionType = transactionType.toDomainTransactionType(),
            date = date,
            categoryId = null,
            isNotMine = isNotMine,
            transferDirection = null,
            notes = null
        )
    }

    @Before
    fun setup() {
        timeProvider = mockk(relaxed = true)
        calculator = SpendingPaceCalculator(timeProvider)
    }

    // ========== SCENARIO 1: Daily Rate Comparisons ==========

    @Test
    fun `pace calculation compares daily rates correctly`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 750 in 15 days (50 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 750.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: spent 1550 in 31 days (50 per day, March has 31 days)
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2024, 3, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: Daily rates should be equal (50 per day)
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31 // March has 31 days
        
        assertEquals(50.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        
        // Pace percentage should be 100% (same daily rate)
        assertEquals(100.0f, result.pacePercentage, 0.01f)
        assertEquals(PaceStatus.ON_PACE, result.paceStatus)
    }

    @Test
    fun `pace calculation detects overspending`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 1500 in 15 days (100 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 1500.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: spent 1550 in 31 days (50 per day, March has 31 days)
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2024, 3, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: Current daily rate is double (100 vs 50)
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31
        
        assertEquals(100.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        
        // Pace percentage should be 200% (double the daily rate)
        assertEquals(200.0f, result.pacePercentage, 0.01f)
        assertEquals(PaceStatus.OVER_PACE, result.paceStatus)
    }

    @Test
    fun `pace calculation detects underspending`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 375 in 15 days (25 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 375.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: spent 1550 in 31 days (50 per day, March has 31 days)
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2024, 3, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: Current daily rate is half (25 vs 50)
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31
        
        assertEquals(25.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        
        // Pace percentage should be 50% (half the daily rate)
        assertEquals(50.0f, result.pacePercentage, 0.01f)
        assertEquals(PaceStatus.UNDER_PACE, result.paceStatus)
    }

    // ========== SCENARIO 2: Projected Total Calculations ==========

    @Test
    fun `projected total calculation for normal days`() {
        // Given: Current date is April 10, 2024 (10th day of month)
        val currentDate = createDate(2024, 4, 10, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 500 in 10 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 500.0, date = createDate(2024, 4, 1))
        )
        
        // April has 30 days
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Projected total should be 500 * 30 / 10 = 1500
        assertEquals(1500.0, result.projectedTotal, 0.01)
        assertEquals(10, result.daysElapsed)
        assertEquals(30, result.daysInMonth)
    }

    @Test
    fun `projected total uses blended smoothing for early days`() {
        // Given: Current date is April 2, 2024 (2nd day of month)
        val currentDate = createDate(2024, 4, 2, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 200 in 2 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 200.0, date = createDate(2024, 4, 1))
        )
        
        // April has 30 days
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Without a prior baseline, early days use a stabilized projection floor.
        // day=2, stabilizedDays=max(2,5)=5 => 200*30/5 = 1200
        assertEquals(1200.0, result.projectedTotal, 0.01)
        assertEquals(2, result.daysElapsed)
        assertEquals(30, result.daysInMonth)
    }

    @Test
    fun `projected total for day 4 remains smooth and below full linear`() {
        // Given: Current date is April 4, 2024 (4th day of month)
        val currentDate = createDate(2024, 4, 4, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 400 in 4 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 400.0, date = createDate(2024, 4, 1))
        )
        
        // April has 30 days
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Without a prior baseline, day 4 is still stabilized to a 5-day floor.
        // 400*30/5 = 2400
        assertEquals(2400.0, result.projectedTotal, 0.01)
        assertEquals(4, result.daysElapsed)
        assertEquals(30, result.daysInMonth)
    }

    // ========== SCENARIO 3: Pace Percentage Calculations ==========

    @Test
    fun `pace percentage uses daily rate comparison`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 900 in 15 days (60 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 900.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: spent 1550 in 31 days (50 per day, March has 31 days)
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2024, 3, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: Pace percentage should be (60/50) * 100 = 120%
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31
        
        assertEquals(60.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        assertEquals(120.0f, result.pacePercentage, 0.01f)
    }

    @Test
    fun `pace percentage handles zero previous spending`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 900 in 15 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 900.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: no spending
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: With no previous data, no baseline exists
        assertEquals(0.0f, result.pacePercentage, 0.01f)
    }

    // ========== SCENARIO 4: Edge Cases ==========

    @Test
    fun `pace calculation with no current spending`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: no spending
        val currentExpenses = emptyList<ExpenseSnapshot>()
        
        // Previous month: spent 1500
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Current spending should be 0
        assertEquals(0.0, result.currentMonthSpent, 0.01)
        assertEquals(15, result.daysElapsed)
        
        // Projected total should be 0
        assertEquals(0.0, result.projectedTotal, 0.01)
    }

    @Test
    fun `pace calculation excludes non-purchase transactions`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: mix of purchases and non-purchases
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 500.0, date = createDate(2024, 4, 1), transactionType = TransactionType.PURCHASE),
            createExpense(id = 2, amount = 1000.0, date = createDate(2024, 4, 2), transactionType = TransactionType.DEPOSIT), // Not a purchase
            createExpense(id = 3, amount = 400.0, date = createDate(2024, 4, 3), transactionType = TransactionType.PURCHASE)
        )
        
        // Previous month: spent 1500
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Should only count purchases (500 + 400 = 900)
        assertEquals(900.0, result.currentMonthSpent, 0.01)
    }

    @Test
    fun `pace calculation excludes not-mine transactions`() {
        // Given: Current date is April 15, 2024 (15th day of month)
        val currentDate = createDate(2024, 4, 15, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: mix of mine and not-mine transactions
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 500.0, date = createDate(2024, 4, 1), isNotMine = false),
            createExpense(id = 2, amount = 1000.0, date = createDate(2024, 4, 2), isNotMine = true), // Not mine
            createExpense(id = 3, amount = 400.0, date = createDate(2024, 4, 3), isNotMine = false)
        )
        
        // Previous month: spent 1500
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: Should only count mine (500 + 400 = 900)
        assertEquals(900.0, result.currentMonthSpent, 0.01)
    }

    @Test
    fun `pace calculation for last day of month`() {
        // Given: Current date is April 30, 2024 (30th day of month)
        val currentDate = createDate(2024, 4, 30, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 1500 in 30 days
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 1500.0, date = createDate(2024, 4, 1))
        )
        
        // Previous month: spent 1550 in 31 days (50 per day, March has 31 days)
        val previousMonthStart = createDate(2024, 3, 1)
        val previousMonthEnd = createDate(2024, 4, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2024, 3, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 4, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: Projected total should equal actual spending
        assertEquals(1500.0, result.projectedTotal, 0.01)
        assertEquals(30, result.daysElapsed)
        assertEquals(30, result.daysInMonth)
        
        // Daily rates should be equal
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31
        
        assertEquals(50.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        assertEquals(100.0f, result.pacePercentage, 0.01f)
    }

    // ========== SCENARIO 5: Month with Different Days ==========

    @Test
    fun `pace calculation for February (28 days)`() {
        // Given: Current date is February 14, 2023 (14th day of month)
        val currentDate = createDate(2023, 2, 14, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 700 in 14 days (50 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 700.0, date = createDate(2023, 2, 1))
        )
        
        // Previous month (January): spent 1550 in 31 days (50 per day)
        val previousMonthStart = createDate(2023, 1, 1)
        val previousMonthEnd = createDate(2023, 2, 1)
        
        // When: Calculate spending pace
        val previousExpenses = listOf(
            createExpense(id = 2, amount = 1550.0, date = createDate(2023, 1, 15))
        )
        
        val result = calculator.calculate(
            currentMonthStart = createDate(2023, 2, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses + previousExpenses
        )
        
        // Then: February has 28 days
        assertEquals(14, result.daysElapsed)
        assertEquals(28, result.daysInMonth)
        
        // Projected total: 700 * 28 / 14 = 1400
        assertEquals(1400.0, result.projectedTotal, 0.01)
        
        // Daily rates: current 50, previous 50 (1550/31 = 50)
        val currentDailyRate = result.currentMonthSpent / result.daysElapsed
        val previousDailyRate = result.previousMonthTotal!! / 31
        
        assertEquals(50.0, currentDailyRate, 0.01)
        assertEquals(50.0, previousDailyRate, 0.01)
        assertEquals(100.0f, result.pacePercentage, 0.01f)
    }

    @Test
    fun `pace calculation for February leap year (29 days)`() {
        // Given: Current date is February 14, 2024 (14th day of month, leap year)
        val currentDate = createDate(2024, 2, 14, 12, 0)
        every { timeProvider.now() } returns currentDate
        
        // Current month: spent 700 in 14 days (50 per day)
        val currentExpenses = listOf(
            createExpense(id = 1, amount = 700.0, date = createDate(2024, 2, 1))
        )
        
        // Previous month (January): spent 1550 in 31 days (50 per day)
        val previousMonthStart = createDate(2024, 1, 1)
        val previousMonthEnd = createDate(2024, 2, 1)
        
        // When: Calculate spending pace
        val result = calculator.calculate(
            currentMonthStart = createDate(2024, 2, 1),
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = currentExpenses
        )
        
        // Then: February 2024 has 29 days (leap year)
        assertEquals(14, result.daysElapsed)
        assertEquals(29, result.daysInMonth)
        
        // Projected total: 700 * 29 / 14 = 1450
        assertEquals(1450.0, result.projectedTotal, 0.01)
    }

    private fun TransactionType.toDomainTransactionType(): DomainTransactionType {
        return when (this) {
            TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
    }
}
