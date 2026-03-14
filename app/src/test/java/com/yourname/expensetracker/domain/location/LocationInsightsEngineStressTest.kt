package com.yourname.expensetracker.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationInsightsEngineStressTest {

    private val engine = LocationInsightsEngine()

    @Test
    fun `stress - clusters nearby expenses as one place`() {
        val result = engine.compute(
            listOf(
                located(1, 40.712800, -74.006000, 10.0, "Starbucks"),
                located(2, 40.712805, -74.006005, 20.0, "Starbucks"),
                located(3, 40.712801, -74.006002, 15.0, "Starbucks")
            )
        )
        assertEquals(1, result.size)
        assertEquals(45.0, result.first().totalSpend, 0.01)
        assertEquals(3, result.first().transactionCount)
    }

    @Test
    fun `stress - separates distant locations into distinct places`() {
        val result = engine.compute(
            listOf(
                located(1, 40.7128, -74.0060, 10.0, "NYC Shop"),
                located(2, 34.0522, -118.2437, 20.0, "LA Shop")
            )
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `stress - top merchant name becomes place name`() {
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
    fun `stress - computes avg transaction and last visit`() {
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
    fun `stress - sorting is by total spend descending`() {
        val result = engine.compute(
            listOf(
                located(1, 40.7128, -74.0060, 300.0, "A"),
                located(2, 40.7300, -74.0200, 100.0, "B")
            )
        )
        assertTrue(result[0].totalSpend >= result[1].totalSpend)
    }

    @Test
    fun `stress - deterministic for same input`() {
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
