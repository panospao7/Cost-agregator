package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SpendingPaceBoundaryTest {

    private lateinit var timeProvider: TimeProvider
    private lateinit var calculator: SpendingPaceCalculator

    @Before
    fun setUp() {
        timeProvider = mockk(relaxed = true)
        calculator = SpendingPaceCalculator(timeProvider)
        every { timeProvider.now() } returns ms(2026, 4, 15)
    }

    @Test
    fun `pace_status_boundaries_exactly_90_and_110_are_on_pace`() {
        val previousMonthStart = ms(2026, 3, 1)
        val currentMonthStart = ms(2026, 4, 1)
        val previousMonthEnd = currentMonthStart

        // March has 31 days, so 1550 => baseline daily rate 50.0
        val previous = expense(id = 1L, date = previousMonthStart, amount = 1550.0)

        val at90 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 2L, date = ms(2026, 4, 2), amount = 675.0))
        )

        val at110 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 3L, date = ms(2026, 4, 3), amount = 825.0))
        )

        val below90 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 4L, date = ms(2026, 4, 4), amount = 674.925))
        )

        val above110 = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(previous, expense(id = 5L, date = ms(2026, 4, 5), amount = 825.075))
        )

        assertApproxEquals(90f, at90.pacePercentage, 0.001f)
        assertApproxEquals(110f, at110.pacePercentage, 0.001f)
        assertApproxEquals(89.99f, below90.pacePercentage, 0.01f)
        assertApproxEquals(110.01f, above110.pacePercentage, 0.01f)

        assertEquals(PaceStatus.ON_PACE, at90.paceStatus)
        assertEquals(PaceStatus.ON_PACE, at110.paceStatus)
        assertEquals(PaceStatus.UNDER_PACE, below90.paceStatus)
        assertEquals(PaceStatus.OVER_PACE, above110.paceStatus)
    }

    @Test
    fun `day 1 projection applies conservative bias and projects 5600`() {
        val previousMonthStart = ms(2026, 2, 1)
        val currentMonthStart = ms(2026, 3, 1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns msAt(2026, 3, 1, 23, 59, 59)

        val result = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(
                expense(id = 10L, date = ms(2026, 2, 10), amount = 100.0),
                expense(id = 11L, date = ms(2026, 3, 1), amount = 800.0)
            )
        )

        assertApproxEquals(800.0, result.currentMonthSpent, 0.01)
        assertEquals(1, result.daysElapsed)
        assertApproxEquals(5600.0, result.projectedTotal, 0.1)
    }

    @Test
    fun `zero previous month baseline returns no baseline status and zero pace percentage`() {
        val previousMonthStart = ms(2026, 2, 1)
        val currentMonthStart = ms(2026, 3, 1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns msAt(2026, 3, 15, 23, 59, 59)

        val result = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(
                expense(id = 20L, date = ms(2026, 3, 2), amount = 300.0),
                expense(id = 21L, date = ms(2026, 3, 10), amount = 200.0)
            )
        )

        assertApproxEquals(0f, result.pacePercentage, 0.001f)
        assertEquals(PaceStatus.NO_BASELINE, result.paceStatus)
        assertNull(result.previousMonthTotal)
    }

    @Test
    fun `float boundary ratio at 90 percent remains on pace`() {
        val previousMonthStart = ms(2026, 2, 1)
        val currentMonthStart = ms(2026, 3, 1)
        val previousMonthEnd = currentMonthStart
        every { timeProvider.now() } returns msAt(2026, 3, 7, 23, 59, 59)

        val result = calculator.calculate(
            currentMonthStart = currentMonthStart,
            previousMonthStart = previousMonthStart,
            previousMonthEnd = previousMonthEnd,
            allExpenses = listOf(
                expense(id = 30L, date = ms(2026, 2, 10), amount = 100.0),
                expense(id = 31L, date = ms(2026, 3, 3), amount = 22.5)
            )
        )

        assertApproxEquals(90.0f, result.pacePercentage, 0.01f)
        assertEquals(PaceStatus.ON_PACE, result.paceStatus)
    }

    private fun expense(id: Long, date: Long, amount: Double): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "Test",
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    private fun ms(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun msAt(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
