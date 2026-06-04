package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.testCurrencyConverter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelDetectionEngineNormalizedTest {

    private val engine = TravelDetectionEngine()
    private val converter: CurrencyConverter = testCurrencyConverter()
    private val homeCurrency = "EUR"
    private val dayMs = 24L * 60 * 60 * 1000

    @Test
    fun computeNormalized_returnsNullForFewExpenses() = runTest {
        val expenses = (1..4).map { i ->
            locatedExpense(
                lat = 40.7128 + i * 0.0001,
                lon = -74.0060 + i * 0.0001,
                date = 1_000L + i * dayMs,
                amount = 10.0
            )
        }
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertNull("Expected null for fewer than 5 expenses", result)
    }

    @Test
    fun computeNormalized_classifiesZonesCorrectly() = runTest {
        val expenses = homeSeed() + listOf(
            // Local (~11 km from home centroid)
            locatedExpense(lat = 40.8128, lon = -74.0060, date = 10_000L, amount = 20.0),
            // Travel (~100 km from home centroid)
            locatedExpense(lat = 41.6128, lon = -74.0060, date = 20_000L, amount = 30.0)
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertNotNull("Expected non-null insight", result)
        assertTrue("Expected local spend >= 20.0", result!!.localSpend >= 20.0)
        assertTrue("Expected travel spend >= 30.0", result.travelSpend >= 30.0)
    }

    @Test
    fun computeNormalized_conveniencePropertiesMatchDisplayAmount() = runTest {
        val expenses = homeSeed() + listOf(
            locatedExpense(lat = 40.8128, lon = -74.0060, date = 10_000L, amount = 25.0),
            locatedExpense(lat = 41.6128, lon = -74.0060, date = 20_000L, amount = 35.0)
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertNotNull("Expected non-null insight", result)
        assertEquals(
            "homeSpend should match homeAggregate.displayAmount",
            result!!.homeAggregate.displayAmount,
            result.homeSpend,
            0.01
        )
        assertEquals(
            "localSpend should match localAggregate.displayAmount",
            result.localAggregate.displayAmount,
            result.localSpend,
            0.01
        )
        assertEquals(
            "travelSpend should match travelAggregate.displayAmount",
            result.travelAggregate.displayAmount,
            result.travelSpend,
            0.01
        )
    }

    @Test
    fun computeNormalized_groupsIntoTrips() = runTest {
        val expenses = homeSeed() + listOf(
            locatedExpense(
                lat = 41.8, lon = -87.6, date = 100_000L, amount = 100.0,
                address = "Shop, Chicago, IL"
            ),
            locatedExpense(
                lat = 41.8, lon = -87.6, date = 100_000L + dayMs, amount = 150.0,
                address = "Cafe, Chicago, IL"
            ),
            locatedExpense(
                lat = 41.8, lon = -87.6, date = 100_000L + 2 * dayMs, amount = 200.0,
                address = "Store, Chicago, IL"
            )
        )
        val result = engine.computeNormalized(expenses, homeCurrency, converter)
        assertNotNull("Expected non-null insight", result)
        assertEquals("Expected 1 trip", 1, result!!.travelTrips.size)
        val trip = result.travelTrips.first()
        assertEquals("Expected trip total = 450.0", 450.0, trip.totalSpend, 0.01)
        assertEquals("Chicago", trip.destinationHint)
    }

    private fun homeSeed(): List<LocatedMoneyExpense> =
        (1..6).map { i ->
            locatedExpense(
                lat = 40.7128 + i * 0.0001,
                lon = -74.0060 + i * 0.0001,
                date = 1_000L + i * dayMs,
                amount = 10.0
            )
        }

    private fun locatedExpense(
        lat: Double,
        lon: Double,
        date: Long,
        amount: Double,
        address: String? = null,
        status: ConversionStatus = ConversionStatus.HOME_CURRENCY
    ) = LocatedMoneyExpense(
        expenseId = (lat * 1000 + lon * 1000 + date).toLong(),
        latitude = lat,
        longitude = lon,
        normalizedAmount = amount,
        normalizedCurrency = "EUR",
        originalAmount = amount,
        originalCurrency = "EUR",
        conversionStatus = status,
        merchant = "Test Merchant",
        date = date,
        resolvedAddress = address
    )
}
