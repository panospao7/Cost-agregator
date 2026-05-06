package com.yourname.expensetracker.testfixtures.scenario

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import kotlin.math.abs
import org.junit.Assert

/**
 * Extension-style assertion helpers for verifying database state after
 * scenario execution.
 *
 * These are designed as [AppDatabase] extension functions so they can be
 * called directly on the database instance in tests:
 * ```
 * db.assertExpenseCount(3)
 * db.assertExpenseExists("Amazon", 42.50)
 * db.assertDashboardTotal(150.0)
 * ```
 *
 * All assertions are suspend functions because the underlying DAO calls
 * are suspend.
 */
object ScenarioAssertions {

    /**
     * Asserts that the total number of expenses in the database equals [expected].
     *
     * Uses [com.yourname.expensetracker.data.database.dao.ExpenseDao.getTotalCount]
     * for an efficient COUNT query rather than fetching all rows.
     */
    suspend fun AppDatabase.assertExpenseCount(expected: Int) {
        val count = expenseDao().getTotalCount()
        Assert.assertEquals("Expense count mismatch", expected, count)
    }

    /**
     * Asserts that at least one expense exists with the given [merchant] name
     * and an amount within [tolerance] of [amount].
     *
     * Fetches all expenses via [com.yourname.expensetracker.data.database.dao.ExpenseDao.getAll]
     * and filters in-memory for flexible matching.
     */
    suspend fun AppDatabase.assertExpenseExists(
        merchant: String,
        amount: Double,
        tolerance: Double = 0.01
    ) {
        val matching = expenseDao().getAll().filter { expense ->
            expense.merchant == merchant &&
                abs(expense.amount - amount) < tolerance
        }
        Assert.assertTrue(
            "Expected expense '$merchant' with amount ≈$amount not found",
            matching.isNotEmpty()
        )
    }

    /**
     * Asserts that the sum of all expense amounts equals [expected]
     * within the given [tolerance].
     *
     * Note: This uses the raw [Expense.amount] field, not the ownership-adjusted
     * [Expense.effectiveAmount]. For ownership-aware assertions, filter or
     * compute separately.
     */
    suspend fun AppDatabase.assertDashboardTotal(expected: Double, tolerance: Double = 0.01) {
        val all = expenseDao().getAll()
        val total = all.sumOf { it.amount }
        Assert.assertEquals("Dashboard total mismatch", expected, total, tolerance)
    }

    /**
     * Asserts that no two expenses share the same deduplication key.
     *
     * Duplicate [Expense.dedupeKey] values indicate that the same transaction
     * was inserted more than once, which should not happen under normal
     * pipeline operation.
     */
    suspend fun AppDatabase.assertNoDuplicateExpenses() {
        val all = expenseDao().getAll()
        val byDedupeKey = all.groupBy { it.dedupeKey }
        val duplicates = byDedupeKey.filter { it.key != null && it.value.size > 1 }
        Assert.assertTrue(
            "Found duplicate expenses: ${
                duplicates.mapValues { (_, list) ->
                    list.map { "id=${it.id}, merchant=${it.merchant}, amount=${it.amount}" }
                }
            }",
            duplicates.isEmpty()
        )
    }
}
