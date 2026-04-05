package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.TEST_CATEGORIES
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.startOfMonth
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryInsightEngineTest {

    private val engine = CategoryInsightEngine()

    @Test
    fun `calculate returns expected golden march category totals and percentages`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val previousMonth = MonthPeriod(
            year = 2026,
            month = 1,
            startMs = startOfMonth(2026, 2),
            endMs = startOfMonth(2026, 3)
        )
        val categoryMap = (TEST_CATEGORIES + Category(
            id = 99L,
            name = "Rent",
            icon = "🏠",
            color = "#607D8B",
            isDefault = true
        )).associateBy { it.id }

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categoryMap = categoryMap,
            allExpenses = goldenMarchAndFebruaryExpensesWithRentCategory()
        )

        val groceries = insights.find { it.category.id == 2L }
        val dining = insights.find { it.category.id == 1L }
        val rent = insights.find { it.category.id == 99L }

        assertNotNull(groceries)
        assertNotNull(dining)
        assertNotNull(rent)

        assertApproxEquals(136.10, groceries!!.currentTotal, 0.01)
        assertApproxEquals(10.60f, groceries.percentageOfTotal, 0.01f)
        assertApproxEquals(147.45f, groceries.changeFromPrevious!!, 0.01f)
        assertEquals(3, groceries.currentCount)

        assertApproxEquals(46.80, dining!!.currentTotal, 0.01)
        assertApproxEquals(3.65f, dining.percentageOfTotal, 0.01f)
        assertApproxEquals(56.0f, dining.changeFromPrevious!!, 0.01f)
        assertEquals(3, dining.currentCount)

        assertApproxEquals(800.00, rent!!.currentTotal, 0.01)
        assertApproxEquals(62.33f, rent.percentageOfTotal, 0.01f)
        assertApproxEquals(0.0f, rent.changeFromPrevious!!, 0.01f)
        assertEquals(1, rent.currentCount)

        val percentageSum = insights.sumOf { it.percentageOfTotal.toDouble() }
        assertApproxEquals(100.0, percentageSum, 0.01)
    }

    @Test
    fun `calculate returns uncategorized fallback for missing category mapping`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )
        val expenses = listOf(
            createExpense(
                date = "2026-03-06",
                amount = 120.0,
                merchant = "Unknown Category Merchant",
                id = 501L
            ).copy(categoryId = 777L)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.associateBy { it.id },
            allExpenses = expenses
        )

        assertEquals(1, insights.size)
        assertEquals("Uncategorized", insights.first().category.name)
        assertApproxEquals(120.0, insights.first().currentTotal, 0.01)
        assertApproxEquals(100.0f, insights.first().percentageOfTotal, 0.01f)
    }

    @Test
    fun `calculate with empty expenses returns empty insights`() {
        val currentMonth = MonthPeriod(
            year = 2026,
            month = 2,
            startMs = startOfMonth(2026, 3),
            endMs = startOfMonth(2026, 4)
        )

        val insights = engine.calculate(
            currentMonth = currentMonth,
            previousMonth = null,
            categoryMap = TEST_CATEGORIES.associateBy { it.id },
            allExpenses = emptyList()
        )

        assertTrue(insights.isEmpty())
    }

    private fun goldenMarchAndFebruaryExpensesWithRentCategory() = listOf(
        createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L).copy(categoryId = 99L),
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
        createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L),

        createExpense("2026-02-01", 800.00, merchant = "Rent Co", id = 101L).copy(categoryId = 99L),
        createExpense("2026-02-05", 55.00, merchant = "Lidl", category = "groceries", id = 102L),
        createExpense("2026-02-10", 58.00, merchant = "Shell Gas", id = 103L),
        createExpense("2026-02-15", 2500.00, type = TransactionType.DEPOSIT, merchant = "Salary", id = 104L),
        createExpense("2026-02-18", 30.00, merchant = "Restaurant B", category = "dining", id = 105L),
        createExpense("2026-02-25", 115.00, merchant = "Utilities", category = "utilities", id = 106L)
    )
}
