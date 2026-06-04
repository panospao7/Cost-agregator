package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.testCurrencyConverter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaSpendingEngineNormalizedTest {

    private val engine = AreaSpendingEngine()
    private val converter: CurrencyConverter = testCurrencyConverter()
    private val homeCurrency = "EUR"

    @Test
    fun computeNormalized_emptyInput_returnsEmpty() = runTest {
        val result = engine.computeNormalized(emptyList(), homeCurrency, converter)
        assertTrue("Expected empty list for empty input", result.isEmpty())
    }

    @Test
    fun computeNormalized_filtersFailedConversion() = runTest {
        val expenses = listOf(
            locatedExpense(
                lat = 40.7128, lon = -74.0060, amount = 100.0,
                address = "Shop, Manhattan, NY",
                status = ConversionStatus.FAILED
            ),
            locatedExpense(
                lat = 40.7128, lon = -74.0060, amount = 50.0,
                address = "Cafe, Manhattan, NY",
                status = ConversionStatus.CONVERTED
            )
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertEquals(1, result.size)
        assertEquals(50.0, result.first().aggregate.displayAmount, 0.01)
    }

    @Test
    fun computeNormalized_producesAreaNamesFromResolvedAddress() = runTest {
        val expenses = listOf(
            locatedExpense(
                lat = 40.7128, lon = -74.0060, amount = 100.0,
                address = "Starbucks, Manhattan, NY"
            ),
            locatedExpense(
                lat = 40.7580, lon = -73.9855, amount = 80.0,
                address = "Gym, Midtown, NY"
            )
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertEquals(2, result.size)
        val areaNames = result.map { it.areaName }.toSet()
        assertTrue("Expected Manhattan in area names", areaNames.contains("Manhattan"))
        assertTrue("Expected Midtown in area names", areaNames.contains("Midtown"))
    }

    @Test
    fun computeNormalized_groupsSameGridCell() = runTest {
        // Two expenses in the same grid cell → same area group
        val expenses = listOf(
            locatedExpense(
                lat = 40.7128, lon = -74.0060, amount = 100.0,
                address = "Shop, Manhattan, NY"
            ),
            locatedExpense(
                lat = 40.7130, lon = -74.0062, amount = 50.0,
                address = "Cafe, Manhattan, NY"
            )
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertEquals(1, result.size)
        val area = result.first()
        assertEquals("Manhattan", area.areaName)
        assertEquals(2, area.transactionCount)
        assertEquals(150.0, area.aggregate.displayAmount, 0.01)
        assertEquals(75.0, area.avgTransaction, 0.01)
    }

    @Test
    fun computeNormalized_sortsByDisplayAmountDescending() = runTest {
        val expenses = listOf(
            locatedExpense(
                lat = 40.7128, lon = -74.0060, amount = 200.0,
                address = "Store, AreaA, NY"
            ),
            locatedExpense(
                lat = 40.7580, lon = -73.9855, amount = 100.0,
                address = "Shop, AreaB, NY"
            ),
            locatedExpense(
                lat = 40.7800, lon = -73.9700, amount = 50.0,
                address = "Cafe, AreaC, NY"
            )
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertEquals(3, result.size)
        assertTrue(
            "Expected descending sort by displayAmount",
            result.zipWithNext { a, b -> a.aggregate.displayAmount >= b.aggregate.displayAmount }.all { it }
        )
    }

    private fun locatedExpense(
        lat: Double,
        lon: Double,
        amount: Double,
        address: String? = null,
        status: ConversionStatus = ConversionStatus.HOME_CURRENCY
    ) = LocatedMoneyExpense(
        expenseId = (lat * 1000 + lon * 1000).toLong(),
        latitude = lat,
        longitude = lon,
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        originalAmount = amount,
        originalCurrency = "EUR",
        conversionStatus = status,
        merchant = "Test Merchant",
        date = 1_000_000L,
        resolvedAddress = address
    )
}
