package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.createExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantInsightEngineTest {

    private val engine = MerchantInsightEngine()

    @Test
    fun `calculate ranks top merchants by total spent descending`() {
        val insights = engine.calculate(
            listOf(
                createExpense("2026-03-01", 800.00, merchant = "Rent Co", id = 1L),
                createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
                createExpense("2026-03-05", 62.50, merchant = "Shell Gas", id = 3L),
                createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
                createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
                createExpense("2026-03-30", 500.00, type = TransactionType.DEPOSIT, merchant = "Bonus", id = 14L),
                createExpense("2026-03-31", 999.0, effectiveAmount = 0.0, merchant = "Other Person", isNotMine = true, id = 15L)
            )
        )

        assertEquals("Rent Co", insights[0].merchant)
        assertApproxEquals(800.0, insights[0].totalSpent, 0.01)

        assertEquals("Lidl", insights[1].merchant)
        assertApproxEquals(136.10, insights[1].totalSpent, 0.01)

        assertEquals("Shell Gas", insights[2].merchant)
        assertApproxEquals(62.50, insights[2].totalSpent, 0.01)
    }

    @Test
    fun `calculate computes frequency and amount statistics per merchant`() {
        val insights = engine.calculate(
            listOf(
                createExpense("2026-03-02", 45.30, merchant = "Lidl", category = "groceries", id = 2L),
                createExpense("2026-03-10", 38.70, merchant = "Lidl", category = "groceries", id = 5L),
                createExpense("2026-03-18", 52.10, merchant = "Lidl", category = "groceries", id = 9L),
                createExpense("2026-03-12", 24.50, merchant = "Restaurant A", category = "dining", id = 6L),
                createExpense("2026-03-25", 17.50, merchant = "Restaurant A", category = "dining", id = 12L)
            )
        )

        val lidl = insights.find { it.merchant == "Lidl" }
        assertNotNull(lidl)

        assertEquals(3, lidl!!.transactionCount)
        assertApproxEquals(136.10, lidl.totalSpent, 0.01)
        assertApproxEquals(45.3667, lidl.avgAmount, 0.0001)
        assertApproxEquals(38.70, lidl.minAmount, 0.01)
        assertApproxEquals(52.10, lidl.maxAmount, 0.01)
    }

    @Test
    fun `calculate flags likely recurring only when threshold and variance criteria are met`() {
        val insights = engine.calculate(
            listOf(
                createExpense("2026-03-01", 10.00, merchant = "NETFLIX", id = 1L),
                createExpense("2026-03-08", 10.40, merchant = "Netflix", id = 2L),
                createExpense("2026-03-15", 9.80, merchant = "netflix", id = 3L),

                createExpense("2026-03-02", 20.0, merchant = "Restaurant A", id = 4L),
                createExpense("2026-03-09", 100.0, merchant = "Restaurant A", id = 5L),
                createExpense("2026-03-16", 220.0, merchant = "Restaurant A", id = 6L),

                createExpense("2026-03-03", 14.0, merchant = "Coffee Shop", id = 7L),
                createExpense("2026-03-10", 14.0, merchant = "Coffee Shop", id = 8L)
            )
        )

        val netflix = insights.find { it.merchant.equals("NETFLIX", ignoreCase = true) }
        val restaurant = insights.find { it.merchant == "Restaurant A" }
        val coffee = insights.find { it.merchant == "Coffee Shop" }

        assertNotNull(netflix)
        assertNotNull(restaurant)
        assertNotNull(coffee)

        assertTrue(netflix!!.isLikelyRecurring)
        assertNotNull(netflix.stdDeviation)
        assertApproxEquals(10.0667, netflix.avgAmount, 0.0001)

        assertFalse(restaurant!!.isLikelyRecurring)
        assertNotNull(restaurant.stdDeviation)

        assertFalse(coffee!!.isLikelyRecurring)
        assertEquals(null, coffee.stdDeviation)
    }
}
