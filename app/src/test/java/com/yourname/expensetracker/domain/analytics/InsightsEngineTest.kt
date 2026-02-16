package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InsightsEngineTest {
    private lateinit var engine: InsightsEngine

    @Before
    fun setup() {
        // InsightsEngine needs DAOs for generateInsights(), but detectRecurring()
        // and buildDailyTotals() are testable with just data.
        // We'll use mockk for the constructor.
        val expenseDao = io.mockk.mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        val recurringEngine = io.mockk.mockk<com.yourname.expensetracker.domain.logic.RecurringExpenseEngine>(relaxed = true)
        io.mockk.coEvery { recurringEngine.getPatterns(any()) } returns emptyList()
        engine = InsightsEngine(expenseDao, recurringEngine)
    }

    private val dayMs = 86_400_000L

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )

    // detectRecurring() was removed from InsightsEngine.
    // Recurring detection logic is now in RecurringExpenseEngine.


    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses, 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 20.0, "EUR", "B", TransactionType.PURCHASE, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(30.0, todayTotal, 0.01)
    }

    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 100.0, "EUR", "B", TransactionType.DEPOSIT, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }
}
