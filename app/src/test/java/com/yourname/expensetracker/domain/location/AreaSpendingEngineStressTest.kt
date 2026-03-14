package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaSpendingEngineStressTest {

    private val engine = AreaSpendingEngine()

    @Test
    fun `stress - aggregates spending by parsed area name`() {
        val result = engine.compute(
            listOf(
                expense(40.7128, -74.0060, 100.0, "Starbucks, Manhattan, NY"),
                expense(40.7130, -74.0062, 50.0, "Cafe, Manhattan, NY"),
                expense(40.7580, -73.9855, 80.0, "Gym, Midtown, NY")
            )
        )
        assertEquals(2, result.size)
        assertEquals(150.0, result.first { it.areaName == "Manhattan" }.totalSpend, 0.01)
    }

    @Test
    fun `stress - ignores expenses without location or address`() {
        val result = engine.compute(
            listOf(
                expense(40.7128, -74.0060, 100.0, "Shop, Manhattan, NY"),
                Expense(amount = 50.0, merchant = "M", currency = "EUR", transactionType = TransactionType.PURCHASE, date = 2_000L),
                Expense(amount = 20.0, merchant = "M", currency = "EUR", transactionType = TransactionType.PURCHASE, date = 3_000L, latitude = 40.7, longitude = -74.0)
            )
        )
        assertEquals(1, result.size)
        assertEquals("Manhattan", result.first().areaName)
    }

    @Test
    fun `stress - sorts areas by descending total spend`() {
        val result = engine.compute(
            listOf(
                expense(40.7128, -74.0060, 200.0, "Store, AreaA, NY"),
                expense(40.7500, -74.0300, 100.0, "Store, AreaB, NY")
            )
        )
        assertEquals("AreaA", result[0].areaName)
        assertEquals("AreaB", result[1].areaName)
    }

    @Test
    fun `stress - computes representative centroid and averages`() {
        val result = engine.compute(
            listOf(
                expense(40.7128, -74.0060, 20.0, "Shop, Downtown, NY"),
                expense(40.7138, -74.0070, 40.0, "Cafe, Downtown, NY")
            )
        ).first()
        assertEquals(60.0, result.totalSpend, 0.01)
        assertEquals(2, result.transactionCount)
        assertEquals(30.0, result.avgTransaction, 0.01)
        assertTrue(result.latitude in 40.7127..40.7139)
    }

    private fun expense(lat: Double, lon: Double, amount: Double, address: String) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = "M",
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis(),
        latitude = lat,
        longitude = lon,
        resolvedAddress = address
    )
}
