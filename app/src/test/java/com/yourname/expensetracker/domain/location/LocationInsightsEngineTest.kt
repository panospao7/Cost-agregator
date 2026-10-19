package com.yourname.expensetracker.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationInsightsEngineTest {

    private val engine = LocationInsightsEngine()

    @Test
    fun `nearby expenses cluster into one place and distant ones into distinct places`() {
        // row: nearby -> one place with summed spend and count
        val nearby = engine.compute(
            listOf(
                located(1, 40.712800, -74.006000, 10.0, "Starbucks"),
                located(2, 40.712805, -74.006005, 20.0, "Starbucks"),
                located(3, 40.712801, -74.006002, 15.0, "Starbucks")
            )
        )
        assertEquals("nearby row: place count", 1, nearby.size)
        assertEquals("nearby row: totalSpend", 45.0, nearby.first().totalSpend, 0.01)
        assertEquals("nearby row: transactionCount", 3, nearby.first().transactionCount)

        // row: distant -> two places
        val distant = engine.compute(
            listOf(
                located(1, 40.7128, -74.0060, 10.0, "NYC Shop"),
                located(2, 34.0522, -118.2437, 20.0, "LA Shop")
            )
        )
        assertEquals("distant row: place count", 2, distant.size)
    }

    @Test
    fun `top merchant name becomes place name`() {
        val result = engine.compute(
            listOf(
                located(1, 40.7128, -74.0060, 10.0, "Coffee A"),
                located(2, 40.7128, -74.0060, 20.0, "Coffee A"),
                located(3, 40.7128, -74.0060, 30.0, "Coffee B")
            )
        ).first()
        assertEquals("Coffee A", result.placeName)
        assertTrue(result.merchantNames.contains("Coffee A"))
    }

    @Test
    fun `computes avg transaction and last visit`() {
        val now = 1_700_000_000_000L
        val result = engine.compute(
            listOf(
                located(1, 40.7128, -74.0060, 10.0, "M", now),
                located(2, 40.7128, -74.0060, 30.0, "M", now + 5_000L)
            )
        ).first()
        assertEquals(20.0, result.avgTransaction, 0.01)
        assertEquals(now + 5_000L, result.lastVisit)
    }

    @Test
    fun `sorting is by total spend descending`() {
        val result = engine.compute(
            listOf(
                // Lower-spend place is supplied first so the descending sort is actually exercised.
                located(1, 40.7300, -74.0200, 100.0, "B"),
                located(2, 40.7128, -74.0060, 300.0, "A")
            )
        )
        assertEquals(2, result.size)
        assertEquals("A", result[0].placeName)
        assertEquals(300.0, result[0].totalSpend, 0.01)
        assertEquals("B", result[1].placeName)
        assertEquals(100.0, result[1].totalSpend, 0.01)
    }

    @Test
    fun `deterministic for same input`() {
        val data = listOf(
            located(1, 40.7128, -74.0060, 10.0, "A"),
            located(2, 40.7128, -74.0060, 20.0, "A")
        )
        val a = engine.compute(data)
        val b = engine.compute(data)
        assertEquals(a, b)
    }

    private fun located(
        id: Long,
        lat: Double,
        lon: Double,
        amount: Double,
        merchant: String,
        date: Long = System.currentTimeMillis()
    ) = LocatedExpense(
        expenseId = id,
        latitude = lat,
        longitude = lon,
        amount = amount,
        merchant = merchant,
        date = date,
        locationSource = "test",
        placeId = null
    )
}
