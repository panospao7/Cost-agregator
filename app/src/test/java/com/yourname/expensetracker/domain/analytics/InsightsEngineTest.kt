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
        engine = InsightsEngine(expenseDao)
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

    @Test
    fun `detects monthly recurring payments`() {
        val expenses = listOf(
            makeExpense("Netflix", 9.99, 90),
            makeExpense("Netflix", 9.99, 60),
            makeExpense("Netflix", 9.99, 30),
            makeExpense("Netflix", 9.99, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant == "Netflix" })
        val netflix = recurring.first { it.merchant == "Netflix" }
        assertTrue(netflix.intervalDays in 25..35)
        assertEquals(4, netflix.occurrences)
    }

    @Test
    fun `detects weekly recurring payments`() {
        val expenses = listOf(
            makeExpense("GYM", 5.00, 28),
            makeExpense("GYM", 5.00, 21),
            makeExpense("GYM", 5.00, 14),
            makeExpense("GYM", 5.00, 7),
            makeExpense("GYM", 5.00, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant.uppercase() == "GYM" })
    }

    @Test
    fun `does not detect irregular payments as recurring`() {
        val expenses = listOf(
            makeExpense("Random Shop", 15.00, 100),
            makeExpense("Random Shop", 23.00, 50),
            makeExpense("Random Shop", 8.00, 10)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty() || recurring.none {
            it.merchant.uppercase() == "RANDOM SHOP"
        })
    }

    @Test
    fun `ignores single-occurrence merchants`() {
        val expenses = listOf(makeExpense("One Time Shop", 50.00, 0))
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty())
    }

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
