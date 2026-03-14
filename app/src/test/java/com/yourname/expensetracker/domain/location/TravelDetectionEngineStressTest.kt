package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelDetectionEngineStressTest {

    private val engine = TravelDetectionEngine()
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun `stress - returns null below minimum located expenses`() {
        val insight = engine.compute((1..4).map {
            expense(lat = 40.7128, lon = -74.0060, date = 1_000L + it * dayMs, amount = 10.0)
        })
        assertNull(insight)
    }

    @Test
    fun `stress - detects home and nonzero home spend`() {
        val expenses = homeSeed() + listOf(
            expense(lat = 41.5, lon = -73.5, date = 20_000L, amount = 50.0)
        )
        val insight = engine.compute(expenses)
        assertNotNull(insight)
        assertTrue((insight!!.homeSpend) >= 60.0)
    }

    @Test
    fun `stress - classifies local and travel spending`() {
        val expenses = homeSeed() + listOf(
            expense(lat = 40.8128, lon = -74.0060, date = 10_000L, amount = 20.0), // ~11km local
            expense(lat = 41.6128, lon = -74.0060, date = 20_000L, amount = 30.0)  // ~100km travel
        )
        val insight = engine.compute(expenses)!!
        assertTrue(insight.localSpend >= 20.0)
        assertTrue(insight.travelSpend >= 30.0)
    }

    @Test
    fun `stress - groups travel expenses within gap into one trip`() {
        val expenses = homeSeed() + listOf(
            expense(lat = 41.8, lon = -87.6, date = 100_000L, amount = 100.0, address = "Shop, Chicago, IL"),
            expense(lat = 41.8, lon = -87.6, date = 100_000L + dayMs, amount = 150.0, address = "Cafe, Chicago, IL"),
            expense(lat = 41.8, lon = -87.6, date = 100_000L + 2 * dayMs, amount = 200.0, address = "Store, Chicago, IL")
        )
        val insight = engine.compute(expenses)!!
        assertEquals(1, insight.travelTrips.size)
        assertEquals(450.0, insight.travelTrips.first().totalSpend, 0.01)
    }

    @Test
    fun `stress - separates trips when gap exceeds three days`() {
        val expenses = homeSeed() + listOf(
            expense(lat = 41.8, lon = -87.6, date = 100_000L, amount = 100.0),
            expense(lat = 41.8, lon = -87.6, date = 100_000L + 5 * dayMs, amount = 200.0)
        )
        val insight = engine.compute(expenses)!!
        assertEquals(2, insight.travelTrips.size)
    }

    @Test
    fun `stress - handles out-of-order input deterministically`() {
        val ordered = homeSeed() + listOf(
            expense(lat = 41.8, lon = -87.6, date = 100_000L + dayMs, amount = 100.0),
            expense(lat = 41.8, lon = -87.6, date = 100_000L + 2 * dayMs, amount = 120.0)
        )
        val shuffled = ordered.shuffled()
        val a = engine.compute(ordered)!!
        val b = engine.compute(shuffled)!!
        assertEquals(a.travelTrips.first().totalSpend, b.travelTrips.first().totalSpend, 0.01)
    }

    @Test
    fun `stress - extracts destination hint from resolved address`() {
        val insight = engine.compute(
            homeSeed() + listOf(
                expense(lat = 41.8, lon = -87.6, date = 100_000L, amount = 100.0, address = "Coffee, Chicago, Illinois")
            )
        )!!
        assertEquals("Chicago", insight.travelTrips.first().destinationHint)
    }

    private fun homeSeed(): List<Expense> =
        (1..6).map { i ->
            expense(lat = 40.7128 + i * 0.0001, lon = -74.0060 + i * 0.0001, date = 1_000L + i * dayMs, amount = 10.0)
        }

    private fun expense(
        lat: Double,
        lon: Double,
        date: Long,
        amount: Double,
        address: String? = null
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = "M",
        transactionType = TransactionType.PURCHASE,
        date = date,
        latitude = lat,
        longitude = lon,
        resolvedAddress = address
    )
}
