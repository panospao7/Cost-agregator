package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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
}
