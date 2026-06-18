package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.toExpenseSnapshots
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SpendingPaceGoldenTest : AnalyticsEngineTestBase() {

    private lateinit var calculator: SpendingPaceCalculator

    @Before
    override fun setUp() {
        super.setUp()
        calculator = SpendingPaceCalculator(timeProvider)
    }

    @Test
    fun `golden march day 15 returns expected spent projected pace percentage and over pace status`() {
        val now = dateToMillisWithTime("2026-03-15", 23, 59, 59)
        every { timeProvider.now() } returns now

        val allExpenses = goldenMarchAndFebruaryExpenses()

        val result = calculator.calculate(
            currentMonthStart = march2026Start,
            previousMonthStart = february2026Start,
            previousMonthEnd = march2026Start,
            allExpenses = allExpenses.toExpenseSnapshots(),
            displayCurrency = "EUR"
        )

        assertApproxEquals(991.79, result.currentMonthSpent, 0.01)
        assertApproxEquals(15.0, result.daysElapsed.toDouble(), 0.0)
        // projectedTotal: weight=1.0 (15/7>1), linearProjection = 991.79 * 31 / 15 = 2049.6993...
        assertApproxEquals(2049.70, result.projectedTotal, 0.01)
        assertApproxEquals(175.0f, result.pacePercentage, 0.1f)
        assertEquals(PaceStatus.OVER_PACE, result.paceStatus)
    }

    @Test
    fun `golden march last day projection equals actual month spent`() {
        val now = dateToMillisWithTime("2026-03-31", 23, 59, 59)
        every { timeProvider.now() } returns now

        val allExpenses = goldenMarchAndFebruaryExpenses()

        val result = calculator.calculate(
            currentMonthStart = march2026Start,
            previousMonthStart = february2026Start,
            previousMonthEnd = march2026Start,
            allExpenses = allExpenses.toExpenseSnapshots(),
            displayCurrency = "EUR"
        )

        assertApproxEquals(31.0, result.daysElapsed.toDouble(), 0.0)
        assertApproxEquals(1283.59, result.currentMonthSpent, 0.01)
        assertApproxEquals(1283.59, result.projectedTotal, 0.01)
    }

    private fun goldenMarchAndFebruaryExpenses() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
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
        createExpense("2026-03-30", 500.00, type = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT, merchant = "Bonus", id = 14L),

        createExpense("2026-02-01", 800.00, merchant = "Rent Co", id = 101L),
        createExpense("2026-02-05", 55.00, merchant = "Lidl", category = "groceries", id = 102L),
        createExpense("2026-02-10", 58.00, merchant = "Shell Gas", id = 103L),
        createExpense("2026-02-15", 2500.00, type = com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT, merchant = "Salary", id = 104L),
        createExpense("2026-02-18", 30.00, merchant = "Restaurant B", category = "dining", id = 105L),
        createExpense("2026-02-25", 115.00, merchant = "Utilities", category = "utilities", id = 106L)
    )

    private fun dateToMillisWithTime(date: String, hour: Int, minute: Int, second: Int): Long {
        val start = com.yourname.expensetracker.dateToMillis(date)
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = start
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, second)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
