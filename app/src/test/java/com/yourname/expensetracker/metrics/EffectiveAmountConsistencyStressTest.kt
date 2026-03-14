package com.yourname.expensetracker.metrics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import kotlin.random.Random

/**
 * Stress and exhaustion tests for effectiveAmount consistency across engines.
 */
class EffectiveAmountConsistencyStressTest {

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
        every { timeProvider.now() } returns fixedTime(2024, 5, 15)
        spendingPaceCalculator = SpendingPaceCalculator(timeProvider)
        monthlyComparisonCalculator = MonthlyComparisonCalculator()
        dayOfWeekAnalyzer = DayOfWeekAnalyzer()
    }

    @Test
    fun `stress - 500 expenses all isNotMine produces zero total`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        val expenses = (1..500).map { i ->
            Expense(
                amount = i * 10.0,
                merchant = "Merchant$i",
                transactionType = TransactionType.PURCHASE,
                date = monthStart + i * 86400L,
                isNotMine = true
            )
        }

        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, expenses
        )
        assertEquals("All not-mine must sum to 0", 0.0, pace.currentMonthSpent, 0.001)
    }

    @Test
    fun `stress - 200 mixed expenses effectiveAmount sum matches manual`() {
        val monthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 5, 15))
        val prevMonthStart = TimePeriodUtils.getStartOfMonth(fixedTime(2024, 4, 1))
        val prevMonthEnd = TimePeriodUtils.getEndOfMonth(prevMonthStart)

        var expectedSum = 0.0
        val expenses = (1..200).map { i ->
            val amount = Random.nextDouble(1.0, 500.0)
            val config = when (i % 5) {
                0 -> Triple(true, false, null to null)
                1 -> Triple(false, true, amount * 0.5 to null)
                2 -> Triple(false, true, null to 50)
                else -> Triple(false, false, null to null)
            }
            val (isNotMine, isShared, share) = config
            val (myShareAmount, mySharePct) = share
            val exp = Expense(
                amount = amount,
                merchant = "M$i",
                transactionType = TransactionType.PURCHASE,
                date = monthStart + i * 43200L,
                isNotMine = isNotMine,
                isSharedExpense = isShared,
                myShareAmount = myShareAmount,
                mySharePercentage = mySharePct
            )
            expectedSum += exp.effectiveAmount
            exp
        }

        val pace = spendingPaceCalculator.calculate(
            monthStart, prevMonthStart, prevMonthEnd, expenses
        )
        assertEquals("Engine sum must match manual effectiveAmount sum", expectedSum, pace.currentMonthSpent, 0.01)
    }

    @Test
    fun `stress - 100 shared with percentage exhaust 1-100`() {
        val currentMonth = MonthPeriod(2024, 5, monthStart(2024, 5), monthStart(2024, 6))
        val prevMonth = MonthPeriod(2024, 4, monthStart(2024, 4), monthStart(2024, 5))

        var expectedSum = 0.0
        val expenses = (1..100).map { pct ->
            val amount = 100.0
            val exp = Expense(
                amount = amount,
                merchant = "M$pct",
                transactionType = TransactionType.PURCHASE,
                date = currentMonth.startMs + pct * 86400L,
                isSharedExpense = true,
                mySharePercentage = pct
            )
            expectedSum += exp.effectiveAmount
            exp
        }

        val result = monthlyComparisonCalculator.calculate(currentMonth, prevMonth, expenses)
        assertEquals("Sum of 1%..100% of 100 each", expectedSum, result.currentTotal, 0.01)
    }

    @Test
    fun `stress - DayOfWeekAnalyzer 7 days x 50 expenses`() {
        val start = monthStart(2024, 5)
        val end = monthStart(2024, 6)

        val expenses = (1..350).map { i ->
            Expense(
                amount = 10.0,
                merchant = "M$i",
                transactionType = TransactionType.PURCHASE,
                date = start + (i % 30) * 86400000L,
                isNotMine = false
            )
        }

        val insights = dayOfWeekAnalyzer.analyze(start, end, expenses)
        val total = insights.sumOf { it.totalSpent }
        assertEquals("All 350 x 10 = 3500", 3500.0, total, 0.001)
    }

    @Test
    fun `edge - zero amount shared`() {
        val exp = Expense(
            amount = 0.0,
            merchant = "Test",
            transactionType = TransactionType.PURCHASE,
            date = 0,
            isSharedExpense = true,
            mySharePercentage = 50
        )
        assertEquals(0.0, exp.effectiveAmount, 0.001)
    }

    @Test
    fun `edge - mySharePercentage 0`() {
        val exp = Expense(
            amount = 100.0,
            merchant = "Test",
            transactionType = TransactionType.PURCHASE,
            date = 0,
            isSharedExpense = true,
            mySharePercentage = 0
        )
        assertEquals(0.0, exp.effectiveAmount, 0.001)
    }

    @Test
    fun `edge - mySharePercentage 100`() {
        val exp = Expense(
            amount = 100.0,
            merchant = "Test",
            transactionType = TransactionType.PURCHASE,
            date = 0,
            isSharedExpense = true,
            mySharePercentage = 100
        )
        assertEquals(100.0, exp.effectiveAmount, 0.001)
    }

    private fun monthStart(year: Int, calendarMonth: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, calendarMonth, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
