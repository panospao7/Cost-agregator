package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.startOfMonth
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class DayOfWeekAnalyzerTest {

    private val analyzer = DayOfWeekAnalyzer()

    @Test
    fun `analyze maps calendar days to monday-zero indexing correctly for bug b17`() {
        val start = startOfMonth(2026, 3)
        val end = startOfMonth(2026, 4)
        val expenses = listOf(
            createExpense("2026-03-01", 70.0, merchant = "Sun Merchant", id = 1L),
            createExpense("2026-03-02", 10.0, merchant = "Mon Merchant", id = 2L),
            createExpense("2026-03-03", 20.0, merchant = "Tue Merchant", id = 3L),
            createExpense("2026-03-04", 30.0, merchant = "Wed Merchant", id = 4L),
            createExpense("2026-03-05", 40.0, merchant = "Thu Merchant", id = 5L),
            createExpense("2026-03-06", 50.0, merchant = "Fri Merchant", id = 6L),
            createExpense("2026-03-07", 60.0, merchant = "Sat Merchant", id = 7L)
        )

        val insights = analyzer.analyze(startDate = start, endDate = end, allExpenses = expenses)

        assertApproxEquals(10.0, insights.first { it.dayName == "Mon" }.totalSpent, 0.01)
        assertApproxEquals(20.0, insights.first { it.dayName == "Tue" }.totalSpent, 0.01)
        assertApproxEquals(30.0, insights.first { it.dayName == "Wed" }.totalSpent, 0.01)
        assertApproxEquals(40.0, insights.first { it.dayName == "Thu" }.totalSpent, 0.01)
        assertApproxEquals(50.0, insights.first { it.dayName == "Fri" }.totalSpent, 0.01)
        assertApproxEquals(60.0, insights.first { it.dayName == "Sat" }.totalSpent, 0.01)
        assertApproxEquals(70.0, insights.first { it.dayName == "Sun" }.totalSpent, 0.01)

        assertEquals(0, insights.first { it.dayName == "Mon" }.dayIndex)
        assertEquals(6, insights.first { it.dayName == "Sun" }.dayIndex)
    }

    @Test
    fun `analyze shows weekend spending higher than weekday for golden march purchases`() {
        val start = startOfMonth(2026, 3)
        val end = startOfMonth(2026, 4)

        val insights = analyzer.analyze(
            startDate = start,
            endDate = end,
            allExpenses = goldenMarchExpenses()
        )

        val weekendTotal = insights.filter { it.dayIndex == 5 || it.dayIndex == 6 }.sumOf { it.totalSpent }
        val weekdayTotal = insights.filter { it.dayIndex in 0..4 }.sumOf { it.totalSpent }

        assertApproxEquals(953.09, weekendTotal, 0.01)
        assertApproxEquals(330.50, weekdayTotal, 0.01)
        assertApproxEquals(1283.59, weekendTotal + weekdayTotal, 0.01)
    }

    @Test
    fun `analyze returns seven zeroed day buckets when no expenses exist`() {
        val start = startOfMonth(2026, 3)
        val end = startOfMonth(2026, 4)

        val insights = analyzer.analyze(startDate = start, endDate = end, allExpenses = emptyList())

        assertEquals(7, insights.size)
        insights.forEach {
            assertApproxEquals(0.0, it.totalSpent, 0.01)
            assertApproxEquals(0.0, it.avgPerTransaction, 0.01)
            assertEquals(0, it.transactionCount)
        }
    }

    private fun goldenMarchExpenses() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
        createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
        createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
        createExpense("2026-03-07", 15.99, merchant = "Netflix", category = "entertainment", id = 4L),
        createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
        createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
        createExpense("2026-03-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 7L),
        createExpense("2026-03-15", 4.80, merchant = "Coffee Shop", category = "dining", id = 8L),
        createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
        createExpense("2026-03-20", 89.90, merchant = "Zara", id = 10L),
        createExpense("2026-03-22", 12.30, merchant = "Pharmacy", id = 11L),
        createExpense(
            date = "2026-03-25",
            amount = 35.00,
            effectiveAmount = 17.50,
            merchant = "Friend Lunch",
            category = "dining",
            id = 12L,
            isSharedExpense = true,
            mySharePercentage = 50
        ),
        createExpense("2026-03-28", 120.00, merchant = "Utilities", category = "utilities", id = 13L),
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L)
    )
}
