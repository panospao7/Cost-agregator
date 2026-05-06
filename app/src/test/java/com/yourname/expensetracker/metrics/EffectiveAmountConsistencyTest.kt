package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.toExpenseSnapshots
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Ensures all engines use expense.effectiveAmount (not amount) when aggregating spending.
 * Critical for shared expenses, not-mine expenses, and correct metrics throughout the app.
 */
class EffectiveAmountConsistencyTest {

    private val timeProvider = mockk<TimeProvider>()
    private lateinit var spendingPaceCalculator: SpendingPaceCalculator
    private lateinit var monthlyComparisonCalculator: MonthlyComparisonCalculator
    private lateinit var dayOfWeekAnalyzer: DayOfWeekAnalyzer

    private fun fixedTime(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setup() {
        every { timeProvider.now() } returns fixedTime(2024, 5, 15) // June 15, 2024
        spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        monthlyComparisonCalculator = MonthlyComparisonCalculator()
        dayOfWeekAnalyzer = DayOfWeekAnalyzer()
    }

    // ============================================================================
    // IS_NOT_MINE - must exclude from totals
    // ============================================================================

    @Test
    fun `effectiveAmount - isNotMine expense excluded from SpendingPaceCalculator`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        val mine = createExpense(100.0, monthStart + 86400000, isNotMine = false)
        val notMine = createExpense(500.0, monthStart + 172800000, isNotMine = true)

        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, listOf(mine, notMine).toExpenseSnapshots(), "EUR"
        )
        assertEquals("Only mine (100) should count", 100.0, pace.currentMonthSpent, 0.001)
    }

    @Test
    fun `effectiveAmount - isNotMine expense excluded from MonthlyComparisonCalculator`() {
        val currentMonth = MonthPeriod(2024, 5, monthStart(2024, 5), monthEnd(2024, 5) + 1)
        val prevMonth = MonthPeriod(2024, 4, monthStart(2024, 4), monthEnd(2024, 4) + 1)

        val mine = createExpense(200.0, currentMonth.startMs + 86400000, isNotMine = false)
        val notMine = createExpense(800.0, currentMonth.startMs + 172800000, isNotMine = true)

        val result = monthlyComparisonCalculator.calculate(
            currentMonth, prevMonth, listOf(mine, notMine).toExpenseSnapshots(), "EUR"
        )
        assertEquals("Only mine (200) should count", 200.0, result.currentTotal, 0.001)
    }

    @Test
    fun `effectiveAmount - isNotMine expense excluded from DayOfWeekAnalyzer`() {
        val start = monthStart(2024, 5)
        val end = monthStart(2024, 6)

        val mine = createExpense(50.0, start + 86400000, isNotMine = false)
        val notMine = createExpense(300.0, start + 86400000, isNotMine = true)

        val insights = dayOfWeekAnalyzer.analyze(start, end, listOf(mine, notMine).toExpenseSnapshots(), "EUR")
        val totalFromInsights = insights.sumOf { it.totalSpent }
        assertEquals("Only mine (50) should count", 50.0, totalFromInsights, 0.001)
    }

    // ============================================================================
    // SHARED EXPENSE - myShareAmount
    // ============================================================================

    @Test
    fun `effectiveAmount - shared expense with myShareAmount uses share not full amount`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        val shared = createExpense(
            amount = 100.0,
            date = monthStart + 86400000,
            isSharedExpense = true,
            myShareAmount = 40.0
        )

        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, listOf(shared).toExpenseSnapshots(), "EUR"
        )
        assertEquals("Should use myShareAmount (40)", 40.0, pace.currentMonthSpent, 0.001)
    }

    @Test
    fun `effectiveAmount - shared expense with mySharePercentage uses calculated share`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        val shared = createExpense(
            amount = 100.0,
            date = monthStart + 86400000,
            isSharedExpense = true,
            mySharePercentage = 50
        )

        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, listOf(shared).toExpenseSnapshots(), "EUR"
        )
        assertEquals("Should use 50% of 100 = 50", 50.0, pace.currentMonthSpent, 0.001)
    }

    @Test
    fun `effectiveAmount - MonthlyComparisonCalculator uses shared myShareAmount`() {
        val currentMonth = MonthPeriod(2024, 5, monthStart(2024, 5), monthEnd(2024, 5) + 1)
        val prevMonth = MonthPeriod(2024, 4, monthStart(2024, 4), monthEnd(2024, 4) + 1)

        val shared = createExpense(
            amount = 200.0,
            date = currentMonth.startMs + 86400000,
            isSharedExpense = true,
            myShareAmount = 75.0
        )

        val result = monthlyComparisonCalculator.calculate(
            currentMonth, prevMonth, listOf(shared).toExpenseSnapshots(), "EUR"
        )
        assertEquals("Should use myShareAmount (75)", 75.0, result.currentTotal, 0.001)
    }

    // ============================================================================
    // EDGE CASES
    // ============================================================================

    @Test
    fun `effectiveAmount - isNotMine overrides shared`() {
        val exp = createExpense(
            amount = 100.0,
            date = 0,
            isNotMine = true,
            isSharedExpense = true,
            myShareAmount = 50.0
        )
        assertEquals("isNotMine should return 0", 0.0, exp.effectiveAmount, 0.001)
    }

    @Test
    fun `effectiveAmount - myShareAmount overrides mySharePercentage when both set`() {
        val exp = createExpense(
            amount = 100.0,
            date = 0,
            isSharedExpense = true,
            myShareAmount = 30.0,
            mySharePercentage = 50
        )
        assertEquals("myShareAmount takes precedence", 30.0, exp.effectiveAmount, 0.001)
    }

    @Test
    fun `effectiveAmount - normal expense uses full amount`() {
        val exp = createExpense(75.0, 0)
        assertEquals(75.0, exp.effectiveAmount, 0.001)
    }

    @Test
    fun `effectiveAmount - mixed expenses sum correctly`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        val expenses = listOf(
            createExpense(100.0, monthStart + 86400000),
            createExpense(200.0, monthStart + 172800000, isNotMine = true),
            createExpense(60.0, monthStart + 259200000, isSharedExpense = true, myShareAmount = 20.0),
            createExpense(80.0, monthStart + 345600000, isSharedExpense = true, mySharePercentage = 25)
        )

        val expectedTotal = 100.0 + 0.0 + 20.0 + (80.0 * 0.25)
        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, expenses.toExpenseSnapshots(), "EUR"
        )
        assertEquals(expectedTotal, pace.currentMonthSpent, 0.001)
    }

    private fun monthStart(year: Int, calendarMonth: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, calendarMonth, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun monthEnd(year: Int, calendarMonth: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, calendarMonth, 1, 0, 0, 0)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    private fun createExpense(
        amount: Double,
        date: Long,
        isNotMine: Boolean = false,
        isSharedExpense: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null
    ): Expense = Expense(
        amount = amount,
        merchant = "Test",
        transactionType = TransactionType.PURCHASE,
        date = date,
        createdAt = System.currentTimeMillis(),
        isNotMine = isNotMine,
        isSharedExpense = isSharedExpense,
        myShareAmount = myShareAmount,
        mySharePercentage = mySharePercentage
    )
}
